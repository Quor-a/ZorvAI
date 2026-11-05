package com.ai.assistance.quro.core.mika.uistate

import com.ai.assistance.quro.core.mika.visual.FrameGrid
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val DOUBLE_TAP_UNSUPPORTED = "double_tap_unsupported"

/** 两次点击之间的间隔:够短才会被认成双击(安卓的双击时限是 300 毫秒)。 */
const val DOUBLE_TAP_GAP_MS = 120L

/** 滑动手势的默认时长。 */
const val DEFAULT_SWIPE_MS = 400L

/** 与 tap 的换算互逆:n = round(p*999/(size-1)),夹在 0–999。 */
fun normalize(p: Int, size: Int): Int {
    if (size <= 1) return 0
    return min(999, max(0, (p * 999.0 / (size - 1)).roundToInt()))
}

/**
 * 屏幕上的一个元素。ref 形如 "@e3";center 为像素(端口内部用,交给模型前换成 0–999);bounds 为 [l,t,r,b],已归一到 0–999。
 * [hint]:输入框里显示的是提示语/预置词(name 就是它),不是已经输入的内容。
 * [focused]:有输入焦点(回车、空格作用在它上面)。
 * [secret]:密码框。
 * [placeholder]:输入框自己的提示语(打了字之后也还在,和此刻显示的 [name] 无关);没有为 null。
 * [searchField]:控件按自己的语义就是搜索框(网页的 type=search、role=searchbox、回车键标成搜索;安卓的搜索框控件)。
 * [states]:控件此刻为真的状态([UiStates] 里的名字:选中、勾上、展开、按下、不可用);判动作效果时开关一拨就看得出。
 */
data class UiElement(
    val ref: String,
    val role: String,
    val name: String,
    val centerX: Int,
    val centerY: Int,
    val bounds: List<Int>,
    val resourceId: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val hint: Boolean = false,
    val focused: Boolean = false,
    /** 网页上页面声明给这个控件的快捷键(aria-keyshortcuts、accesskey 换成 Alt+键),写法同 web_interact 的 key。 */
    val keyShortcuts: List<String> = emptyList(),
    val secret: Boolean = false,
    val placeholder: String? = null,
    val searchField: Boolean = false,
    val states: Set<String> = emptySet(),
) {
    /** 无名字且不可交互的节点对模型没有信息量。 */
    val meaningful: Boolean get() = name.isNotBlank() || clickable || editable || scrollable
}

/** 控件状态的名字,手机与网页共用。 */
object UiStates {
    const val CHECKED = "checked"
    const val SELECTED = "selected"
    const val EXPANDED = "expanded"
    const val PRESSED = "pressed"
    const val DISABLED = "disabled"
    val ALL = setOf(CHECKED, SELECTED, EXPANDED, PRESSED, DISABLED)
}

/**
 * [changedDuringRead]:读这份列表的前后界面不一致(页面自己在动),列表可能和屏幕对不上。
 * [protected]:当前页面受系统防截屏保护,画面看不到。
 * [hasWindow]:屏上有 App 窗口(根节点读不读得到都算);为 false 且没有元素时才是真正的空屏。
 * [web]:网页读屏时的页面信息;手机读屏时为空。
 * [unreadableApp]:读不到窗口内容(安全窗口、启动画面、显示在别处),但端口按焦点窗口或最上面的任务知道前台是哪个 App 时,
 * 它的包名;读得到或不知道时为 null。有它时「没有元素」不等于「没打开 App」。
 */
data class ScreenSnapshot(
    val snapshotId: String,
    val packageName: String?,
    val elements: List<UiElement>,
    val width: Int,
    val height: Int,
    val changedDuringRead: Boolean = false,
    val protected: Boolean = false,
    val hasWindow: Boolean = true,
    val web: WebInfo? = null,
    val unreadableApp: String? = null,
    /** 手机无障碍窗口的完整可见层级;网页和测试端口可不提供。 */
    val windows: List<UiWindowTree> = emptyList(),
    /** 上一个动作过后才开到用户主屏上、读这一屏前刚挪回来的 App(见 [OpenedElsewhere]);没有为 null。 */
    val opened: OpenedElsewhere? = null,
)

