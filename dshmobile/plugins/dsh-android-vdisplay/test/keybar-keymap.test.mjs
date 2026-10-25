// 屏上九键条：**键位语义纯函数层**的回归（离线，node:test）。
//
// 为什么这个文件是判据 §6.1 的主体：键->序列映射与 Ctrl 闩锁状态机是本任务最容易写错、
// 且写错后在终端里后果最严重的部分（发错控制字符 = 真的乱码 / 真的误操作）。
// 判据原文：映射必须是**纯函数**（含 Ctrl 状态机）；「写进 UI 回调 => 判红」；
// 反证「对任意键发 charCode^0x40 => 乱码断言失败」。
//
// 本文件因此分三组：
//   1. 正向：九键的序列 / Ctrl 闩锁状态转移 / 超时 / DECCKM 动态选择；
//   2. 反证：naive 公式（charCode^0x40）对方向键会产出乱码 —— 断言我们**拒绝**而不是发；
//   3. 源码门禁：语义层不得引入 DOM/React/cordis，且 UI 层不得把映射抄回去。
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  CTRL_LATCH_TIMEOUT_MS,
  INITIAL_LATCH,
  KEYBAR_KEYS,
  KEYBAR_SPECS,
  controlCharFor,
  controlKeyCodeFor,
  expireLatch,
  fallbackSequence,
  pressKeybarKey,
  resolveCtrlChar,
} from '../src/keybar/keymap.ts'

const repr = (s) => JSON.stringify(s)

/** 取出一次 outcome 里唯一的 send 效果（没有则抛，便于定位）。 */
function onlySend(outcome) {
  const sends = outcome.effects.filter((e) => e.type === 'send')
  assert.equal(sends.length, 1, '期望恰好一个 send，实际 ' + repr(outcome.effects))
  return sends[0]
}

// ---------------------------------------------------------------------------
// 组 1：正向
// ---------------------------------------------------------------------------

test('九键顺序固定且恰好九个（一屏一行，UI 与语义层同源）', () => {
  assert.deepEqual([...KEYBAR_KEYS], ['keyboard', 'tab', 'ctrl', 'esc', 'left', 'right', 'up', 'down', 'enter'])
  assert.equal(new Set(KEYBAR_KEYS).size, 9)
  for (const key of KEYBAR_KEYS) {
    assert.ok(KEYBAR_SPECS[key], '缺少表项: ' + key)
    assert.equal(KEYBAR_SPECS[key].id, key)
    assert.ok(KEYBAR_SPECS[key].label.length > 0, '缺 label: ' + key)
    assert.ok(KEYBAR_SPECS[key].ariaLabel.length > 0, '缺 ariaLabel: ' + key)
  }
})

test('方向键在普通模式（DECCKM 关闭）是 CSI，在应用模式是 SS3；两组都不得为空', () => {
  const expected = {
    left: ['\u001b[D', '\u001bOD'],
    right: ['\u001b[C', '\u001bOC'],
    up: ['\u001b[A', '\u001bOA'],
    down: ['\u001b[B', '\u001bOB'],
  }
  for (const [key, [csi, ss3]] of Object.entries(expected)) {
    assert.equal(KEYBAR_SPECS[key].csi, csi, key + ' 的 CSI 不对')
    assert.equal(KEYBAR_SPECS[key].ss3, ss3, key + ' 的 SS3 不对')
    assert.notEqual(csi, ss3, key + '：CSI 与 SS3 相同说明没区分模式')
  }
})

test('Tab/Esc/回车 序列正确（且两种模式同值，与方向键不同）', () => {
  assert.equal(KEYBAR_SPECS.tab.csi, '\t')
  assert.equal(KEYBAR_SPECS.esc.csi, '\u001b')
  assert.equal(KEYBAR_SPECS.enter.csi, '\r')
  assert.equal(KEYBAR_SPECS.tab.ss3, '\t')
  assert.equal(KEYBAR_SPECS.esc.ss3, '\u001b')
  assert.equal(KEYBAR_SPECS.enter.ss3, '\r')
})

