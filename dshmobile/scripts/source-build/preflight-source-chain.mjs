#!/usr/bin/env node
// 来源审计链的**派发前本地预检**。
//
// 为什么存在：远程 `build-apk-source` 一次约 60-90 分钟，而它近期判红的那几类问题
// （市场补丁锚点失配、期望补丁集过期、适配器锚点改动、与上游脱钩）**全部可以在本地提前复现**。
// 先跑本脚本再派发，把「远程跑到第 10 分钟才发现锚点没命中」这类往返省掉。
//
// 覆盖（都是本地可复现、且历史上真的判红过的面）：
//   1. 与上游的同步性（落后主流即提示先合并——坑 204/204 都发生在合并之后）
//   2. 登记表补丁对仓库镜像自洽（apply-patches --check）
//   3. 市场插件链：按 workflow 里钉的 URL+sha256 取发布产物 → 解包 → 打补丁 → 与镜像逐字节比对
//   4. 来源链单测（预设载体漂移守卫、overlay 钉还原、锁文件根声明、vendor 锁对齐）
//   5. 静态门禁（执行地图）
//
// 用法：node scripts/source-build/preflight-source-chain.mjs [--no-network]
//   退出码 0 = 全过（可派发）；非 0 = 有面未过（先修再派发）。
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, cpSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { basename, join, resolve } from 'node:path'

const ROOT = resolve(import.meta.dirname, '..', '..')
const WORKFLOW = join(ROOT, '.github', 'workflows', 'build-apk-source.yml')
const CACHE = join(ROOT, '.deploy-tmp', 'component-sources')
const NO_NETWORK = process.argv.includes('--no-network')
const sha256 = (value) => createHash('sha256').update(value).digest('hex')
const normalize = (text) => text.replaceAll('\r\n', '\n')

let failures = 0
const ok = (label, detail = '') => console.log(`  PASS  ${label}${detail ? `：${detail}` : ''}`)
const fail = (label, detail = '') => { failures += 1; console.error(`  FAIL  ${label}${detail ? `：${detail}` : ''}`) }

/** 从 workflow 里读真源（不在本脚本里重复钉版本/哈希：两处登记必漂移）。 */
function workflowFacts() {
  const text = readFileSync(WORKFLOW, 'utf8')
  const url = /https:\/\/registry\.npmjs\.org\/dshmarketplace-plugin\/-\/dshmarketplace-plugin-([\w.-]+)\.tgz/.exec(text)
  const sha = /printf '%s  %s\\n' '([0-9a-f]{64})' "\$marketplace_tarball"/.exec(text)
  const version = /require\('\.\/vendor\/dshmarketplace-plugin\/package\.json'\)\.version"\)" = "([\w.-]+)"/.exec(text)
  if (!url || !sha || !version) throw new Error('workflow 真源解析失败（URL / sha256 / 版本三处都必须能在文件里读到）')
  return { url: url[0], tarball: `dshmarketplace-plugin-${url[1]}.tgz`, expectedSha256: sha[1], expectedVersion: version[1] }
}

function stepSyncWithUpstream() {
  console.log('[1/5] 与上游同步性')
  if (NO_NETWORK) return ok('跳过（--no-network）')
  try {
    execFileSync('git', ['fetch', '--quiet', 'upstream'], { cwd: ROOT, stdio: 'pipe' })
  } catch {
    return fail('git fetch upstream 失败（网络？）—— 无法判断是否落后主流')
  }
  const behind = Number(execFileSync('git', ['rev-list', '--count', 'upstream/main..HEAD'], { cwd: ROOT, encoding: 'utf8' }).trim())
  const ahead = Number(execFileSync('git', ['rev-list', '--count', 'HEAD..upstream/main'], { cwd: ROOT, encoding: 'utf8' }).trim())
  if (ahead > 0) return fail('落后 upstream/main', `${ahead} 个提交未合入——先合并再派发（合并会改变链的输入，见坑 204/204）`)
  ok('未落后 upstream/main', `领先 ${behind} 个提交`)
}

function stepRegistryAgainstMirror() {
  console.log('[2/5] 登记表补丁对仓库镜像自洽（apply-patches --check）')
  const out = execFileSync('node', [join(ROOT, 'scripts', 'patches', 'apply-patches.mjs'), 'vendor', '--check'], { cwd: ROOT, encoding: 'utf8' })
  const allOk = /apply-patches: ALL OK（\d+\/\d+/.test(out)
  if (!allOk) return fail('补丁 check 档未全绿', out.trim().split('\n').slice(-3).join(' | '))
  ok('全部登记补丁在镜像上判定为已应用', /ALL OK（[^）]*）/.exec(out)?.[0] ?? '')
}

