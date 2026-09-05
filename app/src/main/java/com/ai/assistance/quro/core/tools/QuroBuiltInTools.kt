package com.ai.assistance.quro.core.tools

import android.content.Context
import android.os.Build
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.knowledge.QuroRagKnowledgeTool
import com.ai.assistance.quro.core.cms.QuroCmsCallTool
import com.ai.assistance.quro.core.cms.QuroCmsDeployTool
import com.ai.assistance.quro.core.cms.QuroCmsListTool
import com.ai.assistance.quro.core.cms.QuroCmsLogsTool
import com.ai.assistance.quro.core.cms.QuroCmsResultTool
import com.ai.assistance.quro.core.cms.QuroCmsRunDagTool
import com.ai.assistance.quro.core.cms.QuroCmsStatusTool
import com.ai.assistance.quro.core.cms.QuroCmsEngineStatusTool
import com.ai.assistance.quro.core.cms.QuroCmsUndeployTool
import com.ai.assistance.quro.core.cms.QuroCmsToolboxTool
import com.ai.assistance.quro.core.cms.QuroPrivStatusTool
import com.ai.assistance.quro.core.tools.QuroDevEnvTool
import com.ai.assistance.quro.core.aidlaci.QuroAidlAciCallTool
import com.ai.assistance.quro.core.aidlaci.QuroAidlAciListTool
import com.ai.assistance.quro.core.aidlaci.QuroAciHttpServerTool
import com.ai.assistance.quro.core.mcp.McpAciListTool
import com.ai.assistance.quro.core.mcp.McpAciCallTool
import com.ai.assistance.quro.core.mcp.McpAciBridgeTool
import com.ai.assistance.quro.core.tools.ReadScreenTool
import com.ai.assistance.quro.core.tools.GetForegroundAppTool
import com.ai.assistance.quro.core.tools.GetScreenStateTool
import com.ai.assistance.quro.core.tools.TapScreenTool
import com.ai.assistance.quro.core.tools.LongPressScreenTool
import com.ai.assistance.quro.core.tools.SwipeScreenTool
import com.ai.assistance.quro.core.tools.InputTextTool
import com.ai.assistance.quro.core.tools.ScrollScreenTool
import com.ai.assistance.quro.core.tools.GlobalActionTool
import com.ai.assistance.quro.core.tools.ScreenshotTool
import com.ai.assistance.quro.core.tools.ScreenshotBase64Tool
import com.ai.assistance.quro.core.tools.VisualAnalysisTool
import com.ai.assistance.quro.core.tools.TakePhotoTool
import com.ai.assistance.quro.core.tools.TranslateTool
import com.ai.assistance.quro.core.tools.ScreenRecordTool
import com.ai.assistance.quro.core.tools.VolumeControlTool
import com.ai.assistance.quro.core.tools.BrightnessControlTool
import com.ai.assistance.quro.core.tools.WiFiControlTool
import com.ai.assistance.quro.core.tools.BluetoothControlTool
import com.ai.assistance.quro.core.tools.NotificationControlTool
import com.ai.assistance.quro.core.tools.AirplaneModeTool
import com.ai.assistance.quro.core.tools.ScreenRotationTool
import com.ai.assistance.quro.core.tools.OpenAppTool
import com.ai.assistance.quro.core.tools.ShizukuExecTool
import com.ai.assistance.quro.core.tools.ShizukuRootExecTool
import com.ai.assistance.quro.core.tools.FreezeAppTool
import com.ai.assistance.quro.core.tools.InstallAppTool
import com.ai.assistance.quro.core.tools.ShizukuStatusTool
import com.ai.assistance.quro.core.tools.LockScreenTool
import com.ai.assistance.quro.core.tools.DeviceAdminStatusTool
import com.ai.assistance.quro.core.tools.SetCameraDisabledTool
import com.ai.assistance.quro.core.tools.RootExecTool
import com.ai.assistance.quro.core.tools.RootStatusTool
import com.ai.assistance.quro.tools.VncTool
import com.ai.assistance.quro.tools.WorkbenchTool
import com.ai.assistance.quro.core.tools.LinuxRunTool
import com.ai.assistance.quro.core.tools.LinuxInstallTool
import com.ai.assistance.quro.core.tools.LinuxStartTool
import com.ai.assistance.quro.core.tools.LinuxStopTool
import com.ai.assistance.quro.core.tools.LinuxStatusTool
import com.ai.assistance.quro.core.tools.AiwpsCreateTool
import com.ai.assistance.quro.core.tools.AiwpsReadTool
import com.ai.assistance.quro.core.tools.AiwpsEditTool
import com.ai.assistance.quro.core.tools.QuroExperienceLogTool
import com.ai.assistance.quro.core.tools.QuroExperienceQueryTool
import com.ai.assistance.quro.core.tools.QuroExperienceCorrectTool
import com.ai.assistance.quro.core.tools.QuroExperienceVersionCheckTool
import com.ai.assistance.quro.core.tools.FluidCloudTool
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Quro 内置工具：演示工具调用能力。
 */

