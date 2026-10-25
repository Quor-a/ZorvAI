/**
 * 用户面文案唯一真源（**页面侧**，0.14.1 批 3 / P3-1）。
 *
 * 规则（`docs/0.14.1-COPY-STANDARD.md`）：
 *  1. **机器码不上屏**。壳侧 `reason` / HTTP 状态码 / 内部 key 只允许进 `data-*` 属性与日志；
 *     用户看到的必须是这里给出的「发生了什么 + 你现在能做什么」。
 *  2. **映射表落一处**。页面侧所有「码 → 中文」都在本文件；任何组件里再写一张局部表
 *     （副本）都算回归——两张表必然漂移，这正是本批要收的形态。
 *  3. 表里查不到的码**也要给人话**：回一句可反馈的兜底，而不是把码原样抛给用户。
 *     兜底句里不带原码，原码由调用方放进 `data-*`（可截图/可 grep，但不打断阅读）。
 *
 * 为什么页面侧与壳侧各有一张表：两侧是**两种语言的两个渲染面**（WebView 页面 / 原生 UI），
 * 不存在共享的常量载体。故约定「每个渲染面一张表、各一张」，并在规范文档里登记这两张表的
 * 位置与覆盖的码集合；壳侧那张是 `UserCopy.kt`。
 */

/**
 * 调用失败原因 → 人话。
 *
 * 码集合来自壳侧（`AndroidBridge` / `ExternalLinks` / `PathOpen` / `ControlCarrier` /
 * `BrowserHost`）与页面侧自查（`open-path.ts`）。两侧都可能新增码，**新增时必须在这里补一行**：
 * `user-copy.spec.ts` 有一条断言把页面侧自查码集合钉住（缺行即判红）。
 */
