#!/usr/bin/env node
// verify-screen-scope-matrix.mjs —— 屏幕范围/通道/落点的**设备级**验收套件（0.14.1 块N）。
//
// 为什么必须存在这个套件（0.14.1 设备实测的三个 P0 缺陷全都只有真机用户层能看见）：
//   A1 工具面报「Shizuku 未就绪」而壳侧已授权 → 模型放弃可用能力；
//   B  virtual-only 下大面积工具不可用（op 清单不一致 / 参数无法兑现 / 工具不存在）；
//   C  跨屏拉起报成功而应用落在真实屏。
// 现有 A 轨套件断言 DOM 与桥状态，代码层单测断言报文——三层里唯独「设备可见结果」没人断言。
//
// 本套件遵守 AGENTS.md §2.1 的三层口径：断言全部落在**设备事实**上（dumpsys / 截图像素），
// 不信任何「工具自报成功」；任务由模型自己编排（只给目标，不给步骤）。
//
// 用法：
//   node scripts/verify-screen-scope-matrix.mjs --serial 127.0.0.1:16416 [--pkg com.endday.game]
//                                            [--api 3080] [--timeout 180] [--keep]
//   node scripts/verify-screen-scope-matrix.mjs --self-test
//
// 退出码：0 全绿 / 1 判红（真缺陷）/ 2 前置不满足或**证据不足**（不得当作通过）。
import { spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(HERE, '..')
const argv = process.argv.slice(2)
const argOf = (n) => { const i = argv.indexOf('--' + n); return i >= 0 ? argv[i + 1] : undefined }
const has = (n) => argv.includes('--' + n)

const SERIAL = argOf('serial')
const PKG = argOf('pkg') ?? 'com.endday.game'
const API_PORT = argOf('api') ?? '3080'
const TIMEOUT_S = Number(argOf('timeout') ?? 180)
const KEEP = has('keep')
const STAMP = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)
const EVID = join(ROOT, '.deploy-tmp', 'scope-matrix', STAMP)
const ownedForwards = new Set()
const VALID_SCOPES = new Set(['virtual-only', 'real-only', 'all'])

/** Allocate an adb forward on an OS-selected host port and remember only our own mapping. */
function adbForward(remote) {
  const r = spawnSync('adb', ['-s', SERIAL, 'forward', 'tcp:0', remote], { encoding: 'utf8' })
  if (r.status !== 0) throw new Error(`adb forward tcp:0 ${remote} failed: ${(r.stderr || '').trim()}`)
  const port = Number((r.stdout || '').trim().match(/\d+/)?.[0])
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error(`adb forward did not return an allocated port: ${(r.stdout || '').trim()}`)
  ownedForwards.add(port)
  return port
}

function removeOwnedForwards() {
  const errors = []
  for (const port of ownedForwards) {
    const r = spawnSync('adb', ['-s', SERIAL, 'forward', '--remove', `tcp:${port}`], { encoding: 'utf8' })
    if (r.status !== 0) errors.push(`tcp:${port}: ${(r.stderr || '').trim()}`)
    else ownedForwards.delete(port)
  }
  return errors
}

export function shouldDestroyVdisplay({ createdByRun, keep }) {
  return createdByRun === true && keep !== true
}

export function reconcileCreatedVdisplay({ hadVirtualBefore, createReceipt, observedVirtual }) {
  const accepted = createReceipt?.ok === true && createReceipt?.state === 'active'
  if (hadVirtualBefore || !accepted || !observedVirtual) {
    return { owned: false, receiptMatches: false }
  }
  const id = Number(createReceipt.displayId)
  const alias = createReceipt.selected ?? createReceipt.aliases?.[0]
  const receiptMatches = Number.isInteger(id) && id > 0 && id === observedVirtual.displayId
    && (alias === undefined || alias === observedVirtual.alias)
  // The initial registry was empty and the create call was accepted; own the one subsequent
  // singleton for cleanup even if the receipt omitted its ID, while reporting weak evidence.
  return { owned: true, receiptMatches }
}

export function isKnownScreenScope(scope) {
  return VALID_SCOPES.has(scope)
}

/** Restore scope and require an independent readback; exceptions are surfaced as evidence failure. */
export async function restoreScopeVerified(originalScope, setScope, getScope) {
  try {
    const setResult = await setScope(originalScope)
    const setValue = typeof setResult === 'string' ? parseJson(setResult) ?? setResult : setResult
    if (setValue === false || setValue?.ok === false) return { ok: false, actual: undefined, reason: 'scope restore rejected' }
    const actualRaw = await getScope()
    const actual = typeof actualRaw === 'string' ? parseJson(actualRaw) ?? actualRaw : actualRaw
    return actual === originalScope
      ? { ok: true, actual }
      : { ok: false, actual, reason: `scope readback mismatch: expected ${originalScope}, got ${String(actual)}` }
  } catch (error) {
    return { ok: false, reason: `scope restore/readback failed: ${String(error?.message ?? error)}` }
  }
}