test('兜底序列按 DECCKM **动态**选择（绝不写死单一序列）', () => {
  // 阶段 0 [A] 实测：acck=false -> CSI；acck=true -> SS3。
  assert.equal(fallbackSequence(KEYBAR_SPECS.up, false), '\u001b[A')
  assert.equal(fallbackSequence(KEYBAR_SPECS.up, true), '\u001bOA')
  assert.equal(fallbackSequence(KEYBAR_SPECS.left, false), '\u001b[D')
  assert.equal(fallbackSequence(KEYBAR_SPECS.left, true), '\u001bOD')
  // 不产字节的键在两种模式下都是 null。
  assert.equal(fallbackSequence(KEYBAR_SPECS.keyboard, false), null)
  assert.equal(fallbackSequence(KEYBAR_SPECS.keyboard, true), null)
  assert.equal(fallbackSequence(KEYBAR_SPECS.ctrl, true), null)
})

test('未闩锁时按下发送键：状态不变，产出 xterm 身份 + CSI 数据', () => {
  for (const key of ['tab', 'esc', 'left', 'right', 'up', 'down', 'enter']) {
    const outcome = pressKeybarKey(INITIAL_LATCH, key, 1_000)
    assert.equal(outcome.state.latched, false, key + ' 不该改变闩锁')
    const send = onlySend(outcome)
    assert.equal(send.key, key)
    assert.equal(send.ctrlKey, false)
    assert.equal(send.data, KEYBAR_SPECS[key].csi)
    assert.deepEqual(send.xterm, KEYBAR_SPECS[key].xterm, key + ' 的 xterm 身份不对')
  }
})

test('「键盘」键只聚焦、不发序列；开着闩锁时先解除再聚焦', () => {
  const fresh = pressKeybarKey(INITIAL_LATCH, 'keyboard', 1_000)
  assert.deepEqual(fresh.effects, [{ type: 'focus' }])
  assert.equal(fresh.state.latched, false)

  const latched = pressKeybarKey(INITIAL_LATCH, 'ctrl', 1_000)
  const after = pressKeybarKey(latched.state, 'keyboard', 2_000)
  assert.deepEqual(after.effects, [
    { type: 'latch', latched: false, expiresAtMs: null },
    { type: 'focus' },
  ])
  assert.equal(after.state.latched, false)
  assert.equal(after.state.expiresAtMs, null)
})

test('Ctrl 闩锁：点一下进入待命并给出到期时刻（N=4s，常量非魔数）', () => {
  assert.equal(CTRL_LATCH_TIMEOUT_MS, 4_000)
  const on = pressKeybarKey(INITIAL_LATCH, 'ctrl', 10_000)
  assert.equal(on.state.latched, true)
  assert.equal(on.state.expiresAtMs, 10_000 + CTRL_LATCH_TIMEOUT_MS)
  assert.deepEqual(on.effects, [{ type: 'latch', latched: true, expiresAtMs: 14_000 }])
})

test('Ctrl 闩锁：再点一次 Ctrl 主动取消（用户不会卡在 Ctrl 态）', () => {
  const on = pressKeybarKey(INITIAL_LATCH, 'ctrl', 0)
  const off = pressKeybarKey(on.state, 'ctrl', 500)
  assert.equal(off.state.latched, false)
  assert.equal(off.state.expiresAtMs, null)
  assert.deepEqual(off.effects, [{ type: 'latch', latched: false, expiresAtMs: null }])
})

test('Ctrl 闩锁：超时可配置（不写死），且默认值即 4s', () => {
  const on = pressKeybarKey(INITIAL_LATCH, 'ctrl', 0, 3_000)
  assert.equal(on.state.expiresAtMs, 3_000)
  const on5 = pressKeybarKey(INITIAL_LATCH, 'ctrl', 0, 5_000)
  assert.equal(on5.state.expiresAtMs, 5_000)
})

test('expireLatch：未到期不动；恰好到期即解除并产出可见取消效果', () => {
  const on = pressKeybarKey(INITIAL_LATCH, 'ctrl', 1_000)
  const before = expireLatch(on.state, 1_000 + CTRL_LATCH_TIMEOUT_MS - 1)
  assert.equal(before.state.latched, true)
  assert.deepEqual(before.effects, [])

  const atBoundary = expireLatch(on.state, 1_000 + CTRL_LATCH_TIMEOUT_MS)
  assert.equal(atBoundary.state.latched, false)
  assert.equal(atBoundary.state.expiresAtMs, null)
  assert.deepEqual(atBoundary.effects, [{ type: 'latch', latched: false, expiresAtMs: null }])

  const after = expireLatch(before.state, 99_999)
  assert.equal(after.state.latched, false)
})

