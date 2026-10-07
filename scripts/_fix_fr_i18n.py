# -*- coding: utf-8 -*-
"""清掉 values-fr/strings_i18n.xml 里 3 处 `\\'`（Android XML 非法转义）。

来源：本轮追加 qk_03909（法语 "Plus d'actions"）时误写成反斜杠转义。
正确写法：裸单引号（XML 里单引号在双引号属性/文本中完全合法）或 &apos;。
"""
import io
import os
import time

P = "app/src/main/res/values-fr/strings_i18n.xml"
s = io.open(P, encoding="utf-8").read()
BSQ = chr(92) + chr(39)
n = s.count(BSQ)
print("before:", n, "处")
i = s.find(BSQ)
while i > 0:
    print("  ", repr(s[max(0, i - 50):i + 25]))
    i = s.find(BSQ, i + 1)

s2 = s.replace(BSQ, chr(39))
assert BSQ not in s2
io.open(P + ".tmp", "w", encoding="utf-8", newline="\n").write(s2)
for k in range(8):
    try:
        os.replace(P + ".tmp", P)
        print("OK 去掉 %d 处反斜杠" % n)
        break
    except OSError:
        time.sleep(1.5)