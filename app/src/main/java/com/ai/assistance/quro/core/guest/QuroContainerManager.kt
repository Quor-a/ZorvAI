package com.ai.assistance.quro.core.guest

import android.content.Context
import android.os.Process as AndroidProcess
import android.util.Log
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import java.io.File

/**
 * 命名容器（rootfs）生命周期管理 —— 融合自 Cateners/tiny_container（已去品牌化）。
 *
 * tiny_container 用 proot + rootfs 压缩包在安卓上跑完整 Linux 容器，其 proot 启动范式
 * （PROOT_LOADER 环境变量 + .so 软链成 proot 二进制 + --link2symlink + boot 脚本 exec
 * + --assured-path lstat 缓存）已被证明在大量消费级机型可用。这里把该范式收编为
 * 「我方终端」的 proot 容器后端：当 VM 后端（pKVM / AVF / QEMU，见
 * [com.ai.assistance.quro.core.vm.QuroVmEnv]）不可用时，终端可走这里跑真实 Linux。
 *
 * 与 [QuroLinuxEnv]（单 rootfs 沙箱）的区别：本类支持「多个命名容器」，可导入
 * 任意 rootfs.tar.zst / rootfs.tar.gz，对应 tiny_container 的容器导入与管理能力。
 */
object QuroContainerManager {

    private const val TAG = "QuroContainerManager"

    /** proot 启动三元组：二进制路径 / 参数列表 / 环境变量。 */
    data class ProotLaunch(
        val proot: String,
        val args: List<String>,
        val env: Map<String, String>,
    )

    /** 容器根目录：filesDir/linux-containers/<name> */
    fun containerDir(context: Context, name: String): File =
        File(context.filesDir, "linux-containers/$name")

    /** 默认容器名（终端右窗格使用的 Kai 风格 / 当前终端均可指向它）。 */
    const val DEFAULT_CONTAINER = "quro"

    fun isProvisioned(context: Context, name: String = DEFAULT_CONTAINER): Boolean {
        val d = containerDir(context, name)
        return d.isDirectory && File(d, "bin/sh").exists()
    }

    /** 列出已配置的命名容器。 */
    fun listContainers(context: Context): List<String> {
        val base = File(context.filesDir, "linux-containers")
        if (!base.isDirectory) return emptyList()
        return base.listFiles()
            ?.filter { it.isDirectory && File(it, "bin/sh").exists() }
            ?.map { it.name }
            ?: emptyList()
    }

    /**
     * 导入容器：把 rootfs 压缩包解压进 [containerDir]。支持 tar.gz / tar.xz / tar.zst。
     *
     * 解压走 tiny_container 范式：优先用 proot 自带的 tar（libtar.so 软链成 tar 后
     * `proot --link2symlink tar -xf`），其次退回系统 tar；两者皆不可用时返回 false。
     * 解压完成后做 proot-distro 式 uid/gid 修复（去掉 aid_*、补当前用户条目），保证
     * 容器内 passwd/group 与宿主 uid 对齐，避免 proot 内 permission denied。
     *
     * @return 成功返回容器目录，失败返回 null。
     */
    fun importRootfs(context: Context, archive: File, name: String = DEFAULT_CONTAINER): File? {
        if (!archive.exists()) {
            Log.w(TAG, "importRootfs: 压缩包不存在 ${archive.absolutePath}")
            return null
        }
        val dir = containerDir(context, name)
        dir.deleteRecursively()
        dir.mkdirs()

        val proot = QuroLinuxEnv.prootPath(context)
        // 尝试用 proot 自带的 tar（tiny_container 把 tar 也打包成 .so 软链）
        val tarBin = findTarBinary(context)
        if (tarBin != null) {
            runHost("$proot --link2symlink $tarBin -xf ${archive.absolutePath} -C ${dir.absolutePath} --delay-directory-restore --preserve-permissions")
        } else {
            // 退回系统 tar（多数 ROM 不带，失败即 bin/sh 不存在 → 下方判失败）
            runHost("tar -xf ${archive.absolutePath} -C ${dir.absolutePath}")
        }
        if (!File(dir, "bin/sh").exists()) {
            Log.e(TAG, "importRootfs: 解压失败或 rootfs 不完整: ${dir.absolutePath}")
            return null
        }
        fixUidGid(context, dir)
        Log.i(TAG, "importRootfs: 容器 '$name' 就绪: ${dir.absolutePath}")
        return dir
    }

