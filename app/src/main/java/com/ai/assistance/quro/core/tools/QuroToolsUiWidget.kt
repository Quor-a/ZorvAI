package com.ai.assistance.quro.core.tools

import android.content.Context
import androidx.compose.runtime.snapshots.Snapshot
import com.ai.assistance.quro.core.cards.QuroChatCard
import com.ai.assistance.quro.core.cards.CardSdk
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
    // 组件清单不手抄：从 CardSdk 名册运行时生成（数量见 CardSdk.typeCount），名册一扩这里自动同步。
    // 上面那段「常用几类字段」是手写的，只覆盖早期二十来种，**故意保留**：它是唯一常驻的字段写法来源，
    // 删了模型就只能靠card_catalog 现查现写（多一轮工具调用）。但必须标明它不是全量，否则模型会以为
    // 清单就这些 —— 末尾的 compactCatalog 才是全量。
    // 完整样例用 card_catalog 按需拉，避免十几 KB 样例常驻进系统提示词。
    override val description = "在对话框内直接渲染一张可交互 UI 组件。用于把结构化结果以可视化、可操作的方式呈现在对话框里，而非纯文本或仅打开界面。参数 spec 为 JSON 字符串（type + 各类型字段）。" +
        "完整类型与样例见 card_catalog 卡片目录工具（可传 category 或 types 过滤；归类共 9 个：input/data/layout/action/nav/media/flow/decoration/aiwrite）。" +
        "常用几类的 type 与关键字段（**这不是全量**，全量清单见本描述末尾的类目列表与 card_catalog）：" +
        "button{label,command,variant?}; toggle{label,checked,command?}; slider{label,value,min,max,step,unit?,command?}; " +
        "progress{label,value,max?,suffix?}; stat{label,value,unit?,delta?,trend?}; alert{severity,text}; " +
        "table{headers:[],rows:[[]]}; list{items:[{text,sub?,selected?}],selectable?,command?}; segmented{label,options:[],selectedIndex,command?}; " +
        "pie{segments:[{name,value,color?}]}; rating{label,max,value,command?}; countdown{label,target(epoch毫秒或'yyyy-MM-dd HH:mm:ss')}; " +
        "tabs{tabs:[{title,body}],selectedIndex}（body 可写纯文本，也可写一个组件 spec（形如 {type: table, ...} 的 JSON），还可用 content/node 键，或给 children 数组放多个组件；expandable{body,expanded?}（body 同样可写组件 spec）; form{fields:[{key,label,value?,placeholder?,secret?}],submitCommand}; " +
        "chips{label,chips:[],selected:[],multi,command?}; steps{steps:[{title,status}],current}; gauge{label,value,max?,unit?}; " +
        "media{mediaUrl,mediaType(image|audio|video)}; info{body,align?}; " +
        "toolcall{tool,status(pending|running|done|error),progress?,message?}; stream{lines:[...]}; mediaplay{mediaType(audio|video),uri,label?}; " +
"quickreply{replies:[...],multi?}; quickaction{actions:[{label,icon,command}]}; timeline{events:[{time,title,desc?,status?}]}; heatmap{values:[],weeks?}; compare{left_*,right_*}; radar{axes:[{name,value}]}; timer{seconds,command?}; carousel{slides:[{title,body,color?}]}; kanban{columns:[{name,items:[]}]}; " +
"color{colors:[hex],label?,command?}; counter{label?,value?,min?,max?,step?,command?}; breadcrumb{crumbs:[{label,command}]}; tagcloud{tags:[{label,weight,command?}]}; badge{badges:[{label,color?,command?}]}; avatargroup{avatars:[{name,url?,command?}]}; " +
"mermaid{source(多行 Mermaid 文本),theme?(default|dark|forest|neutral|base,缺省按系统深浅色自动选)}：AI 自写的可视化图表（flowchart/时序图/状态机/类图/思维导图/git图…），客户端不内置固定图，只渲染 AI 下发的 Mermaid 源码；" +
"此外，AI 消息中若含链接（yb.tencent.com / yuanbao.tencent.com），会自动渲染为「链接回答」预览卡，点击在应用内浏览器打开该回答（无需经本工具）。" +
"miniapp{html,config?}：AI 生成 Web 应用代码（HTML+JS+CSS），在对话框内实时渲染为可交互的 Web 应用页面，支持 Page/Component 生命周期、data-bind 数据绑定、data-action 事件绑定；" +
"composite{layout(stack|tabs|accordion),children:[<组件spec数组>],description?}：多语言组合卡——把多个子卡聚合成一个整体一起展示，同时支持「只渲染其中一个」；layout=stack 时各子卡顺序堆叠且可点「单独」单独全宽渲染，layout=tabs 时子卡以标签页呈现一次只显示一个，layout=accordion 时各子卡独立折叠；children 内每个元素都是完整的组件 spec（可嵌套 composite），用于「Web 应用后端+前端组合完成」「可视化弹窗+可视化编程+多语言渲染」等需要组合且互不干扰的产物；" +
"legacy: todo{items:[{text,done}]}; chart{chart_type,series:[{label,value}]}; note{body,lang?}; actions{actions:[{label,command}]}。" +
        "command 语法：ui_open_* / ui_toggle_* / linux:install / run:<命令> / widget:<任意自定义>，" +
        "以及 v221 新增 open:<url>（内置浏览器打开）/ copy:<文本>（复制剪贴板）/ ai:<提示词>（直接发给 AI）/ screen:<名称>（界面导航）。" +
        "可用组件共 ${CardSdk.typeCount} 种，按类目：${CardSdk.compactCatalog()}。" +
        "要写某类的完整字段与样例，先调 card_catalog（参数 category 或 types），别凭空猜字段名。" +
        "也可以直接在正文里下发卡片围栏：card（单个 JSON 对象）、cards（数组或组合卡）、cardui（A2UI 邻接表）、cardjson（**一行一个 JSON**，流式友好，逐行独立容错；组合卡请用 cards）。" +
        "围栏头后可跟属性（空格分隔）：开关 compact（内边距收紧，一组小卡片必给）/ scroll（超高内部滚动）/ bordered / flat / dense；带值 title=组级标题（各卡自带标题时以卡为准）/ theme=主题档位，只认 accent(默认)/warn/danger/plain，其它值一律降级为默认。" +
        "例：```cards title=Q3 复盘 theme=accent compact\n[{\"type\":\"stat\",...}]\n```" +
        "🔴 下发后要改这张卡（刷新进度、勾掉一项、改单元格、走字倒计时），用 card_patch " +
        "按 JSON Pointer 改字段，**不要重发整张卡**：重发既费 token 又容易把想保留的字段改掉。" +
        "不确定该改哪个路径时先 card_patch(describe=true, cardId=<下发时的 id>) 看合法路径清单。id 建议自己指定（如 order_1），否则无法后续 patch。"
    override val parametersJson = """{"type":"object","properties":{"spec":{"type":"string","description":"组件 JSON 规格，见工具说明"}}},"required":["spec"]}"""

    override fun run(context: Context, arguments: String): String {
        return try {
            val jo = JSONObject(arguments)
            val spec = jo.optString("spec", "").ifBlank { arguments }
            val card = parseComponentSpec(spec)
                ?: return "❌ 未知组件类型或 spec 解析失败。可用 type 共 ${CardSdk.typeCount} 种：${CardSdk.compactCatalog()}。调 card_catalog 可取完整样例。"
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