/**
 * 动作让一个 App 开到了 ZorvAI 的屏以外、用户的主屏上(App 不是从自己在 ZorvAI 屏上的页面里打开它,系统就放到了主屏)。
 * [packageName] 那个 App(系统问打开哪一个时是问的那个窗口所在的包),[app] 它的显示名。
 * [pulledBack]:已挪到 ZorvAI 的屏、在最上面;为 false 时它留在用户的主屏上没动(用户正用着的那个 App,或系统在问用户)。
 * [inProfile]:这个 App 装了两份(分身、工作资料)时开的是哪一份,[MAIN_PROFILE] 本来那份、[OTHER_PROFILE] 另一份;只装了一份或看不出为 null。
 * [picker]:开出来的是系统问「用哪一个打开」的窗口(同一 App 装了两份、几个 App 都能打开等)。它从不挪:留给用户在自己屏上选,
 * 选之前任务停着等;[app] 是问的那个 App 的名字(认得出时)。
 * [closed]:用户把那个问题关了,什么也没打开。[byUser]:选出来的 App 要用户亲手处理,用户在自己屏上处理完、点了继续。
 * [needsUser]:冒到主屏上的是要用户亲手处理的弹窗(登录验证、账号确认、付款确认一类),留在主屏给他们点。
 */
data class OpenedElsewhere(
    val packageName: String, val app: String?, val pulledBack: Boolean, val inProfile: String? = null, val picker: Boolean = false,
    val closed: Boolean = false, val byUser: Boolean = false, val needsUser: Boolean = false,
) {
    /** 此刻还在用户的主屏上(用户正用着,或系统正在问用户)。 */
    val onUserScreen: Boolean get() = !pulledBack && !closed && !byUser

    fun toJson(): JsonObject = buildJsonObject {
        put("package", packageName)
        app?.let { put("app", it) }
        put("pulledBack", pulledBack)
        if (onUserScreen) put("onUserScreen", true)
        if (needsUser) put("needsUser", true)
        inProfile?.let { put("inProfile", it) }
        if (picker) put("picker", true)
        if (closed) put("closed", true)
        if (byUser) put("byUser", true)
    }

    companion object {
        const val MAIN_PROFILE = "main"
        const val OTHER_PROFILE = "other"
    }
}

/** 从当前任务的虚拟屏收到的界面事件；通知事件可以来自整台设备。 */
data class ScreenEvent(val type: String, val packageName: String?, val text: String?)

/** 本机 OCR 从当前屏幕图像读出的文字行，边界采用与无障碍元素相同的 0–999 坐标；[confidence] 是识别给的 0–1 置信度，没有为 null。 */
data class VisibleTextLine(val text: String, val bounds: List<Int>, val confidence: Float? = null)

/** 内置助手以 Android shell 身份执行命令的结果。 */
data class SystemCommandResult(val exitCode: Int, val output: String)

/** 输入后读回输入框的核对结果;读不回来(密码框、读不到节点)为 UNKNOWN。 */
enum class Verification(val wire: String) { MATCH("match"), MISMATCH("mismatch"), UNKNOWN("unknown") }

sealed interface ActionResult {
    /**
     * 动作已发出。其余字段是端口事后核实到的事实,null/false 表示没有核实或不适用:
     * [changed] 动作前后界面是否变化;[appSwitched] 前台已不是目标 App 时的当前包名;
     * [relocated] 编号已过期、按原元素特征在新界面里找回后执行,[relocatedTo] 是找回的元素现在的名字;
     * [switchedByTarget] 新前台是目标 App 在自己的任务里打开的(如分享到另一个 App),不是外来打断;[verification] 输入回读结果;
     * [hint] 端口对这次结果的补充说明(例如为什么核实不了),原样转给模型;
     * [secret] 输入的目标是密码框(运行记录据此不记原文);
     * [settled] 网页有 DevTools 时动作后等到了网络空闲且页面安静为 true,到上限还没静下来为 false,其余为 null;
     * [coveredBy] 打开的 App 已在最上面,但另一个包的窗口盖在它上面(权限弹窗、账号选择器)时那个包;
     * [chooser] 打开 App 后系统先问「打开哪一个」(同一 App 装了两份等),选好之前 App 还没到前台;
     * [opened] 这个动作让一个 App 开到了用户的主屏上(见 [OpenedElsewhere]);
     * [inProfile] 刚到前台的 App 装了两份时开的是哪一份(见 [OpenedElsewhere.inProfile]):系统问过打开哪一个之后,
     * 以及目标 App 列出过另一份、或要的就是另一份时每次都报;[profileUser] 是此刻屏上那一份所属的用户编号,
     * [requestedUser] 是打开时要的那份用户(要的是本来那份为 null)。
     */
    data class Ok(
        val changed: Boolean? = null,
        val appSwitched: String? = null,
        val relocated: Boolean = false,
        val relocatedTo: String? = null,
        val switchedByTarget: Boolean = false,
        val verification: Verification? = null,
        val hint: String? = null,
        val secret: Boolean = false,
        val settled: Boolean? = null,
        val coveredBy: String? = null,
        val chooser: LaunchChoice? = null,
        val opened: OpenedElsewhere? = null,
        val inProfile: String? = null,
        val profileUser: Int? = null,
        val requestedUser: Int? = null,
    ) : ActionResult

