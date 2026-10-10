package com.ai.assistance.quro.core.cluster

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * #196 集群**独立**技能库。
 *
 * ## 为什么必须另起一套（这是实测踩出来的，不是洁癖）
 *
 * 集群原先直接 [com.ai.assistance.quro.core.skill.QuroSkillStore.load] 读全局技能库，
 * 出了三件事：
 *
 * 1. **一个技能所有角色都能看到** —— 全局库 81 个技能对集群全开放。
 *    集群要的是「精确到角色」的能力凭据，一旦全库可读，能力核对就退化成
 *    「随便哪个技能沾点边就算具备」；
 * 2. **上下文根本带不动** —— 一个技能绑给 N 个角色，就有 N 份正文各自注入各自 prompt。
 *    全局库里`frontend-design` / `aihot` 这类正文动辄数千字符，
 *    3~5 个角色一绑，单请求就顶到上下文上限，表现为「模型不响应」；
 * 3. **用户改全局技能会连带改集群行为** —— 集群的判断标准是隐式的、不可见的，
 *    用户在技能页关掉一个技能，集群派单逻辑就变了，却没人知道。
 *
 * 所以集群自己有一套：独立存储、独立 id 命名空间（`cluster_` 前缀）、
 * 独立 manifest（`assets/cluster-skills/manifest.json`）。
 * **与全局库零耦合**：删掉全局技能不影响集群，集群也不污染用户的技能页。
 *
 * ## 隔离到什么程度
 *
 * -存储：`filesDir/cluster_skills.json`（不是 SharedPreferences，与全局库彻底分开）
 * -  id：`cluster_<sha1(name)[:12]>`，与全局库的 `zorv_` 前缀不冲突
 * -  角色卡引用只认集群库，见 [ClusterRoleCard] 的 [SkillRef]
 * -  用户在技能页动全局技能 → 集群**不受任何影响**
 */
object ClusterSkillStore {

    /** 集群技能 id 前缀。与全局库的 `zorv_` 区分开，混了就会互相覆盖。 */
    const val ID_PREFIX = "cluster_"

    /** 集群技能套件（只有一个组，但留出扩展位）。 */
    const val SUITE = "cluster-v2"

    /** 存储文件名（放在 filesDir，与全局技能的 SharedPreferences 完全隔离）。 */
    private const val FILE = "cluster_skills.json"

    /** 一次性播种守卫。**独立于**全局库的 `builtin_zorv_v1`。 */
    private const val KEY_SEEDED = "cluster_skills_seeded_v1"

    private fun file(context: Context): File = File(context.filesDir, FILE)

    /**
     * 集群技能。
     *
     * 与 [com.ai.assistance.quro.core.skill.QuroSkill] **刻意不同构**：
     * 没有 `callable`（集群技能一律是提示词规程，绝不注册成 function-calling 工具）、
     * 没有 `alwaysOn`（只在本角色绑定时注入）、
     * 多一个 [abilityWords]（中英双语能力词，专门给能力核对用，见 [ClusterCapability]）。
     */
    data class ClusterSkill(
        val id: String,
        val name: String,
        val description: String = "",
        /** 注入该角色 system prompt 的规程正文 */
        val prompt: String = "",
        /** 逗号分隔的触发词（中文为主） */
        val trigger: String = "",
        /**
         * #197：**中英双语**能力词，供主持拆出的能力标签匹配。
         *
         * 为什么必须有这个字段：主持拆出来的是中文能力标签（「HTML/CSS编码」、
         * 「联网搜索」、「文案写作」），而技能名与description 大多是英文
         * （`frontend-design` / `web-search-exa`）。
         * 中文词去contains 英文串，命中恒为 0 —— 这正是节点全被跳过、
         * 最后「假闭环」的直接原因。
         *
         * 格式：`中文词|english-term` 用空格分隔，例如 `"编码 html css frontend web"`。
         */
        val abilityWords: String = "",
        val enabled: Boolean = true,
        val updatedAt: Long = 0L,
    ) {
        /** 供能力核对使用的全部可匹配词（小写、去空白）。 */
        fun abilityHaystack(): List<String> =
            (abilityWords.split(" ").map { it.trim().lowercase() } +
                listOf(name, description, trigger).map { it.trim().lowercase() })
                .filter { it.isNotBlank() }

        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("name", name); put("description", description)
            put("prompt", prompt); put("trigger", trigger); put("abilityWords", abilityWords)
            put("enabled", enabled); put("updatedAt", updatedAt)
        }

