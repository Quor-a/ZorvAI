package com.ai.assistance.quro.core.tools

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 弹窗按钮 */
data class PopupButton(
    val text: String,
    val value: String,
    val style: String = "primary"  // primary/secondary/danger/success
)

/** 弹窗输入框 */
data class PopupInput(
    val id: String,
    val label: String,
    val placeholder: String = "",
    val defaultValue: String = "",
    val type: String = "text"  // text/number/password/email
)

/** 待处理的自由弹窗 */
data class VisualPopupData(
    val title: String,
    val content: String,          // 支持 Markdown/HTML/纯文本
    val buttons: List<PopupButton>,
    val inputs: List<PopupInput>, // 可选的输入框
    val imageUrl: String?,        // 可选的图片
    val width: Int?,              // 可选的宽度
    val height: Int?,             // 可选的高度
    val cancelable: Boolean = true,
    val timeout: Int = 60,
    val latch: CountDownLatch,
    val result: AtomicReference<PopupResult?>
)

/** 弹窗结果 */
data class PopupResult(
    val buttonValue: String?,     // 点击的按钮值
    val inputValues: Map<String, String>,  // 输入框的值
    val cancelled: Boolean = false
)

/** 弹窗队列 */
object VisualPopupQueue {
    private const val TAG = "VisualPopupQueue"
    val pendingPopups = mutableListOf<VisualPopupData>()

    fun submitResult(index: Int, result: PopupResult) {
        if (index in pendingPopups.indices) {
            val pending = pendingPopups[index]
            pending.result.set(result)
            pending.latch.countDown()
            pendingPopups.removeAt(index)
            Log.d(TAG, "用户提交弹窗结果: $result")
        }
    }

    fun getCurrentPopup(): Pair<Int, VisualPopupData>? {
        return if (pendingPopups.isNotEmpty()) {
            0 to pendingPopups[0]
        } else {
            null
        }
    }
}

/**
 * 自由可视化弹窗工具：AI可以创建任意内容的弹窗，没有格式限制
 *
 * 支持的功能：
 * - 自由文本/Markdown/HTML内容
 * - 多个按钮（不同样式）
 * - 输入框（文本/数字/密码等）
 * - 图片显示
 * - 自定义宽高
 * - 可选是否允许取消
 *
 * 使用场景：
 * - AI需要展示复杂信息并让用户操作
 * - AI需要用户输入多个字段
 * - AI需要展示图片并让用户确认
 * - AI需要创建自定义表单
 */
