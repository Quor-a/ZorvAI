# -*- coding: utf-8 -*-
import io, os

p = r"D:/Calw OS-project/QuroAI/app/src/main/java/com/ai/assistance/quro/ui/QuroChatCardsEx.kt"
s = io.open(p, encoding="utf-8").read()

# ── PollCardView：本地投票 + 联动 ──
old = '''/** 投票卡：选项条 + 百分比 + 总数，点击以 `poll:<i>` 回传。 */
@Composable
internal fun PollCardView(card: PollCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val total = if (card.total > 0) card.total else card.options.sumOf { it.count }
    Column(Modifier.fillMaxWidth()) {
        if (card.question.isNotBlank()) {
            Text(card.question, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            Spacer(Modifier.height(6.dp))
        }
        card.options.forEachIndexed { i, o ->
            val pct = if (total <= 0) 0f else o.count / total.toFloat()
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onCommand("${if (card.command.isBlank()) "poll" else card.command}:$i") }
                    .padding(vertical = 3.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(o.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
                    Text(
                        if (total > 0) "%.0f%%".format(pct * 100f) else o.count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(pct).fillMaxSize().clip(RoundedCornerShape(3.dp)).background(ExCardPalette.color(o.color)))
                }
            }
        }
        if (total > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "$total 人参与${if (card.multi) " · 多选" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}'''

new = '''/**
 * 投票卡：选项条 + 百分比 + 总数。
 *
 * 点选项是**本地投票**（当帧就高亮 + 重算百分比），同 id 的兄弟卡通过
 * [CardActionBus] 一起更新；只有当卡片自带 `command` 时才顺带回传给宿主
 * —— 否则 `poll:<i>` 这种串宿主从来没分支接，点了等于石沉大海。
 */
@Composable
internal fun PollCardView(card: PollCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var voted by remember(card.id) { mutableStateOf(-1) }
    DisposableEffect(card.id) {
        val off = CardActionBus.subscribe(card.id) { a ->
            parseCardTogglePayload(a.arg("data"))?.let { (i, _) -> voted = i }
            true
        }
        onDispose { off() }
    }
    val total = if (card.total > 0) card.total else card.options.sumOf { it.count }
    val votedTotal = if (voted >= 0) total + 1 else total
    Column(Modifier.fillMaxWidth()) {
        if (card.question.isNotBlank()) {
            Text(card.question, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            Spacer(Modifier.height(6.dp))
        }
        card.options.forEachIndexed { i, o ->
            val mine = voted == i
            val count = o.count + (if (mine) 1 else 0)
            val pct = if (votedTotal <= 0) 0f else count / votedTotal.toFloat()
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        voted = i
                        if (card.command.isNotBlank()) onCommand(card.command + ":$i")
                    }
                    .padding(vertical = 3.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (mine) "OK " else "") + o.label,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (mine) cs.primary else cs.onSurface,
                    )
                    Text(
                        if (votedTotal > 0) "%.0f%%".format(pct * 100f) else o.count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(pct).fillMaxSize().clip(RoundedCornerShape(3.dp)).background(ExCardPalette.color(o.color)))
                }
            }
        }
        if (total > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "$votedTotal 人参与${if (card.multi) " · 多选" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}'''
assert old in s, "poll"
s = s.replace(old, new, 1)

# ── PaginationCardView：本地翻页 + 联动 ──
old2 = '''/** 分页器：只把目标页码回传，翻页策略交给对话方。 */
@Composable
internal fun PaginationCardView(card: PaginationCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val total = card.total.coerceAtLeast(1)
    val shown = min(total, 7)
    val base = (card.page - 2).coerceIn(1, (total - shown + 1).coerceAtLeast(1))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until shown) {
            val p = base + i
            val sel = p == card.page'''
new2 = '''/**
 * 分页器：当前页是**本地状态**（翻页当场高亮），再把页码回传给对话方。
 *
 * 只发联动（同 id 兄弟卡一起翻）不回传，还是回传（AI 那侧也要翻页），
 * 取决于卡片有没有配 `command` —— 有就两边都做。
 */
@Composable
internal fun PaginationCardView(card: PaginationCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var page by remember(card.id) { mutableStateOf(card.page) }
    DisposableEffect(card.id) {
        val off = CardActionBus.subscribe(card.id) { a ->
            parseCardTogglePayload(a.arg("data"))?.let { (p, _) -> if (p > 0) page = p }
            true
        }
        onDispose { off() }
    }
    val total = card.total.coerceAtLeast(1)
    val shown = min(total, 7)
    val base = (page - 2).coerceIn(1, (total - shown + 1).coerceAtLeast(1))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until shown) {
            val p = base + i
            val sel = p == page'''
assert old2 in s, "pagination"
s = s.replace(old2, new2, 1)

old3 = '''                    .clickable { onCommand("${if (card.command.isBlank()) "ai:第" else card.command} $p 页") },'''
new3 = '''                    .clickable {
                        page = p
                        if (card.command.isBlank()) {
                            // 没有 command 就只做本地联动：让同 id 的另一张分页器一起翻
                            emitCardToggle(card.id, p, 1, onCommand)
                        } else {
                            onCommand(card.command + " " + p + " 页")
                        }
                    },'''
assert old3 in s, "pagination-click"
s = s.replace(old3, new3, 1)

io.open(p + ".tmp", "w", encoding="utf-8").write(s)
os.replace(p + ".tmp", p)
print("OK")
