// =============================================================================
// L3/L4 · MnnEngine 实现（二）：流式生成
// =============================================================================
// 本 TU 承载 MNN 引擎最核心的一段：把 MNN 的 `std::ostream` 输出桥接到 L3 的
// `Callbacks`。架构图上「L3 统一引擎抽象 → L4 引擎与后端」这条箭头在这里落地。
//
// 旧构架的问题（这就是为什么这次不是加功能）：
//   mnnllmnative.cpp 里有两个几乎逐字相同的流式函数
//   （runStreamGenerationWithInputIds 235 行 / runStreamGenerationWithHistory 235 行），
//   唯一的差别是首次调用 `llm->response(inputTokens, ...)` 还是
//   `llm->response(history, ...)`。两份重复代码意味着任何一次修复都得记得改两处 ——
//   事实上已经出现过"只改了一处"的隐患。
//   现在合并成一份 runStreamCore()：**逻辑逐字保留**，只把差异参数化。
//
// 搬家不改逻辑：以下真机经验全部原样保留，勿删注释。
//   · `<eop>` 是 MNN 的结束标记，必须从 payload 里剥掉并把 shouldStop 置位
//   · UTF-8 跨 token 边界缓冲（CJK 不花字）
//   · flush 时机 = 16 字节 / 标点 / 换行 / 出现 `<eop>`（首字延迟 vs 吞吐的平衡点）
//   · xsputn 在取消/停止时**返回 0**（不是 n）—— 让 ostream 进 failbit，
//     这是让 MNN 内部尽快放弃继续写出的唯一手段
// =============================================================================

#include "mnn_engine_impl.h"

#include <exception>
#include <functional>
#include <ostream>
#include <streambuf>
#include <string>
#include <vector>

namespace quro {
namespace llm {
namespace mnn_detail {

// ═════════════════════════════════════════════════════════════════════════
// 流式 sink：MNN 的 ostream 输出 → L3 Callbacks
// ═════════════════════════════════════════════════════════════════════════
// 为什么 sink 里存的是 Session 与 Callbacks 的指针，而不是 JNIEnv/jobject：
//   引擎层不该知道 JNI 存在。旧实现把所有东西（JavaVM、GlobalRef、jmethodID）
//   直接塞进 StreamContext，于是"流式生成"和"JNI 回调桥"是两个不可分的概念，
//   测试、复用到 PC 侧工具链都做不到。现在 JNI 的那一半在 mnn_jni.cpp。
struct StreamSink {
    const Callbacks* cbs = nullptr;

    /// **非 const**：思考段分流器是可变状态（要往里喂 token、并在收尾 finish）。
    /// 旧实现这里是 const Session*，只读 cancel 标志；
    /// 引入分流后必须放开 const —— 否则只能把 splitter 放别处，
    /// 又要多一套"这些状态属于谁"的扯皮。
    Session* session = nullptr;

    /// 待发送缓冲。累积到 flush 条件满足才一次性下发。
    std::string buffer;

    /// 接收方（或 `<eop>`）要求停止。
    bool shouldStop = false;

    /// 是否因用户取消而停止（与 shouldStop 分开记录，便于日志区分原因）。
    bool cancelled = false;
};

// 字符数与旧实现一致（16 字节）—— 改小会碎成单字、改大会让首字延迟变明显。
static constexpr size_t kFlushByteThreshold = 16;

/// 把一个 ThinkSplit 的两段按归属回调出去。
/// @return true = 接收方要求停止
inline bool emitThinkSplit(const ThinkSplit& s, const Callbacks& cbs) {
    if (!s.thinking.empty()) {
        TokenChunk c;
        c.text = s.thinking.data();
        c.len = s.thinking.size();
        c.isThinking = true;   // ★ 此前从未置 true 的分支：思考内容走独立通道
        cbs.onToken(cbs.user, c);
        if (cbs.stopped()) {
            return true;
        }
    }
    if (!s.visible.empty()) {
        TokenChunk c;
        c.text = s.visible.data();
        c.len = s.visible.size();
        c.isThinking = false;
        cbs.onToken(cbs.user, c);
        if (cbs.stopped()) {
            return true;
        }
    }
    return false;
}

class CallbackStreamBuf : public std::streambuf {
public:
    explicit CallbackStreamBuf(StreamSink* sink) : sink_(sink) {}

