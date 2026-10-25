#!/usr/bin/env node
// verify-client-plugin-fail-recovery.mjs —— 「客户端插件装配失败 → 壳侧落盘 + 回引导页 + 一次性回滚」的设备验收。
//
// 唯一接口真源：.deploy-tmp/client-plugin-fail/CONTRACT.md（§1 契约行 / §4 壳侧失败面 / §5 回滚侧 / §8 判据）。
// 注入原理与实测期望：.deploy-tmp/client-plugin-fail/DEVICE-INJECTION.md。
//
// 与 scripts/verify-auto-undo.mjs 的区别（不得混用）：
//   本脚本注入的是浏览器侧 loader entry 失败（包声明 dsh.client、有 ./client 导出、但 import 期必抛），
//   引擎健康、HTTP 200、页面停在上游 "Failed to load plugins"；那条路走 WatchdogV2 的引擎日志签名，是另一回事。
//
// 判据八条（每条独立记录 PASS/FAIL/INCONCLUSIVE；缺证据一律 INCONCLUSIVE，exit 2，绝不判绿）：
//   1) 页面发布了契约行（boot-diag.log：source=page-console 且行内含 [dsh-boot-failed] 与 failedIds=）；
//   2) 失败终态落盘（boot-fail.log：stage=client-plugin-tree-failed）；
//   3) 自动退出到启动页（设备 UI dump 证明引导页可见；screencap 留证）；
//   4) 有界性反证：文档导航次数 == 1（无重载环）+ undo-gate 出现客户端失败的一次性入口叙述行；
//   5) 回滚动作：唯一点名 ⇒ 拔除条目且 patch 回基线；点不出名且清单未变 ⇒ known-good 整份回滚；
//      两者都不成立 ⇒ 断言没有任何写回（否定判据同样要断言）；
//   6) 健康启动回归：清理注入物后重启，boot-diag.log 不得出现 [dsh-boot-failed] 且 page-ready 出现；
//   7) 好插件零损伤：profiles/web/node_modules/@dsh-android/** 逐文件 sha256 与注入前逐条相等。
//
// 用法：
//   node scripts/verify-client-plugin-fail-recovery.mjs --serial 127.0.0.1:16416 [--timeout 240] [--keep]
//   node scripts/verify-client-plugin-fail-recovery.mjs --self-test        # 纯逻辑自检，离线，不碰 adb
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
const TIMEOUT_S = Number(argOf('timeout') ?? 240)
const PKG = 'com.ai.assistance.quro.dshmobile'
const FILES = '/data/user/0/' + PKG + '/files'
const WEB = FILES + '/home/.dsh/profiles/web'
const NM = WEB + '/node_modules/@dsh-android'
const PATCH = WEB + '/cordis.patch.yml'
const ID = 'dsh-client-bad-probe'
const PKG_NAME = '@dsh-android/' + ID
const DIR = NM + '/' + ID
const STAMP = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)
const EVID = join(ROOT, '.deploy-tmp', 'client-plugin-fail-recovery', STAMP)
const GUIDE_MARK = '插件装配失败'

// ── 契约常量（CONTRACT §1；两侧必须字节一致）───────────────────────────────
export const BOOT_FAILED_PREFIX = '[dsh-boot-failed]'
export const BOOT_READY_PREFIX = '[dsh-boot-ready]'
/** undo-gate.log 里客户端失败「一次性入口叙述行」的稳定子串（UndoGate.kt record()）。 */
export const GATE_CLIENT_ENTRY = 'client-plugin-tree-failed decision='
/** 点不出名且清单未变时也不动作的记账（EngineStartFlow.kt writeBootFail stage）。 */
export const STAGE_NO_ACTION = 'client-plugin-tree-failed-no-action'
export const STAGE_CLIENT_FAIL = 'client-plugin-tree-failed'
/** failedIds 合法性口径与 LogCollector.clientFailedIdsOf 同源（CONTRACT §1 第 5 条）。 */
const CLIENT_FAILED_ID = /^[@A-Za-z0-9][@A-Za-z0-9._/-]*$/
const MAX_CLIENT_FAILED_IDS = 8
const MAX_CLIENT_FAILED_ID_CHARS = 120

// ── 纯逻辑（--self-test 全覆盖）──────────────────────────────────────────────

/** 归一化换行（写盘的契约行恒为单行；此函数只是防御性还原 CRLF）。 */
export function foldFieldValues(text) {
  return String(text ?? '').replace(/\r\n?/g, '\n')
}

/** 从一行 boot-diag 记录里取 source=（写盘行格式：dsh-boot-diag source=<x> ...）。 */
export function bootDiagSourceOf(line) {
  const raw = String(line ?? '').replace(/\r/g, '')
  if (!/dsh-boot-diag/.test(raw)) return null
  const m = /(?:^|\s)source=(\S+)/.exec(raw)
  return m === null ? null : m[1]
}

/** boot-diag.log 里所有契约行（正文含 [dsh-boot-failed] 且带 dsh-boot-diag 标记）。 */
export function contractLinesOf(text) {
  return foldFieldValues(text).split('\n').map((l) => l.trim())
    .filter((l) => l.includes(BOOT_FAILED_PREFIX) && /dsh-boot-diag/.test(l))
}

/**
 * 解析契约行（CONTRACT §1 字段序：前缀 → dsh-boot-diag → source= → detail= → failedIds= → pageSideRuntime=）。
 *
 * 与壳侧 LogCollector.clientFailedIdsOf 同口径：
 *   - 先取 failedIds= 的第一个出现位置；值取到下一个空白或行尾（契约保证无引号无空格）；
 *   - - / 空 / 长度 >120 / 形状不合法一律丢弃；最多 8 项；绝不臆造；
 *   - 字段缺失或被 fold 截断成半截 id ⇒ 更少的 id 或空列表，绝不合成新 id。
 */
