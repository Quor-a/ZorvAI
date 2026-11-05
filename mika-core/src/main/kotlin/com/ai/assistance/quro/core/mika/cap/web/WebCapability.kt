package com.ai.assistance.quro.core.mika.cap.web

import com.ai.assistance.quro.core.mika.capability.Capability
import com.ai.assistance.quro.core.mika.capability.CapabilityKey
import com.ai.assistance.quro.core.mika.capability.CapabilityStatus
import com.ai.assistance.quro.core.mika.capability.PromptEnv
import com.ai.assistance.quro.core.mika.uistate.Surface
import com.ai.assistance.quro.core.mika.observation.Look
import com.ai.assistance.quro.core.mika.net.UrlHost
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.uistate.ScreenPort
import com.ai.assistance.quro.core.mika.uistate.ScreenSnapshot
import com.ai.assistance.quro.core.mika.uistate.ActionResult
import com.ai.assistance.quro.core.mika.tool.AgentTool
import com.ai.assistance.quro.core.mika.tool.ArgError
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.workspace.WorkspacePathCheck
import kotlinx.coroutines.CancellationException
import com.ai.assistance.quro.core.mika.uistate.sessions
import com.ai.assistance.quro.core.mika.uistate.DwellStop
import com.ai.assistance.quro.core.mika.uistate.IdleScroll
import com.ai.assistance.quro.core.mika.uistate.PlacePace

private const val EVIDENCE_PAGE_CHARS = 3000
private val HOST_RE = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$")

/**
 * 内置浏览器:网页那一套屏幕工具,再加打开网页、读页、页面脚本、下载上传;接了网页端口([port])才提供工具。
 * [workspacePath] 是上传时的工作区路径检查(规范化、符号链接解析由 app 侧做)。
 * [pacing] 是一页一页看的节奏(见 [PagePacing]):换页的动作之前补足这一页的停留;[sitePace] 按主机名给出站点知识对浏览快慢的说法;
 * [idle] 给了时停留里轻轻滚几下页面(见 [IdleScroll])。
 */
