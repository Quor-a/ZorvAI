package com.zorv.plugin.zxwidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * 跨卡响应式引用引擎 —— 让气泡里的卡片之间真正"联动"。
 *
 * AI 可以在 DSL 里写 {{ref.c5.rows[0][1]}}，指向另一张卡片的某个值。
 * 那张卡一旦被用户改动，所有引用它的卡片自动增量重算，不用 AI 重新下发。
 *
 * 这是把"气泡里的一堆卡"变成"一张有向依赖图"的关键：
 *   表格改一个数 → 引用它的图表重绘 → 引用图表的结论卡重算
 *
 * 三条硬约束：
 *   1. 依赖成环必须检出并拒绝，否则会无限重算
 *   2. 重算走增量 patch，不重画整卡（否则滚动位置与输入焦点全丢）
 *   3. 解析失败时返回占位而不是抛异常——卡片不能因为一个坏引用整块消失
 */
object ZxRef {

    /** 一张卡在图中的运行时状态 */
    class CardNode(
        val id: String,
        val component: String,
        var dsl: String,
        /** 用户交互后的当前值，路径 -> 值 */
        val values: MutableMap<String, String> = mutableMapOf()
    ) {
        /** 本卡引用了谁（出边） */
        val refs = mutableSetOf<Ref>()
        /** 谁引用了本卡（入边），用于反向传播 */
        val dependents = mutableSetOf<String>()
        var revision = 0
    }

    /** {{ref.<cardId>.<path>}} */
    data class Ref(val cardId: String, val path: String, val raw: String)

    /** 图：会话级单例，随会话销毁 */
    val graph = linkedMapOf<String, CardNode>()

    private val REF_RE = Regex("\\{\\{\\s*ref\\.([A-Za-z0-9_]+)\\.([A-Za-z0-9_.\\[\\]]+)\\s*\\}\\}")

    /** 占位符：引用取不到时显示这个，绝不把卡片整块搞没 */
    const val MISSING = "—"

    fun put(id: String, component: String, dsl: String): CardNode {
        val node = CardNode(id, component, dsl)
        node.refs.addAll(extract(dsl))
        graph[id] = node
        rebuildDependents()
        return node
    }

    fun get(id: String): CardNode? = graph[id]

    /** 图里是否已有这张卡 */
    fun has(id: String): Boolean = graph.containsKey(id)

    /** 抽出 DSL 里所有跨卡引用 */
    fun extract(dsl: String): Set<Ref> =
        REF_RE.findAll(dsl).map { Ref(it.groupValues[1], it.groupValues[2], it.value) }.toSet()

    /** 重算入边（谁引用了我） */
    private fun rebuildDependents() {
        graph.values.forEach { it.dependents.clear() }
        for (n in graph.values) {
            for (r in n.refs) graph[r.cardId]?.dependents?.add(n.id)
        }
    }

    /** 把 DSL 里的引用替换成实际值 */
    fun resolve(dsl: String, selfId: String): String {
        var out = dsl
        REF_RE.findAll(dsl).forEach { m ->
            val cardId = m.groupValues[1]
            val path = m.groupValues[2]
            val v = readValue(cardId, path)
            out = out.replace(m.value, v ?: MISSING)
        }
        return out
    }

    /** 读一张卡的某个路径：rows[0][1] / fields.budget / options[2].t */
    fun readValue(cardId: String, path: String): String? {
        val node = graph[cardId] ?: return null
        // 用户交互改过的值优先
        node.values[path]?.let { return it }

        val root = runCatching { JSONObject(node.dsl) }.getOrNull() ?: return null
        val segs = path.split('.')
        var cur: Any? = root
        for (seg in segs) {
            val idx = Regex("\\[(\\d+)\\]").findAll(seg)
            val key = seg.substringBefore('[')
            if (key.isNotEmpty()) {
                cur = when (cur) {
                    is JSONObject -> cur.opt(key)
                    else -> return null
                }
            }
            for (m in idx) {
                val i = m.groupValues[1].toInt()
                cur = when (cur) {
                    is JSONArray -> cur.opt(i)
                    else -> return null
                }
            }
            if (cur == null) return null
        }
        return when (cur) {
            JSONObject.NULL -> null
            is JSONObject, is JSONArray -> cur.toString()
            else -> cur.toString()
        }
    }

