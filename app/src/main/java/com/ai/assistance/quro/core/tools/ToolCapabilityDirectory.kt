package com.ai.assistance.quro.core.tools
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import com.ai.assistance.quro.core.QuroToolSpec

/**
 * 工具能力目录系统
 * 
 * 解决AI不会主动使用工具的问题：
 * 1. 提供工具分类和快速查询
 * 2. 根据用户意图智能匹配工具
 * 3. 提供工具使用示例和最佳实践
 * 
 * 设计目标：
 * - AI能快速找到"我有什么工具能做这个"
 * - 根据用户意图自动推荐工具
 * - 提供清晰的使用场景和参数说明
 */
object ToolCapabilityDirectory {
    
    /**
     * 工具分类枚举
     */
    enum class ToolCategory(val displayName: String, val description: String) {
        BASIC("基础工具", "时间、设备信息、计算等基础功能"),
        SYSTEM_CONTROL("系统控制", "音量、亮度、WiFi、蓝牙等设备控制"),
        FILE_OPERATION(qstr(R.string.qk_03181), "文件读写、复制、移动、删除等"),
        TERMINAL_LINUX("终端/Linux", "终端命令执行、Linux环境管理"),
        /**
         * Termux 官方宿主（应用内完整移植）。
         *
         * 🔴 必须与 TERMINAL_LINUX 分开，否则 AI 会去选底层的 `terminal` 而不是 `termux`：
         *  - `TERMINAL_LINUX` 里的 `terminal` 是**底层会话驱动**（Termux 的内部实现），面向排查/降级；
         *  - 本类是**用户可感知的「终端」本体**：多个终端会话 + Ubuntu 环境执行/装包。
         * 用户说「终端 / Termux / 命令行」时，AI 必须落到这一类。
         */
        TERMUX("Termux 终端", "Termux 官方宿主：多终端会话 + Ubuntu 环境执行/装包（用户说「终端」首选）"),
        NETWORK_WEB(qstr(R.string.qk_03306), "HTTP请求、浏览器、MCP服务"),
        MEDIA("媒体", "音乐、视频、图片、音频处理"),
        UI_CARDS("UI/卡片", "对话框UI组件、可视化图表"),
        DYNAMIC_UI("动态UI", "quro-ui 原生组件：可交互原生界面、富媒体、可视化渲染"),
        GENUI("生成式UI", "生成式 UI 画布（GenUI）：把「画界面」的活派给整屏画布，以及 Web 应用工程（miniapp，HTML+WebView）与原生小程序工程（miniapp_sdk，WXML/WXSS）——Web 应用不是小程序，两套引擎别混"),
        KNOWLEDGE_MEMORY("知识/记忆", "记忆库、知识库、经验库"),
        WORKSPACE(qstr(R.string.qk_00025), "工作区文件管理"),
        ACCESSIBILITY("无障碍", "屏幕读取、点击、滑动等"),
        /**
         * 后台虚拟屏（移植 Mika）：在**用户看不见的独立虚拟屏**上启动 App、点按、截图。
         *
         * 🔴 必须与 ACCESSIBILITY 分开：
         *  - `ACCESSIBILITY` 操作的是**用户正在看的那块屏**，会抢焦点、打断用户；
         *  - 本类操作的是**后台独立屏**（Shizuku 建 VirtualDisplay），主屏焦点/输入法不受影响，
         *    截图走 Surface→ImageReader 而非无障碍接口。
         * 混在一起会让 AI 在「别打扰用户」的场景下错误地选用无障碍工具。
         */
        VIRTUAL_SCREEN("后台虚拟屏", "在不打扰用户主屏的独立虚拟屏上启动/操作 App、截图"),
        /**
         * 看屏 / 屏幕操作（核心能力全家桶）。
         *
         * 🔴 为什么单列这一类：之前所有「看屏」相关工具被拆进 ACCESSIBILITY / VISUAL_INTERACT /
         * AI_CAPABILITIES / ADB_DEBUG 四五个分类，AI 在「操控虚拟屏 / 读屏 / 截图 / 视觉分析」时
         * 根本不知道自己还有哪些屏相关工具，只能凭记忆瞎用，用户反馈「AI 操控虚拟屏时不知道已有的
         * 全部看屏工具」。这一类把**全部屏感知与屏操作能力**聚到一起，让 tool_router 的
         * `list_tools(category="看屏/屏幕操作")` 一次列全。
         *
         * 与 [VIRTUAL_SCREEN] 的边界：`bg_screen`（后台虚拟屏）**仍单列** [VIRTUAL_SCREEN]，
         * 因为它操作的是用户看不见的独立屏、不抢焦点（见 [ToolTaxonomyTest] 的硬断言）；
         * 本类收的是「看/操作**用户正在看的那块屏**」以及「理解屏幕画面」的能力（screen 的
         * read/tap/input/screenshot 等动作、ui_tree、image_recognition …），
         * 这些是会抢焦点、打断用户的主屏操作。`bg_screen` 通过 [getScreenTools] 全家桶与它们互链，
         * 既保证 AI 操控虚拟屏时能发现全部看屏工具，又不破坏「别去抢用户主屏」的语义边界。
         */
        SCREEN_PERCEPTION("看屏/屏幕操作", "读取/理解/操作屏幕：读屏、截图、点击滑动、UI控件树、视觉分析、图像识别（含后台虚拟屏的全家桶互链）"),
        APP_MANAGEMENT("应用管理", "应用启动、安装、冻结等"),
        COMMUNICATION(qstr(R.string.qk_03313), "短信、联系人、日历等"),
        AI_CAPABILITIES("AI能力", "图像生成、视频生成、文档处理"),
        SECURITY("安全/权限", "Shizuku、ROOT、设备管理员"),
        /**
         * ADB 调试（主机侧 + 被调试侧）。
         *
         * 🔴 必须单列，不能并进 SECURITY（那讲的是本机提权）：
         *  - 本类讲的是 **ADB 协议本身**：本机当主机去调试另一台设备（USB/TCP）、
         *    以及本机作为被调试端（tcpip adbd）、无线调试配对。
         *  - `adb_dev` = 去调试别人；`adb_term` = 本机被调试。两个方向常被搞混。
         */
        ADB_DEBUG("ADB 调试", "本机作 ADB 主机调试另一台设备（USB/TCP）、本机被调试、无线配对"),
        CMS_DEVELOPMENT("CMS开发", "CMS模块、引擎、开发环境管理与部署"),
        AIP_DOC(qstr(R.string.qk_00121), "长文档/PPT/报告/思维导图的 AIP 结构化排版与文档生成"),
        /**
         * zxwidget 动态交互界面。
         *
         * 🔴 必须单列一类，不能并进 UI_CARDS / DYNAMIC_UI：
         *  - `UI_CARDS` 是**气泡内富组件**（ui_card / ui_widget，预制组件名册；🔴 它**不是**用户说的「可视化小卡片」，小卡片是独立的 WIDGET_CARD 分类）；
         *  - `DYNAMIC_UI` 是 `quro-ui` 原生组件；
         *  - 本类是 **zxwidget**：477 个 zx 组件，由 `assets/zx/zxwidget.js` 编译，
         *    **直接渲染在对话框的消息流里**（不弹窗、不进卡片栏），两个对话框都支持它。
         * 三者常被混为一谈，分类上分开才能让 tool_discovery / tool_router 讲清「该用哪个」。
         */
        ZXWIDGET("zxwidget", "zxwidget 动态交互界面：477 个 zx 组件直接编译渲染进对话框消息流（方案对比、数据表、表单、看板、时间线、仪表盘…）"),
        PLUGIN("插件扩展", "APK 级插件框架：装独立 APK 给 AI 加能力（AI 工具 / ACI 能力 / 界面 / 指令）"),
        /**
         * 可视化交互：对话框内的可视化询问 / 弹窗 / 确认
         * （visual_question / visual_action / visual_popup / visual_custom_popup）。
         * 与 UI_CARDS（气泡内富卡片）/ DYNAMIC_UI（quro-ui 原生组件）区分开，
         * 避免 tool_discovery / tool_router 把"该用哪个"讲混（issue #12）。
         */
        VISUAL_INTERACT("可视化交互", "对话框内可视化询问/弹窗/确认：visual_question/visual_action/visual_popup/visual_custom_popup"),
        /**
         * 可视化小卡片 —— 🔴 独立分类（用户拍板 2026-11-05）。
         *
         * 必须单列，不能并进 UI_CARDS / DYNAMIC_UI / VISUAL_INTERACT：
         *  - `UI_CARDS` 是气泡内**富组件**（ui_card / ui_widget 预制组件名册）；
         *  - `DYNAMIC_UI` 是 ```quro-ui 原生可交互组件；
         *  - `VISUAL_INTERACT` 是盖在屏幕上的弹窗/询问；
         *  - 本类是**单块、只读、内联在消息流**的仪表盘小组件（visual_card 自由写 + 样板检索参考）。
         * 历史 bug：visual_card 因 `visual_` 前缀被归进「可视化交互/弹窗」，而 RAG 又把
         * 「小卡片」映射到富组件 ui_card/ui_widget，模型因此永远用别的组件顶替小卡片。
         */
        WIDGET_CARD("可视化小卡片", "独立分类：visual_card / visual_card_skeleton —— AI 自由 HTML 小卡（天气/新闻/日程/行情/时钟…），统一卡壳内联渲染；不是动态UI、不是富组件、不是弹窗"),
        /**
         * 工作流 / 定时自动化。
         *
         * 🔴 必须单列，不能并进 BASIC 或 AI_CAPABILITIES：
         *  - 本类是「按条件/时间自动跑一串动作」的编排能力（wf_*、schedule_task、tasker）；
         *  - 与 `schedule_task` 的区别在于：AGENT 定时是**给 AI 自己排期**，WORKFLOW 是**编排用户的任务流**。
         * 混进 BASIC 后 tool_router 按分类列不出来，模型会以为没有自动化能力。
         */
        WORKFLOW("工作流/自动化", "工作流编排与定时自动化：wf、schedule_task、tasker"),
        /**
         * 应用界面导航（打开设置页 / 面板 / 开关）。
         *
         * 🔴 必须与 UI_CARDS 分开：原来 `ui_` 前缀规则把它们全归进 UI_CARDS，
         * 于是「卡片渲染」这一类的 42 个工具里，30+ 个其实是**打开某个设置页**的导航动作
         * （ui_open_build / ui_open_permission / ui_open_model_config …）。
         * 语义错配的直接后果：模型想画卡片时去看到一堆 ui_open_*，
         * 想打开设置页时又以为要进「卡片」分类 —— 两头都不准。
         */
        APP_NAVIGATION("界面导航", "打开 App 内某个设置页/面板/功能入口，以及界面级开关（ui_open_* / ui_toggle_*）"),
    }
    
    /**
     * 工具信息数据类
     */
    data class ToolInfo(
        val name: String,
        val category: ToolCategory,
        val description: String,
        val useCases: List<String>,      // 使用场景
        val examples: List<String>,      // 调用示例
        val parameters: Map<String, String>, // 参数说明
        val tips: List<String>,          // 使用技巧
        val relatedTools: List<String>,  // 相关工具
        val priority: Int = 1            // 优先级（1-5，5最高）
    )
    
    /**
     * 工具能力目录（增强元数据层）：手写的高质量条目（useCases/examples/tips/relatedTools）。
     * 真实注册表里不在本层的工具，会在 [install] 时用 description 兜底合成，分类走命名推断。
     */

    /**
     * 「看屏 / 屏幕操作」能力全家桶：AI 操控虚拟屏或读屏/截图时，需要一次性知道的全部屏相关工具。
     *
     * 🔴 为什么要有这份清单：用户实测反馈「AI 操控虚拟屏时不知道已有的全部看屏工具」。
     * 这些工具原本散在 ACCESSIBILITY / VISUAL_INTERACT / AI_CAPABILITIES / ADB_DEBUG / VIRTUAL_SCREEN
     * 多个分类，靠分类检索列不全。这里把**用户主屏的屏感知/屏操作**（screen /
     * ui_tree / screen …）、**屏幕画面理解**（screen / image_recognition）、
     * **后台虚拟屏**（bg_screen，本身单列 VIRTUAL_SCREEN）、**ADB 截屏/投屏**（adb_dev / adb_term）
     * 一次性聚齐，供 [getScreenTools] 与 tool_router 的「看屏」别名统一暴露。
     */
    val SCREEN_TOOL_FAMILY: List<String> = listOf(
        // 🔴 2026-11-12 全局对齐：这里只放**真实存在、且真的会下发**的工具名。
        // 旧名单里 read_screen / screenshot / tap_screen / input_text / visual_analysis /
        // get_screen_state / screen_record / take_photo … 已被 screen 吸收降级，
        // 再列它们的名字只会让 getScreenTools 返回一堆目录里根本查不到的条目
        // （模型照着调 ⇒ 落到「未知工具」或旧实现，与工具分类构架自相矛盾）。
        // 屏相关动作现在统一由 screen 的 action 承接。
        "screen", "keyboard",
        // 后台虚拟屏（非 intrusive，单列 VIRTUAL_SCREEN，是这套能力的入口/锚点）
        "bg_screen",
        // 控件树 / 图像识别仍各自独立（没有被合体吸收）
        "ui_tree", "image_recognition",
        // ADB 截屏 / 投屏（屏幕操作的可选通道）。
        // adb_term 已被 terminal 吸收（action=adb），这里只能写 terminal。
        "adb_dev", "terminal",
    )

    /**
     * handbook 里出现过的全部工具名。
     *
     * 暴露给 `UnifiedToolingContractTest` 用：handbook 的键必须是**真实注册的工具名**。
     * 键一旦指向已合体/已退役的旧名，这条手写元数据在 [install] 时会被静默丢弃，
     * 谁也不会发现 —— 之前 33 条条目（占 1/3）就是这么变成死数据的。
     */
    internal val handbookToolNames: Set<String> get() = handbook.keys

