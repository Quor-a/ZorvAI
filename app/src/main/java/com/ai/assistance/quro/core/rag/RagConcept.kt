package com.ai.assistance.quro.core.rag

/**
 * 领域概念图：把「用户会说的话」映射到「系统里的能力名」。
 *
 * ## 为什么模糊匹配还不够
 * [RagFuzzy] 能解决错别字、拼音缩写、字形近似，但解决不了**语义鸿沟**：
 *用户说「把那个能联网查东西的打开」，整句话里没有「web_search」也没有「搜索」；
 * 用户说「弄个能看图的表」，没有「chart」也没有「图表」。
 * 这类查询靠字形永远命中不了，必须有一张**人工维护的概念映射表**把它接上。
 *
 * ## 三种关系
 *  - [ALIASES]：等价别名（双向）。「联网查询」≡「上网搜索」。
 *  - [CAPACITIES]：能力 → 触发词。用户说触发词，反查该调用哪个能力。
 *  - [INTENT_EXPANSIONS]：整句意图 → 检索词扩展。「把那首歌剪成30 秒」扩展出
 *    `视频 剪辑 转码 ffmpeg 时长 截取`，让视频类工具能被捞出来。
 *
 * ## 关键约束：扩展是**追加**不是替换
 * 扩展词与原查询一起参与打分（原查询权重更高），而不是替换掉原查询。
 * 否则用户输入里最精确的那部分信息（「30秒」这种约束）会在扩展中丢失。
 */
internal object RagConcept {

    /**
     * 等价别名（双向归一）。key 与 value 视为同一概念。
     * 全部小写；匹配前查询与文档两侧都会过一遍。
     */
    private val ALIASES: List<Pair<String, String>> = listOf(
        // 联网类
        "上网" to "联网", "网上" to "联网", "internet" to "联网", "net" to "联网", "online" to "联网",
        "联网查询" to "联网搜索", "上网查" to "联网搜索", "网上查" to "联网搜索",
        "查资料" to "搜索", "找资料" to "搜索", "扒资料" to "搜索", "调研" to "搜索", "查一查" to "搜索",
        // 出图类
        "画图" to "出图", "画画" to "出图", "作图" to "出图", "生成图" to "出图", "出图" to "图像生成",
        "图片生成" to "图像生成", "文生图" to "图像生成", "ai 画" to "图像生成",
        "海报" to "出图", "封面" to "出图", "配图" to "出图", "插画" to "出图", "壁纸" to "出图",
        "画个界面" to "界面", "画个页面" to "界面", "做个界面" to "界面", "生成界面" to "界面",
        // 视频类
        "剪辑" to "视频处理", "剪片子" to "视频处理", "剪短" to "视频处理", "截取" to "视频处理",
        "切片" to "视频处理", "转码" to "视频处理", "格式转换" to "视频处理",
        "短视频" to "视频", "小视频" to "视频", "录像" to "视频", "录制" to "视频",
        // 语音类
        "念" to "朗读", "读出来" to "朗读", "念出来" to "朗读", "播" to "朗读", "播报" to "朗读",
        "语音播报" to "朗读", "tts" to "朗读", "合成语音" to "朗读", "念给我听" to "朗读",
        // 文件类
        "存起来" to "写入", "保存" to "写入", "存一下" to "写入", "写到文件" to "写入", "落盘" to "写入",
        "删了" to "删除", "干掉" to "删除", "清掉" to "删除", "扔掉" to "删除",
        "看看文件" to "读取", "打开文件" to "读取", "读一下" to "读取", "查看内容" to "读取",
        // 设备类
        "这台手机" to "设备", "我的手机" to "设备", "设备情况" to "设备", "硬件信息" to "设备",
        // 应用类
        "开个" to "启动", "打开应用" to "启动", "进那个" to "启动", "运行一下" to "启动",
        "卸载" to "删除应用", "删掉应用" to "删除应用",
        // 打包类
        "打个包" to "构建", "出包" to "构建", "编译一下" to "构建", "生成 apk" to "构建",
        "给我 apk" to "导出", "拿出包" to "导出", "导出安装包" to "导出",
        // 提醒类
        "叫我" to "提醒", "别忘" to "提醒", "到时候" to "提醒", "点提醒我" to "提醒",
        // 屏幕类
        "看屏幕" to "屏幕", "读屏" to "屏幕", "看看界面" to "屏幕", "当前页面" to "屏幕",
        "点一下屏幕" to "点击", "戳一下" to "点击", "按一下" to "点击", "tap" to "点击",
        // 输入类
        "打字" to "输入文本", "敲字" to "输入文本", "填进去" to "输入文本",
        // 代码类
        "跑代码" to "运行代码", "执行脚本" to "运行代码", "跑个脚本" to "运行代码", "跑一下" to "运行代码",
        "写个程序" to "代码执行", "写个脚本" to "代码执行",
        // 数据可视化
        "看图的表" to "图表", "数据表" to "图表", "统计图" to "图表", "报表" to "图表",
        "趋势" to "图表", "曲线" to "图表", "柱状" to "图表", "饼图" to "图表",
        "可视化" to "图表", "画个图表示" to "图表", "做个图" to "图表",
        // 记忆类
        "记着" to "记忆", "记一下" to "记忆", "记住" to "记忆", "存记忆" to "记忆",
        "想不起来" to "记忆搜索", "之前说的" to "记忆搜索", "查记忆" to "记忆搜索",
        // 消息类
        "发个短信" to "发送短信", "回信息" to "发送短信",
        // 天气/时间类
        "几点" to "时间", "现在时间" to "时间", "日期" to "时间", "今天几号" to "时间",
        "天气" to "天气查询", "气温" to "天气查询", "下雨吗" to "天气查询", "多少度" to "天气查询",
        // 工具与插件
        "插件" to "扩展能力", "扩展" to "扩展能力", "装个能力" to "插件",
        // 界面组件
        "小卡片" to "ui_card", "卡片" to "ui_card", "小控件" to "ui_widget",
        "动态组件" to "ui_control", "活组件" to "ui_control",
    )

