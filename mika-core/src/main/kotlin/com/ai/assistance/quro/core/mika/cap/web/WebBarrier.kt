package com.ai.assistance.quro.core.mika.cap.web

import com.ai.assistance.quro.core.mika.uistate.ScreenCheck
import com.ai.assistance.quro.core.mika.uistate.ScreenDiagnosis
import com.ai.assistance.quro.core.mika.uistate.ScreenFacts
import com.ai.assistance.quro.core.mika.uistate.ScreenState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

/**
 * 网页上采集到的事实:[base] 同手机(可见文字、密码框、黑帧…),另加主文档 HTTP 状态、
 * 页面里框架的地址(只算跨源的)、当前页面主机名。
 */
data class WebFacts(val base: ScreenFacts, val httpStatus: Int?, val frameUrls: List<String>, val host: String?)

/** 网页障碍判定:先看网页独有的迹象,再落到和手机同一套 [ScreenDiagnosis.classify]。 */
object WebBarrier {
    private val RISK_STATUS = setOf(403, 429, 503)
    private val RISK_WORDS = listOf("访问受限", "频繁", "异常", "blocked", "too many requests")

    /** 人机验证服务的框架主机(按域名后缀认)。 */
    private val CAPTCHA_HOSTS = listOf(
        "recaptcha.net", "hcaptcha.com", "challenges.cloudflare.com", "geetest.com", "arkoselabs.com", "funcaptcha.com",
    )
    /** 主机名本身不专门做验证、靠路径区分的(主机, 路径前缀)。 */
    private val CAPTCHA_PATHS = listOf("google.com" to "/recaptcha", "gstatic.com" to "/recaptcha")
    private val CAPTCHA_HOST_WORDS = listOf("captcha")

    /**
     * 风控拦截页(网站觉得这次访问不像人、环境不对)上的说法,单独出现就算(比对时去掉空白、不分大小写)。
     * 只在页面字不多时认:正文很长的页面(讲这类问题的文章)不算。
     */
    private val INTERSTITIAL_WORDS = listOf(
        "访问异常", "异常访问", "访问行为异常", "访问存在异常", "环境异常", "环境存在异常", "存在安全风险", "当前访问存在风险",
        "操作频繁", "操作过于频繁", "访问过于频繁", "请求过于频繁", "访问频繁",
        "unusualtraffic", "unusualactivity", "suspiciousactivity", "automatedqueries", "automatedrequests", "checkingyourbrowser",
        "accesstemporarilyrestricted", "requestwasblocked", "requesthasbeenblocked",
    )
    /** 只是标题(「安全提示」「安全检查」)的,还要同页有异常、验证、频繁一类的字。 */
    private val INTERSTITIAL_TITLES = listOf("安全提示", "安全检查", "安全警告", "securitycheck", "securitynotice", "securityverification")
    private val INTERSTITIAL_CUES = listOf("异常", "验证", "频繁", "风险", "unusual", "verify", "suspicious", "robot", "automated")
    /** 算「页面字不多」的上限:可见文字合计字数(去掉空白)、条数。 */
    private const val INTERSTITIAL_MAX_CHARS = 800
    private const val INTERSTITIAL_MAX_TEXTS = 40

    /** 接口响应里表示被风控拦下的说法:和 [INTERSTITIAL_WORDS] 同一类,另加接口常用的写法。 */
    private val API_RISK_WORDS = INTERSTITIAL_WORDS + listOf(
        "环境存在风险", "账号存在风险", "请求异常", "访问受限", "abnormal", "riskcontrol", "toomanyrequests", "ratelimit",
    )
    /** 只看响应顶层报信息的这几个字段(小写比)。 */
    private val API_MESSAGE_KEYS = setOf("message", "msg", "errmsg", "errormsg", "error_msg", "errormessage", "error", "tip", "tips", "toast", "reason")
    private const val API_MESSAGE_MAX = 200

