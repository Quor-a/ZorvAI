package com.ai.assistance.quro.core.cluster

import android.content.Context
import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.cluster.ClusterSkillStore.ClusterSkill
import com.ai.assistance.quro.core.rag.ClusterSkillRagIndex
import com.ai.assistance.quro.core.tools.ToolCapabilityDirectory

/**
 * #190 集群的「能力运行时」—— 一个角色凭什么能干活，全在这里决定。
 *
 * ## 为什么要有这个类
 *
 * 重写集群时，角色被定义成了一张**介绍卡**：
 * ```
 * # 你具备的技能
 * - 懂 HTML
 * - 会写 Java
 * ```
 * 这两行字不产生任何约束力 —— 模型读到「会写 Java」，只能按训练时的通用知识凭空发挥，
 * 于是出现用户说的「没有相关 skills 能力怎么执行，凭空想象」。
 *
 * 根因是：**宿主早就有一套完整的技能系统**（`QuroSkillStore`，随包内置 67 个技能，
 * 每个技能带prompt 正文与 function-calling schema），而集群**从来没接进去**。
 * 技能库一直在那里，只是集群角色看都没看过。
 *
 * 本类把三条线一次接上：
 * 1. **技能聚合** —— 角色绑定的技能正文真正注入它的 system prompt；
 * 2. **工具权限** —— `RoleContextPolicy.toolWhitelist` 此前存了但没人读，
 *    现在真实裁剪下发列表（非空=只给这些，角色连看都看不到别的）；
 * 3. **能力自我感知** —— 角色知道「我手上有哪些工具和技能」，
 *    知道才能用；不知道就只会说「我做不到」。
 *
 * ## 为什么工具要裁剪而不是全给
 *
 * 旧代码在 [LlmGateway] 里硬写 `enableTools = false`，注释理由是
 * 「避免 N 角色 × 259 工具撑爆上下文」。这个顾虑是对的，但结论下错了：
 * 一刀切关掉工具 = 角色**彻底不能执行**，比上下文膨胀严重得多。
 *
 * 正确做法是**按角色裁剪**：主持只拿调度类工具，执行者拿实现类工具，
 * 评审一个都不拿。每个角色看到的工具数是可控的小集合，
 * 既不撑爆上下文，也真的能动手。
 */
object ClusterSkillRuntime {

    /**
     * 单个角色最多能拿多少个工具。
     *
     * 上限存在的理由与旧代码一致：全量 258 个工具的 definitions 约 129K 字符，
     * 加上角色自己的技能正文与历史，一次请求很容易顶到上下文上限，
     * 表现为「模型不响应」或「答非所问」。
     *
     * #215：从 20 提高到 60 —— 用户要求「对话框所有功能给集群兼容」，
     * 集群角色默认全开时（工具总数 < 300）不受此限制；只有工具多到失控
     * 走裁剪分支时，这个值才决定每个角色最多能拿到多少工具。
     */
    const val MAX_TOOLS_PER_ROLE = 60

    /**
     * #212：工具**全开**的上限。工具总数不超过它就一股脑全给，
     * 不再按「分工需要」裁剪（见 [toolsFor] 的注释）。
     *
     * 只有当工具多到会挤爆上下文时才退化成按需裁剪。
     *
     * #215：用户要求「对话框支持还是不够，完全分开对话框所有功能给集群兼容」——
     * 主对话能用的工具，集群角色也应当能用。原上限 80 会让拥有 200+ 工具的宿主
     * 永远走「按需裁剪」，集群角色只能看到一小撮工具，等于对话框功能没有开放给集群。
     * 提高到 300：只要工具总数没超过它，**一律全开**；只有工具多到失控才裁剪。
     */
    const val TOOL_FULL_OPEN_LIMIT = 300

    /** 单个角色注入的技能正文总字符上限。 */
    const val MAX_SKILL_TEXT_CHARS = 6000

