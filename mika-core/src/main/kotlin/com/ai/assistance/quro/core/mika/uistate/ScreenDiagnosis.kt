package com.ai.assistance.quro.core.mika.uistate

enum class ScreenState(val wire: String) {
    NORMAL("normal"),
    /** 没有窗口或没有可读元素。 */
    BLANK("blank"),
    /** 界面在持续变化,还没加载完。 */
    LOADING("loading"),
    /** 防截屏页面:画面全黑但有窗口。 */
    PROTECTED("protected"),
    /** 登录、验证码、支付等需要用户亲自处理的页面。 */
    NEEDS_HUMAN("needs_human"),
}

/**
 * [takeoverReason] 是建议 hand_to_user 使用的 reason;[cause] 是判定依据的简短说明。
 * 例外:[ScreenDiagnosis.UNSUPPORTED_BROWSER](网站拒绝内置浏览器)不需要人,state 为 NORMAL、needsHuman 为 false,该换路线。
 */
data class ScreenCheck(
    val state: ScreenState,
    val currentApp: String?,
    val needsHuman: Boolean,
    val takeoverReason: String? = null,
    val cause: String? = null,
)

/**
 * 端口采集到的事实。[blackFrames] 是每次界面安静时画面是否全黑的结果,按时间顺序;拿不到画面时为空。
 * [hasWindow] 是有读得到根节点的 App 窗口;[anyWindow] 是有 App 窗口(根节点读不到也算)。
 * [foregroundApp] 是窗口读不到时按焦点窗口或最上面的任务得知的前台 App;不知道为 null。
 */
data class ScreenFacts(
    val currentApp: String?,
    val hasWindow: Boolean,
    val readableElements: Int,
    val changing: Boolean,
    val blackFrames: List<Boolean>,
    val texts: List<String>,
    val passwordField: Boolean,
    val anyWindow: Boolean = hasWindow,
    val foregroundApp: String? = null,
)

object ScreenDiagnosis {
    /** 屏上一个 App 窗口都没有(虚拟屏刚建好时就是这样)。 */
    const val NO_WINDOW = "no window"
    const val UNREADABLE_WINDOW = "unreadable window"
    /** 网页:网站不支持内置浏览器(要求换浏览器或下载 App)。 */
    const val UNSUPPORTED_BROWSER = "unsupported_browser"

    private val CAPTCHA = listOf(
        "captcha", "人机验证", "安全验证", "滑块验证", "拖动滑块", "向右滑动完成", "请完成验证", "verify you are human", "not a robot",
    )
    private val PAYMENT = listOf(
        "支付密码", "确认支付", "确认付款", "立即支付", "输入密码支付", "payment password", "confirm payment", "pay now",
    )
    private val SMS_CODE = listOf(
        "获取验证码", "发送验证码", "短信验证码", "get code", "send code", "verification code",
    )

    /** 屏上文字里第一个付款字样(确认支付、支付密码一类);没有为 null。 */
    fun paymentText(texts: List<String>): String? {
        val text = texts.joinToString("\n").lowercase()
        return PAYMENT.firstOrNull { it in text }
    }

    fun classify(f: ScreenFacts): ScreenCheck {
        val text = f.texts.joinToString("\n").lowercase()
        fun hit(words: List<String>) = words.firstOrNull { it in text }
        fun human(reason: String, cause: String) = ScreenCheck(ScreenState.NEEDS_HUMAN, f.currentApp, true, reason, cause)

        // 页面切换时画面也可能短暂全黑:界面还在变就按加载中处理
        if (f.changing && f.blackFrames.any { it }) {
            return ScreenCheck(ScreenState.LOADING, f.currentApp, false, cause = "black frame while the screen keeps changing")
        }
        val human = when {
            hit(CAPTCHA) != null -> "captcha" to "captcha text: ${hit(CAPTCHA)}"
            hit(PAYMENT) != null -> "payment" to "payment text: ${hit(PAYMENT)}"
            f.passwordField -> "login" to "password field"
            hit(SMS_CODE) != null -> "login" to "verification code text: ${hit(SMS_CODE)}"
            else -> null
        }
        // 两次安静时都全黑才算防截屏,一次黑可能只是还没画出来
        if (f.hasWindow && f.blackFrames.size >= 2 && f.blackFrames.all { it }) {
            return ScreenCheck(ScreenState.PROTECTED, f.currentApp, true, human?.first ?: "other", "secure window: the frame stays black")
        }
        human?.let { (reason, cause) -> return human(reason, cause) }
        if (f.changing) return ScreenCheck(ScreenState.LOADING, f.currentApp, false, cause = "screen keeps changing")
        if (!f.hasWindow || f.readableElements == 0) {
            val cause = when {
                f.hasWindow -> "no readable elements"
                f.anyWindow || f.foregroundApp != null -> UNREADABLE_WINDOW
                else -> NO_WINDOW
            }
            return ScreenCheck(ScreenState.BLANK, f.currentApp ?: f.foregroundApp, false, cause = cause)
        }
        return ScreenCheck(ScreenState.NORMAL, f.currentApp, false)
    }
}