test('未闩锁时 expireLatch 是恒等（幂等，不产生多余效果）', () => {
  const noop = expireLatch(INITIAL_LATCH, 12_345)
  assert.deepEqual(noop.state, INITIAL_LATCH)
  assert.deepEqual(noop.effects, [])
})

test('Ctrl 闩锁 + 九键：**明确拒绝**不支持组合，并自动解除闩锁（不静默发错序列）', () => {
  const on = pressKeybarKey(INITIAL_LATCH, 'ctrl', 0)
  for (const key of ['tab', 'esc', 'left', 'right', 'up', 'down', 'enter']) {
    const outcome = pressKeybarKey(on.state, key, 100)
    assert.equal(outcome.state.latched, false, key + '：拒绝后必须解除闩锁')
    assert.equal(outcome.effects.filter((e) => e.type === 'send').length, 0, key + '：绝不发序列')
    const rejects = outcome.effects.filter((e) => e.type === 'reject')
    assert.equal(rejects.length, 1, key + '：必须有一条 reject 供 UI 提示')
    assert.equal(rejects[0].reason, 'unsupported-control-combo')
    assert.equal(rejects[0].key, key)
    // 拒绝必须伴随可见的闩锁解除效果（用户看得到「取消了」）。
    assert.ok(outcome.effects.some((e) => e.type === 'latch' && e.latched === false))
  }
})

test('控制字符白名单：字母 a-z / A-Z 覆盖 0x01-0x1A，且与 caret 记法一致', () => {
  for (let code = 0; code < 26; code++) {
    const letter = String.fromCharCode(97 + code)
    const expected = String.fromCharCode(1 + code)
    assert.equal(controlCharFor(letter), expected, 'Ctrl+' + letter)
    assert.equal(controlCharFor(letter.toUpperCase()), expected, 'Ctrl+' + letter.toUpperCase())
  }
})

test('控制字符白名单：符号类（@ [ 反斜杠 ] ^ _ ?）', () => {
  assert.equal(controlCharFor('@'), '\u0000')
  assert.equal(controlCharFor('['), '\u001b')
  assert.equal(controlCharFor('\\'), '\u001c')
  assert.equal(controlCharFor(']'), '\u001d')
  assert.equal(controlCharFor('^'), '\u001e')
  assert.equal(controlCharFor('_'), '\u001f')
  assert.equal(controlCharFor('?'), '\u007f')
})

test('控制字符白名单：表外一律 null（数字、命名键、多字符）', () => {
  for (const outside of ['1', '2', '3', '6', '8', '0', ' ', '-', '/', '', 'ab']) {
    assert.equal(controlCharFor(outside), null, '不应映射: ' + repr(outside))
  }
})

test('resolveCtrlChar：命中字母给 KeyX 身份与控制字节；未命中必须 ok=false', () => {
  assert.deepEqual(resolveCtrlChar('c'), { ok: true, data: '\u0003', xterm: { key: 'c', code: 'KeyC', keyCode: 67 } })
  assert.deepEqual(resolveCtrlChar('C'), { ok: true, data: '\u0003', xterm: { key: 'c', code: 'KeyC', keyCode: 67 } })
  assert.deepEqual(resolveCtrlChar('d'), { ok: true, data: '\u0004', xterm: { key: 'd', code: 'KeyD', keyCode: 68 } })
  assert.deepEqual(resolveCtrlChar('['), { ok: true, data: '\u001b', xterm: { key: '[', code: '[', keyCode: 219 } })
  assert.deepEqual(resolveCtrlChar('3'), { ok: false, reason: 'unsupported-control-combo' })
  assert.deepEqual(resolveCtrlChar(' '), { ok: false, reason: 'unsupported-control-combo' })
})

test('纯函数性：同样输入恒等输出，且不修改传入的 state 对象', () => {
  const input = { latched: true, expiresAtMs: 5_000 }
  const snapshot = { ...input }
  const a = pressKeybarKey(input, 'up', 1_000)
  const b = pressKeybarKey(input, 'up', 1_000)
  assert.deepEqual(a, b)
  assert.deepEqual(input, snapshot, 'state 必须是只读的（不得被就地修改）')

  const c = expireLatch(input, 9_999)
  const d = expireLatch(input, 9_999)
  assert.deepEqual(c, d)
  assert.deepEqual(input, snapshot)
})

// ---------------------------------------------------------------------------
// 组 1b：旧式 keyCode（0.14.2 ADB 用户层阻塞级缺陷的反证）
// ---------------------------------------------------------------------------

