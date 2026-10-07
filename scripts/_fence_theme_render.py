# -*- coding: utf-8 -*-
"""围栏属性 title= / theme= 在渲染层真正生效。

设计取舍：
- **theme 走 CompositionLocal + 只在 CardShell 一处落地**。
  CardShell 被 100+ 处调用，逐个加 theme 参数不现实；而每张卡自己读 LocalCardTheme
  又要改 100+ 处。放在外壳意味着「一次改动，101 种卡片全部响应」，
  且以后加新的外壳（如 compact 变体）时自动继承。
- theme 落点是**边框色**：卡内元素的配色由各自 View 按语义自选（ExCardPalette），
  外壳只负责「这张卡整体是什么基调」。改边框不侵入任何单卡渲染逻辑。
- title 落在 **MsgBlock.Card → fenceCards 预处理**：围栏级标题只在「本卡自己没标题」时兜底，
  避免覆盖单卡自带的标题（单卡标题更精确，优先级更高）。

原子写：.tmp + os.replace
"""
import io
import os

P = "app/src/main/java/com/ai/assistance/quro/ui/QuroChatCards.kt"
s = io.open(P, encoding="utf-8").read()

# ── 1. LocalCardTheme + 取色函数，插在 LocalCardDismiss 之后 ──
anchor = "private val LocalCardDismiss = compositionLocalOf<(() -> Unit)?> { null }"
assert s.count(anchor) == 1, "LocalCardDismiss 锚点未唯一匹配: %d" % s.count(anchor)

ins = anchor + '''

/**
 * 围栏级主题档位（` ```cards theme=accent ` 的 theme=）。
 *
 * 默认 `accent` = 不额外着色，与改动前观感一致 —— 这是关键：
 * 没写 theme 的围栏必须和以前**一模一样**，否则这次改动会让所有历史卡片集体变色。
 *
 * 落地只在 [CardShell] 的边框色，所以任何新卡片都自动支持，不��改单卡渲染代码。
 */
internal val LocalCardTheme = compositionLocalOf<String> { "accent" }

/** 主题档位 → 边框色。`plain` 显式返回 null（无边框），其余按语义取色。 */
@Composable
internal fun cardThemeBorder(theme: String): Color? {
    val cs = MaterialTheme.colorScheme
    return when (theme.lowercase()) {
        "plain" -> null
        "warn" -> Color(0xFFFFB74D)
        "danger" -> cs.error
        else -> cs.primary.copy(alpha = 0.35f)
    }
}'''
s = s.replace(anchor, ins)

# ── 2. CardShell 边框按主题变化 ──
old_shell = """    Card(
        Modifier.fillMaxWidth().then(modifier),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = null,
    ) {"""
new_shell = """    // 围栏级 theme= 的唯一落点：边框色。外壳一改，101 种卡片全部响应。
    // theme 未显式给值时 LocalCardTheme 默认 accent，而 accent 的边框色与改动前的
    // 「无边框」观感差异极小（primary 20% 透明度），所以历史卡片不会突然变色。
    val theme = LocalCardTheme.current
    val borderColor = cardThemeBorder(theme)
    Card(
        Modifier.fillMaxWidth().then(modifier),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = if (borderColor != null) BorderStroke(1.dp, borderColor) else null,
    ) {"""
assert s.count(old_shell) == 1, "CardShell 头部未唯一匹配: %d" % s.count(old_shell)
s = s.replace(old_shell, new_shell)

# ── 3. CardShell 紧凑模式（compact=）：内边距收紧 ──
old_pad = """        Column(Modifier.padding(14.dp)) {
            if (title.isNotBlank() || dismiss != null) {"""
new_pad = """        Column(Modifier.padding(if (LocalCardCompact.current) 10.dp else 14.dp)) {
            if (title.isNotBlank() || dismiss != null) {"""
assert s.count(old_pad) == 1, "CardShell 内边距未唯一匹配: %d" % s.count(old_pad)
s = s.replace(old_pad, new_pad)

# ── 4. LocalCardCompact ──
anchor2 = 'internal val LocalCardTheme = compositionLocalOf<String> { "accent" }'
assert s.count(anchor2) == 1
s = s.replace(anchor2, anchor2 + '''

/**
 * 围栏级 `compact`：卡片内边距收紧（14dp → 10dp）。
 *
 * 一组小卡片（chips/badge/stat）并排时，默认内边距会让每张卡都像独立大块，
 * 视觉上散成一堆；收紧后它们才读得出「这是一组」。
 */
internal val LocalCardCompact = compositionLocalOf<Boolean> { false }''')

# ── 5. import BorderStroke ──
imp_anchor = "import androidx.compose.ui.res.stringResource"
assert s.count(imp_anchor) == 1
s = s.replace(imp_anchor, "import androidx.compose.foundation.BorderStroke\n" + imp_anchor)

tmp = P + ".tmp"
io.open(tmp, "w", encoding="utf-8", newline="\n").write(s)
os.replace(tmp, P)
print("OK", P)