package com.ai.assistance.quro.core.scripting

/**
 * TypeScript → JavaScript 转译器（类型剥离子集，token 感知单遍扫描）。
 *
 * 原理：TS 的运行时语义 = 去掉类型标注后的 JS。这里实现 strip-types：
 *  - `interface X {...}` / `declare ...` / `import type ...` / `export type ...` 整块删除
 *  - `type X = ...;`（配平到分号）删除
 *  - 变量/参数/返回值标注 `: T` 剥离（含泛型 `Array<T>`、联合 `"a"|"b"`、函数类型 `(x: T) => R`、可选 `?`）
 *  - `as T` / `as const` 断言剥离、非空断言 `x!` 剥离
 *  - `enum X { A, B = 2 }` → IIFE 对象（含字符串枚举）
 *  - 函数/类声明处泛型 `<T extends ...>` 删除
 *  - class 字段 `x: number = 1` → `x = 1`
 *  - ESM → CommonJS：import（default / named / namespace / 副作用 / type-only）→ require；
 *    export（default/const·let·var/function/class/花括号列表/星号再导出 from）→ module.exports.*。
 *
 * 正确性关键：维护「花括号上下文栈」区分对象字面量 `{a: 1}`（冒号是属性，不剥）
 * 与块级作用域 `class A { x: T }`（冒号是标注，剥）。字符串/模板/注释/正则内的内容一律不碰。
 *
 * 不支持（原样透传，QuickJS 会报语法错）：namespace、装饰器、satisfies。
 */
object TsTranspiler {

    fun transpile(ts: String): String = Transpiler(ts).run()

    private class Transpiler(val ts: String) {
        val n = ts.length
        val out = StringBuilder(ts.length + 64)
        var i = 0
        /** 花括号上下文栈：true=对象字面量（冒号=属性），false=块作用域（冒号=可能的类型标注）。 */
        val braceStack = ArrayDeque<Boolean>()
        /** import/export 转换的临时变量计数（__imp0 / __exp0）。 */
        var impCounter = 0
        var expCounter = 0

        fun run(): String {
            while (i < n) step()
            return out.toString()
        }

        /* ---------- 基础工具 ---------- */
        fun peek(k: Int = 0): Char = ts.getOrNull(i + k) ?: ' '

        fun startsWithWord(w: String): Boolean {
            if (i + w.length > n || !ts.regionMatches(i, w, 0, w.length)) return false
            if (i > 0 && (ts[i - 1].isLetterOrDigit() || ts[i - 1] == '_' || ts[i - 1] == '$')) return false
            val after = ts.getOrNull(i + w.length) ?: return true
            return !(after.isLetterOrDigit() || after == '_' || after == '$')
        }

        fun lastNonWsInOutput(): Char {
            var j = out.length - 1
            while (j >= 0 && out[j].isWhitespace()) j--
            return if (j >= 0) out[j] else ' '
        }

        fun trimTrailingWs() {
            while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')) out.setLength(out.length - 1)
        }

        fun inObjectLiteral(): Boolean = braceStack.lastOrNull() == true

        /** 输出末尾向前跳过空白+一个标识符/数字 token 后，是否是给定关键字（case/default 标签判定）。 */
        fun prevKeywordIs(kw: String): Boolean {
            var j = out.length - 1
            while (j >= 0 && out[j].isWhitespace()) j--
            // 跳过当前 token（标签值 / default）
            while (j >= 0 && (out[j].isLetterOrDigit() || out[j] == '_' || out[j] == '$' || out[j] == ':')) j--
            // 再跳过空白
            while (j >= 0 && out[j].isWhitespace()) j--
            return j >= kw.length - 1 && out.substring(j - kw.length + 1, j + 1) == kw
        }

