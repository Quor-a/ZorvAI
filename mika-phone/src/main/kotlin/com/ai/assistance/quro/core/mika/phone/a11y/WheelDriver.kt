package com.ai.assistance.quro.core.mika.phone.a11y

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.ai.assistance.quro.core.mika.cap.phone.WheelDrags
import com.ai.assistance.quro.core.mika.cap.phone.WheelMath
import com.ai.assistance.quro.core.mika.cap.phone.WheelSteps
import com.ai.assistance.quro.core.mika.uistate.VisibleTextLine
import com.ai.assistance.quro.core.mika.uistate.WheelOutcome
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 把一个滚轮转到一个值,在一次调用里做完。按控件能做什么依次试(能做就做,做不到退到下一种):
 * 1. 滚轮里有输入框(原版的数字滚轮有):直接把值写进去,让它失去焦点确认,读回来;
 * 2. 滚轮能无障碍「往前/往后滚」(原版的数字滚轮一下一格):读出当前值,按 [WheelSteps] 定方向和下数,一批一批滚,每批读回;
 * 3. 都不行(App 自己画的滚轮):读这一列看得到的几项(无障碍读不到就认屏上的字)估一格多高和当前值,在这一列上慢慢拖
 *    (不甩,免得转过头),读回来再校正;拖了值不动就拖远一点、换快一点再试,还不动报 not_moving 交回模型。
 *
 * 给的编号可以是滚轮,也可以是它显示的某一项(常是选中的那一项):这时滚轮是和这一项横向对齐的那一列,
 * 同一块里并排的别的滚轮(时旁边的分)不读、不拖。
 * 读值、拖动、等界面安静都由 [AndroidScreen] 注入,这里只管步骤。
 */
