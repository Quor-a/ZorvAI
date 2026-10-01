// =============================================================================
// L2 · JNI 桥接层（llama.cpp）—— **薄封装**
// =============================================================================
// 架构图对本层的定义（逐字）：
//   「L2 JNI 桥接层：薄封装，**只传 opaque 句柄（jlong）**，回调上行」
//
// 本文件的职责**只有**四件事：
//   ① 解包 JNI 参数（jstring/jobjectArray/jboolean → std::string/vector/bool）
//   ② 通过 jlong 句柄 → 在句柄表里取出 LlamaEngine*（拿不到就返回友好错误，不崩）
//   ③ 调引擎（L3/L4）
//   ④ 把结果与回调转回 JNI（std::string → jstring、C 函数指针 ↔ Java 方法）
//
// **本文件绝不 include llama.h / chat.h / llama_engine_impl.h。**
//   那是分层的硬边界。理由（架构图底部第三条）：
//   llama.h 的结构体含 std::vector / std::string / common_chat_templates_ptr，
//   一旦它们出现在 JNI 边界上，就要求两侧 STL/分配器/ABI 完全一致 ——
//   任何一处不匹配就是 SIGABRT（vector::_M_realloc_insert 一类崩溃）。
//   所以跨边界的只有：jlong 句柄、基础类型、UTF-8 的 std::string。
//
// 与「旧构架」的分界（这次真删了什么）：
//   旧：llama_jni_stub.cpp 单文件 2074 行，JNI 解析、llama_decode 调用、
//       KV 前缀复用、温控调档、崩溃 tombstone、注册表全部糊在一起。
//       所谓「L3 统一引擎抽象」里的 Engine 接口**没有任何实现类**，
//       registerEngine 的调用点是零，createEngineForModel 永远返回
//       「引擎未注册」—— 是死代码。
//   新：本文件不再出现一次 llama_* 调用。全部下沉到 LlamaEngine。
// =============================================================================

#include <jni.h>

#include <android/log.h>

#include <cstdint>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "llama_engine.h"        // L3/L4 契约（不含 llama.h）
#include "quro/engine.h"
#include "quro/engine_types.h"
#include "quro/handle_registry.h"
#include "quro/jni_support.h"

// 日志 TAG 与引擎侧保持一致，用户抓的是 `logcat -s LlamaNative`。
// 注意：**不要**改成 "QuroLlm.Jni" —— 那会让既有的排障命令抓不到生成链路。
#define TAG "LlamaNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

using quro::llm::LlamaEngine;

// ═════════════════════════════════════════════════════════════════════════
// 字符串转换
// ═════════════════════════════════════════════════════════════════════════

