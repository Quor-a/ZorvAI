package com.ai.assistance.quro.core.tools

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * 可视化操作弹窗工具：AI可以创建包含按钮的弹窗，用户点击按钮执行对应操作
 */
class VisualActionTool : QuroTool {
    override val name = "visual_action"
    override val description = """可视化操作弹窗工具：弹出包含多个按钮的弹窗，用户点击按钮后返回对应值。
参数：{"title":"标题","message":"说明文字","buttons":[{"text":"按钮文本","value":"返回值","style":"primary"}],"timeout":30}
style可选: primary(主要), secondary(次要), danger(危险)
返回：用户点击的按钮的value值。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "title":{"type":"string","description":"弹窗标题"},
            "message":{"type":"string","description":"弹窗说明文字"},
            "buttons":{
                "type":"array",
                "items":{
                    "type":"object",
                    "properties":{
                        "text":{"type":"string","description":"按钮显示文本"},
                        "value":{"type":"string","description":"点击后返回的值"},
                        "style":{"type":"string","description":"按钮样式: primary/secondary/danger"}
                    },
                    "required":["text","value"]
                },
                "description":"按钮列表"
            },
            "timeout":{"type":"integer","description":"超时时间（秒），默认60秒"}
        },
        "required":["title","message","buttons"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = JSONObject(arguments)
        val title = args.optString("title", "").trim()
        if (title.isBlank()) return "visual_action 需要 title（弹窗标题）"

        val message = args.optString("message", "").trim()
        if (message.isBlank()) return "visual_action 需要 message（弹窗说明）"

        val buttonsArray = args.optJSONArray("buttons")
        if (buttonsArray == null || buttonsArray.length() == 0) {
            return "visual_action 需要 buttons（按钮列表）"
        }

        val buttons = mutableListOf<VisualButtonConfig>()
        for (i in 0 until buttonsArray.length()) {
            val btnObj = buttonsArray.optJSONObject(i) ?: continue
            val text = btnObj.optString("text", "").trim()
            val value = btnObj.optString("value", "").trim()
            val style = btnObj.optString("style", "primary").trim()

            if (text.isNotBlank() && value.isNotBlank()) {
                buttons.add(VisualButtonConfig(text, value, style))
            }
        }

        if (buttons.isEmpty()) {
            return "visual_action 需要至少一个有效的按钮"
        }

        val timeout = args.optInt("timeout", 60)

        return try {
            showActionDialog(title, message, buttons, timeout)
        } catch (e: Exception) {
            Log.e(TAG, "操作弹窗失败", e)
            "操作弹窗失败: ${e.message}"
        }
    }

    private fun showActionDialog(
        title: String,
        message: String,
        buttons: List<VisualButtonConfig>,
        timeout: Int
    ): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)

        val pending = VisualPendingAction(
            title = title,
            message = message,
            buttons = buttons,
            latch = latch,
            result = result,
            // 非阻塞回流：用户选择操作后由 submitAction 回调，值作为新用户消息触发 AI 续跑。
            onDeliver = { VisualAnswerBridge.deliver(it) }
        )

        synchronized(VisualActionQueue.pendingActions) {
            VisualActionQueue.pendingActions.add(pending)
        }
        // 修复：通知 UI 有新操作加入
        VisualActionQueue.signalAdded()

        Log.d(TAG, "已弹出可视化操作弹窗（非阻塞）: $title (按钮数: ${buttons.size}, 旧超时: ${timeout}s 已不再阻塞等待)")

        // 不再用 latch.await 阻塞 agent 循环：用户无需在固定时间内操作，
        // 操作后值会作为新用户消息回流，由 AI 在下一轮继续。
        return "（已向用户弹出可视化操作弹窗「$title」，等待用户操作；用户选择后会作为新消息继续对话，无需等待计时）"
    }

    companion object {
        private const val TAG = "VisualActionTool"
    }
}
