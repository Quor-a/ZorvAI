package com.ai.assistance.quro.core.bot

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.core.QuroReplyNotifier
import com.ai.assistance.quro.core.bot.adapters.QuroFeishuBotAdapter
import com.ai.assistance.quro.core.bot.adapters.QuroLocalBotAdapter
import com.ai.assistance.quro.core.bot.adapters.QuroQqBotAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * Quro 多平台机器人接入框架（C2 重做版 · 直连官方网关）。
 *
 * 设计要点（与现有架构的复用关系）：
 *  - 收到平台消息 → [QuroBotReplyEngine] 复用 [com.ai.assistance.quro.core.QuroAssistant] +
 *    [com.ai.assistance.quro.core.network.QuroLlmClient] + [com.ai.assistance.quro.core.tools.buildQuroRegistry]
 *    在独立会话里跑 ReAct 循环，得到回复文本（不触碰 UI 层）。
 *  - 回复经对应 [QuroBotAdapter.deliver] 回传平台。
 *  - 三家平台均支持「手机端零公网端点」收消息（已核实）：QQBot / 飞书走官方 WebSocket 长连，
 *    App 持密钥【出站】直连官方网关，无需任何自备服务器 / Webhook。
 *  - [QuroLocalBotAdapter] 保留为纯 App 内链路，用于免凭据端到端联调。
 *
 * 接入位置：
 *  - App 启动：activity/QuroApplication.onCreate 调 QuroBotManager.instance(app).registerDefaults(app) 并 startEnabled(app)。
 *  - UI 入口：ui/ChatScreen.kt 的 showBots + QuroBotSettingsScreen(onClose=...)。
 */

/** 支持的平台。 */
enum class QuroBotPlatform(val label: String) {
    LOCAL("本地测试"),
    QQ("QQ 机器人"),
    FEISHU("飞书机器人"),
}

/** 平台 → Quro 的入站消息。 */
data class QuroInboundMessage(
    val platform: QuroBotPlatform,
    val userId: String,
    val userName: String,
    val text: String,
    /** 平台原始事件体（JSON 字符串 / Map 等），中继层透传，供 adapter 做验签/解密。 */
    val raw: Any? = null,
    /** QQ 被动回复所需的原始消息 ID（不带则网关拒收回复）。 */
    val msgId: String? = null,
    /** QQ 被动回复所需的事件 ID。 */
    val eventId: String? = null,
    /** QQ 群消息的目标群 ID（GROUP_AT_MESSAGE_CREATE 时非空，回复走 /v2/groups/ 端点）。 */
    val groupId: String? = null,
)

/**
 * 机器人消息与 App 会话的绑定器。
 * 由 [QuroChatViewModel] 实现并注册，负责把机器人对话写入持久化会话。
 */
fun interface BotConversationBinder {
    /**
     * @param mode "none" | "auto" | "fixed"
     * @param fixedConvId mode="fixed" 时指定的目标会话 ID
     */
    fun append(
        platform: QuroBotPlatform,
        userId: String,
        userName: String,
        userText: String,
        replyText: String,
        mode: String,
        fixedConvId: String?,
    )
}

/**
 * Quro → 平台的出站回复。
 * imageBytes/imageFileName 非空时，支持图片的平台（飞书）优先发图，文字作为附言补发。
 */
@Suppress("ArrayInDataClass")
data class QuroOutboundMessage(
    val platform: QuroBotPlatform,
    val userId: String,
    val text: String,
    /** 可选图片附件（字节）；非空时飞书等平台优先发图。 */
    val imageBytes: ByteArray? = null,
    val imageFileName: String? = null,
    /** QQ 被动回复所需的原始消息 ID（透传自入站消息）。 */
    val msgId: String? = null,
    /** QQ 被动回复所需的事件 ID。 */
    val eventId: String? = null,
    /** QQ 群消息的目标群 ID（透传自入站消息）。 */
    val groupId: String? = null,
)

/**
 * 平台适配器契约。
 * 每个平台实现一个 adapter：负责配置校验、连接生命周期、把回复投递回平台。
 * 入站消息统一由 [QuroBotManager.handleInbound] 触发（中继层或本地测试调用），
 * 不要求 adapter 自己拉取。
 */
interface QuroBotAdapter {
    val platform: QuroBotPlatform
    /** 凭据/中继是否已配置（决定是否可 start）。 */
    fun isConfigured(): Boolean
    /** 启动连接（如连接中继 WebSocket、注册轮询）。Phase 1 仅本地适配器真正跑通。 */
    suspend fun start()
    /** 停止连接、释放资源。 */
    suspend fun stop()
    /** 把 AI 回复投递回平台（QQBot/飞书/企业微信经 RelayClient 发往后端）。 */
    suspend fun deliver(reply: QuroOutboundMessage)
    /** 当前是否真实连接（WS 已握手 / 长轮询已启动），供 UI 读取。默认返回 false，子类覆写。 */
    val isConnected: Boolean get() = false
    /** 最近一次连接/投递失败的可读原因，供 UI 直接展示（无需翻 logcat）。默认 null。 */
    val lastError: String? get() = null
}

/**
 * 机器人总控。
 * 持有各平台 adapter，统一接收入站消息、驱动回复引擎、分发回复。
 */
