import io, os

p = 'app/src/main/java/com/ai/assistance/quro/ui/QuroChatCards.kt'
src = io.open(p, encoding='utf-8').read()
orig = src

old = """// ───────────── 表格 ─────────────
@Composable
private fun TableCardView(card: QuroChatCard.TableCard) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        if (card.headers.isEmpty() && card.rows.isEmpty()) {
            Text(stringResource(R.string.qk_00946), color = cs.onSurfaceVariant, fontSize = 12.sp); return@CardShell
        }
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            if (card.headers.isNotEmpty()) {
                Row(Modifier.background(cs.primaryContainer).padding(vertical = 6.dp)) {
                    card.headers.forEach { h ->
                        Text(h, color = cs.onPrimaryContainer, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.widthIn(min = 80.dp, max = 220.dp).padding(horizontal = 8.dp))
                    }
                }
            }
            // ★ ANR 防御：大表（工具/查询返回上百行）原 Column+forEach 主线程一次性布局 → 卡顿/ANR。
            // 上限渲染 80 行，超出显示脚注（完整数据仍在卡片 JSON 中）。
            val shownRows = card.rows.take(80)
            shownRows.forEachIndexed { ri, row ->
                Row(Modifier.padding(vertical = 6.dp).then(if (ri % 2 == 1) Modifier.background(cs.outlineVariant.copy(alpha = 0.18f)) else Modifier)) {
                    row.forEach { cell ->
                        Text(cell, color = cs.onSurfaceVariant, fontSize = 12.sp,
                            modifier = Modifier.widthIn(min = 80.dp, max = 220.dp).padding(horizontal = 8.dp))
                    }
                }
            }
            if (card.rows.size > shownRows.size) {
                Text(stringResource(R.string.qk_00947, (card.rows.size - shownRows.size).toString()), color = cs.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp))
            }
        }
    }
}"""

