package com.ai.assistance.quro.core.cards

/**
 * 可视化组件 SDK v1400 —— 第三批：AI 征询决策 + 数据可视化进阶（[QuroChatCard] 的 sealed 子类）。
 *
 * ## 这批的设计依据（业界协议调研结论，不是凭空想的）
 *
 * 调研 A2UI（Google，v0.9.1 现行 / v1.0 候选）与 AG-UI（CopilotKit/LangChain）后，
 * 找到本 SDK 缺的两块，各对应本批的一组组件：
 *
 * 1. **缺「AI 主动征询决策」这一整类。**
 *    AG-UI 把它叫 `HUMAN_IN_INPUT`，CopilotKit 演示里最典型的交互就是它：
 *    >「这笔 $86.40 退你原卡还是存余额？· 原卡 Visa ····4242 · 余额即时到账 · 或者你直接输入」
 *    它与存量 `quickreply` / `quickaction` **语义不同**：那两个是「AI 给选项让用户点」，
 *    用途是推进对话；本批的 [DecisionCard] 是「AI 卡住等一个决定」，用途是**收集约束**，
 *    并且必须有「其它 → 自由输入」出口，否则 AI 只能从自己给的选项里挑，用户会失去话语权。
 *
 * 2. **缺数据可视化的进阶形态。**
 *    A2UI 的 Display/Container 之外，真实分析场景最常用的是
 *    桑基（流向）、漏斗（转化）、瀑布（归因）、四象限（决策）——
 *    这四种不是「换个图形的 chart」，各自表达一种存量图表达不了的语义。
 *
 * 另：A2UI v0.9 已把 `theme` 更名为 `surfaceProperties`（主题是协议级一等公民），
 * 据此本批新增 [SurfacePropsCard] 的落地由围栏属性 `theme=` 承接（见 CardFence）。
 *
 * ## 🔴 追加纪律（与前两批一致）
 *
 * 本批**不改动任何存量类型字段**，清一色新 data class。
 * 每种组件注释里写清「与谁的区别」，避免 AI 在相似卡之间选错。
 */

// ═══════════════ 一、AI 征询决策（AG-UI HUMAN_IN_INPUT） ═══════════════

/**
 * 决策征询卡：AI 卡住，等用户拍一个决定。
 *
 * ## 与 `quickreply` 的区别（最容易选错的一对）
 *
 * | | quickreply | decision（本卡） |
 * |---|---|---|
 * | 语义 | 给用户可点的回应，**推进对话** | AI **卡在等一个约束**，收集决策 |
 * | 谁需要决定 | 用户（回答问题） | AI（缺一个参数才能继续） |
 * | 有无自由输入 | 一般没有 | **必须有** `allowCustom` |
 * | 用完 | 一轮 | **解除 AI 的阻塞** |
 *
 * 典型场景：退款退哪儿、要不要覆盖文件、用哪个方案、预算上限多少。
 *
 * @param options 候选项（至少一项；渲染层会自动补「其它」出口）
 * @param allowCustom 是否允许自由输入。**建议保持 true**，否则用户被逼在 AI 的选项里
 * @param required 是否必须选一个才能继续（false = 可以直接跳过）
 * @param context 卡住的原因，一句话说清「为什么需要你决定」
 */
data class DecisionCard(
    override val id: String,
    override val title: String,
    val question: String,
    val options: List<DecisionCard.Option>,
    val allowCustom: Boolean = true,
    val required: Boolean = true,
    val customHint: String = "或者直接输入你的选择",
    val context: String = "",
) : QuroChatCard {
    /**
     * 一个候选项。
     *
     * @param detail 补充说明，会显示在选项下方（为什么选它、影响是什么）
     */
    data class Option(
        val label: String,
        val value: String = label,
        val detail: String = "",
        val recommended: Boolean = false,
    )
}

/**
 * 确认卡：危险/不可逆操作前的最后一道闸。
 *
 * 与 `decision` 的区别：decision 是**二选一或多选一**的选择题，
 * confirm 是「做 / 不做」的二元闸门，且**默认焦点落在取消**。
 * 删除文件、覆盖存档、付款这类操作都该用它。
 *
 * @param danger true 时用危险色（红），用于删除/清空；false 用主色（用于「确认发布」）
 */
data class ConfirmCard(
    override val id: String,
    override val title: String,
    val message: String,
    val confirmLabel: String = "确认",
    val cancelLabel: String = "取消",
    val danger: Boolean = false,
    val detail: String = "",
) : QuroChatCard

// ═══════════════ 二、数据可视化进阶（四种存量图表达不了的语义） ═══════════════

/**
 * 桑基图：流量**流向**（A 到 B 的量）。
 *
 * 与存量图的区别：
 *  - `pie` 看**占比**（一个整体切成几块，无流向）
 *  - `sankey` 看**流向**（用户从哪来、往哪去，链路可跨多层）
 *  典型：用户来源 → 页面 → 转化行为；能量流动；预算从部门到科目。
 */
data class SankeyCard(
    override val id: String,
    override val title: String,
    /** 节点：id 唯一，显示名用 [SankeyCard.Node.label] */
    val nodes: List<SankeyCard.Node>,
    /** 连线：[from]/[to] 必须是 [nodes] 里的 id，缺节点会被渲染层跳过 */
    val links: List<SankeyCard.Link>,
    val unit: String = "",
) : QuroChatCard {
    data class Node(val id: String, val label: String = "")
    data class Link(val from: String, val to: String, val value: Double)
}

