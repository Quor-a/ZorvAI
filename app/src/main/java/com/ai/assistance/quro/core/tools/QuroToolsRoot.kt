package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.policy.QuroPolicy
import com.ai.assistance.quro.core.policy.QuroPolicyStore
import com.ai.assistance.quro.core.privilege.QuroRootGateway
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
        // BUG-E 修复：权限模式=禁止（DENY）时，root 命令必须被策略拦截，
        // 否则「权限模式」对 L4 形同虚设（AI 仍可经 root_exec 执行任意 root 命令）。
        if (QuroPolicyStore.getPriv(context) == QuroPolicy.DENY) {
            return "❌ 权限模式为「禁止」，root_exec 已被策略拦截。请到 CapOS 权限子系统 → 权限模式 调整为允许/询问后再执行。"
        }
        // E-7：统一走 QuroRootGateway（Shizuku-root → su 降级链 + quoting + 超时 + 审计）。
        // 旧实现在这里自己写了一遍降级和读流：没有超时（命令不退出就永久卡住 ReAct 循环）、
        // 不写审计、FD 也不回收。全部由网关接管。
        val res = QuroRootGateway.exec(context, cmd, capsuleId = "tool.root_exec")
        // 🔧 防「乱执行」：ROOT 通道整体不可用（设备未 Root / 未授权 / su 缺失 / Shizuku 未连接）时，
        // 返回清晰、可执行的结论并明确「请勿重试」，引导模型改用 terminal_exec（应用沙盒免权限 shell），
        // 避免模型把通用错误当成「偶发失败」而反复重试同一条 root 命令 → 表现为跑偏 / 乱执行。
        // （旧逻辑直接 render() 出 "❌ ROOT 执行失败：Cannot run program "su"…"，模型读不出「该换工具」，
        //  会无限重试 root_exec。）
        if (res.channel == QuroRootGateway.Channel.NONE || res.error.isNotBlank()) {
            return "❌ 本设备未获取 ROOT 或本应用未获 ROOT 授权，无法执行 root 命令" +
                "（${res.error.ifBlank { "su 与 Shizuku 通道均不可用" }}）。" +
                "请勿继续重试 root_exec；请改用 terminal_exec（应用沙盒内免权限 shell）执行同类命令。"
        }
        return res.render()
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
            // E-7：经网关执行，自带超时（旧写法 waitFor() 无超时，su 卡住时整个工具挂死）
            version = QuroRootGateway.exec(context, "magisk -v", capsuleId = "tool.root_status")
                .output.trim()
        }
        // 方法3: KernelSU 探测
        if (java.io.File("/data/adb/ksud").exists() || java.io.File("/data/adb/ksu").exists()) {
            method = if (method.isEmpty()) "KernelSU" else "$method + KernelSU"
        }
        // 方法4: APatch 探测
        if (java.io.File("/data/local/tmp/apd").exists()) {
            method = if (method.isEmpty()) "APatch" else "$method + APatch"
        }
        // 实际 su 测试（E-7：统一走网关，校验真实回显 + 5s 超时 + 回收 FD）
        val hasSuAccess = QuroRootGateway.isRootAvailable()

        val ver = version.trim()
        return org.json.JSONObject().apply {
            put("rooted", hasSuAccess)
            put("method", if (method.isEmpty()) "未检测到" else method)
            put("version", ver)
            put("note", if (hasSuAccess) "ROOT 访问可用，可使用 root_exec 工具" else "未获取 ROOT 或 Root 管理器未授权本 App")
        }.toString()
    }
}
