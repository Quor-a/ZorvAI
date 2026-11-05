package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.skill.QuroSkill
import com.ai.assistance.quro.core.skill.QuroSkillStore
import org.json.JSONObject
import com.ai.assistance.quro.core.QuroToolCall
import com.ai.assistance.quro.core.QuroToolResult
import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.chat.ApprovalGate
import com.ai.assistance.quro.core.chat.ApprovalMemory
import com.ai.assistance.quro.core.chat.ApprovalPolicy
import com.ai.assistance.quro.core.chat.ApprovalRequest
import com.ai.assistance.quro.core.agent.loop.ClosedLoopExecutor
import com.ai.assistance.quro.core.agent.loop.DEFAULT_TABLE
import com.ai.assistance.quro.core.agent.loop.ExecResult
import com.ai.assistance.quro.core.agent.loop.FailurePolicyRegistry
import com.ai.assistance.quro.core.agent.loop.FailureType
import com.ai.assistance.quro.core.agent.loop.RecoveryAction
import com.ai.assistance.quro.core.agent.loop.ScenarioFailurePolicy
import com.ai.assistance.quro.core.agent.loop.ToolOutcome
import com.ai.assistance.quro.core.mcp.DroidMcp
import com.ai.assistance.quro.core.mcp.McpTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/**
 * 工具接口：名称 + 描述 + 参数 JSON-Schema + 执行。
 */

/** 单个工具执行的硬超时（毫秒），防止卡住吃满 180s 总超时。 */
private const val TOOL_EXEC_TIMEOUT_MS = 60_000L
interface QuroTool {
    val name: String
    val description: String
    val parametersJson: String
    fun run(context: Context, arguments: String): String

    /**
     * 本次调用的执行预算（毫秒），默认 [TOOL_EXEC_TIMEOUT_MS]（60s）。
     *
     * ## 为什么按参数算而不是固定值（#496）
     * 有些工具**天然长跑**，其自身 API 就带超时参数（如 `harness` 的
     * `timeout_seconds`，默认 600s、上限 3600s）。此前所有工具共用 60s 硬超时，
     * harness 派活每次都在 60s 被强杀（连同 QuroToolProcesses 杀掉引擎会话），
     * 闭环的 TIMEOUT 策略又按默认表原样重跑两次 —— 用户侧就是
     * 「活已经干了但看不到过程和结果，答复一片空白」。
     *
     * 长跑工具 override 本函数，按同一个超时参数返回预算。
     * 实现约定：预算 ≥ 工具自身超时，绝不小于 —— 否则外层先杀、任务还在跑，
     * 结果永远回不来。
     */
    fun execTimeoutMs(arguments: String): Long = TOOL_EXEC_TIMEOUT_MS

    /** 该工具运行前需确保已授予的危险权限（运行时申请）。默认无。 */
    val requiredPermissions: List<String> get() = emptyList()

    /**
     * 该工具是否为**只读**（不改变任何持久状态）。
     *
     * ## 为什么默认 false 而不是 true
     * 宁可**漏并发**也不可**错并发**：并发前提是「两个调用互不影响」，
     * 而漏并发的代价只是慢一点，错并发的代价是数据丢失 / 重复扣款 / 两条重复消息。
     * 因此未显式声明的工具一律按「可能有副作用」处理，走原串行路径。
     *
     * ## 判定标准（实现者自己把关）
     *  - true：纯读 —— 读文件、查状态、搜索、列表、计算、getter 类。
     *  - false：任何写、删、发、启停、支付、装卸、授权、发通知，
     *    以及**任何读依赖某个可被并发改写的可变状态**的情况。
     * 拿不准就填 false。
     */
    val readOnly: Boolean get() = false

    /**
     * 该工具执行前是否**需要用户审批**（危险/不可逆/高副作用）。
     *
     * 默认 false（大多数工具不需要审批）；只有「执行 shell、写/删文件、
     * 控制手机、发消息、支付、装卸应用」这类**一旦执行就无法撤回**的工具才填 true。
     *
     * 审批在 [QuroAssistant] 的工具执行协程里拦截：调用 [com.ai.assistance.quro.core.chat.ApprovalGate.awaitDecision]
     * 挂起等用户在审批卡上拍板；拒绝则短路，工具不执行。
     *
     * 与 [requiredPermissions] 的区别：权限是**系统级一次性授权**（运行时申请），
     * 审批是**每一次调用前的业务确认**（用户主观判断这次该不该做）。
     */
    val requiresApproval: Boolean get() = false

    /**
     * 风险等级提示（可选）：审批卡上显示的醒目风险说明，如「不可逆」「涉及扣款」。
     * 仅当 [requiresApproval] 为 true 时有意义；为空则只展示默认提示。
     */
    val risk: String? get() = null

    /**
     * 用于「按 Bot 隔离工具」——人格卡可用 [QuroPersona.enabledToolCategories]
     * 声明启用的类别白名单，未命中类别的工具不下发给该 Bot。
     *
     * 默认 [ToolCategory.GENERAL]（通用）。类别清单见 [ToolCategory]。
     * 实现者可在 override 时改类别；未显式声明的工具归入 general，保证白名单为空（=全部）时零影响。
     */
    val category: String get() = ToolCategory.GENERAL
}

/** 工具注册表（持有全部原创工具）。 */
class QuroToolRegistry {
    companion object {
        /** 当前进程主注册表实例（由 QuroChatViewModel 在构建后写入，供技能管理等 UI 立即注销已注册工具）。 */
        @Volatile var active: QuroToolRegistry? = null
    }

    private val map = LinkedHashMap<String, QuroTool>()

    /** 技能可调用（function calling）总开关：false=技能仅注入系统提示词，不下发为工具函数。 */
    var skillToolsEnabled: Boolean = true
    /** 最多下发的技能工具数量（避免工具集过大被 API 中转静默丢弃）。 */
    var maxSkillTools: Int = 16

    fun register(tool: QuroTool) {
        map[tool.name] = tool
    }

    /**
     * 删除已注册工具（含技能工具 skill__<name>、导入工具等）。返回是否确有移除。
     * 反向级联：删除「技能工具」（skill__<name>）时，同步删除对应的用户技能定义，
     * 否则下次 mergeSkills 会把该技能重新注册为工具「复活」，造成「工具删了技能还在」的悬挂（#913）。
     */
    fun remove(name: String): Boolean {
        val removed = map.remove(name) != null
        if (removed && name.startsWith("skill__")) {
            appContext?.let { ctx ->
                QuroSkillStore.load(ctx).firstOrNull { QuroSkill.toolNameOf(it.name) == name }
                    ?.let { QuroSkillStore.remove(ctx, it.id) }
            }
        }
        return removed
    }

