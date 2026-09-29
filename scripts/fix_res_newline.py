# -*- coding: utf-8 -*-
import io, re

paths = [
    "app/src/main/res/values/strings.xml",
    "app/src/main/res/values-en/strings.xml",
    "app/src/main/res/values-zh/strings.xml",
]
NL = chr(10)          # 真实换行
ESCAPED = "\\n"       # 两字符：反斜杠 + n（Android 转义）

for p in paths:
    d = io.open(p, encoding="utf-8").read()
    changed = False
    for name in ("config_fetch_fail", "config_info_visual"):
        pat = re.compile(r'(<string name="%s">)([\s\S]*?)(</string>)' % name, re.DOTALL)
        def rep(m, _esc=ESCAPED, _nl=NL):
            return m.group(1) + m.group(2).replace(_nl, _esc) + m.group(3)
        d2 = pat.sub(rep, d)
        if d2 != d:
            d = d2
            changed = True
            print("fixed", p, name)
    if changed:
        io.open(p, "w", encoding="utf-8").write(d)
    else:
        print("unchanged", p)
