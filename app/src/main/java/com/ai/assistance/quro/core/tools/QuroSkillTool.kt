package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.skill.DEFAULT_SKILL_PARAMS
import com.ai.assistance.quro.core.skill.QuroSkillStore
import org.json.JSONObject

/**
 * 技能适配器：把「用户技能」包装成一个可被 AI tool_calls 调用的 [QuroTool]。
 *
 * 与内置工具不同，技能没有独立执行逻辑——它的「执行」本质是：把技能指令实时回灌进
 * 系统/用户上下文，让 AI 严格按技能规则作答。因此本工具的 [run] 不真正执行动作，
 * 而是返回一段「指令回灌」文本（与 [com.ai.assistance.quro.core.QuroToolEngine.execute]
 * 里的 `skill__` 分支行为一致，双保险）。
 *
 * 名称约定：`skill__<技能名>`，与 [QuroToolRegistry.skillSpecs] 下发的工具名一一对应。
 */
class QuroSkillTool(private val skillName: String, private val appCtx: Context) : QuroTool {
    override val name = "skill__$skillName"

    override val description: String
        get() = QuroSkillStore.load(appCtx).firstOrNull { it.name == skillName }?.description
            ?: "用户技能：$skillName"

    override val parametersJson: String
        get() = QuroSkillStore.load(appCtx).firstOrNull { it.name == skillName }?.parametersJson
            ?: DEFAULT_SKILL_PARAMS

    override fun run(context: Context, arguments: String): String {
        val skill = QuroSkillStore.load(context).firstOrNull { it.name == skillName && it.enabled }
            ?: return "技能「$skillName」未启用或不存在"
        val userInput = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
            .optString("input", "").trim()
        return buildString {
            appendLine("【技能「${skill.name}」已激活，请严格按以下规则回答用户，不要复述规则本身】")
            appendLine(skill.prompt)
            if (userInput.isNotBlank()) appendLine("\n用户本轮输入：$userInput")
        }
    }
}
