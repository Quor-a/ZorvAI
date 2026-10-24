import io, os

p = 'app/src/main/java/com/ai/assistance/quro/ui/QuroChatCardsEx.kt'
src = io.open(p, encoding='utf-8').read()
orig = src

old = """/** 键值对列表：左键右值，右值超长省略号截断。 */
@Composable
internal fun KeyValueCardView(card: KeyValueCard, onCommand: (String) -> Unit) {
    CardShell(card.title) {
        card.rows.forEach { r ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !r.command.isNullOrBlank()) { r.command?.let { onCommand(it) } }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    r.k,
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    r.v,
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.End,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}"""

new = """/** 键值对列表：左键右值。\n *\n * 🔴 2026-10-08 修「值区换行溢出被压掉」（用户截图实锤）。\n *\n * 旧实现是 `weight(1f)` + `maxLines = 3` + `TextOverflow.Ellipsis` 的**双截断**：\n * - 键和值各占一半宽度，值本身又长（截图里「集群暂不建议用于…」那种整句结论），\n *   一半宽度下3 行根本放不下 → 直接省略号砍掉；\n * - 用户看到的是「结论被压成半句」，完全不知道集群到底说了什么。\n *\n * 现在：\n * - **键定宽不抢值**：键用 `widthIn(max = 96.dp)`，剩下的全给值（键都是「版本」「大小」这类短词）；\n * - **默认不限行数**，让长值完整换行显示（这才是用户要的信息）；\n * - 真正过长的（> 8 行）才折叠，可点「展开」看全文 —— 折叠阈值给得比旧值宽得多。\n */\n@Composable\ninternal fun KeyValueCardView(card: KeyValueCard, onCommand: (String) -> Unit) {\n    var expanded by remember(card.rows) { mutableStateOf(false) }\n    CardShell(card.title) {\n        card.rows.forEach { r ->\n            val isLong = r.v.length > LONG_VALUE_CHARS\n            Row(\n                Modifier\n                    .fillMaxWidth()\n                    .clickable(enabled = !r.command.isNullOrBlank()) { r.command?.let { onCommand(it) } }\n                    .padding(vertical = 5.dp),\n                verticalAlignment = Alignment.Top,\n            ) {\n                // 键：定宽上限，短词就短着，长词也不会把值挤没\n                Text(\n                    r.k,\n                    Modifier.widthIn(max = 96.dp),\n                    color = MaterialTheme.colorScheme.onSurfaceVariant,\n                    style = MaterialTheme.typography.bodySmall,\n                )\n                Spacer(Modifier.width(10.dp))\n                Text(\n                    r.v,\n                    Modifier.weight(1f),\n                    color = MaterialTheme.colorScheme.onSurface,\n                    style = MaterialTheme.typography.bodySmall,\n                    fontWeight = FontWeight.Medium,\n                    // 🔴 左对齐而不是 End：值被折行时右对齐会读着别扭\n                    textAlign = TextAlign.Start,\n                    maxLines = if (isLong && !expanded) FOLD_LINES else Int.MAX_VALUE,\n                    overflow = TextOverflow.Ellipsis,\n                )\n            }\n        }\n        // 有超长值且被折叠过→ 给一个展开入口\n        if (card.rows.any { it.v.length > LONG_VALUE_CHARS }) {\n            Text(\n                if (expanded) "收起" else "展开完整内容",\n                Modifier\n                    .fillMaxWidth()\n                    .clickable { expanded = !expanded }\n                    .padding(top = 6.dp),\n                color = MaterialTheme.colorScheme.primary,\n                style = MaterialTheme.typography.labelSmall,\n            )\n        }\n    }\n}\n\n/** 值长度超过它才认为「需要折叠」。比旧版的 3 行宽松得多。 */\nprivate const val LONG_VALUE_CHARS = 80\n\n/** 折叠时最多显示几行（比旧版 3 行高，且只有真超长才触发）。 */\nprivate const val FOLD_LINES = 6"""

assert src.count(old) == 1, 'keyvalue anchor count=%d' % src.count(old)
src = src.replace(old, new, 1)

assert src != orig
t = p + '.tmp'
io.open(t, 'w', encoding='utf-8', newline='').write(src)
os.replace(t, p)
print('OK  %d -> %d' % (len(orig), len(src)))