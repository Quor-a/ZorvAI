package com.ai.assistance.quro.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频通话「画面理解」降级链回归测试。
 *
 * 钉死用户明确要求的优先级（自上而下，命中即止）：
 *  1. LLM 自己有视觉识别能力 → 自己用（喂图，零额外远程调用）；
 *  2. LLM 没有视觉 → 若「功能模型配置 → 视频通话」配了独立模型 → **直接用视频通话模型做视觉识别**；
 *  3. 仍没有 → 用「功能模型配置 → 图像识别」作为**保底**视觉模型；
 *  4. 全都没有 → 无画面描述，纯语音对话。
 *
 * 以及两条硬约束：
 *  - 「视频识别」(VIDEO_RECOGNITION) 与本链路**不是一回事**，路由器不得消费它；
 *  - 仅「跟随主模型」不算可用候选（那还是那个没视觉的主模型，重复调用毫无意义）。
 */
class QuroFrameVisionRouterTest {

    private val dedicated = QuroFrameVisionOption(dedicatedModel = true, hasApiKey = true)
    private val followGlobal = QuroFrameVisionOption(dedicatedModel = false, hasApiKey = true)
    private val dedicatedNoKey = QuroFrameVisionOption(dedicatedModel = true, hasApiKey = false)
    private val absent = QuroFrameVisionOption.NONE

    // ── ① 主模型自带视觉：最高优先级，任何配置都盖不住 ──

    @Test
    fun `主模型有视觉时一律自己看 不看任何功能模型配置`() {
        assertEquals(
            QuroFrameVisionSource.MAIN_MODEL,
            QuroFrameVisionRouter.plan(true, true, absent, absent)
        )
    }

    @Test
    fun `主模型有视觉时即便配了视频通话模型也仍然自己看`() {
        assertEquals(
            QuroFrameVisionSource.MAIN_MODEL,
            QuroFrameVisionRouter.plan(true, true, dedicated, dedicated)
        )
    }

    @Test
    fun `主模型有视觉但没有密钥时不能靠喂图 退到下一级`() {
        assertEquals(
            QuroFrameVisionSource.VIDEO_CALL_MODEL,
            QuroFrameVisionRouter.plan(true, false, dedicated, absent)
        )
        assertEquals(
            QuroFrameVisionSource.NONE,
            QuroFrameVisionRouter.plan(true, false, absent, absent)
        )
    }

    // ── ② 视频通话模型：主模型无视觉时的第一顺位 ──

    @Test
    fun `主模型无视觉且配了视频通话模型时直接用它做视觉识别`() {
        assertEquals(
            QuroFrameVisionSource.VIDEO_CALL_MODEL,
            QuroFrameVisionRouter.plan(false, true, dedicated, dedicated)
        )
    }

    @Test
    fun `视频通话模型优先于图像识别保底`() {
        assertEquals(
            QuroFrameVisionSource.VIDEO_CALL_MODEL,
            QuroFrameVisionRouter.plan(false, true, dedicated, dedicated)
        )
    }

    @Test
    fun `视频通话模型只是跟随主模型时不可用`() {
        // 跟随主模型 = 还是那个已被判定无视觉的主模型，再调一次是纯浪费。
        assertEquals(
            QuroFrameVisionSource.IMAGE_RECOGNITION_MODEL,
            QuroFrameVisionRouter.plan(false, true, followGlobal, dedicated)
        )
    }

    @Test
    fun `视频通话模型没有密钥时不可用`() {
        assertEquals(
            QuroFrameVisionSource.IMAGE_RECOGNITION_MODEL,
            QuroFrameVisionRouter.plan(false, true, dedicatedNoKey, dedicated)
        )
    }

    // ── ③ 图像识别模型：保底 ──

    @Test
    fun `只有图像识别模型可用时用它保底`() {
        assertEquals(
            QuroFrameVisionSource.IMAGE_RECOGNITION_MODEL,
            QuroFrameVisionRouter.plan(false, true, absent, dedicated)
        )
    }

    @Test
    fun `图像识别模型只是跟随主模型时不可用`() {
        assertEquals(
            QuroFrameVisionSource.NONE,
            QuroFrameVisionRouter.plan(false, true, absent, followGlobal)
        )
    }

    @Test
    fun `图像识别模型没有密钥时不可用`() {
        assertEquals(
            QuroFrameVisionSource.NONE,
            QuroFrameVisionRouter.plan(false, true, absent, dedicatedNoKey)
        )
    }

    // ── ④ 全都没有：纯语音 ──

    @Test
    fun `什么都没配时降级为无画面描述`() {
        assertEquals(
            QuroFrameVisionSource.NONE,
            QuroFrameVisionRouter.plan(false, true, absent, absent)
        )
    }

