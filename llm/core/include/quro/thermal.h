// =============================================================================
// L5 · 温控自适应调度
// =============================================================================
// 这是「真正决定流畅度的两个开关」之一，也是比语言选型影响更大的那一个：
//   持续推理 5–10 分钟后 SoC 必然降频。实测（骁龙 8 Gen 3）30 分钟裸跑吞吐掉 69%；
//   若每 2 秒读一次 thermal headroom 并**主动**降线程 / 降批大小，可以保住约 77% 峰值
//   （而不是掉到 31%）—— 因为主动降档是平滑的，被动降频是断崖式的。
//
// 为什么必须"主动"：
//   平台的 thermal throttling 是内核/固件行为，它降频时不会通知应用。
//   等我们发现变慢再反应，已经掉进断崖。唯一的办法是**提前**看 headroom（余量），
//   在还有余量时就小幅让步，把温度压在阈值下方，避免触发断崖。
//
// 数据来源（两级降级，都有才最稳）：
//   1) NDK AThermal（libandroid.so，API 30+）：官方 headroom 0.0–1.0 + status 枚举。
//      用 dlopen/dlsym 取，避免 API 26–29 上链接失败。
//   2) sysfs /sys/class/thermal/thermal_zone* ：读温度，按经验区间折算伪 headroom。
//      （部分机型无权限，读到空就退化为「不干预」。）
//
// 设计要点：
//   · 带**滞回**（hysteresis）：降档阈值与升回阈值不同，防止在边界反复抖动
//     （抖动比一直低档更慢，因为每次切换都要重建线程池/改批大小）。
//   · 只在**阶段边界**应用新档位（prefill 之间 / decode 每 N token），
//     不在一个 batch 中途改线程数 —— 那会破坏 ggml/MNN 的线程池假设。
//   · 永不把线程数降到 1 以下、批降到 1 以下。
// =============================================================================
#ifndef QURO_LLM_THERMAL_H
#define QURO_LLM_THERMAL_H

#include <cstdint>
#include <string>

namespace quro {
namespace llm {

/// 热档位。数值越大越热。
enum class ThermalLevel : int32_t {
    UNKNOWN = -1,   // 探测不可用 → 不干预
    COOL = 0,       // 充分余量，可以用上限配置
    WARM = 1,       // 轻微升温，收一点线程
    HOT = 2,        // 接近降频，明显收线程 + 收批
    CRITICAL = 3,   // 已在降频区，保命档：最小线程 + 最小批
};

const char* thermalLevelName(ThermalLevel lv);

/// 温控建议：给资源层/引擎的下一次加载或下一段解码使用的参数。
struct ThermalAdvice {
    ThermalLevel level = ThermalLevel::UNKNOWN;
    int threads = 0;          // 建议线程数（0 = 不干预，用原配置）
    int batch = 0;            // 建议批大小（0 = 不干预）
    int headroomPct = 100;    // 0..100
    int platformStatus = 0;   // AThermal status 原始值（0=none .. 6=shutdown）
    bool throttled = false;   // 是否判定为已进入降频保护
    std::string reason;       // 人话原因，直接可进日志/UI
};

/**
 * 全局温控状态机。
 *
 * 单例：温控是**设备级**状态，不是某个会话的属性。:llm 进程内所有引擎共享同一份。
 */
class ThermalGovernor {
public:
    static ThermalGovernor& instance();

    /// 配置并启动采样（幂等）。pollMs 建议 2000（用户规格：每 2 秒一次）。
    void configure(int pollMs, int baseThreads, int baseBatch);

    /// 采样一次并推进状态机。可被任意线程调用（内部加锁）。
    /// 引擎在 prefill 边界 / decode 每 N token 调一次；也可由独立线程按周期调。
    ThermalAdvice sample();

    /// 取上次采样结果，不触发新采样（廉价，供 stats 上报）。
    ThermalAdvice last() const;

    /// 探测能力：true 表示至少有一种温度数据来源可用。
    bool probeAvailable() const;

    /// 当前建议线程数（已按基础配置 clamp，永不 < 1）。
    int advisedThreads() const;
    /// 当前建议批大小（永不 < 1）。
    int advisedBatch() const;

    /// 是否已经连续处于 HOT/CRITICAL（用于向用户解释"为什么变慢了"）。
    bool sustainedThrottle() const;

    /// 复位（换模型 / 用户手动重载时调）。
    void reset();

private:
    ThermalGovernor();
    ~ThermalGovernor();
    ThermalGovernor(const ThermalGovernor&) = delete;
    ThermalGovernor& operator=(const ThermalGovernor&) = delete;

    struct Impl;
    mutable Impl* impl_;
};

/// 读一次原始温度（摄氏度）。返回 -1 表示读不到。
/// 独立暴露便于诊断页直接展示。
double readSocTemperatureCelsius();

}  // namespace llm
}  // namespace quro

#endif  // QURO_LLM_THERMAL_H
