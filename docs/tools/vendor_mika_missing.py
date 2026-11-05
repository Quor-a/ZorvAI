"""增量补齐 Mika core 缺失文件（ZorvAI :mika-core 已有 338，Mika 有 372 实现文件）。

规则与 vendor_mika_core.py 完全一致，只是：
1. 源目录改成当前浅克隆 /d/mika-src（原脚本指向已删除的 CalwOS-build/tmp/Mika）；
2. 只处理**目标不存在**的文件 → 可反复执行、不会覆盖已移植并适配过的文件；
3. 只处理 src/main 下的非测试文件。

用法：python docs/tools/vendor_mika_missing.py [--dry]
"""
import glob
import io
import os
import sys

_PKG_GUARD = "@@PKG@@"
OLD = "com.wangzan101.mika.core"
NEW = "com.ai.assistance.quro.core.mika"

CANDIDATES = ["D:/mika-src", "D:/CalwOS-build/tmp/Mika"]
MIKA = next((p for p in CANDIDATES if os.path.isdir(p)), None)
if MIKA is None:
    raise SystemExit("找不到 Mika 源码目录（试过 %s）" % CANDIDATES)

SRC_ROOT = os.path.join(MIKA, "core/src/main/kotlin")
DST_ROOT = "D:/Calw OS-project/QuroAI/mika-core/src/main/kotlin"
OLD_DIR = os.path.join("com", "wangzan101", "mika", "core")

# --force：连已存在的文件也覆盖。用于「上游 API 已漂移、必须整体对齐」的场景。
FORCE = "--force" in sys.argv


def debrand(text: str, pkg_prefix: str) -> str:
    text = text.replace(pkg_prefix, _PKG_GUARD)
    text = text.replace("Mika", "ZorvAI").replace("mika", "quro")
    return text.replace(_PKG_GUARD, pkg_prefix)


def convert(src: str, dst: str) -> None:
    text = io.open(src, encoding="utf-8").read()
    text = text.replace(OLD, NEW)
    text = text.replace("com.wangzan101.mika", NEW)
    text = debrand(text, NEW)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    io.open(dst, "w", encoding="utf-8", newline="").write(text)


def main() -> None:
    files = [
        f for f in glob.glob(os.path.join(SRC_ROOT, "**/*.kt"), recursive=True)
        if "/build/" not in f.replace("\\", "/")
    ]
    added, skipped = 0, 0
    for src in files:
        rel = os.path.relpath(os.path.abspath(src), os.path.abspath(SRC_ROOT))
        norm = rel.replace("/", os.sep)
        if norm.startswith(OLD_DIR + os.sep):
            norm = norm[len(OLD_DIR) + 1:]
        dst = os.path.join(DST_ROOT, os.path.join("com", "ai", "assistance", "quro", "core", "mika", norm))
        if os.path.exists(dst) and not FORCE:
            skipped += 1
            continue
        convert(src, dst)
        print("  +", norm)
        added += 1
    print("新增 %d 个，跳过已存在 %d 个，源共 %d" % (added, skipped, len(files)))


if __name__ == "__main__":
    main()