test('反证①：每个 sends=true 的键都必须登记**非 0** keyCode（缺陷形态 = keyCode 0，必须判红）', () => {
  // 缺陷现象（lead 的 adb 实测）：合成事件 keyCode=0 -> xterm 直接丢弃该键 ->
  // 事件到了 .xterm-helper-textarea 却没有任何 POST /api/terminal/write，终端零反应。
  const sending = KEYBAR_KEYS.filter((key) => KEYBAR_SPECS[key].sends)
  assert.equal(sending.length, 7, '应有 7 个发送键（Tab/Esc/方向x4/回车）')
  for (const key of sending) {
    const xterm = KEYBAR_SPECS[key].xterm
    assert.ok(xterm, key + ' 缺 xterm 身份')
    assert.equal(typeof xterm.keyCode, 'number', key + ' 的 keyCode 必须是数字')
    assert.notEqual(xterm.keyCode, 0, key + ' 的 keyCode 为 0 => xterm 会丢弃该键（本缺陷）')
    assert.equal(Number.isInteger(xterm.keyCode), true, key + ' 的 keyCode 必须是整数')
  }
  // 不发送的键不登记 keyCode（null 身份），避免误用。
  for (const key of ['keyboard', 'ctrl']) {
    assert.equal(KEYBAR_SPECS[key].xterm, null, key + ' 不应有 xterm 身份（它不发字节）')
  }
})

test('旧式 keyCode 登记值与实测一致：Tab=9 Esc=27 左=37 上=38 右=39 下=40 回车=13', () => {
  assert.equal(KEYBAR_SPECS.tab.xterm.keyCode, 9)
  assert.equal(KEYBAR_SPECS.esc.xterm.keyCode, 27)
  assert.equal(KEYBAR_SPECS.left.xterm.keyCode, 37)
  assert.equal(KEYBAR_SPECS.up.xterm.keyCode, 38)
  assert.equal(KEYBAR_SPECS.right.xterm.keyCode, 39)
  assert.equal(KEYBAR_SPECS.down.xterm.keyCode, 40)
  assert.equal(KEYBAR_SPECS.enter.xterm.keyCode, 13)
  // 方向键的键码必须互不相同（复制粘贴式漏改会在这里暴露）。
  const arrows = ['left', 'up', 'right', 'down'].map((k) => KEYBAR_SPECS[k].xterm.keyCode)
  assert.equal(new Set(arrows).size, 4, '四个方向键的 keyCode 必须互不相同')
})

test('反证②：Ctrl 白名单的字母 keyCode 必须是**大写 ASCII 65-90**，不是控制字符', () => {
  // 若误用 charCode^0x40 公式，字母会得到控制字符（如 Ctrl+C -> 3），
  // 而那正是 xterm 丢弃该键的形态（3 是控制字节不是键码）。
  for (let code = 0; code < 26; code++) {
    const letter = String.fromCharCode(97 + code)
    const expectedKeyCode = 65 + code
    assert.equal(controlKeyCodeFor(letter), expectedKeyCode, 'Ctrl+' + letter + ' 的 keyCode')
    assert.equal(controlKeyCodeFor(letter.toUpperCase()), expectedKeyCode)
    // 反证：公式给的是控制字节，绝不能被当成 keyCode。
    const formulaResult = String.fromCharCode(letter.charCodeAt(0) ^ 0x40).charCodeAt(0)
    if (letter !== 'a') {
      assert.notEqual(controlKeyCodeFor(letter), formulaResult,
        'Ctrl+' + letter + ' 的 keyCode 不得等于公式值 ' + formulaResult)
    }
  }
  // 逐个点明几个高频组合（与终端语义一致）。
  assert.equal(controlKeyCodeFor('c'), 67, 'Ctrl+C 的 keyCode 是 C 键(67)，不是 3')
  assert.equal(controlCharFor('c'), '\u0003', 'Ctrl+C 的控制字节才是 3')
  assert.notEqual(controlKeyCodeFor('c'), controlCharFor('c').charCodeAt(0))
})

test('resolveCtrlChar 必须同时给出控制字节与**非 0** keyCode（缺一不可）', () => {
  for (const [char, data, keyCode] of [['c', '\u0003', 67], ['d', '\u0004', 68], ['z', '\u001a', 90], ['l', '\u000c', 76]]) {
    const result = resolveCtrlChar(char)
    assert.equal(result.ok, true, char)
    assert.equal(result.data, data)
    assert.equal(result.xterm.keyCode, keyCode, 'Ctrl+' + char + ' 的 keyCode')
    assert.notEqual(result.xterm.keyCode, 0)
  }
  // 表外字符仍然必须拒绝（不得给出 keyCode=0 的半个事实）。
  for (const outside of ['3', ' ', 'ArrowUp', '']) {
    assert.deepEqual(resolveCtrlChar(outside), { ok: false, reason: 'unsupported-control-combo' })
  }
})

