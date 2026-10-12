package com.zorv.plugin.zxwidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * DSL 补丁协议 —— 改一张卡不重发整份 DSL。
 *
 * ⑨ 做了流式增量渲染：AI 一轮可以 emit 多次，气泡逐层长。
 * 但那条路只解决"**追加**"——往表格加一行、往时间轴加一步。
 *
 * 另一种更常见的情况是"**改动**"：
 *
 *   一张 100 行的表，AI 要改其中 3 个单元格。
 *   现在只能重发整份 DSL ≈ 2000 token。
 *   而它真正要表达的只是三句话：
 *     rows[3].v[1] = "2400"
 *     rows[7].v[2] = "已完成"
 *     title       = "Q4 预算"
 *   ≈ 60 token。
 *
 * 省下的不是一次的量，是**每一轮修订**的量。
 * 一个会话改十次就是两万 token。
 *
 * 三处必须想清楚，不然这个机制会变成 bug 制造机：
 *
 *   一、**应用后必须重新校验，失败要回滚**。
 *       单次补丁合法，几次叠加可能把形状改坏
 *       （改了行数但没改表头）。
 *       不校验就落盘，界面会静默变成错的。
 *
 *   二、**路径不存在要明说，不能静默创建**。
 *       `rows[99]` 不存在时，静默补 null 会让
 *       表格出现一堆空行，用户不知道发生了什么。
 *
 *   三、**不能超过一定比例**。补丁的目的是省 token，
 *       如果改的地方比整份 DSL 还多，直接重发更省。
 *       所以超过 60% 就建议整份替换。
 */
object ZxPatch {

    enum class Op { SET, INSERT, REMOVE, MOVE }

    data class Step(
        val op: Op,
        val path: String,
        val value: Any? = null,
        /** MOVE：目标下标 */
        val to: Int = -1
    )

    data class Result(
        val ok: Boolean,
        val reason: String = "",
        /** 应用后的 DSL（失败时为原 DSL，即已回滚） */
        val dsl: JSONObject = JSONObject(),
        /** 这个补丁相对整份 DSL 省了多少 */
        val saved: Int = 0
    )

    /** 补丁规模超过这个比例，不如整份重发 */
    private const val MAX_RATIO = 0.6
    private const val MAX_STEPS = 40

    /**
     * 应用补丁。任何一步失败都回滚到入口状态。
     */
    fun apply(component: String, dsl: JSONObject, steps: List<Step>): Result {
        if (steps.isEmpty()) return Result(false, "补丁为空——没有要改的东西")
        if (steps.size > MAX_STEPS)
            return Result(false, "补丁有 ${steps.size} 步，超过上限 $MAX_STEPS——这种情况下整份重发更省")

        // 省了多少：补丁文本 vs 整份 DSL
        val patchLen = steps.sumOf { s -> s.path.length + (s.value?.toString()?.length ?: 0) + 6 }
        val fullLen = dsl.toString().length
        val saved = (fullLen - patchLen).coerceAtLeast(0) / 3
        if (patchLen.toDouble() / fullLen.coerceAtLeast(1) > MAX_RATIO)
            return Result(false, "补丁规模已达整份 DSL 的 " +
                "${(patchLen.toDouble() / fullLen.coerceAtLeast(1) * 100).toInt()}%（上限 " +
                "${(MAX_RATIO * 100).toInt()}%）——这种规模整份重发更省，别用补丁")

        // 试算：先复制一份，改坏了直接丢掉
        val work = runCatching { JSONObject(dsl.toString()) }.getOrElse { JSONObject() }
        for ((i, s) in steps.withIndex()) {
            val err = when (s.op) {
                Op.SET    -> set(work, s)
                Op.INSERT -> insert(work, s)
                Op.REMOVE -> remove(work, s)
                Op.MOVE   -> move(work, s)
            }
            if (err != null)
                return Result(false, "第 ${i + 1} 步（${s.op} ${s.path}）失败：$err\n" +
                                     "整份补丁已回滚，卡片保持原样。")
        }
        // 关键：应用完必须重新校验。单步合法不代表叠加后还合法
        val v = ZxDsl.validateAny(component, work)
        if (!v.ok)
            return Result(false, "应用后校验失败：${v.reason}\n" +
                                 "整份补丁已回滚。单步合法不代表叠加后还合法——" +
                                 "比如改了行数但没改表头。")
        return Result(true, "已应用 ${steps.size} 步（省 ≈ $saved token）", work, saved)
    }

