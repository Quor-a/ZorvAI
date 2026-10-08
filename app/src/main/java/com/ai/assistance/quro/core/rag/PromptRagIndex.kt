package com.ai.assistance.quro.core.rag

/**
 * 系统提示词 RAG：把「一次性全量注入」变成「按意图选块注入」。
 *
 * ## 为什么提示词也要 RAG
 * 工具 RAG 省的是 `tools` 字段。但系统提示词（[com.ai.assistance.quro.core.soul.QuroSoulPromptEngine]
 * 与平台基座、能力层说明）同样每轮全量下发，而且它的内容**高度分化**：
 * 讲「怎么说话」的段落和讲「怎么调工具」的段落，对一次「帮我算个数」的请求毫无价值。
 * 全量注入的代价不只是 token，更是**注意力被稀释**——
 * 模型在一堆当前无关的规则里，容易漏掉真正该守的那条。
 *
 * ## 🔴 旧架构完整保留（本类不替换任何东西）
 * [Mode.FULL_ALWAYS] 仍然可用（显式选项，用户可开），且 [render] 在任何情况下都会
 * **先拼完整基座**，只把「可选的进阶段落」改成按需追加。也就是说：
 * - 关掉 RAG → 行为与改动前逐字一致；
 * - 开 RAG → 关键段落一条都不少，只是不相关的段落不再每轮刷屏。
 *
 * ## 🔴 2026-10-08 全面排查：5 个病灶全部是**探针实测**，不是推测
 *
 * 本轮用临时探针（跑真实场景打印分数与注入量）查出 5 个实锤缺陷，逐个修掉：
 *
 * 1. **`stickyBlocks` 恒为空** —— 8 个块里 `alwaysSticky = true` 出现 **0 次**，
 *    于是 [stickyBlocks] 永远返回空列表。实测 `SELECTIVE` 模式追加 **0 字符**：
 *    注释里写的「基础段无条件注入」是**死代码**，SELECTIVE 实际等于「什么都不注入」。
 *    → 修法：把真正每轮都该守的 [tool_discipline] 与 [output_format] 定为 `alwaysSticky`。
 *
 * 2. **prompts 域召回被系统性杀死** —— `MIN_SCORE = 0.62` 是给 269 篇的**工具域**定的，
 *    而 `fieldScore` 用 `coverage = hit / qt.size`：一句 10 token 的中文查询
 *    （「这些数据做成表格给我看」切出 10 个 bigram）最多只能拿 0.1 的覆盖率。
 *    实测 `output_format` 明明有「表格」触发词，top1 只有 **0.318** < 0.62 → **零命中**。
 *    → 修法：prompts 域用独立门槛 [PROMPT_MIN_SCORE]（域小、块少、每段都长，
 *      阈值必须与工具域脱钩）。
 *
 * 3. **规则正文被截到 200 字** —— `description = body.take(200)` 让 200 字之后的内容
 *    对 lexical/shape 通道**完全不可见**。实测 `cluster_orchestration` 有 **1416 字，
 *    86% 检索不到**；`ui_delivery`(364)、`tool_discipline`(285)、`code_execution`(224)
 *    同样被砍。
 *    → 修法：prompts 域 [PromptBlock.toDoc] 用**正文全文**做 description。
 *
 * 4. **AI 主动查时拿不到正文** —— [RagHit.toJson] 只回 `description`，而
 *    `RagSearchTool` 从不读 `doc.payload`（真正的 [PromptBlock]）。
 *    于是 `rag_search(domain="prompts")` 命中了也**只给 200 字碎片**。
 *    → 修法：[PromptRagIndex.asRuleResult] 把 payload 里的完整正文交给工具侧回传。
 *
 * 5. **RAG 被当成主要方案** —— [Mode.FULL_ALWAYS] 是默认，实测每轮追加 **3182 字符
 *    （≈2100 tokens）**，其中集群段独占 1416。用户要求「改成 AI 被动使用、
 *    RAG 是备用方案不是主要方案」。
 *    → 修法：默认改为 [Mode.SELECTIVE]，主路径改成 AI 主动调 `rag_search`
 *    （见 [RagSearchTool]）；`FULL_ALWAYS` 保留为显式可选。
 */