test('纯函数层：controlKeyCodeFor 与 controlCharFor 同表同源（表外同时为 null）', () => {
  for (const char of ['a', 'c', 'z', '@', '[', '\\', ']', '^', '_', '?']) {
    assert.notEqual(controlCharFor(char), null, char)
    assert.notEqual(controlKeyCodeFor(char), null, char)
  }
  for (const outside of ['1', ' ', '', 'ab']) {
    assert.equal(controlCharFor(outside), null, outside)
    assert.equal(controlKeyCodeFor(outside), null, '表外必须同时为 null: ' + outside)
  }
})

// ---------------------------------------------------------------------------
// 组 2：反证（naive 公式会怎样）
// ---------------------------------------------------------------------------

test('反证：naive charCode^0x40 公式对方向键会产出乱码 —— 断言我们**拒绝**而非发', () => {
  // 这就是判据点名的反证。把「用公式代替白名单」的写法钉死在反面：
  // 方向键的 key 是 'ArrowUp' 这类名字，公式取首字符 'A'(65) ^ 0x40 = 1 = \x01（Ctrl+A），
  // 在终端里是「跳到行首」，与「历史上一条」完全无关 —— 属静默发错。
  const naive = (char) => String.fromCharCode(char.charCodeAt(0) ^ 0x40)
  assert.equal(naive('A'), '\u0001')

  // 我们的实现：Ctrl+方向键没有白名单入口 => 拒绝，绝不发 naive 的结果。
  const on = pressKeybarKey(INITIAL_LATCH, 'ctrl', 0)
  const rejected = pressKeybarKey(on.state, 'up', 1)
  assert.equal(rejected.effects.filter((e) => e.type === 'send').length, 0)
  assert.equal(rejected.effects.filter((e) => e.type === 'reject').length, 1)
  // 且其 data 不可能等于 naive('A')。
  const anyData = rejected.effects.map((e) => e.data).filter(Boolean)
  assert.equal(anyData.includes('\u0001'), false, '绝不能把 Ctrl+上 发成 Ctrl+A(\u0001)')

  // resolveCtrlChar 也拒绝命名键与数字，堵住「另一条路偷偷用公式」。
  assert.equal(resolveCtrlChar('A').data, '\u0001') // 字母是**合法**映射（与公式巧合一致）
  assert.equal(resolveCtrlChar('3').ok, false)      // 数字立即拒绝（公式会给出 \x73 = 's'）
  assert.notEqual(naive('3'), controlCharFor('3'))
  assert.equal(controlCharFor('3'), null)
})

test('反证：白名单与公式只在 @ A-Z [ 反斜杠 ] ^ _ 上重合（证明我们不是公式的另一种写法）', () => {
  const naive = (char) => String.fromCharCode(char.charCodeAt(0) ^ 0x40)
  // 公式对 a-z 的正确用法必须**先大写**；这也是它能骗过粗测的原因。
  for (let code = 0; code < 26; code++) {
    const lower = String.fromCharCode(97 + code)
    assert.equal(controlCharFor(lower), naive(lower.toUpperCase()), lower + ' 与公式应重合')
  }
  // 公式在 0x40-0x5F 这段（@ A-Z [ 反斜杠 ] ^ _）恰好等于 XOR 0x40，所以那部分也重合 ——
  // 这正是「公式看起来能用」的原因。我们仍然用显式表，因为**表外**它立刻发散：
  for (const symbol of ['@', 'A', '[', '\\', ']', '^', '_', '?']) {
    assert.equal(controlCharFor(symbol), naive(symbol), symbol + ' 在该段内应重合（记录这一巧合）')
  }
  // 表外：公式给小写字母 '!' 而不是 Ctrl+A —— 静默发错。
  assert.equal(naive('a'), '!')
  assert.equal(controlCharFor('a'), '\u0001')
  assert.notEqual(naive('a'), controlCharFor('a'))
  // 表外：空格公式给反引号，我们拒绝。
  assert.equal(naive(' '), '\u0060')
  assert.equal(controlCharFor(' '), null)
  // 表外：数字公式给字母（'3' -> 's'），我们拒绝。
  assert.equal(naive('3'), 's')
  assert.equal(controlCharFor('3'), null)
  // 表外：命名键公式取首字母，把 Ctrl+上 变成 Ctrl+A(\u0001) —— 最危险的一例，我们拒绝。
  assert.equal(naive('ArrowUp'), '\u0001')
  assert.equal(controlCharFor('ArrowUp'), null)
})

