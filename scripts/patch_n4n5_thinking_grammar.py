#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
N4 + N5 补丁：
  N4  llama 侧思考开关 setThinkingMode（与 MNN 的语义对齐）
  N5  GBNF 入口接出 —— 修一个真 bug：nativeSetToolCallGrammar 有实现、
      但既没进 kLlamaNativeMethods 注册表，Kotlin 侧也没有声明。
只做文本替换，不改任何既有逻辑。每个锚点必须命中且唯一，否则整体不写。
"""
import sys

ROOT = r"D:\Calw OS-project\QuroAI"

# ─────────────────────────── 1. Session 加两个字段 ───────────────────────────
IMPL_H = ROOT + r"\llm\llama\src\main\cpp\llama_engine_impl.h"
IMPL_H_OLD = """    common_chat_parser_params toolCallParserParams;
    bool hasToolCallParser = false;
"""
IMPL_H_NEW = """    common_chat_parser_params toolCallParserParams;
    bool hasToolCallParser = false;

    /// ── 思考段开关（对齐 MNN 的 MNNLlmSession.setThinkingMode）──
    /// 三态：-1 = 上层未设置（沿用 llama.cpp 默认 true）、0 = 关、1 = 开。
    /// 为什么不是 bool：MNN 侧不显式调用时模板走的是自身默认分支。若这里写死
    /// bool true，那么"上层只对 MNN 调了关思考、忘了调 llama"就会静默分歧 ——
    /// 而两栈分歧正是这次重构要消灭的那类 bug。
    int32_t thinkingMode = -1;

    /// 模板是否支持 enable_thinking 的懒探测缓存（-1 未探测 / 0 否 / 1 是）。
    /// 探测要让上游真渲染一次模板（贵），而模板在 load 后不可变，故只算一次；
    /// releaseResources 里必须连同 chatTemplates 一起失效。
    int32_t supportsThinking = -1;
"""

# ─────────────────────────── 2. 公共头声明 ───────────────────────────
ENGINE_H = ROOT + r"\llm\llama\src\main\cpp\llama_engine.h"
ENGINE_H_OLD = """    /// 关闭工具调用文法，回到自由文本。
    bool clearToolCallGrammar(std::string* err);
"""
ENGINE_H_NEW = """    /// 关闭工具调用文法，回到自由文本。
    bool clearToolCallGrammar(std::string* err);

    /// 开关思考段（与 MNN 的 setThinkingMode 语义对齐）。
    /// supported 输出该 GGUF 内嵌模板是否真的支持 enable_thinking；
    /// **它不影响返回值** —— 配置写入成功即成功，模板是否采用由模板自己决定，
    /// 这一点与 MNN 侧的 set_config 行为保持一致。
    bool setThinkingMode(bool enabled, bool* supported, std::string* err);

    /// 当前 GGUF 模板是否支持 enable_thinking（懒探测，结果缓存在会话里）。
    bool supportsThinking(std::string* err);