        /* ---------- 主循环 ---------- */
        fun step() {
            val c = ts[i]
            when {
                /* 注释 */
                c == '/' && peek(1) == '/' -> copyLineComment()
                c == '/' && peek(1) == '*' -> copyBlockComment()

                /* 字符串 / 模板 */
                c == '"' || c == '\'' || c == '`' -> copyString(c)

                /* 正则字面量（除法判定）*/
                c == '/' && isRegexPosition() -> copyRegex()

                /* TS 结构 */
                startsWithWord("interface") && (peek(9) == ' ' || peek(9) == '{' || peek(9) == '\n') -> skipBraceBlock(9)
                startsWithWord("declare") -> skipToLineEnd()
                startsWithWord("type") && peek(4) == ' ' && nextNonWsAfter(4) !in listOf('=', '(') -> skipTypeAlias()
                startsWithWord("enum") && peek(4) == ' ' -> emitEnum()
                startsWithWord("as") && peek(2) == ' ' && lastNonWsInOutput() != '.' -> skipAsCast()
                /* ESM → CommonJS（import 后跟 ( 是动态导入、. 是 import.meta，均不动；
                   前置 '.' 排除 obj.export 属性访问；对象字面量内 { import: x } / { export: x } 是属性键，不是语句——
                   不加此判定会把属性键当 import 语句解析，readIdent 在 ':' 处返回空且不前进 → 死循环）*/
                startsWithWord("import") && (i == 0 || ts[i - 1] != '.') && !inObjectLiteral() &&
                    nextNonWsAfter(6) != '(' && nextNonWsAfter(6) != '.' -> transpileImport()
                startsWithWord("export") && (i == 0 || ts[i - 1] != '.') && !inObjectLiteral() -> transpileExport()

                /* 非空断言 x!（不是 !=/!!/!==）*/
                c == '!' && peek(1) != '=' && peek(1) != '!' && prevIsIdentifierEnd() -> i++

                /* 可选标注 `x?: T` 的问号（后跟':'，非 ?. / ??）*/
                c == '?' && peek(1) == ':' && prevIsIdentifierEnd() -> i++

                /* 标注 `: T`（对象字面量属性 / case 标签 / 三元 都不算）*/
                c == ':' && peek(1) != ':' && isAnnotationPosition() -> skipTypeAnnotation()

                /* 泛型声明 <T>( / class A<T> { */
                c == '<' && prevIsIdentifierEnd() && genericDeclClosesToParen() -> skipGenericDecl()

                /* 花括号上下文维护：先判定（拿 append 前的 prev），再 append。 */
                c == '{' -> {
                    val isObj = isObjectLiteralStart()
                    out.append(c)
                    braceStack.addLast(isObj)
                    i++
                }
                c == '}' -> {
                    out.append(c)
                    if (braceStack.isNotEmpty()) braceStack.removeLast()
                    i++
                }

                else -> { out.append(c); i++ }
            }
        }

        fun prevIsIdentifierEnd(): Boolean {
            val prev = lastNonWsInOutput()
            return prev.isLetterOrDigit() || prev == '_' || prev == '$' || prev == ')' || prev == ']'
        }

        /** `{` 的性质：前一非空输出是 `=` `(` `,` `[` `:` `=>` / return 等 → 对象字面量；否则块。 */
        fun isObjectLiteralStart(): Boolean {
            val prev = lastNonWsInOutput()
            if (prev == '=' || prev == '(' || prev == ',' || prev == '[' || prev == ':' || prev == '&') return true
            if (prev == '>' && out.length >= 2 && out[out.length - 2] == '=') return true // =>
            // return {...} / yield {...} / case {...} 罕见但支持：前一 token 是关键字
            if (prev.isLetter() && (prevKeywordIsRaw("return") || prevKeywordIsRaw("yield"))) return true
            return false
        }

        fun prevKeywordIsRaw(kw: String): Boolean {
            var j = out.length - 1
            while (j >= 0 && out[j].isWhitespace()) j--
            return j >= kw.length - 1 && out.substring(j - kw.length + 1, j + 1) == kw
        }

        /* ---------- 拷贝辅助 ---------- */
        fun copyLineComment() {
            while (i < n && ts[i] != '\n') { out.append(ts[i]); i++ }
        }

