package com.ai.assistance.quro.core.display

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * 帧闸门：读屏/OCR 之前先判断「这一帧能不能用」。
 *
 * 移植自 Mika 的帧稳定与黑屏判断（`StillFrameRead` / `frameBlack` 的等价物），解决的正是
 * 我方「AI 看屏不准」的两个主要来源：
 *
 * ## 1. 识别到过渡态画面
 *
 * App 刚被拉起时 `ImageReader` 会连续来好几帧：启动图 → 白屏 → 骨架屏 → 真界面。
 * 谁在中间哪一帧截的图，OCR 就识别哪一帧 —— 于是 AI 看到「Loading…」「正在加载」
 * 或者干脆一片空白，误判成「没打开」，然后重复 open / 重复点击。
 *
 * 判据：连续两次采样（间隔 [SAMPLE_GAP_MS]）的**签名一致**才算停稳。
 * 局部动画（光标、动图头像）不影响文字位置，所以签名用粗采样，不做逐像素比较。
 *
 * ## 2. 防截屏界面（FLAG_SECURE）
 *
 * 银行、支付、部分 IM 的会话页禁止截屏：`ImageReader` 拿到的是**纯黑**。
 * 旧实现把纯黑帧丢给 OCR，OCR 返回空 → 我们告诉 AI「没有可读文字」→
 * AI 以为界面是空的，继续瞎点。现在识别出全黑就直说「可能禁止截屏」。
 */
object FrameGate {

    /** 两次采样间隔：比一帧（16ms）长得多，比人的感知短。 */
    const val SAMPLE_GAP_MS = 220L

    /** 等画面停稳的总预算。超过就用手上最新那帧，不再等。 */
    const val STABLE_BUDGET_MS = 1_400L

    /** 判定「黑」的亮度阈值（0-255，仅看 RGB 均值）。 */
    private const val DARK_LEVEL = 18

    /** 采样点里超过这个比例是黑的，就算整屏黑。 */
    private const val BLACK_RATIO = 0.985f

    /** 一次粗采样的结果。 */
    data class Probe(
        /** 采样签名；同一画面必然相同。 */
        val signature: Long,
        /** 是否整屏近黑（防截屏 / 屏还没亮）。 */
        val black: Boolean,
    )

    /**
     * 等停稳后交出去的一帧。
     *
     * [bitmap] **由调用方负责 recycle**；[stable] 为 false 表示预算内它一直在变
     * （通常意味着界面还在加载），调用方应把这件事告诉模型，而不是当成最终画面。
     */
    class StableFrame(
        val bitmap: Bitmap,
        val probe: Probe,
        val stable: Boolean,
    )

    /**
     * 粗采样一次。
     *
     * 只读约千分之二的像素（横向 24 列 × 纵向 48 行 = 1152 次 `getPixel`，单次 < 2ms）。
     * 逐像素比较会在这条路径上吃掉几百毫秒，完全不值。
     */
    fun probe(bmp: Bitmap): Probe {
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return Probe(0L, black = true)
        val cols = 24
        val rows = 48
        var hash = FNV_OFFSET
        var dark = 0
        var total = 0
        for (r in 0 until rows) {
            val y = ((bmp.height - 1).toLong() * r / (rows - 1)).toInt()
            for (c in 0 until cols) {
                val x = ((bmp.width - 1).toLong() * c / (cols - 1)).toInt()
                val px = runCatching { bmp.getPixel(x, y) }.getOrDefault(0)
                val a = (px ushr 24) and 0xFF
                val rr = (px shr 16) and 0xFF
                val gg = (px shr 8) and 0xFF
                val bb = px and 0xFF
                hash = (hash xor px.toLong()) * FNV_PRIME
                // 全透明也算黑：屏还没合成时给的就是全 0
                if (a == 0 || (rr + gg + bb) / 3 <= DARK_LEVEL) dark++
                total++
            }
        }
        return Probe(hash, total > 0 && dark.toFloat() / total >= BLACK_RATIO)
    }

    /**
     * 等画面停稳，返回一帧（调用方负责 recycle）；拿不到任何帧时返回 null。
     *
     * 连续两次采样签名相同即认为停稳，立刻返回（典型 220ms，最坏 [STABLE_BUDGET_MS]）。
     * 期间产生的中间副本全部在这里回收，不留给调用方。
     *
     * @param grab 取当前帧；必须每次返回**新副本**，否则回收会踩到共享位图
     */
    suspend fun awaitStable(grab: () -> Bitmap?): StableFrame? {
        val deadline = SystemClock.uptimeMillis() + STABLE_BUDGET_MS
        var prev: Bitmap? = null
        var prevProbe: Probe? = null
        try {
            while (true) {
                val cur = grab()
                if (cur == null) {
                    if (SystemClock.uptimeMillis() >= deadline) return null
                    delay(SAMPLE_GAP_MS)
                    continue
                }
                val p = probe(cur)
                // 与上一次签名一致 → 停稳；用这一帧（更新）交出去
                if (prevProbe != null && prevProbe.signature == p.signature) {
                    prev?.recycle()
                    return StableFrame(cur, p, stable = true)
                }
                prev?.recycle()
                prev = cur
                prevProbe = p
                if (SystemClock.uptimeMillis() >= deadline) {
                    // 预算用完还在变：照实交出，但标记为不稳定
                    return StableFrame(cur, p, stable = false)
                }
                delay(SAMPLE_GAP_MS)
            }
        } catch (t: Throwable) {
            prev?.recycle()
            throw t
        }
    }

    /**
     * 等画面出现**与 [before] 不同**的一帧，最多 [timeoutMs]。
     *
     * 用途是「动作之后确认界面真的变了」，比固定 sleep 准得多也快得多：
     * 变了立刻返回（通常 100–300ms），而不是每次都等满。
     */
    suspend fun awaitChange(before: Long, timeoutMs: Long, grab: () -> Bitmap?): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val bmp = grab()
            if (bmp == null) {
                delay(SAMPLE_GAP_MS)
                continue
            }
            val sig = probe(bmp).signature
            bmp.recycle()
            if (sig != 0L && sig != before) return true
            delay(SAMPLE_GAP_MS)
        }
        return false
    }

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L
}
