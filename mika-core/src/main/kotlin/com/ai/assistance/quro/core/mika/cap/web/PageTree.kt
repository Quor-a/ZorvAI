package com.ai.assistance.quro.core.mika.cap.web

import com.ai.assistance.quro.core.mika.uistate.ScreenSnapshot
import com.ai.assistance.quro.core.mika.uistate.UiElement
import com.ai.assistance.quro.core.mika.uistate.normalize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.max
import kotlin.math.roundToInt
import com.ai.assistance.quro.core.mika.uistate.WebInfo

/**
 * 页面脚本输出 → ScreenSnapshot。输入形如
 * `{"els":[{"r":"button","n":"搜索","x":120,"y":40,"w":80,"h":32,"id":"go","c":1,"e":0,"s":0}],"vw":412,"vh":915,"dpr":2.625,"trunc":false,"mut":17}`,
 * 坐标为 CSS 像素。center 换成 WebView 像素(优先按 viewportW / vw 换算,其次 dpr),夹在视口内;bounds 按视口归一到 0–999,和手机同一坐标系。
 * 另有可选标记:`ph`=name 是输入框的提示语(placeholder)而不是已填内容,`f`=有输入焦点;文件选择控件的 `r` 为 "file"。
 * ref 按数组位置编号:坏掉的条目也占一个号,和页面脚本里的编号表对得上。
 * 坏输入给空列表,不抛异常:读屏失败不该让整个回合崩掉。
 */
object PageTree {
    fun parse(json: String, viewportW: Int, viewportH: Int, snapshotId: String, info: WebInfo): ScreenSnapshot {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
        val cssW = root.num("vw")?.takeIf { it > 0 }
        val scale = cssW?.takeIf { viewportW > 0 }?.let { viewportW / it }
            ?: root.num("dpr")?.takeIf { it > 0 }
            ?: 1.0
        val elements = (root?.get("els") as? JsonArray).orEmpty()
            .mapIndexedNotNull { i, e -> (e as? JsonObject)?.let { element(i + 1, it, scale, viewportW, viewportH) } }
        return ScreenSnapshot(
            snapshotId = snapshotId,
            packageName = null,
            elements = elements,
            width = viewportW,
            height = viewportH,
            web = info.copy(truncated = root.flag("trunc")),
        )
    }

    private fun element(n: Int, o: JsonObject, scale: Double, w: Int, h: Int): UiElement {
        val l = px(o.num("x"), scale)
        val t = px(o.num("y"), scale)
        val r = px((o.num("x") ?: 0.0) + (o.num("w") ?: 0.0), scale)
        val b = px((o.num("y") ?: 0.0) + (o.num("h") ?: 0.0), scale)
        val id = o.str("id")?.takeIf { it.isNotBlank() }
        return UiElement(
            ref = "@e$n",
            role = o.str("r") ?: "text",
            name = o.str("n") ?: "",
            centerX = ((l + r) / 2).coerceIn(0, max(0, w - 1)),
            centerY = ((t + b) / 2).coerceIn(0, max(0, h - 1)),
            bounds = listOf(normalize(l, w), normalize(t, h), normalize(r, w), normalize(b, h)),
            resourceId = id?.let { if (it.startsWith("#")) it else "#$it" },
            clickable = o.flag("c"),
            editable = o.flag("e"),
            scrollable = o.flag("s"),
            hint = o.flag("ph"),
            focused = o.flag("f"),
            secret = o.flag("pw"),
            placeholder = o.str("ph")?.takeIf { it.isNotBlank() },
            searchField = o.flag("sr"),
            keyShortcuts = o.str("k")?.split(' ')?.filter(String::isNotBlank)?.take(MAX_SHORTCUTS).orEmpty(),
        )
    }

    /** 页面给的数可能是巨大值或无穷:先夹住再取整,别让取整溢出、中心点算到天边。 */
    private fun px(css: Double?, scale: Double): Int = ((css ?: 0.0) * scale).coerceIn(-PX_LIMIT, PX_LIMIT).roundToInt()

    private const val PX_LIMIT = 1e6
    private const val MAX_SHORTCUTS = 8

    private fun JsonObject?.num(key: String): Double? =
        (this?.get(key) as? JsonPrimitive)?.doubleOrNull?.let { if (it.isFinite()) it.coerceIn(-PX_LIMIT, PX_LIMIT) else 0.0 }

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** 0/1 与 true/false 都认。 */
    private fun JsonObject?.flag(key: String): Boolean {
        val p = this?.get(key) as? JsonPrimitive ?: return false
        return p.booleanOrNull ?: ((p.intOrNull ?: 0) != 0)
    }
}
