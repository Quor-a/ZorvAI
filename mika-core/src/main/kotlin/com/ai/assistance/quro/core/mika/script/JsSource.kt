package com.ai.assistance.quro.core.mika.script

/**
 * 模型或站点命令写的 JS 源码的词法检查。
 *
 * [usesImport]:字符串、注释、正则字面量之外有没有 `import` 这个词(动态 import 会在全局作用域里跑模块,绕开只读包装)。
 * `/` 是除号还是正则的开头,有时只看词法分不清(`)`、`}`、`++` 之后,以及 of/yield/await 这类既可能是关键字也可能是变量名的词之后):
 * 这时两种读法都走一遍,任何一种读法在代码里读到 import 都算有。读法按 (位置, `/` 的读法, 括号栈) 去重,不会成倍增长;
 * 读不通的读法(正则或字符串没合上就到行尾)作废。宁可误拦,不漏。
 */
object JsSource {
    private enum class Slash { Regex, Divide, Both }

    private data class State(val pos: Int, val slash: Slash, val stack: List<Char>)

    /** 之后的 `/` 一定是正则开头的关键字。 */
    private val BEFORE_EXPRESSION = setOf("return", "typeof", "instanceof", "in", "new", "delete", "void", "throw", "case", "do", "else")
    /** 可能是关键字也可能是变量名的词:之后的 `/` 两种读法都走。 */
    private val EITHER = setOf("of", "yield", "await", "let", "async", "get", "set", "static")

    fun usesImport(code: String): Boolean {
        val seen = HashSet<State>()
        val work = ArrayDeque<State>()
        work += State(0, Slash.Regex, emptyList())
        while (work.isNotEmpty()) {
            val s = work.removeLast()
            if (!seen.add(s)) continue
            if (Lexer(code, s) { work += it }.run()) return true
        }
        return false
    }

    /** 从 [start] 起读一条读法,遇到两可的 `/` 就把两种读法交给 [fork] 并停下。读到 import 返回 true。 */
    private class Lexer(private val code: String, start: State, private val fork: (State) -> Unit) {
        private var i = start.pos
        private var slash = start.slash
        private val stack = start.stack.toMutableList()
        private val n = code.length

        fun run(): Boolean {
            while (i < n) {
                val c = code[i]
                when {
                    c.isWhitespace() -> i++
                    c == '/' && peek(1) == '/' -> { while (i < n && !lineEnd(code[i])) i++ }
                    c == '/' && peek(1) == '*' -> { val e = code.indexOf("*/", i + 2); i = if (e < 0) n else e + 2 }
                    c == '/' -> when (slash) {
                        Slash.Divide -> { i++; slash = Slash.Regex }
                        Slash.Regex -> { if (!regex()) return false }
                        Slash.Both -> {
                            fork(State(i, Slash.Regex, stack.toList()))
                            fork(State(i, Slash.Divide, stack.toList()))
                            return false
                        }
                    }
                    c == '\'' || c == '"' -> { if (!string(c)) return false; slash = Slash.Divide }
                    c == '`' -> { i++; if (!template()) return false }
                    c == '{' -> { stack += '{'; i++; slash = Slash.Regex }
                    c == '}' -> {
                        i++
                        if (stack.lastOrNull() == '`') { stack.removeAt(stack.lastIndex); if (!template()) return false }
                        else { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex); slash = Slash.Both }
                    }
                    c == ')' -> { i++; slash = Slash.Both }
                    c == ']' -> { i++; slash = Slash.Divide }
                    (c == '+' || c == '-') && peek(1) == c -> { i += 2; slash = Slash.Both }
                    c.isDigit() || (c == '.' && peek(1)?.isDigit() == true) -> { while (i < n && (code[i].isLetterOrDigit() || code[i] == '.' || code[i] == '_')) i++; slash = Slash.Divide }
                    identStart(c) -> {
                        val word = word()
                        if (word == "import") return true
                        slash = when (word) {
                            in BEFORE_EXPRESSION -> Slash.Regex
                            in EITHER -> Slash.Both
                            else -> Slash.Divide
                        }
                    }
                    else -> { i++; slash = Slash.Regex }
                }
            }
            return false
        }

        private fun peek(k: Int): Char? = code.getOrNull(i + k)
        private fun lineEnd(c: Char) = c == '\n' || c == '\r' || c == ' ' || c == ' '
        private fun identStart(c: Char) = c == '$' || c == '_' || c == '\\' || c.isLetter()
        private fun identPart(c: Char) = identStart(c) || c.isDigit() || c == '‌' || c == '‍'

        /** 一个标识符;`\uXXXX`、`\u{…}` 转义解开后再比。 */
        private fun word(): String {
            val out = StringBuilder()
            while (i < n && identPart(code[i])) {
                if (code[i] == '\\' && peek(1) == 'u') {
                    val braced = peek(2) == '{'
                    val from = i + if (braced) 3 else 2
                    val to = if (braced) code.indexOf('}', from).takeIf { it > 0 } ?: n else minOf(from + 4, n)
                    code.substring(from, to).toIntOrNull(16)?.let { out.appendCodePoint(it) }
                    i = if (braced) minOf(to + 1, n) else to
                } else out.append(code[i++])
            }
            return out.toString()
        }

        /** 字符串字面量;到行尾还没合上(且不是续行)为 false。 */
        private fun string(q: Char): Boolean {
            i++
            while (i < n) {
                val c = code[i]
                when {
                    c == '\\' -> i += 2
                    c == q -> { i++; return true }
                    lineEnd(c) -> return false
                    else -> i++
                }
            }
            return false
        }

        /** 模板字符串的文字部分,从当前位置读到反引号(结束)或 `${`(压栈,回到代码)。 */
        private fun template(): Boolean {
            while (i < n) {
                val c = code[i]
                when {
                    c == '\\' -> i += 2
                    c == '`' -> { i++; slash = Slash.Divide; return true }
                    c == '$' && peek(1) == '{' -> { stack += '`'; i += 2; slash = Slash.Regex; return true }
                    else -> i++
                }
            }
            return false
        }

        /** 正则字面量(含字符类里的 `/`)和标志;到行尾还没合上为 false。 */
        private fun regex(): Boolean {
            i++
            var inClass = false
            while (i < n) {
                val c = code[i]
                when {
                    lineEnd(c) -> return false
                    c == '\\' -> i += 2
                    c == '[' -> { inClass = true; i++ }
                    c == ']' -> { inClass = false; i++ }
                    c == '/' && !inClass -> {
                        i++
                        while (i < n && identPart(code[i])) i++
                        slash = Slash.Divide
                        return true
                    }
                    else -> i++
                }
            }
            return false
        }
    }
}
