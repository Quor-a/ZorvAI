package com.ai.assistance.quro.core.mika.cap.web.cdp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.ai.assistance.quro.core.mika.uistate.CdpKey
import com.ai.assistance.quro.core.mika.uistate.CdpKeys
import com.ai.assistance.quro.core.mika.uistate.KeyCombo
import com.ai.assistance.quro.core.mika.uistate.KeyModifier

// 网页上的真实鼠标、键盘(Input 域)。坐标一律是主框架视口的 CSS 像素,和页面脚本给的一致。
// 节奏(轨迹、按住时长、键间隔、滚轮分格)由 [InputPacing] 给,默认 [InputPacing.Instant] 不加停顿。
// 滚轮只能用在正在显示的标签上:后台标签上 mouseWheel 会挂住,并卡死这个页面之后的所有 Input 命令(调用方负责判断)。

/** view 像素与 CSS 像素的换算;[scale] 是每个 CSS 像素占多少 view 像素(view 宽 ÷ 页面视口宽)。 */
object CdpGeometry {
    fun scale(viewWidth: Int, cssViewportWidth: Double): Double =
        if (viewWidth > 0 && cssViewportWidth > 0) viewWidth / cssViewportWidth else 1.0

    fun toCss(viewPx: Int, scale: Double): Double = if (scale > 0) viewPx / scale else viewPx.toDouble()
}

// ---------- 一次输入动作 ----------

/**
 * 一次输入动作的结果。[NotSent]:一个会改动页面的事件都没发出去(会话已断、第一发就被协议拒绝),
 * 换原生输入重做是安全的。[Uncertain]:发出过事件后失败(包括超时),页面可能已经收到,不能再做一遍;
 * 此时按着的鼠标键、键盘键已尽力抬起。
 */
sealed interface InputOutcome<out T> {
    data class Done<T>(val value: T) : InputOutcome<T>
    data class NotSent(val error: Throwable) : InputOutcome<Nothing>
    data class Uncertain(val error: Throwable) : InputOutcome<Nothing>
}

/**
 * 一次输入动作里发出的命令都经它:记下是否可能已有事件到达页面,以及此刻按着的鼠标键和键盘键,
 * 失败时据此抬起。只读的查询(取视口、读选项)不算事件。
 */
