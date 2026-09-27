import io

def patch(path, anchor, block):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        s = f.read()
    if anchor not in s:
        print("ANCHOR NOT FOUND in", path)
        return False
    le = "\r\n" if "\r\n" in s[:4000] else "\n"
    # block lines are separated by \n in source; normalize to file LE and indent 2 spaces
    lines = block.strip("\n").split("\n")
    indented = le.join("  " + ln if ln.strip() else ln for ln in lines)
    if not indented.endswith(le):
        indented += le
    s2 = s.replace(anchor, anchor + le + le + indented, 1)
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(s2)
    print("PATCHED", path)
    return True

NODE_SPEC_ANCHOR = "在此之前它已能正常显示。"
NODE_SPEC_BLOCK = """【必读：让组件真的能用（写组件前先看这条）】
很多「写了但点了没反应 / 不显示」都因下面没做对——照做即可 100% 可用：
1) 往返铁律：用户点控件 → 你收到【事件名】+ key:value 用户消息 → 你必须在同一条回复里重新输出一整块新 ```quro-ui（带更新状态）。只回文字面板会像「死」的。
2) 围栏语言必须是 quro-ui（或 zorv-ui / quro_ui）；JSON 合法、无注释、键与字符串双引号；action 必须是对象 {"type":"callback",...}，写字符串不触发。
3) text_input/select/checkbox/switch/slider 必须给唯一 id（collect_from 才能收到）；button 用 action + 唯一 event（如 ttt_move / ttt_reset）。
4) game_board：cells 长度 = rows×cols；cellAction 写 callback 且 clickable=true 才有反应；点格自动回发 row/col/index/value。
5) genui：content 必须是合法 GenUI DSL（与全屏 Agent 同写法）；子树内交互用 GenUI 自己的事件机制，别用 quro-ui 的 button action 跨进子树。
6) 其它节点 / 任意新 type 随便写，非法字段回落默认、绝不整体崩。"""

GAME_ANCHOR = "调 game_ui 拿完整组件与模板，再开玩。"
GAME_BLOCK = """\n  ★ 必读：游戏「点了没反应」的 4 个原因（写游戏前先看）
  - 原因1：围栏不是 ```quro-ui → 改成 quro-ui 才渲染。
  - 原因2：你收到【事件名】后只回了文字、没重新输出 ```quro-ui 面板 → 必须每轮重建整块 UI（更新 cells / 比分 / 提示）。
  - 原因3：game_board 的 cellAction 没写 callback，或 clickable 不是 true → 点格没反应。
  - 原因4：收集输入时 text_input 没给 id → collect_from 收不到。
  记住：你就是状态机。UI 不记忆上一局，每次回发你都重新生成最新面板，游戏就「活」了。
  完整教程见 ui_dsl_spec 的【必读：让组件真的能用】段。"""

ok1 = patch(
    "app/src/main/java/com/ai/assistance/quro/core/tools/QuroDynamicUiTool.kt",
    NODE_SPEC_ANCHOR, NODE_SPEC_BLOCK)
ok2 = patch(
    "app/src/main/java/com/ai/assistance/quro/core/tools/GameUiTool.kt",
    GAME_ANCHOR, GAME_BLOCK)
print("NODE_SPEC patched:", ok1, "GAME patched:", ok2)
