// =============================================================================
// L3/L4 · MnnEngine —— quro::llm::Engine 的 MNN 实现
// =============================================================================
// 分层归属（对照架构图）：
//   L2 JNI 桥接层 → mnn_jni.cpp   只传 opaque 句柄(J) + 基础类型 + 回调上行
//   L3 统一引擎抽象 → 本类，实现 quro::llm::Engine 全部纯虚方法
//   L4 引擎与后端   → 本类的 .cpp，唯一 #include <llm/llm.hpp> / MNN 头的地方
//   L5 系统资源     → llm/core 的 thermal / resource / memory_arbiter
//
// ★★ 本头文件**绝不**包含 MNN 的任何头文件，也绝不出现 Llm / LlmContext /
//    MNN::Transformer 等类型 ★★
//   MNN 的 Llm 是裸指针（Llm::createLLM 返回），生命周期靠手工 destroy；
//   LlmContext 里含 std::vector<int>。一旦这些摊在公共头上，上层就被迫与
//   MNN 的 STL/分配器 ABI 绑定 —— 跨 .so 传递就是 SIGABRT。
//   所以实现细节全部锁在 mnn_engine_impl.h（仅本模块的 .cpp 可包含）。
//
// 与「旧构架」的分界（这次真删了什么）：
//   旧：mnnllmnative.cpp 单文件 1785 行，JNI 解析 + Llm 调用 + std::streambuf
//       回调桥 + cancel/lastError/audio 三张**以裸 Llm* 为键的全局 map** +
//       注册表全糊在一起；两个 235 行的流式函数除首次调用外逐字重复。
//   新：三个 TU 按职责切开；cancel / lastError / wavform 回调**从全局 map
//       变成引擎实例字段** —— 旧代码自己都写了注释承认「指针会被复用，
//       残留的错误串会串味」，实例字段从结构上消灭了这个问题。
// =============================================================================
#ifndef QURO_MNN_ENGINE_H
#define QURO_MNN_ENGINE_H

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "quro/engine.h"
#include "quro/engine_types.h"

namespace quro {
namespace llm {

class MnnEngine final : public Engine {
public:
    /// MNN 的对话消息类型（与 MNN::Transformer::ChatMessage 同构：role/content 二元组）。
    /// 注意用**有序 vector** 而不是 map：同一角色可连续出现多次，
    /// 用 map 会把相邻的 user 消息合并掉，模板语义被破坏。
    using ChatMessage = std::pair<std::string, std::string>;
    using ChatMessages = std::vector<ChatMessage>;

    /// 音频帧回调（TTS / 音频模型）。
    /// 为什么用 std::function 而不是裸函数指针（与 llama 侧相反）：
    /// MNN 的 API 本身就是 std::function（`Llm::setWavformCallback`），
    /// 我们的签名只能跟着它，否则每次回调都要多一层转发。
    /// 返回 false 表示"接收方不要了"，MNN 会据此停止吐音频。
    using WavformCallback = std::function<bool(const float*, size_t, bool)>;

    MnnEngine();
    ~MnnEngine() override;

    MnnEngine(const MnnEngine&) = delete;
    MnnEngine& operator=(const MnnEngine&) = delete;

    // ─────────────────────── MNN 生命周期特例 ───────────────────────
    /// 用 llm_config.json 创建实例，**但不加载权重**。
    ///
    /// 为什么要和 load() 分开，而不是像 llama 那样一次 load 到底：
    ///   MNN 的顺序约束是硬的 —— set_config() 必须在 load() **之前**生效，
    ///   否则配置会被加载期的默认值覆盖（上下文长度、backend、采样参数全落回默认）。
    ///   上游 Kotlin 侧的调用顺序就是 create → setConfig*（可能多次）→ load，
    ///   引擎必须如实暴露这个三段式，而不是假装成两段。
    bool createFromConfig(const std::string& configPath, std::string* err);

    /// 实例是否已创建（无论是否已加载权重）。
    bool created() const;

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

    /// MNN 的 response/generate 是**两段式**：第一次 response 会顺带采样出第 1 个
    /// token，之后每次 generate(1) 出一个 token。所以本引擎覆写 generate()，
    /// 把「reset → response(1) → generate(1) 循环」收在一处。
    bool generate(const std::string& prompt, const GenParams& params,
                  const Callbacks& cbs, std::string* err) override;
    bool generateFromHistory(const ChatMessages& history, const GenParams& params,
                             const Callbacks& cbs, std::string* err);

    /// 非流式一次性生成（对应 JNI 的 nativeGenerate）。
    /// 与 generate() 的差别**不是风格，是 MNN 的两条 API 路径**：
    ///   generate()        → response(tokens, os, "<eop>", 1) + 循环 generate(1)
    ///                        自己控制逐 token 节奏，可取消、有 `<eop>` 结束标记
    ///   generateToString()→ response(tokens, oss, **nullptr**, maxTokens)
    ///                        交给 MNN 内部循环跑完，不设结束标记
    /// 保留它是为了不让既有的 nativeGenerate 调用方行为漂移（旧实现就是这条路径）。
    bool generateToString(const std::string& prompt, int32_t maxTokens, std::string* out,
                          std::string* err);

