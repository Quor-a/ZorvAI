package com.ai.assistance.quro.core.mika.gate

import com.ai.assistance.quro.core.mika.uistate.CdpKeys
import com.ai.assistance.quro.core.mika.uistate.KeyCombo
import com.ai.assistance.quro.core.mika.uistate.KeyModifier
import com.ai.assistance.quro.core.mika.uistate.ScreenDiagnosis
import com.ai.assistance.quro.core.mika.uistate.ElementContext
import com.ai.assistance.quro.core.mika.uistate.Hant
import com.ai.assistance.quro.core.mika.uistate.ScreenSnapshot
import com.ai.assistance.quro.core.mika.uistate.UiElement
import com.ai.assistance.quro.core.mika.risk.GateTarget
import com.ai.assistance.quro.core.mika.risk.IrrevAction
import com.ai.assistance.quro.core.mika.uistate.PressKeys
import com.ai.assistance.quro.core.mika.tool.LOCATED_REF
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** [matched]:命中的那段文字(按钮名、资源 ID、target 参数、输入框或滑动的起点)。 */
data class Irreversible(val action: IrrevAction, val matched: String)

/**
 * 写操作执行前的词表兜底:只认按钮名、回车/确认键、横滑列表行这几种信号,不做语义理解。
 * 拿不准时宁可多拦(模型补一次 request_confirmation 就能继续),只有 submit 故意只认完全相等。
 *
 * 读屏:[classify] 的 snapshot 是 ref 所属的那次读屏(没有 ref 时是最近一次),fresh 是此刻重新读的一屏。
 * 坐标、回车、滑动按 fresh 判(读不到才退回 snapshot);ref 两份都判,任一命中就算。
 */
object IrreversibleDetector {
    /**
     * 名字里出现就算(短名字,或拼起来的名字里的每一段);但前面是「待」「已」、
     * 后面是 [STATUS_SUFFIXES] 的是订单状态或入口(待付款、付款成功、购买记录),不算。
     */
    val STRONG: Map<String, IrrevAction> = linkedMapOf(
        "支付" to IrrevAction.pay, "付款" to IrrevAction.pay, "转账" to IrrevAction.pay,
        "删除" to IrrevAction.delete, "撤回" to IrrevAction.delete,
        "下单" to IrrevAction.order, "购买" to IrrevAction.order, "结算" to IrrevAction.order, "结账" to IrrevAction.order,
        "取消订单" to IrrevAction.order, "确认收货" to IrrevAction.order, "退款" to IrrevAction.order,
        "转发" to IrrevAction.send,
    )
    private const val STRONG_MAX_LEN = 12
    private val STATUS_PREFIXES = listOf('待', '已')
    val STATUS_SUFFIXES = listOf("成功", "记录", "售后", "明细", "方式", "列表", "中心", "入口", "码")

    /** 这些付款词只认按钮本身,或前面是「立即」「确认」「去」(「充值中心」「话费充值」「优惠买单」是入口,不算)。 */
    val EXACT_PAY = listOf("收银台", "买单", "充值", "储值", "提现", "转出", "打赏", "缴费")
    private val EXACT_PAY_PREFIXES = listOf("", "立即", "确认", "去")

    /** 红包只认按钮上的这几种说法(「微信红包」一类是聊天里的气泡)。 */
    val RED_PACKET = listOf("红包", "发红包", "塞钱进红包")

    /** 中文:等于词,或以词开头且只多出几个字(如「发送(1)」)。 */
    val PREFIX_WORDS: Map<IrrevAction, List<String>> = linkedMapOf(
        IrrevAction.send to listOf("发送", "传送", "发布", "发表"),
        IrrevAction.delete to listOf("清空", "移除", "注销", "退出群聊"),
        IrrevAction.order to listOf("提交订单"),
    )
    private const val PREFIX_SLACK = 4

    /** 等于词,或后面只跟标点(「评论(23)」「回复 3」是入口和计数,不算)。 */
    val PUNCT_WORDS: Map<IrrevAction, List<String>> = linkedMapOf(IrrevAction.send to listOf("评论", "回复"))

    /** 等于词,或前面是「立即」「确认」。 */
    val BOOKING_WORDS = listOf("预约", "报名")
    private val BOOKING_PREFIXES = listOf("", "立即", "确认")

    /** 只认完全相等:「提交搜索」一类不拦。 */
    val SUBMIT_WORDS = listOf("提交", "确认提交", "送出", "submit")

