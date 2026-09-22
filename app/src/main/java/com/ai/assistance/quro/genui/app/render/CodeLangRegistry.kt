package com.ai.assistance.quro.genui.app.render

/**
 * AI 画界面的技术栈注册表 —— 渲染通道的【唯一事实来源】。
 *
 * GenUI 的核心是：用户说一句话，AI 现场写出一个**真实可用的界面**，端上渲染出来。
 * 关键点在于"渲染"：AI 用什么技术表达这个界面，端上就必须有什么渲染器把它画出来。
 *
 * 历史版本只有一条渲染通道（WebView），AI 只能用 HTML/CSS/JS 画界面。
 * 本表把"AI 可选的界面技术栈"集中定义，与 [RendererRegistry] 的通道一一对应：
 * 提示词里教模型"你有哪些技术可选、什么场景选哪个"，
 * 端上则按同 id 分派到对应渲染器 —— 两处不会脱节。
 *
 * 技术栈分两类：
 *  - **可渲染**（renderable）：端上有真渲染器，AI 写的东西能变成真实界面。
 *  - **可呈现**（presentable）：端上没有执行环境（手机跑不了 Python/C++ 编译器），
 *    但可以作为**界面题材**——AI 用 HTML 画一个代码视图/演示器，把源码作为内容呈现。
 *    这不是"降级"，很多真实需求就是"给我看一段代码 / 做个算法演示"。
 */
object CodeLangRegistry {

    /** 渲染通道类型 */
    enum class Channel(val label: String) {
        WEB("网页通道"),        // AI 写 HTML/CSS/JS → WebView 渲染
        NATIVE_XML("原生布局通道"), // AI 写 Android XML 布局 → LayoutInflater 真实渲染
        COMPOSE("Compose 通道"),   // AI 写 Compose 描述 → Compose 渲染器映射为真组件
        RENDERABLE("原生绘制通道"), // AI 写绘制指令 JSON → GenCanvas 解释为真实原生画面
        PRESENT("内容呈现通道")    // AI 写任意语言源码 → 作为界面内容呈现（代码视图/演示）
    }

    /**
     * 一种 AI 可用的界面技术栈。
     *
     * @param id 提示词里的技术标记（`<!--stack:xxx-->`）与桥 API 标识
     * @param name 展示名
     * @param channel 对应渲染通道
     * @param exts 该技术的典型文件扩展名（内容呈现时用于语法高亮归类）
     * @param markers 语法高亮关键字/标记（AI 自绘高亮或端上高亮使用）
     * @param canRender 端上能否真实渲染成界面（false = 只能作为内容呈现）
     * @param how AI 该怎么写（产出契约：写法、结构、注意事项）
     * @param whenToUse 什么场景该选它
     */
    data class Stack(
        val id: String,
        val name: String,
        val channel: Channel,
        val exts: List<String>,
        val markers: List<String>,
        val canRender: Boolean,
        val how: String,
        val whenToUse: String
    )

    // ---------- 声明顺序 = 提示词里的推荐优先级 ----------