"""

# ─────────────────────── 3. 实现（插在 applyChatTemplate 之前）───────────────────────
CHAT_CPP = ROOT + r"\llm\llama\src\main\cpp\llama_engine_chat.cpp"
CHAT_IMPL_ANCHOR = """bool LlamaEngine::applyChatTemplate(const std::vector<std::string>& roles,
"""
CHAT_IMPL_NEW = """// ═════════════════════════════════════════════════════════════════════════
// 思考段开关（与 MNN 的 MNNLlmSession.setThinkingMode 对齐）
// ═════════════════════════════════════════════════════════════════════════
// 上游机制，不是自研：
//   llama.cpp 的 common_chat_templates_inputs 自带 enable_thinking 字段，
//   common_chat_templates_apply 会把它作为**同名 jinja 变量**注入模板上下文
//   （common/chat.cpp: {"enable_thinking", inputs.enable_thinking}）。
//   MNN 侧同理，只是走 set_config 的 jinja.context。
//   于是两侧的**用户可见行为**完全一致：模板里
//   `{%- if enable_thinking is false %}` 那一支被选中，思考段不再产出。
//
// 能力探测同样用上游函数（chat.h: common_chat_templates_support_enable_thinking）。
// 它内部会把模板真渲染一次、再看 params.supports_thinking —— 比自己扫模板字符串
// 找关键字可靠得多（变量名可能出现在注释、转义或默认值里）。
namespace {

/// 探测 + 缓存。返回模板是否支持 enable_thinking。
/// 探测要让上游真渲染一次模板，而模型作者写坏的模板会在 render 时抛异常 ——
/// 异常穿过 JNI 帧就是 std::terminate（App 直接消失，没有 Java 异常可抓），
/// 所以这里必须兜住。
bool detectSupportsThinking(llama_detail::Session* session) {
    if (session == nullptr) return false;
    if (session->supportsThinking >= 0) return session->supportsThinking == 1;
    bool ok = false;
    if (session->chatTemplates != nullptr) {
        try {
            ok = common_chat_templates_support_enable_thinking(session->chatTemplates.get());
        } catch (const std::exception& e) {
            LOGE("探测 enable_thinking 支持失败：%s", e.what());
            ok = false;
        } catch (...) {
            LOGE("探测 enable_thinking 支持失败（未知错误）。");
            ok = false;
        }
    }
    session->supportsThinking = ok ? 1 : 0;
    return ok;
}

}  // namespace

bool LlamaEngine::setThinkingMode(bool enabled, bool* supported, std::string* err) {
    if (!impl_) {
        if (err) *err = "引擎内部状态缺失（Impl 为空）。";
        return false;
    }
    llama_detail::Session* session = &impl_->session;
    if (session->chatTemplates == nullptr) {
        failWith(session, err, "模型未就绪或该 GGUF 未内嵌聊天模板（chat template 缺失）。");
        return false;
    }
    // 刻意**不**因"模板不支持"而拒绝：与 MNN 对齐 —— 配置写入成功即成功。
    // 模板不支持时这次设置是无害的空操作，上层用 supportsThinking() 单独判断。
    session->thinkingMode = enabled ? 1 : 0;
    if (supported != nullptr) *supported = detectSupportsThinking(session);
    LOGI("setThinkingMode: enabled=%d supported=%d",
         enabled ? 1 : 0, session->supportsThinking == 1 ? 1 : 0);
    return true;
}

bool LlamaEngine::supportsThinking(std::string* err) {
    if (!impl_) {
        if (err) *err = "引擎内部状态缺失（Impl 为空）。";
        return false;
    }
    llama_detail::Session* session = &impl_->session;
    if (session->chatTemplates == nullptr) {
        if (err) *err = "模型未就绪或该 GGUF 未内嵌聊天模板（chat template 缺失）。";
        return false;
    }
    return detectSupportsThinking(session);
}

bool LlamaEngine::applyChatTemplate(const std::vector<std::string>& roles,
"""

# 两条渲染路径都要把三态开关落进 inputs
CHAT_PLAIN_OLD = """    inputs.add_generation_prompt = addAssistant;
    inputs.use_jinja = true;

    // try/catch 是必需的"""
CHAT_PLAIN_NEW = """    inputs.add_generation_prompt = addAssistant;
    inputs.use_jinja = true;
    // 三态：**只有上层显式设置过**才覆盖 llama.cpp 的默认值(true)。
    // "上层没管思考"与"上层明确关掉思考"必须是两件事。
    if (session->thinkingMode >= 0) {
        inputs.enable_thinking = session->thinkingMode == 1;
    }

    // try/catch 是必需的"""

CHAT_STRUCT_OLD = """        inputs.add_generation_prompt = addAssistant;
        inputs.use_jinja = true;

        const common_chat_params params ="""
CHAT_STRUCT_NEW = """        inputs.add_generation_prompt = addAssistant;
        inputs.use_jinja = true;
        // 工具轮同样受思考开关约束（见 applyChatTemplate 处的说明）。
        if (session->thinkingMode >= 0) {
            inputs.enable_thinking = session->thinkingMode == 1;
        }

        const common_chat_params params ="""

# ─────────────────── 4. releaseResources 里让探测缓存失效 ───────────────────
ENGINE_CPP = ROOT + r"\llm\llama\src\main\cpp\llama_engine.cpp"
ENGINE_CPP_OLD = """    session.thinkSplitter.reset();

    // 温控是设备级状态"""
ENGINE_CPP_NEW = """    session.thinkSplitter.reset();

    // 思考开关也绑在会话上：模板已释放 → 探测缓存必须跟着失效，
    // 否则换成一个不支持 enable_thinking 的模型后，supportsThinking 仍返回旧结果。
    // 开关本身回到"未设置"，让新模型走它自己模板的默认分支。
    session.thinkingMode = -1;
    session.supportsThinking = -1;

    // 温控是设备级状态"""

# ─────────────────────────── 5. JNI 实现 ───────────────────────────
JNI_CPP = ROOT + r"\llm\llama\src\main\cpp\llama_jni.cpp"
JNI_ANCHOR = """extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeClearToolCallGrammar(JNIEnv* env, jclass clazz,
                                                                    jlong sessionPtr) {
    (void) env;
    (void) clazz;
    REQUIRE_ENGINE_VAL(sessionPtr, JNI_FALSE);
    std::string err;
    const bool ok = engine->clearToolCallGrammar(&err);
    if (!ok) LOGE("nativeClearToolCallGrammar: %s", err.c_str());
    return ok ? JNI_TRUE : JNI_FALSE;
}
"""
JNI_NEW = JNI_ANCHOR + """
// ── 思考段开关（与 MNN 的 MNNLlmSession.setThinkingMode 对齐）────────────────
// 返回的是"写入成功"（与 MNN 的 set_config 结果同义）；模板是否真的支持
// enable_thinking 由 nativeSupportsThinking 单独回答 —— 把两件事混进一个
// bool 会让上层无法区分"开关没生效"和"这个模型压根不支持思考"。
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeSetThinkingMode(JNIEnv* env, jclass clazz,
                                                               jlong sessionPtr,
                                                               jboolean enabled) {
    (void) env;
    (void) clazz;
    REQUIRE_ENGINE_VAL(sessionPtr, JNI_FALSE);
    std::string err;
    bool supported = false;
    const bool ok = engine->setThinkingMode(enabled == JNI_TRUE, &supported, &err);
    if (!ok) LOGE("nativeSetThinkingMode: %s", err.c_str());
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeSupportsThinking(JNIEnv* env, jclass clazz,
                                                                jlong sessionPtr) {
    (void) env;
    (void) clazz;
    REQUIRE_ENGINE_VAL(sessionPtr, JNI_FALSE);
    std::string err;
    const bool ok = engine->supportsThinking(&err);
    if (!ok && !err.empty()) LOGE("nativeSupportsThinking: %s", err.c_str());
    return ok ? JNI_TRUE : JNI_FALSE;
}
"""

JNI_TABLE_OLD = """    {"nativeClearToolCallGrammar", "(J)Z",
     reinterpret_cast<void*>(Java_com_ai_assistance_llama_LlamaNative_nativeClearToolCallGrammar)},
"""
JNI_TABLE_NEW = """    // 🔴 nativeSetToolCallGrammar 此前**只存在于本文件上半部分**：
    //    实现有、注册表没有、Kotlin 声明也没有 —— LlamaEngine::setToolCallGrammar
    //    （完整实现）在上层根本不可达，工具调用只能靠模型自由发挥。
    {"nativeSetToolCallGrammar", "(JLjava/lang/String;[Ljava/lang/String;)Z",
     reinterpret_cast<void*>(Java_com_ai_assistance_llama_LlamaNative_nativeSetToolCallGrammar)},
    {"nativeClearToolCallGrammar", "(J)Z",
     reinterpret_cast<void*>(Java_com_ai_assistance_llama_LlamaNative_nativeClearToolCallGrammar)},
    {"nativeSetThinkingMode", "(JZ)Z",
     reinterpret_cast<void*>(Java_com_ai_assistance_llama_LlamaNative_nativeSetThinkingMode)},
    {"nativeSupportsThinking", "(J)Z",
     reinterpret_cast<void*>(Java_com_ai_assistance_llama_LlamaNative_nativeSupportsThinking)},
"""
JNI_COUNT_OLD = "constexpr int kLlamaNativeCount = 14;"
JNI_COUNT_NEW = "constexpr int kLlamaNativeCount = 17;"

# ─────────────────────────── 6. LlamaNative.kt ───────────────────────────
NATIVE_KT = ROOT + r"\llm\llama\src\main\java\com\ai\assistance\llama\LlamaNative.kt"
NATIVE_KT_OLD = "    external fun nativeClearToolCallGrammar(sessionPtr: Long): Boolean"
NATIVE_KT_NEW = """    /**
     * 装载工具调用文法（GBNF + 惰性触发模式）。
     *
     * 🔴 此前本方法**只存在于 native 侧**：llama_jni.cpp 里实现了
     * Java_..._nativeSetToolCallGrammar，但既没进 kLlamaNativeMethods 注册表，
     * 这里也没有声明 —— 于是 LlamaEngine::setToolCallGrammar（完整实现）在
     * 上层**根本没有可达入口**，工具调用只能靠模型自由发挥。现已补齐。
     *
     * @param triggerPatterns 惰性触发模式；命中后才开始套用文法。空数组 = 全程约束。
     */
    external fun nativeSetToolCallGrammar(
        sessionPtr: Long,
        grammar: String,
        triggerPatterns: Array<String>
    ): Boolean

    external fun nativeClearToolCallGrammar(sessionPtr: Long): Boolean

    /**
     * 开关思考段。返回**写入是否成功**（与 MNN 的 setThinkingMode 同义）；
     * 模板是否真的支持用 [nativeSupportsThinking] 单独查询。
     */
    external fun nativeSetThinkingMode(sessionPtr: Long, enabled: Boolean): Boolean

    /** 该 GGUF 内嵌模板是否支持 enable_thinking（懒探测，结果缓存在会话里）。 */
    external fun nativeSupportsThinking(sessionPtr: Long): Boolean"""

# ─────────────────────────── 7. LlamaSession.kt ───────────────────────────
SESSION_KT = ROOT + r"\llm\llama\src\main\java\com\ai\assistance\llama\LlamaSession.kt"
SESSION_KT_OLD = "    fun clearToolCallGrammar(): Boolean {"
SESSION_KT_NEW = """    /**
     * 装载工具调用文法（GBNF + 惰性触发模式），与 [clearToolCallGrammar] 配对。
     * 此前 native 侧有完整实现却没有任何可达入口（注册表 + 本层声明双缺），
     * 工具调用只能靠模型自由发挥 —— 本次接通。
     *
     * @param triggerPatterns 命中这些模式后才开始套用文法；空 = 全程约束。
     */
    fun setToolCallGrammar(
        grammar: String,
        triggerPatterns: List<String> = emptyList()
    ): Boolean {
        val ptr: Long
        synchronized(lock) {
            checkValid()
            ptr = sessionPtr
        }

        return LlamaNative.nativeSetToolCallGrammar(ptr, grammar, triggerPatterns.toTypedArray())
    }

    /**
     * 开关思考段（与 MNNLlmSession.setThinkingMode 对齐）。
     * @return 是否写入成功；模板是否真支持请用 [supportsThinking]。
     */
    fun setThinkingMode(enabled: Boolean): Boolean {
        val ptr: Long
        synchronized(lock) {
            checkValid()
            ptr = sessionPtr
        }

        return LlamaNative.nativeSetThinkingMode(ptr, enabled)
    }

    /** 当前模型模板是否支持 enable_thinking（结果由 native 缓存）。 */
    fun supportsThinking(): Boolean {
        val ptr: Long
        synchronized(lock) {
            checkValid()
            ptr = sessionPtr
        }

        return LlamaNative.nativeSupportsThinking(ptr)
    }

    fun clearToolCallGrammar(): Boolean {"""

PATCHES = [
    (IMPL_H, [(IMPL_H_OLD, IMPL_H_NEW)]),
    (ENGINE_H, [(ENGINE_H_OLD, ENGINE_H_NEW)]),
    (CHAT_CPP, [
        (CHAT_IMPL_ANCHOR, CHAT_IMPL_NEW),
        (CHAT_PLAIN_OLD, CHAT_PLAIN_NEW),
        (CHAT_STRUCT_OLD, CHAT_STRUCT_NEW),
    ]),
    (ENGINE_CPP, [(ENGINE_CPP_OLD, ENGINE_CPP_NEW)]),
    (JNI_CPP, [
        (JNI_ANCHOR, JNI_NEW),
        (JNI_TABLE_OLD, JNI_TABLE_NEW),
        (JNI_COUNT_OLD, JNI_COUNT_NEW),
    ]),
    (NATIVE_KT, [(NATIVE_KT_OLD, NATIVE_KT_NEW)]),
    (SESSION_KT, [(SESSION_KT_OLD, SESSION_KT_NEW)]),
]


def main():
    # 先全部读进内存并校验，任何一处不命中就整体不写（避免半装载状态）
    staged = []
    failed = False
    for path, pairs in PATCHES:
        raw = open(path, "rb").read()
        crlf = b"\r\n" in raw
        s = raw.decode("utf-8").replace("\r\n", "\n")
        for old, new in pairs:
            n = s.count(old)
            if n != 1:
                print("FAIL %s: anchor hits=%d (need exactly 1)\n     -> %s"
                      % (path.split("\\")[-1], n, old.split("\n")[0][:70]))
                failed = True
                continue
            s = s.replace(old, new, 1)
        staged.append((path, s, crlf))

    if failed:
        print("\n任何一个锚点不唯一都整体放弃写入（防半装载）。")
        return 1

    for path, s, crlf in staged:
        out = s.replace("\n", "\r\n") if crlf else s
        open(path, "wb").write(out.encode("utf-8"))
        print("PATCHED %-28s (%s, %d bytes)"
              % (path.split("\\")[-1], "CRLF" if crlf else "LF", len(out.encode("utf-8"))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
