package com.ai.assistance.quro.core.aidlaci

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
 * 两个工具都经 QuroAidlAciManager（ACI 控制方客户端）路由到已绑定的第三方 ACI Service。
 */
class QuroAidlAciListTool : QuroTool {
    override val name = "aci_list"
    override val description =
        "列出当前已发现的所有 ACI 第三方 App 及其暴露的能力（id / 说明 / 参数 / 是否需用户确认）。" +
            "当用户问「你能控制哪些 App / 有哪些第三方能力可用」时使用。参数为空 {}。" +
            "注意：第三方 App 必须在设备上已安装且声明了 ACI Service，应用启动时会自动发现；若列表为空，仅说明目标 App 未安装或未声明 ACI Service，请直接告知用户去安装。" +
            "ACI 是本地无 Root 的 AIDL 框架，列表为空时【禁止】用 dumpsys/Shizuku/ROOT 去排查——那不是 ACI 的排障方式。"
    override val parametersJson = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        val mgr = runCatching { QuroAidlAciManager.getInstance() }
            .getOrElse { return "⚠️ ACI 尚未初始化（QuroAidlAciManager 未启动）。" }
        val prompt = mgr.getCapabilityPrompt()
        return buildString {
            append(prompt)
            if (mgr.getCapabilityIndex().isEmpty()) {
                append("\n提示：用 aci_call 调用具体能力前，请先确认目标 App 已安装且 ACI 服务被发现。")
            }
        }.trim()
    }
}

class QuroAidlAciCallTool : QuroTool {
    override val name = "aci_call"
    override val description =
        "调用一个第三方 App 通过 ACI（Agent Capability Interface）暴露的能力（如发消息 / 查未读 / 建群 / 打开网页 / 执行网页 JS / 发起 HTTP 请求 / 共享工作空间读写 workspace_write·workspace_read·workspace_list·workspace_delete）。" +
            "参数：{\"target_package\":\"第三方 App 包名（用 aci_list 查到的 pkg）\",\"capability\":\"能力 id（如 send_message / browser_open / http_request）\",\"args\":{参数名:参数值}}。" +
            "调用会跨进程发往目标 App 的 ACI Service 并同步等待结果（最长约 15 秒）。" +
            "调用前请勿伪造包名——目标 App 会用 Binder 真实 UID 鉴权。" +
            "若目标能力 requireUserConfirm（aci_list 会标注「需要用户确认」），必须先征询用户明确同意，并在 args 中带 confirm:true 才允许调用。" +
            "若返回 503（服务未绑定），属绑定生命周期问题，框架会自动重绑——直接重试一次即可，禁止用 Shizuku/dumpsys/ROOT 去\"修复\"。其他错误码原样转告用户，不要臆测为权限不足。" +
            "【像人一样操作网页（重点）】受控 ZorvAI 浏览器能真正『交互』，而普通 open_web/ai_browser 的 open 只是被动展示、点不进去。你要像真人浏览那样一步步操作：① browser_open 打开目标页 → ② browser_elements 获取页面上带稳定ID的可点击元素（链接/按钮/输入框）→ ③ browser_action 按ID『点击进入链接/填写表单/滚动』（点进去才会跳转子页面，别只停在首页）→ ④ browser_read 读取点击后的页面内容 → 若页面未加载完用 browser_wait 等待。严禁『只打开首页就结束』——用户要的是你点进去拿到里面的内容。" +
            "【自由组合】你可以把多个 ACI 能力像积木一样链式编排，而不是死板地一步步来：例如先 browser_open 打开页面 → browser_script 执行 JS 取数 → browser_read 回读结果；或先 browser_elements 标注稳定ID → browser_action 按ID操作；需要等页面加载则 browser_wait。" +
            "【HTTP / 局域网】受控浏览器（ZorvAI 浏览器）暴露 http_request 能力：可经 ACI 让浏览器代为发起任意 HTTP 请求，包括同网段 LAN 明文（http://192.168.x.x、http://10.x、*.local mDNS 等），用于访问路由器/NAS/智能家居后台、私有 API、物联网设备等——受控浏览器已放开局域网明文，无需因公网明文限制而犹豫；公网请求仍走 HTTPS。" +
            "【真实触摸注入】受控浏览器额外暴露 inject_touch 能力：经 Uinput 伪输入设备向整台设备注入真实触摸事件（action ∈ down/move/up/click/drag/dblclick，参数 x/y/dx/dy/slot/tracking_id/pressure/major），作用于全设备（不限于浏览器视图），与控制面 AIDL/LocalSocket（L1）经 L4 编排协作（信令走 AIDL、内核事件走 Uinput）。仅 root 或系统签名构建真实生效；普通分发版 nativeOpen 失败会明确返回「需 root / 系统签名」，不会假装成功。" +
            "【端侧 APK 构建台（BuildAci，包名 com.ai.assistance.quro.build）协作工作流】构建台可在设备内把 Java 源码编译成 APK。推荐与 ZorvAI 工作区（QuroWorkspace）协作的闭环：" +
            "① 调用构建台 ACI.create_project（args:{project_name:\"工程名\"}）→ 它会在 ZorvAI 工作区建好文件夹并返回绝对路径 path；" +
            "② 用 ZorvAI 自己的 workspace_write 工具把源码写进该 path 下的 src/（如 path/src/Main.java，用户在「工具箱-工作区」也能看到/改）；" +
            "③ 调用构建台 ACI.build_apk（args:{project_dir: path 或工作区相对名, package_name?, app_label?, version_name?}）→ 构建台编译 src/ 全部 Java、注入 base.apk 模板并签名，把 APK 回写到该工程目录；" +
            "④ build_apk 的结果日志经 ACI 回传给你（AI）读取决策——你不要直接去读构建台目录，日志已在返回里。构建台另暴露 build_dex（单文件源码→DEX）、build_project（自带工程→DEX）、build_toolchain（工具链自检）。注意：构建台读写的就是 ZorvAI 工作区目录（它持有全部文件权限），所以 create_project 建的文件夹会直接出现在「工具箱-工作区」里。"
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
        val mgr = runCatching { QuroAidlAciManager.getInstance() }
            .getOrElse { return "⚠️ ACI 尚未初始化（QuroAidlAciManager 未启动）。" }

