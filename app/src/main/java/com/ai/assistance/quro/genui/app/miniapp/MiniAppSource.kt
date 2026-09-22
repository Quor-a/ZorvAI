@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ai.assistance.quro.genui.app.miniapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 小程序源码查看器：WXML / WXSS / JS 三件套带语法高亮展示。
 * 轻量正则染色（关键词/字符串/数字/注释/插值），只求"一眼能读"。
 */

private val HL_KEYWORD = Color(0xFFD9A05B)
private val HL_STRING = Color(0xFF9CBF7A)
private val HL_NUMBER = Color(0xFF7FA8D9)
private val HL_COMMENT = Color(0xFF6E675C)
private val HL_TEXT = Color(0xFFF2EAD9)
private val HL_INTERP_BG = Color(0x33D9A05B)

private val JS_KEYWORDS = setOf(
    "var", "let", "const", "function", "return", "if", "else", "for", "while", "do",
    "break", "continue", "new", "this", "typeof", "instanceof", "true", "false",
    "null", "undefined", "try", "catch", "finally", "throw", "switch", "case",
    "default", "in", "of", "delete", "void", "async", "await")

private fun keywordStyle(word: String): SpanStyle? = when {
    word in JS_KEYWORDS -> SpanStyle(color = HL_KEYWORD)
    word.matches(Regex("-?\\d+(\\.\\d+)?")) -> SpanStyle(color = HL_NUMBER)
    else -> null
}

private fun plainSeg(out: AnnotatedString.Builder, seg: String) {
    // 词法切分：标识符/数字 单独染色，其余原样
    val re = Regex("[A-Za-z_$][A-Za-z0-9_$]*|-?\\d+(?:\\.\\d+)?")
    var last = 0
    for (m in re.findAll(seg)) {
        if (m.range.first > last) out.append(seg.substring(last, m.range.first))
        val st = keywordStyle(m.value)
        if (st != null) { out.pushStyle(st); out.append(m.value); out.pop() }
        else out.append(m.value)
        last = m.range.last + 1
    }
    if (last < seg.length) out.append(seg.substring(last))
}

private fun codeAnnotated(code: String, lang: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = code.length
    while (i < n) {
        val c = code[i]
        // JS 行注释 / 块注释
        if (lang == "js" && c == '/' && i + 1 < n && code[i + 1] == '/') {
            val end = code.indexOf('\n', i).let { if (it == -1) n else it }
            pushStyle(SpanStyle(color = HL_COMMENT)); append(code.substring(i, end)); pop()
            i = end; continue
        }
        if (lang == "js" && c == '/' && i + 1 < n && code[i + 1] == '*') {
            val end = code.indexOf("*/", i + 2).let { if (it == -1) n else it + 2 }
            pushStyle(SpanStyle(color = HL_COMMENT)); append(code.substring(i, end)); pop()
            i = end; continue
        }
        if (lang == "wxml" && code.startsWith("<!--", i)) {
            val end = code.indexOf("-->", i).let { if (it == -1) n else it + 3 }
            pushStyle(SpanStyle(color = HL_COMMENT)); append(code.substring(i, end)); pop()
            i = end; continue
        }
        // 字符串（含模板）
        if (c == '"' || c == '\'' || c == '`') {
            var j = i + 1
            while (j < n && code[j] != c) {
                if (code[j] == '\\') j++
                j++
            }
            val end = (j + 1).coerceAtMost(n)
            pushStyle(SpanStyle(color = HL_STRING)); append(code.substring(i, end)); pop()
            i = end; continue
        }
        // WXML {{ }} 插值
        if (lang == "wxml" && c == '{' && i + 1 < n && code[i + 1] == '{') {
            val end = code.indexOf("}}", i).let { if (it == -1) n else it + 2 }
            pushStyle(SpanStyle(color = HL_NUMBER, background = HL_INTERP_BG))
            append(code.substring(i, end)); pop()
            i = end; continue
        }
        // WXML 标签
        if (lang == "wxml" && c == '<') {
            val end = code.indexOf('>', i).let { if (it == -1) n else it + 1 }
            pushStyle(SpanStyle(color = HL_KEYWORD)); append(code.substring(i, end)); pop()
            i = end; continue
        }
        // WXSS 注释
        if (lang == "wxss" && c == '/' && i + 1 < n && code[i + 1] == '*') {
            val end = code.indexOf("*/", i + 2).let { if (it == -1) n else it + 2 }
            pushStyle(SpanStyle(color = HL_COMMENT)); append(code.substring(i, end)); pop()
            i = end; continue
        }
        // 普通文本：按词收集（词染色）
        val plainEnd = run {
            var j = i
            while (j < n) {
                val ch = code[j]
                if (ch == '"' || ch == '\'' || ch == '`' || ch == '/' || ch == '{' || ch == '<' ||
                    (lang != "wxml" && ch == '\n')) break
                j++
            }
            j
        }
        plainSeg(this, code.substring(i, plainEnd))
        i = plainEnd
    }
}

@Composable
fun MiniAppSourceSheet(appId: String, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sources = remember(appId) {
        val pkg = com.yuanbao.miniapp.core.MiniAppEngine.resolvePackage(ctx, appId)
        listOf("app.js", "pages/index/index.wxml", "pages/index/index.wxss")
            .map { path -> Triple(path, pkg?.read(path) ?: "", guessLang(path)) }
            .filter { it.second.isNotBlank() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1E1B18)) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)
                .heightIn(max = 520.dp)
        ) {
            Text("▦ 源码 · $appId", color = Color(0xFFF2EAD9), fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            if (sources.isEmpty()) {
                Text("未找到源码文件", color = Color(0xFF8A8378), fontSize = 12.sp)
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    sources.forEachIndexed { idx, (path, code, lang) ->
                        Text(path, color = Color(0xFFD9A05B), fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF141210)) {
                            Box(Modifier.horizontalScroll(rememberScrollState()).padding(10.dp)) {
                                Text(
                                    codeAnnotated(code, lang),
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                        if (idx < sources.lastIndex) Spacer(Modifier.height(14.dp))
                    }
                }
            }
        }
    }
}

private fun guessLang(path: String): String = when {
    path.endsWith(".js") -> "js"
    path.endsWith(".wxml") -> "wxml"
    path.endsWith(".wxss") -> "wxss"
    else -> "json"
}
