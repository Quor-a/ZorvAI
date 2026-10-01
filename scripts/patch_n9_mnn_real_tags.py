#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
N9：MNN 侧对齐 —— 把探测到的**真实**思考标签注入原生分流器。

为什么这是"另一个大缺失"：
  llama 侧能从 llama.cpp 的模板 detector 自动拿到真实标签（N8 已接），
  而 MNN 侧压根没有这条路：ThinkSplitter 在两栈共用，默认标记集只覆盖
  `<think>` / `<thinking>` / 全角三种形态。用 `[THINK]`、`<|channel|>…` 的
  MNN 模型，思考段在原生分流器里**根本不被识别** —— 整段推理当正文上屏；
  而 MnnModelCapabilities 那边 emitsThinkBlock 又被判成 true（如果模板里
  同时存在 <think> 字面），于是症状是"开关开了、剥离却没生效"，极难定位。

改动：
  ① MnnEngine::setThinkMarkers(...)（对齐 llama 的 applyDetectedThinkingTags）
  ② JNI nativeSetThinkMarkers + 注册表（22 → 23）
  ③ Kotlin：MNNLlmNative 声明 + MNNLlmSession 包装
  ④ MnnModelCapabilities 提取真实标签对（并保持既有口径不退化）
  ⑤ 驱动层在开思考之前把标签交给原生
"""
import sys

ROOT = r"D:\Calw OS-project\QuroAI"

ENGINE_H = ROOT + r"\llm\mnn\src\main\cpp\mnn_engine.h"
ENGINE_CPP = ROOT + r"\llm\mnn\src\main\cpp\mnn_engine.cpp"
JNI_CPP = ROOT + r"\llm\mnn\src\main\cpp\mnn_jni.cpp"
NATIVE_KT = ROOT + r"\llm\mnn\src\main\java\com\ai\assistance\mnn\MNNLlmNative.kt"
SESSION_KT = ROOT + r"\llm\mnn\src\main\java\com\ai\assistance\mnn\MNNLlmSession.kt"
CAPS_KT = ROOT + r"\llm\mnn\src\main\java\com\ai\assistance\mnn\MnnModelCapabilities.kt"
DRIVER = ROOT + r"\app\src\full\java\com\ai\assistance\quro\core\network\QuroLocalEngineNative.kt"

# ───────────────────────── ① mnn_engine.h ─────────────────────────
H_OLD = """    /// 注入配置（等价于 MNN 的 set_config）。可多次调用，必须在 load() 之前。
    bool setConfig(const std::string& configJson, std::string* err);
"""
H_NEW = """    /// 注入配置（等价于 MNN 的 set_config）。可多次调用，必须在 load() 之前。
    bool setConfig(const std::string& configJson, std::string* err);

    /// 注入模型**真实**使用的思考段标签（开 / 闭两组）。
    ///
    /// 与 llama 侧的 applyDetectedThinkingTags **同一口径**：那边标签由 llama.cpp 的
    /// 模板 detector 自动给出，这边由 Kotlin 的 MnnModelCapabilities 从
    /// `jinja.chat_template` 文本探测后传下来。原生把两组标签并进共用的
    /// ThinkSplitter（幂等），于是 `[THINK]` / `<|channel|>analysis<|message|>`
    /// 这类**默认标记集认不出**的形态也能被正确分流。
    ///
    /// 空数组 = 什么都不做（探测不到标签时的常态，行为与本改动之前一致）。
    bool setThinkMarkers(const std::vector<std::string>& openTags,
                         const std::vector<std::string>& closeTags,
                         std::string* err);
