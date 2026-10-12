package com.zorv.plugin.zxwidget

import org.json.JSONObject
import kotlin.math.max

/**
 * DSL 错题本 —— 记住"模型在这台设备上反复写错的地方"。
 *
 * **为什么现在才做**
 *
 * 前面已经有三道防线，但都在**同一轮里**：
 *
 *   ⑬ DSL 自愈    → 写错了，本地修（事后）
 *   ㉙ 渲染回执    → 画完了，告诉你哪些字段没被用到（事后）
 *   ZxDsl 校验    → 下发时拦下（事中）
 *
 * 三道都在处理"这一次"。但真实情况是**模型会反复犯同一个错**：
 *
 *   同一个模型写 zx_coupon，十次里有八次把 8.5 折写成 v:8.5
 *   同一个模型写 zx_commute，总是漏 walk
 *   同一个模型写 zx_sizing，总是只给 chest 不给 waist
 *
 * 每轮都等它写完、校验拦下、它再改一遍——**一次改错就是一次完整推理**。
 * 而这完全可以提前说一句："你上次在这里栽过，v 要传 0-1 不是 8.5"。
 *
 * ── 它做的事 ────────────────────────────────────────────────
 *
 * 记三件事：哪个组件、哪个字段、什么错。
 * 下次模型**要下发同一组件之前**，给一句预警。
 *
 * 关键在"之前"——事中拦下已经晚了一步，那一步的推理已经花掉了。
 *
 * ── 四条约束 ────────────────────────────────────────────────
 *
 * 一、**只记"能被一条规则说清"的错误**。
 *     语义性错误（引用了不存在的卡）记下来没用——
 *     下次上下文不一样，同样的话可能是对的。
 *     只记机械错误：单位、量纲、取值范围、必填字段。
 *     这条做废了整个机制就是噪音。
 *
 * 二、**同样的错要能合并计数，不能每次都当新错**。
 *     十次同样的错记十条，预警里就会重复十遍同一句话。
 *     模型看到重复会开始忽略——**被忽略的预警不如不预警**。
 *
 * 三、**要给"怎么改"而不只是"错了"**。
 *     "v 不合法"下一次还是不知道该传什么。
 *     "v 要在 0-1，你上次传了 8.5"才是有用的。
 *     所以每条错题都带 fix 建议，且 fix 直接取自校验的失败原因。
 *
 * 四、**会过期**。
 *     模型会升级、prompt 会调整，三个月前的高频错现在可能已经不犯了。
 *     有衰减，且样本太少的（1 次）不给预警——
 *     **一次不算模式，三次才算。**
 *
 * ── 认不出的（诚实说明） ────────────────────────────────────
 *
 * 它认不出"模型换了个新错法"。只有犯过第二次的错才会被预警，
 * 第一次仍然要走"校验拦下 → 自愈或重写"。
 * 所以它是**降低重复犯错的成本**，不是消除犯错。
 */
object ZxMistake {

    /** 错误分类——只有这四类会被记，其余一律不记 */
    enum class Kind(val label: String) {
        RANGE("取值范围"), UNIT("单位量纲"),
        REQUIRED("必填字段"), ENUM("取值枚举")
    }

    data class Entry(
        val component: String,
        val field: String,
        val kind: Kind,
        var sample: String,      // 当时传了什么
        val fix: String,         // 该怎么改
        var count: Int = 1,
        var lastSeen: Long = 0L
    ) {
    }

    private val table = LinkedHashMap<String, Entry>()
    private var clock = 0L

    /* ── 记录 ──────────────────────────────────────────────── */

    /**
     * 记一次错误。
     * @return 是否记下了（认不出类别的返回 false，不记）
     */
    fun record(component: String, reason: String, dsl: String = ""): Boolean {
        val k = classify(reason) ?: return false
        val field = fieldOf(reason)
        val sample = sampleOf(field, dsl)
        val fp = "$component|$field|${k.name}"
        clock++
        val old = table[fp]
        if (old != null) {
            // 二、同样的错合并计数，不重复记
            old.count++
            old.lastSeen = clock
            if (old.sample.isBlank() && sample.isNotBlank()) old.sample = sample
        } else {
            table[fp] = Entry(component, field, k, sample, reason, 1, clock)
        }
        return true
    }

