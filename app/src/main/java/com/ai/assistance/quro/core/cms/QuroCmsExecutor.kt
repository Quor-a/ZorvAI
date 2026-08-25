package com.ai.assistance.quro.core.cms

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import com.ai.assistance.quro.core.agent.QuroAgentTrace
import com.ai.assistance.quro.core.QuroBrowserBridge
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.tools.AiBrowserTool
import com.ai.assistance.quro.core.tools.QuroJsExecutor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CMS 执行后端：四类能力通道。
 * - intent：以应用自身身份 startActivity 拉起其他 App / 系统界面（应用内派发，非 shell 命令）。
 * - js：在 App 内置 QuickJS 沙箱内执行 JS（插件运行时复用）。
 * - api：调用应用内 Android API 完成只读/轻量操作（设备信息、已装应用、时间、应用沙箱文件，路径白名单受限）。
 * - terminal：在 proot/Ubuntu 应用内 Linux 沙箱内执行（即 CMS 所谓"终端"），可跑 python3/node/任意二进制
 *   + 真实文件系统写；环境未就绪直接报错（D1 约束：终端后端唯一化 = proot，不回退设备 sh）。
 * 每次执行经 [QuroCmsBroker] 权限仲裁 + 策略关卡，并写审计（[QuroAgentTrace] + [QuroCmsStorage]）。
 */
class QuroCmsExecutor(context: Context) {

    private val appContext = context.applicationContext
    private val broker = QuroCmsBroker(appContext)

    /**
     * 执行能力。@param uiRequest 由 UI 提供（弹出授权框）；返回是否允许继续。
     */
    suspend fun execute(
        module: QuroCmsModule,
        cap: QuroCmsCapability,
        args: Map<String, String>,
        uiRequest: suspend (QuroCmsPermission) -> AuthorizationLevel?,
        host: RuntimeHost = RuntimeHost.APP,
    ): String {
        // 1) 逐项仲裁所需权限
        val perms = cap.requiresPermissions.mapNotNull { module.findPermission(it) }
        for (p in perms) {
            val ok = broker.ensureAuthorized(module.id, p, riskOf(p.level), uiRequest)
            if (!ok) return "⛔ 权限被拒绝：${p.id}（${p.rationale}）"
        }
        val maxLevel = if (perms.isEmpty()) PermissionLevel.Normal else perms.maxOf { it.level.ordinal }.let { PermissionLevel.values()[it] }

        // 2) 解析动作（按选定宿主取模板，统一做 ${...} 代入）
        val (tpl, type) = cap.effectiveFor(host)
        val resolved = cap.resolveTemplate(tpl, args)

        // 3) trace：记录 AI 内部 action
        QuroAgentTrace.action("cms", "调用能力 ${cap.id}", "host=${host.label} module=${module.id} args=${args.keys.joinToString()}")

        // 4) 执行（按宿主通道）
        val result = try {
            when (type) {
                "intent" -> runIntent(resolved)
                "js" -> QuroJsExecutor.eval(resolved, 10_000)
                "api" -> runApi(resolved, args)
                "terminal" -> when {
                    // 常驻停止：转去停止对应模块的常驻服务（proot 进程强杀）。
                    cap.residentStop -> CmsResidentRuntime.stop(appContext, module.id)
                    // 常驻服务：以常驻 proot 启动（修复 cms_call 一次性调用后服务被杀）。
                    cap.resident -> CmsResidentRuntime.start(appContext, module, args, cap.residentEnv)
                    else -> runTerminal(resolved)
                }
                else -> "⛔ 不支持的执行类型：$type（已禁用 shell/root/无障碍等真实执行通道）"
            }
        } catch (e: Exception) {
            "⛔ 执行异常：${e.message}"
        }

        // 5) 审计 + trace 结果
        perms.forEach { broker.reclaimTemporary(module.id, it.id) }
        broker.logExecution(module.id, cap.id, maxLevel.name, riskOf(maxLevel), result)
        QuroAgentTrace.result("cms", "能力 ${cap.id} 完成", result.take(200))
        return result
    }

    private fun riskOf(level: PermissionLevel): String = when (level) {
        PermissionLevel.Critical -> "critical"
        PermissionLevel.Elevated -> "elevated"
        PermissionLevel.Special -> "special"
        PermissionLevel.Normal -> "normal"
    }

