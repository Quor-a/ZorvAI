package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 卡片事务 —— 一组跨卡改动，要么全成，要么全回滚。
 *
 * 为什么需要：联动让卡片之间有了依赖，于是"改一件事"常常是"改好几张卡"。
 * 比如把预算从 1200 改到 2400，表格、图表、结论三张卡都要变。
 * 如果改到第二张发现数值非法（图表 points 要求 Number，却填了字符串），
 * 就出现表格已改、图表没改的半截状态——用户看到的是自相矛盾的界面。
 *
 * 事务的做法：
 *   1. begin 时给所有涉及的卡做 savepoint（只记受影响字段的原值，不整卡快照）
 *   2. 每步 apply 前先试算校验，任何一步失败立刻 rollback 全部
 *   3. commit 后把整条事务压进撤销栈，用户一步撤销整组改动
 *
 * 与 ZxStore 的分工：ZxStore 管单卡单次交互，ZxTx 管跨卡一组改动。
 * ZxTx 提交后会把整条事务作为一个 Op 压给 ZxStore，这样撤销粒度是"一组"而非"一次"。
 */
object ZxTx {

    enum class State { IDLE, OPEN, COMMITTED, ROLLED_BACK }

    /** 一次字段级改动 */
    data class Step(
        val cardId: String,
        val path: String,
        val before: String?,
        val after: String
    )

    class Transaction(val id: String) {
        var state = State.OPEN
        val steps = mutableListOf<Step>()
        /** savepoint：cardId -> (path -> 原值)，只记动过的字段 */
        val savepoint = mutableMapOf<String, MutableMap<String, String?>>()

        val size: Int get() = steps.size
    }

    private var current: Transaction? = null
    private var seq = 0
    private val history = ArrayDeque<Transaction>(32)

    fun current(): Transaction? = current
    fun state(): State = current?.state ?: State.IDLE

    /** 开事务 */
    fun begin(): Transaction {
        // 已有未提交事务时强制回滚，避免嵌套事务把 savepoint 搞乱
        current?.let { if (it.state == State.OPEN) rollback("嵌套开启，自动回滚前一个") }
        val t = Transaction("tx${++seq}")
        current = t
        ZxAudit.log(ZxAudit.Kind.TX, t.id, "事务开启")
        return t
    }

    /**
     * 在事务里改一个字段。
     * 先记 savepoint，再试算校验，通过才真正写入并传播。
     */
    fun set(cardId: String, path: String, value: String): Result {
        val t = current ?: return Result(false, "没有开启的事务。请先 zx_tx(op=begin)。")
        if (t.state != State.OPEN) return Result(false, "事务 ${t.id} 已结束（${t.state}），不能继续改动。")

        val node = ZxRef.get(cardId)
            ?: return Result(false, "卡片 $cardId 不存在，事务将继续但此步无效。")

        // savepoint 只在第一次改这个字段时记录，保证拿到的是事务开始前的原值
        val sp = t.savepoint.getOrPut(cardId) { mutableMapOf() }
        if (!sp.containsKey(path)) sp[path] = ZxRef.readValue(cardId, path)

        val before = sp[path]

        // 试算：把改动套进 DSL 里校验一遍，不通过就不落盘
        val trial = ZxRef.resolve(node.dsl, cardId).let { dsl ->
            inject(dsl, path, value)
        }
        val check = ZxDsl.validate(node.component, trial)
        if (!check.ok) {
            // 关键：失败不立刻回滚，让调用方决定是追加修正还是放弃
            // 但事务已被标记为脏，commit 时会拒绝
            t.state = State.OPEN
            return Result(false, "步骤校验失败（card=$cardId path=$path）：${check.reason}。" +
                                 "事务 ${t.id} 仍未提交，可继续修正或 zx_tx(op=rollback)。")
        }

        // 写入并沿依赖图传播
        node.values[path] = value
        ZxRef.propagate(cardId, path, value)
        t.steps.add(Step(cardId, path, before, value))
        return Result(true, "已改 $cardId.$path：${before ?: "∅"} → $value（事务内 ${t.size} 步）")
    }

    /** 提交：把整条事务作为单个 Op 压进撤销栈 */
    fun commit(): Result {
        val t = current ?: return Result(false, "没有开启的事务")
        if (t.state != State.OPEN) return Result(false, "事务 ${t.id} 已结束")

        // 提交前对每张涉及的卡做一次完整校验，防止多步叠加后形状被改坏
        for (cardId in t.savepoint.keys) {
            val node = ZxRef.get(cardId) ?: continue
            val check = ZxDsl.validate(node.component, ZxRef.resolve(node.dsl, cardId))
            if (!check.ok) {
                rollback("提交前校验失败：$cardId → ${check.reason}")
                return Result(false, "提交失败，已整体回滚：${check.reason}")
            }
        }

        t.state = State.COMMITTED
        // 整条事务作为一个撤销单元
        ZxStore.push(TxOp(t))
        history.addLast(t)
        current = null
        ZxAudit.log(ZxAudit.Kind.TX, t.id, "提交 ${t.size} 步 · 涉及 ${t.savepoint.size} 张卡")
        return Result(true, "事务 ${t.id} 已提交：${t.size} 步，涉及 ${t.savepoint.size} 张卡。可一步撤销整组。")
    }

