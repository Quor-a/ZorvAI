package com.ai.assistance.quro.core.mika.cap.web

import com.ai.assistance.quro.core.mika.cap.web.cdp.CdpEvaluationException
import com.ai.assistance.quro.core.mika.cap.web.cdp.CdpSession
import com.ai.assistance.quro.core.mika.uistate.ScreenSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 读屏时经 DevTools 会话给页面脚本的元素表补无障碍信息,再交给 [ElementFusion] 合并。
 *
 * 换节点号的做法:页面脚本本来就按编号表留着元素引用,让它把这张表原样交出(一次 Runtime.evaluate 拿到数组对象,
 * 一次 Runtime.getProperties 拆出每个元素的对象引用),再并发 DOM.describeNode 换成 backendNodeId。
 * 不往页面写标记(不触发页面和我们自己的变化计数),不取整棵 DOM.getDocument(大页面上几 MB),
 * 也不靠坐标命中(命中的常是按钮里的文字子元素,要再往上找)。N 个 describeNode 同时发出,往返只算一轮。
 *
 * 之后:Page.getFrameTree → 每帧 Accessibility.getFullAXTree(frameId)(跨源框架和主文档在同一目标里,按帧取);
 * 只有无障碍树有的候选:DOM.getBoxModel 取框、DOM.getNodeForLocation 确认在最上面;
 * 不可点的文字、图片:DOMDebugger.getEventListeners 看有没有点击类监听器。
 * 整个过程限时 [timeoutMs];超时、出错都返回 [Outcome.Skipped],调用方照用页面脚本的结果。
 */
