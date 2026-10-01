package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.core.agent.loop.FailureType

/**
 * 工具失败的**回喂分类**（N4 / 缺口 C4）。
 *
 * ## 为什么不能直接用 [FailureType]
 *
 * [FailureType] 是**闭环自动恢复决策**的词汇 —— 它存在的唯一目的是回答
 * 「接下来自动重试 / 修正 / 回滚 / 升级给谁」。而本枚举要回答的是另一个问题：
 * **「把这条失败喂回模型时，该给模型什么指令」**。
 *
 * 两者会有重合，但**不是同一件事**，证据是这里多出三个 [FailureType] 里没有的种类，
 * 且它们在闭环里根本不需要区分：
 * - [NOT_FOUND]：闭环只会当成 BUSINESS 去重试（因为重试决策与「路径写错了」无关），
 *   但回喂给模型时必须说清「**别用同一参数重试**，先去确认路径」。
 * - [ENV_MISSING]：闭环里同属 BUSINESS，但回喂时必须说「**重试无效**，改用别的工具」。
 * - [INVALID_ARGS] vs [FailureType.PARSE]：闭环视角叫「解析失败」，回喂视角要更直白 ——
 *   「参数不合法，去对照 Schema 改」。
 *
 * 为防止两套词汇**静默漂移**，[kindOf] 对 [FailureType] 做**穷尽 `when` 映射**
 * （不写 `else`）：今后任何人给 [FailureType] 加一种类型，编译即失败，强制同步。
 * 该不变量由 `QuroToolFeedbackTest` 直接断言。
 *
 * ## 为什么必须有「下一步」而不只是「原因」
 *
 * 改造前失败回喂给模型的只有一行原始错误文本（`工具执行异常：xxx`）。模型看到它之后
 * 最常见的反应是**原样重试**，这正是 `QuroAssistant` 里「连续重复失败」检测
 * （`repeatStreak >= 10` 强制停止）被频繁触发的原因 —— 那些停止不是模型太笨，
 * 而是它从未被告知「这类错误重试没有用」。
 *
 * 所以每一类都必须显式回答：**重试有用吗？没用的话该改做什么？**
 */
enum class ToolFailureKind(
    /** 原样重试是否有意义。注意 [INVALID_ARGS] 为 true ——「修正参数后」重试有意义。 */
    val retryable: Boolean,
) {
    /** 参数不合法（畸形 JSON / 字段名错 / 必填缺失 / 类型不符）。 */
    INVALID_ARGS(true),

    /** 目标不存在（文件、目录、App、ID、URL 资源）。 */
    NOT_FOUND(false),

    /** 权限不足。**重试与改参数都无法解决**，必须用户介入。 */
    PERMISSION(false),

    /** 执行超时。缩小范围后重试有效。 */
    TIMEOUT(true),

    /** 网络/传输故障。临时性，可原样重试一次。 */
    TRANSPORT(true),

    /** 环境不具备该能力（工具未安装/未配置、依赖缺失、设备不支持）。 */
    ENV_MISSING(false),

    /** 调用被取消（用户停止 / 切会话）。自动重试会违背用户意图。 */
    CANCELLED(false),

    /** 工具跑完了但业务失败。需读懂原因后调整，不鼓励原样重试。 */
    BUSINESS(false),

    /** 无法归类。允许最多重试一次，之后必须停手并说明。 */
    UNKNOWN(true),
}

/**
 * 工具失败回喂器：把「原始错误文本」变成**自包含、可执行**的失败块。
 *
 * 输出形状（刻意做得像结构化日志，便于模型稳定解析，也便于人在 Logcat / 工具卡片里定位）：
 * ```
 * [工具失败] tool=read_file kind=NOT_FOUND retryable=false
 * 原因：No such file or directory: /sdcard/a.txt
 * 下一步：目标不存在。不要用同一参数重试：请先用列目录/搜索类工具确认…
 * ```
 *
 * 【铁律】只对**失败**调用。工具正常返回的文本里可能恰好含 "not found"
 * （例如搜索类工具的正常输出「no matches found」），若对成功结果也跑分类，
 * 会把成功伪装成失败 —— 那正是本仓反复出现过的「假实现」病灶。
 */
object QuroToolFeedback {

    /** 失败块的起始标记。同时用于**幂等判定**：已带标记的文本不再二次包装。 */
    const val MARKER = "[工具失败]"

    /** 回喂文本的硬上限：失败信息不该挤占模型的目标上下文（context rot，缺口 C6）。 */
    private const val MAX_REASON_CHARS = 600

    /**
     * 从 [FailureType] 映射到回喂种类。
     *
     * **穷尽 `when`、故意不写 `else`** —— [FailureType] 新增枚举值时本函数编译失败，
     * 逼迫作者回来决定「这种新失败该怎么告诉模型」。这是防止两套词汇漂移的唯一机制。
     */
    fun kindOf(type: FailureType): ToolFailureKind = when (type) {
        FailureType.PERMISSION -> ToolFailureKind.PERMISSION
        FailureType.TIMEOUT -> ToolFailureKind.TIMEOUT
        FailureType.TRANSPORT -> ToolFailureKind.TRANSPORT
        FailureType.PARSE -> ToolFailureKind.INVALID_ARGS
        FailureType.BUSINESS -> ToolFailureKind.BUSINESS
        FailureType.UNKNOWN -> ToolFailureKind.UNKNOWN
    }

