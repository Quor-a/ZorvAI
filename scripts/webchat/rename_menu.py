# -*- coding: utf-8 -*-
"""把菜单项 "Web UI 卡片"（各语言均带 card 字样）统一改为 "Web UI"。

用户 2026-10-13 明确：这个对话框是「Web UI」，不是「Web UI 卡片」——
卡片（zxwidget 动态交互界面）是另一码事，只是被这个对话框所支持。
Web UI 是专有名词，各语言不翻译。

原子写：先写 .tmp 再 os.replace，避免中途失败把文件截断成 0 字节。
"""
import io
import os
import re

ROOT = r"D:/Calw OS-project/QuroAI/app/src/main/res"
NAME = "qk_04072"

pat = re.compile(
    r'(<string name="%s"[^>]*>)(.*?)(</string>)' % re.escape(NAME), re.S)

hits = 0
for dirpath, _, filenames in os.walk(ROOT):
    for fn in filenames:
        if fn != "strings_i18n.xml":
            continue
        path = os.path.join(dirpath, fn)
        src = io.open(path, encoding="utf-8").read()
        new, n = pat.subn(lambda m: m.group(1) + "Web UI" + m.group(3), src)
        if n == 0:
            continue
        tmp = path + ".tmp"
        with io.open(tmp, "w", encoding="utf-8", newline="") as f:
            f.write(new)
        os.replace(tmp, path)
        hits += n
        print("改:", os.path.relpath(path, ROOT).replace("\\", "/"), "-> Web UI")

print("共改", hits, "处")
