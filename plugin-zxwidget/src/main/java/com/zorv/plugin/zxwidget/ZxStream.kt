package com.zorv.plugin.zxwidget

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * 卡片的流式增量渲染 —— 从"整卡重绘"推进到"token 级增量"。
 *
 * 为什么需要：之前的做法（ZxCardStore 多次 emit）
 * 已经是"一层一层生长"，但每一层仍然是**整卡重绘**——
 * 把整张卡的 DSL 重新解析、重新组合一遍。
 *
 * 后果有三个，而且都出现在长卡上：
 *   一、滚动位置会跳。整卡重绘后 LazyColumn 重新测量，
 *       用户正在看的位置被顶走。
 *   二、输入焦点会丢。卡里如果有输入框（zx_form / zx_tags），
 *       重绘瞬间焦点丢失，键盘收起。
 *   三、重复解析。100 行的表每次追加一行都要重新解析 100 行。
 *
 * token 级增量的做法：**只把新增的那一段拼上去，不动已有的**。
 *
 * 实现上有三处必须想清楚：
 *
 *   一、**增量必须能定位到路径**，不能只给字符串。
 *       append(path, value) 而不是 append(text)——
 *       只有路径才能在已渲染的树上找到要改的那个节点。
 *
 *   二、**未在渲染中的卡也要能缓冲**。
 *       模型可能先发增量、后发卡头（历史上确实发生过）。
 *       所以要先缓冲，等卡真正挂载再回放。
 *
 *   三、**终止必须幂等。**
 *       流可能因为超时、用户停止、出错而结束；
 *       多次调 end() 不应该发多次完成事件。
 */
object ZxStream {

    enum class Op { BEGIN, APPEND, PATCH, END }

    data class Chunk(
        val op: Op,
        val cardId: String,
        /** APPEND/PATCH 的目标路径，如 "rows[2].v" */
        val path: String = "",
        val value: String = "",
        /** BEGIN 时的组件类型 */
        val component: String = "",
        val seq: Int = 0,
        val t: Long = System.currentTimeMillis()
    ) {
        fun line(): String = when (op) {
            Op.BEGIN -> "%-4d BEGIN  %-8s %s".format(seq, cardId, component)
            Op.APPEND -> "%-4d APPEND %-8s %s += %s".format(seq, cardId, path, trunc(value))
            Op.PATCH -> "%-4d PATCH  %-8s %s = %s".format(seq, cardId, path, trunc(value))
            Op.END -> "%-4d END    %-8s".format(seq, cardId)
        }
        private fun trunc(s: String) =
            if (s.length <= 26) s else s.take(26) + "…(${s.length})"
    }

    private val _chunks = MutableSharedFlow<Chunk>(replay = 0, extraBufferCapacity = 256)
    val chunks: SharedFlow<Chunk> = _chunks

    /** 活跃流：cardId → 已累积的增量 */
    private val active = HashMap<String, StreamBuf>()

    /** 卡还没挂载时先缓冲，挂载后再回放 */
    private val pending = HashMap<String, MutableList<Chunk>>()

    private var seq = 0

    private class StreamBuf(
        val cardId: String,
        val component: String,
        val parts: MutableList<Pair<String, String>> = mutableListOf(),
        var ended: Boolean = false,
        val startT: Long = System.currentTimeMillis()
    )

    /* ── 写入侧（模型 / ZxCardStore 调用） ─────────────────── */

    fun begin(cardId: String, component: String): Chunk {
        val buf = StreamBuf(cardId, component)
        active[cardId] = buf
        val c = Chunk(Op.BEGIN, cardId, component = component, seq = ++seq)
        // 卡还没挂载：先缓冲，别丢
        if (!ZxRef.has(cardId)) pending.getOrPut(cardId) { mutableListOf() }.add(c)
        _chunks.tryEmit(c)
        return c
    }

    /**
     * 追加一段。path 为目标路径，value 为新增内容。
     * 整卡重绘的对立面——只动这一段。
     */
    fun append(cardId: String, path: String, value: String): Chunk? {
        val buf = active[cardId] ?: return null
        if (buf.ended) return null
        buf.parts.add(path to value)
        val c = Chunk(Op.APPEND, cardId, path, value, seq = ++seq)
        if (!ZxRef.has(cardId)) pending.getOrPut(cardId) { mutableListOf() }.add(c)
        _chunks.tryEmit(c)
        return c
    }

