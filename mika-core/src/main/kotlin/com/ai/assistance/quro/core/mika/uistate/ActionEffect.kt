package com.ai.assistance.quro.core.mika.uistate

import com.ai.assistance.quro.core.mika.visual.FrameChange
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.tool.Verb
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** 动作对 App 或网页界面状态的实际效果:按动作前后的界面状态(元素表、控件状态、输入焦点、在哪)判出,画面差异只作旁证;[wire] 是结果里 effect.kind 的写法。 */
enum class EffectKind(val wire: String) {
    /** 界面明显变了,和这个动作对得上。 */
    Done("done"),
    /** 只有小的局部变化(焦点、开关、一两个元素),或可能还在加载:要看一眼结果。 */
    SmallChange("small_change"),
    /** 什么都没变:换个做法,别原样再来。 */
    NoEffect("no_effect"),
    /** 换了地方:手机上前台换成了另一个 App,网页上换了一页(地址或标签变了);新地方见 [NewPlace]。 */
    PlaceChanged("place_changed");

    companion object {
        fun of(wire: String?): EffectKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 动作前后界面状态(元素表)的结构差异。元素按内容对齐(角色 + 名字 + 资源 ID + 是否提示语),不看编号(各次读屏的编号不通用):
 * 同内容同位置同状态算没变,只是控件状态([UiElement.states])变了算 [toggled],同内容换了位置算 [moved](滚动、重排),
 * 同一个输入框里的字变了算 [edited],其余是 [added]/[removed]。[focusMoved]:有输入焦点的元素换了(或焦点出现、消失)。
 * 只看有信息量的元素([UiElement.meaningful])。
 */
data class InterfaceDiff(
    val added: Int,
    val removed: Int,
    val moved: Int,
    val edited: Int,
    val toggled: Int,
    val focusMoved: Boolean,
    val total: Int,
) {
    val changes: Int get() = added + removed + moved + edited + toggled
    val none: Boolean get() = changes == 0 && !focusMoved

    /** 变化够多(门槛见 [EffectTuning])。 */
    val major: Boolean get() = changes >= max(EffectTuning.MAJOR_MIN, ceil(total * EffectTuning.MAJOR_SHARE).toInt())

    companion object {
        fun between(before: ScreenSnapshot, after: ScreenSnapshot): InterfaceDiff {
            val a = before.elements.filter { it.meaningful }
            val b = after.elements.filter { it.meaningful }
            // 先配全同的,再配只差状态的(拨了开关),再配挪了位置的,再配同一个输入框里换了字的
            val leftA = a.toMutableList()
            val leftB = b.toMutableList()
            pairOff(leftA, leftB) { x, y -> key(x) == key(y) && x.bounds == y.bounds && x.states == y.states }
            val toggled = pairOff(leftA, leftB) { x, y -> key(x) == key(y) && x.bounds == y.bounds }
            val moved = pairOff(leftA, leftB) { x, y -> key(x) == key(y) }
            val edited = pairOff(leftA, leftB) { x, y -> x.editable && y.editable && x.role == y.role && x.resourceId == y.resourceId && near(x, y) }
            val focusA = a.firstOrNull { it.focused }?.let(::place)
            val focusB = b.firstOrNull { it.focused }?.let(::place)
            return InterfaceDiff(leftB.size, leftA.size, moved, edited, toggled, focusA != focusB, max(a.size, b.size))
        }

        /** 按 [match] 两两配对并从两边移走,返回配上的对数。 */
        private fun pairOff(a: MutableList<UiElement>, b: MutableList<UiElement>, match: (UiElement, UiElement) -> Boolean): Int {
            var n = 0
            val it = a.iterator()
            while (it.hasNext()) {
                val x = it.next()
                val j = b.indexOfFirst { y -> match(x, y) }
                if (j >= 0) { b.removeAt(j); it.remove(); n++ }
            }
            return n
        }

        private fun key(e: UiElement) = listOf(e.role, e.name, e.resourceId.orEmpty(), e.hint.toString())
        private fun place(e: UiElement) = listOf(e.role, e.resourceId.orEmpty(), e.bounds.toString())

        private fun near(x: UiElement, y: UiElement): Boolean {
            if (x.bounds.size != 4 || y.bounds.size != 4) return x.resourceId != null
            return distance(x, y) <= EffectTuning.NEAR
        }
    }
}

/**
 * 动作之后到的新地方:[name] 是通用的说法(App 包名、网站主机名,和读界面时的「在哪」一致),
 * [kind] 与 [detail] 是这一种界面自己的细节(手机 "app":{package};网页 "web":{url,title,tabId})。
 */
data class NewPlace(val name: String, val kind: String, val detail: JsonObject)

/**
 * 输入后读回的输入框:[text] 是此刻框里的字(显示提示语时为空串;密码框为 null,不读出),[matches] 是否和要输的一致
 * (比较时去掉空白:有的框会给号码分组加空格)。
 */
data class Readback(val text: String?, val secret: Boolean, val matches: Boolean)

/**
 * 判效果用的、来自端口的事实:[changed] 端口在动作里核实的变没变;[switchedTo] 端口看到前台换成的 App;
 * [verified] 输入写完当场读回一致;[loading] 端口说页面还没静下来(网络、导航还在途);
 * [onUserScreen] 动作让这个 App 开到了用户的主屏上、留在那里(用户正用着它),ZorvAI 的屏上看不出变化也不是没点上。
 */
data class PortFacts(
    val changed: Boolean? = null,
    val switchedTo: String? = null,
    val verified: Boolean = false,
    val loading: Boolean = false,
    val onUserScreen: String? = null,
)

/**
 * 一个动作的实际效果:种类、去了哪(换地方时)、界面结构与画面的差异、输入的读回、可能还在加载。
 * [treeBlind]:没看到变化(种类仍是 [EffectKind.NoEffect]),但这一屏的树读不出点的那一处(树不能用,或缺了一块而点不在任何元素上),
 * 只改了焦点、隐藏状态的点看起来也一样,不能说没点上。
 */
data class ActionEffect(
    val kind: EffectKind,
    val place: NewPlace? = null,
    val diff: InterfaceDiff? = null,
    val frame: FrameChange? = null,
    val readback: Readback? = null,
    val loading: Boolean = false,
    val treeBlind: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("kind", kind.wire)
        place?.let { put("to", it.name); put(it.kind, it.detail) }
        diff?.let { t ->
            if (t.added > 0) put("added", t.added)
            if (t.removed > 0) put("removed", t.removed)
            if (t.moved > 0) put("moved", t.moved)
            if (t.edited > 0) put("edited", t.edited)
            if (t.toggled > 0) put("toggled", t.toggled)
            if (t.focusMoved) put("focusMoved", true)
        }
        frame?.let { put("frameChanged", (it.whole * 100).roundToInt() / 100.0) }
        readback?.let { r ->
            put("readback", if (r.secret) HIDDEN else r.text?.take(READBACK_MAX))
            put("readbackMatches", r.matches)
        }
        if (loading) put("mayStillBeLoading", true)
        if (treeBlind) put("treeBlind", true)
    }

    companion object {
        const val HIDDEN = "(hidden)"
        private const val READBACK_MAX = 200

        /** 看不出变化也不能说没点上的动作:点和手势(只改焦点、隐藏状态时树和画面都可能不动)。 */
        private val BLIND_VERBS = setOf(Verb.Tap, Verb.PhoneGesture)

        /**
         * 这一屏的树读不出这个动作作用的那一处:树不能用,或缺了一块而点(画面区域 [region] 的中心)不在任何有信息量的元素上。
         * 按树里的元素点的([target] 不为 null)只在树不能用时算。[quality] 是这一屏最近一次判的树够不够用,不知道为 null。
         */
        internal fun treeBlind(verb: Verb, quality: TreeQuality?, before: ScreenSnapshot?, target: UiElement?, region: List<Int>?): Boolean {
            if (verb !in BLIND_VERBS) return false
            return when (quality) {
                TreeQuality.Unusable -> true
                TreeQuality.Partial -> {
                    if (target != null || region == null || region.size != 4) return false
                    val x = (region[0] + region[2]) / 2
                    val y = (region[1] + region[3]) / 2
                    before?.elements.orEmpty().none { e -> e.meaningful && e.bounds.size == 4 && x in e.bounds[0]..e.bounds[2] && y in e.bounds[1]..e.bounds[3] }
                }
                else -> false
            }
        }

        /**
         * 按动作前后的界面状态判效果。[before]/[after] 读不到时只按端口的事实和画面判;都没有为 null(不报效果)。
         * [target] 是动作的目标元素(模型读到的那份里的,用来找输入框)。
         * 输入([Verb.TypeText])带 [typed](要输的字),[pressedEnter] 时不读回(回车之后框可能清空或已换页)。
         *
         * 拿不准时不说没效果:端口说变了就至少是小变化;界面在动或端口说还在加载时也只算小变化(可能还在加载)。
         */
        fun classify(
            verb: Verb,
            before: ScreenSnapshot?,
            after: ScreenSnapshot?,
            port: PortFacts,
            frame: FrameChange?,
            target: UiElement? = null,
            typed: String? = null,
            pressedEnter: Boolean = false,
            secret: Boolean = false,
        ): ActionEffect? {
            // 端口自己看到前台换了 App:以它为准(两次读可能都在切换之前或之后)
            port.switchedTo?.let { return ActionEffect(EffectKind.PlaceChanged, NewPlace(it, "app", buildJsonObject { put("package", it) }), frame = frame) }
            // 开到了用户的主屏上:换了地方,只是不在 ZorvAI 的屏上
            port.onUserScreen?.let {
                return ActionEffect(EffectKind.PlaceChanged, NewPlace(it, "app", buildJsonObject { put("package", it); put("onUserScreen", true) }), frame = frame)
            }
            val raw = judge(verb, before, after, port, frame, target, typed, pressedEnter, secret) ?: return null
            val loading = port.loading || after?.changedDuringRead == true || after?.let(::showsProgress) == true
            return when {
                raw.kind != EffectKind.NoEffect -> raw.copy(loading = loading && raw.kind == EffectKind.SmallChange)
                loading -> raw.copy(kind = EffectKind.SmallChange, loading = true)
                port.changed == true -> raw.copy(kind = EffectKind.SmallChange)
                else -> raw
            }
        }

        private fun judge(
            verb: Verb, before: ScreenSnapshot?, after: ScreenSnapshot?, port: PortFacts, frame: FrameChange?,
            target: UiElement?, typed: String?, pressedEnter: Boolean, secret: Boolean,
        ): ActionEffect? {
            if (before == null || after == null) {
                return when {
                    port.changed == true || frame?.visible == true -> ActionEffect(EffectKind.SmallChange, frame = frame)
                    port.changed == false -> ActionEffect(EffectKind.NoEffect, frame = frame)
                    else -> null
                }
            }
            moved(before, after)?.let { return ActionEffect(EffectKind.PlaceChanged, it, frame = frame) }
            val diff = InterfaceDiff.between(before, after)
            val readback = if ((verb == Verb.TypeText || verb == Verb.ClearText) && !pressedEnter) {
                readback(after, target, if (verb == Verb.ClearText) "" else typed, secret)
            } else null
            val kind = when {
                readback != null && !readback.secret && readback.matches -> EffectKind.Done
                // 端口写完当场读回过、一致,而这份界面里找不到那个框(或是密码框):按端口的核对算
                port.verified && (readback == null || readback.secret) && !pressedEnter -> EffectKind.Done
                readback != null && !readback.secret -> if (diff.none && frame?.visible != true) EffectKind.NoEffect else EffectKind.SmallChange
                diff.major -> EffectKind.Done
                diff.none -> when {
                    frame?.large == true && max(before.elements.count { it.meaningful }, after.elements.count { it.meaningful }) < EffectTuning.FEW_ELEMENTS -> EffectKind.Done
                    frame?.visible == true -> EffectKind.SmallChange
                    else -> EffectKind.NoEffect
                }
                else -> EffectKind.SmallChange
            }
            return ActionEffect(kind, diff = diff, frame = frame, readback = readback)
        }

        /** 界面上有进度条一类的控件(在加载)。 */
        private fun showsProgress(s: ScreenSnapshot) = s.elements.any { it.role.contains("progress", ignoreCase = true) && UiStates.DISABLED !in it.states }

        /** 换了 App 或换了一页:网页看地址(不算 # 之后)和标签,手机看前台包名。都没换为 null。 */
        private fun moved(before: ScreenSnapshot, after: ScreenSnapshot): NewPlace? {
            val wb = before.web; val wa = after.web
            if (wb != null || wa != null) {
                if (wb == null || wa == null) return null
                if (wb.tabId != wa.tabId || wb.url.substringBefore('#') != wa.url.substringBefore('#')) {
                    return NewPlace(after.packageName ?: wa.url, "web", buildJsonObject { put("url", wa.url); put("title", wa.title); put("tabId", wa.tabId) })
                }
                return null
            }
            val pa = after.packageName ?: return null
            return if (before.packageName != null && before.packageName != pa) NewPlace(pa, "app", buildJsonObject { put("package", pa) }) else null
        }

        /**
         * 此刻输入框里的字:同一个框(同角色同资源 ID、位置相近)优先,其次有焦点的输入框;找不到为 null(不报读回)。
         * [want] 是应有的字(清空时为空串);没给为 null 时不读回。
         */
        private fun readback(after: ScreenSnapshot, target: UiElement?, want: String?, secret: Boolean): Readback? {
            want ?: return null
            val fields = after.elements.filter { it.editable }
            val field = target?.let { t ->
                fields.filter { it.role == t.role && it.resourceId == t.resourceId }.minByOrNull { distance(it, t) }
                    ?.takeIf { distance(it, t) <= EffectTuning.SAME_FIELD }
            } ?: fields.firstOrNull { it.focused } ?: return null
            if (secret || field.secret) return Readback(null, secret = true, matches = false)
            val text = if (field.hint) "" else field.name
            return Readback(text, secret = false, matches = norm(text) == norm(want))
        }

        private fun norm(s: String) = s.filterNot { it.isWhitespace() }
    }
}

/** 两个元素中心的距离(0–999 里);缺范围为 0。 */
private fun distance(a: UiElement, b: UiElement): Int {
    if (a.bounds.size != 4 || b.bounds.size != 4) return 0
    val dx = (a.bounds[0] + a.bounds[2]) / 2 - (b.bounds[0] + b.bounds[2]) / 2
    val dy = (a.bounds[1] + a.bounds[3]) / 2 - (b.bounds[1] + b.bounds[3]) / 2
    return kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toInt()
}

/** 按效果给模型的一句话(prompts/feedback/tools.md 里 effect.*);明显变了不另说。 */
internal object EffectFeedback {
    /** [portSwitched]:端口已报过前台被切走(结果里已有那条说明),不再重复。 */
    fun hint(e: ActionEffect, verb: Verb, portSwitched: Boolean): String? {
        val r = e.readback?.takeIf { !it.secret && !it.matches }
        val differs = r?.let { Feedback.format("effect.readback_differs", "readback" to JsonPrimitive(it.text.orEmpty()).toString()) }
        return when (e.kind) {
            EffectKind.Done -> null
            EffectKind.PlaceChanged -> if (portSwitched) null else Feedback.format("effect.place_changed", "to" to e.place?.name)
            EffectKind.SmallChange -> differs ?: Feedback[if (e.loading) "effect.loading" else "effect.small_change"]
            EffectKind.NoEffect -> listOfNotNull(
                differs,
                Feedback[when {
                    e.treeBlind -> "effect.no_visible_change"
                    verb == Verb.Back -> "effect.no_effect.back"
                    verb == Verb.Scroll || verb == Verb.Swipe -> "effect.no_effect.scroll"
                    else -> "effect.no_effect"
                }],
            ).joinToString(" ")
        }
    }
}
