package com.ai.assistance.quro.core.tools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * AI自写UI可视化弹窗数据
 */
data class VisualCustomPopupData(
    val id: String,
    val title: String,
    val htmlContent: String,        // AI完全自写的HTML/CSS/JS内容
    val cardTitle: String,          // 对话框小卡片标题
    val cardDescription: String,    // 对话框小卡片描述
    val width: Int?,                // 可选的宽度
    val height: Int?,               // 可选的高度
    val cancelable: Boolean = true,
    val timeout: Int = 120,         // 默认2分钟
    val latch: CountDownLatch,
    val result: AtomicReference<String?>  // 返回AI自定义的结果
)

/**
 * AI自写UI可视化弹窗队列
 */
object VisualCustomPopupQueue {
    private const val TAG = "VisualCustomPopupQueue"
    val pendingPopups = mutableListOf<VisualCustomPopupData>()

    // 事件通道：当有新弹窗加入或弹窗关闭时发出信号
    private val _eventChannel = Channel<PopupEvent>(Channel.BUFFERED)
    val eventFlow = _eventChannel.receiveAsFlow()

    sealed class PopupEvent {
        data class PopupAdded(val id: String) : PopupEvent()
        data class PopupRemoved(val id: String) : PopupEvent()
    }

    fun submitResult(id: String, result: String) {
        synchronized(pendingPopups) {
            val index = pendingPopups.indexOfFirst { it.id == id }
            if (index >= 0) {
                val pending = pendingPopups[index]
                pending.result.set(result)
                pending.latch.countDown()
                pendingPopups.removeAt(index)
                Log.d(TAG, "用户提交自定义弹窗结果: $result")
                // 发送弹窗关闭事件
                _eventChannel.trySend(PopupEvent.PopupRemoved(id))
            }
        }
    }

    fun getCurrentPopup(): Pair<String, VisualCustomPopupData>? {
        return synchronized(pendingPopups) {
            if (pendingPopups.isNotEmpty()) {
                val popup = pendingPopups[0]
                popup.id to popup
            } else {
                null
            }
        }
    }

    fun getPopupById(id: String): VisualCustomPopupData? {
        return synchronized(pendingPopups) {
            pendingPopups.find { it.id == id }
        }
    }

    fun addPopup(popup: VisualCustomPopupData) {
        synchronized(pendingPopups) {
            pendingPopups.add(popup)
            // 发送弹窗添加事件
            _eventChannel.trySend(PopupEvent.PopupAdded(popup.id))
        }
    }

    fun removePopup(id: String) {
        synchronized(pendingPopups) {
            val index = pendingPopups.indexOfFirst { it.id == id }
            if (index >= 0) {
                pendingPopups.removeAt(index)
                _eventChannel.trySend(PopupEvent.PopupRemoved(id))
            }
        }
    }
}

/**
 * AI自写UI可视化弹窗工具
 *
 * 与VisualPopupTool的区别：
 * - VisualPopupTool：固定UI组件（按钮、输入框等）
 * - VisualCustomPopupTool：AI完全自写HTML/CSS/JS，UI完全自由
 *
 * 功能：
 * - AI可以创建任意HTML内容的弹窗
 * - 对话框显示小卡片，点击打开完整弹窗
 * - 弹窗内容完全由AI控制（表单、图表、游戏、任何东西）
 * - 弹窗内可以通过JS调用window.parent.postMessage返回结果
 *
 * 使用场景：
 * - AI需要展示复杂的交互式UI
 * - AI需要创建自定义表单、图表、游戏等
 * - AI需要完全控制弹窗的外观和交互
 */
