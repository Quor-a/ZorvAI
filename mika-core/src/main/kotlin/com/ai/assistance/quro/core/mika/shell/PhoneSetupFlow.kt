package com.ai.assistance.quro.core.mika.shell

/** 引导的六步,按顺序。 */
enum class SetupStep { DeveloperOptions, UsbDebugging, Accessibility, WirelessDebugging, PairingCode, Connect }

/** [Skipped]:用户跳过了这一步(只有无障碍能跳过)。 */
enum class StepMark { Done, Current, Todo, Failed, Skipped }

enum class SetupPhase {
    /** 等用户做某一步。 */
    Guide,
    /** 正在配对、连接、启动:显示进度。 */
    Working,
    Success,
    /** 出错:[SetupView.failure] 给原因和修复,[SetupView.suspect] 指出最可能要回去重做的一步。 */
    Failed,
    /** 这个环境里没有能起助手的路(见 [PhoneSetupFlow.unavailable]):一开始就说明,不给走不通的步骤。 */
    Unavailable,
}

/** 配对通知频道的样子:[Off] 被关掉,通知发不出来;[Lowered] 被用户调成不在屏幕顶部弹出,还能下拉通知栏去填。 */
enum class PairingChannelHealth { Ready, Lowered, Off }

/** 配对码要填在通知里,通知这边没准备好的地方。 */
enum class PairingNotifyIssue {
    /** 没允许 ZorvAI 发通知(权限或整个 App 的通知开关)。 */
    NotificationsOff,
    /** 「配对」这一类通知被关掉了。 */
    ChannelOff,
    /** 「配对」通知不会弹出:不挡,只提醒。 */
    NotHeadsUp,
}

/**
 * 引导页的输入:系统设置里读到的开关、用户自己的确认和助手的状态。
 * 读到的开关只用来自动打勾和给提示,不拦死:有的手机读不到或读不准,用户点了「我已经打开了,下一步」([confirmed])就往下走。
 * [wirelessDebugging] 为 null 表示这台手机读不到这个开关(不按原版写设置项):不拦着,当作开着往下走。
 * [confirmed] 是用户说已经做好的几步;[revisit] 是用户在清单里点了前面的某一步,要回去重做。
 * [repair] 是用户在设置页点了「重新配对」:即便已在运行,也回到配对那一步,直到新的配对开始。
 * [usbDebugging] 开着时,Wi-Fi 断开、无线调试被系统关掉后助手也不会停。
 * [accessibility] 是 ZorvAI 的无障碍服务在运行:配对时由它读出配对码;用户可以跳过([accessibilitySkipped]),退回在通知里填。
 * [restrictedSettings] 是系统可能拦着不让打开无障碍(没从应用商店装的 App 要先在应用信息里允许受限制的设置)。
 * [notificationsAllowed]、[pairingChannel] 决定配对码能不能填在通知里。
 * 下面三项决定要不要给「改用电脑启动」(见 [SetupView.usbFallback]):
 * [wirelessMissing] 是系统里根本没有无线调试这一项(原版开机时总会写好这项设置,读不到就是系统没带这个功能);
 * [stalledMs] 是停在无线调试、配对(还没找到配对窗口)、自动连接这几处多久没有进展(见 [PhoneSetupFlow.stallPoint]);
 * [usbAsked] 是用户点了「找不到无线调试?」,或卡片已经出现过(出现后不再收回,免得一会儿有一会儿没)。
 * [restart] 是助手停了以后靠什么再起来(见 [HelperRestart]):只用 USB 启动命令起过时,无线调试和配对两步记作跳过,
 * 助手停了就停在连接一步说它停了、给命令,不退回无线调试一步。
 * [developerSettingsMissing] 是系统里打不开「开发者选项」页(跳过去的 Intent 没有页面接):和 [wirelessMissing] 一起成立时,
 * 用户既开不了 USB 调试也开不了无线调试,见 [PhoneSetupFlow.unavailable]。
 */
data class SetupInputs(
    val developerOptions: Boolean,
    val wirelessDebugging: Boolean?,
    val status: BuiltinShellStatus,
    val discovery: PairingDiscovery = PairingDiscovery.Idle,
    val notificationsAllowed: Boolean = true,
    val pairingChannel: PairingChannelHealth = PairingChannelHealth.Ready,
    val repair: Boolean = false,
    val usbDebugging: Boolean = true,
    val accessibility: Boolean = true,
    val accessibilitySkipped: Boolean = false,
    val restrictedSettings: Boolean = false,
    val confirmed: Set<SetupStep> = emptySet(),
    val revisit: SetupStep? = null,
    val wirelessMissing: Boolean = false,
    val stalledMs: Long = 0L,
    val usbAsked: Boolean = false,
    val restart: HelperRestart = HelperRestart.None,
    val developerSettingsMissing: Boolean = false,
)

