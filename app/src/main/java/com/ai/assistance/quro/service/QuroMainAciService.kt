package com.ai.assistance.quro.service

import ai.aidl.aci.core.AciIntentBridge
import ai.aidl.aci.core.AciProviderBridge
import ai.aidl.aci.core.AidlAciError
import ai.aidl.aci.core.AidlAciRequest
import ai.aidl.aci.core.AidlAciResponse
import ai.aidl.aci.core.BaseAidlAciService
import ai.aidl.aci.core.Capability
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.ai.assistance.quro.core.aidlaci.QuroAidlAciCredentialVault
import com.ai.assistance.quro.core.aidlaci.QuroAidlAciErrors
import com.ai.assistance.quro.core.aidlaci.QuroAidlAciProtocol
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 主应用 ACI 受控端 Service（新增 http_request 能力）。
 *
 * 主应用 QuroAI 原本只是 ACI 控制方（QuroAidlAciManager），本 Service 让主应用
 * 同时成为 ACI 受控端，对外暴露「HTTP 传输」能力：既能发出 HTTP 请求（传），
 * 也能接收响应（收）。AI 经 aci_call("com.ai.assistance.quro", "http_request", ...)
 * 即可让主应用代为发起任意 HTTP 请求。
 *
 * 设计要点（对齐浏览器受控端 QuroControlledAidlAciService）：
 * 1. super.onCreate() 包 try-catch —— 基类内部调 onCreateCapabilities，任何异常都会炸掉 Service
 * 2. onCall 在 Binder 线程被调用；HTTP 网络请求 offload 到后台线程 + CountDownLatch 阻塞
 *    等待（硬上限 14s，< 控制器 15s 超时），避免 NetworkOnMainThread 与控制器超时
 * 3. 响应体 >15万字符触发截断 + gzip 回退（绕开 AIDL ~1MB 事务限制），与浏览器 readHtml 一致
 * 4. onCheckPermission 白名单：自身（主应用）+ 受控浏览器包名，反向放行便于跨 App 互通
 *
 * 注：主应用无 DiagBuffer（仅浏览器模块有），诊断改用 android.util.Log。
 */
class QuroMainAciService : BaseAidlAciService() {

    companion object {
        private const val TAG = "QuroMainACI"
        /** 自身包名（控制方 QuroAI 同时作为受控端，允许自己调自己） */
        private const val SELF_PKG = "com.ai.assistance.quro"
        /** 允许的调用方：自身 + 受控浏览器（反向放行，便于浏览器经 ACI 反调主程序） */
        private const val BROWSER_PKG = "com.ai.assistance.quro.browser"
        /** 控制器 QuroAidlAciManager.callTimeoutMs = 15_000L；handler 硬上限留 1s 余量 */
        private const val HARD_TIMEOUT_S = 14L
    }

    override fun onCreate() {
        Log.d(TAG, "onCreate")
        try {
            super.onCreate()
        } catch (e: Throwable) {
            Log.e(TAG, "super.onCreate 崩溃: ${e.message}")
        }
    }

    override fun onCreateCapabilities(caps: MutableList<Capability>) {
        caps.add(
            Capability.create(
                "http_request",
                "HTTP 传输：发起 HTTP 请求并取回响应（既能发出请求也能接收响应）。" +
                    "支持自定义方法（GET/POST/PUT/DELETE/PATCH/HEAD 等）、请求头与请求体，" +
                    "返回状态码、响应头与响应体。可用于调用 Web API、抓取网页、对接第三方服务。"
            )
                .addParam("url", "string", true, "目标 URL")
                .addParam("method", "string", false, "HTTP 方法，默认 GET")
                .addParam("headers", "string", false, "请求头 JSON 对象，如 {\"Authorization\":\"Bearer x\"}。值可写 \"\$vault:NAME\" 引用已托管凭证（详见 aci_credentials）")
                .addParam("body", "string", false, "请求体（原样发送，字符串）")
                .addParam("tls_verify", "string", false, "是否校验 HTTPS 证书，默认 \"true\"；自签/LAN HTTPS 设 \"false\" 放行（仅限可信内网）")
                .addParam("tls_ca_pem", "string", false, "自定义 CA 的 PEM 文本，用于固定自签证书（优先级高于 tls_verify）")
                .addResult("status_code", "int", "HTTP 响应状态码")
                .addResult("response_headers", "string", "响应头 JSON 对象")
                .addResult("response_body", "string", "响应体（>15万字符截断，完整内容见 response_body_gz）")
                .addResult("truncated", "boolean", "响应体是否被截断")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )
        caps.add(
            Capability.create(
                "aci_protocol",
                "返回本端 ACI 协议版本信息（aci-protocol-v1），供控制端协商兼容版本。" +
                    "属于 ACI 2.0 协议版本化层，依托 aci-core 的 Capability 机制暴露。"
            )
                .addResult("protocol_version", "string", "当前协议标识，如 aci-protocol-v1")
                .addResult("semver", "string", "协议 SemVer，如 1.0.0")
                .addResult("supported", "string", "本端支持的全部协议版本（逗号分隔）")
                .addFlag(Capability.FLAG_NO_UI)
        )
        // Intent 发送代理：受控端在自己进程内代发 Intent（Activity / 广播 / 服务）。
        // 实现复用 aci-core 的通用桥，任何受控端均可一行接入。
        caps.add(AciIntentBridge.capability())
        // ContentProvider 访问代理：受控端代读/代写 content:// URI。
        caps.add(AciProviderBridge.capability())
    }

