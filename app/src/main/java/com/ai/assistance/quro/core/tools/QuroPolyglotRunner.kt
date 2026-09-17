package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 在 proot Ubuntu 容器内真跑 5 种语言代码：javascript / python / ruby / go / rust。
 * python 复用 python_run；其余在容器内安装并运行对应解释器/编译器（写入层已挂载，安装一次后续复用）。
 */
class QuroPolyglotRunner : QuroTool {
    override val name = "polyglot_run"
    override val description = "在 proot Ubuntu 容器内真跑 5 种语言代码：javascript(node) / python3 / ruby / go / rust。" +
        "参数 {\"language\":\"javascript|python|ruby|go|rust\",\"code\":\"源码（必填）\",\"timeout_ms\":30000}。" +
        "python 复用 python_run；其余在容器内自动安装对应解释器/编译器（首次较慢）。支持多行代码（写入临时文件执行）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "language":{"type":"string","description":"语言：javascript | python | ruby | go | rust"},
            "code":{"type":"string","description":"源码（必填）"},
            "timeout_ms":{"type":"integer","description":"超时毫秒，默认 30000，最大 120000"}
        },
        "required":["language","code"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val lang = jo.optString("language", "").lowercase().trim()
        val code = jo.optString("code", "").trim()
        if (lang.isEmpty()) return "❌ 缺少 language（javascript/python/ruby/go/rust）"
        if (code.isEmpty()) return "❌ 缺少 code"
        val timeout = jo.optInt("timeout_ms", 30000).coerceIn(1000, 120000)
        return runBlocking {
            when (lang) {
                "python" -> QuroToolRegistry.active?.get("python_run")?.run(
                    context, JSONObject().apply { put("code", code); put("timeout_ms", timeout) }.toString()
                ) ?: "❌ python_run 不可用"
                else -> runInProot(context, lang, code, timeout)
            }
        }
    }

    private fun runInProot(context: Context, lang: String, code: String, timeout: Int): String {
        val (bin, pkg, ext) = when (lang) {
            "javascript" -> Triple("node", "nodejs", "js")
            "ruby" -> Triple("ruby", "ruby", "rb")
            "go" -> Triple("go", "golang-go", "go")
            "rust" -> Triple("rustc", "rustc", "rs")
            else -> return "❌ 不支持的语言：$lang（支持 javascript/python/ruby/go/rust）"
        }
        val ensure = "command -v $bin >/dev/null 2>&1 || { apt-get update -qq >/dev/null 2>&1 && apt-get install -y $pkg >/dev/null 2>&1; }"
        val runCmd = when (lang) {
            "javascript" -> "$bin /tmp/poly.$ext"
            "ruby" -> "$bin /tmp/poly.$ext"
            "go" -> "cd /tmp && $bin run poly.$ext"
            "rust" -> "cd /tmp && $bin poly.$ext -o /tmp/poly_rs_bin >/dev/null 2>&1 && /tmp/poly_rs_bin"
            else -> ""
        }
        // 用 quoted heredoc 写入源码，任意特殊字符（引号/反引号/反斜杠）都按字面量处理
        val full = "$ensure\ncat > /tmp/poly.$ext <<'QURO_EOF'\n$code\nQURO_EOF\n$runCmd"
        val (rc, out) = QuroLinuxEnv.run(context, full, timeout.toLong() + 2000L)
        return "exit=$rc\n$out"
    }
}
