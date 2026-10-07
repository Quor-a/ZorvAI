# -*- coding: utf-8 -*-
"""精确定位 values-en/strings.xml 里 config_hint 的非法转义，并清掉合并缓存。

合并产物里 config_hint 的值在 "main endpo" 处就断了 —— 说明**源文件**里
那个字符串中就有aapt2 无法解析的字节（不是合并引入的）。
"""
import io
import os
import re
import shutil
import time

# ── 1. 清合并缓存（脏的 merged.dir 会让改动不生效） ──
for d in ["app/build/intermediates/incremental/fullDebug/mergeFullDebugResources",
          "app/build/intermediates/merged_res/fullDebug"]:
    if os.path.isdir(d):
        for i in range(4):
            try:
                shutil.rmtree(d)
                print("rmtree", d)
                break
            except OSError:
                time.sleep(2)

# ── 2. 打印源文件里 config_hint 的原始内容 ──
P = "app/src/main/res/values-en/strings.xml"
s = io.open(P, encoding="utf-8").read()
i = s.find('name="config_hint"')
print("=== config_hint raw ===")
print(repr(s[i - 20: i + 420]))

BS = chr(92)
print("=== 反斜杠出现的全部位置 ===")
for m in re.finditer(re.escape(BS), s):
    k = m.start()
    seg = s[max(0, k - 30):k + 30]
    print("idx=%d %r  next3=%r" % (k, seg, s[k:k + 3]))