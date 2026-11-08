package com.ai.assistance.quro.core.pet

import android.content.Context
import com.ai.assistance.quro.core.display.QuroVirtualScreen
import com.ai.assistance.quro.core.tools.ScreenshotTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 桌宠的「看屏幕」能力：把应用里已有的三套屏幕能力统一接给桌宠。
 *
 * ZorvAI 本来就有完整的后台虚拟屏（[QuroVirtualScreen]：Shizuku 创建独立 Display、
 * 控件树 + OCR + 帧签名）、无障碍读屏（[QuroQuietTurn.readScreenCue]）与
 * 截图/视觉分析（[ScreenshotTool]），但桌宠此前一条都没接，只会把
 * `ReadScreenTool().run()` 的原始节点树 dump 拼进用户消息。
 *
 * 这里的职责：
 *  1. 全部在 IO 线程执行并带超时，绝不在主线程做截图 / OCR / 等 Shizuku；
 *  2. 优先后台虚拟屏（不干扰用户主屏，可让 AI 在上面完成任务），
 *     不可用时静默降级到无障碍文字，再降级到截图路径；
 *  3. 输出**结构化、带诊断结论**的文本，只作为隐藏上下文注入对话，
 *     任何 ❌/权限错误/节点树 dump 都不会变成可见气泡。
 */
object PetScreenBrain {

    enum class Mode(val label: String) {
        VIRTUAL("后台虚拟屏"),
        ACCESSIBILITY("当前屏幕"),
        VISION("截图"),
        UNAVAILABLE("不可用"),
    }

    data class Insight(
        val mode: Mode,
        /** 给模型看的屏幕观察文本（可以为空 = 没读到东西）。 */
        val text: String,
        val displayId: Int = -1,
        val topPackage: String? = null,
        /** 截图文件绝对路径；Agent 可用 visual_analysis 直接看画面。 */
        val screenshotPath: String? = null,
        /** 面向用户的一句状态说明，只进桌宠状态气泡，不进对话。 */
        val status: String,
    ) {
        val available: Boolean get() = mode != Mode.UNAVAILABLE && text.isNotBlank()

        /** 注入隐藏上下文的完整文本。 */
        fun toHiddenContext(): String = buildString {
            append("屏幕来源：").appendLine(mode.label)
            if (displayId >= 0) appendLine("虚拟屏 displayId=$displayId")
            topPackage?.takeIf { it.isNotBlank() }?.let { appendLine("前台应用：$it") }
            screenshotPath?.let { appendLine("截图文件：$it（需要看画面时用 visual_analysis 工具）") }
            appendLine(text)
        }.trim()
    }

    /**
     * 采集屏幕观察。
     *
     * @param preferVirtual 是否优先启用后台虚拟屏（设置项「后台虚拟屏」）。
     * @param withScreenshot 是否额外截一张图供视觉模型使用。
     */
    suspend fun observe(
        context: Context,
        preferVirtual: Boolean = true,
        withScreenshot: Boolean = false,
    ): Insight = withContext(Dispatchers.IO) {
        // ① 后台虚拟屏：Shizuku 可用时创建独立 Display，读控件树 + OCR + 帧诊断。
        if (preferVirtual) {
            val virtual = runCatching {
                withTimeoutOrNull(12_000L) { QuroVirtualScreen.readForAi(context, withOcr = true) }
            }.getOrNull()
            if (virtual != null && virtual.screenReady) {
                val shot = if (withScreenshot) saveVirtualFrame(context) else null
                return@withContext Insight(
                    mode = Mode.VIRTUAL,
                    text = runCatching { virtual.toPromptText() }.getOrDefault(""),
                    displayId = virtual.displayId,
                    topPackage = virtual.topPackage,
                    screenshotPath = shot,
                    status = "后台虚拟屏已就绪 · ${virtual.width}×${virtual.height}",
                )
            }
            // 虚拟屏不可用（未装/未授权 Shizuku）：不报错误，直接静默降级。
        }

        // ② 当前屏幕：纯内存采集无障碍可读文字，排除 ZorvAI 自身窗口避免自我污染。
        val cue = runCatching { QuroQuietTurn.readScreenCue(context.packageName, maxChars = 900) }
            .getOrNull()

        // ③ 截图：成功时把路径给 Agent，让视觉模型直接看画面（游戏 / 自绘 UI 必需）。
        val shot = if (withScreenshot || cue.isNullOrBlank()) takeMainScreenshot(context) else null

        val mode = when {
            !cue.isNullOrBlank() && shot != null -> Mode.VISION
            !cue.isNullOrBlank() -> Mode.ACCESSIBILITY
            shot != null -> Mode.VISION
            else -> Mode.UNAVAILABLE
        }
        val body = buildString {
            if (!cue.isNullOrBlank()) {
                appendLine("当前屏幕可读文字：")
                appendLine(cue)
            }
            if (shot != null) {
                appendLine("已截图：需要识别图标、布局、图片或游戏画面时，用 visual_analysis 工具看这张图。")
            }
            if (isEmpty()) append("屏幕暂时没有可读内容（可能未开启无障碍权限，或界面是纯图形）。")
        }.trim()

        Insight(
            mode = mode,
            text = body,
            screenshotPath = shot,
            status = when (mode) {
                Mode.UNAVAILABLE -> "暂时读不到屏幕，已按普通对话继续"
                Mode.VISION -> "已看屏幕并截图"
                Mode.ACCESSIBILITY -> "已看当前屏幕"
                else -> mode.label
            },
        )
    }

    private fun takeMainScreenshot(context: Context): String? = runCatching {
        val result = ScreenshotTool().run(context, "{}")
        if (!result.startsWith("✅")) return null
        val path = result.removePrefix("✅ 截图成功: ").trim()
        File(path).takeIf { it.isFile && it.length() > 0 }?.absolutePath
    }.getOrNull()

    private fun saveVirtualFrame(context: Context): String? = runCatching {
        val png = QuroVirtualScreen.captureDisplay(context) ?: return null
        val dir = File(context.cacheDir, "pet_screen").apply { mkdirs() }
        val f = File(dir, "virtual_${System.currentTimeMillis()}.png")
        f.writeBytes(png)
        f.absolutePath
    }.getOrNull()
}
