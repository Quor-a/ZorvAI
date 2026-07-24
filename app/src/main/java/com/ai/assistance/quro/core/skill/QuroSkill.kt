package com.ai.assistance.quro.core.skill

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 用户自定义「技能 / SKILL」数据模型。
 *
 * 与已有的 QuickJS 插件运行时（小程序式 UI 插件）、CMS v2 能力模块（系统级能力）互相独立：
 * - 技能更轻量：本质是一段「注入系统提示词的指令 + 简介」，让 AI 真正"拥有"某项能力/行为约束。
 * - 不做 UI 渲染、不跑 JS、不需要权限；开启后由 [QuroChatViewModel.buildSystemPrompt] 注入。
 */
data class QuroSkill(
    val id: String,
    val name: String,
    val description: String = "",
    /** 技能指令正文：注入系统提示词，作为 AI 的额外行为约束 / 能力说明。 */
    val prompt: String = "",
    /** 是否启用（仅启用的技能注入系统提示词）。 */
    val enabled: Boolean = true,
    /** 触发关键词（可选，仅作说明/将来自动匹配用，不参与注入）。 */
    val trigger: String = "",
    val updatedAt: Long = 0L,
)

/** 技能持久化（SharedPreferences，JSON 数组）。 */
object QuroSkillStore {
    private const val PREFS = "quro_skills"
    private const val KEY = "skills"
    private const val KEY_SEEDED = "defaults_seeded"

