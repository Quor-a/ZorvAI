// =============================================================================
// L5 · 温控自适应调度 —— 实现
// =============================================================================
#include "quro/thermal.h"

#include <android/log.h>
#include <dlfcn.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define QLOG_TAG "QuroLlm.Thermal"
#define QLOGI(...) __android_log_print(ANDROID_LOG_INFO, QLOG_TAG, __VA_ARGS__)
#define QLOGW(...) __android_log_print(ANDROID_LOG_WARN, QLOG_TAG, __VA_ARGS__)

namespace quro {
namespace llm {

// ─────────────────────────── AThermal 动态绑定 ───────────────────────────
// 不直接 include <android/thermal.h> 也不用链接期符号：API 26–29 上这些符号不存在，
// 硬链接会让整个 .so 在旧机型上加载失败（UnsatisfiedLinkError）。
// dlopen + dlsym 拿不到就自然降级到 sysfs。
namespace {

// AThermal_getCurrentThermalStatus 的官方档位表（NDK API 30+）。
// 标记 [[maybe_unused]] 是刻意的：本文件只比较 >= MODERATE 与 >= SEVERE 两个阈值，
// 但把完整档位留着，读者才能看出「3 = SEVERE」不是随手挑的数 ——
// 档位含义变了（或中间插档）时也能一眼定位要改哪。
constexpr int kStatusNone [[maybe_unused]]      = 0;
constexpr int kStatusLight [[maybe_unused]]     = 1;
constexpr int kStatusModerate [[maybe_unused]]  = 2;
constexpr int kStatusSevere [[maybe_unused]]    = 3;
constexpr int kStatusCritical [[maybe_unused]]  = 4;
constexpr int kStatusEmergency [[maybe_unused]] = 5;
constexpr int kStatusShutdown [[maybe_unused]]  = 6;

using AThermalManager = void;
using FnAcquire = AThermalManager* (*)();
using FnHeadroom = float (*)(AThermalManager*, int);
using FnStatus = int (*)(AThermalManager*);

struct AThermalApi {
    bool tried = false;
    bool ok = false;
    AThermalManager* mgr = nullptr;
    FnHeadroom headroom = nullptr;
    FnStatus status = nullptr;

    void ensure() {
        if (tried) return;
        tried = true;
        void* h = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (h == nullptr) {
            QLOGI("libandroid.so 不可用，AThermal 停用");
            return;
        }
        auto acquire = reinterpret_cast<FnAcquire>(dlsym(h, "AThermal_acquireManager"));
        headroom = reinterpret_cast<FnHeadroom>(
            dlsym(h, "AThermal_getThermalHeadroom"));
        status = reinterpret_cast<FnStatus>(dlsym(h, "AThermal_getCurrentThermalStatus"));
        if (acquire == nullptr || headroom == nullptr) {
            QLOGI("AThermal 符号缺失（API<30），改用 sysfs");
            return;
        }
        mgr = acquire();
        if (mgr == nullptr) {
            QLOGI("AThermal_acquireManager 返回空");
            return;
        }
        ok = true;
        QLOGI("AThermal 已就绪");
    }
};

AThermalApi& aThermalApi() {
    static AThermalApi api;
    return api;
}

/// 从 sysfs 扫描最热的温度区，返回摄氏度；读不到返回 -1。
double scanSysfsMaxTemp() {
    const char* kRoot = "/sys/class/thermal";
    DIR* dir = opendir(kRoot);
    if (dir == nullptr) return -1.0;

    double maxC = -1.0;
    struct dirent* ent = nullptr;
    while ((ent = readdir(dir)) != nullptr) {
        // 只看 thermal_zone*
        if (strncmp(ent->d_name, "thermal_zone", 12) != 0) continue;

        std::string base = std::string(kRoot) + "/" + ent->d_name;

        // 只统计 SoC 相关区（cpu/gpu/soc/big/little）。电池/充电口的温度
        // 不代表算力余量，混进来会误判。
        std::string typePath = base + "/type";
        char typeBuf[64] = {0};
        if (FILE* f = fopen(typePath.c_str(), "r")) {
            if (fgets(typeBuf, sizeof(typeBuf), f) == nullptr) typeBuf[0] = '\0';
            fclose(f);
        }
        std::string type(typeBuf);
        bool relevant = false;
        for (const char* kw : {"cpu", "gpu", "soc", "big", "little", "tsens", "apc"}) {
            if (type.find(kw) != std::string::npos) {
                relevant = true;
                break;
            }
        }
        if (!relevant) continue;

        std::string tempPath = base + "/temp";
        FILE* f = fopen(tempPath.c_str(), "r");
        if (f == nullptr) continue;
        long raw = 0;
        const bool got = (fscanf(f, "%ld", &raw) == 1);
        fclose(f);
        if (!got) continue;

        // 单位不统一：多数是 0.001℃，少数直接给 ℃。> 1000 视为毫摄氏度。
        double c = (raw > 1000) ? (static_cast<double>(raw) / 1000.0)
                                : static_cast<double>(raw);
        if (c > maxC) maxC = c;
    }
    closedir(dir);
    return maxC;
}

/// 温度 → 伪 headroom（0.0–1.0）。
/// 阈值取业界常见区间：45℃ 以下满余量，75℃ 起进入危险，90℃ 视为毫无余量。
float tempToPseudoHeadroom(double c) {
    if (c < 0.0) return -1.0f;
    constexpr double kCoolC = 45.0;
    constexpr double kHotC = 90.0;
    if (c <= kCoolC) return 1.0f;
    if (c >= kHotC) return 0.0f;
    return static_cast<float>((kHotC - c) / (kHotC - kCoolC));
}

}  // namespace

const char* thermalLevelName(ThermalLevel lv) {
    switch (lv) {
        case ThermalLevel::COOL: return "COOL";
        case ThermalLevel::WARM: return "WARM";
        case ThermalLevel::HOT: return "HOT";
        case ThermalLevel::CRITICAL: return "CRITICAL";
        default: return "UNKNOWN";
    }
}

double readSocTemperatureCelsius() { return scanSysfsMaxTemp(); }

// ─────────────────────────── 状态机 ───────────────────────────
struct ThermalGovernor::Impl {
    mutable std::mutex mtx;
    bool configured = false;
    int pollMs = 2000;
    int baseThreads = 4;
    int baseBatch = 512;
    int64_t lastSampleMs = 0;
    ThermalAdvice current;
    int consecutiveHot = 0;

