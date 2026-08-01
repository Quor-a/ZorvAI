package com.ai.assistance.quro.core.aci

import android.content.Context
import android.os.Bundle
import java.util.zip.GZIPInputStream
import com.ai.assistance.quro.core.tools.QuroTool
import org.json.JSONObject

/**
 * ACI 工具（原创 · 让 LLM 真正调用第三方 App 能力）：
 * - aci_list：列出当前已发现的 ACI 第三方 App 与其暴露的能力。
 * - aci_call：调用某个第三方 App 的指定 ACI 能力，参数 {target_package, capability, args}。
 *
 * 两个工具都经 QuroAciManager（ACI 控制方客户端）路由到已绑定的第三方 ACI Service。
 */
class QuroAciListTool : QuroTool {
    override val name = "aci_list"
    override val description =
        "列出当前已发现的所有 ACI 第三方 App 及其暴露的能力（id / 说明 / 参数 / 是否需用户确认）。" +
            "当用户问「你能控制哪些 App / 有哪些第三方能力可用」时使用。参数为空 {}。" +
            "注意：第三方 App 必须在设备上已安装且声明了 ACI Service，应用启动时会自动发现；若列表为空，仅说明目标 App 未安装或未声明 ACI Service，请直接告知用户去安装。" +
            "ACI 是本地无 Root 的 AIDL 框架，列表为空时【禁止】用 dumpsys/Shizuku/ROOT 去排查——那不是 ACI 的排障方式。"
    override val parametersJson = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        val mgr = runCatching { QuroAciManager.getInstance() }
            .getOrElse { return "⚠️ ACI 尚未初始化（QuroAciManager 未启动）。" }
        val prompt = mgr.getCapabilityPrompt()
        return buildString {
            append(prompt)
            if (mgr.getCapabilityIndex().isEmpty()) {
                append("\n提示：用 aci_call 调用具体能力前，请先确认目标 App 已安装且 ACI 服务被发现。")
            }
        }.trim()
    }
}

