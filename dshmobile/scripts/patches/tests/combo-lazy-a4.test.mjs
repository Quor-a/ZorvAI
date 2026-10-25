// combo-lazy-a4.test.mjs — A4 撤销不变量（0.14.2，2026-09-25 退役）。
//
// A4 当年做的事（把 compose() 从构造期推迟到首个图读者，并把启动期 flush 收敛为标脏）在 0.1.7-rc.1 上
// **收益约为 0、位置为负**：上游自己已把 combo 载荷惰性化（README「creates combo descriptors without
// building response bodies」；index.ts:384 lazyBody），裸树启动期只有 2 次 compose、单次数 ms
// （.deploy-tmp/retire-sweep/REPORT.md §3.1.2 同基线实测 2.27ms + 3.97ms；设备真值 5-9ms）。
// A4 实际只是省掉空表那次 + 把带真记录那次从构造期挪到首个请求路径（TTFB 侧更不利，
// 实测 HTTP−LISTEN 竖屏 557ms / 横屏 1472ms）。它换来 1 个 engine 补丁 + 7 个脆弱锚点 + P1 的 requires。
//
// 本测试守「上游的惰性事实仍在」+「A4 没有半条留在树上」，与 A5 的撤销不变量同形：
//   ① 登记表/实现里不再有 combo-lazy-A4；② A4 的运行时符号不留存根；
//   ③ 若上游哪天把惰性改回构造期现场构造，②会红——那时 A4 的取舍要重新评估，而不是照旧恢复补丁。
//
// 用法：node scripts/patches/tests/combo-lazy-a4.test.mjs
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { versionedFixture } from './lib/fixture.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const failures = []
const check = (label, ok, detail) => {
  console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (ok || detail === undefined ? '' : ' -> ' + detail))
  if (!ok) failures.push(label)
}

const registry = JSON.parse(readFileSync(join(here, '..', 'registry.json'), 'utf8'))
const impl = readFileSync(join(here, '..', 'apply-patches.mjs'), 'utf8')
check('registry 里没有 combo-lazy-A4', !registry.patches.some((p) => p.id === 'combo-lazy-A4'))
check('apply-patches 里没有 A4 实现', !impl.includes("'combo-lazy-A4'"))
check('A4 的运行时符号不留存根（ensureComposed / composeDirty 不在树上）',
  !impl.includes('ensureComposed') && !impl.includes('composeDirty') && !impl.includes('dsh-mobile combo lazy (A4)'))

// P1 曾以 A4 为前置；A4 退役后该 requires 必须一并消失，否则 apply-patches 会报「前提补丁无实现」。
const p1 = registry.patches.find((p) => p.id === 'combo-probe-P1')
check('P1 不再 requires 已退役的 A4',
  Boolean(p1) && !(p1.requires ?? []).includes('combo-lazy-A4'), JSON.stringify(p1?.requires ?? null))
check('P1 的 requires 为空或只点名仍存在的补丁',
  (p1?.requires ?? []).every((id) => registry.patches.some((x) => x.id === id)),
  JSON.stringify(p1?.requires ?? null))

// 上游惰性事实（A4 退役的依据本身必须仍成立，否则退役理由作废）。
const src = readFileSync(versionedFixture('dsh-client-modules', 'lib', 'index.js'), 'utf8')
check('上游 buildCombo 的脚本体仍是 lazy 生产者（A4 的收益归零依据）',
  /scriptBody:\s*lazyBody\(/.test(src))
const lazy = src.match(/function lazyBody\(produce\) \{[\s\S]*?\n\}/)
check('lazyBody 语义可核（首次请求后才产出，且共享 settlement）',
  Boolean(lazy) && lazy[0].includes('Promise.resolve().then(produce)') && lazy[0].includes('result ??='))
const ctor = src.slice(src.indexOf('class ClientModuleRegistry') >= 0 ? src.indexOf('class ClientModuleRegistry') : 0)
check('上游构造函数仍自行 compose 一次（裸树 ctor 首 compose 的事实来源）',
  /this\.composed = this\.compose\(\)/.test(src), '构造期 compose 锚点在场')

if (failures.length) {
  console.error('combo-lazy-a4: ' + failures.length + ' 项失败')
  process.exit(1)
}
console.log('combo-lazy-a4: 撤销不变量全部成立（上游惰性事实仍成立，A4 收益归零）')