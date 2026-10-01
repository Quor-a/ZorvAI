// =============================================================================
// L2 · 句柄注册表 —— 「只传 jlong 不透明句柄」的实现底座
// =============================================================================
// 背景（为什么不能直接把指针当 jlong 传给 Java）：
//   把 `Session*` 强转成 jlong 传出去，等于把**裸指针**交给了 Java 侧保管。
//   一旦 Java 侧发生「取消后又回调一次」「配置变更重建会话但旧任务仍在跑」
//   这类时序问题，native 就会解引用一个已释放的对象 → SIGSEGV/SIGABRT，
//   栈里只剩 `pc 00000000`，完全无从排查。
//
// 本层把「句柄」从裸指针升级为**槽位 + 代际号**：
//
//        handle (int64)
//   ┌──────────────┬──────────┐
//   │ generation   │ slot idx │      高 32 位代际号, 低 32 位槽位下标
//   └──────────────┴──────────┘
//
//   · 槽位复用后代际号 +1 → **旧句柄永久失效**，绝不会误命中新对象。
//   · get() 时校验代际号与 kind，不匹配直接返回 nullptr（上层给友好错误，而不是崩）。
//   · 句柄值本身不含任何地址信息 → Java 侧无法构造出有效句柄去访问任意内存。
//
// 线程安全：内部表用 shared_mutex 保护；decode 期间高频 get() 走读锁，
// 不阻塞并发的另一个请求做 add/remove。
// =============================================================================
#ifndef QURO_LLM_HANDLE_REGISTRY_H
#define QURO_LLM_HANDLE_REGISTRY_H

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace quro {
namespace llm {

/// 句柄指向的对象类别。get/remove 时会校验，防止把 A 类句柄当 B 类用。
enum class HandleKind : int32_t {
    UNKNOWN = 0,
    ENGINE = 1,
    SESSION = 2,   // 引擎会话（MNN 或 llama 各自一份）
    CALLBACK = 3,  // 跨线程回调用的一次性上下文
};

/**
 * 全局句柄注册表。
 *
 * 单例是刻意的：句柄必须在**同一进程内唯一**，且 :llm 进程重启后整体失效
 * （重启会清空这张表，旧句柄自然全废，上层用 get()==nullptr 就能识别并重建）。
 */
class HandleRegistry {
public:
    static HandleRegistry& instance();

    /// 注册对象，返回不透明句柄。ptr 为空返回 0（0 恒为非法句柄）。
    int64_t add(void* ptr, HandleKind kind, const char* label);

    /// 取对象。句柄非法 / 已释放 / 代际不符 / 类别不符 → nullptr。
    void* get(int64_t handle, HandleKind kind) const;

    /// 注销并返回原指针（调用方负责销毁对象）。句柄非法返回 nullptr。
    void* remove(int64_t handle, HandleKind kind);

    /// 注销但不关心原指针（内部对象自管理时用）。
    bool erase(int64_t handle, HandleKind kind);

    /// 当前活跃句柄数（诊断用）。
    size_t size() const;

    /// 某类别活跃句柄数。
    size_t size(HandleKind kind) const;

    /// 遍历某类别的全部句柄（进程退出兜底清理用）。
    std::vector<int64_t> handlesOf(HandleKind kind) const;

    /// 清空（进程退出 / 测试用）。**不**负责销毁对象。
    void clear();

private:
    HandleRegistry();
    ~HandleRegistry();
    HandleRegistry(const HandleRegistry&) = delete;
    HandleRegistry& operator=(const HandleRegistry&) = delete;

    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace llm
}  // namespace quro

#endif  // QURO_LLM_HANDLE_REGISTRY_H
