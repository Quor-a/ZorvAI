package com.ai.assistance.quro.core.cms

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * CMS v2 模块仓库：模块清单以 [org.json] 持久化到 filesDir/cms_modules.json，
 * 协议头 apiVersion 固定为 `cms.io/v2`。
 *
 * v4 精简：仅保留终端（proot/Ubuntu）模块，所有「应用内执行」手机模块已移除。
 * 终端模块自带 [QuroCmsModule.terminalEntry] 真实入口脚本，在 proot 沙箱内作为后端运行；
 * 本 App 作为前端通过 cms_call/ACI 调用，落实「互为主从」。
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
        file.writeText(JSONObject()
            .put("apiVersion", "cms.io/v2")
            .put("seedVersion", SEED_VERSION)
            .put("modules", arr)
            .toString(2))
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
     * 幂等播种：仅在种子版本变化（首次运行 / 内置模块目录升级）时重写内置种子模块，
     * 并清理历史遗留的手机模块与「真实执行」模块（如无障碍模拟点击/滑动的 quro.automation）。
     *
     * 此前每次进 CMS 页都无条件 upsert(builtInModules())，会覆盖用户在终端/导入侧对模块
     * 的手动修复；现改为按 [SEED_VERSION] 只播种一次，后续进入不再重写。
     */
    fun ensureSeed() {
        if (storedSeedVersion() != SEED_VERSION) {
            builtInModules().forEach { upsert(it) }
        }
        purgeLegacy()
    }

    /** 读取已持久化的种子版本（无文件返回空串，触发首次播种）。 */
    private fun storedSeedVersion(): String =
        if (!file.exists()) "" else runCatching {
            JSONObject(file.readText()).optString("seedVersion", "")
        }.getOrDefault("")

    /** 清理历史版本遗留的「真实执行」模块与已移除的手机（应用内执行）模块（已在新架构下失效）。 */
    private fun purgeLegacy() {
        val legacy = setOf(
            "quro.automation",   // 历史「真实执行」模块
            // 已移除的手机（应用内执行）模块
            "quro.web", "quro.system", "quro.file", "quro.code", "quro.time",
            "quro.draw", "quro.github", "quro.daily", "quro.workflow", "quro.memory",
            "quro.12306", "quro.terminal", "quro.launcher",
        )
        val keep = load().filter { it.id !in legacy }
        if (keep.size != load().size) save(keep)
    }

    /**
     * 内置种子模块（Context-free，可在 JVM 单测中直接调用）。
     * v4 精简：仅保留终端（proot/Ubuntu）模块，所有「应用内执行」手机模块已移除。
     * 终端模块均带 [QuroCmsModule.terminalEntry] 真实入口脚本，在 proot 沙箱内作为后端运行。
     */
    companion object {
        /**
         * 种子版本：内置模块目录（能力/入口脚本）发生结构性变更时 +1，
         * 触发 [ensureSeed] 重新播种一次；否则 [ensureSeed] 跳过，避免覆盖用户手动修复。
         */
        private const val SEED_VERSION = "1"

        fun builtInModules(): List<QuroCmsModule> = listOf(
        // 1. 终端·Python 运行时（proot 内真实 Python 后端；终端是后端，本软是前端）
        QuroCmsModule(
            id = "quro.term.python",
            name = "终端·Python运行时",
            version = "1.0.0",
            description = "在 proot/Ubuntu 终端内运行 Python（bootstrap 已装 python3）。模块自带真实入口脚本：部署后在终端拉起一个本地 HTTP 后端（监听 0.0.0.0 端口），本 App 作为前端通过 cms_call/ACI 调用，实现「终端是后端、本软是前端」的互为主从。",
            author = "Zorv AI", license = "Apache-2.0",
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
                    "terminal", "exec sh /root/cms/quro.term.python/entry.sh",
                    resident = true, residentEnv = mapOf("port" to "QURO_HTTP_PORT")),
                QuroCmsCapability("term_python_stop", "停止 Python 后端(HTTP)", "{}",
                    listOf("term.python.exec"), PermissionConstraints(),
                    "terminal", "true", residentStop = true),
            ),
            terminalEntry = """
#!/bin/sh
# Quro CMS 终端模块：Python 后端运行时（proot/Ubuntu）
PORT="${'$'}{QURO_HTTP_PORT:-8765}"
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
        // 2. 终端·Node 运行时（proot 内真实 Node 后端）
        QuroCmsModule(
            id = "quro.term.node",
            name = "终端·Node运行时",
            version = "1.0.0",
            description = "在 proot/Ubuntu 终端内运行 Node.js（bootstrap 已装 nodejs）。自带真实入口脚本：部署后拉起本地 HTTP 后端，作为「终端是后端」的另一实现，与 Python 后端可并存。",
            author = "Zorv AI", license = "Apache-2.0",
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
                    "terminal", "exec sh /root/cms/quro.term.node/entry.sh",
                    resident = true, residentEnv = mapOf("port" to "QURO_HTTP_PORT")),
                QuroCmsCapability("term_node_stop", "停止 Node 后端(HTTP)", "{}",
                    listOf("term.node.exec"), PermissionConstraints(),
                    "terminal", "true", residentStop = true),
            ),
            terminalEntry = """
#!/bin/sh
# Quro CMS 终端模块：Node 后端运行时（proot/Ubuntu）
PORT="${'$'}{QURO_HTTP_PORT:-8766}"
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
        // 3. 终端·静态 HTTP 服务（终端作为后端，对外提供文件服务）
        QuroCmsModule(
            id = "quro.term.httpd",
            name = "终端·静态HTTP服务",
            version = "1.0.0",
            description = "在 proot 内启动一个静态文件 HTTP 服务（python3 -m http.server），把终端变成一个可对外提供文件的后端。本 App 作为前端向其请求资源，落实「互为主从」。",
            author = "Zorv AI", license = "Apache-2.0",
            state = ModuleState.Ready,
            permissions = listOf(
                QuroCmsPermission("term.httpd.exec", PermissionLevel.Normal, "在终端 proot 内启动 HTTP 服务", "*", AuthorizationLevel.Session),
            ),
            capabilities = listOf(
                QuroCmsCapability("term_httpd_start", "启动静态 HTTP 服务", "port:int,dir:string",
                    listOf("term.httpd.exec"), PermissionConstraints(maxExecutionTimeSecs = 30, maxMemoryMb = 128),
                    "terminal", "exec sh /root/cms/quro.term.httpd/entry.sh",
                    resident = true, residentEnv = mapOf("port" to "QURO_HTTP_PORT", "dir" to "QURO_SERVE_DIR")),
                QuroCmsCapability("term_httpd_list", "列出服务目录", "dir:string",
                    listOf("term.httpd.exec"), PermissionConstraints(),
                    "terminal", "ls -la \"\${dir}\"",
                    defaultArgs = mapOf("dir" to "/root/cms/quro.term.httpd/www")),
                QuroCmsCapability("term_httpd_stop", "停止静态 HTTP 服务", "{}",
                    listOf("term.httpd.exec"), PermissionConstraints(),
                    "terminal", "true", residentStop = true),
            ),
            terminalEntry = """
#!/bin/sh
# Quro CMS 终端模块：静态文件 HTTP 服务（终端作为后端）
# 默认端口 8123（避开引擎 cms-static 的 8080 与 quro.term.python 的 8765 / node 的 8766）；
# 若显式指定了 QURO_HTTP_PORT 则优先使用，否则自动探测 8123 起 5 个候选空闲端口。
if [ -n "${'$'}{QURO_HTTP_PORT:-}" ]; then
  PORT="${'$'}QURO_HTTP_PORT"
else
  PORT=8123
  for cand in 8123 8124 8125 8126 8127; do
    if ! (exec 3<>/dev/tcp/127.0.0.1/${'$'}cand) 2>/dev/null; then
      PORT=${'$'}cand; break
    fi
  done
fi
DIR="${'$'}{QURO_SERVE_DIR:-/root/cms/quro.term.httpd/www}"
mkdir -p "${'$'}DIR"
if [ ! -f "${'$'}DIR/index.html" ]; then
  echo "<h1>Quro Terminal HTTPD</h1><p>终端静态文件服务已就绪。</p>" > "${'$'}DIR/index.html"
fi
echo "[quro.term.httpd] 启动静态 HTTP 服务，目录 ${'$'}DIR，端口 ${'$'}PORT"
cd "${'$'}DIR"
exec python3 -m http.server "${'$'}PORT" --bind 0.0.0.0
""".trimIndent(),
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
            resident = o.optBoolean("resident", false),
            residentEnv = jsonObjToMap(o.optJSONObject("resident_env")),
            residentStop = o.optBoolean("resident_stop", false),
            defaultArgs = jsonObjToMap(o.optJSONObject("default_args")),
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
                put("resident", c.resident)
                put("resident_env", JSONObject(c.residentEnv))
                put("resident_stop", c.residentStop)
                put("default_args", JSONObject(c.defaultArgs))
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

    private fun jsonObjToMap(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val m = mutableMapOf<String, String>()
        o.keys().forEach { k -> m[k] = o.optString(k, "") }
        return m
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> runCatching { getString(i) }.getOrNull() }
    }
}
