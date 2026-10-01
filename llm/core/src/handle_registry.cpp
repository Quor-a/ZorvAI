// =============================================================================
// L2 · 句柄注册表实现
// =============================================================================
#include "quro/handle_registry.h"

#include <android/log.h>

#include <cstring>
#include <mutex>
#include <shared_mutex>
#include <unordered_map>

#define QLOG_TAG "QuroLlm.Handle"
#define QLOGI(...) __android_log_print(ANDROID_LOG_INFO, QLOG_TAG, __VA_ARGS__)
#define QLOGW(...) __android_log_print(ANDROID_LOG_WARN, QLOG_TAG, __VA_ARGS__)

namespace quro {
namespace llm {

namespace {

// 高 32 位放代际号，低 32 位放槽位下标。
constexpr int64_t kIndexMask = 0x7FFFFFFFLL;   // 31 位下标（够用且避开符号位）
constexpr int64_t kIndexBits = 31;

inline int64_t makeHandle(uint32_t generation, int64_t index) {
    return (static_cast<int64_t>(generation) << kIndexBits) | (index & kIndexMask);
}
inline uint32_t handleGeneration(int64_t h) {
    return static_cast<uint32_t>(static_cast<uint64_t>(h) >> kIndexBits);
}
inline int64_t handleIndex(int64_t h) { return h & kIndexMask; }

struct Slot {
    void* ptr = nullptr;
    uint32_t generation = 1;      // 从 1 起，0 保留给「无效」
    HandleKind kind = HandleKind::UNKNOWN;
    std::string label;
    bool used = false;
};

}  // namespace

struct HandleRegistry::Impl {
    mutable std::shared_mutex mtx;
    std::vector<Slot> slots;
    // 按类别计数与句柄列表，便于诊断与退出清理
    mutable std::unordered_map<int32_t, size_t> countByKind;
};

HandleRegistry& HandleRegistry::instance() {
    static HandleRegistry reg;
    return reg;
}

HandleRegistry::HandleRegistry() : impl_(new Impl()) {}
HandleRegistry::~HandleRegistry() = default;

int64_t HandleRegistry::add(void* ptr, HandleKind kind, const char* label) {
    if (ptr == nullptr) return 0;

    std::unique_lock<std::shared_mutex> lock(impl_->mtx);

    // 找一个空槽复用；没有就新开一个。
    int64_t index = -1;
    for (size_t i = 0; i < impl_->slots.size(); ++i) {
        if (!impl_->slots[i].used) {
            index = static_cast<int64_t>(i);
            break;
        }
    }
    if (index < 0) {
        if (impl_->slots.size() >= static_cast<size_t>(kIndexMask)) {
            QLOGW("句柄表已满（%zu），拒绝注册", impl_->slots.size());
            return 0;
        }
        impl_->slots.emplace_back();
        index = static_cast<int64_t>(impl_->slots.size()) - 1;
    }

    Slot& slot = impl_->slots[static_cast<size_t>(index)];
    if (slot.generation == 0) slot.generation = 1;  // 回绕保护
    slot.ptr = ptr;
    slot.kind = kind;
    slot.label = (label != nullptr) ? label : "";
    slot.used = true;

    impl_->countByKind[static_cast<int32_t>(kind)]++;

    const int64_t handle = makeHandle(slot.generation, index);
    QLOGI("注册句柄 kind=%d index=%lld gen=%u ptr=%p label=%s",
          static_cast<int>(kind), static_cast<long long>(index), slot.generation,
          ptr, slot.label.c_str());
    return handle;
}

void* HandleRegistry::get(int64_t handle, HandleKind kind) const {
    if (handle == 0) return nullptr;

    const int64_t index = handleIndex(handle);
    if (index < 0 || static_cast<size_t>(index) >= impl_->slots.size()) {
        QLOGW("句柄下标越界：handle=%lld index=%lld", static_cast<long long>(handle),
              static_cast<long long>(index));
        return nullptr;
    }

    std::shared_lock<std::shared_mutex> lock(impl_->mtx);
    const Slot& slot = impl_->slots[static_cast<size_t>(index)];
    if (!slot.used) {
        QLOGW("句柄已释放：handle=%lld index=%lld", static_cast<long long>(handle),
              static_cast<long long>(index));
        return nullptr;
    }
    if (slot.generation != handleGeneration(handle)) {
        // 槽位被复用后旧句柄走到这里 —— 这正是本层要拦住的「野句柄」。
        QLOGW("句柄代际不符（已被复用）：handle=%lld index=%lld expect_gen=%u got_gen=%u",
              static_cast<long long>(handle), static_cast<long long>(index),
              slot.generation, handleGeneration(handle));
        return nullptr;
    }
    if (slot.kind != kind) {
        QLOGW("句柄类别不符：handle=%lld expect=%d got=%d",
              static_cast<long long>(handle), static_cast<int>(kind),
              static_cast<int>(slot.kind));
        return nullptr;
    }
    return slot.ptr;
}

void* HandleRegistry::remove(int64_t handle, HandleKind kind) {
    if (handle == 0) return nullptr;

    const int64_t index = handleIndex(handle);
    if (index < 0 || static_cast<size_t>(index) >= impl_->slots.size()) return nullptr;

    std::unique_lock<std::shared_mutex> lock(impl_->mtx);
    Slot& slot = impl_->slots[static_cast<size_t>(index)];
    if (!slot.used) return nullptr;
    if (slot.generation != handleGeneration(handle)) return nullptr;
    if (slot.kind != kind) return nullptr;

    void* ptr = slot.ptr;
    // 代际号 +1 → 所有仍持有旧句柄的线程立刻失去访问权，而不是访问到新对象。
    slot.generation = (slot.generation + 1 == 0) ? 1 : slot.generation + 1;
    slot.ptr = nullptr;
    slot.used = false;
    slot.label.clear();

    auto it = impl_->countByKind.find(static_cast<int32_t>(kind));
    if (it != impl_->countByKind.end() && it->second > 0) it->second--;

    QLOGI("注销句柄 kind=%d index=%lld → ptr=%p", static_cast<int>(kind),
          static_cast<long long>(index), ptr);
    return ptr;
}

bool HandleRegistry::erase(int64_t handle, HandleKind kind) {
    return remove(handle, kind) != nullptr;
}

size_t HandleRegistry::size() const {
    std::shared_lock<std::shared_mutex> lock(impl_->mtx);
    size_t n = 0;
    for (const auto& s : impl_->slots) {
        if (s.used) ++n;
    }
    return n;
}

size_t HandleRegistry::size(HandleKind kind) const {
    std::shared_lock<std::shared_mutex> lock(impl_->mtx);
    auto it = impl_->countByKind.find(static_cast<int32_t>(kind));
    return (it == impl_->countByKind.end()) ? 0 : it->second;
}

std::vector<int64_t> HandleRegistry::handlesOf(HandleKind kind) const {
    std::shared_lock<std::shared_mutex> lock(impl_->mtx);
    std::vector<int64_t> out;
    for (size_t i = 0; i < impl_->slots.size(); ++i) {
        const Slot& s = impl_->slots[i];
        if (s.used && s.kind == kind) {
            out.push_back(makeHandle(s.generation, static_cast<int64_t>(i)));
        }
    }
    return out;
}

void HandleRegistry::clear() {
    std::unique_lock<std::shared_mutex> lock(impl_->mtx);
    for (auto& s : impl_->slots) {
        s.ptr = nullptr;
        s.used = false;
        s.generation = (s.generation + 1 == 0) ? 1 : s.generation + 1;
        s.kind = HandleKind::UNKNOWN;
        s.label.clear();
    }
    impl_->countByKind.clear();
    QLOGI("句柄表已清空");
}

}  // namespace llm
}  // namespace quro
