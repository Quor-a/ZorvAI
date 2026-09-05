package com.ai.assistance.quro.core.ui.card.widgets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.ai.assistance.quro.core.ui.card.registry.CardRenderer
import com.ai.assistance.quro.core.ui.card.registry.CardState
import com.ai.assistance.quro.core.ui.card.registry.HitBox
import com.ai.assistance.quro.core.ui.card.registry.HitTarget
import com.ai.assistance.quro.core.ui.card.registry.LayoutResult
import com.ai.assistance.quro.core.ui.card.render.RenderBackend
import com.ai.assistance.quro.core.ui.card.spec.CardData
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.ColorToken

/**
 * 补齐 [CardSpec] 协议已声明但此前无渲染器的两种数据形态：
 *  - [CardData.Media]（表格 / 时间线 / 代码）→ 这里实现最常用的「表格卡」(`type="table"`)。
 *  - [CardData.Status]（进度 / 错误 / 工具调用）→ 「状态卡」(`type="status"`)。
 * 端上自写测量/排版/绘制，不依赖任何内置/三方成品控件。
 */

// ── 表格卡（media.kind=media, mediaType=table）──
class TableState : CardState {
    /** measure 时缓存的 density（render 算真实行高用，与 CustomCardState 同模式） */
    var density: Float = 2.75f
}
class TableRenderer : CardRenderer<TableState> {
    override fun createInitialState() = TableState()
    override fun measure(spec: CardSpec, state: TableState, maxWidthPx: Float, density: Float): Size {
        state.density = density
        val media = spec.data as? CardData.Media
        val rowCount = (media?.rows?.size ?: 0) + if (media?.headers?.isNotEmpty() == true) 1 else 0
        // 行高按 sp 13 字号密度算（含 padding），不会被 Canvas 裁切（v1.0.82 cardfix6 教训）
        val rowH = spPx(13f, density) + 14f
        val padV = 12f
        return Size(maxWidthPx, padV + rowCount * rowH + padV)
    }
    override fun layout(spec: CardSpec, state: TableState, size: Size, density: Float): LayoutResult =
        LayoutResult(size.width, size.height)
    override fun render(backend: RenderBackend, spec: CardSpec, result: LayoutResult, state: TableState) {
        val media = spec.data as? CardData.Media ?: return
        val headers = media.headers
        val allRows = if (headers.isNotEmpty()) listOf(headers) + media.rows else media.rows
        val colCount = (allRows.map { it.size }.maxOrNull() ?: 0).coerceAtLeast(1)
        val colW = result.width / colCount
        // 行高必须与 measure 用同一个公式（spPx + padding），否则 13sp 文字真实高度会撑破 rowH 被 Canvas 裁切
        val d = state.density
        val rowH = spPx(13f, d) + 14f
        // 文字垂直居中：drawText topLeft = baseline≈y + sizeSp/3；用 rowH 中心 - sizeSp/2 估算（视觉居中）
        val textFs = 13f
        val textTopOffset = rowH / 2f - spPx(textFs, d) / 2f
        var y = 12f
        // 单列最多能放几个字（CJK 按 1em 估宽），超出截断防横向溢出压到相邻列
        val maxCharsPerCol = (((colW - 16f) / (textFs * d)).toInt()).coerceAtLeast(2)
        allRows.forEachIndexed { ri, row ->
            val isHeader = ri == 0 && headers.isNotEmpty()
            backend.drawRect(0f, y, result.width, y + rowH,
                backend.resolve(if (isHeader) ColorToken.SurfaceVariant else ColorToken.Surface), radiusDp = 0f)
            row.forEachIndexed { ci, cell ->
                val text = if (cell.length > maxCharsPerCol) cell.take(maxCharsPerCol) + "…" else cell
                backend.drawText(text, ci * colW + 8f, y + textTopOffset,
                    backend.resolve(ColorToken.OnSurface), textFs, if (isHeader) 600 else 400)
            }
            backend.drawLine(0f, y + rowH, result.width, y + rowH, backend.resolve(ColorToken.Outline), 1f)
            y += rowH
        }
        for (c in 1 until colCount) {
            backend.drawLine(c * colW, 12f, c * colW, y, backend.resolve(ColorToken.Outline), 1f)
        }
    }
    override fun hitTest(p: Offset, result: LayoutResult, state: TableState): HitTarget? = null
}

