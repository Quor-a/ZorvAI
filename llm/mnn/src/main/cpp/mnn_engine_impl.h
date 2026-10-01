// =============================================================================
// L4 · MnnEngine 内部实现头 —— **仅限 mnn 模块自己的 .cpp 包含**
// =============================================================================
// 这里是 MNN 头文件（llm/llm.hpp、MNN/expr/*）与 rapidjson 唯一被 include 的地方。
// mnn_jni.cpp（L2 桥接层）**绝不**包含本文件 —— 那是分层的硬边界：
//   L2 只认 mnn_engine.h 暴露的契约（jlong 句柄 + 基础类型 + std::string）。
//
// 为什么把 Session 与辅助函数放进内部头，而不是全塞进一个 .cpp：
//   实现被拆成三个 TU（生命周期 / 流式生成 / 模板与工具调用），它们必须共享
//   同一份 Session 定义与同一批辅助函数声明。拆 TU 的目的不是好看，
//   是让「改模板逻辑」不必碰 600 行的流式生成文件。
// =============================================================================
#ifndef QURO_MNN_ENGINE_IMPL_H
#define QURO_MNN_ENGINE_IMPL_H

#include <llm/llm.hpp>

#include <MNN/expr/Expr.hpp>
#include <MNN/expr/Module.hpp>

#include <rapidjson/document.h>
#include <rapidjson/stringbuffer.h>
#include <rapidjson/writer.h>

#include <android/log.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "quro/engine_types.h"
#include "quro/think_splitter.h"

#include "mnn_engine.h"

using namespace MNN;
using namespace MNN::Transformer;

// ---------------------------------------------------------------------------
// 日志与错误宏
// ---------------------------------------------------------------------------
// 放在内部头而不是各 .cpp：实现被拆成三个 TU，三处都要打同一套日志。
// 分散定义迟早会出现 TAG 不一致，而开发者抓 logcat 是靠 `logcat -s MNNLlmNative`
// 的 —— TAG 必须稳定。
#define TAG "MNNLlmNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace quro {
namespace llm {
namespace mnn_detail {

// ---------------------------------------------------------------------------
// MNN 会话状态（原文件里分散在「全局 map + 栈上 StreamContext」的字段，全部收拢）
// ---------------------------------------------------------------------------
struct Session {
    /// MNN 的 Llm 实例（Llm::createLLM / Llm::destroy 配对管理）。
    Llm* llm = nullptr;

    /// 是否已 load()。MNN 没有查询接口，只能自己记。
    bool weightLoaded = false;

    /// 取消标志。旧实现是 `std::map<jlong, bool>`（全局 + 互斥锁），
    /// 现在直接是实例字段 —— 原子布尔，任意线程可写，流式 sink 每个 chunk 读一次。
    std::atomic_bool cancel{false};

    /// ── 思考段 / 正文段 分流（L4，引擎无关实现见 quro/think_splitter.h）──
    /// 契约 `TokenChunk::isThinking` 从本重构起**真的有值**。
    /// 此前 MNN 侧靠 Kotlin 的 StreamingThinkStripper（一百行文本状态机）+
    /// MnnThinkContent.split + stripResidualThink 三层文本手段兜底 ——
    /// 那些实现本身就承认只认 `<think>` 标签、对无标签明文推理完全失效。
    /// 现在分流在引擎边界完成，上层直接消费 isThinking 标志。
    ThinkSplitter thinkSplitter;

    /// 最近一次失败原因（格式：`错误码|英文补充`）。见 mnn_engine.h 的契约说明。
    std::string lastError;

    /// 音频帧回调。**不在这里存 Java 对象** ——
    /// JNI 层（mnn_jni.cpp）自己持有 GlobalRef，只把 std::function 交进来。
    /// 这样引擎层完全不知道 JNI 的存在。
    MnnEngine::WavformCallback wavform;

    /// 同一实例的 generate 串行化。
    /// 为什么必须有：MNN 的 Llm 内部只有一份 KV / mContext，
    /// 两路并发 generate 会同时改它 → 上下文错乱（且不崩，只是输出变乱）。
    /// cancel 不拿这把锁，所以取消仍然即时。
    std::mutex genLock;

    /// 本次加载的配置快照（Stats / resolvedBackend 需要回读）。
    LoadConfig cfg;

    // ── 统计（L3 Stats 的数据来源；MNN 从 LlmContext 读，见 stats()）──
    int64_t prefillTokens = 0;
    int64_t decodeTokens = 0;
    int64_t weightBytes = 0;
};

// ---------------------------------------------------------------------------
// 纯函数辅助（原文件里的 jsonEscape / contextToJson / ChatML 回退等）
// ---------------------------------------------------------------------------
std::string jsonEscape(const std::string& input);
void appendIntVectorJson(std::ostringstream& oss, const std::vector<int>& values);
const char* llmStatusToString(LlmStatus status);
std::string contextToJson(const LlmContext* context);

/// 内置 ChatML 工具说明（Hermes/Qwen 约定）。空 tools 返回空串。
std::string buildToolsInstruction(const rapidjson::Document& toolsDoc);
/// 内置 ChatML 回退渲染。**这是回退不是主路径**，只有模型自带模板产出空串时才用。
std::string renderChatMlFallback(const rapidjson::Document& messagesDoc,
                                 const std::string& toolsField);

/// 结构化渲染核心（原 applyStructuredChatTemplate）。
/// 失败时经 errorOut 回传 `错误码|补充`；返回空串表示失败。
/// **不碰 Session**：它只依赖 Llm，写成纯函数便于单测与复用。
std::string applyStructuredChatTemplateCore(Llm* llm, const std::string& messagesJson,
                                            const std::string& toolsJson,
                                            std::string* errorOut);

/// 流式生成共用的 sink 逻辑（原两份 235 行重复代码合并成一份）。
/// 逻辑逐字来自旧实现，只把「Java 回调」换成 L3 的 Callbacks：
///   · `<eop>` 结束标记
///   · UTF-8 跨 token 边界缓冲（CJK 不花字）
///   · 按 16 字节 / 标点 / 换行 决定 flush（保证首字延迟与吞吐的平衡）
///   · cancel 标志在每次写入前检查
struct StreamSink;

/// 写入 StreamSink 的 streambuf（MNN 的 response/generate 要求 std::ostream&）。
class CallbackStreamBuf;

}  // namespace mnn_detail
}  // namespace llm
}  // namespace quro

// ---------------------------------------------------------------------------
// MnnEngine::Impl 的完整定义
// ---------------------------------------------------------------------------
// 对外（mnn_engine.h）Impl 永远只是前向声明的 struct —— 上层连它有多少成员
// 都不该知道。
namespace quro {
namespace llm {

struct MnnEngine::Impl {
    mnn_detail::Session session;

    /// 保护 create / load / unload 与 session 的创建销毁。
    /// 注意：生成不由它串行化 —— 那归 session.genLock，
    /// 因为生成耗时以分钟计，用这把锁会把 unload（用户切模型）堵死。
    std::mutex lifecycle;
};

}  // namespace llm
}  // namespace quro

#endif  // QURO_MNN_ENGINE_IMPL_H
