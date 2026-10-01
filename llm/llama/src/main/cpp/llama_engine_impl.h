// =============================================================================
// L4 · LlamaEngine 内部实现头 —— **仅限 llama 模块自己的 .cpp 包含**
// =============================================================================
// 这里是 llama.h / chat.h / nlohmann 唯一被 include 的地方。
// llama_jni.cpp（L2 桥接层）**绝不**包含本文件 —— 那是分层的硬边界：
//   L2 只认 llama_engine.h 暴露的契约（jlong 句柄 + 基础类型 + std::string）。
//
// 为什么把结构体与辅助函数放进一个内部头，而不是全塞进 llama_engine.cpp：
//   实现被拆成两个 TU（llama_engine.cpp 走推理主链路，llama_engine_chat.cpp 走
//   模板/工具调用），它们必须共享同一份 Session 定义与同一批辅助函数声明。
//   拆 TU 的目的不是好看，是让「改模板逻辑」不必碰 1400 行的推理文件。
// =============================================================================
#ifndef QURO_LLAMA_ENGINE_IMPL_H
#define QURO_LLAMA_ENGINE_IMPL_H

#include "chat.h"
#include "llama.h"
#include "nlohmann/json.hpp"

#include <atomic>
#include <chrono>
#include <memory>
#include <string>
#include <vector>

#include <android/log.h>

#include <cstdio>
#include <string>

#include "quro/engine_types.h"
#include "quro/thermal.h"
#include "quro/think_splitter.h"

// ---------------------------------------------------------------------------
// 日志与错误宏
// ---------------------------------------------------------------------------
// 放在内部头而不是各 .cpp：实现被拆成三个 TU（engine / generate / chat），
// 三处都要打同一套日志。分散定义迟早会出现 TAG 不一致，
// 而开发者抓 logcat 是靠 `logcat -s LlamaNative` 的 —— TAG 必须稳定。
#define TAG "LlamaNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 记录失败原因（同时打 LOGE，保留 logcat 链路）。
// 为什么要把原因**存进会话**而不是只打日志：native 侧失败此前只进 logcat，
// 用户端一律表现为"没反应"，而用户拿不到 adb。存下来才能回传聊天气泡。
#define SET_ERR(sess, ...)                                        \
    do {                                                          \
        char _errbuf[512];                                        \
        snprintf(_errbuf, sizeof(_errbuf), __VA_ARGS__);          \
        LOGE("%s", _errbuf);                                      \
        if ((sess) != nullptr) (sess)->lastError = _errbuf;       \
    } while (0)

namespace quro {
namespace llm {
namespace llama_detail {

// ---------------------------------------------------------------------------
// 采样参数（原 SamplingParamsNative 原样迁移）
// ---------------------------------------------------------------------------
struct SamplingParamsNative {
    float temperature = 1.0f;
    float topP = 1.0f;
    int32_t topK = 0;
    int32_t penaltyLastN = 64;
    float repeatPenalty = 1.0f;
    float frequencyPenalty = 0.0f;
    float presencePenalty = 0.0f;
    uint32_t seed = 0;
};

// ---------------------------------------------------------------------------
// 工具调用文法配置（原 ToolCallGrammarConfigNative 原样迁移）
// ---------------------------------------------------------------------------
struct ToolCallGrammarConfigNative {
    std::string grammar;
    bool lazy = false;
    std::vector<std::string> triggerPatterns;
    std::vector<llama_token> triggerTokens;
    std::string generationPrompt;
};

// ---------------------------------------------------------------------------
// 会话上下文（原 LlamaSessionNative 原样迁移 —— 字段与注释都是真机长出来的）
// ---------------------------------------------------------------------------
struct Session {
    /// 本次加载的配置快照。
    /// 为什么要在会话里留一份：Stats 与 resolvedBackend() 需要回读
    /// "本次实际请求了什么"（例如 nGpuLayers > 0 才可能是 GPU 后端），
    /// 而这两个查询接口都没有入参。存指针不安全（LoadConfig 可能来自栈），
    /// 所以按值存一份。
    LoadConfig cfg;

    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * sampler = nullptr;
    common_chat_templates_ptr chatTemplates;
    SamplingParamsNative samplingParams;
    ToolCallGrammarConfigNative toolCallGrammar;
    common_chat_parser_params toolCallParserParams;
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
    std::atomic_bool cancel{false};

    /// ── 思考段 / 正文段 分流（L4，引擎无关实现见 quro/think_splitter.h）──
    /// 契约 `TokenChunk::isThinking` 从本重构起**真的有值**：
    /// 在此之前两栈四个发射点全部硬编码 false，害得上层在 Kotlin 里
    /// 手写了三份文本剥离器（StreamingThinkStripper / MnnThinkContent / stripResidualThink）。
    /// 换会话/换模型必须 reset（见 resetKv 与 load），否则状态串味。
    ThinkSplitter thinkSplitter;

    // ── KV 前缀缓存（Plan A）──
    // kvPrefix : 上一轮生成结束后保留的 KV 前缀（= 上一轮最终 promptTokens）。
    // kvPast   : kvPrefix 的长度（即上一轮缓存的 KV 位置数）。
    // kvDirty  : true 表示缓存失效，下轮必须全量重算（失效条件见 generate 内注释）。
    // 初始 kvDirty=true：首个请求尚无缓存，等价于全清。
    std::vector<llama_token> kvPrefix;
    int32_t kvPast = 0;
    bool kvDirty = true;

