// =============================================================================
// L2 · JNI 桥接层（MNN）—— **薄封装**
// =============================================================================
// 架构图对本层的定义（逐字）：
//   「L2 JNI 桥接层：薄封装，**只传 opaque 句柄（jlong）**，回调上行」
//
// 本文件的职责**只有**四件事：
//   ① 解包 JNI 参数（jstring / jobject(java.util.List<kotlin.Pair>) / jobject 回调）
//   ② 通过 jlong 句柄 → 在句柄表里取出 MnnEngine*（拿不到就返回友好错误，不崩）
//   ③ 调引擎（L3/L4）
//   ④ 把结果与回调转回 JNI
//
// **本文件绝不 include mnn_engine_impl.h / <llm/llm.hpp> / MNN 的任何头。**
//   那是分层的硬边界：MNN 的 Llm 是裸指针、LlmContext 里含 std::vector<int>，
//   一旦摊在 JNI 边界上就要求两侧 STL/分配器/ABI 完全一致 —— 任何一处不匹配
//   就是 SIGABRT。跨边界的只有：jlong 句柄、基础类型、UTF-8 的 std::string。
//
// 旧构架对照（这次真删了什么）：
//   旧 mnnllmnative.cpp 里，`parseChatHistory` 用 FindClass("kotlin/Pair") +
//   GetMethodID("getFirst") 逐项反射；cancel / lastError / audio 三张
//   **以裸 Llm* 为键的全局 map**；流式回调桥（JavaVM + GlobalRef + streambuf）
//   与 MNN 调用交织在同一个函数体。现在：
//     · 引擎状态 → 引擎实例字段（句柄表保证句柄与实例一一对应）
//     · 流式桥   → 引擎侧只认 Callbacks，JNI 侧只认 Java 方法
//     · 反射解析 → 留在本文件（它本来就是 JNI 的活）
// =============================================================================

#include <jni.h>

#include <android/log.h>

#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "mnn_engine.h"          // L3/L4 契约（不含任何 MNN 头）
#include "quro/engine.h"
#include "quro/engine_types.h"
#include "quro/handle_registry.h"
#include "quro/jni_support.h"

// 日志 TAG 与引擎侧保持一致，用户抓的是 `logcat -s MNNLlmNative`。
#define TAG "MNNLlmNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

using quro::llm::MnnEngine;

using ChatMessages = MnnEngine::ChatMessages;

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
/// 不认标准的 4 字节序列。模型吐出的 emoji（U+1F600 一类）会被它判为非法并塞进
/// 0xFFFD，用户看到一片 �。
/// 旧实现全程用 NewStringUTF，但引擎侧已经做了 UTF-8 跨 token 边界缓冲 ——
/// 缓冲只保证"不切断字符"，3/4 字节字符走到 JNI 边界照样被 NewStringUTF 打死。
/// 所以这是一个**有意的修正**（不是搬家时的顺手改动）：手动解码成 UTF-16
/// （含代理对）再走 NewString(jchar*)，这是唯一对所有合法 UTF-8 都正确的路径。
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

// ═════════════════════════════════════════════════════════════════════════
// java.util.List<kotlin.Pair<String, String>> → ChatMessages
// ═════════════════════════════════════════════════════════════════════════
// 这段反射解析**属于 L2**（它就是"解 JNI 参数"本身），所以留在本文件。
// 注意每次调用都要重新 FindClass —— LocalRef 在 JNI 帧弹出后失效，
// 想缓存必须用 NewGlobalRef（旧实现没缓存，这里保持原样，避免引入全局引用泄漏）。
ChatMessages parseChatHistory(JNIEnv* env, jobject jhistory) {
    ChatMessages history;
    if (jhistory == nullptr) return history;

    jclass listClass = env->FindClass("java/util/List");
    if (listClass == nullptr) {
        env->ExceptionClear();
        return history;
    }
    jmethodID sizeMethod = env->GetMethodID(listClass, "size", "()I");
    jmethodID getMethod = env->GetMethodID(listClass, "get", "(I)Ljava/lang/Object;");

    jclass pairClass = env->FindClass("kotlin/Pair");
    if (pairClass == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(listClass);
        return history;
    }
    jmethodID getFirstMethod = env->GetMethodID(pairClass, "getFirst", "()Ljava/lang/Object;");
    jmethodID getSecondMethod = env->GetMethodID(pairClass, "getSecond", "()Ljava/lang/Object;");

    if (sizeMethod == nullptr || getMethod == nullptr ||
        getFirstMethod == nullptr || getSecondMethod == nullptr) {
        // 补一层防护：旧实现拿到 null jmethodID 就直接 CallIntMethod → JVM 层面崩。
        // 这里清异常并当空历史处理（上游会报"消息为空"，比崩溃可诊断得多）。
        env->ExceptionClear();
        env->DeleteLocalRef(listClass);
        env->DeleteLocalRef(pairClass);
        return history;
    }

    const jint listSize = env->CallIntMethod(jhistory, sizeMethod);
    for (jint i = 0; i < listSize; ++i) {
        jobject pairObj = env->CallObjectMethod(jhistory, getMethod, i);
        if (pairObj == nullptr) continue;

        jobject roleObj = env->CallObjectMethod(pairObj, getFirstMethod);
        jobject contentObj = env->CallObjectMethod(pairObj, getSecondMethod);

        if (roleObj != nullptr && contentObj != nullptr) {
            history.emplace_back(jstringToString(env, reinterpret_cast<jstring>(roleObj)),
                                 jstringToString(env, reinterpret_cast<jstring>(contentObj)));
        }

        if (roleObj) env->DeleteLocalRef(roleObj);
        if (contentObj) env->DeleteLocalRef(contentObj);
        env->DeleteLocalRef(pairObj);
    }

    env->DeleteLocalRef(listClass);
    env->DeleteLocalRef(pairClass);
    return history;
}

