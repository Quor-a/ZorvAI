package com.ai.assistance.quro.dshmobile

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * issue #258：无障碍 nx/ny 归一化的**基准尺寸**解析（单一真源）。
 *
 * ── 缺陷形态（用户实报，HUAWEI BTK-W00 2200x1440，窗口 bounds=(1438,106) 733x1389）──
 * 归一化 -> 像素的换算原本直接吃 `Resources.getDisplayMetrics()` 的等价物
 * （API 30+ 用 `WindowManager.currentWindowMetrics`）：Android 在**分屏 / 自由窗口 / 悬浮窗**
 * 形态下返回的是**当前窗口**尺寸而非整屏尺寸，于是 `nx=0.712` 算出 `x=0.712*733=522`，
 * 落在屏幕左侧背景应用上；而 DSH 自身浮窗在 `x∈[1438,2144]`，**整块窗口永远点不到**
 * （连它自己的最小化按钮都点不到）。全屏时窗口 = 屏幕，故该缺陷只在非全屏形态触发。
 *
 * ── 为什么把解析抽成这里 ────────────────────────────────────────────────────
 * 「基准是窗口还是整屏」这条判定必须在**唯一一处**实现：click / longClick / snapshot 的 screen
 * 字段 / scroll 兜底手势四条路径原先各自调 `screenSize()`，任何一条漏改就是「同一个 bug 换条路复发」。
 * 现在四者一律经 [resolve]，且判定逻辑是**纯函数**（输入 [Reading]，输出 [Basis]），
 * 可直接被 JVM 单测覆盖——反证用例见 `CoordBasisPolicyTest`。
 *
 * 优先级（高到低）：
 *  1. `WindowManager.getMaximumWindowMetrics().bounds`（API 30+）：**整屏最大窗口区**，
 *     多窗口下仍给出屏幕尺寸，这是本 issue 的主修；
 *  2. `Display.getRealMetrics()`：旧 API 与 API 30+ 取不到最大窗口时的整屏兜底
 *     （已废弃但 minSdk 26 必须留着——26-29 上它是唯一能拿到整屏尺寸的途径）；
 *  3. 当前窗口 bounds：**最后兜底**，并明确标记 [WIRE_WINDOW_CURRENT]——它正是缺陷基准，
 *     回显里必须能看出来，否则同类 bug 下次仍无法自证。
 *
 * 不做的事：不回退到 `Resources.getDisplayMetrics()`（那正是缺陷本身），也不猜 0 尺寸当作屏幕
 * （宁可留下窗口标记让 JS 层如实告警）。
 */
object CoordBasisPolicy {

  /** 基准 = 整屏最大窗口（API 30+ 的 maximumWindowMetrics）。 */
  const val WIRE_SCREEN_MAX = "screen-max-window"

  /** 基准 = Display.getRealMetrics（全 API 的整屏兜底）。 */
  const val WIRE_SCREEN_REAL = "screen-real-metrics"

  /**
   * 基准 = 当前窗口 bounds（缺陷基准）。
   *
   * 只在整屏尺寸两种途径都拿不到时出现；回显该值即等于「本机未能给出整屏尺寸，nx/ny 可能偏左」。
   */
  const val WIRE_WINDOW_CURRENT = "window-current"

  /**
   * 一次取数结果（**已按 API 可用性过滤**的原始素材）。
   *
   * 为什么取数也要可注入：判定是纯函数，取数是 Android API 调用——把两者分开才能在纯 JVM
   * 单测里构造「多窗口」读取值（窗口 733x1389 / 整屏 2200x1440）而不需要一个真实多窗口设备。
   *
   * @param sdkInt 设备 API 级别（判定 API 30 门槛）。
   * @param maxWindow 最大窗口(整屏)尺寸；取不到为 null。
   * @param realMetrics Display.getRealMetrics 尺寸；取不到为 null。
   * @param currentWindow 当前窗口尺寸；取不到为 null。
   */
  data class Reading(
    val sdkInt: Int,
    val maxWindow: Pair<Int, Int>?,
    val realMetrics: Pair<Int, Int>?,
    val currentWindow: Pair<Int, Int>?,
  )

  /**
   * 选定基准。
   *
   * @param reading 取数结果。
   * @return 基准（[Basis.wire] 表明来源；宽高均 >0 才可能来自整屏途径）。
   */
  fun resolve(reading: Reading): Basis {
    if (reading.sdkInt >= Build.VERSION_CODES.R) {
      positive(reading.maxWindow)?.let { return Basis(WIRE_SCREEN_MAX, it.first, it.second) }
    }
    positive(reading.realMetrics)?.let { return Basis(WIRE_SCREEN_REAL, it.first, it.second) }
    positive(reading.currentWindow)?.let { return Basis(WIRE_WINDOW_CURRENT, it.first, it.second) }
    // 全部取不到：保持窗口标记（fail-closed：不回显成一个并不存在的「整屏」基准）。
    return Basis(WIRE_WINDOW_CURRENT, 0, 0)
  }

  private fun positive(size: Pair<Int, Int>?): Pair<Int, Int>? =
    if (size != null && size.first > 0 && size.second > 0) size else null

  /**
   * 从系统服务取数（真实设备路径）。
   *
   * `maximumWindowMetrics` 是 API 30+（R）新增：低于 R 时**不得调用**——minSdk 26 的兼容分支
   * 就在这里（26-29 走 `Display.getRealMetrics()`，它给出整屏尺寸）。
   */
  fun fromContext(context: Context): Reading {
    val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val sdk = Build.VERSION.SDK_INT
    val maxWindow = if (sdk >= Build.VERSION_CODES.R) {
      try {
        val b = wm.maximumWindowMetrics.bounds
        b.width() to b.height()
      } catch (_: Throwable) {
        null
      }
    } else {
      null
    }
    val realMetrics = try {
      val metrics = DisplayMetrics()
      @Suppress("DEPRECATION")
      wm.defaultDisplay.getRealMetrics(metrics)
      metrics.widthPixels to metrics.heightPixels
    } catch (_: Throwable) {
      null
    }
    val currentWindow = if (sdk >= Build.VERSION_CODES.R) {
      try {
        val b = wm.currentWindowMetrics.bounds
        b.width() to b.height()
      } catch (_: Throwable) {
        null
      }
    } else {
      null
    }
    return Reading(sdk, maxWindow, realMetrics, currentWindow)
  }

  /** 便捷入口：当前设备上的整屏基准（无障碍 click / longClick / snapshot / scroll 共用）。 */
  fun screenBasis(context: Context): Basis = resolve(fromContext(context))

  /**
   * 选定后的基准。
   *
   * @param wire 来源 wire 值（[WIRE_SCREEN_MAX] / [WIRE_SCREEN_REAL] / [WIRE_WINDOW_CURRENT]）。
   * @param width 归一化 X 的分母。
   * @param height 归一化 Y 的分母。
   */
  data class Basis(val wire: String, val width: Int, val height: Int) {
    /** true = 基准是整屏（本 issue 期望的形态）；false = 落到了缺陷基准「当前窗口」。 */
    val isScreenScope: Boolean get() = wire != WIRE_WINDOW_CURRENT
  }
}
