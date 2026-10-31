package com.ai.assistance.quro.core.mika.uistate

/**
 * 判动作效果用的全部门槛,集中在一处。都是先定的初值,要按真机上记下的运行(动作前后的界面与结果)校准,
 * 改动时连同 ActionEffectTest、FrameDiffTest 的样例一起看。坐标都按 0–999 归一化。
 */
object EffectTuning {
    // ---- 界面结构 ----
    /** 明显变了:至少这么多处变化…… */
    const val MAJOR_MIN = 3
    /** ……且至少占元素的这个比例。 */
    const val MAJOR_SHARE = 0.10
    /** 同一个输入框换了字:前后中心相距不超过这么远。 */
    const val NEAR = 50
    /** 读回时认同一个输入框:中心相距不超过这么远。 */
    const val SAME_FIELD = 120
    /** 有信息量的元素少于这么多(自绘界面)时,大面积的画面变化也算明显变了。 */
    const val FEW_ELEMENTS = 3

    // ---- 画面(旁证;格子与阈值见 FrameTuning) ----
    /** 坐标动作看落点周围多大一块(半宽)。 */
    const val POINT_REGION = 60

    // ---- 等待 ----
    /** 端口没有在动作里等过界面时,判效果前等界面稳定的上限。 */
    const val SETTLE_MS = 1_500L
    /** 看起来没变时再等这么久重看一次(界面可能是异步更新的)。 */
    const val RECHECK_MS = 700L
    /** 动作前看一眼最多花的时间;来不及就只按端口的事实判。 */
    const val PROBE_MS = 1_000L
    /** 动作之后判效果最多多花的时间(等待加读界面);到了就按已有的事实下结论。 */
    const val BUDGET_MS = 2_500L
}