    fun get(name: String): QuroTool? = map[name]
    fun all(): List<QuroTool> = map.values.toList()
    fun specs(): List<QuroToolSpec> = map.values.map {
        QuroToolSpec(it.name, it.description, it.parametersJson)
    }

    /** 绑定 Application Context 并把已持久化的「导入工具」并入运行时注册表（执行 + 下发明细都生效）。 */
    fun attach(context: Context) {
        appContext = context
        mergeImported(context)
    }

    /** 将导入工具（AI 自写 / 用户粘贴 JSON 导入）注册进本注册表，使其可被 AI 调用。 */
    fun mergeImported(context: Context) {
        QuroImportedToolRegistry.all().forEach { register(QuroImportedTool(it)) }
    }

    private var appContext: Context? = null

    /**
     * 核心工具规格（标准集 ~23 个）。
     *
     * 为什么需要分档：47 个完整工具的 definitions 占 ~4,350 tokens，
     * 大量 API 中转/代理对请求总大小或工具有数限制（常见上限 20-30，
     * 或总 token 上限 6-8K），超限后可能**静默丢弃整个 tools 字段**，
     * 导致模型完全无法调用任何工具（表现为「纯问答、不执行动作」）。
     *
     * 标准集覆盖 95%+ 的日常口语指令（打开应用、设备状态、网络、剪贴板、
     * 短信/联系人、手电筒、闹钟、运行代码、屏幕控制、TTS 等），
     * 将 token 开销降到 ~2,100 以内，确保绝大多数 API 中转能正常透传。
     *
     * 🔴 **实测与本注释已严重脱节（2026-10-05 实测）**：coreSpecs 实为 **201 个 / 86,506 字符**，
     * 不是「~23 个 / ~1,200 tokens」的十几倍；fullSpecs 实为 **258 个 / 129,351 字符**，
     * 不是「~226 个」。所谓「精简集」只省 33%，达不到本注释宣称的降 token 目标。
     * 需要真正控制工具层体积时，走 ZorvPromptBudget.fitTools（按输入预算整份取舍，不裁内容）。
     *
     * 若你的 API 代理支持完整工具（如直连 OpenAI / DeepSeek / SiliconFlow），
     * 可在 [com.ai.assistance.quro.core.QuroAssistant.ask] 中将
     * `registry.coreSpecs()` 改为 `registry.fullSpecs()` 解锁全部工具（实测 258 个）。
     */
    fun coreSpecs(): List<QuroToolSpec> {
        // 精简核心集：仅保留口语指令最高频、且依赖链最短的动作。
        // 目的——把工具 definitions 的 token 开销压到 ~1,200 以内，
        // 确保绝大多数 API 中转（含对「工具数 / 总 token」有上限的代理）能正常透传，
        // 不再静默丢弃整个 tools 字段（那是此前「纯问答、不执行动作」的根因）。
        // 若你的代理确认支持完整工具（直连 OpenAI / DeepSeek / SiliconFlow 等），
        // 可在 QuroAssistant.ask 中将 registry.coreSpecs() 改为 registry.fullSpecs()
        // 解锁全部工具（实测 258 个 / 129,351 字符 —— 见上方「实测与本注释已严重脱节」）。
        // 扩展核心集：覆盖 95%+ 日常口语指令，让模型「知道有什么工具、该用哪个」。
        // 默认（useFullTools=false）即下发此集；fullSpecs 在其基础上再并入其余高级/小众工具。
        // 注意：菜单（appendCapabilityAwareness）与 tools 字段都由此集生成，二者严格一致，
        // 避免模型选了菜单里有、字段里没有的工具而报「未知工具」。
        val coreNames = setOf(
            // 基础
            "get_current_time", "get_device_info", "calculate",
            "get_battery", "get_wifi_info", "get_network_info", "get_sensors", "vibrate",
            "get_clipboard", "set_clipboard",
            // 应用管理
            "list_installed_apps", "launch_app", "search_and_launch_app", "get_package_name",
            "list_app_functions", "invoke_app_function",
            // 通知 / 蓝牙 / 手电（注意：真实注册名是 get_active_notifications，coreNames 必须与之一致，否则工具会被静默丢弃）
            "get_active_notifications", "get_bluetooth_status", "toggle_flashlight",
            // 相机能力补充（能力查询/录像；take_photo 拍照此前漏在核心集外，一并补入）
            "get_camera_capabilities", "take_photo", "capture_video",
            // 通信
            "read_sms", "send_sms", "read_contacts",
            // Intent / 系统广播（唤起其他 App 的 Activity/Service、发系统广播）
            "execute_intent", "send_broadcast",
            // 日历 / 位置
            "read_calendar", "write_calendar", "get_location", "geocode",
            // 文件（只读类，安全）
            "list_files", "read_text_file", "browse_files", "file_read",
            // 文件写/改/删（IDE 集成后默认开放；为高危工具，用户可在设置关闭「完整工具集」回退到只读集）
            "write_file", "delete_file", "make_directory", "move_file", "copy_file", "find_files", "file_info",
            // 格式转换（csv↔json、markdown→html、json 美化；对应「其他 AI」扩展生态的 file_converter）
            "file_converter",
            // 文件编辑增强（writer 结构化写 / apk_editor 改包 / exe_editor 改 PE；对应「其他 AI」文件与编辑维）
            "writer", "apk_editor", "exe_editor",
            // 记忆系统工程化（多空间 / 重建 / 自动保存候选 / 窗口规划 / 导入导出；对应「其他 AI」记忆维分层架构）
            "memory_space", "memory_rebuild", "memory_autosave", "memory_window_plan", "memory_export",
            // 网络 / Web
            "http_request", "open_web", "ai_browser",
            // 自研端侧联网检索（多引擎并发 + 查询改写 + 正文密度抽取 + 五信号重排 + 上下文打包）
            "web_search", "read_url",
            // 对话框内 GitHub 搜索引擎（仓库/代码/Issue/用户）
            "github_search",
            // 屏幕捕获授权（AI 主动发起 MediaProjection 系统授权，无需手动长按开关）
            "enable_screen_capture",
            // 代码执行
            "run_code",
            // SandboxPackage 完整脚本运行时（JS/TS + Tools.* 宿主 API）+ ToolPkg 脚本包 + 项目模板
            "code_runner", "toolpkg_list", "toolpkg_call", "project_create",
            // 广义 IDE 集成（图形/视频/音频/3D/游戏/低代码/代码 IDE）
            "creative_studio",
            // 终端（应用沙盒内 PTY / shell，免权限，无 root/Shizuku）
            // 🔴 `termux` 必须在 coreNames 里：coreSpecs() 是**下发给模型的 tools 字段的唯一来源**
            // （注释已说明菜单与 tools 字段同源）。此前只有 `terminal`，于是 `termux` 工具
            // 从未出现在 tools 里 —— 模型连看都看不到，表现就是「怎么提示都不会用 pkg」。
            // 两者是**两套环境**，不是替代关系：terminal=proot Ubuntu（apt），termux=官方 bootstrap（pkg）。
            "terminal", "termux", "quroterm_exec",   // 合并后的统一终端工具（应用内 proot+Ubuntu / 设备 shell / Termux 原生）
            // TTS
            "speak", "stop_speak",
            // 闹钟
            "set_alarm",
            // 定时任务/自动化提醒
            "schedule_task", "list_scheduled_tasks", "delete_scheduled_task",
            // 记忆库
            "memory_save", "memory_list", "memory_search", "memory_delete",
            // AI 经验闭环（自我进化：报错/方案/工具模式/版本差异 的沉淀与复用）
            "experience_log", "experience_query", "experience_correct", "experience_version_check",
            // 文件知识库（Path ②）
            "knowledge_search", "knowledge_add", "knowledge_manage", "knowledge_rag_search",
            // 文档生成（aiWPS：本地生成 WPS / Office 兼容 .docx/.xlsx/.pptx，零外部依赖）
            "aiwps_create",
            // CodeCanvas 出图后端（外部 FastAPI 渲染服务）：确定性渲染，同样输入永远出同样的图，
            // 适合海报 / 报表 / 代码卡这类要**精确排版**的内容（image_gen 走厂商生图 API，适合创意图）。
            // 🔴 必须进 coreNames：coreSpecs 是「关掉完整工具集」时的下发集，也是
            //   appendCapabilityAwareness 的同源输入；漏在这里 → 默认配置下模型看不到、
            //   系统提示词的工具清单里也不会出现（用户报「AI 根本查不到」）。
            "codecanvas_probe", "codecanvas_script", "codecanvas_code_card",
            "codecanvas_markup", "codecanvas_llm_code",
            // 增强文档创建（md/html/json/xml/yaml/css/js/svg/odt/epub/rtf 等 17 种，含 AIP 文档导出）
            "enhanced_doc_create",
            // 后台 AIP 排版合成（整篇长文档/PPT/报告以工具调用形式产出，对话框渲染 B 通道 Canvas 引擎）
            "aip_compose",
            // 对话框富卡片（AI 下发可交互卡片：待办/图表/笔记/动作）
            "ui_card",
            // 对话框内联 UI 组件（v134：按钮/开关/滑块/进度/统计/提醒/表格/列表/分段/饼图/评分/倒计时/标签页/折叠/表单/标签/步骤/仪表/媒体/信息）
            "ui_widget",
            // zxwidget 动态交互界面（477 个 zx 组件 → 可交互控件，**直接长在对话框消息流里**）。
            // 🔴 必须进 coreNames，理由与上面 codecanvas_* / genui_* 两条**完全一样**：
            //   coreSpecs 是「关掉完整工具集」时的下发集，也是 appendCapabilityAwareness
            //   的同源输入 —— 漏在这里，默认配置下模型两处都看不到它。
            //   2026-10-13 实测：两个 zx 工具只注册在 [QuroBuiltInTools]，
            //   既不在 coreNames、也不在 [QuroToolRouter.ALWAYS_ON]，
            //   于是 PROGRESSIVE 路由下它们**从不进 tools 字段**：
            //   渲染链路整条完好，却因为「模型永远调不到」而表现为「根本渲染不了」。
            "zx_web_card", "zx_catalog",
            // 生成式界面：两条通道必须都进核心集，否则默认配置（useFullTools=false）下模型看不到，
            // 用户说「做个界面/小应用」时只会去凑 ui_widget 卡片。
            //   · genui_agent_open → 内置完整 GenUI-Agent 独立应用（GenUI JSON DSL → 原生 Compose 组件）
            //   · miniapp          → Web 应用（WebView + native.* 原生桥，MiniAppEngine，HTML+JS+CSS）
            "genui_agent_open", "miniapp",
            // 节点编辑器：AI 直接读写节点流工程（与工具中心面板共享 studio/flow/*.qne，面板打开自动恢复）
            "node_editor",
            // 工作流引擎 AI 侧调用入口：创建/点燃/查询运行（缺口清单 · 可实现项）
            "wf_create", "wf_trigger", "wf_run_status",
            // MCP 客户端：AI 调用外部 MCP 服务器工具（#402）
            "mcp_servers", "mcp_list_tools", "mcp_call", "mcp_deploy", "mcp_undeploy", "mcp_list_local",
            // MCP-ACI 桥接：通过 ACI 调用外部 MCP 服务器工具
            "mcp_aci_list", "mcp_aci_call", "mcp_aci_bridge",
            // 第三方服务授权保险库
            "auth_service_add", "auth_service_list", "auth_service_remove",
            // CMS v2 能力模块 + 特权通道自查
            "cms_list", "cms_call", "cms_status", "cms_logs", "cms_result", "cms_run_dag", "cms_deploy_terminal", "cms_undeploy_terminal", "priv_status", "cms_engine_status",
            // ACI（Agent Capability Interface）：AI 作为控制方调用第三方 App 暴露的能力（发现 + 调用）
            "aci_list", "aci_call",
            // 工作区 AI 工具：AI 直接读写 ZorvAI 自己的 QuroWorkspace（与构建台 ACI 协作写码→编译）
            "workspace_write", "workspace_read", "workspace_list",
            // 构建台：端侧 APK 构建（Java → DEX → APK，AI 可真正触发编译打包，与 ui_open_build 互补）
            "build_apk",
            // 构建台：导出产物（构建台 UI 的导出是文件选择器、AI 用不了，本工具替代它把 APK 导出到可访问位置）
            "export_apk",
            // L1 无障碍控屏（CapOS 通道）
            "read_screen", "get_foreground_app", "get_screen_state",
            "tap_screen", "swipe_screen", "input_text", "scroll_screen", "global_action",
            // AI 智能体键盘（Agent IME）：把文本直接打字进聚焦输入框（需启用并切到『Zorv AI 键盘』）
            "ai_type_text", "ai_press_enter", "ai_press_send",
            // 媒体：百分百开源本地音乐 / 视频播放器（后台可用，对话框显示播放卡片）
            "local_music_player", "local_video_player", "list_media", "music_play",
            // 真实执行链路（L1-L4 CapOS 通道已恢复 v115，L5 Linux v116 恢复）：
            // L2: Shizuku ADB 级 IPC（shizuku_exec/freeze_app/install_app/shizuku_root_exec/shizuku_status）
            // L3: 设备管理员（lock_screen/device_admin_status/set_camera_disabled）
            // L4: ROOT 执行（root_exec/root_status）
            // L5: 应用内 Linux 环境（linux_run/linux_install/linux_start/linux_stop/linux_status）
            // 说明：L2-L5 默认进核心集（AI 默认可调用），运行时由系统权限授予 / 资产可用性再把关；
            // 若仍想收紧，可在设置关闭「完整工具集」或调整下方 coreNames。
            "shizuku_exec", "shizuku_root_exec", "freeze_app", "install_app", "shizuku_status",
            "lock_screen", "device_admin_status", "set_camera_disabled",
            "root_exec", "root_status",
            "linux_run", "linux_install", "linux_start", "linux_stop", "linux_status",
            // terminal_run/terminal_exec 为应用沙盒内免权限 shell（Runtime.exec /system/bin/sh）
            // 流体云（OPPO Fluid Cloud）：状态栏胶囊和卡片显示
            "fluid_cloud_notify",
            // 隔离沙箱 + 私有数据库只读查询（应用内免权限）
            "sandbox", "private_db",
            // KaleidoBox 工具包运行器：进程内 JVM / Dex 引擎运行 Kotlin/Java 工具包（list/install/invoke）
            "kaleidobox",
            // ★ APK 级插件框架总控（唯一入口）：列/装/卸/重载插件 + 查看与调用插件贡献的 AI 工具
            //   + 打开插件界面。插件动态贡献的工具由 pluginHostToolSpecs() 另行并入。
            "apk_plugin",
            // 七维能力补全（Dim1/2/3/4/7）：扩展生态 / 五语言真执行 / UI 自动化 / FFmpeg 链 / 终端增强
            // Dim1 扩展生态（网络类 + 编排）
            "crossref_search", "search_provider", "img_gen_provider", "train_12306", "github_read",
            "workflow_run", "automatic_ui",
            // Dim2 五语言真执行
            "polyglot_run",
            // Dim3 UI 自动化
            "ui_tree",
            // Dim4 媒体
            "ffmpeg",
            // Dim7 终端增强
            "tmux", "terminal_keys",
            // Dim5 文件与编辑补全：Git 集成 + 语法检查
            "git", "syntax_check",
            // 自动化集成：Tasker 双向（#39）
            "tasker",
            // 宿主技能库管理：AI 只可列举技能（具体用哪个由用户在对话框选择后随消息发送）
            "skill_list",
        ) + uiActionToolNames() // 并入「UI 动作工具」：对话框界面/弹层/开关 → AI 可调用（见 QuroToolsUiActions）
        val base = map.values.filter { it.name in coreNames }.map {
            QuroToolSpec(it.name, it.description, it.parametersJson)
        }
        // 导入工具（AI 自写 / 用户粘贴 JSON）默认进核心集：导入成功即成为可调用工具
        val imported = QuroImportedToolRegistry.all().map {
            QuroToolSpec(it.name, it.description, it.parametersJson)
        }
        // APK 级插件框架：宿主内置的插件管理工具（plugin_list/install/uninstall/reload/info）
        // + 已装插件动态贡献的 AI 工具。二者都由 QuroPluginHost 统一产出，
        // 保证「装了插件 → 下一轮 function calling 就能看到它的工具」。
        val pluginTools = runCatching { pluginHostToolSpecs() }.getOrElse { emptyList() }
        return guardSpecs((base + imported + pluginTools).plus(skillSpecs()))
    }