    private fun runIntent(json: String): String = runCatching {
        val o = JSONObject(json)
        val intent = Intent().apply {
            o.optString("action").takeIf { it.isNotBlank() }?.let { action = it }
            o.optString("package").takeIf { it.isNotBlank() }?.let { `package` = it }
            o.optString("class").takeIf { it.isNotBlank() }?.let {
                setClassName(o.optString("package").takeIf { p -> p.isNotBlank() } ?: appContext.packageName, it)
            }
            o.optString("data").takeIf { it.isNotBlank() }?.let { data = Uri.parse(it) }
            o.optString("type").takeIf { it.isNotBlank() }?.let { type = it }
            val cats = o.optJSONArray("category")
            if (cats != null) for (i in 0 until cats.length()) addCategory(cats.getString(i))
            val extras = o.optJSONObject("extra")
            extras?.keys()?.forEach { k ->
                val v = extras.get(k)
                when {
                    v is JSONObject -> when (v.optString("t", "")) {
                        "i" -> putExtra(k, v.optInt("v"))
                        "l" -> putExtra(k, v.optLong("v"))
                        "b" -> putExtra(k, v.optBoolean("v"))
                        "f" -> putExtra(k, v.optDouble("v").toFloat())
                        else -> putExtra(k, v.optString("v"))
                    }
                    else -> putExtra(k, extras.getString(k))
                }
            }
        }

        // 拦截：ACTION_VIEW + http(s) URI → 走应用内嵌浏览器（v177），不打开外部浏览器
        val uriData = intent.data
        val action = intent.action
        if ((action == Intent.ACTION_VIEW || action == null) && uriData != null && (uriData.scheme == "http" || uriData.scheme == "https")) {
            QuroBrowserBridge.open(uriData.toString())
            return@runCatching "已在应用内置浏览器打开：${uriData}"
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        "已启动 Intent：${o.optString("action").takeIf { it.isNotBlank() } ?: "(implicit)"}"
    }.getOrElse { "⛔ Intent 启动失败：${it.message}" }

    /**
     * 应用内 API 能力：仅做只读/轻量操作，绝不控制系统。
     * op 取自 cap.action（如 device.info / packages.list / time.now / file.read / file.list / echo）。
     */
    private fun runApi(op: String, args: Map<String, String>): String = runCatching {
        when (op) {
            "device.info" -> buildString {
                append("厂商=${Build.MANUFACTURER}\n")
                append("型号=${Build.MODEL}\n")
                append("品牌=${Build.BRAND}\n")
                append("系统=Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）\n")
                append("设备=${Build.DEVICE}")
            }
            "packages.list" -> {
                val pm = appContext.packageManager
                val apps = pm.getInstalledApplications(0)
                    .map { it.packageName }
                    .sorted()
                    .take(200)
                "已安装应用（共 ${apps.size} 个，最多列 200）:\n" + apps.joinToString("\n")
            }
            "time.now" -> {
                val now = System.currentTimeMillis()
                "epoch=$now\n" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now))
            }
            "file.read" -> {
                val path = args["path"] ?: return@runCatching "⛔ 缺少 path 参数"
                val f = safeSandboxFile(path) ?: return@runCatching "⛔ 仅允许读取应用沙箱内文件"
                if (!f.exists() || !f.isFile) return@runCatching "⛔ 文件不存在：$path"
                "path=${f.absolutePath}\n${f.readText().take(20000)}"
            }
            "file.list" -> {
                val path = args["path"]?.takeIf { it.isNotBlank() } ?: appContext.filesDir.absolutePath
                val f = safeSandboxFile(path) ?: return@runCatching "⛔ 仅允许列举应用沙箱内目录"
                if (!f.exists() || !f.isDirectory) return@runCatching "⛔ 不是目录：$path"
                val items = f.listFiles()?.map { (if (it.isDirectory) "[D] " else "[F] ") + it.name } ?: emptyList()
                "path=${f.absolutePath}（${items.size} 项）:\n" + items.joinToString("\n")
            }
            "echo" -> "workflow: ${args["text"] ?: ""}"

            // ---- 自动化浏览器（AI 自动化研究 / 抓取正文，接 AiBrowserTool 引擎）----
            "ai.browser.automate" -> {
                val query = args["query"] ?: return@runCatching "⛔ 缺少 query 参数"
                val depth = args["depth"]?.toIntOrNull()?.coerceIn(1, 8) ?: 4
                val argJson = JSONObject().apply {
                    put("action", "automate"); put("query", query); put("depth", depth)
                }.toString()
                AiBrowserTool().run(appContext, argJson)
            }
            "ai.browser.read" -> {
                val url = args["url"] ?: return@runCatching "⛔ 缺少 url 参数"
                val argJson = JSONObject().apply { put("action", "read"); put("url", url) }.toString()
                AiBrowserTool().run(appContext, argJson)
            }

            // ---- 系统：电量信息（应用内 BatteryManager，只读）----
            "battery.info" -> {
                val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    ?: return@runCatching "⛔ 电量信息不可用（无 BatteryManager）"
                val capacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                buildString {
                    append("电量=${capacity}%\n")
                    append("充电中=${bm.isCharging}")
                }
            }

            // ---- 文件：沙箱内写入（仅应用沙箱，绝不触达系统文件）----
            "file.write" -> {
                val path = args["path"] ?: return@runCatching "⛔ 缺少 path 参数"
                val content = args["content"] ?: ""
                val f = safeSandboxFile(path) ?: return@runCatching "⛔ 仅允许写入应用沙箱内文件"
                runCatching {
                    f.parentFile?.mkdirs()
                    f.writeText(content)
                    "已写入 ${f.absolutePath}（${content.length} 字节）"
                }.getOrElse { "⛔ 写入失败：${it.message}" }
            }

            // ---- 时间：按指定格式输出（默认 yyyy-MM-dd HH:mm:ss）----
            "time.format" -> {
                val pattern = args["format"]?.takeIf { it.isNotBlank() } ?: "yyyy-MM-dd HH:mm:ss"
                val epoch = args["epoch"]?.toLongOrNull() ?: System.currentTimeMillis()
                val fmt = runCatching { SimpleDateFormat(pattern, Locale.getDefault()) }
                    .getOrElse { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
                "epoch=$epoch\n${fmt.format(Date(epoch))}"
            }

            // ---- 工作流：本地编排多步内置能力（递归分发，真实执行）----
            "workflow.sequence" -> {
                val stepsJson = args["steps"] ?: args["plan"] ?: return@runCatching "⛔ 缺少 steps 参数"
                val arr = runCatching { JSONArray(stepsJson) }.getOrNull()
                    ?: return@runCatching "⛔ steps 不是合法 JSON 数组"
                val sb = StringBuilder()
                var ok = 0
                for (i in 0 until arr.length()) {
                    val step = runCatching { arr.getJSONObject(i) }.getOrNull() ?: continue
                    val sop = step.optString("op", "")
                    val sargs = jsonObjToMap(step.optJSONObject("args"))
                    val out = runApi(sop, sargs)
                    if (!out.startsWith("⛔")) ok++
                    sb.append("步骤 ${i + 1} [$sop]:\n$out\n\n")
                }
                sb.insert(0, "工作流完成：共 ${arr.length()} 步，成功 $ok 步。\n\n")
                sb.toString()
            }

            else -> "⛔ 未知 API 能力：$op"
        }
    }.getOrElse { "⛔ API 执行失败：${it.message}" }

