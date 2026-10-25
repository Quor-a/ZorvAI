#!/usr/bin/env node
// verify-vdisplay-float.mjs — T5：虚拟屏浮窗 + 档位 + 选择器置灰（0.14.0）。
//
// verify-vdisplay-viewer 已覆盖：自动露出（两相）/ 关闭不销毁 / 重开重挂 / 实时选择器 / 多查看器仲裁 / 销毁。
// 本脚本补它**没覆盖**的部分：
//   1) 浮窗开关真源往返：setVdisplayFloatEnabled(true/false) 后 getVdisplayFloatEnabled 收敛；
//   2) 档位（densityDpi 同比）：setVdisplayScale 0.5/0.75/1 后 getVdisplayScale 收敛且为三个合法档；
//   3) 选择器置灰：真实屏 selectable=false 且带 reason（不可镜像）；
//   4) 强制销毁通道在场（设置页三连点的底层入口 forceDestroyVdisplay 可达）；
//   5) 非法档位必须被拒（不静默接受）。
//
// 用法：node scripts/verify-vdisplay-float.mjs --ws <main-webview-cdp-ws> [--serial 127.0.0.1:16416]

import { booleanReadbackMatches } from './vdisplay-float-contract.mjs'

const argv = process.argv.slice(2)
const argOf = (name, def) => { const i = argv.indexOf('--' + name); return i >= 0 ? (argv[i + 1] ?? def) : def }
const WS = argOf('ws', '')
const SERIAL = argOf('serial', '')
void SERIAL
if (!WS) { console.error('用法：--ws <cdp-ws> [--serial <s>]'); process.exit(2) }

function fail(m) { console.error('VDISPLAY-FLOAT FAILED: ' + m); process.exit(1) }
function ok(m, d) { console.log('PASS ' + m + (d === undefined ? '' : '  → ' + d)) }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function evaluate(expression) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS)
    let settled = false
    const finish = (fn, v) => { if (settled) return; settled = true; try { ws.close() } catch { /* closed */ }; fn(v) }
    ws.addEventListener('open', () => ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: true } })))
    ws.addEventListener('message', (e) => { try { const m = JSON.parse(e.data); if (m.id === 1) finish(resolve, m.result?.result?.value) } catch { /* non-JSON */ } })
    ws.addEventListener('error', () => finish(reject, new Error('CDP error')))
    setTimeout(() => finish(reject, new Error('CDP timeout')), 15_000)
  })
}

// 桥方法返回形态（0.14.0 实测）：浮窗开关与档位是**裸原始值**（boolean / number），
// 不是 {ok:true} JSON 对象；vdisplayStatus/forceDestroy 才是 JSON 字符串。
async function callBridge(expr) {
  const raw = await evaluate(expr)
  return typeof raw === 'string' ? JSON.parse(raw) : raw
}
const readBool = (v) => v === true || v?.enabled === true
const readNum = (v) => (typeof v === 'number' ? v : v?.scale)

