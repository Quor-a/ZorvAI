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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.time.Duration.Companion.milliseconds

/**
 * 应用内 Linux 环境（proot + Alpine aarch64）后端。
 *
 * v108 删除了原 QuroLinuxEnv 资产（proot 二进制 + alpine.tar.gz 随包解压），
 * 导致终端只能回退成设备 Toybox sh、AI 的 linux_* 工具全部报「环境不可用」。
 *
 * 本版本（v132）直接移植 Kai 9000（https://github.com/SimonSchubert/Kai）的
 * Android Linux Sandbox 思路并落地：
 * - proot 二进制以预编译 .so 形式打包进 jniLibs，**从 applicationInfo.nativeLibraryDir
 *   取执行权限**（Android 仅在此目录授予 .so 可执行权限，这是终端此前跑不起来的根因）；
 * - Alpine rootfs（3.22.5，因 3.23+ 的 apk-tools 3 用了 proot 不支持的 execveat）
 *   首次使用时从镜像（清华源优先）下载 minirootfs 并解压到应用私有目录；
 * - 交互终端经 [shellLaunch] 以 proot 常驻 /bin/sh，获得 python3 / 完整写能力等；
 * - 非交互命令经 [run] 一次性执行，供 AI 的 linux_* / terminal_* 工具调用；
 * - 任一资产缺失则优雅降级并报明确原因，不崩溃、不静默失败。
 */
object QuroLinuxEnv {

    private const val TAG = "QuroLinuxEnv"

    /** Alpine 锁 3.22.5：3.23+ 的 apk-tools 3 使用 execveat()，proot 不支持，apk update 会失败。 */
    private const val ALPINE_VERSION = "3.22.5"
    private const val ALPINE_BRANCH = "v3.22"
    private const val BUFFER_SIZE = 8192
    private const val MAX_OUTPUT_LENGTH = 15_000L

