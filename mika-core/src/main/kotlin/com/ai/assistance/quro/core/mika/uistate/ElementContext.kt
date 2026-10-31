package com.ai.assistance.quro.core.mika.uistate

/** 一个元素自己的上下文,判它做的是什么时只看这些,不看整屏:同一行上的文字(附着的标签、行里的说明)。 */
object ElementContext {
    /** 同一行:中心落在元素的上下范围里,或元素的中心落在它的上下范围里。 */
    fun row(e: UiElement, s: ScreenSnapshot): List<UiElement> {
        if (e.bounds.size != 4) return emptyList()
        val cy = (e.bounds[1] + e.bounds[3]) / 2
        return s.elements.filter { o ->
            o !== e && o.bounds.size == 4 && ((o.bounds[1] + o.bounds[3]) / 2 in e.bounds[1]..e.bounds[3] || cy in o.bounds[1]..o.bounds[3])
        }
    }
}
