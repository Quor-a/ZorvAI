package com.ai.assistance.quro.core.mika.action

/** 子步画面怎么存:交给调用方(模型单步按自己的顺序存)、界面变了才当场存、不存。 */
enum class Frames { Caller, WhenChanged, None }

/** 被拦下时的说明写给谁看:模型(中文说明、确认流程)、页面脚本(脚本拿到的错误)、调用方自己按代码说明。 */
enum class RefusalStyle { Model, Script, Code }

/**
 * 一种来源和别的来源有意不同的地方,只在这里写;执行顺序本身对所有来源都一样。
 * [source] 是运行记录里的 source。
 */
data class OriginPolicy(
    val source: String,
    /** 能用用户的同意放行付款;其余来源碰到付款就停,交还模型,从不假设用户同意过。 */
    val usesGrant: Boolean,
    /** 没配确认关卡时也判不可逆:子步命中就停,不靠关卡。 */
    val judgeWithoutGate: Boolean,
    /** 找不到点在哪个元素上:true 不执行;false 放行但留痕。 */
    val refuseUnresolved: Boolean,
    /** 守卫一有提示就不执行(回放照路线做,任何异样都交还模型)。 */
    val stopOnGuardWarning: Boolean,
    /** 调用方读屏之前刚等过锁屏关卡(回放要按等完之后的一屏核对),执行前不再等一次。 */
    val gatedByCaller: Boolean,
    /** 读只在所属的那次调用里计数,最多提醒、不叫停任务(页面脚本里的轮询读)。 */
    val isolatedReads: Boolean,
    /** 不为 null 时:动作一旦开始就做完并记下(不可取消),最多等这么久。 */
    val actionCapMs: Long?,
    val frames: Frames,
    /** 记路线要的目标元素与前后锚点(开着回放时)。 */
    val routeTargets: Boolean,
    /** 被确认关卡拦下的这次调用本身就是交给模型的结果,照常记进运行记录。 */
    val recordsRefusal: Boolean,
    val refusals: RefusalStyle,
)

/** 一次动作从哪来;[step] 是它所在的循环轮次。 */
sealed interface Origin {
    val step: Int
    val policy: OriginPolicy

    /** 模型的一次工具调用。 */
    data class Model(override val step: Int) : Origin {
        override val policy get() = MODEL
    }

    /** do_steps 里的一步。 */
    data class Batch(override val step: Int) : Origin {
        override val policy get() = BATCH
    }

    /** web_script 里的一个 `quro.*` 动作。 */
    data class Script(override val step: Int) : Origin {
        override val policy get() = SCRIPT
    }

    /** 经验回放的一步(不调模型)。 */
    data object Replay : Origin {
        override val step = 0
        override val policy get() = REPLAY
    }

    /** MCP 服务端的低层工具调用；没有模型，但仍须经过同一执行入口。 */
    data class McpTool(override val step: Int) : Origin {
        override val policy get() = MCP_TOOL
    }

    companion object {
        /** 一个页面脚本动作的上限:打开网址最多等 20 秒加载,再留余量等页面安静。 */
        const val SCRIPT_ACTION_CAP_MS = 40_000L

        // 只有模型单步能经用户同意放行付款;其余来源碰到付款就停下交还
        private val MODEL = OriginPolicy(
            "model", usesGrant = true, judgeWithoutGate = false, refuseUnresolved = false, stopOnGuardWarning = false,
            gatedByCaller = false, isolatedReads = false, actionCapMs = null, frames = Frames.Caller, routeTargets = true,
            recordsRefusal = true, refusals = RefusalStyle.Model,
        )
        private val BATCH = OriginPolicy(
            "batch", usesGrant = false, judgeWithoutGate = true, refuseUnresolved = true, stopOnGuardWarning = false,
            gatedByCaller = false, isolatedReads = false, actionCapMs = null, frames = Frames.WhenChanged, routeTargets = true,
            recordsRefusal = false, refusals = RefusalStyle.Code,
        )
        // 脚本是网页上的一段程序,不能作为路线的一步回放
        private val SCRIPT = OriginPolicy(
            "script", usesGrant = false, judgeWithoutGate = true, refuseUnresolved = true, stopOnGuardWarning = false,
            gatedByCaller = false, isolatedReads = true, actionCapMs = SCRIPT_ACTION_CAP_MS, frames = Frames.WhenChanged, routeTargets = false,
            recordsRefusal = false, refusals = RefusalStyle.Script,
        )
        // 回放与模型单步一致:没配确认关卡时不判
        private val REPLAY = OriginPolicy(
            "replay", usesGrant = false, judgeWithoutGate = false, refuseUnresolved = true, stopOnGuardWarning = true,
            gatedByCaller = true, isolatedReads = false, actionCapMs = null, frames = Frames.None, routeTargets = true,
            recordsRefusal = false, refusals = RefusalStyle.Code,
        )
        private val MCP_TOOL = OriginPolicy(
            "mcp_tool", usesGrant = false, judgeWithoutGate = true, refuseUnresolved = true, stopOnGuardWarning = true,
            gatedByCaller = false, isolatedReads = false, actionCapMs = null, frames = Frames.None, routeTargets = false,
            recordsRefusal = true, refusals = RefusalStyle.Code,
        )
    }
}

/** 动作的发起者。保留 Origin 作为旧调用方的名称；RunOrigin 只表示这次运行从哪来。 */
typealias Invoker = Origin
