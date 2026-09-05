package com.ai.assistance.quro.core.tools

import android.content.Context
import androidx.compose.runtime.snapshots.Snapshot
import com.ai.assistance.quro.core.cards.QuroChatCardStore
import com.ai.assistance.quro.core.cards.parseComponentSpec
import org.json.JSONObject

/**
 * `ui_card` 工具（v132）：让 AI 在对话框内下发「可交互富卡片」（可视化小卡片）。
 *
 * 参数 `spec` 为 JSON 字符串。类型判别字段兼容两种写法：
 * - `kind`（历史习惯）：todo / chart / note / actions 四种基础卡；
 * - `type`（与 ui_widget / CARD_CATALOG 完全一致）：button/toggle/slider/progress/stat/alert/table/
 *   list/segmented/pie/rating/countdown/tabs/expandable/form/chips/steps/gauge/media/info/toolcall/
 *   stream/mediaplay/quickreply/quickaction/timeline/heatmap/compare/radar/timer/carousel/kanban/
 *   color/counter/breadcrumb/tagcloud/badge/avatargroup/mermaid/miniapp/composite/yuanbao/htmlpreview 全量类型。
 *
 * 解析统一走 [parseComponentSpec]，与 ui_widget 完全同源——两个工具只是入口不同，
 * 渲染、持久化、command 语法全部一致。command 支持：ui_open_* / ui_toggle_* / "linux:install" /
 * "run:<命令>" / open:<url> / copy:<文本> / ai:<提示词> / screen:<名称>。
 */
class UiCardTool : QuroTool {
    override val name = "ui_card"
    override val description = "在对话框内渲染一张可交互富卡片（可视化组件；注意：用户说「小卡片」指的是 ```quro-card 围栏，不是本工具）。" +
        "用于把结构化结果以可视化、可操作的方式呈现给用户，而非纯文本。参数 spec 为 JSON 字符串。" +
        "kind 取值：todo（items:[{text,done}]）、chart（chart_type:bar|line, series:[{label,value}]）、" +
        "note（body, lang 可选）、actions（actions:[{label,command}]）。" +
        "也可用 type 字段下发与 ui_widget 完全一致的全量类型（button/toggle/slider/progress/stat/alert/table/list/segmented/pie/rating/countdown/tabs/expandable/form/chips/steps/gauge/media/info/quickreply/quickaction/timeline/heatmap/compare/radar/timer/carousel/kanban/color/counter/breadcrumb/tagcloud/badge/avatargroup/mermaid/miniapp/composite 等，详见 CARD_CATALOG 卡片目录）。" +
        "command 语法：ui_open_* / ui_toggle_* / linux:install / run:<命令>，" +
        "以及 v221 新增的 open:<url>（内置浏览器打开）/ copy:<文本>（复制剪贴板）/ ai:<提示词>（直接发给 AI）/ screen:<名称>（界面导航）。"
    override val parametersJson = """{"type":"object","properties":{"spec":{"type":"string","description":"卡片 JSON 规格，见工具说明"}}},"required":["spec"]}"""

    override fun run(context: Context, arguments: String): String {
        return try {
            val jo = JSONObject(arguments)
            val spec = jo.optString("spec", "").ifBlank { arguments }
            val s = JSONObject(spec)
            // kind / type 双入口：统一改写成 type 后走 parseComponentSpec（与 ui_widget 同源全量解析）
            if (!s.has("type") && s.has("kind")) s.put("type", s.optString("kind", ""))
            val card = parseComponentSpec(s.toString())
                ?: return "❌ 未知卡片类型（kind/type 均不支持）。基础：todo/chart/note/actions；" +
                    "全量类型见 ui_widget 工具与 CARD_CATALOG（button/toggle/slider/progress/stat/alert/table/list/segmented/pie/rating/countdown/tabs/expandable/form/chips/steps/gauge/media/info/toolcall/stream/mediaplay/quickreply/quickaction/timeline/heatmap/compare/radar/timer/carousel/kanban/color/counter/breadcrumb/tagcloud/badge/avatargroup/mermaid/miniapp/composite/yuanbao/htmlpreview）"
            // 优先挂进聊天气泡（onCard 桥 → 当前助手消息）；桥未连接时退回全局卡片栏兜底
            val bridge = QuroUiActionBridge.onCard
            if (bridge != null) {
                bridge(card)
            } else {
                Snapshot.withMutableSnapshot { QuroChatCardStore.add(card) }
            }
            """{"ok":true,"id":"${card.id}","title":"${card.title}"}"""
        } catch (e: Exception) {
            "❌ ui_card 解析失败：${e.message}"
        }
    }
}
