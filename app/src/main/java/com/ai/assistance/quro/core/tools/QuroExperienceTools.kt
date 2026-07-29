package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.experience.ExperienceType
import com.ai.assistance.quro.core.experience.QuroCompatMarker
import com.ai.assistance.quro.core.experience.QuroExperienceEngine
import com.ai.assistance.quro.core.experience.QuroExperienceRepository
import org.json.JSONObject

/**
 * AI 经验笔记 & 自我进化工具（原创，App 本地沙盒）。
 *
 * 四个工具对应「自我进化 OODA 循环」的三条触发路径（A 被动失败 / B 主动版本自检 / C 工具缺陷）：
 * - [QuroExperienceLogTool]     沉淀一条经验（报错 / 解决方案 / 工具模式 / 版本差异）
 * - [QuroExperienceQueryTool]   检索相关经验（Feedback 闭环的「复用」入口）
 * - [QuroExperienceCorrectTool] 记录一次自我纠错（修正旧经验）
 * - [QuroExperienceVersionCheckTool] 版本兼容自检 / 列出已知兼容标记
 */
class QuroExperienceLogTool : QuroTool {
    override val name = "experience_log"
    override val description =
        "沉淀一条 AI 经验（报错/解决方案/工具模式/版本差异），让下次相关对话自动复用。当一轮对话里你遇到、解决或可复用一个问题时主动调用。" +
            "参数：{\"type\":\"error|solution|pattern|compatibility\",\"title\":\"可选标题\",\"content\":\"经验内容(必填)\"," +
            "\"tags\":\"标签数组(可选，利于检索)\",\"platform\":\"平台/版本(可选)\"," +
            "\"valid_in\":\"版本区间数组(仅 compatibility 用,可选)\",\"broken_since\":\"失效版本(可选)\",\"workaround\":\"规避方案(可选)\"}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "type":{"type":"string","description":"经验类型：error(报错)/solution(方案)/pattern(工具模式)/compatibility(版本差异)"},
            "title":{"type":"string","description":"可选标题"},
            "content":{"type":"string","description":"经验内容，必填"},
            "tags":{"type":"array","items":{"type":"string"},"description":"标签数组，利于检索"},
            "platform":{"type":"string","description":"平台/版本/环境标识，如 Android14 / Kotlin2.0"},
            "valid_in":{"type":"array","items":{"type":"string"},"description":"版本区间，如 [\"1.0\",\"1.2\"]（仅 compatibility 用）"},
            "broken_since":{"type":"string","description":"从哪个版本开始失效（仅 compatibility 用）"},
            "workaround":{"type":"string","description":"规避方案（仅 compatibility 用）"}
        },
        "required":["content"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val obj = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON：$arguments" }
        val content = obj.optString("content", "").trim()
        if (content.isEmpty()) return "缺少 content 参数（经验内容）。"
        val type = ExperienceType.from(obj.optString("type", ""))
        val title = obj.optString("title", "").trim()
        val platform = obj.optString("platform", "").trim()
        val tags = mutableListOf<String>()
        obj.optJSONArray("tags")?.let { a -> for (i in 0 until a.length()) tags.add(a.optString(i, "")) }
        val validIn = mutableListOf<String>()
        obj.optJSONArray("valid_in")?.let { a -> for (i in 0 until a.length()) validIn.add(a.optString(i, "")) }
        val brokenSince = obj.optString("broken_since", "").trim()
        val workaround = obj.optString("workaround", "").trim()
        val entry = QuroExperienceEngine(QuroExperienceRepository(context)).log(
            type = type, title = title, content = content,
            tags = tags.filter { it.isNotBlank() }, platform = platform,
            validIn = validIn.filter { it.isNotBlank() }, brokenSince = brokenSince, workaround = workaround,
        )
        val extra = if (type == ExperienceType.COMPAT && (validIn.isNotEmpty() || brokenSince.isNotBlank())) "（已记录版本兼容标记）" else ""
        return "已沉淀经验（${type.key}）${if (title.isNotBlank()) "：$title" else ""}$extra，id=${entry.id}。"
    }
}

