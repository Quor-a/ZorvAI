package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.skill.QuroSkillStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 宿主技能库查询工具：告诉 AI「库里有哪些技能」，供 `skill` 工具按需加载时对照 id。
 *
 * ## 2026-11-05 删除了 SkillEnableTool / SkillDisableTool
 * 「启用 / 禁用」只改本地一个布尔值：AI 既不知道用户开了什么，也看不到技能正文，
 * 却会在用户没注意时把技能从列表里藏起来 —— 开关根本不是一种发送方式，是错误的作用方式。
 *
 * 现在技能只有一条作用路径：**用户在对话框选择 → 技能全文随那条消息发给 AI → AI 直接遵循**
 * （见 `QuroSkillStore.attachedBlock`）。因此 AI 侧只保留「列举」，不再有任何能改写
 * 技能可见状态的接口；`skill_list` 的输出也不再出现 enabled 字段，避免模型误以为存在开关。
 */
class SkillListTool : QuroTool {
    override val name = "skill_list"

    override val description =
        "列举技能库中的所有技能：返回名称、id、所属套件与简介（优先中文）。" +
            "技能无需启用：用户在对话框选中某个技能后，它的完整指令会随那条消息一起发给你。" +
            "参数为 {} 或 {\"query\":\"关键词\"}。"

    override val parametersJson =
        """{"type":"object","properties":{"query":{"type":"string","description":"按名称/简介/套件过滤，留空返回全部"}},"required":[]}"""

    override fun run(context: Context, arguments: String): String {
        val query = runCatching { JSONObject(arguments) }.getOrNull()
            ?.optString("query", "")?.trim().orEmpty()
        val list = QuroSkillStore.load(context).let { all ->
            if (query.isBlank()) all
            else all.filter { s ->
                s.name.contains(query, ignoreCase = true) ||
                    s.description.contains(query, ignoreCase = true) ||
                    s.descriptionZh.contains(query, ignoreCase = true) ||
                    s.suite.contains(query, ignoreCase = true)
            }
        }
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("name", s.name)
                    put("id", s.id)
                    put("suite", s.suite)
                    put("description", if (s.descriptionZh.isNotBlank()) s.descriptionZh else s.description)
                    put("promptChars", s.prompt.length)
                }
            )
        }
        return "共 ${list.size} 个技能（技能由用户在对话框选择后随消息发送，不存在启用/禁用开关）：\n" +
            arr.toString(2)
    }
}