    /** 从校验失败原因里认出类别；认不出返回 null */
    private fun classify(reason: String): Kind? {
        val r = reason.replace(" ", "")
        return when {
            r.contains("超出0-") || r.contains("超出1-") ||
                r.contains("超出0.") || r.contains("必须为0-") ||
                r.contains("只在0-") || r.contains("在0-1") ||
                r.contains("0-100") || r.contains("超出0-24") ||
                r.contains("只能为0或1") || r.contains("必须是0-")
                -> Kind.RANGE

            r.contains("单位") || r.contains("传成了") ||
                r.contains("超出100年") || r.contains("超过10万") ||
                r.contains("超过一年") || r.contains("超过30天") ||
                r.contains("%）多半是单位")
                -> Kind.UNIT

            r.contains("缺少") || r.contains("没有") && r.contains("字段") ||
                r.contains("缺失")
                -> Kind.REQUIRED

            r.contains("只认") || r.contains("不在四类之内") ||
                r.contains("不在可接受") || r.contains("非法") ||
                r.contains("不在") && r.contains("名单")
                -> Kind.ENUM

            else -> null
        }
    }

    private fun fieldOf(reason: String): String {
        val m = Regex("""([A-Za-z_][A-Za-z0-9_]*)""").find(reason)
        val raw = m?.value ?: "?"
        return when (raw) {
            "zx_" -> "?"
            else -> raw
        }
    }

    private fun sampleOf(field: String, dsl: String): String {
        if (dsl.isBlank() || field == "?") return ""
        val m = Regex(""""$field"\s*:\s*([^,}\]]+)""").find(dsl)
        return m?.groupValues?.get(1)?.trim()?.trim('"') ?: ""
    }

    /* ── 预警 ──────────────────────────────────────────────── */

    /**
     * 下发某组件之前，给一句预警。
     * 没有值得说的就返回 null——**不要为了显得有用而凑一句**。
     */
    fun warnFor(component: String): String? {
        val hits = table.values.filter { it.component == component }
        // 四、一次不算模式，三次才算
        val solid = hits.filter { it.count >= 2 }
        if (solid.isEmpty()) return null
        val sorted = solid.sortedByDescending { it.count }
        val top = sorted.take(2)
        return buildString {
            append("写 $component 之前提醒：这台设备上你犯过 ${solid.size} 类错，")
            append("最频繁的 ${top.size} 条——")
            top.forEachIndexed { i, e ->
                if (i > 0) append("；")
                append("${e.field}（${e.kind.label}）已错 ${e.count} 次")
                if (e.sample.isNotBlank()) append("，上次传了 ${e.sample}")
            }
            append("。　${top.first().fix}")
        }
    }

    /** 全部记录（按次数降序） */
    fun list(): List<Entry> = table.values.sortedByDescending { it.count }

    /** 衰减：把很久没犯的、且次数少的清掉 */
    fun decay(now: Long = clock, halfLife: Long = 30): Int {
        var n = 0
        val it = table.entries.iterator()
        while (it.hasNext()) {
            val (_, e) = it.next()
            val age = now - e.lastSeen
            val keep = e.count.toDouble() / (1 shl (age / max(1, halfLife)).toInt()
                .coerceAtMost(8))
            if (keep < 0.5) { it.remove(); n++ }
        }
        return n
    }

    fun clear(): Int = table.size.also { table.clear() }

    /* ── 与自愈、回执的接口 ─────────────────────────────────── */

    /** 校验失败时调一次：先试着记，再返回要不要自愈 */
    fun onFail(component: String, reason: String, dsl: String): Pair<Boolean, String?> {
        val remembered = record(component, reason, dsl)
        return remembered to warnFor(component)
    }

    fun render(): String = buildString {
        val all = list()
        appendLine("══ DSL 错题本 ══")
        appendLine()
        if (all.isEmpty()) {
            appendLine("  还没有记录。")
            appendLine()
            appendLine("  校验失败且能被一条规则说清时会自动记下。")
            appendLine("  语义性错误（比如引用了不存在的卡）不记——")
            appendLine("  下次上下文不一样，同样的话可能是对的。")
            return@buildString
        }
        appendLine("  共 ${all.size} 类（合并计数后）")
        appendLine("  累计 ${all.sumOf { it.count }} 次")
        appendLine()
        all.take(10).forEach {
            appendLine("  ${it.component} · ${it.field}（${it.kind.label}）　×${it.count}")
            if (it.sample.isNotBlank()) appendLine("      上次传了：${it.sample}")
            appendLine("      怎么改：${it.fix.take(88)}")
        }
        appendLine()
        appendLine("  ── 四类之外的错误一律不记 ──")
        Kind.values().forEach { appendLine("    · ${it.label}") }
    }