    // 清华源优先（国内用户最快），其余为国际兜底。
    private val ALPINE_MIRRORS = listOf(
        "https://mirrors.tuna.tsinghua.edu.cn/alpine",
        "https://dl-cdn.alpinelinux.org/alpine",
        "https://mirrors.edge.kernel.org/alpine",
        "https://alpine.ethz.ch/alpine",
        "https://mirror.csclub.uwaterloo.ca/alpine",
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
    fun prootPath(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath

    fun loaderPath(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so").absolutePath

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

    /** 探测环境是否就绪（不触发下载）。 */
    fun probe(context: Context): EnvStatus {
        val proot = File(prootPath(context))
        if (!proot.exists()) {
            return EnvStatus(false, null, null, "proot 二进制缺失（nativeLibraryDir 未含 libproot.so）")
        }
        val rootfs = File(rootfsPath(context))
        return if (rootfs.isDirectory && File(prootPath(context)).canExecute()) {
            if (_state.value !is SandboxState.Ready) _state.value = SandboxState.Ready
            EnvStatus(true, proot.absolutePath, rootfs.absolutePath, "环境就绪")
        } else {
            EnvStatus(false, proot.absolutePath, null, "Alpine rootfs 未安装（请在终端点「安装 Linux 环境」）")
        }
    }

    /**
     * 触发一次性安装：下载 Alpine rootfs → 解压 → 写 resolv.conf/repositories →
     * apk update → 装 bash。幂等，已是 Ready 则直接返回。进度通过 [state] 暴露给 UI。
     */
    fun setup(context: Context) {
        if (setupJob?.isActive == true) return
        setupJob = scope.launch {
            if (!setupMutex.tryLock()) return@launch
            try {
                setupInternal(context)
            } catch (e: Exception) {
                Log.e(TAG, "setup failed", e)
                _state.value = SandboxState.Error(e.message ?: "安装失败")
            } finally {
                setupMutex.unlock()
            }
        }
    }

    fun cancelSetup() {
        setupJob?.cancel()
        setupJob = null
    }

    private suspend fun setupInternal(context: Context) {
        val arch = getLinuxArch()
        val proot = File(prootPath(context))
        if (!proot.exists()) {
            throw IllegalStateException("proot 二进制缺失于 ${proot.absolutePath}")
        }
        val dir = sandboxDir(context)
        dir.mkdirs()
        File(dir, "tmp").mkdirs()

        // Android 把原生 .so 的 .so.2 后缀剥离，Alpine 内程序按 libtalloc.so.2 找，这里补回。
        val talloc = File(dir, "libtalloc.so.2")
        if (!talloc.exists()) {
            val src = File(context.applicationInfo.nativeLibraryDir, "libtalloc.so")
            if (src.exists()) src.copyTo(talloc, overwrite = true)
        }

        val rootfsDir = File(dir, "rootfs")
        if (rootfsDir.exists()) rootfsDir.deleteRecursively()
        val tarGz = File(dir, "rootfs.tar.gz")
        try {
            _state.value = SandboxState.Downloading(0f)
            downloadRootfs(arch, tarGz) { p -> _state.value = SandboxState.Downloading(p) }

            _state.value = SandboxState.Extracting
            extractTarGz(tarGz, rootfsDir)
        } finally {
            tarGz.delete()
        }

        _state.value = SandboxState.Installing("初始化…")
        makeWritable(rootfsDir)
        prepareRuntimeExtras(context, rootfsDir)

        var updated = false
        var lastErr = ""
        for (mirror in ALPINE_MIRRORS) {
            writeRepositories(rootfsDir, mirror)
            val r = runProot(context, "apk update", timeoutMs = 60_000)
            if (r.first == 0) { updated = true; break }
            lastErr = r.second.take(200)
        }
        if (!updated) {
            rootfsDir.deleteRecursively()
            throw IllegalStateException("apk update 在所有镜像失败: $lastErr")
        }

        // bash 是持久 shell 的基础，必须装。
        _state.value = SandboxState.Installing("安装 bash…")
        val bash = runProot(context, "apk add --no-cache bash", timeoutMs = 120_000)
        if (bash.first != 0) {
            throw IllegalStateException("bash 安装失败：${bash.second.take(200)}")
        }
        _state.value = SandboxState.Ready
    }

    /** 一次性执行命令（AI 工具用）。环境不可用返回原因。 */
    fun run(context: Context, command: String, timeoutMs: Long = 30000): Pair<Int, String> {
        val st = probe(context)
        if (!st.available || st.prootPath == null || st.rootfsPath == null) {
            return -1 to "❌ Linux 环境不可用：${st.reason}。请在终端点「安装 Linux 环境」。"
        }
        return runProot(context, command, timeoutMs)
    }

    /** 内部：构造 proot 参数并执行。 */
    private fun runProot(context: Context, command: String, timeoutMs: Long): Pair<Int, String> {
        val proot = prootPath(context)
        val rootfs = rootfsPath(context)
        val home = homePath(context)
        val tmp = tmpPath(context)
        val loader = loaderPath(context)
        val dir = sandboxDir(context)
        // 运行期资产刷新（resolv.conf 用设备 DNS / getprop 垫片），让 AI 经 linux_* / terminal_*
        // 工具驱动的命令同样拥有联网能力与 getprop。
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
            // getprop 垫片可回落读 /system/build.prop，仅当该文件本就可读时绑定（避免整体启动失败）。
            if (File("/system/build.prop").canRead()) {
                args.add("--bind=/system/build.prop:/system/build.prop")
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
            // 关键修复（#911 根因）：必须先 waitFor(timeout) 再读输出。原先 readText() 会阻塞到
            // 进程退出，导致 timeoutMs 永不触发，hang 住的 bootstrap/provision 让部署永久卡「部署中」。
            // 改为后台线程读 stdout，主线程 waitFor 超时后强杀进程，读取线程随 stdout 关闭自然结束。
            val outBuilder = StringBuilder()
            val reader = p.inputStream.bufferedReader()
            val readThread = Thread {
                try { reader.use { r -> r.forEachLine { outBuilder.appendLine(it) } } } catch (_: Throwable) {}
            }
            readThread.start()
            val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            val code = if (finished) {
                p.exitValue()
            } else {
                try { p.destroyForcibly() } catch (_: Throwable) {}
                -1
            }
            try { readThread.join(2000) } catch (_: Throwable) {} // 进程已结束/被强杀，stdout 已关闭，回收读取线程
            val trimmed = outBuilder.toString().trim()
            code to (if (trimmed.isBlank()) (if (finished) "(no output, exit $code)" else "⏱ 命令超时(${timeoutMs}ms)") else trimmed)
        } catch (e: Exception) {
            -1 to "❌ proot 执行失败: ${e.message}"
        }
    }

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
        val args = mutableListOf(
            "--rootfs=${st.rootfsPath}",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=${homePath(context)}:/root",
            "--bind=${tmpPath(context)}:/tmp",
            "-0",
            "-w", "/root",
            "/bin/sh",
        )
        // getprop 垫片可回落读 /system/build.prop，故把宿主真机 build.prop 只读绑进沙箱
        // （仅当该文件本就可读，避免 proot 因源不存在而整体启动失败）。
        if (File("/system/build.prop").canRead()) {
            args.add("--bind=/system/build.prop:/system/build.prop")
        }
        return st.prootPath to args
    }

    /** 交互 shell 进程应注入的环境变量（PROOT_LOADER / LD_LIBRARY_PATH 等）。 */
    fun shellEnv(context: Context): Array<String> = arrayOf(
        "TERM=xterm-256color",
        "HOME=/root",
        "TMPDIR=${tmpPath(context)}",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "LANG=C.UTF-8",
        "LD_LIBRARY_PATH=${sandboxDir(context).absolutePath}",
        "PROOT_TMP_DIR=${tmpPath(context)}",
        "PROOT_LOADER=${loaderPath(context)}",
    )

    // ----------------------------------------------------------------
    // rootfs 下载（HttpURLConnection，避免引入 ktor 依赖）
    // ----------------------------------------------------------------

    private fun downloadRootfs(arch: String, target: File, onProgress: (Float) -> Unit) {
        val urls = ALPINE_MIRRORS.map { base ->
            "$base/$ALPINE_BRANCH/releases/$arch/alpine-minirootfs-$ALPINE_VERSION-$arch.tar.gz"
        }
        var lastErr: Exception? = null
        for ((i, url) in urls.withIndex()) {
            try {
                downloadFrom(url, target, onProgress)
                return
            } catch (e: Exception) {
                lastErr = e
                if (target.exists()) target.delete()
                if (i < urls.lastIndex) onProgress(0f)
            }
        }
        throw java.io.IOException("所有 Alpine 镜像下载失败: ${lastErr?.message}")
    }

    private fun downloadFrom(url: String, target: File, onProgress: (Float) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.requestMethod = "GET"
        try {
            if (conn.responseCode !in 200..299) {
                throw java.io.IOException("HTTP ${conn.responseCode} from $url")
            }
            val total = conn.contentLengthLong
            conn.inputStream.buffered().use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) onProgress(downloaded.toFloat() / total)
                    }
                }
            }
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
        GZIPInputStream(BufferedInputStream(FileInputStream(tarGz))).use { gzip ->
            extractTar(gzip, target)
        }
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
                    } catch (_: Exception) { }
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
            if (f.isDirectory && !f.canWrite()) f.setWritable(true, true)
        }
    }

    /**
     * 写 rootfs 的 /etc/resolv.conf。
     *
     * **网络修复（用户「要完整的」之一）**：旧实现硬编码 `nameserver 8.8.8.8 / 8.8.4.4`，
     * 在运营商/企业网屏蔽 Google DNS 时终端 `ping`/`apk`/`curl` 全部解析失败。
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
     * 终端内 getprop 垫片脚本（Alpine 没有 Android 的 getprop）。
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
            shim.writeText(GETPROP_SHIM)
            shim.setExecutable(true, false)
        } catch (e: Exception) {
            Log.w(TAG, "prepareRuntimeExtras 部分失败（非致命）: ${e.message}")
        }
    }

    private fun writeRepositories(rootfs: File, mirrorBase: String) {
        val apk = File(rootfs, "etc/apk"); apk.mkdirs()
        File(apk, "repositories").writeText("$mirrorBase/$ALPINE_BRANCH/main\n$mirrorBase/$ALPINE_BRANCH/community\n")
    }

    /** 重置沙箱（清掉 rootfs 与状态）。 */
    fun reset(context: Context) {
        scope.launch {
            sandboxDir(context).deleteRecursively()
            _state.value = SandboxState.NotInstalled
        }
    }
}
