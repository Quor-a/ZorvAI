# -*- coding: utf-8 -*-
"""输出 ZxWidgetEntry 跨行字符串损坏点的完整上下文。"""
import io

p = r"D:/Calw OS-project/QuroAI/plugin-zxwidget/src/main/java/com/zorv/plugin/zxwidget/ZxWidgetEntry.kt"
with io.open(p, encoding="utf-8") as f:
    lines = f.readlines()

# 已知损坏起始行（来自扫描）
starts = [773, 816, 1239, 1324, 1328, 1332, 1339, 1345, 1413, 1461, 1560, 1706, 3271]

for s in starts:
    print(f"\n{'='*70}\n=== 损坏点起始行 {s} ===")
    for i in range(max(0, s - 3), min(len(lines), s + 6)):
        print(f"{i+1}: {lines[i].rstrip()}")