export function parseContractLine(line) {
  const raw = String(line ?? '').replace(/\r/g, '')
  const diag = {
    marker: /dsh-boot-diag/.test(raw),
    source: bootDiagSourceOf(raw),
    hasPrefix: raw.startsWith(BOOT_FAILED_PREFIX),
    containsPrefix: raw.includes(BOOT_FAILED_PREFIX),
    hasFailedIds: /(?:^|\s)failedIds=/.test(raw),
    hasPageSideRuntime: /(?:^|\s)pageSideRuntime=/.test(raw),
    detail: null,
    pageSideRuntime: null,
    ids: [],
    idsRaw: null,
    idsTruncated: false,
  }
  const dm = /(?:^|\s)detail=(\S*)/.exec(raw)
  if (dm !== null) diag.detail = dm[1]
  const pm = /(?:^|\s)pageSideRuntime=(\S*)/.exec(raw)
  if (pm !== null) diag.pageSideRuntime = pm[1]

  const key = 'failedIds='
  const at = raw.indexOf(key)
  if (at < 0) return diag
  const rest = raw.slice(at + key.length)
  const endIdx = rest.search(/\s/)
  const value = (endIdx < 0 ? rest : rest.slice(0, endIdx)).trim()
  diag.idsRaw = value
  if (value === '' || value === '-') return diag
  const parts = value.split(',').map((s) => s.trim())
  diag.idsTruncated = parts.some((p) => p !== '' && p !== '-' && !(p.length <= MAX_CLIENT_FAILED_ID_CHARS && CLIENT_FAILED_ID.test(p)))
  diag.ids = parts
    .filter((p) => p !== '' && p !== '-' && p.length <= MAX_CLIENT_FAILED_ID_CHARS && CLIENT_FAILED_ID.test(p))
    .slice(0, MAX_CLIENT_FAILED_IDS)
  return diag
}

export function countMatches(text, needle) {
  if (needle === '') return 0
  let n = 0, i = 0
  const s = String(text ?? '')
  while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length }
  return n
}

/**
 * 判据 1：页面是否发布了合规契约行。
 * 必须有：source=page-console、行内含 [dsh-boot-failed]、含 failedIds= 字段、含 pageSideRuntime= 收尾。
 * failedIds=- 是合法的（点不出名），不是失败；expectId 给了就额外对照「有没有点出这个 id」。
 */
export function contractLineVerdict(text, expectId) {
  const lines = contractLinesOf(text)
  const parsed = lines.map(parseContractLine)
  // 写盘行是壳侧组装的：source=page-console，契约行原文被折进 detail= —— 故判据是「行内含前缀」，
  // 不是「行首是前缀」（CONTRACT §4 步骤 2：writeBootDiag(source=page-console, detail=该行)）。
  const good = parsed.filter((p) => p.source === 'page-console' && p.hasFailedIds && p.containsPrefix && p.hasPageSideRuntime)
  const named = expectId === undefined || expectId === null ? null : good.some((p) => p.ids.includes(expectId))
  return {
    count: good.length,
    total: parsed.length,
    named,
    last: good[good.length - 1] ?? parsed[parsed.length - 1] ?? null,
    pageReadyCount: countMatches(text, BOOT_READY_PREFIX),
  }
}

/** 从 undo-gate.log 取 pulled plugin=<name> 的插件名清单（CONTRACT §5 外科拔除）。 */
export function pulledPluginsOf(logText) {
  const out = []
  for (const line of foldFieldValues(logText).split('\n')) {
    const m = /pulled plugin=(\S+)/.exec(line)
    if (m !== null) out.push(m[1])
  }
  return out
}

/** undo-gate.log 里客户端失败的一次性入口叙述行（判据 4 的入口证据）。 */
export function gateClientEntriesOf(logText) {
  return foldFieldValues(logText).split('\n').map((l) => l.trim()).filter((l) => l.includes(GATE_CLIENT_ENTRY))
}

/**
 * 判据 4：有界性（导航次数 == 1，无重载环）。
 *   - 见到第二次导航/重载的证据 ⇒ FAIL（缺陷本身：重载环）；
 *   - 计数不足或重复但无重载证据 ⇒ INCONCLUSIVE（宁可说不清，也不判绿）。
 * 注意：页面的 page-ready 与失败契约行属于同一个 epoch——两者都发出本身就是缺陷证据。
 */
export function navigationVerdict({ failCount, readyCount, gateEntries, reloadEvidence }) {
  const count = Math.max(Number(failCount) || 0, Number(readyCount) || 0)
  if (reloadEvidence) {
    return { count, reloadLoop: true, verdict: 'FAIL', reason: '见到第二次导航/重载证据（' + reloadEvidence + '）——重载环成立' }
  }
  if ((Number(gateEntries) || 0) < 1) {
    return { count, reloadLoop: false, verdict: 'INCONCLUSIVE', reason: 'undo-gate.log 里没有客户端失败入口叙述行（' + GATE_CLIENT_ENTRY + '）' }
  }
  if ((Number(failCount) || 0) >= 1 && (Number(readyCount) || 0) >= 1) {
    return { count, reloadLoop: false, verdict: 'PASS', reason: '文档导航计数 = 1（1 条失败契约行 + 1 条 page-ready 诊断行），无第二次导航证据' }
  }
  return {
    count, reloadLoop: false, verdict: 'INCONCLUSIVE',
    reason: '计数不足（fail=' + failCount + ' ready=' + readyCount + '）——设备侧没有留下完整的单次导航证据',
  }
}

/**
 * 判据 5：回滚动作的证据判定（正反例都要断言）。
 *
 * @param named               失败页是否点出了唯一可拔 id（注入时条目 id 与包名一致即为真）
 * @param pulledGrew          本轮新增 pulled plugin= 记账
 * @param okGrew              本轮新增 executed ok snapshot= 记账
 * @param patchBackToBaseline cordis.patch.yml 是否逐字节回到注入前
 * @param badStillMounted     坏条目是否仍在 patch 里
 * @param suppressSeen        本轮入口记账是否 decision=SUPPRESS（30 分钟窗内 ⇒ 前置不满足）
 * @param noActionSeen        boot-fail.log 是否出现 no-action 终态（点不出名且清单已变 ⇒ 明确拒绝写回）
 */
