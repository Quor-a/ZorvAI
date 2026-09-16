package com.ai.assistance.quro.kaleidobox.samples

import com.ai.assistance.quro.kaleidobox.core.KaleidoRuntime
import com.ai.assistance.quro.kaleidobox.core.manifest.ManifestParser
import com.ai.assistance.quro.kaleidobox.core.manifest.ManifestValidator
import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.registry.InstallSource
import com.ai.assistance.quro.kaleidobox.core.registry.MemoryPackageSource
import java.nio.charset.StandardCharsets

/**
 * KaleidoBox 实用 APP 级插件目录（catalog）。
 *
 * 这里列出的全部是**真正能用的工具包**，且都接住了宿主 App 的真实子系统：
 *  - AI 对话引擎（用户已配 provider）         ← `ai.chat`
 *  - 终端 / Linux 环境（proot）              ← `app.term.run`
 *  - 设备 / 宿主信息                          ← `sys.package.info`
 *  - 剪贴板                                   ← `ui.clipboard`
 *  - 联网（受策略约束）                        ← `net.http`
 *
 * 全部为 BUILTIN（Kotlin 类编进 :kaleidobox 模块，随宿主 classpath 分发，最稳），
 * 不再有"计数器/便签/单位换算"这类玩具，也不再有只在设备内编译、缺能力的演示 SRC。
 *
 * 目录即单一事实源：[KaleidoBoxSamples.installBuiltins] 从这里取条目安装。
 */
object KaleidoCatalog {

    enum class Kind { BUILTIN, SRC }

    /**
     * 工具包分组：列表按这个顺序分区展示。
     *
     * [order] 越小越靠前；第三方导入的包没有分组，落到 [OTHER]。
     */
    enum class Group(val label: String, val order: Int) {
        AI_CONTENT("AI 与内容", 10),
        SYSTEM_DEVICE("系统与设备", 20),
        DEV_TERMINAL("开发与终端", 30),
        NET_BROWSE("网络与浏览", 40),
        OTHER("其他", 90),
    }

    /**
     * 标签显示名。清单里的 `keywords`／目录里的 `tags` 是英文机器标签（用于检索、匹配），
     * 界面不应该直接把 `worldbook` 这种词甩给用户，所以集中在这里做一次中文化。
     */
    object TagLabels {
        /** 噪音标签：几乎每个包都有，展示出来没有信息量。 */
        private val noisy = setOf("tool", "plugin")

        private val zh = mapOf(
            "ai" to "AI", "chat" to "对话", "llm" to "大模型",
            "terminal" to "终端", "shell" to "Shell", "proc" to "进程",
            "device" to "设备", "info" to "信息", "clipboard" to "剪贴板",
            "settings" to "设置", "storage" to "存储",
            "http" to "HTTP", "network" to "网络", "net" to "网络", "download" to "下载",
            "apk" to "APK", "reverse" to "逆向", "dex" to "DEX", "dev" to "开发",
            "web" to "网页", "browser" to "浏览器", "ui" to "界面",
            "worldbook" to "世界书", "lorebook" to "Lorebook", "memory" to "记忆",
            "prompt" to "提示词", "text" to "文本",
        )

        fun zhOf(tag: String): String = zh[tag.lowercase()] ?: tag

        fun isNoisy(tag: String): Boolean = tag.lowercase() in noisy

        /** 用于界面展示：去掉噪音标签、中文化、去重、限量。 */
        fun display(tags: List<String>, max: Int = 3): List<String> =
            tags.filter { !isNoisy(it) }
                .map { zhOf(it) }
                .filter { it.isNotBlank() }
                .distinct()
                .take(max)
    }

    data class CatalogEntry(
        val id: String,
        val version: String,
        val nameZh: String,
        val nameEn: String,
        val descZh: String,
        val tags: List<String>,
        val kind: Kind,
        val entryClass: String,
        val manifestJson: String,
        val source: String? = null,
        /** 列表分区用的分组。 */
        val group: Group = Group.OTHER,
    ) {
        val displayName: String get() = if (nameZh.isNotBlank()) nameZh else nameEn

        /** 界面用标签（中文、去噪、限量）。 */
        val displayTags: List<String> get() = TagLabels.display(tags)

        /** 界面用类型徽标（别直接把 BUILTIN/SRC 甩给用户）。 */
        val kindLabel: String get() = when (kind) {
            Kind.BUILTIN -> "内置"
            Kind.SRC -> "端侧编译"
        }
    }

