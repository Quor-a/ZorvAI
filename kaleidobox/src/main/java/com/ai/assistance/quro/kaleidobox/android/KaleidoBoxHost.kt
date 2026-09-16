package com.ai.assistance.quro.kaleidobox.android

import android.content.Context
import android.os.Build
import android.os.Handler
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.ai.assistance.quro.kaleidobox.core.KaleidoRuntime
import com.ai.assistance.quro.kaleidobox.core.engine.CapabilityRegistry
import com.ai.assistance.quro.kaleidobox.core.engine.InvokeContext
import com.ai.assistance.quro.kaleidobox.core.engine.KaleidoAppContext
import com.ai.assistance.quro.kaleidobox.core.eco.DependencyManager
import com.ai.assistance.quro.kaleidobox.core.eco.McpBridge
import com.ai.assistance.quro.kaleidobox.core.eco.NpmResolver
import com.ai.assistance.quro.kaleidobox.core.eco.PipResolver
import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.android.engine.JvmDexEngine
import com.ai.assistance.quro.kaleidobox.samples.KaleidoBoxSamples
import java.io.File

/**
 * ZorvAI（QuroAI）宿主装配 —— 宿主 App 只需要这一行：
 *
 * ```kotlin
 * val box = KaleidoBoxHost.init(applicationContext)
 * val out = box.runtime.invokeUnit("greet", KValue.obj("name" to "世界"))
 * ```
 *
 * 这里做四件事：
 *   1. 按设备能力装配引擎（本模块只保留 JVM / Dex 进程内引擎，去掉了原生 QuickJS/LuaJIT
 *      与 Node 子进程依赖，因为 kaleido 运行时在 QuroAI 里以"工具包运行器"身份存在，
 *      只跑 Kotlin/Java 工具包）
 *   2. 把宿主 App 的能力注册成 Kaleido 能力表（**这是"宿主深度融合"的落点**）
 *   3. 装配依赖生态（npm / pip / MCP）
 *   4. 暴露包目录，供装载内置与市场包
 *
 * 注意：本文件是 kaleido-android 的**去品牌化移植**，包名已映射为
 * `com.ai.assistance.quro.kaleidobox.android`。原生引擎与 Node 子进程相关代码已删除。
 */
object KaleidoBoxHost {

    private const val TAG = "KaleidoBox"

    @Volatile
    private var instance: Kaleido? = null

    data class Kaleido(
        val runtime: KaleidoRuntime,
        val mcp: McpBridge,
        val deps: DependencyManager,
        val packagesDir: File,
    )

    fun get(): Kaleido = instance
        ?: throw IllegalStateException("KaleidoBoxHost 未初始化，请先调用 init(context)")

    fun isInitialized() = instance != null

