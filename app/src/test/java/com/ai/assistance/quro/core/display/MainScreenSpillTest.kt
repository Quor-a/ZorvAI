package com.ai.assistance.quro.core.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主屏「冒出来」判定的单元测试（移植自 Mika 的 MainScreenSpill，纯逻辑）。
 *
 * 这块逻辑决定「AI 动作导致 App 开到用户主屏时，要不要把它挪回后台屏」。
 * 判错的两种代价都很明显：该挪没挪 → App 弹在用户脸上，AI 却以为没点到；
 * 不该挪却挪 → 用户正在手动用的 App 被抢走。
 * 所以用数值把每种边界钉住，而不是靠人工点几下。
 */
class MainScreenSpillTest {

    private fun arrival(
        taskId: Int,
        pkg: String?,
        at: Long,
        from: Int = MainScreenSpill.NEW,
        sameTask: Boolean = false,
        standard: Boolean = true,
        now: Int = MainScreenSpill.MAIN,
    ) = MainScreenSpill.Arrival(
        taskId = taskId,
        packageName = pkg,
        userId = 0,
        at = at,
        from = from,
        sameTask = sameTask,
        standard = standard,
        now = now,
    )

    @Test
    fun `动作后新任务出现在主屏 判定为挪回`() {
        val list = listOf(arrival(101, "com.tencent.mm", at = 1000))
        val v = MainScreenSpill.decide(
            arrivals = list,
            since = 900,
            windowMs = 3000,
            topBefore = 55,
            excluded = emptySet(),
            userTaps = emptyList(),
        )
        assertEquals(1, v.size)
        assertTrue("应是 PullBack，实际 ${v[0]}", v[0] is MainScreenSpill.PullBack)
        assertEquals(101, v[0].arrival.taskId)
    }

    @Test
    fun `动作之前就冒出来的不算`() {
        val list = listOf(arrival(101, "com.tencent.mm", at = 500))
        val v = MainScreenSpill.decide(
            arrivals = list,
            since = 900,
            windowMs = 3000,
            topBefore = 55,
            excluded = emptySet(),
            userTaps = emptyList(),
        )
        assertTrue("早于 since 的不应处理", v.isEmpty())
    }

    @Test
    fun `超出窗口的晚到任务不算`() {
        val list = listOf(arrival(101, "com.tencent.mm", at = 9000))
        val v = MainScreenSpill.decide(
            arrivals = list,
            since = 900,
            windowMs = 3000,
            topBefore = 55,
            excluded = emptySet(),
            userTaps = emptyList(),
        )
        assertTrue("超过 since+windowMs 的不应处理", v.isEmpty())
    }

    @Test
    fun `用户在主屏点过东西 不抢`() {
        val list = listOf(arrival(101, "com.tencent.mm", at = 2000))
        val v = MainScreenSpill.decide(
            arrivals = list,
            since = 900,
            windowMs = 3000,
            topBefore = 55,
            excluded = emptySet(),
            userTaps = listOf(MainScreenSpill.Tap(at = 1500, packageName = null)),
        )
        assertTrue("用户点过就说不清是谁开的，不应挪", v.isEmpty())
    }

    @Test
    fun `用户已经在点这个 App 回报 InUse`() {
        val list = listOf(arrival(101, "com.tencent.mm", at = 1500))
        val v = MainScreenSpill.decide(
            arrivals = list,
            since = 900,
            windowMs = 3000,
            topBefore = 55,
            excluded = emptySet(),
            userTaps = listOf(MainScreenSpill.Tap(at = 2000, packageName = "com.tencent.mm")),
        )
        assertEquals(1, v.size)
        assertTrue("应是 InUse，实际 ${v[0]}", v[0] is MainScreenSpill.InUse)
    }

    @Test
    fun `从别的屏挪过来的任务不算`() {
        val list = listOf(arrival(101, "com.tencent.mm", at = 1500, from = 7))
        val v = MainScreenSpill.decide(
            arrivals = list,
            since = 900,
            windowMs = 3000,
            topBefore = 55,
            excluded = emptySet(),
            userTaps = emptyList(),
        )
        assertTrue("从别的屏挪来的属于接管，不该再挪", v.isEmpty())
    }

    @Test
    fun `桌面与最近任务类页面不算`() {
        val list = listOf(arrival(101, "com.android.launcher", at = 1500, standard = false))
        val v = MainScreenSpill.decide(
            arrivals = list,
            since = 900,
            windowMs = 3000,
            topBefore = 55,
            excluded = emptySet(),
            userTaps = emptyList(),
        )
        assertTrue("非普通 App 页面不应处理", v.isEmpty())
    }

    @Test
    fun `排除列表里的包不处理`() {
        val list = listOf(arrival(101, "com.ai.assistance.quro", at = 1500))
        val v = MainScreenSpill.decide(
            arrivals = list,
            since = 900,
            windowMs = 3000,
            topBefore = 55,
            excluded = setOf("com.ai.assistance.quro"),
            userTaps = emptyList(),
        )
        assertTrue("本应用自己不算", v.isEmpty())
    }

    @Test
    fun `动作前主屏那个任务换页 属于用户在用`() {
        val list = listOf(arrival(55, "com.android.settings", at = 1500, from = MainScreenSpill.MAIN, sameTask = true))
        val v = MainScreenSpill.decide(
            arrivals = list,
            since = 900,
            windowMs = 3000,
            topBefore = 55,
            excluded = emptySet(),
            userTaps = emptyList(),
        )
        assertEquals(1, v.size)
        assertTrue("应是 InUse，实际 ${v[0]}", v[0] is MainScreenSpill.InUse)
    }

    @Test
    fun `同一任务多次冒出来只留最后一次`() {
        val list = listOf(
            arrival(101, "com.tencent.mm", at = 1000),
            arrival(102, "com.tencent.mm", at = 1500),
            arrival(101, "com.tencent.mm", at = 2000),
        )
        val v = MainScreenSpill.decide(
            arrivals = list,
            since = 900,
            windowMs = 3000,
            topBefore = 55,
            excluded = emptySet(),
            userTaps = emptyList(),
        )
        assertEquals(2, v.size)
        assertEquals("taskId 101 应排最后（取最后一次）", 101, v.last().arrival.taskId)
    }

    @Test
    fun `日志只记主屏最上面换的任务`() {
        val log = MainScreenLog()
        val now = 1_000L
        // 第一次只看底，不记
        log.scan(
            listOf(
                MainScreenLog.Task(1, 0, "com.a", 0, "com.a.Main"),
                MainScreenLog.Task(2, 7, "com.b", 0, "com.b.Main"),
            ),
            now = now,
            record = false,
        )
        assertEquals("主屏最上面是 1", 1, log.topTaskId)

        // 主屏换成 2（原来是屏 7 上的）→ 记一条，且 from=7
        log.scan(
            listOf(
                MainScreenLog.Task(2, 0, "com.b", 0, "com.b.Main"),
                MainScreenLog.Task(1, 0, "com.a", 0, "com.a.Main"),
            ),
            now = now + 100,
            record = true,
        )
        val s = log.since(now)
        assertEquals(1, s.size)
        assertEquals(2, s[0].taskId)
        assertEquals("from 应记录它上一次所在的屏", 7, s[0].from)
    }
}
