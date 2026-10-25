#!/usr/bin/env node
// verify-vdisplay-viewer.mjs — 虚拟屏查看器回归（0.14.0 批：自动露出 / 关闭不销毁 / 重开重挂 / 实时选择器 / 多查看器仲裁）。
//
// 用法：
//   node scripts/verify-vdisplay-viewer.mjs --ws <主 WebView CDP ws> [--serial 127.0.0.1:16416] [--keep]
//
// 步骤（全部走可信页面桥 + 原生状态；断言失败即 FAIL，不猜）：
//   A. 基线：vdisplayStatus 有 screens[]，real 恒不可镜像（selectable=false 且有 reason）
//   B. vdisplayCreate：active 且 displayId != 0（绝不映射真实屏）
//   C. 自动露出：等待 [data-testid="vdisplay-stage"] 挂载且 viewers[] 出现 presenting=true（原生 Surface 已挂）
//   D. 选择器：vdisplaySelect('real') 必须拒绝 screen-not-selectable；vdisplaySelect('virtual-1') 成功且 selected 收敛
//   E. 关闭查看器（发布 visible:false）：display 仍 active（dumpsys display 仍在场，若提供 serial）
//   F. 重开重挂：再次发布 visible:true，viewers[] presenting 恢复 true
//   G. 清理：--keep 时保留虚拟屏，否则 vdisplayDestroy 并断言 screens 中虚拟屏消失
import { spawnSync } from 'node:child_process'

const argv = process.argv.slice(2)
const argOf = (name) => { const i = argv.indexOf('--' + name); return i >= 0 ? argv[i + 1] : undefined }
const wsUrl = argOf('ws')
const serial = argOf('serial')
const keep = argv.includes('--keep')
if (!wsUrl) {
  console.error('Usage: node scripts/verify-vdisplay-viewer.mjs --ws <main-webview-cdp-ws> [--serial <adb-serial>] [--keep]')
  process.exit(2)
}

function fail(message) { throw new Error(message) }
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

function evaluate(targetWs, expression) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(targetWs)
    let settled = false
    const finish = (fn, value) => {
      if (settled) return
      settled = true
      try { ws.close() } catch {}
      fn(value)
    }
    ws.addEventListener('open', () => {
      ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: true } }))
    })
    ws.addEventListener('message', (event) => {
      const message = JSON.parse(event.data)
      if (message.id !== 1) return
      if (message.result?.exceptionDetails) {
        finish(reject, new Error(message.result.exceptionDetails.text ?? 'CDP evaluation failed'))
        return
      }
      finish(resolve, message.result?.result?.value)
    })
    ws.addEventListener('error', () => finish(reject, new Error('CDP WebSocket error')))
    setTimeout(() => finish(reject, new Error('CDP evaluation timeout')), 20_000)
  })
}

function bridge(expression) {
  return evaluate(wsUrl, `(() => { const raw = ${expression}; return JSON.parse(raw); })()`)
}

function adb(args) {
  if (serial === undefined) return ''
  const r = spawnSync('adb', ['-s', serial, ...args], { encoding: 'utf8' })
  return (r.stdout ?? '') + (r.stderr ?? '')
}

async function waitFor(label, probe, timeoutMs = 10_000) {
  const deadline = Date.now() + timeoutMs
  let last
  while (Date.now() < deadline) {
    last = await probe()
    if (last?.ok === true) return last
    await sleep(250)
  }
  fail(label + ' 超时：' + JSON.stringify(last))
}