class QuroAciCallTool : QuroTool {
    override val name = "aci_call"
    override val description =
        "调用一个第三方 App 通过 ACI（Agent Capability Interface）暴露的能力（如发消息 / 查未读 / 建群 / 打开网页 / 执行网页 JS / 发起 HTTP 请求）。" +
            "参数：{\"target_package\":\"第三方 App 包名（用 aci_list 查到的 pkg）\",\"capability\":\"能力 id（如 send_message / browser_open / http_request）\",\"args\":{参数名:参数值}}。" +
            "调用会跨进程发往目标 App 的 ACI Service 并同步等待结果（最长约 15 秒）。" +
            "调用前请勿伪造包名——目标 App 会用 Binder 真实 UID 鉴权。" +
            "若目标能力 requireUserConfirm（aci_list 会标注「需要用户确认」），必须先征询用户明确同意，并在 args 中带 confirm:true 才允许调用。" +
            "若返回 503（服务未绑定），属绑定生命周期问题，框架会自动重绑——直接重试一次即可，禁止用 Shizuku/dumpsys/ROOT 去\"修复\"。其他错误码原样转告用户，不要臆测为权限不足。" +
            "【自由组合】你可以把多个 ACI 能力像积木一样链式编排，而不是死板地一步步来：例如先 browser_open 打开页面 → browser_script 执行 JS 取数 → browser_read 回读结果；或先 browser_elements 标注稳定ID → browser_action 按ID操作；需要等页面加载则 browser_wait。" +
            "【HTTP / 局域网】受控浏览器（ZorvAI 浏览器）暴露 http_request 能力：可经 ACI 让浏览器代为发起任意 HTTP 请求，包括同网段 LAN 明文（http://192.168.x.x、http://10.x、*.local mDNS 等），用于访问路由器/NAS/智能家居后台、私有 API、物联网设备等——受控浏览器已放开局域网明文，无需因公网明文限制而犹豫；公网请求仍走 HTTPS。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "target_package":{"type":"string","description":"第三方 App 包名，例如 com.example.chat（用 aci_list 查到）"},
            "capability":{"type":"string","description":"能力 id，例如 send_message / get_unread_count / create_group"},
            "args":{"type":"object","description":"能力所需参数，键为参数名，值为字符串/数字/布尔，例如 {\"contact\":\"张三\",\"content\":\"你好\"}"},
            "confirm":{"type":"boolean","description":"（可选）仅当目标能力 requireUserConfirm 时需要：先征得用户同意再设 true。它是控制方令牌，不会作为业务参数传入远端。"}
        },
        "required":["target_package","capability"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val mgr = runCatching { QuroAciManager.getInstance() }
            .getOrElse { return "⚠️ ACI 尚未初始化（QuroAciManager 未启动）。" }

        val obj = runCatching { JSONObject(arguments) }
            .getOrElse { return "参数不是合法 JSON：$arguments" }
        val target = obj.optString("target_package", "").trim()
        val cap = obj.optString("capability", "").trim()
        if (target.isEmpty()) return "缺少 target_package（要调用的第三方 App 包名，用 aci_list 查）。"
        if (cap.isEmpty()) return "缺少 capability（能力 id，用 aci_list 查）。"

        val argsObj = obj.optJSONObject("args")

        // ── 确认门禁（requireUserConfirm 兜底拦截）──
        // 被调方基类 BaseACIService 历史上仅把该标记当展示用、从不真正拦截；
        // 由控制方（我们）在此兜底：需要用户确认的能力，必须先拿到用户明确同意（args.confirm=true）才放行。
        val confirm = argsObj?.optBoolean("confirm", false) ?: false
        val needConfirm = mgr.getCapabilityIndex()[target]?.any { it.id == cap && it.isRequireUserConfirm } ?: false
        if (needConfirm && !confirm) {
            return "⚠️ 该能力（$cap @ $target）需要用户确认才能执行。请先征得用户明确同意，" +
                "并在 args 中带上 confirm:true 后重试。\n" +
                "示例：{\"target_package\":\"$target\",\"capability\":\"$cap\",\"args\":{\"confirm\":true,...}}"
        }

        // 把 args JSON 对象转成 ACI 的 Bundle 参数（字符串 / 数字 / 布尔分别映射）。
        // confirm 是控制方确认令牌：翻译为被调方期望的 user_confirmed 传入，使其在服务端
        // 也能做 requireUserConfirm 兜底拦截（纵深防御）；不保留原始 confirm 键。
        val bundle = Bundle()
        argsObj?.keys()?.forEach { k ->
            if (k == "confirm") return@forEach
            when (val v = argsObj.opt(k)) {
                is Boolean -> bundle.putBoolean(k, v)
                is Int -> bundle.putInt(k, v)
                is Double -> bundle.putDouble(k, v)
                else -> bundle.putString(k, argsObj.optString(k, ""))
            }
        }
        if (confirm) bundle.putBoolean("user_confirmed", true)

        val resp = mgr.call(target, cap, bundle)
        return if (resp.isSuccess) {
            val result = resp.result
            if (result == null || result.keySet().isEmpty()) {
                "✅ ACI 调用成功（$target / $cap）\n（无返回数据）"
            } else if (cap == "http_request") {
                renderHttpResult(result)
            } else {
                // 若受控端经 html_gz 回传 gzip 二进制，先解压拿完整 HTML
                var fullHtml: String? = null
                if (result.containsKey("html_gz") && result.get("html_gz") is ByteArray) {
                    fullHtml = runCatching { String(gunzip(result.get("html_gz") as ByteArray), Charsets.UTF_8) }.getOrNull()
                }
                val url = result.getString("url") ?: ""
                val title = result.getString("title") ?: ""
                val htmlPreview = result.getString("html") ?: ""
                val truncated = result.getBoolean("truncated", false)
                val sb = StringBuilder()
                sb.append("✅ ACI 调用成功（$target / $cap）\n")
                sb.append("URL: $url\n")
                sb.append("标题: $title\n")
                if (fullHtml != null) {
                    sb.append("HTML（完整内容，经 gzip 解压，共 ${fullHtml.length} 字符）:\n")
                    sb.append(fullHtml)
                } else {
                    if (truncated) sb.append("⚠️ 仅返回截断预览（Binder 限制，完整内容未传输）。\n")
                    sb.append("HTML（共 ${htmlPreview.length} 字符）:\n")
                    sb.append(htmlPreview)
                }
                // 输出其余未在上面专门处理的键（html_gz 不打印原始字节数组）
                val handled = setOf("url", "title", "html", "html_gz", "truncated")
                for (key in result.keySet()) {
                    if (key in handled) continue
                    sb.append("\n  - $key = ${result.get(key)}\n")
                }
                sb.toString().trim()
            }
        } else {
            "⛔ ACI 调用失败（错误码=${resp.errorCode}）：${resp.errorMessage}"
        }
    }

    /** gzip 解压（控制端用，还原受控端经 html_gz 回传的完整 HTML）。 */
    private fun gunzip(data: ByteArray): ByteArray {
        val gz = GZIPInputStream(java.io.ByteArrayInputStream(data))
        return gz.readBytes()
    }

    /**
     * 渲染 http_request 的控制端结果（让 AI 拿到干净的 状态码/响应头/响应体）。
     * 若受控端经 response_body_gz 回传 gzip 二进制，先解压拿完整响应体。
     */
    private fun renderHttpResult(result: android.os.Bundle): String {
        var fullBody: String? = null
        if (result.containsKey("response_body_gz") && result.get("response_body_gz") is ByteArray) {
            fullBody = runCatching {
                String(gunzip(result.get("response_body_gz") as ByteArray), Charsets.UTF_8)
            }.getOrNull()
        }
        val status = result.getInt("status_code", -1)
        val headers = result.getString("response_headers") ?: ""
        val bodyPreview = result.getString("response_body") ?: ""
        val truncated = result.getBoolean("truncated", false)
        val sb = StringBuilder()
        sb.append("✅ HTTP 请求成功\n")
        sb.append("状态码: $status\n")
        sb.append("响应头:\n$headers\n")
        if (fullBody != null) {
            sb.append("响应体（完整内容，经 gzip 解压，共 ${fullBody.length} 字符）:\n")
            sb.append(fullBody)
        } else {
            val reason = result.getString("truncated_reason") ?: ""
            if (truncated && reason.isNotEmpty()) sb.append("⚠️ $reason\n")
            else if (truncated) sb.append("⚠️ 响应体已截断（Binder 限制，完整内容见 response_body_gz）。\n")
            sb.append("响应体（共 ${bodyPreview.length} 字符）:\n")
            sb.append(bodyPreview)
        }
        return sb.toString().trim()
    }
}
