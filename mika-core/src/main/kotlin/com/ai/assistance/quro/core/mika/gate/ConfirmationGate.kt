package com.ai.assistance.quro.core.mika.gate

import com.ai.assistance.quro.core.mika.action.ConfirmChoice
import com.ai.assistance.quro.core.mika.risk.IrrevAction
import com.ai.assistance.quro.core.mika.uistate.Hant
import com.ai.assistance.quro.core.mika.uistate.ScreenSnapshot
import com.ai.assistance.quro.core.mika.uistate.UiElement
import java.math.BigDecimal
import kotlin.math.abs
import com.ai.assistance.quro.core.mika.tool.Verb

/** 模型申报的一次要花钱的操作;字段已去首尾空白、截短,界面上照此显示。[app] 是包名。 */
data class ConfirmationRequest(val action: IrrevAction, val target: String, val content: String, val amount: String?, val app: String)

sealed interface DeclareResult {
    /** 申报的 App 和前台不一致(或读不到前台),不交给用户。 */
    data object Mismatch : DeclareResult
    /** 本任务里用户已经拒绝过同一 App 的同类操作,不再问。 */
    data object Declined : DeclareResult
    /** 付款不走这里,走接管。 */
    data object Unsupported : DeclareResult
    data object Pending : DeclareResult
}

sealed interface Permit {
    data object Allowed : Permit
    /** 没有同意。 */
    data object NoGrant : Permit
    /** 有同意,但和这一步对不上(或已过期);这次同意已作废。 */
    data class Mismatch(val reason: String) : Permit
}

/**
 * 用户对一次要花钱的操作的同意(询问时只有付款要问):只放行紧接着的那一个匹配操作。
 * 同意绑定申报内容:同一前台 App、同类动作、对象出现在屏上(发送时在标题栏),发送时要发的字与申报相等,
 * 申报的金额在屏上;
 * 下一次命中的不可逆操作无论对不对得上都用掉它。中间做了打字以外的写操作(包括返回、打开 App)、
 * 或过了 [MAX_STEPS] 步,同意作废。只能同意这一次;只在一次任务内有效,任务开始和结束时 [reset]。
 * 任务循环和界面回答可能在不同线程,读写都加锁。
 */
class ConfirmationGate {
    private class Grant(val request: ConfirmationRequest, val step: Int)

    private var pending: ConfirmationRequest? = null
    private var grant: Grant? = null
    private val declined = mutableSetOf<Pair<IrrevAction, String>>()

    @Synchronized fun declare(req: ConfirmationRequest, foregroundApp: String?): DeclareResult {
        if (req.action == IrrevAction.pay) return DeclareResult.Unsupported
        if (req.action to req.app in declined) return DeclareResult.Declined
        if (foregroundApp == null || foregroundApp != req.app) return DeclareResult.Mismatch
        pending = req
        return DeclareResult.Pending
    }

    /** [step] 是回答所在的步数,过期按它算。 */
    fun decide(approved: Boolean, step: Int = 0) {
        decide(if (approved) ConfirmChoice.Once else ConfirmChoice.Deny, step)
    }

    /** 按用户的选择记一次性同意。 */
    @Synchronized fun decide(choice: ConfirmChoice, step: Int) {
        val req = pending ?: return
        pending = null
        if (!choice.approved) {
            declined += req.action to req.app
            return
        }
        grant = Grant(req, step)
    }

