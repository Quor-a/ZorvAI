package com.ai.assistance.quro.dshmobile

import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Native startup / fallback screen: status dial, live phase, diagnostics, actions.
 *
 * #448（2026-10-26 用户第五次投诉，原话「**我要的是重写，不是改**」）—— 前四轮的教训是：
 * 把旧骨架（居中品牌门面 + 白卡叠白卡 + 全宽按钮楼梯）摆正、换色、调间距，都不叫重写。
 * 所以这次**换的是组件与骨架本身**，不是它们的排列：
 *
 *   1. **顶部状态条**（brandBlock → topBar）：不再有「56dp logo / 26sp 名称 / 副题 / 版本 pill」
 *      的居中英雄区。品牌退成一条 30dp logo + 竖排名称，右端一枚**相位胶囊**
 *      （活点 + 相位短词）——首屏第一行就回答「它现在怎么样」。
 *   2. **环形仪表**（[GuideStatusDial]，新组件）：168dp 自绘仪表，轨道环 + 相位弧 +
 *      外围刻度 + 环心相位短词。忙碌时弧段沿环自转、故障时红满环、就绪时绿满环。
 *      **不画百分比**：解压总量不可信，仪表只表达「还在动 / 停了 / 好了」。
 *   3. **相位步骤条**（[GuideStepper]，新组件）：抽取运行时 → 启动引擎 → 就绪 三段。
 *      失败现场第一个问题是「卡在哪一段」，旧版只有一句标题答不出。
 *   4. **控制台窗**（[GuideConsolePlate]，新组件）：日志从「白卡里 8 行灰字」变成
 *      深色终端窗口（窗点 + 文件名 + 复制 pill + 等宽正文），一眼分清「机器的输出」与
 *      「产品文案」。窗口自带容器，页面其余部分是暖纸底。
 *   5. **底部命令条**：主操作 accent 实心胶囊 + 圆形齿轮同排；控制台降为右对齐文本链，
 *      修复助手只在失败相位出现的半宽次按钮。
 *
 * 色板契约（品牌暖纸体系）仍由 GuidePaletteMonochromeTest 逐值守护；本文件只负责
 * 结构与层级。仍然保留的 pinned 形态：根画布平铺 ds_bg、主操作 accent 实心胶囊、
 * `shareLog` **不挂进**控制台窗（窗会因无日志而 GONE，恰在「日志都没生成」时才最需要它）、
 * 版本戳单行省略且限宽放得下完整戳、`ds_guide_max_width` 大屏钳制。
 */
internal class GuideChrome(
  val root: LinearLayout,
  /** 顶部状态条（#448 重写：取代旧的居中品牌门面）。 */
  val brandBlock: View,
  /** 仪表台（环形仪表的容器），入场动画的第二个落点。 */
  val cardBlock: View,
  /** 诊断区块：控制台窗 + 分享入口 + chips 的容器。 */
  val diagBlock: View,
  val actionBlock: View,
  val engineStatus: TextView,
  val statusHint: TextView,
  val statusDot: View,
  /** 状态活点的空环：渲染层按相位重绘描边（styleStatusHalo）。 */
  val statusHalo: View,
  /** 顶部条右端的状态胶囊文字（#448 新增）：相位短词，与环/点同一判据。 */
  val phaseChip: TextView,
  /** 环形仪表（#448 新增）：相位驱动弧色、环心文案与自转。 */
  val dial: GuideStatusDial,
  /** 相位步骤条（#448 新增）：抽取运行时 → 启动引擎 → 就绪。 */
  val stepper: GuideStepper,
  val crashBanner: TextView,
  val progressBar: ProgressBar,
  val progressText: TextView,
  val logSummary: TextView,
  val logSection: View,
  /** 诊断区标题行的相位点（#447b）：与状态 dot 同判据同时机换色——日志段亮着时，
   * 「引擎此刻是忙是挂」在诊断卡头部也有一份信号，不必回滚到主状态卡确认。 */
  val logDot: View,
  val copyLog: TextView,
  val shareLog: TextView,
  val primaryButton: Button,
  /** 离线控制台入口：错误页/引导页常驻可点（undo 急救链的 UI 侧落点）。 */
  val consoleButton: Button,
  /** Secondary startup action: retry the engine, or open the repair assistant. */
  val repairButton: Button,
  val runtimeChip: TextView,
  val storageChip: TextView,
  val versionLabel: TextView,
  val homeContent: View,
  val managementHost: FrameLayout,
  val gear: android.widget.ImageButton,
)

