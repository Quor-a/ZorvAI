// =============================================================================
// L5 · 系统资源层实现
// =============================================================================
#include "quro/resource.h"

#include <android/log.h>
#include <dirent.h>
#include <sched.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <sstream>
#include <thread>

#define QLOG_TAG "QuroLlm.Resource"
#define QLOGI(...) __android_log_print(ANDROID_LOG_INFO, QLOG_TAG, __VA_ARGS__)
#define QLOGW(...) __android_log_print(ANDROID_LOG_WARN, QLOG_TAG, __VA_ARGS__)

namespace quro {
namespace llm {

namespace {

constexpr int64_t kMB = 1024LL * 1024LL;

// 缓存：decode 循环里可能每个 batch 都问一次，不能每次都读 /proc。
struct ProfileCache {
    std::mutex mtx;
    int64_t stampedAtMs = 0;
    DeviceProfile cached;
    bool valid = false;
};
ProfileCache& cache() {
    static ProfileCache c;
    return c;
}

int64_t nowMs() {
    struct timespec ts {};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

/// 读一个小文件里的第一个整数。
int64_t readInt64File(const std::string& path) {
    FILE* f = fopen(path.c_str(), "r");
    if (f == nullptr) return -1;
    long long v = -1;
    const bool ok = (fscanf(f, "%lld", &v) == 1);
    fclose(f);
    return ok ? static_cast<int64_t>(v) : -1;
}

/// 从 /proc/meminfo 取某个字段（KB → 字节）。
int64_t readMeminfoKb(const char* key) {
    FILE* f = fopen("/proc/meminfo", "r");
    if (f == nullptr) return 0;
    char line[256];
    int64_t result = 0;
    const size_t klen = strlen(key);
    while (fgets(line, sizeof(line), f) != nullptr) {
        if (strncmp(line, key, klen) == 0) {
            long long kb = 0;
            if (sscanf(line + klen, ": %lld", &kb) == 1) {
                result = static_cast<int64_t>(kb) * 1024;
            }
            break;
        }
    }
    fclose(f);
    return result;
}

/// 从 /proc/self/status 取 VmRSS / VmHWM（KB → 字节）。
int64_t readSelfStatusKb(const char* key) {
    FILE* f = fopen("/proc/self/status", "r");
    if (f == nullptr) return 0;
    char line[256];
    int64_t result = 0;
    const size_t klen = strlen(key);
    while (fgets(line, sizeof(line), f) != nullptr) {
        if (strncmp(line, key, klen) == 0) {
            long long kb = 0;
            if (sscanf(line + klen, ": %lld", &kb) == 1) {
                result = static_cast<int64_t>(kb) * 1024;
            }
            break;
        }
    }
    fclose(f);
    return result;
}

struct CoreFreq {
    int id = 0;
    int64_t maxKhz = 0;
};

std::vector<CoreFreq> readCoreFreqs() {
    std::vector<CoreFreq> out;
    DIR* dir = opendir("/sys/devices/system/cpu");
    if (dir == nullptr) return out;

    struct dirent* ent = nullptr;
    while ((ent = readdir(dir)) != nullptr) {
        if (strncmp(ent->d_name, "cpu", 3) != 0) continue;
        const int id = atoi(ent->d_name + 3);
        // "cpu" 后面必须是数字，排除 cpufreq / cpuidle 这类条目
        if (ent->d_name[3] < '0' || ent->d_name[3] > '9') continue;

        const std::string base = std::string("/sys/devices/system/cpu/") + ent->d_name;
        int64_t khz = readInt64File(base + "/cpufreq/cpuinfo_max_freq");
        if (khz <= 0) {
            // 有些机型只给 scaling_max_freq
            khz = readInt64File(base + "/cpufreq/scaling_max_freq");
        }
        CoreFreq cf;
        cf.id = id;
        cf.maxKhz = (khz > 0) ? khz : 0;
        out.push_back(cf);
    }
    closedir(dir);

    std::sort(out.begin(), out.end(),
              [](const CoreFreq& a, const CoreFreq& b) { return a.id < b.id; });
    return out;
}

}  // namespace

// ─────────────────────────── DeviceProfile ───────────────────────────
std::string DeviceProfile::describe() const {
    std::ostringstream os;
    os << "cores=" << totalCores << "(big=" << bigCores << ")";
    if (maxBigCoreKhz > 0) os << " maxBig=" << (maxBigCoreKhz / 1000) << "MHz";
    os << " memAvail=" << (memAvailableBytes / kMB) << "MB"
       << " memTotal=" << (memTotalBytes / kMB) << "MB";
    if (!bigCoreIds.empty()) {
        os << " bigIds=[";
        for (size_t i = 0; i < bigCoreIds.size(); ++i) {
            if (i) os << ",";
            os << bigCoreIds[i];
        }
        os << "]";
    }
    return os.str();
}

DeviceProfile Resource::profile() {
    auto& c = cache();
    std::lock_guard<std::mutex> lock(c.mtx);

    const int64_t t = nowMs();
    if (c.valid && (t - c.stampedAtMs) < 1000) return c.cached;

    DeviceProfile p;

    const auto freqs = readCoreFreqs();
    p.totalCores = static_cast<int>(freqs.size());
    if (p.totalCores == 0) {
        // sysfs 读不到（部分机型受限）→ 用运行时并发度兜底
        const unsigned hw = std::thread::hardware_concurrency();
        p.totalCores = (hw > 0) ? static_cast<int>(hw) : 4;
    }

    // 大核识别：最高频 ≤ 大核阈值的核心，都被视为同一「最快簇」。
    // 阈值取最大频率的 80%，这样 2.8GHz/2.4GHz 这种轻微差异仍算同簇，
    // 而 2.8GHz vs 1.8GHz 的小核会被排除。
    int64_t maxKhz = 0;
    for (const auto& f : freqs) maxKhz = std::max(maxKhz, f.maxKhz);
    p.maxBigCoreKhz = maxKhz;

    if (maxKhz > 0) {
        const int64_t threshold = static_cast<int64_t>(maxKhz * 0.8);
        for (const auto& f : freqs) {
            if (f.maxKhz >= threshold) p.bigCoreIds.push_back(f.id);
        }
    }

    if (p.bigCoreIds.empty()) {
        // 识别失败（读不到频率）→ 保守：把所有核当大核，不设亲和性。
        for (const auto& f : freqs) p.bigCoreIds.push_back(f.id);
        if (p.bigCoreIds.empty()) {
            for (int i = 0; i < p.totalCores; ++i) p.bigCoreIds.push_back(i);
        }
    }
    p.bigCores = static_cast<int>(p.bigCoreIds.size());

    p.memAvailableBytes = readMeminfoKb("MemAvailable");
    p.memTotalBytes = readMeminfoKb("MemTotal");

    c.cached = p;
    c.stampedAtMs = t;
    c.valid = true;
    return p;
}

void Resource::invalidate() {
    auto& c = cache();
    std::lock_guard<std::mutex> lock(c.mtx);
    c.valid = false;
}

// ─────────────────────────── 线程 ───────────────────────────
bool Resource::bindCurrentThreadToBigCores() {
    const DeviceProfile p = profile();
    if (p.bigCoreIds.empty()) return false;
    // 全部核心都是大核 → 没必要设亲和性
    if (p.bigCores >= p.totalCores) return false;

    cpu_set_t set;
    CPU_ZERO(&set);
    for (int id : p.bigCoreIds) {
        if (id >= 0 && id < CPU_SETSIZE) CPU_SET(id, &set);
    }

    // pid=0 表示当前线程
    const int rc = sched_setaffinity(0, sizeof(set), &set);
    if (rc != 0) {
        QLOGW("设置大核亲和性失败（忽略）：rc=%d", rc);
        return false;
    }
    return true;
}

bool Resource::unbindCurrentThread() {
    cpu_set_t set;
    CPU_ZERO(&set);
    const DeviceProfile p = profile();
    for (int i = 0; i < p.totalCores && i < CPU_SETSIZE; ++i) CPU_SET(i, &set);
    return sched_setaffinity(0, sizeof(set), &set) == 0;
}

int Resource::recommendThreads() {
    const DeviceProfile p = profile();
    // 大核数 - 1：留一个核给 UI 渲染与音频，避免主线程被推理饿死
    // （历史上出现过"推理跑满核 → UI 冻结"的事故）。
    const int n = std::max(1, p.bigCores - 1);
    return n;
}

int Resource::recommendThreads(int thermalCap) {
    int n = recommendThreads();
    if (thermalCap > 0) n = std::min(n, thermalCap);
    return std::max(1, n);
}

bool Resource::raiseCurrentThreadPriority() {
    // nice 值 -5：比普通线程稍高，但远离音频/显示这类实时线程，不做越权争抢。
    // 失败是常态（非 root 下降低 nice 需要 RLIMIT_NICE），静默忽略。
    const int rc = setpriority(PRIO_PROCESS, 0, -5);
    return rc == 0;
}

// ─────────────────────────── 内存 ───────────────────────────
int64_t Resource::memAvailableBytes() { return readMeminfoKb("MemAvailable"); }

int64_t Resource::currentRssBytes() { return readSelfStatusKb("VmRSS"); }

int64_t Resource::peakRssBytes() { return readSelfStatusKb("VmHWM"); }

int64_t Resource::memoryBudgetBytes() {
    const int64_t avail = memAvailableBytes();
    if (avail <= 0) return 2LL * 1024 * 1024 * 1024;  // 读不到 → 保守给 2GB
    int64_t budget = static_cast<int64_t>(avail * 0.55);
    constexpr int64_t kMin = 256LL * 1024 * 1024;
    constexpr int64_t kMax = 12LL * 1024 * 1024 * 1024;
    if (budget < kMin) budget = kMin;
    if (budget > kMax) budget = kMax;
    return budget;
}

bool Resource::budgetAllows(int64_t sizeBytes, std::string* whyNot) {
    if (sizeBytes <= 0) return true;
    const int64_t budget = memoryBudgetBytes();
    if (sizeBytes <= budget) return true;
    if (whyNot != nullptr) {
        std::ostringstream os;
        os << "模型需要约 " << (sizeBytes / kMB) << "MB，但当前可用内存预算只有 "
           << (budget / kMB) << "MB（已按 MemAvailable 的 55% 保守估算，"
           << "为系统、UI 与 KV cache 留出空间）。请换更小的量化版本，"
           << "或先关闭其他占用内存的应用。";
        *whyNot = os.str();
    }
    return false;
}

// ─────────────────────────── 页与对齐 ───────────────────────────
int64_t Resource::pageSize() {
    const long ps = sysconf(_SC_PAGESIZE);
    return (ps > 0) ? static_cast<int64_t>(ps) : 4096;
}

int64_t Resource::alignToPage(int64_t size) {
    const int64_t ps = pageSize();
    if (size <= 0) return ps;
    return ((size + ps - 1) / ps) * ps;
}

bool Resource::canMmap(const std::string& path, int64_t* sizeOut) {
    if (sizeOut != nullptr) *sizeOut = 0;
    if (path.empty()) return false;
    struct stat st {};
    if (stat(path.c_str(), &st) != 0) return false;
    if (!S_ISREG(st.st_mode)) return false;
    if (st.st_size <= 0) return false;
    if (sizeOut != nullptr) *sizeOut = static_cast<int64_t>(st.st_size);
    return true;
}

}  // namespace llm
}  // namespace quro
