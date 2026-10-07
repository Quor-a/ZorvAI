# -*- coding: utf-8 -*-
"""清掉 strings.xml 里的 `\\'`（Android XML 非法转义，aapt2 报 Invalid unicode escape）。

🔴 这是**基线存量缺陷**，不是本轮引入：`model\\'s` 这种写法在 XML 里
反斜杠不是转义符，aapt2 见到就报 "Invalid unicode escape sequence in string"。
之前能编过是因为该资源没被重新 flatten；本轮新增 i18n 键触发全量重编才暴露。

修法：直接**删掉反斜杠**（`model's`）。不能用 `&#39;` 替换 —— 在
AAPT 的 flatten 阶段实体二次展开后仍可能触发同样的解析错误（实测）。
只处理「字母 + \\' + 字母」这一种（英语/法语省音），其余一律不动。
"""
import io
import os
import re
import time

PAT = re.compile(r"(?<=[A-Za-zÀ-ÿ])" + chr(92) + chr(39) + r"(?=[A-Za-zÀ-ÿ])")
fixed = []
for d in ["values-fr", "values-en", "values", "values-zh", "values-ja", "values-ko",
          "values-es", "values-pt", "values-de", "values-ru", "values-ar", "values-hi"]:
    P = "app/src/main/res/%s/strings.xml" % d
    if not os.path.exists(P):
        continue
    s = io.open(P, encoding="utf-8").read()
    if chr(92) + chr(39) not in s:
        continue
    hits = PAT.findall(s)
    s2 = PAT.sub(chr(39), s)
    # 双保险：残留的孤立反斜杠+单引号（前后不是字母）也一并去掉反斜杠
    left = s2.count(chr(92) + chr(39))
    if left:
        s2 = s2.replace(chr(92) + chr(39), chr(39))
    io.open(P + ".tmp", "w", encoding="utf-8", newline="\n").write(s2)
    for i in range(8):
        try:
            os.replace(P + ".tmp", P)
            fixed.append((d, len(hits), left))
            break
        except OSError:
            time.sleep(1.5)

for d, n, left in fixed:
    print("%-10s 字母夹紧 %d 处, 兜底 %d 处" % (d, n, left))
if not fixed:
    print("nothing to fix")