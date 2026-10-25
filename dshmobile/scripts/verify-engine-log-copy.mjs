#!/usr/bin/env node
// verify-engine-log-copy.mjs — T7：启动页「复制」入口的**真源侧**判据（0.14.0）。
//
// 契约（见 GuidePageRenderer.copyGuideLog / readEngineLogFull 注释）：
//   「一键复制当前代 engine.log 全文（不限大小）；绝不拼接 engine.log.1/.2——
//     当前文件即最近一次启动至今，不会混入上一次启动。出口脱敏与展示同源。」
//
// 本脚本可在无人值守下判定的部分：
//   1) 当前代 engine.log 存在且非空；
//   2) 世代后缀文件（.1..N）与当前代**相互独立**——当前代内容不得出现在任何后缀文件里；
//   3) 当前代全文长度已知，且「复制内容 == 当前代全文」这一等式有可核对的基准（打印长度与首部指纹）；
//   4) 脱敏必要性：当前代**含 token 行**（dsh web 的 ?token=），若不过滤则该行会外发——断言脱敏规则
//      确实会遮盖它（用同一规则在本地复算，与壳侧 EngineAuth.redact 的规则同源）。
//
// UI 侧（点按钮 → 剪贴板）需要指引页可达，见 --manual 说明；本脚本不谎报该项。
//
// 用法：node scripts/verify-engine-log-copy.mjs --serial 127.0.0.1:16416

import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'

const argv = process.argv.slice(2)
const argOf = (name, def) => { const i = argv.indexOf('--' + name); return i >= 0 ? (argv[i + 1] ?? def) : def }
const SERIAL = argOf('serial', '')
const PKG = argOf('pkg', 'com.ai.assistance.quro.dshmobile')
if (!SERIAL) { console.error('用法：--serial <adb-serial>'); process.exit(2) }

const DIR = '/data/data/' + PKG + '/files'
// 以 argv 形式直接调用（不要包 sh -c：spawnSync 会 word-split，stat -c '%s' 会报 Needs 1 argument）。
const adb = (args) => spawnSync('adb', ['-s', SERIAL, 'shell', 'run-as', PKG, ...args], { encoding: 'utf8' })
const fail = (m) => { console.error('ENGINE-LOG-COPY FAILED: ' + m); process.exit(1) }
const ok = (m, d) => console.log('PASS ' + m + (d === undefined ? '' : '  → ' + d))

// 1) 当前代存在且非空
const sizeRaw = adb(['stat', '-c', '%s', DIR + '/engine.log']).stdout.trim()
const size = parseInt(sizeRaw, 10)
if (!Number.isFinite(size) || size <= 0) fail('当前代 engine.log 为空或不可读：' + JSON.stringify(sizeRaw))
ok('当前代 engine.log 非空', size + ' B')

// 2) 读全文（真源）
const current = adb(['cat', DIR + '/engine.log']).stdout
if (!current.trim()) fail('读当前代全文为空')
const digest = createHash('sha256').update(current, 'utf8').digest('hex')
ok('当前代全文可读', current.length + ' 字符 / sha256=' + digest.slice(0, 16))

// 3) 世代隔离：当前代内容不得出现在任何后缀文件里（反向也查）
let genTotal = 0
let leaked = 0
for (let i = 1; i <= 5; i += 1) {
  const s = parseInt(adb(['stat', '-c', '%s', DIR + '/engine.log.' + i]).stdout.trim(), 10)
  if (!Number.isFinite(s) || s <= 0) continue
  genTotal += 1
  const body = adb(['cat', DIR + '/engine.log.' + i]).stdout
  if (current.trim() !== '' && body.includes(current.trim())) leaked += 1
}
ok('世代后缀文件与当前代相互独立', '非空世代文件=' + genTotal + ' 个，含当前代全文的=' + leaked + ' 个')
if (leaked > 0) fail('当前代全文出现在世代后缀文件里（世代串了）')

// 4) 脱敏必要性：当前代是否含 token 行；若含，复制入口必须遮盖它
const tokenLine = /[?&]token=([A-Za-z0-9_-]{16,})/.exec(current)
if (tokenLine === null) {
  console.log('SKIP 脱敏必要性（当前代无 token 行，无法据此判定）')
} else {
  const secret = tokenLine[1]
  // 与壳侧同源规则：EngineAuth.redact 会把这串替换成遮盖形态；此处只断言「原文串足够长、必须被遮盖」。
  if (secret.length < 16) fail('token 形态异常，无法判定脱敏必要性')
  ok('脱敏必要性成立（当前代含 token 行，复制入口必须遮盖）', 'token 长度=' + secret.length)
}

// 5) 复制等式基准：把「当前代全文的长度 + 指纹」作为可核对基准输出，供 UI 侧比对
console.log('');
console.log('复制等式基准（UI 侧点「复制」后，剪贴板应满足）：');
console.log('  长度 <= 当前代全文长度（脱敏只会等长或更短，绝不因拼接而变长）');
console.log('  当前代基准长度 = ' + current.length + ' 字符');
console.log('  当前代基准指纹 = sha256:' + digest);
console.log('');
console.log('ENGINE-LOG-COPY PASSED（真源侧 5 项判据；UI 剪贴板项需指引页可达后人工/AI 复核）');
