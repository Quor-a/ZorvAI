package com.ai.assistance.quro.core.mika.cap.phone

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 滚轮选择器(时间、日期、地区这类一格一格转的)的算法,和怎么转无关:认值、算方向和格数、估一格多高。
 * 端口照它在一次工具调用里转到位,不需要模型一格一格看。
 *
 * 「前进」指滚轮上的值往后走(下面那一项转到中间,数字通常变大);是不是变大由读回来的值校正。
 */
object WheelMath {
    private val ONLY_NUMBER = Regex("""^-?\d{1,6}$""")
    private val LEADING_NUMBER = Regex("""^\D{0,3}?(\d{1,6})(?!\d)""")
    private val DIGITS = Regex("""(?<!\d)\d{1,6}(?!\d)""")
    /** 一项的文字里把值和状态词(「已选中」「selected」……)、单位隔开的标点。 */
    private val SEPARATORS = Regex("""[,，、:：;；|/()（）\[\]【】<>《》·•]+""")

    /** 比较文字用:去空白、全角数字换半角、忽略大小写。 */
    fun normalize(s: String): String = buildString {
        for (c in s.trim()) when {
            c.isWhitespace() -> Unit
            c in '０'..'９' -> append('0' + (c - '０'))
            else -> append(c.lowercaseChar())
        }
    }

