package com.ai.assistance.quro.core.cluster

import android.content.Context

// #196/#197：集群用**自己的**技能库，与全局 QuroSkillStore 零耦合。
// 能力核对的语义是「这个角色真具备这项能力吗」，靠的是精确凭据，
// 不是「全局技能库里随便哪个沾点边」。
import com.ai.assistance.quro.core.cluster.ClusterSkillStore.ClusterSkill

/**
 * ★ 能力覆盖检查（CAPABILITY 阶段的核心逻辑）★
 *
 * ## 这解决什么问题
 *
 * 用户的原话：
 * > 「技能库是技能库，没有的技能，集群去哪里获取」
 * > 「没有相关的 skills 能力怎么执行，凭空想象」
 *
 * 拆成两半：
 * - 「去哪里获取」→ [ClusterOpenSkillHub] 解决（本地库 + 4 个开源源）；
 * - 「怎么保证执行前真的具备」→ **本类**解决。
 *
 * 之前即使装了一堆技能，也只是「躺在库里」。派活的时候没人检查
 * 「这个子任务需要的能力，到底有没有人具备」——
 * 于是模型看不清自己手上有没有牌，就只能按训练时的通用知识编。
 *
 * 所以本类的职责是**在派活之前把账算清楚**：
 * 1. 每个子任务需要什么能力（[ClusterNode.ability]）；
 * 2. 现有角色**真的绑了**哪些技能（读技能库，不是读 duties 文本）；
 * 3. 覆盖得住 → 放行；覆盖不住 → 给出**可执行的补救动作**（[Remedy]），
 *    而不是让模型自己编。
 *
 * ## 为什么打分要读技能正文而不只读技能名
 *
 * 角色档案里的 `duties` / `skills` 是**提示词文本**，模型可以声称自己有；
 * 只有 `skillIds` 指向技能库里真实存在的技能才是**可验证的能力**。
 * 所以覆盖度只按集群技能的 name / description / trigger / abilityWords 计算，
 * 角色自称会什么不算数。
 *
 * ## 补救动作为什么分三档（对应文档 A/B/C）
 *
 * 文档写得很清楚，这里逐条落地：
 * - [Remedy.GRANT_LOCAL] 本地已有这个技能，只是没人绑 → 直接绑给最合适的角色（不联网）；
 * - [Remedy.FETCH_OPEN] 本地没有 → 走开源社区装（要联网，失败必须如实回报）；
 * - [Remedy.FORGE] 连开源都找不到 → 造一个新角色（挂最接近的技能 + 明确告知缺什么）。
 *
 * 🔴 铁律：**任何一档补救失败都必须如实说**，绝不能静默放过 ——
 * 静默放过等于回到「凭空想象」，比报错更糟。
 */
object ClusterCapability {

    /** 一个子任务的能力需求与覆盖情况。 */
    data class Coverage(
        val nodeId: String,
        val nodeTitle: String,
        /** 该节点需要的能力描述（来自 [ClusterNode.ability]） */
        val ability: String,
        /** 0f~1f：现有角色对这个能力的最高覆盖度 */
        val score: Float,
        /** 覆盖度最高的角色（并列时取第一个） */
        val bestRoleId: String,
        /**
         * 角色**自己声明**的擅长（来自 duties/skills 文本）对该能力的命中度，0f~1f。
         *
         * 为什么单列成一个字段而不是并进 [score]：
         * 「真技能」与「口头声明」是**不对等**的证据，必须能分开看 ——
         * 用户看到「覆盖 0.42」时要知道这 0.42 里有多少是真本事。
         */
        val declared: Float = 0f,
        /** 是否认为「有人能做」——阈值见 [COVER_THRESHOLD] */
        val covered: Boolean,
    ) {
        val reason: String
            get() = when {
                ability.isBlank() -> "未声明能力需求，按文本相关度兜底"
                covered -> "已有角色覆盖（${(score * 100).toInt()}%）"
                else -> "无角色覆盖，缺「$ability」"
            }
    }

    /** 补救动作。 */
    enum class Remedy { GRANT_LOCAL, FETCH_OPEN, FORGE }

    /** 一次补救的结果。 */
    data class RemedyResult(
        val remedy: Remedy,
        val ok: Boolean,
        /** 人话说明，成功失败都写清楚 */
        val detail: String,
        /** 补救后新增/绑定的技能 id（供提示词与 UI 展示） */
        val skillIds: List<String> = emptyList(),
        /** 补救后涉及的角色 id */
        val roleId: String = "",
    )