    /** 完整工具规格（全部内置工具 + 技能工具）。仅在 API 代理确认支持时使用（见 coreSpecs 说明）。 */
    fun fullSpecs(): List<QuroToolSpec> = guardSpecs(specs().plus(skillSpecs()))

    /**
     * 下发前的统一护栏（N5）：按名去重 + 剔除非法名，并把「被丢弃」变成**响亮日志**。
     *
     * 为什么不能只用 `distinctBy { it.name }`：它会**静默**丢掉同名工具。
     * 这正是「装了技能却只有个别能被调用」的元凶 —— 全中文技能名曾全部坍缩成
     * `skill__skill`，于是被去重到只剩一个，且没有任何线索。
     * 现在只要发生重复，日志里就会列出每一个被丢掉的名字。
     *
     * 非法名（含超长）一律**剔除**：`tools` 是一整段 JSON，只要一个名字不合规，
     * 严格上游就会整段 400 —— 结果是**所有工具调用一起失效**，而不是少一个工具。
     * 剔除 + error 日志，是把「全挂」换成「少一个且看得见」的 fail-safe。
     */
    private fun guardSpecs(raw: List<QuroToolSpec>): List<QuroToolSpec> {
        val result = QuroToolSpecGuard.dedupe(raw)
        if (result.hadDuplicates) {
            android.util.Log.w(
                "QuroToolEngine",
                "⚠️ 工具名重复，已丢弃 ${result.droppedDuplicates.size} 个：${result.droppedDuplicates.joinToString(", ")}。" +
                    "同名工具对模型不可区分（模型只会调到先注册的那个）—— 若是技能，请改名以避免撞名。",
            )
        }
        val (legal, illegal) = result.specs.partition { QuroToolSpecGuard.isLegalName(it.name) }
        if (illegal.isNotEmpty()) {
            android.util.Log.e(
                "QuroToolEngine",
                "🔴 丢弃 ${illegal.size} 个工具名非法的工具：${illegal.joinToString(", ") { it.name }}。" +
                    "原因：上游会整段拒收 tools 数组（=所有工具调用一起失效）。请在注册处修正名字。",
            )
        }
        //
        // 目的：活动流里每条工具调用要显示一句**用户可读**的意图描述
        // （如「探测本地 MCP 端点」），而不是 `quroterm_exec` 这种内部工具名。
        // 做法：给工具 schema 追加一个可选字符串参数 display_intent，
        // 并在描述里写明写法要求；模型调用时会自己填这句话，UI 直接显示。
        //
        // 为什么放在 guardSpecs 而不是各处构造：这里**所有下发路径的唯一收口**
        // （coreSpecs / fullSpecs / specs / skillSpecs 都过它），
        // 放这里才能保证「下发的工具」与「UI 能显示意图」永远一致。
        return legal.map { QuroToolSpecGuard.withDisplayIntent(it) }
    }

