// 内嵌 ADB 输入通道（ADBKeyboard 协议）广播面回归（#413）。
//
// 现场（设备实测，Android 16 / 17 各一台）：
//   `am broadcast -a ADB_INPUT_TEXT --es msg 'probe123'`
//   → 系统只入队 0 个接收者（targetSdk 26+ 的 manifest 接收器收不到隐式广播），
//     而 `am broadcast` 照样打印 `Broadcast completed: result=0` ——
//     「发送完成」被误当成「文本已提交」，页面上的表现就是「IME 链路全绿、文本不动」。
// 本套件把两条真判据钉成单测：① 命令必须点名组件；② 结果码必须能解析并区分语义。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const mod = await import(pathToFileURL(join(HERE, '..', 'lib', 'ime-broadcast.js')).href)

test('广播点名接收器组件，并带上动作/nonce/文本', () => {
  const line = mod.adbKeyboardBroadcast(mod.KB_ACTION_INPUT, '鲸歌Abc123', 'nonce-abc')
  assert.match(line, /^am broadcast -n com\.dsharnessmobile\.shell\/\.AdbKeyboardReceiver -a ADB_INPUT_TEXT /)
  assert.match(line, /--es auth 'nonce-abc'/)
  assert.match(line, /--es msg '鲸歌Abc123'$/)
})

test('隐式广播（无 -p / 无 -n）不再是命令形态', () => {
  const line = mod.adbKeyboardBroadcast(mod.KB_ACTION_CLEAR, undefined, '')
  assert.ok(line.includes('-n ' + mod.KB_RECEIVER), '必须点名组件')
  assert.ok(!line.includes(' -p '), '不得用包名定向（会同时命中运行期接收器，重复提交）')
  assert.ok(!line.includes('--es msg'), '清空动作不该带 msg')
  assert.ok(!line.includes('--es auth'), '无 nonce 时不带 auth 参数')
})

test('单引号转义（文本含 shell 元字符时命令仍闭合）', () => {
  const line = mod.adbKeyboardBroadcast(mod.KB_ACTION_INPUT, `it's a 'q'`, '')
  assert.ok(line.endsWith(`--es msg 'it'\\''s a '\\''q'\\'''`), line)
})

test('空文本只清空不提交（不构造空的 msg 参数）', () => {
  const line = mod.adbKeyboardBroadcast(mod.KB_ACTION_INPUT, '', 'n')
  assert.ok(!line.includes('--es msg'))
})

test('结果码解析：区分已提交/未就绪/被拒，且容忍噪声行', () => {
  assert.equal(mod.parseBroadcastResult('Broadcasting: Intent { act=ADB_INPUT_TEXT }\nBroadcast completed: result=1'), 1)
  assert.equal(mod.parseBroadcastResult('Broadcast completed: result=3\n'), 3)
  assert.equal(mod.parseBroadcastResult('Broadcast completed: result=0'), 0)
  assert.equal(mod.parseBroadcastResult('Broadcast completed: result=-1'), -1)
  assert.equal(mod.parseBroadcastResult(''), null)
  assert.equal(mod.parseBroadcastResult('error: no devices/emulators found'), null)
})

test('结果码常量与壳侧 AdbKeyboardService.RESULT_* 对齐（1..5 且互不重复）', () => {
  const codes = Object.values(mod.KB_RESULT)
  assert.deepEqual(codes, [1, 2, 3, 4, 5])
  assert.equal(new Set(codes).size, codes.length)
})

test('结果码判定：旧壳的 0 与解析不出放行，已知失败码判红', () => {
  assert.equal(mod.isCommitAccepted(1), true, '已提交')
  assert.equal(mod.isCommitAccepted(0), true, '旧壳不写结果码（提交成功也只是 0）——不能判失败')
  assert.equal(mod.isCommitAccepted(null), true, '解析不出（输出被截断）退回回读断言')
  for (const code of [2, 3, 4, 5]) {
    assert.equal(mod.isCommitAccepted(code), false, `结果码 ${code} 必须判红`)
  }
})

test('nonce 读取：私有文件在场则取 trim 值，缺失/目录未定义返回空串', () => {
  const dir = mkdtempSync(join(tmpdir(), 'kb-nonce-'))
  try {
    assert.equal(mod.adbKeyboardAuth(undefined), '')
    assert.equal(mod.adbKeyboardAuth(dir), '', '文件缺失 → 空串（退回 uid 白名单）')
    writeFileSync(join(dir, 'adb-keyboard-nonce'), '  deadbeef  \n')
    assert.equal(mod.adbKeyboardAuth(dir), 'deadbeef')
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('IME 组件名与包名一致（壳侧 manifest 的 service 名）', () => {
  assert.equal(mod.IME_ID, 'com.ai.assistance.quro.dshmobile/.AdbKeyboardService')
  assert.equal(mod.KB_RECEIVER, 'com.ai.assistance.quro.dshmobile/.AdbKeyboardReceiver')
})