    /**
     * 关键词分类（用于**只有裸文本**的失败：工具自己 `QuroToolResult.Error("...")`、
     * `QuroAssistant` 的 `catch` 兜底等）。
     *
     * 顺序即优先级，**从具体到宽泛**，最后 [BUSINESS] 兜绝大多数「失败」字样，
     * 再不行才是 [UNKNOWN]。顺序敏感的原因：
     * - 「required parameter 'path' not found」同时命中 INVALID_ARGS 与 NOT_FOUND，
     *   但语义是参数问题（INVALID_ARGS 在前 → 判对）。
     * - 「permission denied: no such file」同时命中 PERMISSION 与 NOT_FOUND，
     *   但真正的堵点是权限（PERMISSION 在前 → 判对）。
     * - 若把 BUSINESS（「失败」）放前面，它会吞掉上面全部具体分类。
     */
    fun kindOfText(raw: String?): ToolFailureKind {
        val s = raw?.lowercase() ?: return ToolFailureKind.UNKNOWN
        if (s.isBlank()) return ToolFailureKind.UNKNOWN
        for ((kind, keys) in KEYWORD_TABLE) {
            if (keys.any { it in s }) return kind
        }
        return ToolFailureKind.UNKNOWN
    }

    /** 一条失败是否已被包装过（幂等判定）。 */
    fun isWrapped(text: String?): Boolean = text?.trimStart()?.startsWith(MARKER) == true

    /** 该类失败原样重试是否有意义。 */
    fun retryableOf(kind: ToolFailureKind): Boolean = kind.retryable

    /** 给模型的「下一步」指令。与 [kindOfText] 分离，便于单测逐类断言。 */
    fun directiveOf(kind: ToolFailureKind): String = when (kind) {
        ToolFailureKind.INVALID_ARGS ->
            "参数不合法，原样重试没有意义。请对照该工具的 JSON Schema 修正字段名/类型/必填项后" +
                "重新发起调用；若不确定参数格式，先用只读工具确认输入（如列出目录、读取文件内容）。"

        ToolFailureKind.NOT_FOUND ->
            "目标不存在，不要用同一参数重试。请先用列目录/搜索类工具确认真实的路径、文件名或 ID，" +
                "再按确认到的值发起调用；若确认目标确实不存在，请直接告知用户，不要反复试探。"

        ToolFailureKind.PERMISSION ->
            "权限不足 —— 重试和改参数都无法解决。请直接向用户说明需要授予哪个权限" +
                "（或到系统设置中开启），并给出在现有权限下可行的替代方案；不要继续重试同一调用。"

        ToolFailureKind.TIMEOUT ->
            "执行超时。请缩小任务范围后重试（更小的路径、更少的条目、分页分批），不要原样重试；" +
                "若反复超时，改用更轻量的替代工具，或向用户说明该任务当前无法一次完成。"

        ToolFailureKind.TRANSPORT ->
            "网络/传输故障，通常是临时性的：可以原样重试一次；仍然失败则说明网络受限，" +
                "请改用本地能力完成，或告知用户网络不可用。"

        ToolFailureKind.ENV_MISSING ->
            "当前环境不具备该能力（工具未安装/未配置、依赖缺失或设备不支持），重试无效。" +
                "请改用其它可用工具完成同一目标；若没有替代方案，直接向用户说明为何无法完成。"

        ToolFailureKind.CANCELLED ->
            "调用被用户取消。不要自动重试 —— 用户可能已改变意图。" +
                "若该步骤对任务仍然必要，先询问用户是否继续，得到确认后再发起调用。"

        ToolFailureKind.BUSINESS ->
            "工具执行了但业务失败。请先读懂上面的原因，判断是参数问题还是前提条件不满足；" +
                "不要原样重试，应调整参数、改用其它工具，或向用户说明失败原因。"

        ToolFailureKind.UNKNOWN ->
            "未能归类的失败。最多再原样重试一次；若仍失败，请停止重试，" +
                "向用户说明失败原因以及你已经尝试过的操作。"
    }

    /**
     * 组装回喂文本。
     *
     * @param toolName 工具名；未知时传空串（会省略 `tool=` 段而不是拼出 `tool=null`）。
     * @param raw 原始错误文本（会被截断到 [MAX_REASON_CHARS]）。
     * @param known 已由更可靠的来源（[FailureType]）确定的种类；传 null 则走文本分类。
     */
    fun compose(
        toolName: String,
        raw: String?,
        known: ToolFailureKind? = null,
    ): String {
        // 幂等：多层兜底（引擎 → 循环 → 子智能体）可能重复包装同一条失败。
        if (isWrapped(raw)) return raw.orEmpty()
        // 🔑 分类必须基于**原始文本**，不能基于下面的兜底占位文案 ——
        //    占位文案「未提供失败原因」本身含「失败」二字，会把自己判成 BUSINESS，
        //    把一个「什么都不该假设」的场景伪装成「业务失败」。
        val kind = known ?: kindOfText(raw)
        val reason = (raw ?: "").trim().ifBlank { "未提供失败原因" }
        val head = buildString {
            append(MARKER)
            if (toolName.isNotBlank()) append(" tool=").append(toolName)
            append(" kind=").append(kind.name)
            append(" retryable=").append(kind.retryable)
        }
        val clipped = if (reason.length <= MAX_REASON_CHARS) reason
        else reason.take(MAX_REASON_CHARS) + "…（原因过长已截断）"
        return buildString {
            append(head).append('\n')
            append("原因：").append(clipped).append('\n')
            append("下一步：").append(directiveOf(kind))
        }
    }