    /**
     * 技能工具规格：把「可调用」的用户技能注册为 function-calling 工具下发。
     * 受 [skillToolsEnabled] 总开关与 [maxSkillTools] 上限约束。
     */
    private fun skillSpecs(): List<QuroToolSpec> {
        val ctx = appContext ?: return emptyList()
        if (!skillToolsEnabled) return emptyList()
        return QuroSkillStore.callableList(ctx)
            .sortedByDescending { it.updatedAt }.take(maxSkillTools)
            .map {
                QuroToolSpec(
                    QuroSkill.toolNameOf(it.name),
                    it.description.ifBlank { "用户技能：${it.name}" },
                    it.parametersJson,
                )
            }
            // 下发前兜底：仅保留工具名合法者。sanitize 已保证合法，此处为防御性回退，
            // 防止任何异常路径注入非法名导致整轮 tools 被服务端 400 拒收（issue #10）。
            // N5：改用统一的 isLegalName —— 它同时覆盖**长度上限 64**，旧正则只查字符集。
            .filter { QuroToolSpecGuard.isLegalName(it.name) }
    }

    /** 把可调用技能注册为运行时工具实例（双保险：使 registry.get("skill__xxx") 也能命中）。 */
    fun mergeSkills(context: Context) {
        if (!skillToolsEnabled) return
        QuroSkillStore.callableList(context).take(maxSkillTools)
            .forEach { register(QuroSkillTool(it.name, context.applicationContext)) }
    }
}

