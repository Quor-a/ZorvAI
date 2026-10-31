package com.ai.assistance.quro.core.mika.verify

/**
 * 代码兜底:核查器判 pass 之后,用一条与模型无关的规则再对一遍文字证据。代码只核它核得准的——字面上有没有,
 * 不判断一个值是不是属于回答说的那个东西(那是核查模型的事,见 verifier 提示词)。回答正文(「依据:」之前)里:
 * - 引号里的原文(「…」『…』“…”"…"‘…’,题目里本来就有的除外)要在某一处材料里找到;
 *   引号里用一个字母代替回答自己写过的数(「今日 +N」指正文里的「今日 +2,271」),是在说回答自己的写法,不是屏上的字;
 * - 带单位、货币、百分号或数量级(万/亿/千)的数,小数,三位以上的整数,夹在字母里的编号,要按整个词在某处材料里找到;
 * - 日期和时刻(10月23日、2026-10-23、14:05)同样要找到。
 * 「依据:」里只核引文:每段引文都要在某一处材料里找到(引文里的 … 把它分成几段,各段都要在同一处);
 * 依据里其余的字(来源、网址、读取时间、叙述里的计数)不是要核对的事实,对上了可以帮着定位,对不上也不否决。
 * 两位以内、不带单位的整数无论在哪都不要求。题目里的数和原文视为已知。
 * 另外两种来源也算已知,各自只证它证得了的:题目或证据里写了「明天」「周二」,按运行时钟换算出的那一天;
 * 回答里写明的算式(a 运算符 b = c,a、b 都在证据里对上)的结果,和明说是约数(约、≈)的取整。
 * 比对前两边统一写法:全角半角(NFKC)、零宽字符、引用编号与脚注、千分位、万/亿折算、标点与空白;两边都按行比,
 * 回答的一行不和下一行拼起来(行首的列表编号按行去掉),材料不拼成一整串;「当前 App:」这一行不算屏上文字。
 * 引用编号的边界用显式的前后断言,不用 \b:Java 的 \b 把汉字当作单词字符,「@e46显示」里的 46 会被当成回答里的数。
 */
internal object Grounding {
    private val QUOTED = Regex("「([^」]*)」|『([^』]*)』|“([^”]*)”|\"([^\"]*)\"|‘([^’]*)’")
    private val EVIDENCE = Regex("依据\\**\\s*[:：]")
    /**
     * 数后面的亿/万/千是数量级(中间只能隔空白,App 常排成「1561 万热度」;隔了标点就不是);
     * 「千米」「千克」「千瓦」里的千是单位的一部分,不是数量级。行首、引文开头的序号(「5 千与千寻」)先按 [ORDINAL] 去掉。
     */
    private val NUMBER = Regex("(?<!\\d)(?<!\\d[.,])\\d+(?:[.,]\\d+)*(?!\\d)([ \\t]*(?:[亿万]|千(?![米克瓦卡帕焦])))?")
    private val THOUSANDS = Regex("^\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?$")
    private val DIGITS = Regex("\\d+")
    /** 紧跟在数后面、说明它是个量的字(序号后面不会是它们):单位、数量级、量词。 */
    private const val UNIT_WORDS = "公里|千米|米|厘米|毫米|公斤|千克|斤|克|吨|元|块|角|小时|分钟|秒|岁|度|分|人|次|个|条|件|万|亿|千[米克瓦卡帕焦]|[A-Za-z%℃°]"

