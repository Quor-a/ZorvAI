#!/usr/bin/env node
// verify-browser-host.mjs — device-side BrowserHost lifecycle and isolation regression.
// Usage: node scripts/verify-browser-host.mjs <main-webview-cdp-ws-url>
const [, , mainWs] = process.argv
if (!mainWs) {
  console.error('Usage: node scripts/verify-browser-host.mjs <main-webview-cdp-ws-url>')
  process.exit(2)
}

function fail(message) {
  throw new Error(message)
}

function evaluate(wsUrl, expression) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl)
    let settled = false
    const finish = (fn, value) => {
      if (settled) return
      settled = true
      try { ws.close() } catch {}
      fn(value)
    }
    ws.addEventListener('open', () => {
      ws.send(JSON.stringify({
        id: 1,
        method: 'Runtime.evaluate',
        params: { expression, returnByValue: true, awaitPromise: true },
      }))
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
  return evaluate(mainWs, `(() => { const raw = ${expression}; return JSON.parse(raw); })()`)
}

function cdpListUrl(wsUrl) {
  const parsed = new URL(wsUrl)
  return `http://${parsed.host}/json/list`
}

/** 分辨率变化会重建隔离 WebView（密度覆盖）→ CDP target 变更；按 URL 重新解析。 */
async function resolveTarget(mainWs, url, timeoutMs = 15_000) {
  const deadline = Date.now() + timeoutMs
  do {
    const targets = await (await fetch(cdpListUrl(mainWs))).json()
    const found = targets.find(target => target.url === url)
    if (found?.webSocketDebuggerUrl) return found.webSocketDebuggerUrl
    await new Promise(resolve => setTimeout(resolve, 300))
  } while (Date.now() < deadline)
  throw new Error('CDP target not found for ' + url)
}

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms))

