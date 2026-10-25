#!/usr/bin/env node
// verify-notify-consumption.mjs —— 通知信道消费「不再只靠一次文件事件」的设备验收（0.14.1，dsh-mobile#238）。
//
// 用户口径（2026-09-20 真机实报）：「没有弹窗，但是消息实际上有」「悬浮球那个长按消息查看报告没有内容」，
// 且「必须划到后台才有通知」。取证读数是 `.notify.ndjson` 一直在长而 `notify.offset` 冻住 ⇒ 报告根本没投。
//
// 为什么这台套件必须存在（而不是只靠单测）：缺陷的现场是**驱动与推进**（谁触发消费、指针怎么前进），
// 单测只能证明纯函数对；「事件丢失时兜底还活着」只有设备能证。
//
// 三层口径（AGENTS.md §2.1）：
//   - 代码层：`NotifyConsumptionStallTest.kt`（5 例：监听位 / 事件白名单 / 步进与单调 / 尾部倒读 / 去重）
//     + `NotificationContractTest.消费必须有事件之外的兜底驱动`（防「兜底写了没人调」）；
//   - 本文件 = ADB 用户层 + 设备事实，四段判据：
//       P1 **兜底消费**（核心）：给同一个 inode 建**第二个目录项**，从那个名字追加一条 report ——
//          事件名不是 `.notify.ndjson`，被白名单有意忽略（旧包在此**实测停摆 20 s 一动不动**），
//          新包必须在 ≤ [--wait] 秒内由**看门狗 tick** 消费掉，且探针里出现的触发源必须是 `tick`。
//       P2 **去重**：两条逐字节相同的 report 行 → 恰好一条 POSTED + 一条 DUPLICATE_SUPPRESSED。
//       P3 **无残留**：`notify.offset == 文件长度`（消费不落后）。
//       P4 **心跳**（可 INCONCLUSIVE）：探针里出现 `notify tick alive`（5 分钟一格；窗口不够不算 FAIL）。
//   P1/P3 是硬判据；P4 在 timeout 内没到只记 INCONCLUSIVE。
//
// 用法：
//   node scripts/verify-notify-consumption.mjs --serial 127.0.0.1:16416 [--wait 30] [--self-test]
// 退出码：0 全绿 / 1 判红 / 2 前置不满足或证据不足（不得当通过）。
import { spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(HERE, '..')
const argv = process.argv.slice(2)
const argOf = (n) => { const i = argv.indexOf('--' + n); return i >= 0 ? argv[i + 1] : undefined }
const has = (n) => argv.includes('--' + n)
const SERIAL = argOf('serial')
const WAIT_S = Number(argOf('wait') ?? 30)
const PKG = 'com.ai.assistance.quro.dshmobile'
const FILE_REL = 'files/home/.dsh/.notify.ndjson'
const INJECT_NAME = '.notify-inject'
const STAMP = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)
const EVID = join(ROOT, '.deploy-tmp', 'notify-consumption', STAMP)

// ── 纯逻辑（自检直接覆盖；判据全部在这里，设备形态只喂样本）─────────────────────

/** 从 prefs xml 里取某个 long 键（缺键返回 null，不返回 0——0 是合法偏移）。 */
export function longPref(xml, key) {
  const m = new RegExp(`<long\\s+name="${key}"\\s+value="(-?[0-9]+)"`).exec(xml)
  return m === null ? null : Number(m[1])
}

/** `ls -l` 或 `stat -c %s` 两种输出都吃。 */
export function fileSize(text) {
  const st = /^([0-9]+)\s*$/m.exec(text.trim())
  if (st !== null) return Number(st[1])
  const ls = /^\S+\s+\S+\s+\S+\s+\S+\s+([0-9]+)\s/m.exec(text)
  return ls === null ? null : Number(ls[1])
}

/** 解析 `notify drain trigger=… lines=… offset a->b len=n` 行。 */
export function parseDrainLines(log) {
  const out = []
  const re = /notify drain trigger=(\S+) lines=([0-9]+) offset ([0-9]+)->([0-9]+) len=([0-9]+)/g
  let m
  while ((m = re.exec(log)) !== null) {
    out.push({ trigger: m[1], lines: Number(m[2]), from: Number(m[3]), to: Number(m[4]), len: Number(m[5]) })
  }
  return out
}

/** 某次注入之后是否出现「被 tick 消费」的证据：触发源以 tick 开头且偏移确实前进。 */
export function tickConsumed(drains, minOffset) {
  return drains.some((d) => d.trigger.startsWith('tick') && d.to > minOffset && d.to > d.from)
}

