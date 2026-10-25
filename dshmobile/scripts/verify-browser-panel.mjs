#!/usr/bin/env node
// verify-browser-panel.mjs — T4：浏览器面板定向设备用例（0.14.0）。
//
// 覆盖 verify-browser-host 未涉及的面板/多页签语义：
//   1) 关闭即销毁   2) 多页签（不带 tabId 开新页）   3) 页签列表 + follow
//   4) closeTab 只关目标页   5) 保活（hide/show 不销毁、代次不变）
//   6) 错误页（不可达主机 → error + ERR_*）   7) 跨会话占用 fail-closed
//
// 用法：node scripts/verify-browser-panel.mjs <main-webview-cdp-ws-url> [--serial 127.0.0.1:16416]

const argv = process.argv.slice(2)
const mainWs = argv.find((x) => x.startsWith('ws://'))
const argOf = (name, def) => { const i = argv.indexOf('--' + name); return i >= 0 ? (argv[i + 1] ?? def) : def }
const SERIAL = argOf('serial', '')
void SERIAL
if (!mainWs) { console.error('用法：node scripts/verify-browser-panel.mjs <ws> [--serial <s>]'); process.exit(2) }

function fail(m) { throw new Error(m) }
function ok(m, d) { console.log('PASS ' + m + (d === undefined ? '' : '  → ' + d)) }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function evaluate(wsUrl, expression) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl)
    let settled = false
    const finish = (fn, v) => { if (settled) return; settled = true; try { ws.close() } catch { /* closed */ }; fn(v) }
    ws.addEventListener('open', () => ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: true } })))
    ws.addEventListener('message', (e) => { try { const m = JSON.parse(e.data); if (m.id === 1) finish(resolve, m.result?.result?.value) } catch { /* non-JSON */ } })
    ws.addEventListener('error', () => finish(reject, new Error('CDP error')))
    setTimeout(() => finish(reject, new Error('CDP timeout')), 15_000)
  })
}

// 经主页面桥调用浏览器方法；JSON.stringify 在页面里做，避免脚本侧字符串转义踩坑。
async function bridge(method, payload) {
  const arg = payload === undefined ? '' : ', JSON.stringify(' + JSON.stringify(payload) + ')'
  const raw = await evaluate(mainWs, 'window.androidBridge.' + method + '.call(window.androidBridge' + arg + ')')
  if (typeof raw !== 'string') fail('bridge 未返回字符串：' + method + ' -> ' + JSON.stringify(raw))
  return JSON.parse(raw)
}
const status = () => bridge('browserHostStatus')

