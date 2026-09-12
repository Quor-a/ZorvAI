package com.ai.assistance.quro.genui.app.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.app.ui.theme.GenTheme

/**
 * 代码块呈现实体 —— Kotlin / Java / C++ / Python 等"端上跑不了、但可以看"的语言，
 * 端上统一渲染成**有质感的代码视图**（行号 + 语法高亮 + 可复制 + 可横向滚动）。
 *
 * 定位（重要）：
 * 这是**兜底**，不是**规范**。提示词里 AI 被鼓励用自己的 HTML 画代码视图
 * （想要什么风格就什么风格），端上只在 AI 直接甩出一段裸代码块时接管。
 * 这样既不限制 AI 自由发挥，也不会出现"一段没排版的裸代码"这种粗糙结果。
 *
 * 为什么不引第三方高亮库：
 * 高亮只需要"把关键字染个色"。引一个编译时注解处理器/大依赖，为了四个语言的
 * 正则染色，不划算 —— 这里用极小的高亮器，零依赖、可控、不增加包体。
 */
@Composable
fun CodeBlockView(
    block: RenderChannel.CodeBlock,
    modifier: Modifier = Modifier
) {
    val lang = block.lang.lowercase()
    val stack = CodeLangRegistry.byId(lang)
    val label = stack?.name?.substringBefore("（") ?: lang.uppercase()

    val lines = remember(block.code) { block.code.lines() }
    val highlighted = remember(block.code) { CodeHighlighter.highlight(block.code, lang) }

    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    var copied by remember { mutableStateOf(false) }

    val ctx = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier
            .fillMaxWidth()
            .background(GenTheme.Panel)
            .border(1.dp, GenTheme.Line, RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp)
    ) {
        // —— 标题栏：语言标识 + 行数 + 复制 ——
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$label · ${lines.size} 行",
                color = GenTheme.AmberDim, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (copied) "已复制" else "复制",
                color = if (copied) GenTheme.Green else GenTheme.Dim,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clickable {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                        cm?.setPrimaryClip(
                            android.content.ClipData.newPlainText(label, block.code)
                        )
                        copied = true
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Spacer(Modifier.height(6.dp))

        // —— 代码主体：竖向滚动交给外层，横向滚动独立 ——
        // 长行必须能横向拖，否则包成多行会毁掉缩进（缩进是代码可读性的命）。
        Box(
            Modifier
                .fillMaxWidth()
                // 代码超长时内部滚动，避免一屏界面被一段代码撑爆
                .heightIn(max = 420.dp)
                .verticalScroll(vScroll)
                .horizontalScroll(hScroll)
        ) {
            SelectionContainer {
                Row(Modifier.padding(horizontal = 10.dp)) {
                    // 行号列
                    Column {
                        lines.indices.forEach { i ->
                            Text(
                                "${i + 1}",
                                color = GenTheme.Line.copy(alpha = 0.9f),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 19.sp
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    // 代码列
                    Text(
                        highlighted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 19.sp,
                        softWrap = false
                    )
                }
            }
        }
    }
}

/**
 * 极小语法高亮器。
 *
 * 策略：先按语言取一组关键字表，再用**单趟扫描**染色 —— 注释 > 字符串 > 关键字 > 数字，
 * 顺序很重要：注释和字符串里的关键字不能被染色（否则 `// fun main` 里的 fun 会被点亮）。
 *
 * 实现方式：用一个"屏蔽区"记录已决定的区间，后续规则跳过已占据的字符。
 * 这是最朴素但正确的做法，对代码长度（通常几十到几百行）完全够用。
 */
private object CodeHighlighter {

    private val KEYWORDS = mapOf(
        "kotlin" to setOf(
            "fun", "val", "var", "class", "object", "interface", "data", "sealed", "enum",
            "when", "if", "else", "for", "while", "return", "break", "continue", "in", "is",
            "as", "suspend", "override", "open", "abstract", "private", "public", "internal",
            "protected", "companion", "init", "constructor", "import", "package", "try",
            "catch", "finally", "throw", "null", "true", "false", "this", "super", "by",
            "lazy", "it", "typealias", "where", "out", "vararg", "lateinit", "const"
        ),
        "java" to setOf(
            "public", "private", "protected", "static", "final", "class", "interface", "enum",
            "extends", "implements", "abstract", "void", "int", "long", "double", "float",
            "boolean", "char", "byte", "short", "new", "this", "super", "return", "if",
            "else", "for", "while", "do", "switch", "case", "default", "break", "continue",
            "try", "catch", "finally", "throw", "throws", "synchronized", "import", "package",
            "null", "true", "false", "instanceof", "record", "var", "yield"
        ),
        "cpp" to setOf(
            "include", "define", "ifndef", "endif", "pragma", "namespace", "using", "extern",
            "class", "struct", "union", "enum", "template", "typename", "public", "private",
            "protected", "virtual", "override", "const", "constexpr", "static", "inline",
            "void", "int", "long", "double", "float", "char", "bool", "auto", "return", "if",
            "else", "for", "while", "do", "switch", "case", "break", "continue", "new",
            "delete", "nullptr", "true", "false", "sizeof", "static_cast", "reinterpret_cast",
            "JNIEXPORT", "JNICALL", "JNIEnv", "jobject", "jstring", "jint", "std"
        ),
        "python" to setOf(
            "def", "class", "import", "from", "as", "return", "yield", "if", "elif", "else",
            "for", "while", "break", "continue", "try", "except", "finally", "raise", "with",
            "lambda", "global", "nonlocal", "pass", "async", "await", "in", "is", "not",
            "and", "or", "None", "True", "False", "self", "match", "case"
        ),
        "xml" to setOf("xml", "layout", "view", "true", "false", "match_parent", "wrap_content")
    )

    private fun keywordsOf(lang: String): Set<String> =
        KEYWORDS[lang] ?: KEYWORDS["kotlin"]!!

    /** 各语言的单行注释前缀 */
    private fun lineComment(lang: String): String = when (lang) {
        "python" -> "#"
        "cpp" -> "//"
        else -> "//"
    }

    fun highlight(code: String, lang: String): AnnotatedString {
        val kw = keywordsOf(lang)
        val lc = lineComment(lang)
        val n = code.length

        // 决定每个字符的样式类别：0=普通 1=注释 2=字符串 3=关键字 4=数字
        val kinds = IntArray(n)
        var i = 0
        while (i < n) {
            val c = code[i]
            // 注释（优先级最高）
            if (i + lc.length <= n && code.startsWith(lc, i)) {
                val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                for (k in i until end) kinds[k] = 1
                i = end
                continue
            }
            // 块注释 /* … */
            if (lang != "python" && i + 1 < n && c == '/' && code[i + 1] == '*') {
                val end = code.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                for (k in i until end) kinds[k] = 1
                i = end
                continue
            }
            // 字符串
            if (c == '"' || c == '\'') {
                var k = i + 1
                while (k < n && code[k] != c && code[k] != '\n') {
                    if (code[k] == '\\') k++
                    k++
                }
                val end = (k + 1).coerceAtMost(n)
                for (t in i until end) kinds[t] = 2
                i = end
                continue
            }
            // 数字
            if (c.isDigit()) {
                var k = i
                while (k < n && (code[k].isLetterOrDigit() || code[k] == '.' || code[k] == '_')) k++
                for (t in i until k) kinds[t] = 4
                i = k
                continue
            }
            // 标识符 → 关键字
            if (c.isLetter() || c == '_') {
                var k = i
                while (k < n && (code[k].isLetterOrDigit() || code[k] == '_')) k++
                val word = code.substring(i, k)
                if (word in kw) for (t in i until k) kinds[t] = 3
                i = k
                continue
            }
            i++
        }

        return buildAnnotatedString {
            var i = 0
            while (i < n) {
                val k = kinds[i]
                var j = i
                while (j < n && kinds[j] == k) j++
                val piece = code.substring(i, j)
                when (k) {
                    1 -> withStyle(SpanStyle(color = COMMENT)) { append(piece) }
                    2 -> withStyle(SpanStyle(color = STRING)) { append(piece) }
                    3 -> withStyle(SpanStyle(color = KEYWORD, fontWeight = FontWeight.Medium)) { append(piece) }
                    4 -> withStyle(SpanStyle(color = NUMBER)) { append(piece) }
                    else -> append(piece)
                }
                i = j
            }
        }
    }

    // 配色对暖黑稿纸底色做过对比度校准（不自创荧光色，避免刺眼）
    private val COMMENT = Color(0xFF6F6558)
    private val STRING  = Color(0xFF9BB267)
    private val KEYWORD = Color(0xFFE29A45)
    private val NUMBER  = Color(0xFFC98B6B)
}