    /**
     * 一点就替用户联系别人、投出申请的按钮(点下去网站就把招呼语、简历发给对方),按提交算:
     * 等于词,或前面是「立即」「马上」「一键」「去」,后面只跟标点。
     * 「继续沟通」「已投递」「沟通记录」「联系客服」「联系我们」「私信」「消息」不算:是已有的对话、记录、客服页,
     * 或只是打开写消息的地方(真发出去的是之后的发送)。
     */
    val CONTACT_WORDS = listOf(
        "沟通", "打招呼", "打个招呼", "发起聊天", "发起沟通", "联系ta", "联系他", "联系她", "联系对方",
        "投递", "投简历", "投递简历", "应聘",
    )
    private val CONTACT_PREFIXES = listOf("", "立即", "马上", "一键", "去")
    /** 「申请」本身太泛(申请退款、申请权限):只认这几种说法。 */
    val APPLY_WORDS = listOf("立即申请", "一键申请", "申请职位", "申请岗位", "申请该职位")
    /** 英文:整个名字等于这些(不分大小写、空白合并、去掉末尾标点);单独的 Apply(筛选面板的应用)、Contact、Message 不算。 */
    val CONTACT_ENGLISH = listOf(
        "say hi", "say hello", "send greeting", "apply now", "easy apply", "quick apply", "apply for this job", "apply to this job",
        "submit application", "contact now", "contact recruiter", "contact employer", "contact seller", "contact host", "contact owner",
        "contact landlord", "contact agent",
    )

    /** 英文:名字以这个词开头(按单词边界,不分大小写),不限长度,如「Send message」「Delete conversation」。 */
    val ENGLISH: Map<String, IrrevAction> = linkedMapOf(
        "send" to IrrevAction.send, "post" to IrrevAction.send, "reply" to IrrevAction.send,
        "pay" to IrrevAction.pay,
        "delete" to IrrevAction.delete, "remove" to IrrevAction.delete, "clear all" to IrrevAction.delete,
        "place order" to IrrevAction.order, "buy now" to IrrevAction.order, "checkout" to IrrevAction.order,
    )

