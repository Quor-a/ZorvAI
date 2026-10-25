// apk #350 行为回归（离线）：兼容层的两处文件点击拦截必须**只兜底、不劫持**。
//
// 缺陷现场：PICKER_SCRIPT 在 document 捕获期拦截文件点击并转给系统打开方式（旧行为：上游把
// 文件交给桌面原生打开器）。上游 0.2.0-rc.2 已改为在右侧 Sidebar 打开
// （ui-chat apply.ts:203 Files open in the right Sidebar -> :215 openFile -> :218 openResource），
// 于是该拦截变成反向劫持：用户点文件不再预览、反而弹出打开方式（apk #350）。
//
// 修法：拦截前先问「上游是否已自己处理这个按钮」——是则放行，让事件冒泡到上游 React onClick。
//
// 本包没有 jsdom（依赖面刻意保持最小），因此按本包既有做法（open-path-session.test.mjs）
// 分两层验证：**函数体单元测试**证明判据本身正确，**源码级契约断言**证明两处拦截都真的用了它。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../lib/index.js', import.meta.url), 'utf8')

/** 从 lib 里取出 hasUpstreamHandler 的函数体并实例化，用于直接测试判据。 */
function loadHasUpstreamHandler() {
  const start = SRC.indexOf('function hasUpstreamHandler(')
  assert.ok(start > 0, 'lib/index.js 必须定义 hasUpstreamHandler')
  // 函数体以行首 '}' 结束（与 resolveSessionPath 的取法同源）。
  const end = SRC.indexOf('\n}\n', start)
  assert.ok(end > start, 'hasUpstreamHandler 必须能被完整取出')
  const body = SRC.slice(start, end + 3)
  return new Function(body + '\nreturn hasUpstreamHandler')()
}

const hasUpstreamHandler = loadHasUpstreamHandler()

test('判据：节点上挂了 React onClick 即视为上游已接管', () => {
  const el = { ['__reactProps$abc']: { onClick: () => {} } }
  assert.equal(hasUpstreamHandler(el), true)
})

test('判据：没有 React props 的同类节点仍应走兜底', () => {
  assert.equal(hasUpstreamHandler({}), false)
})

test('判据：onClick 不是函数时不得误判为已接管', () => {
  assert.equal(hasUpstreamHandler({ ['__reactProps$x']: { onClick: 'not-a-function' } }), false)
  assert.equal(hasUpstreamHandler({ ['__reactProps$x']: null }), false)
})

test('判据：空值安全（不抛错、判为未接管）', () => {
  assert.equal(hasUpstreamHandler(null), false)
  assert.equal(hasUpstreamHandler(undefined), false)
})

test('判据：只认自身属性，不继承原型链上的键', () => {
  const proto = { ['__reactProps$p']: { onClick: () => {} } }
  const el = Object.create(proto)
  assert.equal(hasUpstreamHandler(el), false)
})

test('契约：两处拦截都必须在消费点击之前问过上游是否已接管', () => {
  const calls = SRC.split('hasUpstreamHandler(').length - 1
  // 1 次定义 + 2 处调用（title 型与 tool-row 型各一处）
  assert.equal(calls, 3, '两处拦截都要调用 hasUpstreamHandler（实际 ' + calls + ' 次）')
  // 两处调用都必须出现在 preventDefault 之前（放行 = 不消费事件）。
  const gate = SRC.indexOf('if(hasUpstreamHandler(el))return;')
  assert.ok(gate > 0, 'title 型拦截必须有放行门')
  const gate2 = SRC.indexOf('if(hasUpstreamHandler(btn))return;')
  assert.ok(gate2 > 0, 'tool-row 型拦截必须有放行门')
  assert.ok(SRC.indexOf('e.preventDefault()') > gate, 'title 型：放行门在消费之前')
})

test('契约：判据必须是 DOM 节点上的 React props 键，不是类名或文案', () => {
  assert.ok(SRC.includes('__reactProps'), '必须以 React props 键判定')
  // 反证：用文案/类名判定会在上游改文案或哈希类名时静默失效。
  assert.equal(SRC.includes('hasUpstreamHandler(document.'), false, '不得用全局特征代替节点判据')
})