    private val handbook = mapOf(
        // ═════════════ 2026-11-12 第一批合体承接者补条目（此前靠 autoInfo 兜底，口语召回弱）═══════════════
        "web" to ToolInfo(
            name = "web",
            category = ToolCategory.NETWORK_WEB,
            description = "联网检索统一入口：action=search 多引擎并发搜索+查询改写+五信号重排（返回带编号引用的事实片段）、read 精读网页正文、search_and_read 搜完直接精读前 N 条",
            useCases = listOf(
                "搜一下最新消息", "查实时资料", "这个事现在怎么样了", "联网确认一下",
                "最新XX是什么", "搜最近的新闻", "查一下价格", "现在多少钱",
                "读一下这个链接", "打开这个网址看内容", "搜完把前几篇读了"
            ),
            examples = listOf(
                "web(action=\"search\", query=\"2026年最新安卓发布\")",
                "web(action=\"read\", url=\"https://example.com/article\")",
                "web(action=\"search_and_read\", query=\"XX评测\", top=3)"
            ),
            parameters = mapOf(
                "action" to "search/read/search_and_read（缺省 search）",
                "query" to "搜索关键词/问题", "url" to "read 的完整网址", "top" to "search_and_read 精读条数"
            ),
            tips = listOf(
                "全程结构化文本流动，不渲染页面（非浏览器套壳）",
                "返回带 [n] 编号可溯源引用；时效性问题必搜",
                "搜完要精读用 search_and_read 一次完成，别拆两次调用"
            ),
            relatedTools = listOf("ai_browser", "http_request", "web_crawler"),
            priority = 5
        ),

        "alarm_clock" to ToolInfo(
            name = "alarm_clock",
            category = ToolCategory.COMMUNICATION,
            description = "应用内真实响铃闹钟统一入口：action=set 设闹钟（支持重复规则）、cancel 取消、list 列出、timer 倒计时提醒（到点真响，不是静默通知）",
            useCases = listOf(
                "设个闹钟", "定个闹钟", "明天七点叫我", "设提醒",
                "取消闹钟", "列出闹钟", "倒计时10分钟提醒我", "到点提醒我"
            ),
            examples = listOf(
                "alarm_clock(action=\"set\", time=\"07:00\", label=\"起床\")",
                "alarm_clock(action=\"timer\", seconds=600, label=\"泡面\")",
                "alarm_clock(action=\"list\")"
            ),
            parameters = mapOf(
                "action" to "set/cancel/list/timer",
                "time" to "HH:mm", "label" to "标签", "seconds" to "timer 的倒计时秒数"
            ),
            tips = listOf(
                "到点真实响铃，区别于 task_schedule（后台任务触发）",
                "重复闹钟带 repeat 参数（daily/weekdays/自定义）"
            ),
            relatedTools = listOf("task_schedule", "write_calendar"),
            priority = 4
        ),

        // ═════════════ 2026-11-12 补：五族合体入口此前**没有书面条目** ═══════════════
        // 这五族（mcp / linux_env / wf / cms / auth_service）第二批合体时就只落到了 autoInfo 兜底：
        // description 直接被当成 useCases，没有口语场景、没有示例、没有 tips，
        // 于是「mcp 接个服务」「跑个流程」「能力模块怎么调」这类最普通的说法全都召不回它们
        // （同一批还缺 RagConcept 概念映射，两处一起补才算真的接上 RAG）。
        "mcp" to ToolInfo(
            name = "mcp",
            category = ToolCategory.NETWORK_WEB,
            description = "MCP 外部工具协议统一入口：servers 列服务器、list_tools 列某服务器的工具、call 调外部工具、capabilities 汇总能力、deploy/undeploy 部署端点、list_local 列本机端点、aci_* 走 ACI 桥",
            useCases = listOf(
                "接个 MCP 服务", "挂个外部工具", "调一下 MCP 的工具",
                "看看有哪些服务器", "这个服务器有哪些工具", "部署一个端点",
                "接上 github 的工具", "接个数据库查一下"
            ),
            examples = listOf(
                "mcp(action=\"servers\")",
                "mcp(action=\"list_tools\", server=\"github\")",
                "mcp(action=\"call\", server=\"github\", tool=\"search_repositories\", arguments={q:\"android\"})"
            ),
            parameters = mapOf(
                "action" to "servers/list_tools/call/capabilities/deploy/undeploy/list_local/aci_list/aci_call/aci_bridge",
                "server" to "服务器别名或地址", "tool" to "要调的外部工具名",
                "arguments" to "传给外部工具的参数对象", "name" to "部署/卸载的端点名"
            ),
            tips = listOf(
                "调外部工具前先 list_tools 拿准确工具名与参数，别凭印象猜",
                "带 tool 或 arguments 时默认按 call 处理，其余默认 list_tools",
                "MCP 与 ACI 分工：MCP 是外部进程/协议，ACI 是同机第三方 App 的 AIDL 能力"
            ),
            relatedTools = listOf("aci", "http_request"),
            priority = 4
        ),

        "linux_env" to ToolInfo(
            name = "linux_env",
            category = ToolCategory.TERMINAL_LINUX,
            description = "应用内 Linux 环境（proot + Ubuntu 24.04）统一入口：start 初始化、status 就绪状态、stop 终止会话、run 执行 shell 命令、install 安装环境、pkg apt 包管理、process 查残留进程",
            useCases = listOf(
                "在 Linux 里跑个命令", "跑个 Linux 命令", "装个 Ubuntu 环境",
                "apt 装个包", "Linux 环境好了吗", "跑个 python 脚本",
                "清理一下后台残留进程"
            ),
            examples = listOf(
                "linux_env(action=\"status\")",
                "linux_env(action=\"run\", command=\"apt update\")",
                "linux_env(action=\"pkg\", package=\"python3-pip\")",
                "linux_env(action=\"run\", command=\"python3 script.py\")"
            ),
            parameters = mapOf(
                "action" to "start/status/stop/run/install/pkg/process（缺省 status）",
                "command" to "run 的 shell 命令", "package" to "install/pkg 的包名"
            ),
            tips = listOf(
                "这是 proot Ubuntu，与 Termux 官方 bootstrap（termux 工具）不是同一套环境",
                "首次使用先 action=install 装环境，再 run；环境没装好时命令会「找不到」",
                "标准 Ubuntu 布局（/root、/etc/apt）才用它；只要 pkg/PREFIX 语义用 termux"
            ),
            relatedTools = listOf("terminal", "termux", "shizuku"),
            priority = 4
        ),

        "wf" to ToolInfo(
            name = "wf",
            category = ToolCategory.WORKFLOW,
            description = "工作流引擎统一入口：create 创建工作流（给 spec）、trigger 点燃运行（给 id）、status 查询运行状态（给 id）",
            useCases = listOf(
                "做个工作流", "编排一套流程", "按步骤自动跑",
                "把这几步串起来执行", "刚才那个流程跑完了吗", "再跑一遍那个流程"
            ),
            examples = listOf(
                "wf(action=\"create\", spec={...})",
                "wf(action=\"trigger\", id=\"wf_123\")",
                "wf(action=\"status\", id=\"wf_123\")"
            ),
            parameters = mapOf(
                "action" to "create/trigger/status（缺省 status）",
                "id" to "工作流 id（trigger/status 必填）", "spec" to "create 的工作流定义对象"
            ),
            tips = listOf(
                "create 返回 id 后要留住，trigger / status 都靠它",
                "与 alarm_clock / task_schedule 区别：这是「一串动作的编排」，不是单点提醒"
            ),
            relatedTools = listOf("cms", "terminal"),
            priority = 3
        ),

        "cms" to ToolInfo(
            name = "cms",
            category = ToolCategory.CMS_DEVELOPMENT,
            description = "能力模块（CMS）统一入口：list 列模块、call 调用模块、status 状态、logs 日志、result 结果、run_dag 按依赖跑一组命令、deploy/undeploy 部署卸载终端模块、toolbox 工具箱、engine_status 引擎状态、priv_status 特权通道自查",
            useCases = listOf(
                "有哪些能力模块", "跑一下那个模块", "调个模块看看结果",
                "看模块日志", "按依赖跑一组命令", "部署终端模块",
                "引擎状态怎么样", "特权通道通不通"
            ),
            examples = listOf(
                "cms(action=\"list\")",
                "cms(action=\"call\", name=\"module_x\", arguments={...})",
                "cms(action=\"run_dag\", dag=[{...},{...}])",
                "cms(action=\"logs\", name=\"module_x\")"
            ),
            parameters = mapOf(
                "action" to "list/call/status/logs/result/run_dag/deploy/undeploy/toolbox/engine_status/priv_status",
                "name" to "模块名", "arguments" to "call 传给模块的参数对象",
                "dag" to "run_dag 的依赖节点数组"
            ),
            tips = listOf(
                "先 list 拿模块名，再 call；盲猜模块名必失败",
                "跑完看结果用 result（结构化产物）而不是 logs（文本日志）",
                "需要「先自查权限再跑」用 priv_status，别直接撞权限错误"
            ),
            relatedTools = listOf("wf", "terminal", "permissions"),
            priority = 3
        ),

        "auth_service" to ToolInfo(
            name = "auth_service",
            category = ToolCategory.SECURITY,
            description = "第三方服务授权保险库统一入口：add 新增凭证、list 列举、remove 移除；给需要 token / api key 的外部服务提供统一存放，不在会话里明文裸奔",
            useCases = listOf(
                "存个 api key", "把这个 token 存起来", "看看存了哪些授权",
                "删掉那个凭证", "接第三方服务要授权"
            ),
            examples = listOf(
                "auth_service(action=\"list\")",
                "auth_service(action=\"add\", name=\"github\", credential=\"ghp_...\")",
                "auth_service(action=\"remove\", name=\"github\")"
            ),
            parameters = mapOf(
                "action" to "add/list/remove（缺省 list）",
                "name" to "服务名", "credential" to "add 的凭证内容（token / api key）"
            ),
            tips = listOf(
                "用户说「接个需要登录的服务」时先 list 看有没有存过，别重复问用户要 token",
                "凭证只在调用时取用，不要在回复里回显明文"
            ),
            relatedTools = listOf("mcp", "http_request", "permissions"),
            priority = 3
        ),

        // ═════════════ 2026-11-11 第二批 8 族合体入口（web/mcp/wf/cms/auth_service/linux_env 另见对应段）═══════════════
        "screen" to ToolInfo(
            name = "screen",
            category = ToolCategory.SCREEN_PERCEPTION,
            description = "屏幕感知与操控统一入口：读控件树、截图、视觉分析、前台应用、点/长按/滑/输入/滚/全局动作、录屏、拍照，以及 read_and_tap / tap_and_read / capture_and_analyze 组合动作",
            useCases = listOf(
                "看看屏幕", "读屏", "当前界面是什么", "屏幕内容",
                "点击", "点一下", "戳一下", "点屏幕上XX",
                "输入文字", "打字", "填进去", "滑动", "往上翻", "返回", "回桌面",
                "截个图", "截屏", "屏幕截图", "录屏", "拍照",
                "这屏幕上是什么", "用视觉看看这个界面", "读一下再点", "点完再看"
            ),
            examples = listOf(
                "screen(action=\"read\")",
                "screen(action=\"tap\", text=\"确定\")",
                "screen(action=\"read_and_tap\", text=\"登录\")",
                "screen(action=\"capture_and_analyze\", question=\"这个页面哪里能下单？\")"
            ),
            parameters = mapOf(
                "action" to "read/foreground/state/screenshot/analyze/photo/authorize/tap/long_press/swipe/input/scroll/global/record_start/record_stop/read_and_tap/tap_and_read/capture_and_analyze",
                "text" to "tap 按文字找控件 / input 要输入的文本", "x" to "tap 坐标", "y" to "tap 坐标",
                "question" to "analyze 的视觉提问", "direction" to "swipe/scroll 方向", "op" to "global 动作: back/home/recents/..."
            ),
            tips = listOf(
                "需无障碍服务已授权；操作的是用户正在看的这块屏（后台屏用 bg_screen）",
                "组合动作一次调用完成「读再点/点再读/截并析」，别拆成多次",
                "打字进输入框用 screen(action=input)；发消息场景用 keyboard(action=type_and_send)",
                "动手前先 screen(action=state) 确认亮屏未锁屏 —— 灭屏/锁屏时点击与读屏都会失败，先 wake_device",
                "操屏前可用 screen(action=foreground) 确认目标 App 已在前台",
                "截图走 screen(action=screenshot)，需要 base64 直喂视觉模型时用 screenshot_base64",
                "录屏 screen(action=record_start) 需用户先授权录制屏幕；录完用 record_stop 取回文件",
                "screen(action=photo) 拍的是真实世界，不是屏幕；要截屏幕用 screenshot",
                "回桌面/最近任务/通知栏这类系统级动作走 screen(action=global)，比在单个 App 里点更稳",
                "读到的 ref 优先于坐标；界面一变旧 ref 立即失效，每次操作前重新 read"
            ),
            relatedTools = listOf("keyboard", "bg_screen", "permissions"),
            priority = 4
        ),

        "keyboard" to ToolInfo(
            name = "keyboard",
            category = ToolCategory.SCREEN_PERCEPTION,
            description = "ZorvAI 智能体键盘（Agent IME）统一入口：把文本打进当前聚焦输入框、按回车、按发送；type_and_send / type_and_enter 一次调用完成",
            useCases = listOf(
                "发消息", "把这段话发出去", "帮我回复", "打字发给他",
                "在输入框里填", "按回车", "发送"
            ),
            examples = listOf(
                "keyboard(action=\"type_and_send\", text=\"好的，稍等\")",
                "keyboard(action=\"type\", text=\"你好\")"
            ),
            parameters = mapOf(
                "action" to "type/enter/send/type_and_send/type_and_enter",
                "text" to "type 系列动作要输入的文本"
            ),
            tips = listOf(
                "需启用并切换到『Zorv AI 键盘』输入法",
                "日常发消息用 type_and_send，不要拆成 type + send 两次"
            ),
            relatedTools = listOf("screen"),
            priority = 3
        ),

        "permissions" to ToolInfo(
            name = "permissions",
            category = ToolCategory.SYSTEM_CONTROL,
            description = "手机权限与系统策略统一入口：CMS 权限模式自查、设备管理员状态、锁屏、摄像头禁用/恢复、手电开关、系统设置（音量/亮度/WiFi/蓝牙/通知/飞行/旋转）、特权执行（Shizuku→ROOT 自动降级）与 check_and_exec 组合",
            useCases = listOf(
                "查一下权限", "权限模式是什么", "这个操作需要什么权限",
                "锁屏", "把屏幕锁了", "关摄像头", "开手电", "关手电",
                "调音量", "亮度调高", "开WiFi", "开蓝牙", "开飞行模式", "自动旋转",
                "特权执行这条命令", "以系统权限跑"
            ),
            examples = listOf(
                "permissions(action=\"check\")",
                "permissions(action=\"check_and_exec\", command=\"settings put global airplane_mode_on 1\")",
                "permissions(action=\"policy\", setting=\"volume\", op=\"up\")"
            ),
            parameters = mapOf(
                "action" to "check/status/lock/camera/flashlight/policy/exec/run_status/check_and_exec",
                "setting" to "volume/brightness/wifi/bluetooth/notification/airplane/rotation/flashlight",
                "op" to "up/down/on/off/toggle/set/...", "command" to "exec/check_and_exec 的 shell 命令"
            ),
            tips = listOf(
                "高风险动作前先 check（CMS 权限模式自查）再动手——check_and_exec 一次完成",
                "exec 自动走 Shizuku→ROOT 降级链，不用自己挑通道"
            ),
            relatedTools = listOf("shizuku", "root", "screen"),
            priority = 3
        ),

        "lsposed" to ToolInfo(
            name = "lsposed",
            category = ToolCategory.SYSTEM_CONTROL,
            description = "系统增强模块（LSPosed / Vector，API 102）统一入口：框架通信与真实作用域、哪些进程确实加载了 Hook、系统助手接管 / 一圈即搜 / 无障碍保护三个主开关、长按电源键目标，以及接管入口可用的 Zorv AI 全能力清单与接管记录",
            useCases = listOf(
                "小布 / 超级小爱接管", "一圈即搜开不起来", "无障碍服务老是被系统关掉",
                "长按电源键唤醒谁", "框架连上了吗", "模块作用域配了吗",
                "接管到底有没有生效", "现在跑的是哪个模型和工具",
            ),
            examples = listOf(
                "lsposed(action=\"status\")",
                "lsposed(action=\"enable\", capability=\"hook_vendor_assistant_takeover\")",
                "lsposed(action=\"set_power_target\", target=\"quro\")",
                "lsposed(action=\"capabilities\")",
                "lsposed(action=\"inbox\", limit=5)",
            ),
            parameters = mapOf(
                "action" to "status/scope/enable/disable/set_power_target/request_scope/capabilities/inbox",
                "capability" to "enable|disable 必填，键名取自 status 的 capabilities",
                "target" to "set_power_target 必填：vendor/gemini/quro",
                "limit" to "inbox 可选，默认 10，上限 30",
            ),
            tips = listOf(
                "服务连接 ≠ 模块已启用：要看 scope 里目标进程是否真的加载了 Hook",
                "接管开关写在 RemotePreferences 里，切换后下一次触发即生效，不用重启目标进程",
                "接管执行的是完整运行时（工具 / Skills / MCP / 记忆 / Soul），不是裸模型转发",
                "开关改了没反应，先用 inbox 看最近一次接管是成功还是回落到了厂商原生",
            ),
            relatedTools = listOf("permissions", "screen", "root", "shizuku"),
            priority = 3
        ),

        "memory" to ToolInfo(
            name = "memory",
            category = ToolCategory.KNOWLEDGE_MEMORY,
            description = "记忆与知识库统一入口：长期记忆（save/list/search/delete）、经验笔记（log/query/correct/version）、本地知识库（kb_*）、本地语义检索（rag*/discover）、私有库只读（db_*）、recall 组合查询",
            useCases = listOf(
                "记住这个", "记一下", "别忘了", "存记忆",
                "之前说的什么", "想不起来", "查记忆", "回忆一下",
                "记个经验", "这条经验不对，改一下",
                "知识库里查一下", "语义检索", "按这句话找该用的工具"
            ),
            examples = listOf(
                "memory(action=\"save\", content=\"用户的时区是 UTC+8\")",
                "memory(action=\"recall\", query=\"上次说的部署步骤\")",
                "memory(action=\"discover\", query=\"怎么生成海报\")"
            ),
            parameters = mapOf(
                "action" to "save/list/search/delete/log/query/correct/version/kb_add/kb_search/kb_list/kb_delete/rag/rag_index/rag_count/discover/db_list/db_tables/db_schema/db_query/recall",
                "content" to "save 的内容", "query" to "search/recall/rag 的查询词"
            ),
            tips = listOf(
                "回答前需要回忆时用 recall 一次查长期记忆+经验，不必分两次",
                "「记住XX」直接 save；带 content 自动按 save、带 query 自动按 recall",
                "search 只查记忆库、kb_search 才查知识库文档 —— 「之前说过」类问题必须先查 search，不许凭空编",
                "list 只返回元信息与摘要，要正文用 search；不确定有哪些分组就不带参数直接 list",
                "delete 不可撤销，先 list 看清要删什么、并向用户确认",
                "「按一句话找该用哪个工具」用 discover，别用 search"
            ),
            relatedTools = listOf("skill", "memory"),
            priority = 3
        ),

        "aci" to ToolInfo(
            name = "aci",
            category = ToolCategory.APP_MANAGEMENT,
            description = "ACI 跨应用能力统一入口（本地 AIDL，免 root/免 Shizuku/免无障碍）：list 列第三方 App 能力、call 调用、list_and_call 一次完成、server_* 管理本地 HTTP 端点",
            useCases = listOf(
                "让别的App帮忙", "用XX应用的功能", "跨应用调用", "看看其他App能干什么",
                "打开真实网页", "第三方能力", "发消息给那个应用"
            ),
            examples = listOf(
                "aci(action=\"list\")",
                "aci(action=\"call\", capability=\"browser_open\", args={url:\"https://example.com\"})",
                "aci(action=\"list_and_call\", capability=\"browser_open\", args={url:\"https://example.com\"})"
            ),
            parameters = mapOf(
                "action" to "list/call/list_and_call/server_status/server_start/server_stop/server_add/server_remove",
                "capability" to "call 的能力 id（**纯字符串**，如 \"browser_open\"；不要传对象）",
                "args" to "call 的能力参数对象", "target_package" to "目标 App 包名（可省略用默认应用）"
            ),
            tips = listOf(
                "call 返回 503 是服务未绑，框架自动重绑后直接重试一次即可，不要改用系统权限工具诊断",
                "免 root 免 Shizuku 免无障碍，优先于特权通道考虑",
                "/aci/* 本地 HTTP 端点（默认 8848）已真实桥接 AIDL，外部进程可用"
            ),
            relatedTools = listOf("mcp", "launch_app"),
            priority = 3
        ),

        "shizuku" to ToolInfo(
            name = "shizuku",
            category = ToolCategory.SECURITY,
            description = "Shizuku 特权通道统一入口（ADB 级 shell，免 root，需用户手动授权）：status 探活、exec 执行命令、root_exec 动作以 root 执行、freeze/unfreeze 冻结解冻应用、install 静默安装、status_and_exec 一次完成",
            useCases = listOf(
                "用 Shizuku 执行", "ADB 级权限跑这条命令", "冻结这个应用", "解冻应用", "静默安装",
                "查 Shizuku 状态", "先探活再执行"
            ),
            examples = listOf(
                "shizuku(action=\"status\")",
                "shizuku(action=\"exec\", command=\"pm list packages -d\")",
                "shizuku(action=\"freeze\", package_name=\"com.example\")"
            ),
            parameters = mapOf(
                "action" to "status/grant/exec/root_exec/freeze/unfreeze/install/status_and_exec",
                "command" to "exec / root_exec 的 shell 命令", "package_name" to "freeze/unfreeze 的包名",
                "apk_path" to "install 的 APK 路径"
            ),
            tips = listOf(
                "真实系统权限，没有明确目的不要试探；授权必须用户手动点",
                "先探活再执行用 status_and_exec，不要拆两次"
            ),
            relatedTools = listOf("root", "permissions", "terminal"),
            priority = 3
        ),

        "root" to ToolInfo(
            name = "root",
            category = ToolCategory.SECURITY,
            description = "ROOT 特权通道统一入口（最高风险，需设备已 Root）：status 检测 ROOT 类型、exec 执行命令、status_and_exec 一次完成",
            useCases = listOf(
                "用 root 执行", "以 root 跑命令", "检测 root", "ROOT 通道执行"
            ),
            examples = listOf(
                "root(action=\"status\")",
                "root(action=\"exec\", command=\"id\")"
            ),
            parameters = mapOf(
                "action" to "status/exec/status_and_exec", "command" to "exec 的 shell 命令"
            ),
            tips = listOf("最高风险通道，能用 shizuku/aci 解决就不要动 root"),
            relatedTools = listOf("shizuku", "permissions"),
            priority = 4
        ),

        // ═══════════════ Web 应用工程（HTML + Page() + native.* 桥）═══════════════
        "miniapp" to ToolInfo(
            name = "miniapp",
            category = ToolCategory.GENUI,
            description = "Web 应用：创建/写入/读取/运行 Web 应用工程（app.json 多页面路由 + HTML + Page() 运行时 + native.* 原生桥），产物就地渲染成对话内 Web 应用卡；也支持 save（整段 HTML 存成工程）、wrap（js/python/css 包装成可渲染页面）",
            useCases = listOf(
                "做个 Web 应用", "做个网页应用", "做个网站", "做个网页工具", "做个 HTML 应用", "做个轻应用", "做个应用",
                "在这儿做个 Web 应用", "建一个 Web 应用工程", "要能存数据的 Web 应用",
                "要能调本地能力的 Web 应用", "Web 应用怎么跑起来", "改一下这个 Web 应用", "Web 应用手册",
                "把这个页面存成 Web 应用", "写个 Python 小工具跑起来", "导入一个 HTML 应用"
            ),
            examples = listOf(
                "miniapp(action=\"manual\")",
                "miniapp(action=\"manual\", topic=\"studio\")",
                "miniapp(action=\"create\", name=\"todo\", files=[{\"path\":\"app.json\",\"content\":\"{\\\"pages\\\":[\\\"pages/index/index\\\"]}\"},{\"path\":\"pages/index/index.html\",\"content\":\"<!DOCTYPE html>…\"}])",
                "miniapp(action=\"run\", name=\"todo\", entry=\"pages/index/index.html\")",
                "miniapp(action=\"list\")"
            ),
            parameters = mapOf(
                "action" to "create/write/read/list/delete/run/manual",
                "name" to "工程名（create/write/read/delete/run 必填）",
                "files" to "文件数组 [{path, content}]（create）",
                "path" to "相对工程根的文件路径（write/read/删除单文件）",
                "entry" to "入口页面（run，默认 pages/index/index.html）",
                "topic" to "manual 主题：compare/studio/native/errors"
            ),
            tips = listOf(
                "用户说「做个 Web 应用 / 网页应用 / 网站 / 网页工具 / 轻应用 / 做个应用」→ **就用它**，ZorvAI 自己会写 Web 应用。",
                "🔴 **Web 应用不是小程序**：本工具 = HTML + WebView（bridge.js 运行时）。用户说「Web 应用」时**严禁**改用 miniapp_sdk（那是 WXML/WXSS 原生小程序引擎，只在用户明确要微信小程序时才用）。",
                "**create 之后必须再 run 一次**，否则界面根本没交付（用户什么都看不到）。",
                "run 的结果会自动内嵌进对话流渲染；**run 完不要再自己写一份 HTML**（那是第二块画布）。",
                "动手前先读手册：miniapp(action=\"manual\") 或 topic=\"studio\""
                    + "（工程结构 / Page() 运行时 / native.* 全函数 / 错误清单）。"
                    + "老 topic native / traps / errors / compare 已随旧 GenUI 画布下线，只回下线说明。",
                "要**原生能力**（存数据/跑SQL/加密/通知/分享/定位/拉起App）→ 本工具（native.* 桥）；"
                    + "只要界面和交互、不要系统能力 → 直接写 ```html 围栏更快；"
                    + "要**原生控件**的可交互界面 → genui_agent_open。",
                "本轮只交付一件：本工具 run 完就收尾，不要再补 HTML 或再调别的画型工具。"
            ),
            relatedTools = listOf("genui_agent_open", "workspace", "ui_widget"),
            priority = 4
        ),

        // ═══════════════ 原生小程序引擎（WXML/WXSS，与 Web 应用是两套东西）═══════════════
        // 2026-11-06 补录：此前 handbook 里没有 miniapp_sdk 条目，tool_router 检索「小程序」
        // 永远命不中它；而 miniapp（Web 应用）反而因 RagConcept 映射吃到「小程序」触发词，
        // 结果用户说「小程序」全被推到 WebView 版、说「Web 应用」也偶尔被带偏到小程序。
        "miniapp_sdk" to ToolInfo(
            name = "miniapp_sdk",
            category = ToolCategory.GENUI,
            description = "原生小程序引擎：AI 直接创建/列出/删除 WXML + WXSS + JS 小程序工程（微信标准范式，自研 C++ 原生引擎渲染，不经过 WebView）。**仅当用户明确要「小程序（原生引擎）/ 微信小程序 / 原生小程序 / WXML」时才用**；用户说「Web 应用 / 网页应用 / 网站」时必须用 miniapp，严禁用本工具",
            useCases = listOf(
                "做个微信小程序", "写个原生小程序", "用 WXML 写个小程序", "小程序（原生引擎）",
                "列一下有哪些小程序", "删掉这个小程序工程"
            ),
            examples = listOf(
                "miniapp_sdk(action=\"list\")",
                "miniapp_sdk(action=\"create\", appId=\"demo\", files={\"app.json\":\"{\\\"pages\\\":[\\\"pages/index/index\\\"]}\", \"pages/index/index.wxml\":\"<view>…</view>\", \"pages/index/index.wxss\":\"…\", \"pages/index/index.js\":\"Page({…})\"})",
                "miniapp_sdk(action=\"delete\", appId=\"demo\")"
            ),
            parameters = mapOf(
                "action" to "list/create/delete",
                "appId" to "小程序 id（create/delete 必填）",
                "files" to "文件映射（create）：相对路径 -> 内容，或数组 [{path,content}]",
                "srcDir" to "（files 备选）工作区内已写好的工程目录，create 时整体拷入"
            ),
            tips = listOf(
                "🔴 **触发边界（最重要）**：用户说「小程序（原生引擎）/ 原生小程序 / 微信小程序 / 用 WXML/WXSS 写个小程序」→ 用它；用户说「Web 应用 / 网页应用 / 网站 / 网页工具」→ **严禁用它**，那是 miniapp（HTML + WebView）的活。",
                "WXML/WXSS/JS 走微信范式，但引擎能力远小于真微信：只支持列出的 wx.* 接口与 ES5 语法，详见工具 description。",
                "创建后会自动渲染在对话框气泡（原生引擎），也可在工具中心「小程序（原生引擎）」面板打开。"
            ),
            relatedTools = listOf("miniapp", "genui_agent_open"),
            priority = 4
        ),

        // ═══════════════ GenUI Agent（内置独立应用：GenUI JSON DSL → 原生 Compose）═══════════════
        "genui_agent_open" to ToolInfo(
            name = "genui_agent_open",
            category = ToolCategory.GENUI,
            description = "拉起内置的 GenUI Agent（生成式界面智能体，独立全屏应用）：GenUI JSON DSL → 原生 Compose 组件（530+），可点、可填、可改",
            useCases = listOf(
                "要原生控件的界面", "做个可交互的原生界面", "打开 GenUI Agent", "用 GenUI 做一个界面",
                "来做个小应用", "要能填表单的界面", "整屏打开一个原生界面"
            ),
            examples = listOf(
                "genui_agent_open(prompt=\"做一个 BMI 计算器：身高体重输入 + 实时结果 + 分级色带\")",
                "genui_agent_open(prompt=\"做一个季度销售看板：指标卡 + 柱状图 + 明细表\")",
                "genui_agent_open()"
            ),
            parameters = mapOf(
                "prompt" to "可选：进去就执行的界面需求（**必须自包含**：GenUI Agent 看不到本对话历史）。留空只打开界面"
            ),
            tips = listOf(
                "交付的是**原生控件**（不是网页、不是 HTML），这是它和 Web 应用的本质区别。",
                "它是**独立全屏应用**，有自己的对话页/历史/宠物与右侧抽屉；模型配置、人格灵魂、工具集全部沿用 ZorvAI 主设置。",
                "用户说「Web 应用 / 网页 / HTML」→ 用 `miniapp` 或 ```html 围栏，不要开它；用户明确要「微信小程序」→ 用 miniapp_sdk，也不要开它。",
                "prompt 要写清「做什么 + 要哪些功能 + 有哪些数据」，写成「做那个」等于没写。",
                "调用后本轮立即收尾，不要再补 HTML 或调别的画型工具。"
            ),
            relatedTools = listOf("miniapp", "ui_widget"),
            priority = 4
        ),

        // ═══════════════ 插件扩展（APK 级插件框架总控）═══════════════
        "apk_plugin" to ToolInfo(
            name = "apk_plugin",
            category = ToolCategory.PLUGIN,
            description = qstr(R.string.qk_03554),
            useCases = listOf(
                "装了哪些插件", "插件能干什么", "帮我装个插件", "把这个插件 APK 装上",
                "卸载某插件", "重载插件（改完插件让它生效）", "打开插件界面", "用某个插件的能力查一下",
                "插件有哪些 AI 工具", "插件框架什么状态"
            ),
            examples = listOf(
                "apk_plugin(action=\"status\")",
                "apk_plugin(action=\"list\")",
                "apk_plugin(action=\"tools\")",
                "apk_plugin(action=\"install_builtin\")",
                "apk_plugin(action=\"call\", name=\"unit_convert\", args=\"{\\\"value\\\":1,\\\"from\\\":\\\"km\\\",\\\"to\\\":\\\"m\\\"}\")",
                "apk_plugin(action=\"open\", surface_id=\"zorvweb_browser\")"
            ),
            parameters = mapOf(
                "action" to "status/list/info/tools/surfaces/open/install/install_builtin/uninstall/reload/call",
                "plugin_id" to "插件包名（info / uninstall / reload）",
                "surface_id" to "插件界面 id（open）",
                "path" to "插件 APK 绝对路径（install）",
                "name" to "插件 AI 工具名（call）",
                "args" to "call 的参数 JSON 对象字符串"
            ),
            tips = listOf(
                "这是插件框架的**唯一**入口，所有插件相关操作都走它，不要自己猜插件工具名。",
                "插件贡献的 AI 工具通常已直接在你的工具集里；若没生效，用 action=call 兜底调用。",
                "不确定插件有什么能力时：先 action=list 看插件，再 action=tools 看工具。",
                "插件桌面（启动器式管理界面）用 ui_control(action=「ui_open_plugins」) 打开。"
            ),
            relatedTools = listOf("ui_control", "build_apk"),
            priority = 4
        ),

        // ═══════════════ 基础工具 ═══════════════
        "get_current_time" to ToolInfo(
            name = "get_current_time",
            category = ToolCategory.BASIC,
            description = qstr(R.string.qk_03698),
            useCases = listOf("现在几点", "今天星期几", "现在什么日期", "距离某天还有多久", "现在是上午还是下午"),
            examples = listOf("get_current_time()"),
            parameters = emptyMap(),
            tips = listOf("不需要参数，直接调用", "返回格式：yyyy-MM-dd HH:mm:ss"),
            relatedTools = listOf("get_device_info"),
            priority = 5
        ),
        
        "get_device_info" to ToolInfo(
            name = "get_device_info",
            category = ToolCategory.BASIC,
            description = qstr(R.string.qk_03552),
            useCases = listOf("我手机什么型号", "系统版本多少", "内存/存储还剩多少", "处理器是什么"),
            examples = listOf("get_device_info()"),
            parameters = emptyMap(),
            tips = listOf("不需要参数，直接调用", "返回设备型号、品牌、Android版本、SDK版本"),
            relatedTools = listOf("get_battery", "get_wifi_info"),
            priority = 4
        ),
        
        "calculate" to ToolInfo(
            name = "calculate",
            category = ToolCategory.BASIC,
            description = qstr(R.string.qk_03682),
            useCases = listOf("算一下1+2*3", "帮我算个数", "计算百分比", "单位换算"),
            examples = listOf("calculate(expr=\"1+2*3\")", "calculate(expr=\"(100-20)/5\")"),
            parameters = mapOf("expr" to "算术表达式，支持+ - * /和括号"),
            tips = listOf("支持四则运算和括号", "不要心算瞎猜，用工具算"),
            relatedTools = listOf("run_code"),
            priority = 5
        ),
        
        // ═══════════════ 系统控制 ═══════════════
        
        
        // ═══════════════ 文件操作 ═══════════════
        "file_write" to ToolInfo(
            name = "file_write",
            category = ToolCategory.FILE_OPERATION,
            description = qstr(R.string.qk_03419),
            useCases = listOf("帮我建个文件", "把这段存成txt", "生成配置文件", "写代码到文件"),
            examples = listOf("write_file(path=\"/sdcard/test.txt\", content=\"Hello World\")"),
            parameters = mapOf("path" to qstr(R.string.qk_01054), "content" to "文件内容"),
            tips = listOf("会自动创建缺失的父目录", "路径用绝对路径"),
            relatedTools = listOf("file_read", "workspace"),
            priority = 4
        ),
        
        "file_read" to ToolInfo(
            name = "file_read",
            category = ToolCategory.FILE_OPERATION,
            description = qstr(R.string.qk_03741),
            useCases = listOf("读一下这个txt", "打开那个日志看看", "把md内容念给我"),
            examples = listOf("read_text_file(path=\"/sdcard/test.txt\")"),
            parameters = mapOf("path" to qstr(R.string.qk_01054)),
            tips = listOf("只能读文本文件", "大文件会截断"),
            relatedTools = listOf("file_write", "file_read"),
            priority = 4
        ),
        
        "list_dir" to ToolInfo(
            name = "list_dir",
            category = ToolCategory.FILE_OPERATION,
            description = qstr(R.string.qk_03629),
            useCases = listOf("看看Download里有什么", "列一下这个文件夹", "目录里有哪些文件"),
            examples = listOf("list_files(path=\"/sdcard/Download\")"),
            parameters = mapOf("path" to "目录路径"),
            tips = listOf("默认列出当前目录", "支持通配符"),
            relatedTools = listOf("file_info", "file_read"),
            priority = 4
        ),
        
        // ═══════════════ 终端/Linux ═══════════════
        // 🔴 2026-11-12：这里原来有一条 `terminal_run` 条目。terminal_run / terminal_exec /
        // terminal_write / terminal_status 等 10 个终端子工具**从未单独注册**（只注册了
        // QuroTerminalTool，它们作为它的 action 存在），所以那些名字在注册表里根本查不到。
        // 目录里给幽灵名留条目，等于把模型往「未知工具」上引。
        "terminal" to ToolInfo(
            name = "terminal",
            category = ToolCategory.TERMINAL_LINUX,
            description = "【ZorvAI 终端】应用自己的终端会话驱动（Termux 的内部实现，⚠️ 一般不要直接调）：status 查环境与全部会话、exec 在 proot Ubuntu 里执行、run 在应用沙盒内一次性执行。与 `termux` 的区别：termux = Termux 官方 bootstrap（PREFIX/pkg），用户说「终端」默认用它；本工具管的是界面上那几个终端窗口。",
            useCases = listOf(
                "查看终端与 Linux 环境状态（ubuntu_ready / 会话列表）",
                "在 Ubuntu 里执行命令：terminal(action=\"exec\", command=\"...\")",
                "在应用沙盒内跑一条 shell：terminal(action=\"run\", command=\"...\")"
            ),
            examples = listOf(
                "terminal(action=\"status\")",
                "terminal(action=\"exec\", command=\"python3 -c 'print(1+1)'\")"
            ),
            parameters = mapOf(
                "action" to "run/exec/write/kill/status/interrupt/sessions/session_new/session_switch/session_kill/quroterm/adb/tmux/keys/monitor",
                "command" to "exec/run 用的 shell 命令", "text" to "write 要写入的一行",
                "id" to "session_switch/session_kill 的会话 id", "name" to "session_new/tmux/keys 的名称",
                "op" to "adb/tmux/keys 的内部动作"
            ),
            tips = listOf(
                "Ubuntu 用 apt 包管理",
                "exec 的返回带 distro 字段，确认命令实际跑在哪（ubuntu / device）",
                "用户说「终端 / Termux / 跑个命令」→ 优先 termux；本工具是它的内部驱动，一般不要直接调",
                "adb / tmux / keys / monitor 已并入本工具，对应旧入口按原名仍可执行"
            ),
            relatedTools = listOf("termux", "linux_env", "shizuku"),
            priority = 3
        ),

        // Termux = 完整移植的 Termux 官方宿主。与 terminal（自研 PTY 会话）分工：
        // terminal 管「会话语义」（写入/中断/切换），termux 管「在这套发行版里干活」。
        // Termux = 应用内完整移植的官方宿主。用户口中的「终端」就是它。
        // 与 terminal（底层会话驱动）分工：terminal 是它的内部实现，AI 一般不该直接调。
        "termux" to ToolInfo(
            name = "termux",
            category = ToolCategory.TERMUX,
            description = "Termux 终端（应用内完整移植的官方宿主）：管理多个终端会话，或在 Termux 官方 bootstrap（PREFIX/pkg）里执行命令、装包、查状态、拉起界面。",
            useCases = listOf(
                "打开终端", "在终端里跑个命令", "用 Termux 装个包", "开个新终端窗口",
                "看看有哪几个终端", "切到另一个终端", "在终端里输入这个", "中断一下这个命令",
                "终端里 python 装一下 requests", "查 Termux 环境好了没"
            ),
            examples = listOf(
                "termux(action=\"status\")",
                "termux(action=\"run\", command=\"uname -a && cat /etc/os-release | head -2\")",
                "termux(action=\"session_new\", name=\"work\")",
                "termux(action=\"sessions\")",
                "termux(action=\"write\", text=\"python3 -V\")",
                "termux(action=\"interrupt\")",
                "termux(action=\"pkg\", package=\"python3 git\")",
                "termux(action=\"open\")"
            ),
            parameters = mapOf(
                "action" to "run / session_new / sessions / session_switch / session_kill / write / interrupt / kill / pkg / status / repair / open",
                "command" to "run 用的 shell 命令（Termux 官方 bootstrap 内执行）",
                "text" to "write 写进当前会话的一行输入",
                "id" to "session_switch / session_kill 的目标会话 id",
                "name" to "session_new 的会话名",
                "package" to "pkg 的包名，多个空格分隔",
                "timeout_ms" to "run 超时毫秒，默认 30000"
            ),
            tips = listOf(
                "🔴 **用户说「终端 / Termux / 命令行」时一律用本工具**，不要用 terminal —— 后者是底层驱动，用途是排查/降级。",
                "**Termux 有多个终端会话**：要「开一个新窗口」→ session_new；要「看看有哪几个」→ sessions；要「切过去」→ session_switch。",
                "一次性执行（装包+跑）用 run / pkg 最省事，能一次拿到完整 stdout/stderr + 退出码。",
                "交互式程序（python REPL、vim、top）用 session_new 建会话后再 write 逐行输入，用 interrupt 发 Ctrl-C。",
                "命令跑在 **Termux 官方 bootstrap** 里（PREFIX / pkg / Termux 源），**不是** Android /system/bin/sh，也**不是** proot Ubuntu（后者走 linux_*）。",
                "🔴 Android 10+ 的 SELinux 禁止应用 exec 私有目录里的 ELF，所以 run/pkg 走的是官方 system_linker_exec（/system/bin/linker64）。看到 Permission denied **不要**去 repair —— 那是策略限制，repair 修不了，只会空转。",
                "只有 bootstrap 半套/缺库（CANNOT LINK 之类）才用 repair。",
                "环境未解压时会自动后台安装并返回提示，稍后重试即可，**不要反复触发**。"
            ),
            relatedTools = listOf("terminal", "linux_env", "bg_screen"),
            priority = 5
        ),

        // 后台虚拟屏（移植 Mika）：与无障碍控屏是「两块不同的屏」，分类上也分开。
        "bg_screen" to ToolInfo(
            name = "bg_screen",
            category = ToolCategory.VIRTUAL_SCREEN,
            description = "后台虚拟屏：在一块**用户看不见**的独立虚拟屏上打开 App、读控件树、精确点按、滑动、截图。不抢主屏焦点、不弹输入法、不打断用户正在做的事。开屏需要 Shizuku；点击优先走无障碍手势（不需要 Shizuku）。",
            useCases = listOf(
                // 触发语：用户这么说话时，必须用本工具，而不是 screen
                "别打扰我 / 后台帮我操作 / 悄悄做 / 不占我屏幕",
                "我在忙，你帮我弄一下（用户此刻在别的 App 里）",
                "后台打开某某 App，做某某步骤",
                "读一下那个 App 现在界面上有什么（不影响我）",
                "截图看看那个 App 现在什么界面",
                "把流程跑一遍，我看着主屏就行",
            ),
            examples = listOf(
                "bg_screen(action=\"status\")",
                "bg_screen(action=\"open\", packageName=\"com.tencent.mm\")",
                "bg_screen(action=\"read\")",
                "bg_screen(action=\"tap_element\", ref=\"@e3\")",
                "bg_screen(action=\"input_text\", text=\"你好\")",
                "bg_screen(action=\"swipe\", x1=540, y1=1800, x2=540, y2=600, duration_ms=300)",
                "bg_screen(action=\"back\")",
                "bg_screen(action=\"screenshot\")",
                "bg_screen(action=\"top\")",
                "bg_screen(action=\"release\")",
            ),
            parameters = mapOf(
                "action" to "status / open / read / tap_element / tap / swipe / input_text / back / screenshot / top / release",
                "packageName" to "open 用的包名（如 com.tencent.mm）",
                "ref" to "tap_element 用的元素引用，来自上一步 read 的返回（如 @e3）",
                "x/y" to "tap 坐标（与截图/read 返回的 centerX/centerY 同一坐标系）",
                "x1/y1/x2/y2" to "swipe 起终点",
                "text" to "input_text 要输入的文字",
                "duration_ms" to "swipe 时长，默认 300",
            ),
            tips = listOf(
                // 🔴 触发条件写死，避免模型「看到 screen 就选它」
                "🔴 **只要用户表达「不要占用我的屏幕 / 我在忙 / 后台做 / 悄悄做」，就必须用本工具**，不要用 `screen`（那会抢焦点、把 App 顶到用户眼前）。",
                "边界：要操作**用户正看着的那块屏**（「点我屏幕上这个按钮」）→ 用无障碍 `screen`；要**另开一块屏干活** → 用本工具。",
                "标准流程：`status`（确认屏与 Shizuku）→ `open`（启动目标 App）→ `read`（读控件树，拿 ref 与真实坐标）→ `tap_element(ref)` 或 `tap(x,y)` → **再 `read` 确认界面变了** → 用完 `release`。",
                "**优先 `read` + `tap_element`**：它给的是控件真实坐标 + 可点/可输/是否勾选，比截图猜坐标准得多、也省 token。只有 `read` 返回「没有可读控件」（界面禁止无障碍读取、或还没渲染完）时才退回 `screen` 看画面。",
                "`read` 之后界面若变了，旧 ref 会失效 —— **点之前重新 `read`**，不要复用上一步的 ref 硬点。",
                "输入文字：`screen` 走无障碍在焦点控件里输入（不经过系统输入法，主屏不会弹键盘）。目标控件没焦点时先 `tap_element` 点一下它。",
                "`open` 自带兜底：动作前后盯主屏 3 秒，若该 App 被系统丢到了用户主屏，会自动挪回后台屏并在返回里写「已自动挪回」——看到这句说明发生过，**不要重试 open**。",
                "常见失败与对策：① 返回「特权服务未就绪」→ 让用户开 Shizuku（**不要反复重试**）；② `read` 空 → 换 `screen`；③ 返回「已接管 / 已另开一个实例 / 已挪到后台屏」→ **都算成功**，直接 read 继续，不要再 open；④ 返回 code=1 才是真的接管不了（主屏前台在跑且挪不动），此时停手说明；⑤ 需要输入密码/支付/验证码 → 停手，交给用户。",
                "任务结束调 `release` 释放屏，别长期占着（占着会一直消耗内存与电量）。",
                "谁在干活：开屏靠 Shizuku（uid 2000），点击/滑动优先走无障碍手势、失败才回退 Shizuku 注入 —— 所以「Shizuku 在线但点不动」时先查无障碍是否被系统停用。",
            ),
            // 🔴 全家桶互链：AI 操控虚拟屏时，必须能发现「全部看屏工具」，而不只是一两个。
            // 这里把整套屏感知/屏操作能力列全（详见 [SCREEN_TOOL_FAMILY] / [getScreenTools]）。
            relatedTools = listOf(
                "screen", "ui_tree",
                "screen",
                "keyboard",
                "screen", "image_recognition", "adb_dev", "terminal", "shizuku",
            ),
            priority = 5
        ),

        // ADB 主机侧：本机当 ADB client 去调试另一台设备。与 adb_term（本机被调试）方向相反。
        "adb_dev" to ToolInfo(
            name = "adb_dev",
            category = ToolCategory.ADB_DEBUG,
            description = "ADB 调试工具：把本机当 ADB 主机去调试另一台 Android 设备（USB 直连 / 网络 / 回环），支持 shell、推拉文件、安装 APK。",
            useCases = listOf(
                "帮我连一下另一台手机", "用 USB 调试那台设备", "给对面装个 APK",
                "在另一台机器上执行命令", "adb 连不上怎么回事", "USB 连上了但识别不到",
                "怎么开启无线调试被电脑调试"
            ),
            examples = listOf(
                "adb_dev(action=\"guide\")",
                "adb_dev(action=\"usb_list\")",
                "adb_dev(action=\"usb_connect\", serial=\"ABC123\")",
                "adb_dev(action=\"tcp_connect\", host=\"192.168.1.8\", port=5555)",
                "adb_dev(action=\"shell\", command=\"getprop ro.product.model\")",
                "adb_dev(action=\"push\", local=\"/sdcard/a.apk\", remote=\"/data/local/tmp/a.apk\")",
                "adb_dev(action=\"install\", local=\"/sdcard/a.apk\")",
                "adb_dev(action=\"disconnect\")"
            ),
            parameters = mapOf(
                "action" to "guide / usb_list / usb_connect / tcp_connect / local_connect / devices / shell / push / pull / install / disconnect",
                "device" to "目标设备（usb_list/devices 给的名字），单设备可省略",
                "serial/deviceId" to "usb_connect 用；serial 拔插后仍稳定，优先用它",
                "host/port" to "tcp_connect 用；legacy 5555，无线调试是随机端口",
                "command" to "shell 命令",
                "local/remote" to "push/pull/install 的路径"
            ),
            tips = listOf(
                "**方向别搞混**：本工具是「我去调试别人」；本机被调试 / 开本机 tcpip 用 `terminal(action=adb, op=tcp_enable)`。",
                "不确定怎么做时先 `adb_dev(action=\"guide\")`，里面有完整技术说明。",
                "USB 直连要求**本机支持 USB Host/OTG**，且对端开着「USB 调试」；只充电的线不行。",
                "首次连接**对端会弹「允许 USB 调试」**，没人点就会认证失败——要明确告诉用户去点。",
                "认证失败排查：对端「开发者选项 → 撤销 USB 调试授权」，再重插线重连。",
                "网络调试要先在对端执行 `adb tcpip 5555`；无线调试（Android 11+）端口随机，需用户现场抄 IP:端口。",
                "拔插后 USB deviceName 会变，用 serial 定位设备，不要缓存 deviceId。"
            ),
            relatedTools = listOf("terminal", "shizuku"),
            priority = 4
        ),

        "zorvai_capabilities" to ToolInfo(
            name = "zorvai_capabilities",
            category = ToolCategory.ACCESSIBILITY,
            description = "列出宿主（ZorvAI）当前能提供的手机控制能力及各自的前提条件：" +
                "读屏与无障碍、触控与输入、屏幕与录制、应用与通知、系统与特权（Shizuku/root）、文件与浏览器。" +
                "harness 的 agent 在调用 mcp__zorvai__* 之前先问它，" +
                "避免缺权限时反复重试、或对着不可用的能力瞎猜。",
            useCases = listOf(
                "harness 现在能用哪些手机能力",
                "Shizuku 到底授权了没",
                "无障碍开了吗",
                "读屏/点击这些能不能用"
            ),
            examples = listOf(
                "zorvai_capabilities",
                "zorvai_capabilities(group=system)"
            ),
            parameters = mapOf(
                "group" to "只查某一组：screen/touch/app/system/file"
            ),
            tips = listOf(
                "返回的是实时快照，会标出每项当前是否可用、缺什么条件",
                "标「需要 Shizuku 已授权 / 需要无障碍权限」时，先让用户去设置里授权",
                "工具名带 mcp__zorvai__ 前缀的是给 harness 用的，同名工具在 ZorvAI 内直接可用"
            ),
            relatedTools = listOf("harness", "screen", "shizuku"),
            priority = 6
        ),

        "harness" to ToolInfo(
            name = "harness",
            category = ToolCategory.TERMINAL_LINUX,
            description = "DeepSeek Harness（dsh）—— DeepSeek 官方 agent harness，完整内置：" +
                "Node 26（bionic 构建）+ dsh 0.2.1-rc.2 全量插件包 + 它自带的前端界面。" +
                "不经 Ubuntu、不经终端、不联网安装。start 起 Web 服务（自带完整 UI，会话/插件/设置都在里面）；" +
                "task 走 headless profile 直接让 harness 跑完一整套 agent 循环并把答案返回 —— " +
                "把「需要完整 agent 工作流」的活儿外包给它是这个工具的核心价值。",
            useCases = listOf(
                "把这件事交给 DeepSeek Harness 去做",
                "打开 DeepSeek Harness 的界面",
                "启动 / 停止 harness 服务",
                "看看 harness 运行日志",
                "harness 起不来帮我看下原因",
                "配置 harness 的模型 key"
            ),
            examples = listOf(
                "harness(action=\"setup\")",
                "harness(action=\"start\")",
                "harness(action=\"task\", task=\"把 src 下所有 console.log 改成统一 logger\")",
                "harness(action=\"status\")",
                "harness(action=\"diag\")"
            ),
            parameters = mapOf(
                "action" to "setup/start/stop/restart/status/logs/url/task/set_key/diag/bridge_on/bridge_off/bridge_status/plugins",
                "task" to "task 动作交给 harness 执行的任务文本",
                "session_id" to "task 时续用已有会话",
                "json" to "task 时输出 NDJSON 事件流",
                "port" to "start 端口，默认 3080",
                "provider" to "set_key 的厂商：deepseek/openai/anthropic",
                "key" to "set_key 的 API key",
                "timeout_seconds" to "task 超时，默认 600"
            ),
            tips = listOf(
                "首次用 action=setup 解压运行时（约 240MB，需几分钟）",
                "harness 有自己的界面：start 后用 WebView 打开 127.0.0.1:3080，不要在 ZorvAI 里重做它的 UI",
                "task 是让 harness 干活的正路：它会自己规划、读文件、跑命令、改代码",
                "harness 需要自己的模型 key（DEEPSEEK_API_KEY 等），与 ZorvAI 的模型互不相干",
                "task 很慢是正常的（agent 循环），timeout 默认 600 秒",
                "起不来先看 action=diag 和 action=logs，别猜",
                "白屏 ≠ 没启动：action=status 看进程、action=logs 看 launch token 行，diag 会连执行位/前端 dist/cookie 一起查"
            ),
            relatedTools = listOf("terminal", "moyu"),
            priority = 5
        ),

        "moyu" to ToolInfo(
            name = "moyu",
            category = ToolCategory.TERMINAL_LINUX,
            description = "墨语言（Mo）—— 完整运行在 Ubuntu 内的系统级语言：编译/运行/调试 .mo 程序，读写源码修 bug，" +
                "调用内置工具链，管理依赖，跑测试与修复闭环，构建自举系统与裸机内核。" +
                "架构：proot 承载 Ubuntu（APK 内置 aarch64 rootfs），墨语言工具链（moc/mo2x/mols/molink/dwmo/modb/seed）、" +
                "x86_64 翻译器 qemu-x86_64-static、zig 交叉编译器、uspace2 自举系统**全部装在 Ubuntu 里**" +
                "（/opt/molang、/opt/zig、/usr/local/bin/mo）——设备上不构建、不转译工具链、不需要点构建按钮，也没有第二套分散的运行时。" +
                "🔴 **不经过宿主 App 沙箱**：box64 那条路已真机证伪（NDK 编的 bionic 动态 PIE 要墨语言自家非标 libc 符号版本，" +
                "App 沙箱里 execve ENOENT），宿主侧只剩 graph/resolve/test/aifix 依赖它，语言执行一律走 Ubuntu。" +
                "**AArch64 原生目标**：native_build 把 .mo 编成 ARM64 静态 ELF，产物不链 libc、不经 qemu，" +
                "由 Android 原生执行 —— 这是墨语言在手机上的最快路径；它是用户态系统程序，不是 Linux 内核。",
            useCases = listOf(
                "写个墨语言程序并跑起来",
                "编译我的 .mo 文件",
                "墨语言编译器报错了帮我修",
                "给我装个墨语言依赖包",
                "用墨语言起个 HTTP 服务",
                "编个安卓能直接跑的 ARM64 原生程序",
                "用墨语言写个自己的系统跑起来",
                "跑一下墨语言的测试用例",
                "看看墨语言标准库和示例"
            ),
            examples = listOf(
                "moyu(action=\"setup\")",
                "moyu(action=\"build_run\", code=\"func main() { print(\\\"hi\\\") }\", name=\"hi.mo\")",
                "moyu(action=\"compile\", path=\"/data/.../moyu/workspace/demo.mo\")",
                "moyu(action=\"run\", path=\"/data/.../moyu/workspace/demo.elf\")",
                "moyu(action=\"exec\", tool=\"mo2x\", args=\"-h\")",
                "moyu(action=\"read\", path=\"/opt/molang/src/compiler.mo\")",
                "moyu(action=\"native_build\", path=\"uspace2.mo\", target=\"aarch64\")",
                "moyu(action=\"native_run\", path=\"workspace/uspace2.arm64\")",
                "moyu(action=\"test\", filter=\"01_\")",
                "moyu(action=\"selfhosted\")",
                "moyu(action=\"kernel_build\")",
                "moyu(action=\"cc_diag\")"
            ),
            parameters = mapOf(
                "action" to "setup/compile/run/build_run/exec/list/read/write/diagnose/pkg_init/dep_install/dep_fetch/" +
                    "dep_list/dep_tree/dep_verify/graph/net_diag/test/aifix/resolve/native_build/native_run/native_targets/" +
                    "cc_diag/native_setup/selfhosted/system_image/system_build/kernel_build/kernel_info/" +
                    "native_status/native_deploy/native_bench/native_exec/native_compile/native_system/native_cleanup（共 37 个）",
                "target" to "native_build 目标：aarch64（默认，Android 原生执行）/ x86_64（qemu 翻译）/ kern-aarch64（裸机，仅模拟器）",
                "opt" to "native_build 优化级别：-O2（默认，交付用）/ -O0（快、省内存，迭代用）",
                "path" to "源文件或可执行文件路径",
                "code" to "write 时的源码内容",
                "name" to "write 时的文件名",
                "tool" to "exec 时的工具名：moc/molink/mols/modb/dwmo/mo2c/mo2x/seed",
                "args" to "exec 时的参数",
                "old" to "aifix 时被替换的原文（文件中唯一）",
                "new" to "aifix 时的替换文本",
                "filter" to "test/aifix 只跑匹配的用例名，空=全部",
                "max_cases" to "test 最多跑前 N 个用例，0=不限",
                "host" to "resolve 的域名/IPv4（墨语言 lib/net.mo 无 DNS）",
                "git" to "dep_install 的 git 仓库地址",
                "layer" to "native_deploy 部署哪层：core（约6.4MB）/ zig（约176MB）",
                "n" to "native_bench 筛法上限"
            ),
            tips = listOf(
                "首次使用先 action=setup：自动备好 Ubuntu rootfs，再把工具链/编译器/自举系统增量解压落地",
                "墨语言与 DeepSeek Harness 是两个独立系统：harness 跑宿主 app 域，墨语言跑 proot 的 Ubuntu guest",
                "修 bug 流程：read 源码 → write 改后版本 → build_run 验证",
                "test 跑内置用例；aifix 会做「打补丁→测试过→撤补丁→测试必须失败」的硬校验，别用它掩盖问题",
                "装依赖用 dep_install / dep_fetch，编译前 App 会自动同步 deps 到源目录（moc 不支持 -I）",
                "resolve 先把域名解析成 IP 整数：墨语言 lib/net.mo 无 DNS，不解析就连不上",
                "要交付原生系统程序用 native_build（aarch64）：产物无 libc、不经 qemu，Android 直接执行",
                "aarch64 产物校验看「AArch64 (0xB7) + 无 PT_INTERP」两项，只看到构建成功不算数",
                "编译慢属正常（zig 首次建缓存），快速迭代用 opt=\"-O0\"，交付前换回 -O2",
                "native_run 会按产物真实架构自动选执行方式，不用手填目标",
                "🔴 裸机内核（kern-aarch64）不能在 proot/Ubuntu 里执行 —— 它自己就是提供系统调用的那一层，只能 kernel_build 编 + kernel_info 看 ELF 结构自证",
                "起不来先 action=diagnose，它会逐环自检并直接说缺哪一环"
            ),
            relatedTools = listOf("moyu", "terminal"),
            priority = 5
        ),
        

        "dev_env" to ToolInfo(
            name = "dev_env",
            category = ToolCategory.TERMINAL_LINUX,
            description = "终端开发环境管理（唯一工具）：安装/卸载/检查开发环境 + 镜像源管理",
            useCases = listOf(
                "安装Node.js环境",
                "装Python",
                "检查Java环境",
                "卸载Rust",
                "看看有哪些开发环境",
                "配置镜像源",
                "切换镜像源"
            ),
            examples = listOf(
                "dev_env(action=\"install\", env=\"node\")",
                "dev_env(action=\"install\", env=\"node\", execute=true)",
                "dev_env(action=\"uninstall\", env=\"python\")",
                "dev_env(action=\"check\")",
                "dev_env(action=\"list\")",
                "dev_env(action=\"source_list\")",
                "dev_env(action=\"source_select\", pm=\"apt\", source=\"tuna_apt\")"
            ),
            parameters = mapOf(
                "action" to "动作: install/uninstall/check/list/source_list/source_select/source_config",
                "env" to "环境名: node/python/java/rust/go/git/all",
                "execute" to "是否直接执行命令（默认false）",
                "pm" to "包管理器: apt/pip/npm/rust",
                "source" to "镜像源ID"
            ),
            tips = listOf(
                "⚠️ 这是终端开发环境管理的唯一工具，CMS 相关操作请用 cms(action=toolbox)",
                "execute=true 时命令直接在终端执行，输出在终端界面可见",
                "execute=false 时只返回命令文本",
                "install 自动配置镜像源后再安装",
                "source_list/source_select 管理镜像源"
            ),
            relatedTools = listOf("linux_env", "terminal", "cms"),
            priority = 5
        ),

        // ═══════════════ 网络/Web ═══════════════
        "http_request" to ToolInfo(
            name = "http_request",
            category = ToolCategory.NETWORK_WEB,
            description = qstr(R.string.qk_03559),
            useCases = listOf("调一下这个接口", "发个GET到XX", "请求这个URL", "对接某个API"),
            examples = listOf("http_request(url=\"https://api.example.com/data\", method=\"GET\")"),
            parameters = mapOf("url" to "请求URL", "method" to "GET/POST等", "headers" to "请求头", "body" to "请求体"),
            tips = listOf("支持GET/POST等方法", "支持JSON请求/响应", "可设置请求头"),
            relatedTools = listOf("ai_browser", "open_web"),
            priority = 4
        ),
        
        "ai_browser" to ToolInfo(
            name = "ai_browser",
            category = ToolCategory.NETWORK_WEB,
            description = qstr(R.string.qk_03535),
            useCases = listOf("帮我搜一下XX", "查资料", "研究一下这个主题", "打开网页看看"),
            examples = listOf("ai_browser(action=\"search\", query=\"Python教程\")", "ai_browser(action=\"open\", url=\"https://example.com\")"),
            parameters = mapOf("action" to "search/open/read", "query" to "搜索关键词", "url" to "网址"),
            tips = listOf("search是搜索+抓取，一次返回", "open是打开网页", "read是读取当前页"),
            relatedTools = listOf("http_request", "open_web"),
            priority = 5
        ),
        
        "open_web" to ToolInfo(
            name = "open_web",
            category = ToolCategory.NETWORK_WEB,
            description = qstr(R.string.qk_03584),
            useCases = listOf("打开百度", "访问XX网址", "看这个网页"),
            examples = listOf("open_web(url=\"https://www.baidu.com\")"),
            parameters = mapOf("url" to "要打开的网址"),
            tips = listOf("只能被动展示，不能点击交互", "真正操作网页用 aci(action=call) 调 browser_open"),
            relatedTools = listOf("ai_browser", "aci"),
            priority = 2
        ),

        // ═══════════════ 媒体 ═══════════════
        "image_gen" to ToolInfo(
            name = "image_gen",
            category = ToolCategory.MEDIA,
            description = qstr(R.string.qk_03571),
            useCases = listOf("生成一张图片", "画个图", "AI生成图片", "帮我设计个logo"),
            examples = listOf("image_gen(prompt=\"一只可爱的猫咪\", style=\"realistic\")"),
            parameters = mapOf("prompt" to qstr(R.string.qk_01041), "style" to "风格"),
            tips = listOf("描述越详细效果越好", "支持多种风格"),
            relatedTools = listOf("image_recognition", "creative_studio"),
            priority = 4
        ),
        
        "image_recognition" to ToolInfo(
            name = "image_recognition",
            category = ToolCategory.SCREEN_PERCEPTION,
            description = qstr(R.string.qk_03443),
            useCases = listOf("识别这张图片", "看看图片里是什么", "分析这个图片"),
            examples = listOf("image_recognition(image_path=\"/sdcard/photo.jpg\")"),
            parameters = mapOf("image_path" to qstr(R.string.qk_01046)),
            tips = listOf("支持多种图片格式", "可识别物体、文字、场景"),
            relatedTools = listOf("image_gen", "screen"),
            priority = 4
        ),
        
        // ═══════════════ UI/卡片 ═══════════════
        "ui_control" to ToolInfo(
            name = "ui_control",
            // 🔴 2026-11-12：UI_CARDS → APP_NAVIGATION。ui_control 的动作全是「打开界面/弹层/
            // 界面开关/新建清空对话」（界面导航语义），与气泡内富卡片（ui_widget）不是一类；
            // handbook 优先级高于规则表，旧值会把 APP_NAVIGATION 分类架空成空壳。
            category = ToolCategory.APP_NAVIGATION,
            description = qstr(R.string.qk_03533),
            useCases = listOf(
                "打开编辑器/终端/工具箱/知识库",
                "切换深度思考/自动记忆开关",
                "打开模型/人格/设置弹层",
                "新建/清空对话",
                "渲染卡片/组件到对话框",
                "查询组件状态",
                "更新组件属性",
                "滚动到指定位置",
                "聚焦到组件",
                "隐藏/显示组件",
                "页面内导航",
                "权限控制"
            ),
            examples = listOf(
                "ui_control(action=\"open\", target=\"editor\")",
                "ui_control(action=\"toggle\", target=\"deepthink\")",
                "ui_control(action=\"sheet\", target=\"model\")",
                "ui_control(action=\"chat\", action_type=\"new\")",
                "ui_control(action=\"card\", title=\"天气\", content=\"# 今天天气\", style=\"info\")",
                "ui_control(action=\"widget\", type=\"button\", id=\"btn1\", label=\"点击\")",
                "ui_control(action=\"status\", component=\"header\")",
                "ui_control(action=\"update\", component=\"header\", props={\"title\":\"新标题\"})",
                "ui_control(action=\"scroll\", target=\"bottom\")",
                "ui_control(action=\"focus\", target=\"input\")",
                "ui_control(action=\"hide\", target=\"sidebar\")",
                "ui_control(action=\"show\", target=\"toolbox\")",
                "ui_control(action=\"navigate\", target=\"section_id\")",
                "ui_control(action=\"permission\", permission=\"camera\", enabled=true)"
            ),
            parameters = mapOf(
                "action" to "操作类型：open|toggle|sheet|chat|card|widget|status|update|scroll|focus|hide|show|navigate|permission",
                "target" to "操作目标",
                "action_type" to "chat操作子类型：new|clear",
                "title" to "卡片标题",
                "content" to "卡片内容（Markdown）",
                "style" to "卡片样式：info|success|warning|error",
                "type" to "组件类型：button|toggle|slider|input|select",
                "id" to "组件唯一ID",
                "label" to "组件标签",
                "value" to "组件值",
                "component" to "组件标识",
                "props" to "要更新的属性键值对",
                "permission" to "权限类型",
                "enabled" to "权限启用状态"
            ),
            tips = listOf(
                "统一入口，替代旧的ui_open_*/ui_toggle_*/ui_card/ui_widget",
                "支持15种操作类型",
                "通过action参数区分操作类型",
                "事件驱动架构，通过UiNavigationBus通知ChatScreen"
            ),
            relatedTools = listOf("tool_discovery", "visual_custom_popup", "visual_popup"),
            priority = 5
        ),

        // ═══════════════ 动态 UI（必备输出）══════════════

        "game_ui" to ToolInfo(
            name = "game_ui",
            category = ToolCategory.DYNAMIC_UI,
            description = qstr(R.string.qk_03444),
            useCases = listOf("用户想玩互动小游戏", "做游戏化互动", "用对话组件做可玩 demo", "现场发明新游戏规则"),
            examples = listOf("game_ui()", "game_ui(section='games')"),
            parameters = mapOf("section" to "overview/board/rules/games/all，默认 overview"),
            tips = listOf("先 game_ui() 拿规范，再用 quro-ui 渲染棋盘/控件", "用 callback 回收用户操作，AI 在对话里维持状态", "棋盘用 game_board 组件，点格自动回发 row/col/index/value", "游戏在 GenUI Agent 中也通用，可 tool_call 调 Agent 能力"),
            relatedTools = listOf("ui_widget", "genui_agent_open"),
            priority = 5
        ),

        // ═══════════════ 可视化交互（强制使用） ═══════════════
        "visual_popup" to ToolInfo(
            name = "visual_popup",
            category = ToolCategory.VISUAL_INTERACT,
            description = "弹出可视化对话框（信息展示 / 确认 / 选择），不阻塞对话流，适合向用户呈现结构化内容或收集简单输入",
            useCases = listOf("展示一段信息或结果", "需要用户确认", "收集用户简单输入"),
            examples = listOf("visual_popup(title=\"结果\", content=\"...\")"),
            parameters = mapOf("title" to "标题", "content" to "内容", "buttons" to "可选按钮列表"),
            tips = listOf("适合呈现结构化内容", "返回用户操作结果"),
            relatedTools = listOf("visual_popup", "visual_custom_popup"),
            priority = 5
        ),
        "visual_custom_popup" to ToolInfo(
            name = "visual_custom_popup",
            category = ToolCategory.VISUAL_INTERACT,
            description = "弹出高度自定义的可视化对话框（自由布局的 UI 组件），用于复杂交互或专属呈现",
            useCases = listOf("复杂结构化交互", "专属可视化呈现"),
            examples = listOf("visual_custom_popup(spec=\"{...}\")"),
            parameters = mapOf("spec" to "自定义 UI 规格 JSON"),
            tips = listOf("适合复杂交互场景", "返回用户操作结果"),
            relatedTools = listOf("visual_popup"),
            priority = 5
        ),
        "node_editor" to ToolInfo(
            name = "node_editor",
            category = ToolCategory.UI_CARDS,
            description = "节点编辑器：AI直接读写节点流工程（.qne），无需打开界面即可编排可视化流程；与工具中心面板共享 studio/flow/*.qne",
            useCases = listOf("画个流程图/节点流", "做个自动化节点", "把思路连成节点", "生成节点流工程"),
            examples = listOf("node_editor(action=\"create\", name=\"flow1\", nodes=[])"),
            parameters = mapOf("action" to "create/read/write/list", "name" to qstr(R.string.qk_02815), "nodes" to "节点列表"),
            tips = listOf("AI可直接产出/修改节点工程，面板打开自动恢复", "适合可视化编程与流程编排", "导出可转 Mermaid/可视化"),
            relatedTools = listOf("ui_control", "ui_widget", "node_editor"),
            priority = 4
        ),

        // ═══════════════ 流体云 ═══════════════
        "fluid_cloud_notify" to ToolInfo(
            name = "fluid_cloud_notify",
            category = ToolCategory.SYSTEM_CONTROL,
            description = qstr(R.string.qk_03427),
            useCases = listOf("AI思考中显示状态", "任务进度显示", "工具执行状态", "创建流体云", "更新进度", "结束流体云"),
            examples = listOf(
                "fluid_cloud(action=create, title=AI思考中, content=正在处理请求...)",
                "fluid_cloud(action=update, entityId=zorvai_xxx, progress=75)",
                "fluid_cloud(action=end, entityId=zorvai_xxx)"
            ),
            parameters = mapOf(
                "action" to "操作类型：create(创建)/update(更新)/end(结束)",
                "title" to "标题（创建/更新时使用）",
                "content" to "内容（创建/更新时使用）",
                "entityId" to "实体ID（更新/结束时必填）",
                "progress" to "进度0-100（更新时使用）"
            ),
            tips = listOf(
                "使用通用entityName（TASK/NAVIGATION）避免受限履约场景",
                "需要ColorOS 14+（推荐16+）",
                "用户需在设置中开启流体云开关",
                "创建时返回entityId，更新/结束时需要传入"
            ),
            relatedTools = listOf("visual_popup", "ui_control"),
            priority = 4
        ),
        
        // ═══════════════ 知识/记忆 ═══════════════

        // ── 以下三条是 RAG 全量排查补上的真实缺口 ──
        // 排查方式：对每条工具跑一遍「用户会怎么说」，看它能不能被召回。
        // `memory` / `memory` / `memory` 原先**没有** handbook 条目，
        // 走 [autoInfo] 兜底 —— 于是 useCases 就是工具自己的 description，
        // 「在我存过的东西里找一下」这种最普通的话召不回 memory，
        // 而它的姊妹工具 memory 反而能召回（因为那条写了 useCases）。
        // 这类不一致在旧检索层同样存在，只是没人系统性验证过。

        
        
        // ═══════════════ 工作区 ═══════════════
        "workspace" to ToolInfo(
            name = "workspace",
            category = ToolCategory.WORKSPACE,
            description = "工作区统一入口：读写列目录、建文档并预览、渲染预览、打开文档、播放媒体，一次调用完成一个动作。",
            useCases = listOf("把这段代码保存到工作区", "读一下工作区里的文件", "工作区里有什么", "创建文档并预览", "播放工作区里的音乐"),
            examples = listOf(
                "workspace(action=write, path=MyApp/src/Main.java, content=...)",
                "workspace(action=doc, path=index.html, content=...)",
                "workspace(action=write_and_render, path=index.html, content=...)"
            ),
            parameters = mapOf("action" to "工作区动作", "path" to "相对工作区根的路径", "content" to "文件内容"),
            tips = listOf("doc / write_and_render 一次调用就出预览卡，不用再单独渲染", "路径相对工作区根，用户可在工具箱-工作区查看"),
            relatedTools = listOf("file_write", "file_read", "list_dir", "build_apk"),
            priority = 5
        ),

        // ═══════════════ 看屏 / 屏幕操作（SCREEN_PERCEPTION 全家桶的补充条目）═══════════════
        // 这些工具原本没有手写条目，靠命名规则兜底归类；现补齐高质量元数据并统一归到 SCREEN_PERCEPTION，
        // 让 tool_router 的 list_tools(category="看屏/屏幕操作") 一次列全，AI 操控虚拟屏时不再漏掉它们。

        "ui_tree" to ToolInfo(
            name = "ui_tree",
            category = ToolCategory.SCREEN_PERCEPTION,
            description = "导出当前界面的无障碍控件树（UI dump）：每个控件的文本、ID、坐标、可点/可输属性，供 AI 精确定位并点按/输入。支持指定虚拟显示器（display）参数，可在后台虚拟屏上 dump。",
            useCases = listOf("把这个界面控件列出来", "我要精确点那个按钮", "读一下界面结构", "在后台屏 dump 控件树"),
            examples = listOf("ui_tree()", "ui_tree(display=2)", "ui_tree(action=\"dump\")"),
            parameters = mapOf(
                "action" to "dump（默认）",
                "display" to "显示器 id（默认主屏；后台虚拟屏用对应 display，如 2）",
            ),
            tips = listOf(
                "比 screen(action=screenshot) 更省 token、坐标更准：拿到 ref 后配合 screen(action=tap, ref=...) / screen(action=input, text=...) 定点操作",
                "界面自绘（游戏/WebView/Flutter）可能读不到控件 → 退回 screen",
                "支持 display 参数：bg_screen 的 read 底层就是它，跨屏 dump 不用切来切去",
            ),
            relatedTools = listOf("screen", "bg_screen"),
            priority = 5
        ),

        // ═══════════════ 无障碍控屏 ═══════════════
        
        

        // ═══════════════ 应用管理 ═══════════════
        "launch_app" to ToolInfo(
            name = "launch_app",
            category = ToolCategory.APP_MANAGEMENT,
            description = qstr(R.string.qk_03680),
            useCases = listOf("打开微信", "启动相机", "帮我开XX应用"),
            examples = listOf("launch_app(package_name=\"com.tencent.mm\")"),
            parameters = mapOf("package_name" to "应用包名"),
            tips = listOf("需要知道包名", "可用get_package_name查询", "支持系统应用和第三方应用"),
            relatedTools = listOf("list_installed_apps", "execute_intent"),
            priority = 4
        ),
        
        
        // ═══════════════ 通信 ═══════════════
        "send_sms" to ToolInfo(
            name = "send_sms",
            category = ToolCategory.COMMUNICATION,
            description = qstr(R.string.qk_03696),
            useCases = listOf("发短信给XX说…", "给我妈发条信息", "发个短信"),
            examples = listOf("send_sms(phone_number=\"13800138000\", message=\"你好\")"),
            parameters = mapOf("phone_number" to "手机号", "message" to "短信内容"),
            tips = listOf("需要短信权限", "支持群发", "可插入联系人名"),
            relatedTools = listOf("read_sms", "read_contacts"),
            priority = 3
        ),
        
        "read_contacts" to ToolInfo(
            name = "read_contacts",
            category = ToolCategory.COMMUNICATION,
            description = qstr(R.string.qk_03434),
            useCases = listOf("我通讯录里谁", "XX的电话多少", "找下联系人"),
            examples = listOf("read_contacts()", "read_contacts(query=\"张三\")"),
            parameters = mapOf("query" to "搜索关键词（可选）"),
            tips = listOf("支持按姓名搜索", "返回联系人列表", "需要联系人权限"),
            relatedTools = listOf("send_sms", "read_sms"),
            priority = 3
        ),
        
        // ═══════════════ AI能力 ═══════════════
        "run_code" to ToolInfo(
            name = "run_code",
            // 🔴 归终端域而不是 AI_CAPABILITIES：它和 termux/python_run 一样属于「执行环境」，
            // 放 AI_CAPABILITIES 会让「跑段代码」和「终端」分成两个篮子，
            // 模型想执行东西时只会在一个篮子里翻（用户报「AI 只会用一个工具」）。
            category = ToolCategory.TERMINAL_LINUX,
            description = qstr(R.string.qk_03429),
            useCases = listOf("跑个Python脚本", "执行这段代码", "算一下这个", "写个程序"),
            examples = listOf("run_code(code=\"print('Hello')\", lang=\"python\")"),
            parameters = mapOf("code" to "代码内容", "lang" to "语言python/node/shell/html"),
            tips = listOf("python内置Brython引擎", "node内置QuickJS", "html会渲染成网页"),
            relatedTools = listOf("terminal", "miniapp"),
            priority = 5
        ),
        
        
        "creative_studio" to ToolInfo(
            name = "creative_studio",
            category = ToolCategory.AI_CAPABILITIES,
            description = qstr(R.string.qk_03581),
            useCases = listOf("推荐IDE", "做个视频", "画个图", "做3D模型", "写音乐"),
            examples = listOf("creative_studio(action=\"list_categories\")", "creative_studio(action=\"recommend\", need=\"video_editing\")"),
            parameters = mapOf("action" to "list_categories/recommend/start", "need" to "需求描述"),
            tips = listOf("包含图形/视频/音频/3D/游戏等所有创作领域", "可推荐适合的工具", "可启动已安装的创作工具"),
            relatedTools = listOf("image_gen", "video_gen"),
            priority = 4
        ),
        
        // ═══════════════ Skills（渐进式披露：目录给元数据，全文靠本工具） ═══════════════
        // 🔴 2026-11-05：SkillLoadTool 早已注册进 QuroBuiltInTools，系统提示词也注入了
        //    Skills Catalog（≤64 条元数据），但本目录与 ALWAYS_ON 都没登记 `skill`——
        //    模型 discovery 查不到它，于是永远不读技能全文，表现为「技能开关没用」。
        "skill" to ToolInfo(
            name = "skill",
            category = ToolCategory.KNOWLEDGE_MEMORY,
            description = "按 id 加载技能全文（Skills Catalog 只给元数据，全文必须调本工具获取）",
            useCases = listOf(
                "按某个技能的方法来做这件事",
                "Skills Catalog 里有相关技能，先读它的完整说明",
                "用技能/方法论/工作流完成当前任务",
            ),
            examples = listOf("skill(name=\"frontend-design\")", "skill(name=\"code-review\")"),
            parameters = mapOf("name" to "技能 id（Skills Catalog 行里 [id=...] 的值）"),
            tips = listOf(
                "只在 Skills Catalog 里看到相关技能时调用，不要凭空猜 id",
                "全文只在本轮生效，下一轮需要再调一次",
                "若用户已为当前消息选了技能，全文会随消息直接下发，无需再调本工具",
            ),
            relatedTools = listOf("skill_list", "memory"),
            priority = 5,
        ),

        // ═══════════════ MCP ═══════════════
        
        
        // ═══════════════ CMS ═══════════════
        

        "build_apk" to ToolInfo(
            name = "build_apk",
            category = ToolCategory.CMS_DEVELOPMENT,
            description = qstr(R.string.qk_03592),
            useCases = listOf("帮我做个App", "打包这个安卓工程", "生成APK", "编译并签名", "用自定义包名/图标出包"),
            examples = listOf("build_apk(source=\"...java代码...\", package_name=\"com.example.app\", generate_keystore=true)"),
            parameters = mapOf(
                "source" to "源代码（Java/Kotlin）",
                "package_name" to "自定义包名",
                "generate_keystore" to "true=自动生成release签名",
                "keystore_alias" to "签名别名",
                "store_password" to "keystore密码",
                "key_password" to "密钥密码",
                "keystore_path" to "已有keystore路径",
                "dependencies" to "依赖JAR路径数组",
                "icon_path" to "自定义图标PNG路径"
            ),
            tips = listOf("支持自定义包名、release签名生成与使用、引用依赖JAR、自定义图标", "入口类支持任意包名/类名（动态探测 zorv_entry.txt）", "与 ui_control(action=「ui_open_build」，打开构建台UI) 互补"),
            relatedTools = listOf("export_apk", "ui_control", "workspace"),
            priority = 5
        ),

        "export_apk" to ToolInfo(
            name = "export_apk",
            category = ToolCategory.CMS_DEVELOPMENT,
            description = qstr(R.string.qk_03454),
            useCases = listOf("把APK导出来", "保存到下载目录", "导出产物"),
            examples = listOf("export_apk(path=\"/sdcard/Download/app.apk\")"),
            parameters = mapOf("path" to "导出目标路径"),
            tips = listOf("构建台UI的导出是文件选择器、AI用不了，本工具替代它"),
            relatedTools = listOf("build_apk"),
            priority = 3
        ),

        
        // ═══════════════ ACI ═══════════════
        
        
        // ═══════════════ 视觉分析 ═══════════════
        
        
        // ═══════════════ 语音 ═══════════════
        "speak" to ToolInfo(
            name = "speak",
            category = ToolCategory.MEDIA,
            description = qstr(R.string.qk_03742),
            useCases = listOf("读给我听", "念一下这段", "大声朗读", "用语音播报"),
            examples = listOf("speak(text=\"你好世界\", voice=\"xiaoxiao\")"),
            parameters = mapOf("text" to "要朗读的文本", "voice" to "音色（可选）"),
            tips = listOf("支持多种音色", "可设置语速", "与自动朗读独立"),
            relatedTools = listOf("speak"),
            priority = 4
        ),
        
        // ═══════════════ 定时任务 ═══════════════
        "task_schedule" to ToolInfo(
            name = "task_schedule",
            category = ToolCategory.AI_CAPABILITIES,
            description = qstr(R.string.qk_03628),
            useCases = listOf("每天定时提醒我喝水", "下周三自动发条消息", "10分钟后执行某个动作"),
            examples = listOf("schedule_task(action=\"create\", schedule=\"0 9 * * *\", task=\"提醒喝水\")"),
            parameters = mapOf("action" to "create/list/delete", "schedule" to "cron表达式", "task" to "任务内容"),
            tips = listOf("支持cron表达式", "可一次创建多个", "支持循环和单次"),
            relatedTools = listOf("task_schedule", "alarm_clock"),
            priority = 4
        ),
        
        // ═══════════════ 文档生成 ═══════════════
        "aiwps_create" to ToolInfo(
            name = "aiwps_create",
            category = ToolCategory.AI_CAPABILITIES,
            description = qstr(R.string.qk_03499),
            useCases = listOf("帮我生成一份Word周报", "做个Excel表格", "出个PPT关于XX", "生成PDF报告"),
            examples = listOf("aiwps_create(type=\"docx\", title=\"周报\", content=\"内容\")"),
            parameters = mapOf("type" to "docx/xlsx/pptx/pdf", "title" to qstr(R.string.qk_01066), "content" to qstr(R.string.qk_01059)),
            tips = listOf("生成真正的Office二进制文件", "可用WPS/Office打开", "文件保存到Downloads目录"),
            relatedTools = listOf("enhanced_doc_create", "chat_doc", "workspace"),
            priority = 5
        ),
        
        "chat_doc" to ToolInfo(
            name = "chat_doc",
            category = ToolCategory.AI_CAPABILITIES,
            description = qstr(R.string.qk_03539),
            useCases = listOf("写一篇文章在对话框显示", "生成代码示例", "写报告/方案", "生成表格"),
            examples = listOf("chat_doc(title=\"方案\", content=\"# 方案\\n...\", format=\"md\")"),
            parameters = mapOf("title" to qstr(R.string.qk_01066), "content" to qstr(R.string.qk_01059), "format" to "md/html/code/text"),
            tips = listOf("不生成文件，内容直接渲染在对话框", "适合快速展示", "支持Markdown/HTML/代码/文本"),
            relatedTools = listOf("aiwps_create", "enhanced_doc_create", "ui_widget"),
            priority = 5
        ),
        
        "enhanced_doc_create" to ToolInfo(
            name = "enhanced_doc_create",
            category = ToolCategory.AIP_DOC,
            description = qstr(R.string.qk_03736),
            useCases = listOf("创建Markdown文件", "生成JSON配置", "写HTML页面", "创建CSV数据"),
            examples = listOf("enhanced_doc_create(type=\"md\", title=\"笔记\", content=\"内容\")"),
            parameters = mapOf("type" to qstr(R.string.qk_01065), "title" to qstr(R.string.qk_01066), "content" to qstr(R.string.qk_01059)),
            tips = listOf("支持17种格式", "文本类文档优先用此工具", "创建后自动渲染预览"),
            relatedTools = listOf("aiwps_create", "chat_doc", "workspace"),
            priority = 4
        ),

        // ═══════════════ zxwidget 动态交互界面（477 个 zx 组件）═══════════════
        // 这两个工具此前没有任何分类规则命中，落到 BASIC，导致 tool_router 的
        // match_intent / list_tools 与 tool_discovery **都查不到 zxwidget**
        // → 模型根本不知道有这个能力 → 用户报「AI 不会用 zxwidget / 渲染不出来」。

        "zx_web_card" to ToolInfo(
            name = "zx_web_card",
            category = ToolCategory.ZXWIDGET,
            description = "zxwidget 动态交互界面：把结构化结果编译成**可交互控件直接渲染在对话框消息流里**（方案对比、数据表、表单、看板、时间线、仪表盘、审批流…），用户在界面上点选/填写，交互结构化回传",
            useCases = listOf(
                "我要能点选对比的方案", "画个可交互的表格（能点行）", "做个表单让我填",
                "时间线/流程可视化", "看板/看板拖拽那种", "仪表盘/进度可视化",
                "让我在界面上选一个", "把这个数据做成能操作的界面", "审批流/步骤条",
                "不要一大段文字，做成能点的东西"
            ),
            examples = listOf(
                "zx_web_card(component=\"zx_compare\", dsl={\"items\":[{\"n\":\"方案A\",\"v\":92,\"d\":\"成本低\"},{\"n\":\"方案B\",\"v\":78,\"d\":\"扩展好\"}]}, title=\"方案对比\")",
                "zx_web_card(component=\"zx_table\", dsl={\"cols\":[\"接口\",\"P99\",\"错误率\"],\"rows\":[[\"POST /api/order\",\"612ms\",\"0.31%\"]]}, title=\"接口指标\")",
                "zx_web_card(component=\"zx_form\", dsl={\"fields\":[{\"k\":\"name\",\"label\":\"姓名\",\"type\":\"text\"}],\"submit\":\"提交\"}, title=\"登记\")"
            ),
            parameters = mapOf(
                "component" to "zx 组件名，必须来自 zx_catalog 名册（477 个，全部 zx_ 开头）",
                "dsl" to "该组件的数据 JSON（对象）；字段按 zx_catalog component=X 返回的契约与 example 填",
                "title" to "界面标题（可选，缺省用组件名）",
                "timeout" to "等待用户交互的超时秒数，默认 120"
            ),
            tips = listOf(
                "🔴 **它是「直接渲染在对话框里」，不是弹窗、也不是气泡内卡片栏**——调完就在当前对话流里长出一块可交互界面。",
                "动笔前先用 `zx_catalog` 确认组件名与字段（可传 component 拿契约 + 示例 DSL），别凭印象拼。",
                "用于「要用户动手操作」的场景；屏幕弹窗用 `visual_popup`；单块只读的**可视化小卡片是独立分类**，用 `visual_card`（html 自由写任意场景），不要用富组件 `ui_card`/`ui_widget` 顶替。",
                "用户点完之后交互会作为**本工具的结果**回来，你据此继续推理——不要重复再画一遍同一块界面。",
                "一块界面只画一次；需要多块就多次调用，它们会依次内联在消息流里。"
            ),
            relatedTools = listOf("ui_widget", "visual_popup", "genui_agent_open"),
            priority = 5
        )
    )
    
