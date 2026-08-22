#include <jni.h>

#include <android/log.h>

#include <atomic>
#include <cstdint>
#include <cstdio>   // snprintf（SET_ERR 宏格式化错误原因）
#include <mutex>
#include <string>
#include <vector>

// Crash diagnostics: capture native aborts (SIGSEGV/SIGBUS/SIGABRT/SIGILL/SIGFPE)
// that Java try/catch and the UncaughtExceptionHandler cannot catch, and write a
// minimal tombstone (signal + fault address + PC) to Download/QuroAI_logs/ so the
// user can retrieve it without adb.
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <sys/stat.h>
#include <ucontext.h>
#include <unwind.h>
#include <dlfcn.h>

#if defined(OPERIT_HAS_LLAMA_CPP) && OPERIT_HAS_LLAMA_CPP
#include "chat.h"
#include "llama.h"
#include "nlohmann/json.hpp"
#include <cstdlib>
#include <ctime>
#include <algorithm>
#include <chrono>
#include <exception>
#include <memory>
#include <sstream>

struct ToolCallGrammarConfigNative {
    std::string grammar;
    bool lazy = false;
    std::vector<std::string> triggerPatterns;
    std::vector<llama_token> triggerTokens;
    std::string generationPrompt;
};
#endif

#define TAG "LlamaNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::string jstringToString(JNIEnv * env, jstring jstr) {
    if (jstr == nullptr) return "";
    const char * cstr = env->GetStringUTFChars(jstr, nullptr);
    std::string out(cstr);
    env->ReleaseStringUTFChars(jstr, cstr);
    return out;
}

#if defined(OPERIT_HAS_LLAMA_CPP) && OPERIT_HAS_LLAMA_CPP
static llama_sampler * createSamplerChain(
        const llama_vocab * vocab,
        float temperature,
        float topP,
        int32_t topK,
        int32_t penaltyLastN,
        float repeatPenalty,
        float frequencyPenalty,
        float presencePenalty,
        uint32_t seed,
        const ToolCallGrammarConfigNative * grammarConfig
) {
    if (topP < 0.0f) topP = 0.0f;
    if (topP > 1.0f) topP = 1.0f;
    if (topK < 0) topK = 0;
    if (penaltyLastN < -1) penaltyLastN = -1;
    if (repeatPenalty < 0.0f) repeatPenalty = 0.0f;

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * chain = llama_sampler_chain_init(sparams);
    if (!chain) return nullptr;

    llama_sampler_chain_add(chain, llama_sampler_init_penalties(
            penaltyLastN,
            repeatPenalty,
            frequencyPenalty,
            presencePenalty
    ));

    llama_sampler_chain_add(chain, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));

    if (grammarConfig != nullptr && !grammarConfig->grammar.empty()) {
        if (vocab == nullptr) {
            llama_sampler_free(chain);
            return nullptr;
        }

        llama_sampler * grammarSampler = nullptr;
        if (grammarConfig->lazy) {
            std::vector<const char *> triggerPatternsC;
            triggerPatternsC.reserve(grammarConfig->triggerPatterns.size());
            for (const auto & pattern : grammarConfig->triggerPatterns) {
                if (!pattern.empty()) {
                    triggerPatternsC.push_back(pattern.c_str());
                }
            }

            grammarSampler = llama_sampler_init_grammar_lazy_patterns(
                vocab,
                grammarConfig->grammar.c_str(),
                "root",
                triggerPatternsC.data(),
                triggerPatternsC.size(),
                grammarConfig->triggerTokens.data(),
                grammarConfig->triggerTokens.size()
            );
        } else {
            grammarSampler = llama_sampler_init_grammar(
                vocab,
                grammarConfig->grammar.c_str(),
                "root"
            );
        }

        if (!grammarSampler) {
            llama_sampler_free(chain);
            return nullptr;
        }

        llama_sampler_chain_add(chain, grammarSampler);
    }

    llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));

    return chain;
}
#endif

static jstring stringToJstring(JNIEnv * env, const std::string & str) {
    return env->NewStringUTF(str.c_str());
}

static jstring bytesUtf8ToJstring(JNIEnv * env, const std::string & bytes) {
    std::u16string out;
    out.reserve(bytes.size());

    const unsigned char * s = reinterpret_cast<const unsigned char *>(bytes.data());
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

    return env->NewString(reinterpret_cast<const jchar *>(out.data()), static_cast<jsize>(out.size()));
}