/**
 * **不写进提示词的东西（用户口径，2026-09-19）**：不告诉模型用哪个工具、也不告诉它先解锁能力组。
 * 设备工具的解锁（`android_capabilities { group }`）本身就是被测链路的一部分——
 * 「用户提出要控制手机 → 模型自己想到必须先解锁」这一步断了，用户看到的现象就是「工具全不可用」。
 * 把解锁写进提示等于**跳过这条链路**，测出来的绿灯是假的（本轮先踩过：写死后任务确实跑通，
 * 但那是提示词的功劳，不是模型的能力）。故提示词只给目标 + 完成信号，工具与解锁由模型自己决定。
 * 相关缺陷登记见坑 167。
 */

const results = []
const record = (phase, name, verdict, detail) => {
  results.push({ phase, name, verdict, detail })
  const mark = verdict === 'PASS' ? 'PASS' : verdict === 'FAIL' ? 'FAIL' : 'INCONCLUSIVE'
  console.log(`[${mark}] ${phase} · ${name}${detail ? ' —— ' + detail : ''}`)
}

// ── adb / CDP 原语 ─────────────────────────────────────────────────────────

function adb(args, { allowFail = false } = {}) {
  const r = spawnSync('adb', ['-s', SERIAL, ...args], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 })
  if (r.status !== 0 && !allowFail) throw new Error(`adb ${args.join(' ')} failed: ${(r.stderr || '').trim()}`)
  return (r.stdout ?? '').replace(/\r/g, '')
}

function sh(cmd, opts) { return adb(['shell', cmd], opts) }

/**
 * stdout + stderr 合并读取。**前置判据必须用它**：`adb shell ls <不存在的文件>` 的
 * `No such file or directory` 是设备端 stderr，adb 原样转发到**本地 stderr**；
 * 只读 stdout 会把「marker 已消失」误判成「读不到 ⇒ 快照未就绪」（套件首跑就踩到）。
 */
function shBoth(cmd) {
  const r = spawnSync('adb', ['-s', SERIAL, 'shell', cmd], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 })
  return ((r.stdout ?? '') + (r.stderr ?? '')).replace(/\r/g, '')
}
function shot(path) {
  const r = spawnSync('adb', ['-s', SERIAL, 'exec-out', 'screencap', '-p'], { maxBuffer: 64 * 1024 * 1024 })
  if (r.status !== 0) throw new Error('screencap failed')
  writeFileSync(path, r.stdout)
  return r.stdout.length
}

/** 经 CDP 调壳侧桥方法（与 verify-vdisplay-viewer 同一路子）。 */
async function bridge(exprs) {
  const sockets = sh('cat /proc/net/unix').split('\n').filter((l) => l.includes('webview_devtools_remote'))
    .map((l) => l.split('@').pop().trim())
  if (sockets.length === 0) throw new Error('找不到 webview_devtools_remote（应用未运行？）')
  const PORT = adbForward(`localabstract:${sockets[sockets.length - 1]}`)
  let ws
  const pending = new Map()
  try {
    const targets = await (await fetch(`http://127.0.0.1:${PORT}/json`, { signal: AbortSignal.timeout(10_000) })).json()
    const page = targets.find((t) => t.type === 'page' && URL.canParse(t.url) && new URL(t.url).port === String(API_PORT))
    if (!page?.webSocketDebuggerUrl) throw new Error('CDP 页面 target 缺少 websocket URL')
    ws = new WebSocket(page.webSocketDebuggerUrl)
    let seq = 0
    ws.addEventListener('message', (ev) => {
      const m = JSON.parse(ev.data)
      const p = pending.get(m.id)
      if (p === undefined) return
      pending.delete(m.id)
      clearTimeout(p.timer)
      m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result)
    })
    ws.addEventListener('close', () => {
      for (const [id, p] of pending) {
        clearTimeout(p.timer)
        p.reject(new Error(`CDP connection closed with request ${id} pending`))
      }
      pending.clear()
    })
    await new Promise((res, rej) => {
      const timer = setTimeout(() => rej(new Error('CDP ws open timeout (10s)')), 10_000)
      ws.addEventListener('open', () => { clearTimeout(timer); res() }, { once: true })
      ws.addEventListener('error', () => { clearTimeout(timer); rej(new Error('CDP ws error')) }, { once: true })
    })
    const send = (method, params) => new Promise((resolve2, reject2) => {
      const id = ++seq
      const timer = setTimeout(() => {
        pending.delete(id)
        reject2(new Error(`CDP ${method} timeout (10s)`))
      }, 10_000)
      pending.set(id, { resolve: resolve2, reject: reject2, timer })
      try { ws.send(JSON.stringify({ id, method, params })) } catch (error) {
        clearTimeout(timer)
        pending.delete(id)
        reject2(error)
      }
    })
    const out = []
    for (const expr of exprs) {
      const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })
      out.push(r.exceptionDetails ? { __error: r.exceptionDetails.exception?.description } : r.result.value)
    }
    return out
  } finally {
    for (const [id, p] of pending) {
      clearTimeout(p.timer)
      p.reject(new Error(`CDP bridge closing with request ${id} pending`))
    }
    pending.clear()
    try { ws?.close() } catch { /* target may already be gone */ }
    const r = spawnSync('adb', ['-s', SERIAL, 'forward', '--remove', `tcp:${PORT}`], { encoding: 'utf8' })
    if (r.status !== 0) throw new Error(`无法移除本套件创建的 CDP forward tcp:${PORT}: ${(r.stderr || '').trim()}`)
    ownedForwards.delete(PORT)
  }
}

