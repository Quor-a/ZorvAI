package com.ai.assistance.quro.util

/**
 * 流式阶段提示文案判定（共享唯一实现）。
 *
 * 背景（Bug「⏳ 正在处理残留」）：prefill 进度 / 模型加载 / 思考中等占位文案是生成过程中的
 * 临时状态，正常流程会被真实内容覆盖/删除；一旦被意外持久化（生成被打断/进程被杀时
 * commitCurrent 把半截占位落盘）即成为永久残留。
 *
 * 消费方（必须共用本实现，消除判定漂移）：
 *  - [com.ai.assistance.quro.core.QuroConversationPersistence]：加载迁移时丢弃纯阶段提示消息；
 *  - [com.ai.assistance.quro.core.QuroAssistant]：终态兜底正文绝不允许回退到阶段提示文案。
 *
 * ⚠️ 匹配必须【收窄】到真实下发过的占位前缀，绝不用 `startsWith("⏳")` 这种宽匹配——
 * 模型正常回复若以 ⏳ emoji 开头（且无 reasoning/工具/卡片）会被误删/误替换。
 */
object QuroStageHints {

    /** 历史与当前版本真实下发过的阶段提示前缀（穷举，新增占位文案时同步补充）。 */
    private val STAGE_HINT_PREFIXES = listOf(
        "⏳ 正在",   // 「⏳ 正在处理提示词… X%」「⏳ 正在加载本地模型(并处理上下文)…」
        "⏳ 上下文",  // 「⏳ 上下文处理完毕，正在生成回复…」
        "⏳ 加载",   // 防御：加载类占位的其它措辞变体
        "本地模型思考中", // streamDisplay 的思考期占位「本地模型思考中…」
    )

    /**
     * 判定 [text] 是否为流式阶段提示占位文案。
     * 仅匹配已知占位前缀；空串/空白返回 false。
     */
    fun isTransientStageHint(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        return STAGE_HINT_PREFIXES.any { t.startsWith(it) }
    }
}