        fun copyBlockComment() {
            out.append("/*"); i += 2
            while (i < n && !(ts[i] == '*' && peek(1) == '/')) { out.append(ts[i]); i++ }
            if (i < n) { out.append("*/"); i += 2 }
        }

        fun copyString(quote: Char) {
            out.append(quote); i++
            while (i < n) {
                when {
                    ts[i] == '\\' -> { out.append(ts[i]); if (i + 1 < n) out.append(ts[i + 1]); i += 2 }
                    ts[i] == quote -> { out.append(quote); i++; return }
                    else -> { out.append(ts[i]); i++ }
                }
            }
        }

        fun isRegexPosition(): Boolean {
            val prev = lastNonWsInOutput()
            return !(prev.isLetterOrDigit() || prev == '_' || prev == '$' || prev == ')' || prev == ']' || prev == '.')
        }

        fun copyRegex() {
            out.append('/'); i++
            var inClass = false
            while (i < n) {
                when {
                    ts[i] == '\\' -> { out.append(ts[i]); if (i + 1 < n) out.append(ts[i + 1]); i += 2 }
                    ts[i] == '[' -> { inClass = true; out.append(ts[i]); i++ }
                    ts[i] == ']' -> { inClass = false; out.append(ts[i]); i++ }
                    ts[i] == '/' && !inClass -> { out.append('/'); i++; break }
                    else -> { out.append(ts[i]); i++ }
                }
            }
            while (i < n && ts[i].isLetter()) { out.append(ts[i]); i++ }
        }

        /* ---------- TS 结构处理 ---------- */

        /** 跳过到第一个配平 `{...}` 块结束（interface 等）。 */
        fun skipBraceBlock(lead: Int) {
            i += lead
            while (i < n && ts[i] != '{' && ts[i] != ';' && ts[i] != '\n') i++
            if (i >= n || ts[i] != '{') { skipToLineEnd(); return }
            var depth = 0
            while (i < n) {
                val c = ts[i]
                if (c == '"' || c == '\'' || c == '`') { val q = c; i++; while (i < n) { if (ts[i] == '\\') i += 2 else if (ts[i] == q) { i++; break } else i++ }; continue }
                if (c == '/' && peek(1) == '/') { while (i < n && ts[i] != '\n') i++; continue }
                if (c == '/' && peek(1) == '*') { i += 2; while (i < n && !(ts[i] == '*' && peek(1) == '/')) i++; i += 2; continue }
                if (c == '{') depth++
                if (c == '}') { depth--; if (depth == 0) { i++; return } }
                i++
            }
        }

        fun skipToLineEnd() {
            while (i < n && ts[i] != '\n') i++
        }

        fun nextNonWsAfter(off: Int): Char {
            var k = i + off
            while (k < n && ts[k].isWhitespace()) k++
            return ts.getOrNull(k) ?: ' '
        }

        /** `const  enum`（中间可有任意空白）的窥探。 */
        fun isConstEnumAhead(): Boolean {
            var k = i + 5
            while (k < n && ts[k].isWhitespace()) k++
            val next = ts.getOrNull(k + 4)
            return ts.regionMatches(k, "enum", 0, 4) && (next == null || !(next.isLetterOrDigit() || next == '_' || next == '$'))
        }

        /** `type X = ...`：跳过（配平到分号或行尾）。`type` 后跟 `=` 或 `(` 是 JS（label/表达式），不删。 */
        fun skipTypeAlias() {
            i += 4
            var depth = 0
            while (i < n) {
                val ch = ts[i]
                when (ch) {
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> depth--
                    ';' -> if (depth <= 0) { i++; return }
                    '\n' -> if (depth <= 0) return
                }
                i++
            }
        }