/** 配对或连接没成功时最可能的原因:[step] 这一步读到是关着的;[unreadable] 为真是这台手机根本读不到。 */
data class SetupSuspect(val step: SetupStep, val unreadable: Boolean)

data class SetupView(
    val phase: SetupPhase,
    val marks: Map<SetupStep, StepMark>,
    /** 此刻该做的一步(Guide 和 Failed 时);Working/Success 时为 null。 */
    val current: SetupStep?,
    val failure: BuiltinShellStatus.Failed? = null,
    /** 配对窗口已经找到:提示用户去通知里填码。 */
    val portFound: Boolean = false,
    /** 停在配对一步时通知这边的问题;[needsNotifications] 为真时要先修好才能在通知里填码。 */
    val notifyIssue: PairingNotifyIssue? = null,
    /** 配对一步时无障碍开着:ZorvAI 自己读配对码,不用去通知里填。 */
    val autoRead: Boolean = false,
    /** 无障碍一步时给出「去应用信息允许受限制的设置」的入口。 */
    val showAppInfo: Boolean = false,
    /** 失败时最可能要回去重做的前一步,界面给一个回去的按钮。 */
    val suspect: SetupSuspect? = null,
    /** 当前这一步是用户点回来重做的:界面给「回到原来的步骤」。 */
    val revisiting: Boolean = false,
    /** 在当前这一步给「改用电脑启动」的卡片:无线调试多半用不了,或者卡住了。正常走下去时不给。 */
    val usbFallback: Boolean = false,
    /** 助手停了以后靠什么再起来(照抄 [SetupInputs.restart]):成功页的提醒、「改用电脑启动」卡片怎么说看它。 */
    val restart: HelperRestart = HelperRestart.None,
) {
    /** 通知发不出来(没允许,或「配对」通知被关):配对码要填在通知里,先修好。 */
    val needsNotifications: Boolean
        get() = notifyIssue == PairingNotifyIssue.NotificationsOff || notifyIssue == PairingNotifyIssue.ChannelOff
}

/**
 * 引导页的状态机:每次输入变化(系统设置开关、用户确认、助手状态、找配对窗口的进度)都重新算一遍,不存中间状态。
 * 成功只看助手:连上了就算设置好,不管前面读到什么。前面几步读到做好了就自动打勾;读到没做好、用户也没确认,就停在那一步,
 * 但用户随时可以点「我已经打开了,下一步」往下走,所以哪一步都不会卡死。
 * 读到开关被关掉、又没确认过时,不管助手之前怎样,都退回那一步。
 */
object PhoneSetupFlow {
    /** 配对、连接时可能因为前面的开关没开而失败的几类(配对码不对说明配对窗口开着,不算)。 */
    private val SWITCH_RELATED = setOf(BuiltinShellFailure.PairingWindowClosed, BuiltinShellFailure.ConnectFailed, BuiltinShellFailure.Unknown)

    /** 用户能点回去重做的几步:自动连接那一步没有要用户做的事。 */
    val REVISITABLE: Set<SetupStep> = SetupStep.entries.toSet() - SetupStep.Connect

    /** 有「我已经打开了,下一步」的几步:读的是系统开关(无障碍另有跳过)。 */
    val CONFIRMABLE: Set<SetupStep> = setOf(SetupStep.DeveloperOptions, SetupStep.UsbDebugging, SetupStep.WirelessDebugging)

    /** 停在 [stallPoint] 这么久没有进展,就给「改用电脑启动」。 */
    const val STALL_MS = 45_000L

    /** 这几类失败说明无线调试这条路走不通,给「改用电脑启动」:找不到配对窗口、连不上、电脑起的助手停了。 */
    private val USB_FAILURES = setOf(BuiltinShellFailure.PairingWindowClosed, BuiltinShellFailure.ConnectFailed, BuiltinShellFailure.UsbHelperStopped)

    fun view(i: SetupInputs): SetupView {
        if (unavailable(i)) return SetupView(SetupPhase.Unavailable, SetupStep.entries.associateWith { StepMark.Todo }, null, restart = i.restart)
        return steps(i).let { it.copy(usbFallback = usbFallback(it, i), restart = i.restart) }
    }

    /**
     * 这个环境能不能设置手机操作:系统里打不开开发者选项页、也没有无线调试这一项,就没有能起助手的路
     * (USB 调试和无线调试都在开发者选项里,电脑启动也要 USB 调试)。助手已经在运行、正在起来,或以前起来过,
     * 说明有别的路,不算。
     */
    fun unavailable(developerSettingsMissing: Boolean, wirelessMissing: Boolean, status: BuiltinShellStatus, restart: HelperRestart): Boolean =
        developerSettingsMissing && wirelessMissing && restart == HelperRestart.None &&
            status != BuiltinShellStatus.Running && status != BuiltinShellStatus.Connecting

