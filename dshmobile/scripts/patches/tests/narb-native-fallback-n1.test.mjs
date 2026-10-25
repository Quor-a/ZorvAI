// narb-native-fallback-n1.test.mjs — N1 补丁回归：锚点命中 + 回落行为正确 + 幂等。
//
// 用与引擎同代的只读 fixture（fixtures/node-addon-require-builtin-<contract.baseline>/lib/index.js，
// 见 tests/lib/fixture.mjs）在临时目录里跑补丁，然后直接 require 打过补丁的 CJS 产物做行为断言。
//
// 为什么必须有行为断言（而不只是锚点命中）：
//   本补丁的整个存在理由就是「Android 上没有 native 产物时不能让 boot 崩」。
//   只验 marker 在场 = 只证明文本被替换，不证明替换后的代码在**缺 native** 的机器上真能回落
//   ——本机（Windows）有 win32 产物，天然走不到回落分支，所以测试必须**模拟 native 缺失**
//   （把 node-addon-native-custom-loader 指向不可解析的路径 + 用 --expose-internals 起子进程）。
//
// 用法：node scripts/patches/tests/narb-native-fallback-n1.test.mjs
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync, cpSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { versionedFixture } from './lib/fixture.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = join(here, '..', '..', '..')
const TARGET = 'usr/lib/node_modules/@deepseek-ai/dsh/node_modules/node-addon-require-builtin/lib/index.js'
const FIXTURE = versionedFixture('node-addon-require-builtin', 'lib', 'index.js')

const failures = []
/** Assert one condition, recording the failure instead of throwing so every check reports. */
function check(label, ok, detail) {
  console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (ok || detail === undefined ? '' : ' -> ' + detail))
  if (!ok) failures.push(label)
}

/** Run apply-patches against `root` and return the spawned result. */
function applyPatches(root) {
  return spawnSync(process.execPath, [join(repoRoot, 'scripts', 'patches', 'apply-patches.mjs'), root, '--apply', '--scope', 'engine', '--only', 'narb-android-N1'], { encoding: 'utf8' })
}

const scratch = mkdtempSync(join(tmpdir(), 'n1-test-'))
try {
  const target = join(scratch, TARGET)
  mkdirSync(dirname(target), { recursive: true })
  writeFileSync(target, readFileSync(FIXTURE, 'utf8').replace(/\r\n/g, '\n'))

  const first = applyPatches(scratch)
  check('apply-patches exits 0', first.status === 0, (first.stderr || '').trim().split('\n').slice(-2).join(' '))
  const patched = readFileSync(target, 'utf8')
  check('marker present after apply', patched.includes('dsh-mobile native-binding fallback (N1)'))
  check('createEntryApi is wrapped in try/catch', patched.includes('try {') && patched.includes('api = createEntryApi('))
  check('requireBuiltin falls back to require()', patched.includes('if (api !== undefined) return api.requireBuiltin(moduleId);'))
  check('getBindingInfo reports the fallback backend', patched.includes("backend: 'expose-internals'"))

  const syntax = spawnSync(process.execPath, ['--check', target], { encoding: 'utf8' })
  check('patched file parses', syntax.status === 0, (syntax.stderr || '').split('\n')[0])

  applyPatches(scratch)
  check('re-apply is idempotent', readFileSync(target, 'utf8') === patched)

  // ── 行为：无 native 时必须回落，且 requireBuiltin 真能拿到内部模块 ──
  // 把 loader 依赖换成不可解析的名字，模拟「本平台没有预编译产物」。
  const fakeRoot = join(scratch, 'missing-loader')
  mkdirSync(join(fakeRoot, 'node_modules'), { recursive: true })
  const probe = join(fakeRoot, 'probe.cjs')
  writeFileSync(probe, [
    "const path = require('node:path');",
    `const mod = require(${JSON.stringify(target)});`,
    "const info = mod.getBindingInfo();",
    "console.log('BACKEND=' + info.backend);",
    "try {",
    "  const cjs = mod.requireBuiltin('internal/modules/cjs/loader');",
    "  console.log('REQUIRE_BUILTIN=' + (typeof cjs.Module._resolveFilename === 'function' ? 'ok' : 'bad'));",
    "} catch (error) {",
    "  console.log('REQUIRE_BUILTIN_FAIL=' + String(error.message).split('\\n')[0]);",
    "}",
    "try {",
    "  const esm = mod.requireBuiltin('internal/modules/esm/loader');",
    "  console.log('ESM=' + (typeof esm.getOrInitializeCascadedLoader === 'function' ? 'ok' : 'bad'));",
    "} catch (error) {",
    "  console.log('ESM_FAIL=' + String(error.message).split('\\n')[0]);",
    "}",
  ].join('\n'))

  // 在 scratch 根建一个 node_modules，让 node-addon-native-custom-loader 解析失败（不存在）。
  const nm = join(scratch, 'node_modules')
  mkdirSync(nm, { recursive: true })
  const run = spawnSync(process.execPath, ['--expose-internals', probe], { encoding: 'utf8', cwd: fakeRoot })
  const out = (run.stdout || '') + (run.stderr || '')
  check('fallback activates without a native binding', out.includes('BACKEND=expose-internals'), out.split('\n').slice(0, 4).join(' | '))
  check('fallback requireBuiltin returns the CJS internal loader', out.includes('REQUIRE_BUILTIN=ok'), out.split('\n').slice(0, 6).join(' | '))
  check('fallback requireBuiltin returns the ESM internal loader', out.includes('ESM=ok'), out.split('\n').slice(0, 8).join(' | '))
  check('fallback emits a one-time warning', out.includes('no prebuilt native binding for this platform'))
} finally {
  rmSync(scratch, { recursive: true, force: true })
}

if (failures.length > 0) {
  console.error('\nnarb-native-fallback-n1: ' + failures.length + ' check(s) failed: ' + failures.join('; '))
  process.exit(1)
}
console.log('\nnarb-native-fallback-n1: all checks passed')