class InputTrack internal constructor(
    val session: CdpSession,
    private val timeoutMs: Long,
    val pacing: InputPacing = InputPacing.Instant,
) {
    var mayHaveDelivered = false
        private set
    private var pressedAt: Pair<Double, Double>? = null
    private var dragging = false
    private val heldKeys = ArrayList<CdpKey>()

    /** 会改动页面的命令。发出前会话已断:没送达。协议回了错误:这一发没生效,之前没发过就仍算没送达。 */
    internal suspend fun effect(method: String, params: JsonObject): JsonObject {
        if (session.closed.value) throw CdpConnectionClosedException("CDP session closed before $method")
        val before = mayHaveDelivered
        mayHaveDelivered = true
        try {
            return session.send(method, params, timeoutMs = timeoutMs)
        } catch (e: CdpException) {
            mayHaveDelivered = before
            throw e
        }
    }

    internal suspend fun query(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject =
        session.send(method, params, timeoutMs = timeoutMs)

    internal suspend fun mouse(type: String, x: Double, y: Double, button: String, buttons: Int, clickCount: Int) {
        val s = session.inputScale
        // 先记下再发:按下这一发超时也可能已按下
        if (type == "mousePressed") pressedAt = x to y
        effect(
            "Input.dispatchMouseEvent",
            buildJsonObject {
                put("type", type); put("x", x * s); put("y", y * s)
                put("button", button); put("buttons", buttons); put("clickCount", clickCount)
                put("pointerType", "mouse")
            },
        )
        session.pointer = x to y
        if (type == "mouseReleased") pressedAt = null
    }

    internal suspend fun wheel(x: Double, y: Double, deltaY: Double) {
        val s = session.inputScale
        effect(
            "Input.dispatchMouseEvent",
            buildJsonObject {
                put("type", "mouseWheel"); put("x", x * s); put("y", y * s)
                put("button", "none"); put("buttons", 0); put("deltaX", 0); put("deltaY", deltaY)
                put("pointerType", "mouse")
            },
        )
        session.pointer = x to y
    }

    /** 按节奏停一会儿;[Instant][InputPacing.Instant] 时不停。 */
    internal suspend fun pause(ms: Long) {
        if (ms > 0) delay(ms)
    }

    internal suspend fun drag(type: String, x: Double, y: Double, data: JsonObject) {
        dragging = true
        val s = session.inputScale
        effect("Input.dispatchDragEvent", buildJsonObject { put("type", type); put("x", x * s); put("y", y * s); put("data", data) })
        if (type == "drop") { dragging = false; pressedAt = null }
    }

    internal suspend fun key(type: String, k: CdpKey, modifiers: Int, text: String?) {
        if (type != "keyUp") heldKeys += k
        effect(
            "Input.dispatchKeyEvent",
            buildJsonObject {
                put("type", type)
                if (modifiers != 0) put("modifiers", modifiers)
                put("key", k.key); put("code", k.code)
                put("windowsVirtualKeyCode", k.keyCode); put("nativeVirtualKeyCode", k.keyCode)
                if (text != null) { put("text", text); put("unmodifiedText", text) }
            },
        )
        if (type == "keyUp") heldKeys.remove(k)
    }

    /** 尽力抬起按着的键:每一发都有短上限,失败不管(页面 Input 可能已卡住)。 */
    internal suspend fun release() {
        suspend fun quiet(method: String, params: JsonObject) {
            runCatching { session.send(method, params, timeoutMs = RELEASE_TIMEOUT_MS) }
        }
        if (dragging) quiet("Input.cancelDragging", JsonObject(emptyMap()))
        pressedAt?.let { (x, y) ->
            quiet("Input.dispatchMouseEvent", buildJsonObject {
                put("type", "mouseReleased"); put("x", x * session.inputScale); put("y", y * session.inputScale)
                put("button", "left"); put("buttons", 0); put("clickCount", 1); put("pointerType", "mouse")
            })
        }
        for (k in heldKeys.asReversed()) {
            quiet("Input.dispatchKeyEvent", buildJsonObject {
                put("type", "keyUp"); put("key", k.key); put("code", k.code)
                put("windowsVirtualKeyCode", k.keyCode); put("nativeVirtualKeyCode", k.keyCode)
            })
        }
        pressedAt = null; dragging = false; heldKeys.clear()
    }

    companion object {
        const val RELEASE_TIMEOUT_MS = 1_000L
    }
}

/** 做一次不加节奏的输入动作(见下)。 */
suspend fun <T> CdpSession.input(timeoutMs: Long, block: suspend InputTrack.() -> T): InputOutcome<T> = input(timeoutMs, InputPacing.Instant, block)

/** 做一次输入动作,节奏按 [pacing];每发命令上限 [timeoutMs]。失败分「没送达」和「不确定」,后者已抬起按着的键。 */
suspend fun <T> CdpSession.input(timeoutMs: Long, pacing: InputPacing, block: suspend InputTrack.() -> T): InputOutcome<T> {
    val t = InputTrack(this, timeoutMs, pacing)
    return try {
        InputOutcome.Done(t.block())
    } catch (e: CancellationException) {
        withContext(NonCancellable) { t.release() }
        throw e
    } catch (e: Exception) {
        if (!t.mayHaveDelivered) InputOutcome.NotSent(e)
        else {
            t.release()
            InputOutcome.Uncertain(e)
        }
    }
}

/**
 * 页面视觉视口的 CSS 宽度,来自浏览器的排版数据(Page.getLayoutMetrics),不经页面脚本:
 * 页面改不了它,拿来把 view 像素换成 Input 用的 CSS 像素。
 */
suspend fun InputTrack.viewportCssWidth(): Double {
    val m = query("Page.getLayoutMetrics")
    fun width(k: String) = (m[k] as? JsonObject)?.get("clientWidth")?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 }
    return width("cssVisualViewport") ?: width("cssLayoutViewport") ?: throw CdpException(0, "no viewport size", "Page.getLayoutMetrics")
}

/** 移过去(按节奏沿一段轨迹,停一下)、按下、按住一会儿、抬起;页面看到的是可信的鼠标点击(pointerType mouse)。 */
suspend fun InputTrack.mouseClick(x: Double, y: Double) {
    approach(x, y)
    pause(pacing.dwell())
    mouse("mousePressed", x, y, "left", 1, 1)
    pause(pacing.press())
    mouse("mouseReleased", x, y, "left", 0, 1)
}

/** 只移动鼠标(按节奏沿一段轨迹):触发 mouseover/mouseenter 和 CSS :hover。 */
suspend fun InputTrack.mouseMove(x: Double, y: Double) = approach(x, y)

