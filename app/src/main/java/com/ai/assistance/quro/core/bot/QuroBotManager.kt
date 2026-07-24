package com.ai.assistance.quro.core.bot

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.core.bot.adapters.QuroFeishuBotAdapter
import com.ai.assistance.quro.core.bot.adapters.QuroLocalBotAdapter
import com.ai.assistance.quro.core.bot.adapters.QuroQqBotAdapter
import com.ai.assistance.quro.core.bot.adapters.QuroWechatIlinkBotAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Quro 多平台机器人接入框架（C2 重做版 · 直连官方网关）。
 *
 * 设计要点（与现有架构的复用关系）：
 *  - 收到平台消息 → [QuroBotReplyEngine] 复用 [com.ai.assistance.quro.core.QuroAssistant] +
 *    [com.ai.assistance.quro.core.network.QuroLlmClient] + [com.ai.assistance.quro.core.tools.buildQuroRegistry]
 *    在独立会话里跑 ReAct 循环，得到回复文本（不触碰 UI 层）。
 *  - 回复经对应 [QuroBotAdapter.deliver] 回传平台。
 *  - 三家平台均支持「手机端零公网端点」收消息（元宝核实）：QQBot / 飞书走官方 WebSocket 长连，
 *    微信 iLink 走 HTTP 长轮询（35s）；App 持密钥【出站】直连官方网关，无需任何自备服务器 / Webhook。
 *  - [QuroLocalBotAdapter] 保留为纯 App 内链路，用于免凭据端到端联调。
 *
 * 微信 Bot 走 iLink 个人号通道（非企业微信）——企业微信需公网回调，不符合「零端点」原则。
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
    WECHAT("微信 iLink 机器人"),
}

/** 平台 → Quro 的入站消息。 */
data class QuroInboundMessage(
    val platform: QuroBotPlatform,
    val userId: String,
    val userName: String,
    val text: String,
    /** 平台原始事件体（JSON 字符串 / Map 等），中继层透传，供 adapter 做验签/解密。 */
    val raw: Any? = null,
)

/** Quro → 平台的出站回复。 */
data class QuroOutboundMessage(
    val platform: QuroBotPlatform,
    val userId: String,
    val text: String,
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

    fun registerAdapter(adapter: QuroBotAdapter) {
        adapters[adapter.platform] = adapter
    }

    fun getAdapter(platform: QuroBotPlatform): QuroBotAdapter? = adapters[platform]

    fun registeredPlatforms(): List<QuroBotPlatform> = adapters.keys.toList()

    /** 注册默认适配器集合（本地 + QQ + 飞书 + 微信 iLink）。 */
    fun registerDefaults(ctx: Context) {
        if (adapters.isEmpty()) {
            registerAdapter(QuroLocalBotAdapter())
            registerAdapter(QuroQqBotAdapter(ctx.applicationContext))
            registerAdapter(QuroFeishuBotAdapter(ctx.applicationContext))
            registerAdapter(QuroWechatIlinkBotAdapter(ctx.applicationContext))
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
                val reply = replyEngine.reply(message.platform, message.userId, message.text)
                val out = QuroOutboundMessage(message.platform, message.userId, reply)
                adapters[message.platform]?.deliver(out)
                uiMirror?.invoke(out)
            } catch (e: Exception) {
                Log.e(TAG, "handleInbound failed platform=${message.platform} user=${message.userId}: ${e.message}")
                val err = QuroOutboundMessage(message.platform, message.userId, "（机器人暂时无法回复：${e.message}）")
                adapters[message.platform]?.let { runCatching { it.deliver(err) } }
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
