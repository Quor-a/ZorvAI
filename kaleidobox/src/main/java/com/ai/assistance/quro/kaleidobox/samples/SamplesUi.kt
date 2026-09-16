package com.ai.assistance.quro.kaleidobox.samples

import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.ui.*
import com.ai.assistance.quro.kaleidobox.core.util.Json

/**
 * 插件共享设计系统（ZorvAI 暖陶土 / 纸 风格，对齐宿主 QuroTheme）。
 *
 * 所有插件统一吃这一套 token 与组件，保证"App 级"视觉一致、不再各写各的。
 *
 * 关键不变式：任何深色表面上必须用浅色文字（[C.codeFg] / [C.onDark]），
 * 否则在亮色主题下会黑底黑字、字全看不见（历史踩过的坑）。
 */
object SamplesUi {

    /** 配色 token —— 与宿主 QuroTheme 的陶土/纸系对齐。 */
    object C {
        val primary = "#C25A38"       // 陶土主色（= 宿主 primary）
        val primaryPress = "#A8482B"
        val primarySoft = "#F4E4DB"   // 浅陶土底
        val ink = "#211E1A"           // 主文字（亮底）
        val inkSoft = "#544D44"       // 次文字
        val muted = "#938A7E"         // 弱文字 / 占位
        val line = "#E3DDD0"          // 发丝边
        val line2 = "#D8D0C0"         // 强边
        val surface = "#FFFFFF"       // 卡片表面
        val paper = "#F4F1EA"         // 页面底（= 宿主 background）
        val codeBg = "#211D18"        // 代码块深底（= 宿主 dark surface）
        val codeFg = "#EDE6DA"        // 代码块文字（亮）
        val codeMuted = "#A89E90"     // 代码块弱字
        val ok = "#6E7C62"            // 鼠尾草绿（ok / AI 气泡）
        val bubbleUserText = "#FFFFFF"
        val onDark = "#EDE6DA"        // 深底上的文字
    }

    // ---- 基础原子 ----

    /** 发丝边框卡片：白底 + 圆角 + 1px 边，平铺高级感（Material3 常用 tonal surface 思路）。 */
    fun card(id: String, content: List<UiNode>, radius: Int = 14, pad: Int = 16): UiNode =
        UiNode.Box(
            id,
            modifier = Mod(
                width = Size.Fill, background = C.surface, cornerRadius = radius,
                border = Border(1, C.line), padding = Edges.all(pad),
            ),
            children = listOf(UiNode.Column("${id}_c", children = content)),
        )

    /** 小分区标签：弱色小字，建立视觉层级。 */
    fun section(id: String, text: String): UiNode =
        UiNode.Text(
            id, Bound.Lit(text), TypeStyle.LABEL, color = C.muted,
            modifier = Mod(padding = Edges(0, 0, 0, 10)),
        )

    /** 占位提示文字。 */
    fun hint(id: String, text: String): UiNode =
        UiNode.Text(
            id, Bound.Lit(text), TypeStyle.CAPTION, color = C.muted,
            modifier = Mod(padding = Edges(0, 6, 0, 6)),
        )

    /** 键值信息卡（设备信息）。 */
    fun infoCard(id: String, rows: List<Pair<String, String>>): UiNode {
        val body = buildList<UiNode> {
            rows.forEachIndexed { i, (k, v) ->
                if (i > 0) add(UiNode.Divider("${id}_d$i", Mod(padding = Edges.symmetric(vertical = 6))))
                add(
                    UiNode.Row(
                        "${id}_r$i",
                        modifier = Mod(padding = Edges(4, 0, 4, 0)),
                        children = listOf(
                            UiNode.Text("${id}_k$i", Bound.Lit(k), TypeStyle.LABEL, color = C.muted,
                                modifier = Mod(width = Size.Dp(92))),
                            UiNode.Text("${id}_v$i", Bound.Lit(v), TypeStyle.BODY, color = C.ink,
                                modifier = Mod(weight = 1f)),
                        ),
                    )
                )
            }
        }
        return card(id, body)
    }