const parseJson = (v) => { try { return typeof v === 'string' ? JSON.parse(v) : v } catch { return undefined } }

// ── 引擎面（HTTP，经 adb forward 到设备内引擎） ─────────────────────────────

export const PRIVILEGE_STATUS_PROBE = `(async () => {
  const response = await fetch('/api/android/privilege/status', { credentials: 'same-origin' });
  return { status: response.status, ok: response.ok, body: response.ok ? await response.json() : null };
})()`

export async function readPrivilegeStatusFromPage(evaluate) {
  const [response] = await evaluate([PRIVILEGE_STATUS_PROBE])
  if (!response?.ok || response?.__error) throw new Error('privilege/status HTTP ' + String(response?.status ?? 'unavailable'))
  return response.body
}

async function engineStatus() {
  // The page already holds the engine auth cookie; never copy it into a host request or evidence.
  return readPrivilegeStatusFromPage(bridge)
}

/**
 * 让模型自己编排一个真实任务（只给目标，不给步骤），轮询设备/页面直到出现完成信号。
 *
 * **为什么走页面而不用 HTTP RPC**：本轮实测发现旧脚本（`e2e-phone-test.ps1`）假定的
 * `POST /api/session.create` 在本版引擎上不存在——从页面内 `fetch('/api/session.create')` 得 `not found`，
 * 设备只监听 3080，页面实际调的是 `/api/session/*`（camelCase，由 `dsh-api-*` 插件服务）+ 连接插件。
 * 那是引擎内部契约，套件不该硬编码；而**页面本身就是已鉴权的客户端**，在页面上下文里驱动
 * 输入框（`contenteditable`）与发送，等价于「真人在这里打字」——正好符合 AGENTS.md §2.1 第 3 条
 * （真实任务 + 由模型自己编排）。失败一律返回 `undefined`，由调用方判 INCONCLUSIVE（不得当通过）。
 */
export function isTaskSubmitted(receipt) {
  return receipt === 'clicked' || receipt === 'enter'
}

/** Permission-gate replies mean the model task did not reach any device tool. */
export function detectBlocker(text) {
  if (/MISSING_CREDENTIAL|no API key for provider/i.test(text)) {
    return '引擎未配置模型凭据（MISSING_CREDENTIAL / no API key）——模型无法运行，'
      + '请在应用「模型」页配置 provider 后重跑本套件（这不是设备缺陷）'
  }
  if (/(?:会话档位.{0,80}workspace-write|workspace-write.{0,100}(?:设备控制|屏幕控制|danger-full-access)|(?:设备控制|屏幕控制).{0,100}danger-full-access|danger-full-access.{0,100}(?:权限|会话))/i.test(text)) {
    return '设备控制被会话权限档位阻止（workspace-write 需要 danger-full-access）；本任务未验证设备行为'
  }
  return ''
}

/** Parse package ActivityRecord identities with their display placement. */
export function packageActivityRecords(pkg, dump) {
  const out = []
  let current = -1
  const header = /^\s*Display #([0-9]+)/
  const member = new RegExp('(^|[\\s:])' + pkg.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '/')
  for (const line of dump.split('\n')) {
    const h = header.exec(line)
    if (h !== null) { current = Number(h[1]); continue }
    if (current < 0 || !line.includes('ActivityRecord{') || !member.test(line)) continue
    const record = /ActivityRecord\{([^\s}]+)/.exec(line)
    if (record !== null) out.push({ displayId: current, id: record[1] })
  }
  return out
}

/** A pre-existing target ActivityRecord is not proof that this task launched the app. */
export function hasNewTargetActivity(pkg, targetDisplayId, beforeDump, afterDump) {
  const before = packageActivityRecords(pkg, beforeDump)
  const oldTargetIds = new Set(before.filter((r) => r.displayId === targetDisplayId).map((r) => r.id))
  return packageActivityRecords(pkg, afterDump).some((r) => r.displayId === targetDisplayId && !oldTargetIds.has(r.id))
}