    /** 引用已过期时 reason 为 "ref_expired"。[hint] 是端口的补充说明(例如盖住目标的是什么),原样附在错误说明后。 */
    data class Failed(val reason: String, val hint: String? = null) : ActionResult
}

/** 打开 App 时这个 App 正被另一个任务用着(同一个 App 同时只能在一块屏上)。 */
const val APP_IN_USE_BY_OTHER_TASK = "app_in_use_by_other_task"

/** [ScreenPort.awaitAppFree] 的结果。 */
enum class AppWait {
    /** 放开了,可以打开了。 */
    Free,
    /** 等到时限仍被占着。 */
    TimedOut,
    /** 占着它的任务正在等这个任务手里的 App:等下去谁也等不到,没有等。 */
    WouldDeadlock,
    /** 这个端口不和别的任务争 App,没什么可等。 */
    Unsupported,
}

/** Agent 观察和操作手机屏幕的唯一入口,由 Android 模块实现。 */
interface ScreenPort {
    val appAccess: com.ai.assistance.quro.core.mika.access.AppAccessPolicy get() = com.ai.assistance.quro.core.mika.access.AppAccessPolicy.ALLOW

    /** 仅核对窗口归属,不读取屏幕内容。空屏可用;有窗口但归属未知时拒绝。 */
    suspend fun checkAppAccess() {}

    suspend fun snapshot(): ScreenSnapshot

    /** 读一屏但不登记编号(模型手里的 ref 不受影响);不可逆判定用。 */
    suspend fun peek(): ScreenSnapshot = snapshot()
    suspend fun screenshotPng(): ByteArray?
    /** 可选的本机文字识别；null 表示当前画面不可读或端口没有 OCR。 */
    suspend fun readVisibleText(): List<VisibleTextLine>? = null
    suspend fun tapRef(ref: String, snapshotId: String): ActionResult
    suspend fun tapXY(x: Int, y: Int): ActionResult
    /** 双击(两次点击间隔在系统认双击的时限内);不支持的端口报 double_tap_unsupported。 */
    suspend fun doubleTapRef(ref: String, snapshotId: String): ActionResult = ActionResult.Failed(DOUBLE_TAP_UNSUPPORTED)
    suspend fun doubleTapXY(x: Int, y: Int): ActionResult = ActionResult.Failed(DOUBLE_TAP_UNSUPPORTED)
    suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult
    suspend fun clearText(ref: String?, snapshotId: String?): ActionResult

    /**
     * 往这一点([x],[y] 为像素)上的输入框里打字:先点它,点出了拿着焦点的输入框就照常打,树里看不到输入框时经剪贴板粘贴、
     * 比打字前后屏上的字核对;粘贴后前后一点没变为 point_not_typed(那一点多半不是输入框,或没拿到焦点)。
     * 默认先点再按焦点打字,不另核对那一点。
     */
    suspend fun typeAt(x: Int, y: Int, text: String): ActionResult =
        tapXY(x, y).let { if (it is ActionResult.Ok) typeText(null, null, text) else it }

