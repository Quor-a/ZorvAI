# -*- coding: utf-8 -*-
"""把本次新增/改写的文案里未转义的撇号转义掉。

真因：Android 字符串资源解析时单引号必须写成反斜杠 + 单引号（或整串用双引号包住），
formatted="false" 并不豁免这条。AGP 的报错文案却是
"Invalid unicode escape sequence"——指向性很差：实际是撇号，不是反斜杠 u。
en 的 component's、fr 的 l'interface 都踩了。

只动本次涉及的 4 个键，别的行不碰。原子写。
"""
import io
import os
import re

ROOT = r"D:/Calw OS-project/QuroAI/app/src/main/res"
KEYS = ["qk_04069", "qk_04070", "qk_04071", "qk_04076"]

APOS = "'"
BACKSLASH = "\\"
fixed = 0

for dirpath, _, filenames in os.walk(ROOT):
    if "strings_i18n.xml" not in filenames:
        continue
    p = os.path.join(dirpath, "strings_i18n.xml")
    s = io.open(p, encoding="utf-8").read()
    orig = s

    for k in KEYS:
        pat = re.compile(r'(<string name="%s"[^>]*>)(.*?)(</string>)' % re.escape(k), re.S)

        def rep(m):
            global fixed
            body = m.group(2)
            if APOS not in body:
                return m.group(0)
            # 只转义尚未被转义的撇号，避免把已有的 \" 变成 \\"
            new = re.sub(r"(?<!%s)%s" % (re.escape(BACKSLASH), re.escape(APOS)),
                         BACKSLASH + APOS, body)
            if new != body:
                fixed += 1
            return m.group(1) + new + m.group(3)

        s = pat.sub(rep, s)

    if s != orig:
        tmp = p + ".tmp"
        with io.open(tmp, "w", encoding="utf-8", newline="") as f:
            f.write(s)
        os.replace(tmp, p)
        print("改:", os.path.relpath(p, ROOT).replace("\\", "/"))

print("共处理", fixed, "条")
