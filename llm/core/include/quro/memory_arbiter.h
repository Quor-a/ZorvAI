// =============================================================================
// L5 · 内存互斥仲裁 —— 「双引擎不做内存共存」
// =============================================================================
// 硬规则第 3 条：同一时刻只让一个引擎持有大模型权重，切换即 unload。
//
// 为什么这条是硬规则而不是优化：
//   MNN 与 llama.cpp 的量化权重各 2–3GB。两个同驻 = 5GB+ 峰值。
//   在 8GB 机型上这不是"慢一点"，而是**必然被 LMK 杀掉**，
//   而且是在推理中途被杀 —— 用户看到的是"聊到一半 App 没了"。
//   更糟的是：两个引擎各自的 GPU 后端（OpenCL/Vulkan）会同时申请显存，
//   触发驱动层分配失败 → native crash，比 LMK 更难诊断。
//
// 本层的语义：
//   · acquire() 是**排他**的。已有持有者时，要么按策略驱逐它，要么直接失败。
//   · 驱逐走持有者自己注册的 evictor（只有引擎自己知道怎么正确 unload）。
//   · release() 必须幂等：引擎 unload 失败也要能调，否则会永久卡住切换。
//   · 每次驱逐都计数。频繁驱逐 = 上层在反复切引擎，是设计问题，日志里必须显形。
//
// 为什么放在 :llm 独立进程：即便万一这里判漏了，代价也只是 :llm 自己被杀，
// 主进程 UI 不受影响（这正是进程隔离要买的保险）。
// =============================================================================
#ifndef QURO_LLM_MEMORY_ARBITER_H
#define QURO_LLM_MEMORY_ARBITER_H

#include <cstdint>
#include <functional>
#include <string>

namespace quro {
namespace llm {

/// 驱逐回调：返回 true 表示成功释放了权重。
using Evictor = std::function<bool()>;

struct WeightHolder {
    std::string owner;   // 空 = 无人持有
    int64_t bytes = 0;
    bool isEmpty() const { return owner.empty(); }
};

class MemoryArbiter {
public:
    static MemoryArbiter& instance();

    /**
     * 申请独占地持有大权重。
     *
     * @param owner      持有者标识（引擎名 + 模型路径摘要），用于日志与诊断。
     * @param bytes      预计权重占用字节。
     * @param evictor    当需要驱逐既有持有者时，用它来释放（可为空 → 不允许驱逐）。
     * @param allowEvict true = 可以驱逐既有持有者；false = 有人持有时直接失败。
     * @param err        失败原因（人话，可直接展示）。
     * @return true 表示现在可以安全加载权重。
     */
    bool acquire(const std::string& owner, int64_t bytes, const Evictor& evictor,
                 bool allowEvict, std::string* err);

    /// 释放持有权。幂等；owner 不匹配时不做任何事（防止误释放别人的）。
    void release(const std::string& owner);

    /// 当前持有者。
    WeightHolder holder() const;

    /// 是否无人持有权重。
    bool idle() const;

    /// 累计驱逐次数。
    int64_t evictCount() const;

    /// 复位（进程重启/测试用）。
    void reset();

    /// 人类可读状态，供诊断页直接显示。
    std::string describe() const;

private:
    MemoryArbiter();
    ~MemoryArbiter();
    MemoryArbiter(const MemoryArbiter&) = delete;
    MemoryArbiter& operator=(const MemoryArbiter&) = delete;

    struct Impl;
    Impl* impl_;
};

}  // namespace llm
}  // namespace quro

#endif  // QURO_LLM_MEMORY_ARBITER_H
