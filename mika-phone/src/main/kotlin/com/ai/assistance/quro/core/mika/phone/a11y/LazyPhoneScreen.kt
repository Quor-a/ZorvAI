package com.ai.assistance.quro.core.mika.phone.a11y

import com.ai.assistance.quro.core.mika.uistate.ActionResult
import com.ai.assistance.quro.core.mika.uistate.AppCopy
import com.ai.assistance.quro.core.mika.uistate.ScreenCheck
import com.ai.assistance.quro.core.mika.uistate.ScreenPort
import com.ai.assistance.quro.core.mika.uistate.ScreenSnapshot
import com.ai.assistance.quro.core.mika.uistate.ScreenEvent
import com.ai.assistance.quro.core.mika.uistate.VisibleTextLine
import com.ai.assistance.quro.core.mika.uistate.SystemCommandResult
import com.ai.assistance.quro.core.mika.uistate.SystemAction
import com.ai.assistance.quro.core.mika.uistate.SystemActionResult
import com.ai.assistance.quro.core.mika.uistate.WheelOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 操作手机 App 需要的(内置助手、无障碍)此刻没有;[hint] 是告诉模型和用户怎么弄好的一句话。 */
class PhoneUnavailable(val hint: String) : Exception(hint)

/**
 * 用到时才接上的手机端口:第一次 launch_app / open_link 时才调 [acquire](连内置助手、等无障碍、备好虚拟屏);
 * 只用网页或沙箱的任务从头到尾不需要内置助手和无障碍。没接上之前读屏是一块空屏、其余动作报 no_app_open,
 * 和接上后还没打开 App 时一样。接不上时这次动作报 phone_unavailable(附怎么弄好),下次再试。
 * 装了哪些 App 只问包管理器([listApps]),不用接上;另一份用户里的 App 没接上时问 [appCopies](内置助手已连着才有,不为它去接)。
 */
class LazyPhoneScreen(
    private val width: Int,
    private val height: Int,
    private val listApps: suspend () -> List<Pair<String, String>>,
    private val appCopies: suspend () -> List<AppCopy> = { emptyList() },
    private val acquire: suspend () -> ScreenPort,
) : ScreenPort {
    private val lock = Mutex()
    @Volatile private var port: ScreenPort? = null

    /** 本任务用过手机(已接上)。 */
    val acquired: Boolean get() = port != null

    private suspend fun built(): ScreenPort? = lock.withLock {
        port ?: try {
            acquire().also { port = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PhoneUnavailable) {
            lastProblem = e.hint
            null
        }
    }

    @Volatile private var lastProblem: String? = null

    private fun unavailable() = ActionResult.Failed(PHONE_UNAVAILABLE, lastProblem)

    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult = built()?.openApp(packageName, cold) ?: unavailable()
    override suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean): ActionResult =
        built()?.openAppCopy(packageName, userId, cold) ?: unavailable()
    override suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult =
        built()?.openLink(action, uri, packageName) ?: unavailable()
    override suspend fun shareText(packageName: String, text: String): ActionResult = built()?.shareText(packageName, text) ?: unavailable()
    override suspend fun systemAction(action: SystemAction): SystemActionResult = built()?.systemAction(action) ?: SystemActionResult(unavailable())
    override suspend fun setWheel(ref: String, snapshotId: String, value: String): WheelOutcome =
        port?.setWheel(ref, snapshotId, value) ?: WheelOutcome(false, null, null, 0, "none", error = "no_app_open")

    // ---------- 没接上之前:还没有打开的 App ----------

    override suspend fun snapshot(): ScreenSnapshot = port?.snapshot() ?: empty()
    override suspend fun peek(): ScreenSnapshot = port?.peek() ?: empty()
    override suspend fun screenshotPng(): ByteArray? = port?.screenshotPng()
    override suspend fun readVisibleText(): List<VisibleTextLine>? = port?.readVisibleText()
    override suspend fun tapRef(ref: String, snapshotId: String) = port?.tapRef(ref, snapshotId) ?: NO_APP_OPEN
    override suspend fun tapXY(x: Int, y: Int) = port?.tapXY(x, y) ?: NO_APP_OPEN
    override suspend fun doubleTapRef(ref: String, snapshotId: String) = port?.doubleTapRef(ref, snapshotId) ?: NO_APP_OPEN
    override suspend fun doubleTapXY(x: Int, y: Int) = port?.doubleTapXY(x, y) ?: NO_APP_OPEN
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = port?.typeText(ref, snapshotId, text) ?: NO_APP_OPEN
    override suspend fun clearText(ref: String?, snapshotId: String?) = port?.clearText(ref, snapshotId) ?: NO_APP_OPEN
    override suspend fun typeAt(x: Int, y: Int, text: String) = port?.typeAt(x, y, text) ?: NO_APP_OPEN
    override suspend fun clearAt(x: Int, y: Int) = port?.clearAt(x, y) ?: NO_APP_OPEN
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = port?.scroll(ref, snapshotId, down) ?: NO_APP_OPEN
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = port?.swipe(x1, y1, x2, y2, durationMs) ?: NO_APP_OPEN
    override suspend fun idleScroll(fraction: Double): Boolean = port?.idleScroll(fraction) ?: false
    override suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long) = port?.gesture(paths, durationMs) ?: NO_APP_OPEN
    override suspend fun back() = port?.back() ?: NO_APP_OPEN
    override suspend fun pressKey(keyCode: Int) = port?.pressKey(keyCode) ?: NO_APP_OPEN
    override suspend fun waitStable(timeoutMs: Long) = port?.waitStable(timeoutMs) ?: true
    override suspend fun awaitChange(timeoutMs: Long): Boolean = port?.awaitChange(timeoutMs) ?: super.awaitChange(timeoutMs)
    override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long) = port?.waitFor(condition, value, timeoutMs) ?: false
    override suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long): ScreenEvent? {
        val screen = built() ?: return null
        if (kind != "notification") (screen as? AndroidScreen)?.ensureDisplay()
        return screen.nextEvent(kind, packageName, timeoutMs)
    }
    override suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int): List<ScreenEvent> {
        val screen = built() ?: return emptyList()
        if (kind != "notification") (screen as? AndroidScreen)?.ensureDisplay()
        return screen.watchEvents(kind, packageName, durationMs, maxEvents)
    }
    override suspend fun systemCommand(command: String): SystemCommandResult? {
        val screen = built() as? AndroidScreen ?: return null
        screen.ensureDisplay()
        return screen.systemCommand(command)
    }
    override suspend fun listApps(): List<Pair<String, String>> = port?.listApps() ?: listApps.invoke()
    // 要问内置助手:没接上之前不为它去接(接不上会请用户设置),打开过 App 之后才有
    override suspend fun otherProfiles(): Map<String, List<String>> = port?.otherProfiles() ?: emptyMap()
    override suspend fun appCopies(): List<AppCopy> = port?.appCopies() ?: appCopies.invoke()
    override suspend fun checkScreen(): ScreenCheck? = port?.checkScreen()
    override suspend fun isProtected() = port?.isProtected() ?: false
    override suspend fun frameGrid() = port?.frameGrid()

    private fun empty() = ScreenSnapshot(snapshotId(), null, emptyList(), width, height, hasWindow = false)

    companion object {
        const val PHONE_UNAVAILABLE = "phone_unavailable"
        private val NO_APP_OPEN = ActionResult.Failed("no_app_open")
    }
}