const results = {}
try {
  // A. 基线屏幕注册表
  const base = await bridge('window.androidBridge.vdisplayStatus()')
  const real = (base.screens ?? []).find((s) => s.alias === 'real')
  if (real === undefined) fail('screens[] 缺少 real 条目: ' + JSON.stringify(base.screens))
  if (real.selectable !== false || !real.reason) fail('real 屏幕必须不可镜像且带 reason: ' + JSON.stringify(real))
  if (real.displayId !== 0) fail('real 的 displayId 必须为 0: ' + JSON.stringify(real))
  results.baseline = { state: base.state, screens: base.screens?.map((s) => s.alias + '=' + s.displayId) }

  // B. 创建（幂等：若已 active 则沿用，但断言 displayId != 0）
  let status = await bridge('window.androidBridge.vdisplayStatus()')
  if (status.state !== 'active') status = await bridge('window.androidBridge.vdisplayCreate()')
  if (status.state !== 'active') fail('创建后必须 active: ' + JSON.stringify(status))
  if (typeof status.displayId !== 'number' || status.displayId === 0) fail('虚拟屏 displayId 必须是动态非 0 值: ' + JSON.stringify(status))
  const virtual = status.selected ?? (status.screens ?? []).find((s) => s.kind === 'virtual')?.alias
  if (typeof virtual !== 'string') fail('找不到 virtual 别名: ' + JSON.stringify(status))
  results.create = { displayId: status.displayId, alias: virtual, state: status.state }

  // C. 自动露出（0.14.0 用户语义：**收起态建窗但不强制展开**；人手动展开即可见）。
  //
  // 因此本条分两相断言，而不是无条件等 stage 挂载：
  //   C1 收起态：**必须不强制展开**（展开控件仍在场）+ stage 可以尚未挂载；
  //   C2 手动展开后：pending 兑现 → stage 挂载 + viewers presenting=true。
  //
  // 历史注记：本脚本早期版本无条件等 C1 挂载，那等价于要求「强制展开」——与用户语义相反，
  // 且会把正确实现判成回归（0.14.0 实测踩到）。判据必须按「用户是否已展开」分支。
  const expandControlPresent = async () =>
    (await evaluate(wsUrl, 'document.querySelector(\'[data-sidebar-right-expand]\') !== null')) === true
  // 先确保处于收起态（若已展开，点统一开关收起）——C1 的前提。
  if (!(await expandControlPresent())) {
    await evaluate(wsUrl, 'document.querySelector(\'[data-sidebar-right-toggle]\')?.click()')
    await sleep(600)
  }
  if (!(await expandControlPresent())) {
    fail('C1 前提不成立：未能把右侧栏置于收起态（展开控件应存在）')
  }
  // 收起态的正确契约 = 面板**仍在 DOM（保活）但不可见**（上游以 visibility:hidden 隐藏），
  // 而不是「必须卸载」。同时不得被程序自动展开（展开控件必须仍在场）。
  await sleep(900)
  if (!(await expandControlPresent())) {
    fail('C1 违反用户语义：收起态下不应被程序自动展开（展开控件应仍在场）')
  }
  const collapsedProbe = JSON.parse(String(await evaluate(wsUrl, `(() => {
    const st = document.querySelector('[data-testid="vdisplay-stage"]');
    return JSON.stringify({
      inDom: st !== null,
      hidden: st === null ? null : (getComputedStyle(st).visibility === 'hidden' || getComputedStyle(st).display === 'none'),
      expandCtl: document.querySelector('[data-sidebar-right-expand]') !== null,
    });
  })()`)))
  if (collapsedProbe.expandCtl !== true) fail('C1 前提失效：应收起（展开控件在场）')
  if (collapsedProbe.inDom === true && collapsedProbe.hidden !== true) {
    fail('C1 违反用户语义：收起态下虚拟屏舞台必须不可见（实测仍可见）: ' + JSON.stringify(collapsedProbe))
  }
  results.collapsedNoForceExpand = collapsedProbe
  // C2：用户手动展开 → 延迟落位兑现。
  await evaluate(wsUrl, 'document.querySelector(\'[data-sidebar-right-toggle]\')?.click()')
  await waitFor('C2 手动展开后查看器落位（stage 挂载）', async () => {
    const mounted = await evaluate(wsUrl, 'document.querySelectorAll(\'[data-testid="vdisplay-stage"]\').length > 0')
    return { ok: mounted === true }
  }, 15_000)
  const attached = await waitFor('查看器 Surface 挂载（viewers presenting）', async () => {
    const s = await bridge('window.androidBridge.vdisplayStatus()')
    const viewer = (s.viewers ?? []).find((v) => v.presenting === true && v.target === virtual)
    return viewer === undefined ? { ok: false, viewers: s.viewers } : { ok: true, viewer }
  }, 15_000)
  results.autoReveal = attached.viewer

  // D. 选择器：真实屏必须被拒绝；虚拟屏选择必须收敛到 selected
  const denied = await bridge(`window.androidBridge.vdisplaySelect('real')`)
  if (denied.ok !== false || denied.code !== 'screen-not-selectable') {
    fail('选择真实屏必须拒绝 screen-not-selectable: ' + JSON.stringify(denied))
  }
  const selected = await bridge(`window.androidBridge.vdisplaySelect('${virtual}')`)
  if (selected.ok !== true || selected.selected !== virtual) {
    fail('选择虚拟屏必须成功且 selected 收敛: ' + JSON.stringify(selected))
  }
  results.selector = { real: denied.code, virtual: selected.selected }

  // E. 关闭查看器：display 不销毁
  const stageRect = await evaluate(wsUrl, '(() => { const el = document.querySelector(\'[data-testid="vdisplay-stage"]\'); if (!el) return null; const r = el.getBoundingClientRect(); return { left: r.left, top: r.top, width: r.width, height: r.height, vw: window.innerWidth, vh: window.innerHeight }; })()')
  if (stageRect === null) fail('stage 元素缺席，无法发布关闭事件')
  const hidden = await bridge(`window.androidBridge.vdisplayBounds(JSON.stringify({ left: ${stageRect.left}, top: ${stageRect.top}, width: ${stageRect.width}, height: ${stageRect.height}, viewportWidth: ${stageRect.vw}, viewportHeight: ${stageRect.vh}, visible: false, viewerId: 'files-sidebar', target: '${virtual}' }))`)
  if (hidden.ok !== true || hidden.presenting === true) fail('visible:false 后不得仍 presenting: ' + JSON.stringify(hidden))
  const afterHide = await bridge('window.androidBridge.vdisplayStatus()')
  if (afterHide.state !== 'active') fail('关闭查看器后 display 必须保持 active: ' + JSON.stringify(afterHide))
  const dump = adb(['shell', 'dumpsys', 'display'])
  if (serial !== undefined) {
    const holdsDisplay = dump.includes('type VIRTUAL') && dump.includes('com.ai.assistance.quro.dshmobile')
    if (!holdsDisplay) fail('dumpsys display 中虚拟屏缺席（display 被误销毁）')
  }
  results.closeKeepsDisplay = { state: afterHide.state, displayId: afterHide.displayId, dumpsysVirtual: serial === undefined ? 'skipped' : true }

  // F. 重开重挂
  const shown = await bridge(`window.androidBridge.vdisplayBounds(JSON.stringify({ left: ${stageRect.left}, top: ${stageRect.top}, width: ${stageRect.width}, height: ${stageRect.height}, viewportWidth: ${stageRect.vw}, viewportHeight: ${stageRect.vh}, visible: true, viewerId: 'files-sidebar', target: '${virtual}' }))`)
  if (shown.ok !== true) fail('重开发布失败: ' + JSON.stringify(shown))
  const reattached = await waitFor('重开重挂（presenting 恢复）', async () => {
    const s = await bridge('window.androidBridge.vdisplayStatus()')
    const viewer = (s.viewers ?? []).find((v) => v.presenting === true && v.target === virtual)
    return viewer === undefined ? { ok: false, viewers: s.viewers } : { ok: true, viewer }
  }, 10_000)
  results.reattach = reattached.viewer

  // G. 清理
  if (!keep) {
    const destroyed = await bridge('window.androidBridge.vdisplayDestroy()')
    const virtualLeft = (destroyed.screens ?? []).some((s) => s.kind === 'virtual')
    if (virtualLeft) fail('销毁后 screens 里不应再有虚拟屏: ' + JSON.stringify(destroyed.screens))
    results.destroy = { state: destroyed.state, screens: destroyed.screens?.map((s) => s.alias) }
  } else {
    results.destroy = 'kept'
  }

  console.log(JSON.stringify({ ok: true, results }, null, 2))
} catch (error) {
  console.error('VDISPLAY-VIEWER-REGRESSION FAILED: ' + String(error?.stack ?? error))
  process.exitCode = 1
}