    /**
     * 关键词表。顺序 = 判定优先级，**从具体到宽泛**。
     *
     * 同时覆盖中英两套：工具报错文案来自 Android 系统（英文，如 `Permission denied`）、
     * Kotlin 异常（英文）、以及本仓工具自己的中文文案。只做小写匹配，故英文关键词一律小写。
     */
    private val KEYWORD_TABLE: List<Pair<ToolFailureKind, List<String>>> = listOf(
        // 1. 取消：必须在最前。取消信息里常带其它词（"cancelled: connection closed"），
        //    若先判 TRANSPORT 会得出「可重试」的错误结论，而取消**绝不能**自动重试。
        ToolFailureKind.CANCELLED to listOf(
            "cancel", "interrupted", "aborted", "已取消", "被取消", "取消", "已中止", "中断",
        ),
        // 2. 超时：也要早于 TRANSPORT（"connect timeout" 同时含 connection）。
        ToolFailureKind.TIMEOUT to listOf(
            "timeout", "timed out", "etimedout", "deadline exceeded", "took too long",
            "超时", "已自动终止",
        ),
        // 3. 权限：早于 NOT_FOUND（"permission denied: no such file" 的堵点是权限）。
        ToolFailureKind.PERMISSION to listOf(
            "permission denied", "eacces", "eperm", "not permitted", "securityexception",
            "security exception", "forbidden", "unauthorized", "missing permission",
            "权限", "拒绝访问", "未授权", "无权限", "没权限",
        ),
        // 4. 参数：早于 NOT_FOUND（"required parameter 'path' not found" 是参数问题）。
        //    "jsonexception"/"unterminated" 专门抓 `org.json` 的解析异常（畸形 arguments 的典型症状）；
        //    刻意不用宽泛的 "json" —— 「JSON 文件不存在」属于 NOT_FOUND，不该被误判成参数问题。
        //    "不能为空"/"为空" 覆盖本仓工具最常见的中文必填校验文案
        //    （如「导入文件路径不能为空」「模型ID不能为空」）。
        ToolFailureKind.INVALID_ARGS to listOf(
            "invalid", "malformed", "not valid json", "jsonexception", "unterminated",
            "unexpected token", "unexpected end of json",
            "missing required", "required parameter", "is required", "type mismatch",
            "cannot deserialize", "illegal argument", "schema", "参数", "必填", "缺少",
            "不能为空", "为空", "格式错误", "解析失败", "不合法",
        ),
        // 5. 环境缺失：早于 NOT_FOUND（"tool not installed" 不是「资源不存在」）。
        //    "未知工具" 是 QuroToolEngine 的真实文案（`未知工具: <name>`），
        //    必须中英都覆盖 —— 否则「模型调了一个不存在的工具」会落到 UNKNOWN，
        //    而 UNKNOWN 允许重试一次，模型会对着一个永远不存在的工具再撞一次。
        ToolFailureKind.ENV_MISSING to listOf(
            "not available", "unsupported", "not supported", "unknown tool", "no such tool",
            "not installed", "unavailable", "not configured", "missing dependency",
            "未安装", "未配置", "不支持", "不可用", "无法在当前环境", "环境", "设备不支持",
            "缺少依赖", "未知工具", "工具不存在",
        ),
        // 6. 目标不存在。
        ToolFailureKind.NOT_FOUND to listOf(
            "no such file", "not found", "does not exist", "no such app", "unable to find",
            "cannot find", "missing file", "不存在", "找不到", "未找到", "没有找到", "无此",
        ),
        // 7. 传输：在 NOT_FOUND 之后（"404 not found" 属于资源不存在，重试无用）。
        ToolFailureKind.TRANSPORT to listOf(
            "connection", "network", "socket", "unreachable", "handshake", "broken pipe",
            "connect failed", "no address associated", "网络", "连接失败", "断流", "无法连接",
        ),
        // 8. 业务失败：最宽泛的「失败」字样放最后，否则会吞掉上面所有具体分类。
        ToolFailureKind.BUSINESS to listOf(
            "failed", "failure", "error", "unsuccessful", "失败", "异常",
        ),
    )

    /** 仅供测试：关键词表按顺序暴露，用于断言「顺序即优先级」这一不变量。 */
    internal fun keywordTableForTest(): List<Pair<ToolFailureKind, List<String>>> = KEYWORD_TABLE
}