class VisualCustomPopupTool : QuroTool {
    override val name = "visual_custom_popup"
    override val description = """AI自写UI可视化弹窗：创建完全自定义的HTML弹窗，UI完全由AI控制。

参数：{
  "title":"弹窗标题",
  "html":"完整的HTML/CSS/JS代码",
  "card_title":"对话框小卡片标题",
  "card_description":"小卡片描述（简短）",
  "width":400,
  "height":300,
  "cancelable":true,
  "timeout":120
}

HTML代码规范：
1. 直接写HTML内容，不需要<html><head><body>标签（会自动包装）
2. 可以使用内联<style>和<script>
3. 通过 window.parent.postMessage(JSON.stringify({action:'submit', data:...}), '*') 返回结果
4. 通过 window.parent.postMessage(JSON.stringify({action:'close'}), '*') 关闭弹窗

示例：
visual_custom_popup({
  "title": "自定义计算器",
  "html": "<div class='calc'><input id='num1' type='number' placeholder='数字1'><select id='op'><option>+</option><option>-</option><option>*</option><option>/</option></select><input id='num2' type='number' placeholder='数字2'><button onclick='calc()'>计算</button><div id='result'></div></div><script>function calc(){const n1=parseFloat(document.getElementById('num1').value);const n2=parseFloat(document.getElementById('num2').value);const op=document.getElementById('op').value;let r;switch(op){case '+':r=n1+n2;break;case '-':r=n1-n2;break;case '*':r=n1*n2;break;case '/':r=n2!==0?n1/n2:'错误';}document.getElementById('result').textContent='结果: '+r;window.parent.postMessage(JSON.stringify({action:'submit',data:{result:r}}),'*');}</script>",
  "card_title": "计算器",
  "card_description": "点击打开自定义计算器"
})

返回：AI在HTML中通过postMessage返回的任意JSON数据"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "title":{"type":"string","description":"弹窗标题"},
            "html":{"type":"string","description":"完整的HTML/CSS/JS代码，AI完全控制UI"},
            "card_title":{"type":"string","description":"对话框小卡片标题"},
            "card_description":{"type":"string","description":"小卡片描述（简短）"},
            "width":{"type":"integer","description":"弹窗宽度（可选，默认自适应）"},
            "height":{"type":"integer","description":"弹窗高度（可选，默认自适应）"},
            "cancelable":{"type":"boolean","description":"是否允许取消（默认true）"},
            "timeout":{"type":"integer","description":"超时时间（秒），默认120秒"},
            "overlay":{"type":"boolean","description":"是否以系统级悬浮窗显示（默认true，自动申请权限）"}
        },
        "required":["title","html","card_title"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = JSONObject(arguments)
        val title = args.optString("title", "").trim()
        if (title.isBlank()) return "visual_custom_popup 需要 title（弹窗标题）"

        val html = args.optString("html", "").trim()
        if (html.isBlank()) return "visual_custom_popup 需要 html（HTML内容）"

        val cardTitle = args.optString("card_title", "").trim().ifBlank { title }
        val cardDescription = args.optString("card_description", "").trim().ifBlank { "点击查看详情" }
        val width = if (args.has("width")) args.optInt("width") else null
        val height = if (args.has("height")) args.optInt("height") else null
        val cancelable = args.optBoolean("cancelable", true)
        val timeout = args.optInt("timeout", 120)
        val overlay = args.optBoolean("overlay", true)

        // 生成唯一ID
        val popupId = "popup_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"

        return try {
            val result = showCustomPopup(popupId, title, html, cardTitle, cardDescription, width, height, cancelable, timeout, overlay, context)
            result ?: "{\"cancelled\":true,\"error\":\"timeout\"}"
        } catch (e: Exception) {
            Log.e(TAG, "自定义弹窗失败", e)
            "{\"cancelled\":true,\"error\":\"${e.message}\"}"
        }
    }

    private fun showCustomPopup(
        id: String,
        title: String,
        html: String,
        cardTitle: String,
        cardDescription: String,
        width: Int?,
        height: Int?,
        cancelable: Boolean,
        timeout: Int,
        overlay: Boolean,
        context: Context
    ): String? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)

        val popup = VisualCustomPopupData(
            id = id,
            title = title,
            htmlContent = html,
            cardTitle = cardTitle,
            cardDescription = cardDescription,
            width = width,
            height = height,
            cancelable = cancelable,
            timeout = timeout,
            latch = latch,
            result = result
        )

        // 使用新的 addPopup 方法（会发送事件通知 UI）
        VisualCustomPopupQueue.addPopup(popup)

        Log.d(TAG, "显示自定义弹窗: $title (ID: $id, 超时: ${timeout}s, 悬浮窗: $overlay)")

        // 如果需要悬浮窗模式，启动悬浮窗服务
        if (overlay) {
            if (com.ai.assistance.quro.service.VisualPopupOverlayService.hasOverlayPermission(context)) {
                com.ai.assistance.quro.service.VisualPopupOverlayService.showPopup(context, id)
            } else {
                // 没有悬浮窗权限，自动跳转设置页请求权限
                com.ai.assistance.quro.service.VisualPopupOverlayService.requestOverlayPermission(context)
                Log.w(TAG, "没有悬浮窗权限，已跳转设置页请求")
                // 回退到普通模式显示
            }
        }

        val answered = latch.await(timeout.toLong(), TimeUnit.SECONDS)

        return if (answered) {
            result.get()
        } else {
            // 超时：从队列中移除
            VisualCustomPopupQueue.removePopup(id)
            null
        }
    }

    companion object {
        private const val TAG = "VisualCustomPopupTool"
    }
}

/**
 * 生成自定义弹窗的完整HTML（带postMessage支持）
 */
fun generateCustomPopupHtml(popupData: VisualCustomPopupData): String {
    return """<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${popupData.title}</title>
    <style>
        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
            /* 让 WebView 内所有元素（尤其是 AI 自写的小卡片）都能稳定接收点击/轻触 */
            touch-action: manipulation;
            -webkit-tap-highlight-color: transparent;
        }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: #f5f5f5;
            min-height: 100vh;
            padding: 16px;
        }
        .popup-container {
            max-width: 100%;
            margin: 0 auto;
            background: white;
            border-radius: 12px;
            box-shadow: 0 4px 20px rgba(0,0,0,0.15);
            overflow: hidden;
        }
        .popup-header {
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            padding: 16px 20px;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .popup-header h2 {
            font-size: 18px;
            font-weight: 600;
        }
        .close-btn {
            background: rgba(255,255,255,0.2);
            border: none;
            color: white;
            width: 32px;
            height: 32px;
            border-radius: 50%;
            cursor: pointer;
            font-size: 18px;
            display: flex;
            align-items: center;
            justify-content: center;
        }
        .close-btn:hover {
            background: rgba(255,255,255,0.3);
        }
        .popup-content {
            padding: 20px;
            min-height: 200px;
        }
        /* AI自定义样式可以覆盖这里 */
    </style>
</head>
<body>
    <div class="popup-container">
        <div class="popup-header">
            <h2>${popupData.title}</h2>
            <button class="close-btn" onclick="closePopup()">&times;</button>
        </div>
        <div class="popup-content">
            ${popupData.htmlContent}
        </div>
    </div>
    
    <script>
        // 结果回传：优先走 Android JS 桥（对话框/悬浮窗注入的 window.Android.postMessage），
        // 否则退回 window.parent.postMessage（兼容 iframe 场景）。
        function __quroSend(obj) {
            var payload = JSON.stringify(obj);
            if (window.Android && window.Android.postMessage) {
                window.Android.postMessage(payload);
            } else {
                window.parent.postMessage(payload, '*');
            }
        }
        function submitResult(data) {
            __quroSend({ action: 'submit', popupId: '${popupData.id}', data: data == null ? {} : data });
        }
        function closePopup() {
            __quroSend({ action: 'close', popupId: '${popupData.id}' });
        }
        // 转发 HTML 内部通过 window.parent.postMessage 发出的消息到 Android 桥，
        // 使 AI 自写的小卡片/按钮（onclick 调 submitResult/closePopup 或 window.parent.postMessage）都能真正回传。
        window.addEventListener('message', function(event) {
            try {
                var msg = typeof event.data === 'string' ? JSON.parse(event.data) : event.data;
                if (window.Android && window.Android.postMessage) {
                    window.Android.postMessage(JSON.stringify(msg));
                }
            } catch(e) {}
        });
        // 全局兜底：任何带 data-quro-submit / onclick=submitResult 的元素都可点
        document.addEventListener('click', function(e) {
            var el = e.target;
            while (el && el !== document) {
                if (el.getAttribute && el.getAttribute('data-quro-submit') != null) {
                    try { submitResult(el.getAttribute('data-quro-submit') || {}); } catch(_) {}
                    break;
                }
                el = el.parentNode;
            }
        }, true);
    </script>
</body>
</html>"""
}