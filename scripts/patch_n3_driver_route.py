# -*- coding: utf-8 -*-
"""N3 收尾 · 驱动层把 native 的思考段接到既有的 onThinking 链路。

QuroLocalEngineNative 里有 4 个流式生成调用点（MNN 3 个 + llama 1 个），
原本只有一个 `onToken` lambda，思考内容靠 lambda 内部的
`stripper.thinkingText()` 自己算出来。

现在引擎侧 L4 分流器已经把思考段单独上行（且**不带标签**），所以每个调用点
都补一个 `onThinking` lambda 直接透传 —— 顺序由引擎保证，不再靠文本猜测。

原来的 `stripper.thinkingText()` 透传**保留不动**：它是 native 未分流时的兜底通道
（两者互斥：引擎分流了 stripper 就收不到思考内容，没分流则 native 通道是空的）。

跑法：python scripts/patch_n3_driver_route.py
"""
import io
import os
import sys

FAIL = []


def patch(path, edits):
    if not os.path.exists(path):
        FAIL.append("文件不存在: " + path)
        return
    s = io.open(path, encoding="utf-8").read()
    crlf = "\r\n" in s
    if crlf:
        s = s.replace("\r\n", "\n")
    for tag, old, new in edits:
        n = s.count(old)
        if n != 1:
            FAIL.append("[%s] 锚点命中 %d 次（期望 1）" % (tag, n))
            continue
        s = s.replace(old, new)
    out = s.replace("\n", "\r\n") if crlf else s
    io.open(path, "w", encoding="utf-8", newline="").write(out)
    print("已打补丁: %s (CRLF=%s)" % (path, crlf))


DRIVER = r"app/src/full/java/com/ai/assistance/quro/core/network/QuroLocalEngineNative.kt"

TAIL_DOC = """                        // 🧠 引擎侧 L4 分流器上行的思考段：**不带标签**，直接透传。
                        // 不再经 Kotlin 的 StreamingThinkStripper —— 那层现在只作为
                        // "native 未分流时的兜底"（见 onToken 里的 thinkingText 透传）。
                        onThinking = { thinkChunk ->"""

edits = [
    # ── 1) MNN 结构化路径 ───────────────────────────────────────────────
    (
        "MNN structured 首行",
        "                val structuredOk = session.generateStreamStructured(messagesJson, toolSpecsJson, effMaxTokens) { token ->",
        "                val structuredOk = session.generateStreamStructured(\n"
        "                    messagesJson, toolSpecsJson, effMaxTokens,\n"
        "                    onToken = { token ->",
    ),
    (
        "MNN structured 尾行",
        "                    if (isCanceled()) return@generateStreamStructured false\n"
        "                    true\n"
        "                }",
        "                    if (isCanceled()) return@generateStreamStructured false\n"
        "                    true\n"
        "                    },\n"
        + TAIL_DOC + "\n"
        "                        onThinking?.let { cb -> runCatching { cb(thinkChunk) } }\n"
        "                        true\n"
        "                    },\n"
        "                )",
    ),
    # ── 2) MNN 降级兜底路径 ─────────────────────────────────────────────
    (
        "MNN fallback 首行",
        "                    val fallbackOk = session.generateStream(history, effMaxTokens) { token ->",
        "                    val fallbackOk = session.generateStream(\n"
        "                        history, effMaxTokens,\n"
        "                        onToken = { token ->",
    ),
    (
        "MNN fallback 尾行",
        "                        if (isCanceled()) return@generateStream false\n"
        "                        true\n"
        "                    }",
        "                        if (isCanceled()) return@generateStream false\n"
        "                        true\n"
        "                        },\n"
        "                        onThinking = { thinkChunk ->\n"
        "                            onThinking?.let { cb -> runCatching { cb(thinkChunk) } }\n"
        "                            true\n"
        "                        },\n"
        "                    )",
    ),
    # ── 3) MNN 非结构化路径 ─────────────────────────────────────────────
    (
        "MNN plain 首行",
        "                session.generateStream(history, effMaxTokens) { token ->",
        "                session.generateStream(\n"
        "                    history, effMaxTokens,\n"
        "                    onToken = { token ->",
    ),
    (
        "MNN plain 尾行",
        "                    if (isCanceled()) return@generateStream false\n"
        "                    true\n"
        "                }\n"
        "            }",
        "                    if (isCanceled()) return@generateStream false\n"
        "                    true\n"
        "                    },\n"
        "                    onThinking = { thinkChunk ->\n"
        "                        onThinking?.let { cb -> runCatching { cb(thinkChunk) } }\n"
        "                        true\n"
        "                    },\n"
        "                )\n"
        "            }",
    ),
    # ── 4) llama 路径（已有命名参数，只补 onThinking）────────────────────
    (
        "llama 尾行",
        "                    return@generateStream false\n"
        "                }\n"
        "                true\n"
        "            }\n"
        "            )",
        "                    return@generateStream false\n"
        "                }\n"
        "                true\n"
        "            },\n"
        "                onThinking = { thinkChunk ->\n"
        "                    // 🧠 引擎侧 L4 分流器上行的思考段：**不带标签**，直接透传。\n"
        "                    onThinking?.let { cb -> runCatching { cb(thinkChunk) } }\n"
        "                    true\n"
        "                }\n"
        "            )",
    ),
]

patch(DRIVER, edits)

if FAIL:
    print("\n!! 失败项:")
    for f in FAIL:
        print("   -", f)
    sys.exit(1)
print("\n驱动层补丁成功")
