package com.ai.assistance.quro.core.mika.observation

/**
 * 不可信的内容(网页的正文与脚本结果、网络响应、别的 AI 的回答、渠道里的话)交给模型时套的固定标记。
 * 只在能力造这条观察、写工具结果的地方套一次,之后的裁剪、压缩都不再套;标记的意思在系统提示词里说一次。
 * 正文里的结束标记转义掉,正文提前合不上这一层。账本里记的是没套标记的原文。
 */
object Untrusted {
    const val OPEN = "<untrusted>"
    const val CLOSE = "</untrusted>"
    const val OVERHEAD = 2 + OPEN.length + CLOSE.length

    /** 套上标记;空的原样返回。 */
    fun wrap(text: String): String = if (text.isEmpty()) text else "$OPEN\n" + text.replace(CLOSE, "<\\/untrusted>") + "\n$CLOSE"

    /** 包裹后的整个字段仍须满足工具声明的长度上限。 */
    fun wrapWithin(text: String, maxChars: Int): String {
        require(maxChars >= OVERHEAD)
        val escaped = text.replace(CLOSE, "<\\/untrusted>")
        return if (escaped.isEmpty()) escaped else "$OPEN\n${escaped.take(maxChars - OVERHEAD)}\n$CLOSE"
    }
}
