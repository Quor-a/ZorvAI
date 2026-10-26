package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.core.QuroToolSpec

/**
 * Quro 工具分类构架（提示词侧紧凑视图）。
 *
 * 为什么需要它（需求 #403「zorvAI 还没做系统提示和工具分类构架」）：
 * 真实注册表有约 200 个工具，`tools` 字段下发的是一张**扁平清单**（名字 + 描述），
 * 模型看到的是「一屏工具名」，没有分类骨架——遇到需求时要么逐个扫名字猜，要么
 * 依赖 tool_discovery 事后查。本类把 [ToolCapabilityDirectory] 的 21 个分类
 * （单一真相源，install 时由真实注册表生成）压成一段 ~1.5KB 的提示词文本：
 *
 *   **分类显示名**（分类描述）→ 代表工具：a、b、c…
 *
 * 每类列 priority 最高的前 [MAX_REPRESENTATIVES] 个工具名（不含描述），
 * 既给模型「这类活有哪些工具」的骨架，又不把 system prompt 撑爆
 * （完整描述仍在 tools 字段与 tool_discovery 里，那里才是详情真相源）。
 *
 * 边界：
 * - 目录未 install（空）时回退为「枚举 + 描述」——不造工具名，避免幻觉；
 * - 不新增分类源：分类以 [ToolCapabilityDirectory.ToolCategory] 为准，
 *   新工具进哪个类由该目录的 inferCategory/handbook 决定，这里只做呈现；
 * - harness（DeepSeek Harness（工作台））归 TERMINAL_LINUX——与目录口径一致，
 *   「同一环境」的关系陈述在 QuroPlatformManifest.SYSTEM 第 ⑦ 段。
 */
object QuroToolTaxonomy {

    /** 每个分类在提示词里列出的代表工具上限。 */
    private const val MAX_REPRESENTATIVES = 6

    /**
     * 生成注入系统提示词的「工具分类构架」段。
     *
     * @param specs 当前真实工具清单（通常传 `registry.fullSpecs()`）；
     *   传空表时直接用目录已安装快照（[ToolCapabilityDirectory.specsSnapshot]），
     *   两者都空则走纯枚举回退。
     */
    fun buildTaxonomySection(specs: List<QuroToolSpec> = ToolCapabilityDirectory.specsSnapshot()): String {
        val sb = StringBuilder()
        sb.append("\n## 工具分类构架（我的能力地图，按类找工具）\n")
        sb.append("我的全部工具按以下分类组织（约 ")
        sb.append(if (specs.isNotEmpty()) specs.size.toString() else "数")
        sb.append(" 个，逐工具详情以 tools 字段下发为准；不确定用哪个时先调 `tool_discovery`）：\n")

        var listed = 0
        for (category in ToolCapabilityDirectory.ToolCategory.values()) {
            val tools = ToolCapabilityDirectory.getToolsByCategory(category)
                .sortedWith(compareByDescending<ToolCapabilityDirectory.ToolInfo> { it.priority }.thenBy { it.name })
            if (tools.isEmpty()) continue
            listed++
            val reps = tools.take(MAX_REPRESENTATIVES).joinToString("、") { it.name }
            val more = if (tools.size > MAX_REPRESENTATIVES) {
                " 等 ${tools.size} 个（其余用 tool_discovery(action=\"list_tools\", category=\"${category.name}\") 查）"
            } else {
                ""
            }
            sb.append("- **${category.displayName}**（${category.description}）→ $reps$more\n")
        }
        if (listed == 0) {
            // 目录未安装的兜底：只给分类名与描述，不编工具名。
            for (category in ToolCapabilityDirectory.ToolCategory.values()) {
                sb.append("- **${category.displayName}**（${category.description}）\n")
            }
        }
        sb.append("\n用法：先按用户需求对上表中的分类，再从 tools 字段里取该类工具调用；" +
            "跨类拿不准时用 `tool_discovery(action=\"match_intent\", intent=\"…\")`。\n")
        return sb.toString()
    }
}
