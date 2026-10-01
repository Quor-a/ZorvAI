// =============================================================================
// L3 · 引擎自注册（llama 模块）
// =============================================================================
// 这是「L3 统一引擎抽象」真正落地的**最后一块**。
//
// 旧构架的实锤（用户指出的那一条）：
//   llm/core 里 Engine 纯虚接口与 engine_factory 写得完整，但
//   · 没有任何类实现 Engine
//   · registerEngine 的调用点是 **零**
//   · 于是 createEngineForModel 永远返回「引擎未注册」
//   —— 整层是死代码，实际推理全在 llama_jni_stub.cpp 里直连。
//
// 现在：
//   · LlamaEngine : public quro::llm::Engine（真实现）
//   · 本文件在模块加载时把它注册进工厂（真调用）
//   · 每个 .so 各有一份 registry（quro_llm_core 是**静态**库，被两个模块
//     各链入一份）—— 所以 libLlamaWrapper.so 里的注册表只有 "llama.cpp"，
//     libMNNWrapper.so 里只有 "mnn"。这正是「双引擎只做能力互补、不做内存共存」
//     在符号层面的体现：两者互不可见，也就不会互相误路由。
//
// 为什么注册放在 .so 加载期（JNI_OnLoad）而不是首次使用时：
//   注册只写一个函数指针进 map，成本可忽略；而"首次使用时注册"意味着
//   第一条推理请求要额外承担一次加锁写表，且失败点被推迟到更难排查的位置。
// =============================================================================

#include "quro/engine_factory.h"

#include "llama_engine.h"

#include <android/log.h>

#include <new>

#define QLOG_TAG "QuroLlm.Factory"
#define QLOGI(...) __android_log_print(ANDROID_LOG_INFO, QLOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, QLOG_TAG, __VA_ARGS__)

namespace quro {
namespace llm {
namespace registry {

namespace {

/// 工厂回调：签名为 `Engine* (*)()`，所有权交给调用方（会被包进 EnginePtr）。
/// 故意**只 new 不 load** —— 契约明确要求"这里只创建对象，不加载权重"，
/// 因为权重加载必须在内存互斥（MemoryArbiter）下由调用方执行。
Engine* createLlamaEngine() {
    return new (std::nothrow) LlamaEngine();
}

}  // namespace

/// 把 LlamaEngine 注册进 L3 引擎工厂。幂等（重复调用安全）。
bool registerLlamaEngine() {
    if (!registerEngine("llama.cpp", &createLlamaEngine)) {
        LOGE("LlamaEngine 注册失败（L3 引擎工厂拒绝了空名/空工厂）");
        return false;
    }
    // 打印当前注册表内容 —— 排障时用户只要看到这一行就知道
    // "引擎路由这一层是否真的活着"（旧构架下这一行永远是空的）。
    QLOGI("引擎注册完成，当前可用：%s", registeredEngines().c_str());
    return true;
}

}  // namespace registry
}  // namespace llm
}  // namespace quro
