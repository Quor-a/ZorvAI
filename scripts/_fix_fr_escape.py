# -*- coding: utf-8 -*-
"""修 values-fr/strings.xml 里历史遗留的非法 \\' 转义。

Android XML 不认反斜杠转义（只认 &#39; / &apos; / 直接写 ' ），
`d\'` 这类写法会让 aapt2 报 "Invalid unicode escape sequence in string" → 整包编译失败。
本轮只是因为新增键触发重新 flatten 才暴露出来，属**既有缺陷**，不是新引入。

规则：把「字母 + \' + 字母」里的反斜杠直接删掉（法语里本来就是省音符号，
写成 d' 即可，不需要转义）。
"""
import io
import os
import re
import time

CHANGED = []
for d in ["values-fr", "values", "values-en", "values-zh", "values-ja", "values-ko",
          "values-es", "values-pt", "values-de", "values-ru", "values-ar", "values-hi"]:
    P = "app/src/main/res/%s/strings.xml" % d
    if not os.path.exists(P):
        continue
    s = io.open(P, encoding="utf-8").read()
    if "\\'" not in s:
        continue
    n = s.count("\\'")
    # 只处理「反斜杠+单引号」紧跟在字母后、后面紧跟字母的情形（法语省音）。
    # 保守：把 \' 全局换成 &#39; —— XML 里 &#39; 是合法实体，任何位置都对。
    s2 = s.replace("\\'", "&#39;")
    io.open(P + ".tmp", "w", encoding="utf-8", newline="\n").write(s2)
    for i in range(8):
        try:
            os.replace(P + ".tmp", P)
            CHANGED.append((d, n))
            break
        except OSError:
            time.sleep(1.5)

for d, n in CHANGED:
    print("fixed %-10s %d 处" % (d, n))
if not CHANGED:
    print("no change needed")