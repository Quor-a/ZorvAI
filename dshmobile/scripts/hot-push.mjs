#!/usr/bin/env node
// hot-push.mjs — 开发期热更新：把本地构建产物推进设备快照树并重启引擎，无需重新打包 APK。
//
// 为什么需要它：只改「快照内 runtime」（插件 lib/*.js、client.js、引擎树）时，重新打包 APK
// （全量门禁 + 双 ABI + 装机 + 快照重解压）要十几分钟，而本脚本是秒级循环：
//   本地 npm run build -> adb push -> run-as cp 进快照树 -> 重启引擎 -> 验证 -> 满意后再正式打包。
//
// 适用面：仅「已可注入、且引擎从快照树读取」的产物（@dsh-android 插件 lib/**、client.js）。
// 不适用：壳侧 Kotlin（.kt 必须重新编译打包）——那类改动只能走 build-apk-013.ps1。
//
// 用法：
//   node scripts/hot-push.mjs --serial 127.0.0.1:16416 --plugin dsh-android-vdisplay
//   node scripts/hot-push.mjs --serial 127.0.0.1:16416 --plugin dsh-client-ui-responsive --pkg @dsh-android/dsh-client-ui-responsive
//   node scripts/hot-push.mjs ... --restart            # 推进后重启引擎
//   node scripts/hot-push.mjs ... --dry-run            # 只打印将要复制的文件
//
// 退出码：0 = 全部推送成功；非 0 = 失败（不静默）。
import { existsSync, readdirSync, statSync } from 'node:fs'
import { join, dirname, relative, basename } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = dirname(HERE)
const argv = process.argv.slice(2)
const argOf = (name) => { const i = argv.indexOf('--' + name); return i >= 0 ? argv[i + 1] : undefined }
const SERIAL = argOf('serial')
const PLUGIN = argOf('plugin')
const PKG = argOf('pkg')
const RESTART = argv.includes('--restart')
const DRY = argv.includes('--dry-run')
const APP_ID = argOf('app') ?? 'com.ai.assistance.quro.dshmobile'

if (!SERIAL || !PLUGIN) {
  console.error('用法: node scripts/hot-push.mjs --serial <adb-serial> --plugin <dir> [--pkg @scope/name] [--restart] [--dry-run]')
  process.exit(2)
}
// 包名默认从「插件目录名」推导：plugins/dsh-android-bridge -> @dsh-android/dsh-android-bridge。
const PLUGIN_BASE = basename(String(PLUGIN).replace(/[\\/]+$/, ''))
const PKG_NAME = PKG ?? '@dsh-android/' + PLUGIN_BASE
const SRC = join(ROOT, PLUGIN)
const LIB = join(SRC, 'lib')
if (!existsSync(LIB)) {
  console.error('构建产物缺席：' + relative(ROOT, LIB) + '（先在该目录 npm run build）')
  process.exit(1)
}

const adb = (args, opts = {}) => spawnSync('adb', ['-s', SERIAL, ...args], { encoding: 'utf8', ...opts })
/** 快照内该包的落点（两个 profile 都要覆盖：引擎按会话装配 profile 读树）。 */
const PROFILE_ROOTS = [
  'files/home/.dsh/profiles/web/node_modules/' + PKG_NAME,
  'files/home/.dsh/profiles/headless/node_modules/' + PKG_NAME,
]

function walk(dir, prefix = '') {
  const out = []
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    const rel = prefix ? prefix + '/' + name : name
    if (statSync(full).isDirectory()) out.push(...walk(full, rel))
    else out.push(rel)
  }
  return out
}
const files = walk(LIB)
console.log('待推送 ' + files.length + ' 个文件：' + PLUGIN + '/lib -> ' + PKG_NAME)
for (const f of files) console.log('  ' + f)
if (DRY) { console.log('（--dry-run：未执行任何复制）'); process.exit(0) }

const stage = '/data/local/tmp/dsh-hot-push'
adb(['shell', 'rm -rf ' + stage])
adb(['shell', 'mkdir -p ' + stage])
const push = adb(['push', LIB, stage + '/lib'])
if (push.status !== 0) {
  console.error('adb push 失败：' + (push.stderr || push.stdout || ''))
  process.exit(1)
}
/* 实修（0.14.2 rc.2）：adb push 在 /data/local/tmp 建的目录权限是 0771（drwxrwx--x）——others 只有
 * 「可穿越」没有「可读」，而下面用 `cp -r <stage>/lib/.` 复制时 cp 必须**列目录内容**，于是以 run-as
 * 身份（u0_aXX，属 others）读 stage 恒得 EACCES：
 *   cp: /data/local/tmp/dsh-hot-push/lib/.: Permission denied
 * 本脚本因此长期「自称可用、实际每个落点必失败」——本轮 adb 实测暴露。
 * 修法：把 stage 放开到 0755（只读拷贝源，不需要写权限给 run-as）。 */
const chmod = adb(['shell', 'chmod -R 755 ' + stage])
if (chmod.status !== 0) {
  console.error('stage 权限放开失败：' + (chmod.stderr || chmod.stdout || ''))
  process.exit(1)
}

let failures = 0
for (const root of PROFILE_ROOTS) {
  const target = root + '/lib'
  // 用 run-as 复制到应用私有目录（adb push 无法直接写 /data/data）。
  const script = 'rm -rf ' + target + ' && mkdir -p ' + target + ' && cp -r ' + stage + '/lib/. ' + target + '/ && echo OK'
  const r = adb(['shell', 'run-as ' + APP_ID + ' sh -c ' + JSON.stringify(script)])
  const ok = r.status === 0 && String(r.stdout).includes('OK')
  console.log((ok ? 'PASS  ' : 'FAIL  ') + target)
  if (!ok) { failures++; console.error('  ' + (r.stderr || r.stdout || '').trim()) }
}
adb(['shell', 'rm -rf ' + stage])
if (failures > 0) { console.error('HOT-PUSH FAILED（' + failures + ' 个落点）'); process.exit(1) }
console.log('HOT-PUSH OK（' + files.length + ' 文件 × ' + PROFILE_ROOTS.length + ' profile）')

if (RESTART) {
  console.log('重启应用以让引擎重读快照树…')
  adb(['shell', 'am force-stop ' + APP_ID])
  adb(['shell', 'am start -n ' + APP_ID + '/.MainActivity'])
  console.log('已重启（等引擎 LISTEN 后自行验证）')
}
