package com.ai.assistance.quro.core.skill
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.content.Context
import com.ai.assistance.quro.core.mcp.QuroMcpClientPrefs
import org.json.JSONArray
import com.ai.assistance.quro.core.tools.QuroToolSpecGuard
import org.json.JSONObject
import java.util.UUID

/** 技能默认参数 Schema（function calling 用）：技能作为可被调用工具时，模型传入的入参。 */
internal const val DEFAULT_SKILL_PARAMS =
    """{"type":"object","properties":{"input":{"type":"string","description":"用户希望按此技能处理的输入内容"}}}"""

/**
 * 用户自定义「技能 / SKILL」数据模型。
 *
 * 与已有的 QuickJS 插件运行时（小程序式 UI 插件）、CMS v2 能力模块（系统级能力）互相独立：
 * - 技能更轻量：本质是一段「工作指令 + 简介」。
 * - 不做 UI 渲染、不跑 JS、不需要权限；用户在对话框选中后，全文随那一条消息发给 AI。
 */
data class QuroSkill(
    val id: String,
    val name: String,
    val description: String = "",
    /** 技能中文简介（优先于 description 在技能选择界面展示；内置技能由官方维护，开源导入时从 SKILL.md 的 description_zh 字段读取）。 */
    val descriptionZh: String = "",
    /** 技能指令正文：注入系统提示词，作为 AI 的额外行为约束 / 能力说明。 */
    val prompt: String = "",
    /**
     * 是否留在技能库列表里（归档位），**不再决定 AI 能不能用**。
     *
     * 2026-11-05 改造：旧语义是「开启=注入系统提示词」，可 AI 只能收到一句技能名列表，
     * 根本不知道用户开了什么、更看不到正文 —— 开关不是一种发送方式。现在技能的生效路径
     * 只有一条：用户在对话框选中 → 随该条消息发送全文（见 [QuroSkillStore.attachedBlock]）。
     * 此字段仅保留给导入/导出、备份、旧数据兼容，并由一次性迁移统一置 true。
     */
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
    /**
     * 技能归属的 Bot（personaId）。空 = 全局共享（所有 Bot 可见）；
     * 非空 = 仅该人格卡（Bot）注入/可调用，按人格卡隔离 Skills。
     */
    val personaId: String = "",
) {
    /** 导出为应用内 JSON 格式（与 parseSkillJson 互为反向，可被本 App 重新导入）。 */
    fun toExportJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("description", description)
        if (descriptionZh.isNotBlank()) put("description_zh", descriptionZh)
        put("prompt", prompt); put("enabled", enabled); put("trigger", trigger)
        put("updatedAt", updatedAt); put("parametersJson", parametersJson)
        put("callable", callable); put("alwaysOn", alwaysOn)
        if (suite.isNotBlank()) put("suite", suite)
        if (signState != "unknown") put("signState", signState)
        if (personaId.isNotBlank()) put("personaId", personaId)
    }

    /** 导出为开放标准 SKILL.md 文本（与 anthropics/skills 等生态兼容，可被其它技能系统识别）。 */
    fun toSkillMd(): String = buildString {
        appendLine("---")
        appendLine("name: $name")
        if (description.isNotBlank()) appendLine("description: $description")
        if (descriptionZh.isNotBlank()) appendLine("description_zh: $descriptionZh")
        appendLine("---")
        appendLine()
        appendLine(prompt)
    }

    /**
     * 技能 → 工具规格（OpenAI function-calling 风格）：
     * {name:"skill__<name>", description, parameters:<JSON-Schema 对象>}。
     * 即「把技能注册成工具」的序列化形态（与 QuroToolRegistry 的 skillSpecs 命名一致）。
     */
    /**
     * 本技能作为 function-calling 工具时的合法名称。
     * 永远满足服务端约束 ^[a-zA-Z0-9_-]+$（前缀 skill__ 之后也合法），
     * 因此无论用户把技能名起成「cost/turn」「我的技能」还是带空格，
     * 下发给模型的 tools 数组都合法，不会再触发 HTTP 400（修复 issue #10）。
     */
    fun toolName(): String = QuroSkill.toolNameOf(name)

    fun toToolSpecJson(): JSONObject = JSONObject().apply {
        put("name", toolName())
        put("description", description.ifBlank { "用户技能：$name" })
        put("parameters", runCatching { JSONObject(parametersJson) }.getOrDefault(JSONObject(DEFAULT_SKILL_PARAMS)))
    }

    companion object {
        /** 技能工具名的固定前缀。其长度参与总长预算（见 [QuroToolSpecGuard.MAX_TOOL_NAME_LEN]）。 */
        private const val SKILL_PREFIX = "skill__"

        /**
         * 把任意技能名净化成合法的 function-calling 工具名片段。
         *
         * ## 为什么不能「把非法字符替换成 -」就完事（这里修了一个真 bug）
         *
         * 旧实现把每个非 ASCII 字符替换成 `-`、折叠连续 `-`、去首尾，空了回退 `"skill"`。
         * 于是**全中文技能名全部坍缩成同一个 `skill`**：
         * 「视频号账号诊断」「合同风险审查」「抖音热榜」→ 全都成了 `skill__skill`。
         * 紧接着 `QuroTool.specs()` 的 `distinctBy { it.name }` 把它们去重到只剩**一个** ——
         * 用户装了 N 个中文技能，AI 实际只能调用其中 1 个，**且没有任何日志**。
         *
         * 现委托 [QuroToolSpecGuard.sanitizeName]：一旦净化过程发生信息丢失，
         * 就追加**原名哈希尾缀**（如 `skill__tool-1a2b3c4d`）把唯一性找回来；
         * 同时把总长压进 64 字符内（OpenAI function name 上限，超了整段 tools 会被拒收）。
         *
         * ## 性质（反向查找依赖前两条，请勿破坏）
         * - **确定性**：同一技能名永远得到同一工具名 → 反向查找
         *   （`toolNameOf(skill.name) == call.name`）成立，无需保存额外映射。
         * - **幂等**：对已是合法名的输入返回自身。
         * - **长度预算**：`skill__`(7) + 片段 ≤ 64。
         */
        fun sanitizeToolName(raw: String): String {
            val base = raw.removePrefix(SKILL_PREFIX)
            return QuroToolSpecGuard.sanitizeName(
                base,
                QuroToolSpecGuard.MAX_TOOL_NAME_LEN - SKILL_PREFIX.length,
            )
        }

        /** 由技能名生成与工具一一对应的合法工具名（带 skill__ 前缀）。 */
        fun toolNameOf(raw: String): String = SKILL_PREFIX + sanitizeToolName(raw)

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
                personaId = o.optString("personaId", "").trim(),
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
        "design-studio", "cloudflare", "douyin-tiktok", "edgeone", "github", "deploy",
        "frontend-design", "humanizer", "search", "weather", "music",
        "ai-news", "document", "ppt-mindmap", "agent", "memory",
        "code", "self", "wxa", "ima", "qq-bot", "contract", "docker", "patent",
        "general",
    )
    val LABELS: Map<String, String> = mapOf(
        "design-studio" to "设计 / 美术套件（默认启用）",
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
        "邮件撰写", "演讲稿", qstr(R.string.qk_03368), "营销文案", "诗词创作", "算法面试", "旅行规划",
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
                        descriptionZh = o.optString("description_zh", ""),
                        prompt = o.optString("prompt", ""),
                        enabled = o.optBoolean("enabled", true),
                        trigger = o.optString("trigger", ""),
                        updatedAt = o.optLong("updatedAt", 0L),
                        parametersJson = o.optString("parametersJson", DEFAULT_SKILL_PARAMS),
                        callable = o.optBoolean("callable", true),
                        alwaysOn = o.optBoolean("alwaysOn", true),
                        suite = o.optString("suite", ""),
                        signState = o.optString("signState", "unknown").takeIf { it.isNotBlank() } ?: "unknown",
                        personaId = o.optString("personaId", ""),
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
     *
     * ✅ 唯一例外：[SUITE_DESIGN]（设计/美术套件）默认 enabled=true：
     * 这一组教会模型「怎么写对界面」，是 GenUI 渲染质量的地基（见 [designSkills]）。
     * 仍然 callable=false（不注册成工具，避免重演工具爆炸）+ alwaysOn=false（按触发词注入，不常驻）。
     */
    private const val KEY_BUILTIN_ZORV = "builtin_zorv_v1"

    /** 设计/美术套件：默认启用、按需注入的那组内建技能。 */
    const val SUITE_DESIGN = "design-studio"

    /**
     * 集群 V2 方法论套件（文档第四节）：14 个「工作流 + 自检清单」型技能包。
     *
     * 与 [SUITE_DESIGN] 是**不同性质**的东西：
     * - design-studio / 原有 67 个 = 工具型（怎么调某个 API、怎么写某个界面的手法）
     * - cluster-v2 = 方法论型（接到这类活该怎么一步步做、做完了怎么自检）
     * 所以不能靠改原有文件糊弄，必须新写。
     */
    const val SUITE_CLUSTER_V2 = "cluster-v2"

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
                        enabled = (suite == SUITE_DESIGN),
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
            // 设计套件是例外：它默认开，不能被这次「全部关掉」的迁移误伤
            if (s.id.startsWith("zorv_") && s.suite != SUITE_DESIGN && (s.enabled || s.callable || s.alwaysOn)) {
                changed = true
                s.copy(enabled = false, callable = false, alwaysOn = false, updatedAt = System.currentTimeMillis())
            } else s
        }
        if (changed) save(context, out)
    }

    /**
     * 一次性迁移：把设计/美术套件翻转为默认启用（enabled=true / callable=false / alwaysOn=false）。
     *
     * 背景：旧版本把 62 个内置技能一律 enabled=false，导致模型完全读不到「怎么写对界面」的规范，
     * 界面质量全靠模型自己猜 —— 这是「组件不好看 / 大片空白 / 文字叠印」的根因之一。
     * 设计套件改为默认启用，但**保持 callable=false**：只注入提示词，不注册成 function-calling 工具，
     * 因此不会重演离线模型被 60+ 技能工具压垮的问题。
     * 幂等（KEY_BUILTIN_ZORV_DESIGN_ON 守卫）；新装设备由 seedBuiltinZorvSkills 的默认值兜底。
     */
    private const val KEY_BUILTIN_ZORV_DESIGN_ON = "builtin_zorv_design_on_v1"

    fun migrateDesignSkillsOn(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BUILTIN_ZORV_DESIGN_ON, false)) return
        prefs.edit().putBoolean(KEY_BUILTIN_ZORV_DESIGN_ON, true).apply()
        val list = loadRaw(context)
        var changed = false
        val out = list.map { s ->
            if (s.suite == SUITE_DESIGN && (!s.enabled || s.callable || s.alwaysOn)) {
                changed = true
                s.copy(enabled = true, callable = false, alwaysOn = false, updatedAt = System.currentTimeMillis())
            } else s
        }
        if (changed) save(context, out)
    }

    /** V2 增量播种的一次性守卫（**独立于** [KEY_BUILTIN_ZORV]，否则老设备拿不到新包）。 */
    private const val KEY_BUILTIN_ZORV_V2 = "builtin_zorv_v2_v1"

    /**
     * 增量播种集群 V2 方法论套件（suite=cluster-v2）。
     *
     * 🔴 为什么必须单独开一条，不能靠 [seedBuiltinZorvSkills]：
     * 那个函数有 `builtin_zorv_v1` 一次性守卫，老设备早已置 true → 直接 return。
     * 只往 manifest.json 加条目 + 放 SKILL.md，对**已安装的设备完全无效** ——
     * 用户技能库里永远不会出现这 14 个包，角色卡 [SkillRef] 解析只会得到 missing。
     *
     * 幂等三重保障：
     * 1. 自己的守卫 key，同一设备只跑一次；
     * 2. 已存在的 id 直接跳过（用户删过的包不会被强行加回）；
     * 3. **不覆盖已存在条目的 enabled** —— 用户手动关掉的技能不该被重新打开。
     *
     * 签名校验沿用 [SkillSigner]：不符只标 failed 并打 Log，不丢弃（保持与旧行为一致）。
     */
    fun seedClusterV2Skills(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BUILTIN_ZORV_V2, false)) return
        prefs.edit().putBoolean(KEY_BUILTIN_ZORV_V2, true).apply()
        runCatching {
            val am = context.assets
            val manifest = JSONObject(am.open("skills/zorv/manifest.json").bufferedReader().readText())
            val arr = manifest.optJSONArray("skills") ?: return@runCatching
            val current = loadRaw(context)
            val existing = current.map { it.id }.toSet()
            val list = current.toMutableList()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("suite", "").trim() != SUITE_CLUSTER_V2) continue
                val id = o.optString("id", "")
                val file = o.optString("file", "")
                if (id.isEmpty() || file.isEmpty() || id in existing) continue
                val md = runCatching { am.open("skills/zorv/$file").bufferedReader().readText() }.getOrNull()
                    ?: continue
                val parsed = parseSkillMd(md).firstOrNull() ?: continue
                val signature = o.optString("signature", "").trim()
                val signState = when {
                    signature.isBlank() -> "unsigned"
                    SkillSigner.verify(id, parsed.name, md, signature) -> "verified"
                    else -> "failed"
                }
                if (signState == "failed") {
                    android.util.Log.w("QuroSkillStore", "V2 内置技能签名校验失败（可能已篡改）：$id / ${parsed.name}")
                }
                // enabled=true 但 callable/alwaysOn=false：只注入提示词，不注册成工具。
                list.add(
                    parsed.copy(
                        id = id,
                        suite = SUITE_CLUSTER_V2,
                        signState = signState,
                        enabled = true,
                        callable = false,
                        alwaysOn = false,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
            if (list.size != current.size) save(context, list)
        }
    }

    /**
     * 已入库的 V2 方法论技能（按名字查真实 id）。
     *
     * 主持启动即绑（[com.ai.assistance.quro.core.cluster.RoleRegistry]）靠它拿到
     * skill-fetch / role-forge 的稳定 id —— 这两个 id 来自 manifest 的
     * `zorv_ + sha1(name)[:12]` 算法，写死在代码里会随改名漂移，所以运行时查。
     */
    fun clusterV2SkillIds(context: Context, names: List<String>): List<String> {
        val want = names.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (want.isEmpty()) return emptyList()
        return runCatching {
            load(context)
                .filter { it.suite == SUITE_CLUSTER_V2 && it.name in want }
                .map { it.id }
        }.getOrDefault(emptyList())
    }

    /**
     * 当前启用、可被注入提示词的设计/美术套件技能。
     *
     * GenUI Agent 的大脑（ZorvBrain）用它拼「界面手艺 + 配色 + 自检 + 模式库」这几层，
     * 让模型在生成界面前就有规矩可依；用户在「技能」页关掉哪个，这里就少哪个。
     */
    fun designSkills(context: Context): List<QuroSkill> =
        runCatching { load(context).filter { it.suite == SUITE_DESIGN && it.enabled && it.prompt.isNotBlank() } }
            .getOrDefault(emptyList())

    /**
     * GenUI Agent 专用：设计/美术套件，**忽略用户在「技能」页的 enabled 开关**，永远给 GenUI 注入。
     *
     * 理由：GenUI 每一轮都是在写界面，没有「界面手艺 / 设计系统 / 自检评分」这套规范就会裸奔，
     * 这正是「组件不好看 / 大片空白 / 文字叠印」的根因。主对话的按需注入仍可走 [designSkills]（遵守开关），
     * 但 GenUI 强制带这套规范，不让用户在技能页随手关掉就废掉界面质量。
     *
     * 兜底：若用户技能库为空（极端情况），直接从随包 assets/skills/zorv 解析，保证 GenUI 永远拿得到规范。
     */
    fun designSkillsForGenUI(context: Context): List<QuroSkill> {
        val fromStore = runCatching {
            load(context).filter { it.suite == SUITE_DESIGN && it.prompt.isNotBlank() }
        }.getOrDefault(emptyList())
        if (fromStore.isNotEmpty()) return fromStore
        return designSkillsFromAssets(context)
    }

    /** 直接从随包 assets 解析 design-studio 套件（不依赖用户技能库 / 迁移状态）。 */
    private fun designSkillsFromAssets(context: Context): List<QuroSkill> {
        return runCatching {
            val am = context.assets
            val manifest = JSONObject(
                am.open("skills/zorv/manifest.json").bufferedReader().readText()
            )
            val arr = manifest.optJSONArray("skills") ?: return@runCatching emptyList()
            val out = mutableListOf<QuroSkill>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("suite", "").trim() != SUITE_DESIGN) continue
                val id = o.optString("id", "")
                val file = o.optString("file", "")
                if (id.isEmpty() || file.isEmpty()) continue
                val md = runCatching { am.open("skills/zorv/$file").bufferedReader().readText() }.getOrNull()
                    ?: continue
                val parsed = parseSkillMd(md).firstOrNull() ?: continue
                out.add(parsed.copy(id = id, suite = SUITE_DESIGN, enabled = true, callable = false, alwaysOn = false))
            }
            out
        }.getOrDefault(emptyList())
    }

    /**
     * 一次性迁移：把历史「被关掉」的技能全部放回技能库（enabled=true）。
     *
     * 背景：旧版把 enabled 当作「AI 能不能用」的总闸，用户关掉的技能既进不了目录、
     * 也无法被 `skill` 工具加载，而 AI 完全无从知晓这个状态。现在 enabled 只表示
     * 「是否留在库列表里」，且技能必须由用户在对话框选择后随消息发送，所以没有任何
     * 理由让历史关停状态继续把技能藏起来。幂等（守卫 key），不影响用户自建技能。
     */
    private const val KEY_ENABLED_VISIBILITY_FIX = "quro_skill_enabled_visibility_v1"

    fun migrateEnabledToLibraryVisibility(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ENABLED_VISIBILITY_FIX, false)) return
        prefs.edit().putBoolean(KEY_ENABLED_VISIBILITY_FIX, true).apply()
        val list = loadRaw(context)
        if (list.none { !it.enabled }) return
        save(context, list.map { if (it.enabled) it else it.copy(enabled = true) })
    }

    fun load(context: Context): List<QuroSkill> {
        clearBuiltinSkillsOnce(context)
        seedBuiltinZorvSkills(context)
        migrateBuiltinSkillsOff(context)
        migrateDesignSkillsOn(context)
        seedClusterV2Skills(context)
        // 必须放在上面几个「把内置技能关掉」的迁移之后，否则老设备会立刻又被翻回 false。
        migrateEnabledToLibraryVisibility(context)
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
                        descriptionZh = o.optString("description_zh", ""),
                        prompt = o.optString("prompt", ""),
                        enabled = o.optBoolean("enabled", true),
                        trigger = o.optString("trigger", ""),
                        updatedAt = o.optLong("updatedAt", 0L),
                        parametersJson = o.optString("parametersJson", DEFAULT_SKILL_PARAMS),
                        callable = o.optBoolean("callable", true),
                        alwaysOn = o.optBoolean("alwaysOn", true),
                        suite = o.optString("suite", ""),
                        signState = o.optString("signState", "unknown").takeIf { it.isNotBlank() } ?: "unknown",
                        personaId = o.optString("personaId", ""),
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

    /**
     * 🔴 从 GitHub 导入技能（2026-11-04）：仓库链接或 /tree/<分支>/<路径>。
     * 下载 zipball → 内存解压 → 扫描所有 SKILL.md（subPath 过滤）→ parseSkillMd 逐个入库。
     * 同名技能已存在则覆盖更新（保留原 id 与 enabled），否则新增。
     * 返回 Pair(新增数, 更新数)；网络/解析异常向上抛由 UI Toast。
     */
    fun importFromGitHub(context: Context, url: String, personaId: String): Pair<Int, Int> {
        // 解析 URL：仓库链接 / /tree/<分支>/<路径>
        val m = Regex("^https?://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:/tree/([^/]+)((?:/[^/]+)*))?$")
            .find(url.trim()) ?: throw IllegalArgumentException("无法识别的 GitHub 链接：支持仓库链接或 /tree/<分支>/<路径>")
        val owner = m.groupValues[1]
        val repo = m.groupValues[2].removeSuffix(".git")
        val ref = m.groupValues[3].ifBlank { "main" }
        val subPath = m.groupValues[4].trimStart('/').trimEnd('/')

        val zipUrl = "https://codeload.github.com/$owner/$repo/zip/refs/heads/$ref"
        val conn = (java.net.URL(zipUrl).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            instanceFollowRedirects = true
        }
        val bytes = if (conn.responseCode in 200..299) {
            conn.inputStream.use { it.readBytes() }
        } else {
            // main 不存在时试 master（老仓库）
            val fallback = "https://codeload.github.com/$owner/$repo/zip/refs/heads/master"
            val c2 = (java.net.URL(fallback).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
            }
            if (c2.responseCode !in 200..299) throw java.io.IOException("GitHub 下载失败 HTTP ${conn.responseCode}")
            c2.inputStream.use { it.readBytes() }
        }

        return importZipBytes(context, bytes, personaId, subPath, requireSkill = true)
    }

    /**
     * 🔴 技能包/ZIP 导入核心（2026-11-05）：扫描压缩包内全部 SKILL.md 逐个入库（同名覆盖），
     * 并识别 .claude-plugin/plugin.json 的 mcpServers（url 型）自动注册为 MCP 服务器
     * @return Pair(新增技能数, 更新技能数)
     */
    fun importZipBytes(context: Context, bytes: ByteArray, personaId: String, subPath: String = "", requireSkill: Boolean = false): Pair<Int, Int> {
        val entries = LinkedHashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (!e.isDirectory) entries[e.name] = zin.readBytes()
                zin.closeEntry()
            }
        }
        val rootPrefix = entries.keys.map { it.substringBefore('/') }.minByOrNull { it.length } ?: ""
        val skillMdPaths = entries.keys.filter {
            (it.endsWith("/SKILL.md") || it == "SKILL.md")
        }.filter { p ->
            val rel = p.removePrefix("$rootPrefix/").removeSuffix("SKILL.md").trimEnd('/')
            subPath.isBlank() || rel == subPath || rel.startsWith("$subPath/")
        }
        if (skillMdPaths.isEmpty() && requireSkill) throw java.io.IOException("压缩包内没找到 SKILL.md（分支/路径不对？）")
        val existing = load(context).toMutableList()
        var added = 0
        var updated = 0
        for (p in skillMdPaths) {
            val md = String(entries[p] ?: continue, Charsets.UTF_8)
            val parsed = parseSkillMd(md).firstOrNull() ?: continue
            val idx = existing.indexOfFirst { it.name == parsed.name }
            if (idx >= 0) {
                existing[idx] = parsed.copy(id = existing[idx].id, enabled = existing[idx].enabled)
                updated++
            } else {
                existing.add(parsed.copy(personaId = personaId))
                added++
            }
        }
        // ── .claude-plugin/plugin.json 的 mcpServers（url 型）→ 注册 MCP ──
        val pluginJsonPath = entries.keys.firstOrNull { it.endsWith(".claude-plugin/plugin.json") }
        if (pluginJsonPath != null) {
            runCatching {
                val pj = JSONObject(String(entries[pluginJsonPath] ?: return@runCatching, Charsets.UTF_8))
                val servers = pj.optJSONObject("mcpServers") ?: return@runCatching
                val keys = servers.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    val so = servers.optJSONObject(name) ?: continue
                    val u = so.optString("url", "")
                    if (u.isNotBlank()) {
                        QuroMcpClientPrefs.add(context, com.ai.assistance.quro.core.mcp.QuroMcpClient.McpServerConfig(
                            alias = name, url = u, kind = "remote", handshake = true,
                        ))
                    }
                }
            }
        }
        if (added + updated > 0) save(context, existing)
        return added to updated
    }

    /** 从本地 ZIP/.skill 包导入（文件选择器路径）。 */
    fun importSkillPackage(context: Context, bytes: ByteArray, personaId: String): Pair<Int, Int> =
        importZipBytes(context, bytes, personaId, "", requireSkill = false)

    fun save(context: Context, list: List<QuroSkill>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { s ->
                arr.put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("name", s.name)
                        put("description", s.description)
                        if (s.descriptionZh.isNotBlank()) put("description_zh", s.descriptionZh)
                        put("prompt", s.prompt)
                        put("enabled", s.enabled)
                        put("trigger", s.trigger)
                        put("updatedAt", s.updatedAt)
                        put("parametersJson", s.parametersJson)
                        put("callable", s.callable)
                        put("alwaysOn", s.alwaysOn)
                        if (s.suite.isNotBlank()) put("suite", s.suite)
                        if (s.signState != "unknown") put("signState", s.signState)
                        if (s.personaId.isNotBlank()) put("personaId", s.personaId)
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

    /**
     * 库内可用技能（含指令正文）。
     * `enabled` 经 [migrateEnabledToLibraryVisibility] 统一为 true，这里只按「有没有正文」过滤；
     * 它决定的是「目录里有哪些技能」，**不是** AI 能不能用 —— 能不能用由用户在对话框的选择决定。
     */
    fun enabledList(context: Context): List<QuroSkill> =
        load(context).filter { it.enabled && it.prompt.isNotBlank() }

    /** 注册为 function-calling 工具的技能（[QuroTool] 侧使用，与「随消息发送」是两条独立通道）。 */
    fun callableList(context: Context): List<QuroSkill> =
        load(context).filter { it.enabled && it.callable && it.prompt.isNotBlank() }

    /**
     * 按 Bot 隔离过滤：只保留「全局技能（personaId 空）」或「归属该 personaId」的技能。
     * personaId 为空 = 只取全局技能（无 Bot 上下文时兜底，避免把别的 Bot 专属技能串进来）。
     */
    fun scopedTo(list: List<QuroSkill>, personaId: String): List<QuroSkill> =
        list.filter { it.personaId.isBlank() || it.personaId == personaId }

    /** 当前 Bot 可见的库内技能（拼元数据目录用）。personaId 为空=仅全局技能。 */
    fun enabledListFor(context: Context, personaId: String): List<QuroSkill> =
        scopedTo(enabledList(context), personaId)

    /**
     * Skills Catalog：只注入元数据，每条一行、最多 64 条、描述超 240 字符截断。
     *
     * 技能全文有两条下发路径，都不依赖「开关」：
     *  1. 用户在对话框选中 → [attachedBlock] 生成全文，作为隐藏 user 消息随那条消息发送（优先）；
     *  2. AI 自行在目录里发现相关技能 → 调 skill 工具 → 全文作为 tool result 进当轮。
     *
     * 旧版把「启用」当闸门，可 AI 只收到名字列表，根本感知不到用户开了什么 —— 已废除。
     * 目录每行格式：- [id=xxx] 名称: 描述。
     */
    fun skillsCatalog(context: Context, personaId: String): String {
        val skills = enabledListFor(context, personaId)
            .filter { it.name.isNotBlank() || it.description.isNotBlank() }
            .take(64)
        if (skills.isEmpty()) return ""
        val header = "## Skills Catalog\n" +
            "Only skill metadata is listed here. When a skill is relevant, call tool `skill` with `name` set to its id " +
            "to load its full instructions before solving the task.（技能目录：按需调 `skill` 工具加载全文，不要凭目录臆测内容）\n" +
            "If the user explicitly attached skills to their message, those full instructions are sent with that message " +
            "and take priority over this catalog.（用户为某条消息选中的技能会随该消息下发全文，优先级高于本目录）"
        val lines = skills.map { s ->
            val id = s.id.ifBlank { s.name }
            val name = s.name.ifBlank { id }
            val desc = s.description.ifBlank { "（no description）" }
                .let { if (it.length > 240) it.take(240) + "..." else it }
            "- [id=$id] $name: $desc"
        }.sorted()
        val overflow = enabledListFor(context, personaId).size - skills.size
        val more = if (overflow > 0)
            "\n$overflow more skills are available but not listed here. If none of the listed skills fits, " +
                "ask the user to pick the exact skill from the chat composer's skill picker — its full instructions " +
                "will then be sent together with their message.（让用户到对话框「选择技能」里精确挑选，全文会随消息发给你）"
        else ""
        return header + "\n" + lines.joinToString("\n") + more
    }

    /** skill 工具执行：按 id 或名称在技能库中匹配，找不到返回提示。 */
    fun findForTool(context: Context, idOrName: String, personaId: String): QuroSkill? {
        val key = idOrName.trim()
        if (key.isEmpty()) return null
        val enabled = enabledListFor(context, personaId)
        return enabled.firstOrNull { it.id.equals(key, ignoreCase = true) }
            ?: enabled.firstOrNull { it.name.equals(key, ignoreCase = true) }
            ?: enabled.firstOrNull { it.name.contains(key, ignoreCase = true) }
    }

    /**
     * 按轮加载块：`skill` 工具的 tool result —— 全文 + 补充说明。
     * 「for this turn」语义：指令随本次 tool result 存在于当轮上下文，下轮不自动续。
     */
    fun loadedBlock(skill: QuroSkill): String =
        buildString {
            append("## Skill loaded for this turn: ").append(skill.name).append('\n')
            append(skill.prompt)
            if (skill.trigger.isNotBlank()) {
                append("\n\nTrigger words（用户消息命中这些词时也应优先使用本技能）: ").append(skill.trigger)
            }
        }

    /**
     * 隐藏消息固定前缀。发送链路靠它在构造本轮上下文时清掉上一轮的
     * 附加技能块，保证「用户选的技能只对那一条消息生效」，不会在历史里越攒越多。
     */
    const val ATTACHED_MARKER = "[用户为本条消息附加了技能]"

    /**
     * 按 id 取回用户为本次消息选中的技能（按技能库顺序，去重）。
     *
     * 不做 `enabled` / `personaId` 过滤：用户**显式选中**就是最高优先级，
     * 不能出现「选了却发不出去」这种又一种 AI 看不见的隐性状态。
     */
    fun byIds(context: Context, ids: List<String>, personaId: String = ""): List<QuroSkill> {
        if (ids.isEmpty()) return emptyList()
        val want = ids.toSet()
        return load(context).filter { it.id in want && it.prompt.isNotBlank() }
    }

    /**
     * 「选择 → 发给 AI → AI 使用」这条链路的载荷：**技能全文**，不是名字、不是开关状态。
     *
     * 作为隐藏 user 消息插在用户本条消息之前下发，措辞上明确告诉模型：
     * 这是用户为**这一条**消息指定的工作指令，必须直接执行，且只对本轮有效。
     */
    fun attachedBlock(skills: List<QuroSkill>): String {
        val list = skills.filter { it.prompt.isNotBlank() }
        if (list.isEmpty()) return ""
        return buildString {
            append(ATTACHED_MARKER).append('\n')
            append("用户为**紧随其后的这一条消息**明确选择了 ${list.size} 个技能。").append('\n')
            append("这些不是背景资料，而是你本轮必须执行的工作指令：\n")
            append("1. 直接按下面每个技能的步骤、约束与输出格式完成本条请求；不要只复述技能内容，也不要说「我没有加载该技能」。\n")
            append("2. 多个技能冲突时按列出顺序优先；与用户本条消息冲突时，以用户本条消息为准。\n")
            append("3. 这些技能只对这一条消息生效，之后的对话不要继续沿用。\n")
            append("4. 全文已经在下面给出，不要再调用 skill 工具重复加载它们。\n")
            list.forEachIndexed { i, s ->
                append('\n').append("### 技能 ").append(i + 1).append("：").append(s.name).append('\n')
                append(s.prompt.trim()).append('\n')
            }
            append("\n（以上技能仅对本条消息生效；现在请处理用户的下一条消息。）")
        }
    }

    /**
     * 按触发词匹配技能（用于 send / voiceBallTurn 的按需预注入）。
     * 匹配**所有**技能（不限 enabled）：调用方自行决定「未开启但命中触发词」是否要按需兜底注入，
     * 已开启的技能由常驻系统提示词负责，不需要这里再补。
     */
    fun matchTriggerSkills(userText: String, context: Context): List<QuroSkill> {
        val t = userText.lowercase()
        return load(context).filter { s ->
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
        val descriptionZh = Regex("^description_zh:\\s*(.+)$", RegexOption.MULTILINE).find(frontmatter)?.groupValues?.get(1)?.trim() ?: ""
        // 触发词：开放标准 SKILL.md 的可选字段，逗号分隔。缺省回落到技能名，
        // 这样内置技能（随包 SKILL.md）也能被 matchTriggerSkills 命中，做到「默认启用 + 按需注入」。
        val trigger = Regex("^trigger:\\s*(.+)$", RegexOption.MULTILINE).find(frontmatter)?.groupValues?.get(1)?.trim()
            ?: name
        val prompt = body.trim().ifBlank { return emptyList() }
        return listOf(
            QuroSkill(
                id = UUID.randomUUID().toString(),
                name = name,
                description = description,
                descriptionZh = descriptionZh,
                trigger = trigger,
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