# -*- coding: utf-8 -*-
"""删掉现在已成死代码的旧脚注分支（新分支已前移到链接之前）。"""
import io
import os

P = r"D:\Calw OS-project\QuroAI\app\src\main\java\com\ai\assistance\quro\ui\dialog\RichText.kt"
with io.open(P, "r", encoding="utf-8", newline="") as f:
    raw = f.read()
nl = "\r\n" if "\r\n" in raw else "\n"
t = raw.replace("\r\n", "\n")

OLD = """            // 脚注引用 [^1] → 上标数字
            c == '[' && i + 1 < n && src[i + 1] == '^' -> {
                val close = src.indexOf(']', i)
                if (close > i + 2) emit(src.substring(i + 2, close)) { it.copy(sup = true) }
                else buf.append(c)
                i = if (close > i + 2) close + 1 else i + 1
            }

"""
n = t.count(OLD)
print("hits:", n)
if n == 1:
    t = t.replace(OLD, "")
    out = t.replace("\n", nl)
    tmp = P + ".tmp"
    with io.open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write(out)
    os.replace(tmp, P)
    print("ok", os.path.getsize(P))
else:
    print("FAIL")