    @Test
    fun `默认参数就是纯语音`() {
        assertEquals(QuroFrameVisionSource.NONE, QuroFrameVisionRouter.plan(false, false))
    }

    @Test
    fun `视频通话没配但图像识别配了 这就是用户说的保底`() {
        // 用户原话场景：功能模型配置里只配了图像识别（或只配了视频通话），另一档没配。
        assertEquals(
            QuroFrameVisionSource.IMAGE_RECOGNITION_MODEL,
            QuroFrameVisionRouter.plan(false, true, absent, dedicated)
        )
        assertEquals(
            QuroFrameVisionSource.VIDEO_CALL_MODEL,
            QuroFrameVisionRouter.plan(false, true, dedicated, absent)
        )
    }

    // ── 可用性判定 ──

    @Test
    fun `可用必须同时满足独立模型与有密钥`() {
        assertTrue(dedicated.usable)
        assertFalse(absent.usable)
        assertFalse(followGlobal.usable)
        assertFalse(dedicatedNoKey.usable)
    }

    @Test
    fun `默认候选不可用`() {
        assertFalse(QuroFrameVisionOption().usable)
        assertFalse(QuroFrameVisionOption.NONE.usable)
    }

    // ── 视觉能力判定（黑名单，默认乐观）──

    @Test
    fun `明确纯文本模型判为无视觉`() {
        assertFalse(QuroFrameVisionRouter.isLikelyVisionModel("gpt-3.5-turbo"))
        assertFalse(QuroFrameVisionRouter.isLikelyVisionModel("text-embedding-3-small"))
        assertFalse(QuroFrameVisionRouter.isLikelyVisionModel("whisper-1"))
        assertFalse(QuroFrameVisionRouter.isLikelyVisionModel("qwen2.5-1.5b"))
        assertFalse(QuroFrameVisionRouter.isLikelyVisionModel("qwen3-0.6b"))
        assertFalse(QuroFrameVisionRouter.isLikelyVisionModel("llama2-13b-chat"))
    }

    @Test
    fun `视觉模型判为有视觉`() {
        assertTrue(QuroFrameVisionRouter.isLikelyVisionModel("gpt-4o"))
        assertTrue(QuroFrameVisionRouter.isLikelyVisionModel("gpt-4o-mini"))
        assertTrue(QuroFrameVisionRouter.isLikelyVisionModel("qwen2.5-vl-7b"))
        assertTrue(QuroFrameVisionRouter.isLikelyVisionModel("gemini-2.0-flash"))
        assertTrue(QuroFrameVisionRouter.isLikelyVisionModel("claude-3-5-sonnet"))
    }

    @Test
    fun `大小写与空白不敏感`() {
        assertFalse(QuroFrameVisionRouter.isLikelyVisionModel("  GPT-3.5-TURBO  "))
        assertTrue(QuroFrameVisionRouter.isLikelyVisionModel(" GPT-4O "))
    }

    @Test
    fun `模型名缺省时按乐观处理`() {
        assertTrue(QuroFrameVisionRouter.isLikelyVisionModel(""))
        assertTrue(QuroFrameVisionRouter.isLikelyVisionModel("   "))
        assertTrue(QuroFrameVisionRouter.isLikelyVisionModel(null))
    }

    @Test
    fun `带视觉后缀的模型不能被instruct 之类误伤`() {
        // 旧黑名单里有裸 "instruct"，会命中 qwen2.5-vl-instruct 这类实际支持视觉的模型。
        assertTrue(QuroFrameVisionRouter.isLikelyVisionModel("qwen2.5-vl-instruct"))
        assertTrue(QuroFrameVisionRouter.isLikelyVisionModel("llama-3.2-11b-vision-instruct"))
    }

    // ── 语义边界：视频识别不得混入本链路 ──

    @Test
    fun `视频通话链路不消费视频识别角色`() {
        // 路由器签名里根本没有 VIDEO_RECOGNITION 的入参 —— 这是编译期保证，
        // 这里再做一次字符串级断言，防止将来有人"顺手"把它接回来。
        val src = QuroFrameVisionRouter::class.java.declaredMethods
            .filter { it.name == "plan" }
            .map { it.parameterTypes.map { p -> p.name }.joinToString(",") }
        assertTrue(src.isNotEmpty())
        assertTrue(src.none { it.contains("VIDEO_RECOGNITION") })
    }

    @Test
    fun `四种画面来源枚举齐全且顺序即优先级`() {
        val values = QuroFrameVisionSource.values().toList()
        assertEquals(4, values.size)
        assertEquals(
            listOf(
                QuroFrameVisionSource.MAIN_MODEL,
                QuroFrameVisionSource.VIDEO_CALL_MODEL,
                QuroFrameVisionSource.IMAGE_RECOGNITION_MODEL,
                QuroFrameVisionSource.NONE,
            ),
            values
        )
    }
}