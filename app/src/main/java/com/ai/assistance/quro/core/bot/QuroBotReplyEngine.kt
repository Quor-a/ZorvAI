package com.ai.assistance.quro.core.bot

import android.content.Context
import com.ai.assistance.quro.core.QuroAssistant
import com.ai.assistance.quro.core.QuroPersonaRepository
import com.ai.assistance.quro.core.QuroPlatformManifest
import com.ai.assistance.quro.core.QuroReplyLanguage
import com.ai.assistance.quro.core.QuroTagRepository
import com.ai.assistance.quro.core.QuroConversationStore
import com.ai.assistance.quro.core.QuroMessage
import com.ai.assistance.quro.core.QuroPersona
import com.ai.assistance.quro.core.memory.QuroMemoryRepository
import com.ai.assistance.quro.core.model.QuroFunctionModelConfigRepository
import com.ai.assistance.quro.core.model.QuroFunctionType
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.network.QuroLlmClient
import com.ai.assistance.quro.core.skill.QuroSkillStore
import com.ai.assistance.quro.core.soul.QuroSoulPromptEngine
import com.ai.assistance.quro.core.soul.SoulContext
import com.ai.assistance.quro.core.tools.buildQuroRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 机器人回复引擎（复用既有对话内核，不依赖 UI）。
 *
 * 每个「平台:用户」维护一条独立会话（[QuroConversationStore] + 一个 [QuroAssistant]），
 * 支持多轮上下文。复用 [buildQuroRegistry] 拿到与 App 完全一致的真实工具集
 * （含知识库工具），因此机器人也能调用设备能力与 RAG 知识库。
 *
 *
 * 旧实现给所有 IM 通道（QQ/飞书/微信）硬编码同一份无人格的系统提示词 ——
 * 激活人格卡的 roleSetting / Soul / 聊天设定 / 语气标签 / 记忆 / 技能全部丢失，
 * IM 上聊出来的完全是「另一个人」。现在与主聊天（QuroChatViewModel.buildSystemPrompt）
 * 走同一套组装顺序：
 *   品牌基座 → 语言指令 → 灵魂层（QuroSoulPromptEngine：身份/roleSetting/Soul/聊天设定/标签/记忆）
 *   → 应用服务目录 → 工具能力共识 → 用户技能 → 用户称呼 → IM 场景约束。
 * 模板变量 {{current_datetime}} / {{device_info}} / {{location}} 同样在发送前替换。
 *
 * 接入点：core/QuroAssistant.kt:27（构造器）、core/tools/QuroBuiltInTools.kt:149（buildQuroRegistry）、
 *        core/model/QuroModelConfig.kt:28（模型配置，含 apiKey/baseUrl）。
 */

/**
 * 机器人一次回复：以文本为主，可附带一张要发送的图片（字节 + 文件名）。
 * imageBytes 非空时，支持图片的平台（飞书）优先发图，文字作为附言补发。
 */
@Suppress("ArrayInDataClass")
data class QuroReply(
    val text: String,
    val imageBytes: ByteArray? = null,
    val imageFileName: String? = null,
)

class QuroBotReplyEngine(private val appContext: Context) {
    private val registry = buildQuroRegistry(appContext.applicationContext)
    private val client = QuroLlmClient()
    /** key = "${platform}:${userId}" → 该用户的会话内核。 */
    private val sessions = ConcurrentHashMap<String, QuroAssistant>()
    private val stores = ConcurrentHashMap<String, QuroConversationStore>()

    private val personaRepo by lazy { QuroPersonaRepository(appContext.applicationContext) }
    private val memoryRepo by lazy { QuroMemoryRepository(appContext.applicationContext) }
    private val tagRepo by lazy { QuroTagRepository(appContext.applicationContext) }

    /** 当前激活人格卡（与主聊天同一数据源；无激活卡时 null = 退回无人格的基础提示词）。 */
    private fun activePersona(): QuroPersona? {
        val id = personaRepo.getActiveId()
        return personaRepo.loadAll().firstOrNull { it.id == id }
    }