    val all: List<Stack> = listOf(

        Stack(
            id = "html",
            name = "HTML / CSS / JS（网页通道）",
            channel = Channel.WEB,
            exts = listOf(".html"),
            markers = emptyList(),
            canRender = true,
            how = "直接写完整 HTML 文档，CSS 与 JS 内联。可用下方列出的本地运行时（vue/react/echarts/gsap/anime/three/countup/mermaid）。",
            whenToUse = "默认首选。绝大多数界面——展示页、表单、清单、图表、小工具——用它最快最灵活。"
        ),

        Stack(
            id = "xml",
            name = "Android XML 布局（原生通道）",
            channel = Channel.NATIVE_XML,
            exts = listOf(".xml"),
            markers = listOf(
                "ConstraintLayout", "LinearLayout", "FrameLayout", "RelativeLayout", "ScrollView",
                "TextView", "ImageView", "Button", "EditText", "Switch", "SeekBar", "ProgressBar",
                "RecyclerView", "CardView", "android:id", "android:layout_width", "android:text",
                "app:layout_constraint", "match_parent", "wrap_content"
            ),
            canRender = true,
            how = """
在 <body> 内写一个 <script type="text/xml-layout"> 块，内容就是标准 Android XML 布局源码：
  <script type="text/xml-layout">
  <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
      android:layout_width="match_parent" android:layout_height="wrap_content"
      android:orientation="vertical" android:padding="16dp">
      <TextView android:id="@+id/title" android:layout_width="wrap_content"
          android:layout_height="wrap_content" android:text="标题" android:textSize="20sp"/>
  </LinearLayout>
  </script>
端上会把你写的 XML **真实渲染成原生控件**（不是网页仿真），然后嵌进你的页面。
你写什么控件、什么属性都行——TextView/Button/EditText/Switch/SeekBar/ProgressBar/
CheckBox/RadioGroup/Spinner/ScrollView/TableLayout/GridLayout 都能建出来；
写了完整类名（如 androidx.cardview.widget.CardView）端上也会反射创建。
自定义属性端上会尽力用反射 set 上去，设不上就忽略，不会报错。
页面里用 id="gen-xml" 的容器标出渲染位置（不标的话端上会显示在页面顶部）。
            """.trimIndent(),
            whenToUse = "用户要'安卓原生界面'、'原生控件'，或需要系统级控件的真实观感时。"
        ),

        Stack(
            id = "compose",
            name = "Jetpack Compose（声明式通道）",
            channel = Channel.COMPOSE,
            exts = listOf(".kt"),
            markers = listOf(
                "Column", "Row", "Box", "Spacer", "LazyColumn", "LazyRow", "Card", "Text",
                "Button", "Icon", "TextField", "Switch", "Slider", "Divider", "Surface",
                "MaterialTheme", "colorScheme", "typography", "Modifier", "padding", "fillMaxWidth",
                "Arrangement", "Alignment"
            ),
            canRender = true,
            how = """
在 <body> 内写一个 id="gen-compose" 的 <script type="text/x-compose"> 块，用 JSON 描述 Compose 组件树：
  <script type="text/x-compose">
  {
    "type": "Column",
    "modifier": {"padding": 16, "fillMaxWidth": true},
    "children": [
      {"type": "Text", "text": "标题", "style": "headlineMedium"},
      {"type": "Card", "children": [
        {"type": "Row", "children": [
          {"type": "Icon", "name": "star"},
          {"type": "Text", "text": "卡片内容"}
        ]}
      ]}
    ]
  }
  </script>
端上把这个描述映射为**真实的 Compose 组件**渲染（Material 3 主题）。
支持的组件与属性见提示词"Compose 组件表"。事件用 "action" 字段声明（如 {"type":"Button","text":"提交","action":"submit"}），
端上渲染后回传 window.addEventListener('mo:compose', ...)。
            """.trimIndent(),
            whenToUse = "用户要'Material Design 界面'、'Compose 风格'、'现代安卓界面'时。"
        ),

        Stack(
            id = "canvas",
            name = "GenCanvas 绘制（原生画布）",
            channel = Channel.RENDERABLE,
            exts = listOf(".json"),
            markers = listOf("ops", "flex", "brush", "rect", "rrect", "circle", "text", "svg", "clip", "group", "linear", "radial", "anim", "event"),
            canRender = true,
            how = """
在 <body> 内写一个 id="gen-canvas" 的 <script type="text/x-canvas"> 块，用 JSON 描述一幅**绘制画面**：
  <script type="text/x-canvas">
  {
    "root": {
      "flex": {"w": "100%", "h": "100%", "pad": 24, "gap": 16, "justify": "center"},
      "ops": [{"op": "rect", "brush": {"solid": "#0F0F14"}}],
      "children": [
        {"flex": {"w": 72, "h": 72}, "ops": [{"op": "clip", "shape": "circle",
          "ops": [{"op": "rect", "brush": {"linear": {"colors": ["#7C4DFF", "#FF4D9D"]}}}]}]},
        {"flex": {"w": "100%", "h": 56}, "ops": [
          {"op": "rrect", "r": 28, "brush": {"linear": {"colors": ["#7C4DFF", "#FF4D9D"]}},
           "shadow": {"color": "#667C4DFF", "blur": 20, "dy": 8}},
          {"op": "text", "v": "开始", "size": 16, "weight": "medium", "color": "#FFF", "align": "center"}
        ]}
      ]
    }
  }
  </script>
端上把这份 JSON 解释为**真实的安卓原生画面**（Yoga 布局 → Canvas 绘制 → 真动画 → 真命中测试），
没有 WebView、没有组件白名单、没有编译。op 集（rect/rrect/circle/oval/path/line/arc/points/image/
text/svg/clip/group）自由组合，复杂图形一律退化成 svg op。完整字段见 GenCanvas DSL。
事件用 "event":{"onClick":"名字"} 声明，端上回传 window.dispatchEvent(new CustomEvent('mo:canvas', {detail:名字}))。
            """.trimIndent(),
            whenToUse = "用户要'极致的自定义视觉'、'非标准控件'、'纯绘制质感画面'（仪表盘/信息图/插画风 UI）时。"
        ),

        Stack(
            id = "kotlin",
            name = "Kotlin 代码（呈现）",
            channel = Channel.PRESENT,
            exts = listOf(".kt", ".kts"),
            markers = listOf(
                "fun", "val", "var", "class", "object", "interface", "data class", "sealed",
                "when", "suspend", "Flow", "StateFlow", "coroutineScope", "launch", "async",
                "runCatching", "let", "apply", "use", "require", "@Composable", "remember",
                "mutableStateOf", "LaunchedEffect", "Modifier", "private", "override"
            ),
            canRender = false,
            how = "用 HTML 画一个代码视图（深色背景 + 行号 + 等宽字体 + 语法高亮 + 一键复制按钮），把 Kotlin 源码作为内容呈现。",
            whenToUse = "用户要'看 Kotlin 代码'、'写个 Kotlin 示例'、'讲解 Kotlin'，或界面题材本身就是代码。"
        ),

        Stack(
            id = "java",
            name = "Java 代码（呈现）",
            channel = Channel.PRESENT,
            exts = listOf(".java"),
            markers = listOf(
                "public", "private", "protected", "static", "final", "class", "interface",
                "extends", "implements", "abstract", "void", "new", "this", "super",
                "try", "catch", "synchronized", "@Override", "Optional", "Stream"
            ),
            canRender = false,
            how = "同 Kotlin 呈现方式：HTML 代码视图 + 高亮 + 复制。",
            whenToUse = "用户明确要 Java 代码/示例时。"
        ),

        Stack(
            id = "cpp",
            name = "C / C++ 代码（呈现）",
            channel = Channel.PRESENT,
            exts = listOf(".cpp", ".cc", ".c", ".h", ".hpp"),
            markers = listOf(
                "#include", "#define", "namespace", "extern", "JNIEXPORT", "JNICALL",
                "jobject", "jstring", "JNIEnv", "std::", "auto", "constexpr", "template",
                "struct", "class", "nullptr", "malloc", "memcpy"
            ),
            canRender = false,
            how = "同 Kotlin 呈现方式。JNI/NDK 代码可配一个结构说明图（用 mermaid）。",
            whenToUse = "用户要 C/C++、NDK、JNI、性能相关代码或讲解时。"
        ),

        Stack(
            id = "python",
            name = "Python 代码（呈现）",
            channel = Channel.PRESENT,
            exts = listOf(".py"),
            markers = listOf(
                "def", "class", "import", "from", "return", "yield", "async", "await",
                "lambda", "try", "except", "finally", "with", "as", "None", "True", "False",
                "self", "dataclass", "typing", "pathlib", "argparse"
            ),
            canRender = false,
            how = "同 Kotlin 呈现方式。可配「代码 + 运行结果」双栏，或算法步骤可视化。",
            whenToUse = "用户要 Python 脚本、算法、数据处理、讲解 Python 时。"
        )
    )