    @Synchronized
    fun init(context: Context, appBridge: KaleidoAppBridge? = null): Kaleido {
        instance?.let { return it }

        val app = context.applicationContext
        val packagesDir = File(app.filesDir, "kaleidobox/packages").apply { mkdirs() }
        val depsDir = File(app.filesDir, "kaleidobox/deps").apply { mkdirs() }
        // DexClassLoader 的优化产物（odex）落盘目录，必须可写。
        val dexDir = File(app.cacheDir, "kaleidobox/dex").apply { mkdirs() }

        // ---- 1. 引擎装配（仅 JVM / Dex 进程内引擎）----
        val runtime = KaleidoRuntime.builder()
            .capabilities { registerAndroidCapabilities(app, this, appBridge) }
            .engines {
                // 唯一真实可运行的引擎路径：进程内 DexClassLoader 加载 Kotlin/Java 工具包。
                // 原生 QuickJS/LuaJIT（NativeBridge/NativeEngine）与 Node 子进程（RpcEngine）已从本模块剔除。
                runCatching {
                    val engine = JvmDexEngine(app.classLoader, dexDir)
                    if (engine.isAvailable()) {
                        register(engine)
                        Log.i(TAG, "JVM / Dex 引擎就绪")
                    } else {
                        Log.w(TAG, "JVM / Dex 引擎不可用")
                    }
                }.onFailure { Log.w(TAG, "JVM / Dex 引擎不可用: ${it.message}") }
            }
            // 插件 = App：给每个包独立私有目录 + 持久化 KV
            .storageDir(File(app.filesDir, "kaleidobox/data"))
            .appContextProvider { pkgId ->
                val filesDir = File(app.filesDir, "kaleidobox/data/$pkgId").apply { mkdirs() }
                val cacheDir = File(app.cacheDir, "kaleidobox/data/$pkgId").apply { mkdirs() }
                KaleidoAppContext(pkgId, filesDir, cacheDir, app)
            }
            .build()

        // ---- 2. MCP 双向桥 ----
        val mcp = McpBridge(
            onRegisterUnit = { unit ->
                // MCP 工具 → 宿主 unit。AI 调 MCP 工具和调本地插件完全一样。
                Log.i(TAG, "MCP 工具已注册为 unit: ${unit.name}")
            },
            onUnregisterUnit = { name -> Log.i(TAG, "MCP 工具已移除: $name") }
        )

        // ---- 3. 依赖生态 ----
        val deps = DependencyManager(
            targetDirOf = { pkgId -> File(depsDir, pkgId.replace('.', '_')).apply { mkdirs() } },
            resolvers = buildList {
                NpmResolver(registry = "https://registry.npmmirror.com").takeIf { it.available() }?.let { add(it) }
                PipResolver(indexUrl = "https://mirrors.cloud.tencent.com/pypi/simple")
                    .takeIf { it.available() }?.let { add(it) }
            }
        )
        Log.i(TAG, "可用依赖生态: ${deps.availableEcosystems()}")

        val k = Kaleido(runtime, mcp, deps, packagesDir)
        instance = k

        // 自动装载内置示例包，保证用户打开 KaleidoBox 面板就有可交互的 UI（装完即见、点完即变）。
        runCatching { KaleidoBoxSamples.installBuiltins(runtime) }
            .onSuccess { Log.i(TAG, "内置示例包已装载") }
            .onFailure { Log.w(TAG, "内置示例包装载失败: ${it.message}") }

        Log.i(TAG, "KaleidoBox 初始化完成 | ABI=${Build.SUPPORTED_ABIS.firstOrNull()} SDK=${Build.VERSION.SDK_INT}")
        return k
    }

    // -------------------------------------------------- 宿主能力表

