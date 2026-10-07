# -*- coding: utf-8 -*-
"""ChatScreen：把围栏属性接到渲染层。

1. `fenceCards` 从「扁平成 List<QuroChatCard>」改成「每组带 attrs」，
   这样同一围栏里的卡片能共用同一个 theme/compact —— 围栏属性本来就是组级语义。
2. 围栏级 title= 作为**外层标题**渲染（不改卡片自身的 title）：
   卡片是不可变 data class，通用改 title 需要 101 个 copy()，而且会覆盖单卡自带标题；
   外层标题语义更准（「这一组是 Q3 复盘」而不是「把每张卡都叫 Q3 复盘」）。
3. cardjson 逐行围栏特别处理：它是流式增量渲染，标题只在围栏**闭合后**出现一次。

原子写：.tmp + os.replace
"""
import io
import os

P = "app/src/main/java/com/ai/assistance/quro/ui/ChatScreen.kt"
s = io.open(P, encoding="utf-8").read()

old = """        val fenceCards = remember(blocks) {
            blocks.filterIsInstance<MsgBlock.Card>()
                .mapNotNull { blk -> runCatching { CardFence.toCards(blk.fence, blk.source) }.getOrNull() }
                .flatten()
                // 流式防护：数据还没写完（表格/饼图/图表/热力图/雷达空数据）时不显示，
                // 下一帧数据到齐重解析就会产出完整卡片，避免闪一下「（无数据）」。
                .filter { c -> !cardHasNoData(c) }
        }
        if (fenceCards.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().clipToBounds()) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = Int.MAX_VALUE,
                ) {
                    for (c in fenceCards) {
                        key(c.id) {
                            QuroChatCardView(
                                c,
                                onCommand,
                                modifier = if (isCompactQuroCard(c)) Modifier.wrapContentWidth() else Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }"""

new = """        // 按围栏分组保留（而不是直接 flatten）：theme/compact 是**组级**属性，
        // flatten 之后卡片就不知道该跟哪一组的主题走了。
        val fenceGroups = remember(blocks) {
            blocks.filterIsInstance<MsgBlock.Card>().mapNotNull { blk ->
                val cards = runCatching { CardFence.toCards(blk.fence, blk.source) }.getOrNull()
                    // 流式防护：数据还没写完（表格/饼图/图表/热力图/雷达空数据）时不显示，
                    // 下一帧数据到齐重解析就会产出完整卡片，避免闪一下「（无数据）」。
                    ?.filter { c -> !cardHasNoData(c) }
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                CardFence.Grouped(
                    cards = cards,
                    title = CardFence.parseValueAttrs(blk.attrs)["title"].orEmpty(),
                    theme = CardFence.parseValueAttrs(blk.attrs)["theme"]?.lowercase()?.takeIf { it in CardFence.THEME_PRESETS } ?: "accent",
                    compact = CardFence.parseAttrs(blk.attrs).contains("compact"),
                    // 逐行围栏流式时会反复重解析，标题只在闭合后给，避免每帧闪标题
                    showTitle = blk.fence != CardFence.FENCE_CARDJSON || blk.closed,
                )
            }
        }
        if (fenceGroups.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            for (grp in fenceGroups) {
                if (grp.showTitle && grp.title.isNotBlank()) {
                    Text(
                        grp.title,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                    )
                }
                CompositionLocalProvider(
                    LocalCardTheme provides grp.theme,
                    LocalCardCompact provides grp.compact,
                ) {
                    Box(Modifier.fillMaxWidth().clipToBounds()) {
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            maxItemsInEachRow = Int.MAX_VALUE,
                        ) {
                            for (c in grp.cards) {
                                key(c.id) {
                                    QuroChatCardView(
                                        c,
                                        onCommand,
                                        modifier = if (isCompactQuroCard(c)) Modifier.wrapContentWidth() else Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }"""

assert s.count(old) == 1, "fenceCards 段未唯一匹配: %d" % s.count(old)
s = s.replace(old, new)

tmp = P + ".tmp"
io.open(tmp, "w", encoding="utf-8", newline="\n").write(s)
os.replace(tmp, P)
print("OK", P)