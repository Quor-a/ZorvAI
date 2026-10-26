package com.ai.assistance.quro.terminal.runtime

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.ai.assistance.quro.terminal.Pty
import com.ai.assistance.quro.terminal.TerminalSession
import com.ai.assistance.quro.terminal.data.PackageManagerType
import com.ai.assistance.quro.terminal.runtime.Distro
import com.ai.assistance.quro.terminal.utils.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 终端运行环境引导器。
 *
 * 职责单一：负责在设备上准备 proot + Ubuntu rootfs 所需的本地环境——
 * 目录结构、原生库链接、busybox 符号链接、rootfs 资产解压、启动脚本生成，
 * 以及基于 PTY 的会话进程启动/销毁。不参与命令派发与会话状态管理。
 */
@RequiresApi(Build.VERSION_CODES.O)
class TerminalEnvironment(context: Context) {

    private val appContext: Context = context.applicationContext
    private val filesDir: File = context.filesDir
    private val usrDir: File = File(filesDir, "usr")
    private val binDir: File = File(usrDir, "bin")
    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir
    // 应用自有外部目录，作为「终端公用挂载主目录」始终可挂（不依赖 MANAGE_EXTERNAL_STORAGE 权限）
    private val sharedHostDir: String = context.getExternalFilesDir(null)?.absolutePath
        ?: File(filesDir, "shared").apply { mkdirs() }.absolutePath

    private val sourceManager = SourceManager(context)

    private val envInitMutex = Mutex()
    private var isEnvInitialized = false

    /** 最近一次环境初始化的失败原因（2026-10-26：云手机无 logcat，失败原因必须能展示给用户）。 */
    @Volatile
    var lastInitError: String? = null
        private set

    private val activeSessions = ConcurrentHashMap<String, TerminalSession>()

    companion object {
        private const val TAG = "TerminalEnvironment"
        private const val UBUNTU_FILENAME = "ubuntu-noble-aarch64-pd-v4.18.0.tar.xz"
        /** 资产内容版本：改了内置 tar 包内容时 +1，强制设备重抽。
         *  仅靠 lastUpdateTime 无法捕捉「同版本号改内容」，会导致设备继续用旧解压 rootfs。 */
        private const val ASSET_VERSION = 17

        /**
         * 「解压进行中」标记文件名（写在 rootfs 根下）。
         *
         * 🔴 2026-10-27 v1.1.7 回归后：install_ubuntu（v1.1.7 原文）**不写**此标记，
         * 因此 [isExtracting] 恒为 false（兼容保留；墨语言通道按 NEEDS_INSTALL/READY 推进）。
         * 此前的 HEAD 版 install_rootfs 在解压开始前写入、校验通过后删除，存续期精确等于
         * 「解压这段时间」，是 UI「正在解压…」与按状态轮询的依据（2026-10-21 用户要求）。
         */
        const val EXTRACT_MARK = ".extracting"
    }

