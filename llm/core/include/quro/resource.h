// =============================================================================
// L5 · 系统资源层 —— 线程绑定 / 内存预算 / mmap 策略
// =============================================================================
// 这一层管的是「引擎不该自己操心、但错了就会慢或崩」的事：
//
//   1. 线程与大小核
//      手机 SoC 是 big.LITTLE。推理线程若被调度到小核，吞吐直接腰斩。
//      本层识别大核簇并把推理线程的亲和性钉上去（sched_setaffinity）。
//      注意：不是钉死单核，而是**排除小核**，保留大核间自由迁移。
//
//   2. 内存预算
//      4GB 量化模型加载后 native heap 可飙到 3.8GB，低端机 LMK 几十秒内必杀。
//      本层给出「这台设备现在还能给推理多少」的保守估算（基于 MemAvailable），
//      引擎据此决定 mmap 还是整读、以及是否降 nCtx。
//
//   3. mmap 策略
//      权重走 mmap 有三个直接收益：不占匿名内存（对 LMK 友好）、按需分页（首 token 更快）、
//      多进程共享同一份 page cache。代价是首次触摸有缺页开销 —— 对端侧 LLM 完全值得。
//
// 所有探测都做失败降级：读不到就返回保守默认值，绝不因此让推理失败。
// =============================================================================
#ifndef QURO_LLM_RESOURCE_H
#define QURO_LLM_RESOURCE_H

#include <cstdint>
#include <string>
#include <vector>

namespace quro {
namespace llm {

struct DeviceProfile {
    int totalCores = 0;
    int bigCores = 0;             // 大核数量（识别失败时 == totalCores）
    std::vector<int> bigCoreIds;  // 大核逻辑核编号
    int64_t maxBigCoreKhz = 0;
    int64_t memAvailableBytes = 0;
    int64_t memTotalBytes = 0;

    std::string describe() const;
};

/**
 * 系统资源层。无状态探测（每次读内核接口），因此不需要单例；
 * 但结果做了短时缓存（默认 1 秒），避免 decode 循环里每 token 都读文件。
 */
class Resource {
public:
    /// 探测设备画像（带 1 秒缓存）。
    static DeviceProfile profile();

    /// 强制重新探测（机型热插拔/配置变更后）。
    static void invalidate();

    // ── 线程 ────────────────────────────────────────────────────────────
    /**
     * 给「当前线程」设置 CPU 亲和性，排除小核。
     * 返回 true 表示成功设置（内核不允许时返回 false，调用方忽略即可）。
     * 说明：必须在**推理工作线程内**调用，对主线程调用无效。
     */
    static bool bindCurrentThreadToBigCores();

    /// 解除本线程的亲和性限制（恢复全核可调度）。
    static bool unbindCurrentThread();

    /// 推荐线程数：大核数 - 1（留一核给 UI/系统），下限 1。
    static int recommendThreads();

    /**
     * 推荐线程数（带温控上限）。
     * thermalCap <= 0 表示不加限制。
     */
    static int recommendThreads(int thermalCap);

    /// 给推理线程设一个略低于默认的 nice 值（-5），提升被调度优先级。
    /// 需要权限，失败静默忽略（不影响正确性）。
    static bool raiseCurrentThreadPriority();

    // ── 内存 ────────────────────────────────────────────────────────────
    /// 当前 MemAvailable（字节）。读不到返回 0。
    static int64_t memAvailableBytes();

    /// 本进程当前 RSS（字节）。读不到返回 0。
    static int64_t currentRssBytes();

    /// 本进程历史峰值 RSS（字节，取 /proc/self/status 的 VmHWM）。
    static int64_t peakRssBytes();

    /**
     * 推理可用的内存预算（字节）。
     *
     * 策略：取 MemAvailable 的 55%。留 45% 给
     *   · 本进程其余部分（UI、WebView、缩略图）
     *   · 系统与其他应用（被 LMK 盯上的从来不是"用了多少"，而是"还够不够别人用"）
     *   · 推理过程中的临时缓冲（KV cache 增长、batch 中间张量）
     * 下限 256MB，上限 12GB（防止异常值）。
     */
    static int64_t memoryBudgetBytes();

    /// 该预算是否够装下 sizeBytes 的模型；不够时 whyNot 给出人话原因。
    static bool budgetAllows(int64_t sizeBytes, std::string* whyNot);

    // ── 页与对齐 ────────────────────────────────────────────────────────
    /// 系统页大小（Android 15+ 有 16KB 机型，mmap 对齐必须用它）。
    static int64_t pageSize();

    /// 把 size 向上对齐到页大小。
    static int64_t alignToPage(int64_t size);

    /// 文件是否适合 mmap（存在、普通文件、大小 > 0）。
    static bool canMmap(const std::string& path, int64_t* sizeOut);
};

}  // namespace llm
}  // namespace quro

#endif  // QURO_LLM_RESOURCE_H
