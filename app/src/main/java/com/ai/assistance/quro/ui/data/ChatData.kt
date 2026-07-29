package com.ai.assistance.quro.ui.data

import com.ai.assistance.quro.core.cards.QuroChatCard

/**
 * Zorv AI 数据模型与示例数据（UI 层）。
 * 模型按 API 提供商分组，使用真实模型名称 + 真实 ID（格式：提供商_模型id）。
 */

data class ChatModel(
    val name: String,        // 展示名，如 "GPT-4o"
    val id: String,          // 真实 ID，如 "openai_gpt-4o"
    val desc: String,
    val provider: String,    // 提供商，如 "OpenAI"
    val mark: String         // 左侧标记字母，如 "G"
)

data class ModelGroup(
    val provider: String,
    val models: List<ChatModel>
)

data class Persona(
    val id: String = "",      // QuroPersona.id，用于回写激活状态（空则不写回）
    val name: String,       // "砚生"
    val role: String,       // "沉静读书人"
    val desc: String,
    val ava: String,       // "砚"（无图时退化为首字母）
    val color: String,      // "#211E1A"
    val avatarUri: String = "",  // 自定义图片头像的内部路径（有图时优先显示图片）
    val tags: List<String> = emptyList()  // 显示用（来自 QuroTag.name）
)
data class HistoryItem(
    val id: String = "",      // QuroConversationMeta.id，用于回写会话切换
    val title: String,
    val sub: String,
    val time: String,
    val group: String,
    val active: Boolean = false
)

data class Attachment(
    val name: String,
    val meta: String,
    val path: String? = null,   // 应用私有目录下的绝对文件路径（可下载/预览）
    val type: String? = null,   // "image" | "video" | "file"
)

data class ThinkBlock(val steps: List<String>)

/** 工具调用展示块（让 AI 的自动化操作在对话中可见）。 */
data class ToolCallUi(
    val name: String,
    val args: String,
    val result: String? = null,
)

data class Message(
    val id: Int,
    val mine: Boolean,
    val author: String,
    val avatar: String,
    val avatarUri: String = "",  // 自定义图片头像的内部路径（有图时优先显示图片）
    val time: String,
    val text: String? = null,
    val attachment: Attachment? = null,
    val think: ThinkBlock? = null,
    val tools: List<ToolCallUi>? = null,  // 本消息关联的工具调用（可见化，而非隐藏管道）
    val cards: List<QuroChatCard> = emptyList(), // 气泡内富组件（一等公民，合体进聊天气泡）
)

// ---------- 示例：模型（按提供商分组） ----------

val GPT4O = ChatModel(
    name = "GPT-4o", id = "openai_gpt-4o",
    desc = "多模态旗舰，综合能力强、响应均衡。", provider = "OpenAI", mark = "G"
)

val SAMPLE_MODEL_GROUPS = listOf(
    ModelGroup("OpenAI", listOf(
        GPT4O,
        ChatModel("GPT-4o mini", "openai_gpt-4o-mini", "轻量快答，成本低，适合高频简单任务。", "OpenAI", "g"),
        ChatModel("GPT-4 Turbo", "openai_gpt-4-turbo", "长上下文与工具调用成熟，生态完善。", "OpenAI", "4"),
        ChatModel("o1", "openai_o1", "强化推理，擅长数学与复杂规划，思考较慢。", "OpenAI", "o"),
        ChatModel("o3-mini", "openai_o3-mini", "推理性价比高，代码与科学任务表现好。", "OpenAI", "o")
    )),
    ModelGroup("Anthropic", listOf(
        ChatModel("Claude 3.5 Sonnet", "anthropic_claude-3-5-sonnet", "长文本与严谨推理见长，写作自然。", "Anthropic", "C"),
        ChatModel("Claude 3.5 Haiku", "anthropic_claude-3-5-haiku", "极速轻量，适合实时与海量预处理。", "Anthropic", "c"),
        ChatModel("Claude 3 Opus", "anthropic_claude-3-opus", "最强深度任务，长文理解与 Agent 编排出色。", "Anthropic", "O")
    )),
    ModelGroup("Google", listOf(
        ChatModel("Gemini 1.5 Pro", "google_gemini-1.5-pro", "超长上下文（百万 token），多模态原生。", "Google", "Ge"),
        ChatModel("Gemini 1.5 Flash", "google_gemini-1.5-flash", "高速低延迟，大规模并发友好。", "Google", "gf")
    )),
    ModelGroup("DeepSeek", listOf(
        ChatModel("DeepSeek-V3", "deepseek_deepseek-v3", "开源旗舰，中英文均衡，性价比高。", "DeepSeek", "D"),
        ChatModel("DeepSeek-R1", "deepseek_deepseek-r1", "推理专精，链式思考，数学与代码强。", "DeepSeek", "R")
    ))
)