"""

# ───────────────────────── ② mnn_engine.cpp ─────────────────────────
CPP_ANCHOR = """void MnnEngine::setWavformCallback(WavformCallback cb) {
"""
CPP_NEW = """bool MnnEngine::setThinkMarkers(const std::vector<std::string>& openTags,
                                const std::vector<std::string>& closeTags,
                                std::string* err) {
    if (!impl_) {
        if (err) *err = "引擎内部状态缺失（Impl 为空）。";
        return false;
    }

    // 与 llama 侧 applyDetectedThinkingTags 完全相同的取舍：
    // 用**定长标记**（terminator 为空），因为传下来的是完整标签串，而
    // `<|channel|>analysis<|message|>` 有 28 字节、远超 maxTagLength(=16)
    // 的保护上限 —— 定长匹配不走那条保护，因此不受限。
    std::vector<ThinkSplitter::Marker> extra;
    extra.reserve(openTags.size() + closeTags.size());
    for (const std::string& tag : openTags) {
        if (tag.empty()) continue;
        ThinkSplitter::Marker m;
        m.prefix = tag;
        m.terminator.clear();
        m.open = true;
        extra.push_back(std::move(m));
    }
    for (const std::string& tag : closeTags) {
        if (tag.empty()) continue;
        ThinkSplitter::Marker m;
        m.prefix = tag;
        m.terminator.clear();
        m.open = false;
        extra.push_back(std::move(m));
    }

    if (extra.empty()) {
        // 探测不到标签不是错误：保留默认标记集，行为与改动前一致。
        return true;
    }

    // addMarkers 是幂等的：驱动层每个模型加载时调一次，重复调用**不会复位** ——
    // 否则会把已经切了一半的思考段打断（只在多轮对话里复现的那种错）。
    impl_->session.thinkSplitter.addMarkers(extra);
    LOGI("setThinkMarkers: open=%d close=%d (共 %d 条注入)",
         static_cast<int>(openTags.size()), static_cast<int>(closeTags.size()),
         static_cast<int>(extra.size()));
    return true;
}

void MnnEngine::setWavformCallback(WavformCallback cb) {
"""

# ───────────────────────── ③ mnn_jni.cpp ─────────────────────────
JNI_HELPER_ANCHOR = """extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeCancel(JNIEnv* env, jclass clazz, jlong llmPtr) {
"""
JNI_HELPER_NEW = """namespace {

/// 把 String[] 收成 vector。
/// llama 侧有同名实现，但那是**另一个 .so**（符号隔离 + 静态库各自链接），
/// 不共享；为它开一个公共头不划算，就地写一份。
std::vector<std::string> collectStrings(JNIEnv* env, jobjectArray jarray) {
    std::vector<std::string> out;
    if (jarray == nullptr) {
        return out;
    }
    const jsize count = env->GetArrayLength(jarray);
    out.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto item = reinterpret_cast<jstring>(env->GetObjectArrayElement(jarray, i));
        if (item == nullptr) {
            continue;
        }
        out.push_back(jstringToString(env, item));
        env->DeleteLocalRef(item);
    }
    return out;
}

}  // namespace

// ── 思考段真实标签（对齐 llama 侧的 applyDetectedThinkingTags）────────────
// 默认标记集只认 <think> / <thinking> / 全角；模板用 [THINK]、<|channel|>… 的模型
// 交给原生后**根本切不开**，整段推理会被当正文推上屏。
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeSetThinkMarkers(JNIEnv* env, jclass clazz,
                                                              jlong llmPtr,
                                                              jobjectArray openTags,
                                                              jobjectArray closeTags) {
    (void) clazz;
    REQUIRE_ENGINE_VAL(llmPtr, JNI_FALSE);
    std::string err;
    const bool ok = engine->setThinkMarkers(collectStrings(env, openTags),
                                            collectStrings(env, closeTags), &err);
    if (!ok) LOGE("nativeSetThinkMarkers: %s", err.c_str());
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeCancel(JNIEnv* env, jclass clazz, jlong llmPtr) {
"""

JNI_TABLE_OLD = """    {"nativeGetLastError", "(J)Ljava/lang/String;",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeGetLastError)},
};"""
JNI_TABLE_NEW = """    {"nativeGetLastError", "(J)Ljava/lang/String;",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeGetLastError)},
    // 思考段真实标签注入：MNN 侧没有 llama 那种"模板 detector 自动给标签"的路径，
    // 由 Kotlin 的 MnnModelCapabilities 探测后传下来（见 nativeSetThinkMarkers）。
    {"nativeSetThinkMarkers", "(J[Ljava/lang/String;[Ljava/lang/String;)Z",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeSetThinkMarkers)},
};"""

JNI_COUNT_OLD = "constexpr int kMNNLlmNativeCount = 22;"
JNI_COUNT_NEW = "constexpr int kMNNLlmNativeCount = 23;"

# ───────────────────────── ④ MNNLlmNative.kt ─────────────────────────
NATIVE_OLD = """    external fun nativeSetConfig(llmPtr: Long, configJson: String): Boolean
"""
NATIVE_NEW = """    external fun nativeSetConfig(llmPtr: Long, configJson: String): Boolean

    /**
     * 注入模型**真实**使用的思考段标签（开 / 闭两组）。
     *
     * 原生分流器（ThinkSplitter）默认只认 `<think>` / `<thinking>` / 全角三种形态，
     * 而真实模型可能用 `[THINK]`、`<|channel|>analysis<|message|>` 之类 ——
     * 不注入的话，那些模型的思考段在原生**根本不被识别**，整段推理当正文上屏；
     * 而 Kotlin 侧的能力探测已经判为"会产出思考段"，于是症状变成
     * "开关开了、剥离却没生效"，极难定位。
     *
     * 幂等：重复调用不会打断进行中的段。
     * @return 注入是否被接受（空数组也算成功 —— 那是"探测不到标签"的常态）
     */
    external fun nativeSetThinkMarkers(
        llmPtr: Long,
        openTags: Array<String>,
        closeTags: Array<String>
    ): Boolean
