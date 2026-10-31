package com.ai.assistance.quro.core.mika.cap.web

import com.ai.assistance.quro.core.mika.uistate.UiElement
import com.ai.assistance.quro.core.mika.uistate.UiStates
import com.ai.assistance.quro.core.mika.uistate.normalize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 元素融合:页面脚本的元素表(看得见、没被盖住的才列)与 DevTools 无障碍树按 backendNodeId 合成一份元素列表。纯函数。
 * - 两边都有:名字、角色、禁用/焦点等状态取无障碍树,位置、可见、遮挡取页面脚本。无障碍树里被忽略的节点不采信。
 *   输入框、下拉框的名字里带着已填的值,保留页面脚本的;文件控件、内嵌页面占位的角色保留页面脚本的。
 * - 只有无障碍树有:只收可交互的角色。页面脚本读不到的跨源框架(它只列出一个「内嵌页面」占位)里的,
 *   框剪到占位的可见范围内,插在占位后面;页面脚本读得到的文档里的(如封闭的 shadow root),要命中检测确认在最上面才收。
 * - 只有页面脚本有:原样保留;元素自己挂着点击类监听器的纯文字/图片升为可点。
 * 顺序:文档顺序。超出 [Input.budget] 时先丢没信息量的,再丢纯文字,最后才丢可交互的,丢掉的个数记在 omitted。
 * 编号重新按顺序排成 @e1…;[Result.pageRefs] 是新编号 → 页面脚本编号表里的编号(只有无障碍树有的元素没有)。
 */
object ElementFusion {
    const val DEFAULT_BUDGET = 400
    /** 只有无障碍树有的元素最多补这么多个(每个要一次取框、一次命中检测)。 */
    const val MAX_CANDIDATES = 60
    /** 最多给这么多个页面脚本元素查监听器。 */
    const val MAX_LISTENER_PROBES = 40
    private const val NAME_CHARS = 80

    /**
     * 无障碍树的一个节点。[id]/[parent] 是本帧树里的节点号,[order] 是本帧树深度优先的先后;
     * [states] 是为真的布尔/三态属性名(disabled、focused、readonly、editable、checked…)。
     */
    data class AxNode(
        val id: String,
        val parent: String?,
        val frameId: String,
        val order: Int,
        val backendId: Int?,
        val role: String,
        val name: String,
        val ignored: Boolean = false,
        val states: Set<String> = emptySet(),
    )

    /** 顶层视口 CSS 像素的矩形。 */
    data class Box(val l: Double, val t: Double, val r: Double, val b: Double) {
        val cx get() = (l + r) / 2
        val cy get() = (t + b) / 2
    }

    data class Frame(val id: String, val parent: String?)

    enum class Origin { PAGE, BOTH, AX }

    /**
     * [page] 是 PageTree 的结果(视图像素),[pageIds] 与它按下标对齐;[owners] 是框架元素的 backendNodeId → 它里面文档的 frameId。
     * [boxes]/[onTop] 给只有无障碍树有的候选节点(按 backendNodeId):框(已换成顶层视口坐标)与命中检测结果。
     * [listeners] 是挂着点击类监听器的元素。[scale] 是 CSS 像素 → 视图像素的倍数。
     */
    class Input(
        val page: List<UiElement>,
        val pageIds: List<Int?>,
        val owners: Map<Int, String> = emptyMap(),
        val frames: List<Frame> = emptyList(),
        val ax: List<AxNode> = emptyList(),
        val boxes: Map<Int, Box> = emptyMap(),
        val onTop: Map<Int, Boolean> = emptyMap(),
        val listeners: Set<Int> = emptySet(),
        val scale: Double = 1.0,
        val width: Int,
        val height: Int,
        val budget: Int = DEFAULT_BUDGET,
    )

    class Result(
        val elements: List<UiElement>,
        val pageRefs: Map<String, String>,
        val backendIds: Map<String, Int>,
        val origins: Map<String, Origin>,
        /** 只有无障碍树有的元素所在框架的占位元素(新编号 → 占位的 backendNodeId);在主文档的为空。 */
        val frameOwners: Map<String, Int>,
        val omitted: Int,
    )

