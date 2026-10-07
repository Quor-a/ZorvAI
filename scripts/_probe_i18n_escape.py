# -*- coding: utf-8 -*-
"""aapt2 报某键非法转义，但同名键在 strings.xml里是干净的 → 真凶在 strings_i18n.xml。

Android 资源合并规则：**同一个 res/values* 目录下同名 key 会跨文件共存**，
但 aapt2 报错时给的是 values-en/strings.xml 的行列（因为它在合并输出里
第一个被扫到）。所以必须回到**每个values*/ 下的所有 strings*.xml** 里找。
"""
import glob
import io
import os
import re

BS = chr(92)
Q = chr(39)
TARGETS = ["config_hint", "config_info_visual", "qk_03909"]

for f in glob.glob("app/src/main/res/values*/strings*.xml"):
    s = io.open(f, encoding="utf-8").read()
    if BS + Q not in s:
        continue
    for t in TARGETS:
        key = 'name="%s"' % t
        i = s.find(key)
        while i > 0:
            end = s.find("</string>", i)
            seg = s[i:end]
            if BS + Q in seg:
                j = seg.find(BS + Q)
                print("%s :: %s" % (f, t))
                print("   ", repr(seg[max(0, j - 60):j + 30]))
            i = s.find(key, i + 1)

print("--- 全仓 values* 里所有 \\' 出现位置 ---")
for f in glob.glob("app/src/main/res/values*/strings*.xml"):
    s = io.open(f, encoding="utf-8").read()
    n = s.count(BS + Q)
    if n:
        print("%-58s %d" % (f, n))