/** 返回当前时间。 */
class QuroClockTool : QuroTool {
    override val name = "get_current_time"
    override val description = "获取当前日期与时间，参数为空 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, arguments: String): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return fmt.format(Date())
    }
}

/** 返回设备基础信息。 */
class QuroDeviceInfoTool : QuroTool {
    override val name = "get_device_info"
    override val description = "获取设备型号与系统版本，参数为空 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, arguments: String): String =
        "型号=${Build.MODEL}, 品牌=${Build.BRAND}, Android=${Build.VERSION.RELEASE}, SDK=${Build.VERSION.SDK_INT}"
}

/** 四则运算计算器（安全递归下降求值，不支持函数/变量）。 */
class QuroCalculatorTool : QuroTool {
    override val name = "calculate"
    override val description = "计算一个算术表达式，支持 + - * / 和括号，参数为 {\"expr\":\"1+2*3\"}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{"expr":{"type":"string","description":"算术表达式"}},
        "required":["expr"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val expr = JSONObject(arguments).optString("expr", "").trim()
        if (expr.isEmpty()) return "缺少 expr 参数"
        return try {
            val v = QuroMath.eval(expr)
            if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        } catch (e: Exception) {
            "计算失败: ${e.message}"
        }
    }
}

/** 极简算术求值器。 */
object QuroMath {
    fun eval(src: String): Double {
        val s = src.replace(" ", "")
        if (s.isEmpty()) throw IllegalArgumentException("空表达式")
        val p = Parser(s)
        val v = p.parseAddSub()
        if (p.pos != s.length) throw IllegalArgumentException("非法字符: ${s[p.pos]}")
        return v
    }

    private class Parser(private val s: String) {
        var pos = 0
        private fun peek(): Char? = if (pos < s.length) s[pos] else null
        private fun eat(c: Char) {
            if (peek() == c) pos++ else throw IllegalArgumentException("期望 '$c'")
        }

