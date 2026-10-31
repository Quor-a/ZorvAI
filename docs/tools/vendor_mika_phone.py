"""搬迁 Mika 的后台虚拟屏操作层（cap/phone/a11y + display 及其小依赖）到 :mika-phone。

只搬 23 个文件，不做整包扩散：
  a11y 15 个 + display 3 个 + shell 3 个（SystemIntents/MainScreenLog/MainScreenWatch）
  + i18n 1 个（AppLanguage）+ vision 1 个（BitmapImageOps）

包名映射：
  cap.phone.*  → com.ai.assistance.quro.core.mika.phone.*
  core.*       → com.ai.assistance.quro.core.mika.*        （已在 :mika-core）
  i18n.*       → com.ai.assistance.quro.core.mika.i18n.*
  vision.*     → com.ai.assistance.quro.core.mika.appvision.*

宿主绑定替换（唯一的功能性改动，其余逐字节保真）：
  IMikaShell   → QuroShellPort
  ShellService → ShellCodes

注意替换顺序：先换宿主绑定（带完整包名），再换包名前缀，最后才做去品牌化文案替换，
否则 "Mika"→"ZorvAI" 会把 MikaShellPort 改成 ZorvAIShellPort。
"""
import io
import os

# 去品牌化时保护包名前缀：否则 "mika"→"quro" 会把 com.ai.assistance.quro.core.mika 改成 ...core.quro
_PKG_GUARD = "@@PKG@@"


def debrand(text: str, pkg_prefix: str) -> str:
    text = text.replace(pkg_prefix, _PKG_GUARD)
    text = text.replace("Mika", "ZorvAI").replace("mika", "quro")
    return text.replace(_PKG_GUARD, pkg_prefix)


MIKA = "D:/CalwOS-build/tmp/Mika/app/src/main/java/com/wangzan101/mika"
DST = "D:/Calw OS-project/QuroAI/mika-phone/src/main/kotlin"

FILES = [
    ("cap/phone/a11y/AndroidScreen.kt", "phone/a11y"),
    ("cap/phone/a11y/AppCopyReader.kt", "phone/a11y"),
    ("cap/phone/a11y/AppSwitch.kt", "phone/a11y"),
    ("cap/phone/a11y/ChangeWait.kt", "phone/a11y"),
    ("cap/phone/a11y/LazyPhoneScreen.kt", "phone/a11y"),
    ("cap/phone/a11y/MainScreenCapture.kt", "phone/a11y"),
    ("cap/phone/a11y/MainScreenSpill.kt", "phone/a11y"),
    ("cap/phone/a11y/NodeIds.kt", "phone/a11y"),
    ("cap/phone/a11y/OverlayHost.kt", "phone/a11y"),
    ("cap/phone/a11y/PhoneOcr.kt", "phone/a11y"),
    ("cap/phone/a11y/ScreenAccessibility.kt", "phone/a11y"),
    ("cap/phone/a11y/ScreenReader.kt", "phone/a11y"),
    ("cap/phone/a11y/StillFrameRead.kt", "phone/a11y"),
    ("cap/phone/a11y/SystemPickers.kt", "phone/a11y"),
    ("cap/phone/a11y/WheelDriver.kt", "phone/a11y"),
    ("cap/phone/display/AgentDisplay.kt", "phone/display"),
    ("cap/phone/display/FrameMath.kt", "phone/display"),
    ("cap/phone/display/LazyDisplay.kt", "phone/display"),
    ("cap/phone/shell/SystemIntents.kt", "phone/shell"),
    ("cap/phone/shell/MainScreenLog.kt", "phone/shell"),
    ("cap/phone/shell/MainScreenWatch.kt", "phone/shell"),
    ("i18n/AppLanguage.kt", "i18n"),
    ("vision/BitmapImageOps.kt", "appvision"),
]

PKG_RULES = [
    ("com.wangzan101.mika.cap.phone", "com.ai.assistance.quro.core.mika.phone"),
    ("com.wangzan101.mika.core.", "com.ai.assistance.quro.core.mika."),
    ("com.wangzan101.mika.i18n", "com.ai.assistance.quro.core.mika.i18n"),
    ("com.wangzan101.mika.vision", "com.ai.assistance.quro.core.mika.appvision"),
    ("com.wangzan101.mika", "com.ai.assistance.quro.core.mika"),
]




copied = 0
for src_rel, out_rel in FILES:
    text = io.open(os.path.join(MIKA, src_rel), encoding="utf-8").read()

    # 1) 宿主绑定点（必须在包名替换之前，且带全限定名前缀）
    text = text.replace(
        "com.wangzan101.mika.shell.IMikaShell",
        "com.ai.assistance.quro.core.mika.phone.shell.QuroShellPort",
    )
    text = text.replace(
        "com.wangzan101.mika.cap.phone.shell.ShellService",
        "com.ai.assistance.quro.core.mika.phone.shell.ShellCodes",
    )

    # 2) 包名前缀
    for a, b in PKG_RULES:
        text = text.replace(a, b)

    # 3) 裸标识符（import 已改，这里的用法还没改）
    text = text.replace("IMikaShell", "QuroShellPort")
    text = text.replace("ShellService.", "ShellCodes.")
    text = text.replace("ShellService", "ShellCodes")

    # 4) package 行按目录重写
    pkg = "com.ai.assistance.quro.core.mika." + out_rel.replace("/", ".")
    lines = text.split("\n")
    for i, l in enumerate(lines):
        if l.startswith("package "):
            lines[i] = "package " + pkg
            break
    text = "\n".join(lines)

    # 5) 去品牌化文案（此时已无需要保留的 Mika 标识符）
    text = debrand(text, "com.ai.assistance.quro.core.mika")

    out_dir = os.path.join(DST, "com/ai/assistance/quro/core/mika", out_rel)
    os.makedirs(out_dir, exist_ok=True)
    io.open(os.path.join(out_dir, os.path.basename(src_rel)), "w",
            encoding="utf-8", newline="").write(text)
    copied += 1

print(f"copied {copied} files")