class ElementProbe(private val session: CdpSession, private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    // 单个调用的上限放宽些:总时限先到,整次放弃,而不是某一步超时后带着残缺的数据往下走
    private val callTimeoutMs = timeoutMs + 1_000
    sealed interface Outcome {
        class Fused(val result: ElementFusion.Result, val anchors: Map<String, Anchor>, val stats: String) : Outcome
        class Skipped(val reason: String) : Outcome
    }

    /**
     * 只有无障碍树有的元素,点之前重新找它用:[hits] 是它自己和子孙的节点号(命中这些都算点到它),
     * [ownerId] 是所在框架元素的节点号(框若按框架内坐标给出,用它换算)。
     */
    data class Anchor(val backendId: Int, val hits: Set<Int>, val ownerId: Int?)

    /**
     * 找到的位置(顶层视口 CSS 像素):[box] 整个框,([x],[y]) 是框在可见范围里那部分的中心(点它就点这里);
     * [onTop] 命中检测结果,检测不了为 null。
     */
    data class Placed(val box: ElementFusion.Box, val onTop: Boolean?, val x: Double = box.cx, val y: Double = box.cy)

    private class Ref(val objectId: String?, val backendId: Int?, val frameId: String?)

    private class Skip(message: String) : Exception(message)

    /** [refNodes] 是在页面主环境里求值、返回本次编号表元素数组的表达式;[scale] 是 CSS 像素 → 视图像素。 */
    suspend fun enrich(page: ScreenSnapshot, refNodes: String, scale: Double): Outcome {
        val started = System.nanoTime()
        return try {
            withTimeout(timeoutMs) { run(page, refNodes, scale, started) }
        } catch (e: TimeoutCancellationException) {
            Outcome.Skipped("timeout after ${timeoutMs}ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Skipped(e.message ?: e.javaClass.simpleName)
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(RELEASE_TIMEOUT_MS) {
                    runCatching { session.send("Runtime.releaseObjectGroup", buildJsonObject { put("objectGroup", GROUP) }, timeoutMs = RELEASE_TIMEOUT_MS) }
                }
            }
        }
    }

    private suspend fun run(page: ScreenSnapshot, refNodes: String, scale: Double, started: Long): Outcome = coroutineScope {
        val frames = async { frameTree() }
        val mainAx = async { frames.await().firstOrNull()?.let { axTree(it.id) }.orEmpty() }
        val refs = refs(refNodes, page.elements.size)
        val tIds = ms(started)
        val frameList = frames.await()
        val owners = HashMap<Int, String>()
        refs.forEach { r -> if (r.backendId != null && r.frameId != null) owners[r.backendId] = r.frameId }
        // describeNode 没给出框架号的,反过来按帧问它的框架元素
        val known = owners.values.toSet()
        frameList.drop(1).filter { it.id !in known }.take(MAX_FRAMES).map { f ->
            async { frameOwner(f.id)?.let { it to f.id } }
        }.awaitAll().filterNotNull().forEach { (id, f) -> owners[id] = f }
        val pageIds = refs.map { it.backendId }
        val base = ElementFusion.Input(page.elements, pageIds, owners, frameList, emptyList(), scale = scale, width = page.width, height = page.height)
        val wanted = framesToRead(base, frameList)
        val ax = mainAx.await() + wanted.map { async { axTree(it) } }.awaitAll().flatten()
        val tAx = ms(started)
        val withAx = ElementFusion.Input(page.elements, pageIds, owners, frameList, ax, scale = scale, width = page.width, height = page.height)
        val candidates = ElementFusion.candidates(withAx)
        val anchors = candidates.mapNotNull { c ->
            c.node.backendId?.let { it to Anchor(it, ElementFusion.subtreeIds(ax, c.node), c.ownerId) }
        }.toMap()
        val placed = candidates.map { c ->
            async { c.node.backendId?.let { id -> place(anchors.getValue(id), ElementFusion.clip(withAx, c))?.let { id to it } } }
        }
        val heard = ElementFusion.listenerProbes(withAx).map { i ->
            async { refs[i].objectId?.let { o -> if (listens(o)) refs[i].backendId else null } }
        }
        val boxes = placed.awaitAll().filterNotNull()
        val listeners = heard.awaitAll().filterNotNull().toSet()
        val input = ElementFusion.Input(
            page.elements, pageIds, owners, frameList, ax,
            boxes = boxes.associate { it.first to it.second.box },
            onTop = boxes.mapNotNull { (id, p) -> p.onTop?.let { id to it } }.toMap(),
            listeners = listeners, scale = scale, width = page.width, height = page.height,
        )
        val result = ElementFusion.fuse(input)
        val refAnchors = result.backendIds.filterKeys { result.origins[it] == ElementFusion.Origin.AX }
            .mapNotNull { (ref, id) -> anchors[id]?.let { ref to it } }.toMap()
        val stats = "els=${page.elements.size} matched=${pageIds.count { it != null }} frames=${frameList.size}/${wanted.size + 1} " +
            "ax=${ax.size} candidates=${candidates.size} added=${refAnchors.size} listeners=${listeners.size} omitted=${result.omitted} " +
            "ms(ids=$tIds ax=$tAx total=${ms(started)})"
        Outcome.Fused(result, refAnchors, stats)
    }

    /** 主文档、跨源占位里的框架优先,至多 [MAX_FRAMES] 个(不含主文档)。 */
    private fun framesToRead(input: ElementFusion.Input, frames: List<ElementFusion.Frame>): List<String> {
        val behind = HashSet<String>()
        input.pageIds.forEachIndexed { i, id -> if (id != null && input.page[i].role == "frame") input.owners[id]?.let(behind::add) }
        val parent = frames.associate { it.id to it.parent }
        fun underPlaceholder(f: String): Boolean {
            var cur: String? = f
            var hops = 0
            while (cur != null && hops++ < 32) {
                if (cur in behind) return true
                cur = parent[cur]
            }
            return false
        }
        val rest = frames.drop(1).map { it.id }
        return (rest.filter(::underPlaceholder) + rest.filterNot(::underPlaceholder)).take(MAX_FRAMES)
    }

    private suspend fun refs(expression: String, expected: Int): List<Ref> = coroutineScope {
        val r = session.send(
            "Runtime.evaluate",
            buildJsonObject {
                put("expression", expression)
                put("returnByValue", false)
                put("objectGroup", GROUP)
                put("silent", true)
            },
            timeoutMs = callTimeoutMs,
        )
        (r["exceptionDetails"] as? JsonObject)?.let { throw CdpEvaluationException(it) }
        val arr = (r["result"] as? JsonObject)?.str("objectId") ?: throw Skip("page script has no ref table")
        val props = session.send(
            "Runtime.getProperties",
            buildJsonObject { put("objectId", arr); put("ownProperties", true) },
            timeoutMs = callTimeoutMs,
        )["result"] as? JsonArray ?: throw Skip("ref table unreadable")
        val byIndex = HashMap<Int, String?>()
        for (p in props) {
            val o = p as? JsonObject ?: continue
            val i = o.str("name")?.toIntOrNull() ?: continue
            byIndex[i] = (o["value"] as? JsonObject)?.takeIf { it.str("subtype") == "node" }?.str("objectId")
        }
        if (byIndex.size != expected) throw Skip("ref table has ${byIndex.size} entries, page tree has $expected")
        (0 until expected).map { i ->
            async {
                val oid = byIndex[i] ?: return@async Ref(null, null, null)
                val node = runCatching {
                    session.send("DOM.describeNode", buildJsonObject { put("objectId", oid) }, timeoutMs = callTimeoutMs)["node"] as? JsonObject
                }.getOrNull()
                Ref(oid, node?.int("backendNodeId"), node?.str("frameId"))
            }
        }.awaitAll()
    }

    private suspend fun frameTree(): List<ElementFusion.Frame> {
        val tree = session.send("Page.getFrameTree", timeoutMs = callTimeoutMs)["frameTree"] as? JsonObject ?: return emptyList()
        return parseFrameTree(tree)
    }

    private suspend fun frameOwner(frameId: String): Int? = runCatching {
        session.send("DOM.getFrameOwner", buildJsonObject { put("frameId", frameId) }, timeoutMs = callTimeoutMs).int("backendNodeId")
    }.getOrNull()

    private suspend fun axTree(frameId: String): List<ElementFusion.AxNode> = runCatching {
        val nodes = session.send("Accessibility.getFullAXTree", buildJsonObject { put("frameId", frameId) }, timeoutMs = callTimeoutMs)["nodes"] as? JsonArray
        parseAxTree(nodes.orEmpty().mapNotNull { it as? JsonObject }, frameId)
    }.getOrElse { if (it is CancellationException) throw it else emptyList() }

    private suspend fun listens(objectId: String): Boolean = runCatching {
        val l = session.send("DOMDebugger.getEventListeners", buildJsonObject { put("objectId", objectId) }, timeoutMs = callTimeoutMs)["listeners"] as? JsonArray
        l.orEmpty().any { (it as? JsonObject)?.str("type") in POINTER_EVENTS }
    }.getOrElse { if (it is CancellationException) throw it else false }

    /**
     * 取框并做命中检测(点取框在 [clip] 里可见部分的中心)。坐标按顶层视口理解没命中、而它在框架里时,
     * 再按「框架内坐标 + 框架内容区左上角」试一次:不同版本的内核对框架里节点给的坐标系可能不同。
     */
    suspend fun place(anchor: Anchor, clip: ElementFusion.Box? = null): Placed? {
        val box = boxOf(anchor.backendId) ?: return null
        val at = visibleCenter(box, clip)
        if (anchor.hits.isEmpty()) return Placed(box, null, at?.first ?: box.cx, at?.second ?: box.cy)
        val hit = at?.let { hitAt(it) }
        if (at != null && hit != null && hit in anchor.hits) return Placed(box, true, at.first, at.second)
        val owner = anchor.ownerId?.let { boxOf(it, content = true) }
        if (owner != null) {
            val shifted = ElementFusion.Box(box.l + owner.l, box.t + owner.t, box.r + owner.l, box.b + owner.t)
            val at2 = visibleCenter(shifted, clip)
            val again = at2?.let { hitAt(it) }
            if (at2 != null && again != null && again in anchor.hits) return Placed(shifted, true, at2.first, at2.second)
        }
        return Placed(box, if (hit == null) null else false, at?.first ?: box.cx, at?.second ?: box.cy)
    }

    /**
     * 点之前重新找只有无障碍树有的元素:先滚进视口(DOM 命令,不是滚轮),再取框;
     * 在框架里的,点取框在框架内容区里可见部分的中心。元素已不在为 null。
     */
    suspend fun locate(anchor: Anchor): Placed? = withTimeoutOrNull(LOCATE_TIMEOUT_MS) {
        runCatching { session.send("DOM.scrollIntoViewIfNeeded", buildJsonObject { put("backendNodeId", anchor.backendId) }, timeoutMs = LOCATE_TIMEOUT_MS) }
            .onFailure { if (it is CancellationException) throw it }
        runCatching { place(anchor, anchor.ownerId?.let { boxOf(it, content = true) }) }.getOrElse { if (it is CancellationException) throw it else null }
    }

    private suspend fun boxOf(backendId: Int, content: Boolean = false): ElementFusion.Box? = runCatching {
        val model = session.send("DOM.getBoxModel", buildJsonObject { put("backendNodeId", backendId) }, timeoutMs = callTimeoutMs)["model"] as? JsonObject
        quadBox(model?.get(if (content) "content" else "border") as? JsonArray)
    }.getOrElse { if (it is CancellationException) throw it else null }

    private fun visibleCenter(box: ElementFusion.Box, clip: ElementFusion.Box?): Pair<Double, Double>? {
        val l = max(box.l, clip?.l ?: box.l)
        val t = max(box.t, clip?.t ?: box.t)
        val r = min(box.r, clip?.r ?: box.r)
        val b = min(box.b, clip?.b ?: box.b)
        return if (r <= l || b <= t) null else (l + r) / 2 to (t + b) / 2
    }

    private suspend fun hitAt(at: Pair<Double, Double>): Int? {
        return runCatching {
            session.send(
                "DOM.getNodeForLocation",
                buildJsonObject {
                    put("x", at.first.roundToInt())
                    put("y", at.second.roundToInt())
                    put("includeUserAgentShadowDOM", false)
                },
                timeoutMs = callTimeoutMs,
            ).int("backendNodeId")
        }.getOrElse { if (it is CancellationException) throw it else null }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 1_500L
        const val LOCATE_TIMEOUT_MS = 1_000L
        private const val RELEASE_TIMEOUT_MS = 300L
        /** 除主文档外最多读这么多个框架的无障碍树。 */
        const val MAX_FRAMES = 12
        private const val GROUP = "quro-elements"
        private val POINTER_EVENTS = setOf("click", "mousedown", "mouseup", "pointerdown", "pointerup", "touchstart", "touchend")

        /** `Page.getFrameTree` 的 frameTree → 先序的帧列表,主文档在最前。 */
        fun parseFrameTree(tree: JsonObject): List<ElementFusion.Frame> {
            val out = ArrayList<ElementFusion.Frame>()
            fun walk(t: JsonObject, parent: String?, depth: Int) {
                if (depth > 32) return
                val f = t["frame"] as? JsonObject ?: return
                val id = f.str("id") ?: return
                out += ElementFusion.Frame(id, f.str("parentId") ?: parent)
                (t["childFrames"] as? JsonArray).orEmpty().forEach { c -> (c as? JsonObject)?.let { walk(it, id, depth + 1) } }
            }
            walk(tree, null, 0)
            return out
        }

        /** `Accessibility.getFullAXTree` 的 nodes → [ElementFusion.AxNode],order 按 childIds 深度优先重排(不依赖返回顺序)。 */
        fun parseAxTree(nodes: List<JsonObject>, frameId: String): List<ElementFusion.AxNode> {
            val byId = LinkedHashMap<String, JsonObject>()
            for (n in nodes) n.str("nodeId")?.let { byId[it] = n }
            val parentOf = HashMap<String, String>()
            for ((id, n) in byId) {
                n.str("parentId")?.let { parentOf[id] = it }
                (n["childIds"] as? JsonArray).orEmpty().forEach { c -> (c as? JsonPrimitive)?.contentOrNull?.let { parentOf.putIfAbsent(it, id) } }
            }
            val order = LinkedHashMap<String, Int>()
            val roots = byId.keys.filter { parentOf[it]?.let(byId::containsKey) != true }
            for (root in roots) {
                val stack = ArrayDeque(listOf(root))
                while (stack.isNotEmpty()) {
                    val id = stack.removeLast()
                    if (id in order || id !in byId) continue
                    order[id] = order.size
                    val kids = (byId.getValue(id)["childIds"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    for (k in kids.asReversed()) stack.addLast(k)
                }
            }
            return order.keys.map { id ->
                val n = byId.getValue(id)
                ElementFusion.AxNode(
                    id = id,
                    parent = parentOf[id],
                    frameId = n.str("frameId") ?: frameId,
                    order = order.getValue(id),
                    backendId = n.int("backendDOMNodeId"),
                    role = ((n["role"] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    name = ((n["name"] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    ignored = (n["ignored"] as? JsonPrimitive)?.booleanOrNull == true,
                    states = states(n["properties"] as? JsonArray),
                )
            }
        }

        // 布尔为真、三态为 true/mixed、记号不是 false 的属性名
        private fun states(props: JsonArray?): Set<String> {
            val out = HashSet<String>()
            for (p in props.orEmpty()) {
                val o = p as? JsonObject ?: continue
                val name = o.str("name") ?: continue
                val v = (o["value"] as? JsonObject)?.get("value") as? JsonPrimitive ?: continue
                val on = v.booleanOrNull ?: v.contentOrNull?.let { it.isNotEmpty() && it != "false" && it != "undefined" } ?: false
                if (on) out += name
            }
            return out
        }

        /** 四个顶点(x1,y1,…,x4,y4)→ 外接矩形。 */
        fun quadBox(q: JsonArray?): ElementFusion.Box? {
            val v = q?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: return null
            if (v.size < 8) return null
            val xs = listOf(v[0], v[2], v[4], v[6])
            val ys = listOf(v[1], v[3], v[5], v[7])
            return ElementFusion.Box(xs.min(), ys.min(), xs.max(), ys.max())
        }

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull

        private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000
    }
}
