package com.ai.assistance.quro.browser

import ai.aci.core.ACIError
import ai.aci.core.ACIRequest
import ai.aci.core.ACIResponse
import ai.aci.core.BaseACIService
import ai.aci.core.Capability
import android.content.Intent
import android.os.Bundle

/**
 * ACI 受控端 Service（v6 · Binder 溢出修复：html 安全截断 + gzip 二进制回传）。
 *
 * 核心改进（针对 v4 诊断结果：Activity 正常但 Service 可能 onCreate 崩溃导致绑不上）：
 * 1. super.onCreate() 也包 try-catch —— 基类内部调 onCreateCapabilities，任何异常都会炸掉整个 Service
 * 2. 所有诊断写入 DiagBuffer（不依赖文件），Activity 启动后渲染到屏幕
 * 3. 能力注册用最简 API 先验证通路（后续再加复杂参数）
 */
class QuroControlledAciService : BaseACIService() {

    companion object {
        private const val TAG = "Service"
        private const val ZORV_PKG = "com.ai.assistance.quro"
    }

    override fun onCreate() {
        DiagBuffer.append(TAG, "═ onCreate 开始 ═")
        try {
            // 关键：super.onCreate() 内部会调用 onCreateCapabilities()
            // 如果后者抛异常，整个 Service 创建失败 → bindService 永远不会成功
            super.onCreate()
            DiagBuffer.append(TAG, "✅ super.onCreate() 完成（含 onCreateCapabilities）")
        } catch (e: Throwable) {
            DiagBuffer.append(TAG, "❌ super.onCreate() 崩溃: ${e.javaClass.simpleName}: ${e.message}")
            // 不要 rethrow —— 让 Service 尽量存活，至少 onBind 能返回 binder
        }

        try {
            BrowserCore.init(applicationContext)
            DiagBuffer.append(TAG, "✅ BrowserCore.init()")
        } catch (e: Throwable) {
            DiagBuffer.append(TAG, "⚠️ BrowserCore.init 失败: ${e.message}")
        }

        DiagBuffer.append(TAG, "═ onCreate 结束 ═")
    }

    override fun onCreateCapabilities(caps: MutableList<Capability>) {
        DiagBuffer.append(TAG, "onCreateCapabilities 开始 (caps列表已传入)")

        var ok = 0
        var fail = 0

        // browser_open
        try {
            caps.add(
                Capability.create("browser_open", "打开指定网址并导航到该页面")
                    .addParam("url", "string", true, "要打开的网址")
                    .addResult("launched", "boolean", "是否已启动")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_open")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_open: ${e.message}")
        }

        // browser_read
        try {
            caps.add(
                Capability.create("browser_read", "读取当前页的 URL、标题与完整 HTML")
                    .addResult("url", "string", "当前网址")
                    .addResult("title", "string", "页面标题")
                    .addResult("html", "string", "页面 HTML")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_read")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_read: ${e.message}")
        }

        // browser_list
        try {
            caps.add(
                Capability.create("browser_list", "列出当前打开的浏览器标签页")
                    .addResult("tabs", "string", "标签页摘要")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_list")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_list: ${e.message}")
        }

        // browser_info
        try {
            caps.add(
                Capability.create("browser_info", "查询受控浏览器的包名与版本信息")
                    .addResult("package", "string", "包名")
                    .addResult("version", "string", "版本名")
                    .addResult("version_code", "string", "版本号")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_info")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_info: ${e.message}")
        }

        DiagBuffer.append(TAG, "onCreateCapabilities 完成: $ok 成功 / $fail 失败 / 总计=${caps.size}")

        // 持久化一份到文件（备用）
        DiagBuffer.persist(this)
    }

    override fun onCheckPermission(req: ACIRequest?, callerPkg: String?): Boolean {
        val ok = callerPkg == ZORV_PKG || callerPkg == packageName
        DiagBuffer.append(TAG, "onCheckPermission: caller=$callerPkg → ${if(ok)"放行" else "拒绝"}")
        return ok
    }