async function runModelTask(promptText) {
  const script = `(async () => {
    const box = document.querySelector('[contenteditable="true"][role="textbox"]')
      || document.querySelector('[contenteditable="true"]')
    if (!box) return 'no-composer'
    if (document.querySelector('[role=dialog]')) return 'dialog-open'
    if (document.querySelector('[data-composer-card] button[aria-label="停止生成"]')) return 'generation-active'
    if ((box.textContent || '').trim() !== '') return 'composer-not-empty'
    box.focus()
    const sel = window.getSelection()
    sel.removeAllRanges()
    const range = document.createRange()
    range.selectNodeContents(box)
    range.collapse(false)
    sel.addRange(range)
    document.execCommand('insertText', false, ${JSON.stringify(promptText)})
    await new Promise((r) => setTimeout(r, 400))
    const btns = Array.from(document.querySelectorAll('button'))
    const send = btns.reverse().find((b) => /send|发送/i.test(String(b.getAttribute('aria-label') || '') + String(b.title || '')))
    if (send && !send.disabled) { send.click(); return 'clicked' }
    box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }))
    return 'enter'
  })()`
  let sent
  try { sent = (await bridge([script]))[0] } catch (e) { return { sent: undefined, reason: String(e.message) } }
  if (!isTaskSubmitted(sent)) {
    return { sent: undefined, blocker: '', reason: '任务未提交：' + JSON.stringify(sent).slice(0, 120) }
  }
  // 等模型编排完成：断言在设备侧，这里只等一个宽松窗口（页面上出现「已完成/停止」类状态或超时）。
  // 完成判据（2026-09-19 设备实测修正）：**不能**去匹配「停止/Stop」——本界面在跑的时候显示
  // 「深度求索中…」，匹配不到就直接判 idle，于是「模型还在想」被误判成「任务结束」，
  // 后续断言全部落在半成品状态上（首跑就是这样得出 3 条 INCONCLUSIVE）。
  // 改为「最短等待 + 文本连续两次不变」：既不会早退，也不会白等到超时。
  const deadline = Date.now() + TIMEOUT_S * 1000
  const minWaitUntil = Date.now() + 60_000
  let prev = ''
  let active = true
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 6000))
    const now = await pageConversationText()
    const [generating] = await bridge([`!!document.querySelector('[data-composer-card] button[aria-label="停止生成"]')`])
    active = generating === true
    if (detectBlocker(now) !== '') break
    if (!active && Date.now() > minWaitUntil && now !== '' && now === prev) break
    prev = now
  }
  const finalText = await pageConversationText()
  return { sent, blocker: detectBlocker(finalText) || (active ? '任务仍在生成，目标尚未完成；不得开始下一任务' : '') }
}

/** 抓页面会话区的可见文本（作为「模型自己编排」的过程留证；判据仍只看设备事实）。 */
async function pageConversationText() {
  try {
    return (await bridge([`document.body.innerText.slice(0, 20000)`]))[0] ?? ''
  } catch { return '' }
}

/** 解析「某包在哪些 display」——与壳侧 VdisplayController.displaysRunning 同一判据（固定字面量过滤，避免 16KiB 截断）。 */
export function displaysRunning(pkg, dump) {
  const out = new Set()
  let current = -1
  const header = /^\s*Display #([0-9]+)/
  const member = new RegExp('(^|[\\s:])' + pkg.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '/')
  for (const line of dump.split('\n')) {
    const h = header.exec(line)
    if (h !== null) { current = Number(h[1]); continue }
    if (current < 0 || !line.includes('ActivityRecord{')) continue
    if (member.test(line)) out.add(current)
  }
  return [...out].sort((a, b) => a - b)
}

/** SF token ↔ 别名配对（只返回目标alias的成对记录，便于不泄露整份SF dump地留证）。 */
export function sfMappingForAlias(sfDump, alias) {
  let pending = null
  for (const raw of sfDump.split('\n')) {
    const t = /^Virtual Display[ \t]+(\d+)[ \t]*$/.exec(raw.trim())
    if (t !== null) { pending = t[1]; continue }
    if (pending === null) continue
    const n = /^[ \t]*name="([^"]*)"[ \t]*$/.exec(raw)
    if (n === null) continue
    if (n[1] === 'DSH ' + alias) return { token: pending, name: n[1] }
    pending = null
  }
  return null
}

export function sfTokenForAlias(sfDump, alias) {
  return sfMappingForAlias(sfDump, alias)?.token ?? null
}

/** 真实屏（display 0）当前的前台包名；读不到返回空串。 */
function topResumedOnDisplay0() {
  const dump = shBoth("dumpsys activity activities | grep -E '^ *Display #|topResumedActivity'")
  let current = -1
  for (const line of dump.split('\n')) {
    const h = /^\s*Display #([0-9]+)/.exec(line)
    if (h !== null) { current = Number(h[1]); continue }
    if (current !== 0) continue
    const m = /topResumedActivity=ActivityRecord\{[^}]*?\s(u0\s+)?([A-Za-z0-9_.]+)\//.exec(line)
    if (m !== null) return m[2]
  }
  return ''
}