        /** `as T`：从输出里去掉前面的空格，跳过类型表达式。 */
        fun skipAsCast() {
            trimTrailingWs()
            i += 2
            while (i < n && ts[i] == ' ') i++
            while (i < n) {
                val ch = ts[i]
                if (ch.isLetterOrDigit() || ch == '_' || ch == '$' || ch == '.' || ch == '<' || ch == '>' ||
                    ch == '[' || ch == ']' || ch == '|' || ch == '&' || ch == '{' || ch == '}' || ch == '"' || ch == '\'' || ch == '-') {
                    i++
                } else if (ch == ' ') {
                    var k = i + 1
                    while (k < n && ts[k] == ' ') k++
                    val nc = ts.getOrNull(k) ?: break
                    if (nc.isLetter() || nc == '{' || nc == '[') i++ else break
                } else break
            }
        }

        /** `:` 是否处于「类型标注位置」：非对象字面量内 + 非 case/default 标签。
         *  对象字面量内仅方法返回类型 `f(): T` 的冒号是标注（前一字符必是 ')'）；
         *  属性键冒号 `key: value` 的前一字符是标识符——不剥。 */
        fun isAnnotationPosition(): Boolean {
            val prev = lastNonWsInOutput()
            if (inObjectLiteral()) return prev == ')'
            if (prevKeywordIs("case") || prevKeywordIs("default")) return false
            if (prev == '?') return true // x?: T（问号已剥）
            return prev.isLetterOrDigit() || prev == '_' || prev == '$' || prev == ')' || prev == ']'
        }

        /** 跳过类型标注（`:` 之后），到默认值 `=` / 语句边界为止；`= expr` 默认值留给 JS。 */
        fun skipTypeAnnotation() {
            i++ // ':'
            var angle = 0; var paren = 0; var brace = 0; var bracket = 0
            var started = false
            // 函数类型判定：标注以 `(` 开头（如 `(x: number) => number`）时，
            // 括号配平后的 `=>` 仍属于类型；否则（如 `: string =>`）`=>` 是箭头函数本体。
            var firstIsParen = false
            while (i < n) {
                val ch = ts[i]
                when {
                    ch == '<' -> { angle++; started = true; i++ }
                    ch == '>' -> { if (angle == 0) break; angle--; started = true; i++ }
                    ch == '(' -> { if (!started) firstIsParen = true; paren++; started = true; i++ }
                    ch == ')' -> { if (paren == 0) break; paren--; i++ }
                    ch == '{' -> { brace++; started = true; i++ }
                    ch == '}' -> { if (brace == 0) break; brace--; i++ }
                    ch == '[' -> { bracket++; started = true; i++ }
                    ch == ']' -> { if (bracket == 0) break; bracket--; i++ }
                    ch == '"' || ch == '\'' -> { copyString(ch); started = true }
                    ch == ':' -> { started = true; i++ } // 函数类型参数里的嵌套标注 (x: number)，跳过冒号继续吃
                    ch.isLetterOrDigit() || ch == '_' || ch == '$' || ch == '.' || ch == '-' -> { started = true; i++ }
                    ch == '|' || ch == '&' -> { started = true; i++ }
                    // `=>`：括号未配平（类型内部）或函数类型（以 `(` 开头且括号已配平）→ 属于类型；
                    // 否则是箭头函数本体，绝不能吃。
                    ch == '=' && peek(1) == '>' -> {
                        if (paren > 0 || angle > 0 || brace > 0 || bracket > 0 || firstIsParen) {
                            i += 2
                            if (paren == 0 && angle == 0 && brace == 0 && bracket == 0) {
                                // 顶层函数类型的 `=>`：后面跟的是返回类型，重置状态让它继续被吃
                                started = false; firstIsParen = false
                            } else started = true
                        } else break
                    }
                    ch.isWhitespace() -> {
                        var k = i
                        while (k < n && ts[k].isWhitespace()) k++
                        val nc = ts.getOrNull(k) ?: break
                        if (!started) {
                            // 类型还没开始：遇到语句/参数边界说明这不是标注上下文 → 放弃
                            if (nc == ',' || nc == ';' || nc == ')' || nc == '}' || nc == ']' || nc == '=' || nc == '\n') break
                            i = k; continue
                        }
                        // 已开始：只有这些延续类型表达式
                        if (angle > 0 || paren > 0 || brace > 0 || bracket > 0) { i = k; continue }
                        if (nc == '|' || nc == '&' || nc == '?') { i = k; continue }
                        // `=>`：非函数类型（firstIsParen=false）且括号已配平 → 箭头函数本体，停
                        if (nc == '=' && ts.getOrNull(k + 1) == '>' && !firstIsParen) break
                        if (nc == '=' && ts.getOrNull(k + 1) == '>') { i = k; continue } // 函数类型的箭头
                        if (nc == 'e' && ts.regionMatches(k, "extends", 0, 7)) { i = k; continue }
                        break
                    }
                    ch == '=' && peek(1) != '=' -> break // 默认值/赋值，保留
                    else -> break
                }
            }
            trimTrailingWs()
        }

