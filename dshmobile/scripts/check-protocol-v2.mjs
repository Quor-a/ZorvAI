#!/usr/bin/env node
// check-protocol-v2.mjs — 控制协议 V2 往返与体积门禁（0.13.8 批 F / DESIGN-PROTOCOL-V2.md §S6）
//
// 为什么单独一个门禁：协议 V2 的收益全在**体积**与**结构自洽**上，两者都是"编译通过 + 人工看
// 一眼"发现不了的——列式化多带一个字段、广播规则失效、去重口径变宽，代码照样绿，只是回填报文
// 悄悄回到 30 KB（回到「约 150 节点即熔断」的旧世界）。这里把设计文档里的实测基线钉成断言。
//
// 断言来源：plugins/dsh-android-manage/test/protocol-v2.test.mjs（+ test/fixtures/ui-probe.*）。
// 该测试跑的是**构建产物**（lib/*.js），因此本门禁先做「lib 是否比 src 新」的时效检查，
// 过期就明确叫人重建，绝不拿旧产物判绿（假绿防线，与 0.13.7 polyfill 事件同一教训）。
// 【0.14.1 W1】mtime 只是**触发**：真伪由重建哈希裁决（scripts/lib/product-freshness.mjs）——
// 纯 mtime 会把 robocopy/检出/编辑器触碰误判成过期，而本门禁原来的处置是**硬判红**，
// 一个假阳性就能让整条聚合链停在这里（这正是「防线被结构性绕过」的另一种形态）。
//
// 退出码：0 = 通过；1 = 失败（构建链与 CI 以此拒打包/拒合并）。
import { existsSync, statSync, readdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
import { stalenessTrigger, arbitrateFreshness } from './lib/product-freshness.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = dirname(HERE)
const PLUGIN = join('plugins', 'dsh-android-manage')
const LIB = join(ROOT, PLUGIN, 'lib', 'protocol-v2.js')
const TEST = join(ROOT, PLUGIN, 'test', 'protocol-v2.test.mjs')
const FIXTURES = join(ROOT, PLUGIN, 'test', 'fixtures')

const fail = (msg) => {
  console.error('CHECK-PROTOCOL-V2 FAILED：' + msg)
  process.exit(1)
}

if (!existsSync(TEST)) fail(`测试文件缺席：${TEST}`)
if (!existsSync(join(FIXTURES, 'ui-probe.xml'))) fail(`探针 fixture 缺席：${join(FIXTURES, 'ui-probe.xml')}`)
if (!existsSync(join(FIXTURES, 'ui-probe.expect.json'))) fail(`冻结基线缺席：${join(FIXTURES, 'ui-probe.expect.json')}`)
if (!existsSync(LIB)) {
  fail(`构建产物缺席：${LIB}\n  先构建：cd ${PLUGIN} && npm run build（注入链会自动构建，纯门禁场景需手动）`)
}

/** lib 时效：任一 src/*.ts 比 lib/*.js 新只是**触发**，真伪由重建哈希裁决（0.14.1 W1）。 */
const newest = (dir, ext) => {
  let newestMs = 0
  let newestFile = ''
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    const st = statSync(full)
    if (st.isDirectory()) continue
    if (!name.endsWith(ext)) continue
    if (st.mtimeMs > newestMs) { newestMs = st.mtimeMs; newestFile = name }
  }
  return { ms: newestMs, file: newestFile }
}
const srcDir = join(ROOT, PLUGIN, 'src')
const libDir = join(ROOT, PLUGIN, 'lib')
const staleness = () => {
  const s = newest(srcDir, '.ts')
  const l = newest(libDir, '.js')
  return { s, l, stale: s.ms > l.ms }
}
const cur = staleness()
if (cur.stale) {
  const arb = arbitrateFreshness(join(ROOT, PLUGIN), { root: ROOT })
  if (arb.verdict === 'fresh') {
    console.log('NOTE  src/' + cur.s.file + ' 比 lib/' + cur.l.file + ' 新，但重建逐字节一致（mtime 假阳性，按新鲜继续）')
  } else if (arb.verdict === 'stale') {
    fail(`构建产物陈旧：${arb.detail}
  先构建：cd ${PLUGIN} && npm run build（构建链不代建插件，免得注入中途被重写）`)
  } else {
    // 重建不可裁决（无 typescript / 编译失败 / 产物在 lib 缺席）：本门禁的既有姿态是**不放过**——
    // 它判的正是「跑在 lib 产物上的往返」，产物可信度无从确认时不得判绿。
    fail(`构建产物过期：src/${cur.s.file} 比 lib/${cur.l.file} 新，且重建不可裁决：${arb.detail}
  先构建：cd ${PLUGIN} && npm install && npm run build（构建链不代建插件，免得注入中途被重写）`)
  }
}

// 跨语言 fixture 必须与 TS 编码器同源（壳侧 Kotlin 单测就是拿它比对的）
const gen = spawnSync(process.execPath, ['scripts/gen-protocol-v2-fixture.mjs', '--check'], { cwd: ROOT, encoding: 'utf8' })
if (gen.status !== 0) {
  console.error((gen.stdout ?? '') + (gen.stderr ?? ''))
  fail('跨语言 fixture 与生成器不一致：重跑 node scripts/gen-protocol-v2-fixture.mjs 并提交产物')
}
console.log('PASS  跨语言 fixture 与生成器同源')

const run = spawnSync(process.execPath, ['--test', '--test-reporter=spec', TEST], { cwd: ROOT, encoding: 'utf8' })
const out = (run.stdout ?? '') + (run.stderr ?? '')
const summary = out.split('\n').filter((l) => /^ℹ (tests|pass|fail)/.test(l)).join('  ')
if (run.status !== 0) {
  console.error(out.slice(-4000))
  fail(`协议 V2 测试未通过（${summary || 'no summary'}）`)
}
// review §2.3：node --test 全 .skip 时 exit 0——必须要求有效通过数 > 0（全 skip = 假绿）。
// 报告器无关：Node 21+ 默认 spec（"ℹ pass N"），Node 20 默认 TAP（"# pass N"）。
// 上面的 spawn 已显式钉 spec，这里再兼容 TAP 形态——任一默认变更都不会把通过数静默读成 0（假红）。
const passN = Number((/^ℹ pass (\d+)/m.exec(out) ?? /^# pass (\d+)/m.exec(out))?.[1] ?? '0')
if (passN <= 0) fail(`协议 V2 测试未产生有效通过数（全 skip = 假绿，pass=${passN}）`)
console.log('PASS  协议 V2 往返 + 体积门禁' + (summary ? `（${summary}）` : ''))
console.log('CHECK-PROTOCOL-V2 PASSED')