const CALL_REASON: Readonly<Record<string, string>> = {
  // ── 页面侧自查（open-path.ts）──────────────────────────────────────────
  unavailable: '这个功能需要安卓应用内打开（浏览器里不可用）',
  refused: '系统选择器拒绝了这次打开请求（可能是该目录不允许外部应用访问）',
  'empty-answer': '系统选择器没有返回结果（可能是系统组件异常）——请重试，或直接在文件管理器里打开',
  'bridge-error': '应用内调用出错——请重试；多次失败可复制日志反馈',

  // ── 壳侧：链路未装配（安装包不完整 / 非安卓宿主）────────────────────────
  'bridge not wired': '应用与页面的连接未接好（安装包不完整）——请重新安装应用',
  'no-shell-context': '应用上下文尚未就绪（刚启动或正在重启）——请稍后重试',
  'browser-host-not-wired': '内置浏览器组件未接好（安装包不完整）——请重新安装应用',
  'carrier-not-started': '设备控制服务尚未启动——请到「手机控制」开启无障碍服务后重试',
  'a11y-unavailable': '无障碍服务未开启——该操作需要「无障碍」通道；浏览器与虚拟屏操作不受影响',
  'unknown-op': '本版不认识这个浏览器操作——请更新应用后再试',

  // ── 壳侧：外链与服务拉起（ExternalLinks）──────────────────────────────
  'unknown-key': '这个链接没有在本版登记——请更新应用后再试',
  'insecure-url': '链接不是 https，出于安全已拒绝打开',
  'no-handler': '设备上没有能打开它的应用——请先安装浏览器或文件管理器',
  'not-installed': '还没安装 Shizuku——请先点「下载 Shizuku」',

  // ── 壳侧：AI root 权限授权门（RootGrant，issue #262 / 0.14.2-fx-2-root.1）──
  'consent-required': '先勾选「已阅读」并查看《AI root 权限免责声明》，才能开启 AI root 权限（免责声明内容更新后需重新确认）',
  'not-root-channel': 'Shizuku 通道不是 root 身份（无法在未 root 的设备上赋予该权限）——请让 Shizuku 以 root 启动后再来开启',
  'missing-asset': '应用内文档缺失（安装包不完整）——请重新安装应用后再试',
  'ui-thread-timeout': '应用界面正忙——请稍后重试；多次失败可复制日志反馈',
  // ★2026-09-30 主人指正：**多数 Root 管理器不再自动弹授权框**（除 Magisk 外得自己打开管理器授予）✗
  // ⇒ 这几条一律说「在你自己使用的 Root 管理器里允许本应用」，**不承诺"会弹窗"** ✓。
  'root-not-granted': '尚未获得 root 授权——请在你自己使用的 Root 管理器里允许本应用使用 root（多数管理器不会自动弹授权框）',
  'no-su': '本机没有可用的 su（未 root 或未安装 Root 管理器）——请先在你自己使用的 Root 管理器里完成 root 后再试',
  'already-requesting': '正在等待 root 授权结果——若管理器没有弹出授权框，请自己打开它允许本应用',
  // ── 壳侧：root 执行面与属主自愈（RootAccess / ShizukuTransport）────────────
  'request-started': '已开始检测 root 授权——若管理器没有弹出授权框，请自己打开它允许本应用；授权后本页会自动续开开关',
  requesting: '正在等待 root 授权结果——若管理器没有弹出授权框，请自己打开它允许本应用',
  'su-timeout': 'root 命令超时后被终止——请重试或缩短命令',
  'su-exec-failed': 'root 命令执行失败——请稍后重试；多次失败可复制日志反馈',
  'empty-command': '命令为空',
  'no-data-dir': '读不到应用数据目录（应用上下文异常）——请重启应用后再试',
  'bad-path': '路径无法解析',
  'out-of-app-data': '只允许修复应用数据目录内的文件',
  'repair-unsupported': '当前通道不支持属主修复（旧服务）——请到「手机控制」点「重置链接」重建通道后再试',
  'repair-item-failed': '有属主条目修复失败——请检查 root 授权后重试',
  'repair-incomplete': '属主修复未完成（仍有条目属主不对）——请重试',
  'repair-truncated': '目标目录达到遍历/深度上限，只处理了一部分——请查看诊断后再试',
  'repair-deadline': '属主维护达到时间预算，剩余结果未知——请查看诊断后再试',
  'repair-result-unknown': '特权工作结果不明，已暂停派发和维护；不要重复提交，仅重启应用不能证明结算。必要时重启设备',
  'root-maintenance-epoch-unavailable': '无法确认设备启动标识，本次不派发特权工作',
  'root-maintenance-lease-unavailable': '不能持久化特权执行租约，本次不派发',
  'repair-started': '已提交属主维护，尚未完成，结果会自动刷新',
  'repair-running': '属主维护已经在进行中，请等待原任务结算',
  'root-maintenance-busy': '属主维护占用特权通道，本次没有派发命令；请等待维护结算',
  'repair-configuration-required': '维护服务没有确认应用身份，请重置 Shizuku 连接',
  'shizuku-configuration-required': 'UserService 配置未确认，本次未派发；请重置连接',
  'shizuku-identity-failed': '无法确认执行服务的实际身份，本次拒绝派发',
  'repair-transport-incomplete': '维护进程或输出不完整，无法确认全部结果',
  'repair-helper-output-invalid': '维护 helper 没有返回完整有效结果，请复制诊断日志',
  'repair-native-failed': '原生维护异常，未能确认结果，请复制诊断日志',
  'invalid-app-data-anchor': '不能确认本应用可信数据目录，已拒绝维护',
  'invalid-repair-arguments': '属主维护参数非法，未开始变更',
  'installed-apk-unavailable': '无法读取已安装的签名应用代码，已拒绝维护',
  'requires-uid-0': '当前维护进程不是 root，未修改属主',
  'hardlink-protection-unavailable': '内核硬链接保护不可确认或已关闭，已拒绝维护',
  'invalid-app-uid': '完整应用 UID 不合法，已拒绝维护',
  'invalid-entry-cap': '属主维护条目上限非法，未开始变更',
  'invalid-deadline': '属主维护时间预算非法，未开始变更',
  'invalid-depth': '属主维护深度限制非法，未开始变更',
  'invalid-data-dir': '属主维护数据目录非法，未开始变更',
  'invalid-subpath': '属主维护相对路径非法，未开始变更',
  'subpath-depth-exceeded': '目标路径超过维护深度上限，已拒绝',
  'foreign-owner': '遇到非本应用且非 root 的属主，拒绝修改该条目',
  'shared-root-file': 'root 文件允许其它主体写入，无法确认安全归属，已拒绝修改',
  'unsafe-regular-link-count': '普通文件有不安全的硬链接数量，已拒绝修改',
  'cross-device-entry': '遇到其它挂载设备上的条目，已拒绝修改',
  'symlink-target': '目标是符号链接，已拒绝修改',
  'symlink-ancestor': '目标祖先是符号链接，已拒绝维护',
  'unsupported-node': '遇到特殊文件节点，未打开或修改',
  'directory-cycle': '遇到目录循环，已停止该分支',
  'inode-changed': '维护期间文件身份发生变化，已拒绝继续变更',
  'pinned-inode-changed': '已固定文件身份不一致，已拒绝继续变更',
  'chown-failed': '属主修改失败，未记为已修复',
  'ownership-verification-failed': '属主修改后验证失败，结果不能算完成',
  'repair-unexpected-failure': '属主维护发生未预期异常，请复制诊断日志',
  'anchor-open-failed': '无法打开可信应用数据锚点，未开始维护',
  'anchor-stat-failed': '无法确认应用数据锚点属性，未开始维护',
  'anchor-not-directory': '应用数据锚点不是目录，已拒绝维护',
  'ancestor-not-directory': '目标祖先不是目录，已拒绝维护',
  'listing-open-failed': '无法打开目录枚举，维护未完成',
  'listing-read-failed': '目录枚举失败，剩余条目未知',
  'invalid-child-name': '目录条目名非法，已拒绝访问',
  'pre-mutation-stat-failed': '变更前不能确认文件属性，未修改该条目',
  'verification-stat-failed': '变更后不能读取文件属性，结果未验证',
  'stat-failed': '无法读取文件属性，维护未完成',
  'open-failed': '无法打开维护条目，未修改该条目',
  'close-failed': '关闭维护描述符失败，请查看诊断日志',

  // ── 壳侧：内置浏览器加载（BrowserHost 的 load-error:<code>）────────────
  'load-error': '内置浏览器加载失败——请检查网址，或换用系统浏览器打开',
}

