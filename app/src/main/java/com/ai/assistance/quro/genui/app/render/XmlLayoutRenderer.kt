package com.ai.assistance.quro.genui.app.render

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory
import java.io.ByteArrayInputStream

/**
 * XML 布局原生渲染引擎 —— 把 AI 写的 Android XML 布局**真实渲染**成原生控件。
 *
 * 核心立场（与 GenUI 的哲学一致）：**端上零约束，AI 自由发挥**。
 * 这里不做标签白名单、不做属性过滤 —— AI 想用什么控件、什么属性就用什么。
 * 端上的职责是"尽力把它渲染出来"，而不是"规定它能写什么"。
 *
 * 为什么不用 LayoutInflater：
 * LayoutInflater 需要「已编译的资源」（res/layout/xxx.xml 编进 APK 的 R 表），
 * 而 AI 的 XML 是运行时现写的 —— 没有资源 ID，inflate 会直接抛 InflateException。
 * 所以这里走**自解析 + 反射建 View**：
 *   1. 解析 XML DOM；
 *   2. 按标签名解析出**任意 Android 控件类**（内置映射 + 反射兜底）；
 *   3. 用反射把 XML 属性设置到控件上（setXxx / 字段 / 常见属性表）。
 * 效果与 LayoutInflater 一致：真原生控件、真原生绘制。
 *
 * 容错原则：
 * - 单个标签渲染失败 → 跳过它，继续渲染兄弟节点（不炸整棵树）；
 * - 属性设置失败 → 忽略该属性（不因为一个属性名写错就放弃整个控件）；
 * - 整体失败 → 返回带原因的占位视图，绝不白屏。
 */
object XmlLayoutRenderer {

    private const val MAX_DEPTH = 40
    private const val MAX_NODES = 800

    /**
     * 渲染结果。[view] 为根视图；[error] 非空表示解析失败。
     */
    data class Result(val view: View, val error: String? = null, val nodeCount: Int = 0)

    /**
     * 解析并构建 View 树。
     * @param ctx 用于创建 View 的上下文（最好是 Activity 的 Context，才有主题）
     */
    fun render(ctx: Context, xml: String): Result {
        if (xml.isBlank()) return Result(errorView(ctx, "XML 为空"), "empty")
        val root = runCatching { parse(xml) }.getOrElse { e ->
            return Result(errorView(ctx, "XML 解析失败：${e.message?.take(160)}"), e.message)
        }

        var count = 0
        val view: View = runCatching { build(ctx, root, 0) { count++ } }.getOrElse { e ->
            return Result(errorView(ctx, "布局构建失败：${e.message?.take(160)}"), e.message)
        } ?: return Result(errorView(ctx, "无法构建根标签 <${root.tagName}>"), "no root view")

        // 根容器铺满可用宽度，高度交内容决定
        view.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return Result(view, null, count)
    }

    // ---------- XML 解析 ----------

    private fun parse(xml: String): Element {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            // 关键修正：之前用 disallow-doctype-decl=true 硬拒任何 DOCTYPE，
            // 但 AI 生成原生布局时几乎必定带 <!DOCTYPE html>，于是每次都抛
            // "disallow-doctype-decl" 解析异常 → 画布只剩红字"原生布局渲染失败"。
            //
            // 正确做法：**允许 DOCTYPE**，但把"外部实体"这条 XXE 攻击面彻底关死：
            //   - external-general-entities / external-parameter-entities = false（不解析 SYSTEM/PUBLIC 外部实体）
            //   - load-external-dtd = false（不拉取外部 DTD 文件）
            //   - isExpandEntityReferences = false
            // 这样 <!DOCTYPE html> 这类无害声明能正常解析，而 XXE 风险被堵死。
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isExpandEntityReferences = false
            isNamespaceAware = false
        }
        var src = xml.trim().trimStart('\uFEFF')
        // 仍可能顺手包一层 Markdown 代码围栏（```xml ... ```），那不是合法 XML，剥掉。
        src = stripLeadingFence(src)
        // AI 常忘记 XML 声明；补一个，避免解析器报 "prolog 中不允许内容"
        val body = if (src.startsWith("<?xml")) src else "<?xml version=\"1.0\" encoding=\"utf-8\"?>$src"
        val doc = dbf.newDocumentBuilder().parse(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
        return doc.documentElement
    }

