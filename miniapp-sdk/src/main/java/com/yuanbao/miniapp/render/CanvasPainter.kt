package com.yuanbao.miniapp.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

/**
 * Canvas backend of the self-developed renderer: walks our render tree and
 * issues raw draw calls. No android.view.View is involved anywhere.
 */
class CanvasPainter {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val path = Path()

    /** Images resolved by the host (src -> bitmap). */
    var imageProvider: ((src: String) -> Bitmap?)? = null

    /** px per rpx, kept in sync with the layout engine. */
    var rpxRatio: Float = 1f

    /**
     * 最近一帧实际走到的节点数（诊断用）。
     * 用来区分两种"白屏"：树是空的（3）还是树有内容但一个节点都没画出来。
     */
    var lastDrawnNodes: Int = 0
        private set

    /** 最近一帧画出的非透明底色（诊断用：纯白 + 0 节点 = 页面内容没出来）。 */
    var lastClearColor: Int = 0
        private set

    fun draw(canvas: Canvas, root: RenderNode, viewportWidth: Float, viewportHeight: Float) {
        lastDrawnNodes = 0
        // 视口硬裁剪：AI 页面内容溢出（固定宽度/异常尺寸）也不画出屏幕
        canvas.save()
        canvas.clipRect(0f, 0f, viewportWidth, viewportHeight)
        // 底色跟随页面根背景（深色主题不再先涂白闪屏）
        val rootBg = root.style.backgroundColor
        val clear = if (rootBg != 0 && rootBg != android.graphics.Color.TRANSPARENT) rootBg else Color.WHITE
        lastClearColor = clear
        canvas.drawColor(clear)
        drawNode(canvas, root, root, viewportWidth, viewportHeight)
        canvas.restore()
    }

    private fun drawNode(canvas: Canvas, node: RenderNode, root: RenderNode, vw: Float, vh: Float) {
        if (node.style.display == Display.NONE) return
        val st = node.style
        val alpha = st.opacity.coerceIn(0f, 1f)
        if (alpha <= 0f) return
        lastDrawnNodes++
        // 尺寸为 0 时只跳过"自身"的绘制，绝不跳过子树——绝对定位子元素可以撑在 0 尺寸父级上，
        // 旧逻辑直接 return 会让整棵子树消失（表现为整块空白）。
        val hasBox = node.width > 0f && node.height > 0f

        val scrollable = st.overflow == Overflow.SCROLL
        val saveCount = if (hasBox) canvas.save() else -1
        if (hasBox) {
            if (scrollable) {
                canvas.clipRect(node.absX, node.absY, node.absX + node.width, node.absY + node.height)
            } else if (st.overflow == Overflow.HIDDEN) {
                canvas.clipRect(node.absX, node.absY, node.absX + node.width, node.absY + node.height)
            }
        }

        val alphaSave = if (alpha < 1f && hasBox) {
            canvas.saveLayerAlpha(node.absX, node.absY, node.absX + node.width, node.absY + node.height,
                (alpha * 255).toInt())
        } else -1

        // background + border
        if (hasBox) {
            rect.set(node.absX, node.absY, node.absX + node.width, node.absY + node.height)
            if (st.backgroundColor != Color.TRANSPARENT) {
                fillPaint.color = st.backgroundColor
                fillPaint.style = Paint.Style.FILL
                if (st.borderRadius > 0f) {
                    path.reset()
                    path.addRoundRect(rect, st.borderRadius, st.borderRadius, Path.Direction.CW)
                    canvas.drawPath(path, fillPaint)
                } else {
                    canvas.drawRect(rect, fillPaint)
                }
            }
            if (st.borderWidth > 0f && st.borderColor != Color.TRANSPARENT) {
                borderPaint.color = st.borderColor
                borderPaint.strokeWidth = st.borderWidth
                val half = st.borderWidth / 2f
                rect.set(node.absX + half, node.absY + half,
                    node.absX + node.width - half, node.absY + node.height - half)
                if (st.borderRadius > 0f) {
                    path.reset()
                    path.addRoundRect(rect, st.borderRadius, st.borderRadius, Path.Direction.CW)
                    canvas.drawPath(path, borderPaint)
                } else {
                    canvas.drawRect(rect, borderPaint)
                }
                rect.set(node.absX, node.absY, node.absX + node.width, node.absY + node.height)
            }
        }

        // scroll offset for children
        val childSave = canvas.save()
        if (scrollable) canvas.translate(-node.scrollLeft, -node.scrollTop)

        if (hasBox) {
            when (node.type) {
                NodeType.TEXT -> drawText(canvas, node)
                NodeType.BUTTON -> {
                    drawText(canvas, node)
                }
                NodeType.INPUT -> drawInput(canvas, node)
                NodeType.IMAGE -> drawImage(canvas, node)
                else -> Unit
            }
        }

        // 流内子元素：按 z-index 稳定排序绘制（同 z 保持文档序，后画的在上）
        for (child in node.children.sortedBy { it.style.zIndex }) {
            if (child.style.position == PositionType.ABSOLUTE) continue
            drawNode(canvas, child, root, vw, vh)
        }
        // 绝对定位子元素画在流内容之上，但仍在 overflow 裁剪 / 滚动位移范围内。
        // 旧实现放在 restore 之后 → 带 overflow:hidden 的卡片里，绝对定位元素会"逃出"卡片
        // 盖住别的卡片（就是看到的"一层盖一层"），滚动容器里也不跟随滚动。
        for (child in node.children.sortedBy { it.style.zIndex }) {
            if (child.style.position == PositionType.ABSOLUTE) {
                drawNode(canvas, child, root, vw, vh)
            }
        }
        canvas.restoreToCount(childSave)

        if (alphaSave != -1) canvas.restoreToCount(alphaSave)
        if (saveCount != -1) canvas.restoreToCount(saveCount)
    }

