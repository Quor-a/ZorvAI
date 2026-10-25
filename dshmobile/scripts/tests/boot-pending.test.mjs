#!/usr/bin/env node
/**
 * boot-pending 撤销不变量（0.14.2）。
 *
 * G1（非官方包 pending 降级为告警）已撤销，理由不是「锚点漂了」而是「它要修的场景在产品里不存在」：
 * 上游 requiredStartupEntryIds 与本方 profile 行面无交集，且它的 `const failures = [];` 锚点会误匹配
 * `auditStartupEntries` 里的同名声明——一个会误匹配的锚点比没有锚点更危险。
 * 更关键的是 G1 的覆盖面本身是错的：真正打死启动的是 import 期失败，那条路径根本不经过
 * assertEntriesActivated（由 boot-third-party-isolation-G3 在挂载点接管，见其注释与回归测试）。
 *
 * 本测试守两条：
 *   ① G1 不再出现在登记表/实现里（防止有人「顺手把老补丁加回来」）；
 *   ② boot 期容错只有一个真源：G3。rc.1/rc.2 产物里 pending 仍然致命这一默认语义不得被我们改掉。
 *
 * 【G.0b 假绿修复（0.14.2-fx-2）】为什么必须输出 `ℹ pass N`：
 * 构建器（scripts/build-snapshot-013.mjs）用 `/ℹ pass (\d+)/` 解析本脚本的输出，
 * 而旧版只打印 `PASS  <label>`（不带 ℹ pass 汇总行）⇒ 构建器永远解析到 pass=0，
 * 叠加旧判据 `Number(fail)>0 || out.includes('[skip]')`（fail 亦为 0）⇒ **vacuous PASS**：
 * 脚本一行断言都没成立也照样放行。现补上机器可解析的汇总行，判据收紧为 `pass>0`。
 *
 * 用法：node scripts/tests/boot-pending.test.mjs [--boot <path>]
 *   --boot 给定时额外做一条产物面断言（目标文件必须可读且带 boot 编排审计缝）；
 *   目标文件不在场则打印 `[skip]` 并退 0 —— 构建器把 `[skip]` 判红（不得以「没找到」结案）。
 */
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = join(here, '..', '..')
const { versionedFixture } = await import(pathToFileURL(join(repoRoot, 'scripts', 'patches', 'tests', 'lib', 'fixture.mjs')).href)

const argv = process.argv.slice(2)
const bootIdx = argv.indexOf('--boot')
const bootTarget = bootIdx >= 0 && argv[bootIdx + 1] ? argv[bootIdx + 1] : undefined

let passes = 0
const failures = []
const check = (label, ok, detail) => {
  console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (ok || detail === undefined ? '' : ' -> ' + detail))
  if (ok) passes += 1
  else failures.push(label)
}

// ── 产物面前置：--boot 给定但文件不在场 ⇒ [skip]（构建器判红，不以「没找到」结案）──────
if (bootTarget !== undefined && !existsSync(bootTarget)) {
  console.log(`[skip] dsh-app-boot 目标文件不在场：${bootTarget}`)
  process.exit(0)
}

const registry = JSON.parse(readFileSync(join(repoRoot, 'scripts', 'patches', 'registry.json'), 'utf8'))
const impl = readFileSync(join(repoRoot, 'scripts', 'patches', 'apply-patches.mjs'), 'utf8')
check('registry 里没有 boot-pending-G1', !registry.patches.some((p) => p.id === 'boot-pending-G1'))
check('apply-patches 里没有 G1 实现', !impl.includes("'boot-pending-G1'"))
check('G1 的 marker 已从实现里彻底消失', !impl.includes('dsh-mobile boot tolerance (G1)'))

const g3 = registry.patches.find((p) => p.id === 'boot-third-party-isolation-G3')
check('boot 期容错唯一真源是 G3（scope=engine，目标 dsh-app-boot）',
  Boolean(g3 && g3.scope === 'engine' && g3.target.includes('dsh-app-boot')))
check('G3 的隔离式挂载器在实现里（容错确实接上了）', impl.includes('dshMobileMountRootIncludeTolerant'))

// 真产物面：未打补丁的夹具里 pending 概念仍在（⇒ 撤销 G1 不是「上游顺手把这条路径删了」，
// 而是「我们不再改写它的默认语义」）；一旦上游把 pending 变成默认容错，本断言红，重开评估。
const boot = readFileSync(versionedFixture('dsh-app-boot', 'lib', 'index.js'), 'utf8')
check('夹具仍报告 pending 等待（撤销前提可复核）', /pending \(waiting for/.test(boot))
check('夹具未自带我们的容错标记（纯净夹具=未打补丁）', !boot.includes('dsh-mobile'))

// ── 产物面断言（--boot 给定时）────────────────────────────────────────────────
// 版本无关：rc.1 导出 assertEntriesActivated、rc.2 改名为 auditStartupEntries——上游换名不该让
// 本测试变成「导入了 undefined 然后崩」。故只断言「该产物带 boot 编排审计缝且可读」。
if (bootTarget !== undefined) {
  const artifact = readFileSync(bootTarget, 'utf8')
  check('产物带 boot 编排审计缝（assertEntriesActivated 或 auditStartupEntries）',
    artifact.includes('assertEntriesActivated') || artifact.includes('auditStartupEntries'))
}

// ── 机器可解析汇总（构建器按 /ℹ pass (\d+)/ 与 /ℹ fail (\d+)/ 读取）───────────
console.log(`ℹ pass ${passes}`)
console.log(`ℹ fail ${failures.length}`)

if (failures.length) {
  console.error(`boot-pending: ${failures.length} 项失败`)
  process.exit(1)
}
console.log('boot-pending: 撤销不变量全部成立')