// ── 状态卡（status.kind=status：progress / error / tool_call / 其它）──
class StatusState : CardState {
    /** measure 时缓存 density（render 算 sp 真实行高用） */
    var density: Float = 2.75f
}
class StatusRenderer : CardRenderer<StatusState> {
    override fun createInitialState() = StatusState()
    override fun measure(spec: CardSpec, state: StatusState, maxWidthPx: Float, density: Float): Size {
        state.density = density
        val kind = (spec.data as? CardData.Status)?.statusType
        // 按 sp 14 文字密度算（含 padding）；错误/重试两行加 reason 文字
        val oneLine = spPx(14f, density) + 14f
        val twoLine = spPx(14f, density) * 2 + 22f
        val h = when (kind) {
            "progress" -> oneLine + 14f + 8f      // 标题 + 进度条 + gap
            "error" -> twoLine + spPx(13f, density) + 10f  // 标题 + reason + 重试提示
            else -> oneLine
        }
        return Size(maxWidthPx, h)
    }
    override fun layout(spec: CardSpec, state: StatusState, size: Size, density: Float): LayoutResult =
        LayoutResult(size.width, size.height)
    override fun render(backend: RenderBackend, spec: CardSpec, result: LayoutResult, state: StatusState) {
        val st = spec.data as? CardData.Status ?: return
        val d = state.density
        val pad = 14f
        val padTop = 14f
        // y 坐标必须按 sp 真实行高算（topLeft），否则 14sp 文字会被进度条/下一行覆盖（v1.0.82 cardfix7 教训）
        val titleFs = 14f
        val bodyFs = 13f
        when (st.statusType) {
            "progress" -> {
                val titleY = padTop
                backend.drawText(st.text.ifEmpty { "进度" }, pad, titleY, backend.resolve(ColorToken.OnSurface), titleFs, 600)
                // 进度条画在标题文字之下，避免覆盖
                val barTop = titleY + spPx(titleFs, d) + 6f
                val barH = 10f
                val barW = result.width - pad * 2
                backend.drawRect(pad, barTop, pad + barW, barTop + barH, backend.resolve(ColorToken.SurfaceVariant), radiusDp = 5f)
                val fillW = barW * st.progress.coerceIn(0f, 1f)
                if (fillW > 0f) {
                    backend.drawRect(pad, barTop, pad + fillW, barTop + barH, backend.resolve(ColorToken.Primary), radiusDp = 5f)
                }
            }
            "error" -> {
                val titleY = padTop
                backend.drawText("错误", pad, titleY, backend.resolve(ColorToken.Danger), titleFs, 700)
                val reason = st.reason ?: st.text
                val reasonY = titleY + spPx(titleFs, d) + 4f
                if (reason.isNotEmpty()) {
                    backend.drawText(if (reason.length > 60) reason.take(60) + "…" else reason, pad, reasonY, backend.resolve(ColorToken.OnSurface), bodyFs, 400)
                }
                if (st.retryable) {
                    val retryY = reasonY + spPx(bodyFs, d) + 6f
                    backend.drawText("点击重试", pad, retryY, backend.resolve(ColorToken.Primary), bodyFs, 600)
                }
            }
            "tool_call" -> {
                backend.drawText(st.text.ifEmpty { "工具调用中…" }, pad, padTop, backend.resolve(ColorToken.OnSurface), titleFs, 500)
            }
            else -> {
                backend.drawText(st.text.ifEmpty { st.statusType }, pad, padTop, backend.resolve(ColorToken.OnSurfaceVariant), titleFs, 400)
            }
        }
    }
    override fun hitTest(p: Offset, result: LayoutResult, state: StatusState): HitTarget? = null
}