internal class GuideCallbacks(
  val onStartEngine: () -> Unit,
  /** 打开离线控制台（undo 急救 CLI 的运行界面）：错误页的显式恢复路径，不随任何更新链走。 */
  val onOpenConsole: () -> Unit,
  val onGrantStorage: () -> Unit,
  val onCopyLog: () -> Unit,
  val onShareLog: () -> Unit,
  val onManagement: () -> Unit = {},
  val onRepairAssistant: () -> Unit = {},
)

internal fun buildGuideChrome(activity: ComponentActivity, callbacks: GuideCallbacks): GuideChrome {
  val res = activity.resources
  fun dp(v: Float) = (v * res.displayMetrics.density).toInt()
  fun dim(id: Int) = res.getDimension(id)
  fun dpix(id: Int) = res.getDimensionPixelSize(id)
  fun color(id: Int) = activity.getColor(id)
  fun typeMedium() = Typeface.create("sans-serif-medium", Typeface.NORMAL)

  val hairline = (res.displayMetrics.density).toInt().coerceAtLeast(1)
  val ringStroke = (1.5f * res.displayMetrics.density).toInt().coerceAtLeast(1)

  val root = LinearLayout(activity).apply {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(color(R.color.ds_bg))
    visibility = View.GONE
  }

  val content = LinearLayout(activity).apply {
    orientation = LinearLayout.VERTICAL
    val g = dpix(R.dimen.ds_guide_gutter)
    setPadding(g, 0, g, dp(8f))
  }

  // ══ ① 顶部状态条 ═════════════════════════════════════════════════════════
  // 旧形态（#446/#447b）是**居中纵向品牌门面**：56dp logo / 26sp 名称 / 副题 / 版本 pill，
  // 占掉首屏上三分之一，且完全不提「机器在干什么」。用户第五次投诉的原话是
  // 「我要的是重写，不是改」——把旧骨架摆正、换色、调间距都不算。
  // 新形态：一条**状态条**——左 logo 30dp + 竖排名称/副题，右侧一枚相位胶囊
  // （活点 + 相位短词）。品牌退成「谁在这儿」，首屏让位给「它怎么样了」。
  val brandMark = ImageView(activity).apply {
    setImageResource(R.drawable.dshm_brand_logo)
    layoutParams = LinearLayout.LayoutParams(dp(30f), dp(30f))
    contentDescription = activity.getString(R.string.app_name)
  }
  val nameStack = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
  nameStack.addView(
    TextView(activity).apply {
      text = activity.getString(R.string.app_name)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
      setTextColor(color(R.color.ds_text_primary))
      typeface = typeMedium()
      gravity = Gravity.START
    },
    LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ),
  )
  nameStack.addView(
    TextView(activity).apply {
      text = activity.getString(R.string.ds_brand_subtitle)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
      setTextColor(color(R.color.ds_text_tertiary))
      gravity = Gravity.START
      setPadding(0, dp(1f), 0, 0)
    },
    LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ),
  )

  // 相位胶囊：活点（渲染层按相位换色 + 脉冲）+ 相位短词（渲染层写文案）。
  // statusHalo/statusDot 仍是同一对控件——渲染层的换色与脉冲逻辑一行不改即生效，
  // 只是它们从「卡里的英雄光晕」降为「状态条上的一枚活点」。
  val statusHalo = FrameLayout(activity).apply {
    layoutParams = LinearLayout.LayoutParams(dp(16f), dp(16f))
    background = ovalRing(color(R.color.ds_hairline), ringStroke)
  }
  val statusDot = View(activity).apply {
    layoutParams = FrameLayout.LayoutParams(dp(6f), dp(6f), Gravity.CENTER)
    background = DsUi.oval(color(R.color.ds_text_tertiary))
  }
  statusHalo.addView(statusDot)
  val phaseChip = TextView(activity).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    setTextColor(color(R.color.ds_text_secondary))
    typeface = typeMedium()
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
  }
  val statusChip = LinearLayout(activity).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    background = DsUi.roundRect(color(R.color.ds_chip), dim(R.dimen.ds_radius_pill))
    setPadding(dp(10f), dp(6f), dp(12f), dp(6f))
  }
  statusChip.addView(statusHalo)
  statusChip.addView(
    phaseChip,
    LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { marginStart = dp(8f) },
  )

  val topBar = LinearLayout(activity).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(0, dpix(R.dimen.ds_space_16), 0, dp(4f))
  }
  topBar.addView(brandMark)
  topBar.addView(
    nameStack,
    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
      marginStart = dp(10f)
    },
  )
  topBar.addView(
    statusChip,
    LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ),
  )
  content.addView(
    topBar,
    LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ),
  )

  // ══ ② 仪表台（#448 新组件）═══════════════════════════════════════════════
  // 旧形态：白卡里一个 30dp 空心环 + 23sp 标题——状态在整页里只有一行字的分量。
  // 新形态：168dp 环形仪表当视觉主体，环心就是相位短词，弧随相位变（忙碌自转 / 故障红满 /
  // 就绪绿满），外围刻度提供读数感但不承载编造的数据（产品口径：不画百分比）。
  val dial = GuideStatusDial(activity)
  val dialWrap = FrameLayout(activity).apply {
    addView(
      dial,
      FrameLayout.LayoutParams(dp(168f), dp(168f), Gravity.CENTER),
    )
  }
  content.addView(
    dialWrap,
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(184f)).apply {
      topMargin = dpix(R.dimen.ds_space_8)
    },
  )

  // ══ ③ 状态文字区（相位标题 / 副文案 / 进度 / 崩溃横幅）═════════════════════
  val engineStatus = TextView(activity).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
    setTextColor(color(R.color.ds_text_primary))
    typeface = typeMedium()
    letterSpacing = 0f
    setLineSpacing(0f, 1.2f)
    gravity = Gravity.CENTER_HORIZONTAL
  }
  // 渲染层用 `(engineStatus.parent as View).visibility` 做显隐 → 必须有一个专属包装层，
  // 否则会把仪表台一起藏掉。
  val engineStatusWrap = LinearLayout(activity).apply {
    orientation = LinearLayout.VERTICAL
    addView(
      engineStatus,
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )
  }
  content.addView(
    engineStatusWrap,
    LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ),
  )

  val statusHint = TextView(activity).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    setTextColor(color(R.color.ds_text_secondary))
    gravity = Gravity.CENTER_HORIZONTAL
    setLineSpacing(0f, 1.4f)
    visibility = View.GONE
  }
  content.addView(
    statusHint,
    LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dpix(R.dimen.ds_space_8) },
  )

  // 进度：**细线**（4dp）+ 恒不定档。旧版是整宽粗条——不知道总量时它仍长得像在报比例；
  // 细线只是呼吸，不宣称进度。
  val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
    visibility = View.GONE
    max = 100
    progressDrawable = DsUi.progressLayer(
      color(R.color.ds_progress_track),
      color(R.color.ds_accent),
      dim(R.dimen.ds_radius_pill),
    )
    indeterminateTintList = android.content.res.ColorStateList.valueOf(color(R.color.ds_accent))
  }
  content.addView(
    progressBar,
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4f)).apply {
      topMargin = dpix(R.dimen.ds_space_16)
    },
  )

  val progressText = TextView(activity).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    setTextColor(color(R.color.ds_text_tertiary))
    gravity = Gravity.CENTER_HORIZONTAL
    setPadding(0, dpix(R.dimen.ds_space_8), 0, 0)
    visibility = View.GONE
  }
  content.addView(
    progressText,
    LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ),
  )

  val crashBanner = TextView(activity).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    setTextColor(color(R.color.ds_danger))
    typeface = typeMedium()
    maxLines = 3
    ellipsize = TextUtils.TruncateAt.END
    visibility = View.GONE
    background = DsUi.roundRect(color(R.color.ds_danger_soft), dim(R.dimen.ds_radius_sm))
    setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
  }
  content.addView(
    crashBanner,
    LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dpix(R.dimen.ds_space_16) },
  )

  // ══ ④ 相位步骤条（#448 新组件）═══════════════════════════════════════════
  // 「卡在哪一段」是失败现场的第一个问题，旧版答不出（只有一句标题）。
  val stepper = GuideStepper(activity)
  content.addView(
    stepper,
    LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dpix(R.dimen.ds_space_20) },
  )

  // ══ ⑤ 诊断区：控制台窗（#448 新组件）+ 分享入口 + chips ════════════════════
  val logDot = View(activity).apply {
    background = DsUi.oval(color(R.color.ds_text_tertiary))
  }
  val copyLog = TextView(activity).apply {
    text = activity.getString(R.string.ds_copy_log)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    setTextColor(color(R.color.ds_text_primary))
    typeface = typeMedium()
    background = DsUi.roundRect(color(R.color.ds_bg), dim(R.dimen.ds_radius_pill))
    setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
    isClickable = true
    isFocusable = true
    minHeight = dp(40f)
    minWidth = dp(48f)
    gravity = Gravity.CENTER
    setOnClickListener { callbacks.onCopyLog() }
    DsUi.bindPressScale(this, 0.96f)
  }
  val logSummary = TextView(activity).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    maxLines = 8
    ellipsize = TextUtils.TruncateAt.END
  }
  val consolePlate = GuideConsolePlate(activity)
  consolePlate.buildHeader(activity.getString(R.string.ds_log_title), logDot, copyLog)
  consolePlate.attachBody(logSummary)
  val logSection: View = consolePlate
  logSection.visibility = View.GONE

  // 「分享诊断包」**不挂在控制台窗里**：窗的可见性由「日志有没有尾部」决定，
  // 而「引擎起不来、日志根本没生成」恰恰是最需要它的现场——挂进去等于把入口藏掉。
  val shareLog = TextView(activity).apply {
    text = activity.getString(R.string.ds_share_log)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    setTextColor(color(R.color.ds_accent))
    typeface = typeMedium()
    setPadding(0, dp(6f), 0, dp(6f))
    isClickable = true
    isFocusable = true
    minHeight = dp(48f)
    setOnClickListener { callbacks.onShareLog() }
    DsUi.bindPressScale(this, 0.97f)
    visibility = View.GONE
  }

  val runtimeChip = chipView(activity, typeMedium())
  val storageChip = chipView(activity, typeMedium())
  val chipRow = LinearLayout(activity).apply {
    orientation = LinearLayout.HORIZONTAL
    visibility = View.GONE
  }
  chipRow.addView(
    runtimeChip,
    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
  )
  chipRow.addView(
    storageChip,
    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
      marginStart = dpix(R.dimen.ds_space_8)
    },
  )

  val diagSection = LinearLayout(activity).apply {
    orientation = LinearLayout.VERTICAL
  }
  diagSection.addView(
    logSection,
    LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dpix(R.dimen.ds_space_20) },
  )
  diagSection.addView(
    shareLog,
    LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dpix(R.dimen.ds_space_4) },
  )
  diagSection.addView(
    chipRow,
    LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dpix(R.dimen.ds_space_16) },
  )
  content.addView(
    diagSection,
    LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ),
  )

  // ══ ⑥ 页脚：说明 + 版本戳 ════════════════════════════════════════════════
  val explanation = TextView(activity).apply {
    text = activity.getString(R.string.ds_brand_explain)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    setTextColor(color(R.color.ds_text_tertiary))
    gravity = Gravity.CENTER_HORIZONTAL
    setLineSpacing(0f, 1.3f)
  }
  content.addView(
    explanation,
    LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dpix(R.dimen.ds_space_24) },
  )

  // 版本戳：界面唯一「装的是哪一包」的自证手段（版本号被锁死，只能看戳）。
  // 单行 + 省略号 + 限宽必须保留；限宽要放得下完整戳（34% 会吃掉时间位 → 自证失效）。
  val versionLabel = TextView(activity).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
    setTextColor(color(R.color.ds_text_tertiary))
    typeface = Typeface.MONOSPACE
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
    maxWidth = (res.displayMetrics.widthPixels * 0.62f).toInt()
    gravity = Gravity.CENTER_HORIZONTAL
  }
  content.addView(
    versionLabel,
    LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dpix(R.dimen.ds_space_8) },
  )

  // ══ ⑦ 滚动视口 + 管理宿主 ════════════════════════════════════════════════
  val scroll = ScrollView(activity).apply {
    isFillViewport = true
    overScrollMode = View.OVER_SCROLL_NEVER
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    addView(
      content,
      FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER_HORIZONTAL,
      ),
    )
  }
  val managementHost = FrameLayout(activity).apply { visibility = View.GONE }
  // 一个稳定视口承担剩余高度：切换期间底部命令条绝不因内容显隐而位移。
  val viewport = FrameLayout(activity).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    addView(scroll, FrameLayout.LayoutParams(-1, -1))
    addView(managementHost, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
  }
  root.addView(viewport)

  // ══ ⑧ 底部命令条（sticky）═════════════════════════════════════════════════
  fun makePrimary(): Button = Button(activity).apply {
    text = activity.getString(R.string.ds_start_engine)
    isAllCaps = false
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    setTextColor(color(R.color.ds_text_on_accent))
    typeface = typeMedium()
    stateListAnimator = null
    background = DsUi.ripple(
      DsUi.roundRect(color(R.color.ds_accent), dim(R.dimen.ds_radius_pill)),
      color(R.color.ds_accent_pressed),
    )
    layoutParams = LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, dpix(R.dimen.ds_btn_height),
    )
    DsUi.bindPressScale(this)
    setOnClickListener { callbacks.onStartEngine() }
  }

  fun makeSecondary(label: String, onClick: () -> Unit): Button = Button(activity).apply {
    text = label
    isAllCaps = false
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    setTextColor(color(R.color.ds_text_primary))
    typeface = typeMedium()
    stateListAnimator = null
    background = DsUi.ripple(
      DsUi.roundRect(
        color(R.color.ds_surface), dim(R.dimen.ds_radius_pill),
        color(R.color.ds_hairline), hairline,
      ),
      color(R.color.ds_accent_soft),
    )
    DsUi.bindPressScale(this, 0.97f)
    setOnClickListener { onClick() }
  }

  fun makeTextLink(label: String, onClick: () -> Unit): Button = Button(activity).apply {
    text = label
    isAllCaps = false
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    setTextColor(color(R.color.ds_accent))
    typeface = typeMedium()
    stateListAnimator = null
    background = DsUi.ripple(
      DsUi.roundRect(android.graphics.Color.TRANSPARENT, dim(R.dimen.ds_radius_pill)),
      color(R.color.ds_accent_soft),
    )
    minHeight = dp(48f)
    gravity = Gravity.CENTER
    DsUi.bindPressScale(this, 0.97f)
    setOnClickListener { onClick() }
  }

  val primaryButton = makePrimary()
  val consoleButton = makeTextLink(activity.getString(R.string.ds_open_console), callbacks.onOpenConsole)
  val repairButton = makeSecondary(activity.getString(R.string.ds_repair_assistant), callbacks.onRepairAssistant)

  val repairRow = LinearLayout(activity).apply {
    orientation = LinearLayout.HORIZONTAL
    visibility = View.GONE
    layoutParams = LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, dpix(R.dimen.ds_btn_secondary_height),
    ).apply { topMargin = dpix(R.dimen.ds_space_8) }
  }
  repairRow.addView(
    repairButton,
    LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
    ),
  )

  val consoleRow = LinearLayout(activity).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.END
    layoutParams = LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dpix(R.dimen.ds_space_4) }
  }
  consoleRow.addView(
    consoleButton,
    LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ),
  )

  val gear = android.widget.ImageButton(activity).apply {
    setImageDrawable(android.graphics.drawable.RotateDrawable().apply {
      drawable = activity.getDrawable(R.drawable.ic_management_gear)
      fromDegrees = 0f
      toDegrees = 240f
      pivotX = 0.5f
      pivotY = 0.5f
      isPivotXRelative = true
      isPivotYRelative = true
    })
    imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.ds_text_primary))
    background = DsUi.ripple(
      DsUi.roundRect(color(R.color.ds_surface), dim(R.dimen.ds_radius_pill)),
      color(R.color.ds_accent_soft),
    )
    stateListAnimator = null
    setOnClickListener { callbacks.onManagement() }
    DsUi.bindPressScale(this, 0.94f)
    contentDescription = activity.getString(R.string.ds_management_cd)
  }

  val primaryRow = LinearLayout(activity).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
  }
  primaryRow.addView(
    primaryButton,
    LinearLayout.LayoutParams(0, dpix(R.dimen.ds_btn_height), 1f),
  )
  primaryRow.addView(
    gear,
    LinearLayout.LayoutParams(dpix(R.dimen.ds_btn_height), dpix(R.dimen.ds_btn_height)).apply {
      marginStart = dpix(R.dimen.ds_space_8)
    },
  )

  val actionBlock = LinearLayout(activity).apply {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(color(R.color.ds_bg))
    val g = dpix(R.dimen.ds_guide_gutter)
    setPadding(g, dpix(R.dimen.ds_space_8), g, dpix(R.dimen.ds_space_16))
    addView(consoleRow)
    addView(repairRow)
    addView(primaryRow)
  }
  root.addView(actionBlock)

  // ══ ⑨ 大屏钳制（ds_guide_max_width 单一来源）═══════════════════════════════
  val maxContentW = dpix(R.dimen.ds_guide_max_width)
  val gutter = dpix(R.dimen.ds_guide_gutter)
  val clamp = { v: View ->
    val avail = root.width - gutter * 2
    if (avail > 0) {
      val want = if (avail > maxContentW) maxContentW else ViewGroup.LayoutParams.MATCH_PARENT
      val lp = v.layoutParams
      if (lp != null && lp.width != want) {
        lp.width = want
        v.layoutParams = lp
      }
    }
  }
  root.gravity = Gravity.CENTER_HORIZONTAL
  root.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
    if (r - l != or - ol) {
      clamp(actionBlock)
      clamp(content)
      clamp(managementHost)
    }
  }
  root.post {
    clamp(actionBlock)
    clamp(content)
    clamp(managementHost)
  }

  return GuideChrome(
    root = root,
    brandBlock = topBar,
    cardBlock = dialWrap,
    diagBlock = diagSection,
    actionBlock = actionBlock,
    engineStatus = engineStatus,
    statusHint = statusHint,
    statusDot = statusDot,
    statusHalo = statusHalo,
    phaseChip = phaseChip,
    dial = dial,
    stepper = stepper,
    crashBanner = crashBanner,
    progressBar = progressBar,
    progressText = progressText,
    logSummary = logSummary,
    logSection = logSection,
    logDot = logDot,
    copyLog = copyLog,
    shareLog = shareLog,
    primaryButton = primaryButton,
    consoleButton = consoleButton,
    repairButton = repairButton,
    runtimeChip = runtimeChip,
    storageChip = storageChip,
    versionLabel = versionLabel,
    homeContent = scroll,
    managementHost = managementHost,
    gear = gear,
  )
}

