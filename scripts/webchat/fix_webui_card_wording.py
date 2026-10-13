# -*- coding: utf-8 -*-
"""把代码与提示词里残留的「Web UI 卡片」措辞理清楚。

用户 2026-10-13 的定义（终稿）：
  · Web UI        = 第三个对话框，ZorvAI 对话框的 Web 复制品
  · zxwidget      = 动态交互界面（477 个 zx 组件），不是对话框，只被两个对话框支持
  · zx_web_card   = 弹**单卡片**对话框，与「第三个对话框」是两回事

旧措辞把这三者焊成了一团：工具描述里写「Web UI 卡片（ZorvAI 第三个对话框）」，
模型据此以为存在一个叫「Web UI 卡片」的东西。提示词里的旧概念不删，
模型会照旧按旧概念幻觉——所以这一遍必须改，且要改准。

按「长串优先」逐条替换，最后报告仍残留的地方。原子写。
"""
import io
import os

ROOT = r"D:/Calw OS-project/QuroAI/app/src/main/java"

# 长串在前，避免被短串先吃掉
RULES = [
    ("Web UI 卡片（ZorvAI 第三个对话框的 AI 侧入口）", "zxwidget 卡片工具（动态交互界面的 AI 侧入口）"),
    ("Web UI 卡片（ZorvAI 第三个对话框）：", "zxwidget 动态交互界面："),
    ("Web UI 卡片（ZorvAI 第三个对话框）", "zxwidget 动态交互界面"),
    ("Web UI 卡片（第三个对话框，zx 交互式卡片族）", "zxwidget 卡片（动态交互界面，477 个 zx 组件）"),
    ("第三个对话框：Web UI 卡片（zx 交互式卡片族）", "zxwidget 单卡片对话框（zx 交互式卡片族）"),
    ("第三个对话框：Web UI 卡片。", "zxwidget 单卡片对话框。"),
    ("第三个对话框（Web UI 卡片）", "zxwidget 开关"),
    ("Web UI 卡片（第三个对话框）：component", "zxwidget 卡片：component"),
    ("Web UI 卡片（WebView 承载的 zx 交互式卡片）", "zxwidget 单卡片（WebView 承载的动态交互界面）"),
    ("Web UI 卡片 / zxwidget 动态交互界面用", "zxwidget 动态交互界面用"),
    ("【Web UI 卡片交互】", "【zxwidget 交互】"),
    ("Web UI 卡片对话框本体", "zxwidget 单卡片对话框本体"),
    ("Web UI 卡片对话框", "zxwidget 单卡片对话框"),
    ("Web UI 卡片队列", "zxwidget 卡片队列"),
    ("Web UI 卡片工具", "zxwidget 卡片工具"),
    ("弹出 ZorvAI 第三个对话框（Web UI 卡片）：用 WebView 渲染 zx 交互式卡片族",
     "弹出 zxwidget 动态交互界面（WebView 承载的 zx 组件族）"),
    ("**可交互卡片（Web UI）**", "**可交互卡片（zxwidget）**"),
    ("（第三个对话框，WebView 承载 zx 卡片族 477 个组件）", "（WebView 承载 zx 组件族 477 个）"),
    ("「可交互卡片 / Web UI 卡片」", "「可交互卡片 / zxwidget 动态界面」"),
    ("需要「用户在卡片上当场操作并回传」用 Web UI 卡片。", "需要「用户在卡片上当场操作并回传」用 zxwidget 动态交互界面。"),
    ("弹 Web UI 卡片", "弹 zxwidget 卡片"),
    ("弹出 Web UI 卡片", "弹出 zxwidget 卡片"),
    ("Web UI 卡片", "zxwidget 卡片"),          # 兜底
]


def main():
    touched, total = 0, 0
    leftover = []
    for dirpath, _, filenames in os.walk(ROOT):
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            p = os.path.join(dirpath, fn)
            s = io.open(p, encoding="utf-8").read()
            if "Web UI 卡片" not in s:
                continue
            before = s
            n_here = 0
            for a, b in RULES:
                if a in s:
                    n_here += s.count(a)
                    s = s.replace(a, b)
            if s != before:
                tmp = p + ".tmp"
                with io.open(tmp, "w", encoding="utf-8", newline="") as f:
                    f.write(s)
                os.replace(tmp, p)
                touched += 1
                total += n_here
                rel = os.path.relpath(p, ROOT).replace("\\", "/")
                if "Web UI 卡片" in s:
                    leftover.append(rel)
                print("改", n_here, "处:", rel)
    print("---")
    print("涉及文件", touched, "个，替换", total, "处")
    if leftover:
        print("!! 仍有残留:", leftover)
    else:
        print("无残留")


if __name__ == "__main__":
    main()
