package com.ai.assistance.quro.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆层：认知分型 [QuroMemoryKind] / [QuroMemoryKindPolicy]。
 *
 * 三条铁律各有对应断言：
 *  1. **不误分**：明显是偏好的内容不得被推成情节记忆。
 *  2. **不丢数据**：分型缺失（历史 JSON 无 kind 键）必须回落而不是崩。
 *  3. **不污染**：过期的工作记忆必须退出检索（权重归零），情节记忆只降权不删。
 */
class QuroMemoryKindTest {

    private val now = 1_700_000_000_000L
    private val day = 24 * 3600 * 1000L

    @Test
    fun four_cognitive_types_exist() {
        assertEquals(4, QuroMemoryKind.entries.size)
        assertEquals(QuroMemoryKind.SEMANTIC, QuroMemoryKind.of("semantic"))
        assertEquals(QuroMemoryKind.EPISODIC, QuroMemoryKind.of("EPISODIC"))
        assertEquals(QuroMemoryKind.PROCEDURAL, QuroMemoryKind.of(" procedural "))
        assertEquals(QuroMemoryKind.WORKING, QuroMemoryKind.of("working"))
    }

    @Test
    fun unknown_kind_key_returns_null_so_caller_can_fall_back() {
        assertNull(QuroMemoryKind.of(null))
        assertNull(QuroMemoryKind.of(""))
        assertNull(QuroMemoryKind.of("   "))
        assertNull(QuroMemoryKind.of("nonsense"))
    }

    @Test
    fun resolve_prefers_explicit_kind_over_inference() {
        // 显式 kind 永远优先：哪怕内容看起来像情节，用户标了 procedural 就是 procedural。
        val k = QuroMemoryKindPolicy.resolve(
            kindKey = "procedural",
            title = "昨天那个部署流程",
            content = "先 build 再 sign",
            tags = emptyList(),
        )
        assertEquals(QuroMemoryKind.PROCEDURAL, k)
    }

    @Test
    fun missing_kind_falls_back_to_content_inference_not_crash() {
        // 历史记忆没有 kind 键 —— 必须推导出合理分型，绝不能抛异常或返回 null。
        val pref = QuroMemoryKindPolicy.resolve("", "用户偏好", "我讨厌被叫全名，以后都叫我小王", emptyList())
        assertEquals(QuroMemoryKind.SEMANTIC, pref)

        val proc = QuroMemoryKindPolicy.resolve("", "部署", "步骤：先跑 gradlew，再 apksigner 签名", emptyList())
        assertEquals(QuroMemoryKind.PROCEDURAL, proc)

        val epi = QuroMemoryKindPolicy.resolve("", "上周的事", "昨天用户说他把服务器密码忘了", emptyList())
        assertEquals(QuroMemoryKind.EPISODIC, epi)

        val work = QuroMemoryKindPolicy.resolve("", "草稿", "这个功能的临时方案，还没写完", emptyList())
        assertEquals(QuroMemoryKind.WORKING, work)
    }

    @Test
    fun explicit_tag_is_most_trustworthy_signal() {
        val k = QuroMemoryKindPolicy.infer(
            title = "乱七八糟的标题",
            content = "随便一段话",
            tags = listOf("procedural"),
        )
        assertEquals(QuroMemoryKind.PROCEDURAL, k)
    }

    @Test
    fun unclassifiable_content_falls_back_to_semantic() {
        // 推不出就回落语义（最通用、最不会引发错误行为），绝不返回 null。
        val k = QuroMemoryKindPolicy.infer("t", "c", emptyList())
        assertNotNull(k)
        assertEquals(QuroMemoryKind.FALLBACK, k)
        assertEquals(QuroMemoryKind.SEMANTIC, k)
    }

    @Test
    fun semantic_and_procedural_never_decay() {
        assertEquals(1.0, QuroMemoryKindPolicy.weightOf(QuroMemoryKind.SEMANTIC, 365 * day, now), 0.0)
        assertEquals(1.0, QuroMemoryKindPolicy.weightOf(QuroMemoryKind.PROCEDURAL, 365 * day, now), 0.0)
    }

    @Test
    fun episodic_decays_but_is_never_deleted() {
        val fresh = QuroMemoryKindPolicy.weightOf(QuroMemoryKind.EPISODIC, 1 * day, now)
        assertEquals(1.0, fresh, 0.0)

        val stale = QuroMemoryKindPolicy.weightOf(QuroMemoryKind.EPISODIC, 60 * day, now)
        assertTrue("过期情节记忆应降权", stale in 0.0..1.0 && stale < 1.0)
        assertTrue("降权不得归零（用户仍可能想找回）", stale > 0.0)
        assertTrue(QuroMemoryKindPolicy.alive(QuroMemoryKind.EPISODIC, 60 * day))
    }

    @Test
    fun working_memory_expires_entirely() {
        assertTrue(QuroMemoryKindPolicy.alive(QuroMemoryKind.WORKING, 1 * 3600 * 1000L))
        val dead = QuroMemoryKindPolicy.weightOf(QuroMemoryKind.WORKING, 3 * 3600 * 1000L, now)
        assertEquals(0.0, dead, 0.0)
        assertFalse("过期工作记忆必须退出检索", QuroMemoryKindPolicy.alive(QuroMemoryKind.WORKING, 3 * 3600 * 1000L))
    }

    @Test
    fun negative_age_is_treated_as_fresh() {
        // 设备时间被往回调时 ageMs 会是负数，此时不得把所有记忆判成过期。
        assertEquals(1.0, QuroMemoryKindPolicy.weightOf(QuroMemoryKind.WORKING, -5000, now), 0.0)
    }

    @Test
    fun directives_are_ascii_and_type_specific() {
        for (k in QuroMemoryKind.entries) {
            val d = QuroMemoryKindPolicy.directive(k)
            assertTrue(d.isNotEmpty())
            assertTrue(
                "指令头必须是 ASCII 前缀（避免污染思考语言）：$d",
                d.all { it.code < 128 },
            )
        }
        // 四条指令必须互不相同 —— 否则分型对模型没有意义。
        val all = QuroMemoryKind.entries.map { QuroMemoryKindPolicy.directive(it) }
        assertEquals(all.size, all.distinct().size)
    }

    @Test
    fun procedural_directive_says_follow_steps() {
        val d = QuroMemoryKindPolicy.directive(QuroMemoryKind.PROCEDURAL)
        assertTrue(d.contains("procedure"))
        assertTrue(d.contains("steps"))
    }

    @Test
    fun episodic_directive_forbids_generalizing() {
        val d = QuroMemoryKindPolicy.directive(QuroMemoryKind.EPISODIC)
        assertTrue(d.contains("NOT"))
        assertTrue(d.contains("once"))
    }
}
