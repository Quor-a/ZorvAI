# -*- coding: utf-8 -*-
"""N3 · Kotlin 侧接通「思考段」独立通道。

配套 patch_n3_thinking_route.py（JNI 层）。JNI 已经会把 isThinking 的 chunk
优先送到 `onThinking(String):Boolean`，本脚本把 Kotlin 这 4 层接上：

    GenerationCallback 接口（llama / mnn 各一个）
      → LlamaSession / MNNLlmSession 的会话方法
        → QuroLocalEngineNative 驱动层
          → 已有的 onThinking 链路（一直到 UI 思考区）

两个关键设计取舍：

  1. `onThinking` 声明为**抽象方法**（不给默认实现）。
     若给一个"转调 onToken"的默认实现，未升级的实现类会把**不带标签的思考原文**
     当正文吐出去（实时上屏），而且编译期完全看不出来 —— 这正是本次要防的回归。
     抽象方法则让**所有实现类编译报错**，编译器帮我们查全，不会漏。
     （另一个隐患：Kotlin 的接口默认实现在旧 `-Xjvm-default=disable` 下只在
      `Xxx$DefaultImpls` 里有方法体，接口本身仍是 abstract。此时 JNI 的
      GetMethodID 找得到方法、但未 override 的实现类调用会抛 AbstractMethodError。
      用抽象方法从根上避开这个坑。）

  2. 会话层新参数插在 `onToken` **之前**（而不是最后）。
     Kotlin 的 trailing lambda 绑定的是**最后一个**参数，若把 onThinking 放最后，
     现有的 `generateStream(x, y) { token -> ... }` 会静默改成绑定 onThinking。
     插在前面 = 老调用点一行都不用改，零破坏。

跑法：python scripts/patch_n3_kotlin_route.py
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
            FAIL.append("%s [%s] 锚点命中 %d 次（期望 1）" % (path, tag, n))
            continue
        s = s.replace(old, new)
    out = s.replace("\n", "\r\n") if crlf else s
    io.open(path, "w", encoding="utf-8", newline="").write(out)
    print("已打补丁: %s (CRLF=%s)" % (path, crlf))


IFACE_DOC = '''        /**
         * 思考段增量（模型 `<think>…</think>` 内部内容）。
         *
         * 引擎侧 L4 分流器已经把思考标签吃掉、并把内容拆成两个通道，
         * 所以这里拿到的是**不带标签的纯净思考文本**，直接送 UI 的「思考区」即可。
         *
         * 声明为**抽象方法**而不是给默认实现，是刻意的：
         * 若默认实现转调 [onToken]，未升级的实现类会把不带标签的思考原文当正文
         * 吐出去（实时上屏），而且编译期毫无提示。
         * 抽象方法能让**所有实现类编译报错**，强制每个实现点显式处理这个通道。
         *
         * 原生侧按 `onThinking(Ljava/lang/String;)Z` 用 GetMethodID 探测，
         * 探测不到时会退化成"把思考内容包回 `<think>` 标签走 onToken"，
         * 因此这里**必须**与原生签名严格一致。
         *
         * @return true 继续生成，false 停止生成
         */
        fun onThinking(token: String): Boolean'''

# ═══════════════════════════════════════════════════════════════════════════
# K1 · llama: LlamaNative.GenerationCallback
# ═══════════════════════════════════════════════════════════════════════════
LLAMA_NATIVE = r"llm/llama/src/main/java/com/ai/assistance/llama/LlamaNative.kt"

llama_iface_old = '''    interface GenerationCallback {
        fun onToken(token: String): Boolean'''
llama_iface_new = '''    interface GenerationCallback {
        /** 正文段增量。@return true 继续生成，false 停止生成 */
        fun onToken(token: String): Boolean

''' + IFACE_DOC

patch(LLAMA_NATIVE, [("iface", llama_iface_old, llama_iface_new)])

# ═══════════════════════════════════════════════════════════════════════════
# K2 · mnn: MNNLlmNative.GenerationCallback
# ═══════════════════════════════════════════════════════════════════════════
MNN_NATIVE = r"llm/mnn/src/main/java/com/ai/assistance/mnn/MNNLlmNative.kt"

mnn_iface_old = '''        fun onToken(token: String): Boolean
    }'''
mnn_iface_new = '''        fun onToken(token: String): Boolean

''' + IFACE_DOC + '''
    }'''

patch(MNN_NATIVE, [("iface", mnn_iface_old, mnn_iface_new)])

# ═══════════════════════════════════════════════════════════════════════════
# K3 · MNNLlmSession
# ═══════════════════════════════════════════════════════════════════════════
MNN_SESSION = r"llm/mnn/src/main/java/com/ai/assistance/mnn/MNNLlmSession.kt"

mnn_gs_old = '''    fun generateStream(
        history: List<Pair<String, String>>,
        maxTokens: Int = -1,
        onToken: (String) -> Boolean
    ): Boolean {
        val callback = guardedCallback("token callback", onToken)'''
mnn_gs_new = '''    fun generateStream(
        history: List<Pair<String, String>>,
        maxTokens: Int = -1,
        /**
         * 思考段增量回调（引擎侧 L4 分流器已把思考从正文里分出来，**不带标签**）。
         *
         * 插在 [onToken] **之前**是刻意的：Kotlin 的 trailing lambda 绑定最后一个参数，
         * 若放最后，现有的 `generateStream(h, m) { token -> ... }` 写法会静默改成
         * 绑定 onThinking。插在前面 = 老调用点一行都不用改。
         */
        onThinking: ((String) -> Boolean)? = null,
        onToken: (String) -> Boolean
    ): Boolean {
        val callback = guardedCallback("token callback", onToken, onThinking)'''

mnn_gss_old = '''    fun generateStreamStructured(
        messagesJson: String,
        toolsJson: String? = null,
        maxTokens: Int = -1,
        onToken: (String) -> Boolean
    ): Boolean {
        lastNativeError = null
        val callback = guardedCallback("structured token callback", onToken)'''
mnn_gss_new = '''    fun generateStreamStructured(
        messagesJson: String,
        toolsJson: String? = null,
        maxTokens: Int = -1,
        /** 思考段增量回调。插在 [onToken] 之前，理由同 [generateStream]。 */
        onThinking: ((String) -> Boolean)? = null,
        onToken: (String) -> Boolean
    ): Boolean {
        lastNativeError = null
        val callback = guardedCallback("structured token callback", onToken, onThinking)'''

mnn_gc_old = '''    private fun guardedCallback(
        label: String,
        onToken: (String) -> Boolean
    ): MNNLlmNative.GenerationCallback {'''
mnn_gc_new = '''    private fun guardedCallback(
        label: String,
        onToken: (String) -> Boolean,
        /**
         * 思考段回调。参数名用 onThinkingChunk 而不是 onThinking：
         * 下面匿名对象里要 override `onThinking(...)`，同名会让人读不清
         * "这一行出现的 onThinking 到底是参数还是方法"。
         */
        onThinkingChunk: ((String) -> Boolean)? = null,
    ): MNNLlmNative.GenerationCallback {'''

mnn_gc_tail_old = '''                    Log.e(TAG, "Error in $label", e)
                    false
                }
            }
        }
    }'''
mnn_gc_tail_new = '''                    Log.e(TAG, "Error in $label", e)
                    false
                }
            }

            /**
             * 思考段上行。**刻意不过 RepetitionGuard**：
             * 退化检测针对的是正文复读，而思考过程本身就可能反复推敲同一句话，
             * 拿它做退化判定会误杀正常推理。
             */
            override fun onThinking(token: String): Boolean {
                return try {
                    onThinkingChunk?.invoke(token) ?: true
                } catch (e: Exception) {
                    Log.e(TAG, "Error in thinking callback", e)
                    // 思考段回调出错不终止生成：思考只是展示用，
                    // 正文才是用户真正要的答案，不能因它丢掉整段回复。
                    true
                }
            }
        }
    }'''

patch(MNN_SESSION, [
    ("generateStream", mnn_gs_old, mnn_gs_new),
    ("generateStreamStructured", mnn_gss_old, mnn_gss_new),
    ("guardedCallback sig", mnn_gc_old, mnn_gc_new),
    ("guardedCallback tail", mnn_gc_tail_old, mnn_gc_tail_new),
])

# ═══════════════════════════════════════════════════════════════════════════
# K4 · LlamaSession
# ═══════════════════════════════════════════════════════════════════════════
LLAMA_SESSION = r"llm/llama/src/main/java/com/ai/assistance/llama/LlamaSession.kt"

ls_sig_old = '''    fun generateStream(
        prompt: String,
        maxTokens: Int,
        onProgress: ((String, Int, Int) -> Unit)? = null,
        onToken: (String) -> Boolean,
    ): Boolean {'''
ls_sig_new = '''    fun generateStream(
        prompt: String,
        maxTokens: Int,
        onProgress: ((String, Int, Int) -> Unit)? = null,
        /**
         * 思考段增量回调（引擎侧 L4 分流器已把思考从正文里分出来，**不带标签**）。
         *
         * 插在 [onToken] **之前**是刻意的：Kotlin 的 trailing lambda 绑定最后一个参数，
         * 若放最后，现有调用点的 trailing lambda 会静默改成绑定 onThinking。
         * 插在前面 = 老调用点一行都不用改。
         */
        onThinking: ((String) -> Boolean)? = null,
        onToken: (String) -> Boolean,
    ): Boolean {'''

ls_cb_old = '''            object : LlamaNative.GenerationCallback {
                override fun onToken(token: String): Boolean = onToken(token)
                override fun onProgress(stage: String, current: Int, total: Int) {
                    onProgress?.invoke(stage, current, total)
                }
            }'''
ls_cb_new = '''            object : LlamaNative.GenerationCallback {
                override fun onToken(token: String): Boolean = onToken(token)

                /**
                 * 思考段上行。调用方没传 onThinking 时**直接放行**：
                 * 思考内容只是不被展示，绝不能因此中断生成。
                 */
                override fun onThinking(token: String): Boolean =
                    onThinking?.invoke(token) ?: true

                override fun onProgress(stage: String, current: Int, total: Int) {
                    onProgress?.invoke(stage, current, total)
                }
            }'''

patch(LLAMA_SESSION, [
    ("generateStream sig", ls_sig_old, ls_sig_new),
    ("callback impl", ls_cb_old, ls_cb_new),
])

if FAIL:
    print("\n!! 失败项:")
    for f in FAIL:
        print("   -", f)
    sys.exit(1)
print("\n全部补丁成功")