    // 档位映射：headroom 越低，线程/批收得越狠。
    // 注意这些是**建议值**，真正的 clamp 在 advisedThreads/Batch 里做。
    ThermalAdvice evaluate(int headroomPct, int platformStatus, const std::string& why) {
        ThermalAdvice a;
        a.headroomPct = headroomPct;
        a.platformStatus = platformStatus;
        a.reason = why;

        // 平台 status 优先判定：>= MODERATE 视为已进入降频保护。
        if (platformStatus >= kStatusModerate) a.throttled = true;
        if (headroomPct <= 20) a.throttled = true;

        // 滞回：升档门槛高于降档门槛，避免在边界抖动。
        // 当前档位在 current.level，先用带滞回的阈值算出目标档。
        ThermalLevel prev = current.level;
        ThermalLevel target;
        if (headroomPct >= 70) {
            target = ThermalLevel::COOL;
        } else if (headroomPct >= 40) {
            // 从 COOL 往下走要跌破 40 才降；已经在 WARM 则要回到 75 才升。
            target = (prev == ThermalLevel::COOL && headroomPct >= 55)
                         ? ThermalLevel::COOL
                         : ThermalLevel::WARM;
        } else if (headroomPct >= 15) {
            target = (prev == ThermalLevel::WARM && headroomPct >= 45)
                         ? ThermalLevel::WARM
                         : ThermalLevel::HOT;
        } else {
            target = ThermalLevel::CRITICAL;
        }
        // 平台明确报严重及以上，直接压到 CRITICAL（不信任 headroom 的乐观值）。
        if (platformStatus >= kStatusSevere) target = ThermalLevel::CRITICAL;

        a.level = target;

        // 档位 → 线程/批的下调比例
        double threadScale = 1.0;
        double batchScale = 1.0;
        switch (target) {
            case ThermalLevel::COOL:
                threadScale = 1.0; batchScale = 1.0; break;
            case ThermalLevel::WARM:
                // 轻微让步：线程 -25%，批不变（批变小会明显拖慢 prefill）
                threadScale = 0.75; batchScale = 1.0; break;
            case ThermalLevel::HOT:
                // 明显让步：线程 -50%，批 -50%
                threadScale = 0.5; batchScale = 0.5; break;
            case ThermalLevel::CRITICAL:
                // 保命档：只留少量线程、小批。宁可慢，也要保住"能用"。
                threadScale = 0.34; batchScale = 0.25; break;
            default:
                a.threads = 0; a.batch = 0; return a;
        }

        a.threads = std::max(1, static_cast<int>(baseThreads * threadScale));
        a.batch = std::max(1, static_cast<int>(baseBatch * batchScale));
        // 线程数不得超过硬件上限
        const int hw = static_cast<int>(std::thread::hardware_concurrency());
        if (hw > 0) a.threads = std::min(a.threads, hw);
        return a;
    }
};

// ── 单例 ────────────────────────────────────────────────────────────────────
// 为什么用「函数内 static」而不是文件级静态对象：
//   1. 线程安全：C++11 起函数内 static 的初始化由编译器插入 guard 变量，
//      多线程首次并发进入也只会构造一次。本层会被 JNI 工作线程并发调用，
//      文件级静态对象在这里要靠「动态库加载期串行」来兜底，不可靠。
//   2. **惰性**：只有真正用到温控的进程才会构造它。文件级静态对象会在
//      .so 加载期就构造，而温控要 dlopen("libandroid.so") —— 那属于
//      「加载期做 IO」，是所有卡启动 / 加载失败的经典来源。
//   3. 销毁顺序：函数内 static 的析构由 atexit 最后处理，不会被本层其他
//      静态对象反序析构之后当成野指针继续访问。
ThermalGovernor& ThermalGovernor::instance() {
    static ThermalGovernor governor;
    return governor;
}

ThermalGovernor::ThermalGovernor() : impl_(new Impl()) {
    impl_->current.level = ThermalLevel::UNKNOWN;
}
ThermalGovernor::~ThermalGovernor() { delete impl_; }

void ThermalGovernor::configure(int pollMs, int baseThreads, int baseBatch) {
    std::lock_guard<std::mutex> lock(impl_->mtx);
    impl_->pollMs = std::max(500, pollMs);
    impl_->baseThreads = std::max(1, baseThreads);
    impl_->baseBatch = std::max(1, baseBatch);
    impl_->configured = true;
    aThermalApi().ensure();
    QLOGI("温控已配置 | pollMs=%d baseThreads=%d baseBatch=%d | 探测=%s",
          impl_->pollMs, impl_->baseThreads, impl_->baseBatch,
          probeAvailable() ? "有" : "无");
}

bool ThermalGovernor::probeAvailable() const {
    AThermalApi& api = const_cast<AThermalApi&>(aThermalApi());
    api.ensure();
    if (api.ok) return true;
    return scanSysfsMaxTemp() >= 0.0;
}

ThermalAdvice ThermalGovernor::sample() {
    std::lock_guard<std::mutex> lock(impl_->mtx);
    if (!impl_->configured) {
        impl_->current.level = ThermalLevel::UNKNOWN;
        impl_->current.threads = 0;
        impl_->current.batch = 0;
        impl_->current.reason = "温控未配置";
        return impl_->current;
    }

    AThermalApi& api = aThermalApi();
    api.ensure();

    int headroomPct = 100;
    int platformStatus = kStatusNone;
    std::string why;

    bool haveHeadroom = false;
    if (api.ok && api.headroom != nullptr) {
        const float h = api.headroom(api.mgr, 0 /* forecast seconds */);
        if (h > 0.0f) {
            headroomPct = std::max(0, std::min(100, static_cast<int>(h * 100.0f)));
            haveHeadroom = true;
            why = "AThermal headroom";
        }
    }
    if (api.ok && api.status != nullptr) {
        platformStatus = api.status(api.mgr);
    }

    if (!haveHeadroom) {
        const double c = scanSysfsMaxTemp();
        const float pseudo = tempToPseudoHeadroom(c);
        if (pseudo >= 0.0f) {
            headroomPct = std::max(0, std::min(100, static_cast<int>(pseudo * 100.0f)));
            haveHeadroom = true;
            char buf[96];
            snprintf(buf, sizeof(buf), "sysfs 温度 %.1f℃", c);
            why = buf;
        }
    }

    if (!haveHeadroom) {
        // 探测不可用 → 明确不干预（而不是瞎猜档位）。
        impl_->current = ThermalAdvice{};
        impl_->current.level = ThermalLevel::UNKNOWN;
        impl_->current.reason = "无可用温控数据源，不干预";
        return impl_->current;
    }

    ThermalAdvice advice = impl_->evaluate(headroomPct, platformStatus, why);

    // 只在档位变化时打日志（每 2 秒一条会把日志刷爆）
    if (advice.level != impl_->current.level) {
        QLOGI("温控档位 %s → %s | headroom=%d%% status=%d threads=%d batch=%d | %s",
              thermalLevelName(impl_->current.level), thermalLevelName(advice.level),
              advice.headroomPct, advice.platformStatus, advice.threads, advice.batch,
              advice.reason.c_str());
    }

    if (advice.level == ThermalLevel::HOT || advice.level == ThermalLevel::CRITICAL) {
        impl_->consecutiveHot++;
    } else {
        impl_->consecutiveHot = 0;
    }

    impl_->current = advice;
    return advice;
}

ThermalAdvice ThermalGovernor::last() const {
    std::lock_guard<std::mutex> lock(impl_->mtx);
    return impl_->current;
}

int ThermalGovernor::advisedThreads() const {
    std::lock_guard<std::mutex> lock(impl_->mtx);
    if (impl_->current.level == ThermalLevel::UNKNOWN || impl_->current.threads <= 0) {
        return impl_->baseThreads;
    }
    return std::max(1, impl_->current.threads);
}

int ThermalGovernor::advisedBatch() const {
    std::lock_guard<std::mutex> lock(impl_->mtx);
    if (impl_->current.level == ThermalLevel::UNKNOWN || impl_->current.batch <= 0) {
        return impl_->baseBatch;
    }
    return std::max(1, impl_->current.batch);
}

bool ThermalGovernor::sustainedThrottle() const {
    std::lock_guard<std::mutex> lock(impl_->mtx);
    return impl_->consecutiveHot >= 3;  // 连续 3 次采样（约 6 秒）仍在 HOT 以上
}

void ThermalGovernor::reset() {
    std::lock_guard<std::mutex> lock(impl_->mtx);
    impl_->current = ThermalAdvice{};
    impl_->current.level = ThermalLevel::UNKNOWN;
    impl_->consecutiveHot = 0;
}

}  // namespace llm
}  // namespace quro