        companion object {
            fun fromJson(o: JSONObject): ClusterSkill = ClusterSkill(
                id = o.optString("id"),
                name = o.optString("name"),
                description = o.optString("description"),
                prompt = o.optString("prompt"),
                trigger = o.optString("trigger"),
                abilityWords = o.optString("abilityWords"),
                enabled = o.optBoolean("enabled", true),
                updatedAt = o.optLong("updatedAt", 0L),
            )
        }
    }

    // ——————————————— 持久化 ———————————————

    private fun readRaw(context: Context): MutableList<ClusterSkill> {
        val out = mutableListOf<ClusterSkill>()
        runCatching {
            val f = file(context)
            if (!f.exists()) return@runCatching
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                out += ClusterSkill.fromJson(arr.optJSONObject(i) ?: continue)
            }
        }
        return out
    }

    private fun writeRaw(context: Context, list: List<ClusterSkill>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            // 🔴 原子写：直接 writeText 会先截断为 0，中途异常 = 技能库全丢。
            val f = file(context)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) {
                // 某些文件系统 renameTo 会失败，退化成「写临时文件后覆盖」
                f.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    /** 供测试与设置页直接落库。 */
    fun save(context: Context, list: List<ClusterSkill>) = writeRaw(context, list)

    /** 读集群技能库（会自动先播种）。 */
    fun load(context: Context): List<ClusterSkill> {
        seed(context)
        return readRaw(context)
    }

    fun get(context: Context, id: String): ClusterSkill? =
        load(context).firstOrNull { it.id == id }

    fun byName(context: Context, name: String): ClusterSkill? =
        load(context).firstOrNull { it.name == name }

    fun upsert(context: Context, skill: ClusterSkill) {
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.id == skill.id }
        if (idx >= 0) list[idx] = skill else list.add(skill)
        writeRaw(context, list)
    }

    fun delete(context: Context, id: String) {
        val list = load(context)
        writeRaw(context, list.filterNot { it.id == id })
    }

    // ——————————————— 播种 ———————————————

    /** id = `cluster_` + sha1hex(name)[:12]，与全局库的 `zorv_` 命名空间不重叠。 */
    fun stableId(name: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-1")
        val bytes = d.digest(name.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0F])
        }
        return ID_PREFIX + sb.substring(0, 12)
    }

    /**
     * 从 `assets/cluster-skills/manifest.json` 播种集群技能。
     *
     * 幂等：守卫 + 已存在则跳过（用户删过的不会被加回）。
     *
     * 🔴 #214：`KEY_SEEDED` 必须**成功播种并落库后**才置 true。
     * 旧实现先 `putBoolean(KEY_SEEDED, true)` 再读 assets —— 一旦 assets 读取失败
     * （manifest 损坏/权限异常/文件被裁剪），守卫已被置位，之后每次 `load()` 都直接 return，
     * 技能库永远播种不出来 → 角色绑定的 `cluster_xxx` id 全部查不到 →
     * `ClusterCapability.audit` 的 `byId` 为空 → 只走 declaredScore（封顶 0.30 < 0.34）
     * → 每个节点都判「无人具备」→ SKIPPED。这正是用户实测「角色明明绑定了 copywrite，
     * host 却判无人具备」的直接根因之一。
     */
    fun seed(context: Context) {
        val prefs = context.getSharedPreferences("quro_cluster_skills", Context.MODE_PRIVATE)
        // 自愈：守卫为 true 但库是空的（旧版本异常路径留下的坏状态）→ 强制重新播种。
        if (prefs.getBoolean(KEY_SEEDED, false) && readRaw(context).isNotEmpty()) return
        runCatching {
            val am = context.assets
            val manifest = JSONObject(
                am.open("cluster-skills/manifest.json").bufferedReader().readText()
            )
            val arr = manifest.optJSONArray("skills") ?: return@runCatching
            val existing = readRaw(context).map { it.id }.toSet()
            val list = readRaw(context).toMutableList()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "").trim()
                val file = o.optString("file", "").trim()
                if (name.isEmpty() || file.isEmpty()) continue
                val id = stableId(name)
                if (id in existing) continue
                val parsed = parseMd(
                    id = id,
                    name = name,
                    md = runCatching { am.open("cluster-skills/$file").bufferedReader().readText() }
                        .getOrNull() ?: continue,
                )
                list.add(parsed)
            }
            if (list.size != existing.size) writeRaw(context, list)
            // 🔴 #214：全部成功后才置位。这样 assets 读取失败时下次还能重试。
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        }
    }

    /**
     * 解析 SKILL.md。
     *
     * front-matter 支持 `name` / `description` / `trigger` / `abilityWords`，
     * 其余为正文。与全局库的同名解析**刻意分开实现**——
     * 共用一个函数就得共用一套字段，以后全局库加字段会静默影响集群。
     */
    fun parseMd(id: String, name: String, md: String): ClusterSkill {
        val text = md.trim()
        val fmMatch = Regex("^---\\s*\\n(.*?)\\n---\\s*\\n?", RegexOption.DOT_MATCHES_ALL).find(text)
        val (fm, body) = if (fmMatch != null) {
            fmMatch.groupValues[1] to text.removeRange(fmMatch.range)
        } else {
            "" to text
        }
        fun fmField(k: String): String =
            Regex("^$k:\\s*(.+)$", RegexOption.MULTILINE).find(fm)?.groupValues?.get(1)?.trim().orEmpty()
        return ClusterSkill(
            id = id,
            name = if (fmField("name").isNotBlank()) fmField("name") else name,
            description = fmField("description"),
            prompt = body.trim(),
            trigger = fmField("trigger"),
            abilityWords = fmField("abilityWords"),
            enabled = true,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * 内置的**兜底**能力词表。
     *
     * 随包 manifest 里每个技能都应写 `abilityWords`，但万一项目漏写或
     * 用户自建技能没填，这里按技能名做一次启发式补全 —— 宁可给个大概，
     * 也不要因为一个字段没填就让角色被判成「无人具备能力」（那是更坏的结果）。
     *
     * 键是技能名的**小写**；值为额外补充的能力词。
     */
    private val NAME_FALLBACK_WORDS: Map<String, String> = mapOf(
        "html-dev" to "网页 html css 页面 单文件 前端 布局 编码 实现界面",
        "ui-design" to "设计 界面 视觉 配色 视觉设计 美化 样式 界面设计",
        "frontend-design-playbook" to "前端 界面 交互 实现界面 组件 页面 前端开发",
        "frontend-design" to "前端 界面 视觉 网页 实现界面 html",
        "md-doc" to "文档 写作 说明书 markdown 写文档 技术文档",
        "copywrite" to "文案 写作 标题 卖点 宣传 撰写 写作",
        "planning" to "规划 拆解 拆任务 计划 任务分解 排期",
        "strategy" to "策略 战略 决策 取舍 方案选择",
        "review" to "评审 验收 审阅 检查 复核 把关",
        "code-review" to "代码评审 code review 审代码 检查代码 cr",
        "mobile-control" to "手机 操作手机 点击 滑动 截屏 设备控制 app操作",
        "data-analysis" to "数据分析 统计 报表 透视 数据处理 看数据",
        "web-research" to "联网 搜索 检索 调研 查资料 搜索联网 联网搜索 资讯",
        "web-search-exa" to "联网 搜索 检索 联网搜索 搜索联网 调研 查询",
        "web-search" to "联网 搜索 检索 联网搜索 调研 查询",
        "skill-fetch" to "技能获取 拉取技能 装技能 开源技能 补技能",
        "role-forge" to "造角色 新建角色 角色卡 加专家 补角色",
        "humanizer-zh" to "文案 去ai味 写作 润色",
        "humanizer" to "文案 去ai味 writing 润色",
        "news-summary" to "新闻 资讯 热点 总结 资讯 新闻热点",
    )

    /** 给技能补齐可匹配的能力词（manifest 没写就用启发式表兜底）。 */
    fun effectiveAbilityWords(skill: ClusterSkill): List<String> {
        val fromField = skill.abilityWords.split(" ").map { it.trim() }.filter { it.isNotBlank() }
        if (fromField.isNotEmpty()) return fromField.map { it.lowercase() }
        NAME_FALLBACK_WORDS[skill.name.lowercase()]
            ?.split(" ")?.filter { it.isNotBlank() }?.map { it.lowercase() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        // #210：手写表也没覆盖 → 自动切词，绝不返回空（见 [heuristicWords]）。
        return heuristicWords(skill)
    }

    /** 切词时丢掉的英文虚词与噪音（中英混排的 description 里很多）。 */
    private val HEURISTIC_STOP = setOf(
        "the", "and", "for", "with", "use", "when", "your", "you", "are", "this",
        "that", "from", "com", "www", "http", "https", "skill", "skills", "based",
        "into", "how", "what", "all", "can", "via", "not", "any", "its", "them",
    )

    /**
     * #210：从技能名/描述**自动切词**的最后一道兜底。
     *
     * 为什么必须有它：随包 81 个技能里只有 [NAME_FALLBACK_WORDS] 那 20 个手写了
     * 中文能力词。其余（frontend-dev / Docker / tavily / …）一旦没填 abilityWords，
     * [effectiveAbilityWords] 就返回**空列表** —— 于是这个技能对任何能力都得 0 分，
     * 绑着它的角色在 CAPABILITY 阶段一律被判「不具备能力」→ 节点 SKIPPED。
     *
     * 用户实测「JavaScript 编程匹配不上已绑定的 frontend-dev」就是这么来的：
     * 不是打分公式错，是**根本没有可供匹配的词**。
     * 空列表等于「这个技能等于不存在」，比给个大概坏得多。
     */
    private fun heuristicWords(skill: ClusterSkill): List<String> {
        fun cut(s: String): List<String> =
            Regex("[^a-z0-9\u4e00-\u9fff]+")
                .split(s.lowercase())
                .filter { w ->
                    w.isNotBlank() && w !in HEURISTIC_STOP &&
                        // 中文片段全收；英文要 >=3 字符（2 字符的 ui/if 太容易误命中）
                        (w.all { it.code in 0x4E00..0x9FFF } || w.length >= 3)
                }
        // 技能名优先（它最能代表这个技能是什么），描述只作补充。
        return (cut(skill.name) + cut(skill.description + " " + skill.trigger))
            .distinct()
            .take(24)
    }

    /**
     * 集群技能的完整匹配干草堆（能力核对用）。
     *
     * = 显式 abilityWords（或启发式兜底）+ name + description + trigger。
     * 语料比全局技能**宽**很多，这正是为了跨中英文断层。
     */
    fun abilityHaystack(skill: ClusterSkill): List<String> =
        (effectiveAbilityWords(skill) +
            listOf(skill.name, skill.description, skill.trigger).map { it.trim().lowercase() })
            .filter { it.isNotBlank() }

    /** 调试/UI 用：列出技能与其能力词。 */
    fun describe(context: Context): List<Pair<ClusterSkill, List<String>>> =
        load(context).map { it to abilityHaystack(it) }

    // ——————————————— 开源技能（集群自有命名空间） ———————————————

    /**
     * 开源技能的 id 前缀。
     *
     * 🔴 #200：原来是 `gh_`，直接落全局库。改成 `cluster_gh_` 才是真正隔离 ——
     * 用户装给集群的技能不该出现在全局技能页，集群的判单标准也不该被全局库影响。
     */
    const val OPEN_ID_PREFIX = ID_PREFIX + "gh_"

    /**
     * 开源技能的稳定 id。算法与 [ClusterOpenSkillHub.install] 里的**完全一致**，
     * 否则角色卡预判「已安装」会与实际安装结果对不上。
     */
    fun openStableId(key: String): String = OPEN_ID_PREFIX + sha1hex(key).take(16)

    /**
     * 把一份外部 SKILL.md 装进**集群**技能库。
     *
     * 幂等：同 key 重复安装是覆盖而非堆副本。返回 `技能 to 是否已存在`。
     *
     * @param key 稳定键（一般是 `源id/目录`）
     * @param descriptionHint 正文没写 description 时用它兜底
     */
    fun installOpen(
        context: Context,
        key: String,
        md: String,
        descriptionHint: String = "",
    ): Result<Pair<ClusterSkill, Boolean>> {
        val id = openStableId(key)
        val name = Regex("^---\\s*\\n(.*?)\\n---", RegexOption.DOT_MATCHES_ALL)
            .find(md.trim())
            ?.groupValues?.get(1)
            ?.let { fm ->
                Regex("^name:\\s*(.+)$", RegexOption.MULTILINE).find(fm)?.groupValues?.get(1)?.trim()
            }
            .orEmpty()
        if (name.isBlank()) {
            return Result.failure(
                IllegalStateException(
                    "SKILL.md 没有可解析的 front-matter（需要 --- name: ... description: ... ---）"
                )
            )
        }
        val parsed = parseMd(id = id, name = name, md = md)
        val skill = parsed.copy(
            description = parsed.description.ifBlank { descriptionHint },
            enabled = true,
            updatedAt = System.currentTimeMillis(),
        )
        val library = load(context)
        val existed = library.any { it.id == id || it.name == skill.name }
        return runCatching {
            upsert(context, skill)
            skill to existed
        }
    }

    /** 本地已安装的开源集群技能（按 [OPEN_ID_PREFIX] 判定）。 */
    fun installedOpenSkills(context: Context): List<ClusterSkill> =
        runCatching { load(context).filter { it.id.startsWith(OPEN_ID_PREFIX) } }
            .getOrDefault(emptyList())

    /** 某个远端条目是否已装进集群库。 */
    fun isOpenInstalled(context: Context, key: String): Boolean =
        runCatching { load(context).any { it.id == openStableId(key) } }.getOrDefault(false)

    private fun sha1hex(s: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-1")
        val bytes = d.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }
}