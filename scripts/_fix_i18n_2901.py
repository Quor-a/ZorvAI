# -*- coding: utf-8 -*-
"""追加 i18n 新键 qk_03909「更多操作」。

铁律：qk_%05d **只能追加**，绝不复用语义不符的既有键
（本轮qk_02851 已被「环境未就绪」占用）。
`formatted="false"`：该串会被 getString 无条件 String.format，裸 % 会真机崩。
"""
import io
import os
import time

NEW = {
    "values": "更多操作",
    "values-en": "More actions",
    "values-zh": "更多操作",
    "values-ja": "その他の操作",
    "values-ko": "더 많은 작업",
    "values-fr": "Plus d\\'actions",
    "values-de": "Weitere Aktionen",
    "values-es": "Más acciones",
    "values-pt": "Mais ações",
    "values-ru": "Действия",
    "values-ar": "إجراءات أخرى",
    "values-hi": "और क्रियाएँ",
}

for d, zh in NEW.items():
    P = "app/src/main/res/%s/strings_i18n.xml" % d
    if not os.path.exists(P):
        print("skip (no file):", P)
        continue
    s = io.open(P, encoding="utf-8").read()
    if 'name="qk_03909"' in s:
        print("exists:", d)
        continue
    # 插到 </resources> 前
    idx = s.rfind("</resources>")
    assert idx > 0, P
    # 中文侧对齐相邻行缩进
    line = '\n    <string name="qk_03909" formatted="false">%s</string>\n' % zh
    if d == "values":
        line = '\n    <!-- ⋮ 溢出菜单无障碍标签（Web 应用 / 小程序详情顶栏） -->\n' + line
    s = s[:idx] + line + s[idx:]
    io.open(P + ".tmp", "w", encoding="utf-8", newline="\n").write(s)
    for i in range(8):
        try:
            os.replace(P + ".tmp", P)
            print("OK %-10s -> %s" % (d, zh))
            break
        except OSError:
            time.sleep(1.5)