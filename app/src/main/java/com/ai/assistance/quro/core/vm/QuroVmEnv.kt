package com.ai.assistance.quro.core.vm

import android.content.Context
import android.util.Log
import java.io.File

/**
 * VM 终端后端能力探测 + 资产路径解析（Kalidroid 思路：AVF/pKVM 真内核 → QEMU 软虚拟化 → proot 兜底）。
 *
 * 三级能力（优先级从高到低）：
 *  1. AVF / pKVM：Android Virtualization Framework，跑**完整 Linux 内核**（最优，接近原生性能）。
 *     探测用系统特性 "android.software.virtualization_framework"（Android 14+），并用反射兜底探测
 *     android.system.virtualmachine.VirtualizationManager；实测设备若未开启 pKVM（多数消费级手机），
 *     框架特性仍在但 start() 会失败 —— 此时由 [QuroAvfLauncher.start] 捕获异常并降级 QEMU/proot。
 *  2. QEMU：软件虚拟化（TCG），无需 KVM/pKVM，几乎全机型可用；需预置 qemu-system-aarch64 + kernel + rootfs.tar.gz
 *     （rootfs 经 virtio-9p 挂为 guest 根，免去 qcow2 磁盘镜像与镜像工具）。
 *  3. proot：用户态模拟，由 [com.ai.assistance.quro.core.linux.QuroLinuxEnv] 兜底，无 VM 资产时终端依旧可用。
 *
 * 本类**只探测与解析路径，不下载/不安装**。QEMU 二进制、AVF VM payload、kernel、disk 等重资产
 * 由打包步骤放入 assets/vm/（见 assets/vm/README.md）；资产缺失时探测自然判为不可用并降级。
 */
object QuroVmEnv {
    private const val TAG = "QuroVmEnv"

    /** VM 后端类型（优先级从高到低）。 */
    enum class VmBackend { AVF, QEMU, NONE }

    /** VM 能力探测结果。 */
    data class VmStatus(
        val available: Boolean,
        val backend: VmBackend,
        val reason: String,
        val avfAvailable: Boolean = false,
        val qemuAvailable: Boolean = false,
    )

    /** VM 资产根目录（应用私有，类比 QuroLinuxEnv.sandboxDir）。 */
    fun vmDir(context: Context): File = File(context.filesDir, "vm").also { it.mkdirs() }

    /** 探测 VM 能力（不触发下载，纯探测）。 */
    fun probe(context: Context): VmStatus {
        val avf = probeAvf(context)
        if (avf) {
            Log.i(TAG, "✅ AVF/pKVM 框架可用")
            return VmStatus(true, VmBackend.AVF, "AVF/pKVM 可用（完整 Linux 内核）", avfAvailable = true)
        }
        val qemu = probeQemu(context)
        if (qemu) {
            Log.i(TAG, "✅ QEMU 可用")
            return VmStatus(true, VmBackend.QEMU, "QEMU 可用（软件虚拟化）", qemuAvailable = true)
        }
        Log.i(TAG, "⚠ VM 后端不可用，回退 proot 用户态 Linux")
        return VmStatus(false, VmBackend.NONE, "本机无 AVF/pKVM 且未预置 QEMU 资产，回退 proot", avfAvailable = false, qemuAvailable = false)
    }

    /** 便捷判断：是否存在任意 VM 后端。 */
    fun vmAvailable(context: Context): Boolean = probe(context).available

    /**
     * 按探测结果启动可用 VM 后端，返回桥接后的控制台 [QuroVmConsoleProcess]，
     * 任一后端不可用/启动失败返回 null（上层回退 proot）。
     */
    fun startConsole(context: Context): QuroVmConsoleProcess? {
        return when (probe(context).backend) {
            VmBackend.AVF -> QuroAvfLauncher.start(context)
            VmBackend.QEMU -> QuroQemuLauncher.start(context)
            VmBackend.NONE -> null
        }
    }

    /** 探测 AVF/pKVM：系统特性优先，反射 VirtualizationManager 兜底。 */
    private fun probeAvf(context: Context): Boolean {
        return try {
            if (context.packageManager.hasSystemFeature("android.software.virtualization_framework")) {
                Log.d(TAG, "AVF: 系统特性 android.software.virtualization_framework 存在")
                return true
            }
            // 反射兜底（部分 ROM 特性位未声明但框架类存在）
            val vmClass = Class.forName("android.system.virtualmachine.VirtualizationManager")
            runCatching {
                val get = vmClass.getMethod("get", Context::class.java)
                get.invoke(null, context) != null
            }.getOrDefault(false)
        } catch (e: Throwable) {
            Log.d(TAG, "AVF: 探测不可用: ${e.message}")
            false
        }
    }

    /** 探测 QEMU：qemu-system-aarch64 二进制可执行 + kernel 存在 + rootfs（tar 或已解压目录）存在。 */
    private fun probeQemu(context: Context): Boolean {
        val q = File(qemuPath(context))
        val k = File(kernelPath(context))
        val r = File(rootfsTarPath(context))
        val rd = rootfsDir(context)
        val ok = q.exists() && q.canExecute() && k.exists() && (r.exists() || rd.exists())
        Log.d(TAG, "QEMU: qemu=${q.exists()}/${q.canExecute()} kernel=${k.exists()} rootfsTar=${r.exists()} rootfsDir=${rd.exists()}")
        return ok
    }

