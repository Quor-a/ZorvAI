package com.ai.assistance.quro.core.tools

/**
 * 工具「多用途 / 多说法」提示集。
 *
 * 设计目的：解决「AI 只在用户用了固定训练样本措辞时才懂，换个说法（同一功能多用途）就失效」的问题。
 * - [TOOL_USAGE_HINTS]：每个工具对应「用户可能的各种口语说法 / 多用途场景」，注入系统提示词菜单，
 *   让模型把任意措辞映射到正确工具，而不是只认死板关键词。
 * - [buildToolUseDirective]：强指令，要求模型「必须真正调用工具执行、不要只描述、按意图而非关键词匹配」。
 *
 * 注意：本集只覆盖默认核心集工具（与 QuroTool.coreSpecs 的 coreNames 严格对齐），
 * 动态 UI 动作工具（ui_open_* / ui_toggle_* 等）由 QuroToolsUiActions 自行描述，不在此枚举。
 */
object QuroToolUsageHints {

    /** 每个工具名 → 一句「常见说法 / 多用途」提示（注入系统提示词，增强灵活映射）。 */
    val TOOL_USAGE_HINTS: Map<String, String> = mapOf(
        // ── 2026-11-11 第二批 8 族合体：一个入口 + action，别再找散装旧名 ──
        "shizuku" to "「用 Shizuku 跑条命令」「冻结/解冻某 App」「静默安装 APK」「查 Shizuku 授权」都调用，action=exec/freeze/unfreeze/install/status",
        "root" to "「用 root 执行」「查 Root 是否可用」都调用，action=exec/status",
        // 🔴 2026-11-12 补：这 6 个合体族此前**没有提示条目**，而它们名下原本挂着
        // 6 组旧名（mcp_* / cms_* / auth_service_* / linux_* / 定时任务 / 剪贴板）的提示——
        // 旧名降级后那些条目永远查不到，用户的口语说法整块失效。这里把触发性说法补齐。
        "mcp" to "「看看连了哪些 MCP 服务器」「MCP 服务列表」「这个 MCP 服务器有哪些工具」「列一下外部工具」「调用外部 MCP 的 XX 工具」「让连着的服务器干 YY」「把我的 MCP 服务器部署到本机」「起一个本地 MCP」「停掉那个本地 MCP 服务」「卸载部署」「本机部署了哪些 MCP」「本地服务列表」都调用，action=servers/list_tools/call/deploy/undeploy/list_local",
        "cms" to "「我装了哪些能力模块」「CMS 模块列表」「调用 XX 能力模块做 YY」「让模块执行」「CMS 引擎状态」「引擎部署好没」「引擎就绪了吗」「引擎拉起了哪些服务」「那个模块部署成功没」「看模块的运行日志」「拿模块的返回结果」「按顺序跑这一组命令」「把这个模块推到终端跑」「从终端卸掉那个模块」都调用，action=list/call/status/logs/result/run_dag/deploy/undeploy/toolbox/engine_status",
        "auth_service" to "「把 XX 平台的 token 存起来」「记录这个 API key 给以后用」「我存了哪些授权」「列一下凭证」「删掉 XX 的授权」「移除那个 key」都调用，action=add/list/remove",
        "linux_env" to "「在 Linux 环境跑 XX」「执行个 Linux 命令」「装一下 Linux 环境」「初始化 Ubuntu」「启动 Linux 环境」「关掉 Linux 环境」「Linux 环境状态」「装好了没」都调用，action=start/stop/status/run/install/pkg/process",
        "task_schedule" to "「每天定时提醒我喝水」「下周三自动发条消息」「10 分钟后执行某个动作」「未来某时刻自动触发」「我定了哪些定时任务」「看看待办的提醒」「自动化列表」「取消那个定时提醒」「删掉周三的任务」都调用",
        "clipboard" to "「我刚复制了什么」「剪贴板里是啥」「读出我复制的内容」「帮我把这段复制下来」「存到剪贴板」「把这个文本放剪贴板」都调用",
        "terminal" to "「跑终端命令」「开个会话」「无线调试」「tmux」「发按键」「看终端输出」都调用，action=exec/run/write/sessions/adb/tmux/keys/monitor（也说：「在终端跑条命令」「执行 shell」「df -h 看一下」「关掉终端」「杀掉那个 shell 进程」「终端还活着吗」「当前会话状态」）",
        "keyboard" to "「在别的 App 输入框打字」「发出去」「按回车」都调用，日常发消息一次用 action=type_and_send",
        "aci" to "「让第三方 App 帮我做事」「打开真实网页」「发消息」「列能力」「管本地 ACI 服务」都调用，action=call/list/list_and_call/server_status（也说：「这个 App 能做什么」「XX 有哪些可调用功能」「能调起它的什么页面」「用 XX App 分享到朋友圈」「调起 XX 的扫一扫」「让 XX 打开某页面」）",
        "screen" to "「读屏」「点一下」「滑一下」「输入文字」「返回桌面」「截图」「看屏幕」「拍照」「录屏」都调用，action=read/tap/swipe/input/global/screenshot/analyze/photo/record_start（也说：「截个图」「截图保存」「把屏幕截下来」「截屏分析/发视觉模型」）",
        "permissions" to "「查权限模式」「开关 WiFi/蓝牙/手电」「调音量/亮度」「锁屏」「禁用摄像头」都调用，action=check/policy/status/lock/camera/flashlight（也说：「调高音量」「静音」「音量调到最大」「把声音关小」「调亮屏幕」「亮度调高」「开自动亮度」「屏幕太暗了」「开WiFi」「关掉WiFi」「WiFi开一下」「开蓝牙」「关蓝牙」「蓝牙开一下」「下拉通知栏」「收起通知」「清掉通知」「开飞行模式」「关飞行模式」「飞行模式开一下」「自动旋转屏幕」「锁竖屏」「锁横屏」「锁住手机」）",
        "memory" to "「记住这件事」「查记忆」「找经验」「知识库」「按原话找该用哪个工具」都调用，action=save/search/recall/query/kb_search/rag/discover",
        // ── 基础 ──
        "get_current_time" to "用户说「现在几点」「今天星期几」「现在什么日期」「距离某天还有多久」「现在是上午还是下午」都调用",
        "get_device_info" to "「我手机什么型号」「系统版本多少」「内存/存储还剩多少」「处理器是什么」都调用",
        "calculate" to "任何算数/单位换算/百分比/利率/打分都调用，不要心算瞎猜",

        // ── 系统 / 设备 ──
        "get_battery" to "「电量还剩多少」「在充电吗」「电池健康度」都调用",
        "get_wifi_info" to "「连的什么 WiFi」「WiFi 名称/信号」「IP 地址多少」都调用",
        "get_network_info" to "「现在用的流量还是 WiFi」「网络类型」「有没有联网」都调用",
        "get_sensors" to "「有没有光线/陀螺仪/加速度传感器」「传感器列表」都调用",
        "vibrate" to "「震一下」「手机抖一下」「提醒我（震动）」都调用",

        // ── 剪贴板 ──

        // ── 应用管理 ──
        "list_installed_apps" to "「我装了哪些 App」「列一下应用」「有没有装 XX」都调用",
        "launch_app" to "「打开微信」「启动相机」「帮我开 XX 应用」都调用（也说：「打开那个绿的聊天软件」「找一下能扫码的 App 打开」「名字里有'shop'的打开」「打开XX应用」「启动XX」）",
        "get_package_name" to "「XX 应用的包名是什么」「这个 App 的 package」都调用",

        // ── 通知 / 蓝牙 / 手电 ──
        "get_active_notifications" to "「我有什么通知」「未读消息有哪些」「状态栏提醒」都调用",
        "get_bluetooth_status" to "「蓝牙开了吗」「连了什么设备」「蓝牙状态」都调用",

        // ── 通信 ──
        "read_sms" to "「读一下我的短信」「有没有验证码」「看下 XX 发的消息」都调用",
        "send_sms" to "「发短信给 XX 说…」「给我妈发条信息」都调用",
        "read_contacts" to "「我通讯录里谁」「XX 的电话多少」「找下联系人」都调用",
        "execute_intent" to "多用途：既能「打开某 App 的某个深层页面/Activity」也能「启动某 Service/广播接收器」也能「用隐式 Intent 调起系统功能（如拨号/分享面板）」——按用户真实意图选用",
        "send_broadcast" to "多用途：既能「发一条系统/自定义广播通知其他组件」也能「触发本机某个接收器动作」——需要向系统或其他 App 广播事件时调用",

        // ── 日历 / 位置 ──
        "read_calendar" to "「我今天的安排」「最近有什么日程」「周五有空吗」都调用",
        "write_calendar" to "「帮我记一下明天下午三点的会」「加个日程」「提醒我周五去打针」「把这场会议写进日历」都调用",
        "get_location" to "「我在哪」「当前位置」「经纬度多少」都调用",
        "geocode" to "「这个地址在哪」「帮我把'XX路1号'转成坐标」「导航到 XX 怎么走（先定位）」都调用",

        // ── 文件（只读） ──
        "file_read" to "「读一下这个 txt」「打开那个日志看看」「把 md 内容念给我」「读取这个文件」「读一下工作区里的 XX 文件」都调用（root=app|internal|workspace）",
        "list_dir" to "「看看 Download 里有什么」「列一下这个文件夹」「目录里有哪些文件」「工作区里有什么」都调用（root=app|internal|workspace）",

        // ── 文件写/改/删 ──
        "file_write" to "「帮我建个文件写点东西」「把这段存成 xx.txt」「生成配置文件」「把代码存到工作区」都调用（root=app|workspace）；工作区动作也可统一走 workspace。",
        "delete_file" to "「删掉这个文件」「清掉 XX」「移除那个日志」都调用",
        "make_directory" to "「新建个文件夹」「建个目录叫 XX」都调用",
        "move_file" to "「把这个文件挪到 XX」「移动文件」「重命名并移动」都调用",
        "copy_file" to "「复制这个文件到 XX」「备份一份」都调用",
        "file_info" to "「这个文件多大」「创建时间」「文件属性」都调用",

        // ── 工作区（QuroWorkspace，统一入口） ──
        "workspace" to "「把这段代码保存到工作区」「写到我的工程里」「工作区里有什么」「读一下工作区的 XX 文件」「预览这个文件」「创建文档」「播放工作区里的音乐」「打开这个PDF」都调用；用 action 区分：write / read / list / doc / render / write_and_render / doc_view / media。doc 与 write_and_render 自带预览，不要拆成多次调用。",

        // ── 网络 / Web ──
        "http_request" to "「调一下这个接口」「发个 GET/POST 到 XX」「请求这个 URL」「对接某个 API」都调用",
        "open_web" to "「打开百度」「访问 XX 网址」类请求：若系统提示里已声明【默认 ACI 应用已设置】（通常是受控浏览器），【优先用 aci(action=call) 的 browser_open】（真实可交互、能点进去）；只有在未设默认 ACI 应用时才用 open_web 被动展示（仅供查看，AI 无法点击交互）。真正操作网页（点链接/填表/翻页）一律用 aci(action=call) 的 browser_open→browser_elements→browser_action。",
        "ai_browser" to "多用途：既能「后台联网搜索资料/自动研究」也能「打开网页被动展示」也能「下载网页里的文件」——需要 AI 自动上网查资料/下载时调用（open 仅展示、不能点击；真正点击/填表/进入子页面请用 aci(action=call) 的 ZorvAI 受控浏览器 browser_open→browser_elements→browser_action）",
        "browser_act" to "【内置浏览器 AI 真·交互工具】接管对话框当前正在显示的内置 WebView，能真正点击/填表/读取 DOM/截图/抓包——与 open_web / ai_browser 的 open（仅被动展示、点不进去）不同，browser_act 能真正操控页面。标准流程：action=open(网址或关键词，未开浏览器时自动打开并等待挂载) → action=status(确认 loaded=true) → action=snapshot(拿可交互元素的 quro-id 列表) → click(id)/fill(id) 操作 → read(selector) 回读 → 必要时 scroll/wait。需要像人一样操作内置浏览器网页（点链接/填表单/翻页/读点击后内容）时优先用 browser_act；化小窗状态下同样可用。",

        // ── 代码执行 ──
        "run_code" to "多用途：用户给的任意代码片段（Python/JS/Shell 等）都能跑；「算一下这段」「跑个脚本」「帮我试试这段代码」都调用",

        // ── 终端 ──

        // ── TTS ──
        "speak" to "「读给我听」「念一下这段」「大声朗读」「用语音播报」都调用；也可用于把 AI 回复转语音（也说：「别念了」「停下朗读」）",

        // ── 闹钟 ──
        "alarm_clock" to "「定个明早 7 点闹钟」「3 小时后提醒我」「倒计时 5 分钟」都调用（action=set，type=alarm|timer）。（也说：「倒计时5分钟」「设个计时器」「煮面计时」）",

        // ── 定时任务 / 自动化 ──

        // ── 记忆库 ──

        // ── AI 经验闭环 ──

        // ── 知识库 ──
        // 🔴 2026-11-07 修复重复 key：mapOf 同名后者覆盖前者，两条引导各丢一半，合并为一条。

        // ── 文档生成 ──
        "aiwps_create" to "「帮我生成一份 Word 周报」「做个 Excel 表格」「出个 PPT 关于 XX」都调用（本地生成 Office 兼容文件）",

        // ── 可视化组件（富卡片）──
        // 🔴 这四个是「在对话框里直接把结果画出来」的唯一正路。
        // 历史提示词里把卡片挂在 ui_control(action="card"/"widget") 名下，
        // 与真实工具不符 → 模型会调一个不存在的 action，卡片静默不出现。
        // 🔴 2026-11-07：两条 ui_widget 合并，保留完整组件清单（此前被短句覆盖丢失）。
        "ui_widget" to "在对话框里展示单个可视化组件时调用（按钮/开关/滑块/进度/仪表/统计/表格/列表/分段控件/饼图/评分/倒计时/标签页/折叠/表单/筹码/步骤条/甘特/热力/雷达/对比/看板/轮播/时间线/里程碑/文件/代码/终端/日志/二维码/条码/颜色/计数器/面包屑/标签云/徽章/头像组/mermaid 图表/miniapp 网页……）。凡是「结果适合画出来而不是写成文字」都优先用它，别退化成一堆纯文本。富组件卡片一次一张，传组件 spec；旧名 ui_card 等价，kind 字段兼容。要组件名册与字段样例时带 with_catalog=true（原 card_catalog 已并入此参数），要 DSL 规范带 with_spec=true，输出前自检带 with_validate=true。",
        "card_patch" to "已下发过的卡片要改内容时调用（进度刷新、倒计时走字、勾掉待办、改表格单元格、更新统计数字、列表追加），只发改动的字段而不重发整张卡；建议先 describe 拿合法路径。",

        // ── 统一UI控制工具 ──
        "ui_control" to "统一UI控制工具：操控界面每个角落。常见用法：「打开编辑器/终端/工具箱」→ ui_control(action=\"open\", target=\"editor\")；「切换深度思考」→ ui_control(action=\"toggle\", target=\"deepthink\")；「打开模型选择」→ ui_control(action=\"sheet\", target=\"model\")；「新建对话」→ ui_control(action=\"chat\", action_type=\"new\")；「查询状态/更新属性/滚动/聚焦/隐藏/显示/导航/权限控制」→ ui_control(action=\"status\"/\"update\"/\"scroll\"/\"focus\"/\"hide\"/\"show\"/\"navigate\"/\"permission\")。可用 target（action=open 直达界面/能力）：editor/terminal/toolbox/knowledge/cms/aci/about/appearance/soul/memory/permission/model_config/voice/settings/tool_center（工具中心总览）/vispro（可视化编程：Mermaid 源码编辑+实时渲染+导出 SVG）/node_editor（节点编辑器：拖拽节点流，导出 Mermaid）/miniapp（Web 应用：渲染 AI 生成的 HTML/JS Web 应用）； Web 应用也可直接在对话框渲染——优先用 ```miniapp 围栏或 ui_widget(type=\"miniapp\")",

        // ── MCP 客户端 ──

        // ── 第三方授权保险库 ──

        // ── CMS v2 能力模块 ──

        // ── ACI ──

        // ── L1 无障碍控屏 ──

        // ── 屏幕视觉双模感知 ──
        // 🔴 2026-11-07：两条 screenshot 合并，保留 base64 用法说明。

        // ── 系统级控制动作 ──

        // ── 媒体 ──
        // 🔴 2026-11-07：两条 music_play 合并（原第二条在下方数行，已删除）。
        "music_play" to "「放首歌」「播我的本地音乐」都调用（单曲 {uri,title} 或播放列表都支持）；「播第 3 首」「切歌」「放 XX 这首歌」也用它。",
        "local_video_player" to "「播这个视频」「打开视频播放器」都调用",
        "list_media" to "「我手机里有哪些音乐/视频」「媒体库列表」都调用",
        // （重复的 music_play key 已合并到上方）

        // ── L2 Shizuku ──

        // ── L3 设备管理员 ──

        // ── L4 ROOT ──

        // ── L5 Linux 环境 ──

        // ── Web 应用（多文件 Web 应用）──
        "miniapp" to "「做个计算器/游戏/网站/应用」「写个多文件项目」「创建前后端分离项目」「做个完整的XX功能」都调用 miniapp——AI用多种语言写多个文件，完成后渲染在对话框里",
    )

