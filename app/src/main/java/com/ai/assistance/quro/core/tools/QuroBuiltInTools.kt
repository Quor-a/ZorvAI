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
import com.ai.assistance.quro.core.cms.QuroPrivStatusTool
import com.ai.assistance.quro.core.aidlaci.QuroAidlAciCallTool
import com.ai.assistance.quro.core.aidlaci.QuroAidlAciListTool
import com.ai.assistance.quro.core.tools.TerminalDriveTool
import com.ai.assistance.quro.core.tools.ReadScreenTool
import com.ai.assistance.quro.core.tools.GetForegroundAppTool
import com.ai.assistance.quro.core.tools.GetScreenStateTool
import com.ai.assistance.quro.core.tools.TapScreenTool
import com.ai.assistance.quro.core.tools.SwipeScreenTool
import com.ai.assistance.quro.core.tools.InputTextTool
import com.ai.assistance.quro.core.tools.ScrollScreenTool
import com.ai.assistance.quro.core.tools.GlobalActionTool
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
import com.ai.assistance.quro.core.tools.TerminalExecTool
import com.ai.assistance.quro.core.tools.TerminalWriteTool
import com.ai.assistance.quro.core.tools.TerminalKillTool
import com.ai.assistance.quro.core.tools.TerminalStatusTool
import com.ai.assistance.quro.core.tools.TerminalInterruptTool
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
    // 闹钟
    r.register(SetAlarmTool())
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
    // ACI（Agent Capability Interface）：让 AI 作为控制方发现并调用第三方 App 暴露的能力
    r.register(QuroAidlAciListTool())
    r.register(QuroAidlAciCallTool())
    // 特权通道状态查询：AI 调用高风险能力前自查可用通道、自行选择
    r.register(QuroPrivStatusTool())
    // CMS v2 反馈环：状态/日志/结构化结果查询（让 AI 自我确认「部署/调用是否成功」）
    r.register(QuroCmsStatusTool())
    r.register(QuroCmsEngineStatusTool())
    r.register(QuroCmsLogsTool())
    r.register(QuroCmsResultTool())
    // CMS v2 DAG 编排：按依赖执行一组 terminal 命令
    r.register(QuroCmsRunDagTool())
    // QuroTerm 自研沙盒终端能力（集成 NovaTerm，去品牌化为 QuroTerm）
    r.register(QuroTermTool())
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
    // 屏幕操控：点击 / 滑动 / 输入文本 / 滚动 / 全局动作
    r.register(TapScreenTool())
    r.register(SwipeScreenTool())
    r.register(InputTextTool())
    // AI 智能体键盘（Agent IME）：ai_type_text / ai_press_enter / ai_press_send，走 IME 通道把文本打入聚焦输入框
    r.register(AiKeyboardTypeTool())
    r.register(AiKeyboardPressEnterTool())
    r.register(AiKeyboardSendTool())
    r.register(ScrollScreenTool())
    r.register(GlobalActionTool())
    // 文件知识库（Path ②）：本地文档检索 + 写入，零基建覆盖日常知识检索
    r.register(KnowledgeSearchTool())
    r.register(KnowledgeAddTool())
    // 第三方服务授权保险库：供 http_request 用 service 参数自动带鉴权与 baseUrl
    r.register(AuthServiceAddTool())
    r.register(AuthServiceListTool())
    r.register(AuthServiceRemoveTool())
    // 终端驱动工具（应用沙盒内免权限 shell，Runtime.exec /system/bin/sh）：
    r.register(TerminalDriveTool())
    r.register(TerminalExecTool())
    r.register(TerminalWriteTool())
    r.register(TerminalKillTool())
    r.register(TerminalStatusTool())
    // E-9：两段式中断（先 ETX，再强杀重建会话）
    r.register(TerminalInterruptTool())

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
    // L5 应用内 Linux 环境（proot + Alpine，可选高级入口，完整工具集解锁）
    r.register(LinuxRunTool())
    r.register(LinuxInstallTool())
    r.register(LinuxStartTool())
    r.register(LinuxStopTool())
    r.register(LinuxStatusTool())
    // 媒体：百分百开源本地音乐 / 视频播放器（基于 Android 框架 MediaPlayer / 系统播放能力）
    r.register(LocalMusicPlayerTool())
    r.register(MusicPlayTool())
    r.register(LocalVideoPlayerTool())
    // AI 自动化浏览器 + 联网搜索（后台可用）
    r.register(AiBrowserTool())
    // 升级版知识库（add / search / list，后台可用）
    r.register(KnowledgeManageTool())
    // 知识库 C3 重做：本地自包含向量语义检索（RAG），零重依赖，离线可用（无 API Key 时降级本地词法检索）
    r.register(QuroRagKnowledgeTool())
    // aiWPS 文档生成（docx / xlsx / pptx / pdf / md / txt / csv / html，后台生成真实可打开文件）
    r.register(AiwpsCreateTool())
    // aiWPS 文档读取 / 改写：补全「读-写-改」闭环，让 AI 能真正重写已有文档（解决"重写功能几乎没有"）
    r.register(AiwpsReadTool())
    r.register(AiwpsEditTool())
    // UI 动作工具：把对话框界面/弹层/开关注册为 AI 可调用工具（走 QuroUiActionBridge 回调 ChatScreen）
    allUiActionTools.forEach { r.register(it) }
    // 对话框富卡片工具：AI 下发可交互卡片（待办/图表/笔记/动作）
    r.register(UiCardTool())
    // 对话框内联 UI 组件工具（v134）：AI 在对话框里直接展示可交互 UI（按钮/开关/滑块/进度/统计/提醒/表格/列表/分段/饼图/评分/倒计时/标签页/折叠/表单/标签/步骤/仪表/媒体/信息）
    r.register(UiWidgetTool())
    // MCP 客户端工具：让 AI 调用外部 MCP 服务器暴露的工具（#402）
    r.register(McpServersTool())
    r.register(McpListToolsTool())
    r.register(McpCallTool())
    // 本地 MCP 部署工具：AI 创作并部署 MCP 服务器到本应用内（#Task8）
    r.register(McpDeployTool())
    r.register(McpUndeployTool())
    r.register(McpListLocalTool())
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
    return r
}
