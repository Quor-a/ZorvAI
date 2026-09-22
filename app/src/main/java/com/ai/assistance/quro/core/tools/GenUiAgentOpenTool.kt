package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.Intent
import com.ai.assistance.quro.genui.aiapp.GenUiAgentActivity
import org.json.JSONObject

/**
 * 打开内置的 **GenUI Agent**（生成式界面智能体）。
 *
 * 背景：本 App 有两条「生成式界面」通道，加上本工具内置的 GenUI-Agent，共**两套范式**：
 *  1. `miniapp`         → **小程序工作室**（自研引擎 `miniapp-sdk`，HTML+JS+CSS 或微信语法 WXML/WXSS/JS）
 *  2. 本工具            → **内置完整的开源项目 [GenUI-Agent](https://github.com/Quor-a/GenUI-Agent)**
 *                        （去品牌化后落在 `com.ai.assistance.quro.genui.aiapp` / `com.ai.assistance.quro.genui.sdk`）
 *
 * 两者**互相独立、互不替代**，别混为一谈（范式不同）：
 *  - 通道 1：AI 写 **HTML / WXML+WXSS+JS**，用 WebView 或自研引擎画出来；
 *  - 通道 2（本工具）：AI 产出 **GenUI JSON DSL**，SDK 直接映射成**原生 Compose 组件**（530+ 组件，
 *    含样式 / 动画 / 交互 / 状态 / 技能），表单输入经 `collectFrom` 聚合回 AI 继续对话。
 *
 * 本工具是「独立应用」这一路的入口：模型据此知道「要一块原生可交互界面」时可以派给 GenUI Agent。
 * 它是一个**独立全屏应用**（有自己的对话页、历史、宠物与右侧抽屉），不强占 ZorvAI 的对话框；
 * 但其**模型配置 / 灵魂人格 / 工具集全部复用 ZorvAI 主设置**（见 `genui/aiapp/brain/ZorvBrain.kt`），
 * 不再自带独立的模型与灵魂配置页。
 */
class GenUiAgentOpenTool : QuroTool {

    override val name: String = "genui_agent_open"

    override val parametersJson: String = """
        {
          "type": "object",
          "properties": {
            "prompt": {
              "type": "string",
              "description": "可选：想让它一进去就做的界面/应用需求，例如「做一个 BMI 计算器」「画一个季度销售看板」。留空则只打开界面。"
            }
          },
          "required": []
        }
    """.trimIndent()

    override val description: String = """
打开内置的 **GenUI Agent**（生成式界面智能体，内置完整开源项目 GenUI-Agent）。

什么时候用它：
- 用户要一个**可交互的界面 / 小应用**（计算器、表单、看板、小游戏、设置页…），且希望是**原生控件**而不是网页；
- 用户明确说「用 GenUI」「打开 GenUI Agent」「生成式界面」；
- 你要交付的是一块**能点、能填、能改**的界面，而不是一段文字或一张图。

它与另外两条「生成式界面」通道的区别（别选错）：
- 单张流程图 / 架构图 / 思维导图 → 直接用 ```mermaid 围栏，**不要**开 GenUI Agent；
- 一个网页成品（HTML/JS 页面）→ 用 ```html 围栏；
- 要**网页形态**的界面成品、且希望产物落在对话框里 → `genui_open`（对话框内生成式 UI 画布）；
- 要**小程序形态**（HTML+JS+CSS 或微信语法 WXML/WXSS/JS）的工程 → `miniapp`（小程序工作室）；
- 要一块**原生可交互界面**（GenUI JSON DSL → 原生 Compose 组件，530+ 组件）→ **本工具**。

调用后 GenUI Agent 会以全屏独立界面启动，它有自己的一套独立界面与应用内状态；但模型配置、
灵魂人格与工具集都读 ZorvAI 的主设置（在主设置里配一次即可），ZorvAI 侧不会替它生成内容。
""".trimIndent()

    override fun run(context: Context, arguments: String): String {
        val prompt = runCatching {
            JSONObject(arguments.ifBlank { "{}" }).optString("prompt", "").trim()
        }.getOrElse { "" }

        return try {
            val intent = Intent(context, GenUiAgentActivity::class.java).apply {
                // 工具可能从 Application/Service 上下文调用，必须带 NEW_TASK。
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (prompt.isNotEmpty()) putExtra(GenUiAgentActivity.EXTRA_PROMPT, prompt)
            }
            context.startActivity(intent)
            if (prompt.isEmpty()) {
                "已打开 GenUI Agent（独立全屏应用，模型配置/人格/工具集沿用 ZorvAI 主设置）。请在那里继续描述你要的界面。"
            } else {
                "已打开 GenUI Agent，并带上需求：「${prompt.take(80)}」。它是独立界面，后续交互在 GenUI Agent 内完成。"
            }
        } catch (t: Throwable) {
            "打开 GenUI Agent 失败：${t.message ?: t.javaClass.simpleName}"
        }
    }
}
