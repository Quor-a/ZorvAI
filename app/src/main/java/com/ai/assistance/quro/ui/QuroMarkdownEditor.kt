package com.ai.assistance.quro.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 真实的Markdown编辑器
 * 支持编辑/预览双模式，实时渲染Markdown
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroMarkdownEditor(
    content: String,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    var isPreviewMode by remember { mutableStateOf(false) }
    var showFormatBar by remember { mutableStateOf(true) }
    
    Column(modifier = modifier.fillMaxSize()) {
        // 顶部工具栏
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = cs.surface,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 编辑/预览切换
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = !isPreviewMode,
                        onClick = { isPreviewMode = false },
                        label = { Text("编辑", fontSize = 12.sp) },
                        leadingIcon = if (!isPreviewMode) {
                            { Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                    FilterChip(
                        selected = isPreviewMode,
                        onClick = { isPreviewMode = true },
                        label = { Text("预览", fontSize = 12.sp) },
                        leadingIcon = if (isPreviewMode) {
                            { Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
                
                // 格式栏切换
                IconButton(
                    onClick = { showFormatBar = !showFormatBar },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (showFormatBar) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = "切换格式栏",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        
        // 格式化工具栏
        if (showFormatBar && !isPreviewMode) {
            MarkdownFormatToolbar(
                onFormat = { format ->
                    onContentChange(content + format)
                }
            )
            HorizontalDivider(color = cs.outlineVariant)
        }
        
        // 编辑/预览区域
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .background(cs.background)
        ) {
            if (isPreviewMode) {
                // 预览模式：实时渲染Markdown
                MarkdownPreview(content = content)
            } else {
                // 编辑模式：文本编辑
                OutlinedTextField(
                    value = content,
                    onValueChange = onContentChange,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    placeholder = {
                        Text(
                            text = "开始输入Markdown内容...",
                            fontSize = 16.sp,
                            color = cs.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = cs.primary
                    )
                )
            }
        }
    }
}

/**
 * Markdown格式化工具栏
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkdownFormatToolbar(
    onFormat: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    
    val formatActions = listOf(
        Triple("加粗", Icons.Filled.FormatBold, "**文本**"),
        Triple("斜体", Icons.Filled.FormatItalic, "*文本*"),
        Triple("删除线", Icons.Filled.FormatStrikethrough, "~~文本~~"),
        Triple("代码", Icons.Filled.Code, "`代码`"),
        Triple("标题1", Icons.Filled.Title, "# "),
        Triple("标题2", Icons.Filled.Title, "## "),
        Triple("标题3", Icons.Filled.Title, "### "),
        Triple("无序列表", Icons.Filled.FormatListBulleted, "- "),
        Triple("有序列表", Icons.Filled.FormatListNumbered, "1. "),
        Triple("任务列表", Icons.Filled.CheckBox, "- [ ] "),
        Triple("引用", Icons.Filled.FormatQuote, "> "),
        Triple("代码块", Icons.Filled.DataObject, "```\n代码\n```"),
        Triple("分割线", Icons.Filled.HorizontalRule, "\n---\n"),
        Triple("链接", Icons.Filled.Link, "[链接文本](url)"),
        Triple("图片", Icons.Filled.Image, "![图片描述](url)"),
        Triple("表格", Icons.Filled.GridView, "| 列1 | 列2 |\n|------|------|\n| 内容 | 内容 |")
    )
    
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(formatActions) { (name, icon, format) ->
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(name) } },
                state = rememberTooltipState()
            ) {
                IconButton(
                    onClick = { onFormat(format) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        icon,
                        contentDescription = name,
                        tint = cs.onSurface,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * Markdown预览组件
 * 使用WebView渲染Markdown
 */
@Composable
fun MarkdownPreview(content: String) {
    val cs = MaterialTheme.colorScheme
    
    // 将Markdown转换为HTML
    val htmlContent = remember(content) {
        markdownToHtml(content)
    }
    
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                
                // 设置背景色为透明，跟随主题
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { webView ->
            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <style>
                        * {
                            box-sizing: border-box;
                            margin: 0;
                            padding: 0;
                        }
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                            line-height: 1.6;
                            padding: 16px;
                            color: #333;
                            background-color: #fafafa;
                        }
                        h1, h2, h3, h4, h5, h6 {
                            margin-top: 24px;
                            margin-bottom: 16px;
                            font-weight: 600;
                            line-height: 1.25;
                        }
                        h1 { font-size: 2em; border-bottom: 1px solid #eee; padding-bottom: 0.3em; }
                        h2 { font-size: 1.5em; border-bottom: 1px solid #eee; padding-bottom: 0.3em; }
                        h3 { font-size: 1.25em; }
                        p { margin-bottom: 16px; }
                        code {
                            background-color: #f6f8fa;
                            padding: 0.2em 0.4em;
                            border-radius: 3px;
                            font-family: 'SFMono-Regular', Consolas, monospace;
                            font-size: 85%;
                        }
                        pre {
                            background-color: #f6f8fa;
                            padding: 16px;
                            border-radius: 6px;
                            overflow-x: auto;
                            margin-bottom: 16px;
                        }
                        pre code {
                            background: none;
                            padding: 0;
                            font-size: 100%;
                        }
                        blockquote {
                            border-left: 4px solid #dfe2e5;
                            padding: 0 16px;
                            color: #6a737d;
                            margin-bottom: 16px;
                        }
                        ul, ol {
                            padding-left: 2em;
                            margin-bottom: 16px;
                        }
                        li { margin-bottom: 4px; }
                        table {
                            border-collapse: collapse;
                            width: 100%;
                            margin-bottom: 16px;
                        }
                        th, td {
                            border: 1px solid #dfe2e5;
                            padding: 8px 12px;
                            text-align: left;
                        }
                        th {
                            background-color: #f6f8fa;
                            font-weight: 600;
                        }
                        img {
                            max-width: 100%;
                            height: auto;
                            border-radius: 4px;
                        }
                        a {
                            color: #0366d6;
                            text-decoration: none;
                        }
                        a:hover {
                            text-decoration: underline;
                        }
                        hr {
                            border: none;
                            border-top: 1px solid #e1e4e8;
                            margin: 24px 0;
                        }
                        /* 任务列表样式 */
                        .task-list-item {
                            list-style-type: none;
                            margin-left: -1.5em;
                        }
                        .task-list-item input {
                            margin-right: 0.5em;
                        }
                    </style>
                </head>
                <body>
                    $htmlContent
                </body>
                </html>
            """.trimIndent()
            
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * 简单的Markdown转HTML转换器
 */
private fun markdownToHtml(markdown: String): String {
    val lines = markdown.lines()
    val html = StringBuilder()
    var inCodeBlock = false
    var codeBlockContent = StringBuilder()
    var codeBlockLanguage = ""
    var inBlockquote = false
    var blockquoteContent = StringBuilder()
    var inList = false
    var listType = "" // "ul" or "ol"
    var listItemCount = 0
    
    for (line in lines) {
        // 代码块处理
        if (line.trimStart().startsWith("```")) {
            if (inCodeBlock) {
                // 结束代码块
                html.append("<pre><code class=\"language-$codeBlockLanguage\">${escapeHtml(codeBlockContent.toString())}</code></pre>\n")
                codeBlockContent.clear()
                inCodeBlock = false
            } else {
                // 开始代码块
                inCodeBlock = true
                codeBlockLanguage = line.trimStart().removePrefix("```").trim()
            }
            continue
        }
        
        if (inCodeBlock) {
            codeBlockContent.appendLine(line)
            continue
        }
        
        // 引用块处理
        if (line.startsWith("> ")) {
            if (!inBlockquote) {
                inBlockquote = true
                blockquoteContent.clear()
            }
            blockquoteContent.appendLine(line.removePrefix("> "))
            continue
        } else if (inBlockquote) {
            // 结束引用块
            html.append("<blockquote>${markdownToInlineHtml(blockquoteContent.toString())}</blockquote>\n")
            blockquoteContent.clear()
            inBlockquote = false
        }
        
        // 空行处理
        if (line.isBlank()) {
            if (inList) {
                html.append("</$listType>\n")
                inList = false
            }
            html.append("\n")
            continue
        }
        
        // 标题处理
        if (line.startsWith("# ")) {
            html.append("<h1>${markdownToInlineHtml(line.removePrefix("# "))}</h1>\n")
            continue
        }
        if (line.startsWith("## ")) {
            html.append("<h2>${markdownToInlineHtml(line.removePrefix("## "))}</h2>\n")
            continue
        }
        if (line.startsWith("### ")) {
            html.append("<h3>${markdownToInlineHtml(line.removePrefix("### "))}</h3>\n")
            continue
        }
        if (line.startsWith("#### ")) {
            html.append("<h4>${markdownToInlineHtml(line.removePrefix("#### "))}</h4>\n")
            continue
        }
        if (line.startsWith("##### ")) {
            html.append("<h5>${markdownToInlineHtml(line.removePrefix("##### "))}</h5>\n")
            continue
        }
        if (line.startsWith("###### ")) {
            html.append("<h6>${markdownToInlineHtml(line.removePrefix("###### "))}</h6>\n")
            continue
        }
        
        // 分割线
        if (line.trim() == "---" || line.trim() == "***" || line.trim() == "___") {
            html.append("<hr>\n")
            continue
        }
        
        // 无序列表
        if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) {
            if (!inList || listType != "ul") {
                if (inList) html.append("</$listType>\n")
                html.append("<ul>\n")
                inList = true
                listType = "ul"
            }
            val content = line.removePrefix("- ").removePrefix("* ").removePrefix("+ ")
            html.append("<li>${markdownToInlineHtml(content)}</li>\n")
            continue
        }
        
        // 有序列表
        if (line.matches(Regex("^\\d+\\.\\s.*"))) {
            if (!inList || listType != "ol") {
                if (inList) html.append("</$listType>\n")
                html.append("<ol>\n")
                inList = true
                listType = "ol"
            }
            val content = line.replace(Regex("^\\d+\\.\\s"), "")
            html.append("<li>${markdownToInlineHtml(content)}</li>\n")
            continue
        }
        
        // 任务列表
        if (line.startsWith("- [ ] ") || line.startsWith("- [x] ")) {
            val isChecked = line.startsWith("- [x] ")
            val content = line.removePrefix("- [ ] ").removePrefix("- [x] ")
            val checkbox = if (isChecked) " checked" else ""
            html.append("<div class=\"task-list-item\"><input type=\"checkbox\"$checkbox disabled> ${markdownToInlineHtml(content)}</div>\n")
            continue
        }
        
        // 表格处理（简化版）
        if (line.contains("|")) {
            // 跳过分隔行
            if (line.matches(Regex("^\\|[-\\s|]+\\|$"))) {
                continue
            }
            val cells = line.split("|").filter { it.isNotBlank() }.map { it.trim() }
            if (cells.isNotEmpty()) {
                html.append("<tr>")
                cells.forEach { cell ->
                    html.append("<td>${markdownToInlineHtml(cell)}</td>")
                }
                html.append("</tr>\n")
            }
            continue
        }
        
        // 普通段落
        html.append("<p>${markdownToInlineHtml(line)}</p>\n")
    }
    
    // 处理未关闭的块
    if (inCodeBlock) {
        html.append("<pre><code class=\"language-$codeBlockLanguage\">${escapeHtml(codeBlockContent.toString())}</code></pre>\n")
    }
    if (inBlockquote) {
        html.append("<blockquote>${markdownToInlineHtml(blockquoteContent.toString())}</blockquote>\n")
    }
    if (inList) {
        html.append("</$listType>\n")
    }
    
    return html.toString()
}

/**
 * Markdown行内元素转HTML
 */
private fun markdownToInlineHtml(text: String): String {
    var result = text
    
    // 行内代码（必须最先处理，避免被其他规则影响）
    result = result.replace(Regex("`([^`]+)`")) { match ->
        "<code>${escapeHtml(match.groupValues[1])}</code>"
    }
    
    // 加粗+斜体
    result = result.replace(Regex("\\*\\*\\*(.+?)\\*\\*\\*")) { match ->
        "<strong><em>${match.groupValues[1]}</em></strong>"
    }
    
    // 加粗
    result = result.replace(Regex("\\*\\*(.+?)\\*\\*")) { match ->
        "<strong>${match.groupValues[1]}</strong>"
    }
    
    // 斜体
    result = result.replace(Regex("\\*(.+?)\\*")) { match ->
        "<em>${match.groupValues[1]}</em>"
    }
    
    // 删除线
    result = result.replace(Regex("~~(.+?)~~")) { match ->
        "<del>${match.groupValues[1]}</del>"
    }
    
    // 图片
    result = result.replace(Regex("!\\[([^\\]]*)\\]\\(([^)]+)\\)")) { match ->
        "<img src=\"${match.groupValues[2]}\" alt=\"${match.groupValues[1]}\">"
    }
    
    // 链接
    result = result.replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")) { match ->
        "<a href=\"${match.groupValues[2]}\">${match.groupValues[1]}</a>"
    }
    
    return result
}

/**
 * HTML转义
 */
private fun escapeHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}