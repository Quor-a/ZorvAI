package com.zorv.plugin.zxwidget
import kotlin.math.abs

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * 跨卡查询语言 —— 用一句查询从多张卡里取数，而不是逐张遍历。
 *
 * 为什么需要：做到第十批，一次会话里可能有十几张卡。
 * 模型要回答"预算表里研发那一行是多少"，
 * 现在只能把整张卡的 DSL 读进上下文——
 * 而它真正需要的只是一个单元格。
 *
 * 于是就有了这个矛盾：
 *   · 不读 DSL，模型答不了
 *   · 读整个 DSL，十几次下来上下文就满了
 *
 * 查询语言是唯一的解：让模型用一句短查询换回一小段结果。
 *
 *   cards(type=zx_table)              → 所有表格卡的 id
 *   get(budget).rows[k=dev].v[1]      → 一个单元格
 *   sum(budget.rows.*.v[1])           → 一列求和
 *   where(chart.labels, >100)         → 过滤
 *
 * 三条设计约束：
 *
 *   一、**结果必须短**。查询的价值就是省上下文，
 *       如果回一大坨，等于没查。默认截断 6 条。
 *
 *   二、**路径解析必须给出"哪一步断了"**。
 *       模型拿到 null 只能重试；拿到
 *       "rows[k=dev] 匹配 0 行（实际键：ops/qa）"
 *       它下一轮就能自己改对。这是它和 ⑭ 渲染回执同源的思路——
 *       失败要留下可修正的信息。
 *
 *   三、**只读**。查询语言绝不提供写入语法——
 *       写入已经有 zx_transaction 和 ZxRef.propagate 两条路径，
 *       再加一条会让"这个改动到底走了哪条路"变得无法追溯。
 */
object ZxQuery {

    private const val MAX_RESULTS = 6

    sealed class Res {
        data class Str(val v: String) : Res()
        data class Num(val v: Double) : Res()
        data class List_(val items: List<String>) : Res()
        data class Fail(val msg: String) : Res()
    }

    /**
     * 执行一句查询。只读。
     */
    fun run(q: String): Res {
        val s = q.trim()
        if (s.isBlank()) return Res.Fail("查询为空")

        // cards(type=xxx)
        val mCards = Regex("""cards\s*\(\s*type\s*=\s*(\w+)\s*\)""").find(s)
        if (mCards != null) {
            val t = mCards.groupValues[1]
            val ids = ZxRef.graph.entries.filter { it.value.component == t }
                .map { it.key }
            if (ids.isEmpty())
                return Res.Fail("没有 type=$t 的卡片。当前有：${ZxRef.graph.values.map { it.component }.distinct().joinToString()}")
            return Res.List_(ids.take(MAX_RESULTS))
        }

        // sum(path) / avg / max / min / count
        val mAgg = Regex("""(sum|avg|max|min|count)\s*\((.+)\)""").find(s)
        if (mAgg != null) {
            val fn = mAgg.groupValues[1]
            val path = mAgg.groupValues[2].trim().trim('"')
            val nums = collectNums(path)
            if (nums is Res.Fail) return nums
            val list = (nums as Res.List_).items.mapNotNull { it.toDoubleOrNull() }
            if (list.isEmpty()) return Res.Fail("$path 取到的不是数值：${(nums as Res.List_).items.take(3).joinToString()}")
            return Res.Num(when (fn) {
                "sum" -> list.sum()
                "avg" -> list.average()
                "max" -> list.maxOrNull()!!
                "min" -> list.minOrNull()!!
                else  -> list.size.toDouble()
            })
        }

        // where(path, op value)
        val mWhere = Regex("""where\s*\((.+?)\s*,\s*(>=|<=|!=|>|<|=)\s*(-?[\d.]+)\s*\)""").find(s)
        if (mWhere != null) {
            val path = mWhere.groupValues[1].trim().trim('"')
            val op = mWhere.groupValues[2]
            val v = mWhere.groupValues[3].toDoubleOrNull()
                ?: return Res.Fail("where 的比较值不是数值：${mWhere.groupValues[3]}")
            val got = collectNums(path)
            if (got is Res.Fail) return got
            val hit = (got as Res.List_).items.withIndex().filter { (_, x) ->
                val d = x.toDoubleOrNull() ?: return@filter false
                when (op) { ">" -> d > v; "<" -> d < v; ">=" -> d >= v
                            "<=" -> d <= v; "!=" -> d != v; else -> d == v }
            }.map { "#${it.index}=${it.value}" }
            if (hit.isEmpty()) return Res.Fail("$path 里没有满足 $op $v 的项（共 ${got.items.size} 项）")
            return Res.List_(hit.take(MAX_RESULTS))
        }

        // get(path) —— 单值
        val mGet = Regex("""get\s*\((.+)\)""").find(s)
        if (mGet != null) return resolve(mGet.groupValues[1].trim().trim('"'))

        // 裸路径也允许
        if (s.matches(Regex("""[A-Za-z_][\w.\[\]=\"'*,-]*"""))) return resolve(s)
        return Res.Fail("无法解析查询：$s。支持 cards(type=) / get(path) / sum|avg|max|min|count(path) / where(path, op value)")
    }

