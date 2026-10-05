package com.ai.assistance.quro.core.cards

import org.json.JSONArray
import org.json.JSONObject

/**
 * **形状兜底推断**：AI 忘了写 `type` 时，从字段形状反推它想画什么。
 *
 * ## 🔴 这个问题是怎么被发现的
 *
 * 真机截图里出现了一屏这样的东西：
 * ```
 * {"label":"继续测试","command":"ai:继续下一类组件"}
 * {"label":"查看报告","command":"ai:查看汇总报告"}
 * {"label":"重新测试","command":"ai:重新测试"}
 * ```
 * 三行原始 JSON 躺在卡片框里。实测（`CardFence.toCards`）返回 **0 张卡** ——
 * 整组被静默丢弃，界面上「什么都没发生」，日志里也没有任何一行。
 *
 * 根因在 [CardSdk.parseObj]：
 * ```kotlin
 * val type = s.optString("type", "").trim().lowercase()
 * if (type.isEmpty()) return null
 * ```
 * 只要 `type` 缺失就返回 null。而模型写按钮时**极容易漏type** ——
 * `{"label":"继续测试","command":"ai:继续下一类组件"}` 人读起来完全合理，
 * 它就是 `actions` / `quickaction` 的一个数组项。
 *
 * ## 为什么不能无条件猜
 *
 * 「看到 JSON 就猜它是卡片」是错的：代码块里的 schema、配置片段、
 * 用户贴的报文，全是 JSON 无 `type` 的对象。无脑吞掉会把用户的代码
 * 变成一张莫名其妙的卡片 —— 比丢卡更糟，而且**用户无从察觉**（他看到的是自己的代码被改样了）。
 *
 * 所以本文件只认**高度特异**的形状：字段组合在正常业务 JSON 里几乎不会自然出现。
 * 判据是「有 type 键就不猜」（type 写错了走名册的归一化/兜底路径，不该由这里接管）、
 * 「必须命中至少一个该 type 独有的必填键」、
 * 「必须能构造出**非空**卡片」（空卡等于没修好，只是把「丢弃」换成「显示空白」）。
 *
 * 猜错的最坏代价是「多渲染一张不该有的卡」，所以宁可漏猜 —— 漏猜退化成原来的行为（丢卡），
 * 而原行为至少不会篡改用户内容。
 */
object CardShapeFallback {

    /** 推断结果：命中的 type + 归一化后的 spec。 */
    data class Guessed(val type: String, val spec: JSONObject)