// ═════════════════════════════════════════════════════════════════════════
// 句柄
// ═════════════════════════════════════════════════════════════════════════
// 用 HandleRegistry 而不是 `reinterpret_cast<MnnEngine*>(handle)`：
//   旧实现把裸 Llm* 交给 Java 保管。MNN 销毁后地址会被下一个实例复用，
//   于是"上一轮的 cancel 标志 / 错误串 / 音频回调"会串到新会话上 ——
//   旧代码甚至专门写了 `clearLastError(llmPtr)` 并注释"指针会被复用，
//   残留的错误串会串味"来打补丁。句柄表用「槽位 + 代际号」从结构上消灭这个问题：
//   槽位复用后代际号 +1，旧句柄永久失效。

MnnEngine* engineOf(jlong handle) {
    return static_cast<MnnEngine*>(
        quro::llm::HandleRegistry::instance().get(handle, quro::llm::HandleKind::ENGINE));
}

#define REQUIRE_ENGINE(handle)                                              \
    MnnEngine* engine = engineOf(handle);                                   \
    if (engine == nullptr) {                                                \
        LOGE("无效或已释放的会话句柄：%lld", (long long) (handle));          \
        return;                                                             \
    }
#define REQUIRE_ENGINE_VAL(handle, val)                                     \
    MnnEngine* engine = engineOf(handle);                                   \
    if (engine == nullptr) {                                                \
        LOGE("无效或已释放的会话句柄：%lld", (long long) (handle));          \
        return (val);                                                       \
    }

// ═════════════════════════════════════════════════════════════════════════
// 音频回调（TTS）—— JNI 侧持有 Java 对象，引擎侧只拿到 std::function
// ═════════════════════════════════════════════════════════════════════════
// 为什么这张表留在 JNI 层而不是引擎里：
//   引擎不该知道 JNIEnv / GlobalRef / jmethodID 的存在（否则 PC 侧工具链无法复用）。
//   这里以**句柄**为键（不是裸指针），配合句柄表的代际校验，天然不会串味。
struct AudioHolder {
    JavaVM* jvm = nullptr;
    jobject callbackGlobalRef = nullptr;
    jmethodID onAudioDataMethod = nullptr;
};

std::mutex gAudioMutex;
std::map<int64_t, AudioHolder> gAudioHolders;  // key = 句柄

void releaseAudioHolder(JNIEnv* env, int64_t handle) {
    AudioHolder holder;
    bool has = false;
    {
        std::lock_guard<std::mutex> lock(gAudioMutex);
        auto it = gAudioHolders.find(handle);
        if (it != gAudioHolders.end()) {
            holder = it->second;
            gAudioHolders.erase(it);
            has = true;
        }
    }
    if (has && env != nullptr && holder.callbackGlobalRef != nullptr) {
        env->DeleteGlobalRef(holder.callbackGlobalRef);
    }
}

