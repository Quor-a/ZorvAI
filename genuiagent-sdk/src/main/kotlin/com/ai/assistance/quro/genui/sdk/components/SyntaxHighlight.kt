package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.material3.minimumInteractiveComponentSize

import com.ai.assistance.quro.genui.sdk.style.ZorvPalette

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 零依赖语法高亮引擎（Vercel AI SDK 式代码体验）：
 * 关键字 / 字符串 / 注释 / 数字 / 函数名 / 类型 着色，AnnotatedString 直渲。
 * 语言：kotlin java python js/ts json xml sql go rust c# shell 通用。
 */
object SyntaxHighlight {

    data class Palette(
        val keyword: Color, val string: Color, val comment: Color,
        val number: Color, val fn: Color, val type: Color, val plain: Color
    )

    val Dark = Palette(
        keyword = ZorvPalette.Terracotta, string = ZorvPalette.Success, comment = ZorvPalette.InkSoft,
        number = ZorvPalette.ErrorWarm, fn = ZorvPalette.Info, type = ZorvPalette.Terracotta, plain = ZorvPalette.InfoSoft
    )
    val Light = Palette(
        keyword = ZorvPalette.Terracotta, string = ZorvPalette.Success, comment = ZorvPalette.Info,
        number = ZorvPalette.Terracotta, fn = ZorvPalette.Info, type = ZorvPalette.Terracotta, plain = ZorvPalette.Ink
    )

    private val keywords = mapOf(
        "kotlin" to setOf("fun","val","var","class","object","interface","data","sealed","when","if","else","for","while","return","import","package","private","public","internal","override","suspend","companion","lateinit","is","as","in","out","null","true","false","this","super","try","catch","finally","throw","by","where","init","enum","const","vararg","operator","inline","reified","abstract","open","do","break","continue","typealias"),
        "java" to setOf("public","private","protected","class","interface","extends","implements","static","final","void","int","long","double","float","boolean","char","byte","short","new","return","if","else","for","while","do","switch","case","break","continue","try","catch","finally","throw","throws","import","package","this","super","null","true","false","abstract","synchronized","volatile","transient","enum","record","var","instanceof","default","native","strictfp","assert","yield","sealed","permits"),
        "python" to setOf("def","class","return","if","elif","else","for","while","import","from","as","with","try","except","finally","raise","lambda","pass","break","continue","global","nonlocal","assert","yield","async","await","del","in","is","not","and","or","None","True","False","self","match","case"),
        "javascript" to setOf("function","const","let","var","class","extends","return","if","else","for","while","do","switch","case","break","continue","new","this","super","typeof","instanceof","in","of","try","catch","finally","throw","async","await","yield","import","export","from","default","null","undefined","true","false","void","delete","static","get","set"),
        "go" to setOf("func","package","import","var","const","type","struct","interface","map","chan","go","defer","return","if","else","for","range","switch","case","default","break","continue","select","fallthrough","nil","true","false","make","new","len","cap","append","copy","delete","panic","recover"),
        "rust" to setOf("fn","let","mut","const","static","struct","enum","trait","impl","pub","use","mod","crate","self","super","match","if","else","loop","while","for","in","return","break","continue","where","as","dyn","ref","move","async","await","unsafe","type","true","false","Some","None","Ok","Err"),
        "sql" to setOf("SELECT","FROM","WHERE","INSERT","INTO","VALUES","UPDATE","SET","DELETE","CREATE","TABLE","ALTER","DROP","INDEX","VIEW","JOIN","LEFT","RIGHT","INNER","OUTER","FULL","ON","AND","OR","NOT","NULL","IS","IN","BETWEEN","LIKE","ORDER","BY","GROUP","LIMIT","OFFSET","HAVING","DISTINCT","AS","UNION","ALL","CASE","WHEN","THEN","ELSE","END","PRIMARY","KEY","FOREIGN","REFERENCES","DEFAULT","AUTO_INCREMENT","UNIQUE","CASCADE"),
        "shell" to setOf("if","then","else","elif","fi","for","in","do","done","while","case","esac","function","return","local","export","echo","exit","read","set","unset","source","alias","trap","shift","cd","sudo","apt","npm","git","curl","wget","chmod","mkdir","rm","cp","mv","cat","grep","sed","awk","kill","ps","which","env")
    )
    // js/ts 互通 / 别名
    private fun keysOf(lang: String?): Set<String> {
        val l = (lang ?: "").lowercase().trim()
        return when {
            l.startsWith("kt") -> keywords["kotlin"]!!
            l == "java" -> keywords["java"]!!
            l.startsWith("py") -> keywords["python"]!!
            l == "js" || l == "javascript" || l == "ts" || l == "typescript" || l == "jsx" || l == "tsx" -> keywords["javascript"]!!
            l == "go" || l == "golang" -> keywords["go"]!!
            l.startsWith("rs") || l == "rust" -> keywords["rust"]!!
            l == "sql" -> keywords["sql"]!!
            l == "sh" || l == "bash" || l == "shell" || l == "zsh" -> keywords["shell"]!!
            else -> keywords["kotlin"]!! + keywords["javascript"]!! + keywords["python"]!!
        }
    }

