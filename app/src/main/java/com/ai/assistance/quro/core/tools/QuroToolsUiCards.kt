package com.ai.assistance.quro.core.tools
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.content.Context
import androidx.compose.runtime.snapshots.Snapshot
import com.ai.assistance.quro.core.cards.CardSdk
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
    override val description = qstr(R.string.qk_03603) +
        "用于把结构化结果以可视化、可操作的方式呈现给用户，而非纯文本。参数 spec 为 JSON 字符串。" +
        "kind 取值：todo（items:[{text,done}]）、chart（chart_type:bar|line, series:[{label,value}]）、" +
        "note（body, lang 可选）、actions（actions:[{label,command}]）。" +
        "也可用 type 字段下发与 ui_widget 完全一致的全量类型（button/toggle/slider/progress/stat/alert/table/list/segmented/pie/rating/countdown/tabs/expandable/form/chips/steps/gauge/media/info/quickreply/quickaction/timeline/heatmap/compare/radar/timer/carousel/kanban/color/counter/breadcrumb/tagcloud/badge/avatargroup/mermaid/miniapp/composite 等，详见 CARD_CATALOG 卡片目录）。" +
        "command 语法：ui_open_* / ui_toggle_* / linux:install / run:<命令>，" +
        "以及 v221 新增的 open:<url>（内置浏览器打开）/ copy:<文本>（复制剪贴板）/ ai:<提示词>（直接发给 AI）/ screen:<名称>（界面导航）。" +
        "与 ui_widget 共用同一份名册，共 ${CardSdk.typeCount} 种：${CardSdk.compactCatalog()}。" +
        "要完整字段与样例先调 card_catalog。" +
        "正文围栏同样支持 card / cards / cardui / cardjson（cardjson 为一行一个 JSON，流式友好）。" +
        "围栏头后可跟属性（空格分隔）：开关 compact（一组小卡片必给）/ scroll（超高内部滚动）；" +
        "带值 title=组级标题（各卡自带标题时以卡为准）/ theme=主题档位，只认 accent(默认)/warn/danger/plain。" +
        "🔴 下发后若要改这张卡（刷新进度、勾掉一项、改个单元格），用 card_patch 按 JSON Pointer " +
        "改字段即可，**不要重发整张卡**：重发既费 token 又容易把想保留的字段改掉。" +
        "patch 前建议先 card_patch(describe=true, cardId) 看该卡当前有哪些合法路径。cardId 就是这里下发的 id。"
    override val parametersJson = """{"type":"object","properties":{"spec":{"type":"string","description":"卡片 JSON 规格，见工具说明"}}},"required":["spec"]}"""

    override fun run(context: Context, arguments: String): String {
        return try {
            val jo = JSONObject(arguments)
            val spec = jo.optString("spec", "").ifBlank { arguments }
            val s = JSONObject(spec)
            // kind / type 双入口：统一改写成 type 后走 parseComponentSpec（与 ui_widget 同源全量解析）
            if (!s.has("type") && s.has("kind")) s.put("type", s.optString("kind", ""))
            val card = parseComponentSpec(s.toString())
                ?: return "❌ 未知卡片类型（kind/type 均不支持）。基础：todo/chart/note/actions。" +
                    "全量共 ${CardSdk.typeCount} 种：${CardSdk.compactCatalog()}。调 card_catalog 取完整样例。"
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