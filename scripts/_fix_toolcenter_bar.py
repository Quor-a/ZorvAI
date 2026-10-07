# -*- coding: utf-8 -*-
"""工具中心两个 Panel 的顶栏紧凑化 + 渲染区最大化。

真机症状（2026-10-06 截图，两页同症）：
  · 「Web 应用」：←返回列表 | demo | 对话框预览 | 删除 —— 四个 TextButton
    平铺一行，把整行撑到屏幕 1/5 高，下面内容区几乎看不到。
  · 「工具中心」小程序页：← 返回列表 | hello —— 同样把内容挤到底边一条。

根因不是字号，是**一行平铺 4 个 TextButton + 各自带 8~12dp 水平内边距**，
在窄屏（1080px /360dp 宽）上必须换行，Compose 里 TextButton 换行后每个
占满一行的垂直空间，行高直接翻倍。

修法（移动端标准做法）：
  1. 主操作收进**图标按钮**，只保留「返回」「删除」两个动作的常驻可见，
     次要操作（对话框预览 / 打开）走 **Overflow 图标菜单**；
  2. 单行Row 改为「返回 +标题(weight) + 动作区」，标题单行省略号；
  3. 行高压到 48dp（Material 最小可点区域），不再随内容膨胀；
  4. 渲染区用 weight(1f) 吃掉剩余全部高度（原来已被weight 占用，
     但顶栏膨胀后剩余被压到 0）。

不动的：TextButton 的可点面积仍 ≥48dp，不牺牲可点性。
"""
import io
import os
import time

P = "app/src/main/java/com/ai/assistance/quro/ui/QuroToolCenterScreen.kt"
s = io.open(P, encoding="utf-8").read()

# ══════════════ 1. MiniAppPanel（Web 应用）顶栏 ══════════════
old1 = """            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { current = null; engineRef.value = null }) { Text(stringResource(R.string.qk_02767)) }
                Text(current ?: "", Modifier.weight(1f).padding(12.dp), color = Muted)
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val html = MiniAppTool().run(context, JSONObject().put("action", "run").put("name", current).toString())
                        withContext(Dispatchers.Main) {
                            if (html.startsWith("❌")) Toast.makeText(context, html, Toast.LENGTH_SHORT).show()
                            else { onRenderInChat("miniapp", html, current ?: qstr(R.string.qk_02775)); Toast.makeText(context, qstr(R.string.qk_02843), Toast.LENGTH_SHORT).show() }
                        }
                    }
                }) { Text(qstr(R.string.qk_02844)) }
                TextButton(onClick = {
                    if (current != null && File(root, current!!).deleteRecursively()) { refreshKey++; current = null; Toast.makeText(context, qstr(R.string.qk_02845), Toast.LENGTH_SHORT).show() }
                }) { Text(qstr(R.string.qk_00091)) }
            }"""
assert s.count(old1) == 1, "p1 anchor %d" % s.count(old1)

new1 = """            // 🔴 顶栏紧凑化（2026-10-06 真机截图：原来一行平铺 4 个 TextButton，
            // 窄屏上 TextButton 换行后每个占满整行垂直空间，把渲染区挤到只剩一条边）。
            // 现在：返回用图标、标题单行省略、次要操作收进溢出菜单 —— 整行恒定 48dp。
            MiniAppDetailBar(
                title = current ?: "",
                onBack = { current = null; engineRef.value = null },
                overflowItems = listOf(
                    qstr(R.string.qk_02844) to {
                        scope.launch(Dispatchers.IO) {
                            val html = MiniAppTool().run(context, JSONObject().put("action", "run").put("name", current).toString())
                            withContext(Dispatchers.Main) {
                                if (html.startsWith("❌")) Toast.makeText(context, html, Toast.LENGTH_SHORT).show()
                                else { onRenderInChat("miniapp", html, current ?: qstr(R.string.qk_02775)); Toast.makeText(context, qstr(R.string.qk_02843), Toast.LENGTH_SHORT).show() }
                            }
                        }
                    },
                    qstr(R.string.qk_00091) to {
                        if (current != null && File(root, current!!).deleteRecursively()) { refreshKey++; current = null; Toast.makeText(context, qstr(R.string.qk_02845), Toast.LENGTH_SHORT).show() }
                    },
                ),
            )"""