    /** 一个候选节点:[placeholder] 是它所在跨源框架在页面脚本元素表里的占位下标,在页面脚本读得到的文档里为 null。 */
    class Candidate(val node: AxNode, val placeholder: Int?, val ownerId: Int?)

    // ---------- 角色 ----------

    private val CLICK_ROLES = setOf(
        "link", "button", "checkbox", "radio", "combobox", "switch", "tab", "menuitem", "option", "slider",
        "menuitemcheckbox", "menuitemradio", "treeitem", "listbox",
    )
    private val EDIT_ROLES = setOf("textbox")
    /** 页面脚本给的这些角色不让无障碍树改:file 是上传要认的,frame 是跨源框架的占位。 */
    private val KEEP_PAGE_ROLES = setOf("file", "frame")
    private val NON_INTERACTIVE_ROLES = setOf("heading", "image")

    /** 无障碍树的角色 → 和页面脚本同一套词;没有语义的(generic、StaticText…)为 null。 */
    fun role(ax: String): String? = when (ax) {
        "searchbox", "spinbutton" -> "textbox"
        "img" -> "image"
        "PopUpButton" -> "combobox"
        "DisclosureTriangle", "ToggleButton" -> "button"
        "Iframe", "IframePresentational" -> "frame"
        in CLICK_ROLES, in EDIT_ROLES, in NON_INTERACTIVE_ROLES -> ax
        else -> null
    }

    private fun interactive(role: String?) = role != null && (role in CLICK_ROLES || role in EDIT_ROLES)

    // ---------- 第一步:要补框的候选 ----------

    /** 只有无障碍树有、可交互、值得补的节点;页面脚本读不到的跨源框架里的排前面。 */
    fun candidates(input: Input): List<Candidate> {
        val matched = input.pageIds.filterNotNull().toSet()
        val byFrame = input.ax.groupBy { it.frameId }.mapValues { (_, l) -> l.associateBy { it.id } }
        val pageIndex = input.pageIds.withIndex().filter { it.value != null }.associate { it.value!! to it.index }
        val behind = placeholders(input)
        val out = mutableListOf<Candidate>()
        for (n in input.ax) {
            val id = n.backendId ?: continue
            if (n.ignored || id in matched || !interactive(role(n.role))) continue
            val ph = behind[n.frameId]
            if (ph == null) {
                // 读得到的文档里,外层有页面脚本已列出的可交互元素:页面脚本看见了外层却没列它,多半被盖住或只是外层的一部分
                val nodes = byFrame[n.frameId].orEmpty()
                var p = n.parent?.let(nodes::get)
                var covered = false
                while (p != null) {
                    val pi = p.backendId?.let(pageIndex::get)
                    if (pi != null && input.page[pi].let { it.clickable || it.editable }) { covered = true; break }
                    p = p.parent?.let(nodes::get)
                }
                if (covered) continue
            }
            out += Candidate(n, ph?.first, ph?.second)
        }
        return out.sortedBy { if (it.placeholder != null) 0 else 1 }.take(MAX_CANDIDATES)
    }

    /** frameId → (占位在页面脚本元素表里的下标, 占位的 backendNodeId):占位框架本身及它里面套的框架。 */
    private fun placeholders(input: Input): Map<String, Pair<Int, Int>> {
        val direct = HashMap<String, Pair<Int, Int>>()
        input.pageIds.forEachIndexed { i, id ->
            if (id != null && input.page[i].role == "frame") input.owners[id]?.let { direct[it] = i to id }
        }
        if (direct.isEmpty()) return direct
        val parent = input.frames.associate { it.id to it.parent }
        val out = HashMap<String, Pair<Int, Int>>()
        for (f in input.frames.map { it.id } + direct.keys) {
            var cur: String? = f
            var hops = 0
            while (cur != null && hops++ < 32) {
                direct[cur]?.let { out[f] = it }
                if (out.containsKey(f)) break
                cur = parent[cur]
            }
        }
        return out
    }