    /**
     * #199：单个角色最多注入**几个**技能的正文。
     *
     * 为什么必须限个数而不是只限总字数：
     * 只限总字数时，预算会被最后一个技能吃掉前面的（累加到超预算就break），
     * 于是「绑 5 个技能」实际只注入了 1 个的全文 + 半个，
     * 角色拿到的是残缺信息。只限个数才能保证「每个技能的规矩都在」，
     * 再由 [MAX_SKILL_TEXT_CHARS] 做总量收口。
     */
    const val MAX_SKILLS_PER_ROLE = 5

    /**
     * #199：单个技能正文注入的字符上限。
     *
     * 方法论型技能正文动辄 3000~4300 字符（见 assets/cluster-skills/）。
     * 一个都不截的话，两个技能就顶满 [MAX_SKILL_TEXT_CHARS]。
     */
    const val MAX_CHARS_PER_SKILL = 2200

    /**
     * 分工 → 优先工具类别。
     *
     * 用 [ToolCapabilityDirectory.ToolCategory] 而不是硬编码工具名：
     * 工具名会随版本增删（259 个工具还在变），类别是稳定契约。
     * 同一类别内按「工具名语义打分」取前N 个，保证具体能力（如「操作手机」= ACCESSIBILITY）
     * 真的落到执行者手上。
     */
    private fun preferredCategories(kind: RoleKind): List<ToolCapabilityDirectory.ToolCategory> =
        when (kind) {
            // 主持只做调度：不需要亲手读文件/跑命令，给多了纯浪费上下文。
            RoleKind.HOST -> listOf(
                ToolCapabilityDirectory.ToolCategory.AI_CAPABILITIES,
                ToolCapabilityDirectory.ToolCategory.KNOWLEDGE_MEMORY,
            )
            // 规划：要读资料、要查能力，但不该动手改世界。
            RoleKind.PLANNER -> listOf(
                ToolCapabilityDirectory.ToolCategory.KNOWLEDGE_MEMORY,
                ToolCapabilityDirectory.ToolCategory.FILE_OPERATION,
                ToolCapabilityDirectory.ToolCategory.WORKSPACE,
                ToolCapabilityDirectory.ToolCategory.NETWORK_WEB,
            )
            // 执行：要什么给什么 —— 文件、终端、网络、UI、设备、媒体全上。
            RoleKind.EXECUTOR -> listOf(
                ToolCapabilityDirectory.ToolCategory.FILE_OPERATION,
                ToolCapabilityDirectory.ToolCategory.TERMINAL_LINUX,
                ToolCapabilityDirectory.ToolCategory.NETWORK_WEB,
                ToolCapabilityDirectory.ToolCategory.ACCESSIBILITY,
                ToolCapabilityDirectory.ToolCategory.WORKSPACE,
                ToolCapabilityDirectory.ToolCategory.DYNAMIC_UI,
                ToolCapabilityDirectory.ToolCategory.GENUI,
                ToolCapabilityDirectory.ToolCategory.UI_CARDS,
                ToolCapabilityDirectory.ToolCategory.MEDIA,
                ToolCapabilityDirectory.ToolCategory.CMS_DEVELOPMENT,
                ToolCapabilityDirectory.ToolCategory.AIP_DOC,
                ToolCapabilityDirectory.ToolCategory.AI_CAPABILITIES,
                ToolCapabilityDirectory.ToolCategory.PLUGIN,
            )
            // 专家：能出内容，也能查证。
            RoleKind.EXPERT -> listOf(
                ToolCapabilityDirectory.ToolCategory.NETWORK_WEB,
                ToolCapabilityDirectory.ToolCategory.KNOWLEDGE_MEMORY,
                ToolCapabilityDirectory.ToolCategory.AIP_DOC,
                ToolCapabilityDirectory.ToolCategory.AI_CAPABILITIES,
                ToolCapabilityDirectory.ToolCategory.MEDIA,
            )
            // 评审：只判对错，不碰任何会改变世界的东西（不给终端/文件/设备控制）。
            RoleKind.CRITIC -> listOf(
                ToolCapabilityDirectory.ToolCategory.KNOWLEDGE_MEMORY,
            )
        }

