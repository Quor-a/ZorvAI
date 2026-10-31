package com.ai.assistance.quro.core.mika.skills

import com.ai.assistance.quro.core.mika.memory.NumberText

/**
 * 沉淀技能时去掉这一次的具体值:目标与回答里引号内的内容、2 位以上的数字串、金额;
 * 起草时另把核查引用的屏幕原文、这次输入的文字也当作具体值。中文人名不做识别——靠引号与用户过目。
 * 所有文字先统一写法(NFKC;中文数字、分隔开的数字并成一串阿拉伯数字),比较与替换按同一口径。
 */
object SkillRedactor {
    const val PLACEHOLDER = "<参数>"

    private val QUOTED = listOf("「", "」", "『", "』", "“", "”", "\"", "\"", "‘", "’", "《", "》")
        .chunked(2).map { (open, close) -> Regex(Regex.escape(open) + "([^" + Regex.escape(close) + "\\n]+)" + Regex.escape(close)) }
    private val AMOUNT = Regex("[¥$]\\s*\\d+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?\\s*(?:元|块钱|块|万元|万|美元)")
    private val DIGITS = Regex("\\d{2,}")
    private val ONLY_DIGITS = Regex("\\d+")
    private val PIECE_SPLIT = Regex("[\\s,.:;!?、。·]+")
    /** 草稿头部的这些行是标识,不扫数字(运行编号、包名);只在头部内豁免。 */
    private val KEEP_LINES = Regex("^(?:name|apps|packages|domains|learned_from|source):")

    fun norm(s: String): String = NumberText.collapse(s)

    fun literals(goal: String, criteria: List<String>): Set<String> {
        val out = LinkedHashSet<String>()
        fun quoted(t: String) = QUOTED.forEach { re -> re.findAll(t).forEach { out += it.groupValues[1].trim() } }
        val g = norm(goal)
        quoted(g)
        out += numbers(g)
        criteria.map(::norm).forEach(::quoted)
        return keep(out)
    }

    /**
     * 整段文字(核查引用的屏幕原文、输入的文字)连同其中的数字、金额,以及按空白和标点切开的各段都算具体值
     * (「王小明:明天见」里的人名单独出现也要换掉)。
     */
    fun textLiterals(texts: List<String>): Set<String> =
        keep(texts.map(::norm).flatMap { t -> listOf(t.trim()) + numbers(t) + t.split(PIECE_SPLIT) })

    /** 长的先换,免得短值把长值拆开;纯数字只换整段数字(「12」不动「12306」)。 */
    fun redact(md: String, literals: Set<String>): String {
        var out = norm(md)
        for (lit in keep(literals.map(::norm)).sortedByDescending { it.length }) {
            out = if (ONLY_DIGITS.matches(lit)) Regex("(?<!\\d)" + lit + "(?!\\d)").replace(out, PLACEHOLDER)
            else out.replace(lit, PLACEHOLDER)
        }
        return out
    }

    /**
     * 草稿全文再扫一遍:剩下的金额、2 位以上数字串一律换掉(单号、电话、价格多半是这一次的值);
     * [keepDigits] 是 App 名字里自带的数字(内置技能的名字、别名里出现的),保留。
     */
    fun scrubNumbers(md: String, keepDigits: Set<String>): String {
        val lines = norm(md).lines()
        val frontEnd = if (lines.firstOrNull()?.trimEnd() == "---") (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" } ?: -1 else -1
        return lines.mapIndexed { i, line ->
            if (i in 1 until frontEnd && KEEP_LINES.containsMatchIn(line)) line
            else DIGITS.replace(AMOUNT.replace(line) { m -> if (m.value.filter(Char::isDigit) in keepDigits) m.value else PLACEHOLDER }) { m ->
                if (m.value in keepDigits) m.value else PLACEHOLDER
            }
        }.joinToString("\n")
    }

    private fun numbers(t: String) = AMOUNT.findAll(t).map { it.value.trim() } + DIGITS.findAll(t).map { it.value }

    // 一个字的值换掉会把正文搅乱(「妈」「号」);纯数字不受此限
    private fun keep(values: Iterable<String>) =
        values.map { it.trim() }.filter { it.isNotEmpty() && (it.length >= 2 || ONLY_DIGITS.matches(it)) }.toSet()
}
