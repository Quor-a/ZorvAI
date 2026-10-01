// =============================================================================
// L5 · 内存互斥仲裁实现
// =============================================================================
#include "quro/memory_arbiter.h"

#include <android/log.h>

#include <mutex>
#include <sstream>

#include "quro/resource.h"

#define QLOG_TAG "QuroLlm.Arbiter"
#define QLOGI(...) __android_log_print(ANDROID_LOG_INFO, QLOG_TAG, __VA_ARGS__)
#define QLOGW(...) __android_log_print(ANDROID_LOG_WARN, QLOG_TAG, __VA_ARGS__)

namespace quro {
namespace llm {

namespace { constexpr int64_t kMB = 1024LL * 1024LL; }

struct MemoryArbiter::Impl {
    mutable std::mutex mtx;
    WeightHolder holder;
    int64_t evictCount = 0;
};

MemoryArbiter& MemoryArbiter::instance() {
    static MemoryArbiter a;
    return a;
}

MemoryArbiter::MemoryArbiter() : impl_(new Impl()) {}
MemoryArbiter::~MemoryArbiter() { delete impl_; }

bool MemoryArbiter::acquire(const std::string& owner, int64_t bytes,
                            const Evictor& evictor, bool allowEvict,
                            std::string* err) {
    if (owner.empty()) {
        if (err) *err = "acquire 的 owner 为空";
        return false;
    }

    // 先在锁外做预算校验：memoryBudgetBytes() 会读 /proc，不该占着锁。
    if (bytes > 0) {
        if (!Resource::budgetAllows(bytes, err)) {
            QLOGW("拒绝加载：%s 需要 %lldMB，超出内存预算", owner.c_str(),
                  static_cast<long long>(bytes / kMB));
            return false;
        }
    }

    std::string evictedOwner;
    int64_t evictedBytes = 0;

    {
        std::lock_guard<std::mutex> lock(impl_->mtx);

        if (!impl_->holder.isEmpty()) {
            if (impl_->holder.owner == owner) {
                // 同一持有者重复 acquire（例如换模型重载）→ 直接放行并更新字节数。
                impl_->holder.bytes = bytes;
                return true;
            }
            if (!allowEvict || !evictor) {
                if (err) {
                    std::ostringstream os;
                    os << "引擎「" << impl_->holder.owner << "」当前持有 "
                       << (impl_->holder.bytes / kMB) << "MB 权重；"
                       << "本进程只允许一个引擎持有大权重（避免两个 2–3GB 权重同驻被 LMK 杀）。"
                       << "请先卸载它，或允许切换时自动卸载。";
                    *err = os.str();
                }
                QLOGW("拒绝加载：%s 被 %s 占用且不允许驱逐", owner.c_str(),
                      impl_->holder.owner.c_str());
                return false;
            }
            evictedOwner = impl_->holder.owner;
            evictedBytes = impl_->holder.bytes;
            // 先清空再驱逐：evictor 内部可能回调 release()，避免自我死锁/重复计数。
            impl_->holder = WeightHolder{};
        }
    }

    // 在锁外执行驱逐 —— unload 会做重活（释放 KV、关 GPU 上下文），
    // 持锁执行会阻塞其他线程的诊断查询。
    if (!evictedOwner.empty()) {
        QLOGI("内存互斥：驱逐旧持有者「%s」（%lldMB）以让位给「%s」",
              evictedOwner.c_str(), static_cast<long long>(evictedBytes / kMB),
              owner.c_str());
        const bool ok = evictor ? evictor() : false;
        if (!ok) {
            QLOGW("驱逐「%s」失败，拒绝加载「%s」", evictedOwner.c_str(), owner.c_str());
            if (err) {
                std::ostringstream os;
                os << "无法卸载正在占用内存的引擎「" << evictedOwner
                   << "」，因此不能加载新模型。请重启应用后重试。";
                *err = os.str();
            }
            // 旧持有者的状态已不可信，这里不复原 —— 让它自己在下一次 acquire 时重建。
            std::lock_guard<std::mutex> lock(impl_->mtx);
            impl_->evictCount++;
            return false;
        }
        std::lock_guard<std::mutex> lock(impl_->mtx);
        impl_->evictCount++;
    }

    {
        std::lock_guard<std::mutex> lock(impl_->mtx);
        impl_->holder.owner = owner;
        impl_->holder.bytes = bytes;
    }
    QLOGI("内存互斥：%s 获得权重持有权（%lldMB）", owner.c_str(),
          static_cast<long long>(bytes / kMB));
    return true;
}

void MemoryArbiter::release(const std::string& owner) {
    std::lock_guard<std::mutex> lock(impl_->mtx);
    if (impl_->holder.isEmpty()) return;
    if (!owner.empty() && impl_->holder.owner != owner) {
        // 不匹配就不动 —— 否则 A 的 unload 会误释放 B 的持有权。
        QLOGW("release 忽略：owner=%s 但当前持有者是 %s", owner.c_str(),
              impl_->holder.owner.c_str());
        return;
    }
    QLOGI("内存互斥：%s 释放权重持有权", impl_->holder.owner.c_str());
    impl_->holder = WeightHolder{};
}

WeightHolder MemoryArbiter::holder() const {
    std::lock_guard<std::mutex> lock(impl_->mtx);
    return impl_->holder;
}

bool MemoryArbiter::idle() const {
    std::lock_guard<std::mutex> lock(impl_->mtx);
    return impl_->holder.isEmpty();
}

int64_t MemoryArbiter::evictCount() const {
    std::lock_guard<std::mutex> lock(impl_->mtx);
    return impl_->evictCount;
}

void MemoryArbiter::reset() {
    std::lock_guard<std::mutex> lock(impl_->mtx);
    impl_->holder = WeightHolder{};
    impl_->evictCount = 0;
}

std::string MemoryArbiter::describe() const {
    std::lock_guard<std::mutex> lock(impl_->mtx);
    std::ostringstream os;
    if (impl_->holder.isEmpty()) {
        os << "无引擎持有权重";
    } else {
        os << impl_->holder.owner << " 持有 " << (impl_->holder.bytes / kMB) << "MB";
    }
    os << " | 累计驱逐 " << impl_->evictCount << " 次";
    return os.str();
}

}  // namespace llm
}  // namespace quro