        /** `<...>` 配平后是否紧跟 `(` 或 `{`（函数/类声明泛型）；内含 `=>` 视为表达式不删。 */
        fun genericDeclClosesToParen(): Boolean {
            var angle = 0
            var k = i
            var guard = 0
            while (k < n && guard++ < 300) {
                when (ts[k]) {
                    '<' -> angle++
                    '>' -> {
                        angle--
                        if (angle == 0) {
                            var m = k + 1
                            while (m < n && ts[m].isWhitespace()) m++
                            return ts.getOrNull(m) == '(' || ts.getOrNull(m) == '{'
                        }
                    }
                    ';', '{' -> return false
                    '=' -> if (ts.getOrNull(k + 1) == '>') return false
                }
                k++
            }
            return false
        }

        fun skipGenericDecl() {
            var angle = 0
            while (i < n) {
                if (ts[i] == '<') angle++
                if (ts[i] == '>') { angle--; if (angle == 0) { i++; return } }
                i++
            }
        }

        /* ===================== ESM → CommonJS ===================== */

        fun skipWs() { while (i < n && ts[i].isWhitespace()) i++ }

        fun readIdent(): String {
            skipWs()
            val s = i
            while (i < n && (ts[i].isLetterOrDigit() || ts[i] == '_' || ts[i] == '$')) i++
            return ts.substring(s, i)
        }

        fun qStr(s: String): String =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

        /** 读一个模块说明符字符串（i 在引号上），返回内容并越过结尾引号。 */
        fun readModuleString(): String {
            val q = ts[i]; i++
            val s = i
            while (i < n && ts[i] != q) i++
            val mod = ts.substring(s, i)
            if (i < n) i++
            return mod
        }

        /** `import ...`：default / named(as 别名) / * as ns / 副作用 / type-only → require 系列。 */
        fun transpileImport() {
            i += 6
            skipWs()
            // import type ... / import "./m"
            if (startsWithWord("type")) { skipToLineEnd(); return }
            if (i < n && (ts[i] == '"' || ts[i] == '\'')) {
                val mod = readModuleString()
                if (i < n && ts[i] == ';') i++
                out.append("require(").append(qStr(mod)).append(");")
                return
            }
            var defaultName: String? = null
            var nsName: String? = null
            val named = ArrayList<Pair<String, String>>() // 模块名 → 本地名
            while (i < n) {
                val c = ts[i]
                when {
                    c.isWhitespace() -> i++
                    c == ',' -> i++
                    c == '{' -> {
                        i++
                        while (i < n && ts[i] != '}') {
                            skipWs()
                            while (i < n && ts[i] == ',') { i++; skipWs() }
                            if (i >= n || ts[i] == '}') break
                            if (startsWithWord("type")) { // { type X } 类型导入：跳过名字
                                i += 4; skipWs()
                                readIdent()
                                if (startsWithWord("as")) { skipWs(); readIdent() }
                                continue
                            }
                            val nm = readIdent()
                            var local = nm
                            skipWs()
                            if (startsWithWord("as")) { i += 2; local = readIdent() }
                            if (nm.isNotEmpty()) named.add(nm to local)
                        }
                        if (i < n) i++ // '}'
                    }
                    c == '*' -> {
                        i++
                        skipWs()
                        if (startsWithWord("as")) { i += 2; nsName = readIdent() }
                    }
                    startsWithWord("from") -> i += 4
                    c == '"' || c == '\'' -> {
                        val mod = readModuleString()
                        if (i < n && ts[i] == ';') i++
                        emitImport(mod, defaultName, nsName, named)
                        return
                    }
                    else -> {
                        val id = readIdent()
                        if (id.isEmpty()) i++ // 防御：无法识别的字符强制前进，绝不死循环
                        else defaultName = id
                    }
                }
            }
        }