class QuroBotManager(
    private val appContext: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val replyEngine = QuroBotReplyEngine(appContext.applicationContext)
    private val adapters = ConcurrentHashMap<QuroBotPlatform, QuroBotAdapter>()

    /** UI 镜像回调：把每条 bot 回复同时推给前台（可选，如写入主对话）。 */
    var uiMirror: ((QuroOutboundMessage) -> Unit)? = null

    /** 会话绑定器：把机器人对话写入 App 持久化会话。 */
    var conversationBinder: BotConversationBinder? = null

    /** 读取某平台的会话绑定模式：none / auto / fixed。 */
    fun bindMode(platform: QuroBotPlatform): String {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("bind_mode_${platform.name}", "auto")?.lowercase() ?: "auto"
    }

    /** 读取 fixed 模式下的目标会话 ID。 */
    fun bindConvId(platform: QuroBotPlatform): String? {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("bind_conv_${platform.name}", null)
    }

    fun registerAdapter(adapter: QuroBotAdapter) {
        adapters[adapter.platform] = adapter
    }

    fun getAdapter(platform: QuroBotPlatform): QuroBotAdapter? = adapters[platform]

    fun registeredPlatforms(): List<QuroBotPlatform> = adapters.keys.toList()

    /** 注册默认适配器集合（本地 + QQ + 飞书）。 */
    fun registerDefaults(ctx: Context) {
        if (adapters.isEmpty()) {
            registerAdapter(QuroLocalBotAdapter())
            registerAdapter(QuroQqBotAdapter(ctx.applicationContext))
            registerAdapter(QuroFeishuBotAdapter(ctx.applicationContext))
        }
    }

    /** 启动所有「已启用且已配置」的平台。在 Application.onCreate 调用一次。 */
    fun startEnabled(ctx: Context) {
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        adapters.values.forEach { a ->
            val enabled = prefs.getBoolean("enabled_${a.platform.name}", a.platform == QuroBotPlatform.LOCAL)
            if (enabled && a.isConfigured()) {
                scope.launch { runCatching { a.start() } }
            }
        }
    }

    /**
     * 统一收消息入口（中继层 / 本地测试 / 系统广播都会走到这里）。
     * 内部切到 IO 协程跑回复引擎，拿到文本后调 adapter.deliver 回传平台，并触发 uiMirror。
     */
    fun handleInbound(message: QuroInboundMessage) {
        scope.launch {
            try {
                // 系统级弹窗：IM 入站消息（离开软件时 heads-up）
                QuroReplyNotifier.notifyImMessage(
                    appContext,
                    "${message.platform.label}·${message.userName.ifBlank { message.userId }}",
                    message.text,
                    id = QuroReplyNotifier.NOTIF_IM_INBOUND,
                )

                // 驱动回复引擎；硬超时 90s 防止 assistant.ask 卡死导致飞书侧「永无回复/无错误」。
                val reply = try {
                    withTimeout(90_000) {
                        replyEngine.reply(message.platform, message.userId, message.text)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "replyEngine.reply 超时/失败 platform=${message.platform} user=${message.userId}: ${e.message}")
                    QuroReply("（机器人回复超时或失败：${e.message ?: "未知错误"}）")
                }
                val out = QuroOutboundMessage(
                    message.platform,
                    message.userId,
                    reply.text,
                    imageBytes = reply.imageBytes,
                    imageFileName = reply.imageFileName,
                    msgId = message.msgId,
                    eventId = message.eventId,
                    groupId = message.groupId,
                )

                // 按平台配置，把用户消息 + 机器人回复写入 App 持久化会话
                val mode = bindMode(message.platform)
                if (mode != "none") {
                    runCatching {
                        conversationBinder?.append(
                            platform = message.platform,
                            userId = message.userId,
                            userName = message.userName.ifBlank { message.userId },
                            userText = message.text,
                            replyText = reply.text,
                            mode = mode,
                            fixedConvId = bindConvId(message.platform),
                        )
                    }
                }

                runCatching { adapters[message.platform]?.deliver(out) }
                // 系统级弹窗：机器人回复（离开软件时 heads-up；前台时用户在对话里直接看到）
                runCatching {
                    QuroReplyNotifier.notifyImMessage(
                        appContext,
                        "Zorv · ${message.platform.label}",
                        reply.text,
                        id = QuroReplyNotifier.NOTIF_IM_REPLY,
                    )
                }
                uiMirror?.invoke(out)
            } catch (e: Exception) {
                Log.e(TAG, "handleInbound failed platform=${message.platform} user=${message.userId}: ${e.message}")
                val err = QuroOutboundMessage(
                    message.platform,
                    message.userId,
                    "（机器人暂时无法回复：${e.message}）",
                    msgId = message.msgId,
                    eventId = message.eventId,
                    groupId = message.groupId,
                )
                runCatching { adapters[message.platform]?.deliver(err) }
            }
        }
    }

    /** 供 UI 设置页直接发送一条「本地测试消息」，验证端到端链路。 */
    fun sendLocalTest(text: String, userName: String = "本地测试用户") {
        handleInbound(QuroInboundMessage(QuroBotPlatform.LOCAL, LOCAL_DEV_USER, userName, text))
    }

    companion object {
        const val PREFS = "quro_bots"
        const val TAG = "QuroBot"
        const val LOCAL_DEV_USER = "local-dev"

        @Volatile
        private var _instance: QuroBotManager? = null

        /** 进程内单例。首次调用时注册默认适配器（不自动 start，避免无凭据时连后端）。 */
        fun instance(appContext: Context): QuroBotManager =
            _instance ?: synchronized(this) {
                _instance ?: QuroBotManager(appContext.applicationContext).also {
                    it.registerDefaults(appContext.applicationContext)
                    _instance = it
                }
            }
    }
}
