// =============================================================================
// L3 · 引擎工厂与路由 —— 「上层按模型格式路由」
// =============================================================================
// 目标：上层只说"用这个模型跑"，由本层决定落到哪个引擎，并给出可读的失败原因。
//
// 路由规则（顺序即优先级）：
//   *.gguf            → llama.cpp
//   目录含 llm_config.json → MNN（MNN LLM 引擎的清单文件）
//   *.mnn             → MNN
//   其他              → 失败，并列出"看起来像什么"，避免用户拿到一句无语的报错
//
// 为什么用「模块自注册」而不是 include：
//   MnnEngine 实现在 :mnn 模块、LlamaEngine 在 :llama 模块，而本层被两个模块共用。
//   若在这里 include 它们的头，就形成 core → mnn / core → llama 的编译期依赖，
//   两个模块会互相拉入对方的头文件与宏（这两套引擎的宏是会打架的：都定义了
//   GPU 后端开关、都 include 各自的 ggml/MNN 配置）。自注册把依赖方向反过来：
//   模块 → core（单向），每个 .so 只把自己那份实现注册进去。
// =============================================================================
#ifndef QURO_LLM_ENGINE_FACTORY_H
#define QURO_LLM_ENGINE_FACTORY_H

#include <string>

#include "quro/engine.h"

namespace quro {
namespace llm {

enum class ModelFormat : int32_t {
    UNKNOWN = 0,
    GGUF = 1,       // llama.cpp
    MNN_DIR = 2,    // MNN 模型目录（含 llm_config.json）
    MNN_FILE = 3,   // 单个 .mnn 文件
};

const char* modelFormatName(ModelFormat f);

/**
 * 按路径判定模型格式。不打开文件内容，只看扩展名与目录清单 —— 廉价且够准。
 */
ModelFormat detectModelFormat(const std::string& path);

/// 加载前预检：路径存在、形态正确、必备文件齐全。失败时 whyNot 可直接展示。
bool preflight(const std::string& modelPath, ModelFormat* fmtOut, std::string* whyNot);

/// 引擎构造回调。返回堆上新实例（所有权交给调用方，包进 EnginePtr）。
using EngineFactoryFn = Engine* (*)();

/**
 * 注册一个引擎实现。模块在自己的 JNI_OnLoad 里调用。
 * 同名重复注册会覆盖并告警（热重载场景）。
 */
bool registerEngine(const char* name, EngineFactoryFn fn);

/// 注销（仅测试用）。
void clearEngineRegistry();

/// 已注册的引擎名（诊断用）。
std::string registeredEngines();

/**
 * 按模型路径创建引擎。失败返回 nullptr，err 给出人话原因。
 * 注意：这里只创建对象，**不加载权重**（load 由调用方在内存互斥下执行）。
 */
EnginePtr createEngineForModel(const std::string& modelPath, std::string* err);

/// 不带模型路径直接按名字创建（诊断/测试用）。
EnginePtr createEngineByName(const std::string& name, std::string* err);

}  // namespace llm
}  // namespace quro

#endif  // QURO_LLM_ENGINE_FACTORY_H