    /**
     * 深色代码 / 日志块，文字显式用亮色，避免黑底黑字。
     *
     * 关键：本块内部自带 verticalScroll，而被放在 [scrollPage] 的纵向滚动内容里，
     * 若用 weight 或 wrap 父容器会让内层 scroll 拿到无限高度 → Compose 崩溃
     * （"Vertically scrollable component was measured with an infinity maximum height"）。
     * 因此外层 Box 必须给【固定高度】（fixedH），让内层 scroll 始终有界。
     *
     * @param fill  true → 较高的固定高度（约 320），适合终端输出占主区
     * @param minH  覆盖固定高度（用于响应块/剪贴板当前内容等较小块）
     * @param weight 非空时改为"占据剩余高度"（放进竖向 [Column] 里用），
     *               这样终端/日志能真正占满主区，而不是被压在一个固定高度里
     */
    fun codeBlock(
        id: String,
        lines: List<String>,
        emptyHint: String,
        label: String? = null,
        fill: Boolean = false,
        minH: Int? = null,
        weight: Float? = null,
    ): UiNode {
        val fixedH = minH ?: (if (fill) 320 else 140)
        val linesCol: UiNode = if (lines.isEmpty())
            UiNode.Text("${id}_e", Bound.Lit(emptyHint), TypeStyle.BODY, color = C.codeMuted)
        else UiNode.Column(
            "${id}_lc",
            children = lines.mapIndexed { i, l ->
                UiNode.Text("${id}_l$i", Bound.Lit(l), TypeStyle.MONO, color = C.codeFg,
                    modifier = Mod(padding = Edges(0, 3, 0, 3)))
            },
        )
        val scroll = UiNode.Scroll(
            "${id}_s", vertical = true,
            modifier = Mod(width = Size.Fill),
            child = linesCol,
        )
        val col = buildList<UiNode> {
            if (label != null) add(
                UiNode.Text("${id}_lbl", Bound.Lit(label), TypeStyle.LABEL, color = C.codeMuted,
                    modifier = Mod(padding = Edges(0, 0, 0, 8)))
            )
            add(scroll)
        }
        return UiNode.Box(
            id,
            modifier = Mod(
                width = Size.Fill,
                height = if (weight != null) Size.Fill else Size.Dp(fixedH),
                weight = weight,
                background = C.codeBg, cornerRadius = 14, padding = Edges.all(14),
            ),
            children = listOf(UiNode.Column("${id}_c", children = col)),
        )
    }

    /** 标签输入框（Material OutlinedTextField，已随主题）。 */
    fun field(
        id: String, label: String, value: Bound<String>, action: Action,
        singleLine: Boolean = true, minH: Int = 56,
    ): UiNode = UiNode.TextField(
        id, value, action, label = label, singleLine = singleLine,
        modifier = Mod(width = Size.Fill, minHeight = minH, padding = Edges(0, 0, 0, 10)),
    )

    /** 主操作：陶土填充、整行、48 高 CTA。 */
    fun primaryAction(id: String, label: String, extra: Mod = Mod()): UiNode =
        UiNode.Button(
            id, Bound.Lit(label), Action.of(id),
            variant = UiNode.Button.Variant.FILLED,
            modifier = extra.copy(width = Size.Fill, minHeight = 48),
        )

    /** 次级操作：柔和整行。 */
    fun secondaryAction(id: String, label: String, extra: Mod = Mod()): UiNode =
        UiNode.Button(
            id, Bound.Lit(label), Action.of(id),
            variant = UiNode.Button.Variant.TONAL,
            modifier = extra.copy(width = Size.Fill, minHeight = 48),
        )

    /** HTTP 方法选择器：横向滚动的选中态分段（FILLED 表选中），窄屏不再拆字。 */
    fun methodPicker(id: String, methods: List<String>, selected: String): UiNode =
        UiNode.Scroll(
            id, vertical = false,
            modifier = Mod(width = Size.Fill, padding = Edges(0, 0, 0, 4)),
            child = UiNode.Row(
                "${id}_r",
                children = methods.map { m ->
                    UiNode.Button(
                        "${id}_$m", Bound.Lit(m), Action.of("method", "value" to m),
                        variant = if (m == selected) UiNode.Button.Variant.FILLED else UiNode.Button.Variant.TONAL,
                        modifier = Mod(padding = Edges(0, 0, 0, 8)),
                    )
                },
            ),
        )

    /** 对话气泡：用户 = 陶土底白字 / AI = 白卡深字。 */
    fun bubble(id: String, role: String, text: String): UiNode {
        val isUser = role == "user"
        return UiNode.Row(
            "${id}_r",
            arrangement = if (isUser) Arrangement.END else Arrangement.START,
            modifier = Mod(padding = Edges(0, 0, 0, 10)),
            children = listOf(
                UiNode.Box(
                    "${id}_b",
                    modifier = Mod(
                        width = Size.Fraction(0.82f),
                        background = if (isUser) C.primary else C.surface,
                        cornerRadius = 14,
                        border = if (isUser) null else Border(1, C.line),
                        padding = Edges.all(12),
                    ),
                    children = listOf(
                        UiNode.Text("${id}_t", Bound.Lit(text), TypeStyle.BODY,
                            color = if (isUser) C.bubbleUserText else C.ink),
                    ),
                ),
            ),
        )
    }

    // ---- 页面骨架 ----

