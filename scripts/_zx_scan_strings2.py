# -*- coding: utf-8 -*-
"""扫描 Kotlin 文件里普通字符串跨行损坏（原本 \n 被还原成真实换行）。

正确处理：行注释 //、块注释 /* */、三引号字符串 \"\"\"...\"\"\"（跨行合法）。
只报告普通 "..." 字符串跨行（损坏）或行内可疑内嵌引号。
"""
import io, os, glob

def scan_file(path):
    with io.open(path, "r", encoding="utf-8") as f:
        lines = f.readlines()
    issues = []
    in_str = False          # 普通字符串中
    str_start = 0
    in_triple = False       # 三引号字符串中
    triple_start = 0
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
                if ch == '\\':
                    j += 2
                    continue
                if ch == '"':
                    in_str = False
                    str_start = 0
                    j += 1
                    continue
                # 普通字符串内出现真实换行（我们按行扫描，所以这里不会遇到换行）
                j += 1
                continue
            # 不在字符串中
            if ch == '/' and j + 1 < n and line[j+1] == '/':
                break
            if ch == '/' and j + 1 < n and line[j+1] == '*':
                in_block = True
                j += 2
                continue
            if ch == '"':
                if j + 2 < n and line[j+1] == '"' and line[j+2] == '"':
                    in_triple = True
                    triple_start = i
                    j += 3
                    continue
                in_str = True
                str_start = i
                j += 1
                continue
            j += 1
        if in_str:
            issues.append(("CROSS", str_start, i, line[-80:]))
        if in_triple:
            # 三引号跨行是合法的，不报告
            pass
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
    print(f"TOTAL ordinary-string cross-line issues: {total}")
    for name, issues in per_file.items():
        print(f"\n=== {name}: {len(issues)} ===")
        for (kind, sl, el, snippet) in issues[:10]:
            print(f"  [{kind}] line {sl}-{el}: ...{snippet}...")

if __name__ == "__main__":
    main()