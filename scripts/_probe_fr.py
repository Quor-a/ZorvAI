# -*- coding: utf-8 -*-
"""打印 values-fr/strings.xml 前几行的精确码位，定位非法转义。"""
import io

P = "app/src/main/res/values-fr/strings.xml"
L = io.open(P, encoding="utf-8").read().split(chr(10))
for n in (2, 3):
    line = L[n]
    print("--- line %d ---" % (n + 1))
    print(repr(line[:200]))
    # 只打印非 ASCII 与转义相关字符的码位
    for k, ch in enumerate(line):
        o = ord(ch)
        if o > 126 or ch == chr(92):
            print("  idx=%d U+%04X %r" % (k, o, ch))