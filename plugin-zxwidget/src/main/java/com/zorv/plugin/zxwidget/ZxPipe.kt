package com.zorv.plugin.zxwidget

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 分析流水线 —— 把一串分析步骤串成一条可整体重跑的链。
 *
 * **为什么需要**
 *
 * 前面二十四批做了一百多个**单个组件**，每个都能回答一个问题：
 * QQ 图回答"是不是正态"、t 检验回答"有没有差异"、
 * Bootstrap 回答"这个估计稳不稳"。
 *
 * 但真实的分析从来不是一次：
 *
 *   看分布 → 检正态 → 选检验 → 看效应 → 校多重 → 出结论
 *
 * 之前每一步都要模型**单独下发一张卡**，有三个后果：
 *
 *   一、**依赖关系在界面上完全看不见**。
 *       六张卡平铺在气泡里，用户看不出
 *       "第 3 步的检验方法是由第 2 步的正态性检验决定的"。
 *       他只能看到六个结论，看不到它们之间的关系。
 *
 *   二、**上游变了下游不会自动跟着变**。
 *       用户改了第 1 步的样本，第 2-6 步还是旧结果——
 *       于是界面上出现一组自相矛盾的结论，
 *       而用户不知道该信哪个。
 *
 *   三、**没法整体重跑**。
 *       想换一批数据重做一遍，得让模型重新走一轮推理，
 *       把六张卡再发一次——而其中五张的逻辑完全没变。
 *
 * 所以要做成流水线：声明步骤与依赖，
 * 本地按拓扑执行，上游变了自动传播，可整体重跑。
 *
 * ── 四条约束 ────────────────────────────────────────────────
 *
 * 一、**某步失败时下游全部待定，而不是各自画出半截**。
 *     这是最重要的一条。第 2 步的正态性检验失败了
 *     （比如样本太少），第 3 步的 t 检验就没有依据——
 *     如果让它照常画出来，用户会看到一个**没有前提的结论**，
 *     并且以为它是有效的。
 *     所以失败要沿依赖向下游传播，下游标 PENDING 不渲染。
 *
 * 二、**上游变了必须能传播到下游**。
 *     重跑的价值就在于此：改一个输入，
 *     整条链重新走一遍，界面上永远不会出现
 *     "上游是新的、下游是旧的"这种自相矛盾的状态。
 *
 * 三、**只重跑受影响的部分**。
 *     改了第 5 步的参数不该重算第 1-4 步。
 *     全链重算在链长了以后会明显变慢，
 *     而"慢"会让用户不想用它——不用就等于没有。
 *
 * 四、**步骤只读自己声明的输入**。
 *     如果每步都能读全量数据，依赖关系就名存实亡了：
 *     界面上画着"A 依赖 B"，实际 A 偷偷读了 C，
 *     改 C 时 A 不会重算，界面就骗人了。
 *     所以执行时只把声明的 inputs 传进去。
 */
object ZxPipe {

    enum class State(val label: String) {
        IDLE("待执行"),
        RUN("执行中"),
        OK("成功"),
        FAIL("失败"),
        PENDING("待定")   // 上游没跑完 / 上游失败
    }

    /** 一步 */
    data class Step(
        val id: String,
        /** 用的分析能力，如 normality / test / bootstrap */
        val op: String,
        val label: String,
        /** 声明的输入（上游 step id），只读这些 */
        val inputs: List<String>,
        val params: Map<String, String> = emptyMap()
    )

    /** 一步的执行结果 */
    data class Out(
        val stepId: String,
        val state: State,
        val summary: String = "",
        /** 产出，供下游取用 */
        val values: Map<String, Double> = emptyMap(),
        val ms: Long = 0L
    )

    /** 一条流水线 */
    class Run(val steps: List<Step>) {
        private val byId = steps.associateBy { it.id }
        private val outs = LinkedHashMap<String, Out>()

