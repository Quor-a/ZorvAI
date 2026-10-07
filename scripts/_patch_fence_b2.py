# -*- coding: utf-8 -*-
"""CardFence 第二批改造：

 1. normalizeType 只映射到**名册里真实存在**的 type（不再伪造一个下游认不出来的名字）；
 2. COMPONENT_ALIASES 扩到 A2UI catalog + RN/Flutter 常见的 ~150 个组件名；
 3. cardui 邻接表改两阶段构建（缓存 + 环检测），共享子节点不再被吞掉；
 4. cards 围栏支持**嵌套组合卡**（children 里再套 children），带深度/总量双上限；
 5. 新增 lintAliases()，把「别名指向不存在的 type」这种静默失效变成可测的断言。
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "app", "src", "main", "java", "com", "ai", "assistance", "quro", "core", "cards", "CardFence.kt")


def read(p):
    with io.open(p, encoding="utf-8") as f:
        return f.read()


def write(p, s):
    tmp = p + ".tmp_%d" % os.getpid()
    with io.open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write(s)
    os.replace(tmp, p)


# ── 1) 常量：深度/总量上限 ──
CONST_OLD = '''    /** 单独一行（可带缩进）的闭合围栏 —— 任意语言都算闭合标记。 */
    private val CLOSE_RE = Regex("^[ \\\\t]*```+[ \\\\t]*$")
'''
CONST_NEW = CONST_OLD + '''
    /**
     * 组合卡最大嵌套深度。
     *
     * 模型偶尔会把 `children` 自引用（自己指向自己），没有上限就是递归到 StackOverflow，
     * 而崩溃发生在渲染线程上 —— 整条消息都跟着没了。
     */
    const val MAX_COMPOSITE_DEPTH = 6

    /** 单张卡允许的节点总数（含所有层）。深度管得住"套娃"，总量管得住"铺满屏"。 */
    const val MAX_TOTAL_NODES = 64
'''

# ── 2) normalizeType + 别名表 ──
NORM_OLD_START = '''    /**
     * A2UI / 常见 PascalCase 组件名 → 本 SDK 的 snake_case type。
     *
     * 覆盖 A2UI basic catalog 里的高频组件 + 业界常见别名。
     * 未命中的原样返回（小写化），交给上层走 CustomCard 兜底。
     */
    fun normalizeType(name: String): String {
        val n = name.trim()
        if (n.isEmpty()) return "custom"
        val lower = n.lowercase()
        // 已是本 SDK 的 snake_case
        if (lower in KNOWN_SNAKE) return lower
        return COMPONENT_ALIASES[n] ?: lower.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
    }
'''
NORM_OLD_END = '''    /** 跨 SDK 组件名映射。键为 A2UI/业界写法，值为本 SDK type。 */
    private val COMPONENT_ALIASES: Map<String, String> = mapOf(
'''
NORM_NEW = '''    /**
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
        COMPONENT_ALIASES[n.lowercase()]?.let { return it }
        val snake = pascalToSnake(n)
        return if (snake in CardSdk.types) snake else "custom"
    }

    /**
     * 该组件名**能否**映射到名册里的某个 type（供调用方先判断再决定要不要保留原文）。
     *
     * 注意语义：返回 false 不代表"这个组件不存在"，只代表"客户端没有它的渲染器"。
     */
    fun isKnownType(name: String): Boolean = normalizeType(name) != "custom"

    /** PascalCase / camelCase → snake_case（`TextInput` → `text_input`，`QRCode` → `qr_code`）。 */
    private fun pascalToSnake(n: String): String =
        n.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2")
            .lowercase()

    /**
     * 别名表自检：每条映射的目标都必须是**名册里真实存在**的 type。
     *
     * 映射到不存在的 type 不会报错、不会崩溃，运行期只是静默变成一张 CustomCard
     * —— 表现为"AI 明明说画了个 XX，界面上什么都没有"。所以这条必须能被测试抓到。
     */
    fun lintAliases(): List<String> {
        val bad = ArrayList<String>()
        COMPONENT_ALIASES.forEach { (k, v) ->
            if (v !in CardSdk.types) bad += "别名 $k → $v 不在名册里"
        }
        return bad
    }

    /** 跨 SDK 组件名映射。键为 A2UI/业界写法，值为本 SDK type（必须存在于名册，见 [lintAliases]）。 */
    private val COMPONENT_ALIASES: Map<String, String> = mapOf(
'''

# ── 3) cardui：邻接表两阶段构建 ──
BUILD_OLD_HEAD = '''    /** 邻接表 → 树。深度上限防环（A/B 互相引用会无限递归）。 */
    private fun buildFrom(
        id: String,
        nodes: Map<String, JSONObject>,
        visiting: MutableSet<String>,
        depth: Int,
    ): QuroChatCard? {
        if (depth > 24 || id in visiting) return null
        val node = nodes[id] ?: return null
        visiting.add(id)

'''
BUILD_NEW_HEAD = '''    /**
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

'''

BUILD_OLD_TAIL = '''        visiting.remove(id)
'''
BUILD_NEW_TAIL = ''''''

BUILD_OLD_BODY_SPLIT = '''        val kids = childIds.mapNotNull { buildFrom(it, nodes, visiting, depth + 1) }
'''
BUILD_NEW_BODY_SPLIT = '''        val kids = childIds.mapNotNull { walk(it, depth + 1) }
'''

BUILD_OLD_END = '''        // 有子节点时优先走组合卡，保留结构
        if (kids.isNotEmpty() && type in CONTAINER_TYPES) {
            return QuroChatCard.CompositeCard(
                id = node.optString("id", QuroChatCardStore.newId()),
                title = node.optString("title", "").ifBlank { "" },
                layout = if (type == "row") "stack" else "stack",
                children = kids,
                description = null,
            )
        }

        val parsed = safeParse(spec.toString())
        if (parsed != null) return parsed

        // 映射不到 → 原样保留，附上原始组件名，别让 AI 的意图凭空消失
        return CustomCard(
            id = node.optString("id", QuroChatCardStore.newId()),
            title = node.optString("title", "").ifBlank { compName },
            kind = compName,
            payload = spec.toString(),
            children = kids,
        )
    }
'''
BUILD_NEW_END = '''        // 有子节点时优先走组合卡，保留结构
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

        val parsed = runCatching { parseComponentSpec(spec.toString()) }.getOrNull()
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
'''

# ── 4) cards 围栏：递归组合卡 ──
CARDS_OLD = '''    /** `cards` 围栏体。 */
    private fun parseCardsBlock(t: String): List<QuroChatCard> {
        // 形态 A：{"layout":..,"children":[..]} → 合成组合卡
        if (t.startsWith("{")) {
            runCatching { JSONObject(t) }.getOrNull()?.let { o ->
                val children = o.optJSONArray("children")
                if (children != null) {
                    val kids = children.objects().mapNotNull { safeParse(it.toString()) }
                    if (kids.isNotEmpty()) {
                        return listOf(
                            QuroChatCard.CompositeCard(
                                id = o.optString("id", QuroChatCardStore.newId()),
                                title = o.optString("title", "").ifBlank { "" },
                                layout = o.optString("layout", "stack"),
                                children = kids,
                                description = o.optString("description", "").ifBlank { null },
                            )
                        )
                    }
                    return emptyList()
                }
            }
            // 形态 B：单个对象也允许（模型常偷懒）
            return listOfNotNull(safeParse(t))
        }
        // 形态 C：数组
        if (t.startsWith("[")) {
            val arr = runCatching { JSONArray(t) }.getOrNull() ?: return emptyList()
            return arr.objects().mapNotNull { safeParse(it.toString()) }
        }
        return emptyList()
    }
'''
CARDS_NEW = '''    /** `cards` 围栏体。 */
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
     * 为什么"名册认识"的优先于"有 children"：像 `{\"type\":\"kanban\",\"children\":[...]}`
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
'''

CALL_OLD = '''        return listOfNotNull(buildFrom(rootId!!, nodes, mutableSetOf(), 0))'''
CALL_NEW = '''        return listOfNotNull(buildFrom(rootId!!, nodes))'''

# ── 扩别名表 ──
ALIAS_OLD_TAIL = '''        "Mermaid" to "mermaid", "Markdown" to "note", "Custom" to "custom",
    )
'''
ALIAS_NEW_TAIL = '''        "Mermaid" to "mermaid", "Markdown" to "note", "Custom" to "custom",
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
'''


def main():
    src = read(TARGET)
    for name, old in (("CONST", CONST_OLD), ("NORM_HEAD", NORM_OLD_START),
                      ("NORM_ALIAS", NORM_OLD_END), ("BUILD_HEAD", BUILD_OLD_HEAD),
                      ("BUILD_TAIL", BUILD_OLD_TAIL), ("BUILD_SPLIT", BUILD_OLD_BODY_SPLIT),
                      ("BUILD_END", BUILD_OLD_END), ("CARDS", CARDS_OLD), ("CALL", CALL_OLD),
                      ("ALIAS_TAIL", ALIAS_OLD_TAIL)):
        hits = src.count(old)
        if hits != 1:
            raise SystemExit("锚点 %s 命中 %d 次，拒绝改动" % (name, hits))

    src = src.replace(CONST_OLD, CONST_NEW)
    # 2) normalizeType：整段替换（头 + 别名表声明行）
    src = src.replace(NORM_OLD_START, NORM_NEW)
    # 3) buildFrom：头 + 递归调用 + 尾
    src = src.replace(BUILD_OLD_HEAD, BUILD_NEW_HEAD)
    src = src.replace(BUILD_OLD_BODY_SPLIT, BUILD_NEW_BODY_SPLIT)
    src = src.replace(BUILD_OLD_TAIL, BUILD_NEW_TAIL)
    src = src.replace(BUILD_OLD_END, BUILD_NEW_END)
    # 4) cards 递归
    src = src.replace(CARDS_OLD, CARDS_NEW)
    # 5) 调用点
    src = src.replace(CALL_OLD, CALL_NEW)
    # 6) 别名表扩充
    src = src.replace(ALIAS_OLD_TAIL, ALIAS_NEW_TAIL)

    write(TARGET, src)

    out = read(TARGET)
    checks = {
        "MAX_COMPOSITE_DEPTH": "MAX_COMPOSITE_DEPTH",
        "MAX_TOTAL_NODES": "MAX_TOTAL_NODES",
        "lintAliases": "fun lintAliases()",
        "isKnownType": "fun isKnownType",
        "pascalToSnake": "private fun pascalToSnake",
        "递归 parseNodes": "private fun parseNodes(text: String, depth: Int, budget: IntArray)",
        "memo": "val memo = LinkedHashMap",
        "call new buildFrom": "buildFrom(rootId!!, nodes)",
    }
    for label, needle in checks.items():
        if needle not in out:
            raise SystemExit("复核失败：%s" % label)
    if "visiting: MutableSet" in out or "visiting.remove(" in out:
        raise SystemExit("复核失败：旧的 visiting 逻辑还在")
    print("[OK] CardFence.kt 改造完成（映射补全 + 递归组合卡 + 两阶段建树）")


if __name__ == "__main__":
    sys.exit(main())