class QuroExperienceQueryTool : QuroTool {
    override val name = "experience_query"
    override val description = "检索与当前问题相关的历史经验（报错/方案/工具模式/版本差异），用于复用已有结论、避免重复踩坑。参数：{\"query\":\"检索关键词或问题描述\",\"top_n\":\"返回条数(默认5)\"}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "query":{"type":"string","description":"检索关键词或问题描述"},
            "top_n":{"type":"integer","description":"返回条数，默认 5"}
        },
        "required":["query"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val q = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON：$arguments" }
            .optString("query", "").trim()
        if (q.isEmpty()) return "缺少 query 参数。"
        val topN = runCatching { JSONObject(arguments).optInt("top_n", 5) }.getOrElse { 5 }.coerceIn(1, 20)
        val results = QuroExperienceEngine(QuroExperienceRepository(context)).queryRelevant(q, topN, bumpReuse = true)
        if (results.isEmpty()) return "没有与「$q」相关的历史经验。"
        val sb = StringBuilder("相关经验（按相关性）：\n")
        results.forEachIndexed { i, e ->
            sb.append("${i + 1}. [${e.type.key}]")
            if (e.title.isNotBlank()) sb.append(" ${e.title}")
            sb.append("（复用 ${e.reuseCount} 次")
            if (e.correctionCount > 0) sb.append("，已修正 ${e.correctionCount} 次")
            sb.append("）\n   ${e.content}\n")
            if (e.tags.isNotEmpty()) sb.append("   标签：${e.tags.joinToString(", ")}\n")
        }
        return sb.toString().trim()
    }
}

class QuroExperienceCorrectTool : QuroTool {
    override val name = "experience_correct"
    override val description = "记录一次自我纠错：某条经验被证明过时/错误时，标记它已修正并写下原因与新结论。参数：{\"id\":\"经验 id\",\"was\":\"此前错误结论\",\"reason\":\"为何失效\",\"fix\":\"正确做法\"}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "id":{"type":"string","description":"要修正的经验 id（来自 experience_query / experience_log 的返回）"},
            "was":{"type":"string","description":"此前错误或过时的结论"},
            "reason":{"type":"string","description":"为何失效/错误的根因"},
            "fix":{"type":"string","description":"正确做法或新结论"}
        },
        "required":["id","fix"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val obj = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON：$arguments" }
        val id = obj.optString("id", "").trim()
        if (id.isEmpty()) return "缺少 id 参数。"
        val was = obj.optString("was", "").trim()
        val reason = obj.optString("reason", "").trim()
        val fix = obj.optString("fix", "").trim()
        if (fix.isEmpty()) return "缺少 fix 参数（正确做法）。"
        val ok = QuroExperienceEngine(QuroExperienceRepository(context)).recordCorrection(id, was, reason, fix)
        return if (ok) "已记录自我纠错并标记经验 $id 为已修正。" else "未找到 id=$id 的经验，无法修正（请先用 experience_query 确认 id）。"
    }
}

class QuroExperienceVersionCheckTool : QuroTool {
    override val name = "experience_version_check"
    override val description = "版本兼容自检 / 列出已知兼容标记。给定 platform+version 时，返回对该版本已失效的兼容项（提示规避方案）；不给版本时列出全部已知兼容标记。参数：{\"platform\":\"如 Android/Kotlin\",\"version\":\"如 14 或 1.0.308\"}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "platform":{"type":"string","description":"平台/框架名，如 Android / Kotlin / MIUI"},
            "version":{"type":"string","description":"版本号，如 14 或 1.0.308"}
        }
    }"""

    override fun run(context: Context, arguments: String): String {
        val obj = runCatching { JSONObject(arguments) }.getOrElse { return "参数不是合法 JSON：$arguments" }
        val platform = obj.optString("platform", "").trim()
        val version = obj.optString("version", "").trim()
        val repo = QuroExperienceRepository(context)
        val (_, comps, _) = repo.loadAll()
        if (version.isNotBlank()) {
            val broken = QuroExperienceEngine(repo).versionSelfCheck(platform, version)
            if (broken.isEmpty()) return "未发现有针对 ${platform.ifBlank { "该平台" }} $version 的失效兼容项。"
            val sb = StringBuilder("⚠️ 对 ${platform.ifBlank { "该平台" }} $version 已失效的兼容项：\n")
            broken.forEach { m ->
                sb.append("- ${m.subject}")
                if (m.brokenSince.isNotBlank()) sb.append("（自 ${m.brokenSince} 起失效）")
                sb.append("\n  规避：${m.workaround.ifBlank { "（无）" }}\n")
            }
            return sb.toString().trim()
        }
        if (comps.isEmpty()) return "当前没有已知的版本兼容标记。"
        val sb = StringBuilder("已知版本兼容标记：\n")
        comps.forEach { m ->
            sb.append("- ${m.subject}：正常 ${m.validIn.joinToString(",").ifBlank { "—" }}")
            if (m.brokenSince.isNotBlank()) sb.append("；自 ${m.brokenSince} 起失效")
            sb.append("\n  规避：${m.workaround.ifBlank { "（无）" }}\n")
        }
        return sb.toString().trim()
    }
}
