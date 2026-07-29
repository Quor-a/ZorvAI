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

    // ---------- 导入 / 导出 ----------

    /** 导出全部模块为单个 cms.io/v2 JSON 文档（复用 serializeModule）。 */
    fun exportAll(): String {
        val arr = JSONArray()
        load().forEach { arr.put(serializeModule(it)) }
        return JSONObject().put("apiVersion", "cms.io/v2").put("modules", arr).toString(2)
    }

    /** 导出指定模块列表为 cms.io/v2 JSON 文档（供「导出模块」按选择导出）。 */
    fun exportModules(list: List<QuroCmsModule>): String {
        val arr = JSONArray()
        list.forEach { arr.put(serializeModule(it)) }
        return JSONObject().put("apiVersion", "cms.io/v2").put("modules", arr).toString(2)
    }

    /** 从 cms.io/v2 JSON 文档导入模块（按 id 覆盖合并）。返回成功导入的模块数；格式不符返回 0。 */
    fun importModules(json: String): Int {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return 0
        if (!verifyManifest(root)) return 0
        val arr = root.optJSONArray("modules") ?: return 0
        val existing = load().associateBy { it.id }.toMutableMap()
        var n = 0
        for (i in 0 until arr.length()) {
            val m = runCatching { parseModule(arr.getJSONObject(i)) }.getOrNull() ?: continue
            existing[m.id] = m
            n++
        }
        if (n > 0) save(existing.values.toList())
        return n
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
        builtInModules().forEach { upsert(it) }
        purgeLegacy()
    }

    /** 清理历史版本遗留的「真实执行」模块（已在新架构下失效）。 */
    private fun purgeLegacy() {
        val legacy = setOf("quro.automation")
        val keep = load().filter { it.id !in legacy }
        if (keep.size != load().size) save(keep)
    }

    /**
     * 内置种子模块（Context-free，可在 JVM 单测中直接调用）。
     * 所有种子能力均为纯应用内执行（intent / js / api），不调用 shell / root / Shizuku / 无障碍真实执行。
     * v311：重写 10 个内置插件为真实原生实现，并把自动化浏览器接入为 quro.web 的真实能力。
     */
    companion object {
        fun builtInModules(): List<QuroCmsModule> = listOf(
        // 1. Web 搜索与浏览（应用内嵌 WebView 浏览器，v177 重写）
        QuroCmsModule(
            id = "quro.web",
            name = "Web 搜索与浏览",
            version = "1.0.0",
            description = "网络搜索、打开任意网址（应用内嵌 WebView 浏览器，不跳转外部浏览器）；并内置 AI 自动化浏览器：后台自动研究（搜索→抓取→合并简报）与单页正文抓取。",
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
                // 自动化浏览器（★ 真实能力）：AI 后台自动研究（搜索→抓取前 N 页正文→合并简报）
                QuroCmsCapability("automate_browser", "自动化研究(浏览器)", "query:string,depth:int",
                    listOf("net.access"), PermissionConstraints(maxExecutionTimeSecs = 30),
                    "api", "ai.browser.automate"),
                // 抓取单页正文（真实能力，接 AiBrowserTool 引擎）
                QuroCmsCapability("extract_article", "抓取网页正文", "url:string",
                    listOf("net.access"), PermissionConstraints(maxExecutionTimeSecs = 20),
                    "api", "ai.browser.read"),
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
                // 电量信息（真实能力，应用内 BatteryManager 只读）
                QuroCmsCapability("battery_info", "读取电量信息", "{}",
                    listOf("sys.info"), PermissionConstraints(),
                    "api", "battery.info"),
            ),
        ),
        // 3. 文件工具（仅应用沙箱内只读，不触达系统文件 / 不执行 shell）
        QuroCmsModule(
            id = "quro.file",
            name = "文件工具",
            version = "1.0.0",
            description = "在应用自身沙箱内列目录、读文件、写文件（不读取系统文件，不执行 shell）。",
            author = "Quro AI", license = "Apache-2.0",
            state = ModuleState.Ready,
            permissions = listOf(
                QuroCmsPermission("fs.read", PermissionLevel.Normal, "读取应用沙箱文件", "*", AuthorizationLevel.Session),
                QuroCmsPermission("fs.write", PermissionLevel.Normal, "写入应用沙箱文件", "*", AuthorizationLevel.Session),
            ),
            capabilities = listOf(
                QuroCmsCapability("list_dir", "列应用目录", "path:string",
                    listOf("fs.read"), PermissionConstraints(),
                    "api", "file.list"),
                QuroCmsCapability("read_file", "读应用文件", "path:string",
                    listOf("fs.read"), PermissionConstraints(),
                    "api", "file.read"),
                QuroCmsCapability("write_file", "写应用文件", "path:string,content:string",
                    listOf("fs.write"), PermissionConstraints(),
                    "api", "file.write"),
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
                QuroCmsCapability(
                    id = "run_code_dual",
                    summary = "运行 JS/Node（可前端 QuickJS 或后端 proot node，互为主从）",
                    schema = "script:string",
                    requiresPermissions = listOf("code.run"),
                    constraints = PermissionConstraints(maxExecutionTimeSecs = 10),
                    actionType = "js",
                    action = "\${script}",
                    runOn = setOf(RuntimeHost.APP, RuntimeHost.TERMINAL),
                    terminalAction = "node -e \"\${script}\"",
                ),
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
                // 按指定格式输出时间（真实能力，应用内 SimpleDateFormat）
                QuroCmsCapability("format_time", "格式化时间", "format:string,epoch:string",
                    listOf("time.read"), PermissionConstraints(),
                    "api", "time.format"),
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
            description = "组合多步能力编排简单工作流（应用内真实执行：echo 单步 + 多步序列编排内置能力）。",
            author = "Quro AI", license = "Apache-2.0",
            state = ModuleState.Ready,
            permissions = listOf(
                QuroCmsPermission("wf.run", PermissionLevel.Normal, "运行本地工作流", "*", AuthorizationLevel.Session),
            ),
            capabilities = listOf(
                QuroCmsCapability("echo_step", "回显步骤", "text:string",
                    listOf("wf.run"), PermissionConstraints(),
                    "api", "echo"),
                // 多步编排（真实能力，递归分发其它内置 api 能力，本地执行）
                QuroCmsCapability("run_sequence", "运行多步工作流", "steps:string",
                    listOf("wf.run"), PermissionConstraints(maxExecutionTimeSecs = 30),
                    "api", "workflow.sequence"),
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
        // 14. 终端·Python 运行时（proot 内真实 Python 后端；终端是后端，本软是前端）
        QuroCmsModule(
            id = "quro.term.python",
            name = "终端·Python运行时",
            version = "1.0.0",
            description = "在 proot/Alpine 终端内运行 Python（bootstrap 已装 python3）。模块自带真实入口脚本：部署后在终端拉起一个本地 HTTP 后端（监听 0.0.0.0 端口），本 App 作为前端通过 cms_call/ACI 调用，实现「终端是后端、本软是前端」的互为主从。",
            author = "Quro AI", license = "Apache-2.0",
            state = ModuleState.Ready,
            permissions = listOf(
                QuroCmsPermission("term.python.exec", PermissionLevel.Normal, "在终端 proot 内执行 Python", "*", AuthorizationLevel.Session),
            ),
            capabilities = listOf(
                QuroCmsCapability("term_python_run", "运行 Python 代码", "code:string",
                    listOf("term.python.exec"), PermissionConstraints(maxExecutionTimeSecs = 30),
                    "terminal", "python3 -c \"\${code}\""),
                QuroCmsCapability("term_python_version", "查看 Python 版本", "{}",
                    listOf("term.python.exec"), PermissionConstraints(),
                    "terminal", "python3 --version"),
                QuroCmsCapability("term_python_serve", "启动 Python 后端(HTTP)", "port:int",
                    listOf("term.python.exec"), PermissionConstraints(maxExecutionTimeSecs = 30, maxMemoryMb = 128),
                    "terminal", "QURO_HTTP_PORT=\${port} nohup sh /root/cms/quro.term.python/entry.sh > /root/cms/quro.term.python/run.log 2>&1 & echo started"),
            ),
            terminalEntry = """
#!/bin/sh
# Quro CMS 终端模块：Python 后端运行时（proot/Alpine）
PORT="${'$'}QURO_HTTP_PORT:-8765"
echo "[quro.term.python] 启动 Python 后端，监听 0.0.0.0:${'$'}PORT"
python3 - "${'$'}PORT" <<'PYEOF'
import sys, json
from http.server import BaseHTTPRequestHandler, HTTPServer
port = int(sys.argv[1])
class H(BaseHTTPRequestHandler):
    def _j(self, obj):
        self.send_response(200)
        self.send_header('Content-Type','application/json')
        self.end_headers()
        self.wfile.write(json.dumps(obj).encode('utf-8'))
    def do_GET(self):
        self._j({"module":"quro.term.python","status":"ok","echo":self.path,"method":"GET"})
    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(n).decode('utf-8','replace') if n else ''
        self._j({"module":"quro.term.python","status":"ok","echo":self.path,"body":body,"method":"POST"})
    def log_message(self, *a): pass
HTTPServer(('0.0.0.0', port), H).serve_forever()
PYEOF
""".trimIndent(),
        ),
        // 15. 终端·Node 运行时（proot 内真实 Node 后端）
        QuroCmsModule(
            id = "quro.term.node",
            name = "终端·Node运行时",
            version = "1.0.0",
            description = "在 proot/Alpine 终端内运行 Node.js（bootstrap 已装 nodejs）。自带真实入口脚本：部署后拉起本地 HTTP 后端，作为「终端是后端」的另一实现，与 Python 后端可并存。",
            author = "Quro AI", license = "Apache-2.0",
            state = ModuleState.Ready,
            permissions = listOf(
                QuroCmsPermission("term.node.exec", PermissionLevel.Normal, "在终端 proot 内执行 Node", "*", AuthorizationLevel.Session),
            ),
            capabilities = listOf(
                QuroCmsCapability("term_node_run", "运行 Node 代码", "code:string",
                    listOf("term.node.exec"), PermissionConstraints(maxExecutionTimeSecs = 30),
                    "terminal", "node -e \"\${code}\""),
                QuroCmsCapability("term_node_version", "查看 Node 版本", "{}",
                    listOf("term.node.exec"), PermissionConstraints(),
                    "terminal", "node --version"),
                QuroCmsCapability("term_node_serve", "启动 Node 后端(HTTP)", "port:int",
                    listOf("term.node.exec"), PermissionConstraints(maxExecutionTimeSecs = 30, maxMemoryMb = 128),
                    "terminal", "QURO_HTTP_PORT=\${port} nohup sh /root/cms/quro.term.node/entry.sh > /root/cms/quro.term.node/run.log 2>&1 & echo started"),
            ),
            terminalEntry = """
#!/bin/sh
# Quro CMS 终端模块：Node 后端运行时（proot/Alpine）
PORT="${'$'}QURO_HTTP_PORT:-8766"
echo "[quro.term.node] 启动 Node 后端，监听 0.0.0.0:${'$'}PORT"
node - "${'$'}PORT" <<'JSEOF'
const port = parseInt(process.argv[2], 10);
const http = require('http');
const server = http.createServer((req, res) => {
  let body = '';
  req.on('data', c => body += c);
  req.on('end', () => {
    res.writeHead(200, {'Content-Type': 'application/json'});
    res.end(JSON.stringify({module: 'quro.term.node', status: 'ok', echo: req.url, body: body, method: req.method}));
  });
});
server.listen(port, '0.0.0.0', () => console.log('node backend on ' + port));
JSEOF
""".trimIndent(),
        ),
        // 16. 终端·静态 HTTP 服务（终端作为后端，对外提供文件服务）
        QuroCmsModule(
            id = "quro.term.httpd",
            name = "终端·静态HTTP服务",
            version = "1.0.0",
            description = "在 proot 内启动一个静态文件 HTTP 服务（python3 -m http.server），把终端变成一个可对外提供文件的后端。本 App 作为前端向其请求资源，落实「互为主从」。",
            author = "Quro AI", license = "Apache-2.0",
            state = ModuleState.Ready,
            permissions = listOf(
                QuroCmsPermission("term.httpd.exec", PermissionLevel.Normal, "在终端 proot 内启动 HTTP 服务", "*", AuthorizationLevel.Session),
            ),
            capabilities = listOf(
                QuroCmsCapability("term_httpd_start", "启动静态 HTTP 服务", "port:int,dir:string",
                    listOf("term.httpd.exec"), PermissionConstraints(maxExecutionTimeSecs = 30, maxMemoryMb = 128),
                    "terminal", "QURO_HTTP_PORT=\${port} QURO_SERVE_DIR=\${dir} nohup sh /root/cms/quro.term.httpd/entry.sh > /root/cms/quro.term.httpd/run.log 2>&1 & echo started"),
                QuroCmsCapability("term_httpd_list", "列出服务目录", "dir:string",
                    listOf("term.httpd.exec"), PermissionConstraints(),
                    "terminal", "ls -la \"\${dir}\""),
            ),
            terminalEntry = """
#!/bin/sh
# Quro CMS 终端模块：静态文件 HTTP 服务（终端作为后端）
PORT="${'$'}QURO_HTTP_PORT:-8080"
DIR="${'$'}QURO_SERVE_DIR:-/root/cms/quro.term.httpd/www}"
mkdir -p "${'$'}DIR"
if [ ! -f "${'$'}DIR/index.html" ]; then
  echo "<h1>Quro Terminal HTTPD</h1><p>终端静态文件服务已就绪。</p>" > "${'$'}DIR/index.html"
fi
echo "[quro.term.httpd] 启动静态 HTTP 服务，目录 ${'$'}DIR，端口 ${'$'}PORT"
cd "${'$'}DIR"
exec python3 -m http.server "${'$'}PORT" --bind 0.0.0.0
""".trimIndent(),
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
        )
        )
    }

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
        terminalEntry = o.optString("terminal_entry", ""),
    )

    private fun parsePermission(o: JSONObject) = QuroCmsPermission(
        id = o.getString("id"),
        level = runCatching { PermissionLevel.valueOf(o.optString("level", "Normal")) }.getOrDefault(PermissionLevel.Normal),
        rationale = o.optString("rationale", ""),
        scope = o.optString("scope", ""),
        authorization = runCatching { AuthorizationLevel.valueOf(o.optString("authorization", "Denied")) }.getOrDefault(AuthorizationLevel.Denied),
    )

    private fun parseCapability(o: JSONObject): QuroCmsCapability {
        val actionType = o.optString("actionType", "shell")
        val runOnArr = o.optJSONArray("run_on")
        val runOn = if (runOnArr != null) {
            (0 until runOnArr.length()).mapNotNull { runCatching { RuntimeHost.fromName(runOnArr.getString(it)) }.getOrNull() }.toSet()
        } else emptySet()
        val effectiveRunOn = if (runOn.isEmpty()) RuntimeHost.hostsFor(actionType) else runOn
        return QuroCmsCapability(
            id = o.getString("id"),
            summary = o.optString("summary", ""),
            schema = o.optString("schema", ""),
            requiresPermissions = o.optJSONArray("requiresPermissions").toStringList(),
            constraints = parseConstraints(o.optJSONObject("constraints")),
            actionType = actionType,
            action = o.optString("action", ""),
            runOn = effectiveRunOn,
            terminalAction = o.optString("terminal_action", "").takeIf { it.isNotBlank() },
        )
    }

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
        put("terminal_entry", m.terminalEntry)
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
                put("run_on", JSONArray(c.runOn.map { it.name }))
                put("terminal_action", c.terminalAction ?: "")
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