    /**
     * 判定「有人能做」的阈值。
     *
     * 0.34 不是随手写的：一个能力描述一般由 3~6 个词构成，
     * 技能 name+description+trigger 全都命中才 1.0；命中 1/3 刚好过线。
     * 定太高会把大量真能干的角色判成「没人能做」，导致无谓地造角色；
     * 定太低会让空壳角色蒙混过关，又回到凭空想象。
     */
    const val COVER_THRESHOLD = 0.34f

    /**
     * 「角色自称擅长」这条证据的权重封顶。
     *
     * 必须**小于** [COVER_THRESHOLD] —— 只靠嘴说会的不算会，
     * 详见 [scoreRole] 的注释。
     */
    const val DECLARED_CAP = 0.30f

    /** 能力覆盖最低分（低于它连 FORGE 都不做，直接如实回报缺口）。 */
    const val MIN_USEFUL_SCORE = 0.12f

    // ——————————————— 能力词提取 ———————————————

    /**
     * 把能力描述切成可比对的词。
     *
     * 中文没有空格，按标点切完再按 2 字窗口补切：
     * 「设计落地页的视觉」→ [设计, 落地, 落地页, 视觉, 页的...] 里挑有信息量的。
     * 停用词表是为了不把「的 / 了 / 一个」这类字算成能力词。
     */
    private val STOP = setOf(
        "的", "了", "和", "与", "或", "一个", "一些", "进行", "相关", "方面", "能力",
        "需要", "要求", "必须", "能够", "可以", "这个", "那个", "我们", "以及", "等等",
        "使用", "通过", "用于", "关于", "对应", "包括", "包含", "实现", "完成", "做出",
    )

    fun abilityTerms(ability: String): List<String> {
        val raw = ability.trim().lowercase()
        if (raw.isBlank()) return emptyList()
        val byPunct = raw.split(Regex("[\\s,，。、；;：:（）()\\[\\]【】/+\\-—_·|]+"))
            .filter { it.isNotBlank() }
        val out = LinkedHashSet<String>()
        for (part in byPunct) {
            if (part in STOP) continue
            // 🔴 #202：`out += part` 原先**无条件**把切出的整块加进 terms。
            // 能力描述没有标点时（主持常写「产出可验收的方案片段」这种整句），
            // part 本身就是整句 → 整句进 terms →
            // 技能只要含「方案」二字（strategy）就算**整段命中**。
            // 实测导致 8 个技能同分 0.5，派单彻底没有区分度。
            //
            // 现在只收**够短**的整块（<=6 字，如「HTML/CSS」「联网搜索」），
            // 长句交给下面的 2 字窗口拆 —— 宁可漏掉整句精确匹配，
            // 也不能让「长句含短词」变成普遍误判。
            if (part !in STOP && part.length <= 6) out += part
            // 中文长词再切 2 字窗口
            // 🔴 #202：原来只切**前 4 个**窗口（`until minOf(4, ...)`），
            // 实测「承接功能目标定义子任务」只切出 [承接, 接功, 功能, 能目]，
            // 把真正的词「任务」「目标」「定义」全漏掉了 →
            // planning 技能的「任务分解」「拆解」永远撞不上，
            // 能力分只靠另一段拿 0.5，第一段0。
            // 现在切**全串**所有 2 字窗口：宁可多几个候选，也不漏掉真词。
            if (part.length >= 4 && part.all { it.code in 0x4E00..0x9FFF }) {
                for (k in 0 until part.length - 1) {
                    val w = part.substring(k, k + 2)
                    if (w !in STOP) out += w
                }
            }
        }
        // 🔴 #202：这里原来还有一句 `if (raw.length in 3..24) out += raw`，
        // 把**整句**当一个可匹配项参与匹配。那是假闭环的元凶之一：
        // 能力「产出可验收的方案片段」整串去撞技能干草堆，只要技能里出现
        // 「方案」二字（strategy 技能）就算**整段命中**；出现「验收」二字
        // （review 技能）也算整段命中。
        // 实测：同一句能力描述下，html-dev / planning / strategy / review /
        // code-review / ui-design / role-forge / frontend-design-playbook
        // **八个技能全部拿 0.5 分**，与真正该接单的 planning 完全同分 →
        // 派单层根本没有区分度，排序被 ③④（历史成功率+预算余量，共 30% 与任务无关）主导。
        // 这正是用户第 5 轮实测「策划任务派给前端开发角色」的真因。
        //
        // 整句精确匹配的价值，远小于它带来的「长句含短词 → 普遍误判」代价，故整条删除。
        return out.filter { it.length >= 2 }.toList()
    }

