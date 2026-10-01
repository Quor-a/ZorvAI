// =============================================================================
// L3/L4 · LlamaEngine 实现（一）：生命周期 / 分词 / 分阶段推理 / 统计
// =============================================================================
// 本 TU 的分工：
//   · L4 llama.cpp 调用（load / unload / tokenize / prefill / decode）
//   · L3 Engine 契约的落地
//   · L5 的接线点（温控在 llama_engine_logic.inc 里，线程/内存见 load）
//
// 同模块另两个 TU：
//   llama_engine_generate.cpp  一体化 generate（KV 前缀复用，行为与旧实现逐字一致）
//   llama_engine_chat.cpp      聊天模板 / 工具调用文法 / 采样参数
//
// 共享的会话级辅助逻辑在 llama_engine_logic.inc（由脚本从旧实现逐字抽出，
// 见 scripts/refactor_split_llama.py 的说明 —— 那些长注释对应的都是一次真机事故，
// 不允许重写，只允许搬家）。
// =============================================================================

#include "llama_engine_impl.h"

#include <android/log.h>

#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <ucontext.h>
#include <unwind.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <memory>
#include <mutex>
#include <sstream>

// 日志与 SET_ERR 宏由 llama_engine_impl.h 统一提供（三个 TU 共用一套 TAG）。

namespace quro {
namespace llm {
namespace llama_detail {

// ─────────────────────────────────────────────────────────────────────────
// 由脚本从旧实现逐字抽出的会话级逻辑（采样链、温控、分词、模板、文法）。
// 只做了三类机械替换：LlamaSessionNative→Session、jint→int32_t、去掉 static。
// ─────────────────────────────────────────────────────────────────────────
#include "llama_engine_logic.inc"

// ─────────────────────────────────────────────────────────────────────────
// 本 TU 独有的两个辅助
// ─────────────────────────────────────────────────────────────────────────

int64_t estimateModelBytes(llama_model * model) {
    if (model == nullptr) return 0;
    // llama_model_size 返回权重字节数。取不到（0）时返回 0，
    // 由上层决定是"未知"还是"不计入预算" —— 不要在这里编一个假数字，
    // 内存互斥（MemoryArbiter）会拿它做判据，假数字比没有更危险。
    const uint64_t bytes = llama_model_size(model);
    return static_cast<int64_t>(bytes);
}

void describeUnavailable(std::string * whyNot) {
    // 本模块只在定义了 QURO_HAS_LLAMA_CPP 的构建里参与编译（见 llm/llama/CMakeLists.txt），
    // 所以「编进来了」本身就等于「llama.cpp 可用」。真正的不可用情形是
    // 「模型加载失败」，那由 load() 报错，不走这里。
    // 这个函数保留是为了让 L3 的 available() 契约完整 —— 契约要求
    // 「不可用时给出人话原因」，而不是要求「一定有不可用的可能」。
    if (whyNot != nullptr) whyNot->clear();
}

}  // namespace llama_detail
}  // namespace llm
}  // namespace quro

