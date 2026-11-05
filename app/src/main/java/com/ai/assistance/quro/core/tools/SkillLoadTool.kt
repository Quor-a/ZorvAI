package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.skill.QuroSkillStore

/**
 *
 * ## 与旧注入机制的关系（「skills 开关没用」的根治）
 * 系统提示词从此只带 **Skills Catalog**（每技能一行元数据，≤64 条）；
 * 模型看到相关技能后调本工具 → 全文作为 tool result 进入当轮上下文
 * （「Skill loaded for this turn」语义：只活在当轮，下轮不自动续）。
 * 开关（enabled）= 是否进 Catalog + 是否能被本工具命中，语义立刻可感。
 *
 */
class SkillLoadTool(private val personaId: String = "") : QuroTool {
    override val name = "skill"

    override val description =
        "Load a skill's full instructions. Returns the skill prompt and usage guidance, " +
            "which you should follow to solve the task. " +
            "技能全文按需加载：先看系统提示词里的 Skills Catalog，相关就调本工具（name 传其 id）。"

    override val parametersJson = """{
        "type": "object",
        "properties": {
            "name": {
                "type": "string",
                "description": "技能 id（Skills Catalog 行里 [id=...] 的值）；也接受技能名。"
            }
        },
        "required": ["name"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val key = runCatching {
            org.json.JSONObject(arguments).optString("name", "")
        }.getOrNull().orEmpty().trim()
        if (key.isEmpty()) {
            return "❌ 缺少 name 参数：请传 Skills Catalog 里 [id=...] 的值。"
        }
        // 作用域跟随当前激活人格（与 Skills Catalog 注入同一数据源）；
        // 构造时显式传了 personaId（bot 引擎等场景）则优先用它。
        val scope = personaId.ifBlank {
            runCatching {
                val repo = com.ai.assistance.quro.core.QuroPersonaRepository(context.applicationContext)
                repo.loadAll().firstOrNull { it.id == repo.getActiveId() }?.id ?: ""
            }.getOrDefault("")
        }
        val skill = QuroSkillStore.findForTool(context, key, scope)
            ?: return "❌ 没有找到启用的技能「$key」。请对照 Skills Catalog 的 [id=...] 列表重试；" +
                "若确实不存在，告知用户到「技能」页开启或导入。"
        return QuroSkillStore.loadedBlock(skill)
    }
}