        fun emitImport(mod: String, defaultName: String?, nsName: String?, named: List<Pair<String, String>>) {
            val m = "__imp" + (impCounter++)
            out.append("var ").append(m).append(" = require(").append(qStr(mod)).append(");")
            if (defaultName != null) {
                out.append("var ").append(defaultName).append(" = (").append(m).append(" && ").append(m)
                    .append(".default !== undefined) ? ").append(m).append(".default : ").append(m).append(";")
            }
            if (nsName != null) out.append("var ").append(nsName).append(" = ").append(m).append(";")
            named.forEach { (nm, local) ->
                out.append("var ").append(local).append(" = ").append(m).append(".").append(nm).append(";")
            }
        }

        /** `export ...`：type/interface/declare 删；default/const·let·var/function/class/花括号列表/星号再导出 → module.exports。 */
        fun transpileExport() {
            i += 6
            skipWs()
            when {
                startsWithWord("type") -> skipToLineEnd()
                startsWithWord("declare") -> skipToLineEnd()
                // 不消费：交给主循环的 interface / enum 分支整块处理
                startsWithWord("interface") -> return
                startsWithWord("enum") -> return
                startsWithWord("const") && isConstEnumAhead() -> return
                startsWithWord("default") -> {
                    i += 7
                    skipWs()
                    // 函数/类声明在 `=` 后按表达式解析（module.exports.default = function/class ...）
                    out.append("module.exports.default = ")
                }
                startsWithWord("const") -> transpileExportVar(5)
                startsWithWord("var") -> transpileExportVar(3)
                startsWithWord("let") -> transpileExportVar(3)
                startsWithWord("function") -> {
                    // 函数声明有提升：先挂 exports 再让声明原样通过
                    peekNameAfter("function")?.let { nm ->
                        out.append("module.exports.").append(nm).append(" = ").append(nm).append(";")
                    }
                }
                startsWithWord("async") -> {
                    // async function f(){} 同样提升
                    var k = i + 5
                    while (k < n && ts[k].isWhitespace()) k++
                    if (ts.regionMatches(k, "function", 0, 8)) {
                        k += 8
                        while (k < n && ts[k].isWhitespace()) k++
                        val s = k
                        while (k < n && (ts[k].isLetterOrDigit() || ts[k] == '_' || ts[k] == '$')) k++
                        if (k > s) {
                            val nm = ts.substring(s, k)
                            out.append("module.exports.").append(nm).append(" = ").append(nm).append(";")
                        }
                    }
                }
                startsWithWord("class") -> {
                    // class 无提升：转成表达式赋值 module.exports.C = class C {...}
                    peekNameAfter("class")?.let { nm -> out.append("module.exports.").append(nm).append(" = ") }
                }
                i < n && ts[i] == '{' -> transpileExportBraces()
                i < n && ts[i] == '*' -> transpileExportStar()
                else -> { /* 未知形式：吞掉 export 关键字，后续原样输出 */ }
            }
        }

        /** 从 i 处关键字后窥探标识符名（不移动 i）。 */
        fun peekNameAfter(kw: String): String? {
            var k = i + kw.length
            while (k < n && ts[k].isWhitespace()) k++
            val s = k
            while (k < n && (ts[k].isLetterOrDigit() || ts[k] == '_' || ts[k] == '$')) k++
            return if (k > s) ts.substring(s, k) else null
        }