namespace quro {
namespace llm {

// ═════════════════════════════════════════════════════════════════════════
// 构造 / 析构
// ═════════════════════════════════════════════════════════════════════════

LlamaEngine::LlamaEngine() : impl_(new Impl()) {}

LlamaEngine::~LlamaEngine() {
    // 析构必须释放全部权重：:llm 进程在切模型时会销毁旧引擎，
    // 若把权重留给 OS 回收，下一次 load 会与尚未回收的旧权重叠加 —— 低端机直接 OOM。
    unload();
}

const char* LlamaEngine::name() const { return "llama.cpp"; }

bool LlamaEngine::available(std::string* whyNot) const {
    llama_detail::describeUnavailable(whyNot);
    return true;
}

// ═════════════════════════════════════════════════════════════════════════
// 生命周期
// ═════════════════════════════════════════════════════════════════════════

int64_t LlamaEngine::weightBytes() const {
    if (!impl_) return 0;
    return impl_->session.weightBytes;
}

bool LlamaEngine::loaded() const {
    if (!impl_) return false;
    return impl_->session.model != nullptr && impl_->session.ctx != nullptr;
}

bool LlamaEngine::load(const LoadConfig& cfg, std::string* err) {
    if (!impl_) {
        if (err) *err = "引擎内部状态缺失（Impl 为空）。";
        return false;
    }

    // 生命周期串行化：load/unload 是重操作（几分钟），且都在 IO 线程上；
    // 生成由 session.genLock 管，两者不共用锁，避免"切模型时被生成堵死"。
    std::lock_guard<std::mutex> guard(impl_->lifecycle);

    // 幂等：先卸干净再装。旧实现由 Kotlin 侧保证不重复 load，
    // 但引擎自持这条不变量更安全（重复 load 会泄漏一份完整的 llama_context，
    // 手机上就是几百 MB 的 KV 分配不回来）。
    // 注意这里调的是**不加锁**的 releaseResources()，不是 unload() ——
    // 本函数已持有 lifecycle，调 unload() 会重入死锁。
    if (loaded()) {
        LOGI("load() 前发现已有会话，先释放再加载");
        releaseResources();
    }

    llama_detail::ensureBackendInit();

    llama_detail::Session& session = impl_->session;
    session.cfg = cfg;

    const std::string& modelPath = cfg.modelPath;
    // 线程数：0 = 交给引擎默认（与旧实现一致，旧实现用的是 4）。
    // 注意 Kotlin 侧 resolveThreads() 会先算好一个具体值再传下来，
    // 所以这里的默认值只在极端路径上生效 —— 保持 4 是为了「不改变行为」。
    const int32_t effectiveThreads = llama_detail::positiveOrDefaultInt(cfg.nThreads, 4);
    const bool gpuOffloadSupported = llama_supports_gpu_offload();
    const int32_t requestedGpuLayers = std::max<int32_t>(0, cfg.nGpuLayers);
    const int32_t effectiveGpuLayers = gpuOffloadSupported ? requestedGpuLayers : 0;
    const bool effectiveUseMmap = cfg.useMmap;
    const bool effectiveFlashAttention = cfg.flashAttention;
    const bool effectiveKvUnified = cfg.kvUnified;
    const bool effectiveOffloadKqv =
        gpuOffloadSupported && effectiveGpuLayers > 0 && cfg.offloadKqv;

    const auto loadStart = std::chrono::steady_clock::now();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = effectiveGpuLayers;
    // llama.cpp 现在通过 load_mode 暴露模型内存映射；旧的 use_mmap/use_mlock 字段已移除。
    mparams.load_mode = effectiveUseMmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    // 【#1116】关闭 extra buffer types（ARM CPU repack）。
    // repack 会把量化权重重排成 Q4_0_4x8 / Q4_K_8x8 等布局，走独立的 NEON/i8mm
    // gemm kernel。这条路径依赖运行时 HWCAP 探测，在 big.LITTLE 机型上大小核能力
    // 不一致时可能选到当前核不支持的 kernel，真机崩溃点（ggml mul_mat）正落在这里。
    // 手机上 repack 的收益有限，稳定性优先，先关掉。
    mparams.use_extra_bufts = false;

    LOGI(
        "Creating llama session. model=%s threads=%d n_ctx=%d n_batch=%d n_ubatch=%d gpu_layers=%d use_mmap=%d flash_attn=%d kv_unified=%d offload_kqv=%d gpu_support=%d",
        modelPath.c_str(),
        effectiveThreads,
        cfg.nCtx,
        cfg.nBatch,
        cfg.nUBatch,
        effectiveGpuLayers,
        effectiveUseMmap ? 1 : 0,
        effectiveFlashAttention ? 1 : 0,
        effectiveKvUnified ? 1 : 0,
        effectiveOffloadKqv ? 1 : 0,
        gpuOffloadSupported ? 1 : 0
    );

    if (requestedGpuLayers > 0 && !gpuOffloadSupported) {
        LOGI("GPU layers requested but this build has no GPU offload backend; continuing on CPU");
    }

    session.model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (session.model == nullptr) {
        LOGE("Failed to load model from file");
        if (err) {
            *err = "模型加载失败：" + modelPath +
                   "。常见原因：文件未完整传输、格式不是 GGUF、或该 GGUF 的量化类型"
                   "不被本构建支持。";
        }
        return false;
    }

    if (!llama_detail::initializeChatTemplatesForSession(&session)) {
        LOGE("Failed to initialize chat templates for model");
        llama_model_free(session.model);
        session.model = nullptr;
        if (err) {
            *err = "该 GGUF 未内嵌可用的聊天模板（chat template）。"
                   "llama.cpp 需要它把消息渲染成模型认识的提示词格式。";
        }
        return false;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = llama_detail::positiveOrDefaultUInt(cfg.nCtx, 0u);
    if (cparams.n_ctx == 0) {
        cparams.n_ctx = static_cast<uint32_t>(llama_model_n_ctx_train(session.model));
    }
    const uint32_t defaultBatch = std::min<uint32_t>(cparams.n_ctx, 512u);
    cparams.n_batch = std::min<uint32_t>(llama_detail::positiveOrDefaultUInt(cfg.nBatch, defaultBatch), cparams.n_ctx);
    cparams.n_ubatch = std::min<uint32_t>(llama_detail::positiveOrDefaultUInt(cfg.nUBatch, cparams.n_batch), cparams.n_batch);
    cparams.n_seq_max = 1;
    cparams.n_threads = effectiveThreads;
    cparams.n_threads_batch = effectiveThreads;
    cparams.flash_attn_type =
        effectiveFlashAttention ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    cparams.offload_kqv = effectiveOffloadKqv;
    cparams.kv_unified = effectiveKvUnified;
    // 计算类型（KV 缓存精度）：对齐 PocketPal 默认 F16。若不显式设置，llama.cpp 按模型原生类型
    // 可能落到 F32 —— KV 缓存体积翻倍，手机上 4096+ 上下文极易在 llama_init_from_model 阶段
    // OOM / 分配超时，表现为「模型加载不完整 / 卡在加载」。F16 是稳定且省内存的默认值。
    cparams.type_k = GGML_TYPE_F16;
    cparams.type_v = GGML_TYPE_F16;
    cparams.abort_callback = llama_detail::abortCallback;
    cparams.abort_callback_data = &session;

    session.ctx = llama_init_from_model(session.model, cparams);
    if (session.ctx == nullptr) {
        LOGE("Failed to create context");
        llama_model_free(session.model);
        session.model = nullptr;
        if (err) {
            *err = "上下文创建失败（KV cache 分配不出来）。"
                   "通常是 n_ctx 过大或设备可用内存不足，可调小上下文窗口。";
        }
        return false;
    }

    llama_set_n_threads(session.ctx, effectiveThreads, effectiveThreads);

    // ── L5 · 温控自适应初始化 ──
    // 配置成**基础**（threads/batch）为当前请求值：档位比例都是相对它算的，
    // 传 0 或别的值会让 COOL 档的线程数不等于用户要的线程数。
    session.thermalPollMs = std::max<int32_t>(0, cfg.thermalPollMs);
    session.thermalThreads = effectiveThreads;
    session.thermalLastCheck = std::chrono::steady_clock::now();
    session.activeThreads = effectiveThreads;
    session.activeBatch = static_cast<int32_t>(cparams.n_batch);
    if (session.thermalPollMs > 0) {
        ThermalGovernor::instance().configure(
            session.thermalPollMs, effectiveThreads, static_cast<int>(cparams.n_batch));
        LOGI("温控自适应已启用：pollMs=%d baseThreads=%d baseBatch=%u",
             session.thermalPollMs, effectiveThreads, (unsigned) cparams.n_batch);
    }

    session.samplingParams = llama_detail::SamplingParamsNative{};
    session.samplingParams.seed = static_cast<uint32_t>(std::rand());

    if (!llama_detail::rebuildSamplerForSession(&session)) {
        LOGE("Failed to create sampler chain");
        llama_free(session.ctx);
        llama_model_free(session.model);
        session.ctx = nullptr;
        session.model = nullptr;
        if (err) *err = "采样链创建失败（内存不足或词表异常）。";
        return false;
    }

    session.cancel.store(false);
    session.kvDirty = true;
    session.kvPrefix.clear();
    session.kvPast = 0;
    session.lastError.clear();

    session.weightBytes = llama_detail::estimateModelBytes(session.model);
    session.loadMs = std::chrono::duration_cast<std::chrono::microseconds>(
                         std::chrono::steady_clock::now() - loadStart)
                         .count() / 1000.0;

    LOGI("llama 会话就绪：权重 %lld MB | 加载 %.0f ms",
         (long long) (session.weightBytes / 1024 / 1024), session.loadMs);
    return true;
}

void LlamaEngine::releaseResources() {
    llama_detail::Session& session = impl_->session;
    if (session.ctx != nullptr || session.model != nullptr || session.sampler != nullptr) {
        LOGI("释放 llama 会话（归还权重 %lld MB）",
             (long long) (session.weightBytes / 1024 / 1024));
    }

    if (session.sampler != nullptr) {
        llama_sampler_free(session.sampler);
        session.sampler = nullptr;
    }
    if (session.ctx != nullptr) {
        llama_free(session.ctx);
        session.ctx = nullptr;
    }
    session.chatTemplates.reset();
    if (session.model != nullptr) {
        llama_model_free(session.model);
        session.model = nullptr;
    }

    session.kvPrefix.clear();
    session.kvPast = 0;
    session.kvDirty = true;
    session.weightBytes = 0;

    // 思考段状态也是会话级状态：换模型/卸载后必须复位。
    // 不复位的话，若上一个会话结束在思考段中途，新会话的第一段正文
    // 会被错误地判成思考内容（反之亦然）—— 表现为"新会话开头几个字不见了"。
    session.thinkSplitter.reset();

    // 思考开关也绑在会话上：模板已释放 → 探测缓存必须跟着失效，
    // 否则换成一个不支持 enable_thinking 的模型后，supportsThinking 仍返回旧结果。
    // 开关本身回到"未设置"，让新模型走它自己模板的默认分支。
    session.thinkingMode = -1;
    session.supportsThinking = -1;

    // 温控是设备级状态，不随会话销毁 —— 但要把档位基线复位，
    // 否则新会话的第一段 decode 会继承上一个会话的档位判断。
    ThermalGovernor::instance().reset();
}

bool LlamaEngine::unload() {
    if (!impl_) return true;
    std::lock_guard<std::mutex> guard(impl_->lifecycle);
    releaseResources();
    return true;
}

// ═════════════════════════════════════════════════════════════════════════
// 分词
// ═════════════════════════════════════════════════════════════════════════

bool LlamaEngine::tokenize(const std::string& text, std::vector<int32_t>* out, std::string* err) {
    if (out == nullptr) {
        if (err) *err = "输出参数为空。";
        return false;
    }
    out->clear();
    if (!loaded()) {
        if (err) *err = "引擎尚未加载模型。";
        return false;
    }
    const llama_vocab* vocab = llama_model_get_vocab(impl_->session.model);
    const std::vector<llama_token> tokens =
        llama_detail::tokenizeTextToVector(vocab, text, true);
    out->reserve(tokens.size());
    for (const llama_token t : tokens) out->push_back(static_cast<int32_t>(t));
    return true;
}

bool LlamaEngine::detokenize(const std::vector<int32_t>& tokens, std::string* out,
                             std::string* err) {
    if (out == nullptr) {
        if (err) *err = "输出参数为空。";
        return false;
    }
    out->clear();
    if (!loaded()) {
        if (err) *err = "引擎尚未加载模型。";
        return false;
    }
    const llama_vocab* vocab = llama_model_get_vocab(impl_->session.model);

    std::vector<llama_token> raw;
    raw.reserve(tokens.size());
    for (const int32_t t : tokens) raw.push_back(static_cast<llama_token>(t));

    std::vector<char> buf(std::max<size_t>(64, raw.size() * 8 + 32));
    int32_t n = llama_detokenize(vocab, raw.data(), static_cast<int32_t>(raw.size()),
                                buf.data(), static_cast<int32_t>(buf.size()), true, false);
    if (n < 0) {
        buf.resize(static_cast<size_t>(-n));
        n = llama_detokenize(vocab, raw.data(), static_cast<int32_t>(raw.size()),
                             buf.data(), static_cast<int32_t>(buf.size()), true, false);
    }
    if (n < 0) {
        if (err) *err = "反分词失败（缓冲区反复不足，疑似词表异常）。";
        return false;
    }
    out->assign(buf.data(), buf.data() + n);
    return true;
}

int32_t LlamaEngine::countTokens(const std::string& text, std::string* err) {
    if (!loaded()) {
        if (err) *err = "引擎尚未加载模型。";
        return -1;
    }
    const llama_vocab* vocab = llama_model_get_vocab(impl_->session.model);
    return llama_detail::tokenizeText(vocab, text, true);
}

// ═════════════════════════════════════════════════════════════════════════
// 分阶段推理（prefill / decode）
// ═════════════════════════════════════════════════════════════════════════
// 它们是 Engine 契约要求的"可分阶段调用"形式；一体化路径见 generate()。
// 分阶段版本**不做 KV 前缀复用**（调用方给的就是完整序列），语义简单直接。
//
// 为什么并存：generate() 承载的是真机调出来的前缀复用策略，不能拆；
// 而"先 prefill 再反复 decode"是给上层做阶段级控制（如 prefill 走 GPU、
// decode 走 CPU 的后端分离）留的入口 —— 那两个阶段的后端可以不同。
// ═════════════════════════════════════════════════════════════════════════

bool LlamaEngine::prefill(const std::vector<int32_t>& promptTokens, const Callbacks& cbs,
                          std::string* err) {
    if (!loaded()) {
        if (err) *err = "引擎尚未加载模型。";
        return false;
    }
    if (promptTokens.empty()) {
        if (err) *err = "提示词为空。";
        return false;
    }

    llama_detail::Session& session = impl_->session;
    std::lock_guard<std::mutex> gen(session.genLock);
    session.cancel.store(false);
    session.lastError.clear();

    const llama_vocab* vocab = llama_model_get_vocab(session.model);
    const int32_t nCtx = static_cast<int32_t>(llama_n_ctx(session.ctx));

    std::vector<llama_token> tokens;
    tokens.reserve(promptTokens.size());
    for (const int32_t t : promptTokens) tokens.push_back(static_cast<llama_token>(t));

    // 与 generate 同一条纪律：绝不能送 EOG/EOS 进 prefill。
    while (!tokens.empty() && llama_vocab_is_eog(vocab, tokens.back())) tokens.pop_back();
    if (tokens.empty()) {
        if (err) *err = "token 序列全是结束符（EOG/EOS），无法处理。";
        return false;
    }

    if (nCtx > 0 && static_cast<int32_t>(tokens.size()) > nCtx) {
        const size_t drop = tokens.size() - static_cast<size_t>(nCtx);
        tokens.erase(tokens.begin(), tokens.begin() + static_cast<std::ptrdiff_t>(drop));
        LOGI("prefill 按 n_ctx 截断：保留 %d 丢弃 %zu", nCtx, drop);
    }

    // 清空 KV：分阶段 API 的契约是"我给的就是完整序列"。
    llama_memory_t mem = llama_get_memory(session.ctx);
    if (mem != nullptr) llama_memory_clear(mem, true);

    const uint32_t nBatch = llama_n_batch(session.ctx);
    // 手机 CPU prefill 大 chunk 单段可能 >30s，被系统/库层超时掐死；改小到 256
    // 让每段更快完成、进度条更频繁更新（与 generate 内的口径一致）。
    const int32_t chunkSize = static_cast<int32_t>(std::min<uint32_t>(nBatch, 256u));
    const int32_t total = static_cast<int32_t>(tokens.size());
    const llama_seq_id seq0 = 0;

    llama_batch batch = llama_batch_init(nBatch, 0, 1);
    const auto t0 = std::chrono::steady_clock::now();
    bool failed = false;

    for (int32_t offset = 0; offset < total; offset += chunkSize) {
        if (session.cancel.load()) {
            if (err) *err = "prefill 被取消。";
            failed = true;
            break;
        }
        const int32_t chunk = std::min<int32_t>(chunkSize, total - offset);
        for (int32_t i = 0; i < chunk; i++) {
            batch.token[i] = tokens[offset + i];
            batch.pos[i] = offset + i;
            batch.n_seq_id[i] = 1;
            // ⚠️ 写值，不覆写指针 —— 见 generate() 里的同一条长注释：
            // 覆写 seq_id[i] 会让 llama_batch_free 去 free 一个栈地址 → SIGABRT。
            batch.seq_id[i][0] = seq0;
            batch.logits[i] = (offset + i == total - 1) ? 1 : 0;
        }
        batch.n_tokens = chunk;

        if (cbs.onProgress != nullptr) {
            ProgressChunk pc{"prefill", offset, total};
            cbs.onProgress(cbs.user, pc);
        }

        const int32_t ret = llama_decode(session.ctx, batch);
        if (ret != 0) {
            // 返回码语义：0=成功；1=找不到 KV slot（失败）；2=aborted；<-1=致命。
            if (err) {
                std::ostringstream os;
                os << "prefill 解码失败 llama_decode ret=" << ret
                   << "（offset=" << offset << "/" << total << "）";
                *err = os.str();
            }
            session.lastError = err != nullptr ? *err : "prefill 解码失败";
            failed = true;
            break;
        }
        llama_detail::applyThermalAdvice(&session);
    }
    llama_batch_free(batch);

    session.prefillMs = std::chrono::duration_cast<std::chrono::microseconds>(
                            std::chrono::steady_clock::now() - t0).count() / 1000.0;
    session.prefillTokens = total;

    if (failed) {
        session.kvDirty = true;
        return false;
    }

    session.kvPrefix = tokens;
    session.kvPast = total;
    session.kvDirty = false;
    return true;
}

bool LlamaEngine::decode(const GenParams& params, const Callbacks& cbs, std::string* err) {
    if (!loaded()) {
        if (err) *err = "引擎尚未加载模型。";
        return false;
    }
    llama_detail::Session& session = impl_->session;
    if (session.sampler == nullptr) {
        if (err) *err = "采样链缺失（会话状态异常）。";
        return false;
    }

    std::lock_guard<std::mutex> gen(session.genLock);

    const llama_vocab* vocab = llama_model_get_vocab(session.model);
    const int32_t nCtx = static_cast<int32_t>(llama_n_ctx(session.ctx));
    const int maxNew = params.maxTokens > 0 ? params.maxTokens : 256;

    llama_sampler_reset(session.sampler);

    llama_batch batch = llama_batch_init(1, 0, 1);
    batch.n_seq_id[0] = 1;
    batch.seq_id[0][0] = 0;

    const auto t0 = std::chrono::steady_clock::now();
    std::vector<llama_token> generated;
    generated.reserve(static_cast<size_t>(maxNew));
    std::string prevDecoded;
    std::string pendingUtf8;
    std::vector<char> detokBuf;
    int32_t nPast = session.kvPast;
    bool dirty = false;

    for (int i = 0; i < maxNew; i++) {
        if (session.cancel.load()) {
            dirty = true;
            break;
        }
        // 接收方（JNI 回调被 Java 拒绝）请求停止 —— 与 cancel 走同一条 break 路径，
        // 但不置 dirty：prompt 部分仍然是干净的 KV，可缓存（与旧实现一致）。
        if (cbs.stopped()) {
            LOGI("decode 被接收方请求停止");
            break;
        }

        llama_detail::applyThermalAdvice(&session);

        const llama_token newToken = llama_sampler_sample(session.sampler, session.ctx, -1);
        llama_sampler_accept(session.sampler, newToken);

        if (llama_vocab_is_eog(vocab, newToken)) {
            if (i == 0) {
                session.lastError =
                    "模型在第一个 token 就输出了结束符。通常意味着聊天模板与该 GGUF 不匹配，"
                    "或提示词格式有误。";
                if (err) *err = session.lastError;
            }
            break;
        }

        generated.push_back(newToken);

        int32_t detokCap = std::max<int32_t>(64, static_cast<int32_t>(generated.size() * 8 + 32));
        detokBuf.resize(static_cast<size_t>(detokCap));
        int32_t nDetok = llama_detokenize(vocab, generated.data(),
                                          static_cast<int32_t>(generated.size()),
                                          detokBuf.data(), static_cast<int32_t>(detokBuf.size()),
                                          true, false);
        if (nDetok < 0) {
            detokBuf.resize(static_cast<size_t>(-nDetok));
            nDetok = llama_detokenize(vocab, generated.data(),
                                      static_cast<int32_t>(generated.size()),
                                      detokBuf.data(), static_cast<int32_t>(detokBuf.size()),
                                      true, false);
        }
        std::string decodedNow;
        if (nDetok > 0) decodedNow.assign(detokBuf.data(), detokBuf.data() + nDetok);

        std::string delta;
        if (!prevDecoded.empty() && decodedNow.rfind(prevDecoded, 0) == 0) {
            delta = decodedNow.substr(prevDecoded.size());
        } else {
            delta = decodedNow;
        }
        prevDecoded = decodedNow;

        if (!delta.empty() && cbs.onToken != nullptr) {
            // UTF-8 边界缓冲：多字节 CJK 可能跨 token，delta 末尾可能是不完整序列。
            // 直接交给上层会被替换成 U+FFFD（�）—— 这就是用户报过的花字。
            pendingUtf8 += delta;
            std::string complete;
            size_t ci = 0;
            while (ci < pendingUtf8.size()) {
                const int charLen = llama_detail::utf8CharLength(
                    static_cast<unsigned char>(pendingUtf8[ci]));
                if (charLen == 0 || ci + static_cast<size_t>(charLen) > pendingUtf8.size()) break;
                complete.append(pendingUtf8, ci, static_cast<size_t>(charLen));
                ci += static_cast<size_t>(charLen);
            }
            if (ci > 0) pendingUtf8.erase(0, ci);
            if (!complete.empty()) {
                TokenChunk chunk;
                chunk.text = complete.data();
                chunk.len = complete.size();
                chunk.isThinking = false;
                cbs.onToken(cbs.user, chunk);
            }
        }

        if (nCtx > 0 && nPast >= nCtx) {
            LOGI("context window reached: n_past=%d n_ctx=%d", nPast, nCtx);
            break;
        }

        batch.token[0] = newToken;
        batch.pos[0] = nPast;
        batch.logits[0] = 1;
        batch.n_tokens = 1;

        const int32_t ret = llama_decode(session.ctx, batch);
        if (ret != 0) {
            if (ret == 2 || ret == 1) {
                // 2 = aborted（取消）；1 = KV 满。已生成的内容有效，正常收尾。
                LOGI("decode 提前结束 ret=%d n_past=%d", ret, nPast);
                dirty = true;
                break;
            }
            session.kvDirty = true;
            llama_batch_free(batch);
            if (err) {
                std::ostringstream os;
                os << "生成阶段解码失败 llama_decode ret=" << ret
                   << "（已生成 " << i << " token）";
                *err = os.str();
            }
            session.lastError = err != nullptr ? *err : "生成阶段解码失败";
            return false;
        }
        nPast += 1;
    }

    // 收尾：把残余的不完整字节吐出去（截断输出宁可显示 � 也不要静默丢弃）。
    if (!pendingUtf8.empty() && cbs.onToken != nullptr) {
        TokenChunk chunk;
        chunk.text = pendingUtf8.data();
        chunk.len = pendingUtf8.size();
        cbs.onToken(cbs.user, chunk);
        pendingUtf8.clear();
    }

    session.decodeMs = std::chrono::duration_cast<std::chrono::microseconds>(
                           std::chrono::steady_clock::now() - t0).count() / 1000.0;
    session.decodeTokens += static_cast<int64_t>(generated.size());

    // 生成尾不进缓存（与 generate 同口径）：生成期间 KV = prompt + 裸 assistant token，
    // 而下一轮的 prompt 是经聊天模板重新渲染的，两者在结尾必然分叉。
    llama_memory_t mem = llama_get_memory(session.ctx);
    if (mem != nullptr && !dirty) {
        session.kvPast = nPast;
    } else {
        session.kvDirty = true;
    }

    llama_batch_free(batch);
    return true;
}

// ═════════════════════════════════════════════════════════════════════════
// 取消 / KV 重置 / 统计
// ═════════════════════════════════════════════════════════════════════════

void LlamaEngine::cancel() {
    if (!impl_) return;
    // 只置标志，绝不在这里抢 genLock —— 取消必须能从 UI 线程立刻返回。
    // （曾想过"顺手把 decode 也停掉"，那需要抢锁，而锁正被 decode 持有，
    //  结果就是 UI 线程卡住几秒 —— 比不取消更糟。）
    impl_->session.cancel.store(true);
}

bool LlamaEngine::resetKv(std::string* err) {
    if (!impl_) {
        if (err) *err = "引擎内部状态缺失。";
        return false;
    }
    llama_detail::Session& session = impl_->session;
    if (session.ctx == nullptr) {
        if (err) *err = "引擎尚未加载模型。";
        return false;
    }
    // 工具轮（applyStructuredChatTemplate 渲染的 prompt）与新模型/新会话切换时调用，
    // 确保下一轮不会把错误的 KV 前缀当作上下文续写。
    llama_memory_t mem = llama_get_memory(session.ctx);
    if (mem != nullptr) llama_memory_clear(mem, true);
    session.kvPrefix.clear();
    session.kvPast = 0;
    session.kvDirty = true;

    // 同上：KV 清了就代表"上下文断了"，思考段状态也必须跟着断，
    // 否则新一段输出会被上一段的段状态污染。
    session.thinkSplitter.reset();
    return true;
}

Stats LlamaEngine::stats() const {
    Stats s;
    if (!impl_) return s;
    const llama_detail::Session& session = impl_->session;

    s.loadMs = session.loadMs;
    s.prefillMs = session.prefillMs;
    s.decodeMs = session.decodeMs;
    s.prefillTokens = session.prefillTokens;
    s.decodeTokens = session.decodeTokens;
    if (session.prefillMs > 0.0) {
        s.prefillTps = static_cast<double>(session.prefillTokens) * 1000.0 / session.prefillMs;
    }
    if (session.decodeMs > 0.0) {
        s.decodeTps = static_cast<double>(session.decodeTokens) * 1000.0 / session.decodeMs;
    }

    // 温控可观测性：这两个值就是「有没有真的启动降频保护」的证据。
    const ThermalAdvice advice = ThermalGovernor::instance().last();
    s.thermalHeadroomPct = advice.headroomPct;
    s.thermalStatus = advice.platformStatus;
    s.thermallyThrottled = advice.throttled;

    s.activeThreads = session.activeThreads;
    s.activeBatch = session.activeBatch;
    s.weightBytes = session.weightBytes;

    s.prefillBackendUsed = resolvedBackend(Phase::PREFILL);
    s.decodeBackendUsed = resolvedBackend(Phase::DECODE);
    return s;
}

Backend LlamaEngine::resolvedBackend(Phase phase) const {
    if (!impl_) return Backend::CPU;
    // llama.cpp 的 n_gpu_layers 是**整图属性**：一次 load 决定全部层的后端，
    // 运行期无法让 prefill 走 GPU 而 decode 回 CPU（那需要重建上下文并迁移
    // KV，代价远超收益 —— 见 engine_types.h 里 Phase 的说明）。
    // 所以本引擎如实上报：只要 offload 了层，两者都是 GPU；否则都是 CPU。
    // 「按阶段选后端」在 MNN 侧才成立（它的 KV cache 是显式的 mContext，可跨后端复用）。
    if (impl_->session.cfg.nGpuLayers > 0 && llama_supports_gpu_offload()) {
        return Backend::OPENCL;
    }
    (void) phase;
    return Backend::CPU;
}

std::string LlamaEngine::lastError() const {
    if (!impl_) return std::string();
    return impl_->session.lastError;
}

void LlamaEngine::setLastErrorForced(const std::string& msg) {
    if (!impl_) return;
    impl_->session.lastError = msg;
}

}  // namespace llm
}  // namespace quro