    /**
     * 终端通道（CMS v2）：在 proot/Ubuntu 沙箱内执行命令（即"终端"）。
     * D1 约束：终端执行后端唯一化 = proot；环境未就绪**直接报错**，绝不回退 /system/bin/sh 玩具通道。
     */
    private fun runTerminal(cmd: String): String {
        val st = QuroLinuxEnv.probe(appContext)
        if (!st.available) {
            return "⛔ 终端环境(proot/Ubuntu)未就绪：${st.reason}。请在终端页点「安装 Linux 环境」。"
        }
        val (code, out) = QuroLinuxEnv.run(appContext, cmd)
        return if (code == 0) out else "⛔ 终端执行失败(exit $code): ${out.take(300)}"
    }

    /**
     * 仅允许访问应用沙箱（filesDir / cacheDir / externalCacheDir）内路径，杜绝越权读取系统文件。
     */
    private fun safeSandboxFile(path: String): File? {
        val f = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        val roots = listOf(appContext.filesDir, appContext.cacheDir, appContext.externalCacheDir)
            .mapNotNull { it?.canonicalPath }
        val can = f.canonicalPath
        return if (roots.any { can.startsWith(it) }) f else null
    }

    /** 把 JSONObject 摊平为 String→String 参数表（供工作流步骤递归分发）。 */
    private fun jsonObjToMap(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val m = mutableMapOf<String, String>()
        o.keys().forEach { k -> m[k] = o.optString(k, "") }
        return m
    }
}
