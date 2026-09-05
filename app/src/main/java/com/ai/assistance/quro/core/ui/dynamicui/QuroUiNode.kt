package com.ai.assistance.quro.core.ui.dynamicui

/**
 * ZorvAI 动态 UI 节点模型（参照 Kai `ui/dynamicui` 设计重写）。
 *
 * 与 Kai 一致采用「AI 输出 UI DSL → 解析 → 原生渲染」三段式：
 * AI 在回复里写 ```quro-ui 代码块，解析器转成节点树，Compose 直接渲染成真实原生控件，
 * 而不是内嵌 HTML/WebView —— 因此可交互、可回传状态给模型。
 *
 * 设计约束（与 Kai 保持一致的容错哲学）：
 *  - 每个字段都允许缺失，解析时回落默认值，单个节点坏掉不影响整棵树；
 *  - id 用于状态绑定与 collectFrom 取值，交互节点必须有 id；
 *  - 颜色统一 #RRGGBB / #AARRGGBB 字符串，由 [QuroUiColor.parse] 兜底解析。
 *
 * v1.0.83 新增通用样式系统（[QuroUiStyle]）：任意节点都能挂任意视觉样式，让 AI
 * 像写 Compose 一样自由描述界面。模型输出始终是「数据」不是「代码」（红线：
 * 端上绝不执行 AI 代码），因此再自由的样式也只是声明、会被忠实渲染成原生控件。
 */

/** 所有 UI 节点的基类型。 */
sealed interface QuroUiNode {
    val id: String?
    /** 通用视觉样式（可选）。[QuroUiStyle] 见文件末。 */
    val style: QuroUiStyle? get() = null
}

// =============================================================================================
// 布局节点
// =============================================================================================

/** 纵向布局。 */
data class QuroColumnNode(
    override val id: String? = null,
    override val style: QuroUiStyle? = null,
    val children: List<QuroUiNode> = emptyList(),
    val spacing: Int? = null,
    val padding: Int? = null,
    val horizontalAlign: String? = null, // start | center | end
    val scrollable: Boolean = false,
    val weight: Float? = null,
) : QuroUiNode

/** 横向布局。 */
data class QuroRowNode(
    override val id: String? = null,
    override val style: QuroUiStyle? = null,
    val children: List<QuroUiNode> = emptyList(),
    val spacing: Int? = null,
    val padding: Int? = null,
    val verticalAlign: String? = null, // top | center | bottom
    val scrollable: Boolean = false,
    val weight: Float? = null,
) : QuroUiNode

/** 堆叠布局（层叠）。当携带视觉样式（背景/圆角/边框）时渲染器会当作竖向列容器处理；
 * 否则保持原本「子节点层叠重叠」语义（AI 用 box 做浮层/叠放时）。 */
data class QuroBoxNode(
    override val id: String? = null,
    override val style: QuroUiStyle? = null,
    val children: List<QuroUiNode> = emptyList(),
    val weight: Float? = null,
) : QuroUiNode

/** 卡片容器（带圆角与阴影）。 */
data class QuroCardNode(
    override val id: String? = null,
    override val style: QuroUiStyle? = null,
    val children: List<QuroUiNode> = emptyList(),
    val title: String? = null,
    val padding: Int? = null,
    val cornerRadius: Int? = null,
    val onClick: QuroUiAction? = null,
    val weight: Float? = null,
) : QuroUiNode

/**
 * 多 pane 布局容器：把多个子区块排成「并排」或「竖排」。
 *
 * - direction=auto（默认）：跟随 surface 尺寸档位 —— Expanded（宽屏/平板/分屏）并排，否则竖排。
 *   这正是「WindowSizeClass 多 pane 切换」的落地：AI 只声明一个 pane 容器，客户端按屏幕自动决定横竖。
 * - direction=row / column：强制横排 / 竖排，不受档位影响。
 *
 * 每个子区块在渲染时各自包一层 SurfaceHost(designWidthDp=360)，把 AI 的 360dp 设计稿等比映射到
 * 自己所占的那一格宽度 —— 并排时每格更窄但内容照样不溢出，竖排时每格满宽。
 */
data class QuroPaneNode(
    override val id: String? = null,
    override val style: QuroUiStyle? = null,
    val children: List<QuroUiNode> = emptyList(),
    val direction: String? = null, // auto | row | column
    val spacing: Int? = null,
    val padding: Int? = null,
) : QuroUiNode

// =============================================================================================
// 内容节点
// =============================================================================================

