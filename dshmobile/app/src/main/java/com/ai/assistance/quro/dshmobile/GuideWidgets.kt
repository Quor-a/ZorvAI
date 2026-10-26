package com.ai.assistance.quro.dshmobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 引导页的自绘组件（#448 整页重写）。
 *
 * 用户第五次投诉的措辞是「**我要的是重写，不是改**」——也就是说前几轮把旧骨架
 * （居中品牌门面 + 白卡叠白卡 + 全宽按钮楼梯）摆正、换色、调间距，都不是他要的东西。
 * 这次换的是**组件本身**：仪表环、相位步骤条、控制台窗，三个都是新画的，
 * 不复用任何旧的「卡片 + TextView」组合。
 *
 * 三个组件只负责「画」，数据由 GuidePageRenderer 的相位驱动，
 * 不自己推断引擎状态（不造假信息）。
 */

/** 相位在视觉上的三态。 */
internal enum class DialTone { Busy, Failed, Ready }

/**
 * 环形仪表：状态页的视觉主体。
 *
 * 旧形态是「白卡里塞一个 30dp 空心环 + 10dp 圆点 + 23sp 标题」——那只比徽标大一点，
 * 撑不起整页。新形态是一枚 **168dp 的仪表环**：轨道环 + 相位弧 + 外围刻度 +
 * 环心相位短词。忙碌时弧段沿环自转（不定档，如实表达「还在动」而不编造百分比），
 * 故障时整环转红并停转，就绪时满环转绿。
 */
internal class GuideStatusDial(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(10f)
        strokeCap = Paint.Cap.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(10f)
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val rect = RectF()

    /** 相位色（轨道/弧/刻度/文字统一由此推导）。 */
    private var tone = DialTone.Ready
    private var centerMain = ""
    private var centerSub: String? = null
    private var fraction: Float? = null
    private var spinDeg = 0f

    init {
        setWillNotDraw(false)
        trackPaint.color = context.getColor(R.color.ds_progress_track)
        mainPaint.color = context.getColor(R.color.ds_text_primary)
        subPaint.color = context.getColor(R.color.ds_text_tertiary)
        mainPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 21f, resources.displayMetrics,
        )
        subPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics,
        )
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.ds_dial_a11y)
    }

    fun setTone(next: DialTone) {
        if (tone == next) return
        tone = next
        invalidate()
    }

    /** 环心文案：主词大号、副注小号灰。传空串即不画。 */
    fun setCenter(main: String, sub: String? = null) {
        if (centerMain == main && centerSub == sub) return
        centerMain = main
        centerSub = sub
        invalidate()
    }

    /**
     * 确定档进度（0..1）；`null` = 不定档（弧段自转）。
     *
     * 🔴 只接受**真实**进度来源。产品口径（0.14.2 P2）是：解压总量不可信，
     * 所以一律传 null，绝不按编造常数画百分比。
     */
    fun setFraction(value: Float?) {
        val next = value?.coerceIn(0f, 1f)
        if (fraction == next) return
        fraction = next
        invalidate()
    }

    /** 弧色：忙碌/不定档 = 陶土；故障 = 危险红；就绪 = 品牌成功绿。 */
    private fun arcColor(): Int = when (tone) {
        DialTone.Busy -> context.getColor(R.color.ds_accent)
        DialTone.Failed -> context.getColor(R.color.ds_danger)
        DialTone.Ready -> context.getColor(R.color.ds_ok)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val inset = dp(14f)
        val size = minOf(w, h) - inset * 2
        val left = (w - size) / 2f
        val top = (h - size) / 2f
        rect.set(left, top, left + size, top + size)
        val cx = rect.centerX()
        val cy = rect.centerY()
        val baseR = size / 2f

        // 轨道环
        canvas.drawCircle(cx, cy, baseR - trackPaint.strokeWidth / 2f, trackPaint)

        // 外围刻度：每 30° 一根长刻度、每 6° 一根短刻度（读数感，不承载数据）
        tickPaint.color = context.getColor(R.color.ds_hairline)
        val tickOut = baseR + dp(2f)
        for (i in 0 until 60) {
            val long = i % 5 == 0
            val len = if (long) dp(6f) else dp(3f)
            val rad = Math.toRadians((i * 6).toDouble())
            val sx = cx + (tickOut * Math.cos(rad)).toFloat()
            val sy = cy + (tickOut * Math.sin(rad)).toFloat()
            val ex = cx + ((tickOut + len) * Math.cos(rad)).toFloat()
            val ey = cy + ((tickOut + len) * Math.sin(rad)).toFloat()
            canvas.drawLine(sx, sy, ex, ey, tickPaint)
        }

        // 相位弧
        arcPaint.color = arcColor()
        val arcRect = RectF(rect)
        arcRect.inset(trackPaint.strokeWidth / 2f, trackPaint.strokeWidth / 2f)
        val f = fraction
        if (f != null) {
            canvas.drawArc(arcRect, -90f, 360f * f, false, arcPaint)
        } else if (tone == DialTone.Busy) {
            // 不定档：一段 90° 的弧沿环自转。不用 ValueAnimator，直接在 draw 相位推进，
            // 只在可见且忙碌时续帧（onDraw 里 postInvalidateOnAnimation），页一藏就停。
            canvas.drawArc(arcRect, spinDeg, 90f, false, arcPaint)
            spinDeg = (spinDeg + 2.4f) % 360f
            if (isShown) postInvalidateOnAnimation()
        } else {
            // 就绪/故障且没有进度：画满环（故障时是红满环，一眼「停在这里了」）
            canvas.drawArc(arcRect, -90f, 360f, false, arcPaint)
        }

        // 环心文案（主词 + 副注，垂直居中偏上一点，读起来更稳）
        val mainY = cy + mainPaint.textSize / 3f
        if (centerMain.isNotEmpty()) canvas.drawText(centerMain, cx, mainY, mainPaint)
        centerSub?.takeIf { it.isNotEmpty() }?.let {
            canvas.drawText(it, cx, mainY + subPaint.textSize + dp(8f), subPaint)
        }
    }
}

