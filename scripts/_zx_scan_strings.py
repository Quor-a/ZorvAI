# -*- coding: utf-8 -*-
"""扫描 Kotlin 文件里「字符串跨行」的损坏点（原本 \n 被还原成真实换行）。

策略：逐字符扫描，维护字符串状态。当一行以未闭合的字符串结尾、
且下一行以字符串内容继续时，记录该位置。
"""
import io, os, sys, glob

def scan_file(path):
    with io.open(path, "r", encoding="utf-8") as f:
        lines = f.readlines()
    issues = []
    in_str = False
    str_line = 0
    for i, raw in enumerate(lines, 1):
        line = raw.rstrip("\n")
        n = len(line)
        j = 0
        while j < n:
            ch = line[j]
            if ch == '\\' and j + 1 < n:
                # 转义序列：如果原本是 \n 被还原成真实换行，这里不会出现 \\n
                j += 2
                continue
            if ch == '"':
                in_str = not in_str
                if in_str:
                    str_line = i
                else:
                    str_line = 0
                j += 1
                continue
            if ch == '/' and j + 1 < n and line[j+1] == '/':
                break  # 行注释
            if ch == '/' and j + 1 < n and line[j+1] == '*':
                # 块注释
                k = line.find("*/", j + 2)
                if k < 0:
                    break
                j = k + 2
                continue
            j += 1
        if in_str:
            issues.append((str_line, i, line[:100]))
            # 继续到下一行，in_str 保持
    return issues

def main():
    root = r"D:/Calw OS-project/QuroAI/plugin-zxwidget/src/main/java/com/zorv/plugin/zxwidget"
    total = 0
    per_file = {}
    for p in sorted(glob.glob(os.path.join(root, "*.kt"))):
        issues = scan_file(p)
        if issues:
            per_file[os.path.basename(p)] = issues
            total += len(issues)
    print(f"TOTAL cross-line string issues: {total}")
    for name, issues in per_file.items():
        print(f"\n=== {name}: {len(issues)} ===")
        for (sl, el, snippet) in issues[:8]:
            print(f"  line {sl}-{el}: ...{snippet}...")

if __name__ == "__main__":
    main()