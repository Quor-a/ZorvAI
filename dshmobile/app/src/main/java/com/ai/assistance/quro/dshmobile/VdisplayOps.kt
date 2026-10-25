package com.ai.assistance.quro.dshmobile

import android.content.Context
import org.json.JSONObject

/**
 * vd* op 的统一分发：虚拟屏是 Shizuku 特权面，**不依赖无障碍服务**。
 *
 * 两处调用方：
 * - `DeviceControlService.handle`：六面登记链按行首引号解析分支名，分支必须留在 handle 里，
 *   实现委托到本对象（scripts/check-control-ops.mjs 的 A 项）；
 * - `ControlCarrier`：无障碍关闭时的承载者（队列由前台引擎服务持有）。
 */
internal object VdisplayOps {
  fun handle(context: Context, op: String, args: JSONObject): JSONObject {
    // 空闲回收（0.14.0 用户要求：对话数分钟不运行且虚拟屏无操作则 kill 掉，否则一直占资源）。
    //
    // 入口处顺手回收一次：成本只是「比较时间戳」，但保证只要还有 vd 活动，空闲屏就会被及时
    // 释放。真正的兜底在 MainActivity 的周期任务（面板关掉、AI 不再调用时只有那条路走得到）。
    VdisplayController.reclaimIdle(context.applicationContext)
    return when (op) {
      "vdCreate" -> VdisplayController.create(context, args)
      "vdDestroy" -> VdisplayController.destroy(context, args)
      "vdLaunch" -> VdisplayController.launchSettingsProbe(context, args)
      "vdMoveTask" -> unsupported(op)
      "vdInfo" -> VdisplayController.status(context)
      // 跨屏拉起（0.14.0 用户实报：android_app_launch 没有屏幕维度，虚拟屏里开不了应用）。
      // 虚拟屏输入（0.14.0 用户实报：virtual-only 下「请改用坐标」指向一条不存在的路）。
      "vdInput" -> VdisplayController.input(
        context,
        args.optString("verb", ""),
        args,
        args.optString("target", "").takeIf { it.isNotBlank() },
      )
      "vdLaunchApp" -> VdisplayController.launchApp(
        context,
        args.optString("pkg", ""),
        args.optString("target", "").takeIf { it.isNotBlank() },
      )
      else -> JSONObject()
        .put("__error", "未知虚拟屏操作 $op")
        .put("reason", "unknown-op")
        .put("op", op)
    }
  }

  /** 已登记但壳侧尚未实现：fail-closed 的结构化拒绝（键位与旧 handle 实现逐字一致）。 */
    private fun unsupported(op: String): JSONObject = JSONObject()
      .put("__error", "暂不支持：$op（壳侧已登记、实现未落地——fail-closed 拒绝）")
      .put("reason", "unsupported")
      .put("op", op)
}