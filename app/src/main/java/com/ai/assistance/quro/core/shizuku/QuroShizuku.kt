package com.ai.assistance.quro.core.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.ai.assistance.quro.IQuroShellService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Quro Shizuku 真实集成层（CapOS L2 通道）。
 *
 * ## 执行架构（v429 重构，对齐 alian-android）
 *
 * **主路径 — AIDL UserService**（推荐，稳定）：
 *   通过 [Shizuku.bindUserService] 将 [QuroShellService] 绑定到 Shizuku 特权进程。
 *   所有命令通过 AIDL [IQuroShellService.exec] 在 root/shell 环境中执行，
 *   不依赖 Shizuku private API（newProcess），不受版本升级影响。
 *
 * **备选路径 — 反射 newProcess**（降级用）：
 *   当 AIDL 服务绑定失败时，回退到反射调用 private [Shizuku.newProcess]。
 *   这是 v427 的方案，保留作容错。
 *
 * ## 与其他类的关系
 * - [com.ai.assistance.quro.core.privilege.QuroShizukuBridge]：探测状态展示
 * - 本类：真实执行 + 授权请求 + 安装检测
 */
object QuroShizuku {

    private const val TAG = "QuroShizuku"

    // ════════════════════════════════════════
    // AIDL UserService 主路径
    // ════════════════════════════════════════