    /**
     * 构造 proot 启动参数 + 环境（tiny_container 范式）。
     * 命令由调用方追加（通常为 /bin/sh）。
     *
     * @return [ProotLaunch]；容器未就绪返回 null。
     */
    fun buildLaunch(context: Context, name: String = DEFAULT_CONTAINER): ProotLaunch? {
        val dir = containerDir(context, name)
        if (!File(dir, "bin/sh").exists()) {
            Log.w(TAG, "buildLaunch: 容器 '$name' 未就绪")
            return null
        }
        val proot = QuroLinuxEnv.prootPath(context)
        val home = QuroLinuxEnv.homePath(context)
        val tmp = QuroLinuxEnv.tmpPath(context)
        val loader = QuroLinuxEnv.loaderPath(context)
        val loader32 = findNativeLibWithFallback(context, "libproot-loader32.so")

        val args = mutableListOf(
            proot,
            "--rootfs=${dir.absolutePath}",
            "--link2symlink",
            "--bind=/dev",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=$home:/root",
            "--bind=$tmp:/tmp",
        )
        QuroLinuxEnv.sharedStorageHostDir(context)?.let { ss ->
            args.add("--bind=${ss.absolutePath}:/sdcard")
        }
        // tiny_container 的 lstat 缓存优化：把容器目录标为 assured-path，减少 proot 对宿主 fs 的 lstat 探活
        args.add("--assured-path=${dir.absolutePath}")
        args.add("-0")
        args.add("-w"); args.add("/root")
        args.add("/bin/sh"); args.add("-c")

        val env = mutableMapOf(
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "PROOT_TMP_DIR" to tmp,
            "PROOT_LOADER" to loader,
        )
        loader32?.let { env["PROOT_LOADER_32"] = it }
        return ProotLaunch(proot, args, env)
    }

    /**
     * 启动常驻 proot /bin/sh，返回 Process 供 [com.ai.assistance.quro.core.terminal.QuroShellSession]
     * 消费（读 stdout、写 stdin）。容器未就绪返回 null（上层回退 [QuroLinuxEnv]）。
     */
    fun launchSession(context: Context, name: String = DEFAULT_CONTAINER): Process? {
        val launch = buildLaunch(context, name) ?: return null
        return try {
            val pb = ProcessBuilder(launch.proot, *launch.args.toTypedArray(), "/bin/sh")
            pb.environment().putAll(launch.env)
            pb.redirectErrorStream(true)
            Log.i(TAG, "launchSession: 启动容器 '$name' proot 终端")
            pb.start()
        } catch (e: Exception) {
            Log.e(TAG, "launchSession: 容器 '$name' 启动失败", e)
            null
        }
    }

    /** 删除命名容器（保留 DEFAULT_CONTAINER 以外的均可清理）。 */
    fun removeContainer(context: Context, name: String): Boolean {
        if (name == DEFAULT_CONTAINER) {
            Log.w(TAG, "removeContainer: 不允许删除默认容器")
            return false
        }
        return containerDir(context, name).deleteRecursively()
    }

    // ===================== 内部工具 =====================

    /** 查找 proot 自带的 tar 二进制（libtar.so 软链成 tar）。找不到返回 null。 */
    private fun findTarBinary(context: Context): String? {
        val candidate = findNativeLibWithFallback(context, "libtar.so")
        if (candidate != null && File(candidate).exists()) return candidate
        // 退回系统 tar（极少）
        return if (runHost("command -v tar").isNotBlank()) "tar" else null
    }

    /** proot-distro 式 uid/gid 修复（去 aid_*，补当前用户）。直接用宿主 sh 改解压出的 host 侧文件。 */
    private fun fixUidGid(context: Context, rootfs: File) {
        val uid = AndroidProcess.myUid()
        // Android 上每个应用是独立用户，gid 通常与 uid 相同
        val gid = uid
        val user = try { runHost("id -un").trim() } catch (_: Throwable) { "u$uid" }
        val fixes = listOf(
            "sed -i '/^aid_/d' $rootfs/etc/passwd",
            "sed -i '/^aid_/d' $rootfs/etc/shadow",
            "sed -i '/^aid_/d' $rootfs/etc/group",
            "sed -i '/^aid_/d' $rootfs/etc/gshadow",
            "chmod u+rw $rootfs/etc/passwd $rootfs/etc/shadow $rootfs/etc/group $rootfs/etc/gshadow",
            "echo 'aid_$user:x:$uid:$gid:quro:/:/sbin/nologin' >> $rootfs/etc/passwd",
            "echo 'aid_$user:*:18446:0:99999:7:::' >> $rootfs/etc/shadow",
        )
        runHost(fixes.joinToString("; "))
    }

    /** 在宿主侧执行命令，返回 stdout（trim）。失败返回空串。 */
    private fun runHost(command: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out
    } catch (e: Throwable) {
        Log.w(TAG, "runHost 失败: ${e.message}")
        ""
    }

    /** 查找 nativeLibraryDir / assets 回退的 .so 路径（与 QuroLinuxEnv 同策略，局部副本避免跨包耦合）。 */
    private fun findNativeLibWithFallback(context: Context, libName: String): String? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val inNative = File(nativeDir, libName)
        if (inNative.exists()) return inNative.absolutePath
        return null
    }
}