#if !(defined(OPERIT_HAS_LLAMA_CPP) && OPERIT_HAS_LLAMA_CPP)

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeIsAvailable(JNIEnv * env, jclass clazz) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeGetUnavailableReason(JNIEnv * env, jclass clazz) {
    const char * msg = "llama.cpp native backend is not built. Ensure CMake fetched llama.cpp and links target 'llama'.";
    return env->NewStringUTF(msg);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeCreateSession(
        JNIEnv * env,
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
        jboolean offloadKqv
) {
    (void) env;
    (void) clazz;
    (void) pathModel;
    (void) nThreads;
    (void) nCtx;
    (void) nBatch;
    (void) nUBatch;
    (void) nGpuLayers;
    (void) useMmap;
    (void) flashAttention;
    (void) kvUnified;
    (void) offloadKqv;
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeReleaseSession(JNIEnv * env, jclass clazz, jlong sessionPtr) {
    (void) env;
    (void) clazz;
    (void) sessionPtr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeCancel(JNIEnv * env, jclass clazz, jlong sessionPtr) {
    (void) env;
    (void) clazz;
    (void) sessionPtr;
}

// Plan A: 与真实分支（OPERIT_HAS_LLAMA_CPP）的 nativeResetKv 配对的空实现。
// 必须存在——否则未编入 llama.cpp 时 LlamaSession.resetContext() 会抛 UnsatisfiedLinkError。
extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeResetKv(JNIEnv * env, jclass clazz, jlong sessionPtr) {
    (void) env;
    (void) clazz;
    (void) sessionPtr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeCountTokens(JNIEnv * env, jclass clazz, jlong sessionPtr, jstring text) {
    (void) env;
    (void) clazz;
    (void) sessionPtr;
    (void) text;
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeSetSamplingParams(
        JNIEnv * env,
        jclass clazz,
        jlong sessionPtr,
        jfloat temperature,
        jfloat topP,
        jint topK,
        jfloat repetitionPenalty,
        jfloat frequencyPenalty,
        jfloat presencePenalty,
        jint penaltyLastN
) {
    (void) env;
    (void) clazz;
    (void) sessionPtr;
    (void) temperature;
    (void) topP;
    (void) topK;
    (void) repetitionPenalty;
    (void) frequencyPenalty;
    (void) presencePenalty;
    (void) penaltyLastN;
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeApplyChatTemplate(
        JNIEnv * env,
        jclass clazz,
        jlong sessionPtr,
        jobjectArray roles,
        jobjectArray contents,
        jboolean addAssistant
) {
    (void) clazz;
    (void) sessionPtr;
    (void) roles;
    (void) contents;
    (void) addAssistant;
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeApplyStructuredChatTemplate(
        JNIEnv * env,
        jclass clazz,
        jlong sessionPtr,
        jstring messagesJson,
        jstring toolsJson,
        jboolean addAssistant
) {
    (void) env;
    (void) clazz;
    (void) sessionPtr;
    (void) messagesJson;
    (void) toolsJson;
    (void) addAssistant;
    return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeGenerateStream(JNIEnv * env, jclass clazz, jlong sessionPtr, jstring prompt, jint maxTokens, jobject callback) {
    (void) env;
    (void) clazz;
    (void) sessionPtr;
    (void) prompt;
    (void) maxTokens;
    (void) callback;
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeSetToolCallGrammar(
        JNIEnv * env,
        jclass clazz,
        jlong sessionPtr,
        jstring grammar,
        jobjectArray triggerPatterns
) {
    (void) env;
    (void) clazz;
    (void) sessionPtr;
    (void) grammar;
    (void) triggerPatterns;
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeClearToolCallGrammar(JNIEnv * env, jclass clazz, jlong sessionPtr) {
    (void) env;
    (void) clazz;
    (void) sessionPtr;
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeParseToolCallResponse(JNIEnv * env, jclass clazz, jlong sessionPtr, jstring content) {
    (void) env;
    (void) clazz;
    (void) sessionPtr;
    (void) content;
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeGetLastError(JNIEnv * env, jclass clazz, jlong sessionPtr) {
    (void) clazz;
    (void) sessionPtr;
    return env->NewStringUTF("llama.cpp 运行时未编入本构建（fdroid 风味）");
}

#else

namespace {

struct SamplingParamsNative {
    float temperature = 1.0f;
    float topP = 1.0f;
    int32_t topK = 0;
    int32_t penaltyLastN = 64;
    float repeatPenalty = 1.0f;
    float frequencyPenalty = 0.0f;
    float presencePenalty = 0.0f;
    uint32_t seed = static_cast<uint32_t>(std::rand());
};

struct LlamaSessionNative {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * sampler = nullptr;
    common_chat_templates_ptr chatTemplates;
    SamplingParamsNative samplingParams;
    ToolCallGrammarConfigNative toolCallGrammar;
    common_chat_parser_params toolCallParserParams;
    bool hasToolCallParser = false;
    std::atomic_bool cancel{false};
    // Plan A: KV 前缀缓存状态。
    // kvPrefix : 上一轮生成结束后保留的 KV 前缀（= 上一轮最终 promptTokens）。
    // kvPast   : kvPrefix 的长度（即上一轮缓存的 KV 位置数）。
    // kvDirty  : true 表示缓存失效，下轮必须全量重算（失效条件见 nativeGenerateStream 内注释）。
    // 初始 kvDirty=true：首个请求尚无缓存，等价于全清。
    std::vector<llama_token> kvPrefix;
    int32_t kvPast = 0;
    bool kvDirty = true;
    // 🔎 最近一次失败的**人类可读原因**。
    // 背景：此前 native 的所有失败都只走 LOGE 进 logcat，用户端表现统一为"没反应/不回复"，
    // 排障必须依赖 adb 或翻 Download/QuroAI_logs —— 用户拿不到日志，我就只能靠猜，
    // 已经因此白跑了三轮修复。现在把原因回传 Java，直接显示在聊天气泡里。
    // 访问方：genLock 已把同一 session 的 generate 串行化，故不额外加锁。
    std::string lastError;
};

// 记录失败原因（同时打 LOGE，保留 logcat 链路）
#define SET_ERR(sess, ...)                                        \
    do {                                                          \
        char _errbuf[512];                                        \
        snprintf(_errbuf, sizeof(_errbuf), __VA_ARGS__);          \
        LOGE("%s", _errbuf);                                      \
        if ((sess) != nullptr) (sess)->lastError = _errbuf;       \
    } while (0)

// UTF-8 character byte-length from leading byte (0 = invalid/incomplete).
// Mirrors MNN's utf8CharLength (mnnllmnative.cpp:728-734) for UTF-8 boundary buffering:
// when a multi-byte CJK character spans two tokens, the streaming detokenize delta may
// end with an incomplete UTF-8 sequence. Without buffering, bytesUtf8ToJstring replaces
// those bytes with 0xFFFD (�), producing the garbled text users reported.
static inline int utf8CharLength(unsigned char byte) {
    if ((byte & 0x80) == 0) return 1;
    if ((byte & 0xE0) == 0xC0) return 2;
    if ((byte & 0xF0) == 0xE0) return 3;
    if ((byte & 0xF8) == 0xF0) return 4;
    return 0;
}

static std::once_flag gBackendInitOnce;

// ---- Native crash tombstone writer (async-signal-safe subset) -------------------
static void quroCrashWriteStr(int fd, const char * s) {
    if (s == nullptr) return;
    size_t n = strlen(s);
    if (n > 0) write(fd, s, n);
}

static void quroCrashWriteNum(int fd, long v) {
    char buf[24];
    int i = 0;
    if (v < 0) { buf[i++] = '-'; v = -v; }
    char tmp[20];
    int j = 0;
    if (v == 0) tmp[j++] = '0';
    while (v > 0) { tmp[j++] = (char)('0' + (v % 10)); v /= 10; }
    while (j > 0) buf[i++] = tmp[--j];
    write(fd, buf, i);
}

// Minimal signal handler: write a symbolized backtrace (signal + fault address +
// fault PC + a backtrace resolved to lib!function+offset) to Download/QuroAI_logs/
// so the user can retrieve it without adb, then re-raise so the OS still produces
// a real tombstone. Only best-effort async-signal-safe-ish calls are used:
// backtrace/backtrace_symbols_fd/dladdr are commonly used in crash handlers;
// write/open/close are async-signal-safe. No C++ streams / STL formatting.
static void quroNativeCrashSigHandler(int sig, siginfo_t * info, void * uctx) {
    mkdir("/sdcard/Download/QuroAI_logs", 0755);

    int fd = open("/sdcard/Download/QuroAI_logs/quro_native_crash.log",
                  O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd >= 0) {
        quroCrashWriteStr(fd, "[QuroNativeCrash] signal=");
        quroCrashWriteNum(fd, sig);
        quroCrashWriteStr(fd, " si_addr=");
        quroCrashWriteNum(fd, info ? reinterpret_cast<long>(info->si_addr) : 0L);

        // Faulting PC from the ucontext (the most useful single address).
        ucontext_t * uc = reinterpret_cast<ucontext_t *>(uctx);
        uintptr_t faultPc = 0;
#if defined(__aarch64__)
        faultPc = (uintptr_t) uc->uc_mcontext.pc;
#elif defined(__x86_64__)
        faultPc = (uintptr_t) uc->uc_mcontext.gregs[REG_RIP];
#elif defined(__arm__)
        faultPc = (uintptr_t) uc->uc_mcontext.arm_pc;
#endif
        quroCrashWriteStr(fd, " fault_pc=");
        quroCrashWriteNum(fd, (long) faultPc);
        quroCrashWriteStr(fd, " (use with addr2line / ndk-stack)\n");
        quroCrashWriteStr(fd, "[QuroNativeCrash] backtrace:\n");

        // bionic has no glibc backtrace(); use the Itanium _Unwind_Backtrace API
        // (available via <unwind.h>) and resolve each frame with dladdr so we get
        // lib!function+offset (ASLR-stripped), which maps directly to addr2line.
        struct QuroUnwindState {
            void * frames[32];
            int count;
        };
        QuroUnwindState st;
        st.count = 0;
        auto quroUnwindCb = +[](struct _Unwind_Context * ctx, void * arg) -> _Unwind_Reason_Code {
            auto * s = static_cast<QuroUnwindState *>(arg);
            if (s->count >= 32) return _URC_END_OF_STACK;
            uintptr_t ip = static_cast<uintptr_t>(_Unwind_GetIP(ctx));
            // _Unwind_GetIP returns the return address (next insn); subtract 1 so
            // the address falls inside the calling instruction for symbolization.
            s->frames[s->count++] = reinterpret_cast<void *>(ip ? ip - 1 : ip);
            return _URC_NO_REASON;
        };
        _Unwind_Backtrace(quroUnwindCb, &st);
        // Skip frame 0 (our signal handler) and frame 1 (the libc raise/abort
        // trampoline) so the first printed frame is the real llama.cpp call site.
        for (int i = 2; i < st.count; i++) {
            Dl_info dlinfo;
            memset(&dlinfo, 0, sizeof(dlinfo));
            dladdr(st.frames[i], &dlinfo);
            quroCrashWriteStr(fd, "  #");
            quroCrashWriteNum(fd, i);
            quroCrashWriteStr(fd, " ");
            if (dlinfo.dli_fname) {
                quroCrashWriteStr(fd, dlinfo.dli_fname);
            } else {
                quroCrashWriteStr(fd, "<unknown>");
            }
            quroCrashWriteStr(fd, "!");
            if (dlinfo.dli_sname) {
                quroCrashWriteStr(fd, dlinfo.dli_sname);
            } else {
                quroCrashWriteStr(fd, "??");
            }
            if (dlinfo.dli_fbase) {
                quroCrashWriteStr(fd, "+0x");
                quroCrashWriteNum(fd, (long)((uintptr_t)st.frames[i] - (uintptr_t)dlinfo.dli_fbase));
            } else {
                quroCrashWriteStr(fd, " @0x");
                quroCrashWriteNum(fd, (long)(uintptr_t)st.frames[i]);
            }
            if ((uintptr_t)st.frames[i] == faultPc) {
                quroCrashWriteStr(fd, "   <-- fault");
            }
            quroCrashWriteStr(fd, "\n");
        }
        close(fd);
    }

    // Restore default disposition and re-raise so the system still produces a tombstone.
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = SIG_DFL;
    sigaction(sig, &sa, nullptr);
    raise(sig);
}

static void installNativeCrashHandler() {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = quroNativeCrashSigHandler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGBUS, &sa, nullptr);
    sigaction(SIGABRT, &sa, nullptr);
    sigaction(SIGILL, &sa, nullptr);
    sigaction(SIGFPE, &sa, nullptr);
}

static void ensureBackendInit() {
    std::call_once(gBackendInitOnce, []() {
        installNativeCrashHandler();
        llama_backend_init();
        std::srand(static_cast<unsigned int>(std::time(nullptr)));
        LOGI("llama_backend_init done");
    });
}

static uint32_t positiveOrDefaultUInt(jint value, uint32_t defaultValue) {
    return value > 0 ? static_cast<uint32_t>(value) : defaultValue;
}

static int32_t positiveOrDefaultInt(jint value, int32_t defaultValue) {
    return value > 0 ? static_cast<int32_t>(value) : defaultValue;
}

static bool jbooleanToBool(jboolean value) {
    return value == JNI_TRUE;
}

static bool abortCallback(void * user_data) {
    auto * session = reinterpret_cast<LlamaSessionNative *>(user_data);
    return session != nullptr && session->cancel.load();
}

static bool rebuildSamplerForSession(LlamaSessionNative * session) {
    if (session == nullptr || session->model == nullptr || session->ctx == nullptr) {
        return false;
    }

    const llama_vocab * vocab = llama_model_get_vocab(session->model);

    llama_sampler * next = createSamplerChain(
        vocab,
        session->samplingParams.temperature,
        session->samplingParams.topP,
        session->samplingParams.topK,
        session->samplingParams.penaltyLastN,
        session->samplingParams.repeatPenalty,
        session->samplingParams.frequencyPenalty,
        session->samplingParams.presencePenalty,
        session->samplingParams.seed,
        &session->toolCallGrammar
    );

    if (!next) {
        return false;
    }

    if (session->sampler) {
        llama_sampler_free(session->sampler);
        session->sampler = nullptr;
    }

    session->sampler = next;
    return true;
}

static int32_t tokenizeText(const llama_vocab * vocab, const std::string & text, bool addSpecial) {
    if (vocab == nullptr) return 0;
    int32_t capacity = static_cast<int32_t>(text.size()) + 8;
    std::vector<llama_token> tokens;
    tokens.resize(std::max(16, capacity));

    int32_t n = llama_tokenize(
        vocab,
        text.c_str(),
        static_cast<int32_t>(text.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        addSpecial,
        true
    );

    if (n < 0) {
        tokens.resize(static_cast<size_t>(-n));
        n = llama_tokenize(
            vocab,
            text.c_str(),
            static_cast<int32_t>(text.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            addSpecial,
            true
        );
    }

    return std::max<int32_t>(0, n);
}

static std::vector<llama_token> tokenizeTextToVector(const llama_vocab * vocab, const std::string & text, bool addSpecial) {
    std::vector<llama_token> tokens;
    if (vocab == nullptr || text.empty()) {
        return tokens;
    }

    int32_t capacity = static_cast<int32_t>(text.size()) + 8;
    tokens.resize(std::max(16, capacity));

    int32_t n = llama_tokenize(
        vocab,
        text.c_str(),
        static_cast<int32_t>(text.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        addSpecial,
        true
    );

    if (n < 0) {
        tokens.resize(static_cast<size_t>(-n));
        n = llama_tokenize(
            vocab,
            text.c_str(),
            static_cast<int32_t>(text.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            addSpecial,
            true
        );
    }

    if (n <= 0) {
        tokens.clear();
        return tokens;
    }

    tokens.resize(static_cast<size_t>(n));
    return tokens;
}

static ToolCallGrammarConfigNative buildToolCallGrammarConfig(const common_chat_params & params) {
    ToolCallGrammarConfigNative config;
    config.grammar = params.grammar;
    config.lazy = params.grammar_lazy;
    config.generationPrompt = params.generation_prompt;

    for (const auto & trigger : params.grammar_triggers) {
        switch (trigger.type) {
            case COMMON_GRAMMAR_TRIGGER_TYPE_WORD:
                config.triggerPatterns.push_back(regex_escape(trigger.value));
                break;
            case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN:
                config.triggerPatterns.push_back(trigger.value);
                break;
            case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN_FULL:
                if (trigger.value.empty()) {
                    config.triggerPatterns.push_back("^$");
                } else {
                    std::string anchored;
                    if (trigger.value.front() != '^') {
                        anchored.push_back('^');
                    }
                    anchored += trigger.value;
                    if (trigger.value.back() != '$') {
                        anchored.push_back('$');
                    }
                    config.triggerPatterns.push_back(std::move(anchored));
                }
                break;
            case COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN:
                if (trigger.token != LLAMA_TOKEN_NULL) {
                    config.triggerTokens.push_back(trigger.token);
                }
                break;
            default:
                break;
        }
    }

    return config;
}

static void resetToolCallState(LlamaSessionNative * session) {
    if (session == nullptr) {
        return;
    }

    session->toolCallGrammar = ToolCallGrammarConfigNative{};
    session->toolCallParserParams = common_chat_parser_params();
    session->hasToolCallParser = false;
}

static bool initializeChatTemplatesForSession(LlamaSessionNative * session) {
    if (session == nullptr || session->model == nullptr) {
        return false;
    }

    try {
        session->chatTemplates = common_chat_templates_init(session->model, "");
        return static_cast<bool>(session->chatTemplates);
    } catch (const std::exception & e) {
        LOGE("Failed to initialize chat templates: %s", e.what());
        session->chatTemplates.reset();
        return false;
    } catch (...) {
        LOGE("Failed to initialize chat templates: unknown error");
        session->chatTemplates.reset();
        return false;
    }
}

static bool buildChatMessages(
        const std::vector<std::string> & roles,
        const std::vector<std::string> & contents,
        std::vector<common_chat_msg> & outMessages
) {
    if (roles.size() != contents.size()) {
        return false;
    }

    outMessages.clear();
    outMessages.reserve(roles.size());

    for (size_t i = 0; i < roles.size(); ++i) {
        common_chat_msg msg;
        msg.role = roles[i];
        msg.content = contents[i];
        outMessages.push_back(std::move(msg));
    }

    return true;
}

static bool tokenToPiece(const llama_vocab * vocab, llama_token token, std::string & out) {
    if (vocab == nullptr) return false;
    std::vector<char> buf;
    buf.resize(256);

    int32_t n = llama_token_to_piece(vocab, token, buf.data(), static_cast<int32_t>(buf.size()), 0, true);
    if (n < 0) {
        buf.resize(static_cast<size_t>(-n));
        n = llama_token_to_piece(vocab, token, buf.data(), static_cast<int32_t>(buf.size()), 0, true);
    }
    if (n <= 0) return false;
    out.assign(buf.data(), buf.data() + n);
    return true;
}

static void prefillToolCallGenerationPrompt(LlamaSessionNative * session) {
    if (session == nullptr || session->model == nullptr || session->sampler == nullptr) {
        return;
    }
    if (session->toolCallGrammar.grammar.empty() || session->toolCallGrammar.generationPrompt.empty()) {
        return;
    }

    const llama_vocab * vocab = llama_model_get_vocab(session->model);
    auto tokens = tokenizeTextToVector(vocab, session->toolCallGrammar.generationPrompt, false);
    for (const auto token : tokens) {
        llama_sampler_accept(session->sampler, token);
    }
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeIsAvailable(JNIEnv * env, jclass clazz) {
    (void) env;
    (void) clazz;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeGetUnavailableReason(JNIEnv * env, jclass clazz) {
    (void) clazz;
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeCreateSession(
        JNIEnv * env,
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
        jboolean offloadKqv
) {
    (void) clazz;
    ensureBackendInit();

    const std::string modelPath = jstringToString(env, pathModel);
    const int32_t effectiveThreads = positiveOrDefaultInt(nThreads, 4);
    const bool gpuOffloadSupported = llama_supports_gpu_offload();
    const int32_t requestedGpuLayers = std::max<int32_t>(0, static_cast<int32_t>(nGpuLayers));
    const int32_t effectiveGpuLayers = gpuOffloadSupported ? requestedGpuLayers : 0;
    const bool effectiveUseMmap = jbooleanToBool(useMmap);
    const bool effectiveFlashAttention = jbooleanToBool(flashAttention);
    const bool effectiveKvUnified = jbooleanToBool(kvUnified);
    const bool effectiveOffloadKqv =
        gpuOffloadSupported &&
        effectiveGpuLayers > 0 &&
        jbooleanToBool(offloadKqv);

    auto * session = new (std::nothrow) LlamaSessionNative();
    if (!session) {
        LOGE("Failed to allocate session");
        return 0;
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = effectiveGpuLayers;
    // llama.cpp now exposes model memory mapping through load_mode; the old
    // use_mmap/use_mlock fields no longer exist in llama_model_params.
    mparams.load_mode = effectiveUseMmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    // 【#1116】关闭 extra buffer types（ARM CPU repack）。
    // repack 会把量化权重重排成 Q4_0_4x8 / Q4_K_8x8 等布局，走独立的 NEON/i8mm
    // gemm kernel。这条路径依赖运行时 HWCAP 探测，在 big.LITTLE 机型上大小核能力
    // 不一致时可能选到当前核不支持的 kernel，真机崩溃点（ggml mul_mat）正落在这里。
    // 手机上 repack 的收益有限，稳定性优先，先关掉。
    mparams.use_extra_bufts = false;

    LOGI(
        "Creating llama session. model=%s threads=%d n_ctx=%d n_batch=%d n_ubatch=%d gpu_layers=%d use_mmap=%d flash_attn=%d kv_unified=%d offload_kqv=%d gpu_support=%d",
        modelPath.c_str(),
        effectiveThreads,
        static_cast<int>(nCtx),
        static_cast<int>(nBatch),
        static_cast<int>(nUBatch),
        effectiveGpuLayers,
        effectiveUseMmap ? 1 : 0,
        effectiveFlashAttention ? 1 : 0,
        effectiveKvUnified ? 1 : 0,
        effectiveOffloadKqv ? 1 : 0,
        gpuOffloadSupported ? 1 : 0
    );

    if (requestedGpuLayers > 0 && !gpuOffloadSupported) {
        LOGI("GPU layers requested but this build has no GPU offload backend; continuing on CPU");
    }

    session->model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (!session->model) {
        LOGE("Failed to load model from file");
        delete session;
        return 0;
    }

    if (!initializeChatTemplatesForSession(session)) {
        LOGE("Failed to initialize chat templates for model");
        llama_model_free(session->model);
        session->model = nullptr;
        delete session;
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = positiveOrDefaultUInt(nCtx, 0u);
    if (cparams.n_ctx == 0) {
        cparams.n_ctx = static_cast<uint32_t>(llama_model_n_ctx_train(session->model));
    }
    const uint32_t defaultBatch = std::min<uint32_t>(cparams.n_ctx, 512u);
    cparams.n_batch = std::min<uint32_t>(positiveOrDefaultUInt(nBatch, defaultBatch), cparams.n_ctx);
    cparams.n_ubatch = std::min<uint32_t>(positiveOrDefaultUInt(nUBatch, cparams.n_batch), cparams.n_batch);
    cparams.n_seq_max = 1;
    cparams.n_threads = effectiveThreads;
    cparams.n_threads_batch = effectiveThreads;
    cparams.flash_attn_type =
        effectiveFlashAttention ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    cparams.offload_kqv = effectiveOffloadKqv;
    cparams.kv_unified = effectiveKvUnified;
    // 计算类型（KV 缓存精度）：对齐 PocketPal 默认 F16。若不显式设置，llama.cpp 按模型原生类型
    // 可能落到 F32 —— KV 缓存体积翻倍，手机上 4096+ 上下文极易在 llama_init_from_model 阶段
    // OOM / 分配超时，表现为「模型加载不完整 / 卡在加载」。F16 是稳定且省内存的默认值。
    cparams.type_k = GGML_TYPE_F16;
    cparams.type_v = GGML_TYPE_F16;
    cparams.abort_callback = abortCallback;
    cparams.abort_callback_data = session;

    session->ctx = llama_init_from_model(session->model, cparams);
    if (!session->ctx) {
        LOGE("Failed to create context");
        llama_model_free(session->model);
        delete session;
        return 0;
    }

    llama_set_n_threads(session->ctx, effectiveThreads, effectiveThreads);

    session->samplingParams = SamplingParamsNative{};
    session->samplingParams.seed = static_cast<uint32_t>(std::rand());

    if (!rebuildSamplerForSession(session)) {
        LOGE("Failed to create sampler chain");
        llama_free(session->ctx);
        llama_model_free(session->model);
        delete session;
        return 0;
    }

    session->cancel.store(false);

    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeReleaseSession(JNIEnv * env, jclass clazz, jlong sessionPtr) {
    (void) env;
    (void) clazz;

    if (sessionPtr == 0) return;
    auto * session = reinterpret_cast<LlamaSessionNative *>(sessionPtr);

    if (session->sampler) {
        llama_sampler_free(session->sampler);
        session->sampler = nullptr;
    }

    if (session->ctx) {
        llama_free(session->ctx);
        session->ctx = nullptr;
    }

    session->chatTemplates.reset();

    if (session->model) {
        llama_model_free(session->model);
        session->model = nullptr;
    }

    delete session;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeCancel(JNIEnv * env, jclass clazz, jlong sessionPtr) {
    (void) env;
    (void) clazz;
    if (sessionPtr == 0) return;
    auto * session = reinterpret_cast<LlamaSessionNative *>(sessionPtr);
    session->cancel.store(true);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeCountTokens(JNIEnv * env, jclass clazz, jlong sessionPtr, jstring text) {
    (void) clazz;
    if (sessionPtr == 0) return 0;
    auto * session = reinterpret_cast<LlamaSessionNative *>(sessionPtr);
    if (!session->model) return 0;
    const llama_vocab * vocab = llama_model_get_vocab(session->model);
    const std::string input = jstringToString(env, text);
    return static_cast<jint>(tokenizeText(vocab, input, true));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeSetSamplingParams(
        JNIEnv * env,
        jclass clazz,
        jlong sessionPtr,
        jfloat temperature,
        jfloat topP,
        jint topK,
        jfloat repetitionPenalty,
        jfloat frequencyPenalty,
        jfloat presencePenalty,
        jint penaltyLastN
) {
    (void) env;
    (void) clazz;

    if (sessionPtr == 0) return JNI_FALSE;
    auto * session = reinterpret_cast<LlamaSessionNative *>(sessionPtr);
    if (!session->ctx || !session->model) return JNI_FALSE;

    session->samplingParams.temperature = (float) temperature;
    session->samplingParams.topP = (float) topP;
    session->samplingParams.topK = (int32_t) topK;
    session->samplingParams.penaltyLastN = (int32_t) penaltyLastN;
    session->samplingParams.repeatPenalty = (float) repetitionPenalty;
    session->samplingParams.frequencyPenalty = (float) frequencyPenalty;
    session->samplingParams.presencePenalty = (float) presencePenalty;
    session->samplingParams.seed = static_cast<uint32_t>(std::rand());

    if (!rebuildSamplerForSession(session)) {
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeSetToolCallGrammar(
        JNIEnv * env,
        jclass clazz,
        jlong sessionPtr,
        jstring grammar,
        jobjectArray triggerPatterns
) {
    (void) clazz;

    if (sessionPtr == 0 || grammar == nullptr) return JNI_FALSE;
    auto * session = reinterpret_cast<LlamaSessionNative *>(sessionPtr);
    if (!session->ctx || !session->model) return JNI_FALSE;

    const std::string grammarStr = jstringToString(env, grammar);
    if (grammarStr.empty()) {
        return JNI_FALSE;
    }

    std::vector<std::string> patterns;
    if (triggerPatterns != nullptr) {
        const jsize count = env->GetArrayLength(triggerPatterns);
        patterns.reserve(static_cast<size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            auto jPattern = reinterpret_cast<jstring>(env->GetObjectArrayElement(triggerPatterns, i));
            if (jPattern != nullptr) {
                const std::string pattern = jstringToString(env, jPattern);
                if (!pattern.empty()) {
                    patterns.push_back(pattern);
                }
                env->DeleteLocalRef(jPattern);
            }
        }
    }

    const ToolCallGrammarConfigNative previousConfig = session->toolCallGrammar;
    const common_chat_parser_params previousParserParams = session->toolCallParserParams;
    const bool previousHasParser = session->hasToolCallParser;

    session->toolCallGrammar.grammar = grammarStr;
    session->toolCallGrammar.lazy = !patterns.empty();
    session->toolCallGrammar.triggerPatterns = patterns;
    session->toolCallGrammar.triggerTokens.clear();
    session->toolCallGrammar.generationPrompt.clear();
    session->toolCallParserParams = common_chat_parser_params();
    session->hasToolCallParser = false;

    if (!rebuildSamplerForSession(session)) {
        session->toolCallGrammar = previousConfig;
        session->toolCallParserParams = previousParserParams;
        session->hasToolCallParser = previousHasParser;
        (void) rebuildSamplerForSession(session);
        LOGE("Failed to enable tool-call grammar");
        return JNI_FALSE;
    }

    LOGI("Tool-call grammar enabled. trigger_patterns=%zu", session->toolCallGrammar.triggerPatterns.size());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeClearToolCallGrammar(JNIEnv * env, jclass clazz, jlong sessionPtr) {
    (void) env;
    (void) clazz;

    if (sessionPtr == 0) return JNI_FALSE;
    auto * session = reinterpret_cast<LlamaSessionNative *>(sessionPtr);
    if (!session->ctx || !session->model) return JNI_FALSE;

    const ToolCallGrammarConfigNative previousConfig = session->toolCallGrammar;
    const common_chat_parser_params previousParserParams = session->toolCallParserParams;
    const bool previousHasParser = session->hasToolCallParser;

    resetToolCallState(session);

    if (!rebuildSamplerForSession(session)) {
        session->toolCallGrammar = previousConfig;
        session->toolCallParserParams = previousParserParams;
        session->hasToolCallParser = previousHasParser;
        (void) rebuildSamplerForSession(session);
        LOGE("Failed to clear tool-call grammar");
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeApplyChatTemplate(
    JNIEnv * env,
    jclass clazz,
    jlong sessionPtr,
    jobjectArray roles,
    jobjectArray contents,
    jboolean addAssistant
) {
    (void) clazz;

    if (sessionPtr == 0 || roles == nullptr || contents == nullptr) return nullptr;
    auto * session = reinterpret_cast<LlamaSessionNative *>(sessionPtr);
    if (!session->model || !session->chatTemplates) {
        SET_ERR(session, "模型未就绪或该 GGUF 未内嵌聊天模板（chat template 缺失）");
        return nullptr;
    }

    const jsize nRoles = env->GetArrayLength(roles);
    const jsize nContents = env->GetArrayLength(contents);
    if (nRoles <= 0 || nContents <= 0 || nRoles != nContents) {
        SET_ERR(session, "消息数组非法（roles=%d contents=%d）", (int) nRoles, (int) nContents);
        return nullptr;
    }

    std::vector<std::string> roleBuf;
    std::vector<std::string> contentBuf;
    roleBuf.reserve(static_cast<size_t>(nRoles));
    contentBuf.reserve(static_cast<size_t>(nRoles));

    for (jsize i = 0; i < nRoles; i++) {
        auto jrole = (jstring) env->GetObjectArrayElement(roles, i);
        auto jcontent = (jstring) env->GetObjectArrayElement(contents, i);
        roleBuf.push_back(jstringToString(env, jrole));
        contentBuf.push_back(jstringToString(env, jcontent));
        if (jrole) env->DeleteLocalRef(jrole);
        if (jcontent) env->DeleteLocalRef(jcontent);
    }

    std::vector<common_chat_msg> messages;
    if (!buildChatMessages(roleBuf, contentBuf, messages)) {
        SET_ERR(session, "构建聊天消息失败（角色/内容为空或非法）");
        return nullptr;
    }

    common_chat_templates_inputs inputs;
    inputs.messages = std::move(messages);
    inputs.add_generation_prompt = addAssistant == JNI_TRUE;
    inputs.use_jinja = true;

    try {
        const common_chat_params params = common_chat_templates_apply(session->chatTemplates.get(), inputs);
        if (params.prompt.empty()) {
            SET_ERR(session, "聊天模板渲染结果为空（模板与消息不匹配）");
            return nullptr;
        }
        return bytesUtf8ToJstring(env, params.prompt);
    } catch (const std::exception & e) {
        SET_ERR(session, "聊天模板渲染异常：%s", e.what());
        return nullptr;
    } catch (...) {
        SET_ERR(session, "聊天模板渲染异常（未知错误）");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeApplyStructuredChatTemplate(
    JNIEnv * env,
    jclass clazz,
    jlong sessionPtr,
    jstring messagesJson,
    jstring toolsJson,
    jboolean addAssistant
) {
    (void) clazz;

    if (sessionPtr == 0 || messagesJson == nullptr) return nullptr;
    auto * session = reinterpret_cast<LlamaSessionNative *>(sessionPtr);
    if (!session->model || !session->chatTemplates || !session->ctx) return nullptr;

    const std::string messagesStr = jstringToString(env, messagesJson);
    const std::string toolsStr = jstringToString(env, toolsJson);

    const ToolCallGrammarConfigNative previousConfig = session->toolCallGrammar;
    const common_chat_parser_params previousParserParams = session->toolCallParserParams;
    const bool previousHasParser = session->hasToolCallParser;

    try {
        const auto messages = nlohmann::ordered_json::parse(messagesStr);
        const auto tools = toolsStr.empty()
            ? nlohmann::ordered_json()
            : nlohmann::ordered_json::parse(toolsStr);

        common_chat_templates_inputs inputs;
        inputs.messages = common_chat_msgs_parse_oaicompat(messages);
        inputs.tools = common_chat_tools_parse_oaicompat(tools);
        inputs.tool_choice = inputs.tools.empty()
            ? COMMON_CHAT_TOOL_CHOICE_NONE
            : COMMON_CHAT_TOOL_CHOICE_AUTO;
        inputs.add_generation_prompt = addAssistant == JNI_TRUE;
        inputs.use_jinja = true;

        const common_chat_params params = common_chat_templates_apply(session->chatTemplates.get(), inputs);
        if (params.prompt.empty()) {
            return nullptr;
        }

        session->toolCallGrammar = buildToolCallGrammarConfig(params);
        session->toolCallParserParams = common_chat_parser_params(params);
        session->toolCallParserParams.parse_tool_calls = true;
        session->hasToolCallParser = !params.parser.empty();
        if (session->hasToolCallParser) {
            session->toolCallParserParams.parser.load(params.parser);
        }

        if (!rebuildSamplerForSession(session)) {
            session->toolCallGrammar = previousConfig;
            session->toolCallParserParams = previousParserParams;
            session->hasToolCallParser = previousHasParser;
            (void) rebuildSamplerForSession(session);
            LOGE("Failed to apply structured chat template sampler state");
            return nullptr;
        }

        return bytesUtf8ToJstring(env, params.prompt);
    } catch (const std::exception & e) {
        session->toolCallGrammar = previousConfig;
        session->toolCallParserParams = previousParserParams;
        session->hasToolCallParser = previousHasParser;
        (void) rebuildSamplerForSession(session);
        LOGE("Failed to apply structured chat template: %s", e.what());
        return nullptr;
    } catch (...) {
        session->toolCallGrammar = previousConfig;
        session->toolCallParserParams = previousParserParams;
        session->hasToolCallParser = previousHasParser;
        (void) rebuildSamplerForSession(session);
        LOGE("Failed to apply structured chat template: unknown error");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeParseToolCallResponse(
    JNIEnv * env,
    jclass clazz,
    jlong sessionPtr,
    jstring content
) {
    (void) clazz;

    if (sessionPtr == 0 || content == nullptr) return nullptr;
    auto * session = reinterpret_cast<LlamaSessionNative *>(sessionPtr);
    if (!session->hasToolCallParser) return nullptr;

    const std::string contentStr = jstringToString(env, content);
    if (contentStr.empty()) {
        return nullptr;
    }

    try {
        const common_chat_msg parsed = common_chat_parse(contentStr, false, session->toolCallParserParams);
        if (parsed.tool_calls.empty()) {
            return nullptr;
        }

        auto normalized = nlohmann::ordered_json::object();
        normalized["tool_calls"] = parsed.to_json_oaicompat()["tool_calls"];
        return bytesUtf8ToJstring(env, normalized.dump());
    } catch (const std::exception & e) {
        LOGE("Failed to parse tool-call response: %s", e.what());
        return nullptr;
    } catch (...) {
        LOGE("Failed to parse tool-call response: unknown error");
        return nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeGenerateStream(JNIEnv * env, jclass clazz, jlong sessionPtr, jstring prompt, jint maxTokens, jobject callback) {
    (void) clazz;

    if (sessionPtr == 0 || callback == nullptr) return JNI_FALSE;
    auto * session = reinterpret_cast<LlamaSessionNative *>(sessionPtr);
    if (!session->model || !session->ctx || !session->sampler) {
        if (session != nullptr) {
            session->lastError = "会话内部对象缺失（model/ctx/sampler 为空），模型可能已被卸载";
        }
        return JNI_FALSE;
    }

    // 每次生成开始先清空上轮错误，避免把旧原因误报给这一轮。
    session->lastError.clear();
    session->cancel.store(false);

    // Plan A: KV 不再每轮无条件清空。改为 tokenize + 头部截断之后（拿到最终 promptTokens）
    // 再做条件前缀复用（见下方 "Plan A: KV 前缀缓存" 段）：若本轮 prompt 是上一轮 prompt 的
    // 严格前缀扩展则续用 KV，否则按情况部分砍尾 / 全清。这里只做采样器复位——llama_sampler_reset
    // 是生成态、与 KV 无关，保持每轮无条件执行（team-lead 明确要求不要顺手条件化）。
    if (session->sampler) {
        llama_sampler_reset(session->sampler);
    }

    const std::string promptStr = jstringToString(env, prompt);
    const llama_vocab * vocab = llama_model_get_vocab(session->model);

    // Resolve callback method
    jclass cbCls = env->GetObjectClass(callback);
    if (!cbCls) {
        session->lastError = "无法解析生成回调对象";
        return JNI_FALSE;
    }
    jmethodID midOnToken = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)Z");
    if (!midOnToken) {
        session->lastError = "回调缺少 onToken 方法";
        return JNI_FALSE;
    }
    // 可选进度回调：prefill 阶段在手机 CPU 上可能耗时数十秒，期间一个 token 都吐不出来，
    // UI 全程空白 → 用户观感就是"卡死/不回复"。有它就能把"正在处理提示词 x/y"实时上屏。
    // 找不到该方法时静默降级（老版本 Java 侧接口无此方法），不影响生成。
    jmethodID midOnProgress = env->GetMethodID(cbCls, "onProgress", "(Ljava/lang/String;II)V");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        midOnProgress = nullptr;
    }

    // Tokenize prompt
    int32_t capacity = static_cast<int32_t>(promptStr.size()) + 8;
    std::vector<llama_token> promptTokens;
    promptTokens.resize(std::max(16, capacity));
    // ⚠️ BOS/EOS 处理（本地模型"答非所问 / 首 token 即 EOG"的根因之一）
    //
    // llama_tokenize 的 add_special 只有两种极端：
    //   true  → 无条件按词表元数据补 BOS。模板本身已含 <|begin_of_text|> 的模型（Llama-3 系）
    //           会得到**双 BOS**，模型会当成两段对话的拼接，轻则答非所问、重则立刻吐 EOG。
    //   false → 一律不补。模板不含 BOS 但词表要求 BOS 的模型（Llama-2 / Mistral / Gemma 系）
    //           会**丢 BOS**，首 token 分布严重跑偏。
    // 两个值都会在某类模型上出错，所以这里先用 false 分词，再按词表元数据
    // （llama_vocab_get_add_bos）**检查首 token 是否已经是 BOS**，缺了才补一个——
    // 既不双 BOS 也不丢 BOS，对 Qwen / Llama-2 / Llama-3 / Gemma 一致正确。
    // parse_special 恒为 true：模板里的 <|im_start|> 等必须解析成真正的特殊 token 而非字面量。
    int32_t nPrompt = llama_tokenize(
        vocab,
        promptStr.c_str(),
        static_cast<int32_t>(promptStr.size()),
        promptTokens.data(),
        static_cast<int32_t>(promptTokens.size()),
        false,
        true
    );
    if (nPrompt < 0) {
        promptTokens.resize(static_cast<size_t>(-nPrompt));
        nPrompt = llama_tokenize(
            vocab,
            promptStr.c_str(),
            static_cast<int32_t>(promptStr.size()),
            promptTokens.data(),
            static_cast<int32_t>(promptTokens.size()),
            false,
            true
        );
    }
    if (nPrompt <= 0) {
        SET_ERR(session, "提示词分词失败（tokenize 返回 %d，prompt 长度 %zu 字节）", (int) nPrompt, promptStr.size());
        return JNI_FALSE;
    }
    promptTokens.resize(static_cast<size_t>(nPrompt));

    // —— BOS 补齐（见上方注释）：词表要求 BOS 且模板没渲染出 BOS 时，手动在最前面补一个。
    const bool vocabWantsBos = llama_vocab_get_add_bos(vocab);
    const llama_token bosTok = llama_vocab_bos(vocab);
    bool bosInjected = false;
    if (vocabWantsBos && bosTok != LLAMA_TOKEN_NULL) {
        if (promptTokens.empty() || promptTokens.front() != bosTok) {
            promptTokens.insert(promptTokens.begin(), bosTok);
            bosInjected = true;
        }
    }
    LOGI("tokenize | nPrompt=%d | vocabWantsBos=%d | bos=%d | injected=%d | firstTok=%d | eos=%d",
         (int) promptTokens.size(), (int) vocabWantsBos, (int) bosTok, (int) bosInjected,
         promptTokens.empty() ? -1 : (int) promptTokens.front(), (int) llama_vocab_eos(vocab));

    // Avoid prompts that end with EOG/EOS tokens (some vocabs add EOS automatically when add_special=true)
    while (!promptTokens.empty() && llama_vocab_is_eog(vocab, promptTokens.back())) {
        promptTokens.pop_back();
    }
    if (promptTokens.empty()) {
        SET_ERR(session, "提示词分词后只剩结束符（EOG/EOS），聊天模板可能与该 GGUF 不匹配");
        return JNI_FALSE;
    }

    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(session->ctx));
    int maxNew = maxTokens <= 0 ? 256 : static_cast<int>(maxTokens);
    if (n_ctx > 0) {
        const int32_t reserveForGeneration = std::max<int32_t>(32, std::min<int32_t>(maxNew, n_ctx / 4));
        const int32_t maxPromptTokens = std::max<int32_t>(1, n_ctx - reserveForGeneration);
        if (static_cast<int32_t>(promptTokens.size()) > maxPromptTokens) {
            const size_t drop = promptTokens.size() - static_cast<size_t>(maxPromptTokens);
            const auto dropCount = static_cast<std::vector<llama_token>::difference_type>(drop);
            promptTokens.erase(promptTokens.begin(), promptTokens.begin() + dropCount);
            LOGI("Prompt truncated to fit context: kept=%d dropped=%zu n_ctx=%d", maxPromptTokens, drop, n_ctx);
        }
    }

    if (promptTokens.empty()) {
        SET_ERR(session, "提示词按上下文窗口截断后为空（n_ctx=%d 太小）", (int) n_ctx);
        return JNI_FALSE;
    }

    // ===================== Plan A: KV 前缀缓存（条件失效） =====================
    // 前缀失效的完整条件列表（任一满足 → 本轮回退为全量重算）：
    //   1) session->kvDirty == true（主动失效：nativeResetKv / resetContext()，或本/上轮
    //      生成失败、取消、abort 时已置位）；
    //   2) 尚无缓存：kvPrefix 为空（首个请求）；
    //   3) 本轮 prompt 不是缓存前缀的扩展：reuse == 0（prompt 与 kvPrefix 在首个 token 就分叉，
    //      或上下文头部被原生截断丢掉了前缀）；
    //   4) 用户取消：session->cancel 在 prefill 或生成阶段被置位；
    //   5) 任意 llama_decode 返回非 0（KV 槽不足 ret==1 / 中止 ret==2 / 致命 <-1），prefill 或生成；
    //   6) 工具轮：applyStructuredChatTemplate 渲染的 prompt（app 层在工具轮调用 resetContext()，
    //      本机接口已暴露，接线由 team-lead 后续决定）。
    // 注：原生头部截断（1441 行附近）会丢弃 prompt 头部，若正好丢掉前缀 → reuse 塌为 0，自然全清。
    llama_memory_t mem = session->ctx ? llama_get_memory(session->ctx) : nullptr;
    const int32_t promptLen = static_cast<int32_t>(promptTokens.size());
    int32_t reuse = 0;
    if (mem != nullptr && !session->kvDirty && !session->kvPrefix.empty()
        && static_cast<int32_t>(session->kvPrefix.size()) >= session->kvPast) {
        // 求 promptTokens 与 kvPrefix 的最长公共前缀长度。
        const int32_t cachedLen = session->kvPast;
        const int32_t limit = std::min<int32_t>(promptLen, cachedLen);
        int32_t r = 0;
        while (r < limit && promptTokens[r] == session->kvPrefix[r]) {
            r++;
        }
        reuse = r;
    }
    if (session->kvDirty) {
        reuse = 0;  // 显式失效：强制不复用
    }

    // 退化保护（⚠️ 必须在裁剪 KV **之前**做）：若本轮 prompt 与缓存完全一致
    // （reuse == promptLen），就没有任何新增 token 可 decode → 本轮拿不到 logits，
    // llama_sampler_sample 会取到上一轮的陈旧 logits（或越界）→ 崩溃/输出垃圾。
    // 因此强制至少留 1 个 token 给本轮 decode。
    // 为什么必须先 clamp 再裁剪：若先裁剪后 clamp，reuse == kvPast == promptLen 时走的是
    // "前缀完全匹配、KV 不动"分支，pos=promptLen-1 的 cell 仍留在 cache 里；紧接着又把
    // 同一个 token 在同一 pos 重新 decode 一次 —— llama.cpp 的 unified KV 是"找空槽插入"，
    // 不会按 pos 去重，于是 seq0 出现**两个 pos=promptLen-1 的 cell**：
    //   ① 注意力重复看到该 token，logits 被污染；
    //   ② 生成结束后 llama_memory_seq_rm(mem, 0, promptLen, -1) 只删 pos>=promptLen，
    //      这个重复 cell 删不掉，却被当作干净前缀写进 kvPrefix → 缓存被永久投毒，
    //      后续每轮复用都在错误 KV 上续写。
    // 先 clamp 成 promptLen-1，就会落进下面的 `reuse < kvPast` 分支，把 pos>=promptLen-1
    // 的 cell 先删掉再重新 decode，KV 保持唯一且正确。
    if (reuse >= promptLen) {
        reuse = promptLen - 1;
        if (reuse < 0) reuse = 0;
    }

    if (mem != nullptr) {
        if (reuse == 0) {
            // 完全不复用：全量重算（等价于原无条件 llama_memory_clear 行为）。
            llama_memory_clear(mem, true);
        } else if (reuse < session->kvPast) {
            // 部分复用：砍掉分叉尾部 [reuse, kvPast)，保留 [0, reuse) 作为续写前缀。
            // llama_memory_seq_rm 语义（include/llama.h:733）：删除 seq 中位置在 [p0, p1) 的 token；
            // p1 < 0 表示 [p0, inf)。返回 bool，false = 无法删除部分序列。
            // 返回 false 时必须降级为全清，且 **reuse 要一并归 0** ——
            // 否则 KV 已被清空、prefill 却仍从 offset=reuse 开始，[0, reuse) 这段 token
            // 永远不会被 decode，模型在空 KV 上从中间位置续写 → 纯乱码。
            if (!llama_memory_seq_rm(mem, 0, reuse, -1)) {
                LOGE("kv prefix partial-rm failed (reuse=%d kvPast=%d); fallback to full clear",
                     (int) reuse, (int) session->kvPast);
                llama_memory_clear(mem, true);
                reuse = 0;
            }
        }
        // reuse == kvPast：前缀完全匹配，KV 不动，直接进入续写（最快路径）。
    } else {
        // 没有 KV memory（极端配置）→ 不可能复用。
        reuse = 0;
    }

    const int32_t startOffset = reuse;

    const auto prefillStart = std::chrono::steady_clock::now();

    // Prefill in chunks of n_batch. Use an EXPLICIT, writable batch (llama_batch_init)
    // and set pos/seq_id/logits per token — do NOT rely on llama_batch_validate's
    // null-default behavior, which falls back to pos=0 when KV memory is null and
    // mis-places chunked tokens (KV overwrite at position 0 -> garbage context ->
    // first sampled token is EOG -> empty output). This matches PocketPal's
    // chunk-prefill范式 and is robust regardless of KV memory state.
    const uint32_t n_batch = llama_n_batch(session->ctx);
    // 手机 CPU prefill 大 chunk 单段可能 >30s，被系统/库层超时掐死；改小到 256 让每段
    // 更快完成、进度条更频繁更新，同时仍保持合理效率（256 是 llama.cpp 常见 ubatch 量级）。
    const int32_t effectiveChunk = static_cast<int32_t>(std::min<uint32_t>(n_batch, 256u));
    const int32_t totalPrompt = static_cast<int32_t>(promptTokens.size());
    int32_t n_past = reuse;

    LOGI(
        "Prefill decode start: prompt_tokens=%zu n_ctx=%d n_batch=%u effective_chunk=%d max_new=%d",
        promptTokens.size(),
        n_ctx,
        n_batch,
        effectiveChunk,
        maxNew
    );
    const llama_seq_id seq0 = 0;
    llama_batch pbatch = llama_batch_init(n_batch, 0, 1);
    bool prefillFailed = false;
    int32_t offset = startOffset;
    while (offset < totalPrompt) {
        const int32_t chunk = std::min<int32_t>(effectiveChunk, totalPrompt - offset);
        for (int32_t i = 0; i < chunk; i++) {
            pbatch.token[i] = promptTokens[offset + i];
            pbatch.pos[i] = offset + i;
            pbatch.n_seq_id[i] = 1;
            // ⚠️ 致命坑（本次崩溃真根因）：绝不能写成 `pbatch.seq_id[i] = &seq0`。
            // llama_batch_init 已为每个 token 槽 malloc 了 seq_id[i]（大小 n_seq_max），
            // llama_batch_free 会遍历到 nullptr 哨兵并**逐个 free(batch.seq_id[i])**。
            // 覆写指针 = ①泄漏原 malloc 块 ②让 free() 去释放一个**栈地址**
            // → bionic malloc 判定非法指针直接 abort（SIGABRT signal=6 @ free/llama_batch_free），
            // 侥幸不 abort 时也已污染堆元数据 → 后续随机 SIGSEGV（如 ggml_vec_dot_q5_K_q8_K）
            // 与 ggml_abort @ llama_context::decode。单线程发一条消息即必现，与并发无关。
            // 正确做法同官方 common_batch_add：往已分配的槽里**写值**。
            pbatch.seq_id[i][0] = seq0;
            // only the very last token across the whole prompt outputs logits
            pbatch.logits[i] = (offset + i == totalPrompt - 1) ? 1 : 0;
        }
        pbatch.n_tokens = chunk;
        LOGI("Prefill chunk: offset=%d chunk=%d last=%d", (int) offset, (int) chunk,
             (offset + chunk >= totalPrompt) ? 1 : 0);

        const auto chunkStart = std::chrono::steady_clock::now();

        // 把 prefill 进度实时推给 UI（首 token 到达后会被真实文本覆盖）。
        if (midOnProgress != nullptr) {
            jstring jstage = env->NewStringUTF("prefill");
            if (jstage != nullptr) {
                // Plan C: 透出"本轮真正要 decode 的新增 token 数"，不是总 prompt 长度。
                // 原生每轮把 promptTokens 整体下发，但 Plan A 已复用 KV 前缀，实际只 decode
                // [reuse, totalPrompt) 这段新 token；Kotlin 侧据此阈值（LOCAL_PREFILL_PROGRESS_TOKEN_THRESHOLD）
                // 决定是否上屏进度条——多轮只新增几十 token 时被挡住，消除"每轮弹 正在处理提示词 X%"。
                const int32_t effTotal = static_cast<int32_t>(promptTokens.size()) - reuse;
                const int32_t effCur = std::max<int32_t>(0, offset - reuse);
                env->CallVoidMethod(callback, midOnProgress, jstage, (jint) effCur, (jint) effTotal);
                env->DeleteLocalRef(jstage);
                if (env->ExceptionCheck()) env->ExceptionClear();
            }
        }

        int32_t ret = llama_decode(session->ctx, pbatch);
        const auto chunkMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                                 std::chrono::steady_clock::now() - chunkStart)
                                 .count();
        LOGI("Prefill chunk done: offset=%d chunk=%d ret=%d time=%lldms", (int) offset, (int) chunk,
             (int) ret, (long long) chunkMs);
        // ⚠️ 返回码语义（include/llama.h）：0=成功；1=**找不到 KV slot（失败！）**；
        // 2=aborted；-1=非法 batch；<-1=致命。
        // 旧代码写的是 `ret != 0 && ret != 1`，等于把 1 当成功继续跑 ——
        // 该 chunk 的 KV 根本没写进去，上下文残缺 → 采样出的首 token 直接是 EOG
        // → 生成循环立刻 break → 输出空字符串（"不闪退但一个字都不回"的独立根因之一）。
        if (ret != 0) {
            if (ret == 2) {
                SET_ERR(session, "提示词处理被中断（模型正在卸载或已取消）");
            } else if (ret == 1) {
                SET_ERR(session,
                        "提示词处理失败：KV 缓存放不下（提示词 %d token / n_ctx=%d / n_batch=%u）。"
                        "请减少上下文或换更小的模型",
                        (int) totalPrompt, (int) n_ctx, (unsigned) n_batch);
            } else {
                SET_ERR(session, "提示词解码失败 llama_decode ret=%d（offset=%d/%d）",
                        (int) ret, (int) offset, (int) totalPrompt);
            }
            prefillFailed = true;
            break;
        }
        // 让 unload/cancel 能快速打断 prefill：在每段 chunk 解码后检查 cancel 标志。
        if (session->cancel.load()) {
            SET_ERR(session, "提示词处理被取消（cancel）");
            prefillFailed = true;
            break;
        }
        offset += chunk;
    }
    llama_batch_free(pbatch);
    const auto prefillTotalMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                                    std::chrono::steady_clock::now() - prefillStart)
                                    .count();
    LOGI("Prefill phase total: %lldms (tokens=%d)", (long long) prefillTotalMs, totalPrompt);
    if (prefillFailed) {
        session->kvDirty = true;  // prefill 未正常完成，KV 状态不可信 → 下轮全清
        return JNI_FALSE;
    }

    // n_past for subsequent single-token decoding
    n_past = static_cast<int32_t>(promptTokens.size());

    prefillToolCallGenerationPrompt(session);

    // Generation loop
    std::vector<llama_token> generatedTokens;

    // Reusable single-token batch with EXPLICIT pos/seq_id (same rationale as prefill):
    // never rely on llama_batch_validate null-defaults.
    llama_batch gbatch = llama_batch_init(1, 0, 1);
    gbatch.n_seq_id[0] = 1;
    // 同 prefill：写值，不覆写指针（否则 llama_batch_free 会 free 栈地址 → SIGABRT）。
    gbatch.seq_id[0][0] = seq0;
    generatedTokens.reserve(static_cast<size_t>(maxNew));
    // Plan A: 本轮生成是否被取消/abort/解码失败。若是，KV 含不完整生成尾，不可缓存为前缀。
    bool generationDirty = false;
    std::string prevDecoded;
    std::string pendingUtf8;  // incomplete trailing UTF-8 bytes buffered across tokens (CJK mojibake fix)
    std::vector<char> detokBuf;

    for (int i = 0; i < maxNew; i++) {
        if (session->cancel.load()) {
            LOGI("generation cancelled");
            generationDirty = true;
            break;
        }

        const llama_token newToken = llama_sampler_sample(session->sampler, session->ctx, -1);
        llama_sampler_accept(session->sampler, newToken);

        if (i == 0) {
            LOGI("first sampled token=%d eog=%d", (int) newToken, (int) llama_vocab_is_eog(vocab, newToken));
        }

        if (llama_vocab_is_eog(vocab, newToken)) {
            // ⚠️ 关键可观测性缺口（此前完全没记录）：
            // 若**第一个**采样 token 就是 EOG，循环立刻 break，generatedTokens 为空，
            // 但函数仍返回 JNI_TRUE → Kotlin 侧 ok=true 且 sb 为空 → QuroLlmResult.Text("")
            // → 聊天气泡纯空白、无任何报错，用户只能看到"不回复"，日志里也毫无痕迹。
            // 这是"能跑但一个字都不吐"最典型的形态，必须显式记录成可见错误。
            if (i == 0) {
                SET_ERR(session,
                        "模型在第一个 token 就输出了结束符（EOG token=%d）。"
                        "通常意味着聊天模板与该 GGUF 不匹配，或提示词格式有误",
                        (int) newToken);
            }
            break;
        }

        // Detokenize the generated token sequence to produce valid UTF-8 text.
        // Token pieces may split multi-byte sequences; emitting per-token pieces often results in mojibake.
        generatedTokens.push_back(newToken);

        int32_t detokCap = std::max<int32_t>(64, static_cast<int32_t>(generatedTokens.size() * 8 + 32));
        detokBuf.resize(static_cast<size_t>(detokCap));

        int32_t nDetok = llama_detokenize(
            vocab,
            generatedTokens.data(),
            static_cast<int32_t>(generatedTokens.size()),
            detokBuf.data(),
            static_cast<int32_t>(detokBuf.size()),
            true,
            false
        );
        if (nDetok < 0) {
            detokBuf.resize(static_cast<size_t>(-nDetok));
            nDetok = llama_detokenize(
                vocab,
                generatedTokens.data(),
                static_cast<int32_t>(generatedTokens.size()),
                detokBuf.data(),
                static_cast<int32_t>(detokBuf.size()),
                true,
                false
            );
        }

        std::string decodedNow;
        if (nDetok > 0) {
            decodedNow.assign(detokBuf.data(), detokBuf.data() + nDetok);
        }

        std::string delta;
        if (!prevDecoded.empty() && decodedNow.rfind(prevDecoded, 0) == 0) {
            delta = decodedNow.substr(prevDecoded.size());
        } else {
            delta = decodedNow;
        }
        prevDecoded = decodedNow;

        if (!delta.empty()) {
            // UTF-8 boundary buffering: append delta to pendingUtf8, emit only complete characters.
            // Multi-byte CJK characters can span two tokens; the delta from llama_detokenize may end
            // with an incomplete UTF-8 sequence. bytesUtf8ToJstring would replace those bytes with
            // 0xFFFD (�). By buffering incomplete trailing bytes, we hold them until the next token
            // completes the character — mirroring MNN's extractCompleteUtf8 (mnnllmnative.cpp:746-765).
            pendingUtf8 += delta;
            std::string completeChars;
            size_t ci = 0;
            while (ci < pendingUtf8.size()) {
                int charLen = utf8CharLength(static_cast<unsigned char>(pendingUtf8[ci]));
                if (charLen == 0 || ci + static_cast<size_t>(charLen) > pendingUtf8.size()) {
                    break;  // invalid byte or incomplete trailing bytes — wait for next token
                }
                completeChars.append(pendingUtf8, ci, static_cast<size_t>(charLen));
                ci += static_cast<size_t>(charLen);
            }
            if (ci > 0) {
                pendingUtf8.erase(0, ci);
            }
            if (!completeChars.empty()) {
                jstring jdelta = bytesUtf8ToJstring(env, completeChars);
                if (jdelta == nullptr || env->ExceptionCheck()) {
                    env->ExceptionClear();
                } else {
                    const jboolean keepGoing = env->CallBooleanMethod(callback, midOnToken, jdelta);
                    env->DeleteLocalRef(jdelta);
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                        LOGE("Java callback threw exception; stopping generation");
                        break;
                    }
                    if (!keepGoing) {
                        break;
                    }
                }
            }
        }

        if (n_ctx > 0 && n_past >= n_ctx) {
            LOGI("context window reached: n_past=%d n_ctx=%d", n_past, n_ctx);
            break;
        }

        llama_token next = newToken;
        gbatch.token[0] = next;
        gbatch.pos[0] = n_past;
        gbatch.logits[0] = 1;
        gbatch.n_tokens = 1;
        LOGI("generation decode #%d n_past=%d", i, (int) n_past);
        int32_t ret = llama_decode(session->ctx, gbatch);
        if (ret != 0) {
            if (ret == 2) {
                LOGI("decode aborted");
                generationDirty = true;
                break;
            }
            if (ret == 1) {
                // KV 满：已生成的内容仍然有效，正常收尾 break 而不是整段判失败。
                LOGI("no KV slot during generation (context full) n_past=%d n_ctx=%d", n_past, n_ctx);
                generationDirty = true;
                break;
            }
            SET_ERR(session, "生成阶段解码失败 llama_decode ret=%d（已生成 %d token）", (int) ret, i);
            session->kvDirty = true;
            llama_batch_free(gbatch);
            return JNI_FALSE;
        }

        n_past += 1;
    }

    // Flush any remaining buffered UTF-8 bytes (e.g. generation ended mid-character).
    // These bytes may be incomplete — bytesUtf8ToJstring will replace them with 0xFFFD,
    // which is the correct behavior for truncated output (better than silently dropping).
    if (!pendingUtf8.empty()) {
        jstring jdelta = bytesUtf8ToJstring(env, pendingUtf8);
        if (jdelta != nullptr && !env->ExceptionCheck()) {
            env->CallBooleanMethod(callback, midOnToken, jdelta);
            env->DeleteLocalRef(jdelta);
        } else {
            env->ExceptionClear();
        }
        pendingUtf8.clear();
    }

    // ===================== Plan A: 生成结束后的保守尾处理 =====================
    // 生成期间 KV = promptTokens + 本轮裸 assistant token。但下一轮 prompt 是把这段回复经聊天模板
    // 重新渲染的（带 <|im_start|>assistant / <|im_end|> 包装 + 后续 user 轮），两者在生成文本结尾处
    // 必然分叉。若直接续用 KV，下一轮"prefill 前缀"会和真实 prompt 错位 → 上下文错乱。
    // 采用保守方案：丢弃生成尾（回归到干净的 promptTokens 前缀），把 kvPrefix 记为 promptTokens；
    // 这样下一轮 prompt 必以 promptTokens 为严格前缀 → 正常复用。代价：每轮多存/算一点，但绝不分叉。
    // 若本轮被取消/abort/解码失败（generationDirty）→ 不缓存，标记 kvDirty 让下轮全清。
    if (mem != nullptr && !generationDirty) {
        llama_memory_seq_rm(mem, 0, static_cast<int32_t>(promptTokens.size()), -1);
        session->kvPrefix = promptTokens;
        session->kvPast = static_cast<int32_t>(promptTokens.size());
        session->kvDirty = false;
    } else {
        session->kvDirty = true;
    }

    llama_batch_free(gbatch);
    return JNI_TRUE;
}

/**
 * 取回本会话最近一次失败的人类可读原因。
 *
 * 存在意义：native 侧的失败此前只进 logcat，用户端一律表现为"没反应"，
 * 而用户拿不到 adb / 日志文件 → 我只能靠猜，已经因此白跑三轮。
 * 现在 Kotlin 侧在推理失败或输出为空时调用它，把真正原因直接写进聊天气泡。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeGetLastError(JNIEnv * env, jclass clazz, jlong sessionPtr) {
    (void) clazz;
    if (sessionPtr == 0) return nullptr;
    auto * session = reinterpret_cast<LlamaSessionNative *>(sessionPtr);
    if (session->lastError.empty()) return nullptr;
    return bytesUtf8ToJstring(env, session->lastError);
}

// Plan A: 主动让 KV 前缀缓存失效并清空 KV（对应 Kotlin LlamaSession.resetContext()）。
// 工具轮（applyStructuredChatTemplate 渲染的 prompt）与新模型/新会话切换时由 app 层调用，
// 确保下一轮不会把错误的 KV 前缀当作上下文续写。
extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeResetKv(JNIEnv * env, jclass clazz, jlong sessionPtr) {
    (void) env;
    (void) clazz;
    if (sessionPtr == 0) return;
    auto * session = reinterpret_cast<LlamaSessionNative *>(sessionPtr);
    if (session->ctx) {
        llama_memory_t mem = llama_get_memory(session->ctx);
        if (mem) {
            llama_memory_clear(mem, true);
        }
    }
    session->kvPrefix.clear();
    session->kvPast = 0;
    session->kvDirty = true;
}

#endif