        /** `export const|let|var a = e, b = e2` → 声明原样 + module.exports.a = a; ... */
        fun transpileExportVar(kwLen: Int) {
            val kw = ts.substring(i, i + kwLen)
            i += kwLen
            val names = ArrayList<String>()
            val decl = StringBuilder().append(kw).append(' ')
            while (true) {
                skipWs()
                val name = readIdent()
                if (name.isEmpty()) break
                names.add(name)
                decl.append(name)
                // 名字与 '=' 之间可能有类型标注（export const x: T = e）——走标注剥离逻辑
                while (i < n && ts[i] != '=' && ts[i] != ',' && ts[i] != ';' && ts[i] != '\n') {
                    if (ts[i] == ':') { skipTypeAnnotation(); skipWs(); break }
                    i++
                }
                if (i < n && ts[i] == '=') {
                    decl.append(" =")
                    i++
                    appendStrippedExpr(decl)
                }
                if (i < n && ts[i] == ',') { decl.append(", "); i++; continue }
                break
            }
            if (i < n && ts[i] == ';') i++
            out.append(decl).append(';')
            names.forEach { out.append("module.exports.").append(it).append(" = ").append(it).append(';') }
        }

        /** 提取初始化表达式区间（到顶层 `,` / `;` / 行尾），**递归转译**后追加。
         *  递归而非裸拷贝的原因：表达式里可能有箭头函数参数/返回类型标注
         *  （`export const f = (x: number): number => {...}`），裸拷贝会把 `: number` 带进 JS。 */
        fun appendStrippedExpr(decl: StringBuilder) {
            val start = i
            var par = 0; var br = 0; var bk = 0
            while (i < n) {
                val c = ts[i]
                when {
                    c == '"' || c == '\'' || c == '`' -> { val q = c; i++; while (i < n) { if (ts[i] == '\\') i += 2 else if (ts[i] == q) { i++; break } else i++ } }
                    c == '/' && peek(1) == '/' -> while (i < n && ts[i] != '\n') i++
                    c == '/' && peek(1) == '*' -> { i += 2; while (i < n && !(ts[i] == '*' && peek(1) == '/')) i++; i += 2 }
                    c == '(' -> { par++; i++ }
                    c == ')' -> { par--; i++ }
                    c == '{' -> { br++; i++ }
                    c == '}' -> { br--; i++ }
                    c == '[' -> { bk++; i++ }
                    c == ']' -> { bk--; i++ }
                    (c == ',' || c == ';' || c == '\n') && par == 0 && br == 0 && bk == 0 -> {
                        decl.append(transpile(ts.substring(start, i)))
                        return
                    }
                    else -> i++
                }
            }
            decl.append(transpile(ts.substring(start, i)))
        }

        /** `export { a, b as c } [from "m"]`。 */
        fun transpileExportBraces() {
            i++ // '{'
            val items = ArrayList<Pair<String, String>>() // 本地名 → 导出名
            while (i < n && ts[i] != '}') {
                skipWs()
                while (i < n && ts[i] == ',') { i++; skipWs() }
                if (i >= n || ts[i] == '}') break
                if (startsWithWord("type")) { // { type A }：类型导出，跳过
                    i += 4; skipWs()
                    readIdent()
                    if (startsWithWord("as")) { skipWs(); readIdent() }
                    continue
                }
                val nm = readIdent()
                var exportedAs = nm
                skipWs()
                if (startsWithWord("as")) { i += 2; exportedAs = readIdent() }
                if (nm.isNotEmpty()) items.add(nm to exportedAs)
            }
            if (i < n) i++ // '}'
            skipWs()
            if (startsWithWord("from")) {
                i += 4
                skipWs()
                if (i < n && (ts[i] == '"' || ts[i] == '\'')) {
                    val mod = readModuleString()
                    val m = "__exp" + (expCounter++)
                    out.append("var ").append(m).append(" = require(").append(qStr(mod)).append(");")
                    items.forEach { (local, exp) ->
                        out.append("module.exports.").append(exp).append(" = ").append(m).append(".").append(local).append(";")
                    }
                }
            } else {
                items.forEach { (local, exp) ->
                    out.append("module.exports.").append(exp).append(" = ").append(local).append(";")
                }
            }
            if (i < n && ts[i] == ';') i++
        }

