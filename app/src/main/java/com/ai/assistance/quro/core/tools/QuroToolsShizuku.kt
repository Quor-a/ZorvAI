package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.policy.QuroPolicy
import com.ai.assistance.quro.core.policy.QuroPolicyStore
import com.ai.assistance.quro.core.shizuku.QuroShizuku
import org.json.JSONObject

/**
 * L2 Shizuku 执行工具集（CapOS 通道）。
 *
 * 通过 Shizuku 的 ADB 级 IPC 执行系统命令：
 *   - shizuku_exec：以 Shell UID 执行任意命令
 *   - shizuku_root_exec：以 Root 执行命令（需 Shizuku root 模式）
 *   - freeze_app：冻结/解冻应用（免 Root 停用应用，需 Shizuku）
 *   - install_app：静默安装 APK
 *   - shizuku_status：查询 Shizuku 连接状态与版本信息
 */

/** 经 Shizuku 执行 Shell 命令。 */
class ShizukuExecTool : QuroTool {
    override val name = "shizuku_exec"
    override val description = "经 Shizuku 以 ADB/Shell 权限执行命令（比普通 Runtime.exec 更高权限）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "command":{"type":"string","description":"要执行的 shell 命令"}
        },
        "required":["command"]
    }"""
    override fun run(context: Context, arguments: String): String {
        if (!QuroShizuku.isReady) return "❌ Shizuku 未就绪：请到 CapOS 权限子系统 → L2 Shizuku → 请求授权并确保 Shizuku 应用正在运行"
        val cmd = JSONObject(arguments).optString("command", "").ifBlank { return "❌ 缺少 command 参数" }
        return QuroShizuku.exec(cmd)
    }
}

/** 经 Shizuku 以 Root 执行命令（需 Shizuku 以 root 模式运行）。 */
class ShizukuRootExecTool : QuroTool {
    override val name = "shizuku_root_exec"
    override val description = "经 Shizuku 以 Root 权限执行命令（需设备已 Root 且 Shizuku 以 root 模式运行）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "command":{"type":"string","description":"要执行的 shell 命令"}
        },
        "required":["command"]
    }"""
    override fun run(context: Context, arguments: String): String {
        if (!QuroShizuku.isReady) return "❌ Shizuku 未就绪"
        val cmd = JSONObject(arguments).optString("command", "").ifBlank { return "❌ 缺少 command 参数" }
        // BUG-E 修复：权限模式=禁止（DENY）时，root 命令必须被策略拦截（与 root_exec 一致）。
        if (QuroPolicyStore.getPriv(context) == QuroPolicy.DENY) {
            return "❌ 权限模式为「禁止」，shizuku_root_exec 已被策略拦截。请到 CapOS 权限子系统 → 权限模式 调整为允许/询问后再执行。"
        }
        return QuroShizuku.execAsRoot(cmd)
    }
}

/** 冻结/解冻指定包名的应用。 */
class FreezeAppTool : QuroTool {
    override val name = "freeze_app"
    override val description = "冻结或解冻指定应用（冻结后应用不驻内存、不收推送）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "package_name":{"type":"string","description":"目标应用的包名"},
            "action":{"type":"string","enum":["freeze","unfreeze"],"description":"freeze=冻结 / unfreeze=解冻（默认 freeze）"}
        },
        "required":["package_name"]
    }"""
    override fun run(context: Context, arguments: String): String {
        if (!QuroShizuku.isReady) return "❌ Shizuku 未就绪"
        val args = JSONObject(arguments)
        val pkg = args.optString("package_name", "").ifBlank { return "❌ 缺少 package_name" }
        val action = args.optString("action", "freeze")
        // 使用 pm 命令实现冻结/解冻（Android 9+）
        val cmd = when (action) {
            "unfreeze" -> "pm enable $pkg"
            else -> "pm disable-user --user 0 $pkg"
        }
        val result = QuroShizuku.exec(cmd)
        return when {
            result.contains("exit=0") -> "✅ 已${if (action == "freeze") "冻结" else "解冻"} $pkg"
            result.contains("new user") || result.contains("Unknown package") ->
                "⚠️ 包不存在或不支持对当前用户操作: $result"
            else -> "❌ 操作失败: $result"
        }
    }
}

/** 静默安装 APK 文件。 */
class InstallAppTool : QuroTool {
    override val name = "install_app"
    override val description = "静默安装 APK 文件（无需用户在安装界面确认；需 Shizuku + 存储读取权限）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "apk_path":{"type":"string","description":"APK 文件的绝对路径（如 /sdcard/Download/app.apk）"}
        },
        "required":["apk_path"]
    }"""
    override fun run(context: Context, arguments: String): String {
        if (!QuroShizuku.isReady) return "❌ Shizuku 未就绪"
        val path = JSONObject(arguments).optString("apk_path", "").ifBlank { return "❌ 缺少 apk_path" }
        // 使用 pm install
        val result = QuroShizuku.exec("pm install -r \"$path\"")
        return when {
            result.contains("Success") -> "✅ 安装成功: $path"
            result.contains("exit=0") -> "✅ 安装完成: $result"
            else -> "❌ 安装失败: $result"
        }
    }
}

/** 查询 Shizuku 状态信息。 */
class ShizukuStatusTool : QuroTool {
    override val name = "shizuku_status"
    override val description = "查询 Shizuku 服务状态（是否安装/授权/运行中/版本信息），无需参数 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, arguments: String): String =
        QuroShizuku.getVersionInfo(context)
}