object PromptRagIndex {

    /** 域标识。 */
    const val DOMAIN = "prompts"

    /**
     * 注入策略。
     *
     * @param FULL_ALWAYS 全量注入 8 段。实测每轮 **3182 字符（≈2100 tokens）**，
     *   且 [cluster_orchestration] 一段就占 1416 字 —— 这就是「把 RAG 当主要方案」。
     *   **保留但不再默认**，需要时才显式开。
     * @param SELECTIVE 基础段常驻 + 进阶段按意图 RAG 选段。**默认**。
     *
     * ## 🔴 为什么默认是 SELECTIVE 而不是 FULL_ALWAYS
     *
     * 用户要求「改成 AI 被动使用，系统提示词 RAG 是**备用方案不是主要方案**」。
     * SELECTIVE 才符合这个语义：每轮只灌真正每轮都要守的基础段（[stickyBlocks]），
     * 其余规则由 **AI 自己调 `rag_search(domain="prompts")` 主动取**。
     * 也就是说检索从「宿主塞给 AI」变成「AI 按需拉」，这才是被动使用。
     */
    enum class Mode { SELECTIVE, FULL_ALWAYS }

    /**
     * 一个提示词块。
     *
     * @param id 块标识。
     * @param title 块标题（会作为 markdown 标题注入）。
     * @param body 块正文。
     * @param alwaysSticky true=基础段，无条件注入，不参与 RAG 筛选。
     * @param triggers 该块适用的场景触发词（供 [RagConcept] 与关键词使用）。
     * @param payload 块正文原文，命中后交还调用方。
     */
    data class PromptBlock(
        val id: String,
        val title: String,
        val body: String,
        val alwaysSticky: Boolean = false,
        val triggers: List<String> = emptyList(),
        val keywords: List<String> = emptyList(),
    ) {
        /**
         * 🔴 `description` 用**正文全文**，不截断到 200 字。
         *
         * 旧实现 `body.take(200)` 实测害了两处：
         * - 200 字之后的内容对 lexical/shape 通道**完全不可见**，
         *   `cluster_orchestration`（1416 字）有 86% 检索不到；
         * - `rag_search` 回显的就是 `description`，AI 命中了也只拿到碎片。
         *
         * 工具域那边仍是短描述（工具本来就短），所以只有 prompts 域改全文。
         */
        fun toDoc(): RagDoc = RagDoc(
            id = id,
            name = id,
            title = title,
            description = body,
            keywords = keywords,
            triggers = triggers,
            concepts = triggers,
            // 基础段优先权最高：它无条件注入，不需要靠排序取胜。
            priority = if (alwaysSticky) 1.0 else 0.6,
            hintBoost = 0.3,
            payload = this,
        )
    }