    // ——————————————— 片段级覆盖（#200E2E）————————————————

    /**
     * 主持拆解出来的节点标题固定形如「子任务1」「步骤2」「第 3 步」——
     * 纯编号标签，**不含任何能力信息**。
     *
     * 🔴 不能让它进能力分母：它永远匹配不上任何技能，白白把覆盖率压低一截，
     * 于是「明明有联网调研技能」被判成无人具备能力 → 节点被跳过 →
     * 全跳过却宣布达成（用户真机实测的假闭环）。
     */
    private val SEG_NOISE_LABEL = Regex(
        """^(子?任务|步骤|阶段|第)\s*\d+\s*[步项期]?\s*[、.]?$"""
    )

    /**
     * #200E2E：中文近义词组（同组内任一词视为等价）。
     *
     * 为什么必须有它：「研究」与「调研」是近义词，但共享 **0 个连续汉字**，
     * 双向 `contains` 无解 —— 这是 #197（中英断层）之外的**第二种断层**，
     * 同样是实测踩出来的：主持写「做研究并输出结论」，技能 abilityWords 里
     * 只有「调研」，命中恒为 0，节点直接被跳过。
     *
     * 收录标准：只收**强近义**（换个说法指同一件事），不收牵强联想——
     * 把「谁都能沾一点」的词塞进同一组会凭空派单，比漏派更坏。
     */
    private val SYNONYM_GROUPS: List<Set<String>> = listOf(
        setOf("研究", "调研", "调查", "查证"),
        setOf("搜索", "检索", "查询", "查找"),
        setOf("写作", "撰写", "撰稿", "起草"),
        setOf("文案", "宣传", "推广", "卖点"),
        setOf("设计", "策划", "构思"),
        setOf("前端", "网页", "页面", "界面"),
        setOf("审查", "评审", "验收", "核对"),
        setOf("测试", "验证", "自查", "校验"),
        setOf("优化", "改进", "提速"),
        setOf("输出", "产出", "交付"),
        // #202：实测「定义子任务」撞不上 planning 的「拆解/任务分解」——
        // 主持与策划对同一件事用不同词，且都不是对方的字面。
        setOf("拆解", "拆分", "分解", "拆任务", "任务分解", "定义子任务", "定义任务"),
        setOf("规划", "计划", "排期", "策划案", "方案"),
        setOf("目标", "指标", "目的"),
    )

    /** 能力片段的切分符：标点 + 空白。斜杠与连字符**保留**（「HTML/CSS」是一个整体）。 */
    private val SEG_SPLIT = Regex(
        """[\s,，。、；;：:（）()【】\[\]]+"""
    )

    /**
     * #200E2E：把能力描述切成**语义片段**。

     * 🔴 为什么不能拿「切碎的词」当覆盖度分母：
     * 中文没有空格，2 字窗口切出来的碎片（「究并」「并输」）跨词边界，
     * **永远不可能**命中任何技能，却把分母抬高一倍。
     * 实测：能力「子任务1 做研究并输出结论」被切成 7 个词，真正命中技能的只有 1 个
     * → 0.143 < 0.34 → 节点被跳过。
     *
     * 片段级口径：每段算一次「有没有证据覆盖」，编号标签这类噪音段直接剔除。
     * 于是阈值 0.34 的语义变得可解释：**技能覆盖了能力描述的三分之一以上**。

     */
    fun abilitySegments(ability: String): List<String> {
        val raw = ability.trim().lowercase()
        if (raw.isBlank()) return emptyList()
        return raw.split(SEG_SPLIT)
            .map { it.trim() }
            .filter { it.length >= 2 && !SEG_NOISE_LABEL.matches(it) }
            .distinct()
    }

    /**
     * 一个能力词的**全部可接受说法**：它本身 + 它所属近义组的其它词。

     * 「研究」的探针 = [研究, 调研, 调查, 查证]，于是
     * 「技能只有调研 / 能力写研究」也能撞上 —— #200E2E 的核心。
     */
    private fun probesFor(term: String): List<String> {
        val out = LinkedHashSet<String>()
        out += term
        for (g in SYNONYM_GROUPS) {
            if (g.any { term.contains(it) }) out += g
        }
        return out.toList()
    }