    override fun onCheckPermission(req: AidlAciRequest?, callerPkg: String?): Boolean {
        val ok = callerPkg == SELF_PKG || callerPkg == BROWSER_PKG
        Log.d(TAG, "onCheckPermission: caller=$callerPkg → ${if (ok) "放行" else "拒绝"}")
        return ok
    }

    override fun onCall(req: AidlAciRequest?): AidlAciResponse {
        if (req == null) return AidlAciResponse.error(AidlAciError.REQUEST_NULL, "null")
        return try {
            when (req.capability) {
                "http_request" -> handleHttpRequest(req.params)
                "aci_protocol" -> handleProtocol()
                AciIntentBridge.CAP_ID -> AciIntentBridge.handle(this, req.params)
                AciProviderBridge.CAP_ID -> AciProviderBridge.handle(this, req.params)
                else -> AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND, "unknown: ${req.capability}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onCall 异常: ${e.message}")
            aciError(QuroAidlAciErrors.E_INTERNAL, "onCall 异常", "受控端处理调用时抛异常：${e.message}。请查看被控端日志。", QuroAidlAciErrors.LAYER_BINDER)
        }
    }

    private fun handleHttpRequest(params: Bundle?): AidlAciResponse {
        val url = params?.getString("url") ?: ""
        if (url.isEmpty()) return aciError(QuroAidlAciErrors.E_BAD_REQUEST, "缺少 url 参数", "调用 http_request 必须传 url 参数。", QuroAidlAciErrors.LAYER_PROTOCOL)
        val method = (params?.getString("method") ?: "GET").uppercase()
        val headersStr = params?.getString("headers") ?: ""
        val body = params?.getString("body")  // 可能为 null
        // P0 治理：HTTPS 证书校验策略（自签 / LAN 可信内网放行）
        val tlsRaw = params?.getString("tls_verify") ?: "true"
        val tlsVerify = tlsRaw.equals("true", ignoreCase = true)
        val tlsCaPem = params?.getString("tls_ca_pem")  // 自定义 CA 固定

        val latch = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var result: AidlAciResponse? = null
        executor.execute {
            try {
                result = doHttp(method, url, headersStr, body, tlsVerify, tlsCaPem)
            } catch (e: Throwable) {
                result = aciHttpError(e.message)
            } finally {
                latch.countDown()
            }
        }
        val done = latch.await(HARD_TIMEOUT_S, TimeUnit.SECONDS)
        executor.shutdownNow()
        return if (done) {
            result ?: aciError(QuroAidlAciErrors.E_INTERNAL, "内部错误：无结果", "服务内部状态异常，请重试或重启目标 App。", QuroAidlAciErrors.LAYER_BINDER)
        } else {
            aciError(QuroAidlAciErrors.E_TIMEOUT, "http_request 超时", "目标未在 ${HARD_TIMEOUT_S}s 内响应（控制器上限 15s）。请确认目标可达、网络正常；超大响应建议改用分块/下载能力。", QuroAidlAciErrors.LAYER_HTTP)
        }
    }

