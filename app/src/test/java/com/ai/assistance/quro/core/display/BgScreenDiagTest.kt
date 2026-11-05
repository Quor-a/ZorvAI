package com.ai.assistance.quro.core.display

import android.graphics.Bitmap
import android.graphics.Color
import com.ai.assistance.quro.core.ScreenOcrBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 后台虚拟屏「看屏」三条链路的回归测试（纯 JVM + Robolectric 位图）。
 *
 * 盯的是这三类在真机上表现完全不同、但日志里全无痕迹的失效：
 *
 * 1. **画面全黑（FLAG_SECURE）被当成「界面没有文字」** —— 模型于是继续瞎点；
 * 2. **识别到过渡态画面**（启动图/白屏/骨架屏）—— 模型以为没打开，重复 open；
 * 3. **双识别器重复行**（中文模型也读英文）—— 模型看到「设置 设置 网络 网络」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BgScreenDiagTest {

    private fun solid(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    // ── 1. 黑屏识别（防截屏） ────────────────────────────────────

    @Test
    fun `纯黑画面判为黑屏`() {
        val bmp = solid(200, 400, Color.BLACK)
        assertTrue("FLAG_SECURE 的界面拿到的就是纯黑帧，必须能认出来", FrameGate.probe(bmp).black)
        bmp.recycle()
    }

    @Test
    fun `正常画面不判黑`() {
        val bmp = solid(200, 400, Color.WHITE)
        assertFalse(FrameGate.probe(bmp).black)
        bmp.recycle()
    }

    @Test
    fun `大部分有内容就不算黑屏`() {
        // 真实界面的采样点里总有几个暗色像素（图标/文字），不能因为「黑底主题」就误判
        val bmp = solid(200, 400, Color.WHITE)
        // 画一条暗色带，仍有 98% 以上是白的
        for (x in 0 until 200) {
            for (y in 0 until 8) bmp.setPixel(x, y, Color.BLACK)
        }
        assertFalse(FrameGate.probe(bmp).black)
        bmp.recycle()
    }

    // ── 2. 帧签名与稳定判定 ──────────────────────────────────────

    @Test
    fun `同画面签名一致 不同画面签名不同`() {
        val a1 = solid(120, 240, Color.WHITE)
        val a2 = solid(120, 240, Color.WHITE)
        val b = solid(120, 240, Color.rgb(10, 200, 30))
        assertEquals(
            "同一画面两次采样必须一致，否则永远判不出「停稳」",
            FrameGate.probe(a1).signature, FrameGate.probe(a2).signature,
        )
        assertNotEquals(FrameGate.probe(a1).signature, FrameGate.probe(b).signature)
        a1.recycle(); a2.recycle(); b.recycle()
    }

    @Test
    fun `已回收的位图不抛异常 按黑处理`() {
        val bmp = solid(40, 80, Color.WHITE)
        bmp.recycle()
        // 不能因为一次竞态回收就让整条读屏链路崩掉
        assertTrue(FrameGate.probe(bmp).black)
    }

    // ── 3. OCR 双识别器去重 ─────────────────────────────────────

    private fun line(text: String, cx: Int, cy: Int, conf: Float? = null): ScreenOcrBridge.OcrLine =
        ScreenOcrBridge.OcrLine(text, cx - 20, cy - 10, cx + 20, cy + 10, conf)

    @Test
    fun `中文与拉丁识别器报的同一行只留一条`() {
        val merged = ScreenOcrBridge.dedupeLines(
            listOf(
                line("Settings", 100, 200),
                line("Settings", 104, 203), // 拉丁模型再报一次，位置差几像素
                line("网络", 300, 400),
            ),
        )
        assertEquals("重复行必须合并，否则模型看到「Settings Settings」", 2, merged.size)
    }

    @Test
    fun `重复行保留置信度高的那份`() {
        val merged = ScreenOcrBridge.dedupeLines(
            listOf(line("取消", 100, 200, 0.4f), line("取消", 102, 201, 0.9f)),
        )
        assertEquals(1, merged.size)
        assertEquals(0.9f, merged.first().confidence)
    }

    @Test
    fun `同文本但位置很远的行不能被合并`() {
        // 列表里常见同名条目（多个「删除」），合并会丢掉真实控件
        val merged = ScreenOcrBridge.dedupeLines(
            listOf(line("删除", 100, 200), line("删除", 100, 1200)),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `大小写与空格差异视为同一行`() {
        val merged = ScreenOcrBridge.dedupeLines(
            listOf(line("Wi Fi", 100, 200), line("wi fi", 101, 200)),
        )
        assertEquals(1, merged.size)
    }

    // ── 4. 给模型的诊断文本 ─────────────────────────────────────

    private fun reading(
        snap: BgSnapshot? = null,
        ocr: ScreenOcrBridge.Result? = null,
        probe: FrameGate.Probe? = null,
        stable: Boolean = true,
        ready: Boolean = true,
    ) = QuroVirtualScreen.BgReading(
        displayId = 18, width = 1220, height = 2712, topPackage = "com.tencent.mm",
        snapshot = snap, ocr = ocr, probe = probe, stable = stable,
        screenReady = ready, error = null,
    )

    @Test
    fun `黑屏时明确告诉模型可能禁止截屏`() {
        val text = reading(probe = FrameGate.Probe(1L, black = true)).toPromptText()
        assertTrue("必须点名 FLAG_SECURE，否则模型会以为界面是空的", text.contains("禁止截屏"))
    }

    @Test
    fun `界面还在变时提示稍后重读`() {
        val text = reading(stable = false).toPromptText()
        assertTrue(text.contains("还在"))
    }

    @Test
    fun `无障碍未开启时说明只能靠 OCR 坐标`() {
        val text = reading(snap = null).toPromptText()
        assertTrue(text.contains("未开启"))
        assertTrue("要告诉模型 OCR 文字仍可直接点", text.contains("tap"))
    }

    @Test
    fun `OCR 失败不能伪装成没有文字`() {
        val text = reading(
            ocr = ScreenOcrBridge.Result(ScreenOcrBridge.Status.FAILED, reason = "model missing"),
        ).toPromptText()
        assertTrue(text.contains("识别器出错"))
        assertTrue("必须澄清这不代表没字", text.contains("不代表"))
    }

    @Test
    fun `元素与 OCR 都空时给出排查建议`() {
        val text = reading(snap = BgSnapshot(18, 1220, 2712, null, emptyList(), emptyList(), 0L, false))
            .toPromptText()
        assertTrue(text.contains("都为空"))
    }
}
