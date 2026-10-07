# -*- coding: utf-8 -*-
"""全仓清理 Android XML 里的非法反斜杠转义，并强制干净重建资源。

## 为什么必须做干净构建
这些坏转义是**基线存量**（`l\'inférence` / `model\'s` / `Plus d\'actions`），
之前能编过是因为增量构建没有重新 flatten 这些资源。本轮新增 i18n 键触发
全量资源重编，于是全部浮出来，报错还「张冠李戴」（aapt2 报的是合并产物里
第一个被扫到的键，不是真正有问题的那一个）。

## 修法
反斜杠在 Android XML 里**不是通用转义符**，合法转义只有几种。
省音写法（字母+反斜杠+单引号）一律非法，反斜杠须删掉。
处理策略（保守）：逐字符扫描，反斜杠后跟非法字符时吞掉反斜杠、保留字符。
"""
import glob
import io
import os
import re
import shutil
import time

BS = chr(92)
Q = chr(39)
DQ = chr(34)
BT = chr(96)

# Android 资源里合法的反斜杠转义（不含 \' 与 \`）
LEGAL = set(["n", "t", "r", BS + "n", BS + "t", BS + "r", "u", "b", "f"])

total = 0
for f in sorted(glob.glob("app/src/main/res/values*/strings*.xml")):
    s = io.open(f, encoding="utf-8").read()
    orig = s
    # 1) \'
    s = s.replace(BS + Q, Q)
    # 2) \`
    s = s.replace(BS + BT, BT)
    # 3) 剩余反斜杠：逐个判定合法性
    out = []
    k = 0
    while k < len(s):
        ch = s[k]
        if ch == BS and k + 1 < len(s):
            nxt = s[k + 1]
            if nxt in ("n", "t", "r"):
                out.append(BS + nxt)      # 合法：\n \t \r
                k += 2
                continue
            if nxt == BS:
                out.append(BS + BS)      # 合法：\\
                k += 2
                continue
            if nxt == DQ:
                out.append(BS + DQ)
                k += 2
                continue
            # 非法：吞掉反斜杠，保留字符本身
            out.append(nxt)
            k += 2
            continue
        out.append(ch)
        k += 1
    s = "".join(out)

    if s != orig:
        cnt = sum(1 for a, b in zip(orig, s) if a != b)
        total += 1
        print("fixed %-52s" % f)
        io.open(f + ".tmp", "w", encoding="utf-8", newline="\n").write(s)
        for i in range(8):
            try:
                os.replace(f + ".tmp", f)
                break
            except OSError:
                time.sleep(1.5)

print("共改%d 个文件" % total)

# ── 强制干净重建资源 ──
for d in ["app/build/intermediates/incremental/fullDebug/mergeFullDebugResources",
          "app/build/intermediates/merged_res/fullDebug",
          "app/build/intermediates/packaged_res/fullDebug"]:
    if os.path.isdir(d):
        for i in range(4):
            try:
                shutil.rmtree(d)
                print("cleaned", d)
                break
            except OSError:
                time.sleep(2)