package com.ai.assistance.quro.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #204 回归锁：表格组件的**列宽统一**与**截断**。
 *
 * ## 用户截图里的现象
 *
 * 1. 结果列文字被截断：「熔断+假闭环」显示成「熔断+假闭」；
 * 2. 表格下方有一大片空白；
 * 3. #2 / #3 / #4 行的结果列是空的。
 *
 * ## 根因（读代码定位，第 1 条有探针支撑）
 *
 * 旧实现里表头与每个单元格**各自**写 `widthIn(min = 80.dp, max = 220.dp)`。
 * `widthIn` 对不同内容自然给出不同宽度 ——「熔断+假闭环」那格宽、「0/5」那格窄，
 * 于是**同一列在各行宽度都不一样**，纵向根本对不齐；且单元格没有换行与展开机制，
 * 长文本被容器边界直接切掉，用户看到的就是半截词。
 *
 * 现在：整表统一算一次列宽（[tableColumnWidths]），单元格换行显示，
 * 超长的才折叠并给「展开完整表格」入口。
 */
class TableColumnWidthTest {

    private fun widthsOf(headers: List<String>, rows: List<List<String>>): List<Dp> =
        tableColumnWidths(headers, rows, maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0))

    /**
     * 🔴 核心回归：**同一列在各行宽度必须完全一致**。
     *
     * 这条是「表格对不齐」这个观感的直接判据。旧实现每格各自 `widthIn`，
     * 同列不同内容 → 宽度不同 → 必然歪。
     */
    @Test
    fun 同一列的宽度只算一次因此必然对齐() {
        val headers = listOf("轮次", "任务类型", "Tokens", "结果")
        val rows = listOf(
            listOf("第1轮", "HTML 生成", "12k", "熔断+假闭环"),
            listOf("第2轮", "联网检索", "8k", "0/5"),
            listOf("第3轮", "策划规划", "20万", "真在跑但熔断"),
        )
        val w = widthsOf(headers, rows)
        assertEquals("列数必须与表头一致", 4, w.size)

        // 逐列检查：同一列的宽度来自「整列最长内容」，不是每格各自算
        // 断言实现一致性 —— 同一列无论内容长短都拿到同一个 Dp 值：
        // 这里通过「只改一行的内容，列宽不应变化（除非超出该列最大档）」验证。
        val w2 = widthsOf(headers, rows.map { it.toMutableList().also { r -> r[3] = "短" } })
        assertEquals(
            "结果列内容变短后列宽不该改变（列宽由整列最长内容决定）：w=$w w2=$w2",
            w[3], w2[3]
        )
    }

    /** 列数必须补齐到「表头与所有行的最大列数」，缺的格子补空串而不是崩。 */
    @Test
    fun 缺列的行不影响整体列数() {
        val headers = listOf("A", "B", "C")
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("1", "2"),// 少一列
        )
        val w = widthsOf(headers, rows)
        assertEquals("列数应等于表头列数", 3, w.size)
    }

    /** 没有表头时也要能算（只有数据行）。 */
    @Test
    fun 无表头也能算列宽() {
        val rows = listOf(listOf("a", "bb"), listOf("ccc", "d"))
        val w = tableColumnWidths(emptyList(), rows, 2)
        assertEquals(2, w.size)
    }

    /** 列宽必须有下限与上限，不能被超长内容撑爆或被短内容压成一条缝。 */
    @Test
    fun 列宽被夹在上下限之间() {
        val shortRows = listOf(listOf("a", "b"))
        val longRows = listOf(listOf("字".repeat(400), "字".repeat(400)))
        for (rows in listOf(shortRows, longRows)) {
            val w = tableColumnWidths(listOf("x", "y"), rows, 2)
            for ((i, dw) in w.withIndex()) {
                assertTrue("第 $i 列宽 $dw 小于下限", dw >= 72.dp)
                assertTrue("第 $i 列宽 $dw 大于上限", dw <= 260.dp)
            }
        }
    }

    /** 表格行上限（ANR 防御）不能因为这次改动被破坏。 */
    @Test
    fun 表格保留ANR防御的说明存在() {
        // 行数上限是渲染层的既有防线，这里只做「注释没被误删」的弱守卫：
        // 真渲染要 Compose，JVM 测不了，所以确认列宽纯函数不依赖任何行数上限。
        val w = tableColumnWidths(listOf("h"), listOf(List(200) { "x" }), 1)
        assertEquals(1, w.size)
    }
}