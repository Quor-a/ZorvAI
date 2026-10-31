package com.ai.assistance.quro.core.mika.replay

import com.ai.assistance.quro.core.mika.uistate.ScreenSnapshot
import com.ai.assistance.quro.core.mika.uistate.UiElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.ai.assistance.quro.core.mika.tool.LegacyNames
import com.ai.assistance.quro.core.mika.tool.ToolIndex
import com.ai.assistance.quro.core.mika.tool.Verb

/**
 * 路线里认元素的办法:编号只在一次读屏内有效,换成角色、名字、资源 ID、位置(0–999)和同类中的序号。
 * [name] 为空表示不按名字认;形如 `{1}` 表示名字取自本次原话的槽。
 * [uniqueAtRecord]:记录时按这些特征在那一屏里只找到它一个。
 */
@Serializable
data class Selector(
    val role: String,
    val name: String,
    val resourceId: String?,
    val bounds: List<Int>,
    val ordinal: Int,
    val uniqueAtRecord: Boolean = false,
)

/** 一屏的锚点:前台包名 + 最多 [RouteAnchors.MAX] 段稳定文字。 */
@Serializable
data class Anchors(val packageName: String?, val texts: List<String>)

/** [argsJson] 已去掉编号,输入文字里的槽写成 `{1}`。 */
@Serializable
data class RouteStep(val tool: String, val argsJson: String, val target: Selector?, val before: Anchors, val after: Anchors)

/** 一条办成过的做法。[template] 是把原话里的槽值换成 `{n}` 之后的句子。 */
@Serializable
data class Route(
    val id: String,
    val template: String,
    val slots: Int,
    val packages: List<String>,
    val appVersions: Map<String, String>,
    val steps: List<RouteStep>,
    val successes: Int = 0,
    val consecutiveMisses: Int = 0,
    val missStep: Int? = null,
    val disabled: Boolean = false,
    val lastUsedMs: Long,
)

/** 路线的大小上限:步数和落盘字节数。 */
const val MAX_ROUTE_STEPS = 60
const val MAX_ROUTE_BYTES = 64 * 1024

/**
 * 改名前记下的路线:步骤里的旧工具名换成现在能记进路线的那个工具(几块屏都有的动词,取能回放的那一套)。
 * 现在的名字不动;认不出的留着,回放时按「不是可回放的动作」停下。
 */
fun Route.withCurrentToolNames(legacy: LegacyNames, tools: ToolIndex): Route {
    if (steps.all { tools[it.tool] != null }) return this
    return copy(steps = steps.map { st ->
        if (tools[st.tool] != null) st else legacy.resolve(st.tool) { tools[it]?.routable == true }?.let { st.copy(tool = it) } ?: st
    })
}

object RouteCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(r: Route): String = json.encodeToString(Route.serializer(), r)
    fun decode(s: String): Route = json.decodeFromString(Route.serializer(), s)
}

/** 从读屏里取选择器和锚点。 */
object RouteAnchors {
    const val MAX = 3
    private const val MAX_LEN = 12
    /** 数字、时间、金额一类每次都可能变,不当锚点。 */
    private val VOLATILE = Regex("[0-9０-９¥￥$€£%]")
    private val LIST_ROLES = setOf("list", "grid", "recycler", "scroll")

    fun stable(text: String): Boolean {
        val t = text.trim()
        return t.length in 1..MAX_LEN && !VOLATILE.containsMatchIn(t)
    }

    private fun container(e: UiElement) = e.scrollable || e.role.lowercase() in LIST_ROLES

    private fun inside(e: UiElement, c: UiElement) = e !== c && e.bounds.size == 4 && c.bounds.size == 4 &&
        e.bounds[0] >= c.bounds[0] && e.bounds[1] >= c.bounds[1] && e.bounds[2] <= c.bounds[2] && e.bounds[3] <= c.bounds[3]

    /**
     * 可当锚点的文字,按屏上顺序去重,有资源 ID 的排前面。列表、可滚动区里的条目是内容(联系人、消息、商品),
     * 输入框里的字是用户写的,都不算——只取页面的固定框架(标题栏、标签、按钮)。
     */
    fun texts(s: ScreenSnapshot): List<String> {
        val boxes = s.elements.filter(::container)
        return s.elements
            .filter { e -> !e.editable && !container(e) && boxes.none { inside(e, it) } && stable(e.name) }
            .sortedBy { it.resourceId == null }
            .map { it.name.trim() }.distinct()
    }

    fun before(s: ScreenSnapshot?, target: UiElement?): Anchors {
        if (s == null) return Anchors(null, emptyList())
        val self = target?.name?.trim()
        return Anchors(s.packageName, texts(s).filter { it != self }.take(MAX))
    }

    /** 动作后新出现的文字(动作前那一屏没有的)。 */
    fun after(before: ScreenSnapshot?, after: ScreenSnapshot): Anchors {
        val old = before?.let(::texts)?.toSet().orEmpty()
        return Anchors(after.packageName, texts(after).filter { it !in old }.take(MAX))
    }

    /**
     * 选择器里的名字:输入框只留提示语(没有就空),那是界面不是用户写的字;
     * 其余元素的名字要像锚点一样稳定(短、无数字),否则不存名字,只靠角色、资源 ID 和序号认。
     */
    fun storedName(e: UiElement): String = when {
        e.editable -> if (e.hint && stable(e.name)) e.name.trim() else ""
        stable(e.name) -> e.name.trim()
        else -> ""
    }

    fun selector(s: ScreenSnapshot, e: UiElement): Selector {
        val name = storedName(e)
        // 按存下来的特征认:名字没存就只比角色和资源 ID
        fun same(x: UiElement) = x.role == e.role && x.resourceId == e.resourceId && (name.isEmpty() || x.name.trim() == e.name.trim())
        val ordinal = s.elements.takeWhile { it !== e }.count(::same)
        return Selector(e.role, name, e.resourceId, e.bounds, ordinal, uniqueAtRecord = s.elements.count(::same) == 1)
    }

    /** 包含该点(0–999)的最小元素;同样小时先取能点的。 */
    fun elementAt(s: ScreenSnapshot, x: Int, y: Int): UiElement? = s.elements
        .filter { it.bounds.size == 4 && x in it.bounds[0]..it.bounds[2] && y in it.bounds[1]..it.bounds[3] }
        .minWithOrNull(compareBy<UiElement> { (it.bounds[2] - it.bounds[0]).toLong() * (it.bounds[3] - it.bounds[1]) }.thenBy { !it.clickable })
}

/** 生效的写操作:ok 且界面变了,或输入回读一致;启动 App、打开链接成功即算(端口已等到目标在前台)。 */
fun effectiveWrite(verb: Verb?, result: JsonObject): Boolean {
    if (result["ok"] != JsonPrimitive(true)) return false
    return result["changed"] == JsonPrimitive(true) || result["verification"] == JsonPrimitive("match") ||
        (verb in OPENS && result["changed"] != JsonPrimitive(false))
}

/** 打开 App、打开链接:成功就是到了目标那里。 */
internal val OPENS = setOf(Verb.LaunchApp, Verb.OpenLink)