    /** 序号不是要核对的数:第N条/步/名…、前N个/条…、Top N、No.N、#N、行首的列表编号(1. 2、 3))。「第2026届」是值,不算。 */
    private val ORDINAL = Regex(
        "第[ \\t]*\\d+[ \\t]*(?=[条个集页项张篇步名位行章节轮次题])|前[ \\t]*\\d+[ \\t]*(?=[个条名位项篇张])|(?i:top)[ \\t]*\\d+|(?i:no)\\.[ \\t]*\\d+|#\\d+" +
            "|(?m)^[ \\t]*\\d+[ \\t]*[.、)）](?!\\d)" +
            // 行首或引文开头的序号后面隔个空格就是名字(「5 千与千寻…」):不是值
            "|(?m)(?:^|(?<=[「『“\"‘]))[ \\t]*\\d{1,3}[ \\t]*[.、](?!\\d)|(?m)(?:^|(?<=[「『“\"‘]))[ \\t]*\\d{1,3}[ \\t]+(?=\\p{L})(?!$UNIT_WORDS)",
    )
    /** 日期和时刻;用 . 分隔的只认年.月.日三段,免得把 1561.5 当成日期。 */
    private val DATE = Regex(
        "(?<!\\d)(?:\\d{4}\\s*[-/年]\\s*\\d{1,2}(?:\\s*[-/月]\\s*\\d{1,2}\\s*[日号]?)?|\\d{4}\\.\\d{1,2}\\.\\d{1,2}" +
            "|\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]?|\\d{1,2}\\s*:\\s*\\d{2}(?:\\s*:\\s*\\d{2})?)(?!\\d)",
    )
    /** 紧跟在数后面、说明它是个量值的单位(计数用的量词「条」「个」不算:那是回答自己数出来的)。 */
    private val UNIT_AFTER = Regex(
        "^\\s*(?:%|‰|元|块|角|美元|欧元|日元|港元|英镑|℃|°|度|公里|千米|米|厘米|毫米|公斤|千克|斤|克|吨|小时|分钟|秒|毫秒|岁|英寸|寸|折|星" +
            "|(?:km|cm|mm|kg|mAh|GB|MB|KB|TB|Hz|ms|[kKwWmgGM])(?![A-Za-z]))",
    )
    private val CURRENCY_BEFORE = Regex("[¥$€£]\\s*$")
    private val ZERO_WIDTH = Regex("[\\u200B\\u200C\\u200D\\uFEFF\\u2060]")
    /** 回答里的强调与代码标记(**粗**、__粗__、~~删~~、`码`)只是排版,不是内容;单个 * 可能是乘号,不动。 */
    private val MARKUP = Regex("\\*\\*|__|~~|`")
    private val FRAGMENT_BREAK = Regex("[^\\p{L}]+")
    private val PIECE_BREAK = Regex("[^\\p{L}\\p{N}]+")
    /**
     * 不同模型引用的写法:元素编号(@e16、@v2,可能套在括号里)、方括号脚注([1]、[^2]、【3】)。
     * 不是回答的内容,比对前去掉。边界用前后断言:后面紧跟汉字(@e46显示)也要认出来。
     */
    private val REF_CITATION = Regex(
        "[(\\[【]\\s*@[ev]\\d+(?:\\s*[,、]\\s*@[ev]\\d+)*\\s*[)\\]】]|(?<![A-Za-z0-9_])@[ev]\\d+(?!\\d)",
    )
    private val FOOTNOTE = Regex("\\[\\^?\\d{1,3}]|【\\d{1,3}】")
    private val MAGNITUDE = mapOf("亿" to 8, "万" to 4, "千" to 3)
    const val APP_HEADER = "当前 App:"
    /** 依据里的文字片段太短会碰巧和任何屏幕都对得上(「屏上」「设置」),不算。 */
    private const val MIN_FRAGMENT = 4
    /** 屏上的一行出现在依据里时,这一行至少要有几个字。 */
    private const val MIN_LINE_IN_BASIS = 2

    private data class Num(val value: String, val magnitude: String) {
        /** 带单位折算后的值(5.3万 与 53000 相同);算不出为 null。 */
        val scaled: String? = value.toBigDecimalOrNull()?.let { v ->
            (MAGNITUDE[magnitude]?.let { v.movePointRight(it) } ?: v).stripTrailingZeros().toPlainString()
        }
        fun matches(screen: Num) = (value == screen.value && (magnitude.isEmpty() || magnitude == screen.magnitude)) ||
            (scaled != null && scaled == screen.scaled)
    }

    /** [Token]:夹在英文字母里的编号(G7541、a3f899c),整个按一个词比,不按数比。[Quote] 含依据里的引文。 */
    enum class Kind { Quote, Number, Date, Token }

    /** 回答里的一条具体事实。数和日期是能写成文字的值;不带数字的引文才可能只在画面上(如单选框选中的状态)。 */
    data class Claim(val kind: Kind, val text: String) {
        val visualOnly: Boolean get() = kind == Kind.Quote && text.none(Char::isDigit)
    }

    /**
     * 兜底的结果。[unmatched] 是在哪个证据来源里都找不到的事实;[anchored] 表示至少有一条事实或依据里的一处对上了。
     * [found]:对得上 true,对不上 false,回答里没有可核对的东西(或只有数对上、没有引文或依据定位)null。
     * [items] 是回答里有据可查的条目数(评测的 answer_grounded 用,见 [check])。
     */
    class Result(
        val found: Boolean?, val claims: List<Claim>, val unmatched: List<Claim>, val anchored: Boolean, val located: Boolean = false, val items: Int = 0,
    )

    /** NFKC 之后 … 已是 ...。 */
    private const val CUT = "..."
    /** 回答里紧挨着 ... 的那段字(至少几个字才拿去比)。 */
    private val BEFORE_CUT = Regex("([^\\n.「」『』“”\"]{2,20})\\.\\.\\.")

    private fun isCut(line: String) = line.trimEnd().endsWith(CUT)