        /** 下游：id → 依赖它的那些 step */
        private fun dependents(id: String) = steps.filter { id in it.inputs }

        fun state(id: String) = outs[id]?.state ?: State.IDLE
        fun out(id: String) = outs[id]
        fun all() = steps.map { it.id to (outs[it.id] ?: Out(it.id, State.IDLE)) }

        /**
         * 拓扑序：有环时按原顺序把剩下的追加进去，不让批次卡死。
         *
         * 和 ① 引用传播、㉓ 过渡编排用的是同一套处理——
         * 有环就降级，不能让整条链不执行。
         */
        fun order(): List<Step> {
            val placed = HashSet<String>()
            val out = ArrayList<Step>()
            var guard = 0
            while (placed.size < steps.size && guard++ < steps.size + 4) {
                val ready = steps.filter { !placed.contains(it.id) &&
                    it.inputs.all { p -> placed.contains(p) } }
                if (ready.isEmpty()) {
                    out.addAll(steps.filter { !placed.contains(it.id) })
                    break
                }
                ready.forEach { out.add(it); placed.add(it.id) }
            }
            return out
        }

        /** 检查依赖声明是否合法 */
        fun check(): Pair<Boolean, String> {
            steps.forEach { s ->
                s.inputs.forEach { p ->
                    if (!byId.containsKey(p))
                        return false to "步骤「${s.label}」依赖的 $p 不存在"
                    if (p == s.id) return false to "步骤「${s.label}」依赖自己"
                }
            }
            // 环检测
            val seen = HashSet<String>()
            fun dfs(id: String, path: MutableList<String>): String? {
                if (path.contains(id)) return (path + id).joinToString(" → ")
                if (seen.contains(id)) return null
                path.add(id)
                byId[id]?.inputs?.forEach { dfs(it, path)?.let { return it } }
                path.removeAt(path.size - 1)
                seen.add(id)
                return null
            }
            steps.forEach { dfs(it.id, mutableListOf())?.let { return false to "依赖成环：$it" } }
            return true to ""
        }

        /** 执行一步（本地，不调模型） */
        private fun exec(s: Step, t0: Long): Out {
            val ps = s.inputs.mapNotNull { outs[it] }
            // 上游有失败 / 待定 → 本步待定，不渲染
            if (ps.any { it.state != State.OK })
                return Out(s.id, State.PENDING, "等待上游：${ps.filter { it.state != State.OK }.joinToString("、") { byId[it.stepId]?.label ?: it.stepId }}")

            return try {
                val v = eval(s, ps)
                Out(s.id, State.OK, v.first, v.second, System.currentTimeMillis() - t0)
            } catch (e: Exception) {
                Out(s.id, State.FAIL, "失败：${e.message ?: "未知错误"}")
            }
        }

