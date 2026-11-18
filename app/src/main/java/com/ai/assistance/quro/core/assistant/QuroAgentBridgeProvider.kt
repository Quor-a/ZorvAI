package com.ai.assistance.quro.core.assistant

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 「厂商助手 → Zorv AI 完整运行时」的服务端入口（app 主进程）。
 *
 * 由 LSPosed Hook 住的进程通过 `ContentResolver.call(content://com.ai.assistance.quro.agent, ...)`
 * 发起三种请求：能力清单 / 运行时帧 / 一次完整 Agent 回合。
 *
 * 安全边界（不声明 android:permission 的理由见 [QuroAgentBridgeProtocol]）：
 *  · 用 Binder.getCallingUid() 反查包名，只放行 system_server / shell / 声明作用域里的进程；
 *  · 校验协议版本，字段语义漂移时直接拒绝；
 *  · 回合有硬超时上限，避免 Hook 进程被无限阻塞；
 *  · 任何异常都转成结构化错误返回，绝不把异常抛进别人的进程。
 */
class QuroAgentBridgeProvider : ContentProvider() {

    companion object {
        private const val TAG = "QuroAgentBridge"
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val app = context ?: return errorBundle("Provider 尚未附加 Context")
        val e = extras ?: Bundle()
        val version = e.getInt(QuroAgentBridgeProtocol.EXTRA_VERSION, QuroAgentBridgeProtocol.VERSION)
        if (version != QuroAgentBridgeProtocol.VERSION) {
            return errorBundle("协议版本不匹配：caller=$version, provider=${QuroAgentBridgeProtocol.VERSION}")
        }

        val uid = Binder.getCallingUid()
        val callerPackages = runCatching { app.packageManager.getPackagesForUid(uid) }.getOrNull()
        if (!QuroAgentBridgeProtocol.isCallerAllowed(uid, callerPackages)) {
            Log.w(TAG, "拒绝未授权调用 uid=$uid packages=${callerPackages?.joinToString()}")
            return errorBundle("调用方未授权（uid=$uid）")
        }

        return runCatching {
            when (method) {
                QuroAgentBridgeProtocol.METHOD_CAPABILITIES ->
                    okBundle(QuroAssistantCapabilities.snapshot(app).toString())

                QuroAgentBridgeProtocol.METHOD_RUNTIME ->
                    okBundle(
                        QuroAssistantCapabilities.runtimeFrame(
                            app,
                            e.getString(QuroAgentBridgeProtocol.EXTRA_SOURCE)
                                ?: QuroAgentBridgeProtocol.SOURCE_UNKNOWN,
                        ).toString()
                    )

                QuroAgentBridgeProtocol.METHOD_RUN -> runTurn(app, e)

                QuroAgentBridgeProtocol.METHOD_DELIVER -> deliver(app, e)

                QuroAgentBridgeProtocol.METHOD_INBOX -> inbox(app, e)

                QuroAgentBridgeProtocol.METHOD_EVENTS -> events(e)

                else -> errorBundle("未知 method：$method")
            }
        }.getOrElse { t ->
            Log.w(TAG, "处理 $method 失败：${t.javaClass.simpleName}")
            errorBundle("处理失败：${t.javaClass.simpleName}")
        }
    }

