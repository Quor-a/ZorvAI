# -*- coding: utf-8 -*-
"""全仓（**含 AndroidX/Material 库带来的 values*/strings.xml**）清掉非法 `\\'` 转义。

## 真因（2026-10-06 排查结论）
aapt2 报的 `Failed to flatten XML for resource 'XXX'` 里的 **XXX 是无辜的**——
它是报错时刻正在处理的那个 key，不是真正含非法转义的那个。
第一次报 `qk_03909`（我新加的），清掉法语里的 `d\'actions` 后改报 `config_hint`，
清掉 en 里的 `model\'s` 后又报 `config_info_visual` ……
真正的元凶是**AndroidX / Material 依赖库里带过来的 `\'`**（`won\'t`、`o\'clock`、
`l\'entrada`…散布在几十个 values-<locale>/strings.xml 里）。
只要有一个，**整个资源编译就失败** —— 表现为「新增任何 i18n 键都编不过」。

## 修法
Android XML 里反斜杠不是通用转义符，`\'` 一律非法 → 直接删掉反斜杠。
范围：`app/src/main/res/values*/**/*.xml`（含库资源），
不动 `\n` `\t` `\r` `\\` `\"` 这些合法转义。
"""
import glob
import io
import os
import time

BS = chr(92)
Q = chr(39)
LEGAL = ("n", "t", "r", BS, chr(34))

files = sorted(glob.glob("app/src/main/res/values*/*.xml"))
total = 0
for f in files:
    try:
        s = io.open(f, encoding="utf-8").read()
    except Exception as e:
        print("skip", f, e)
        continue
    if BS + Q not in s:
        continue
    n = s.count(BS + Q)
    s2 = s.replace(BS + Q, Q)
    io.open(f + ".tmp", "w", encoding="utf-8", newline="\n").write(s2)
    for i in range(8):
        try:
            os.replace(f + ".tmp", f)
            print("fixed %-60s %d" % (f, n))
            total += n
            break
        except OSError:
            time.sleep(1.5)

print("=== 共清理 %d 处非法转义，覆盖 %d 个文件 ===" % (total, len(set()) if total == 0 else 0))

# 复查：还有没有残留
left = 0
for f in files:
    try:
        s = io.open(f, encoding="utf-8").read()
    except Exception:
        continue
    if BS + Q in s:
        left += s.count(BS + Q)
        print("STILL BAD:", f, s.count(BS + Q))
print("残留:", left)