/** 被「目录项名字不在白名单」的路径消费 = 注入实验失效（说明事件路径也命中了，判不出兜底）。 */
export function consumedByInjectName(drains) {
  return drains.some((d) => d.trigger.includes(INJECT_NAME))
}

/** 投递结果序列（kind + result），按出现顺序；`result=` 是 NotifyStore 的唯一终态记账。 */
export function dispatchResults(log) {
  const out = []
  const re = /notify dispatch kind=(\S+) result=(\S+)/g
  let m
  while ((m = re.exec(log)) !== null) out.push({ kind: m[1], result: m[2] })
  return out
}

/**
 * 去重判据：**本轮新出现的**最后一条 DUPLICATE_SUPPRESSED 之前，紧邻的同类投递必须是 POSTED。
 *
 * 两个「不这么写就会假绿」的点：① 只数条数会被探针里的历史行骗过（上一轮验收留下的
 * DUPLICATE_SUPPRESSED 同样满足「DUPLICATE 紧前是 POSTED」）⇒ 必须传 [baseline]（注入前该串的出现次数），
 * 要求**新增**才判过；② 必须看相邻关系而不是计数——历史 POSTED 一堆，计数式判据恒真。
 */
export function dedupVerdict(log, baseline = 0) {
  const seq = dispatchResults(log)
  const dups = []
  for (let k = 0; k < seq.length; k++) if (seq[k].result === 'DUPLICATE_SUPPRESSED') dups.push(k)
  const i = dups.length > 0 ? dups[dups.length - 1] : -1
  const prev = i > 0 ? seq[i - 1] : null
  const ok = dups.length > baseline && i > 0 && prev.kind === seq[i].kind && prev.result === 'POSTED'
  return {
    posted: seq.filter((d) => d.result === 'POSTED').length,
    dup: dups.length,
    ok,
  }
}

function selfTest() {
  let bad = 0
  let total = 0
  const expect = (name, cond) => { total++; if (!cond) { bad++; console.log('[SELFTEST-FAIL] ' + name) } else console.log('[SELFTEST-PASS] ' + name) }

  const xml = '<?xml version=\'1.0\'?>\n<map>\n    <long name="notify.offset" value="8733" />\n    <long name="notify.markerOffset" value="47373" />\n</map>\n'
  expect('prefs：读得出 offset', longPref(xml, 'notify.offset') === 8733)
  expect('prefs：缺键必须是 null 而不是 0（0 是合法偏移）', longPref('<map></map>', 'notify.offset') === null)
  expect('ls -l 形态取长度', fileSize('-rw------- 1 u0_a56 u0_a56 9064 2026-09-20 23:32 x') === 9064)
  expect('stat 形态取长度', fileSize('9064\n') === 9064)

  // 停摆现场的形状（文件在长、零 drain 行）——必须**判不出**兜底消费
  const stallLog = '09-20 23:15:15 [dsh-notify] notify: kind=silent id=4097 channel=dsh-silent\n'
  expect('停摆现场：无 drain 行 ⇒ 兜底未消费', tickConsumed(parseDrainLines(stallLog), 8733) === false)
  // 旧包观测量（偏移冻住、文件在长）也不能被判成消费
  const frozen = 'notify drain trigger=start lines=0 offset 8733->8733 len=8733\n'
  expect('偏移不前进不算消费', tickConsumed(parseDrainLines(frozen), 8733) === false)
  // 注入名字被消费 ⇒ 实验失效（否则会拿事件路径冒充兜底）
  const byName = 'notify drain trigger=watch:.notify-inject lines=1 offset 8733->9064 len=9064\n'
  expect('事件名命中注入名必须判为实验失效', consumedByInjectName(parseDrainLines(byName)) === true)
  // 兜底消费
  const byTick = 'notify drain trigger=tick lines=1 offset 8733->9064 len=9064\n'
  expect('tick 消费且偏移前进 = 兜底生效',
    tickConsumed(parseDrainLines(byTick), 8733) === true && consumedByInjectName(parseDrainLines(byTick)) === false)

  // 去重：POSTED 紧邻 DUPLICATE_SUPPRESSED
  const dupLog = 'notify dispatch kind=report result=POSTED\nnotify dispatch kind=report result=DUPLICATE_SUPPRESSED\n'
  expect('去重：一投一丢 = 通过', dedupVerdict(dupLog, 0).ok === true)
  const dupLog2 = 'notify dispatch kind=report result=POSTED\n'
  expect('去重：缺 DUPLICATE 必须判红', dedupVerdict(dupLog2, 0).ok === false)
  const dupLog3 = 'notify dispatch kind=silent result=POSTED\nnotify dispatch kind=report result=DUPLICATE_SUPPRESSED\n'
  expect('去重：前一条不是同类 POSTED 必须判红', dedupVerdict(dupLog3, 0).ok === false)
  expect('去重：只有历史 DUPLICATE（baseline 已含）必须判红 —— 这条防「上轮证据冒充本轮」',
    dedupVerdict(dupLog, 1).ok === false)

  if (bad > 0) { console.log(`SELFTEST FAILED（${bad}/${total} 例）`); process.exit(1) }
  console.log(`SELFTEST PASSED（${total} 例，含 5 例否定判据：偏移不前进不算消费 / 注入名命中即实验失效 / 缺 DUPLICATE / 前一条非同类 POSTED / 历史 DUPLICATE 冒充本轮）`)
  process.exit(0)
}