    /**
     * 宿主把自己的能力暴露给插件。
     *
     * 这是整个设计里最关键的"契约面"：插件无论用什么语言，
     * 看到的都是同一张能力表、同样的调用方式。
     * 宿主想开放新能力 = 在这里加一行；想收紧 = 删一行。
     */
    private fun registerAndroidCapabilities(app: Context, reg: CapabilityRegistry, appBridge: KaleidoAppBridge?) {
        val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        // ---- 时间 ----
        reg.bind("sys.now", "当前时间戳（毫秒）") { _, _ -> KValue.I64(System.currentTimeMillis()) }

        // ---- 包私有键值存储 ----
        reg.bind("data.kv", "包私有键值存储") { args, ctx ->
            val op = args["op"].asString()
            val key = args["key"].asString()
            val prefs = app.getSharedPreferences("kaleidobox_${ctx.pkgId}", Context.MODE_PRIVATE)
            when (op) {
                "set" -> {
                    val v = args["value"]
                    with(prefs.edit()) {
                        when (v) {
                            is KValue.Str -> putString(key, v.value)
                            is KValue.I64 -> putLong(key, v.value)
                            is KValue.F64 -> putFloat(key, v.value.toFloat())
                            is KValue.Bool -> putBoolean(key, v.value)
                            else -> putString(key, v.asString())
                        }
                        apply()
                    }
                    KValue.ok(true)
                }
                "get" -> when {
                    prefs.contains(key).not() -> KValue.Null
                    else -> KValue.Str(prefs.all[key].toString())
                }
                "delete" -> { prefs.edit().remove(key).apply(); KValue.ok(true) }
                else -> KValue.fail("E_PARAM", "op 必须是 set/get/delete")
            }
        }

        // ---- 内置世界书：读取「世界书」插件的条目，供 AI 对话按关键词动态注入 ----
        // data.kv 是按包隔离的，AI 助手包读不到世界书包的私有 KV，故由宿主统一桥接。
        reg.bind("worldbook.entries", "读取内置世界书条目（供 AI 对话按关键词注入）") { _, _ ->
            val prefs = app.getSharedPreferences("kaleidobox_dev.kaleidobox.worldbook", Context.MODE_PRIVATE)
            KValue.Str(prefs.all["worldbook_entries"]?.toString() ?: "[]")
        }

        // ---- 作用域文件读写 ----
        // 注意：只给包私有目录。要访问共享存储必须走 fs.read:media 并由用户授权。
        reg.bind("fs.read:scoped", "读取本包私有目录内的文件") { args, ctx ->
            val path = args["path"].asString()
            val root = File(app.filesDir, "kaleidobox/data/${ctx.pkgId}").apply { mkdirs() }
            val f = resolveSafe(root, path)
                ?: return@bind KValue.fail("E_PERMISSION", "路径越界: $path")
            if (!f.exists()) return@bind KValue.fail("E_NOT_FOUND", "文件不存在: $path")
            KValue.Bytes(f.readBytes())
        }

        reg.bind("fs.write:scoped", "写入本包私有目录") { args, ctx ->
            val path = args["path"].asString()
            val root = File(app.filesDir, "kaleidobox/data/${ctx.pkgId}").apply { mkdirs() }
            val f = resolveSafe(root, path)
                ?: return@bind KValue.fail("E_PERMISSION", "路径越界: $path")
            f.parentFile?.mkdirs()
            when (val data = args["data"]) {
                is KValue.Str -> f.writeText(data.value)
                is KValue.Bytes -> f.writeBytes(data.value)
                else -> return@bind KValue.fail("E_PARAM", "data 必须是字符串或字节")
            }
            KValue.ok(mapOf("path" to path, "size" to f.length()))
        }

        reg.bind("fs.list:scoped", "列出本包私有目录") { args, ctx ->
            val root = File(app.filesDir, "kaleidobox/data/${ctx.pkgId}").apply { mkdirs() }
            val rel = args["path"].asString()
            val dir = if (rel.isBlank()) root else resolveSafe(root, rel)
            if (dir == null || !dir.isDirectory) return@bind KValue.Arr(emptyList())
            KValue.Arr(dir.listFiles()?.map {
                KValue.obj("name" to it.name, "dir" to it.isDirectory, "size" to it.length())
            } ?: emptyList())
        }

        // ---- UI ----
        reg.bind("ui.toast", "弹出 Toast（自动切主线程）") { args, _ ->
            val text = args["text"].asString()
            mainHandler.post { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }
            KValue.ok(true)
        }

        reg.bind("ui.clipboard", "读写剪贴板") { args, _ ->
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = args["text"]
            if (text is KValue.Str) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("kaleidobox", text.value))
                KValue.ok(true)
            } else {
                val clip = cm.primaryClip
                KValue.Str(clip?.getItemAt(0)?.text?.toString() ?: "")
            }
        }

        // ---- 系统信息 ----
        reg.bind("sys.package.info", "宿主 App 与设备信息") { _, _ ->
            KValue.obj(
                "hostVersion" to (runCatching {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                }.getOrNull() ?: "unknown"),
                "sdk" to Build.VERSION.SDK_INT,
                "abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}"
            )
        }

        // ---- 富设备信息（供"设备信息"app 完整展示硬件/系统/资源/电量/网络）----
        reg.bind("sys.device.info", "读取完整设备信息：硬件/系统/CPU/内存/存储/屏幕/电池/网络/运行时长") { _, _ ->
            // 屏幕
            val dm = app.resources.displayMetrics
            val densityName = when {
                dm.densityDpi >= 640 -> "xxxhdpi"
                dm.densityDpi >= 480 -> "xxhdpi"
                dm.densityDpi >= 320 -> "xhdpi"
                dm.densityDpi >= 240 -> "hdpi"
                dm.densityDpi >= 160 -> "mdpi"
                else -> "ldpi"
            }
            // CPU 核心数 + 最高主频（/sys 不可读时降级为 -1）
            val cores = Runtime.getRuntime().availableProcessors()
            val maxFreqKhz = runCatching {
                File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrDefault(-1L)
            // 内存
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            // 存储（数据分区）
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val storageTotal = stat.blockCountLong * stat.blockSizeLong
            val storageFree = stat.availableBlocksLong * stat.blockSizeLong
            // 电池：先用 BatteryManager 属性（无需注册广播，最稳），状态/温度用粘性广播尽力读取
            val bm = app.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val batteryPct = runCatching {
                bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            }.getOrDefault(-1)
            val battery = runCatching {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()
            val status = battery?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val tempRaw = battery?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val batteryStatus = when (status) {
                android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
                android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
                android.os.BatteryManager.BATTERY_STATUS_FULL -> "已充满"
                android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
                else -> "未知"
            }
            // 网络
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val netType = when {
                caps == null -> "无网络"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝数据"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "其他"
            }
            // 运行时长
            val upMs = android.os.SystemClock.elapsedRealtime()
            val upStr = "${upMs / 3_600_000}h ${(upMs % 3_600_000) / 60_000}m"
            KValue.obj(
                "manufacturer" to Build.MANUFACTURER,
                "brand" to Build.BRAND,
                "model" to Build.MODEL,
                "product" to Build.PRODUCT,
                "hardware" to Build.HARDWARE,
                "device" to Build.DEVICE,
                "androidRelease" to Build.VERSION.RELEASE,
                "sdk" to Build.VERSION.SDK_INT,
                "securityPatch" to (if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else ""),
                "buildId" to Build.ID,
                "buildFingerprint" to Build.FINGERPRINT,
                "bootloader" to Build.BOOTLOADER,
                "abis" to (Build.SUPPORTED_ABIS?.joinToString(", ") ?: ""),
                "cpuCores" to cores,
                "cpuMaxFreqMhz" to (if (maxFreqKhz > 0) maxFreqKhz / 1000 else -1L),
                "memTotal" to mi.totalMem,
                "memAvail" to mi.availMem,
                "lowMemory" to mi.lowMemory,
                "storageTotal" to storageTotal,
                "storageFree" to storageFree,
                "screenW" to dm.widthPixels,
                "screenH" to dm.heightPixels,
                "densityDpi" to dm.densityDpi,
                "densityName" to densityName,
                "batteryPct" to batteryPct,
                "batteryStatus" to batteryStatus,
                "batteryTempC" to (if (tempRaw > 0) tempRaw / 10.0 else -1.0),
                "netType" to netType,
                "uptime" to upStr,
                "locale" to java.util.Locale.getDefault().toString(),
            )
        }

        // ---- 日志：透传到 logcat ----
        reg.bind("sys.log", "写入宿主日志") { args, _ ->
            val level = args["level"].asString()
            val tag = args["tag"].asString().ifBlank { "kaleidobox-pkg" }
            val msg = args["message"].asString()
            when (level) {
                "ERROR" -> Log.e(tag, msg)
                "WARN" -> Log.w(tag, msg)
                "DEBUG" -> Log.d(tag, msg)
                else -> Log.i(tag, msg)
            }
            KValue.ok(true)
        }

        // ---- 网络：受 netEgress 策略约束（由 PermissionGate 门控）----
        // 返回：status(状态码) / body(响应体) / headers(响应头数组) / timeMs(耗时) / size(字节) / contentType
        reg.bind("net.http", "发起 HTTP 请求（受 allowHosts 约束），回显状态码/响应头/耗时/大小") { args, _ ->
            val url = args["url"].asString()
            if (!url.startsWith("https://")) return@bind KValue.fail("E_PERMISSION", "仅允许 https")
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            try {
                conn.requestMethod = args["method"].asString().ifBlank { "GET" }.uppercase()
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.instanceFollowRedirects = true
                args["headers"].asMap().forEach { (k, v) ->
                    conn.setRequestProperty(k, v.asString())
                }
                val body = args["body"]
                if (body is KValue.Str && body.value.isNotEmpty()) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.value.toByteArray()) }
                }
                val t0 = System.nanoTime()
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val bytes = runCatching { stream?.readBytes() ?: ByteArray(0) }.getOrDefault(ByteArray(0))
                val ms = (System.nanoTime() - t0) / 1_000_000
                val respHeaders = runCatching {
                    conn.headerFields.entries
                        .filter { it.key != null }
                        .sortedBy { it.key.lowercase() }
                        .map { it.key to it.value.joinToString(", ") }
                }.getOrDefault(emptyList())
                KValue.obj(
                    "status" to code,
                    "body" to String(bytes, Charsets.UTF_8),
                    "headers" to KValue.Arr(respHeaders.map { (k, v) -> KValue.obj("k" to k, "v" to v) }),
                    "timeMs" to ms,
                    "size" to bytes.size.toLong(),
                    "contentType" to (conn.contentType ?: ""),
                )
            } finally {
                conn.disconnect()
            }
        }

        // ---- App 级能力（需宿主注入 KaleidoAppBridge；否则这些能力不可用）----
        if (appBridge != null) {
            // AI 对话引擎：直接走用户已配的 provider（故障转移/多 key 由宿主管理）
            reg.bind("ai.chat", "调用宿主 AI 对话引擎（用户已配 provider），支持多轮历史") { args, _ ->
                // 新协议：传完整对话历史 messages: [{role, content}, ...]；
                // 兼容旧调用：仅传单条 prompt。
                val messages = (args["messages"] as? KValue.Arr)?.value?.mapNotNull { kv ->
                    (kv as? KValue.Obj)?.let {
                        val role = it.value["role"]?.asString()
                            ?.takeIf { r -> r in setOf("system", "user", "assistant", "tool") } ?: "user"
                        val content = it.value["content"]?.asString() ?: ""
                        role to content
                    }
                } ?: run {
                    val prompt = args["prompt"].asString().ifBlank { return@bind KValue.fail("E_PARAM", "messages 或 prompt 不能为空") }
                    listOf("user" to prompt)
                }
                val system = args["system"].asString().takeIf { !it.isBlank() }
                when (val r = appBridge.aiChat(messages, system)) {
                    is KaleidoAiResult.Ok -> KValue.obj(
                        "text" to r.text,
                        "tools" to KValue.Arr(r.tools.map { KValue.Str(it) }),
                    )
                    is KaleidoAiResult.Err -> KValue.fail("E_AI", r.message)
                }
            }
            // 宿主是否已配置可用 AI 提供商（插件据此决定是否显示"未配置"提示）
            reg.bind("ai.available", "宿主是否已配置可用 AI 提供商") { _, _ ->
                KValue.Bool(appBridge.aiAvailable())
            }
            // 终端 / Linux 环境：同步执行命令，返回 (退出码, 输出)
            reg.bind("app.term.run", "在终端/Linux 环境同步执行命令") { args, _ ->
                val command = args["command"].asString().ifBlank { return@bind KValue.fail("E_PARAM", "command 不能为空") }
                val timeout = (args["timeout"] as? KValue.I64)?.value?.takeIf { it > 0 } ?: 30_000L
                val (code, out) = appBridge.termRun(command, timeout)
                KValue.obj("code" to code, "output" to out)
            }
        }

        // ---- APP 级：APK 逆向（列应用 / 组件权限 / 拉取 / DEX / 签名，仅用 PackageManager）----
        reg.bind("app.apk.list", "列出已安装应用（filter 关键字过滤，withSystem 含系统应用）") { args, _ ->
            val filter = args["filter"].asString().lowercase()
            val withSystem = (args["withSystem"] as? KValue.Bool)?.value ?: false
            val pm = app.packageManager
            val list = pm.getInstalledPackages(0)
                .filter { pi ->
                    val isSys = (pi.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
                    if (!(withSystem || !isSys)) false
                    else if (filter.isBlank()) true
                    else {
                        val label = pi.applicationInfo?.loadLabel(pm)?.toString().orEmpty().lowercase()
                        label.contains(filter) || pi.packageName.lowercase().contains(filter)
                    }
                }
                .map { pi ->
                    val ai = pi.applicationInfo
                    val vc: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        pi.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        pi.versionCode.toLong()
                    }
                    KValue.obj(
                        "pkg" to pi.packageName,
                        "label" to (ai?.loadLabel(pm)?.toString() ?: pi.packageName),
                        "versionName" to (pi.versionName ?: ""),
                        "versionCode" to vc,
                        "system" to ((ai?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0),
                        "sourceDir" to (ai?.sourceDir ?: ""),
                        // 细节字段：APK 体积 / 安装与更新时间 / 目标与最低 SDK / uid / 是否为用户应用
                        "size" to (ai?.sourceDir?.let { runCatching { File(it).length() }.getOrDefault(0L) } ?: 0L),
                        "installTime" to pi.firstInstallTime,
                        "updateTime" to pi.lastUpdateTime,
                        "targetSdk" to (ai?.targetSdkVersion ?: 0),
                        "minSdk" to (ai?.minSdkVersion ?: 0),
                        "uid" to (ai?.uid ?: -1),
                        "updatedSystemApp" to ((ai?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0),
                    )
                }
            KValue.Arr(list)
        }

        reg.bind("app.apk.info", "查询应用清单：四大组件 / 权限 / 签名") { args, _ ->
            val pkgName = args["pkg"].asString().ifBlank { return@bind KValue.fail("E_PARAM", "pkg 不能为空") }
            val pm = app.packageManager
            val flags = android.content.pm.PackageManager.GET_ACTIVITIES or
                android.content.pm.PackageManager.GET_SERVICES or
                android.content.pm.PackageManager.GET_RECEIVERS or
                android.content.pm.PackageManager.GET_PROVIDERS or
                android.content.pm.PackageManager.GET_PERMISSIONS
            val pi = try {
                pm.getPackageInfo(pkgName, flags)
            } catch (e: Exception) { return@bind KValue.fail("E_NOT_FOUND", "应用不存在: ${e.message}") }
            val ai = pi.applicationInfo
            val activities = pi.activities?.map { it.name }?.toList() ?: emptyList()
            val services = pi.services?.map { it.name }?.toList() ?: emptyList()
            val receivers = pi.receivers?.map { it.name }?.toList() ?: emptyList()
            val providers = pi.providers?.map { it.name }?.toList() ?: emptyList()
            val perms = pi.requestedPermissions?.toList() ?: emptyList()
            val sigs = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pi.signingInfo?.apkContentsSigners?.map { c ->
                        java.security.MessageDigest.getInstance("SHA-1").digest(c.toByteArray()).toHex()
                    }
                } else null
            }.getOrNull() ?: emptyList()
            KValue.obj(
                "pkg" to pkgName,
                "label" to (ai?.loadLabel(pm)?.toString() ?: pkgName),
                "sourceDir" to (ai?.sourceDir ?: ""),
                "activities" to KValue.Arr(activities.map { KValue.Str(it) }),
                "services" to KValue.Arr(services.map { KValue.Str(it) }),
                "receivers" to KValue.Arr(receivers.map { KValue.Str(it) }),
                "providers" to KValue.Arr(providers.map { KValue.Str(it) }),
                "permissions" to KValue.Arr(perms.map { KValue.Str(it) }),
                "signatures" to KValue.Arr(sigs.map { KValue.Str(it) }),
            )
        }

        reg.bind("app.apk.pull", "把目标 APK 复制到本包私有目录（供后续读取/分析）") { args, ctx ->
            val pkgName = args["pkg"].asString().ifBlank { return@bind KValue.fail("E_PARAM", "pkg 不能为空") }
            val pm = app.packageManager
            val src = runCatching { pm.getApplicationInfo(pkgName, 0).sourceDir }.getOrNull()
                ?: return@bind KValue.fail("E_NOT_FOUND", "应用不存在")
            val root = File(app.filesDir, "kaleidobox/data/${ctx.pkgId}").apply { mkdirs() }
            val outName = "${pkgName.replace('.', '_')}.apk"
            val out = File(root, outName)
            runCatching { java.io.File(src).copyTo(out, overwrite = true) }
                .onFailure { return@bind KValue.fail("E_IO", "复制失败: ${it.message}") }
            KValue.obj("name" to outName, "size" to out.length(), "absPath" to out.absolutePath)
        }

        reg.bind("app.apk.dex", "列出 APK 内的 DEX 文件") { args, _ ->
            val pkgName = args["pkg"].asString().ifBlank { return@bind KValue.fail("E_PARAM", "pkg 不能为空") }
            val pm = app.packageManager
            val src = runCatching { pm.getApplicationInfo(pkgName, 0).sourceDir }.getOrNull()
                ?: return@bind KValue.fail("E_NOT_FOUND", "应用不存在")
            val entries = runCatching {
                java.util.zip.ZipFile(src).use { zf ->
                    zf.entries().toList().filter { it.name.endsWith(".dex") }
                        .map { KValue.obj("name" to it.name, "size" to it.size) }
                }
            }.getOrNull() ?: return@bind KValue.fail("E_IO", "读取 APK 失败")
            KValue.Arr(entries)
        }

        // ---- App 级"真 Android 组件"能力（固定到桌面 / 系统通知，无需 appBridge）----
        reg.bind("app.shortcut.create", "把插件固定到桌面（launcher 支持时）") { args, ctx ->
            val pkgId = args["pkgId"].asString().ifBlank { ctx.pkgId }
            val surfaceId = args["surfaceId"].asString().takeIf { !it.isBlank() }
            val label = args["label"].asString().takeIf { !it.isBlank() } ?: pkgId
            KaleidoShortcuts.pinPluginShortcut(app, pkgId, surfaceId, label)
        }

        reg.bind("app.notify", "发送系统通知（无权限时自动降级 Toast）") { args, _ ->
            val title = args["title"].asString().ifBlank { "KaleidoBox" }
            val body = args["body"].asString().ifBlank { "" }
            val channelId = "kaleidobox_notify"
            val nm = NotificationManagerCompat.from(app)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(channelId, "KaleidoBox 通知", NotificationManager.IMPORTANCE_DEFAULT)
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
            val n = androidx.core.app.NotificationCompat.Builder(app, channelId)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(app.packageManager.getApplicationInfo(app.packageName, 0).icon)
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(System.currentTimeMillis().toInt(), n) }
                .onFailure { Toast.makeText(app, "$title：$body", Toast.LENGTH_LONG).show() }
            KValue.ok(true)
        }
    }

    /** 防目录穿越：解析后的路径必须在 root 之内。 */
    private fun resolveSafe(root: File, rel: String): File? {
        val canonical = File(root, rel).canonicalPath
        val rootCanonical = root.canonicalPath
        return if (canonical.startsWith(rootCanonical + File.separator) || canonical == rootCanonical) {
            File(canonical)
        } else null
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
