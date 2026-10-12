package com.zorv.plugin.zxwidget

/**
 * 渲染偏好学习 —— 让"该画哪个组件"这件事随用户习惯收敛。
 *
 * ⑧ 渲染决策路由解决的是"**这个条件下哪个能用**"：
 * 离线降级、token 不够降级、窄屏降级。
 * ⑩能力协商 2.0 解决的是"**这个 token 预算下值不值得画**"。
 *
 * 但两者都只看**客观条件**，不看**这个人**。
 * 真实情况是两个人面对同一份数据会想要不同的东西：
 *
 *   有人每次都要表格（要复制、要核对数字）
 *   有人每次都要图表（要看趋势、看形状）
 *
 * 现在每一轮模型都要重新猜，猜错一次就是一次往返：
 * 它画了图，用户说"给我表格"，再画一遍。
 *
 * 偏好学习要做的不是"替模型决定"，
 * 而是**在模型问"画什么"时给出一个带证据的建议**。
 * 模型仍然可以无视它——用户明确说了要什么的时候，
 * 明确指令永远优先于统计。
 *
 * 三处必须想清楚，不然会学歪：
 *
 *   一、**接受不等于喜欢**。用户没退回也可能是懒得说。
 *       所以信号分三档权重：明确指定 > 用了（点了/复制了） > 没退回。
 *       "没退回"只给很低的权重。
 *
 *   二、**要有衰减**。三个月前喜欢表格不代表现在还喜欢。
 *       老的观测权重按半衰期下降，否则偏好会僵死在第一次。
 *
 *   三、**样本不足时不给建议**。三次观测就断言"用户偏好图表"
 *       是在编故事。低于阈值要明说"样本不足"，让模型自己决定。
 */
object ZxPref {

    enum class Signal(val w: Float, val label: String) {
        /** 用户明确说了要这个组件 */
        ASKED(3.0f, "明确指定"),
        /** 用户用了它：点了、复制了、展开了 */
        USED(1.5f, "使用了"),
        /** 只是没退回——可能只是懒得说 */
        ACCEPTED(0.4f, "未退回"),
        /** 用户明确退回/要求换掉 */
        REJECTED(-2.5f, "被退回")
    }

    data class Obs(val component: String, val signal: Signal, val t: Long)

    private val obs = ArrayDeque<Obs>(400)
    /** 半衰期：14 天。老观测权重下降，否则偏好会僵死在第一次 */
    private const val HALF_LIFE_MS = 14L * 24 * 3600 * 1000
    /** 低于这个样本量不给建议——三次观测就断言偏好是在编故事 */
    private const val MIN_SAMPLES = 5
    private const val MAX_OBS = 400

    fun record(component: String, signal: Signal) {
        obs.addLast(Obs(component, signal, System.currentTimeMillis()))
        while (obs.size > MAX_OBS) obs.removeFirst()
    }

    /** 时间衰减权重 */
    private fun decay(t: Long, now: Long): Double {
        val age = (now - t).coerceAtLeast(0)
        return Math.pow(0.5, age.toDouble() / HALF_LIFE_MS)
    }

    /**
     * 对一组候选组件打分。返回排序后的建议。
     * 样本不足时 ok=false，让模型自己决定而不是被误导。
     */
    fun rank(candidates: List<String>): Pair<Boolean, List<Pair<String, Double>>> {
        val now = System.currentTimeMillis()
        val scored = candidates.map { c ->
            val rel = obs.filter { it.component == c }
            val s = rel.sumOf { it.signal.w.toDouble() * decay(it.t, now) }
            c to s
        }.sortedByDescending { it.second }
        val n = obs.count { it.component in candidates }
        return (n >= MIN_SAMPLES) to scored
    }