    /**
     * 显示的文字里的整数:"09" → 9,"9时"/"9 点" → 9,"2026年" → 2026,"第3周" → 3;
     * 无障碍名字常在值前后带状态词("已选中，21"、"Selected, 21"、"21 (selected)"),整段只有一个数时就是它。
     * 有几个数时取开头附近的第一个(前面的字超过三个为 null);没有数字为 null。
     */
    fun number(s: String?): Int? {
        val t = s?.let(::normalize)?.ifEmpty { null } ?: return null
        if (ONLY_NUMBER.matches(t)) return t.toIntOrNull()
        DIGITS.findAll(t).toList().singleOrNull()?.let { return it.value.toIntOrNull() }
        return LEADING_NUMBER.find(t)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** 按标点切开的几段(已按 [normalize] 处理):"已选中，21" → [已选中, 21]。 */
    fun segments(s: String): List<String> = normalize(s).split(SEPARATORS).filter { it.isNotEmpty() }

    /**
     * 报给模型的值:名字里带着状态词时只留值那一段("已选中，21" → "21"、"Selected, 21" → "21");
     * 切不出唯一一段带数字的就原样(文字值不猜哪段是状态词)。
     */
    fun clean(label: String?): String? {
        val t = label?.trim()?.ifEmpty { null } ?: return null
        val parts = t.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return t
        return parts.filter { p -> p.any { it.isDigit() || it in '０'..'９' } }.singleOrNull() ?: t
    }

    /** 目标本身就是一个数(「9」「09」「２０２６」),不是带数字的文字。 */
    fun numericTarget(target: String): Int? = normalize(target).takeIf(ONLY_NUMBER::matches)?.toIntOrNull()

    /**
     * 滚轮此刻显示的 [shown] 是不是要的 [target]:目标是纯数字时按数比("09" 就是 "9","40分" 就是 "40",
     * "已选中，21" 就是 "21");否则文字相同,或显示的(或按标点切开的某一段)以目标开头/结尾(「北京市」对「北京」)。
     */
    fun matches(shown: String?, target: String): Boolean {
        val s = shown?.let(::normalize)?.ifEmpty { null } ?: return false
        val t = normalize(target).ifEmpty { return false }
        numericTarget(target)?.let { return number(s) == it }
        fun affix(x: String) = x == t || x.startsWith(t) || x.endsWith(t)
        return affix(s) || segments(s).any(::affix)
    }

    /**
     * 从上到下看得到的几项估出循环的长度:数字里出现一次「往下变小」(…58 59 00 01…),循环长度 = 断点前的最大值 − 断点后的最小值 + 1;
     * 看不出循环(全是递增、不是数字、太少)为 null。
     */
    fun modulus(visible: List<String>): Int? {
        val n = visible.map { number(it) ?: return null }
        if (n.size < 2) return null
        val drops = (1 until n.size).filter { n[it] < n[it - 1] }
        if (drops.size != 1) return null
        val i = drops.single()
        return (n.subList(0, i).max() - n.subList(i, n.size).min() + 1).takeIf { it >= 2 }
    }

    /** 从 [current] 到 [target] 要往数大的那边走几格(负数往小的那边):循环长度 [modulus] 已知时取近的那一边。 */
    fun distance(current: Int, target: Int, modulus: Int?): Int {
        val d = target - current
        if (modulus == null || modulus < 2) return d
        val m = ((d % modulus) + modulus) % modulus
        return if (m <= modulus / 2) m else m - modulus
    }

    /** 数从 [before] 变成 [after] 走了几格(循环长度已知时 59→0 是 +1 不是 −59);不是数字为 null。 */
    fun moved(before: String?, after: String?, modulus: Int?): Int? {
        val b = number(before) ?: return null
        val a = number(after) ?: return null
        return distance(b, a, modulus)
    }

    /** 一格多高:相邻几项中心的间距取中位数;不到两项、间距太小为 null。 */
    fun itemHeight(centers: List<Int>): Int? {
        val sorted = centers.sorted()
        val gaps = (1 until sorted.size).map { sorted[it] - sorted[it - 1] }.filter { it > 2 }
        if (gaps.isEmpty()) return null
        return gaps.sorted()[gaps.size / 2]
    }

    /**
     * 选中的那一项:只有一项标着选中([Item.selected],无障碍的 isSelected)就是它;各项都带着自己的高度时,
     * 是自己的上下范围盖住中线 [centerY] 的那一项(都没盖住为 -1:中间那一格没报出来,不拿上下的邻居充数);
     * 否则按 [selected] 找中线上的。
     */
    fun selectedIndex(items: List<Item>, centerY: Int, itemHeight: Int?): Int {
        items.indices.filter { items[it].selected }.singleOrNull()?.let { return it }
        if (items.isNotEmpty() && items.all { it.height > 0 }) {
            return items.indices.filter { abs(items[it].centerY - centerY) * 2 <= items[it].height }
                .minByOrNull { abs(items[it].centerY - centerY) } ?: -1
        }
        return selected(items.map { it.centerY }, centerY, itemHeight)
    }

    /**
     * 中线上那一格没报出来(有的滚轮只报上下两格)、上下紧挨着的两项都是数时,中间那一格的文字:
     * 上下差 2 就是夹在中间的数;差得多时只认转过一圈的接口(59 和 1 中间是 0,22 和 0 中间是 23,12 和 2 中间是 1,
     * 11 和 1 中间是 12,1 和 59 中间是 0)。1–12 的时针在 12 那一格没报出来时上下是 11 和 1,不能按「小的那边减一」推成 0
     * (0 不在这只滚轮上);倒着排(1 在上、11 在下)也是 12。写法照上面那一项(单位;上下有补零的就补零);推不出为 null。
     */
    fun between(above: String, below: String): String? {
        val a = number(above) ?: return null
        val b = number(below) ?: return null
        val mid = when {
            abs(b - a) == 2 -> (a + b) / 2
            // 1–12:11 和 1 中间是 12。要排在「小的那边减一」之前,否则会推成 0
            (a == 11 && b == 1) || (a == 1 && b == 11) -> 12
            b < a && b <= 2 -> if (b >= 1) b - 1 else a + 1
            a < b && a <= 2 -> if (a >= 1) a - 1 else b + 1
            else -> return null
        }
        if (mid < 0) return null
        val token = DIGITS.find(above.trim())?.value ?: return null
        val other = DIGITS.find(below.trim())?.value.orEmpty()
        // 补零的滚轮(上下有一项以 0 开头,如 09、01)照样补零到同样宽
        val padded = listOf(token, other).any { it.length > 1 && it.startsWith('0') }
        return above.trim().replaceFirst(token, if (padded) mid.toString().padStart(token.length, '0') else mid.toString())
    }

    /**
     * 从上到下的几项里,和第 [anchor] 项连成一列、间距差不多的那一段:同一块里滚轮上下的标题、按钮不算进来。
     * 一格多高按它和上下相邻项的间距(取小的)算。不到三项、估不出一格多高时原样返回。
     */
    fun column(items: List<Item>, anchor: Int): List<Item> {
        if (items.size < 3 || anchor !in items.indices) return items
        val near = listOfNotNull(
            items.getOrNull(anchor - 1)?.let { items[anchor].centerY - it.centerY },
            items.getOrNull(anchor + 1)?.let { it.centerY - items[anchor].centerY },
        ).filter { it > 2 }
        val h = near.minOrNull() ?: return items
        fun even(gap: Int) = gap > h * 0.4 && gap < h * 1.8
        var lo = anchor
        var hi = anchor
        while (lo > 0 && even(items[lo].centerY - items[lo - 1].centerY)) lo--
        while (hi < items.lastIndex && even(items[hi + 1].centerY - items[hi].centerY)) hi++
        return items.subList(lo, hi + 1)
    }

    /** 中线 [centerY] 上的那一项的下标:中心离中线最近的;离得超过大半格(没有正对中线的)为 -1。 */
    fun selected(centers: List<Int>, centerY: Int, itemHeight: Int?): Int {
        val best = centers.indices.minByOrNull { abs(centers[it] - centerY) } ?: return -1
        if (itemHeight != null && abs(centers[best] - centerY) > itemHeight * 0.75) return -1
        return best
    }

    /**
     * 要往前转几格才到(负数往后):目标就在看得到的几项里时按位置差算(下面的是前进);目标和选中的都是数字时按数差算
     * (看得出循环就走近的一边,眼前看不出时用之前看出的 [knownModulus];[increasingForward] 为 false 时前进是变小);
     * 都不行(文字列表、目标不在眼前)为 null。
     */
    fun stepsTo(visible: List<String>, selectedIndex: Int, target: String, increasingForward: Boolean = true, knownModulus: Int? = null): Int? {
        if (selectedIndex !in visible.indices) return null
        visible.indexOfFirst { matches(it, target) }.takeIf { it >= 0 }?.let { return it - selectedIndex }
        val t = numericTarget(target) ?: return null
        val cur = number(visible[selectedIndex]) ?: return null
        val d = distance(cur, t, modulus(visible) ?: knownModulus)
        return if (increasingForward) d else -d
    }

    /**
     * 转过 [steps] 格要拖的距离(像素,正数手指往上拖 = 前进),一格 [itemHeight];一次拖不超过 [maxDrag],超过的分几次。
     * 返回每一次拖的距离。
     */
    fun drags(steps: Int, itemHeight: Int, maxDrag: Int): List<Int> {
        if (steps == 0 || itemHeight <= 0) return emptyList()
        val perDrag = (maxDrag / itemHeight).coerceAtLeast(1)
        val sign = if (steps > 0) 1 else -1
        val out = mutableListOf<Int>()
        var left = abs(steps)
        while (left > 0) {
            val n = minOf(left, perDrag)
            out += sign * n * itemHeight
            left -= n
        }
        return out
    }

    /** 滚轮里看得到的一项:文字和中心(像素);[selected] 无障碍标着选中;[height] 它自己有多高(不知道为 0)。 */
    data class Item(val text: String, val centerX: Int, val centerY: Int, val selected: Boolean = false, val height: Int = 0)

    /**
     * 一个能竖着滚的容器看上去是不是滚轮,宁可漏认不错认:三到九项、每项都是短的值(不超过 [MAX_ITEM_CHARS] 个字)、互不相同,
     * 都在容器横向的正中(偏离不到宽度的 15%),从上到下等距排着(最大间距不到最小的 1.5 倍)。
     * 设置页那种靠左排的短标签、聊天列表都不像。
     */
    fun looksLikeWheel(items: List<Item>, left: Int, right: Int): Boolean {
        if (items.size !in 3..9 || right <= left) return false
        val texts = items.map { normalize(it.text) }
        if (texts.any { it.isEmpty() || it.length > MAX_ITEM_CHARS } || texts.toSet().size != texts.size) return false
        val mid = (left + right) / 2.0
        if (items.any { abs(it.centerX - mid) > (right - left) * 0.15 }) return false
        val ys = items.map { it.centerY }
        if (ys.zipWithNext().any { (a, b) -> b <= a }) return false
        val gaps = ys.zipWithNext { a, b -> b - a }
        return gaps.max() < gaps.min() * 1.5
    }

    private const val MAX_ITEM_CHARS = 8

    /** 拖了 [dragged] 像素实际转过 [moved] 格:一格实际多高;没转为 null。 */
    fun calibrated(dragged: Int, moved: Int): Int? =
        if (moved == 0) null else abs((dragged.toDouble() / moved).roundToInt()).takeIf { it > 2 }
}

/**
 * 按无障碍「往前/往后滚」转滚轮时的下一步:每批之后把读回的值交进来,它说再往哪边滚几下,或停。
 * 一下转过的不一定是一格(列表一下滚一页),所以每批之后按读回的值重算:
 * - 目标是数:按数差定方向和下数;第一次读回时看「往前」是变大还是变小;值不动了说明这一边到头、目标不在范围里,停;
 *   来回越过目标好几次(一下不止一格,停不准)也停,交给拖动去校准。
 * - 目标是文字:在眼前就按位置差;不在就一下一下往前扫,到头了反过来扫,两头都到了停。
 */
class WheelSteps(private val target: String, private val cap: Int = 80, private val burst: Int = 5) {
    sealed interface Step {
        data object Done : Step
        /** 往 [forward] 那边滚 [count] 下。 */
        data class Act(val forward: Boolean, val count: Int) : Step
        /**
         * 停下:[reason] 为 at_end(到头了,目标不在这一边)、not_moving(循环的滚轮滚了不动)、cap(滚了太多下)、
         * overshoot(停不准)、unknown_value(读不出值)。
         */
        data class Stop(val reason: String) : Step
    }