    private fun runTurn(app: Context, e: Bundle): Bundle {
        val request = QuroAgentTurnRunner.Request(
            source = e.getString(QuroAgentBridgeProtocol.EXTRA_SOURCE)
                ?: QuroAgentBridgeProtocol.SOURCE_UNKNOWN,
            text = e.getString(QuroAgentBridgeProtocol.EXTRA_TEXT).orEmpty(),
            context = e.getString(QuroAgentBridgeProtocol.EXTRA_CONTEXT).orEmpty(),
            imageRefs = e.getStringArrayList(QuroAgentBridgeProtocol.EXTRA_IMAGES).orEmpty(),
            personaId = e.getString(QuroAgentBridgeProtocol.EXTRA_PERSONA_ID).orEmpty(),
            timeoutMs = e.getLong(
                QuroAgentBridgeProtocol.EXTRA_TIMEOUT_MS,
                QuroAgentBridgeProtocol.DEFAULT_TIMEOUT_MS,
            ),
            requestId = e.getString(QuroAgentBridgeProtocol.EXTRA_REQUEST_ID).orEmpty(),
            lite = e.getBoolean(QuroAgentBridgeProtocol.EXTRA_LITE, false),
            streamEvents = e.getBoolean(QuroAgentBridgeProtocol.EXTRA_STREAM_EVENTS, false),
        )
        // call() 跑在 binder 线程，允许阻塞；回合本身在 QuroAgentTurnRunner 内切到 IO 并有硬超时。
        // 事件流为 true 时，Hook 侧会在另一个线程并行轮询 METHOD_EVENTS，
        // 这里必须在任何出口（正常 / 异常 / 超时）都把流标记成 done，否则渲染器会一直空转。
        val hasStream = request.streamEvents && request.requestId.isNotBlank()
        if (hasStream) QuroAgentTurnEvents.start(request.requestId)
        var succeeded = false
        val result = try {
            runBlocking(Dispatchers.IO) { QuroAgentTurnRunner.run(app, request) }
                .also { succeeded = it.ok }
        } finally {
            if (hasStream) QuroAgentTurnEvents.finish(request.requestId, succeeded)
        }
        return okBundle(result.toJson().put(
            QuroAgentBridgeProtocol.RESULT_REQUEST_ID,
            request.requestId,
        ).toString())
    }

    /** 增量拉取回合内事件；仅同一 requestId 的 Hook 侧可读取，不落盘、不改会话状态。 */
    private fun events(e: Bundle): Bundle {
        val runId = e.getString(QuroAgentBridgeProtocol.EXTRA_REQUEST_ID).orEmpty()
        val afterSeq = e.getLong(QuroAgentBridgeProtocol.EXTRA_EVENTS_AFTER_SEQ, 0L)
        if (runId.isBlank()) return errorBundle("缺少 requestId")
        return okBundle(QuroAgentTurnEvents.poll(runId, afterSeq).toString())
    }

    /** Hook 侧把一次接管的结果写回，供设置页回溯。 */
    private fun deliver(app: Context, e: Bundle): Bundle {
        val source = QuroAgentBridgeProtocol.normalizeSource(
            e.getString(QuroAgentBridgeProtocol.EXTRA_SOURCE) ?: QuroAgentBridgeProtocol.SOURCE_UNKNOWN,
        )
        QuroAgentBridgeInbox.append(
            app,
            QuroAgentBridgeInbox.Record(
                source = source,
                request = e.getString(QuroAgentBridgeProtocol.EXTRA_TEXT).orEmpty().take(2_000),
                reply = e.getString(QuroAgentBridgeProtocol.EXTRA_REPLY).orEmpty().take(8_000),
                ok = e.getBoolean(QuroAgentBridgeProtocol.EXTRA_OK, false),
                error = e.getString(QuroAgentBridgeProtocol.EXTRA_ERROR).orEmpty().take(500),
                elapsedMs = e.getLong(QuroAgentBridgeProtocol.EXTRA_ELAPSED_MS, 0L),
                ts = System.currentTimeMillis(),
            ),
        )
        return okBundle(JSONObject().apply { put(QuroAgentBridgeProtocol.RESULT_OK, true) }.toString())
    }

    private fun inbox(app: Context, e: Bundle): Bundle {
        val limit = e.getInt(QuroAgentBridgeProtocol.EXTRA_LIMIT, 10)
        val records = org.json.JSONArray()
        QuroAgentBridgeInbox.recent(app, limit).forEach { records.put(it.toJson()) }
        return okBundle(JSONObject().apply {
            put(QuroAgentBridgeProtocol.RESULT_OK, true)
            put(QuroAgentBridgeProtocol.EXTRA_RECORDS, records)
        }.toString())
    }

    private fun okBundle(json: String): Bundle = Bundle().apply {
        putString(QuroAgentBridgeProtocol.EXTRA_RESULT_JSON, json)
    }

    private fun errorBundle(message: String): Bundle = Bundle().apply {
        putString(
            QuroAgentBridgeProtocol.EXTRA_RESULT_JSON,
            JSONObject().apply {
                put(QuroAgentBridgeProtocol.RESULT_OK, false)
                put(QuroAgentBridgeProtocol.RESULT_TEXT, "")
                put(QuroAgentBridgeProtocol.RESULT_ERROR, message)
            }.toString(),
        )
    }

    // ---- ContentProvider 其余抽象方法：本 Provider 只用 call()，不提供 CRUD ----

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0
}