    /** 解析一条路径到单值。关键：失败要说清断在哪一步。 */
    private fun resolve(path: String): Res {
        val parts = splitPath(path)
        if (parts.isEmpty()) return Res.Fail("路径为空")

        val head = parts.first()
        val cardId = head.name
        val node = ZxRef.get(cardId)
            ?: return Res.Fail("没有 id=$cardId 的卡片。当前有：${ZxRef.graph.keys.take(MAX_RESULTS).joinToString()}")
        val root = runCatching { JSONObject(node.dsl) }.getOrElse {
            return Res.Fail("卡片 $cardId 的 DSL 不是合法 JSON，无法查询")
        }

        var cur: Any = root
        var walked = cardId
        for (p in parts.drop(1)) {
            val next = step(cur, p)
            if (next == null) {
                // 给出实际存在的键，让模型下一轮能自己改对
                val hint = keysOf(cur)
                return Res.Fail("$walked 之后取 ${p.raw} 失败" +
                    if (hint != null) "（这一层实际有：$hint）" else "")
            }
            cur = next
            walked += "." + p.raw
        }
        return when (cur) {
            is String -> Res.Str(cur)
            is Number -> Res.Num(cur.toDouble())
            is Boolean -> Res.Str(cur.toString())
            else -> Res.Str(cur.toString().take(200))
        }
    }

    /** 收集一条路径上的所有数值（支持 * 通配） */
    private fun collectNums(path: String): Res {
        if (!path.contains("*")) {
            return when (val r = resolve(path)) {
                is Res.Num -> Res.List_(listOf(r.v.toString()))
                is Res.Str -> Res.List_(listOf(r.v))
                is Res.Fail -> r
                else -> Res.Fail("$path 不是单个值，无法聚合")
            }
        }
        val prefix = path.substringBefore("*")
        val suffix = path.substringAfter("*")
        val arrPath = prefix.trimEnd('.')
        val arr = resolve(arrPath)
        val list = when (arr) {
            is Res.List_ -> arr.items
            else -> {
                // 试着把 arrPath 当数组取
                val raw = resolveArray(arrPath) ?: return Res.Fail("$arrPath 不是数组，无法用 * 展开")
                raw
            }
        }
        val out = mutableListOf<String>()
        list.forEachIndexed { i, item ->
            val sub = resolve("$arrPath[$i]$suffix")
            if (sub is Res.Num) out.add(sub.v.toString())
            else if (sub is Res.Str) out.add(sub.v)
        }
        if (out.isEmpty()) return Res.Fail("$path 用 * 展开后没有取到任何值")
        return Res.List_(out.take(MAX_RESULTS * 3))
    }

    private fun resolveArray(path: String): List<String>? {
        val parent = path.substringBeforeLast('[')
        val idx = path.substringAfterLast('[').substringBefore(']').toIntOrNull()
        val r = resolve(parent)
        val raw = when (r) { is Res.Str -> r.v; is Res.Num -> r.v.toString(); else -> return null }
        return null
    }

    private data class Seg(val raw: String, val name: String) {
        var filter: Pair<String, String>? = null   // k=v
        var index: Int? = null
    }

    private fun splitPath(path: String): List<Seg> {
        val out = mutableListOf<Seg>()
        val re = Regex("""([^.\[]+)(\[([^\]]*)\])?""")
        var rest = path
        // 简单按 . 切，再处理每段后的 [..]
        val parts = rest.split('.')
        for (p in parts) {
            if (p.isBlank()) continue
            val name = p.substringBefore('[')
            val seg = Seg(raw = p, name = name)
            val bx = p.substringAfter('[', "").substringBefore(']', "")
            if (bx.isNotBlank()) {
                if (bx.contains("=")) {
                    val (k, v) = bx.split("=", limit = 2)
                    seg.filter = k.trim('"', '\'') to v.trim('"', '\'')
                } else seg.index = bx.toIntOrNull()
            }
            out.add(seg)
        }
        return out
    }

