package com.ai.assistance.quro.core.experience

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 经验引擎：分类 / 沉淀 / 相关性检索 / 自我纠错 / 版本自检 / 进化指标。
 *
 * 设计对齐腾讯元宝方案的「OODA 自我进化循环」：
 * - Observe：对话中遇到报错 / 解决 / 工具模式 / 版本差异
 * - Orient：classify 归类 + queryRelevant 找历史经验
 * - Decide / Act：log 沉淀新经验 / correct 修正旧经验 / recordCompat 记版本差异
 * - Feedback：下次相关对话经 queryRelevant 自动注入 top-N 经验，闭环复用
 */
class QuroExperienceEngine(private val repo: QuroExperienceRepository) {

    /** 启发式归类：给定文本与可选类型提示，推断经验类型。 */
    fun classify(text: String, typeHint: String? = null): ExperienceType {
        val hint = ExperienceType.from(typeHint)
        if (hint != ExperienceType.ERROR) return hint // 显式指定且非默认 error 时尊重
        val t = text.lowercase()
        return when {
            listOf("报错", "崩溃", "异常", "失败", "crash", "exception", "error", "bug", "anr", "闪退", "不生效", "没反应")
                .any { t.contains(it) } -> ExperienceType.ERROR
            listOf("解决", "修复", "方案", "可用", "成功", "workaround", "fix", "修复后", "应该这样")
                .any { t.contains(it) } -> ExperienceType.SOLUTION
            listOf("模式", "工具", "用法", "习惯", "每次", "pattern", "tool", "prefer", "默认")
                .any { t.contains(it) } -> ExperienceType.PATTERN
            listOf("版本", "兼容", "android", "sdk", "api", "kotlin", "兼容", "broken_since")
                .any { t.contains(it) } -> ExperienceType.COMPAT
            else -> ExperienceType.ERROR
        }
    }

    /** 沉淀一条经验（含可选版本兼容标记）。返回新建条目。 */
    fun log(
        type: ExperienceType,
        title: String,
        content: String,
        tags: List<String>,
        platform: String,
        validIn: List<String> = emptyList(),
        brokenSince: String = "",
        workaround: String = "",
    ): QuroExperienceEntry {
        val entry = QuroExperienceEntry(
            type = type,
            title = title.trim(),
            content = content.trim(),
            tags = tags.map { it.trim() }.filter { it.isNotBlank() },
            platform = platform.trim(),
        )
        repo.addExperience(entry)
        // 兼容性经验同时落地一个版本标记，供 versionSelfCheck 评估
        if (type == ExperienceType.COMPAT && (validIn.isNotEmpty() || brokenSince.isNotBlank())) {
            repo.addCompat(
                QuroCompatMarker(
                    subject = if (platform.isNotBlank()) platform else (title.ifBlank { content.take(40) }),
                    validIn = validIn,
                    brokenSince = brokenSince,
                    workaround = workaround.ifBlank { content },
                )
            )
        }
        return entry
    }

    /**
     * 相关性检索（Feedback 闭环核心）：按标签交集 + 文本包含打分，返回 top-N。
     * @param bumpReuse 为 true 时同步累加命中条目的 reuseCount（用于进化指标）。
     */
    fun queryRelevant(query: String, topN: Int = 5, bumpReuse: Boolean = false): List<QuroExperienceEntry> {
        val q = query.lowercase()
        if (q.isBlank()) return emptyList()
        val tokens = q.split(TOKEN_RE).map { it.trim() }.filter { it.length >= 2 }
        if (tokens.isEmpty()) return emptyList()

        val (exps, _, _) = repo.loadAll()
        val scored = exps.mapNotNull { e ->
            val hay = buildString {
                append(e.title).append(' ').append(e.content).append(' ')
                append(e.tags.joinToString(" ")).append(' ').append(e.platform)
            }.lowercase()
            var score = 0
            tokens.forEach { tk -> if (hay.contains(tk)) score += 1 }
            // 标签精确命中加权（经验标签通常更聚焦）
            e.tags.forEach { tg -> if (tokens.any { it == tg.lowercase() || tkContains(it, tg.lowercase()) }) score += 2 }
            if (score <= 0) null else e to score
        }
        val top = scored.sortedWith(compareByDescending<Pair<QuroExperienceEntry, Int>> { it.second }
            .thenByDescending { it.first.correctedAt }.thenByDescending { it.first.createdAt })
            .take(topN).map { it.first }
        if (bumpReuse && top.isNotEmpty()) repo.bumpReuse(top.map { it.id })
        return top
    }

