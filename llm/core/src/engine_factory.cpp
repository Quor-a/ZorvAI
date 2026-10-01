// =============================================================================
// L3 · 引擎工厂与路由实现
// =============================================================================
#include "quro/engine_factory.h"

#include <android/log.h>
#include <sys/stat.h>

#include <cctype>
#include <cstring>
#include <map>
#include <mutex>
#include <sstream>

#define QLOG_TAG "QuroLlm.Factory"
#define QLOGI(...) __android_log_print(ANDROID_LOG_INFO, QLOG_TAG, __VA_ARGS__)
#define QLOGW(...) __android_log_print(ANDROID_LOG_WARN, QLOG_TAG, __VA_ARGS__)

namespace quro {
namespace llm {

namespace {

constexpr const char* kEngineLlama = "llama.cpp";
constexpr const char* kEngineMnn = "mnn";

std::mutex& registryMutex() {
    static std::mutex m;
    return m;
}
std::map<std::string, EngineFactoryFn>& registry() {
    static std::map<std::string, EngineFactoryFn> r;
    return r;
}

bool isDirectory(const std::string& path) {
    struct stat st {};
    if (stat(path.c_str(), &st) != 0) return false;
    return S_ISDIR(st.st_mode);
}

bool isRegularFile(const std::string& path) {
    struct stat st {};
    if (stat(path.c_str(), &st) != 0) return false;
    return S_ISREG(st.st_mode);
}

bool fileExists(const std::string& path) {
    struct stat st {};
    return stat(path.c_str(), &st) == 0;
}

/// 取小写扩展名（含点）。
std::string extensionOf(const std::string& path) {
    const size_t slash = path.find_last_of('/');
    const size_t dot = path.find_last_of('.');
    if (dot == std::string::npos) return "";
    if (slash != std::string::npos && dot < slash) return "";
    std::string ext = path.substr(dot);
    for (char& c : ext) c = static_cast<char>(::tolower(c));
    return ext;
}

/// 目录下是否含 MNN LLM 的清单文件。
bool hasMnnConfig(const std::string& dir) {
    if (!isDirectory(dir)) return false;
    return fileExists(dir + "/llm_config.json") || fileExists(dir + "/config.json");
}

}  // namespace

const char* modelFormatName(ModelFormat f) {
    switch (f) {
        case ModelFormat::GGUF: return "GGUF (llama.cpp)";
        case ModelFormat::MNN_DIR: return "MNN 模型目录";
        case ModelFormat::MNN_FILE: return "MNN 权重文件";
        default: return "未知格式";
    }
}

ModelFormat detectModelFormat(const std::string& path) {
    if (path.empty()) return ModelFormat::UNKNOWN;

    if (isDirectory(path)) {
        return hasMnnConfig(path) ? ModelFormat::MNN_DIR : ModelFormat::UNKNOWN;
    }

    const std::string ext = extensionOf(path);
    if (ext == ".gguf") return ModelFormat::GGUF;
    if (ext == ".mnn") return ModelFormat::MNN_FILE;
    // 有些 MNN 模型是直接指向目录内某个文件，这里再认一次"同目录有 llm_config.json"
    const size_t slash = path.find_last_of('/');
    if (slash != std::string::npos) {
        const std::string dir = path.substr(0, slash);
        if (hasMnnConfig(dir)) return ModelFormat::MNN_DIR;
    }
    return ModelFormat::UNKNOWN;
}

bool preflight(const std::string& modelPath, ModelFormat* fmtOut, std::string* whyNot) {
    if (fmtOut != nullptr) *fmtOut = ModelFormat::UNKNOWN;

    if (modelPath.empty()) {
        if (whyNot) *whyNot = "模型路径为空。";
        return false;
    }
    if (!fileExists(modelPath)) {
        if (whyNot) {
            *whyNot = "模型路径不存在：" + modelPath +
                      "（若刚从别处拷贝，请确认已完整传输，且位于应用私有目录或已授予访问权限）。";
        }
        return false;
    }

    const ModelFormat fmt = detectModelFormat(modelPath);
    if (fmtOut != nullptr) *fmtOut = fmt;

    switch (fmt) {
        case ModelFormat::GGUF:
            if (!isRegularFile(modelPath)) {
                if (whyNot) *whyNot = "GGUF 路径不是普通文件：" + modelPath;
                return false;
            }
            if (whyNot) whyNot->clear();
            return true;

        case ModelFormat::MNN_FILE:
            if (whyNot) whyNot->clear();
            return true;

        case ModelFormat::MNN_DIR: {
            // MNN LLM 需要 llm_config.json —— 缺了它原生层会在加载时 abort
            // （历史上真机出现过这种"直接闪退"，所以这里必须在 Java/Kotlin 之前拦住）。
            if (isDirectory(modelPath) && !fileExists(modelPath + "/llm_config.json")) {
                if (whyNot) {
                    *whyNot = "MNN 模型目录缺少 llm_config.json：" + modelPath +
                              "。请确认下载完整（该文件是 MNN LLM 的配置清单，"
                              "缺失时原生层会直接中止）。";
                }
                return false;
            }
            if (whyNot) whyNot->clear();
            return true;
        }

        default:
            if (whyNot) {
                std::ostringstream os;
                os << "无法识别的模型格式：" << modelPath << "。"
                   << "支持的形态：① 单个 .gguf 文件（llama.cpp 引擎）；"
                   << "② MNN 模型目录（内含 llm_config.json）；③ 单个 .mnn 权重文件。";
                *whyNot = os.str();
            }
            return false;
    }
}

bool registerEngine(const char* name, EngineFactoryFn fn) {
    if (name == nullptr || fn == nullptr) return false;
    std::lock_guard<std::mutex> lock(registryMutex());
    auto it = registry().find(name);
    if (it != registry().end()) {
        if (it->second == fn) return true;  // 重复注册同一函数，幂等
        QLOGW("引擎「%s」重复注册（覆盖旧实现）", name);
    } else {
        QLOGI("引擎已注册：%s", name);
    }
    registry()[name] = fn;
    return true;
}

void clearEngineRegistry() {
    std::lock_guard<std::mutex> lock(registryMutex());
    registry().clear();
}

std::string registeredEngines() {
    std::lock_guard<std::mutex> lock(registryMutex());
    std::ostringstream os;
    bool first = true;
    for (const auto& kv : registry()) {
        if (!first) os << ", ";
        os << kv.first;
        first = false;
    }
    return os.str();
}

EnginePtr createEngineByName(const std::string& name, std::string* err) {
    EngineFactoryFn fn = nullptr;
    {
        std::lock_guard<std::mutex> lock(registryMutex());
        auto it = registry().find(name);
        if (it != registry().end()) fn = it->second;
    }
    if (fn == nullptr) {
        if (err) {
            std::ostringstream os;
            os << "引擎「" << name << "」未注册（本次构建未编入，或尚未完成初始化）。";
            const std::string all = registeredEngines();
            os << (all.empty() ? "当前没有任何引擎可用。" : ("当前可用引擎：" + all + "。"));
            *err = os.str();
        }
        return nullptr;
    }
    Engine* raw = fn();
    if (raw == nullptr) {
        if (err) *err = "引擎「" + name + "」实例创建失败（构造返回空）。";
        return nullptr;
    }
    return EnginePtr(raw);
}

EnginePtr createEngineForModel(const std::string& modelPath, std::string* err) {
    ModelFormat fmt = ModelFormat::UNKNOWN;
    if (!preflight(modelPath, &fmt, err)) return nullptr;

    const char* target = nullptr;
    switch (fmt) {
        case ModelFormat::GGUF: target = kEngineLlama; break;
        case ModelFormat::MNN_DIR:
        case ModelFormat::MNN_FILE: target = kEngineMnn; break;
        default: break;
    }
    if (target == nullptr) {
        if (err) *err = std::string("模型格式无法路由到任何引擎：") + modelFormatName(fmt);
        return nullptr;
    }

    EnginePtr engine = createEngineByName(target, err);
    if (engine == nullptr) return nullptr;

    // 引擎不可用（本次构建没编入该引擎）→ 提前给出人话原因，而不是等到 load 才失败。
    std::string whyNot;
    if (!engine->available(&whyNot)) {
        if (err) {
            std::ostringstream os;
            os << "引擎「" << target << "」本次不可用";
            if (!whyNot.empty()) os << "：" << whyNot;
            *err = os.str();
        }
        return nullptr;
    }

    QLOGI("模型路由：%s → 引擎 %s", modelFormatName(fmt), target);
    return engine;
}

}  // namespace llm
}  // namespace quro