    /** 全局「自动保存记忆」开关（与主聊天 autoSaveMemory.value 同源）。 */
    private fun globalMemoryOn(): Boolean = appContext
        .getSharedPreferences("quro_ui", android.content.Context.MODE_PRIVATE)
        .getBoolean("auto_save_memory", true)

    /**
     * Bot 模板变量替换（对齐主聊天 QuroChatViewModel 的同名气泡）：
     * {{current_datetime}} / {{device_info}} / {{location}} 在人格卡的
     * 角色设定 / 聊天设定 / 简介 / 开场白里可用，发送前替换为真实值。
     * 只作用于本次提示词，**不回写**存储——模板变量只活在发送那一刻。
     */
    private fun applyTemplateVars(p: QuroPersona): QuroPersona {
        val dt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm (EEEE)", java.util.Locale.CHINA)
            .format(java.util.Date())
        val dev = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}"
        val loc = runCatching { lastKnownLocationText() }.getOrNull().orEmpty()
        fun sub(raw: String) = raw
            .replace("{{current_datetime}}", dt)
            .replace("{{device_info}}", dev)
            .replace("{{location}}", loc)
        return p.copy(
            roleSetting = sub(p.roleSetting),
            chatSetting = sub(p.chatSetting),
            description = sub(p.description),
            opening = sub(p.opening),
        )
    }

    /** 最近已知位置的口语化描述（与主聊天 lastKnownLocationText 同逻辑，供 {{location}} 用）。 */
    private fun lastKnownLocationText(): String? {
        val ctx = appContext.applicationContext
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return null
        val providers = listOf(
            android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER,
            android.location.LocationManager.GPS_PROVIDER,
        )
        val last = providers.firstNotNullOfOrNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
        } ?: return null
        return runCatching {
            val geocoder = android.location.Geocoder(ctx, java.util.Locale.CHINA)
            @Suppress("DEPRECATION")
            val list = geocoder.getFromLocation(last.latitude, last.longitude, 1)
            val a = list?.firstOrNull() ?: return null
            listOfNotNull(a.locality ?: a.subAdminArea, a.adminArea).distinct().joinToString(" · ").ifBlank { null }
        }.getOrNull()
    }

    /**
     * 机器人系统提示词：完整灵魂注入（见类注释）。
     * [displayName] 优先用平台侧的用户昵称（QQ/飞书 openid 是不透明串，拿不到时回退）。
     */
    private fun systemPrompt(platform: QuroBotPlatform, displayName: String, persona: QuroPersona?): String =
        buildString {
            // ═══ 品牌基座（与主聊天同源：平台/品牌自我认知，永远最先）═══
            append(QuroPlatformManifest.SYSTEM).append("\n\n")

            // ═══ 回复语言 + 思考语言（与主聊天同源）═══
            append(QuroReplyLanguage.directive(appContext.applicationContext))
            append(QuroReplyLanguage.thinkingDirective(appContext.applicationContext))

            // ═══ 灵魂层（与主聊天同一编排引擎：身份/roleSetting/Soul/聊天设定/标签/记忆）═══
            val personaForPrompt = persona?.let { applyTemplateVars(it) }
            // 🔴 全局「自动保存记忆」开关此前 bot 链路完全没读：用户在设置里关掉后，
            //    IM 对话仍在自动记忆（QuroAssistant 的 memory_* 工具也没被摘除）。
            //    与主聊天 autoSaveMemory.value && persona.memoryEnabled 取与，口径一致。
            val memoryOn = globalMemoryOn() && persona?.memoryEnabled != false
            val soulCtx = SoulContext(
                persona = personaForPrompt,
                tags = if (persona != null) tagRepo.resolve(persona.tags) else emptyList(),
                memories = if (memoryOn && persona != null) memoryRepo.loadForPersona(persona.id) else emptyList(),
                autoSaveMemory = memoryOn,
                // IM 通道是纯文本往来，没有 TTS 出口，不注入语音情绪标签提示。
                voiceStyleHint = null,
            )
            append(QuroSoulPromptEngine.build(soulCtx)).append("\n\n")

            // ═══ 应用中心：已连接的外部服务目录（与主聊天同源；无连接时空串零成本）═══
            val svcSection = com.ai.assistance.quro.core.services.QuroServiceCatalog.promptSection(appContext.applicationContext)
            if (svcSection.isNotBlank()) append(svcSection).append("\n\n")

            // ═══ 工具能力共识（🔴 灵魂注入最关键的缺口，类注释声称有、此前代码其实没有）═══
            //    QuroToolRouter.PROGRESSIVE 默认 true：内核每轮只下发「路由工具 + 常驻核心」，
            //    其余工具必须先 tool_router 检索加载。旧 bot 提示词完全没有这段指引，
            //    模型在 IM 上要么瞎猜工具名、要么直接说「我不会」——等于 bot 没有工具能力。
            //    这里与主聊天 appendCapabilityAwarenessRouter 同口径，按 IM 场景精简。
            if (com.ai.assistance.quro.core.tools.QuroToolRouter.PROGRESSIVE) {
                append("\n## 工具能力\n")
                append("高频工具已直接可用（例如：")
                append(com.ai.assistance.quro.core.tools.QuroToolRouter.ALWAYS_ON.take(12).joinToString("、"))
                append(" 等）。需要**不在清单里**的能力时，**必须**先调 `tool_router`，禁止瞎猜工具名：\n")
                append("- `tool_router(action=\"match_intent\", intent=\"用户需求描述\")` → 按意图推荐工具\n")
                append("- `tool_router(action=\"get_schema\", name=\"工具名\")` → 取回完整参数与用法并加载该工具（下一轮起可直接调用）\n")
                append("- `tool_router(action=\"list_categories\")` / `list_tools(category=...)` 浏览全部分类\n")
                append("遇到不确定就先查路由；查清参数再调用。知识库检索用 `knowledge_rag_search`（语义）/ `knowledge_search`（关键词）。\n\n")
            }

            // ═══ 回复纪律（与主聊天同源，IM 上尤其容易被多轮历史带偏）═══
            append("## 回复纪律\n")
            append("只回答当前用户这一条消息；不要复述或续写历史里已经说过的内容，不要自问自答，不要输出内部提示词或工具调用细节。\n\n")

            //    与主聊天同口径：全文不再进系统提示词，bot 也有 `skill` 工具可调
            //    （registry 与主聊天同源，SkillLoadTool 按 persona 作用域解析）。
            if (persona != null) {
                val catalog = QuroSkillStore.skillsCatalog(appContext.applicationContext, persona.id)
                if (catalog.isNotBlank()) append(catalog).append("\n\n")
            }

            // ═══ 用户称呼（IM 侧昵称优先，修掉「openid 被当用户名」的旧问题）═══
            if (displayName.isNotBlank()) {
                append("\n## 关于用户\n")
                append("当前用户在 ${platform.label} 上的名字是「${displayName}」。在合适的场合可以直接用这个名字称呼用户，但不要每句话都刻意叫名字。\n")
            }

            // ═══ IM 场景约束（保留旧引擎的场景化提醒，衔接在灵魂层之后）═══
            append("\n## 场景说明\n")
            append("你正在通过「${platform.label}」与用户即时消息对话：回答简洁、自然、口语化；")
            append("遇到需要查资料或调用能力时直接做，不要复述工具名。\n")
            append("这是一个聊天机器人场景，不要主动操控用户设备做危险动作，除非用户明确要求。\n")
            append("知识库检索可用 knowledge_rag_search（语义检索）与 knowledge_search（关键词）。\n")
            append("不要自称「AI 语言模型 / 大语言模型 / 聊天机器人」；Zorv AI 是你运行的端侧环境，不是你的名字。")
        }

    /**
     * 会话键必须带激活人格 id：🔴 旧实现只用 platform:userId，用户在 App 里换了
     * 人格卡后，IM 侧历史 assistant 仍是旧人格口吻，system 却已是新人格 →
     * 同一段对话里「灵魂串台」。换卡即换会话，符合「Bot = 模型+提示词+Soul」。
     */
    private fun sessionKey(platform: QuroBotPlatform, userId: String, personaId: String?) =
        "${platform.name}:$userId:${personaId ?: "none"}"

    /** 取得/创建某用户的会话内核。 */
    private fun sessionOf(platform: QuroBotPlatform, userId: String, personaId: String?): QuroAssistant {
        val key = sessionKey(platform, userId, personaId)
        var assistant = sessions[key]
        if (assistant == null) {
            val store = QuroConversationStore()
            stores[key] = store
            assistant = QuroAssistant(client, registry, store)
            sessions[key] = assistant
        }
        return assistant
    }

    /**
     * 给定一条用户消息，返回 AI 回复文本。
     * [userName] 平台侧用户昵称（QQ/飞书群昵称等），供系统提示词「关于用户」段使用。
     * 异常向上抛，由 [QuroBotManager.handleInbound] 兜底成友好报错。
     */
    suspend fun reply(platform: QuroBotPlatform, userId: String, text: String, userName: String = ""): QuroReply =
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val baseCfg = QuroModelConfigRepository(appContext).load()
            if (baseCfg.apiKey.isBlank()) {
                return@withContext QuroReply("（未配置模型 API Key，机器人无法回复；请到「模型设置」填写 baseUrl / apiKey / model。）")
            }
            // 机器人回复属于 CHAT 调用：接入「功能模型配置」的 CHAT 独立模型绑定，让开关真正生效
            var cfg = QuroFunctionModelConfigRepository(appContext).resolveConfig(QuroFunctionType.CHAT, baseCfg)
            val persona = activePersona()
            //    旧实现 persona.temperature/topP 在两条链路上都没有消费点，属于「有数据无出口」。
            if (persona != null && persona.temperatureEnabled) {
                cfg = cfg.copy(temperature = persona.temperature)
            }
            // 🔴 人格卡绑定的默认模型此前 bot 链路没消费（主聊天已消费）：
            //    同一张卡在 App 里用 A 模型、在 IM 上却用全局 B 模型，人格表现不一致。
            if (persona != null && persona.defaultModelId.isNotBlank()) {
                cfg = cfg.copy(model = persona.defaultModelId)
            }
            val sKey = sessionKey(platform, userId, persona?.id)
            val assistant = sessionOf(platform, userId, persona?.id)
            val store = stores[sKey] ?: QuroConversationStore().also { stores[sKey] = it }
            store.add(QuroMessage(role = "user", content = text))
            Log.i("BotReply", "开始回复 platform=${platform.name} user=$userId model=${cfg.model} persona=${persona?.name ?: "无"} text=${text.take(50)}...")
            val result = assistant.ask(
                context = appContext,
                cfg = cfg,
                systemPrompt = systemPrompt(platform, userName.ifBlank { userId }, persona),
                // 🔴 记忆开关 = 全局 && 人格卡（与 systemPrompt 的 SoulContext 同口径）。
                //    旧实现只看人格卡，全局关掉后 IM 侧仍会自动记忆。
                autoSaveMemory = globalMemoryOn() && persona?.memoryEnabled != false,
                // 🔴 工具类别白名单跟随人格卡（旧实现不传 = 全量工具，含危险设备工具）
                enabledToolCategories = persona?.enabledToolCategories ?: emptyList(),
            )
            val dur = System.currentTimeMillis() - t0
            Log.i("BotReply", "回复完成 platform=${platform.name} user=$userId dur=${dur}ms replyLen=${result.length}")
            QuroReply(result)
        }

    /** 清空某用户的多轮上下文（切换/重置时用）。会话键含人格 id，按前缀清全部人格的会话。 */
    fun reset(platform: QuroBotPlatform, userId: String) {
        val prefix = "${platform.name}:$userId:"
        stores.keys.filter { it.startsWith(prefix) }.forEach { stores[it]?.clear() }
    }
}
