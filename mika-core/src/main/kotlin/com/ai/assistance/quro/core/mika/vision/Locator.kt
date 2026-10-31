package com.ai.assistance.quro.core.mika.vision

import com.ai.assistance.quro.core.mika.uistate.ScreenFamily
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.uistate.ScreenPort
import com.ai.assistance.quro.core.mika.uistate.ScreenSnapshot
import com.ai.assistance.quro.core.mika.uistate.TreeAdequacy
import com.ai.assistance.quro.core.mika.uistate.TreeQuality
import com.ai.assistance.quro.core.mika.uistate.UiElement
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/** 两张截图的哈希距离不超过它就算同一画面(控件树与画面同步、@v 编号仍有效)。 */
const val LOCATE_SCREEN_CHANGE = 8

sealed interface LocateResult {
    /** [x]/[y]/[box] 为 0–999 归一化,与 tap 同一坐标系;[source] 是 "elements" 或 "vision"。 */
    data class Found(
        val ref: String,
        val snapshotId: String,
        val x: Int,
        val y: Int,
        val box: IntArray?,
        val seen: String?,
        val source: String,
        val note: String?,
        /** 只靠看图、没有佐证(这一屏的树不能用,没东西可对照):点完要看效果。 */
        val unverified: Boolean = false,
    ) : LocateResult

    data class NotFound(val reason: String, val message: String) : LocateResult
}

/** 画面拿不到或不能给视觉模型看;[reason] 是 protected_content、no_app_open 等。 */
class ScreenUnavailable(val reason: String, message: String) : Exception(message)

/** 找不到、看不了时给模型的说明;点名的是这块屏那一套工具。 */
private class LocateMessages(f: ScreenFamily) {
    private val describe = f.tool("describe")
    private val dump = f.tool("list_elements")
    val notFoundNext = "Try another description or pass the exact on-screen label as text, look with $describe, or call hand_to_user."
    val protectedScreen = "This screen is protected and cannot be captured, so nothing on it can be located by sight. " +
        "Use $dump and tap by ref, or call hand_to_user."
    val noApp = "Nothing is open on this screen yet, so there is nothing to look at. Open an app or a page first."
    val noShot = "The screen could not be captured right now. Wait a moment and try again, or use $dump."
    val unchecked = "The screen could not be read, so its picture was not sent to the vision model. " +
        "Wait a moment and try again, or use $dump."
    val checkSeen = "Check that seen is what you want before tapping; if it is not, do not tap it."
    val uncorroborated = "Something was marked by sight, but this vision model's boxes are not verified and nothing backed this one up " +
        "(no on-screen label under it matching the text, and a second look did not mark the same element), so it was not used. " +
        "Use $describe to look at the screen, then tap by coordinates with a target; or pass the exact on-screen label as text."
    val visionOnly = "Marked by sight alone: this screen's element list cannot back the mark up, so it was not cross-checked. " +
        "Check that seen is what you want, tap it, then read the result's effect: if it is no_effect, the mark missed; do not tap it again, " +
        "find it again with a different description or tap a visibleText center instead."
}