    /**
     * 合成目录：以真实注册表为单一真相源。
     * - 手写 [handbook] 作为「增强元数据」覆盖（保留高质量 useCases/examples/tips/relatedTools 与手写分类）；
     * - 真实注册表里 handbook 没有的工具，用 description 兜底生成最小 ToolInfo（分类走命名推断 [inferCategory]）；
     * - handbook 里「真实不存在」的旧名工具不会进入合成目录，避免误导模型去调不存在的工具。
     * 由此 [directory] 始终 == 真实可调用工具全集，模型经 tool_discovery / tool_router 查到的目录与
     * 实际下发的 tools 字段严格一致，从根本上消除「分组目录残缺 → 很多功能得提醒才用」。
     */
    private var directory: Map<String, ToolInfo> = handbook

    /**
     * 最近一次 [install] 进来的**真实工具清单**快照。
     *
     * 🔴 为什么需要：`AgentRag.refresh()` 无参调用时不会安装工具域
     * （`if (toolSpecs.isNotEmpty())`），于是若 RAG 的第一次初始化
     * 是由旧的 `rag_search`（现已并入 memory(action=discover)）触发的，tools 域会**永远为空** ——
     * 该路查询必然零命中，模型据此判定「没有这个工具」。
     * 留这份快照，让任何地方都能补齐工具域索引。
     */
    private var lastInstalledSpecs: List<QuroToolSpec> = emptyList()

