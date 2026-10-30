package com.ai.assistance.quro.core.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静默时段边界测试。
 *
 * ## 为什么单独测这两个纯函数
 * 跨零点的区间判断（22:00-07:00 到底包含 23:30 还是 06:00）是这类代码最容易写错的地方，
 * 而且**错了不会有任何报错**——用户只会在半夜被念醒，或者白天发现助手一声不吭。
 * 靠真机手测要等一天才能覆盖两个边界，所以这里用分钟数直接打边界。
 */
class AssistantSpeakPolicyTest {

    private val h = { hh: Int, mm: Int -> hh * 60 + mm }

    @Test
    fun crossMidnightRangeContainsBothSides() {
        val r = parseQuietRange("22:00-07:00")
        assertEquals(h(22, 0) to h(7, 0), r)
        // 起点、跨零点后、终点附近都算命中
        assertTrue(inQuietWindow(r!!, h(22, 0)))
        assertTrue(inQuietWindow(r, h(23, 30)))
        assertTrue(inQuietWindow(r, h(0, 0)))
        assertTrue(inQuietWindow(r, h(6, 59)))
        assertTrue(inQuietWindow(r, h(7, 0)))
        // 中间白天时段不算
        assertFalse(inQuietWindow(r, h(12, 0)))
        assertFalse(inQuietWindow(r, h(21, 59)))
        assertFalse(inQuietWindow(r, h(7, 1)))
    }

    @Test
    fun sameDayRangeWorks() {
        val r = parseQuietRange("09:00-18:00")
        assertEquals(h(9, 0) to h(18, 0), r)
        assertTrue(inQuietWindow(r!!, h(12, 0)))
        assertFalse(inQuietWindow(r, h(8, 59)))
        assertFalse(inQuietWindow(r, h(18, 1)))
    }

    @Test
    fun singleDigitHourAccepted() {
        // 用户手输 `9:00-18:00` 很常见，不能当成非法丢掉
        assertEquals(h(9, 0) to h(18, 0), parseQuietRange("9:00-18:00"))
        assertEquals(h(9, 0) to h(7, 0), parseQuietRange("9:00-7:00"))
    }

    @Test
    fun whitespaceTolerated() {
        assertEquals(h(22, 0) to h(7, 0), parseQuietRange("  22:00 - 07:00 "))
    }

    @Test
    fun invalidInputsReturnNull() {
        assertNull(parseQuietRange(""))
        assertNull(parseQuietRange("22:00"))              // 缺结束
        assertNull(parseQuietRange("22:00-"))             // 缺结束时间
        assertNull(parseQuietRange("24:00-07:00"))        // 小时越界
        assertNull(parseQuietRange("22:60-07:00"))        // 分钟越界
        assertNull(parseQuietRange("abc-def"))
        assertNull(parseQuietRange("-"))
    }
}
