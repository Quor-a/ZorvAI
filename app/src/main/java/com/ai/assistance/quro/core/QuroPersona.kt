package com.ai.assistance.quro.core
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 结构化标签（原创）：每条标签含名称、描述、AI 提示内容、JSON 配置块。
 * 标签为全局独立资源（见 [QuroTagRepository]），人格卡只引用其名称。
 * hint 会融入系统提示词约束语气；json 为用户自定义的任意结构化配置（保留字段，供高级用法）。
 */
data class QuroTag(
    val name: String,           // 显示名，如 "元气"
    val description: String = "", // 标签含义说明，如 "性格开朗、充满正能量"
    val hint: String = "",      // AI 提示内容，如 "说话活泼、多用感叹号和emoji"
    val json: String = "",      // 用户自定义 JSON 配置（保留字段，如 {"tone":"playful"}）
)

/**
 * 人格语音组合（原创，Project B1）：把"人格 ↔ 语音"结构化绑定。
 * 仅当 [providerId] 非空时启用；[voiceId]/[emotion]/[speed] 为空时回落到全局 TTS 配置。
 * - providerId：目标 TTS 服务商 id（"edge"/"mimo"/"volcengine"/...），空=跟随全局选中服务商。
 * - voiceId：预置/自由音色 id（如 "zh-CN-XiaoxiaoNeural"），空=跟随服务商默认音色。
 * - emotionEnabled/emotionTags：情绪/风格标签开关组（对应各服务商 providerTags，如 "gentle"/"严肃"）。
 *   关闭 emotionEnabled 时由 LLM 自动组合；开启后 emotionTags 中的标签交给 LLM 自由组合，默认不全开。
 * - speed：语速倍率（1.0=默认；0.5–2.0），由各客户端按自身参数映射（Edge 相对百分比 / MiniMax·火山倍率 / 讯飞 0-100）。
 */
data class QuroVoiceProfile(
    val providerId: String = "",
    val voiceId: String = "",
    val emotionEnabled: Boolean = false,
    val emotionTags: List<String> = emptyList(),
    val speed: Float = 1.0f,
)

/**
 * 人格卡：Zorv AI 的「灵魂」。一张人格卡定义 AI 的身份、语气与长期记忆来源。
 * 字段覆盖：名称 / 头像（自定义图片，无图时退化为首字母）/ 描述 / 角色设定 / 开场白 / 聊天设定 /
 * 语音设定（供 TTS 等语音功能使用，不进入系统提示词）/ AI 人格孵化 / 标签（仅存全局标签名）。
 */