    /** #200E2E：技能覆盖了能力描述里的几段（分母是段数，不是切碎的词数）。 */
    private fun segmentHitCount(skill: ClusterSkill, segments: List<String>): Int =
        segments.count { seg -> hitCount(skill, abilityTerms(seg)) > 0 }

    // ——————————————— 覆盖度计算 ———————————————

    /**
     * 角色对某能力的覆盖度（文档 V2 第 5 页口径）。
     *
     * 两条**不对等**的证据：
     * 1. **真技能**（[RoleProfile.skillIds] 指向的技能 name/description/trigger）
     *    —— 主证据，权重 1.0，是唯一能独立越过 [COVER_THRESHOLD] 的依据；
     * 2. **角色声明的擅长**（duties + skills 文本，即文档说的 goodAt 关键词）
     *    —— 次证据，权重封顶 [DECLARED_CAP]。
     *
     * 🔴 封顶值 0.30 < COVER_THRESHOLD 0.34 是**故意**的：
     * 只靠嘴说自己会的不算会。封得太高（比如放到 0.5）就会出现
     * 「职责写了一句『设计』就派了设计单子」—— 那正是本阶段要消灭的凭空想象。
     *
     * 但它也不能是 0：文档明确要求 goodAt 也参与打分，
     * 完全砍掉会让「没绑技能包但职责写得很清楚」的正常角色被误判成没人会，
     * 节点直接 SKIPPED，用户只看到「什么都没发生」——
     * **误杀比漏判更糟**，因为漏判还有 [audit] 之后的补救兜着。
     *
     * @param role 该角色
     * @param ability 能力需求描述
     * @param skillLib 技能库快照（避免每个角色都重读一次 IO）
     */
    fun scoreRole(role: RoleProfile, ability: String, skillLib: Map<String, ClusterSkill>): Float {
        // #200E2E：分母单位是**语义片段**，不是切碎的词。
        val segments = abilitySegments(ability)
        if (segments.isEmpty()) return 0f
        val byId = role.skillIds.mapNotNull { skillLib[it] }
        // 这里**不能**因为没有真技能就 return 0f —— 那样「没绑技能包
        // 但职责写得很清楚」的角色永远拿不到任何分，会被 CAPABILITY 阶段
        // 直接判成无人具备能力 → 节点误杀。声明分仍要算（封顶见 DECLARED_CAP）。
        if (byId.isEmpty()) return declaredScore(role, abilityTerms(ability))

        // 启用状态参与打分：被用户关掉的技能不该算作「具备能力」
        val usable = byId.filter { it.enabled }
        if (usable.isEmpty()) return 0f

        var best = 0f
        for (s in usable) {
            val hit = segmentHitCount(s, segments)
            if (hit <= 0) continue
            val v = (hit.toFloat() / segments.size).coerceAtMost(1f)
            if (v > best) best = v
        }
        // 多技能协同加成：≥2 个技能都沾边，比单个技能孤证更可信（最多 +0.1）
        val multi = usable.count { segmentHitCount(it, segments) > 0 }
        if (multi >= 2) best = (best + 0.1f).coerceAtMost(1f)
        return maxOf(best, declaredScore(role, abilityTerms(ability)))
    }

    /**
     * #202：一个词是否「短到可以当能力标签用」。
     *
     * 🔴 为什么需要它：`hitCount` 允许「长term 含短词」这种包含关系
     * （能力写「HTML/CSS编码」、技能写「编码」要能命中）。
     * 但如果不设门槛，「产出可验收的方案片段」这种整句会
     * 因为技能里有「验收」「方案」二字就整段命中 —— 实测导致 8 个技能同分 0.5，
     * 派单彻底失去区分度。所以只有**短词**才允许被长句包含式命中。
     */
    private fun isShortWord(w: String): Boolean = w.length in 2..4

    /**
     * #202：技能 name / description / trigger 字段的**分词符**。
     *
     * 这些字段常写成「视觉设计 界面设计 令牌」或「frontend-design, ui」，
     * 不切开就没法判断「命中的是不是一个真词」——
     * 整条 9 字串判 isShortWord 恒为 false，好技能会被误杀成 0 分。
     */
    private val DESC_TOKEN_SPLIT = Regex("""[\s,，。、；;：:（）()【】\[\]/+\-—_·|]+""")

