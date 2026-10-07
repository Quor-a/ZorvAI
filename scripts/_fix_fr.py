# -*- coding: utf-8 -*-
"""修法语 qk_03909 的转义：Plus d\'actions -> Plus d&apos;actions（Android XML 不认反斜杠转义）。"""
import io
import os
import time

P = "app/src/main/res/values-fr/strings_i18n.xml"
L = io.open(P, encoding="utf-8").read().split(chr(10))
hit = 0
for i, l in enumerate(L):
    if "qk_03909" in l:
        print("before:", repr(l))
        L[i] = '    <string name="qk_03909" formatted="false">Plus d&apos;actions</string>'
        print("after :", repr(L[i]))
        hit += 1
assert hit == 1, "hit=%d" % hit
s = chr(10).join(L)
io.open(P + ".tmp", "w", encoding="utf-8", newline=chr(10)).write(s)
for i in range(8):
    try:
        os.replace(P + ".tmp", P)
        print("OK")
        break
    except OSError:
        time.sleep(1.5)