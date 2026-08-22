package com.ai.assistance.quro.core.terminal

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 多语言运行器
 * 
 * 支持JavaScript、Python、HTML、JSON、CSS、XML、C/C++/Java的运行和渲染
 */
class QuroLanguageRunner(private val context: Context) {
    
    /**
     * 语言类型
     */
    enum class Language {
        JAVASCRIPT,
        PYTHON,
        HTML,
        JSON,
        CSS,
        XML,
        C,
        CPP,
        JAVA,
        UNKNOWN
    }
    
    /**
     * 运行结果
     */
    data class RunResult(
        val success: Boolean,
        val output: String,
        val error: String? = null,
        val htmlOutput: String? = null
    )
    
    /**
     * 检测语言类型
     */
    fun detectLanguage(code: String, filename: String? = null): Language {
        // 根据文件扩展名检测
        if (filename != null) {
            when {
                filename.endsWith(".js") -> return Language.JAVASCRIPT
                filename.endsWith(".py") -> return Language.PYTHON
                filename.endsWith(".html") || filename.endsWith(".htm") -> return Language.HTML
                filename.endsWith(".json") -> return Language.JSON
                filename.endsWith(".css") -> return Language.CSS
                filename.endsWith(".xml") -> return Language.XML
                filename.endsWith(".c") -> return Language.C
                filename.endsWith(".cpp") || filename.endsWith(".cc") -> return Language.CPP
                filename.endsWith(".java") -> return Language.JAVA
            }
        }
        
        // 根据内容特征检测
        val trimmed = code.trim()
        when {
            trimmed.startsWith("<!") || trimmed.startsWith("<html") || trimmed.startsWith("<div") -> return Language.HTML
            trimmed.startsWith("{") && trimmed.endsWith("}") -> return Language.JSON
            trimmed.startsWith("[") && trimmed.endsWith("]") -> return Language.JSON
            trimmed.contains("function ") && trimmed.contains("=>") -> return Language.JAVASCRIPT
            trimmed.contains("def ") && trimmed.contains(":") -> return Language.PYTHON
            trimmed.contains("class ") && trimmed.contains("public static void main") -> return Language.JAVA
            trimmed.contains("#include") -> return Language.C
            trimmed.contains("int main()") || trimmed.contains("void main()") -> return Language.C
        }
        
        return Language.UNKNOWN
    }
    
    /**
     * 运行代码
     */
    suspend fun runCode(
        code: String,
        language: Language,
        filename: String? = null
    ): RunResult = withContext(Dispatchers.IO) {
        when (language) {
            Language.HTML -> runHtml(code)
            Language.JAVASCRIPT -> runJavaScript(code)
            Language.PYTHON -> runPython(code)
            Language.JSON -> runJson(code)
            Language.CSS -> runCss(code)
            Language.XML -> runXml(code)
            Language.C, Language.CPP, Language.JAVA -> runCompiledLanguage(code, language)
            Language.UNKNOWN -> RunResult(false, "未知语言类型")
        }
    }
    
    /**
     * 运行HTML
     */
    private fun runHtml(code: String): RunResult {
        return RunResult(
            success = true,
            output = "HTML渲染中...",
            htmlOutput = code
        )
    }
    
