// =============================================================================
// L3/L4 · LlamaEngine —— quro::llm::Engine 的 llama.cpp 实现
// =============================================================================
// 分层归属（对照架构图）：
//   L2 JNI 桥接层 → llama_jni.cpp   只传 opaque 句柄(J) + 基础类型 + 回调上行
//   L3 统一引擎抽象 → 本类，实现 quro::llm::Engine 全部纯虚方法
//   L4 引擎与后端   → 本类的 .cpp，唯一 #include "llama.h" / "chat.h" 的地方
//   L5 系统资源     → llm/core 的 thermal / resource / memory_arbiter
//
// ★★ 本头文件**绝不**包含 llama.h / chat.h，也绝不出现它们的类型 ★★
//   llama.h 的结构体里含 std::vector / std::string / common_chat_templates_ptr。
//   这些类型一旦出现在公共头上，上层（尤其 JNI 层）就被迫与 llama.cpp 的
//   STL/分配器 ABI 绑定 —— 任何一处不匹配就是 SIGABRT（vector::_M_realloc_insert
//   一类崩溃）。所以实现细节（结构体、辅助函数、上下文）全部锁在
//   llama_engine.cpp 的匿名 namespace 里，本头文件只暴露：
//     ① Engine 契约方法
//     ② llama.cpp 特有的扩展能力（采样参数 / 工具调用文法 / 聊天模板）
//        —— 它们也不是 llama 类型，只是 std::string / std::vector<std::string>
//
// 与「旧构架」的分界（这就是为什么这次不是加功能）：
//   旧：llama_jni_stub.cpp 单文件 2074 行 = JNI 解析 + llama 调用 + KV 策略 +
//       温控 + 崩溃处理 + 注册表。L3 的 Engine 是空接口，engine_factory 永远
//       返回「引擎未注册」，createEngineForModel 是死代码。
//   新：本类真的实现 Engine 并被注册；JNI 退化为薄层；engine_factory 真正路由。
// =============================================================================
#ifndef QURO_LLAMA_ENGINE_H
#define QURO_LLAMA_ENGINE_H

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "quro/engine.h"
#include "quro/engine_types.h"

namespace quro {
namespace llm {

class LlamaEngine final : public Engine {
public:
    LlamaEngine();
    ~LlamaEngine() override;

    LlamaEngine(const LlamaEngine&) = delete;
    LlamaEngine& operator=(const LlamaEngine&) = delete;

    // ─────────────────────── quro::llm::Engine ───────────────────────
    const char* name() const override;
    bool available(std::string* whyNot) const override;

    bool load(const LoadConfig& cfg, std::string* err) override;
    bool unload() override;
    bool loaded() const override;
    int64_t weightBytes() const override;

    bool tokenize(const std::string& text, std::vector<int32_t>* out,
                  std::string* err) override;
    bool detokenize(const std::vector<int32_t>& tokens, std::string* out,
                    std::string* err) override;

    bool prefill(const std::vector<int32_t>& promptTokens, const Callbacks& cbs,
                 std::string* err) override;
    bool decode(const GenParams& params, const Callbacks& cbs,
                std::string* err) override;

    /// 覆写 Engine 的默认三步实现：本引擎把 tokenize / KV 前缀复用 / prefill /
    /// decode 收在一个函数里，因为它们共享大量局部状态（reuse、n_past、
    /// KV memory 句柄、可复用的 llama_batch）。拆开重来一遍会引入行为漂移 ——
    /// 这段逻辑是真机调出来的（见 .cpp 里的长注释）。
    bool generate(const std::string& prompt, const GenParams& params,
                  const Callbacks& cbs, std::string* err) override;

    void cancel() override;
    bool resetKv(std::string* err) override;

    Stats stats() const override;
    Backend resolvedBackend(Phase phase) const override;

    // ─────────────── llama.cpp 特有扩展（不在 Engine 契约内）───────────────
    // 这些都是 l33t 层需要、但 MNN 侧语义不同的能力。放在具体引擎类上，
    // 而不是塞进 Engine 接口 —— 接口只放两个引擎都成立的东西。

    /// 词表分词数量（带 BOS/EOS 特殊 token）。失败返回 -1 并写 err。
    int32_t countTokens(const std::string& text, std::string* err);

    /// 设置采样参数并重建采样链。字段语义与旧实现逐字一致。
    bool setSamplingParams(float temperature, float topP, int32_t topK,
                           float repetitionPenalty, float frequencyPenalty,
                           float presencePenalty, int32_t penaltyLastN,
                           std::string* err);

    /// 开启工具调用文法约束（GBNF + 惰性触发模式）。
    bool setToolCallGrammar(const std::string& grammar,
                            const std::vector<std::string>& triggerPatterns,
                            std::string* err);

    /// 关闭工具调用文法，回到自由文本。
    bool clearToolCallGrammar(std::string* err);

    /// 用 GGUF 内嵌的聊天模板渲染 prompt（roles/contents 逐条对应）。
    bool applyChatTemplate(const std::vector<std::string>& roles,
                           const std::vector<std::string>& contents,
                           bool addAssistant, std::string* out, std::string* err);

    /// 用 OpenAI 风格的结构化消息 + 工具定义渲染 prompt，
    /// 同时把解析器状态写进会话（工具轮专用）。
    bool applyStructuredChatTemplate(const std::string& messagesJson,
                                     const std::string& toolsJson,
                                     bool addAssistant, std::string* out,
                                     std::string* err);

    /// 把模型输出按当前解析器拆成 OpenAI 风格 tool_calls JSON。
    /// 无工具调用时返回 false 且 out 清空（不是错误）。
    bool parseToolCallResponse(const std::string& content, std::string* out,
                               std::string* err);

    /// 最近一次失败的人类可读原因（可能为空）。上层直接显示给用户。
    std::string lastError() const;

    /// 供 JNI 层写入「回调被 Java 拒绝」这类错误原文。
    void setLastErrorForced(const std::string& msg);

private:
    /// 真正释放权重。**调用方必须已持有 impl_->lifecycle**。
    /// 拆出这个不加锁的版本是必须的：load() 在持锁状态下要先把旧会话释放掉，
    /// 如果它去调公开的 unload()（也要拿同一把 std::mutex），就是重入死锁 ——
    /// 表现为"切换模型时整个引擎永久卡住"，而且不会崩溃、不会有栈，极难定位。
    void releaseResources();

    /// 实现细节全部锁在 .cpp 里。本类对上层是一个不透明句柄。
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

// ---------------------------------------------------------------------------
// 模块级注册入口（实现见 llama_engine_registry.cpp）
// ---------------------------------------------------------------------------
// 由本 .so 的 JNI_OnLoad 调用一次，把 LlamaEngine 交给 L3 引擎工厂。
// 声明在这里而不是单独开一个头：它就属于"本模块对外提供什么"，
// 而且调用方只有一个（llama_jni.cpp），为它多一个头文件不划算。
namespace registry {

/// 幂等。失败（工厂拒绝）返回 false，调用方只记日志、不让 .so 加载失败。
bool registerLlamaEngine();

}  // namespace registry

}  // namespace llm
}  // namespace quro

#endif  // QURO_LLAMA_ENGINE_H
