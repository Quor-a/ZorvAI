# -*- coding: utf-8 -*-
"""精确分析 Kotlin 文件括号配平：处理注释、普通字符串、三引号字符串、字符串模板。"""
import io, sys

p = r"D:/Calw OS-project/QuroAI/plugin-zxwidget/src/main/java/com/zorv/plugin/zxwidget/ZxWidgetEntry.kt"
with io.open(p, "r", encoding="utf-8") as f:
    src = f.read()

def strip_comments_and_strings(code):
    """返回去掉注释和字符串后的代码，保留 {} 结构。"""
    out = []
    i = 0
    n = len(code)
    while i < n:
        ch = code[i]
        # 行注释
        if ch == '/' and i + 1 < n and code[i+1] == '/':
            while i < n and code[i] != '\n':
                i += 1
            continue
        # 块注释
        if ch == '/' and i + 1 < n and code[i+1] == '*':
            i += 2
            while i < n and not (code[i] == '*' and i + 1 < n and code[i+1] == '/'):
                i += 1
            i += 2
            continue
        # 三引号字符串
        if ch == '"' and i + 2 < n and code[i+1] == '"' and code[i+2] == '"':
            i += 3
            while i < n and not (code[i] == '"' and i + 1 < n and code[i+1] == '"' and code[i+2] == '"'):
                i += 1
            i += 3
            continue
        # 普通字符串（处理模板 ${}）
        if ch == '"':
            i += 1
            while i < n:
                if code[i] == '\\':
                    i += 2
                    continue
                if code[i] == '"':
                    break
                if code[i] == '$' and i + 1 < n and code[i+1] == '{':
                    # 跳过模板内容
                    depth_t = 1
                    i += 2
                    while i < n and depth_t > 0:
                        if code[i] == '{':
                            depth_t += 1
                        elif code[i] == '}':
                            depth_t -= 1
                        i += 1
                    continue
                i += 1
            i += 1
            continue
        # 字符
        if ch == "'":
            i += 1
            while i < n:
                if code[i] == '\\':
                    i += 2
                    continue
                if code[i] == "'":
                    break
                i += 1
            i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)

clean = strip_comments_and_strings(src)
depth = 0
line_depths = []
current_line = 1
for ch in clean:
    if ch == '\n':
        line_depths.append(depth)
        current_line += 1
        continue
    if ch == '{':
        depth += 1
    elif ch == '}':
        depth -= 1
        if depth < 0:
            print(f"NEGATIVE depth {depth} at line {current_line}")
            break
line_depths.append(depth)

print("final depth:", depth)
print("lines where depth <= 1 (class closed):")
for ln, d in enumerate(line_depths, 1):
    if d <= 1:
        print(f"  line {ln}: depth={d}")

# 打印深度在 1..3 间跳变的位置（找异常闭合）
prev = 0
for ln, d in enumerate(line_depths, 1):
    if d != prev and abs(d - prev) > 1:
        print(f"  JUMP line {ln}: {prev} -> {d}")
    prev = d