# -*- coding: utf-8 -*-
"""第三批增强了 funnel（加 showRate/unit/hint、value 放宽为 Double），
存量第一版的 FunnelCardView 不删会**重名冲突**，且它用 Float 运算已编译不过。

处理：删掉存量旧渲染，由第三批增强版独占。
渲染能力只增不减 —— 旧版只有「层宽 + 箭头」，新版多了留存率、单位、流失原因。
"""
import io, os, sys

# ── 1) 删存量旧 FunnelCardView ──
P = "app/src/main/java/com/ai/assistance/quro/ui/QuroChatCardsEx.kt"
L = io.open(P, encoding="utf-8").read().split("\n")

start = None
for i, l in enumerate(L):
    if l.startswith("/** 漏斗图：层宽按 value/max 收窄"):
        start = i
        break
if start is None:
    print("ABORT: 未找到存量漏斗渲染")
    sys.exit(1)

# 结束：该 @Composable fun 结束后第一个顶层空行
end = None
for j in range(start + 1, len(L)):
    if L[j].startswith("/** ") and j > start + 3:
        end = j
        break
if end is None:
    print("ABORT: 未找到漏斗渲染结束边界")
    sys.exit(1)

print("将删 %d 行（%d..%d）" % (end - start, start + 1, end))
print("首行:", L[start])
print("末行:", L[end - 1])

removed = "\n".join(L[start:end])
# 留下一个说明占位，避免后来人以为漏斗渲染丢了
note = [
    "// 漏斗图渲染已迁到 QuroChatCardsEx3.kt 的 FunnelCardView（v1400 第三批增强版：",
    "// 多了留存率、单位、每级流失原因，且 value 放宽为 Double 以免小数被截断）。",
    "// 旧实现只画「层宽 + 箭头」，能力是新版子集，故直接删除而非并存。",
    "",
]
L[start:end] = note

s = "\n".join(L)
tmp = P + ".tmp"
with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
os.replace(tmp, P)
print("OK: 存量漏斗渲染已删除")

# ── 2) 分派处若指向旧签名则改新签名 ──
P2 = "app/src/main/java/com/ai/assistance/quro/ui/QuroChatCards.kt"
c = io.open(P2, encoding="utf-8").read()
old = "is FunnelCard -> FunnelCardView(card, onCommand)"
new = "is FunnelCard -> FunnelCardView(card)"
if old in c:
    c = c.replace(old, new)
    tmp2 = P2 + ".tmp"
    with io.open(tmp2, "w", encoding="utf-8", newline="\n") as f:
        f.write(c)
    os.replace(tmp2, P2)
    print("OK: 分派签名已改")
else:
    print("SKIP 分派签名（可能已是新签名或多处）")