    /**
     * 进阶段落库。
     *
     * 🔴 这些段落的共同点：**只在特定场景下才相关**，而过去每轮都在。
     * 覆盖的全是用户真实会遇到、且规则说错就真出错的场景。
     */
    val blocks: List<PromptBlock> = listOf(
        PromptBlock(
            id = "tool_discipline",
            title = "工具调用纪律",
            body = """
                ## 工具调用纪律
                - 你拥有大量工具，但每轮只下发**已加载**工具的真实 schema。
                绝大多数工具不会自动出现在你的 tools 字段里。
                - **只要你认为自己没有某个能力（哪怕从没听说过对应工具），必须先用
                  `tool_router.match_intent(intent=用户原话)` 查一遍再说**。
                工具目录里可能就有它。绝不凭猜报「我不会」。
                - 查到候选后用 `tool_router.get_schema(name=...)` 加载完整参数，下一轮起即可直接调用。
                - 工具返回失败时，先读错误信息再决定重试或换路，不要用同样的参数反复重试。
            """.trimIndent(),
            triggers = listOf("工具", "调用", "能力", "做不到", "不会", "怎么用", "有什么工具", "工具列表"),
            keywords = listOf("tool_router", "match_intent", "get_schema", "rag_search", "工具检索", "按需加载"),
            // 🔴 基础段：每轮都必须守「不确定就去查，别猜」。这条是整套 RAG 的入口纪律，
            // 若它也变成「按需检索」，AI 根本不会去检索 —— 那才是真的检索不了。
            alwaysSticky = true,
        ),
        PromptBlock(
            id = "output_format",
            title = "输出格式纪律",
            body = """
                ## 输出格式纪律
                - 工具结果是**给模型看的文本**，不是给用户看的排版内容。
                  不要把工具返回的原始文本直接复述给用户，要提炼后用自己的话讲清楚。
                - 工具返回本地文件路径时，若用户想看到它，调 `attach_file` 挂到气泡里。
                - 需要精确排版（表格 / 图表 / 海报 / 代码卡）时用可视化组件工具或 CodeCanvas 出图，
                  不要用 markdown 表格硬凑。
                - 🔴 **不要反复用同一个组件类型**。名册里有 101 种（`card_catalog`
                  能按效果模糊检索，也有全程常驻的 `ui_widget` / `ui_card`）。
                  同一段对话里若上一张用的是表格，这次数据换了性质就该换组件：
                  排名 → ranking、占比 → pie、趋势 → chart/line、完成度 → gauge/speedometer、体育比分 → scoreboard、
                  时间线 → timeline、多维打分 → matrix、分组对比 → compare、进度安排 → kanban/gantt。
                  **拿不准时先 `card_catalog(find="用户想要什么效果")` 查一次**，
                  比凭记忆瞎选一个类型强得多。
            """.trimIndent(),
            triggers = listOf(
                "格式", "排版", "表格", "显示", "展示", "出图", "图片", "挂载", "文件",
                // 🔴 2026-10-08 补组件选型触发词（用户报「老是使用同一个类型组件」）。
                // 这些词不进 triggers 的话，用户说「排名 / 趋势 / 占比 / 时间线」时
                // 本段根本召不回，AI 就退回到最熟的 table/keyvalue。
                "排名", "排行", "趋势", "占比", "比例", "时间线", "对比", "仪表盘",
                "打分", "评分", "进度", "看板", "热力图", "散点", "甘特", "漏斗",
            ),
            keywords = listOf(
                "attach_file", "ui_card", "ui_widget", "codecanvas", "输出格式",
                "card_catalog", "find", "ranking", "scoreboard", "gauge", "pie", "timeline",
                "compare", "heatmap", "speedometer", "kanban", "gantt",
            ),
            // 🔴 基础段：用户报的两条硬伤（组件老是同一个类型、产物只出标题不给正文）
            // 都出在这条纪律上 —— 每轮都得看见，不能等「检索到表格」才给。
            alwaysSticky = true,
        ),
        PromptBlock(
            id = "ui_delivery",
            title = "界面交付路径",
            body = """
                ## 界面交付路径（选错会白干，务必分清）
                - **小卡片 / 可视化图表** → `ui_card` / `ui_widget`（对话气泡内的富卡片）
                - **动态 UI 组件（可交互）** → `ui_control` / `quro-ui` 原生组件
                - **整屏界面** → GenUI 画布（`genui_draw`）
                - **Web 应用 / 小程序** → `miniapp`
                - **可视化弹窗 / 询问** → `visual_popup` / `visual_ask`
                - **出图（静态图片）** → `codecanvas_onscreen_*`（端��，无需服务器）
                - ⚠️ 这六条路径**互不等价**，不要用一个凑另一个。需要「能点」就用可交互组件，
                  需要「能看」用富卡片，需要「整屏」用画布。
            """.trimIndent(),
            triggers = listOf("界面", "卡片", "组件", "画布", "页面", "弹窗", "小程序", "可视化", "交互"),
            keywords = listOf("ui_card", "ui_widget", "ui_control", "genui", "miniapp", "codecanvas"),
        ),
        PromptBlock(
            id = "memory_policy",
            title = "记忆策略",
            body = """
                ## 记忆策略
                - 用户的偏好 / 习惯 / 重要约定值得长期记住时，主动调 `memory_save`，
                  不需要用户明确要求。
                - 用户提到「之前说过 / 上次告诉过你 / 你还记得吗」时，先 `memory_search` 再回答，
                  **绝不凭印象编造过往对话内容**。
                - 记忆是跨会话的，检索无结果时要明确说「我没有相关记忆」，不要虚构。
            """.trimIndent(),
            triggers = listOf("记住", "记忆", "之前", "上次", "还记得", "我说过", "忘了吗"),
            keywords = listOf("memory_save", "memory_search", "knowledge_rag_search", "长期记忆"),
        ),
        PromptBlock(
            id = "device_control",
            title = "设备控制与无障碍",
            body = """
                ## 设备控制
                - 操作手机界面用无障碍工具族：`read_screen` → `tap_screen` / `swipe_screen` /
                  `input_text`。**先读屏再操作**，不要盲点坐标。
                - 需要 Shizuku / ROOT 权限才能做的操作，先查 `shizuku_status` / `root_status`，
                  权限不足时明确告知用户如何授权，而不是反复重试。
                - 涉及破坏性操作（删除文件、卸载应用、清数据）前先向用户确认。
            """.trimIndent(),
            triggers = listOf("点击", "滑动", "控制手机", "屏幕", "权限", "root", "shizuku", "删除", "卸载"),
            keywords = listOf("read_screen", "tap_screen", "swipe_screen", "input_text", "无障碍", "shizuku"),
        ),
        PromptBlock(
            id = "code_execution",
            title = "代码执行与构建",
            body = """
                ## 代码执行与构建
                - 跑代码用 `run_code` / `terminal_exec`；终端与 Linux 环境是两套，
                  先查 `terminal_status` / `linux_status` 再决定用哪个。
                - 构建 APK 用 `build_apk`，产出后用 `export_apk` 导出；
                  **不要自己拼 zip 冒充 APK**。
                - 脚本报错时读完整的 `Caused by` 链（常在中间段，不要只看第一行）再改。
            """.trimIndent(),
            triggers = listOf("代码", "脚本", "运行", "编译", "打包", "构建", "apk", "终端", "报错", "调试"),
            keywords = listOf("run_code", "terminal_exec", "build_apk", "export_apk", "调试"),
        ),
        PromptBlock(
            id = "media_generation",
            title = "图像与媒体生成",
            body = """
                ## 图像与多媒体生成
                - **AI 生图**（照片级、有随机性）→ `image_gen`；
                - **确定性渲染**（海报/报表/流程图/代码卡，同输入必同输出）→ `codecanvas_onscreen_*`。
                - 视频处理（剪辑 / 转码 / 截取）→ ffmpeg 工具族。
                - 语音播报 → `speak`；语音识别 → 录音 + ASR 工具。
            """.trimIndent(),
            triggers = listOf("画", "出图", "图片", "照片", "视频", "剪辑", "音频", "语音", "播报", "海报", "流程图"),
            keywords = listOf("image_gen", "codecanvas_onscreen_script", "codecanvas_onscreen_markup", "ffmpeg", "speak"),
        ),
        PromptBlock(
            id = "cluster_orchestration",
            title = "多角色集群协作",
            body = """
                ## 多角色集群协作
                集群**不是你自己扮演的角色**，而是一组现成的工具。真正的集群主持是一个
                独立程序（`persona_cluster_host`），由它拆解目标、点名角色、验收、决定收尾。
                - 复杂目标（需要多种专业能力协作、单轮对话搞不定）→ 调用 `cluster_start` 工具，
                  把目标交给它跑。参数：`goal`（必填）、`acceptance`（可选验收标准）、`sync`
                  （默认 true=等它跑完再回话；false=只拿任务 ID）。
                - 长任务不要干等：`sync=false` 拿 `taskId`，之后用 `cluster_status` 查进度。
                - 🔴 **绝对不要自己扮演主持、不要假装有角色在讨论**。你看不到集群内部发生了什么，
                  编造出来的「主持说/角色答」全是假的。要集群干活就调工具。
                - 需要调整集群本身时用 `cluster_roles`（列角色）、`cluster_models`（列可用模型）、
                  `cluster_enroll`（把一张人格卡招进集群）、`cluster_remove_role`（把角色移出集群）、
                  `cluster_bind_model`（给某个角色换模型）、`cluster_host_config`（改主持熔断）、
                  `cluster_abort`（中止正在跑的任务）。
                - **角色卡 = 一个 skills 聚合**：建角色首选 `cluster_rolecard`
                  （mode=list 看有哪些卡；mode=create 用卡建角）。一张卡同时给出分工、
                  灵魂注入正文、以及一整组技能，建完角技能已自动绑好 —— 用户不该自己去
                  几十个技能里一格格挑。内置卡覆盖 UI 设计师/前端开发/网站部署/手机操作员/
                  规划师/策划师/写作/文档/办公文档/评审。
                - **本机没有的手艺，去开源社区拿**（技能库只有随包那几十个）：
                  `cluster_skill_search` 搜开源技能（已接 Anthropic 官方技能集、Superpowers
                  工程方法论、Composio 技能集）→ `cluster_skill_install` 下载装进技能库
                  → 装完可 `cluster_skill_grant(autoGrant=personaId)` 直接绑给某个角色。
                - **给角色配技能**（已有技能时）：`cluster_skill_market` 查技能拿到 skillId，
                  或直接 `cluster_skill_grant` 传 skillNames（按名字绑，免查 id），
                  也可把 `role.profile` 里的 toolWhitelist 留给按需裁剪。
                  绑定后技能正文会真正注入该角色，它才具备那门手艺。
                - 用户要「招个 UI 设计师」这类事时：优先 `cluster_rolecard(mode=create,
                  cardId=ui-designer)` 一步到位；卡片不够用再 `cluster_enroll` + 手动配技能。
                - 外部技能（用户自己写的 SKILL.md、团队规范）用 `cluster_skill_import` 导入技能库。
                - 用户也可在「设置 → 多角色集群」页面里招人、用角色卡建角、装开源技能、调熔断、发起任务。
            """.trimIndent(),
            triggers = listOf("集群", "主持", "角色", "角色卡", "分工", "协作", "多agent", "编排", "子任务", "任务图", "技能", "技能市场", "开源技能", "专家"),
            keywords = listOf(
                "cluster_start", "cluster_status", "cluster_roles", "cluster_models",
                "cluster_enroll", "cluster_remove_role", "cluster_bind_model",
                "cluster_host_config", "cluster_abort",
                "cluster_skill_market", "cluster_skill_grant", "cluster_skill_import",
                "cluster_skill_search", "cluster_skill_install", "cluster_rolecard",
                "多角色集群", "集群主持", "技能市场", "角色卡", "开源技能",
            ),
        ),
    )