function stepMarketplaceChain() {
  console.log('[3/5] 市场插件链：发布产物 + 注册表补丁 == 仓库镜像')
  let facts
  try { facts = workflowFacts() } catch (e) { return fail('workflow 真源解析', e.message) }
  const tarball = join(CACHE, facts.tarball)
  mkdirSync(CACHE, { recursive: true })
  if (!existsSync(tarball)) {
    if (NO_NETWORK) return fail('发布产物不在缓存且指定了 --no-network', tarball)
    execFileSync('curl', ['--fail', '--location', '--retry', '3', '--max-time', '300', facts.url, '--output', tarball], { stdio: 'pipe' })
  }
  const actualSha256 = sha256(readFileSync(tarball))
  if (actualSha256 !== facts.expectedSha256) return fail('发布产物哈希与 workflow 钉的不一致', `${actualSha256} ≠ ${facts.expectedSha256}`)
  ok('发布产物哈希与 workflow 一致', actualSha256.slice(0, 16))

  const scratch = mkdtempSync(join(tmpdir(), 'dsh-preflight-'))
  try {
    const vendor = join(scratch, 'vendor')
    mkdirSync(join(vendor, 'dshmarketplace-plugin'), { recursive: true })
    // 只比市场插件：其余 vendor 包原样带过去，免得 undo-* 补丁因缺文件误报。
    cpSync(join(ROOT, 'vendor', 'dsh-undo-savepoint'), join(vendor, 'dsh-undo-savepoint'), { recursive: true })
    const target = join(vendor, 'dshmarketplace-plugin')
    // 归档走 stdin、解到 cwd：路径里带盘符冒号时 GNU tar 会把它当远端主机，Windows 上必炸；
    // 干脆不给 tar 传任何路径参数（cwd 由 Node 设）。
    execFileSync('tar', ['-xzf', '-', '--strip-components=1'], { cwd: target, input: readFileSync(tarball) })
    const version = JSON.parse(readFileSync(join(target, 'package.json'), 'utf8')).version
    if (version !== facts.expectedVersion) return fail('发布产物版本与 workflow 断言不一致', `${version} ≠ ${facts.expectedVersion}`)

    const adapter = execFileSync('node', [join(ROOT, 'scripts', 'source-build', 'apply-source-marketplace-patches.mjs'), vendor, '--apply'], { cwd: ROOT, encoding: 'utf8' })
    if (!/ALL OK/.test(adapter)) return fail('市场补丁施加未报 ALL OK', adapter.trim().split('\n').slice(-2).join(' | '))

    for (const file of ['lib/index.js', 'lib/client.js']) {
      const built = normalize(readFileSync(join(target, file), 'utf8'))
      const mirror = normalize(execFileSync('git', ['show', `HEAD:vendor/dshmarketplace-plugin/${file}`], { cwd: ROOT, encoding: 'utf8' }))
      if (sha256(built) !== sha256(mirror)) {
        return fail(`补丁后 ${file} 与仓库镜像不一致`, `${sha256(built).slice(0, 16)} ≠ ${sha256(mirror).slice(0, 16)}`)
      }
    }
    ok('发布产物 + 注册表补丁 == 仓库镜像（逐字节）', 'lib/index.js、lib/client.js')
  } catch (error) {
    fail('市场插件链复现失败', String(error?.message ?? error).split('\n')[0])
  } finally {
    rmSync(scratch, { recursive: true, force: true })
  }
}

function stepSourceBuildTests() {
  console.log('[4/5] 来源链单测')
  const suites = [
    'source-chain-rerun.test.mjs',
    'preset-carriers.test.mjs',
    'restore-overlay-pins.test.mjs',
    'reconcile-engine-patch-copies.test.mjs',
    'normalize-snapshot.test.mjs',
    'check-package-lock-roots.test.mjs',
    'reconcile-harness-vendor-lock.test.mjs',
    'check-android-native-runtime-packages.test.mjs',
  ]
  const failed = []
  for (const suite of suites) {
    try {
      execFileSync('node', ['--test', join(ROOT, 'scripts', 'source-build', suite)], { cwd: ROOT, stdio: 'pipe' })
    } catch { failed.push(suite) }
  }
  if (failed.length) return fail('单测判红', failed.join(', '))
  ok('全部通过', `${suites.length} 个用例文件`)
}

function stepStaticGates() {
  console.log('[5/5] 静态门禁')
  try {
    execFileSync('node', [join(ROOT, 'scripts', 'check-code-map.mjs')], { cwd: ROOT, stdio: 'pipe' })
    ok('check-code-map')
  } catch { fail('check-code-map 判红') }
}

console.log('来源链派发前预检（本地可复现面）')
stepSyncWithUpstream()
stepRegistryAgainstMirror()
stepMarketplaceChain()
stepSourceBuildTests()
stepStaticGates()

if (failures > 0) {
  console.error(`\n预检未过（${failures} 项）——先修再派发远程：一次远程往返 60-90 分钟。`)
  process.exit(1)
}
console.log('\n预检全过，可以派发远程来源构建。')
