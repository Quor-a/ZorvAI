/**
 * 特权 shell 通道（0.14.0 §6：替换退役的内置 adb 的引擎侧 op 面）。
 *
 * 背景：0.14.0 前引擎侧 `execAdbShell` / `execAdbLine` 在快照内经 Termux 再 spawn 一次 `adb`
 * 客户端（android-tools），依赖无线调试配对；0.14.0 起该面退役，改由壳侧 Shizuku UserService
 * （uid 2000）执行，经控制队列投递（`sh*` 四 op）。
 *
 * 三条纪律：
 * - 两条通道独立可用：`sh*` 是 neverA11y（与 browser/vd 同族），由壳侧 ControlCarrier 承载，
 *   不依赖无障碍服务；
 * - 不做「adb 字符串透传」：`translateAdbLine` 把旧行拆成 exec/pull/push 步骤，工具层不再出现
 *   `adb` 执行面（§8.3 迁移清单）；
 * - 失败必须是结构化的：拒绝面由壳侧给 code + guidance，本文件只负责翻译与归一。
 */

/** 壳侧特权 shell op（与 scripts/control-ops-pending.json 的 shell 族逐字对应）。 */
export const SHELL_OPS = ['shExec', 'shPull', 'shPush', 'shRemove'] as const
export type ShellOp = (typeof SHELL_OPS)[number]

/** 控制队列回执（与 browser/vdisplay 两族同形）。 */
export interface ShellControlReply {
  ok: boolean
  data?: Record<string, unknown>
  error?: string
}

/** 翻译后的单步：只表达「执行 / 取回 / 推送」三种语义，不含 adb 客户端语义。 */
export type ShellStep =
  | { kind: 'exec'; command: string }
  | { kind: 'pull'; remote: string; local: string }
  | { kind: 'push'; local: string; remote: string }

export type ShellLineTranslation =
  | { ok: true; steps: ShellStep[] }
  | { ok: false; error: string }

/** 引号感知的行拆分（`&&` / `;` 分隔；引号内的分隔符不拆）。 */
function splitSegments(line: string): string[] {
  const out: string[] = []
  let cur = ''
  let quote: '"' | "'" | null = null
  for (let i = 0; i < line.length; i++) {
    const ch = line[i]
    if (quote !== null) {
      cur += ch
      if (ch === quote) quote = null
      continue
    }
    if (ch === '"' || ch === "'") {
      quote = ch
      cur += ch
      continue
    }
    if (ch === ';') {
      out.push(cur)
      cur = ''
      continue
    }
    if (ch === '&' && line[i + 1] === '&') {
      out.push(cur)
      cur = ''
      i += 1
      continue
    }
    cur += ch
  }
  out.push(cur)
  return out.map((s) => s.trim()).filter((s) => s.length > 0)
}

/** 去掉一层成对引号（`"..."` / `'...'`）。 */
function unquote(text: string): string {
  const t = text.trim()
  if (t.length >= 2) {
    const first = t[0]
    if ((first === '"' || first === "'") && t[t.length - 1] === first) return t.slice(1, -1)
  }
  return t
}

/** 引号感知的参数拆分（pull/push 的两个位置参数）。 */
function splitArgs(text: string): string[] {
  const out: string[] = []
  let cur = ''
  let quote: '"' | "'" | null = null
  for (const ch of text.trim()) {
    if (quote !== null) {
      if (ch === quote) quote = null
      else cur += ch
      continue
    }
    if (ch === '"' || ch === "'") {
      quote = ch
      continue
    }
    if (ch === ' ' || ch === '\t') {
      if (cur.length > 0) {
        out.push(cur)
        cur = ''
      }
      continue
    }
    cur += ch
  }
  if (cur.length > 0) out.push(cur)
  return out
}

/** 剥掉 adb 全局开关（`-s <serial>` / `-d` / `-e` / `-H <host>` / `-P <port>`）。 */
function stripAdbFlags(rest: string): string {
  let cur = rest.trim()
  for (;;) {
    const match = /^(?:-s\s+\S+|-[de]|-H\s+\S+|-P\s+\S+)\s+/.exec(cur)
    if (match === null) return cur
    cur = cur.slice(match[0].length)
  }
}

/** 把一行旧 adb 用法翻译成特权 shell 步骤；无法翻译一律结构化拒绝（绝不退化成透传）。 */
export function translateAdbLine(line: string): ShellLineTranslation {
  const steps: ShellStep[] = []
  for (const segment of splitSegments(line)) {
    if (!/(^|\s)adb\s/.test(segment) && !segment.startsWith('adb ')) {
      steps.push({ kind: 'exec', command: segment })
      continue
    }
    if (!segment.startsWith('adb ')) {
      steps.push({ kind: 'exec', command: segment })
      continue
    }
    const rest = stripAdbFlags(segment.slice(4))
    if (rest === 'shell' || rest.startsWith('shell ')) {
      const command = unquote(rest.slice('shell'.length))
      if (command.length === 0) return { ok: false, error: 'adb shell 缺少命令' }
      steps.push({ kind: 'exec', command })
      continue
    }
    if (rest.startsWith('pull ')) {
      const args = splitArgs(rest.slice('pull'.length))
      if (args.length < 2) return { ok: false, error: 'adb pull 需要 <远端> <本地> 两个参数' }
      steps.push({ kind: 'pull', remote: args[0], local: args[1] })
      continue
    }
    if (rest.startsWith('push ')) {
      const args = splitArgs(rest.slice('push'.length))
      if (args.length < 2) return { ok: false, error: 'adb push 需要 <本地> <远端> 两个参数' }
      steps.push({ kind: 'push', local: args[0], remote: args[1] })
      continue
    }
    if (rest.startsWith('devices')) {
      // 旧用法只用于展示；换通道后由 shell 自称身份（型号 + 序列号）保持同一信息面。
      steps.push({ kind: 'exec', command: "printf 'List of devices attached\\n%s\\tdevice\\n' \"$(getprop ro.serialno)\"" })
      continue
    }
    if (/^(?:connect|disconnect|kill-server|start-server)\b/.test(rest)) {
      // 常驻 server / 配对语义随 adb 退役：幂等 no-op（不再有需要拉起的服务）。
      steps.push({ kind: 'exec', command: 'true' })
      continue
    }
    return { ok: false, error: `不支持的 adb 子命令（0.14.0 起特权 shell 通道不再提供 adb 客户端语义）：adb ${rest.split(/\s+/)[0] ?? ''}` }
  }
  if (steps.length === 0) return { ok: false, error: '空行（无可执行步骤）' }
  return { ok: true, steps }
}
