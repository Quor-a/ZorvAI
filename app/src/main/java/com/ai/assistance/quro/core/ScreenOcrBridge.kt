package com.ai.assistance.quro.core

import android.graphics.Bitmap
import android.util.Log
import com.ai.assistance.quro.core.mika.phone.a11y.PhoneOcr
import com.ai.assistance.quro.core.mika.uistate.VisibleTextLine

/**
 * 本地端侧 OCR 桥（Mika `PhoneOcr` 的对外门面，**完整移植**）。
 *
 * Mika 的 `PhoneOcr` 用 Google ML Kit 双识别器（中文 + 拉丁）在本机并行识别，
 * 不依赖任何云端、不消耗 token，专门补「无障碍节点树读不到文字」的界面：
 * 自绘 UI / Flutter / 游戏 / WebView / 控件没名字的场景。
 *
 * 引擎侧我们与上游一致，这一层补的是上游有、我们之前缺的三件接线语义：
 *
 * 1. **「没字」和「识别失败」必须分开**。旧实现把一切异常吞成空列表，于是 ML Kit 崩了、
 *    模型还没下载、图片非法，全都表现为「这块屏没有文字」——AI 据此判定界面是空的、
 *    继续瞎点。现在返回 [Result]，调用方能把「识别失败」如实讲给模型听。
 * 2. **双识别器会吐出重复行**。中文模型也能读英文，于是同一行文字会被两个模型各报一次，
 *    结果是 AI 看到「设置 设置 网络 网络 …」。现在按「文本 + 位置」去重。
 * 3. **保留像素包围盒**，不只中心点。AI 判断「文字在输入框里还是外面」需要框，
 *    误差也能由调用方自己算，不用再反推。
 *
 * 调用方务必：位图用完即交给这里（[read] 内部会 recycle 它，因为 ML Kit 也持有引用）。
 */
object ScreenOcrBridge {

    private const val TAG = "ScreenOcrBridge"

    /** 识别的三种结局 + 「根本没法试」。 */
    enum class Status {
        /** 识别成功且读到了字。 */
        OK,

        /** 识别成功，但这块屏上确实没有字。 */
        EMPTY,

        /** 识别器本身出错（模型缺失 / ML Kit 初始化失败 / 图片非法）。 */
        FAILED,

        /** 该环境没有可用的识别器，连试都没法试。 */
        UNAVAILABLE,
    }

    /** OCR 一行：文字 + **屏幕像素**包围盒 + 置信度（模型不给时为 null）。 */
    data class OcrLine(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val confidence: Float? = null,
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }

    /** 一次识别的结果；[status] 为 [Status.FAILED] 时 [reason] 说明原因。 */
    data class Result(
        val status: Status,
        val lines: List<OcrLine> = emptyList(),
        val reason: String? = null,
    ) {
        val usable: Boolean get() = status == Status.OK
    }

    /**
     * 识别一张位图上的全部文字（本机 ML Kit）。
     *
     * **不抛异常**：OCR 只是看屏的兜底增强，绝不能因为它挂了就让读屏失败；
     * 但失败会如实反映在 [Result.status] 上，不再伪装成「没有字」。
     *
     * @param bitmap 必须是软件位图（[Bitmap.Config.ARGB_8888]）；调用后**会被 recycle**
     */
    suspend fun read(bitmap: Bitmap): Result {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0 || bitmap.isRecycled) {
            runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
            return Result(Status.UNAVAILABLE, reason = "位图不可用（尺寸 ${w}x$h）")
        }

        val raw: List<VisibleTextLine> = try {
            PhoneOcr.read(bitmap)
        } catch (t: Throwable) {
            // PhoneOcr 内部已 recycle；两套识别器都失败时它刻意抛异常，而不是返回空列表
            Log.w(TAG, "OCR 识别失败: ${t.message}")
            return Result(
                status = if (t is NoClassDefFoundError) Status.UNAVAILABLE else Status.FAILED,
                reason = t.message ?: t.javaClass.simpleName,
            )
        }

        val lines = dedupe(raw.mapNotNull { toLine(it, w, h) })
        return if (lines.isEmpty()) {
            Result(Status.EMPTY)
        } else {
            Result(Status.OK, lines)
        }
    }

    /** 是否已经预热过（[warmUp] 成功）。仅用于诊断展示，不影响功能。 */
    @Volatile
    var isWarm: Boolean = false
        private set

    /** 预热识别器：首次 [read] 要初始化两套模型（约 1s），趁空闲先付掉这笔钱。 */
    fun warmUp() {
        runCatching { PhoneOcr.warmUp() }
            .onSuccess { isWarm = true }
            .onFailure { Log.w(TAG, "OCR 预热失败（可忽略）: ${it.message}") }
    }

    /**
     * 归一化包围盒（0..999，Mika 的坐标约定）→ 像素。
     *
     * 上游 `PhoneOcr` 报的是归一化坐标，这样同一份结果能用于任意分辨率；
     * 而 AI 的 tap 用像素，所以在这里一次性换算，免得每处调用都各算一遍。
     */
    private fun toLine(line: VisibleTextLine, w: Int, h: Int): OcrLine? {
        val b = line.bounds
        if (b.size != 4) return null
        val text = line.text.trim()
        if (text.isEmpty()) return null
        fun px(v: Int, size: Int) = (v.toLong() * size / 1000L).toInt().coerceIn(0, size)
        var l = px(b[0], w)
        var t = px(b[1], h)
        var r = px(b[2], w)
        var bt = px(b[3], h)
        // 极窄的框（模型偶尔给成一条线）撑开一点，保证中心点落在字上
        if (r - l < 2) { l = (l - 1).coerceAtLeast(0); r = (r + 1).coerceAtMost(w) }
        if (bt - t < 2) { t = (t - 1).coerceAtLeast(0); bt = (bt + 1).coerceAtMost(h) }
        return OcrLine(text, l, t, r, bt, line.confidence)
    }

    /**
     * 去重：同一行文字会被中文、拉丁两个模型各报一次。
     *
     * 判据是「文本相同（忽略大小写与空白）」且「中心点相距 < 3% 屏宽」——
     * 同一行字在两个模型下位置只会差几个像素，不同行不会这么近。
     * 保留置信度更高的那份；都没有置信度就留先到的（中文模型先跑）。
     */
    private fun dedupe(lines: List<OcrLine>): List<OcrLine> = dedupeLines(lines)

    /** 见 [dedupe]；抽成 internal 是为了单测能直接验证「双识别器重复行」这条行为。 */
    internal fun dedupeLines(lines: List<OcrLine>): List<OcrLine> {
        if (lines.size <= 1) return lines
        val kept = ArrayList<OcrLine>(lines.size)
        for (line in lines) {
            val key = line.text.lowercase().filterNot { it.isWhitespace() }
            val dup = kept.indexOfFirst { k ->
                k.text.lowercase().filterNot { it.isWhitespace() } == key &&
                    // 相对判据：以文字框自身尺寸的一半作为「同一处」的半径
                    kotlin.math.abs(k.centerX - line.centerX) <= maxOf(24, (k.right - k.left) / 2) &&
                    kotlin.math.abs(k.centerY - line.centerY) <= maxOf(24, (k.bottom - k.top) / 2)
            }
            if (dup < 0) {
                kept += line
            } else {
                val old = kept[dup]
                if ((line.confidence ?: 0f) > (old.confidence ?: 0f)) kept[dup] = line
            }
        }
        return kept
    }
}
