// =============================================================================
// L4 · 后端选择策略 —— 「后端别一刀切」
// =============================================================================
// 用户规格原话：decode 阶段 batch_size=1 是**带宽受限**的，实测在骁龙 8 Elite 上
// llama.cpp 的 OpenCL 反而比 CPU 慢。prefill 走 GPU、decode 走 CPU 才是正解。
//
// 本层把这句经验固化成可复用策略，避免每个引擎各自瞎猜：
//
//            计算受限                带宽受限
//   prefill  batch=512+   ──→  GPU 有优势（矩阵乘吞吐）
//   decode   batch=1      ──→  CPU 更优（kernel launch 开销 + 显存往返吃掉收益）
//
//   还要叠加一个现实约束：GPU 后端首次初始化要 1–3 秒（编译 kernel / 建上下文）。
//   如果一次会话只生成几十个 token，GPU 的初始化开销都收不回来 —— 所以本层还提供
//   `shouldUseGpuForPrefill(promptTokens, ...)` 做收益门槛判断。
//
// 用户显式指定后端时**尊重用户**（除非法不可用），策略只负责"没指定时给对的默认"。
// =============================================================================
#ifndef QURO_LLM_BACKEND_POLICY_H
#define QURO_LLM_BACKEND_POLICY_H

#include <string>

#include "quro/engine_types.h"

namespace quro {
namespace llm {

/// 引擎自报的可用后端集合（运行时探测结果，非编译期）。
struct BackendCaps {
    bool cpu = true;             // 永远为真
    bool opencl = false;
    bool vulkan = false;
    bool opengl = false;
    bool nnapi = false;
};

class BackendPolicy {
public:
    /**
     * 解析某阶段实际要用的后端。
     *
     * @param phase     推理阶段
     * @param requested 上层显式请求（AUTO = 交给策略决定）
     * @param caps      引擎自报的可用后端
     * @return 实际可用的后端；请求的后端不可用时降级到 CPU（并应记日志）。
     */
    static Backend resolve(Phase phase, Backend requested, const BackendCaps& caps);

    /// 策略默认值：prefill → 优先 GPU；decode → CPU。
    static Backend recommend(Phase phase, const BackendCaps& caps);

    /**
     * prefill 是否值得上 GPU。
     *
     * GPU 首次初始化成本约 1–3 秒（各家不同，这里按保守 1.5 秒估）。
     * 经验阈值：prompt 少于约 256 token 时，GPU 省下的时间抵不过初始化开销。
     * 一旦同一次会话里已经用 GPU 做过 prefill（gpuWarm=true），后续短 prompt 也值得走 GPU。
     */
    static bool shouldUseGpuForPrefill(int promptTokens, bool gpuWarm);

    /// 人话解释（直接进日志/诊断页）。
    static std::string explain(Phase phase, Backend resolved, const BackendCaps& caps);
};

}  // namespace llm
}  // namespace quro

#endif  // QURO_LLM_BACKEND_POLICY_H
