# -*- coding: utf-8 -*-
"""补上 _c213c 里唯一失败的锚点（正则与常量）。

🔴 坑：源文件里是 Kotlin 源码，`\s` 在文件里写作 `\\s`（两个反斜杠）。
    Python 普通字符串里 `'\\\\s'` 才是两个反斜杠，之前 `!\\[` 只写了一个所以匹配不上。
    统一用 raw string 按字面写。
"""
import io
import os

ROOT = r"D:\Calw OS-project\QuroAI"
RT = os.path.join(ROOT, r"app\src\main\java\com\ai\assistance\quro\ui\dialog\RichText.kt")

with io.open(RT, "r", encoding="utf-8", newline="") as f:
    raw = f.read()
nl = "\r\n" if "\r\n" in raw else "\n"
t = raw.replace("\r\n", "\n")

OLD = r'private val RE_IMAGE_LINE = Regex("^\\s*!\\[([^\\]]*)\\]\\(([^)\\s]+)[^)]*\\)\\s*$")'
NEW = OLD + "\n" + "\n".join([
    r'',
    r'/** 脚注定义行：`[^1]: 释义`。 */',
    r'private val RE_FOOTNOTE_DEF = Regex("^\\s*\\[\\^([^\\]]+)]\\s*:\\s*(.*)$")',
    r'',
    r'/**',
    r' * 美元符常量。',
    r' *',
    r' * 🔴 必须这么写：Kotlin 字符串里 `$` 是模板起始符，`"$$"` 编译不过。',
    r' *    用 charArray 构造最稳，不依赖编译器对转义的支持。',
    r' */',
    "private val MATH_DELIM = String(charArrayOf('$', '$'))",
    "private val MATH_DOLLAR = String(charArrayOf('$'))",
])
n = t.count(OLD)
print("anchor hits:", n)
if n == 1:
    t = t.replace(OLD, NEW)
    out = t.replace("\n", nl)
    tmp = RT + ".tmp"
    with io.open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write(out)
    os.replace(tmp, RT)
    print("ok  written", os.path.getsize(RT))
else:
    print("FAIL")