    /**
     * 原地改写某一段（不是追加）。用于"先给占位，后填真值"。
     */
    fun patch(cardId: String, path: String, value: String): Chunk? {
        val buf = active[cardId] ?: return null
        if (buf.ended) return null
        val c = Chunk(Op.PATCH, cardId, path, value, seq = ++seq)
        if (!ZxRef.has(cardId)) pending.getOrPut(cardId) { mutableListOf() }.add(c)
        _chunks.tryEmit(c)
        return c
    }

    /** 终止。幂等——多次调用只发一次 END。 */
    fun end(cardId: String): Chunk? {
        val buf = active[cardId] ?: return null
        if (buf.ended) return null          // 幂等
        buf.ended = true
        val c = Chunk(Op.END, cardId, seq = ++seq,
                      value = (System.currentTimeMillis() - buf.startT).toString())
        if (!ZxRef.has(cardId)) pending.getOrPut(cardId) { mutableListOf() }.add(c)
        _chunks.tryEmit(c)
        return c
    }

    /* ── 读取侧（宿主 / 渲染层调用） ───────────────────────── */

    /** 卡挂载后调用：回放缓冲的增量 */
    fun drainPending(cardId: String): List<Chunk> {
        val l = pending.remove(cardId) ?: return emptyList()
        l.forEach { _chunks.tryEmit(it) }
        return l
    }

    fun isActive(cardId: String) = active[cardId]?.let { !it.ended } ?: false

    /** 已累积的完整内容（按 path 归并） */
    fun materialize(cardId: String): Map<String, String> {
        val buf = active[cardId] ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        buf.parts.forEach { (p, v) -> out[p] = (out[p] ?: "") + v }
        return out
    }

    fun clear(cardId: String? = null) {
        if (cardId == null) { active.clear(); pending.clear() }
        else { active.remove(cardId); pending.remove(cardId) }
    }

    /* ── 演示与诊断 ────────────────────────────────────────── */

    /** 把一段长文本切成 token 级增量，模拟模型逐字输出 */
    fun simulate(cardId: String, component: String, full: String, per: Int = 3): List<Chunk> {
        clear(cardId)
        val out = mutableListOf<Chunk>()
        out.add(begin(cardId, component))
        var i = 0
        while (i < full.length) {
            val piece = full.substring(i, minOf(i + per, full.length))
            append(cardId, "text", piece)?.let { out.add(it) }
            i += per
        }
        end(cardId)?.let { out.add(it) }
        return out
    }

    fun report(): String = buildString {
        val b = active.values.lastOrNull()
        if (b == null) { appendLine("当前没有活跃流。"); return@buildString }
        appendLine("活跃流：${b.cardId} · ${b.component} · ${b.parts.size} 段"
                   + if (b.ended) " · 已结束" else " · 进行中")
        val full = materialize(b.cardId)
        full.entries.take(4).forEach { (p, v) ->
            appendLine("  %-10s %s".format(p, trunc2(v)))
        }
        appendLine()
        appendLine("已缓冲待回放的卡：${pending.size} 张"
                   + if (pending.isEmpty()) "" else "（${pending.keys.take(3).joinToString()}）")
        appendLine()
        appendLine("整卡重绘的三个后果，token 级增量就是要避免它们：")
        appendLine("  一、滚动位置会跳——重绘后 LazyColumn 重新测量")
        appendLine("  二、输入焦点会丢——卡里有输入框时键盘会收起")
        appendLine("  三、重复解析——100 行的表追加一行要重解析 100 行")
    }

    private fun trunc2(s: String) = if (s.length <= 30) s else s.take(30) + "…(${s.length})"

    fun guide(): String = """
        zx_stream(op=begin|append|patch|end|simulate|report|clear|guide, card?, path?, value?, component?)

        卡片的流式增量渲染——从「整卡重绘」推进到「token 级增量」。

        为什么需要：之前每一层仍然是整卡重绘，把整张卡的 DSL
        重新解析、重新组合一遍。后果有三个，都出现在长卡上：
          一、滚动位置会跳——重绘后 LazyColumn 重新测量
          二、输入焦点会丢——卡里有输入框时键盘会收起
          三、重复解析——100 行的表追加一行要重解析 100 行

        三处必须想清楚：
          一、增量必须能定位到路径，不能只给字符串。
              append(path, value) 而不是 append(text)——
              只有路径才能在已渲染的树上找到要改的那个节点
          二、未在渲染中的卡也要能缓冲。
              模型可能先发增量、后发卡头，所以要先缓冲再回放
          三、终止必须幂等。
              流可能因超时、用户停止、出错而结束，
              多次调 end() 不应该发多次完成事件

        simulate 会把一段长文本切成 token 级增量，用于演示与回归。
    """.trimIndent()
}
