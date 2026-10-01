// =============================================================================
// L3/L4 · MnnEngine 实现（一）：生命周期 / 分词 / 配置 / 统计
// =============================================================================
// 本 TU 的分工：
//   · L4 MNN 调用（createLLM / load / destroy / reset / tokenizer_* / set_config）
//   · L3 Engine 契约的生命周期与可观测性部分
//   · 纯函数层的落点（mnn_engine_logic.inc 只被本 TU include）
//
// 同模块另两个 TU：
//   mnn_engine_generate.cpp   流式生成（response + generate 循环 + UTF-8/`<eop>` sink）
//   mnn_engine_chat.cpp       聊天模板 / 结构化工具调用 / 上下文与配置导出
// =============================================================================

#include "mnn_engine_impl.h"

#include <exception>
#include <string>
#include <vector>

// 日志与状态结构由 mnn_engine_impl.h 统一提供（三个 TU 共用一套 TAG）。

namespace quro {
namespace llm {
namespace mnn_detail {

// ─────────────────────────────────────────────────────────────────────────
// 纯函数层：由脚本从旧实现逐字抽出（JSON 转义、LlmContext→JSON、
// 内置 ChatML 工具说明与回退渲染、结构化渲染核心）。
// **只在本 TU include** —— 它定义符号，多 TU include 会重复定义。
// ─────────────────────────────────────────────────────────────────────────
#include "mnn_engine_logic.inc"

}  // namespace mnn_detail
}  // namespace llm
}  // namespace quro