    /**
     * 分工 → 关键词打分表。类别解决「属于哪一族」，关键词解决「族内选哪个」。
     *
     * 例：执行者要「操作手机」，ACCESSIBILITY 族里 `screen_tap` / `swipe` 才对口，
     * 只按类别取前 N 会取到无障碍里的其他工具。
     */
    private fun kindKeywords(kind: RoleKind): List<String> =
        when (kind) {
            RoleKind.HOST -> listOf("cluster", "delegate", "schedule", "memory", "search")
            RoleKind.PLANNER -> listOf("read", "list", "search", "find", "query", "knowledge", "plan")
            RoleKind.EXECUTOR -> listOf(
                "write", "edit", "create", "exec", "run", "shell", "http", "fetch", "browser",
                "tap", "swipe", "click", "input", "screenshot", "build", "deploy", "file",
                "code", "image", "video", "install",
            )
            RoleKind.EXPERT -> listOf("search", "fetch", "analyze", "doc", "report", "image")
            RoleKind.CRITIC -> listOf("read", "list", "diff", "check", "verify")
        }

    /**
     * 为某个角色算出它真正能用的工具清单。
     *
     * 优先级（**高→低**）：
     * 1. [RoleContextPolicy.toolWhitelist] 非空 → **完全以它为准**，只给这些。
     *    显式配置永远压过自动推断 —— 用户手动圈定就是意图本身。
     * 2. 否则按 [preferredCategories] + [kindKeywords] 自动挑。
     * 3. 评审（CRITIC）恒定拿不到会改变世界的工具 —— 见下方硬过滤。
     *
     * @param allTools 宿主当前已注册的全部工具（通常是 `registry.fullSpecs()`）
     */
    fun toolsFor(
        context: Context,
        role: RoleProfile,
        allTools: List<QuroToolSpec>
    ): List<QuroToolSpec> {
        if (allTools.isEmpty()) return emptyList()
        val whitelist = role.context.toolWhitelist

        val picked: List<QuroToolSpec> = if (whitelist.isNotEmpty()) {
            // 显式白名单：按用户写的顺序保留，未知工具名静默丢弃（不报错，工具可能已下线）
            allTools.filter { it.name in whitelist }.take(MAX_TOOLS_PER_ROLE)
        } else if (allTools.size > TOOL_FULL_OPEN_LIMIT) {
            // 工具多到会挤爆上下文时才退化成按需裁剪
            pickByRelevance(role, allTools)
        } else {
            // 🔴 #212：**默认全开**。
            //
            // 原来这里按「分工需要」打分裁剪，最多只给 MAX_TOOLS_PER_ROLE(20) 个。
            // 实测后果：执行角色需要的 write_file / shell / browser 之类
            // 一旦没被关键词或类别命中就被砍掉，它**想干活却没有工具** ——
            // 于是只能输出一段文字说明，甚至什么都产不出来，
            // 验收方看到的就是「待验收产物为空」。用户原话：
            // 「所有成员开开放所有工具能力而不是只是给需要的」。
            //
            // 「给需要的」听起来合理，但**需要什么只有模型执行到那一步才知道**，
            // 提前替它猜，猜错就是整个子任务空转。
            // 真要限，用 toolWhitelist 显式圈定 —— 那是明确意图，不是猜测。
            allTools
        }

        // 🔴 #200：先硬过滤全局技能激活工具（`skill__xxx`），再过过评审禁用。
        //
        // 为什么集群不要它：全局技能的激活工具读的是**全局库**正文，
        // 而集群技能是独立库里的规程。一旦放进来，集群角色就能绕过集群技能体系，
        // 直接抽全局技能正文 ——隔离弱了一半，而且它还恰好命中关键词被强得分优先下发。
        val isolated = picked.filterNot { it.name.startsWith(GLOBAL_SKILL_TOOL_PREFIX) }
        return if (role.role.isVerifier) isolated.filterNot { it.name in MUTATING_TOOLS } else isolated
    }