    /** 比对时去掉空白:「请下载 App 继续」和「请下载App继续」一样认。 */
    private val UNSUPPORTED_WORDS = listOf(
        "请在浏览器中打开", "当前浏览器不支持", "不支持此浏览器", "请下载app继续", "browserisnotsupported", "browsernotsupported",
    )

    fun classify(f: WebFacts): ScreenCheck {
        // 手机同一套规则已经判出要人处理(密码框、验证码字样、付款),以它为准
        val base = ScreenDiagnosis.classify(f.base)
        if (base.state == ScreenState.NEEDS_HUMAN) return base
        val text = f.base.texts.joinToString("\n").lowercase()
        val compact = text.filterNot(Char::isWhitespace)
        val app = f.base.currentApp ?: f.host
        if (f.httpStatus in RISK_STATUS) {
            RISK_WORDS.firstOrNull { it in text }?.let {
                return ScreenCheck(ScreenState.NEEDS_HUMAN, app, true, "access_blocked", "HTTP ${f.httpStatus} with text: $it")
            }
        }
        f.frameUrls.firstOrNull { captchaFrame(it, f.host) }?.let {
            return ScreenCheck(ScreenState.NEEDS_HUMAN, app, true, "captcha", "verification frame from ${WebUrlPolicy.host(it)}")
        }
        interstitial(f.base.texts, compact)?.let {
            return ScreenCheck(ScreenState.NEEDS_HUMAN, app, true, "access_blocked", "risk-control page: \"$it\"")
        }
        UNSUPPORTED_WORDS.firstOrNull { it in compact }?.let {
            return ScreenCheck(ScreenState.NORMAL, app, false, ScreenDiagnosis.UNSUPPORTED_BROWSER,
                "the site refuses this embedded browser (\"$it\"): switch route to the app if it is installed, or end_not_done")
        }
        return base
    }

    /** 字不多的页面上风控拦截页的说法;不是为 null。 */
    private fun interstitial(texts: List<String>, compact: String): String? {
        if (texts.size > INTERSTITIAL_MAX_TEXTS || compact.length > INTERSTITIAL_MAX_CHARS) return null
        INTERSTITIAL_WORDS.firstOrNull { it in compact }?.let { return it }
        val title = INTERSTITIAL_TITLES.firstOrNull { it in compact } ?: return null
        return INTERSTITIAL_CUES.firstOrNull { it in compact }?.let { "$title … $it" }
    }

    /**
     * 页面自己收到的接口响应 [body] 是不是在说被风控拦下了:JSON 对象顶层报信息的字段(message、msg、error…)里有那类说法。
     * 返回那段说法(截短);不是 JSON、没有这类字段或没说到为 null。
     */
    fun apiRisk(body: String?): String? {
        val text = body?.trim()?.takeIf { it.startsWith("{") } ?: return null
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        for ((k, v) in o) {
            if (k.lowercase() !in API_MESSAGE_KEYS) continue
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val compact = s.lowercase().filterNot(Char::isWhitespace)
            if (compact.length > API_MESSAGE_MAX) continue
            if (API_RISK_WORDS.any { it in compact }) return s.trim().take(API_MESSAGE_MAX)
        }
        return null
    }

    /** 跨源(主机名和页面不同)且指向人机验证服务的框架。 */
    private fun captchaFrame(url: String, pageHost: String?): Boolean {
        val h = WebUrlPolicy.host(url) ?: return false
        if (pageHost != null && h == pageHost.lowercase().removePrefix("www.")) return false
        fun under(d: String) = h == d || h.endsWith(".$d")
        val path = runCatching { URI(url.trim()).rawPath }.getOrNull().orEmpty()
        return CAPTCHA_HOSTS.any(::under) || CAPTCHA_PATHS.any { (d, p) -> under(d) && path.startsWith(p) } ||
            CAPTCHA_HOST_WORDS.any { it in h }
    }
}