    /* ── 路径读写 ──────────────────────────────────────────────── */

    /**
     * 路径语法：`rows[3].v[1]`、`title`、`items[0].n`
     * 不支持通配——补丁要能精确定位，模糊匹配会改到不该改的地方。
     */
    private fun split(path: String): List<Any> {
        val out = mutableListOf<Any>()
        var i = 0
        while (i < path.length) {
            val j = path.indexOfAny(charArrayOf('.', '['), i)
            if (j < 0) { out.add(path.substring(i)); break }
            if (j > i) out.add(path.substring(i, j))
            if (path[j] == '[') {
                val e = path.indexOf(']', j)
                if (e < 0) return emptyList()
                val idx = path.substring(j + 1, e).toIntOrNull() ?: return emptyList()
                out.add(idx)
                i = e + 1
            } else i = j + 1
        }
        return out.filter { it !is String || it.isNotEmpty() }
    }

    /** 取父容器 + 最后一段，用于写入 */
    private fun resolve(root: JSONObject, path: String): Pair<Any?, Any?>? {
        val seg = split(path)
        if (seg.isEmpty()) return null
        var cur: Any? = root
        for (k in 0 until seg.size - 1) {
            cur = when (val s = seg[k]) {
                is String -> (cur as? JSONObject)?.opt(s)
                is Int -> (cur as? JSONArray)?.opt(s)
                else -> null
            } ?: return null
        }
        return cur to seg.last()
    }

    private fun set(root: JSONObject, s: Step): String? {
        val (parent, last) = resolve(root, s.path) ?: return "路径 ${s.path} 不存在——不静默创建"
        when (last) {
            is String -> {
                if (parent !is JSONObject) return "${s.path} 的父级不是对象"
                parent.put(last, s.value)
            }
            is Int -> {
                if (parent !is JSONArray) return "${s.path} 的父级不是数组"
                // 越界不静默补 null：会让表格出现一堆空行
                if (last >= parent.length()) return "${s.path} 越界（数组只有 ${parent.length()} 项）"
                parent.put(last, s.value)
            }
            else -> return "${s.path} 路径不合法"
        }
        return null
    }

    private fun insert(root: JSONObject, s: Step): String? {
        val (parent, last) = resolve(root, s.path) ?: return "路径 ${s.path} 不存在"
        val arr = parent as? JSONArray
            ?: return "${s.path} 的父级不是数组，插不进去"
        val at = (last as? Int) ?: arr.length()
        if (at > arr.length()) return "${s.path} 插入位置 $at 超过长度 ${arr.length()}"
        arr.put(at, s.value)
        return null
    }

    private fun remove(root: JSONObject, s: Step): String? {
        val (parent, last) = resolve(root, s.path) ?: return "路径 ${s.path} 不存在"
        val arr = parent as? JSONArray ?: return "${s.path} 的父级不是数组"
        val at = (last as? Int) ?: return "${s.path} 缺下标"
        if (at >= arr.length()) return "${s.path} 越界（数组只有 ${arr.length()} 项）"
        arr.remove(at)
        return null
    }

    private fun move(root: JSONObject, s: Step): String? {
        val (parent, last) = resolve(root, s.path) ?: return "路径 ${s.path} 不存在"
        val arr = parent as? JSONArray ?: return "${s.path} 的父级不是数组"
        val from = (last as? Int) ?: return "${s.path} 缺下标"
        if (from >= arr.length() || s.to >= arr.length() || s.to < 0)
            return "${s.path} 的 $from → ${s.to} 越界"
        val v = arr.remove(from)
        arr.put(s.to, v)
        return null
    }