/** 指针从上一次的位置(不知道就从附近某处)移到 (x, y),最后一发正好落在目标上。 */
private suspend fun InputTrack.approach(x: Double, y: Double) {
    val from = session.pointer ?: pacing.origin(x, y)
    val points = pacing.path(from, x to y)
    points.forEachIndexed { i, (px, py) ->
        if (i > 0) pause(pacing.moveStep())
        mouse("mouseMoved", px, py, "none", 0, 0)
    }
}

/**
 * 指针移到 (x, y) 后转滚轮,一格一格地滚 [distance] CSS 像素(正为向下),格与格之间停一下。
 * 只用于正在显示的标签(见文件开头)。
 */
suspend fun InputTrack.mouseWheel(x: Double, y: Double, distance: Double) {
    approach(x, y)
    pacing.notches(distance).forEachIndexed { i, d ->
        if (i > 0) pause(pacing.wheelGap())
        wheel(x, y, d)
    }
}

/**
 * 指针不动,在它此刻的位置(不知道在哪就在 ([x], [y]))转滚轮,一格一格地滚 [distance] CSS 像素(正为向下)。
 * 停留时顺手滚一下用:不发鼠标移动,不悬停。只用于正在显示的标签(见文件开头)。
 */
suspend fun InputTrack.wheelInPlace(x: Double, y: Double, distance: Double) {
    val (px, py) = session.pointer ?: (x to y)
    pacing.notches(distance).forEachIndexed { i, d ->
        if (i > 0) pause(pacing.wheelGap())
        wheel(px, py, d)
    }
}

/**
 * 按住左键从起点分 [steps] 步拖到终点。按指针事件写的拖动(滑块、拖排序)直接收到移动;
 * 页面用 HTML 原生拖放(draggable)时,先让浏览器把拖动交给我们(setInterceptDrags),
 * 拿到拖动数据后在终点补 dragEnter/dragOver/drop。返回是否走了原生拖放。
 */
suspend fun InputTrack.mouseDrag(fromX: Double, fromY: Double, toX: Double, toY: Double, steps: Int, stepDelayMs: Long = 16): Boolean = coroutineScope {
    val intercept = runCatching { query("Input.setInterceptDrags", buildJsonObject { put("enabled", true) }) }.isSuccess
    val intercepted = if (intercept) async(start = CoroutineStart.UNDISPATCHED) { session.events.first { it.method == "Input.dragIntercepted" } } else null
    try {
        approach(fromX, fromY)
        pause(pacing.dwell())
        mouse("mousePressed", fromX, fromY, "left", 1, 1)
        pause(pacing.press())
        val n = steps.coerceAtLeast(1)
        for (i in 1..n) {
            delay(stepDelayMs)
            if (intercepted?.isCompleted == true) break
            val f = i.toDouble() / n
            mouse("mouseMoved", fromX + (toX - fromX) * f, fromY + (toY - fromY) * f, "left", 1, 0)
        }
        val data = intercepted?.takeIf { it.isCompleted }?.await()?.params?.get("data") as? JsonObject
        if (data != null) {
            for (type in listOf("dragEnter", "dragOver", "drop")) drag(type, toX, toY, data)
        } else {
            mouse("mouseReleased", toX, toY, "left", 0, 1)
        }
        data != null
    } finally {
        intercepted?.cancel()
        if (intercept) runCatching { query("Input.setInterceptDrags", buildJsonObject { put("enabled", false) }) }
    }
}

/** 按一次组合键:修饰键依次按下,主键按下抬起,修饰键倒序抬起。出字的键用 keyDown(带 text),其余用 rawKeyDown。 */
suspend fun InputTrack.pressCombo(combo: KeyCombo) {
    var held = 0
    for (m in combo.modifiers) {
        held = held or m.bit
        key("rawKeyDown", m.key, held, null)
        pause(pacing.modifierGap())
    }
    val text = combo.text
    val k = if (text != null && text != combo.key.text) combo.key.copy(key = text) else combo.key
    key(if (text != null) "keyDown" else "rawKeyDown", k, held, text)
    pause(pacing.keyHold())
    key("keyUp", k, held, null)
    for (m in combo.modifiers.asReversed()) {
        pause(pacing.modifierGap())
        held = held and m.bit.inv()
        key("keyUp", m.key, held, null)
    }
}