    fun report(): String = buildString {
        appendLine("DSL 错题本 —— 记住「模型在这台设备上反复写错的地方」")
        appendLine()
        appendLine("前面已经有三道防线，但都在**同一轮里**：")
        appendLine()
        appendLine("  ⑬ DSL 自愈    → 写错了，本地修（事后）")
        appendLine("  ㉙ 渲染回执    → 画完了，告诉你哪些字段没被用到（事后）")
        appendLine("  ZxDsl 校验    → 下发时拦下（事中）")
        appendLine()
        appendLine("三道都在处理「这一次」。但真实情况是**模型会反复犯同一个错**：")
        appendLine()
        appendLine("  写 zx_coupon，十次里八次把 8.5 折写成 v:8.5")
        appendLine("  写 zx_commute，总是漏 walk")
        appendLine("  写 zx_sizing，总是只给 chest 不给 waist")
        appendLine()
        appendLine("每轮都等它写完、校验拦下、它再改一遍——")
        appendLine("**一次改错就是一次完整推理**。")
        appendLine("而这完全可以提前说一句：")
        appendLine("「你上次在这里栽过，v 要传 0-1 不是 8.5」。")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、**只记「能被一条规则说清」的错误**")
        appendLine("  二、**同样的错要能合并计数**")
        appendLine("  三、**要给「怎么改」而不只是「错了」**")
        appendLine("  四、**会过期**，且一次不算模式、两次才算")
        appendLine()
        appendLine("第二条最容易做废：十次同样的错记十条，")
        appendLine("预警里就会重复十遍同一句话。")
        appendLine("**模型看到重复会开始忽略——被忽略的预警不如不预警。**")
        appendLine()
        appendLine("它认不出「模型换了个新错法」。")
        appendLine("只有犯过第二次的错才会被预警，")
        appendLine("第一次仍然要走「校验拦下 → 自愈或重写」。")
        appendLine("所以它是**降低重复犯错的成本**，不是消除犯错。")
    }

    fun demo(): String = buildString {
        appendLine("══ 错题本是怎么工作的 ══")
        appendLine()
        appendLine("第 1 次：模型写 zx_coupon，v:8.5")
        appendLine("  校验拦下：v=8.5 是折扣率，**必须在 0-1 之间**")
        appendLine("  记下：zx_coupon · v（取值范围）×1")
        appendLine("  不给预警——**一次不算模式**")
        appendLine()
        appendLine("第 2 次：又写 v:8.5")
        appendLine("  记下：×2（合并，不是新增一条）")
        appendLine()
        appendLine("第 3 次：模型又要写 zx_coupon")
        appendLine("  **下发之前**给预警：")
        appendLine("    「这台设备上你犯过 1 类错，最频繁的 1 条——")
        appendLine("     v（取值范围）已错 2 次，上次传了 8.5。")
        appendLine("     v=8.5 是折扣率，必须在 0-1 之间」")
        appendLine()
        appendLine("这一步省下的是**一次完整推理**。")
        appendLine()
        appendLine("── 四类之外的不记 ──")
        Kind.values().forEach { appendLine("  · ${it.label}") }
        appendLine()
        appendLine("语义性错误不记：引用了不存在的卡、")
        appendLine("数据本身不对——下次上下文不一样，同样的话可能是对的。")
    }

    fun guide(): String = """
        zx_mistake(op=warn|list|render|report|demo|decay|clear|guide)

        DSL 错题本：记住"模型在这台设备上反复写错的地方"。"

        前面三道防线都在**同一轮里**：
          ⑬ DSL 自愈    → 写错了，本地修（事后）
          ㉙ 渲染回执    → 画完了，告诉你哪些字段没被用到（事后）
          ZxDsl 校验    → 下发时拦下（事中）

        但真实情况是**模型会反复犯同一个错**。
        每轮都等它写完、校验拦下、它再改一遍——
        **一次改错就是一次完整推理**。

        四条约束：
          一、**只记「能被一条规则说清」的错误**
          二、**同样的错要能合并计数**
          三、**要给「怎么改」而不只是「错了」**
          四、**会过期**，且一次不算模式、两次才算

          op=warn    <组件名> 下发前给预警
          op=list    列出全部记录
          op=demo    演示一次完整的"三次才预警"
    """.trimIndent()

    /** 给 AI_TOOL 用：把 JSON 参数包成调用 */
    fun handle(op: String, arg: String): String = when (op) {
        "warn" -> warnFor(arg) ?: "没有值得预警的——要么没犯过错，" +
            "要么只犯过一次（**一次不算模式**）"
        "list" -> render()
        "render" -> render()
        "report" -> report()
        "demo" -> demo()
        "decay" -> "衰减掉 ${decay()} 条很久没犯的"
        "clear" -> "已清空 ${clear()} 条"
        else -> guide()
    }
}