private fun chipView(activity: ComponentActivity, type: android.graphics.Typeface): TextView {
  val res = activity.resources
  return TextView(activity).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    setTextColor(activity.getColor(R.color.ds_text_secondary))
    typeface = type
    gravity = Gravity.CENTER
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
    background = DsUi.roundRect(
      activity.getColor(R.color.ds_chip),
      res.getDimension(R.dimen.ds_radius_pill),
    )
    val padH = (10 * res.displayMetrics.density).toInt()
    val padV = (7 * res.displayMetrics.density).toInt()
    setPadding(padH, padV, padH, padV)
  }
}

/**
 * 状态光晕环（#446）：**空心**圆环——只描边不填充，卡面白/暖黑底透得出来，
 * 环心 dot 的脉冲因此是「环里的灯塔」，而不是「实心球上的高光」。
 *
 * 为什么不用填充圆：填充圆会把 card 的纸面糊成一块色斑，而状态色（陶土/危险）
 * 已经由 dot 表达；环只承担「这里有东西在动」的容器感。
 */
private fun ovalRing(ink: Int, strokePx: Int): android.graphics.drawable.GradientDrawable =
  android.graphics.drawable.GradientDrawable().apply {
    shape = android.graphics.drawable.GradientDrawable.OVAL
    setStroke(strokePx, ink)
  }

