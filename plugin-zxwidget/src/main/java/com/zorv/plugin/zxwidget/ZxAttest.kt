package com.zorv.plugin.zxwidget

/**
 * 确认登记 —— 谁在什么时候、对什么状态做了确认。
 *
 * **为什么需要它**
 *
 * 第三十九批每个组件都有自己的 `attest()` 条，
 * 但那是**卡内**的、临时的：
 *
 *   交接清单里勾了"主人已签字"
 *   退租结算里勾了"房东已签字"
 *   设备归还里勾了"出租方已签字"
 *
 * 三张卡各记各的。而真实情况是**这三件事是同一次交接**——
 * 猫交出去、房子退掉、设备还回去，都是"责任从 A 到 B"。
 *
 * 更要紧的是：**卡内的勾是会变的**。
 * 用户点一下就从"已签字"变回"待签字"，
 * 而真出了争议，需要的是"当时到底谁签了、几点签的"。
 *
 * ── 贯穿的一条 ──────────────────────────────────────────────
 *
 * **确认之后只能追加异议，不能修改确认本身。**
 *
 * 这是它和"卡内勾选"最本质的区别：
 *
 *   勾选：随时可以改回去 → 事后说不清当时到底认没认
 *   登记：一旦确认就固化，反悔只能追加一条异议 → 两次都有记录
 *
 * 允许修改确认，等于允许事后抵赖——
 * 那这份登记就**没有任何证据价值**。
 *
 * ── 四条约束 ────────────────────────────────────────────────
 *
 * 一、**确认必须有对象快照**。
 *     只记"张三确认了"没用，要记"张三确认了【体重 4.8kg】"。
 *     没有快照的确认，事后双方对"当时是什么状态"照样吵。
 *
 * 二、**异议不覆盖原确认**。
 *     这直接来自上面那条：反悔追加一条，原确认还在。
 *     两份都在，才看得出"他先认了，后来又不认了"。
 *
 * 三、**同一人对同一对象只能确认一次**。
 *     重复确认会产生多条看似独立、实则重复的记录，
 *     数的时候会多算，看起来"两个人认了"实际是一个人认了两次。
 *     所以要去重，**且去重时要看出来它是重复的**。
 *
 * 四、**确认不能跨越"状态已变"**。
 *     这条最容易漏：A 确认时是 4.8kg，后来改成 4.5kg，
 *     如果 A 那条确认还挂着"4.8kg"，它其实已经失效了——
 *     但看起来仍然有效。**失效的确认比没有确认更危险。**
 *
 * ── 认不出的（诚实说明） ────────────────────────────────────
 *
 * 它不知道"签字的人是不是本人"，那是宿主鉴权的事。
 * 这里记的是"界面上谁点了那一下"。
 */
object ZxAttest {

    data class Record(
        val who: String,
        val what: String,   // 对象
        val snapshot: String, // 确认时的状态快照
        val seq: Int,
        val objections: MutableList<String> = mutableListOf()
    )

    private val recs = ArrayList<Record>()
    private var seq = 0

    /** 当前状态变了——所有对旧快照的确认都失效 */
    private val superseded = ArrayList<String>()

    fun confirm(who: String, what: String, snapshot: String): Pair<Boolean, String> {
        if (who.isBlank()) return false to "**缺 who（谁确认的）**"
        // 一、必须有快照
        if (what.isBlank())
            return false to "**缺 what（确认的对象）**——" +
                "**只记「张三确认了」没用，要记确认的是哪一样**"
        if (snapshot.isBlank())
            return false to "**缺 snapshot（确认时的状态）**——" +
                "**没有快照的确认，事后双方对「当时是什么状态」照样吵**"
        // 三、同一人对同一对象只能确认一次
        val dup = recs.indexOfFirst { it.who == who && it.what == what }
        if (dup >= 0) {
            val r = recs[dup]
            // 快照不同 → 状态变了，原确认失效
            return if (r.snapshot != snapshot) {
                false to "「$who」此前确认的是【${r.snapshot}】，现在是【$snapshot】——" +
                    "**状态变了，那条确认已经失效**，请重新确认而不是覆盖"
            } else {
                false to "「$who」已经确认过「$what」了（第 ${r.seq} 条）——" +
                    "**重复确认会产生多条看似独立、实则重复的记录**"
            }
        }
        recs.add(Record(who, what, snapshot, ++seq))
        return true to "第 $seq 条：$who 确认【$what = $snapshot】"
    }

