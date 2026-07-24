package com.ai.assistance.quro.core.cms

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * CMS v2 模块仓库：模块清单以 [org.json] 持久化到 filesDir/cms_modules.json，
 * 协议头 apiVersion 固定为 `cms.io/v2`。
 *
 * v3 重构：所有种子能力均为纯应用内执行（intent / js / api），
 * 不再包含任何 shell / root / Shizuku / 无障碍真实执行能力。
 */
class QuroCmsRepository(context: Context) {

    private val file = File(context.filesDir, "cms_modules.json")

    // ---------- 读写 ----------

    fun load(): List<QuroCmsModule> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("modules") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                runCatching { parseModule(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun save(list: List<QuroCmsModule>) {
        val arr = JSONArray()
        list.forEach { arr.put(serializeModule(it)) }
        file.writeText(JSONObject().put("apiVersion", "cms.io/v2").put("modules", arr).toString(2))
    }

    fun get(id: String): QuroCmsModule? = load().firstOrNull { it.id == id }

    fun upsert(m: QuroCmsModule) {
        val all = load().filter { it.id != m.id } + m
        save(all)
    }

    fun uninstall(id: String) {
        save(load().filter { it.id != id })
    }

    /** 所有模块的所有能力（用于「能力」页列出与调用）。 */
    fun loadCapabilities(): List<Pair<QuroCmsModule, QuroCmsCapability>> =
        load().flatMap { m -> m.capabilities.map { m to it } }

    // ---------- 校验 ----------

    /** 结构校验：apiVersion 必须以 cms.io/v2 开头。 */
    fun verifyManifest(json: JSONObject): Boolean {
        val api = json.optString("apiVersion", "")
        return api.startsWith("cms.io/v2")
    }

    // ---------- 种子模块（首次运行注入内置能力目录） ----------

    /**
     * 强制覆盖写入内置种子模块（确保升级后的纯应用内能力始终生效），并清理历史遗留的
     * 「真实执行」模块（如无障碍模拟点击/滑动的 quro.automation）。
     */
    fun ensureSeed() {
        seedModules().forEach { upsert(it) }
        purgeLegacy()
    }

    /** 清理历史版本遗留的「真实执行」模块（已在新架构下失效）。 */
    private fun purgeLegacy() {
        val legacy = setOf("quro.automation")
        val keep = load().filter { it.id !in legacy }
        if (keep.size != load().size) save(keep)
    }

    private fun seedModules(): List<QuroCmsModule> = listOf(
        // 1. Web 搜索与浏览（应用内嵌 WebView 浏览器，v177 重写）
        QuroCmsModule(
            id = "quro.web",
            name = "Web 搜索与浏览",
            version = "1.0.0",
            description = "网络搜索、打开任意网址（应用内嵌 WebView 浏览器，不跳转外部浏览器）。",
            author = "Quro AI", license = "Apache-2.0",
            state = ModuleState.Ready,
            permissions = listOf(
                QuroCmsPermission("net.access", PermissionLevel.Normal, "访问网络（搜索/打开网页）", "*", AuthorizationLevel.Session),
            ),
            capabilities = listOf(
                QuroCmsCapability("web_search", "网络搜索", "query:string",
                    listOf("net.access"), PermissionConstraints(allowedDomains = listOf("google.com", "bing.com", "baidu.com")),
                    "intent", """{"action":"android.intent.action.VIEW","data":"https://www.google.com/search?q=${'$'}{query}"}"""),
                QuroCmsCapability("open_url", "打开网址", "url:string",
                    listOf("net.access"), PermissionConstraints(),
                    "intent", """{"action":"android.intent.action.VIEW","data":"${'$'}{url}"}"""),
            ),
        ),
        // 2. 系统信息（只读 API，不控制系统）
        QuroCmsModule(
            id = "quro.system",
            name = "系统信息",
            version = "1.0.0",
            description = "只读设备信息、列出已安装应用（均通过应用内 Android API，不执行 shell / 不控制系统）。",
            author = "Quro AI", license = "Apache-2.0",
            state = ModuleState.Ready,
            permissions = listOf(
                QuroCmsPermission("sys.info", PermissionLevel.Normal, "读取设备属性", "*", AuthorizationLevel.Session),
            ),
            capabilities = listOf(
                QuroCmsCapability("device_model", "读取设备型号", "{}",
                    listOf("sys.info"), PermissionConstraints(),
                    "api", "device.info"),
                QuroCmsCapability("list_packages", "列出已安装应用", "{}",
                    listOf("sys.info"), PermissionConstraints(),
                    "api", "packages.list"),
            ),
        ),
        // 3. 文件工具（仅应用沙箱内只读，不触达系统文件 / 不执行 shell）
        QuroCmsModule(
            id = "quro.file",
            name = "文件工具",
            version = "1.0.0",
            description = "在应用自身沙箱内列目录、读文件（不读取系统文件，不执行 shell）。",
            author = "Quro AI", license = "Apache-2.0",
            state = ModuleState.Ready,
            permissions = listOf(
                QuroCmsPermission("fs.read", PermissionLevel.Normal, "读取应用沙箱文件", "*", AuthorizationLevel.Session),
            ),
            capabilities = listOf(
                QuroCmsCapability("list_dir", "列应用目录", "path:string",
                    listOf("fs.read"), PermissionConstraints(),
                    "api", "file.list"),
                QuroCmsCapability("read_file", "读应用文件", "path:string",
                    listOf("fs.read"), PermissionConstraints(),
                    "api", "file.read"),
            ),
        ),
        // 4. 代码运行（应用内 QuickJS 沙箱执行 JS，不调用系统 node/python）
        QuroCmsModule(
            id = "quro.code",
            name = "代码运行",
            version = "1.0.0",
            description = "在应用进程内置 QuickJS 沙箱内执行 JS 脚本（应用内逻辑执行，不触达系统）。",
            author = "Quro AI", license = "Apache-2.0",
            state = ModuleState.Ready,
            permissions = listOf(
                QuroCmsPermission("code.run", PermissionLevel.Normal, "在应用内执行脚本", "*", AuthorizationLevel.Session),
            ),
            capabilities = listOf(
                QuroCmsCapability("run_node", "运行 JS", "script:string",
                    listOf("code.run"), PermissionConstraints(maxExecutionTimeSecs = 10),
                    "js", "\${script}"),
            ),
        ),
        // 5. 时间（只读 API）
        QuroCmsModule(
            id = "quro.time",
            name = "时间",
            version = "1.0.0",
            description = "读取当前系统时间（应用内 API）。",
            author = "Quro AI", license = "Apache-2.0",
            state = ModuleState.Ready,
            permissions = listOf(
                QuroCmsPermission("time.read", PermissionLevel.Normal, "读取系统时间", "*", AuthorizationLevel.Session),
            ),
            capabilities = listOf(
                QuroCmsCapability("now", "当前时间", "{}",
                    listOf("time.read"), PermissionConstraints(),
                    "api", "time.now"),
            ),
        ),
        // 6. AI 绘图（intent 打开各厂商平台）
        QuroCmsModule(
            id = "quro.draw",
            name = "AI 绘图",
            version = "1.0.0",
            description = "打开各厂商文生图平台（实际生成由对话框内 AI 经 API 完成，此处仅登记能力并打开对应平台）。",
            author = "Quro AI", license = "Apache-2.0",
            state = ModuleState.Ready,
            permissions = listOf(
                QuroCmsPermission("net.api", PermissionLevel.Normal, "调用绘图平台（需联网）", "*", AuthorizationLevel.Session),
            ),
            capabilities = listOf(
                QuroCmsCapability("draw_openai", "OpenAI 绘图", "prompt:string",
                    listOf("net.api"), PermissionConstraints(),
                    "intent", """{"action":"android.intent.action.VIEW","data":"https://openai.com/dall-e"}"""),
                QuroCmsCapability("draw_zhipu", "智谱绘图", "prompt:string",
                    listOf("net.api"), PermissionConstraints(),
                    "intent", """{"action":"android.intent.action.VIEW","data":"https://open.bigmodel.cn"}"""),
                QuroCmsCapability("draw_qwen", "通义千问绘图", "prompt:string",
                    listOf("net.api"), PermissionConstraints(),
                    "intent", """{"action":"android.intent.action.VIEW","data":"https://tongyi.aliyun.com"}"""),
            ),
        ),
        // 7. 开发协作（intent 打开仓库）
        QuroCmsModule(
            id = "quro.github",
            name = "GitHub",
            version = "1.0.0",
            description = "打开仓库、查看项目主页（intent）。",
            author = "Quro AI", license = "Apache-2.0",
            state = ModuleState.Ready,
            permissions = listOf(
                QuroCmsPermission("net.access", PermissionLevel.Normal, "访问网络", "*", AuthorizationLevel.Session),
            ),
            capabilities = listOf(
                QuroCmsCapability("open_repo", "打开仓库", "url:string",
                    listOf("net.access"), PermissionConstraints(),
                    "intent", """{"action":"android.intent.action.VIEW","data":"${'$'}{url}"}"""),
            ),
        ),
        // 8. 日常助手（intent 拉起系统日历/闹钟）
        QuroCmsModule(
            id = "quro.daily",
            name = "日常助手",
            version = "1.0.0",
            description = "日历、闹钟等日常操作（应用内派发 Intent 拉起系统界面）。",
            author = "Quro AI", license = "Apache-2.0",
            state = ModuleState.Ready,
            permissions = listOf(
                QuroCmsPermission("daily.intent", PermissionLevel.Normal, "启动系统日历/闹钟", "*", AuthorizationLevel.Session),
            ),
            capabilities = listOf(
                QuroCmsCapability("open_calendar", "打开日历", "{}",
                    listOf("daily.intent"), PermissionConstraints(),
                    "intent", """{"action":"android.intent.action.VIEW","data":"content://com.android.calendar/time"}"""),
                QuroCmsCapability("set_alarm", "设置闹钟", "hour:int,minute:int",
                    listOf("daily.intent"), PermissionConstraints(),
                    "intent", """{"action":"android.intent.action.SET_ALARM","extra":{"android.intent.extra.alarm.HOUR":{"t":"i","v":"${'$'}{hour}"},"android.intent.extra.alarm.MINUTES":{"t":"i","v":"${'$'}{minute}"}}}"""),
            ),
        ),
        // 9. 工作流（应用内 echo 演示）
        QuroCmsModule(
            id = "quro.workflow",
            name = "工作流",
            version = "1.0.0",
            description = "组合多步能力编排简单工作流（演示入口，应用内执行）。",
            author = "Quro AI", license = "Apache-2.0",
            state = ModuleState.Ready,
            permissions = listOf(
                QuroCmsPermission("wf.run", PermissionLevel.Normal, "运行本地工作流", "*", AuthorizationLevel.Session),
            ),
            capabilities = listOf(
                QuroCmsCapability("echo_step", "回显步骤", "text:string",
                    listOf("wf.run"), PermissionConstraints(),
                    "api", "echo"),
            ),
        ),
        // 10. 记忆库（intent 打开记忆页）
        QuroCmsModule(
            id = "quro.memory",
            name = "记忆库",
            version = "1.0.0",
            description = "长期记忆的登记与权限通道（实际读写由 memory_* 工具完成）。",
            author = "Quro AI", license = "Apache-2.0",
            state = ModuleState.Ready,
            permissions = listOf(
                QuroCmsPermission("mem.access", PermissionLevel.Normal, "读写本地记忆库", "*", AuthorizationLevel.Session),
            ),
            capabilities = listOf(
                QuroCmsCapability("open_memory", "打开记忆库", "{}",
                    listOf("mem.access"), PermissionConstraints(),
                    "intent", """{"action":"android.intent.action.VIEW","data":"quro://memory"}"""),
            ),
        ),
        // 11. 12306 出行（intent）
        QuroCmsModule(
            id = "quro.12306",
            name = "12306 出行",
            version = "1.0.0",
            description = "打开铁路 12306 官网（intent）。",
            author = "Quro AI", license = "Apache-2.0",
            state = ModuleState.Ready,
            permissions = listOf(
                QuroCmsPermission("net.access", PermissionLevel.Normal, "访问网络", "*", AuthorizationLevel.Session),
            ),
            capabilities = listOf(
                QuroCmsCapability("open_12306", "打开 12306", "{}",
                    listOf("net.access"), PermissionConstraints(),
                    "intent", """{"action":"android.intent.action.VIEW","data":"https://www.12306.cn"}"""),
            ),
        ),
        // 12. 终端（应用内 PTY，纯用户空间；执行经 QuroAgentTrace 实时可视化）
        QuroCmsModule(
            id = "quro.terminal",
            name = "终端",
            version = "1.0.0",
            description = "在应用内 PTY（/system/bin/sh）执行 shell 命令（纯用户空间，无 root/Shizuku）。执行过程经 QuroAgentTrace 实时可视化。",
            author = "Quro AI", license = "Apache-2.0",
            state = ModuleState.Ready,
            permissions = listOf(
                QuroCmsPermission("term.run", PermissionLevel.Normal, "在应用内执行 shell 命令", "*", AuthorizationLevel.Session),
            ),
            capabilities = listOf(
                QuroCmsCapability("run_shell", "执行命令", "command:string",
                    listOf("term.run"), PermissionConstraints(maxExecutionTimeSecs = 15),
                    "terminal", "\${command}"),
            ),
        ),
        // 13. 启动器（应用级操作：打开应用 / 回到桌面，均以应用自身身份 startActivity）
        QuroCmsModule(
            id = "quro.launcher",
            name = "启动器",
            version = "1.0.0",
            description = "打开指定应用、回到桌面等应用级操作（应用内派发 Intent，不控制系统）。",
            author = "Quro AI", license = "Apache-2.0",
            state = ModuleState.Ready,
            permissions = listOf(
                QuroCmsPermission("launch.app", PermissionLevel.Normal, "启动其他应用", "*", AuthorizationLevel.Session),
            ),
            capabilities = listOf(
                QuroCmsCapability("open_app", "打开应用", "package:string",
                    listOf("launch.app"), PermissionConstraints(),
                    "intent", """{"action":"android.intent.action.MAIN","package":"${'$'}{package}","category":["android.intent.category.LAUNCHER"]}"""),
                QuroCmsCapability("home", "回到桌面", "{}",
                    listOf("launch.app"), PermissionConstraints(),
                    "intent", """{"action":"android.intent.action.MAIN","category":["android.intent.category.HOME"]}"""),
            ),
        ),
    )

    // ---------- 解析 / 序列化 ----------

    private fun parseModule(o: JSONObject): QuroCmsModule = QuroCmsModule(
        id = o.getString("id"),
        name = o.optString("name", o.getString("id")),
        version = o.optString("version", "1.0.0"),
        description = o.optString("description", ""),
        author = o.optString("author", ""),
        license = o.optString("license", ""),
        state = runCatching { ModuleState.valueOf(o.optString("state", "Stopped")) }.getOrDefault(ModuleState.Stopped),
        permissions = jsonList(o.optJSONArray("permissions")) { parsePermission(it) },
        capabilities = jsonList(o.optJSONArray("capabilities")) { parseCapability(it) },
        dependencies = jsonList(o.optJSONArray("dependencies")) { parseDependency(it) },
        lifecycle = o.optString("lifecycle", "onDemand"),
        catalog = o.optString("catalog", ""),
        signature = o.optString("signature", ""),
    )

    private fun parsePermission(o: JSONObject) = QuroCmsPermission(
        id = o.getString("id"),
        level = runCatching { PermissionLevel.valueOf(o.optString("level", "Normal")) }.getOrDefault(PermissionLevel.Normal),
        rationale = o.optString("rationale", ""),
        scope = o.optString("scope", ""),
        authorization = runCatching { AuthorizationLevel.valueOf(o.optString("authorization", "Denied")) }.getOrDefault(AuthorizationLevel.Denied),
    )

    private fun parseCapability(o: JSONObject) = QuroCmsCapability(
        id = o.getString("id"),
        summary = o.optString("summary", ""),
        schema = o.optString("schema", ""),
        requiresPermissions = o.optJSONArray("requiresPermissions").toStringList(),
        constraints = parseConstraints(o.optJSONObject("constraints")),
        actionType = o.optString("actionType", "shell"),
        action = o.optString("action", ""),
    )

    private fun parseDependency(o: JSONObject) = QuroCmsDependency(
        kind = runCatching { DepKind.valueOf(o.optString("kind", "CAPABILITY")) }.getOrDefault(DepKind.CAPABILITY),
        spec = o.optString("spec", ""),
        capability = o.optString("capability", ""),
        version = o.optString("version", ""),
        optional = o.optBoolean("optional", false),
    )

    private fun parseConstraints(o: JSONObject?): PermissionConstraints {
        if (o == null) return PermissionConstraints()
        return PermissionConstraints(
            allowedPaths = o.optJSONArray("allowed_paths").toStringList(),
            allowedCommands = o.optJSONArray("allowed_commands").toStringList(),
            allowedDomains = o.optJSONArray("allowed_domains").toStringList(),
            maxExecutionTimeSecs = o.optInt("max_execution_time_secs", 30),
            maxMemoryMb = o.optInt("max_memory_mb", 64),
        )
    }

    private fun serializeModule(m: QuroCmsModule) = JSONObject().apply {
        put("apiVersion", "cms.io/v2")
        put("id", m.id)
        put("name", m.name)
        put("version", m.version)
        put("description", m.description)
        put("author", m.author)
        put("license", m.license)
        put("state", m.state.name)
        put("lifecycle", m.lifecycle)
        put("catalog", m.catalog)
        put("signature", m.signature)
        put("permissions", JSONArray(m.permissions.map { p ->
            JSONObject().apply {
                put("id", p.id); put("level", p.level.name)
                put("rationale", p.rationale); put("scope", p.scope)
                put("authorization", p.authorization.name)
            }
        }))
        put("capabilities", JSONArray(m.capabilities.map { c ->
            JSONObject().apply {
                put("id", c.id); put("summary", c.summary); put("schema", c.schema)
                put("requiresPermissions", JSONArray(c.requiresPermissions))
                put("constraints", JSONObject().apply {
                    put("allowed_paths", JSONArray(c.constraints.allowedPaths))
                    put("allowed_commands", JSONArray(c.constraints.allowedCommands))
                    put("allowed_domains", JSONArray(c.constraints.allowedDomains))
                    put("max_execution_time_secs", c.constraints.maxExecutionTimeSecs)
                    put("max_memory_mb", c.constraints.maxMemoryMb)
                })
                put("actionType", c.actionType); put("action", c.action)
            }
        }))
        put("dependencies", JSONArray(m.dependencies.map { d ->
            JSONObject().apply {
                put("kind", d.kind.name); put("spec", d.spec); put("capability", d.capability)
                put("version", d.version); put("optional", d.optional)
            }
        }))
    }

    // ---------- JSON 列表工具 ----------

    private fun <T> jsonList(arr: JSONArray?, block: (JSONObject) -> T): List<T> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i -> runCatching { block(arr.getJSONObject(i)) }.getOrNull() }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> runCatching { getString(i) }.getOrNull() }
    }
}