    /** 清空这一点上的输入框,做法同 [typeAt]。 */
    suspend fun clearAt(x: Int, y: Int): ActionResult =
        tapXY(x, y).let { if (it is ActionResult.Ok) clearText(null, null) else it }
    suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = DEFAULT_SWIPE_MS): ActionResult

    /**
     * 停留时顺手轻轻滚一下([fraction] 个屏幕或视口高度,正为往下),不点任何东西。网页走和往下滚一样的原生输入、不移动指针;
     * 手机在占了大半屏的那块可滚动内容上慢慢划一小段。这会儿不便滚(有弹框、输入法开着、输入框拿着焦点、看不到可滚的内容)时不滚,
     * 返回 false;滚了为 true。默认不滚。
     */
    suspend fun idleScroll(fraction: Double): Boolean = false
    /** 多点手势；每条路径是按时间先后排列的像素点，所有手指同时移动。 */
    suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long): ActionResult = ActionResult.Failed("unsupported")
    suspend fun back(): ActionResult
    suspend fun pressKey(keyCode: Int): ActionResult
    suspend fun openApp(packageName: String, cold: Boolean = false): ActionResult

    /**
     * 打开装在另一份用户 [userId] 里的那份 [packageName](分身、工作资料,见 [appCopies]);其余同 [openApp]。
     * 那份用户不能用、或那里没装这个 App 时失败 copy_not_found。不支持的端口返回 unsupported。
     */
    suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean = false): ActionResult = ActionResult.Failed("unsupported")

    /**
     * [packageName](另一份用户 [userId] 里的那份;null 是本来那份)正被另一个任务用着时([APP_IN_USE_BY_OTHER_TASK]),
     * 最多等 [timeoutMs] 到它放开。只是等,不打开:等到了调用方再打开一次。占着它的任务自己正在等这个任务手里的
     * App(互相等,谁也等不到)时不等,当场返回 [AppWait.WouldDeadlock]。不和别的任务争 App 的端口返回 [AppWait.Unsupported]。
     */
    suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait = AppWait.Unsupported

    /**
     * 在 Agent 的屏上打开一个链接或安卓设置页([action] 为 null 时按 VIEW 打开 [uri]);[packageName] 限定由哪个 App 打开。
     * 失败原因:link_unresolved(没有 App 能打开)、invalid_link、app_in_use_by_user。不支持的端口返回 unsupported。
     */
    suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult = ActionResult.Failed("unsupported")

    /**
     * 把一段文字分享给 [packageName](ACTION_SEND text/plain),在 Agent 的屏上打开它的分享页;只打开,不发出。
     * 这个 App 没有收文字分享的页面时失败 cannot_share;其余和 [openApp] 一样(建屏、用户正在用、系统问打开哪一个)。不支持的端口返回 unsupported。
     */
    suspend fun shareText(packageName: String, text: String): ActionResult = ActionResult.Failed("unsupported")

    /**
     * 经安卓标准接口做一个系统动作(见 [SystemAction]):先查有没有 App 接,没有报 not_supported;有就在 Agent 的屏上交给它
     * (第一次用时建屏,用户正在主屏用那个 App 时不打开)。不支持的端口返回 unsupported。
     */
    suspend fun systemAction(action: SystemAction): SystemActionResult = SystemActionResult(ActionResult.Failed("unsupported"))

    /**
     * 把编号 [ref] 那个滚轮(或滚轮里的一项)转到显示 [value]:在端口里一次做完(能输入就输入,能无障碍滚就一格格滚,
     * 都不行就按一格多高拖),每做几下读回一次。不支持的端口报 unsupported。
     */
    suspend fun setWheel(ref: String, snapshotId: String, value: String): WheelOutcome =
        WheelOutcome(false, null, null, 0, "none", error = "unsupported")
    suspend fun waitStable(timeoutMs: Long): Boolean
    /**
     * 等这块屏的下一次界面变化,最多 [timeoutMs];等到返回 true。事件驱动的端口在有界面事件(无障碍事件、页面变动)时醒来,
     * 不支持的端口等满时限返回 false,由调用方按间隔重读。
     */
    suspend fun awaitChange(timeoutMs: Long): Boolean { delay(timeoutMs.coerceAtLeast(0)); return false }
    /** 等待事件驱动的界面条件；condition: appear/disappear/activity/stable。 */
    suspend fun waitFor(condition: String, value: String?, timeoutMs: Long): Boolean = false
    /** 监听虚拟屏的界面事件，或全局通知；仅在调用期间订阅。 */
    suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long): ScreenEvent? = null
    /** 在一段时间内持续收集事件；单次订阅不会漏掉两次事件间的变化。 */
    suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int): List<ScreenEvent> =
        listOfNotNull(nextEvent(kind, packageName, durationMs))
    /** 直接经任务持有的 shell 助手执行命令。 */
    suspend fun systemCommand(command: String): SystemCommandResult? = null
    suspend fun listApps(): List<Pair<String, String>> // 包名, 显示名

    /** 也装在这台手机另一份用户里(分身、工作资料等)的 App:包名 → 那几份用户的名字。查不到或不支持时为空。 */
    suspend fun otherProfiles(): Map<String, List<String>> = emptyMap()

    /** 能打开的另一份 App(装在分身、工作资料一类的另一份用户里,规则见 [AppCopies]);查不到或不支持时为空。 */
    suspend fun appCopies(): List<AppCopy> = emptyList()

    /**
     * 任务结束时这份 App 怎么办(见 [AfterTaskMarks]):只记下,此刻什么也不动。不支持的端口(没有任务结束时的去留)为 null。
     */
    val afterTask: AfterTaskMarks? get() = null

    /** 屏幕诊断;端口不支持时为 null,由调用方按元素列表粗判。 */
    suspend fun checkScreen(): ScreenCheck? = null

    /** 当前页面是否受系统防截屏保护。 */
    suspend fun isProtected(): Boolean = false

    /** 此刻画面缩成的亮度格子(判动作效果用,见 [FrameGrid.sample]);没有画面、端口不支持时为 null。要便宜:不编码图片。 */
    suspend fun frameGrid(): FrameGrid? = null
}