/**
 * 双引擎工具引擎（原创编排）：
 * - 主调度引擎：vendored droid-mcp（Apache-2.0）进程内直接派发
 * - 预留官方 MCP Kotlin SDK 引擎接入点（见 QuroMcpSdkEngine，v2 接入）
 * 两个引擎共用同一份 QuroTool 原创真相源，工具实现零复制。
 */
class QuroToolEngine(private val registry: QuroToolRegistry) {
    private var appContext: Context? = null

    companion object {
        /**
         * 允许并发的工具名白名单（人工审过的保守集）。
         *
         * 收录标准（三条全满足）：
         *  1. 纯读，不写任何持久状态；
         *  2. 不发外部消息、不启停任何东西、不触发授权弹窗；
         *  3. 实现类已显式声明 `override val readOnly = true`（两处都成立才并发）。
         *
         * 🔴 刻意**不收录**的：`browse_files`（大目录遍历抢 IO，会拖慢同批其他读）、
         * `web_search` / `http_request` / `read_url`（网络并发易触发限流）、
         * `list_installed_apps`（包管理查询在部分 ROM 上非线程安全）、
         * `terminal_status`（读的是被其他工具写的共享终端会话状态）。
         * 想要这些并发，先确认线程安全再加进来。
         */
        val CONCURRENT_SAFE: Set<String> = setOf(
            "get_current_time", "get_device_info", "get_battery",
            "get_wifi_info", "get_network_info", "get_sensors",
            "calculate",
            "file_info", "find_files",
            "get_package_name", "get_active_notifications", "get_bluetooth_status",
            "root_status", "shizuku_status",
        )

        /**
         * 一轮里**串行**工具调用的总预算上限。
         *
         * 为什么要有：单个工具默认 60s，模型一次给 6 个写操作 = 最坏 6 分钟无反馈，
         * 用户体感就是「卡死」然后只能点停止。取 3 分钟：够跑几次真实的
         * `pkg install` / 大文件操作，又不会让一轮对话无限期占着。
         *
         * 有工具显式声明了更长的 `execTimeoutMs`（如 harness 的 3600s）时，
         * 本上限对那一轮**不生效**（改按各工具预算之和），否则会把长任务误杀。
         */
        const val MAX_ROUND_BUDGET_MS: Long = 180_000L
    }

    private val droidMcp: DroidMcp = DroidMcp.builder()
        .addTools(registry.all().map { it.toMcpTool(this) })
        .build()

    internal fun getContext(): Context? = appContext

    /** 允许外部（如 MCP HTTP Server）注入 Application Context，使工具可正常执行。 */
    fun setContext(ctx: Context) { appContext = ctx }

    /** 下发给 LLM 的 OpenAI function-calling 工具规格。 */
    fun specs(): List<QuroToolSpec> = registry.specs()

    /** 按 LLM 返回的 tool_calls 逐一执行（经 droid-mcp 引擎派发）。 */
    /** 统一闭环执行器：把"感知-判断-执行-反馈-校验-修正"闭环推广到所有工具调用。 */
    private val closedLoop = ClosedLoopExecutor()

    init {
        // #496：harness（DeepSeek Harness 派活）专属失败策略。
        // 全局 TIMEOUT 现在已是「首档即升级」（见 FailurePolicy.DEFAULT_TABLE —— 超时重试同参数
        // 只会再超时，把「卡」放大成「长时间卡」）。这里仍显式保留 harness 的覆写，原因不同：
        // harness 超时要**如实交还模型**，不能重派（每次重派都新建引擎会话重复派同一个任务，
        // 用户侧看到「活被干了多遍、过程和结果却回不来」）。两条规则独立成立，
        // 别因为全局表改了就把这段删掉，否则将来全局回退时 harness 会静默复活旧行为。
        FailurePolicyRegistry.register(
            ScenarioFailurePolicy(
                scenario = "harness",
                table = DEFAULT_TABLE + mapOf(
                    FailureType.TIMEOUT to listOf(RecoveryAction.ESCALATE),
                    FailureType.TRANSPORT to listOf(RecoveryAction.ESCALATE),
                ),
            ),
        )
    }