    /** 长词优先替换，避免「上网」先吃掉「上网查」。 */
    private val aliasPairs: List<Pair<String, String>> = ALIASES.sortedByDescending { it.first.length }

    /**
     * 能力名 → 触发词。用户说触发词即命中该能力。
     *
     * 与 [ALIASES] 的区别：别名是**一对一等价**，这里是**一对多**——
     * 「搜索」这一个能力对应「搜一下/查一查/找资料/百度/google/新闻」等大量口语触发形态。
     */
    private val CAPACITIES: Map<String, List<String>> = mapOf(
        // 🔴 上面这批是「手段型」触发词（用户说出「上网查」）；下面这批是「效果型」（用户只说想要什么）。
        // 用户要「模糊搜索」，缺的就是后者：「这玩意现在多少钱」「外面怎么说」这类句子
        // 字面上与「搜索」零重叠，只靠手段型词永远命中不了。
        "web_search" to listOf(
            // —— 手段型：说清了怎么查 ——
            "联网", "上网", "网上", "查资料", "找资料", "搜一下", "查一查", "搜一搜",
            "百度", "google", "新闻", "资讯", "八卦", "热点", "调研",
            // —— 效果型：只说想要什么，不说手段（新增，这批才是「模糊」的关键）——
            "打听", "打听一下", "帮我打听", "外面怎么说", "外面是怎么说", "大家怎么说",
            "现在多少钱", "多少钱", "价格", "售价", "多少钱一个", "值不值",
            "最近有什么", "有什么新", "新说法", "最新进展", "怎么样了", "后续",
            "有没有这回事", "是真的吗", "靠不靠谱", "可信吗", "来源",
            "别人怎么评价", "口碑", "风评", "对比一下", "哪个好",
            "替代方案", "有哪些选择", "哪个牌子好", "推荐一下", "求推荐",
        ),
        "read_url" to listOf("这个网址", "打开链接", "网页内容", "抓取网页", "这个页面说了什么"),
        "speak" to listOf("念", "朗读", "读出来", "播报", "念给我听", "念首诗", "念一首诗"),
        "image_gen" to listOf("画", "出图", "作图", "生成图片", "文生图", "海报", "封面", "配图", "壁纸", "插画"),
        "codecanvas_markup" to listOf("画", "出图", "海报", "封面", "配图", "流程图", "示意图", "架构图", "确定性渲染", "排版整齐的海报"),
        "codecanvas_script" to listOf("脚本画", "编程画图", "代码渲染", "数据驱动绘图"),
        "set_alarm" to listOf("闹钟", "叫我", "定时", "提醒我", "别忘", "到点"),
        "build_apk" to listOf("打包", "构建", "编译", "出包", "出 apk"),
        "export_apk" to listOf("导出", "拿出包", "给我 apk", "导出安装包"),
        "write_file" to listOf("写入", "保存", "存起来", "存一下", "落盘", "写文件"),
        "read_text_file" to listOf("读取", "读文件", "看看文件", "打开文件", "读一下"),
        "delete_file" to listOf("删除", "删了", "干掉", "清掉", "扔掉", "删文件"),
        "launch_app" to listOf("启动", "打开应用", "开个", "进那个", "运行一下"),
        "read_screen" to listOf("看屏幕", "读屏", "看看屏幕", "当前界面", "屏幕内容"),
        "tap_screen" to listOf("点击", "点一下", "戳一下", "按一下", "点屏幕"),
        "input_text" to listOf("输入", "打字", "敲字", "填进去", "输入文字"),
        "run_code" to listOf("运行代码", "跑代码", "执行脚本", "跑脚本", "跑一下"),
        "get_current_time" to listOf("几点", "现在时间", "今天几号", "日期", "星期几"),
        "get_device_info" to listOf("设备信息", "手机信息", "什么手机", "硬件", "这台手机"),
        "send_sms" to listOf("发短信", "发信息", "短信", "回信息"),
        "memory_save" to listOf("记住", "记一下", "记着", "存记忆", "别忘了"),
        "memory_search" to listOf("想不起来", "之前说的", "查记忆", "回忆", "记的什么"),
        "ui_card" to listOf("小卡片", "卡片", "ui_card", "富卡片", "小卡片组件"),
        "ui_widget" to listOf("小控件", "ui_widget", "控件", "浮层组件"),
        "ui_control" to listOf("动态组件", "ui_control", "活组件", "交互组件"),
        "genui_draw" to listOf("画界面", "生成界面", "做界面", "整个界面", "ui 界面", "全屏界面"),
        "miniapp" to listOf("小程序", "网页应用", "web 应用", "做个应用", "轻应用"),
        "quroterm_exec" to listOf("终端", "命令行", "shell", "cmd", "执行命令", "跑命令"),
        "linux_run" to listOf("linux", "linux 环境", "子系统", "跑 linux"),
        "http_request" to listOf("接口", "api", "请求", "http", "调接口"),
        "ai_browser" to listOf("浏览器", "上网", "打开网页", "浏览"),
        "browser_act" to listOf("点网页", "填表单", "网页点击", "自动浏览", "爬网页"),
    )

