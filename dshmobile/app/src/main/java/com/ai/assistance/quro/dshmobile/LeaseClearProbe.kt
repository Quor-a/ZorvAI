package com.ai.assistance.quro.dshmobile

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * 「残留租约能不能清」这一罕见决策的**审计留痕**（2026-10-02 复审要求）。
 *
 * 为什么单独留痕：这条判据曾因设备侧 API 不可信而误判（本机服务端 v13.6：`checkSelfPermission()`
 * 恒 denied、`getUid()` 撤权后仍返回 0），出问题时必须能事后复核**它当时看到了什么信号**。
 *
 * 纪律：①只在**存在残留租约**时写（常路零开销）；②有界——超过 [MAX_BYTES] 先清空再写；
 * ③任何异常都吞掉：诊断面绝不反过来影响启动。
 */
internal object LeaseClearProbe {
  private const val FILE_NAME = "lease-clear-probe.log"
  private const val MAX_BYTES = 8L * 1024L

  /**
   * @param context 应用上下文（写 filesDir）
   * @param probe 判定结果与原始信号明细（含 `bind=` 取值，让「压根没尝试绑定」一眼可见）
   * @param decisive 是否来自决定性探测（`probeRootChannel`）；false = 用户强制清除等
   * @param forced 是否用户显式确认的强制清除
   * @param dispatched 租约是否带「已派发特权工作」痕迹（自动清算的否决项）
   * @param allowed 自动清算准入判定结果（四条件是否同时满足）
   * @param leaseUnknown 租约自身的结算/持久化状态是否不可信（自动清算的第二个否决项）
   */
  fun record(
    context: Context,
    probe: ShizukuTransport.RootChannelProbe,
    decisive: Boolean,
    forced: Boolean = false,
    dispatched: Boolean = false,
    allowed: Boolean = false,
    leaseUnknown: Boolean = false,
  ) {
    runCatching {
      val file = File(context.filesDir, FILE_NAME)
      if (file.length() > MAX_BYTES) file.delete()
      val line = JSONObject()
        .put("t", System.currentTimeMillis())
        .put("state", probe.state.name)
        .put("serverUid", probe.serverUid)
        .put("decisive", decisive)
        .put("forced", forced)
        .put("dispatched", dispatched)
        .put("leaseUnknown", leaseUnknown)
        .put("allowed", allowed)
        .put("detail", probe.detail)
        .toString()
      file.appendText(line + "\n")
    }
  }
}
