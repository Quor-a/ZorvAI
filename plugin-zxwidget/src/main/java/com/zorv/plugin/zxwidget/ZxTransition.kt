package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 多卡同时更新时的补间与调度 —— 让一批卡的变化"看起来是同一个动作"。
 *
 * 为什么需要：联动（①）之后，一次用户操作会连带改好几张卡。
 * 如果每张卡各自按自己的时机重绘，用户看到的是：
 *   表变了 → 停顿 → 图变了 → 停顿 → 结论变了
 * 这不是"一件事"，是"三件事依次发生"。
 *
 * 所以要有调度：把这批更新当成一个**批次**，
 * 统一决定谁先谁后、间隔多少、谁并行。
 *
 * 三条规则：
 *
 *   一、**顺序按依赖**。上游先于下游——
 *       否则会看到"结论先变，表格后变"的因果倒置。
 *
 *   二、**总数超阈值就降级**。一次动 8 张卡如果每张都补间，
 *       用户看到的是满屏在动，反而看不清哪张变了。
 *       超过 4 张就缩短间隔、跳过位移只保留淡入。
 *
 *   三、**间隔有上限**。总时长超过 600ms 用户会觉得"卡"。
 *       所以间隔 = min(基础间隔, 预算 / 张数)。
 *
 * 另外：**失败不阻断批次**。
 * 一张卡更新失败不应该让后面几张都不动——
 * 失败的那张标出来就够了。
 */
object ZxTransition {

    enum class Kind {
        /** 数值/文本微调，最轻 */
        INPLACE,
        /** 结构变了（增删行/列） */
        RESHAPE,
        /** 整张卡换了组件 */
        REPLACE,
        /** 新卡进入 */
        ENTER,
        /** 卡片离场 */
        EXIT
    }

    data class Step(
        val cardId: String,
        val kind: Kind,
        /** 依赖的上游卡，决定顺序 */
        val after: List<String> = emptyList(),
        val delayMs: Int = 0,
        val ok: Boolean = true,
        val reason: String = ""
    )

    data class Batch(
        val id: String,
        val steps: List<Step>,
        val totalMs: Int,
        val degraded: Boolean,
        val t: Long = System.currentTimeMillis()
    ) {
        fun line(): String = buildString {
            append("%-6s %d 步 · 总时长 %dms".format(id, steps.size, totalMs))
            if (degraded) append(" · 已降级")
        }
    }

    private const val BASE_GAP = 90       // 单步基础间隔
    private const val BUDGET_MS = 600     // 总时长预算
    private const val DEGRADE_AT = 4      // 超过这个步数就降级

    private var seq = 0
    private val history = ArrayDeque<Batch>()
    private const val MAX_HIST = 20

    /**
     * 编排一个批次：拓扑排序 + 间隔分配 + 降级判定。
     */
    fun plan(steps: List<Step>): Batch {
        val n = steps.size
        val degraded = n > DEGRADE_AT

        // 一、拓扑排序：上游先于下游
        val byId = steps.associateBy { it.cardId }
        val ordered = mutableListOf<Step>()
        val placed = mutableSetOf<String>()
        var guard = 0
        while (ordered.size < n && guard++ <= n * 2) {
            val ready = steps.filter { s ->
                s.cardId !in placed && s.after.all { it in placed || it !in byId }
            }
            if (ready.isEmpty()) {
                // 有环——把剩下的按原顺序追加，不让批次卡死
                ordered.addAll(steps.filter { it.cardId !in placed })
                break
            }
            ordered.addAll(ready)
            ready.forEach { placed.add(it.cardId) }
        }

        // 二、间隔分配：总时长不超预算
        val gap = if (n <= 1) 0
                  else minOf(BASE_GAP, (BUDGET_MS - 120) / n)
        val withDelay = ordered.mapIndexed { i, s ->
            s.copy(delayMs = i * gap,
                   // 三、降级：跳过位移，只保留淡入
                   kind = if (degraded && s.kind == Kind.RESHAPE) Kind.INPLACE else s.kind)
        }

        val total = withDelay.lastOrNull()?.delayMs?.plus(duration(withDelay.last().kind, degraded)) ?: 0
        val b = Batch("B${++seq}", withDelay, total, degraded)
        history.addLast(b)
        if (history.size > MAX_HIST) history.removeFirst()
        return b
    }

