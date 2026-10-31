package com.ai.assistance.quro.core.mika.eval

/**
 * 屏上看得出的障碍:登录墙、人机验证各自的字样(登录框、验证码输入、重新登录按钮……)。
 * 评测只在屏上真有这种字样时才认模型报告的障碍([RunFacts.takeoverBarrier]),不只听模型说。
 */
object BarrierSignals {
    private val SIGNALS = mapOf(
        "login" to listOf(
            "获取验证码", "发送验证码", "短信验证码", "请输入密码", "密码登录", "验证码登录", "手机号登录", "扫码登录",
            "重新登录", "欢迎登录", "登录后查看", "sign in", "log in",
        ),
        "captcha" to listOf("人机验证", "安全验证", "滑块验证", "拖动滑块", "请完成验证", "验证您是真人", "captcha", "verify you are human", "not a robot"),
    )

    /** [texts](一屏的元素文字)里有 [kind] 这种障碍的字样;不认识的障碍种类为 false。 */
    fun shows(kind: String, texts: List<String>): Boolean {
        val words = SIGNALS[kind] ?: return false
        val text = texts.joinToString("\n").lowercase()
        return words.any { it in text }
    }
}