bool invokeAudioHolder(int64_t handle, const float* data, size_t size, bool isLastChunk) {
    AudioHolder holder;
    {
        std::lock_guard<std::mutex> lock(gAudioMutex);
        auto it = gAudioHolders.find(handle);
        if (it == gAudioHolders.end()) return false;
        holder = it->second;
    }
    if (holder.jvm == nullptr || holder.callbackGlobalRef == nullptr ||
        holder.onAudioDataMethod == nullptr) {
        return false;
    }

    // 走统一 RAII（旧实现手工 AttachCurrentThread/DetachCurrentThread，
    // 漏一条路径就会泄漏线程结构）。
    quro::llm::jni::Scope scope;
    JNIEnv* env = scope.env();
    if (env == nullptr) {
        LOGE("Failed to attach thread for audio callback");
        return false;
    }

    jfloatArray audioDataArray = env->NewFloatArray(static_cast<jsize>(size));
    if (audioDataArray == nullptr) return false;
    env->SetFloatArrayRegion(audioDataArray, 0, static_cast<jsize>(size), data);

    const jboolean keepGoing = env->CallBooleanMethod(holder.callbackGlobalRef,
                                                     holder.onAudioDataMethod, audioDataArray,
                                                     isLastChunk ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(audioDataArray);

    if (env->ExceptionCheck()) {
        // 旧实现用 ExceptionDescribe（打日志）。这里保留清异常 + 停止，不 Describe ——
        // Describe 会把整个栈打到 logcat，在音频每帧回调里等于刷屏。
        env->ExceptionClear();
        LOGE("Java audio callback threw; stopping waveform generation");
        return false;
    }
    return keepGoing == JNI_TRUE;
}

// ═════════════════════════════════════════════════════════════════════════
// onToken 回调桥
// ═════════════════════════════════════════════════════════════════════════
struct JavaTokenSink {
    void* callbackRef = nullptr;
    jmethodID midOnToken = nullptr;

    /// 思考段上行通道（`onThinking(String):Boolean`）。
    /// **可为 null**：Java 侧没实现该方法时保持 null，此时思考内容会带上
    /// `<think>…</think>` 包装走 onToken 回退 —— 见 sinkOnToken 里的说明。
    jmethodID midOnThinking = nullptr;

    bool javaRejected = false;
    bool javaThrew = false;
    std::string thrownDetail;
};

void sinkOnToken(void* user, const quro::llm::TokenChunk& chunk) {
    auto* sink = static_cast<JavaTokenSink*>(user);
    if (sink == nullptr || chunk.text == nullptr || chunk.len == 0) return;

    quro::llm::jni::Scope scope;
    JNIEnv* env = scope.env();
    if (env == nullptr) {
        sink->javaRejected = true;
        return;
    }
    jobject callbackObj = quro::llm::jni::callbackObject(sink->callbackRef);
    if (callbackObj == nullptr) {
        sink->javaRejected = true;
        return;
    }

    // ── 思考段路由（L4 分流器 → Java 上行）────────────────────────────────
    // 引擎侧已经把 `<think>` 标签吃掉，并把内容分成两个通道
    // （TokenChunk::isThinking）。所以这里必须**按通道分别上行**：
    //
    //   首选：Java 侧实现了 onThinking(String):Boolean → 思考内容走独立通道，
    //         上层直接送进"思考区"，不需要任何文本标签。
    //   回退：Java 侧没有该方法（旧实现 / 未同步升级）→ 把思考内容**包回**
    //         `<think>…</think>` 再走 onToken，让上层那套文本剥离器照常工作。
    //
    // 为什么回退也一定要包标签：**绝不能把思考原文裸发**。
    // 上层剥离器只认标签；一旦标签被引擎吃掉、它又拿不到 isThinking，
    // 思考过程就会**实时上屏** —— 这是最容易被忽略、但用户一眼就看得见的回归。
    std::string wrapped;
    const char* payload = chunk.text;
    size_t payloadLen = chunk.len;
    jmethodID mid = sink->midOnToken;
    if (chunk.isThinking) {
        if (sink->midOnThinking != nullptr) {
            mid = sink->midOnThinking;
        } else {
            wrapped.reserve(chunk.len + 16);
            wrapped.append("<think>");
            wrapped.append(chunk.text, chunk.len);
            wrapped.append("</think>");
            payload = wrapped.data();
            payloadLen = wrapped.size();
        }
    }

    jstring jtoken = bytesUtf8ToJstring(env, std::string(payload, payloadLen));
    if (jtoken == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }

    const jboolean keepGoing = env->CallBooleanMethod(callbackObj, mid, jtoken);
    env->DeleteLocalRef(jtoken);

    if (env->ExceptionCheck()) {
        // 必须清除异常标记，否则后续所有 JNI 调用都会失败。
        env->ExceptionClear();
        sink->javaThrew = true;
        sink->thrownDetail = "E_MNN_STREAM_THROW|Java 回调抛出异常，生成已中止";
        LOGE("Java callback threw exception; stopping generation");
        return;
    }
    if (!keepGoing) {
        sink->javaRejected = true;
    }
}

bool sinkShouldStop(void* user) {
    auto* sink = static_cast<JavaTokenSink*>(user);
    if (sink == nullptr) return false;
    return sink->javaRejected || sink->javaThrew;
}

quro::llm::Callbacks makeTokenCallbacks(JavaTokenSink* sink) {
    quro::llm::Callbacks cbs;
    cbs.user = sink;
    cbs.onToken = &sinkOnToken;
    cbs.onProgress = nullptr;   // MNN 侧没有 prefill 进度回调（旧实现也没有）
    cbs.shouldStop = &sinkShouldStop;
    return cbs;
}

/// 共用的流式入口：解析回调 → 调引擎 → 收尾。
/// 返回 false 表示"没跑起来"（此时 lastError 里已有原因）。
bool runStreamWithJavaCallback(JNIEnv* env, MnnEngine* engine, jobject callback,
                              const std::function<bool(const quro::llm::Callbacks&, std::string*)>& body) {
    jclass cbCls = env->GetObjectClass(callback);
    if (cbCls == nullptr) {
        env->ExceptionClear();
        LOGE("无法解析生成回调对象");
        return false;
    }
    jmethodID midOnToken = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)Z");
    // 思考段上行通道，**可选**：Java 侧没有 onThinking 时静默降级
    // （思考内容带上 <think> 包装走 onToken），而不是让整次生成失败。
    // 必须在 DeleteLocalRef(cbCls) **之前**取，否则 cbCls 已失效。
    jmethodID midOnThinking = env->GetMethodID(cbCls, "onThinking", "(Ljava/lang/String;)Z");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        midOnThinking = nullptr;
    }
    env->DeleteLocalRef(cbCls);
    if (midOnToken == nullptr) {
        env->ExceptionClear();
        LOGE("回调缺少 onToken(Ljava/lang/String;)Z 方法");
        return false;
    }

    JavaTokenSink sink;
    sink.midOnToken = midOnToken;
    sink.midOnThinking = midOnThinking;
    sink.callbackRef = quro::llm::jni::retainCallback(env, callback);
    if (sink.callbackRef == nullptr) {
        LOGE("无法持有 Java 回调引用（GlobalRef 创建失败）");
        return false;
    }

    const quro::llm::Callbacks cbs = makeTokenCallbacks(&sink);
    std::string err;
    const bool ok = body(cbs, &err);

    const bool javaThrew = sink.javaThrew;
    const std::string thrownDetail = sink.thrownDetail;
    quro::llm::jni::releaseCallback(sink.callbackRef);
    sink.callbackRef = nullptr;

    if (!ok) {
        LOGE("stream generation failed: %s", err.c_str());
        return false;  // 引擎已把原因写进 lastError
    }
    // Java 侧抛异常导致的中止：已生成内容仍有效（返回 true），
    // 但把原因记进 lastError —— 旧实现这里是静默的，表现为"ok=true 且文本为空"。
    if (javaThrew) {
        engine->setLastErrorForced(thrownDetail);
    }
    return true;
}

}  // namespace

