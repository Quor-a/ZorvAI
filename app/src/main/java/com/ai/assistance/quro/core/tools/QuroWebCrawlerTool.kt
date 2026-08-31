package com.ai.assistance.quro.core.tools

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ai.assistance.quro.core.QuroBrowserBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * AI 驱动网页爬虫（内置浏览器 / WebView）。
 *
 * 与 [BrowserActTool] 的区别：browser_act 是"单页操控"（点哪填哪），web_crawler 是"批量抓取"——
 * 它会顺着链接自动遍历整站，适合采集文章/列表/文档。
 *
 * 能力：
 * - 走真实 WebView 渲染，所以**能抓 JS 动态页面**（SPA / 懒加载），不是只能抓静态 HTML。
 * - 自动提取每页正文（body.innerText 清洗）与外链（a[href] 绝对化）。
 * - 同站 / 同域限流、去重、深度与页数上限，避免无限爬。
 * - 结果返回结构化 JSON；可选 [save_markdown] 把整站正文导成 Markdown 报告存到下载目录。
 *
 * 入口：工具中心「网页爬虫」卡 → 自动打开浏览器并提示 AI 用 web_crawler。
 */
class WebCrawlerTool : QuroTool {
    override val name = "web_crawler"
    override val description = "AI 驱动网页爬虫（用应用内置浏览器 WebView 逐页渲染抓取）。" +
        "支持 JS 动态页面；自动提取正文与外链，同域限流、去重、深度/页数上限。" +
        "参数 {\"start_url\":\"https://...\",\"max_depth\":2,\"max_pages\":15,\"same_host_only\":true," +
        "\"extract\":\"both|links|text\",\"render_wait_ms\":2500,\"save_markdown\":false}。" +
        "extract=both 返回链接+正文，=links 只返回链接，=text 只返回正文。" +
        "若没有活跃浏览器会自动打开 start_url。结果返回 JSON；save_markdown=true 额外导出 Markdown 到下载目录。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"crawl=从 start_url 批量爬取；fetch=只抓取单页(等价 max_depth=0,max_pages=1)。默认 crawl"},
            "start_url":{"type":"string","description":"起始 URL（crawl 必填）"},
            "max_depth":{"type":"integer","description":"最大链接深度，默认 2，最大 4"},
            "max_pages":{"type":"integer","description":"最大抓取页数，默认 15，最大 60"},
            "same_host_only":{"type":"boolean","description":"只抓同 host 的链接（默认 true，避免爬出本站）"},
            "extract":{"type":"string","description":"both(链接+正文)/links(仅链接)/text(仅正文)，默认 both"},
            "render_wait_ms":{"type":"integer","description":"每页渲染等待毫秒，默认 2500，最大 12000"},
            "save_markdown":{"type":"boolean","description":"是否把整站正文导出 Markdown 到下载目录，默认 false"}
        },
        "required":["start_url"]
    }"""

    override fun run(context: Context, arguments: String): String = runBlocking {
        val jo = JSONObject(arguments)
        val action = jo.optString("action", "crawl").trim().lowercase()
        val startUrl = jo.optString("start_url", "").trim()
        if (startUrl.isEmpty()) return@runBlocking "web_crawler 缺少 start_url"
        if (!runCatching { URL(startUrl) }.isSuccess) return@runBlocking "start_url 不是合法 URL：$startUrl"

        val maxDepth = if (action == "fetch") 0 else jo.optInt("max_depth", 2).coerceIn(0, 4)
        val maxPages = if (action == "fetch") 1 else jo.optInt("max_pages", 15).coerceIn(1, 60)
        val sameHostOnly = jo.optBoolean("same_host_only", true)
        val extract = jo.optString("extract", "both").takeIf { it in setOf("both", "links", "text") } ?: "both"
        val renderWaitMs = jo.optInt("render_wait_ms", 2500).coerceIn(300, 12000)
        val saveMarkdown = jo.optBoolean("save_markdown", false)

        // 确保有活跃浏览器
        if (!QuroBrowserController.isAttached()) {
            QuroBrowserBridge.open(startUrl)
            repeat(60) {
                delay(100)
                if (QuroBrowserController.isAttached()) return@repeat
            }
        }
        if (!QuroBrowserController.isAttached()) {
            return@runBlocking "浏览器尚未就绪：请先在工具中心打开「浏览器 AI 操控」或「网页爬虫」，再调用 web_crawler。"
        }

        val baseHost = runCatching { URL(startUrl).host }.getOrDefault("")
        val visited = LinkedHashSet<String>()
        val queue = ArrayDeque<CrawlItem>()
        queue.add(CrawlItem(normalize(startUrl), 0))
        val pages = mutableListOf<PageRecord>()

        var done = 0
        var linkTotal = 0
        while (queue.isNotEmpty() && done < maxPages) {
            val item = queue.removeFirst()
            val norm = normalize(item.url)
            if (norm in visited) continue
            visited.add(norm)

            QuroBrowserController.navigate(item.url)
            QuroBrowserController.waitReady(renderWaitMs.toLong())
            val title = QuroBrowserController.currentTitle() ?: ""
            val links = QuroBrowserController.collectLinks() ?: emptyList()
            linkTotal += links.size
            val text = if (extract != "links") (QuroBrowserController.collectText(4000) ?: "") else ""

            pages.add(
                PageRecord(
                    url = item.url,
                    title = title,
                    depth = item.depth,
                    links = links.take(80),
                    text = if (extract != "links") text.take(1500) else "",
                )
            )
            done++

            if (item.depth < maxDepth) {
                for (l in links) {
                    val ln = normalize(l)
                    if (ln.isEmpty() || ln in visited) continue
                    if (sameHostOnly && hostOf(ln) != baseHost) continue
                    if (pages.size + queue.size >= maxPages) continue
                    queue.add(CrawlItem(ln, item.depth + 1))
                }
            }
        }

        // 组装结果
        val pagesArr = JSONArray()
        val md = StringBuilder()
        md.append("# 爬虫报告\n\n")
        md.append("- 起始：$startUrl\n- 抓取页数：$done / 上限 $maxPages\n- 最大深度：$maxDepth\n- 同域限制：$sameHostOnly\n\n")
        pages.forEach { p ->
            val o = JSONObject()
            o.put("url", p.url)
            o.put("title", p.title)
            o.put("depth", p.depth)
            o.put("links", JSONArray(p.links))
            if (extract != "links") o.put("text", p.text)
            pagesArr.put(o)
            // markdown
            md.append("## [${p.title.ifBlank { p.url }}](${p.url})\n\n")
            if (extract != "links" && p.text.isNotBlank()) {
                md.append(p.text).append("\n\n")
            }
            if (extract != "text") {
                md.append("链接（${p.links.size}）：\n")
                p.links.forEach { md.append("- $it\n") }
                md.append("\n")
            }
        }

        val result = JSONObject().apply {
            put("pages", pagesArr)
            put("stats", JSONObject().apply {
                put("pages_crawled", done)
                put("max_pages", maxPages)
                put("max_depth", maxDepth)
                put("same_host_only", sameHostOnly)
                put("total_links_seen", linkTotal)
                put("extract", extract)
            })
        }

        var saveNote = ""
        if (saveMarkdown) {
            val name = "crawler-${System.currentTimeMillis()}.md"
            saveNote = "\n\n📄 Markdown 报告已保存：${saveMarkdownFile(context, name, md.toString())}"
        }

        // 截断输出，避免撑爆上下文：只回前若干页摘要 + 统计
        val brief = JSONObject().apply {
            put("stats", result.getJSONObject("stats"))
            put("pages_summary", JSONArray().also { arr ->
                pages.take(12).forEach { p ->
                    arr.put(JSONObject().apply {
                        put("url", p.url)
                        put("title", p.title)
                        put("links", p.links.size)
                        if (extract != "links") put("text_head", p.text.take(200))
                    })
                }
            })
            if (pages.size > 12) put("note", "仅显示前 12 页摘要，完整 ${pages.size} 页见 web_crawler 返回的 pages 字段（或直接 save_markdown=true 导出）")
        }
        "✅ 爬取完成（${done} 页）。\n${brief.toString(2)}$saveNote"
    }

    private data class CrawlItem(val url: String, val depth: Int)
    private data class PageRecord(
        val url: String,
        val title: String,
        val depth: Int,
        val links: List<String>,
        val text: String,
    )

    /** 去 fragment，避免同页锚点造成重复。 */
    private fun normalize(u: String): String {
        val h = u.indexOf('#')
        return if (h >= 0) u.substring(0, h) else u
    }

    private fun hostOf(u: String): String = runCatching { URL(u).host }.getOrDefault("")

    private fun saveMarkdownFile(context: Context, name: String, content: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    "下载目录/$name"
                } else {
                    fallbackSaveMd(context, name, content)
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                fallbackSaveMdToDir(dir, name, content)
            }
        } catch (e: Exception) {
            fallbackSaveMd(context, name, content)
        }
    }

    private fun fallbackSaveMd(context: Context, name: String, content: String): String {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        return fallbackSaveMdToDir(dir, name, content)
    }

    private fun fallbackSaveMdToDir(dir: java.io.File?, name: String, content: String): String {
        return try {
            dir?.mkdirs()
            val f = java.io.File(dir, name)
            f.writeText(content, Charsets.UTF_8)
            f.absolutePath
        } catch (e: Exception) {
            "保存失败：${e.message}"
        }
    }
}