    /** 整句意图 → 检索扩展词。解决长句里关键概念被淹没的问题。 */
    private val INTENT_EXPANSIONS: List<Pair<String, List<String>>> = listOf(
        "剪短" to listOf("视频", "剪辑", "转码", "ffmpeg", "时长", "截取", "片段"),
        "把歌弄成" to listOf("音频", "提取", "剪辑", "片段", "ffmpeg"),
        "联网查" to listOf("web_search", "联网", "搜索", "read_url", "抓取"),
        // 🔴 效果型整句意图：这些句式**不含任何手段词**，字面检索必然捞不到 web_search。
        // 与 CAPACITIES 的补充是两层：这里管整句形态，那里管零散短语。
        "现在多少钱" to listOf("web_search", "价格", "查询", "比价", "行情"),
        "帮我打听" to listOf("web_search", "查询", "信息", "资讯"),
        "外面怎么说" to listOf("web_search", "口碑", "评价", "讨论"),
        "有没有这回事" to listOf("web_search", "查证", "核实", "来源"),
        "最近有什么新" to listOf("web_search", "最新", "进展", "资讯"),
        "求推荐" to listOf("web_search", "对比", "评价", "排行"),
        "哪个好" to listOf("web_search", "对比", "评测", "口碑"),
        "做个界面" to listOf("genui", "界面", "画布", "ui", "动态组件", "quro-ui"),
        "看数据" to listOf("图表", "可视化", "ui_card", "canvas", "统计"),
        "装到手机上" to listOf("构建", "打包", "apk", "导出", "安装"),
        "读文件里的内容" to listOf("read_text_file", "读取", "解析", "文件"),
        "让 AI 记住" to listOf("memory_save", "记忆", "写入", "保存"),
        "以前存的" to listOf("memory_search", "记忆", "检索", "回忆"),
        "打电话" to listOf("dial", "拨打", "号码", "通信"),
        "发消息给别人" to listOf("send_sms", "短信", "联系人"),
        "导航" to listOf("地图", "位置", "geocode", "地点"),
        "附近有什么" to listOf("位置", "地点", "搜索", "周边"),
        "翻译" to listOf("翻译", "translate", "语言"),
        "识别图里的字" to listOf("ocr", "识别", "文字提取", "图像识别"),
        "这张图什么" to listOf("视觉", "分析", "图像识别", "看图", "识图"),
        "把这段文字读给我" to listOf("朗读", "tts", "语音", "speak"),
        "控制手机" to listOf("点击", "滑动", "无障碍", "屏幕", "自动化"),
        "自动点" to listOf("点击", "无障碍", "屏幕", "自动化", "tap"),
    )