    /**
     * [screen] 是此刻的一屏;[typing] 是这一步要输入并回车的文字(type_text 时)。
     * [seen] 是看图找到的点复看确认过的所见文字:读屏里没有文字(自绘界面)时,对象可以对它核,
     * 要发的字读不到也就不核(画面上的字核不了)。有同意时不论结果都用掉它。付款永远不放行。
     */
    @Synchronized fun permits(hit: Irreversible, screen: ScreenSnapshot?, step: Int, typing: String? = null, seen: List<String> = emptyList()): Permit {
        val g = grant ?: return Permit.NoGrant
        grant = null
        val r = g.request
        val reason = when {
            hit.action == IrrevAction.pay -> "payment is never approved here"
            step - g.step > MAX_STEPS -> "the approval expired ($MAX_STEPS steps)"
            hit.action != r.action -> "approved ${r.action.name} but this step is ${hit.action.name}"
            screen?.packageName == null || screen.packageName != r.app -> "approved in ${r.app} but the screen shows ${screen?.packageName ?: "unknown"}"
            !targetShown(r, screen) && !(textless(screen) && seenShows(r, seen)) -> "the target \"${r.target}\" is not shown " + (if (r.action == IrrevAction.send) "in the title area" else "on the screen")
            r.action == IrrevAction.send && !contentMatches(r.content, screen, typing) && !(textless(screen) && seenShows(r, seen)) -> "the text to send is not exactly what was approved"
            !amountShown(r.amount, screen, hit.matched) -> "the approved amount ${r.amount ?: "(none)"} is not on the screen"
            else -> return Permit.Allowed
        }
        return Permit.Mismatch(reason)
    }

    /** 读屏里一个带字的元素都没有(自绘界面、画布)。 */
    private fun textless(s: ScreenSnapshot) = s.elements.none { it.name.isNotBlank() }

    /** 申报的对象出现在复看确认过的所见文字里(至少两个字)。 */
    private fun seenShows(r: ConfirmationRequest, seen: List<String>): Boolean {
        val t = norm(r.target).take(MATCH_CHARS)
        return t.length >= 2 && seen.any { t in norm(it) }
    }

    /** 执行了一个写操作:打字和清空输入框不影响同意,其余(点别处、滑动、返回、打开 App)让它作废。 */
    /** 有一次已同意、还没用掉的操作。 */
    val hasGrant: Boolean @Synchronized get() = grant != null

    /** 用户同意了、还没被用掉的那次操作;没有为 null。 */
    val granted: ConfirmationRequest? @Synchronized get() = grant?.request

    /** [verb]:刚执行的写操作做的动作;输入、清空不作废同意(同意的就是要输入的这一步)。 */
    @Synchronized fun noteWrite(verb: Verb?) {
        if (verb !in KEEPS_GRANT) grant = null
    }

    @Synchronized fun reset() {
        pending = null
        grant = null
        declined.clear()
    }

    /**
     * 对象要在屏上不可编辑的文字里(输入框里打的不算),至少两个字;
     * 发送时只认屏幕顶部的标题栏(聊天对象的名字在那里),其余动作屏上任何地方都行。
     */
    private fun targetShown(r: ConfirmationRequest, s: ScreenSnapshot): Boolean {
        val t = norm(r.target).take(MATCH_CHARS)
        if (t.length < 2) return false
        return s.elements.any { e ->
            !e.editable && t in norm(e.name) && (r.action != IrrevAction.send || (e.bounds.size == 4 && e.bounds[1] <= TITLE_BAND))
        }
    }

    /**
     * 要发的就是同意的那段话:点发送时某个输入框里的字和它相等,type_text 回车时要输入的字和它相等。
     * 申报被截短(以 … 结尾)时比前缀。
     */
    private fun contentMatches(content: String, s: ScreenSnapshot, typing: String?): Boolean {
        val capped = content.endsWith("…")
        val c = norm(content.removeSuffix("…"))
        fun same(x: String) = norm(x).let { if (capped) it.startsWith(c) else it == c }
        if (typing != null) return same(typing)
        return s.elements.any { it.editable && !it.hint && same(it.name) }
    }