    /// 把当前缓冲下发一次。`<eop>` 出现时截断并置 shouldStop（与旧实现一致）。
    void flushToCallbacks() {
        if (sink_ == nullptr || sink_->buffer.empty() || sink_->shouldStop) return;

        static const std::string endMarker = "<eop>";
        const size_t pos = sink_->buffer.find(endMarker);
        std::string payload = sink_->buffer;
        if (pos != std::string::npos) {
            payload = sink_->buffer.substr(0, pos);
            sink_->shouldStop = true;
        }

        if (payload.empty()) {
            sink_->buffer.clear();
            return;
        }

        if (sink_->cbs != nullptr && sink_->cbs->onToken != nullptr) {
            // 过思考段分流器：思考增量与正文增量分别回调（**最多两次**，不是一次）。
            // 一个 flush 窗口里完全可能同时包含思考与正文（例如 `<think>a</think>b`
            // 落在同一个 16 字节窗口里），调用方不能假设"一次 flush 一次回调"。
            //
            // 生命周期：payload 在回调返回后即失效（TokenChunk 契约），
            // 但分流器内部已把内容拷进自己的 std::string，
            // 拆出的两段各自独立、都在本次调用期间有效。
            if (sink_->session != nullptr) {
                emitThinkSplit(sink_->session->thinkSplitter.feed(payload), *sink_->cbs);
            } else {
                // 没有 session（理论上不会发生）：退化成旧行为，不丢数据。
                TokenChunk chunk;
                chunk.text = payload.data();
                chunk.len = payload.size();
                chunk.isThinking = false;
                sink_->cbs->onToken(sink_->cbs->user, chunk);
            }
            // 接收方（Java 侧 onToken 返回 false）要求停止。
            if (sink_->cbs->stopped()) {
                sink_->shouldStop = true;
                LOGD("Stream stopped by callback");
            }
        }

        sink_->buffer.clear();
    }

protected:
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        if (sink_ == nullptr || n <= 0) return n;

        if (sink_->shouldStop) return 0;

        // 取消检查放在这里（而不是只在循环条件里）：MNN 一次 generate(1)
        // 内部可能写入多次，逐个 chunk 检查才能让"点停止"在百毫秒级生效。
        if (sink_->session != nullptr && sink_->session->cancel.load()) {
            LOGD("Generation cancelled by user");
            sink_->cancelled = true;
            sink_->shouldStop = true;
            // 返回 0（不是 n）：让 std::ostream 进入 failbit。
            // 这是让 MNN 内部尽快停止继续写出的唯一手段，旧实现如此。
            return 0;
        }

        const std::string completeChars = extractCompleteUtf8(s, static_cast<size_t>(n));
        if (completeChars.empty()) {
            // 全是半个字符 —— 已经存进 pending，按"已消费"返回，避免 ostream 报错。
            return n;
        }

        sink_->buffer.append(completeChars);
        if (shouldFlush(completeChars)) {
            flushToCallbacks();
        }
        return n;
    }

private:
    static int utf8CharLength(unsigned char byte) {
        if ((byte & 0x80) == 0) return 1;
        if ((byte & 0xE0) == 0xC0) return 2;
        if ((byte & 0xF0) == 0xE0) return 3;
        if ((byte & 0xF8) == 0xF0) return 4;
        return 0;
    }

    static bool containsFlushDelimiter(const std::string& text) {
        // 中英标点都要认：只认 ASCII 标点会让中文长句一直攒到 16 字节才吐。
        return text.find('\n') != std::string::npos ||
               text.find('.') != std::string::npos ||
               text.find('!') != std::string::npos ||
               text.find('?') != std::string::npos ||
               text.find("\xE3\x80\x82") != std::string::npos ||  // 。
               text.find("\xEF\xBC\x81") != std::string::npos ||  // ！
               text.find("\xEF\xBC\x9F") != std::string::npos;    // ？
    }

