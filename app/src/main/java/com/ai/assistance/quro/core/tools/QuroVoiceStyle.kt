package com.ai.assistance.quro.core.tools

/**
 * 语音风格标注解析（小米 MiMo TTS 风格标签）。
 *
 * 核心设计（纠正旧实现「所有开启标签强制一次性前缀」的错误）：
 * 风格标签是一个「池子」，LLM 在每段文本前自行决定用哪些标签（决定权在 LLM），
 * 同一段可叠加多个，未标注段落用默认音色自然朗读。
 *
 * 标注格式（每行开头，半角括号，多标签空格分隔）：
 *   (开心) 今天天气真好呀！
 *   (温柔) 出门记得带伞。
 *   (开心 俏皮) 一起去玩吧！
 *   (唱歌) 哗啦啦啦啦啦下雨了～
 */
object QuroVoiceStyle {
    data class Segment(val text: String, val tags: List<String>)

    private val MARKER_RE = Regex("^\\(\\s*([^()]*?)\\s*\\)\\s*(.*)$")

    /**
     * 把 LLM 回复按行切分为带标签片段。
     * @param availableTags 当前激活人格卡允许的标签白名单；仅保留白名单内的标签（容错：忽略自创/错字）。
     */
    fun segment(input: String, availableTags: List<String>): List<Segment> {
        val pool = availableTags.toSet()
        if (pool.isEmpty()) return listOf(Segment(input, emptyList()))
        return input.split("\n").mapNotNull { line ->
            val m = MARKER_RE.find(line)
            if (m != null) {
                val tagPart = m.groupValues[1].trim()
                val text = m.groupValues[2]
                if (text.isBlank() && tagPart.isBlank()) return@mapNotNull null
                val tags = if (tagPart.isBlank()) emptyList()
                else tagPart.split(Regex("\\s+")).mapNotNull { tok ->
                    val t = tok.trim().trimEnd('，', '。', '、', ',', '.', '：', ':')
                    if (t in pool) t else null
                }
                Segment(text, tags)
            } else {
                if (line.isBlank()) null else Segment(line, emptyList())
            }
        }
    }

    /** 是否含有有效风格标记（用于判断是否需要逐段合成）。 */
    fun hasMarkers(input: String, availableTags: List<String>): Boolean {
        val pool = availableTags.toSet()
        if (pool.isEmpty()) return false
        return input.split("\n").any { line ->
            val m = MARKER_RE.find(line) ?: return@any false
            val tagPart = m.groupValues[1].trim()
            tagPart.split(Regex("\\s+")).any { tok ->
                val t = tok.trim().trimEnd('，', '。', '、', ',', '.', '：', ':')
                t in pool
            }
        }
    }

    /** 去除每行开头的风格标记，返回纯文本（聊天界面显示 / 复制用）。 */
    fun strip(input: String): String {
        return input.split("\n").joinToString("\n") { line ->
            val m = MARKER_RE.find(line)
            if (m != null) m.groupValues[2] else line
        }
    }

    /**
     * 系统提示词片段：告知 LLM 如何标注风格（当前选中的云端服务商支持风格标签时注入）。
     * 不绑定任何单一服务商——文案使用通用「云语音合成」措辞，标签来自调用方传入的可用标签池。
     *
     * v224 增强：强调「自动组合情绪标签形成自然人类语音」——LLM 应像人说话一样
     * 自由切换语气，不是机械地给每句话加标记，而是在情绪转折处自然标注。
     */
    fun systemHint(availableTags: List<String>): String {
        val tags = if (availableTags.isEmpty()) "（未配置可用标签）" else availableTags.joinToString(" / ")
        return """## 语音风格标注（重要 — 像人类一样自然说话）
你已接入云端语音合成（TTS），你的回复会被朗读出来。
请用「情绪/风格标记」让语音像真人一样有自然起伏和情感变化：

**核心原则（像人说话）：**
- 人在说话时会自然切换语气——开心时轻快、安慰人时温柔、讲道理时稳重。你也应该这样。
- 不是每句都加标记！只在情绪明显变化的地方标注，其余用默认音色自然朗读。
- 一段话里可以有多个情绪层次：(温柔) 别难过… (开心) 一切都会好起来的！

**标记格式：每行开头半角括号 + 标签**
  (开心) 今天天气真好呀！
  (温柔) 不过出门记得带伞哦。
  (唱歌) 哗啦啦啦啦啦下雨了～
  (开心 俏皮) 我们一起去玩吧！

**可选风格标签池（只能从下列中选，不要自创）：**
  $tags

**规则：**
  - 情绪标签默认全部可用，你根据内容自动选择最合适的组合。
  - 同一段可叠加多个标签（如 (开心 俏皮)），表达复杂情感。
  - 不加标记的句子用默认音色自然朗读（大部分句子不需要标记）。
  - 标记只用于语音合成，不会显示给用户，放心使用。
  - 纯代码 / 表格 / 列表段落不要加标记。
  - **目标：让听的人感觉在和一个有温度、有情绪变化的真人对话。**""".trimIndent()
    }
}