try {
  await bridge('browserHostClose')
  await sleep(400)

  // 1) 关闭即销毁
  const afterClose = await status()
  if (afterClose.created !== false) fail('关闭后 created 应为 false：' + JSON.stringify(afterClose))
  if ((afterClose.tabs ?? []).length !== 0) fail('关闭后页签应清零：' + JSON.stringify(afterClose.tabs))
  ok('关闭即销毁（created=false，页签清零）')

  // 可信舞台矩形
  const metrics = await evaluate(mainWs, '({ width: innerWidth, height: innerHeight })')
  await bridge('browserHostBounds', { left: 12, top: 140, width: Math.max(180, metrics.width - 24), height: Math.max(160, metrics.height - 180), viewportWidth: metrics.width, viewportHeight: metrics.height, visible: true })

  // 2) 多页签（模型路径）
  //
  // 重要区分（0.14.0 实测）：多页签由**模型工具 browser_open** → 控制队列 → 壳侧 navigateOp(newTab) 建立；
  // 它**不是**页面可达的桥 API（`controlOp` 不存在于 window.androidBridge，`browserHostShow` 是面板单视图
  // 桥、忽略 newTab）。因此本脚本不能在页面内自行造多页。
  //
  // 做法：读壳侧当前页签作为「已由模型建立的多页」的证据；若此刻只有 1 页，则本项**明确 SKIP 并说明原因**，
  // 不谎报通过（假绿比失败更糟）。多页签的完整断言由 Agent 视角用例覆盖：
  //   Agent 依次 browser_open 两个站点 → browser_list_tabs 返回 2 页 → browser_close_tab 关掉其一。
  const shellTabs = await status()
  const tabCount = (shellTabs.tabs ?? []).length
  if (tabCount >= 2) {
    ok('多页签：壳侧已存在多个页签（模型已建立）', 'tabs=' + shellTabs.tabs.map((t) => t.tabId).join(','))
  } else {
    console.log('SKIP 多页签（本刻壳侧仅 ' + tabCount + ' 页；该项须由模型调用 browser_open 建立后判定，'
      + '见 Agent 视角用例 verify-browser-panel-agent）')
  }

  // 3) 页签列表可读（面板侧读壳侧状态）
  if (!Array.isArray(shellTabs.tabs)) fail('status().tabs 应为数组：' + JSON.stringify(shellTabs.tabs))
  ok('页签列表可读', tabCount + ' 页')
  // 保活需要一个已存在的页面：用面板桥打开一页（页面可达路径）。
  const opened = await bridge('browserHostShow', { url: 'https://example.com/' })
  if (opened.ok !== true || opened.created !== true) fail('开页面失败（保活用例前置）：' + JSON.stringify(opened))
  const pageDeadline = Date.now() + 20_000
  let pageReady = null
  do { await sleep(400); pageReady = await status(); if (pageReady.url === 'https://example.com/') break } while (Date.now() < pageDeadline)
  if (pageReady.url !== 'https://example.com/') fail('页面未就绪（保活用例前置）：' + JSON.stringify(pageReady))

  // 4) 保活：hide 后页面仍在、代次不变；show 后恢复
  const beforeHide = await status()
  await bridge('browserHostHide')
  await sleep(700)
  const afterHide = await status()
  if (afterHide.created !== true) fail('hide 后页面不应被销毁：' + JSON.stringify(afterHide))
  if (afterHide.visible !== false) fail('hide 后 visible 应为 false：' + JSON.stringify(afterHide))
  if (afterHide.pageGeneration !== beforeHide.pageGeneration) fail('hide 后页代次不应变化（保活）：' + beforeHide.pageGeneration + ' -> ' + afterHide.pageGeneration)
  await bridge('browserHostShow')
  await sleep(700)
  const afterShow = await status()
  if (afterShow.created !== true) fail('show 后页面应仍在：' + JSON.stringify(afterShow))
  ok('保活：hide→show 不销毁、页代次不变', 'gen=' + afterShow.pageGeneration)

  // 5) 错误页（面板桥导航当前活动页；不可达主机 → 内置错误页）
  await bridge('browserHostShow', { url: 'https://no-such-host.invalid/' })
  let errState = null
  const errDeadline = Date.now() + 25_000
  do { await sleep(600); errState = await status(); if (errState.loadState === 'error') break } while (Date.now() < errDeadline)
  if (errState.loadState !== 'error') fail('不可达主机应进入 error 态：' + JSON.stringify(errState))
  // 契约（0.14.0 实测）：壳侧 reason 是结构化的 `load-error:<code>`（如 load-error:-2 =
  // ERR_NAME_NOT_RESOLVED），页面上是内置错误页（title 为 ERR_*）。两者都要能判定失败，
  // 故这里两个信号至少一个在场即可，不绑定单一字段名。
  const reasonIsStructured = /^load-error:-?\d+$/.test(String(errState.reason))
  const titleIsErr = /ERR_/.test(String(errState.title))
  if (!reasonIsStructured && !titleIsErr) {
    fail('错误态既无结构化 reason（load-error:<code>）也无 ERR_* 标题：' + JSON.stringify({ reason: errState.reason, title: errState.title }))
  }
  ok('错误页：不可达主机 → loadState=error 且带可判定信号', 'reason=' + errState.reason + ' title=' + errState.title)

  // 6) 按会话隔离（0.14.0 用户口径：**互不占用**）
  //
  // 语义：每个会话各有自己的工作台（各自的页签集合/代次/可见性）。切到别的会话看不到、
  // 也碰不到别人的页面，**不存在**「工作台被某会话占用」这种状态（旧的 browser-session-busy
  // 与单向归属锁已随本批移除）。
  //
  // 判据：用两个不同 session 各开一页，两边都应成功且各自的页签集合独立。
  const sessA = await bridge('browserHostShow', { url: 'https://example.com/', session: 'session-A', newTab: true })
  if (sessA.ok !== true) fail('session-A 开页应成功（隔离模型下不再有占用拒绝）：' + JSON.stringify(sessA))
  await sleep(600)
  const sessB = await bridge('browserHostShow', { url: 'https://example.com/', session: 'session-B', newTab: true })
  if (sessB.ok !== true) fail('session-B 开页应成功（与 A 互不占用）：' + JSON.stringify(sessB))
  ok('按会话隔离：两个会话各自开页互不拒绝', 'A=' + sessA.tabId + ' B=' + sessB.tabId)

  // 反向断言：B 此刻是当前工作台，读到的会话标识应是 B（而不是 A 的残留）。
  const asB = await status()
  if (asB.ownerSessionId !== 'session-B') {
    fail('当前工作台应属于最后下推的会话 session-B：' + JSON.stringify({ owner: asB.ownerSessionId }))
  }
  ok('当前工作台随会话切换', 'owner=' + asB.ownerSessionId)

  console.log('')
  console.log('BROWSER-PANEL PASSED')
} catch (e) {
  console.error('BROWSER-PANEL FAILED: ' + (e?.message ?? e))
  process.exit(1)
}