if (has('self-test')) selfTest()
if (!SERIAL) { console.error('缺 --serial（MuMu: 127.0.0.1:16416）'); process.exit(2) }

const results = []
const record = (name, verdict, detail) => {
  results.push({ name, verdict, detail })
  console.log(`[${verdict}] ${name}${detail ? ' —— ' + detail : ''}`)
}

function adb(args, input) {
  const r = spawnSync('adb', ['-s', SERIAL, ...args], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, input })
  return ((r.stdout ?? '') + (r.stderr ?? '')).replace(/\r/g, '')
}
const runAs = (cmd) => adb(['shell', `run-as ${PKG} ${cmd}`])
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const offsetNow = () => longPref(runAs('cat shared_prefs/dsh-notify.xml'), 'notify.offset')
const sizeNow = () => fileSize(runAs(`stat -c %s ${FILE_REL}`))
const probe = (n = 400) => runAs(`tail -${n} files/notify-responder.log`)
const sh = (cmd) => adb(['shell', cmd])

/** 主 WebView 的 CDP 桥（与 verify-adb-only-tree.mjs 同法：真实页面、真实发送）。 */
async function bridge(exprs) {
  const sockets = sh('cat /proc/net/unix').split('\n').filter((l) => l.includes('webview_devtools_remote'))
    .map((l) => l.split('@').pop().trim())
  if (sockets.length === 0) throw new Error('找不到 webview_devtools_remote（应用未运行？）')
  const PORT = 29227
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

/** 把自然任务交给模型：只给目标与完成信号，**不提工具名、不提通知**。 */
async function askModel(promptText) {
  const js = `(async () => {
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

function shot(path) {
  const r = spawnSync('adb', ['-s', SERIAL, 'exec-out', 'screencap', '-p'], { maxBuffer: 64 * 1024 * 1024 })
  writeFileSync(path, r.stdout ?? Buffer.alloc(0))
  return (r.stdout ?? Buffer.alloc(0)).length
}

const postedCount = () => (probe(4000).match(/notify dispatch kind=report result=POSTED/g) ?? []).length

/**
 * P5（用户症状①「有消息但不弹窗」）：**真实任务**在**应用处于后台**时结束，报告必须投出去并弹横幅。
 * 判据分两段，缺一不算通过：① 探针新增一条 `kind=report result=POSTED`；② 投递后立刻截屏留证
 * （横幅只停留数秒，截屏交给视觉判读——本轮不把「截图里有没有横幅」自动化，避免自证式断言）。
 */
async function bannerPhase() {
  // 冷却：Android 15 起「同一应用短时间内连续多条通知」会进入 cooldown，后到的横幅会被压低——
  // 刚跑完 P1/P2 注入就测横幅等于自己污染自己。故先静默 [--cooldown] 秒（默认 60）。
  const cooldown = Number(argOf('cooldown') ?? 60)
  console.log(`… 横幅相位冷却 ${cooldown}s（避开平台通知冷却，否则判据不成立）`)
  await sleep(cooldown * 1000)

  sh(`am start -n ${PKG}/.MainActivity`)
  await sleep(3500)
  const before = postedCount()
  const sent = await askModel('帮我在系统设置里看看「关于手机」这一页有什么内容，完成后只回复一行 DONE。')
  if (sent === 'no-composer' || sent?.__error !== undefined) {
    record('P5 真实任务：后台态报告必须投递', 'INCONCLUSIVE', 'composer 不可用：' + JSON.stringify(sent).slice(0, 140))
    return
  }
  record('P5 真实任务已发起（自然语句，不提工具/通知）', 'PASS', 'send=' + sent)
  // 任务跑起来后把应用切到后台：这样报告只能走系统通知（应用内提示看不见）。
  // 不用 `input keyevent 3`——本机型（MuMu/API 35）实测该键不生效（焦点仍在应用上，dumpsys 可证）；
  // 改用一个真实的第三方前台（系统设置），并以 mCurrentFocus 作为判据。
  await sleep(6000)
  sh('am start -n com.android.settings/.Settings')
  await sleep(1500)
  // **背景态是判据的一部分**：没切成功就必须判 INCONCLUSIVE，否则前台截图也能“通过”（假绿）。
  const focus = /mCurrentFocus=Window\{[^}]*?\s([A-Za-z0-9_.]+)\//.exec(sh('dumpsys window | grep -m1 mCurrentFocus'))?.[1] ?? ''
  const background = focus !== '' && focus !== PKG
  record('P5 前置：应用确实在后台', background ? 'PASS' : 'INCONCLUSIVE',
    background ? `前台包=${focus}` : `前台仍是 ${focus || '未知'}——横幅相位不成立`)
  if (!background) return
  const deadline = Date.now() + 180_000
  let hit = false
  while (Date.now() < deadline) {
    if (postedCount() > before) { hit = true; break }
    await sleep(700)
  }
  const bytes = hit ? shot(join(EVID, 'banner.png')) : 0
  record('P5 后台态报告投递（POSTED 新增）', hit ? 'PASS' : 'INCONCLUSIVE',
    hit ? `已 POSTED；横幅截图 ${bytes} B → banner.png（视觉判读）` : '180 s 内没有新的 report POSTED（模型未完成 / 无凭据）')
  // 收尾：回到应用（后续 phase 需要前台/可见）
  sh(`am start -n ${PKG}/.MainActivity`)
  await sleep(2000)
}

async function inject(line, name = INJECT_NAME) {
  runAs(`rm -f files/home/.dsh/${name}`)
  const ln = runAs(`ln ${FILE_REL} files/home/.dsh/${name}`)
  const sz = fileSize(runAs(`stat -c %s files/home/.dsh/${name}`))
  if (sz === null) return { error: 'ln 失败：' + ln.trim().slice(0, 160) }
  const out = adb(['shell', `run-as ${PKG} sh -c 'cat >> files/home/.dsh/${name}'`], line + '\n')
  return { size: sz + (line.length + 1), out }
}

async function main() {
  mkdirSync(EVID, { recursive: true })
  console.log(`=== 通知消费兜底验收（serial=${SERIAL}，等待窗口 ${WAIT_S}s）=== 证据目录 ${EVID}`)

  // P0 前置
  const xml = runAs('cat shared_prefs/dsh-notify.xml')
  const off0 = longPref(xml, 'notify.offset')
  const len0 = sizeNow()
  const probe0 = probe()
  if (off0 === null || len0 === null) {
    record('P0 前置：可读偏移与文件长度', 'INCONCLUSIVE', `offset=${off0} len=${len0}（应用是否在运行 / run-as 是否可用？）`)
    return finish()
  }
  record('P0 前置：可读偏移与文件长度', 'PASS', `offset=${off0} len=${len0} 落后=${len0 - off0} B`)

  // --banner-only：只跑用户症状①（真实任务 + 后台 + 横幅），不做注入 —— 避免自己刚发的通知触发
  // 平台通知冷却，把横幅判据污染掉（本轮实测踩过）。
  if (has('banner-only')) { await bannerPhase(); return finish() }

  // P1 核心：注入一条 report，事件名不在白名单 ⇒ 只有兜底能消费
  const sid = 'inject-p1-' + Date.now().toString(36)
  const line1 = JSON.stringify({
    ts: new Date().toISOString(), kind: 'report', outcome: 'completed', outcomeLabel: '已完成',
    sessionId: sid, title: '注入验收 P1', summary: '硬链接注入：事件名不在白名单，仅兜底可消费',
    durationMs: 1234, durationLabel: '1.2s', toolCount: 0, turn: 1, presentedFiles: [], popup: true,
  })
  const before = offsetNow()
  const injectOut = inject(line1)
  const len1 = sizeNow()
  console.log(`注入：offset=${before} len=${len1}（+${len1 === null ? '?' : len1 - before} B）${injectOut.error ? ' 错误=' + injectOut.error : ''}`)
  if (len1 === null || len1 <= (before ?? 0)) {
    record('P1 注入生效（文件确实变长）', 'INCONCLUSIVE', `len=${len1} offset=${before} ${injectOut.error ?? ''}`)
    return finish()
  }
  let drains = []
  let ok = false
  for (let i = 0; i < WAIT_S; i++) {
    drains = parseDrainLines(probe())
    if (consumedByInjectName(drains)) break
    if (tickConsumed(drains, before)) { ok = true; break }
    await sleep(1000)
  }
  const probeText = probe()
  writeFileSync(join(EVID, 'probe-after-p1.log'), probeText)
  if (consumedByInjectName(drains)) {
    record('P1 兜底消费（事件名不在白名单）', 'FAIL',
      '本轮消费来自事件路径（trigger 含注入名）⇒ 本实验判不出兜底，需改用别的注入方式')
  } else if (ok) {
    record('P1 兜底消费（事件名不在白名单）', 'PASS', `≤${WAIT_S}s 内由 tick 消费：${drains.filter((d) => d.trigger.startsWith('tick')).slice(-1)[0]?.trigger}`)
  } else {
    record('P1 兜底消费（事件名不在白名单）', 'FAIL',
      `${WAIT_S}s 内没有任何 drain 推进（offset=${offsetNow()} len=${sizeNow()}）——兜底未生效`)
  }

  // P2 去重：两条逐字节相同的行（同一会话同内容）
  const line2 = JSON.stringify({
    ts: new Date().toISOString(), kind: 'report', outcome: 'completed', outcomeLabel: '已完成',
    sessionId: 'inject-p2', title: '注入验收 P2', summary: '同内容两行，必须只投一次',
    durationMs: 2000, durationLabel: '2.0s', toolCount: 1, turn: 2, presentedFiles: [], popup: true,
  })
  const dupBaseline = dedupVerdict(probe(4000), 0).dup
  inject(line2 + '\n' + line2, '.notify-inject2')
  let dup = { ok: false, posted: 0, dup: dupBaseline }
  for (let i = 0; i < Math.max(WAIT_S, 15); i++) {
    dup = dedupVerdict(probe(4000), dupBaseline)
    if (dup.ok) break
    await sleep(1000)
  }
  writeFileSync(join(EVID, 'probe-after-p2.log'), probe(4000))
  record('P2 同内容重复行只投一次', dup.ok ? 'PASS' : 'FAIL',
    `DUPLICATE 计数 ${dupBaseline} → ${dup.dup}（判据：本轮新增 + 紧前一条同类为 POSTED）`)

  // P3 无残留：等一小段让本轮的两个 tick/事件都落定，再要求 offset == len
  let off3 = offsetNow()
  let len3 = sizeNow()
  for (let i = 0; i < 20 && off3 !== len3; i++) {
    await sleep(1000)
    off3 = offsetNow()
    len3 = sizeNow()
  }
  record('P3 消费无残留（offset == len）', off3 === len3 ? 'PASS' : 'FAIL', `offset=${off3} len=${len3}`)

  // P4 心跳（5 分钟一格；窗口不够只记 INCONCLUSIVE）。取**最后一条**：tail 里可能还留着上一进程的心跳行。
  const hbAll = [...probe(2000).matchAll(/notify tick alive ticks=[0-9]+[^\n]*/g)].map((m) => m[0])
  const hb = hbAll.length > 0 ? hbAll[hbAll.length - 1] : null
  record('P4 兜底心跳可见', hb !== null ? 'PASS' : 'INCONCLUSIVE',
    hb !== null ? hb : '窗口内未到心跳点（每 5 分钟一行），不算失败')

  // P5 用户症状①：真实任务 + 后台态 → 报告必须投递（截图留证看横幅）
  if (has('banner')) await bannerPhase()

  // 清理注入用目录项（本套件不改产品状态，只动这两个临时名）
  runAs(`rm -f files/home/.dsh/${INJECT_NAME} files/home/.dsh/.notify-inject2`)
  return finish()
}

function finish() {
  writeFileSync(join(EVID, 'results.json'), JSON.stringify(results, null, 2))
  const fail = results.filter((r) => r.verdict === 'FAIL').length
  const inc = results.filter((r) => r.verdict === 'INCONCLUSIVE').length
  console.log(`=== 汇总：PASS=${results.length - fail - inc} FAIL=${fail} INCONCLUSIVE=${inc} ===`)
  process.exit(fail > 0 ? 1 : inc > 0 ? 2 : 0)
}

await main()
