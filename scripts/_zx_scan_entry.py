# -*- coding: utf-8 -*-
"""精确扫描 ZxWidgetEntry 的普通字符串跨行损坏点。"""
import io

p = r"D:/Calw OS-project/QuroAI/plugin-zxwidget/src/main/java/com/zorv/plugin/zxwidget/ZxWidgetEntry.kt"
with io.open(p, encoding="utf-8") as f:
    lines = f.readlines()

issues = []
in_triple = False
in_str = False
str_start = 0
in_block = False
for i, raw in enumerate(lines, 1):
    line = raw.rstrip("\n")
    n = len(line)
    j = 0
    while j < n:
        ch = line[j]
        if in_block:
            k = line.find("*/", j)
            if k < 0:
                break
            in_block = False
            j = k + 2
            continue
        if in_triple:
            k = line.find('"""', j)
            if k < 0:
                break
            in_triple = False
            j = k + 3
            continue
        if in_str:
            if ch == "\\":
                j += 2
                continue
            if ch == '"':
                in_str = False
                str_start = 0
                j += 1
                continue
            j += 1
            continue
        if ch == "/" and j + 1 < n and line[j + 1] == "/":
            break
        if ch == "/" and j + 1 < n and line[j + 1] == "*":
            in_block = True
            j += 2
            continue
        if ch == "'":
            # 字符字面量 'x' 或 '\x'
            j += 1
            if j < n and line[j] == "\\":
                j += 2
            else:
                j += 1
            if j < n and line[j] == "'":
                j += 1
            continue
        if ch == '"':
            if j + 2 < n and line[j + 1] == '"' and line[j + 2] == '"':
                in_triple = True
                j += 3
                continue
            in_str = True
            str_start = i
            j += 1
            continue
        j += 1
    if in_str:
        issues.append((str_start, i, line[-100:]))

print(f"ZxWidgetEntry cross-line issues: {len(issues)}")
for sl, el, sn in issues[:80]:
    print(f"  {sl}-{el}: ...{sn}")