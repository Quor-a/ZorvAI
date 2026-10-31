package com.ai.assistance.quro.core.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮小窗手势物理单测。
 *
 * 关键断言：四个角的位置计算、按速度吸附的方向、弹簧能收敛不振荡发散。
 * 这些错了表现为「小窗飞出去 / 卡在半路 / 停在屏幕外」，肉眼很难描述，用数值钉住。
 */
class PipPhysicsTest {

    private val bounds = PipPhysics.Bounds(
        width = 1080f,
        height = 2000f,
        pipWidth = 280f,
        pipHeight = 560f,
        margin = 30f,
        top = 120f,
        bottom = 200f,
    )

    @Test
    fun `四个角的位置在预期范围内`() {
        val (tlX, tlY) = bounds.position(PipPhysics.Corner.TopStart)
        assertEquals("左上 X 应为 margin", 30f, tlX, 0.01f)
        assertEquals("左上 Y 应为 top+margin", 150f, tlY, 0.01f)

        val (trX, _) = bounds.position(PipPhysics.Corner.TopEnd)
        assertEquals("右上 X = width - pipWidth - margin", 1080f - 280f - 30f, trX, 0.01f)

        val (_, blY) = bounds.position(PipPhysics.Corner.BottomStart)
        assertEquals("左下 Y = height - pipHeight - bottom - margin", 2000f - 560f - 200f - 30f, blY, 0.01f)

        // 所有角都必须落在可视区内
        PipPhysics.Corner.entries.forEach { c ->
            val (x, y) = bounds.position(c)
            assertTrue("$c X 不能为负", x >= 0f)
            assertTrue("$c Y 不能小于 top", y >= bounds.top)
            assertTrue("$c 右下不能越过 bottom 留白", y + bounds.pipHeight <= bounds.height - bounds.bottom)
        }
    }

    @Test
    fun `向右下方甩动后吸附到右下角`() {
        val corner = PipPhysics.snapCorner(
            x = 400f, y = 800f, vx = 6_000f, vy = 8_000f, bounds = bounds,
        )
        assertEquals(PipPhysics.Corner.BottomEnd, corner)
    }

    @Test
    fun `向左上方甩动后吸附到左上角`() {
        val corner = PipPhysics.snapCorner(
            x = 600f, y = 900f, vx = -6_000f, vy = -8_000f, bounds = bounds,
        )
        assertEquals(PipPhysics.Corner.TopStart, corner)
    }

    @Test
    fun `静止松手时吸附到最近角`() {
        // 贴着左上角附近松手，速度为 0
        val corner = PipPhysics.snapCorner(x = 60f, y = 200f, vx = 0f, vy = 0f, bounds = bounds)
        assertEquals(PipPhysics.Corner.TopStart, corner)
    }

    @Test
    fun `临界阻尼弹簧能收敛到目标且不越过太多`() {
        val spring = PipPhysics.Spring()
        var m = PipPhysics.Motion(position = 0f, velocity = 0f)
        val target = 100f
        var maxOvershoot = 0f
        repeat(120) {
            m = spring.step(m, target, 1f / 60f)
            if (m.position > target) maxOvershoot = maxOf(maxOvershoot, m.position - target)
        }
        assertTrue("弹簧必须收敛（实际 ${m.position}）", spring.settled(m, target))
        assertTrue("临界阻尼不应大幅过冲（实际 $maxOvershoot）", maxOvershoot < 5f)
    }

    @Test
    fun `投射位移随速度单调递增`() {
        assertTrue(PipPhysics.project(0f) == 0f)
        assertTrue("速度越大滑得越远", PipPhysics.project(2000f) > PipPhysics.project(1000f))
        // 负速度应投射到反方向
        assertTrue(PipPhysics.project(-1000f) < 0f)
    }
}
