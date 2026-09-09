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
    /**
     * 套件分组 id（空串 = 未分组/通用）。内置 Zorv 技能按 `manifest.json` 里的 `suite` 字段播种；
     * 一组逻辑相关的技能归入同一套件（如 cloudflare / douyin-tiktok / deploy / search 等），
     * 技能页按套件折叠展示，不再是一长串零散条目。
     */
    val suite: String = "",
    /**
     * 内置技能签名校验状态：
     * - "unknown" 未校验（用户自建技能默认）
     * - "verified" 随包签名校验通过（内容未被篡改）
     * - "failed"   随包签名校验失败（内容可能被篡改 → 排查标记）
     * - "unsigned" 随包未携带签名（旧版/外部导入）
     */
    val signState: String = "unknown",
) {
    /** 导出为应用内 JSON 格式（与 parseSkillJson 互为反向，可被本 App 重新导入）。 */
    fun toExportJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("description", description)
        put("prompt", prompt); put("enabled", enabled); put("trigger", trigger)
        put("updatedAt", updatedAt); put("parametersJson", parametersJson)
        put("callable", callable); put("alwaysOn", alwaysOn)
        if (suite.isNotBlank()) put("suite", suite)
        if (signState != "unknown") put("signState", signState)
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
                suite = o.optString("suite", "").trim(),
                signState = o.optString("signState", "unknown").trim(),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }
}

/**
 * 套件（Suite）元数据：内置 Zorv 技能按业务域归并成「套件」，技能页按套件折叠展示。
 * 一组逻辑相关的技能归入同一套件 id（见 manifest.json 的 `suite` 字段）。
 *
 * - [ORDER] 决定套件在列表里的展示顺序（数组下标越小越靠前）。
 * - [LABELS] 套件 id → 中文展示名。
 * - 不在 [LABELS] 里的套件回退为 id 本身；空串 "" 表示「未分组/通用」，始终排最后。
 */
object QuroSkillSuites {
    val ORDER: List<String> = listOf(
        "cloudflare", "douyin-tiktok", "edgeone", "github", "deploy",
        "frontend-design", "humanizer", "search", "weather", "music",
        "ai-news", "document", "ppt-mindmap", "agent", "memory",
        "code", "self", "wxa", "ima", "qq-bot", "contract", "docker", "patent",
        "general",
    )
    val LABELS: Map<String, String> = mapOf(
        "cloudflare" to "Cloudflare 套件",
        "douyin-tiktok" to "抖音 / TikTok 套件",
        "edgeone" to "EdgeOne 套件",
        "github" to "GitHub 套件",
        "deploy" to "部署 / 托管套件",
        "frontend-design" to "前端 / 设计套件",
        "humanizer" to "去 AI 味 / 润色套件",
        "search" to "搜索 / 资讯套件",
        "weather" to "天气套件",
        "music" to "音乐套件",
        "ai-news" to "AI 资讯套件",
        "document" to "公文 / 文档套件",
        "ppt-mindmap" to "PPT / 脑图套件",
        "agent" to "智能体 / 人格套件",
        "memory" to "记忆管理套件",
        "code" to "代码 / 工程套件",
        "self" to "数字分身套件",
        "wxa" to "小程序 AI 套件",
        "ima" to "IMA 知识库套件",
        "qq-bot" to "QQ 机器人套件",
        "contract" to "合同审查套件",
        "docker" to "Docker 套件",
        "patent" to "专利套件",
        "general" to "通用 / 未分组",
    )

    fun label(suite: String): String = if (suite.isBlank()) LABELS["general"] ?: "通用 / 未分组" else LABELS[suite] ?: suite

    /** 把技能列表按套件分组，返回 (套件id, 该套件技能列表) 的有序列表。 */
    fun group(list: List<QuroSkill>): List<Pair<String, List<QuroSkill>>> {
        val bySuite = list.groupBy { it.suite.ifBlank { "general" } }
        val ordered = ORDER.filter { it in bySuite }.map { it to bySuite.getValue(it) }
        val rest = bySuite.keys.filter { it !in ORDER }.sorted().map { it to bySuite.getValue(it) }
        return ordered + rest
    }
}

/**
 * 内置技能签名器：用 HMAC-SHA256 对随包技能的「规范内容」做签名，
 * 播种前/排查时重新计算并比对，检测技能文件是否被篡改（签名排查）。
 *
 * 注意：这是「随包完整性校验」而非「第三方代码签名」——私钥不下发、只内置 salt 做 HMAC。
 * 目的是在 Seed 阶段拦住被篡改/损坏的内置技能资产，并在「验证签名」里给出排查报告。
 */
object SkillSigner {
    /** 内置 HMAC salt（与构建期签名脚本保持一致）。仅用于随包资产完整性校验。 */
    const val SALT = "zorv-ai-builtin-skill-sign-v1"