    /**
     * 运行JavaScript
     */
    private fun runJavaScript(code: String): RunResult {
        return try {
            // 使用WebView执行JavaScript
            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>JavaScript输出</title>
                    <style>
                        body { font-family: monospace; background: #1e1e1e; color: #d4d4d4; padding: 20px; }
                        .output { background: #252526; border: 1px solid #3c3c3c; border-radius: 4px; padding: 10px; margin: 10px 0; }
                        .error { color: #f44747; }
                        .log { color: #dcdcaa; }
                    </style>
                </head>
                <body>
                    <div id="output" class="output">JavaScript输出:</div>
                    <script>
                        const output = document.getElementById('output');
                        const originalLog = console.log;
                        console.log = function(...args) {
                            originalLog.apply(console, args);
                            output.innerHTML += '<div class="log">' + args.join(' ') + '</div>';
                        };
                        try {
                            $code
                        } catch (e) {
                            output.innerHTML += '<div class="error">错误: ' + e.message + '</div>';
                        }
                    </script>
                </body>
                </html>
            """.trimIndent()
            
            RunResult(
                success = true,
                output = "JavaScript执行中...",
                htmlOutput = html
            )
        } catch (e: Exception) {
            RunResult(false, "JavaScript执行失败", e.message)
        }
    }
    
    /**
     * 运行Python
     */
    private fun runPython(code: String): RunResult {
        return try {
            // 这里应该调用Python解释器
            // 由于Android环境限制，可能需要使用Chaquopy或类似方案
            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>Python输出</title>
                    <style>
                        body { font-family: monospace; background: #1e1e1e; color: #d4d4d4; padding: 20px; }
                        .output { background: #252526; border: 1px solid #3c3c3c; border-radius: 4px; padding: 10px; margin: 10px 0; }
                        .code { color: #9cdcfe; }
                    </style>
                </head>
                <body>
                    <div class="output">
                        <div>Python代码:</div>
                        <pre class="code">$code</pre>
                    </div>
                    <div class="output">
                        <div>输出:</div>
                        <div>Python解释器需要集成</div>
                    </div>
                </body>
                </html>
            """.trimIndent()
            
            RunResult(
                success = true,
                output = "Python执行中...",
                htmlOutput = html
            )
        } catch (e: Exception) {
            RunResult(false, "Python执行失败", e.message)
        }
    }
    
    /**
     * 运行JSON
     */
    private fun runJson(code: String): RunResult {
        return try {
            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>JSON格式化</title>
                    <style>
                        body { font-family: monospace; background: #1e1e1e; color: #d4d4d4; padding: 20px; }
                        .json { background: #252526; border: 1px solid #3c3c3c; border-radius: 4px; padding: 10px; margin: 10px 0; }
                        .key { color: #9cdcfe; }
                        .string { color: #ce9178; }
                        .number { color: #b5cea8; }
                        .boolean { color: #569cd6; }
                    </style>
                </head>
                <body>
                    <div class="json">
                        <div>JSON格式化输出:</div>
                        <pre>${formatJson(code)}</pre>
                    </div>
                </body>
                </html>
            """.trimIndent()
            
            RunResult(
                success = true,
                output = "JSON格式化中...",
                htmlOutput = html
            )
        } catch (e: Exception) {
            RunResult(false, "JSON格式化失败", e.message)
        }
    }
    
    /**
     * 运行CSS
     */
    private fun runCss(code: String): RunResult {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>CSS预览</title>
                <style>
                    body { font-family: monospace; background: #1e1e1e; color: #d4d4d4; padding: 20px; }
                    .preview { background: #ffffff; border: 1px solid #3c3c3c; border-radius: 4px; padding: 20px; margin: 10px 0; }
                </style>
                $code
            </head>
            <body>
                <div class="preview">
                    <h1>CSS预览</h1>
                    <p>这是一个预览区域，应用了上面的CSS样式</p>
                    <button>按钮</button>
                    <input type="text" placeholder="输入框">
                </div>
            </body>
            </html>
        """.trimIndent()
        
        return RunResult(
            success = true,
            output = "CSS预览中...",
            htmlOutput = html
        )
    }
    
    /**
     * 运行XML
     */
    private fun runXml(code: String): RunResult {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>XML格式化</title>
                <style>
                    body { font-family: monospace; background: #1e1e1e; color: #d4d4d4; padding: 20px; }
                    .xml { background: #252526; border: 1px solid #3c3c3c; border-radius: 4px; padding: 10px; margin: 10px 0; }
                </style>
            </head>
            <body>
                <div class="xml">
                    <div>XML格式化输出:</div>
                    <pre>${formatXml(code)}</pre>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        return RunResult(
            success = true,
            output = "XML格式化中...",
            htmlOutput = html
        )
    }
    
    /**
     * 运行编译型语言（C/C++/Java）
     */
    private fun runCompiledLanguage(code: String, language: Language): RunResult {
        val langName = when (language) {
            Language.C -> "C"
            Language.CPP -> "C++"
            Language.JAVA -> "Java"
            else -> "未知语言"
        }
        
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>${langName}代码</title>
                <style>
                    body { font-family: monospace; background: #1e1e1e; color: #d4d4d4; padding: 20px; }
                    .code { background: #252526; border: 1px solid #3c3c3c; border-radius: 4px; padding: 10px; margin: 10px 0; }
                    .keyword { color: #569cd6; }
                    .string { color: #ce9178; }
                    .comment { color: #6a9955; }
                </style>
            </head>
            <body>
                <div class="code">
                    <div>${langName}代码:</div>
                    <pre>${highlightSyntax(code, language)}</pre>
                </div>
                <div class="code">
                    <div>编译运行需要编译器环境</div>
                    <div>在终端中运行: ${getCompileCommand(language)}</div>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        return RunResult(
            success = true,
            output = "${langName}代码显示中...",
            htmlOutput = html
        )
    }
    
    /**
     * 格式化JSON
     */
    private fun formatJson(json: String): String {
        return try {
            // 简单的JSON格式化
            val formatted = StringBuilder()
            var indent = 0
            var inString = false
            var escape = false
            
            for (char in json) {
                when {
                    escape -> {
                        formatted.append(char)
                        escape = false
                    }
                    char == '\\' && inString -> {
                        formatted.append(char)
                        escape = true
                    }
                    char == '"' -> {
                        formatted.append(char)
                        inString = !inString
                    }
                    !inString && char == '{' || char == '[' -> {
                        formatted.append(char)
                        formatted.append('\n')
                        indent++
                        formatted.append("  ".repeat(indent))
                    }
                    !inString && char == '}' || char == ']' -> {
                        formatted.append('\n')
                        indent--
                        formatted.append("  ".repeat(indent))
                        formatted.append(char)
                    }
                    !inString && char == ',' -> {
                        formatted.append(char)
                        formatted.append('\n')
                        formatted.append("  ".repeat(indent))
                    }
                    !inString && char == ':' -> {
                        formatted.append(": ")
                    }
                    else -> formatted.append(char)
                }
            }
            formatted.toString()
        } catch (e: Exception) {
            json
        }
    }
    
    /**
     * 格式化XML
     */
    private fun formatXml(xml: String): String {
        return try {
            // 简单的XML格式化
            val formatted = StringBuilder()
            var indent = 0
            val lines = xml.split("\n")
            
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                
                when {
                    trimmed.startsWith("</") -> {
                        indent--
                        formatted.append("  ".repeat(indent))
                        formatted.append(trimmed)
                        formatted.append('\n')
                    }
                    trimmed.startsWith("<") && trimmed.endsWith("/>") -> {
                        formatted.append("  ".repeat(indent))
                        formatted.append(trimmed)
                        formatted.append('\n')
                    }
                    trimmed.startsWith("<") && !trimmed.startsWith("<?") -> {
                        formatted.append("  ".repeat(indent))
                        formatted.append(trimmed)
                        formatted.append('\n')
                        indent++
                    }
                    else -> {
                        formatted.append("  ".repeat(indent))
                        formatted.append(trimmed)
                        formatted.append('\n')
                    }
                }
            }
            formatted.toString()
        } catch (e: Exception) {
            xml
        }
    }
    
    /**
     * 语法高亮
     */
    private fun highlightSyntax(code: String, language: Language): String {
        var result = code
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        
        when (language) {
            Language.C, Language.CPP -> {
                result = result.replace(Regex("\\b(int|char|float|double|void|if|else|for|while|do|switch|case|break|continue|return|struct|class|public|private|protected|static|void|int|char|float|double)\\b"), 
                    "<span class=\"keyword\">$1</span>")
                result = result.replace(Regex("//.*$"), "<span class=\"comment\">$0</span>")
            }
            Language.JAVA -> {
                result = result.replace(Regex("\\b(public|private|protected|static|void|int|char|float|double|class|interface|extends|implements|new|return|if|else|for|while|do|switch|case|break|continue)\\b"),
                    "<span class=\"keyword\">$1</span>")
                result = result.replace(Regex("//.*$"), "<span class=\"comment\">$0</span>")
            }
            else -> {}
        }
        
        return result
    }
    
    /**
     * 获取编译命令
     */
    private fun getCompileCommand(language: Language): String {
        return when (language) {
            Language.C -> "gcc -o program program.c && ./program"
            Language.CPP -> "g++ -o program program.cpp && ./program"
            Language.JAVA -> "javac Program.java && java Program"
            else -> ""
        }
    }
}