// =============================================================================
// L3 · 统一引擎抽象 —— 类型定义
// =============================================================================
// 这里是 MNN 与 llama.cpp 的**公共契约**。上层只认这些类型，不认任何引擎私有结构。
//
// 为什么必须这样切：
//   llama.h / MNN 的 C++ API 里有 std::vector、std::string 等 C++ 对象。
//   一旦把它们摊在 JNI 边界上（按值/按引用跨语言传递），就要求两侧 ABI、STL 实现、
//   分配器完全一致 —— 任何一处不匹配就是 SIGABRT（"vector::_M_realloc_insert" 类崩溃）。
//   本层的规矩：**C++ 对象只在 C++ 内部流动**；跨 JNI 只走 jlong 句柄 + 基础类型
//   （见 handle_registry.h）。
//
// 语言：C++17（NDK r27，libc++）
// =============================================================================
#ifndef QURO_LLM_ENGINE_TYPES_H
#define QURO_LLM_ENGINE_TYPES_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace quro {
namespace llm {

// ---------------------------------------------------------------------------
// 后端（L4 的加速器选择）
// ---------------------------------------------------------------------------
enum class Backend : int32_t {
    AUTO = 0,    // 交给引擎自己挑（MNN 默认策略 / ggml 默认）
    CPU = 1,     // 永远安全，decode 阶段的默认选择
    OPENCL = 2,
    VULKAN = 3,
    OPENGL = 4,  // MNN 独有
    NNAPI = 5,   // MNN 独有
    COREML = 6,  // 仅 iOS，本工程不用，占位保证枚举稳定
};

const char* backendName(Backend b);

// ---------------------------------------------------------------------------
// 推理阶段 —— 决定用哪个后端
// ---------------------------------------------------------------------------
// ★ 这是「两个决定流畅度的开关」之一的落点：
//   prefill 是大 batch 的**计算受限**阶段，GPU 有优势；
//   decode 是 batch=1 的**显存带宽受限**阶段，实测（骁龙 8 Elite）llama.cpp 的
//   OpenCL 反而比 CPU 慢 —— 一刀切用 GPU 会让逐字输出变卡。
//   所以后端必须**按阶段**选，不能全局一刀切。
// ---------------------------------------------------------------------------
enum class Phase : int32_t {
    PREFILL = 0,
    DECODE = 1,
};

const char* phaseName(Phase p);

// ---------------------------------------------------------------------------
// 加载配置
// ---------------------------------------------------------------------------
struct LoadConfig {
    std::string modelPath;        // GGUF 文件（llama）或模型目录（MNN）

    int nCtx = 2048;              // 上下文窗口
    int nThreads = 0;             // 0 = 由 resource 层按设备自动定
    int nBatch = 512;             // prefill 批大小
    int nUBatch = 512;            // 微批
    int nGpuLayers = 0;           // llama: offload 到 GPU 的层数；MNN 忽略

    bool useMmap = true;          // 权重 mmap（省内存、加快加载）
    bool flashAttention = true;
    bool kvUnified = true;
    bool offloadKqv = false;

    // 后端分离策略：prefill 可上 GPU，decode 默认回 CPU
    Backend prefillBackend = Backend::AUTO;
    Backend decodeBackend = Backend::CPU;

    // L5 资源约束
    int64_t memBudgetBytes = 0;   // 0 = 自动探测（MemAvailable 的一定比例）
    int thermalPollMs = 2000;     // 温控采样周期（用户规格：每 2 秒一次）
    bool adaptiveThermal = true;  // 是否允许温控动态降线程/降批
    bool bindBigCores = true;     // 是否把推理线程绑到大核
};

// ---------------------------------------------------------------------------
// 生成参数
// ---------------------------------------------------------------------------
struct GenParams {
    int maxTokens = 512;
    float temperature = 0.8f;
    float topP = 0.9f;
    int topK = 40;
    float repetitionPenalty = 1.1f;
    int32_t seed = -1;            // -1 = 随机

