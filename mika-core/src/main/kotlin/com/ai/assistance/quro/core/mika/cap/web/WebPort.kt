package com.ai.assistance.quro.core.mika.cap.web

import com.ai.assistance.quro.core.mika.uistate.KeyCombo
import com.ai.assistance.quro.core.mika.uistate.ActionResult
import com.ai.assistance.quro.core.mika.uistate.ScreenPort
import kotlinx.serialization.json.JsonObject
import com.ai.assistance.quro.core.mika.uistate.PageDialog

data class TabInfo(val tabId: String, val url: String, val title: String, val current: Boolean)

/**
 * [externalLink]:页面要跳到 App(intent:/自定义协议/应用商店),没有跳。
 * [settled]:有 DevTools 时,加载后等到了网络空闲且页面安静为 true,等到上限还没静下来为 false;没有 DevTools 为 null。
 * [netError]:主文档没打开时浏览器给的错误码(如 net::ERR_NAME_NOT_RESOLVED);打开了为 null。
 * [upgradedFrom]:明文 http 被拒后改用 https 重试时,原来的 http 网址。
 * [download]:网址其实是个文件,这次打开变成了下载;它的下载记录。
 */
data class NavResult(
    val tab: TabInfo,
    val loaded: Boolean,
    val httpStatus: Int?,
    val newTab: Boolean,
    val externalLink: ExternalLink?,
    val settled: Boolean? = null,
    val netError: String? = null,
    val upgradedFrom: String? = null,
    val download: DownloadInfo? = null,
    /** 加载中页面弹了框,还在等回话(加载等待在它出现时就停了)。 */
    val dialog: PageDialog? = null,
)

/**
 * 整页正文的一段;[links]、[images] 为 (文字或说明, 地址);[nextOffset] 为下一段的起点,读完为 null。
 * [quality] 是读页的质量信号(按 [PageRead] 读时有);[netError] 是在后台标签里打开网址失败时浏览器给的错误码。
 */
data class PageText(
    val url: String,
    val title: String,
    val text: String,
    val links: List<Pair<String, String>>,
    val images: List<Pair<String, String>>,
    val truncated: Boolean,
    val nextOffset: Int?,
    val quality: ReadQuality? = null,
    val netError: String? = null,
)

/** [file] 为沙箱路径 /workspace/downloads/<名字>;[status] 为 done|failed|running。 */
data class DownloadInfo(val file: String, val bytes: Long, val mime: String?, val status: String, val error: String?)

/**
 * web_keep_open 的结果:[keep] 为 true 时这一页([tabId]、[url]、[title]、站点 [host])在任务结束后留在后台接着运行,
 * 至多 [hours] 小时;[replaces] 是此刻留着的另一页的站点(任务结束时它会被关掉),没有为 null。[keep] 为 false 时撤销了这个任务里的留页。
 */
data class KeepOpenResult(
    val keep: Boolean,
    val tabId: String?,
    val url: String?,
    val title: String?,
    val host: String?,
    val hours: Int,
    val replaces: String? = null,
)

/** 当前标签是用户自己在 ZorvAI 浏览器里看着的那一页(借来的):它本来就不随任务关,用不着留。 */
class WebTabIsUsers(val tabId: String) : Exception("tab $tabId is the user's own page")

/** web_site_mode 的结果:[host] 当前页面的站点,[mobile] 现在用手机版,[reloaded] 当前页已按新版本重新加载。 */
data class SiteModeResult(val host: String, val mobile: Boolean, val reloaded: Boolean)

sealed interface EvalJs {
    /** [json] 是脱敏、截断后的结果;[fresh]:这次新建了标签的读取世界,之前存在 state 里的都没了。 */
    data class Ok(val json: String, val truncated: Boolean, val fresh: Boolean = false) : EvalJs

    /**
     * [error] 为 script_error|timeout|read_only(脚本想改页面)|invalid_selector|blocked_network|blocked_navigation|
     * limit_exceeded|network_error|too_large|navigated(页面换了文档)|devtools_unavailable|no_tab_open。
     */
    data class Failed(val error: String, val message: String) : EvalJs
}

/** web_wait_for 的条件;[wire] 是工具参数里的写法。 */
enum class WaitKind(val wire: String) {
    TEXT_PRESENT("text_present"), TEXT_ABSENT("text_absent"), NETWORK_IDLE("network_idle"), URL_CONTAINS("url_contains");