    /**
     * 按 LLM 返回的 tool_calls 逐一执行（经闭环执行器派发）。
     *
     * ## 🔴 [onModelCorrect] 通道（此前完全没接，本轮补上）
     * [ClosedLoopExecutor] 的失败策略表里 [RecoveryAction.CORRECT] / [ROLLBACK] 是
     * 「让上层修正后重试」与「不应用本次结果」两档。此前这里**不传 onModelCorrect**，
     * 于是 [ClosedLoopExecutor.handleFailure] 的 CORRECT 分支永远走 else：
     * 「模型修正通道不可用 → 升级」，而 ROLLBACK 与 ESCALATE 返回的又是同一个
     * [ToolOutcome.Failure]。**三档塌成一档，按场景自愈的设计等于没生效**——
     * 实际行为只剩「裸重试到 maxAttempts 就失败」。
     *
     * 现在接上：闭环判定需要修正时，把失败类型 + 可执行的修正方向回灌进工具结果，
     * 模型下一轮能看到「这类错重试没用，该改什么」。
     *
     * ### 刻意不做「自动改参数」
     * 闭环在**引擎侧**无法安全地自动改 LLM 传的参数 —— 猜错字段名会变成更隐蔽的错。
     * 所以这里的修正是**指令式**的（把该改什么告诉模型），由模型重发一次完整调用；
     * 真正落地的是 [QuroToolFeedback.compose] 那套「重试有没有用」的词汇，
     * 它按 [FailureType] 穷尽映射，不做宽泛猜测。
     */
    suspend fun execute(context: Context, calls: List<QuroToolCall>): List<QuroToolResult> {
        appContext = context
        if (calls.size == 1) return listOf(runOne(context, calls[0]))

        // 🔑 只读调用并发，有副作用的调用仍**严格串行**。
        // 此前是 calls.map { dispatch } 全串行：一轮 8 个只读查询（读 3 个文件 +
        // 查 2 个应用状态 + 搜 2 次）要等 8 倍时间，而它们彼此毫无依赖 ——
        // 这是端侧延迟最大的一块无谓浪费（模型一轮经常并发给出十几个调用）。
        //
        // 为什么不全并发：工具里大量是写/发/启停（send_sms、delete_file、
        // launch_app、apk_plugin 装卸）。并发这些会导致重复发送、竞态删除。
        // 所以判据是 [QuroTool.readOnly]，未声明的一律 false（保守）。
        val results = arrayOfNulls<QuroToolResult>(calls.size)
        val serial = mutableListOf<Int>()
        val parallel = mutableListOf<Int>()
        calls.forEachIndexed { i, c ->
            if (isReadOnlyCall(c)) parallel += i else serial += i
        }

        // 有副作用的先按原顺序跑完（顺序必须与模型给出的调用序一致）。
        //
        // 🔴 整轮预算（「调用工具会卡」的直接成因之一）：
        // 串行调用每个默认 60s，模型一次给出 6 个写操作，最坏就是 6 分钟。
        // 全程没有任何异常、进度条也一直在转 —— 用户侧看到的就是「卡住了」。
        // 这里给整轮一个上限：超出的调用**不执行**，直接按超时回喂，模型据此收敛成分步执行。
        // 单个调用不设这层限制（交给它自己的 execTimeoutMs，长跑工具如 harness 声明了 3600s）。
        val roundDeadline =
            if (serial.size <= 1) Long.MAX_VALUE
            else System.currentTimeMillis() + roundBudgetMs(calls, serial)
        for (i in serial) {
            val left = roundDeadline - System.currentTimeMillis()
            results[i] = if (roundDeadline != Long.MAX_VALUE && left <= 0L) {
                QuroToolResult.Failed(
                    "tool_round_budget",
                    QuroToolFeedback.compose(
                        calls[i].name,
                        "本轮工具执行总时长已到上限（${MAX_ROUND_BUDGET_MS / 1000}s），该调用未执行。" +
                            "请分步重试：一次只做一步，做完看结果再继续。",
                    ),
                )
            } else {
                runOne(context, calls[i])
            }
        }
        // 只读的并发跑；协程里 try/catch 兜底，绝不让一个失败带走整批。
        if (parallel.size > 1) {
            coroutineScope {
                parallel.forEach { i ->
                    launch(Dispatchers.IO) {
                        results[i] = runCatching { runOne(context, calls[i]) }
                            .getOrElse { e ->
                                // 🔴 issue #11 问题 8：用户停止不是「某个工具失败」。
                                // 并行分支的 runCatching 同样会吞掉 CancellationException，
                                // 把「已停止」染成一条错误结果回灌 → 原样上抛，让上层短路。
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                QuroToolResult.Failed(
                                    "tool_error",
                                    QuroToolFeedback.compose(calls[i].name, "工具执行异常：${e.message}"),
                                )
                            }
                    }
                }
            }
        } else {
            for (i in parallel) results[i] = runOne(context, calls[i])
        }
        // 🔴 结果必须按调用原序返回：assistant[tool_calls] 与 tool[] 靠下标配对，
        // 顺序错了模型会拿到张冠李戴的结果（与「按序 await 改并发」时最容易踩的坑）。
        return results.map { it ?: QuroToolResult.Failed("tool_no_result", "工具未返回结果") }
    }

    /**
     * 本轮串行调用的总预算（毫秒）。
     *
     * 规则（刻意保守）：
     *  · 只有一个串行调用 → 不受整轮限制，交给它自己的 `execTimeoutMs`；
     *  · 有工具声明了超过默认值的长超时 → 按各工具预算之和，**不封顶**（避免误杀长任务）；
     *  · 其余情况 → 求和后封顶到 [MAX_ROUND_BUDGET_MS]。
     */
    private fun roundBudgetMs(calls: List<QuroToolCall>, serial: List<Int>): Long {
        var sum = 0L
        var anyLong = false
        for (i in serial) {
            val budget = runCatching {
                registry.get(calls[i].name)?.execTimeoutMs(calls[i].arguments)
            }.getOrNull() ?: TOOL_EXEC_TIMEOUT_MS
            if (budget > TOOL_EXEC_TIMEOUT_MS) anyLong = true
            sum += budget
        }
        return if (anyLong) sum else sum.coerceAtMost(MAX_ROUND_BUDGET_MS)
    }

    /**
     * 单个调用走完整闭环。
     * 抽出来是为了让「串行分支」与「并发分支」复用同一段逻辑，避免两条路径行为漂移。
     */
    private suspend fun runOne(context: Context, call: QuroToolCall): QuroToolResult {
        // ── 审批拦截：危险/高副作用工具在执行前挂起等用户拍板 ──
        // 只有「本会话 / 总是允许」之外的调用才真正弹卡；已记住允许的免问。
        // 🔴 通道开关直通（2026-11-04 用户实测：特权已开「允许」还弹审批卡）：
        //    权限面板的 CMS / 特权开关此前只管 CMS broker / 特权管理器那道关卡，
        //    这道审批卡是独立的第二道门 —— 开关=允许时照样弹。现在反向也接上：
        //    工具所属通道开关为 ALLOW → 直接免卡放行；DENY → 直接拒，不弹卡。
        val channel = com.ai.assistance.quro.core.chat.ApprovalPolicy.channelFor(call.name)
        val channelAllows = when (channel) {
            com.ai.assistance.quro.core.chat.ApprovalChannel.CMS ->
                com.ai.assistance.quro.core.policy.QuroPolicyStore.getCms(context) ==
                    com.ai.assistance.quro.core.policy.QuroPolicy.ALLOW
            com.ai.assistance.quro.core.chat.ApprovalChannel.PRIV ->
                com.ai.assistance.quro.core.policy.QuroPolicyStore.getPriv(context) ==
                    com.ai.assistance.quro.core.policy.QuroPolicy.ALLOW
            null -> false
        }
        val channelDenies = when (channel) {
            com.ai.assistance.quro.core.chat.ApprovalChannel.CMS ->
                com.ai.assistance.quro.core.policy.QuroPolicyStore.getCms(context) ==
                    com.ai.assistance.quro.core.policy.QuroPolicy.DENY
            com.ai.assistance.quro.core.chat.ApprovalChannel.PRIV ->
                com.ai.assistance.quro.core.policy.QuroPolicyStore.getPriv(context) ==
                    com.ai.assistance.quro.core.policy.QuroPolicy.DENY
            null -> false
        }
        if (channelDenies) {
            return QuroToolResult.Failed("approval_denied", "权限面板已把该通道设为「禁止」：${ApprovalPolicy.labelFor(call.name)}")
        }
        val declared = registry.get(call.name)?.requiresApproval ?: false
        if (channelAllows.not() &&
            ApprovalPolicy.needsApproval(call.name, call.arguments, declared) &&
            !ApprovalMemory.isAllowed(call.name)
        ) {
            val request = ApprovalRequest(
                id = "approval_${System.currentTimeMillis()}_${call.name.hashCode()}",
                toolName = call.name,
                toolLabel = ApprovalPolicy.labelFor(call.name),
                summary = "即将执行「${ApprovalPolicy.labelFor(call.name)}」",
                reason = null,
                facts = ApprovalPolicy.factsFor(call.name, call.arguments),
                risk = registry.get(call.name)?.risk,
            )
            val denial = ApprovalGate.awaitDecision(request)
            if (denial != null) {
                // 拒绝/超时：短路，工具不执行，把审查说明回喂给模型。
                return QuroToolResult.Failed("approval_denied", denial)
            }
            // 允许：按用户选的粒度记住，后续同类调用免问。
            // （粒度在 ApprovalGate.resolve 时由 UI 传入，这里通过 listener 回传的记忆集合兜底）
        }
        return closedLoop.dispatch(
            context = context,
            scenario = scenarioFor(call),
            name = call.name,
            arguments = call.arguments,
            execOnce = { attempt -> execOnce(call, context, attempt) },
            onModelCorrect = { failure ->
                // 闭环判 CORRECT 时把「该改什么」回灌给模型。
                // PERMISSION 类在策略表里就是 ESCALATE（弹窗/问用户），
                // 让模型自己重试没有意义，因此返回 true 表示「已按可修正处理」。
                correctionHint(call, failure) != null
            },
        ).toToolResult(call.name, call)
    }

