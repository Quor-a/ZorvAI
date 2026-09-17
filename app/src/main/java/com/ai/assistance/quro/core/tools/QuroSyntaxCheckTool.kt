package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * 语法/结构校验（对应「其他 AI」文件与编辑维 writer 的语法检查）。
 * - json / xml：端侧原生解析（org.json / XmlPullParser），零依赖、即时。
 * - yaml：轻量静态校验（缩进/注释/键值），非完整解析。
 * - python / javascript / ruby：经 proot 内对应解释器的语法专用检查
 *   （python3 -m py_compile / node --check / ruby -c），首次自动安装。
 * - go / rust / kotlin：端侧括号配平 + 关键字静态启发式（完整编译需在项目工程内执行）。
 */
class QuroSyntaxCheckTool : QuroTool {
    override val name = "syntax_check"
    override val description = "语法/结构校验：支持 json / xml / yaml(轻量) / python / javascript / ruby / go / rust / kotlin。" +
        "参数 {\"language\":\"语言（必填）\",\"code\":\"待校验文本（必填）,\"timeout_ms\":30000}。" +
        "json/xml 端侧解析；python/javascript/ruby 经 proot 解释器语法检查；go/rust/kotlin 端侧括号配平启发式。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "language":{"type":"string","description":"json | xml | yaml | python | javascript | ruby | go | rust | kotlin"},
            "code":{"type":"string","description":"待校验的源码/文本（必填）"},
            "timeout_ms":{"type":"integer","description":"超时毫秒，默认 30000，最大 120000"}
        },
        "required":["language","code"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val lang = jo.optString("language", "").trim().lowercase()
        val code = jo.optString("code", "").trim()
        if (lang.isEmpty()) return "❌ 缺少 language（json/xml/yaml/python/javascript/ruby/go/rust/kotlin）"
        if (code.isEmpty()) return "❌ 缺少 code"
        val timeout = jo.optInt("timeout_ms", 30000).coerceIn(1000, 120000)
        return when (lang) {
            "json" -> checkJson(code)
            "xml" -> checkXml(code)
            "yaml" -> checkYaml(code)
            "python", "javascript", "ruby" -> runBlocking { checkViaProot(context, lang, code, timeout) }
            "go", "rust", "kotlin" -> checkHeuristic(lang, code)
            else -> "❌ 不支持的语言：$lang（支持 json/xml/yaml/python/javascript/ruby/go/rust/kotlin）"
        }
    }

    private fun checkJson(code: String): String {
        return runCatching { JSONObject(code); "✅ JSON 合法" }.getOrElse {
            runCatching { JSONArray(code); "✅ JSON 数组合法" }.getOrElse { "❌ JSON 语法错误：${it.message}" }
        }
    }

    private fun checkXml(code: String): String {
        return runCatching {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(code))
            while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) { /* drain */ }
            "✅ XML 合法"
        }.getOrElse { "❌ XML 语法错误：${it.message}" }
    }

    private fun checkYaml(code: String): String {
        val lines = code.lines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        for (ln in lines) {
            val ind = ln.takeWhile { it == ' ' }.length
            if (ind % 2 != 0) return "⚠️ YAML 缩进建议为 2 的倍数（行首空格数=${ind}）：${ln.take(40)}"
            if (!ln.trim().contains(":") && !ln.trim().startsWith("-") && !ln.trim().matches(Regex("^[-+]\\s*\\S+")))
                return "⚠️ YAML 行缺少键值分隔 ':' 或列表 '-'：${ln.take(40)}"
        }
        return "✅ YAML 轻量校验通过（仅检查缩进/注释/键值，非完整解析）"
    }

    private fun checkViaProot(context: Context, lang: String, code: String, timeout: Int): String {
        val (bin, pkg, ext, check) = when (lang) {
            "python" -> Quad("python3", "python3", "py", "python3 -m py_compile")
            "javascript" -> Quad("node", "nodejs", "js", "node --check")
            "ruby" -> Quad("ruby", "ruby", "rb", "ruby -c")
            else -> return "❌ 不支持的语言：$lang"
        }
        val ensure = "command -v $bin >/dev/null 2>&1 || { apt-get update -qq >/dev/null 2>&1 && apt-get install -y $pkg >/dev/null 2>&1; }"
        val full = "$ensure\ncat > /tmp/syn.$ext <<'QURO_EOF'\n$code\nQURO_EOF\n$check /tmp/syn.$ext"
        val (rc, out) = QuroLinuxEnv.run(context, full, timeout.toLong() + 2000L)
        return if (rc == 0) "✅ $lang 语法检查通过" else "❌ $lang 语法错误（exit=$rc）：\n${out.take(2000)}"
    }

    private fun checkHeuristic(lang: String, code: String): String {
        val pairs = listOf('(' to ')', '[' to ']', '{' to '}')
        val stack = ArrayDeque<Char>()
        var inStr = false
        var strCh = ' '
        var prev = ' '
        for (c in code) {
            if (inStr) {
                if (c == '\\') { prev = c; continue }
                if (c == strCh) inStr = false
            } else {
                when (c) {
                    '"', '\'' -> { inStr = true; strCh = c }
                    '(', '[', '{' -> stack.addLast(c)
                    ')', ']', '}' -> {
                        val top = stack.removeLastOrNull()
                        val expect = pairs.first { it.second == c }.first
                        if (top != expect) return "❌ $lang 括号不配对：遇到 '$c' 但栈顶为 '$top'"
                    }
                }
            }
            prev = c
        }
        if (stack.isNotEmpty()) return "❌ $lang 括号未闭合，剩余：${stack.joinToString("")}"
        return "✅ $lang 静态启发式通过（括号配平）— 注：完整语义/类型检查需在项目工程内编译"
    }

    private data class Quad(val bin: String, val pkg: String, val ext: String, val check: String)
}