    private fun drawText(canvas: Canvas, node: RenderNode) {
        val st = node.style
        val lines = node.lines
        if (lines.isEmpty()) return
        val fs = st.fontSize
        val lh = if (st.lineHeight.isNaN()) fs * 1.25f else st.lineHeight
        textPaint.color = st.color
        textPaint.textSize = fs
        textPaint.isFakeBoldText = st.fontWeight == FontWeight.BOLD
        textPaint.textAlign = Paint.Align.LEFT

        val padL = st.padding.left.resolve(node.width, 0f, rpxRatio) ?: 0f
        val padR = st.padding.right.resolve(node.width, 0f, rpxRatio) ?: 0f
        val padT = st.padding.top.resolve(node.height, 0f, rpxRatio) ?: 0f
        val contentW = maxOf(0f, node.width - padL - padR)

        val fm = textPaint.fontMetrics
        val baselineOffset = (lh - (fm.descent - fm.ascent)) / 2f - fm.ascent
        var y = node.absY + padT + baselineOffset

        val maxLines = if (st.maxLines > 0) st.maxLines else lines.size
        for (i in 0 until minOf(lines.size, maxLines)) {
            val line = lines[i]
            val lw = textPaint.measureText(line)
            val x = when (st.textAlign) {
                TextAlign.CENTER -> node.absX + padL + (contentW - lw) / 2f
                TextAlign.RIGHT -> node.absX + padL + contentW - lw
                TextAlign.LEFT -> node.absX + padL
            }
            canvas.drawText(line, x, y, textPaint)
            y += lh
        }
    }

    private fun drawInput(canvas: Canvas, node: RenderNode) {
        val st = node.style
        val value = node.attributes["value"] ?: ""
        val placeholder = node.attributes["placeholder"] ?: ""
        val show = if (value.isNotEmpty()) value else placeholder
        textPaint.color = if (value.isNotEmpty()) st.color else Color.GRAY
        textPaint.textSize = st.fontSize
        textPaint.isFakeBoldText = st.fontWeight == FontWeight.BOLD
        val fm = textPaint.fontMetrics
        val padL = st.padding.left.resolve(node.width, 0f, rpxRatio) ?: 0f
        val baseline = node.absY + node.height / 2f - (fm.descent + fm.ascent) / 2f
        canvas.drawText(show, node.absX + padL, baseline, textPaint)
        // underline like a native input
        fillPaint.color = st.borderColor
        canvas.drawRect(node.absX, node.absY + node.height - 1f,
            node.absX + node.width, node.absY + node.height, fillPaint)
    }

    private fun drawImage(canvas: Canvas, node: RenderNode) {
        val src = node.attributes["src"] ?: return
        val bmp = imageProvider?.invoke(src) ?: return
        val st = node.style
        val dst = RectF(node.absX, node.absY, node.absX + node.width, node.absY + node.height)
        bitmapPaint.xfermode = null
        if (st.borderRadius > 0f) {
            val save = canvas.saveLayer(dst.left, dst.top, dst.right, dst.bottom, null)
            path.reset()
            path.addRoundRect(dst, st.borderRadius, st.borderRadius, Path.Direction.CW)
            canvas.clipPath(path)
            canvas.drawBitmap(bmp, null, dst, bitmapPaint)
            canvas.restoreToCount(save)
        } else {
            canvas.drawBitmap(bmp, null, dst, bitmapPaint)
        }
    }
}