// ═════════════════════════════════════════════════════════════════════════
// JNI 入口（22 个，签名与 Kotlin 声明严格一致）
// ═════════════════════════════════════════════════════════════════════════

// ── L3 生命周期 ──────────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeCreateLlm(JNIEnv* env, jclass clazz,
                                                        jstring jconfigPath) {
    (void) clazz;
    const std::string configPath = jstringToString(env, jconfigPath);
    LOGD("Creating LLM from config: %s", configPath.c_str());

    auto engine = std::make_unique<MnnEngine>();
    std::string err;
    // 只创建，不加载：MNN 的 set_config 必须在 load 之前生效
    // （见 MnnEngine::createFromConfig 的说明）。
    if (!engine->createFromConfig(configPath, &err)) {
        LOGE("nativeCreateLlm: %s", err.c_str());
        return 0;
    }

    // 注册成功前**不** release：句柄表已满时 engine 仍由 unique_ptr 持有，
    // 离开作用域自动析构 → 已创建的 MNN 实例被正确销毁。
    const int64_t handle = quro::llm::HandleRegistry::instance().add(
        engine.get(), quro::llm::HandleKind::ENGINE, "mnn");
    if (handle == 0) {
        LOGE("nativeCreateLlm: 句柄表已满，无法注册会话（将销毁已创建的实例）");
        return 0;
    }
    engine.release();
    return static_cast<jlong>(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeLoadLlm(JNIEnv* env, jclass clazz, jlong llmPtr) {
    (void) env;
    (void) clazz;
    REQUIRE_ENGINE_VAL(llmPtr, JNI_FALSE);
    LOGD("Loading LLM model (handle=%lld)", (long long) llmPtr);

    // MNN 的 load 不吃配置（配置早已经 set_config 注入），传默认 LoadConfig 即可。
    quro::llm::LoadConfig cfg;
    cfg.modelPath = "";
    std::string err;
    const bool ok = engine->load(cfg, &err);
    if (!ok) LOGE("nativeLoadLlm: %s", err.c_str());
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeReleaseLlm(JNIEnv* env, jclass clazz,
                                                         jlong llmPtr) {
    (void) clazz;
    if (llmPtr == 0) return;

    // 先摘音频回调（内含 GlobalRef，必须在引擎销毁前放掉），
    // 再从句柄表移除并析构引擎（析构会 Llm::destroy 释放权重）。
    releaseAudioHolder(env, static_cast<int64_t>(llmPtr));

    // remove 校验代际号与类别 —— Kotlin 侧重复 close 时第二次拿到 nullptr，
    // 不会二次 delete。
    void* raw = quro::llm::HandleRegistry::instance().remove(llmPtr,
                                                             quro::llm::HandleKind::ENGINE);
    if (raw == nullptr) return;

    std::unique_ptr<MnnEngine> engine(static_cast<MnnEngine*>(raw));
    LOGI("LLM released successfully (handle=%lld)", (long long) llmPtr);
}

// ── 分词 ────────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jint JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeCountTokens(JNIEnv* env, jclass clazz, jlong llmPtr,
                                                          jstring jtext) {
    (void) clazz;
    REQUIRE_ENGINE_VAL(llmPtr, 0);
    std::vector<int32_t> tokens;
    std::string err;
    if (!engine->tokenize(jstringToString(env, jtext), &tokens, &err)) {
        LOGE("nativeCountTokens: %s", err.c_str());
        return 0;
    }
    return static_cast<jint>(tokens.size());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeTokenize(JNIEnv* env, jclass clazz, jlong llmPtr,
                                                       jstring jtext) {
    (void) clazz;
    REQUIRE_ENGINE_VAL(llmPtr, nullptr);
    std::vector<int32_t> tokens;
    std::string err;
    if (!engine->tokenize(jstringToString(env, jtext), &tokens, &err)) {
        LOGE("nativeTokenize: %s", err.c_str());
        return nullptr;
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(tokens.size()));
    if (result != nullptr) {
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(tokens.size()), tokens.data());
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeDetokenize(JNIEnv* env, jclass clazz, jlong llmPtr,
                                                         jint token) {
    (void) clazz;
    REQUIRE_ENGINE_VAL(llmPtr, nullptr);
    std::string text;
    std::string err;
    if (!engine->detokenize({static_cast<int32_t>(token)}, &text, &err)) {
        LOGE("nativeDetokenize: %s", err.c_str());
        return nullptr;
    }
    return bytesUtf8ToJstring(env, text);
}

// ── 生成 ────────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeGenerate(JNIEnv* env, jclass clazz, jlong llmPtr,
                                                       jstring jprompt, jint maxTokens,
                                                       jobject callback) {
    (void) clazz;
    (void) callback;  // 旧实现的这个入口也不使用 callback（整段返回）
    REQUIRE_ENGINE_VAL(llmPtr, nullptr);
    std::string out;
    std::string err;
    if (!engine->generateToString(jstringToString(env, jprompt), static_cast<int32_t>(maxTokens),
                                  &out, &err)) {
        LOGE("nativeGenerate: %s", err.c_str());
        return nullptr;
    }
    return bytesUtf8ToJstring(env, out);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeGenerateStream(
    JNIEnv* env, jclass clazz, jlong llmPtr, jobject jhistory, jint maxTokens, jobject callback) {
    (void) clazz;
    if (callback == nullptr) return JNI_FALSE;
    REQUIRE_ENGINE_VAL(llmPtr, JNI_FALSE);

    const ChatMessages history = parseChatHistory(env, jhistory);
    LOGD("Starting stream generation with %zu history messages", history.size());

    quro::llm::GenParams params;
    params.maxTokens = static_cast<int>(maxTokens);

    const bool ok = runStreamWithJavaCallback(
        env, engine, callback,
        [&](const quro::llm::Callbacks& cbs, std::string* err) {
            return engine->generateFromHistory(history, params, cbs, err);
        });
    if (ok) LOGI("Direct history stream generation completed");
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeGenerateStreamStructured(
    JNIEnv* env, jclass clazz, jlong llmPtr, jstring jmessagesJson, jstring jtoolsJson,
    jint maxTokens, jobject callback) {
    (void) clazz;
    if (callback == nullptr) return JNI_FALSE;
    REQUIRE_ENGINE_VAL(llmPtr, JNI_FALSE);

    const std::string messagesJson = jstringToString(env, jmessagesJson);
    const std::string toolsJson = jstringToString(env, jtoolsJson);

    quro::llm::GenParams params;
    params.maxTokens = static_cast<int>(maxTokens);

    const bool ok = runStreamWithJavaCallback(
        env, engine, callback,
        [&](const quro::llm::Callbacks& cbs, std::string* err) {
            return engine->generateStructured(messagesJson, toolsJson, params, cbs, err);
        });
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ── 聊天模板 ────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeApplyChatTemplate(JNIEnv* env, jclass clazz,
                                                               jlong llmPtr,
                                                               jstring juserContent) {
    (void) clazz;
    REQUIRE_ENGINE_VAL(llmPtr, nullptr);
    std::string out;
    std::string err;
    if (!engine->applyChatTemplate(jstringToString(env, juserContent), &out, &err)) {
        LOGE("nativeApplyChatTemplate: %s", err.c_str());
        return nullptr;
    }
    return bytesUtf8ToJstring(env, out);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeApplyChatTemplateWithHistory(JNIEnv* env,
                                                                          jclass clazz,
                                                                          jlong llmPtr,
                                                                          jobject jhistory) {
    (void) clazz;
    REQUIRE_ENGINE_VAL(llmPtr, nullptr);
    std::string out;
    std::string err;
    if (!engine->applyChatTemplateWithHistory(parseChatHistory(env, jhistory), &out, &err)) {
        LOGE("nativeApplyChatTemplateWithHistory: %s", err.c_str());
        return nullptr;
    }
    return bytesUtf8ToJstring(env, out);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeApplyChatTemplateWithStructuredMessages(
    JNIEnv* env, jclass clazz, jlong llmPtr, jstring jmessagesJson, jstring jtoolsJson) {
    (void) clazz;
    REQUIRE_ENGINE_VAL(llmPtr, nullptr);
    std::string out;
    std::string err;
    if (!engine->applyChatTemplateWithStructuredMessages(jstringToString(env, jmessagesJson),
                                                        jstringToString(env, jtoolsJson), &out,
                                                        &err)) {
        // 错误码已由引擎写进 lastError（上层用它做本地化）。
        LOGE("nativeApplyChatTemplateWithStructuredMessages: %s", err.c_str());
        return nullptr;
    }
    return bytesUtf8ToJstring(env, out);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeCountTokensWithHistory(JNIEnv* env, jclass clazz,
                                                                    jlong llmPtr,
                                                                    jobject jhistory) {
    (void) clazz;
    REQUIRE_ENGINE_VAL(llmPtr, 0);
    std::string err;
    const int32_t n = engine->countTokensWithHistory(parseChatHistory(env, jhistory), &err);
    if (n < 0) {
        LOGE("nativeCountTokensWithHistory: %s", err.c_str());
        return 0;
    }
    return static_cast<jint>(n);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeCountTokensWithStructuredMessages(
    JNIEnv* env, jclass clazz, jlong llmPtr, jstring jmessagesJson, jstring jtoolsJson) {
    (void) clazz;
    REQUIRE_ENGINE_VAL(llmPtr, 0);
    std::string err;
    const int32_t n = engine->countTokensWithStructuredMessages(
        jstringToString(env, jmessagesJson), jstringToString(env, jtoolsJson), &err);
    if (n < 0) {
        LOGE("nativeCountTokensWithStructuredMessages: %s", err.c_str());
        return 0;
    }
    return static_cast<jint>(n);
}

// ── 配置 / 上下文 / 复位 / 取消 / 错误 ───────────────────────────────────

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeDumpConfig(JNIEnv* env, jclass clazz, jlong llmPtr) {
    (void) clazz;
    REQUIRE_ENGINE_VAL(llmPtr, nullptr);
    std::string out;
    std::string err;
    if (!engine->dumpConfig(&out, &err)) {
        LOGE("nativeDumpConfig: %s", err.c_str());
        return nullptr;
    }
    return bytesUtf8ToJstring(env, out);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeGetContextInfo(JNIEnv* env, jclass clazz,
                                                            jlong llmPtr) {
    (void) clazz;
    REQUIRE_ENGINE_VAL(llmPtr, nullptr);
    std::string out;
    std::string err;
    if (!engine->contextInfoJson(&out, &err)) {
        LOGE("nativeGetContextInfo: %s", err.c_str());
        return nullptr;
    }
    return bytesUtf8ToJstring(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeReset(JNIEnv* env, jclass clazz, jlong llmPtr) {
    (void) env;
    (void) clazz;
    REQUIRE_ENGINE(llmPtr);
    std::string err;
    if (!engine->resetKv(&err)) {
        LOGE("nativeReset: %s", err.c_str());
        return;
    }
    LOGD("LLM reset successfully");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeSetConfig(JNIEnv* env, jclass clazz, jlong llmPtr,
                                                        jstring jconfigJson) {
    (void) clazz;
    REQUIRE_ENGINE_VAL(llmPtr, JNI_FALSE);
    std::string err;
    const bool ok = engine->setConfig(jstringToString(env, jconfigJson), &err);
    if (!ok) LOGE("nativeSetConfig: %s", err.c_str());
    return ok ? JNI_TRUE : JNI_FALSE;
}

namespace {

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
    (void) env;
    (void) clazz;
    REQUIRE_ENGINE(llmPtr);
    LOGD("Cancelling generation (handle=%lld)", (long long) llmPtr);
    engine->cancel();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeGetLastError(JNIEnv* env, jclass clazz,
                                                          jlong llmPtr) {
    (void) clazz;
    REQUIRE_ENGINE_VAL(llmPtr, nullptr);
    // take 语义：读后即清，避免下一轮读到上一轮的残留。
    const std::string err = engine->takeLastError();
    if (err.empty()) return nullptr;
    return bytesUtf8ToJstring(env, err);
}

// ── 音频（TTS）───────────────────────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeSetAudioDataCallback(JNIEnv* env, jclass clazz,
                                                                   jlong llmPtr, jobject callback) {
    (void) clazz;
    REQUIRE_ENGINE_VAL(llmPtr, JNI_FALSE);

    const int64_t handle = static_cast<int64_t>(llmPtr);

    // 先摘旧的（含 DeleteGlobalRef），再按需要装新的。
    releaseAudioHolder(env, handle);

    if (callback == nullptr) {
        engine->setWavformCallback(nullptr);
        return JNI_TRUE;
    }

    JavaVM* jvm = nullptr;
    if (env->GetJavaVM(&jvm) != JNI_OK || jvm == nullptr) {
        LOGE("Failed to get JavaVM for audio callback");
        return JNI_FALSE;
    }

    jclass callbackClass = env->GetObjectClass(callback);
    if (callbackClass == nullptr) {
        env->ExceptionClear();
        LOGE("Failed to resolve audio callback class");
        return JNI_FALSE;
    }
    jmethodID onAudioDataMethod = env->GetMethodID(callbackClass, "onAudioData", "([FZ)Z");
    env->DeleteLocalRef(callbackClass);
    if (onAudioDataMethod == nullptr) {
        env->ExceptionClear();
        LOGE("Failed to find onAudioData method in audio callback");
        return JNI_FALSE;
    }

    jobject callbackGlobalRef = env->NewGlobalRef(callback);
    if (callbackGlobalRef == nullptr) {
        LOGE("Failed to create global reference for audio callback");
        return JNI_FALSE;
    }

    {
        std::lock_guard<std::mutex> lock(gAudioMutex);
        gAudioHolders[handle] = AudioHolder{jvm, callbackGlobalRef, onAudioDataMethod};
    }

    // 引擎侧只拿到一个不透明的 std::function —— 它不知道 JNI 的存在。
    engine->setWavformCallback([handle](const float* data, size_t size, bool isLastChunk) {
        return invokeAudioHolder(handle, data, size, isLastChunk);
    });
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeGenerateWavform(JNIEnv* env, jclass clazz,
                                                              jlong llmPtr) {
    (void) env;
    (void) clazz;
    REQUIRE_ENGINE_VAL(llmPtr, JNI_FALSE);
    std::string err;
    const bool ok = engine->generateWaveform(&err);
    if (!ok) LOGE("nativeGenerateWavform: %s", err.c_str());
    return ok ? JNI_TRUE : JNI_FALSE;
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
// 对应 Kotlin 声明：com.ai.assistance.mnn.MNNLlmNative（22 个方法）
namespace {

const JNINativeMethod kMNNLlmNativeMethods[] = {
    {"nativeCreateLlm", "(Ljava/lang/String;)J",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeCreateLlm)},
    {"nativeLoadLlm", "(J)Z",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeLoadLlm)},
    {"nativeReleaseLlm", "(J)V",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeReleaseLlm)},
    {"nativeTokenize", "(JLjava/lang/String;)[I",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeTokenize)},
    {"nativeDetokenize", "(JI)Ljava/lang/String;",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeDetokenize)},
    {"nativeCountTokens", "(JLjava/lang/String;)I",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeCountTokens)},
    {"nativeGenerate", "(JLjava/lang/String;ILcom/ai/assistance/mnn/MNNLlmNative$GenerationCallback;)Ljava/lang/String;",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeGenerate)},
    {"nativeGenerateStream", "(JLjava/util/List;ILcom/ai/assistance/mnn/MNNLlmNative$GenerationCallback;)Z",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeGenerateStream)},
    {"nativeGenerateStreamStructured", "(JLjava/lang/String;Ljava/lang/String;ILcom/ai/assistance/mnn/MNNLlmNative$GenerationCallback;)Z",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeGenerateStreamStructured)},
    {"nativeApplyChatTemplateWithHistory", "(JLjava/util/List;)Ljava/lang/String;",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeApplyChatTemplateWithHistory)},
    {"nativeApplyChatTemplateWithStructuredMessages", "(JLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeApplyChatTemplateWithStructuredMessages)},
    {"nativeCountTokensWithHistory", "(JLjava/util/List;)I",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeCountTokensWithHistory)},
    {"nativeCountTokensWithStructuredMessages", "(JLjava/lang/String;Ljava/lang/String;)I",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeCountTokensWithStructuredMessages)},
    {"nativeDumpConfig", "(J)Ljava/lang/String;",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeDumpConfig)},
    {"nativeGetContextInfo", "(J)Ljava/lang/String;",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeGetContextInfo)},
    {"nativeApplyChatTemplate", "(JLjava/lang/String;)Ljava/lang/String;",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeApplyChatTemplate)},
    {"nativeReset", "(J)V",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeReset)},
    {"nativeSetConfig", "(JLjava/lang/String;)Z",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeSetConfig)},
    {"nativeSetAudioDataCallback", "(JLcom/ai/assistance/mnn/MNNLlmNative$AudioDataCallback;)Z",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeSetAudioDataCallback)},
    {"nativeGenerateWavform", "(J)Z",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeGenerateWavform)},
    {"nativeCancel", "(J)V",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeCancel)},
    {"nativeGetLastError", "(J)Ljava/lang/String;",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeGetLastError)},
    // 思考段真实标签注入：MNN 侧没有 llama 那种"模板 detector 自动给标签"的路径，
    // 由 Kotlin 的 MnnModelCapabilities 探测后传下来（见 nativeSetThinkMarkers）。
    {"nativeSetThinkMarkers", "(J[Ljava/lang/String;[Ljava/lang/String;)Z",
     reinterpret_cast<void*>(Java_com_ai_assistance_mnn_MNNLlmNative_nativeSetThinkMarkers)},
};

