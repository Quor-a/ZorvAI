# -*- coding: utf-8 -*-
"""把 MnnModelCapabilities.kt 里被 shell heredoc 吞掉 \\b 的正则修回来。

背景：上一个补丁用 bash heredoc 传入 Python 源码，heredoc 会吞掉一层反斜杠，
于是 `\\b` 变成了真正的退格符（0x08）写进 Kotlin 源码 —— 文件里看不出来，
但正则语义已经坏了（`\btools\b` 变成 `<BS>tools<BS>`）。

本脚本不经过 shell 传参，直接读文件、按字节定位、写回原换行符。
"""

import io
import sys

PATH = r"D:\Calw OS-project\QuroAI\llm\mnn\src\main\java\com\ai\assistance\mnn\MnnModelCapabilities.kt"
BS = chr(92)          # 一个反斜杠
BEL = chr(8)          # 退格符（被 heredoc 误写的那个）

with io.open(PATH, encoding="utf-8", newline="") as fh:
    text = fh.read()

crlf = "\r\n" in text
norm = text.replace("\r\n", "\n")

wrong = '[^%{' + ']*' + BEL + 'tools' + BEL + '"""'
if wrong not in norm and BEL not in norm:
    print("NOT FOUND: 文件里没有退格符污染，无需修复")
    # 打印现状帮助判断
    for i, line in enumerate(norm.split("\n"), 1):
        if "TOOLS_CONDITION_RE" in line:
            print("  line %d: %s" % (i, repr(line)))
    sys.exit(1 if BEL in norm else 0)

# 把任意位置的退格符换回 \b —— 本文件里只应有这一处用途
count = norm.count(BEL)
norm = norm.replace(BEL, BS + "b")
print("replaced %d backspace char(s) with \\b" % count)

out = norm.replace("\n", "\r\n") if crlf else norm
with io.open(PATH, "w", encoding="utf-8", newline="") as fh:
    fh.write(out)

# 复核
with io.open(PATH, encoding="utf-8", newline="") as fh:
    check = fh.read()
for i, line in enumerate(check.replace("\r\n", "\n").split("\n"), 1):
    if "TOOLS_CONDITION_RE" in line:
        print("line %d: %s" % (i, repr(line)))
print("OK  (crlf=%s)" % crlf)
