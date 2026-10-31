package com.ai.assistance.quro.core.mika.shell

/**
 * ZorvAI 交给内置助手的心愿:自己的进程被杀后要不要把它拉起来([revive]),拉起来之前要不要把 ZorvAI 的无障碍服务写回系统列表([accessibility])。
 * 助手读不到 ZorvAI 的设置,只认 ZorvAI 最后一次经 Binder 交来的这一份(见 [RestoreWishSender])。
 */
data class RestoreWish(val revive: Boolean, val accessibility: Boolean) {
    /** 经 AIDL 传的整数。 */
    val flags: Int get() = (if (revive) REVIVE else 0) or (if (accessibility) ACCESSIBILITY else 0)

    companion object {
        const val REVIVE = 1
        const val ACCESSIBILITY = 2

        /** 助手刚起来、ZorvAI 还没交来心愿时:什么都不做。 */
        val NONE = RestoreWish(revive = false, accessibility = false)

        fun fromFlags(flags: Int) = RestoreWish(flags and REVIVE != 0, flags and ACCESSIBILITY != 0)

        /**
         * 胶囊设成一直显示,或有任务在跑,才值得在被杀后拉起来;否则用户亲手强行停止就让它停着。
         * 无障碍服务只在用户没在 ZorvAI 里关掉「替我打开无障碍」([accessibilityWanted])、
         * 而且此刻系统列表里确实有 ZorvAI([accessibilityOn])时才写回:用户趁 ZorvAI 活着在系统设置里关掉的,不替他打开。
         */
        fun of(capsuleAlways: Boolean, taskRunning: Boolean, accessibilityWanted: Boolean, accessibilityOn: Boolean): RestoreWish {
            val revive = capsuleAlways || taskRunning
            return RestoreWish(revive, revive && accessibilityWanted && accessibilityOn)
        }
    }
}

/**
 * ZorvAI 一侧:把心愿交给手里的助手。换了助手(新的 Binder)或心愿变了才交;交失败不记,下次再交。
 * [send] 返回是否交到了。
 */
class RestoreWishSender<T : Any>(private val send: (T, RestoreWish) -> Boolean) {
    private var shell: T? = null
    private var sent: RestoreWish? = null

    @Synchronized
    fun update(current: T?, wish: RestoreWish) {
        if (current == null) {
            shell = null
            sent = null
            return
        }
        if (current === shell && wish == sent) return
        if (send(current, wish)) {
            shell = current
            sent = wish
        } else {
            shell = null
            sent = null
        }
    }
}

/** 助手在交接时告诉 ZorvAI 的:上次把它拉起来的时间、最近窗口内拉了几次、上次因为拉得太勤而作罢的时间(墙上时钟毫秒,0 表示没有)。 */
data class HelperRestoreReport(val lastRestoredAtMs: Long, val recent: Int, val gaveUpAtMs: Long) {
    val empty: Boolean get() = lastRestoredAtMs == 0L && gaveUpAtMs == 0L
}

/**
 * 助手一侧:ZorvAI 的进程没了、过了 [GRACE_MS] 还没回来时,决定要不要把它拉起来。
 * 只看 ZorvAI 最后交来的 [RestoreWish];[windowMs] 里最多拉 [maxAttempts] 次,拉起来又马上死掉就停手,等窗口过去。
 */
class HelperRestorePolicy(
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val windowMs: Long = WINDOW_MS,
) {
    enum class Decision {
        Restore,
        /** ZorvAI 没要(胶囊不是一直显示、也没有任务在跑)。 */
        NotWanted,
        /** 等的这一会儿里 ZorvAI 自己回来了(或正在交接)。 */
        AppBack,
        /** 最近拉得太勤:ZorvAI 一拉起来就又死,不再拉。 */
        BackedOff,
    }

    private val attempts = ArrayDeque<Long>()
    private var lastRestoredAt = 0L
    private var gaveUpAt = 0L

    @Synchronized
    fun decide(wish: RestoreWish, appBack: Boolean, now: Long): Decision {
        if (appBack) return Decision.AppBack
        if (!wish.revive) return Decision.NotWanted
        prune(now)
        if (attempts.size >= maxAttempts) {
            gaveUpAt = now
            return Decision.BackedOff
        }
        attempts.addLast(now)
        lastRestoredAt = now
        return Decision.Restore
    }

    @Synchronized
    fun report(now: Long): HelperRestoreReport {
        prune(now)
        return HelperRestoreReport(lastRestoredAt, attempts.size, gaveUpAt)
    }

    private fun prune(now: Long) {
        // 时钟倒退时也清掉:看不清就不算
        while (attempts.isNotEmpty() && (now - attempts.first() >= windowMs || now < attempts.first())) attempts.removeFirst()
    }

    companion object {
        /** ZorvAI 死后先等这么久:系统或用户可能马上把它重新打开,那就不用助手动手。 */
        const val GRACE_MS = 8_000L
        const val MAX_ATTEMPTS = 3
        const val WINDOW_MS = 10 * 60_000L
    }
}

/** 助手把 ZorvAI 拉起来之前,写回无障碍服务的命令(安卓原版 settings 的写法)。 */
object RestoreCommands {
    const val ENABLE_ACCESSIBILITY = "settings put secure accessibility_enabled 1"

    /** 列表里没有 ZorvAI 就追加(别的服务一个不动),有就只把总开关打开;[current] 是 `settings get` 读到的原样输出。 */
    fun accessibility(current: String?, component: String): List<String> =
        if (AccessibilityList.contains(current, component)) listOf(ENABLE_ACCESSIBILITY)
        else PermissionCommands.writeAccessibility(AccessibilityList.with(current, component))
}

/** ZorvAI 启动时看到的无障碍服务问题:被从系统列表里拿掉了,或在列表里却一直没绑定。 */
enum class A11yLoss { Removed, Unbound }

/** 上一次在 ZorvAI 启动时看到的无障碍服务问题和时间(墙上时钟毫秒)。 */
data class A11yLossRecord(val loss: A11yLoss, val atMs: Long)

object A11yStartCheck {
    /** 等服务连上的时长:进程刚起来时系统通常几秒内就会绑定。 */
    const val CONNECT_WAIT_MS = 5_000L

    /**
     * [initial] 是 ZorvAI 刚起来时的样子,[connectedSoon] 是 [CONNECT_WAIT_MS] 内连上了没有;
     * [expected] 为 false(服务从没连上过,比如还没设置)时不算问题。
     */
    fun loss(initial: A11yObservation, connectedSoon: Boolean, expected: Boolean): A11yLoss? = when {
        !expected || initial.connected -> null
        !initial.enabled -> A11yLoss.Removed
        !connectedSoon -> A11yLoss.Unbound
        else -> null
    }
}
