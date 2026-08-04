package com.ai.assistance.quro.core.skill

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 技能默认参数 Schema（function calling 用）：技能作为可被调用工具时，模型传入的入参。 */
internal const val DEFAULT_SKILL_PARAMS =
    """{"type":"object","properties":{"input":{"type":"string","description":"用户希望按此技能处理的输入内容"}}}"""

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
    /** function calling 参数 Schema（JSON-Schema 字符串），AI 调用此技能工具时按此填参。 */
    val parametersJson: String = DEFAULT_SKILL_PARAMS,
    /** 是否注册为可被 AI tool_calls 调用的函数（false=仅注入系统提示词，不可被调用）。 */
    val callable: Boolean = true,
    /** 是否常驻系统提示词（true=默认进系统提示词；false=仅在触发词命中时按需注入，避免与常驻重复）。 */
    val alwaysOn: Boolean = true,
) {
    /** 导出为应用内 JSON 格式（与 parseSkillJson 互为反向，可被本 App 重新导入）。 */
    fun toExportJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("description", description)
        put("prompt", prompt); put("enabled", enabled); put("trigger", trigger)
        put("updatedAt", updatedAt); put("parametersJson", parametersJson)
        put("callable", callable); put("alwaysOn", alwaysOn)
    }

    /** 导出为开放标准 SKILL.md 文本（与 anthropics/skills 等生态兼容，可被其它技能系统识别）。 */
    fun toSkillMd(): String = buildString {
        appendLine("---")
        appendLine("name: $name")
        if (description.isNotBlank()) appendLine("description: $description")
        appendLine("---")
        appendLine()
        appendLine(prompt)
    }

    /**
     * 技能 → 工具规格（OpenAI function-calling 风格）：
     * {name:"skill__<name>", description, parameters:<JSON-Schema 对象>}。
     * 即「把技能注册成工具」的序列化形态（与 QuroToolRegistry 的 skillSpecs 命名一致）。
     */
    fun toToolSpecJson(): JSONObject = JSONObject().apply {
        put("name", "skill__$name")
        put("description", description.ifBlank { "用户技能：$name" })
        put("parameters", runCatching { JSONObject(parametersJson) }.getOrDefault(JSONObject(DEFAULT_SKILL_PARAMS)))
    }

    companion object {
        /**
         * 工具规格 JSON → 技能（"技能转换"的反向：工具 → 技能）。
         * 支持 name 带/不带 `skill__` 前缀；parameters 为 JSON-Schema 对象或 parametersJson 字符串。
         */
        fun fromToolSpec(json: String): QuroSkill? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            return fromToolSpec(o)
        }

        private fun fromToolSpec(o: JSONObject): QuroSkill? {
            var name = o.optString("name", "").trim()
            if (name.isEmpty()) return null
            name = name.removePrefix("skill__")
            if (name.isEmpty()) return null
            val description = o.optString("description", "").trim()
            val paramsObj = o.optJSONObject("parameters")
            val parametersJson = if (paramsObj != null) paramsObj.toString() else o.optString("parametersJson", DEFAULT_SKILL_PARAMS)
            return QuroSkill(
                id = UUID.randomUUID().toString(),
                name = name,
                description = description,
                prompt = o.optString("prompt", "").trim(),
                enabled = o.optBoolean("enabled", true),
                trigger = o.optString("trigger", "").trim(),
                parametersJson = parametersJson.ifBlank { DEFAULT_SKILL_PARAMS },
                callable = o.optBoolean("callable", true),
                alwaysOn = o.optBoolean("alwaysOn", true),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }
}

/** 技能持久化（SharedPreferences，JSON 数组）。 */
object QuroSkillStore {
    private const val PREFS = "quro_skills"
    private const val KEY = "skills"
    private const val KEY_BUILTIN_CLEARED = "builtin_cleared_v2"

