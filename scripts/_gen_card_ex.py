# -*- coding: utf-8 -*-
"""把 _newcards_snippet.kt.txt（原设计：塞进 sealed 体内）转成独立 Kotlin 文件。

 rationale：Kotlin 1.5+ 允许 sealed 子类分布在**同包同模块**的任意文件，
 所以 34 个新组件完全不需要塞进 QuroChatCard.kt —— 那样要让花括号配平脚本
 定位 sealed 闭合括号，一旦算错就整文件截断（2026-10-05 已踩过一次，尾部 936 行丢失）。
 单文件 = 可 git diff 复核 + 可独立编译报错定位，零风险。

 用法：python scripts/_gen_card_ex.py
"""
import io
import os
import sys

ROOT = r"D:\Calw OS-project\QuroAI"
CARDS = os.path.join(ROOT, "app", "src", "main", "java", "com", "ai", "assistance", "quro", "core", "cards")
SRC = os.path.join(CARDS, "_newcards_snippet.kt.txt")
DST = os.path.join(CARDS, "QuroChatCardEx.kt")

raw = io.open(SRC, encoding="utf-8", newline="").read()
body = raw.replace("\r\n", "\n").rstrip("\n")
lines = body.split("\n")

# 最后一行是 sealed 体的闭合 "}"，独立文件里不需要
if lines and lines[-1].strip() == "}":
    lines = lines[:-1]

# 去掉整体 4 空格缩进（原先嵌在 sealed interface 体内）
ded = []
for l in lines:
    if l.startswith("    "):
        ded.append(l[4:])
    else:
        ded.append(l)

out_lines = []
out_lines.append("package com.ai.assistance.quro.core.cards")
out_lines.append("")
out_lines.append("/**")
out_lines.append(" * 可视化组件 SDK v1400 —— 34 种增强组件（[QuroChatCard] 的 sealed 子类，独立成文件）。")
out_lines.append(" *")
out_lines.append(" * ## 为什么要独立文件")
out_lines.append(" *")
out_lines.append(" * Kotlin 1.5 起，sealed 子类允许分散在**同包同模块**的任意文件，")
out_lines.append(" * 所以新增组件**不需要**改 `QuroChatCard.kt`。改那一处需要靠脚本做花括号配平，")
out_lines.append(" * 一旦定位偏了就是整文件截断（2026-10-05 真踩过：尾部 parse/serialize/store 全丢）。")
out_lines.append(" * 独立文件可 diff 复核、编译报错能直接跳行，零风险。")
out_lines.append(" *")
out_lines.append(" * ## 🔴 存量类型签名一律不动")
out_lines.append(" *")
out_lines.append(" * 存量 47 种的数据类字段**一个字都没改**——它们已在真机跑着，")
out_lines.append(" * 改字段会连带 serializeCard / parseCard / 渲染分派 / 历史存档四处。")
out_lines.append(" * 本批全部是**新类型**，每种对应一个真实缺口（见下方注释）。")
out_lines.append(" */")
out_lines.append("")

body = "\n".join(out_lines + ded)
# 规范化：正文统一 LF 写盘； afterwards 由 gradle 侧 CRLF 转写不影响编译
tmp = DST + ".tmp"
io.open(tmp, "w", encoding="utf-8", newline="\n").write(body)
os.replace(tmp, DST)

txt = io.open(DST, encoding="utf-8", newline="").read()
n_ex = sum(1 for l in txt.split("\n") if l.startswith("data class ") or l.startswith("data class\t"))
print("生成:", DST)
print("行数:", len(txt.split("\n")))
print("顶层 data class 数:", sum(1 for l in txt.split("\n") if l.startswith("data class ") and "(" in l))
print("结尾 3 行:", [txt.split("\n")[-3], txt.split("\n")[-2], txt.split("\n")[-1]])
