# -*- coding: utf-8 -*-
"""扫values-fr 目录下**所有** xml，找出所有非法反斜杠转义（含 AndroidX 库带进来的）。"""
import glob
import io

BS = chr(92)
LEGAL = ("n", "t", "r", BS, chr(34), "u")

for f in sorted(glob.glob("app/src/main/res/values-fr/*.xml")):
    s = io.open(f, encoding="utf-8").read()
    bad = []
    for i, ch in enumerate(s):
        if ch != BS:
            continue
        nxt = s[i + 1] if i + 1 < len(s) else ""
        if nxt in LEGAL:
            continue
        bad.append(i)
    print("%-52s 反斜杠 %d, 非法 %d" % (f, s.count(BS), len(bad)))
    for i in bad[:20]:
        print("    idx=%d%r" % (i, s[max(0, i - 60):i + 20]))