package com.ai.assistance.quro.core.mika.cap.web

import com.ai.assistance.quro.core.mika.uistate.ScreenFamily
import com.ai.assistance.quro.core.mika.uistate.tools.ScreenTool
import com.ai.assistance.quro.core.mika.tool.AgentTool
import com.ai.assistance.quro.core.mika.tool.ToolContext

/** 网页专属的工具:只有接了网页端口时才提供。 */
internal interface WebTool : AgentTool {
    override val capability: String get() = WEB
    override fun available(ctx: ToolContext) = ctx.web != null
}

/** 网页能力的 id,也是网页那一套工具名字的前缀。 */
const val WEB = "web"

/** 网页上 press_key 只接受这些键:回车、退格、删除、Tab、方向键、翻页键。 */
private val KEYS = setOf(66, 160, 67, 112, 61, 19, 20, 21, 22, 92, 93)
private const val KEYS_HINT = "on a web page only Enter, Backspace, Delete, Tab, arrow and page keys are allowed"

/** 网页那一套屏幕工具:读屏编号以 w 开头,「在哪」报站点;经验路线不记网页上的做法。 */
internal val WEB_FAMILY = ScreenFamily(
    WEB, snapshotPrefix = "w", placeKey = ScreenFamily.SITE_PLACE, keys = KEYS, keysHint = KEYS_HINT,
    ownPrompts = setOf("observe", "back"), emptyHint = "empty_page",
)

/** 网页一组:那一套屏幕工具,再加网页专属的导航、读页、标签、下载上传、脚本、等待。按发给模型的顺序。 */
internal object WebGroup {
    val tools: List<AgentTool> = ScreenTool.of(WEB_FAMILY) + listOf(
        Navigate, ReadPage, EvaluateJs, RunSiteCommand, Tabs, Downloads, Upload, SiteMode, WebScriptTool, Interact, Capture, WebWaitFor,
    )
}