    /** 计算规范签名串：id|name|fileContent 的 HMAC-SHA256（hex）。 */
    fun sign(id: String, name: String, content: String): String = hmacSha256("$id|$name|$content", SALT)

    /** 校验：重新计算并比对。sig 为空视为未签名（返回 false 但 signState=unsigned）。 */
    fun verify(id: String, name: String, content: String, sig: String?): Boolean {
        if (sig.isNullOrBlank()) return false
        return sign(id, name, content) == sig
    }

    private fun hmacSha256(data: String, key: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
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

    /**
     * 按 id 去重：保留首次出现，丢弃后续重复项。
     *
     * 背景（崩溃修复 #3）：旧版本 manifest 曾短暂播种过重复 id（如 zorv_ff8e876ec646），
     * 部分设备 SharedPreferences 里残留两条同 id 记录。本 App 技能页 LazyColumn 用 skill.id
     * 作 key，重复 key 直接抛 `IllegalArgumentException: Key "xxx" was already used` 崩进程。
     * 播种是幂等的（KEY_BUILTIN_ZORV 守卫）不会主动清理，所以在此对读出数据做兜底去重，
     * 让任何残留重复都无法抵达 UI。
     */
    private fun dedupeById(list: List<QuroSkill>): List<QuroSkill> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<QuroSkill>()
        for (s in list) {
            if (s.id in seen) continue
            seen.add(s.id)
            out.add(s)
        }
        return out
    }

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
                        suite = o.optString("suite", ""),
                        signState = o.optString("signState", "unknown").takeIf { it.isNotBlank() } ?: "unknown",
                    )
                )
            }
        }
        return dedupeById(out).sortedBy { it.name }
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
                val suite = o.optString("suite", "").trim()
                val signature = o.optString("signature", "").trim()
                if (id.isEmpty() || file.isEmpty() || id in existing) continue
                val md = runCatching { am.open("skills/zorv/$file").bufferedReader().readText() }.getOrNull() ?: continue
                val parsed = parseSkillMd(md).firstOrNull() ?: continue
                // 签名排查：用随包签名校验内置技能内容完整性；缺失签名→unsigned，不符→failed。
                val signState = when {
                    signature.isBlank() -> "unsigned"
                    SkillSigner.verify(id, parsed.name, md, signature) -> "verified"
                    else -> "failed"
                }
                if (signState == "failed") {
                    android.util.Log.w("QuroSkillStore", "内置技能签名校验失败（可能已篡改）：$id / ${parsed.name}")
                }
                list.add(
                    parsed.copy(
                        id = id,
                        suite = suite,
                        signState = signState,
                        enabled = false,
                        callable = false,
                        alwaysOn = false,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
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
                        suite = o.optString("suite", ""),
                        signState = o.optString("signState", "unknown").takeIf { it.isNotBlank() } ?: "unknown",
                    )
                )
            }
        }
        return dedupeById(out).sortedBy { it.name }
    }

    /**
     * 签名排查：逐条校验随包内置技能的 HMAC 签名（直接比对 assets 源，不依赖已播种的 SharedPreferences）。
     * 返回报告供 UI「验证签名」按钮展示：通过 / 失败（疑似篡改）/ 缺失签名 的数量与失败项清单。
     */
    data class SkillVerifyReport(
        val total: Int,
        val verified: Int,
        val failed: Int,
        val unsigned: Int,
        val failedNames: List<String>,
    )

    fun verifyBuiltinSignatures(context: Context): SkillVerifyReport {
        val am = context.assets
        val manifest = runCatching {
            JSONObject(am.open("skills/zorv/manifest.json").bufferedReader().readText())
        }.getOrNull() ?: return SkillVerifyReport(0, 0, 0, 0, emptyList())
        val arr = manifest.optJSONArray("skills") ?: return SkillVerifyReport(0, 0, 0, 0, emptyList())
        var verified = 0
        var failed = 0
        var unsigned = 0
        val failedNames = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val file = o.optString("file", "")
            val signature = o.optString("signature", "").trim()
            val md = runCatching { am.open("skills/zorv/$file").bufferedReader().readText() }.getOrNull() ?: continue
            val name = parseSkillMd(md).firstOrNull()?.name ?: o.optString("name", id)
            when {
                signature.isBlank() -> unsigned++
                SkillSigner.verify(id, name, md, signature) -> verified++
                else -> { failed++; failedNames.add(name) }
            }
        }
        return SkillVerifyReport(arr.length(), verified, failed, unsigned, failedNames)
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
                        if (s.suite.isNotBlank()) put("suite", s.suite)
                        if (s.signState != "unknown") put("signState", s.signState)
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