    /**
     * #212：按需裁剪 —— **只在工具总数超过 [TOOL_FULL_OPEN_LIMIT] 时**才用。
     *
     * 分类 + 关键词打分，取前 [MAX_TOOLS_PER_ROLE] 个。
     * 保留它不是因为默认需要，而是给「工具真的多到失控」留一条退路。
     *
     * #215：上限从 20 提高到 60 —— 即使走到裁剪分支，也要让集群角色
     * 尽量拿到与主对话一致的工具面，而不是只给一丁点。
     */
    private fun pickByRelevance(role: RoleProfile, allTools: List<QuroToolSpec>): List<QuroToolSpec> {
        val cats = preferredCategories(role.role)
        val keywords = kindKeywords(role.role)
        val scored = allTools.map { spec ->
            var score = 0
            cats.forEachIndexed { idx, cat ->
                if (categoryOf(spec.name) == cat) {
                    // 越靠前的类别权重越高
                    score += (cats.size - idx) * 10
                }
            }
            val lower = spec.name.lowercase()
            keywords.forEach { kw -> if (lower.contains(kw)) score += 6 }
            spec to score
        }
        return scored.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(MAX_TOOLS_PER_ROLE)
            .map { it.first }
    }

    /** 查工具所属分类；目录未收录时返回 null（不抛异常）。 */
    private fun categoryOf(toolName: String): ToolCapabilityDirectory.ToolCategory? =
        runCatching { ToolCapabilityDirectory.getToolInfo(toolName)?.category }.getOrNull()

    /**
     * 评审角色的硬禁清单。
     *
     * 评审的价值在于「独立判断」，一旦它能改文件/跑命令/点屏幕，
     * 它就会顺手把不满意的产物直接改掉 —— 验收就永远不可能不通过。
     * 这不是提示词层面的软约束，是列表层面的硬过滤。
     */
    /**
     * 🔴 #200：全局技能激活工具的名前缀。
     *
     * 集群角色绝不得拿到它：它会把**全局库**的技能正文回灌给集群角色，
     * 绕过 ClusterSkillStore 直接抽全局规程 —— 隔离就是个空壳。
     */
    private const val GLOBAL_SKILL_TOOL_PREFIX = "skill__"

    private val MUTATING_TOOLS = setOf(
        "write_file", "edit_file", "delete_file", "write_text_file", "create_directory",
        "move_file", "copy_file", "shell", "run_shell", "exec_shell", "install",
        "uninstall", "screen_tap", "screen_swipe", "screen_input", "input_text",
        "press_key", "open_app", "set_volume", "set_brightness",
    )

    /**
     * 解析角色绑定的真技能。
     *
     * #217：**技能注入走 RAG**（用户最新诉求：「给集群加技能 skills 加 RAG」）。
     * #218：**技能装配走 [ClusterSkillEngine]**（用户最新诉求：「集群 skills 要实现
     * 自己的 skills 架构、引擎、依赖，等等使用做出来自己的功能」）。
     *
     * 旧实现把绑定的技能正文全量拼进 system prompt（受 [MAX_SKILLS_PER_ROLE] /
     * [MAX_SKILL_TEXT_CHARS] 限制），与当前子任务无关的技能也在刷屏。
     * 现在：
     * 1. 先把集群技能库注册成 [ClusterSkillRagIndex] 的 RAG 域（与主对话共用
     *    [com.ai.assistance.quro.core.rag.AgentRag.engine] 同一通道）；
     * 2. 按角色当前任务相关的能力词（[RoleProfile.duties]/[RoleProfile.skills]/
     *    任务上下文）RAG 检索，只把**命中当前任务**的技能正文注入；
     * 3. 未命中的绑定技能保留摘要行（summaryLines），角色知道它存在，
     *    需要时可 `rag_search(domain="cluster_skills")` 按需取全文。
     *
     * #218 新增：`resolveSkills` 委托给 [ClusterSkillEngine.assemble]，
     * 后者在 RAG 之前先做依赖闭包解析与工具需求校验。
     * 本函数保留为**兼容旧契约**的薄封装，供既有调用方（[ClusterEngine.callRole]）
     * 使用；返回的 [ResolvedSkills] 只含引擎产物中与旧契约一致的部分
     * （skills / summaryLines / bodies），引擎新增的 missingDependencies / cycles /
     * missingTools 由 [ClusterSkillEngine.assemble] 单独暴露，接入方可按需读取。
     *
     * @param role 角色档案，读它的 `skillIds`
     * @return (技能正文, 技能摘要行, 可function-calling 的技能)
     */
    fun resolveSkills(
        context: Context,
        role: RoleProfile,
        actualToolNames: Set<String> = emptySet(),
    ): ResolvedSkills {
        if (role.skillIds.isEmpty()) {
            return ResolvedSkills(emptyList(), emptyList(), emptyList())
        }
        // #218：委托给集群自己的技能引擎（依赖闭包 + 工具校验 + RAG 注入）。
        val assembled = ClusterSkillEngine.assemble(context, role, actualToolNames)
        return ResolvedSkills(
            skills = assembled.skills,
            summaryLines = assembled.summaryLines,
            bodies = assembled.bodies,
            missingTools = assembled.missingTools,
            missingDependencies = assembled.missingDependencies,
            cycles = assembled.cycles,
        )
    }

