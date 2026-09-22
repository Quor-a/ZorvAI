package com.yuanbao.miniapp.render

/** Node kinds supported by the self-developed renderer. */
enum class NodeType { VIEW, TEXT, IMAGE, BUTTON, INPUT, SCROLL, ROOT }

/**
 * A node in our own render tree. This has nothing to do with android.view.View:
 * layout is computed by FlexLayout and pixels are produced by our painters.
 */
class RenderNode(
    val id: Int,
    val type: NodeType,
    val tag: String = "view"
) {
    var style: Style = Style()
    var text: String = ""
    var parent: RenderNode? = null
    val children = ArrayList<RenderNode>()

    /** Attributes copied from the template (src, placeholder, value, disabled...). */
    val attributes = HashMap<String, String>()

    /** event name -> handler name declared in the page (e.g. "tap" -> "onTap"). */
    val events = HashMap<String, String>()

    /** Extra state for scroll containers / input. */
    var scrollTop: Float = 0f
    var scrollLeft: Float = 0f
    var contentHeight: Float = 0f
    var contentWidth: Float = 0f

    // ---- layout output ----
    var x: Float = 0f        // relative to parent content box
    var y: Float = 0f
    var width: Float = 0f
    var height: Float = 0f
    var absX: Float = 0f     // absolute on screen (refresh per layout pass)
    var absY: Float = 0f

    /** Text lines produced by TextLayout (only for NodeType.TEXT / BUTTON). */
    var lines: List<String> = emptyList()
    var lineHeightPx: Float = 0f

    fun addChild(node: RenderNode) {
        node.parent = this
        children.add(node)
    }

    fun removeAllChildren() {
        children.forEach { it.parent = null }
        children.clear()
    }

    fun hasEvents(): Boolean = events.isNotEmpty()

    fun containsPoint(px: Float, py: Float): Boolean =
        px >= absX && px <= absX + width && py >= absY && py <= absY + height

    /** Depth-first search for the node owning [id]. */
    fun findById(target: Int): RenderNode? {
        if (id == target) return this
        for (c in children) {
            val hit = c.findById(target)
            if (hit != null) return hit
        }
        return null
    }

    /** Deep copy used by the diffing path. */
    fun deepCopy(): RenderNode {
        val n = RenderNode(id, type, tag)
        n.style = style.copy()
        n.text = text
        n.attributes.putAll(attributes)
        n.events.putAll(events)
        n.scrollTop = scrollTop
        for (c in children) n.addChild(c.deepCopy())
        return n
    }
}
