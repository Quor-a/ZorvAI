package com.ai.assistance.quro.ui.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 头像 / 名字行 / 正文三者的垂直避让关系。
 *
 * ## 为什么要钉死
 *
 * 这组常量被反复改过多轮，每次都是「用户说太近 → 改一点 → 还是太近 → 再改」，
 * 因为**改的是封顶值而不是缺口本身**：28dp 头像算出的缺口是 7dp，
 * 却被 `MAX_BODY_TOP_INSET_DP = 4` 砍掉近一半，正文顶部始终压在头像下半截。
 *
 * 用户原话（2026-10-06 真机截图 `Screenshot_2026-10-06-14-27-41-31`）：
 * 「文本离头像怎么近，你觉得正常吗」——指的就是正文首行贴着头像下巴。
 *
 * 本测试把「正文必须让开头像」这条关系写成**可执行的断言**，
 * 而不是靠注释里的实测数字（注释已经骗过我们一次）。
 */
class AvatarTextSpacingTest {

    @Test
    fun `正文内缩不被封顶砍掉`() {
        // 28dp 头像：缺口 28-21=7dp，必须原样落地，不能被封顶打折
        assertEquals(7, bodyTopInsetDp(28))
    }

    @Test
    fun `大头像也不超过封顶`() {
        // 封顶必须真实生效（64dp 头像 → 缺口 43dp），否则超宽屏上正文会脱节到天边
        assertTrue(bodyTopInsetDp(64) <= 10)
        assertTrue(bodyTopInsetDp(120) <= 10)
    }

    @Test
    fun `小头像不产生内缩`() {
        // 头像小于名字行占位时不需要让位（否则小头像场景会莫名多出空隙）
        assertEquals(0, bodyTopInsetDp(16))
        assertEquals(0, bodyTopInsetDp(21))
    }

    @Test
    fun `名字行下移量在合理区间`() {
        // 名字行只做「与头像中线对齐」的居中补偿，不该多下移（多了会与正文脱节）
        assertEquals(0, nameRowCenterOffsetDp(16))
        assertTrue(nameRowCenterOffsetDp(28) in 1..4)
        assertTrue(nameRowCenterOffsetDp(48) in 1..4)
    }

    @Test
    fun `正文总下移量足以让开头像`() {
        // 🔴 本测试的核心断言：
        // 正文最终落点 = 名字行下移 + 正文内缩（两者叠加，因为名字行是正文上方的兄弟）。
        // 28dp 头像要求总下移 ≥ 9dp：实测「10px 净空 @42px 头像」≈ 6.7dp 是可接受下限，
        // 再小正文就贴住头像下巴了。
        val total = nameRowCenterOffsetDp(28) + bodyTopInsetDp(28)
        assertTrue(
            "28dp 头像时正文总下移仅 ${total}dp，不足以让开头像（用户反馈文本太近）",
            total >= 9,
        )
    }

    @Test
    fun `头像增大时避让单调不减`() {
        // 单调性回归：任何更大的头像都不能让正文反而更靠上
        var prev = -1
        for (size in 0..64 step 2) {
            val t = nameRowCenterOffsetDp(size) + bodyTopInsetDp(size)
            assertTrue("头像 $size dp 时总下移 $t <前一档 $prev", t >= prev)
            prev = t
        }
    }

    @Test
    fun `正文宽度上限为屏幕留出头像空间`() {
        // 360dp 屏 + 28dp 头像 + 16dp 列表内边距：正文上限必须是屏宽减去头像与边距，
        // 否则头像会被内容列挤出可视区（此前踩过的坑：上限算大 → 对齐与头像一起废掉）
        val maxW = messageContentMaxWidth(
            screenWidthDp = 360,
            avatarSizeDp = 28,
            listPaddingDp = 16,
            bubblePaddingDp = 0,
        )
        assertEquals(306, maxW)
        assertTrue("正文上限必须小于屏宽", maxW < 360)
    }

    @Test
    fun `超窄屏下正文宽度有下限`() {
        // 分屏/小屏时不得压到不可读
        val maxW = messageContentMaxWidth(
            screenWidthDp = 240,
            avatarSizeDp = 28,
            listPaddingDp = 16,
            bubblePaddingDp = 0,
        )
        assertEquals(200, maxW)
    }

    @Test
    fun `平板上限封顶不失控`() {
        // 平板上无限拉宽会让中文一行 60 字难回扫
        val maxW = messageContentMaxWidth(
            screenWidthDp = 2000,
            avatarSizeDp = 28,
            listPaddingDp = 16,
            bubblePaddingDp = 0,
        )
        assertEquals(720, maxW)
    }
}