        /** `export * [as ns] from "m"`。 */
        fun transpileExportStar() {
            i++ // '*'
            skipWs()
            var asName: String? = null
            if (startsWithWord("as")) { i += 2; asName = readIdent(); skipWs() }
            if (startsWithWord("from")) {
                i += 4
                skipWs()
                if (i < n && (ts[i] == '"' || ts[i] == '\'')) {
                    val mod = readModuleString()
                    if (asName != null) {
                        out.append("module.exports.").append(asName).append(" = require(").append(qStr(mod)).append(");")
                    } else {
                        out.append("Object.assign(module.exports, require(").append(qStr(mod)).append("));")
                    }
                }
            }
            if (i < n && ts[i] == ';') i++
        }

        /* ---------- enum ---------- */
        fun emitEnum() {
            i += 4
            while (i < n && ts[i] == ' ') i++
            if (ts.regionMatches(i, "const", 0, 5) && !(ts.getOrNull(i + 5)?.isLetterOrDigit() ?: true)) i += 5
            while (i < n && ts[i] == ' ') i++
            val nameStart = i
            while (i < n && (ts[i].isLetterOrDigit() || ts[i] == '_' || ts[i] == '$')) i++
            val name = ts.substring(nameStart, i)
            while (i < n && ts[i] != '{') { if (ts[i] == ';' || ts[i] == '\n') { out.append(name); return }; i++ }
            i++ // '{'
            val members = ArrayList<Pair<String, String>>()
            var autoVal = 0
            while (i < n && ts[i] != '}') {
                while (i < n && (ts[i] == ' ' || ts[i] == '\n' || ts[i] == '\r' || ts[i] == ',' || ts[i] == '\t')) i++
                if (i >= n || ts[i] == '}') break
                val ms = i
                while (i < n && (ts[i].isLetterOrDigit() || ts[i] == '_' || ts[i] == '$')) i++
                val member = ts.substring(ms, i)
                while (i < n && ts[i] == ' ') i++
                var value = ""
                if (i < n && ts[i] == '=') {
                    i++
                    while (i < n && ts[i] == ' ') i++
                    val vs = i
                    while (i < n) {
                        val ch = ts[i]
                        if (ch == '"' || ch == '\'') { val q = ch; i++; while (i < n) { if (ts[i] == '\\') i += 2 else if (ts[i] == q) { i++; break } else i++ }; continue }
                        if (ch == ',' || ch == '\n' || ch == '}') break
                        i++
                    }
                    value = ts.substring(vs, i).trim()
                }
                if (member.isNotEmpty()) {
                    if (value.isEmpty()) { value = autoVal.toString(); autoVal++ }
                    else value.toIntOrNull()?.let { autoVal = it + 1 }
                    members.add(member to value)
                }
            }
            if (i < n) i++ // '}'
            // var E; (function(E){E[E["A"]=0]="A";...})(E = E || {});
            out.append("var ").append(name).append("; (function(").append(name).append("){")
            members.forEach { (m, v) ->
                val isStr = v.startsWith("\"") || v.startsWith("'")
                if (isStr) {
                    out.append(name).append("[\"").append(m).append("\"]=").append(v).append(";")
                } else {
                    out.append(name).append("[").append(name).append("[\"").append(m).append("\"]=").append(v).append("]").append("]=").append("\"").append(m).append("\";")
                }
            }
            out.append("})(").append(name).append(" = ").append(name).append(" || {});")
        }
    }
}
