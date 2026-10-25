#!/usr/bin/env node
// verify-adb-only-tree.mjs —— 纯 Shizuku（无障碍关）下「控件树可用且被模型用上」的设备验收（0.14.1 块N）。
//
// 用户口径（2026-09-19）：「android_ui_tree 理应可以做到和无障碍一样的体验，确保 AI 在只有 adb 的情况下
// 体验也很好（因为猜像素就是折磨）」。
//
// 三层口径（AGENTS.md §2.1）：
//   - 代码层：`plugins/dsh-android-manage/test/ui-tree-parity.test.mjs`（schema 同形 / 节点清单 / ref 落坐标）；
//   - 本文件 = ADB 用户层 + 设备事实：**开一个全新对话**（能力解锁是**对话级**的，新对话必然是全掩蔽起点），
//     给一个**自然任务**（不提工具名、不提解锁——按 §2.1 第 3 条，那条链路本身就是要测的东西），
//     然后断言两件事：
//       ① **设备事实**：目标界面真的到了前台（`dumpsys` 读，不信任何自述文案）；
//       ② **路由证据**：本次会话里出现过 `android_ui_tree`（说明模型自己找到了纯 ADB 那条控件树路，
//          而不是撞在 android_ui_dump 上——无障碍关时后者恒不可用）。
//
// 用法：
//   node scripts/verify-adb-only-tree.mjs --serial 127.0.0.1:16416 [--timeout 240] [--self-test]
// 退出码：0 全绿 / 1 判红 / 2 前置不满足或证据不足（不得当通过）。
import { spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(HERE, '..')
const argv = process.argv.slice(2)
const argOf = (n) => { const i = argv.indexOf('--' + n); return i >= 0 ? argv[i + 1] : undefined }
const has = (n) => argv.includes('--' + n)
const SERIAL = argOf('serial')
const TIMEOUT_S = Number(argOf('timeout') ?? 240)
const STAMP = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)
const EVID = join(ROOT, '.deploy-tmp', 'adb-only-tree', STAMP)

const results = []
const record = (name, verdict, detail) => {
  results.push({ name, verdict, detail })
  const mark = verdict === 'PASS' ? 'PASS' : verdict === 'FAIL' ? 'FAIL' : 'INCONCLUSIVE'
  console.log(`[${mark}] ${name}${detail ? ' —— ' + detail : ''}`)
}

function adb(args) {
  const r = spawnSync('adb', ['-s', SERIAL, ...args], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 })
  return ((r.stdout ?? '') + (r.stderr ?? '')).replace(/\r/g, '')
}
const sh = (cmd) => adb(['shell', cmd])
function shot(path) {
  const r = spawnSync('adb', ['-s', SERIAL, 'exec-out', 'screencap', '-p'], { maxBuffer: 64 * 1024 * 1024 })
  writeFileSync(path, r.stdout ?? Buffer.alloc(0))
  return (r.stdout ?? Buffer.alloc(0)).length
}

/** display 0（真实屏）当前前台包名；解析不出返回空串。 */
export function topOnDisplay0(activitiesDump) {
  let current = -1
  for (const line of activitiesDump.split('\n')) {
    const h = /^\s*Display #([0-9]+)/.exec(line)
    if (h !== null) { current = Number(h[1]); continue }
    if (current !== 0) continue
    const m = /topResumedActivity=ActivityRecord\{[^}]*?\s(u0\s+)?([A-Za-z0-9_.]+)\//.exec(line)
    if (m !== null) return m[2]
  }
  return ''
}

/** 无障碍是否开着（用设备事实判，不用 prefs）。 */
export function a11yBound(accessibilityDump, pkg) {
  return new RegExp(pkg).test(accessibilityDump) && /Bound services/i.test(accessibilityDump)
}

function selfTest() {
  let failed = 0
  const dump = 'Display #0 (activities from top to bottom):\n    topResumedActivity=ActivityRecord{1 u0 com.android.settings/.Settings t1}\n'
    + 'Display #3 (activities from top to bottom):\n    topResumedActivity=ActivityRecord{2 u0 com.endday.game/com.godot.game.GodotApp t2}\n'
  if (topOnDisplay0(dump) !== 'com.android.settings') { failed++; console.error('SELF-TEST FAIL：display 0 前台解析') }
  if (topOnDisplay0('') !== '') { failed++; console.error('SELF-TEST FAIL：空输入必须为空串') }
  if (topOnDisplay0('    topResumedActivity=ActivityRecord{1 u0 a/.M t1}') !== '') { failed++; console.error('SELF-TEST FAIL：无 Display 锚点不得猜屏') }
  if (a11yBound('Bound services:{Service[1]\n  com.ai.assistance.quro.dshmobile/.DeviceControlService}', 'dsharnessmobile') !== true) { failed++; console.error('SELF-TEST FAIL：a11y 绑定判定') }
  if (failed > 0) { console.error(`VERIFY-ADB-ONLY-TREE SELF-TEST FAILED（${failed}）`); process.exit(1) }
  console.log('VERIFY-ADB-ONLY-TREE SELF-TEST PASSED（4 例：前台解析 / 空输入 / 无锚点 fail-closed / a11y 绑定）')
}

