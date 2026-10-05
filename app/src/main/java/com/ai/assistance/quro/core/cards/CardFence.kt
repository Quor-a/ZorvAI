package com.ai.assistance.quro.core.cards

import org.json.JSONArray
import org.json.JSONObject

/**
 * 卡片围栏协议（v1400 起）—— AI 在**正文里**直接下发可视化组件的第二条通道。
 *
 * ## 为什么要有这条通道
 *
 * 原来卡片只有一条下发路径：模型必须精确构造 `ui_widget` 工具调用。
 * 这有两个硬伤：
 *  1. function call 的参数是结构化的，**错一个键名整张卡直接报废**，而模型在
 *     参数量大时错键很常见；
 *  2. 模型没法「边说边画」——工具调用是离散的，正文里出现卡片时只会变成
 *     一坨 JSON 文本被 Markwon 当代码块渲染（这正是 GenUI 侧查出的同类故障）。
 *
 * 围栏通道让模型可以像写代码块一样自然地画卡片：
 * ```
 * 这是我的分析：
 * ```card
 * {"type":"stat","label":"用户","value":"1.2k","trend":"up"}
 * ```
 * 还需要看明细吗？
 * ```
 *
 * ## 四种围栏头
 *
 * - [FENCE_CARD]  `card`   —— 单组件，一个 JSON 对象
 * - [FENCE_CARDS] `cards`  —— 多组件，JSON 数组，或 `{"layout":..,"children":[..]}`
 * - [FENCE_CARDUI] `cardui` —— A2UI 风格邻接表（扁平 `id` + `children` 引用）
 * - [FENCE_CARDJSON] `cardjson` —— 逐行 JSON（一行一个卡片，流式友好）
 *
 * ## 围栏属性
 *
 * 围栏头之后可跟属性（空格分隔），分**开关**与**带值**两类：
 *
 * | 属性 | 类型 | 含义 |
 * |---|---|---|
 * | `compact` | 开关 | 内边距收紧（14dp → 10dp）。一组小卡片并排时必给，否则散成一堆 |
 * | `scroll` | 开关 | 内容超高时卡片内部滚动，而非把气泡撑长 |
 * | `bordered` / `flat` / `dense` | 开关 | 预留，渲染层当前按默认处理 |
 * | `title=` | 带值 | **组级标题**。各卡自己没标题时用它兜底，省 token 也免得三个标题 |
 * | `theme=` | 带值 | 主题档位：`accent`(默认) / `warn` / `danger` / `plain` |
 *
 * 例：
 * ```
 * ```cards title=Q3 复盘 theme=accent compact
 * [{"type":"stat","label":"营收","value":"1.2M","trend":"up"}]
 * ```
 * ```
 *
 * 带值属性的键必须是 [KNOWN_VALUE_ATTRS] 里的，`theme` 的值还必须在 [THEME_PRESETS] 内，
 * 否则**原样丢弃并降级为默认**（主题值则降级为 `accent`）——
 * 宁可主题不生效，也不要让模型写 `theme=rainbow` 时得到一张不明不白的卡。
 *
 * 属性的动因之一是 A2UI v0.9 把 `theme` 更名为 `surfaceProperties` 并提到协议级一等公民：
 * 「卡片长什么样」应该是可枚举的少数几档，而不是模型每次现编的颜色值。
 *
 *
 * ## 🔴 未闭合围栏必须能解析
 *
 * 流式输出时，正文会**先出现起始 ``` 再逐帧补内容**，围栏闭合在几十帧之后。
 * 若解析器要求结尾也有 ```，那么流式过程中每���帧都会被判成"普通代码块"，
 * 界面会闪一下代码块源码再跳回卡片 —— 用户在 GenUI 侧已经吃过这个亏。
 * 所以 [parse] 把 `closed=false` 也当作合法结果返回，由渲染层决定
 * 是先渲染骨架还是先压住不显示。
 *
 * 本文件是**纯逻辑、无 Android 依赖**，可 JVM 单测。
 */
object CardFence {

    /** 单组件围栏头。 */
    const val FENCE_CARD = "card"

    /** 多组件围栏头。 */
    const val FENCE_CARDS = "cards"

    /** A2UI 风格扁平邻接表围栏头。 */
    const val FENCE_CARDUI = "cardui"

    /**
     * 逐行 JSON 围栏头：**一行一个独立卡片 JSON**。
     *
     * ## 为什么再加一种围栏
     *
     * [FENCE_CARD] / [FENCE_CARDS] 的围栏体是一整段 JSON，流式时必须等整段收全才能解析，
     * 而模型实际经常是一行一个卡片地陆续吐 —— 这段时间用户只能看到代码块。
     * 逐行约定让每行独立解析：已完整的行立刻出卡片，末尾没收全的那行等闭合后自然补上，
     * 且某一行脏数据不会连累其余各行（整段 JSON 坏一处就整卡全废）。
     */
    const val FENCE_CARDJSON = "cardjson"