    /**
     * 该调用是否可并发。
     *
     * 判据是「工具实现声明了 [QuroTool.readOnly]」**且**「名字在 [CONCURRENT_SAFE] 白名单里」。
     * 双重条件是刻意的冗余：白名单是人工审过的保守集，`readOnly` 是实现者的自述，
     * 两者都成立才并发。新增工具想进白名单必须显式改 [CONCURRENT_SAFE] ——
     * 宁可漏并发（只是慢），不可错并发（重复发送 / 竞态删除）。
     *
     * 技能类（skill__）一律不并发：技能指令由用户自定义，无法保证只读。
     */
    private fun isReadOnlyCall(call: QuroToolCall): Boolean {
        if (call.name.startsWith("skill__")) return false
        if (call.name !in CONCURRENT_SAFE) return false
        return runCatching { registry.get(call.name)?.readOnly == true }.getOrDefault(false)
    }

    /**
     * 修正方向提示：该失败类型下，模型**该改什么**才能成功。
     * 返回 null 表示这条失败无需模型修正（直接升级给用户更合适）。
     *
     * 纯函数、无副作用，可直接单测。
     */
    internal fun correctionHint(
        call: QuroToolCall,
        failure: ToolOutcome.Failure,
    ): String? {
        val kind = QuroToolFeedback.kindOf(failure.failureType)
        return when (kind) {
            ToolFailureKind.INVALID_ARGS ->
                "参数不合法导致失败。重发这次调用时按工具 schema 修正：必填字段不能缺、字段名要与 schema 完全一致、JSON 必须完整（不要截断）。"
            ToolFailureKind.NOT_FOUND ->
                "目标不存在。先用 list/find 类工具确认真实名称或路径，再重发；不要用猜的路径重试。"
            ToolFailureKind.PERMISSION ->
                "缺权限，重试无用。改为告知用户需要开启哪项权限，或改用不依赖该权限的替代方案。"
            ToolFailureKind.ENV_MISSING ->
                "环境不具备该能力（工具未安装/未配置/依赖缺失/设备不支持）。重试无用：先告知用户缺什么，或改用不依赖它的方案。"
            ToolFailureKind.CANCELLED ->
                "调用被取消（用户停止或切换了会话）。**不要重试**，直接按用户当前真实意图重新作答。"
            ToolFailureKind.TIMEOUT ->
                "已超时。缩小请求范围（更小的分页/更短的输出/更窄的路径）后重发，不要原样重试。"
            ToolFailureKind.TRANSPORT ->
                "网络或传输失败。若工具支持重试参数请开启，否则稍后重发；不要连续快速重复同一调用。"
            ToolFailureKind.BUSINESS ->
                "业务条件不满足。重发前先确认前置条件（如文件/应用/权限/服务是否就绪），必要时先执行准备步骤。"
            ToolFailureKind.UNKNOWN ->
                "失败原因未归类。重发前先缩小范围或补一次探测（如列出可用项），确认工具与参数都存在再调用。"
        }.let { "[correct] ${call.name}: $it" }
    }

    /** 把工具名归类到闭环场景键，供 [FailurePolicyRegistry] 查专属策略。 */
    private fun scenarioFor(call: QuroToolCall): String = when {
        call.name.startsWith("skill__") -> "skill"
        call.name.startsWith("plugin__") -> "plugin"
        // #496：harness 派活是长跑任务（默认 600s 预算），超时/传输失败时
        // 绝不能被默认策略原样重跑（每次重跑都会新建引擎会话重复派同一任务，
        // 表现为「活被干了好几遍」）。专属策略见 QuroToolEngine 的 init 块。
        call.name == "harness" -> "harness"
        else -> "tool"
    }

    /**
     * 统一结果 -> 对外 [QuroToolResult]（N4：失败结果带上「类型 + 可执行指令」）。
     *
     * 🔑 这是全仓**唯一**覆盖所有工具失败的咽喉点：任何工具（droid-mcp 派发 / APK 插件 /
     * 技能 / 权限前置检查 / 超时保护）的失败都会先汇聚成 [ToolOutcome.Failure]，再经此处出口。
     * 所以只改这一处，就能让模型在**每一轮**都拿到「这类错重试有没有用、没用该改做什么」，
     * 而不是像以前那样只收到一行原始错误文本、然后本能地原样重试
     * （这正是「连续重复失败」检测被频繁触发的根因）。
     *
     * ⚠️ 成功结果**原样返回**、绝不包装：工具正常输出里可能恰好含 "not found"
     * （例如搜索类工具的「no matches found」），把成功染上失败标记会凭空制造假故障。
     */
    private fun ToolOutcome.toToolResult(
        toolName: String,
        call: QuroToolCall? = null,
    ): QuroToolResult = when (this) {
        is ToolOutcome.Success -> QuroToolResult.Success(raw)
        is ToolOutcome.Failure -> {
            val base = QuroToolFeedback.compose(toolName, message, QuroToolFeedback.kindOf(failureType))
            // 修正方向仅在「闭环确实判定需要模型修正」时附加；
            // PERMISSION 类不附加（重试无用，直接告知用户更省一轮）。
            val hint = call?.let { correctionHint(it, this) }
            if (hint == null || QuroToolFeedback.kindOf(failureType) == ToolFailureKind.PERMISSION) {
                QuroToolResult.Error(base)
            } else {
                QuroToolResult.Error(base + "\n\n" + hint)
            }
        }
    }