    // 🔎 最近一次失败的**人类可读原因**。
    // 背景：此前 native 的所有失败都只走 LOGE 进 logcat，用户端表现统一为
    // 「没反应/不回复」，排障必须依赖 adb 或翻 Download/QuroAI_logs —— 用户拿不到
    // 日志，只能靠猜，已因此白跑三轮修复。现在把原因回传 Java，直接显示在聊天气泡里。
    // 访问方：genLock 已把同一 session 的 generate 串行化，故不额外加锁。
    std::string lastError;

    // ── L5 · 温控自适应（实现见 applyThermalAdvice）──
    // thermalPollMs：采样间隔（毫秒）。**0 = 禁用**。默认值来自 LoadConfig。
    // thermalThreads：当前实际生效的线程数。只有当它**变化**时才去动后端 ——
    //   llama_set_n_threads 会重建线程绑定，无谓调用纯属浪费，也会刷日志。
    int32_t thermalPollMs = 0;
    int32_t thermalThreads = 0;
    std::chrono::steady_clock::time_point thermalLastCheck{};

    // ── 统计（L3 Stats 的数据来源）──
    double loadMs = 0.0;
    double prefillMs = 0.0;
    double decodeMs = 0.0;
    int64_t prefillTokens = 0;
    int64_t decodeTokens = 0;
    int64_t weightBytes = 0;
    int32_t activeBatch = 0;
    int32_t activeThreads = 0;

    /// 同一会话的 generate 串行化（原实现靠 Kotlin 侧加锁 + 此处兜底）。
    std::mutex genLock;
};

// ---------------------------------------------------------------------------
// 辅助函数（原文件匿名 namespace 里的自由函数，改为跨 TU 共享）
// ---------------------------------------------------------------------------

/// 建立/重建采样链。失败返回 false（并保留旧链）。
bool rebuildSamplerForSession(Session * session);

/// 创建采样链（含可选的文法约束）。
llama_sampler * createSamplerChain(const llama_vocab * vocab, float temperature,
                                   float topP, int32_t topK, int32_t penaltyLastN,
                                   float repeatPenalty, float frequencyPenalty,
                                   float presencePenalty, uint32_t seed,
                                   const ToolCallGrammarConfigNative * grammarConfig);

/// 分词（返回 token 数；失败返回负数，绝对值 = 所需容量）。
int32_t tokenizeText(const llama_vocab * vocab, const std::string & text, bool addSpecial);
std::vector<llama_token> tokenizeTextToVector(const llama_vocab * vocab,
                                              const std::string & text, bool addSpecial);

/// 从 common_chat_params 抽出 GBNF / 触发模式 / 生成提示词。
ToolCallGrammarConfigNative buildToolCallGrammarConfig(const common_chat_params & params);

/// 清空工具调用相关状态（文法 + 解析器 + 生成提示词）。
void resetToolCallState(Session * session);

/// 用模型内嵌模板初始化 chatTemplates。无模板的 GGUF 返回 false。
bool initializeChatTemplatesForSession(Session * session);

/// 把 roles/contents 两条并行数组转成 common_chat_msg 列表。
bool buildChatMessages(const std::vector<std::string> & roles,
                       const std::vector<std::string> & contents,
                       std::vector<common_chat_msg> & out);

/// 单个 token 还原成文本片段（工具调用提示词用）。
bool tokenToPiece(const llama_vocab * vocab, llama_token token, std::string & out);

/// 工具轮：把 generationPrompt 先喂进 KV（原逻辑原样保留）。
void prefillToolCallGenerationPrompt(Session * session);

/// L5 温控：在 token / chunk 边界读取档位并**主动**下调线程数。
/// 未启用（thermalPollMs <= 0）或档位未变时不做任何事。
void applyThermalAdvice(Session * session);

/// 首次调用时初始化 ggml 后端 + 安装 native 崩溃处理器。
void ensureBackendInit();

/// 从 UTF-8 首字节求该字符字节数（0 = 非法/不完整）。
int utf8CharLength(unsigned char byte);

/// 字数工具（正数取原值，否则取默认）。
uint32_t positiveOrDefaultUInt(int32_t value, uint32_t defaultValue);
int32_t positiveOrDefaultInt(int32_t value, int32_t defaultValue);

/// llama_decode 的 abort 回调（转发到 session->cancel）。
bool abortCallback(void * user_data);

/// 估算权重占用字节数（内存互斥判据）。取不到时返回 0。
int64_t estimateModelBytes(llama_model * model);

/// 把 llama.cpp 的"为什么不可用"翻译成人话。
void describeUnavailable(std::string * whyNot);

}  // namespace llama_detail
}  // namespace llm
}  // namespace quro

// ---------------------------------------------------------------------------
// LlamaEngine::Impl 的完整定义
// ---------------------------------------------------------------------------
// 放在内部头里的原因：实现被拆成三个 TU
//   llama_engine.cpp           生命周期 / 分词 / prefill
//   llama_engine_generate.cpp  一体化 generate（KV 前缀复用在这里）
//   llama_engine_chat.cpp      聊天模板 / 工具调用 / 采样参数
// 它们都要拿到同一个 impl_->session。对外（llama_engine.h）Impl 永远只是
// 前向声明的 struct —— 上层连它有多少成员都不该知道。
#include "llama_engine.h"

namespace quro {
namespace llm {

struct LlamaEngine::Impl {
    llama_detail::Session session;

    /// 保护 load / unload 与 session 的创建销毁。
    /// 注意：生成不由它串行化 —— 那归 session.genLock，
    /// 因为生成耗时以分钟计，用这把锁会把 unload（用户切模型）堵死。
    std::mutex lifecycle;
};

}  // namespace llm
}  // namespace quro

#endif  // QURO_LLAMA_ENGINE_IMPL_H
