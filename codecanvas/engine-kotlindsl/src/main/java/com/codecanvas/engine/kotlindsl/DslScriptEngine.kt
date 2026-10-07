package com.codecanvas.engine.kotlindsl

import com.codecanvas.core.script.DrawList
import com.codecanvas.core.script.DrawListBinding
import com.codecanvas.core.script.ScriptEngine
import com.codecanvas.core.script.ScriptLanguage
import com.codecanvas.core.script.ScriptResult
import com.codecanvas.core.script.ScriptSandbox
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Kotlin DSL 引擎 —— **零依赖、零体积增量**的第四种脚本。
 *
 * 存在意义：
 * - 不想为 JS/Lua 引入原生 .so 时（体积敏感、或只需要固定模板）
 * - 脚本由服务端下发但语法要极度可控（自研语法，天然沙箱化：没有文件/网络/反射能力）
 * - AI 生成这类扁平指令脚本的成功率最高（无嵌套语法负担）
 *
 * 语法（行式，支持变量 / 表达式 / 循环 / 条件）：
 *
 * ```
 * bg #0d1117
 * fill #58a6ff
 * $gap = 60
 * repeat 12 i {
 *     circle {100 + $i * $gap} 300 {20 + $i}
 * }
 * text "CodeCanvas" 100 900 48 center
 * ```
 *
 * `{}` 内为算术表达式，支持 + - * / % ^ ( ) 与 sin/cos/min/max/abs/rand。
 */
class DslScriptEngine : ScriptEngine {

    override val language: ScriptLanguage = ScriptLanguage.KOTLIN_DSL

    @Volatile private var interrupted = false

    override suspend fun execute(
        script: String,
        out: DrawList,
        sandbox: ScriptSandbox,
        bindings: Map<String, Any?>,
    ): ScriptResult {
        interrupted = false
        val binding = DrawListBinding(out, 1080f, 1440f, bindings, sandbox.maxLogs)
        val start = System.currentTimeMillis()
        val vars = HashMap<String, Double>(bindings.size + 8).apply {
            bindings.forEach { (k, v) -> (v as? Number)?.toDouble()?.let { put(k, it) } }
            put("W", 1080.0); put("H", 1440.0); put("PI", PI)
        }

        return try {
            val lines = script.lineSequence()
                .map { it.substringBefore("//").trim() }
                .filter { it.isNotEmpty() }
                .toList()
            Parser(lines, binding, vars, sandbox.maxCallDepth) { interrupted }.run()
            ScriptResult.Success(out.size, System.currentTimeMillis() - start, binding.drainLogs())
        } catch (t: Interrupted) {
            ScriptResult.Failure.Timeout(-1)
        } catch (t: Throwable) {
            ScriptResult.Failure.Runtime(t.message ?: "DSL 脚本错误", t.stackTraceToString())
        }
    }

    override fun interrupt() { interrupted = true }
    override fun release() {}

    private class Interrupted : RuntimeException("interrupted")

