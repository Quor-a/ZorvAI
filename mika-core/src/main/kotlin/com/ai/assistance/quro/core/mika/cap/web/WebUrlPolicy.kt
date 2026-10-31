package com.ai.assistance.quro.core.mika.cap.web

import com.ai.assistance.quro.core.mika.tool.PACKAGE_NAME_RE
import java.net.URI
import java.net.URLDecoder
import com.ai.assistance.quro.core.mika.net.UrlHost

/** 页面想跳去 App 的链接;[packageName] 能从链接里解析出来、且是合法包名时才有。 */
data class ExternalLink(val url: String, val packageName: String?)

/** 网址规则:能打开什么、哪些是跳 App 的链接、两个地址是否同一站点。 */
object WebUrlPolicy {
    /** 浏览器自身协议:既不能导航,也不是跳 App。 */
    private val BROWSER_SCHEMES = setOf("http", "https", "file", "content", "javascript", "data", "about", "blob")

    private val SCHEME_RE = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")

    /** 应用商店的网页地址(主机, 路径前缀):打开它们的用意是装或开 App。 */
    private val STORE_PAGES = listOf(
        "play.google.com" to "/store/apps",
        "market.android.com" to "/details",
        "apps.apple.com" to "/",
        "itunes.apple.com" to "/",
    )

    /** null = 可以打开;否则是给模型的 invalid_arguments 说明。 */
    fun checkNavigable(url: String): String? {
        val u = url.trim()
        if (u.isEmpty()) return "the URL is empty"
        val scheme = scheme(u) ?: return "the URL must start with http:// or https://"
        if (external(u) != null) return "this is a link that opens an app, so it is not opened in the browser; when the task needs that app, open it on the phone"
        if (scheme != "http" && scheme != "https") return "only http/https URLs can be opened, not $scheme:"
        if (host(u) == null) return "the URL has no domain"
        return null
    }

    /** intent:、自定义协议、应用商店链接(含商店网页) → 跳 App 的链接;普通网页与浏览器自身协议为 null。 */
    fun external(url: String): ExternalLink? {
        val u = url.trim()
        val scheme = scheme(u) ?: return null
        when (scheme) {
            "intent" -> {
                val pkg = u.substringAfter("#Intent;", "").split(';')
                    .firstOrNull { it.startsWith("package=") }?.removePrefix("package=")
                return ExternalLink(u, validPackage(pkg))
            }
            "market" -> return ExternalLink(u, validPackage(queryParam(u, "id")))
            "http", "https" -> {
                val h = host(u) ?: return null
                val path = runCatching { URI(u).rawPath }.getOrNull().orEmpty().ifEmpty { "/" }
                if (STORE_PAGES.none { (sh, prefix) -> h == sh && path.startsWith(prefix) }) return null
                return ExternalLink(u, validPackage(queryParam(u, "id")))
            }
        }
        return if (scheme in BROWSER_SCHEMES) null else ExternalLink(u, null)
    }

    /** 小写 ASCII 域名(国际化域名转成 xn-- 形式),去掉端口和开头的 www.;解析不出为 null。 */
    fun host(url: String): String? = UrlHost.of(url)

    /** 两个地址是否同一站点,见 [UrlHost.sameSite]。 */
    fun sameSite(a: String, b: String): Boolean = UrlHost.sameSite(a, b)

    /** 可注册域名,见 [UrlHost.registrable]。 */
    fun registrable(host: String): String? = UrlHost.registrable(host)

    /** 同源:协议、主机名、端口都相同(缺省端口按协议补齐)。 */
    fun sameOrigin(a: String, b: String): Boolean {
        val oa = origin(a) ?: return false
        return oa == origin(b)
    }

    private fun origin(url: String): Triple<String, String, Int>? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        val h = UrlHost.raw(url) ?: return null
        val port = u.port.takeIf { it >= 0 } ?: portOf(u.rawAuthority) ?: when (scheme) { "http" -> 80; "https" -> 443; else -> -1 }
        return Triple(scheme, h, port)
    }

    private fun portOf(authority: String?): Int? =
        authority?.substringAfterLast('@')?.substringAfterLast(']')?.substringAfter(':', "")?.toIntOrNull()

    private fun validPackage(raw: String?): String? {
        val p = raw?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.trim() ?: return null
        return p.takeIf(PACKAGE_NAME_RE::matches)
    }

    /** 协议名(小写);「example.com:8080」「localhost:3000」这类没写协议的不算。 */
    private fun scheme(url: String): String? {
        val i = url.indexOf(':')
        if (i <= 0) return null
        val s = url.substring(0, i)
        if (!SCHEME_RE.matches(s) || !s.all { it.code < 128 }) return null
        val rest = url.substring(i + 1)
        if ('.' in s || (rest.isNotEmpty() && rest.takeWhile { it != '/' }.all(Char::isDigit) && rest.first().isDigit())) return null
        return s.lowercase()
    }

    private fun queryParam(url: String, name: String): String? =
        url.substringAfter('?', "").substringBefore('#').split('&')
            .firstOrNull { it.startsWith("$name=") }?.removePrefix("$name=")?.takeIf { it.isNotBlank() }
}