namespace quro {
namespace llm {

// ═════════════════════════════════════════════════════════════════════════
// 构造 / 析构
// ═════════════════════════════════════════════════════════════════════════

MnnEngine::MnnEngine() : impl_(new Impl()) {}

MnnEngine::~MnnEngine() {
    // 析构必须释放 Llm：:llm 进程在切模型时会销毁旧引擎，
    // 若把权重留给 OS 回收，下一次 load 会与尚未回收的旧权重叠加 —— 低端机直接 OOM。
    unload();
}

const char* MnnEngine::name() const { return "mnn"; }

bool MnnEngine::available(std::string* whyNot) const {
    // 本 .so 只在 full 风味里产出 —— 能加载就代表 MNN 编进来了。
    // 真正不可用的情形是「createLLM / load 失败」，那由对应方法报错，不走这里。
    // 本函数保留是为了让 L3 的 available() 契约完整（契约要求"不可用时给出人话原因"，
    // 而不是要求"一定有不可用的可能"）。
    if (whyNot != nullptr) whyNot->clear();
    return true;
}

bool MnnEngine::created() const {
    if (!impl_) return false;
    return impl_->session.llm != nullptr;
}

// ═════════════════════════════════════════════════════════════════════════
// 创建（三段式的第一段：create → setConfig* → load）
// ═════════════════════════════════════════════════════════════════════════
bool MnnEngine::createFromConfig(const std::string& configPath, std::string* err) {
    if (!impl_) {
        if (err) *err = "引擎内部状态缺失（Impl 为空）。";
        return false;
    }

    std::lock_guard<std::mutex> guard(impl_->lifecycle);
    mnn_detail::Session& session = impl_->session;

    // 幂等：重复 create 会泄漏一整份 MNN 实例（含已分配的 embedding/tokenizer）。
    // 旧实现靠 Kotlin 侧保证不重复调用，引擎自持这条不变量更安全。
    // 注意调的是**不加锁**的 releaseResources()，不是 unload()：本函数已持有 lifecycle。
    if (session.llm != nullptr) {
        LOGI("createFromConfig 前发现已有实例，先释放");
        releaseResources();
    }

    if (configPath.empty()) {
        if (err) *err = "MNN 配置路径为空。";
        return false;
    }

    try {
        // 只创建实例，**不** load：MNN 要求 set_config 在 load 之前生效，
        // 否则配置会被加载期默认值覆盖（上下文长度 / backend / 采样参数全落回默认）。
        Llm* llm = Llm::createLLM(configPath);
        if (llm == nullptr) {
            LOGE("Failed to create LLM instance");
            if (err) {
                *err = "MNN 实例创建失败：" + configPath +
                       "。常见原因：llm_config.json 缺失或格式错误、"
                       "或模型目录不完整（缺少 tokenizer / weight）。";
            }
            return false;
        }
        session.llm = llm;
        session.weightLoaded = false;
        session.cancel.store(false);
        session.lastError.clear();
        LOGI("LLM instance created at %p (not loaded yet)", llm);
        return true;
    } catch (const std::exception& e) {
        LOGE("Exception creating LLM: %s", e.what());
        if (err) *err = std::string("MNN 实例创建异常：") + e.what();
        return false;
    } catch (...) {
        LOGE("Unknown exception creating LLM");
        if (err) *err = "MNN 实例创建异常（未知错误）。";
        return false;
    }
}

// ═════════════════════════════════════════════════════════════════════════
// 三段式的第三段：加载权重
// ═════════════════════════════════════════════════════════════════════════
bool MnnEngine::load(const LoadConfig& cfg, std::string* err) {
    if (!impl_) {
        if (err) *err = "引擎内部状态缺失（Impl 为空）。";
        return false;
    }
    std::lock_guard<std::mutex> guard(impl_->lifecycle);
    mnn_detail::Session& session = impl_->session;

    if (session.llm == nullptr) {
        // 与 llama 的差别：MNN 必须先 createFromConfig 拿到 Llm（配置也要先注入），
        // 所以这里不能"顺手 create 一个"。如实报错比猜测路径更负责。
        if (err) {
            *err = "尚未创建 MNN 实例：请先 createFromConfig(配置路径) 并注入配置，再 load()。";
        }
        return false;
    }
    if (session.weightLoaded) {
        LOGI("load() 时权重已加载，跳过（幂等）");
        return true;
    }

    session.cfg = cfg;

    try {
        if (!session.llm->load()) {
            LOGE("Failed to load LLM model");
            if (err) {
                *err = "MNN 权重加载失败。常见原因：模型文件不完整、"
                       "可用内存不足（可调小上下文/换更小的模型）、"
                       "或 llm_config.json 里声明的 backend 在本机不可用。";
            }
            return false;
        }
        session.weightLoaded = true;
        LOGI("LLM model loaded successfully");
        return true;
    } catch (const std::exception& e) {
        LOGE("Exception loading LLM: %s", e.what());
        if (err) *err = std::string("MNN 权重加载异常：") + e.what();
        return false;
    } catch (...) {
        LOGE("Unknown exception loading LLM");
        if (err) *err = "MNN 权重加载异常（未知错误）。";
        return false;
    }
}

void MnnEngine::releaseResources() {
    // 调用契约：调用方必须已持有 impl_->lifecycle。
    mnn_detail::Session& session = impl_->session;
    if (session.llm == nullptr) {
        session.weightLoaded = false;
        return;
    }

    try {
        // 回调必须先摘掉：wavform 回调里捕获的是本引擎指针，
        // 若销毁顺序不对（回调还挂着、llm 先没了），下一次音频回调就是悬垂指针。
        session.wavform = nullptr;
        session.llm->setWavformCallback(nullptr);
    } catch (...) {
        // setWavformCallback 是虚函数，部分 MNN 子类实现为空；异常也不阻断销毁。
    }

    try {
        Llm::destroy(session.llm);
        LOGI("LLM released successfully");
    } catch (const std::exception& e) {
        LOGE("Exception releasing LLM: %s", e.what());
    } catch (...) {
        LOGE("Unknown exception releasing LLM");
    }

    session.llm = nullptr;
    session.weightLoaded = false;
    session.cancel.store(false);
    session.lastError.clear();
    session.prefillTokens = 0;
    session.decodeTokens = 0;
    session.weightBytes = 0;
}

bool MnnEngine::unload() {
    if (!impl_) return true;
    std::lock_guard<std::mutex> guard(impl_->lifecycle);
    releaseResources();
    return true;
}

bool MnnEngine::loaded() const {
    if (!impl_) return false;
    return impl_->session.llm != nullptr && impl_->session.weightLoaded;
}

int64_t MnnEngine::weightBytes() const {
    // 如实返回"未知"：MNN 不提供权重占用查询接口（Llm 没有 size() 一类的 API），
    // LlmContext 里也只有耗时/序列长度。engine.h 约定 0 = 未知。
    // **不要在这里按文件大小编一个数字** —— 内存互斥（MemoryArbiter）会拿它做判据，
    // 假数字比"不知道"更危险。
    if (!impl_) return 0;
    return impl_->session.weightBytes;
}

// ═════════════════════════════════════════════════════════════════════════
// 分词
// ═════════════════════════════════════════════════════════════════════════

bool MnnEngine::tokenize(const std::string& text, std::vector<int32_t>* out, std::string* err) {
    if (out == nullptr) {
        if (err) *err = "输出参数为空。";
        return false;
    }
    out->clear();
    if (!impl_ || impl_->session.llm == nullptr) {
        if (err) *err = "引擎尚未创建 MNN 实例。";
        return false;
    }
    try {
        const std::vector<int> tokens = impl_->session.llm->tokenizer_encode(text);
        out->reserve(tokens.size());
        for (const int t : tokens) out->push_back(static_cast<int32_t>(t));
        return true;
    } catch (const std::exception& e) {
        LOGE("Exception in tokenize: %s", e.what());
        if (err) *err = std::string("分词异常：") + e.what();
        return false;
    } catch (...) {
        LOGE("Unknown exception in tokenize");
        if (err) *err = "分词异常（未知错误）。";
        return false;
    }
}

bool MnnEngine::detokenize(const std::vector<int32_t>& tokens, std::string* out, std::string* err) {
    if (out == nullptr) {
        if (err) *err = "输出参数为空。";
        return false;
    }
    out->clear();
    if (!impl_ || impl_->session.llm == nullptr) {
        if (err) *err = "引擎尚未创建 MNN 实例。";
        return false;
    }
    if (tokens.empty()) return true;
    try {
        // MNN 的 tokenizer_decode 是单 token 接口；多 token 逐个解再拼接。
        // 这与 llama 的批量 detokenize 语义不同，但不影响调用方
        // （上层只用它做单 token 的调试回显）。
        std::string result;
        for (const int32_t t : tokens) {
            result += impl_->session.llm->tokenizer_decode(static_cast<int>(t));
        }
        *out = std::move(result);
        return true;
    } catch (const std::exception& e) {
        LOGE("Exception in detokenize: %s", e.what());
        if (err) *err = std::string("反分词异常：") + e.what();
        return false;
    } catch (...) {
        LOGE("Unknown exception in detokenize");
        if (err) *err = "反分词异常（未知错误）。";
        return false;
    }
}

// ═════════════════════════════════════════════════════════════════════════
// 取消 / 复位
// ═════════════════════════════════════════════════════════════════════════

void MnnEngine::cancel() {
    if (!impl_) return;
    // 只置原子标志，**不抢 genLock**：cancel 必须能被 UI 线程即时调用，
    // 而生成可能正持锁跑几分钟。真正的停止点由流式 sink 在每个 chunk 检查。
    impl_->session.cancel.store(true);
    LOGI("Cancellation flag set");
}

bool MnnEngine::resetKv(std::string* err) {
    if (!impl_ || impl_->session.llm == nullptr) {
        if (err) *err = "引擎尚未创建 MNN 实例。";
        return false;
    }
    try {
        impl_->session.llm->reset();
        impl_->session.cancel.store(false);
        return true;
    } catch (const std::exception& e) {
        LOGE("Exception in reset: %s", e.what());
        if (err) *err = std::string("复位异常：") + e.what();
        return false;
    } catch (...) {
        LOGE("Unknown exception in reset");
        if (err) *err = "复位异常（未知错误）。";
        return false;
    }
}

// ═════════════════════════════════════════════════════════════════════════
// 配置 / 音频 / 上下文
// ═════════════════════════════════════════════════════════════════════════

bool MnnEngine::setConfig(const std::string& configJson, std::string* err) {
    if (!impl_ || impl_->session.llm == nullptr) {
        if (err) *err = "引擎尚未创建 MNN 实例。";
        return false;
    }
    try {
        // 与旧实现一致：返回值即成败，异常单独兜。**不**在这里检查是否已 load ——
        // 上层可能先 load 再改采样参数（部分 MNN 版本允许），由 MNN 自己拒绝。
        const bool ok = impl_->session.llm->set_config(configJson);
        if (ok) {
            LOGD("LLM config set successfully");
        } else {
            LOGE("Failed to set LLM config");
            if (err) *err = "MNN 拒绝了该配置（JSON 合法但字段不被接受）。";
        }
        return ok;
    } catch (const std::exception& e) {
        LOGE("Exception in set_config: %s", e.what());
        if (err) *err = std::string("配置注入异常：") + e.what();
        return false;
    } catch (...) {
        LOGE("Unknown exception in set_config");
        if (err) *err = "配置注入异常（未知错误）。";
        return false;
    }
}

void MnnEngine::setWavformCallback(WavformCallback cb) {
    if (!impl_) return;
    mnn_detail::Session& session = impl_->session;
    session.wavform = std::move(cb);
    if (session.llm == nullptr) return;

    if (!session.wavform) {
        // 显式置空：MNN 的 setWavformCallback 接受空 function 表示"不再回调"。
        session.llm->setWavformCallback(std::function<bool(const float*, size_t, bool)>());
        return;
    }
    // 捕获 this 是安全的：引擎实例持有 Llm，Llm 不会比引擎活得久
    // （releaseResources 里先摘回调再 destroy）。
    session.llm->setWavformCallback([this](const float* data, size_t size, bool isLastChunk) {
        const mnn_detail::Session& s = impl_->session;
        if (!s.wavform) return false;
        return s.wavform(data, size, isLastChunk);
    });
}

bool MnnEngine::generateWaveform(std::string* err) {
    if (!impl_ || impl_->session.llm == nullptr) {
        if (err) *err = "引擎尚未创建 MNN 实例。";
        return false;
    }
    try {
        impl_->session.llm->generateWavform();
        return true;
    } catch (const std::exception& e) {
        LOGE("Exception in generateWavform: %s", e.what());
        if (err) *err = std::string("波形生成异常：") + e.what();
        return false;
    } catch (...) {
        LOGE("Unknown exception in generateWavform");
        if (err) *err = "波形生成异常（未知错误）。";
        return false;
    }
}

std::string MnnEngine::takeLastError() {
    if (!impl_) return std::string();
    // 读后即清：避免下一轮读到上一轮的残留（旧实现专门为此写了 take 语义）。
    std::string value = impl_->session.lastError;
    impl_->session.lastError.clear();
    return value;
}

void MnnEngine::setLastErrorForced(const std::string& msg) {
    if (!impl_) return;
    impl_->session.lastError = msg;
}

// ═════════════════════════════════════════════════════════════════════════
// 可观测性
// ═════════════════════════════════════════════════════════════════════════

Stats MnnEngine::stats() const {
    Stats out;
    if (!impl_ || impl_->session.llm == nullptr) return out;

    try {
        const LlmContext* ctx = impl_->session.llm->getContext();
        if (ctx == nullptr) return out;

        out.prefillTokens = static_cast<int64_t>(ctx->prompt_len);
        out.decodeTokens = static_cast<int64_t>(ctx->gen_seq_len);
        out.prefillMs = static_cast<double>(ctx->prefill_us) / 1000.0;
        out.decodeMs = static_cast<double>(ctx->decode_us) / 1000.0;
        out.loadMs = static_cast<double>(ctx->load_us) / 1000.0;

        // tps 只在耗时有效时算 —— 否则会出现 inf/NaN 被写进 UI。
        if (out.prefillMs > 0.0 && out.prefillTokens > 0) {
            out.prefillTps = static_cast<double>(out.prefillTokens) * 1000.0 / out.prefillMs;
        }
        if (out.decodeMs > 0.0 && out.decodeTokens > 0) {
            out.decodeTps = static_cast<double>(out.decodeTokens) * 1000.0 / out.decodeMs;
        }
        // 温控/线程数：MNN 侧本轮未接温控（线程数在 ScheduleConfig 里，
        // 改它需要重建 interp，不能在 decode 循环里调档）。如实留默认值。
        out.weightBytes = impl_->session.weightBytes;
    } catch (const std::exception& e) {
        LOGE("Exception in stats: %s", e.what());
    } catch (...) {
        LOGE("Unknown exception in stats");
    }
    return out;
}

Backend MnnEngine::resolvedBackend(Phase phase) const {
    (void) phase;
    // 如实回答：MNN 的后端由 llm_config.json 的 backend_type 决定，
    // 且运行期**没有查询接口**（Llm 不暴露当前 backend）。
    // 返回 AUTO（= 交给引擎自己挑）而不是猜一个 CPU/OpenCL ——
    // resolvedBackend 的契约是"本次加载实际生效的后端"，猜出来的数字会误导排障。
    return Backend::AUTO;
}

}  // namespace llm
}  // namespace quro