    /** 全部受支持的围栏头。顺序即匹配优先级。 */
    val ALL_FENCES: List<String> = listOf(FENCE_CARD, FENCE_CARDS, FENCE_CARDUI, FENCE_CARDJSON)

    /**
     * 围栏起始行正则。
     *
     * - `card` 必须排在 `cards` 前面匹配吗？**不需要**：正则的 `\b` 已保证
     *   `card` 不会吃掉 `cards` 的前缀（`cards` 里的 `card` 后面跟的是 `s`，
     *   不是词边界），反之 `cards` 也不会误配裸 `card`（`card` 后面必须是空白或行尾）。
     * - 属性部分用 `([^\n`]*)` 而非 `(.*?)`：属性里不该出现反引号，
     *   出现说明围栏已经闭合了，那就是另一个块了。
     */
    private val OPEN_RE = Regex(
        "^[ \\t]*```+[ \\t]*(" + ALL_FENCES.joinToString("|") + ")\\b([^\\n`]*)$",
        RegexOption.MULTILINE
    )

    /** 单独一行（可带缩进）的闭合围栏 —— 任意语言都算闭合标记。 */
    private val CLOSE_RE = Regex("^[ \\t]*```+[ \\t]*$")

    /**
     * 组合卡最大嵌套深度。
     *
     * 模型偶尔会把 `children` 自引用（自己指向自己），没有上限就是递归到 StackOverflow，
     * 而崩溃发生在渲染线程上 —— 整条消息都跟着没了。
     */
    const val MAX_COMPOSITE_DEPTH = 6

    /** 单张卡允许的节点总数（含所有层）。深度管得住"套娃"，总量管得住"铺满屏"。 */
    const val MAX_TOTAL_NODES = 64

    /** 围栏属性里能识别的开关（无值）。未识别的属性一律保留在 [CardFenceSlice.attrs] 里但不生效。 */
    private val KNOWN_ATTRS = setOf("compact", "scroll", "bordered", "flat", "dense")

    /**
     * 围栏属性里能识别的**带值**属性（`name=value` 或 `name:"value"`）。
     *
     * ## 为什么要有带值属性
     *
     * 早期围栏属性全是开关（`card compact scroll`），能表达的东西只有「要不要」，
     * 表达不了「是什么」。而卡片最需要的两类信息恰好都是「是什么」：
     *  - `title=`：**一组卡的共同标题**。围栏体里每张卡各写一个 title 既费 token，
     *    又会出现「三个标题」这种视觉噪音；写在围栏头上就是一个标题管一组。
     *  - `theme=`：配色（`accent` / `warn` / `danger` / `plain`）。
     *    A2UI v0.9 把 `theme` 更名为 `surfaceProperties` 并把它提到协议级一等公民，
     *    就是因为「卡片长什么样」不该由模型每次现编，而应是**可枚举的少数几档**。
     *
     * 所以取值一律走 [THEME_PRESETS] / `KNOWN_SWITCHES` 白名单，不认识的原样丢弃 ——
     * 宁可主题不生效，也不要让模型写 `theme=rainbow` 时得到一张不明不白的卡。
     */
    private val KNOWN_VALUE_ATTRS = setOf("title", "theme")

    /** 可选主题档位（对应渲染层的语义色）。 */
    val THEME_PRESETS: List<String> = listOf("accent", "warn", "danger", "plain")