std::string jstringToString(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (chars == nullptr) return std::string();
    std::string out(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return out;
}

/// UTF-8 字节 → Java String。
///
/// 为什么不直接用 `env->NewStringUTF()`：JNI 的 "UTF-8" 实际是 **Modified UTF-8**，
/// 不认标准的 4 字节序列。模型吐出的 emoji（U+1F600 一类）会被它判为非法并
/// 塞进 0xFFFD，用户看到一片 �。这里手工解码成 UTF-16（含代理对），再走
/// `NewString(jchar*)` —— 这是唯一对所有合法 UTF-8 都正确的路径。
jstring bytesUtf8ToJstring(JNIEnv* env, const std::string& bytes) {
    std::u16string out;
    out.reserve(bytes.size());

    const unsigned char* s = reinterpret_cast<const unsigned char*>(bytes.data());
    size_t i = 0;
    while (i < bytes.size()) {
        uint32_t cp = 0;
        const unsigned char c0 = s[i];

        if (c0 < 0x80) {
            cp = c0;
            i += 1;
        } else if ((c0 & 0xE0) == 0xC0 && i + 1 < bytes.size()) {
            const unsigned char c1 = s[i + 1];
            if ((c1 & 0xC0) != 0x80) {
                cp = 0xFFFD;
                i += 1;
            } else {
                cp = ((c0 & 0x1F) << 6) | (c1 & 0x3F);
                if (cp < 0x80) cp = 0xFFFD;
                i += 2;
            }
        } else if ((c0 & 0xF0) == 0xE0 && i + 2 < bytes.size()) {
            const unsigned char c1 = s[i + 1];
            const unsigned char c2 = s[i + 2];
            if (((c1 & 0xC0) != 0x80) || ((c2 & 0xC0) != 0x80)) {
                cp = 0xFFFD;
                i += 1;
            } else {
                cp = ((c0 & 0x0F) << 12) | ((c1 & 0x3F) << 6) | (c2 & 0x3F);
                if (cp < 0x800) cp = 0xFFFD;
                i += 3;
            }
        } else if ((c0 & 0xF8) == 0xF0 && i + 3 < bytes.size()) {
            const unsigned char c1 = s[i + 1];
            const unsigned char c2 = s[i + 2];
            const unsigned char c3 = s[i + 3];
            if (((c1 & 0xC0) != 0x80) || ((c2 & 0xC0) != 0x80) || ((c3 & 0xC0) != 0x80)) {
                cp = 0xFFFD;
                i += 1;
            } else {
                cp = ((c0 & 0x07) << 18) | ((c1 & 0x3F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F);
                if (cp < 0x10000 || cp > 0x10FFFF) cp = 0xFFFD;
                i += 4;
            }
        } else {
            cp = 0xFFFD;
            i += 1;
        }

        if (cp <= 0xFFFF) {
            out.push_back(static_cast<char16_t>(cp));
        } else {
            cp -= 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        }
    }

    return env->NewString(reinterpret_cast<const jchar*>(out.data()),
                          static_cast<jsize>(out.size()));
}

/// 把 jobjectArray 里的每个元素转成 std::string。空数组/空元素都跳过。
std::vector<std::string> jstringArrayToVector(JNIEnv* env, jobjectArray array) {
    std::vector<std::string> out;
    if (array == nullptr) return out;
    const jsize count = env->GetArrayLength(array);
    if (count <= 0) return out;
    out.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto element = reinterpret_cast<jstring>(env->GetObjectArrayElement(array, i));
        if (element != nullptr) {
            out.push_back(jstringToString(env, element));
            env->DeleteLocalRef(element);
        } else {
            // 占位：roles/contents 是**等长并行数组**，下标必须一一对应。
            // 丢弃一个元素会让后面的角色与内容全部错位（模板语义直接崩）。
            out.emplace_back();
        }
    }
    return out;
}

// ═════════════════════════════════════════════════════════════════════════
// 句柄
// ═════════════════════════════════════════════════════════════════════════
// 用 HandleRegistry 而不是 `reinterpret_cast<LlamaEngine*>(handle)`：
//   裸指针当句柄 = 把内存地址交给 Java 保管。一旦出现「取消后又回调一次」
//   「切模型重建会话但旧任务仍在跑」这类时序问题，native 就会解引用已释放对象
//   → SIGSEGV，栈里只剩 pc 00000000。
//   句柄表用「槽位 + 代际号」，槽位复用后旧句柄永久失效，取不到就返回友好错误。

LlamaEngine* engineOf(jlong handle) {
    return static_cast<LlamaEngine*>(
        quro::llm::HandleRegistry::instance().get(handle, quro::llm::HandleKind::ENGINE));
}

/// 取不到引擎时的统一返回：返回 false 并**不**抛异常（契约：不跨 JNI 抛异常）。
#define REQUIRE_ENGINE(handle)                                              \
    LlamaEngine* engine = engineOf(handle);                                 \
    if (engine == nullptr) {                                                \
        LOGE("无效或已释放的会话句柄：%lld", (long long) (handle));          \
        return;                                                             \
    }
#define REQUIRE_ENGINE_VAL(handle, val)                                     \
    LlamaEngine* engine = engineOf(handle);                                 \
    if (engine == nullptr) {                                                \
        LOGE("无效或已释放的会话句柄：%lld", (long long) (handle));          \
        return (val);                                                       \
    }

// ═════════════════════════════════════════════════════════════════════════
// Java 回调 → L3 Callbacks 的桥
// ═════════════════════════════════════════════════════════════════════════
// 这是 L2 层最核心的一段：架构图上「回调上行」这条箭头就落在这里。
//
// 分工：
//   · Java 侧只认识 `onToken(String): Boolean` 与 `onProgress(String,int,int): void`
//   · 引擎侧只认识 C 函数指针 `void(*)(void*, const TokenChunk&)`
//   · 本结构体负责把后者翻译成前者，并把 Java 的 **Boolean 返回值**（"我不要了"）
//     收集起来，通过 `shouldStop` 回传引擎 —— 这就是 L3 里 StopFn 的存在意义。
//
// 为什么不用 std::function：回调可能从引擎工作线程触发，裸函数指针没有堆分配、
// 没有异常、没有 STL 依赖，在 ABI 边界上最稳（engine_types.h 的既定取舍）。
struct JavaTokenSink {
    void* callbackRef = nullptr;      // jni::retainCallback 拿到的 GlobalRef 封装
    jmethodID midOnToken = nullptr;
    jmethodID midOnProgress = nullptr;

    // Java 侧要求停止的两个来源，合起来就是 shouldStop 的返回值。
    bool javaRejected = false;        // onToken 返回 false
    bool javaThrew = false;           // onToken 抛异常（已清除，但必须停）

    std::string thrownDetail;         // javaThrew 时给用户看的原文
};

/// onToken 的上行翻译。
void sinkOnToken(void* user, const quro::llm::TokenChunk& chunk) {
    auto* sink = static_cast<JavaTokenSink*>(user);
    if (sink == nullptr || chunk.text == nullptr || chunk.len == 0) return;

    // Scope 会保证当前线程已 attach（同线程调用时零开销，跨线程时正确 attach/detach）。
    // 旧实现里有 "AttachCurrentThread 但从不 detach" 的写法，长会话下会泄漏
    // 线程结构直至 OOM —— 这里统一走 RAII。
    quro::llm::jni::Scope scope;
    JNIEnv* env = scope.env();
    if (env == nullptr) {
        sink->javaRejected = true;
        return;
    }
    jobject callbackObj = quro::llm::jni::callbackObject(sink->callbackRef);
    if (callbackObj == nullptr) {
        // GlobalRef 已被释放（生成收尾与回调竞争）。这里必须停：
        // 拿 null 去 CallBooleanMethod 是 JVM 层面的非法调用。
        sink->javaRejected = true;
        return;
    }

    jstring jdelta = bytesUtf8ToJstring(env, std::string(chunk.text, chunk.len));
    if (jdelta == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }

    const jboolean keepGoing = env->CallBooleanMethod(callbackObj, sink->midOnToken, jdelta);
    env->DeleteLocalRef(jdelta);

    if (env->ExceptionCheck()) {
        // Java 侧抛异常（例如 UI 已销毁）。**必须**清除异常标记，
        // 否则后续所有 JNI 调用（含 DetachCurrentThread）都会失败。
        env->ExceptionClear();
        sink->javaThrew = true;
        sink->thrownDetail = "Java 回调抛出异常，生成已中止";
        LOGE("Java callback threw exception; stopping generation");
        return;
    }
    if (!keepGoing) {
        // 旧实现是 `break`（收尾并返回成功）。新实现改成 shouldStop 拉取式，
        // 引擎在下一次 cbs.stopped() 检查处等价 break —— 语义完全一致。
        sink->javaRejected = true;
    }
}

/// onProgress 的上行翻译。
/// 注意**不做百分比换算**：引擎给的就是 token 数，Java 侧按
/// LOCAL_PREFILL_PROGRESS_TOKEN_THRESHOLD 决定值不值得显示进度条。
void sinkOnProgress(void* user, const quro::llm::ProgressChunk& chunk) {
    auto* sink = static_cast<JavaTokenSink*>(user);
    if (sink == nullptr || sink->midOnProgress == nullptr) return;

    quro::llm::jni::Scope scope;
    JNIEnv* env = scope.env();
    if (env == nullptr) return;

    jstring jstage = bytesUtf8ToJstring(env, chunk.stage == nullptr ? "" : chunk.stage);
    if (jstage == nullptr) return;

    env->CallVoidMethod(quro::llm::jni::callbackObject(sink->callbackRef), sink->midOnProgress,
                        jstage, static_cast<jint>(chunk.current), static_cast<jint>(chunk.total));
    env->DeleteLocalRef(jstage);

    if (env->ExceptionCheck()) {
        // 进度是"锦上添花"，抛异常只清掉、**不**中止生成（与旧实现一致）。
        env->ExceptionClear();
    }
}

/// shouldStop：把 Java 侧的两种"我不要了"合并成引擎的一个布尔。
/// 引擎在每个 chunk / token 边界调用一次，所以这里必须够便宜（无 JNI 调用）。
bool sinkShouldStop(void* user) {
    auto* sink = static_cast<JavaTokenSink*>(user);
    if (sink == nullptr) return false;
    return sink->javaRejected || sink->javaThrew;
}

/// 组装一套 Callbacks。
quro::llm::Callbacks makeCallbacks(JavaTokenSink* sink, bool wantProgress) {
    quro::llm::Callbacks cbs;
    cbs.user = sink;
    cbs.onToken = &sinkOnToken;
    cbs.onProgress = wantProgress ? &sinkOnProgress : nullptr;
    cbs.shouldStop = &sinkShouldStop;
    return cbs;
}

}  // namespace