    /**
     * 把技能列表裁剪成可注入的三段产物。供测试直接调用。
     *
     * #199 新增两个隔离闸门：
     * - [MAX_SKILLS_PER_ROLE]：单角色最多注入几个技能的正文；
     * - **按 kind 分层注入**：主持/规划师只要「知道有这套规矩」，不要正文；
     *   执行者/评审才需要照着做。只截断不算隔离 —— 截断后所有人拿到的是同一坨残缺文本。
     *
     * #217：生产路径 [resolveSkills] 已改为 RAG 按需注入（只注入命中当前任务的技能正文），
     * 本函数保留为纯逻辑入口（测试与降级用），行为与旧版一致。
     */
    fun buildSkills(skills: List<ClusterSkill>, kind: RoleKind = RoleKind.EXECUTOR): ResolvedSkills {
        // 只注入启用的技能：被用户关掉的技能注入进去等于自我否定
        val usable = skills.filter { it.enabled }
            .sortedByDescending { it.updatedAt }
            .take(MAX_SKILLS_PER_ROLE)
        val lines = usable.map {
            "- 「${it.name}」${it.description.ifBlank { "（无说明）" }.take(80)}"
        }
        val fullText = when (kind) {
            RoleKind.EXECUTOR, RoleKind.CRITIC -> true
            RoleKind.HOST, RoleKind.PLANNER, RoleKind.EXPERT -> false
        }
        val body = if (fullText) buildBodies(usable) else buildSummaries(usable)
        return ResolvedSkills(usable, lines, if (body.isEmpty()) emptyList() else listOf(body.toString()))
    }

    /**
     * #217：把 RAG 命中的技能正文拼成可注入文本（全文模式）。
     *
     * 与旧 [buildSkills] 的预算语义一致：
     * - [MAX_SKILLS_PER_ROLE]：单角色最多注入几个技能；
     * - [MAX_SKILL_TEXT_CHARS]：总预算，超了就停并**明确写明截断**；
     * - [MAX_CHARS_PER_SKILL]：单技能超长时截断并注明。
     *
     * #218：改为 internal，供 [ClusterSkillEngine] 装配时调用。
     */
    internal fun buildBodies(skills: List<ClusterSkill>): String {
        val usable = skills.filter { it.enabled }
            .sortedByDescending { it.updatedAt }
            .take(MAX_SKILLS_PER_ROLE)
        val body = StringBuilder()
        var budget = MAX_SKILL_TEXT_CHARS
        for (s in usable) {
            val raw = s.prompt.trim()
            if (raw.isEmpty()) continue
            val text = if (raw.length > MAX_CHARS_PER_SKILL) {
                raw.take(MAX_CHARS_PER_SKILL) +
                    "\n\n（此技能正文过长，此处只给了摘要；完整内容见该角色技能清单）"
            } else raw
            if (text.length > budget) {
                body.appendLine()
                body.appendLine("## 技能「${s.name}」（已截断）")
                body.appendLine(text.take(budget))
                body.appendLine("（正文过长已截断，完整规则见技能页）")
                break
            }
            budget -= text.length
            body.appendLine()
            body.appendLine("## 技能「${s.name}」")
            body.appendLine(text)
            if (budget <= 0) {
                body.appendLine("（其余技能因篇幅未注入。本角色绑定的技能清单：${
                    usable.joinToString("、") { "「${it.name}」" }
                }。若某一门正是本次任务所需，明确说出来，由主持决定是否拆细本节点。）")
                break
            }
        }
        return body.toString()
    }