/**
 * 打字:有节奏时字母、数字、空格逐键敲(大写字母带 Shift),键间隔长短不一;其余的字(标点、中文、换行)
 * 一段一段整段交给焦点,像输入法提交。没有节奏、或文字长过 [InputPacing.Limits.maxKeyedChars] 时整段交给焦点(像粘贴)。
 * 不会因为文字里的换行按下回车。
 */
suspend fun InputTrack.typeText(text: String) {
    if (!pacing.paced || text.length > pacing.limits.maxKeyedChars) return insertText(text)
    var first = true
    for (run in TypingRuns.split(text)) {
        if (!first) pause(pacing.keyGap())
        first = false
        if (!run.keyed) {
            insertText(run.text)
            continue
        }
        run.text.forEachIndexed { i, c ->
            if (i > 0) pause(pacing.keyGap())
            val k = CdpKeys.named(c.toString())!!
            if (c in 'A'..'Z') pressCombo(KeyCombo(listOf(KeyModifier.Shift), k))
            else {
                key("keyDown", k, 0, k.text)
                pause(pacing.keyHold())
                key("keyUp", k, 0, null)
            }
        }
    }
}

/** 打字时怎么分段:[keyed] 的一段逐键敲,其余整段提交。 */
object TypingRuns {
    data class Run(val text: String, val keyed: Boolean)

