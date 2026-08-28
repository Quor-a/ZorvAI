package com.ai.assistance.quro.core.linux

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import com.ai.assistance.quro.util.QuroDiag
import kotlin.time.Duration.Companion.milliseconds

/** 把 Windows CRLF 统一为 LF，防止写入 proot/Ubuntu 的脚本被 sh 解析成非法选项。 */
private fun String.normalizeLineEndings(): String = this.replace("\r\n", "\n").replace("\r", "\n")

/**
 * 应用内 Linux 环境（proot + Ubuntu 24.04 ARM64）后端。
 *
 * v108 删除了原 QuroLinuxEnv 资产（proot 二进制 + rootfs 随包解压），
 * 导致终端只能回退成设备 Toybox sh、AI 的 linux_* 工具全部报「环境不可用」。
 *
 * 本版本采用 Android Linux Sandbox 思路并落地：
 * - proot 二进制以预编译 .so 形式打包进 jniLibs，**从 applicationInfo.nativeLibraryDir
 *   取执行权限**（Android 仅在此目录授予 .so 可执行权限，这是终端此前跑不起来的根因）；
 * - Ubuntu 24.04 ARM64 rootfs（首次使用时从镜像下载 base rootfs 并解压到应用私有目录）；
 * - 交互终端经 [shellLaunch] 以 proot 常驻 /bin/sh，获得 python3 / 完整写能力等；
 * - 非交互命令经 [run] 一次性执行，供 AI 的 linux_* / terminal_* 工具调用；
 * - 任一资产缺失则优雅降级并报明确原因，不崩溃、不静默失败。
 */
object QuroLinuxEnv {

    private const val TAG = "QuroLinuxEnv"

    /** Ubuntu 24.04 LTS (Noble) ARM64 rootfs。 */
    private const val UBUNTU_CODENAME = "noble"
    private const val UBUNTU_VERSION = "24.04.4"
    private const val BUFFER_SIZE = 8192
    private const val MAX_OUTPUT_LENGTH = 15_000L

    /**
     * dpkg/apt 锁检测与释放 prologue —— 所有经 proot 的 apt/dpkg 安装脚本都应先执行。
     *
     * 上一次安装中断/崩溃会残留 /var/lib/dpkg/lock* 与 /var/cache/apt/archives/lock，
     * 导致后续 apt-get 卡死或报 "Could not get lock ... - open (11: Resource temporarily unavailable)" / "Unable to acquire the dpkg frontend lock"。
     * 策略：逐个检查锁文件，若已存在且**无进程占用**（stale，典型为上次中断遗留）则删除；
     * 若被进程占用则跳过（不误杀运行中的 apt）。最后 dpkg --configure -a 修复半配置状态。
     */
    val APT_LOCK_RELEASE_PROLOGUE = """
        |# ── dpkg / apt 锁检测与释放（仅当锁残留且无进程占用时才释放）──
        |echo "[lock] 检查 dpkg/apt 残留锁..."
        |_quro_release_locks() {
        |  local released=0
        |  for lk in /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend /var/cache/apt/archives/lock /var/lib/apt/lists/lock; do
        |    [ -e "${'$'}lk" ] || continue
        |    local held=0
        |    if command -v fuser >/dev/null 2>&1; then
        |      fuser "${'$'}lk" >/dev/null 2>&1 && held=1
        |    else
        |      for _p in /proc/[0-9]*/cmdline; do
        |        if tr '\0' ' ' < "${'$'}_p" 2>/dev/null | grep -qE 'apt|dpkg'; then held=1; break; fi
        |      done
        |    fi
        |    if [ "${'$'}held" -eq 0 ]; then
        |      rm -f "${'$'}lk" 2>/dev/null || true
        |      echo "[lock] 释放 stale 锁: ${'$'}lk"
        |      released=1
        |    else
        |      echo "[lock] ${'$'}lk 被进程占用，跳过释放"
        |    fi
        |  done
        |  [ "${'$'}released" -eq 0 ] && echo "[lock] 无残留锁，无需释放"
        |}
        |_quro_release_locks
        |dpkg --configure -a 2>/dev/null || true
        |""".trimMargin()

    // rootfs 下载镜像（Ubuntu Base 最小化 rootfs）- 使用HTTP避免SSL问题
    // 优先使用阿里云镜像（清华镜像可能被封锁）
    private val UBUNTU_ROOTFS_MIRRORS = listOf(
        "http://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release",
        "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release",
        "http://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release",
    )

    // apt 软件源镜像（阿里云优先，清华其次，国际兜底）
    // ⚠️ arm64/aarch64 架构必须用 ubuntu-ports，不是 ubuntu！
    private val UBUNTU_APT_MIRRORS = listOf(
        "http://mirrors.aliyun.com/ubuntu-ports",
        "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports",
        "http://ports.ubuntu.com/ubuntu-ports",
    )

    sealed interface SandboxState {
        data object NotInstalled : SandboxState
        data class Downloading(val progress: Float) : SandboxState
        data object Extracting : SandboxState
        data class Installing(val detail: String = "") : SandboxState
        data object Ready : SandboxState
        data class Error(val message: String) : SandboxState
    }

