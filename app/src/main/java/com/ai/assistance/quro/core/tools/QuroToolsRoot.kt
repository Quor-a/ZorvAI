package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.shizuku.QuroShizuku
import org.json.JSONObject

/**
 * L4 ROOT 执行工具集（CapOS 最高风险通道）。
 *
 * 提供 su 级命令执行能力：
 *   - root_exec：直接以 su 执行命令
 *   - root_status：检测 ROOT 是否可用及类型（Magisk / KernelSU / KSU / 其他）
 *
 * ⚠️ ROOT 操作有最高风险，误操作可能损坏系统。
 * 工具调用前 AI 应先通过 priv_status 自查 L4 可用性。
 */

/** 以 ROOT 权限执行命令。优先走 Shizuku root exec，降级为 Runtime.exec(su)。 */
class RootExecTool : QuroTool {
    override val name = "root_exec"
    override val description = "以 ROOT 权限执行 shell 命令（最高风险通道！慎用）。优先使用 Shizuku root 通道，降级为 su 直调。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "command":{"type":"string","description":"要执行的 shell 命令（必填）"}
        },
        "required":["command"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val cmd = JSONObject(arguments).optString("command", "").ifBlank { return "❌ 缺少 command 参数" }
        // 优先尝试 Shizuku root（更稳定、输出完整）
        if (QuroShizuku.isReady) {
            val r = QuroShizuku.execAsRoot(cmd)
            if (!r.startsWith("❌")) return "[shizuku-root] $r"
        }
        // 降级：Runtime.exec su
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val err = p.errorStream.bufferedReader().use { it.readText() }
            val code = p.waitFor()
            val body = (out + err).trim()
            "exit=$code\n${if (body.isBlank()) "(无输出)" else body}"
        } catch (e: SecurityException) {
            "❌ ROOT 不可用：su 被拒绝（设备未 Root 或 Root 管理器未授权本 App）"
        } catch (e: Exception) {
            "❌ ROOT 执行失败: ${e.message}"
        }
    }
}

/** 检测 ROOT 状态与类型。 */
class RootStatusTool : QuroTool {
    override val name = "root_status"
    override val description = "检测设备是否已 ROOT 及 ROOT 类型（Magisk / KernelSU / KSU / 传统 su），无需参数 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        // 多重探测
        var method = ""
        var version = ""
        // 方法1: which su / system/xbin/su
        val suPaths = arrayOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/data/local/xbin/su",
            "/data/local/bin/su", "/system/sd/xbin/su", "/bin/su", "/magisk/.core/bin/su",
            "/debug_ramdisk/su", "/dev/su")
        for (path in suPaths) {
            if (java.io.File(path).exists()) { method += (if (method.isEmpty()) "" else ", ") + "su@$path"; break }
        }
        // 方法2: Magisk 探测
        if (java.io.File("/data/adb/magisk").exists()) {
            method = if (method.isEmpty()) "Magisk" else "$method + Magisk"
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "magisk -v"))
                version = p.inputStream.bufferedReader().use { it.readText() }.trim()
                p.waitFor()
            } catch (_: Exception) {}
        }
        // 方法3: KernelSU 探测
        if (java.io.File("/data/adb/ksud").exists() || java.io.File("/data/adb/ksu").exists()) {
            method = if (method.isEmpty()) "KernelSU" else "$method + KernelSU"
        }
        // 方法4: APatch 探测
        if (java.io.File("/data/local/tmp/apd").exists()) {
            method = if (method.isEmpty()) "APatch" else "$method + APatch"
        }
        // 实际 su 测试
        val hasSuAccess = try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo 'root_ok'"))
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            p.waitFor(); out == "root_ok"
        } catch (_: Exception) { false }

        val ver = version.trim()
        return org.json.JSONObject().apply {
            put("rooted", hasSuAccess)
            put("method", if (method.isEmpty()) "未检测到" else method)
            put("version", ver)
            put("note", if (hasSuAccess) "ROOT 访问可用，可使用 root_exec 工具" else "未获取 ROOT 或 Root 管理器未授权本 App")
        }.toString()
    }
}