    /**
     * 解析**带值**围栏属性：`title=xxx` / `theme:xxx` 两种写法都吃。
     *
     * 两种写法都收是因为模型会照着样例里的 JSON 习惯写冒号。
     * 返回键统一小写；值保留原样（主题值另在 [theme] 里归一）。
     */
    fun parseValueAttrs(attrText: String): Map<String, String> {
        if (attrText.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (tok in attrText.split(Regex("[\\s,]+"))) {
            val t = tok.trim()
            if (t.isEmpty()) continue
            val eq = t.indexOf('=')
            val colon = t.indexOf(':')
            val cut = when {
                eq >= 0 && (colon < 0 || eq < colon) -> eq
                colon >= 0 -> colon
                else -> -1
            }
            if (cut <= 0) continue
            val k = t.substring(0, cut).trim().lowercase()
            if (k !in KNOWN_VALUE_ATTRS) continue
            // 属性值可能带引号（模型常写 theme="danger"），去掉包裹引号
            var v = t.substring(cut + 1).trim().trim('"', '\'')
            if (v.isEmpty()) continue
            out[k] = v
        }
        return out
    }

    /**
     * 一段围栏切片。
     *
     * @param fence       围栏头（card / cards / cardui）
     * @param body        围栏内文本（原样，未 trim —— 交给解析层去容忍空白）
     * @param attrs       围栏属性键集合（已小写）
     * @param start       正文中的起始偏移（含）
     * @param end         正文中的结束偏移（不含，指向围栏末尾之后）
     * @param closed      围栏是否已闭合。`false` = 流式中间态
     * @param raw         原始切片文本（含围栏头与围栏身），供"显示源码"降级
     */
    data class Slice(
        val fence: String,
        val body: String,
        val attrs: Set<String>,
        val start: Int,
        val end: Int,
        val closed: Boolean,
        val raw: String,
        /** 带值属性（`title=` / `theme=`），由 [parseValueAttrs] 从围栏头解析。 */
        val valueAttrs: Map<String, String> = emptyMap(),
    ) {
        /** 是否为多组件围栏。 */
        val isMulti: Boolean get() = fence == FENCE_CARDS || fence == FENCE_CARDUI

        /**
         * 逐行卡片围栏：调用方应**边收边渲染**，不要等 [closed] 为 true。
         *
         * 未闭合不等于不可用 —— 这正是它存在的理由。
         */
        val isStreaming: Boolean get() = fence == FENCE_CARDJSON

        /** 紧凑模式：卡片内边距收紧。 */
        val compact: Boolean get() = "compact" in attrs

        /** 可滚动：内容超高时卡片内部滚动而非把气泡撑长。 */
        val scroll: Boolean get() = "scroll" in attrs

        /** 围栏级标题：一组卡的共同标题（各卡自己的 title 优先）。空串 = 不覆盖。 */
        val title: String get() = valueAttrs["title"].orEmpty()

        /**
         * 主题档位，取值限定在 [THEME_PRESETS]。
         *
         * 认不出来的值**降级为 plain**而不是保留原字符串 ——
         * 未知值若原样传下去，渲染层要么找不到对应色（整张卡无色）
         * 要么按未知分支硬套默认（看起来像生效了，其实不是）。
         * 降级成 plain 至少视觉上稳定，且 [themeRaw] 仍留着原值可供排查。
         */
        val theme: String
            get() = valueAttrs["theme"]?.lowercase()?.takeIf { it in THEME_PRESETS } ?: "accent"

        /** 模型原始写下的主题值（未校验），供「为什么没生效」的排查用。空串 = 没写。 */
        val themeRaw: String get() = valueAttrs["theme"].orEmpty()
    }

    /**
     * 解析正文里全部卡片围栏。
     *
     * 规则：
     *  1. 只认行首（可带缩进）的围栏头，避免误伤正文里行中的 `card` 字样；
     *  2. 遇到受支持的围栏头，一路吃到**下一个**闭合围栏；中间的普通围栏
     *     （比如模型误写了 ```python）不算闭合 —— 但会继续往后找，
     *     因为模型写错闭合语言是很常见的，单个杂散闭合标记不该让整张卡消失；
     *  3. 走到文本末尾仍未闭合 → [Slice.closed] = `false`，正常返回。
     *
     * 不做嵌套：卡片围栏内不再解析卡片围栏。理由是卡片 JSON 里出现
     * "```" 只有两种可能——模型在字符串里写了反引号（罕见），或者模型
     * 想嵌套（更罕见且必然导致 JSON 本身非法）。支持嵌套会让
     * "截断的 JSON" 和 "嵌套的 JSON" 无法区分。
     */
    fun parse(text: String): List<Slice> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Slice>()
        val open = OPEN_RE.find(text) ?: return emptyList()
        // findAll + 手动推进：闭合围栏不是 OPEN_RE 的匹配（闭合行不含受支持的头）
        var cursor = 0
        while (cursor < text.length) {
            val m = OPEN_RE.find(text, cursor) ?: break
            val head = m.groupValues[1].lowercase()
            val attrText = m.groupValues[2].trim().lowercase()
            val bodyStart = m.range.last + 1

            // 从 bodyStart 起找闭合围栏
            val close = findClose(text, bodyStart)
            val body: String
            val end: Int
            val closed: Boolean
            if (close != null) {
                body = text.substring(bodyStart, close)
                end = close + 1
                closed = true
            } else {
                body = text.substring(bodyStart)
                end = text.length
                closed = false
            }
            out.add(
                Slice(
                    fence = head,
                    body = body,
                    attrs = parseAttrs(attrText),
                    start = m.range.first,
                    end = end,
                    closed = closed,
                    raw = text.substring(m.range.first, end),
                    valueAttrs = parseValueAttrs(attrText),
                )
            )
            cursor = if (end > cursor) end else m.range.last + 1
        }
        return out
    }

    /**
     * 从 [from] 起找第一个闭合围栏行。
     *
     * 找不到返回 null。**不用非贪婪正则**，因为围栏可能嵌套且数量不定，
     * 逐行扫描才可预测 —— 这与 `ChatHistory.foldFences` 用同样的理由。
     */
    private fun findClose(text: String, from: Int): Int? {
        var i = from
        while (i <= text.length) {
            val nl = text.indexOf('\n', i)
            val lineEnd = if (nl < 0) text.length else nl
            val line = text.substring(i, lineEnd)
            if (CLOSE_RE.containsMatchIn(line)) {
                // CLOSE_RE 允许行首缩进，用 find 拿到该行**起始**的绝对下标
                return CLOSE_RE.find(line)?.let { it.range.first + i }
            }
            if (nl < 0) break
            i = nl + 1
        }
        return null
    }

