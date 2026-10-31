package com.ai.assistance.quro.core.mika.selfcheck

/**
 * 兼容性自检的一项。列的顺序就是跑的顺序;[needs] 里有一项没过(失败或被跳过)这一项就跳过,并写明是缺了哪一项。
 * [key] 是报告里的英文名,[timeoutMs] 是这一项最多跑多久(整次自检另有总时限,见 [SelfCheckRunner.TOTAL_BUDGET_MS])。
 */
enum class SelfCheck(val key: String, val timeoutMs: Long) {
    /** 机型、系统版本、处理器架构、内存、屏幕、有没有无线调试、ZorvAI 的版本。 */
    DEVICE("device", 5_000),
    /** 无障碍开着且已绑定、通知允许、电池不受限制。 */
    PERMISSIONS("permissions", 5_000),
    /** 内置助手在运行、怎么起来的、一次简单调用来回多久。 */
    HELPER("helper", 15_000),
    /** 能建 ZorvAI 的虚拟屏;安卓 14 起它有没有自己的焦点。 */
    VIRTUAL_DISPLAY("virtual display", 10_000),
    /** 在虚拟屏上打开 ZorvAI 自己的自检页,再打开系统设置(只看不点),确认开在 ZorvAI 的屏上、不在主屏。 */
    APP_LAUNCH("app launch", 20_000),
    /** 自检页的控件树读得到、要的控件都在;虚拟屏的截图不是一片黑或一片空。 */
    SCREEN_READING("screen reading", 10_000),
    /** 在自检页上点按、打英文和中文、滚动、返回,每一样都回读核对。 */
    ACTIONS("actions", 20_000),
    /** 自检页一直在动的十几秒里,虚拟屏的画面一直在变、控件树一直读得到。 */
    STAYS_ALIVE("stays alive", 25_000),
    /** 主屏最上面的任务从头到尾没变。 */
    MAIN_SCREEN("main screen", 5_000),
    /** 内置浏览器打开一页本地网页(不联网),读出文字、点一下按钮再核对。 */
    BROWSER("browser", 15_000),
    /** 沙箱里跑 `echo ok` 和一行 Python(只在 arm64 上;运行环境没准备好算部分可用,不现装)。 */
    SANDBOX("sandbox", 20_000),
    /** 能打开里面 App 的另一份用户(分身、工作资料)有几份;只数,不列 App,私密空间不算。 */
    APP_COPIES("app copies", 10_000),
    ;

    /** 报告里的编号,从 1 起。 */
    val number: Int get() = ordinal + 1

    /** 先要过的几项。 */
    val needs: List<SelfCheck>
        get() = when (this) {
            DEVICE, PERMISSIONS, BROWSER, SANDBOX -> emptyList()
            HELPER -> listOf(DEVICE)
            VIRTUAL_DISPLAY -> listOf(HELPER)
            APP_LAUNCH -> listOf(PERMISSIONS, VIRTUAL_DISPLAY)
            SCREEN_READING -> listOf(APP_LAUNCH)
            ACTIONS -> listOf(SCREEN_READING)
            STAYS_ALIVE -> listOf(SCREEN_READING)
            MAIN_SCREEN -> listOf(VIRTUAL_DISPLAY)
            APP_COPIES -> listOf(HELPER)
        }
}

enum class CheckStatus {
    Pending, Running, Pass, Partial, Fail, Skipped;

    /** 过了:能用,或部分能用。依赖它的项照跑。 */
    val passed: Boolean get() = this == Pass || this == Partial

    /** 已经有结论(不再变)。 */
    val final: Boolean get() = this != Pending && this != Running
}

/**
 * 给用户的一句话说的是哪种情况(界面按它取本地化的文字,报告里不写)。都是通用的说法:原因加上能做的下一步。
 */