internal class WebCapability(
    val port: WebPort?,
    val workspacePath: ((String) -> WorkspacePathCheck)?,
    pacing: PagePacing = PagePacing.Off,
    sitePace: (String) -> PlacePace = { PlacePace.NORMAL },
    idle: IdleScroll? = null,
) : Capability {
    override val id = WEB

    val dwell = PageDwell(pacing, sitePace, idle)

    override val folds = true

    override fun status(): CapabilityStatus = if (port != null) CapabilityStatus.Available else CapabilityStatus.Unavailable("no browser")

    override fun tools(): List<AgentTool> = WebGroup.tools

    /** 这次的话是从浏览器页上对着一页问的(带着 [AttachedPage] 的说明):那一页已经是当前标签,开局就展开网页一组。 */
    override fun preload(task: String): Boolean = port != null && AttachedPage.has(task)

    /** 页面说明里的标题、网址和选中的字来自网页,去掉后剩下用户打的字。 */
    override fun userWords(text: String): String = AttachedPage.words(text)

    override val family = WEB_FAMILY

    override val surface = port?.let { p ->
        Surface(WEB_FAMILY, WebScreen(p, dwell), declaredTarget = ::declaredHost, targetNote = "the site host shown as currentSite (e.g. shop.example.com)",
            mode = { siteMode(p) })
    }

    /** 网页那一组收着时不给。 */
    override fun sections(env: PromptEnv): List<String> =
        if (env.isOpen(id) && WebGroup.tools.any { it.name in env.offered }) listOf(env.gate(Prompts.capability(WEB))) else emptyList()

    override fun onTaskStart(ctx: ToolContext) {
        ctx[KEY] = this
        dwell.onWait = { ctx.pacedWaitMs += it }
        dwell.onIdleScroll = { ctx.idleScrolls += it }
        dwell.afterIdle = { ctx.sessions[id]?.reprobe() }
    }

    /** 用户答过话:同一网站的页数上限重新算(用户答应了接着看)。 */
    override fun onUserAnswered(ctx: ToolContext) = dwell.userAnswered()

    /** 网页上的对外动作:做的这一次不受页数上限拦;做成之后这个网站的页数上限不再算(成批联系、投递做到网站自己的上限为止)。 */
    override fun onOutwardStart(ctx: ToolContext, site: String) = dwell.outwardStart(site)

    override fun onOutwardEnd(ctx: ToolContext, site: String, done: Boolean) = dwell.outwardEnd(site, done)

    /** 再看一眼:此刻这一页的界面状态,另附整页正文的开头(界面状态只有视口里的元素)。 */
    override suspend fun freshLook(ctx: ToolContext, withImage: Boolean): Look? {
        val p = port ?: return null
        return ctx.sessions[id]?.freshLook(withImage) { pageStart(p)?.let { webRead(it, null) } }
    }

    /** 确认申报的 app 在网页上是站点主机名(国际化域名按 ASCII 形式),和网页读屏的 packageName 一致。 */
    private fun declaredHost(app: String): String {
        val host = app.takeIf { it.isNotEmpty() && it.none { c -> c in "/:?#@\\ " } }?.let { UrlHost.of("https://$it") }
        if (host == null || !HOST_RE.matches(host))
            throw ArgError("app", "on the web surface, must be the site host shown as currentSite, e.g. shop.example.com")
        return host
    }

    /** 整页正文的开头。 */
    private suspend fun pageStart(p: WebPort): String? {
        val text = try { p.readPage(EVIDENCE_PAGE_CHARS, 0, scrollLazy = false).text } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        return text?.takeIf { it.isNotBlank() }?.let { "网页正文(开头):\n$it" }
    }

    /** 当前标签此刻用的版本(mobile / desktop);读不到为 null。 */
    private suspend fun siteMode(p: WebPort): String? = try { p.siteMode() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    /**
     * 网页那一套屏幕工具读屏与动作经它:返回键是页面的后退;读屏的包名换成站点主机名
     * (确认关卡按它核对「申报的是不是眼前这个」)。读屏时把地址交给 [dwell] 认新的一页;
     * 可能换页的动作(点击、后退、按键、往下滚、滑动)之前先补足这一页的停留,打字、清空、往上滚不等。
     * 按编号点、后退、按键、往下滚不按屏幕坐标做,停留里可以轻轻滚几下页面;按坐标点、滑动的停留里不滚(坐标会对不上)。
     * 这个网站本任务里的页到了上限时,除往下滚之外的这些动作都不做,报 page_limit。
     */
    private class WebScreen(private val port: WebPort, private val dwell: PageDwell) : ScreenPort by port {
        override suspend fun back(): ActionResult = paced(idle = true) { port.historyBack() }
        override suspend fun snapshot() = port.snapshot().withSite()
        override suspend fun peek() = port.peek().withSite()
        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult = paced(idle = true) { port.tapRef(ref, snapshotId) }
        override suspend fun tapXY(x: Int, y: Int): ActionResult = paced(idle = false) { port.tapXY(x, y) }
        override suspend fun doubleTapRef(ref: String, snapshotId: String): ActionResult = paced(idle = true) { port.doubleTapRef(ref, snapshotId) }
        override suspend fun doubleTapXY(x: Int, y: Int): ActionResult = paced(idle = false) { port.doubleTapXY(x, y) }
        override suspend fun pressKey(keyCode: Int): ActionResult = paced(idle = true) { port.pressKey(keyCode) }
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult =
            paced(idle = false) { port.swipe(x1, y1, x2, y2, durationMs) }

        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult {
            if (!down) return port.scroll(ref, snapshotId, false)
            // 往下滚是读这一页余下的内容:到了页数上限也照滚;停留中用户插了话就不滚
            try { dwell.before(port = port, gate = false) } catch (e: DwellStop) { return ActionResult.Failed(e.code, e.detail) }
            val mark = dwell.scrollMark(port)
            return port.scroll(ref, snapshotId, true).also { dwell.afterScroll(port, mark) }
        }

        /** 先补足停留(到了页数上限、停留中用户插了话就不做,报 page_limit 或 skipped_for_new_message),再做 [action]。 */
        private suspend fun paced(idle: Boolean, action: suspend () -> ActionResult): ActionResult {
            try {
                dwell.before(port = port.takeIf { idle })
            } catch (e: DwellStop) {
                return ActionResult.Failed(e.code, e.detail)
            }
            return action()
        }

        private fun ScreenSnapshot.withSite() = web?.let { w ->
            dwell.seen(w.url)
            copy(packageName = UrlHost.of(w.url) ?: packageName)
        } ?: this
    }

    companion object {
        val KEY = CapabilityKey<WebCapability>(WEB)
    }
}

/** 本任务的网页端口;没有网页为 null。 */
internal val ToolContext.web: WebPort? get() = this[WebCapability.KEY]?.port

/** 本任务的一页一页的停留;没有网页能力为 null。 */
internal val ToolContext.dwell: PageDwell? get() = this[WebCapability.KEY]?.dwell

internal val ToolContext.workspacePath: ((String) -> WorkspacePathCheck)? get() = this[WebCapability.KEY]?.workspacePath
