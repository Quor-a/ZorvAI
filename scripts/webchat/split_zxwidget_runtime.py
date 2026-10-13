#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 ZxWebCardDialog.kt 拆成「运行时装配」一个文件，并删掉整框弹窗那半截。

为什么拆：用户 2026-10-13 明确「zxwidget 不是卡片方式，是直接渲染在对话框的」。
于是 `ZxWebCardDialog` 这个整框弹窗（连同 `ZxWebCardView`）整体下线，
只留下真正有用的运行时装配（buildHtml / summarize / assets），
放进语义正确的 `ZxWidgetRuntime.kt`。原子写。
"""
import io
import os
import sys

ROOT = r"D:/Calw OS-project/QuroAI"
SRC = os.path.join(ROOT, "app/src/main/java/com/ai/assistance/quro/ui/chat/ZxWebCardDialog.kt")
DST = os.path.join(ROOT, "app/src/main/java/com/ai/assistance/quro/ui/chat/ZxWidgetRuntime.kt")

HEADER = '''package com.ai.assistance.quro.ui.chat

import android.content.Context
import com.ai.assistance.quro.util.QuroDiag
import org.json.JSONObject

/**
 * zxwidget 运行时（动态交互界面）的宿主侧装配。
 *
 * 术语先说清（这三个词在本仓经常被混着用）：
 *   · **zxwidget** = 动态交互界面。一套独立的组件编译器（477 个 zx 组件），
 *     既不是 ZorvAI 也不是 Web UI，只是**被这两个对话框一起支持**。
 *     运行时在 `assets/zx/zxwidget.js`，单卡片页壳在 `zx_card_runtime.html`。
 *   · **ZorvAI 原生对话框** = 聊天主界面。zxwidget **直接长在它的消息流里**。
 *   · **Web UI 对话框** = ZorvAI 对话框的 Web 复制品。同样长在消息流里。
 *   两边同一个来源（zxwidget），区别只在「谁把它画出来」。
 *
 * 🔴 交付形态（用户 2026-10-13 拍板）：**不是卡片、不是弹窗，直接渲染在对话框**。
 *   历史上一度是「整框弹窗装一张卡」——那正是被否掉的形态，已下线；
 *   本文件旧名 `ZxWebCardDialog.kt`，现在只剩装配，没有 Dialog，也不再叫卡片。
 *
 * 触发路径：模型调 `zx_web_card` 工具 → 工具调用落在 assistant 消息上
 *   → 两个对话框各自从**消息上的工具调用**读出 component/dsl，就地内联渲染
 *   （Web UI 走 web-chat 的 toolCall 渲染，原生走 [ZxWidgetInline]）。
 *   用户在界面上操作 → 交互作为**该工具的结果**回传，本轮对话继续。
 *
 * 为什么走 WebView 而不是原生 Compose：
 *   zx 组件族有 477 个，原生逐个实现要写几十万行；而它们的 DSL 是同构 JSON。
 *   Web 侧一个运行时 + 一层形状推断就能全覆盖，改样式也不用发版。
 *   宿主只保留三件事：把 DSL 送进去、把交互接出来、决定交互之后干什么。
 *
 * 注意：本页（`zx_card_runtime.html`）走 `loadDataWithBaseURL` + 构建期内联，
 *   `file://` 下加载不到外链脚本，所以它内联了一份编译器拷贝。
 *   那份拷贝与新模块 `zx/zxwidget.js` **目前并不完全同源**——见
 *   `scripts/zx/zxwidget_parity_check.js` 的 KNOWN-DRIFT 清单。
 */
'''


def atomic_write(path, text):
    tmp = path + ".tmp"
    with io.open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write(text)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def main():
    if not os.path.exists(SRC):
        print("源文件不存在：%s" % SRC)
        return 1
    with io.open(SRC, "r", encoding="utf-8", newline="") as f:
        lines = f.read().split("\n")

    # 找到 `object ZxWebCard {` 那一行（1-based 79 / 0-based 78）
    try:
        start = next(i for i, l in enumerate(lines) if l.startswith("object ZxWebCard {"))
    except StopIteration:
        print("找不到 `object ZxWebCard {`，锚点不唯一，整体放弃")
        return 1

    # 找到 object 的收尾 `}`（顶格的那一个）
    end = None
    for i in range(start + 1, len(lines)):
        if lines[i] == "}":
            end = i
            break
    if end is None:
        print("找不到 object 的收尾 `}`，整体放弃")
        return 1

    body = "\n".join(lines[start:end + 1])
    if "fun summarize" not in body or "fun buildHtml" not in body:
        print("object 体里缺 buildHtml/summarize，内容与预期不符，整体放弃")
        return 1

    atomic_write(DST, HEADER + body + "\n")
    print("已写出 %s（%d 行）" % (os.path.relpath(DST, ROOT).replace("\\", "/"), body.count("\n") + 1))

    os.remove(SRC)
    print("已删除旧文件 %s" % os.path.relpath(SRC, ROOT).replace("\\", "/"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
