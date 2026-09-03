package com.ai.assistance.quro.terminal.runtime

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.ai.assistance.quro.terminal.Pty
import com.ai.assistance.quro.terminal.TerminalSession
import com.ai.assistance.quro.terminal.data.PackageManagerType
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

    private val activeSessions = ConcurrentHashMap<String, TerminalSession>()

    companion object {
        private const val TAG = "TerminalEnvironment"
        private const val UBUNTU_FILENAME = "ubuntu-noble-aarch64-pd-v4.18.0.tar.xz"
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
                    val startScript = generateStartScript()
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
            val assets = listOf(UBUNTU_FILENAME)
            assets.forEach { assetName ->
                val assetFile = File(filesDir, assetName)
                if (!assetFile.exists()) {
                    appContext.assets.open(assetName).use { input ->
                        assetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Extracted $assetName")
                } else {
                    Log.d(TAG, "Asset $assetName already exists.")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to extract assets", e)
            throw e
        }
    }

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

    fun startTerminalSession(sessionId: String): Pair<TerminalSession, Pty> {
        val bash = File(binDir, "bash").absolutePath
        val startScript = "source \$HOME/common.sh && start_shell"

        val command = arrayOf(bash, "-c", startScript)

        val env = mutableMapOf<String, String>()
        env["PATH"] = "${binDir.absolutePath}:${System.getenv("PATH")}"
        env["HOME"] = filesDir.absolutePath
        env["PREFIX"] = usrDir.absolutePath
        env["TERMUX_PREFIX"] = usrDir.absolutePath
        env["LD_LIBRARY_PATH"] = "${nativeLibDir}:${binDir.absolutePath}"
        env["PROOT_LOADER"] = File(binDir, "loader").absolutePath
        env["TMPDIR"] = File(filesDir, "tmp").absolutePath
        env["PROOT_TMP_DIR"] = File(filesDir, "tmp").absolutePath
        env["TERM"] = "xterm-256color"
        env["LANG"] = "en_US.UTF-8"

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