    /** 真实工具清单的只读快照；尚未 install 时返回空表（调用方需判空）。 */
    fun specsSnapshot(): List<QuroToolSpec> = lastInstalledSpecs

    /** 由真实注册表安装/刷新工具能力目录。应在注册表构建（register + attach + mergeSkills）完成后调用一次。 */
    fun install(specs: List<QuroToolSpec>) {
        val merged = LinkedHashMap<String, ToolInfo>()
        for (s in specs) {
            merged[s.name] = handbook[s.name] ?: autoInfo(s)
        }
        directory = merged
        lastInstalledSpecs = specs.toList()
    }

    private fun autoInfo(spec: QuroToolSpec): ToolInfo = ToolInfo(
        name = spec.name,
        category = inferCategory(spec.name),
        description = spec.description,
        useCases = listOf(spec.description),
        examples = emptyList(),
        parameters = emptyMap(),
        tips = emptyList(),
        relatedTools = emptyList(),
        priority = 3,
    )

    /** 命名推断分类（兜底；handbook 已含手写分类优先）。与 QuroToolRouter.categorize 同源逻辑。 */
    private fun inferCategory(name: String): ToolCategory {
        // 🔴 2026-11-02 重构：分类逻辑搬到 ToolTaxonomy（单一真相源），本函数只做委托。
        // 原来这里是一份 ~80 行的 when 规则，QuroToolRouter.categorize 里还有**另一份**
        // 几乎一样的实现，靠注释「必须同源」人工同步，已经漂移（cms_* 两边归属不同）。
        // 而且两份都是逐条打补丁，实测 239 个工具里 100 个（42%）落到兜底 BASIC：
        // tool_router 按分类列不出 write_file / run_code / set_alarm / http_request 这些日常工具，
        // 只好把它们全塞进 ALWAYS_ON 每轮全量下发 —— 常驻集膨胀到 154 个，这才是响应变慢的机制。
        return ToolTaxonomy.categoryOf(name)
    }