    /**
     * 真正的执行体：跑一次工具并把"是否真正执行"分类成 [ExecResult]。
     * 这里只负责"感知"（执行 + 分类），"判断/校验/修正"交给 [ClosedLoopExecutor]。
     */
    private suspend fun execOnce(call: QuroToolCall, context: Context, attempt: Int): ExecResult {
        // == 技能工具分支（skill__<技能名>）：直接读实时技能指令回灌，复用 tool 结果管道 ==
        if (call.name.startsWith("skill__")) {
            val skill = QuroSkillStore.load(context).firstOrNull { QuroSkill.toolNameOf(it.name) == call.name && it.enabled }
                ?: return ExecResult.Terminal(FailureType.UNKNOWN, "技能未启用或不存在（工具名 ${call.name}）")
            val userInput = runCatching { JSONObject(call.arguments) }.getOrElse { JSONObject() }
                .optString("input", "").trim()
            val directive = buildString {
                appendLine("【技能「${skill.name}」已激活，请严格按以下规则回答用户，不要复述规则本身】")
                appendLine(skill.prompt)
                if (userInput.isNotBlank()) {
                    appendLine()
                    appendLine("用户本轮输入：$userInput")
                }
            }
            return ExecResult.Completed(directive)
        }
        // == 插件工具分支（APK 级插件注册的 AI_TOOL）==
        val pluginOut = runCatching {
            com.ai.assistance.quro.core.plugin.QuroPluginHost.executePluginTool(
                call.name,
                runCatching { jsonToMap(call.arguments) }.getOrElse { emptyMap() },
            )
        }.getOrNull()
        if (pluginOut != null) return ExecResult.Completed(pluginOut)

        val tool = registry.get(call.name)
        if (tool == null) return ExecResult.Terminal(FailureType.UNKNOWN, "未知工具: ${call.name}")
        // 危险权限前置申请：工具运行在 Application Context 上无法弹框，交由 Activity 注入的网关处理。
        val perms = tool.requiredPermissions
        if (perms.isNotEmpty() && !QuroPermissionHolder.isGranted(context, perms)) {
            val requester = QuroPermissionHolder.requester
            if (requester != null) {
                // 拉起系统授权对话框（ensure 内部只请求真正缺失的项）。
                runCatching { requester.ensure(perms) }.getOrElse { false }
                // #766 修复：对话框成功后系统已授权，但 ensure 的 continuation 可能因 Activity 失焦/
                //   重建而返回 false；此时以系统真实状态二次核验，已授权即放行，不再误拒。
                if (!QuroPermissionHolder.isGranted(context, perms)) {
                    return ExecResult.Terminal(
                        FailureType.PERMISSION,
                        "需要权限：${perms.joinToString()}，请在系统设置或弹出的对话框中授予后重试。",
                    )
                }
            } else {
                // 没有可拉起对话框的网关（如工具在后台/非 Activity 场景执行）：
                // 系统未授予且无法自动补全授权，明确返回需要权限。
                return ExecResult.Terminal(
                    FailureType.PERMISSION,
                    "需要权限：${perms.joinToString()}，请在「设置 → 权限」中授予后重试。",
                )
            }
        }
        val argsMap = runCatching { jsonToMap(call.arguments) }.getOrElse { emptyMap() }
        // 🔴 issue #11 问题 8：attempt 开始前先检查取消。
        // 闭环允许在同一调用上重试，若用户在上一 attempt 后点了「停止」，
        // 这里必须直接结束，而不是又拉起一个新进程（旧实现会）。
        coroutineContext[Job]?.ensureActive()
        // 工具执行带超时保护（60s）：防止单个工具卡住吃满 180s 总超时。
        // 重试/升级由闭环执行器按失败类型统一决策，这里只负责单次干净执行。
        //
        // 🔴 本轮同时接上 [QuroToolProcesses]（issue #11 问题 8 核心）：
        // withTimeout 内侧建进程登记 Run，工具 spawn 的进程（proot / su / sh -c /
        // python / 抓包…）全部在册；job 取消时（用户停止 / barge-in 切会话 /
        // **本 withTimeout 超时**）在取消方线程上强杀在册进程。
        // 此前取消与超时都只能干等 `Process.waitFor` 自己返回 —— proot 里跑个
        // apt install 一等就是一分多钟，用户点停止毫无反应，根因就在这里。
        // #496：执行预算由工具自己按参数声明（长跑工具如 harness 可到 3600s）。
        // 60s 默认值保持不变，未 override 的工具行为与此前完全一致。
        val execBudgetMs = tool.execTimeoutMs(call.arguments)
        val execResult = runCatching {
            withTimeout(execBudgetMs) {
                val run = QuroToolProcesses.beginRun(coroutineContext[Job])
                try {
                    droidMcp.callTool(call.name, argsMap)
                } finally {
                    QuroToolProcesses.endRun(run)
                }
            }
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.e("QuroToolEngine", "工具「${call.name}」执行超时（${execBudgetMs}ms），已强杀派生进程")
                return ExecResult.Failed(
                    FailureType.TIMEOUT,
                    "工具执行超时（超过${execBudgetMs / 1000}秒），已自动终止。如需执行长时间任务，请分步操作或调大该工具的超时参数。",
                )
            }
            // 🔴 用户停止（父 job 取消）不是「工具失败」：绝不当成错误结果回灌给模型
            // （否则界面会看到一条莫名的「工具执行异常」且下一轮还会跑），原样上抛，
            // 由 QuroAssistant 的「回灌前短路」结束本轮 ReAct。
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("QuroToolEngine", "工具「${call.name}」执行异常: ${e.message}")
            return ExecResult.Failed(FailureType.TRANSPORT, "工具执行异常: ${e.message}")
        }
        return if (execResult.isSuccess) {
            val text = execResult.data?.get("result")?.toString() ?: "OK"
            ExecResult.Completed(text)
        } else {
            ExecResult.Failed(FailureType.BUSINESS, "工具执行失败: ${execResult.errorMessage}")
        }
    }


    /** 导出 MCP 格式工具清单（供协议/桌面客户端）。 */
    fun listToolsMcpJson(): String = droidMcp.listToolsJson()
}