    /**
     * #197：能力词命中一个技能的数量。
     *
     * 🔴 这里的干草堆必须走 [ClusterSkillStore.abilityHaystack]（含 `abilityWords`
     * 中英双语词），**不能**再拼 name+description+trigger：
     * 主持拆的能力标签是中文（「HTML/CSS编码」「联网搜索」），
     * 技能名/description 是英文（`frontend-design`）。
     * 中文 contains 英文恒为 false → 命中恒为 0 → 节点全被跳过 → 假闭环。
     * 这是实测踩过的坑，不是理论风险。
     *
     * 🔴 #200E2E：**不能**只写字面双向包含 ——
     * 「研究」与「调研」是近义词却共享 0 个连续汉字，contains 无解；
     * 所以下面拿 [probesFor] 的近义探针去撞。只写字面匹配的那个版本已删除，
     * 它是 #200E2E 修的那个真bug 的原型，留着只会让人以为可以用。
     */
    private fun hitCount(skill: ClusterSkill, terms: List<String>): Int {
        // 🔴 #202 分层：技能**能力词**与**描述正文**的证据强度不同，不能一视同仁。
        // 干草堆里既有 abilityWords（「验收」「方案」这种标签），
        // 也有 description（「要求每个子节点可独立验收」这种自然语言）。
        // 自然语言里出现能力词纯属巧合 —— 实测就是它把 review 抬到与 planning 同分。
        val labelled = ClusterSkillStore.effectiveAbilityWords(skill)
        val full = ClusterSkillStore.abilityHaystack(skill)
        if (full.isEmpty()) return 0
        var hit = 0
        for (term in terms) {
            // #200E2E：拿这个词的**近义探针**去撞（研究↔调研）。
            val probes = probesFor(term)
            // 规则一：探针撞**能力词** —— 双向包含，长term 可含短标签词。
            val byLabel = labelled.any { h ->
                probes.any { p -> h.contains(p) || p.contains(h) }
            }
            // 🔴 #202 规则二：撞 name/description/trigger 这类自然语言字段时，
            // 必须先按非字母数字切开再判短词——
            // 原先直接拿整条 description 判 isShortWord，而 description 常写成
            // 「视觉设计 界面设计 令牌」这种 9 字长串 → 全部被门槛挡掉 →
            // 明明有 ui 技能却判 0 分（实测 6 个既有测试集体回归）。
            // 现在切开后逐 token 判：短 token 算证据，长 token 不算。
            val byDesc = full.any { h ->
                val tokens = h.split(DESC_TOKEN_SPLIT).filter { it.isNotBlank() }
                tokens.any { tok ->
                    isShortWord(tok) && probes.any { p -> tok.contains(p) || p.contains(tok) }
                }
            }
            if (byLabel || byDesc) hit++
        }
        return hit
    }


    /**
     * 角色**声明**的擅长（goodAt 关键词）对某能力的命中度，封顶 [DECLARED_CAP]。
     *
     * 🔴 永远不能单独越过阈值 —— 见 [scoreRole] 的注释。
     */
    fun declaredScore(role: RoleProfile, terms: List<String>): Float {
        if (terms.isEmpty()) return 0f
        val phrases = (role.duties + role.skills + role.taboos)
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
        val hay = phrases.joinToString(" ")
        if (hay.isBlank()) return 0f
        // 逐条职责短语做**双向**包含匹配：
        // 能力词为中文而职责文本为英文（或反之）时，单向 contains 恒为 0。
        // 要求两边均 ≥3 字符，避免单字母/双字母互相包含刷分。
        // #200E2E：同样走近义探针，但封顶仍是 DECLARED_CAP 0.30 ——
        // 只提高召回，不让「嘴说自己会」越过阈值。
        val hit = terms.count { term ->
            // 探针先摊平再匹配：旧的 `probes.any { p -> ... } || phrases.any { p -> ... }`
            // 把 p 写到了它自己的作用域外，编译器直接报 unresolved。
            probesFor(term).any { p ->
                hay.contains(p) ||
                    phrases.any { ph -> ph.length >= 3 && p.length >= 3 && p.contains(ph) }
            }
        }
        if (hit == 0) return 0f
        return (hit.toFloat() / terms.size).coerceAtMost(1f) * DECLARED_CAP
    }