    /**
     * 标准插件页：内容区内部滚动（weight=1f），操作区固定底部。
     * 用此骨架后，外壳不必再包全局滚动，长内容（聊天/终端）也能正确内部滚动、输入栏常驻。
     */
    fun scrollPage(id: String, content: List<UiNode>, actions: List<UiNode> = emptyList()): UiNode =
        UiNode.Column(
            id,
            modifier = Mod(width = Size.Fill, height = Size.Fill),
            children = buildList {
                add(
                    UiNode.Scroll(
                        "${id}_scroll", vertical = true,
                        modifier = Mod(width = Size.Fill, weight = 1f),
                        child = UiNode.Column("${id}_content", children = content),
                    )
                )
                if (actions.isNotEmpty()) add(
                    UiNode.Column(
                        "${id}_actions",
                        modifier = Mod(padding = Edges(0, 12, 0, 0)),
                        children = actions,
                    )
                )
            },
        )

    // ---- 兼容旧 helper（渐进迁移期保留） ----
    fun title(text: String, sub: String? = null): UiNode = UiNode.Column(
        "title_${text.hashCode()}",
        children = buildList {
            add(UiNode.Text("t0", Bound.Lit(text), TypeStyle.TITLE, color = C.ink))
            if (sub != null) add(
                UiNode.Text("t1", Bound.Lit(sub), TypeStyle.CAPTION, color = C.muted,
                    modifier = Mod(padding = Edges(0, 4, 0, 0)))
            )
        },
    )

    fun label(text: String, id: String): UiNode =
        UiNode.Text(id, Bound.Lit(text), TypeStyle.LABEL, color = C.muted, modifier = Mod(padding = Edges(0, 0, 0, 6)))

    fun button(id: String, label: String, variant: UiNode.Button.Variant = UiNode.Button.Variant.TONAL, enabled: Boolean = true, modifier: Mod = Mod()): UiNode =
        UiNode.Button(id, Bound.Lit(label), Action.of(id), enabled = Bound.Lit(enabled), variant = variant, modifier = modifier)

    fun logLines(entries: List<String>, emptyHint: String): UiNode = codeBlock("log", entries, emptyHint)

    val ACCENT = C.primary
    val BUBBLE_AI = C.surface
    val ON_ACCENT = C.bubbleUserText
    val ON_BUBBLE_AI = C.ink
    val MUTED = C.muted

    // ---- 状态工具 ----

    /**
     * 从宿主回灌的 [args] 里取出 UI 状态。
     *
     * 关键坑（曾导致"插件只有界面、点了没反应"）：宿主在
     * [com.ai.assistance.quro.kaleidobox.core.KaleidoRuntime.renderSurface] /
     * [com.ai.assistance.quro.kaleidobox.core.KaleidoRuntime.dispatchUiAction] 里都会把状态
     * 用 `KValue.obj("state" to state)` 二次包装成嵌套的 [KValue.Obj]，其内层值是
     * [KValue.Str]/[KValue.Arr] 等 KValue 子类型，而不是裸的 `Map` / `String`。
     *
     * 因此这里必须两步：① 解开外层 Obj 拿到内层 state；② 用 [Json.fromK] 把 KValue
     * 递归还原成原生 `String` / `List` / `Map`。只做 `(raw as? Map<*,*>)` 会恒为 null，
     * 插件永远读不到状态。
     */
    fun readState(args: KValue): Map<String, Any?> {
        val raw = (args as? KValue.Obj)?.value?.get("state")
        val stateK = when (raw) {
            is KValue.Obj -> raw
            is Map<*, *> -> KValue.of(raw.mapKeys { it.key.toString() })
            else -> return emptyMap()
        }
        @Suppress("UNCHECKED_CAST")
        return (Json.fromK(stateK) as? Map<String, Any?>) ?: emptyMap()
    }

    fun strList(state: Map<String, Any?>, key: String): List<String> {
        val v = state[key]
        return (v as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
    }

    /**
     * 布尔取值。
     *
     * 坑：状态经过 JSON 往返后，数字是 [Long]/[Double] 而不是 Int，
     * 布尔是 [Boolean]；直接 `as Int` 会抛 ClassCastException。
     * 这里统一宽松转换，插件侧不必各写一遍。
     */
    fun boolOf(state: Map<String, Any?>, key: String, default: Boolean = false): Boolean =
        when (val v = state[key]) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.equals("true", true) || v == "1"
            else -> default
        }

    /** 整数取值（宽松，见 [boolOf] 的类型说明）。 */
    fun intOf(state: Map<String, Any?>, key: String, default: Int = 0): Int =
        when (val v = state[key]) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: default
            is Boolean -> if (v) 1 else 0
            else -> default
        }

    /** 字符串取值。 */
    fun strOf(state: Map<String, Any?>, key: String, default: String = ""): String =
        state[key]?.toString() ?: default

    /** 对象数组取值：把 `List<Map>` 还原成可变的结构化列表（用于标签页 / 条目数组）。 */
    fun mapList(state: Map<String, Any?>, key: String): MutableList<MutableMap<String, Any?>> {
        val v = state[key] as? List<*> ?: return mutableListOf()
        return v.mapNotNull { it as? Map<*, *> }
            .map { row -> row.entries.associate { e -> e.key.toString() to e.value }.toMutableMap() }
            .toMutableList()
    }
}
