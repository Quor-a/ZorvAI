package com.ai.assistance.quro.core.mika.uistate


/**
 * 一种屏幕(手机、网页……)的一套同名动词工具:名字是 `<id>_<动词>`,参数结构相同,说明与允许的取值各自给。
 * 有屏幕的能力各给一份;没接端口时工具照样登记(按名字查得到),只是不提供。
 */
class ScreenFamily(
    /** 能力的 id,也是这一套工具名字的前缀。 */
    val id: String,
    /** 这块屏读屏编号的前缀;编号认不出属于哪块屏时按它判。 */
    val snapshotPrefix: String,
    /** 读屏结果里报「在哪」的字段。 */
    val placeKey: String = APP_PLACE,
    /** press_key 能按的键;null 不限。 */
    val keys: Set<Int>? = null,
    /** [keys] 不允许时给模型的说明。 */
    val keysHint: String? = null,
    /** 经验路线记它的动作、回放它们。 */
    val routable: Boolean = false,
    /** 这一套里结果是文字的工具,沙箱里的 `quro phone` 能调。 */
    val sandboxCallable: Boolean = false,
    /** 这些动词的说明用本屏自己的一份(prompts/tools/<id>_<动词>.md),其余用共用的模板。 */
    val ownPrompts: Set<String> = emptySet(),
    /** 这块屏上什么都还没打开时给模型的说明(反馈的键)。 */
    val emptyHint: String = "empty_screen",
) {
    /** 这一套里做 [base] 动作的工具名。 */
    fun tool(base: String) = "${id}_$base"

    companion object {
        const val APP_PLACE = "currentApp"
        const val SITE_PLACE = "currentSite"
    }
}

/** 一块接上了端口的屏幕,由有屏幕的能力提供。 */
class Surface(
    val family: ScreenFamily,
    /** 这块屏的读屏与动作经它。 */
    val screen: ScreenPort,
    /** 确认申报的 app 字段在这块屏上该怎么写:不对抛 ArgError,对了返回规范写法。 */
    val declaredTarget: (String) -> String,
    /** 确认申报的 app 字段在这块屏上写什么(工具参数说明里用)。 */
    val targetNote: String,
    /** 运行状态里写的这块屏此刻的版本(如网页的手机版、电脑版);没有为 null。 */
    val mode: (suspend () -> String?)? = null,
) {
    val id: String get() = family.id
}

/**
 * 在操作哪块屏:一次调用进行中是它作用的那块([enter] 到 [leave]);没有调用在进行时是最近一次成功操作的那块,任务开始时是第一块。
 * 只用于不点名屏幕的地方取默认值(确认、接管、核查证据、等界面稳定);不转发任何调用。调用可以嵌套(页面脚本里的动作)。
 */
class SurfaceFocus(val surfaces: List<Surface>) {
    init {
        require(surfaces.isNotEmpty()) { "need at least one surface" }
    }

    /** 最近一次成功操作的那块屏。 */
    @Volatile private var settled: Surface = surfaces.first()
    private val acting = ArrayDeque<Surface>()

    val current: Surface get() = synchronized(acting) { acting.lastOrNull() } ?: settled

    operator fun get(id: String): Surface? = surfaces.firstOrNull { it.id == id }

    /** 一次调用开始,作用在 [id] 那块屏上;不是屏或没给时沿用此刻那块。 */
    fun enter(id: String?) {
        val s = id?.let(::get) ?: current
        synchronized(acting) { acting.addLast(s) }
    }

    /** 那次调用结束;[succeeded] 时它那块屏成为最近操作的屏,失败的不算。 */
    fun leave(succeeded: Boolean) {
        val s = synchronized(acting) { acting.removeLastOrNull() } ?: return
        if (succeeded) settled = s
    }
}