async function bridge(exprs) {
  const sockets = sh('cat /proc/net/unix').split('\n').filter((l) => l.includes('webview_devtools_remote'))
    .map((l) => l.split('@').pop().trim())
  if (sockets.length === 0) throw new Error('找不到 webview_devtools_remote（应用未运行？）')
  const PORT = 29226
  adb(['forward', `tcp:${PORT}`, `localabstract:${sockets[sockets.length - 1]}`])
  const targets = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json()
  const page = targets.find((t) => t.type === 'page') ?? targets[0]
  const ws = new WebSocket(page.webSocketDebuggerUrl)
  let seq = 0
  const pending = new Map()
  ws.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data)
    const p = pending.get(m.id)
    if (p === undefined) return
    pending.delete(m.id)
    m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result)
  })
  await new Promise((res, rej) => {
    ws.addEventListener('open', res, { once: true })
    ws.addEventListener('error', () => rej(new Error('CDP ws error')), { once: true })
  })
  const send = (method, params) => new Promise((res2, rej2) => {
    const id = ++seq
    pending.set(id, { resolve: res2, reject: rej2 })
    ws.send(JSON.stringify({ id, method, params }))
  })
  const out = []
  for (const expr of exprs) {
    const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })
    out.push(r.exceptionDetails ? { __error: r.exceptionDetails.exception?.description } : r.result.value)
  }
  ws.close()
  return out
}

/** 新开一个对话（能力解锁是对话级的：新对话 = 全掩蔽起点，正是要测的起点）。 */
async function newConversation() {
  const js = `(() => {
    const btn = Array.from(document.querySelectorAll('button,[role="button"],a'))
      .find((e) => /新会话|新建对话|新对话/.test((e.innerText || '').trim()))
    if (!btn) return 'no-button'
    btn.click()
    return 'clicked'
  })()`
  return (await bridge([js]))[0]
}

/** 把自然任务交给模型（只给目标 + 完成信号；**不提工具名、不提解锁**——那条链路本身就是要测的）。 */
async function askModel(promptText) {
  const js = `(async () => {
    await new Promise((r) => setTimeout(r, 1200))
    const box = document.querySelector('[contenteditable="true"][role="textbox"]')
      || document.querySelector('[contenteditable="true"]')
    if (!box) return 'no-composer'
    box.focus()
    const sel = window.getSelection(); sel.removeAllRanges()
    const range = document.createRange(); range.selectNodeContents(box); range.collapse(false); sel.addRange(range)
    document.execCommand('insertText', false, ${JSON.stringify(promptText)})
    await new Promise((r) => setTimeout(r, 400))
    const btns = Array.from(document.querySelectorAll('button'))
    const send = btns.reverse().find((b) => /send|发送/i.test(String(b.getAttribute('aria-label') || '')))
      || btns.find((b) => b.querySelector('svg') && b.offsetParent !== null)
    if (send) { send.click(); return 'clicked' }
    box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }))
    return 'enter'
  })()`
  return (await bridge([js]))[0]
}

const convoText = async () => {
  try { return (await bridge(['document.body.innerText.slice(0, 40000)']))[0] ?? '' } catch { return '' }
}