    /** 基础段：无条件注入，不参与筛选。 */
    val stickyBlocks: List<PromptBlock> get() = blocks.filter { it.alwaysSticky }

    /**
     * 🔴 prompts 域**独立**的召回门槛，刻意低于 [RagEngine] 的 `MIN_SCORE(0.62)`。
     *
     * ## 为什么必须脱钩（实测，不是推测）
     *
     * `fieldScore` 的覆盖率算法是 `coverage = hit / qt.size` —— 分母是**整句** token 数。
     * 中文按 bigram 切分后，一句 10 字查询就是 10 个 token，于是**单个触发词最多只值 0.1 覆盖率**。
     * 实测数据：
     *
     * | 查询 | prompts 域 top1 | 结果 |
     * |---|---|---|
     * | `这些数据做成表格给我看` | `output_format` **0.318** | 零命中 |
     * | `帮我查一下明天的天气` | `tool_discipline` **0.141** | 零命中 |
     * | `帮我把这个趋势用图表显示出来` | `output_format` 0.900 | 命中但 < `WEAK_LINE(1.0)` → 被 explain 判「没召回到」 |
     *
     * `output_format` 的 triggers 里**明明有「表格」**，却因为整句稀释拿不到分。
     * 工具域能活下来是因为它有 269 篇、每篇 name/keywords 密度高；
     * prompts 域只有 8 篇、每篇正文很长，**照抄工具域阈值就是系统性召回失败**。
     *
     * 取 0.12：低于它的是「查询里连一个 prompts 域实词都没有」，
     * 实测噪声串 `zzzqqq不相关的东西` 在本域拿 0 分，安全。
     */
    const val PROMPT_MIN_SCORE = 0.12