    /** 首次访问时自动填充内置默认技能集（仅执行一次）。 */
    fun ensureSeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        val existing = load(context)
        if (existing.isNotEmpty()) {
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
            return
        }
        save(context, defaultSkills())
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    fun load(context: Context): List<QuroSkill> {
        ensureSeeded(context)
        val out = mutableListOf<QuroSkill>()
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    QuroSkill(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("name", ""),
                        description = o.optString("description", ""),
                        prompt = o.optString("prompt", ""),
                        enabled = o.optBoolean("enabled", true),
                        trigger = o.optString("trigger", ""),
                        updatedAt = o.optLong("updatedAt", 0L),
                    )
                )
            }
        }
        return out.sortedBy { it.name }
    }

    fun save(context: Context, list: List<QuroSkill>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { s ->
                arr.put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("name", s.name)
                        put("description", s.description)
                        put("prompt", s.prompt)
                        put("enabled", s.enabled)
                        put("trigger", s.trigger)
                        put("updatedAt", s.updatedAt)
                    }
                )
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply()
        }
    }

    fun addOrUpdate(context: Context, skill: QuroSkill) {
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.id == skill.id }
        if (idx >= 0) list[idx] = skill else list.add(skill)
        save(context, list)
    }

    fun remove(context: Context, id: String) {
        save(context, load(context).filter { it.id != id })
    }

    /** 当前启用且含指令正文的技能（注入系统提示词用）。 */
    fun enabledList(context: Context): List<QuroSkill> =
        load(context).filter { it.enabled && it.prompt.isNotBlank() }

    /** 内置默认技能集（22 款实用技能）。 */
    private fun defaultSkills(): List<QuroSkill> {
        val now = System.currentTimeMillis()
        fun skill(name: String, desc: String, prompt: String, trigger: String = "", enabled: Boolean = false): QuroSkill =
            QuroSkill(
                id = UUID.randomUUID().toString(),
                name = name,
                description = desc,
                prompt = prompt,
                enabled = enabled,
                trigger = trigger,
                updatedAt = now,
            )
        return listOf(
            skill(
                "翻译助手", "中英互译，自动检测语言并翻译，保持原文语义和语调",
                "你现在是一名专业翻译。当用户发送中文时，将其翻译成自然流畅的英文；当用户发送英文时，翻译成中文。保持原文的语义、语调和风格。如果原文是技术文档，保留专业术语。翻译后可直接给出译文，无需额外解释，除非用户要求。",
                "翻译 translate", enabled = true
            ),
            skill(
                "写作润色", "优化文章表达，提升文笔流畅度和专业感",
                "你现在是一名资深文字编辑。用户发来的文字，请进行润色优化：修正语法错误、提升表达流畅度、增强逻辑连贯性、优化用词。保持原文核心意思不变。先给出修改后的全文，再简要列出主要改动点。",
                "润色 写作", enabled = true
            ),
            skill(
                "代码审查", "审查代码质量，指出潜在问题并给出优化建议",
                "你现在是一名高级软件工程师。当用户发送代码时，请进行代码审查：检查代码风格、潜在 bug、性能问题、安全隐患、可维护性。给出具体改进建议和优化后的代码片段。使用代码块格式展示代码。",
                "代码 review code", enabled = true
            ),
            skill(
                "长文摘要", "将长文本压缩为结构化摘要，提取核心要点",
                "你现在是一名信息提炼专家。用户发来的长文本，请生成结构化摘要：1) 一句话总结核心观点 2) 3-5 个关键要点（带序号） 3) 重要数据/结论。摘要应忠实原文，不添加推测。如果文本太短无需摘要则直接回复原文。",
                "总结 摘要 summarize", enabled = true
            ),
            skill(
                "头脑风暴", "针对主题快速生成创意方案和思路",
                "你现在是一名创意顾问。用户给出一个主题或问题后，请快速生成 10 个创意方案或思路。每个方案用一句话描述，按创新程度排序。不要自我审查，鼓励天马行空的想法。最后标注哪些是可立即执行的。",
                "头脑风暴 创意 brainstorm"
            ),
            skill(
                "日程规划", "帮助规划任务优先级和时间安排",
                "你现在是一名效率教练。用户描述待办事项后，请帮助规划：1) 按紧急/重要四象限分类 2) 建议执行顺序 3) 估算每项所需时间 4) 标注可委派/可合并的事项。输出格式清晰的表格或列表。",
                "日程 计划 plan"
            ),
            skill(
                "学习辅导", "用通俗易懂的方式讲解知识点",
                "你现在是一名耐心的一对一家教老师。用户提问任何知识点，请用通俗易懂的方式讲解：先给出一句话核心定义，再用生活化的类比解释，然后举 2-3 个例子，最后出一道检验题。根据用户反馈调整深度。",
                "学习 讲解 learn"
            ),
            skill(
                "邮件撰写", "专业邮件起草，支持多种语气和场景",
                "你现在是一名商务沟通专家。用户描述邮件目的和收件人后，请起草一封专业邮件：主题行简洁有力、开头得体、正文清晰分段、结尾有明确行动呼吁。支持正式/友好/催促/道歉等语气，用户可指定。",
                "邮件 email mail"
            ),
            skill(
                "演讲稿", "撰写演讲/汇报文稿，结构清晰有感染力",
                "你现在是一名演讲稿撰写人。用户给出演讲主题、时长和受众后，请撰写演讲稿：开场用故事或问题吸引注意、主体分 3 个要点展开、结尾呼应开头并发出行动号召。语言口语化，适合朗读。标注建议停顿和互动点。",
                "演讲 speech 汇报"
            ),
            skill(
                "数据分析", "解读数据趋势，给出可视化建议和业务洞察",
                "你现在是一名数据分析师。用户提供数据或描述数据来源后，请帮助：1) 识别关键指标和趋势 2) 指出异常值和潜在原因 3) 给出 3 条可执行的业务建议 4) 建议适合的图表类型。如果数据不完整，列出需要补充的字段。",
                "数据 分析 data"
            ),
            skill(
                "营销文案", "撰写吸引人的广告/推广/社媒文案",
                "你现在是一名资深文案策划。用户描述产品/活动后，请撰写营销文案：标题抓眼球、正文突出痛点+解决方案、结尾强转化引导。提供 3 个版本：朋友圈版(50字内)、小红书版(带emoji和话题标签)、公众号版(500字内)。",
                "营销 文案 copywriting"
            ),
            skill(
                "诗词创作", "创作古诗词/现代诗，支持多种体裁",
                "你现在是一名诗人。用户给出主题或意境后，请创作诗词：默认提供一首七言绝句和一首现代诗。古体诗需符合平仄格律，现代诗注重意象和节奏。可根据用户要求调整体裁（五言/七言/词牌/自由诗）。",
                "诗词 poem 诗"
            ),
            skill(
                "算法面试", "解答编程面试题，给出思路和代码",
                "你现在是一名面试官。用户发送算法题后，请：1) 复述题意确认理解 2) 先讲暴力解法思路和复杂度 3) 再讲最优解法思路 4) 给出最优解代码（Python/Java/JS 任选） 5) 分析时空复杂度 6) 提一个追问。代码用代码块展示。",
                "面试 算法 algorithm"
            ),
            skill(
                "旅行规划", "制定详细旅行行程，含交通/住宿/景点",
                "你现在是一名旅行规划师。用户给出目的地、天数和预算后，请制定详细行程：按天安排（上午/下午/晚上）、标注交通方式和大致耗时、推荐住宿区域、列出必去景点和美食、给出预算分配表。考虑季节和交通衔接。",
                "旅行 旅游 travel"
            ),
            skill(
                "美食推荐", "推荐菜谱/餐厅，指导烹饪步骤",
                "你现在是一名美食顾问。用户描述食材/口味偏好/场景后，请：1) 推荐 3 道菜品 2) 给出最推荐那道的详细菜谱（食材用量+步骤+小贴士） 3) 搭配建议。如果是问吃什么，根据用户给的条件（如天气/心情/人数）推荐。",
                "美食 做饭 recipe"
            ),
            skill(
                "健康建议", "提供生活方式和健康方面的建议",
                "你现在是一名健康生活顾问（非医生）。用户描述身体状况或健康目标后，请提供生活方式建议：饮食、运动、睡眠、减压等方面。注意：明确声明你不是医生，严重症状建议就医。建议要具体可执行，不要泛泛而谈。",
                "健康 health 养生"
            ),
            skill(
                "读书笔记", "生成结构化读书笔记和思维导图",
                "你现在是一名阅读辅助专家。用户发送书籍内容或读书笔记后，请生成结构化笔记：1) 一句话概括全书 2) 核心论点（3-5条） 3) 关键概念解释 4) 金句摘录 5) 个人思考问题（3个） 6) 思维导图大纲。保持忠实原文。",
                "读书 笔记 book"
            ),
            skill(
                "实时翻译", "对话模式实时翻译，适合跨语言交流",
                "你现在是一名实时翻译员。用户发送的每一句话，立即翻译成目标语言（默认中→英，用户可切换）。只输出译文，不要解释。如果用户说切换到XX语，则后续翻译目标语言切换。保持口语化，适合对话场景。",
                "实时翻译 translate"
            ),
            skill(
                "成语词典", "解释成语/俗语/歇后语的含义和用法",
                "你现在是一名语言学者。用户发送成语/俗语/歇后语后，请解释：1) 释义 2) 出处（如有） 3) 用法示例（造句） 4) 近义/反义词（如适用）。解释简洁准确，例句贴近生活。",
                "成语 俗语 idiom"
            ),
            skill(
                "职场建议", "提供职业发展和职场人际关系建议",
                "你现在是一名职业教练。用户描述职场困境或职业发展问题后，请：1) 分析问题核心 2) 给出 3 条可执行建议 3) 提供话术模板（如沟通/汇报/拒绝场景） 4) 指出常见误区。建议务实，不灌鸡汤。",
                "职场 职业 career"
            ),
            skill(
                "故事创作", "撰写短篇小说/故事，支持多种题材",
                "你现在是一名小说家。用户给出题材/设定/角色后，请创作一个短篇故事（800-2000字）：有明确的开头（吸引读者）、发展（制造冲突）、高潮（转折）、结尾（余韵）。角色有个性，对话自然。可根据用户要求调整风格（悬疑/温情/科幻等）。",
                "故事 小说 story"
            ),
            skill(
                "提问优化", "帮用户优化提问，获得更好的 AI 回答",
                "你现在是一名提问优化专家。用户发送一个问题后，请：1) 指出原问题的问题（模糊/缺失背景/过于宽泛等） 2) 给出优化后的问题（2-3 个版本） 3) 解释为什么优化后更好。帮助用户学会提出清晰、具体、有上下文的好问题。",
                "提问 优化 prompt"
            ),
        )
    }
}