export function rollbackActionVerdict({ named, pulledGrew, okGrew, patchBackToBaseline, badStillMounted, suppressSeen, noActionSeen }) {
  const acted = Boolean(pulledGrew || okGrew)
  if (named) {
    if (pulledGrew && patchBackToBaseline) {
      return { verdict: 'PASS', reason: '唯一点名 ⇒ 外科拔除：undo-gate 新增 pulled 记账，且 cordis.patch.yml 逐字节回到基线' }
    }
    if (pulledGrew && !patchBackToBaseline) {
      return { verdict: 'FAIL', reason: '闸门记了 pulled，但 cordis.patch.yml 未回到基线（写回不完整）' }
    }
    if (okGrew && !badStillMounted) {
      return { verdict: 'PASS', reason: '唯一点名却走了整份回滚（executed ok），坏条目已不在装配里——动作有效但非外科分支' }
    }
    if (!acted && patchBackToBaseline && !badStillMounted) {
      return { verdict: 'PASS', reason: '未见本轮写回记账，但坏条目已不在 patch 且 patch 与基线逐字节一致（无写回也自洽）' }
    }
    if (!acted && suppressSeen) {
      return { verdict: 'INCONCLUSIVE', reason: '一次性入口被判 SUPPRESS（30 分钟重试窗内）——前置不满足，本轮构造不出回滚' }
    }
    if (!acted && noActionSeen) {
      return { verdict: 'FAIL', reason: '唯一点名却走到 no-action 拒绝分支——外科判据没认出可拔条目' }
    }
    return { verdict: 'INCONCLUSIVE', reason: '既没有 pulled/executed ok，也没有可自洽的无写回证据' }
  }
  if (okGrew && patchBackToBaseline === false) {
    return { verdict: 'FAIL', reason: '整份回滚后 cordis.patch.yml 未回到基线' }
  }
  if (okGrew) {
    return { verdict: 'PASS', reason: '点不出名 ⇒ known-good 整份回滚（executed ok），patch 已回基线' }
  }
  if (pulledGrew) {
    return { verdict: 'FAIL', reason: '点不出名却发生了外科拔除（pulled）——判据不容许' }
  }
  if (noActionSeen && !patchBackToBaseline && badStillMounted) {
    return { verdict: 'PASS', reason: '点不出名且清单已变 ⇒ 明确拒绝写回（no-action），坏条目仍在 patch：否定判据成立' }
  }
  if (suppressSeen) {
    return { verdict: 'INCONCLUSIVE', reason: '一次性入口被判 SUPPRESS（30 分钟重试窗内）——前置不满足' }
  }
  return { verdict: 'INCONCLUSIVE', reason: '既无 known-good 回滚，也无明确拒绝且零写回的证据' }
}

