package com.ai.assistance.quro.core.xposed

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.ai.assistance.quro.core.assistant.QuroAgentBridgeProtocol
import org.json.JSONObject

/**
 * Hook 进程侧的 Agent Bridge 客户端（净室实现）。
 *
 * 运行位置：system_server / SystemUI / Google App / 小布 / 超级小爱等**别人的进程**。
 * 因此这里只依赖 Android 框架类与 [QuroAgentBridgeProtocol] 的纯常量，
 * 不触碰 Application 单例、资源 ID 或任何 app 侧运行时。
 *
 * 所有方法一律「失败返回结构化 JSON，绝不抛异常到宿主进程」：
 * 框架服务没连、app 没安装、binder 挂了，都只让这次接管降级回厂商原生逻辑。
 */
object QuroAgentBridgeClient {

    private val URI: Uri = Uri.parse(QuroAgentBridgeProtocol.CONTENT_URI)

    private fun baseExtras(): Bundle = Bundle().apply {
        putInt(QuroAgentBridgeProtocol.EXTRA_VERSION, QuroAgentBridgeProtocol.VERSION)
    }

    private fun call(
        resolver: ContentResolver?,
        method: String,
        extras: Bundle,
    ): JSONObject {
        if (resolver == null) return failure("当前进程没有可用的 ContentResolver")
        return runCatching {
            val reply = resolver.call(URI, method, null, extras)
            val json = reply?.getString(QuroAgentBridgeProtocol.EXTRA_RESULT_JSON)
            if (json.isNullOrBlank()) failure("框架未返回 Agent Bridge 结果")
            else JSONObject(json)
        }.getOrElse { t -> failure("Agent Bridge 调用失败：${t.javaClass.simpleName}") }
    }

    /** 全能力清单：工具 / Skills / MCP / 记忆 / 运行时配置 / 四通道 / 厂商能力映射。 */
    fun capabilities(resolver: ContentResolver?): JSONObject =
        call(resolver, QuroAgentBridgeProtocol.METHOD_CAPABILITIES, baseExtras())

    /** 轻量运行时帧：助手跑在哪、当前模型、工具数、四通道可用性。 */
    fun runtime(resolver: ContentResolver?, source: String): JSONObject =
        call(resolver, QuroAgentBridgeProtocol.METHOD_RUNTIME, baseExtras().apply {
            putString(QuroAgentBridgeProtocol.EXTRA_SOURCE, QuroAgentBridgeProtocol.normalizeSource(source))
        })

    /**
     * 跑一次完整 Agent 回合。
     *
     * @param hiddenContext 入口内部上下文（屏幕观察 / 厂商卡片 / 触发原因），只给模型判断用。
     * @param imageRefs     图片引用列表（content:// / file:// / data:image / http(s)://），
     *                      由运行时解码为多模态消息；为空则纯文本回合。
     */
    fun runTurn(
        resolver: ContentResolver?,
        source: String,
        text: String,
        hiddenContext: String = "",
        personaId: String = "",
        requestId: String = "",
        timeoutMs: Long = QuroAgentBridgeProtocol.DEFAULT_TIMEOUT_MS,
        lite: Boolean = false,
        imageRefs: List<String> = emptyList(),
        /** 声明本次回合需要 app 侧产出流式事件；requestId 同时是事件流的 key。 */
        streamEvents: Boolean = false,
    ): JSONObject = call(resolver, QuroAgentBridgeProtocol.METHOD_RUN, baseExtras().apply {
        putString(QuroAgentBridgeProtocol.EXTRA_SOURCE, QuroAgentBridgeProtocol.normalizeSource(source))
        putString(QuroAgentBridgeProtocol.EXTRA_TEXT, text)
        putString(QuroAgentBridgeProtocol.EXTRA_CONTEXT, hiddenContext)
        putStringArrayList(
            QuroAgentBridgeProtocol.EXTRA_IMAGES,
            ArrayList(imageRefs.filter { it.isNotBlank() }),
        )
        putString(QuroAgentBridgeProtocol.EXTRA_PERSONA_ID, personaId)
        putString(QuroAgentBridgeProtocol.EXTRA_REQUEST_ID, requestId)
        putLong(QuroAgentBridgeProtocol.EXTRA_TIMEOUT_MS, QuroAgentBridgeProtocol.sanitizeTimeout(timeoutMs))
        putBoolean(QuroAgentBridgeProtocol.EXTRA_LITE, lite)
        putBoolean(QuroAgentBridgeProtocol.EXTRA_STREAM_EVENTS, streamEvents)
    })