"""

# ───────────────────────── ⑤ MNNLlmSession.kt ─────────────────────────
SESSION_OLD = """    fun setThinkingMode(enabled: Boolean): Boolean {"""
SESSION_NEW = """    /**
     * 注入模型**真实**使用的思考段标签（与 llama 侧的注入同一口径）。
     *
     * 必须在 [setThinkingMode] **之前**调用：标签决定原生能不能切开思考段，
     * 开关决定要不要产出思考段 —— 顺序反了会出现"开了却切不开"的窗口。
     */
    fun setThinkMarkers(openTags: List<String>, closeTags: List<String>): Boolean {
        val ptr = synchronized(lock) {
            if (llmPtr == 0L) return false
            llmPtr
        }
        return MNNLlmNative.nativeSetThinkMarkers(
            ptr,
            openTags.toTypedArray(),
            closeTags.toTypedArray()
        )
    }

    fun setThinkingMode(enabled: Boolean): Boolean {"""

# ───────────────────────── ⑥ MnnModelCapabilities.kt ─────────────────────────
CAPS_PAIRS_OLD = """    /** 模型能力探测结果。 */
    data class Capabilities("""
CAPS_PAIRS_NEW = """    /**
     * 「默认标记集**认不出**、必须注入给原生分流器」的思考段标签对（开 → 闭）。
     *
     * 为什么需要这张表：原生共用的 ThinkSplitter 默认只覆盖 `<think>` / `<thinking>` /
     * 全角三种形态（见 quro/think_splitter.h 的 defaultMarkers）。真实模型还会用
     * `[THINK]`、GPT-OSS 系的 `<|channel|>analysis<|message|>`、另一种 channel 写法
     * `<|channel>thought` —— 不注入的话，那些模型的思考段在原生分流器里**根本不被
     * 识别**，整段推理会当正文推上屏。
     *
     * 口径与 llama 侧一致：那边由 llama.cpp 的模板 detector 自动给出
     * （common_chat_params::thinking_start_tag / thinking_end_tags），给出的就是这几种。
     *
     * 表里只列默认集不认的形态 —— 已认的重复注入虽然幂等，但配置越干净越好。
     */
    private val THINK_TAG_PAIRS: List<Pair<String, String>> = listOf(
        "[THINK]" to "[/THINK]",
        "<|channel|>analysis<|message|>" to "<|end|>",
        "<|channel>thought" to "<channel|>",
    )

    /** 模型能力探测结果。 */
    data class Capabilities("""

CAPS_FIELDS_OLD = """        /** 模板是否会产出 `<think>` 段（无论是否可开关）。 */
        val emitsThinkBlock: Boolean = false,
        /** 探测过程的补充说明（失败原因 / 命中特征），用于写诊断日志。 */
        val note: String = "",
    ) {"""
CAPS_FIELDS_NEW = """        /**
         * 模板是否会产出思考段（无论是否可开关）。
         *
         * 语义在 N9 之后**更完整**了：除了默认标记集能认的 `<think>`，
         * 现在也涵盖 `[THINK]` / `<|channel|>…` 这些默认集认不出的形态
         * （它们曾让"开关开了、剥离却没生效"变成一桩悬案）。
         */
        val emitsThinkBlock: Boolean = false,
        /**
         * 需要注入给原生分流器的思考段标签（开 / 闭，一一对应）。
         * 空表示模板只用默认形态，或探测不到 —— 两者都不需要注入。
         */
        val thinkOpenTags: List<String> = emptyList(),
        val thinkCloseTags: List<String> = emptyList(),
        /** 探测过程的补充说明（失败原因 / 命中特征），用于写诊断日志。 */
        val note: String = "",
    ) {"""

CAPS_DETECT_OLD = """        val supportsThinkingToggle = lower.contains("enable_thinking")
        val emitsThinkBlock = lower.contains("<think>") || lower.contains("</think>")

        val hits = buildList {
            if (supportsTools) add("tools")
            if (supportsThinkingToggle) add("enable_thinking")
            if (emitsThinkBlock) add("<think>")
        }"""
CAPS_DETECT_NEW = """        val supportsThinkingToggle = lower.contains("enable_thinking")
        // 默认标记集能认的形态沿**原口径**判定 —— 这一条直接挂钩 v1.0.50
        // 「回复混进 Thinking Process 独白」那次修正的依据，不动它。
        val emitsDefaultThinkBlock = lower.contains("<think>") || lower.contains("</think>")
        // 默认集**认不出**、需要往下注入给原生分流器的那些标签对。
        // 要求开闭**成对**出现，避免把正文里偶发的单个标签当成思考段特征。
        val extraPairs = THINK_TAG_PAIRS.filter { (open, close) ->
            lower.contains(open.lowercase()) && lower.contains(close.lowercase())
        }
        val emitsThinkBlock = emitsDefaultThinkBlock || extraPairs.isNotEmpty()
        val thinkOpenTags = extraPairs.map { it.first }
        val thinkCloseTags = extraPairs.map { it.second }

        val hits = buildList {
            if (supportsTools) add("tools")
            if (supportsThinkingToggle) add("enable_thinking")
            if (emitsThinkBlock) add("<think>")
            if (extraPairs.isNotEmpty()) add("thinkTags=" + thinkOpenTags.joinToString(","))
        }"""

CAPS_RETURN_OLD = """            emitsThinkBlock = emitsThinkBlock,
            note = note,
        )"""
CAPS_RETURN_NEW = """            emitsThinkBlock = emitsThinkBlock,
            thinkOpenTags = thinkOpenTags,
            thinkCloseTags = thinkCloseTags,
            note = note,
        )"""

# ───────────────────────── ⑦ 驱动层 ─────────────────────────
DRIVER_OLD = """            val thinkingApplicable = caps.emitsThinkBlock
            val applied = runCatching { session.setThinkingMode(thinkingApplicable) }.getOrDefault(false)"""
DRIVER_NEW = """            // 🧠 先把模板**真实**使用的思考标签交给原生分流器，再决定要不要开思考。
            // 顺序不能反：标签决定原生**能不能切开**思考段，开关决定要不要**产出**
            // 思考段。默认标记集只认 <think>/<thinking>/全角，而模板可能用
            // [THINK]、<|channel|>analysis<|message|> —— 不注入的话，那些模型的
            // 思考段在原生根本不被识别，整段推理会被当正文推上屏，而下面的
            // emitsThinkBlock 已经判为 true，症状就成了"开关开了、剥离却没生效"。
            // （llama 侧同样的事由 llama.cpp 的模板 detector 自动完成。）
            runCatching { session.setThinkMarkers(caps.thinkOpenTags, caps.thinkCloseTags) }

            val thinkingApplicable = caps.emitsThinkBlock
            val applied = runCatching { session.setThinkingMode(thinkingApplicable) }.getOrDefault(false)"""

PATCHES = [
    (ENGINE_H, [(H_OLD, H_NEW)]),
    (ENGINE_CPP, [(CPP_ANCHOR, CPP_NEW)]),
    (JNI_CPP, [
        (JNI_HELPER_ANCHOR, JNI_HELPER_NEW),
        (JNI_TABLE_OLD, JNI_TABLE_NEW),
        (JNI_COUNT_OLD, JNI_COUNT_NEW),
    ]),
    (NATIVE_KT, [(NATIVE_OLD, NATIVE_NEW)]),
    (SESSION_KT, [(SESSION_OLD, SESSION_NEW)]),
    (CAPS_KT, [
        (CAPS_PAIRS_OLD, CAPS_PAIRS_NEW),
        (CAPS_FIELDS_OLD, CAPS_FIELDS_NEW),
        (CAPS_DETECT_OLD, CAPS_DETECT_NEW),
        (CAPS_RETURN_OLD, CAPS_RETURN_NEW),
    ]),
    (DRIVER, [(DRIVER_OLD, DRIVER_NEW)]),
]


def main():
    staged, failed = [], False
    for path, pairs in PATCHES:
        try:
            raw = open(path, "rb").read()
        except FileNotFoundError:
            print("MISSING FILE:", path)
            failed = True
            continue
        crlf = b"\r\n" in raw
        s = raw.decode("utf-8").replace("\r\n", "\n")
        for old, new in pairs:
            n = s.count(old)
            if n != 1:
                print("FAIL %s: hits=%d (need 1)\n     -> %s"
                      % (path.split("\\")[-1], n, old.split("\n")[0][:70]))
                failed = True
                continue
            s = s.replace(old, new, 1)
        staged.append((path, s, crlf))
    if failed:
        print("\n整体放弃写入（防半装载）。")
        return 1
    for path, s, crlf in staged:
        out = s.replace("\n", "\r\n") if crlf else s
        open(path, "wb").write(out.encode("utf-8"))
        print("PATCHED %-28s (%s, %d bytes)"
              % (path.split("\\")[-1], "CRLF" if crlf else "LF", len(out.encode("utf-8"))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