async function main() {
  if (has('self-test')) { selfTest(); return }
  if (SERIAL === undefined) { console.error('用法：--serial <serial> [--timeout 秒] [--self-test]'); process.exit(2) }
  mkdirSync(EVID, { recursive: true })

  // ── P0 前置：设备在线 / 快照就绪 / 应用在前台 / **无障碍必须是关的** ──
  if (!adb(['devices']).includes(SERIAL)) { record('设备在线', 'INCONCLUSIVE', SERIAL); process.exit(2) }
  const fp = sh('run-as com.ai.assistance.quro.dshmobile ls files/.snapshot-fingerprint')
  const tx = sh('run-as com.ai.assistance.quro.dshmobile ls files/.snapshot-transaction')
  if (!fp.includes('.snapshot-fingerprint') || !/No such file/.test(tx)) {
    record('快照就绪', 'INCONCLUSIVE', '刷新期间禁跑验收'); process.exit(2)
  }
  sh('am start -n com.ai.assistance.quro.dshmobile/.MainActivity')
  await new Promise((r) => setTimeout(r, 2500))
  const a11y = a11yBound(sh('dumpsys accessibility'), 'com.ai.assistance.quro.dshmobile')
  if (a11y) {
    record('前提：无障碍关闭（纯 Shizuku）', 'INCONCLUSIVE',
      '本套件测的是「只有 ADB/Shizuku」这条形态；当前无障碍已开——请先在系统设置里关掉「DSH 设备控制」再跑')
    process.exit(2)
  }
  record('前提：无障碍关闭（纯 Shizuku）', 'PASS', 'dumpsys accessibility 无本应用绑定服务')

  // ── 新对话 + 自然任务 ──
  const created = await newConversation()
  record('新开对话（掩蔽起点）', created === 'clicked' ? 'PASS' : 'INCONCLUSIVE', 'newConversation -> ' + String(created))
  const before = topOnDisplay0(sh("dumpsys activity activities | grep -E '^ *Display #|topResumedActivity'"))
  const sent = await askModel('帮我打开系统设置，进到「关于手机」那一页看看里面有什么。完成后只回复一行 DONE。')
  if (sent === 'no-composer' || sent?.__error !== undefined) {
    record('任务已发起', 'INCONCLUSIVE', 'composer 不可用：' + JSON.stringify(sent).slice(0, 120)); process.exit(2)
  }
  // 同 scope 套件：跑的时候界面显示「深度求索中…」，不能拿「停止/Stop」当结束信号。
  const deadline = Date.now() + TIMEOUT_S * 1000
  const minWaitUntil = Date.now() + 60_000
  let text = ''
  let prevText = ''
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 6000))
    text = await convoText()
    if (/MISSING_CREDENTIAL|no API key for provider/i.test(text)) break
    if (Date.now() > minWaitUntil && text !== '' && text === prevText) break
    prevText = text
  }
  writeFileSync(join(EVID, 'conversation.txt'), text)
  shot(join(EVID, 'screen.png'))

  // ── ① 设备事实：目标界面真的到了前台 ──
  const after = topOnDisplay0(sh("dumpsys activity activities | grep -E '^ *Display #|topResumedActivity'"))
  record('设备事实：设置界面已到前台', after === 'com.android.settings' ? 'PASS' : 'FAIL',
    `display 0 前台：${before || '(未知)'} → ${after || '(未知)'}`)

  // ── ② 路由证据：本次会话里出现过 android_ui_tree（纯 ADB 那条控件树路） ──
  if (/MISSING_CREDENTIAL|no API key for provider/i.test(text)) {
    record('路由证据：用到 android_ui_tree', 'INCONCLUSIVE', '引擎未配置模型凭据（模型没跑起来）——不是设备缺陷')
  } else if (/android_ui_tree/.test(text)) {
    record('路由证据：用到 android_ui_tree', 'PASS', '会话里出现 android_ui_tree（模型自己找到了纯 ADB 的控件树路）')
  } else if (/android_ui_dump/.test(text) && /不可用|没有|失败|unknown tool/i.test(text)) {
    record('路由证据：用到 android_ui_tree', 'FAIL', '模型撞在 android_ui_dump 上并失败——无障碍关时它恒不可用，指引未生效')
  } else {
    record('路由证据：用到 android_ui_tree', 'INCONCLUSIVE', '会话里未同时看到成功与失败信号（证据不足，不得当通过）')
  }

  writeFileSync(join(EVID, 'results.json'), JSON.stringify({ serial: SERIAL, results }, null, 2))
  const failed = results.filter((r) => r.verdict === 'FAIL').length
  const inc = results.filter((r) => r.verdict === 'INCONCLUSIVE').length
  console.log(`\n证据目录：${EVID}`)
  console.log(`结论：PASS ${results.length - failed - inc} · FAIL ${failed} · INCONCLUSIVE ${inc}`)
  if (failed > 0) { console.error('VERIFY-ADB-ONLY-TREE FAILED'); process.exit(1) }
  if (inc > 0) { console.error('VERIFY-ADB-ONLY-TREE INCONCLUSIVE（证据不足 ≠ 通过）'); process.exit(2) }
  console.log('VERIFY-ADB-ONLY-TREE PASSED')
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((e) => { console.error('套件异常：' + (e?.stack ?? e)); process.exit(2) })
}