    /**
     * 工具使用总原则：自主决策、灵活编排，而非死板铁律。
     * 注入到系统提示词「我的能力」段开头，引导 AI 自由选用任意工具、思考组合方式、
     * 并在失败时诊断 → 解决 → 重试，持续迭代直到任务完成。
     */
    fun buildToolUseDirective(): String = """
# 工具使用原则（自主决策，灵活编排）

你是拥有完整工具箱的智能体。工具是你真正完成任务的"手脚"——当用户需要真实数据（天气/时间/设备状态/联网信息）或真实动作（打开应用/读写文件/朗读/控制设备/执行代码）时，应主动调用对应工具，而不是只用文字描述或假装已完成。

## 1. 自由使用，不被限定
- 你可以使用**任意**已下发的工具来完成任务，不局限于某几个；工具清单（名称 + 多用途说明）与你可调用的函数严格一致，只调用清单里出现的工具名，不要编造清单外的工具。
- 同一需求常有无数种说法与场景：按「意图」而非「关键词」匹配工具；一个工具往往有多种用途（如 execute_intent 既能打开 App 深层页面也能发隐式 Intent，ai_browser 既能联网搜索也能自动填表/抓正文/下载），按真实意图选用，不被单一用例限制。

## 2. 先想清楚再动手（规划与配合）
- 动手前先想：要达成目标需走哪些步骤？哪些步骤可用工具？该用哪个（或哪几个）工具、以什么顺序、**如何配合**？
- 多个独立动作可一次性并行发起（一次返回多个 tool_calls）；有依赖关系的动作则按顺序逐步调用。
- 复杂任务拆成多步：思考 → 调用 → 看结果 → 再思考 → 再调用，直到任务完成。

## 3. 失败不是终点（诊断 → 解决 → 重试）
- 工具返回失败时，**先认真读错误信息**：报了什么？是参数不对、权限不足、网络问题，还是目标不存在？
- 针对问题想解决办法：参数错就修正重发；权限不足就提示用户去授权或切换权限模式；缺前置步骤就先补做；不合适就换一个更贴切的工具。
- 解决后**重新调用**验证；若还不行，**换个思路继续思考**——换工具、换方法，或把任务拆得更细。不要反复用同一个失败的方式硬撞，也不要直接放弃说"做不到"。

## 4. 知识性内容可酌情免调
- 纯知识性问答（常识/定义/数学计算/科普等，不依赖实时数据或设备动作）：如果你能直接回答就不必调用工具，如果无法直接回答则调用相关工具。
""".trimIndent()
}
