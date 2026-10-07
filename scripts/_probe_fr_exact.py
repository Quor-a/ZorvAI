# -*- coding: utf-8 -*-
"""字节级精确定位 values-fr/strings_i18n.xml 里所有反斜杠，逐个列出码位。"""
import io

P = "app/src/main/res/values-fr/strings_i18n.xml"
L = io.open(P, encoding="utf-8").read().split(chr(10))
BS = chr(92)
for n, line in enumerate(L, 1):
    if BS not in line:
        continue
    hits = [i for i, c in enumerate(line) if c == BS]
    print("line %d : %d 处" % (n, len(hits)))
    for i in hits:
        seq = line[i:i + 4]
        print("   idx=%d seq=%r codepoints=%s" % (i, seq, [hex(ord(c)) for c in seq]))
        print("      ctx=%r" % line[max(0, i - 55):i + 25])