    /** 去掉开头的 Markdown 代码围栏（```xml / ``` / ~~~），只处理一次 */
    private fun stripLeadingFence(s: String): String {
        val f = s.trimStart()
        val m = Regex("^(```+|~~~+)\\s*(?:xml|XML|html|HTML)?\\s*\\n?", RegexOption.IGNORE_CASE).find(f)
        return if (m != null) f.substring(m.range.last + 1).trimStart() else s
    }

    // ---------- 视图构建 ----------

    private fun build(ctx: Context, el: Element, depth: Int, onNode: () -> Unit): View? {
        if (depth > MAX_DEPTH) return null
        onNode()

        val tag = el.tagName.substringAfterLast(':')
        val v: View = createView(ctx, tag) ?: return null
        applyAttrs(ctx, v, el)

        // —— 容器：递归子节点 ——
        if (v is ViewGroup) {
            for (i in 0 until el.childNodes.length) {
                val node = el.childNodes.item(i)
                if (node.nodeType != Node.ELEMENT_NODE) continue
                val childEl = node as Element
                val child = build(ctx, childEl, depth + 1, onNode) ?: continue
                // 关键：LayoutParams 的类型必须匹配【父容器】的类型，否则 FrameLayout 等
                // 在 measure/layout 时把子 params 强转成自己的 LayoutParams 会抛
                // ClassCastException，导致整块原生布局崩掉（表现为"渲染问题"）。
                runCatching { v.addView(child, layoutParamsFor(ctx, v, childEl)) }
            }
        } else if (v is TextView) {
            // 文本控件：text 属性优先，其次取元素文本内容
            if (v.text.isNullOrBlank()) {
                val t = attr(el, "text") ?: textContent(el)
                if (t.isNotBlank()) v.text = unescape(t)
            }
        }
        return v
    }

    /**
     * 按标签名创建控件 —— **无白名单**。
     * 先试内置短名表（覆盖 99% 常用控件，快且稳），
     * 再试反射（AI 写了完整类名或冷门控件也能建出来）。
     */
    private fun createView(ctx: Context, tag: String): View? {
        val short = tag.substringAfterLast('.').lowercase()

        // ① 短名 → 常用控件（覆盖绝大多数场景）
        builtinOf(short)?.let { return it(ctx) }
        // ② 反射：完整类名或 Android 标准控件全名
        reflectOf(ctx, tag)?.let { return it }
        // ③ 兜底：任何未知标签都当容器处理（AI 写错标签名也不至于整块消失）
        return FrameLayout(ctx)
    }