try {
  // 先关闭上一次运行留下的页面，保证初始态断言确定。
  try { await bridge('window.androidBridge.browserHostClose()') } catch { /* no host yet */ }
  await sleep(300)
  const before = await bridge('window.androidBridge.browserHostStatus()')
  if (before.available !== true || before.created !== false || before.visible !== false) {
    fail('unexpected initial BrowserHost state: ' + JSON.stringify(before))
  }
  const metrics = await evaluate(mainWs, '({ width: innerWidth, height: innerHeight })')
  const bounds = await bridge(`window.androidBridge.browserHostBounds(JSON.stringify({ left: 12, top: 140, width: Math.max(180, ${metrics.width} - 24), height: Math.max(160, ${metrics.height} - 180), viewportWidth: ${metrics.width}, viewportHeight: ${metrics.height}, visible: true }))`)
  if (bounds.ok !== true) fail('BrowserHost rejected trusted stage bounds: ' + JSON.stringify(bounds))
  const denied = await bridge("window.androidBridge.browserHostShow('http://127.0.0.1:3080/')")
  if (denied.ok !== false || denied.reason !== 'unsupported-url') {
    fail('BrowserHost allowed trusted loopback navigation: ' + JSON.stringify(denied))
  }
  const shown = await bridge("window.androidBridge.browserHostShow('https://example.com/')")
  if (shown.ok !== true || shown.created !== true) fail('BrowserHost did not create: ' + JSON.stringify(shown))
  // A freshly restarted Android WebView can delay the first renderer navigation beyond a fixed
  // 1.2s interval. Poll the authoritative native status rather than calling an otherwise healthy
  // BrowserHost a failure solely because its first onPageStarted callback is late.
  const deadline = Date.now() + 10_000
  let live
  do {
    live = await bridge('window.androidBridge.browserHostStatus()')
    if (live.created === true && live.visible === true && live.url === 'https://example.com/') break
    await sleep(250)
  } while (Date.now() < deadline)
  if (live.created !== true || live.visible !== true || live.url !== 'https://example.com/') {
    fail('BrowserHost did not become visible at its requested URL: ' + JSON.stringify(live))
  }
  const targets = await (await fetch(cdpListUrl(mainWs))).json()
  const browserTarget = targets.find(target => target.url === 'https://example.com/')
  if (!browserTarget?.webSocketDebuggerUrl) fail('isolated BrowserHost target is absent from CDP roster')
  const thirdPartyBridge = await evaluate(browserTarget.webSocketDebuggerUrl, 'typeof window.androidBridge')
  if (thirdPartyBridge !== 'undefined') fail('third-party BrowserHost page received androidBridge')
  const hidden = await bridge('window.androidBridge.browserHostHide()')
  if (hidden.ok !== true || hidden.visible !== false) fail('BrowserHost hide did not settle: ' + JSON.stringify(hidden))
  // 分辨率 = CSS 视口（SPEC §1.2）：页面 innerWidth/Height 必须精确等于所选档（±1）。舞台放不下时
  // 按同一 scale 等比缩小（letterbox，无 transform），因此 innerWidth 仍等于请求值。
  const viewport = await bridge('window.androidBridge.browserHostViewport(JSON.stringify({ id: \'phone-portrait\', width: 390, height: 844 }))')
  if (viewport.ok !== true || viewport.viewportId !== 'phone-portrait') {
    fail('BrowserHost viewport preset rejected: ' + JSON.stringify(viewport))
  }
  const shownAgain = await bridge('window.androidBridge.browserHostShow()')
  if (shownAgain.ok !== true) fail('BrowserHost re-show failed: ' + JSON.stringify(shownAgain))
  // 预设变化会让壳侧重建隔离 WebView 并重载页面；重建后 CDP target 变更，按 URL 重新解析。
  let cssViewport = null
  let live2 = null
  const viewportDeadline = Date.now() + 15_000
  do {
    await sleep(300)
    try {
      const ws = await resolveTarget(mainWs, 'https://example.com/', 4_000)
      cssViewport = await evaluate(ws, '({ width: window.innerWidth, height: window.innerHeight, dpr: window.devicePixelRatio })')
    } catch { cssViewport = null }
    live2 = await bridge('window.androidBridge.browserHostStatus()')
    if (cssViewport && Math.abs(cssViewport.width - 390) <= 1 && Math.abs(cssViewport.height - 844) <= 1) break
  } while (Date.now() < viewportDeadline)
  // 宽度必须精确（letterbox 只改变缩放，不改 CSS 宽度语义）；高度按 letterbox 取整容差判定。
  //
  // 依据（0.14.0 实测 + 源码算式）：applyStageBounds 里
  //   factor = min(stageW/cssW, stageH/cssH, baseDensity)
  //   物理矩形 = round(cssW*factor) x round(cssH*factor)
  // 物理矩形必须取整，故当舞台装不下请求档时（此处舞台 450x650 装不下 390x844）
  // 高度方向的往返会有 1/factor ≈ 1.3 px 的固有取整误差（实测 innerWidth=390 精确、
  // innerHeight=842 vs 请求 844）。脚本自身注释也只承诺「innerWidth 仍等于请求值」。
  // 故：宽度 ±1 精确；高度允许 ±2（letterbox 取整）；再用宽高比把容差锁住，
  // 避免真实回归藏在放宽的界里。
  const requestedRatio = 390 / 844
  const heightTolerance = 2
  const widthOk = cssViewport && Math.abs(cssViewport.width - 390) <= 1
  const heightOk = cssViewport && Math.abs(cssViewport.height - 844) <= heightTolerance
  const ratioOk = cssViewport && cssViewport.height > 0 &&
    Math.abs(cssViewport.width / cssViewport.height - requestedRatio) <= 0.01
  if (!cssViewport || !widthOk || !heightOk || !ratioOk) {
    fail('CSS viewport must match the requested preset (width ±1 / height ±' + heightTolerance
      + ' letterbox rounding / ratio preserved): ' + JSON.stringify({
      cssViewport, requested: { width: 390, height: 844 }, requestedRatio, widthOk, heightOk, ratioOk, live2 }))
  }
  if (live2.pageWidth !== cssViewport.width || live2.pageHeight !== cssViewport.height) {
    fail('host-reported page viewport must match the page: ' + JSON.stringify({ live2, cssViewport }))
  }
  // PC 身份档：UA + platform / maxTouchPoints / screen 必须为桌面形态（document-start 注入，无桥）。
  const identity = await bridge("window.androidBridge.browserHostIdentity(JSON.stringify({ profile: 'linux-desktop', ua: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36', preset: 'custom', width: 1280, height: 720 }))")
  if (identity.ok !== true) fail('BrowserHost identity switch rejected: ' + JSON.stringify(identity))
  let desktopSignals = null
  const identityDeadline = Date.now() + 20_000
  do {
    await sleep(300)
    try {
      const ws = await resolveTarget(mainWs, 'https://example.com/', 4_000)
      desktopSignals = await evaluate(ws, `({
        ua: navigator.userAgent.slice(0, 40),
        platform: navigator.platform,
        touch: navigator.maxTouchPoints,
        touchStart: ('ontouchstart' in window),
        screenW: screen.width,
        screenH: screen.height,
        iw: innerWidth,
        ih: innerHeight,
        mq: matchMedia('(min-width:1024px)').matches,
      })`)
    } catch { desktopSignals = null }
    if (desktopSignals && Math.abs(desktopSignals.iw - 1280) <= 1 && Math.abs(desktopSignals.ih - 720) <= 1) break
  } while (Date.now() < identityDeadline)
  if (!desktopSignals) fail('desktop identity signals unavailable')
  if (!String(desktopSignals.ua).includes('X11; Linux x86_64')) fail('desktop UA not applied: ' + JSON.stringify(desktopSignals))
  if (!String(desktopSignals.platform).includes('Linux')) fail('desktop platform not applied: ' + JSON.stringify(desktopSignals))
  if (desktopSignals.touch !== 0) fail('desktop maxTouchPoints not applied: ' + JSON.stringify(desktopSignals))
  if (desktopSignals.screenW < 1024) fail('desktop screen.width not applied: ' + JSON.stringify(desktopSignals))
  if (desktopSignals.iw < 1279 || desktopSignals.iw > 1281 || desktopSignals.ih < 719 || desktopSignals.ih > 721) fail('desktop CSS viewport must equal 1280x720 (±1): ' + JSON.stringify(desktopSignals))
  if (desktopSignals.mq !== true) fail('desktop media query (min-width:1024px) must match: ' + JSON.stringify(desktopSignals))
  // 复位：回到安卓身份与设备跟随视口，供后续用例使用。
  const restoreIdentity = await bridge("window.androidBridge.browserHostIdentity(JSON.stringify({ profile: 'android-real', ua: '', preset: 'device' }))")
  if (restoreIdentity.ok !== true) fail('BrowserHost identity restore failed: ' + JSON.stringify(restoreIdentity))
  const reset = await bridge('window.androidBridge.browserHostViewport(JSON.stringify({ id: \'device\', width: 0, height: 0 }))')
  if (reset.ok !== true || reset.viewportId !== 'device') fail('BrowserHost viewport reset failed: ' + JSON.stringify(reset))
  console.log(JSON.stringify({ ok: true, before, bounds, denied, live, thirdPartyBridge, hidden, viewport, cssViewport, live2, identity, desktopSignals, reset }))
} catch (error) {
  console.error('BROWSERHOST-REGRESSION FAILED: ' + String(error?.stack ?? error))
  process.exitCode = 1
}