    // ---------------- 清单（BUILTIN，实用 APP 级） ----------------

    private val AI_MANIFEST = """
    {
      "schema": 1,
      "id": "dev.kaleidobox.aiassistant",
      "version": "1.0.0",
      "name": { "zh": "AI 助手", "en": "AI Assistant" },
      "description": { "zh": "直接调用宿主已配的 AI 对话引擎提问，结果回显在面板", "en": "Ask the host AI engine and show the reply" },
      "authors": ["ZorvAI"],
      "keywords": ["ai", "chat", "tool"],
      "runtime": [ { "id": "main", "lang": "kotlin", "engine": "jvm_dex", "entry": "com.ai.assistance.quro.kaleidobox.samples.AiAssistantToolkit" } ],
      "units": [
        { "name": "about_aiassistant", "runtime": "main", "target": "main:about_aiassistant", "title": { "zh": "关于" }, "description": "返回工具说明" },
        { "name": "ask", "runtime": "main", "target": "main:ask", "title": { "zh": "提问" }, "description": "用宿主 AI 回答一个问题", "params": { "type": "object", "properties": { "prompt": { "type": "string" }, "system": { "type": "string" } } } }
      ],
      "capabilities": ["ai.chat", "ai.available", "data.kv", "ui.clipboard", "ui.toast", "worldbook.entries"],
      "sandbox": { "level": "in_process", "memoryMb": 64, "netEgress": "deny" },
      "ui": [ { "id": "main", "surface": "toolbox", "render": "main:render", "onAction": "main:onAction", "title": { "zh": "AI 助手" } } ]
    }
    """.trimIndent()

    private val TERMINAL_MANIFEST = """
    {
      "schema": 1,
      "id": "dev.kaleidobox.terminal",
      "version": "1.0.0",
      "name": { "zh": "终端", "en": "Terminal" },
      "description": { "zh": "在宿主的终端/Linux 环境同步执行命令并回显输出", "en": "Run a command in the host terminal/Linux env" },
      "authors": ["ZorvAI"],
      "keywords": ["terminal", "shell", "tool"],
      "runtime": [ { "id": "main", "lang": "kotlin", "engine": "jvm_dex", "entry": "com.ai.assistance.quro.kaleidobox.samples.TerminalToolkit" } ],
      "units": [
        { "name": "about_terminal", "runtime": "main", "target": "main:about_terminal", "title": { "zh": "关于" }, "description": "返回工具说明" },
        { "name": "run", "runtime": "main", "target": "main:run", "title": { "zh": "执行" }, "description": "执行一条命令", "params": { "type": "object", "properties": { "command": { "type": "string" } } } }
      ],
      "capabilities": ["app.term.run", "data.kv", "ui.clipboard"],
      "sandbox": { "level": "in_process", "memoryMb": 64, "netEgress": "deny" },
      "ui": [ { "id": "main", "surface": "toolbox", "render": "main:render", "onAction": "main:onAction", "title": { "zh": "终端" } } ]
    }
    """.trimIndent()

    private val DEVINFO_MANIFEST = """
    {
      "schema": 1,
      "id": "dev.kaleidobox.devinfo",
      "version": "1.0.0",
      "name": { "zh": "设备信息", "en": "Device Info" },
      "description": { "zh": "读取宿主 App 与设备信息，一键复制", "en": "Show host App and device info, copy with one tap" },
      "authors": ["ZorvAI"],
      "keywords": ["device", "info", "tool"],
      "runtime": [ { "id": "main", "lang": "kotlin", "engine": "jvm_dex", "entry": "com.ai.assistance.quro.kaleidobox.samples.DevInfoToolkit" } ],
      "units": [
        { "name": "about_devinfo", "runtime": "main", "target": "main:about_devinfo", "title": { "zh": "关于" }, "description": "返回工具说明" }
      ],
      "capabilities": ["sys.package.info", "sys.device.info", "ui.clipboard", "ui.toast"],
      "sandbox": { "level": "in_process", "memoryMb": 32, "netEgress": "deny" },
      "ui": [ { "id": "main", "surface": "toolbox", "render": "main:render", "onAction": "main:onAction", "title": { "zh": "设备信息" } } ]
    }
    """.trimIndent()