    /** QEMU 二进制路径（nativeLibraryDir / assets/vm 回退）。 */
    fun qemuPath(context: Context): String =
        findVmBinary(context, "libqemu.so", "vm/qemu-system-aarch64")

    /** AVF VM payload（内置 payload APK）路径。 */
    fun avfPayloadPath(context: Context): String =
        findVmBinary(context, "libavf_vm_payload.so", "vm/vm_payload.apk")

    /** Linux kernel（供 QEMU/AVF 启动）路径。 */
    fun kernelPath(context: Context): String =
        findVmBinary(context, "libvm_kernel.so", "vm/Image")

    /** rootfs 压缩包（供 QEMU，经 9p 挂载；构建脚本产出，assets/vm/rootfs.tar.gz）。 */
    fun rootfsTarPath(context: Context): String =
        findVmBinary(context, "libvm_rootfs.so", "vm/rootfs.tar.gz")

    /** initramfs（供 QEMU，Alpine netboot initramfs-virt，含 9p 模块；可选）。 */
    fun initramfsPath(context: Context): String =
        findVmBinary(context, "libvm_initramfs.so", "vm/initramfs-virt")

    /** rootfs 解压目录（运行时首次从 assets/vm/rootfs 展开到这里）。 */
    fun rootfsDir(context: Context): File = File(vmDir(context), "rootfs")

    /**
     * 把 assets/vm/rootfs 一次性展开到可写目录 files/vm/rootfs（virtio-9p 需要可写源，
     * 而 APK 内 assets 只读）。已展开（存在 .quro_rootfs_ok 标记）则直接返回。
     * 返回可写 rootfs 目录；assets 未打包或为空返回 null。
     */
    fun ensureRootfsFromAssets(context: Context): File? {
        val dst = rootfsDir(context)
        if (File(dst, ".quro_rootfs_ok").exists() && dst.isDirectory) return dst
        return try {
            val top = context.assets.list("vm/rootfs") ?: return null
            if (top.isEmpty()) return null
            dst.mkdirs()
            copyAssetDir(context, "vm/rootfs", dst)
            File(dst, ".quro_rootfs_ok").createNewFile()
            Log.i(TAG, "✅ rootfs 已从 assets 展开到 ${dst.absolutePath}")
            dst
        } catch (e: Exception) {
            Log.w(TAG, "rootfs 展开失败: ${e.message}")
            null
        }
    }

    private fun copyAssetDir(context: Context, assetRel: String, dst: File) {
        val entries = context.assets.list(assetRel) ?: return
        if (entries.isEmpty()) {
            // 叶子：文件
            context.assets.open(assetRel).use { input ->
                java.io.FileOutputStream(File(dst, assetRel.substringAfterLast('/'))).use { out -> input.copyTo(out) }
            }
            return
        }
        for (name in entries) {
            val childDst = File(dst, name)
            val childAsset = "$assetRel/$name"
            if (context.assets.list(childAsset)?.isNotEmpty() == true) {
                childDst.mkdirs()
                copyAssetDir(context, childAsset, childDst)
            } else {
                context.assets.open(childAsset).use { input ->
                    java.io.FileOutputStream(childDst).use { out -> input.copyTo(out) }
                }
            }
        }
    }

    /** VM guest shell 环境（占位；externalProcess 模式下 ProcessBuilder 不消费，保留语义完整）。 */
    fun vmShellEnv(context: Context): Array<String> = arrayOf(
        "TERM=xterm-256color",
        "HOME=/root",
        "LANG=C.UTF-8",
    )

    /**
     * 解析 VM 原生资产：优先 nativeLibraryDir（APK 内 .so），缺失则从 assets/vm/ 解压。
     * 类比 QuroLinuxEnv.findNativeLibWithAssetsFallback，但资产可位于 assets/vm/ 子目录。
     */
    private fun findVmBinary(context: Context, libName: String, assetRelPath: String): String {
        val primary = File(context.applicationInfo.nativeLibraryDir, libName)
        if (primary.exists()) return primary.absolutePath
        val fileName = assetRelPath.substringAfterLast('/')
        val extracted = extractVmFromAssets(context, "vm/$fileName")
        if (extracted != null) return extracted.absolutePath
        // 资产未打包：返回预期路径（probe 据此判为不可用），不抛异常。
        return File(vmDir(context), fileName).absolutePath
    }

    private fun extractVmFromAssets(context: Context, assetName: String): File? {
        return try {
            val target = File(vmDir(context), assetName.substringAfterLast('/'))
            if (target.exists()) return target
            context.assets.open(assetName).use { input ->
                java.io.FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            target.setExecutable(true, false)
            Log.i(TAG, "✅ 从 assets 解压 $assetName -> ${target.absolutePath}")
            target
        } catch (e: Exception) {
            Log.d(TAG, "VM 资产 $assetName 未打包: ${e.message}")
            null
        }
    }
}