    companion object {
        fun of(wire: String): WaitKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** [met]:条件在时限内成立;[url] 为结束时的地址。 */
data class WaitOutcome(val met: Boolean, val waitedMs: Long, val url: String?)

/** 当前标签没有 DevTools 会话(系统不支持、连不上):要用网络记录的工具结果是 devtools_unavailable。 */
class DevtoolsUnavailable : Exception("devtools unavailable")

/** 网页标签:在手机读屏/操作的同一套接口上,加网页独有的导航、读页、标签、下载上传。 */
interface WebPort : ScreenPort {
    suspend fun navigate(url: String, newTab: Boolean): NavResult
    /** [scrollLazy]:读之前把懒加载内容滚动触发一遍(会动页面);核查取证时为 false,只读不滚。 */
    suspend fun readPage(maxChars: Int, offset: Int, scrollLazy: Boolean = true): PageText

    /**
     * web_read_page 的读法:带质量信号、折叠列表块;[PageRead.url] 给了时在临时后台标签里打开、读完关掉,不动当前标签。
     * 不支持后台读时给了 url 抛 [UnsupportedOperationException]。
     */
    suspend fun read(req: PageRead): PageText =
        if (req.url == null) readPage(req.maxChars, req.offset, req.lazy) else throw UnsupportedOperationException("background reads are not available")
    /**
     * 读脚本:在当前标签真实页面的读取世界里只读地跑 [script](见 [ReadScriptProtocol]),上限 [timeoutMs];
     * [args] 是站点命令的参数(脚本里的 args)。同一标签同一文档上的几次调用共用 state。
     * DevTools 用不了时报 devtools_unavailable,不退回别的做法。
     */
    suspend fun evaluate(script: String, timeoutMs: Long, args: JsonObject? = null): EvalJs
    suspend fun tabs(): List<TabInfo>
    suspend fun switchTab(tabId: String): TabInfo?
    suspend fun closeTab(tabId: String): Boolean
    /** 下载功能还没接入时抛 [UnsupportedOperationException],工具结果是 downloads_unavailable。 */
    suspend fun downloads(): List<DownloadInfo>
    suspend fun upload(ref: String, snapshotId: String, paths: List<String>): ActionResult
    suspend fun historyBack(): ActionResult
    /**
     * 当前标签的站点改用手机版或桌面版网页(默认桌面版),按站点记住,并重新加载当前页。
     * 没有打开的网页为 null;不支持时抛 [UnsupportedOperationException]。
     */
    suspend fun setSiteMode(mobile: Boolean): SiteModeResult? = throw UnsupportedOperationException("site mode is not available")

    /**
     * 任务结束后留下当前标签接着运行([keep] 为 false 时撤销这个任务里的留页)。默认任务结束时关掉全部标签;留下的那一页至多留
     * [KeepOpenResult.hours] 小时,全局只留一页。没有打开的网页为 null;当前标签是用户自己的页面时抛 [WebTabIsUsers];
     * 不支持时抛 [UnsupportedOperationException]。
     */
    suspend fun keepOpen(keep: Boolean, reason: String): KeepOpenResult? = throw UnsupportedOperationException("keeping a page open is not available")

    /** 当前标签此刻用的版本:`mobile` 或 `desktop`;没有打开的网页或不知道为 null。 */
    suspend fun siteMode(): String? = null

    /**
     * 在当前标签真实页面的隔离世界里跑 [code](async 函数体),`quro.*` 的调用经 [bridge] 回到 ZorvAI;上限 [timeoutMs]。
     * DevTools 用不了时报 devtools_unavailable,不退回别的做法。
     */
    suspend fun runScript(code: String, timeoutMs: Long, bridge: ScriptBridge): ScriptRun =
        ScriptRun.Failed(DEVTOOLS_UNAVAILABLE, "Page scripts need the page's DevTools connection, which is not available here.")

    // web_interact:需要 DevTools 会话的动作;连不上时返回 devtools_unavailable,不假装做了
    suspend fun hover(at: WebPoint): ActionResult = ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
    suspend fun drag(from: WebPoint, to: WebPoint, steps: Int): ActionResult = ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
    /** 原生下拉框选一项;[option] 是选项的显示文字或 value。 */
    suspend fun selectOption(ref: String, snapshotId: String, option: String): ActionResult = ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
    /** 回答当前标签上等回话的页面弹框(见 [PageDialogs]);[text] 只给输入框弹框。没有弹框报 no_dialog。 */
    suspend fun answerDialog(accept: Boolean, text: String?): ActionResult = ActionResult.Failed(NO_DIALOG)
    /** 对当前焦点按一次组合键。 */
    suspend fun pressCombo(combo: KeyCombo): ActionResult = ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
    /** 当前标签记下的 XHR/fetch 响应(不带正文),旧的在前。没有 DevTools 时抛 [DevtoolsUnavailable]。 */
    suspend fun captures(urlContains: String?, method: String?, sinceLastAction: Boolean): List<CapturedResponse> = throw DevtoolsUnavailable()

    /** 取一个响应,能取正文(JSON/文本、不太大)时带上;不在记录里为 null。没有 DevTools 时抛 [DevtoolsUnavailable]。 */
    suspend fun capture(id: String): CapturedResponse? = throw DevtoolsUnavailable()

    /** 等 [kind] 成立,至多 [timeoutMs]。要看网络的条件在没有 DevTools 时抛 [DevtoolsUnavailable]。 */
    suspend fun waitFor(kind: WaitKind, value: String?, timeoutMs: Long): WaitOutcome = throw DevtoolsUnavailable()

}

/** web_interact 的落点:读屏编号,或 view 像素坐标。 */
sealed interface WebPoint {
    data class Ref(val ref: String, val snapshotId: String) : WebPoint
    data class Xy(val x: Int, val y: Int) : WebPoint
}

const val DEVTOOLS_UNAVAILABLE = "devtools_unavailable"
const val NO_DIALOG = "no_dialog"
/** 页面有弹框在等回话:别的动作都不做,先回话。 */
const val DIALOG_OPEN = "dialog_open"

/** 当前标签正交给用户处理(接管中);返回非 ActionResult 的方法用它报,工具结果是 handed_over。 */
class WebTabHandedOver(val tabId: String) : Exception("tab $tabId is with the user")

/** 当前标签上页面弹框挂着(页面停着):要读页面、开网址的方法用它报,工具结果是 dialog_open。 */
class WebDialogOpen(val tabId: String) : Exception("tab $tabId has a page dialog open")

/** 标签的渲染进程崩溃(或被系统回收),标签已销毁;返回非 ActionResult 的方法用它报,工具结果是 tab_crashed。 */
class WebTabCrashed(val tabId: String) : Exception("tab $tabId crashed")
