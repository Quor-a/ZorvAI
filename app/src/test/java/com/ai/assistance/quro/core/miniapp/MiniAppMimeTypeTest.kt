package com.ai.assistance.quro.core.miniapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Web 应用的 MIME 类型表回归测试。
 *
 * 🔴 为什么这个测试必须存在（2026-10-06）：
 * `mimeTypeOf` 原先只有 8 种类型，其余全落 `application/octet-stream`。
 * 表面上「都能加载」，实际上现代 Web 栈被浏览器**直接拒绝执行**：
 *  - `<script type="module">` / `.mjs` 必须是 `text/javascript`，
 *    否则 Android WebView 抛 `SyntaxError: Unexpected token ':'`，
 *    整条 import 链断掉 —— ES Modules 全废；
 *  - `.wasm` 必须是 `application/wasm`，否则
 *    `WebAssembly.instantiateStreaming` 失败 —— Rust/C++/Go 产物全废；
 *  - webfont / 音视频 / 图标同理。
 * 这类缺陷**不报错、不崩溃，只是功能悄悄没了**，没有测试钉住必然回退。
 */
class MiniAppMimeTypeTest {

    @Test
    fun `ES Module 的 MIME 必须严格正确`() {
        // 🔴 这两条错了整个模块化就没了
        assertEquals("text/javascript", MiniAppEngine.mimeTypeOf("app.mjs"))
        assertEquals("text/javascript", MiniAppEngine.mimeTypeOf("app.js"))
        assertEquals("text/javascript", MiniAppEngine.mimeTypeOf("lib/index.js"))
    }

    @Test
    fun `WebAssembly 的 MIME 必须是 application-wasm`() {
        // 🔴 Rust / C++ / Go 编译产物的命门
        assertEquals("application/wasm", MiniAppEngine.mimeTypeOf("module.wasm"))
    }

    @Test
    fun `字体类型齐全`() {
        assertEquals("font/woff2", MiniAppEngine.mimeTypeOf("inter.woff2"))
        assertEquals("font/woff", MiniAppEngine.mimeTypeOf("legacy.woff"))
        assertEquals("font/ttf", MiniAppEngine.mimeTypeOf("legacy.ttf"))
        assertEquals("font/otf", MiniAppEngine.mimeTypeOf("legacy.otf"))
    }

    @Test
    fun `音视频类型齐全`() {
        assertEquals("video/mp4", MiniAppEngine.mimeTypeOf("demo.mp4"))
        assertEquals("video/webm", MiniAppEngine.mimeTypeOf("demo.webm"))
        assertEquals("audio/mpeg", MiniAppEngine.mimeTypeOf("demo.mp3"))
        assertEquals("audio/ogg", MiniAppEngine.mimeTypeOf("demo.ogg"))
        assertEquals("audio/wav", MiniAppEngine.mimeTypeOf("demo.wav"))
    }

    @Test
    fun `图片与图标类型齐全`() {
        assertEquals("image/png", MiniAppEngine.mimeTypeOf("a.png"))
        assertEquals("image/jpeg", MiniAppEngine.mimeTypeOf("a.jpg"))
        assertEquals("image/jpeg", MiniAppEngine.mimeTypeOf("a.jpeg"))
        assertEquals("image/svg+xml", MiniAppEngine.mimeTypeOf("a.svg"))
        assertEquals("image/webp", MiniAppEngine.mimeTypeOf("a.webp"))
        assertEquals("image/avif", MiniAppEngine.mimeTypeOf("a.avif"))
        // favicon 走 <link rel="icon">，类型不对浏览器直接不显示图标
        assertEquals("image/x-icon", MiniAppEngine.mimeTypeOf("favicon.ico"))
    }

    @Test
    fun `样式与数据类型齐全`() {
        assertEquals("text/css", MiniAppEngine.mimeTypeOf("style.css"))
        assertEquals("application/json", MiniAppEngine.mimeTypeOf("data.json"))
        assertEquals("application/json", MiniAppEngine.mimeTypeOf("app.js.map"))
        assertEquals(
            "application/manifest+json",
            MiniAppEngine.mimeTypeOf("site.webmanifest")
        )
        assertEquals("text/html", MiniAppEngine.mimeTypeOf("index.html"))
    }

    @Test
    fun `扩展名大小写不敏感`() {
        assertEquals("text/javascript", MiniAppEngine.mimeTypeOf("APP.MJS"))
        assertEquals("application/wasm", MiniAppEngine.mimeTypeOf("Module.WASM"))
        assertEquals("image/png", MiniAppEngine.mimeTypeOf("Logo.PNG"))
    }

    @Test
    fun `查询串与锚点不影响扩展名解析`() {
        // WebView 请求 URL 常带 ?v=123 与 #hash，不能因此把扩展名判成别的
        assertEquals("text/javascript", MiniAppEngine.mimeTypeOf("app.js?v=123"))
        assertEquals("application/wasm", MiniAppEngine.mimeTypeOf("m.wasm#x"))
        assertEquals("image/png", MiniAppEngine.mimeTypeOf("a.png?t=9"))
    }

    @Test
    fun `未知扩展名兜底为二进制流而非 null`() {
        // WebResourceResponse 的 mimeType 是**非空**参数，返回 null 会崩
        val r = MiniAppEngine.mimeTypeOf("weird.unknown")
        assertTrue("兜底不能是 null", r.isNotEmpty())
        assertEquals("application/octet-stream", r)
        // 无扩展名
        assertEquals("application/octet-stream", MiniAppEngine.mimeTypeOf("LICENSE"))
    }

    @Test
    fun `关键类型不得回退成 octet-stream`() {
        // 一次性把「回退就废」的类型全列出来，任何一个被改成兜底就红
        val critical = listOf(
            "mjs", "js", "wasm", "css", "json", "map", "html",
            "png", "jpg", "jpeg", "gif", "svg", "webp", "avif", "ico",
            "woff2", "woff", "ttf", "otf",
            "mp4", "webm", "mp3", "ogg", "wav",
        )
        val bad = critical.filter { ext ->
            MiniAppEngine.mimeTypeOf("x.$ext") == "application/octet-stream"
        }
        assertTrue("这些类型被误判成 octet-stream 会导致静默失效：$bad", bad.isEmpty())
    }
}