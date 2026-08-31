package com.ai.assistance.quro.core.vm

import android.content.Context
import android.util.Log
import java.io.File

/**
 * AVF / pKVM 启动器（反射绑定，避免编译期依赖 Android 15+ API）。
 *
 * 经 android.system.virtualmachine.VirtualizationManager 取 VirtualizationService，
 * 用 VirtualMachineConfig 启动内置 VM payload（Microdroid / 完整 Linux payload），
 * 再 connectVsock 取控制台串口并桥接为 [QuroVmConsoleProcess]。
 *
 * 任何 API 不匹配 / 类缺失 / 权限不足 → 返回 null，由上层降级 QEMU/proot。
 * 真实可用需：设备开启 pKVM + 声明并授予 MANAGE_VIRTUAL_MACHINE +
 * 预置 VM payload APK（assets/vm/vm_payload.apk，内含 vm_config.json 与 kernel/rootfs）。
 */
object QuroAvfLauncher {
    private const val TAG = "QuroAvfLauncher"
    private const val VM_NAME = "quro_vm"
    private const val CONSOLE_VSOCK_PORT = 23

    fun start(context: Context): QuroVmConsoleProcess? {
        val vm = try {
            val vmClass = Class.forName("android.system.virtualmachine.VirtualizationManager")
            val get = vmClass.getMethod("get", Context::class.java)
            get.invoke(null, context) ?: run {
                Log.w(TAG, "VirtualizationManager.get 返回 null")
                return null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "反射 VirtualizationManager 失败: ${e.message}")
            return null
        }

        return try {
            val payload = File(QuroVmEnv.avfPayloadPath(context))
            if (!payload.exists()) {
                Log.w(TAG, "AVF payload 缺失: ${payload.absolutePath}")
                return null
            }

            val configClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig")
            val boolType: Class<*> = Boolean::class.javaPrimitiveType as Class<*>
            val fromApk = configClass.getMethod(
                "fromApk", Context::class.java, File::class.java, String::class.java, boolType,
            )
            val config = fromApk.invoke(null, context, payload, "vm_config.json", false)

            val vmChildClass = Class.forName("android.system.virtualmachine.VirtualMachine")
            val getOrCreate = vm.javaClass.getMethod("getOrCreate", String::class.java, configClass)
            val machine = getOrCreate.invoke(vm, VM_NAME, config)

            vmChildClass.getMethod("start").invoke(machine)

            // connectVsock(port) -> VsockConnection（getInputStream / getOutputStream）
            val intType: Class<*> = Int::class.javaPrimitiveType as Class<*>
            val conn = vmChildClass.getMethod("connectVsock", intType).invoke(machine, CONSOLE_VSOCK_PORT)
            val connClass = conn.javaClass
            val inStream = connClass.getMethod("getInputStream").invoke(conn) as java.io.InputStream
            val outStream = connClass.getMethod("getOutputStream").invoke(conn) as java.io.OutputStream
            Log.i(TAG, "✅ AVF VM 控制台已连接 (vsock port $CONSOLE_VSOCK_PORT)")
            QuroVmConsoleProcess(inStream, outStream) {
                runCatching { vmChildClass.getMethod("stop").invoke(machine) }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "AVF VM 启动失败（降级 QEMU/proot）: ${e.message}")
            diag(context, "AVF VM 启动失败: ${e.message}")
            null
        }
    }

    /** 诊断日志写到 VM 资产目录（真机可用文件管理器取走，无需 adb）。 */
    private fun diag(context: Context, msg: String) {
        Log.e(TAG, msg)
        runCatching {
            File(QuroVmEnv.vmDir(context), "quro_avf_diag.log")
                .appendText("[${System.currentTimeMillis()}] $msg\n")
        }
    }
}
