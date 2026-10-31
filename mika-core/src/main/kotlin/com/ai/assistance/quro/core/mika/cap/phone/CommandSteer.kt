package com.ai.assistance.quro.core.mika.cap.phone

/**
 * 系统命令在做专门工具做的事(点、滑、打字、按键、打开 App)时,结果里提醒换回那个工具:专门工具在 ZorvAI 的屏上做,
 * 做完会核实效果,shell 发的输入不核实。第一次只提醒、照样执行;本任务提醒过之后,再做这几类事的命令不执行,
 * 只回那句提醒([refuses])。用 shell 分享文字一律不执行,指向分享文字的工具。查状态、读设置一类的命令不受影响。
 */
internal object CommandSteer {
    enum class Purpose(val feedbackKey: String) {
        Gesture("system_command.use_tap"), Type("system_command.use_type"), Key("system_command.use_key"), Launch("system_command.use_launch"),
        Share("system_command.use_share"),
    }

    /** [purpose] 的命令这回不执行:分享一律不执行;其余在本任务已提醒过([steered])之后不执行。 */
    fun refuses(purpose: Purpose?, steered: Boolean): Boolean = purpose == Purpose.Share || (purpose != null && steered)

    /** 命令里每一段 `input …` 的子命令(前面可带输入源和 -d 屏号)。 */
    private val INPUT = Regex("""(?:^|[;&|(\s])input\s+(?:(?:touchscreen|touchpad|keyboard|mouse|stylus|trackball|dpad|gamepad|joystick|rotaryencoder|touchnavigation)\s+)?(?:-d\s+\S+\s+)?([a-z]+)""")
    private val LAUNCH = Regex("""(?:^|[;&|(\s])(?:am\s+(?:start|start-activity)\b|cmd\s+activity\s+start(?:-activity)?\b|monkey\s+(?:.*\s)?-p\s)""")
    private val GESTURES = setOf("tap", "swipe", "draganddrop", "motionevent", "press", "roll")
    /** 启动的是分享:动作写全(android.intent.action.SEND)或只写 -a SEND。 */
    private val SHARE = Regex("""(?:android\.intent\.action\.SEND|-a\s+["']?SEND)(?![A-Za-z_])""")
    /** 分享的是文件(带 STREAM):分享文字的工具做不了,按一般的启动算。 */
    private const val STREAM = "android.intent.extra.STREAM"

    /**
     * 命令在模仿哪一类专门工具;都不是为 null。打字和点同在一条命令里时按打字算(要说的是打字该用什么);
     * 分享文字排在最前:它一律不执行。
     */
    fun purpose(command: String): Purpose? {
        val subs = INPUT.findAll(command).map { it.groupValues[1] }.toSet()
        val launches = LAUNCH.containsMatchIn(command)
        return when {
            launches && SHARE.containsMatchIn(command) && STREAM !in command -> Purpose.Share
            "text" in subs -> Purpose.Type
            subs.any { it in GESTURES } -> Purpose.Gesture
            subs.any { it == "keyevent" || it == "keycombination" } -> Purpose.Key
            launches -> Purpose.Launch
            else -> null
        }
    }
}