constexpr int kMNNLlmNativeCount = 23;

}  // namespace

namespace quro {
namespace llm {
namespace jni {

/// 注册本 .so 内的一个 native 类。返回是否全部成功。
bool register_MNNLlmNative(JNIEnv* env) {
    std::string err;
    if (!registerNatives(env, "com/ai/assistance/mnn/MNNLlmNative", kMNNLlmNativeMethods,
                         kMNNLlmNativeCount, &err)) {
        __android_log_print(ANDROID_LOG_ERROR, "QuroLlm.Jni",
                            "注册 com.ai.assistance.mnn.MNNLlmNative 失败：%s", err.c_str());
        return false;
    }
    return true;
}

}  // namespace jni
}  // namespace llm
}  // namespace quro

// 本 .so 的注册入口。同 .so 内其他 TU 的注册函数在此统一调用。
namespace quro {
namespace llm {
namespace jni {
bool register_MNNNetNative(JNIEnv* env);
bool register_MNNModuleNative(JNIEnv* env);
}  // namespace jni
}  // namespace llm
}  // namespace quro

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void) reserved;

    // 记住 JavaVM：推理在引擎工作线程上跑，回调时需要用它在那个线程 attach。
    quro::llm::jni::setJavaVm(vm);

    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, "QuroLlm.Jni", "JNI_OnLoad: GetEnv 失败");
        return JNI_ERR;
    }

    // ① 把 MnnEngine 注册进 L3 引擎工厂（旧构架下 registerEngine 调用点为零）。
    const bool engineRegistered = quro::llm::registry::registerMnnEngine();

    // ② 注册三个 native 类的方法表。
    bool allOk = quro::llm::jni::register_MNNLlmNative(env);
    allOk = quro::llm::jni::register_MNNNetNative(env) && allOk;
    allOk = quro::llm::jni::register_MNNModuleNative(env) && allOk;

    // 注册失败**不**让 .so 加载失败：加载失败会让整个本地推理链路
    // 直接抛 UnsatisfiedLinkError，连诊断信息都拿不到。
    // 这里放行并由上层在首次调用时给出可读错误。
    if (!engineRegistered) {
        __android_log_print(ANDROID_LOG_ERROR, "QuroLlm.Jni",
                            "JNI_OnLoad: MnnEngine 未注册进引擎工厂（路由不可用，"
                            "直连路径不受影响）");
    }
    if (!allOk) {
        __android_log_print(ANDROID_LOG_ERROR, "QuroLlm.Jni",
                            "JNI_OnLoad: 部分 native 方法注册失败，本地推理将不可用");
    }
    return JNI_VERSION_1_6;
}