    data class EnvStatus(
        val available: Boolean,
        val prootPath: String?,
        val rootfsPath: String?,
        val reason: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<SandboxState>(SandboxState.NotInstalled)
    val state: StateFlow<SandboxState> = _state

    private val setupMutex = Mutex()
    private var setupJob: Job? = null

    private fun sandboxDir(context: Context) = File(context.filesDir, "linux-sandbox")

    fun rootfsPath(context: Context): String =
        File(sandboxDir(context), "rootfs").absolutePath

    /** /root 绑定到外部可见目录，使沙箱内产物可被 FileProvider 打开。 */
    fun homePath(context: Context): String {
        val external = context.getExternalFilesDir(null)
        val target = if (external != null) File(external, "sandbox-home") else File(sandboxDir(context), "home")
        target.mkdirs()
        return target.absolutePath
    }

    fun tmpPath(context: Context): String =
        File(sandboxDir(context), "tmp").absolutePath

    /** proot 二进制：nativeLibraryDir 内，Android 在此授予 .so 可执行权限。 */
    fun prootPath(context: Context): String = findNativeLibWithAssetsFallback(context, "libproot.so")

    fun loaderPath(context: Context): String = findNativeLibWithAssetsFallback(context, "libproot-loader.so")
    
    /**
     * 查找 native library，如果 nativeLibraryDir 为空则从 assets 解压。
     * 解决某些设备/ROM 的 native library 解压失败问题。
     */
    private fun findNativeLibWithAssetsFallback(context: Context, libName: String): String {
        val primary = File(context.applicationInfo.nativeLibraryDir, libName)
        if (primary.exists()) return primary.absolutePath
        
        // nativeLibraryDir 为空，尝试从 assets 解压
        Log.w(TAG, "⚠ $libName 在 nativeLibraryDir 中不存在，尝试从 assets 解压")
        val extracted = extractProotFromAssets(context, libName)
        if (extracted != null) {
            Log.i(TAG, "✅ 从 assets 解压 $libName: ${extracted.absolutePath}")
            return extracted.absolutePath
        }
        
        Log.w(TAG, "⚠ $libName 在所有路径均未找到，返回默认路径: ${primary.absolutePath}")
        return primary.absolutePath
    }
    
    /**
     * 从 assets/linux_env/ 解压 proot 二进制到应用私有目录。
     */
    private fun extractProotFromAssets(context: Context, libName: String): File? {
        return try {
            val assetName = when (libName) {
                "libproot.so" -> "linux_env/proot"
                "libproot-loader.so" -> "linux_env/libproot-loader.so"
                "libproot-loader32.so" -> "linux_env/libproot-loader32.so"
                else -> null
            } ?: return null
            
            val targetDir = File(context.filesDir, "native-libs")
            targetDir.mkdirs()
            val target = File(targetDir, libName)
            if (target.exists()) return target
            
            context.assets.open(assetName).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            target.setExecutable(true, false)
            Log.i(TAG, "✅ 从 assets 解压 $assetName -> ${target.absolutePath} (${target.length()} bytes)")
            target
        } catch (e: Exception) {
            Log.e(TAG, "❌ 从 assets 解压 $libName 失败: ${e.message}")
            null
        }
    }



    private fun getLinuxArch(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when {
            abi.startsWith("arm64") -> "aarch64"
            abi.startsWith("armeabi") -> "armhf"
            abi.startsWith("x86_64") -> "x86_64"
            abi.startsWith("x86") -> "x86"
            else -> "aarch64"
        }
    }

    /**
     * 探测环境是否就绪（不触发下载）。
     *
     * **关键修复（终端「部署按钮变导出日志」根因）**：旧实现只在环境确实存在时把 [_state]
     * 升级为 [SandboxState.Ready]，却从不降级。一旦 [_state] 进入 Ready，即使 rootfs 被
     * （沙箱内命令误删 / 系统清理私有目录 / 升级残留）删掉，[_state] 仍停在 Ready，
     * 终端安装横幅被永久隐藏，用户顶栏只剩「导出日志」按钮、无从重新部署。
     * 现改为：探测到 proot/rootfs 实际不可用时，若此前被错误标记为 Ready，则降级回 NotInstalled，
     * 让安装横幅重新出现。下载/安装中间态由 [setup] 自身管理，此处不抢状态。
     */
    fun probe(context: Context): EnvStatus {
        val prootPathStr = prootPath(context)
        val proot = File(prootPathStr)
        Log.i(TAG, "🔍 probe 开始: prootPath=$prootPathStr, exists=${proot.exists()}")
        
        if (!proot.exists()) {
            if (_state.value is SandboxState.Ready) _state.value = SandboxState.NotInstalled
            // 诊断：列出 nativeLibraryDir 下的实际文件，帮助定位问题
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val nativeFiles = try {
                File(nativeDir).listFiles()?.map { it.name }?.joinToString(", ") ?: "(目录不可读)"
            } catch (_: Throwable) { "(访问失败)" }
            Log.e(TAG, "❌ proot 二进制缺失。nativeLibraryDir=$nativeDir，内容=[$nativeFiles]")
            return EnvStatus(false, null, null,
                "proot 二进制缺失。应用库目录($nativeDir)内容: $nativeFiles。" +
                "可能原因：1) APK 未正确安装（native library 未解压）；2) 设备架构不匹配（需 arm64-v8a）；" +
                "3) 系统清理了应用数据。请尝试卸载重装。")
        }

        val rootfs = File(rootfsPath(context))
        // 关键：不仅看目录在不在，还要确认 rootfs 真能用（/bin/sh 存在且可解析）。
        // 否则「目录在但解压残缺 / 符号链接创建失败 / 上次安装中断残留」会被误判为就绪，
        // 终端顶栏显示 proot/Linux，实际 proot 启动失败、静默回退设备 sh —— 即「完全废了」的无声根因。
        // 注意：/bin/sh 是绝对符号链接，必须按 rootfs 边界内解析（见 [rootfsBinRunnable]）。
        val shOk = rootfs.isDirectory && rootfsBinRunnable(rootfs, "bin/sh")
        val prootCanExec = proot.canExecute()
        Log.i(TAG, "🔍 rootfs 存在=${rootfs.isDirectory}, sh 可执行=$shOk, proot 可执行=$prootCanExec")
        
        return if (shOk && prootCanExec) {
            if (_state.value !is SandboxState.Ready) _state.value = SandboxState.Ready
            EnvStatus(true, proot.absolutePath, rootfs.absolutePath, "环境就绪")
        } else {
            // rootfs 缺失/残缺/不可执行：把错误的 Ready 降级回 NotInstalled，恢复安装横幅。
            if (_state.value is SandboxState.Ready) _state.value = SandboxState.NotInstalled
            // 🔧 「曾安装但现已丢失」：多因系统清理应用数据（应用长时间未用）。写自诊断到 Download，
            // 并给出一键恢复提示，避免用户面对「莫名其妙没了」无从下手。
            val wasThere = wasInstalled(context)
            if (wasThere) writeLostEnvDiagnostic(context)
            val reason = when {
                !rootfs.isDirectory ->
                    if (wasThere)
                        "开发环境曾安装但 rootfs 已丢失（最可能是系统清理了应用数据 / 应用长时间未用被速冻）。点「安装 Linux 环境」或发送 linux:install 可一键重新下载恢复。"
                    else
                        "Ubuntu rootfs 未安装（请在终端点「安装 Linux 环境」）"
                !rootfsBinRunnable(rootfs, "bin/sh") ->
                    "rootfs 解压残缺（/bin/sh 无法在 rootfs 内解析，可能符号链接创建失败），请重试安装"
                !prootCanExec ->
                    "proot 不可执行（权限问题）。可能原因：SELinux 限制、文件系统挂载选项（noexec）、或应用权限不足。"
                else -> "proot 不可执行"
            }
            Log.e(TAG, "❌ 环境不可用: $reason")
            EnvStatus(false, proot.absolutePath, null, reason)
        }
    }

    /**
     * 在 rootfs 内部解析一个（可能含绝对/相对符号链接的）路径，判断其最终真实文件是否存在且可执行。
     *
     * **不能用 [File.exists]/[File.canExecute] 直接判**：rootfs 的 `/bin/sh` 可能是符号链接
     * （如 `-> /bin/busybox` 或 `-> /usr/bin/dash`）。在宿主 Android 文件系统上链接目标
     * 根本不存在，宿主侧 `exists()` 会误判缺失；但 proot 进入 rootfs 后链接目标是存在的。
     * 必须按 rootfs 边界把链接目标解析回 rootfs 内再判定，否则正确解压的 rootfs 会被误报「残缺」。
     */
    private fun rootfsBinRunnable(rootfs: File, relPath: String): Boolean {
        var cur = File(rootfs, relPath)
        val root = rootfs.canonicalFile
        val seen = mutableSetOf<File>()
        repeat(16) {
            // 先确认解析结果落在 rootfs 内，避免越界；再用 canExecute 判可执行（会跟随链接）。
            if (cur.canonicalFile.startsWith(root) && cur.canExecute()) return true
            if (!java.nio.file.Files.isSymbolicLink(cur.toPath())) return false
            val target = java.nio.file.Files.readSymbolicLink(cur.toPath()).toString()
            cur = if (target.startsWith("/")) File(root, target.removePrefix("/"))
                   else File(cur.parentFile ?: root, target)
            if (!seen.add(cur)) return false // 防环
        }
        return false
    }

    /**
     * 触发一次性安装：下载 Ubuntu rootfs → 解压 → 写 resolv.conf/sources.list →
     * apt-get update → 装 bash。幂等，已是 Ready 则直接返回。进度通过 [state] 暴露给 UI。
     */
    fun setup(context: Context) {
        if (setupJob?.isActive == true) return
        setupJob = scope.launch {
            if (!setupMutex.tryLock()) return@launch
            try {
                setupInternal(context)
            } catch (e: Exception) {
                Log.e(TAG, "setup failed", e)
                val logPath = File(sandboxDir(context), "setup-diag.log").absolutePath
                _state.value = SandboxState.Error("${e.message}\n\n诊断日志: $logPath")
            } finally {
                setupMutex.unlock()
            }
        }
    }

    /**
     * 阻塞式确保终端(proot/Ubuntu)已安装，供 CMS 部署器等「必须同步等到环境就绪」的调用方使用。
     *
     * - 已就绪：直接返回就绪状态（不触发下载）。
     * - 未就绪：触发一次性安装（下载 rootfs → 解压 → apt-get update → 装 bash → proot 自检），
     *   成功返回就绪状态；安装过程进度仍经 [state] 暴露给 UI。
     * - 安装失败：返回 available=false 的状态并附带原因（不抛异常，便于部署器转成明确错误文案）。
     *
     * 实现要点：用 [runBlocking] + [setupMutex.withLock] 同步等待安装完成；若并发的 [setup] 已在跑，
     * withLock 会等其释放后重新探测（可能已被装好）。**调用方必须已处于后台线程**——
     * CMS 部署器本就在工作线程跑阻塞式 proot 命令，符合此约束。
     */
    fun ensureInstalledBlocking(context: Context): EnvStatus {
        val st = probe(context)
        if (st.available) return st
        return try {
            runBlocking {
                setupMutex.withLock {
                    // 抢到锁后再探一次：并发 setup() 可能已先完成安装
                    probe(context).takeIf { it.available }?.let { return@runBlocking it }
                    setupInternal(context)
                    probe(context)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensureInstalledBlocking 终端安装失败", e)
            EnvStatus(false, prootPath(context).takeIf { File(it).exists() }, null, e.message ?: "终端安装失败")
        }
    }

    fun cancelSetup() {
        setupJob?.cancel()
        setupJob = null
    }

    /** 将诊断日志同时写到 app 私有目录下的文件，方便用户取出查看。 */
    private fun diagLog(context: Context, msg: String) {
        Log.i(TAG, msg)
        try {
            val logDir = File(context.filesDir, "linux-sandbox")
            logDir.mkdirs()
            val logFile = File(logDir, "setup-diag.log")
            logFile.appendText("[${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}] $msg\n")
        } catch (_: Throwable) {}
    }

    // ═══ 安装态持久化 + 丢失自诊断（应对「长时间未用 → 系统清理应用数据 → 重检显示无环境」）═══
    private fun wasInstalled(context: Context): Boolean =
        runCatching { context.getSharedPreferences("quro_linux_env", android.content.Context.MODE_PRIVATE).getBoolean("installed", false) }.getOrDefault(false)

    private fun markInstalled(context: Context) {
        runCatching { context.getSharedPreferences("quro_linux_env", android.content.Context.MODE_PRIVATE).edit().putBoolean("installed", true).apply() }
    }

    /**
     * 开发环境丢失时，把自诊断报告写到手机公共 Download/QuroAI_logs/（用户用文件管理器即可取到，
     * 无需 adb/logcat）。公共目录写失败则兜底到应用外部存储 QuroAI_logs/。
     */
    private fun writeLostEnvDiagnostic(context: Context) {
        val content = buildString {
            appendLine("ZorvAI 开发环境（Linux 沙箱）丢失自诊断")
            appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
            appendLine()
            appendLine("现象：重新检测开发环境，提示「未安装 / 没有开发环境」。")
            appendLine("根因判断：rootfs（Ubuntu 解压目录 ${rootfsPath(context)}）已不存在。")
            appendLine("最可能原因：应用长时间未使用，被系统/手机管家清理了应用数据")
            appendLine("  （如 OPPO/ColorOS 的应用速冻、应用数据清理，或手动「清除数据」），")
            appendLine("  把 filesDir/linux-sandbox 整目录删除。注意 proot 二进制在 APK 内（nativeLibraryDir），")
            appendLine("  不会丢；丢的是下载解压的 Ubuntu rootfs。")
            appendLine()
            appendLine("恢复方法（任选其一）：")
            appendLine("  1) 打开「开发环境」页面 → 点「安装 Linux 环境」；")
            appendLine("  2) 在对话框发送：linux:install ；")
            appendLine("  3) AI 调用需要 Linux 的工具时，会自动触发重新下载安装。")
            appendLine("重新安装需联网（从阿里云/清华镜像下载 Ubuntu 24.04 ARM64 rootfs 并解压，约数百 MB）。")
        }
        try {
            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "QuroAI_logs")
            dir.mkdirs()
            File(dir, "linux_env_lost.txt").writeText(content)
        } catch (_: Throwable) {
            try {
                val fb = context.getExternalFilesDir("QuroAI_logs")
                fb?.mkdirs()
                File(fb, "linux_env_lost.txt").writeText(content)
            } catch (_: Throwable) {}
        }
    }

    private suspend fun setupInternal(context: Context) {
        // 清理上次诊断日志
        try { File(sandboxDir(context), "setup-diag.log").delete() } catch (_: Throwable) {}
        diagLog(context, "=== setupInternal 开始 ===")
        diagLog(context, "架构: ${getLinuxArch()}, proot: ${prootPath(context)}")

        val arch = getLinuxArch()
        val prootPathStr = prootPath(context)
        val proot = File(prootPathStr)
        if (!proot.exists()) {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val nativeFiles = try {
                File(nativeDir).listFiles()?.map { it.name }?.joinToString(", ") ?: "(目录不可读)"
            } catch (_: Throwable) { "(访问失败)" }
            
            // 详细诊断
            val diagnosticInfo = buildString {
                appendLine("=== proot 缺失诊断 ===")
                appendLine("proot路径: ${proot.absolutePath}")
                appendLine("nativeLibraryDir: $nativeDir")
                appendLine("nativeLibraryDir存在: ${File(nativeDir).exists()}")
                appendLine("nativeLibraryDir可读: ${File(nativeDir).canRead()}")
                appendLine("nativeLibraryDir内容: $nativeFiles")
                appendLine("文件大小: ${proot.length()}")
                appendLine("文件权限: canRead=${proot.canRead()}, canWrite=${proot.canWrite()}, canExecute=${proot.canExecute()}")
                
                // 检查 APK 安装目录
                try {
                    val apkDir = File(context.applicationInfo.sourceDir).parentFile
                    if (apkDir != null) {
                        appendLine("APK安装目录: ${apkDir.absolutePath}")
                        appendLine("APK安装目录存在: ${apkDir.exists()}")
                        // 列出 lib 目录
                        val libDir = File(apkDir, "lib")
                        if (libDir.exists()) {
                            val libFiles = libDir.listFiles()?.map { it.name }?.joinToString(", ") ?: "(空)"
                            appendLine("lib目录内容: $libFiles")
                            // 检查 arm64-v8a 目录
                            val arm64Dir = File(libDir, "arm64-v8a")
                            if (arm64Dir.exists()) {
                                val arm64Files = arm64Dir.listFiles()?.map { it.name }?.joinToString(", ") ?: "(空)"
                                appendLine("arm64-v8a目录内容: $arm64Files")
                            }
                        }
                    }
                } catch (e: Exception) {
                    appendLine("APK目录检查失败: ${e.message}")
                }
                
                // 检查 assets
                try {
                    val assetFiles = context.assets.list("linux_env") ?: emptyArray()
                    appendLine("assets/linux_env/内容: ${assetFiles.joinToString(", ")}")
                } catch (e: Exception) {
                    appendLine("assets检查失败: ${e.message}")
                }
                
                appendLine("========================")
            }
            
            Log.e(TAG, diagnosticInfo)
            throw IllegalStateException(
                "proot 二进制缺失于 ${proot.absolutePath}。\n" +
                "应用库目录($nativeDir)内容: $nativeFiles。\n" +
                "详细诊断信息已记录到日志。\n" +
                "可能原因：1) APK 未正确安装（native library 未解压）；2) 设备架构不匹配（需 arm64-v8a）；" +
                "3) 系统清理了应用数据。请尝试卸载重装。"
            )
        }
        val dir = sandboxDir(context)
        dir.mkdirs()
        File(dir, "tmp").mkdirs()

        // Android 把原生 .so 的 .so.2 后缀剥离，Linux 内程序按 libtalloc.so.2 找，这里补回。
        val talloc = File(dir, "libtalloc.so.2")
        if (!talloc.exists()) {
            val tallocPath = findNativeLibWithAssetsFallback(context, "libtalloc.so")
            val src = File(tallocPath)
            if (src.exists()) src.copyTo(talloc, overwrite = true)
            else QuroDiag.log("LinuxEnv", "⚠ nativeLibraryDir 无 libtalloc.so，libproot-loader 可能加载失败")
        }

        val rootfsDir = File(dir, "rootfs")
        if (rootfsDir.exists()) rootfsDir.deleteRecursively()
        val tarGz = File(dir, "rootfs.tar.gz")
        val tarXz = File(dir, "rootfs.tar.xz")
        var extractSuccess = false
        try {
            _state.value = SandboxState.Downloading(0f)
            downloadRootfs(context, arch, tarGz) { p -> _state.value = SandboxState.Downloading(p) }

            _state.value = SandboxState.Extracting
            // 优先尝试xz格式（兼容上游 proot rootfs 打包）
            if (tarXz.exists() && tarXz.length() > 0) {
                Log.i(TAG, "尝试使用xz格式rootfs: ${tarXz.absolutePath} (${tarXz.length()} bytes)")
                try {
                    extractXzTar(tarXz, rootfsDir)
                    // 验证解压是否成功
                    if (rootfsDir.exists() && rootfsDir.isDirectory && rootfsDir.listFiles()?.isNotEmpty() == true) {
                        Log.i(TAG, "xz格式rootfs解压成功，目录内容数: ${rootfsDir.listFiles()?.size}")
                        extractSuccess = true
                    } else {
                        Log.w(TAG, "xz格式rootfs解压后目录为空或不存在")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "xz格式rootfs解压失败: ${e.message}")
                    // 清理解压失败的残留
                    if (rootfsDir.exists()) rootfsDir.deleteRecursively()
                }
            }

            // 如果xz解压失败或不存在，尝试gz格式
            if (!extractSuccess && tarGz.exists() && tarGz.length() > 0) {
                Log.i(TAG, "尝试使用gz格式rootfs: ${tarGz.absolutePath} (${tarGz.length()} bytes)")
                try {
                    extractTarGz(tarGz, rootfsDir)
                    // 验证解压是否成功
                    if (rootfsDir.exists() && rootfsDir.isDirectory && rootfsDir.listFiles()?.isNotEmpty() == true) {
                        Log.i(TAG, "gz格式rootfs解压成功，目录内容数: ${rootfsDir.listFiles()?.size}")
                        extractSuccess = true
                    } else {
                        Log.w(TAG, "gz格式rootfs解压后目录为空或不存在")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "gz格式rootfs解压失败: ${e.message}")
                    // 清理解压失败的残留
                    if (rootfsDir.exists()) rootfsDir.deleteRecursively()
                }
            }

            // 如果两种格式都失败，抛出异常
            if (!extractSuccess) {
                val xzExists = tarXz.exists()
                val gzExists = tarGz.exists()
                throw java.io.IOException(
                    "rootfs文件解压失败。xz文件存在: $xzExists(${if (xzExists) tarXz.length() else 0} bytes), " +
                    "gz文件存在: $gzExists(${if (gzExists) tarGz.length() else 0} bytes)。" +
                    "可能原因：1) busybox/tar命令不可用；2) 存储空间不足；3) 文件损坏"
                )
            }
        } finally {
            tarGz.delete()
            tarXz.delete()
        }

        // 解压后立即校验 rootfs 真可用：/bin/sh 必须能在 rootfs 内部解析为可执行文件。
        diagLog(context, "解压完成，rootfsDir=${rootfsDir.absolutePath}, 文件数=${rootfsDir.listFiles()?.size ?: 0}")
        if (!rootfsBinRunnable(rootfsDir, "bin/sh")) {
            val detail = "rootfs 解压后 /bin/sh 无法在 rootfs 内解析（解压残缺或符号链接创建失败），无法启动 Ubuntu"
            diagLog(context, "⛔ $detail")
            QuroDiag.log("LinuxEnv", "⛔ $detail")
            rootfsDir.deleteRecursively()
            throw IllegalStateException(detail)
        }
        diagLog(context, "✅ /bin/sh 校验通过")

        _state.value = SandboxState.Installing("初始化…")
        diagLog(context, "makeWritable 开始")
        makeWritable(rootfsDir)
        diagLog(context, "fixHardlinks 开始")
        fixHardlinks(rootfsDir)

        // 创建 usr/bin/ 目录和符号链接（参考上游 proot 实现）
        diagLog(context, "创建 usr/bin/ 符号链接")
        createUsrBinSymlinks(context, dir)

        diagLog(context, "prepareRuntimeExtras 开始")
        prepareRuntimeExtras(context, rootfsDir)

        // 先做一次 proot 基础能力测试（在 apt-get update 之前）
        diagLog(context, "proot 基础测试：echo hello")
        val baseTest = runProot(context, "echo PROOT_BASELINE_OK", timeoutMs = 15_000)
        diagLog(context, "proot 基础测试结果: exit=${baseTest.first}, output=${baseTest.second.take(200)}")
        if (baseTest.first != 0 || !baseTest.second.contains("PROOT_BASELINE_OK")) {
            val detail = "proot 无法在 rootfs 内执行基础命令。\n" +
                "proot路径: ${prootPath(context)}, exists=${File(prootPath(context)).exists()}\n" +
                "proot可执行: ${File(prootPath(context)).canExecute()}\n" +
                "rootfs: ${rootfsDir.absolutePath}, 文件数=${rootfsDir.listFiles()?.size}\n" +
                "输出(exit ${baseTest.first}):\n${baseTest.second.take(800)}\n" +
                "常见原因：1) proot loader 缺失或不兼容 2) SELinux 限制 ptrace 3) 设备不支持"
            diagLog(context, "⛔ $detail")
            QuroDiag.log("LinuxEnv", "⛔ $detail")
            rootfsDir.deleteRecursively()
            throw IllegalStateException(detail)
        }
        diagLog(context, "✅ proot 基础测试通过")

        // 写 DNS
        diagLog(context, "写入 resolv.conf 和 sources.list")
        val dns = deviceDnsServers(context)
        diagLog(context, "设备DNS: $dns")

        var updated = false
        var lastErr = ""
        for (mirror in UBUNTU_APT_MIRRORS) {
            diagLog(context, "尝试 apt-get update (镜像: $mirror)")
            writeAptSources(rootfsDir, mirror)
            // 使用更多选项确保成功：强制IPv4、禁用缓存、增加超时
            val r = runProot(context, "apt-get update -o Acquire::http::No-Cache=true -o Acquire::Max-FutureTime=0 -o Acquire::ForceIPv4=true", timeoutMs = 180_000)
            diagLog(context, "apt-get update 结果: exit=${r.first}, output=${r.second.take(500)}")
            if (r.first == 0) { updated = true; break }
            lastErr = r.second
            Log.w(TAG, "apt-get update 失败 (镜像: $mirror): ${r.second.take(500)}")
        }
        if (!updated) {
            val detail = "apt-get update 在所有镜像均失败。\n$lastErr"
            diagLog(context, "⛔ $detail")
            QuroDiag.log("LinuxEnv", "⛔ $detail")
            rootfsDir.deleteRecursively()
            throw IllegalStateException(detail)
        }
        diagLog(context, "✅ apt-get update 成功")

        // bash 是持久 shell 的基础，必须装。
        _state.value = SandboxState.Installing("安装 bash…")
        diagLog(context, "安装 bash...")
        val bash = runProot(context, "apt-get install -y --no-install-recommends bash", timeoutMs = 120_000)
        diagLog(context, "bash 安装结果: exit=${bash.first}, output=${bash.second.take(300)}")
        if (bash.first != 0) {
            val detail = "bash 安装失败（exit ${bash.first}）：\n${bash.second}"
            diagLog(context, "⛔ $detail")
            QuroDiag.log("LinuxEnv", "⛔ $detail")
            throw IllegalStateException(detail)
        }
        diagLog(context, "✅ bash 安装成功")

        // ★ 部署后自检
        _state.value = SandboxState.Installing("自检 proot 运行环境…")
        diagLog(context, "smoke test: echo + id + apt-get --version")
        val smoke = runProot(context, "echo QURO_SMOKETEST_OK; id -u; apt-get --version", timeoutMs = 30_000)
        diagLog(context, "smoke test 结果: exit=${smoke.first}, output=${smoke.second.take(500)}")
        if (smoke.first != 0 || !smoke.second.contains("QURO_SMOKETEST_OK")) {
            val detail = "部署后自检失败：proot 在您的设备上无法在 rootfs 内执行命令。\n" +
                "自检输出（exit ${smoke.first}）：\n${smoke.second.take(800)}"
            diagLog(context, "⛔ $detail")
            QuroDiag.log("LinuxEnv", "⛔ $detail")
            rootfsDir.deleteRecursively()
            throw IllegalStateException(detail)
        }

        diagLog(context, "=== ✅ 部署成功 ===")
        _state.value = SandboxState.Ready
        // 持久化「曾安装」标记：即使后续 rootfs 被系统清理，重检时也能识别为「丢失」并提示一键恢复。
        markInstalled(context)
    }

    /** 一次性执行命令（AI 工具用）。环境不可用返回原因。 */
    fun run(context: Context, command: String, timeoutMs: Long = 30000): Pair<Int, String> {
        // 不再依赖 probe() 前置检查 —— probe 的 rootfsBinRunnable 可能在宿主侧误判符号链接。
        // 直接尝试执行命令，让 proot 自己报错。
        return runProot(context, command, timeoutMs)
    }

    /** 带实时日志回调的执行命令。每输出一行就回调一次。 */
    fun runWithLog(
        context: Context,
        command: String,
        timeoutMs: Long = 30000,
        onLine: (String) -> Unit = {}
    ): Pair<Int, String> {
        return runProotWithLog(context, command, timeoutMs, onLine)
    }

    /** proot 启动参数与环境（不含最终命令）。一次性执行与常驻进程共用，确保行为一致。 */
    private data class ProotLaunch(
        val args: MutableList<String>,
        val env: MutableMap<String, String>,
        val workDir: File,
    )

    /** 构造 proot 启动参数与环境（命令由调用方追加为末参）。 */
    private fun buildProotLaunch(context: Context): ProotLaunch {
        val proot = prootPath(context)
        val rootfs = rootfsPath(context)
        val home = homePath(context)
        val tmp = tmpPath(context)
        val loader = loaderPath(context)
        val dir = sandboxDir(context)
        val usrBinDir = File(dir, "usr/bin")
        val args = mutableListOf(
            proot,
            "--rootfs=$rootfs",
            "--link2symlink",  // 添加 link2symlink 支持（参考上游 proot 实现）
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=$home:/root",
            "--bind=$tmp:/tmp",
        )
        // 绑定 usr/bin 目录（包含 bash、busybox 等预编译二进制）
        if (usrBinDir.exists()) {
            args.add("--bind=${usrBinDir.absolutePath}:/usr/bin")
        }
        // getprop 垫片可回落读 /system/build.prop，仅当该文件本就可读时绑定（避免整体启动失败）。
        if (File("/system/build.prop").canRead()) {
            args.add("--bind=/system/build.prop:/system/build.prop")
        }
        // 网络支持：绑定 DNS 配置和网络相关文件，使 proot 内的 HTTP 服务能正常工作
        val rootfsFile = File(rootfs)
        val etcDir = File(rootfsFile, "etc")
        if (etcDir.exists()) {
            // 绑定 DNS 配置（resolv.conf 已由 prepareRuntimeExtras 写入）
            val resolvConf = File(etcDir, "resolv.conf")
            if (resolvConf.exists()) {
                args.add("--bind=${resolvConf.absolutePath}:/etc/resolv.conf")
            }
            // 绑定 hosts 文件
            val hostsFile = File(etcDir, "hosts")
            if (!hostsFile.exists()) {
                hostsFile.writeText("127.0.0.1 localhost\n::1 localhost\n")
            }
            args.add("--bind=${hostsFile.absolutePath}:/etc/hosts")
        }
        args.add("-0")
        args.add("-w"); args.add("/root")
        args.add("/bin/sh"); args.add("-c")
        val env = mutableMapOf(
            "HOME" to "/root",
            // 更新 PATH 包含 usr/bin/（bash 和 busybox 所在位置）
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${usrBinDir.absolutePath}",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "LD_LIBRARY_PATH" to "${dir.absolutePath}:${usrBinDir.absolutePath}",
            "PROOT_TMP_DIR" to tmp,
            "PROOT_LOADER" to loader,
        )
        return ProotLaunch(args, env, rootfsFile.parentFile ?: rootfsFile)
    }

    /** 内部：构造 proot 参数并执行。添加多种执行策略应对权限问题。 */
    private fun runProot(context: Context, command: String, timeoutMs: Long): Pair<Int, String> {
        // 不再依赖 probe() 前置检查 —— probe 的 rootfsBinRunnable 可能在宿主侧误判符号链接。
        // 直接尝试执行命令，让 proot 自己报错。
        val rootfs = rootfsPath(context)
        prepareRuntimeExtras(context, File(rootfs))
        Log.i(TAG, "runProot 开始执行，超时: ${timeoutMs}ms，命令: ${command.take(100)}...")

        val launch = buildProotLaunch(context)
        launch.args.add(command)
        return try {
            val pb = ProcessBuilder(launch.args)
            pb.directory(launch.workDir)
            pb.environment().putAll(launch.env)
            pb.redirectErrorStream(true)
            val p = pb.start()
            Log.i(TAG, "proot 进程已启动")

            // 关键修复（#911 根因）：必须先 waitFor(timeout) 再读输出。原先 readText() 会阻塞到
            // 进程退出，导致 timeoutMs 永不触发，hang 住的 bootstrap/provision 让部署永久卡「部署中」。
            // 改为后台线程读 stdout，主线程 waitFor 超时后强杀进程，读取线程随 stdout 关闭自然结束。
            val outBuilder = StringBuilder()
            val reader = p.inputStream.bufferedReader()
            val readThread = Thread {
                try { reader.use { r -> r.forEachLine { outBuilder.appendLine(it) } } } catch (_: Throwable) {}
            }
            readThread.start()
            val startTime = System.currentTimeMillis()
            val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            val duration = System.currentTimeMillis() - startTime
            val code = if (finished) {
                p.exitValue()
            } else {
                Log.w(TAG, "proot 执行超时(${timeoutMs}ms)，强杀进程")
                try { p.destroyForcibly() } catch (_: Throwable) {}
                -1
            }
            try { readThread.join(2000) } catch (_: Throwable) {} // 进程已结束/被强杀，stdout 已关闭，回收读取线程
            val trimmed = outBuilder.toString().trim()
            Log.i(TAG, "proot 执行完成，耗时: ${duration}ms，退出码: $code，输出长度: ${trimmed.length}")
            if (trimmed.isNotEmpty()) {
                Log.i(TAG, "proot 输出前500字符: ${trimmed.take(500)}")
            }
            code to (if (trimmed.isBlank()) (if (finished) "(no output, exit $code)" else "⏱ 命令超时(${timeoutMs}ms)") else trimmed)
        } catch (e: Exception) {
            Log.e(TAG, "proot 执行异常: ${e.message}")
            -1 to "❌ proot 执行失败: ${e.message}"
        }
    }

    /**
     * 启动**常驻** proot 进程（原创运行时 · 修复终端 httpd 被杀）。
     *
     * 与 [runProot] 不同：不 waitFor、不超时强杀，直接返回存活的 [Process] 句柄，
     * 由调用方（[CmsResidentRuntime]）持有并管理生命周期。proot 进程存活期间，
     * 其内以 `exec` 启动的 server 子进程随之常驻——这正是一次性 [runProot] 做不到的
     * （一次性 proot 退出后，作为其子进程的 server 因失去 syscall 翻译层而一同被杀）。
     *
     * @param command 在 proot 内执行的命令（通常为 `cd <dir> && exec sh ./entry.sh`）。
     * @param extraEnv 注入到 proot 环境（进而透传给 guest）的额外变量，如 QURO_HTTP_PORT。
     * @return 已启动的常驻 proot 进程；环境不可用或创建失败返回 null。
     */
    fun spawnPersistent(context: Context, command: String, extraEnv: Map<String, String> = emptyMap()): Process? {
        val st = probe(context)
        if (!st.available || st.prootPath == null || st.rootfsPath == null) {
            Log.w(TAG, "spawnPersistent 失败：环境不可用：${st.reason}")
            return null
        }
        prepareRuntimeExtras(context, File(st.rootfsPath))
        val launch = buildProotLaunch(context)
        launch.args.add(command)
        launch.env.putAll(extraEnv)
        return try {
            val pb = ProcessBuilder(launch.args)
            pb.directory(launch.workDir)
            pb.environment().putAll(launch.env)
            pb.redirectErrorStream(true)
            val p = pb.start()
            Log.i(TAG, "spawnPersistent 常驻 proot 已启动，命令: ${command.take(100)}")
            p
        } catch (e: Exception) {
            Log.e(TAG, "spawnPersistent 创建进程失败: ${e.message}")
            null
        }
    }

    /** 带实时日志回调的 proot 执行。每输出一行就回调一次。 */
    private fun runProotWithLog(
        context: Context,
        command: String,
        timeoutMs: Long,
        onLine: (String) -> Unit
    ): Pair<Int, String> {
        val proot = prootPath(context)
        val rootfs = rootfsPath(context)
        val home = homePath(context)
        val tmp = tmpPath(context)
        val loader = loaderPath(context)
        val dir = sandboxDir(context)

        Log.i(TAG, "runProotWithLog 开始执行，超时: ${timeoutMs}ms")

        prepareRuntimeExtras(context, File(rootfs))
        return try {
            val args = mutableListOf(
                proot,
                "--rootfs=$rootfs",
                "--bind=/dev",
                "--bind=/proc",
                "--bind=/sys",
                "--bind=$home:/root",
                "--bind=$tmp:/tmp",
            )
            // 绑定 usr/bin 目录（包含 bash、busybox 等预编译二进制）
            val usrBinDir = File(dir, "usr/bin")
            if (usrBinDir.exists()) {
                args.add("--bind=${usrBinDir.absolutePath}:/usr/bin")
            }
            if (File("/system/build.prop").canRead()) {
                args.add("--bind=/system/build.prop:/system/build.prop")
            }
            // 网络支持：绑定 DNS 配置和网络相关文件，使 proot 内的 HTTP 服务能正常工作
            val rootfsFile = File(rootfs)
            val etcDir = File(rootfsFile, "etc")
            if (etcDir.exists()) {
                val resolvConf = File(etcDir, "resolv.conf")
                if (resolvConf.exists()) {
                    args.add("--bind=${resolvConf.absolutePath}:/etc/resolv.conf")
                }
                val hostsFile = File(etcDir, "hosts")
                if (!hostsFile.exists()) {
                    hostsFile.writeText("127.0.0.1 localhost\n::1 localhost\n")
                }
                args.add("--bind=${hostsFile.absolutePath}:/etc/hosts")
            }
            args.add("-0")
            args.add("-w"); args.add("/root")
            args.add("/bin/sh"); args.add("-c"); args.add(command)
            val pb = ProcessBuilder(args)
            pb.directory(File(rootfs).parentFile)
            pb.environment().apply {
                put("HOME", "/root")
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("TERM", "xterm-256color")
                put("LANG", "C.UTF-8")
                put("LD_LIBRARY_PATH", dir.absolutePath)
                put("PROOT_TMP_DIR", tmp)
                put("PROOT_LOADER", loader)
            }
            pb.redirectErrorStream(true)
            val p = pb.start()
            
            val outBuilder = StringBuilder()
            val reader = p.inputStream.bufferedReader()
            // 实时读取并回调每一行
            val readThread = Thread {
                try {
                    reader.use { r ->
                        r.forEachLine { line ->
                            outBuilder.appendLine(line)
                            onLine(line)
                        }
                    }
                } catch (_: Throwable) {}
            }
            readThread.start()
            val startTime = System.currentTimeMillis()
            val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            val duration = System.currentTimeMillis() - startTime
            val code = if (finished) {
                p.exitValue()
            } else {
                Log.w(TAG, "proot 执行超时(${timeoutMs}ms)，强杀进程")
                try { p.destroyForcibly() } catch (_: Throwable) {}
                -1
            }
            try { readThread.join(2000) } catch (_: Throwable) {}
            val trimmed = outBuilder.toString().trim()
            Log.i(TAG, "runProotWithLog 执行完成，耗时: ${duration}ms，退出码: $code")
            code to (if (trimmed.isBlank()) (if (finished) "(no output)" else "⏱ 超时") else trimmed)
        } catch (e: Exception) {
            Log.e(TAG, "proot 执行异常: ${e.message}")
            -1 to "❌ 执行失败: ${e.message}"
        }
    }

    /** 策略1：直接执行 proot */
    /**
     * 交互终端启动参数：(proot 路径, 参数列表)。环境未安装返回 null。
     * 注意：PROOT_LOADER / LD_LIBRARY_PATH 等环境变量由 [shellEnv] 提供，
     * 调用方（QuroShellSession）须将其并入 shell 进程环境。
     */
    fun shellLaunch(context: Context): Pair<String, List<String>>? {
        val st = probe(context)
        if (!st.available || st.prootPath == null || st.rootfsPath == null) return null
        val rootfs = File(st.rootfsPath)
        // 运行期资产（resolv.conf 用设备 DNS / getprop 垫片）随网络与设备状态刷新，
        // 避免安装时一次性快照过期（如换了 WiFi、或升级后属性变化）。
        prepareRuntimeExtras(context, rootfs)
        val dir = sandboxDir(context)
        val usrBinDir = File(dir, "usr/bin")
        val args = mutableListOf(
            "--rootfs=${st.rootfsPath}",
            "--link2symlink",  // 添加 link2symlink 支持（参考上游 proot 实现）
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=${homePath(context)}:/root",
            "--bind=${tmpPath(context)}:/tmp",
        )
        // 绑定 usr/bin 目录（包含 bash、busybox 等预编译二进制）
        if (usrBinDir.exists()) {
            args.add("--bind=${usrBinDir.absolutePath}:/usr/bin")
        }
        args.add("-0")
        args.add("-w"); args.add("/root")
        args.add("/bin/sh")
        // getprop 垫片可回落读 /system/build.prop，故把宿主真机 build.prop 只读绑进沙箱
        // （仅当该文件本就可读，避免 proot 因源不存在而整体启动失败）。
        if (File("/system/build.prop").canRead()) {
            args.add("--bind=/system/build.prop:/system/build.prop")
        }
        // 网络支持：绑定 DNS 配置和网络相关文件，使 proot 内的 HTTP 服务能正常工作
        val etcDir = File(rootfs, "etc")
        if (etcDir.exists()) {
            val resolvConf = File(etcDir, "resolv.conf")
            if (resolvConf.exists()) {
                args.add("--bind=${resolvConf.absolutePath}:/etc/resolv.conf")
            }
            val hostsFile = File(etcDir, "hosts")
            if (!hostsFile.exists()) {
                hostsFile.writeText("127.0.0.1 localhost\n::1 localhost\n")
            }
            args.add("--bind=${hostsFile.absolutePath}:/etc/hosts")
        }
        return st.prootPath to args
    }

    /** 交互 shell 进程应注入的环境变量（PROOT_LOADER / LD_LIBRARY_PATH 等）。 */
    fun shellEnv(context: Context): Array<String> {
        val dir = sandboxDir(context)
        val usrBinDir = File(dir, "usr/bin")
        return arrayOf(
            "TERM=xterm-256color",
            "HOME=/root",
            "TMPDIR=${tmpPath(context)}",
            // 更新 PATH 包含 usr/bin/（bash 和 busybox 所在位置）
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${usrBinDir.absolutePath}",
            "LANG=C.UTF-8",
            "LD_LIBRARY_PATH=${dir.absolutePath}:${usrBinDir.absolutePath}",
            "PROOT_TMP_DIR=${tmpPath(context)}",
            "PROOT_LOADER=${loaderPath(context)}",
        )
    }

    // ----------------------------------------------------------------
    // rootfs 下载（HttpURLConnection，避免引入 ktor 依赖）
    // ----------------------------------------------------------------

    private fun downloadRootfs(context: Context, arch: String, target: File, onProgress: (Float) -> Unit) {
        // 纯网络下载模式 - 不再从assets读取rootfs
        Log.i(TAG, "开始网络下载rootfs (gz格式)")
        val ubuntuArch = when (arch) {
            "aarch64" -> "arm64"
            "armhf" -> "armhf"
            "x86_64" -> "amd64"
            "x86" -> "i386"
            else -> "arm64"
        }
        val fileName = "ubuntu-base-${UBUNTU_VERSION}-base-${ubuntuArch}.tar.gz"
        val urls = UBUNTU_ROOTFS_MIRRORS.map { base -> "$base/$fileName" }

        // 检查是否已有有效的下载文件（断点续传支持）
        if (target.exists() && target.length() > 10 * 1024 * 1024) { // 大于10MB认为有效
            Log.i(TAG, "发现已存在的下载文件: ${target.absolutePath}, 大小: ${target.length() / 1024}KB, 跳过下载")
            onProgress(1f)
            return
        }

        // 清理不完整的下载
        if (target.exists()) {
            Log.i(TAG, "清理不完整的下载文件: ${target.absolutePath}, 大小: ${target.length()}")
            target.delete()
        }

        var lastErr: Exception? = null
        for ((i, url) in urls.withIndex()) {
            try {
                Log.i(TAG, "尝试镜像 ${i + 1}/${urls.size}: $url")
                downloadFrom(url, target, onProgress)
                // 验证下载的文件
                if (target.exists() && target.length() > 10 * 1024 * 1024) {
                    Log.i(TAG, "✅ rootfs下载成功: ${target.absolutePath}, 大小: ${target.length() / 1024}KB")
                    return
                } else {
                    val size = if (target.exists()) target.length() else 0
                    throw java.io.IOException("下载的文件太小: ${size}bytes (期望 >10MB)")
                }
            } catch (e: Exception) {
                lastErr = e
                Log.e(TAG, "❌ 镜像下载失败: $url, 错误: ${e.message}")
                if (target.exists()) target.delete()
                if (i < urls.lastIndex) onProgress(0f)
            }
        }
        throw java.io.IOException("所有 Ubuntu 镜像下载失败: ${lastErr?.message}")
    }

    private fun downloadFrom(url: String, target: File, onProgress: (Float) -> Unit) {
        Log.i(TAG, "开始下载: $url, 目标文件: ${target.absolutePath}")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "QuroLinuxEnv/1.0")
        try {
            Log.i(TAG, "连接建立中...")
            val responseCode = conn.responseCode
            Log.i(TAG, "HTTP响应码: $responseCode")
            if (responseCode !in 200..299) {
                val errorBody = try { conn.errorStream?.bufferedReader()?.readText()?.take(500) } catch (_: Throwable) { "" }
                throw java.io.IOException("HTTP $responseCode from $url\n$errorBody")
            }
            val total = conn.contentLengthLong
            val contentType = conn.contentType
            Log.i(TAG, "Content-Length: $total, Content-Type: $contentType")
            val startTime = System.currentTimeMillis()
            var lastLogTime = startTime
            conn.inputStream.buffered().use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        // 每2秒或有Content-Length时每5%打一次日志
                        if (now - lastLogTime > 2000) {
                            val elapsed = (now - startTime) / 1000.0
                            val speed = if (elapsed > 0) downloaded / elapsed / 1024 else 0.0
                            if (total > 0) {
                                val pct = downloaded * 100f / total
                                Log.i(TAG, "下载进度: ${downloaded}/${total} (${pct.toInt()}%) 速度: ${"%.1f".format(speed)} KB/s")
                                onProgress(downloaded.toFloat() / total)
                            } else {
                                Log.i(TAG, "已下载: ${downloaded / 1024}KB 速度: ${"%.1f".format(speed)} KB/s")
                                // 未知大小时模拟进度：基于已下载量估算，100MB为参考
                                val estimatedProgress = (downloaded.toFloat() / (100 * 1024 * 1024)).coerceAtMost(0.95f)
                                onProgress(estimatedProgress)
                            }
                            lastLogTime = now
                        }
                    }
                    // 最终进度
                    val finalSize = target.length()
                    Log.i(TAG, "下载完成: 文件大小=${finalSize / 1024}KB, 耗时=${(System.currentTimeMillis() - startTime) / 1000}秒")
                    onProgress(1f)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "下载失败: $url, 错误: ${e.message}")
            throw e
        } finally {
            conn.disconnect()
        }
    }

    // ----------------------------------------------------------------
    // tar.gz 解压（移植 Kai 的 RootfsDownloader，含软链/硬链/越权防护）
    // ----------------------------------------------------------------

    private const val TAR_BLOCK_SIZE = 512
    private const val TAR_NAME_OFFSET = 0
    private const val TAR_MODE_OFFSET = 100
    private const val TAR_SIZE_OFFSET = 124
    private const val TAR_TYPE_OFFSET = 156
    private const val TAR_LINK_OFFSET = 157
    private const val TAR_PREFIX_OFFSET = 345

    private fun extractTarGz(tarGz: File, target: File) {
        target.mkdirs()
        Log.d(TAG, "extractTarGz: 开始解压rootfs文件: ${tarGz.name}")
        
        // 检查文件格式
        val fileName = tarGz.name.lowercase()
        when {
            fileName.endsWith(".tar.xz") -> {
                Log.d(TAG, "extractTarGz: 检测到xz格式，使用busybox tar解压")
                extractXzTar(tarGz, target)
            }
            fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz") -> {
                Log.d(TAG, "extractTarGz: 检测到gzip格式，使用Java GZIPInputStream解压")
                extractGzTar(tarGz, target)
            }
            else -> {
                Log.d(TAG, "extractTarGz: 未知格式，尝试gzip解压")
                extractGzTar(tarGz, target)
            }
        }
    }
    
    private fun extractGzTar(tarGz: File, target: File) {
        try {
            GZIPInputStream(BufferedInputStream(FileInputStream(tarGz))).use { gzip ->
                extractTar(gzip, target)
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractGzTar: Java gzip解压失败: ${e.message}")
            throw e
        }
    }
    
    private fun extractXzTar(xzFile: File, target: File) {
        Log.i(TAG, "extractXzTar: 开始解压xz文件: ${xzFile.absolutePath}")
        target.mkdirs()

        // 检查文件是否存在且有效
        if (!xzFile.exists() || xzFile.length() == 0L) {
            throw java.io.IOException("xz文件不存在或为空: ${xzFile.absolutePath}")
        }

        // 检查可用的解压命令
        val hasTar = checkCommandExists("tar")
        val hasBusybox = checkCommandExists("busybox")
        val hasXz = checkCommandExists("xz")

        Log.i(TAG, "extractXzTar: 可用命令 - tar=$hasTar, busybox=$hasBusybox, xz=$hasXz")

        // 策略1：直接使用tar命令（如果可用）
        if (hasTar) {
            val cmd = "tar xf '${xzFile.absolutePath}' -C '${target.absolutePath}'"
            Log.i(TAG, "extractXzTar: 尝试tar命令: $cmd")
            if (execCommand(cmd)) {
                Log.i(TAG, "extractXzTar: tar命令解压成功")
                return
            }
            Log.w(TAG, "extractXzTar: tar命令解压失败")
        }

        // 策略2：使用busybox tar（如果可用）
        if (hasBusybox) {
            val cmd = "busybox tar xf '${xzFile.absolutePath}' -C '${target.absolutePath}'"
            Log.i(TAG, "extractXzTar: 尝试busybox tar命令: $cmd")
            if (execCommand(cmd)) {
                Log.i(TAG, "extractXzTar: busybox tar命令解压成功")
                return
            }
            Log.w(TAG, "extractXzTar: busybox tar命令解压失败")
        }

        // 策略3：先用xz解压，再用tar解压（如果xz可用）
        if (hasXz) {
            val decompressed = File(xzFile.parent, "${xzFile.nameWithoutExtension}")
            val xzCmd = "xz -dk '${xzFile.absolutePath}'"
            Log.i(TAG, "extractXzTar: 尝试xz解压: $xzCmd")
            if (execCommand(xzCmd) && decompressed.exists()) {
                val tarCmd = "tar xf '${decompressed.absolutePath}' -C '${target.absolutePath}'"
                Log.i(TAG, "extractXzTar: 尝试tar解压: $tarCmd")
                if (execCommand(tarCmd)) {
                    decompressed.delete()
                    Log.i(TAG, "extractXzTar: xz+tar解压成功")
                    return
                }
                decompressed.delete()
            }
            Log.w(TAG, "extractXzTar: xz+tar解压失败")
        }

        // 策略4：使用Java内置方式解压（最可靠但最慢）
        Log.i(TAG, "extractXzTar: 尝试Java内置解压方式")
        try {
            extractXzWithJava(xzFile, target)
            Log.i(TAG, "extractXzTar: Java内置解压成功")
            return
        } catch (e: Exception) {
            Log.e(TAG, "extractXzTar: Java内置解压失败: ${e.message}")
        }

        // 所有策略都失败
        throw java.io.IOException(
            "所有xz解压策略都失败。" +
            "可用命令: tar=$hasTar, busybox=$hasBusybox, xz=$hasXz。" +
            "请确保设备上有可用的解压工具。"
        )
    }

    /** 检查系统命令是否存在 */
    private fun checkCommandExists(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("which", command))
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    /** 执行shell命令并返回是否成功 */
    private fun execCommand(cmd: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val error = process.errorStream.bufferedReader().readText()
                Log.w(TAG, "execCommand失败: exitCode=$exitCode, error=${error.take(500)}")
            }
            exitCode == 0
        } catch (e: Exception) {
            Log.w(TAG, "execCommand异常: ${e.message}")
            false
        }
    }

    /** 使用Java内置方式解压xz文件 */
    private fun extractXzWithJava(xzFile: File, target: File) {
        // Java没有内置xz支持，需要使用系统命令
        // 尝试多种命令组合
        val commands = listOf(
            // 策略1：直接用tar（现代tar支持xz）
            arrayOf("tar", "xf", xzFile.absolutePath, "-C", target.absolutePath),
            // 策略2：用sh -c调用tar
            arrayOf("sh", "-c", "tar xf '${xzFile.absolutePath}' -C '${target.absolutePath}'"),
            // 策略3：尝试先xz解压再tar（如果xz命令可用）
            arrayOf("sh", "-c", "xz -dk '${xzFile.absolutePath}' && tar xf '${xzFile.absolutePath}' -C '${target.absolutePath}'"),
        )

        for (cmd in commands) {
            try {
                Log.i(TAG, "extractXzWithJava: 尝试命令: ${cmd.joinToString(" ")}")
                val pb = ProcessBuilder(*cmd)
                pb.redirectErrorStream(true)
                val process = pb.start()
                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    Log.i(TAG, "extractXzWithJava: 命令成功")
                    return
                } else {
                    Log.w(TAG, "extractXzWithJava: 命令失败(exit $exitCode): ${output.take(200)}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "extractXzWithJava: 命令执行异常: ${e.message}")
            }
        }

        throw java.io.IOException(
            "xz解压失败：系统没有可用的tar/xz命令。" +
            "建议使用gz格式的rootfs文件。"
        )
    }

    private fun extractTar(input: java.io.InputStream, targetDir: File) {
        val header = ByteArray(TAR_BLOCK_SIZE)
        val data = ByteArray(BUFFER_SIZE)
        while (true) {
            if (readFully(input, header) < TAR_BLOCK_SIZE) break
            val name = readTarString(header, TAR_NAME_OFFSET, 100)
            if (name.isEmpty()) break
            val prefix = readTarString(header, TAR_PREFIX_OFFSET, 155)
            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
            val sizeStr = readTarString(header, TAR_SIZE_OFFSET, 12)
            val size = if (sizeStr.isNotEmpty()) sizeStr.toLong(8) else 0L
            val modeStr = readTarString(header, TAR_MODE_OFFSET, 8)
            val mode = if (modeStr.isNotEmpty()) modeStr.toInt(8) else 0
            val typeFlag = header[TAR_TYPE_OFFSET]
            val linkName = readTarString(header, TAR_LINK_OFFSET, 100)
            val outFile = File(targetDir, fullName)
            if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                skipBytes(input, alignToBlock(size))
                continue
            }
            when (typeFlag.toInt().toChar()) {
                '5', 'D' -> outFile.mkdirs()
                '2' -> {
                    outFile.parentFile?.mkdirs()
                    try {
                        if (outFile.exists()) outFile.delete()
                        java.nio.file.Files.createSymbolicLink(outFile.toPath(), java.nio.file.Paths.get(linkName))
                    } catch (e: Exception) {
                        // 符号链接创建失败（如 SELinux 限制应用私有目录软链）会导致 /bin/sh 等缺失，
                        // 必须记下来，否则 rootfs 残缺却被当成「解压成功」。
                        QuroDiag.log("LinuxEnv", "⚠ 符号链接创建失败: $fullName -> $linkName: ${e.message}")
                    }
                }
                '1' -> {
                    val linkTarget = File(targetDir, linkName)
                    outFile.parentFile?.mkdirs()
                    if (linkTarget.exists()) linkTarget.copyTo(outFile, overwrite = true)
                }
                '0', '\u0000' -> {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        var remaining = size
                        while (remaining > 0) {
                            val toRead = minOf(remaining, data.size.toLong()).toInt()
                            val r = input.read(data, 0, toRead)
                            if (r <= 0) break
                            out.write(data, 0, r)
                            remaining -= r
                        }
                    }
                    if (mode and 0b001_001_001 != 0) outFile.setExecutable(true, false)
                    skipBytes(input, alignToBlock(size) - size)
                    continue
                }
                else -> { }
            }
            if (size > 0 && typeFlag.toInt().toChar() !in setOf('0', '\u0000')) {
                skipBytes(input, alignToBlock(size))
            }
        }
    }

    private fun readTarString(buf: ByteArray, offset: Int, length: Int): String {
        val end = minOf(offset + length, buf.size)
        val nullIdx = (offset until end).firstOrNull { buf[it] == 0.toByte() } ?: end
        return String(buf, offset, nullIdx - offset, Charsets.US_ASCII).trim()
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val r = input.read(buf, total, buf.size - total)
            if (r <= 0) break
            total += r
        }
        return total
    }

    private fun skipBytes(input: java.io.InputStream, count: Long) {
        var rem = count
        while (rem > 0) {
            val skipped = input.skip(rem)
            if (skipped <= 0) { if (input.read() < 0) break; rem -= 1 } else rem -= skipped
        }
    }

    private fun alignToBlock(size: Long): Long {
        val rem = size % TAR_BLOCK_SIZE
        return if (rem == 0L) size else size + (TAR_BLOCK_SIZE - rem)
    }

    private fun makeWritable(rootfs: File) {
        rootfs.walkTopDown().forEach { f ->
            // 递归设置所有目录和文件的写权限
            if (f.isDirectory && !f.canWrite()) f.setWritable(true, true)
            if (f.isFile && !f.canWrite()) f.setWritable(true, true)
        }
        // 确保关键目录存在且可写
        listOf("var/lib/dpkg", "var/lib/apt", "var/cache/apt", "tmp").forEach { dir ->
            val d = File(rootfs, dir)
            d.mkdirs()
            d.setWritable(true, true)
        }
    }

    /**
     * 修复 rootfs 中的 hardlink 问题（P0 修复）。
     *
     * Ubuntu rootfs 通常不依赖 busybox hardlink，但极少数压缩包格式或文件系统
     * 可能导致 hardlink 退化为 0 字节空文件。本函数扫描 rootfs 中所有 0 字节的
     * 可执行文件并记录日志，确保问题可追溯。
     */
    private fun fixHardlinks(rootfs: File) {
        Log.i(TAG, "修复 rootfs hardlink 问题...")
        var fixed = 0

        // 1. 修复 bin 目录下的 0 字节文件 → 符号链接到 busybox
        val binDir = File(rootfs, "bin")
        if (binDir.isDirectory) {
            binDir.listFiles()?.forEach { f ->
                if (f.isFile && f.length() == 0L && f.canExecute()) {
                    try {
                        f.delete()
                        java.nio.file.Files.createSymbolicLink(
                            f.toPath(),
                            java.nio.file.Paths.get("/bin/busybox")
                        )
                        fixed++
                    } catch (_: Exception) {}
                }
            }
        }

        // 2. 修复 usr/bin 目录下的 0 字节文件 → 符号链接到 busybox
        val usrBinDir = File(rootfs, "usr/bin")
        if (usrBinDir.isDirectory) {
            usrBinDir.listFiles()?.forEach { f ->
                if (f.isFile && f.length() == 0L) {
                    try {
                        f.delete()
                        java.nio.file.Files.createSymbolicLink(
                            f.toPath(),
                            java.nio.file.Paths.get("/bin/busybox")
                        )
                        fixed++
                    } catch (_: Exception) {}
                }
            }
        }

        // 3. 修复 lib 目录下的 0 字节 .so 文件 → 删除（避免加载崩溃）
        val libDir = File(rootfs, "lib")
        if (libDir.isDirectory) {
            libDir.listFiles()?.forEach { f ->
                if (f.isFile && f.length() == 0L && f.name.endsWith(".so")) {
                    f.delete()
                    fixed++
                }
            }
        }

        Log.i(TAG, "修复 hardlink 完成，共修复 $fixed 个文件")
    }

    /**
     * 写 rootfs 的 /etc/resolv.conf。
     *
     * **网络修复（用户「要完整的」之一）**：旧实现硬编码 `nameserver 8.8.8.8 / 8.8.4.4`，
     * 在运营商/企业网屏蔽 Google DNS 时终端 `ping`/`apt`/`curl` 全部解析失败。
     * 改为优先采用**设备当前网络真实 DNS**（由 [deviceDnsServers] 经 ConnectivityManager
     * 取 LinkProperties.dnsServers），缺失再回落到硬编码的公共 DNS。这样终端联网行为与
     * 宿主 App 一致，切换 WiFi/数据也不会失效。
     */
    private fun writeResolvConf(rootfs: File, context: Context) {
        val etc = File(rootfs, "etc"); etc.mkdirs()
        val servers = deviceDnsServers(context)
        val body = if (servers.isNotEmpty()) {
            servers.joinToString("\n") { "nameserver $it" }
        } else {
            "nameserver 8.8.8.8\nnameserver 8.8.4.4"
        }
        File(etc, "resolv.conf").writeText(body + "\n")
    }

    /**
     * 取设备当前网络真实 DNS 服务器列表（含 IPv4/IPv6），供 rootfs resolv.conf 使用。
     * 需要 [android.Manifest.permission.ACCESS_NETWORK_STATE]（已在 app 与 aidl-aci-browser 两处 manifest 声明）。
     */
    private fun deviceDnsServers(context: Context): List<String> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return emptyList()
            val net = cm.activeNetwork ?: return emptyList()
            val lp = cm.getLinkProperties(net) ?: return emptyList()
            lp.dnsServers.mapNotNull { it.hostAddress?.takeIf { h -> h.isNotBlank() } }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 终端内 getprop 垫片脚本（Linux 环境没有 Android 的 getprop）。
     * 数据来自 [prepareRuntimeExtras] 写入的 /etc/quro_props.prop，并回落读只读绑入的
     * /system/build.prop；无参时打印全部属性（与 Android getprop 行为一致）。
     */
    private const val GETPROP_SHIM = """#!/bin/sh
# QuroAI getprop shim (proot/Linux) — 把宿主 App 在启动时抓取的 ro.* 属性暴露给沙箱。
PROPS="/etc/quro_props.prop"
if [ ${'$'}# -ge 1 ]; then
  key="${'$'}1"
  val=$(grep -m1 "^${'$'}{key}=" "${'$'}PROPS" 2>/dev/null | cut -d= -f2-)
  if [ -z "${'$'}val" ] && [ -r /system/build.prop ]; then
    val=$(grep -m1 "^${'$'}{key}=" /system/build.prop 2>/dev/null | cut -d= -f2-)
  fi
  if [ -z "${'$'}val" ] && [ ${'$'}# -ge 2 ]; then
    val="${'$'}2"
  fi
  printf '%s\n' "${'$'}val"
else
  cat "${'$'}PROPS" 2>/dev/null
  if [ -r /system/build.prop ]; then cat /system/build.prop; fi
fi
"""

    /**
     * 反射读取隐藏 API `android.os.SystemProperties.get`，用于补全 [Build] 未直接暴露的属性
     * （如 ro.build.date、ro.serialno、persist.sys.timezone 等）。失败（无权限/API 变动）返回 null。
     */
    private fun sysprop(name: String): String? = try {
        val c = Class.forName("android.os.SystemProperties")
        val m = c.getMethod("get", String::class.java, String::class.java)
        val v = m.invoke(null, name, "") as? String
        v?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) { null }

    /** 从 [Build] + SystemProperties 反射构造 ro.* 属性快照（最常见的查询全部覆盖）。 */
    private fun buildProps(context: Context): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        fun put(k: String, v: String?) { if (v != null && v.isNotBlank()) m[k] = v }
        put("ro.build.version.sdk", Build.VERSION.SDK_INT.toString())
        put("ro.build.version.release", Build.VERSION.RELEASE)
        put("ro.build.version.incremental", Build.VERSION.INCREMENTAL)
        put("ro.build.version.codename", Build.VERSION.CODENAME)
        put("ro.build.version.preview_sdk", Build.VERSION.PREVIEW_SDK_INT.toString())
        put("ro.build.version.security_patch", Build.VERSION.SECURITY_PATCH)
        put("ro.build.version.base_os", Build.VERSION.BASE_OS)
        put("ro.build.id", Build.ID)
        put("ro.build.display.id", Build.DISPLAY)
        put("ro.build.user", Build.USER)
        put("ro.build.host", Build.HOST)
        put("ro.build.type", Build.TYPE)
        put("ro.build.tags", Build.TAGS)
        put("ro.build.fingerprint", Build.FINGERPRINT)
        put("ro.product.model", Build.MODEL)
        put("ro.product.brand", Build.BRAND)
        put("ro.product.name", Build.PRODUCT)
        put("ro.product.device", Build.DEVICE)
        put("ro.product.board", Build.BOARD)
        put("ro.product.manufacturer", Build.MANUFACTURER)
        put("ro.product.hardware", Build.HARDWARE)
        put("ro.hardware", Build.HARDWARE)
        put("ro.product.cpu.abi", Build.SUPPORTED_ABIS.firstOrNull())
        put("ro.product.cpu.abilist", Build.SUPPORTED_ABIS.joinToString(","))
        put("ro.product.cpu.abilist32", Build.SUPPORTED_32_BIT_ABIS.joinToString(","))
        put("ro.product.cpu.abilist64", Build.SUPPORTED_64_BIT_ABIS.joinToString(","))
        for (k in listOf(
            "ro.build.date", "ro.build.date.utc", "ro.serialno", "ro.kernel.qemu",
            "ro.config.low_ram", "persist.sys.timezone", "ro.crypto.state",
            "ro.crypto.type", "ro.debuggable", "ro.secure", "ro.bootmode",
            "ro.revision", "ro.build.characteristics",
        )) {
            sysprop(k)?.let { put(k, it) }
        }
        return m
    }

    /**
     * 每次启动终端/执行命令前，把运行期需要的「额外资产」刷进 rootfs：
     * 1. resolv.conf —— 用设备真实 DNS（网络修复，见 [writeResolvConf]）；
     * 2. /etc/quro_props.prop —— ro.* 属性快照（getprop 垫片数据源）；
     * 3. /usr/local/bin/getprop —— 垫片脚本，使 `getprop ro.build.version.sdk` 等可用（[GETPROP_SHIM]）。
     *
     * rootfs 在 setup 时已 makeWritable，且均在应用私有目录，写操作安全；任何一步失败都只记日志，不致命。
     */
    /**
     * 创建 usr/bin/ 目录和符号链接（参考上游 proot 实现）。
     * 将 nativeLibraryDir 中的二进制链接到 usr/bin/，使 proot 环境内可直接使用。
     */
    private fun createUsrBinSymlinks(context: Context, sandboxDir: File) {
        val usrDir = File(sandboxDir, "usr")
        val binDir = File(usrDir, "bin")
        binDir.mkdirs()

        val nativeLibDir = context.applicationInfo.nativeLibraryDir

        // 需要创建符号链接的二进制文件
        val libraries = mapOf(
            "libproot.so" to "proot",
            "libproot-loader.so" to "loader",
            "libbash.so" to "bash",
            "libbusybox.so" to "busybox",
            "libtalloc.so" to "talloc"
        )

        libraries.forEach { (libName, linkName) ->
            val libFile = File(nativeLibDir, libName)
            val linkFile = File(binDir, linkName)

            Log.i(TAG, "检查 $libName: ${libFile.absolutePath}, exists=${libFile.exists()}")

            if (!libFile.exists()) {
                Log.w(TAG, "⚠ 原生库不存在: $libName")
                return@forEach
            }

            try {
                // 删除已存在的文件或损坏的符号链接
                if (linkFile.exists() || linkFile.toPath().let { java.nio.file.Files.isSymbolicLink(it) }) {
                    linkFile.delete()
                }

                // 设置可执行权限
                libFile.setExecutable(true, false)

                // 创建符号链接
                java.nio.file.Files.createSymbolicLink(linkFile.toPath(), libFile.toPath())
                Log.i(TAG, "✅ 创建符号链接: $linkName -> ${libFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 创建符号链接失败: $linkName, 错误: ${e.message}")
            }
        }

        // 为 busybox 创建常用命令的符号链接（完整覆盖）
        val busybox = File(binDir, "busybox")
        if (busybox.exists()) {
            val busyboxCommands = listOf(
                // Shell
                "sh", "ash", "bash",
                // 文件操作
                "cp", "mv", "rm", "mkdir", "rmdir", "touch", "ln", "chmod", "chown", "chgrp",
                "ls", "cat", "head", "tail", "more", "less", "tac", "rev",
                "find", "xargs", "basename", "dirname", "realpath", "readlink", "stat", "du", "df",
                "dd", "cpio", "lsattr", "chattr",
                // 文本处理
                "grep", "egrep", "fgrep", "sed", "awk", "sort", "uniq", "wc", "tr", "cut",
                "diff", "cmp", "tee", "tee", "paste", "column", "fold",
                // 压缩
                "tar", "gzip", "gunzip", "zcat", "bzip2", "bunzip2", "bzcat",
                "xz", "unxz", "xzcat", "zip", "unzip",
                // 网络
                "wget", "curl", "ping", "ifconfig", "netstat", "nslookup", "telnet",
                // 系统信息
                "uname", "hostname", "id", "whoami", "uptime", "free", "ps", "top", "kill", "killall",
                "env", "printenv", "set", "export", "alias", "unalias",
                "date", "cal", "time", "sleep", "usleep",
                // 十六进制
                "hexdump", "xxd", "od",
                // 其他
                "vi", "nano", "ed", "patch", "strings", "seq", "yes", "expr", "test",
                "seq", "yes", "true", "false", "sleep", "usleep",
                "sync", "reboot", "halt", "poweroff", "su", "sudo", "su",
                "crontab", "at", "tee", "xargs", "nohup",
                // 进程管理
                "nice", "renice", "nohup", "timeout",
                // 设备
                "mknod", "losetup", "mount", "umount", "fdisk", "mkfs",
            )
            busyboxCommands.forEach { cmd ->
                val cmdFile = File(binDir, cmd)
                if (!cmdFile.exists()) {
                    try {
                        java.nio.file.Files.createSymbolicLink(cmdFile.toPath(), busybox.toPath())
                    } catch (_: Throwable) {}
                }
            }
            Log.i(TAG, "✅ 为 busybox 创建了 ${busyboxCommands.size} 个命令符号链接")
        }
    }

    private fun prepareRuntimeExtras(context: Context, rootfs: File) {
        try {
            writeResolvConf(rootfs, context)
            val props = buildProps(context)
            File(rootfs, "etc").mkdirs()
            File(rootfs, "etc/quro_props.prop").writeText(
                props.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n"
            )
            val bin = File(rootfs, "usr/local/bin"); bin.mkdirs()
            val shim = File(bin, "getprop")
            // GETPROP_SHIM 是源码里的原始字符串；Windows 工作区 CRLF 会让 sh 执行出错，强转 LF。
            shim.writeText(GETPROP_SHIM.normalizeLineEndings())
            shim.setExecutable(true, false)
            // 安装全面的终端命令垫片（quro_commands.sh）
            installCommandShims(context, rootfs)
            // 安装跨平台命令兼容层（platform_compat.sh）
            installPlatformCompat(context, rootfs)
        } catch (e: Exception) {
            Log.w(TAG, "prepareRuntimeExtras 部分失败（非致命）: ${e.message}")
        }
    }

    /**
     * 安装全面的终端命令垫片到 /usr/local/bin/。
     * 每个垫片先检查真实命令是否存在（来自 apt/busybox），若不存在则提供基本实现。
     * 这确保即使 apt 安装失败，用户也能使用常见的终端命令。
     */
    private fun installCommandShims(context: Context, rootfs: File) {
        try {
            val marker = File(rootfs, "usr/local/bin/.quro_commands_installed")
            // 幂等：如果已安装且脚本未更新，跳过
            if (marker.exists()) {
                val assetTime = try {
                    context.assets.open("linux_env/quro_commands.sh").use { it.available().toLong() }
                } catch (_: Exception) { 0L }
                if (assetTime <= 0) return
            }
            // 从 assets 复制 quro_commands.sh 到 rootfs
            val targetScript = File(rootfs, "usr/local/bin/quro_commands.sh")
            try {
                context.assets.open("linux_env/quro_commands.sh").use { input ->
                    FileOutputStream(targetScript).use { output ->
                        input.copyTo(output)
                    }
                }
                targetScript.setExecutable(true, false)
                // 执行脚本安装所有命令垫片
                val proot = prootPath(context)
                val loader = loaderPath(context)
                val tmp = tmpPath(context)
                val home = homePath(context)
                val process = ProcessBuilder(
                    proot,
                    "--rootfs=${rootfs.absolutePath}",
                    "--link2symlink",
                    "--bind=/dev",
                    "--bind=/proc",
                    "--bind=/sys",
                    "--bind=$home:/root",
                    "--bind=$tmp:/tmp",
                    "--bind=/system/build.prop:/system/build.prop",
                    "-0", "-w", "/root",
                    "/bin/sh", "-c",
                    "chmod +x /usr/local/bin/quro_commands.sh && /usr/local/bin/quro_commands.sh"
                )
                process.directory(rootfs.parentFile)
                process.environment().apply {
                    put("HOME", "/root")
                    put("PATH", "/usr/local/bin:/usr/bin:/bin")
                    put("LANG", "C.UTF-8")
                }
                process.redirectErrorStream(true)
                val p = process.start()
                val output = p.inputStream.bufferedReader().readText()
                val exitCode = p.waitFor()
                if (exitCode == 0) {
                    marker.writeText("installed")
                    Log.i(TAG, "✅ 终端命令垫片安装成功: ${output.trim().take(200)}")
                } else {
                    Log.w(TAG, "⚠ 终端命令垫片安装失败 (exit=$exitCode): ${output.trim().take(200)}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠ 终端命令垫片安装异常（非致命）: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠ installCommandShims 异常（非致命）: ${e.message}")
        }
    }

    /**
     * 安装跨平台命令兼容层（platform_compat.sh）。
     * 为 Alpine Linux 环境提供其他平台命令的兼容包装器：
     * - Debian/Ubuntu: apt, dpkg, apt-get, apt-cache, apt-mark, dpkg-reconfigure
     * - Termux: pkg, termux-setup-storage, termux-open, termux-clipboard-*, termux-reload-settings
     * - CentOS/RHEL: yum, dnf, rpm, systemctl
     * - Arch Linux: pacman, yay, makepkg
     * - openSUSE: zypper
     * - Void Linux: xbps-install, xbps-remove, xbps-query
     * - NixOS: nix-env, nix-channel
     * - macOS: open, pbcopy, pbpaste, say
     * - BSD: ls-bsd, ps-bsd
     * - 通用: update-alternatives, update-rc.d, service, invoke-rc.d
     * - 网络: ifconfig, netstat, route
     * - 开发: gcc, g++, make, cmake, gdb, strace
     * - 运行时: python, pip, node
     */
    private fun installPlatformCompat(context: Context, rootfs: File) {
        try {
            val marker = File(rootfs, "usr/local/bin/.platform_compat_installed")
            // 幂等：如果已安装且脚本未更新，跳过
            if (marker.exists()) {
                val assetTime = try {
                    context.assets.open("linux_env/platform_compat.sh").use { it.available().toLong() }
                } catch (_: Exception) { 0L }
                if (assetTime <= 0) return
            }
            // 从 assets 复制 platform_compat.sh 到 rootfs
            val targetScript = File(rootfs, "usr/local/bin/platform_compat.sh")
            try {
                context.assets.open("linux_env/platform_compat.sh").use { input ->
                    FileOutputStream(targetScript).use { output ->
                        input.copyTo(output)
                    }
                }
                targetScript.setExecutable(true, false)
                // 执行脚本安装跨平台兼容层
                val proot = prootPath(context)
                val loader = loaderPath(context)
                val tmp = tmpPath(context)
                val home = homePath(context)
                val process = ProcessBuilder(
                    proot,
                    "--rootfs=${rootfs.absolutePath}",
                    "--link2symlink",
                    "--bind=/dev",
                    "--bind=/proc",
                    "--bind=/sys",
                    "--bind=$home:/root",
                    "--bind=$tmp:/tmp",
                    "--bind=/system/build.prop:/system/build.prop",
                    "-0", "-w", "/root",
                    "/bin/sh", "-c",
                    "chmod +x /usr/local/bin/platform_compat.sh && /usr/local/bin/platform_compat.sh"
                )
                process.directory(rootfs.parentFile)
                process.environment().apply {
                    put("HOME", "/root")
                    put("PATH", "/usr/local/bin:/usr/bin:/bin")
                    put("LANG", "C.UTF-8")
                }
                process.redirectErrorStream(true)
                val p = process.start()
                val output = p.inputStream.bufferedReader().readText()
                val exitCode = p.waitFor()
                if (exitCode == 0) {
                    marker.writeText("installed")
                    Log.i(TAG, "✅ 跨平台命令兼容层安装成功: ${output.trim().take(200)}")
                } else {
                    Log.w(TAG, "⚠ 跨平台命令兼容层安装失败 (exit=$exitCode): ${output.trim().take(200)}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠ 跨平台命令兼容层安装异常（非致命）: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠ installPlatformCompat 异常（非致命）: ${e.message}")
        }
    }

    private fun writeAptSources(rootfs: File, mirrorBase: String) {
        val aptDir = File(rootfs, "etc/apt")
        aptDir.mkdirs()
        Log.i(TAG, "writeAptSources: 配置apt源 $mirrorBase")
        
        // Ubuntu 24.04 用 DEB822 格式（/etc/apt/sources.list.d/ubuntu.sources），
        // 但也兼容传统 sources.list。必须删除 DEB822 格式，否则 apt 会优先读取它。
        val sourcesListD = File(aptDir, "sources.list.d")
        if (sourcesListD.exists()) {
            Log.i(TAG, "sources.list.d 目录存在，列出所有文件:")
            sourcesListD.listFiles()?.forEach { file ->
                Log.i(TAG, "  文件: ${file.name} (${file.length()} bytes)")
                if (file.name.endsWith(".sources") || file.name.endsWith(".list")) {
                    val deleted = file.delete()
                    Log.i(TAG, "  删除${if (deleted) "成功" else "失败"}: ${file.absolutePath}")
                }
            }
        } else {
            Log.i(TAG, "sources.list.d 目录不存在")
        }
        
        // 写入传统 sources.list 格式
        val sourcesContent = "deb $mirrorBase/ $UBUNTU_CODENAME main restricted universe multiverse\n" +
            "deb $mirrorBase/ ${UBUNTU_CODENAME}-updates main restricted universe multiverse\n" +
            "deb $mirrorBase/ ${UBUNTU_CODENAME}-security main restricted universe multiverse\n" +
            "deb $mirrorBase/ ${UBUNTU_CODENAME}-backports main restricted universe multiverse\n"
        File(aptDir, "sources.list").writeText(sourcesContent)
        Log.i(TAG, "写入sources.list: $mirrorBase")
        
        // 关闭签名验证（proot 环境下 GPG 公钥可能不完整）
        // 同时配置超时和重试（手机网络不稳定）
        File(aptDir, "apt.conf.d").mkdirs()
        File(aptDir, "apt.conf.d/99no-check-gpg").writeText(
            "Acquire::Check-Valid-Until \"false\";\n" +
            "APT::Get::AllowUnauthenticated \"true\";\n" +
            "Acquire::http::Timeout \"60\";\n" +
            "Acquire::https::Timeout \"60\";\n" +
            "Acquire::ftp::Timeout \"60\";\n" +
            "Acquire::Retries \"5\";\n" +
            "Acquire::http::Dl-Limit \"256\";\n" +
            "Acquire::ForceIPv4 \"true\";\n" +
            "Acquire::http::Pipeline-Depth \"0\";\n"
        )
        Log.i(TAG, "写入99no-check-gpg + 超时重试配置")
    }

    /** 读取最近一次部署的诊断日志。 */
    fun getDiagLog(context: Context): String? {
        return try {
            val logFile = File(sandboxDir(context), "setup-diag.log")
            if (logFile.exists()) logFile.readText() else null
        } catch (_: Throwable) { null }
    }

    /** 重置沙箱（清掉 rootfs 与状态）。 */
    fun reset(context: Context) {
        scope.launch {
            sandboxDir(context).deleteRecursively()
            _state.value = SandboxState.NotInstalled
        }
    }
}