    private fun duration(kind: Kind, degraded: Boolean): Int = when (kind) {
        Kind.INPLACE -> if (degraded) 90 else 140
        Kind.RESHAPE -> if (degraded) 90 else 200
        Kind.REPLACE -> 220
        Kind.ENTER   -> if (degraded) 100 else 180
        Kind.EXIT    -> 120
    }

    /**
     * 从引用图自动推导步骤：上游 → 下游，用户改的那张是源头。
     * 这样调用方不用手工声明依赖。
     */
    fun planFromRefs(sourceId: String, changed: Map<String, String>): Batch {
        val node = ZxRef.get(sourceId)
        val steps = mutableListOf<Step>()
        steps.add(Step(sourceId, Kind.INPLACE))
        if (node != null) {
            // 依赖这张卡的下游卡
            ZxRef.graph.forEach { (id, n) ->
                if (id != sourceId && n.refs.any { it.cardId == sourceId }) {
                    val kind = if (changed.containsKey(id)) Kind.INPLACE else Kind.INPLACE
                    steps.add(Step(id, kind, after = listOf(sourceId)))
                }
            }
        }
        changed.keys.forEach { id ->
            if (steps.none { it.cardId == id })
                steps.add(Step(id, Kind.INPLACE, after = listOf(sourceId)))
        }
        return plan(steps)
    }

    fun last(): Batch? = history.lastOrNull()

    fun report(): String = buildString {
        val b = history.lastOrNull()
        if (b == null) { appendLine("还没有编排过任何批次。"); return@buildString }
        appendLine(b.line())
        b.steps.forEach { s ->
            val arrow = if (s.after.isEmpty()) "起点" else "after ${s.after.joinToString()}"
            appendLine("  +${String.format("%3d", s.delayMs)}ms  %-14s %-8s %s"
                .format(s.cardId, s.kind.name, arrow))
        }
        appendLine()
        if (b.degraded) {
            appendLine("已降级：${b.steps.size} 步超过 $DEGRADE_AT 步——")
            appendLine("满屏都在动反而看不清哪张变了，所以缩短间隔并跳过位移。")
        }
        appendLine("规则：上游先于下游（否则会看到结论先变、表格后变的因果倒置）；")
        appendLine("总时长不超 ${BUDGET_MS}ms，超了用户会觉得卡。")
    }

    fun stats(): String = buildString {
        appendLine("历史 ${history.size} 个批次：")
        history.forEach { appendLine("  " + it.line()) }
        val avg = if (history.isEmpty()) 0 else history.sumOf { it.steps.size } / history.size
        appendLine()
        appendLine("平均每批 $avg 步。")
    }

    fun clear() = history.clear()

    fun guide(): String = """
        zx_transition(op=plan|planRefs|report|stats|clear|guide, card?, changed?)

        多卡同时更新时的补间与调度——让一批卡的变化「看起来是同一个动作」。

        为什么需要：联动之后一次操作会连带改好几张卡。
        各自按自己的时机重绘，用户看到的是「表变了 → 停顿 → 图变了」，
        这不是一件事，是三件事依次发生。

        三条规则：
          一、顺序按依赖——上游先于下游，
              否则会看到「结论先变、表格后变」的因果倒置
          二、超过 4 步就降级——满屏都在动反而看不清哪张变了
          三、总时长不超 600ms——超了用户会觉得卡

        失败不阻断批次：一张卡更新失败不该让后面几张都不动，
        失败的那张标出来就够了。
    """.trimIndent()
}