    /** 可真实渲染的通道（AI 可用它们做界面骨架） */
    val renderable: List<Stack> get() = all.filter { it.canRender }

    fun byId(id: String): Stack? = all.find { it.id == id }

    /**
     * 生成提示词里的"界面技术栈"段落。
     * 由本表派生：加一种技术栈只改这里一处。
     *
     * 立场：这里描述的是**端上有什么渲染能力**，而不是"AI 只能写这些"。
     * AI 在每套技术栈内部想怎么写就怎么写，端上尽力渲染。
     */
    fun promptSection(): String = buildString {
        appendLine("画这个界面时，你可以从下列技术栈里选。**默认用 html**——只有在它确实更合适时才换。")
        appendLine("这些技术栈可以在**同一个界面里混用**：HTML 负责整体排版与图表，")
        appendLine("原生控件负责需要系统级质感的部分（表单、开关、滑杆），各取所长。")
        appendLine()
        renderable.forEach { s ->
            appendLine("## `<!--stack:${s.id}-->` ${s.name}  【端上会真实渲染】")
            appendLine("   何时用：${s.whenToUse}")
            appendLine("   怎么写：")
            s.how.lines().forEach { appendLine("     $it") }
            appendLine()
        }
        appendLine("## 以下技术栈端上不能执行，但可作为**界面题材**呈现（代码视图/演示）")
        appendLine("说明：这些语言的源码端上无法编译执行。它们的价值在于**成为界面的内容**——")
        appendLine("比如做一个代码讲解页、算法演示器、JNI 结构说明图。你要展示代码时有两种写法：")
        appendLine()
        appendLine("**推荐**：自己用 HTML 画代码视图（深色块 + 行号 + 等宽字体 + 高亮 + 复制按钮），")
        appendLine("           风格完全由你定，跟整页设计语言统一。")
        appendLine("**省事**：直接给裸代码块，端上会自动接管渲染成代码视图（行号 / 高亮 / 复制）：")
        appendLine("         <script type=\"text/x-code\" data-lang=\"kotlin\">你的代码</script>")
        appendLine("         data-lang 可取：${all.filter { !it.canRender }.joinToString(" / ") { it.id }}")
        appendLine("         端上高亮器认这些关键字：${all.filter { !it.canRender }.flatMap { it.markers }.take(40).joinToString(" ")}")
        appendLine()
        appendLine("**不要**把整段代码直接裸贴进 <pre> 里就完事——那是没设计过的页面。")
        appendLine()
        all.filter { !it.canRender }.forEach { s ->
            appendLine("### `<!--stack:${s.id}-->` ${s.name}")
            appendLine("   何时用：${s.whenToUse}")
            appendLine("   怎么写：${s.how}")
            appendLine("   高亮关键字参考：${s.markers.take(24).joinToString(" ")}")
            appendLine()
        }
        appendLine("### Compose 通道可用组件表")
        appendLine(ComposeDescRenderer.componentHelp)
    }
}
