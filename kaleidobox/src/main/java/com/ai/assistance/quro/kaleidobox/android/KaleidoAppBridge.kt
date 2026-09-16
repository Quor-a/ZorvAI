package com.ai.assistance.quro.kaleidobox.android

/**
 * 宿主 App 桥：把 App 级能力（AI 对话引擎、终端/Linux 环境）以中性接口暴露给插件，
 * 避免 kaleidobox 模块反向依赖 app 模块。由 app 实现并注入 [KaleidoBoxHost.init]。
 *
 * 为何要这一层：kaleidobox 是"工具包运行器"内核，只认 KValue 与能力表；
 * 而 AI / 终端是宿主 App 的子系统（在 app 模块）。app 实现本接口后，
 * 宿主能力表里的 `ai.chat` / `app.term.run` 就能真正调到 App 的真实能力。
 */
interface KaleidoAppBridge {
    /**
     * 调用宿主 AI 对话引擎（用户已配 provider）。
     *
     * @param messages 完整对话历史，顺序为 (role, content)，role ∈ {system, user, assistant, tool}。
     *                 多轮对话必须把历史一并传入，否则模型无法"记忆"前文——这正是此前
     *                 AI 助手"像一次性回声、不像助手"的根因。
     * @param system   可选 system 提示词，会插到历史最前。
     * @return 纯文本或错误
     */
    fun aiChat(messages: List<Pair<String, String>>, system: String? = null): KaleidoAiResult

    /** 宿主是否已配置至少一个可用的 AI 提供商（未配置时 [aiChat] 会直接失败）。 */
    fun aiAvailable(): Boolean

    /** 在终端/Linux 环境同步执行命令，返回 (退出码, 输出)。 */
    fun termRun(command: String, timeoutMs: Long = 30_000L): Pair<Int, String>
}

/** AI 对话结果（中性，不依赖 app 模块类型）。 */
sealed class KaleidoAiResult {
    data class Ok(val text: String, val tools: List<String> = emptyList()) : KaleidoAiResult()
    data class Err(val message: String) : KaleidoAiResult()
}