    /** 记录一次自我纠错：标记原经验已修正并追加纠错日志。返回是否命中。 */
    fun recordCorrection(id: String, was: String, reason: String, fix: String): Boolean {
        val (exps, _, _) = repo.loadAll()
        val target = exps.firstOrNull { it.id == id } ?: return false
        val updated = target.copy(
            correctedAt = System.currentTimeMillis(),
            correctionCount = target.correctionCount + 1,
        )
        repo.updateExperience(updated)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        repo.addCorrection(
            QuroCorrectionLog(
                date = today,
                experienceId = id,
                was = was.trim(),
                reason = reason.trim(),
                fix = fix.trim(),
            )
        )
        return true
    }

    /** 记录 / 覆盖一个版本兼容标记。 */
    fun recordCompat(marker: QuroCompatMarker) {
        repo.addCompat(marker)
    }

    /**
     * 版本自检：返回对给定 (platform, version) 已失效的兼容标记（broken_since 非空且该版本 ≥ broken_since）。
     * 若无 broken_since 但 valid_in 非空且该版本不在区间内，也判为「可能失效」。
     */
    fun versionSelfCheck(platform: String, version: String): List<QuroCompatMarker> {
        val (_, comps, _) = repo.loadAll()
        if (comps.isEmpty()) return emptyList()
        val v = parseVersion(version)
        return comps.filter { m ->
            val subjMatch = platform.isBlank() || m.subject.lowercase().contains(platform.lowercase())
            val broken = m.brokenSince.isNotBlank() && v != null && compareVersion(v, parseVersion(m.brokenSince) ?: return@filter false) >= 0
            val outOfRange = m.validIn.isNotEmpty() && v != null && m.validIn.none { inRange(v, it) }
            subjMatch && (broken || outOfRange)
        }
    }

    /** 进化指标：对齐元宝方案的 Correction Rate / Reuse Count / Accuracy Trend / Version Drift。 */
    fun metrics(): ExperienceMetrics {
        val (exps, comps, cors) = repo.loadAll()
        val total = exps.size
        val corrected = exps.count { it.correctionCount > 0 }
        val reuse = exps.sumOf { it.reuseCount }
        val correctionRate = if (total == 0) 0.0 else corrected.toDouble() / total
        return ExperienceMetrics(
            totalExperiences = total,
            totalCorrections = cors.size,
            correctedExperiences = corrected,
            correctionRate = correctionRate,
            totalReuse = reuse,
            compatMarkers = comps.size,
            versionDrift = comps.count { it.brokenSince.isNotBlank() },
        )
    }

    // ---- 内部工具 ----

    private fun tkContains(token: String, tag: String): Boolean = tag.contains(token) || token.contains(tag)

    private fun inRange(v: List<Int>, range: String): Boolean {
        // 支持 "1.0" 单点 或 "1.0-1.2" 区间
        if (range.contains("-")) {
            val (a, b) = range.split("-", limit = 2)
            val va = parseVersion(a) ?: return false
            val vb = parseVersion(b) ?: return false
            return compareVersion(v, va) >= 0 && compareVersion(v, vb) <= 0
        }
        val target = parseVersion(range) ?: return false
        return compareVersion(v, target) == 0
    }

    private fun parseVersion(s: String): List<Int>? {
        val cleaned = s.trim().trimStart('v', 'V')
        if (cleaned.isBlank()) return null
        val segs = cleaned.split('.', '-', '_', limit = 4)
        val nums = segs.mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        return if (nums.isEmpty()) null else nums
    }

    private fun compareVersion(a: List<Int>, b: List<Int>): Int {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    companion object {
        private val TOKEN_RE = Regex("[^\\p{L}\\p{N}]+")
    }
}

data class ExperienceMetrics(
    val totalExperiences: Int,
    val totalCorrections: Int,
    val correctedExperiences: Int,
    val correctionRate: Double,
    val totalReuse: Int,
    val compatMarkers: Int,
    val versionDrift: Int,
)
