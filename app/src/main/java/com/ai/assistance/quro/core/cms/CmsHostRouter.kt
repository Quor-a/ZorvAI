package com.ai.assistance.quro.core.cms

import android.content.Context
import com.ai.assistance.quro.core.linux.QuroLinuxEnv

/**
 * CmsHostRouter — 能力运行宿主路由（Capability-Oriented Architecture 的 Runtime Host 落地）。
 *
 * 一个 [QuroCmsCapability] 声明 [QuroCmsCapability.runOn]（可在哪些宿主运行：APP 前端 / TERMINAL 后端）。
 * 调用携带 [InvocationTarget]（auto/app/terminal），本路由按以下策略解析最终宿主：
 * 1) 显式 app/terminal 且能力支持 → 直接采用；
 * 2) auto 或显式宿主不被支持 → 在 runOn 候选内自动选：
 *    - 候选唯一 → 该宿主；
 *    - 候选双宿主(APP+TERMINAL) → 若 proot 就绪 且 未锁屏 且 (充电中 或 电量≥20%) → TERMINAL，否则 APP
 *      （即「后台长任务/有算力时迁移到后端，前台/省电时留在前端」的互为主从策略）。
 *
 * 解析失败（请求宿主能力不支持且无可行候选）→ 返回 [HostResolution.guidance] 引导文案，
 * 上层转成「⛔ 引导错误」，不静默失败。
 */
object CmsHostRouter {

    data class HostResolution(val host: RuntimeHost?, val guidance: String?)

    fun resolve(cap: QuroCmsCapability, requested: InvocationTarget, context: Context?): HostResolution {
        val candidates = cap.runOn
        // 1) 显式指定且能力支持
        when (requested) {
            InvocationTarget.APP ->
                if (candidates.contains(RuntimeHost.APP)) return HostResolution(RuntimeHost.APP, null)
            InvocationTarget.TERMINAL ->
                if (candidates.contains(RuntimeHost.TERMINAL)) return HostResolution(RuntimeHost.TERMINAL, null)
            InvocationTarget.AUTO -> Unit
        }
        // 2) auto 或不支持 → 自动选
        if (candidates.isEmpty()) {
            return HostResolution(null, "⛔ 能力 ${cap.id} 未声明任何可运行宿主(runOn)。")
        }
        if (candidates.size == 1) {
            return HostResolution(candidates.first(), null)
        }
        // 双宿主：按运行时上下文选（互为主从策略）；context 为空（JVM 单测）时按保守策略：不就绪/未锁/中性电量
        val terminalReady = if (context == null) false else runCatching { QuroLinuxEnv.probeLenient(context).available }.getOrDefault(false)
        val locked = context != null && isDeviceLocked(context)
        val (level, charging) = if (context == null) (50 to false) else batteryInfo(context)
        val preferTerminal = terminalReady && !locked && (charging || level >= 20)
        val want = if (preferTerminal) RuntimeHost.TERMINAL else RuntimeHost.APP
        return HostResolution(if (candidates.contains(want)) want else candidates.first(), null)
    }

    private fun isDeviceLocked(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        return km?.isDeviceLocked == true
    }

    private fun batteryInfo(context: Context): Pair<Int, Boolean> {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager ?: return 50 to false
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level to bm.isCharging
    }
}