    private val CLIPBOARD_MANIFEST = """
    {
      "schema": 1,
      "id": "dev.kaleidobox.clipboard",
      "version": "1.0.0",
      "name": { "zh": "剪贴板", "en": "Clipboard" },
      "description": { "zh": "读写系统剪贴板，可复制/粘贴文本", "en": "Read and write the system clipboard" },
      "authors": ["ZorvAI"],
      "keywords": ["clipboard", "tool"],
      "runtime": [ { "id": "main", "lang": "kotlin", "engine": "jvm_dex", "entry": "com.ai.assistance.quro.kaleidobox.samples.ClipboardToolkit" } ],
      "units": [
        { "name": "about_clipboard", "runtime": "main", "target": "main:about_clipboard", "title": { "zh": "关于" }, "description": "返回工具说明" }
      ],
      "capabilities": ["ui.clipboard", "ui.toast", "data.kv"],
      "sandbox": { "level": "in_process", "memoryMb": 32, "netEgress": "deny" },
      "ui": [ { "id": "main", "surface": "toolbox", "render": "main:render", "onAction": "main:onAction", "title": { "zh": "剪贴板" } } ]
    }
    """.trimIndent()

    private val NETTOOL_MANIFEST = """
    {
      "schema": 1,
      "id": "dev.kaleidobox.nettool",
      "version": "1.0.0",
      "name": { "zh": "联网请求", "en": "HTTP Tool" },
      "description": { "zh": "向 https 地址发起 GET 请求并回显响应", "en": "Make an https GET request and show the response" },
      "authors": ["ZorvAI"],
      "keywords": ["http", "network", "tool"],
      "runtime": [ { "id": "main", "lang": "kotlin", "engine": "jvm_dex", "entry": "com.ai.assistance.quro.kaleidobox.samples.NetToolToolkit" } ],
      "units": [
        { "name": "about_nettool", "runtime": "main", "target": "main:about_nettool", "title": { "zh": "关于" }, "description": "返回工具说明" },
        { "name": "fetch", "runtime": "main", "target": "main:fetch", "title": { "zh": "请求" }, "description": "请求一个 URL", "params": { "type": "object", "properties": { "url": { "type": "string" } } } }
      ],
      "capabilities": ["net.http", "ui.clipboard", "data.kv"],
      "sandbox": { "level": "in_process", "memoryMb": 32, "netEgress": "allow" },
      "ui": [ { "id": "main", "surface": "toolbox", "render": "main:render", "onAction": "main:onAction", "title": { "zh": "联网请求" } } ]
    }
    """.trimIndent()

    private val APK_MANIFEST = """
    {
      "schema": 1,
      "id": "dev.kaleidobox.apktool",
      "version": "1.0.0",
      "name": { "zh": "APK 逆向", "en": "APK Reverse" },
      "description": { "zh": "列应用、查组件/权限/签名、拉取 APK、列内部 DEX", "en": "List apps, inspect manifest, pull APK, list DEX" },
      "authors": ["ZorvAI"],
      "keywords": ["apk", "reverse", "tool"],
      "runtime": [ { "id": "main", "lang": "kotlin", "engine": "jvm_dex", "entry": "com.ai.assistance.quro.kaleidobox.samples.ApkToolToolkit" } ],
      "units": [
        { "name": "about_apk", "runtime": "main", "target": "main:about_apk", "title": { "zh": "关于" }, "description": "返回工具说明" }
      ],
      "capabilities": ["app.apk.list", "app.apk.info", "app.apk.pull", "app.apk.dex", "ui.toast", "ui.clipboard"],
      "sandbox": { "level": "in_process", "memoryMb": 64, "netEgress": "deny" },
      "ui": [ { "id": "main", "surface": "toolbox", "render": "main:render", "onAction": "main:onAction", "title": { "zh": "APK 逆向" } } ]
    }
    """.trimIndent()

    private val WEBUI_MANIFEST = """
    {
      "schema": 1,
      "id": "dev.kaleidobox.webui",
      "version": "1.0.0",
      "name": { "zh": "WebUI 浏览器", "en": "WebUI Browser" },
      "description": { "zh": "应用内多标签浏览器：前进后退、书签、历史、页内查找、桌面版、下载", "en": "In-app multi-tab browser with bookmarks, history, find-in-page" },
      "authors": ["ZorvAI"],
      "keywords": ["web", "browser", "ui"],
      "runtime": [ { "id": "main", "lang": "kotlin", "engine": "jvm_dex", "entry": "com.ai.assistance.quro.kaleidobox.samples.WebUiToolkit" } ],
      "units": [
        { "name": "about_webui", "runtime": "main", "target": "main:about_webui", "title": { "zh": "关于" }, "description": "返回工具说明" }
      ],
      "capabilities": ["net.http", "data.kv", "ui.clipboard", "ui.toast"],
      "sandbox": { "level": "in_process", "memoryMb": 64, "netEgress": "allow" },
      "ui": [ { "id": "main", "surface": "toolbox", "render": "main:render", "onAction": "main:onAction", "title": { "zh": "WebUI 浏览器" } } ]
    }
    """.trimIndent()