    fun keyable(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == ' '

    fun split(text: String): List<Run> {
        val out = ArrayList<Run>()
        val cur = StringBuilder()
        var keyed: Boolean? = null
        for (c in text) {
            val k = keyable(c)
            if (keyed != null && k != keyed) {
                out += Run(cur.toString(), keyed)
                cur.clear()
            }
            keyed = k
            cur.append(c)
        }
        if (keyed != null) out += Run(cur.toString(), keyed)
        return out
    }
}

/** 把文字整段交给当前焦点(和输入法提交一样触发 input 事件)。 */
suspend fun InputTrack.insertText(text: String) {
    effect("Input.insertText", buildJsonObject { put("text", text) })
}

// ---------- 原生下拉框 ----------

data class SelectOption(val text: String, val value: String, val disabled: Boolean)

sealed interface SelectOutcome {
    /** [byScript]:键盘没选中,改由脚本设值并补发 input/change(不是可信事件)。 */
    data class Selected(val index: Int, val text: String, val changed: Boolean, val byScript: Boolean) : SelectOutcome
    data object NotASelect : SelectOutcome
    data object Disabled : SelectOutcome
    data class NoSuchOption(val available: List<String>) : SelectOutcome
    data class OptionDisabled(val text: String) : SelectOutcome
    /** 键盘和脚本都没让它选中这一项。 */
    data class NotApplied(val text: String) : SelectOutcome
}

object SelectMatch {
    /** 先按 value 完全相等,再按显示文字(去空白)相等,最后不分大小写;都没有为 null。 */
    fun indexOf(options: List<SelectOption>, wanted: String): Int? {
        val w = wanted.trim()
        options.indexOfFirst { it.value == wanted }.takeIf { it >= 0 }?.let { return it }
        options.indexOfFirst { it.text.trim() == w }.takeIf { it >= 0 }?.let { return it }
        return options.indexOfFirst { it.text.trim().equals(w, ignoreCase = true) || it.value.equals(w, ignoreCase = true) }.takeIf { it >= 0 }
    }
}

private const val SELECT_OF = "function(){var n=this;while(n&&!(n.tagName&&n.tagName.toUpperCase()==='SELECT')){" +
    "n=n.parentElement||(n.parentNode&&n.parentNode.host)||null;}return n;}"
private const val SELECT_INFO = "function(){return {disabled:!!this.disabled,multiple:!!this.multiple,index:this.selectedIndex," +
    "options:Array.prototype.slice.call(this.options,0,500).map(function(o){return {text:String(o.label||o.text||''),value:String(o.value),disabled:!!o.disabled};})};}"
private const val SELECT_INDEX = "function(i){return this.multiple?(this.options[i]&&this.options[i].selected?i:-1):this.selectedIndex;}"
private const val SELECT_SET = "function(i){if(this.multiple){this.options[i].selected=true;}else{" +
    "Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype,'selectedIndex').set.call(this,i);}" +
    "this.dispatchEvent(new Event('input',{bubbles:true}));this.dispatchEvent(new Event('change',{bubbles:true}));" +
    "return this.multiple?(this.options[i].selected?i:-1):this.selectedIndex;}"

private fun callParams(objectId: String, fn: String, args: List<Int>, byValue: Boolean) = buildJsonObject {
    put("objectId", objectId); put("functionDeclaration", fn); put("returnByValue", byValue)
    if (args.isNotEmpty()) put("arguments", buildJsonArray { args.forEach { add(buildJsonObject { put("value", it) }) } })
}

private fun callResult(r: JsonObject): JsonObject {
    (r["exceptionDetails"] as? JsonObject)?.let { throw CdpEvaluationException(it) }
    return r["result"]?.jsonObject ?: JsonObject(emptyMap())
}

/**
 * 选中 (x, y) 处原生下拉框里的一项。做法:按坐标找到节点(DOM.getNodeForLocation,穿透 shadow 与 iframe),
 * 往上找到 select;聚焦后用方向键一格一格移到目标项,每按一次读回当前项。
 * 方向键是可信的键盘事件,页面框架看到的是真实的 input/change,也不会弹出系统选择框。
 * 方向键走不到(页面拦了按键、多选框)时才用脚本设值并补发 input/change。聚焦、按键、脚本设值都算改动页面的事件。
 */
suspend fun InputTrack.selectOptionAt(x: Double, y: Double, wanted: String): SelectOutcome {
    runCatching { query("DOM.enable") }
    val loc = query(
        "DOM.getNodeForLocation",
        buildJsonObject { put("x", x.toInt()); put("y", y.toInt()); put("includeUserAgentShadowDOM", false); put("ignorePointerEventsNone", true) },
    )
    val backend = (loc["backendNodeId"] as? JsonPrimitive)?.intOrNull ?: return SelectOutcome.NotASelect
    val node = query("DOM.resolveNode", buildJsonObject { put("backendNodeId", backend) })["object"]?.jsonObject
    val nodeId = (node?.get("objectId") as? JsonPrimitive)?.contentOrNull ?: return SelectOutcome.NotASelect
    val sel = callResult(query("Runtime.callFunctionOn", callParams(nodeId, SELECT_OF, emptyList(), byValue = false)))
    val selectId = (sel["objectId"] as? JsonPrimitive)?.contentOrNull ?: return SelectOutcome.NotASelect
    try {
        val info = callResult(query("Runtime.callFunctionOn", callParams(selectId, SELECT_INFO, emptyList(), byValue = true)))["value"] as? JsonObject
            ?: return SelectOutcome.NotASelect
        if ((info["disabled"] as? JsonPrimitive)?.booleanOrNull == true) return SelectOutcome.Disabled
        val multiple = (info["multiple"] as? JsonPrimitive)?.booleanOrNull == true
        val options = (info["options"] as? JsonArray).orEmpty().mapNotNull { o ->
            val m = o as? JsonObject ?: return@mapNotNull null
            SelectOption(
                (m["text"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim(),
                (m["value"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                (m["disabled"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
        val target = SelectMatch.indexOf(options, wanted) ?: return SelectOutcome.NoSuchOption(options.map { it.text })
        val want = options[target]
        if (want.disabled) return SelectOutcome.OptionDisabled(want.text)
        suspend fun current() =
            (callResult(query("Runtime.callFunctionOn", callParams(selectId, SELECT_INDEX, listOf(target), byValue = true)))["value"] as? JsonPrimitive)?.intOrNull ?: -1
        val start = (info["index"] as? JsonPrimitive)?.intOrNull ?: -1
        if (!multiple && start == target || multiple && current() == target) return SelectOutcome.Selected(target, want.text, changed = false, byScript = false)
        if (!multiple) {
            effect("DOM.focus", buildJsonObject { put("objectId", selectId) })
            var at = start
            var presses = 0
            // 每按一次读回;按了没动(页面拦了按键)就不再按,总次数不超过选项数
            while (at != target && presses <= options.size) {
                pressCombo(KeyCombo(emptyList(), CdpKeys.named(if (at < target) "ArrowDown" else "ArrowUp")!!))
                presses++
                val now = current()
                if (now == at) break
                at = now
            }
            if (at == target) return SelectOutcome.Selected(target, want.text, changed = true, byScript = false)
        }
        val set = (callResult(effect("Runtime.callFunctionOn", callParams(selectId, SELECT_SET, listOf(target), byValue = true)))["value"] as? JsonPrimitive)?.intOrNull
        return if (set == target) SelectOutcome.Selected(target, want.text, changed = true, byScript = true) else SelectOutcome.NotApplied(want.text)
    } finally {
        runCatching { query("Runtime.releaseObject", buildJsonObject { put("objectId", selectId) }) }
    }
}
