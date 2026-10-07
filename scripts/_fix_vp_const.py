# -*- coding: utf-8 -*-
"""把 VIEWPORT_RE 改成三引号raw string（避免 \\s 转义），常量挪到类体外的顶层。"""
import io
import os
import time

P = "app/src/main/java/com/ai/assistance/quro/core/miniapp/MiniAppEngine.kt"
s = io.open(P, encoding="utf-8").read()

#删掉类体里那行（它引用了不存在的 VIEWPORT_PATTERN）
old = "    private val VIEWPORT_RE = Regex(VIEWPORT_PATTERN, RegexOption.IGNORE_CASE)"
assert s.count(old) == 1, "count=%d" % s.count(old)
s = s.replace(old + "\n", "")
# 连同它上面的注释也清掉
old_c = "    /** 探测页面是否已声明 viewport（含 name='viewport' 单引号写法）。 */\n"
assert s.count(old_c) == 1
s = s.replace(old_c, "")

# 在 package 声明之后插入顶层常量
Q3 = chr(34) * 3
old_pkg = "package com.ai.assistance.quro.core.miniapp\n"
assert s.count(old_pkg) == 1
new_pkg = old_pkg + "\n" \
    "/** 探测页面是否已声明 viewport（含 name='viewport' 单引号写法）。三引号 raw string 避开正则转义。 */\n" \
    "private val VIEWPORT_RE = Regex(" + Q3 + """<meta[^>]+name\s*=\s*['"]?viewport""" + Q3 + ", RegexOption.IGNORE_CASE)\n"
s = s.replace(old_pkg, new_pkg)

assert "VIEWPORT_PATTERN" not in s
assert s.count("private val VIEWPORT_RE") == 1
io.open(P + ".tmp", "w", encoding="utf-8", newline="\n").write(s)
for i in range(8):
    try:
        os.replace(P + ".tmp", P)
        print("OK")
        break
    except OSError:
        time.sleep(1.5)