    private val WORLDBOOK_MANIFEST = """
    {
      "schema": 1,
      "id": "dev.kaleidobox.worldbook",
      "version": "1.0.0",
      "name": { "zh": "世界书", "en": "WorldBook" },
      "description": { "zh": "Lorebook 提示词注入引擎：关键词/次关键词+触发逻辑、插入深度、权重、概率、常驻、包含组、递归，可测试扫描", "en": "Lorebook prompt-injection engine with keys, logic, depth, weight, probability" },
      "authors": ["ZorvAI"],
      "keywords": ["worldbook", "lorebook", "memory"],
      "runtime": [ { "id": "main", "lang": "kotlin", "engine": "jvm_dex", "entry": "com.ai.assistance.quro.kaleidobox.samples.WorldBookToolkit" } ],
      "units": [
        { "name": "about_worldbook", "runtime": "main", "target": "main:about_worldbook", "title": { "zh": "关于" }, "description": "返回工具说明" }
      ],
      "capabilities": ["data.kv", "ui.clipboard", "ui.toast"],
      "sandbox": { "level": "in_process", "memoryMb": 32, "netEgress": "deny" },
      "ui": [ { "id": "main", "surface": "toolbox", "render": "main:render", "onAction": "main:onAction", "title": { "zh": "世界书" } } ]
    }
    """.trimIndent()

    // ---------------- 目录 ----------------

