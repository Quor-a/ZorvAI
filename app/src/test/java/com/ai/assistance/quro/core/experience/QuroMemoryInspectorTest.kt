package com.ai.assistance.quro.core.experience

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「清理内存」真实数据层的口径单测。
 *
 * 背景：用户指出「清理内存不要搞假实现，要真实的功能」。
 * 本类锁两件最容易再次造假的事：
 * 1. [QuroMemoryInfo.collectibleHeap] 必须真的是「已分配 - 已用」，不是把额度当可清理量；
 * 2. [measureDirOrNull] 必须能区分「无权限(null)」与「真空(0)」——把读不到说成 0 是最典型的假实现。
 */
class QuroMemoryInspectorTest {

    private fun info(
        javaHeap: Long = 100L * 1024 * 1024,
        javaHeapUsed: Long = 40L * 1024 * 1024,
        pssTotal: Long = 200L * 1024 * 1024,
        systemTotal: Long = 8L * 1024 * 1024 * 1024,
    ) = QuroMemoryInfo(
        javaHeap = javaHeap,
        javaHeapUsed = javaHeapUsed,
        nativeHeap = 30L * 1024 * 1024,
        nativeHeapTotal = 64L * 1024 * 1024,
        pssTotal = pssTotal,
        pssDalvik = 90L * 1024 * 1024,
        pssNative = 110L * 1024 * 1024,
        systemTotal = systemTotal,
        systemAvailable = 2L * 1024 * 1024 * 1024,
        systemLowMemory = false,
    )

    @Test
    fun `可回收内存等于已分配减已用而非堆额度`() {
        // 100MB 额度、已用 40MB → 可回收 60MB。
        // 🔴 绝不能返回 100MB（javaHeap）：那是「已向系统申请的额度」，
        //    把它标成「可清理内存」就是页面级的假实现。
        assertEquals(60L * 1024 * 1024, info().collectibleHeap)
    }

    @Test
    fun `已用超过额度时可回收量不出现负数`() {
        // GC 刚跑完/采样竞态时 totalMemory 可能小于 freeMemory，
        // 此时若直接相减会得到负数，UI 上会显示「-8 MB」这种荒唐值。
        val m = info(javaHeap = 100L * 1024 * 1024, javaHeapUsed = 130L * 1024 * 1024)
        assertEquals(0L, m.collectibleHeap)
    }

    @Test
    fun `堆用满时可回收量为零`() {
        val m = info(
            javaHeap = 100L * 1024 * 1024,
            javaHeapUsed = 100L * 1024 * 1024,
        )
        assertEquals(0L, m.collectibleHeap)
    }

    @Test
    fun `PSS占设备内存比例被夹在0到1`() {
        // 8GB 设备上占 200MB → 约 2.4%
        val m = info()
        assertEquals(200L * 1024 * 1024f / (8L * 1024 * 1024 * 1024), m.pssPercent, 0.0001f)
        assertTrue(m.pssPercent in 0f..1f)

        // 极端输入：PSS 大于设备总内存（口径异常/多进程共享）时不得 >1
        val absurd = info(pssTotal = 100L * 1024 * 1024 * 1024)
        assertEquals(1f, absurd.pssPercent, 0.0001f)

        // 总内存为 0（系统接口失败）时不得除零 → 0
        val zero = info(systemTotal = 0L)
        assertEquals(0f, zero.pssPercent, 0.0001f)
    }

    @Test
    fun `堆使用率被夹在0到1`() {
        assertEquals(0.4f, info().javaHeapPercent, 0.0001f)
        assertEquals(0f, info(javaHeap = 0L, javaHeapUsed = 10L).javaHeapPercent, 0.0001f)
        assertEquals(1f, info(javaHeapUsed = 999L * 1024 * 1024).javaHeapPercent, 0.0001f)
    }

    @Test
    fun `目录体积能区分无权限与真空`() {
        val tmp = createTempDirForTest()

        // 1) 不存在的目录 = 确实为空 → 0L（不是 null）
        val missing = File(tmp, "definitely-not-here")
        assertEquals(0L, measureDirOrNull(missing))

        // 2) 真实存在的空目录 = 真空 → 0L
        val empty = File(tmp, "empty").apply { mkdirs() }
        assertEquals(0L, measureDirOrNull(empty))

        // 3) 有内容的目录 = 真实体积，且递归计入子目录
        val withFile = File(tmp, "withFile").apply { mkdirs() }
        File(withFile, "a.bin").writeBytes(ByteArray(2048))
        val sub = File(withFile, "sub").apply { mkdirs() }
        File(sub, "b.bin").writeBytes(ByteArray(1024))
        assertEquals(3072L, measureDirOrNull(withFile))
    }

    private fun createTempDirForTest(): File =
        File.createTempFile("quro_memtest", "").let {
            it.delete()
            it.mkdirs()
            it.deleteOnExit()
            it
        }
}