/** 未知码的兜底（**不带原码**；原码由调用方放进 `data-*`）。 */
export const UNKNOWN_CALL_REASON = '调用失败（原因未在本版登记）——请重试；多次失败可复制日志反馈'

/**
 * 壳侧/页面侧的失败原因 → 人话。
 *
 * `load-error:<code>` 这类**带前缀的复合码**按前缀归类（后缀是 WebView 的内部错误码，
 * 对用户无意义）；`reason` 里混进异常消息时也走兜底——绝不把异常措辞当文案。
 * @param reason - 壳侧回的原因码，或页面自查码。
 * @returns 用户可读的一句话（含下一步）。
 */
export function describeCallReason(reason: string | undefined): string {
  const code = (reason ?? '').trim()
  if (code === '') return UNKNOWN_CALL_REASON
  const table = CALL_REASON[code]
  if (table !== undefined) return table
  const prefix = code.split(':', 1)[0]
  const byPrefix = CALL_REASON[prefix]
  if (byPrefix !== undefined) return byPrefix
  return UNKNOWN_CALL_REASON
}

/**
 * HTTP 状态 → 人话（**状态码不上屏**）。
 *
 * 缺陷现场（审查档 §4.1）：界面上出现过「未获授权（HTTP 401）」「扫描失败（HTTP 500）」——
 * 用户拿不到任何可执行信息，只知道有个编号。这里按语义分档，`status` 仅留给调用方放进
 * `data-http` 与诊断日志。
 * @param action - 动作名（「读取来件状态」「清理运行时缓存」…），拼进句子。
 * @param status - HTTP 状态码（仅用于分档，不拼进返回串）。
 * @returns 用户可读的一句话（含下一步）。
 */
export function describeHttpFailure(action: string, status: number): string {
  if (status === 401 || status === 403) return action + '未获授权——请确认是在本机应用内操作；仍失败请重新打开应用'
  if (status === 404) return action + '的接口不存在（本版不匹配或应用安装包不完整）——请更新或重新安装应用'
  if (status === 405) return action + '不被允许——请更新应用到较新版本后再试'
  if (status >= 500) return action + '时应用内部出错——请稍后重试；仍失败可复制日志反馈'
  if (status >= 400) return action + '被应用拒绝——请稍后重试；仍失败可复制日志反馈'
  return action + '失败——请稍后重试'
}

/**
 * 通知设置写失败原因 → 人话（P3-1 + P3-6）。
 *
 * 壳侧 `NotifyCenter.applySetting` 回 `unknown-key` / `readback-mismatch` 两个码：
 * 前者是本版不认识该开关（不该发生），后者是写完读回与预期不一致（系统拦了写入）。
 * 旧实现把码与内部 key 一起上屏（「未生效（readback-mismatch）：cat.question」），
 * 这里改成「哪一类开关没生效 + 下一步」，key 不收进句子。
 */
