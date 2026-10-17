package com.ai.assistance.quro.terminal.runtime

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.ai.assistance.quro.terminal.Pty
import com.ai.assistance.quro.terminal.TerminalSession
import com.ai.assistance.quro.terminal.data.PackageManagerType
import com.ai.assistance.quro.terminal.runtime.Distro
import com.ai.assistance.quro.terminal.utils.RootfsDownloader
import com.ai.assistance.quro.terminal.utils.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

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

    /** 当前选中的发行版（双系统切换），持久化在 SharedPreferences，默认 Ubuntu 以兼容老用户。 */
    private val distroPrefs = context.getSharedPreferences("terminal_distro", Context.MODE_PRIVATE)
    var selectedDistro: Distro = Distro.fromId(distroPrefs.getString("selected_distro", null))

    private val envInitMutex = Mutex()
    private var isEnvInitialized = false

    private val activeSessions = ConcurrentHashMap<String, TerminalSession>()

    companion object {
        private const val TAG = "TerminalEnvironment"
        private const val UBUNTU_FILENAME = "ubuntu-noble-aarch64-pd-v4.18.0.tar.xz"
        /** LANXIU（澜岫）安卓版 rootfs：本仓库 Release 标签（README 权威源）。 */
        const val LANXIU_DOWNLOAD_URL =
            "https://github.com/Quor-a/qiyuan-linux-android/releases/download/lanxiu-1.7.41-aarch64/lanxiu-aarch64-rootfs-20261017.tar.xz"
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
                    val startScript = generateStartScript(selectedDistro)
                    File(filesDir, "common.sh").writeText(startScript)
                    Log.d(TAG, "Environment initialization completed successfully.")
                    true
                } catch (e: Exception) {
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
    }

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

        try {
            Files.deleteIfExists(busybox.toPath())
            busyboxSo.setExecutable(true, false)
            Files.createSymbolicLink(busybox.toPath(), busyboxSo.toPath())
            if (!(busybox.exists() && busybox.canExecute())) {
                Log.e(TAG, "Verification failed: busybox link not functional after creation")
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create busybox link using Java NIO", e)
            return
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
            try {
                Files.deleteIfExists(linkFile.toPath())
                libFile.setExecutable(true, false)
                Files.createSymbolicLink(linkFile.toPath(), libFile.toPath())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create $linkName link using Java NIO", e)
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
            // Ubuntu 内置；LANXIU 亦内置（与 Ubuntu 同机制，不再首次按需下载）。
            val assetNames = listOf(UBUNTU_FILENAME, Distro.LANXIU.rootfsFilename)
            val stampFile = File(filesDir, ".assets.stamp")
            val prevStamp = runCatching { stampFile.readText() }.getOrDefault("")
            // 用 lastUpdateTime 而不是 versionCode：每次安装都会变，保证「同版本号重装」也能刷新资产。
            // 旧实现「exists() 即跳过」会让换了内容的新资产、以及被中断写坏的半截包永远留在设备上。
            val installStamp = runCatching {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime
            }.getOrDefault(0L)
            val prevStampV = Regex("^v=(\\d+)$", RegexOption.MULTILINE)
                .find(prevStamp)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            val versionChanged = prevStampV != installStamp

            val newStamp = StringBuilder("v=$installStamp\n")
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
                    appContext.assets.open(assetName).use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (dest.exists() && !dest.delete()) Log.w(TAG, "Cannot delete old $assetName")
                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true)
                        Files.deleteIfExists(tmp.toPath())
                    }
                    Log.d(TAG, "Copied asset $assetName -> ${dest.length()} bytes")
                } else {
                    Log.d(TAG, "Asset $assetName up to date (${dest.length()} bytes).")
                }
                newStamp.append(assetName).append('=').append(dest.length()).append('\n')
            }
            runCatching { stampFile.writeText(newStamp.toString()) }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to extract assets", e)
            throw e
        }
    }

    private fun generateStartScript(distro: Distro): String {
        val rootfsName = distro.rootfsFilename.replace(Regex("-pd.*"), "")
        val tmpDir = File(filesDir, "tmp").absolutePath
        val binDir = binDir.absolutePath
        val homeDir = filesDir.absolutePath
        val usrDir = usrDir.absolutePath
        val prootDistroPath = "$usrDir/var/lib/proot-distro"
        val rootfsPath = "$prootDistroPath/installed-rootfs/${distro.rootfsDirName}"

        val aptSource = sourceManager.getSelectedSource(PackageManagerType.APT)
        val pipSource = sourceManager.getSelectedSource(PackageManagerType.PIP)
        val npmSource = sourceManager.getSelectedSource(PackageManagerType.NPM)

        val common = """
        export TMPDIR=$tmpDir
        export BIN=$binDir
        export HOME=$homeDir
        export ROOTFS_PATH=$rootfsPath
        export ROOTFS_TARBALL=${distro.rootfsFilename}
        export ROOTFS_NAME=$rootfsName
        export DISTRO_ID=${distro.id}
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
        # 解析 guest 内 bash 真实路径：独立 aarch64 rootfs（如 LANXIU）bash 在 /usr/bin/bash，
        # 而 Ubuntu(usrmerge) 在 /bin/bash。两者都要兼容，否则 LANXIU 登录会找不到 /bin/bash 而失败。
        resolve_guest_bash(){
          if [ -e "${'$'}ROOTFS_PATH/bin/bash" ]; then GUEST_BASH=/bin/bash
          elif [ -e "${'$'}ROOTFS_PATH/usr/bin/bash" ]; then GUEST_BASH=/usr/bin/bash
          else GUEST_BASH=/bin/bash; fi
        }
        # LANXIU 扁平 rootfs 是 usrmerge 风格：库/二进制全在 /usr，顶层缺 /bin /lib /sbin。
        # 动态二进制 PT_INTERP 写死 /lib/ld-linux-aarch64.so.1，但 loader 实际在 /usr/lib；
        # 不补这根链接，proot exec 任何动态二进制都找不到 loader -> execve/chdir 报 ENOSYS 崩
        #（2026-10-17 真机事故：LANXIU 一执行命令就 proot error Function not implemented）。
        # 补标准 usrmerge 链接（Ubuntu 本就这结构，幂等无害）。必须在每次 proot 启动前保证存在。
        fix_usrmerge_links(){
          [ -d "${'$'}ROOTFS_PATH/usr/lib" ] && [ ! -e "${'$'}ROOTFS_PATH/lib" ] && busybox ln -s usr/lib "${'$'}ROOTFS_PATH/lib" 2>/dev/null
          [ -d "${'$'}ROOTFS_PATH/usr/bin" ] && [ ! -e "${'$'}ROOTFS_PATH/bin" ] && busybox ln -s usr/bin "${'$'}ROOTFS_PATH/bin" 2>/dev/null
          [ -d "${'$'}ROOTFS_PATH/usr/sbin" ] && [ ! -e "${'$'}ROOTFS_PATH/sbin" ] && busybox ln -s usr/sbin "${'$'}ROOTFS_PATH/sbin" 2>/dev/null
        }
        """.trimIndent()

        // 解压：Ubuntu 的 tar 包顶层有 ubuntu-noble-aarch64/ 需拍平；LANXIU 是扁平 rootfs（无顶层目录）。
        // 两者都用 busybox tar xf（与既有 Ubuntu 路径一致，busybox tar 自动识别 xz）。
        val installRootfs = """
        install_rootfs(){
          mkdir -p "${'$'}ROOTFS_PATH" 2>/dev/null
          # 就绪判定（与 Kotlin isRootfsReady 对齐）：bash 在 /bin 或 /usr/bin + 发行版身份文件。
          # 🔴 不能只看 etc/issue.net：Ubuntu 有 issue.net，但 LANXIU（独立 aarch64 rootfs）只有
          # etc/os-release / etc/lanxiu-release，压根没有 issue.net。用 issue.net 会把「已解压好的澜岫」
          # 永远判成未就绪 -> 每次会话都 rm -rf 重装，终端反复黑屏（2026-10-17 真机事故根因）。
          # 同时保留旧实现的强校验意图：bash + 身份文件双条件，避免「半截解压」被误判为可用。
          ready_check(){ { [ -e "${'$'}ROOTFS_PATH/bin/bash" ] || [ -e "${'$'}ROOTFS_PATH/usr/bin/bash" ]; } && { [ -e "${'$'}ROOTFS_PATH/etc/os-release" ] || [ -e "${'$'}ROOTFS_PATH/etc/issue.net" ]; }; }
          if ready_check; then
            VERSION=`grep '^PRETTY_NAME=' "${'$'}ROOTFS_PATH/etc/os-release" 2>/dev/null | head -n 1 | cut -d= -f2- | tr -d '"'`
            [ -z "${'$'}VERSION" ] && VERSION=`cat "${'$'}ROOTFS_PATH/etc/issue.net" 2>/dev/null`
            progress_echo "rootfs ${'$'}L_INSTALLED -> ${'$'}VERSION"
          else
            if [ -n "${'$'}(ls -A "${'$'}ROOTFS_PATH" 2>/dev/null)" ]; then
              progress_echo "rootfs 检测到半截解压，清理后重装..."
              rm -rf "${'$'}ROOTFS_PATH"/* 2>/dev/null
            fi
            if [ ! -e ~/"${'$'}ROOTFS_TARBALL" ]; then
              progress_echo "rootfs 安装包缺失，无法安装"
              return 1
            fi
            progress_echo "rootfs ${'$'}L_NOT_INSTALLED, ${'$'}L_INSTALLING..."
            # 空间预检：澜岫解压后约 460MB（Ubuntu 约 280MB）。空间不够先给明确提示，
            # 好过 tar 解到一半失败只剩黑屏。
            FREE_KB=`df -k "${'$'}HOME" 2>/dev/null | tail -n 1 | awk '{print ${'$'}4}'`
            if [ -n "${'$'}FREE_KB" ] && [ "${'$'}FREE_KB" -lt 614400 ] 2>/dev/null; then
              progress_echo "rootfs 解压需约 600MB 可用空间，当前仅 ${'$'}((FREE_KB / 1024))MB，请先清理设备存储"
              return 1
            fi
            # 解压错误不再静默吞掉（原来 >/dev/null 2>&1，坏了只看到黑屏）：错误落盘 + 回显末行。
            if ! busybox tar xf ~/"${'$'}ROOTFS_TARBALL" -C "${'$'}ROOTFS_PATH/" 2>"${'$'}TMPDIR/rootfs_extract.err"; then
              progress_echo "rootfs 解压报错：${'$'}(tail -n 1 "${'$'}TMPDIR/rootfs_extract.err" 2>/dev/null)"
            fi
            ${if (distro == Distro.UBUNTU) "mv \"${'$'}ROOTFS_PATH/${'$'}ROOTFS_NAME\"/* \"${'$'}ROOTFS_PATH/\" 2>/dev/null\n            rm -rf \"${'$'}ROOTFS_PATH/${'$'}ROOTFS_NAME\"" else ":  # LANXIU rootfs 已是扁平布局，无需拍平"}
            fix_usrmerge_links
            echo "Extraction complete"
            echo 'export ANDROID_DATA=/home/' >> "${'$'}ROOTFS_PATH/root/.bashrc"
            # 仅在校验通过后才删除安装包：避免「删除后被再次中断 -> 无包可重装」导致永久残废
            if ready_check; then
              rm -f ~/"${'$'}ROOTFS_TARBALL"
            else
              progress_echo "rootfs 解压校验失败，保留安装包以便重试"
              return 1
            fi
          fi
          # DNS：写入多 nameserver 兜底（国内/海外通用）。LANXIU 原生缺失 resolv.conf，
          # 仅靠此行保证开箱 DNS 可用；rootfs 内也烤入了一份默认 resolv.conf 作双保险。
          cat > "${'$'}ROOTFS_PATH/etc/resolv.conf" <<'EOF'
nameserver 8.8.8.8
nameserver 1.1.1.1
nameserver 223.5.5.5
nameserver 119.29.29.29
EOF
          # 解压后自检：若目录仍为空（tar 包缺失或解压失败），报错退出，避免 proot 挂空目录。
          if [ -z "${'$'}(ls -A "${'$'}ROOTFS_PATH" 2>/dev/null)" ]; then
            progress_echo "rootfs 解压失败：请先确认安装包完整"
            return 1
          fi
        }
        """.trimIndent()

        // 源配置：仅 Debian/Ubuntu 系写 apt/pip/npm；澜岫 用自家 qypkg，留空函数。
        val configureSources = """
        configure_sources(){
          ${if (distro.isDebian) """
          cat <<'EOF' > "${'$'}ROOTFS_PATH/etc/apt/sources.list"
        # From ZorvAI Settings - ${aptSource.name}
        deb ${aptSource.url} noble main restricted universe multiverse
        deb ${aptSource.url} noble-updates main restricted universe multiverse
        deb ${aptSource.url} noble-backports main restricted universe multiverse
        EOF
          mkdir -p "${'$'}ROOTFS_PATH/root/.config/pip" 2>/dev/null
          echo '[global]' > "${'$'}ROOTFS_PATH/root/.config/pip/pip.conf"
          echo 'index-url = ${pipSource.url}' >> "${'$'}ROOTFS_PATH/root/.config/pip/pip.conf"
          mkdir -p "${'$'}ROOTFS_PATH/root/.config/uv" 2>/dev/null
          echo 'index-url = "${pipSource.url}"' > "${'$'}ROOTFS_PATH/root/.config/uv/uv.toml
          mkdir -p "${'$'}ROOTFS_PATH/root" 2>/dev/null
          echo 'registry=${npmSource.url}' > "${'$'}ROOTFS_PATH/root/.npmrc
          """ else ":  # 澜岫 不需要 Debian 源配置"}
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

        val loginRootfs = """
        login_rootfs(){
          mkdir -p "${'$'}ROOTFS_PATH/storage/emulated" 2>/dev/null
          # 终端「公用挂载主目录」：应用自有外部目录，不依赖存储权限，始终可用
          mkdir -p "${'$'}ROOTFS_PATH/mnt/shared" 2>/dev/null
          mkdir -p "${'$'}ROOTFS_PATH/root/shared" 2>/dev/null
          resolve_guest_bash
          fix_usrmerge_links
          exec ${'$'}BIN/proot \
            -0 \
            -r "${'$'}ROOTFS_PATH" \
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
              SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt \
              CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt \
              ${'$'}GUEST_BASH -lc "echo LOGIN_SUCCESSFUL; echo TERMINAL_READY; exec ${'$'}GUEST_BASH -il"
        }
        """.trimIndent().replace("@SB@", sharedStorageBindLines)

        // 一次性执行入口：与 login_rootfs 同样的 proot 挂载，但**不进交互 shell**，
        // 而是直接 `bash -lc "$1"` 跑完就退出。用于「远程连接」这类需要可靠退出码的场景，
        // 与终端会话的提示符检测/命令队列完全解耦。
        // 额外点：TERM=dumb + DEBIAN_FRONTEND=noninteractive，避免 apt 弹交互式对话框卡死。
        val guestExec = """
        guest_exec(){
          mkdir -p "${'$'}ROOTFS_PATH/storage/emulated" 2>/dev/null
          mkdir -p "${'$'}ROOTFS_PATH/mnt/shared" 2>/dev/null
          mkdir -p "${'$'}ROOTFS_PATH/root/shared" 2>/dev/null
          mkdir -p "${'$'}TMPDIR/zxremote" 2>/dev/null
          resolve_guest_bash
          fix_usrmerge_links
          echo GUEST_EXEC_START
          exec ${'$'}BIN/proot \
            -0 \
            -r "${'$'}ROOTFS_PATH" \
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
              TERM=dumb \
              LANG=en_US.UTF-8 \
              DEBIAN_FRONTEND=noninteractive \
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/var/cargo/bin:/usr/local/go/bin \
              SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt \
              CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt \
              ${'$'}GUEST_BASH -lc "${'$'}1"
        }
        """.trimIndent().replace("@SB@", sharedStorageBindLines)

        return """
        $common
        $installRootfs
        $configureSources
        $loginRootfs
        $guestExec
        clear_lines
        start_shell(){
          install_rootfs
          configure_sources
          sleep 1
          bump_progress
          login_rootfs
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

    /** 某发行版的 rootfs 目录（proot-distro 布局，按 rootfsDirName 区分）。 */
    fun rootfsDir(distro: Distro = selectedDistro): File =
        File(File(usrDir, "var/lib/proot-distro"), "installed-rootfs/${distro.rootfsDirName}")

    /** rootfs 是否已解压可用（bash 实际位置 + 发行版身份文件）。
     *  注意：独立 aarch64 rootfs（如 LANXIU）bash 在 /usr/bin/bash 而非 /bin/bash（无 /bin→/usr/bin 合并），
     *  因此两种路径都要查，否则解压成功也永远判「未就绪」。
     *  身份文件同样不能只认 issue.net：Ubuntu 有，澜岫只有 os-release / lanxiu-release。
     *  与 common.sh 里 ready_check 保持完全一致的判定口径（bash + os-release|issue.net）。 */
    fun isRootfsReady(distro: Distro = selectedDistro): Boolean {
        val root = rootfsDir(distro)
        val hasBash = File(root, "bin/bash").exists() || File(root, "usr/bin/bash").exists()
        val hasIdentity = File(root, "etc/os-release").exists() || File(root, "etc/issue.net").exists()
        return hasBash && hasIdentity
    }

    /** rootfs 安装包是否还在本地（还在 = 尚未解压，可自动初始化）。 */
    fun hasRootfsArchive(distro: Distro = selectedDistro): Boolean =
        File(filesDir, distro.rootfsFilename).exists()

    /** 判定 guest 环境可用性等级（按发行版）。 */
    fun guestEnvStatus(distro: Distro = selectedDistro): GuestEnvStatus = when {
        isRootfsReady(distro) -> GuestEnvStatus.READY
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
     * 在 proot guest 内执行一次性脚本，返回真实退出码与输出。
     *
     * 与终端会话的命令队列/提示符检测**完全解耦**：
     *  1. 宿主侧先 `source common.sh`（幂等，rootfs 没解压就顺手解压）再 `guest_exec`；
     *  2. 真正的工作脚本写成文件，由 guest 内 `run.sh` 执行，输出 `tee` 到日志文件，
     *     收尾把真实退出码写进 `code` 文件；
     *  3. 宿主**以 code 文件出现作为完成信号**（不是以 proot 进程退出为准），
     *     因此即使脚本派生了 sshd / Xvnc 这类常驻进程也不会误判为「一直安装中」。
     *
     * @param script guest 内要执行的 bash 脚本（可多行）
     * @param timeoutMs 超时上限；到期会尽力终止并标记 [GuestExecResult.timedOut]
     * @param onOutput 逐行回调（在读取线程上调用，实现方自行保证线程安全）
     */
    suspend fun execGuest(
        script: String,
        timeoutMs: Long,
        onOutput: ((String) -> Unit)? = null,
    ): GuestExecResult = withContext(Dispatchers.IO) {
        if (!initializeEnvironment()) {
            return@withContext GuestExecResult(false, -1, "", false, "本地终端运行环境初始化失败")
        }
        val bash = File(binDir, "bash")
        if (!bash.exists()) {
            return@withContext GuestExecResult(false, -1, "", false, "缺少宿主 bash（原生库未链接）")
        }
        val jobId = "job_" + java.lang.Long.toString(System.currentTimeMillis(), 36) +
            "_" + (0..0xFFFF).random().toString(16)
        val jobDir = File(mailboxHostDir(), jobId)
        try {
            if (!jobDir.mkdirs() && !jobDir.isDirectory) {
                return@withContext GuestExecResult(false, -1, "", false, "无法创建执行信箱目录")
            }
            val codeFile = File(jobDir, "code")
            val logFile = File(jobDir, "log")
            File(jobDir, "task.sh").writeText(script)
            File(jobDir, "run.sh").writeText(
                "#!/bin/bash\n" +
                    "D=\"/dev/shm/zxremote/$jobId\"\n" +
                    "rm -f \"\$D/code\"\n" +
                    "bash \"\$D/task.sh\" 2>&1 | tee \"\$D/log\"\n" +
                    "echo \"\${PIPESTATUS[0]}\" > \"\$D/code\"\n"
            )

            val prelude = "source \"\$HOME/common.sh\" && install_rootfs && configure_sources && guest_exec \"\$1\""
            val guestCmd = "bash /dev/shm/zxremote/$jobId/run.sh"
            val command = arrayOf(bash.absolutePath, "-c", prelude, "zxremote", guestCmd)

            val pty = try {
                Pty.start(command, baseEnv(), filesDir)
            } catch (e: Exception) {
                Log.e(TAG, "execGuest: 无法启动 proot 通道", e)
                return@withContext GuestExecResult(
                    false, -1, "", false, "无法启动 proot 通道：${e.message}"
                )
            }

            val captured = StringBuilder()
            val captureLock = Any()
            fun emit(line: String) {
                val safe = line.replace('\r', ' ').take(2000)
                synchronized(captureLock) { captured.append(safe).append('\n') }
                onOutput?.invoke(safe)
            }

            val reader = Thread {
                try {
                    pty.stdout.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { emit(it) }
                    }
                } catch (_: Throwable) {
                    // 关流/进程退出时的正常异常，忽略
                }
            }.apply { isDaemon = true; name = "zxremote-reader"; start() }

            var exitCode = Int.MIN_VALUE
            val waiter = Thread {
                try {
                    exitCode = pty.waitFor()
                } catch (_: Throwable) {
                }
            }.apply { isDaemon = true; name = "zxremote-wait"; start() }

            val deadline = System.currentTimeMillis() + timeoutMs
            var timedOut = false
            while (!codeFile.exists()) {
                if (System.currentTimeMillis() >= deadline) {
                    timedOut = true
                    break
                }
                if (!waiter.isAlive) {
                    // 进程已退出：给文件系统一点落盘时间，再判定一次
                    delay(250)
                    break
                }
                delay(120)
            }

            // 收尾：优先让 proot 自然退出（守护进程已脱壳，不应被信号误伤），
            // 仅在仍未退出时才强杀。
            val graceEnd = System.currentTimeMillis() + 3000
            while (waiter.isAlive && System.currentTimeMillis() < graceEnd) delay(100)
            if (waiter.isAlive) {
                try {
                    pty.destroy()
                } catch (_: Throwable) {
                }
            } else {
                pty.closeQuietly()
            }
            try {
                reader.join(1200)
            } catch (_: InterruptedException) {
            }

            val completed = codeFile.exists()
            val code = if (completed) {
                codeFile.readText().trim().toIntOrNull() ?: -1
            } else {
                exitCode.takeIf { it != Int.MIN_VALUE } ?: -1
            }
            val log = if (logFile.exists()) {
                runCatching { logFile.readText() }.getOrDefault("")
            } else {
                ""
            }
            val merged = if (log.isNotBlank()) log else synchronized(captureLock) { captured.toString() }
            if (!completed) {
                Log.w(TAG, "execGuest 未拿到回执: jobId=$jobId timedOut=$timedOut exit=$exitCode")
            }
            GuestExecResult(
                completed = completed,
                code = code,
                output = merged,
                timedOut = timedOut,
            )
        } catch (e: Exception) {
            Log.e(TAG, "execGuest 失败", e)
            GuestExecResult(false, -1, "", false, e.message ?: "执行通道异常")
        } finally {
            runCatching { jobDir.deleteRecursively() }
        }
    }

    /** 宿主侧启动子进程所需的环境变量（终端会话与一次性执行共用，避免两处漂移）。 */
    private fun baseEnv(): MutableMap<String, String> = mutableMapOf<String, String>().apply {
        this["PATH"] =
            "/system/bin:/system/xbin:${binDir.absolutePath}:${System.getenv("PATH") ?: ""}"
        this["HOME"] = filesDir.absolutePath
        this["PREFIX"] = usrDir.absolutePath
        this["TERMUX_PREFIX"] = usrDir.absolutePath
        this["LD_LIBRARY_PATH"] = "${nativeLibDir}:${binDir.absolutePath}"
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
     * 确保 host 级服务可用：尽力做原生库链接 + busybox 软链（不碰 Ubuntu rootfs），
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
                File(filesDir, "common.sh").writeText(generateStartScript(selectedDistro))
            }
        }
        return true
    }

    /** host 级 shell：Android 系统自带的 `/system/bin/sh`（真 shell，环境无关，无需 Ubuntu）。 */
    fun hostShellCommand(): Array<String> = arrayOf("/system/bin/sh")

    /** 切换发行版：持久化偏好 + 重建 common.sh（让后续启动/后端用新目标）。 */
    suspend fun setDistro(distro: Distro): Boolean {
        selectedDistro = distro
        distroPrefs.edit().putString("selected_distro", distro.id).apply()
        runCatching { File(filesDir, "common.sh").writeText(generateStartScript(distro)) }
        return true
    }

    /** 首次使用按需下载 LANXIU rootfs（约 88MB），落盘到 filesDir/<filename>。 */
    suspend fun downloadLanxiuRootfs(
        onProgress: ((downloaded: Long, total: Long, fraction: Float) -> Unit)? = null
    ): Boolean {
        val dest = File(filesDir, Distro.LANXIU.rootfsFilename)
        if (dest.exists()) return true
        return RootfsDownloader.download(LANXIU_DOWNLOAD_URL, dest, onProgress)
    }

    /**
     * 后端 shell：rootfs 就绪时进 proot 登录 shell（按选中发行版），否则退化为 host 的 busybox sh。
     */
    fun backendShellCommand(): Array<String> {
        ensureHostBackendReady()
        return if (isRootfsReady(selectedDistro)) {
            arrayOf(
                File(binDir, "bash").absolutePath, "-c",
                "source \$HOME/common.sh && install_rootfs && configure_sources && login_rootfs"
            )
        } else {
            hostShellCommand()
        }
    }

    /** 当前后端类型（用于界面展示）。 */
    fun backendLabel(): String =
        if (isRootfsReady(selectedDistro)) "${selectedDistro.displayName} (proot)" else "Host (busybox)"

    fun startTerminalSession(sessionId: String): Pair<TerminalSession, Pty> {
        val bash = File(binDir, "bash").absolutePath
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
