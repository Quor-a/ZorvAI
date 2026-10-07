# -*- coding: utf-8 -*-
"""清 values-fr/strings_i18n.xml 里 qk_03906 的非法 `\\'`，**写盘后立刻校验**。

上一轮教训：清理脚本跑完，随后有 `git checkout -- app/src/main/res/` 把文件
恢复成带`\'` 的版本，导致「以为已修好、其实没修」，白排查半小时。
所以这次：**改完立刻读回验证**，不靠记忆。
"""
import io
import os
import time

BSQ = chr(92) + chr(39)
P = "app/src/main/res/values-fr/strings_i18n.xml"

s = io.open(P, encoding="utf-8").read()
n = s.count(BSQ)
print("改前 %d 处" % n)
assert n > 0, "已经是干净的？"

# 只动 qk_03906 那一行，最小化影响面
L = s.split(chr(10))
hit = 0
for i, line in enumerate(L):
    if "qk_03906" in line and BSQ in line:
        L[i] = line.replace(BSQ, chr(39))
        print("修line %d" % (i + 1))
        hit += 1
assert hit == 1, "hit=%d" % hit
s2 = chr(10).join(L)

io.open(P + ".tmp", "w", encoding="utf-8", newline=chr(10)).write(s2)
for i in range(8):
    try:
        os.replace(P + ".tmp", P)
        break
    except OSError:
        time.sleep(1.5)

# 🔴 立刻读回验证（不信记忆）
chk = io.open(P, encoding="utf-8").read()
print("读回校验: 该文件非法 \\' =", chk.count(BSQ))
assert BSQ not in chk, "仍然存在！"
L2 = chk.split(chr(10))
for i, line in enumerate(L2):
    if "qk_03906" in line:
        print("现在:", repr(line[:160]))
        break
print("OK 已确认写盘生效")