// combo-parallel-c3.test.mjs — C3 撤销不变量 + 探针降级语义仍可信（0.14.2）。
//
// C3（把 compose 的逐条装配分片到 worker 池）挂在 A3 注入的那条逐条循环上；A3 撤销后
// 那条循环就不存在了，而 rc.1 上游把 combo 载荷改成 `lazyBody` 之后，**启动路径上已无
// 「逐条重活」可分片**（每条只剩 utf8 解码 + 两次正则剥离，同机 55 条/4.6 MiB 合计 44 ms）。
// 所以本文件不再测分片，改守两件事：
//   ① C3 不得以「顺手把老补丁加回来」的形态复活（它的前置已不在）；
//   ② P1 探针在 A3/A5/C3 全部缺席时**仍必须打印字段**、并以 -1 / none 表示「没有这块」——
//      0.14.1 的实锤教训就是设备读到 `t_compose_total=-1` 却无人知，字段省掉与读数造假同级危险。
// 重开 C3 的触发条件写在下面 check 里，用真机读数判，不凭印象。
//
// 用法：node scripts/patches/tests/combo-parallel-c3.test.mjs
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
check('registry 里没有 combo-parallel-C3', !registry.patches.some((p) => p.id === 'combo-parallel-C3'))
check('apply-patches 里没有 C3 实现', !impl.includes("'combo-parallel-C3'"))
check('C3 的 worker 分片符号不留存根', !impl.includes('dshMobileComboShard') && !impl.includes('shards['))

const p1 = registry.patches.find((p) => p.id === 'combo-probe-P1')
check('P1 的 requires 只点名仍存在的补丁',
  Boolean(p1) && (p1.requires ?? []).every((id) => registry.patches.some((x) => x.id === id)),
  JSON.stringify(p1?.requires ?? null))
// A4 于 2026-09-25 退役（收益归零，见 combo-lazy-a4.test.mjs 头注），P1 的 requires 随之为空。
check('P1 不再依赖任何已退役补丁（A4 退役后 requires 为空）',
  JSON.stringify(p1?.requires ?? []) === JSON.stringify([]))

// ② 探针降级语义：A3/A5 读数缺席时打印哨兵值，绝不省字段。
const cacheLine = impl.slice(impl.indexOf('function dshMobileComboProbeCacheLine'), impl.indexOf('function dshMobileComboProbeSingles'))
check('comboCache 缺席时打印 comboCache=none（不是省略字段）', cacheLine.includes('"comboCache=none hits=0 misses=0"'))
const singles = impl.slice(impl.indexOf('function dshMobileComboProbeSingles'), impl.indexOf('function dshMobileComboProbeLoopLine'))
check('A5 计数缺席时打印 singles=-1（-1 教训不回潮）', singles.includes('? value : -1'))
const totalLine = (impl.match(/console\.log\(`\[perf\] TOTAL[^\n]*/) || [''])[0]
check('TOTAL 行字段集合固定（壳侧解析口径不随补丁增减）',
  ['calls=', 'totalMs=', 'firstAt=', 'singles='].every((f) => totalLine.includes(f))
  && totalLine.includes('dshMobileComboProbeLoopLine()'), totalLine.slice(0, 90))

// 重开 C3 的判据必须可执行：先看真机读数，再决定要不要把重活搬出主线程。
const src = readFileSync(versionedFixture('dsh-client-modules', 'lib', 'index.js'), 'utf8')
const composeFn = src.slice(src.indexOf('compose() {'), src.indexOf('\tflush('))
check('上游 compose 仍在主线程同步跑（重开 C3 的窗口存在与否由此判）',
  composeFn.length > 0 && !composeFn.includes('worker') && !composeFn.includes('Worker'))
console.log('提示：重开 C3 的触发条件是设备 `[perf] compose` 行 dur/totalMs 明显吃掉首屏——'
  + '读数走 scripts/perf/count-compose.mjs，别用本机数字代替设备数字。')

if (failures.length) {
  console.error(`combo-parallel-c3: ${failures.length} 项失败`)
  process.exit(1)
}
console.log('combo-parallel-c3: 撤销不变量 + 探针降级语义全部成立')
