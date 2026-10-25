// combo-cache-a3.test.mjs — A3 撤销不变量（0.14.2），并守「撤销理由仍然成立」这条可复核性。
//
// A3（构建期把 identity combo source + section map 预计算成 .combo-cache，运行期按 sha256 查表）
// 在 0.1.5 上是净收益：compose() 启动期跑 9-14 次，每次对 90 条 bundle 现场 `comboSource` +
// `identitySectionMap`（后者把整份 source 塞进 sourcesContent 并逐行数 mappings）。
// 0.1.7-rc.1 上游把 combo 载荷改成懒构造后，**启动路径上只剩** `prepareSource`
// （utf8 解码 + 两次尾注释剥离）；identity map 只在 `.map` 端点被请求时才构造
// （Android 上 devtools 不开，等于不发生）。同机同批字节实测（55 个 rc.1 client.js / 4.6 MiB）：
//   上游 boot 路径        44 ms
//   A3 现形态（查表）    129 ms   ← 还要 JSON.parse 5.09 MiB 清单 + 逐条读 <sha>.map
//   A3 收窄形态（不读 map） 78 ms
// ⇒ A3 在 rc.1 上是**可测量的净亏**，而且 5 MiB 清单本身是产物死重（打包/传输/解包都付）。
// 撤销不是「锚点找不到所以删」，是「量出来它让体验变差所以删」——本测试把这个结论钉住。
//
// 用法：node scripts/patches/tests/combo-cache-a3.test.mjs
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
import { versionedFixture } from './lib/fixture.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = join(here, '..', '..', '..')
const failures = []
const check = (label, ok, detail) => {
  console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (ok || detail === undefined ? '' : ' -> ' + detail))
  if (!ok) failures.push(label)
}

// ① 补丁面：A3 不得复活
const registry = JSON.parse(readFileSync(join(here, '..', 'registry.json'), 'utf8'))
const impl = readFileSync(join(here, '..', 'apply-patches.mjs'), 'utf8')
check('registry 里没有 combo-cache-A3', !registry.patches.some((p) => p.id === 'combo-cache-A3'))
check('apply-patches 里没有 A3 实现', !impl.includes("'combo-cache-A3'"))
check('A3 的运行时符号不留存根', !impl.includes('dshMobileComboCacheLookup') && !impl.includes('DSH_MOBILE_COMBO_CACHE_STATS'))

// ② 链路面：写半边不得再被调用（否则快照里躺着 5 MiB 没人读的清单）
for (const rel of ['scripts/build-snapshot-013.mjs', 'scripts/build-apk.mjs', 'scripts/build-apk-013.ps1', 'scripts/inject-all.py']) {
  const p = join(repoRoot, rel)
  const text = existsSync(p) ? readFileSync(p, 'utf8') : ''
  const code = text.split('\n').filter((l) => !l.trim().startsWith('//') && !l.trim().startsWith('#')).join('\n')
  check(rel + ' 不再调用 combo 预计算/不接收 delta 目录',
    !code.includes('combo-precompute.mjs') && !code.includes('combo-cache-delta'))
}

// ③ 撤销理由仍可复核：identity map 的构造必须留在「只有 .map 请求才走」的那条路径上。
//    一旦上游把它挪回 boot 路径（例如又改成现场 compose 全表），上面的 44 ms 结论就不成立了，
//    本断言会红——那时该重新测量并决定是否重开预计算，而不是照抄今天的数字。
const src = readFileSync(versionedFixture('dsh-client-modules', 'lib', 'index.js'), 'utf8')
const scriptFn = src.slice(src.indexOf('function buildComboScript('), src.indexOf('function buildComboSourceMap('))
const mapFn = src.slice(src.indexOf('function buildComboSourceMap('), src.indexOf('function buildCombo('))
check('boot 路径（buildComboScript）不构造 identity map', !scriptFn.includes('identitySectionMap') && !scriptFn.includes('newlineCount'))
check('identity map 只在 .map 端点路径上构造', mapFn.includes('identitySectionMap') && mapFn.includes('newlineCount'))
check('A3 的预计算模块已随补丁一并移除（不留无人调用的死代码）',
  !existsSync(join(repoRoot, 'scripts', 'lib', 'combo-precompute.mjs'))
  && !existsSync(join(repoRoot, 'dsh-mobile-apk', 'scripts', 'lib', 'combo-precompute.mjs')))

// ④ 回流门禁自身可判红（新语义：产物里出现 .combo-cache 即红）
const gate = spawnSync(process.execPath, [join(repoRoot, 'scripts', 'check-combo-cache.mjs'), '--self-test'], { encoding: 'utf8' })
check('check-combo-cache --self-test 通过（回流判据有反证）', gate.status === 0,
  (gate.stdout + gate.stderr).trim().split('\n').slice(-1)[0])

if (failures.length) {
  console.error(`combo-cache-a3: ${failures.length} 项失败`)
  process.exit(1)
}
console.log('combo-cache-a3: 撤销不变量全部成立（含收益反向复核实测依据）')
