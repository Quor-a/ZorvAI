// combo-single-lazy-a5.test.mjs — A5 撤销不变量（0.14.2）。
//
// A5 当年做的事（把单条 combo 的脚本体从 compose() 里挪到首次被请求时）在 0.1.7-rc.1 由**上游自己做掉了**：
// `buildCombo` 不再现场构造，而是返回两个 `lazyBody(...)` 生产者（上游注释原文：
// "Run one producer in the first requester's microtask, not off-thread, and share its settlement"），
// 并且上游还显式把**上一代已服务过的字节**留住（`responses.get(url) ?? this.responses.get(...)`）——
// 这比 A5「代际换手即清空、旧 URL 一律 404」的取舍更稳。保留 A5 等于在同一个热点上叠两套语义。
//
// 因此本测试守的是「上游的懒构造事实仍在」+「A5 没有半条留在树上」：
//   ① 登记表/实现里不再有 combo-single-lazy-A5；
//   ② 真产物夹具里 buildCombo 的两个载荷都是 lazy 生产者，且 compose() 不再逐条构造脚本体；
//   ③ 若上游哪天把懒构造改回现场构造，②会红——那时 A5 的取舍要重新评估，而不是照旧恢复补丁。
//
// 用法：node scripts/patches/tests/combo-single-lazy-a5.test.mjs
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { versionedFixture } from './lib/fixture.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = join(here, '..', '..', '..')
const failures = []
const check = (label, ok, detail) => {
  console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (ok || detail === undefined ? '' : ' -> ' + detail))
  if (!ok) failures.push(label)
}

const registry = JSON.parse(readFileSync(join(here, '..', 'registry.json'), 'utf8'))
const impl = readFileSync(join(here, '..', 'apply-patches.mjs'), 'utf8')
check('registry 里没有 combo-single-lazy-A5', !registry.patches.some((p) => p.id === 'combo-single-lazy-A5'))
check('apply-patches 里没有 A5 实现', !impl.includes("'combo-single-lazy-A5'"))
check('A5 的运行时符号不留存根（singleRecords 不在树上）', !impl.includes('singleRecords'))

const src = readFileSync(versionedFixture('dsh-client-modules', 'lib', 'index.js'), 'utf8')
const comboFn = src.slice(src.indexOf('function buildCombo('), src.indexOf('function buildBatch('))
check('上游 buildCombo 存在且只描述 combo（不构造载荷）', comboFn.length > 0 && comboFn.includes('function buildCombo('))
check('脚本体是 lazy 生产者（A5 的前提由上游满足）',
  /scriptBody:\s*lazyBody\(/.test(comboFn) && /sourceMapBody:\s*lazyBody\(/.test(comboFn))
check('compose() 不再逐条现场构造脚本体',
  !/buildCombo\([^)]*\)[^;]*;\s*\n\s*responses\.set\([^)]*Body/.test(src))

const lazy = src.match(/function lazyBody\(produce\) \{[\s\S]*?\n\}/)
check('lazyBody 语义可核（首次请求后才产出，且共享 settlement）',
  Boolean(lazy) && lazy[0].includes('Promise.resolve().then(produce)') && lazy[0].includes('result ??='))

if (failures.length) {
  console.error(`combo-single-lazy-a5: ${failures.length} 项失败`)
  process.exit(1)
}
console.log('combo-single-lazy-a5: 撤销不变量全部成立（上游已原生懒构造）')
