package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONArray
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
        "blocks 可省略：只传 sections（[{title,body}]）或 content/markdown/text 整段正文时，工具自动合成块，不会因缺 blocks 报错。" +
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
            "blocks":{"type":"array","description":"AIP 块数组，每个块 {\"id\":\"b1\",\"type\":\"...\",\"data\":{...}}。支持的 type 见系统提示「AIP 块型」：heading/paragraph/list/table/code/quote/callout/divider/image/chart/columns/steps/timeline/mindmap/slide/section/html。可选：省略时用 sections 或 content 自动合成"},
            "sections":{"type":"array","description":"可选：分节数组 [{title,level,body|content|blocks}]，blocks 省略时自动转成 section+段落块"},
            "content":{"type":"string","description":"可选：整段正文（markdown 或纯文本），blocks 省略时按标题/段落自动切块。别名 markdown/text"},
            "export":{"type":"string","description":"可选：docx/pptx/md/pdf，生成可分享文件（走 aiwps_create 自研引擎）。不填则仅对话框内渲染、不落文件"}
        },
        "required":["kind"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrNull()
            ?: return "参数不是合法 JSON：$arguments"
        val kind = jo.optString("kind", "doc").ifBlank { "doc" }.lowercase()
        if (kind !in setOf("doc", "deck", "mindmap")) {
            return "aip_compose 的 kind 必须是 doc / deck / mindmap，收到：$kind"
        }
        // blocks 容错：AI 调用时可能没把 blocks 数组传全（大文档常被模型省略）。
        // blocks 缺失/为空时，从 title/content/markdown/text/sections 等字段自动构造块，
        // 保证「缺参数」也能渲染出完整文档，不白屏、不报「缺少非空 blocks 数组」。
        val blocks = jo.optJSONArray("blocks")
        val effectiveBlocks: JSONArray = when {
            blocks != null && blocks.length() > 0 -> blocks
            else -> synthesizeBlocks(jo)
        }

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
            put("blocks", effectiveBlocks)
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

    /**
     * blocks 缺失/为空时的兜底构造：AI 常省略大 blocks 数组、只传标题+正文。
     * 从多形态参数合成 AIP 块，保证「缺参数」也能渲染出文档，不白屏、不报错。
     * 优先级：
     *   1. sections：数组 [{title,body|content|blocks:[...]}] → section + 其下段落/子块
     *   2. content / markdown / text：整段文本 → 按空行/换行切段落块
     *   3. 都没有 → 单个 paragraph 占位（标题已有，不白屏）
     */
    /** @suppress 单测可调（internal） */
    internal fun synthesizeBlocks(jo: JSONObject): JSONArray {        val out = JSONArray()

        // 1) sections 数组
        val sections = jo.optJSONArray("sections")
        if (sections != null && sections.length() > 0) {
            for (i in 0 until sections.length()) {
                val so = sections.optJSONObject(i) ?: continue
                val secTitle = so.optString("title", "").trim()
                if (secTitle.isNotBlank()) {
                    out.put(JSONObject().apply {
                        put("id", "sec$i")
                        put("type", "section")
                        put("data", JSONObject().apply {
                            put("level", so.optInt("level", 1).coerceIn(1, 4))
                            put("title", secTitle)
                        })
                    })
                }
                // 该节正文：body/content/paragraphs/blocks 数组
                val body = so.optString("body", "").ifBlank { so.optString("content", "") }
                if (body.isNotBlank()) {
                    body.split("\n\n", "\n").forEach { para ->
                        val p = para.trim()
                        if (p.isNotBlank()) out.put(paragraphBlock(p))
                    }
                }
                val paras = so.optJSONArray("paragraphs")
                if (paras != null) for (j in 0 until paras.length()) {
                    val p = paras.optString(j, "").trim()
                    if (p.isNotBlank()) out.put(paragraphBlock(p))
                }
                so.optJSONArray("blocks")?.let { sub ->
                    for (j in 0 until sub.length()) {
                        val b = sub.optJSONObject(j) ?: continue
                        out.put(b)
                    }
                }
            }
            return out
        }

        // 2) content / markdown / text 整段文本
        val content = jo.optString("content", "")
            .ifBlank { jo.optString("markdown", "") }
            .ifBlank { jo.optString("text", "") }
            .trim()
        if (content.isNotBlank()) {
            // 简单启发式：## 开头当标题，其余当段落
            var id = 0
            content.split("\n\n", "\n").forEach { raw ->
                val line = raw.trim()
                if (line.isBlank()) return@forEach
                val h = Regex("^(#{1,6})\\s+(.*)$").matchEntire(line)
                if (h != null) {
                    out.put(JSONObject().apply {
                        put("id", "b${id++}")
                        put("type", "heading")
                        put("data", JSONObject().apply {
                            put("level", h.groupValues[1].length.coerceIn(1, 6))
                            put("text", h.groupValues[2])
                        })
                    })
                } else {
                    out.put(paragraphBlock(line, id++))
                }
            }
            if (out.length() > 0) return out
        }

        // 3) 兜底：单段落占位（标题已由信封头部渲染，不白屏）
        out.put(paragraphBlock("（文档内容待补充）", 0))
        return out
    }

    private fun paragraphBlock(text: String, id: Int = 0): JSONObject = JSONObject().apply {
        put("id", "b$id")
        put("type", "paragraph")
        put("data", JSONObject().apply { put("text", text) })
    }
}