class VisualPopupTool : QuroTool {
    override val name = "visual_popup"
    override val description = """自由可视化弹窗工具：创建任意内容的弹窗，支持文本、按钮、输入框、图片等。
参数：{
  "title":"标题",
  "content":"内容(Markdown/HTML/纯文本)",
  "buttons":[{"text":"按钮文本","value":"返回值","style":"primary"}],
  "inputs":[{"id":"input1","label":"标签","placeholder":"提示","type":"text"}],
  "image_url":"图片URL(可选)",
  "width":400,
  "height":300,
  "cancelable":true,
  "timeout":60
}
按钮样式: primary/secondary/danger/success
输入类型: text/number/password/email
返回：{"button":"点击的按钮值","inputs":{"input1":"输入的值"},"cancelled":false}"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "title":{"type":"string","description":"弹窗标题"},
            "content":{"type":"string","description":"弹窗内容，支持Markdown/HTML/纯文本"},
            "buttons":{
                "type":"array",
                "items":{
                    "type":"object",
                    "properties":{
                        "text":{"type":"string","description":"按钮显示文本"},
                        "value":{"type":"string","description":"点击后返回的值"},
                        "style":{"type":"string","description":"按钮样式: primary/secondary/danger/success"}
                    },
                    "required":["text","value"]
                },
                "description":"按钮列表"
            },
            "inputs":{
                "type":"array",
                "items":{
                    "type":"object",
                    "properties":{
                        "id":{"type":"string","description":"输入框ID"},
                        "label":{"type":"string","description":"输入框标签"},
                        "placeholder":{"type":"string","description":"占位符提示"},
                        "default_value":{"type":"string","description":"默认值"},
                        "type":{"type":"string","description":"输入类型: text/number/password/email"}
                    },
                    "required":["id","label"]
                },
                "description":"输入框列表（可选）"
            },
            "image_url":{"type":"string","description":"图片URL（可选）"},
            "width":{"type":"integer","description":"弹窗宽度（可选，默认自适应）"},
            "height":{"type":"integer","description":"弹窗高度（可选，默认自适应）"},
            "cancelable":{"type":"boolean","description":"是否允许取消（默认true）"},
            "timeout":{"type":"integer","description":"超时时间（秒），默认60秒"}
        },
        "required":["title","content"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = JSONObject(arguments)
        val title = args.optString("title", "").trim()
        if (title.isBlank()) return "visual_popup 需要 title（弹窗标题）"

        val content = args.optString("content", "").trim()
        if (content.isBlank()) return "visual_popup 需要 content（弹窗内容）"

        // 解析按钮
        val buttons = mutableListOf<PopupButton>()
        args.optJSONArray("buttons")?.let { arr ->
            for (i in 0 until arr.length()) {
                val btnObj = arr.optJSONObject(i) ?: continue
                val text = btnObj.optString("text", "").trim()
                val value = btnObj.optString("value", "").trim()
                val style = btnObj.optString("style", "primary").trim()
                if (text.isNotBlank() && value.isNotBlank()) {
                    buttons.add(PopupButton(text, value, style))
                }
            }
        }

        // 解析输入框
        val inputs = mutableListOf<PopupInput>()
        args.optJSONArray("inputs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val inputObj = arr.optJSONObject(i) ?: continue
                val id = inputObj.optString("id", "").trim()
                val label = inputObj.optString("label", "").trim()
                val placeholder = inputObj.optString("placeholder", "").trim()
                val defaultValue = inputObj.optString("default_value", "").trim()
                val type = inputObj.optString("type", "text").trim()
                if (id.isNotBlank() && label.isNotBlank()) {
                    inputs.add(PopupInput(id, label, placeholder, defaultValue, type))
                }
            }
        }

        val imageUrl = args.optString("image_url", "").trim().ifBlank { null }
        val width = if (args.has("width")) args.optInt("width") else null
        val height = if (args.has("height")) args.optInt("height") else null
        val cancelable = args.optBoolean("cancelable", true)
        val timeout = args.optInt("timeout", 60)

        return try {
            val result = showPopup(title, content, buttons, inputs, imageUrl, width, height, cancelable, timeout)
            result?.let {
                val resultMap = mutableMapOf<String, Any?>()
                resultMap["button"] = it.buttonValue
                resultMap["inputs"] = it.inputValues
                resultMap["cancelled"] = it.cancelled
                JSONObject(resultMap as Map<String, Any>).toString()
            } ?: "{\"cancelled\":true,\"error\":\"timeout\"}"
        } catch (e: Exception) {
            Log.e(TAG, "弹窗失败", e)
            "{\"cancelled\":true,\"error\":\"${e.message}\"}"
        }
    }

    private fun showPopup(
        title: String,
        content: String,
        buttons: List<PopupButton>,
        inputs: List<PopupInput>,
        imageUrl: String?,
        width: Int?,
        height: Int?,
        cancelable: Boolean,
        timeout: Int
    ): PopupResult? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<PopupResult?>(null)

        val popup = VisualPopupData(
            title = title,
            content = content,
            buttons = buttons,
            inputs = inputs,
            imageUrl = imageUrl,
            width = width,
            height = height,
            cancelable = cancelable,
            timeout = timeout,
            latch = latch,
            result = result
        )

        synchronized(VisualPopupQueue.pendingPopups) {
            VisualPopupQueue.pendingPopups.add(popup)
        }

        Log.d(TAG, "显示弹窗: $title (超时: ${timeout}s)")

        val answered = latch.await(timeout.toLong(), TimeUnit.SECONDS)

        return if (answered) {
            result.get()
        } else {
            synchronized(VisualPopupQueue.pendingPopups) {
                VisualPopupQueue.pendingPopups.remove(popup)
            }
            null
        }
    }

    companion object {
        private const val TAG = "VisualPopupTool"
    }
}
