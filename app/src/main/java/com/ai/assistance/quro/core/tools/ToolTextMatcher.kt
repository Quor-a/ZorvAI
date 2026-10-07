package com.ai.assistance.quro.core.tools

/**
 * 工具检索的文本处理：中英混合分词 + 同义词归一。
 *
 * ## 为什么需要它
 * [ToolCapabilityDirectory.matchToolsByIntent] 要把「用户随口一句中文」对上
 * 「工具名/描述/场景/示例」。而工具这侧的文本是**中英混排**的：
 * `export_apk` /「端侧APK构建」/「把导出的产物给用户」/「FFmpeg 视频处理」。
 * 不分词直接做子串匹配会两头落空：
 *  - 用户说「导出 APK」匹配不到 `export_apk`（中文 vs 英文标识符）；
 *  - 用户说「念一首诗」匹配不到 `speak`（同义不同词）。
 *
 * ## 分词策略（够用即可，不追求语言学完美）
 *  - **中文**：先按标点/空白切开，再对每段生成 **bigram**（相邻两字组合）。
 *    「设个闹钟」→「设个」「个闹」「闹钟」。bigram 对中文检索的召回明显优于单字，
 *    又不需要引入分词词典依赖。单字段长度 1 时保留单字。
 *  - **英文/数字**：按 `_`、`-`、驼峰、空白切成词，并保留原词本身
 *    （`export_apk` → `export`,`apk`，也能整体命中 `export_apk`）。
 *  - 全部转小写去空白，保证「APK」「apk」「Apk」等价。
 *
 * ## 同义词
 * [synonyms] 是一张**双向**归一表：查询侧与工具侧都过一遍 [normalize]，
 * 把「念/朗读/读」归到 `朗读`，把「导出/输出」归到 `导出`。
 * 这样「念一首诗」与工具描述里的「朗读文本」就能对上。
 * 词条按**长词优先**替换，避免「导出」先被「出」吃掉这类问题。
 */
internal object ToolTextMatcher {

    /** 同义词归一表：key 与 value 视为等价词组。两侧文本都会经过它。 */
    private val synonyms: List<Pair<String, String>> = listOf(
        // 语音
        "念" to "朗读", "读出来" to "朗读", "念出来" to "朗读", "语音播报" to "朗读",
        "tts" to "朗读", "speak" to "朗读", "播报" to "朗读",
        // 图像产出
        "画" to "出图", "绘制" to "出图", "生成图片" to "出图", "作图" to "出图",
        "生图" to "出图", "p图" to "图片", "图片处理" to "图片",
        "海报" to "出图", "截图" to "屏幕",
        // 视频
        "剪视频" to "视频", "剪辑" to "视频", "转码" to "视频", "录像" to "视频",
        // 文件
        "删掉" to "删除", "移除" to "删除", "去掉" to "删除", "erase" to "删除",
        "delete" to "删除", "rm" to "删除",
        "新建文件" to "写入", "保存文件" to "写入", "写文件" to "写入", "存一下" to "写入",
        // 检索
        "搜一下" to "搜索", "查一下" to "搜索", "搜一搜" to "搜索", "找一下" to "搜索",
        "百度" to "搜索", "google" to "搜索", "检索" to "搜索", "search" to "搜索",
        // 🔴 「新闻/资讯/消息」→ 搜索：用户说「搜一下最近的新闻」时，真正该给的是 web_search，
        //   但描述里未必出现「新闻」二字，靠同义词把语义接上（实测此前掉出前 5）。
        "新闻" to "搜索", "资讯" to "搜索", "消息" to "搜索", "报道" to "搜索",
        "八卦" to "搜索", "热点" to "搜索", "动态" to "搜索",
        // 设备
        "设备信息" to "设备", "手机信息" to "设备", "机器信息" to "设备",
        "什么手机" to "设备", "设备状态" to "设备",
        // 应用
        "打开应用" to "启动", "开一下" to "启动", "跑一下" to "启动", "launch" to "启动",
        // 打包
        "打包" to "构建", "编译" to "构建", "打成" to "构建", "build" to "构建",
        "导出apk" to "导出", "拿出apk" to "导出", "export" to "导出",
        // 提醒
        "闹钟" to "提醒", "定时" to "提醒", "提醒我" to "提醒", "alarm" to "提醒",
        // 屏幕
        "看屏幕" to "屏幕", "读屏" to "屏幕", "截图" to "屏幕", "截图看下" to "屏幕",
        // 代码执行
        "跑一下代码" to "运行", "执行代码" to "运行", "跑起来" to "运行", "run" to "运行",
        // 小程序/界面
        "小程序" to "应用界面", "做个界面" to "界面", "做个页面" to "界面",
    )

    /**
     * 同义词替换顺序：**长词优先**。
     *
     * 🔴 不排序会静默失效：词表里「搜一下 → 搜索」在「搜索 → 搜索」之前，
     * 但如果「出图 → 出图」排在「导出apk → 导出」前面就会先把「导出apk」里的
     * 「出」段改坏。所以固定按 `from` 长度降序，保证「搜一下」先于任何以它为前缀的短词。
     */
    private val synonymPairs: List<Pair<String, String>> =
        synonyms.sortedByDescending { it.first.length }

    /**
     * 归一化：转小写、空白压缩，并按同义词表把等价词统一。
     * 只在**已分好词**的片段上调用，避免长串被误替换。
     */
    fun normalize(token: String): String {
        var t = token.lowercase().trim()
        if (t.isEmpty()) return t
        for ((from, to) in synonymPairs) {
            if (t.contains(from)) t = t.replace(from, to)
        }
        return t
    }

    /**
     * 中英混合分词。
     *
     * @return 去重后的 token 列表（顺序稳定，便于测试断言）
     */
    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val raw = LinkedHashSet<String>()

        // 1) 先按非字母数字（含 CJK）切段，英文标识符与中文自然分开
        val segments = SEGMENT_SPLIT.split(text.lowercase())
        for (seg in segments) {
            if (seg.isBlank()) continue
            // 2) 英文段：再按下划线/连字符/驼峰切（lowercase 后驼峰已无意义，靠分隔符）
            val latin = UNDERSCORE_SPLIT.split(seg).filter { it.isNotBlank() }
            for (w in latin) {
                // 纯数字片段（如 1080）对语义无贡献，跳过；含字母的（api、v2）保留
                if (w.any { it.isLetter() }) raw.add(normalize(w))
            }
            // 3) 中文段：bigram（长度 1 时保留单字）
            if (hasCjk(seg)) {
                val chars = seg.filter { isCjk(it) || it.isDigit() }
                if (chars.length == 1) {
                    raw.add(normalize(chars.toString()))
                } else {
                    for (i in 0 until chars.length - 1) {
                        raw.add(normalize(chars.substring(i, i + 2)))
                    }
                }
            }
        }
        // 4) 原始整段（长度 >= 2）也作为 token，支持「导出APK」这类整词命中
        val compact = text.lowercase().replace(" ", "")
        if (compact.length in 2..12) raw.add(normalize(compact))

        return raw.filter { it.isNotBlank() }.toList()
    }

    private fun hasCjk(s: String) = s.any { isCjk(it) }

    private fun isCjk(c: Char) = c.code in 0x4E00..0x9FFF

    /** 非字母数字与非 CJK 的分隔符（保留下划线与连字符，供英文再切）。 */
    private val SEGMENT_SPLIT = Regex("[^\\p{L}\\p{N}_-]+")

    private val UNDERSCORE_SPLIT = Regex("[_-]+")
}