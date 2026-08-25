package com.ai.assistance.quro.activity

import android.app.Application
import android.content.Context
import com.ai.assistance.quro.core.QuroCrashLogger
import com.ai.assistance.quro.core.mcp.QuroLocalMcpManager
import com.ai.assistance.quro.core.tools.QuroImportedToolRegistry
import com.ai.assistance.quro.core.tools.QuroScheduledTaskScheduler
import com.ai.assistance.quro.core.bot.QuroBotManager
import com.ai.assistance.quro.core.aidlaci.QuroAidlAciManager
import com.ai.assistance.quro.core.shizuku.QuroShizuku
import com.ai.assistance.quro.ui.QuroPersonaViewModel
import com.ai.assistance.quro.workflow.data.WorkflowRepository
import com.ai.assistance.quro.workflow.data.NotesRepository
import com.ai.assistance.quro.workflow.data.RunStore
import com.ai.assistance.quro.workflow.executor.WorkflowEngine
import com.ai.assistance.quro.workflow.trigger.TriggerEngine

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

    companion object {
        /**
         * 进程级 Application Context。
         *
         * 存在原因：本地推理链路（full 风味的 QuroLocalEngineNative / LocalModelSessionHolder）
         * 是被 QuroAssistant 反射实例化的无参对象，拿不到任何 Context，但 MNN 必须传入一个
         * **应用私有可写目录**做算子缓存（tmp_path）。没有它 MNN 会把缓存写向模型所在目录
         * （通常在 /storage/emulated/0/Download/... 不可写）→ 每次推理都重新编译算子 → 极慢。
         * attachBaseContext 阶段即赋值，早于任何 ContentProvider / Activity。
         */
        @Volatile
        @JvmStatic
        var appCtx: Context? = null
            private set
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        appCtx = base.applicationContext ?: base
        QuroCrashLogger.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        // L2 Shizuku：进程启动即注册 Binder 监听（官方推荐做法）。
        // 确保 Shizuku 在应用存活期间任意时刻连接/授权都能被即时探知，
        // 修复「Shizuku 已装/已授权但权限页一直检测为未连接」的时序盲区（#915）。
        try { QuroShizuku.ensureInit() } catch (_: Throwable) {}
        // 载入持久化的「导入工具」（AI 自写 / 用户粘贴 JSON 导入），使其在所有会话默认可用
        QuroImportedToolRegistry.load(applicationContext)
        // 自动拉起 AI 部署的本地 MCP 服务器，使其随应用启动即恢复可用（界面自动拉取注册）
        QuroLocalMcpManager.startAll(applicationContext)
        // 恢复所有定时任务调度（开机/重启后自动重新排程）
        QuroScheduledTaskScheduler.ensureChannel(applicationContext)
        QuroScheduledTaskScheduler.scheduleAll(applicationContext)
        // 机器人框架（C2）：注册默认适配器并在「已启用且已配置」的平台启动（本地测试默认启用）
        QuroBotManager.instance(applicationContext).startEnabled(applicationContext)
        // ACI（Agent Capability Interface）：让 Zorv AI 成为 ACI 控制方（AI 中枢），
        // 启动即发现并绑定设备上已安装的第三方 ACI App，使其能力可被 AI 调用。
        // 整体包在 try 中，避免 ACI 异常影响应用正常启动。
        try {
            QuroAidlAciManager.init(applicationContext)
            QuroAidlAciManager.getInstance().discover()
        } catch (e: Throwable) {
            android.util.Log.e("QuroApplication", "ACI 初始化失败（不影响主流程）", e)
        }
        // 心跳孵化：偏好就绪后启动全局后台循环（AtomicBoolean 守卫避免重复启动；默认开启）
        QuroPersonaViewModel.initHeartbeat(applicationContext)
        if (QuroPersonaViewModel.heartbeatEnabled.value) {
            QuroPersonaViewModel.startHeartbeat()
        }
        // 工作流引擎 + 触发器：初始化仓库/引擎，并武装所有「定时(time)」工作流，
        // 使其随应用启动自动排程（修复定时工作流永不触发）。整体包 try，避免影响主流程。
        try {
            WorkflowRepository.init(applicationContext)
            NotesRepository.init(applicationContext)
            RunStore.init(applicationContext)
            WorkflowEngine.init(applicationContext)
            TriggerEngine.init(applicationContext)
            TriggerEngine.armAll()
        } catch (e: Throwable) {
            android.util.Log.e("QuroApplication", "Workflow 初始化失败（不影响主流程）", e)
        }
    }
}
