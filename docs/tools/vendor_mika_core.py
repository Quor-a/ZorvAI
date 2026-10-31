"""把 Mika 的 core 模块整体 vendor 成 QuroAI 的 :mika-core 模块。

只做三件事：
1. 包名 com.wangzan101.mika.core → com.ai.assistance.quro.core.mika（目录同步）；
2. 去品牌化（注释/字符串里的 Mika → ZorvAI，小写 mika → quro）；
3. resources 原样拷贝（knowledge / prompts / skills）。

不改任何逻辑。要对比上游，直接 diff 本脚本产物与 Mika 源码即可（只差包名与品牌词）。
"""
import glob
import io
import os
import shutil

# 去品牌化时保护包名前缀：否则 "mika"→"quro" 会把 com.ai.assistance.quro.core.mika 改成 ...core.quro
_PKG_GUARD = "@@PKG@@"


def debrand(text: str, pkg_prefix: str) -> str:
    text = text.replace(pkg_prefix, _PKG_GUARD)
    text = text.replace("Mika", "ZorvAI").replace("mika", "quro")
    return text.replace(_PKG_GUARD, pkg_prefix)


MIKA = "D:/CalwOS-build/tmp/Mika"
SRC_ROOT = os.path.join(MIKA, "core/src/main/kotlin")
SRC_RES = os.path.join(MIKA, "core/src/main/resources")
DST_ROOT = "D:/Calw OS-project/QuroAI/mika-core/src/main/kotlin"
DST_RES = "D:/Calw OS-project/QuroAI/mika-core/src/main/resources"

OLD = "com.wangzan101.mika.core"
NEW = "com.ai.assistance.quro.core.mika"

files = glob.glob(os.path.join(SRC_ROOT, "**/*.kt"), recursive=True)
files = [f for f in files if "/build/" not in f.replace("\\", "/")]

copied = 0
for src in files:
    text = io.open(src, encoding="utf-8").read()
    rel = os.path.relpath(os.path.abspath(src), os.path.abspath(SRC_ROOT))
    # 目录：把 com/wangzan101/mika/core 换成 com/ai/assistance/quro/core/mika
    old_dir = os.path.join("com", "wangzan101", "mika", "core")
    norm = rel.replace("/", os.sep)
    if norm.startswith(old_dir + os.sep):
        norm = norm[len(old_dir) + 1:]
        out_rel = os.path.join("com", "ai", "assistance", "quro", "core", "mika", norm)
    else:
        out_rel = os.path.join("com", "ai", "assistance", "quro", "core", "mika", norm)
    out = os.path.join(DST_ROOT, out_rel)
    os.makedirs(os.path.dirname(out), exist_ok=True)

    text = text.replace(OLD, NEW)
    # 兜底：个别文件可能写全限定 com.wangzan101.mika（非 core 前缀）
    text = text.replace("com.wangzan101.mika", "com.ai.assistance.quro.core.mika")
    text = debrand(text, NEW)
    io.open(out, "w", encoding="utf-8", newline="").write(text)
    copied += 1

# resources 原样拷
res_copied = 0
if os.path.isdir(SRC_RES):
    for src in glob.glob(os.path.join(SRC_RES, "**/*"), recursive=True):
        if os.path.isdir(src):
            continue
        rel = os.path.relpath(src, SRC_RES)
        out = os.path.join(DST_RES, rel)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        shutil.copyfile(src, out)
        res_copied += 1




print(f"kotlin files copied: {copied}")
print(f"resource files copied: {res_copied}")