    private fun commentToken(lang: String?): String = when ((lang ?: "").lowercase()) {
        "python" -> "#"
        "shell", "sh", "bash", "zsh" -> "#"
        "sql" -> "--"
        else -> "//"
    }

    /** 高亮主入口：返回着色 AnnotatedString */
    fun highlight(code: String, lang: String?, p: Palette = Dark): AnnotatedString = buildAnnotatedString {
        val kw = keysOf(lang)
        val cmt = commentToken(lang)
        val lines = code.lines()
        lines.forEachIndexed { li, line ->
            var i = 0
            val n = line.length
            while (i < n) {
                // 行注释：整段注释色
                if (line.startsWith(cmt, i)) {
                    val seg = line.substring(i)
                    pushStyle(SpanStyle(color = p.comment))
                    append(seg)
                    pop()
                    break
                }
                val c = line[i]
                when {
                    // 字符串
                    c == '"' || c == '\'' || c == '`' -> {
                        var j = i + 1
                        while (j < n && line[j] != c) { if (line[j] == '\\') j++; j++ }
                        val end = (j + 1).coerceAtMost(n)
                        val seg = line.substring(i, end)
                        pushStyle(SpanStyle(color = p.string))
                        append(seg); pop()
                        i = end
                    }
                    // 数字
                    c.isDigit() && (i == 0 || !line[i-1].isLetterOrDigit() && line[i-1] != '_') -> {
                        var j = i
                        while (j < n && (line[j].isDigit() || line[j] in "xXbBoO.aAfFeElL")) j++
                        val seg = line.substring(i, j)
                        pushStyle(SpanStyle(color = p.number))
                        append(seg); pop()
                        i = j
                    }
                    // 标识符
                    c.isLetter() || c == '_' -> {
                        var j = i
                        while (j < n && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                        val word = line.substring(i, j)
                        when {
                            kw.contains(word) || (lang == "sql" && kw.contains(word.uppercase())) -> {
                                pushStyle(SpanStyle(color = p.keyword)); append(word); pop()
                            }
                            j < n && line[j] == '(' -> {
                                pushStyle(SpanStyle(color = p.fn)); append(word); pop()
                            }
                            word.first().isUpperCase() -> {
                                pushStyle(SpanStyle(color = p.type)); append(word); pop()
                            }
                            else -> append(word)
                        }
                        i = j
                    }
                    else -> { append(c); i++ }
                }
            }
            if (li < lines.size - 1) append(10.toChar())
        }
    }
}

/**
 * 代码块 v2：语法高亮 + 语言标签 + 复制按钮 + 行号（深色卡片）
 */
@Composable
fun CodeBlockV2(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
    showLineNumbers: Boolean = true
) {
    val palette = SyntaxHighlight.Dark
    var copied by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val lines = remember(code) { code.lines() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ZorvPalette.Ink, RoundedCornerShape(10.dp))
    ) {
        // 头部：语言标签 + 复制
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(ZorvPalette.Ink)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Box(
                Modifier
                    .width(8.dp)
                    .background(palette.type, RoundedCornerShape(6.dp))
                    .padding(vertical = 0.dp)
            ) { }
            Spacer(Modifier.width(8.dp))
            Text(
                (language ?: "text").uppercase(),
                fontSize = 11.sp,
                color = ZorvPalette.Info,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(ZorvPalette.Ink, RoundedCornerShape(6.dp))
                    .minimumInteractiveComponentSize().clickable {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(code))
                        copied = true
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "复制",
                    tint = ZorvPalette.Info,
                    modifier = Modifier.width(13.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(if (copied) "已复制" else "复制", fontSize = 11.sp, color = ZorvPalette.Info)
            }
        }
        // 代码体
        Row(modifier = Modifier.padding(12.dp)) {
            if (showLineNumbers) {
                Text(
                    lines.indices.joinToString("\\n") { "${it + 1}" },
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ZorvPalette.Info
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                SyntaxHighlight.highlight(code, language, palette),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFamily = FontFamily.Monospace,
                color = palette.plain
            )
        }
    }
}
