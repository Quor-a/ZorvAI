# -*- coding: utf-8 -*-
"""把围栏属性写进两个工具的 description（模型可见面）。

不动 `card_catalog` 那条指引，只在「围栏说明」后面补属性语法 —— 模型的
行为完全取决于它知不知道有这回事，属性语法不写进提示词等于没做。
原子写：.tmp + os.replace
"""
import io
import os

OLD = ('"也可以直接在正文里下发卡片围栏：card（单个 JSON 对象）、cards（数组或组合卡）、'
       'cardui（A2UI 邻接表）、cardjson（**一行一个 JSON**，流式友好，逐行独立容错；组合卡请用 cards）。"')

NEW = ('"也可以直接在正文里下发卡片围栏：card（单个 JSON 对象）、cards（数组或组合卡）、'
       'cardui（A2UI 邻接表）、cardjson（**一行一个 JSON**，流式友好，逐行独立容错；组合卡请用 cards）。" +\n'
       '        "围栏头后可跟属性（空格分隔）：开关 compact（内边距收紧，一组小卡片必给）/ scroll（超高内部滚动）/ '
       'bordered / flat / dense；带值 title=组级标题（各卡自带标题时以卡为准）/ '
       'theme=主题档位，只认 accent(默认)/warn/danger/plain，其它值一律降级为默认。" +\n'
       '        "例：```cards title=Q3 复盘 theme=accent compact\\n[{\\"type\\":\\"stat\\",...}]\\n```"')

for P in ["app/src/main/java/com/ai/assistance/quro/core/tools/QuroToolsUiWidget.kt",
          "app/src/main/java/com/ai/assistance/quro/core/tools/QuroToolsUiCards.kt"]:
    if not os.path.exists(P):
        print("SKIP（不存在）", P)
        continue
    s = io.open(P, encoding="utf-8").read()
    n = s.count(OLD)
    if n != 1:
        print("SKIP %s 锚点匹配数=%d" % (os.path.basename(P), n))
        continue
    s = s.replace(OLD, NEW)
    tmp = P + ".tmp"
    io.open(tmp, "w", encoding="utf-8", newline="\n").write(s)
    os.replace(tmp, P)
    print("OK", os.path.basename(P))