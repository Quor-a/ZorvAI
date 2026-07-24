package com.ai.assistance.quro.core.tools

import android.content.Context
import androidx.compose.runtime.snapshots.Snapshot
import com.ai.assistance.quro.core.cards.QuroChatCard
import com.ai.assistance.quro.core.cards.QuroChatCardStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * `ui_card` 工具（v132）：让 AI 在对话框内下发「可交互富卡片」。
 *
 * 参数 `spec` 为 JSON 字符串，结构：
 * {
 *   "kind": "todo" | "chart" | "note" | "actions",
 *   "title": "卡片标题",
 *   "id": "可选，缺省自动生成（同名覆盖）",
 *   // todo:
 *   "items": [ {"text":"...","done":false}, ... ],
 *   // chart:
 *   "chart_type": "bar" | "line",
 *   "series": [ {"label":"一月","value":12}, ... ],
 *   // note:
 *   "body": "笔记/代码内容", "lang": "kotlin|json|python|null",
 *   // actions:
 *   "actions": [ {"label":"打开终端","command":"ui_open_terminal"}, ... ]
 * }
 * command 支持：ui_open_* / ui_toggle_* / "linux:install" / "run:<命令>"。
 * 返回生成卡片的回执；卡片立即出现在对话框底部卡片栏并可由用户交互。
 */
class UiCardTool : QuroTool {
    override val name = "ui_card"
    override val description = "在对话框内渲染一张可交互富卡片（待办清单 / 数据图表 / 笔记代码 / 动作按钮组）。" +
        "用于把结构化结果以可视化、可操作的方式呈现给用户，而非纯文本。参数 spec 为 JSON 字符串。" +
        "kind 取值：todo（items:[{text,done}]）、chart（chart_type:bar|line, series:[{label,value}]）、" +
        "note（body, lang 可选）、actions（actions:[{label,command}]）。" +
        "command 语法：ui_open_* / ui_toggle_* / linux:install / run:<命令>，" +
        "以及 v221 新增的 open:<url>（内置浏览器打开）/ copy:<文本>（复制剪贴板）/ ai:<提示词>（直接发给 AI）/ screen:<名称>（界面导航）。" +
        "更丰富的卡片类型（color/counter/breadcrumb/tagcloud/badge/avatargroup 等）与完整样例见 ui_widget 工具及 CARD_CATALOG 卡片目录。"
    override val parametersJson = """{"type":"object","properties":{"spec":{"type":"string","description":"卡片 JSON 规格，见工具说明"}}},"required":["spec"]}"""

    override fun run(context: Context, arguments: String): String {
        return try {
            val jo = JSONObject(arguments)
            val spec = jo.optString("spec", "").ifBlank { arguments }
            val s = JSONObject(spec)
            val kind = s.optString("kind", "")
            val title = s.optString("title", "卡片")
            val id = s.optString("id", QuroChatCardStore.newId())
            val card: QuroChatCard = when (kind) {
                "todo" -> {
                    val items = arrayListOf<QuroChatCard.TodoCard.TodoItem>()
                    val arr = s.optJSONArray("items") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val it = arr.optJSONObject(i) ?: continue
                        items.add(QuroChatCard.TodoCard.TodoItem(it.optString("text", ""), it.optBoolean("done", false)))
                    }
                    QuroChatCard.TodoCard(id, title, items)
                }
                "chart" -> {
                    val series = arrayListOf<QuroChatCard.ChartCard.SeriesPoint>()
                    val arr = s.optJSONArray("series") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val it = arr.optJSONObject(i) ?: continue
                        val v = runCatching { it.optDouble("value", 0.0).toFloat() }.getOrDefault(0f)
                        series.add(QuroChatCard.ChartCard.SeriesPoint(it.optString("label", ""), v))
                    }
                    QuroChatCard.ChartCard(id, title, s.optString("chart_type", "bar"), series)
                }
                "note" -> QuroChatCard.NoteCard(id, title, s.optString("body", ""), s.optString("lang", "").ifBlank { null })
                "actions" -> {
                    val actions = arrayListOf<QuroChatCard.ActionCard.CardAction>()
                    val arr = s.optJSONArray("actions") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val it = arr.optJSONObject(i) ?: continue
                        actions.add(QuroChatCard.ActionCard.CardAction(it.optString("label", "动作"), it.optString("command", "")))
                    }
                    QuroChatCard.ActionCard(id, title, actions)
                }
                else -> return "❌ 未知卡片类型 kind=$kind（支持 todo/chart/note/actions）"
            }
            // 优先挂进聊天气泡（onCard 桥 → 当前助手消息）；桥未连接时退回全局卡片栏兜底
            val bridge = QuroUiActionBridge.onCard
            if (bridge != null) {
                bridge(card)
            } else {
                Snapshot.withMutableSnapshot { QuroChatCardStore.add(card) }
            }
            """{"ok":true,"id":"$id","kind":"$kind","title":"$title"}"""
        } catch (e: Exception) {
            "❌ ui_card 解析失败：${e.message}"
        }
    }
}
