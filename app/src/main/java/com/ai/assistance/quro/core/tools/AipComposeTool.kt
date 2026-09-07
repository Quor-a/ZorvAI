package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONObject
import com.ai.assistance.quro.core.canvas.Aip
import com.ai.assistance.quro.core.canvas.AipConvert

/**
 * 后台 AIP 排版合成工具（工具调用形式）。
 *
 * 定位：让模型在需要「整篇长文档 / PPT / 报告 / 思维导图」时，以 **工具调用** 的形式产出 AIP 信封，
 * 而非在回复正文里直接写 ```aip 围栏。工具做 L1 字段修复与规范化后回传规范化信封，
 * 对话框据此用 AIP Canvas 引擎（B 通道）渲染成原生排版卡片——即「工具调用形式，最后渲染在对话框」。
 *
 * 依赖引擎（全部零三方库、自研）：
 *  - 解析/规范化：core/canvas/Aip.kt（runCatching 容错 + 四级降级，未知块走 Fallback 兜底）。
 *  - 形态互转/导出序列化：core/canvas/AipConvert.kt（toMarkdown / toPptxText）。
 *  - 文档落地（可选 export）：AiwpsCreateTool（自研 OOXML，生成真实 .docx/.pptx/.md/.pdf，不引第三方）。
 *
 * 与 inline 围栏的关系：aip_compose 是「工具调用」主路径；```aip 围栏作为兜底仍可用，二者渲染同一引擎。
 */
class AipComposeTool : QuroTool {
    override val name = "aip_compose"
    override val description = "📐 后台 AIP 排版合成：用于整篇长文档 / PPT / 报告 / 思维导图的结构化排版。" +
        "传入 AIP 信封（kind=doc|deck|mindmap + blocks 块数组），工具做字段修复与规范化后回传，" +
        "对话框用原生排版引擎渲染成精美卡片（doc=文档流带分节、deck=16:9 横滑幻灯片、mindmap=导图）。" +
        "可选 export=docx|pptx|md|pdf 时一并生成可分享/打开的真实文件（自研 OOXML，无需联网）。" +
        "适用：长文档、演示文稿、调研报告、建设方案、结构化长回答。单张流程图/架构图用 mermaid，网页成品用 ```html，不要滥用本工具。" +
        "参数 {\"kind\":\"doc|deck|mindmap\",\"title\":\"标题\",\"subtitle\":\"副标题\",\"author\":\"作者\"," +
        "\"accent\":\"#RRGGBB\",\"blocks\":[{\"id\":\"b1\",\"type\":\"...\",\"data\":{...}}],\"export\":\"docx|pptx|md|pdf|null\"}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "kind":{"type":"string","description":"排版形态：doc=文档流（目录+分节）/deck=16:9 横滑幻灯片/mindmap=思维导图。默认 doc"},
            "title":{"type":"string","description":"文档标题（deck 用作封面标题）"},
            "subtitle":{"type":"string","description":"副标题"},
            "author":{"type":"string","description":"作者（可选）"},
            "accent":{"type":"string","description":"主题色 #RRGGBB（可选，默认品牌蓝）"},
            "blocks":{"type":"array","description":"AIP 块数组，每个块 {\"id\":\"b1\",\"type\":\"...\",\"data\":{...}}。支持的 type 见系统提示「AIP 块型」：heading/paragraph/list/table/code/quote/callout/divider/image/chart/columns/steps/timeline/mindmap/slide/section/html"},
            "export":{"type":"string","description":"可选：docx/pptx/md/pdf，生成可分享文件（走 aiwps_create 自研引擎）。不填则仅对话框内渲染、不落文件"}
        },
        "required":["kind","blocks"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrNull()
            ?: return "参数不是合法 JSON：$arguments"
        val kind = jo.optString("kind", "doc").ifBlank { "doc" }.lowercase()
        if (kind !in setOf("doc", "deck", "mindmap")) {
            return "aip_compose 的 kind 必须是 doc / deck / mindmap，收到：$kind"
        }
        val blocks = jo.optJSONArray("blocks")
        if (blocks == null || blocks.length() == 0) return "缺少非空 blocks 数组"

        // 组装规范化信封（meta/theme 给默认值，满足 Aip.parse 的最简结构）
        val env = JSONObject().apply {
            put("v", 1)
            put("kind", kind)
            put("meta", JSONObject().apply {
                put("title", jo.optString("title", ""))
                put("subtitle", jo.optString("subtitle", ""))
                put("author", jo.optString("author", ""))
            })
            put("theme", JSONObject().apply {
                put("name", "aurora")
                put("accent", jo.optString("accent", ""))
            })
            put("blocks", blocks)
        }

        // L1 字段修复 + 规范化校验（复用 Aip 解析，确保块结构合法、未知块走 Fallback）
        val parsed = Aip.parse(env.toString())
        val envelope = parsed.envelope
        if (envelope == null) {
            return "AIP 信封解析失败（降级级别：${parsed.degradation}），请检查 blocks 结构是否合法。原始信封：\n${env}"
        }

        // 导出（可选）：走自研 OOXML 引擎落地为真实文件
        val export = jo.optString("export", "").trim().lowercase()
        val exportMsg = if (export.isNotBlank()) {
            val content = if (export == "pptx") AipConvert.toPptxText(envelope) else AipConvert.toMarkdown(envelope)
            val r = runCatching {
                AiwpsCreateTool().run(
                    context,
                    JSONObject().apply {
                        put("type", export)
                        put("content", content)
                        put("title", envelope.title)
                        put("filename", AipConvert.exportFileStem(envelope))
                    }.toString(),
                )
            }.getOrElse { "导出失败：${it.message}" }
            "\n\n[导出] $r"
        } else ""

        // 回传规范化信封（对话框据此渲染）；导出信息作为尾部文本，渲染端会拆分显示
        return env.toString() + exportMsg
    }
}