    /**
     * 回答是不是建立在被截断的文字上:回答里紧挨着 ... 的那段字、引号里的原文(去掉首尾的 ...),在屏上只出现在被截断的行里,
     * 而且一直连到截断处。截断的文字只是值的一部分,不能当完整的值核实。
     * 只看屏上的字是否被截断:回答自己用 ... 省略引文的后半句,而屏上那一行是完整的,不算截断。
     */
    fun builtOnTruncated(answer: String, screens: List<String>): Boolean {
        val a = canon(answer)
        val raw = screens.flatMap { canon(it).lines() }.filter { it.isNotBlank() && !it.startsWith(APP_HEADER) }
        fun onlyOnCutLines(piece: String): Boolean {
            val p = norm(piece)
            if (p.length < 2) return false
            val hits = raw.filter { p in norm(it) }
            return hits.isNotEmpty() && hits.all { l -> isCut(l) && norm(l.trimEnd().removeSuffix(CUT)).endsWith(p) }
        }
        // 引文以 ... 开头(屏上那一行前半截被截掉):这段字只出现在以 ... 开头的行里
        fun onlyOnHeadCutLines(piece: String): Boolean {
            val p = norm(piece)
            if (p.length < 2) return false
            val hits = raw.filter { p in norm(it) }
            return hits.isNotEmpty() && hits.all { l -> l.trimStart().startsWith(CUT) && norm(l.trimStart().removePrefix(CUT)).startsWith(p) }
        }
        val quoted = quotes(a).map(String::trim)
        if (quoted.any { q -> q.endsWith(CUT) && onlyOnCutLines(q.removeSuffix(CUT)) }) return true
        if (quoted.any { q -> q.startsWith(CUT) && onlyOnHeadCutLines(q.removePrefix(CUT).removeSuffix(CUT)) }) return true
        if (quoted.any(::onlyOnCutLines)) return true
        // 紧挨着 ... 的字前面可能连着回答自己的话(「要求 Kotlin + Jetpack...」):取在屏上出现过的最长的那段尾巴来比
        return BEFORE_CUT.findAll(a).any { m ->
            val cap = m.groupValues[1]
            val tail = cap.indices.map { cap.substring(it) }.firstOrNull { t -> norm(t).length >= 2 && raw.any { norm(t) in norm(it) } }
            tail != null && onlyOnCutLines(tail)
        }
    }

    /** 去掉引用编号与脚注(不是回答的内容)。 */
    fun stripRefs(s: String): String = s.replace(REF_CITATION, " ").replace(FOOTNOTE, " ")

