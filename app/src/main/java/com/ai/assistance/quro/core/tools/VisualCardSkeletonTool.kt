package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.cards.WidgetSkeletons

/**
 * 🔴 `visual_card_skeleton`：可视化小卡片的**参考样板检索工具**（2026-11-06 降级定位）。
 *
 * ## 定位反转（2026-11-06 用户拍板）
 * 「可视化小卡片要的不是只支持内置小卡，要的是 AI 可以自己写出来没有的小卡片。」
 * 旧版把这个工具说成「先取骨架再填数」的必经两步，模型于是只会从 13 份内置骨架里
 * 挑一张填数——库里没有的场景（收件箱/番茄钟/登机牌/战绩…设计稿 180+ 场景）就写不出来。
 * 现在降级为**参考样板**：`visual_card` 的 html 参数本身就是自由创作空间，场景对得上
 * 才取样板改造，对不上直接自己写。两工具均保持常驻（QuroToolRouter.kt:104）。
 *
 * ## 分类边界（对用户那句「可视化小卡片不是动态UI组件和富组件」的落地）
 * 本工具只服务**可视化小卡片**这一独立分类：
 *   ≠ 动态UI组件（```quro-ui 原生可交互控件）
 *   ≠ 富组件（ui_card / ui_widget 预制组件名册）
 *   ≠ 可视化弹窗（visual_popup 等盖在屏幕上的窗口）
 *   ≠ 网页预览（```html 整页）
 *   ≠ 旧 ```quro-card Canvas 围栏（legacy，不再用于小卡片）
 */
class VisualCardSkeletonTool : QuroTool {
    override val name = "visual_card_skeleton"

    override val description = """visual_card_skeleton：取回「可视化小卡片」的**参考样板** HTML。
注意：visual_card 的 html 参数**本身就能自由写任何场景**，这个工具只是「没把握时的参考」——场景与 13 份样板对得上（天气/新闻/日程/终端/播放器/行情/待办/监控/位置/电量/快捷/时钟/摘录）就取一份改造；对不上（收件箱/番茄钟/登机牌/汇率/任何长尾场景）**直接自己写**，不要硬套。
样板自包含、零依赖、亮暗自适应；卡壳（圆角20/标题栏/主题/viewport）由宿主提供，不要在样板里再包一层卡片。
不传 kind（或 kind=list）返回全部 13 份目录。

示例：visual_card_skeleton(kind="clock") → 返回世界时钟样板；替换 {{数据位}} 后交给 visual_card，也可以只借鉴它的局部结构。"""

    override val parametersJson = """{
        "type": "object",
        "properties": {
            "kind": {
                "type": "string",
                "description": "样板种类：weather/news/calendar/terminal/music/market/todo/monitor/location/battery/shortcuts/clock/quote；留空或 list 返回目录"
            }
        },
        "required": []
    }"""

    override fun run(context: Context, arguments: String): String {
        val o = runCatching { org.json.JSONObject(arguments) }.getOrNull()
        val kind = o?.optString("kind", "").orEmpty().trim()
        if (kind.isBlank() || kind.equals("list", ignoreCase = true)) return WidgetSkeletons.catalog()
        val skeleton = WidgetSkeletons.get(kind)
            ?: return "未知 kind「$kind」。\n\n" + WidgetSkeletons.catalog()
        return buildString {
            append("【${skeleton.name} · ${skeleton.kind} · ${skeleton.size}】${skeleton.useFor}\n")
            append("这是**参考样板**：把 {{数据位}} 换成真实内容后作为 visual_card 的 html 参数；")
            append("也可以只借鉴局部结构、其余自由发挥。不要引外部资源、不要再包一层卡片边框。\n\n")
            append(skeleton.html)
        }
    }
}