data class QuroPersona(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val avatarEmoji: String = "🤖",
    val avatarType: String = "emoji",   // "emoji" | "image"
    val avatarUri: String = "",         // 自定义图片头像的内部路径（avatarType=="image" 时有效）
    val description: String = "",
    val roleSetting: String = "",   // 角色设定：系统提示词核心
    val opening: String = "",       // 开场白：首次对话的问候
    val chatSetting: String = "",   // 聊天设定：回复长度/语气/禁忌约束
    val voiceSetting: String = "",  // 语音设定：推荐音色/语速（自然语言，供 TTS 使用，不进系统提示词）
    val incubation: String = "",    // AI 人格孵化：孵化灵感与备忘
    val lastIncubatedAt: Long = 0,  // 每卡独立心跳时间戳：最近一次（心跳/手动）孵化完成时刻，0=未孵化
    /**
     * Soul：这只人格卡跟你长期相处沉淀下来的「人格画像」（Markdown）。
     *
     * 与 [roleSetting]（静态角色设定，用户手写）不同，Soul 是**动态沉淀**的——
     * 由自动进化从对话里总结、也可手动编辑，是「它已经长成了什么样」的记录。
     * 可留空（尚未形成 Soul）。注入系统提示词时优先级：身份认知 > roleSetting > Soul > 语气标签。
     */
    val soul: String = "",          // Soul 人格画像（Markdown，自动沉淀 + 可手动编辑）
    val soulUpdatedAt: Long = 0,    // Soul 最近一次更新时刻，0=未形成
    /**
     * 🔴 待学清单（2026-11-04 孵化/进化合体，学习进步导向）：
     * 自我进化引擎产出的「下一阶段学习计划」，每条 `待办|待学 :: 标题 :: 建议`。
     * 每次进化时回喂给引擎检查完成情况——孵化=给自己列作业，而不是改设定。
     */
    val openItems: String = "",
    /**
     * 作为「人格卡 → 模型调用」的覆盖层，仅在开关打开时生效，否则跟随全局模型配置。
     * - temperature / topP：采样值（1.0 = 跟随模型默认）。
     * - temperatureEnabled / topPEnabled：是否显式覆盖（关闭 = 用模型/全局默认）。
     * - reasoningLevel：推理档位（low/medium/high），空 = 跟随全局。
     */
    val temperature: Float = 1.0f,
    val topP: Float = 1.0f,
    val temperatureEnabled: Boolean = false,
    val topPEnabled: Boolean = false,
    val reasoningLevel: String = "",  // "low" | "medium" | "high" | ""（空=跟随全局）
    /**
     * 空字符串 = 跟随全局模型配置（旧行为）；非空 = 该人格卡（Bot）绑定指定模型名，
     * 发送时覆盖 cfg.model（provider/baseUrl/apiKey 仍跟随全局）。这是
     * 「Bot = 模型 + 提示词 + Soul + Memory + 工具开关」按 Bot 隔离的最后一块拼图。
     */
    val defaultModelId: String = "",
    /**
     * 空 = 全部工具可用（默认，保持旧行为零迁移）；非空 = 只下发这些类别内的工具。
     * 类别清单见 [com.ai.assistance.quro.core.tools.ToolCategory]。
     */
    val enabledToolCategories: List<String> = emptyList(),
    /**
     * - evolutionEnabled：自动进化（对话若干轮后自动沉淀 Soul）；
     * - memoryEnabled：记忆注入（关闭后该 Bot 的系统提示词不带记忆/经验段）；
     * - proactiveMode：主动模式（该 Bot 允许被心跳/事件触发主动发消息）。
     */
    val evolutionEnabled: Boolean = true,
    val memoryEnabled: Boolean = true,
    /**
     * - 最小间隔小时数（0–168，默认 8；0 = 不限间隔，只按轮数触发）；
     * - 触发检查的最小完成轮数（1–50，默认 10）。
     * 两者同时满足才进入检查（此前 ZorvAI 写死固定值，无法按 Bot 调节）。
     */
    val evolutionMinIntervalHours: Int = 8,
    val evolutionTurnsPerCheck: Int = 10,
    val proactiveMode: Boolean = false,
    val tags: List<String> = emptyList(),  // 仅存全局标签名称（见 QuroTagRepository）
    val voiceProfile: QuroVoiceProfile? = null, // 结构化语音组合（B1）；空=跟随全局 TTS 设置
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

class QuroPersonaRepository(val context: Context) {
    private val file = File(context.filesDir, "quro_personas.json")
    private val prefs: SharedPreferences =
        context.getSharedPreferences("quro_persona", Context.MODE_PRIVATE)

    fun loadAll(): List<QuroPersona> {
        if (!file.exists()) {
            val seeded = seed()
            saveAll(seeded)
            // 首次启动：自动激活第一个人格卡（让系统提示词立即生效）
            if (seeded.isNotEmpty()) setActiveId(seeded[0].id)
            return seeded
        }
        val text = runCatching { file.readText() }.getOrElse { return emptyList() }
        if (text.isBlank()) return emptyList()
        val arr = runCatching { JSONObject(text).optJSONArray("personas") }.getOrNull() ?: return emptyList()
        val out = mutableListOf<QuroPersona>()
        for (i in 0 until arr.length()) {
            runCatching { parse(arr.getJSONObject(i)) }.getOrNull()?.let { out.add(it) }
        }
        // 🔒 串台防御（全面排查）：剥离泄漏的测试用「星眠少女」女友人格。
        // 该人格含「主人/女朋友/♡」设定，曾因被设为激活态导致 AI 全程串台撒娇、
        // 工具调用失准、上下文错乱（用户实测收到「主人～你发了个数字58205」等错乱回复）。
        // 即便历史数据曾激活它，也绝不向 UI / 系统提示词暴露，并把修正持久化回文件。
        val cleaned = out.filter { it.name != "星眠少女" }
        if (cleaned.size != out.size) runCatching { saveAll(cleaned) }
        return cleaned
    }

    fun saveAll(list: List<QuroPersona>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(serialize(it)) }
            file.writeText(JSONObject().put("personas", arr).toString())
        }
    }

    fun upsert(p: QuroPersona) {
        val all = loadAll().toMutableList()
        val idx = all.indexOfFirst { it.id == p.id }
        val updated = p.copy(updatedAt = System.currentTimeMillis())
        if (idx >= 0) all[idx] = updated else all.add(updated)
        saveAll(all)
    }

    fun delete(id: String) {
        saveAll(loadAll().filter { it.id != id })
        if (getActiveId() == id) setActiveId("")
    }

    /**
     * 当前激活人格的 id。
     * 兜底逻辑（修复「自我认知不行」）：
     * - 若从未激活（id 为空），或激活项已被删除 → 自动激活第一张人格卡并持久化。
     * 这样无论新装/重装/旧数据，系统提示词都能拿到一张真实人格（小星等），
     * 不会再回退到通用「Zorv AI」兜底导致身份认知丢失。
     */
    fun getActiveId(): String {
        val cur = prefs.getString(KEY_ACTIVE, "") ?: ""
        if (cur.isNotBlank()) {
            val all = loadAll()
            val active = all.firstOrNull { it.id == cur }
            // 🔒 串台防御（全面排查）：即便历史数据曾把「星眠少女」女友人格设为激活态，
            // 也强制回落到 Zorv AI，杜绝「主人/♡」女友人格串台应答、工具调用失准、上下文错乱。
            if (active != null && active.name != "星眠少女") return cur
        }
        val all = loadAll()
        if (all.isEmpty()) return ""
        // 优先回落到固定 ID 的 Zorv AI 主人格；旧数据无此 ID 时退化为首张人格卡。
        val zorv = all.firstOrNull { it.id == ZORV_AI_ID } ?: all.first()
        if (zorv.id != cur) setActiveId(zorv.id)
        return zorv.id
    }

    fun setActiveId(id: String) = prefs.edit { putString(KEY_ACTIVE, id) }

    /** 返回当前激活的人格卡；若数据缺失则回退第一个或返回空人格。供 TTS 读取语音风格卡。 */
    fun getActive(): QuroPersona {
        val id = getActiveId()
        val all = loadAll()
        return all.firstOrNull { it.id == id } ?: all.firstOrNull() ?: QuroPersona()
    }

    /** 按 id 取一条人格卡；不存在返回 null。集群模块（RoleRegistry / ClusterEngine）依赖此方法。 */
    fun get(id: String): QuroPersona? = loadAll().firstOrNull { it.id == id }

    /** 解析 JSONArray 为字符串列表（空/异常返回空列表）。 */
    private fun parseStringList(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun seed(): List<QuroPersona> {
        val now = System.currentTimeMillis()

        /** 将 assets 中的内置头像复制到应用私有目录，返回文件绝对路径（失败返回空串）。 */
        fun builtinAvatar(assetPath: String): String {
            return runCatching {
                val dir = File(context.filesDir, "quro_avatars")
                dir.mkdirs()
                val dst = File(dir, assetPath.substringAfterLast('/'))
                if (!dst.exists()) {
                    context.assets.open(assetPath).use { input ->
                        dst.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                dst.absolutePath
            }.getOrNull() ?: ""
        }

        return listOf(
            QuroPersona(
                id = ZORV_AI_ID,
                name = "Zorv AI",
                avatarEmoji = "✦",
                avatarType = "image",
                avatarUri = builtinAvatar("avatars/avatar_quro_ai.jpg"),
                description = qstr(R.string.qk_03625),
                roleSetting = "你叫 Zorv AI，是一个全能型 AI 助手。你理性客观、逻辑清晰，同时温暖贴心。你擅长回答各类问题、协助创作、分析数据、编写代码、翻译语言、策划方案。你说话简洁有力但不冷漠，会在用户需要时给出详尽解释和多种方案。",
                opening = "你好！我是 Zorv AI ✦ 随时为你效劳，今天想做什么？",
                chatSetting = "简洁专业有温度；复杂问题善用分点；适时用 emoji 增加亲和力；主动追问关键细节。",
                voiceSetting = "清澈中性声，语速适中",
                voiceProfile = QuroVoiceProfile(providerId = "", voiceId = "", emotionEnabled = false, emotionTags = emptyList(), speed = 1.0f),
                tags = listOf("全能", "助手", "理性", "温暖"),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun parse(o: JSONObject): QuroPersona {
        val tagsArr = o.optJSONArray("tags")
        val tags = if (tagsArr != null) {
            val list = mutableListOf<String>()
            for (i in 0 until tagsArr.length()) {
                // 兼容三种格式：
                // 1) JSONObject（旧格式 QuroTag 对象）→ 提取 name
                // 2) 纯字符串（新格式，仅存标签名）→ 直接使用
                // 3) 字符串但内容是 JSON（序列化异常：QuroTag 被 toString/手动写成 JSON 字符串存入）→ 解析提取 name
                val tagObj = tagsArr.optJSONObject(i)
                var tagStr = if (tagObj != null) tagObj.optString("name", "").trim() else tagsArr.optString(i, "").trim()
                // 兜底：如果 tagStr 以 { 开头，说明是 JSON 字符串格式的旧数据，尝试解析取 name
                if (tagStr.startsWith("{")) {
                    runCatching {
                        val inner = org.json.JSONObject(tagStr)
                        tagStr = inner.optString("name", "").trim()
                    }
                }
                if (tagStr.isNotEmpty()) list.add(tagStr)
            }
            list
        } else {
            emptyList()
        }
        val vpObj = o.optJSONObject("voiceProfile")
        val voiceProfile = if (vpObj != null) {
            val legacyEmotion = vpObj.optString("emotion", "")
            val emotionEnabled = if (vpObj.has("emotionEnabled")) vpObj.optBoolean("emotionEnabled", false) else legacyEmotion.isNotBlank()
            val emotionTags = if (vpObj.has("emotionTags")) {
                val arr = vpObj.optJSONArray("emotionTags")
                (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it) }.filter { it.isNotBlank() }
            } else if (legacyEmotion.isNotBlank()) listOf(legacyEmotion) else emptyList()
            QuroVoiceProfile(
                providerId = vpObj.optString("providerId", ""),
                voiceId = vpObj.optString("voiceId", ""),
                emotionEnabled = emotionEnabled,
                emotionTags = emotionTags,
                speed = vpObj.optDouble("speed", 1.0).toFloat(),
            )
        } else null
        return QuroPersona(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", ""),
            avatarEmoji = o.optString("avatarEmoji", "🤖").ifBlank { "🤖" },
            avatarType = o.optString("avatarType", "emoji").ifBlank { "emoji" },
            avatarUri = o.optString("avatarUri", ""),
            description = o.optString("description", ""),
            roleSetting = o.optString("roleSetting", ""),
            opening = o.optString("opening", ""),
            chatSetting = o.optString("chatSetting", ""),
            voiceSetting = o.optString("voiceSetting", ""),
            incubation = o.optString("incubation", ""),
            lastIncubatedAt = o.optLong("lastIncubatedAt", 0),
            soul = o.optString("soul", ""),
            soulUpdatedAt = o.optLong("soulUpdatedAt", 0),
            openItems = o.optString("openItems", ""),
            temperature = o.optDouble("temperature", 1.0).toFloat(),
            topP = o.optDouble("topP", 1.0).toFloat(),
            temperatureEnabled = o.optBoolean("temperatureEnabled", false),
            topPEnabled = o.optBoolean("topPEnabled", false),
            reasoningLevel = o.optString("reasoningLevel", ""),
            defaultModelId = o.optString("defaultModelId", ""),
            evolutionMinIntervalHours = o.optInt("evolutionMinIntervalHours", 8).coerceIn(0, 168),
            evolutionTurnsPerCheck = o.optInt("evolutionTurnsPerCheck", 10).coerceIn(1, 50),
            enabledToolCategories = parseStringList(o.optJSONArray("enabledToolCategories")),
            evolutionEnabled = o.optBoolean("evolutionEnabled", true),
            memoryEnabled = o.optBoolean("memoryEnabled", true),
            proactiveMode = o.optBoolean("proactiveMode", false),
            tags = tags,
            voiceProfile = voiceProfile,
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
        )
    }

    private fun serialize(p: QuroPersona): JSONObject {
        val tagsArr = JSONArray()
        p.tags.forEach { tagsArr.put(it) }
        return JSONObject().apply {
            put("id", p.id)
            put("name", p.name)
            put("avatarEmoji", p.avatarEmoji)
            put("avatarType", p.avatarType)
            put("avatarUri", p.avatarUri)
            put("description", p.description)
            put("roleSetting", p.roleSetting)
            put("opening", p.opening)
            put("chatSetting", p.chatSetting)
            put("voiceSetting", p.voiceSetting)
            put("incubation", p.incubation)
            put("lastIncubatedAt", p.lastIncubatedAt)
            put("soul", p.soul)
            put("soulUpdatedAt", p.soulUpdatedAt)
            if (p.openItems.isNotBlank()) put("openItems", p.openItems)
            put("temperature", p.temperature)
            put("topP", p.topP)
            put("temperatureEnabled", p.temperatureEnabled)
            put("topPEnabled", p.topPEnabled)
            put("reasoningLevel", p.reasoningLevel)
            put("defaultModelId", p.defaultModelId)
            put("evolutionMinIntervalHours", p.evolutionMinIntervalHours)
            put("evolutionTurnsPerCheck", p.evolutionTurnsPerCheck)
            put("enabledToolCategories", JSONArray().apply { p.enabledToolCategories.forEach { put(it) } })
            put("evolutionEnabled", p.evolutionEnabled)
            put("memoryEnabled", p.memoryEnabled)
            put("proactiveMode", p.proactiveMode)
            if (p.voiceProfile != null) {
                put("voiceProfile", JSONObject().apply {
                    put("providerId", p.voiceProfile.providerId)
                    put("voiceId", p.voiceProfile.voiceId)
                    put("emotionEnabled", p.voiceProfile.emotionEnabled)
                    put("emotionTags", JSONArray().apply { p.voiceProfile.emotionTags.forEach { put(it) } })
                    put("speed", p.voiceProfile.speed)
                })
            }
            put("tags", tagsArr)
            put("createdAt", p.createdAt)
            put("updatedAt", p.updatedAt)
        }
    }

    /**
     * 当前激活人格之外**其它人格卡**的 name + description 清单，注入系统提示词——
     * AI 知道「本机还有谁、各自擅长什么」，配合 `ask_persona` 工具实现人格互问。
     */
    fun personaDirectorySection(context: Context, excludePersonaId: String): String {
        val others = loadAll().filter {
            it.id != excludePersonaId && it.name.isNotBlank() && it.description.isNotBlank()
        }
        if (others.isEmpty()) return ""
        return buildString {
            append("## 人格目录（本机的其它人格卡）\n")
            append("以下是本机其它的 AI 人格。需要另一个视角/专长时，用 `ask_persona` 工具（persona 传它的 id）向它提问：\n")
            others.forEach { p ->
                append("- [id=${p.id}] ${p.name}: ${p.description.take(160)}\n")
            }
        }
    }

    companion object {
        private const val KEY_ACTIVE = "active_persona_id"
        /** Zorv AI 主人格固定 ID：跨安装 / 版本保持稳定，避免随机 UUID 打乱激活态。 */
        const val ZORV_AI_ID = "persona_zorv_ai"
    }
}