enum class Advice {
    /** 系统版本低于安卓 12,手机操作用不了。 */
    PhoneTooOld,
    /** 系统里没有无线调试:改用电脑经 USB 启动。 */
    NoWirelessDebugging,
    /** 无障碍没开。 */
    TurnOnAccessibility,
    /** 无障碍开着却没在运行:关掉再打开。 */
    RestartAccessibility,
    /** 通知没允许:看不到进度和「需要你」的提醒。 */
    AllowNotifications,
    /** 电池受限制:后台可能被停掉。 */
    AllowBattery,
    /** 内置助手没在运行:先完成手机操作的设置。 */
    SetUpPhoneControl,
    /** 建不了虚拟屏。 */
    DisplayFailed,
    /** 安卓 14 以下虚拟屏没有自己的焦点,后台操作时主屏的输入焦点可能短暂丢失。 */
    FocusShared,
    /** 页面没开到 ZorvAI 的屏上。 */
    LaunchFailed,
    /** 系统设置开到了主屏上。 */
    OpenedOnMainScreen,
    /** 系统设置正在你的主屏上用着,这一步没去打开它。 */
    SettingsInUse,
    /** 控件树读不到或缺控件。 */
    ReadFailed,
    /** 截图是一片黑或一片空。 */
    BlankFrames,
    /** 点按、打字、滚动或返回没生效。 */
    ActionFailed,
    /** 虚拟屏的画面过一会儿就不动了。 */
    FramesStopped,
    /** 主屏最上面的任务变了。 */
    MainScreenChanged,
    /** 系统里没有可用的 WebView。 */
    NoWebView,
    /** 内置浏览器没读出或没点成。 */
    WebFailed,
    /** 沙箱只在 arm64 手机上有。 */
    SandboxUnsupported,
    /** 运行环境还没准备好。 */
    SandboxNotPrepared,
    /** 命令没跑出预期的结果。 */
    SandboxFailed,
    /** 这一项超时。 */
    TimedOut,
    /** 这一项出错。 */
    Error,
    /** 整次自检的时间用完了。 */
    OutOfTime,
    /** 用户取消了。 */
    Cancelled,
}

/**
 * 一项跑完的结论。[detail] 是写进报告的一行事实(英文,不含个人数据:不写账号、App 列表、用户的内容、序列号);
 * [advice] 让界面给一句本地化的大白话;通过时一般为空。
 */
data class CheckOutcome(val status: CheckStatus, val detail: String, val advice: Advice? = null) {
    companion object {
        fun pass(detail: String) = CheckOutcome(CheckStatus.Pass, detail)
        fun partial(detail: String, advice: Advice) = CheckOutcome(CheckStatus.Partial, detail, advice)
        fun fail(detail: String, advice: Advice) = CheckOutcome(CheckStatus.Fail, detail, advice)
        fun skipped(detail: String, advice: Advice? = null) = CheckOutcome(CheckStatus.Skipped, detail, advice)
    }
}

/** 一项此刻的状态:还没跑、在跑、或者结论;[blockedBy] 是因为它没过才跳过的那一项。 */
data class CheckResult(
    val check: SelfCheck,
    val status: CheckStatus = CheckStatus.Pending,
    val detail: String = "",
    val advice: Advice? = null,
    val blockedBy: SelfCheck? = null,
    val durationMs: Long = 0,
)

/**
 * 报告抬头的设备事实,都来自系统公开的接口;不含序列号、账号这类能认出人的东西。
 * [wirelessDebugging] 系统里有没有无线调试这一项,读不准为 null。
 */
data class DeviceFacts(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdk: Int,
    val abis: List<String>,
    val ramBytes: Long,
    val screenWidth: Int,
    val screenHeight: Int,
    val wirelessDebugging: Boolean?,
    val appVersion: String,
    val appBuild: String,
) {
    /** 机型:型号里已带厂商名时只写型号。 */
    val modelName: String get() = if (model.startsWith(manufacturer, ignoreCase = true)) model.trim() else "$manufacturer $model".trim()

    val arm64: Boolean get() = ARM64 in abis

    companion object {
        const val ARM64 = "arm64-v8a"
        /** 手机操作的最低系统版本(安卓 12)。 */
        const val MIN_PHONE_SDK = 31
        /** 安卓 14 起虚拟屏能有自己的焦点。 */
        const val OWN_FOCUS_SDK = 34
    }
}

/**
 * 自检要的安卓能力。界面那边的实现在 app 里;单元测试换成假的。
 * 每一项都可能因超时或用户取消被打断,要能及时停下;出错可以直接抛,跑的那一边按失败记。
 */
interface SelfCheckPort {
    /** 第一项「设备」的事实(只读系统接口,很快)。 */
    suspend fun facts(): DeviceFacts

    /** 跑一项([SelfCheck.DEVICE] 以外;那一项由 [facts] 判)。 */
    suspend fun check(check: SelfCheck): CheckOutcome

    /** 放掉跑的过程中占着的一切(虚拟屏、自检页、网页标签、助手连接)。跑完、取消、出错都会调,可能调不止一次。 */
    suspend fun release()
}