export function describeNotifyWriteFailure(reason: string | undefined): string {
  const code = (reason ?? '').trim()
  if (code === 'readback-mismatch') return '系统没有接受这次改动（写入后读回不一致）——请重试；仍失败请到系统设置里直接修改通知权限'
  if (code === 'unknown-key') return '本版不认识这个开关（应用安装包与页面版本不匹配）——请更新或重新安装应用'
  return '开关未生效——请重试；仍失败可复制日志反馈'
}

/**
 * 通知渠道重要性（壳侧 `importance` 数字）→ 人话。
 *
 * 旧实现直接把数字印成「（重要性 4）」——数字档位对用户没有意义，用户要看的是
 * 「会不会响、会不会弹」。档位语义与 Android `NotificationManager.IMPORTANCE_*` 一一对应。
 * @param importance - 壳侧回的重要性整数。
 * @returns 「高（会弹出并响铃）」这类人话；非数字返回空串（调用方整段省略）。
 */
export function describeImportance(importance: unknown): string {
  if (typeof importance !== 'number' || !Number.isFinite(importance)) return ''
  if (importance >= 4) return '高（会弹到屏幕上并响铃）'
  if (importance === 3) return '默认（会响铃，不弹到屏幕上）'
  if (importance === 2) return '低（只在通知栏提示，不响铃）'
  if (importance === 1) return '极低（不响铃、不提示，仅在通知栏可见）'
  return '已关闭（系统不再显示该渠道的通知）'
}

/**
 * 硬截断唯一入口（P3-4）：超长一律附省略号。
 *
 * 缺陷现场：`take(24)` / `slice(0, 20)` 这类硬截断把 `rm -rf /data/loca` 呈现成一条**看起来
 * 完整**的命令——用户据此判断「AI 在跑什么」会得出错误结论。截断必须自己说出来。
 * @param text - 原文。
 * @param max - 允许的最大字符数（含省略号）。
 * @returns 未超长时原样；超长时 `max-1` 个字符 + `…`。
 */
export function truncateWithEllipsis(text: string, max: number): string {
  if (max <= 0) return ''
  if (text.length <= max) return text
  return text.slice(0, max - 1) + '…'
}

/**
 * 时长口径唯一真源（P3-3，页面侧）。
 *
 * 口径与壳侧 `UserCopy.durationText` **同规则**：`< 60s` 用「X秒」、`>= 60s` 用「X分Y秒」、
 * `>= 1h` 用「X小时Y分」；不出现 `8.4s` / `1m24s` 这类英文单位混排。
 * 未知（`<= 0`）返回空串，调用方**整段省略**，不打印 `-` 这类占位符。
 * @param ms - 毫秒数。
 * @returns 统一口径的时长文本。
 */
export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return ''
  const total = Math.round(ms / 1000)
  if (total < 60) {
    // 与壳侧同规则：整秒不带小数，否则保留一位（反馈读起来更有信息量）。
    if (ms % 1000 === 0) return total + '秒'
    return (ms / 1000).toFixed(1) + '秒'
  }
  const minutes = Math.floor(total / 60)
  if (minutes < 60) return minutes + '分' + String(total % 60).padStart(2, '0') + '秒'
  const hours = Math.floor(minutes / 60)
  return hours + '小时' + String(minutes % 60).padStart(2, '0') + '分'
}

/**
 * 一条用户可见回执：**人话正文 + 机器码**。
 *
 * 这是「码不上屏」这一条规则的载体：正文由本文件的翻译函数产出，码/状态码只经
 * [noticeDataAttrs] 落进 `data-*`。组件里有 `code`/`http` 时**不得**把它拼进 `text`。
 */
export interface Notice {
  /** 用户可读正文（含下一步）。 */
  text: string
  /** 壳侧/页面侧的失败原因码（诊断用；不进正文）。 */
  code?: string
  /** HTTP 状态码（诊断用；不进正文）。 */
  http?: number
}

/**
 * [Notice] → 可直接展开进 JSX 的 `data-*` 属性（空值不产生属性）。
 * @param notice - 回执。
 * @returns `{'data-code'?: string, 'data-http'?: string}`。
 */
export function noticeDataAttrs(notice: Notice): Record<string, string> {
  const attrs: Record<string, string> = {}
  if (notice.code !== undefined && notice.code !== '') attrs['data-code'] = notice.code
  if (notice.http !== undefined) attrs['data-http'] = String(notice.http)
  return attrs
}