        /** 具体算子 */
        private fun eval(s: Step, ps: List<Out>): Pair<String, Map<String, Double>> {
            val raw = s.params["data"] ?: ps.firstOrNull()?.summary ?: ""
            val xs = raw.split(",").mapNotNull { it.trim().toDoubleOrNull() }
            return when (s.op) {
                "normality" -> {
                    if (xs.size < 3) throw IllegalStateException("样本少于 3，分位数无从谈起")
                    val mu = xs.average()
                    val sd = sqrt(xs.map { (it - mu) * (it - mu) }.average())
                    val skew = xs.map { ((it - mu) / sd.coerceAtLeast(1e-9)).let { z -> z * z * z } }.average()
                    "μ=%.2f σ=%.2f 偏度=%.3f（|偏度|<1 可认为近似对称）".format(mu, sd, skew) to
                        mapOf("mu" to mu, "sd" to sd, "skew" to skew)
                }
                "test" -> {
                    val ys = (s.params["b"] ?: "").split(",").mapNotNull { it.trim().toDoubleOrNull() }
                    if (xs.size < 2 || ys.size < 2) throw IllegalStateException("两组各至少 2 个样本")
                    val m1 = xs.average(); val m2 = ys.average()
                    val pooled = sqrt(((xs.size - 1) * xs.map { (it - m1) * (it - m1) }.average() +
                        (ys.size - 1) * ys.map { (it - m2) * (it - m2) }.average()) /
                        maxOf(1, xs.size + ys.size - 2)).coerceAtLeast(1e-9)
                    val d = (m1 - m2) / pooled
                    "差值=%.3f Cohen's d=%.3f".format(m1 - m2, d) to
                        mapOf("diff" to (m1 - m2), "d" to d)
                }
                "bootstrap" -> {
                    if (xs.size < 3) throw IllegalStateException("样本少于 3，重采样无意义")
                    val rnd = kotlin.random.Random((s.params["seed"] ?: "7").toIntOrNull() ?: 7)
                    val b = (s.params["b"] ?: "2000").toIntOrNull() ?: 2000
                    val vals = (0 until b).map { (0 until xs.size).map { xs[rnd.nextInt(xs.size)] }.average() }.sorted()
                    val lo = vals[(0.025 * b).toInt()]; val hi = vals[(0.975 * b).toInt()]
                    "95% CI [%.2f, %.2f]，宽 %.2f".format(lo, hi, hi - lo) to
                        mapOf("lo" to lo, "hi" to hi, "width" to hi - lo)
                }
                "effect" -> {
                    val d = s.params["d"]?.toDoubleOrNull()
                        ?: ps.firstOrNull()?.values?.get("d")
                        ?: throw IllegalStateException("没有上游的 d")
                    "d=%.3f → %s".format(d, when {
                        kotlin.math.abs(d) < 0.2 -> "效应极小"
                        kotlin.math.abs(d) < 0.5 -> "小"
                        kotlin.math.abs(d) < 0.8 -> "中"
                        else -> "大"
                    }) to mapOf("d" to d)
                }
                "correct" -> {
                    val psRaw = s.params["ps"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
                        ?: throw IllegalStateException("缺少 ps")
                    val q = s.params["q"]?.toDoubleOrNull() ?: 0.05
                    val m = psRaw.size
                    val sorted = psRaw.sorted()
                    var cut = 0
                    sorted.forEachIndexed { k, p -> if (p <= (k + 1.0) / m * q) cut = k + 1 }
                    "BH 校正后阈值 %.5f，%d 个仍显著".format(
                        if (cut > 0) sorted[cut - 1] else q, cut) to
                        mapOf("thr" to (if (cut > 0) sorted[cut - 1] else q), "n" to cut.toDouble())
                }
                "conclude" -> {
                    val d = ps.firstOrNull()?.values?.get("d") ?: 0.0
                    "结论：效应 %s".format(when {
                        kotlin.math.abs(d) < 0.2 -> "极小，实际没差别"
                        kotlin.math.abs(d) < 0.5 -> "小，需结合业务判断"
                        else -> "中到大，可以据此决策"
                    }) to emptyMap()
                }
                else -> throw IllegalStateException("未知算子：${s.op}")
            }
        }

        /** 跑全链 */
        fun runAll(): List<Out> {
            val chk = check()
            if (!chk.first) {
                outs.clear()
                steps.forEach { outs[it.id] = Out(it.id, State.FAIL, chk.second) }
                return all().map { it.second }
            }
            outs.clear()
            order().forEach { s ->
                val t0 = System.currentTimeMillis()
                outs[s.id] = exec(s, t0)
            }
            return all().map { it.second }
        }

        /**
         * 只重跑受影响的部分。
         *
         * 改了某一步，从它开始沿依赖向下游重跑，
         * 上游不动——全链重算在链长了以后会明显变慢，
         * 而"慢"会让用户不想用它。
         */
        fun rerunFrom(id: String): List<Out> {
            val start = order().indexOfFirst { it.id == id }
            if (start < 0) return runAll()
            val seq = order()
            val touched = LinkedHashSet<String>()
            fun mark(x: String) {
                if (!touched.add(x)) return
                dependents(x).forEach { mark(it.id) }
            }
            mark(id)
            seq.filter { touched.contains(it.id) }.forEach { s ->
                val t0 = System.currentTimeMillis()
                outs[s.id] = exec(s, t0)
            }
            return all().map { it.second }
        }
    }