    /**
     * 按字段形状猜type。返回 null = 不猜，让调用方走原有丢弃/兜底路径。
     *
     * [hintTitle] 是围栏属性里的 `title=`（组级标题）——数组项转卡片时用它当卡标题，
     * 因为数组项本身没有标题位置，否则推断出来的卡一律无标题，在一排卡里认不出是哪个。
     */
    fun guess(s: JSONObject, hintTitle: String = ""): Guessed? {
        // 有 type 一律不猜：type 写错（大小写/别名）是名册该处理的事，
        // 这里插手会把「已知 type 的拼写错误」降级成「无 type 的形状猜测」，反而丢信息。
        if (s.has("type")) return null
        val keys = s.keys()
        val present = HashSet<String>()
        while (keys.hasNext()) present.add(keys.next())

        // ── 1. action 项：label/text + command ──
        // 特异性来源：command 必须是**带协议前缀**的（ai:/reply:/copy:/open:/ui_/screen:）。
        // 光有 label+command 不够特异 —— 很多后端接口的报文就长这样。
        if (present.contains("command")) {
            val cmd = s.optString("command", "")
            if (hasKnownCommandPrefix(cmd)) {
                // 数组项形态：这一项是 actions 数组里的一项
                return wrap("actions", s, "actions", hintTitle)
            }
            return null
        }

        // ── 2. 单个按钮：label + variant/icon（没有 command 时按无动作按钮处理）──
        // 不猜：没有 command 的按钮点了没反应，是个「看起来能点其实点不动」的卡，
        // 比不显示更糟。留给名册的 quickaction（它有 icon 特征）。

        // ═══ 弱特异推断的分界线 ═══
        // 上面几条判据都很硬（protocol前缀 / selectedIndex / chips 键本身），
        // 而下面几条（level+text、time+title、label+value）在业务 JSON 里也常见。
        // 所以先在这里立一道结构键守卫：**带结构键的对象一律不走弱特异推断** ——
        // 有 items/chips/rows 这些键说明它已经是别的卡了，再猜就是张冠李戴。
        // （这条守卫早先只加在 stat 那一步，导致 `{"level":"info","text":"x","items":[…]}`
        //   被日志推断先抢走 —— 由「不该猜」侧的测试逼出来。）
        val hasStructural = present.any { it in STRUCTURAL_KEYS }

        // ── 3. 分段选择：options 数组 + selectedIndex ──
        // 特异性：selectedIndex 只有 segmented 用，通用 JSON 不会带它。
        if (s.has("options") && s.optJSONArray("options") != null && s.has("selectedIndex")) {
            return wrap("segmented", s, null, hintTitle)
        }

        // ── 4. 标签组：chips 数组 ──
        if (s.optJSONArray("chips") != null) {
            return wrap("chips", s, null, hintTitle)
        }

        // ── 5. 可选列表：items 数组 + selectable ──
        if (s.optJSONArray("items") != null && s.has("selectable")) {
            return wrap("list", s, null, hintTitle)
        }

        // ── 6. 日志/流式行：level + text ──
        // 特异性来源：`level` 的取值域被限死成日志语义（info/success/warning/error/debug…），
        // 而本 SDK 只有 stream/alert 用它；业务 JSON 里的 level 通常是数值（用户等级、
        // 权限级别），且多半不带 text。
        if (present.contains("text") && present.contains("level")) {
            if (hasStructural) return null
            val lv = s.opt("level")
            if (lv is String && lv.lowercase() in LOG_LEVELS) {
                return wrap("stream", s, "lines", hintTitle)
            }
            return null
        }

        // ── 7. 时间线事件项：time + title ──
        if (present.contains("time") && present.contains("title") && !hasStructural) {
            return wrap("timeline", s, "events", hintTitle)
        }

        // ── 8. 统计卡：label + value（无 icon/command）──
        // 特异性：value 必须是**字符串**（不是数字）。本 SDK 里 value:String 是
        // stat/alert/info 独有的约定，且「label + 字符串value」在业务报文里
        // 通常叫 text/content，不叫 value —— 撞名概率低但非零，所以要求
        // 同时没有 items/chips/options 这些更明确的结构键（见上面的 hasStructural 守卫）。
        if (s.has("value") && !hasStructural && s.opt("value") is String) {
            return wrap("stat", s, null, hintTitle)
        }

        return null
    }

