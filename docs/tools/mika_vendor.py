"""把 Mika「后台虚拟屏」依赖闭包整体搬进 QuroAI。

规则：
1. 包名统一前缀 `com.ai.assistance.quro.core.mika.`，保持与宿主既有类隔离；
2. `cap.phone.*` 映射为 `phone.*`（避免与 core 下同名子包撞车）；
3. `core.vision` 与 App 层 `vision` 同名，分别映射为 `vision` 与 `appvision`；
4. 只做包名替换 + 去品牌化文案，不改任何逻辑；
5. 目录与包名严格对应。
"""
import glob
import io
import os
import re
import shutil
import sys

MIKA = "D:/CalwOS-build/tmp/Mika"
DST_ROOT = "D:/Calw OS-project/QuroAI/app/src/main/java"

# 源根目录（Mika 的两个模块）
SRC_ROOTS = [
    os.path.join(MIKA, "app/src/main/java/com/wangzan101/mika"),
    os.path.join(MIKA, "core/src/main/kotlin/com/wangzan101/mika"),
]

# 闭包清单由 mika_closure.py 生成，这里直接复用它的算法
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "."))
exec(open(os.path.join(os.path.dirname(__file__), "mika_closure.py"), encoding="utf-8").read().split(
    'print(f"=== 闭包文件数')[0])

# closure 变量来自上面 exec 的脚本
files_to_copy = sorted(closure)

# 包名映射（顺序敏感：先长后短）
def new_pkg(pkg: str) -> str:
    if pkg.startswith("com.wangzan101.mika.cap.phone"):
        return pkg.replace("com.wangzan101.mika.cap.phone", "com.ai.assistance.quro.core.mika.phone", 1)
    if pkg.startswith("com.wangzan101.mika.core.vision"):
        return pkg.replace("com.wangzan101.mika.core.vision", "com.ai.assistance.quro.core.mika.vision", 1)
    if pkg.startswith("com.wangzan101.mika.vision"):
        return pkg.replace("com.wangzan101.mika.vision", "com.ai.assistance.quro.core.mika.appvision", 1)
    if pkg.startswith("com.wangzan101.mika.core."):
        return pkg.replace("com.wangzan101.mika.core.", "com.ai.assistance.quro.core.mika.", 1)
    if pkg.startswith("com.wangzan101.mika.i18n"):
        return pkg.replace("com.wangzan101.mika.i18n", "com.ai.assistance.quro.core.mika.i18n", 1)
    if pkg.startswith("com.wangzan101.mika.shell"):
        return pkg.replace("com.wangzan101.mika.shell", "com.ai.assistance.quro.core.mika.appshell", 1)
    return pkg.replace("com.wangzan101.mika", "com.ai.assistance.quro.core.mika", 1)


def rewrite(text: str) -> str:
    # import 与全限定名一起改，靠包名前缀替换完成（顺序：长前缀优先）
    pairs = [
        ("com.wangzan101.mika.cap.phone", "com.ai.assistance.quro.core.mika.phone"),
        ("com.wangzan101.mika.core.vision", "com.ai.assistance.quro.core.mika.vision"),
        ("com.wangzan101.mika.vision", "com.ai.assistance.quro.core.mika.appvision"),
        ("com.wangzan101.mika.core.", "com.ai.assistance.quro.core.mika."),
        ("com.wangzan101.mika.i18n", "com.ai.assistance.quro.core.mika.i18n"),
        ("com.wangzan101.mika.shell", "com.ai.assistance.quro.core.mika.appshell"),
        ("com.wangzan101.mika", "com.ai.assistance.quro.core.mika"),
    ]
    for a, b in pairs:
        text = text.replace(a, b)
    # 去品牌化（只改注释与字符串里的品牌词，不动标识符）
    text = text.replace("Mika", "ZorvAI").replace("mika", "quro")
    return text


copied = 0
skipped = 0
for src in files_to_copy:
    src = os.path.abspath(src).replace("/", os.sep)
    try:
        text = io.open(src, encoding="utf-8").read()
    except Exception as e:
        print("skip(read fail)", src, e)
        skipped += 1
        continue
    m = re.search(r"^\s*package\s+([A-Za-z0-9_.]+)", text, re.M)
    if not m:
        print("skip(no package)", src)
        skipped += 1
        continue
    old_pkg = m.group(1)
    pkg = new_pkg(old_pkg)
    rel = pkg.replace(".", os.sep)
    name = os.path.basename(src)
    out_dir = os.path.join(DST_ROOT, rel)
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, name)
    io.open(out, "w", encoding="utf-8", newline="").write(rewrite(text))
    copied += 1

print(f"copied={copied} skipped={skipped}")
