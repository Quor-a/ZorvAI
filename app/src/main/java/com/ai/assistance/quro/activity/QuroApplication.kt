package com.ai.assistance.quro.activity

import android.app.Application
import android.content.Context
import com.ai.assistance.quro.core.QuroCrashLogger
import com.ai.assistance.quro.core.mcp.QuroLocalMcpManager
import com.ai.assistance.quro.core.tools.QuroImportedToolRegistry
import com.ai.assistance.quro.core.tools.QuroScheduledTaskScheduler
import com.ai.assistance.quro.core.bot.QuroBotManager
import com.ai.assistance.quro.ui.QuroPersonaViewModel

/**
 * 应用入口（原创）。
 *
 * 注意：
 * 1. 崩溃收集器在 attachBaseContext 安装——此阶段早于所有 ContentProvider.onCreate，
 *    可捕获启动期最早发生的崩溃，便于无 adb 取回日志。
 * 2. 架构（v116 起最终态）：AI 默认即可见并自行选择调用 L1 无障碍 / L2 Shizuku /
 *    L3 设备管理员 / L4 ROOT / L5 应用内 Linux(proot) 全部工具，运行时由系统权限授予把关。
 */
class QuroApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        QuroCrashLogger.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        // 载入持久化的「导入工具」（AI 自写 / 用户粘贴 JSON 导入），使其在所有会话默认可用
        QuroImportedToolRegistry.load(applicationContext)
        // 自动拉起 AI 部署的本地 MCP 服务器，使其随应用启动即恢复可用（界面自动拉取注册）
        QuroLocalMcpManager.startAll(applicationContext)
        // 恢复所有定时任务调度（开机/重启后自动重新排程）
        QuroScheduledTaskScheduler.ensureChannel(applicationContext)
        QuroScheduledTaskScheduler.scheduleAll(applicationContext)
        // 机器人框架（C2）：注册默认适配器并在「已启用且已配置」的平台启动（本地测试默认启用）
        QuroBotManager.instance(applicationContext).startEnabled(applicationContext)
        // 心跳孵化：偏好就绪后启动全局后台循环（AtomicBoolean 守卫避免重复启动；默认开启）
        QuroPersonaViewModel.initHeartbeat(applicationContext)
        if (QuroPersonaViewModel.heartbeatEnabled.value) {
            QuroPersonaViewModel.startHeartbeat()
        }
    }
}
