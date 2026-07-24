package com.ai.assistance.quro.core.tools

import android.content.Context
import androidx.compose.runtime.snapshots.Snapshot
import com.ai.assistance.quro.core.cards.QuroChatCard
import com.ai.assistance.quro.core.cards.QuroChatCardStore
import com.ai.assistance.quro.core.cards.parseComponentSpec
import org.json.JSONObject

/**
 * `ui_widget` 工具（v134）：让 AI 在对话框内直接「展示」各类可交互 UI 组件。
 *
 * 参数 `spec` 为 JSON 字符串，结构：
 * {
 *   "type": "button|toggle|slider|progress|stat|alert|table|list|segmented|pie|rating|countdown|tabs|expandable|form|chips|steps|gauge|media|info|todo|chart|note|actions",
 *   "title": "卡片标题",
 *   "id": "可选，缺省自动生成（同名覆盖）",
 *   ...各类型字段（见各分支）
 * }
 *
 * 组件在对话框底部卡片栏渲染为真正可交互的 Compose 控件，随用户操作即时变化；
 * 全部在「对话框里展示出来」，区别于「打开某个界面」的 UI 动作工具。
 *
 * 解析逻辑统一走 [parseComponentSpec]（与聊天消息内联组件共用），[QuroChatCardStore] 负责渲染。
 */
class UiWidgetTool : QuroTool {
    override val name = "ui_widget"
    override val description = "在对话框内直接渲染一张可交互 UI 组件。用于把结构化结果以可视化、可操作的方式呈现在对话框里，而非纯文本或仅打开界面。参数 spec 为 JSON 字符串（type + 各类型字段）。" +
        "完整类型与样例见 CARD_CATALOG 卡片目录（含 input/data/media/layout/action/nav/decoration 七大归类，可据此生成上百款卡片）。" +
        "type 取值与关键字段：" +
        "button{label,command,variant?}; toggle{label,checked,command?}; slider{label,value,min,max,step,unit?,command?}; " +
        "progress{label,value,max?,suffix?}; stat{label,value,unit?,delta?,trend?}; alert{severity,text}; " +
        "table{headers:[],rows:[[]]}; list{items:[{text,sub?,selected?}],selectable?,command?}; segmented{label,options:[],selectedIndex,command?}; " +
        "pie{segments:[{name,value,color?}]}; rating{label,max,value,command?}; countdown{label,target(epoch毫秒或'yyyy-MM-dd HH:mm:ss')}; " +
        "tabs{tabs:[{title,body}],selectedIndex}; expandable{body,expanded?}; form{fields:[{key,label,value?,placeholder?,secret?}],submitCommand}; " +
        "chips{label,chips:[],selected:[],multi,command?}; steps{steps:[{title,status}],current}; gauge{label,value,max?,unit?}; " +
        "media{mediaUrl,mediaType(image|audio|video)}; info{body,align?}; " +
        "toolcall{tool,status(pending|running|done|error),progress?,message?}; stream{lines:[...]}; mediaplay{mediaType(audio|video),uri,label?}; " +
"quickreply{replies:[...],multi?}; quickaction{actions:[{label,icon,command}]}; timeline{events:[{time,title,desc?,status?}]}; heatmap{values:[],weeks?}; compare{left_*,right_*}; radar{axes:[{name,value}]}; timer{seconds,command?}; carousel{slides:[{title,body,color?}]}; kanban{columns:[{name,items:[]}]}; " +
"color{colors:[hex],label?,command?}; counter{label?,value?,min?,max?,step?,command?}; breadcrumb{crumbs:[{label,command}]}; tagcloud{tags:[{label,weight,command?}]}; badge{badges:[{label,color?,command?}]}; avatargroup{avatars:[{name,url?,command?}]}; " +
"此外，AI 消息中若含腾讯元宝链接（yb.tencent.com / yuanbao.tencent.com），会自动渲染为「元宝回答」预览卡，点击在应用内浏览器打开该回答（无需经本工具）。" +
"legacy: todo{items:[{text,done}]}; chart{chart_type,series:[{label,value}]}; note{body,lang?}; actions{actions:[{label,command}]}。" +
        "command 语法：ui_open_* / ui_toggle_* / linux:install / run:<命令> / widget:<任意自定义>，" +
        "以及 v221 新增 open:<url>（内置浏览器打开）/ copy:<文本>（复制剪贴板）/ ai:<提示词>（直接发给 AI）/ screen:<名称>（界面导航）。"
    override val parametersJson = """{"type":"object","properties":{"spec":{"type":"string","description":"组件 JSON 规格，见工具说明"}}},"required":["spec"]}"""

    override fun run(context: Context, arguments: String): String {
        return try {
            val jo = JSONObject(arguments)
            val spec = jo.optString("spec", "").ifBlank { arguments }
            val card = parseComponentSpec(spec)
                ?: return "❌ 未知组件类型或 spec 解析失败（请检查 type 与字段，支持 button/toggle/slider/progress/stat/alert/table/list/segmented/pie/rating/countdown/tabs/expandable/form/chips/steps/gauge/media/info/toolcall/stream/mediaplay/quickreply/quickaction/timeline/heatmap/compare/radar/timer/carousel/kanban 及 v221 新增 color/counter/breadcrumb/tagcloud/badge/avatargroup，详见 CARD_CATALOG；legacy 仍支持 todo/chart/note/actions；元宝链接 yb.tencent.com 会自动生成预览卡）"
            // 优先挂进聊天气泡（onCard 桥 → 当前助手消息）；桥未连接时退回全局卡片栏兜底
            val bridge = QuroUiActionBridge.onCard
            if (bridge != null) {
                bridge(card)
            } else {
                Snapshot.withMutableSnapshot { QuroChatCardStore.add(card) }
            }
            """{"ok":true,"id":"${card.id}","title":"${card.title}"}"""
        } catch (e: Exception) {
            "❌ ui_widget 解析失败：${e.message}"
        }
    }
}
