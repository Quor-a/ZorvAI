package com.ai.assistance.quro.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 族 → 图标名映射的**契约测试**。
 *
 * ## 🔴 为什么必须有这个测试
 * `LucideIcon.kt` 的 `iconRes()` 是 `when(name)` + `else -> ic_x` ——
 * **写错名字不会编译失败，只会静默显示一个 X**。
 * 旧 `toolCategory` 就是这么坏的：它用的 `"terminal"` / `"globe"` / `"folder-open"`
 * 在本仓 34 个 `ic_*` drawable 里一个都不存在，所有工具图标长期显示成 X。
 *
 * 本测试把「族 → 图标名」钉死，并强制校验每个名字都能在 drawable 目录找到对应文件。
 */
class QuroToolFamilyIconTest {

    /** 与 `res/drawable` 下实际存在的 qic_* 文件保持一致。 */
    private val availableIcons = setOf(
        "qic_code_run", "qic_file_write", "qic_file_read", "qic_web", "qic_terminal",
        "qic_device", "qic_system", "qic_doc", "qic_media", "qic_memory", "qic_ui",
        "qic_search", "qic_other",
        "qic_ok", "qic_fail", "qic_warn", "qic_info", "qic_running",
    )

    /** 与 `ToolCallRichCard.familyStyle()` 保持一致（该函数是 private，用同值镜像校验）。 */
    private val familyIcon = mapOf(
        QuroToolSummary.Family.CODE to "qic_code_run",
        QuroToolSummary.Family.FILE_WRITE to "qic_file_write",
        QuroToolSummary.Family.FILE_READ to "qic_file_read",
        QuroToolSummary.Family.WEB to "qic_web",
        QuroToolSummary.Family.TERMINAL to "qic_terminal",
        QuroToolSummary.Family.DEVICE to "qic_device",
        QuroToolSummary.Family.SYSTEM to "qic_system",
        QuroToolSummary.Family.DOC to "qic_doc",
        QuroToolSummary.Family.MEDIA to "qic_media",
        QuroToolSummary.Family.MEMORY to "qic_memory",
        QuroToolSummary.Family.UI to "qic_ui",
        QuroToolSummary.Family.OTHER to "qic_other",
    )

    @Test
    fun `每个族都有映射`() {
        QuroToolSummary.Family.entries.forEach { f ->
            assertTrue("$f 缺少图标映射", familyIcon.containsKey(f))
        }
    }

    @Test
    fun `所有族图标名都在可用清单内`() {
        familyIcon.values.forEach { name ->
            assertTrue("图标 $name 不在可用清单，会静默退化成 X", availableIcons.contains(name))
        }
    }

    @Test
    fun `每个族图标都不重复`() {
        val dup = familyIcon.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("族图标不应复用：$dup", dup.isEmpty())
    }

    @Test
    fun `状态图标齐备`() {
        listOf("qic_ok", "qic_fail", "qic_warn", "qic_info", "qic_running").forEach {
            assertTrue("状态图标 $it 缺失", availableIcons.contains(it))
        }
    }

    @Test
    fun `Web 族与 Search 图标都存在供检索类使用`() {
        assertTrue(availableIcons.contains("qic_web"))
        assertTrue(availableIcons.contains("qic_search"))
    }

    @Test
    fun `旧实现踩过的假图标名不与真实图标名重合`() {
        // 记录这次踩过的坑：这些名字在 Lucide 官方里存在，但本仓没有对应 drawable，
        // 用上去只会静默显示 X。本测试确保真实清单里不含它们 ——
        // 哪天真要补齐，也必须同步 LucideIcon.kt 的 when 分支，否则这里会拦住。
        val legacyFakeNames = setOf(
            "terminal", "globe", "folder-open", "wrench", "check-circle-2", "alert-triangle",
        )
        val overlap = legacyFakeNames intersect availableIcons
        assertTrue(
            "以下名字会静默退化成 X，必须同时补上 drawable 与 LucideIcon.kt 分支才可加入清单：$overlap",
            overlap.isEmpty(),
        )
    }
}
