# -*- coding: utf-8 -*-
"""MiniAppEngine：注入 viewport meta + 补齐 WebView 设置。

真机故障（2026-10-06 截图）：工具中心「Web 应用」打开 demo/导入的 HTML 页，
顶栏返回行把整屏吃掉、页面元素**整体等比放大**到不符合浏览标准；
「工具中心」里miniapp_sdk 的 hello 页同样只露出底边一条。

根因：页面 HTML **没有 `<meta name="viewport">`** 时，WebView 按 980px 虚拟
布局宽度渲染再整体缩放到屏宽 —— 所有元素（含宿主顶栏下方的内容）一起变大。
`MiniAppTool` 的内置模板都带 viewport，所以只有「AI 自由写HTML」与
「导入本地 HTML」两条路径中招。

🔴 修法必须是**注入 viewport**，不能开 `useWideViewPort`/`loadWithOverviewMode`：
本文件同仓QuroToolCenterScreen.kt:790 已留前车之鉴 —— 那两项会让
`html,body{height:100%}` 的页面算出 0 高可见视口 → 整页白屏。

铁律：原子写（.tmp + os.replace）；改前 assert 锚点唯一。
"""
import io
import os
import time

P = "app/src/main/java/com/ai/assistance/quro/core/miniapp/MiniAppEngine.kt"
s = io.open(P, encoding="utf-8").read()

# ── 1. configure() 补齐设置 ──
old1 = """    /** 配置 WebView：启用 JS、注入 native 桥、拦截 Web 应用本地资源请求。 */
    fun configure() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)"""
assert s.count(old1) == 1, "cfg anchor %d" % s.count(old1)

new1 = """    /**
     * 配置 WebView：启用 JS、注入 native 桥、拦截 Web 应用本地资源请求。
     *
     * 🔴 **不要开 `useWideViewPort` / `loadWithOverviewMode`**（2026-10-06）：
     * 这两项会让 `html,body{height:100%;overflow:hidden}` 的页面算出 0 高可见
     * 视口 → 整页白屏。同仓 QuroToolCenterScreen.kt:790 有同款前车之鉴。
     * 移动端视口的正确解法是给 HTML **注入 viewport meta**（见 [ensureViewport]）。
     *
     * 🔴 `textZoom` 必须**钉死为 100**：不钉的话 WebView 会跟随系统「显示大小/
     * 字体大小」设置放大，同一段 HTML 在不同用户手机上字号差一大截 —— 这正是
     * 「只有开了大字体的机器上整页elements 巨大」的成因。
     */
    fun configure() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            defaultTextEncodingName = "UTF-8"
            loadsImagesAutomatically = true
            // 让 <img> 等资源在 file:// / 自定义 scheme 下也能加载
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            // 🔴 字号不跟随系统字体缩放：AI 生成的页面自己用 px/rpx 控制版式，
            // 叠加系统缩放会整体放大到不符合浏览标准（真机截图症状）。
            // 代价：用户没法再通过系统设置放大 Web 应用页 —— 但版式可控更重要，
            // 且小程序侧（miniapp-sdk）本来就不跟sp 缩放，两边行为要一致。
            textZoom = 100
            // 视口宽度由页面自己的 viewport meta 决定（见 ensureViewport）
            useWideViewPort = false
            loadWithOverviewMode = false
            // 视口按设备宽度裁剪，避免内容溢出横向滚动
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }"""
s = s.replace(old1, new1)

# ── 2. loadPageHtml 注入 viewport ──
old2 = """        val injected = injectBridge(html, query)"""
assert s.count(old2) == 1, "inject anchor %d" % s.count(old2)
new2 = """        val injected = injectBridge(ensureViewport(html), query)"""
s = s.replace(old2, new2)

# ── 3. 新增 ensureViewport()，放在 injectBridge 之前 ──
old3 = """    /** 在页面 <head> 注入桥接运行时脚本 + 当前页 query。 */"""
assert s.count(old3) == 1, "injectBridge anchor %d" % s.count(old3)

new3 = """    /**
     * 🔴 保证页面有 `<meta name="viewport">`，没有就补一条。
     *
     * 没有 viewport 的 HTML，WebView 会按 980px 的虚拟布局宽度渲染再整体缩放到
     * 屏宽 —— 表现为**整页元素等比放大**，顶栏下方的内容被挤到看不见，
     * 完全不符合移动端浏览标准（2026-10-06 真机截图，两处同症状）。
     *
     * 为什么不用 `settings.useWideViewPort = true`：
     * 那会让 WebView 忽略页面的 `height:100%` 语义，部分页算出 0 高可见视口 →
     * 整页白屏（同仓 QuroToolCenterScreen.kt:790 前车之鉴）。注入 meta 是唯一
     * 兼顾「按设备宽度渲染」与「尊重页面自身盒模型」的做法。
     *
     * 已有 viewport 的页面（含 AI 显式写了 `initial-scale` / `user-scalable=no`
     * 的）一律原样保留 —— 只补缺失的那部分，不覆盖作者意图。
     */
    fun ensureViewport(html: String): String {
        if (html.isBlank()) return html
        // 已带 viewport（无论大小写、无论写在 head 还是别处）→ 不动
        if (VIEWPORT_RE.containsMatchIn(html)) return html

        val meta = "<meta name=\\"viewport\\" content=\\"width=device-width,initial-scale=1,viewport-fit=cover\\">"
        val headIdx = html.indexOf("<head", ignoreCase = true)
        if (headIdx >= 0) {
            val gt = html.indexOf('>', headIdx)
            if (gt >= 0) return html.substring(0, gt + 1) + "\\n" + meta + html.substring(gt + 1)
            //<head 后没有 '>'：HTML 本身不完整，补完 head 再塞
            return html + "\\n<meta><head>" + meta + "</head>"
        }
        // 连 <head> 都没有：html/`<html ...>` 之后立刻补
        val htmlIdx = html.indexOf("<html", ignoreCase = true)
        if (htmlIdx >= 0) {
            val gt = html.indexOf('>', htmlIdx)
            if (gt >= 0) return html.substring(0, gt + 1) + "\\n<head>" + meta + "</head>" + html.substring(gt + 1)
        }
        return meta + html
    }

    /** 在页面 <head> 注入桥接运行时脚本 + 当前页 query。 */"""
s = s.replace(old3, new3)

# ── 4. VIEWPORT_RE 常量：放在 companion/文件末尾的 private 区域 ──
old4 = """    private fun mimeType(p: String): String = when {"""
assert s.count(old4) == 1, "mime anchor %d" % s.count(old4)
BS = chr(92)  # 反斜杠
Q1 = chr(39)  # 单引号
Q2 = chr(34)  # 双引号
PAT = '<meta[^>]+name' + BS + 's*=' + BS + 's*[' + Q1 + Q2 + ']?viewport'
new4 = (
    "    /** 探测页面是否已声明 viewport（含 name='viewport' 单引号写法）。 */\n"
    '    private const val VIEWPORT_PATTERN = "' + PAT + '"\n'
    "    private val VIEWPORT_RE = Regex(VIEWPORT_PATTERN, RegexOption.IGNORE_CASE)\n"
    "\n"
    "    private fun mimeType(p: String): String = when {"
)
s = s.replace(old4, new4)

assert chr(0xfffd) not in s
assert s.count("fun ensureViewport") == 1
assert s.count("ensureViewport(html)") == 1

io.open(P + ".tmp", "w", encoding="utf-8", newline="\n").write(s)
for i in range(8):
    try:
        os.replace(P + ".tmp", P)
        print("OK %d chars" % len(s))
        break
    except OSError:
        time.sleep(1.5)