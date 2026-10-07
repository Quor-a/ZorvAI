# -*- coding: utf-8 -*-
"""修第三批渲染层的 Compose 错误。

根因：**Canvas 的 lambda 是 DrawScope，不是 @Composable 上下文**，在里面读
MaterialTheme.colorScheme 会报 "@Composable invocations can only happen from the
context of a @Composable function"。正确做法是在 Canvas 外面把颜色取好、
用局部变量传进 lambda。

同时修：
- FunnelCardView 用了 st.label（存量字段是 st.name）；
- labels.isNotBlank() 应为 labels.isNotEmpty()（List 没 isNotBlank 扩展）；
- maxOf/minOf 的整型与浮点混用。
"""
import io, os, sys, re

P = "app/src/main/java/com/ai/assistance/quro/ui/QuroChatCardsEx3.kt"
s = io.open(P, encoding="utf-8").read()


def sub(old, new, tag, cnt=1):
    global s
    n = s.count(old)
    if n != cnt:
        print("ABORT %s: 命中 %d 次（期望 %d）" % (tag, n, cnt))
        sys.exit(1)
    s = s.replace(old, new)
    print("OK %s (%d 处)" % (tag, n))


# ── 1) SankeyCardView：颜色提到 Canvas 外 ──
sub(
    """    val maxVal = card.links.maxOf { abs(it.value) }.coerceAtLeast(0.0001)""",
    """    val maxVal = card.links.maxOf { abs(it.value) }.coerceAtLeast(0.0001)
    // Canvas 的 lambda 是 DrawScope（不是 @Composable），颜色必须在外面取好再传进去
    val nodeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
    val linkColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)""",
    "sankey 颜色提取",
)

sub(
    """                    drawRoundRect(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                        topLeft = Offset(x - bw / 2, y - 6f),""",
    """                    drawRoundRect(
                        color = nodeColor,
                        topLeft = Offset(x - bw / 2, y - 6f),""",
    "sankey 节点色",
)

sub(
    """                drawLine(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    start = a, end = b, strokeWidth = lw, cap = StrokeCap.Round,
                )""",
    """                drawLine(
                    color = linkColor,
                    start = a, end = b, strokeWidth = lw, cap = StrokeCap.Round,
                )""",
    "sankey 连线色",
)

# ── 2) QuadrantCardView：颜色提取 ──
sub(
    """    val maxA = card.axisMax.coerceAtLeast(0.0001)""",
    """    val maxA = card.axisMax.coerceAtLeast(0.0001)
    // 同上：Canvas 外取色
    val hiFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
    val axisLine = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val dotColor = MaterialTheme.colorScheme.primary""",
    "quadrant 颜色提取",
)

sub(
    """            drawRect(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                topLeft = Offset(w / 2, 0f), size = Size(w / 2, h / 2),
            )
            drawLine(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                start = Offset(w / 2, 0f), end = Offset(w / 2, h), strokeWidth = 1f,
            )
            drawLine(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                start = Offset(0f, h / 2), end = Offset(w, h / 2), strokeWidth = 1f,
            )""",
    """            drawRect(color = hiFill, topLeft = Offset(w / 2, 0f), size = Size(w / 2, h / 2))
            drawLine(color = axisLine, start = Offset(w / 2, 0f), end = Offset(w / 2, h), strokeWidth = 1f)
            drawLine(color = axisLine, start = Offset(0f, h / 2), end = Offset(w, h / 2), strokeWidth = 1f)""",
    "quadrant 画线",
)

sub(
    """                drawCircle(color = MaterialTheme.colorScheme.primary, radius = 5f, center = Offset(x, y))""",
    """                drawCircle(color = dotColor, radius = 5f, center = Offset(x, y))""",
    "quadrant 数据点",
)

# ── 3) GraphCardView：颜色提取 + 整数运算修正 ──
sub(
    """    val maxCol = card.nodes.maxOf { if (it.col >= 0) it.col else card.nodes.indexOf(it) % 3 }.coerceAtLeast(1)
    val maxRow = card.nodes.maxOf { if (it.row >= 0) it.row else card.nodes.indexOf(it) / 3 }.coerceAtLeast(1)""",
    """    val maxCol = card.nodes.indices.maxOf { i ->
        val n = card.nodes[i]
        if (n.col >= 0) n.col else i % 3
    }.coerceAtLeast(1)
    val maxRow = card.nodes.indices.maxOf { i ->
        val n = card.nodes[i]
        if (n.row >= 0) n.row else i / 3
    }.coerceAtLeast(1)
    val edgeColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val shapeColor = mapOf(
        "service" to MaterialTheme.colorScheme.primary,
        "db" to ExCardPalette.INFO,
        "queue" to ExCardPalette.WARNING,
    )
    val fallbackNodeColor = MaterialTheme.colorScheme.secondary""",
    "graph 颜色提取 + 坐标修正",
)

sub(
    """                drawLine(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    start = a, end = b, strokeWidth = 1.2f,""",
    """                drawLine(
                    color = edgeColor,
                    start = a, end = b, strokeWidth = 1.2f,""",
    "graph 连线色",
)

sub(
    """                val c = when (n.shape.lowercase()) {
                    "service" -> MaterialTheme.colorScheme.primary
                    "db" -> ExCardPalette.INFO
                    "queue" -> ExCardPalette.WARNING
                    else -> MaterialTheme.colorScheme.secondary
                }""",
    """                val c = shapeColor[n.shape.lowercase()] ?: fallbackNodeColor""",
    "graph 节点色查表",
)

# 图例里的重复 when 也换成查表
sub(
    """                        .background(
                            when (n.shape.lowercase()) {
                                "service" -> MaterialTheme.colorScheme.primary
                                "db" -> ExCardPalette.INFO
                                "queue" -> ExCardPalette.WARNING
                                else -> MaterialTheme.colorScheme.secondary
                            }
                        ),""",
    """                        .background(shapeColor[n.shape.lowercase()] ?: fallbackNodeColor),""",
    "graph 图例色",
)

# ── 4) FunnelCardView：st.label -> st.name ──
sub("st.label,\n                        style = MaterialTheme.typography.bodyMedium,",
    "st.name,\n                        style = MaterialTheme.typography.bodyMedium,",
    "funnel 字段名 label->name",
)

# ── 5) isNotBlank -> isNotEmpty ──
sub("if (labels.isNotBlank()) {", "if (labels.isNotEmpty()) {", "List.isNotBlank 修正",
)

tmp = P + ".tmp"
with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
os.replace(tmp, P)
print("DONE")