new = """// ───────────── 表格 ─────────────
/** 表格列宽的取值范围（dp）。下限防太窄，下限之上按内容长度分配。 */
private const val TABLE_COL_MIN = 72
private const val TABLE_COL_MAX = 260
private const val TABLE_COL_CHARS = 18   // 每 18 个字符算一档宽度

/**
 * 🔴 2026-10-08 修表格「截断 + 列对不齐」（用户截图实锤）。
 *
 * ## 旧实现的两处病根
 *
 * 1. **每格各自算宽度**：表头与每个单元格里都写 `widthIn(min=80, max=220)`，
 *    而 `widthIn` 对**不同内容**自然给出不同宽度 ——
 *    「熔断+假闭环」那格宽、「0/5」那格窄，于是**同一列在各行宽度都不一样**，
 *    表格纵向根本对不齐（截图里正是这个观感）。
 * 2. **文字被硬裁**：单元格没有换行与展开机制，长文本被容器边界切掉，
 *    用户看到「熔断+假闭」这种**半截词**，完全读不出结论。
 *
 * ## 现在
 *
 * - **先统一算一次列宽**（`tableColumnWidths`），表头与所有行共用同一组宽度 → 必然对齐；
 * - 单元格 `maxLines` 放开到 [TABLE_CELL_LINES]，长文本**换行完整显示**；
 *   仍超长的才折叠，整表给一个「展开全部」入口。
 */
@Composable
private fun TableCardView(card: QuroChatCard.TableCard) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember(card.rows) { mutableStateOf(false) }
    CardShell(card.title) {
        if (card.headers.isEmpty() && card.rows.isEmpty()) {
            Text(stringResource(R.string.qk_00946), color = cs.onSurfaceVariant, fontSize = 12.sp); return@CardShell
        }
        val colCount = maxOf(
            card.headers.size,
            card.rows.maxOfOrNull { it.size } ?: 0
        )
        // 🔴 统一列宽：表头与所有行共用，表格才能纵向对齐
        val widths = remember(card.headers, card.rows) {
            tableColumnWidths(card.headers, card.rows, colCount)
        }
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            if (card.headers.isNotEmpty()) {
                Row(Modifier.background(cs.primaryContainer).padding(vertical = 6.dp)) {
                    card.headers.forEachIndexed { ci, h ->
                        Text(
                            h, color = cs.onPrimaryContainer, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .width(if (ci < widths.size) widths[ci] else TABLE_COL_MIN.dp)
                                .padding(horizontal = 8.dp)
                        )
                    }
                }
            }
            // ★ ANR 防御：大表（工具/查询返回上百行）原 Column+forEach 主线程一次性布局 → 卡顿/ANR。
            // 上限渲染 80 行，超出显示脚注（完整数据仍在卡片 JSON 中）。
            val shownRows = if (expanded) card.rows else card.rows.take(80)
            shownRows.forEachIndexed { ri, row ->
                Row(Modifier.padding(vertical = 6.dp).then(if (ri % 2 == 1) Modifier.background(cs.outlineVariant.copy(alpha = 0.18f)) else Modifier)) {
                    (0 until colCount).forEach { ci ->
                        Text(
                            text = row.getOrElse(ci) { "" },
                            color = cs.onSurfaceVariant, fontSize = 12.sp,
                            modifier = Modifier
                                .width(if (ci < widths.size) widths[ci] else TABLE_COL_MIN.dp)
                                .padding(horizontal = 8.dp),
                            // 🔴 完整换行显示，别把「熔断+假闭环」裁成「熔断+假闭」
                            maxLines = if (expanded) Int.MAX_VALUE else TABLE_CELL_LINES,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (card.rows.size > shownRows.size) {
                Text(stringResource(R.string.qk_00947, (card.rows.size - shownRows.size).toString()), color = cs.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp))
            }
            // 有被折叠的单元格就给出路，否则用户永远看不到完整结论
            if (!expanded && card.rows.any { row -> row.any { it.length > TABLE_CELL_LINES * TABLE_COL_CHARS } }) {
                Text(
                    "展开完整表格",
                    Modifier.fillMaxWidth().clickable { expanded = true }.padding(top = 4.dp),
                    color = cs.primary, fontSize = 11.sp,
                )
            }
        }
    }
}

/** 单元格未展开时最多显示几行（旧实现没有换行概念，等价于被容器硬裁成 1 行）。 */
private const val TABLE_CELL_LINES = 3

/**
 * 统一计算每列宽度（dp）。
 *
 * 🔴 为什么必须整表算一次而不是每格各自 `widthIn`：
 * `widthIn(min,max)` 对不同内容给出不同宽度，于是同一列在各行宽度不同 → 纵向对不齐。
 * 这里按「该列最长内容的字符数」分档，每档取该列所有行里的最大值，全表共用。
 */
private fun tableColumnWidths(
    headers: List<String>,
    rows: List<List<String>>,
    colCount: Int,
): List<Dp> = (0 until colCount).map { ci ->
    var longest = headers.getOrElse(ci) { "" }.length
    for (r in rows) longest = maxOf(longest, r.getOrElse(ci) { "" }.length)
    // 中文按 2 个字符宽度估
    val visual = longest.coerceIn(TABLE_COL_MIN_CHARS, TABLE_COL_MAX_CHARS)
    ((visual / TABLE_COL_CHARS) * TABLE_COL_MIN + TABLE_COL_MIN)
        .coerceIn(TABLE_COL_MIN, TABLE_COL_MAX).dp
}

private const val TABLE_COL_MIN_CHARS = 8
private const val TABLE_COL_MAX_CHARS = 60"""

assert src.count(old) == 1, 'table anchor count=%d' % src.count(old)
src = src.replace(old, new, 1)

assert src != orig
t = p + '.tmp'
io.open(t, 'w', encoding='utf-8', newline='').write(src)
os.replace(t, p)
print('OK  %d -> %d' % (len(orig), len(src)))