/**
 * 相位步骤条：把「引擎从解压到就绪」的三段路摆出来。
 *
 * 为什么值得一个组件：用户看到「启动失败 5s 后重试」时最想知道的是**卡在哪一段**。
 * 旧版只有一句标题，不回答这个问题。这里三段（抽取运行时 / 启动引擎 / 就绪）
 * 各自显示状态，失败相位停在当前段并标红。
 *
 * 数据来源只有相位，不推断、不估算。
 */
internal class GuideStepper(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
    }
    private val statePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        textAlign = Paint.Align.RIGHT
    }

    private val steps = arrayOf(
        context.getString(R.string.ds_step_extract),
        context.getString(R.string.ds_step_launch),
        context.getString(R.string.ds_step_ready),
    )

    private var activeIndex = -1
    private var failed = false
    private var allDone = false

    init {
        labelPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        statePaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        labelPaint.color = context.getColor(R.color.ds_text_secondary)
        statePaint.color = context.getColor(R.color.ds_text_tertiary)
    }

    /**
     * 相位 → 段位。
     *
     * 映射是**穷举**的：新增相位若忘了归类，编译期不会报错，所以这里用 when 全分支，
     * 未归类的一律落到「未开始」，绝不猜。
     */
    fun applyPhase(phase: GuidePhase) {
        failed = false
        allDone = false
        activeIndex = when (phase) {
            GuidePhase.Extracting, GuidePhase.Updating, GuidePhase.Undoing -> 0
            GuidePhase.Starting, GuidePhase.Recovering -> 1
            GuidePhase.Idle, GuidePhase.Info -> {
                allDone = true
                2
            }
            GuidePhase.Error, GuidePhase.Closed -> {
                failed = true
                1
            }
        }
        invalidate()
    }

    private val rowH: Float get() = dp(34f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (rowH * steps.size).toInt() + dp(4f).toInt())
    }

    private fun toneFor(index: Int): Int = when {
        failed && index == activeIndex -> context.getColor(R.color.ds_danger)
        allDone -> context.getColor(R.color.ds_ok)
        index < activeIndex -> context.getColor(R.color.ds_ok)
        index == activeIndex -> context.getColor(R.color.ds_accent)
        else -> context.getColor(R.color.ds_hairline)
    }

    private fun stateFor(index: Int): String = when {
        failed && index == activeIndex -> context.getString(R.string.ds_step_failed)
        allDone -> context.getString(R.string.ds_step_done)
        index < activeIndex -> context.getString(R.string.ds_step_done)
        index == activeIndex -> context.getString(R.string.ds_step_active)
        else -> context.getString(R.string.ds_step_pending)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dotCx = dp(6f)
        val textX = dp(24f)
        for (i in steps.indices) {
            val cy = rowH * i + rowH / 2f
            val tone = toneFor(i)
            // 连接线（先画，站点压在上面）
            if (i < steps.size - 1) {
                linePaint.color = context.getColor(R.color.ds_hairline)
                linePaint.strokeWidth = dp(1.5f)
                val bottom = rowH * (i + 1) + rowH / 2f
                canvas.drawLine(dotCx, cy + dp(7f), dotCx, bottom - dp(7f), linePaint)
            }
            // 站点：实心 = 已走过/当前，空心 = 未到
            if (i <= activeIndex || allDone) {
                dotPaint.color = tone
                dotPaint.style = Paint.Style.FILL
                canvas.drawCircle(dotCx, cy, dp(5f), dotPaint)
            } else {
                ringPaint.color = tone
                ringPaint.strokeWidth = dp(1.5f)
                canvas.drawCircle(dotCx, cy, dp(4.5f), ringPaint)
            }
            // 当前段加一圈外环，强调「就在这里」
            if (i == activeIndex && !allDone) {
                ringPaint.color = tone
                ringPaint.strokeWidth = dp(1.5f)
                canvas.drawCircle(dotCx, cy, dp(8f), ringPaint)
            }
            labelPaint.color = if (i <= activeIndex || allDone) {
                context.getColor(R.color.ds_text_primary)
            } else {
                context.getColor(R.color.ds_text_tertiary)
            }
            val baseline = cy - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(steps[i], textX, baseline, labelPaint)
            statePaint.color = tone
            canvas.drawText(stateFor(i), width - dp(2f), baseline, statePaint)
        }
    }
}

