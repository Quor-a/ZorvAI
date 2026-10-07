# -*- coding: utf-8 -*-
"""只追加qk_03909（更多操作）一个键，12 语言。

🔴 两个必须遵守的坑（本轮都踩了）：
1) `qk_03909` 这个键本身**没问题**（max=3908，3909 空闲）。之前 aapt2 报
   "Failed to flatten ... qk_03909" 是**假象** —— aapt2 遇到第一个非法转义就
   整体放弃并把当时正在处理的 key 报出来。真正的元凶是法语那行
   `Plus d\'actions`：反斜杠在 Android XML 里不是转义符，`\'` 非法。
2) 因此法语必须写 `&apos;` 或**裸单引号**，绝不能是 `\'`。
   本轮统一用裸单引号（XML 文本节点里完全合法，最不容易再出问题）。
"""
import io
import os
import time

KEY = "qk_03909"
VALUES = {
    "values": "更多操作",
    "values-zh": "更多操作",
    "values-en": "More actions",
    "values-ja": "その他の操作",
    "values-ko": "더 많은 작업",
    "values-fr": "Plus d'actions",
    "values-de": "Weitere Aktionen",
    "values-es": "Más acciones",
    "values-pt": "Mais ações",
    "values-ru": "Действия",
    "values-ar": "إجراءات أخرى",
    "values-hi": "और क्रियाएँ",
}
BS = chr(92)
for d, zh in VALUES.items():
    P = "app/src/main/res/%s/strings_i18n.xml" % d
    if not os.path.exists(P):
        print("skip:", d)
        continue
    s = io.open(P, encoding="utf-8").read()
    if 'name="%s"' % KEY in s:
        print("exists:", d)
        continue
    assert BS + chr(39) not in zh, "值里不能有反斜杠+单引号: %s" % d
    idx = s.rfind("</resources>")
    assert idx > 0, P
    line = '\n    <!-- ⋮ 溢出菜单无障碍标签（Web 应用 / 小程序详情顶栏） -->\n' \
        if d == "values" else "\n"
    line += '    <string name="%s" formatted="false">%s</string>\n' % (KEY, zh)
    s = s[:idx] + line + s[idx:]
    io.open(P + ".tmp", "w", encoding="utf-8", newline="\n").write(s)
    for i in range(8):
        try:
            os.replace(P + ".tmp", P)
            print("OK %-10s %s" % (d, zh))
            break
        except OSError:
            time.sleep(1.5)