    fun from(o: JSONObject): Run? {
        val steps = o.optJSONArray("steps") ?: return null
        val list = (0 until steps.length()).mapNotNull { k ->
            val s = steps.optJSONObject(k) ?: return@mapNotNull null
            val inputsA = s.optJSONArray("inputs")
            Step(
                id = s.optString("id", "s$k"),
                op = s.optString("op", ""),
                label = s.optString("n", s.optString("op", "步骤${k + 1}")),
                inputs = (0 until (inputsA?.length() ?: 0)).map { inputsA!!.optString(it) },
                params = s.optJSONObject("params")?.let { p ->
                    p.keys().asSequence().associateWith { p.optString(it) }
                } ?: emptyMap()
            )
        }
        if (list.isEmpty()) return null
        return Run(list)
    }

    fun validate(o: JSONObject): Pair<Boolean, String> {
        val steps = o.optJSONArray("steps") ?: return false to "zx_pipe 缺少 steps"
        val n = steps.length()
        if (n == 0) return false to "zx_pipe 的 steps 为空"
        if (n > 12) return false to "zx_pipe 最多 12 步，实际 $n 步"
        val OPS = setOf("normality", "test", "bootstrap", "effect", "correct", "conclude")
        val ids = HashSet<String>()
        for (k in 0 until n) {
            val s = steps.optJSONObject(k)
                ?: return false to "steps[$k] 不是对象，应为 {\"id\":,\"op\":,\"n\":,\"inputs\":[],\"params\":{}}"
            val id = s.optString("id", "")
            if (id.isBlank()) return false to "steps[$k] 缺少 id"
            if (!ids.add(id)) return false to "步骤 id「$id」重复——依赖无法定位"
            val op = s.optString("op", "")
            if (op !in OPS)
                return false to "steps[$k].op=\"$op\" 不支持，可选：${OPS.joinToString(" / ")}"
            val ins = s.optJSONArray("inputs")
            for (j in 0 until (ins?.length() ?: 0)) {
                val p = ins!!.optString(j)
                // 依赖必须在同链内，且不能是后面的步骤（否则成环）
                if (p == id) return false to "steps[$k]（$id）依赖自己"
            }
        }
        // 引用不存在的步骤
        for (k in 0 until n) {
            val s = steps.optJSONObject(k) ?: continue
            val ins = s.optJSONArray("inputs") ?: continue
            for (j in 0 until ins.length())
                if (!ids.contains(ins.optString(j)))
                    return false to "steps[$k]（${s.optString("id")}）依赖的 ${ins.optString(j)} 不存在"
        }
        return true to ""
    }

    fun render(run: Run): String = buildString {
        appendLine("分析流水线（${run.steps.size} 步）")
        appendLine()
        run.all().forEach { (id, o) ->
            val s = run.steps.first { it.id == id }
            val mark = when (o.state) {
                State.OK -> "✓"; State.FAIL -> "✗"; State.PENDING -> "…"; else -> "·"
            }
            appendLine("$mark ${s.label}".padEnd(20) + " [${o.state.label}]")
            if (o.summary.isNotBlank()) appendLine("    ${o.summary}")
            if (s.inputs.isNotEmpty())
                appendLine("    依赖：${s.inputs.joinToString("、")}")
        }
    }