function selfTest() {
  let failed = 0
  const dump = 'Display #0 (activities from top to bottom):\n    topResumedActivity=ActivityRecord{1 u0 a/.M t1}\n'
    + 'Display #2 (activities from top to bottom):\n    topResumedActivity=ActivityRecord{2 u0 com.endday.game/com.godot.game.GodotApp t2}\n'
  const got = displaysRunning('com.endday.game', dump).join(',')
  if (got !== '2') { failed++; console.error('SELF-TEST FAIL：displaysRunning 期望 2 实得 ' + got) }
  if (displaysRunning('com.endday', dump).length !== 0) { failed++; console.error('SELF-TEST FAIL：前缀安全') }
  if (displaysRunning('com.endday.game', 'ActivityRecord{2 u0 com.endday.game/.M t2}').length !== 0) {
    failed++; console.error('SELF-TEST FAIL：无 Display 锚点时必须为空（不得猜屏号）')
  }
  const sf = 'Virtual Display 11529215047793762666\n    name="mumuscreen000"\nVirtual Display 999\n    name="DSH virtual-1"\n'
  if (sfTokenForAlias(sf, 'virtual-1') !== '999') { failed++; console.error('SELF-TEST FAIL：SF token 配对') }
  if (sfTokenForAlias(sf, 'virtual-2') !== null) { failed++; console.error('SELF-TEST FAIL：未知别名必须为 null') }
  if (failed > 0) { console.error(`VERIFY-SCREEN-SCOPE-MATRIX SELF-TEST FAILED（${failed}）`); process.exit(1) }
  console.log('VERIFY-SCREEN-SCOPE-MATRIX SELF-TEST PASSED（6 例判别力：分组解析 / 前缀安全 / 无锚点 fail-closed / token 配对）')
}

