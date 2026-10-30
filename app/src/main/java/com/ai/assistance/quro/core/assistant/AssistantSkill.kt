package com.ai.assistance.quro.core.assistant

import android.content.Context
import com.ai.assistance.quro.activity.QuroApplication
import com.ai.assistance.quro.util.QuroDiag

/** 技能分类（对标小布技能广场的类目，也是广场 UI 的 tab）。 */
enum class AssistantCategory(val wire: String, val label: String) {
    CHAT("chat", "对话"),
    TERMINAL("terminal", "终端"),
    DEVICE("device", "设备"),
    REMINDER("reminder", "提醒"),
    KNOWLEDGE("knowledge", "知识"),
    SYSTEM("system", "系统"),
    LINK("link", "跨设备"),
    PERMISSION("permission", "权限"),
}

/**
 * 一个可被编排的助手能力。
 *
 * 小布的 skill 层（6724 个类，全应用最重）本质就是这张表：**id + 触发词 + 参数 + 执行器**。
 * 我们把 ZorvAI 已有的 171 个工具与内置动作统一收进这张表，让「AI 工具面 / 外部指令面 /
 * 技能广场 UI」三处共用同一份事实，而不是各自维护一套。
 *
 * @param requirement 非空表示「开箱不可用」，广场上会标出来并给配置入口
 * @param external 是否允许外部应用（广播/深链/分享）调用；改系统状态的技能一律 false
 * @param deeplink 只有跳转没有执行体时使用
 */
data class AssistantSkill(
    val id: String,
    val title: String,
    val summary: String,
    val category: AssistantCategory,
    val keywords: List<String> = emptyList(),
    val requirement: String = "",
    val external: Boolean = false,
    val deeplink: String = "",
    val run: (suspend (AssistantRequest) -> AssistantResult)? = null,
) {
    val runnable: Boolean get() = run != null || deeplink.isNotEmpty()
}

/**
 * 技能注册表 + 启停持久化 + 文本匹配。
 *
 * 纯内存注册（各模块在自己 init 时 [registerAll]），启停状态落 SharedPreferences。
 * 线程安全：注册在启动期单线程完成，查询用副本，避免并发修改。
 */
object AssistantSkillRegistry {
    private const val TAG = "assistant-skill"
    private const val PREFS = "quro_assistant_skills"

    private val skills = LinkedHashMap<String, AssistantSkill>()
    private val disabled = HashSet<String>()

    @Volatile
    private var prefsLoaded = false

    private fun prefs(): android.content.SharedPreferences? {
        val ctx: Context = QuroApplication.appCtx ?: return null
        return runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()
    }

    private fun ensurePrefs() {
        if (prefsLoaded) return
        synchronized(this) {
            if (prefsLoaded) return
            runCatching {
                prefs()?.getStringSet(KEY_DISABLED, emptySet())?.let { disabled.addAll(it) }
            }
            prefsLoaded = true
        }
    }

    private const val KEY_DISABLED = "disabled_ids"

    fun register(skill: AssistantSkill) {
        ensurePrefs()
        synchronized(this) { skills[skill.id] = skill }
    }

    fun registerAll(list: List<AssistantSkill>) {
        ensurePrefs()
        synchronized(this) { list.forEach { skills[it.id] = it } }
    }

    fun get(id: String): AssistantSkill? = synchronized(this) { skills[id] }

    fun all(): List<AssistantSkill> = synchronized(this) { skills.values.toList() }

    fun isEnabled(id: String): Boolean {
        ensurePrefs()
        return synchronized(this) { id !in disabled }
    }

    fun setEnabled(id: String, on: Boolean) {
        ensurePrefs()
        val snapshot = synchronized(this) {
            if (on) disabled.remove(id) else disabled.add(id)
            disabled.toSet()
        }
        runCatching { prefs()?.edit()?.putStringSet(KEY_DISABLED, snapshot)?.apply() }
        QuroDiag.log(TAG, "skill ${if (on) "enabled" else "disabled"} id=$id")
    }

    /**
     * 按文本找最匹配的技能（外部指令面用）。
     *
     * 规则：先看 `/id` 或 `skill:id` 形式的显式调用，再看关键词命中；
     * 命中多个时取**命中词最长**的那个（长词更具体，避免「终端」抢走「终端环境」）。
     * 关键词**等长时保留先注册的那个**（注册顺序 = 能力表声明顺序，稳定可预期）。
     * 注意注册表是进程级全局单例，写测试时关键词要用各用例独有的字串，否则会跨用例串味。
     */
    fun match(text: String): AssistantSkill? {
        val t = text.trim()
        if (t.isEmpty()) return null
        // 显式：`/skill-id 参数…` 或 `skill:skill-id`
        val explicit = Regex("^(?:/|skill:)\\s*([a-z0-9\\-_.]+)", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.getOrNull(1)
        if (explicit != null) return get(explicit)

        var best: AssistantSkill? = null
        var bestLen = 0
        for (s in all()) {
            if (!isEnabled(s.id)) continue
            for (k in s.keywords) {
                if (k.isNotBlank() && t.contains(k, ignoreCase = true) && k.length > bestLen) {
                    best = s
                    bestLen = k.length
                }
            }
        }
        return best
    }

    /** 广场搜索：标题 / 摘要 / 关键词 / id 四处命中，标题权重最高。 */
    fun search(query: String, category: AssistantCategory? = null): List<AssistantSkill> {
        val q = query.trim()
        val pool = all().filter { category == null || it.category == category }
        if (q.isEmpty()) return pool.sortedWith(compareBy({ it.category.ordinal }, { it.title }))
        return pool.map { s -> s to score(s, q) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun score(s: AssistantSkill, q: String): Int {
        var sc = 0
        if (s.title.contains(q, ignoreCase = true)) sc += 100
        if (s.id.contains(q, ignoreCase = true)) sc += 60
        s.keywords.forEach { if (it.contains(q, ignoreCase = true)) sc += 40 }
        if (s.summary.contains(q, ignoreCase = true)) sc += 15
        return sc
    }

    /**
     * 执行一个技能：统一计时、统一异常兜底、统一日志。
     * 绝不把异常抛给调用方 —— 外部入口拿到的永远是 [AssistantResult]。
     */
    suspend fun run(id: String, req: AssistantRequest): AssistantResult {
        val skill = get(id)
            ?: return AssistantResult.fail(AssistantCodes.SKILL_NOT_FOUND, "没有这个能力：$id", skillId = id)
        if (!isEnabled(id)) {
            return AssistantResult.fail(AssistantCodes.SKILL_DISABLED, "能力「${skill.title}」已停用", skillId = id)
        }
        val exec = skill.run
            ?: return AssistantResult.fail(
                AssistantCodes.UNSUPPORTED,
                if (skill.deeplink.isNotEmpty()) "「${skill.title}」只有入口，没有执行体" else "「${skill.title}」未实现",
                skillId = id,
            )
        val t0 = System.currentTimeMillis()
        val r = runCatching { exec(req) }
            .getOrElse { AssistantResult.internalError(id, it) }
        val ms = System.currentTimeMillis() - t0
        val out = r.copy(skillId = id, elapsedMs = ms)
        QuroDiag.log(TAG, "run ${skill.id} from=${req.source.wire} ${out.wire()}")
        return out
    }
}