    /** 二、异议不覆盖原确认 */
    fun object_(who: String, what: String, reason: String): Pair<Boolean, String> {
        val r = recs.firstOrNull { it.who == who && it.what == what }
            ?: return false to "「$who」还没确认过「$what」——**没确认就谈不上异议**"
        if (reason.isBlank())
            return false to "**缺 reason（异议理由）**——" +
                "**只说「不认」不说为什么，等于没说**"
        r.objections.add(reason)
        return true to "已在第 ${r.seq} 条上追加异议（**原确认保留**：【${r.snapshot}】）"
    }

    /** 四、状态变更 → 旧确认失效 */
    fun invalidate(what: String, newSnapshot: String): String {
        val hit = recs.filter { it.what == what && it.snapshot != newSnapshot }
        hit.forEach { superseded.add("第 ${it.seq} 条 ${it.who} 确认的【${it.snapshot}】") }
        return if (hit.isEmpty()) "「$what」没有受影响的确认"
        else buildString {
            appendLine("「$what」状态改为【$newSnapshot】，${hit.size} 条确认已失效：")
            hit.forEach { appendLine("  · $it") }
            appendLine("**失效的确认比没有确认更危险**——看起来还有效，实际已经不是那个状态了")
        }.trimEnd()
    }

    /** 双方都确认了才算达成共识 */
    fun agreed(what: String, parties: List<String>): Boolean {
        val r = recs.filter { it.what == what && it.objections.isEmpty() }
        return parties.all { p -> r.any { it.who == p } }
    }

    fun clear(): Int = recs.size.also { recs.clear(); superseded.clear(); seq = 0 }

    fun render(): String = buildString {
        appendLine("══ 确认登记 ══")
        appendLine()
        if (recs.isEmpty()) {
            appendLine("  还没有确认。")
            appendLine()
            appendLine("  第三十九批每个组件都有自己的 attest() 条，")
            appendLine("  但那是**卡内的、会变的**——点一下就从「已签字」变回「待签字」。")
            return@buildString
        }
        recs.forEach { r ->
            val dead = superseded.any { it.startsWith("第 ${r.seq} 条") }
            appendLine("  ${if (dead) "**失效**" else "第 ${r.seq} 条"}　${r.who} 确认【${r.what} = ${r.snapshot}】")
            r.objections.forEach { appendLine("    异议：$it") }
        }
        if (superseded.isNotEmpty()) {
            appendLine()
            appendLine("  已失效的确认：")
            superseded.forEach { appendLine("    · $it") }
        }
    }

    fun report(): String = buildString {
        appendLine("确认登记 —— 谁在什么时候、对什么状态做了确认")
        appendLine()
        appendLine("第三十九批每个组件都有自己的 attest() 条，")
        appendLine("但那是**卡内的、临时的**：点一下就从「已签字」变回「待签字」。")
        appendLine()
        appendLine("而真出了争议，需要的是「当时到底谁签了、认的是哪个状态」。")
        appendLine()
        appendLine("**确认之后只能追加异议，不能修改确认本身。**")
        appendLine()
        appendLine("这是它和「卡内勾选」最本质的区别：")
        appendLine()
        appendLine("  勾选：随时可以改回去 → 事后说不清当时到底认没认")
        appendLine("  登记：一旦确认就固化，反悔只能追加一条异议 → 两次都有记录")
        appendLine()
        appendLine("**允许修改确认，等于允许事后抵赖**——")
        appendLine("那这份登记就没有任何证据价值。")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、**确认必须有对象快照**")
        appendLine("  二、**异议不覆盖原确认**")
        appendLine("  三、**同一人对同一对象只能确认一次**")
        appendLine("  四、**确认不能跨越「状态已变」**")
        appendLine()
        appendLine("第四条最容易漏：A 确认时是 4.8kg，后来改成 4.5kg，")
        appendLine("如果 A 那条确认还挂着「4.8kg」，它其实已经失效了——")
        appendLine("但看起来仍然有效。")
        appendLine("**失效的确认比没有确认更危险。**")
    }