    /**
     * prompts 域检索：独立门槛 + 全文参与。
     *
     * 供 [com.ai.assistance.quro.core.tools.RagSearchTool] 在 `domain="prompts"` 时调用。
     */
    fun searchPrompts(engine: RagEngine, query: String, limit: Int = 5): List<RagHit> =
        runCatching { engine.search(query, DOMAIN, limit.coerceIn(1, 8), PROMPT_MIN_SCORE) }
            .getOrDefault(emptyList())

    /**
     * 从检索结果里取出**完整规则正文**（[PromptBlock.body]），不是 200 字碎片。
     *
     * 🔴 这是「AI 被动使用」成立的关键：AI 主动 `rag_search` 时若只拿到摘要，
     * 它拿到的还是「半条规则」，照样会用错。必须把 payload 里的全文交出去。
     */
    fun ruleBodyOf(hit: RagHit): String? =
        (hit.doc.payload as? PromptBlock)?.body

    /**
     * prompts 域结果渲染成给模型看的正文清单（供工具 explain 模式复用）。
     * 无命中时返回空串，由调用方决定措辞。
     */
    fun renderFullBodies(hits: List<RagHit>): String = buildString {
        hits.forEach { h ->
            val body = ruleBodyOf(h)
            if (body != null) {
                append("### ").append(h.doc.title.ifBlank { h.id }).append('\n').append(body).append("\n\n")
            }
        }
    }

