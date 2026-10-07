# -*- coding: utf-8 -*-
"""第三批 9 种组件的渲染分派接线（QuroChatCardView 穷尽 when + import）。"""
import io, os, sys

P = "app/src/main/java/com/ai/assistance/quro/ui/QuroChatCards.kt"
src = io.open(P, encoding="utf-8").read()

# ---- 1) import ----
IMPORTS = [
    "ConfirmCard", "DecisionCard", "FeedCard", "FunnelCard", "GraphCard",
    "MatrixCard", "QuadrantCard", "SankeyCard", "SectionCard", "WaterfallCard",
]
missing = [c for c in IMPORTS if ("import com.ai.assistance.quro.core.cards.%s\n" % c) not in src]
if missing:
    anchor = "import com.ai.assistance.quro.core.cards.CustomCard\n"
    if src.count(anchor) != 1:
        # 退而求其次：找任一 cards import 作为锚点
        import re
        m = re.search(r"import com\.ai\.assistance\.quro\.core\.cards\.[A-Za-z]+\n", src)
        if not m:
            print("ABORT: 找不到 cards import 锚点")
            sys.exit(1)
        anchor = m.group(0)
    add = "".join("import com.ai.assistance.quro.core.cards.%s\n" % c for c in missing)
    src = src.replace(anchor, anchor + add, 1)
    print("OK import: +%d" % len(missing))
else:
    print("SKIP import")

# ---- 2) 渲染分派穷尽 when ----
DISPATCH = """            // ── 第三批（v1400-b3）：AI 征询决策 + 可视化进阶 ──
            is DecisionCard -> DecisionCardView(card, onCommand)
            is ConfirmCard -> ConfirmCardView(card, onCommand)
            is SankeyCard -> SankeyCardView(card)
            is FunnelCard -> FunnelCardView(card)
            is WaterfallCard -> WaterfallCardView(card)
            is QuadrantCard -> QuadrantCardView(card)
            is MatrixCard -> MatrixCardView(card)
            is FeedCard -> FeedCardView(card)
            is GraphCard -> GraphCardView(card)
            is SectionCard -> SectionCardView(card)
"""

if "is DecisionCard -> DecisionCardView" in src:
    print("SKIP 分派")
else:
    anchor = "            is CustomCard -> "
    if src.count(anchor) != 1:
        print("ABORT: CustomCard 分派锚点命中 %d 次" % src.count(anchor))
        sys.exit(1)
    src = src.replace(anchor, DISPATCH + anchor, 1)
    print("OK 分派")

tmp = P + ".tmp"
with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
os.replace(tmp, P)
print("DONE")