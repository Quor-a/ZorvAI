package com.ai.assistance.quro.ui.chat

/**
 * 对话内卡片 WebView 的**统一 HTML 兼容层**（2026-11-04）。
 *
 * ## 修的两个大 bug（用户实测：「组件超出屏幕」「外部资源直接加载不了」）
 *
 * 1. **超屏**：WebView 不设 viewport 时默认用 980px 虚拟宽度渲染，AI 写的卡片
 *    （设计给 375px 屏的）要么整页缩小成一团、要么横向溢出屏幕。这里统一补
 *    `width=device-width` viewport + 一组**兜底 CSS**（img/video 等替换元素
 *    max-width:100%、代码块 pre-wrap），任何 AI 写的卡片都塞得进手机屏。
 * 2. **外部资源加载失败**：`loadDataWithBaseURL(null, …)` 的页面 origin 是 null，
 *    相对路径、CDN fetch/XHR、ES modules 全被浏览器拦掉；`file://` origin 也一样
 *    （跨域请求一律以 null origin 发出，主流 CDN 直接拒）。统一改用 https origin
 *    （[BASE_URL]），script/img/fetch 与普通网页行为一致。
 *
 * 幂等：重复 inject 不会叠加（marker 检测）。纯字符串处理，可单测。
 */
object CardHtmlCompat {

    /** 卡片 WebView 的统一基源：https origin，外部 CDN 资源按普通网页策略加载。 */
    const val BASE_URL = "https://localhost/"

    private const val MARKER = "quro-card-compat"

    private const val VIEWPORT = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />"

    private const val STYLE =
        "<style id=\"$MARKER\">" +
            "html,body{max-width:100%!important;overflow-x:hidden!important;word-wrap:break-word}" +
            "img,video{max-width:100%!important;height:auto!important}" +
            "canvas,svg,iframe,embed,object{max-width:100%!important}" +
            "table{max-width:100%!important;display:block;overflow-x:auto}" +
            "pre,code{white-space:pre-wrap!important;word-break:break-word!important}" +
            "*{box-sizing:border-box}" +
            "</style>"

    /**
     * 给 AI 下发的卡片 HTML 补 viewport 与兜底 CSS。
     *
     * - 已有 viewport meta 的不再加第二个（浏览器只认第一个）；
     * - 已注入过 compat 的直接原样返回；
     * - 无 `<head>` 的半截 HTML（流式常客）整体前置，浏览器容错解析。
     */
    fun inject(html: String): String {
        if (html.contains(MARKER)) return html
        val lower = html.lowercase()
        val compat = if (lower.contains("<meta") && lower.contains("viewport")) STYLE else VIEWPORT + STYLE

        // 优先插到 <head> 之后；无 head 时插到 <html> 之后；再没有就整体前置。
        val headEnd = html.indexOf("</head>", ignoreCase = true)
        if (headEnd >= 0) {
            return html.substring(0, headEnd) + compat + html.substring(headEnd)
        }
        val headOpen = html.indexOf("<head", ignoreCase = true)
        if (headOpen >= 0) {
            val gt = html.indexOf('>', headOpen)
            if (gt >= 0) return html.substring(0, gt + 1) + compat + html.substring(gt + 1)
        }
        val htmlOpen = html.indexOf("<html", ignoreCase = true)
        if (htmlOpen >= 0) {
            val gt = html.indexOf('>', htmlOpen)
            if (gt >= 0) return html.substring(0, gt + 1) + "<head>" + compat + "</head>" + html.substring(gt + 1)
        }
        return compat + html
    }
}