    /**
     * #217：摘要模式 —— 非执行型角色只拿「规程存在 + 何时用」，不拿整篇正文。
     * 主持/规划师的工作是调度与判断，不需要照着网页/文案规程去写。
     *
     * #218：改为 internal，供 [ClusterSkillEngine] 装配时调用。
     */
    internal fun buildSummaries(skills: List<ClusterSkill>): String {
        val usable = skills.filter { it.enabled }
            .sortedByDescending { it.updatedAt }
            .take(MAX_SKILLS_PER_ROLE)
        val body = StringBuilder()
        var budget = MAX_SKILL_TEXT_CHARS
        for (s in usable) {
            val raw = s.prompt.trim()
            if (raw.isEmpty()) continue
            val head = raw.lineSequence().takeWhile { it.isNotBlank() }.joinToString("\n")
            val lead = if (head.length > MAX_CHARS_PER_SKILL / 2) head.take(MAX_CHARS_PER_SKILL / 2)
            else head
            val text = lead + "\n（本技能是方法论型规程全文，执行细节需照着做时再取；此处只登记其存在与适用场景）"
            if (text.length > budget) {
                body.appendLine()
                body.appendLine("## 技能「${s.name}」（已截断）")
                body.appendLine(text.take(budget))
                body.appendLine("（正文过长已截断，完整规则见技能页）")
                break
            }
            budget -= text.length
            body.appendLine()
            body.appendLine("## 技能「${s.name}」")
            body.appendLine(text)
            if (budget <= 0) break
        }
        return body.toString()
    }

    /**
     * 生成「能力自我感知」段。
     *
     * 没有这一段，角色的行为是「我不知道我能不能做」→ 只能说「我无法操作手机」。
     * 有了这一段，它至少知道**手上有什么牌**，可以据此规划或明确说明限制。
     *
     * 关键设计：这里只列**名字与一句作用**，不列参数 schema ——
     * schema 已经在 tools 字段里了，重复一遍会白烧上下文。
     */
    fun capabilityAwareness(
        context: Context,
        role: RoleProfile,
        tools: List<QuroToolSpec>,
        skills: List<ClusterSkill>
    ): String = capabilityAwareness(
        context, role, tools,
        ResolvedSkills(skills = skills, summaryLines = emptyList(), bodies = emptyList()),
    )

