package com.ai.assistance.quro.dshmobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/**
 * 外部跳转的唯一出口（0.14.1 用户定例 2026-09-22：设置页「手机控制」的 Shizuku 引导）。
 *
 * **为什么是「key → URL」而不是「页面传 URL」**：页面内容按不可信处理（与 [PathOpen] 同口径）。
 * 若桥面直接接收 URL，任何能在页面里执行脚本的东西就获得了「让壳侧拉起任意 Intent」的能力——
 * 那比本需求（两个固定链接）大得多。故 URL 表是壳侧常量，页面只能点名。
 *
 * **两个链接共用一条通道**（用户定例）：下载页与视频教程的差别只在登记值，不需要两条桥方法；
 * 将来加链接只需在 [TARGETS] 登记一个 key，页面侧无需新桥方法。
 *
 * **失败一律结构化回报**（`{"ok":false,"reason":…}`，绝不静默）：未登记（unknown-key）/
 * 登记值非 https（insecure-url）/ 无可处理应用（no-handler）/ 拉起异常（异常类名）。
 * 静默失败是本轮 UI 审查里出现频次最高的缺陷形态，本通道不复制它。
 *
 * 反证方式：把 [classify] 的 https 判据去掉（或让未知 key 回落到任一登记值），
 * `app/src/test/java/com/ai/assistance/quro/dshmobile/ExternalLinksTest.kt` 的对应用例必红。
 */
object ExternalLinks {

  /** 下载 Shizuku：GitHub Releases 页（release 版 APK 在该页资产区）。 */
  const val SHIZUKU_DOWNLOAD = "shizuku-download"

  /** 视频教程：从安装到「无线调试启动」再到授权的完整流程。 */
  const val SHIZUKU_TUTORIAL = "shizuku-tutorial"

  /**
   * Shizuku 管理器包名（官方）。
   *
   * 与 [ShizukuTransport] 的在场判定同源：那张判据自 0.14.8 起**不再以本包名单独否决**
   * （见 [SHIZUKU_MANAGER_PACKAGES]），本常量只表达「官方管理器是谁」。单侧改动由
   * `ExternalLinksTest.installedProbeUsesTheSharedManagerTable` 的源码契约守着。
   */
  const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

  /**
   * Stellar 管理器包名（Shizuku 的深度定制分支，见 https://github.com/roro2239/Stellar）。
   *
   * 它经兼容层提供同一套 Shizuku API（pingBinder/getVersion/getUid/checkSelfPermission/
   * addUserService），只是**不安装官方包名的 APK**——把官方包名当成能力事实就会把它误判成
   * 「未检测到 Shizuku」。
   */
  const val STELLAR_PACKAGE = "roro.stellar.manager"

  /**
   * 已知的 Shizuku 兼容管理器包名（表内顺序即 [openShizukuManager] 的拉起顺序）。
   *
   * **本表只用于「把用户送到管理器界面」**，不是能力判据：能力判据是 Shizuku API binder 是否
   * 可达（[ShizukuTransport.installed]）。分支实现（Stellar 等）与官方共存时互不覆盖，
   * 逐个尝试拉起即可，不必也不应假定只有官方一种实现。
   */
  val SHIZUKU_MANAGER_PACKAGES: List<String> = listOf(SHIZUKU_PACKAGE, STELLAR_PACKAGE)

  /**
   * key → 登记 URL。**唯一**外链真源；只许 https（明文 http 会被 [classify] 拒收）。
   *
   * 教程链接按用户给定的原样保留（含分享来源查询串 `vd_source`，不做清洗）。
   */
  private val TARGETS: Map<String, String> = mapOf(
    SHIZUKU_DOWNLOAD to "https://github.com/RikkaApps/Shizuku/releases",
    SHIZUKU_TUTORIAL to
      "https://www.bilibili.com/video/BV1iFy7BpECf/?vd_source=f4d91092356f067f076eeacb3ff30380",
  )

  /** 已登记 key 一览（供门禁与测试枚举，避免「加了 key 忘了接线」这类漏项）。 */
  fun keys(): List<String> = TARGETS.keys.sorted()

  /**
   * 纯判据：key → 判据结果。
   * @param targets 覆盖表（默认 [TARGETS]；仅供测试注入非法登记值以验证拒收分支）。
   */
  internal fun classify(
    key: String,
    targets: Map<String, String> = TARGETS,
  ): ExternalLinkVerdict {
    val url = targets[key] ?: return ExternalLinkVerdict.UnknownKey
    if (!url.startsWith("https://")) return ExternalLinkVerdict.InsecureUrl
    return ExternalLinkVerdict.Openable(url)
  }

  /** 打开登记链接（交给系统浏览器或默认处理应用，不经过应用内 BrowserHost）。 */
  fun open(activity: Activity, key: String): String = when (val verdict = classify(key)) {
    ExternalLinkVerdict.UnknownKey -> answer(false, "unknown-key")
    ExternalLinkVerdict.InsecureUrl -> answer(false, "insecure-url")
    is ExternalLinkVerdict.Openable ->
      launch(activity, Intent(Intent.ACTION_VIEW, Uri.parse(verdict.url)))
  }

  /**
   * 拉起 Shizuku 管理器界面。
   *
   * 授权**只能**由用户在 Shizuku 内完成（被提权方不得自改授权，与 [ShizukuProbe] 同口径），
   * 所以这里只负责把用户送到那个界面，不做任何隐式安装/授权。
   */
  fun openShizukuManager(activity: Activity): String {
    // 逐个候选包尝试（官方 + 分支实现）：装哪个送哪个。全都不在场才如实报 not-installed
    // ——不假装能拉起一个不存在的界面。
    for (pkg in SHIZUKU_MANAGER_PACKAGES) {
      val intent = activity.packageManager.getLaunchIntentForPackage(pkg) ?: continue
      return launch(activity, intent)
    }
    return answer(false, "not-installed")
  }

  private fun launch(activity: Activity, intent: Intent): String {
    if (intent.resolveActivity(activity.packageManager) == null) return answer(false, "no-handler")
    return try {
      activity.startActivity(intent)
      answer(true, null)
    } catch (e: Exception) {
      answer(false, e.javaClass.simpleName)
    }
  }

  private fun answer(ok: Boolean, reason: String?): String {
    val out = JSONObject().put("ok", ok)
    if (reason != null) out.put("reason", reason)
    return out.toString()
  }
}

/** [ExternalLinks.classify] 的判据结果。纯数据，可脱离 Android 单测。 */
internal sealed class ExternalLinkVerdict {
  /** 已登记且为 https，可打开。 */
  data class Openable(val url: String) : ExternalLinkVerdict()

  /** 未登记的 key（含空串）。 */
  data object UnknownKey : ExternalLinkVerdict()

  /** 已登记但不是 https——登记值写错时的兜底，不允许把明文链接交给系统。 */
  data object InsecureUrl : ExternalLinkVerdict()
}