    /**
     * 拉取 [requestId] 回合在 [afterSeq] 之后产生的事件。
     *
     * 返回 JSON：{ ok, events_json: "[...]", events_last_seq, events_done }。
     * 失败一律返回 `ok=false`，调用方据此降级为「一次性全文」，绝不让厂商助手界面空白。
     */
    fun pollEvents(
        resolver: ContentResolver?,
        requestId: String,
        afterSeq: Long,
    ): JSONObject = call(resolver, QuroAgentBridgeProtocol.METHOD_EVENTS, baseExtras().apply {
        putString(QuroAgentBridgeProtocol.EXTRA_REQUEST_ID, requestId)
        putLong(QuroAgentBridgeProtocol.EXTRA_EVENTS_AFTER_SEQ, afterSeq)
    })

    /** 便捷重载：能拿到 Context 的 Hook 路径直接用。 */
    fun runTurn(
        ctx: Context?,
        source: String,
        text: String,
        hiddenContext: String = "",
        lite: Boolean = false,
        imageRefs: List<String> = emptyList(),
    ): JSONObject = runTurn(
        resolver = ctx?.contentResolver,
        source = source,
        text = text,
        hiddenContext = hiddenContext,
        lite = lite,
        imageRefs = imageRefs,
    )

    /**
     * 把一次接管结果写回 app 侧收件箱。
     * 这是「可观测」而不是装饰：Hook 进程不写主聊天库，失败也能留下原因。
     */
    fun deliver(
        resolver: ContentResolver?,
        source: String,
        request: String,
        result: JSONObject,
    ): JSONObject = call(resolver, QuroAgentBridgeProtocol.METHOD_DELIVER, baseExtras().apply {
        putString(QuroAgentBridgeProtocol.EXTRA_SOURCE, QuroAgentBridgeProtocol.normalizeSource(source))
        putString(QuroAgentBridgeProtocol.EXTRA_TEXT, request)
        putString(QuroAgentBridgeProtocol.EXTRA_REPLY, textOf(result))
        putBoolean(QuroAgentBridgeProtocol.EXTRA_OK, isOk(result))
        putString(QuroAgentBridgeProtocol.EXTRA_ERROR, errorOf(result))
        putLong(
            QuroAgentBridgeProtocol.EXTRA_ELAPSED_MS,
            result.optLong(QuroAgentBridgeProtocol.RESULT_ELAPSED_MS, 0L),
        )
    })

    fun inbox(resolver: ContentResolver?, limit: Int = 10): JSONObject = call(
        resolver,
        QuroAgentBridgeProtocol.METHOD_INBOX,
        baseExtras().apply { putInt(QuroAgentBridgeProtocol.EXTRA_LIMIT, limit) },
    )

    /**
     * 一次完整的「厂商助手入口接管」：判开关 → 跑完整 Agent 回合 → 记录结果。
     *
     * @param imageRefs 图片引用列表（小布/小爱入口附带的图片），由运行时解码为多模态消息；
     *                  为空则纯文本回合。
     * @param prefixHandled Hook 侧已自行判定并剥离前缀。厂商助手在拦截点必须先判前缀
     *                      才能决定是否吃掉原生请求，为 true 时本方法不再二次判定，
     *                      否则剥后文本必被判为「无前缀」→ 固定返回 null → 界面出现失败卡。
     * @return 回合结果 JSON；null 表示本次不接管，调用方应放行厂商原生链路。
     */
    fun takeover(
        ctx: Context?,
        source: String,
        rawText: String,
        hiddenContext: String = "",
        imageRefs: List<String> = emptyList(),
        prefixHandled: Boolean = false,
    ): JSONObject? {
        val resolver = ctx?.contentResolver ?: return null
        if (!QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.VENDOR_ASSISTANT_TAKEOVER)) return null
        val requirePrefix = !prefixHandled &&
            QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.VENDOR_REQUIRE_PREFIX)
        if (requirePrefix && !QuroAgentBridgeProtocol.hasTakeoverPrefix(rawText)) return null
        val text = if (requirePrefix) QuroAgentBridgeProtocol.stripTakeoverPrefix(rawText) else rawText
        if (text.isBlank() && imageRefs.isEmpty()) return null
        val lite = !QuroModulePrefs.isEnabled(QuroModuleConfig.Keys.VENDOR_CUSTOM_MODEL)
        val result = runTurn(
            resolver = resolver,
            source = source,
            text = text,
            hiddenContext = hiddenContext,
            lite = lite,
            imageRefs = imageRefs,
        )
        runCatching { deliver(resolver, source, text, result) }
        return result
    }

    fun isOk(result: JSONObject): Boolean = result.optBoolean(QuroAgentBridgeProtocol.RESULT_OK, false)

    fun textOf(result: JSONObject): String = result.optString(QuroAgentBridgeProtocol.RESULT_TEXT, "")

    fun errorOf(result: JSONObject): String = result.optString(QuroAgentBridgeProtocol.RESULT_ERROR, "")

    private fun failure(message: String): JSONObject = JSONObject().apply {
        put(QuroAgentBridgeProtocol.RESULT_OK, false)
        put(QuroAgentBridgeProtocol.RESULT_TEXT, "")
        put(QuroAgentBridgeProtocol.RESULT_ERROR, message)
    }
}
