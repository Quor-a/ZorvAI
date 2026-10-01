// =============================================================================
// L3 · 统一引擎抽象 —— 纯虚接口
// =============================================================================
//                        ┌──────────────────────────┐
//   上层（L1 编排 / L2 JNI）│      Engine (纯虚)        │
//                        └────────────┬─────────────┘
//                                     │ 按模型格式路由（engine_factory）
//                    ┌────────────────┴────────────────┐
//              MnnEngine                          LlamaEngine
//           (CPU/OpenCL/Vulkan/NNAPI)                (GGUF)
//
// 设计规则（不可违反）：
//   1. 本接口**不含任何引擎私有头文件**。llama.h / MNN 的头只允许出现在各自
//      实现文件里（llama_engine.cpp / mnn_engine.cpp），绝不外泄到公共头 ——
//      这就是「别直接暴露 llama.h」这条规则的落点：结构体里含 std::vector，
//      跨语言/跨 .so 传递必然 ABI 不匹配 → SIGABRT。
//   2. 所有方法**不抛异常**：错误经 std::string* err 回传。NDK 默认不保证
//      异常能穿过 JNI 帧，抛出去就是 std::terminate。
//   3. cancel() 必须可被**任意线程**调用，且必须让阻塞中的 decode 尽快返回 false。
//      这是「取消」这条链路唯一的正确实现方式（不是设个标志位然后等）。
//   4. 生命周期：load → (tokenize/prefill/decode)* → unload。
//      prefill/decode 可反复调用；resetKv() 清历史重新 prefill（无状态化）。
// =============================================================================
#ifndef QURO_LLM_ENGINE_H
#define QURO_LLM_ENGINE_H

#include <memory>
#include <string>
#include <vector>

#include "quro/engine_types.h"

namespace quro {
namespace llm {

class Engine {
public:
    virtual ~Engine() = default;

    // ── 身份与可用性 ────────────────────────────────────────────────────
    /// 引擎名（"mnn" / "llama.cpp"），用于日志、路由与内存互斥记录。
    virtual const char* name() const = 0;

    /// 本次构建是否编入了该引擎（full 风味才有）。
    /// 不可用时 whyNot 给出人话原因，便于上层直接提示用户。
    virtual bool available(std::string* whyNot) const = 0;

    // ── 生命周期 ────────────────────────────────────────────────────────
    /// 加载权重。成功返回 true；失败经 err 回传原因，且必须保证**没有半加载状态**
    /// （失败即内部自清理，不留 hanging 句柄 —— 否则内存互斥会误判仍被占用）。
    virtual bool load(const LoadConfig& cfg, std::string* err) = 0;

    /// 卸载并释放全部权重。可重复调用（幂等）。
    virtual bool unload() = 0;

    virtual bool loaded() const = 0;

    /// 权重占用字节数 —— 内存互斥（同一时刻只允许一个引擎持有大权重）的判据。
    virtual int64_t weightBytes() const = 0;

    // ── 分词 ────────────────────────────────────────────────────────────
    virtual bool tokenize(const std::string& text, std::vector<int32_t>* out,
                          std::string* err) = 0;
    virtual bool detokenize(const std::vector<int32_t>& tokens, std::string* out,
                            std::string* err) = 0;

    // ── 推理 ────────────────────────────────────────────────────────────
    /// 预填充：把 prompt token 喂进 KV cache。对应 Phase::PREFILL（可走 GPU）。
    virtual bool prefill(const std::vector<int32_t>& promptTokens,
                         const Callbacks& cbs, std::string* err) = 0;

    /// 逐 token 解码。对应 Phase::DECODE（默认 CPU，见 backend_policy）。
    /// 每产出一个 token 经 cbs.onToken 回吐；返回 true 表示正常结束（含自然 EOS）。
    virtual bool decode(const GenParams& params, const Callbacks& cbs,
                        std::string* err) = 0;

    /// 便捷组合：tokenize → prefill → decode。
    /// 默认实现按上述三步走；引擎如需特殊处理（例如 MNN 自带 chat template
    /// 与结构化生成）可覆写。
    virtual bool generate(const std::string& prompt, const GenParams& params,
                          const Callbacks& cbs, std::string* err);

    /// 取消当前 decode/prefill。**任意线程可调**，且必须立即让被取消的调用返回。
    virtual void cancel() = 0;

    /// 清空 KV cache 与历史（对齐「每轮无状态重 prefill」策略）。
    virtual bool resetKv(std::string* err) = 0;

    // ── 可观测性 ────────────────────────────────────────────────────────
    virtual Stats stats() const = 0;

    /// 本次加载实际生效的后端（AUTO 解析后的结果），供日志与 UI 显示。
    virtual Backend resolvedBackend(Phase phase) const = 0;
};

using EnginePtr = std::unique_ptr<Engine>;

}  // namespace llm
}  // namespace quro

#endif  // QURO_LLM_ENGINE_H