    /** 两边比对前统一写法:全角半角(NFKC)、零宽字符、排版标记、引用编号与脚注。 */
    private fun canon(s: String): String =
        stripRefs(java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).replace(ZERO_WIDTH, "").replace(MARKUP, ""))

    private fun norm(s: String) = s.filter { it.isLetterOrDigit() }

    private fun quotes(s: String) = QUOTED.findAll(s).map { m -> m.groupValues.drop(1).first { it.isNotEmpty() } }.toList()

    private fun num(m: MatchResult): Num {
        val raw = m.value.substring(0, m.value.length - m.groupValues[1].length)
        return Num(if (THOUSANDS.matches(raw)) raw.replace(",", "") else raw, m.groupValues[1].trim())
    }

    private fun numbers(s: String): List<Num> = NUMBER.findAll(s).map(::num).toList()

    /**
     * [s] 里算作事实的数:带数量级、单位或货币符号的,小数,三位以上的整数。
     * 两位以内不带单位的整数是计数、序号、排名,不算。
     */
    private fun valueNumbers(s: String, known: Set<String>): List<Num> = NUMBER.findAll(s).mapNotNull { m ->
        val n = num(m)
        if (n.scaled != null && n.scaled in known) return@mapNotNull null
        val after = s.substring(m.range.last + 1, minOf(s.length, m.range.last + 9))
        val unit = n.magnitude.isNotEmpty() || UNIT_AFTER.containsMatchIn(after) || CURRENCY_BEFORE.containsMatchIn(s.substring(0, m.range.first))
        val decimal = '.' in n.value
        val long = n.value.substringBefore('.').substringBefore(',').length >= 3
        n.takeIf { unit || decimal || long }
    }.toList()

    private fun components(s: String) = DIGITS.findAll(s).map { it.value.trimStart('0').ifEmpty { "0" } }.toList()

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isAsciiAlnum() = isAsciiLetter() || this in '0'..'9'

    /**
     * 屏上 [s] 里 [start] 到 [end](不含)这个数是不是完整的一个词:前一个字不能是英文字母或数字;后面紧跟英文字母时,
     * 只能是一串字母就结束(单位,如 128GB),不能再接数字。哈希、网址里的编号、a3f899c 这类 id 里的数字不算屏上的数。
     * 数里面的小数点、千分位不影响。
     */
    private fun wholeToken(s: String, start: Int, end: Int): Boolean {
        if (s.getOrNull(start - 1)?.isAsciiAlnum() == true) return false
        var i = end
        while (i < s.length && s[i].isAsciiLetter()) i++
        return i == end || i == s.length || s[i] !in '0'..'9'
    }

    /**
     * [text] 里哪几行(按 [text] 的行号)对上了回答里的事实:给核查模型看节选时,这些行一定留下([EvidenceLedger.excerpt])。不是核对规则。
     */
    fun hitLines(goal: String, answer: String, text: String, today: java.time.LocalDate? = null): Set<Int> {
        val r = check(goal, answer, listOf(text), today)
        val ok = r.claims.filter { it !in r.unmatched }
        if (ok.isEmpty()) return emptySet()
        val pieces = ok.filter { it.kind == Kind.Quote }.flatMap { q -> q.text.split(CUT).flatMap { it.split(PIECE_BREAK) } }.map(::norm).filter { it.length >= 2 }
        val raw = canon(text).lines()
        return raw.indices.filter { i ->
            val l = raw[i]
            if (l.isBlank() || l.startsWith(APP_HEADER)) return@filter false
            val line = lines(listOf(l)).single()
            pieces.any { it in line.text } || ok.any { c ->
                when (c.kind) {
                    Kind.Quote -> false
                    Kind.Number -> numbers(c.text).firstOrNull()?.let { n -> line.nums.any(n::matches) } == true
                    Kind.Date -> components(c.text).let { v -> v.isNotEmpty() && line.digits.windowed(v.size).any { it == v } } ||
                        components(c.text).drop(1).let { v -> v.size == 2 && line.digits.windowed(2).any { it == v } }
                    Kind.Token -> c.text.lowercase() in line.tokens
                }
            }
        }.toSet()
    }

    /** 屏上一行里完整的数(见 [wholeToken])。 */
    private fun screenNumbers(s: String): List<Num> = NUMBER.findAll(s).mapNotNull { m ->
        val n = num(m)
        n.takeIf { wholeToken(s, m.range.first, m.range.first + m.value.length - m.groupValues[1].length) }
    }.toList()

    /** 屏上一行里完整的数字串(对日期用)。 */
    private fun screenComponents(s: String) = DIGITS.findAll(s).filter { wholeToken(s, it.range.first, it.range.last + 1) }
        .map { it.value.trimStart('0').ifEmpty { "0" } }.toList()

    private class Line(val text: String, val nums: List<Num>, val digits: List<String>, val tokens: Set<String>)

    private val ALNUM_RUN = Regex("[A-Za-z0-9]+")

    /** 一行里的英文字母数字串(比编号用,不分大小写)。 */
    private fun alnumRuns(s: String) = ALNUM_RUN.findAll(s).map { it.value.lowercase() }.toSet()

    /**
     * [s] 里夹在英文字母里的编号(数不是完整的词,见 [wholeToken]),和把它们换成等长空白后的文字:
     * 编号整个当一个值核对,里面的数字不再当数。
     */
    private fun idTokens(s: String): Pair<List<String>, String> {
        val ids = NUMBER.findAll(s).filterNot { m -> wholeToken(s, m.range.first, m.range.first + m.value.length - m.groupValues[1].length) }
            .mapNotNull { m ->
                var a = m.range.first
                var b = m.range.last + 1
                while (a > 0 && s[a - 1].isAsciiAlnum()) a--
                while (b < s.length && s[b].isAsciiAlnum()) b++
                a until b
            }.distinct().toList()
        var t = s
        ids.forEach { r -> t = t.replaceRange(r, " ".repeat(r.last - r.first + 1)) }
        return ids.map { s.substring(it) } to t
    }

    private fun lines(screens: List<String>) = screens.flatMap { canon(it).lines() }
        .filter { it.isNotBlank() && !it.startsWith(APP_HEADER) }.map { Line(norm(it), screenNumbers(it), screenComponents(it), alnumRuns(it)) }

    /** 引文(可能跨几行)里至少有一行能在屏上的某一行里找到。 */
    fun quoteInReads(quote: String, screens: List<String>): Boolean {
        val ls = lines(screens)
        return canon(quote).lines().map(::norm).filter { it.length >= 2 }.any { q -> ls.any { q in it.text } }
    }

    /** 对得上返回 true,对不上返回 false;回答里没有可核对的东西时返回 null。 */
    fun found(goal: String, answer: String, screens: List<String>): Boolean? = check(goal, answer, screens).found

    /** 相对的日子:今天、明天、后天、昨天,周X / 星期X。 */
    private val RELATIVE_DAY = Regex("今天|今日|明天|明日|后天|昨天|昨日|(?:周|星期|礼拜)([一二三四五六日天])")
    private val WEEKDAY_CHARS = "一二三四五六日"
    /** 周X 换算成具体日期时看的范围:昨天到一周后。 */
    private const val WEEK_AHEAD = 7L

    /** [word] 按 [today] 换算出的日期(周X 在昨天到一周后之间可能有两个)。 */
    private fun resolve(word: MatchResult, today: java.time.LocalDate): List<java.time.LocalDate> = when (word.value) {
        "今天", "今日" -> listOf(today)
        "明天", "明日" -> listOf(today.plusDays(1))
        "后天" -> listOf(today.plusDays(2))
        "昨天", "昨日" -> listOf(today.minusDays(1))
        else -> {
            val c = word.groupValues[1].replace("天", "日")
            val dow = java.time.DayOfWeek.of(WEEKDAY_CHARS.indexOf(c) + 1)
            (-1L..WEEK_AHEAD).map(today::plusDays).filter { it.dayOfWeek == dow }
        }
    }

    /** 日期写法里的年、月、日;只有年月、或是时刻时为 null。 */
    private fun dateParts(d: String): Triple<Int?, Int, Int>? {
        if (':' in d) return null
        val c = DIGITS.findAll(d).map { it.value.toInt() }.toList()
        return when {
            c.size == 3 && c[0] >= 1000 -> Triple(c[0], c[1], c[2])
            c.size == 2 && !Regex("^\\d{4}").containsMatchIn(d.trim()) -> Triple(null, c[0], c[1])
            else -> null
        }
    }

    /**
     * 运行时钟能作证的日期:题目或证据里的某一行写了「明天」「周二」这类说法,按 [today] 换算出的那一天,
     * 回答正文(引文和「依据:」以外)写成具体日期时由时钟作证。只出现在回答里的相对说法什么也不证;时刻、引文里的日期、
     * 依据里的日期(回答说是从页面读到的)都不由时钟作证。
     */
    private fun clockDates(body: String, basis: String, said: List<String>, today: java.time.LocalDate?): Set<String> {
        today ?: return emptySet()
        val meant = said.flatMap { t -> RELATIVE_DAY.findAll(t).flatMap { resolve(it, today) }.toList() }.toSet()
        if (meant.isEmpty()) return emptySet()
        val plain = QUOTED.replace(body) { " ".repeat(it.value.length) }
        val quotedOrRead = quotes(body).joinToString("\n") + "\n" + basis
        return plain.lines().flatMap { DATE.findAll(it).toList() }.mapNotNull { m ->
            val text = m.value.trim()
            val (y, mo, d) = dateParts(text) ?: return@mapNotNull null
            if (DATE.findAll(quotedOrRead).any { it.value.trim() == text }) return@mapNotNull null
            text.takeIf { meant.any { it.monthValue == mo && it.dayOfMonth == d && (y == null || it.year == y) } }
        }.toSet()
    }

    private const val NUM_TOKEN = "(\\d+(?:,\\d{3})*(?:\\.\\d+)?)"
    /** 一个操作数:可带货币符号、紧跟的单位(字母、℃、°、%),和括号里写的出处(「399(商品页)」)。 */
    private const val OPERAND = "([¥$€£]?)\\s*$NUM_TOKEN\\s*([\\p{L}℃°%]{0,4})\\s*(?:[(（][^()（）\\n]{1,20}[)）])?\\s*"
    /**
     * 回答里写明的一步运算:a 运算符 b = c(或 ≈ c,= 约 c);运算符是 + - − × * ÷ /。c 后面带 % 时,÷ 和 / 的结果按百分数比。
     */
    private val EXPRESSION = Regex(
        OPERAND + "([+\\-−×*÷/])\\s*" + OPERAND + "(=|≈)\\s*(约|≈)?\\s*([¥$€£]?)\\s*$NUM_TOKEN(\\s*%)?\\s*([\\p{L}℃°]{0,4})",
    )

    /** 一个标准单位:量纲和换算到基本单位的倍数。 */
    private class Unit(val dimension: String, val factor: java.math.BigDecimal)

    /**
     * 标准单位的同义写法和换算(封闭的一张表,不做别的猜测):公里/千米/km,米/m,元/块/¥/RMB/人民币,克/g,千克/公斤/kg,
     * 小时/h,分钟/min,%/百分之。最长的写法先认。
     */
    private val UNITS: List<Pair<String, Unit>> = listOf(
        "公里" to Unit("length", java.math.BigDecimal(1000)), "千米" to Unit("length", java.math.BigDecimal(1000)), "km" to Unit("length", java.math.BigDecimal(1000)),
        "米" to Unit("length", java.math.BigDecimal.ONE), "m" to Unit("length", java.math.BigDecimal.ONE),
        "人民币" to Unit("cny", java.math.BigDecimal.ONE), "RMB" to Unit("cny", java.math.BigDecimal.ONE), "元" to Unit("cny", java.math.BigDecimal.ONE),
        "块" to Unit("cny", java.math.BigDecimal.ONE), "¥" to Unit("cny", java.math.BigDecimal.ONE),
        "千克" to Unit("mass", java.math.BigDecimal(1000)), "公斤" to Unit("mass", java.math.BigDecimal(1000)), "kg" to Unit("mass", java.math.BigDecimal(1000)),
        "克" to Unit("mass", java.math.BigDecimal.ONE), "g" to Unit("mass", java.math.BigDecimal.ONE),
        "小时" to Unit("time", java.math.BigDecimal(3600)), "h" to Unit("time", java.math.BigDecimal(3600)),
        "分钟" to Unit("time", java.math.BigDecimal(60)), "min" to Unit("time", java.math.BigDecimal(60)),
        "百分之" to Unit("percent", java.math.BigDecimal.ONE), "%" to Unit("percent", java.math.BigDecimal.ONE),
    ).sortedByDescending { it.first.length }

    /** 货币符号和数后面的字里认出的标准单位;认不出为 null(按没写单位算)。 */
    private fun unitOf(before: String, after: String): Unit? =
        UNITS.firstOrNull { (k, _) -> before == k }?.second ?: UNITS.firstOrNull { (k, _) -> after.startsWith(k, ignoreCase = k.all { it.isAsciiLetter() }) && !after.drop(k.length).take(1).any { it.isAsciiLetter() } }?.second

    /** 算式里操作数的单位:表里有的按标准单位;写了表里没有的(美元符号、英里……)各算一种量纲,只和自己同类。 */
    private fun operandUnit(before: String, after: String): Unit? =
        unitOf(before, after) ?: (before + after.trim()).takeIf { it.isNotBlank() }?.let { Unit("other:" + it.lowercase(), java.math.BigDecimal.ONE) }
    /** 紧挨在数前面、明说是约数的记号。 */
    private val APPROX_BEFORE = Regex("(?:约|≈)\\s*[¥$€£]?\\s*$")

    /**
     * [x] 是 [d] 取整的结果:不是约数时只认按 [x] 写出的最后一位取整(整数要相等,小数按小数位);
     * 明说是约数(约、≈)时末尾的 0 也算精度(900 取到百位),且相对误差不超过 [MAX_ROUND_ERROR]。
     */
    private fun roundsTo(x: java.math.BigDecimal, d: java.math.BigDecimal, approx: Boolean): Boolean {
        val diff = (x - d).abs()
        if (diff.signum() == 0) return true
        val power = when {
            x.scale() > 0 -> -x.scale()
            approx -> minOf(-x.stripTrailingZeros().scale(), MAX_ROUND_POWER).coerceAtLeast(0)
            else -> 0
        }
        val half = java.math.BigDecimal.ONE.scaleByPowerOfTen(power).divide(java.math.BigDecimal(2))
        if (diff > half) return false
        return !approx || (d.signum() != 0 && diff.divide(d.abs(), java.math.MathContext.DECIMAL64) <= MAX_ROUND_ERROR)
    }

    private const val MAX_ROUND_POWER = 6
    private val MAX_ROUND_ERROR = java.math.BigDecimal("0.05")
    private val HUNDRED = java.math.BigDecimal(100)

    private fun plainValue(s: String) = s.replace(",", "").toBigDecimalOrNull()

    /**
     * 回答里写明了算式、两个操作数都在证据里对上的结果(「6363−5464=899」):返回结果的写法(和回答里的数的写法一致)。
     * [grounded] 判断一个数在证据里对得上(不含题目里的数)。
     */
    private fun computed(body: String, grounded: (String) -> Boolean): Set<String> = body.lines().flatMap { EXPRESSION.findAll(it).toList() }.mapNotNull { m ->
        val g = m.groupValues
        val (aCur, aRaw, aUnit, op) = listOf(g[1], g[2], g[3], g[4])
        val (bCur, bRaw, bUnit, eq) = listOf(g[5], g[6], g[7], g[8])
        val (approxWord, cCur, cRaw, pct) = listOf(g[9], g[10], g[11], g[12])
        val cUnitText = g[13]
        if (!grounded(aRaw) || !grounded(bRaw)) return@mapNotNull null
        var a = plainValue(aRaw) ?: return@mapNotNull null
        var b = plainValue(bRaw) ?: return@mapNotNull null
        var c = plainValue(cRaw) ?: return@mapNotNull null
        val mc = java.math.MathContext.DECIMAL64
        // 加减要同一个量纲:写了单位的按标准单位换算(公里和千米是一回事,公里和米差 1000 倍);量纲不同的算式不算数
        if (op in setOf("+", "-", "−")) {
            val ua = operandUnit(aCur, aUnit)
            val ub = operandUnit(bCur, bUnit)
            val uc = unitOf(cCur, if (pct.isNotBlank()) "%" else cUnitText)
            val units = listOfNotNull(ua, ub, uc)
            if (units.map { it.dimension }.distinct().size > 1) return@mapNotNull null
            val base = ua ?: ub ?: uc
            if (base != null) {
                a = a * (ua ?: base).factor; b = b * (ub ?: base).factor
                c = c * (uc ?: ua ?: base).factor
            }
        }
        val r = when (op) {
            "+" -> a + b
            "-", "−" -> a - b
            "×", "*" -> a * b
            else -> if (b.signum() == 0) return@mapNotNull null else a.divide(b, mc)
        }
        val result = if (pct.isNotBlank() && (op == "÷" || op == "/")) r * HUNDRED else r
        val approx = eq == "≈" || approxWord.isNotEmpty()
        cRaw.replace(",", "").takeIf { roundsTo(c, result, approx) }
    }.toSet()

    /** 引文里代替数的一个字母(「今日 +N」的 N)。 */
    private val PLACEHOLDER = Regex("(?<![A-Za-z])[A-Za-z](?![A-Za-z])")

    /**
     * 引文 [q] 是在说回答自己的写法:里面有代替数的字母,把字母换成数后,正好是回答正文(引号以外)里写过的一段(「今日 +N」对「今日 +2,271」)。
     */
    private fun template(q: String, plain: String): Boolean {
        if (!PLACEHOLDER.containsMatchIn(q)) return false
        val pattern = q.trim().split(Regex("\\s+")).joinToString("\\s*") { w ->
            PLACEHOLDER.split(w).joinToString("\\d+(?:[.,]\\d+)*") { Regex.escape(it) }
        }
        return Regex(pattern).containsMatchIn(plain)
    }

    private val EMPHASIS = Regex("\\*\\*|__|`")
    /** 行首的列表记号(1. 2、 3) ① - * •)。 */
    private val LIST_MARK = Regex("^[ \\t]*(?:\\(?\\d{1,3}[.、)）:：]|（\\d{1,3}）|[①-⑳]|[-*•·])[ \\t]*")
    /** 列表里「标签:值」的值。 */
    private val LABEL_VALUE = Regex("^[^:：]{1,12}[:：]\\s*(.+)$")
    /** 一个条目至少要有几个字(单字、标点哪屏都有,不能拿来凑数)。 */
    private const val MIN_ITEM = 2

    fun check(goal: String, answer: String, screens: List<String>, today: java.time.LocalDate? = null): Result {
        val a = canon(answer)
        val entries = screens.map { lines(listOf(it)) }
        val ls = entries.flatten()
        val g = canon(goal)
        val goalNums = numbers(g).map { it.value }.toSet()
        // 题目里的数按值比(1000.0 和 1000 是同一个数)
        val goalValues = numbers(g).mapNotNull { it.scaled }.toSet()
        val goalText = norm(g)
        val at = EVIDENCE.find(a)
        val basis = at?.let { a.substring(it.range.last + 1) }.orEmpty()
        val body = at?.let { a.substring(0, it.range.first) } ?: a

        fun numOnScreen(n: Num) = ls.any { l -> l.nums.any(n::matches) }
        /** [q] 在 [where] 这些行里:整段在某一行,或按标点空白分开的每段各在某一行(其中的数按整个数比)。 */
        fun textIn(q: String, where: List<Line>): Boolean {
            val t = norm(q)
            if (t.isEmpty() || where.any { t in it.text }) return true
            val pieces = q.split(PIECE_BREAK).map(::norm).filter(String::isNotEmpty)
            // 行首的序号(「5 千与千寻」)、夹在字母里的数字(AQ3A)不是数,不拿去比
            return pieces.size > 1 && pieces.all { p -> where.any { p in it.text } } &&
                screenNumbers(ORDINAL.replace(q.trim(), " ")).all { n -> where.any { l -> l.nums.any(n::matches) } }
        }
        /** 引文在某一处材料里:被 ... 分开的几段各自对得上,而且在同一处。 */
        fun quoteFound(q: String): Boolean {
            val parts = q.split(CUT).filter { norm(it).isNotEmpty() }.ifEmpty { listOf(q) }
            return entries.any { e -> parts.all { textIn(it, e) } }
        }
        fun dateOnScreen(d: String): Boolean {
            val c = components(d)
            val variants = listOfNotNull(c, c.takeIf { it.size == 3 && it[0].length == 4 }?.drop(1))
            return variants.any { v -> ls.any { l -> l.digits.windowed(v.size).any { it == v } } }
        }

        // 一行一行地取值:回答的一行不和下一行拼起来,行首的列表编号按行去掉
        fun values(line: String): List<Claim> {
            val dates = DATE.findAll(line).map { it.value }.filter { d -> components(d).any { it !in goalNums } }.toList()
            val (ids, plain) = idTokens(ORDINAL.replace(DATE.replace(line, " "), " "))
            return dates.map { Claim(Kind.Date, it.trim()) } + ids.filter { norm(it) !in goalText }.map { Claim(Kind.Token, it) } +
                valueNumbers(plain, goalValues).map { Claim(Kind.Number, it.value + it.magnitude) }
        }
        val plainBody = QUOTED.replace(body, " ")
        val bodyQuotes = quotes(body).filter { norm(it).length >= 2 && norm(it) !in goalText && !template(it, plainBody) }
        val basisQuotes = quotes(basis).filter { norm(it).length >= 2 }
        val bodyLines = body.lines().map { it.replace(EMPHASIS, "") }
        val lineClaims = bodyLines.map(::values)
        val claims = (bodyQuotes.map { Claim(Kind.Quote, it) } + lineClaims.flatten() + basisQuotes.map { Claim(Kind.Quote, it) }).distinct()

        // 证据里写了「明天」「周二」的地方:题目、证据里的每一行
        val byClock = clockDates(body, basis, listOf(g) + ls.map { it.text }, today)
        fun matched(c: Claim) = when (c.kind) {
            Kind.Quote -> quoteFound(c.text)
            Kind.Number -> numbers(c.text).firstOrNull()?.let(::numOnScreen) ?: false
            Kind.Date -> dateOnScreen(c.text) || c.text in byClock
            Kind.Token -> ls.any { c.text.lowercase() in it.tokens }
        }
        // 证据里对得上的数(题目里的数不算:题目给的数算出来的结果仍要在屏上读到)
        fun grounded(n: Num) = (n.scaled == null || n.scaled !in goalValues) && numOnScreen(n)
        fun groundedRaw(raw: String) = numbers(raw).firstOrNull()?.let(::grounded) == true
        val known = a.lines().flatMap { numbers(ORDINAL.replace(DATE.replace(it, " "), " ")) }.filter(::grounded).mapNotNull { it.scaled?.toBigDecimalOrNull() }
        // 写明算式的结果,和明说是约数(约、≈)、按写出的精度取整后等于某个对得上的数的数
        val derivedNums = computed(body, ::groundedRaw) + body.lines().flatMap { line ->
            NUMBER.findAll(line).mapNotNull { m ->
                val n = num(m)
                val x = n.scaled?.toBigDecimalOrNull() ?: return@mapNotNull null
                if (!APPROX_BEFORE.containsMatchIn(line.substring(0, m.range.first))) return@mapNotNull null
                (n.value + n.magnitude).takeIf { known.any { roundsTo(x, it, approx = true) } }
            }.toList()
        }
        val unmatched = claims.filterNot { matched(it) || (it.kind == Kind.Number && it.text in derivedNums) }

        // 依据里引文以外的字只帮着定位(对上一处即可),对不上不否决
        val basisNorm = norm(basis)
        val fragments = QUOTED.replace(basis, " ").split(FRAGMENT_BREAK).map(::norm).filter { it.length >= MIN_FRAGMENT }
        val located = (basisQuotes.isNotEmpty() && basisQuotes.all(::quoteFound)) || fragments.any { f -> ls.any { f in it.text } } ||
            ls.any { it.text.length >= MIN_LINE_IN_BASIS && it.text in basisNorm }

        val quoteMatched = claims.any { it.kind == Kind.Quote && it !in unmatched }
        val found = when {
            unmatched.isNotEmpty() -> false
            quoteMatched || located -> true
            // 没有引文、依据也没定位到:只有数对上,数可能碰巧在屏上,单靠它们不算对上
            else -> null
        }

        // 有据可查的条目:回答里有对上的事实的行(内容相同的行只算一次),和不带值的列表条目里整段在某一行里的;
        // 取它和对上的不同事实数里大的那个
        val okClaims = claims.filter { it !in unmatched }.toSet()
        val rows = mutableSetOf<String>()
        val bare = mutableSetOf<String>()
        bodyLines.forEachIndexed { i, raw ->
            val key = norm(raw)
            if (key.isEmpty()) return@forEachIndexed
            val inLine = lineClaims[i] + quotes(raw).map { Claim(Kind.Quote, it) }
            if (inLine.any { it in okClaims }) { rows += key; return@forEachIndexed }
            if (inLine.isNotEmpty() || !LIST_MARK.containsMatchIn(raw)) return@forEachIndexed
            val item = raw.replace(LIST_MARK, "").trim().let { t -> LABEL_VALUE.find(t)?.groupValues?.get(1) ?: t }
            if (norm(item).length >= MIN_ITEM && norm(item) !in goalText && textIn(item, ls)) bare += norm(item)
        }
        val okInBody = (bodyQuotes.map { Claim(Kind.Quote, it) } + lineClaims.flatten()).filter { it in okClaims }.toSet()
        val items = maxOf(rows.size, okInBody.size) + bare.size
        return Result(found, claims, unmatched, anchored = located || claims.size > unmatched.size, located = located, items = items)
    }
}
