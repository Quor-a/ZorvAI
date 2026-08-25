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
        "get_clipboard" to "「我刚复制了什么」「剪贴板里是啥」「读出我复制的内容」都调用",
        "set_clipboard" to "「帮我把这段复制下来」「存到剪贴板」「把这个文本放剪贴板」都调用",

        // ── 应用管理 ──
        "list_installed_apps" to "「我装了哪些 App」「列一下应用」「有没有装 XX」都调用",
        "launch_app" to "「打开微信」「启动相机」「帮我开 XX 应用」都调用",
        "search_and_launch_app" to "「打开那个绿的聊天软件」「找一下能扫码的 App 打开」「名字里有'shop'的打开」都调用",
        "get_package_name" to "「XX 应用的包名是什么」「这个 App 的 package」都调用",
        "list_app_functions" to "「这个 App 能做什么」「XX 有哪些可调用功能」「能调起它的什么页面」都调用",
        "invoke_app_function" to "「用 XX App 分享到朋友圈」「调起 XX 的扫一扫」「让 XX 打开某页面」都调用",

        // ── 通知 / 蓝牙 / 手电 ──
        "get_active_notifications" to "「我有什么通知」「未读消息有哪些」「状态栏提醒」都调用",
        "get_bluetooth_status" to "「蓝牙开了吗」「连了什么设备」「蓝牙状态」都调用",
        "toggle_flashlight" to "「开手电筒」「关闪光灯」「灯亮一下」「照亮」都调用",

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
        "list_files" to "「看看 Download 里有什么」「列一下这个文件夹」「目录里有哪些文件」都调用",
        "read_text_file" to "「读一下这个 txt」「打开那个日志看看」「把 md 内容念给我」都调用",
        "browse_files" to "「浏览我的文件」「进到某个目录看看」「文件管理器打开 XX」都调用",
        "file_read" to "「读取这个文件」「打开文件内容」「读 XX 文件」都调用（与 read_text_file 同族，按路径/类型选用）",

        // ── 文件写/改/删 ──
        "write_file" to "「帮我建个文件写点东西」「把这段存成 xx.txt」「生成配置文件」都调用",
        "delete_file" to "「删掉这个文件」「清掉 XX」「移除那个日志」都调用",
        "make_directory" to "「新建个文件夹」「建个目录叫 XX」都调用",
        "move_file" to "「把这个文件挪到 XX」「移动文件」「重命名并移动」都调用",
        "copy_file" to "「复制这个文件到 XX」「备份一份」都调用",
        "find_files" to "「找一下名字里带'报告'的文件」「全盘搜 xx.pdf」「哪个目录有这个文件」都调用",
        "file_info" to "「这个文件多大」「创建时间」「文件属性」都调用",

        // ── 工作区（QuroWorkspace） ──
        "workspace_write" to "「把这段代码保存到工作区」「写到我的工程里」「存成文件」「生成个项目放工作区」都调用（工作区即「工具箱-工作区」目录，用户能在 UI 看到/下载/改；path 是相对工作区根的路径，如 MyApp/src/Main.java）",
        "workspace_read" to "「读一下工作区里的 XX 文件」「看看 MyApp/src/Main.java 现在内容」「工作区那个配置长啥样」都调用（path 为工作区内相对路径）",
        "workspace_list" to "「工作区里有什么」「列出我的工程」「看下 MyApp 目录结构」「工作区根目录有哪些文件」都调用（path 可选，默认根目录）",

        // ── 网络 / Web ──
        "http_request" to "「调一下这个接口」「发个 GET/POST 到 XX」「请求这个 URL」「对接某个 API」都调用",
        "open_web" to "「打开百度」「访问 XX 网址」类请求：若系统提示里已声明【默认 ACI 应用已设置】（通常是受控浏览器），【优先用 aci_call 的 browser_open】（真实可交互、能点进去）；只有在未设默认 ACI 应用时才用 open_web 被动展示（仅供查看，AI 无法点击交互）。真正操作网页（点链接/填表/翻页）一律用 aci_call 的 browser_open→browser_elements→browser_action。",
        "ai_browser" to "多用途：既能「后台联网搜索资料/自动研究」也能「打开网页被动展示」也能「下载网页里的文件」——需要 AI 自动上网查资料/下载时调用（open 仅展示、不能点击；真正点击/填表/进入子页面请用 aci_call 的 ZorvAI 受控浏览器 browser_open→browser_elements→browser_action）",

        // ── 代码执行 ──
        "run_code" to "多用途：用户给的任意代码片段（Python/JS/Shell 等）都能跑；「算一下这段」「跑个脚本」「帮我试试这段代码」都调用",

        // ── 终端 ──
        "terminal_run" to "「在终端跑条命令」「执行 shell」「df -h 看一下」都调用（应用内沙盒，免权限）",
        "terminal_exec" to "需要连续/交互式终端会话（多步命令、保持状态）时调用",
        "terminal_write" to "向已开的终端会话写入输入（如半交互命令的后续参数）",
        "terminal_kill" to "「关掉终端」「杀掉那个 shell 进程」",
        "terminal_status" to "「终端还活着吗」「当前会话状态」",
        "quroterm_exec" to "走 QuroTerm 自研沙盒执行命令（与 terminal_run 同类，按当前通道选用）",

        // ── TTS ──
        "speak" to "「读给我听」「念一下这段」「大声朗读」「用语音播报」都调用；也可用于把 AI 回复转语音",
        "stop_speak" to "「别念了」「停下朗读」「静音」都调用",

        // ── 闹钟 ──
        "set_alarm" to "「定个明早 7 点闹钟」「提醒我 3 小时后吃药（闹钟）」「加个闹铃」都调用",

        // ── 定时任务 / 自动化 ──
        "schedule_task" to "多用途：既能「每天定时提醒我喝水」也能「下周三自动发条消息」也能「10 分钟后执行某个动作」——需要「未来某时刻自动触发」时调用",
        "list_scheduled_tasks" to "「我定了哪些定时任务」「看看待办的提醒」「自动化列表」都调用",
        "delete_scheduled_task" to "「取消那个定时提醒」「删掉周三的任务」都调用",

        // ── 记忆库 ──
        "memory_save" to "「记住我喜欢喝咖啡」「把这件事存进记忆」「记下 XX 偏好」都调用",
        "memory_list" to "「你都记了我什么」「列一下记忆」「我存了哪些笔记」都调用",
        "memory_search" to "「我之前是不是说过 XX」「查一下我记过的关于 YY 的」都调用",
        "memory_delete" to "「忘掉那条记忆」「删掉关于 XX 的记录」都调用",

        // ── AI 经验闭环 ──
        "experience_log" to "「把这次踩的坑记下来」「沉淀一个经验：XX 容易失败」「存个方案」都调用",
        "experience_query" to "「以前有没有类似问题的解法」「查经验库里 YY 怎么处理」都调用",
        "experience_correct" to "「上条经验是错的，更正为…」「修正那个记录」都调用",
        "experience_version_check" to "「这个功能在旧版本和新版本有啥区别」「查版本差异」都调用",

        // ── 知识库 ──
        "knowledge_search" to "「在我的知识库里搜 XX」「查资料（本地文档）」「找下我存过的关于 YY 的」都调用",
        "knowledge_add" to "「把这篇文档加进知识库」「导入这个文件当知识」「存成知识条目」都调用",
        "knowledge_manage" to "「管理我的知识库」「删掉某条知识」「看知识库列表/重新建索引」都调用",
        "knowledge_rag_search" to "多用途：语义/向量检索本地知识库——「按意思找相关文档」「类似 XX 的内容有哪些」「用自然语言搜我存过的资料」都调用（比关键词 search 更懂语义）",

        // ── 文档生成 ──
        "aiwps_create" to "「帮我生成一份 Word 周报」「做个 Excel 表格」「出个 PPT 关于 XX」都调用（本地生成 Office 兼容文件）",

        // ── 统一UI控制工具 ──
        "ui_control" to "统一UI控制工具：操控界面每个角落。常见用法：「打开编辑器/终端/工具箱」→ ui_control(action=\"open\", target=\"editor\")；「切换深度思考」→ ui_control(action=\"toggle\", target=\"deepthink\")；「打开模型选择」→ ui_control(action=\"sheet\", target=\"model\")；「新建对话」→ ui_control(action=\"chat\", action_type=\"new\")；「渲染卡片/组件」→ ui_control(action=\"card\"/\"widget\")；「查询状态/更新属性/滚动/聚焦/隐藏/显示/导航/权限控制」→ ui_control(action=\"status\"/\"update\"/\"scroll\"/\"focus\"/\"hide\"/\"show\"/\"navigate\"/\"permission\")",

        // ── MCP 客户端 ──
        "mcp_servers" to "「看看连了哪些 MCP 服务器」「MCP 服务列表」都调用",
        "mcp_list_tools" to "「这个 MCP 服务器有哪些工具」「列一下外部工具」都调用",
        "mcp_call" to "「调用外部 MCP 的 XX 工具」「让连着的服务器干 YY」都调用",
        "mcp_deploy" to "「把我的 MCP 服务器部署到本机」「起一个本地 MCP」都调用",
        "mcp_undeploy" to "「停掉那个本地 MCP 服务」「卸载部署」都调用",
        "mcp_list_local" to "「本机部署了哪些 MCP」「本地服务列表」都调用",

        // ── 第三方授权保险库 ──
        "auth_service_add" to "「把 XX 平台的 token 存起来」「记录这个 API key 给以后用」都调用",
        "auth_service_list" to "「我存了哪些授权」「列一下凭证」都调用",
        "auth_service_remove" to "「删掉 XX 的授权」「移除那个 key」都调用",

        // ── CMS v2 能力模块 ──
        "cms_list" to "「我装了哪些能力模块」「CMS 模块列表」都调用",
        "cms_call" to "「调用 XX 能力模块做 YY」「让模块执行」都调用",
        "cms_engine_status" to "「CMS引擎状态」「引擎部署好没」「引擎就绪了吗」「引擎拉起了哪些服务」「系统资源包状态」都调用",
        "cms_status" to "「那个模块部署成功没」「能力状态」都调用",
        "cms_logs" to "「看模块的运行日志」「报错信息」都调用",
        "cms_result" to "「拿模块的返回结果」「上次执行产出」都调用",
        "cms_run_dag" to "「按顺序跑这一组命令/步骤」「编排执行」都调用",
        "cms_deploy_terminal" to "「把这个模块推到终端跑」「部署到 Linux 环境」都调用",
        "cms_undeploy_terminal" to "「从终端卸掉那个模块」都调用",
        "priv_status" to "「我现在能用 Shizuku/ROOT 吗」「哪些高危通道可用」都调用（调用高风险能力前先自查）",

        // ── ACI ──
        "aci_list" to "「有哪些第三方 App 能让我调用」「可控制的外部能力」都调用；想用受控浏览器访问局域网（LAN）HTTP 服务（路由器/NAS/智能家居后台/私有 API）也先 aci_list 看它是否暴露 http_request",
        "aci_call" to "「让 XX App 帮我做 YY」「调起外部 App 的能力」都调用；可自由组合多个能力（如 browser_open 打开网页 → browser_script 执行 JS 取数 → browser_read 回读结果，其间用 browser_wait 等加载），也能调 http_request 让受控浏览器访问同网段 LAN 明文服务（http://192.168.x.x、http://10.x、*.local mDNS），不必死板地按固定步骤走",

        // ── L1 无障碍控屏 ──
        "read_screen" to "「看看现在屏幕上是啥」「读一下当前界面」「这个 App 现在显示啥」都调用",
        "get_foreground_app" to "「我现在在前用哪个 App」「前台是哪个」都调用",
        "get_screen_state" to "「屏幕亮着吗」「锁屏没」「是否熄屏」都调用",
        "tap_screen" to "「点一下屏幕上的 XX 按钮」「帮我戳那个位置」都调用",
        "long_press_screen" to "「长按 XX 弹出菜单」「长按选择这段文字」「长按应用图标卸载」都调用（触发长按菜单/选择/拖拽预备）",
        "swipe_screen" to "「往上滑」「左滑翻页」「划一下」「在 (x1,y1)→(x2,y2) 划」都调用",
        "input_text" to "「在那个输入框里打'你好'」「填表」都调用",
        "scroll_screen" to "「往下滚」「滚动列表」都调用",
        "global_action" to "「返回桌面」「下拉通知栏」「展开通知」「下拉状态栏/快捷设置」「截个图」「最近任务」「锁屏」等系统全局动作都调用",

        // ── 屏幕视觉双模感知 ──
        "screenshot" to "「截个图」「截图保存」「把屏幕截下来」都调用——返回截图文件路径",
        "screenshot_base64" to "「截图发给视觉模型」「截屏分析」都调用——返回Base64编码的图片",
        "visual_analysis" to "「看看屏幕上是什么」「分析这个页面」「屏幕上有什么按钮/文字/图标」都调用——当read_screen节点树不够用时（游戏/WebView/Flutter/自绘UI），用视觉模型分析截图",

        // ── 系统级控制动作 ──
        "take_photo" to "「拍照」「打开相机拍一张」「帮我拍照」都调用",
        "screen_record" to "「录屏」「开始录像」「停止录屏」都调用",
        "volume_control" to "「调高音量」「静音」「音量调到最大」「把声音关小」都调用",
        "brightness_control" to "「调亮屏幕」「亮度调高」「开自动亮度」「屏幕太暗了」都调用",
        "wifi_control" to "「开WiFi」「关掉WiFi」「WiFi开一下」都调用",
        "bluetooth_control" to "「开蓝牙」「关蓝牙」「蓝牙开一下」都调用",
        "notification_control" to "「下拉通知栏」「收起通知」「清掉通知」都调用",
        "airplane_mode" to "「开飞行模式」「关飞行模式」「飞行模式开一下」都调用",
        "screen_rotation" to "「自动旋转屏幕」「锁竖屏」「锁横屏」都调用",
        "set_timer" to "「倒计时5分钟」「设个计时器」「煮面计时」都调用",
        "open_app" to "「打开XX应用」「启动XX」都调用（传包名）",

        // ── 媒体 ──
        "local_music_player" to "「放首歌」「打开音乐播放器」「播我的本地音乐」都调用",
        "local_video_player" to "「播这个视频」「打开视频播放器」都调用",
        "list_media" to "「我手机里有哪些音乐/视频」「媒体库列表」都调用",
        "music_play" to "「播第 3 首」「切歌」「放 XX 这首歌」都调用",

        // ── L2 Shizuku ──
        "shizuku_exec" to "「用 Shizuku 跑条命令」「以 ADB 权限执行 XX」都调用（需 Shizuku 已授权运行）",
        "shizuku_root_exec" to "「经 Shizuku 提权执行」「root 级命令（走 Shizuku）」都调用",
        "freeze_app" to "「冻结 XX 应用」「停用这个 App」都调用",
        "install_app" to "「装这个 apk」「安装 XX」都调用",
        "shizuku_status" to "「Shizuku 连上了吗」「Shizuku 状态」都调用",

        // ── L3 设备管理员 ──
        "lock_screen" to "「锁屏」「锁住手机」都调用（需设备管理员已激活）",
        "device_admin_status" to "「设备管理员开了吗」「管理员状态」都调用",
        "set_camera_disabled" to "「禁用摄像头」「关掉相机」都调用",

        // ── L4 ROOT ──
        "root_exec" to "「以 root 执行 XX」「跑条需要 root 的命令」都调用（需已 Root）",
        "root_status" to "「手机 root 了没」「root 状态」都调用",

        // ── L5 Linux 环境 ──
        "linux_run" to "「在 Linux 环境跑 XX」「执行个 Linux 命令」都调用（需 proot 资产）",
        "linux_install" to "「装一下 Linux 环境」「初始化 Ubuntu」都调用",
        "linux_start" to "「启动 Linux 环境」都调用",
        "linux_stop" to "「关掉 Linux 环境」都调用",
        "linux_status" to "「Linux 环境状态」「装好了没」都调用",

        // ── 后端工作区 ──
        "workbench" to "「做个计算器/游戏/网站/应用」「写个多文件项目」「创建前后端分离项目」「做个完整的XX功能」都调用——AI用多种语言写多个文件，完成后渲染在对话框里",
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