    /**
     * 初始化本地运行环境（幂等）。
     */
    suspend fun initializeEnvironment(): Boolean {
        if (isEnvInitialized) return true

        envInitMutex.withLock {
            if (isEnvInitialized) return true

            val success = withContext(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Starting environment initialization...")
                    createDirectories()
                    linkNativeLibs()
                    createBusyboxSymlinks()
                    extractAssets()
                    ensureRootfsComplete()
                    val startScript = generateStartScript()
                    File(filesDir, "common.sh").writeText(startScript)
                    Log.d(TAG, "Environment initialization completed successfully.")
                    true
                } catch (e: Exception) {
                    lastInitError = "${e.javaClass.simpleName}: ${e.message}"
                    Log.e(TAG, "Environment initialization failed", e)
                    false
                }
            }
            if (success) {
                isEnvInitialized = true
            }
            return success
        }
    }

    private fun createDirectories() {
        if (!usrDir.exists()) {
            usrDir.mkdirs()
        }
        if (!binDir.exists()) {
            binDir.mkdirs()
            Log.d(TAG, "Created bin directory at: ${binDir.absolutePath}")
        }
        File(filesDir, "tmp").mkdirs()
        // 终端用户空间目录（app 拥有）：登录时 bind 进 guest 的 /root/shared（2026-10-26 起，
        // /root 归还 rootfs 内自带家；这里是 LANXIU 时代共享遗产的容身之处）。
        File(filesDir, "proot_home").mkdirs()
        // 🔴 CA 证书 bundle：初始化阶段就生成（幂等）。v1.1.7 回归后 common.sh 无 patch_rootfs，
        // 但内置 rootfs 自带 ca-certificates.crt（tarball 实证 219KB），HTTPS 开箱即用；
        // bundle 仅作 execGuest 通道兜底（见 ensureCaBundle 注释）。
        ensureCaBundle()
    }

    // 🔴🔴🔴 不要复活 isPieElf / 任何 PIE 门控（2026-10-26 复盘定案）🔴🔴🔴
    //
    // jniLibs 里的 libbash.so / libbusybox.so / libloader.so 经 ELF 程序头解析均为
    // **e_type=2（ET_EXEC）+ 无 PT_INTERP + 无 PT_DYNAMIC = 静态二进制**。
    // 静态 ET_EXEC 由内核 binfmt_elf 直接映射执行，不经过动态链接器，
    // Android 10+ 完全可以执行（v1.1.7 起真机一直这么跑，同一份二进制字节未变）。
    //
    // 6fbb0d46 引入的 isPieElf 把静态 ET_EXEC 误判为「Android 10+ 拒绝执行」，
    // 于是跳过创建 binDir/bash 与 binDir/busybox 链接（还删掉历史链接）→
    // startTerminalSession 的 Pty.start(bash, "-c", ...) 去 exec 一个**不存在的文件**
    // → 终端必死。这就是「终端怎么修都还是废」的真因，与 Ubuntu 包/rootfs 无关。
    //
    // bionic 的 "libbusybox.so has unexpected e_type: 2" 只出现在 **dlopen** 场景
    // （把 .so 当共享库加载），与 execve 执行无关，不能拿它当 exec 的判据。
    //
    // 因此 linkNativeLibs / createBusyboxSymlinks 必须**无条件建链**，与 v1.1.7 一致。

    private fun linkNativeLibs() {
        Log.d(TAG, "Linking native libraries from: $nativeLibDir")

        val nativeLibDirFile = File(nativeLibDir)
        if (!nativeLibDirFile.exists() || !nativeLibDirFile.isDirectory) {
            Log.e(TAG, "Native library directory not found or is not a directory.")
            return
        }

        val busybox = File(binDir, "busybox")
        val busyboxSo = File(nativeLibDir, "libbusybox.so")
        if (!busyboxSo.exists()) {
            Log.e(TAG, "libbusybox.so not found, cannot create busybox link")
            return
        }

        // 🔴 2026-10-26 真机复盘：个别设备/ROM 的 /data 文件系统（FUSE/虚拟化层）
        // 不支持符号链接，Files.createSymbolicLink 直接抛异常 -> 原来只 Log + return，
        // 于是 binDir/bash 根本不存在 -> Pty.start exec 不存在的文件 -> 终端纯黑屏。
        // 现在软链失败自动退回「实体拷贝」：busybox 才 1.5MB，五个库全拷不到 5MB，
        // 换一刀切兼容。exec 语义与软链完全一致（都是可执行文件），无行为差异。
        val busyboxOk = runCatching {
            Files.deleteIfExists(busybox.toPath())
            busyboxSo.setExecutable(true, false)
            Files.createSymbolicLink(busybox.toPath(), busyboxSo.toPath())
            busybox.exists() && busybox.canExecute()
        }.getOrElse { e ->
            Log.w(TAG, "busybox 符号链接失败（${e.javaClass.simpleName}: ${e.message}），退回实体拷贝")
            false
        }
        if (!busyboxOk) {
            runCatching {
                Files.deleteIfExists(busybox.toPath())
                busyboxSo.copyTo(busybox, overwrite = true)
                busybox.setExecutable(true, false)
                busybox.exists() && busybox.canExecute()
            }.onFailure { Log.e(TAG, "busybox 实体拷贝也失败", it) }
        }

        val libraries = mapOf(
            "libproot.so" to "proot",
            "libloader.so" to "loader",
            "liblibtalloc.so.2.so" to "libtalloc.so.2",
            "libbash.so" to "bash",
            "libsudo.so" to "sudo"
        )

        libraries.forEach { (libName, linkName) ->
            val libFile = File(nativeLibDir, libName)
            val linkFile = File(binDir, linkName)
            if (!libFile.exists()) {
                Log.w(TAG, "Native library not found: $libName")
                return@forEach
            }
            // 同 busybox：软链不可用（云手机 FS）时退回实体拷贝，保证 bash/loader/proot 一定可 exec。
            val linkOk = runCatching {
                Files.deleteIfExists(linkFile.toPath())
                libFile.setExecutable(true, false)
                Files.createSymbolicLink(linkFile.toPath(), libFile.toPath())
                linkFile.exists() && linkFile.canExecute()
            }.getOrElse { e ->
                Log.w(TAG, "$linkName 符号链接失败（${e.javaClass.simpleName}: ${e.message}），退回实体拷贝")
                false
            }
            if (!linkOk) {
                runCatching {
                    Files.deleteIfExists(linkFile.toPath())
                    libFile.copyTo(linkFile, overwrite = true)
                    linkFile.setExecutable(true, false)
                    linkFile.exists() && linkFile.canExecute()
                }.onFailure { Log.e(TAG, "$linkName 实体拷贝也失败", it) }
            }
        }
    }

    private fun createBusyboxSymlinks() {
        val links = listOf(
            "awk", "ash", "basename", "bzip2", "curl", "cp", "chmod", "cut", "cat", "du", "dd",
            "find", "grep", "gzip", "hexdump", "head", "id", "lscpu", "mkdir", "realpath", "rm",
            "sed", "stat", "sh", "tr", "tar", "uname", "xargs", "xz", "xxd"
        )
        val busybox = File(binDir, "busybox")
        for (linkName in links) {
            try {
                createSymbolicLink(busybox, linkName, binDir, true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create link for '$linkName'", e)
            }
        }
        try {
            val fileLink = File(binDir, "file")
            if (!fileLink.exists()) {
                Files.createSymbolicLink(fileLink.toPath(), File("/system/bin/file").toPath())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create symlink for 'file'", e)
        }
    }

    @Throws(IOException::class)
    private fun createSymbolicLink(target: File, linkName: String, linkDir: File, force: Boolean) {
        val linkFile = File(linkDir, linkName)
        val targetPath = if (target.parentFile == linkDir) {
            Paths.get(target.name)
        } else {
            target.toPath()
        }
        if (force) {
            Files.deleteIfExists(linkFile.toPath())
        }
        Files.createSymbolicLink(linkFile.toPath(), targetPath)
    }

    private fun extractAssets() {
        try {
            // Ubuntu 内置（唯一 rootfs 资产）。墨语言运行时是独立资产，
            // 由 MoyuRuntime 自行从 assets 解压到 filesDir/moyu，不参与 rootfs 安装。
            val assetNames = listOf(UBUNTU_FILENAME)
            val stampFile = File(filesDir, ".assets.stamp")
            val prevStamp = runCatching { stampFile.readText() }.getOrDefault("")
            // 用 lastUpdateTime 而不是 versionCode：每次安装都会变，保证「同版本号重装」也能刷新资产。
            // 旧实现「exists() 即跳过」会让换了内容的新资产、以及被中断写坏的半截包永远留在设备上。
            val installStamp = runCatching {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime
            }.getOrDefault(0L)
            val stampMatch = Regex("^v=(\\d+);av=(\\d+)$", RegexOption.MULTILINE)
                .find(prevStamp)
            val prevStampV = stampMatch?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            val prevAssetV = stampMatch?.groupValues?.get(2)?.toIntOrNull() ?: -1
            val versionChanged = prevStampV != installStamp || prevAssetV != ASSET_VERSION

            val newStamp = StringBuilder("v=$installStamp;av=$ASSET_VERSION\n")
            assetNames.forEach { assetName ->
                val dest = File(filesDir, assetName)
                val recordedSize = Regex("^${Regex.escape(assetName)}=(\\d+)$", RegexOption.MULTILINE)
                    .find(prevStamp)?.groupValues?.get(1)?.toLongOrNull()
                // 尺寸与戳不符（上次拷贝被中断留下半截包）或版本变了 -> 必须重拷。
                val needCopy = versionChanged || !dest.exists() ||
                    recordedSize == null || dest.length() != recordedSize
                if (needCopy) {
                    // 先写 .tmp 再原子替换：半截拷贝只会留下 .tmp，绝不会污染正式文件名。
                    // 从根上消除「半截 tarball 被判为已下载 -> busybox tar 永远解压失败 -> 终端永久黑屏」的死锁。
                    val tmp = File(filesDir, "$assetName.tmp")
                    Files.deleteIfExists(tmp.toPath())
                    // 🔴 容错（2026-10-20）：瘦身/验证包可能**故意不含**大 rootfs 资产——全量包 862MB
                    // （rootfs 495MB + libxul 137MB），在部分设备上安装会失败，导致「装了新包但跑的还是
                    // 旧代码」的假象。资产缺失时必须跳过而不是抛异常，否则 initializeEnvironment 直接
                    // 失败 -> 整个终端不可用。设备上已有解压好的 rootfs，不依赖这份 tarball 也能跑。
                    val copied = runCatching {
                        appContext.assets.open(assetName).use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        }
                        true
                    }.getOrElse { e ->
                        Log.w(TAG, "Asset $assetName 不存在或拷贝失败，跳过（瘦身包场景）：${e.message}")
                        Files.deleteIfExists(tmp.toPath())
                        false
                    }
                    if (copied) {
                        if (dest.exists() && !dest.delete()) Log.w(TAG, "Cannot delete old $assetName")
                        if (!tmp.renameTo(dest)) {
                            tmp.copyTo(dest, overwrite = true)
                            Files.deleteIfExists(tmp.toPath())
                        }
                        Log.d(TAG, "Copied asset $assetName -> ${dest.length()} bytes")
                    } else if (!dest.exists()) {
                        Log.w(TAG, "Asset $assetName 缺失，且设备上也没有副本（依赖后续按需下载）")
                    }
                } else {
                    Log.d(TAG, "Asset $assetName up to date (${dest.length()} bytes).")
                }
                // 只有设备上确实存在该文件时才记尺寸；否则下次仍会尝试拷贝。
                if (dest.exists()) {
                    newStamp.append(assetName).append('=').append(dest.length()).append('\n')
                }
            }
            runCatching { stampFile.writeText(newStamp.toString()) }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to extract assets", e)
            throw e
        }
    }

    /**
     * 生成启动脚本。
     *
     * 🔴🔴 2026-10-27：完整回归 v1.1.7 发布版脚本（用户指令「直接完整回归1.1.7终端」）。
     * HEAD 自 df64c52b（10-18）起脚本膨胀到 434 行（暂存解压/原子换装/解压锁/资产标记/
     * usrmerge 兜底/CA 兜底/就绪强检），真机实跑 common.sh 在设备上报
     * 「line 433: syntax error: unexpected end of file」→ source 失败 → 安装/登录从未执行 → rootfs 目录不存在 → 全黑。
     * 本地 bash -n 全绿而设备失败，根因未定位（已排除 CRLF：工作区纯 LF）。
     * 结论：不再增量修，脚本载荷整体换回 v1.1.7 原文（约 106 行、真机验证过的形态）。
     * tarball 实测佐证 v1.1.7 假设全部成立：标准 Ubuntu 24.04(noble) aarch64、usrmerge
     * 布局（/bin→usr/bin）、root/.bashrc+.profile 为官方干净版、自带 ca-certificates.crt、
     * 顶层包装目录 ubuntu-noble-aarch64/（install_ubuntu 的 mv 拍平正为此而设）。
     * HEAD 旁支通道（execGuest/prepareGuestRootfs 等）的 Kotlin 调用点已同步改回
     * v1.1.7 函数名（install_ubuntu/login_ubuntu）。
     */
    private fun generateStartScript(): String {
        val ubuntuName = UBUNTU_FILENAME.replace(Regex("-pd.*"), "")
        val tmpDir = File(filesDir, "tmp").absolutePath
        val binDir = binDir.absolutePath
        val homeDir = filesDir.absolutePath
        val usrDir = usrDir.absolutePath
        val prootDistroPath = "$usrDir/var/lib/proot-distro"
        val ubuntuPath = "$prootDistroPath/installed-rootfs/ubuntu"

        val aptSource = sourceManager.getSelectedSource(PackageManagerType.APT)
        val pipSource = sourceManager.getSelectedSource(PackageManagerType.PIP)
        val npmSource = sourceManager.getSelectedSource(PackageManagerType.NPM)
        val rustSource = sourceManager.getSelectedSource(PackageManagerType.RUST)

        val common = """
        export TMPDIR=$tmpDir
        export BIN=$binDir
        export HOME=$homeDir
        export UBUNTU_PATH=$ubuntuPath
        export UBUNTU=$UBUNTU_FILENAME
        export UBUNTU_NAME=$ubuntuName
        export L_NOT_INSTALLED="not installed"
        export L_INSTALLING="installing"
        export L_INSTALLED="installed"
        clear_lines(){
          printf "\\033[1A"
          printf "\\033[K"
          printf "\\033[1A"
          printf "\\033[K"
        }
        progress_echo(){
          echo -e "\\033[31m- ${'$'}@\\033[0m"
          echo "${'$'}@" > "${'$'}TMPDIR/progress_des"
        }
        bump_progress(){
          current=0
          if [ -f "${'$'}TMPDIR/progress" ]; then
            current=${'$'}(cat "${'$'}TMPDIR/progress" 2>/dev/null || echo 0)
          fi
          next=${'$'}((current + 1))
          printf "${'$'}next" > "${'$'}TMPDIR/progress"
        }
        """.trimIndent()

        val installUbuntu = """
        install_ubuntu(){
          mkdir -p ${'$'}UBUNTU_PATH 2>/dev/null
          if [ -z "${'$'}(ls -A ${'$'}UBUNTU_PATH)" ]; then
            progress_echo "Ubuntu ${'$'}L_NOT_INSTALLED, ${'$'}L_INSTALLING..."
            ls ~/${'$'}UBUNTU
            progress_echo "Extracting Ubuntu rootfs..."
            busybox tar xf ~/${'$'}UBUNTU -C ${'$'}UBUNTU_PATH/ >/dev/null 2>&1
            rm -f ~/${'$'}UBUNTU
            echo "Extraction complete"
            mv ${'$'}UBUNTU_PATH/${'$'}UBUNTU_NAME/* ${'$'}UBUNTU_PATH/ 2>/dev/null
            rm -rf ${'$'}UBUNTU_PATH/${'$'}UBUNTU_NAME
            echo 'export ANDROID_DATA=/home/' >> ${'$'}UBUNTU_PATH/root/.bashrc
          else
            VERSION=`cat ${'$'}UBUNTU_PATH/etc/issue.net 2>/dev/null`
            progress_echo "Ubuntu ${'$'}L_INSTALLED -> ${'$'}VERSION"
          fi
          echo 'nameserver 8.8.8.8' > ${'$'}UBUNTU_PATH/etc/resolv.conf
        }
        """.trimIndent()

        val configureSources = """
        configure_sources(){
          # 配置APT源
          cat <<'EOF' > ${'$'}UBUNTU_PATH/etc/apt/sources.list
        # From ZorvAI Settings - ${aptSource.name}
        deb ${aptSource.url} noble main restricted universe multiverse
        deb ${aptSource.url} noble-updates main restricted universe multiverse
        deb ${aptSource.url} noble-backports main restricted universe multiverse
        EOF

          # 配置Pip/Uv源
          mkdir -p ${'$'}UBUNTU_PATH/root/.config/pip 2>/dev/null
          echo '[global]' > ${'$'}UBUNTU_PATH/root/.config/pip/pip.conf
          echo 'index-url = ${pipSource.url}' >> ${'$'}UBUNTU_PATH/root/.config/pip/pip.conf

          mkdir -p ${'$'}UBUNTU_PATH/root/.config/uv 2>/dev/null
          echo 'index-url = "${pipSource.url}"' > ${'$'}UBUNTU_PATH/root/.config/uv/uv.toml

          # 配置NPM源
          mkdir -p ${'$'}UBUNTU_PATH/root 2>/dev/null
          echo 'registry=${npmSource.url}' > ${'$'}UBUNTU_PATH/root/.npmrc
        }
        """.trimIndent()

        // 共享存储挂载（/sdcard）：仅在已获存储权限时 bind，避免「挂上了却 EACCES」。
        // Android 11+ 需「所有文件访问」(MANAGE_EXTERNAL_STORAGE)；10- 需 READ/WRITE_EXTERNAL_STORAGE。
        // 无权限时跳过挂载，与 QuroLinuxEnv.sharedStorageHostDir 的权限闸门保持一致。
        // 这里把两行 bind 收敛到 `@SB@` 占位符，trimIndent 之后按权限结果替换成「有 / 无」挂载，
        // 避免在 raw string 里做插值导致缩进被 trimIndent 破坏。
        val sharedStorageBindLines = if (sharedStorageAccessible()) {
            "-b /storage/emulated/0:/sdcard \\\n    -b /storage/emulated/0:/storage/emulated/0 \\"
        } else {
            ""
        }

        val loginUbuntu = """
        login_ubuntu(){
          mkdir -p "${'$'}UBUNTU_PATH/storage/emulated" 2>/dev/null
          # 终端「公用挂载主目录」：应用自有外部目录，不依赖存储权限，始终可用
          mkdir -p "${'$'}UBUNTU_PATH/mnt/shared" 2>/dev/null
          mkdir -p "${'$'}UBUNTU_PATH/root/shared" 2>/dev/null
          exec ${'$'}BIN/proot \
            -0 \
            -r "${'$'}UBUNTU_PATH" \
            --link2symlink \
            -b /dev \
            -b /proc \
            -b /sys \
            -b /dev/pts \
            -b "${'$'}TMPDIR":/dev/shm \
            -b /proc/self/fd:/dev/fd \
            -b /proc/self/fd/0:/dev/stdin \
            -b /proc/self/fd/1:/dev/stdout \
            -b /proc/self/fd/2:/dev/stderr \
            -b "$sharedHostDir":/mnt/shared \
            -b "$sharedHostDir":/root/shared \
            @SB@
            -w /root \
            /usr/bin/env -i \
              HOME=/root \
              TERM=xterm-256color \
              LANG=en_US.UTF-8 \
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/var/cargo/bin:/usr/local/go/bin \
              /bin/bash -lc "echo LOGIN_SUCCESSFUL; echo TERMINAL_READY; exec /bin/bash -il"
        }
        """.trimIndent().replace("@SB@", sharedStorageBindLines)

        return """
        $common
        $installUbuntu
        $configureSources
        $loginUbuntu
        clear_lines
        start_shell(){
          install_ubuntu
          configure_sources
          sleep 1
          bump_progress
          login_ubuntu
        }
        """.trimIndent()
    }

    /**
     * 判断共享存储（/sdcard，即 /storage/emulated/0）是否可读。
     * 与 [QuroLinuxEnv.sharedStorageHostDir] 的权限闸门一致：
     * Android 11+ 需「所有文件访问」(MANAGE_EXTERNAL_STORAGE)；10- 需 READ/WRITE_EXTERNAL_STORAGE。
     */
    private fun sharedStorageAccessible(): Boolean {
        val accessible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            @Suppress("DEPRECATION")
            (appContext.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED) ||
            @Suppress("DEPRECATION")
            (appContext.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
        if (!accessible) return false
        val f = android.os.Environment.getExternalStorageDirectory()
        return f != null && f.canRead()
    }

    // ==================== 以下为兼容 API（墨语言 ⇢ Ubuntu 融合通道等外部调用方） ====================
    // 发行版抽象已收敛为唯一 Ubuntu；参数保留 `distro: Distro = Distro.UBUNTU` 形状仅为兼容既有调用点。

    /** rootfs 目录（proot-distro 布局）。 */
    fun rootfsDir(distro: Distro = Distro.UBUNTU): File =
        File(File(usrDir, "var/lib/proot-distro"), "installed-rootfs/ubuntu")

    /** rootfs 是否已解压可用（bash 实际位置 + 发行版身份文件）。
     *  bash 可能在 /bin/bash 或 /usr/bin/bash，两种路径都查。
     *  与 v1.1.7 common.sh 里 install_ubuntu 的就绪口径一致（bash + os-release|issue.net）。 */
    fun isRootfsReady(distro: Distro = Distro.UBUNTU): Boolean {
        val root = rootfsDir(distro)
        val hasBash = File(root, "bin/bash").exists() || File(root, "usr/bin/bash").exists()
        val hasIdentity = File(root, "etc/os-release").exists() || File(root, "etc/issue.net").exists()
        return hasBash && hasIdentity
    }

    /** rootfs 安装包是否还在本地（还在 = 尚未解压，可自动初始化）。 */
    fun hasRootfsArchive(distro: Distro = Distro.UBUNTU): Boolean =
        File(filesDir, UBUNTU_FILENAME).exists()

    /**
     * 是否正在解压（rootfs 内存在 `.extracting` 标记）。
     *
     * 🔴 2026-10-27 v1.1.7 回归后恒为 false：install_ubuntu（v1.1.7 原文）不写该标记。
     * 方法保留是为兼容 HEAD 期调用点（guestEnvStatus 的 INSTALLING 分支不会命中，
     * 解压期间表现为 NEEDS_INSTALL，解压完成即 READY）。
     */
    fun isExtracting(distro: Distro = Distro.UBUNTU): Boolean {
        val mark = File(rootfsDir(distro), EXTRACT_MARK)
        if (!mark.exists()) return false
        // 🔴 陈旧标记兜底（2026-10-22）：解压进程被杀（OOM/用户划掉 App）后标记会残留，
        // 若不判龄，UI 会**永久**停在「正在解压…」，用户以为卡死。正常解压 3-8 分钟，
        // 30 分钟仍未更新即视为陈旧，按「未在解压」处理（后续会走重装分支重新解压）。
        val ageMinutes = (System.currentTimeMillis() - mark.lastModified()) / 60_000L
        return ageMinutes < 30
    }

    /**
     * rootfs 关键文件体检：返回**缺失项**清单（空 = 完整）。
     *
     * 🔴 为什么需要它（2026-10-21 真机）：原来「就绪」只查 bash + os-release。
     * xz 解压被中途打断（超时/被杀）时，这两个文件往往已经落地 → 判 READY → 但
     * libpixman / libxcb / libXau / xkb / 字体全没解出来 → 桌面必崩且看不出原因。
     * 这里把「跑起来真正需要的」逐项列出，缺哪项一目了然。
     */
    fun rootfsMissingParts(distro: Distro = Distro.UBUNTU): List<String> {
        val root = rootfsDir(distro)
        if (!root.exists()) return listOf("rootfs 目录不存在")
        // 通用底线：能起 shell、能加载动态程序。
        // 🔴 libc 不能写进 must 列表：Ubuntu 的 libc.so.6 在 usr/lib/aarch64-linux-gnu 下，
        // 写死任一条都会让 rootfs 永远判"不完整"→ 无限循环解压。
        // 故下面单独用「两条路径任一」判定。
        val must = emptyList<String>()
        val missing = must.filter { !File(root, it).exists() }.toMutableList()
        // bash 两条路径任一存在即可
        val hasBash = File(root, "bin/bash").exists() || File(root, "usr/bin/bash").exists()
        if (!hasBash) missing.add("bin/bash 或 usr/bin/bash")
        // 动态链接器同样两条路径任一
        val hasLd = File(root, "lib/ld-linux-aarch64.so.1").exists() ||
            File(root, "usr/lib/ld-linux-aarch64.so.1").exists() ||
            File(root, "lib/ld-linux.so.2").exists() ||
            File(root, "usr/lib/ld-linux.so.2").exists()
        if (!hasLd) missing.add("ld-linux-aarch64.so.1（lib/ 或 usr/lib/）")
        // glibc 同样两条路径任一
        val hasLibc = File(root, "usr/lib/libc.so.6").exists() ||
            File(root, "usr/lib/aarch64-linux-gnu/libc.so.6").exists()
        if (!hasLibc) missing.add("libc.so.6（usr/lib 或 usr/lib/aarch64-linux-gnu）")
        return missing
    }

    /**
     * 残骸 rootfs 自愈守卫（2026-10-27）。
     *
     * 🔴 背景（真机实证）：v1.1.7 的 install_ubuntu 就绪判据是「目录非空即已安装」——
     * `if [ -z "$(ls -A $UBUNTU_PATH)" ]`。解压被中途掐断（v1.1.7 createNewSession 的
     * 30s 超时 closeSession 杀 Pty）时，tar 按字母序先落地 bin/bash 等靠前条目，后部的
     * usr/bin/id、var/lib/dpkg/status 还没解出 → 残骸目录非空 → 之后每次启动都判
     * 「已安装」→ 残骸永不自愈：
     *   · id 缺失 → /etc/profile 第 12 行 `id -u` 炸 → `-sh: 12: id: not found`、PS1 退化；
     *   · dpkg 数据库 / apt lists 缺失 → apt update 能跑完但 install 全部
     *     `E: Unable to locate package`（用户侧就是「能用但经常抽风」）。
     *
     * tarball 实证（check_tarball2.py）：19 个顶层条目、六项关键文件全部在包内，
     * 排除「包本身缺件」——残骸只能来自「解压被杀」，解法就是删掉让 install_ubuntu 重解压。
     *
     * 时序安全性：只在 initializeEnvironment（envInitMutex 串行 + isEnvInitialized once）
     * 内、extractAssets() 之后调用。此时 tarball 必在 filesDir（extractAssets 负责补拷，
     * install_ubuntu 解压后会 rm 掉它，下次进来又补回），而真正的解压发生在 Pty 起来之后
     * 的 common.sh install_ubuntu——本函数先于任何 Pty 执行，绝不可能删掉正在进行的解压。
     * execGuest 通道也先过 initializeEnvironment，一处守卫全覆盖。
     *
     * 瘦身包兜底：设备无 tarball 副本时（APK 故意不含 rootfs 资产）跳过自愈——删了就没源了。
     */
    private fun ensureRootfsComplete() {
        val root = rootfsDir()
        if (!root.isDirectory) return                 // 从未安装：install_ubuntu 全量解压
        if (root.listFiles().isNullOrEmpty()) return  // 空目录：同上
        val missing = mutableListOf<String>()
        // usrmerge 布局（/bin→usr/bin）：两条路径任一存在即算有，避免误判完整 rootfs。
        if (!(File(root, "bin/bash").exists() || File(root, "usr/bin/bash").exists())) {
            missing.add("bash")
        }
        if (!(File(root, "bin/id").exists() || File(root, "usr/bin/id").exists())) {
            missing.add("id")
        }
        if (!(File(root, "bin/apt").exists() || File(root, "usr/bin/apt").exists())) {
            missing.add("apt")
        }
        if (!File(root, "var/lib/dpkg/status").exists()) missing.add("var/lib/dpkg/status")
        if (!File(root, "etc/profile").exists()) missing.add("etc/profile")
        if (!File(root, "root/.bashrc").exists()) missing.add("root/.bashrc")
        if (missing.isEmpty()) return                 // 完整：不动
        if (!File(filesDir, UBUNTU_FILENAME).exists()) {
            Log.w(TAG, "rootfs 残骸（缺 $missing）但 tarball 缺失，跳过自愈（瘦身包场景）")
            return
        }
        Log.w(TAG, "rootfs 残骸自愈：缺 $missing → 删除 ${root.absolutePath}，下次启动重解压")
        if (!root.deleteRecursively()) {
            Log.e(TAG, "残骸 rootfs 删除失败，下次启动将重试：${root.absolutePath}")
        }
    }

    /** 判定 guest 环境可用性等级。 */
    fun guestEnvStatus(distro: Distro = Distro.UBUNTU): GuestEnvStatus = when {
        // 🔴 顺序铁律：「解压中」必须最先判。半截解压时 bash 常常已落地，若先判 READY 就会把
        // 正在解压的 rootfs 当成就绪（用户看到"就绪"但终端黑屏、桌面一启动就崩）。
        isExtracting(distro) -> GuestEnvStatus.INSTALLING
        // 🔴 就绪必须通过「关键文件体检」：只查 bash + os-release 是不够的——半截解压的 rootfs
        // 这两个恰好在，但 libpixman / libxcb / libXau / xkb / 字体全缺，结果就是「显示就绪、
        // 终端却黑屏、点启动桌面就报缺库」。缺件一律降级为 NEEDS_INSTALL（有包就能重解压修好）。
        isRootfsReady(distro) && rootfsMissingParts(distro).isEmpty() -> GuestEnvStatus.READY
        hasRootfsArchive(distro) -> GuestEnvStatus.NEEDS_INSTALL
        else -> GuestEnvStatus.ASSET_MISSING
    }

    /**
     * 执行信箱宿主目录。
     *
     * guest 内等价路径为 `/dev/shm/zxremote`（因为 `TMPDIR` 被 bind 到 guest 的 `/dev/shm`）。
     * 该 bind 在 [generateStartScript] 中**无条件**存在，不依赖存储权限，因此是宿主⇄guest 之间
     * 最稳的一条「文件信箱」通道：guest 写退出码/日志，宿主直接读。
     */
    fun mailboxHostDir(): File = File(File(filesDir, "tmp"), "zxremote").apply { mkdirs() }

    /**
     * 生成 Android 系统 CA 证书 bundle 到 filesDir/ca-bundle.crt（幂等），供 guest 内 curl/openssl
     * 走 HTTPS。内置 rootfs 自带 ca-certificates.crt（tarball 实证 219KB，v1.1.7 起即依赖它），
     * 本 bundle 是 execGuest 通道的兜底：宿主侧把 /system/etc/security/cacerts 下所有 .0
     * （Android 标准 CA 存储，哈希命名的 PEM 软链）拼成一个 PEM bundle，[execGuest] 导出
     * SSL_CERT_FILE/CURL_CA_BUNDLE 时若 guest 内证书缺失可手动补。宿主读不到 cacerts
     * （权限/不存在）时返回 null，不报错、不回归。
     */
    private fun ensureCaBundle(): File? {
        val out = File(filesDir, "ca-bundle.crt")
        if (out.exists() && out.length() > 0) return out
        return try {
            val cacerts = File("/system/etc/security/cacerts")
            if (!cacerts.isDirectory) return null
            val sb = StringBuilder()
            cacerts.listFiles { f -> f.isFile && f.name.endsWith(".0") }?.forEach { pem ->
                try { sb.append(pem.readText()).append('\n') } catch (_: Throwable) {}
            }
            if (sb.isEmpty()) return null
            out.writeText(sb.toString())
            Log.i(TAG, "ensureCaBundle: 生成 ${sb.length} 字节 CA bundle")
            out
        } catch (e: Exception) {
            Log.w(TAG, "ensureCaBundle 失败（非致命）", e)
            null
        }
    }

    /**
     * 宿主侧确保 rootfs 解压 + usrmerge 链接就绪（不开 proot）。
     *
     * 公开原因：墨语言 ⇢ Ubuntu 融合通道（MoyuUbuntu）要在工具链落地前先保证 Ubuntu 就绪，
     * 它与终端会话走同一条 install_ubuntu 链路（2026-10-27 回归 v1.1.7 函数名），目标固定为 Ubuntu。
     */
    fun prepareGuestRootfs(distro: Distro = Distro.UBUNTU): Boolean {
        val bash = File(binDir, "bash")
        if (!bash.exists()) {
            Log.e(TAG, "prepareGuestRootfs: 缺少宿主 bash（原生库未链接）")
            return false
        }
        // prepare 走 common.sh 的 install_ubuntu（v1.1.7 原文），目标固定 Ubuntu。
        return try {
            val cmd = "source \"\$HOME/common.sh\" && install_ubuntu && echo PREPARE_DONE"
            val pb = ProcessBuilder(bash.absolutePath, "-c", cmd)
            pb.directory(filesDir)
            pb.environment().putAll(baseEnv())
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val ok = p.waitFor(600_000, java.util.concurrent.TimeUnit.MILLISECONDS) && p.exitValue() == 0
            if (!ok) Log.w(TAG, "prepareGuestRootfs 失败:\n$out")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "prepareGuestRootfs 异常", e)
            false
        }
    }

    /**
     * 在 proot guest 内执行一次性脚本，返回真实退出码与输出。
     *
     * 与终端会话的命令队列/提示符检测**完全解耦**：
     *  1. 宿主侧先确保 rootfs 解压 + usrmerge 链接就绪（`prepareGuestRootfs`）；
     *  2. 直接以 [ProcessBuilder] 拉起 proot（照搬 QuroLinuxEnv，走 App 主进程上下文，ptrace 可用），
     *     不再经 Pty.start(bash → guest_exec → exec proot) 的旧链路（该链路上下文不同导致 proot 注入
     *     loader 时 PEEKDATA 失败）；
     *  3. proot 参数与 QuroLinuxEnv.buildProotLaunch 严格对齐（-0 -w /root 硬编码、同样 binds）。
     *
     * @param script guest 内要执行的 bash 脚本（可多行）
     * @param timeoutMs 超时上限；到期会尽力终止并标记 [GuestExecResult.timedOut]
     * @param onOutput 逐行回调（在读取线程上调用，实现方自行保证线程安全）
     */
    suspend fun execGuest(
        script: String,
        timeoutMs: Long,
        onOutput: ((String) -> Unit)? = null,
        logFile: File? = null,
        detach: Boolean = false,
        /**
         * 额外的 proot bind（如 `--bind=<宿主目录>:/opt/xxx`）。
         *
         * 墨语言用它把宿主侧的墨语言工具链、zig 编译器、原生库目录**挂进** Ubuntu，
         * 零拷贝、秒级，且不改动主 rootfs（符合「一律走增量，不覆盖整包」的规矩）。
         */
        extraBinds: List<String> = emptyList(),
    ): GuestExecResult = withContext(Dispatchers.IO) {
        if (!initializeEnvironment()) {
            return@withContext GuestExecResult(false, -1, "", false, "本地终端运行环境初始化失败")
        }
        // 🔴 照搬 QuroLinuxEnv（Ubuntu 通道）：proot 直接由 ProcessBuilder 拉起，走 App 主进程上下文，
        // ptrace/PEEKDATA 可用。旧链路经 Pty.start(bash → source common.sh → guest_exec → exec proot)
        // 起 proot，native createSubprocess 的上下文不同，导致 proot 注入 loader 时 PEEKDATA 失败。
        // 宿主侧预生成 CA 证书 bundle（幂等）：rootfs 自带 ca-certificates.crt，此 bundle 仅兜底。
        ensureCaBundle()
        // 先确保 rootfs 解压 + usrmerge 链接就绪（纯宿主侧文件操作，不开 proot）。
        if (!prepareGuestRootfs()) {
            return@withContext GuestExecResult(false, -1, "", false, "rootfs 解压/就绪失败（见 Log）")
        }

        val rootfsPath = rootfsDir().absolutePath
        val prootBin = File(nativeLibDir, "libproot.so").absolutePath
        val tmpDir = File(filesDir, "tmp").absolutePath
        // 🔴 getcwd 铁律：/root 必须真实存在（与 Ubuntu 登录通道一致，2026-10-26 起 /root 归还
        // rootfs 内自带家，共享 home 只挂 /root/shared）；proot_home 若缺失，bind 静默失败 ->
        // /root/shared 挂不上但不影响 /root 本身。这里在 Kotlin 侧显式保证其存在且可遍历，
        // 不依赖 init/createDirectories 的隐式副作用。
        val sharedHome = File(filesDir, "proot_home").apply {
            mkdirs()
            setReadable(true, false)
            setExecutable(true, false)
        }.absolutePath
        // 🔴 getcwd 兜底 v5 配套：bind 的目标挂载点必须在 rootfs 内真实存在，否则 proot 会**静默跳过**
        // 该 bind -> -w /root 落空 -> 又回到「bash 继承宿主 cwd」的老坑。这里宿主侧硬保证它存在且可遍历，
        // 不依赖 install_ubuntu 是否执行过 chmod（execGuest 通道不跑 common.sh 的登录函数）。
        runCatching {
            File(rootfsPath, "root").let { r ->
                r.mkdirs()
                r.setReadable(true, false)
                r.setExecutable(true, false)
            }
        }
        val loader = File(nativeLibDir, "libproot-loader.so").absolutePath

        // proot 参数：与 QuroLinuxEnv.buildProotLaunch 严格对齐（含 /dev/urandom→/dev/random、
        // /proc/net、filesDir:/quro），仅 rootfs/家目录换成共享 home。
        val args = mutableListOf(
            prootBin,
            "--rootfs=$rootfsPath",
            "--link2symlink",
            "--bind=/dev",
            "--bind=/dev/pts",      // 🔴 解锁 pty 分配：telnetd/sshd 需要 /dev/pts；common.sh 的 login_ubuntu 已有，execGuest 直连 proot 此前漏绑 → 远程登录服务启动即死。与 -b /dev 并列显式绑定 devpts 文件系统。
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc",
            "--bind=/proc/net:/proc/net",
            "--bind=/sys",
            "--bind=$sharedHome:/root/shared",   // 2026-10-26 /root 归属回归：共享 home 只挂 /root/shared（v1.1.7 形态，与 login_ubuntu 一致），/root 归还 rootfs 内自带家
            "--bind=$tmpDir:/tmp",
            "--bind=${filesDir.absolutePath}:/quro",
        )
        // 附加 bind（墨语言融合 Ubuntu：宿主侧工具链/编译器/原生库目录直接挂进 guest，零拷贝）。
        args.addAll(extraBinds)
        if (sharedStorageAccessible()) {
            val ext = android.os.Environment.getExternalStorageDirectory()
            if (ext != null) {
                args.add("--bind=${ext.absolutePath}:/sdcard")
                args.add("--bind=${ext.absolutePath}:${ext.absolutePath}")
            }
        }
        // 🔴 getcwd 兜底 v5（2026-10-20 真机事故）：guest /root 一旦翻译失败（rootfs 内 /root 缺失 /
        // 被 tar 里的 0700 挡住 / 历史 bind 被静默跳过），tracee 会继承宿主
        // cwd -> bash 启动即打
        //   shell-init: error retrieving current directory: getcwd: cannot access parent directories: Success
        // 然后**整个 -c 脚本一句都不执行**（真机界面只看到这一行红字，桌面永远起不来）。
        // 改用 `-w /`：guest 根 = 宿主 ROOTFS_PATH，映射恒成立、目录恒可遍历，getcwd 必成功；
        // 再在脚本首行 `cd /root`（失败退回 /），语义与 -w /root 完全等价但没有失败可能。
        // -0 仍保留（root 仿真，与 Ubuntu 三通道对齐）。
        args.add("-0")
        args.add("-w"); args.add("/")
        // Ubuntu 用 /bin/sh -c（非登录、不清环境）；经 usrmerge 链接 /bin→usr/bin 后
        // /bin/sh 即 /usr/bin/sh，存在且可用，无需 -i/-lc 登录壳（那正是旧链路 getcwd ENOENT 的温床）。
        // 🔴 解释器按 rootfs 实况选（2026-10-20）：rootfs 可能只有 usr/bin/sh 而
        // /bin → usr/bin 的 usrmerge 链接尚未建立。此时写死 /bin/sh 只会得到一个裸 ENOENT
        // （proot 报 execve("/bin/sh"): No such file or directory），界面无法区分是「解释器没了」
        // 还是「脚本内容错」。宿主侧直接看文件存在性，选不到才退回 /bin/sh（旧行为，不回归）。
        val guestSh = listOf("bin/sh", "usr/bin/sh", "bin/bash", "usr/bin/bash")
            .firstOrNull { File(rootfsPath, it).exists() }
            ?.let { "/$it" } ?: "/bin/sh"
        Log.i(TAG, "execGuest: guest shell=$guestSh（rootfs=$rootfsPath）")
        args.add(guestSh); args.add("-c")
        // cwd 前置：与上面的 `-w /` 配合，把「家在 /root」语义补回来。两段都不可失败，
        // 保证无论 guest /root 处于什么状态，脚本一定能在这个 shell 里跑起来。
        args.add("cd /root 2>/dev/null || cd /; " + script)

        val env = mutableMapOf(
            "HOME" to "/root",
            "PWD" to "/",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/rust/bin:/usr/local/go/bin",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "DEBIAN_FRONTEND" to "noninteractive",
            // 🔴 CA 证书：rootfs 自带 /etc/ssl/certs/ca-certificates.crt（tarball 实证 219KB），
            // 这里显式导出，保证 curl/openssl/wget 默认走它（HTTPS 开箱即用，无需手动 export）。
            "SSL_CERT_FILE" to "/etc/ssl/certs/ca-certificates.crt",
            "CURL_CA_BUNDLE" to "/etc/ssl/certs/ca-certificates.crt",
            // 🔴 2026-10-21 决定性修复：LD_LIBRARY_PATH 原来只含宿主 nativeLibDir + binDir，
            // **完全不含 guest 的库目录**。动态链接器优先查 LD_LIBRARY_PATH，查不到才落 ld.so.cache；
            // 而这个 rootfs 构建时 ldconfig 没跑（cache 不可靠），桌面/GTK 程序一律报
            // "cannot open shared object file"（真机现象：libpixman / libcairo / libgdk-3 / libm 全说缺，
            // 但包内实体都在）。这里把 guest 标准库目录全部追加，宿主路径对 guest 无意义但无害。
            "LD_LIBRARY_PATH" to "$nativeLibDir:${binDir.absolutePath}:" +
                "/usr/lib:/usr/lib/aarch64-linux-gnu:/usr/lib/fcitx5-libs:/lib:/lib/aarch64-linux-gnu:/usr/local/lib",
            "PROOT_TMP_DIR" to tmpDir,
            "PROOT_LOADER" to loader,
        )

        return@withContext try {
            val pb = ProcessBuilder(args)
            // 宿主 cwd 与 Ubuntu 通道（QuroLinuxEnv.buildProotLaunch 用 rootfs 父目录）保持一致：
            // -w / 已决定 guest cwd，这里只是让「万一翻译失败」时的继承路径也落在 rootfs 附近。
            pb.directory(File(rootfsPath).parentFile ?: filesDir)
            pb.environment().putAll(env)
            // 常驻服务（detach）：输出落到日志文件，不在管道里等 —— proot 进程继续活着，
            // 它的子进程（如 dsh 引擎）也随之常驻。
            if (logFile != null) {
                runCatching { logFile.parentFile?.mkdirs() }
                pb.redirectErrorStream(true)
                pb.redirectOutput(ProcessBuilder.Redirect.to(logFile))
            } else {
                pb.redirectErrorStream(true)
            }
            val p = pb.start()
            if (detach) {
                Log.i(TAG, "execGuest: 后台常驻已拉起，脚本=$script")
                return@withContext GuestExecResult(completed = true, code = 0, output = "", timedOut = false)
            }
            Log.i(TAG, "execGuest: proot 直接拉起（照搬 QuroLinuxEnv），rootfs=$rootfsPath")
            val captured = StringBuilder()
            val captureLock = Any()
            fun emit(line: String) {
                val safe = line.replace('\r', ' ').take(2000)
                synchronized(captureLock) { captured.append(safe).append('\n') }
                onOutput?.invoke(safe)
            }
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { emit(it) }
                    }
                } catch (_: Throwable) {
                    // 关流/进程退出时的正常异常，忽略
                }
            }.apply { isDaemon = true; name = "execGuest-reader"; start() }
            val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            val code = if (finished) p.exitValue() else {
                try { p.destroyForcibly() } catch (_: Throwable) {}
                -1
            }
            try { reader.join(2000) } catch (_: Throwable) {}
            val merged = synchronized(captureLock) { captured.toString() }
            Log.i(TAG, "execGuest: proot 执行完成 finished=$finished 退出码=$code")
            GuestExecResult(completed = true, code = code, output = merged, timedOut = !finished)
        } catch (e: Exception) {
            Log.e(TAG, "execGuest 失败", e)
            GuestExecResult(false, -1, "", false, e.message ?: "执行通道异常")
        }
    }

    /** 宿主侧启动子进程所需的环境变量（终端会话与一次性执行共用，避免两处漂移）。 */
    private fun baseEnv(): MutableMap<String, String> = mutableMapOf<String, String>().apply {
        this["PATH"] =
            "/system/bin:/system/xbin:${binDir.absolutePath}:${System.getenv("PATH") ?: ""}"
        this["HOME"] = filesDir.absolutePath
        this["PREFIX"] = usrDir.absolutePath
        this["TERMUX_PREFIX"] = usrDir.absolutePath
        // 🔴 同 buildProotLaunch：必须包含 guest 标准库目录，否则 guest 内 ld.so 只看到宿主路径，
        // 加上该 rootfs 的 ldconfig 未跑过 → 一律 "cannot open shared object file"。
        this["LD_LIBRARY_PATH"] = "${nativeLibDir}:${binDir.absolutePath}:" +
            "/usr/lib:/usr/lib/aarch64-linux-gnu:/usr/lib/fcitx5-libs:/lib:/lib/aarch64-linux-gnu:/usr/local/lib"
        this["PROOT_LOADER"] = File(binDir, "loader").absolutePath
        this["TMPDIR"] = File(filesDir, "tmp").absolutePath
        this["PROOT_TMP_DIR"] = File(filesDir, "tmp").absolutePath
        this["TERM"] = "xterm-256color"
        this["LANG"] = "en_US.UTF-8"
    }

    /** 公开：宿主侧启动子进程所需的目录（供 host 级服务拉 PTY）。 */
    val filesDirectory: File get() = filesDir

    /** 公开：宿主侧启动子进程所需的环境变量（终端会话与一次性执行共用）。 */
    fun baseEnvironment(): MutableMap<String, String> = baseEnv()

    /**
     * 确保 host 级服务可用：尽力做原生库链接 + busybox 软链（不碰 rootfs），
     * 并在 proot 后端需要但 common.sh 缺失时重建它。
     *
     * **注意：纯 Java 的 SSH 服务端（MINA）本就不依赖 busybox / proot**；原生库缺失
     * （未初始化 Ubuntu 环境）时只影响「Ubuntu 登录 shell」后端，不影响 SSH 服务本身。
     * 因此这里**绝不因 busybox 缺失而返回 false 阻断服务**——降级即可，让 SSH 永远能起。
     */
    fun ensureHostBackendReady(): Boolean {
        runCatching { linkNativeLibs() }.onFailure { Log.w(TAG, "linkNativeLibs 跳过", it) }
        runCatching { createBusyboxSymlinks() }.onFailure { Log.w(TAG, "createBusyboxSymlinks 跳过", it) }
        runCatching {
            if (isEnvInitialized && !File(filesDir, "common.sh").exists()) {
                File(filesDir, "common.sh").writeText(generateStartScript())
            }
        }
        return true
    }

    /** host 级 shell：Android 系统自带的 `/system/bin/sh`（真 shell，环境无关，无需 Ubuntu）。 */
    fun hostShellCommand(): Array<String> = arrayOf("/system/bin/sh")

    /**
     * 后端 shell：rootfs 就绪时进 proot 登录 shell，否则退化为 host 的 busybox sh。
     */
    fun backendShellCommand(): Array<String> {
        ensureHostBackendReady()
        return if (isRootfsReady()) {
            arrayOf(
                File(binDir, "bash").absolutePath, "-c",
                "source \$HOME/common.sh && install_ubuntu && configure_sources && login_ubuntu"
            )
        } else {
            hostShellCommand()
        }
    }

    /** 当前后端类型（用于界面展示）。 */
    fun backendLabel(): String =
        if (isRootfsReady()) "Ubuntu (proot)" else "Host (busybox)"

    /**
     * 🔴 启动自检（2026-10-26 真机复盘）：测试机是物理手机、经云电脑操作，抓不到 logcat，
     * 终端黑屏时零诊断信息。本函数把启动链每一步的**实查结果**写成文本，由
     * [com.ai.assistance.quro.terminal.TerminalManager] 在 Pty.start 前注入终端输出流
     * （READY 前也会渲染，见 OutputProcessor），用户截图即可定位断点。
     *
     * 覆盖项：ABI/架构、jniLibs 齐全性、bash/busybox 链接与实体可执行性、bash exec 实测
     * （能抓出 EACCES/ENOEXEC/ENOENT 这类「链在但跑不了」）、tarball 落盘完整性、
     * common.sh、rootfs 就绪/半截/锁/资产标记、剩余空间。
     */
    fun startupDiagnostics(): String {
        val sb = StringBuilder()
        fun line(s: String) { sb.append(s).append("\r\n") }
        line("==== ZorvAI 终端启动自检 B20261027-0010 ====")
        line("[1] os.arch=${System.getProperty("os.arch")} abis=${Build.SUPPORTED_ABIS.joinToString(",")} sdk=${Build.VERSION.SDK_INT}")
        val nld = File(nativeLibDir)
        line("[2] nativeLibDir=$nativeLibDir isDir=${nld.isDirectory}")
        if (nld.isDirectory) {
            val libs = (nld.listFiles() ?: emptyArray()).sortedBy { it.name }
            line("[2] jniLibs(${libs.size}): " + libs.joinToString(" ") { "${it.name}=${it.length()}" })
        }
        val bashFile = File(binDir, "bash")
        val linkTarget = runCatching { Files.readSymbolicLink(bashFile.toPath()) }.getOrNull()
        line("[3] bash exists=${bashFile.exists()} 符号链接目标=$linkTarget canExec=${bashFile.canExecute()}")
        val busyboxFile = File(binDir, "busybox")
        line("[3] busybox exists=${busyboxFile.exists()} canExec=${busyboxFile.canExecute()}")
        val execTest = runCatching {
            val pb = ProcessBuilder(bashFile.absolutePath, "-c", "echo EXEC_OK")
            pb.redirectErrorStream(true)
            pb.directory(filesDir)
            val p = pb.start()
            val finished = p.waitFor(8, TimeUnit.SECONDS)
            val out = runCatching { p.inputStream.bufferedReader().use { it.readText() }.trim() }.getOrDefault("")
            if (finished) "exit=${p.exitValue()} out='${out.take(120)}'"
            else { p.destroyForcibly(); "TIMEOUT(8s) out='${out.take(120)}'" }
        }.getOrElse { e -> "FAIL ${e.javaClass.simpleName}: ${e.message}" }
        line("[4] bash exec 实测: $execTest")
        val tb = File(filesDir, UBUNTU_FILENAME)
        line("[5] tarball exists=${tb.exists()} size=${tb.length()} (期望 64133552)")
        val cs = File(filesDir, "common.sh")
        val csLines = runCatching { cs.readLines().size }.getOrDefault(-1)
        // 🔴 bash -n 实解（2026-10-27 回归版新增）：2130 版真机报「line 433: syntax error:
        // unexpected end of file」而本地全绿，根因未定位。这条自检直接回答「设备上这份
        // common.sh 到底能不能解析」——OK 则问题在 exec/权限层，FAIL 则文件本身被写坏。
        val csSyntax = if (cs.exists()) runCatching {
            val pb = ProcessBuilder(bashFile.absolutePath, "-n", cs.absolutePath)
            pb.redirectErrorStream(true)
            pb.directory(filesDir)
            val p = pb.start()
            val finished = p.waitFor(8, TimeUnit.SECONDS)
            val out = runCatching { p.inputStream.bufferedReader().use { it.readText() }.trim() }.getOrDefault("")
            if (finished && p.exitValue() == 0) "OK" else "FAIL[${out.take(200)}]"
        }.getOrElse { e -> "ERR ${e.javaClass.simpleName}" } else "无文件"
        line("[6] common.sh exists=${cs.exists()} size=${cs.length()} lines=$csLines bash -n=$csSyntax")
        val rf = rootfsDir()
        val mark = File(rf, ".quro_asset_v")
        val lock = File(rf, ".extract_lock")
        line("[7] rootfs ready=${isRootfsReady()} extracting=${isExtracting()} 缺项=${rootfsMissingParts()}")
        line("[7] 资产标记='${runCatching { mark.readText().trim() }.getOrDefault("无")}' 解压锁存在=${lock.exists()} 顶层条目=${runCatching { rf.list()?.size }.getOrDefault(-1)}")
        line("[8] 存储: filesDir 可用=${filesDir.usableSpace / 1048576}MB / 总=${filesDir.totalSpace / 1048576}MB")
        line("==== 自检结束，以下为真实终端输出 ====")
        return sb.toString()
    }

    fun startTerminalSession(sessionId: String): Pair<TerminalSession, Pty> {
        val bash = File(binDir, "bash").absolutePath
        // 🔴 启动前硬校验（2026-10-26）：bash 链接缺失时进程会瞬间死亡且界面无任何提示，
        // 这里显式报错并把链接补上，让「终端没反应」有据可查。
        if (!File(bash).exists()) {
            Log.e(TAG, "startTerminalSession: binDir/bash 不存在（原生库未链接？），尝试补链")
            runCatching { linkNativeLibs() }
        }
        val startScript = "source \$HOME/common.sh && start_shell"

        val command = arrayOf(bash, "-c", startScript)

        val env = baseEnv()

        Log.d(TAG, "Starting terminal session with command: ${command.joinToString(" ")}")
        Log.d(TAG, "Environment: $env")

        val pty = Pty.start(command, env, filesDir)
        val session = TerminalSession(
            process = pty.process,
            stdout = pty.stdout,
            stdin = pty.stdin
        )
        activeSessions[sessionId] = session
        return Pair(session, pty)
    }

    fun closeTerminalSession(sessionId: String) {
        activeSessions[sessionId]?.let { session ->
            session.process.destroy()
            activeSessions.remove(sessionId)
            Log.d(TAG, "Closed and removed session: $sessionId")
        }
    }

    fun closeAllSessions() {
        activeSessions.keys.forEach { sessionId ->
            closeTerminalSession(sessionId)
        }
    }
}