    /**
     * 数组项 → 容器卡。
     *
     * 模型经常直接下发一个**数组**（`[{...},{...}]`）而忘了最外层的
     * `{"type":"actions","actions":[...]}` 包装 —— 这正是截图那一类。
     * 这里把每个元素各自推断后按 type 归组：同 type 合成一张卡（按钮组），
     * 不同 type 各出一张。这样「三个按钮」是一张按钮组，不是三张按钮。
     */
    fun guessArray(arr: JSONArray, hintTitle: String = ""): List<QuroChatCard> {
        val out = ArrayList<QuroChatCard>()
        // 保留插入顺序：同一 type 的项要相邻合并成一张卡
        val order = LinkedHashMap<String, MutableList<JSONObject>>()
        val singles = ArrayList<Pair<String, Guessed>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val g = guess(o, hintTitle) ?: continue
            if (g.type in GROUPABLE) {
                order.getOrPut(g.type) { ArrayList() }.add(o)
            } else {
                singles += g.type to g
            }
        }
        order.forEach { (type, objs) ->
            val merged = JSONObject().apply {
                put("type", type)
                if (hintTitle.isNotBlank()) put("title", hintTitle)
                val key = containerKey(type)
                // stream 的容器是**字符串**数组（StreamCard 只有 lines: List<String>），
                // 而推断出来的项是对象 —— 不转的话 strArr 逐项 optString 拿到 "{}"，
                // 于是渲染出一串 "{" —— 比丢卡更像坏了。
                if (type == "stream") {
                    put(key, JSONArray().also { a -> objs.forEach { a.put(it.asStreamLine()) } })
                } else {
                    put(key, JSONArray().also { a -> objs.forEach { a.put(it) } })
                }
            }
            CardSdk.parseObj(merged)?.let { out += it }
        }
        singles.forEach { (_, g) -> CardSdk.parseObj(g.spec)?.let { out += it } }
        return out
    }

    /**
     * `{"level":"error","text":"失败"}` → `[error] 失败`。
     *
     * 保留 level 是有意的：截图里三行分别 info/success/warning，
     * 丢掉 level 三行就长得一模一样，用户失去了唯一的区分线索。
     */
    private fun JSONObject.asStreamLine(): String {
        val t = optString("text", "").ifBlank { optString("message", "") }
        val lv = optString("level", "").ifBlank { optString("status", "") }
        return if (lv.isBlank()) t else "[$lv] $t"
    }

    /**
     * 可合并成一张容器卡的 type → 容器数组键名。
     *
     * 收录标准：**同类多项合成一张卡**才是对的。
     * 反例（不收）：`list` / `chips` 这类本身就是「一张卡含若干项」，
     * 收进来会把三个 stat 之外的东西错并成一张。
     */
    private val GROUPABLE: Map<String, String> by lazy {
        mapOf(
            "actions" to "actions",
            "quickaction" to "actions",
            "badge" to "badges",
            "tagcloud" to "tags",
            "avatargroup" to "avatars",
            "breadcrumb" to "crumbs",
            // 日志行 / 时间线事件：模型常整段下发同形状的项，各合一张卡才对
            "stream" to "lines",
            "timeline" to "events",
        )
    }

    private fun containerKey(type: String): String = GROUPABLE[type] ?: "actions"

    /**
     * 已知 action 协议前缀（取自 [CardAction]）。
     *
     * `ui_` 是唯一不带冒号的一族（`ui_toggle_notify` 这种），
     * 所以比对其余时用 startsWith。
     *
     * 🔴 这里曾经写成 `cmd.takeWhile { it == ':' }` —— 那是错的：
     * 首字符不是冒号时立即停，`"ai:继续"` 取出的是 `"a"`，永远匹配不上，
     * 于是**所有 action 推断全静默失效**（表现为「改了没效果，测试也只是那几个红」）。
     * 教训：涉及协议前缀的提取，先拿一个真实样本手算一遍再写。
     */
    private val COMMAND_PREFIX: Set<String> by lazy {
        setOf("ai:", "reply:", "copy:", "open:", "screen:", "callback:", "tool:", "tool_call:")
    }

    /** command 是否带已知协议前缀。 */
    private fun hasKnownCommandPrefix(cmd: String): Boolean {
        if (cmd.isBlank()) return false
        if (cmd.startsWith("ui_")) return true
        return COMMAND_PREFIX.any { cmd.startsWith(it) }
    }

    /** 一旦出现这些键，说明这是有结构的卡而不是标量卡，别走 stat 猜测。 */
    private val STRUCTURAL_KEYS = setOf(
        "items", "chips", "options", "actions", "nodes", "edges", "children",
        "rows", "headers", "sections", "lines", "series", "points", "segments", "axes", "values",
    )

    /**
     * 认作日志行的 level 取值域。
     *
     * 限死取值域是这条推断的**全部特异性来源**：`level` 这个键在业务 JSON 里
     * 太常见（用户等级、权限级别、关卡…），但那些几乎都是**数值**；
     * 取值是这些**字符串**且同时带 `text` 的，基本就是日志。
     */
    private val LOG_LEVELS: Set<String> by lazy {
        setOf("info", "success", "warning", "warn", "error", "debug", "trace", "fatal", "tip", "note")
    }

    /**
     * 把猜出的 type 与原spec 合成一份可解析的 spec。
     *
     * [wrapKey] 非空表示「这个对象本身是容器数组里的一项」，需要先包一层。
     * [wrapKey] 为 null 表示对象本身就是完整卡 spec，补个 type 即可。
     */
    private fun wrap(type: String, s: JSONObject, wrapKey: String?, hintTitle: String): Guessed {
        val spec = if (wrapKey == null) {
            JSONObject(s.toString()).apply { put("type", type) }
        } else {
            JSONObject().apply {
                put("type", type)
                if (hintTitle.isNotBlank()) put("title", hintTitle)
                put(containerKey(type), JSONArray().put(s))
            }
        }
        if (spec.optString("title", "").isBlank() && hintTitle.isNotBlank()) spec.put("title", hintTitle)
        return Guessed(type, spec)
    }

    // ═══════════════════════ 已知但未收录的 type ═══════════════════════

    /**
     * **有 `type`，但名册里没收录** —— 与 [guess] 互补的第二条兜底路径。
     *
     * ## 真机故障
     *
     * `{"type":"md","id":"fc_1","name":"测试报告.md","size":"2.4 KB","path":"\workspace\test-report.md"}`
     * 渲染成「⚠ 未识别组件：md」+ 一坨原始 JSON。模型其实**画对了**：
     * name/size/path 三件套就是 [CardSdk] 里 `filecard` 的完整字段，
     * 只是把「markdown 文件」这个后缀当成了 type。
     *
     * 这类失效比「漏 type」隐蔽：卡片**有**输出、还带着⚠ 图标，
     * 用户以为是自己没见过的组件，实际上是我们少接了一个别名。
     *
     * ## 为什么不并进 [guess]
     *
     * [guess] 的第一条铁律是「有 type 一律不猜」—— 因为 type 拼错属于**名册归一化**
     * 该管的事，插手会把已知信息降级掉。而这里不同：type 存在且**根本不在名册里**，
     * [CardSdk.parseObj] 已经准备落 [CardSdk.fallbackCard] 了，也就是说**再没有别的兜底**。
     * 此时按形状认一次是纯赚的：认错了也只是回到原来的「未识别组件」，
     * 不会把用户代码改样（那是无 type 乱猜的风险，这儿不存在）。
     *
     * 判据仍然保守 —— 只认文件卡这一个高特异形状：
     *  - `name` + `path` **同时**存在（业务 JSON 里这对组合几乎只出现在文件描述上）；
     *  - 或 type 本身就是文件后缀（`md`/`markdown`/`txt`…）**且**带 name 或 path。
     */
    fun resolveUnknown(s: JSONObject): Guessed? {
        val type = s.optString("type", "").trim().lowercase()
        if (type.isEmpty()) return null
        // 名册里有的 type 不归这里管 —— 那是 lint / 归一化该报的账
        if (type in CardSdk.types) return null

        val hasName = s.optString("name", "").isNotBlank()
        val hasPath = s.optString("path", "").isNotBlank()
        val hasSize = s.optString("size", "").isNotBlank()

        // ① 模型把文件扩展名当 type：md / markdown / txt / log …
        if (type in FILE_SUFFIX_TYPES && (hasName || hasPath)) {
            return wrap("filecard", s, null, "")
        }
        // ② 文件卡形状：name + path 齐备（size 只是加强信号，不强求）
        if (hasName && hasPath && hasSize) {
            return wrap("filecard", s, null, "")
        }
        return null
    }

    /**
     * 模型常拿来当 type 的文件扩展名 / 简称。
     *
     * 收录标准：**在名册里不存在同名 type**（否则会抢走真卡的解析），
     * 且语义上一定是「一个文件」。加了新卡若与这里撞名，
     * [lintUnknownTargets] 会报出来。
     */
    private val FILE_SUFFIX_TYPES: Set<String> by lazy {
        setOf(
            "md", "markdown", "mdown", "mkd", "txt", "text_file", "log",
            "csv", "json_file", "yaml", "yml", "pdf_file", "doc", "docx",
            "xls", "xlsx", "ppt", "pptx", "zip", "apk",
        )
    }

    /**
     * 自检：兜底目标的 type 必须真在名册里，否则「兜底本身也成了兜底」——
     * 那时候这里配得再对也只会渲染出「未识别组件」，且没有任何报错。
     * 由 [CardSdk.lint] 调用。
     */
    fun lintUnknownTargets(): List<String> {
        val bad = ArrayList<String>()
        FILE_SUFFIX_TYPES.forEach { t ->
            if (t in CardSdk.types) bad += "后缀兜底 $t 与名册 type 撞名（真卡会被抢）"
        }
        return bad
    }
}
