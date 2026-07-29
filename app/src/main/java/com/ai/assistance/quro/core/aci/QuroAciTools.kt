package com.ai.assistance.quro.core.aci

import android.content.Context
import android.os.Bundle
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
        "调用一个第三方 App 通过 ACI（Agent Capability Interface）暴露的能力（如发消息 / 查未读 / 建群）。" +
            "参数：{\"target_package\":\"第三方 App 包名（用 aci_list 查到的 pkg）\",\"capability\":\"能力 id（如 send_message）\",\"args\":{参数名:参数值}}。" +
            "调用会跨进程发往目标 App 的 ACI Service 并同步等待结果（最长约 15 秒）。" +
            "调用前请勿伪造包名——目标 App 会用 Binder 真实 UID 鉴权。" +
            "若目标能力 requireUserConfirm（aci_list 会标注「需要用户确认」），必须先征询用户明确同意，并在 args 中带 confirm:true 才允许调用。" +
            "若返回 503（服务未绑定），属绑定生命周期问题，框架会自动重绑——直接重试一次即可，禁止用 Shizuku/dumpsys/ROOT 去\"修复\"。其他错误码原样转告用户，不要臆测为权限不足。"
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
            val sb = StringBuilder("✅ ACI 调用成功（$target / $cap）\n")
            val result = resp.result
            if (result != null && result.keySet().isNotEmpty()) {
                sb.append("返回结果：\n")
                for (key in result.keySet()) {
                    sb.append("  - $key = ${result.get(key)}\n")
                }
            } else {
                sb.append("（无返回数据）")
            }
            sb.toString().trim()
        } else {
            "⛔ ACI 调用失败（错误码=${resp.errorCode}）：${resp.errorMessage}"
        }
    }
}
