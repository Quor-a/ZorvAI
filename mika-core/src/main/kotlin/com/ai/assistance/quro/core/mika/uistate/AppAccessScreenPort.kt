package com.ai.assistance.quro.core.mika.uistate

import com.ai.assistance.quro.core.mika.access.AppAccessDenied
import com.ai.assistance.quro.core.mika.access.AppAccessPolicy
import com.ai.assistance.quro.core.mika.access.AppAccessState
import kotlinx.coroutines.delay

/**
 * 手机端口的应用访问边界:每个 [ScreenPort] 成员都在这里显式写出(不用 `by` 委托),端口以后加的成员不会不经检查就漏过去;
 * 测试里逐个成员核对这一点。
 *
 * 读屏、动作前后各核对一次前台窗口的归属;按目标打开(App、分享、指定了 App 的链接)只看目标。
 * 窗口探针 [packages] 只读归属,不采集内容,也不为检查创建屏幕。禁用名单为空时什么也不多做。
 *
 * 少问窗口:[snapshot]、[peek] 直接用读到的这一屏里的窗口归属核对,不另问;给了 [windowToken](窗口一变就变的计数)时,
 * 核对过的结果在窗口没变、名单没变、且不超过 [CHECK_TTL_MS] 时直接沿用(同一次读屏紧接着的截图、预览的一帧帧画面)。
 */