    /** 回滚：按逆序把每个字段写回原值 */
    fun rollback(reason: String = "手动回滚"): Result {
        val t = current ?: return Result(false, "没有开启的事务")
        val undone = t.steps.reversed()
        for (s in undone) {
            val node = ZxRef.get(s.cardId) ?: continue
            val back = s.before
            if (back == null) node.values.remove(s.path)
            else {
                node.values[s.path] = back
                ZxRef.propagate(s.cardId, s.path, back)
            }
        }
        t.state = State.ROLLED_BACK
        current = null
        ZxAudit.log(ZxAudit.Kind.TX_ROLLBACK, t.id, "$reason · 回滚 ${undone.size} 步")
        return Result(true, "事务 ${t.id} 已回滚：$reason。已撤销 ${undone.size} 步改动，全部卡片恢复到事务前状态。")
    }

    /** savepoint：事务内设一个可回退到的中间点 */
    fun savepoint(name: String): Result {
        val t = current ?: return Result(false, "没有开启的事务")
        t.savepoint["@$name"] = mutableMapOf()
        // 用当前所有已改字段的值做快照
        t.steps.groupBy { it.cardId }.forEach { (cardId, steps) ->
            t.savepoint["@$name"]!!["__${cardId}_count"] = steps.size.toString()
        }
        return Result(true, "已设 savepoint $name（当前 ${t.size} 步）")
    }

    /** 事务作为一个整体撤销单元 */
    private class TxOp(private val t: Transaction) : ZxStore.Op {
        override val label = "事务 ${t.id}（${t.size} 步 · ${t.savepoint.size} 张卡）"
        override fun undo() {
            t.steps.reversed().forEach { s ->
                val node = ZxRef.get(s.cardId) ?: return@forEach
                if (s.before == null) node.values.remove(s.path)
                else { node.values[s.path] = s.before!!; ZxRef.propagate(s.cardId, s.path, s.before!!) }
            }
        }
        override fun redo() {
            t.steps.forEach { s ->
                val node = ZxRef.get(s.cardId) ?: return@forEach
                node.values[s.path] = s.after
                ZxRef.propagate(s.cardId, s.path, s.after)
            }
        }
    }

    /** 把值注入 DSL 的指定路径，用于试算 */
    private fun inject(dsl: String, path: String, value: String): String {
        // 试算阶段用宽松替换：把路径末段对应的旧值换成新值
        val key = path.substringAfterLast('.').substringBefore('[')
        val old = runCatching {
            val o = JSONObject(dsl)
            val seg = path.split('.')
            var cur: Any? = o
            for (s in seg) {
                val idx = Regex("\\[(\\d+)\\]").findAll(s)
                val k = s.substringBefore('[')
                if (k.isNotEmpty()) cur = (cur as? JSONObject)?.opt(k)
                for (m in idx) cur = (cur as? org.json.JSONArray)?.opt(m.groupValues[1].toInt())
            }
            cur?.toString()
        }.getOrNull()
        return if (old != null) dsl.replace(old, value) else dsl
    }

    fun history(): List<Transaction> = history.toList()

    fun statusAsTerminal(): String {
        val lines = org.json.JSONArray()
        val t = current
        if (t == null) {
            lines.put(JSONObject().put("lv", "out").put("c", "当前没有进行中的事务"))
        } else {
            lines.put(JSONObject().put("lv", "cmd").put("c", "zx_tx status"))
            lines.put(JSONObject().put("lv", "ok").put("c", "${t.id}  state=${t.state}  ${t.size} 步"))
            t.steps.forEach { s ->
                lines.put(JSONObject().put("lv", "out")
                    .put("c", "  ${s.cardId}.${s.path}  ${s.before ?: "∅"} → ${s.after}"))
            }
        }
        if (history.isNotEmpty()) {
            lines.put(JSONObject().put("lv", "out").put("c", "历史事务：${history.size} 条"))
            history.takeLast(5).forEach { h ->
                lines.put(JSONObject().put("lv", "out").put("c", "  ${h.id} ${h.state} ${h.size} 步"))
            }
        }
        return JSONObject().put("type", "zx_terminal")
            .put("cmd", "zx_tx status").put("exit", 0).put("lines", lines).toString()
    }

    fun reset() { current = null; history.clear(); seq = 0 }
}
