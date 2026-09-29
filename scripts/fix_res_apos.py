# -*- coding: utf-8 -*-
import io, re

p = "app/src/main/res/values-en/strings.xml"
d = io.open(p, encoding="utf-8").read()

pat = re.compile(r'(<string name="[^"]*">)([\s\S]*?)(</string>)', re.DOTALL)

def fix_val(v):
    # 把未转义的单引号 ' 转义为 \'；已转义(前面是\)的跳过；不动 &quot; 等实体。
    out = []
    i = 0
    while i < len(v):
        c = v[i]
        if c == "'":
            if i > 0 and v[i-1] == "\\":
                out.append(c)  # 已是转义
            else:
                out.append("\\'")
        else:
            out.append(c)
        i += 1
    return "".join(out)

def rep(m):
    return m.group(1) + fix_val(m.group(2)) + m.group(3)

d2 = pat.sub(rep, d)
if d2 != d:
    io.open(p, "w", encoding="utf-8").write(d2)
    print("escaped apostrophes in", p)
else:
    print("no change", p)
