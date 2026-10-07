# -*- coding: utf-8 -*-
"""把 cardjson 围栏写进 ui_widget / ui_card 的工具描述。

不做这一步的话，cardjson 就是个只有开发者自己知道的暗功能 —— 第四种围栏接了 6 处
（常量/正则/分派/解析/测试/文档）却没人用，等于白扩。
"""
import io, os, sys

ROOT = "app/src/main/java/com/ai/assistance/quro/core/tools/"

W = ROOT + "QuroToolsUiWidget.kt"
w = io.open(W, encoding="utf-8").read()

OLD = '"要写某类的完整字段与样例，先调 card_catalog（参数 category 或 types），别凭空猜字段名。"'
NEW = ('"要写某类的完整字段与样例，先调 card_catalog（参数 category 或 types），别凭空猜字段名。" +\n'
       '        "也可以直接在正文里下发卡片围栏：card（单个 JSON 对象）、cards（数组或组合卡）、' +
       'cardui（A2UI 邻接表）、cardjson（**一行一个 JSON**，流式友好，逐行独立容错；组合卡请用 cards）。"')

if OLD not in w:
    print("ABORT: ui_widget 锚点未命中")
    sys.exit(1)
w = w.replace(OLD, NEW, 1)

tmp = W + ".tmp"
with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
    f.write(w)
os.replace(tmp, W)
print("OK: ui_widget 已写明 4 种围栏")

C = ROOT + "QuroToolsUiCards.kt"
c = io.open(C, encoding="utf-8").read()
OLD_C = '"要完整字段与样例先调 card_catalog。"'
NEW_C = ('"要完整字段与样例先调 card_catalog。" +\n'
         '        "正文围栏同样支持 card / cards / cardui / cardjson（cardjson 为一行一个 JSON，流式友好）。"')
if OLD_C not in c:
    print("ABORT: ui_card 锚点未命中")
    sys.exit(1)
c = c.replace(OLD_C, NEW_C, 1)

tmp = C + ".tmp"
with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
    f.write(c)
os.replace(tmp, C)
print("OK: ui_card 已写明 4 种围栏")