/**
 * 瀑布图：变化**归因**（从起点到终点，每根柱是一个增量）。
 *
 * 与 `bar`/`stackedbar` 的区别：那俩是**并列比较**（各年营收），
 * waterfall 是**累加推导**（Q1 100 → +20 → −30 → Q2 90，每根柱可正可负）。
 * 典型：预算 vs 实际差异拆解、利润变动归因、用户增长来源。
 */
data class WaterfallCard(
    override val id: String,
    override val title: String,
    /** 步骤：isTotal=true 的项是「小计柱」而非增减柱 */
    val steps: List<WaterfallCard.Step>,
    /** 起始值；null 则取首个 isTotal 项的 value */
    val start: Double? = null,
    val unit: String = "",
) : QuroChatCard {
    /**
     * @param delta 增量值（isTotal=true 时忽略此字段，用 [value]）
     */
    data class Step(
        val label: String,
        val delta: Double = 0.0,
        val value: Double = 0.0,
        val isTotal: Boolean = false,
    )
}

/**
 * 四象限：两轴定位的**决策矩阵**。
 *
 * 与 `radar` 的区别：radar 是**同维度多指标**的轮廓（一个实体的多项能力），
 * quadrant 是**两个维度分档**（每项在两轴上的位置决定它落在哪个象限）。
 * 典型：优先级矩阵（影响 × 成本）、用户分群（活跃 × 消费力）、风险评级。
 *
 * @param xLabel/yLabel 轴名；每项的 x/y 值域均为 0..[axisMax]
 */
data class QuadrantCard(
    override val id: String,
    override val title: String,
    val items: List<QuadrantCard.Item>,
    val xLabel: String = "",
    val yLabel: String = "",
    val axisMax: Double = 100.0,
    val quadrants: List<String> = emptyList(),
) : QuroChatCard {
    /**
     * @param x 第一坐标（0..axisMax），落到右半
     * @param y 第二坐标（0..axisMax），落到上半
     */
    data class Item(val label: String, val x: Double, val y: Double, val tag: String = "")
}

// ═══════════════ 三、其它协议级形态 ═══════════════

/**
 * 分档对比：两组实体的**逐维对照**。
 *
 * 与 `compare` 的区别：compare 是左/右两张卡的并列展示（结构可不同），
 * 本卡是**同维度逐行打分**（每行一个指标，两列一个分值 + 各自差值），
 * 差值正负自动着色 —— 典型「两个方案哪个更适合我」。
 */
data class MatrixCard(
    override val id: String,
    override val title: String,
    val leftLabel: String = "方案 A",
    val rightLabel: String = "方案 B",
    val rows: List<MatrixCard.Row>,
    /** 是否给每行算差值（right − left）并着色 */
    val showDiff: Boolean = true,
    val unit: String = "",
) : QuroChatCard {
    /**
     * @param better 说明「哪个方向算好」："high" 表示数值大者优，"low" 表示小者优，
     *               null 表示该行不参与优劣判定（如「发布时间」）
     */
    data class Row(
        val label: String,
        val left: String,
        val right: String,
        val better: String? = "high",
    )
}

/**
 * 事件流：带时间戳的**流水账**（区别于 `timeline` 是编排计划）。
 *
 * 与 `timeline` 的区别：timeline 表达**计划/里程碑**（未来要做的事），
 * 本卡表达**已发生的事实流**（日志、提交、消息、账单变动）。
 */
data class FeedCard(
    override val id: String,
    override val title: String,
    val items: List<FeedCard.Item>,
    /** 右下角来源标记，如「服务器」「GitHub」 */
    val source: String = "",
) : QuroChatCard {
    /**
     * @param level  info / success / warning / error —— 决定圆点颜色，出错项一眼可见
     */
    data class Item(
        val time: String,
        val text: String,
        val level: String = "info",
        val actor: String = "",
    )
}

/**
 * 关系图：节点 + 连线的**任意图**（不是层级树）。
 *
 * 与存量图的区别：
 *  - `tree` / `cardui` 的 children 是**树**（一个父节点）
 *  - `graph` 是**图**（可多父、可成环，如人际关系、依赖关系、服务拓扑）
 *
 * 渲染层不做力导向布局（那需要物理引擎与手势，成本高且不稳定），
 * 而是按 [GraphCard.Node.col]/[row] 的显式网格坐标摆放 ——
 * 模型只需给出大致相对位置，渲染结果即可预期。缺坐标时按出现顺序顺排。
 */
data class GraphCard(
    override val id: String,
    override val title: String,
    val nodes: List<GraphCard.Node>,
    val edges: List<GraphCard.Edge>,
) : QuroChatCard {
    /**
     * @param col/row 显式网格坐标（0 起步，缺省按出现顺序顺排）
     * @param shape   node（默认）/ service / db / queue —— 决定图标与配色
     */
    data class Node(
        val label: String,
        val col: Int = -1,
        val row: Int = -1,
        val shape: String = "node",
    )

    /**
     * @param label 边上文字（可空）
     * @param kind   flow（默认实线）/ dep（依赖，虚线）/ back（反向）
     */
    data class Edge(val from: String, val to: String, val label: String = "", val kind: String = "flow")
}

/**
 * 键值对列表的**分区版**：分组展示结构化明细。
 *
 * 与存量 `keyvalue` 的区别：keyvalue 是平铺的单层键值对，
 * 本卡是**多组分区**（如订单的商品/收货/支付三段明细），
 * 每组有自己的小标题，组内才是键值对。
 */
data class SectionCard(
    override val id: String,
    override val title: String,
    val sections: List<SectionCard.Section>,
) : QuroChatCard {
    /**
     * @param rows 键值对；用 [null] 表示「空行作分隔」
     */
    data class Section(val title: String, val rows: List<Pair<String, String>>)
}