    /**
     * 算出每个节点的能力覆盖情况。
     *
     * @param context 上下文（读技能库）
     * @param nodes 本次任务的全部子任务
     * @param roles 候选角色（一般是 [RoleRegistry.experts]）
     */
    fun audit(
        context: Context,
        nodes: List<ClusterNode>,
        roles: List<RoleProfile>,
    ): List<Coverage> {
        val skillLib = runCatching {
            ClusterSkillStore.load(context).associateBy { it.id }
        }.getOrDefault(emptyMap())

        return nodes.map { node ->
            // 能力需求优先用显式 ability；旧节点没有该字段时退回标题+指令
            val ability = node.ability.ifBlank {
                (node.title + " " + node.instruction).trim()
            }
            val scored = roles.map { it to scoreRole(it, ability, skillLib) }
                .filter { it.second > 0f }
                .sortedByDescending { it.second }
            val top = scored.firstOrNull()
            // 「有人自称会」不等于「有人真会」：只有真技能那条才算覆盖，
            // declared 只作为诊断信息透出，让人能看出「差在哪」。
            val declaredBest = roles.asSequence()
                .filter { it.enabled }
                .map { it to declaredScore(it, ClusterCapability.abilityTerms(ability)) }
                .filter { it.second > 0f }
                .maxByOrNull { it.second }
            Coverage(
                nodeId = node.id,
                nodeTitle = node.title,
                ability = ability,
                score = top?.second ?: 0f,
                bestRoleId = top?.first?.personaId ?: "",
                declared = declaredBest?.second ?: 0f,
                covered = (top?.second ?: 0f) >= COVER_THRESHOLD,
            )
        }
    }

    /** 汇总：哪些节点缺能力。 */
    fun gaps(list: List<Coverage>): List<Coverage> = list.filter { !it.covered }

    /** 汇总文案，给 UI / 工具结果显示。 */
    fun summary(list: List<Coverage>): String = buildString {
        val ok = list.count { it.covered }
        appendLine("能力覆盖 $ok/${list.size} 个子任务")
        list.forEach { c ->
            val mark = if (c.covered) "✓" else "✗"
            // 🔴 不覆盖但有人自称会 → 必须说出来，否则用户只看到「✗」，
            // 根本不知道「其实有角色声明能做，只是没装技能包」。
            val declaredHint = if (!c.covered && c.declared > 0f) {
                "（有角色声明擅长，但无技能包支撑）"
            } else ""
            appendLine("- [$mark] ${c.nodeTitle}：${c.reason}$declaredHint")
        }
    }

    // ——————————————— 补救 ———————————————

    /**
     * 在**本地技能库**里找最匹配的能力描述的技能。
     *
     * 这是 [Remedy.GRANT_LOCAL] 的前置：不联网就能救回来的，绝不该联网去装。
     */
    fun findLocalMatch(
        context: Context,
        ability: String,
        excludeIds: Set<String> = emptySet(),
    ): ClusterSkill? {
        // #200E2E：与 scoreRole 同一口径（语义片段），否则这里找到的技能
        // 绑上去后 scoreRole 仍然判 0 —— 又变成「绑了但没用」。
        val segments = abilitySegments(ability)
        if (segments.isEmpty()) return null
        val lib = runCatching { ClusterSkillStore.load(context) }.getOrDefault(emptyList())
        return lib.asSequence()
            .filter { it.enabled && it.id !in excludeIds }
            .map { s -> segmentHitCount(s, segments) to s }
            .filter { it.first > 0 }
            .sortedByDescending { it.first }
            .map { it.second }
            .firstOrNull()
    }

