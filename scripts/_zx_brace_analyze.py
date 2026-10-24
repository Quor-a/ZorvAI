# -*- coding: utf-8 -*-
"""分析 ZxWidgetEntry.kt 的括号配平，找出提前闭合位置。"""
import io, sys

p = r"D:/Calw OS-project/QuroAI/plugin-zxwidget/src/main/java/com/zorv/plugin/zxwidget/ZxWidgetEntry.kt"
with io.open(p, "r", encoding="utf-8") as f:
    lines = f.readlines()

depth = 0
min_depth_report = []
in_block_comment = False
for i, raw in enumerate(lines, 1):
    line = raw.rstrip("\n")
    # 去掉块注释
    j = 0
    code = []
    while j < len(line):
        if in_block_comment:
            k = line.find("*/", j)
            if k < 0:
                break
            in_block_comment = False
            j = k + 2
            continue
        if line.startswith("//", j):
            break
        if line.startswith("/*", j):
            k = line.find("*/", j + 2)
            if k < 0:
                in_block_comment = True
                break
            j = k + 2
            continue
        code.append(line[j])
        j += 1
    code = "".join(code)
    # 处理字符串（简单起见：去掉 "..." 和 '...' 和 """...""" 内容）
    # 用状态机
    out = []
    k = 0
    n = len(code)
    while k < n:
        ch = code[k]
        if ch == '"':
            # 找闭合（支持 \ 转义）
            k += 1
            while k < n:
                if code[k] == '\\':
                    k += 2
                    continue
                if code[k] == '"':
                    break
                k += 1
        elif ch == "'":
            k += 1
            while k < n:
                if code[k] == '\\':
                    k += 2
                    continue
                if code[k] == "'":
                    break
                k += 1
        else:
            out.append(ch)
        k += 1
    s = "".join(out)
    depth += s.count("{") - s.count("}")
    if depth < 2:
        min_depth_report.append((i, depth, raw.strip()[:80]))

print("final depth:", depth)
print("lines where depth < 2 (class should keep >= 2):")
for ln, d, text in min_depth_report[:40]:
    print(f"  line {ln}: depth={d} | {text}")