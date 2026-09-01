package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.privilege.QuroLSPosed
import com.ai.assistance.quro.core.privilege.QuroRootGateway
import com.ai.assistance.quro.core.shizuku.QuroShizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 终端直用特权通道执行工具（#564）。
 *
 * 让终端 / AI 直接以 Root / Shizuku 权限执行命令，并自查可用通道：
 * - action=run：经 [QuroRootGateway]（Shizuku-root → su 自动降级）执行 shell 命令，返回所用通道与输出；
 * - action=status：报告 Root / Shizuku / LSPosed / ZorvAI 授权 的可用状态，供 AI 调用高危能力前自查。
 *
 * 复用既有统一网关，不重复实现 quoting / 超时 / 审计；无提权通道时命令被拒绝并返回引导，绝不裸奔。
 */
class QuroPrivExecTool : QuroTool {
    override val name = "priv_exec"
    override val description =
        "终端直用特权通道执行命令（Shizuku→ROOT 自动降级）。" +
            "action=run：以 root 权限执行 shell 命令（经 QuroRootGateway，Shizuku-root 优先、失败降级 su），返回所用通道与输出；" +
            "action=status：报告 Root / Shizuku / LSPosed / ZorvAI 授权 的可用状态。" +
            "参数 {\"action\":\"run|status\",\"command\":\"要执行的完整 shell 命令(run 用,无需自己加引号)\",\"timeout_ms\":15000}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"run=以特权执行命令 / status=查看特权通道可用状态"},
            "command":{"type":"string","description":"run 时要执行的完整 shell 命令（无需自己加引号）"},
            "timeout_ms":{"type":"integer","description":"run 超时毫秒，默认 15000，最大 60000"}
        },
        "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON：$arguments" }
        val action = jo.optString("action", "").trim().lowercase()
        return when (action) {
            "run" -> runCatching {
                val cmd = jo.optString("command", "").trim()
                if (cmd.isEmpty()) return "run 缺少 command"
                val timeout = jo.optLong("timeout_ms", QuroRootGateway.DEFAULT_TIMEOUT_MS).coerceIn(1000, 60000)
                runBlocking(Dispatchers.IO) {
                    QuroRootGateway.exec(context, cmd, timeout, "capos.priv_exec").render()
                }
            }.getOrElse { "priv_exec 执行异常：${it.message}" }
            "status" -> runCatching { status(context) }.getOrElse { "priv_exec 状态查询异常：${it.message}" }
            else -> "未知 action: $action（支持 run / status）"
        }
    }

    private fun status(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("🔐 特权通道状态：\n")
        val rootCached = QuroRootGateway.cachedRootAvailable()
        sb.append("- Root(su)：").append(
            when (rootCached) {
                true -> "可用（已探测）"
                false -> "不可用"
                null -> "未探测（用 run 触发一次真实探测）"
            },
        ).append('\n')
        sb.append("- Shizuku：")
            .append(if (runCatching { QuroShizuku.isReady }.getOrDefault(false)) "已连接并授权" else "未就绪")
            .append('\n')
        sb.append("- LSPosed/Xposed：").append(QuroLSPosed.statusText(ctx)).append('\n')
        sb.append("- ZorvAI 授权：应用级特权由上述通道提供；本工具不新增任何权限定义（符合 LSPosed 界面铁律）。\n")
        sb.append("\n说明：priv_exec run 经 QuroRootGateway（Shizuku-root → su 自动降级）。无提权通道时命令会被拒绝并返回引导。")
        return sb.toString().trim()
    }
}
