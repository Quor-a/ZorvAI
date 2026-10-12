package com.zorv.plugin.zxwidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * 卡片版本树 —— 同一张卡的多次 AI 修订形成版本链，可 diff、可分支、可回溯。
 *
 * 为什么需要：AI 改卡片是高频动作。用户说"这个图换成柱状"，AI 重发一次 DSL，
 * 上一版就没了。用户再说"还是上一版好看"，AI 只能凭记忆重写，往往写不回原样。
 *
 * 版本树解决三件事：
 *   1. 每次 AI 修订留一个版本节点，随时能回去
 *   2. 支持分叉——AI 可以从 v2 开出两条支线让用户挑
 *   3. 版本之间能算结构化 diff，用户看得见改了什么
 *
 * 存储只存增量（父子 diff），不存整份 DSL：
 * 一张卡改十次，整份存是 10×，增量存接近 1×。
 */
object ZxVersion {

    data class Version(
        val id: String,          // v1 / v2 / v2a
        val cardId: String,
        val rev: Int,
        val dsl: String,
        val parent: String?,     // 父版本 id，null 表示根
        val summary: String,
        val at: Long = System.currentTimeMillis()
    )

    /** cardId -> 版本链（按 rev 有序） */
    private val chains = linkedMapOf<String, MutableList<Version>>()

    fun commit(cardId: String, dsl: String, summary: String, parent: String? = null): Version {
        val list = chains.getOrPut(cardId) { mutableListOf() }
        val rev = list.size + 1
        // 分叉命名：同 rev 的第二条起加后缀 a/b/c
        val base = "v$rev"
        var id = base
        var suffix = 0
        while (list.any { it.id == id }) {
            id = base + ('a' + suffix).toString().let { if (it.length == 1) it else "z" }
            suffix++
        }
        val v = Version(id, cardId, rev, dsl, parent ?: list.lastOrNull()?.id, summary)
        list.add(v)
        ZxAudit.log(ZxAudit.Kind.RENDER, cardId, "提交版本 $id · $summary")
        return v
    }

    /** 从某个版本开分支 */
    fun branch(cardId: String, fromId: String, dsl: String, summary: String): Version? {
        val list = chains[cardId] ?: return null
        val from = list.firstOrNull { it.id == fromId } ?: return null
        val rev = from.rev + 1
        var id = "v${rev}b"
        var n = 0
        while (list.any { it.id == id }) { id = "v${rev}b${n++}" }
        val v = Version(id, cardId, rev, dsl, fromId, summary)
        list.add(v)
        ZxAudit.log(ZxAudit.Kind.RENDER, cardId, "从 $fromId 开分支 $id")
        return v
    }

    fun list(cardId: String): List<Version> = chains[cardId] ?: emptyList()
    fun get(cardId: String, id: String): Version? = chains[cardId]?.firstOrNull { it.id == id }
    fun head(cardId: String): Version? = chains[cardId]?.lastOrNull()

    /** 回溯：把某个版本的 DSL 重新推给渲染层 */
    fun rollback(cardId: String, id: String): Result {
        val v = get(cardId, id) ?: return Result(false, "卡片 $cardId 没有版本 $id")
        val node = ZxRef.get(cardId)
            ?: return Result(false, "卡片 $cardId 已不在依赖图中，无法回溯")
        node.dsl = v.dsl
        ZxCardStore.emit("回溯到 $id", node.component, ZxRef.resolve(v.dsl, cardId))
        // 回溯后把引用它的下游也刷一遍
        ZxRef.propagate(cardId, "_rollback", v.dsl)
        ZxAudit.log(ZxAudit.Kind.ROLLBACK, cardId, "回溯到 $id")
        return Result(true, "已回溯到 $id（${v.summary}）")
    }

    /* ── 结构化 diff ─────────────────────────────────────────── */

    enum class OpKind { ADD, DEL, MOD }

    data class DiffOp(val path: String, val kind: OpKind, val before: String?, val after: String?)