    /**
     * 查询归一：把等价说法折叠成同一形态。
     * 与旧 [com.ai.assistance.quro.core.tools.ToolTextMatcher.normalize] 的别名表**并存不冲突**——
     * 那张表服务旧检索路径，这张服务新引擎，两边都过一遍即可。
     *
     * 🔴 英文别名必须按**词边界**匹配，不能用裸 `contains`。
     * 实测事故：`ALIASES` 里有 `"net" to "联网"`，而 `normalize` 用 `t.replace("net", "联网")`，
     * 于是工具名 `get_network_info` 被归一化成 `get_联网work_info` —— 自己的名字再也匹配不上自己，
     * 实测检索时被 `web_search` 反超（5.855 vs 4.949）。
     * 短英文别名（net / web / ai / os）在标识符里 ubiquitous，裸包含匹配必然误伤。
     */
    fun normalize(text: String): String {
        var t = RagFuzzy.norm(text)
        if (t.isEmpty()) return t
        for ((from, to) in aliasPairs) {
            t = if (isAsciiWord(from)) replaceAsciiWord(t, from, to) else {
                if (t.contains(from)) t.replace(from, to) else t
            }
        }
        return t
    }

    /** 是否是纯 ASCII 词（需要按词边界匹配，避免污染 snake_case 标识符）。 */
    private fun isAsciiWord(s: String): Boolean =
        s.isNotEmpty() && s.all { it in 'a'..'z' || it in '0'..'9' || it == ' ' }

    /**
     * 按词边界替换 ASCII 词。
     *
     * 边界 = 非字母数字。`get_network_info` 里 `net` 前后是 `_`，都算边界之外——
     * 但 `_` 属于标识符内部，所以这里把 `_` 与 `-` 也算作「词内字符」，
     * 即 `net` 只有在前后是空格/标点/串首串尾时才替换。
     */
    private fun replaceAsciiWord(text: String, word: String, to: String): String {
        val sb = StringBuilder()
        var i = 0
        var changed = false
        while (i < text.length) {
            val hit = text.startsWith(word, i) &&
                (i == 0 || !isWordChar(text[i - 1])) &&
                (i + word.length >= text.length || !isWordChar(text[i + word.length]))
            if (hit) {
                sb.append(to); i += word.length; changed = true
            } else {
                sb.append(text[i]); i++
            }
        }
        return if (changed) sb.toString() else text
    }

    /** 标识符内部字符：`_` 与 `-` 都算，因此 net 不会命中 get_network_info。 */
    private fun isWordChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'

    /**
     * 能力反查：查询里出现了某能力的触发词，返回该能力名（按触发词长度降序，长触发更可信）。
     */
    fun expandToCapacities(query: String): List<String> {
        val q = normalize(query)
        if (q.isBlank()) return emptyList()
        val hits = ArrayList<Pair<String, Int>>()
        for ((capability, triggers) in CAPACITIES) {
            var best = 0
            for (t in triggers) {
                if (q.contains(normalize(t)) && normalize(t).length > best) best = normalize(t).length
            }
            if (best > 0) hits.add(capability to best)
        }
        return hits.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * 意图扩展词：把长句里隐含的概念补出来。
     * 🔴 只做**追加**：返回的是额外词，调用方必须与原查询一起参与打分。
     */
    fun expandIntent(query: String): List<String> {
        // 🔴 这里必须用**原文**匹配，不能先 normalize()。
        // normalize 会把「剪短」改写成「视频处理」，而 INTENT_EXPANSIONS 的触发词恰恰是
        // 「剪短」——先归一化等于把触发词自己抹掉，扩展词永远命中不了（实测返回空列表）。
        // 触发词表登记的是**用户原话**；归一化只该用在同义（ALIASES）与能力（CAPACITIES）两路上。
        val q = RagFuzzy.norm(query)
        if (q.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        // 局部变量不叫 words：`String.words()` 是 Kotlin 的扩展函数，
        // 解构出来的 words 会与它抢名字，编译报 "iterator() is ambiguous"。
        for ((trigger, expansion) in INTENT_EXPANSIONS) {
            if (q.contains(trigger)) out.addAll(expansion)
        }
        return out.toList()
    }

    /**
     * 能力触发词全集（供索引构建期给文档补侧面关键词）。
     * 文档在索引里额外挂上自己能力的触发词，「模糊查询」就能撞上来。
     */
    fun triggersOf(capability: String): List<String> =
        CAPACITIES[capability]?.map { normalize(it) }.orEmpty()
}