    fun unavailable(i: SetupInputs): Boolean =
        unavailable(i.developerSettingsMissing, i.wirelessMissing, i.status, i.restart)

    /**
     * 卡住计时算在哪一处:停在无线调试一步、停在配对一步还没找到配对窗口(系统一直没有配对服务)、正在自动连接。
     * 前面几步要用户自己去系统设置里找,花多久都正常,不算;换了地方就从头算。
     */
    fun stallPoint(v: SetupView): SetupStep? = when {
        v.phase == SetupPhase.Working -> SetupStep.Connect
        v.phase != SetupPhase.Guide -> null
        v.current == SetupStep.WirelessDebugging -> SetupStep.WirelessDebugging
        v.current == SetupStep.PairingCode && !v.portFound -> SetupStep.PairingCode
        else -> null
    }

    /**
     * 要不要给「改用电脑启动」:系统没有无线调试、用户说找不到,都只在走到无线调试这一步以后给(电脑启动也要先做完前两步);
     * 配对、连接失败在上面几类里,或者用户说开了、助手却读到关着;在 [stallPoint] 停了 [STALL_MS] 以上。别的时候不给。
     */
    private fun usbFallback(v: SetupView, i: SetupInputs): Boolean {
        if (v.phase == SetupPhase.Success) return false
        val reachedWireless = v.phase == SetupPhase.Working || (v.current != null && v.current >= SetupStep.WirelessDebugging)
        return (reachedWireless && (i.wirelessMissing || i.usbAsked)) ||
            v.failure?.reason in USB_FAILURES ||
            (v.phase == SetupPhase.Failed && v.suspect?.step == SetupStep.WirelessDebugging) ||
            (stallPoint(v) != null && i.stalledMs >= STALL_MS)
    }

    private fun steps(i: SetupInputs): SetupView {
        val status = effectiveStatus(i.status, i.repair)
        if (status == BuiltinShellStatus.Running) return SetupView(SetupPhase.Success, marks(SetupStep.entries.size, i), null)
        val natural = natural(i, status)
        // 正在连接时以进度为准;读到更前面的一步被关掉时先回那一步
        val back = i.revisit?.takeIf { it in REVISITABLE && status != BuiltinShellStatus.Connecting } ?: return natural
        val earlier = natural.phase == SetupPhase.Guide && natural.current != null && natural.current < back
        if (earlier) return natural
        // 别的步骤照旧打勾或标失败,原来停着的那一步先变回待做
        val marks = natural.marks.mapValues { (_, m) -> if (m == StepMark.Current) StepMark.Todo else m } + (back to StepMark.Current)
        return guide(back, i).copy(marks = marks, revisiting = true)
    }

    /**
     * 没有回头重做时的样子。正在连接(包括助手换手、刚起来等交接)以进度为准,不因为读到无线调试关着就退回那一步:
     * 电脑起的助手、能替用户打开无线调试时,都不用用户去开。USB 启动命令起的助手停了,同样不往无线调试引。
     */
    private fun natural(i: SetupInputs, status: BuiltinShellStatus): SetupView {
        if (blocks(SetupStep.DeveloperOptions, i)) return guide(SetupStep.DeveloperOptions, i)
        if (blocks(SetupStep.UsbDebugging, i)) return guide(SetupStep.UsbDebugging, i)
        if (blocks(SetupStep.Accessibility, i)) return guide(SetupStep.Accessibility, i)
        if (status == BuiltinShellStatus.Connecting) return working(i)
        if (status is BuiltinShellStatus.Failed && status.reason == BuiltinShellFailure.UsbHelperStopped) {
            return SetupView(
                SetupPhase.Failed, marks(SetupStep.Connect.ordinal, i, current = SetupStep.Connect, mark = StepMark.Failed), SetupStep.Connect,
                failure = status,
            )
        }
        // 读不到开关时以助手的说法为准;用户确认过就不拦
        val wirelessConfirmed = SetupStep.WirelessDebugging in i.confirmed
        val wirelessOff = !wirelessConfirmed &&
            (i.wirelessDebugging == false || (i.wirelessDebugging == null && status == BuiltinShellStatus.WirelessDebuggingOff))
        if (wirelessOff) return guide(SetupStep.WirelessDebugging, i)
        // 用户说开了,助手却读到关着、不去连:不一直转圈,指出是哪一步并给回去的按钮
        if (status == BuiltinShellStatus.WirelessDebuggingOff && wirelessConfirmed && i.wirelessDebugging != true) {
            return SetupView(
                SetupPhase.Failed, marks(SetupStep.Connect.ordinal, i, current = SetupStep.Connect, mark = StepMark.Failed), SetupStep.Connect,
                suspect = SetupSuspect(SetupStep.WirelessDebugging, unreadable = i.wirelessDebugging == null),
            )
        }
        return when (status) {
            BuiltinShellStatus.Running -> error("handled above")
            BuiltinShellStatus.Connecting -> error("handled above")
            // 配对过、无线调试开着而状态还没跟上:实现马上会自动连,先显示「正在连接」
            BuiltinShellStatus.WirelessDebuggingOff -> working(i)
            BuiltinShellStatus.NotPaired -> guide(SetupStep.PairingCode, i)
            is BuiltinShellStatus.Failed -> {
                val step = if (status.reason.duringPairing || status.fix == BuiltinShellFix.RePair) SetupStep.PairingCode else SetupStep.Connect
                SetupView(
                    SetupPhase.Failed, marks(step.ordinal, i, current = step, mark = StepMark.Failed), step, failure = status,
                    portFound = step == SetupStep.PairingCode && i.discovery is PairingDiscovery.Found,
                    notifyIssue = notifyIssue(step, i),
                    autoRead = step == SetupStep.PairingCode && i.accessibility,
                    suspect = if (status.reason in SWITCH_RELATED) suspect(i) else null,
                )
            }
        }
    }