    override fun onCall(req: ACIRequest?): ACIResponse {
        if (req == null) {
            DiagBuffer.append(TAG, "onCall: null request")
            return ACIResponse.error(ACIError.REQUEST_NULL, "null")
        }
        val cap = req.capability
        DiagBuffer.append(TAG, "onCall: capability=$cap")
        // 点亮 AI「眼睛」：通知 Activity 底部指示灯进入「控制中」状态
        BrowserCore.reportAiActivity("ACI 调用能力：$cap")

        return try {
            when (cap) {
                "browser_open" -> handleOpen(req.params)
                "browser_read" -> handleRead()
                "browser_list" -> handleList()
                "browser_info" -> handleInfo()
                else -> {
                    DiagBuffer.append(TAG, "onCall: 未知能力 $cap")
                    ACIResponse.error(ACIError.CAPABILITY_NOT_FOUND, "unknown: $cap")
                }
            }
        } catch (e: Throwable) {
            DiagBuffer.append(TAG, "onCall 异常: ${e.message}")
            ACIResponse.error(ACIError.INTERNAL_ERROR, e.message ?: "err")
        }
    }

    // ── 能力实现 ──

    private fun handleOpen(params: Bundle?): ACIResponse {
        val url = params?.getString("url") ?: ""
        DiagBuffer.append(TAG, "browser_open: url=$url")
        if (url.isEmpty()) return ACIResponse.error(ACIError.BAD_REQUEST, "no url")

        BrowserCore.loadUrl(url)

        try {
            startActivity(Intent(this, BrowserActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("url", url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Throwable) {
            DiagBuffer.append(TAG, "browser_open: Activity启动失败 ${e.message}")
        }
        return ACIResponse.success(Bundle()).putResult("launched", true)
    }

    /**
     * 读取当前页 URL/标题/HTML（v6 修复 Binder ~1MB 溢出）。
     * 策略：始终返回「安全截断的 html 字符串」（≤150k 字符，永不过 Binder，向后兼容）；
     * 若原始 HTML 过大，额外 gzip 压成 byte[] 经 html_gz 回传，控制端解压拿到完整内容，
     * 彻底绕开 1MB 事务限制。gzip 仍超 900KB 时放弃 html_gz，仅返回截断预览。
     */
    private fun handleRead(): ACIResponse {
        val raw = BrowserCore.readHtml()
        DiagBuffer.append(TAG, "browser_read: rawLen=${raw.length}")
        val url = BrowserCore.getUrl() ?: ""
        val title = BrowserCore.getTitle() ?: ""
        val truncated = raw.length > 150_000
        val safe = if (truncated) {
            raw.take(150_000) + "\n…[内容已截断，完整 HTML 见 html_gz，共 ${raw.length} 字符]"
        } else raw
        val resp = ACIResponse.success(Bundle())
            .putResult("url", url)
            .putResult("title", title)
            .putResult("html", safe)
            .putResult("truncated", truncated)
        if (truncated) {
            val gz = gzip(raw.toByteArray())
            DiagBuffer.append(TAG, "browser_read: gzipLen=${gz.size}")
            if (gz.size <= 900_000) {
                resp.putResult("html_gz", gz)
                resp.putResult("html_len", raw.length)
            } else {
                DiagBuffer.append(TAG, "browser_read: gzip 仍超 Binder(${gz.size})，放弃 html_gz，仅返回截断预览")
            }
        }
        return resp
    }

    /** gzip 压缩（受控端用，绕过 AIDL ~1MB 限制）。 */
    private fun gzip(data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        val gz = java.util.zip.GZIPOutputStream(bos)
        gz.write(data)
        gz.finish()
        gz.close()
        return bos.toByteArray()
    }

    private fun handleList(): ACIResponse {
        return ACIResponse.success(Bundle())
            .putResult("tabs", "url=${BrowserCore.getUrl()} title=${BrowserCore.getTitle()}")
    }

    private fun handleInfo(): ACIResponse {
        return ACIResponse.success(Bundle())
            .putResult("package", packageName)
            .putResult("versionName", try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "?" })
            .putResult("versionCode", try { "${packageManager.getPackageInfo(packageName, 0).longVersionCode}" } catch (_: Throwable) { "0" })
    }
}