/** 文本。typography: title | headline | body | caption | label（沿用旧 style 字符串语义）。 */
data class QuroTextNode(
    override val id: String? = null,
    override val style: QuroUiStyle? = null,
    val value: String = "",
    val typography: String? = null, // 旧字段名 style，表示排版级别（title/headline/body/caption/label）
    val bold: Boolean = false,
    val italic: Boolean = false,
    val color: String? = null,
    val size: Int? = null,
    val maxLines: Int? = null,
    val align: String? = null, // start | center | end
) : QuroUiNode

/** 图片（网络 URL 或 data: base64）。 */
data class QuroImageNode(
    override val id: String? = null,
    override val style: QuroUiStyle? = null,
    val url: String = "",
    val alt: String? = null,
    val height: Int? = null,
    val aspectRatio: Float? = null,
    val cornerRadius: Int? = null,
) : QuroUiNode

/** 图标（Material 图标名，如 "star" / "settings"）。 */
data class QuroIconNode(
    override val id: String? = null,
    override val style: QuroUiStyle? = null,
    val name: String = "info",
    val size: Int? = null,
    val tint: String? = null,
    val description: String? = null,
) : QuroUiNode

/** 徽标/胶囊标签。 */
data class QuroBadgeNode(
    override val id: String? = null,
    override val style: QuroUiStyle? = null,
    val text: String = "",
    val color: String? = null,
    val background: String? = null,
) : QuroUiNode

/** 进度条。progress 取值 0.0–1.0；progress 为 null 时显示不确定动画。 */
data class QuroProgressNode(
    override val id: String? = null,
    override val style: QuroUiStyle? = null,
    val progress: Float? = null,
    val label: String? = null,
) : QuroUiNode

/** 分隔线。 */
data class QuroDividerNode(
    override val id: String? = null,
    override val style: QuroUiStyle? = null,
    val thickness: Int? = null,
    val padding: Int? = null,
) : QuroUiNode

/** 占位空白。 */
data class QuroSpacerNode(
    override val id: String? = null,
    override val style: QuroUiStyle? = null,
    val height: Int? = null,
    val width: Int? = null,
) : QuroUiNode

// =============================================================================================
// 富媒体 / 文档节点（v1.0.82 新增：原生文本排版 + 媒体播放 + 完整浏览器 + 代码）
// =============================================================================================

/** 原生 Markdown 富文本排版（非 HTML）：支持 #/##/### 标题、列表、引用、加粗斜体、行内代码、
 *  链接，以及围栏代码块（可展示所有语言的代码）。用于把「文档级排版内容」直接渲染成原生控件。 */
data class QuroMarkdownNode(
    override val id: String? = null,
    override val style: QuroUiStyle? = null,
    val value: String = "",
) : QuroUiNode

/** 视频播放（内嵌播放器）：url 支持 http(s) / 本地文件路径 / content:// uri。 */
data class QuroVideoNode(
    override val id: String? = null,
    override val style: QuroUiStyle? = null,
    val url: String = "",
    val title: String? = null,
) : QuroUiNode

/** 音频 / 音乐播放（内嵌播放器）：url 支持 http(s) / 本地文件路径 / content:// uri。 */
data class QuroAudioNode(
    override val id: String? = null,
    override val style: QuroUiStyle? = null,
    val url: String = "",
    val title: String? = null,
) : QuroUiNode

/** 内嵌完整功能浏览器（WebView）：支持 JavaScript、缩放、页内前进/后退导航。
 *  height 为可选像素高度（默认 320）。 */
data class QuroBrowserNode(
    override val id: String? = null,
    override val style: QuroUiStyle? = null,
    val url: String = "",
    val height: Int? = null,
) : QuroUiNode

/** 自写 HTML（v1.0.84 新增）：AI 完全自写 HTML/CSS/JS，经 WebView loadDataWithBaseURL
 *  内联渲染到对话气泡。这是「自写 UI」的核心节点——AI 不再受 DSL 白名单限制，
 *  可以直接写任意 HTML 内容（表单、图表、动画、小工具等）。
 *  html 为完整 HTML 源码；height 为可选高度 dp（默认自适应，上限 600）。
 *  注意：与 browser 的区别——browser 加载 url，html 渲染内联源码。 */
data class QuroHtmlNode(
    override val id: String? = null,
    override val style: QuroUiStyle? = null,
    val html: String = "",
    val height: Int? = null,
) : QuroUiNode

/** 代码块（展示 ZorvAI 支持的所有语言）：code 为源码，lang 为语言名（python/node/shell/html/
 *  json/xml/svg/c/cpp/java 等）。runnable=true 时显示「运行」按钮，点击经 run_code 真执行。 */
data class QuroCodeNode(
    override val id: String? = null,
    override val style: QuroUiStyle? = null,
    val code: String = "",
    val lang: String? = null,
    val title: String? = null,
    val runnable: Boolean = false,
) : QuroUiNode

// =============================================================================================
// 交互节点
// =============================================================================================

