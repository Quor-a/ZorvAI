package com.ai.assistance.quro.core.cards

import java.util.concurrent.atomic.AtomicReference

/**
 * 卡片补丁的**宿主桥**：`card_patch` 工具 ↔ 真正持有卡片的那一侧。
 *
 * ## 为什么需要这一层，而不是让工具直接改卡片
 *
 * 卡片有两个载体，工具都拿不到：
 *  - 气泡内的可视化组件在 `QuroMessage.cards` 里，持有者是 ViewModel；
 *  - 底部卡片栏在 [QuroChatCardStore]，但 store 是全局单例、由界面订阅渲染，
 *    工具改了它也不知道「用户看到的是哪一条会话里的那张卡」。
 *
 * 而 [QuroTool] 的实例是 [com.ai.assistance.quro.core.tools.buildQuroRegistry] 全局构建的，
 * **不能持有 ViewModel 引用**（ViewModel 生命周期比工具短，且多进程各有一份）。
 *
 * 所以取既有 [com.ai.assistance.quro.core.tools.UiNavigationBus] 的同一套做法：
 * 工具只负责「把意图发出去」，由宿主（ChatScreen，持有 vm）执行并回填结果。
 *
 * ## 为什么不用 Channel 而是 AtomicReference 回调
 *
 * 补丁的**返回值要立刻回喂给模型**（成功条数 / 失败原因 + 合法路径清单），
 * 而 `UiNavigationBus` 是单向事件流、没有回执。改成宿主注册一个处理器函数，
 * 工具同步调用它拿结果 —— 模型这一轮就能知道成败，下一轮才能改对。
 * 补丁是**请求-响应**语义，用事件流表达会丢掉回执，故与导航总线有意不同。
 *
 * 没有宿主时（单测 / 工具自检阶段）[apply] 返回 `null`，工具据此回一句
 * 「当前无界面宿主」而不是谎报成功。
 */
object CardPatchBridge {

    /** 宿主处理器：给卡片应用补丁，返回结果；无此卡片时返回 null。 */
    fun interface Host {
        fun apply(cardId: String, patchJson: String): CardPatch.Result?
    }

    private val hostRef = AtomicReference<Host?>(null)

    /**
     * 探路：返回该卡当前可改的合法路径。
     *
     * 与 [Host] 分开是因为**不需要回执**（没有副作用），且探路可能发生在
     * 补丁宿主尚未注册时（理论上不该发生，但分开后至少不会 NPE）。
     */
    private val describerRef = AtomicReference<((String) -> List<String>)?>(null)

    /** ChatScreen 在 onDispose 时注销，避免旧宿主泄漏（切会话后仍指向旧 vm）。 */
    fun install(host: Host?, describer: ((String) -> List<String>)? = null) {
        hostRef.set(host)
        describerRef.set(describer)
    }

    /** 当前是否接到了宿主。工具据此决定回执措辞。 */
    val hasHost: Boolean get() = hostRef.get() != null

    /** 探路；无探路器或找不到卡时返回空列表。 */
    fun describe(cardId: String): List<String> =
        runCatching { describerRef.get()?.invoke(cardId).orEmpty() }.getOrElse { emptyList() }

    /**
     * 应用补丁。
     *
     * @return 宿主返回的结果；**无宿主时返回 null**（调用方必须区别于
     *   「有宿主但补丁失败」—— 前者是环境未就绪，后者要回喂失败原因）。
     */
    fun apply(cardId: String, patchJson: String): CardPatch.Result? =
        runCatching { hostRef.get()?.apply(cardId, patchJson) }.getOrNull()
}