// ---------------------------------------------------------------------------
// 组 3：源码门禁
// ---------------------------------------------------------------------------

test('源码门禁：语义层必须是纯的（无 DOM / React / cordis 依赖）', () => {
  const path = fileURLToPath(new URL('../src/keybar/keymap.ts', import.meta.url))
  const source = readFileSync(path, 'utf8')
  // 先剥注释：文件头的纪律说明会**点名**这些词（说明「不需要它们」），不能当依赖。
  const code = source.replace(/\/\*[\s\S]*?\*\//gu, '').replace(/^\s*\/\/.*$/gmu, '')
  const imports = [...code.matchAll(/^\s*import\s[^\n]*from\s+'([^']+)'/gmu)].map((m) => m[1])
  assert.deepEqual(imports, [], '语义层不得 import 任何东西，实际: ' + repr(imports))
  for (const forbidden of ['document', 'window', 'react', 'cordis', 'navigator', 'localStorage']) {
    assert.equal(new RegExp('\\b' + forbidden + '\\b', 'i').test(code), false,
      '语义层**代码**不得出现 ' + forbidden)
  }
  // 反证：这套剥注释后的扫描确实能抓到真实依赖（在临时字符串上验证，不动源文件）。
  const fake = "import { useState } from 'react'\nconst x = document.body"
  assert.equal(/import\s[^\n]*from\s+'([^']+)'/u.test(fake), true)
  assert.equal(/\bdocument\b/u.test(fake), true)
})

test('源码门禁：UI 层不得把键位映射抄回去（判据「写进 UI 回调 => 判红」）', () => {
  const clientPath = fileURLToPath(new URL('../src/client/index.ts', import.meta.url))
  const client = readFileSync(clientPath, 'utf8')

  // 1) UI 层必须把语义**委托**给 keybar 目录，而不是本地另写一份。
  assert.ok(/from\s+'\.\.\/keybar\//.test(client),
    'UI 层必须 import ../keybar/* 使用共享语义，而不是自己实现映射')

  // 2) UI 层不得出现任何键位序列字面量。
  const literal = [
    '\\u001b[A', '\\u001b[B', '\\u001b[C', '\\u001b[D',
    '\\u001bOA', '\\u001bOB', '\\u001bOC', '\\u001bOD',
    '\\x1b[A', '\\x1bOA',
    '\\u0003', '\\u0004', '\\u001a', '\\u000c',
  ]
  const found = literal.filter((needle) => client.includes(needle))
  assert.deepEqual(found, [], 'UI 层出现键位序列字面量: ' + repr(found))

  // 3) 链必须是**可达**的：UI 若只 import mount.ts，mount.ts 必须自己 import keymap.ts，
  //    否则「共享语义」是假的（UI 与 mount 各写一份就没被拦住）。
  const imported = [...client.matchAll(/from\s+'(\.\.\/keybar\/[^']+)'/gu)].map((m) => m[1])
  assert.ok(imported.length > 0, 'UI 层必须至少 import 一个 keybar 模块')
  const resolvesToKeymap = imported.some((spec) => spec.includes('keymap'))
  if (!resolvesToKeymap) {
    const mountPath = fileURLToPath(new URL('../src/keybar/mount.ts', import.meta.url))
    const mount = readFileSync(mountPath, 'utf8')
    assert.ok(/from\s+'\.\/keymap\.ts'/.test(mount),
      'UI 只 import 了 ' + repr(imported) + '，则该模块必须 import ./keymap.ts，链才可达')
  }

  // 4) 反证：这套检查确实能抓到「UI 自己抄一份」的形态（在临时字符串上验证，不动源文件）。
  const badClient = "const M = { up: '\\u001b[A' }"
  assert.equal(literal.some((needle) => badClient.includes(needle)), true,
    '字面量扫描必须能抓到本地抄写的方向键序列')
  assert.equal(/from\s+'\.\.\/keybar\//.test(badClient), false,
    '委托检查必须能抓到「没有委托」的形态')
})
