package com.ai.assistance.quro.core.mika.verify

import com.ai.assistance.quro.core.mika.observation.InputHints
import com.ai.assistance.quro.core.mika.observation.ObsKind
import com.ai.assistance.quro.core.mika.observation.Observation
import com.ai.assistance.quro.core.mika.record.Redactor

/**
 * 一次任务里助手实际读到的全部材料(证据账本):读屏、读网页、命令输出与读到的文件、工具结果里的事实。
 * 运行时的核查器和评测的核对用的是同一套账本和同一个挑选规则([forAnswer]),两边不会各看各的。
 *
 * - 进账前脱敏([Redactor.evidence]),每条按来源截短;同样的原文只留最近一次(来处跟着更新)。
 *   接管之后读到的输入框里不是助手打的字(用户在接管时填的)不记;命令输出里原样出现在命令本身里的行(回显)不记。
 * - 总量有上限([capChars]):超了从最早的丢起。这是内存上的安全上限,正常任务碰不到。
 * - 交给核对的条目([forAnswer]):含有回答里某条事实的每一条(一条也不丢)、这些条目和回答提到的每个 App 或网址最近的那一条
 *   (后来读到的可能和回答矛盾)、最近读到的 [RECENT] 条。按读到的先后排。代码的核对看这些条目的全文;
 *   核查模型只看它们的节选([excerpt]:对上事实的行和前后几行,总字数有上限),带着来处(第几步、哪种来源、哪个 App 或网址、网页标题)。
 *   挑选只决定给看哪些,核对规则([Grounding])不变:没读到过的东西,账本里没有,挑多少都证不了。
 * - 助手打进输入框的字不给回答作证:那个输入框里的字、输入框还有焦点时以这些字开头的联想行,挑选时去掉;
 *   结果里恰好和搜索词一样的标题照样留着。不知道哪些是输入框的(旧记录)按整行等于打进去的字去掉。
 */
class EvidenceLedger(private val capChars: Int = STORE_CHARS) {
    private val entries = ArrayDeque<Observation>()
    private val typed = mutableSetOf<String>()
    private var total = 0
    private var seq = 0
    private var afterTakeover = false

    val all: List<Observation> get() = entries.toList()
    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * 记一条观察;脱敏、截短后为空的不记,返回记下的那一条(填好编号、第几步、时刻;[step]、[atMs] 是此刻的)。
     * 观察里的 [Observation.inputs] 是那一次输入框里的字;[Observation.authored] 是助手自己写的东西(跑的命令):原样出现在里面的行是回显,不记。
     */
    fun add(o: Observation, step: Int = o.step, atMs: Long = o.atMs): Observation? {
        val ins = o.inputs?.names?.map(::norm)?.filter { it.isNotEmpty() }
        var lines = Redactor.evidence(o.text).lines()
        // 接管时用户自己填的字(输入框里不是助手打的)不留
        if (afterTakeover && ins != null) lines = lines.filterNot { l -> norm(l).let { n -> n in ins && typed.none { it in n } } }
        o.authored?.let(::norm)?.takeIf { it.isNotEmpty() }?.let { a -> lines = lines.filterNot { l -> norm(l).let { it.isNotEmpty() && it in a } } }
        val t = clip(o.kind, lines.joinToString("\n"))
        if (t.isBlank()) return null
        val place = o.place?.let { it.copy(name = Redactor.evidence(it.name)) }
        // 回显已经去掉了,记下的这条不再带着助手自己写的东西
        return insert(o.copy(text = t, place = place, step = step, atMs = atMs, id = ++seq, authored = null,
            inputs = ins?.let { InputHints(it, o.inputs.focused?.let(::norm)?.takeIf { f -> f.isNotEmpty() }) },
            title = o.title?.let(Redactor::evidence)?.takeIf { it.isNotBlank() }))
    }

