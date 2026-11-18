package com.ai.assistance.quro.core.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 各目标包进程的功能域分发。
 *
 * 一个包一个功能域，域内 Hook 各自记账、各自降级；某个 ROM 上找不到目标
 * 不会波及其他能力，也不会把宿主进程带崩：
 *  · SystemUI        → 手势条长按一圈即搜 + 助手 Intent 观测
 *  · Google App      → 语音浮窗补偿 + 机型/地区资格补齐 + 助手 Intent 观测
 *  · 小布 / 超级小爱 → 助手接管（走完整 Agent 运行时）
 *  · ColorOS 识屏 / 小布记忆 → 一圈即搜上下文
 *  · 小米桌面        → 桌面手势一圈即搜
 */
internal object QuroPackageHooks {

    fun install(module: XposedModule, param: PackageReadyParam, processName: String) {
        val pkg = param.packageName
        val report = runCatching {
            QuroPackageFeatureHooks.install(module, param, processName)
        }.getOrElse { e ->
            if (e is Exception) {
                runCatching {
                    module.log(
                        Log.ERROR,
                        QuroModuleConfig.LOG_TAG,
                        "[$pkg] 功能域安装失败：${e.javaClass.simpleName}",
                        e,
                    )
                }
                return
            }
            throw e
        }
        // 明细由 QuroHookRegistrar 逐条记录；这里只补一行「本进程当前开关快照」，
        // 便于对比「Hook 装上了但开关没开」这种最常见的问题。
        val switches = QuroModuleConfig.MAIN_SWITCHES.joinToString { def ->
            "${def.key}=${QuroModulePrefs.isEnabled(def.key)}"
        }
        runCatching {
            module.log(
                Log.INFO,
                QuroModuleConfig.LOG_TAG,
                "[$pkg] 功能域 ${report.size} 项；开关 $switches",
            )
        }
        // 把本进程的安装账本写到应用能读到的地方（厂商进程可能没有写权限，写不进就算了）。
        runCatching {
            val ctx = QuroHookEnvironment.currentApplication()
                ?: QuroHookEnvironment.systemContext(null)
            QuroHookReport.publish(ctx, pkg, report)
        }
    }
}
