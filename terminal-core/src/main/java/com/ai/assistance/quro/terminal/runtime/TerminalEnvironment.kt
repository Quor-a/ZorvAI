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
            "https://github.com/Quor-a/lanxiu-linux-android/releases/download/lanxiu-1.7.41-aarch64/lanxiu-aarch64-rootfs-20261017.tar.xz"
        /** 资产内容版本：改了内置 tar 包内容（如 rebrand 改 os-release、r10 proot 修复加 ld.so.cache）时 +1，
         *  强制设备重抽。仅靠 lastUpdateTime 无法捕捉「同版本号改内容」，会导致设备继续用旧解压 rootfs。
         *  本次 +1：烤入 Xtigervnc + 桌面依赖库 + misc 字体索引，支撑 in-app VNC 桌面。 */
        private const val ASSET_VERSION = 8

        /**
         * LANXIU 图形桌面（VNC）增量包：仅含 Xtigervnc + 15 个依赖库 + misc 字体索引，约 9.5MB。
         *
         * 🔴 为什么要单独做增量包（2026-10-19 真机事故根因）：
         * 把 Xtigervnc 烤进 455MB 主 rootfs 后，设备上已有的旧 rootfs 永远满足 `install_rootfs`
         * 的 `ready_check`（bash + os-release 都在），于是「换资产」只能靠把 455MB 整包
         * **覆盖解压到 8 万多个已存在文件上**——这在手机上要数分钟，而 `prepareGuestRootfs`
         * 只有 180s 上限：解压被杀 → 资产标记写不上 → 下次又从头解压 → **死循环，永远拿不到
         * Xtigervnc**。真机表现就是 `nohup: failed to run command 'Xtigervnc': No such file or directory`。
         *
         * 改为叠加式小包后：每次 exec 前只需检查 `/usr/bin/Xtigervnc` 是否存在，缺则解这 9.5MB
         * （秒级完成，远低于任何超时），老 av7 rootfs 也能就地补上，且完全不触碰主包。
         */
        private const val LANXIU_ADDON_FILENAME = "lanxiu-vnc-addon.tar.xz"
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
        // 双系统共享用户空间：app 拥有的公共 home，proot 启动时 bind 进两套 rootfs 的 /root。
        // LANXIU 与 Ubuntu 共用同一个 home/数据，仅 OS 层不同；且 app 拥有它 -> /root 永远可遍历，
        // 从根上消除 tar 包 agentuser/0700 的 /root 让 proot -w /root 进不去 -> getcwd 失败(exit 130)。
        File(filesDir, "proot_home").mkdirs()
        // 🔴 CA 证书 bundle：初始化阶段就生成（幂等），覆盖 exec 与交互两条通道——两条路径都会走
        // patch_lanxiu_rootfs 把 bundle 复制进内置 rootfs，确保 HTTPS 开箱即用，不依赖先跑哪条。
        ensureCaBundle()
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
            // 第三个是 LANXIU 图形桌面增量包（Xtigervnc + 依赖库 + 字体索引，~9.5MB）：
            // 单独成包是为了「老 rootfs 就地补文件」，避开 455MB 整包重解压的超时死循环（详见常量注释）。
            val assetNames = listOf(UBUNTU_FILENAME, Distro.LANXIU.rootfsFilename, LANXIU_ADDON_FILENAME)
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
            // versionChanged：App 重装(lastUpdateTime 变) 或 资产内容变更(ASSET_VERSION 升) 任一触发重抽。
            // 关键：rebrand 等改了 tar 包内容但不升 versionCode 时，仅靠 lastUpdateTime 不会重抽，
            // 导致设备继续用旧解压 rootfs（曾出现 status 仍显示 Qiyuan 而非 澜岫）。ASSET_VERSION +1 强制重抽。
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
        export SHARED_HOME=$homeDir/proot_home
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
        # LANXIU 扁平 rootfs：实测 tar 包里【根本没有】顶层 /lib /bin /sbin 成员，也没有任何
        # usrmerge 符号链接（只有 usr/lib / usr/bin / usr/sbin 等真实目录）。动态二进制 PT_INTERP
        # 写死 /lib/ld-linux-aarch64.so.1，但 loader 实际在 /usr/lib——不补这根链接，proot exec
        # 任何动态二进制都找不到 loader -> 报 ENOSYS（Function not implemented）崩。
        #（2026-10-17 真机事故：LANXIU 一执行命令就 proot error Function not implemented）。
        # 因此这根链接【必须每次 proot 启动前强制保证】，且要能对抗各种异常态：
        #   - 缺失（正常解压后）：补；
        #   - 被解压工具误建成空目录（busybox tar 在某些固件上会把 symlink 抽成空目录）：删空目录再补；
        #   - 已是正确符号链接：跳过（幂等）；
        #   - 非空目录（不该出现）：保留并告警，绝不强行破坏真内容。
        # Ubuntu 本就这结构，幂等无害。末尾自检 loader 是否可达，不可达时给出明确告警便于排查。
        fix_usrmerge_links(){
          fix_one(){
            d="${'$'}1"; t="${'$'}2"
            if [ -d "${'$'}ROOTFS_PATH/${'$'}t" ]; then
              cur="${'$'}ROOTFS_PATH/${'$'}d"
              need=1
              if [ -L "${'$'}cur" ]; then
                [ "${'$'}(busybox readlink "${'$'}cur" 2>/dev/null)" = "${'$'}t" ] && need=0
              fi
              if [ "${'$'}need" = "1" ]; then
                # 不是正确的符号链接：可能缺失，也可能被解压工具误建成目录/文件
                if [ -e "${'$'}cur" ] && [ ! -L "${'$'}cur" ]; then
                  if [ -d "${'$'}cur" ] && [ -z "${'$'}(busybox ls -A "${'$'}cur" 2>/dev/null)" ]; then
                    # 空目录：解压工具误建成空目录的典型情形，安全删掉再补
                    busybox rm -rf "${'$'}cur"
                  elif [ -d "${'$'}cur" ]; then
                    progress_echo "警告: /${'$'}d 是非空目录而非 usrmerge 链接，动态库可能无法加载"
                  fi
                fi
                if [ ! -e "${'$'}cur" ]; then
                  busybox ln -s "${'$'}t" "${'$'}cur" 2>/dev/null || ln -s "${'$'}t" "${'$'}cur" 2>/dev/null
                fi
              fi
            fi
          }
          fix_one lib usr/lib
          fix_one bin usr/bin
          fix_one sbin usr/sbin
          # 自检：loader 必须可达，否则 proot 启动会 ENOSYS（Function not implemented）
          if [ ! -e "${'$'}ROOTFS_PATH/lib/ld-linux-aarch64.so.1" ] && [ ! -e "${'$'}ROOTFS_PATH/lib/ld-linux.so.2" ]; then
            progress_echo "警告: 动态链接器 /lib/ld-linux 仍不可达，proot 可能无法启动"
          fi
        }
        # LANXIU 专用兜底补丁（2026-10-18 真机实测根因）：LANXIU 扁平 rootfs 与 Ubuntu 两处差异，
        # 必须每次 proot 启动前强制保证（ready_check 命中时 install_rootfs 的解压分支被跳过，旧逻辑
        # 就不补这些，导致重解压前一直缺）。exec 与交互两条通道共用 install_rootfs，故在此统一兜底：
        #  1) LANXIU 只有 /usr/bin/bash，没有 /usr/bin/sh（也无 dash）。execGuest/login_rootfs 调 /bin/sh
        #     （经 usrmerge /bin->usr/bin 即 /usr/bin/sh）缺失即 proot 起不来（/bin/sh not found）。补 sh->bash。
        #  2) tar 包保留 agentuser/0700 模式位 -> 各级目录 0700，挡 proot getcwd 向上遍历父链（实测
        #     chmod 0755 各级目录后 pwd/getcwd 才正常）。仅 chmod 目录（绝不动文件，保 sudo 等 setuid 位），
        #     并用隐藏标记文件避免每次 exec 全树扫描（重解压会清掉标记，自动重跑）。
        patch_lanxiu_rootfs(){
          cd "${'$'}ROOTFS_PATH" 2>/dev/null || return 0
          if [ ! -e usr/bin/sh ]; then
            busybox ln -sf bash usr/bin/sh 2>/dev/null || ln -sf bash usr/bin/sh 2>/dev/null
          fi
          if [ ! -e .lanxiu_patched_v1 ]; then
            busybox find . -type d -exec chmod 0755 {} + 2>/dev/null || find . -type d -exec chmod 0755 {} + 2>/dev/null
            : > .lanxiu_patched_v1 2>/dev/null
          fi
          # 🔴 CA 证书 bundle（宿主预先生成，落 ${'$'}HOME/ca-bundle.crt）：内置 LANXIU rootfs 原生缺 CA，
          # curl HTTPS 会证书校验失败。复制进 guest /etc/ssl/certs 并让 curl/openssl 默认走它。
          # 幂等：已存在则跳过。双上下文安全：guest(proot) 下 ${'$'}HOME=/root 无此文件 -> -f 不命中 ->
          # 跳过（此时证书已由宿主侧首次运行时复制进 rootfs，持久生效，扛住重解压）。
          if [ -f "${'$'}HOME/ca-bundle.crt" ] && [ ! -e etc/ssl/certs/ca-certificates.crt ]; then
            busybox mkdir -p etc/ssl/certs 2>/dev/null || mkdir -p etc/ssl/certs 2>/dev/null
            busybox cp -f "${'$'}HOME/ca-bundle.crt" etc/ssl/certs/ca-certificates.crt 2>/dev/null || cp -f "${'$'}HOME/ca-bundle.crt" etc/ssl/certs/ca-certificates.crt 2>/dev/null
            [ -e etc/ssl/certs/ca-certificates.crt ] && ln -sf ca-certificates.crt etc/ssl/cert.pem 2>/dev/null
          fi
        }
        """.trimIndent()

        // 解压：Ubuntu 的 tar 包顶层有 ubuntu-noble-aarch64/ 需拍平；LANXIU 是扁平 rootfs（无顶层目录）。
        // 两者都用 busybox tar xf（与既有 Ubuntu 路径一致，busybox tar 自动识别 xz）。
        val installRootfs = """
        install_rootfs(){
          mkdir -p "${'$'}ROOTFS_PATH" 2>/dev/null
          # 共享用户空间目录（app 拥有），供两套 rootfs 的 /root 共用；configure_sources 也会写这里。
          mkdir -p "${'$'}SHARED_HOME" 2>/dev/null
          # 就绪判定（与 Kotlin isRootfsReady 对齐）：bash 在 /bin 或 /usr/bin + 发行版身份文件。
          # 🔴 不能只看 etc/issue.net：Ubuntu 有 issue.net，但 LANXIU（独立 aarch64 rootfs）只有
          # etc/os-release / etc/lanxiu-release，压根没有 issue.net。用 issue.net 会把「已解压好的澜岫」
          # 永远判成未就绪 -> 每次会话都 rm -rf 重装，终端反复黑屏（2026-10-17 真机事故根因）。
          # 同时保留旧实现的强校验意图：bash + 身份文件双条件，避免「半截解压」被误判为可用。
          ready_check(){ { [ -e "${'$'}ROOTFS_PATH/bin/bash" ] || [ -e "${'$'}ROOTFS_PATH/usr/bin/bash" ]; } && { [ -e "${'$'}ROOTFS_PATH/etc/os-release" ] || [ -e "${'$'}ROOTFS_PATH/etc/issue.net" ]; }; }
          # 🔴 资产版本标记（2026-10-19 真机根因）：ready_check 只证明「曾经解压过」，不证明「解压的就是当前这份资产」。
          # 设备上旧的 av7 rootfs 永远满足 ready_check -> bump ASSET_VERSION 后新 tarball 只被拷进 filesDir、
          # 永不重解压 -> rootfs 里压根没有 Xtigervnc，真机报
          #   nohup: failed to run command 'Xtigervnc': No such file or directory
          # 故：解压成功后在 rootfs 内写 .quro_asset_v=av=N，此后「就绪」还须比对标记与当前内置版本。
          ASSET_VER=${ASSET_VERSION}
          ASSET_MARK="${'$'}ROOTFS_PATH/.quro_asset_v"
          asset_current(){ [ "`cat "${'$'}ASSET_MARK" 2>/dev/null`" = "av=${'$'}ASSET_VER" ]; }
          # 🔴 LANXIU 图形桌面增量件（Xtigervnc + 依赖库 + 字体索引，~9.5MB）：幂等，只在缺失时解。
          # 为什么不把 Xtigervnc 只放在主包：设备上已有的旧 rootfs 永远满足 ready_check，换资产就得把
          # 455MB 主包「覆盖解压到 8 万多个已存在文件」——手机要数分钟，而宿主侧 prepareGuestRootfs
          # 只有 180s 上限 -> 解压被 SIGKILL -> 标记写不上 -> 下次重头来，形成「永远升不上去」的死循环
          # （2026-10-19 真机二次根因）。小包秒级解完，老 av7 rootfs 也能就地补文件，且完全不碰主包。
          ADDON_TARBALL="${LANXIU_ADDON_FILENAME}"
          install_lanxiu_addon(){
            # 🔴 已在位（存在+可执行+非空）才直接返回；否则强制重解增量包补齐。
            # 之前用 [ -e ] 判断，若上一版被 180s 超时打断留下半截/零字节的 Xtigervnc，会被误判为
            # 已存在而跳过 -> 永远起不来（2026-10-20 真机现场：装了最新包仍失败的根因之一）。
            if [ -x "${'$'}ROOTFS_PATH/usr/bin/Xtigervnc" ] && [ -s "${'$'}ROOTFS_PATH/usr/bin/Xtigervnc" ]; then
              # 🔴 大小兜底：完整 Xtigervnc ≈ 2.9MB。若上一版被中断留下半截文件，虽非空且带可执行位，
              # 但体积远小于此 -> 仍判为损坏，强制重解增量包（而不是带着半截二进制继续跑，VNC 必崩）。
              XV_SZ=`wc -c < "${'$'}ROOTFS_PATH/usr/bin/Xtigervnc" 2>/dev/null || echo 0`
              if [ "${'$'}XV_SZ" -ge 2000000 ]; then
                return 0
              fi
            fi
            if [ ! -e ~/"${'$'}ADDON_TARBALL" ]; then
              # 设备没有增量包：若 Xtigervnc 已就位则放行，否则报错（提示需含增量包的包）。
              if [ -x "${'$'}ROOTFS_PATH/usr/bin/Xtigervnc" ]; then
                return 0
              fi
              progress_echo "LANXIU 桌面增量包缺失(${LANXIU_ADDON_FILENAME})，Xtigervnc 无法补齐"
              return 1
            fi
            progress_echo "补齐 LANXIU 桌面组件(Xtigervnc + 依赖库)..."
            if ! busybox tar xof ~/"${'$'}ADDON_TARBALL" -C "${'$'}ROOTFS_PATH/" 2>"${'$'}TMPDIR/addon_extract.err"; then
              progress_echo "增量包解压报错：${'$'}(tail -n 1 "${'$'}TMPDIR/addon_extract.err" 2>/dev/null)"
              return 1
            fi
            chmod 0755 "${'$'}ROOTFS_PATH/usr/bin/Xtigervnc" "${'$'}ROOTFS_PATH/usr/bin/tigervncconfig" 2>/dev/null
            if [ ! -x "${'$'}ROOTFS_PATH/usr/bin/Xtigervnc" ]; then
              progress_echo "增量包解压后仍未得到可用的 Xtigervnc"
              return 1
            fi
            progress_echo "LANXIU 桌面组件已就位(Xtigervnc)"
          }
          if ready_check; then
            if asset_current; then
              VERSION=`grep '^PRETTY_NAME=' "${'$'}ROOTFS_PATH/etc/os-release" 2>/dev/null | head -n 1 | cut -d= -f2- | tr -d '"'`
              [ -z "${'$'}VERSION" ] && VERSION=`cat "${'$'}ROOTFS_PATH/etc/issue.net" 2>/dev/null`
              progress_echo "rootfs ${'$'}L_INSTALLED -> ${'$'}VERSION"
            else
              # 🔴 不做整包重解压（见 install_lanxiu_addon 注释里的超时死循环）。标记直接更新，
              # 避免每次 exec 都重复走这条分支；主包内容变更由后续版本（或叠加包）承接。
              echo "av=${'$'}ASSET_VER" > "${'$'}ASSET_MARK" 2>/dev/null
              progress_echo "rootfs 资产标记更新 -> av=${'$'}ASSET_VER}(沿用现有 rootfs，增量件单独叠加)"
            fi
            # 无论主 rootfs 新旧，都幂等补齐桌面组件（缺才解，已有直接返回）。
            ${if (distro == Distro.LANXIU) "install_lanxiu_addon || true" else ":  # Ubuntu 不用 LANXIU 增量件"}
          else
            if [ -n "${'$'}(ls -A "${'$'}ROOTFS_PATH" 2>/dev/null)" ]; then
              progress_echo "rootfs 检测到半截解压，清理后重装..."
              rm -rf "${'$'}ROOTFS_PATH"/* 2>/dev/null
            fi
            # 清掉旧资产标记（glob 不清点文件）：解压成功后才重写，避免「半截解压 + 旧标记」被判为已就绪而跳过重装。
            rm -f "${'$'}ASSET_MARK" 2>/dev/null
            # 同理清掉补丁标记：重解压后必须让 patch_lanxiu_rootfs 重新全树 chmod 0755。
            rm -f "${'$'}ROOTFS_PATH/.lanxiu_patched_v1" 2>/dev/null
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
            # -o: 不保留 tar 包属主/属组，全部归当前（应用）进程所有 -> 应用可遍历整棵树，
            # 避免 tar 包里 agentuser/0700 的 /root 让 proot -w /root 进不去 -> getcwd 失败(exit 130)。
            if ! busybox tar xof ~/"${'$'}ROOTFS_TARBALL" -C "${'$'}ROOTFS_PATH/" 2>"${'$'}TMPDIR/rootfs_extract.err"; then
              progress_echo "rootfs 解压报错：${'$'}(tail -n 1 "${'$'}TMPDIR/rootfs_extract.err" 2>/dev/null)"
              return 1
            fi
            ${if (distro == Distro.UBUNTU) "mv \"${'$'}ROOTFS_PATH/${'$'}ROOTFS_NAME\"/* \"${'$'}ROOTFS_PATH/\" 2>/dev/null\n            rm -rf \"${'$'}ROOTFS_PATH/${'$'}ROOTFS_NAME\"" else ":  # LANXIU rootfs 已是扁平布局，无需拍平"}
            fix_usrmerge_links
            # 🔴 解压完整性校验（2026-10-18 实测根因之一）：解压残缺 -> guest 动态二进制 proot
            # 启动即 ENOSYS(Function not implemented)，进程极短命（进程采样器抓不到），表现为
            # 「LANXIU exec 不起新进程」。解压后强制核验 loader 与 bash 可达，任一缺失立即报错返回，
            # 把沉默的黑屏变成可见、可诊断的失败，而不是带着半截 rootfs 继续。
            if [ ! -e "${'$'}ROOTFS_PATH/lib/ld-linux-aarch64.so.1" ] && [ ! -e "${'$'}ROOTFS_PATH/lib/ld-linux.so.2" ]; then
              progress_echo "rootfs 解压不完整：动态链接器 /lib/ld-linux 仍不可达（usrmerge 链接缺失或解压残缺）"
              return 1
            fi
            if [ ! -e "${'$'}ROOTFS_PATH/usr/bin/bash" ] && [ ! -e "${'$'}ROOTFS_PATH/bin/bash" ]; then
              progress_echo "rootfs 解压不完整：guest bash 缺失"
              return 1
            fi
            echo "Extraction complete"
            # 解压完整性校验已过：写入资产版本标记，后续 ready_check 只认这个版本的 rootfs。
            echo "av=${'$'}ASSET_VER" > "${'$'}ASSET_MARK" 2>/dev/null
            # 防御：确保 rootfs 顶层对当前进程可遍历（动态库路径等）。
            chmod 0755 "${'$'}ROOTFS_PATH" 2>/dev/null
            # 共享用户空间：基础环境写进 SHARED_HOME/.bashrc（两套 rootfs 共用，幂等）。
            grep -q 'ANDROID_DATA' "${'$'}SHARED_HOME/.bashrc" 2>/dev/null || echo 'export ANDROID_DATA=/home/' >> "${'$'}SHARED_HOME/.bashrc"
            # 仅在校验通过后才删除安装包：避免「删除后被再次中断 -> 无包可重装」导致永久残废
            if ready_check; then
              rm -f ~/"${'$'}ROOTFS_TARBALL"
            else
              progress_echo "rootfs 解压校验失败，保留安装包以便重试"
              return 1
            fi
            # 主包（av8+）已含桌面组件；此处仍幂等检查一次，兼容「旧主包 + 新增量包」组合。
            ${if (distro == Distro.LANXIU) "install_lanxiu_addon || true" else ":  # Ubuntu 不用 LANXIU 增量件"}
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
          # 🔴 LANXIU 兜底补丁：无论新鲜解压还是 ready 跳过解压分支，都强制补 usrmerge 链接 +
          # sh 链接 + 目录权限（ready 命中时旧逻辑不跑 fix_usrmerge_links，这里统一兜底，覆盖 exec
          # 与交互两条通道，且扛住 App 更新/rootfs 重解压自动重跑）。
          fix_usrmerge_links
          patch_lanxiu_rootfs
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
          mkdir -p "${'$'}SHARED_HOME/.config/pip" 2>/dev/null
          echo '[global]' > "${'$'}SHARED_HOME/.config/pip/pip.conf"
          echo 'index-url = ${pipSource.url}' >> "${'$'}SHARED_HOME/.config/pip/pip.conf"
          mkdir -p "${'$'}SHARED_HOME/.config/uv" 2>/dev/null
          echo 'index-url = "${pipSource.url}"' > "${'$'}SHARED_HOME/.config/uv/uv.toml
          mkdir -p "${'$'}SHARED_HOME" 2>/dev/null
          echo 'registry=${npmSource.url}' > "${'$'}SHARED_HOME/.npmrc
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
          mkdir -p "${'$'}ROOTFS_PATH/storage/emulated"
          # 终端「公用挂载主目录」：应用自有外部目录，不依赖存储权限，始终可用
          mkdir -p "${'$'}ROOTFS_PATH/mnt/shared"
          # 🔴 共享用户空间（双系统铁律 2026-10-18）：LANXIU 与 Ubuntu 共用同一个 app 拥有的 home，
          # 仅 OS 层不同。把 SHARED_HOME bind 进 guest 的 /root -> 两套 rootfs 看到同一 home/数据，
          # 用户文件在双系统间互通，且 /root 永远 app 可遍历，从根消除 tar 包 agentuser/0700 致
          # proot -w /root 进不去 -> shell-init getcwd 失败(exit 130)。
          mkdir -p "${'$'}SHARED_HOME"
          mkdir -p "${'$'}SHARED_HOME/shared"
          # 🔴 getcwd 铁律（同 guest_exec）：强制保证 guest /root 真实存在，宿主先 cd guest 根。
          mkdir -p "${'$'}ROOTFS_PATH/root"
          chmod 0755 "${'$'}ROOTFS_PATH/root" 2>/dev/null || true
          cd "${'$'}ROOTFS_PATH"
          G_CWD=/root; G_HOME=/root
          resolve_guest_bash
          fix_usrmerge_links
          patch_lanxiu_rootfs
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
            -b "${'$'}SHARED_HOME":/root \
            -b "$sharedHostDir":/mnt/shared \
            @SB@
            -w "${'$'}G_CWD" \
            /usr/bin/env -i \
              HOME="${'$'}G_HOME" \
              TERM=xterm-256color \
              LANG=en_US.UTF-8 \
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/rust/bin:/usr/local/go/bin \
              SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt \
              CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt \
              ${'$'}GUEST_BASH -lc "echo LOGIN_SUCCESSFUL; ldconfig >/dev/null 2>&1 || true; echo TERMINAL_READY; exec ${'$'}GUEST_BASH -il"
        }
        """.trimIndent().replace("@SB@", sharedStorageBindLines)

        // 一次性执行入口：与 login_rootfs 同样的 proot 挂载，但**不进交互 shell**，
        // 而是直接 `bash -lc "$1"` 跑完就退出。用于「远程连接」这类需要可靠退出码的场景，
        // 与终端会话的提示符检测/命令队列完全解耦。
        // 额外点：TERM=dumb + DEBIAN_FRONTEND=noninteractive，避免 apt 弹交互式对话框卡死。
        val guestExec = """
        guest_exec(){
          mkdir -p "${'$'}ROOTFS_PATH/storage/emulated"
          mkdir -p "${'$'}ROOTFS_PATH/mnt/shared"
          # 🔴 共享用户空间（双系统铁律 2026-10-18）：同 login_rootfs，SHARED_HOME bind 进 /root。
          mkdir -p "${'$'}SHARED_HOME"
          mkdir -p "${'$'}SHARED_HOME/shared"
          mkdir -p "${'$'}TMPDIR/zxremote"
          # 🔴 getcwd 铁律（2026-10-18 真机事故）：LANXIU exec 通道 100% 因「proot -w 指向的 guest 目录
          # 翻译失败 -> tracee 继承宿主 cwd(filesDir) 且 guest 内无此路径 -> shell-init getcwd ENOENT -> exit 130」。
          # 三层兜底：① 宿主侧先 cd 进 guest 根(ROOTFS_PATH, -r 映射为 /, 永远可翻译)；
          # ② **强制保证 guest /root 真实存在**(主机 mkdir, 不依赖 bind/rootfs 是否自带) -> -w /root 必能 chdir；
          # ③ -w /root(必存在) + SHARED_HOME bind。任一失效仍有 guest / 可翻译, getcwd 不再 ENOENT。
          mkdir -p "${'$'}ROOTFS_PATH/root"
          chmod 0755 "${'$'}ROOTFS_PATH/root" 2>/dev/null || true
          cd "${'$'}ROOTFS_PATH"
          G_CWD=/root; G_HOME=/root
          resolve_guest_bash
          fix_usrmerge_links
          echo GUEST_EXEC_START
          # 🔴 与 Ubuntu 三通道（交互/sandbox/CMS）严格对齐：-0 -w /root 必须齐备。
          # 用户 2026-10-18 8000 次高频采样证实 Ubuntu 三通道全带 -0 -w /root；LANXIU 缺此即
          # 继承宿主 cwd / 无 root 仿真 -> getcwd ENOENT 或 proot 启动异常。G_CWD 恒为 /root。
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
            -b "${'$'}SHARED_HOME":/root \
            -b "$sharedHostDir":/mnt/shared \
            @SB@
            -w "${'$'}G_CWD" \
            /usr/bin/env -i \
              HOME="${'$'}G_HOME" \
              TERM=dumb \
              LANG=en_US.UTF-8 \
              DEBIAN_FRONTEND=noninteractive \
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/rust/bin:/usr/local/go/bin \
              SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt \
              CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt \
              ${'$'}GUEST_BASH -lc "ldconfig >/dev/null 2>&1 || true; ${'$'}1"
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
     * 照搬 QuroLinuxEnv 之前的 rootfs 就绪动作：纯宿主侧解压 + usrmerge 链接修复，不开 proot。
     * 复用 common.sh 的 install_rootfs / fix_usrmerge_links（二者只做文件操作/符号链接）。
     * 必须在 execGuest 直接拉起 proot 之前跑，否则 LANXIU 扁平 rootfs 缺少 /bin→usr/bin 链接会导致
     * guest 动态二进制 loader 找不到 -> proot ENOSYS。幂等：rootfs 已就绪时 install_rootfs 跳过解压。
     */
    /**
     * 生成 Android 系统 CA 证书 bundle 到 filesDir/ca-bundle.crt（幂等），供 guest 内 curl/openssl
     * 走 HTTPS。内置 LANXIU rootfs 原生缺 CA 证书，curl 访问 HTTPS 会证书校验失败；宿主侧把
     * /system/etc/security/cacerts 下所有 .0（Android 标准 CA 存储，哈希命名的 PEM 软链）拼成一个
     * PEM bundle，[patch_lanxiu_rootfs] 会把它复制进 guest /etc/ssl/certs，[execGuest] 再导出
     * SSL_CERT_FILE/CURL_CA_BUNDLE。宿主读不到 cacerts（权限/不存在）时返回 null，不报错、不回归。
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

    private fun prepareGuestRootfs(): Boolean {
        val bash = File(binDir, "bash")
        if (!bash.exists()) {
            Log.e(TAG, "prepareGuestRootfs: 缺少宿主 bash（原生库未链接）")
            return false
        }
        return try {
            val cmd = "source \"\$HOME/common.sh\" && install_rootfs && fix_usrmerge_links && echo PREPARE_DONE"
            val pb = ProcessBuilder(bash.absolutePath, "-c", cmd)
            pb.directory(filesDir)
            pb.environment().putAll(baseEnv())
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val ok = p.waitFor(180_000, java.util.concurrent.TimeUnit.MILLISECONDS) && p.exitValue() == 0
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
     *  3. proot 参数与 QuroLinuxEnv.buildProotLaunch 严格对齐（-0 -w /root 硬编码、同样 binds），
     *     仅 rootfs/家目录换成 LANXIU 的共享 home。
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
        // 🔴 照搬 QuroLinuxEnv（Ubuntu 通道）：proot 直接由 ProcessBuilder 拉起，走 App 主进程上下文，
        // ptrace/PEEKDATA 可用。旧链路经 Pty.start(bash → source common.sh → guest_exec → exec proot)
        // 起 proot，native createSubprocess 的上下文不同，导致 proot 注入 loader 时 PEEKDATA 失败。
        // 宿主侧预生成 CA 证书 bundle（幂等），供 patch_lanxiu_rootfs 复制进内置 rootfs，HTTPS 开箱即用。
        ensureCaBundle()
        // 先确保 rootfs 解压 + usrmerge 链接就绪（纯宿主侧文件操作，不开 proot）。
        if (!prepareGuestRootfs()) {
            return@withContext GuestExecResult(false, -1, "", false, "LANXIU rootfs 解压/就绪失败（见 Log）")
        }

        val rootfsPath = rootfsDir(selectedDistro).absolutePath
        val prootBin = File(nativeLibDir, "libproot.so").absolutePath
        val tmpDir = File(filesDir, "tmp").absolutePath
        // 🔴 getcwd 铁律：/root 必须 bind mount（与 Ubuntu 通道一致）getcwd 才正常；bind 的宿主目标
        // proot_home 若缺失，bind 静默失败 -> /root 退回 rootfs 内建目录 -> 内置目录 getcwd 翻译在
        // proot 5.0.0 下犯别扭（shell-init 警告、cd 报失真错误）。这里在 Kotlin 侧显式保证其存在且可遍历，
        // 不依赖 init/createDirectories 的隐式副作用。
        val sharedHome = File(filesDir, "proot_home").apply {
            mkdirs()
            setReadable(true, false)
            setExecutable(true, false)
        }.absolutePath
        val loader = File(nativeLibDir, "libproot-loader.so").absolutePath

        // proot 参数：与 QuroLinuxEnv.buildProotLaunch 严格对齐（含 /dev/urandom→/dev/random、
        // /proc/net、filesDir:/quro），仅 rootfs/家目录换成 LANXIU 的共享 home。
        val args = mutableListOf(
            prootBin,
            "--rootfs=$rootfsPath",
            "--link2symlink",
            "--bind=/dev",
            "--bind=/dev/pts",      // 🔴 解锁 pty 分配：telnetd/sshd 需要 /dev/pts；common.sh 的 guest_exec/login_rootfs 已有，execGuest 直连 proot 此前漏绑 → 远程登录服务启动即死。与 -b /dev 并列显式绑定 devpts 文件系统。
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc",
            "--bind=/proc/net:/proc/net",
            "--bind=/sys",
            "--bind=$sharedHome:/root",   // 双系统共享 home（app 拥有，/root 永远可遍历）
            "--bind=$tmpDir:/tmp",
            "--bind=${filesDir.absolutePath}:/quro",
        )
        if (sharedStorageAccessible()) {
            val ext = android.os.Environment.getExternalStorageDirectory()
            if (ext != null) {
                args.add("--bind=${ext.absolutePath}:/sdcard")
                args.add("--bind=${ext.absolutePath}:${ext.absolutePath}")
            }
        }
        // 🔴 cwd 硬编码 /root（Ubuntu 同款），绝不依赖变量——修旧链路 -w "$G_CWD" 变量可能落空的隐患。
        args.add("-0")
        args.add("-w"); args.add("/root")
        // Ubuntu 用 /bin/sh -c（非登录、不清环境）；LANXIU rootfs 经 usrmerge 链接 /bin→usr/bin 后
        // /bin/sh 即 /usr/bin/sh，存在且可用，无需 -i/-lc 登录壳（那正是旧链路 getcwd ENOENT 的温床）。
        args.add("/bin/sh"); args.add("-c")
        args.add(script)

        val env = mutableMapOf(
            "HOME" to "/root",
            "PWD" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/rust/bin:/usr/local/go/bin",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "DEBIAN_FRONTEND" to "noninteractive",
            // 🔴 CA 证书：patch_lanxiu_rootfs 已将宿主生成的 bundle 复制进 guest /etc/ssl/certs，
            // 这里显式导出，保证 curl/openssl/wget 默认走它（HTTPS 开箱即用，无需手动 export）。
            "SSL_CERT_FILE" to "/etc/ssl/certs/ca-certificates.crt",
            "CURL_CA_BUNDLE" to "/etc/ssl/certs/ca-certificates.crt",
            "LD_LIBRARY_PATH" to "$nativeLibDir:${binDir.absolutePath}",
            "PROOT_TMP_DIR" to tmpDir,
            "PROOT_LOADER" to loader,
        )

        return@withContext try {
            val pb = ProcessBuilder(args)
            pb.directory(filesDir)              // 宿主 cwd 无关紧要：proot -w /root 决定 guest cwd
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            val p = pb.start()
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

    /**
     * 可见交互终端（老栈 QuroShellSession 委托）启动命令。
     *
     * 仅当选中 [Distro.LANXIU] 且 rootfs 就绪（或 tar 包在、可 host 侧解压）时返回
     * `(command, env)`，复用 common.sh 的 start_shell（含 install_rootfs / login_rootfs /
     * patch_lanxiu_rootfs / 全部 usrmerge 修复 / CA / binds），与终端-core 新栈会话完全一致。
     * 其余情况（Ubuntu 或 LANXIU 未就绪）返回 null，调用方回退 QuroLinuxEnv 的 Ubuntu/设备路径。
     *
     * 复用而非重写：LANXIU 的全部运行修复都在 TerminalEnvironment 内闭环，老栈只负责把这条
     * `bash -c "source \$HOME/common.sh && start_shell"` 命令交给自己的 PTY/管道进程跑——两条
     * 栈共用同一份 rootfs 与同一套修复，永不分叉。
     */
    fun visibleTerminalLaunch(): Pair<Array<String>, Array<String>>? {
        if (selectedDistro != Distro.LANXIU) return null
        val bash = File(binDir, "bash")
        if (!bash.exists()) return null
        // 确保原生库软链 + CA bundle：proot/loader/bash 必须存在，否则即便 rootfs 就绪也起不来。
        runCatching { linkNativeLibs() }
        runCatching { createBusyboxSymlinks() }
        ensureCaBundle()
        // 确保内置 LANXIU tar 包已落到 filesDir（APK asset → 文件），消除「TerminalManager 首次实例化
        // 异步 extractAssets 尚未完成」的竞态；已抽过则 stamp 命中，本调用近乎零成本。
        runCatching { extractAssets() }
        // 确保 common.sh 为当前（LANXIU）版本（setDistro 已写；此处兜底切换后未初始化）。
        runCatching { File(filesDir, "common.sh").writeText(generateStartScript(selectedDistro)) }
        // LANXIU 仅在 rootfs 真正就绪（解压 + usrmerge 链接 + sh 链接）时才进 proot；
        // 仅存在 tar 包但未解压 → prepareGuestRootfs（host 侧）先解压，成功才放行，
        // 失败则回落 Ubuntu，避免「解压失败 → bash 退出 → 保活重建 → 再失败」死循环。
        if (!isRootfsReady(Distro.LANXIU)) {
            if (!hasRootfsArchive(Distro.LANXIU)) return null
            if (!prepareGuestRootfs()) return null
        }
        val command = arrayOf(bash.absolutePath, "-c", "source \$HOME/common.sh && start_shell")
        val env = baseEnv().map { "${it.key}=${it.value}" }.toTypedArray()
        return command to env
    }

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