    // 结构化输出（工具调用）：两者都为空表示自由文本
    std::string toolsJson;        // 工具定义（引擎各自转成自己的文法/模板）
    std::string grammar;          // 显式 GBNF / 语法（llama 支持）
};

// ---------------------------------------------------------------------------
// 流式回调载荷
// ---------------------------------------------------------------------------
// text 指向的缓冲**仅在该次回调有效**，回调返回后即失效 ——
// 上层要留存必须自己拷贝。这样避免每 token 一次堆分配。
struct TokenChunk {
    const char* text = nullptr;
    size_t len = 0;
    bool isThinking = false;      // 思考段（<think>）与正文分流
};

// ---------------------------------------------------------------------------
// 运行统计（L5 可观测性）
// ---------------------------------------------------------------------------
struct Stats {
    double loadMs = 0.0;
    double prefillMs = 0.0;
    double decodeMs = 0.0;

    int64_t prefillTokens = 0;
    int64_t decodeTokens = 0;

    double prefillTps = 0.0;      // tokens/s
    double decodeTps = 0.0;

    // 温控可观测性：这两个值就是「有没有真的启动降频保护」的证据
    int thermalHeadroomPct = 100; // 0..100，越低越接近降频
    int thermalStatus = 0;        // 平台 thermal status 原始值
    bool thermallyThrottled = false;
    int activeThreads = 0;
    int activeBatch = 0;

    Backend prefillBackendUsed = Backend::CPU;
    Backend decodeBackendUsed = Backend::CPU;

    int64_t weightBytes = 0;      // 权重占用（内存互斥判据）
    int64_t peakRssBytes = 0;     // 进程峰值 RSS
};

// ---------------------------------------------------------------------------
// 回调类型
// ---------------------------------------------------------------------------
// 用裸函数指针 + void* 上下文，而不是 std::function：
//   回调可能从引擎内部工作线程触发，裸指针没有堆分配、没有异常、没有 STL 依赖，
//   在 ABI 边界上最稳。上层（JNI）自己决定怎么把 user 转回 JavaVM/全局引用。
using TokenFn = void (*)(void* user, const TokenChunk& chunk);
/// 进度载荷。stage 指向的缓冲**仅在该次回调有效**，与 TokenChunk 同规矩。
struct ProgressChunk {
    const char* stage = nullptr;   // 阶段名，如 "prefill"
    int current = 0;               // 已处理 token 数（不是百分比）
    int total = 0;                 // 本轮要处理的 token 总数
};

/// 不要把它压成百分比：上层要按 token 数决定「值不值得显示进度条」
/// （多轮对话只新增几十 token 时不该弹进度），百分比反推 token 数会失真。
using ProgressFn = void (*)(void* user, const ProgressChunk& chunk);
/// 拉取式停止询问：返回 true 表示「不要再产出了，收尾吧」。
/// 引擎应在每个 token / chunk 边界调用一次；为 nullptr 时视为永不停止。
using StopFn = bool (*)(void* user);

struct Callbacks {
    TokenFn onToken = nullptr;
    ProgressFn onProgress = nullptr;
    /// 与 cancel() 的分工：
    ///   cancel()      —— 外部单方面终止（UI 点停止、卸载模型）
    ///   shouldStop()  —— 接收方请求终止（如 JNI 层把 token 交给 Java 后，
    ///                    Java 返回 false 表示"我不要了"）
    /// 两者在引擎里走**同一条 break 路径**，所以不需要在 onToken 的返回值里
    /// 再塞一层控制流 —— 回调保持 void，语义单一。
    StopFn shouldStop = nullptr;
    void* user = nullptr;

    bool valid() const { return onToken != nullptr; }

    /// 接收方是否已请求停止。nullptr 安全。
    bool stopped() const { return shouldStop != nullptr && shouldStop(user); }
};

}  // namespace llm
}  // namespace quro

#endif  // QURO_LLM_ENGINE_TYPES_H
