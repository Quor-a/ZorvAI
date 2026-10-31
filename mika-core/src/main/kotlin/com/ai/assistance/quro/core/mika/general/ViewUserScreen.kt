package com.ai.assistance.quro.core.mika.general

import com.ai.assistance.quro.core.mika.observation.ObsKind
import com.ai.assistance.quro.core.mika.observation.Observation
import com.ai.assistance.quro.core.mika.observation.Place
import com.ai.assistance.quro.core.mika.observation.Trust
import com.ai.assistance.quro.core.mika.observation.Untrusted
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.tool.AgentTool
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.Concurrency
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.fail
import com.ai.assistance.quro.core.mika.tool.okObj
import com.ai.assistance.quro.core.mika.tool.str
import com.ai.assistance.quro.core.mika.userscreen.UserScreen
import com.ai.assistance.quro.core.mika.userscreen.UserScreenMessage
import com.ai.assistance.quro.core.mika.userscreen.UserScreenPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.put
import java.time.ZoneId

const val VIEW_USER_SCREEN = "view_user_screen"

/**
 * 再看用户发来的一屏(按编号,这个对话里的都行):App、时刻、屏上的字;主模型看得到图时附上截图,
 * 看不到图而有看图模型时,带着问题问看图模型那张截图(和读屏用的是同一个看图模型,只是看的是存下的那张,不是此刻的屏)。
 * 只在这次的话或之前几轮里带着用户的屏幕时提供。
 */
internal object ViewUserScreen : AgentTool {
    override val verb = Verb.ViewUserScreen
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef(VIEW_USER_SCREEN,
        Prompts.tool(VIEW_USER_SCREEN),
        mapOf(
            "id" to str("The screen's id, as given with the screen (Screen id) or in the earlier turns."),
            "question" to str("What you want to know about it, 1-300 characters. Needed when you cannot see images: the vision model answers it from the screenshot."),
        ), listOf("id"))

    private val ROLE: String by lazy { Prompts.role("user_screen") }

    override fun available(ctx: ToolContext): Boolean {
        if (ctx.config.userScreens == null) return false
        val spec = ctx.config.runSpec ?: return false
        return UserScreenMessage.has(spec.goal) ||
            spec.priorTurns.any { t -> UserScreenMessage.has(t.userText) || t.steers.any(UserScreenMessage::has) }
    }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val id = a.str("id", max = 64)!!.trim()
        val question = a.str("question", max = 300)?.trim()
        val source = ctx.config.userScreens!!
        val screen = read { source.screen(id) } ?: return fail("screen_not_found", Feedback["user_screen_not_found"])
        val zone = ZoneId.systemDefault()
        if (screen.blocked) return ToolExecution(okObj {
            put("id", id); put("app", screen.app); put("time", UserScreenPrompt.time(screen.takenAt, zone)); put("blocked", true)
            put("message", Feedback["user_screen.blocked_reply"])
        })
        val png = read { source.image(id) }
        val sees = ctx.config.visionEnabled && png != null
        val vision = ctx.config.vision
        val answer = if (!sees && question != null && png != null && vision != null) read { vision.ask(ROLE, question, listOf(png)) } else null
        val text = textOf(screen)
        val content = okObj {
            put("id", id); put("app", screen.app); screen.packageName?.let { put("package", it) }
            put("time", UserScreenPrompt.time(screen.takenAt, zone))
            put("text", Untrusted.wrap(text))
            if (sees) put("imageAttached", true)
            answer?.let { put("seen", Untrusted.wrap(it)) }
            when {
                sees || answer != null -> Unit
                png == null -> put("note", Feedback["user_screen_no_image"])
                vision == null -> put("note", Feedback["user_screen_text_only"])
                question == null -> put("note", Feedback["user_screen_ask"])
                else -> put("note", Feedback["user_screen_vision_failed"])
            }
        }
        val seen = Observation(ObsKind.Read, listOfNotNull(text, answer).joinToString("\n"),
            place = screen.packageName?.let(Place::app), trust = Trust.Untrusted)
        return ToolExecution(content, images = if (sees) listOf(png!!) else emptyList(), observations = listOf(seen))
    }

    /** 屏上的字,一行一个,前面是中心点。 */
    fun textOf(screen: UserScreen): String = screen.lines.joinToString("\n") { "(${it.x},${it.y}) ${it.text}" }

    private inline fun <T> read(block: () -> T?): T? =
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
}