    /**
     * #218：带技能引擎产物的能力自我感知。
     *
     * 在旧版基础上，额外把 [ResolvedSkills.missingTools] / [ResolvedSkills.missingDependencies]
     * / [ResolvedSkills.cycles] 告知角色：技能规程要求用某个工具但角色没有，角色必须如实
     * 说出来让主持补，而不是假装自己能做。
     */
    fun capabilityAwareness(
        context: Context,
        role: RoleProfile,
        tools: List<QuroToolSpec>,
        resolved: ResolvedSkills,
    ): String = buildString {
        appendLine("# 你现在实际拥有的能力")
        appendLine()
        appendLine("这是你本次任务**真实可用**的能力清单，不是你的想象。你必须用它来判断自己能不能做。")

        if (tools.isEmpty()) {
            appendLine()
            appendLine("## 工具")
            appendLine("**你没有可调用的工具。**")
            appendLine("- 你只能输出文字/代码/方案文本给人看。")
            appendLine("- 需要真正动手（写文件、跑命令、操作设备、联网检索）时，**明确写出你需要的动作与参数**，")
            appendLine("  由主持转交有工具的角色执行 —— 不要假装自己已经做了。")
        } else {
            appendLine()
            appendLine("## 工具（${tools.size} 个，可直接 function-calling 调用）")
            tools.forEach { spec ->
                val desc = spec.description.lineSequence().firstOrNull()?.trim().orEmpty()
                appendLine("- `${spec.name}`：${desc.take(60)}")
            }
            appendLine()
            appendLine("调用规则：")
            appendLine("- 工具名必须与上面**逐字一致**，参数必须符合该工具的 JSON Schema。")
            appendLine("- 一次可以调多个工具；工具返回的内容会作为新的输入交给你。")
            appendLine("- 拿到结果后**基于结果继续判断**，不要停在第一次调用上。")
            appendLine("- 工具失败就把失败原因说清楚并换路径，不要编造成功。")
        }

        if (resolved.skills.isNotEmpty()) {
            appendLine()
            appendLine("## 技能（${resolved.skills.size} 项，规程正文已注入你的系统提示）")
            // 🔴 #196：不再分「可激活/仅规范」—— 集群技能一律只是提示词规程，
            // 没有 function-calling 形态。标成「可激活」会让角色去调一个不存在的工具。
            resolved.skills.forEach { sk ->
                appendLine("- 「${sk.name}」${sk.description.take(60)}")
            }
            // #217：技能走 RAG 按需取全文 —— 与主对话同一检索通道。
            appendLine()
            appendLine("## 技能按需检索（RAG）")
            appendLine("- 上面注入的是与当前任务**最相关**的技能正文。你绑定的全部技能以摘要行登记在上，")
            appendLine("  需要某门技能的完整规程时，调 `rag_search(query=\"<你要达成的效果>\", domain=\"cluster_skills\")`")
            appendLine("  按需取回**完整正文**，取回后照着执行。")
            appendLine("- 不确定自己有没有某门手艺时，**先 rag_search(domain=\"cluster_skills\") 搜一遍再说**，")
            appendLine("  不要凭记忆说「我不会」——技能库里可能就有。")

            // #218：依赖与工具需求校验结果必须如实告知。
            if (resolved.missingDependencies.isNotEmpty()) {
                appendLine()
                appendLine("## 技能依赖缺失")
                appendLine("- 你绑定的技能声明了依赖，但技能库里找不到：${resolved.missingDependencies.joinToString("、")}")
                appendLine("- 依赖缺失的技能可能不完整。需要时明确告诉主持，由主持补装或换技能。")
            }
            if (resolved.cycles.isNotEmpty()) {
                appendLine()
                appendLine("## 技能依赖环（已剔除）")
                appendLine("- 检测到依赖成环：${resolved.cycles.joinToString("；") { it.joinToString(" -> ") }}")
                appendLine("- 环上技能已从装配中剔除，避免死循环。需要时告诉主持调整依赖声明。")
            }
            if (resolved.missingTools.isNotEmpty()) {
                appendLine()
                appendLine("## 技能需要但你缺的工具")
                appendLine("- 你的技能规程声明需要这些工具，但你当前没有：${resolved.missingTools.joinToString("、")}")
                appendLine("- 不要假装能用它们。需要时明确告诉主持，由主持用工具配置/白名单补上，或换技能。")
            }
        }

        appendLine()
        appendLine("## 边界")
        appendLine("- 上面没列出的能力，你当前没有。需要时**如实说明缺什么**，然后：")
        appendLine("  - 若你能用现有工具/技能组合出解决办法，就直接动手；")
        appendLine("  - 若确实缺一门手艺，明确告诉主持「需要什么能力」，主持会通过 cluster_skill_search / cluster_skill_install / cluster_rolecard / cluster_forge_role 补上；")
        appendLine("  - 不要假装自己已经做了，也不要因为「没有现成能力」就拒绝任务 —— 缺能力就去创造。")
    }

    /** 技能解析产物。 */
    data class ResolvedSkills(
        /** 实际生效的技能对象（来自 [ClusterSkillStore]，非全局技能库） */
        val skills: List<ClusterSkill>,
        /** 供 UI/工具展示的一行摘要 */
        val summaryLines: List<String>,
        /** 注入提示词的技能正文（可能为空 —— 技能都禁用了） */
        val bodies: List<String>,
        /**
         * #218：技能声明需要、但角色实际没有的工具名。
         * 由 [ClusterSkillEngine] 汇总；接入方可据此告知角色缺什么。
         */
        val missingTools: List<String> = emptyList(),
        /**
         * #218：依赖声明了但库中不存在的技能 id/名。
         */
        val missingDependencies: List<String> = emptyList(),
        /**
         * #218：被检测出的依赖环（环上技能已从装配中剔除）。
         */
        val cycles: List<List<String>> = emptyList(),
    )
}