internal class WheelDriver(
    private val width: Int,
    private val height: Int,
    /** 等到界面连续安静 quietMs,最多等 capMs。 */
    private val quiet: suspend (quietMs: Long, capMs: Long) -> Unit,
    /** 一根手指沿这几点拖(像素):[slow] 为 true 时慢拖、松手前停住不甩;false 时像人快快划一下。 */
    private val drag: suspend (points: List<Pair<Int, Int>>, slow: Boolean) -> Boolean,
    /** 认屏上的字(0–999 的坐标);没有为 null。 */
    private val ocr: suspend () -> List<VisibleTextLine>?,
    /** 这块屏上弹着输入法。 */
    private val imeShown: () -> Boolean,
    /** 按一下返回(收起输入法)。 */
    private val back: () -> Boolean,
) {
    private var attempts = 0

    /** 一次读值:选中的那一项 [current](读不出为 null)和这一列看得到的几项(从上到下),[selected] 是选中项的下标。 */
    private class Reading(val current: String?, val items: List<WheelMath.Item>, val selected: Int) {
        val texts get() = items.map { it.text }
    }

    /**
     * 要转的那一列:从 [root] 往下读,只认横向落在 [left]..[right] 里的项;[mid] 是选中那一格的中线;
     * [rowHeight] 是一格多高估不出来时的退路(给的是一项时就是它自己的高度)。
     */
    private class Column(val root: AccessibilityNodeInfo, val box: Rect, val left: Int, val right: Int, val mid: Int, val rowHeight: Int)

    suspend fun set(start: AccessibilityNodeInfo, value: String): WheelOutcome {
        val startBox = Rect().also(start::getBoundsInScreen)
        if (startBox.isEmpty) return outcome(false, null, null, "none", "not_a_wheel")
        val wheel = scrollableWheel(start, startBox)
        val col = columnOf(start, startBox, wheel)
        var now = read(col)
        val from = now.current
        if (from == null && now.items.size < 2) {
            val scrollable = wheel != null || start.isScrollable
            return outcome(false, null, null, "none", if (scrollable) "unknown_value" else "not_a_wheel")
        }
        if (WheelMath.matches(from, value)) return outcome(true, from, from, "none")

        // 1. 直接输入
        editableOf(wheel ?: start)?.let { field ->
            if (typeInto(field, value)) {
                now = settled(col)
                if (WheelMath.matches(now.current, value)) return outcome(true, now.current, from, "text")
            }
        }

        // 2. 无障碍滚动
        if (wheel != null && canStep(wheel)) {
            val steps = WheelSteps(value, cap = MAX_STEPS)
            var stopped: String? = null
            var rechecks = 0
            loop@ while (true) {
                when (val s = steps.next(now.current, now.texts)) {
                    WheelSteps.Step.Done -> {
                        // 读到目标时滚轮可能还在动:停稳了再核对一次,不对就接着滚
                        now = settled(col)
                        if (WheelMath.matches(now.current, value)) return outcome(true, now.current, from, "actions")
                        if (++rechecks > MAX_RECHECKS) { stopped = "not_reached"; break@loop }
                    }
                    is WheelSteps.Step.Stop -> { stopped = s.reason; break@loop }
                    is WheelSteps.Step.Act -> {
                        val action = if (s.forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        var done = 0
                        repeat(s.count) { if (wheel.performAction(action)) done++ }
                        attempts += s.count
                        quiet(STEP_QUIET_MS, STEP_CAP_MS)
                        wheel.refresh()
                        now = settled(col)
                        // 一下都没滚动:这一边到头了(或这一边不让滚)
                        if (done == 0 && !WheelMath.matches(now.current, value)) { stopped = "at_end"; break@loop }
                    }
                }
            }
            if (stopped == "at_end" || stopped == "unknown_value") return outcome(false, now.current, from, "actions", stopped)
        }

        // 3. 拖动
        return dragTo(col, value, from, now)
    }

    private suspend fun dragTo(col: Column, value: String, from: String?, first: Reading): WheelOutcome {
        var now = first
        val plan = WheelDrags(value, rowHeight = col.rowHeight)
        var dragged = 0
        var rechecks = 0
        while (true) {
            when (val s = plan.next(now.texts, now.selected, now.items.map { it.centerY }, dragged)) {
                WheelDrags.Step.Done -> {
                    // 停稳了再核对一次(松手后滚轮还会吸附到最近的一格)
                    now = settled(col)
                    if (WheelMath.matches(now.current, value)) return outcome(true, now.current, from, "drag")
                    if (++rechecks > MAX_RECHECKS) return outcome(false, now.current, from, "drag", "not_reached")
                }
                is WheelDrags.Step.Stop -> return outcome(false, now.current, from, "drag", s.reason)
                is WheelDrags.Step.Drag -> {
                    dragged = dragSteps(col, now, s.steps, plan.itemHeight, s.boost, s.slow)
                        ?: return outcome(false, now.current, from, "drag", "not_reached")
                    now = settled(col)
                }
            }
        }
    }

    /**
     * 在这一列上拖过 [steps] 格(正数手指往上 = 前进),每格 [itemHeight]、再乘 [boost];返回手指实际走过的像素(往上为正),
     * 拖不出去为 null。手指落在这一列的横向正中,往上拖从这一列靠下的地方起、往下拖从靠上的地方起,可以拖出这一列(手指没抬起,
     * 滚轮照样跟着走),只夹在屏内。
     */
    private suspend fun dragSteps(col: Column, r: Reading, steps: Int, itemHeight: Int, boost: Double, slow: Boolean): Int? {
        val h = itemHeight.coerceAtLeast(1)
        val x = r.items.map { it.centerX }.sorted().getOrNull(r.items.size / 2) ?: (col.left + col.right) / 2
        val top = r.items.minOfOrNull { it.centerY - h / 2 } ?: (col.mid - h / 2)
        val bottom = r.items.maxOfOrNull { it.centerY + h / 2 } ?: (col.mid + h / 2)
        val maxDrag = maxOf(bottom - top - h, h * MIN_DRAG_ROWS)
        var dragged = 0
        for (piece in WheelMath.drags(steps, h, maxDrag)) {
            val len = maxOf((abs(piece) * boost).roundToInt(), MIN_DRAG_PX)
            val up = piece > 0
            val y0 = (if (up) bottom - h / 2 else top + h / 2).coerceIn(maxOf(top + 1, 1), minOf(bottom - 1, height - 2).coerceAtLeast(maxOf(top + 1, 1)))
            val y1 = (if (up) y0 - len else y0 + len).coerceIn(1, height - 2)
            if (!drag(listOf(x.coerceIn(0, width - 1) to y0, x.coerceIn(0, width - 1) to y1), slow)) return null
            attempts++
            dragged += y0 - y1
            quiet(DRAG_QUIET_MS, DRAG_CAP_MS)
        }
        return dragged
    }

    /** 往滚轮里的输入框写值并确认(失去焦点时原版滚轮按写进去的值定下来);因此弹出的输入法收起来。 */
    private suspend fun typeInto(field: AccessibilityNodeInfo, value: String): Boolean {
        val imeBefore = imeShown()
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        val set = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        attempts++
        field.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
        quiet(STEP_QUIET_MS, STEP_CAP_MS)
        if (!imeBefore && imeShown()) {
            back()
            quiet(STEP_QUIET_MS, STEP_CAP_MS)
        }
        return set
    }

    // ---------- 读 ----------

    /**
     * 停稳后的读数:滚动、拖动之后滚轮还会转一会儿、吸附到最近的一格(原版数字滚轮一格的动画约 0.3 秒),
     * 也不一定发无障碍事件。隔 [SETTLE_MS] 连读两次值相同才算停稳,至多读 [MAX_SETTLE_READS] 次。
     */
    private suspend fun settled(col: Column): Reading {
        var last = read(col)
        repeat(MAX_SETTLE_READS - 1) {
            delay(SETTLE_MS)
            val again = read(col)
            if (again.current == last.current) return again
            last = again
        }
        return last
    }

    private suspend fun read(col: Column): Reading {
        col.root.refresh()
        col.root.getBoundsInScreen(col.box)
        val found = treeItems(col)
        if (found.isNotEmpty()) {
            val h = WheelMath.itemHeight(found.map { it.centerY })
            val anchor = WheelMath.selectedIndex(found, col.mid, h)
            val items = if (anchor >= 0) WheelMath.column(found, anchor) else found
            val i = WheelMath.selectedIndex(items, col.mid, WheelMath.itemHeight(items.map { it.centerY }) ?: h)
            if (i >= 0) return Reading(items[i].text, items, i)
            // 没有哪一项盖住中线:可能是中间那一格没报出来(只报了上下两格),按上下紧挨着的两项推出中间那一格;
            // 推不出时退回离中线最近的那一项
            val below = items.indexOfFirst { it.centerY > col.mid }
            val mid = (if (below > 0) WheelMath.between(items[below - 1].text, items[below].text) else null) ?: run {
                val j = WheelMath.selected(items.map { it.centerY }, col.mid, WheelMath.itemHeight(items.map { it.centerY }) ?: h)
                return Reading(items.getOrNull(j)?.text, items, j)
            }
            val withMid = items.toMutableList().apply {
                add(below, WheelMath.Item(mid, (items[below - 1].centerX + items[below].centerX) / 2, col.mid, selected = true))
            }
            return Reading(mid, withMid, below)
        }
        // 自己画的滚轮:认这一列里的字
        val lines = ocr().orEmpty().mapNotNull { l ->
            val cx = px((l.bounds[0] + l.bounds[2]) / 2, width)
            val cy = px((l.bounds[1] + l.bounds[3]) / 2, height)
            if (!col.box.contains(cx, cy) || cx !in col.left..col.right || l.text.isBlank() || l.text.trim().length > MAX_ITEM_CHARS) null
            else WheelMath.Item(l.text.trim(), cx, cy)
        }.sortedBy { it.centerY }
        val i = WheelMath.selected(lines.map { it.centerY }, col.mid, WheelMath.itemHeight(lines.map { it.centerY }))
        return Reading(lines.getOrNull(i)?.text, lines, i)
    }

    /** 这一列里看得到、带字的几项(往下找几层),从上到下;同一项不重复。 */
    private fun treeItems(col: Column): List<WheelMath.Item> {
        val out = mutableListOf<WheelMath.Item>()
        val r = Rect()
        fun add(n: AccessibilityNodeInfo): Boolean {
            val label = itemLabel(n) ?: return false
            n.getBoundsInScreen(r)
            if (r.isEmpty || !col.box.contains(r.centerX(), r.centerY()) || r.centerX() !in col.left..col.right) return false
            if (out.none { it.text == label && abs(it.centerY - r.centerY()) < 4 }) out += WheelMath.Item(label, r.centerX(), r.centerY(), n.isSelected, r.height())
            return true
        }
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                if (!c.isVisibleToUser) continue
                if (!add(c) && depth < MAX_DOWN) walk(c, depth + 1)
            }
        }
        if (col.root.childCount == 0) add(col.root) else walk(col.root, 0)
        return out.sortedBy { it.centerY }
    }

    /** 一项的名字;系统单独报的状态(stateDescription,如「已选中」)若被拼进了名字,先拿掉。太长的不算一项。 */
    private fun itemLabel(n: AccessibilityNodeInfo): String? {
        var label = ScreenReader.ownLabel(n) ?: return null
        val state = n.stateDescription?.toString()?.trim()
        if (!state.isNullOrEmpty() && label != state && label.contains(state)) {
            label = label.replace(state, "").trim { it.isWhitespace() || !it.isLetterOrDigit() }.ifEmpty { label }
        }
        return label.takeIf { it.length <= MAX_ITEM_CHARS }
    }

    // ---------- 认滚轮 ----------

    /**
     * 能无障碍滚的滚轮:给的节点自己、往上几层里能滚的那个(给的是滚轮里的一项时),或往下找到的;
     * 往上找到的要横向罩着给的那一项的正中(整页的滚动区罩着几个并排的滚轮,不能拿来滚)。没有为 null。
     */
    private fun scrollableWheel(start: AccessibilityNodeInfo, startBox: Rect): AccessibilityNodeInfo? {
        if (scrollable(start)) return start
        val r = Rect()
        var p = start.parent
        var depth = 0
        while (p != null && depth++ < MAX_UP) {
            if (scrollable(p)) {
                p.getBoundsInScreen(r)
                return p.takeIf { r.centerX() in startBox.left..startBox.right }
            }
            p = p.parent
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(start) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 60) {
            val c = queue.removeFirst()
            if (scrollable(c)) return c
            for (i in 0 until c.childCount) c.getChild(i)?.let(queue::add)
        }
        return null
    }

    /**
     * 要读的那一列。给的是滚轮(能滚的)或里面装着几项:就是它。给的是一项(自己画的滚轮常这样):
     * 往上找最近的、里面和它横向对齐的项不少于三项的那一层,只认横向和它对齐的项,所以旁边的滚轮不算进来。
     */
    private fun columnOf(start: AccessibilityNodeInfo, startBox: Rect, wheel: AccessibilityNodeInfo?): Column {
        val ownHeight = startBox.height().coerceAtLeast(1)
        if (wheel != null && wheel != start && Rect().also(wheel::getBoundsInScreen).let { it.width() <= startBox.width() * 2 }) {
            // 给的是滚轮里的一项、滚轮就这一列宽:读整个滚轮
            val box = Rect().also(wheel::getBoundsInScreen)
            return Column(wheel, box, box.left, box.right, startBox.centerY(), ownHeight)
        }
        if (start.childCount > 0) {
            val own = Column(start, Rect(startBox), startBox.left, startBox.right, startBox.centerY(), startBox.height() / ASSUMED_ROWS)
            if (treeItems(own).size >= 2) return own
        }
        var best: Column? = null
        var p = start.parent
        var depth = 0
        while (p != null && depth++ < MAX_UP) {
            val box = Rect().also(p::getBoundsInScreen)
            val c = Column(p, box, startBox.left, startBox.right, startBox.centerY(), ownHeight)
            val n = treeItems(c).size
            if (n >= 3) return c
            if (n >= 2 && best == null) best = c
            p = p.parent
        }
        return best ?: Column(start, Rect(startBox), startBox.left, startBox.right, startBox.centerY(), ownHeight)
    }

    private fun scrollable(n: AccessibilityNodeInfo): Boolean = n.isScrollable || canStep(n)

    private fun canStep(n: AccessibilityNodeInfo): Boolean = n.actionList.any {
        it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    }

    /** 滚轮自己或里面一层的输入框(原版数字滚轮中间那一格就是)。 */
    private fun editableOf(wheel: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (wheel.isEditable) return wheel
        for (i in 0 until wheel.childCount) {
            val c = wheel.getChild(i) ?: continue
            if (c.isEditable && c.isVisibleToUser) return c
        }
        return null
    }

    private fun px(n: Int, size: Int) = (n * (size - 1) / 999.0).roundToInt()

    private fun outcome(reached: Boolean, value: String?, from: String?, method: String, error: String? = null) =
        WheelOutcome(reached, WheelMath.clean(value), WheelMath.clean(from), attempts, method, if (reached) null else error ?: "not_reached")

    private companion object {
        const val MAX_STEPS = 80
        const val MAX_UP = 5
        const val MAX_DOWN = 4
        /** 一项的名字常带着状态词(「已选中，21」「Selected, 21」),放宽一些。 */
        const val MAX_ITEM_CHARS = 24
        /** 一格多高估不出来时按一屏五行算。 */
        const val ASSUMED_ROWS = 5
        /** 一次拖至少能拖这么多格(列表只读到一两项时也拖得开)、至少这么多像素(拖得太短会被当成点按)。 */
        const val MIN_DRAG_ROWS = 3
        const val MIN_DRAG_PX = 48
        /** 判停稳:隔这么久连读两次值相同;至多读几次;读到目标后停稳了却不对,至多再接着转几次。 */
        const val SETTLE_MS = 350L
        const val MAX_SETTLE_READS = 5
        const val MAX_RECHECKS = 2
        const val STEP_QUIET_MS = 150L
        const val STEP_CAP_MS = 700L
        const val DRAG_QUIET_MS = 300L
        const val DRAG_CAP_MS = 1_500L
    }
}
