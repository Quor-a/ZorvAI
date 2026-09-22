package com.yuanbao.miniapp.view

import com.yuanbao.miniapp.util.Json

/**
 * Self-developed expression evaluator for `{{ ... }}` bindings.
 *
 * Supported: property paths (a.b.c / a[0]), literals, unary ! / -,
 * arithmetic + - * / %, comparisons (==, !=, ===, !==, <, <=, >, >=),
 * logical && ||, and the ternary operator.
 */
class ExpressionEvaluator {

    private val tokens = ArrayList<Token>()
    private var pos = 0

    private data class Token(val type: T, val text: String = "", val num: Double = 0.0) {
        enum class T { NUM, STR, IDENT, PUNCT, END }
    }

    fun evaluate(expr: String, context: Json): Json = runCatching {
        tokens.clear()
        pos = 0
        tokenize(expr)
        val result = parseTernary(context)
        result
    }.getOrElse { Json.Null }

    // ------------------------------------------------------------ lexer
    private fun tokenize(s: String) {
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
                        if (s[i] == '\\' && i + 1 < s.length) { sb.append(s[i + 1]); i += 2; continue }
                        sb.append(s[i]); i++
                    }
                    i++
                    tokens.add(Token(Token.T.STR, sb.toString()))
                }
                c.isDigit() -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    tokens.add(Token(Token.T.NUM, num = s.substring(start, i).toDoubleOrNull() ?: 0.0))
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$')) i++
                    tokens.add(Token(Token.T.IDENT, s.substring(start, i)))
                }
                else -> {
                    val two = s.substring(i, minOf(i + 2, s.length))
                    when {
                        two == "===" || two == "!==" || two == "&&" || two == "||" ||
                            two == ">=" || two == "<=" || two == "==" || two == "!=" -> {
                            tokens.add(Token(Token.T.PUNCT, two)); i += 2
                        }
                        else -> { tokens.add(Token(Token.T.PUNCT, c.toString())); i++ }
                    }
                }
            }
        }
        tokens.add(Token(Token.T.END))
    }

    private fun peek(): Token = tokens.getOrElse(pos) { Token(Token.T.END) }
    private fun next(): Token = tokens.getOrElse(pos++) { Token(Token.T.END) }
    private fun eat(text: String): Boolean {
        if (peek().type == Token.T.PUNCT && peek().text == text) { pos++; return true }
        return false
    }

    // ------------------------------------------------------------ parser
    private fun parseTernary(ctx: Json): Json {
        val cond = parseOr(ctx)
        if (eat("?")) {
            val a = parseTernary(ctx)
            if (!eat(":")) return cond
            val b = parseTernary(ctx)
            return if (truthy(cond)) a else b
        }
        return cond
    }

    private fun parseOr(ctx: Json): Json {
        var left = parseAnd(ctx)
        while (eat("||")) {
            val l = truthy(left)
            val right = parseAnd(ctx)
            left = if (l) left else right
        }
        return left
    }

    private fun parseAnd(ctx: Json): Json {
        var left = parseEquality(ctx)
        while (eat("&&")) {
            val l = truthy(left)
            val right = parseEquality(ctx)
            left = if (!l) left else right
        }
        return left
    }

    private fun parseEquality(ctx: Json): Json {
        var left = parseRelational(ctx)
        while (true) {
            when {
                eat("===") -> { val r = parseRelational(ctx); left = Json.Bool(strictEq(left, r)) }
                eat("!==") -> { val r = parseRelational(ctx); left = Json.Bool(!strictEq(left, r)) }
                eat("==") -> { val r = parseRelational(ctx); left = Json.Bool(looseEq(left, r)) }
                eat("!=") -> { val r = parseRelational(ctx); left = Json.Bool(!looseEq(left, r)) }
                else -> return left
            }
        }
    }

    private fun parseRelational(ctx: Json): Json {
        var left = parseAdditive(ctx)
        while (true) {
            when {
                eat("<=") -> { val r = parseAdditive(ctx); left = Json.Bool(left.asDouble() <= r.asDouble()) }
                eat(">=") -> { val r = parseAdditive(ctx); left = Json.Bool(left.asDouble() >= r.asDouble()) }
                eat("<") -> { val r = parseAdditive(ctx); left = Json.Bool(left.asDouble() < r.asDouble()) }
                eat(">") -> { val r = parseAdditive(ctx); left = Json.Bool(left.asDouble() > r.asDouble()) }
                else -> return left
            }
        }
    }

    private fun parseAdditive(ctx: Json): Json {
        var left = parseMultiplicative(ctx)
        while (true) {
            when {
                eat("+") -> {
                    val r = parseMultiplicative(ctx)
                    left = if (left is Json.Str || r is Json.Str) Json.Str(left.asString() + r.asString())
                    else Json.Num(left.asDouble() + r.asDouble())
                }
                eat("-") -> { val r = parseMultiplicative(ctx); left = Json.Num(left.asDouble() - r.asDouble()) }
                else -> return left
            }
        }
    }

    private fun parseMultiplicative(ctx: Json): Json {
        var left = parseUnary(ctx)
        while (true) {
            when {
                eat("*") -> { val r = parseUnary(ctx); left = Json.Num(left.asDouble() * r.asDouble()) }
                eat("/") -> { val r = parseUnary(ctx); left = Json.Num(left.asDouble() / r.asDouble()) }
                eat("%") -> { val r = parseUnary(ctx); left = Json.Num(left.asDouble() % r.asDouble()) }
                else -> return left
            }
        }
    }

    private fun parseUnary(ctx: Json): Json {
        if (eat("!")) return Json.Bool(!truthy(parseUnary(ctx)))
        if (eat("-")) return Json.Num(-parseUnary(ctx).asDouble())
        if (eat("+")) return Json.Num(parseUnary(ctx).asDouble())
        return parsePrimary(ctx)
    }

    private fun parsePrimary(ctx: Json): Json {
        val t = next()
        when (t.type) {
            Token.T.NUM -> return Json.Num(t.num)
            Token.T.STR -> return Json.Str(t.text)
            Token.T.IDENT -> {
                when (t.text) {
                    "true" -> return Json.Bool(true)
                    "false" -> return Json.Bool(false)
                    "null" -> return Json.Null
                    "undefined" -> return Json.Null
                }
                var value = lookup(ctx, t.text)
                while (true) {
                    if (eat(".")) {
                        val name = next().text
                        value = lookup(value, name)
                        continue
                    }
                    if (eat("[")) {
                        val idx = parseTernary(ctx)
                        if (idx is Json.Num) value = indexOf(value, idx.value.toInt())
                        else value = lookup(value, idx.asString())
                        if (!eat("]")) break
                        continue
                    }
                    break
                }
                return value
            }
            Token.T.PUNCT -> {
                if (t.text == "(") {
                    val v = parseTernary(ctx)
                    eat(")")
                    return v
                }
                return Json.Null
            }
            else -> return Json.Null
        }
    }

    private fun lookup(root: Json, path: String): Json {
        if (root is Json.Obj) {
            val direct = root[path]
            if (direct != null) return direct
        }
        return Json.Null
    }

    private fun indexOf(v: Json, i: Int): Json {
        if (v is Json.Arr) return v.items.getOrNull(i) ?: Json.Null
        if (v is Json.Str) return Json.Str(v.value.getOrNull(i)?.toString() ?: "")
        return Json.Null
    }

    private fun truthy(v: Json): Boolean = when (v) {
        is Json.Null -> false
        is Json.Bool -> v.value
        is Json.Num -> v.value != 0.0
        is Json.Str -> v.value.isNotEmpty() && v.value != "false" && v.value != "0"
        is Json.Arr -> v.items.isNotEmpty()
        is Json.Obj -> v.fields.isNotEmpty()
    }

    private fun strictEq(a: Json, b: Json): Boolean {
        if (a is Json.Null || b is Json.Null) return a is Json.Null && b is Json.Null
        if (a is Json.Num && b is Json.Num) return a.value == b.value
        if (a is Json.Str && b is Json.Str) return a.value == b.value
        if (a is Json.Bool && b is Json.Bool) return a.value == b.value
        return false
    }

    private fun looseEq(a: Json, b: Json): Boolean {
        if (a is Json.Null && b is Json.Null) return true
        if (a is Json.Null || b is Json.Null) return a is Json.Null
        return a.asString() == b.asString()
    }

    companion object {
        private val MUSTACHE = Regex("\\{\\{([^{}]*)\\}\\}")

        /** True when [text] contains at least one {{ }} binding. */
        fun hasBinding(text: String): Boolean = MUSTACHE.containsMatchIn(text)

        /**
         * Interpolates [text]: pure bindings keep their native type,
         * mixed content is concatenated as a string.
         */
        fun interpolate(text: String, context: Json): Json {
            if (!hasBinding(text)) return Json.Str(text)
            val matches = MUSTACHE.findAll(text).toList()
            if (matches.size == 1) {
                val m = matches[0]
                if (m.range.first == 0 && m.range.last == text.length - 1) {
                    return ExpressionEvaluator().evaluate(m.groupValues[1], context)
                }
            }
            val sb = StringBuilder()
            var last = 0
            for (m in matches) {
                sb.append(text.substring(last, m.range.first))
                sb.append(ExpressionEvaluator().evaluate(m.groupValues[1], context).asString())
                last = m.range.last + 1
            }
            if (last < text.length) sb.append(text.substring(last))
            return Json.Str(sb.toString())
        }
    }
}
