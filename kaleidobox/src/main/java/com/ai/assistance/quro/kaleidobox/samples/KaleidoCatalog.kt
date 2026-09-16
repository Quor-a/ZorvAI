package com.ai.assistance.quro.kaleidobox.samples

import com.ai.assistance.quro.kaleidobox.core.KaleidoRuntime
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
    ) {
        val displayName: String get() = if (nameZh.isNotBlank()) nameZh else nameEn
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
      "capabilities": ["ai.chat", "ai.available", "data.kv", "ui.clipboard"],
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
      "description": { "zh": "应用内浏览器，直接渲染网页，不跳出 App", "en": "In-app browser rendering web pages" },
      "authors": ["ZorvAI"],
      "keywords": ["web", "browser", "ui"],
      "runtime": [ { "id": "main", "lang": "kotlin", "engine": "jvm_dex", "entry": "com.ai.assistance.quro.kaleidobox.samples.WebUiToolkit" } ],
      "units": [
        { "name": "about_webui", "runtime": "main", "target": "main:about_webui", "title": { "zh": "关于" }, "description": "返回工具说明" }
      ],
      "capabilities": ["net.http", "data.kv", "ui.clipboard"],
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
      "description": { "zh": "持久化设定/知识卡片仓库（分类·正面·背面）", "en": "Persistent setting/knowledge card store" },
      "authors": ["ZorvAI"],
      "keywords": ["worldbook", "memory", "cards"],
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
        ),
        CatalogEntry(
            "dev.kaleidobox.terminal", "1.0.0", "终端", "Terminal",
            "在宿主的终端/Linux 环境同步执行命令", listOf("terminal", "shell", "tool"), Kind.BUILTIN,
            "com.ai.assistance.quro.kaleidobox.samples.TerminalToolkit", TERMINAL_MANIFEST,
        ),
        CatalogEntry(
            "dev.kaleidobox.devinfo", "1.0.0", "设备信息", "Device Info",
            "读取宿主 App 与设备信息，一键复制", listOf("device", "info", "tool"), Kind.BUILTIN,
            "com.ai.assistance.quro.kaleidobox.samples.DevInfoToolkit", DEVINFO_MANIFEST,
        ),
        CatalogEntry(
            "dev.kaleidobox.clipboard", "1.0.0", "剪贴板", "Clipboard",
            "读写系统剪贴板", listOf("clipboard", "tool"), Kind.BUILTIN,
            "com.ai.assistance.quro.kaleidobox.samples.ClipboardToolkit", CLIPBOARD_MANIFEST,
        ),
        CatalogEntry(
            "dev.kaleidobox.nettool", "1.0.0", "联网请求", "HTTP Tool",
            "向 https 地址发起 GET 请求", listOf("http", "network", "tool"), Kind.BUILTIN,
            "com.ai.assistance.quro.kaleidobox.samples.NetToolToolkit", NETTOOL_MANIFEST,
        ),
        CatalogEntry(
            "dev.kaleidobox.apktool", "1.0.0", "APK 逆向", "APK Reverse",
            "列应用、查组件/权限/签名、拉取 APK、列内部 DEX", listOf("apk", "reverse", "tool"), Kind.BUILTIN,
            "com.ai.assistance.quro.kaleidobox.samples.ApkToolToolkit", APK_MANIFEST,
        ),
        CatalogEntry(
            "dev.kaleidobox.webui", "1.0.0", "WebUI 浏览器", "WebUI Browser",
            "应用内浏览器，直接渲染网页", listOf("web", "browser", "ui"), Kind.BUILTIN,
            "com.ai.assistance.quro.kaleidobox.samples.WebUiToolkit", WEBUI_MANIFEST,
        ),
        CatalogEntry(
            "dev.kaleidobox.worldbook", "1.0.0", "世界书", "WorldBook",
            "持久化设定/知识卡片仓库", listOf("worldbook", "memory", "cards"), Kind.BUILTIN,
            "com.ai.assistance.quro.kaleidobox.samples.WorldBookToolkit", WORLDBOOK_MANIFEST,
        ),
    )

    fun find(id: String): CatalogEntry? = entries.firstOrNull { it.id == id }

    /** 安装全部 BUILTIN 条目（幂等）。宿主初始化与"装示例包"按钮都走这里。 */
    fun installBuiltins(runtime: KaleidoRuntime) {
        entries.filter { it.kind == Kind.BUILTIN }.forEach { e ->
            if (runtime.installedPackages().any { it.id == e.id }) return@forEach
            runCatching {
                val src = MemoryPackageSource(
                    mapOf("kaleido.json" to e.manifestJson.toByteArray(StandardCharsets.UTF_8))
                )
                runtime.install(src, KaleidoRuntime.InstallOptions(trusted = true, source = InstallSource.BUILTIN))
            }
        }
    }

    /** 给 AI 工具用的目录文本描述。 */
    fun describe(): String = buildString {
        appendLine("KaleidoBox 插件目录（${entries.size} 个，均为 BUILTIN 已编入安装包）：")
        entries.forEach { e ->
            appendLine("- ${e.id} | ${e.displayName} | ${e.descZh}")
            appendLine("  units: ${unitNames(e)}")
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