    /* ── 解析补丁文本 ──────────────────────────────────────────── */

    /**
     * 从紧凑文本解析补丁。
     * 格式：一行一步，`op path = value` 或 `op path -> to`
     *   set     rows[3].v[1] = 2400
     *   insert  rows[8] = {"v":["x","y"]}
     *   remove  rows[2]
     *   move    rows[1] -> 5
     */
    fun parse(text: String): List<Step> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("//") }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"), 2)
            if (parts.size < 2) return@mapNotNull null
            val op = when (parts[0].lowercase()) {
                "set" -> Op.SET; "insert" -> Op.INSERT
                "remove", "del" -> Op.REMOVE; "move" -> Op.MOVE
                else -> return@mapNotNull null
            }
            val rest = parts[1]
            when (op) {
                Op.MOVE -> {
                    val m = Regex("^(\\S+)\\s*->\\s*(-?\\d+)$").find(rest) ?: return@mapNotNull null
                    Step(op, m.groupValues[1], to = m.groupValues[2].toInt())
                }
                Op.REMOVE -> Step(op, rest.trim())
                else -> {
                    val i = rest.indexOf('=')
                    if (i < 0) return@mapNotNull null
                    val path = rest.substring(0, i).trim()
                    val raw = rest.substring(i + 1).trim()
                    val value: Any? = when {
                        raw.startsWith("{") || raw.startsWith("[") ->
                            runCatching {
                                if (raw.startsWith("{")) JSONObject(raw) else JSONArray(raw)
                            }.getOrNull()
                        raw.equals("true", true) || raw.equals("false", true) ->
                            raw.toBoolean()
                        raw.toLongOrNull() != null -> raw.toLong()
                        raw.toDoubleOrNull() != null -> raw.toDouble()
                        else -> raw.trim('"', '\'')
                    }
                    Step(op, path, value)
                }
            }
        }.toList()

    /** 把改动描述成补丁建议，供模型参考 */
    fun suggest(component: String, dsl: JSONObject): String = buildString {
        appendLine("补丁格式（一行一步）：")
        appendLine("  set    rows[3].v[1] = 2400")
        appendLine("  insert rows[8] = {\"v\":[\"x\",\"y\"]}")
        appendLine("  remove rows[2]")
        appendLine("  move   rows[1] -> 5")
        appendLine()
        appendLine("当前卡片 $component，整份 DSL 约 ${dsl.toString().length / 3} token。")
        appendLine("改动少于 ${(MAX_RATIO * 100).toInt()}% 时补丁更省；超过就整份重发。")
        appendLine()
        appendLine("三处约束：")
        appendLine("  一、应用后必须重新校验，失败整体回滚")
        appendLine("  二、路径不存在不静默创建")
        appendLine("  三、补丁规模超 ${(MAX_RATIO * 100).toInt()}% 建议整份替换")
    }

    fun guide(): String = """
        zx_patch(op=apply|suggest|guide, component?, card?, patch?)

        改一张卡不重发整份 DSL。

        ⑨ 做了流式增量渲染，但那条路只解决「追加」——
        往表格加一行、往时间轴加一步。
        更常见的是「改动」：

          一张 100 行的表，改 3 个单元格
          现在：重发整份 DSL ≈ 2000 token
          补丁：rows[3].v[1] = 2400  …  ≈ 60 token

        省下的不是一次的量，是每一轮修订的量。
        一个会话改十次就是两万 token。

        格式（一行一步）：
          set    rows[3].v[1] = 2400
          insert rows[8] = {"v":["x","y"]}
          remove rows[2]
          move   rows[1] -> 5

        三处必须想清楚：
          一、应用后必须重新校验，失败整体回滚——
              单步合法不代表叠加后还合法
          二、路径不存在不静默创建——静默补 null
              会让表格出现一堆空行
          三、补丁规模超 60% 建议整份替换——
              目的是省 token，补丁比整份还大就本末倒置
    """.trimIndent()
}