    /** 内置短名映射。这是"快捷方式"而非"白名单"——不在表里的走反射。 */
    private fun builtinOf(short: String): ((Context) -> View)? = when (short) {
        // —— 布局容器 ——
        "linearlayout" -> { c -> LinearLayout(c) }
        "framelayout" -> { c -> FrameLayout(c) }
        "relativelayout" -> { c -> android.widget.RelativeLayout(c) }
        "scrollview" -> { c -> android.widget.ScrollView(c) }
        "horizontalscrollview" -> { c -> android.widget.HorizontalScrollView(c) }
        "tablelayout" -> { c -> android.widget.TableLayout(c) }
        "tablerow" -> { c -> android.widget.TableRow(c) }
        "gridlayout" -> { c -> android.widget.GridLayout(c) }
        "viewgroup" -> { c -> FrameLayout(c) }
        // —— 基础控件 ——
        "view" -> { c -> View(c) }
        "space" -> { c -> android.widget.Space(c) }
        "textview" -> { c -> TextView(c) }
        "button" -> { c -> android.widget.Button(c) }
        "imageview" -> { c -> android.widget.ImageView(c) }
        "imagebutton" -> { c -> android.widget.ImageButton(c) }
        "edittext" -> { c -> android.widget.EditText(c) }
        "checkbox" -> { c -> android.widget.CheckBox(c) }
        "radiobutton" -> { c -> android.widget.RadioButton(c) }
        "radiogroup" -> { c -> android.widget.RadioGroup(c) }
        "switch" -> { c -> android.widget.Switch(c) }
        "togglebutton" -> { c -> android.widget.ToggleButton(c) }
        "seekbar" -> { c -> android.widget.SeekBar(c) }
        "ratingbar" -> { c -> android.widget.RatingBar(c) }
        "progressbar" -> { c -> android.widget.ProgressBar(c) }
        "spinner" -> { c -> android.widget.Spinner(c) }
        "chronometer" -> { c -> android.widget.Chronometer(c) }
        "webview" -> { c -> android.webkit.WebView(c) }
        "progressbar_horizontal" -> { c ->
            android.widget.ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal) }
        else -> null
    }

    /** 反射创建：支持完整类名，也支持省略 "android.widget." 前缀的写法 */
    private fun reflectOf(ctx: Context, tag: String): View? {
        val candidates = listOf(
            tag,
            "android.widget.$tag",
            "android.view.$tag",
            "androidx.appcompat.widget.$tag",
            "com.google.android.material.$tag"
        )
        for (name in candidates) {
            runCatching {
                val cls = Class.forName(name)
                if (!View::class.java.isAssignableFrom(cls)) return@runCatching
                val v = cls.getConstructor(Context::class.java).newInstance(ctx)
                if (v is View) return v
            }
        }
        return null
    }

    // ---------- 属性应用：尽力而为，失败即忽略 ----------

    private fun applyAttrs(ctx: Context, v: View, el: Element) {
        val attrs = el.attributes
        for (i in 0 until attrs.length) {
            val name = attrs.item(i).nodeName
            val raw = attrs.item(i).nodeValue ?: continue
            val key = name.substringAfterLast(':')
            runCatching { applyAttr(ctx, v, key, raw) }
        }

        // 通用外观：文字色 / 背景色（AI 若显式给了才覆盖控件默认外观）
        if (v is TextView) {
            colorOf(attr(el, "textColor"))?.let { v.setTextColor(it) }
            if (v.textColors == null) v.setTextColor(0xFFE8EAF0.toInt())
        }
        colorOf(attr(el, "background") ?: attr(el, "backgroundColor"))?.let { c ->
            v.background = GradientDrawable().apply {
                cornerRadius = dp(ctx, attr(el, "cornerRadius")?.removeSuffix("dp")?.toFloatOrNull() ?: 0f)
                setColor(c)
            }
        }
    }

    /** 单属性应用：反射 setter 优先，其次常见属性表 */
    private fun applyAttr(ctx: Context, v: View, key: String, raw: String) {
        when (key) {
            // —— 布局尺寸：不设 ViewGroup.LayoutParams（由父容器决定），
            //    但根节点/独立使用时给它一个合理值 ——
            "layout_width", "layout_height", "id", "layout_weight", "layout_gravity",
            "layout_margin", "layout_marginTop", "layout_marginBottom",
            "layout_marginLeft", "layout_marginRight", "layout_marginStart", "layout_marginEnd" -> {
                // 这些在 layoutParamsFor 里处理
                return
            }
            "orientation" -> {
                if (v is LinearLayout) v.orientation =
                    if (raw.equals("horizontal", true)) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                return
            }
            // gravity 的对齐语义按容器类型分派：
            //  - LinearLayout：自身的 gravity 决定子元素对齐
            //  - FrameLayout：子元素对齐由 LayoutParams.gravity 决定（容器本身无此属性）
            //  - 其余：反射尽力尝试，设不上就跳过（不影响其它属性生效）
            "gravity" -> {
                when (v) {
                    is LinearLayout -> v.gravity = gravityOf(raw)
                    is FrameLayout -> {
                        val lp = v.layoutParams as? FrameLayout.LayoutParams
                        if (lp != null) { lp.gravity = gravityOf(raw); v.layoutParams = lp }
                    }
                    else -> reflectSet(v, "gravity", raw)
                }
                return
            }
            "padding" -> {
                val p = dim(ctx, raw) ?: 0
                v.setPadding(p, p, p, p); return
            }
            "paddingLeft", "paddingStart" -> {
                v.setPadding(dim(ctx, raw) ?: v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom); return
            }
            "paddingTop" -> {
                v.setPadding(v.paddingLeft, dim(ctx, raw) ?: v.paddingTop, v.paddingRight, v.paddingBottom); return
            }
            "paddingRight", "paddingEnd" -> {
                v.setPadding(v.paddingLeft, v.paddingTop, dim(ctx, raw) ?: v.paddingRight, v.paddingBottom); return
            }
            "paddingBottom" -> {
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, dim(ctx, raw) ?: v.paddingBottom); return
            }
            "visibility" -> {
                v.visibility = when (raw.lowercase()) {
                    "gone" -> View.GONE
                    "invisible" -> View.INVISIBLE
                    else -> View.VISIBLE
                }
                return
            }
            "text" -> { if (v is TextView) v.text = unescape(raw); return }
            "textSize" -> {
                if (v is TextView) {
                    val n = raw.removeSuffix("sp").removeSuffix("dp").toFloatOrNull()
                    if (n != null) v.setTextSize(TypedValue.COMPLEX_UNIT_SP, n)
                }
                return
            }
            "textStyle" -> {
                if (v is TextView) {
                    var s = 0
                    raw.lowercase().split('|').forEach {
                        s = s or when (it.trim()) {
                            "bold" -> android.graphics.Typeface.BOLD
                            "italic" -> android.graphics.Typeface.ITALIC
                            else -> 0
                        }
                    }
                    if (s != 0) v.setTypeface(v.typeface, s)
                }
                return
            }
            "textColor", "hint", "maxLines", "maxWidth", "minLines", "letterSpacing",
            "lineSpacingExtra", "lineSpacingMultiplier", "textAlignment" -> {
                if (v is TextView) applyTextAttr(ctx, v, key, raw); return
            }
            "hintTextColor" -> {
                if (v is TextView) colorOf(raw)?.let { v.setHintTextColor(it) }; return
            }
            "checked" -> {
                val b = raw.equals("true", true)
                when (v) {
                    is android.widget.CheckBox -> v.isChecked = b
                    is android.widget.Switch -> v.isChecked = b
                    is android.widget.RadioButton -> v.isChecked = b
                    is android.widget.ToggleButton -> v.isChecked = b
                    else -> {}
                }
                return
            }
            "max", "progress" -> {
                val n = raw.toIntOrNull() ?: return
                when (v) {
                    is android.widget.ProgressBar -> if (key == "max") v.max = n else v.progress = n
                    is android.widget.SeekBar -> if (key == "max") v.max = n else v.progress = n
                    is android.widget.RatingBar -> if (key == "max") v.numStars = n else v.rating = n.toFloat()
                    else -> {}
                }
                return
            }
            "src" -> return   // 无资源表，忽略图片资源引用（AI 可用 HTML 侧画图）
            "scaleType" -> {
                if (v is android.widget.ImageView) runCatching {
                    v.scaleType = android.widget.ImageView.ScaleType.valueOf(raw.uppercase())
                }
                return
            }
            "clickable", "enabled", "focusable", "selected", "alpha", "rotation",
            "scaleX", "scaleY", "elevation", "translationX", "translationY" -> {
                applyViewAttr(v, key, raw); return
            }
        }

        // —— 反射兜底：set + 首字母大写（如 layout_span 之外的自定义属性）——
        reflectSet(v, key, raw)
    }

    private fun applyTextAttr(ctx: Context, v: TextView, key: String, raw: String) = when (key) {
        "textColor" -> colorOf(raw)?.let { v.setTextColor(it) }
        "hint" -> v.hint = unescape(raw)
        "maxLines" -> v.maxLines = raw.toIntOrNull() ?: Int.MAX_VALUE
        "minLines" -> v.minLines = raw.toIntOrNull() ?: 0
        "maxWidth" -> dim(ctx, raw)?.let { v.maxWidth = it }
        "letterSpacing" -> raw.toFloatOrNull()?.let { v.letterSpacing = it }
        "lineSpacingExtra" -> dim(ctx, raw)?.let { v.setLineSpacing(it.toFloat(), 1f) }
        "lineSpacingMultiplier" -> raw.toFloatOrNull()?.let { v.setLineSpacing(0f, it) }
        "textAlignment" -> runCatching { v.textAlignment = View.TEXT_ALIGNMENT_VIEW_START }
        else -> Unit
    }

    private fun applyViewAttr(v: View, key: String, raw: String) {
        when (key) {
            "clickable" -> v.isClickable = raw.equals("true", true)
            "enabled" -> v.isEnabled = raw.equals("true", true)
            "focusable" -> v.isFocusable = raw.equals("true", true)
            "selected" -> v.isSelected = raw.equals("true", true)
            "alpha" -> raw.toFloatOrNull()?.let { v.alpha = it }
            "rotation" -> raw.removeSuffix("deg").toFloatOrNull()?.let { v.rotation = it }
            "scaleX" -> raw.toFloatOrNull()?.let { v.scaleX = it }
            "scaleY" -> raw.toFloatOrNull()?.let { v.scaleY = it }
            "elevation" -> raw.removeSuffix("dp").toFloatOrNull()?.let { v.elevation = it }
            "translationX" -> raw.removeSuffix("dp").toFloatOrNull()?.let { v.translationX = it }
            "translationY" -> raw.removeSuffix("dp").toFloatOrNull()?.let { v.translationY = it }
            else -> Unit
        }
    }

    /** 反射设置属性：set<Key> 若存在则调用 */
    private fun reflectSet(v: View, key: String, raw: String) {
        val setter = "set" + key.replaceFirstChar { it.uppercaseChar() }
        val cls = v.javaClass
        // 依次尝试 String / Int / Float / Boolean 单参 setter
        runCatching {
            cls.getMethod(setter, String::class.java).invoke(v, raw)
            return
        }
        runCatching {
            cls.getMethod(setter, Int::class.javaPrimitiveType).invoke(v, raw.toIntOrNull() ?: return)
            return
        }
        runCatching {
            cls.getMethod(setter, Float::class.javaPrimitiveType).invoke(v, raw.toFloatOrNull() ?: return)
            return
        }
        runCatching {
            cls.getMethod(setter, Boolean::class.javaPrimitiveType).invoke(v, raw.equals("true", true))
        }
    }

    // ---------- LayoutParams ----------

    /**
     * 按【父容器】类型返回正确的 LayoutParams 子类。
     *
     * 之前这里永远返回 LinearLayout.LayoutParams —— 在非 LinearLayout 父容器（FrameLayout /
     * RelativeLayout / GridLayout / ScrollView 等）下有三个后果：
     *  1) FrameLayout 等会在 layout 时把子 params 强转为自己的 LayoutParams，类型不匹配直接
     *     ClassCastException → 整块原生布局渲染失败（最典型的"渲染问题"）；
     *  2) layout_gravity 设到了 LinearLayout.LayoutParams 上，而 FrameLayout 读的是自己的
     *     LayoutParams.gravity → 子元素不居中 / 不对齐；
     *  3) layout_weight 在非 LinearLayout 父下被忽略。
     *
     * 现在根据 parent 的实际类型分派，margin/gravity 才真正生效、也不会再崩。
     */
    private fun layoutParamsFor(ctx: Context, parent: ViewGroup, el: Element): ViewGroup.LayoutParams {
        val w = sizeOf(ctx, attr(el, "layout_width"))
        val h = sizeOf(ctx, attr(el, "layout_height"))
        val weight = attr(el, "layout_weight")?.toFloatOrNull()
        val grav = gravityOf(attr(el, "layout_gravity"))

        val lp: ViewGroup.LayoutParams = when {
            parent is LinearLayout ->
                LinearLayout.LayoutParams(w, h).apply {
                    if (weight != null) this.weight = weight
                    this.gravity = grav
                }
            parent is android.widget.GridLayout ->
                android.widget.GridLayout.LayoutParams().apply {
                    this.width = w; this.height = h
                    setGravity(grav)
                }
            parent is android.widget.RelativeLayout ->
                // RelativeLayout.LayoutParams 无 gravity 字段；align 规则由 AI 的 layout_* 属性决定，
                // 这里只保证尺寸/margin 类型正确，避免 ClassCastException。
                android.widget.RelativeLayout.LayoutParams(w, h)
            parent is FrameLayout ->
                FrameLayout.LayoutParams(w, h).apply { this.gravity = grav }
            else ->
                // 未知父容器（自定义 ViewGroup 等）：用通用基类，能力弱但绝不崩
                ViewGroup.LayoutParams(w, h)
        }

        // margin 只在 MarginLayoutParams 子类上有效，统一在此设置
        if (lp is ViewGroup.MarginLayoutParams) {
            val m = attr(el, "layout_margin")
            if (m != null) {
                val mm = dim(ctx, m) ?: 0
                lp.setMargins(mm, mm, mm, mm)
            } else {
                lp.setMargins(
                    dim(ctx, attr(el, "layout_marginLeft") ?: attr(el, "layout_marginStart")) ?: 0,
                    dim(ctx, attr(el, "layout_marginTop")) ?: 0,
                    dim(ctx, attr(el, "layout_marginRight") ?: attr(el, "layout_marginEnd")) ?: 0,
                    dim(ctx, attr(el, "layout_marginBottom")) ?: 0
                )
            }
        }
        return lp
    }

    // ---------- 工具 ----------

    private fun sizeOf(ctx: Context, raw: String?): Int = when (raw?.lowercase()) {
        null -> ViewGroup.LayoutParams.WRAP_CONTENT
        "match_parent", "fill_parent" -> ViewGroup.LayoutParams.MATCH_PARENT
        else -> dim(ctx, raw) ?: ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private fun dim(ctx: Context, raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().lowercase()
        val n = s.removeSuffix("dp").removeSuffix("dip").removeSuffix("sp").removeSuffix("px")
            .toFloatOrNull() ?: return null
        return if (s.endsWith("sp"))
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, n, ctx.resources.displayMetrics).toInt()
        else dp(ctx, n).toInt()
    }

    private fun dp(ctx: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    private fun gravityOf(g: String?): Int {
        if (g.isNullOrBlank()) return Gravity.START or Gravity.TOP
        var r = 0
        g.lowercase().split('|', ' ').forEach {
            r = r or when (it.trim()) {
                "center" -> Gravity.CENTER
                "center_horizontal" -> Gravity.CENTER_HORIZONTAL
                "center_vertical" -> Gravity.CENTER_VERTICAL
                "start", "left" -> Gravity.START
                "end", "right" -> Gravity.END
                "top" -> Gravity.TOP
                "bottom" -> Gravity.BOTTOM
                "fill_horizontal" -> Gravity.FILL_HORIZONTAL
                "fill_vertical" -> Gravity.FILL_VERTICAL
                else -> 0
            }
        }
        return if (r == 0) Gravity.START or Gravity.TOP else r
    }

    private fun colorOf(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        if (!s.startsWith("#") && s !in namedColors) return null
        return runCatching {
            if (s.startsWith("#")) Color.parseColor(s) else namedColors.getValue(s)
        }.getOrNull()
    }

    /** 常用颜色名（Android 系统色 + CSS 常用色），让 AI 写 "white" / "red" 也能生效 */
    private val namedColors: Map<String, Int> = mapOf(
        "black" to 0xFF000000.toInt(), "white" to 0xFFFFFFFF.toInt(),
        "red" to 0xFFFF0000.toInt(), "green" to 0xFF00FF00.toInt(),
        "blue" to 0xFF0000FF.toInt(), "yellow" to 0xFFFFFF00.toInt(),
        "cyan" to 0xFF00FFFF.toInt(), "magenta" to 0xFFFF00FF.toInt(),
        "gray" to 0xFF888888.toInt(), "grey" to 0xFF888888.toInt(),
        "lightgray" to 0xFFCCCCCC.toInt(), "darkgray" to 0xFF444444.toInt(),
        "orange" to 0xFFFFA500.toInt(), "purple" to 0xFF800080.toInt(),
        "pink" to 0xFFFFC0CB.toInt(), "transparent" to 0x00000000
    )

    private fun attr(el: Element, name: String): String? {
        el.getAttribute(name).takeIf { it.isNotBlank() }?.let { return it }
        listOf("android", "app", "tools", "aapt").forEach { p ->
            el.getAttribute("$p:$name").takeIf { it.isNotBlank() }?.let { return it }
        }
        val attrs = el.attributes
        for (i in 0 until attrs.length) {
            if (attrs.item(i).nodeName.substringAfterLast(':') == name) {
                val v = attrs.item(i).nodeValue
                if (!v.isNullOrBlank()) return v
            }
        }
        return null
    }

    private fun textContent(el: Element): String {
        var s = ""
        for (i in 0 until el.childNodes.length) {
            val c = el.childNodes.item(i)
            if (c.nodeType == Node.TEXT_NODE) s += c.nodeValue
        }
        return s.trim()
    }

    /** DOM 文本节点不会自动还原实体，这里补上 */
    private fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("\\n", "\n")

    /** 解析失败时的占位视图：让用户看见原因，而不是白屏 */
    private fun errorView(ctx: Context, msg: String): View =
        TextView(ctx).apply {
            text = "原生布局渲染失败\n$msg"
            setTextColor(0xFFE06C75.toInt())
            textSize = 13f
            setPadding(32, 32, 32, 32)
        }
}