    /**
     * 申报的金额只和屏上带币种(¥/￥、元、$)的数比:「已售25件」「满25减3」不算;输入框和提示语里的不算;
     * 紧挨着优惠、减、抵扣、运费、券、红包、已省(或带负号)的金额不算。
     * 有合计/实付/应付/总计时:可滚动区外的每个合计都要等于申报的金额;都在滚动区里时看离按钮最近的那个;
     * 合计字样在、金额读不出(同一行后面几段拼起来也读不出)时不放行。
     * 没有合计时要等于可滚动区之外最大的那个金额。申报里没有数字不放行。
     */
    private fun amountShown(amount: String?, s: ScreenSnapshot, button: String): Boolean {
        val declared = amount?.let { currencyFree(it) }?.let { NUMBER.find(it)?.value }?.toBigDecimalOrNull() ?: return false
        fun same(x: BigDecimal) = x.compareTo(declared) == 0
        val shown = s.elements.filter { !it.editable && !it.hint }
        val scrollables = s.elements.filter { it.scrollable }
        fun outside(e: UiElement) = scrollables.none { inside(e, it) }
        val labels = shown.filter { e -> TOTAL_LABELS.any { it in Hant.fold(e.name) } }
        if (labels.isNotEmpty()) {
            val totals = labels.map { it to totalOf(it, shown) }
            if (totals.any { it.second == null }) return false
            val out = totals.filter { outside(it.first) }
            if (out.isNotEmpty()) return out.all { same(it.second!!) }
            val b = shown.firstOrNull { it.name.trim() == button }
            val near = totals.minByOrNull { (e, _) -> if (b == null) -top(e) else abs(centerY(e) - centerY(b)) }!!
            return same(near.second!!)
        }
        val largest = shown.filter(::outside).flatMap { amounts(it.name) }.maxOrNull() ?: return false
        return same(largest)
    }

    /** 合计标签后面的金额:先看它自己,再把同一行右边的几段按横向顺序拼上去看。 */
    private fun totalOf(label: UiElement, shown: List<UiElement>): BigDecimal? {
        fun after(text: String) = TOTAL_LABELS.mapNotNull { w -> text.indexOf(w).takeIf { it >= 0 }?.let { text.substring(it + w.length) } }
            .firstNotNullOfOrNull { amounts(it).firstOrNull() }
        return after(Hant.fold(label.name)) ?: after(Hant.fold(IrreversibleDetector.rowText(label, shown)))
    }

    private fun currencyFree(t: String) = t.replace(THOUSANDS, "")

    /** 带币种的金额,去掉紧挨着优惠一类字样或带负号的。 */
    private fun amounts(name: String): List<BigDecimal> {
        val t = currencyFree(Hant.fold(name))
        return CURRENCY.findAll(t).filter { m ->
            val before = t.substring(maxOf(0, m.range.first - DISCOUNT_WINDOW), m.range.first)
            DISCOUNTS.none { it in before } && !before.trimEnd().endsWith("-")
        }.mapNotNull { m -> m.groupValues[1].ifEmpty { m.groupValues[2] }.toBigDecimalOrNull() }.toList()
    }

    private fun top(e: UiElement) = if (e.bounds.size == 4) e.bounds[1] else 0
    private fun centerY(e: UiElement) = if (e.bounds.size == 4) (e.bounds[1] + e.bounds[3]) / 2 else 0

    private fun inside(c: UiElement, p: UiElement): Boolean {
        val a = c.bounds; val b = p.bounds
        return a.size == 4 && b.size == 4 && a[0] >= b[0] && a[1] >= b[1] && a[2] <= b[2] && a[3] <= b[3]
    }


    private fun norm(s: String) = s.filterNot { it.isWhitespace() }.lowercase()

    companion object {
        /** 同意后过了这么多步还没用就作废。 */
        const val MAX_STEPS = 3
        const val MATCH_CHARS = 20
        /** 发送对象要在这条线(0–999)以上:屏幕顶部约 15% 的标题栏。 */
        const val TITLE_BAND = 150
        private val NUMBER = Regex("\\d+(\\.\\d+)?")
        private val CURRENCY = Regex("[¥￥$＄]\\s*(\\d+(?:\\.\\d+)?)|(\\d+(?:\\.\\d+)?)\\s*元")
        private val THOUSANDS = Regex("(?<=\\d),(?=\\d{3}(?!\\d))")
        private val TOTAL_LABELS = IrreversibleDetector.TOTAL_LABELS
        private val DISCOUNTS = listOf("优惠", "减", "抵扣", "运费", "券", "红包", "已省")
        private const val DISCOUNT_WINDOW = 4
        private val KEEPS_GRANT = setOf(Verb.TypeText, Verb.ClearText)
    }
}
