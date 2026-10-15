package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.content.Context
import com.ai.assistance.quro.core.skill.QuroSkill
import com.ai.assistance.quro.core.skill.QuroSkillStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 宿主技能库管理工具（issue #17 问题 3）。
 *
 * 此前技能库只有「运行」通道（skill__<name> 让 AI 调起某个技能），但没有对「库本身」的可调接口：
 * AI 既看不到自己有哪些技能，也无法在对话里按需启用 / 禁用某个技能。本文件补齐这一缺口。
 */
class SkillListTool : QuroTool {
    override val name = "skill_list"
    override val description =
        "列举宿主技能库中的所有技能：返回每个技能的名称、启用状态、是否可调用、所属套件与简介（优先中文）。参数为 {} 或 {\"enabled_only\":false}。"
    override val parametersJson =
        """{"type":"object","properties":{"enabled_only":{"type":"boolean","description":"仅返回已启用的技能，默认 false"}},"required":[]}"""
    override fun run(context: Context, arguments: String): String {
        val enabledOnly = runCatching { JSONObject(arguments) }.getOrNull()?.optBoolean("enabled_only", false) ?: false
        val list = QuroSkillStore.load(context).let { if (enabledOnly) it.filter { s -> s.enabled } else it }
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("name", s.name)
                    put("id", s.id)
                    put("enabled", s.enabled)
                    put("callable", s.callable)
                    put("suite", s.suite)
                    put("description", if (s.descriptionZh.isNotBlank()) s.descriptionZh else s.description)
                }
            )
        }
        return "共 ${list.size} 个技能：\n" + arr.toString(2)
    }
}

class SkillEnableTool : QuroTool {
    override val name = "skill_enable"
    override val description = "启用宿主技能库中的某个技能（按名称或 id 匹配）。参数为 {\"name\":\"技能名\"}。"
    override val parametersJson =
        """{"type":"object","properties":{"name":{"type":"string","description":"技能名称或 id"}},"required":["name"]}"""
    override fun run(context: Context, arguments: String): String = setSkillEnabled(context, arguments, true)
}

class SkillDisableTool : QuroTool {
    override val name = "skill_disable"
    override val description = "禁用宿主技能库中的某个技能（按名称或 id 匹配）。参数为 {\"name\":\"技能名\"}。"
    override val parametersJson =
        """{"type":"object","properties":{"name":{"type":"string","description":"技能名称或 id"}},"required":["name"]}"""
    override fun run(context: Context, arguments: String): String = setSkillEnabled(context, arguments, false)
}

private fun setSkillEnabled(context: Context, arguments: String, enabled: Boolean): String {
    val name = runCatching { JSONObject(arguments) }.getOrNull()?.optString("name", "")?.trim() ?: ""
    if (name.isEmpty()) return "缺少 name 参数"
    val list = QuroSkillStore.load(context)
    val target = list.firstOrNull {
        it.name == name || it.id == name || QuroSkill.toolNameOf(it.name) == name
    } ?: return "未找到技能：$name"
    QuroSkillStore.addOrUpdate(
        context,
        target.copy(enabled = enabled, updatedAt = System.currentTimeMillis())
    )
    return "已${if (enabled) "启用" else "禁用"}技能：${target.name}"
}