    private fun working(i: SetupInputs) = SetupView(SetupPhase.Working, marks(SetupStep.Connect.ordinal, i, current = SetupStep.Connect), null)

    /** 读到这一步没做好,用户也没确认(无障碍还可以跳过):停在这一步。 */
    private fun blocks(step: SetupStep, i: SetupInputs): Boolean =
        detected(step, i) == false && step !in i.confirmed && !(step == SetupStep.Accessibility && i.accessibilitySkipped)

    /** 从系统设置读到的这一步做没做好;读不到、或这一步不是开关时为 null。 */
    fun detected(step: SetupStep, i: SetupInputs): Boolean? = when (step) {
        SetupStep.DeveloperOptions -> i.developerOptions
        SetupStep.UsbDebugging -> i.usbDebugging
        SetupStep.Accessibility -> i.accessibility
        SetupStep.WirelessDebugging -> i.wirelessDebugging
        SetupStep.PairingCode, SetupStep.Connect -> null
    }

    /** 配对、连接要靠开发者选项和无线调试:按顺序找第一个读到关着(用户确认过才走到这里)或读不到的。 */
    private fun suspect(i: SetupInputs): SetupSuspect? = when {
        !i.developerOptions -> SetupSuspect(SetupStep.DeveloperOptions, unreadable = false)
        i.wirelessDebugging != true -> SetupSuspect(SetupStep.WirelessDebugging, unreadable = i.wirelessDebugging == null)
        else -> null
    }

    private fun guide(step: SetupStep, i: SetupInputs) = SetupView(
        SetupPhase.Guide, marks(step.ordinal, i, current = step), step,
        portFound = step == SetupStep.PairingCode && i.discovery is PairingDiscovery.Found,
        notifyIssue = notifyIssue(step, i),
        autoRead = step == SetupStep.PairingCode && i.accessibility,
        showAppInfo = step == SetupStep.Accessibility && i.restrictedSettings,
    )

    private fun notifyIssue(step: SetupStep, i: SetupInputs): PairingNotifyIssue? = when {
        step != SetupStep.PairingCode -> null
        !i.notificationsAllowed -> PairingNotifyIssue.NotificationsOff
        i.pairingChannel == PairingChannelHealth.Off -> PairingNotifyIssue.ChannelOff
        i.pairingChannel == PairingChannelHealth.Lowered -> PairingNotifyIssue.NotHeadsUp
        else -> null
    }

    /** 只用 USB 启动命令起过助手时没走的两步。 */
    private val WIRELESS_STEPS = setOf(SetupStep.WirelessDebugging, SetupStep.PairingCode)

    /** 前 [done] 步做完(跳过的无障碍、USB 启动时没走的无线调试和配对记成跳过),[current] 这一步打 [mark],其余待做。 */
    private fun marks(done: Int, i: SetupInputs, current: SetupStep? = null, mark: StepMark = StepMark.Current): Map<SetupStep, StepMark> =
        SetupStep.entries.associateWith { s ->
            when {
                s.ordinal < done && s == SetupStep.Accessibility && !i.accessibility && s !in i.confirmed -> StepMark.Skipped
                s.ordinal < done && s in WIRELESS_STEPS && i.restart == HelperRestart.UsbCommand -> StepMark.Skipped
                s.ordinal < done -> StepMark.Done
                s == current -> mark
                else -> StepMark.Todo
            }
        }

    /** 「重新配对」时,配对开始([BuiltinShellStatus.Connecting])之前助手在不在运行都当作还没配对。 */
    fun effectiveStatus(status: BuiltinShellStatus, repair: Boolean): BuiltinShellStatus =
        if (repair && status !is BuiltinShellStatus.Connecting) BuiltinShellStatus.NotPaired else status
}