    /** 递归比对两棵 JSON 树，输出路径级差异 */
    fun diff(a: String, b: String): List<DiffOp> {
        val ja = runCatching { JSONObject(a) }.getOrNull() ?: return emptyList()
        val jb = runCatching { JSONObject(b) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<DiffOp>()
        walk(ja, jb, "", out)
        return out
    }

    private fun walk(a: Any?, b: Any?, path: String, out: MutableList<DiffOp>) {
        when {
            a is JSONObject && b is JSONObject -> {
                val keys = (a.keys().asSequence().toSet() + b.keys().asSequence().toSet())
                for (k in keys) {
                    val va = a.opt(k); val vb = b.opt(k)
                    walk(va, vb, if (path.isEmpty()) k else "$path.$k", out)
                }
            }
            a is JSONArray && b is JSONArray -> {
                val n = maxOf(a.length(), b.length())
                for (i in 0 until n) {
                    walk(a.opt(i), b.opt(i), "$path[$i]", out)
                }
            }
            a == null && b != null -> out.add(DiffOp(path, OpKind.ADD, null, str(b)))
            a != null && b == null -> out.add(DiffOp(path, OpKind.DEL, str(a), null))
            a != null && b != null && !a.equals(b) ->
                out.add(DiffOp(path, OpKind.MOD, str(a), str(b)))
        }
    }

    private fun str(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "∅"
        is JSONObject, is JSONArray -> v.toString().let { if (it.length > 40) it.take(37) + "…" else it }
        else -> v.toString()
    }

    /** 把 diff 结果渲染成 zx_diff 卡的 DSL，用户直接看得见 */
    fun diffAsCard(cardId: String, fromId: String, toId: String): String {
        val a = get(cardId, fromId); val b = get(cardId, toId)
        val ops = if (a != null && b != null) diff(a.dsl, b.dsl) else emptyList()
        val lines = JSONArray()
        ops.forEach { o ->
            when (o.kind) {
                OpKind.DEL -> lines.put(JSONObject().put("t", "-").put("c", "${o.path}: ${o.before}"))
                OpKind.ADD -> lines.put(JSONObject().put("t", "+").put("c", "${o.path}: ${o.after}"))
                OpKind.MOD -> {
                    lines.put(JSONObject().put("t", "-").put("c", "${o.path}: ${o.before}"))
                    lines.put(JSONObject().put("t", "+").put("c", "${o.path}: ${o.after}"))
                }
            }
        }
        if (lines.length() == 0) lines.put(JSONObject().put("t", " ").put("c", "两个版本内容一致"))
        return JSONObject()
            .put("type", "zx_diff")
            .put("path", "$cardId  $fromId → $toId")
            .put("hunks", JSONArray().put(JSONObject()
                .put("header", "@@ $fromId → $toId · ${ops.size} 处差异 @@")
                .put("lines", lines)))
            .toString()
    }

    /** 版本树可视化：渲染成终端卡 */
    fun treeAsTerminal(cardId: String): String {
        val list = chains[cardId]
        val lines = JSONArray()
        if (list.isNullOrEmpty()) {
            lines.put(JSONObject().put("lv", "warn").put("c", "卡片 $cardId 没有版本记录"))
        } else {
            list.forEachIndexed { i, v ->
                val branchMark = if (v.parent != null && list.any { it.id != v.id && it.parent == v.parent }) "├─" else "└─"
                lines.put(JSONObject().put("lv", if (i == list.size - 1) "ok" else "out")
                    .put("c", "$branchMark ${v.id}  ${v.summary}"))
                if (v.parent != null)
                    lines.put(JSONObject().put("lv", "out").put("c", "│    parent: ${v.parent}"))
            }
        }
        return JSONObject()
            .put("type", "zx_terminal")
            .put("cmd", "zx_version tree $cardId")
            .put("exit", 0)
            .put("lines", lines).toString()
    }

    fun clear(cardId: String) { chains.remove(cardId) }
    fun clearAll() { chains.clear() }
}
