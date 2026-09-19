package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * `genui_open` —— 把活派给「生成式 UI 对话」（GenUI）画布。
 *
 * 这是 ZorvAI 对话框**唯一**能主动叫动 GenUI 的入口（反向调用）。此前只有 GenUI → ZorvAI
 * 的回推通道，ZorvAI 侧根本没有对应的工具。链路细节见 [GenUiBridge]。
 *
 * 调用后会发生什么：
 *   1. 界面从 ZorvAI 对话框切到 GenUI 画布（生成式 UI 对话模式）；
 *   2. GenUI 以 [prompt] 作为用户指令**自动开跑**，按形态路由产出产物；
 *   3. 产物经既有 push 通道（HTML / 原生小程序 / 纯文本）**自动回写进 ZorvAI 对话框**，
 *      用户从 GenUI 退回来就能看到，无需手动搬运。
 *
 * 因此：调完本工具，本轮**不要再自己画一份界面**——那会和 GenUI 的产物叠成两块画布。
 * 正确做法是给用户一句极简交代（"已经交给 GenUI 画布在做，回来就能看到"）后收尾。
 */
class GenUiOpenTool : QuroTool {

    override val name = "genui_open"

    override val description = """把任务派给「生成式 UI 对话」（GenUI）画布去生成界面——ZorvAI 主动调 GenUI 的唯一入口。

调下去之后：界面会切到 GenUI 画布并以 prompt 自动开跑；GenUI 生成的产物（界面 / 原生小程序）会自动回写进本对话框，用户退回来就能看到。**闭环，不用人搬。**

何时用（**用户点名才走，不是默认路径**）：
- 用户**点名**生成式 UI 这条路：「用生成式 UI」「进 GenUI 画布」「GenUI 画一个」「用画布做」。
- 用户要一件**整屏作品**且明说不要挤在对话框里（「整屏打开」「全屏画一个」）。
- 用户要**生成式 UI 形态**的交付（界面即回复、可点可交互的一整屏）。
- 此时 mode 可指名：要原生小程序 → "native"；要带原生能力的小程序 → "studio"；只要网页 → "html"。

何时**不要**用（重要，别把活全推出去）：
- 用户说「做个小程序 / 在这儿做个小程序 / 在这儿画个界面」→ **你自己用 `miniapp` 做**，
  就地在对话框里交付成小程序卡。**ZorvAI 自己会写小程序，不许推给 GenUI。**
- 能在本对话框内交付的（HTML 页面、ui_widget 富卡片、`miniapp` 小程序）→ 就地交付，别换屏。
- 用户只要一段文字、一个事实、一句解释 → 自己答。
- 用户明说「就在这儿回答 / 别跳屏」。
- 用户已经在 GenUI 画布里（切过去没有意义）。

判据一句话：**用户点名了 GenUI / 生成式 UI，才调本工具；否则优先在本对话框内交付。**

参数纪律：
- `prompt` 必须**自包含**：GenUI 看不到本对话框的历史，昵称、上下文、数据都要写进去。
  差的写法：`prompt="做那个"`；好的写法：`prompt="做一个记账小程序：首页显示本月支出合计、可添加一笔支出（金额/分类/备注）、按分类汇总饼图，数据存本地"`。
- **一轮只调用一次**，且调用后本轮不要再用其他画界面手段（写 HTML / miniapp 工作室）。
- 用户明确说「就在这儿回答/别跳屏」时，不要调本工具。"""

    override val parametersJson = """{
        "type":"object",
        "properties":{
            "prompt":{
                "type":"string",
                "description":"交给 GenUI 的完整任务描述（自包含：GenUI 看不到本对话历史）。要写清：做什么、要哪些功能、什么风格、有什么数据。"
            },
            "mode":{
                "type":"string",
                "enum":["canvas","native","studio","html"],
                "description":"指定 GenUI 用哪条交付路径：canvas=不指定，交给 GenUI 自己路由（默认）；native=强制原生 UI（genui_native_ui，WXML/WXSS/JS）；studio=强制小程序工作室（HTML+Page()+native.* 原生桥）；html=强制直接成稿 HTML 页面。"
            }
        },
        "required":["prompt"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrNull()
            ?: return err("参数不是合法 JSON：$arguments")
        val prompt = args.optString("prompt").trim()
        if (prompt.isEmpty()) return err("prompt 不能为空：必须给出自包含的任务描述（GenUI 看不到本对话历史）")
        val mode = args.optString("mode", GenUiBridge.MODE_CANVAS).trim().lowercase().ifEmpty { GenUiBridge.MODE_CANVAS }
        val valid = setOf(
            GenUiBridge.MODE_CANVAS, GenUiBridge.MODE_NATIVE,
            GenUiBridge.MODE_STUDIO, GenUiBridge.MODE_HTML
        )
        if (mode !in valid) return err("mode 只支持 ${valid.joinToString("/")}，收到：$mode")

        if (!GenUiBridge.isAvailable()) {
            return JSONObject()
                .put("ok", false)
                .put("error", "GenUI 画布当前不可用（界面未在可切换状态）。请改用其它方式交付：直接写一份完整 HTML 文档，或用 miniapp 工具做小程序。")
                .toString()
        }
        val opened = GenUiBridge.open(prompt = prompt, mode = mode)
        if (!opened) return err("切换到 GenUI 画布失败（入口未响应）。请改用其它方式交付。")

        return JSONObject()
            .put("ok", true)
            .put("opened", true)
            .put("mode", mode)
            .put("handoff", "已把任务交给 GenUI 画布并自动开跑")
            .put(
                "next",
                "本轮到此为止：GenUI 生成的界面 / 原生小程序会自动回写进本对话框，用户从画布退回来即可看到。" +
                    "不要再自己写 HTML、也不要再调绘制类工具——那会和 GenUI 的产物叠成两块画布。" +
                    "给用户一句极简交代即可（例如「已经交给生成式 UI 画布在做，画好了会自动回到这里」）。"
            )
            .toString()
    }

    private fun err(msg: String) = JSONObject().put("error", msg).toString()

    /** 供工具中心/调试面板列参数用（保持与其它工具同形的静态信息）。 */
    companion object {
        fun modesJson(): JSONArray = JSONArray()
            .put(GenUiBridge.MODE_CANVAS).put(GenUiBridge.MODE_NATIVE)
            .put(GenUiBridge.MODE_STUDIO).put(GenUiBridge.MODE_HTML)
    }
}
