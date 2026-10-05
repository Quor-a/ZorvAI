# -*- coding: utf-8 -*-
import io, os

p = r"D:/Calw OS-project/QuroAI/app/src/main/java/com/ai/assistance/quro/ui/QuroChatCardsEx.kt"
s = io.open(p, encoding="utf-8").read()
lines = s.split("\n")

# ① helper 块被误插到 package 之前（import 全被挤到中段 → "imports are only allowed
#    at the beginning of file"）。挪到最后一个 import 之后。
assert lines[0].startswith("// ─────────────── 卡片内部联动的小协议"), lines[0]
block = lines[0:27]          # 注释 + 3 个 internal fun
rest = lines[27:]            # rest[0] == "package ..."
assert rest[0].startswith("package "), rest[0]
last_imp = max(i for i, l in enumerate(rest) if l.startswith("import "))
out = rest[: last_imp + 1] + [""] + block + [""] + rest[last_imp + 1 :]
s = "\n".join(out)

# ② 嵌套 lambda 里 `it` 被 getOrElse 抢了名字，改成显式下标取
s = s.replace("val isDone = checked.getOrElse(i) { it.done }",
              "val isDone = if (i < checked.size) checked[i] else card.items[i].done")

io.open(p + ".tmp", "w", encoding="utf-8").write(s)
os.replace(p + ".tmp", p)
print("OK", last_imp)
