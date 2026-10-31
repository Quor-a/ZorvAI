"""算 mika-phone 模块要含哪些文件：a11y + display 以及它们在 app 模块内的依赖（不含 core）。

core 已经整模块 vendor 为 :mika-core，这里只处理原本住在 app 模块的 cap/phone.* 与少量
app 层包（vision / i18n / shell）。包级纳入（同包跨文件引用不经过 import）。
"""
import glob
import os
import re
import collections

ROOT = "D:/CalwOS-build/tmp/Mika/app/src/main/java/com/wangzan101/mika"
PREFIX_APP = "com.wangzan101.mika"
CORE_PREFIX = "com.wangzan101.mika.core"

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
    parts = fqn.split(".")
    for cut in range(len(parts), 3, -1):
        pkg = ".".join(parts[:cut - 1])
        cls = parts[cut - 1]
        cand = os.path.join(ROOT, *pkg.split(".")[len("com.wangzan101.mika".split(".")):], cls + ".kt")
        if os.path.isfile(cand):
            return os.path.abspath(cand).replace("/", os.sep)
        for f in by_pkg.get(pkg, ()):
            if cls in symbols_of.get(f, set()):
                return f
    return None


seeds = []
for pat in ["cap/phone/a11y/*.kt", "cap/phone/display/*.kt"]:
    for f in glob.glob(os.path.join(ROOT, pat)):
        seeds.append(os.path.abspath(f).replace("/", os.sep))

closure = set(seeds)
unresolved = set()
while True:
    before = len(closure)
    for f in list(closure):
        closure |= by_pkg.get(pkg_of.get(f, ""), set())
    queue = list(closure)
    seen = set()
    while queue:
        f = queue.pop()
        if f in seen:
            continue
        seen.add(f)
        for imp in imports_of.get(f, []):
            if not imp.startswith(PREFIX_APP):
                continue
            if imp.startswith(CORE_PREFIX):
                continue  # 已在 :mika-core
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
print(f"=== mika-phone 闭包：{len(closure)} files, {total} lines ===")
pkgs = collections.Counter()
for f in closure:
    pkgs[pkg_of.get(f, "")] += lines_of.get(f, 0)
print("\n=== 按包 ===")
for k, v in pkgs.most_common():
    print(f"  {k:52s} {v:6d}")
print("\n=== 文件清单 ===")
for f in sorted(closure, key=lambda x: os.path.relpath(x, ROOT)):
    print(f"  {lines_of.get(f,0):5d}  {os.path.relpath(f, ROOT).replace(chr(92), '/')}")
if unresolved:
    print(f"\n=== 未解析（{len(unresolved)}）===")
    for u in sorted(unresolved):
        print("  ", u)