    fun demo(): String = buildString {
        appendLine("══ 一次寄养交接 ══")
        appendLine()
        appendLine("  猫交出去时称重 4.8kg。")
        appendLine()
        confirm("主人", "体重", "4.8kg").let { (ok, m) ->
            appendLine("  主人确认：${if (ok) "✓" else "✗"} $m") }
        confirm("寄养家庭", "体重", "4.8kg").let { (ok, m) ->
            appendLine("  寄养家庭确认：${if (ok) "✓" else "✗"} $m") }
        appendLine()
        appendLine("  达成共识：${agreed("体重", listOf("主人", "寄养家庭"))}")
        appendLine()
        appendLine("  ── 一、缺快照会被拦 ──")
        appendLine()
        confirm("主人", "猫粮", "").let { (ok, m) ->
            appendLine("  ${if (ok) "✓" else "✗"} $m") }
        appendLine()
        appendLine("  ── 三、重复确认会被拦 ──")
        appendLine()
        confirm("主人", "体重", "4.8kg").let { (ok, m) ->
            appendLine("  ${if (ok) "✓" else "✗"} $m") }
        appendLine()
        appendLine("  ── 二、异议不覆盖原确认 ──")
        appendLine()
        object_("寄养家庭", "体重", "接回来是 4.5kg，不是 4.8kg").let { (ok, m) ->
            appendLine("  ${if (ok) "✓" else "✗"} $m") }
        appendLine("  原确认仍在：【体重 = 4.8kg】")
        appendLine()
        appendLine("  ── 四、状态变了，旧确认失效 ──")
        appendLine()
        appendLine("  " + invalidate("体重", "4.5kg").replace("\n", "\n  "))
        appendLine()
        appendLine("  这时候不能说「双方都确认了」——")
        appendLine("  他们确认的是 4.8kg，而现在是 4.5kg。")
    }

    fun guide(): String = """
        zx_attest(op=confirm|object|invalidate|agreed|list|report|demo|clear|guide)

        确认登记：谁在什么时候、对什么状态做了确认。

        第三十九批每个组件都有自己的 attest() 条，
        但那是**卡内的、会变的**——点一下就从"已签字"变回"待签字"。"
        真出了争议，需要的是"当时到底谁签了、认的是哪个状态"。"

        **确认之后只能追加异议，不能修改确认本身。**
        允许修改确认，等于允许事后抵赖。

        四条约束：
          一、**确认必须有对象快照**
          二、**异议不覆盖原确认**
          三、**同一人对同一对象只能确认一次**
          四、**确认不能跨越"状态已变"**（失效的确认比没有确认更危险）

          op=confirm <谁> <对象> <状态快照>
          op=object <谁> <对象> <异议理由>
          op=invalidate <对象> <新状态>   状态变更，旧确认失效
          op=agreed <对象> <参与方,逗号分隔>
          op=demo  演示"为什么允许改确认就等于允许抵赖"
    """.trimIndent()

    fun handle(op: String, a: String, b: String, c: String): String = when (op) {
        "confirm" -> confirm(a, b, c).let { (ok, m) -> (if (ok) "✓ " else "✗ ") + m }
        "object" -> object_(a, b, c).let { (ok, m) -> (if (ok) "✓ " else "✗ ") + m }
        "invalidate" -> invalidate(a, b)
        "agreed" -> {
            val ps = b.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (ps.isEmpty()) "用法：agreed <对象> <参与方,逗号分隔>"
            else if (agreed(a, ps)) "「$a」双方都已确认且无异议"
            else "「$a」**还没达成共识**——还差一方，或者有人提了异议"
        }
        "list" -> render()
        "render" -> render()
        "report" -> report()
        "demo" -> demo()
        "clear" -> "已清空 ${clear()} 条"
        else -> guide()
    }
}
