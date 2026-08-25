package com.ai.assistance.quro.workflow.executor

/**
 * 极简表达式与变量引擎（无第三方依赖）。
 *
 *  - substitute：把 ${name} / ${name:默认值} 替换为变量值。
 *  - eval：求布尔表达式，支持 == != > < >= <= 以及 && || ! 与括号，
 *          操作数为数字 / 引号字符串 / 标识符（解析为变量值）/ true / false。
 *
 * 用于 CONDITION 分支判断、LOOP 的 while 条件，以及动作参数的变量替换。
 */
object Expression {

    fun substitute(text: String, vars: Map<String, String>): String {
        if (!text.contains("\$")) return text
        val re = Regex("""\$\{([a-zA-Z_][a-zA-Z0-9_]*)(?::([^}]*))?\}""")
        return re.replace(text) { m ->
            val name = m.groupValues[1]
            val def = m.groupValues[2]
            vars[name] ?: if (def.isNotEmpty()) def else ""
        }
    }

    fun eval(expr: String, vars: Map<String, String>): Boolean {
        val e = substitute(expr, vars)
        if (e.isBlank()) return false
        val toks = Tokenizer(e).tokenize()
        if (toks.isEmpty()) return false
        return try {
            Parser(toks, vars).parseOr()
        } catch (_: Exception) {
            false
        }
    }

    private class Tokenizer(private val s: String) {
        data class Tk(val type: String, val text: String)

        fun tokenize(): List<Tk> {
            val out = mutableListOf<Tk>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> i++
                    c == '"' || c == '\'' -> {
                        val q = c
                        i++
                        val sb = StringBuilder()
                        while (i < s.length && s[i] != q) {
                            if (s[i] == '\\' && i + 1 < s.length) {
                                sb.append(s[i + 1]); i += 2
                            } else {
                                sb.append(s[i]); i++
                            }
                        }
                        i++
                        out.add(Tk("str", sb.toString()))
                    }
                    c.isDigit() || (c == '-' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                        val sb = StringBuilder().append(c)
                        i++
                        while (i < s.length && (s[i].isDigit() || s[i] == '.')) {
                            sb.append(s[i]); i++
                        }
                        out.add(Tk("num", sb.toString()))
                    }
                    c.isLetter() || c == '_' -> {
                        val sb = StringBuilder().append(c)
                        i++
                        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) {
                            sb.append(s[i]); i++
                        }
                        out.add(Tk("ident", sb.toString()))
                    }
                    else -> {
                        val two = s.substring(i, (i + 2).coerceAtMost(s.length))
                        when (two) {
                            "==", "!=", ">=", "<=", "&&", "||" -> {
                                out.add(Tk("op", two)); i += 2
                            }
                            else -> {
                                out.add(Tk("op", c.toString())); i++
                            }
                        }
                    }
                }
            }
            return out
        }
    }

    private class Parser(private val toks: List<Tokenizer.Tk>, private val vars: Map<String, String>) {
        private var pos = 0

        private fun peek(): Tokenizer.Tk? = toks.getOrNull(pos)
        private fun next(): Tokenizer.Tk? = toks.getOrNull(pos)?.also { pos++ }
        private fun expect(text: String) {
            val t = next()
            if (t == null || t.type != "op" || t.text != text) {
                throw IllegalStateException("期望 $text")
            }
        }

        fun parseOr(): Boolean {
            var left = parseAnd()
            while (peek()?.let { it.type == "op" && it.text == "||" } == true) {
                next()
                left = left || parseAnd()
            }
            return left
        }

        fun parseAnd(): Boolean {
            var left = parseNot()
            while (peek()?.let { it.type == "op" && it.text == "&&" } == true) {
                next()
                left = left && parseNot()
            }
            return left
        }

        fun parseNot(): Boolean {
            if (peek()?.let { it.type == "op" && it.text == "!" } == true) {
                next()
                return !parseNot()
            }
            return parseComparison()
        }

        fun parseComparison(): Boolean {
            val left = parseOperand()
            val op = peek()
            if (op?.type == "op" && op.text in setOf("==", "!=", ">", "<", ">=", "<=")) {
                next()
                val right = parseOperand()
                return compare(left, op.text, right)
            }
            return toBool(left)
        }

        fun parseOperand(): Any? {
            val t = peek() ?: throw IllegalStateException("表达式意外结束")
            return when (t.type) {
                "(" -> {
                    next()
                    val e = parseOr()
                    expect(")")
                    e
                }
                "str" -> {
                    next(); t.text
                }
                "num" -> {
                    next(); t.text.toDouble()
                }
                "ident" -> {
                    next(); resolveIdent(t.text)
                }
                else -> {
                    next(); t.text
                }
            }
        }

        private fun resolveIdent(name: String): Any? = when (name) {
            "true" -> true
            "false" -> false
            "null" -> null
            else -> vars[name] ?: ""
        }

        private fun toBool(v: Any?): Boolean = when (v) {
            is Boolean -> v
            is Double -> v != 0.0
            is String -> v.equals("true", ignoreCase = true) || v.isNotEmpty()
            null -> false
            else -> true
        }

        private fun asStr(v: Any?): String = when (v) {
            is Boolean -> v.toString()
            is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
            null -> ""
            else -> v.toString()
        }

        private fun compare(l: Any?, op: String, r: Any?): Boolean {
            val lc = (l as? Double) ?: runCatching { (l as? String)?.toDouble() }.getOrNull()
            val rc = (r as? Double) ?: runCatching { (r as? String)?.toDouble() }.getOrNull()
            val bothNum = lc != null && rc != null
            return when (op) {
                "==" -> if (bothNum) lc == rc else asStr(l) == asStr(r)
                "!=" -> if (bothNum) lc != rc else asStr(l) != asStr(r)
                ">" -> if (bothNum) lc!! > rc!! else asStr(l) > asStr(r)
                "<" -> if (bothNum) lc!! < rc!! else asStr(l) < asStr(r)
                ">=" -> if (bothNum) lc!! >= rc!! else asStr(l) >= asStr(r)
                "<=" -> if (bothNum) lc!! <= rc!! else asStr(l) <= asStr(r)
                else -> false
            }
        }
    }
}
