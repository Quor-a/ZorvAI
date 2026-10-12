package com.zorv.plugin.zxwidget

/**
 * 事务冲突合并 —— 两个事务改了同一张卡时怎么并。
 *
 * ⑪ 跨卡事务保证"一组改动全成或全回滚"，
 * 但它假设**同一时刻只有一个事务在改**。
 *
 * 真实情况是会撞车的：
 *
 *   事务 A：改表格第 3 行（用户手动改的）
 *   事务 B：同一张表格，AI 批量修订第 3 行
 *   两个都 begin 了，都要提交
 *
 * 现在的行为是后提交的覆盖先提交的，
 * 先提交那个事务的 savepoint 白记了——
 * 用户以为自己改过了，其实被冲掉了，而且没有任何提示。
 *
 * **静默覆盖比失败更糟**：失败用户知道要重试，
 * 静默覆盖他根本不知道发生了什么。
 *
 * 三种情况三种处理，不能一律拒绝也不能一律合并：
 *
 *   一、**改的是不同字段** → 自动合并。
 *       两条改动不相干，合并没有任何歧义。
 *
 *   二、**改的是同一字段，值相同** → 幂等，直接过。
 *       两个事务做了同一件事，不需要提示。
 *
 *   三、**改的是同一字段，值不同** → 真冲突，必须让人判。
 *       自动选一个（哪怕按时间戳）都是在替用户决定，
 *       而用户才是知道哪个对的人。
 *
 * 关键：真冲突时**保留两条改动的值**，
 * 只报"这两条冲突了"，不猜该留哪个。
 */
object ZxTxMerge {

    enum class Kind { AUTO_MERGED, IDEMPOTENT, CONFLICT }

    data class Step(val cardId: String, val path: String, val value: String)

    data class Outcome(
        val kind: Kind,
        /** 合并后的全部步骤 */
        val merged: List<Step> = emptyList(),
        /** 真冲突的条目 */
        val conflicts: List<Pair<Step, Step>> = emptyList(),
        val note: String = ""
    )

    /** 两个事务的改动是否落在同一张卡的同一路径 */
    private fun key(s: Step) = s.cardId + "|" + s.path

    fun merge(a: List<Step>, b: List<Step>): Outcome {
        val map = LinkedHashMap<String, Step>()
        val conflicts = mutableListOf<Pair<Step, Step>>()
        a.forEach { map[key(it)] = it }
        var sameValue = 0
        b.forEach { s ->
            val prev = map[key(s)]
            when {
                prev == null -> map[key(s)] = s
                prev.value == s.value -> sameValue++      // 幂等，留谁都一样
                else -> conflicts.add(prev to s)          // 真冲突
            }
        }
        return when {
            conflicts.isNotEmpty() -> Outcome(
                Kind.CONFLICT,
                conflicts = conflicts,
                note = "${conflicts.size} 处真冲突——同一路径两个不同值，必须让人判。" +
                       "自动选一个（哪怕按时间戳）都是在替用户决定。" +
                       "已保留两条值，未决定保留哪个。")
            sameValue > 0 -> Outcome(
                Kind.IDEMPOTENT, map.values.toList(),
                note = "$sameValue 处两个事务改成了同一个值——幂等，直接过。")
            else -> Outcome(
                Kind.AUTO_MERGED, map.values.toList(),
                note = "改动落在不同路径，自动合并，无歧义。")
        }
    }

    /** 冲突条目给人看的描述 */
    fun describe(p: Pair<Step, Step>): String {
        val (x, y) = p
        return "${x.cardId} 的 ${x.path}：\n" +
               "  一方改成「${x.value}」\n" +
               "  另一方改成「${y.value}」"
    }

    fun report(x: Outcome): String = buildString {
        when (x.kind) {
            Kind.AUTO_MERGED -> appendLine("✓ 自动合并（${x.merged.size} 步）")
            Kind.IDEMPOTENT -> appendLine("✓ 幂等（${x.merged.size} 步）")
            Kind.CONFLICT -> {
                appendLine("✗ ${x.conflicts.size} 处真冲突：")
                x.conflicts.forEach {
                    appendLine("  " + describe(it).replace("\n", "\n  "))
                }
            }
        }
        appendLine()
        appendLine(x.note)
    }

    fun cases(): String = buildString {
        appendLine("三种情况（不能一律拒绝也不能一律合并）：")
        appendLine()
        val mk = { c: String, p: String, v: String -> Step(c, p, v) }
        val a1 = listOf(mk("c1", "rows[3].v[1]", "2400"))
        val b1 = listOf(mk("c1", "rows[7].v[0]", "已发货"))
        appendLine("一、不同字段")
        appendLine(merge(a1, b1).note)
        appendLine()
        val a2 = listOf(mk("c1", "rows[3].v[1]", "2400"))
        val b2 = listOf(mk("c1", "rows[3].v[1]", "2400"))
        appendLine("二、同字段同值")
        appendLine(merge(a2, b2).note)
        appendLine()
        val a3 = listOf(mk("c1", "rows[3].v[1]", "2400"))
        val b3 = listOf(mk("c1", "rows[3].v[1]", "1800"))
        appendLine("三、同字段不同值")
        appendLine(report(merge(a3, b3)).trimEnd())
    }

    fun guide(): String = """
        zx_txmerge(op=merge|cases|guide, a?, b?)

        两个事务改了同一张卡时怎么并。

        ⑪ 跨卡事务保证「一组改动全成或全回滚」，
        但它假设同一时刻只有一个事务在改：

          事务 A：改表格第 3 行（用户手动改的）
          事务 B：同一张表格，AI 批量修订第 3 行
          两个都 begin 了，都要提交

        现在的行为是后提交的覆盖先提交的，
        先提交那个事务的 savepoint 白记了——
        用户以为自己改过了，其实被冲掉了，而且没有任何提示。

        静默覆盖比失败更糟：失败用户知道要重试，
        静默覆盖他根本不知道发生了什么。

        三种情况三种处理，不能一律拒绝也不能一律合并：
          一、不同字段 → 自动合并（无歧义）
          二、同一字段值相同 → 幂等，直接过
          三、同一字段值不同 → 真冲突，必须让人判

        真冲突时保留两条值，只报冲突，不猜该留哪个——
        自动选一个（哪怕按时间戳）都是在替用户决定，
        而用户才是知道哪个对的人。
    """.trimIndent()
}