async function main() {
  if (has('self-test')) { selfTest(); return }
  if (SERIAL === undefined) { console.error('用法：--serial <serial> [--pkg <pkg>] [--api <port>] [--timeout <秒>] [--keep] [--self-test]'); process.exit(2) }
  mkdirSync(EVID, { recursive: true })
  const cmds = []
  const run = (cmd) => { cmds.push(cmd); return sh(cmd, { allowFail: true }) }

  // ── P0 前置（不满足 → exit 2：前置不满足不得当缺陷上报，也不得当作通过） ──
  const devices = adb(['devices']).split('\n').filter((l) => l.includes(SERIAL))
  if (devices.length === 0) { record('P0', '设备在线', 'INCONCLUSIVE', SERIAL + ' 不在 adb devices'); process.exit(2) }
  const fp = run(`run-as com.ai.assistance.quro.dshmobile ls files/.snapshot-fingerprint`)
  const tx = shBoth('run-as com.ai.assistance.quro.dshmobile ls files/.snapshot-transaction')
  if (!fp.includes('.snapshot-fingerprint') || !/No such file/.test(tx)) {
    record('P0', '快照就绪（指纹在场且事务 marker 已消失）', 'INCONCLUSIVE',
      `刷新期间禁跑验收；先等快照完成（marker 探测输出：${tx.trim().slice(0, 120) || '(空)'}）`)
    process.exit(2)
  }
  run('am start -n com.ai.assistance.quro.dshmobile/.MainActivity')
  await new Promise((r) => setTimeout(r, 2500))

  let scope
  let scopeMayHaveChanged = false
  let vd
  let createdVdisplay = false
  try {
  const [scopeRaw, statusRaw] = await bridge(['androidBridge.getScreenScope()', 'androidBridge.vdisplayStatus()'])
  scope = typeof scopeRaw === 'string' ? parseJson(scopeRaw) ?? scopeRaw : scopeRaw
  if (!isKnownScreenScope(scope)) throw new Error(`原 screen scope 不是已知枚举，拒绝猜测或写回：${JSON.stringify(scopeRaw)}`)
  const shell = parseJson(statusRaw)
  record('P0', '范围与虚拟屏', 'PASS', `scope=${scope} · 屏=${(shell?.screens ?? []).map((s) => s.alias + '#' + s.displayId).join(', ')}`)
  vd = (shell?.screens ?? []).find((s) => s.kind === 'virtual')
  if (vd === undefined) {
    const [createdRaw] = await bridge(['androidBridge.vdisplayCreate()'])
    const createResult = parseJson(createdRaw)
    if (createResult?.ok === false || createResult?.success === false) throw new Error(`vdisplayCreate 拒绝：${JSON.stringify(createResult)}`)
    await new Promise((r) => setTimeout(r, 3000))
    const again = parseJson((await bridge(['androidBridge.vdisplayStatus()']))[0])
    vd = (again?.screens ?? []).find((s) => s.kind === 'virtual')
    const ownership = reconcileCreatedVdisplay({ hadVirtualBefore: false, createReceipt: createResult, observedVirtual: vd })
    createdVdisplay = ownership.owned
    if (vd !== undefined && ownership.owned && !ownership.receiptMatches) {
      record('P0', '虚拟屏创建回执绑定设备事实', 'INCONCLUSIVE',
        `create 已受理且设备有新虚拟屏，但回执缺少/不匹配 displayId；该屏仍归本轮并将在收尾释放：回执=${JSON.stringify(createResult)}，设备=${vd.alias}#${vd.displayId}`)
    } else if (vd !== undefined && !ownership.owned) {
      record('P0', '虚拟屏创建回执绑定设备事实', 'INCONCLUSIVE',
        `设备出现虚拟屏，但 create 未给出明确成功回执；为避免删除外部资源不认领该屏：${JSON.stringify(createResult)}`)
    }
  }
  if (vd === undefined) {
    record('P0', '虚拟屏存在', 'INCONCLUSIVE', '建屏未成功（Shizuku 未就绪/未授权），本套件无法继续')
    throw new Error('虚拟屏前置不满足')
  }

  // ── P1 跨面一致性（A1/A2）：引擎面 vs 壳侧面 vs 设备事实 ──
  let engine
  try { engine = await engineStatus() } catch (e) { record('P1', '引擎状态面可达', 'INCONCLUSIVE', String(e.message)); engine = undefined }
  const shizukuProc = run('ps -A | grep shizuku_server')
  const a11yBound = run('dumpsys accessibility | grep -A2 "Bound services"')
  const serviceRunning = /shizuku_server/.test(shizukuProc)
  if (engine !== undefined) {
    const engineReady = engine?.gates?.shizukuReady === true
    const shellReady = shell?.state === 'ready' || shell?.state === 'active'
    if (!engineReady && shellReady && serviceRunning) {
      record('P1', 'Shizuku 状态跨面一致', 'FAIL',
        `引擎面 gates.shizukuReady=${String(engine?.gates?.shizukuReady)} 而壳侧面 state=${String(shell?.state)}/${String(shell?.code)} 且 shizuku_server 在运行 —— 同一个事实两种读数（A1）`)
    } else {
      record('P1', 'Shizuku 状态跨面一致', 'PASS', `引擎=${String(engine?.gates?.shizukuReady)} shell=${String(shell?.state)} 服务=${serviceRunning}`)
    }
    const engineA11y = engine?.gates?.a11yEnabled === true
    const boundOurs = /dsharnessmobile/.test(a11yBound)
    if (!engineA11y && boundOurs) {
      record('P1', '无障碍状态跨面一致', 'FAIL', '引擎面 a11yEnabled=false 但 dumpsys 显示本应用服务已绑定')
    } else {
      record('P1', '无障碍状态跨面一致', 'PASS', `引擎=${engineA11y} 设备绑定=${boundOurs}`)
    }
  }

  // ── P2 落点（C1）：模型自己拉起，设备侧回读落点 ──
  const beforeDump = run(`dumpsys activity activities | grep -E '^ *Display #|ActivityRecord'`)
  const before = displaysRunning(PKG, beforeDump)
  const beforeActivities = packageActivityRecords(PKG, beforeDump)
  const task = await runModelTask(
    `帮我用手机把游戏 ${PKG} 在虚拟屏 ${vd.alias} 上打开。`
    + '完成后只回复一行 DONE，不要解释。',
  )
  writeFileSync(join(EVID, 'p2-conversation.txt'), await pageConversationText())
  const afterDump = run(`dumpsys activity activities | grep -E '^ *Display #|ActivityRecord'`)
  const after = displaysRunning(PKG, afterDump)
  shot(join(EVID, 'p2-real-screen.png'))
  if (task.sent === undefined || task.blocker !== '') {
    record('P2', '拉起落点=虚拟屏', 'INCONCLUSIVE',
      (task.blocker !== '' ? task.blocker : '未能发起任务：' + String(task.reason))
      + `（设备事实：${PKG} 在 displayId=${after.join(',') || '无'}）`)
  } else if (after.includes(vd.displayId) && hasNewTargetActivity(PKG, vd.displayId, beforeDump, afterDump)) {
    record('P2', '拉起落点=虚拟屏', 'PASS', `${PKG} 在 displayId=${after.join(',')}（目标 ${vd.displayId}；此前 ${before.join(',') || '不在任何屏'}）`)
  } else if (after.includes(vd.displayId)) {
    record('P2', '拉起落点=虚拟屏', 'INCONCLUSIVE',
      `目标屏已有 ActivityRecord，任务后没有可确认的新实例（目标 ${vd.displayId}；前后实例数 ${beforeActivities.filter((r) => r.displayId === vd.displayId).length}/${packageActivityRecords(PKG, afterDump).filter((r) => r.displayId === vd.displayId).length}）`)
  } else if (after.length === 0) {
    record('P2', '拉起落点=虚拟屏', 'INCONCLUSIVE', `回读里找不到 ${PKG} 的 ActivityRecord（应用可能已退出）——不构成落点证明`)
  } else {
    record('P2', '拉起落点=虚拟屏', 'FAIL', `${PKG} 实际在 displayId=${after.join(',')}，目标是 ${vd.displayId}（用户实报的「跳到真实屏」）`)
  }

  // ── P3 虚拟屏输入 + 双屏像素对照（真实屏必须不变） ──
  const sfDump = run('dumpsys SurfaceFlinger | grep -E "^(Virtual Display|    name=)"')
  const sfMapping = sfMappingForAlias(sfDump, vd.alias)
  const token = sfMapping?.token ?? null
  writeFileSync(join(EVID, 'sf-token-mapping.json'), JSON.stringify({
    alias: vd.alias, displayId: vd.displayId,
    sfName: sfMapping?.name ?? null, token, matched: sfMapping !== null,
  }, null, 2) + '\n')
  let vdBefore = 0
  let vdBeforeSha = ''
  let realBefore = 0
  if (token !== null) {
    const r = spawnSync('adb', ['-s', SERIAL, 'exec-out', 'screencap', '-p', '-d', token], { maxBuffer: 64 * 1024 * 1024 })
    const bytes = r.stdout ?? Buffer.alloc(0)
    writeFileSync(join(EVID, 'p3-vd-before.png'), bytes)
    vdBefore = bytes.length
    vdBeforeSha = createHash('sha256').update(bytes).digest('hex').slice(0, 12)
  }
  realBefore = shot(join(EVID, 'p3-real-before.png'))
  const inputTask = await runModelTask(
    // 任务目标必须**带可见结果**（0.14.1 W2 修正）：上一版是「点左上角 + 输入 dsh-test」，
    // 而那块区域是纯背景、输入又没有焦点控件 ⇒ 画面天然不变，像素判据恒得 INCONCLUSIVE
    // （两轮实跑都是如此）。那不是「注入没生效」，是**判据不可能有信号**。
    // 现在只给目标（让画面发生可见变化）+ 完成信号，步骤仍由模型自己编排（AGENTS §2.1 第 3 条）。
    `请在虚拟屏 ${vd.alias} 上操作一次，让这块屏幕的画面发生**可见变化**（例如点开界面里的按钮或切换页面）。`
    + '完成后只回复一行 DONE，不要解释。',
  )
  writeFileSync(join(EVID, 'p3-conversation.txt'), await pageConversationText())
  const realAfter = shot(join(EVID, 'p3-real-after.png'))
  if (token !== null) {
    const r = spawnSync('adb', ['-s', SERIAL, 'exec-out', 'screencap', '-p', '-d', token], { maxBuffer: 64 * 1024 * 1024 })
    const vdAfterBytes = r.stdout ?? Buffer.alloc(0)
    writeFileSync(join(EVID, 'p3-vd-after.png'), vdAfterBytes)
    const vdAfter = vdAfterBytes.length
    // 判据用**内容哈希**而不是长度：两张不同的图压缩后可能等长（长度相等只是弱代理）。
    const sha = (b) => createHash('sha256').update(b).digest('hex').slice(0, 12)
    const changed = vdAfter > 0 && vdBeforeSha !== sha(vdAfterBytes)
    // 任务没真跑起来时（凭据/发起失败），像素变化可能来自「刚建屏的过渡帧 → 空屏」，不能当证据。
    const usable = inputTask.sent !== undefined && inputTask.blocker === ''
    record('P3', '虚拟屏可注入且像素有变化', usable ? (changed ? 'PASS' : 'INCONCLUSIVE') : 'INCONCLUSIVE',
      usable
        ? `虚拟屏截图 ${vdBefore} B → ${vdAfter} B（sha ${vdBeforeSha} → ${sha(vdAfterBytes)}，`
          + (changed ? '有变化' : '未观察到变化：可能注入未生效，或该屏画面本身静止') + '）'
        : (inputTask.blocker !== '' ? inputTask.blocker : '任务未发起，像素对照不构成证据')
          + `（截图仍留证：${vdBefore} B → ${vdAfter} B）`)
  } else {
    record('P3', '虚拟屏截图（SF token）', 'INCONCLUSIVE', '未能由别名配到 SF token，无法取虚拟屏像素')
  }
  const realUnchanged = realBefore === realAfter
  // **真实屏「字节不变」不是有效判据（本轮首跑即假红）**：模型/应用就在真实屏上工作，
  // 会话区滚动、任务文字上屏都会改变真实屏像素——把它判红是本套件自己的缺陷。
  // 真正要守的命题是「虚拟屏操作没有把第三方应用带到真实屏前台」，故改用**前台归属**判据。
  const realTop = topResumedOnDisplay0()
  const intruder = realTop !== '' && realTop !== 'com.ai.assistance.quro.dshmobile'
  record('P3', '虚拟屏操作未把第三方应用带到真实屏前台', intruder ? 'FAIL' : 'PASS',
    `display 0 前台=${realTop || '未知'}（pixel 参照：${realBefore} B → ${realAfter} B，仅作留证不作判据）`)

  // ── P4 real-only 反证：同一动作必须整体翻转 ──
  try {
    scopeMayHaveChanged = true
    const [setRaw] = await bridge([`androidBridge.setScreenScope('real-only')`])
    const setResult = parseJson(setRaw)
    if (setRaw === false || setResult?.ok === false || setResult?.success === false) {
      record('P4', 'real-only 下虚拟屏操作被拒（反证）', 'INCONCLUSIVE', `setScreenScope 被拒：${JSON.stringify(setRaw)}`)
    } else {
      const [scopeAfterSetRaw] = await bridge(['androidBridge.getScreenScope()'])
      const scopeAfterSet = typeof scopeAfterSetRaw === 'string' ? parseJson(scopeAfterSetRaw) ?? scopeAfterSetRaw : scopeAfterSetRaw
      if (scopeAfterSet !== 'real-only') {
        record('P4', 'real-only 下虚拟屏操作被拒（反证）', 'INCONCLUSIVE', `scope 写入未收敛：${String(scopeAfterSet)}`)
      } else {
        await new Promise((r) => setTimeout(r, 1200))
        const denyTask = await runModelTask(
          `请在虚拟屏 ${vd.alias} 上点一下坐标 (10,10)。完成后只回复一行 DONE。`,
        )
        writeFileSync(join(EVID, 'p4-conversation.txt'), await pageConversationText())
        const convo = (denyTask.sent === undefined ? '' : await pageConversationText())
        const denied = /screen-out-of-scope|不允许访问|real-only|不在开放范围/i.test(convo)
        record('P4', 'real-only 下虚拟屏操作被拒（反证）', denied ? 'PASS' : 'INCONCLUSIVE',
          denyTask.sent === undefined || denyTask.blocker !== ''
            ? (denyTask.blocker !== '' ? denyTask.blocker : '未能发起任务：' + String(denyTask.reason))
            : (denied ? '拒绝文案在场（页面会话区）' : '未观察到拒绝文案——反证未成立，**不得视为通过**'))
      }
    }
  } catch (error) {
    record('P4', 'real-only 下虚拟屏操作被拒（反证）', 'INCONCLUSIVE', `反证未能完成：${String(error?.message ?? error)}`)
  }

  } catch (error) {
    record('DRIVER', '验收驱动收敛', 'INCONCLUSIVE', String(error?.message ?? error))
  } finally {
    if (scopeMayHaveChanged && scope !== undefined) {
      const restored = await restoreScopeVerified(scope,
        async (value) => (await bridge([`androidBridge.setScreenScope(${JSON.stringify(value)})`]))[0],
        async () => (await bridge(['androidBridge.getScreenScope()']))[0])
      record('CLEANUP', 'screen scope 还原并回读', restored.ok ? 'PASS' : 'INCONCLUSIVE', restored.ok ? String(restored.actual) : restored.reason)
    }
    if (shouldDestroyVdisplay({ createdByRun: createdVdisplay, keep: KEEP })) {
      try {
        await bridge(['androidBridge.vdisplayDestroy()'])
        const afterDestroy = parseJson((await bridge(['androidBridge.vdisplayStatus()']))[0])
        const remains = (afterDestroy?.screens ?? []).some((s) => s.kind === 'virtual')
        record('CLEANUP', '仅销毁本轮创建的虚拟屏', remains ? 'INCONCLUSIVE' : 'PASS', remains ? '销毁后仍有 virtual screen' : '已销毁并回读确认')
      } catch (error) {
        record('CLEANUP', '仅销毁本轮创建的虚拟屏', 'INCONCLUSIVE', String(error?.message ?? error))
      }
    }
    for (const error of removeOwnedForwards()) record('CLEANUP', '移除本轮 adb forward', 'INCONCLUSIVE', error)
  }
  // ── 证据落盘 + 汇总（必须在状态还原与资源清理之后） ──
  writeFileSync(join(EVID, 'commands.md'), cmds.map((c) => 'adb -s ' + SERIAL + ' shell ' + c).join('\n') + '\n')
  writeFileSync(join(EVID, 'results.json'), JSON.stringify({ serial: SERIAL, pkg: PKG, scope, vd, results }, null, 2))
  const failed = results.filter((r) => r.verdict === 'FAIL').length
  const inconclusive = results.filter((r) => r.verdict === 'INCONCLUSIVE').length
  console.log(`\n证据目录：${EVID}`)
  console.log(`结论：PASS ${results.length - failed - inconclusive} · FAIL ${failed} · INCONCLUSIVE ${inconclusive}`)
  if (failed > 0) process.exitCode = 1
  else if (inconclusive > 0) process.exitCode = 2
  else console.log('VERIFY-SCREEN-SCOPE-MATRIX PASSED')
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((e) => { console.error('套件异常：' + (e?.stack ?? e)); process.exit(2) })
}