    private fun step(cur: Any, seg: Seg): Any? {
        return when (cur) {
            is JSONObject -> {
                val v = cur.opt(seg.name) ?: return null
                applyIdx(v, seg)
            }
            is org.json.JSONArray -> {
                if (seg.filter != null) {
                    val (k, fv) = seg.filter!!
                    for (i in 0 until cur.length()) {
                        val o = cur.optJSONObject(i) ?: continue
                        if (o.optString(k) == fv) return o
                    }
                    return null
                }
                val i = seg.index ?: seg.name.toIntOrNull() ?: return null
                if (i !in 0 until cur.length()) return null
                cur.opt(i)
            }
            else -> null
        }
    }

    private fun applyIdx(v: Any, seg: Seg): Any? {
        if (seg.index == null && seg.filter == null) return v
        val arr = v as? org.json.JSONArray ?: return null
        if (seg.filter != null) {
            val (k, fv) = seg.filter!!
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString(k) == fv) return o
            }
            return null
        }
        val i = seg.index ?: return null
        if (i !in 0 until arr.length()) return null
        return arr.opt(i)
    }

    private fun keysOf(cur: Any): String? = when (cur) {
        is JSONObject -> cur.keys().asSequence().toList().take(8).joinToString()
        is org.json.JSONArray -> {
            val ks = (0 until min(cur.length(), 6)).mapNotNull { i ->
                cur.optJSONObject(i)?.optString("k")?.takeIf { it.isNotBlank() }
                    ?: cur.optJSONObject(i)?.optString("n")?.takeIf { it.isNotBlank() }
            }
            if (ks.isEmpty()) "共 ${cur.length()} 项（无 k/n 键，请用下标）" else ks.joinToString()
        }
        else -> null
    }

    /* ── 对外输出 ───────────────────────────────────────────── */

    fun exec(q: String): String {
        val r = run(q)
        return when (r) {
            is Res.Str -> r.v
            is Res.Num -> if (r.v == kotlin.math.floor(r.v) && abs(r.v) < 1e15)
                r.v.toLong().toString() else "%.2f".format(r.v)
            is Res.List_ -> {
                val shown = r.items.take(MAX_RESULTS)
                val more = r.items.size - shown.size
                shown.joinToString("\n") + if (more > 0) "\n…还有 $more 条（上限 $MAX_RESULTS）" else ""
            }
            is Res.Fail -> "查询失败：" + r.msg
        }
    }

    fun report(): String = buildString {
        appendLine("当前 ${ZxRef.graph.size} 张卡可查：")
        ZxRef.graph.entries.take(MAX_RESULTS).forEach { (id, n) ->
            appendLine("  %-8s %s".format(id, n.component))
        }
        appendLine()
        appendLine("示例：cards(type=zx_table) / get(budget.rows[k=dev].v[1])")
        appendLine("      sum(budget.rows.*.v[1]) / where(chart.labels, >100)")
        appendLine()
        appendLine("只读——写入走 zx_transaction 或 ZxRef.propagate，")
        appendLine("再加一条会让「这个改动走了哪条路」无法追溯。")
    }

    fun guide(): String = """
        zx_query(q="查询表达式" | op=report|guide)

        跨卡查询语言——用一句查询从多张卡里取数，而不是逐张遍历。

        为什么需要：一次会话里可能有十几张卡。模型要回答
        「预算表里研发那一行是多少」，现在只能把整张卡读进上下文——
        而它真正需要的只是一个单元格。

        语法：
          cards(type=zx_table)          所有表格卡的 id
          get(budget).rows[k=dev].v[1]  一个单元格
          sum(budget.rows.*.v[1])       一列求和
          avg / max / min / count       同上
          where(chart.labels, >100)     过滤

        三条约束：
          一、结果必须短——查询的价值就是省上下文，默认截断 $MAX_RESULTS 条
          二、失败要说清「哪一步断了」——
              拿到 null 模型只能重试；拿到
              「rows[k=dev] 匹配 0 行（实际键：ops/qa）」它下一轮就能改对
          三、只读——写入走 zx_transaction 或 ZxRef.propagate
    """.trimIndent().replace("$MAX_RESULTS", MAX_RESULTS.toString())
}