    /**
     * 照原样放回一条运行时已经记下的观察(运行记录里的 observed,已脱敏、截短、去掉回显,编号也是那时的):
     * 不再过滤,只照进账时一样去掉同样的旧一条、守住总量上限。离线重建账本用,和运行时的账本一样。
     */
    fun restore(o: Observation): Observation {
        seq = maxOf(seq, o.id)
        return insert(o)
    }

    private fun insert(stored: Observation): Observation {
        entries.indexOfFirst { it.text == stored.text && it.place == stored.place }.takeIf { it >= 0 }?.let { total -= entries.removeAt(it).text.length }
        entries.addLast(stored)
        total += stored.text.length
        while (total > capChars && entries.size > 1) total -= entries.removeFirst().text.length
        return stored
    }

    /** 这段原文(按进账时一样脱敏、截短后)已经在账本里。 */
    fun has(kind: ObsKind, text: String): Boolean = clip(kind, Redactor.evidence(text)).let { t -> entries.any { it.text == t } }

    /** 助手用 type_text 打进去的字;只记不是密码框的。 */
    fun noteTyped(text: String) {
        norm(text).takeIf { it.isNotEmpty() }?.let { typed += it }
    }

    /** 交给用户接管过:之后读屏里输入框中不是助手打的字不再进账。 */
    fun noteTakeover() {
        afterTakeover = true
    }

    /** 同一份账本的副本(运行时把账本交给核查器时用,之后的读屏不影响这份)。 */
    fun copy(): EvidenceLedger = EvidenceLedger(capChars).also { c ->
        c.entries.addAll(entries); c.typed.addAll(typed); c.total = total; c.seq = seq; c.afterTakeover = afterTakeover
    }

    /** 交给核对的条目:见类说明。每条已去掉输入框回显;去掉后没字的不选。 */
    fun forAnswer(goal: String, answer: String): List<Observation> {
        val cands = entries.mapNotNull(::withoutEcho)
        val holds = cands.indices.filter { i -> Grounding.check(goal, answer, listOf(cands[i].text)).let { it.claims.size > it.unmatched.size } }
        val said = (goal + "\n" + answer).lowercase()
        val places = holds.mapNotNull { cands[it].where } + cands.mapNotNull { it.where }.filter { w -> placeNames(w).any { it in said } }
        val latest = places.distinct().map { w -> cands.indexOfLast { it.where == w } }
        val recent = (cands.size - RECENT until cands.size).filter { it >= 0 }
        return (holds + latest + recent).distinct().sorted().map { cands[it] }
    }

    /** 最近进账的那一条的编号(还没进账为 0);和 [readSince] 一起判断某个时刻之后有没有重新读过。 */
    val lastId: Int get() = seq

    /** 编号 [id] 之后读到过东西(读屏、网页、命令输出与文件;工具报告的事实不算读)。 */
    fun readSince(id: Int): Boolean = entries.any { it.id > id && it.kind != ObsKind.Fact }

    /** 回答里的事实在其中对得上的条目(已去掉输入框回显,按读到的先后);回答里没有可核对的事实时为空。回答的来源由它来找。 */
    fun supporting(goal: String, answer: String): List<Observation> =
        entries.mapNotNull(::withoutEcho).filter { e -> Grounding.check(goal, answer, listOf(e.text)).let { it.claims.size > it.unmatched.size } }

    private fun withoutEcho(e: Observation): Observation? {
        if (typed.isEmpty() || e.kind != ObsKind.Read) return e
        val lines = e.text.lines()
        val ins = e.inputs?.names
        val kept = if (ins == null) lines.filterNot { norm(it) in typed } else {
            // 装着打进去的字的输入框,每个去掉一行(同样字的结果标题照样留着)
            val left = ins.filter { n -> typed.any { it in n } }.groupingBy { it }.eachCount().toMutableMap()
            // 还有焦点的输入框里是打进去的字:以这些字开头、更长的行是联想
            val suggesting = e.inputs?.focused?.let { f -> typed.filter { it in f } }.orEmpty()
            lines.filterNot { l ->
                val n = norm(l)
                val k = left[n] ?: 0
                if (k > 0) { left[n] = k - 1; true } else n !in ins && suggesting.any { n.startsWith(it) && n.length > it.length }
            }
        }
        return if (kept.size == lines.size) e else kept.joinToString("\n").takeIf { it.isNotBlank() }?.let { e.copy(text = it) }
    }