    private fun doHttp(
        method: String,
        url: String,
        headersStr: String,
        body: String?,
        tlsVerify: Boolean,
        tlsCaPem: String?
    ): AidlAciResponse {
        val client = buildHttpClient(tlsVerify, tlsCaPem)
        val t0 = System.currentTimeMillis()

        val reqBuilder = Request.Builder().url(url)
        if (headersStr.isNotEmpty()) {
            try {
                val h = JSONObject(headersStr)
                val it = h.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val rawVal = h.optString(k)
                    // P0 治理：凭证托管 —— "$vault:NAME" 解析为已加密托管的真实凭证
                    val resolved = QuroAidlAciCredentialVault.resolve(this, rawVal) ?: rawVal
                    reqBuilder.addHeader(k, resolved)
                }
            } catch (ignored: Throwable) {
                Log.w(TAG, "headers 解析失败，忽略: $headersStr")
            }
        }

        val mediaType = "application/octet-stream".toMediaTypeOrNull()
        val reqBody: RequestBody? = if (!body.isNullOrEmpty()) {
            body.toByteArray(Charsets.UTF_8).toRequestBody(mediaType)
        } else null

        val okReq: Request = try {
            val builtBuilder: Request.Builder = when (method) {
                "GET" -> reqBuilder.get()
                "HEAD" -> reqBuilder.head()
                "POST" -> reqBuilder.post(bodyOrEmpty(reqBody))
                "PUT" -> reqBuilder.put(bodyOrEmpty(reqBody))
                "PATCH" -> reqBuilder.patch(bodyOrEmpty(reqBody))
                "DELETE" -> reqBuilder.delete(reqBody)
                else -> reqBuilder.method(method, reqBody)
            }
            builtBuilder.build()
        } catch (e: Throwable) {
            return aciError(QuroAidlAciErrors.E_BAD_REQUEST, "HTTP 方法/请求体非法", "请使用合法 HTTP 方法（GET/POST/PUT/DELETE/PATCH/HEAD）并确认请求体为合法字符串。", QuroAidlAciErrors.LAYER_PROTOCOL)
        }

