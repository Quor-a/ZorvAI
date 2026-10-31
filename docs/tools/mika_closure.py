"""算出 Mika「后台虚拟屏」能力的精确依赖闭包。

从种子文件出发（虚拟屏 + 无障碍操作相关），递归解析 package + import，
只跟踪 Mika 自己的包，直到不再有新文件进入闭包。
输出：必须搬运的文件清单、总行数、以及闭包里出现的顶层包分布。
"""
import glob
import os
import re
import collections

ROOT = "D:/CalwOS-build/tmp/Mika"
PREFIX = "com.wangzan101.mika"

# 索引：全仓 Mika 源文件 -> 它声明的 package + 定义的顶层符号
files = glob.glob(os.path.join(ROOT, "**/*.kt"), recursive=True) + \
        glob.glob(os.path.join(ROOT, "**/*.java"), recursive=True)
files = [f for f in files if "/build/" not in f.replace("\\", "/")]

pkg_of = {}          # file -> package
imports_of = {}      # file -> [import 的完整类名]
lines_of = {}
symbols_of = {}      # file -> set(顶层符号)
for f in files:
    try:
        src = open(f, encoding="utf-8", errors="ignore").read()
    except Exception:
        continue
    m = re.search(r"^\s*package\s+([A-Za-z0-9_.]+)", src, re.M)
    pkg = m.group(1) if m else ""
    f = os.path.abspath(f).replace("/", os.sep)
    pkg_of[f] = pkg
    imports_of[f] = re.findall(r"^\s*import\s+(?:static\s+)?([A-Za-z0-9_.]+)", src, re.M)
    lines_of[f] = src.count("\n") + 1
    syms = set()
    for sm in re.finditer(
        r"^(?:@\w+(?:\([^)]*\))?\s*)*(?:[A-Za-z_][A-Za-z0-9_]*(?:\([^)]*\))?\s+)*"
        r"(?:class|interface|object|fun|val|var|typealias)\s+([A-Za-z0-9_]+)",
        src, re.M):
        syms.add(sm.group(1))
    symbols_of[f] = syms

# 包名 -> 该包下所有文件
by_pkg = collections.defaultdict(list)
for f, p in pkg_of.items():
    by_pkg[p].append(f)

def find_file(fqn):
    """把 import 的完整类名映射到源文件：先试 包.类 = 文件名，再退回按 package 目录。"""
    if not fqn.startswith(PREFIX):
        return None
    parts = fqn.split(".")
    # 类名 = 最后一段（可能是嵌套类，逐级回退）
    for cut in range(len(parts), 3, -1):
        pkg = ".".join(parts[:cut - 1])
        cls = parts[cut - 1]
        cand = os.path.join(ROOT, *pkg.split("."), cls + ".kt")
        if os.path.isfile(cand):
            return cand.replace("/", os.sep)
        cand = os.path.join(ROOT, *pkg.split("."), cls + ".java")
        if os.path.isfile(cand):
            return cand.replace("/", os.sep)
        # 同包多文件：类名与文件名不一致时，扫该包
        for f in by_pkg.get(pkg, []):
            if cls in symbols_of.get(f, set()):
                return f
    return None

# 种子：虚拟屏 + 无障碍操作全套
seeds = []
for pat in [
    "app/src/main/java/com/wangzan101/mika/cap/phone/a11y/*.kt",
    "app/src/main/java/com/wangzan101/mika/cap/phone/display/*.kt",
]:
    for f in glob.glob(os.path.join(ROOT, pat)):
        seeds.append(os.path.abspath(f).replace("/", os.sep))

closure = set(seeds)
queue = list(seeds)
unresolved = set()
while queue:
    f = queue.pop()
    for imp in imports_of.get(f, []):
        if not imp.startswith(PREFIX):
            continue
        tgt = find_file(imp)
        if tgt is None:
            unresolved.add(imp)
            continue
        if tgt not in closure:
            closure.add(tgt)
            queue.append(tgt)

total = sum(lines_of.get(f, 0) for f in closure)
print(f"=== 闭包文件数: {len(closure)}，总行数: {total} ===")
dist = collections.Counter()
for f in closure:
    rel = os.path.relpath(f, ROOT).replace("\\", "/")
    # 取到 core/<子包> 或 cap/phone/<子包>
    m = re.search(r"(?:core|cap/phone)/([A-Za-z0-9_]+)/", rel)
    key = m.group(1) if m else rel.rsplit("/", 1)[0]
    dist[key] += 1
print("\n=== 闭包按目录分布 ===")
for k, v in dist.most_common():
    n = sum(lines_of.get(f, 0) for f in closure
            if re.search(r"(?:core|cap/phone)/" + re.escape(k) + r"/", os.path.relpath(f, ROOT).replace("\\", "/")))
    print(f"  {k:22s} {v:4d} files  {n:6d} lines")

print("\n=== 闭包内文件清单 ===")
for f in sorted(closure, key=lambda x: os.path.relpath(x, ROOT)):
    rel = os.path.relpath(f, ROOT).replace("\\", "/")
    print(f"  {lines_of.get(f,0):5d}  {rel}")

if unresolved:
    print(f"\n=== 未能定位的 import（{len(unresolved)} 个）===")
    for u in sorted(unresolved):
        print("  ", u)