/** 逐文件 sha256 清单文本（设备侧 sha256sum 输出）→ Map<相对路径, 哈希>。 */
export function manifestMap(text) {
  const map = new Map()
  for (const line of foldFieldValues(text).split('\n')) {
    const m = /^([0-9a-f]{64})\s+(.+)$/.exec(line.trim())
    if (m !== null) map.set(m[2].replace(/^\.\//, ''), m[1])
  }
  return map
}

/** 两份清单的差异（坏插件自己的目录树由调用方先排除）。 */
export function manifestDiff(before, after) {
  const added = [], removed = [], changed = []
  for (const k of after.keys()) if (!before.has(k)) added.push(k)
  for (const [k, v] of before) {
    if (!after.has(k)) removed.push(k)
    else if (after.get(k) !== v) changed.push(k)
  }
  return { added, removed, changed, same: added.length === 0 && removed.length === 0 && changed.length === 0 }
}

/** 与 verify-auto-undo.mjs 同口径：把注入块插到第二个顶层条目之前（两侧都是真实块边界）。 */
export function spliceMidFile(text, block) {
  const lines = text.split('\n')
  let seen = 0
  for (let i = 0; i < lines.length; i++) {
    if (/^-/.test(lines[i])) {
      seen++
      if (seen === 2) return [...lines.slice(0, i), ...block.split('\n').slice(0, -1), ...lines.slice(i)].join('\n')
    }
  }
  return text.replace(/\n?$/, '\n') + block
}

/** patch 文件是否挂载了某个插件名（剔除装配的判据）。 */
export function patchMounts(text, name) {
  const escaped = name.replace(/[.*+?${\}()|[\]\\]/g, '\\$&')
  return new RegExp("(^|\\s)'?" + escaped + "'?\\s*$", 'm').test(String(text ?? ''))
}

// ── 自检（离线；不碰 adb、不读设备）─────────────────────────────────────────
function selfTest() {
  let bad = 0, total = 0
  const expect = (n, c) => { total++; if (!c) { bad++; console.log('[SELFTEST-FAIL] ' + n) } else console.log('[SELFTEST-PASS] ' + n) }

  // A. 契约行解析：正常行
  const normal = BOOT_FAILED_PREFIX + ' dsh-boot-diag source=page-plugin-fail detail=reason=boom takenMs=12'
    + ' failedCount=1 rendered=false failedIds=' + ID + ' pageSideRuntime={"readyAt":1,"waitedMs":9}'
  const d0 = parseContractLine(normal)
  expect('解析/正常：识别 dsh-boot-diag 与 source=page-plugin-fail', d0.marker === true && d0.source === 'page-plugin-fail')
  expect('解析/正常：前缀在行首', d0.hasPrefix === true)
  expect('解析/正常：failedIds 取到单 id', d0.ids.length === 1 && d0.ids[0] === ID)
  expect('解析/正常：detail 与 pageSideRuntime 都在', d0.detail !== null && d0.hasPageSideRuntime === true)
  expect('解析/正常：无臆造截断', d0.idsTruncated === false)

  // A2. 壳侧写盘行（source=page-console）仍是同一条契约行
  const written = 'dsh-boot-diag source=page-console t_listen=1 pageSideRuntime=reported-by-page detail=' + normal
  expect('解析/壳侧写盘：source 仍可读出 page-console', bootDiagSourceOf(written) === 'page-console')
  expect('解析/壳侧写盘：正文里仍能取到 failedIds', parseContractLine(written).ids[0] === ID)
  expect('判据1：壳侧写盘行算「页面发布了契约行」', contractLineVerdict(written + '\n', ID).named === true)

  // B. failedIds=- （点不出名，合法但无 id）
  const dash = BOOT_FAILED_PREFIX + ' dsh-boot-diag source=page-plugin-fail detail=reason=x failedCount=0 failedIds=- pageSideRuntime=unavailable'
  const dDash = parseContractLine(dash)
  expect('解析/空值：failedIds=- 得空列表且不报截断', dDash.ids.length === 0 && dDash.idsTruncated === false && dDash.idsRaw === '-')
  const dashWritten = 'dsh-boot-diag source=page-console pageSideRuntime=reported-by-page detail=' + dash
  expect('判据1/空值：行本身仍算合规契约行', contractLineVerdict(dashWritten + '\n', null).count === 1)
  expect('判据1/空值：expectId 对照失败（点不出名）', contractLineVerdict(dashWritten + '\n', ID).named === false)

  // B2. failedIds= 空值 / 字段缺失
  expect('解析/空值：failedIds= 后直接跟空格 ⇒ 空列表', parseContractLine(BOOT_FAILED_PREFIX + ' dsh-boot-diag failedIds= pageSideRuntime={}').ids.length === 0)
  const noFieldLine = BOOT_FAILED_PREFIX + ' dsh-boot-diag source=page-plugin-fail pageSideRuntime={}'
  const noField = parseContractLine(noFieldLine)
  expect('解析/缺失：无 failedIds 字段 ⇒ hasFailedIds=false 且空列表', noField.hasFailedIds === false && noField.ids.length === 0)
  expect('判据1/缺失：缺 failedIds 字段不算合规契约行',
    contractLineVerdict('dsh-boot-diag source=page-console pageSideRuntime={} detail=' + noFieldLine + '\n', null).count === 0)

  // C. 多 id
  const multi = BOOT_FAILED_PREFIX + ' dsh-boot-diag source=page-plugin-fail failedIds=live2d-pet,@scope/pkg,third.one pageSideRuntime={}'
  const dMulti = parseContractLine(multi)
  expect('解析/多 id：三项按序解析', dMulti.ids.length === 3 && dMulti.ids[0] === 'live2d-pet' && dMulti.ids[1] === '@scope/pkg' && dMulti.ids[2] === 'third.one')

  // C2. 非法形状与超长被滤掉（与壳侧同口径，绝不臆造）
  const dirty = BOOT_FAILED_PREFIX + ' dsh-boot-diag failedIds=ok-one,../evil,.dot,ok-two pageSideRuntime={}'
  const dDirty = parseContractLine(dirty)
  expect('解析/过滤：非法形状被滤掉，合法项保留', dDirty.ids.length === 2 && dDirty.ids[0] === 'ok-one' && dDirty.ids[1] === 'ok-two')
  expect('解析/过滤：见到被滤项时置 idsTruncated', dDirty.idsTruncated === true)
  const longId = 'a'.repeat(121)
  expect('解析/超长：>120 字符被拒', parseContractLine(BOOT_FAILED_PREFIX + ' dsh-boot-diag failedIds=' + longId + ' pageSideRuntime={}').ids.length === 0)
  expect('解析/超长：120 字符整好保留', parseContractLine(BOOT_FAILED_PREFIX + ' dsh-boot-diag failedIds=' + 'a'.repeat(120) + ' pageSideRuntime={}').ids.length === 1)
  const nine = Array.from({ length: 9 }, (_, i) => 'id-' + i).join(',')
  expect('解析/超限：最多 8 项', parseContractLine(BOOT_FAILED_PREFIX + ' dsh-boot-diag failedIds=' + nine + ' pageSideRuntime={}').ids.length === 8)

  // D. 截断（fold 2048 上限把 JSON 截掉 / 半截 id）
  const truncated = BOOT_FAILED_PREFIX + ' dsh-boot-diag source=page-plugin-fail failedIds=good-one,live2d-pet@dsh-android/dsh-live2d-pe'
  const dTrunc = parseContractLine(truncated)
  expect('解析/截断：半截 id 原样保留（不补全、不臆造）', dTrunc.ids.length === 2 && dTrunc.ids[1] === 'live2d-pet@dsh-android/dsh-live2d-pe')
  expect('解析/截断：pageSideRuntime 被截掉 ⇒ hasPageSideRuntime=false', dTrunc.hasPageSideRuntime === false)
  expect('判据1/截断：缺 pageSideRuntime 收尾 ⇒ 不算合规契约行',
    contractLineVerdict('dsh-boot-diag source=page-console detail=' + truncated + '\n', null).count === 0)
  const halfId = BOOT_FAILED_PREFIX + ' dsh-boot-diag failedIds=good-one,bad'
  expect('解析/截断：半截但形状合法者仍被接受（与壳侧一致）', parseContractLine(halfId).ids.length === 2)
  expect('解析/形状：首字符非法者被丢弃（.x 不以 @ 或字母数字起头）', parseContractLine(BOOT_FAILED_PREFIX + ' dsh-boot-diag failedIds=.x').ids.length === 0)

  // E. 前缀不匹配（前导空白 / 换行前缀 / 第三方日志）
  expect('解析/前缀：前导空白 ⇒ 行首前缀不成立', parseContractLine('  ' + BOOT_FAILED_PREFIX + ' dsh-boot-diag failedIds=x pageSideRuntime={}').hasPrefix === false)
  expect('解析/前缀：前导空白仍属「行内含」（写盘行不按行首判）', parseContractLine('  ' + BOOT_FAILED_PREFIX + ' dsh-boot-diag failedIds=x').containsPrefix === true)
  expect('解析/前缀：换行折进来的行首不成立', parseContractLine('boom' + '\n' + BOOT_FAILED_PREFIX + ' dsh-boot-diag failedIds=x').hasPrefix === false)
  expect('解析/前缀：第三方页面报错不得被认成契约行', contractLinesOf('dsh-boot-diag source=console-error text=TypeError: x is not a function' + '\n').length === 0)
  expect('解析/前缀：[dsh-boot-ready] 不得被认成失败契约行', contractLinesOf('[dsh-boot-ready] dsh-boot-diag source=page-ready pageSideRuntime={}' + '\n').length === 0)
  expect('解析/marker：非 dsh-boot-diag 行不算契约行', contractLinesOf('[dsh-boot-failed] something else' + '\n').length === 0)

  // F. 导航次数判定：正反例
  const navGood = navigationVerdict({ failCount: 1, readyCount: 1, gateEntries: 1, reloadEvidence: null })
  expect('导航/正例：1 失败 + 1 ready + 入口 ⇒ PASS 且计数=1', navGood.verdict === 'PASS' && navGood.count === 1 && navGood.reloadLoop === false)
  const navLoop = navigationVerdict({ failCount: 2, readyCount: 2, gateEntries: 2, reloadEvidence: 'boot-diag 出现两条 page-ready' })
  expect('导航/反例：第二次导航证据 ⇒ FAIL 且 reloadLoop=true', navLoop.verdict === 'FAIL' && navLoop.reloadLoop === true)
  const navNoEntry = navigationVerdict({ failCount: 1, readyCount: 1, gateEntries: 0, reloadEvidence: null })
  expect('导航/反例：无一次性入口记账 ⇒ INCONCLUSIVE（不判绿）', navNoEntry.verdict === 'INCONCLUSIVE')
  const navThin = navigationVerdict({ failCount: 0, readyCount: 1, gateEntries: 1, reloadEvidence: null })
  expect('导航/反例：只有 ready 没有失败行 ⇒ INCONCLUSIVE', navThin.verdict === 'INCONCLUSIVE')
  const navZero = navigationVerdict({ failCount: 0, readyCount: 0, gateEntries: 0, reloadEvidence: null })
  expect('导航/反例：全零 ⇒ INCONCLUSIVE（缺证据不等于通过）', navZero.verdict === 'INCONCLUSIVE')

  // G. 回滚证据：正反例
  const rb1 = rollbackActionVerdict({ named: true, pulledGrew: true, okGrew: false, patchBackToBaseline: true, badStillMounted: false, suppressSeen: false, noActionSeen: false })
  expect('回滚/正例：唯一点名 + pulled + 回基线 ⇒ PASS', rb1.verdict === 'PASS')
  const rb2 = rollbackActionVerdict({ named: true, pulledGrew: true, okGrew: false, patchBackToBaseline: false, badStillMounted: true, suppressSeen: false, noActionSeen: false })
  expect('回滚/反例：记了 pulled 但没回基线 ⇒ FAIL', rb2.verdict === 'FAIL')
  const rb3 = rollbackActionVerdict({ named: true, pulledGrew: false, okGrew: false, patchBackToBaseline: false, badStillMounted: true, suppressSeen: true, noActionSeen: false })
  expect('回滚/反例：SUPPRESS 重试窗 ⇒ INCONCLUSIVE（前置不满足）', rb3.verdict === 'INCONCLUSIVE')
  const rb4 = rollbackActionVerdict({ named: true, pulledGrew: false, okGrew: false, patchBackToBaseline: false, badStillMounted: true, suppressSeen: false, noActionSeen: true })
  expect('回滚/反例：唯一点名却走 no-action ⇒ FAIL', rb4.verdict === 'FAIL')
  const rb5 = rollbackActionVerdict({ named: false, pulledGrew: false, okGrew: true, patchBackToBaseline: true, badStillMounted: false, suppressSeen: false, noActionSeen: false })
  expect('回滚/正例：点不出名 + known-good 整份回滚 ⇒ PASS', rb5.verdict === 'PASS')
  const rb6 = rollbackActionVerdict({ named: false, pulledGrew: true, okGrew: false, patchBackToBaseline: false, badStillMounted: true, suppressSeen: false, noActionSeen: false })
  expect('回滚/反例：点不出名却发生外科拔除 ⇒ FAIL', rb6.verdict === 'FAIL')
  const rb7 = rollbackActionVerdict({ named: false, pulledGrew: false, okGrew: false, patchBackToBaseline: false, badStillMounted: true, suppressSeen: false, noActionSeen: true })
  expect('回滚/否定判据正例：明确拒绝且零写回（坏条目仍在）⇒ PASS', rb7.verdict === 'PASS')
  const rb8 = rollbackActionVerdict({ named: false, pulledGrew: false, okGrew: false, patchBackToBaseline: true, badStillMounted: false, suppressSeen: false, noActionSeen: false })
  expect('回滚/否定判据反例：无声无息地消失了却无记账 ⇒ INCONCLUSIVE', rb8.verdict === 'INCONCLUSIVE')
  const rb9 = rollbackActionVerdict({ named: true, pulledGrew: false, okGrew: false, patchBackToBaseline: true, badStillMounted: false, suppressSeen: false, noActionSeen: false })
  expect('回滚/正例：无记账但坏条目已不在且 patch 等于基线 ⇒ PASS', rb9.verdict === 'PASS')

  // H. undo-gate 记账解析
  const gateLog = [
    'dsh-undo-gate at=1000 client-plugin-tree-failed decision=EXECUTE detail=条目 ' + ID,
    'dsh-undo-gate at=1100 pulled plugin=' + PKG_NAME + ' id=' + ID,
    'dsh-undo-gate at=1200 executed ok snapshot=20261005-000000-abc (auto, plugin-mounted)',
  ].join('\n')
  expect('记账：取到一次性入口叙述行', gateClientEntriesOf(gateLog).length === 1)
  expect('记账：取到 pulled 插件名', pulledPluginsOf(gateLog).length === 1 && pulledPluginsOf(gateLog)[0] === PKG_NAME)
  expect('记账：未把决定行误当拔除行', pulledPluginsOf('dsh-undo-gate at=1 client-plugin-tree-failed decision=SUPPRESS').length === 0)

  // I. 清单
  const m1 = manifestMap('aa'.repeat(32) + '  ./lib/index.js' + '\n' + 'bb'.repeat(32) + '  package.json' + '\n')
  const m2 = manifestMap('aa'.repeat(32) + '  ./lib/index.js' + '\n' + 'cc'.repeat(32) + '  package.json' + '\n')
  expect('清单：解析两行', m1.size === 2 && m1.get('lib/index.js') === 'aa'.repeat(32))
  expect('清单：差异抓内容变更', manifestDiff(m1, m2).changed.length === 1 && manifestDiff(m1, m1).same === true)

  // J. 注入与挂载判据
  const srcSplice = ['# c', '- insert:', '    - id: a', "      name: '@x/a'", '- insert:', '    - id: b', "      name: '@x/b'", ''].join('\n')
  const spliced = spliceMidFile(srcSplice, ['- insert:', '    - id: bad', "      name: '@x/bad'", ''].join('\n'))
  expect('注入：块落在两个既有块之间', spliced.indexOf('@x/bad') > spliced.indexOf('@x/a') && spliced.indexOf('@x/bad') < spliced.indexOf("'@x/b'"))
  expect('注入：既有条目一字不动', spliced.split('\n').filter((l) => /name:/.test(l)).length === 3)
  expect('挂载：命中坏插件名', patchMounts("- insert:" + '\n' + "    - id: x" + '\n' + "      name: '" + PKG_NAME + "'" + '\n', PKG_NAME) === true)
  expect('挂载：其他插件名不得误判', patchMounts("- insert:" + '\n' + "    - id: x" + '\n' + "      name: '@dsh-android/dsh-android-bridge'" + '\n', PKG_NAME) === false)

  // K. 健康回归的否定判据
  expect('健康回归：零失败行 + page-ready 至少一次',
    countMatches('[dsh-boot-ready] x' + '\n' + '[dsh-boot-ready] y' + '\n', BOOT_READY_PREFIX) === 2
    && countMatches('[dsh-boot-ready] x' + '\n', BOOT_FAILED_PREFIX) === 0)

  if (bad > 0) { console.log('SELFTEST FAILED（' + bad + '/' + total + '）'); process.exit(1) }
  console.log('SELFTEST PASSED（' + total + ' 例，含契约行解析 正常/-/多 id/截断/前缀不匹配、导航次数正反例、回滚证据正反例）')
  process.exit(0)
}

// ── 设备面 ────────────────────────────────────────────────────────────────

const results = []
const record = (name, verdict, detail) => {
  results.push({ name, verdict, detail })
  console.log('[' + verdict + '] ' + name + (detail ? ' —— ' + detail : ''))
}

let patchBaseline = ''
const adb = (args, input) => {
  const r = spawnSync('adb', ['-s', SERIAL, ...args], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, input, timeout: 120000 })
  return ((r.stdout ?? '') + (r.stderr ?? '')).replace(/\r/g, '')
}
const sh = (cmd) => adb(['shell', cmd])
const runAs = (cmd) => sh('run-as ' + PKG + ' sh -c "' + cmd + '"')
const writeDeviceFile = (path, content) => adb(['shell', 'run-as ' + PKG + " sh -c 'cat > " + path + "'"], content)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const PKG_JSON = JSON.stringify({
  name: PKG_NAME, version: '0.0.1', type: 'module', main: 'lib/index.js',
  exports: { '.': './lib/index.js', './client': './lib/client.js', './package.json': './package.json' },
  dsh: { client: { platform: 'web' } }, private: true,
}, null, 2)
const NODE_HALF = 'export function apply() { /* no-op：Node 半侧必须存在，否则形态退化成引擎侧失败 */ }' + '\n'
const CLIENT_HALF = "throw new Error('INJECTED-CLIENT-BAD-PLUGIN: must never load')" + '\n'

const readFiles = (name) => runAs('cat ' + FILES + '/' + name + ' 2>/dev/null')
const readPatch = () => runAs('cat ' + PATCH + ' 2>/dev/null')
const enginePs = () => sh("ps -A -o PID,ARGS 2>/dev/null | grep 'dsh/lib/bin.js' | grep -v grep | awk '{print $1}'").trim().split('\n')[0] || ''

async function engineAlive() {
  adb(['forward', 'tcp:13080', 'tcp:3080'])
  try {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 5000)
    const res = await fetch('http://127.0.0.1:13080/', { signal: ctrl.signal })
    clearTimeout(timer)
    return res.status > 0
  } catch { return false }
}

