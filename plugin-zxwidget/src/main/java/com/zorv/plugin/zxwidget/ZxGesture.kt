package com.zorv.plugin.zxwidget

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.abs

/**
 * 交互基建 —— 所有输入型组件共用的三件事。
 *
 * 做到第五批才补这一层，是因为前三批都是"看 + 点选"，
 * 手势冲突、焦点顺序、长按菜单这些问题根本不会出现。
 * 一旦有了输入型组件，这三件事就是地基——不先定好，
 * 每个组件各写一套，行为必然不一致。
 *
 * 一、手势仲裁：卡片里的横向拖拽 vs 外层列表的纵向滚动
 *    判定错了整个页面手感就废了——想拖滑块结果页面在滚。
 *
 * 二、焦点链：多个输入框之间自动跳焦
 *    zx_pin 每格一个输入位，用户填完一格应该自动进下一格，
 *    而不是手动点八次。
 *
 * 三、长按菜单：上下文操作
 *    气泡里的卡片没有右键，长按是唯一的上下文入口。
 */
object ZxGesture {

    /* ══════════ 一、手势仲裁 ══════════ */

    enum class Axis { HORIZONTAL, VERTICAL, UNDECIDED }

    /**
     * 方向锁定阈值：位移超过这个距离才判定方向。
     * 12dp 是经验值——太小会误判（手指微抖就锁死），
     * 太大会让用户觉得"拖了半天没反应"。
     */
    internal const val SLOP_DP = 12f

    /** 主轴判定：横移量大于纵移量的这个倍数才算横向 */
    internal const val RATIO = 1.4f

    fun decideAxis(delta: Offset): Axis {
        val dx = abs(delta.x); val dy = abs(delta.y)
        if (dx < SLOP_DP && dy < SLOP_DP) return Axis.UNDECIDED
        return if (dx > dy * RATIO) Axis.HORIZONTAL else Axis.VERTICAL
    }

    /**
     * 只接管横向拖拽的 pointerInput —— 纵向留给外层滚动。
     *
     * 这是最关键的一段：Compose 的手势是冒泡的，
     * 如果这里无差别 detectDragGestures，纵向滑动会被组件吃掉，
     * 用户会发现"卡片区域滚不动页面"。
     */
    suspend fun PointerInputScope.detectHorizontalDrag(
        onStart: (Offset) -> Unit = {},
        onEnd: () -> Unit = {},
        onDrag: (Float) -> Unit          // 归一化 0-1 的横向位置
    ) {
        var axis: Axis = Axis.UNDECIDED
        var startX = 0f
        detectDragGestures(
            onDragStart = { p -> axis = Axis.UNDECIDED; startX = p.x; onStart(p) },
            onDragEnd = { axis = Axis.UNDECIDED; onEnd() },
            onDragCancel = { axis = Axis.UNDECIDED }
        ) { change, dragAmount ->
            if (axis == Axis.UNDECIDED) {
                axis = decideAxis(dragAmount)
                // 判定为纵向就彻底放手，让外层 LazyColumn 接管
                if (axis == Axis.VERTICAL) return@detectDragGestures
            }
            if (axis == Axis.HORIZONTAL) {
                onDrag((change.position.x / size.width).coerceIn(0f, 1f))
            }
        }
    }

    /** 只接管纵向拖拽（下拉刷新、纵向滑块用） */
    suspend fun PointerInputScope.detectVerticalDrag(
        onEnd: () -> Unit = {},
        onDrag: (Float) -> Unit
    ) {
        var axis: Axis = Axis.UNDECIDED
        detectDragGestures(
            onDragStart = { axis = Axis.UNDECIDED },
            onDragEnd = { axis = Axis.UNDECIDED; onEnd() },
            onDragCancel = { axis = Axis.UNDECIDED }
        ) { change, dragAmount ->
            if (axis == Axis.UNDECIDED) {
                axis = decideAxis(dragAmount)
                if (axis == Axis.HORIZONTAL) return@detectDragGestures
            }
            if (axis == Axis.VERTICAL) onDrag((change.position.y / size.height).coerceIn(0f, 1f))
        }
    }

