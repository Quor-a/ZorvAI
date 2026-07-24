package com.ai.assistance.quro.core.tools

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.core.QuroChatMessage
import com.ai.assistance.quro.core.QuroLlmResult
import com.ai.assistance.quro.core.QuroPersonaRepository
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.network.QuroLlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 语音风格自动推导器（v113 —「让语音球像人一样自发带情绪」）。
 *
 * 设计目标（用户原话）：语音球要**绝对**自发地说出各种情绪 / 语速 / 语气，
 * 而不是每次都要人提醒。
 *
 * 机制（对齐小米 MiMo-V2.5-TTS 官方「自然语言控制」）：
 *   - 小米文档明确：把情绪/语速/语气写进 `user` 消息（自然语言），即可整体控制朗读，
 *     一句指令可覆盖多情绪混合、句级/词级粒度，甚至「导演模式」。
 *   - 本推导器对**每条将要朗读的回复**生成一句自然语言朗读指令（如「开心俏皮，语速稍快，带笑意」），
 *     注入到云 TTS 的 `user` 消息里，作为情绪/语速/语气控制。
 *
 * 两级策略（永远可用，绝不因网络挂掉而变哑）：
 *   1) LLM 轻量推断：用当前聊天模型做一次极小 max_tokens 调用，质量最高、最像人；
 *      任意失败 / 超时（5s）立即降级。
 *   2) 关键词启发式：离线也能根据文本情绪词 + 标点给出合理朗读指令。
 *
 * 与既有「(风格) 音频标签」通道互补：本推导器负责「整体基调」，LLM 自带的逐段 (开心) 标签
 * 负责「细粒度」，两者可叠加（小米支持自然语言 + 音频标签并存）。
 */
object QuroSpeechStyleDeriver {
    private const val TAG = "QuroSpeechStyle"

    // 轻量专用客户端：短超时，避免拖慢语音球整体延迟
    private val fastClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    private const val DERIVE_TIMEOUT_MS = 5000L

    /**
     * 推导朗读风格指令（自然语言）。返回空串 = 用默认音色自然朗读（中性文本）。
     * `base` 为人格卡 voiceSetting（如「温柔女声，语速中等」），会与推导结果合并。
     */
    suspend fun deriveStyle(ctx: Context, text: String): String = withContext(Dispatchers.IO) {
        val persona = runCatching { QuroPersonaRepository(ctx).getActive() }.getOrNull()
        val base = persona?.voiceSetting?.trim().orEmpty()
        val clean = QuroVoiceStyle.strip(text).trim()
        if (clean.isEmpty()) return@withContext base

        // 1) LLM 轻量推断（带超时保护）
        val llm = runCatching { deriveViaLlm(ctx, clean) }.getOrNull()
        if (!llm.isNullOrBlank()) return@withContext combine(base, llm)

        // 2) 启发式兜底
        val h = heuristic(clean)
        return@withContext if (h.isBlank()) base else combine(base, h)
    }

    /** base（人格音色）与推导风格合并；base 为空则单用推导。 */
    private fun combine(base: String, style: String): String {
        val s = style.trim()
        if (s.isEmpty()) return base
        return if (base.isBlank()) s else "$base，$s"
    }

    /**
     * 关键词启发式：根据情绪词 + 标点给出朗读指令。
     * 顺序即优先级（先匹配到的先生效）。返回空串表示中性文本。
     */
    private fun heuristic(text: String): String {
        val rules = listOf(
            Regex("哈哈|嘻嘻|嘿嘿|耶|太棒了|好开心|太喜欢|开心|高兴|喜欢|可爱|赞|棒") to "开心活泼，语速稍快，带笑意",
            Regex("呜呜|呜咽|抽泣|伤心|难过|遗憾|可惜|失落|想哭|心碎") to "悲伤温柔，语速偏慢，带鼻音",
            Regex("气死|可恶|混蛋|愤怒|讨厌|烦死|岂有此理|受不了|滚") to "愤怒急切，语速快，声音略带颤抖",
            Regex("害怕|恐惧|吓人|恐怖|担心|紧张|不安|发抖") to "紧张不安，语速偏快，声音微微发颤",
            Regex("恭喜|庆祝|好消息|太好了|胜利|成功|毕业|中奖") to "兴奋喜悦，语速稍快，充满活力",
            Regex("抱歉|对不起|愧疚|忘了|失误|不好意思|遗憾地") to "愧疚温和，语速偏慢，语气放软",
            Regex("警告|注意|危险|小心|务必|必须|记住|严禁") to "严肃郑重，语速沉稳，语气坚定",
            Regex("累|疲惫|困|想睡|休息|安静|沉默|……|缓缓") to "慵懒平静，语速放慢，气息轻柔",
            Regex("好奇|为什么|怎么|吗？|如何|什么情况|咋") to "好奇疑惑，语速中等，尾音上扬",
            Regex("爱你|喜欢你|想你|抱抱|亲亲|亲爱") to "温柔甜蜜，语速稍慢，带笑意",
            Regex("急|快一点|赶紧|马上|立刻|来不及") to "急切紧迫，语速快，语气紧凑",
            Regex("慢慢|不急|从容|淡定|冷静") to "从容舒缓，语速放慢，语气平和",
        )
        for ((re, hint) in rules) {
            if (re.containsMatchIn(text)) return hint
        }
        // 标点兜底
        val excl = text.count { it == '！' || it == '!' }
        val q = text.count { it == '？' || it == '?' }
        return when {
            excl >= 2 -> "情绪激动，语速稍快"
            q >= 2 -> "好奇疑惑，语速中等，尾音上扬"
            text.length > 80 -> "平缓叙述，语速中等，自然流畅"
            else -> ""
        }
    }

    /**
     * 用当前聊天模型做一次轻量推断。失败 / 空结果返回 null（交给启发式）。
     * 使用聊天配置（baseUrl/apiKey/model），而非小米 TTS 凭证——
     * 同一个正在对话的模型最懂上下文，情绪判断最像人。
     */
    private suspend fun deriveViaLlm(ctx: Context, text: String): String? = runCatching {
        val cfg = QuroModelConfigRepository(ctx).load()
        if (cfg.apiKey.isBlank()) return null
        val baseUrl = cfg.baseUrl.trim().trimEnd('/')
        val sys = "你是语音导演。下面是一段将要被朗读的 AI 回复。请输出一句不超过25字的朗读指令，" +
                "用中文描述它的情绪、语速、语气，例如『开心俏皮，语速稍快，带笑意』或『严肃郑重，语速沉稳』。" +
                "只输出这句指令本身，不要解释、不要引号、不要标点包裹。"
        val messages = listOf(
            QuroChatMessage("system", sys),
            QuroChatMessage("user", "待朗读文本：\n$text"),
        )
        val res = withTimeoutOrNull(DERIVE_TIMEOUT_MS) {
            QuroLlmClient(fastClient).chat(baseUrl, cfg.apiKey, cfg.model, messages, 0.5f, 48, emptyList())
        } ?: return null
        when (res) {
            is QuroLlmResult.Text -> res.content.trim().lines().firstOrNull { it.isNotBlank() }?.take(40)
            else -> null
        }
    }.getOrNull()
}
