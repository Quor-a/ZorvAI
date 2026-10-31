package com.ai.assistance.quro.core.mika.tool

import com.ai.assistance.quro.core.mika.model.ToolSpec

/**
 * 工具分两层发给模型:一直都在的(核心的通用工具、不收的能力),和按能力分的几组(收着时只有入口工具,用短说明;
 * 第一次用到时展开成整组、换回全文,本任务里一直留着)。只在有屏幕的那几组展开时才提供的通用工具(接管、报障碍、回看画面、一次多步)
 * 算作 [SCREENS] 一组,任何一块屏展开它就展开。
 *
 * 顺序固定:一直都在的 → [SCREENS] → 各能力的组(按登记的顺序)。展开一组只改从它那一组起的部分,
 * 前面的字节不变;请求前缀因展开最多失效一次。
 */
internal object ToolGroups {
    /** 有屏幕的几组共用的那一组。 */
    const val SCREENS = "screens"

    /** 冻结下来的一个工具:全文定义、收着时的入口定义(不是入口为 null)、所属的组(一直都在为 null)。 */
    class Slot(val full: ToolSpec, val entry: ToolSpec?, val group: String?)

    /**
     * 此刻发给模型的工具:[expanded] 是已展开的组(含 [SCREENS]);[extra] 是循环自己另给、属于某一组的定义
     * (一次多步属于 [SCREENS]),排在那一组的末尾,只在那一组展开时给。
     */
    fun layout(slots: List<Slot>, expanded: Set<String>, extra: Map<String, List<ToolSpec>> = emptyMap()): List<ToolSpec> =
        order(slots).flatMap { g ->
            val members = slots.filter { it.group == g }
            if (g == null || g in expanded) members.map { it.full } + extra[g].orEmpty()
            else members.mapNotNull { it.entry }
        }

    /** 各组的先后:一直都在的、[SCREENS]、再按能力第一次出现的顺序。 */
    fun order(slots: List<Slot>): List<String?> = listOf(null, SCREENS) + slots.mapNotNull { it.group }.filter { it != SCREENS }.distinct()
}