    val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            "dev.kaleidobox.aiassistant", "1.0.0", "AI 助手", "AI Assistant",
            "直接调用宿主已配的 AI 对话引擎提问", listOf("ai", "chat", "tool"), Kind.BUILTIN,
            "com.ai.assistance.quro.kaleidobox.samples.AiAssistantToolkit", AI_MANIFEST,
            group = Group.AI_CONTENT,
        ),
        CatalogEntry(
            "dev.kaleidobox.terminal", "1.0.0", "终端", "Terminal",
            "在宿主的终端/Linux 环境同步执行命令", listOf("terminal", "shell", "tool"), Kind.BUILTIN,
            "com.ai.assistance.quro.kaleidobox.samples.TerminalToolkit", TERMINAL_MANIFEST,
            group = Group.DEV_TERMINAL,
        ),
        CatalogEntry(
            "dev.kaleidobox.devinfo", "1.0.0", "设备信息", "Device Info",
            "读取宿主 App 与设备信息，一键复制", listOf("device", "info", "tool"), Kind.BUILTIN,
            "com.ai.assistance.quro.kaleidobox.samples.DevInfoToolkit", DEVINFO_MANIFEST,
            group = Group.SYSTEM_DEVICE,
        ),
        CatalogEntry(
            "dev.kaleidobox.clipboard", "1.0.0", "剪贴板", "Clipboard",
            "读写系统剪贴板", listOf("clipboard", "tool"), Kind.BUILTIN,
            "com.ai.assistance.quro.kaleidobox.samples.ClipboardToolkit", CLIPBOARD_MANIFEST,
            group = Group.SYSTEM_DEVICE,
        ),
        CatalogEntry(
            "dev.kaleidobox.nettool", "1.0.0", "联网请求", "HTTP Tool",
            "向 https 地址发起 GET 请求", listOf("http", "network", "tool"), Kind.BUILTIN,
            "com.ai.assistance.quro.kaleidobox.samples.NetToolToolkit", NETTOOL_MANIFEST,
            group = Group.NET_BROWSE,
        ),
        CatalogEntry(
            "dev.kaleidobox.apktool", "1.0.0", "APK 逆向", "APK Reverse",
            "列应用、查组件/权限/签名、拉取 APK、列内部 DEX", listOf("apk", "reverse", "tool"), Kind.BUILTIN,
            "com.ai.assistance.quro.kaleidobox.samples.ApkToolToolkit", APK_MANIFEST,
            group = Group.DEV_TERMINAL,
        ),
        CatalogEntry(
            "dev.kaleidobox.webui", "1.0.0", "WebUI 浏览器", "WebUI Browser",
            "应用内多标签浏览器：前进后退、书签、历史、查找、桌面版", listOf("web", "browser", "ui"), Kind.BUILTIN,
            "com.ai.assistance.quro.kaleidobox.samples.WebUiToolkit", WEBUI_MANIFEST,
            group = Group.NET_BROWSE,
        ),
        CatalogEntry(
            "dev.kaleidobox.worldbook", "1.0.0", "世界书", "WorldBook",
            "Lorebook 提示词注入引擎（关键词触发/深度/权重/递归）", listOf("worldbook", "lorebook", "memory"), Kind.BUILTIN,
            "com.ai.assistance.quro.kaleidobox.samples.WorldBookToolkit", WORLDBOOK_MANIFEST,
            group = Group.AI_CONTENT,
        ),
    )

    fun find(id: String): CatalogEntry? = entries.firstOrNull { it.id == id }

    /**
     * 按分组归类（组内保持目录顺序），分组按 [Group.order] 排序。
     * 界面「已安装」与「插件目录」两个列表都用它分区，避免两处各写一遍分组逻辑。
     */
    fun grouped(list: List<CatalogEntry> = entries): List<Pair<Group, List<CatalogEntry>>> =
        list.groupBy { it.group }
            .toList()
            .sortedBy { it.first.order }
            .map { it.first to it.second }

    /** 目录里在用的标签（去噪，按出现次数降序），供界面做标签筛选行。 */
    fun tagsInUse(): List<String> = entries.flatMap { it.tags }
        .filter { !TagLabels.isNoisy(it) }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .map { it.key }

    /**
     * 内置清单自检：把每个清单跑一遍**宿主安装时用的同一套校验器**。
     *
     * 内置清单是编译期常量，只在用户点「安装」那一刻才会被校验——改坏了（例如放了
     * `netEgress: allow` 却漏了 `net.http`）下场就是真机上弹「清单校验失败」，改代码的人
     * 当时完全不知道。这里留一个可主动调用的体检入口：日志、自检页、单测都能用。
     *
     * @return id → 错误列表；空 Map 表示全部通过。
     */
    fun validateAll(): Map<String, List<String>> {
        val bad = LinkedHashMap<String, List<String>>()
        entries.forEach { e ->
            val errs = runCatching { ManifestParser.parse(e.manifestJson) }
                .map { m ->
                    ManifestValidator.validate(m)
                        .filter { it.level == ManifestValidator.Level.ERROR }
                        .map { it.toString() }
                }
                .getOrElse { listOf("解析失败: ${it.message}") }
            if (errs.isNotEmpty()) bad[e.id] = errs
        }
        return bad
    }

    /**
     * 安装全部 BUILTIN 条目（幂等）。宿主初始化与"装示例包"按钮都走这里。
     *
     * @return 装失败的 条目 id → 原因；空 Map = 全部就绪。
     *         以前这里用 `runCatching {}` 把异常**静默吞掉**，于是某个内置包清单坏了，
     *         界面表现只是"这个包没出现"，看不出任何原因——现在把失败原因交回调用方。
     */
    fun installBuiltins(runtime: KaleidoRuntime): Map<String, String> {
        val failed = LinkedHashMap<String, String>()
        entries.filter { it.kind == Kind.BUILTIN }.forEach { e ->
            if (runtime.installedPackages().any { it.id == e.id }) return@forEach
            runCatching {
                val src = MemoryPackageSource(
                    mapOf("kaleido.json" to e.manifestJson.toByteArray(StandardCharsets.UTF_8))
                )
                runtime.install(src, KaleidoRuntime.InstallOptions(trusted = true, source = InstallSource.BUILTIN))
            }.onFailure { failed[e.id] = it.message ?: it.javaClass.simpleName }
        }
        return failed
    }

    /** 给 AI 工具用的目录文本描述（按分组分区，与界面一致）。 */
    fun describe(): String = buildString {
        appendLine("KaleidoBox 插件目录（${entries.size} 个，均为 BUILTIN 已编入安装包）：")
        grouped().forEach { (group, list) ->
            appendLine("【${group.label}】")
            list.forEach { e ->
                val tags = e.displayTags.takeIf { it.isNotEmpty() }?.joinToString("/") ?: ""
                appendLine("- ${e.id} | ${e.displayName} | $tags | ${e.descZh}")
                appendLine("  units: ${unitNames(e)}")
            }
        }
    }

    private fun unitNames(e: CatalogEntry): String {
        val names = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").findAll(e.manifestJson)
            .map { it.groupValues[1] }
            .filter { !it.startsWith("about_") }
            .toList()
        return names.joinToString(", ")
    }
}