    /** 到目前为止滚了几下。 */
    var actions: Int = 0
        private set
    private val numeric = WheelMath.numericTarget(target)
    private var increasingForward = true
    private var calibrated = false
    private var modulus: Int? = null
    private var last: String? = null
    private var lastAct: Step.Act? = null
    private var reversals = 0
    private var scanForward = true
    private var scanReversed = false

    /** 刚才那批之后:此刻选中的值 [current] 和看得到的几项 [visible](从上到下)。第一次调时还没滚过。 */
    fun next(current: String?, visible: List<String> = emptyList()): Step {
        if (WheelMath.matches(current, target)) return Step.Done
        if (current == null) return Step.Stop("unknown_value")
        WheelMath.modulus(visible)?.let { modulus = it }
        val prev = lastAct
        val unchanged = prev != null && current == last
        if (actions >= cap) return Step.Stop("cap")
        if (numeric != null && WheelMath.number(current) != null) {
            if (prev != null) {
                // 值不动:看得出循环的滚轮没有头,是这一下没起作用,不是到头了
                if (unchanged) return Step.Stop(if (modulus != null) "not_moving" else "at_end")
                val moved = WheelMath.moved(last, current, modulus)
                if (!calibrated && moved != null && moved != 0) {
                    calibrated = true
                    if ((moved > 0) != prev.forward) increasingForward = false
                }
            }
            val d = WheelMath.distance(WheelMath.number(current)!!, numeric, modulus).let { if (increasingForward) it else -it }
            val forward = d > 0
            val turned = prev != null && forward != prev.forward
            if (turned && ++reversals > 3) return Step.Stop("overshoot")
            // 刚越过目标往回走:一下一下来
            val n = if (turned) 1 else minOf(abs(d), burst)
            return act(forward, n.coerceAtMost(cap - actions), current)
        }
        val sel = visible.indexOfFirst { it == current }
        val at = visible.indexOfFirst { WheelMath.matches(it, target) }
        if (sel >= 0 && at >= 0) return act(at > sel, minOf(abs(at - sel), burst, cap - actions), current)
        if (unchanged) {
            if (scanReversed) return Step.Stop("at_end")
            scanReversed = true
            scanForward = !scanForward
        }
        return act(scanForward, 1, current)
    }