// ═════════════════════════════════════════════════════════════════════════
// JNI 入口
// ═════════════════════════════════════════════════════════════════════════

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeIsAvailable(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
    // 本 .so 只在定义了 QURO_HAS_LLAMA_CPP 的构建里产出 —— 能加载就代表可用。
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeGetUnavailableReason(JNIEnv* env, jclass clazz) {
    (void) clazz;
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeCreateSession(
        JNIEnv* env,
        jclass clazz,
        jstring pathModel,
        jint nThreads,
        jint nCtx,
        jint nBatch,
        jint nUBatch,
        jint nGpuLayers,
        jboolean useMmap,
        jboolean flashAttention,
        jboolean kvUnified,
        jboolean offloadKqv,
        jint thermalPollMs) {
    (void) clazz;

    quro::llm::LoadConfig cfg;
    cfg.modelPath = jstringToString(env, pathModel);
    if (cfg.modelPath.empty()) {
        LOGE("nativeCreateSession: 模型路径为空");
        return 0;
    }
    cfg.nThreads = static_cast<int>(nThreads);
    cfg.nCtx = static_cast<int>(nCtx);
    cfg.nBatch = static_cast<int>(nBatch);
    cfg.nUBatch = static_cast<int>(nUBatch);
    cfg.nGpuLayers = static_cast<int>(nGpuLayers);
    cfg.useMmap = (useMmap == JNI_TRUE);
    cfg.flashAttention = (flashAttention == JNI_TRUE);
    cfg.kvUnified = (kvUnified == JNI_TRUE);
    cfg.offloadKqv = (offloadKqv == JNI_TRUE);
    cfg.thermalPollMs = static_cast<int>(thermalPollMs);
    // adaptiveThermal 与温控周期是同一件事的两种表达：周期为 0 即"关"。
    // 在这里对齐，避免两个字段出现"一个开一个关"的歧义状态。
    cfg.adaptiveThermal = cfg.thermalPollMs > 0;

    auto engine = std::make_unique<LlamaEngine>();
    std::string err;
    if (!engine->load(cfg, &err)) {
        LOGE("nativeCreateSession: 引擎加载失败：%s", err.c_str());
        // load 失败必须自清理（契约要求）：engine 随 unique_ptr 析构，
        // 其析构会走 unload() 释放任何半加载状态。
        return 0;
    }

    // 注册成功前**不** release：句柄表已满（add 返回 0）时，engine 仍由
    // unique_ptr 持有 → 离开作用域自动析构 → 权重被正确释放。
    // 若先 release 再发现注册失败，就只能靠裸 delete 兜底，容易漏。
    LlamaEngine* rawEngine = engine.get();
    const int64_t handle = quro::llm::HandleRegistry::instance().add(
        rawEngine, quro::llm::HandleKind::ENGINE, "llama");
    if (handle == 0) {
        LOGE("nativeCreateSession: 句柄表已满，无法注册会话（将释放已加载的权重）");
        return 0;
    }
    engine.release();  // 所有权移交句柄表，由 nativeReleaseSession 负责销毁
    return static_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeReleaseSession(JNIEnv* env, jclass clazz,
                                                             jlong sessionPtr) {
    (void) env;
    (void) clazz;
    if (sessionPtr == 0) return;

    // remove 会校验代际号与类别 —— 重复释放（Kotlin 侧 close 两次）第一次之后
    // 旧句柄立即失效，第二次拿到的就是 nullptr，不会二次 delete。
    void* raw = quro::llm::HandleRegistry::instance().remove(
        sessionPtr, quro::llm::HandleKind::ENGINE);
    if (raw == nullptr) return;

    std::unique_ptr<LlamaEngine> engine(static_cast<LlamaEngine*>(raw));
    // 析构即 unload：:llm 进程切模型时会销毁旧引擎，权重不还给 OS 的话，
    // 下一次 load 会与尚未回收的旧权重叠加 → 低端机直接 OOM。
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeCancel(JNIEnv* env, jclass clazz,
                                                      jlong sessionPtr) {
    (void) env;
    (void) clazz;
    REQUIRE_ENGINE(sessionPtr);
    // cancel 必须可被任意线程立即调用（UI 点"停止"）：引擎侧只置原子标志，
    // 并在 llama_decode 的 abort_callback 与每个 token 边界检查，不抢 genLock。
    engine->cancel();
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeResetKv(JNIEnv* env, jclass clazz,
                                                       jlong sessionPtr) {
    (void) env;
    (void) clazz;
    REQUIRE_ENGINE(sessionPtr);
    std::string err;
    if (!engine->resetKv(&err)) {
        LOGE("nativeResetKv: %s", err.c_str());
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeCountTokens(JNIEnv* env, jclass clazz,
                                                           jlong sessionPtr, jstring text) {
    (void) clazz;
    REQUIRE_ENGINE_VAL(sessionPtr, 0);
    std::string err;
    const int32_t n = engine->countTokens(jstringToString(env, text), &err);
    if (n < 0) {
        LOGE("nativeCountTokens: %s", err.c_str());
        return 0;
    }
    return static_cast<jint>(n);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeSetSamplingParams(
        JNIEnv* env,
        jclass clazz,
        jlong sessionPtr,
        jfloat temperature,
        jfloat topP,
        jint topK,
        jfloat repetitionPenalty,
        jfloat frequencyPenalty,
        jfloat presencePenalty,
        jint penaltyLastN) {
    (void) env;
    (void) clazz;
    REQUIRE_ENGINE_VAL(sessionPtr, JNI_FALSE);
    std::string err;
    const bool ok = engine->setSamplingParams(
        static_cast<float>(temperature), static_cast<float>(topP), static_cast<int32_t>(topK),
        static_cast<float>(repetitionPenalty), static_cast<float>(frequencyPenalty),
        static_cast<float>(presencePenalty), static_cast<int32_t>(penaltyLastN), &err);
    if (!ok) LOGE("nativeSetSamplingParams: %s", err.c_str());
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeSetToolCallGrammar(
        JNIEnv* env,
        jclass clazz,
        jlong sessionPtr,
        jstring grammar,
        jobjectArray triggerPatterns) {
    (void) clazz;
    if (grammar == nullptr) return JNI_FALSE;
    REQUIRE_ENGINE_VAL(sessionPtr, JNI_FALSE);
    std::string err;
    const bool ok = engine->setToolCallGrammar(
        jstringToString(env, grammar), jstringArrayToVector(env, triggerPatterns), &err);
    if (!ok) LOGE("nativeSetToolCallGrammar: %s", err.c_str());
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
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

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeApplyChatTemplate(
        JNIEnv* env,
        jclass clazz,
        jlong sessionPtr,
        jobjectArray roles,
        jobjectArray contents,
        jboolean addAssistant) {
    (void) clazz;
    if (roles == nullptr || contents == nullptr) return nullptr;
    REQUIRE_ENGINE_VAL(sessionPtr, nullptr);

    std::string prompt;
    std::string err;
    const bool ok = engine->applyChatTemplate(
        jstringArrayToVector(env, roles), jstringArrayToVector(env, contents),
        addAssistant == JNI_TRUE, &prompt, &err);
    if (!ok) {
        LOGE("nativeApplyChatTemplate: %s", err.c_str());
        return nullptr;
    }
    // 模板渲染结果可能含 emoji / 生僻字 —— 必须走 UTF-8 精确转换，不能 NewStringUTF。
    return bytesUtf8ToJstring(env, prompt);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeApplyStructuredChatTemplate(
        JNIEnv* env,
        jclass clazz,
        jlong sessionPtr,
        jstring messagesJson,
        jstring toolsJson,
        jboolean addAssistant) {
    (void) clazz;
    if (messagesJson == nullptr) return nullptr;
    REQUIRE_ENGINE_VAL(sessionPtr, nullptr);

    std::string prompt;
    std::string err;
    const bool ok = engine->applyStructuredChatTemplate(
        jstringToString(env, messagesJson), jstringToString(env, toolsJson),
        addAssistant == JNI_TRUE, &prompt, &err);
    if (!ok) {
        // 刻意不写 lastError（同引擎侧注释）：工具轮首次调用时"未就绪"是常态，
        // 写进去会让聊天气泡冒出一条误导性错误。只留日志。
        LOGE("nativeApplyStructuredChatTemplate: %s", err.c_str());
        return nullptr;
    }
    return bytesUtf8ToJstring(env, prompt);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeParseToolCallResponse(JNIEnv* env, jclass clazz,
                                                                    jlong sessionPtr,
                                                                    jstring content) {
    (void) clazz;
    if (content == nullptr) return nullptr;
    REQUIRE_ENGINE_VAL(sessionPtr, nullptr);

    std::string out;
    std::string err;
    // 返回 false + out 为空 = "本轮没有工具调用"，是**正常结果**，直接给 Java null。
    if (!engine->parseToolCallResponse(jstringToString(env, content), &out, &err)) {
        return nullptr;
    }
    return bytesUtf8ToJstring(env, out);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeGetLastError(JNIEnv* env, jclass clazz,
                                                           jlong sessionPtr) {
    (void) clazz;
    REQUIRE_ENGINE_VAL(sessionPtr, nullptr);
    const std::string last = engine->lastError();
    if (last.empty()) return nullptr;
    return bytesUtf8ToJstring(env, last);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeGenerateStream(JNIEnv* env, jclass clazz,
                                                             jlong sessionPtr, jstring prompt,
                                                             jint maxTokens, jobject callback) {
    (void) clazz;
    if (callback == nullptr) return JNI_FALSE;
    REQUIRE_ENGINE_VAL(sessionPtr, JNI_FALSE);

    // ── 解析 Java 回调 ──
    jclass cbCls = env->GetObjectClass(callback);
    if (cbCls == nullptr) {
        LOGE("无法解析生成回调对象");
        return JNI_FALSE;
    }
    jmethodID midOnToken = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)Z");
    if (midOnToken == nullptr) {
        env->ExceptionClear();
        LOGE("回调缺少 onToken(Ljava/lang/String;)Z 方法");
        env->DeleteLocalRef(cbCls);
        return JNI_FALSE;
    }
    // 可选进度回调：prefill 在手机 CPU 上可能耗时数十秒，期间一个 token 都吐不出来，
    // UI 全程空白 → 用户观感就是"卡死"。找不到该方法时静默降级（老版本 Java 接口无它）。
    jmethodID midOnProgress = env->GetMethodID(cbCls, "onProgress", "(Ljava/lang/String;II)V");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        midOnProgress = nullptr;
    }
    env->DeleteLocalRef(cbCls);

    JavaTokenSink sink;
    sink.midOnToken = midOnToken;
    sink.midOnProgress = midOnProgress;
    // GlobalRef：回调可能从引擎工作线程触发，LocalRef 在 JNI 帧弹出后即失效。
    sink.callbackRef = quro::llm::jni::retainCallback(env, callback);
    if (sink.callbackRef == nullptr) {
        LOGE("无法持有 Java 回调引用（GlobalRef 创建失败）");
        return JNI_FALSE;
    }

    quro::llm::GenParams params;
    params.maxTokens = static_cast<int>(maxTokens);
    // 采样参数已由 nativeSetSamplingParams 写进会话（引擎自持），这里不重复传。
    // 这与旧实现一致：maxTokens 走参数，temperature/topP/... 走会话状态。

    const quro::llm::Callbacks cbs = makeCallbacks(&sink, midOnProgress != nullptr);

    std::string err;
    const bool ok = engine->generate(jstringToString(env, prompt), params, cbs, &err);

    // 回调桥要显式释放（含 DeleteGlobalRef）。放在这里而不是 RAII 析构里，
    // 是为了保证在任何 return 路径上都执行 —— 目前只有下面这一处出口。
    const bool javaThrew = sink.javaThrew;
    const std::string thrownDetail = sink.thrownDetail;
    quro::llm::jni::releaseCallback(sink.callbackRef);
    sink.callbackRef = nullptr;

    if (!ok) {
        LOGE("nativeGenerateStream 失败：%s", err.c_str());
        return JNI_FALSE;
    }

    // Java 侧抛异常导致的中止：生成内容仍然有效（返回 JNI_TRUE），
    // 但必须把原因记进 lastError —— 旧实现这里是静默的，表现为
    // "ok=true 且文本为空"，用户端看不出任何线索。
    if (javaThrew) {
        engine->setLastErrorForced(thrownDetail);
    }
    return JNI_TRUE;
}

// ═════════════════════════════════════════════════════════════════════════
// L2 注册表：符号隔离
// ═════════════════════════════════════════════════════════════════════════
// 为什么需要这张表：
//   version script 把本 .so 的导出表收敛到只剩 JNI_OnLoad，
//   于是 JVM「按符号名查找 native 方法」的路径不再可用（符号已变 local）。
//   改成在 JNI_OnLoad 里显式 RegisterNatives 给出函数指针。
//
// 签名来自 javap -s（JVM 自己算出的描述符），不是人工推断。
// 对应 Kotlin 声明：com.ai.assistance.llama.LlamaNative
namespace {

const JNINativeMethod kLlamaNativeMethods[] = {
    {"nativeIsAvailable", "()Z",
     reinterpret_cast<void*>(Java_com_ai_assistance_llama_LlamaNative_nativeIsAvailable)},
    {"nativeGetUnavailableReason", "()Ljava/lang/String;",
     reinterpret_cast<void*>(Java_com_ai_assistance_llama_LlamaNative_nativeGetUnavailableReason)},
    {"nativeCreateSession", "(Ljava/lang/String;IIIIIZZZZI)J",
     reinterpret_cast<void*>(Java_com_ai_assistance_llama_LlamaNative_nativeCreateSession)},
    {"nativeReleaseSession", "(J)V",
     reinterpret_cast<void*>(Java_com_ai_assistance_llama_LlamaNative_nativeReleaseSession)},
    {"nativeCancel", "(J)V",
     reinterpret_cast<void*>(Java_com_ai_assistance_llama_LlamaNative_nativeCancel)},
    {"nativeResetKv", "(J)V",
     reinterpret_cast<void*>(Java_com_ai_assistance_llama_LlamaNative_nativeResetKv)},
    {"nativeCountTokens", "(JLjava/lang/String;)I",
     reinterpret_cast<void*>(Java_com_ai_assistance_llama_LlamaNative_nativeCountTokens)},
    {"nativeSetSamplingParams", "(JFFIFFFI)Z",
     reinterpret_cast<void*>(Java_com_ai_assistance_llama_LlamaNative_nativeSetSamplingParams)},
    {"nativeApplyChatTemplate", "(J[Ljava/lang/String;[Ljava/lang/String;Z)Ljava/lang/String;",
     reinterpret_cast<void*>(Java_com_ai_assistance_llama_LlamaNative_nativeApplyChatTemplate)},
    {"nativeApplyStructuredChatTemplate", "(JLjava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;",
     reinterpret_cast<void*>(Java_com_ai_assistance_llama_LlamaNative_nativeApplyStructuredChatTemplate)},
    {"nativeGenerateStream", "(JLjava/lang/String;ILcom/ai/assistance/llama/LlamaNative$GenerationCallback;)Z",
     reinterpret_cast<void*>(Java_com_ai_assistance_llama_LlamaNative_nativeGenerateStream)},
    {"nativeClearToolCallGrammar", "(J)Z",
     reinterpret_cast<void*>(Java_com_ai_assistance_llama_LlamaNative_nativeClearToolCallGrammar)},
    {"nativeParseToolCallResponse", "(JLjava/lang/String;)Ljava/lang/String;",
     reinterpret_cast<void*>(Java_com_ai_assistance_llama_LlamaNative_nativeParseToolCallResponse)},
    {"nativeGetLastError", "(J)Ljava/lang/String;",
     reinterpret_cast<void*>(Java_com_ai_assistance_llama_LlamaNative_nativeGetLastError)},
};

constexpr int kLlamaNativeCount = 14;

}  // namespace

namespace quro {
namespace llm {
namespace jni {

/// 注册本 .so 内的一个 native 类。返回是否全部成功。
bool register_LlamaNative(JNIEnv* env) {
    std::string err;
    if (!registerNatives(env, "com/ai/assistance/llama/LlamaNative", kLlamaNativeMethods,
                         kLlamaNativeCount, &err)) {
        __android_log_print(ANDROID_LOG_ERROR, "QuroLlm.Jni",
                            "注册 com.ai.assistance.llama.LlamaNative 失败：%s", err.c_str());
        return false;
    }
    return true;
}

}  // namespace jni
}  // namespace llm
}  // namespace quro

// 本 .so 的注册入口。同 .so 内其他 TU 的注册函数在此统一调用。
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void) reserved;

    // 记住 JavaVM：推理在引擎工作线程上跑，回调时需要用它在那个线程 attach。
    quro::llm::jni::setJavaVm(vm);

    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, "QuroLlm.Jni", "JNI_OnLoad: GetEnv 失败");
        return JNI_ERR;
    }

    // ① 把 LlamaEngine 注册进 L3 引擎工厂。
    //    这一步是"L3 不再是死代码"的判据：旧构架里 registerEngine 调用点为零，
    //    createEngineForModel 永远返回「引擎未注册」。现在它真的有实现可路由。
    //    放在 RegisterNatives **之前**：引擎注册失败只是少一条路由能力，
    //    而 native 方法注册失败会让整个 JNI 类不可用，后者的日志要更靠前可见。
    const bool engineRegistered = quro::llm::registry::registerLlamaEngine();

    // ② 注册 native 方法表。
    const bool allOk = quro::llm::jni::register_LlamaNative(env);

    // 注册失败**不**让 .so 加载失败：加载失败会让整个本地推理链路
    // 直接抛 UnsatisfiedLinkError，连诊断信息都拿不到。
    // 这里放行并由上层在首次调用时给出可读错误。
    if (!engineRegistered) {
        __android_log_print(ANDROID_LOG_ERROR, "QuroLlm.Jni",
                            "JNI_OnLoad: LlamaEngine 未注册进引擎工厂（路由不可用，"
                            "直连路径不受影响）");
    }
    if (!allOk) {
        __android_log_print(ANDROID_LOG_ERROR, "QuroLlm.Jni",
                            "JNI_OnLoad: 部分 native 方法注册失败，本地推理将不可用");
    }
    return JNI_VERSION_1_6;
}
