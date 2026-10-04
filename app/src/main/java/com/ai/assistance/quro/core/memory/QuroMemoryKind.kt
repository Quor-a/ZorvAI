package com.ai.assistance.quro.core.memory

/**
 * 记忆层：**认知分型**。
 *
 * ## 为什么此前算「部分达标」
 * [QuroMemoryStore] 此前已经把工程化的部分做齐了——多空间（personaId）、分组（group）、
 * 标签、BM25 检索、窗口规划、自动保存候选、导入导出。缺的**不是功能，是分型**：
 * 所有记忆被塞在同一张表、同一套检索里，检索时无法按「记忆的**性质**」分派。
 * 这直接导致两类现实故障：
 *  - 「我上周说过一次」的**情景**（某次对话里发生过的事）和「用户长期偏好」的**语义**
 *    混在一起，前者被当成稳定偏好反复生效，越用越歪。
 *  - **程序性**记忆（用户固定的操作流程，比如「部署要先 build 再 sign」）本该在
 *    命中时直接驱动动作，现在只作为一段文本塞进上下文，靠模型自己想起来。
 *
 * ## 四种分型
 * | 分型 | 存什么 | 检索时该怎么用 |
 * |---|---|---|
 * | [SEMANTIC] 语义 | 稳定事实、长期偏好、用户画像 | 常规注入；可长期驻留 |
 * | [EPISODIC] 情节 | 某一次交互里发生的事（带时间锚点） | 常规注入；**会衰减** |
 * | [PROCEDURAL] 程序 | 固定操作流程 / 步骤 | 命中时优先作为「怎么做」的行动指令 |
 * | [WORKING] 工作 | 当前任务的临时草稿 | **最短保鲜**，超期即失，不进长期记忆 |
 *
 * ## 兼容性铁律
 * [QuroMemoryEntry.kind] 是**带默认值的可选字段**，历史 JSON 里没有该键时
 * [QuroMemoryKind.resolve] 会按内容推断出合理分型，**老记忆库零迁移即可用**。
 * 绝不因为分型缺失就丢弃或拒绝加载旧条目。
 */
enum class QuroMemoryKind(val key: String, val label: String) {
    /** 稳定事实 / 长期偏好 / 用户画像。 */
    SEMANTIC("semantic", "语义"),

    /** 某一次交互里发生的事，带时间锚点。 */
    EPISODIC("episodic", "情节"),

    /** 固定操作流程与步骤。 */
    PROCEDURAL("procedural", "程序"),

    /** 当前任务的临时草稿，最短保鲜。 */
    WORKING("working", "工作");

    companion object {
        /** 未知 / 非法值回落目标：语义（最通用、最不会引发错误行为）。 */
        val FALLBACK = SEMANTIC

        fun of(key: String?): QuroMemoryKind? {
            if (key.isNullOrBlank()) return null
            val k = key.trim().lowercase()
            return entries.firstOrNull { it.key == k }
        }
    }
}

object QuroMemoryKindPolicy {
    /** 情节记忆保鲜期：超过则**检索降权**（不删除，用户可能仍想找回来）。 */
    const val EPISODIC_TTL_MS = 30L * 24 * 3600 * 1000

    /** 工作记忆保鲜期：超期即**不参与常规检索**。 */
    const val WORKING_TTL_MS = 2L * 3600 * 1000

    /** 分型解析：显式 kind 优先；缺失时按内容启发式推断；推不出回落 [QuroMemoryKind.FALLBACK]。 */
    fun resolve(
        kindKey: String,
        title: String,
        content: String,
        tags: List<String>,
    ): QuroMemoryKind {
        QuroMemoryKind.of(kindKey)?.let { return it }
        return infer(title, content, tags)
    }

    /** 仅按内容推断分型（历史数据迁移 / 用户没指定 kind 时用）。 */
    fun infer(title: String, content: String, tags: List<String>): QuroMemoryKind {
        val blob = (title + " " + content + " " + tags.joinToString(" ")).lowercase()

        // 显式标签最可信：用户自己打的标签就是他的意图
        for (t in tags) {
            QuroMemoryKind.of(t)?.let { return it }
        }
        // 程序性：出现步骤 / 命令 / 流程 / 顺序信号
        if (Regex("(步骤|流程|先.{0,6}再|按顺序|第[一二三四五六七八九十]步|依次)").containsMatchIn(blob)) {
            return QuroMemoryKind.PROCEDURAL
        }
        if (Regex("(^|\\s)(gradlew|adb |git |npm |python |curl |bash |sh )").containsMatchIn(blob)) {
            return QuroMemoryKind.PROCEDURAL
        }
        // 情节性：出现明确时间锚点 + 一次性事件动词
        if (Regex("(昨天|今天|刚才|上次|上周|前几天|当时|那次|刚刚)").containsMatchIn(blob)) {
            return QuroMemoryKind.EPISODIC
        }
        // 工作性：显式草稿 / 待办信号
        if (Regex("(草稿|临时|待办|todo|暂存|还没|未完成)").containsMatchIn(blob)) {
            return QuroMemoryKind.WORKING
        }
        // 语义性：稳定偏好 / 画像信号
        if (Regex("(喜欢|讨厌|偏好|习惯|always|never|以后都|默认|我是|我叫|我的)").containsMatchIn(blob)) {
            return QuroMemoryKind.SEMANTIC
        }
        return QuroMemoryKind.FALLBACK
    }

    /**
     * 检索权重系数：1.0 = 原权重，<1 = 降权。
     *
     * 情节记忆超保鲜期降权（而非删除）：用户偶尔仍会问「上次那个结论是什么」。
     * 工作记忆超期直接归零：临时草稿留在检索结果里只会污染上下文。
     */
    fun weightOf(kind: QuroMemoryKind, ageMs: Long, now: Long = System.currentTimeMillis()): Double {
        if (ageMs < 0) return 1.0
        return when (kind) {
            QuroMemoryKind.SEMANTIC -> 1.0
            QuroMemoryKind.PROCEDURAL -> 1.0
            QuroMemoryKind.EPISODIC ->
                if (ageMs > EPISODIC_TTL_MS) 0.4 else 1.0
            QuroMemoryKind.WORKING ->
                if (ageMs > WORKING_TTL_MS) 0.0 else 1.0
        }
    }

    /** 该条记忆此刻是否应参与常规检索（工作记忆过期即出局）。 */
    fun alive(kind: QuroMemoryKind, ageMs: Long): Boolean = weightOf(kind, ageMs) > 0.0

    /** 给模型看的一行说明（ASCII 头，避免污染思考语言）。 */
    fun directive(kind: QuroMemoryKind): String = when (kind) {
        QuroMemoryKind.PROCEDURAL ->
            "memory[procedural]: this is a fixed procedure the user expects to be followed - follow its steps instead of improvising."
        QuroMemoryKind.SEMANTIC ->
            "memory[semantic]: a stable fact or preference - treat it as always true unless the user corrects it."
        QuroMemoryKind.EPISODIC ->
            "memory[episodic]: something that happened once - do NOT generalize it into a lasting rule."
        QuroMemoryKind.WORKING ->
            "memory[working]: a temporary draft for the current task only - do not persist it as a long-term fact."
    }
}
