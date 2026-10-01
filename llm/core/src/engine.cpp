// =============================================================================
// L3 · Engine 基类默认实现
// =============================================================================
// 只有 generate() 提供默认实现（tokenize → prefill → decode 三步组合）。
// 其余全纯虚：每个引擎的加载/采样/取消语义差异太大，强行给默认实现只会掩盖问题。
//
// 为什么 generate() 给默认实现：绝大多数引擎的三步组合是一样的，
// 让每个引擎各写一遍等于给了三次写错的机会（尤其是"prefill 后没清 token 缓冲"
// 这类错误，会表现为首 token 是重复文本，很难查）。
// 需要特殊处理的引擎（如 MNN 自带 chat template + 结构化生成）自行覆写。
// =============================================================================
#include "quro/engine.h"

#include <android/log.h>

#include <chrono>

#define QLOG_TAG "QuroLlm.Engine"
#define QLOGI(...) __android_log_print(ANDROID_LOG_INFO, QLOG_TAG, __VA_ARGS__)

namespace quro {
namespace llm {

namespace {
int64_t nowMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}
}  // namespace

bool Engine::generate(const std::string& prompt, const GenParams& params,
                      const Callbacks& cbs, std::string* err) {
    if (!loaded()) {
        if (err) *err = "引擎尚未加载模型，无法推理。";
        return false;
    }
    if (prompt.empty() && params.maxTokens <= 0) {
        if (err) *err = "prompt 为空且 maxTokens<=0，没有可执行的工作。";
        return false;
    }

    const int64_t t0 = nowMs();

    // ① 分词
    std::vector<int32_t> tokens;
    if (!prompt.empty()) {
        if (!tokenize(prompt, &tokens, err)) return false;
    }
    const int64_t tTokenize = nowMs();

    // ② 预填充（Phase::PREFILL —— 可能走 GPU）
    if (!tokens.empty()) {
        if (!prefill(tokens, cbs, err)) return false;
    }
    const int64_t tPrefill = nowMs();

    // ③ 逐 token 解码（Phase::DECODE —— 默认 CPU）
    if (params.maxTokens > 0) {
        if (!decode(params, cbs, err)) return false;
    }
    const int64_t tDecode = nowMs();

    QLOGI("generate 完成 | tokenize=%lldms prefill=%lldms(%zu tok) decode=%lldms",
          static_cast<long long>(tTokenize - t0),
          static_cast<long long>(tPrefill - tTokenize), tokens.size(),
          static_cast<long long>(tDecode - tPrefill));
    return true;
}

}  // namespace llm
}  // namespace quro
