# -*- coding: utf-8 -*-
"""N3 收尾 · 修 MNNLlmSession.chat 的位置参数错位。

背景：给 generateStream / generateStreamStructured 插入 onThinking 参数时
（刻意插在 onToken 之前以保住 trailing lambda 兼容），MNNLlmSession.chat 里
那处**位置参数**调用 `generateStream(history, maxTokens, onToken)` 就错位了 ——
第 3 个位置现在是 onThinking，编译器直接报
    No value passed for parameter 'onToken'
这正是"用位置参数调用带默认值的参数列表"的典型脆点，顺手改成命名参数。

跑法：python scripts/fix_n3_mnn_chat.py
"""
import io
import os
import sys

FAIL = []
p = r"llm/mnn/src/main/java/com/ai/assistance/mnn/MNNLlmSession.kt"

if not os.path.exists(p):
    print("文件不存在: " + p)
    sys.exit(1)

s = io.open(p, encoding="utf-8").read()
crlf = "\r\n" in s
if crlf:
    s = s.replace("\r\n", "\n")

edits = [
    (
        "chat 签名补 onThinking",
        """    fun chat(
        userContent: String,
        maxTokens: Int = -1,
        onToken: (String) -> Boolean
    ): Boolean {""",
        """    fun chat(
        userContent: String,
        maxTokens: Int = -1,
        /** 思考段增量回调。插在 [onToken] 之前，理由同 [generateStream]。 */
        onThinking: ((String) -> Boolean)? = null,
        onToken: (String) -> Boolean
    ): Boolean {""",
    ),
    (
        "chat 内部调用改命名参数",
        "        return generateStream(history, maxTokens, onToken)",
        "        return generateStream(history, maxTokens, onThinking = onThinking, onToken = onToken)",
    ),
]

for tag, old, new in edits:
    n = s.count(old)
    if n != 1:
        FAIL.append("[%s] 锚点命中 %d 次（期望 1）" % (tag, n))
        continue
    s = s.replace(old, new)

out = s.replace("\n", "\r\n") if crlf else s
io.open(p, "w", encoding="utf-8", newline="").write(out)

if FAIL:
    print("!! 失败项:")
    for f in FAIL:
        print("   -", f)
    sys.exit(1)
print("已修 MNNLlmSession.chat（CRLF=%s）" % crlf)