    companion object {
        /** 账本总字数的安全上限。 */
        const val STORE_CHARS = 400_000
        /** 交给核查模型的节选总字数的目标上限(含有回答事实的条目一条不丢,放不下时每条再缩短)。 */
        const val EXCERPT_CHARS = 6_000
        /** 节选时对上事实的行前后各留几行(放不下时逐步减到 0)。 */
        const val EXCERPT_CONTEXT = 2
        /** 没有对上事实的条目(某处最近的一次、最近读的)只给开头这么多字。 */
        const val HEAD_CHARS = 600
        /** 放不下时每条节选至少留这么多字。 */
        const val MIN_EXCERPT = 300
        /** 交给核对的最近几条。 */
        const val RECENT = 1
        /** 读屏、网页、别的 AI 的回答每条至多这么多字。 */
        const val READ_CHARS = 6_000
        /** 命令输出、文件内容、工具事实每条至多这么多字(按头尾截短)。 */
        const val OUTPUT_CHARS = 4_000

        private val WS = Regex("\\s+")
        private fun norm(s: String) = s.replace(WS, "")
        private val PLACE_SPLIT = Regex("[^\\p{L}\\p{N}]+")
        private val PLACE_NOISE = setOf("com", "cn", "net", "org", "www", "http", "https", "android", "app", "html", "m")

        /** App 或网址里能在题目、回答里提到的词(包名、域名的各段,太短太常见的不算)。 */
        private fun placeNames(where: String): List<String> =
            where.lowercase().split(PLACE_SPLIT).filter { it.length >= 3 && it !in PLACE_NOISE }

        /**
         * 给核查模型看的节选:[text] 里 [hits] 这几行和前后各 [context] 行,省掉的地方写一行「[省略 N 行]」;没有对上的行时
         * (或 [withHead])另给开头 [HEAD_CHARS] 字。
         * [cap] 是这一条至多多少字(按行截,截掉的同样标出)。
         */
        fun excerpt(text: String, hits: Set<Int>, context: Int, cap: Int = Int.MAX_VALUE, withHead: Boolean = false): String {
            val ls = text.lines()
            var size = 0
            val head = ls.indices.takeWhile { i -> (size < HEAD_CHARS).also { size += ls[i].length + 1 } }.toSet()
            val near = hits.flatMap { h -> (h - context)..(h + context) }.filter { it in ls.indices }.toSet()
            val keep = if (hits.isEmpty() || withHead) head + near else near
            val out = StringBuilder()
            var skipped = 0
            fun flush() { if (skipped > 0) { out.append("[省略 $skipped 行]\n"); skipped = 0 } }
            var room = cap
            for (i in ls.indices) {
                if (i !in keep || room <= 0) { skipped++; continue }
                flush()
                val l = ls[i]
                if (l.length <= room) { out.append(l).append('\n'); room -= l.length + 1 }
                else { out.append(l.take(room)).append("[本行后面省略 ${l.length - room} 字]\n"); room = 0 }
            }
            flush()
            return out.toString().trimEnd()
        }

        private fun clip(kind: ObsKind, t: String): String = when (kind) {
            ObsKind.Read, ObsKind.Reply, ObsKind.UserSaid, ObsKind.Attachment -> t.take(READ_CHARS)
            ObsKind.Output, ObsKind.Fact ->
                if (t.length <= OUTPUT_CHARS) t else t.take(OUTPUT_CHARS * 3 / 4) + "\n…(中间省略)…\n" + t.takeLast(OUTPUT_CHARS / 4)
        }
    }
}
