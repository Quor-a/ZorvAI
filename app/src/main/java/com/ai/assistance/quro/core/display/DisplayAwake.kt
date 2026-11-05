package com.ai.assistance.quro.core.display

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 让后台虚拟屏一直「醒着」（移植自 Mika 的 `DisplayAwake`，语义保持一致）。
 *
 * ## 为什么非要有它
 *
 * 我们建屏时带了 `OWN_DISPLAY_GROUP`（Android 13 起的可信屏），这块屏因此**自成一个显示组**，
 * 有自己的息屏计时。而 AI 注入的输入**不算用户操作**——放着不管，到点这组屏就睡了。
 *
 * 睡着的表现极具欺骗性，看起来像三个不同的 bug：
 *  - 画面不再合成 → `ImageReader` 不再来帧 → 截图永远是同一张（AI 以为界面没变）；
 *  - 点按注入到睡着的屏上 → 系统直接丢弃（AI 以为点错了，反复重试）；
 *  - 启动 App 找不到活动显示组 → 启动变慢甚至静默失败。
 *
 * 所以屏在的时候，每 [PERIOD_MS] 替它报一次用户活动（远小于系统最短息屏时间）。
 * 屏放掉就停；系统不支持或没有权限时**立刻收手**，不刷日志、不空转。
 *
 * ## 为什么只在能报的时候报
 *
 * 报到「默认显示组」等于让**用户自己的主屏**也一直不灭——那比屏睡着更糟。
 * 因此 [poke] 的实现方（shell 侧）自己做了三重判断：只认自建的屏、只在 13+ 带了自己组时、
 * 只在系统真有按屏重载时。这里拿到 false 就永久放弃。
 */
internal class DisplayAwake(
    private val scope: CoroutineScope,
    /** 替这块屏报一次用户活动；报不了返回 false。 */
    private val poke: (Int) -> Boolean,
) {
    private var job: Job? = null

    /** 系统这条路走不通（没权限 / 没有按屏接口），别再试。 */
    @Volatile
    private var unavailable = false

    /** 已经在替哪块屏保活；同一块屏重复调不会叠加定时器。 */
    @Volatile
    private var watching: Int = -1

    fun start(displayId: Int) {
        if (unavailable) return
        if (displayId < 0) return
        if (watching == displayId && job?.isActive == true) return
        stop()
        watching = displayId
        job = scope.launch {
            while (isActive) {
                if (!runCatching { poke(displayId) }.getOrDefault(false)) {
                    unavailable = true
                    Log.i(TAG, "该环境不支持按屏报用户活动，停止保活（屏照常用，可能自己息屏）")
                    return@launch
                }
                delay(PERIOD_MS)
            }
        }
        Log.d(TAG, "开始保活 displayId=$displayId（每 ${PERIOD_MS}ms 报一次用户活动）")
    }

    fun stop() {
        job?.cancel()
        job = null
        watching = -1
    }

    /** 此刻是否真的在替某块屏保活（管理页/诊断用）。 */
    val isRunning: Boolean get() = job?.isActive == true

    companion object {
        private const val TAG = "DisplayAwake"

        /**
         * 4 秒一次。系统最短的息屏时间是 15s（`SCREEN_OFF_TIMEOUT` 下限），
         * 留足三倍余量，同时不至于让 shell 进程每帧都被唤醒。
         */
        const val PERIOD_MS = 4_000L
    }
}
