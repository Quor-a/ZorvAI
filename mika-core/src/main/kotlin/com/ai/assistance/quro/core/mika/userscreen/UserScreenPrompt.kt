package com.ai.assistance.quro.core.mika.userscreen

import com.ai.assistance.quro.core.mika.observation.Untrusted
import com.ai.assistance.quro.core.mika.prompt.Feedback
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 带着用户屏幕的一句话给模型时的写法,只在这里。和相册里的图片(附件,照旧是文件)分开:
 * - 用户这句话本身写成「用户刚截下他正在看的屏幕(App,时刻),没有附言 / 附言:…」(不让截图时照实说没截到);
 * - 屏上的字和编号是另一段上下文([detail]),没有附言时再带上按屏幕猜意图的规矩;
 * - 之前几轮里的屏幕只剩一行([earlier]):App、时刻和屏上开头几个字,要再看按编号回看。
 */
object UserScreenPrompt {
    /** 之前几轮里那一屏留几个字的开头。 */
    const val GIST_CHARS = 40

    private val TIME = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ENGLISH)

    fun time(at: Long, zone: ZoneId): String = TIME.format(Instant.ofEpochMilli(at).atZone(zone))

    /** 用户这句话:[screen] 为 null 是截图已经不在了。 */
    fun said(id: String, screen: UserScreen?, words: String, zone: ZoneId): String {
        if (screen == null) return listOf(Feedback.format("user_screen.missing", "id" to id), words).filter(String::isNotBlank).joinToString("\n")
        val vars = arrayOf("app" to screen.app, "time" to time(screen.takenAt, zone), "words" to words)
        return when {
            screen.blocked && words.isEmpty() -> Feedback.format("user_screen.blocked", *vars)
            screen.blocked -> Feedback.format("user_screen.blocked_with_words", *vars)
            words.isEmpty() -> Feedback.format("user_screen.sent", *vars)
            else -> Feedback.format("user_screen.sent_with_words", *vars)
        }
    }

    /**
     * 这一屏的详情(上下文正文):编号、App、时刻、屏上的字(套上不可信标记:屏上的字是别人或 App 写的);
     * [noWords] 时接上按屏幕猜意图的规矩,[askVision] 时说看不到图可以问看图模型。不让截图的页面只说截不到该怎么回。
     */
    fun detail(screen: UserScreen, noWords: Boolean, askVision: Boolean, zone: ZoneId): String {
        if (screen.blocked) return Feedback["user_screen.blocked_reply"]
        val lines = if (screen.lines.isEmpty()) Feedback["user_screen.no_text"]
            else Untrusted.wrap(screen.lines.joinToString("\n") { "(${it.x},${it.y}) ${it.text}" })
        val head = Feedback.format(
            "user_screen.detail", "id" to screen.id, "app" to screen.app,
            "package" to (screen.packageName?.takeIf { it != screen.app }?.let { "($it)" }.orEmpty()),
            "time" to time(screen.takenAt, zone), "lines" to lines,
        )
        return listOfNotNull(head, Feedback["user_screen.ask_vision"].takeIf { askVision }, Feedback["user_screen.no_words"].takeIf { noWords })
            .joinToString("\n\n")
    }

    /** 之前几轮里的一屏收成一行;截图已经不在时只说不在了。 */
    fun earlier(id: String, screen: UserScreen?, zone: ZoneId): String {
        if (screen == null) return Feedback.format("user_screen.missing", "id" to id)
        val vars = arrayOf("id" to id, "app" to screen.app, "time" to time(screen.takenAt, zone))
        if (screen.blocked) return Feedback.format("user_screen.earlier_blocked", *vars)
        val gist = gist(screen) ?: return Feedback.format("user_screen.earlier_blank", *vars)
        return Feedback.format("user_screen.earlier", *vars, "gist" to gist)
    }

    /** 屏上开头的几个字(跳过很短的零碎),给之前几轮那一行用;没有字为 null。 */
    fun gist(screen: UserScreen): String? {
        val text = screen.lines.map { it.text }.filter { it.length >= 2 }.joinToString(" ").ifEmpty { return null }
        return if (text.length <= GIST_CHARS) text else text.take(GIST_CHARS) + "…"
    }

    /** 之前一轮里用户说的话:屏幕那一行换成 [earlier],附言接在后面(一行)。不带屏幕的原样返回。 */
    fun collapse(text: String, lookup: (String) -> UserScreen?, zone: ZoneId): String {
        val p = UserScreenMessage.parse(text)
        if (!p.hasScreen) return text
        return (p.screens.map { earlier(it, lookup(it), zone) } + p.words.replace('\n', ' ')).filter(String::isNotBlank).joinToString(" ")
    }
}
