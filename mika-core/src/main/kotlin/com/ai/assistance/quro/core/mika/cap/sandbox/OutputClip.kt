package com.ai.assistance.quro.core.mika.cap.sandbox

/** 交给模型的命令输出:开头 + 结尾,中间写明省略了多少字。 */
object OutputClip {
    fun clip(s: String, head: Int = 4000, tail: Int = 12000): Pair<String, Boolean> {
        if (s.length <= head + tail) return s to false
        // 不把一个代理对劈成两半
        val h = if (head > 0 && s[head - 1].isHighSurrogate()) head - 1 else head
        var start = s.length - tail
        if (start < s.length && s[start].isLowSurrogate()) start++
        return "${s.substring(0, h)}\n…(${start - h} characters omitted)…\n${s.substring(start)}" to true
    }
}