    /** 围栏属性 → 键集合。空属性返回空集合。**只收无值开关**，带值的（`title=`/`theme=`）归 [parseValueAttrs]。 */
    fun parseAttrs(attrText: String): Set<String> {
        if (attrText.isBlank()) return emptySet()
        return attrText.split(Regex("[\\s,]+"))
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            // 🔴 带值属性绝不能混进开关集合：`title=季度报表` 若当成开关名塞进 attrs，
            // `title=季度报表` 就成了一个谁也匹配不上的假开关，而真正的 title 无人读取 ——
            // 表现为「写了 title= 完全没反应」，且没有任何报错。
            .filter { !it.contains('=') && !it.contains(':') }
            .toSet()
    }

    /**
     * 把一段围栏体解析成卡片列表。
     *
     * 宽容处理（模型输出不守规矩是常态，不是异常）：
     *  - `[单组件]` 对象 → 1 张卡
     *  - `[多组件]` 数组 → N 张卡
     *  - `[多组件]` `{"layout":..,"children":[..]}` → 合成 1 张 [QuroChatCard.CompositeCard]
     *  - `[A2UI]` `{"surface":..,"components":{id:..}}` → 邻接表还原为树
     *  - 数组里混入非对象元素（字符串/null）→ 跳过，不整体失败
     *  - 一个都解不出来 → 返回空列表，由调用方决定是否降级显示源码
     */
    fun toCards(fence: String, body: String): List<QuroChatCard> {
        val t = body.trim()
        if (t.isEmpty()) return emptyList()
        return when (fence) {
            FENCE_CARD -> listOfNotNull(safeParse(t))
            FENCE_CARDS -> parseCardsBlock(t)
            FENCE_CARDUI -> parseA2uiBlock(t)
            FENCE_CARDJSON -> parseLineJsonBlock(t)
            else -> emptyList()
        }
    }