    /// ⚠️ 分阶段调用的语义限制（如实说明，不假装能分离）：
    ///   MNN 把「预填充 prompt」与「采样出第 1 个 token」耦合在同一次 response() 里，
    ///   没有只做 prefill、不出 token 的 API。所以：
    ///     prefill() = reset() + response(count=1)，**会向 cbs 吐 1 个 token**；
    ///     decode()  = 继续 generate(1) 循环，必须在 prefill() 之后调用。
    ///   想要"一次跑完"请直接用 generate() —— 它才是主路径。
    bool prefill(const std::vector<int32_t>& promptTokens, const Callbacks& cbs,
                 std::string* err) override;
    bool decode(const GenParams& params, const Callbacks& cbs,
                std::string* err) override;

    /// 取消当前生成。任意线程可调；只置原子标志，由流式 sink 在每个 chunk 检查。
    void cancel() override;

    /// 清空 KV / 对话状态（MNN 的 Llm::reset）。
    bool resetKv(std::string* err) override;

    Stats stats() const override;
    Backend resolvedBackend(Phase phase) const override;

    // ─────────────── MNN 特有扩展（不在 Engine 契约内）───────────────
    // 这些是 MNN 的 API 形状带来的能力，llama 侧没有对应物，所以挂在具体引擎类上，
    // 不塞进 Engine 接口 —— 接口只放两个引擎都成立的东西。

    /// 注入配置（等价于 MNN 的 set_config）。可多次调用，必须在 load() 之前。
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

    /// 模型自带 chat template 渲染（单条 user 内容）。
    bool applyChatTemplate(const std::string& userContent, std::string* out,
                           std::string* err);
    /// 模型自带 chat template 渲染（多轮历史）。
    bool applyChatTemplateWithHistory(const ChatMessages& history, std::string* out,
                                      std::string* err);
    /// 结构化（工具调用）渲染：OpenAI 风格 messages/tools JSON → prompt。
    bool applyChatTemplateWithStructuredMessages(const std::string& messagesJson,
                                                 const std::string& toolsJson,
                                                 std::string* out, std::string* err);

    /// 渲染 + 分词后的 token 数（上下文预算用）。
    int32_t countTokensWithHistory(const ChatMessages& history, std::string* err);
    int32_t countTokensWithStructuredMessages(const std::string& messagesJson,
                                              const std::string& toolsJson,
                                              std::string* err);

    bool dumpConfig(std::string* out, std::string* err);
    bool contextInfoJson(std::string* out, std::string* err);

    /// 结构化流式生成（工具轮主路径）：渲染 → 分词 → 流式生成。
    /// 与 generateFromHistory 的区别是入口形态：这里收的是 OpenAI 风格 JSON。
    bool generateStructured(const std::string& messagesJson, const std::string& toolsJson,
                            const GenParams& params, const Callbacks& cbs,
                            std::string* err);

    /// TTS / 音频模型：让引擎吐出波形（经 WavformCallback）。
    bool generateWaveform(std::string* err);
    void setWavformCallback(WavformCallback cb);

    // ─────────────────────── 错误通道 ───────────────────────
    /// 最近一次失败的人类可读原因。格式：`错误码|英文补充说明`。
    ///
    /// 🔴 错误码字符串**是与 Kotlin 的契约**（`MnnNativeError` 负责翻译），
    ///    改动会让用户看到英文原文而不是本地化提示。稳定值：
    ///    E_MNN_NO_MESSAGES / E_MNN_BAD_MESSAGES / E_MNN_NO_VALID_MESSAGE /
    ///    E_MNN_TEMPLATE_THROW / E_MNN_TEMPLATE_EMPTY / E_MNN_EMPTY_TOKENS /
    ///    E_MNN_STREAM_THROW
    ///
    /// 旧实现把这张表放在**以裸 Llm* 为键的全局 map** 里，还专门写了
    /// "指针会被复用，残留的错误串会串味"的清理代码。现在它是实例字段，
    /// 句柄表保证了实例与句柄一一对应，串味在结构上不可能发生。
    std::string takeLastError();
    void setLastErrorForced(const std::string& msg);

private:
    /// 真正释放 Llm。**调用方必须已持有 impl_->lifecycle**。
    /// 拆出不加锁版本的原因同 llama 侧：load() 在持锁状态下要先把旧实例销毁，
    /// 若去调公开的 unload()（也要拿同一把锁）就是重入死锁 —— 无崩溃、无栈、
    /// 表现为"切换模型时永久卡住"。
    void releaseResources();

    /// 实现细节全部锁在 .cpp 里。本类对上层是一个不透明句柄。
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

// ---------------------------------------------------------------------------
// 模块级注册入口（实现见 mnn_engine_registry.cpp）
// ---------------------------------------------------------------------------
namespace registry {

/// 幂等。失败（工厂拒绝）返回 false，调用方只记日志、不让 .so 加载失败。
bool registerMnnEngine();

}  // namespace registry

}  // namespace llm
}  // namespace quro

#endif  // QURO_MNN_ENGINE_H
