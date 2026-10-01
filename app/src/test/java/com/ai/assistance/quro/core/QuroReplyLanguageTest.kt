package com.ai.assistance.quro.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「思考语言」指令的单测（N16）。
 *
 * 覆盖纯文本函数 [QuroReplyLanguage.thinkingDirectiveText] /
 * [QuroReplyLanguage.shortThinkingDirectiveText]，不碰 [android.content.Context]。
 *
 * 存在理由：中文界面下 `needsDirective("zh")` 为 false（提示词整篇中文 ⇒ 回复自然中文），
 * 于是**整条回复语言指令被跳过**；但思考语言推不出来 —— 工具清单里混着英文
 * （工具名、schema 字段名、能力目录的英文描述），模型常拿英文口径开场做推理，
 * 真机表现为「深度思考」卡片里一大段英文 + 一段中文，而该卡片直接展示思考原文。
 * 所以思考语言指令必须独立于「中文要不要注入回复语言」这条判断。
 */
class QuroReplyLanguageTest {

    private val ZH = "Chinese（zh）"
    private val EN = "English（en）"
    private val JA = "Japanese / 日本語（ja）"

    // ---------- 完整版 ----------

    @Test
    fun `thinking directive is never blank for zh`() {
        // 本次修复的核心：中文时回复语言指令为空，但思考语言指令必须有内容。
        assertTrue(QuroReplyLanguage.thinkingDirectiveText(ZH).isNotBlank())
    }

    @Test
    fun `thinking directive binds reasoning to the user language`() {
        val t = QuroReplyLanguage.thinkingDirectiveText(ZH)
        assertTrue(t.contains("<think>"))
        assertTrue(t.contains("reasoning_content"))
        assertTrue(t.contains("same language as the user's message"))
        // 中英双语：英文句强约束，中文句点破陷阱（与 directive() 同套路）。
        assertTrue(t.contains("Never reason in English while the user writes in Chinese"))
        assertTrue(t.contains("禁止在用户用中文时用英文推理"))
    }

    @Test
    fun `thinking directive forbids copying tool docs verbatim`() {
        val t = QuroReplyLanguage.thinkingDirectiveText(ZH)
        assertTrue(t.contains("never copy tool descriptions"))
        assertTrue(t.contains("抄进思考过程"))
    }

    @Test
    fun `thinking directive carries the language name`() {
        assertTrue(QuroReplyLanguage.thinkingDirectiveText(ZH).contains("Chinese"))
        assertTrue(QuroReplyLanguage.thinkingDirectiveText(JA).contains("日本語"))
        assertTrue(QuroReplyLanguage.thinkingDirectiveText(EN).contains("English"))
    }

    @Test
    fun `thinking directive is compact enough for a busy system prompt`() {
        // 「两条句子」级别的指令，不该把已经 16k+ 字符的云端系统提示词撑爆。
        assertTrue(QuroReplyLanguage.thinkingDirectiveText(ZH).length < 700)
    }

    @Test
    fun `thinking directive is not the reply language directive`() {
        // 思考语言指令要说明「适用于思考过程」，否则会被当成回复语言指令的重复。
        val t = QuroReplyLanguage.thinkingDirectiveText(ZH)
        assertTrue(t.contains("Thinking Language / 思考语言"))
        assertTrue(t.contains("APPLIES TO REASONING"))
    }

    // ---------- 精简版 ----------

    @Test
    fun `short thinking directive also covers zh`() {
        assertTrue(QuroReplyLanguage.shortThinkingDirectiveText(ZH).isNotBlank())
    }

    @Test
    fun `short thinking directive stays short`() {
        val t = QuroReplyLanguage.shortThinkingDirectiveText(ZH)
        assertTrue(t.contains("Think and reason in Chinese（zh）"))
        assertTrue(t.contains("Do NOT reason in English"))
        assertTrue(t.contains("（思考过程必须用 Chinese（zh） 书写"))
        assertTrue(t.length < 400)
    }

    @Test
    fun `short thinking directive differs per language`() {
        assertFalse(
            QuroReplyLanguage.shortThinkingDirectiveText(ZH) ==
                QuroReplyLanguage.shortThinkingDirectiveText(EN),
        )
    }
}
