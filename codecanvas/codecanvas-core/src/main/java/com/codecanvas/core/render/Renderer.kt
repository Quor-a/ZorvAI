package com.codecanvas.core.render

import com.codecanvas.core.model.CanvasSpec
import com.codecanvas.core.script.DrawList

/**
 * 渲染后端枚举 —— 「多架构」的四种实现。
 *
 * 选型建议：
 * - CANVAS   ：纯原生 Bitmap + Canvas，无额外依赖，速度最快，适合脚本绘图
 * - WEBVIEW  ：HTML/CSS 排版，唯一能做到 Carbon/ray.so 观感的方案，适合代码卡片、Markdown
 * - COMPOSE  ：直接产出 Compose ImageBitmap，适合在 UI 里内联展示
 * - SVG      ：矢量输出，无限放大不失真，适合流程图、导出到印刷/文档
 */
enum class RenderKind(val id: String, val supportsVector: Boolean) {
    CANVAS("canvas", false),
    WEBVIEW("webview", false),
    COMPOSE("compose", false),
    SVG("svg", true),
}

/** 渲染输入源：既能吃脚本产出的指令，也能吃代码卡片 / HTML */
sealed interface RenderSource {
    data class Commands(val list: DrawList) : RenderSource

    /** 代码卡片：走高亮 + WebView/Canvas 排版 */
    data class CodeCard(
        val code: String,
        val language: String,
        val theme: String = "atom-one-dark",
        val showLineNumbers: Boolean = true,
        val title: String? = null,
    ) : RenderSource

    /** 任意 HTML/Markdown，仅 WEBVIEW 后端支持 */
    data class Markup(val html: String) : RenderSource
}

interface Renderer {
    val kind: RenderKind

    /** 该后端是否支持某种输入源（例如 SVG 后端不支持 Markup） */
    fun supports(source: RenderSource): Boolean

    suspend fun render(spec: CanvasSpec, source: RenderSource): RenderOutput

    fun release()
}

sealed interface RenderOutput {
    /** 位图结果 */
    data class Bitmap(val bitmap: android.graphics.Bitmap) : RenderOutput

    /** 矢量结果（SVG 字符串），可直接落盘或转 PDF */
    data class Vector(val svg: String, val widthPx: Int, val heightPx: Int) : RenderOutput

    data class ComposeBitmap(val imageBitmap: androidx.compose.ui.graphics.ImageBitmap) : RenderOutput
}
