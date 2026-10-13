# -*- coding: utf-8 -*-
"""把 zxwidget 组件样式从旧聊天页里抽出来，成为独立模块的一份。

分节边界（旧运行时的 CSS）：
  ── 顶栏 ──         49
  ── 消息流 ──       67
  ── zxwidget ──    146   ← 抽这个
  ── 输入区 ──      269
样式是 zxwidget 自己的，不该长在某个对话框的页面里。

⚠️ 一次性脚本，**已执行、不可重跑**：源文件 `app/src/main/assets/zx_chat_runtime.html`
   已于 2026-10-13 删除。改样式请直接改 `assets/web-chat/zxwidget.css`。
   单卡片运行时 `zx_card_runtime.html` 里还有一份未收敛的样式拷贝。
"""
import io
import os

ROOT = r"D:/Calw OS-project/QuroAI"
SRC = os.path.join(ROOT, "app/src/main/assets/zx_chat_runtime.html")
OUT = os.path.join(ROOT, "app/src/main/assets/web-chat/zxwidget.css")

START = "/* ── zxwidget 动态交互界面"
END = "/* ── 输入区 ──"

HEADER = """/* ═══════════════════════════════════════════════════════════════════════
   zxwidget 组件样式 —— 动态交互界面
   ═══════════════════════════════════════════════════════════════════════

   与 assets/zx/zxwidget.js 配套：那个负责把组件契约编译成 DOM，
   这个负责让它长得像样。两者都不属于任何一个对话框。
   ═══════════════════════════════════════════════════════════════════════ */

"""


def main():
    src = io.open(SRC, encoding="utf-8").read()
    i = src.find(START)
    j = src.find(END, i)
    if i < 0 or j < 0:
        raise SystemExit("!! 找不到样式分节边界 i=%s j=%s" % (i, j))
    block = src[i:j].rstrip() + "\n"

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    tmp = OUT + ".tmp"
    with io.open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write(HEADER + block)
    os.replace(tmp, OUT)
    print("已写出", OUT, os.path.getsize(OUT), "字节")
    print("选择器数:", block.count("{"))


if __name__ == "__main__":
    main()
