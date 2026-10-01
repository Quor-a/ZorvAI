// =============================================================================
// L3 · 引擎自注册（mnn 模块）
// =============================================================================
// 与 llama 侧的 mnn_engine_registry.cpp 完全对称：把 MnnEngine 交给 L3 引擎工厂。
//
// 为什么这一步是"L3 不再是死代码"的判据：
//   旧构架的 llm/core 里 Engine 纯虚接口与 engine_factory 写得完整，但
//   · 没有任何类实现 Engine
//   · registerEngine 调用点是零
//   · createEngineForModel 永远返回「引擎未注册」
//   实际推理全在 mnnllmnative.cpp 里直连 MNN。
//
// 每个 .so 各有一份 registry（quro_llm_core 是**静态**库，被两个模块各链入一份），
// 所以 libMNNWrapper.so 里的注册表只有 "mnn"，libLlamaWrapper.so 里只有 "llama.cpp"。
// 这正是「双引擎只做能力互补、不做内存共存」在符号层面的体现：
// 两者互不可见，也就不会互相误路由。
// =============================================================================

#include "quro/engine_factory.h"

#include "mnn_engine.h"

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
/// 故意**只 new 不 create**：MNN 的实例创建（createLLM）需要配置路径这一入参，
/// 而工厂回调没有入参 —— 所以这里只产出"空壳引擎"，
/// 由调用方随后 createFromConfig(path) + setConfig* + load()。
Engine* createMnnEngine() {
    return new (std::nothrow) MnnEngine();
}

}  // namespace

/// 把 MnnEngine 注册进 L3 引擎工厂。幂等（重复调用安全）。
bool registerMnnEngine() {
    if (!registerEngine("mnn", &createMnnEngine)) {
        LOGE("MnnEngine 注册失败（L3 引擎工厂拒绝了空名/空工厂）");
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
