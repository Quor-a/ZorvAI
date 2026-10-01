#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
N3 回归修复：native 分流后，思考段没进 StreamingThinkStripper。

回归怎么来的（我在 N3 里引入的）：
  N3 让 native 的 L4 分流器把思考段拆成**独立通道**上行（不带 <think> 标签），
  驱动层把它直接透传给 UI —— 这一步本身是对的。
  但 `stripper.rawText()` 只累积 onToken 的内容，于是它**丢了整个思考段**，
  而下游有两处依赖"完整原文"：
    ① `QuroLocalToolsCodec.parseDetailed(stripper.rawText())` ——
       「思考段内 <tool_call> 恢复」这条路径永久失效（恰恰是最需要兜住的场景）；
    ② `if (stripper.rawText().isEmpty())` —— 模型只吐思考、没吐正文时，
       被误判成"结构化生成未产出"，触发一次毫无必要的降级重试。

修法（最小侵入）：
  · onThinking 收到思考段时，把 <think>…</think> 标签**贴回去**再喂 stripper，
    让它重建"完整原文"视图（思考段归 thinking，visible 不受影响）；
  · 置 nativeThinkingSeen 标志，onToken 里原有的 `stripper.thinkingText()` 透传
    只在**没见到 native 分流**时才执行 —— 那是 JNI 回退路径（native 把思考
    包回标签走 onToken）唯一的上行通道，绝不能删，否则旧 .so 上思考会消失。

行级处理（不依赖整块文本匹配），缩进按原样推导。
"""

ROOT = r"D:\Calw OS-project\QuroAI"
DRIVER = ROOT + r"\app\src\full\java\com\ai\assistance\quro\core\network\QuroLocalEngineNative.kt"

# 标签用拼接构造，避免本文件自身的转义歧义
THINK_OPEN = "<" + "think" + ">"
THINK_CLOSE = "<" + "/think" + ">"

FLAG_DECL_DOC = [
    "// 🔧 N3 收尾：native 侧的 L4 分流器已把思考段拆成**独立通道**上行（不带标签），",
    "// 而终态的工具调用恢复、【是否产出】判定都读 stripper.rawText() —— 它只累积",
    "// onToken 的内容。若不再把思考段补回去，rawText() 就永久丢了思考段：",
    "//   ① 「思考段内 <tool_call>」恢复路径失效（那正是最需要兜住的场景）；",
    "//   ② 模型只吐思考不吐正文时被误判成「结构化生成未产出」，白白降级重试。",
    "// 这里把标签贴回去再喂 stripper 重建完整原文；本标志用于避免与 native 的",
    "// onThinking 增量双发（见 onToken 里的 thinkingText 兜底）。",
]

FEED_DOC = [
    "// 贴回 <think>…</think> 再喂剥离器，保持 rawText() 的「完整原文」语义",
    "// （visible 不受影响：剥离器会把标签内的内容归到思考段）。",
]


def main():
    raw = open(DRIVER, "rb").read()
    crlf = b"\r\n" in raw
    s = raw.decode("utf-8").replace("\r\n", "\n")
    lines = s.split("\n")

    out = []
    decl_count = 0
    onthink_count = 0
    guard_count = 0
    i = 0
    while i < len(lines):
        ln = lines[i]
        stripped = ln.strip()
        indent = ln[: len(ln) - len(ln.lstrip())]

        # ── ① stripper 创建处：紧跟其后声明标志 ──
        if stripped == "val stripper = StreamingThinkStripper()":
            out.append(ln)
            out.extend(indent + d for d in FLAG_DECL_DOC)
            out.append(indent + "var nativeThinkingSeen = false")
            decl_count += 1
            i += 1
            continue

        # ── ② onThinking 回调体开头：置标志 + 贴标签回喂 ──
        if stripped == "onThinking = { thinkChunk ->":
            inner = indent + "    "
            out.append(ln)
            out.append(inner + "nativeThinkingSeen = true")
            out.extend(inner + d for d in FEED_DOC)
            out.append(
                inner
                + 'runCatching { stripper.accept("' + THINK_OPEN + '" + thinkChunk + "'
                + THINK_CLOSE + '") }'
            )
            onthink_count += 1
            i += 1
            continue

        # ── ③ onToken 里的兜底透传：加守卫 ──
        if stripped == "onThinking?.let { cb -> runCatching { cb(stripper.thinkingText()) } }":
            inner = indent + "    "
            out.append(indent + "// native 已分流时不再重复透传（避免与 onThinking 增量双发）；")
            out.append(indent + "// native 未分流（旧 .so / JNI 回退把思考包回标签走 onToken）时，")
            out.append(indent + "// 这里是唯一的上行通道，不能省。")
            out.append(indent + "if (!nativeThinkingSeen) {")
            out.append(inner + stripped)
            out.append(indent + "}")
            guard_count += 1
            i += 1
            continue

        out.append(ln)
        i += 1

    print("stripper 创建点（应 2，MNN + llama）      :", decl_count)
    print("onThinking 回调（应 4，两条路径共 4 处）   :", onthink_count)
    print("thinkingText 兜底（应 4，两条路径共 4 处） :", guard_count)

    if (decl_count, onthink_count, guard_count) != (2, 4, 4):
        print("\n数量与预期不符 → 整体放弃写入。")
        return 1

    s2 = "\n".join(out)
    out_bytes = s2.replace("\n", "\r\n") if crlf else s2
    open(DRIVER, "wb").write(out_bytes.encode("utf-8"))
    print("\nPATCHED %s (%s, %d bytes)"
          % (DRIVER.split("\\")[-1], "CRLF" if crlf else "LF", len(out_bytes.encode("utf-8"))))
    return 0


if __name__ == "__main__":
    import sys

    sys.exit(main())
