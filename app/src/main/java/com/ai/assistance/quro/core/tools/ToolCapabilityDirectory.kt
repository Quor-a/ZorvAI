package com.ai.assistance.quro.core.tools

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
        FILE_OPERATION("文件操作", "文件读写、复制、移动、删除等"),
        TERMINAL_LINUX("终端/Linux", "终端命令执行、Linux环境管理"),
        NETWORK_WEB("网络/Web", "HTTP请求、浏览器、MCP服务"),
        MEDIA("媒体", "音乐、视频、图片、音频处理"),
        UI_CARDS("UI/卡片", "对话框UI组件、可视化图表"),
        KNOWLEDGE_MEMORY("知识/记忆", "记忆库、知识库、经验库"),
        WORKSPACE("工作区", "工作区文件管理"),
        ACCESSIBILITY("无障碍", "屏幕读取、点击、滑动等"),
        APP_MANAGEMENT("应用管理", "应用启动、安装、冻结等"),
        COMMUNICATION("通信", "短信、联系人、日历等"),
        AI_CAPABILITIES("AI能力", "图像生成、视频生成、文档处理"),
        SECURITY("安全/权限", "Shizuku、ROOT、设备管理员")
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
     * 工具能力目录
     */
    private val directory = mapOf(
        // ═══════════════ 基础工具 ═══════════════
        "get_current_time" to ToolInfo(
            name = "get_current_time",
            category = ToolCategory.BASIC,
            description = "获取当前日期与时间",
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
            description = "获取设备型号与系统版本",
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
            description = "计算算术表达式",
            useCases = listOf("算一下1+2*3", "帮我算个数", "计算百分比", "单位换算"),
            examples = listOf("calculate(expr=\"1+2*3\")", "calculate(expr=\"(100-20)/5\")"),
            parameters = mapOf("expr" to "算术表达式，支持+ - * /和括号"),
            tips = listOf("支持四则运算和括号", "不要心算瞎猜，用工具算"),
            relatedTools = listOf("run_code"),
            priority = 5
        ),
        
        // ═══════════════ 系统控制 ═══════════════
        "volume_control" to ToolInfo(
            name = "volume_control",
            category = ToolCategory.SYSTEM_CONTROL,
            description = "控制设备音量",
            useCases = listOf("调高音量", "静音", "音量调到最大", "把声音关小"),
            examples = listOf("volume_control(action=\"set\", level=80)", "volume_control(action=\"mute\")"),
            parameters = mapOf("action" to "set/mute/unmute", "level" to "音量级别0-100"),
            tips = listOf("action=set时需要level参数", "action=mute是静音"),
            relatedTools = listOf("brightness_control"),
            priority = 3
        ),
        
        "wifi_control" to ToolInfo(
            name = "wifi_control",
            category = ToolCategory.SYSTEM_CONTROL,
            description = "控制WiFi开关",
            useCases = listOf("开WiFi", "关掉WiFi", "WiFi开一下"),
            examples = listOf("wifi_control(action=\"enable\")", "wifi_control(action=\"disable\")"),
            parameters = mapOf("action" to "enable/disable"),
            tips = listOf("action=enable开启WiFi", "action=disable关闭WiFi"),
            relatedTools = listOf("bluetooth_control"),
            priority = 3
        ),
        
        // ═══════════════ 文件操作 ═══════════════
        "write_file" to ToolInfo(
            name = "write_file",
            category = ToolCategory.FILE_OPERATION,
            description = "写入文件内容",
            useCases = listOf("帮我建个文件", "把这段存成txt", "生成配置文件", "写代码到文件"),
            examples = listOf("write_file(path=\"/sdcard/test.txt\", content=\"Hello World\")"),
            parameters = mapOf("path" to "文件路径", "content" to "文件内容"),
            tips = listOf("会自动创建缺失的父目录", "路径用绝对路径"),
            relatedTools = listOf("read_text_file", "workspace_write"),
            priority = 4
        ),
        
        "read_text_file" to ToolInfo(
            name = "read_text_file",
            category = ToolCategory.FILE_OPERATION,
            description = "读取文本文件内容",
            useCases = listOf("读一下这个txt", "打开那个日志看看", "把md内容念给我"),
            examples = listOf("read_text_file(path=\"/sdcard/test.txt\")"),
            parameters = mapOf("path" to "文件路径"),
            tips = listOf("只能读文本文件", "大文件会截断"),
            relatedTools = listOf("write_file", "workspace_read"),
            priority = 4
        ),
        
        "list_files" to ToolInfo(
            name = "list_files",
            category = ToolCategory.FILE_OPERATION,
            description = "列出目录内容",
            useCases = listOf("看看Download里有什么", "列一下这个文件夹", "目录里有哪些文件"),
            examples = listOf("list_files(path=\"/sdcard/Download\")"),
            parameters = mapOf("path" to "目录路径"),
            tips = listOf("默认列出当前目录", "支持通配符"),
            relatedTools = listOf("find_files", "browse_files"),
            priority = 4
        ),
        
        // ═══════════════ 终端/Linux ═══════════════
        "terminal_run" to ToolInfo(
            name = "terminal_run",
            category = ToolCategory.TERMINAL_LINUX,
            description = "在终端执行命令",
            useCases = listOf("在终端跑条命令", "执行shell", "df -h看一下"),
            examples = listOf("terminal_run(command=\"ls -la\")", "terminal_run(command=\"df -h\")"),
            parameters = mapOf("command" to "要执行的shell命令"),
            tips = listOf("应用内沙盒执行", "不需要权限", "支持常见Linux命令"),
            relatedTools = listOf("quroterm_exec", "linux_run"),
            priority = 4
        ),
        
        "linux_run" to ToolInfo(
            name = "linux_run",
            category = ToolCategory.TERMINAL_LINUX,
            description = "在Linux环境执行命令",
            useCases = listOf("在Linux环境跑XX", "执行个Linux命令", "apt装个包"),
            examples = listOf("linux_run(command=\"apt update\")", "linux_run(command=\"python3 script.py\")"),
            parameters = mapOf("command" to "要执行的Linux命令"),
            tips = listOf("需要Linux环境已安装", "支持apt包管理", "可运行Python/Node等"),
            relatedTools = listOf("terminal_run", "linux_install"),
            priority = 4
        ),
        
        "linux_install" to ToolInfo(
            name = "linux_install",
            category = ToolCategory.TERMINAL_LINUX,
            description = "安装Linux环境",
            useCases = listOf("装一下Linux环境", "初始化Alpine", "安装proot"),
            examples = listOf("linux_install()"),
            parameters = emptyMap(),
            tips = listOf("首次使用需要安装", "安装后可运行Linux命令", "需要网络连接"),
            relatedTools = listOf("linux_run", "linux_status"),
            priority = 3
        ),
        
        // ═══════════════ 网络/Web ═══════════════
        "http_request" to ToolInfo(
            name = "http_request",
            category = ToolCategory.NETWORK_WEB,
            description = "发送HTTP请求",
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
            description = "AI浏览器：联网搜索、抓取网页、自动研究",
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
            description = "打开网页（被动展示）",
            useCases = listOf("打开百度", "访问XX网址", "看这个网页"),
            examples = listOf("open_web(url=\"https://www.baidu.com\")"),
            parameters = mapOf("url" to "要打开的网址"),
            tips = listOf("只能被动展示，不能点击交互", "真正操作网页用aci_call的browser_open"),
            relatedTools = listOf("ai_browser", "aci_call"),
            priority = 2
        ),
        
        // ═══════════════ 媒体 ═══════════════
        "image_gen" to ToolInfo(
            name = "image_gen",
            category = ToolCategory.MEDIA,
            description = "AI图像生成",
            useCases = listOf("生成一张图片", "画个图", "AI生成图片", "帮我设计个logo"),
            examples = listOf("image_gen(prompt=\"一只可爱的猫咪\", style=\"realistic\")"),
            parameters = mapOf("prompt" to "图片描述", "style" to "风格"),
            tips = listOf("描述越详细效果越好", "支持多种风格"),
            relatedTools = listOf("image_recognition", "creative_studio"),
            priority = 4
        ),
        
        "image_recognition" to ToolInfo(
            name = "image_recognition",
            category = ToolCategory.MEDIA,
            description = "图像识别/分析",
            useCases = listOf("识别这张图片", "看看图片里是什么", "分析这个图片"),
            examples = listOf("image_recognition(image_path=\"/sdcard/photo.jpg\")"),
            parameters = mapOf("image_path" to "图片路径"),
            tips = listOf("支持多种图片格式", "可识别物体、文字、场景"),
            relatedTools = listOf("image_gen", "visual_analysis"),
            priority = 4
        ),
        
        // ═══════════════ UI/卡片 ═══════════════
        "ui_control" to ToolInfo(
            name = "ui_control",
            category = ToolCategory.UI_CARDS,
            description = "统一UI控制工具：操控界面每个角落（打开界面/切换开关/打开弹层/对话管理/渲染卡片/组件/查询状态/更新属性/滚动/聚焦/隐藏/显示/导航/权限控制）",
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
        
        // ═══════════════ 可视化交互（强制使用） ═══════════════
        "visual_question" to ToolInfo(
            name = "visual_question",
            category = ToolCategory.UI_CARDS,
            description = "⚠️【强制】模糊命令/缺少信息时必须调用此工具询问用户",
            useCases = listOf("用户指令模糊", "缺少关键信息", "需要确认不可逆操作", "多个选项需要用户选择", "多种理解需要确认"),
            examples = listOf("visual_question(question=\"你想要什么风格？\", options=[\"正式\",\"轻松\"])"),
            parameters = mapOf("question" to "问题", "options" to "选项列表", "allow_custom" to "允许自定义输入"),
            tips = listOf("【强制】遇到任何不确定必须调用", "禁止猜测、禁止假设、禁止跳过", "返回用户选择的答案"),
            relatedTools = listOf("visual_action", "visual_popup"),
            priority = 5
        ),
        
        "visual_action" to ToolInfo(
            name = "visual_action",
            category = ToolCategory.UI_CARDS,
            description = "可视化操作弹窗：让用户从多个操作中选择一个",
            useCases = listOf("选择操作", "确认/取消", "选择打开方式"),
            examples = listOf("visual_action(title=\"选择操作\", buttons=[{\"text\":\"查看\",\"value\":\"view\"}])"),
            parameters = mapOf("title" to "标题", "buttons" to "按钮列表"),
            tips = listOf("适合让用户选择操作", "返回用户点击的按钮值"),
            relatedTools = listOf("visual_question", "visual_popup"),
            priority = 4
        ),

        // ═══════════════ 流体云 ═══════════════
        "fluid_cloud" to ToolInfo(
            name = "fluid_cloud",
            category = ToolCategory.SYSTEM_CONTROL,
            description = "控制OPPO流体云，显示状态栏胶囊和卡片",
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
        "memory_save" to ToolInfo(
            name = "memory_save",
            category = ToolCategory.KNOWLEDGE_MEMORY,
            description = "保存一条记忆",
            useCases = listOf("记住我喜欢喝咖啡", "把这件事存进记忆", "记下XX偏好"),
            examples = listOf("memory_save(content=\"用户喜欢喝咖啡\", title=\"用户偏好\")"),
            parameters = mapOf("content" to "记忆内容", "title" to "标题（可选）", "group" to "分组（可选）"),
            tips = listOf("自动保存跨会话", "支持标签和分组", "主动保存用户透露的持久信息"),
            relatedTools = listOf("memory_list", "memory_search"),
            priority = 5
        ),
        
        "knowledge_search" to ToolInfo(
            name = "knowledge_search",
            category = ToolCategory.KNOWLEDGE_MEMORY,
            description = "搜索知识库",
            useCases = listOf("在我的知识库里搜XX", "查资料（本地文档）", "找下我存过的关于YY的"),
            examples = listOf("knowledge_search(query=\"Python教程\")"),
            parameters = mapOf("query" to "搜索关键词"),
            tips = listOf("搜索本地知识库", "支持语义搜索", "需要先添加知识"),
            relatedTools = listOf("knowledge_add", "knowledge_rag_search"),
            priority = 4
        ),
        
        "knowledge_add" to ToolInfo(
            name = "knowledge_add",
            category = ToolCategory.KNOWLEDGE_MEMORY,
            description = "添加文档到知识库",
            useCases = listOf("把这篇文档加进知识库", "导入这个文件当知识", "存成知识条目"),
            examples = listOf("knowledge_add(path=\"/sdcard/doc.pdf\", title=\"文档标题\")"),
            parameters = mapOf("path" to "文档路径", "title" to "标题"),
            tips = listOf("支持多种文档格式", "自动建立索引", "支持PDF、Word、TXT等"),
            relatedTools = listOf("knowledge_search", "knowledge_manage"),
            priority = 4
        ),
        
        // ═══════════════ 工作区 ═══════════════
        "workspace_write" to ToolInfo(
            name = "workspace_write",
            category = ToolCategory.WORKSPACE,
            description = "写入工作区文件",
            useCases = listOf("把这段代码保存到工作区", "写到我的工程里", "存成文件", "生成个项目放工作区"),
            examples = listOf("workspace_write(path=\"MyApp/src/Main.java\", content=\"代码内容\")"),
            parameters = mapOf("path" to "相对路径", "content" to "文件内容", "append" to "是否追加"),
            tips = listOf("路径是相对工作区根目录", "自动创建缺失目录", "用户可在工具箱-工作区查看"),
            relatedTools = listOf("workspace_read", "workspace_list"),
            priority = 5
        ),
        
        "workspace_read" to ToolInfo(
            name = "workspace_read",
            category = ToolCategory.WORKSPACE,
            description = "读取工作区文件",
            useCases = listOf("读一下工作区里的XX文件", "看看MyApp/src/Main.java现在内容", "工作区那个配置长啥样"),
            examples = listOf("workspace_read(path=\"MyApp/src/Main.java\")"),
            parameters = mapOf("path" to "相对路径"),
            tips = listOf("路径是相对工作区根目录", "读取完整内容"),
            relatedTools = listOf("workspace_write", "workspace_list"),
            priority = 4
        ),
        
        "workspace_list" to ToolInfo(
            name = "workspace_list",
            category = ToolCategory.WORKSPACE,
            description = "列出工作区内容",
            useCases = listOf("工作区里有什么", "列出我的工程", "看下MyApp目录结构", "工作区根目录有哪些文件"),
            examples = listOf("workspace_list()", "workspace_list(path=\"MyApp/src\")"),
            parameters = mapOf("path" to "相对路径（可选）"),
            tips = listOf("默认列根目录", "可指定子目录"),
            relatedTools = listOf("workspace_write", "workspace_read"),
            priority = 4
        ),
        
        // ═══════════════ 无障碍控屏 ═══════════════
        "read_screen" to ToolInfo(
            name = "read_screen",
            category = ToolCategory.ACCESSIBILITY,
            description = "读取当前屏幕内容",
            useCases = listOf("看看现在屏幕上是啥", "读一下当前界面", "这个App现在显示啥"),
            examples = listOf("read_screen()"),
            parameters = emptyMap(),
            tips = listOf("读取屏幕文本和控件", "返回结构化数据", "支持无障碍节点树"),
            relatedTools = listOf("screenshot", "visual_analysis"),
            priority = 5
        ),
        
        "tap_screen" to ToolInfo(
            name = "tap_screen",
            category = ToolCategory.ACCESSIBILITY,
            description = "点击屏幕指定位置",
            useCases = listOf("点一下屏幕上的XX按钮", "帮我戳那个位置", "点击确认"),
            examples = listOf("tap_screen(x=500, y=800)", "tap_screen(resource_id=\"btn_confirm\")"),
            parameters = mapOf("x" to "X坐标", "y" to "Y坐标", "resource_id" to "控件ID"),
            tips = listOf("支持坐标点击", "支持控件ID点击", "优先用控件ID"),
            relatedTools = listOf("long_press_screen", "swipe_screen"),
            priority = 4
        ),
        
        "swipe_screen" to ToolInfo(
            name = "swipe_screen",
            category = ToolCategory.ACCESSIBILITY,
            description = "滑动屏幕",
            useCases = listOf("往上滑", "左滑翻页", "划一下", "在(x1,y1)→(x2,y2)划"),
            examples = listOf("swipe_screen(x1=500, y1=1000, x2=500, y2=500)", "swipe_screen(direction=\"up\")"),
            parameters = mapOf("x1" to "起点X", "y1" to "起点Y", "x2" to "终点X", "y2" to "终点Y", "direction" to "方向up/down/left/right"),
            tips = listOf("支持坐标滑动", "支持方向滑动", "可设置持续时间"),
            relatedTools = listOf("scroll_screen", "tap_screen"),
            priority = 4
        ),
        
        // ═══════════════ 应用管理 ═══════════════
        "launch_app" to ToolInfo(
            name = "launch_app",
            category = ToolCategory.APP_MANAGEMENT,
            description = "启动应用",
            useCases = listOf("打开微信", "启动相机", "帮我开XX应用"),
            examples = listOf("launch_app(package_name=\"com.tencent.mm\")"),
            parameters = mapOf("package_name" to "应用包名"),
            tips = listOf("需要知道包名", "可用get_package_name查询", "支持系统应用和第三方应用"),
            relatedTools = listOf("open_app", "list_installed_apps"),
            priority = 4
        ),
        
        "open_app" to ToolInfo(
            name = "open_app",
            category = ToolCategory.APP_MANAGEMENT,
            description = "打开应用（按包名）",
            useCases = listOf("打开XX应用", "启动XX"),
            examples = listOf("open_app(package_name=\"com.tencent.mm\")"),
            parameters = mapOf("package_name" to "应用包名"),
            tips = listOf("与launch_app类似", "支持包名和应用名"),
            relatedTools = listOf("launch_app", "search_and_launch_app"),
            priority = 3
        ),
        
        // ═══════════════ 通信 ═══════════════
        "send_sms" to ToolInfo(
            name = "send_sms",
            category = ToolCategory.COMMUNICATION,
            description = "发送短信",
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
            description = "读取联系人",
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
            category = ToolCategory.AI_CAPABILITIES,
            description = "运行代码（Python/JS/Shell等）",
            useCases = listOf("跑个Python脚本", "执行这段代码", "算一下这个", "写个程序"),
            examples = listOf("run_code(code=\"print('Hello')\", lang=\"python\")"),
            parameters = mapOf("code" to "代码内容", "lang" to "语言python/node/shell/html"),
            tips = listOf("python内置Brython引擎", "node内置QuickJS", "html会渲染成网页"),
            relatedTools = listOf("terminal_run", "workbench"),
            priority = 5
        ),
        
        "workbench" to ToolInfo(
            name = "workbench",
            category = ToolCategory.AI_CAPABILITIES,
            description = "创建完整多文件项目",
            useCases = listOf("做个计算器", "写个多文件项目", "创建前后端分离项目", "做个完整的XX功能"),
            examples = listOf("workbench(action=\"create\", name=\"calculator\", files=[{path:\"index.html\", content:\"...\"}])"),
            parameters = mapOf("action" to "create/run/edit", "name" to "项目名", "files" to "文件列表", "entry" to "入口文件"),
            tips = listOf("支持多文件项目", "自动合并CSS/JS到HTML", "运行后渲染在对话框"),
            relatedTools = listOf("run_code", "workspace_write"),
            priority = 5
        ),
        
        "creative_studio" to ToolInfo(
            name = "creative_studio",
            category = ToolCategory.AI_CAPABILITIES,
            description = "广义IDE知识库和创作工具",
            useCases = listOf("推荐IDE", "做个视频", "画个图", "做3D模型", "写音乐"),
            examples = listOf("creative_studio(action=\"list_categories\")", "creative_studio(action=\"recommend\", need=\"video_editing\")"),
            parameters = mapOf("action" to "list_categories/recommend/start", "need" to "需求描述"),
            tips = listOf("包含图形/视频/音频/3D/游戏等所有创作领域", "可推荐适合的工具", "可启动已安装的创作工具"),
            relatedTools = listOf("image_gen", "video_gen"),
            priority = 4
        ),
        
        // ═══════════════ MCP ═══════════════
        "mcp_servers" to ToolInfo(
            name = "mcp_servers",
            category = ToolCategory.NETWORK_WEB,
            description = "列出MCP服务器",
            useCases = listOf("看看连了哪些MCP服务器", "MCP服务列表", "外部工具有哪些"),
            examples = listOf("mcp_servers()"),
            parameters = emptyMap(),
            tips = listOf("列出所有MCP服务器", "包括本地和远程", "显示连接状态"),
            relatedTools = listOf("mcp_list_tools", "mcp_call"),
            priority = 3
        ),
        
        "mcp_call" to ToolInfo(
            name = "mcp_call",
            category = ToolCategory.NETWORK_WEB,
            description = "调用MCP工具",
            useCases = listOf("调用外部MCP的XX工具", "让连着的服务器干YY", "用外部工具"),
            examples = listOf("mcp_call(server=\"github\", tool=\"create_issue\", params={...})"),
            parameters = mapOf("server" to "服务器名", "tool" to "工具名", "params" to "参数"),
            tips = listOf("先用mcp_list_tools查看可用工具", "支持参数验证", "返回结构化结果"),
            relatedTools = listOf("mcp_servers", "mcp_list_tools"),
            priority = 4
        ),
        
        // ═══════════════ CMS ═══════════════
        "cms_call" to ToolInfo(
            name = "cms_call",
            category = ToolCategory.AI_CAPABILITIES,
            description = "调用CMS能力模块",
            useCases = listOf("调用XX能力模块做YY", "让模块执行", "用CMS功能"),
            examples = listOf("cms_call(capability_id=\"echo_text\", args={text:\"hello\"})"),
            parameters = mapOf("capability_id" to "能力ID", "args" to "参数"),
            tips = listOf("先用cms_list查看可用能力", "支持同步/异步", "可传参数"),
            relatedTools = listOf("cms_list", "cms_status"),
            priority = 4
        ),
        
        "cms_list" to ToolInfo(
            name = "cms_list",
            category = ToolCategory.AI_CAPABILITIES,
            description = "列出CMS能力模块",
            useCases = listOf("我装了哪些能力模块", "CMS模块列表", "有什么能力"),
            examples = listOf("cms_list()"),
            parameters = emptyMap(),
            tips = listOf("列出所有已安装模块", "显示能力ID和说明", "显示风险级别"),
            relatedTools = listOf("cms_call", "cms_status"),
            priority = 3
        ),
        
        // ═══════════════ ACI ═══════════════
        "aci_call" to ToolInfo(
            name = "aci_call",
            category = ToolCategory.APP_MANAGEMENT,
            description = "调用第三方App的ACI能力",
            useCases = listOf("让XX App帮我做YY", "调起外部App的能力", "用浏览器打开网页"),
            examples = listOf("aci_call(capability=\"browser_open\", args={url:\"https://example.com\"})"),
            parameters = mapOf("target_package" to "目标包名（可选）", "capability" to "能力名", "args" to "参数"),
            tips = listOf("可省略target_package用默认应用", "先用aci_list查看可用能力", "支持多种能力组合"),
            relatedTools = listOf("aci_list", "browser_open"),
            priority = 5
        ),
        
        "aci_list" to ToolInfo(
            name = "aci_list",
            category = ToolCategory.APP_MANAGEMENT,
            description = "列出可用的ACI能力",
            useCases = listOf("有哪些第三方App能让我调用", "可控制的外部能力", "ACI能力列表"),
            examples = listOf("aci_list()"),
            parameters = emptyMap(),
            tips = listOf("列出所有已发现的ACI App", "显示每个App的能力", "显示绑定状态"),
            relatedTools = listOf("aci_call", "launch_app"),
            priority = 4
        ),
        
        // ═══════════════ 视觉分析 ═══════════════
        "screenshot" to ToolInfo(
            name = "screenshot",
            category = ToolCategory.ACCESSIBILITY,
            description = "截取屏幕截图",
            useCases = listOf("截个图", "截图保存", "把屏幕截下来"),
            examples = listOf("screenshot()"),
            parameters = emptyMap(),
            tips = listOf("返回截图文件路径", "可保存到指定位置", "支持全屏和区域截图"),
            relatedTools = listOf("screenshot_base64", "visual_analysis"),
            priority = 4
        ),
        
        "visual_analysis" to ToolInfo(
            name = "visual_analysis",
            category = ToolCategory.ACCESSIBILITY,
            description = "视觉分析屏幕内容",
            useCases = listOf("看看屏幕上是什么", "分析这个页面", "屏幕上有什么按钮/文字/图标"),
            examples = listOf("visual_analysis()"),
            parameters = emptyMap(),
            tips = listOf("用视觉模型分析截图", "适合游戏/WebView/Flutter", "比read_screen更全面"),
            relatedTools = listOf("screenshot", "read_screen"),
            priority = 4
        ),
        
        // ═══════════════ 语音 ═══════════════
        "speak" to ToolInfo(
            name = "speak",
            category = ToolCategory.MEDIA,
            description = "TTS语音合成",
            useCases = listOf("读给我听", "念一下这段", "大声朗读", "用语音播报"),
            examples = listOf("speak(text=\"你好世界\", voice=\"xiaoxiao\")"),
            parameters = mapOf("text" to "要朗读的文本", "voice" to "音色（可选）"),
            tips = listOf("支持多种音色", "可设置语速", "与自动朗读独立"),
            relatedTools = listOf("stop_speak"),
            priority = 4
        ),
        
        // ═══════════════ 定时任务 ═══════════════
        "schedule_task" to ToolInfo(
            name = "schedule_task",
            category = ToolCategory.AI_CAPABILITIES,
            description = "创建定时任务",
            useCases = listOf("每天定时提醒我喝水", "下周三自动发条消息", "10分钟后执行某个动作"),
            examples = listOf("schedule_task(action=\"create\", schedule=\"0 9 * * *\", task=\"提醒喝水\")"),
            parameters = mapOf("action" to "create/list/delete", "schedule" to "cron表达式", "task" to "任务内容"),
            tips = listOf("支持cron表达式", "可一次创建多个", "支持循环和单次"),
            relatedTools = listOf("list_scheduled_tasks", "delete_scheduled_task"),
            priority = 4
        ),
        
        // ═══════════════ 文档生成 ═══════════════
        "aiwps_create" to ToolInfo(
            name = "aiwps_create",
            category = ToolCategory.AI_CAPABILITIES,
            description = "生成真实Office文件（docx/xlsx/pptx/pdf），可下载/分享",
            useCases = listOf("帮我生成一份Word周报", "做个Excel表格", "出个PPT关于XX", "生成PDF报告"),
            examples = listOf("aiwps_create(type=\"docx\", title=\"周报\", content=\"内容\")"),
            parameters = mapOf("type" to "docx/xlsx/pptx/pdf", "title" to "标题", "content" to "内容"),
            tips = listOf("生成真正的Office二进制文件", "可用WPS/Office打开", "文件保存到Downloads目录"),
            relatedTools = listOf("enhanced_doc_create", "chat_doc", "workspace_doc"),
            priority = 5
        ),
        
        "chat_doc" to ToolInfo(
            name = "chat_doc",
            category = ToolCategory.AI_CAPABILITIES,
            description = "对话框内直接写文档并渲染显示（不生成文件）",
            useCases = listOf("写一篇文章在对话框显示", "生成代码示例", "写报告/方案", "生成表格"),
            examples = listOf("chat_doc(title=\"方案\", content=\"# 方案\\n...\", format=\"md\")"),
            parameters = mapOf("title" to "标题", "content" to "内容", "format" to "md/html/code/text"),
            tips = listOf("不生成文件，内容直接渲染在对话框", "适合快速展示", "支持Markdown/HTML/代码/文本"),
            relatedTools = listOf("aiwps_create", "enhanced_doc_create", "ui_widget"),
            priority = 5
        ),
        
        "enhanced_doc_create" to ToolInfo(
            name = "enhanced_doc_create",
            category = ToolCategory.AI_CAPABILITIES,
            description = "多格式文档创建（md/txt/csv/json/html等17种）",
            useCases = listOf("创建Markdown文件", "生成JSON配置", "写HTML页面", "创建CSV数据"),
            examples = listOf("enhanced_doc_create(type=\"md\", title=\"笔记\", content=\"内容\")"),
            parameters = mapOf("type" to "格式", "title" to "标题", "content" to "内容"),
            tips = listOf("支持17种格式", "文本类文档优先用此工具", "创建后自动渲染预览"),
            relatedTools = listOf("aiwps_create", "chat_doc", "workspace_doc"),
            priority = 4
        )
    )
    
    /**
     * 根据用户意图匹配工具
     */
    fun matchToolsByIntent(intent: String): List<ToolInfo> {
        val intentLower = intent.lowercase()
        val matches = mutableListOf<ToolInfo>()
        
        for ((name, info) in directory) {
            // 检查使用场景匹配
            for (useCase in info.useCases) {
                if (intentLower.contains(useCase.lowercase()) || 
                    useCase.lowercase().contains(intentLower)) {
                    matches.add(info)
                    break
                }
            }
        }
        
        // 按优先级排序
        return matches.sortedByDescending { it.priority }
    }
    
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
     * 根据意图生成工具推荐（注入系统提示）
     */
    fun buildIntentRecommendations(): String {
        val sb = StringBuilder()
        sb.appendLine("# 常见意图 → 工具推荐")
        sb.appendLine("当用户说以下意图时，直接调用对应工具：")
        sb.appendLine()
        
        val intentMappings = mapOf(
            "时间/日期" to listOf("get_current_time"),
            "设备信息" to listOf("get_device_info", "get_battery", "get_wifi_info"),
            "计算/算数" to listOf("calculate", "run_code"),
            "文件操作" to listOf("read_text_file", "write_file", "list_files", "find_files"),
            "终端命令" to listOf("terminal_run", "quroterm_exec", "linux_run"),
            "联网搜索" to listOf("ai_browser", "http_request"),
            "打开网页" to listOf("aci_call(browser_open)", "ai_browser", "open_web"),
            "图片生成" to listOf("image_gen"),
            "图片识别" to listOf("image_recognition", "visual_analysis"),
            "UI展示" to listOf("ui_control"),
            "流程图/架构图" to listOf("ui_control(action=\"widget\", type=\"mermaid\")"),
            "记忆保存" to listOf("memory_save"),
            "知识搜索" to listOf("knowledge_search", "knowledge_rag_search"),
            "工作区文件" to listOf("workspace_write", "workspace_read", "workspace_list"),
            "屏幕操作" to listOf("read_screen", "tap_screen", "swipe_screen"),
            "应用启动" to listOf("launch_app", "open_app"),
            "发短信" to listOf("send_sms"),
            "读联系人" to listOf("read_contacts"),
            "代码执行" to listOf("run_code", "workbench"),
            "文档生成（可下载）" to listOf("aiwps_create", "enhanced_doc_create"),
            "对话框写文档" to listOf("chat_doc"),
            "对话框显示文档" to listOf("chat_doc"),
            "定时任务" to listOf("schedule_task"),
            "语音朗读" to listOf("speak"),
            "MCP工具" to listOf("mcp_call", "mcp_list_tools"),
            "CMS模块" to listOf("cms_call", "cms_list"),
            "第三方App" to listOf("aci_call", "aci_list"),
            "流体云/状态栏" to listOf("fluid_cloud"),
            "AI思考中/任务进度" to listOf("fluid_cloud(action=create)", "fluid_cloud(action=update)")
        )
        
        for ((intent, tools) in intentMappings) {
            sb.appendLine("- **$intent** → ${tools.joinToString("、") { "`$it`" }}")
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
- 用户需要可视化结果（图表/流程图/UI）→ 调用ui_control(action="widget")展示

## 2. 工具组合使用
- 复杂任务拆成多步：思考 → 调用 → 看结果 → 再思考 → 再调用
- 多个独立动作可并行发起（一次返回多个tool_calls）
- 有依赖关系的动作按顺序调用

## 3. 失败处理
- 工具返回失败时，先读错误信息
- 针对问题修正：参数错就修正，权限不足就提示用户授权
- 换个思路：换工具、换方法、拆更细的任务

## 4. 工具选择原则
- 能用专用工具就不用通用工具（如发短信用send_sms不用http_request）
- 能用简单工具就不用复杂工具（如读文件用read_text_file不用run_code）
- 能用内置工具就不用外部工具（如计算用calculate不用run_code）

## 5. 工具调用格式
- 一次调用多个工具：返回多个tool_calls
- 参数用JSON格式
- 返回结果后继续处理，直到任务完成
""".trimIndent()
    }
}