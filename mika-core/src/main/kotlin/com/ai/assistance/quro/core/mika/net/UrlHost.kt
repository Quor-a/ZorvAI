package com.ai.assistance.quro.core.mika.net

import java.net.IDN
import java.net.URI
import java.net.URLDecoder

/** 网址里的主机名与站点:「在哪个网站」的说法(读屏、运行状态、知识匹配、技能草稿)都按它算。 */
object UrlHost {
    /** 常见的两段公共后缀。 */
    private val TWO_PART_SUFFIXES = setOf(
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
        "com.hk", "com.tw", "com.mo", "com.sg", "com.au", "com.br",
        "co.uk", "org.uk", "ac.uk", "co.jp", "ne.jp", "or.jp", "co.kr", "co.in", "co.nz",
    )

    /** 托管平台:每个用户的子域名是不同的站点,平台域名本身当公共后缀。 */
    private val PRIVATE_SUFFIXES = setOf(
        "github.io", "vercel.app", "pages.dev", "netlify.app", "blogspot.com", "herokuapp.com",
        "amazonaws.com", "workers.dev", "web.app", "appspot.com", "firebaseapp.com",
    )

    /** 国家顶级域名下常作第二段公共后缀的词。 */
    private val GENERIC_SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go")

    /** 小写 ASCII 域名(国际化域名转成 xn-- 形式),去掉端口和开头的 www.;解析不出为 null。 */
    fun of(url: String): String? = raw(url)?.removePrefix("www.")?.ifEmpty { null }

    /**
     * 两个地址是否同一站点:主机名相同(不计 www),或同属一个能确定的可注册域名。
     * 不看协议和端口。拿不准的后缀一律判为不同站点。
     */
    fun sameSite(a: String, b: String): Boolean {
        val ha = of(a) ?: return false
        val hb = of(b) ?: return false
        if (ha == hb) return true
        val sa = registrable(ha) ?: return false
        return sa == registrable(hb)
    }

    internal fun raw(url: String): String? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        // 含非 ASCII 字符的主机名 URI 解析不出 host,从 authority 里取
        val h = u.host ?: u.rawAuthority?.let(::hostOfAuthority) ?: return null
        val ascii = runCatching { IDN.toASCII(h.trimEnd('.')) }.getOrNull()?.lowercase() ?: return null
        return ascii.ifEmpty { null }
    }

    private fun hostOfAuthority(authority: String): String? {
        val a = URLDecoder.decode(authority.substringAfterLast('@'), "UTF-8")
        if (a.startsWith("[")) return a.substringBefore(']') + "]"
        return a.substringBefore(':').ifEmpty { null }
    }

    /** 可注册域名(公共后缀再加一段);后缀拿不准、或主机名就是后缀本身时为 null。 */
    fun registrable(host: String): String? {
        if (host.startsWith("[") || host.all { it.isDigit() || it == '.' }) return null
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return null
        fun tail(n: Int) = labels.takeLast(n).joinToString(".")
        val suffixLen = when {
            tail(2) in PRIVATE_SUFFIXES || tail(2) in TWO_PART_SUFFIXES -> 2
            labels.last().length == 2 && labels.size >= 2 &&
                (labels[labels.size - 2].length <= 3 || labels[labels.size - 2] in GENERIC_SECOND_LEVEL) -> 2
            else -> 1
        }
        if (labels.size <= suffixLen) return null
        return tail(suffixLen + 1)
    }
}
