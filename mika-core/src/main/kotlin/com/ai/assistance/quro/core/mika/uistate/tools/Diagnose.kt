package com.ai.assistance.quro.core.mika.uistate.tools

import com.ai.assistance.quro.core.mika.uistate.ScreenFamily
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.uistate.ScreenDiagnosis
import com.ai.assistance.quro.core.mika.uistate.ScreenState
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.HINT_PROTECTED
import com.ai.assistance.quro.core.mika.tool.HINT_UNSUPPORTED_BROWSER
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.ok
import kotlinx.serialization.json.put
import com.ai.assistance.quro.core.mika.uistate.screen
import com.ai.assistance.quro.core.mika.uistate.session

/** 诊断空白、加载中、锁屏或受保护的屏幕;要过锁屏关卡(锁着屏诊断不出东西)。 */
internal class Diagnose(f: ScreenFamily) : ScreenTool(f, "diagnose") {
    override val verb = Verb.Diagnose
    override val label = "检查屏幕"
    override val risk = Risk.Write
    override val def by lazy { ToolDef(name, describe()) }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val place = family.placeKey
        ctx.screen.checkScreen()?.let { c ->
            return ok {
                put(place, c.currentApp)
                put("state", c.state.wire)
                put("usable", c.state == ScreenState.NORMAL)
                put("needsHuman", c.needsHuman)
                c.takeoverReason?.let { put("takeoverReason", it) }
                c.cause?.let { put("cause", it) }
                when (c.state) {
                    ScreenState.NORMAL -> if (c.takeoverReason == ScreenDiagnosis.UNSUPPORTED_BROWSER) put("hint", HINT_UNSUPPORTED_BROWSER)
                    ScreenState.BLANK -> put("hint", when {
                        c.currentApp == null && c.cause == ScreenDiagnosis.NO_WINDOW -> Feedback[family.emptyHint]
                        // 前台 App 在,只是窗口读不到:不是没打开,不要再启动
                        c.currentApp != null && c.cause == ScreenDiagnosis.UNREADABLE_WINDOW -> Feedback.format("unreadable_window", "app" to c.currentApp)
                        else -> Feedback["screen.blank"]
                    })
                    ScreenState.LOADING -> put("hint", Feedback["screen.loading"])
                    ScreenState.PROTECTED -> put("hint", HINT_PROTECTED)
                    // 被限制访问:除了交给用户,还要别马上重试
                    ScreenState.NEEDS_HUMAN -> put("hint", if (c.takeoverReason == "access_blocked") Feedback["barrier.access_blocked"]
                        else Feedback.format("screen.needs_human", "reason" to c.takeoverReason))
                }
            }
        }
        val s = ctx.session.snapshot()
        val usable = s.elements.any { it.meaningful }
        return ok {
            put(place, s.packageName ?: s.unreadableApp)
            put("usable", usable)
            put("needsHuman", false)
            if (!usable) put("hint", s.unreadableApp?.let { Feedback.format("unreadable_window", "app" to it) } ?: Feedback["screen.no_elements"])
        }
    }
}
