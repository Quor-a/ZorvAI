package com.ai.assistance.quro.browser

import ai.aci.core.ACIError
import ai.aci.core.ACIRequest
import ai.aci.core.ACIResponse
import ai.aci.core.BaseACIService
import ai.aci.core.Capability
import android.content.Intent
import android.os.Bundle
import org.json.JSONObject
import java.net.URLEncoder

/**
 * ACI 受控端 Service（v7 · 新增 browser_crawl / browser_search / browser_script 三类能力）。
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

        // browser_crawl（v7 新增：爬虫）
        try {
            caps.add(
                Capability.create("browser_crawl", "抓取当前页结构化数据：标题 + 可读正文 + 出站链接（AI 检索/爬虫用）")
                    .addResult("url", "string", "当前网址")
                    .addResult("title", "string", "页面标题")
                    .addResult("text", "string", "页面正文（截断到约15万字符）")
                    .addResult("links", "string", "出站链接 JSON 数组 [{text,href}]")
                    .addResult("link_count", "string", "页面链接总数")
                    .addResult("truncated", "boolean", "正文是否被截断")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_crawl")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_crawl: ${e.message}")
        }

        // browser_search（v7 新增：检索/搜索）
        try {
            caps.add(
                Capability.create("browser_search", "用搜索引擎检索关键词，返回结果页的标题/正文/链接")
                    .addParam("query", "string", true, "检索词")
                    .addParam("engine", "string", false, "搜索引擎：bing/google/baidu/ddg，默认 bing")
                    .addResult("query", "string", "检索词")
                    .addResult("engine", "string", "实际使用的引擎")
                    .addResult("url", "string", "实际打开的检索 URL")
                    .addResult("title", "string", "结果页标题")
                    .addResult("text", "string", "结果页正文（截断）")
                    .addResult("links", "string", "结果链接 JSON 数组")
                    .addResult("truncated", "boolean", "是否截断")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_search")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_search: ${e.message}")
        }

        // browser_script（v7 新增：脚本/执行 JS）
        try {
            caps.add(
                Capability.create("browser_script", "在当前页面执行任意 JavaScript 并返回结果（脚本能力）")
                    .addParam("code", "string", true, "要执行的 JS 代码（表达式或 IIFE 返回结果）")
                    .addResult("result", "string", "JS 执行结果（JSON 字符串形式，已截断）")
                    .addResult("truncated", "boolean", "结果是否被截断")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_script")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_script: ${e.message}")
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
                "browser_crawl" -> handleCrawl()
                "browser_search" -> handleSearch(req.params)
                "browser_script" -> handleScript(req.params)
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
        // 【v1.0.11 回归修复】等 WebView 注册就绪再返回，避免后续读取竞态拿到 null
        BrowserCore.awaitWebView(3000)
        return ACIResponse.success(Bundle()).putResult("launched", true)
    }

    /**
     * 读取当前页 URL/标题/HTML（v6 修复 Binder ~1MB 溢出）。
     * 策略：始终返回「安全截断的 html 字符串」（≤150k 字符，永不过 Binder，向后兼容）；
     * 若原始 HTML 过大，额外 gzip 压成 byte[] 经 html_gz 回传，控制端解压拿到完整内容，
     * 彻底绕开 1MB 事务限制。gzip 仍超 900KB 时放弃 html_gz，仅返回截断预览。
     */
    private fun handleRead(): ACIResponse {
        // 【v1.0.11 回归修复】读前先确认 WebView 已就绪，否则给明确错误而非返回空串
        if (BrowserCore.awaitWebView(2000) == null) {
            return ACIResponse.error(ACIError.INTERNAL_ERROR, "浏览器尚未就绪：无活动页面，请先调用 browser_open")
        }
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

    /** 把 crawlPage 返回的 JSON 拆成结构化字段（容错：缺字段/解析失败给 error）。 */
    private data class CrawlResult(
        val url: String, val title: String, val text: String, val links: String, val linkCount: Int, val err: String?
    )
    private fun parseCrawl(raw: String): CrawlResult {
        if (raw.isEmpty()) return CrawlResult("", "", "", "[]", 0, "empty")
        return try {
            val o = JSONObject(raw)
            if (o.has("error")) return CrawlResult("", "", "", "[]", 0, o.optString("error"))
            val linksArr = o.optJSONArray("links")
            CrawlResult(
                o.optString("url", ""),
                o.optString("title", ""),
                o.optString("text", ""),
                linksArr?.toString() ?: "[]",
                o.optInt("linkCount", 0),
                null
            )
        } catch (e: Throwable) {
            CrawlResult("", "", "", "[]", 0, "parse:${e.message}")
        }
    }

    /** 爬虫：抓取当前页结构化数据。 */
    private fun handleCrawl(): ACIResponse {
        if (BrowserCore.awaitWebView(2000) == null) {
            return ACIResponse.error(ACIError.INTERNAL_ERROR, "浏览器尚未就绪：无活动页面，请先调用 browser_open")
        }
        val raw = BrowserCore.crawlPage()
        DiagBuffer.append(TAG, "browser_crawl: rawLen=${raw.length}")
        val c = parseCrawl(raw)
        val resp = ACIResponse.success(Bundle())
        if (c.err != null) {
            resp.putResult("error", c.err)
            return resp
        }
        val truncated = c.text.length > 150_000
        val safe = if (truncated) c.text.take(150_000) + "\n…[正文已截断，完整内容见 browser_read]" else c.text
        resp.putResult("url", c.url)
            .putResult("title", c.title)
            .putResult("text", safe)
            .putResult("links", c.links)
            .putResult("link_count", "${c.linkCount}")
            .putResult("truncated", truncated)
        return resp
    }

    /** 检索：用搜索引擎查词，返回结果页结构化数据。 */
    private fun handleSearch(params: Bundle?): ACIResponse {
        val q = params?.getString("query") ?: ""
        DiagBuffer.append(TAG, "browser_search: query=$q")
        if (q.isEmpty()) return ACIResponse.error(ACIError.BAD_REQUEST, "no query")
        val engine = (params?.getString("engine") ?: "bing").lowercase()
        val enc = URLEncoder.encode(q, "UTF-8")
        val url = when (engine) {
            "google" -> "https://www.google.com/search?q=$enc"
            "baidu" -> "https://www.baidu.com/s?wd=$enc"
            "ddg", "duckduckgo" -> "https://duckduckgo.com/?q=$enc"
            else -> "https://www.bing.com/search?q=$enc"
        }
        // 【v1.0.11 回归修复】检索需要先把结果页载入 WebView，先确认就绪
        if (BrowserCore.awaitWebView(2000) == null) {
            return ACIResponse.error(ACIError.INTERNAL_ERROR, "浏览器尚未就绪：无活动页面，请先调用 browser_open")
        }
        BrowserCore.loadUrl(url)
        val raw = BrowserCore.crawlPage()
        val c = parseCrawl(raw)
        val resp = ACIResponse.success(Bundle())
            .putResult("query", q)
            .putResult("engine", engine)
            .putResult("url", if (c.url.isEmpty()) url else c.url)
            .putResult("title", c.title)
        val truncated = c.text.length > 150_000
        resp.putResult("text", if (truncated) c.text.take(150_000) else c.text)
            .putResult("links", c.links)
            .putResult("truncated", truncated)
        if (c.err != null) resp.putResult("error", c.err)
        return resp
    }

    /** 脚本：在当前页执行任意 JS 并返回结果。 */
    private fun handleScript(params: Bundle?): ACIResponse {
        val code = params?.getString("code") ?: ""
        DiagBuffer.append(TAG, "browser_script: codeLen=${code.length}")
        if (code.isEmpty()) return ACIResponse.error(ACIError.BAD_REQUEST, "no code")
        if (BrowserCore.awaitWebView(2000) == null) {
            return ACIResponse.error(ACIError.INTERNAL_ERROR, "浏览器尚未就绪：无活动页面，请先调用 browser_open")
        }
        val raw = BrowserCore.evalScript(code)
        val truncated = raw.length > 150_000
        val safe = if (truncated) raw.take(150_000) + "\n…[结果已截断]" else raw
        return ACIResponse.success(Bundle())
            .putResult("result", safe)
            .putResult("truncated", truncated)
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
        if (BrowserCore.awaitWebView(2000) == null) {
            return ACIResponse.error(ACIError.INTERNAL_ERROR, "浏览器尚未就绪：无活动页面，请先调用 browser_open")
        }
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