    private fun act(forward: Boolean, count: Int, current: String?): Step {
        val a = Step.Act(forward, count.coerceAtLeast(1))
        last = current
        lastAct = a
        actions += a.count
        return a
    }
}

/**
 * 拖动转滚轮(App 自己画的、不能无障碍滚的)时的下一步:每次拖完把读回的这一列交进来,它说再拖几格、怎么拖,或停。
 * - 按 [WheelMath.stepsTo] 定格数(数字按数差,看得出循环走近的一边,之前看出过的循环长度记着);第一次转动时看「往上拖」
 *   是变大还是变小,按实际转过的格数校准一格多高。
 * - 拖了值不动不等于到头:只有看不出循环、往要去的那边已经没有项时才算到头(at_end);否则是这一下没起作用,
 *   下次换个快慢(只差一格的还拖远一点),连着 [maxStill] 次不动报 not_moving。
 * - 文字列表、目标不在眼前:一次翻过看得到的几项往一边扫,到头了反过来,两头都到了停。
 */
class WheelDrags(private val target: String, rowHeight: Int, private val maxMoves: Int = 5, private val maxStill: Int = 3) {
    sealed interface Step {
        data object Done : Step
        /** 拖过 [steps] 格(正数手指往上 = 前进),距离再乘 [boost];[slow] 慢拖不甩,否则快划一下。 */
        data class Drag(val steps: Int, val boost: Double, val slow: Boolean) : Step
        /** 停下:[reason] 为 at_end、not_moving、not_reached(转了几轮没到,或按数算到了却没有这个值)、unknown_value。 */
        data class Stop(val reason: String) : Step
    }

