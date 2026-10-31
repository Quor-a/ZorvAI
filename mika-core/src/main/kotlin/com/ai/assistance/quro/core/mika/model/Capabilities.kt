package com.ai.assistance.quro.core.mika.model

data class Capabilities(
    val toolCalls: Boolean,
    val vision: Boolean,
    val contextTokens: Int,
    val reasoning: Boolean,
    /**
     * 看图定位(在截图上框出元素)的准度已经过验证。没验证的模型框出来的点,find_visually 要另有佐证才交出去。
     */
    val groundingVerified: Boolean = false,
    /** 服务端怎样复用相同的提示词开头(省钱、提速);由适配器按它决定请求里带不带缓存标记。 */
    val promptCaching: PromptCaching = PromptCaching.Implicit,
    /** 服务商明确公布的一次请求图片上限;未知时不猜。 */
    val maxImages: Int = Int.MAX_VALUE,
)

/**
 * [Implicit]:服务端自动按相同开头复用,请求里不用标。[Explicit]:要在消息上标出缓存到哪里(cache_control),
 * 不标就不复用。[None]:不复用。
 */
enum class PromptCaching(val wire: String) {
    Implicit("implicit"), Explicit("explicit"), None("none");

    companion object {
        fun of(wire: String?): PromptCaching = entries.firstOrNull { it.wire == wire } ?: Implicit
    }
}

enum class ModelRole { Main, Vision, Light }