/**
 * 控制台窗：日志不该是一张「白卡里 8 行灰字」，它本来就是终端窗口。
 *
 * 形态：深色窗面 + 标题栏（三枚窗点 + 文件名 + 复制 pill）+ 等宽正文。
 * 与页面其余暖纸底形成硬对比——一眼能分清「这是机器的输出，不是产品文案」。
 */
internal class GuideConsolePlate(context: Context) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun dpi(v: Float) = (v * density).toInt()

    init {
        orientation = VERTICAL
        // radius 是 Float（DsUi.roundRect 的契约），所以这里用 dp() 而不是 dpi()
        background = DsUi.roundRect(
            context.getColor(R.color.ds_text_primary),
            dp(14f),
            null,
            0,
        )
    }

    /** 标题栏：左侧活点（相位色由渲染层刷）+ 静态窗点 + 文件名，右侧动作槽。 */
    fun buildHeader(title: String, liveDot: View, action: View?): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(14f), dpi(10f), dpi(10f), dpi(6f))
        }
        bar.addView(liveDot, LayoutParams(dpi(8f), dpi(8f)))
        // 两枚静态窗点：纯装饰，告诉眼睛「这是个窗口」
        for (i in 0 until 2) {
            val d = View(context)
            d.background = DsUi.oval(context.getColor(R.color.ds_text_secondary))
            bar.addView(d, LayoutParams(dpi(6f), dpi(6f)).apply { marginStart = dpi(6f) })
        }
        bar.addView(
            TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(context.getColor(R.color.ds_bg))
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.04f
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpi(10f) },
        )
        action?.let { bar.addView(it) }
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return bar
    }

    /** 正文槽：等宽、暖纸色字、行距放宽（终端里挤成一坨最难读）。 */
    fun attachBody(body: TextView) {
        body.setTextColor(context.getColor(R.color.ds_bg))
        body.typeface = Typeface.MONOSPACE
        body.setLineSpacing(0f, 1.5f)
        body.setPadding(dpi(14f), 0, dpi(14f), dpi(12f))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
}