private val BBOX_2D = Regex("\"?bbox_2d\"?\\s*:\\s*\\[\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*]")
private val BARE_BOX = Regex("^\\[\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*]$")
private val FOUR_NUMBERS = Regex("\\[\\s*\\d+(?:\\.\\d+)?\\s*,\\s*\\d+(?:\\.\\d+)?\\s*,\\s*\\d+(?:\\.\\d+)?\\s*,\\s*\\d+(?:\\.\\d+)?\\s*]")
private val SEEN = Regex("\"seen\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
private val YES = Regex("^(是|yes(?![A-Za-z0-9_]))", RegexOption.IGNORE_CASE)
private const val MAX_SEEN = 120

private val PROMPTS: Pair<String, String> by lazy {
    val (locate, confirm) = Prompts.role("locate").split("<!-- confirm -->")
    locate.trim() to confirm.trim()
}
private val DESCRIBE_PROMPT: String by lazy { Prompts.role("describe") }

/**
 * 在屏上找一个元素:有原文且控件树和画面同步时先在控件树里找唯一的精确匹配,
 * 否则让视觉模型框出来,再核对名字、画红框复看确认。找到的点登记进 [registry]。
 * 受保护的页面(系统防截屏)的画面一律不交给视觉模型。
 */
class Locator(private val screen: ScreenPort, private val vision: VisionService, private val registry: LocateRegistry, family: ScreenFamily) {
    private val m = LocateMessages(family)
    private class Box(val l: Int, val t: Int, val r: Int, val b: Int, val seen: String?)

    suspend fun locate(target: String, text: String?): LocateResult {
        val first = capture().let { it as? Shot.Ok ?: return (it as Shot.Missing).asNotFound() }
        val elements = if (text != null) readElements() else null
        val second = capture().let { it as? Shot.Ok ?: return (it as Shot.Missing).asNotFound() }
        val synced = elements != null && hamming(first.hash, second.hash) <= LOCATE_SCREEN_CHANGE

        if (text != null && synced) {
            val hit = exactMatch(elements!!, text)
            if (hit != null) {
                val b = hit.bounds
                val x = mid(b[0], b[2]); val y = mid(b[1], b[3])
                val (ref, sid) = registry.put(x, y, second.hash)
                return LocateResult.Found(ref, sid, x, y, intArrayOf(b[0], b[1], b[2], b[3]), null, "elements", null)
            }
        }

        clearForVision()?.let { return it.asNotFound() }
        val (locatePrompt, confirmPrompt) = PROMPTS
        val box = try {
            parseBox(vision.ask(locatePrompt, if (text != null) "$target (label: \"$text\")" else target, listOf(second.png)))
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return LocateResult.NotFound("vision_unavailable", "Locating by sight failed this time. ${m.notFoundNext}")
        } ?: return LocateResult.NotFound("not_found", "The described element was not found on the screen. ${m.notFoundNext}")

        if (text != null && box.seen != null && disagree(box.seen, text)) {
            return LocateResult.NotFound("name_mismatch", "The closest match reads \"${box.seen.take(40)}\", not \"$text\", so it was not used. ${m.notFoundNext}")
        }
        val x = mid(box.l, box.r); val y = mid(box.t, box.b)
        val occupant = if (text != null && synced) elementAt(elements!!, x, y) else null
        if (occupant != null && occupant.name.isNotBlank() && disagree(occupant.name, text!!)) {
            return LocateResult.NotFound("name_mismatch", "The spot that looked like it is labelled \"${occupant.name.take(40)}\", not \"$text\", so it was not used. ${m.notFoundNext}")
        }

        val boxArr = intArrayOf(box.l, box.t, box.r, box.b)
        val confirmed = try {
            val framed = vision.ops.drawFrame(second.png, boxArr)
            YES.containsMatchIn(vision.ask(confirmPrompt.replace("{target}", target), "Yes or no?", listOf(framed)).trim())
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return LocateResult.NotFound("vision_unavailable", "Locating by sight failed this time. ${m.notFoundNext}")
        }
        if (!confirmed) return LocateResult.NotFound("not_confirmed", "Something was marked, but a second look did not confirm it is the described element. ${m.notFoundNext}")

        val nameChecked = occupant != null && occupant.name.isNotBlank()
        // 定位准度没验证过的模型:只靠看图的框要有佐证——框中心的元素就是要找的字(上面已核过名字),或再框一次、复看两次都确认且看到的一致。
        // 树不能用、或缺了一块而要找的不在树里时,没有东西可对照:照样交出去,标明没佐证,点完由效果说话
        val visionOnly = !vision.groundingVerified && !nameChecked && nothingToCheckAgainst(text)
        if (!vision.groundingVerified && !nameChecked && !visionOnly) {
            val again = try {
                secondLook(target, text, box, second.png, locatePrompt, confirmPrompt)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                return LocateResult.NotFound("vision_unavailable", "Locating by sight failed this time. ${m.notFoundNext}")
            }
            if (!again) return LocateResult.NotFound("unverified_grounding", m.uncorroborated)
        }

        val (ref, sid) = registry.put(x, y, second.hash)
        if (visionOnly) return LocateResult.Found(ref, sid, x, y, boxArr, box.seen, "vision", m.visionOnly, unverified = true)
        return LocateResult.Found(ref, sid, x, y, boxArr, box.seen, "vision", m.checkSeen.takeUnless { nameChecked && box.seen != null })
    }

    /**
     * 这一屏没有能给看图的框作佐证的东西:树不能用;或缺了一块,而要找的字([text])不在树里(没给字时按不在算)。
     * 树够用、判不准、读不到屏时都不算:照旧要佐证。
     */
    private suspend fun nothingToCheckAgainst(text: String?): Boolean {
        val s = peekOrNull() ?: return false
        return when (TreeAdequacy.ofTree(s).quality) {
            TreeQuality.Unusable -> true
            TreeQuality.Partial -> text == null || s.elements.none { it.name.isNotBlank() && norm(text) in norm(it.name) }
            else -> false
        }
    }

    /**
     * 同一张图再框一次:两次的框互相包住对方的中心、看到的字一致(都要有),第二个框画红框复看也确认,才算佐证。
     */
    private suspend fun secondLook(target: String, text: String?, first: Box, png: ByteArray, locatePrompt: String, confirmPrompt: String): Boolean {
        val box = parseBox(vision.ask(locatePrompt, if (text != null) "$target (label: \"$text\")" else target, listOf(png))) ?: return false
        val seenA = first.seen ?: return false
        val seenB = box.seen ?: return false
        if (disagree(seenA, seenB) || (text != null && disagree(seenB, text))) return false
        if (!contains(first, mid(box.l, box.r), mid(box.t, box.b)) || !contains(box, mid(first.l, first.r), mid(first.t, first.b))) return false
        val framed = vision.ops.drawFrame(png, intArrayOf(box.l, box.t, box.r, box.b))
        return YES.containsMatchIn(vision.ask(confirmPrompt.replace("{target}", target), "Yes or no?", listOf(framed)).trim())
    }

    private fun contains(b: Box, x: Int, y: Int) = x in b.l..b.r && y in b.t..b.b

    /** 当前截图和「我现在想做的事」交给视觉模型,原样返回描述。画面拿不到抛 [ScreenUnavailable],模型出错抛 ModelError。 */
    suspend fun describe(task: String): String {
        val shot = capture().let { it as? Shot.Ok ?: throw (it as Shot.Missing).let { m -> ScreenUnavailable(m.reason, m.message) } }
        clearForVision()?.let { throw ScreenUnavailable(it.reason, it.message) }
        return vision.ask(DESCRIBE_PROMPT, "What I want to do now: $task", listOf(shot.png))
    }

    /**
     * 当前画面能不能交给视觉模型:能为 null;不能时是原因
     * (protected_content、读不到屏时 vision_unavailable)。
     */
    internal suspend fun visionBlocker(): String? = clearForVision()?.reason

    private sealed interface Shot {
        class Ok(val png: ByteArray, val hash: Long) : Shot
        class Missing(val reason: String, val message: String) : Shot {
            fun asNotFound() = LocateResult.NotFound(reason, message)
        }
    }

    /**
     * 截一张图。屏上有防截屏窗口时不截(截到的也是黑的,付款页一类不能外传);
     * 截不到时区分「还没开 App」和一时截不到。
     */
    private suspend fun capture(): Shot {
        if (screen.isProtected()) return Shot.Missing("protected_content", m.protectedScreen)
        val png = screen.screenshotPng()
        if (png == null) {
            val s = peekOrNull()
            val empty = s != null && s.packageName == null && s.elements.isEmpty() && !s.hasWindow
            return if (empty) Shot.Missing("no_app_open", m.noApp) else Shot.Missing("screenshot_unavailable", m.noShot)
        }
        // 截完再看一次:截图过程中切到了受保护页面
        if (vision.ops.isMostlyBlack(png) && screen.isProtected()) return Shot.Missing("protected_content", m.protectedScreen)
        return Shot.Ok(png, vision.ops.aHash(png))
    }

    /**
     * 交给视觉模型前再读一次屏:受保护页面不传；读不到屏也不传。
     * 付款页按普通页面处理。可以传时为 null。
     */
    private suspend fun clearForVision(): Shot.Missing? {
        val s = peekOrNull() ?: return Shot.Missing("vision_unavailable", m.unchecked)
        if (s.protected) return Shot.Missing("protected_content", m.protectedScreen)
        return null
    }

    private suspend fun readElements(): List<UiElement>? = peekOrNull()?.takeIf { !it.protected }?.elements

    private suspend fun peekOrNull(): ScreenSnapshot? =
        try { screen.peek() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    /** 名字或资源 ID(去空白、小写)恰好等于 [text] 的唯一元素;嵌套或同中心的算同一个,多个为 null。 */
    private fun exactMatch(elements: List<UiElement>, text: String): UiElement? {
        val want = norm(text)
        val hits = elements.filter { e -> onScreen(e) && (norm(e.name) == want || e.resourceId?.let { norm(it) == want } == true) }
        val distinct = hits.filterIndexed { i, e -> hits.take(i).none { o -> center(o) == center(e) || nested(o, e) } }
        return distinct.singleOrNull()
    }

    /** 那一点上面积最小、且不到半屏的元素。 */
    private fun elementAt(elements: List<UiElement>, x: Int, y: Int): UiElement? = elements.filter { e ->
        val b = e.bounds
        b.size == 4 && x in b[0]..b[2] && y in b[1]..b[3] && area(e) < 999L * 999 / 2
    }.minByOrNull(::area)

    private fun parseBox(reply: String): Box? {
        val t = reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (t == "null") return null
        if (Regex("bbox_2d").findAll(t).count() > 1 || FOUR_NUMBERS.findAll(t).count() > 1) return null
        val m = BBOX_2D.find(t) ?: BARE_BOX.find(t) ?: return null
        val (x1, y1, x2, y2) = m.groupValues.drop(1).map { it.toDouble() }
        if (listOf(x1, y1, x2, y2).any { it > 1000 } || x2 <= x1 || y2 <= y1) return null
        // 0–1 的小数框是另一种坐标约定,换算不可靠
        if (maxOf(x1, y1, x2, y2) <= 1) return null
        fun scale(v: Double) = (v * 999 / 1000).roundToInt()
        val seen = SEEN.find(t)?.groupValues?.get(1)?.let(::unescape)?.trim()?.take(MAX_SEEN)?.ifEmpty { null }
        return Box(scale(x1), scale(y1), scale(x2), scale(y2), seen)
    }

    private fun unescape(raw: String): String =
        try { (Json.parseToJsonElement("\"$raw\"") as JsonPrimitive).content } catch (e: Exception) { raw }

    private fun disagree(a: String, b: String): Boolean {
        val x = norm(a); val y = norm(b)
        return x.isNotEmpty() && y.isNotEmpty() && y !in x && x !in y
    }

    /** 在屏内且有面积(读屏把屏外的元素压到边上,面积为 0)。 */
    private fun onScreen(e: UiElement): Boolean {
        val b = e.bounds
        return b.size == 4 && b[0] >= 0 && b[1] >= 0 && b[2] <= 999 && b[3] <= 999 && b[2] > b[0] && b[3] > b[1]
    }

    private fun norm(s: String) = s.filterNot(Char::isWhitespace).lowercase()
    private fun mid(a: Int, b: Int) = ((a + b) / 2.0).roundToInt()
    private fun center(e: UiElement) = mid(e.bounds[0], e.bounds[2]) to mid(e.bounds[1], e.bounds[3])
    private fun area(e: UiElement): Long = (e.bounds[2] - e.bounds[0]).toLong() * (e.bounds[3] - e.bounds[1])

    private fun nested(a: UiElement, b: UiElement): Boolean {
        val p = a.bounds; val q = b.bounds
        return (p[0] <= q[0] && p[1] <= q[1] && p[2] >= q[2] && p[3] >= q[3]) || (q[0] <= p[0] && q[1] <= p[1] && q[2] >= p[2] && q[3] >= p[3])
    }
}