    /**
     * 根据用户意图匹配工具（RAG 检索层）。
     *
     * ## 🔴 为什么重写（实测召回率 2/14 = 14%）
     * 旧实现只有一处整句包含判断：`intent.contains(useCase) || useCase.contains(intent)`。
     * 而 `autoInfo` 给无手写条目的工具填的 useCases 就是 **工具自己的 description**，
     * 于是「设个闹钟」永远匹配不上「设置/添加闹钟（支持重复）」这种整句——
     * 实测 14 条最普通的中文口语里只召回 2 条，且排第一的恒定是 `cms`（噪声条目）。
     * 那不是检索，是字符串全等。
     *
     * ## 现在的做法：分词 + 多字段加权评分
     *  1. 意图与工具文本都过同一套**中英混合分词器** [ToolTextMatcher.tokenize]；
     *  2. 在多个字段上分别算分（名字 / 描述 / 场景 / 示例 / 技巧 / 分类），字段权重不同；
     *  3. 中文按 **bigram + 内置词典** 切，英文按 `_[a-z]` 与原词切；
     *  4. 同义词走 [ToolTextMatcher.synonyms]，解决「念一首诗」→「朗读」、「导出」→`export` 这类；
     *  5. 完全不命中时**不返回空**——回退成「名字里含意图任意 token」的宽松结果，
     *     保证模型至少有候选可看，而不是「未找到匹配的工具」。
     *
     * 排序先看检索得分，再看目录手写的 `priority`，最后按名字定序（**结果稳定可测**）。
     */
    fun matchToolsByIntent(intent: String): List<ToolInfo> {
        val q = intent.trim()
        if (q.isEmpty()) return emptyList()
        val tokens = ToolTextMatcher.tokenize(q)
        if (tokens.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<ToolInfo, Double>>()
        for (info in directory.values) {
            val score = scoreOf(info, tokens, q)
            if (score > 0.0) scored.add(info to score)
        }
        if (scored.isNotEmpty()) {
            return scored
                .sortedWith(
                    compareByDescending<Pair<ToolInfo, Double>> { it.second }
                        .thenByDescending { it.first.priority }
                        .thenBy { it.first.name }
                )
                .map { it.first }
        }
        // 🔴 零命中兜底：返回**分类级**候选（按目录 priority 取前若干），而不是空列表。
        //   空列表会被 tool_router 渲染成「未找到匹配的工具」，模型就此认定「没有这个能力」——
        //   那正是用户报的「AI 根本查不到」。给一批同类候选，模型至少能挑近似的试。
        return directory.values
            .sortedWith(
                compareByDescending<ToolInfo> { it.priority }
                    .thenBy { it.name }
            )
            .take(FALLBACK_CANDIDATES)
    }

    /** 零命中时返回的兜底候选个数。给得太多会稀释上下文，太少又挑不出替代品。 */
    private const val FALLBACK_CANDIDATES = 12

    /** 单个工具对当前意图的检索得分；0 表示不相关。 */
    private fun scoreOf(info: ToolInfo, tokens: List<String>, rawQuery: String): Double {
        var score = 0.0
        // 字段权重：名字命中最可靠（用户往往直接说工具用途），描述次之，场景/示例再次。
        score += fieldScore(info.name, tokens, 3.0)
        score += fieldScore(info.description, tokens, 2.0)
        info.useCases.forEach { score += fieldScore(it, tokens, 1.2) }
        info.examples.forEach { score += fieldScore(it, tokens, 0.8) }
        info.tips.forEach { score += fieldScore(it, tokens, 0.5) }
        info.category?.displayName?.let { score += fieldScore(it, tokens, 1.0) }
        // 整串包含：用户可能把描述整句抄进来（"打开浏览器搜索天气预报"）
        if (rawQuery.length >= 3 && (info.description.contains(rawQuery, ignoreCase = true) ||
            info.name.contains(rawQuery, ignoreCase = true))) score += 4.0

        // 🔴 名称**恰好**等于查询实义词时给强加成：`build_apk` 对「打包」「导出APK」
        //   这类「用户直接说出工具用途」的查询最准；纯 token 重叠排第一的常是描述长、
        //   什么词都沾一点的工具。
        val queryCore = tokens.filter { tokenWeight(it) >= 0.6 }
        if (queryCore.isNotEmpty()) {
            val nameTokens = ToolTextMatcher.tokenize(info.name).toHashSet()
            val nameHit = queryCore.count { nameTokens.contains(it) }
            if (nameHit > 0) {
                score += 1.6 * nameHit / queryCore.size
            }
        }
        return score
    }

    /** token 命中率加权：多命中的字段给分，含全部 token 的额外加成。 */
    private fun fieldScore(field: String, tokens: List<String>, weight: Double): Double {
        if (field.isBlank()) return 0.0
        val fieldTokens = ToolTextMatcher.tokenize(field)
        if (fieldTokens.isEmpty()) return 0.0
        val set = fieldTokens.toHashSet()
        var hit = 0
        var hitWeight = 0.0
        for (t in tokens) {
            if (!set.contains(t)) continue
            hit++
            hitWeight += tokenWeight(t)
        }
        if (hit == 0) return 0.0
        // 覆盖率为主、命中个数为辅：命中 1/3 个 token 不该等同于命中 3/3
        val coverage = hit.toDouble() / tokens.size
        val bonus = if (hit == tokens.size) 0.5 else 0.0
        // 🔴 命中词的**信息量**参与打分：命中「闹钟/导出/apk」这类实义词，
        //   与命中「一下/这张/那个」这类泛化词，贡献不该等同。
        //   修正前「画一张海报」里 card_patch 靠「一/张」这类通用 bigram 排到第一，
        //   真正的 codecanvas_markup 反而靠后——纯按命中数排序时噪声必然压过正解。
        val informativeness = (hitWeight / hit).coerceIn(0.15, 1.0)
        return weight * (coverage * informativeness + bonus)
    }

    /**
     * 单个 token 的信息量权重：停用词 0.15，普通词 0.6，纯数字/单字 0.5，较长的实词 1.0。
     *
     * 没有语料统计可用（工具域语料太小），故用**长度 + 词性直觉**近似：
     * 短且泛的（「一下」「这个」「那个」）几乎出现在每个工具描述里，命中它没有信息量。
     */
    private fun tokenWeight(token: String): Double = when {
        token in STOP_WORDS -> 0.15
        token.length <= 1 -> 0.4
        token.all { it.isDigit() } -> 0.3
        token.length >= 3 -> 1.0
        else -> 0.6
    }

    /**
     * 中文检索里的泛化词/停用词：几乎出现在每条工具描述中，命中它们不能作为相关证据。
     *
     * 🔴 这类 token 主要由 bigam 切分产生（「画一**张**」「这**张**」「一**个**」），
     * 也包含用户口语里的礼貌/操作前缀（「帮我」「麻烦」「给我」）。
     */
    private val STOP_WORDS = setOf(
        "一下", "这个", "那个", "这些", "那些", "什么", "怎么", "可以", "需要",
        "帮我", "帮忙", "麻烦", "给我", "给我来", "来个", "来一个", "来张",
        "一张", "一个", "这张", "那张", "进行", "使用", "相关", "有关",
        "一下的", "的话", "东西", "内容", "并且", "或者", "然后", "现在",
        // 🔴 「念/朗读/说」这类**动作意图词**若不作停用词，会让「念一段文字」把
        //   所有描述含「文字」的翻译/听写工具都拉高分，speak 反而掉出候选。
        //   注意只停用这些「泛化动作」，具体动作词（导出/朗读/删除）仍在同义词表里生效。
        "一段", "一段话", "文字", "文本", "内容一下",
    )
    
    /**
     * 根据分类获取工具
     */
    fun getToolsByCategory(category: ToolCategory): List<ToolInfo> {
        return directory.values.filter { it.category == category }
    }
    
    /**
     * 获取工具信息
     */
    fun getToolInfo(toolName: String): ToolInfo? {
        return directory[toolName]
    }

    /**
     * 返回「看屏 / 屏幕操作」能力全家桶（[SCREEN_TOOL_FAMILY]）。
     *
     * 用于：AI 操控虚拟屏 / 读屏 / 截图时，一次拿到全部屏相关工具的 [ToolInfo]，
     * 解决「不知道已有的全部看屏工具」问题。只返回注册表里真实存在的（不存在的会被过滤，
     * 例如某个设备未启用 ADB 时 adb_dev/adb_term 仍可安全缺省）。
     */
    fun getScreenTools(): List<ToolInfo> {
        return SCREEN_TOOL_FAMILY.mapNotNull { directory[it] }
    }
    
    /**
     * 获取所有分类
     */
    fun getAllCategories(): List<ToolCategory> {
        return ToolCategory.values().toList()
    }
    
    /**
     * 获取工具能力目录摘要（注入系统提示）
     */
    fun buildDirectorySummary(): String {
        val sb = StringBuilder()
        sb.appendLine("# 工具能力目录（快速参考）")
        sb.appendLine("当你需要完成某个任务时，按以下分类快速找到合适工具：")
        sb.appendLine()
        
        for (category in ToolCategory.values()) {
            val tools = getToolsByCategory(category)
            if (tools.isNotEmpty()) {
                sb.appendLine("## ${category.displayName}（${category.description}）")
                tools.sortedByDescending { it.priority }.forEach { tool ->
                    sb.appendLine("- ${tool.name}：${tool.description}")
                    if (tool.useCases.isNotEmpty()) {
                        sb.appendLine("  · 用于：${tool.useCases.take(3).joinToString("、")}")
                    }
                }
                sb.appendLine()
            }
        }
        
        return sb.toString()
    }
    
    /**
     * 意图 → 工具推荐表（系统提示词「常见意图 → 工具推荐」的唯一来源）。
     *
     * 🔴 用 List<Pair> 而不是 Map：`mapOf` 遇到重复键会**静默丢弃**前一条，
     * 这张表此前就有一对重复的「终端命令」键，前一条（terminal/termux/linux_env）
     * 一直被后一条覆盖，谁都发现不了。List 保留全部条目，重复由
     * `UnifiedToolingContractTest` 直接断言，改坏会红。
     */
    internal val INTENT_MAPPINGS: List<Pair<String, List<String>>> = listOf(
            "时间/日期" to listOf("get_current_time"),
            "设备信息" to listOf("get_device_info", "get_battery", "get_wifi_info"),
            "计算/算数" to listOf("calculate", "run_code"),
            "文件操作" to listOf("file_read", "file_write", "list_dir", "file_info"),
            "终端命令" to listOf("termux", "terminal(action=exec)", "terminal(action=run)", "linux_env(action=run)"),
            "联网搜索" to listOf("web(action=search_and_read)", "http_request", "web(action=read)"),
            "打开网页" to listOf("aci(action=call, capability=browser_open)", "ai_browser", "open_web"),
            "图片生成" to listOf("image_gen"),
            "图片识别" to listOf("image_recognition", "screen"),
            "UI展示" to listOf("ui_control"),
            "原生交互界面/动态UI" to listOf("ui_widget(with_spec=true)", "ui_widget(with_validate=true)"),
            "富组件/数据卡片（ui_widget，禁止裸 JSON）" to listOf("ui_widget", "visual_card"),
            // 🔴 术语铁律（2026-11-05 重写）：「可视化小卡片」= **独立分类 WIDGET_CARD**，
            // 唯一出口 `visual_card` 工具（html 自由写任意场景；13 份样板仅供参考，2026-11-06）。
            // 旧 ```quro-card Canvas 围栏是 legacy，不再作为小卡片；ui_widget/ui_card 是富组件另一分类，
            // 动态UI/弹窗/网页预览同理——均严禁顶替。旧映射曾把「小卡片」指到富组件，是 AI 用错通道的根因。
            "可视化小卡片 / 小卡片（**独立分类：调 `visual_card` 工具，html 自由写任意场景**；不是动态UI/富组件/弹窗）" to listOf("visual_card", "visual_card_skeleton"),
            "可视化弹窗（visual_popup，独立功能，≠小卡片）" to listOf("visual_popup"),
            "待办/进度/统计/评分/告警卡片" to listOf("ui_widget(type=\"list\")", "ui_widget(type=\"progress\")", "ui_widget(type=\"stat\")", "ui_widget(type=\"rating\")", "ui_widget(type=\"alert\")"),
            "列表/表格/饼图卡片" to listOf("ui_widget(type=\"list\")", "ui_widget(type=\"table\")", "ui_widget(type=\"pie\")"),
            "播放器/浏览器完整界面" to listOf("ui_widget(with_spec=true)", "genui_agent_open", "miniapp"),
            "流程图/架构图" to listOf("ui_control(action=\"widget\", type=\"mermaid\")"),
            "记忆保存" to listOf("memory(action=save)"),
            "知识搜索" to listOf("memory(action=kb_search)", "memory(action=rag)"),
            "记忆检索 / 经验 / 规则发现" to listOf("memory(action=recall)", "memory(action=discover)"),
            "Shizuku 特权" to listOf("shizuku(action=status)", "shizuku(action=exec)"),
            "ROOT 执行" to listOf("root(action=status)", "root(action=exec)"),
            "智能体键盘打字 / 发送" to listOf("keyboard(action=type_and_send)"),
            "权限自查 / 系统设置" to listOf("permissions(action=check)", "permissions(action=policy)"),
            "工作区文件" to listOf("workspace", "file_write(root=workspace)", "file_read(root=workspace)"),
            "屏幕操作" to listOf("screen(action=read)", "screen(action=tap)", "screen(action=swipe)"),
            "屏幕感知 / 截图 / 视觉理解" to listOf("screen(action=read)", "screen(action=screenshot)", "screen(action=analyze)"),
            "应用启动" to listOf("launch_app"),
            "发短信" to listOf("send_sms"),
            "读联系人" to listOf("read_contacts"),
            "代码执行" to listOf("run_code", "miniapp"),
            // 🔴 2026-11-06 术语边界：Web 应用 ≠ 小程序。用户说 Web 应用/网页应用/网站 → miniapp（HTML+WebView）；
            // 用户明确说微信小程序/原生小程序/WXML → miniapp_sdk。两者渲染引擎不同，严禁互替。
            "Web 应用 / 网页应用 / 网站 / 网页工具（HTML，不是小程序）" to listOf("miniapp"),
            "微信小程序 / 原生小程序 / WXML 工程（仅用户明确要小程序时）" to listOf("miniapp_sdk"),
            "文档生成（可下载）" to listOf("aiwps_create", "enhanced_doc_create"),
            "对话框写文档" to listOf("aip_compose", "chat_doc"),
            "对话框显示文档" to listOf("aip_compose", "chat_doc"),
            "定时任务" to listOf("task_schedule"),
            "语音朗读" to listOf("speak"),
            "MCP工具" to listOf("mcp(action=servers)", "mcp(action=list_tools)", "mcp(action=call)"),
            "技能 / 加载技能全文 / 按技能方法做" to listOf("skill", "skill_list"),
            "查看技能 / 有哪些技能" to listOf("skill_list"),
            "CMS模块" to listOf("cms(action=list)", "cms(action=call)", "cms(action=toolbox)"),
            "开发环境安装" to listOf("dev_env(action=install)", "dev_env(action=install, execute=true)"),
            "开发环境检查" to listOf("dev_env(action=check)"),
            "开发环境卸载" to listOf("dev_env(action=uninstall)"),
            "开发环境管理" to listOf("dev_env(action=install)", "dev_env(action=check)", "dev_env(action=list)"),
            "镜像源管理" to listOf("dev_env(action=source_list)", "dev_env(action=source_select)"),
            "第三方App" to listOf("aci(action=call)", "aci(action=list)"),
            "流体云/状态栏" to listOf("fluid_cloud_notify"),
            "AI思考中/任务进度" to listOf("fluid_cloud_notify(action=create)", "fluid_cloud_notify(action=update)"),
            "CMS模块管理" to listOf("cms(action=toolbox)"),
            "CMS引擎部署" to listOf("cms(action=deploy)", "cms(action=engine_status)"),
            "CMS部署修复" to listOf("cms(action=undeploy)", "cms(action=deploy)"),
            "AI自写CMS模块" to listOf("cms(action=toolbox)")
    )

    fun buildIntentRecommendations(): String {
        val sb = StringBuilder()
        sb.appendLine("# 常见意图 → 工具推荐")
        sb.appendLine("当用户说以下意图时，直接调用对应工具：")
        sb.appendLine()

        for ((intent, tools) in INTENT_MAPPINGS) {
            // 手写表里难免残留已退役/已合体的旧名。输出前按当前真实注册表过滤，
            // 过滤键取 "(" 前的工具名，保留后面的 action 提示，模型才不会照着调出「未知工具」。
            val live = tools.filter { t -> directory.containsKey(t.substringBefore("(").trim()) }
            if (live.isEmpty()) continue
            sb.appendLine("- **$intent** → ${live.joinToString("、") { "`$it`" }}")
        }
        
        return sb.toString()
    }
    
    /**
     * 获取工具使用最佳实践
     */
    fun buildBestPractices(): String {
        return """
# 工具使用最佳实践

## 1. 主动使用工具
- 用户需要真实数据（天气/时间/设备状态）→ 调用工具获取，不要瞎猜
- 用户需要真实动作（打开应用/读写文件/控制设备）→ 调用工具执行

### 🔴 工具是**分分类按需取用**的，不是一次全给你

你手上能用的工具远多于当前 `tools` 字段里列出的那些。除了少数核心工具常驻，
其余约 250 个都在**分类目录**里，需要你主动查。**这不是限制，是让你别在一堆没用的东西里挑。**

规矩只有三条：
1. **不确定用哪个工具 → 先 `tool_router(action="match_intent", intent="用户原话")`**。
   直接把用户那句话丢进去，比你自己想关键词准。
2. 拿到工具名 → `get_schema(name=...)` 取参数用法；**已知参数时直接 `call(name=..., arguments={...})` 执行**，一次调用完成，不必多等一轮。
3. `match_intent` 没结果 → `list_categories()` 看有哪几类，再 `list_tools(category=...)`。

⚠️ **只要你心里冒出「我没有这个能力」的念头，必须先查一遍再说。**
你「没听说过」某个工具，通常只是因为它没常驻，不代表它不存在。
说「做不到」之前没查过分类，就是在骗用户。

- 用户提到**「小卡片 / 可视化小卡片 / 来张天气卡」** → **调 `visual_card` 工具**（独立分类）。**html 直接自由写**——任何场景都能写，没有白名单；结构没把握时才 `visual_card_skeleton(kind=...)` 取参考样板改造。禁止用 quro-card 围栏 / 动态UI / 富组件 / 弹窗 / 网页预览顶替。
- 用户要**更重的可视化**（饼图/雷达/热力图/甘特图/看板/流程图/二维码/迷你应用…103 种）→ 调 `ui_widget` 或 `ui_card` 工具（富组件，另一分类）。
- **动态 UI（quro-ui）、可视化弹窗（visual_popup）、可视化询问（visual_question）都是别的分类**，不是小卡片。

### 可视化小卡片（独立分类，必须走工具通道；自由生成为主）

「可视化小卡片」= 单块、只读、看完即走、内联在消息流里的仪表盘小组件。它**不属于**动态UI组件 / 富组件 / 可视化弹窗 / 网页预览。唯一出口：

```
visual_card(title="...", emoji="📍", accent="#4B7CF6", html="<div>…直接写这个场景的卡体…</div>")
```

**自由写是主路**：html 参数就是完整创作空间，任何场景都能写——天气/新闻/日程之外，收件箱、番茄钟、会议倒计时、项目进度、心率、温控、门锁、物流、打车、登机牌、汇率、预算、自选股、订阅、战绩、直播……用户要什么场景就写什么场景，**没有「不在清单里就不能做」这回事**。

写作规则（保证观感统一）：
1. 只写卡体：圆角 20 / 头部标题栏 / 亮暗主题 / viewport 由宿主卡壳给，不要重复画卡片边框。
2. 主文字 `color:CanvasText`（亮暗自适应）；线/底用 `rgba(128,138,160,.x)` 或主题 token（--card/--border/--muted 已注入）；强调色用固定 hex（蓝 #4f6bff / 青 #18b89a / 橙 #ff9a4c / 红 #ff5f6d；行情涨红跌绿）。
3. 布局一律 flex/grid + 百分比（容器约 340dp）；图表/环形/折线/二维码/路线用内联 SVG；零外部依赖，不引 CDN、不写 <script>。
4. 没把握时用 `visual_card_skeleton(kind=...)` 取 13 份参考样板（weather/news/calendar/terminal/music/market/todo/monitor/location/battery/shortcuts/clock/quote）改造或组合——样板是参考，不是白名单。
5. 一工具一卡；一次回复要出多张卡就调多次 `visual_card`。

⚠️ 历史通道 quro-card 围栏（Canvas DSL）是 legacy，字段名与 HTML 不通用，**不再用于可视化小卡片**。写它用户看到的是裸 JSON 或降级块，等于「写不出来小卡片」。

## 2. 把多个工具编排成一条链（这是「干成一件事」的关键）

**一件真事极少只用一个工具。** 「给张三发条微信说我晚点到」= 读联系人 → 打开微信 → 找会话 → 输入 → 发送 → 读界面确认。
你要在动手**之前**就想清楚这条链，而不是走一步看一步。

### 编排的四步法

1. **拆**：把用户这句话拆成「必须达成的状态」。上例的目标状态是「微信里那条消息真的发出去了」，
   不是「点了发送按钮」。
2. **列**：列出达成它需要的动作序列，并标出**哪些能并行、哪些必须串行**。
   - 并行：互不依赖的读操作（同时查天气+查日程）、多个独立的信息获取。
   - 串行：后一步依赖前一步的结果（不知道包名就先查、不知道 ref 就先 read）。
3. **查**：序列里任何一个动作用哪个工具你没把握 → 立刻 `tool_router` 查，**别硬套手上的核心工具**。
   例：「发消息」不该用 `send_broadcast` 硬凑，应该查有没有专门的分享/输入通道。
4. **走**：执行一步 → **读结果** → 决定下一步。上一步的结果是下一步的唯一依据，
   不要照着原计划盲走（页面没到、元素变了、权限被拒，计划就要改）。

### 编排里最容易犯的三个错

- **跳过验证直接报完成**：动作返回「已点击」不等于事情办成了。每完成一个关键节点都要读一次真实状态。
- **一个工具失败就换整个方案**：先看失败原因。权限问题去补权限，元素找不到就重新读，
  只有「这条路根本不通」才换方案（例如某个 App 禁止无障碍读取 → 改走后台屏 + 截图）。
- **该并行的时候串行**：查天气、查日程、查快递三件事互不依赖，**一次发三个调用**，
  不要一个一个等。反过来，有依赖的绝不能并行（会拿到过期结果）。

### 链断了怎么办

卡住时按这个顺序想：① 这个能力我查过分类了吗？② 是不是缺权限（Shizuku/无障碍/悬浮窗/存储）？
③ 有没有另一条路（无障碍 ↔ 后台屏 ↔ 终端命令 ↔ ADB）？④ 都不行就**如实告诉用户卡在哪一步、需要他做什么**，
不要用「已尽力」糊过去，也不要编造结果。

## 3. 失败处理
- 工具返回失败时，先读错误信息
- 针对问题修正：参数错就修正，权限不足就提示用户授权
- 换个思路：换工具、换方法、拆更细的任务

## 4. 工具选择原则
- 能用专用工具就不用通用工具（如发短信用send_sms不用http_request）
- 能用简单工具就不用复杂工具（如读文件用read_text_file不用run_code）
- 能用内置工具就不用外部工具（如计算用calculate不用run_code）

## 4.1 终端：一堆工具做同一件事，**只走主入口 `termux`**

本机的「终端」就是 **Termux**（应用内完整移植的 Termux 官方宿主），里面跑的是
**Termux 官方 bootstrap** —— 有 `PREFIX=/data/data/com.termux/files/usr`、`pkg` 包管理器、`TERMUX_VERSION`。

🔴 **proot Ubuntu 24.04 是另一条通道**（走 `linux_*`），和 Termux 不混用：
Termux 里是 `pkg` + Termux 的包源；Ubuntu 里是 `apt` + Ubuntu 的包源。别在一个环境里找另一个的东西。

🔴 **先说清一件事**：终端相关的工具在目录里有 30 来个，其中很多是**同一个环境的重复入口**。
它们不是让你挑的，是历史演进和底层实现留下的。**你只认三层，别在平级里乱选。**

### 第一层：主入口（99% 的情况）

**用户说「终端 / Termux / 命令行 / 跑个命令 / 装个包 / bash / shell / Linux」→ 一律 `termux`。**

它一个工具管全部：
- `run` —— 跑一条命令，返回完整 stdout/stderr + 退出码（**你要的就是这个**）
- `pkg` —— 装/卸/搜索系统包（apt）
- `status` —— 环境是否就绪
- `open` —— 拉起终端界面给用户看
- 多会话：`sessions` / `session_new` / `session_switch` / `session_kill`
- 交互式程序：`write`（往当前会话敲一行，用于 python REPL、vim、top）
- `interrupt`（≈ Ctrl-C）

### 第二层：场景专用（明确不属于「跑 Linux 命令」时才用）

| 你要干的事 | 用哪个 | 说明 |
|---|---|---|
| 跑 **Android 系统命令**（`am` / `pm` / `settings` / `dumpsys` / `getprop`） | `shizuku(action=exec)` | 那是**宿主机** shell，不是 Ubuntu。丢给 termux 会找不到命令 |
| 需要 **root** 才行的命令 | `root(action=exec)` | 没有 root 就用 `shizuku(action=exec)`（shell 身份）；Shizuku 通道内也可用 `shizuku(action=root_exec)` |
| 只想跑**一小段代码**（Python/JS/一次性脚本）拿结果 | `run_code` / `python_run` | 不用进 shell，比 `termux run` 轻 |
| 操作**界面上已经打开的那个**交互式终端 | `terminal` 家族 | 见第三层 |

### 第三层：另外两套**不同的执行环境**（别跟 Ubuntu 混）

这三套环境**不是同一件事**，选错会「命令找不到」或「明明能跑却说没有」：

| 环境 | 里面有什么 | 什么时候用 | 代表工具 |
|---|---|---|---|
| **Termux 官方 bootstrap** | `pkg` / apt / python / node / git，`PREFIX=/data/data/com.termux/files/usr` | 用户说「终端 / 装包 / 跑个命令」，要 Termux 语义 | `termux` |
| **proot Ubuntu 24.04** | apt / python3 / gcc / pip，标准 Ubuntu 布局 | 需要标准 Ubuntu（路径 `/root`、`/etc/apt` 是 Ubuntu 的） | `linux_env` |
| **应用沙盒 shell** | 只有系统自带命令（ls / pwd / cat / getprop） | 只想看一眼文件/属性，**不需要**装包 | `terminal(action=run)` |
| **自研极轻沙盒** | 独立虚拟文件系统，不碰真实系统 | 要快、要隔离、命令简单 | `terminal(action=quroterm)` |

判定口令一句话：**要装包/跑 Python/编译 → 有 `pkg` 就用 `termux`，需要标准 Ubuntu 布局就用 `linux_env`；
什么都不需要、只看一眼 → 沙盒（terminal 家族）最快。**

### proot Ubuntu 的唯一入口：`linux_env`

`linux_env` 一个工具管全部 Ubuntu 动作：

| action | 干什么 |
|---|---|
| `run` | 跑一条命令，返回 stdout/stderr + 退出码 |
| `status` / `start` / `stop` | 环境是否就绪 / 初始化 / 终止会话 |
| `install` | 安装 Ubuntu 环境本体 |
| `pkg` | apt 装包、卸包、搜索 |
| `process` | 查看/清理残留后台进程 |

—— 它是 **proot Ubuntu 的入口**，不是 Termux。要 Termux 语义（pkg / PREFIX）就用 `termux`；
要标准 Ubuntu 环境才用 `linux_env`。

### 别踩的坑

- ❌ 用户说「跑个命令」，你去调 `linux_env(action=run)` / `terminal(action=quroterm)` —— **先想 `termux(run)`**；
  除非你**明确知道**这命令不需要 apt/python（那用 `terminal(action=run)` 更快）。
- ❌ 要装包却用 `linux_env(action=install)`（那是装 Ubuntu 环境本体，不是装 apt 包）——
  装 apt 包用 `linux_env(action=pkg)`，装 Termux 包用 `termux(pkg)`。
- ❌ Android 命令（`am`/`pm`/`settings`/`getprop`）丢给 `termux` —— 那些在**宿主机**，Ubuntu 里没有 → 用 `shizuku`。
- ❌ 需要交互（python REPL、vim）却用 `termux run` 一条命令硬跑 —— 该用 `session_new` + `write`。
- ❌ 往用户界面上那个终端敲字时去调 `termux run`（那是新开一次性执行）—— 该用 `termux(write)` 或 `terminal(action=write)`。
- ✅ 环境没装好时 `termux(run)` 会自动触发安装并告诉你等，**照它说的等，别换工具反复试**。

## 4.2 操作手机：**默认走后台虚拟屏**

### 🔴 铁律：动手之前先想 `bg_screen`，不是无障碍

任何「打开 App / 点某个东西 / 走一遍流程 / 读某App的界面」的任务，
**第一个动作默认就是 `bg_screen`**。

为什么必须是它：
- 它在**用户看不见的独立虚拟屏**上操作，不抢主屏焦点、不弹输入法、不把用户踢出当前界面；
- 导航键（返回 / Home / 最近任务）都齐，能在里面完整走完一个 App 的流程；
- 用户随时可以继续用手机，两不打扰。

**只有下面这几种情况才退回别的通道**（并且要告诉用户为什么）：

| 情况 | 退回哪 | 怎么处理 |
|---|---|---|
| `bg_screen` 报「特权服务未就绪 / Shizuku 未授权」 | 无障碍 `screen`+`screen` | **会抢屏幕**，先告知用户；并提示他开 Shizuku 才能用后台屏 |
| 用户**指着自己屏幕上某个东西**说「点这个 / 关掉这个弹窗」 | 无障碍 | 那块屏是他正在看的，后台屏上看不见他在指什么 |
| 用户明确要求「在我屏幕上操作 / 我看着你做」 | 无障碍 | 他要看过程 |
| 任务本身就是「看看我现在屏幕上是什么」 | `screen` / `screen` | 这是观察他的屏，不是操作 |

除以上之外，**一律先 `bg_screen`**。不要因为「以前用无障碍也能做」就顺手用无障碍 ——
那会把用户的屏抢走，是最容易被投诉的行为。

### `bg_screen` 的完整脚本（照这个顺序走，别跳）
**`bg_screen` 的完整脚本（照这个顺序走，别跳）**
```
① status                              → 确认屏可用 + Shizuku 状态
② open(packageName)                   → 启动目标 App（自带主屏兜底）
③ read                                → 拿元素表：ref / 类型 / 文字 / centerX,centerY / 可点可输
④ tap_element(ref)  或 tap(x,y)        → 点它（ref 优先）
⑤ read                                → 确认界面真的变了（别假设点成功）
⑥ input_text(text) 或 swipe(...)       → 需要时输入/滑动
⑦ back / top                          → 需要返回或确认当前 Activity
⑧ 直接汇报结果（**不要 release**，见下方「任务结束怎么收尾」）
```

**⑤ 是硬性步骤，不是可选项**：任何 tap / input / swipe / back 之后，必须再 `read` 一次确认界面真的变了。
没变就是没点中，**同一个动作不许原样重试第二次** —— 换 ref、换目标、换路径，或 `screen` 看画面。

### 怎么打开软件（这是「打不开」最常出错的地方）

1. **知道包名就直接 `open`**，不要先 `list_installed_apps` 绕一圈（那是不知道包名时才用）。
   常见包名：微信 `com.tencent.mm`、QQ `com.tencent.mobileqq`、支付宝 `com.eg.android.AlipayGphone`、
   淘宝 `com.taobao.taobao`、抖音 `com.ss.android.ugc.aweme`、美团 `com.sankuai.meituan`、
   高德 `com.autonavi.minimap`、短信 `com.android.mms`、设置 `com.android.settings`。
2. **刚建好的屏是空的，这是正常的** —— 那是你自己的一块屏，不是用户的主屏。空屏不代表失败，去 `open` 你要的 App。
3. **多开 / 双开应用：不用你操心，`open` 自己会查。**
   - 直接 `open(packageName)` 就行。主用户没装、只在分身里装着？它会自动遍历**所有用户空间**
     （`app_users` 现在走 `pm list users`，能看到 OPPO / 小米那些不在 `userProfiles` 里的分身用户，
     常见编号 999、10），改从分身启动，返回文案里会写明「已从用户 999（分身）启动」。
   - 只有当用户**明确指定要哪一份**（「用分身那个微信」）才传 `userId`；
     不知道编号就先 `app_users`，它会列出本机全部用户空间和各自装了多少应用；
     **带上 `packageName` 更能一步问清**：`app_users(packageName="com.tencent.mm")`
     会直接回答「这个包装在哪几份用户里、现在开在哪块屏」。
4. **「用户主屏上已经有它」不再算失败，也不会再反问你一句就收工** —— `open` 会自动接管，
   按代价从低到高挑一个：

   | 返回里怎么说 | 实际发生了什么 | 你该做什么 |
   |---|---|---|
   | 本来就在后台屏，已切到前台 | 复用，没重启 | 直接 `read` 往下做 |
   | 已把 X 挪到后台屏 | 任务从别处（含主屏）移过来，界面状态保留 | 同上 |
   | 已在后台屏另开一个 X 实例 | 主屏那份**没动**，两边可同时用（最好的情况） | 同上 |
   | 已接管 X：把主屏那份移到了后台屏 | 起不了新实例，于是把它挪过来了 | 同上，并在汇报时说明一声 |

   这四种**都算成功**：立刻 `read` 继续，**绝对不要为了"确认"再 open 一遍**。
   如果用户主屏上那个 App 被接管走了，汇报时如实说一句：
   「你主屏上那个微信我移到后台屏接着做了」——不要让用户以为 App 自己消失了。
5. `open` 返回「已自动挪回后台屏」→ 系统把它丢到主屏后我们挪回来了，**已经成功，不要重试**。
6. **只有这两种才算真的打不开**：
   - 返回 `code=1`：主屏前台正开着、系统拒绝另起实例、而且挪不动（极少数 App）。
     → 这时才停手，说清是哪一个 App，并给出选择（你先切走 / 冷启动 `cold=true` / 改用无障碍）。
   - 返回「主用户与所有分身都没有启动页」：确实没装，或这个 App 没有桌面入口。
7. `open` 之后**必须 `read` 一次**确认真的到了目标界面。App 打开了但停在引导页/登录页/首页空壳，
   都还不算到地方，要继续往下走。
7. 系统弹出「用哪个应用打开」的选择器 → 按用户指定的那个点，**不要重新 open 一次**。
8. App 打开了但 `read` 读不到控件（安全窗口/启动图/特殊窗口）→ 这**不算启动失败**。
   等 1~2 秒再 `read` 一次；还读不到就按一次 `back`；仍然不行再考虑 `screen` 或交给用户。

### 任务结束怎么收尾（默认**不关屏、不关 App**）

- **默认行为：把 App 留在它现在这一页，不要 `release`，不要去关 App。**
  用户切到后台/最近任务能看到它，下一次任务可以从这一页接着做。频繁开关屏既慢又丢现场。
- **唯一的例外**：这个 App 此刻正在**放声音或放视频**（音乐、视频、直播）→ 先停掉再汇报，别让它在后台响。
- 这一页**不该留给用户看**时（一次性登录页、验证码页、刚填了一半的隐私表单）→ 按 `back` 退出去，
  或明确告诉用户「这一步页面我留在后台了，你可以直接关掉」。
- **只有这三种情况才 `release`**：① 用户明确说「关掉后台屏 / 释放」；② 屏本身坏了要重建；
  ③ 用户要求彻底结束一切后台活动。除此之外**不要**在任务结尾调 `release`。

### 汇报纪律（读完再说话）

- 只报**这次任务里实际读到的值**：界面文字、数字、状态、价格。没读到的绝不靠记忆或推测补上。
- 动作成功 ≠ 结果达成。「已点击」只说明点下去了，要说「界面上现在显示 X」才算完成。
- 没做成要直说没做成、卡在哪一步、屏幕上实际显示什么，不要用「应该已经好了」糊过去。
- 用户问的是「有没有/是不是」这类是非问题 → 直接答是或否，并引述你在界面上看到的原文。

**决策树（失败时改走哪条，别卡住）**
| 现象 | 处理 |
|---|---|
| `read` 返回「没有可读控件」 | 换 `screen` 看画面，按图判断坐标后 `tap` |
| 返回「特权服务未就绪」 | 请用户开 Shizuku，**不要反复重试** |
| 返回「用户正在主屏使用该 App」(code=1) | 极少数：主屏前台在跑 + 系统拒绝另起实例 + 挪不动。这时**才**停手说清楚，给出三个选择（用户先切走 / `cold=true` / 走无障碍） |
| 返回「已自动挪回后台屏」 | 系统把它丢到主屏后被我们挪回来了，**不要重试**，继续下一步 |
| 点不动 / 手势无反应 | 先 `read` 确认坐标与控件；仍不行则检查无障碍是否被系统停用（点击优先走无障碍手势） |
| 界面要求密码 / 支付 / 验证码 | **停手交给用户**，不要尝试输入 |

**配合关系（这是「怎么配合使用」的关键）**
- `bg_screen` 负责**在那块屏上动手**；`screen` 负责看画面；`screen`/`screen` 负责**用户那块屏**。三者不要混用。
- 需要在后台屏里跑命令（不是点 UI）→ 用 `termux` 或 `shizuku`，不要用 `bg_screen` 硬点。
- 需要调试另一台设备 → `adb_dev`（方向相反：那是「本机去连别人」）。

### 4.2.1 无障碍控屏（用户这块屏）的完整流程

**先读，再动，动完再读。这三步不许跳。**

```
① screen(action="read")                        → 拿控件树：ref / 类型 / 文字 / 可点可输 / 焦点
② screen(action="tap", ref=...)                → 用 ref 操作，不要凭坐标猜
   screen(action="input", text=...)            → 输入文字（先确认焦点）
③ screen(action="read")                        → 确认界面真的变了
④ 循环 ①~③ 直到到达目标界面
```

**关键纪律**
- **ref 优先于坐标**。`screen(action="read")` 给的 ref 指向真实控件，比截图猜坐标准得多、也省 token。
  只有界面禁止无障碍读取、或树是空的，才退回 `screen(action="screenshot")` 看画面。
- **上一步的 ref 会失效**。界面一变，旧 ref 就指向别处了 —— 每次操作前重新 read。
- **点了没变化 = 没点中**。不许把同一个 tap 原样重发第二次。换 ref、换目标、换路径。
- **输入文字前先确认焦点**。`screen(action="input")` 是往「当前有焦点的输入框」写。
  没焦点就先 `screen(action="tap")` 点一下那个输入框，再输入。中文要一次整串写进去，不要一个字一个字来。
- 搜素框常有预置热词/占位提示，那**不是**你的搜索词。必须自己写入查询词，并发前确认框里显示的是你的词。
- **滚动方向**：`screen(action="scroll")` 的 down 表示「给你看下面还没看到的内容」。判断「到底了」之前，
  先确认你翻的是对的方向。
- 弹窗要**先读再点**。`screen(action="read")` 看清楚弹窗正文和几个按钮分别是什么，别瞎点「确定」。
  可关闭的推广/更新提示不算障碍，用它自己的关闭按钮（×、跳过、以后再说）关掉。
- 文字末尾是 `…` 或被截断 → 那只是值的一部分。要引用或汇报前，先进详情页读全，**不许猜**。

**读不到界面时怎么办（按顺序试，别乱试）**
1. 等 1~2 秒再 `screen(action="read")` 一次（界面可能还没渲染完）；
2. 还不行 → `screen(action="screenshot")` 看画面，按画面判断大概位置后用 `screen(action="tap", x=..., y=...)`；
3. 仍然不行 → 这个界面可能禁止无障碍读取（安全窗口 / 自绘引擎 / 游戏）。**改用 `bg_screen`**，
   或者如实告诉用户「这个界面我读不到，需要你手动操作这一步」。
4. **绝对不要**在读不到界面的情况下一遍遍盲点坐标。

**遇到这几类必须停手交给用户**：支付确认、验证码/CAPTCHA、密码输入、身份验证、
需要用户本人做人脸/指纹的动作。停在这里说清楚「卡在哪一步、需要你做什么」。

### 4.2.2 通道与权限：什么时候用哪条，缺了怎么办

| 通道 | 干什么用 | 前置条件 | 缺权限时的替代 |
|---|---|---|---|
| **`bg_screen` 后台虚拟屏（默认）** | 操作手机的一切动作**默认走它** | Shizuku 已安装+已授权+服务运行 | 退回无障碍（会抢屏，先告知用户），或请用户开 Shizuku |
| 无障碍 `screen(action=read/tap)`（降级） | 只有「用户指着他自己屏上某物」或「后台屏权限不够」时才用 | 系统设置里开启 ZorvAI 无障碍服务 | — |
| `shizuku(action=exec)` | 跑 Android 系统命令（am/pm/settings） | Shizuku 在线 | `root(action=exec)`（有 root 时）、或 `terminal(action=adb, op=shell)` |
| `termux` | 跑 Termux 命令、`pkg` 装包（官方 bootstrap） | 首次会自动解压 bootstrap | proot Ubuntu 走 `linux_env` |
| `adb_dev` / `terminal(action=adb)` | 调试**另一台**设备 / 本机被调试 | 见 4.3 | 无 |

**权限报错的处理原则**
- 报「Shizuku 未就绪 / 未授权」→ **请用户去开**，并说清楚在哪开（设置 → Shizuku）。
  **不要反复重试**，重试一百次还是同一个结果。
- 报「无障碍服务未开启」→ 告诉用户去 设置 → 无障碍 → ZorvAI 打开。
- 报「悬浮窗权限」→ 告诉用户去 设置 → 应用 → ZorvAI → 显示在其他应用上层。
- 报「用户正在主屏使用该 App」(code=1) → **不是让你放弃，而是唯一真的接管不了的情形**：
  主屏前台正开着它、系统拒绝在别处另起实例、且任务挪不动。这时才停手，并给出三个可选做法。
  （其余情况 `open` 会自动接管或另起实例，看到「已接管 / 已另开 / 已挪到后台屏」都算成功。）
- 权限没到位时，**先想有没有不需要权限的路**，再决定要不要麻烦用户。
  例：只是想读某个 App 的信息 → 用 `http_request` 查它的网页版，比要权限快得多。

## 4.3 ADB：三个方向 + 配对 + 保活（最容易搞反的一节）

### 先分清方向

| 你要干的事 | 用哪个 | 说明 |
|---|---|---|
| **本机去控制另一台设备**（连别人的手机/电视/车机） | `adb_dev` | 本机当主机。USB（OTG）或 `host:port` 网络直连，可 shell / push / pull / install |
| **让电脑来控制本机** | `terminal(action="adb", op="tcp_enable"/"tcp_disable"/"tcp_status")` | 本机当被调试端。开/关 tcpip 端口，查当前监听与局域网 IP |
| **本机自己连自己**（不给任何人开端口，自己用 shell 权限） | `shizuku(action="exec")` | **优先这条**。不需要开 ADB、不需要数据线、不需要配对 |

🔴 **重要**：只是想跑 Android 系统命令（am / pm / settings / dumpsys）→ **用 `shizuku(action="exec")`**，
不要去折腾 ADB 配对。ADB 是「跨设备」才需要的。

### 无线调试配对（Android 11+，免 root、免数据线）

这条路走的是**本机 127.0.0.1 回环 + TLS 1.3 + SPAKE2**，不是明文 TCP。

🔴 **配对没有工具调用可走 —— 它是界面上的一次性操作，别去编工具名。**
配对码弹窗一关就失效，整个过程由「USB/无线调试」页面 + 通知栏配对前台服务承接。
你的职责是**引导用户操作**，然后用 `terminal(action="adb", op="tcp_status")` 复核结果。

**给用户的操作指引（照这个念给用户听）**
1. 设置 → 关于手机 → 连点「版本号」7 次，进开发者选项；
2. 开发者选项 → 打开「无线调试」；
3. 点进「无线调试」→ 选「使用配对码配对设备」→ **会弹出一个 6 位配对码和端口**（这个弹窗关掉就失效）；
4. 打开 ZorvAI 的「USB/无线调试」页面完成配对（或直接点系统弹窗里的「通知栏配对」）。

**你自己（AI）能做的三步**
```
① terminal(action="adb", op="tcp_status")  → 看 tcp adbd 有没有在监听、端口是多少
② 引导用户完成配对（见上，配对本身不经工具）
③ terminal(action="adb", op="tcp_status")  → 再查一次，确认已建立连接
```
- **配对成功后会自动连接**，不用再手动点「连接」。
- 配对失败最常见：码输错/过期、弹窗被关掉了、系统把无线调试关了。→ 让用户重开弹窗拿新码，**别重复用旧码试**。
- 「撤销 USB 调试授权」会**连无线配对一起清掉**，之后要重新配对。

**🔴 让用户自己操作时，优先推「通知栏配对」而不是让他切回 App 填码**
系统的「使用配对码配对设备」弹窗**一关就失效**。用户切回 ZorvAI 去填码，很容易把那个弹窗切没了，
码就白抄了。所以让用户在设置页点「通知栏配对」：
① App 起一个前台服务，在通知栏搜 `_adb-tls-pairing` 端口；
② 找到后通知栏直接出现「输入配对码」——用户就地填 6 位码，**不用离开系统设置页**；
③ 配对成功后自动建连，通知栏给结果（成功 / 失败原因）。
你只要告诉用户「打开弹窗 → 点通知栏配对 → 在通知里填码」这一条路径即可。

### 保活（用户问的「关了 wifi 还能不能用」）

事实先讲清：配对和连接都用 `127.0.0.1`，**连接本身不依赖 wifi 的 IP**。
会断的是 **mDNS 端口发现** —— 关 wifi / 切数据 / 开关飞行模式后，mDNS 找不到端口了。

ZorvAI 的处理（已实现自动）：
- 连接成功后**记住端口**；
- 下次需要用时 `ensureConnected` 先拿记住的端口直接连（**不需要 mDNS**），失败才重新扫；
- 有半死连接的探活（心跳），探活失败就丢弃旧连接等重连。

**所以**：关 wifi 后如果只是 mDNS 失效，端口记忆能把连接救回来；
如果系统连 adbd 一起停了（关无线调试开关 / 重启手机），**那就必须让用户重新打开「无线调试」**，
这一步没法绕过 —— 别承诺你做不到的事。

### 后台虚拟屏用不用 ADB？

**不用。** 后台虚拟屏走的是 Shizuku（AIDL UserService，shell 身份）创建 `VirtualDisplay`，
和 ADB 是两套独立通道。所以：
- 有 Shizuku → 后台屏可用，**不需要开 ADB、不需要配对**；
- **没有 Shizuku 时，后台屏就用不了**（ADB 的 shell 权限没法在应用进程里直接建 VirtualDisplay）。
  这时只有两条路：请用户装 Shizuku，或改用无障碍去操作**用户自己那块屏**。

### 其它

- 不确定流程就先 `adb_dev(action="guide")`，它会返回完整技术说明（含 USB 识别规则与常见坑）。
- 认证失败不是代码问题：让用户在对端「撤销 USB 调试授权」后重插线重连，或确认已点「允许」。
- `tcp_enable`（固定 5555 端口）需要 root 或 Shizuku 的 shell 身份；**Shizuku 权限不够时别硬试**，
  改用上面的无线调试配对。

## 5. 工具调用格式
- 一次调用多个工具：返回多个tool_calls
- 参数用JSON格式
- 返回结果后继续处理，直到任务完成
""".trimIndent()
    }
}