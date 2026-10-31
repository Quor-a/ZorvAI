package com.ai.assistance.quro.core.mika.uistate

/** 一块屏上一个无障碍窗口。根节点读不到时仍保留窗口,让模型知道有遮挡或加载中的界面。 */
data class UiWindowTree(
    val id: Int,
    val type: String,
    val layer: Int,
    val title: String?,
    val packageName: String?,
    val active: Boolean,
    val focused: Boolean,
    val nodes: List<UiTreeNode>,
)

/** 可见无障碍节点;只有能从这一屏的元素表解析的节点才带 [ref]。 */
data class UiTreeNode(
    val role: String,
    val bounds: List<Int>,
    val ref: String? = null,
    val text: String? = null,
    val description: String? = null,
    val hintText: String? = null,
    val resourceId: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val secret: Boolean = false,
    /** 看上去是滚轮选择器(一格一格转的时间、日期、地区……):用转滚轮的工具一次转到值。 */
    val wheel: Boolean = false,
    val children: List<UiTreeNode> = emptyList(),
) {
    /** 稳定编号只替换模型地址;节点本身与 Android 端的引用表不动。 */
    fun withRefs(refs: Map<String, String>): UiTreeNode = copy(
        ref = ref?.let { refs[it] ?: it },
        children = children.map { it.withRefs(refs) },
    )
}
