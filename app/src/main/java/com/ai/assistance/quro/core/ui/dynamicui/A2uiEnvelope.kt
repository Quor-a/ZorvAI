package com.ai.assistance.quro.core.ui.dynamicui

import org.json.JSONObject

/**
 * A2UI 第③层：JSONL 信封（流式传输格式）。
 *
 * 一条 A2UI 会话由若干「信封消息」组成，每个信封一行 JSON（JSON Lines）：
 *  - createSurface   ：新建一个界面，body 是一棵 quro-ui 节点树（字符串化的 JSON 对象）；
 *  - updateComponents：按 id 局部替换某些节点（结构微调，不动数据）；
 *  - updateDataModel ：下发/合并数据模型（结构不变、数据流动，配合第④层指针绑定）；
 *  - deleteSurface   ：销毁界面。
 *
 * 这与「模型输出永远是数据不是代码」一致：每条消息都是声明式数据，
 * 客户端翻译器（第⑤层）按消息增量更新原生控件。线上 MIME 约定 application/a2ui+json。
 */
sealed interface A2uiMessage {
    val surface: String

    data class CreateSurface(override val surface: String, val rootJson: String) : A2uiMessage
    data class UpdateComponents(override val surface: String, val components: Map<String, String>) : A2uiMessage
    data class UpdateDataModel(override val surface: String, val dataJson: String) : A2uiMessage
    data class DeleteSurface(override val surface: String) : A2uiMessage

    companion object {
        /** 解析一行 JSON 信封；非信封行返回 null（调用方应忽略）。 */
        fun parse(line: String): A2uiMessage? {
            val t = line.trim()
            if (!t.startsWith("{") || !t.contains("\"type\"")) return null
            return runCatching {
                val o = JSONObject(t)
                val type = o.optString("type", "").trim().lowercase()
                val surface = o.optString("surface", "default")
                when (type) {
                    "createsurface" -> {
                        val root = o.optJSONObject("root") ?: return null
                        CreateSurface(surface, root.toString())
                    }
                    "updatecomponents" -> {
                        val comps = o.optJSONObject("components") ?: return null
                        val map = LinkedHashMap<String, String>()
                        comps.keys().forEach { id ->
                            comps.optJSONObject(id)?.let { map[id] = it.toString() }
                        }
                        UpdateComponents(surface, map)
                    }
                    "updatedatamodel" -> {
                        val data = o.optJSONObject("data") ?: return null
                        UpdateDataModel(surface, data.toString())
                    }
                    "deletesurface" -> DeleteSurface(surface)
                    else -> null
                }
            }.getOrNull()
        }
    }
}

/** 一个 A2UI 界面实例：根节点树 + 数据模型。 */
data class A2uiSurface(
    var root: QuroUiNode? = null,
    val dataModel: MutableMap<String, Any?> = LinkedHashMap(),
)

/**
 * A2UI 会话：累积多条信封消息，维护若干 surface 的当前状态。
 * 客户端每收到一行就 [applyLine]，最终用 [primaryRoot] 取「最新一棵已校验+已绑定的原生节点树」交给渲染器。
 */
class A2uiSession {
    private val surfaces = LinkedHashMap<String, A2uiSurface>()
    private var lastSurface = "default"

    /** 应用一行信封；成功解析并返回 true，否则 false（忽略该行）。 */
    fun applyLine(line: String): Boolean {
        val msg = A2uiMessage.parse(line) ?: return false
        applyMessage(msg)
        return true
    }

    /** 应用整段 JSONL 文本，返回成功消费的信封行数。 */
    fun applyJsonl(text: String): Int {
        var n = 0
        text.lines().forEach { if (it.isNotBlank() && applyLine(it)) n++ }
        return n
    }

    fun applyMessage(msg: A2uiMessage) {
        when (msg) {
            is A2uiMessage.CreateSurface -> {
                lastSurface = msg.surface
                val node = runCatching { QuroUiDslParser.buildNode(JSONObject(msg.rootJson)) }.getOrNull()
                surfaces.getOrPut(msg.surface) { A2uiSurface() }.root = node
            }
            is A2uiMessage.UpdateComponents -> {
                lastSurface = msg.surface
                val surface = surfaces[msg.surface] ?: return
                val root = surface.root ?: return
                var newRoot = root
                msg.components.forEach { (id, json) ->
                    val repl = runCatching { QuroUiDslParser.buildNode(JSONObject(json)) }.getOrNull() ?: return@forEach
                    newRoot = replaceNodeById(newRoot, id, repl)
                }
                surface.root = newRoot
            }
            is A2uiMessage.UpdateDataModel -> {
                lastSurface = msg.surface
                val surface = surfaces.getOrPut(msg.surface) { A2uiSurface() }
                val model = runCatching { QuroUiPointer.toModel(JSONObject(msg.dataJson)) }.getOrNull() ?: return
                surface.dataModel.putAll(model)
            }
            is A2uiMessage.DeleteSurface -> surfaces.remove(msg.surface)
        }
    }

    /** 取最新一棵「已校验 + 已绑定指针」的原生节点树（最近一个 surface 优先）。 */
    fun primaryRoot(): QuroUiNode? {
        val surface = surfaces[lastSurface] ?: surfaces.values.firstOrNull() ?: return null
        return surface.root?.let { QuroUiPointer.bindTree(QuroUiCatalog.validate(it).root, surface.dataModel) }
    }

    /** 取指定 surface 的「已校验 + 已绑定指针」原生节点树。 */
    fun rootOf(surface: String): QuroUiNode? {
        val s = surfaces[surface] ?: return null
        return s.root?.let { QuroUiPointer.bindTree(QuroUiCatalog.validate(it).root, s.dataModel) }
    }

    /** 按 id 在树中找节点并替换为新节点（找不到则原样返回）。 */
    private fun replaceNodeById(root: QuroUiNode, id: String, replacement: QuroUiNode): QuroUiNode {
        if (root.id == id) return replacement
        return when (root) {
            is QuroColumnNode -> root.copy(children = root.children.map { replaceNodeById(it, id, replacement) })
            is QuroRowNode -> root.copy(children = root.children.map { replaceNodeById(it, id, replacement) })
            is QuroBoxNode -> root.copy(children = root.children.map { replaceNodeById(it, id, replacement) })
            is QuroCardNode -> root.copy(
                children = root.children.map { replaceNodeById(it, id, replacement) },
                onClick = root.onClick,
            )
            is QuroTabsNode -> root.copy(tabs = root.tabs.map {
                it.copy(node = it.node?.let { n -> replaceNodeById(n, id, replacement) })
            })
            is QuroListNode -> root.copy(itemTemplate = root.itemTemplate?.let { replaceNodeById(it, id, replacement) })
            else -> root
        }
    }
}
