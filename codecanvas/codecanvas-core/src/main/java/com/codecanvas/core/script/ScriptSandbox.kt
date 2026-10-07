package com.codecanvas.core.script

/**
 * 脚本沙箱约束。
 *
 * 脚本可能来自 AI 生成或远程下发，**必须**当成不可信输入处理：
 * - 超时：JS/Lua 引擎是单线程同步执行，宿主用看门狗协程 + 引擎中断标志实现硬超时
 * - 指令数：由 DrawList.maxCommands 兜底
 * - 能力：默认禁网、禁文件、禁反射；各引擎在注入全局对象时据此裁剪 API
 */
data class ScriptSandbox(
    val timeoutMs: Long = 3_000L,
    val maxCommands: Int = 20_000,
    val maxLogs: Int = 200,
    /**
     * canvas API 调用预算。
     *
     * JS/Lua 同步执行无法从外部中断，这是唯一能拦住
     * `while(true){ canvas.circle(...) }` 这类「循环里持续画图」失控脚本的手段。
     * 纯计算死循环（循环体不调 canvas）拦不住，只能靠 [staticLoopGuard] 在入口拒绝。
     */
    val instructionBudget: Int = 100_000,
    /**
     * 执行前的静态死循环预检。
     *
     * 开启后，`while(true)` / `for(;;)` 会被直接拒绝而不进引擎。
     * 可能误杀 `while(true){ if(x) break; }` 这类合法写法，
     * 但移动端一个卡死的线程代价远高于一次误报，默认开启。
     */
    val staticLoopGuard: Boolean = true,
    val allowNetwork: Boolean = false,
    val allowFileRead: Boolean = false,
    val allowFileWrite: Boolean = false,
    /** 是否禁止宿主对象注入（开启后脚本只能画图，拿不到任何 Java 对象） */
    val isolateHost: Boolean = true,
    /** 递归/调用深度上限，防栈溢出 */
    val maxCallDepth: Int = 64,
) {
    companion object {
        /** 最严格：只画图，超时短、预算小、开静态预检 */
        val STRICT = ScriptSandbox(
            timeoutMs = 2_000L,
            maxCommands = 5_000,
            instructionBudget = 20_000,
            staticLoopGuard = true,
        )

        /** 宽松：本地调试用，放开文件读与死循环预检 */
        val DEBUG = ScriptSandbox(
            timeoutMs = 15_000L,
            maxCommands = 100_000,
            instructionBudget = 2_000_000,
            staticLoopGuard = false,
            allowFileRead = true,
            isolateHost = false,
        )
    }
}

/**
 * 跨语言统一的「宿主回调」接口。
 *
 * JS / Lua / Python 三端的桥接对象都实现它：脚本调 `canvas.line(...)` 时，
 * 最终都落到同一个 Java 方法，从而保证四条链路行为完全一致。
 */
interface CanvasBinding {

    // 状态
    fun save()
    fun restore()
    fun translate(dx: Float, dy: Float)
    fun rotate(degrees: Float)
    fun scale(sx: Float, sy: Float)
    fun setFill(color: String)
    fun setStroke(color: String, width: Float)
    fun setAlpha(alpha: Float)
    fun setShadow(color: String, blur: Float, dx: Float, dy: Float)
    fun clearShadow()

    // 图元
    fun background(color: String)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float)
    fun rect(x: Float, y: Float, w: Float, h: Float, radius: Float)
    fun circle(cx: Float, cy: Float, r: Float)
    fun oval(x: Float, y: Float, w: Float, h: Float)
    fun arc(x: Float, y: Float, w: Float, h: Float, start: Float, sweep: Float, useCenter: Boolean = false)
    fun polyline(points: FloatArray, closed: Boolean)
    fun path(d: String)
    fun text(str: String, x: Float, y: Float, size: Float, align: String)
    fun richText(spansJson: String, x: Float, y: Float, size: Float = 28f)

    // 信息
    fun width(): Float
    fun height(): Float
    fun log(msg: String)

    /** 宿主传入的 args，脚本侧通过 args.xxx 访问 */
    val args: Map<String, Any?>
}