    /**
     * 执行补救。挂起 —— [Remedy.FETCH_OPEN] 要联网装技能。
     *
     * @param context 上下文
     * @param cov 覆盖缺口
     * @param roles 当前角色（用于挑要绑给谁）
     * @param allowNetwork 是否允许联网装开源技能（预算关掉时为 false）
     */
    suspend fun remedy(
        context: Context,
        cov: Coverage,
        roles: List<RoleProfile>,
        allowNetwork: Boolean,
    ): RemedyResult {
        // ① 本地已有 → 绑给最合适的角色。零网络开销，永远先试这一档。
        val local = findLocalMatch(context, cov.ability)
        if (local != null) {
            val target = pickBindingTarget(roles, local.name)
            if (target != null) {
                runCatching {
                    RoleRegistry.upsert(
                        context, target.copy(skillIds = (target.skillIds + local.id).distinct())
                    )
                }.onSuccess {
                    return RemedyResult(
                        Remedy.GRANT_LOCAL, true,
                        "本地技能「${local.name}」已绑给「${target.personaId}」",
                        listOf(local.id), target.personaId
                    )
                }.onFailure {
                    return RemedyResult(Remedy.GRANT_LOCAL, false, "绑定失败：${it.message}")
                }
            }
        }

        // ② 本地没有 → 联网去开源社区找
        if (allowNetwork) {
            val hits = runCatching { ClusterOpenSkillHub.searchAll(context, cov.ability, limitPerSource = 30) }
                .getOrDefault(emptyList())
            val pick = hits.firstOrNull()
            if (pick != null) {
                val installed = ClusterOpenSkillHub.install(context, pick.sourceId, pick.dir)
                if (installed.isSuccess) {
                    val skill = installed.getOrNull()!!.first
                    val target = pickBindingTarget(roles, skill.name)
                    if (target != null) {
                        runCatching {
                            RoleRegistry.upsert(
                                context, target.copy(skillIds = (target.skillIds + skill.id).distinct())
                            )
                        }.onSuccess {
                            return RemedyResult(
                                Remedy.FETCH_OPEN, true,
                                "已从开源社区装下「${skill.name}」（${pick.key}）并绑给「${target.personaId}」",
                                listOf(skill.id), target.personaId
                            )
                        }.onFailure {
                            return RemedyResult(
                                Remedy.FETCH_OPEN, false,
                                "技能已装上但绑定失败：${it.message}", listOf(skill.id)
                            )
                        }
                    }
                    // 装上了但没角色可绑（如全是评审）——技能留在库里，如实说明
                    return RemedyResult(
                        Remedy.FETCH_OPEN, true,
                        "已装下「${skill.name}」，但当前没有可绑技能的执行角色，技能已留在技能库",
                        listOf(skill.id)
                    )
                }
                return RemedyResult(
                    Remedy.FETCH_OPEN, false,
                    "开源技能 ${pick.key} 安装失败：${installed.exceptionOrNull()?.message ?: "未知原因"}"
                )
            }
            return RemedyResult(
                Remedy.FETCH_OPEN, false,
                "本地与开源社区都没找到能覆盖「${cov.ability}」的技能"
            )
        }

        // ③ 不允许联网 → 如实回报，不偷偷降级
        return RemedyResult(
            Remedy.GRANT_LOCAL, false,
            "本地技能库也没有覆盖「${cov.ability}」的技能，且当前未允许联网获取"
        )
    }

    /**
     * 挑该把技能绑给谁。
     *
     * 铁律：**绝不绑给评审**。评审的价值在于独立判断，
     * 一旦它有了生产技能就会顺手把不满意的产物改掉，验收就永远不可能不通过
     * （与 [ClusterSkillRuntime] 对 CRITIC 的硬过滤同一原则）。
     */
    /**
     * 挑一个该绑技能的角色。
     *
     * 🔴 #196：只吃 **skillName**，不吃技能对象本身。
     * 因为这里会被两类技能共用：集群库技能（[ClusterSkill]）与
     * 开源社区装下来的技能。若把参数类型写成某一个具体类，
     * 另一类就传不进来（编译器直接报 type mismatch）。
     * 挑人逻辑本来就只需要「这技能叫什么」，不需要它的其他字段。
     */
    private fun pickBindingTarget(roles: List<RoleProfile>, skillName: String): RoleProfile? =
        roles.asSequence()
            .filter { it.enabled && it.role.canExecute }
            .maxByOrNull { r ->
                // 同为执行者时，优先已有相关技能的（省一次冷启动）
                r.skillIds.size + abilityBonus(r, skillName)
            }

    private fun abilityBonus(role: RoleProfile, skillName: String): Int {
        val hay = (role.duties + role.skills).joinToString(" ").lowercase()
        val key = skillName.lowercase()
        return when {
            key.isNotBlank() && hay.contains(key) -> 3
            key.isNotBlank() && key.split(Regex("\\s+")).any { it.length >= 3 && hay.contains(it) } -> 1
            else -> 0
        }
    }
}