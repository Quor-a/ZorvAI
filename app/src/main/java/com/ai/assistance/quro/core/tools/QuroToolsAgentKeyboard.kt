package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.service.QuroAiKeyboardService
import org.json.JSONObject

/**
 * AI 智能体键盘工具：把文本直接打字进当前聚焦的输入框（如 WPS 文档）。
 *
 * 与 [InputTextTool]（无障碍 ACTION_SET_TEXT）互补：
 * - [AiKeyboardTypeTool] 走 IME 通道，对绝大多数 App 更可靠，但要求用户已启用并切到「Quro AI 键盘」。
 * - [InputTextTool] 走无障碍通道，无需切换键盘，但依赖目标 App 暴露可编辑节点。
 *
 * 典型链路：launch_app(打开 WPS) → 用户点一下文档输入框（获得焦点）→ ai_type_text(正文) → ai_press_enter(提交)。
 */
class AiKeyboardTypeTool : QuroTool {
    override val name = "ai_type_text"
    override val description =
        "通过 Quro AI 智能体键盘，把文本直接打字进当前聚焦的输入框（如 WPS 文档）。" +
        "前置条件：在「系统设置→语言与输入法」启用并切到『Quro AI 键盘』，且目标 App（如 WPS）的输入框已聚焦。" +
        "若未启用键盘，请改用 input_text（无障碍通道）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "text":{"type":"string","description":"要输入的文本内容（必填）"}
        },
        "required":["text"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val svc = QuroAiKeyboardService.instance
            ?: return "❌ 智能体键盘未运行：请在系统设置启用『Quro AI 键盘』（设置→语言与输入法→Quro AI 键盘）"
        if (!svc.isInputActive()) {
            return "⚠️ 当前没有可输入的焦点：请先切到『Quro AI 键盘』并确保目标 App（如 WPS）的输入框已聚焦，再重试"
        }
        val text = JSONObject(arguments).optString("text", "").takeIf { it.isNotBlank() }
            ?: return "❌ 缺少 text 参数"
        return if (svc.typeText(text)) {
            "✅ 已通过智能体键盘输入: ${text.take(64)}${if (text.length > 64) "…" else ""}"
        } else {
            "❌ 输入失败（输入框可能不支持 commitText，可改用 input_text 无障碍通道）"
        }
    }
}

/** AI 智能体键盘：发送回车键（提交/换行）。 */
class AiKeyboardPressEnterTool : QuroTool {
    override val name = "ai_press_enter"
    override val description =
        "通过 Quro AI 智能体键盘发送回车键（提交/换行）。前置条件同 ai_type_text：已启用并切到『Quro AI 键盘』且目标 App 输入框已聚焦。"
    override val parametersJson = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        val svc = QuroAiKeyboardService.instance
            ?: return "❌ 智能体键盘未运行：请在系统设置启用『Quro AI 键盘』"
        if (!svc.isInputActive()) {
            return "⚠️ 当前没有可输入的焦点：请先切到『Quro AI 键盘』并确保目标 App 输入框已聚焦，再重试"
        }
        return if (svc.pressEnter()) "✅ 已发送回车" else "❌ 发送回车失败"
    }
}