    /** 一个节点在本帧无障碍树里的子孙(含自己)对应的 backendNodeId:命中检测落在这些节点上都算点到它。 */
    fun subtreeIds(ax: List<AxNode>, node: AxNode): Set<Int> {
        val children = ax.filter { it.frameId == node.frameId }.groupBy { it.parent }
        val out = HashSet<Int>()
        val stack = ArrayDeque(listOf(node))
        while (stack.isNotEmpty() && out.size < 500) {
            val n = stack.removeLast()
            n.backendId?.let(out::add)
            children[n.id]?.let(stack::addAll)
        }
        return out
    }

    /** 候选节点可见的范围(CSS 像素):跨源框架里的是占位的可见部分,其余是视口。命中检测点取框与它相交部分的中心。 */
    fun clip(input: Input, c: Candidate): Box {
        val k = input.scale.takeIf { it > 0 } ?: 1.0
        val r = c.placeholder?.let { rectOf(input.page[it], input.width, input.height) }
            ?: Rect(0.0, 0.0, input.width.toDouble(), input.height.toDouble())
        return Box(r.l / k, r.t / k, r.r / k, r.b / k)
    }

    /** 要查监听器的页面脚本元素下标:不可点的纯文字、图片,且无障碍树也没说它可交互。 */
    fun listenerProbes(input: Input): List<Int> {
        val axById = axByBackend(input.ax)
        return input.page.indices.filter { i ->
            val e = input.page[i]
            val id = input.pageIds.getOrNull(i) ?: return@filter false
            !e.clickable && !e.editable && (e.role == "text" || e.role == "image") &&
                !interactive(axById[id]?.takeIf { !it.ignored }?.let { role(it.role) })
        }.take(MAX_LISTENER_PROBES)
    }

    private fun axByBackend(ax: List<AxNode>): Map<Int, AxNode> {
        val m = HashMap<Int, AxNode>()
        for (n in ax) {
            val id = n.backendId ?: continue
            val old = m[id]
            if (old == null || (old.ignored && !n.ignored)) m[id] = n
        }
        return m
    }

    // ---------- 第二步:合并 ----------

    fun fuse(input: Input): Result {
        val axById = axByBackend(input.ax)
        val merged = input.page.mapIndexed { i, e ->
            val id = input.pageIds.getOrNull(i)
            Item(merge(e, id?.let(axById::get), id != null && id in input.listeners), id, e.ref, if (id?.let(axById::get) != null) Origin.BOTH else Origin.PAGE, null)
        }
        val extra = axOnly(input)
        // 插入:跨源框架里的跟在占位后面;读得到的文档里的跟在同帧里无障碍树顺序在它前面的最后一个已对上的元素后面
        val pageOrder = HashMap<Int, Pair<String, Int>>()
        input.pageIds.forEachIndexed { i, id -> id?.let(axById::get)?.let { pageOrder[i] = it.frameId to it.order } }
        val after = extra.groupBy { c ->
            c.placeholder ?: pageOrder.entries.filter { it.value.first == c.node.frameId && it.value.second < c.node.order }
                .maxByOrNull { it.value.second }?.key ?: (input.page.size - 1)
        }
        val all = ArrayList<Item>()
        if (input.page.isEmpty()) extra.forEach { all += it.item }
        merged.forEachIndexed { i, item ->
            all += item
            after[i]?.sortedBy { it.node.order }?.forEach { all += it.item }
        }
        val kept = budget(all, input.budget)
        val elements = ArrayList<UiElement>(kept.size)
        val pageRefs = HashMap<String, String>()
        val ids = HashMap<String, Int>()
        val origins = HashMap<String, Origin>()
        val owners = HashMap<String, Int>()
        kept.forEachIndexed { i, it ->
            val ref = "@e${i + 1}"
            elements += it.element.copy(ref = ref)
            it.pageRef?.let { r -> pageRefs[ref] = r }
            it.backendId?.let { b -> ids[ref] = b }
            it.ownerId?.let { o -> owners[ref] = o }
            origins[ref] = it.origin
        }
        return Result(elements, pageRefs, ids, origins, owners, all.size - kept.size)
    }