    /** 一格多高(像素):先按看得到的几项估,转动后按实际转过的格数校准。 */
    var itemHeight: Int = rowHeight.coerceAtLeast(1)
        private set
    private val numeric = WheelMath.numericTarget(target)
    private var started = false
    private var increasingForward = true
    private var directionKnown = false
    private var modulus: Int? = null
    private var last: String? = null
    private var lastDrag: Step.Drag? = null
    private var lastScanning = false
    private var scanForward = true
    private var scanReversed = false
    private var moves = 0
    private var still = 0

    /**
     * 刚才那次拖动之后:这一列从上到下的几项 [items]、各自中心的纵坐标 [centers]、选中项下标 [selected](找不到为 -1),
     * 和手指实际走过的像素 [dragged](往上为正;第一次调时还没拖过,给 0)。
     */
    fun next(items: List<String>, selected: Int, centers: List<Int> = emptyList(), dragged: Int = 0): Step {
        val current = items.getOrNull(selected)
        if (WheelMath.matches(current, target)) return Step.Done
        if (items.isEmpty()) return Step.Stop("unknown_value")
        if (!started) {
            started = true
            WheelMath.itemHeight(centers)?.let { itemHeight = it }
        }
        WheelMath.modulus(items)?.let { modulus = it }
        val prev = lastDrag
        if (prev != null) {
            if (current != last) {
                still = 0
                moves++
                val moved = WheelMath.moved(last, current, modulus)
                if (moved != null && moved != 0) {
                    if (!directionKnown && numeric != null) {
                        directionKnown = true
                        if ((moved > 0) != (dragged > 0)) increasingForward = false
                    }
                    WheelMath.calibrated(dragged, moved)?.let { itemHeight = it }
                }
            } else if (atEnd(items, selected, forward = prev.steps > 0)) {
                if (!lastScanning || scanReversed) return Step.Stop("at_end")
                scanReversed = true
                scanForward = !scanForward
            } else {
                still++
            }
        }
        if (still >= maxStill) return Step.Stop("not_moving")
        if (moves >= maxMoves) return Step.Stop("not_reached")
        val planned = WheelMath.stepsTo(items, selected, target, increasingForward, modulus)
        // 按数算已经到了却没对上:这个值不在滚轮上(比如 0–59 里找 75)
        if (planned == 0) return Step.Stop("not_reached")
        lastScanning = planned == null
        val steps = planned ?: ((items.size - 1).coerceAtLeast(1) * if (scanForward) 1 else -1)
        last = current
        // 上一下没起作用:换个快慢再来;只差一格的拖得短,再拖远一点(拖得多的不加,免得一起作用就转过头、把校准带偏)
        val boost = if (abs(steps) <= 1) 1.0 + BOOST * still else 1.0
        return Step.Drag(steps, boost, slow = still % 2 == 0).also { lastDrag = it }
    }

    /** 看不出循环,往要去的那边(前进是下面)已经没有项了。 */
    private fun atEnd(items: List<String>, selected: Int, forward: Boolean): Boolean {
        if (modulus != null || items.size < 2 || selected !in items.indices) return false
        return if (forward) selected == items.lastIndex else selected == 0
    }

    private companion object {
        const val BOOST = 0.6
    }
}