/** 按钮。variant: filled | outlined | text */
data class QuroButtonNode(
    override val id: String? = null,
    override val style: QuroUiStyle? = null,
    val label: String = "",
    val action: QuroUiAction? = null,
    val variant: String? = null,
    val enabled: Boolean = true,
    val icon: String? = null,
) : QuroUiNode

/** 文本输入框。 */
data class QuroTextInputNode(
    override val id: String = "",
    override val style: QuroUiStyle? = null,
    val label: String? = null,
    val placeholder: String? = null,
    val value: String? = null,
    val multiline: Boolean = false,
    val lines: Int? = null,
    val inputType: String? = null, // text | number | password | email | phone
) : QuroUiNode

/** 复选框。 */
data class QuroCheckboxNode(
    override val id: String = "",
    override val style: QuroUiStyle? = null,
    val label: String = "",
    val checked: Boolean = false,
) : QuroUiNode

/** 开关。 */
data class QuroSwitchNode(
    override val id: String = "",
    override val style: QuroUiStyle? = null,
    val label: String? = null,
    val checked: Boolean = false,
) : QuroUiNode

/** 单选下拉。 */
data class QuroSelectNode(
    override val id: String = "",
    override val style: QuroUiStyle? = null,
    val label: String? = null,
    val options: List<String> = emptyList(),
    val selected: String? = null,
) : QuroUiNode

/** 滑块。取值 min..max，步长 step。 */
data class QuroSliderNode(
    override val id: String = "",
    override val style: QuroUiStyle? = null,
    val label: String? = null,
    val value: Float = 0f,
    val min: Float = 0f,
    val max: Float = 100f,
    val step: Int = 1,
) : QuroUiNode

/**
 * 列表。items 为静态数据；每项用 [itemTemplate] 渲染，
 * 模板内可用 {{item}} / {{item.field}} / {{index}} 占位符引用当前项。
 * 当 item 是 JSON 对象字符串时，{{item.emoji}} / {{item.title}} 等按字段解析；
 * 当 item 是普通字符串时，{{item}} 直接替换为整个字符串。
 */
data class QuroListNode(
    override val id: String? = null,
    override val style: QuroUiStyle? = null,
    val items: List<String> = emptyList(),
    val itemTemplate: QuroUiNode? = null,
    val maxHeight: Int? = null,
) : QuroUiNode

/** 标签页。tabs: [{title, node}] */
data class QuroTabsNode(
    override val id: String? = null,
    override val style: QuroUiStyle? = null,
    val tabs: List<QuroTabItem> = emptyList(),
) : QuroUiNode

data class QuroTabItem(
    val title: String = "",
    val node: QuroUiNode? = null,
)

// =============================================================================================
// 动作模型
// =============================================================================================

/**
 * 节点动作。参照 Kai `UiAction`，并扩展 ZorvAI 特有的工具/技能调用能力，
 * 让动态 UI 不只是「展示」，而能驱动 Agent 真正做事。
 */
sealed interface QuroUiAction

/** 回传事件给对话：把 collectFrom 收集的控件值 + data 一并作为用户消息发回模型。 */
data class QuroCallbackAction(
    val event: String = "",
    val data: Map<String, String> = emptyMap(),
    val collectFrom: List<String> = emptyList(),
) : QuroUiAction

/** 切换目标节点可见性（如展开/收起详情）。 */
data class QuroToggleAction(
    val targetId: String = "",
) : QuroUiAction

/** 打开网页（内置浏览器）。 */
data class QuroOpenUrlAction(
    val url: String = "",
) : QuroUiAction

/** 复制文本到剪贴板。 */
data class QuroCopyAction(
    val text: String = "",
    val label: String? = null,
) : QuroUiAction

/** 直接调用内置工具（tool_call），让 UI 按钮驱动 Agent 执行动作。 */
data class QuroToolCallAction(
    val tool: String = "",
    val arguments: Map<String, String> = emptyMap(),
    val collectFrom: List<String> = emptyList(),
) : QuroUiAction

/** 调用已安装技能。 */
data class QuroSkillAction(
    val skill: String = "",
    val input: String? = null,
    val collectFrom: List<String> = emptyList(),
) : QuroUiAction

/** 打开应用（包名或应用名）。 */
data class QuroOpenAppAction(
    val packageName: String = "",
) : QuroUiAction

/**
 * 打开 ZorvAI 内置界面（深链导航）：让动态 UI 按钮直接跳进终端 / 模型配置 / 可视化编程 /
 * 小程序 / 工具中心等原生界面，而不只是回发文本。
 *
 * target 与 [com.ai.assistance.quro.core.tools.ui.UiNavigationEvent.OpenScreen] 完全对齐：
 * editor / terminal / toolbox / knowledge / cms / aci / about / appearance / soul / memory /
 * permission / model_config / voice / settings / tool_center，以及工具中心子能力
 * vispro(可视化编程) / node_editor(flow) / miniapp(小程序) / workbench。
 */