    private class Parser(
        private val lines: List<String>,
        private val b: DrawListBinding,
        private val vars: HashMap<String, Double>,
        private val maxDepth: Int,
        private val interrupted: () -> Boolean,
    ) {
        private var i = 0

        fun run() {
            while (i < lines.size) {
                if (interrupted()) throw Interrupted()
                val line = lines[i]
                if (line.endsWith("{")) {
                    // 块语句：收集到匹配的 }
                    val body = collectBlock()
                    execBlock(line.removeSuffix("{").trim(), body, depthOffset)
                } else {
                    exec(line, vars)
                }
                i++
            }
        }

        private fun collectBlock(): List<String> {
            val head = lines[i]
            val indent = head.takeWhile { it == ' ' || it == '\t' }.length
            val body = ArrayList<String>()
            i++
            while (i < lines.size) {
                val l = lines[i]
                if (l.trim() == "}" && l.takeWhile { it == ' ' || it == '\t' }.length <= indent) break
                body.add(l.trim())
                i++
            }
            return body
        }

        private fun execBlock(head: String, body: List<String>, depth: Int) {
            if (depth > maxDepth) throw RuntimeException("块嵌套超过 $maxDepth 层")
            val t = head.split(Regex("\\s+"))
            when (t.firstOrNull()) {
                "repeat" -> {
                    val times = t.getOrNull(1)?.toIntOrNull() ?: eval(t[1], vars).toInt()
                    val varName = t.getOrNull(2)?.removePrefix("$") ?: "i"
                    repeat(times.coerceAtMost(100_000)) { n ->
                        if (interrupted()) throw Interrupted()
                        val scope = HashMap(vars)
                        scope[varName] = n.toDouble()
                        // 用子 Parser 递归执行体，从而支持块内再嵌套块。
                        // 上游此处误写成 Runner（类名其实是 Parser）且参数顺序颠倒，
                        // Kotlin 2.3 下还把 .apply { run() } 的 receiver 判成 kotlin.run，直接编译不过。
                        val sub = Parser(body, b, scope, maxDepth, interrupted)
                        sub.depthOffset = depth + 1
                        sub.run()
                    }
                }
                "if" -> {
                    val cond = t.drop(1).joinToString(" ")
                    if (eval(cond, vars) != 0.0) {
                        val sub = Parser(body, b, HashMap(vars), maxDepth, interrupted)
                        sub.depthOffset = depth + 1
                        sub.run()
                    }
                }
                else -> throw RuntimeException("未知块语句: ${t.firstOrNull()}")
            }
        }

        /** 子 Runner 相对于父级已经消耗的嵌套层数 */
        private var depthOffset: Int = 0

        private fun exec(line: String, scope: HashMap<String, Double>) {
            val sp = line.indexOf(' ')
            val cmd = if (sp < 0) line else line.substring(0, sp)
            val rest = if (sp < 0) "" else line.substring(sp + 1).trim()
            val a = splitArgs(rest)   // 保留 "..." 与 {...} 整体

            when (cmd) {
                "bg", "background" -> b.background(a[0])
                "fill" -> b.setFill(a[0])
                "stroke" -> b.setStroke(a[0], num(a, 1, 2.0).toFloat())
                "alpha" -> b.setAlpha(num(a, 0, 1.0).toFloat())
                "shadow" -> b.setShadow(a[0], num(a, 1, 8.0).toFloat(), num(a, 2, 0.0).toFloat(), num(a, 3, 4.0).toFloat())
                "noshadow" -> b.clearShadow()
                "save" -> b.save()
                "restore" -> b.restore()
                "translate" -> b.translate(num(a, 0).toFloat(), num(a, 1).toFloat())
                "rotate" -> b.rotate(num(a, 0).toFloat())
                "scale" -> b.scale(num(a, 0, 1.0).toFloat(), num(a, 1, num(a, 0, 1.0)).toFloat())

                "line" -> b.line(num(a, 0).toFloat(), num(a, 1).toFloat(), num(a, 2).toFloat(), num(a, 3).toFloat())
                "rect" -> b.rect(num(a, 0).toFloat(), num(a, 1).toFloat(), num(a, 2).toFloat(), num(a, 3).toFloat(), num(a, 4, 0.0).toFloat())
                "circle" -> b.circle(num(a, 0).toFloat(), num(a, 1).toFloat(), num(a, 2).toFloat())
                "oval" -> b.oval(num(a, 0).toFloat(), num(a, 1).toFloat(), num(a, 2).toFloat(), num(a, 3).toFloat())
                "arc" -> b.arc(num(a, 0).toFloat(), num(a, 1).toFloat(), num(a, 2).toFloat(), num(a, 3).toFloat(), num(a, 4).toFloat(), num(a, 5).toFloat())
                "path" -> b.path(a[0])
                "poly" -> b.polyline(a.map { num(listOf(it), 0).toFloat() }.toFloatArray(), false)
                "text" -> b.text(a[0], num(a, 1).toFloat(), num(a, 2).toFloat(), num(a, 3, 28.0).toFloat(), a.getOrElse(4) { "left" })

                "log" -> b.log(a.joinToString(" "))
                else -> {
                    // 赋值：$x = expr
                    val m = Regex("^\\\$(\\w+)\\s*=\\s*(.+)$").find(line)
                    if (m != null) {
                        vars[m.groupValues[1]] = eval(m.groupValues[2], scope)
                    } else throw RuntimeException("未知指令: $cmd")
                }
            }
        }

        private fun num(a: List<String>, idx: Int, def: Double = 0.0): Double =
            a.getOrNull(idx)?.let { eval(it, vars) } ?: def

        /** 按空格切分，但 "..." 与 {...} 视为整体 */
        private fun splitArgs(s: String): List<String> {
            val out = ArrayList<String>()
            var cur = StringBuilder()
            var quote = false
            var brace = 0
            for (ch in s) {
                when {
                    ch == '"' -> { quote = !quote; cur.append(ch) }
                    ch == '{' -> { brace++; cur.append(ch) }
                    ch == '}' -> { brace--; cur.append(ch) }
                    ch == ' ' && !quote && brace == 0 -> {
                        if (cur.isNotEmpty()) { out.add(cur.toString()); cur = StringBuilder() }
                    }
                    else -> cur.append(ch)
                }
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
            return out.map { it.trim('"') }
        }

        /** 极简递归下降求值器：+ - * / % ^ ( ) 变量 函数 */
        private fun eval(expr: String, scope: HashMap<String, Double>): Double {
            var s = expr.trim()
            if (s.startsWith("{") && s.endsWith("}")) s = s.substring(1, s.length - 1).trim()
            if (s.startsWith('"')) return s.trim('"').toDoubleOrNull() ?: 0.0
            val st = State(s, scope)
            val v = st.expr()
            return v
        }

        private inner class State(val src: String, val scope: HashMap<String, Double>) {
            var p = 0
            fun expr(): Double = term().let { acc ->
                var v = acc
                while (p < src.length) {
                    when (src[p]) {
                        '+' -> { p++; v += term() }
                        '-' -> { p++; v -= term() }
                        else -> break
                    }
                }
                v
            }

            fun term(): Double {
                var v = factor()
                while (p < src.length) {
                    when (src[p]) {
                        '*' -> { p++; v *= factor() }
                        '/' -> { p++; v /= factor() }
                        '%' -> { p++; v %= factor() }
                        else -> break
                    }
                }
                return v
            }

            fun factor(): Double {
                var v = power()
                if (p < src.length && src[p] == '^') { p++; v = Math.pow(v, power()) }
                return v
            }

            fun power(): Double {
                skip()
                if (p >= src.length) return 0.0
                return when (val c = src[p]) {
                    '(' -> { p++; val v = expr(); skip(); if (p < src.length && src[p] == ')') p++; v }
                    '-' -> { p++; -power() }
                    '+' -> { p++; power() }
                    else -> {
                        if (c.isDigit() || c == '.') { num() }
                        else if (c == '$') { p++; val n = ident(); scope[n] ?: vars[n] ?: 0.0 }
                        else { val n = ident(); if (p < src.length && src[p] == '(') call(n) else (scope[n] ?: vars[n] ?: 0.0) }
                    }
                }
            }

            fun call(name: String): Double {
                p++ // (
                val args = ArrayList<Double>()
                while (p < src.length && src[p] != ')') {
                    args.add(expr())
                    if (p < src.length && src[p] == ',') p++
                }
                if (p < src.length) p++ // )
                return when (name) {
                    "sin" -> sin(Math.toRadians(args.getOrElse(0) { 0.0 }))
                    "cos" -> cos(Math.toRadians(args.getOrElse(0) { 0.0 }))
                    "abs" -> kotlin.math.abs(args.getOrElse(0) { 0.0 })
                    "min" -> args.minOrNull() ?: 0.0
                    "max" -> args.maxOrNull() ?: 0.0
                    "round" -> args.getOrElse(0) { 0.0 }.let { kotlin.math.round(it).toDouble() }
                    "rand" -> Math.random()
                    "sqrt" -> kotlin.math.sqrt(args.getOrElse(0) { 0.0 })
                    else -> 0.0
                }
            }

            fun num(): Double {
                val st = p
                while (p < src.length && (src[p].isDigit() || src[p] == '.')) p++
                return src.substring(st, p).toDoubleOrNull() ?: 0.0
            }

            fun ident(): String {
                val st = p
                while (p < src.length && (src[p].isLetterOrDigit() || src[p] == '_')) p++
                return src.substring(st, p)
            }

            fun skip() { while (p < src.length && src[p] == ' ') p++ }
        }
    }
}