    /** 把提示词块灌进引擎。 */
    fun install(engine: RagEngine) {
        engine.clearDomain(DOMAIN)
        blocks.forEach { engine.register(DOMAIN, it.toDoc()) }
    }

    /**
     * 组装最终注入文本。
     *
     * @param mode [Mode.FULL_ALWAYS] 时返回**全部**块，逐字等价于改动前的全量注入；
     * @param userQuery 本轮用户输入，用于筛选进阶段（基础段不受影响）。
     *
     * 返回值保证以两个换行结尾（除非一个块都没追加）。
     * 调用方是这么用的：sb.append(render(..., base = sb.toString()))，
     * 紧接着还要拼语言复述段；块正文末尾没有换行的话两段会黏成一句，
     * 而模型对黏在一起的指令非常容易只读前半句。
     */
    fun render(engine: RagEngine, mode: Mode, userQuery: String, base: String): String {
        val sb = StringBuilder(base)
        val before = sb.length
        if (mode == Mode.FULL_ALWAYS) {
            blocks.forEach { appendBlock(sb, it) }
            return tail(sb, before)
        }
        // 基础段无条件
        stickyBlocks.forEach { appendBlock(sb, it) }
        // 进阶段按意图选；检索不到就一个都不加——宁缺毋滥，塞无关规则只会稀释注意力。
        if (userQuery.isNotBlank()) {
            // 🔴 用 prompts 域自己的门槛，不用引擎默认的 MIN_SCORE(0.62)，
            // 也不用旧代码硬写的 1.2（1.2 比 0.62 更严，实测把「出张海报」之外的
            // 大多数真查询也一起砍了）—— 依据见 [PROMPT_MIN_SCORE] 的实测表。
            val picked = searchPrompts(engine, userQuery, limit = 3)
            val chosen = picked.mapNotNull { it.doc.payload as? PromptBlock }.distinctBy { it.id }
            if (chosen.isNotEmpty()) {
                sb.append("\n\n## 本轮相关补充规则（按你的问题自动检索）\n")
                chosen.forEach { appendBlock(sb, it) }
            }
        }
        return tail(sb, before)
    }

    /** 一个块都没追加时原样返回 base；否则去掉尾部空白并补足两个换行。 */
    private fun tail(sb: StringBuilder, before: Int): String {
        if (sb.length == before) return sb.toString()
        return sb.toString().trimEnd() + "\n\n"
    }

    private fun appendBlock(sb: StringBuilder, block: PromptBlock) {
        if (sb.isNotEmpty()) sb.append("\n\n")
        sb.append("### ").append(block.title).append('\n').append(block.body)
    }
}