        fun parseAddSub(): Double {
            var v = parseMulDiv()
            while (peek() == '+' || peek() == '-') {
                val op = s[pos++]
                val r = parseMulDiv()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        private fun parseMulDiv(): Double {
            var v = parsePrimary()
            while (peek() == '*' || peek() == '/') {
                val op = s[pos++]
                val r = parsePrimary()
                v = if (op == '*') v * r else v / r
            }
            return v
        }

        private fun parsePrimary(): Double {
            if (peek() == '(') {
                eat('(')
                val v = parseAddSub()
                eat(')')
                return v
            }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("期望数字")
            return s.substring(start, pos).toDouble()
        }
    }
}

/** 注册全部原创工具（最大集，100% 自研，引擎来自 vendored droid-mcp Apache-2.0）。 */
fun buildQuroRegistry(context: Context? = null): QuroToolRegistry {
    val r = QuroToolRegistry()
    // ══════════════ 工具分类架构（能力域 → 代表工具）══════════════
    //  · 基础/系统/设备   : clock, device_info, battery, wifi, sensors, clipboard, apps, notifications, bluetooth, flashlight
    //  · 通信/日历        : sms, contacts, calendar
    //  · 文件/工作区      : list/read/write/delete files, workbench, workspace_*, knowledge_*
    //  · 网络/Web         : http_request, open_web, ai_browser, web_crawler
    //  · 终端/沙箱/Linux  : quro_term, terminal_*, sandbox, linux_*, python_run
    //  · 特权/ADB/LSPosed : priv_exec, priv_status, adb_term, lsposed（高风险，自动降级通道）
    //  · 抓包             : packet_capture（proot 内 mitmdump，flow 写 /mnt/quro/mitm/）
    //  · 内置浏览器 AI 操控: browser_act（snapshot/click/fill/eval/wait/read，接管对话框内置 WebView）
    //  · 对话框文档/排版   : chat_doc（对话框内渲染 Markdown/HTML/代码）；文档附件支持对话框内联排版预览（mammoth/SheetJS/pdf.js）
    //  · ACI/跨应用       : aidl_aci_*, aci_http_server, mcp_aci_*, list/invoke_app_function
    //  · 无障碍控屏/L1     : read_screen, tap/longpress/swipe, ai_keyboard_*, screenshot*, visual_*
    //  · 系统控制/L2-L5    : shizuku_*, root_*, device_admin_*, 媒体/拍摄/录屏/音量/亮度…
    //  · 多媒/生成         : image_gen, video_gen, translate, image/audio/video_recognition
    //  · 文档生成          : aiwps_create/read/edit, enhanced_doc
    //  · 记忆/经验/技能    : memory_*, experience_*, skills, tool_discovery
    //  · 动态 UI(必备)      : quro-ui 原生组件 = ui_dsl_spec / ui_validate / 消息内 ```quro-ui 围栏（AI 主动默认输出）
    //  · UI/可视化         : ui_control(ui_*), visual_*, creative_studio, fluid_cloud
    //  · 化小窗(纯 UI)     : 对话框顶栏 + 浏览器工具栏「化小窗」按钮（可拖拽悬浮小窗，非工具）
    // ══════════════════════════════════════════════════════════════

    // 基础演示工具
    r.register(QuroClockTool())
    r.register(QuroDeviceInfoTool())
    r.register(QuroCalculatorTool())
    // 系统 / 设备
    r.register(GetBatteryTool())
    r.register(GetWifiTool())
    r.register(GetNetworkTool())
    r.register(GetSensorsTool())
    r.register(VibrateTool())
    r.register(GetClipboardTool())
    r.register(SetClipboardTool())
    r.register(ListAppsTool())
    r.register(LaunchAppTool())
    r.register(SearchAndLaunchAppTool())
    r.register(GetPackageNameTool())
    r.register(GetNotificationsTool())
    r.register(GetBluetoothTool())
    r.register(ToggleFlashlightTool())
    // 通信
    r.register(ReadSmsTool())
    r.register(SendSmsTool())
    r.register(ReadContactsTool())
    // 日历
    r.register(ReadCalendarTool())
    r.register(WriteCalendarTool())
    // 文件（应用专属目录，无权限）
    r.register(ListFilesTool())
    r.register(ReadTextFileTool())
    // 位置
    r.register(GetLocationTool())
    r.register(GeocodeTool())
    // 媒体
    r.register(ListMediaTool())
    // AI 发文件：把设备文件作为对话框附件（图片/视频/文档直接预览）
    r.register(AttachFileTool())
    // 闹钟（应用内真正会响的提醒）
    r.register(SetAlarmTool())
    r.register(CancelAlarmTool())
    r.register(ListAlarmsTool())
    // 定时任务/自动化提醒
    r.register(ScheduleTaskTool())
    r.register(ListScheduledTasksTool())
    r.register(DeleteScheduledTaskTool())
    // 网络 / Web
    r.register(HttpRequestTool())
    // 文件写改删
    r.register(WriteFileTool())
    r.register(DeleteFileTool())
    r.register(MakeDirectoryTool())
    r.register(MoveFileTool())
    r.register(CopyFileTool())
    r.register(FindFilesTool())
    r.register(FileInfoTool())
    // 工具箱：文件管理 / 浏览器 / IDE
    r.register(BrowseFilesTool())
    r.register(FileReadTool())
    r.register(OpenWebTool())
    r.register(RunCodeTool())
    // 后端工作区：多文件多语言项目
    r.register(WorkbenchTool())
    r.register(MiniAppStudioTool())    // 小程序工作台：AI 直接 CRUD/运行小程序工程（完整移植 MiniAppFramework）
    // TTS 朗读
    r.register(SpeakTool())
    r.register(StopSpeakTool())
    // Intent / 广播
    r.register(ExecuteIntentTool())
    r.register(SendBroadcastTool())
    // CMS v2 能力模块系统：让 AI 自我感知并真实调用已安装的能力
    r.register(QuroCmsListTool())
    r.register(QuroCmsCallTool())
    // CMS v2 终端部署/卸载（原创运行时）：把模块推到 proot 终端并运行
    r.register(QuroCmsDeployTool())
    r.register(QuroCmsUndeployTool())
    // CMS v2 统一工具箱：整合模块管理、引擎管理、开发环境管理、部署修复于一体
    r.register(QuroCmsToolboxTool())
    // 开发环境管理工具：独立管理终端开发环境的安装/卸载/检查
    r.register(QuroDevEnvTool())
    // ACI（Agent Capability Interface）：让 AI 作为控制方发现并调用第三方 App 暴露的能力
    r.register(QuroAidlAciListTool())
    r.register(QuroAidlAciCallTool())
    // ACI HTTP 模拟服务器（当真实 API 尚未完成时使用）
    r.register(QuroAciHttpServerTool())
    // 工作区 AI 工具：AI 直接读写 ZorvAI 自己的 QuroWorkspace（与构建台 ACI 协作）
    r.register(WorkspaceWriteTool())
    r.register(WorkspaceReadTool())
    r.register(WorkspaceListTool())
    r.register(WorkspaceRenderTool())   // 工作区文件渲染到对话框
    r.register(WorkspaceDocTool())      // 工作区文档创建
    r.register(WorkspaceMediaTool())    // 工作区媒体播放（音乐/视频）
    r.register(WorkspaceDocViewTool())  // 工作区文档查看（系统应用打开）
    // VNC 桌面环境工具
    r.register(VncTool())
    // 特权通道状态查询：AI 调用高风险能力前自查可用通道、自行选择
    r.register(QuroPrivStatusTool())
    // CMS v2 反馈环：状态/日志/结构化结果查询（让 AI 自我确认「部署/调用是否成功」）
    r.register(QuroCmsStatusTool())
    r.register(QuroCmsEngineStatusTool())
    r.register(QuroCmsLogsTool())
    r.register(QuroCmsResultTool())
    // CMS v2 DAG 编排：按依赖执行一组 terminal 命令
    r.register(QuroCmsRunDagTool())
    // QuroTerm 自研沙盒终端能力（集成 NovaTerm为 QuroTerm）
    r.register(QuroTermTool())
    // #564 终端直用特权通道执行（Shizuku→ROOT 自动降级）：run=以 root 执行命令 / status=查特权通道
    r.register(QuroPrivExecTool())
    // #565 ADB 可当 ADB 终端（无线调试中枢）：shell=本机 ADB shell / tcp_status|tcp_enable|tcp_disable=管理 TCP adbd
    r.register(QuroAdbTermTool())
    // LSPosed/Xposed 模块 AI 直驱（完整对接）：status/foreground 读桥数据、enable/disable 写 lsposed_bridge.json 管控桥开关
    r.register(QuroLsposeTool())
    // 记忆库：AI 自动沉淀长期记忆（保存/列出/检索/删除）
    r.register(QuroMemorySaveTool())
    r.register(QuroMemoryListTool())
    r.register(QuroMemorySearchTool())
    r.register(QuroMemoryDeleteTool())
    // AI 经验笔记 & 自我进化系统（App 本地沙盒，零隐私风险）
    r.register(QuroExperienceLogTool())
    r.register(QuroExperienceQueryTool())
    r.register(QuroExperienceCorrectTool())
    r.register(QuroExperienceVersionCheckTool())
    // ═════════════ L1 无障碍控屏（CapOS 通道，需无障碍服务已授权）══════════════
    // 屏幕感知：读取界面 / 前台应用 / 屏幕状态
    r.register(ReadScreenTool())
    r.register(GetForegroundAppTool())
    r.register(GetScreenStateTool())
    // 屏幕操控：点击 / 长按 / 滑动 / 输入文本 / 滚动 / 全局动作
    r.register(TapScreenTool())
    r.register(LongPressScreenTool())
    r.register(SwipeScreenTool())
    r.register(InputTextTool())
    // AI 智能体键盘（Agent IME）：ai_type_text / ai_press_enter / ai_press_send，走 IME 通道把文本打入聚焦输入框
    r.register(AiKeyboardTypeTool())
    r.register(AiKeyboardPressEnterTool())
    r.register(AiKeyboardSendTool())
    r.register(ScrollScreenTool())
    r.register(GlobalActionTool())
    // ═════════════ 屏幕视觉双模感知（截图+视觉分析）══════════════
    r.register(ScreenshotTool())           // 截图并保存文件
    r.register(ScreenshotBase64Tool())     // 截图返回Base64（用于视觉模型）
    r.register(VisualAnalysisTool())       // 截图+视觉模型分析
    // ═════════════ 系统级控制动作（一等公民）══════════════
    r.register(TakePhotoTool())            // 拍照
    r.register(ScreenRecordTool())         // 录屏
    r.register(VolumeControlTool())        // 音量控制
    r.register(BrightnessControlTool())    // 亮度控制
    r.register(WiFiControlTool())          // WiFi控制
    r.register(BluetoothControlTool())     // 蓝牙控制
    r.register(NotificationControlTool())  // 通知栏控制
    r.register(AirplaneModeTool())         // 飞行模式
    r.register(ScreenRotationTool())       // 屏幕旋转
    r.register(SetTimerTool())             // 倒计时
    r.register(OpenAppTool())              // 打开应用
    // 文件知识库（Path ②）：本地文档检索 + 写入，零基建覆盖日常知识检索
    r.register(KnowledgeSearchTool())
    r.register(KnowledgeAddTool())
    // 第三方服务授权保险库：供 http_request 用 service 参数自动带鉴权与 baseUrl
    r.register(AuthServiceAddTool())
    r.register(AuthServiceListTool())
    r.register(AuthServiceRemoveTool())
    // 终端驱动工具（统一为单一 terminal 工具，action 分发；内部复用原 10 个终端子工具实例）
    r.register(QuroTerminalTool())

    // ═════════════ L2 Shizuku 执行（CapOS 通道，需 Shizuku 已授权+运行中）══════════════
    r.register(ShizukuExecTool())
    r.register(ShizukuRootExecTool())
    r.register(FreezeAppTool())
    r.register(InstallAppTool())
    r.register(ShizukuStatusTool())

    // ═════════════ L3 设备管理员（CapOS 通道，需设备管理员已激活）══════════════
    r.register(LockScreenTool())
    r.register(DeviceAdminStatusTool())
    r.register(SetCameraDisabledTool())

    // ═════════════ L4 ROOT（CapOS 最高风险通道，需设备已 Root）══════════════
    r.register(RootExecTool())
    r.register(RootStatusTool())
    // L5 应用内 Linux 环境（proot + Ubuntu 24.04，可选高级入口，完整工具集解锁）
    r.register(LinuxRunTool())
    r.register(LinuxInstallTool())
    r.register(LinuxStartTool())
    r.register(LinuxStopTool())
    r.register(LinuxStatusTool())
    r.register(LinuxPackageTool())   // 统一包管理：自动适配 apt/apk/dnf/pacman
    // 隔离沙箱（应用私有目录隔离，路径穿越防护）
    r.register(QuroSandboxTool())
    // 应用私有数据库只读查询（应用私有目录隔离，只读安全）
    r.register(QuroPrivateDbTool())
    // 媒体：百分百开源本地音乐 / 视频播放器（基于 Android 框架 MediaPlayer / 系统播放能力）
    r.register(LocalMusicPlayerTool())
    r.register(MusicPlayTool())
    r.register(LocalVideoPlayerTool())
    // AI 自动化浏览器 + 联网搜索（后台可用）
    r.register(AiBrowserTool())
    // AI 操控内置浏览器（接管前台 WebView）：snapshot/click/fill/eval/wait/read
    r.register(BrowserActTool())
    // AI 在 proot 容器内执行 Python 代码
    r.register(PythonRunTool())
    // AI 抓包：在 proot 容器内启动 mitmdump，flow 写到 /mnt/quro/mitm/
    r.register(PacketCaptureTool())
    // AI 驱动网页爬虫：用内置浏览器 WebView 批量渲染抓取（支持 JS 动态页、同域限流、去重、导出 Markdown）
    r.register(WebCrawlerTool())
    // 终端监控：proot 内读 /proc 暴露负载/内存/进程（无 root 的 Android 应用也可观测，无需 cgroups）
    r.register(TermMonitorTool())
    // 升级版知识库（add / search / list，后台可用）
    r.register(KnowledgeManageTool())
    // 知识库 C3 重做：本地自包含向量语义检索（RAG），零重依赖，离线可用（无 API Key 时降级本地词法检索）
    r.register(QuroRagKnowledgeTool())
    // aiWPS 文档生成（docx / xlsx / pptx / pdf / md / txt / csv / html，后台生成真实可打开文件）
    r.register(AiwpsCreateTool())
    // aiWPS 文档读取 / 改写：补全「读-写-改」闭环，让 AI 能真正重写已有文档（解决"重写功能几乎没有"）
    r.register(AiwpsReadTool())
    r.register(AiwpsEditTool())
    // AI 多媒体生成/识别工具：LLM 直接调用，结果返回对话框
    r.register(ImageGenTool())           // AI 生图
    r.register(VideoGenTool())           // AI 生视频
    r.register(TranslateTool())          // 对话框多语言后端：文本翻译（消费 QuroFeatureModelConfig.TRANSLATION）
    r.register(ImageRecognitionTool())   // AI 图像识别
    r.register(AudioRecognitionTool())   // AI 音频识别
    r.register(VideoUnderstandingTool()) // AI 视频理解
    // 增强版文档创建工具：支持更多类型和更好渲染
    r.register(EnhancedDocTool())        // 增强版文档创建
    // UI 动作工具：打开界面 / 弹层 / 开关（ui_open_* / ui_toggle_* / ui_clear_chat 等）
    // allUiActionTools.forEach { r.register(it) }
    // 对话框富卡片工具：可视化小卡片（独立功能，与 ui_control / quro-ui / visual_popup 互不相关）
    r.register(UiCardTool())
    // 对话框内联 UI 组件工具：可视化富卡片（独立功能，与 ui_control / quro-ui / visual_popup 互不相关）
    r.register(UiWidgetTool())
    // MCP 客户端工具：让 AI 调用外部 MCP 服务器暴露的工具（#402）
    r.register(McpServersTool())
    r.register(McpListToolsTool())
    r.register(McpCallTool())
    // MCP-ACI 桥接工具：让 AI 通过 ACI 调用外部 MCP 服务器的工具
    r.register(McpAciListTool())
    r.register(McpAciCallTool())
    r.register(McpAciBridgeTool())
    // 工具发现工具：让 AI 主动查询工具能力目录，解决不会主动使用工具的问题
    r.register(ToolDiscoveryTool())
    // 本地 MCP 部署工具：AI 创作并部署 MCP 服务器到本应用内（#Task8）
    r.register(McpDeployTool())
    r.register(McpUndeployTool())
    r.register(McpListLocalTool())
    // 广义 IDE 集成工具：图形/视频/音频/3D/游戏/低代码/代码 IDE 的完整知识库和调用能力
    r.register(CreativeStudioTool())
    // 可视化问答和操作弹窗工具
    r.register(VisualQuestionTool())   // 可视化问答弹窗
    r.register(VisualActionTool())     // 可视化操作弹窗
    r.register(VisualPopupTool())      // 自由可视化弹窗（固定UI组件）
    r.register(VisualCustomPopupTool()) // AI自写UI可视化弹窗（完全自定义HTML）
    r.register(NodeEditorTool())       // 节点编辑器：AI 直接读写节点流工程（无需打开界面）
    r.register(VisualStudioTool())     // 可视化编程：命名工程多项目保存（产物+可视化）
    // 动态 UI（必备输出）工具：AI 默认主动输出 quro-ui DSL 代码块 → 解析 → Compose 原生渲染（可交互、可回传表单值）
    r.register(UiDslSpecTool())     // 拉取 DSL 规范，避免长 schema 常驻系统提示词
    r.register(UiValidateTool())    // 输出前自检，把「渲染失败」变成事前修正
    // UI 导航工具集：让 AI 能操控自己的界面（ui_* 命名规范）
    registerUiTools(r)
    // 流体云工具：控制OPPO流体云，显示状态栏胶囊和卡片
    r.register(FluidCloudTool(context!!))
    // 对话框文档工具：AI在对话框内直接写文档并渲染显示
    r.register(ChatDocTool())          // 对话框文档（Markdown/HTML/代码/文本）
    // 并入「导入工具」（AI 自写 / 用户粘贴 JSON 导入），使其可被 AI 调用
    context?.let { r.attach(it) }
    // 并入「可调用技能」：把用户技能注册为 AI 工具函数（function calling），与导入工具同理
    context?.let { r.mergeSkills(it) }
    // 同步技能可调用开关（来自模型配置）：使 QuroModelConfig.skillToolsEnabled 生效
    context?.let { ctx ->
        runCatching {
            val cfg = QuroModelConfigRepository(ctx).load()
            r.skillToolsEnabled = cfg.skillToolsEnabled
            r.maxSkillTools = cfg.maxSkillTools
        }
    }
    // 高级能力工具集。
    // 这一批此前只是「独立组件」：有完整业务逻辑，但既没实现 QuroTool 契约也没注册，
    // 因此从未进入过模型的工具集——功能写了等于没写。现已补齐契约并在此注册。
    context?.let { ctx ->
        r.register(QuroMultiProviderTool(ctx))   // multi_provider  ：多提供商配置 / 故障转移 / 健康检查
        r.register(QuroHeartbeatTool(ctx))       // heartbeat       ：自主心跳与系统自检报告
        r.register(QuroLocalModelTool(ctx))      // local_model     ：本地模型下载 / 加载 / 推理
        r.register(QuroVirtualDisplayTool(ctx))  // virtual_display ：虚拟显示器与后台自动化
        r.register(QuroTaskSchedulerTool(ctx))   // task_scheduler  ：定时任务调度（Cron）
        r.register(QuroDataManagerTool(ctx))     // data_manager    ：数据导出 / 导入 / 加密备份
    }
    // 工具能力目录以真实注册表为单一真相源动态生成（修复「分组目录残缺→AI 查不到/不主动用工具」）
    // 用 fullSpecs()（内置全部 + 技能工具），与模型实际下发集合严格一致（core/full 模式下目录==可调用全集）
    ToolCapabilityDirectory.install(r.fullSpecs())
    return r
}