    /// 只返回**完整**的 UTF-8 字符，尾部不完整字节留到下一次。
    std::string extractCompleteUtf8(const char* s, size_t n) {
        pendingUtf8_.append(s, n);

        size_t i = 0;
        std::string completeChars;
        while (i < pendingUtf8_.size()) {
            const int length = utf8CharLength(static_cast<unsigned char>(pendingUtf8_[i]));
            if (length == 0 || i + static_cast<size_t>(length) > pendingUtf8_.size()) {
                break;  // 非法字节或尾部不完整 —— 等下一次写入
            }
            completeChars.append(pendingUtf8_, i, static_cast<size_t>(length));
            i += static_cast<size_t>(length);
        }
        if (i > 0) {
            pendingUtf8_.erase(0, i);
        }
        return completeChars;
    }

    bool shouldFlush(const std::string& completeChars) const {
        return sink_->buffer.find("<eop>") != std::string::npos ||
               sink_->buffer.size() >= kFlushByteThreshold ||
               containsFlushDelimiter(completeChars);
    }

    StreamSink* sink_;
    /// 跨 token 的不完整 UTF-8 尾巴（旧实现的 mPendingUtf8Bytes）。
    std::string pendingUtf8_;
};

// ═════════════════════════════════════════════════════════════════════════
// 流式生成核心（原两份重复函数合并）
// ═════════════════════════════════════════════════════════════════════════
namespace {

/// MNN 的单次生成上限（旧实现硬编码 8192；超过会让手机上一次性占满 KV）。
constexpr int kMaxNewTokensCeiling = 8192;
constexpr int kDefaultNewTokens = 512;

/**
 * 跑一次流式生成。
 *
 * @param resetFirst    是否先 llm->reset() 清 KV。
 *                      分阶段调用时 decode 必须为 false —— 否则会把 prefill 的 KV 抹掉。
 * @param firstCall     首次调用（response）。doFirstCall=false 时不被调用。
 * @param doFirstCall   是否执行首次调用。prefill / generate 为 true；decode 为 false。
 * @param loopTokens    generate(1) 循环的上限。注意 MNN 的首次 response 就已经产出了
 *                      1 个 token，所以 generate 路径的总量是 1 + loopTokens。
 */
template <typename FirstCall>
bool runStreamCore(Session* session, bool resetFirst, bool doFirstCall, FirstCall&& firstCall,
                   int loopTokens, const Callbacks& cbs, std::string* err) {
    StreamSink sink;
    sink.cbs = &cbs;
    sink.session = session;

    CallbackStreamBuf buf(&sink);
    std::ostream outputStream(&buf);

    try {
        if (resetFirst) {
            session->llm->reset();

            // ── 顺带复位思考分流器，判据与 KV 完全一致 ────────────────────
            // resetFirst=true  = 新一轮生成（prefill / generate）→ 文本流从头开始，必须复位；
            // resetFirst=false = 同一轮的后半段（decode 接在 prefill 之后）→ 文本流连续，
            //                    **绝不能**复位，否则会把 prefill 阶段已进入的思考段状态丢掉。
            // 这正是"分流器状态的生命周期 = 一轮生成"这条语义的落地位置。
            //
            // 不复位的后果：上一轮若在思考段中途结束（未闭合 </think>），
            // segment_ 残留为 Thinking，本轮正文被整段判成思考 → 正文通道为空。
            session->thinkSplitter.reset();
        }

        if (doFirstCall) {
            firstCall(outputStream);
            if (resetFirst) {
                session->prefillTokens += 1;
            }
        }

        int produced = 0;
        while (!sink.shouldStop && produced < loopTokens && !session->cancel.load()) {
            session->llm->generate(1);
            produced++;
        }
        session->decodeTokens += produced;

        // 收尾：把不足 flush 阈值的尾巴也吐出去（否则最后一个短句永远不显示）。
        if (!sink.buffer.empty() && !sink.shouldStop) {
            buf.flushToCallbacks();
        }

        // 分流器里可能还压着暂缓的尾字节（未闭合思考段 / 不完整标签前缀）。
        // 不吐就会静默丢字节 —— 表现为回复末尾少几个字。
        // 判据用 cancelled 而不是 shouldStop：`<eop>` 会置 shouldStop 但那是**正常结束**，
        // 其之前的内容仍必须吐出；只有用户主动取消才不再补发。
        if (sink.session != nullptr && !sink.cancelled && !cbs.stopped()) {
            emitThinkSplit(sink.session->thinkSplitter.finish(), cbs);
        }
        return true;
    } catch (const std::exception& e) {
        LOGE("Exception in stream generation: %s", e.what());
        // 只在没有更具体原因时才写 —— 准备阶段（模板/分词）的错误比这里更有信息量。
        if (session->lastError.empty()) {
            session->lastError = std::string("E_MNN_STREAM_THROW|") + e.what();
        }
        if (err != nullptr) *err = session->lastError;
        return false;
    } catch (...) {
        LOGE("Unknown exception in stream generation");
        if (session->lastError.empty()) {
            session->lastError = "E_MNN_STREAM_THROW|unknown native exception";
        }
        if (err != nullptr) *err = session->lastError;
        return false;
    }
}

int clampNewTokens(int requested) {
    int n = requested > 0 ? requested : kDefaultNewTokens;
    if (n > kMaxNewTokensCeiling) n = kMaxNewTokensCeiling;
    return n;
}

/// 取 Session 而不是 MnnEngine::Impl —— Impl 是 MnnEngine 的**私有**嵌套类型，
/// 命名它会在类外触发访问权限错误（编译期直接报 private）。
/// 引擎成员函数传 &impl_->session 进来，既拿到需要的东西，又不越界。
bool requireReady(Session* session, std::string* err) {
    if (session == nullptr || session->llm == nullptr) {
        if (err != nullptr) *err = "引擎尚未创建 MNN 实例。";
        return false;
    }
    return true;
}

}  // namespace

}  // namespace mnn_detail
}  // namespace llm
}  // namespace quro

