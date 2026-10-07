# -*- coding: utf-8 -*-
r"""清掉 values-fr/strings_i18n.xml 里 qk_03906 的 3 处非法 `\\'`（基线存量缺陷）。

## 为什么拖了这么久才定位到
aapt2 遇到第一个非法转义就**整体放弃**资源编译，并把「当时正在处理的 key」
报进错误信息里 —— 于是报的是 `qk_03909` / `config_hint` / `config_info_visual`
这些**无辜的键**，每修一个就换一个报错，很有迷惑性。
真正元凶是 `qk_03906`（法语 `l\'inférence` 这类省音写法）。

🔴 教训（本仓新铁律）：
**aapt2 报的 key 不可信**，它只是「报错时刻扫到的那个 key」。
见到 `Failed to flatten XML for resource 'XXX'` 时，
必须用脚本扫该 locale 目录下**所有** xml 的非法反斜杠序列，别跟着报错键跑。
反斜杠在 Android XML 里只对 `\n \t \r \\ \"` 合法，其余（`\'` `` \` `` `\x`）一律非法。
"""
import io
import os
import time

P = "app/src/main/res/values-fr/strings_i18n.xml"
s = io.open(P, encoding="utf-8").read()
BSQ = chr(92) + chr(39)
n = s.count(BSQ)
print("before: %d 处" % n)
s2 = s.replace(BSQ, chr(39))
assert BSQ not in s2
io.open(P + ".tmp", "w", encoding="utf-8", newline="\n").write(s2)
for i in range(8):
    try:
        os.replace(P + ".tmp", P)
        print("OK清掉 %d 处" % n)
        break
    except OSError:
        time.sleep(1.5)

# 全仓复查一遍所有 locale
import glob
left = 0
for f in glob.glob("app/src/main/res/values*/*.xml"):
    t = io.open(f, encoding="utf-8").read()
    for i, ch in enumerate(t):
        if ch == chr(92):
            nxt = t[i + 1] if i + 1 < len(t) else ""
            if nxt not in ("n", "t", "r", chr(92), chr(34), "u"):
                left += 1
                print("STILL BAD:", f, repr(t[max(0, i - 50):i + 15]))
print("全仓残留非法转义:", left)