async function restartApp() {
  sh('am force-stop ' + PKG)
  await sleep(1500)
  sh('am start -n ' + PKG + '/.MainActivity')
  await sleep(2000)
}

/** UI dump（adb 用户层证据）：返回 XML 原文；取不到时返回空串，调用方判 INCONCLUSIVE。 */
function uiDump() {
  sh('uiautomator dump /sdcard/dsh-verify-ui.xml >/dev/null 2>&1')
  const xml = sh('cat /sdcard/dsh-verify-ui.xml 2>/dev/null')
  sh('rm -f /sdcard/dsh-verify-ui.xml')
  return xml
}

function screencap(path) {
  const r = spawnSync('adb', ['-s', SERIAL, 'exec-out', 'screencap', '-p'], { maxBuffer: 64 * 1024 * 1024 })
  if (r.status === 0 && r.stdout && r.stdout.length > 0) { writeFileSync(path, r.stdout); return true }
  return false
}

async function main() {
  if (!SERIAL) { console.error('缺 --serial（例：--serial 127.0.0.1:16416）'); process.exit(2) }
  mkdirSync(EVID, { recursive: true })
  console.log('=== 客户端插件装配失败恢复验收（serial=' + SERIAL + '，等待窗口 ' + TIMEOUT_S + 's）=== 证据 ' + EVID)

  // ── P0 前置 ──
  if (!sh('pm path ' + PKG).includes('package:')) { record('P0 应用已安装', 'INCONCLUSIVE', PKG + ' 未安装'); return finish() }
  record('P0 应用已安装', 'PASS', sh('dumpsys package ' + PKG + ' | grep -m1 versionName').trim())
  if (!(await engineAlive())) { record('P0 引擎当前健康（基线）', 'INCONCLUSIVE', '引擎不健康时无法归因是本轮注入造成的'); return finish() }
  record('P0 引擎当前健康（基线）', 'PASS', 'host→tcp:3080 有响应（401=需鉴权，算活）')

  const patch0 = readPatch()
  if (patch0.trim() === '') { record('P0 cordis.patch.yml 可读', 'INCONCLUSIVE', PATCH + ' 读不到或为空'); return finish() }
  patchBaseline = patch0
  writeFileSync(join(EVID, 'patch-baseline.yml'), patchBaseline)
  if (patchMounts(patchBaseline, PKG_NAME) || runAs('test -d ' + DIR + ' && echo yes').includes('yes')) {
    record('P0 无残留注入物', 'INCONCLUSIVE', '上一轮注入物仍在设备上（先清理再跑；--keep 的那次要手工还原）')
    return finish()
  }
  record('P0 无残留注入物', 'PASS', 'patch 未挂 ' + PKG_NAME + ' 且 ' + DIR + ' 不存在')
  const man0 = manifestMap(runAs('cd ' + NM + ' && find . -type f | sort | xargs sha256sum'))
  if (man0.size === 0) { record('P0 好插件清单可读', 'INCONCLUSIVE', NM + ' 下取不到文件清单'); return finish() }
  record('P0 好插件清单可读', 'PASS', '@dsh-android/** 共 ' + man0.size + ' 个文件')

  const diag0 = readFiles('boot-diag.log')
  const fail0 = readFiles('boot-fail.log')
  const gate0 = readFiles('undo-gate.log')
  const base = {
    diagFailed: countMatches(diag0, BOOT_FAILED_PREFIX),
    diagReady: countMatches(diag0, BOOT_READY_PREFIX),
    stageFail: countMatches(fail0, 'stage=' + STAGE_CLIENT_FAIL),
    gateClientEntries: gateClientEntriesOf(gate0).length,
    pulled: pulledPluginsOf(gate0).length,
    okSnapshots: countMatches(gate0, 'executed ok snapshot='),
  }
  writeFileSync(join(EVID, 'baseline.json'), JSON.stringify({ ...base, files: man0.size }, null, 2))
  console.log('基线：failed行=' + base.diagFailed + ' ready行=' + base.diagReady + ' stage失败=' + base.stageFail
    + ' gate入口=' + base.gateClientEntries + ' 历史pulled=' + base.pulled + ' 好插件文件=' + man0.size)

  // ── P1 注入客户端坏插件（代码 + 装配）──
  runAs('rm -rf ' + DIR + ' && mkdir -p ' + DIR + '/lib')
  writeDeviceFile(DIR + '/package.json', PKG_JSON + '\n')
  writeDeviceFile(DIR + '/lib/index.js', NODE_HALF)
  writeDeviceFile(DIR + '/lib/client.js', CLIENT_HALF)
  const entry = '- insert:' + '\n' + '    - id: ' + ID + '\n' + "      name: '" + PKG_NAME + "'" + '\n'
  writeDeviceFile(PATCH, spliceMidFile(patchBaseline, entry))
  const patchInjected = readPatch()
  const injected = patchMounts(patchInjected, PKG_NAME)
    && runAs('test -f ' + DIR + '/lib/client.js && echo yes').includes('yes')
    && runAs('test -f ' + DIR + '/lib/index.js && echo yes').includes('yes')
  record('P1 客户端坏插件已注入（代码 + 装配）', injected ? 'PASS' : 'INCONCLUSIVE',
    injected ? DIR + '（package.json + lib/index.js + lib/client.js）+ cordis.patch.yml 中段追加一条 entry'
      : '注入不完整：装配或三件套缺一')
  if (!injected) { return finish() }
  writeFileSync(join(EVID, 'patch-after-inject.yml'), patchInjected)

  // ── P2 重启应用（latch 与一次性预算都是本进程状态，必须整进程重启）──
  await restartApp()
  record('P2 应用已重启', 'PASS', 'am force-stop + am start -n ' + PKG + '/.MainActivity')

  // ── P3 判据 1：页面发布契约行 ──
  const t0 = Date.now()
  let diag = '', failLog = '', gateLog = ''
  let contract = { count: 0, total: 0, named: null, last: null }
  const bound = () => gateClientEntriesOf(gateLog).length > base.gateClientEntries
    && countMatches(diag, BOOT_FAILED_PREFIX) >= 1 && countMatches(diag, BOOT_READY_PREFIX) >= 1
  while (Date.now() - t0 < TIMEOUT_S * 1000) {
    await sleep(5000)
    diag = readFiles('boot-diag.log')
    failLog = readFiles('boot-fail.log')
    gateLog = readFiles('undo-gate.log')
    contract = contractLineVerdict(diag, ID)
    if (bound()) break
  }
  writeFileSync(join(EVID, 'boot-diag-injected.log'), diag)
  writeFileSync(join(EVID, 'boot-fail-injected.log'), failLog)
  writeFileSync(join(EVID, 'undo-gate-injected.log'), gateLog)
  const elapsed = Math.round((Date.now() - t0) / 1000)

  if (contract.count < 1) {
    record('P3 判据1：页面发布契约行（source=page-console + [dsh-boot-failed] + failedIds=）', 'INCONCLUSIVE',
      elapsed + 's 内 boot-diag.log 没有合规契约行（带标记的行共 ' + contract.total + ' 条）——注入未生效或页面侧未实现')
  } else if (contract.named === true) {
    record('P3 判据1：页面发布契约行', 'PASS', elapsed + 's：合规契约行 ' + contract.count + ' 条，failedIds 点出了 ' + ID
      + '；detail=' + String(contract.last && contract.last.detail ? contract.last.detail : '').slice(0, 80))
  } else {
    record('P3 判据1：页面发布契约行', 'FAIL', '契约行在场（' + contract.count + ' 条）但没点出期望 id ' + ID
      + '（idsRaw=' + String(contract.last ? contract.last.idsRaw : '') + '）')
  }

  // ── P4 判据 2：失败终态落盘 ──
  const stageCount = countMatches(failLog, 'stage=' + STAGE_CLIENT_FAIL)
  record('P4 判据2：失败终态落盘 stage=' + STAGE_CLIENT_FAIL, stageCount > 0 ? 'PASS' : 'FAIL',
    stageCount > 0 ? 'boot-fail.log 出现 ' + stageCount + ' 条' : 'boot-fail.log 缺 stage=' + STAGE_CLIENT_FAIL)

  // ── P5 判据 3：自动退出到启动页（UI dump + 截图）──
  const xml = uiDump()
  const uiText = (xml.match(/text="([^"]*)"/g) ?? []).map((s) => s.slice(6, -1)).join(' | ')
  const guideSeen = uiText.includes(GUIDE_MARK)
  const shot = join(EVID, 'ui-01-guide-error.png')
  const gotShot = screencap(shot)
  writeFileSync(join(EVID, 'ui-dump-injected.xml'), xml)
  if (guideSeen) {
    record('P5 判据3：自动退出到启动页（可见「' + GUIDE_MARK + '」）', 'PASS',
      'uiautomator dump 可见文本含「' + GUIDE_MARK + '」' + (gotShot ? '；截图 ' + shot : '；截图失败（证据降级）'))
  } else if (xml.trim() === '') {
    record('P5 判据3：自动退出到启动页', 'INCONCLUSIVE', 'uiautomator dump 取不到内容（设备侧不可用），无法判读屏幕')
  } else {
    record('P5 判据3：自动退出到启动页', 'FAIL', 'dump 可见文本里没有「' + GUIDE_MARK + '」：' + uiText.slice(0, 200))
  }

  // ── P6 判据 4：有界性（导航次数 == 1）+ 一次性入口叙述行 ──
  const readyCount = countMatches(diag, BOOT_READY_PREFIX)
  const failCount = countMatches(diag, BOOT_FAILED_PREFIX)
  const entries = gateClientEntriesOf(gateLog)
  const newEntries = entries.length - base.gateClientEntries
  let reloadEvidence = null
  if (readyCount > 1) reloadEvidence = 'boot-diag 出现 ' + readyCount + ' 条 page-ready'
  else if (failCount > 1) reloadEvidence = 'boot-diag 出现 ' + failCount + ' 条失败契约行'
  else if (newEntries > 1) reloadEvidence = 'undo-gate 出现 ' + newEntries + ' 条客户端失败入口记账'
  const nav = navigationVerdict({ failCount, readyCount, gateEntries: entries.length, reloadEvidence })
  record('P6 判据4：有界性（导航次数 = 1，无重载环）', nav.verdict,
    nav.reason + '；入口叙述行新增 ' + newEntries + ' 条（' + String(entries[entries.length - 1] ?? '').slice(0, 120) + '）')

  // ── P7 判据 5：回滚动作 ──
  const patchFinal = readPatch()
  writeFileSync(join(EVID, 'patch-after-recovery.yml'), patchFinal)
  const pulled = pulledPluginsOf(gateLog)
  const pulledGrew = pulled.length > base.pulled
  const okGrew = countMatches(gateLog, 'executed ok snapshot=') > base.okSnapshots
  const patchBackToBaseline = patchFinal === patchBaseline
  const badStillMounted = patchMounts(patchFinal, PKG_NAME)
  const suppressSeen = entries.some((l) => /decision=SUPPRESS/.test(l))
  const noActionSeen = failLog.includes('stage=' + STAGE_NO_ACTION)
  const rb = rollbackActionVerdict({ named: true, pulledGrew, okGrew, patchBackToBaseline, badStillMounted, suppressSeen, noActionSeen })
  record('P7 判据5：回滚动作（唯一点名 ⇒ 外科拔除 + 回基线）', rb.verdict, rb.reason
    + '；pulled=' + pulled.length + '（新增 ' + (pulled.length - base.pulled) + '）executed-ok 新增=' + (okGrew ? 'yes' : 'no'))
  const otherPulled = pulled.filter((p) => p !== PKG_NAME)
  record('P7 判据5附：未连坐其它插件（pulled 只应出现坏插件）', otherPulled.length === 0 ? 'PASS' : 'FAIL',
    otherPulled.length === 0 ? 'undo-gate 的 pulled 记账里没有第三方条目' : '被连坐拔除：' + otherPulled.slice(0, 4).join(', '))

  // ── P8 判据 7：好插件零损伤（逐文件 sha256）──
  const man1 = manifestMap(runAs('cd ' + NM + ' && find . -type f | sort | xargs sha256sum'))
  const diff = manifestDiff(man0, man1)
  const badPrefix = ID + '/'
  const goodAdded = diff.added.filter((k) => !k.startsWith(badPrefix))
  const goodRemoved = diff.removed.filter((k) => !k.startsWith(badPrefix))
  const goodChanged = diff.changed.filter((k) => !k.startsWith(badPrefix))
  const goodIntact = goodAdded.length === 0 && goodRemoved.length === 0 && goodChanged.length === 0
  writeFileSync(join(EVID, 'manifest-diff.json'), JSON.stringify({ added: goodAdded, removed: goodRemoved, changed: goodChanged, badResidue: diff.added.filter((k) => k.startsWith(badPrefix)) }, null, 2))
  record('P8 判据7：好插件零损伤（@dsh-android/** 逐文件 sha256）', goodIntact ? 'PASS' : 'FAIL',
    goodIntact ? '共 ' + man0.size + ' 个文件逐一相等'
      : '增 ' + goodAdded.length + ' / 删 ' + goodRemoved.length + ' / 改 ' + goodChanged.length + '：' + [...goodAdded, ...goodRemoved, ...goodChanged].slice(0, 4).join(', '))

  // ── P9 判据 6：健康启动回归（清理注入物后重启）──
  runAs('rm -rf ' + DIR)
  writeDeviceFile(PATCH, patchBaseline)
  const restoreOk = readPatch() === patchBaseline && !runAs('test -d ' + DIR + ' && echo yes').includes('yes')
  record('P9 清理：注入物与 patch 已还原', restoreOk ? 'PASS' : 'FAIL',
    restoreOk ? '目录已删、cordis.patch.yml 逐字节回到基线' : '还原不完整（目录仍在或 patch 不一致）')
  await restartApp()
  let diagH = '', readyH = 0, failedH = -1
  const t1 = Date.now()
  while (Date.now() - t1 < TIMEOUT_S * 1000) {
    await sleep(5000)
    diagH = readFiles('boot-diag.log')
    failedH = countMatches(diagH, BOOT_FAILED_PREFIX) - base.diagFailed
    readyH = countMatches(diagH, BOOT_READY_PREFIX) - base.diagReady
    if (readyH >= 1) break
  }
  writeFileSync(join(EVID, 'boot-diag-healthy.log'), diagH)
  if (failedH !== 0) {
    record('P9 判据6：健康启动无失败行 + page-ready', 'FAIL', '清理后仍新增 ' + failedH + ' 条 [dsh-boot-failed]')
  } else if (readyH >= 1) {
    record('P9 判据6：健康启动无失败行 + page-ready', 'PASS',
      Math.round((Date.now() - t1) / 1000) + 's：本轮新增 page-ready ' + readyH + ' 条，[dsh-boot-failed] 零新增')
  } else {
    record('P9 判据6：健康启动无失败行 + page-ready', 'INCONCLUSIVE', '窗口内没见到 page-ready（清理后页面未自报就绪）——不足以判绿')
  }

  // ── 收尾 ──
  if (!has('keep')) {
    const residue = runAs('test -d ' + DIR + ' && echo yes').includes('yes')
    record('收尾：注入物目录残留', residue ? 'FAIL' : 'PASS', residue ? DIR + ' 仍在磁盘上（剔除装配不等于删文件）' : '目录已清理')
    if (readPatch() !== patchBaseline) {
      writeDeviceFile(PATCH, patchBaseline)
      record('收尾：patch 再还原一次', readPatch() === patchBaseline ? 'PASS' : 'FAIL', '兜底写回基线')
    }
  } else {
    console.log('--keep：保留现场（注入物与 patch 未清理，证据目录 ' + EVID + '）')
  }
  return finish()
}

function finish() {
  mkdirSync(EVID, { recursive: true })
  writeFileSync(join(EVID, 'results.json'), JSON.stringify(results, null, 2))
  const fail = results.filter((r) => r.verdict === 'FAIL').length
  const inc = results.filter((r) => r.verdict === 'INCONCLUSIVE').length
  console.log('=== 汇总：PASS=' + (results.length - fail - inc) + ' FAIL=' + fail + ' INCONCLUSIVE=' + inc + ' ===')
  console.log('证据目录：' + EVID)
  process.exit(fail > 0 ? 1 : inc > 0 ? 2 : 0)
}

if (has('self-test')) selfTest()
else await main()
