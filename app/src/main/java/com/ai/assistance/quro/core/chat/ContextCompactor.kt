package com.ai.assistance.quro.core.chat

import java.security.MessageDigest

/**
 * 可停止的摘要压缩。
 *
 * ## 与 [com.ai.assistance.quro.core.QuroAssistant.compactForLocal] 的区别
 *
 * 那个是「本地模型兜底瘦身」——按字符预算保头丢尾，不产生新内容，只在本地离线
 * 上下文超限时兜底：LLM 摘要压缩。
 *
 *  1. 手动触发（上下文窗口面板里的「压缩上下文」）；
 *  2. 可中途停止；
 *  3. 内容指纹校验——压缩跑着的时候对话又变了，这次结果作废（[CompactionState.invalidated]）；
 *  4. 成功后在位点留一条「以上内容已压缩」的分隔消息。
 *
 * ## 为什么内容指纹必须做
 *
 * 压缩是异步的（模型生成摘要要几秒到十几秒）。这段时间里用户可能又发了一句、
 * AI 可能又回了一句。等摘要回来，历史已经和「发起压缩那一刻」不一样了——直接替换
 * 会把新消息吞掉、把摘要建立在过时的对话上。所以发起时记录 inputDigest（当时
 * 参与压缩的消息内容的哈希），完成时重算比对，不一致就作废并提示。
 *
 * 纯 Kotlin，不依赖 Android，可单测。
 */

/** 压缩状态机。 */
enum class CompactionStatus {
    /** 已发起，等待模型返回。 */
    RUNNING,
    /** 成功生成摘要并替换历史。 */
    COMPLETED,
    /** 用户中途停止。 */
    CANCELLED,
    /** 模型返回空摘要 / 调用失败。 */
    FAILED,
    /** 压缩期间对话内容发生变化，本次结果作废。 */
    INVALIDATED,
}

/** 一次压缩的完整记录（不落库——本会话内有效）。 */
data class CompactionRecord(
    val id: String,
    val conversationId: String,
    /** 发起时参与压缩的消息数量。 */
    val inputMessageCount: Int,
    /** 发起时的内容指纹（参与压缩消息内容的 SHA-256 前 16 位 hex）。 */
    val inputDigest: String,
    /** 摘要正文。 */
    val summary: String = "",
    /** 摘要方法，固定 llm_summary。 */
    val summaryMethod: String = "llm_summary",
    /** 压缩前估算 token。 */
    val tokensBefore: Int = 0,
    /** 压缩后估算 token。 */
    val tokensAfter: Int = 0,
    val status: CompactionStatus = CompactionStatus.RUNNING,
    /** 失败 / 作废原因，人话。 */
    val reason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
)

/**
 * 摘要压缩的**协调器**：负责指纹、预算估算、以及「把摘要写回消息流」。
 *
 * 模型调用本身不在这里做（依赖 QuroLlmClient，那是网络层），由 ViewModel 注入
 * 一个挂起函数 [summarize]。这样本类保持纯 Kotlin、可单测，也把「可停止」落实成
 * 对协程 Job 的取消 + 完成时的指纹复核。
 */
object ContextCompactor {

    private const val DIGEST_LEN = 16

    /**
     * 计算一批消息的内容指纹。
     *
     * 只取 [QuroMessageLike] 的 role+content（工具轮把参数/结果也算进去），
     * 因为这才是「这段历史代表什么」的实质。时间戳、id 不参与——否则同一批消息
     * 每次算都不一样，指纹就没意义了。
     */
    fun digest(messages: List<QuroMessageLike>): String {
        val md = MessageDigest.getInstance("SHA-256")
        messages.forEach { m ->
            md.update(m.role.toByteArray())
            md.update(0x1f)
            md.update(m.textContent.toByteArray())
            md.update(0x1e)
        }
        return md.digest().take(DIGEST_LEN).joinToString("") { "%02x".format(it) }
    }

    /**
     * 估算一批消息的 token 数（复用 [ContextWindowCalculator] 的语言混合口径）。
     */
    fun estimateTokens(messages: List<QuroMessageLike>): Int =
        messages.sumOf { ContextWindowCalculator.estimateTokens(it.textContent) }

    /**
     * 判定一次运行中的压缩是否已经「过时」——输入内容与发起时不一致。
     *
     * @return true 表示内容变了，本次压缩应当作废。
     */
    fun isStale(record: CompactionRecord, current: List<QuroMessageLike>): Boolean =
        record.inputDigest != digest(current)

    /** 取一批消息里「值得保留、不参与摘要」的尾部条数（最新几轮，保护近因）。 */
    fun <T : QuroMessageLike> preservedTail(messages: List<T>, preserveRounds: Int): List<T> {
        if (preserveRounds <= 0) return emptyList()
        // 一轮 = user..assistant（可能夹工具）；从尾往前按 user/assistant 计数，取够 preserveRounds 轮。
        val result = ArrayDeque<T>()
        var rounds = 0
        for (m in messages.asReversed()) {
            result.addFirst(m)
            if (m.role == "user") {
                rounds++
                if (rounds >= preserveRounds) break
            }
        }
        return result.toList()
    }
}

/**
 * 摘要器暴露给「消息」的最小视图，解耦 [com.ai.assistance.quro.core.QuroMessage]
 * 与本模块（避免 core.chat 反向依赖 core 的完整消息模型，也让单测更轻）。
 */
interface QuroMessageLike {
    val role: String
    val textContent: String
}