    /**
     * 逐行 JSON 围栏体解析：**一行一个独立卡片**。
     *
     * 与整段解析的关键差别在容错粒度 —— 逐行的代价是失去跨行的组合卡语法
     * （`cards` 的数组 / `composite` 的 children 都得跨行才写得下），
     * 换来的是单行脏数据不连累其余各行、以及流式可增量渲染。
     * 所以组合卡请继续用 [FENCE_CARDS]。
     *
     * 未闭合围栏（`closed=false`）的最后一行通常还没收全，解析不出来属正常，
     * 直接跳过即可；等闭合后重新解析自然会补上。
     */
    fun parseLineJsonBlock(body: String): List<QuroChatCard> {
        if (body.isBlank()) return emptyList()
        return body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.startsWith("{") }
            .mapNotNull { safeParse(it) }
            .toList()
    }

    /** 解析围栏 + 转卡片，一步到位。 */
    fun extract(text: String): List<Pair<Slice, List<QuroChatCard>>> =
        parse(text).mapNotNull { s ->
            val cards = toCards(s.fence, s.body)
            if (cards.isEmpty()) null else s to cards
        }

    /**
     * 一组卡片 + 该围栏的效果属性（渲染层用）。
     *
     * ## 为什么要保留「组」这个概念
     *
     * `theme=` / `compact=` 是**组级**语义：` ```cards theme=danger ` 表示
     * 「这一组都用危险基调」，而不是「这一堆卡片各自碰巧同色」。
     * 所以渲染层不能只拿 `List<QuroChatCard>` —— flatten 之后卡片就不知道
     * 该跟哪一组的主题走了。解析层只负责忠实带出属性，**怎么用交给渲染层**
     * （本文件不引 Android 依赖，保持可 JVM 单测）。
     */
    data class Grouped(
        val cards: List<QuroChatCard>,
        /** 围栏级标题（`title=`）。空串 = 无标题。 */
        val title: String,
        /** 主题档位，必在 [THEME_PRESETS] 内；未写时为 `accent`。 */
        val theme: String,
        /** 紧凑模式（`compact`）。 */
        val compact: Boolean,
        /** 是否渲染围栏标题。逐行围栏流式时为 false，避免每帧闪标题。 */
        val showTitle: Boolean = true,
    )

    /**
     * 把 [protected] 区间内的花括号就地抹成空格（**长度与下标完全不变**）。
     *
     * 用途：正文里既有 ```card 围栏、又有内联组件 JSON 时，外层扫描不能把围栏里的 JSON
     * 当成「内联组件」再抽一遍 —— 那样围栏通道与内联通道会把同一张卡渲染两遍。
     * 抹成空格后，[parse] 之外的一切扫描（花括号平衡、索引、长度）都不用改一行：
     * 花括号找不到，自然就跳过那一段。
     *
     * 保持长度不变是有意为之：调用方可以继续用原始下标定位围栏本身。
     */
    fun blankBraces(text: String, protected: List<IntRange>): String {
        if (protected.isEmpty()) return text
        return text.toCharArray().also { ch ->
            for (r in protected) {
                val from = maxOf(r.first, 0)
                val to = minOf(r.last, ch.lastIndex)
                for (x in from..to) if (ch[x] == '{') ch[x] = ' '
            }
        }.concatToString()
    }

    private fun safeParse(json: String): QuroChatCard? = runCatching { parseComponentSpec(json) }.getOrNull()

    /** `cards` 围栏体。 */
    private fun parseCardsBlock(t: String): List<QuroChatCard> = parseNodes(t)

    /**
     * `cards` 围栏体 → 卡片列表（**递归**）。
     *
     * 三种形态都吃：
     *  1. 数组 → 逐项递归（数组套数组也行）；
     *  2. 对象带 `children` → 合成 [QuroChatCard.CompositeCard]，子项继续递归，
     *     所以「组合卡里再套组合卡」是真支持的，不是只认第一层；
     *  3. 普通对象 → 交给 [safeParse]。
     *
     * 为什么"名册认识"的优先于"有 children"：像 `{"type":"kanban","children":[...]}`
     * 这种，children 是看板自己的列语义，应该由它自己的 builder 去读；
     * 只有名册不认识、且又带了 children 的对象，才当组合容器拆开。
     */
    private fun parseNodes(text: String): List<QuroChatCard> =
        parseNodes(text, 0, IntArray(1) { MAX_TOTAL_NODES })

    private fun parseNodes(text: String, depth: Int, budget: IntArray): List<QuroChatCard> {
        val t = text.trim()
        if (t.isEmpty() || depth > MAX_COMPOSITE_DEPTH || budget[0] <= 0) return emptyList()
        if (t.startsWith("[")) {
            val arr = runCatching { JSONArray(t) }.getOrNull() ?: return emptyList()
            return arr.objects().flatMap { parseNodes(it.toString(), depth + 1, budget) }
        }
        val o = runCatching { JSONObject(t) }.getOrNull() ?: return emptyList()
        val direct = safeParse(t)
        if (direct != null && direct !is QuroChatCard.CompositeCard && direct !is CustomCard) {
            return listOf(direct)
        }
        val children = o.optJSONArray("children")?.let { kids ->
            kids.objects().flatMap { parseNodes(it.toString(), depth + 1, budget) }
        }.orEmpty()
        if (children.isNotEmpty()) {
            return listOf(
                QuroChatCard.CompositeCard(
                    id = o.optString("id", QuroChatCardStore.newId()),
                    title = o.optString("title", "").ifBlank { "" },
                    layout = o.optString("layout", "stack"),
                    children = children,
                    description = o.optString("description", "").ifBlank { null },
                )
            )
        }
        return listOfNotNull(direct)
    }

    /**
     * `cardui` 围栏体：A2UI 风格邻接表。
     *
     * 支持两种外层：
     *  - `[{"id":"a","component":"Column","children":["b"]},…]`（A2UI 官方扁平表）
     *  - `{"components":{id:{…}}}`（字典形态）
     *
     * 组件名归一：把 A2UI 的 PascalCase 组件名映射到本 SDK 的 snake_case type
     * （`Text` → `info`、`Button` → `button`、`Column` → `stack`…）。
     * 映射不到的**不丢弃**，包一层 [CustomCard] 保留原始内容，
     * 至少让用户看到 AI 试图画什么。
     */
    private fun parseA2uiBlock(t: String): List<QuroChatCard> {
        val nodes = LinkedHashMap<String, JSONObject>()
        val rootId: String? = when {
            t.startsWith("[") -> {
                val arr = runCatching { JSONArray(t) }.getOrNull() ?: return emptyList()
                for (o in arr.objects()) {
                    val id = o.optString("id", "").ifBlank { o.optString("component", "") }
                    if (id.isNotBlank()) nodes[id] = o
                }
                nodes.keys.firstOrNull()
            }
            t.startsWith("{") -> {
                val o = runCatching { JSONObject(t) }.getOrNull() ?: return emptyList()
                // 🔴 不能用 optString：遇到 JSONObject 它会返回整段 JSON 而不是空串，
                // 扁平邻接表里 "root" 是节点本身，那样 rootId 会变成一长串 JSON，整棵树全丢。
                val root = when (val r0 = if (o.has("root")) o.get("root") else if (o.has("rootId")) o.get("rootId") else null) {
                    is String -> r0
                    else -> ""
                }
                val comps = o.optJSONObject("components") ?: o.optJSONObject("nodes")
                if (comps != null) {
                    // A2UI 标准形态：{"root":"id","components":{...}}
                    val keys = comps.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        comps.optJSONObject(k)?.let { nd -> nodes[k] = nd }
                    }
                } else {
                    // 扁平邻接表：顶层每个值都是节点（模型手写时最自然的一种）
                    val ks = o.keys()
                    while (ks.hasNext()) {
                        val k = ks.next()
                        o.optJSONObject(k)?.let { nd -> nodes[k] = nd }
                    }
                }
                root.ifBlank { pickRoot(nodes) }
            }
            else -> null
        } ?: return emptyList()

        return listOfNotNull(buildFrom(rootId!!, nodes))
    }

    /**
     * 从邻接表里挑根节点。
     *
     * 不能用「第一个 key」：org.json 的 keys() 走 HashMap 迭代，顺序不固定，
     * 扁平表里根节点可能根本不是第一个（真机上就会把 Text 叶子当成根，
     * 整棵树只剩一个叶子节点）。所以这里按**没有父节点**来挑，
     * 并优先叫 root / rootId 的那个。
     */
    private fun pickRoot(nodes: Map<String, JSONObject>): String {
        if (nodes.isEmpty()) return ""
        val referenced = HashSet<String>()
        nodes.values.forEach { nd ->
            nd.optJSONArray("children")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.opt(i)
                    val cid = when (c) {
                        is String -> c
                        is JSONObject -> c.optString("id", "").ifBlank { c.optString("component", "") }
                        else -> ""
                    }
                    if (cid.isNotBlank()) referenced.add(cid)
                }
            }
        }
        for (name in listOf("root", "rootId")) {
            if (name in nodes) return name
        }
        nodes.keys.firstOrNull { it !in referenced }?.let { return it }
        return nodes.keys.firstOrNull() ?: ""
    }

    /**
     * 邻接表 → 树（两阶段：**先给每个 id 建卡并缓存，再按引用装配 children**）。
     *
     * 为什么不是"边建边往下钻"的旧写法：
     *  - 共享子节点（A、B 都引用 C）时旧逻辑会把 C 建两次，而 `visiting` 集合
     *    让第二次直接返回 null —— 一棵树上凭空少一整根枝；
     *  - 只有一条 `visiting` 时，同一个 id 在**同一条子树路径上**出现两次就断。
     *
     * 现在用 memo：引用几遍都取同一个对象，环也一并断掉（在建中的 id 在 memo 里
     * 存着 null，回头读到它就是"这条边先放着，别再深入了"）。
     */
    private fun buildFrom(rootId: String, nodes: Map<String, JSONObject>): QuroChatCard? {
        val memo = LinkedHashMap<String, QuroChatCard?>()
        var budget = MAX_TOTAL_NODES

        fun walk(id: String, depth: Int): QuroChatCard? {
            if (id in memo) return memo[id]
            if (depth > MAX_COMPOSITE_DEPTH || budget <= 0) return null
            val node = nodes[id] ?: return null
            budget--
            memo[id] = null

            // A2UI 用 component/children；本 SDK 用 type/children
            val compName = node.optString("component", "").ifBlank { node.optString("type", "") }
            val type = normalizeType(compName)

            val childIds = buildList {
                node.optJSONArray("children")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        // 两种写法都吃：{"children":[{"id":"a"}]} 与 {"children":["a"]}
                        val cid = when (val c = arr.opt(i)) {
                            is String -> c
                            is JSONObject -> c.optString("id", "").ifBlank { c.optString("component", "") }
                            else -> ""
                        }
                        if (cid.isNotBlank() && cid != id) add(cid)
                    }
                }
            }
            val kids = childIds.mapNotNull { walk(it, depth + 1) }

            // props / properties 合并成一张扁平 JSON，让 parseComponentSpec 能吃
            val spec = JSONObject().apply {
                put("type", type)
                val nid = node.optString("id", "").ifBlank { QuroChatCardStore.newId() }
                put("id", nid)
                val ntitle = node.optString("title", "").ifBlank { "" }
                put("title", ntitle)
                val props = node.optJSONObject("properties") ?: node.optJSONObject("props")
                if (props != null) {
                    val keys = props.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        put(k, props.get(k))
                    }
                }
                // 容器语义：容器组件把子节点塞进 children
                if (kids.isNotEmpty()) {
                    put("children", JSONArray().also { a -> kids.forEach { a.put(serializeCard(it)) } })
                }
            }

            // 有子节点时优先走组合卡，保留结构
            if (kids.isNotEmpty() && type in CONTAINER_TYPES) {
                val composite = QuroChatCard.CompositeCard(
                    id = node.optString("id", QuroChatCardStore.newId()),
                    title = node.optString("title", "").ifBlank { "" },
                    layout = "stack",
                    children = kids,
                    description = null,
                )
                memo[id] = composite
                return composite
            }

            // type 已是哨兵 custom 时**不能再喂给解析层**：parseObj 查不到名册会再兜一个
        // kind="custom" 的卡，AI 写的原始组件名就被冲掉了。
        val parsed = if (type == "custom") null else runCatching { parseComponentSpec(spec.toString()) }.getOrNull()
            if (parsed != null) {
                memo[id] = parsed
                return parsed
            }

            // 映射不到 → 原样保留，附上原始组件名，别让 AI 的意图凭空消失
            val fallback = CustomCard(
                id = node.optString("id", QuroChatCardStore.newId()),
                title = node.optString("title", "").ifBlank { compName },
                kind = if (type == "custom" && compName.isNotBlank()) compName else type,
                payload = spec.toString(),
                children = kids,
            )
            memo[id] = fallback
            return fallback
        }

        return walk(rootId, 0)
    }

    /** 容器型 type：子节点需要挂进 children 而不是被 props 吞掉。 */
    /**
     * 容器型 type：子节点要挂进 children 而不是被 props 吞掉。
     *
     * 末尾那个 `custom` 是**识别不出来的容器**：model 写了 `{"component":"Whatever","children":[...]}`
     * 这种，它带的 children 显然还是子卡，当成容器组一层组合卡，比丢一堆孤儿子节点有用。
     */
    private val CONTAINER_TYPES = setOf(
        "stack", "column", "row", "card", "container", "list", "grid", "tabs", "composite", "custom",
    )

    /**
     * A2UI / 常见组件名 → 本 SDK 的 type。
     *
     * 三段式，按顺序：
     *  1. 精确匹配别名表（`Text` → `info`）；
     *  2. 小写后匹配别名表（`text` / `TEXT` 也算）；
     *  3. PascalCase → snake_case 通用转换，**且要求目标在名册里**。
     *
     * 🔴 第 3 步的"必须在名册里"是有意为之：以前会把认不出来的名字 snake_case 化后
     * 照返回，下游 parseObj 查不到又落 CustomCard —— 白绕一圈，还把"这名字我们没做"
     * 这件事藏起来了。现在直接返回 `custom`，让 CustomCard 里原样保留 AI 写的组件名，
     * 用户反馈时一眼知道是哪个组件没接上。
     *
     * 映射不到 → `"custom"`（不是"原样返回"），交给 [parseA2uiBlock] 进兜底卡。
     */
    fun normalizeType(name: String): String {
        val n = name.trim()
        if (n.isEmpty()) return "custom"
        COMPONENT_ALIASES[n]?.let { return it }
        ALIASES_LOWER[n.lowercase()]?.let { return it }
        val snake = pascalToSnake(n)
        return if (snake in CardSdk.types) snake else "custom"
    }

    /**
     * 该组件名**能否**映射到名册里的某个 type（供调用方先判断再决定要不要保留原文）。
     *
     * 注意语义：返回 false 不代表"这个组件不存在"，只代表"客户端没有它的渲染器"。
     */
    fun isKnownType(name: String): Boolean = normalizeType(name) != "custom"

    /**
     * PascalCase / camelCase → snake_case（`TextInput` → `text_input`，`QRCode` → `qr_code`）。
     *
     * 🔴 替换串必须写成 `${g1}_${g2}` 而不是 `"$1_$2"`：Kotlin 的模板会把 `$1_$2`
     * 解析成 `$1` + `$2` 两个引用，中间那个下划线被吞掉（实测 QRCode 直接退化成 qrcode，
     * 与 roster 的 `qrcode` 撞巧能对上，但 HTMLPreview 这类就悄悄错成 htmlpreview）。
     *
     * 做成 internal 是为了能直接单测这条纯函数 —— 它错了不会报错，只会让组件名映射悄悄失效。
     */
    internal fun pascalToSnake(n: String): String {
        val a = Regex("([a-z0-9])([A-Z])")
        val b = Regex("([A-Z]+)([A-Z][a-z])")
        var s = a.replace(n) { m -> "${m.groupValues[1]}_${m.groupValues[2]}" }
        s = b.replace(s) { m ->
            val head = m.groupValues[0].dropLast(2)   // 前一段全大写
            val tail = m.groupValues[0].takeLast(2)   // 最后一个大写 + 后面那个小写
            "${head}_$tail"
        }
        return s.lowercase()
    }

    /**
     * 别名表自检：每条映射的目标都必须是**名册里真实存在**的 type。
     *
     * 映射到不存在的 type 不会报错、不会崩溃，运行期只是静默变成一张 CustomCard
     * —— 表现为"AI 明明说画了个 XX，界面上什么都没有"。所以这条必须能被测试抓到。
     */
    fun lintAliases(): List<String> {
        val bad = ArrayList<String>()
        COMPONENT_ALIASES.forEach { (k, v) ->
            // `custom` 是 normalizeType 的哨兵值（表示"这组件我们没做"），不是名册里的 type，放行
            if (v != "custom" && v !in CardSdk.types) bad += "别名 $k → $v 不在名册里"
        }
        return bad
    }



    /** 跨 SDK 组件名映射。键为 A2UI/业界写法，值为本 SDK type。 */
    private val COMPONENT_ALIASES: Map<String, String> = mapOf(
        "Text" to "info", "Paragraph" to "note", "Markdown" to "note", "Code" to "note",
        "Button" to "button", "IconButton" to "button", "FAB" to "button", "Chip" to "chips",
        "Chips" to "chips", "Toggle" to "toggle", "Switch" to "toggle", "Checkbox" to "toggle",
        "RadioButton" to "toggle", "Slider" to "slider", "Rating" to "rating", "Stepper" to "counter",
        "TextField" to "form", "TextBox" to "form", "Select" to "segmented", "Dropdown" to "segmented",
        "Column" to "composite", "Row" to "composite", "Stack" to "composite", "Card" to "composite",
        "Container" to "composite", "List" to "list", "Grid" to "list", "Tabs" to "tabs",
        "Accordion" to "expandable", "Divider" to "info", "Image" to "media", "Video" to "mediaplay",
        "Audio" to "mediaplay", "ProgressBar" to "progress", "Progress" to "progress",
        "CircularProgress" to "ring", "Table" to "table", "BarChart" to "chart", "LineChart" to "chart",
        "PieChart" to "pie", "RadarChart" to "radar", "Gauge" to "gauge", "Heatmap" to "heatmap",
        "Mermaid" to "mermaid", "Markdown" to "note", "Custom" to "custom",
        // ── A2UI basic catalog（第二批补齐）──
        "TextInput" to "form", "TextArea" to "form", "Input" to "form", "TextBox" to "form",
        "SearchBox" to "searchbox", "SearchBar" to "searchbox", "Search" to "searchbox",
        "CheckboxGroup" to "toggle", "RadioGroup" to "toggle", "Switch" to "toggle",
        "Stepper" to "counter", "IncDec" to "counter",
        "Tag" to "tagcloud", "Tags" to "tagcloud", "Label" to "tagcloud",
        "Avatar" to "avatargroup", "AvatarGroup" to "avatargroup",
        "Panel" to "composite", "Group" to "composite", "Flex" to "composite",
        "ScrollView" to "composite", "Section" to "composite", "Box" to "composite",
        "GridView" to "groupedlist", "List" to "list",
        "TabBar" to "tabs",
        "Collapse" to "expandable", "Disclosure" to "expandable",
        "LinearProgress" to "progress", "BarChart" to "chart", "Bars" to "chart", "Plot" to "chart",
        "Donut" to "pie", "Doughnut" to "pie",
        "GaugeChart" to "gauge", "Speedometer" to "speedometer", "Tachometer" to "speedometer",
        "Sparkline" to "sparkline", "TrendLine" to "sparkline",
        "ScatterChart" to "scatter", "ScatterPlot" to "scatter",
        "FunnelChart" to "funnel",
        "CandlestickChart" to "candlestick", "OHLC" to "candlestick",
        "BoxPlotChart" to "boxplot",
        "StackedBar" to "stackedbar", "StackedBarChart" to "stackedbar",
        "Comparison" to "compare",
        "CountdownTimer" to "countdown",
        "Stopwatch" to "stopwatch",
        "Clock" to "clock",
        "Invoice" to "invoice", "Bill" to "invoice", "Receipt" to "invoice",
        "Gantt" to "gantt", "GanttChart" to "gantt",
        "Steps" to "steps",
        "Board" to "kanban", "Trello" to "kanban",
        "TodoList" to "checklist", "TaskList" to "checklist", "CheckList" to "checklist",
        "Vote" to "poll", "Quiz" to "poll",
        "Blockquote" to "quote",
        "CodeDiff" to "diff", "Patch" to "diff",
        "FlowChart" to "flow", "Graph" to "flow", "DirectedGraph" to "flow",
        "OrgChart" to "hierarchy", "Organization" to "hierarchy",
        "VCard" to "contact",
        "Commodity" to "product",
        "Event" to "schedule", "Agenda" to "schedule",
        "Attachment" to "filecard", "Document" to "filecard",
        "Picture" to "media", "Photo" to "media",
        "Player" to "mediaplay",
        "Swiper" to "carousel", "SliderView" to "carousel",
        "Console" to "terminal",
        "Hyperlink" to "linklist",
        "Pager" to "pagination", "Paginator" to "pagination",
        "Swatch" to "color", "Colors" to "color",
        "StarRating" to "rating",
        "Metric" to "stat", "KPI" to "stat",
        "Banner" to "alert", "Notice" to "alert",
        "ToolCallStatus" to "toolcall",
        "Logs" to "stream",
        "MiniProgram" to "miniapp",
        "WebView" to "htmlpreview",
        "Equation" to "formula", "Math" to "formula",
        "Translation" to "translate",
        "Vocabulary" to "vocab", "WordCard" to "vocab",
        "Habit" to "tracker", "HabitTrack" to "tracker",
        "Score" to "scoreboard", "Match" to "scoreboard", "Game" to "scoreboard",
        "ColorPalette" to "palette", "Theme" to "palette",
        "QRCode" to "qrcode", "Qrcode" to "qrcode",
        "ImageGrid" to "gallery", "Photos" to "gallery",
        "Link" to "linklist",
    )

    /**
     * 别名表的小写索引。
     *
     * 表本身按 A2UI 原样写（Text / TextInput），但模型也会写成 TEXT 或 text。
     * 逐次 lowercase 查原表等于没有大小写不敏感，所以这里一次性建索引。
     */
    private val ALIASES_LOWER: Map<String, String> by lazy {
        COMPONENT_ALIASES.entries.associate { it.key.lowercase() to it.value }
    }

    /** 扩展 JSONArray 遍历：只取对象元素，字符串/null/数字一律跳过。 */
    private fun JSONArray.objects(): List<JSONObject> {
        val out = ArrayList<JSONObject>(length())
        for (i in 0 until length()) {
            optJSONObject(i)?.let { out.add(it) }
        }
        return out
    }
}