        val obj = runCatching { JSONObject(arguments) }
            .getOrElse { return "参数不是合法 JSON：$arguments" }
        val target = obj.optString("target_package", "").trim()
        val cap = obj.optString("capability", "").trim()
        if (target.isEmpty()) return "缺少 target_package（要调用的第三方 App 包名，用 aci_list 查）。"
        if (cap.isEmpty()) return "缺少 capability（能力 id，用 aci_list 查）。"

        val argsObj = obj.optJSONObject("args")

        // ── 确认门禁（requireUserConfirm 兜底拦截）──
        // 被调方基类 BaseAidlAciService 历史上仅把该标记当展示用、从不真正拦截；
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
                val raw = result.get(key)
                // 构建台日志等超长文本压缩后再回传，避免占满上下文导致多步编排跑偏
                val rendered = if (key == "log" && raw is String) compactAciLog(raw) else raw
                sb.append("\n  - $key = $rendered\n")
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
     * 压缩受控端回传的超长文本（典型如构建台 build_dex/build_project 的 ecj/d8 日志）。
     * 模型只需「成/败 + 为什么失败 + 首尾上下文」来决策下一步，无需整段编译输出。
     * 压缩后仅几百字符，避免多步编排时撑爆上下文预算导致早期轮次被裁、模型丢主线跑偏。
     */
    private fun compactAciLog(text: String): String {
        val cap = 900
        if (text.length <= cap) return text
        val lines = text.lineSequence().toList()
        val errLines = lines.filter {
            it.contains("ERROR", ignoreCase = true) ||
                it.contains("Exception", ignoreCase = true) ||
                it.contains("错误", ignoreCase = true) ||
                it.contains("失败", ignoreCase = true) ||
                it.contains("✗", ignoreCase = true) ||
                it.contains("⛔", ignoreCase = true)
        }
        return buildString {
            append("〔日志共 ${text.length} 字符，仅保留错误行+首尾〕\n")
            lines.take(3).forEach { append(it).append("\n") }
            if (errLines.isNotEmpty()) {
                append("── 错误/异常行 ──\n")
                errLines.take(15).forEach { append(it).append("\n") }
            } else {
                append("（无 ERROR/Exception 行）\n")
            }
            append("── 尾部 ──\n")
            lines.takeLast(5).forEach { append(it).append("\n") }
        }.take(cap)
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
