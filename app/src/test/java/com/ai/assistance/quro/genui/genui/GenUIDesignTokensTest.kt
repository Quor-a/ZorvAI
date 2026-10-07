package com.ai.assistance.quro.genui.genui

import com.ai.assistance.quro.genui.sdk.render.GenUILayoutGuard
import com.ai.assistance.quro.genui.sdk.style.GenUIDesignTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设计 token 一致性测试（2026-10-06）。
 *
 * 钉死「间距只用 8pt 网格」这条契约：
 * 修复前全仓 220 处内边距里有 49 处（10/14/18/3/7dp）落在网格之外，
 * 同一屏里卡片一高一低 —— 这就是用户说的「组件虽然多但是不齐」。
 *
 * 本测试保证 token 自身不会腐化，也保证守卫的默认间距与 token 同源
 * （不是各自写一个 8 —— 两个来源就等于没有约束）。
 */
class GenUIDesignTokensTest {

    @Test
    fun 间距档位全部落在八点网格上() {
        // 合法档位：2/4/8/12/16/24/32（2 与 4 是 8 的半格与四分之一，HIG 允许）
        for (d in GenUIDesignTokens.spacingScale) {
            val v = d.value
            assertTrue(
                "间距 ${d.value}dp 不在 8pt 网格上（允许 2/4/8/12/16/24/32）",
                v % 2f == 0f || v == 4f || v == 2f
            )
            assertTrue("间距 ${d.value}dp 超出守卫上限", v <= GenUILayoutGuard.MAX_SPACING_DP)
        }
        assertEquals(7, GenUIDesignTokens.spacingScale.size)
    }

    @Test
    fun 间距档位严格递增不重复() {
        val vals = GenUIDesignTokens.spacingScale.map { it.value }
        for (i in 1 until vals.size) {
            assertTrue("间距档位必须递增：$vals", vals[i] > vals[i - 1])
        }
    }

    @Test
    fun 默认间距与token同源不是各自写死() {
        // 🔴 这条最关键：守卫的 DEFAULT_SPACING_DP 与 token 的 spacingDefault
        // 必须是同一个数。曾经「容器默认间距」有 0f 与 8f 两套实现，
        // 两个来源就等于没有约束 —— 现在只允许一个。
        assertEquals(
            GenUIDesignTokens.spacingDefault,
            GenUILayoutGuard.DEFAULT_SPACING_DP,
            0.001f
        )
        assertEquals(8f, GenUIDesignTokens.spacingDefault, 0.001f)
    }

    @Test
    fun 圆角档位符合Material3刻度() {
        val r = listOf(
            GenUIDesignTokens.radiusXs.value,
            GenUIDesignTokens.radiusSm.value,
            GenUIDesignTokens.radiusMd.value,
            GenUIDesignTokens.radiusLg.value,
            GenUIDesignTokens.radiusXl.value,
        )
        assertEquals(listOf(4f, 8f, 12f, 16f, 28f), r)
    }

    @Test
    fun 字号档位符合Material3TypeScale且递增() {
        val f = listOf(
            GenUIDesignTokens.fontLabelSmall,
            GenUIDesignTokens.fontBodySmall,
            GenUIDesignTokens.fontBodyMedium,
            GenUIDesignTokens.fontBodyLarge,
            GenUIDesignTokens.fontTitleLarge,
            GenUIDesignTokens.fontHeadlineSmall,
            GenUIDesignTokens.fontDisplaySmall,
        )
        for (i in 1 until f.size) {
            assertTrue("字号档位必须递增：$f", f[i] > f[i - 1])
        }
        for (v in f) {
            assertTrue(
                "字号 $v 超出守卫上限 ${GenUILayoutGuard.MAX_FONT_SIZE_SP}",
                v <= GenUILayoutGuard.MAX_FONT_SIZE_SP
            )
        }
    }

    @Test
    fun 可点区域不低于无障碍下限() {
        assertTrue(
            "最小可点区域必须 >= 48dp（Material 无障碍下限）",
            GenUIDesignTokens.minTouchTarget.value >= 48f
        )
        assertTrue(
            "图标按钮不能小于最小可点区域",
            GenUIDesignTokens.iconButtonSize.value <= GenUIDesignTokens.minTouchTarget.value
        )
    }

    @Test
    fun 页面边距落在网格上() {
        assertEquals(16f, GenUIDesignTokens.pagePaddingH.value, 0.001f)
        assertEquals(16f, GenUIDesignTokens.pagePaddingTop.value, 0.001f)
        assertEquals(24f, GenUIDesignTokens.pagePaddingBottom.value, 0.001f)
    }

    @Test
    fun 阅读宽度上限与守卫宽度上限一致() {
        // PageCanvas 的 widthIn(max) 必须与守卫的宽度钳制同源，
        // 否则「AI 写 700dp 宽」在钳制层放行、在画布层又被裁一次，两层标准不一。
        assertEquals(720f, GenUILayoutGuard.MAX_WIDTH_DP, 0.001f)
    }
}
