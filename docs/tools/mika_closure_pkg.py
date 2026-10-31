"""把闭包补全到「包级」稳定点：同包内跨文件引用不经过 import，必须整包纳入。

迭代：把闭包里每个文件所在的整个 package 都加入，再重新跟踪 import，直到集合不再增长。
输出最终必须搬的规模，用来判断是整模块 vendor 还是可以按子集搬。
"""
import glob
import os
import re
import collections

ROOT = "D:/CalwOS-build/tmp/Mika"
PREFIX = "com.wangzan101.mika"

files = glob.glob(os.path.join(ROOT, "**/*.kt"), recursive=True)
files = [f for f in files if "/build/" not in f.replace("\\", "/")]

pkg_of, imports_of, lines_of, symbols_of = {}, {}, {}, {}
for f in files:
    f = os.path.abspath(f).replace("/", os.sep)
    src = open(f, encoding="utf-8", errors="ignore").read()
    m = re.search(r"^\s*package\s+([A-Za-z0-9_.]+)", src, re.M)
    pkg_of[f] = m.group(1) if m else ""
    imports_of[f] = re.findall(r"^\s*import\s+(?:static\s+)?([A-Za-z0-9_.]+)", src, re.M)
    lines_of[f] = src.count("\n") + 1
    symbols_of[f] = set(re.findall(
        r"^(?:@\w+(?:\([^)]*\))?\s*)*(?:[A-Za-z_][A-Za-z0-9_]*(?:\([^)]*\))?\s+)*"
        r"(?:class|interface|object|fun|val|var|typealias)\s+([A-Za-z0-9_]+)", src, re.M))

by_pkg = collections.defaultdict(set)
for f, p in pkg_of.items():
    by_pkg[p].add(f)


def find_file(fqn):
    if not fqn.startswith(PREFIX):
        return None
    parts = fqn.split(".")
    for cut in range(len(parts), 3, -1):
        pkg = ".".join(parts[:cut - 1])
        cls = parts[cut - 1]
        for ext in (".kt", ".java"):
            cand = os.path.join(ROOT, *pkg.split("."), cls + ext)
            if os.path.isfile(cand):
                return os.path.abspath(cand).replace("/", os.sep)
        for f in by_pkg.get(pkg, ()):
            if cls in symbols_of.get(f, set()):
                return f
    # 顶层符号：找同包内任意文件声明了它
    for cut in range(len(parts), 3, -1):
        pkg = ".".join(parts[:cut - 1])
        sym = parts[cut - 1]
        for f in by_pkg.get(pkg, ()):
            if sym in symbols_of.get(f, set()):
                return f
    return None


seeds = []
for pat in [
    "app/src/main/java/com/wangzan101/mika/cap/phone/a11y/*.kt",
    "app/src/main/java/com/wangzan101/mika/cap/phone/display/*.kt",
]:
    for f in glob.glob(os.path.join(ROOT, pat)):
        seeds.append(os.path.abspath(f).replace("/", os.sep))

closure = set(seeds)
unresolved = set()

rounds = 0
while True:
    rounds += 1
    before = len(closure)
    # 1) 整包纳入
    for f in list(closure):
        closure |= by_pkg.get(pkg_of.get(f, ""), set())
    # 2) 跟踪 import
    queue = list(closure)
    seen = set()
    while queue:
        f = queue.pop()
        if f in seen:
            continue
        seen.add(f)
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
    if len(closure) == before:
        break

total = sum(lines_of.get(f, 0) for f in closure)
print(f"=== 包级闭包稳定：{len(closure)} files, {total} lines（迭代 {rounds} 轮）===")
dist = collections.Counter()
for f in closure:
    p = pkg_of.get(f, "")
    key = p.replace(PREFIX + ".", "")
    key = ".".join(key.split(".")[:2]) if key.count(".") >= 1 else key
    dist[key] += lines_of.get(f, 0)
print("\n=== 按包累计行数(前 25) ===")
for k, v in dist.most_common(25):
    print(f"  {k:36s} {v:7d}")

core_all = [f for f in by_pkg.keys() if f.startswith(PREFIX + ".core")]
core_files = set()
for p in core_all:
    core_files |= by_pkg[p]
core_total = sum(lines_of.get(f, 0) for f in core_files)
print(f"\n=== core 模块全量: {len(core_files)} files, {core_total} lines ===")
print(f"=== 闭包占 core 的比例: {len(closure & core_files)}/{len(core_files)} files ===")

if unresolved:
    print(f"\n=== 仍未定位的 import({len(unresolved)}) ===")
    for u in sorted(unresolved):
        print("  ", u)