/**
 * 状态光晕环按相位重绘（#446）：忙碌=陶土、故障=危险、常态=暖线。
 *
 * 与渲染层给 dot 换色（`DsUi.oval(dotColor)`）是**同一判据、同一时机**——环与点
 * 永远同色系，不会出现「环还是灰的、点已经红了」的半换状态。常态（Idle/Info）
 * 状态行整体隐藏，这条分支主要服务相位切换的中间态。
 */
internal fun styleStatusHalo(
  halo: View,
  res: android.content.res.Resources,
  busy: Boolean,
  failed: Boolean,
) {
  val stroke = (1.5f * res.displayMetrics.density).toInt().coerceAtLeast(1)
  val ink = when {
    failed -> res.getColor(R.color.ds_danger)
    busy -> res.getColor(R.color.ds_accent)
    else -> res.getColor(R.color.ds_hairline)
  }
  halo.background = ovalRing(ink, stroke)
}


/**
 * 状态 chip 的观感（S1-7）：**可动作**与**纯事实**必须在视觉上分得开。
 *
 * 缺陷现场：runtimeChip（纯事实，不可点）与 storageChip（可点）外观**逐像素相同**，
 * 用户没有任何线索知道哪个能点——只能靠试。修法不是给可点的那枚加个装饰，而是让
 * 「能点」这件事本身有形态：强调色文字 + 同色描边 + 按压反馈；不可点的一枚保持中性灰无描边。
 */
internal fun styleStorageChip(
  activity: ComponentActivity,
  chip: TextView,
  actionable: Boolean,
  danger: Boolean = false,
) {
  val res = activity.resources
  val pill = res.getDimension(R.dimen.ds_radius_pill)
  val hairline = res.displayMetrics.density.toInt().coerceAtLeast(1)
  // 破坏/故障态（写入失败）保留红调：它虽然也可点，但语义是「出事了」，不是「去做吧」。
  val ink = activity.getColor(if (danger) R.color.ds_danger else R.color.ds_accent)
  if (actionable) {
    chip.setTextColor(ink)
    chip.background = DsUi.roundRect(activity.getColor(R.color.ds_chip), pill, ink, hairline)
    DsUi.bindPressScale(chip, 0.97f)
  } else {
    chip.setTextColor(activity.getColor(R.color.ds_text_secondary))
    chip.background = DsUi.roundRect(activity.getColor(R.color.ds_chip), pill)
  }
  chip.isClickable = actionable
  chip.isFocusable = actionable
}