    @Volatile
    private var shellService: IQuroShellService? = null
    @Volatile
    private var aidlBound = false

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(
                "com.ai.assistance.quro",
                QuroShellService::class.java.name
            )
        )
            .daemon(false)
            .processNameSuffix("quro_shell")
            .debuggable(false)
            .version(1)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shellService = IQuroShellService.Stub.asInterface(service)
            aidlBound = true
            Log.i(TAG, "✅ AIDL ShellService 已连接（命令将在特权进程中执行）")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            aidlBound = false
            Log.w(TAG, "⚠️ AIDL ShellService 断开")
        }
    }

    /**
     * 绑定 AIDL UserService 到 Shizuku 特权进程。
     * 需在 Shizuku 已授权后调用（isReady=true 时自动触发）。
     */
    fun bindAidlService() {
        if (aidlBound) return
        runCatching {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
            Log.d(TAG, "bindUserService 已发起")
        }.onFailure { e ->
            Log.w(TAG, "bindUserService 失败: ${e.message}，将使用备选路径")
        }
    }

    /** 解绑 AIDL 服务。 */
    fun unbindAidlService() {
        if (!aidlBound) return
        runCatching {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            shellService = null
            aidlBound = false
        }
    }

    /** AIDL 路径是否可用。 */
    val isAidlAvailable: Boolean get() = aidlBound && shellService != null

    // ════════════════════════════════════════
    // 备选路径：反射 newProcess（v427 方案）
    // ════════════════════════════════════════

    @Volatile
    private var newProcessMethod: java.lang.reflect.Method? = null

    private fun resolveNewProcess(): java.lang.reflect.Method {
        newProcessMethod?.let { return it }
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
        newProcessMethod = m
        return m
    }

    private fun shizukuNewProcess(cmd: Array<String>, env: Array<String>?, dir: String?): Process {
        val m = runCatching { resolveNewProcess() }.getOrElse {
            throw IllegalStateException("无法解析 Shizuku.newProcess（Shizuku API 不兼容）", it)
        }
        @Suppress("UNCHECKED_CAST")
        return m.invoke(null, cmd, env, dir) as Process
    }

    // ════════════════════════════════════════
    // Binder 存活检测（保留原有逻辑）
    // ════════════════════════════════════════

    private val binderAlive = java.util.concurrent.atomic.AtomicBoolean(false)
    private val listenersRegistered = java.util.concurrent.atomic.AtomicBoolean(false)

    fun ensureInit() {
        if (listenersRegistered.compareAndSet(false, true)) {
            runCatching {
                Shizuku.addBinderReceivedListener(object : Shizuku.OnBinderReceivedListener {
                    override fun onBinderReceived() {
                        binderAlive.set(true)
                        // binder 就绪后立即尝试绑定 AIDL 服务
                        bindAidlService()
                    }
                })
                Shizuku.addBinderDeadListener(object : Shizuku.OnBinderDeadListener {
                    override fun onBinderDead() {
                        binderAlive.set(false)
                        aidlBound = false
                        shellService = null
                    }
                })
            }
        }
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            binderAlive.set(true)
            bindAidlService()
        }
    }

    /** Shizuku Binder 是否存活（服务已连接，可询问授权状态）。 */
    val isAlive: Boolean get() = runCatching {
        ensureInit()
        if (binderAlive.get()) return@runCatching true
        val alive = Shizuku.pingBinder() || Shizuku.pingBinder()
        if (alive) {
            binderAlive.set(true)
            bindAidlService()
        }
        alive
    }.getOrDefault(false)

    /**
     * Shizuku 是否已就绪（binder 可用、权限已授予、且服务 UID 合法）。
     * 就绪时会自动尝试绑定 AIDL 服务。
     */
    val isReady: Boolean get() = runCatching {
        if (!isAlive) return@runCatching false
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) return@runCatching false
        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        if (uid != 0 && uid != 2000) return@runCatching false
        // 就绪且尚未绑定时，尝试绑定 AIDL
        if (!aidlBound) bindAidlService()
        true
    }.getOrElse { false }

    // ════════════════════════════════════════
    // 授权 & 安装检测（保留原有逻辑）
    // ════════════════════════════════════════

    fun requestPermission(activity: android.app.Activity, requestCode: Int, listener: Shizuku.OnRequestPermissionResultListener?) {
        if (!isInstalled(activity)) {
            Log.w(TAG, "Shizuku 未安装，无法请求权限")
            return
        }
        try {
            listener?.let { Shizuku.addRequestPermissionResultListener(it) }
            Shizuku.requestPermission(requestCode)
        } catch (_: Exception) {
            Log.w(TAG, "Shizuku API 调用失败，请手动授权")
            try { activity.startActivity(android.content.Intent("moe.shizuku.manager.intent.action.REQUEST_PERMISSION")) }
            catch (_: Exception) { /* 忽略 */ }
        }
    }

    fun isInstalled(ctx: Context): Boolean {
        val pm = ctx.packageManager
        if (runCatching { pm.getPackageInfo("moe.shizuku.manager", 0) }.getOrNull() != null) return true
        return runCatching { pm.getPackageInfo("moe.shizuku.privileged.api", 0) }.getOrNull() != null
    }

    // ════════════════════════════════════════
    // 命令执行（双路径：AIDL 优先 → 反射降级）
    // ════════════════════════════════════════

    /**
     * 经 Shizuku 执行命令。
     *
     * **优先级**：
     *   1. AIDL UserService（[shellService].exec）— 在特权进程中 Runtime.exec，稳定可靠
     *   2. 反射 newProcess — 备选，依赖 private API
     *
     * 两种路径均以 Shizuku 服务 UID（root=0 / shell=2000）执行，
     * 绝不降级到 App 自身 UID 的 Runtime.exec。
     */
    fun exec(command: String): String {
        if (!isReady) return "❌ Shizuku 未就绪（请到 系统权限 → L2 Shizuku → 请求授权，并确保 Shizuku 应用正在运行）"

        // ── 路径 1：AIDL UserService（主路径）──
        if (isAidlAvailable) {
            return try {
                val result = shellService!!.exec(command)
                Log.d(TAG, "[AIDL] exec OK: ${result.take(80)}")
                result
            } catch (e: Exception) {
                Log.e(TAG, "[AIDL] exec 失败，降级到反射路径", e)
                execViaReflection(command) // 降级
            }
        }

        // ── 路径 2：反射 newProcess（备选）──
        return execViaReflection(command)
    }

    /**
     * 以 root 权限执行命令。
     * - root 模式（UID=0）：直接 sh -c 即为 root
     * - shell 模式（UID=2000）：经 su -c 提权
     */
    fun execAsRoot(command: String): String {
        if (!isReady) return "❌ Shizuku 未就绪"

        // AIDL 路径：直接传 su -c 或 sh -c 给特权进程
        if (isAidlAvailable) {
            return try {
                val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
                val cmd = if (uid == 0) command else "su -c $command"
                val result = shellService!!.exec(cmd)
                Log.d(TAG, "[AIDL] execAsRoot OK: ${result.take(80)}")
                result
            } catch (e: Exception) {
                Log.e(TAG, "[AIDL] execAsRoot 失败，降级", e)
                execAsRootViaReflection(command)
            }
        }

        return execAsRootViaReflection(command)
    }

    // ── 反射路径实现（内部用）──

    private fun execViaReflection(command: String): String {
        return try {
            val process: Process = shizukuNewProcess(arrayOf("sh", "-c", command), null, null)
            val out = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val err = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val exitCode = process.waitFor()
            val body = (out + err).trim()
            "exit=$exitCode\n${if (body.isBlank()) "(无输出)" else body}"
        } catch (e: Throwable) {
            Log.e(TAG, "[Reflect] exec 失败", e)
            "❌ Shizuku 执行失败: ${e.message}"
        }
    }

    private fun execAsRootViaReflection(command: String): String {
        return try {
            val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
            val process: Process = if (uid == 0) {
                shizukuNewProcess(arrayOf("sh", "-c", command), null, null)
            } else {
                shizukuNewProcess(arrayOf("su", "-c", command), null, null)
            }
            val out = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val err = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val exitCode = process.waitFor()
            val body = (out + err).trim()
            "exit=$exitCode\n${if (body.isBlank()) "(无输出)" else body}"
        } catch (e: Throwable) {
            "❌ Root 执行失败: ${e.message}"
        }
    }

    // ════════════════════════════════════════
    // 调试信息
    // ════════════════════════════════════════

    /** 获取详细状态（调试 / UI 展示用）。 */
    fun getVersionInfo(ctx: Context): String = try {
        """{
            |"installed":${isInstalled(ctx)},
            |"alive":$isAlive,
            |"ready":$isReady,
            |"aidlAvailable":$isAidlAvailable,
            |"permission":${Shizuku.checkSelfPermission()},
            |"uid":${runCatching { Shizuku.getUid() }.getOrDefault(-1)},
            |"pid":${android.os.Process.myPid()}
        }""".trimMargin()
    } catch (e: Exception) {
        "{\"error\":\"${e.message}\"}"
    }
}
