// =============================================================================
// L4 · 后端选择策略实现
// =============================================================================
#include "quro/backend_policy.h"

#include <android/log.h>

#include <sstream>

#define QLOG_TAG "QuroLlm.Backend"
#define QLOGI(...) __android_log_print(ANDROID_LOG_INFO, QLOG_TAG, __VA_ARGS__)

namespace quro {
namespace llm {

const char* backendName(Backend b) {
    switch (b) {
        case Backend::AUTO: return "auto";
        case Backend::CPU: return "cpu";
        case Backend::OPENCL: return "opencl";
        case Backend::VULKAN: return "vulkan";
        case Backend::OPENGL: return "opengl";
        case Backend::NNAPI: return "nnapi";
        case Backend::COREML: return "coreml";
        default: return "unknown";
    }
}

const char* phaseName(Phase p) {
    switch (p) {
        case Phase::PREFILL: return "prefill";
        case Phase::DECODE: return "decode";
        default: return "unknown";
    }
}

namespace {

bool usable(Backend b, const BackendCaps& caps) {
    switch (b) {
        case Backend::CPU: return true;
        case Backend::OPENCL: return caps.opencl;
        case Backend::VULKAN: return caps.vulkan;
        case Backend::OPENGL: return caps.opengl;
        case Backend::NNAPI: return caps.nnapi;
        case Backend::COREML: return false;  // 本工程不涉及
        default: return false;
    }
}

}  // namespace

Backend BackendPolicy::recommend(Phase phase, const BackendCaps& caps) {
    if (phase == Phase::DECODE) {
        // decode 是 batch=1、带宽受限。GPU 在这里通常**更慢**：
        //   · 每步只算 1 个 token，矩阵乘规模小，kernel launch 开销占比高；
        //   · 权重常驻显存/在 CPU 与 GPU 间往返，带宽成为瓶颈；
        //   · 实测骁龙 8 Elite 上 llama.cpp 的 OpenCL 后端 decode 慢于 CPU。
        // 所以默认 CPU —— 这是「别一刀切」这条经验的核心。
        return Backend::CPU;
    }
    // prefill 是大 batch 的计算受限阶段，GPU 的并行吞吐能显著缩短首 token 时间。
    // 按 Vulkan → OpenCL 顺序偏好：Vulkan 在 Android 上驱动支持更统一、开销更低。
    if (caps.vulkan) return Backend::VULKAN;
    if (caps.opencl) return Backend::OPENCL;
    if (caps.nnapi) return Backend::NNAPI;
    if (caps.opengl) return Backend::OPENGL;
    return Backend::CPU;
}

Backend BackendPolicy::resolve(Phase phase, Backend requested, const BackendCaps& caps) {
    if (requested == Backend::AUTO) {
        return recommend(phase, caps);
    }
    if (usable(requested, caps)) {
        return requested;
    }
    // 用户显式指定了但本次构建/本机不可用 → 降级 CPU 并明确告知。
    const Backend fallback = Backend::CPU;
    QLOGI("后端降级：阶段=%s 请求=%s 不可用 → 使用 %s", phaseName(phase),
          backendName(requested), backendName(fallback));
    return fallback;
}

bool BackendPolicy::shouldUseGpuForPrefill(int promptTokens, bool gpuWarm) {
    // 已经热过（本次会话之前用过 GPU）→ 无初始化开销，短 prompt 也走 GPU。
    if (gpuWarm) return promptTokens > 0;
    // 冷启动：GPU 首次要建上下文 / 编译 kernel，约 1–3 秒。
    // prompt ≥ 256 token 时，GPU 省下的 prefill 时间才开始明显超过这笔开销。
    return promptTokens >= 256;
}

std::string BackendPolicy::explain(Phase phase, Backend resolved,
                                   const BackendCaps& caps) {
    std::ostringstream os;
    os << phaseName(phase) << " → " << backendName(resolved);
    if (phase == Phase::DECODE && resolved == Backend::CPU) {
        os << "（decode batch=1 属带宽受限，GPU 反而更慢，故走 CPU）";
    } else if (phase == Phase::PREFILL && resolved != Backend::CPU) {
        os << "（prefill 属计算受限，大 batch 交给 GPU）";
    } else if (resolved == Backend::CPU) {
        os << "（本机无可用 GPU 后端，或初始化为冷启动）";
    }
    os << " | 可用: cpu";
    if (caps.opencl) os << ",opencl";
    if (caps.vulkan) os << ",vulkan";
    if (caps.opengl) os << ",opengl";
    if (caps.nnapi) os << ",nnapi";
    return os.str();
}

}  // namespace llm
}  // namespace quro
