# -*- coding: utf-8 -*-
"""修两处编译错误：buildAnnotatedString 命中 @Composable 重载；MdImage 回调可空。"""
import io, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
P = os.path.join(ROOT, "app", "src", "main", "java", "com", "ai", "assistance", "quro", "ui", "dialog", "RichText.kt")

raw = io.open(P, "rb").read()
crlf = raw.count(b"\r\n")
lf = raw.count(b"\n") - crlf
eol = "\r\n" if crlf and crlf >= lf else "\n"
t = raw.decode("utf-8").replace("\r\n", "\n")


def sub(old, new, name):
    global t
    if old not in t:
        print("  !! MISS:", name)
        sys.exit(1)
    if t.count(old) != 1:
        print("  !! NOT UNIQUE (%d):" % t.count(old), name)
        sys.exit(1)
    print("  ok:", name)
    t = t.replace(old, new)


# 1) highlightCode 改用显式 Builder（避开 @Composable 重载）
sub(
    r'''    return buildAnnotatedString {
        var i = 0
        val n = code.length
        while (i < n) {
            val c = code[i]
            if (code.startsWith("//", i)) {
                val e = code.indexOf('\n', i); val end = if (e < 0) n else e
                withStyle(SpanStyle(color = CODE_COMMENT)) { append(code.substring(i, end)) }; i = end; continue
            }
            if (code.startsWith("/*", i)) {
                val e = code.indexOf("*/", i + 2); val end = if (e < 0) n else (e + 2)
                withStyle(SpanStyle(color = CODE_COMMENT)) { append(code.substring(i, end)) }; i = end; continue
            }
            if (hashComment && (i == 0 || code[i - 1] == '\n') && c == '#') {
                val e = code.indexOf('\n', i); val end = if (e < 0) n else e
                withStyle(SpanStyle(color = CODE_COMMENT)) { append(code.substring(i, end)) }; i = end; continue
            }
            if (c == '"' || c == '\'' || c == '`') {
                var j = i + 1
                while (j < n && code[j] != c) { if (code[j] == '\\') j++; j++ }
                val end = minOf(j + 1, n)
                withStyle(SpanStyle(color = CODE_STR)) { append(code.substring(i, end)) }
                i = end; continue
            }
            if (c.isDigit()) {
                var j = i
                while (j < n && (code[j].isDigit() || code[j] == '.')) j++
                withStyle(SpanStyle(color = CODE_NUM)) { append(code.substring(i, j)) }
                i = j; continue
            }
            if (c.isLetter() || c == '_') {
                var j = i
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val word = code.substring(i, j)
                if (word in kw) {
                    withStyle(SpanStyle(color = CODE_KW, fontWeight = FontWeight.SemiBold)) { append(word) }
                } else {
                    withStyle(SpanStyle(color = plain)) { append(word) }
                }
                i = j; continue
            }
            append(c); i++
        }
    }
}''',
    r'''    // 🔴 这里**必须**用显式 Builder：Compose 1.7 起 `buildAnnotatedString` 有一个
    // @Composable 重载（支持 appendInlineContent），顶层非 Composable 函数里
    // 直接调用会被解析到它，于是报「must be marked with @Composable」。
    // inlineToAnnotated 那边能编译是因为它的调用形态不同，别照抄过去。
    val b = AnnotatedString.Builder()
    var i = 0
    val n = code.length
    while (i < n) {
        val c = code[i]
        if (code.startsWith("//", i)) {
            val e = code.indexOf('\n', i); val end = if (e < 0) n else e
            b.withStyle(SpanStyle(color = CODE_COMMENT)) { append(code.substring(i, end)) }; i = end; continue
        }
        if (code.startsWith("/*", i)) {
            val e = code.indexOf("*/", i + 2); val end = if (e < 0) n else (e + 2)
            b.withStyle(SpanStyle(color = CODE_COMMENT)) { append(code.substring(i, end)) }; i = end; continue
        }
        if (hashComment && (i == 0 || code[i - 1] == '\n') && c == '#') {
            val e = code.indexOf('\n', i); val end = if (e < 0) n else e
            b.withStyle(SpanStyle(color = CODE_COMMENT)) { append(code.substring(i, end)) }; i = end; continue
        }
        if (c == '"' || c == '\'' || c == '`') {
            var j = i + 1
            while (j < n && code[j] != c) { if (code[j] == '\\') j++; j++ }
            val end = minOf(j + 1, n)
            b.withStyle(SpanStyle(color = CODE_STR)) { append(code.substring(i, end)) }
            i = end; continue
        }
        if (c.isDigit()) {
            var j = i
            while (j < n && (code[j].isDigit() || code[j] == '.')) j++
            b.withStyle(SpanStyle(color = CODE_NUM)) { append(code.substring(i, j)) }
            i = j; continue
        }
        if (c.isLetter() || c == '_') {
            var j = i
            while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
            val word = code.substring(i, j)
            if (word in kw) {
                b.withStyle(SpanStyle(color = CODE_KW, fontWeight = FontWeight.SemiBold)) { append(word) }
            } else {
                b.withStyle(SpanStyle(color = plain)) { append(word) }
            }
            i = j; continue
        }
        b.append(c); i++
    }
    return b.toAnnotatedString()
}''',
    "highlightCode 用显式 Builder",
)

# 2) diffAnnotated 同样处理
sub(
    r'''private fun diffAnnotated(code: String, plain: Color): AnnotatedString = buildAnnotatedString {
    code.split("\n").forEachIndexed { idx, ln ->
        if (idx > 0) append("\n")
        when {
            ln.startsWith("+") -> withStyle(SpanStyle(color = Color(0xFF2E9E63))) { append(ln) }
            ln.startsWith("-") -> withStyle(SpanStyle(color = Color(0xFFC0392B))) { append(ln) }
            ln.startsWith("@@") -> withStyle(SpanStyle(color = Color(0xFF2563EB))) { append(ln) }
            else -> withStyle(SpanStyle(color = plain.copy(alpha = 0.72f))) { append(ln) }
        }
    }
}''',
    r'''private fun diffAnnotated(code: String, plain: Color): AnnotatedString {
    val b = AnnotatedString.Builder()   // 同上：避开 @Composable 重载
    code.split("\n").forEachIndexed { idx, ln ->
        if (idx > 0) b.append("\n")
        when {
            ln.startsWith("+") -> b.withStyle(SpanStyle(color = Color(0xFF2E9E63))) { append(ln) }
            ln.startsWith("-") -> b.withStyle(SpanStyle(color = Color(0xFFC0392B))) { append(ln) }
            ln.startsWith("@@") -> b.withStyle(SpanStyle(color = Color(0xFF2563EB))) { append(ln) }
            else -> b.withStyle(SpanStyle(color = plain.copy(alpha = 0.72f))) { append(ln) }
        }
    }
    return b.toAnnotatedString()
}''',
    "diffAnnotated 用显式 Builder",
)

# 3) MdImage 回调可空
sub(
    r'''    onLinkClick: (String) -> Unit,
) {
    AsyncImage(''',
    r'''    onLinkClick: ((String) -> Unit)?,
) {
    AsyncImage(''',
    "MdImage 回调可空",
)
sub(
    "            .clickable { onLinkClick(b.url) },",
    "            .clickable { onLinkClick?.invoke(b.url) },",
    "MdImage 回调判空",
)

data = t.replace("\n", eol).encode("utf-8")
tmp = P + ".tmp"
with open(tmp, "wb") as f:
    f.write(data)
os.replace(tmp, P)
print("written:", len(data), "bytes")
