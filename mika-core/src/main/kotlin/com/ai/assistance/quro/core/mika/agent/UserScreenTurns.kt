package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.model.Part
import com.ai.assistance.quro.core.mika.userscreen.UserScreen
import com.ai.assistance.quro.core.mika.userscreen.UserScreenMessage
import com.ai.assistance.quro.core.mika.userscreen.UserScreenPrompt
import com.ai.assistance.quro.core.mika.userscreen.UserScreenSource
import kotlinx.coroutines.CancellationException
import java.time.ZoneId

/**
 * 一个任务里用户发来的屏幕:读出说明和截图,把带屏幕的那句话换成给模型的样子(见 [UserScreenPrompt])。
 * 这一句(开局原话、插话)里的屏幕给全:那句话、截图([seesImages] 时)、详情;之前几轮里的只剩一行。
 * 没有 [source](这个入口没有用户屏幕)时那一行原样留着。
 */
internal class UserScreenTurns(
    private val source: UserScreenSource?,
    private val zone: ZoneId,
    private val seesImages: Boolean,
    /** 能问看图模型(有 view_user_screen 又有看图模型、主模型看不到图)时详情里说一声。 */
    private val canAskVision: Boolean,
    private val image: (ByteArray) -> Part.Image,
    /** 这一句里给全的一屏(记进证据账本)。 */
    private val seen: (UserScreen) -> Unit = {},
) {
    /**
     * 换好的一句:[said] 是用户这句话(给模型的原话),[images] 接在它里面,[details] 是紧跟着的详情(来源 → 正文);
     * [words] 是用户自己打的字(记忆引文、意图判断只看它)。
     */
    class Rendered(val said: String, val images: List<Part>, val details: List<Pair<String, String>>, val words: String)

    private val cache = HashMap<String, UserScreen?>()

    /** 这句话带着屏幕时换成给模型的样子;不带时为 null。 */
    suspend fun render(raw: String): Rendered? {
        val p = UserScreenMessage.parse(raw)
        if (!p.hasScreen) return null
        val screens = p.screens.map { it to load(it) }
        screens.forEach { (_, s) -> s?.let(seen) }
        val said = screens.mapIndexed { i, (id, s) -> UserScreenPrompt.said(id, s, if (i == screens.lastIndex) p.words else "", zone) }
            .joinToString("\n")
        val images = if (!seesImages) emptyList() else screens.mapNotNull { (id, s) ->
            if (s == null || s.blocked) null else read { source?.image(id) }?.let(image)
        }
        val details = screens.mapNotNull { (_, s) ->
            s?.let { "user's screen · ${it.app}" to UserScreenPrompt.detail(it, p.words.isEmpty(), canAskVision && !it.blocked, zone) }
        }
        return Rendered(said, images, details, p.words)
    }

    /** 之前几轮里用户说的话(原话和插话):屏幕那一行收成一行说明。 */
    suspend fun collapse(turns: List<Turn>): List<Turn> {
        if (turns.none { t -> UserScreenMessage.has(t.userText) || t.steers.any(UserScreenMessage::has) }) return turns
        turns.flatMap { listOf(it.userText) + it.steers }.flatMap { UserScreenMessage.parse(it).screens }.distinct().forEach { load(it) }
        fun one(text: String) = UserScreenPrompt.collapse(text, { cache[it] }, zone)
        return turns.map { t -> t.copy(userText = one(t.userText), steers = t.steers.map(::one)) }
    }

    /** 回答里带着屏幕时(ZorvAI 问你时双击了胶囊):换成一段文字,截图要看用回看工具。 */
    suspend fun asText(raw: String): String {
        val r = render(raw) ?: return raw
        return (listOf(r.said) + r.details.map { it.second }).joinToString("\n\n")
    }

    private suspend fun load(id: String): UserScreen? = cache.getOrPut(id) { read { source?.screen(id) } }

    private inline fun <T> read(block: () -> T?): T? =
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
}