    /** 旧版本内置的 22 款硬编码技能名（用于一次性清理，不再随 App 内置）。 */
    private val BUILTIN_NAMES = setOf(
        "翻译助手", "写作润色", "代码审查", "长文摘要", "头脑风暴", "日程规划", "学习辅导",
        "邮件撰写", "演讲稿", "数据分析", "营销文案", "诗词创作", "算法面试", "旅行规划",
        "美食推荐", "健康建议", "读书笔记", "实时翻译", "成语词典", "职场建议", "故事创作", "提问优化",
    )

    /** 仅读存储、不触发迁移（供迁移逻辑内部调用，避免递归）。 */
    private fun loadRaw(context: Context): List<QuroSkill> {
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
                        parametersJson = o.optString("parametersJson", DEFAULT_SKILL_PARAMS),
                        callable = o.optBoolean("callable", true),
                        alwaysOn = o.optBoolean("alwaysOn", true),
                    )
                )
            }
        }
        return out.sortedBy { it.name }
    }

    /**
     * 一次性迁移：清除旧版本内置的 22 款硬编码技能（按名称匹配）。
     * 技能不再随 App 内置写死——改为用户从开放标准 SKILL.md 导入 / 自行创建。
     * 仅清理已知内置名称，不影响用户自建技能。幂等（KEY_BUILTIN_CLEARED 守卫）。
     */
    fun clearBuiltinSkillsOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BUILTIN_CLEARED, false)) return
        prefs.edit().putBoolean(KEY_BUILTIN_CLEARED, true).apply()
        val list = loadRaw(context)
        val remaining = list.filter { it.name !in BUILTIN_NAMES }
        if (remaining.size != list.size) save(context, remaining)
    }

    /**
     * 首次启动把 assets/skills/zorv/ 下随包内置的 Zorv AI 技能播种进用户技能库。
     * 用 manifest.json 里的稳定 id（zorv_<sha1>），幂等：已存在则跳过，用户删过也不会被强制加回。
     *
     * ⚠️ 默认 enabled=false / callable=false / alwaysOn=false：
     * - 内置技能的定位是「注入系统提示词的行为约束 / 能力说明」，不应默认全部开启、更不应
     *   默认注册成 function-calling 工具（否则离线模型会被 60+ 技能工具压垮 → 调一次工具就卡死/乱码）。
     * - 用户需要哪个技能，到「技能」页手动开启即可；开启后仍 alwaysOn=false（按需/触发注入，不污染全局提示词）。
     */
    private const val KEY_BUILTIN_ZORV = "builtin_zorv_v1"

    fun seedBuiltinZorvSkills(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BUILTIN_ZORV, false)) return
        prefs.edit().putBoolean(KEY_BUILTIN_ZORV, true).apply()
        runCatching {
            val am = context.assets
            val manifest = JSONObject(am.open("skills/zorv/manifest.json").bufferedReader().readText())
            val arr = manifest.optJSONArray("skills") ?: return@runCatching
            val existing = loadRaw(context).map { it.id }.toSet()
            val list = loadRaw(context).toMutableList()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                val file = o.optString("file", "")
                if (id.isEmpty() || file.isEmpty() || id in existing) continue
                val md = am.open("skills/zorv/$file").bufferedReader().readText()
                val parsed = parseSkillMd(md).firstOrNull() ?: continue
                list.add(parsed.copy(id = id, enabled = false, callable = false, alwaysOn = false, updatedAt = System.currentTimeMillis()))
            }
            save(context, list)
        }
    }

    /**
     * 一次性迁移（离线模型卡死修复配套）：把历史已播种的内置 Zorv 技能（id 以 "zorv_" 开头）
     * 翻转为 enabled=false / callable=false / alwaysOn=false。
     *
     * 背景：旧版本 seedBuiltinZorvSkills 把 62 个内置技能默认 enabled=true && callable=true，
     * 导致它们被注册成 skill__* function-calling 工具。一旦用户在「本地模型」开启工具调用，
     * 整套云端工具集（含 60+ 技能工具）被塞给 1.2B 本地模型 → 一直"正在处理提示词"卡死 /
     * 调一次工具就乱码。内置技能的定位本是「注入系统提示词的行为约束」，不该默认成为工具。
     * 幂等（KEY_BUILTIN_ZORV_CALLABLE_FIX 守卫）；新装设备由 seedBuiltinZorvSkills 的新默认值兜底。
     */
    private const val KEY_BUILTIN_ZORV_CALLABLE_FIX = "builtin_zorv_offline_callable_fix_v1"

    fun migrateBuiltinSkillsOff(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BUILTIN_ZORV_CALLABLE_FIX, false)) return
        prefs.edit().putBoolean(KEY_BUILTIN_ZORV_CALLABLE_FIX, true).apply()
        val list = loadRaw(context)
        var changed = false
        val out = list.map { s ->
            if (s.id.startsWith("zorv_") && (s.enabled || s.callable || s.alwaysOn)) {
                changed = true
                s.copy(enabled = false, callable = false, alwaysOn = false, updatedAt = System.currentTimeMillis())
            } else s
        }
        if (changed) save(context, out)
    }

    fun load(context: Context): List<QuroSkill> {
        clearBuiltinSkillsOnce(context)
        seedBuiltinZorvSkills(context)
        migrateBuiltinSkillsOff(context)
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
                        parametersJson = o.optString("parametersJson", DEFAULT_SKILL_PARAMS),
                        callable = o.optBoolean("callable", true),
                        alwaysOn = o.optBoolean("alwaysOn", true),
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
                        put("parametersJson", s.parametersJson)
                        put("callable", s.callable)
                        put("alwaysOn", s.alwaysOn)
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

    /** 当前「可调用」技能（启用 + callable + 含指令正文），用于注册为 AI 工具函数。 */
    fun callableList(context: Context): List<QuroSkill> =
        load(context).filter { it.enabled && it.callable && it.prompt.isNotBlank() }

    /** 按触发词匹配当前启用的技能（用于 send / voiceBallTurn 的按需预注入）。 */
    fun matchTriggerSkills(userText: String, context: Context): List<QuroSkill> {
        val t = userText.lowercase()
        return enabledList(context).filter { s ->
            s.trigger.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                .any { t.contains(it) }
        }
    }

    /**
     * 从开放标准 SKILL.md 文本解析技能（与 anthropics/skills、agentskills 等生态兼容）。
     * 格式：
     *   ---
     *   name: <技能名>
     *   description: <一句话描述>
     *   ---
     *   <指令正文，作为注入系统提示词的 prompt>
     *
     * 返回 0~1 个技能（一段标准 SKILL.md 含一个技能）。解析失败返回空列表。
     * 这是「技能不再内置、改从开源导入」的核心入口：用户把 GitHub 上的 SKILL.md 粘贴/下载进来即可。
     */
    fun parseSkillMd(md: String): List<QuroSkill> {
        val text = md.trim()
        if (text.isEmpty()) return emptyList()
        // 提取 frontmatter（首个 --- 与第二个 --- 之间）
        val fmMatch = Regex("^---\\s*\\n(.*?)\\n---\\s*\\n?", RegexOption.DOT_MATCHES_ALL).find(text)
        val (frontmatter, body) = if (fmMatch != null) {
            fmMatch.groupValues[1] to text.removeRange(fmMatch.range)
        } else {
            "" to text
        }
        val name = Regex("^name:\\s*(.+)$", RegexOption.MULTILINE).find(frontmatter)?.groupValues?.get(1)?.trim()
            ?: Regex("^name:\\s*(.+)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()
            ?: return emptyList()
        val description = Regex("^description:\\s*(.+)$", RegexOption.MULTILINE).find(frontmatter)?.groupValues?.get(1)?.trim() ?: ""
        val prompt = body.trim().ifBlank { return emptyList() }
        return listOf(
            QuroSkill(
                id = UUID.randomUUID().toString(),
                name = name,
                description = description,
                prompt = prompt,
                enabled = true,
                updatedAt = System.currentTimeMillis(),
                callable = true,
                // 从开源导入的技能默认非常驻：不污染全局系统提示词，仅当用户在对话框「选择技能」时生效
                alwaysOn = false,
            )
        )
    }
}
