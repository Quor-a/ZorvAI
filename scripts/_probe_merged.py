# -*- coding: utf-8 -*-
"""直接读合并产物，打印报错键附近的真实码位 —— 不再猜。

aapt2 报的行列来自 merged.dir 下的合并 XML，源文件已多次清理仍报同样错，
说明**真正的非法转义在别的 values*目录的同名键里**（资源合并是按 key 覆盖的，
报错位置指向的是「第一个出现的那个文件」，不是「出错的那个文件」）。
"""
import glob
import io
import os

for f in sorted(glob.glob("app/build/intermediates/incremental/fullDebug/mergeFullDebugResources/merged.dir/values-*/*.xml")):
    if not os.path.exists(f):
        continue
    s = io.open(f, encoding="utf-8", errors="replace").read()
    BS = chr(92)
    bad = []
    for i, ch in enumerate(s):
        if ch != BS:
            continue
        nxt = s[i + 1] if i + 1 < len(s) else ""
        if nxt in ("n", "t", "r", BS, chr(34)):
            continue
        bad.append((i, s[max(0, i - 70):i + 25]))
    if bad:
        print("=== %s : %d 处可疑反斜杠" % (f, len(bad)))
        seen = set()
        for i, ctx in bad[:14]:
            key = ctx[60:85]
            if key in seen:
                continue
            seen.add(key)
            print("   ", repr(ctx))
print("--- done ---")