// ═════════════════════════════════════════════════════════════════════════
// L3 Engine 契约：generate / prefill / decode
// ═════════════════════════════════════════════════════════════════════════

namespace quro {
namespace llm {

bool MnnEngine::generate(const std::string& prompt, const GenParams& params,
                         const Callbacks& cbs, std::string* err) {
    if (!mnn_detail::requireReady(&impl_->session, err)) return false;

    // 与旧实现一致：整个生成期间串行化（MNN 内部只有一份 KV）。
    // cancel() 不拿这把锁，所以取消仍然即时。
    std::lock_guard<std::mutex> gen(impl_->session.genLock);

    impl_->session.cancel.store(false);
    const int total = mnn_detail::clampNewTokens(params.maxTokens);

    std::vector<int> inputTokens;
    try {
        inputTokens = impl_->session.llm->tokenizer_encode(prompt);
    } catch (const std::exception& e) {
        LOGE("Exception encoding prompt: %s", e.what());
        impl_->session.lastError = std::string("E_MNN_EMPTY_TOKENS|tokenizer_encode threw: ") + e.what();
        if (err) *err = impl_->session.lastError;
        return false;
    }
    if (inputTokens.empty()) {
        impl_->session.lastError =
            "E_MNN_EMPTY_TOKENS|prompt produced 0 tokens (tokenizer files may be missing or mismatched)";
        LOGE("Refusing to run inference with 0 prompt tokens");
        if (err) *err = impl_->session.lastError;
        return false;
    }

    LOGI("Stream prompt ready: %zu tokens, max_new=%d", inputTokens.size(), total);
    auto firstCall = [this, &inputTokens](std::ostream& os) {
        // 首次调用会顺带采样出第 1 个 token 并写进 os（这是 MNN 的 API 形状）。
        impl_->session.llm->response(inputTokens, &os, "<eop>", 1);
    };
    return mnn_detail::runStreamCore(&impl_->session, /*resetFirst=*/true, /*doFirstCall=*/true,
                                     firstCall, total > 0 ? total - 1 : 0, cbs, err);
}

bool MnnEngine::generateFromHistory(const ChatMessages& history, const GenParams& params,
                                    const Callbacks& cbs, std::string* err) {
    if (!mnn_detail::requireReady(&impl_->session, err)) return false;

    std::lock_guard<std::mutex> gen(impl_->session.genLock);
    impl_->session.cancel.store(false);

    const int total = mnn_detail::clampNewTokens(params.maxTokens);
    const ChatMessages historyCopy = history;  // 捕获前拷贝：调用方的 vector 可能先失效

    // 旧实现在 history 为空时也照样调 response —— 由 MNN 自己处理（会渲染出
    // 只有 generation prompt 的模板）。这里不额外拦，保持行为一致。
    LOGD("Starting stream generation with %zu history messages", historyCopy.size());

    auto firstCall = [this, &historyCopy](std::ostream& os) {
        impl_->session.llm->response(historyCopy, &os, "<eop>", 1);
    };
    return mnn_detail::runStreamCore(&impl_->session, /*resetFirst=*/true, /*doFirstCall=*/true,
                                     firstCall, total > 0 ? total - 1 : 0, cbs, err);
}

bool MnnEngine::generateStructured(const std::string& messagesJson, const std::string& toolsJson,
                                   const GenParams& params, const Callbacks& cbs,
                                   std::string* err) {
    if (!mnn_detail::requireReady(&impl_->session, err)) return false;

    std::lock_guard<std::mutex> gen(impl_->session.genLock);

    // 每轮开始先清掉上一轮的残留，保证上层读到的一定是本轮的原因。
    impl_->session.lastError.clear();
    impl_->session.cancel.store(false);

    std::string templateError;
    std::string prompt;
    try {
        prompt = mnn_detail::applyStructuredChatTemplateCore(impl_->session.llm, messagesJson,
                                                            toolsJson, &templateError);
    } catch (const std::exception& e) {
        impl_->session.lastError = std::string("E_MNN_STREAM_THROW|") + e.what();
        LOGE("Exception preparing structured stream generation: %s", e.what());
        if (err) *err = impl_->session.lastError;
        return false;
    } catch (...) {
        impl_->session.lastError = "E_MNN_STREAM_THROW|unknown native exception";
        LOGE("Unknown exception preparing structured stream generation");
        if (err) *err = impl_->session.lastError;
        return false;
    }

    // 🛡️ B-1 防御：prompt 为空**禁止**进入推理。空 prompt 喂进去只会得到 0 token，
    // 上层就只能报「未产生任何输出」这种毫无信息量的话。这里直接带原因返回。
    if (prompt.empty()) {
        if (templateError.empty()) {
            templateError = "E_MNN_TEMPLATE_EMPTY|rendered prompt is empty";
        }
        impl_->session.lastError = templateError;
        LOGE("Refusing to run inference with empty prompt: %s", templateError.c_str());
        if (err) *err = templateError;
        return false;
    }

    std::vector<int> inputTokens;
    try {
        inputTokens = impl_->session.llm->tokenizer_encode(prompt);
    } catch (const std::exception& e) {
        impl_->session.lastError = std::string("E_MNN_EMPTY_TOKENS|") + e.what();
        LOGE("Exception encoding structured prompt: %s", e.what());
        if (err) *err = impl_->session.lastError;
        return false;
    }
    if (inputTokens.empty()) {
        std::ostringstream oss;
        oss << "E_MNN_EMPTY_TOKENS|prompt has " << prompt.size()
            << " chars but tokenizer produced 0 tokens (tokenizer files may be missing or mismatched)";
        impl_->session.lastError = oss.str();
        LOGE("%s", oss.str().c_str());
        if (err) *err = impl_->session.lastError;
        return false;
    }

    LOGI("Structured prompt ready: %zu chars -> %zu tokens", prompt.size(), inputTokens.size());
    const int total = mnn_detail::clampNewTokens(params.maxTokens);
    auto firstCall = [this, &inputTokens](std::ostream& os) {
        impl_->session.llm->response(inputTokens, &os, "<eop>", 1);
    };
    return mnn_detail::runStreamCore(&impl_->session, /*resetFirst=*/true, /*doFirstCall=*/true,
                                     firstCall, total > 0 ? total - 1 : 0, cbs, err);
}

// ═════════════════════════════════════════════════════════════════════════
// 非流式一次性生成（nativeGenerate 的旧路径，行为逐字保留）
// ═════════════════════════════════════════════════════════════════════════
bool MnnEngine::generateToString(const std::string& prompt, int32_t maxTokens, std::string* out,
                                 std::string* err) {
    if (out == nullptr) {
        if (err) *err = "输出参数为空。";
        return false;
    }
    out->clear();
    if (!mnn_detail::requireReady(&impl_->session, err)) return false;

    std::lock_guard<std::mutex> gen(impl_->session.genLock);
    impl_->session.cancel.store(false);

    try {
        const std::vector<int> inputTokens = impl_->session.llm->tokenizer_encode(prompt);
        LOGD("Input tokens: %zu", inputTokens.size());

        // end_with 传 nullptr、不加循环：完全交给 MNN 内部跑完。
        // 与流式路径的差别见 mnn_engine.h 的说明 —— 这不是风格选择，是两条 API。
        std::stringstream outputStream;
        impl_->session.llm->response(inputTokens, &outputStream, nullptr,
                                     static_cast<int>(maxTokens));
        *out = outputStream.str();
        LOGD("Generated response: %zu chars", out->size());
        return true;
    } catch (const std::exception& e) {
        LOGE("Exception in generateToString: %s", e.what());
        impl_->session.lastError = std::string("E_MNN_STREAM_THROW|") + e.what();
        if (err) *err = impl_->session.lastError;
        return false;
    } catch (...) {
        LOGE("Unknown exception in generateToString");
        impl_->session.lastError = "E_MNN_STREAM_THROW|unknown native exception";
        if (err) *err = impl_->session.lastError;
        return false;
    }
}

// ═════════════════════════════════════════════════════════════════════════
// 分阶段调用（契约完整性用；主路径是 generate*）
// ═════════════════════════════════════════════════════════════════════════
// 如实说明 MNN 的限制，不假装能分离：
//   1) MNN 把「预填充 prompt」与「采样出第 1 个 token」耦合在同一次 response() 里，
//      没有只做 prefill、不出 token 的 API。所以 prefill() 会向 cbs 吐 1 个 token。
//   2) decode() **不** reset，必须在 prefill() 之后调用，否则会把刚建好的 KV 抹掉。
// 想"一次跑完"请直接用 generate() —— 它才是主路径，也是 Kotlin 侧实际走的路。

bool MnnEngine::prefill(const std::vector<int32_t>& promptTokens, const Callbacks& cbs,
                        std::string* err) {
    if (!mnn_detail::requireReady(&impl_->session, err)) return false;

    std::lock_guard<std::mutex> gen(impl_->session.genLock);
    impl_->session.cancel.store(false);

    std::vector<int> tokens;
    tokens.reserve(promptTokens.size());
    for (const int32_t t : promptTokens) tokens.push_back(static_cast<int>(t));

    auto firstCall = [this, &tokens](std::ostream& os) {
        impl_->session.llm->response(tokens, &os, "<eop>", 1);
    };
    return mnn_detail::runStreamCore(&impl_->session, /*resetFirst=*/true, /*doFirstCall=*/true,
                                     firstCall, /*loopTokens=*/0, cbs, err);
}

bool MnnEngine::decode(const GenParams& params, const Callbacks& cbs, std::string* err) {
    if (!mnn_detail::requireReady(&impl_->session, err)) return false;

    std::lock_guard<std::mutex> gen(impl_->session.genLock);

    const int total = mnn_detail::clampNewTokens(params.maxTokens);
    auto noFirstCall = [](std::ostream&) {};
    // resetFirst=false：prefill 建的 KV 必须保留，否则上下文直接丢了。
    return mnn_detail::runStreamCore(&impl_->session, /*resetFirst=*/false, /*doFirstCall=*/false,
                                     noFirstCall, total, cbs, err);
}

}  // namespace llm
}  // namespace quro