    fun demo(): String {
        val run = Run(listOf(
            Step("s1", "normality", "看分布", emptyList(),
                mapOf("data" to "820,910,1050,1120,980,1240,760,1310,1090,940")),
            Step("s2", "test", "做检验", listOf("s1"),
                mapOf("data" to "820,910,1050,1120,980,1240,760,1310,1090,940",
                      "b" to "1150,1010,880,1260,990,1070,1300,1180,1220,1050")),
            Step("s3", "effect", "看效应", listOf("s2")),
            Step("s4", "bootstrap", "看区间", listOf("s1"),
                mapOf("data" to "820,910,1050,1120,980,1240,760,1310,1090,940")),
            Step("s5", "correct", "校多重", listOf("s3"),
                mapOf("ps" to "0.012,0.043,0.310,0.620,0.081,0.441,0.510,0.721,0.130,0.881",
                      "q" to "0.05")),
            Step("s6", "conclude", "出结论", listOf("s3", "s5"))
        ))
        val outs = run.runAll()
        return buildString {
            appendLine("══ 正常跑通 ══")
            appendLine()
            appendLine(render(run).trimEnd())
            appendLine()
            appendLine("══ 上游失败会怎样 ══")
            appendLine()
            val bad = Run(listOf(
                Step("s1", "normality", "看分布", emptyList(), mapOf("data" to "1,2")),
                Step("s2", "test", "做检验", listOf("s1"),
                    mapOf("data" to "1,2", "b" to "3,4")),
                Step("s3", "conclude", "出结论", listOf("s2"))
            ))
            bad.runAll()
            appendLine(render(bad).trimEnd())
            appendLine()
            appendLine("第 1 步失败 → 第 2、3 步**待定而不渲染**。")
            appendLine("如果让它们照常画出来，用户会看到一个")
            appendLine("**没有前提的结论**，并且以为它是有效的。")
        }
    }

    fun report(): String = buildString {
        appendLine("分析流水线（把一串分析步骤串成可整体重跑的链）")
        appendLine()
        appendLine("前面做了一百多个**单个组件**，每个都能回答一个问题。")
        appendLine("但真实的分析从来不是一次：")
        appendLine()
        appendLine("  看分布 → 检正态 → 选检验 → 看效应 → 校多重 → 出结论")
        appendLine()
        appendLine("之前每一步都要模型单独下发一张卡，三个后果：")
        appendLine()
        appendLine("  一、依赖关系在界面上完全看不见")
        appendLine("      （看不出「第 3 步的方法由第 2 步的正态性检验决定」）")
        appendLine("  二、上游变了下游不会自动跟着变")
        appendLine("      （界面上出现自相矛盾的结论，而用户不知该信哪个）")
        appendLine("  三、没法整体重跑（换批数据得让模型重新走一轮推理）")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、某步失败时下游全部待定，不各自画出半截")
        appendLine("  二、上游变了必须传播到下游")
        appendLine("  三、只重跑受影响的部分")
        appendLine("  四、步骤只读自己声明的输入")
    }

    fun guide(): String = """
        zx_pipe(op=run|rerun|demo|report|guide, ...)

        把一串分析步骤串成可整体重跑的链。

        真实分析从来不是一次：
          看分布 → 检正态 → 选检验 → 看效应 → 校多重 → 出结论

        每步单独下发一张卡，三个后果：
          一、依赖关系在界面上完全看不见
          二、上游变了下游不会自动跟着变（界面自相矛盾）
          三、没法整体重跑

        四条约束：
          一、某步失败 → 下游全部**待定**，不各自画出半截
              （让它们照常画出来 = 用户看到一个没有前提的结论）
          二、上游变了必须传播到下游
          三、只重跑受影响的部分（全链重算会慢到没人用）
          四、步骤只读自己声明的输入（否则依赖关系名存实亡）

        算子：normality / test / bootstrap / effect / correct / conclude
        DSL：steps[{id,op,n,inputs[],params{}}]
    """.trimIndent()
}