// ---------- 示例：人格 ----------

val SAMPLE_PERSONAS = listOf(
    Persona(
        name = "砚生", role = "沉静读书人", ava = "砚", color = "#211E1A",
        desc = "温润、有条理，习惯先理清脉络再作答，适合阅读、整理与深度讨论。",
        tags = listOf("整理", "读书", "规划")
    ),
    Persona(
        name = "厉锋", role = "锐评编辑", ava = "锋", color = "#C25A38",
        desc = "直接、犀利，专挑逻辑漏洞与空话，适合改稿、辩论与决策前的硬碰硬。",
        tags = listOf("锐评", "改稿", "决策")
    ),
    Persona(
        name = "阿木", role = "代码匠", ava = "木", color = "#6E7C62",
        desc = "务实、给得出可运行代码，重视边界与可维护性，适合工程实现与排错。",
        tags = listOf("编码", "排错", "架构")
    )
)

// ---------- 示例：历史对话 ----------

val SAMPLE_HISTORY = listOf(
    HistoryItem(id = "", title = "会议纪要行动清单", sub = "帮我把这份纪要整理成行动清单…", time = "09:32", group = "今天", active = true),
    HistoryItem(id = "", title = "竞品首页改版思路", sub = "对比三个站点的首屏结构", time = "08:10", group = "今天"),
    HistoryItem(id = "", title = "小红书文案三版", sub = "轻松 / 专业 / 种草 三种语气", time = "周二", group = "本周"),
    HistoryItem(id = "", title = "读书笔记 ·《安静》", sub = "第二章核心观点的摘录", time = "周一", group = "本周"),
    HistoryItem(id = "", title = "京都行程规划", sub = "5 天 4 晚，含交通衔接", time = "7/09", group = "更早"),
    HistoryItem(id = "", title = "SQL 慢查询排查", sub = "定位到缺失的联合索引", time = "7/02", group = "更早")
)

// ---------- 示例：对话消息 ----------

val SAMPLE_MESSAGES = listOf(
    Message(
        id = 1, mine = true, author = "你", avatar = "我", time = "09:32",
        attachment = Attachment("会议纪要_0716.pdf", "1.8 MB · PDF"),
        text = "帮我把这份纪要整理成行动清单，把**需要老板确认**的部分单独标出来。"
    ),
    Message(
        id = 2, mine = false, author = "砚生", avatar = "砚", time = "09:32",
        think = ThinkBlock(listOf(
            "通读全文，提取全部待办与责任人。",
            "识别其中的决策节点与资源审批点。",
            "区分「执行项」与「待确认项」。",
            "按影响面与截止时间排序，标注优先级。"
        )),
        text = "已读完，共 **6 项行动**。其中 **2 项**需要老板拍板，已用标记标出：\n" +
                "① 上线时间表：建议定在 8/12 灰度、8/19 全量。（需老板确认）\n" +
                "② 运营物料：由小林本周四前交付初稿。\n" +
                "③ 预算追加 12 万：用于第三方压测服务。（需老板确认）\n" +
                "④ 客服话术：由婷婷同步更新知识库。\n" +
                "⑤ 数据看板：8/5 前接好实时漏斗。\n" +
                "⑥ 复盘会：8/20 下午，砚生整理纪要。\n" +
                "要我把 ①③ 直接草拟成一封给老板的确认邮件吗？"
    ),
    Message(
        id = 3, mine = true, author = "你", avatar = "我", time = "09:35",
        text = "好，草拟一下，语气正式一点。"
    )
)
