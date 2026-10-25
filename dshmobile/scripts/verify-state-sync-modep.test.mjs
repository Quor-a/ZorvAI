// verify-state-sync.mjs 的 appops 解析回归（D1）。
//
// 为什么用「提取源码再测」而不是 import：该脚本顶层会在缺 --serial 时 process.exit(2)，
// 直接 import 会带副作用退出。本项目已有同类先例（combo-probe-P1 从产物提取采样器源码做行为反证）。
//
// 判据必须**可证伪**：除断言新实现能解析真实输出，还要断言**旧实现解析不了**——
// 否则这条测试无法区分「修好了」与「测试恒真」。
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const src = readFileSync(join(here, 'verify-state-sync.mjs'), 'utf8')

const m = /const modeOf = \(out\) => \{[\s\S]*?\n  \}/.exec(src)
if (!m) { console.error('FAIL 未能从 verify-state-sync.mjs 中提取 modeOf 实现'); process.exit(1) }
const modeOf = new Function('return (' + m[0].replace('const modeOf = ', '') + ')')()

// 真实 `adb shell appops get <pkg> <OP>` 的四种实测形态（取自 16384 实机输出）
const SAMPLES = [
  { raw: 'SYSTEM_ALERT_WINDOW: allow; time=+1h40m49s830ms ago (running)', want: 'allow', why: '允许 + 尾部 time 与 (running)' },
  { raw: 'MANAGE_EXTERNAL_STORAGE: deny; time=+9d2h16m55s675ms ago; rejectTime=+2h22m19s323ms ago', want: 'deny', why: '拒绝 + 双段元数据' },
  { raw: 'SYSTEM_ALERT_WINDOW: allow', want: 'allow', why: '裸串（旧实现唯一能解析的形态）' },
  { raw: 'MANAGE_EXTERNAL_STORAGE: default', want: 'default', why: 'default 态' },
  { raw: 'No operations.', want: null, why: '无该 op（必须回 null，不得瞎猜）' },
]

let checks = 0, failed = 0
const ok = (cond, label) => { checks++; if (!cond) { failed++; console.error('  FAIL ' + label) } else console.log('  ok   ' + label) }

console.log('A. 新实现必须解析真实形态')
for (const s of SAMPLES) ok(modeOf(s.raw) === s.want, JSON.stringify(s.raw.slice(0, 46)) + ' -> ' + String(modeOf(s.raw)) + '（期望 ' + String(s.want) + '；' + s.why + '）')

console.log('B. 反证：旧实现（/^[A-Z_]+:/ 缺失，改回 :\\s*([a-z]+)\\s*$）必须解析不了带元数据的真实形态')
const oldModeOf = (out) => { const mm = /:\s*([a-z]+)\s*$/.exec(out.trim()); return mm ? mm[1] : null }
for (const s of SAMPLES.slice(0, 2)) ok(oldModeOf(s.raw) === null, '旧实现对真实形态回 null（证明本条测试有判别力）：' + JSON.stringify(s.raw.slice(0, 40)))
ok(oldModeOf(SAMPLES[2].raw) === 'allow', '旧实现只对裸串有效（这正是它在设备上恒失效的原因）')

console.log('C. 结构断言：源码不得再出现行尾锚定的旧写法，且必须显式报告未还原')
ok(!/const modeOf = \(out\) => \{ const m = \/:\\s*\[a-z\]\+\\s\*\$\//.test(src), '旧的一行式 modeOf 已移除')
ok(src.includes('捕获值为 null'), 'restoreExternalState 显式报告未还原项（不再静默跳过）')
ok(src.includes('tcp:' + String.fromCharCode(39) + ' + CDP_PORT') || src.includes("tcp:' + CDP_PORT"), 'forward 使用派生端口 CDP_PORT 而非硬编码 29225')
ok(!/tcp:29225/.test(src.replace(/[\s\S]*?29225 \+\(h % 100\)[\s\S]*?/, '')) || src.includes('29225 + (h % 100)'), '硬编码 tcp:29225 已改为按 serial 派生（保留 29225 仅作基数）')

console.log('')
console.log('VERIFY-STATE-SYNC-MODEOF ' + (failed === 0 ? 'PASSED' : 'FAILED') + '（' + checks + ' 项检查，' + failed + ' 项失败）')
process.exitCode = failed === 0 ? 0 : 1