    /* ══════════ 二、焦点链 ══════════ */

    /**
     * 焦点链：一组输入位之间的跳转规则。
     *
     * zx_pin 用它做"填完一格跳下一格"，zx_timepicker 用它做"选完时跳分"。
     * 规则做成显式的数据而不是散在各组件里，是为了让"退格回退"这类
     * 行为在所有组件上表现一致。
     */
    class FocusChain(private val size: Int) {
        private var cur = 0

        fun current(): Int = cur
        fun at(i: Int) { cur = i.coerceIn(0, size - 1) }

        /** 填入后前进；已在末位则留在末位 */
        fun advance(): Int { if (cur < size - 1) cur++; return cur }

        /** 退格后回退；若当前位为空则先回退再清空 */
        fun retreat(): Int { if (cur > 0) cur--; return cur }

        /** 跳到第一个空位——用于初始化和粘贴场景 */
        fun firstEmpty(isEmpty: (Int) -> Boolean): Int {
            for (i in 0 until size) if (isEmpty(i)) { cur = i; return i }
            cur = size - 1
            return cur
        }

        fun isComplete(isEmpty: (Int) -> Boolean): Boolean =
            (0 until size).none { isEmpty(it) }
    }

    /* ══════════ 三、长按菜单 ══════════ */

    /** 一项上下文操作 */
    data class Action(
        val key: String,
        val label: String,
        /** 危险操作标红 */
        val danger: Boolean = false
    )

    /** 卡片默认提供的上下文操作 */
    fun defaults(): List<Action> = listOf(
        Action("copy", "复制内容"),
        Action("expand", "展开查看"),
        Action("regen", "让 AI 重画"),
        Action("pin", "钉住这张卡"),
        Action("dismiss", "收起", danger = true)
    )

    /** 长按手势：500ms 是 Material 规范值，不要改 */
    suspend fun PointerInputScope.detectLongPressMenu(
        onLongPress: (Offset) -> Unit
    ) {
        detectTapGestures(onLongPress = onLongPress)
    }

    /**
     * 长按触发后要不要震动反馈。
     * 交给宿主决定——插件不直接持有 Vibrator 权限，
     * 这里只是把"该不该震"这件事表达出来。
     */
    fun shouldHaptic(): Boolean = true

    /**
     * 菜单项点击后的回传。
     * 走 zx_select 而不是新开工具——模型侧只需要知道"用户对哪张卡做了什么"，
     * 多开一个工具只会让工具表更长、模型更难选。
     */
    fun dispatch(ctx: RenderContext, cardId: String, action: Action) {
        ctx.callTool("zx_select", mapOf(
            "choice" to action.key,
            "source" to "zx_longpress",
            "card" to cardId
        ))
    }
}

/** 上述三件事的说明文本，供 /zxgesture 与 zx_gesture 工具输出 */
fun ZxGesture.axisGuide(): String = """
    一、手势仲裁（横向拖拽 vs 纵向滚动）
      方向锁定阈值 ${SLOP_DP}dp：位移超过才判定，太小程序误判、太大用户觉得没反应
      主轴判定：横移 > 纵移 × $RATIO 才算横向

      关键：判定为纵向就彻底放手，让外层 LazyColumn 接管。
      若组件无差别吃掉所有拖拽，用户会发现「卡片区域滚不动页面」。
""".trimIndent()

fun ZxGesture.focusGuide(): String = """
    二、焦点链（多输入位自动跳焦）
      advance()   填入后前进
      retreat()   退格后回退
      firstEmpty() 跳到第一个空位（用于初始化与粘贴）

      zx_pin 每格一个输入位，填完自动进下一格——不做的话用户要手动点八次。
""".trimIndent()

fun ZxGesture.menuGuide(): String = """
    三、长按菜单（气泡里没有右键，长按是唯一的上下文入口）
      默认项：${defaults().joinToString(" / ") { it.label }}
      长按 500ms 触发（Material 规范值，不要改）
      点击走 zx_select 回传，不新开工具——多开工具只会让工具表更长、模型更难选
""".trimIndent()