data class QuroOpenScreenAction(
    val target: String = "",
) : QuroUiAction

/** 直接渲染 HTML 到对话气泡（第一层渲染，复用 MiniAppCard 运行时）。 */
data class QuroRenderHtmlAction(
    val html: String = "",
) : QuroUiAction

/** 直接渲染可视化编程（mermaid 源码）到对话气泡（第一层渲染，复用 MermaidCard 运行时）。 */
data class QuroRenderVisproAction(
    val source: String = "",
) : QuroUiAction

/** 可视化弹窗：在对话上方弹出信息框（标题 + 内容），纯本地展示、不惊动模型。 */
data class QuroVisualPopupAction(
    val title: String = "",
    val content: String = "",
) : QuroUiAction

/**
 * 可视化询问：弹出选项列表让用户点选，选中项作为一条用户消息回发模型继续对话。
 * 用于「二选一 / 多选一 / 确认」式交互，比 callback 表单更轻量。
 */
data class QuroVisualAskAction(
    val prompt: String = "",
    val options: List<String> = emptyList(),
) : QuroUiAction

// =============================================================================================
// 通用样式系统（v1.0.83）
// =============================================================================================

/**
 * 通用样式对象：可挂在**任意**节点上，让 AI 像写 Compose 一样自由描述任意元素的视觉样式。
 *
 * 模型输出始终是「数据」不是「代码」—— 再自由的样式也只是声明，端上忠实渲染成原生控件，
 * 且解析时任意字段非法都会回落默认值，绝不会导致渲染失败。
 *
 * AI 两种写法等价（解析器都会收进同一个 [QuroUiStyle]）：
 *  - 嵌套对象：`"style":{"backgroundColor":"#fff","borderRadius":12,"padding":8}`
 *  - 顶层平铺：`{"type":"box","backgroundColor":"#fff","borderRadius":12,"padding":8}`
 */
data class QuroUiStyle(
    val background: QuroUiBackground? = null, // 背景：纯色或渐变
    val borderColor: String? = null,         // 边框颜色（#RRGGBB / 语义名）
    val borderWidth: Int? = null,            // 边框宽度（dp）
    val borderRadius: Int? = null,           // 圆角（dp）
    val shadowElevation: Int? = null,        // 阴影高度（dp）
    val shadowColor: String? = null,         // 阴影颜色
    val padding: QuroUiEdges? = null,        // 内边距
    val margin: QuroUiEdges? = null,         // 外边距
    val width: QuroUiSize? = null,           // 宽度：固定 dp / 撑满 / 自适应
    val height: QuroUiSize? = null,          // 高度
    val maxWidth: Int? = null,               // 最大宽度（dp）
    val maxHeight: Int? = null,              // 最大高度（dp）
    val opacity: Float? = null,              // 透明度 0..1
    val align: String? = null,               // 父容器内对齐：start | center | end（column 控水平、row 控垂直；top|bottom 也接受）
    val visible: Boolean? = null,            // false 则不渲染该节点
)

/** 背景：纯色或渐变二选一。 */
sealed interface QuroUiBackground {
    data class Solid(val color: String) : QuroUiBackground
    data class Gradient(
        val colors: List<String>,
        val direction: String? = null, // vertical | horizontal | diagonal | radial
        val angle: Int? = null,        // diagonal 时的角度（度，保留位）
    ) : QuroUiBackground
}

/** 四边/单边边距（纯数据，不引入 Compose 依赖，由渲染器转成 PaddingValues）。 */
data class QuroUiEdges(
    val all: Int? = null,
    val horizontal: Int? = null,
    val vertical: Int? = null,
    val top: Int? = null,
    val bottom: Int? = null,
    val start: Int? = null,
    val end: Int? = null,
)

/** 尺寸：固定 dp / 撑满父容器（row/column 内可带权重）/ 自适应内容。 */
sealed interface QuroUiSize {
    data class Fixed(val dp: Int) : QuroUiSize
    data class Fill(val weight: Float? = null) : QuroUiSize
    object Wrap : QuroUiSize
}

// =============================================================================================
// 解析结果
// =============================================================================================

/** UI 代码块解析结果：成功得到节点树，失败保留修复后的原始 JSON 以便回退展示为代码块。 */
sealed class QuroUiParseResult {
    data class Success(val root: QuroUiNode, val rawJson: String) : QuroUiParseResult()
    data class Failure(val rawJson: String, val reason: String) : QuroUiParseResult()
}