    /**
     * 给模型的建议文本。刻意写清"这是统计，不是指令"。
     */
    fun advise(candidates: List<String>, intent: String = ""): String {
        val (enough, scored) = rank(candidates)
        if (candidates.isEmpty()) return "没有候选组件"
        val n = obs.count { it.component in candidates }
        if (!enough)
            return "样本不足（${n}/${MIN_SAMPLES} 次观测）——不给建议，请自行决定。\n" +
                   "三次观测就断言「用户偏好某个组件」是在编故事。"
        val top = scored.first()
        val second = scored.getOrNull(1)
        val gap = if (second != null) top.second - second.second else Double.MAX_VALUE
        return buildString {
            appendLine("基于 $n 次观测（半衰期 14 天）：")
            scored.take(4).forEachIndexed { i, (c, s) ->
                appendLine("  ${i + 1}. $c  ${"%.2f".format(s)}")
            }
            appendLine()
            if (gap < 0.5)
                appendLine("前两名差距仅 ${"%.2f".format(gap)}——偏好不明显，两种都可以，别硬选。")
            else
                appendLine("建议 ${top.first}。" +
                           (if (intent.isNotBlank()) "（本轮意图：$intent）" else ""))
            appendLine()
            appendLine("这是统计不是指令：用户明确说了要什么的时候，明确指令优先。")
        }
    }

    /** 单组件的历史画像 */
    fun profile(component: String): String {
        val now = System.currentTimeMillis()
        val rel = obs.filter { it.component == component }
        if (rel.isEmpty()) return "$component：无观测记录"
        val bySig = rel.groupBy { it.signal }
        return buildString {
            appendLine("$component：${rel.size} 次观测")
            bySig.forEach { (s, list) ->
                appendLine("  ${s.label} × ${list.size}")
            }
            val score = rel.sumOf { it.signal.w.toDouble() * decay(it.t, now) }
            appendLine("  衰减后得分 ${"%.2f".format(score)}")
            val last = rel.maxOf { it.t }
            appendLine("  最近一次 ${(now - last) / 86400000} 天前")
        }
    }

    fun summary(): String = buildString {
        if (obs.isEmpty()) { appendLine("还没有观测记录。"); return@buildString }
        val grouped = obs.groupBy { it.component }
        appendLine("渲染偏好（共 ${obs.size} 次观测，半衰期 14 天）")
        grouped.entries.sortedByDescending {
            it.value.sumOf { o -> o.signal.w.toDouble() * decay(o.t, System.currentTimeMillis()) }
        }.forEach { (c, list) ->
            val s = list.sumOf { o -> o.signal.w.toDouble() * decay(o.t, System.currentTimeMillis()) }
            appendLine("  $c  ${"%.2f".format(s)}  (${list.size} 次)")
        }
        appendLine()
        appendLine("⑧ 路由解决「这个条件下哪个能用」，")
        appendLine("⑩协商 2.0 解决「这个 token 预算下值不值得画」，")
        appendLine("但两者都只看客观条件，不看这个人。")
        appendLine("这个模块补的是「这个人习惯要什么」。")
    }

    fun clear() = obs.clear()
    fun size() = obs.size

    fun guide(): String = """
        zx_pref(op=advise|profile|record|summary|clear|guide, cands?, component?, signal?)

        渲染偏好学习：让「该画哪个组件」随用户习惯收敛。

        ⑧ 路由解决「这个条件下哪个能用」，
        ⑩协商 2.0 解决「这个 token 预算下值不值得画」。
        但两者都只看客观条件，不看这个人：

          有人每次都要表格（要复制、要核对数字）
          有人每次都要图表（要看趋势、看形状）

        现在每一轮模型都要重新猜，猜错一次就是一次往返：
        它画了图，用户说「给我表格」，再画一遍。

        偏好学习不是替模型决定，而是给出带证据的建议。
        模型仍然可以无视——用户明确说了要什么的时候，
        明确指令永远优先于统计。

        三处必须想清楚：
          一、接受不等于喜欢——用户没退回可能只是懒得说。
              信号分四档：明确指定 3.0 / 使用了 1.5 /
              未退回 0.4 / 被退回 -2.5
          二、要有衰减——三个月前喜欢不代表现在还喜欢。
              半衰期 14 天，否则偏好会僵死在第一次
          三、样本不足时不给建议——三次观测就断言偏好是在编故事
    """.trimIndent()
}