    /** 弹窗、底部面板上的通用确认:同一屏上有强词或金额时才算。 */
    val GENERIC = listOf("确认", "确定", "ok", "完成", "confirm")
    private val AMOUNT = Regex("[¥￥$＄]\\s*\\d|\\d+(\\.\\d+)?\\s*元")
    /** 带这些字的金额是合计,不论在不在可滚动区里都算上下文。 */
    val TOTAL_LABELS = listOf("合计", "实付", "应付", "总计")
    /** 同屏有金额时,这些字说明是付款面板(付款方式、收银台、验密)。 */
    val PAY_SHEET_WORDS = listOf("支付方式", "付款方式", "支付宝", "银行卡", "收银台", "免密")
    /** 这几个字在普通页面上也常见(账户余额、登录密码):只在下半屏、同一区域有确认/支付一类按钮时才算付款面板。 */
    val WEAK_PAY_SHEET_WORDS = listOf("余额", "密码", "指纹", "零钱")
    /** 介绍付款方式的字样(商品页、规格面板上常见),不是付款动作,先去掉再判。 */
    private val PAYMENT_ADS = Regex("支持[^\\s，,。]{0,8}?(?:付款|支付)|货到付款|先用后付|花呗|分期|付款方式说明")
    /** 页面上有这些下单按钮时,是商品页或规格面板:不按付款面板字样判。 */
    private val ORDER_CONTEXT = listOf("立即购买", "加入购物车", "立即下单")
    /** 0–999 里这条线以下算屏幕底部区域(付款面板、底部弹层)。 */
    private const val BOTTOM_REGION = 500
    private val PAY_SHEET_ENGLISH = Regex("(?<![A-Za-z0-9_])(pay|payment|checkout total)(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)

    /** 先从名字里去掉,再判强词:付款方式的选项不是付款动作。 */
    val EXCLUDED = listOf("支付宝", "支付方式", "支付设置")

    /** 资源 ID 按 _ - . 和驼峰拆开后的单词;submit、post 不在这里(资源 ID 里太常见)。 */
    private val RESOURCE_WORDS: Map<String, IrrevAction> = mapOf(
        "send" to IrrevAction.send, "reply" to IrrevAction.send,
        "pay" to IrrevAction.pay,
        "delete" to IrrevAction.delete, "remove" to IrrevAction.delete,
        "checkout" to IrrevAction.order,
    )

    /** 输入框明确是搜索框才不算发送:其余输入框里回车一律按发送拦。提示语里找这些字,资源 ID 里按单词找 search。 */
    private val SEARCH_FIELD = listOf("search", "搜索", "搜寻")
    private val SEARCH_BUTTONS = setOf("搜索", "搜寻", "search")

    private const val KEY_ENTER = 66
    private const val KEY_NUMPAD_ENTER = 160
    private const val KEY_DPAD_CENTER = 23
    private const val KEY_SPACE = 62
    private const val KEY_DEL = 67
    private const val KEY_FORWARD_DEL = 112
    private val ACTIVATING_KEYS = setOf(KEY_ENTER, KEY_NUMPAD_ENTER, KEY_DPAD_CENTER, KEY_SPACE)

    /** 横滑超过屏宽这个比例(0–999)、从列表行上起手,按「滑动删除」算。 */
    private const val SWIPE_DELETE_MIN = 400
    /** 宽度超过这个(0–999)的可点元素算列表行。 */
    private const val ROW_MIN_WIDTH = 600
    /** 比这高(0–999)的元素不算「同一行」(容器、大段文字)。 */
    private const val ROW_MAX_HEIGHT = 150
    /** 比这矮(0–999)的可滚动区按轮播算。 */
    private const val CAROUSEL_MAX_HEIGHT = 400

    /** 输入框旁「清空」一类按钮的名字(去空白、小写)。 */
    private val FIELD_CLEAR_WORDS = setOf("清空", "清除", "清空输入", "清除输入", "清空搜索", "清除搜索", "清空内容", "清除内容",
        "clear", "cleartext", "clearsearch", "clearquery", "×", "✕", "x")
    /** 清空键离输入框左右边至多这么远(0–999)。 */
    private const val FIELD_NEAR = 80

    // 判哪些、按什么判,由工具声明([GateTarget],只有 External 的工具有);返回、清空输入框、打开 App 不会提交任何东西,不判
    /** 改选中项的键:方向键、Home/End、翻页。 */
    private val SELECTION_KEYS = setOf(19, 20, 21, 22, 122, 123, 92, 93)
    /** 改了选中项就可能触发页面动作(onchange 提交)的控件。 */
    private val SELECTION_ROLES = setOf("combobox", "listbox", "option", "radio", "radiogroup", "menu", "menubar", "menuitem",
        "menuitemradio", "menuitemcheckbox", "tree", "treeitem")
    private val OPTION_ROLES = setOf("option", "radio", "menuitem", "menuitemradio", "menuitemcheckbox", "treeitem")

    /** 这次调用要不要判(要判才去读屏)。 */
    fun needsCheck(target: GateTarget?, args: JsonObject): Boolean = when (target) {
        // 上传要点文件控件,和点击一样判
        GateTarget.Tap, GateTarget.FileControl -> true
        // 打字带回车时判;往一点上打字时那一下点击也判(点到的可能是发送一类的按钮)
        GateTarget.Enter -> bool(args, "pressEnter") || atPoint(args)
        GateTarget.Key -> key(args) in ACTIVATING_KEYS
        GateTarget.Swipe -> horizontal(args) != null
        // 网页上能触发提交的交互都判:每个组合键按焦点判,拖动按起点和终点(滑动支付、拖进删除区),
        // 下拉选择按下拉框和选中的那一项(页面可能一改就提交);悬停不会提交
        GateTarget.Interact -> when (str(args, "action")) {
            "key" -> combo(args) != null
            "drag", "select" -> true
            // 点页面弹框的「确定」可能就是提交(确认删除、确认付款):按弹框的字判;取消不判
            "dialog" -> str(args, "answer") == "accept"
            else -> false
        }
        null -> false
    }

    /** null = 不是不可逆操作。 */
    fun classify(target: GateTarget?, args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot? = null): Irreversible? {
        if (!needsCheck(target, args)) return null
        return when (target) {
            GateTarget.Tap, GateTarget.FileControl -> tapHit(args, snapshot, fresh)
            GateTarget.Enter -> (if (atPoint(args)) tapHit(args, snapshot, fresh) else null)
                ?: if (bool(args, "pressEnter")) enterHit(args, snapshot, fresh ?: snapshot, KEY_ENTER) else null
            GateTarget.Key -> enterHit(args, snapshot, fresh ?: snapshot, key(args)!!)
            GateTarget.Swipe -> swipeHit(args, fresh ?: snapshot)
            GateTarget.Interact -> when (str(args, "action")) {
                "key" -> comboHit(args, snapshot, fresh ?: snapshot)
                "select" -> selectHit(args, snapshot, fresh)
                "dialog" -> dialogHit(fresh ?: snapshot)
                else -> dragHit(args, snapshot, fresh)
            }
            null -> null
        }
    }

    private fun combo(args: JsonObject) = str(args, "key")?.let(CdpKeys::parseCombo)

    /** 打字给的是一个点(坐标,或换成坐标之前的 @vN),不是树里的元素。 */
    private fun atPoint(args: JsonObject) = args["x"] != null || str(args, "ref")?.let(LOCATED_REF::matches) == true

    /**
     * 组合键按它能对焦点做什么来判:
     * 回车、空格(带不带修饰键)会激活焦点,按 press_key 的回车/空格规则判(和点它一样,输入框里的空格是打字);
     * 方向键、Home/End、翻页会改选中项,只在焦点是选择类控件时判,按控件和它能选到的项判(同 select);
     * 焦点不在输入框时,页面可能把删除、退格、单个字母数字、Ctrl/Alt/Meta+字母数字当命令:焦点在元素上按点它判
     * (选择类控件上的字母是按首字跳选,按控件和可选项判);焦点在页面本身(没有焦点元素)时删除、退格一律拦,
     * 其余命令键只在页面声明了它是哪个控件的快捷键(aria-keyshortcuts、accesskey)时按那个控件判;
     * Tab、Escape 等只挪焦点或关闭,输入框里的键是编辑,都不判。
     */
    private fun comboHit(args: JsonObject, snapshot: ScreenSnapshot?, s: ScreenSnapshot?): Irreversible? {
        val combo = combo(args)!!
        val code = combo.key.androidKeyCode
        if (code == KEY_ENTER || code == KEY_SPACE) return enterHit(args, snapshot, s, code)
        val screen = s ?: snapshot ?: return null
        val focused = screen.elements.firstOrNull { it.focused }
        if (focused?.editable == true) return null
        // 选择控件上改选中项的键(方向键、按首字跳选)按控件和它能选到的项判
        fun selection(e: UiElement) = selectionTargets(e, screen).firstNotNullOfOrNull { elementHit(it, screen) }
        if (code in SELECTION_KEYS) return focused?.takeIf { it.role in SELECTION_ROLES }?.let(::selection)
        val deleting = code == KEY_DEL || code == KEY_FORWARD_DEL
        val printable = combo.key.text != null && combo.key.text != " "
        if (!deleting && !printable) return null
        val typeAhead = printable && combo.modifiers.all { it == KeyModifier.Shift }
        if (focused != null) {
            val own = if (typeAhead && focused.role in SELECTION_ROLES) selection(focused) else elementHit(focused, screen)
            return own ?: declaredHit(combo, screen)
        }
        if (deleting) return Irreversible(IrrevAction.delete, "${combo.key.key}:焦点不明")
        return declaredHit(combo, screen)
    }

    /** 页面声明了这个组合键的控件(aria-keyshortcuts、accesskey),按点它判。 */
    private fun declaredHit(combo: KeyCombo, s: ScreenSnapshot): Irreversible? = s.elements
        .filter { e -> e.keyShortcuts.any { sameCombo(CdpKeys.parseCombo(it), combo) } }
        .firstNotNullOfOrNull { elementHit(it, s) }

    private fun sameCombo(a: KeyCombo?, b: KeyCombo) = a != null && a.key.code == b.key.code && a.modifiers.toSet() == b.modifiers.toSet()

    /** 选择类控件和它能选到的项:控件里面的选项,焦点本身是选项时还有同类的其它选项(单选组、菜单)。 */
    private fun selectionTargets(focused: UiElement, s: ScreenSnapshot): List<UiElement> {
        val inside = s.elements.filter { it !== focused && it.role in OPTION_ROLES && inside(it, focused) }
        val peers = if (focused.role in OPTION_ROLES) s.elements.filter { it !== focused && it.role == focused.role } else emptyList()
        return listOf(focused) + inside + peers
    }

    /** 确定页面弹框:弹框的字按按钮名判(通用的「确定」看同屏上下文),再按整句里的强词判。 */
    private fun dialogHit(s: ScreenSnapshot?): Irreversible? {
        val message = s?.web?.dialog?.message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val action = matchName(message, s) ?: message.split(Regex("[\\s，,。.！!？?：:；;]+")).firstNotNullOfOrNull { strong(it) } ?: return null
        return Irreversible(action, "dialog:${message.take(40)}")
    }

    /** 下拉选择:下拉框按点击判,选中的那一项的文字按按钮名判。 */
    private fun selectHit(args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot?): Irreversible? {
        tapHit(args, snapshot, fresh)?.let { return it }
        val option = str(args, "option") ?: return null
        return matchName(option, fresh ?: snapshot)?.let { Irreversible(it, "option:${option.trim()}") }
    }

    /** 拖动的起点、终点各按一次点击来判(编号共用 snapshotId)。 */
    private fun dragHit(args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot?): Irreversible? {
        fun end(ref: String, x: String, y: String) = buildJsonObject {
            val r = str(args, ref)
            if (r != null) {
                put("ref", r)
                str(args, "snapshotId")?.let { put("snapshotId", it) }
            } else {
                args[x]?.let { put("x", it) }
                args[y]?.let { put("y", it) }
            }
        }
        return listOf(end("ref", "x", "y"), end("toRef", "toX", "toY")).firstNotNullOfOrNull { tapHit(it, snapshot, fresh) }
            ?.let { it.copy(matched = "drag:${it.matched}") }
    }

    /**
     * 这一屏是付款页或付款面板:有确认支付、支付密码一类字样,或有金额且像付款面板
     * (商品页、规格面板不算)。返回命中的依据;不是为 null。
     */
    fun paymentPage(s: ScreenSnapshot): String? {
        ScreenDiagnosis.paymentText(s.elements.map { it.name })?.let { return it }
        val shown = s.elements.filter { !it.editable && !it.hint }
        val amount = shown.any { e -> TOTAL_LABELS.any { it in Hant.fold(e.name) } || AMOUNT.containsMatchIn(rowText(e, shown)) }
        return if (amount && paySheet(shown)) "payment sheet with an amount" else null
    }

    /** 点击找不到作用在哪个元素上(读屏缺失、编号不属于这次读屏、坐标处没有元素);供运行记录留痕。 */
    fun unresolved(target: GateTarget?, args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot? = null): Boolean =
        target == GateTarget.Tap && tapTargets(args, snapshot, fresh).isEmpty()

    /**
     * 按钮名匹配词表;[screen] 给了时,通用的「确认/确定」看同屏有没有强词或金额。
     * [hit] 是被点的元素(知道时):可滚动区里的金额只在被点的元素不在那个区里时才不算。
     */
    fun matchName(name: String, screen: ScreenSnapshot? = null, hit: UiElement? = null): IrrevAction? {
        val raw = Hant.fold(name).trim()
        if (raw.isEmpty()) return null
        val n = raw.filterNot(Char::isWhitespace).lowercase()
        if (n in SUBMIT_WORDS) return IrrevAction.submit
        if (contacts(raw)) return IrrevAction.submit
        if (n in RED_PACKET) return IrrevAction.pay
        if (EXACT_PAY.any { w -> EXACT_PAY_PREFIXES.any { n == it + w } }) return IrrevAction.pay
        strong(raw)?.let { return it }
        for ((action, words) in PREFIX_WORDS) for (w in words) {
            if (n.startsWith(w) && n.length <= w.length + PREFIX_SLACK) return action
        }
        for ((action, words) in PUNCT_WORDS) for (w in words) {
            if (n.startsWith(w) && n.drop(w.length).none { it.isLetterOrDigit() }) return action
        }
        if (BOOKING_WORDS.any { w -> BOOKING_PREFIXES.any { n == it + w } }) return IrrevAction.order
        val lower = raw.lowercase()
        for ((w, action) in ENGLISH) {
            if (lower.startsWith(w) && lower.getOrNull(w.length)?.isLetter() != true) return action
        }
        if (screen != null && n.trimEnd { !it.isLetterOrDigit() } in GENERIC) return contextAction(screen, hit)
        return null
    }

    /** 名字是联系别人、投申请的按钮(见 [CONTACT_WORDS])。 */
    private fun contacts(raw: String): Boolean {
        val n = raw.filterNot(Char::isWhitespace).lowercase().trimEnd { !it.isLetterOrDigit() }
        if (n in APPLY_WORDS || CONTACT_WORDS.any { w -> CONTACT_PREFIXES.any { n == it + w } }) return true
        val e = raw.lowercase().trim().trimEnd { !it.isLetterOrDigit() }.replace(Regex("\\s+"), " ")
        return e in CONTACT_ENGLISH
    }

    /** 短名字整体、或拼起来的名字的每一段里出现强词;先去掉付款方式一类的词。 */
    private fun strong(label: String): IrrevAction? {
        val folded = Hant.fold(label)
        for (p in listOf(folded) + folded.split(Regex("\\s+"))) {
            var t = p.filterNot(Char::isWhitespace).replace(PAYMENT_ADS, "")
            EXCLUDED.forEach { t = t.replace(it, "") }
            if (t.isEmpty() || t.length > STRONG_MAX_LEN) continue
            STRONG.entries.firstOrNull { (w, _) -> occurrences(t, w).any { actionAt(t, w, it) } }?.let { return it.value }
        }
        return null
    }

    private fun occurrences(t: String, w: String) = generateSequence(t.indexOf(w).takeIf { it >= 0 }) { i -> t.indexOf(w, i + 1).takeIf { it >= 0 } }

    /** 这一处是动作,不是状态:前面不是「待」「已」,后面(跳过标点)不是状态词。 */
    private fun actionAt(t: String, w: String, i: Int): Boolean {
        if (i > 0 && t[i - 1] in STATUS_PREFIXES) return false
        val rest = t.substring(i + w.length).dropWhile { !it.isLetterOrDigit() }
        return STATUS_SUFFIXES.none { rest.startsWith(it) }
    }

    /**
     * 同屏(弹窗、底部面板)的上下文:有付款词就是付款;否则按第一个非下单类强词(如删除确认框);
     * 有金额且同屏有付款方式、收银台、验密一类字样(或任意长度的一段里有支付/付款)也是付款;
     * 再否则有下单词或金额,按下单(下单的同意要和屏上金额对得上,见 [ConfirmationGate]);都没有就不是。
     * 金额:合计一类总算;别的金额在可滚动区里、而被点的元素不在那个区里时不算(筛选面板的价格档、商品列表)。
     */
    private fun contextAction(screen: ScreenSnapshot, hit: UiElement?): IrrevAction? {
        val found = screen.elements.mapNotNull { strong(it.name) }
        if (IrrevAction.pay in found) return IrrevAction.pay
        found.firstOrNull { it != IrrevAction.order }?.let { return it }
        val shown = screen.elements.filter { !it.editable && !it.hint }
        val scrollables = screen.elements.filter { it.scrollable }
        val amount = shown.any { e ->
            // 合计一类字样总算(金额可能拆在后面几段里,也可能还没加载出数);别的按整行拼起来认金额
            TOTAL_LABELS.any { it in Hant.fold(e.name) } ||
                (AMOUNT.containsMatchIn(rowText(e, shown)) && scrollables.none { s -> inside(e, s) && hit != null && !inside(hit, s) })
        }
        if (amount && paySheet(shown)) return IrrevAction.pay
        return if (IrrevAction.order in found || amount) IrrevAction.order else null
    }

    /**
     * 有金额的这一屏像不像付款面板:付款方式、收银台、验密一类字样,或任意长度的一段里有支付/付款
     * (先去掉「支持花呗付款」「货到付款」一类介绍);页面上有立即购买、加入购物车时不按这些判。
     * 余额、密码、指纹、零钱只在下半屏、且下半屏有确认/支付一类按钮时才算。
     */
    private fun paySheet(shown: List<UiElement>): Boolean {
        if (shown.any { e -> ORDER_CONTEXT.any { it in Hant.fold(e.name).filterNot(Char::isWhitespace) } }) return false
        val bottomButton = shown.any { b ->
            b.clickable && top(b) >= BOTTOM_REGION && Hant.fold(b.name).filterNot(Char::isWhitespace).lowercase().let { n ->
                n.trimEnd { !it.isLetterOrDigit() } in GENERIC || "支付" in n || "付款" in n
            }
        }
        return shown.any { e ->
            val t = Hant.fold(e.name).filterNot(Char::isWhitespace).replace(PAYMENT_ADS, "")
            PAY_SHEET_WORDS.any { it in t } || PAY_SHEET_ENGLISH.containsMatchIn(e.name) ||
                listOf("支付", "付款").any { w -> occurrences(t, w).any { actionAt(t, w, it) } } ||
                (bottomButton && top(e) >= BOTTOM_REGION && WEAK_PAY_SHEET_WORDS.any { it in t })
        }
    }

    /**
     * 从 [start] 起同一行、在它右边的各段按横向顺序拼起来(「实付 | ¥ | 39.90」)。
     * 同一行:两者的纵向中心都落在对方的上下边之间,整屏的大容器不会把别的行拼进来。
     */
    internal fun rowText(start: UiElement, elements: List<UiElement>): String {
        if (start.bounds.size != 4) return start.name
        fun cy(e: UiElement) = (e.bounds[1] + e.bounds[3]) / 2
        val row = elements.filter { e ->
            e !== start && e.bounds.size == 4 && e.bounds[0] >= start.bounds[0] &&
                cy(e) in start.bounds[1]..start.bounds[3] && cy(start) in e.bounds[1]..e.bounds[3]
        }.sortedBy { it.bounds[0] }
        return start.name + row.joinToString("") { it.name.filterNot(Char::isWhitespace) }
    }

    private fun top(e: UiElement) = if (e.bounds.size == 4) e.bounds[1] else 0

    private fun matchResource(id: String): IrrevAction? = resourceWords(id).firstNotNullOfOrNull { RESOURCE_WORDS[it] }

    /** 资源 ID 按 _ - . 和驼峰拆成的单词。 */
    private fun resourceWords(id: String): List<String> =
        id.substringAfterLast('/').replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase().split('_', '-', '.')

    /**
     * 改输入框里的字本身不会提交任何东西:点输入框(只是聚焦,框里的字是打进去的内容)、点框里没名字的小图标
     * (清空 ×、语音)或清空键,或紧挨着框、同一行、名字是清空一类的按钮,都是编辑。提交的是之后的回车、发送、搜索。
     */
    internal fun editsField(e: UiElement, s: ScreenSnapshot): Boolean {
        if (e.editable) return true
        if (e.bounds.size != 4) return false
        val fields = s.elements.filter { it.editable && it.bounds.size == 4 && it !== e }
        val n = Hant.fold(e.name).filterNot(Char::isWhitespace).lowercase()
        val clears = n in FIELD_CLEAR_WORDS || e.resourceId?.let { r -> "clear" in resourceWords(r) } == true
        // 框里没名字的小图标(清空 ×、语音):资源 ID 也说不出是发送一类时才算编辑;有名字的按钮(网页编辑区里的「发送」)照常判
        val bareIcon = n.isEmpty() && e.resourceId?.let(::matchResource) == null
        if ((clears || bareIcon) && fields.any { inside(e, it) }) return true
        if (!clears) return false
        val cy = (e.bounds[1] + e.bounds[3]) / 2
        return fields.any { f -> cy in f.bounds[1]..f.bounds[3] && e.bounds[0] >= f.bounds[0] - FIELD_NEAR && e.bounds[0] <= f.bounds[2] + FIELD_NEAR }
    }

    private fun elementHit(e: UiElement, s: ScreenSnapshot): Irreversible? {
        for (x in withChildren(e, s)) {
            if (editsField(x, s)) continue
            matchName(x.name, s, x)?.let { return Irreversible(it, x.name.trim()) }
            x.resourceId?.let { r -> matchResource(r)?.let { return Irreversible(it, r) } }
        }
        return null
    }

    private fun tapHit(args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot?): Irreversible? {
        val targets = tapTargets(args, snapshot, fresh)
        // 点的是输入框或它的清空键:编辑,不看模型写的 target(「清空搜索框」)
        if (targets.isNotEmpty() && targets.all { (e, s) -> editsField(e, s) }) return null
        for ((e, s) in targets) elementHit(e, s)?.let { return it }
        // 坐标点上没有带名字的元素(自绘界面、没名字的图标,看图找到的点常这样):按同一行的文字判
        if (str(args, "ref") == null && targets.all { (e, s) -> withChildren(e, s).all { it.name.isBlank() } }) {
            val s = fresh ?: snapshot
            val y = num(args, "y")
            if (s != null && y != null) rowHit(s, y)?.let { return it }
        }
        // 元素找不到、或是没有文字的图标时,模型自己写的 target 也算数;
        // 看图找到的点另带找的原文(text)和模型看到的字(seen),一样判
        return listOf("target", "text", "seen").firstNotNullOfOrNull { k ->
            str(args, k)?.let { t -> matchName(t, fresh ?: snapshot)?.let { Irreversible(it, t.trim()) } }
        }
    }

    /**
     * 点击作用的元素(连同它所在的那一屏):ref → 原读屏里的元素,加上此刻它中心位置上的元素;
     * 坐标 → 此刻那一点上最小的可点元素。
     */
    private fun tapTargets(args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot?): List<Pair<UiElement, ScreenSnapshot>> {
        val ref = str(args, "ref")
        if (ref != null) {
            val s = snapshot?.takeIf { it.snapshotId == str(args, "snapshotId") } ?: return emptyList()
            val e = s.elements.firstOrNull { it.ref == ref } ?: return emptyList()
            val now = fresh?.let { f -> center(e)?.let { (x, y) -> at(f, x, y) }?.let { it to f } }
            return listOfNotNull(e to s, now)
        }
        val s = fresh ?: snapshot ?: return emptyList()
        val x = num(args, "x") ?: return emptyList()
        val y = num(args, "y") ?: return emptyList()
        return listOfNotNull(at(s, x, y)?.let { it to s })
    }

    /** 纵向跨过 [y] 的矮元素(一行里的文字、按钮)的名字里有没有不可逆的词。 */
    private fun rowHit(s: ScreenSnapshot, y: Int): Irreversible? =
        rowElements(s, y).filterNot { editsField(it, s) }.firstNotNullOfOrNull { e -> matchName(e.name, s, e)?.let { Irreversible(it, e.name.trim()) } }

    private fun rowElements(s: ScreenSnapshot, y: Int) = s.elements
        .filter { it.name.isNotBlank() && !it.editable && it.bounds.size == 4 && y in it.bounds[1]..it.bounds[3] && height(it) <= ROW_MAX_HEIGHT }

    private fun at(s: ScreenSnapshot, x: Int, y: Int): UiElement? {
        val under = s.elements.filter { contains(it, x, y) }
        return under.filter { it.clickable }.minByOrNull(::area) ?: under.minByOrNull(::area)
    }

    /** 没有名字的可点容器连同落在它里面、不可点的文字子元素一起判;不可点的空白节点(如整屏的根)不展开。 */
    private fun withChildren(e: UiElement, s: ScreenSnapshot): List<UiElement> {
        if (e.name.isNotBlank() || !e.clickable) return listOf(e)
        return listOf(e) + s.elements.filter { it !== e && !it.clickable && it.name.isNotBlank() && inside(it, e) }
    }

    /**
     * 回车、确认键、空格作用在焦点上:焦点是输入框时,除非明确是搜索框,一律按发送算(空格只是打字,不算);
     * 焦点是按钮时按词表判;看不出焦点在哪时,屏上的输入框全是搜索框才放行,否则算命中。
     * type_text 给了 ref 时,作用的就是那个输入框。输入法的「搜索」回车键类型读不到,不作依据。
     */
    private fun enterHit(args: JsonObject, snapshot: ScreenSnapshot?, s: ScreenSnapshot?, key: Int): Irreversible? {
        val keyName = if (key == KEY_SPACE) "空格" else "回车"
        val ref = str(args, "ref")
        val field = if (ref == null) null
        else snapshot?.takeIf { it.snapshotId == str(args, "snapshotId") }?.elements?.firstOrNull { it.ref == ref }
        val focused = field ?: s?.elements?.firstOrNull { it.focused }
        if (focused != null) {
            if (focused.editable) {
                if (key == KEY_SPACE || isSearch(focused, s ?: snapshot)) return null
                return Irreversible(IrrevAction.send, "$keyName:${label(focused)}")
            }
            return (s ?: snapshot)?.let { elementHit(focused, it) }
        }
        val fields = s?.elements?.filter { it.editable }.orEmpty()
        if (fields.isNotEmpty() && fields.all { isSearch(it, s) }) return null
        return Irreversible(IrrevAction.send, "$keyName:焦点不明")
    }

    /**
     * 按元素自己的语义认搜索框:端口认出的搜索框控件、角色里有 search(网页 searchbox,安卓的搜索框控件类)、
     * 提示语(打了字也还在的那份,或此刻显示的)或资源 ID 里有搜索字样,或同一行有可点的「搜索」按钮。
     * 输入框里有字时名字就是打进去的字(「帮我搜索一下」是消息),只有提示语才看名字。在搜索框里回车是查找,可以撤回,不是发送。
     */
    private fun isSearch(e: UiElement, s: ScreenSnapshot?): Boolean {
        // 同一行有发送一类的按钮:是发消息的框(「搜索或提问」的聊天框),提示语说搜索也照样按发送判
        if (s != null && ElementContext.row(e, s).any { b -> b.clickable && !b.editable && sendLike(b.name) }) return false
        if (e.searchField || "search" in e.role.lowercase()) return true
        val hint = Hant.fold(listOfNotNull(e.name.takeIf { e.hint }, e.placeholder).joinToString(" ")).lowercase()
        if (SEARCH_FIELD.any { it in hint } || e.resourceId?.let { "search" in resourceWords(it) } == true) return true
        if (s == null || e.bounds.size != 4) return false
        return s.elements.any { b ->
            b !== e && b.clickable && !b.editable && b.bounds.size == 4 &&
                Hant.fold(b.name).filterNot(Char::isWhitespace).lowercase() in SEARCH_BUTTONS &&
                (b.bounds[1] + b.bounds[3]) / 2 in e.bounds[1]..e.bounds[3]
        }
    }

    /** 按钮上是发送一类的字(发送、发布、发表、send)。 */
    private fun sendLike(name: String): Boolean {
        val n = Hant.fold(name).filterNot(Char::isWhitespace).lowercase()
        if (n.isEmpty()) return false
        return PREFIX_WORDS.getValue(IrrevAction.send).any { n.startsWith(it) && n.length <= it.length + PREFIX_SLACK } || n == "send" || n == "post"
    }

    /**
     * 横滑够长、起手点在列表行(宽的可点元素,本身不可滚动)上:很多 App 的列表这样删除一行。
     * 读屏里没有容器方向信息:只认出矮的可滚动区(轮播);整屏高的横向容器上的长滑仍会被拦(提示词里让模型改用短滑)。
     */
    private fun swipeHit(args: JsonObject, s: ScreenSnapshot?): Irreversible? {
        val (sx, sy) = horizontal(args) ?: return null
        s ?: return null
        // 起手点在矮的可滚动区里(横向轮播一类),是翻页不是删除
        if (s.elements.any { it.scrollable && contains(it, sx, sy) && height(it) < CAROUSEL_MAX_HEIGHT }) return null
        val row = s.elements.filter { contains(it, sx, sy) && it.clickable && !it.scrollable && width(it) >= ROW_MIN_WIDTH }
            .minByOrNull(::area) ?: return null
        return Irreversible(IrrevAction.delete, "swipe on ${label(row)}")
    }

    /** 够长的横滑的起点(0–999);不是横滑或不够长为 null。scroll 按分发器的换算算出手指起点。 */
    private fun horizontal(args: JsonObject): Pair<Int, Int>? {
        if (args.containsKey("startX")) {
            val x1 = num(args, "startX") ?: return null; val y1 = num(args, "startY") ?: return null
            val x2 = num(args, "endX") ?: return null; val y2 = num(args, "endY") ?: return null
            val dx = abs(x2 - x1)
            return if (dx >= SWIPE_DELETE_MIN && dx > abs(y2 - y1)) x1 to y1 else null
        }
        val dir = str(args, "direction") ?: return null
        if (dir != "left" && dir != "right") return null
        val dist = min(650, (num(args, "amount") ?: 3) * 100)
        if (dist < SWIPE_DELETE_MIN) return null
        val cx = num(args, "x") ?: 500; val cy = num(args, "y") ?: 500
        val dx = if (dir == "left") dist else -dist
        return max(50, min(949, (cx - dx / 2.0).roundToInt())) to cy
    }

    private fun key(args: JsonObject): Int? = str(args, "key")?.let(PressKeys::resolve)

    private fun label(e: UiElement) = e.name.trim().ifEmpty { e.resourceId ?: e.ref }

    private fun center(e: UiElement): Pair<Int, Int>? =
        if (e.bounds.size != 4) null else (e.bounds[0] + e.bounds[2]) / 2 to (e.bounds[1] + e.bounds[3]) / 2

    private fun contains(e: UiElement, x: Int, y: Int) =
        e.bounds.size == 4 && x in e.bounds[0]..e.bounds[2] && y in e.bounds[1]..e.bounds[3]

    private fun inside(child: UiElement, parent: UiElement): Boolean {
        val c = child.bounds; val p = parent.bounds
        return c.size == 4 && p.size == 4 && c[0] >= p[0] && c[1] >= p[1] && c[2] <= p[2] && c[3] <= p[3]
    }

    private fun height(e: UiElement) = if (e.bounds.size != 4) 0 else e.bounds[3] - e.bounds[1]

    private fun width(e: UiElement) = if (e.bounds.size != 4) 0 else e.bounds[2] - e.bounds[0]

    private fun area(e: UiElement): Long =
        if (e.bounds.size != 4) Long.MAX_VALUE else (e.bounds[2] - e.bounds[0]).toLong() * (e.bounds[3] - e.bounds[1])

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** 和 tap 一样容忍 [a,b](取中点)。 */
    private fun num(o: JsonObject, k: String): Int? {
        fun d(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        val v = o[k]
        if (v is JsonArray && v.size == 2) {
            val a = d(v[0]); val b = d(v[1])
            if (a != null && b != null) return ((a + b) / 2).roundToInt()
        }
        return d(v)?.roundToInt()
    }

    private fun bool(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() == "true"
}
