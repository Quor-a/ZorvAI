package com.ai.assistance.quro.core.mika.userscreen

import com.ai.assistance.quro.core.mika.uistate.ScreenSnapshot
import com.ai.assistance.quro.core.mika.uistate.TreeAdequacy
import com.ai.assistance.quro.core.mika.uistate.TreeQuality
import com.ai.assistance.quro.core.mika.uistate.VisibleText
import com.ai.assistance.quro.core.mika.uistate.VisibleTextLine
import com.ai.assistance.quro.core.mika.uistate.UiElement
import com.ai.assistance.quro.core.mika.uistate.normalize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 用户屏幕上的字从哪来:先用前台 App 的无障碍树;树不够用(和任务读屏同一套判断,[TreeAdequacy])时再认画面上的字(OCR),
 * 只补树里没有的、去掉认错的行。每行带中心点,看不到图的模型也分得清左边右边(聊天里对方和自己的气泡)。
 */
object UserScreenText {
    /** 一屏最多留这么多行、每行这么多字:够看清这一屏,又不把上下文撑大。 */
    const val MAX_LINES = 120
    const val MAX_CHARS = 200

    /** 树不是明显够用:要再认一遍画面上的字。受保护的页面画面是黑的,不认。 */
    fun needsOcr(s: ScreenSnapshot): Boolean = TreeAdequacy.ofTree(s).quality.let { it != TreeQuality.Adequate && it != TreeQuality.Protected }

    /**
     * 屏上的字,从上到下、同一行从左到右。树里有名字的元素(密码框不算)和 [ocr] 里树没有的行合在一起;
     * [top]/[bottom](0–999)以外是状态栏、导航条那几条,那里认出的字(时间、电量)不要。第二个值是有没有用到认出来的字。
     */
    fun lines(s: ScreenSnapshot, ocr: List<VisibleTextLine>?, top: Int = 0, bottom: Int = 999): Pair<List<ScreenLine>, Boolean> {
        val named = s.elements.filter { !it.secret && it.name.isNotBlank() }
        // 元素的中心是像素,bounds 和认出来的字一样是 0–999:都换成 0–999 再合、再排
        val tree = named.filterNot { joinsChildren(it, named) }
            .map { ScreenLine(clip(it.name), normalize(it.centerX, s.width), normalize(it.centerY, s.height)) }
        val extra = ocr?.let { VisibleText.missing(s, VisibleText.legible(it)) }.orEmpty()
            .map { ScreenLine(clip(it.text), (it.bounds[0] + it.bounds[2]) / 2, (it.bounds[1] + it.bounds[3]) / 2) }
            .filter { it.y in top..bottom }
        val all = (tree + extra).distinctBy { Triple(it.text, it.x / 20, it.y / 20) }
            .sortedWith(compareBy({ it.y / ROW }, { it.x }))
            .take(MAX_LINES)
        return all to extra.any { it in all }
    }

    /**
     * [e] 只是把框在它里面的那几段字连起来(一行设置项、一条消息的整块):去掉它的名字里这几段后不剩字。
     * 这时留里面那几段,各有各的位置;名字里有自己的字时照留。
     */
    private fun joinsChildren(e: UiElement, all: List<UiElement>): Boolean {
        val inner = all.filter { it !== e && inside(it.bounds, e.bounds) && area(it.bounds) < area(e.bounds) }
        if (inner.isEmpty()) return false
        var rest = e.name
        inner.map { it.name.trim() }.sortedByDescending { it.length }.forEach { rest = rest.replace(it, "") }
        return rest.none { it.isLetterOrDigit() }
    }

    private fun inside(a: List<Int>, b: List<Int>) = a.size == 4 && b.size == 4 && a[0] >= b[0] && a[1] >= b[1] && a[2] <= b[2] && a[3] <= b[3]
    private fun area(b: List<Int>) = if (b.size == 4) (b[2] - b[0]).toLong() * (b[3] - b[1]) else 0L

    /** 同一行的容差(0–999):中心高低差不到这么多的算同一行,按左右排。 */
    private const val ROW = 12

    private fun clip(t: String): String {
        val one = t.replace('\n', ' ').trim()
        return if (one.length <= MAX_CHARS) one else one.take(MAX_CHARS) + "…"
    }
}

/** 一屏的说明存成的 JSON(和截图放在一起);读不懂的返回 null。 */
object UserScreenJson {
    fun encode(s: UserScreen): String = buildJsonObject {
        put("id", s.id); put("takenAt", s.takenAt)
        s.packageName?.let { put("package", it) }; s.appLabel?.let { put("label", it) }
        if (s.blocked) put("blocked", true)
        if (s.fromOcr) put("ocr", true)
        put("lines", JsonArray(s.lines.map { l -> buildJsonObject { put("t", l.text); put("x", l.x); put("y", l.y) } }))
    }.toString()

    fun decode(text: String): UserScreen? = runCatching {
        val o = Json.parseToJsonElement(text) as JsonObject
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        UserScreen(
            id = str("id") ?: return null,
            takenAt = (o["takenAt"] as? JsonPrimitive)?.longOrNull ?: return null,
            packageName = str("package"), appLabel = str("label"),
            blocked = (o["blocked"] as? JsonPrimitive)?.booleanOrNull == true,
            lines = (o["lines"] as? JsonArray).orEmpty().mapNotNull { e ->
                val l = e as? JsonObject ?: return@mapNotNull null
                ScreenLine((l["t"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                    (l["x"] as? JsonPrimitive)?.intOrNull ?: 0, (l["y"] as? JsonPrimitive)?.intOrNull ?: 0)
            },
            fromOcr = (o["ocr"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }.getOrNull()
}