    private class Item(val element: UiElement, val backendId: Int?, val pageRef: String?, val origin: Origin, val ownerId: Int?)

    private class Extra(val node: AxNode, val placeholder: Int?, val item: Item)

    private fun merge(e: UiElement, ax: AxNode?, listener: Boolean): UiElement {
        val heard = listener && !e.clickable && !e.editable && (e.role == "text" || e.role == "image")
        val base = if (heard) e.copy(role = if (e.role == "text") "button" else e.role, clickable = true) else e
        if (ax == null || ax.ignored) return base
        val axRole = role(ax.role)
        val roleOut = when {
            base.role in KEEP_PAGE_ROLES || axRole == null || axRole == "frame" -> base.role
            interactive(axRole) -> axRole
            base.role == "text" -> axRole
            else -> base.role
        }
        val axName = cut(ax.name)
        // 输入框、下拉框、滑块的名字里有值(密码框只说填了几位):保留页面脚本的
        val valued = base.editable || base.role in setOf("textbox", "combobox", "slider") || roleOut in EDIT_ROLES
        val name = when {
            valued -> base.name.ifBlank { axName }
            axName.isNotBlank() -> axName
            else -> base.name
        }
        val disabled = "disabled" in ax.states
        val readonly = "readonly" in ax.states
        return base.copy(
            role = roleOut,
            name = name,
            clickable = !disabled && (base.clickable || roleOut in CLICK_ROLES),
            editable = !disabled && !readonly && (base.editable || (roleOut in EDIT_ROLES && "editable" in ax.states)),
            focused = base.focused || "focused" in ax.states,
            states = base.states + ax.states.filter { it in UiStates.ALL },
        )
    }

    private fun axOnly(input: Input): List<Extra> {
        val w = input.width
        val h = input.height
        val view = Rect(0.0, 0.0, w.toDouble(), h.toDouble())
        val pageRects = input.page.map { rectOf(it, w, h) }
        val out = ArrayList<Extra>()
        val keptRects = ArrayList<Pair<Extra, Rect>>()
        val nodesByFrame = input.ax.groupBy { it.frameId }.mapValues { (_, l) -> l.associateBy { it.id } }
        for (c in candidates(input)) {
            val id = c.node.backendId ?: continue
            val box = input.boxes[id] ?: continue
            val top = input.onTop[id]
            // 读得到的文档里:页面脚本没列它,要命中检测明说在最上面;跨源框架里:命中检测说被盖住才不收
            if (c.placeholder == null && top != true) continue
            if (c.placeholder != null && top == false) continue
            val clip = c.placeholder?.let { pageRects[it] } ?: view
            val raw = Rect(box.l * input.scale, box.t * input.scale, box.r * input.scale, box.b * input.scale)
            val vis = raw.intersect(clip)?.intersect(view) ?: continue
            if (vis.r - vis.l < 1 || vis.b - vis.t < 1) continue
            val roleOut = role(c.node.role) ?: continue
            val name = cut(c.node.name)
            // 页面脚本那边没对上节点号、但其实是同一个元素(同名或可交互且大半重合):不重复列
            val dup = input.page.indices.any { i ->
                val e = input.page[i]
                if (input.pageIds[i] != null || e.role == "frame") return@any false
                val r = pageRects[i]
                r.contains(vis.cx, vis.cy) && (e.name.equals(name, ignoreCase = true) || ((e.clickable || e.editable) && r.iou(vis) > 0.5))
            }
            if (dup) continue
            // 无障碍树里套着的同名可交互节点(链接里的按钮)、同角色同名几乎同框的:只留外层/第一个
            val nested = keptRects.any { (k, r) ->
                val sameName = k.item.element.name.equals(name, ignoreCase = true)
                (sameName || name.isBlank()) && isAncestor(nodesByFrame[c.node.frameId].orEmpty(), k.node, c.node) ||
                    (sameName && k.item.element.role == roleOut && r.iou(vis) > 0.8)
            }
            if (nested) continue
            val disabled = "disabled" in c.node.states
            val l = vis.l.roundToInt()
            val t = vis.t.roundToInt()
            val r = vis.r.roundToInt()
            val b = vis.b.roundToInt()
            val el = UiElement(
                ref = "",
                role = roleOut,
                name = name,
                centerX = ((l + r) / 2).coerceIn(0, max(0, w - 1)),
                centerY = ((t + b) / 2).coerceIn(0, max(0, h - 1)),
                bounds = listOf(normalize(l, w), normalize(t, h), normalize(r, w), normalize(b, h)),
                resourceId = null,
                clickable = !disabled && roleOut in CLICK_ROLES,
                editable = !disabled && "readonly" !in c.node.states && roleOut in EDIT_ROLES,
                scrollable = false,
                focused = "focused" in c.node.states,
                states = c.node.states.filter { it in UiStates.ALL }.toSet(),
                // 只有无障碍树看得到的输入框(跨源框架、读屏脚本没列的):看不出是不是密码框,按密码框算
                secret = !disabled && "readonly" !in c.node.states && roleOut in EDIT_ROLES,
            )
            val x = Extra(c.node, c.placeholder, Item(el, id, null, Origin.AX, c.ownerId))
            out += x
            keptRects += x to vis
        }
        return out
    }