        return try {
            val response = client.newCall(okReq).execute()
            try {
                val code = response.code
                val headers = JSONObject()
                for (i in 0 until response.headers.size) {
                    headers.put(response.headers.name(i), response.headers.value(i))
                }

                // 大响应体保护：Content-Length > 2MB 不载入内存，直接标记截断
                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                if (contentLength > 2_000_000L) {
                    HttpCallAudit.log(this, url, method, code, System.currentTimeMillis() - t0)
                    return AidlAciResponse.success(Bundle())
                        .putResult("status_code", code)
                        .putResult("response_headers", headers.toString())
                        .putResult("response_body", "")
                        .putResult("truncated", true)
                        .putResult(
                            "truncated_reason",
                            "响应体超过 2MB，未载入内存（如需大文件请改用受控浏览器或文件下载能力）"
                        )
                }

                val raw = response.body?.string() ?: ""
                val truncated = raw.length > 150_000
                val safe = if (truncated) {
                    raw.take(150_000) + "\n…[响应体已截断，完整内容见 response_body_gz，共 ${raw.length} 字符]"
                } else raw
                val r = AidlAciResponse.success(Bundle())
                    .putResult("status_code", code)
                    .putResult("response_headers", headers.toString())
                    .putResult("response_body", safe)
                    .putResult("truncated", truncated)
                if (truncated) {
                    val gz = gzip(raw.toByteArray())
                    if (gz.size <= 900_000) {
                        r.putResult("response_body_gz", gz)
                        r.putResult("response_body_len", raw.length)
                    }
                }
                HttpCallAudit.log(this, url, method, code, System.currentTimeMillis() - t0)
                return r
            } finally {
                response.close()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "doHttp 异常: ${e.message}")
            return aciHttpError(e.message)
        }
    }

    /**
     * 构造「结构化错误」响应：用 aci-protocol 语义码作为 wire 错误码，
     * errorMessage 内嵌 {code,message,suggestion,layer} JSON（控制端可解析自助纠错）。
     * 依托 aci-core 的 AidlAciResponse.error(int,String) 公开 API，不改内核。
     */
    private fun aciError(code: Int, message: String, suggestion: String, layer: String): AidlAciResponse =
        AidlAciResponse.error(code, QuroAidlAciErrors.of(code, message, suggestion, layer).toJson())

    /** HTTP 层失败：按异常特征区分 TLS / 通用客户端错误，给出可解析建议。 */
    private fun aciHttpError(cause: String?): AidlAciResponse =
        if (QuroAidlAciErrors.isTlsError(cause))
            aciError(QuroAidlAciErrors.E_HTTP_TLS, "HTTPS 证书校验失败", QuroAidlAciErrors.httpSuggestion(cause), QuroAidlAciErrors.LAYER_HTTP)
        else
            aciError(QuroAidlAciErrors.E_HTTP_CLIENT, "HTTP 请求失败", QuroAidlAciErrors.httpSuggestion(cause), QuroAidlAciErrors.LAYER_HTTP)

    /** 受控端协议版本握手：返回 aci-protocol 标识 + SemVer + 支持列表（aci-core Capability 暴露）。 */
    private fun handleProtocol(): AidlAciResponse {
        val b = Bundle()
        b.putString("protocol_version", QuroAidlAciProtocol.PROTOCOL_VERSION)
        b.putString("semver", QuroAidlAciProtocol.PROTOCOL_SEMVER)
        b.putString("supported", QuroAidlAciProtocol.SUPPORTED.joinToString(","))
        return AidlAciResponse.success(b)
    }

    /**
     * 构造 OkHttpClient，按 HTTPS 信任策略配置：
     * - tlsCaPem 非空：固定自定义 CA（自签证书精准信任，最安全）；
     * - tlsVerify=false：放行所有证书（仅限可信内网 / 自签调试，有明确风险）；
     * - 默认（两者皆否）：系统证书校验（标准公开 HTTPS）。
     */
    private fun buildHttpClient(tlsVerify: Boolean, tlsCaPem: String?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(14, TimeUnit.SECONDS)
            .writeTimeout(14, TimeUnit.SECONDS)
        return when {
            !tlsCaPem.isNullOrBlank() -> {
                try {
                    val cf = CertificateFactory.getInstance("X.509")
                    val ca = cf.generateCertificate(tlsCaPem.toByteArray(Charsets.UTF_8).inputStream()) as X509Certificate
                    val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
                    ks.setCertificateEntry("aci_ca", ca)
                    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    tmf.init(ks)
                    val ssl = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, SecureRandom()) }
                    builder.sslSocketFactory(ssl.socketFactory, tmf.trustManagers[0] as X509TrustManager)
                } catch (e: Throwable) {
                    Log.w(TAG, "自定义 CA 解析失败，回退系统校验: ${e.message}")
                }
                builder.build()
            }
            !tlsVerify -> {
                val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                })
                val ssl = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
                builder.sslSocketFactory(ssl.socketFactory, trustAll[0] as X509TrustManager)
                builder.hostnameVerifier(HostnameVerifier { _, _ -> true })
                builder.build()
            }
            else -> builder.build()
        }
    }

    private fun bodyOrEmpty(b: RequestBody?): RequestBody =
        b ?: ByteArray(0).toRequestBody()

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        val gz = GZIPOutputStream(bos)
        gz.write(data)
        gz.finish()
        gz.close()
        return bos.toByteArray()
    }

    /**
     * HTTP 调用审计（原创，P0 治理项）。
     * 持久化每一次经 http_request 发出的真实 HTTP 请求（URL/方法/状态码/耗时），
     * 供用户在控制台或文件管理器事后审查「AI 昨天发了哪些 HTTP 请求」。
     * 存储 filesDir/http_call_audit.json，{"audit":[...]}，最多保留最近 500 条。
     */
    private object HttpCallAudit {
        private const val FILE = "http_call_audit.json"
        private const val MAX = 500
        private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        fun log(ctx: Context, url: String, method: String, code: Int, durationMs: Long) {
            runCatching {
                val file = File(ctx.filesDir, FILE)
                val arr = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
                val obj = JSONObject()
                obj.put("timestamp", fmt.format(Date()))
                obj.put("method", method)
                obj.put("url", url)
                obj.put("code", code)
                obj.put("durationMs", durationMs)
                arr.put(obj)
                while (arr.length() > MAX) arr.remove(0)
                file.writeText(JSONObject().put("audit", arr).toString())
            }
        }
    }
}
