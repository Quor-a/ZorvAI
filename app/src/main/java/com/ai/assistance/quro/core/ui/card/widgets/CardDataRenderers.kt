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
class TableState : CardState
class TableRenderer : CardRenderer<TableState> {
    override fun createInitialState() = TableState()
    override fun measure(spec: CardSpec, state: TableState, maxWidthPx: Float): Size {
        val media = spec.data as? CardData.Media
        val rowCount = (media?.rows?.size ?: 0) + if (media?.headers?.isNotEmpty() == true) 1 else 0
        val rowH = 30f
        return Size(maxWidthPx, 12f + rowCount * rowH + 12f)
    }
    override fun layout(spec: CardSpec, state: TableState, size: Size): LayoutResult =
        LayoutResult(size.width, size.height)
    override fun render(backend: RenderBackend, spec: CardSpec, result: LayoutResult, state: TableState) {
        val media = spec.data as? CardData.Media ?: return
        val headers = media.headers
        val allRows = if (headers.isNotEmpty()) listOf(headers) + media.rows else media.rows
        val colCount = (allRows.map { it.size }.maxOrNull() ?: 0).coerceAtLeast(1)
        val colW = result.width / colCount
        val rowH = 30f
        var y = 12f
        allRows.forEachIndexed { ri, row ->
            val isHeader = ri == 0 && headers.isNotEmpty()
            backend.drawRect(0f, y, result.width, y + rowH,
                backend.resolve(if (isHeader) ColorToken.SurfaceVariant else ColorToken.Surface), radiusDp = 0f)
            row.forEachIndexed { ci, cell ->
                val text = if (cell.length > 40) cell.take(40) + "…" else cell
                backend.drawText(text, ci * colW + 8f, y + rowH / 2f - 8f,
                    backend.resolve(ColorToken.OnSurface), 13f, if (isHeader) 600 else 400)
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
class StatusState : CardState
class StatusRenderer : CardRenderer<StatusState> {
    override fun createInitialState() = StatusState()
    override fun measure(spec: CardSpec, state: StatusState, maxWidthPx: Float): Size {
        val kind = (spec.data as? CardData.Status)?.statusType
        val h = when (kind) {
            "progress" -> 72f
            "error" -> 84f
            else -> 60f
        }
        return Size(maxWidthPx, h)
    }
    override fun layout(spec: CardSpec, state: StatusState, size: Size): LayoutResult =
        LayoutResult(size.width, size.height)
    override fun render(backend: RenderBackend, spec: CardSpec, result: LayoutResult, state: StatusState) {
        val st = spec.data as? CardData.Status ?: return
        val pad = 14f
        when (st.statusType) {
            "progress" -> {
                backend.drawText(st.text.ifEmpty { "进度" }, pad, 18f, backend.resolve(ColorToken.OnSurface), 14f, 600)
                val barTop = 44f
                val barH = 10f
                val barW = result.width - pad * 2
                backend.drawRect(pad, barTop, pad + barW, barTop + barH, backend.resolve(ColorToken.SurfaceVariant), radiusDp = 5f)
                val fillW = barW * st.progress.coerceIn(0f, 1f)
                if (fillW > 0f) {
                    backend.drawRect(pad, barTop, pad + fillW, barTop + barH, backend.resolve(ColorToken.Primary), radiusDp = 5f)
                }
            }
            "error" -> {
                backend.drawText("错误", pad, 18f, backend.resolve(ColorToken.Danger), 14f, 700)
                val reason = st.reason ?: st.text
                if (reason.isNotEmpty()) {
                    backend.drawText(if (reason.length > 60) reason.take(60) + "…" else reason, pad, 42f, backend.resolve(ColorToken.OnSurface), 13f, 400)
                }
                if (st.retryable) {
                    backend.drawText("点击重试", pad, 66f, backend.resolve(ColorToken.Primary), 13f, 600)
                }
            }
            "tool_call" -> {
                backend.drawText(st.text.ifEmpty { "工具调用中…" }, pad, 18f, backend.resolve(ColorToken.OnSurface), 14f, 500)
            }
            else -> {
                backend.drawText(st.text.ifEmpty { st.statusType }, pad, 18f, backend.resolve(ColorToken.OnSurfaceVariant), 14f, 400)
            }
        }
    }
    override fun hitTest(p: Offset, result: LayoutResult, state: StatusState): HitTarget? = null
}