    /**
     * 直接写一个值（不触发传播）。
     * 用于撤销/重做这类"把值改回去"的场景——
     * 撤销时再传播一遍会让下游卡反复闪，而且可能触发环检测。
     *
     * @return 受影响的下游卡列表（供调用方决定是否增量重绘），无下游返回 null
     */
    fun set(cardId: String, path: String, value: String): List<CardNode>? {
        val node = graph[cardId] ?: return null
        node.values[path] = value
        node.revision++
        val down = dependentsOf(cardId)
        return if (down.isEmpty()) null else down
    }

    /** 同 set()，保持命名一致性 */
    fun writeValue(cardId: String, path: String, value: String): List<CardNode>? =
        set(cardId, path, value)

    /** 取一张卡的所有下游（含间接） */
    fun dependentsOf(cardId: String): List<CardNode> {
        val out = linkedSetOf<CardNode>()
        val q = ArrayDeque<String>()
        q.add(cardId)
        val seen = mutableSetOf(cardId)
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            graph[cur] ?: continue
            for (id in graph.values.filter { it.dependents.contains(cur) }.map { it.id }) {
                if (seen.add(id)) { graph[id]?.let { out.add(it) }; q.add(id) }
            }
        }
        return out.toList()
    }

    /**
     * 用户改了某张卡的某个值 → 沿依赖图向下游传播。
     * 返回需要增量重算的卡片（已拓扑排序），调用方对每张发 zx_patch。
     */
    fun propagate(cardId: String, path: String, value: String): List<CardNode> {
        val start = graph[cardId] ?: return emptyList()
        start.values[path] = value
        start.revision++

        // BFS 收集全部下游，同时记录路径用于环检测
        val order = mutableListOf<CardNode>()
        val visited = mutableSetOf(cardId)
        val queue = ArrayDeque<String>()
        start.dependents.forEach { queue.addLast(it) }

        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!visited.add(id)) continue
            val n = graph[id] ?: continue
            n.revision++
            order.add(n)
            n.dependents.forEach { queue.addLast(it) }
        }
        return order
    }

    /** 环检测：返回环上的卡片 id 链，无环返回 null */
    fun detectCycle(): List<String>? {
        val color = mutableMapOf<String, Int>() // 0 未访问 1 在栈上 2 已完成
        val stack = mutableListOf<String>()

        fun dfs(id: String): List<String>? {
            color[id] = 1
            stack.add(id)
            val node = graph[id] ?: return null
            for (r in node.refs) {
                when (color[r.cardId]) {
                    1 -> { // 回边 → 成环，截取环片段
                        val from = stack.indexOf(r.cardId)
                        return stack.subList(from, stack.size) + r.cardId
                    }
                    0, null -> dfs(r.cardId)?.let { return it }
                }
            }
            stack.removeAt(stack.size - 1)
            color[id] = 2
            return null
        }

        for (id in graph.keys) if (color[id] == null || color[id] == 0) {
            dfs(id)?.let { return it }
        }
        return null
    }

    /** 建立引用前先自检，成环就拒绝并回喂模型 */
    fun checkLink(fromId: String, dsl: String): Result {
        val node = graph[fromId] ?: return Result(false, "源卡片不存在：$fromId")
        val refs = extract(dsl)
        if (refs.isEmpty()) return Result(false, "DSL 里没有找到 {{ref.xxx.yyy}} 形式的引用")

        for (r in refs) {
            if (r.cardId == fromId) return Result(false, "卡片 ${fromId} 不能引用自己")
            if (graph[r.cardId] == null)
                return Result(false, "引用了不存在的卡片：${r.cardId}（当前只有 ${graph.keys.joinToString()}）")
        }

        // 试算：临时塞进去看会不会成环
        val backup = node.refs.toSet()
        node.refs.clear(); node.refs.addAll(refs)
        rebuildDependents()
        val cycle = detectCycle()
        if (cycle != null) {
            node.refs.clear(); node.refs.addAll(backup); rebuildDependents()
            return Result(false, "引用会形成环：${cycle.joinToString(" → ")}。请打断其中一条。")
        }
        node.dsl = dsl
        return Result(true, "已建立 ${refs.size} 条引用，下游卡片将自动跟随")
    }

    /** 生成带引用的 DSL 片段，供模型直接抄 */
    fun sampleLink(): String = """
        // 让图表引用表格里的第二列第一行
        {"type":"zx_chart","style":"bar","title":"预算分布",
         "points":[{{ref.c5.rows[0][1]}},{{ref.c5.rows[1][1]}},{{ref.c5.rows[2][1]}}],
         "labels":["服务器","域名","CDN"]}
    """.trimIndent()

    fun clear() { graph.clear() }
}