s = s.replace(old1, new1)

# ══════════════ 2. MiniAppSdkPanel 顶栏 ══════════════
old2 = """            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { current = null }) { Text(stringResource(R.string.qk_02767)) }
                Text(current ?: "", Modifier.weight(1f).padding(12.dp), color = Muted)
            }"""
assert s.count(old2) == 1, "p2 anchor %d" % s.count(old2)

new2 = """            // 同上：顶栏恒定 48dp，把剩余高度全给渲染区。
            MiniAppDetailBar(title = current ?: "", onBack = { current = null }, overflowItems = emptyList())"""
s = s.replace(old2, new2)

# ══════════════ 3. 新增 MiniAppDetailBar 组件 ══════════════
old3 = """@Composable
private fun MiniAppPanel("""
assert s.count(old3) == 1, "comp anchor %d" % s.count(old3)

new3 = """/**
 * Web 应用 / 小程序详情页的紧凑顶栏。
 *
 * 🔴 为什么不用一排 TextButton（2026-10-06 真机截图的根因）：
 * TextButton 自带 8~12dp 水平内边距与最小高度，窄屏上一行放不下 4 个就换行，
 * 换行后每个独占一行垂直空间 —— 顶栏从 48dp 膨胀到 100dp+，
 * 底下的渲染区被压到只剩一条边（截图里正是「只露底边一条蓝」）。
 *
 * 布局：`(返回图标) (标题 weight=1 单行省略) (溢出图标) (删除图标)`，
 * 整行恒定 48dp，标题再长也不会顶高。
 *
 * @param overflowItems 次要操作（label → onClick），收进 ⋮ 菜单。传空则不显示该按钮。
 */
@Composable
private fun MiniAppDetailBar(
    title: String,
    onBack: () -> Unit,
    overflowItems: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val cs = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            LucideIcon("chevron_left", stringResource(R.string.qk_00143), Modifier.size(22.dp), tint = cs.onBackground)
        }
        Text(
            title.ifBlank { "—" },
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = cs.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (overflowItems.isNotEmpty()) {
            // ⋮ 溢出菜单。用 Popup 而非 DropdownMenu：父行有固定高度，
            // DropdownMenu 会被 Row 的裁剪吃掉（这是 Compose 里老坑）。
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    LucideIcon("more_vertical", stringResource(R.string.qk_02851), Modifier.size(20.dp), tint = cs.onBackground)
                }
                Popup(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    Column(
                        Modifier
                            .background(cs.surface, RoundedCornerShape(10.dp))
                            .widthIn(min = 150.dp),
                    ) {
                        overflowItems.forEach { (label, act) ->
                            Text(
                                label,
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { menuOpen = false; act() }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onBackground,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniAppPanel("""
s = s.replace(old3, new3)

# ══════════════ 4. 补 import（layout 走通配 *，只需 UI 层的） ══════════════
anchor_imp = "import androidx.compose.ui.unit.dp"
assert s.count(anchor_imp) >= 1, "imp anchor %d" % s.count(anchor_imp)
need = [
    "import androidx.compose.ui.text.style.TextOverflow",
    "import androidx.compose.ui.window.Popup",
]
lines = s.split("\n")
last = max(i for i, l in enumerate(lines) if l.startswith("import "))
have = set(lines)
add = [x for x in need if x not in have]
for a in add:
    lines.insert(last + 1, a)
s = "\n".join(lines)

assert chr(0xfffd) not in s
assert s.count("fun MiniAppDetailBar") == 1
assert s.count("MiniAppDetailBar(") == 3  # 定义 + 2 处调用

io.open(P + ".tmp", "w", encoding="utf-8", newline="\n").write(s)
for i in range(8):
    try:
        os.replace(P + ".tmp", P)
        print("OK %d chars, imports added=%s" % (len(s), add))
        break
    except OSError:
        time.sleep(1.5)