class AppAccessScreenPort(
    private val inner: ScreenPort,
    override val appAccess: AppAccessPolicy,
    private val packages: suspend () -> List<String?>,
    private val protectedUi: () -> Boolean = { false },
    private val ownPackage: String? = null,
    private val windowToken: (() -> Long)? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : ScreenPort {
    /** 上一次核对通过时的窗口计数、名单版本、是否在保护 ZorvAI 自己的界面、时刻。 */
    private class Verified(val token: Long, val revision: Long, val guarded: Boolean, val at: Long)

    @Volatile private var verified: Verified? = null

    override suspend fun checkAppAccess() {
        val state = appAccess.current()
        if (!state.available) throw AppAccessDenied("app_access_unavailable", null)
        val guarded = protectedUi()
        if (state.blocked.isEmpty() && !guarded) return
        if (stillVerified(state, guarded)) return
        val token = windowToken?.invoke()
        verify(state, packages(), guarded, token)
    }

    private fun stillVerified(state: AppAccessState, guarded: Boolean): Boolean {
        val v = verified ?: return false
        val token = windowToken?.invoke() ?: return false
        return v.token == token && v.revision == state.revision && v.guarded == guarded && clock() - v.at <= CHECK_TTL_MS
    }

    /** 核对 [found] 这些窗口归属;有认不出归属的,隔一会儿再问一次窗口。通过了记下,供 [stillVerified] 沿用。 */
    private suspend fun verify(state: AppAccessState, found: List<String?>, guarded: Boolean, token: Long?) {
        verified = null
        var s = state
        var targets = found
        var t = token
        if (s.blocked.isNotEmpty() && targets.any { it.isNullOrBlank() }) {
            delay(RETRY_MS)
            t = windowToken?.invoke()
            targets = packages()
            s = appAccess.current()
        }
        if (!s.available) throw AppAccessDenied("app_access_unavailable", null)
        for (pkg in targets) {
            if (guarded && ownPackage != null && pkg == ownPackage) throw AppAccessDenied("app_access_protected", pkg)
            s.denial(pkg)?.let { throw it }
        }
        verified = t?.let { Verified(it, s.revision, guarded, clock()) }
    }

    /** 读一屏:读到的就带着窗口归属,读完拿它核对;端口不给窗口时退回问窗口。 */
    private suspend fun observed(read: suspend () -> ScreenSnapshot): ScreenSnapshot {
        val state = appAccess.current()
        if (!state.available) throw AppAccessDenied("app_access_unavailable", null)
        val guarded = protectedUi()
        if (state.blocked.isEmpty() && !guarded) return read()
        // 计数先取:读的过程中窗口变了,下一次就不沿用
        val token = windowToken?.invoke()
        val snap = read()
        verify(appAccess.current(), snap.accessPackages() ?: packages(), guarded, token)
        return snap
    }

    private suspend fun <T> checked(body: suspend () -> T): T {
        checkAppAccess()
        val value = body()
        checkAppAccess()
        return value
    }

    private fun target(packageName: String) {
        appAccess.current().denial(packageName)?.let { throw it }
    }

    // ---------- 读屏 ----------
    override suspend fun snapshot() = observed { inner.snapshot() }
    override suspend fun peek() = observed { inner.peek() }
    override suspend fun screenshotPng() = checked { inner.screenshotPng() }
    override suspend fun readVisibleText() = checked { inner.readVisibleText() }
    override suspend fun frameGrid() = checked { inner.frameGrid() }
    override suspend fun isProtected() = checked { inner.isProtected() }
    override suspend fun checkScreen() = checked { inner.checkScreen() }

    // ---------- 在当前屏上的动作 ----------
    override suspend fun tapRef(ref: String, snapshotId: String) = checked { inner.tapRef(ref, snapshotId) }
    override suspend fun tapXY(x: Int, y: Int) = checked { inner.tapXY(x, y) }
    override suspend fun doubleTapRef(ref: String, snapshotId: String) = checked { inner.doubleTapRef(ref, snapshotId) }
    override suspend fun doubleTapXY(x: Int, y: Int) = checked { inner.doubleTapXY(x, y) }
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = checked { inner.typeText(ref, snapshotId, text) }
    override suspend fun clearText(ref: String?, snapshotId: String?) = checked { inner.clearText(ref, snapshotId) }
    override suspend fun typeAt(x: Int, y: Int, text: String) = checked { inner.typeAt(x, y, text) }
    override suspend fun clearAt(x: Int, y: Int) = checked { inner.clearAt(x, y) }
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = checked { inner.scroll(ref, snapshotId, down) }
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = checked { inner.swipe(x1, y1, x2, y2, durationMs) }
    override suspend fun idleScroll(fraction: Double) = checked { inner.idleScroll(fraction) }
    override suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long) = checked { inner.gesture(paths, durationMs) }
    override suspend fun back() = checked { inner.back() }
    override suspend fun pressKey(keyCode: Int) = checked { inner.pressKey(keyCode) }
    override suspend fun setWheel(ref: String, snapshotId: String, value: String) = checked { inner.setWheel(ref, snapshotId, value) }

    // ---------- 等待与事件 ----------
    override suspend fun waitStable(timeoutMs: Long) = checked { inner.waitStable(timeoutMs) }
    override suspend fun awaitChange(timeoutMs: Long) = checked { inner.awaitChange(timeoutMs) }
    override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long) = checked { inner.waitFor(condition, value, timeoutMs) }
    override suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long) = checked { inner.nextEvent(kind, packageName, timeoutMs) }
    override suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int) =
        checked { inner.watchEvents(kind, packageName, durationMs, maxEvents) }

    // ---------- 按目标打开:可以离开受限的 App,只看目标 ----------
    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
        target(packageName)
        return inner.openApp(packageName, cold)
    }

    override suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean): ActionResult {
        target(packageName)
        return inner.openAppCopy(packageName, userId, cold)
    }

    override suspend fun shareText(packageName: String, text: String): ActionResult {
        target(packageName)
        return inner.shareText(packageName, text)
    }

    /** 指定了 App 的在这里看;没指定的由端口按名单挑接手的 App(由谁打开只有端口知道)。 */
    override suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult {
        packageName?.let(::target)
        return inner.openLink(action, uri, packageName)
    }

    /** 只等占用登记、不读屏;目标照打开时一样先看,受限的 App 不必等,当场拒。 */
    override suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait {
        target(packageName)
        return inner.awaitAppFree(packageName, userId, timeoutMs)
    }

    /** 接手的 App 由端口按名单挑(由谁接只有端口知道);做完后的前台由执行入口照常核对。 */
    override suspend fun systemAction(action: SystemAction) = inner.systemAction(action)

    // ---------- 不碰任何 App 界面的:原样转交 ----------
    override suspend fun systemCommand(command: String) = inner.systemCommand(command)
    override suspend fun listApps() = inner.listApps()
    override suspend fun otherProfiles() = inner.otherProfiles()
    override suspend fun appCopies() = inner.appCopies()
    override val afterTask: AfterTaskMarks? get() = inner.afterTask

    companion object {
        /** 有认不出归属的窗口时,隔多久再问一次(刚开的窗口要一会儿才读得到)。 */
        const val RETRY_MS = 100L

        /** 核对结果在窗口没变时最多沿用多久;兜住窗口事件来晚的那一小段。 */
        const val CHECK_TTL_MS = 1_000L
    }
}

/**
 * 这一屏里各 App 窗口的归属,规则同端口的窗口探针:有 App 窗口就是它们的包名(读不到根节点的为 null);
 * 没有 App 窗口时是认得出的前台 App,也没有就是空屏(空列表)。端口没给窗口层级(网页、测试端口)时为 null,由调用方另问。
 */
internal fun ScreenSnapshot.accessPackages(): List<String?>? {
    val apps = windows.filter { it.type == "application" }
    if (apps.isNotEmpty()) return apps.map { it.packageName }
    if (hasWindow) return null
    return listOfNotNull(unreadableApp)
}
