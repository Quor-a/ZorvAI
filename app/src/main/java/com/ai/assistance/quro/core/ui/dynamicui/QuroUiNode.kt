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
 */

/** 所有 UI 节点的基类型。 */
sealed interface QuroUiNode {
    val id: String?
}

// =============================================================================================
// 布局节点
// =============================================================================================

/** 纵向布局。 */
data class QuroColumnNode(
    override val id: String? = null,
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
    val children: List<QuroUiNode> = emptyList(),
    val spacing: Int? = null,
    val padding: Int? = null,
    val verticalAlign: String? = null, // top | center | bottom
    val scrollable: Boolean = false,
    val weight: Float? = null,
) : QuroUiNode

/** 堆叠布局（层叠）。 */
data class QuroBoxNode(
    override val id: String? = null,
    val children: List<QuroUiNode> = emptyList(),
    val padding: Int? = null,
    val weight: Float? = null,
) : QuroUiNode

/** 卡片容器（带圆角与阴影）。 */
data class QuroCardNode(
    override val id: String? = null,
    val children: List<QuroUiNode> = emptyList(),
    val title: String? = null,
    val padding: Int? = null,
    val cornerRadius: Int? = null,
    val onClick: QuroUiAction? = null,
    val weight: Float? = null,
) : QuroUiNode

// =============================================================================================
// 内容节点
// =============================================================================================

/** 文本。style: title | headline | body | caption | label */
data class QuroTextNode(
    override val id: String? = null,
    val value: String = "",
    val style: String? = null,
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
    val url: String = "",
    val alt: String? = null,
    val height: Int? = null,
    val aspectRatio: Float? = null,
    val cornerRadius: Int? = null,
) : QuroUiNode

/** 图标（Material 图标名，如 "star" / "settings"）。 */
data class QuroIconNode(
    override val id: String? = null,
    val name: String = "info",
    val size: Int? = null,
    val tint: String? = null,
    val description: String? = null,
) : QuroUiNode

/** 徽标/胶囊标签。 */
data class QuroBadgeNode(
    override val id: String? = null,
    val text: String = "",
    val color: String? = null,
    val background: String? = null,
) : QuroUiNode

/** 进度条。progress 取值 0.0–1.0；progress 为 null 时显示不确定动画。 */
data class QuroProgressNode(
    override val id: String? = null,
    val progress: Float? = null,
    val label: String? = null,
) : QuroUiNode

/** 分隔线。 */
data class QuroDividerNode(
    override val id: String? = null,
    val thickness: Int? = null,
    val padding: Int? = null,
) : QuroUiNode

/** 占位空白。 */
data class QuroSpacerNode(
    override val id: String? = null,
    val height: Int? = null,
    val width: Int? = null,
) : QuroUiNode

// =============================================================================================
// 交互节点
// =============================================================================================

/** 按钮。variant: filled | outlined | text */
data class QuroButtonNode(
    override val id: String? = null,
    val label: String = "",
    val action: QuroUiAction? = null,
    val variant: String? = null,
    val enabled: Boolean = true,
    val icon: String? = null,
) : QuroUiNode

/** 文本输入框。 */
data class QuroTextInputNode(
    override val id: String = "",
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
    val label: String = "",
    val checked: Boolean = false,
) : QuroUiNode

/** 开关。 */
data class QuroSwitchNode(
    override val id: String = "",
    val label: String? = null,
    val checked: Boolean = false,
) : QuroUiNode

/** 单选下拉。 */
data class QuroSelectNode(
    override val id: String = "",
    val label: String? = null,
    val options: List<String> = emptyList(),
    val selected: String? = null,
) : QuroUiNode

/** 滑块。取值 min..max，步长 step。 */
data class QuroSliderNode(
    override val id: String = "",
    val label: String? = null,
    val value: Float = 0f,
    val min: Float = 0f,
    val max: Float = 100f,
    val step: Int = 1,
) : QuroUiNode

/**
 * 列表。items 为静态数据；每项用 [itemTemplate] 渲染，
 * 模板内可用 {{item}} / {{index}} 占位符引用当前项。
 */
data class QuroListNode(
    override val id: String? = null,
    val items: List<String> = emptyList(),
    val itemTemplate: QuroUiNode? = null,
    val maxHeight: Int? = null,
) : QuroUiNode

/** 标签页。tabs: [{title, node}] */
data class QuroTabsNode(
    override val id: String? = null,
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

// =============================================================================================
// 解析结果
// =============================================================================================

/** UI 代码块解析结果：成功得到节点树，失败保留修复后的原始 JSON 以便回退展示为代码块。 */
sealed class QuroUiParseResult {
    data class Success(val root: QuroUiNode, val rawJson: String) : QuroUiParseResult()
    data class Failure(val rawJson: String, val reason: String) : QuroUiParseResult()
}