try {
  // 0) 前置：确认桥面在场
  const surface = await evaluate('Object.keys(window.androidBridge).filter((k) => /vdisplay/i.test(k))')
  for (const need of ['getVdisplayFloatEnabled', 'setVdisplayFloatEnabled', 'setVdisplayScale', 'getVdisplayScale', 'forceDestroyVdisplay']) {
    if (!surface.includes(need)) fail('桥面缺 ' + need + '（当前：' + surface.join(',') + '）')
  }
  ok('浮窗/档位/强制销毁桥面在场', surface.length + ' 个 vdisplay* 方法')

  // 1) 浮窗开关往返（记忆原值，测完还原）
  const original = await callBridge('window.androidBridge.getVdisplayFloatEnabled()')
  const originalEnabled = readBool(original)
  const flipped = !originalEnabled
  const set1 = await callBridge('window.androidBridge.setVdisplayFloatEnabled(' + String(flipped) + ')')
  await sleep(400)
  const read1 = await callBridge('window.androidBridge.getVdisplayFloatEnabled()')
  const read1Enabled = readBool(read1)
  if (!booleanReadbackMatches(flipped, read1Enabled)) fail('浮窗开关未收敛：set=' + flipped + ' returned=' + JSON.stringify(set1) + ' readback=' + JSON.stringify(read1))
  ok('浮窗开关真源往返', originalEnabled + ' -> ' + flipped + ' (returned=' + JSON.stringify(set1) + ')')
  // 还原
  await callBridge('window.androidBridge.setVdisplayFloatEnabled(' + String(originalEnabled) + ')')
  await sleep(300)
  const restored = await callBridge('window.androidBridge.getVdisplayFloatEnabled()')
  const restoredEnabled = readBool(restored)
  if (restoredEnabled !== originalEnabled) fail('浮窗开关未能还原：' + JSON.stringify(restored))
  ok('浮窗开关已还原', String(originalEnabled))

  // 2) 档位收敛（0.5 / 0.75 / 1）
  const beforeScale = await callBridge('window.androidBridge.getVdisplayScale()')
  const scales = [0.5, 0.75, 1]
  for (const s of scales) {
    const setr = await callBridge('window.androidBridge.setVdisplayScale(' + s + ')')
    if (typeof setr !== 'number' && setr?.ok !== true) fail('设置档位 ' + s + ' 失败：' + JSON.stringify(setr))
    await sleep(350)
    const got = await callBridge('window.androidBridge.getVdisplayScale()')
    const value = readNum(got)
    if (Math.abs(Number(value) - s) > 1e-6) fail('档位未收敛：set=' + s + ' get=' + JSON.stringify(got))
  }
  ok('档位收敛（0.5 / 0.75 / 1 三档）')
  // 还原
  const beforeValue = readNum(beforeScale)
  if (typeof beforeValue === 'number' && Number.isFinite(beforeValue)) {
    await callBridge('window.androidBridge.setVdisplayScale(' + beforeValue + ')')
    await sleep(300)
    ok('档位已还原', String(beforeValue))
  }

  // 3) 非法档位必须被拒（不静默接受）
  // 非法档位：壳侧应拒绝（返回 false/ok:false）或保持原值不采纳。
  const bad = await callBridge('window.androidBridge.setVdisplayScale(0.123)')
  if (bad === true || bad?.ok === true) {
    const nowScale = await callBridge('window.androidBridge.getVdisplayScale()')
    const nowValue = readNum(nowScale)
    if (Math.abs(Number(nowValue) - 0.123) <= 1e-6) fail('非法档位 0.123 被静默接受并生效')
    ok('非法档位未被采纳（ok:true 但值未变）', JSON.stringify(nowValue))
  } else {
    ok('非法档位被拒', bad?.code ?? bad?.reason ?? 'rejected')
  }

  // 4) 选择器置灰：真实屏不可选且带 reason
  const status = await callBridge('window.androidBridge.vdisplayStatus()')
  const real = (status.screens ?? []).find((s) => s.alias === 'real')
  if (real === undefined) fail('screens[] 缺 real 条目：' + JSON.stringify(status.screens))
  if (real.selectable !== false) fail('真实屏必须不可选（置灰）：' + JSON.stringify(real))
  if (typeof real.reason !== 'string' || real.reason.length === 0) fail('置灰必须带 reason（否则是静默禁用）：' + JSON.stringify(real))
  ok('选择器置灰：real selectable=false 且带 reason')

  // 5) 强制销毁通道在场且可用（当前无活跃屏时应为幂等成功）
  const fd = await callBridge('window.androidBridge.forceDestroyVdisplay()')
  if (fd?.ok !== true) fail('forceDestroyVdisplay 应幂等成功：' + JSON.stringify(fd))
  ok('强制销毁通道在场', 'state=' + String(fd.state ?? ''))

  console.log('')
  console.log('VDISPLAY-FLOAT PASSED')
} catch (e) {
  console.error('VDISPLAY-FLOAT FAILED: ' + (e?.message ?? e))
  process.exit(1)
}