    private fun isAncestor(nodes: Map<String, AxNode>, outer: AxNode, inner: AxNode): Boolean {
        if (outer.frameId != inner.frameId) return false
        var p = inner.parent?.let(nodes::get)
        var hops = 0
        while (p != null && hops++ < 256) {
            if (p.id == outer.id) return true
            p = p.parent?.let(nodes::get)
        }
        return false
    }

    /** 超预算时按「没信息量 → 纯文字等不可交互 → 可交互」的次序、各自从后往前丢,保留的仍按原顺序。 */
    private fun budget(all: List<Item>, budget: Int): List<Item> {
        if (all.size <= budget) return all
        fun tier(e: UiElement) = when {
            !e.meaningful -> 0
            !(e.clickable || e.editable || e.scrollable) -> 1
            else -> 2
        }
        val drop = HashSet<Int>()
        var need = all.size - budget.coerceAtLeast(0)
        for (t in 0..2) {
            for (i in all.indices.reversed()) {
                if (need == 0) break
                if (tier(all[i].element) == t) { drop += i; need-- }
            }
        }
        return all.filterIndexed { i, _ -> i !in drop }
    }

    private fun cut(s: String): String {
        val c = s.replace(Regex("\\s+"), " ").trim()
        return if (c.length > NAME_CHARS) c.substring(0, NAME_CHARS) else c
    }

    private data class Rect(val l: Double, val t: Double, val r: Double, val b: Double) {
        val cx get() = (l + r) / 2
        val cy get() = (t + b) / 2
        fun intersect(o: Rect): Rect? {
            val nl = max(l, o.l); val nt = max(t, o.t); val nr = min(r, o.r); val nb = min(b, o.b)
            return if (nr > nl && nb > nt) Rect(nl, nt, nr, nb) else null
        }
        fun contains(x: Double, y: Double) = x >= l && x <= r && y >= t && y <= b
        private fun area() = max(0.0, r - l) * max(0.0, b - t)
        fun iou(o: Rect): Double {
            val i = intersect(o)?.area() ?: return 0.0
            val u = area() + o.area() - i
            return if (u <= 0) 0.0 else i / u
        }
    }

    // 归一的 0–999 换回视图像素;误差不到一个像素
    private fun rectOf(e: UiElement, w: Int, h: Int): Rect {
        fun dx(v: Int) = v * (w - 1).coerceAtLeast(1) / 999.0
        fun dy(v: Int) = v * (h - 1).coerceAtLeast(1) / 999.0
        return Rect(dx(e.bounds[0]), dy(e.bounds[1]), dx(e.bounds[2]), dy(e.bounds[3]))
    }
}
