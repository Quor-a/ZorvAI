package com.ai.assistance.quro.core.mika.uistate.tools

import com.ai.assistance.quro.core.mika.uistate.ScreenFamily
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.uistate.PressKeys
import com.ai.assistance.quro.core.mika.tool.AgentTool
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.uistate.sessions

/**
 * 一套屏幕工具里的一个:名字是 `<屏>_<动词>`,只作用在 [family] 那块屏上(分发器在调用前记下它)。
 * 说明用共用模板 prompts/tools/<动词>.md(这块屏有自己的一份时用 <屏>_<动词>.md),模板里点名的工具都换成这一套的名字。
 */
internal abstract class ScreenTool(val family: ScreenFamily, val base: String) : AgentTool {
    final override val name: String = family.tool(base)
    override val capability: String get() = family.id
    /** 手机这一套里结果是文字的可从沙箱调;看图的(图片结果、要视觉模型)不开放。 */
    override val sandboxCallable: Boolean get() = family.sandboxCallable && verb !in IMAGE_VERBS
    override val promptFile: String get() = if (base in family.ownPrompts) name else base

    /** 这块屏接上了端口才提供;[usable] 是各工具另外的条件。 */
    final override fun available(ctx: ToolContext): Boolean = family.id in ctx.sessions && usable(ctx)

    protected open fun usable(ctx: ToolContext): Boolean = true

    /** 说明全文:模板里的 {{动词}} 换成这一套里的工具名,{{keys}} 是这块屏能按的键。 */
    protected fun describe(vararg vars: Pair<String, Any?>): String =
        Prompts.tool(promptFile, *(MIRRORED.map { it to family.tool(it) } + ("keys" to keyNames(family)) + vars).toTypedArray())

    override fun toString() = name

    companion object {
        private val IMAGE_VERBS = setOf(Verb.Screenshot, Verb.Describe, Verb.FindVisually)

        /** 每块屏都有的动词,按发给模型的顺序。 */
        val MIRRORED = listOf(
            "observe", "list_elements", "screenshot", "describe", "find_visually",
            "tap", "scroll", "swipe", "type_text", "clear_text", "back", "press_key", "diagnose",
        )

        /** 一块屏上 press_key 能按的键名。 */
        fun keyNames(f: ScreenFamily): String =
            PressKeys.BY_NAME.filterValues { f.keys == null || it in f.keys }.keys.joinToString()

        /** 一块屏的那一套屏幕工具,按 [MIRRORED] 的顺序。 */
        fun of(f: ScreenFamily): List<AgentTool> = listOf(
            Observe(f), ListElements(f), Screenshot(f), Describe(f), FindVisually(f),
            Tap(f), Scroll(f), Swipe(f), TypeText(f), ClearText(f), Back(f), PressKey(f), Diagnose(f),
        ).also { tools -> check(tools.map { it.base } == MIRRORED) }
    }
}
