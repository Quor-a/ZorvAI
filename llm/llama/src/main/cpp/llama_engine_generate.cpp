// =============================================================================
// L3/L4 · LlamaEngine 实现（二）：一体化 generate
// =============================================================================
// 为什么 generate 不按 Engine 的默认三步（tokenize → prefill → decode）实现：
//   真机跑出来的这段逻辑里，tokenize、KV 前缀复用、头部截断、prefill 分块、
//   decode 循环**共享同一批局部状态**（reuse / n_past / KV memory 句柄 /
//   可复用的 llama_batch）。把默认三步套上去，等于要重新发明一遍共享方式 ——
//   那是行为漂移的温床。所以这里覆写：保留一体化的编排，只是把
//   「JNI 回调」换成 L3 的 Callbacks，「jstring 入参」换成 std::string。
//
// 本文件承载的真机经验（每条都对应一次线上事故，勿删注释）：
//   · BOS 双补 / 丢 BOS —— 本地模型答非所问、首 token 即 EOG 的根因
//   · llama_batch.seq_id 指针覆写 —— llama_batch_free free 栈地址 → SIGABRT
//   · llama_decode 返回码 1 被当成功 —— KV 未写入 → 输出空字符串
//   · KV 前缀复用的退化保护 —— 重复 cell 导致 KV 被永久投毒
//   · 首 token 即 EOG —— 不记录就是"能跑但一个字都不吐"
//   · UTF-8 跨 token 边界 —— CJK 花字
// =============================================================================

#include "llama_engine_impl.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace quro {
namespace llm {

namespace {

/// 把一个 ThinkSplit 的两段按归属回调出去。
/// @return true = 接收方要求停止
bool emitThinkSplit(const ThinkSplit& s, const Callbacks& cbs) {
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

/// 把一段**已确认 UTF-8 完整**的增量过思考段分流器再回调。
///
/// 注意：一个 token 可能同时产出思考增量与正文增量（例如 `<think>abc</think>def`
/// 恰好切在一个 token 里），因此这里**最多会回调两次**，不是一次。
/// 调用方不能假设「一个 token 一次回调」。
bool emitThroughThinkSplitter(llama_detail::Session* session, const char* data, size_t len,
                              const Callbacks& cbs) {
    if (session == nullptr || data == nullptr || len == 0) {
        return false;
    }
    return emitThinkSplit(session->thinkSplitter.feed(data, len), cbs);
}

/// 生成结束收尾：吐出分流器里暂缓的尾字节。
/// 未闭合的思考段按思考归属吐出、不完整的标签前缀按当前段吐出 —— **绝不丢字节**。
void flushThinkSplitter(llama_detail::Session* session, const Callbacks& cbs) {
    if (session == nullptr) {
        return;
    }
    emitThinkSplit(session->thinkSplitter.finish(), cbs);
}

}  // namespace

bool LlamaEngine::generate(const std::string& prompt, const GenParams& params,
                           const Callbacks& cbs, std::string* err) {
    if (!loaded()) {
        if (err) *err = "引擎尚未加载模型。";
        return false;
    }

    llama_detail::Session* session = &impl_->session;

    // ── 每轮生成开始前复位思考分流器 ──────────────────────────────────────
    // **不能省**：上一轮若在思考段中途结束（模型没吐 </think>，或被 maxTokens 截断），
    // 分流器的 segment_ 会残留为 Thinking。本轮正文于是被整段判成思考内容 ——
    // 上层拿到空的正文通道，用户看到的是"AI 不回复了"这种极难联想到根因的现象。
    //
    // 放在这里而不是别处：`generate()` 是 llama 侧**唯一**的生成入口
    // （JNI 只有 nativeGenerateStream 一条路到这儿），所以一处即覆盖全部。
    // Session 跨轮复用、只构造一次，所以也不能靠构造函数来复位。
    session->thinkSplitter.reset();

    if (session->model == nullptr || session->ctx == nullptr || session->sampler == nullptr) {
        SET_ERR(session, "会话内部对象缺失（model/ctx/sampler 为空），模型可能已被卸载");
        if (err) *err = session->lastError;
        return false;
    }

    // 同一会话的 generate 串行化。旧实现靠 Kotlin 侧加锁 + abortCallback，
    // 引擎自己持这条不变量更稳：两路并发 generate 会同时改 KV，必然互相破坏。
    // 注意 cancel() 不拿这把锁（见 llama_engine.cpp 的说明），所以取消仍然即时。
    std::lock_guard<std::mutex> gen(session->genLock);

    // 把 lastError 同步给调用方，避免每个分支都写两遍。
    auto failNow = [&]() -> bool {
        if (err != nullptr) *err = session->lastError;
        return false;
    };

    // 每次生成开始先清空上轮错误，避免把旧原因误报给这一轮。
    session->lastError.clear();
    session->cancel.store(false);

    // Plan A: KV 不再每轮无条件清空。改为 tokenize + 头部截断之后（拿到最终 promptTokens）
    // 再做条件前缀复用（见下方 "Plan A: KV 前缀缓存" 段）：若本轮 prompt 是上一轮 prompt 的
    // 严格前缀扩展则续用 KV，否则按情况部分砍尾 / 全清。这里只做采样器复位——llama_sampler_reset
    // 是生成态、与 KV 无关，保持每轮无条件执行（team-lead 明确要求不要顺手条件化）。
    if (session->sampler) {
        llama_sampler_reset(session->sampler);
    }

    const std::string& promptStr = prompt;
    const llama_vocab * vocab = llama_model_get_vocab(session->model);

    // ── 进度回调改为 L3 契约 ──
    // 旧实现直接调 Java 的 onProgress("prefill", cur, total)；现在经 Callbacks 上行，
    // 由 L2 层（llama_jni.cpp）负责转成 Java 调用。引擎层不再认识 JNIEnv。
    const bool hasProgress = (cbs.onProgress != nullptr);
    const bool hasToken = (cbs.onToken != nullptr);

    // Tokenize prompt
    int32_t capacity = static_cast<int32_t>(promptStr.size()) + 8;
    std::vector<llama_token> promptTokens;
    promptTokens.resize(std::max(16, capacity));
    // ⚠️ BOS/EOS 处理（本地模型"答非所问 / 首 token 即 EOG"的根因之一）
    //
    // llama_tokenize 的 add_special 只有两种极端：
    //   true  → 无条件按词表元数据补 BOS。模板本身已含 <|begin_of_text|> 的模型（Llama-3 系）
    //           会得到**双 BOS**，模型会当成两段对话的拼接，轻则答非所问、重则立刻吐 EOG。
    //   false → 一律不补。模板不含 BOS 但词表要求 BOS 的模型（Llama-2 / Mistral / Gemma 系）
    //           会**丢 BOS**，首 token 分布严重跑偏。
    // 两个值都会在某类模型上出错，所以这里先用 false 分词，再按词表元数据
    // （llama_vocab_get_add_bos）**检查首 token 是否已经是 BOS**，缺了才补一个——
    // 既不双 BOS 也不丢 BOS，对 Qwen / Llama-2 / Llama-3 / Gemma 一致正确。
    // parse_special 恒为 true：模板里的 <|im_start|> 等必须解析成真正的特殊 token 而非字面量。
    int32_t nPrompt = llama_tokenize(
        vocab,
        promptStr.c_str(),
        static_cast<int32_t>(promptStr.size()),
        promptTokens.data(),
        static_cast<int32_t>(promptTokens.size()),
        false,
        true
    );
    if (nPrompt < 0) {
        promptTokens.resize(static_cast<size_t>(-nPrompt));
        nPrompt = llama_tokenize(
            vocab,
            promptStr.c_str(),
            static_cast<int32_t>(promptStr.size()),
            promptTokens.data(),
            static_cast<int32_t>(promptTokens.size()),
            false,
            true
        );
    }
    if (nPrompt <= 0) {
        SET_ERR(session, "提示词分词失败（tokenize 返回 %d，prompt 长度 %zu 字节）", (int) nPrompt, promptStr.size());
        return failNow();
    }
    promptTokens.resize(static_cast<size_t>(nPrompt));

    // —— BOS 补齐（见上方注释）：词表要求 BOS 且模板没渲染出 BOS 时，手动在最前面补一个。
    const bool vocabWantsBos = llama_vocab_get_add_bos(vocab);
    const llama_token bosTok = llama_vocab_bos(vocab);
    bool bosInjected = false;
    if (vocabWantsBos && bosTok != LLAMA_TOKEN_NULL) {
        if (promptTokens.empty() || promptTokens.front() != bosTok) {
            promptTokens.insert(promptTokens.begin(), bosTok);
            bosInjected = true;
        }
    }
    LOGI("tokenize | nPrompt=%d | vocabWantsBos=%d | bos=%d | injected=%d | firstTok=%d | eos=%d",
         (int) promptTokens.size(), (int) vocabWantsBos, (int) bosTok, (int) bosInjected,
         promptTokens.empty() ? -1 : (int) promptTokens.front(), (int) llama_vocab_eos(vocab));

    // Avoid prompts that end with EOG/EOS tokens (some vocabs add EOS automatically when add_special=true)
    while (!promptTokens.empty() && llama_vocab_is_eog(vocab, promptTokens.back())) {
        promptTokens.pop_back();
    }
    if (promptTokens.empty()) {
        SET_ERR(session, "提示词分词后只剩结束符（EOG/EOS），聊天模板可能与该 GGUF 不匹配");
        return failNow();
    }

    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(session->ctx));
    int maxNew = params.maxTokens <= 0 ? 256 : params.maxTokens;
    if (n_ctx > 0) {
        const int32_t reserveForGeneration = std::max<int32_t>(32, std::min<int32_t>(maxNew, n_ctx / 4));
        const int32_t maxPromptTokens = std::max<int32_t>(1, n_ctx - reserveForGeneration);
        if (static_cast<int32_t>(promptTokens.size()) > maxPromptTokens) {
            const size_t drop = promptTokens.size() - static_cast<size_t>(maxPromptTokens);
            const auto dropCount = static_cast<std::vector<llama_token>::difference_type>(drop);
            promptTokens.erase(promptTokens.begin(), promptTokens.begin() + dropCount);
            LOGI("Prompt truncated to fit context: kept=%d dropped=%zu n_ctx=%d", maxPromptTokens, drop, n_ctx);
        }
    }

    if (promptTokens.empty()) {
        SET_ERR(session, "提示词按上下文窗口截断后为空（n_ctx=%d 太小）", (int) n_ctx);
        return failNow();
    }

    // ===================== Plan A: KV 前缀缓存（条件失效） =====================
    // 前缀失效的完整条件列表（任一满足 → 本轮回退为全量重算）：
    //   1) session->kvDirty == true（主动失效：nativeResetKv / resetContext()，或本/上轮
    //      生成失败、取消、abort 时已置位）；
    //   2) 尚无缓存：kvPrefix 为空（首个请求）；
    //   3) 本轮 prompt 不是缓存前缀的扩展：reuse == 0（prompt 与 kvPrefix 在首个 token 就分叉，
    //      或上下文头部被原生截断丢掉了前缀）；
    //   4) 用户取消：session->cancel 在 prefill 或生成阶段被置位；
    //   5) 任意 llama_decode 返回非 0（KV 槽不足 ret==1 / 中止 ret==2 / 致命 <-1），prefill 或生成；
    //   6) 工具轮：applyStructuredChatTemplate 渲染的 prompt（app 层在工具轮调用 resetContext()，
    //      本机接口已暴露，接线由 team-lead 后续决定）。
    // 注：原生头部截断（1441 行附近）会丢弃 prompt 头部，若正好丢掉前缀 → reuse 塌为 0，自然全清。
    llama_memory_t mem = session->ctx ? llama_get_memory(session->ctx) : nullptr;
    const int32_t promptLen = static_cast<int32_t>(promptTokens.size());
    int32_t reuse = 0;
    if (mem != nullptr && !session->kvDirty && !session->kvPrefix.empty()
        && static_cast<int32_t>(session->kvPrefix.size()) >= session->kvPast) {
        // 求 promptTokens 与 kvPrefix 的最长公共前缀长度。
        const int32_t cachedLen = session->kvPast;
        const int32_t limit = std::min<int32_t>(promptLen, cachedLen);
        int32_t r = 0;
        while (r < limit && promptTokens[r] == session->kvPrefix[r]) {
            r++;
        }
        reuse = r;
    }
    if (session->kvDirty) {
        reuse = 0;  // 显式失效：强制不复用
    }

    // 退化保护（⚠️ 必须在裁剪 KV **之前**做）：若本轮 prompt 与缓存完全一致
    // （reuse == promptLen），就没有任何新增 token 可 decode → 本轮拿不到 logits，
    // llama_sampler_sample 会取到上一轮的陈旧 logits（或越界）→ 崩溃/输出垃圾。
    // 因此强制至少留 1 个 token 给本轮 decode。
    // 为什么必须先 clamp 再裁剪：若先裁剪后 clamp，reuse == kvPast == promptLen 时走的是
    // "前缀完全匹配、KV 不动"分支，pos=promptLen-1 的 cell 仍留在 cache 里；紧接着又把
    // 同一个 token 在同一 pos 重新 decode 一次 —— llama.cpp 的 unified KV 是"找空槽插入"，
    // 不会按 pos 去重，于是 seq0 出现**两个 pos=promptLen-1 的 cell**：
    //   ① 注意力重复看到该 token，logits 被污染；
    //   ② 生成结束后 llama_memory_seq_rm(mem, 0, promptLen, -1) 只删 pos>=promptLen，
    //      这个重复 cell 删不掉，却被当作干净前缀写进 kvPrefix → 缓存被永久投毒，
    //      后续每轮复用都在错误 KV 上续写。
    // 先 clamp 成 promptLen-1，就会落进下面的 `reuse < kvPast` 分支，把 pos>=promptLen-1
    // 的 cell 先删掉再重新 decode，KV 保持唯一且正确。
    if (reuse >= promptLen) {
        reuse = promptLen - 1;
        if (reuse < 0) reuse = 0;
    }

    if (mem != nullptr) {
        if (reuse == 0) {
            // 完全不复用：全量重算（等价于原无条件 llama_memory_clear 行为）。
            llama_memory_clear(mem, true);
        } else if (reuse < session->kvPast) {
            // 部分复用：砍掉分叉尾部 [reuse, kvPast)，保留 [0, reuse) 作为续写前缀。
            // llama_memory_seq_rm 语义（include/llama.h:733）：删除 seq 中位置在 [p0, p1) 的 token；
            // p1 < 0 表示 [p0, inf)。返回 bool，false = 无法删除部分序列。
            // 返回 false 时必须降级为全清，且 **reuse 要一并归 0** ——
            // 否则 KV 已被清空、prefill 却仍从 offset=reuse 开始，[0, reuse) 这段 token
            // 永远不会被 decode，模型在空 KV 上从中间位置续写 → 纯乱码。
            if (!llama_memory_seq_rm(mem, 0, reuse, -1)) {
                LOGE("kv prefix partial-rm failed (reuse=%d kvPast=%d); fallback to full clear",
                     (int) reuse, (int) session->kvPast);
                llama_memory_clear(mem, true);
                reuse = 0;
            }
        }
        // reuse == kvPast：前缀完全匹配，KV 不动，直接进入续写（最快路径）。
    } else {
        // 没有 KV memory（极端配置）→ 不可能复用。
        reuse = 0;
    }

    const int32_t startOffset = reuse;

    const auto prefillStart = std::chrono::steady_clock::now();

    // Prefill in chunks of n_batch. Use an EXPLICIT, writable batch (llama_batch_init)
    // and set pos/seq_id/logits per token — do NOT rely on llama_batch_validate's
    // null-default behavior, which falls back to pos=0 when KV memory is null and
    // mis-places chunked tokens (KV overwrite at position 0 -> garbage context ->
    // first sampled token is EOG -> empty output). This matches PocketPal's
    // chunk-prefill范式 and is robust regardless of KV memory state.
    const uint32_t n_batch = llama_n_batch(session->ctx);
    // 手机 CPU prefill 大 chunk 单段可能 >30s，被系统/库层超时掐死；改小到 256 让每段
    // 更快完成、进度条更频繁更新，同时仍保持合理效率（256 是 llama.cpp 常见 ubatch 量级）。
    const int32_t effectiveChunk = static_cast<int32_t>(std::min<uint32_t>(n_batch, 256u));
    const int32_t totalPrompt = static_cast<int32_t>(promptTokens.size());
    int32_t n_past = reuse;

    LOGI(
        "Prefill decode start: prompt_tokens=%zu n_ctx=%d n_batch=%u effective_chunk=%d max_new=%d",
        promptTokens.size(),
        n_ctx,
        n_batch,
        effectiveChunk,
        maxNew
    );
    const llama_seq_id seq0 = 0;
    llama_batch pbatch = llama_batch_init(n_batch, 0, 1);
    bool prefillFailed = false;
    int32_t offset = startOffset;
    while (offset < totalPrompt) {
        const int32_t chunk = std::min<int32_t>(effectiveChunk, totalPrompt - offset);
        for (int32_t i = 0; i < chunk; i++) {
            pbatch.token[i] = promptTokens[offset + i];
            pbatch.pos[i] = offset + i;
            pbatch.n_seq_id[i] = 1;
            // ⚠️ 致命坑（本次崩溃真根因）：绝不能写成 `pbatch.seq_id[i] = &seq0`。
            // llama_batch_init 已为每个 token 槽 malloc 了 seq_id[i]（大小 n_seq_max），
            // llama_batch_free 会遍历到 nullptr 哨兵并**逐个 free(batch.seq_id[i])**。
            // 覆写指针 = ①泄漏原 malloc 块 ②让 free() 去释放一个**栈地址**
            // → bionic malloc 判定非法指针直接 abort（SIGABRT signal=6 @ free/llama_batch_free），
            // 侥幸不 abort 时也已污染堆元数据 → 后续随机 SIGSEGV（如 ggml_vec_dot_q5_K_q8_K）
            // 与 ggml_abort @ llama_context::decode。单线程发一条消息即必现，与并发无关。
            // 正确做法同官方 common_batch_add：往已分配的槽里**写值**。
            pbatch.seq_id[i][0] = seq0;
            // only the very last token across the whole prompt outputs logits
            pbatch.logits[i] = (offset + i == totalPrompt - 1) ? 1 : 0;
        }
        pbatch.n_tokens = chunk;
        LOGI("Prefill chunk: offset=%d chunk=%d last=%d", (int) offset, (int) chunk,
             (offset + chunk >= totalPrompt) ? 1 : 0);

        const auto chunkStart = std::chrono::steady_clock::now();

        // 把 prefill 进度实时推给 UI（首 token 到达后会被真实文本覆盖）。
        // Plan C: 透出"本轮真正要 decode 的新增 token 数"，不是总 prompt 长度。
        // 原生每轮把 promptTokens 整体下发，但 Plan A 已复用 KV 前缀，实际只 decode
        // [reuse, totalPrompt) 这段新 token；Kotlin 侧据此阈值（LOCAL_PREFILL_PROGRESS_TOKEN_THRESHOLD）
        // 决定是否上屏进度条——多轮只新增几十 token 时被挡住，消除"每轮弹 正在处理提示词 X%"。
        if (hasProgress) {
            ProgressChunk pc;
            pc.stage = "prefill";
            pc.current = std::max<int32_t>(0, offset - reuse);
            pc.total = static_cast<int32_t>(promptTokens.size()) - reuse;
            cbs.onProgress(cbs.user, pc);
        }

        int32_t ret = llama_decode(session->ctx, pbatch);
        const auto chunkMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                                 std::chrono::steady_clock::now() - chunkStart)
                                 .count();
        LOGI("Prefill chunk done: offset=%d chunk=%d ret=%d time=%lldms", (int) offset, (int) chunk,
             (int) ret, (long long) chunkMs);
        // ⚠️ 返回码语义（include/llama.h）：0=成功；1=**找不到 KV slot（失败！）**；
        // 2=aborted；-1=非法 batch；<-1=致命。
        // 旧代码写的是 `ret != 0 && ret != 1`，等于把 1 当成功继续跑 ——
        // 该 chunk 的 KV 根本没写进去，上下文残缺 → 采样出的首 token 直接是 EOG
        // → 生成循环立刻 break → 输出空字符串（"不闪退但一个字都不回"的独立根因之一）。
        if (ret != 0) {
            if (ret == 2) {
                SET_ERR(session, "提示词处理被中断（模型正在卸载或已取消）");
            } else if (ret == 1) {
                SET_ERR(session,
                        "提示词处理失败：KV 缓存放不下（提示词 %d token / n_ctx=%d / n_batch=%u）。"
                        "请减少上下文或换更小的模型",
                        (int) totalPrompt, (int) n_ctx, (unsigned) n_batch);
            } else {
                SET_ERR(session, "提示词解码失败 llama_decode ret=%d（offset=%d/%d）",
                        (int) ret, (int) offset, (int) totalPrompt);
            }
            prefillFailed = true;
            break;
        }
        // 让 unload/cancel 能快速打断 prefill：在每段 chunk 解码后检查 cancel 标志。
        if (session->cancel.load()) {
            SET_ERR(session, "提示词处理被取消（cancel）");
            prefillFailed = true;
            break;
        }
        // L5 · 温控：chunk 边界是安全的调档点（这一批已经算完，线程池空闲）。
        llama_detail::applyThermalAdvice(session);
        offset += chunk;
    }
    llama_batch_free(pbatch);
    const auto prefillTotalMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                                    std::chrono::steady_clock::now() - prefillStart)
                                    .count();
    LOGI("Prefill phase total: %lldms (tokens=%d)", (long long) prefillTotalMs, totalPrompt);
    session->prefillMs = static_cast<double>(prefillTotalMs);
    session->prefillTokens = totalPrompt;
    if (prefillFailed) {
        session->kvDirty = true;  // prefill 未正常完成，KV 状态不可信 → 下轮全清
        return failNow();
    }

    // n_past for subsequent single-token decoding
    n_past = static_cast<int32_t>(promptTokens.size());

    llama_detail::prefillToolCallGenerationPrompt(session);

    // Generation loop
    std::vector<llama_token> generatedTokens;

    // Reusable single-token batch with EXPLICIT pos/seq_id (same rationale as prefill):
    // never rely on llama_batch_validate null-defaults.
    llama_batch gbatch = llama_batch_init(1, 0, 1);
    gbatch.n_seq_id[0] = 1;
    // 同 prefill：写值，不覆写指针（否则 llama_batch_free 会 free 栈地址 → SIGABRT）。
    gbatch.seq_id[0][0] = seq0;
    generatedTokens.reserve(static_cast<size_t>(maxNew));
    // Plan A: 本轮生成是否被取消/abort/解码失败。若是，KV 含不完整生成尾，不可缓存为前缀。
    bool generationDirty = false;
    std::string prevDecoded;
    std::string pendingUtf8;  // incomplete trailing UTF-8 bytes buffered across tokens (CJK mojibake fix)
    std::vector<char> detokBuf;

    for (int i = 0; i < maxNew; i++) {
        if (session->cancel.load()) {
            LOGI("generation cancelled");
            generationDirty = true;
            break;
        }

        // 接收方（L2 层把 token 交给 Java 后，Java 返回 false = 不要了）请求停止。
        // 走独立的 break 路径且**不置 generationDirty** —— 与本分支上面的 cancel 区别对待：
        // prompt 部分的 KV 仍然是干净可缓存的（与旧实现里 `if (!keepGoing) break;` 一致）。
        if (cbs.stopped()) {
            LOGI("generation stopped by sink");
            break;
        }

        // L5 · 温控：token 边界调档。decode 阶段是长时间持续负载（一次可跑几十秒），
        // 也正是 SoC 升温最快的一段 —— 这里是温控收益最大的地方。
        // 节流由 applyThermalAdvice 内部的 thermalPollMs 控制，每个 token 调它只是
        // 读一次 steady_clock，开销可忽略。
        llama_detail::applyThermalAdvice(session);

        const llama_token newToken = llama_sampler_sample(session->sampler, session->ctx, -1);
        llama_sampler_accept(session->sampler, newToken);

        if (i == 0) {
            LOGI("first sampled token=%d eog=%d", (int) newToken, (int) llama_vocab_is_eog(vocab, newToken));
        }

        if (llama_vocab_is_eog(vocab, newToken)) {
            // ⚠️ 关键可观测性缺口（此前完全没记录）：
            // 若**第一个**采样 token 就是 EOG，循环立刻 break，generatedTokens 为空，
            // 但函数仍返回 true → Kotlin 侧 ok=true 且 sb 为空 → QuroLlmResult.Text("")
            // → 聊天气泡纯空白、无任何报错，用户只能看到"不回复"，日志里也毫无痕迹。
            // 这是"能跑但一个字都不吐"最典型的形态，必须显式记录成可见错误。
            if (i == 0) {
                SET_ERR(session,
                        "模型在第一个 token 就输出了结束符（EOG token=%d）。"
                        "通常意味着聊天模板与该 GGUF 不匹配，或提示词格式有误",
                        (int) newToken);
            }
            break;
        }

        // Detokenize the generated token sequence to produce valid UTF-8 text.
        // Token pieces may split multi-byte sequences; emitting per-token pieces often results in mojibake.
        generatedTokens.push_back(newToken);

        int32_t detokCap = std::max<int32_t>(64, static_cast<int32_t>(generatedTokens.size() * 8 + 32));
        detokBuf.resize(static_cast<size_t>(detokCap));

        int32_t nDetok = llama_detokenize(
            vocab,
            generatedTokens.data(),
            static_cast<int32_t>(generatedTokens.size()),
            detokBuf.data(),
            static_cast<int32_t>(detokBuf.size()),
            true,
            false
        );
        if (nDetok < 0) {
            detokBuf.resize(static_cast<size_t>(-nDetok));
            nDetok = llama_detokenize(
                vocab,
                generatedTokens.data(),
                static_cast<int32_t>(generatedTokens.size()),
                detokBuf.data(),
                static_cast<int32_t>(detokBuf.size()),
                true,
                false
            );
        }

        std::string decodedNow;
        if (nDetok > 0) {
            decodedNow.assign(detokBuf.data(), detokBuf.data() + nDetok);
        }

        std::string delta;
        if (!prevDecoded.empty() && decodedNow.rfind(prevDecoded, 0) == 0) {
            delta = decodedNow.substr(prevDecoded.size());
        } else {
            delta = decodedNow;
        }
        prevDecoded = decodedNow;

        if (!delta.empty() && hasToken) {
            // UTF-8 boundary buffering: append delta to pendingUtf8, emit only complete characters.
            // Multi-byte CJK characters can span two tokens; the delta from llama_detokenize may end
            // with an incomplete UTF-8 sequence. 直接交给上层会被替换成 0xFFFD (�)。
            // By buffering incomplete trailing bytes, we hold them until the next token
            // completes the character — mirroring MNN's extractCompleteUtf8.
            pendingUtf8 += delta;
            std::string completeChars;
            size_t ci = 0;
            while (ci < pendingUtf8.size()) {
                int charLen = llama_detail::utf8CharLength(static_cast<unsigned char>(pendingUtf8[ci]));
                if (charLen == 0 || ci + static_cast<size_t>(charLen) > pendingUtf8.size()) {
                    break;  // invalid byte or incomplete trailing bytes — wait for next token
                }
                completeChars.append(pendingUtf8, ci, static_cast<size_t>(charLen));
                ci += static_cast<size_t>(charLen);
            }
            if (ci > 0) {
                pendingUtf8.erase(0, ci);
            }
            if (!completeChars.empty()) {
                // 过思考段分流器：思考增量与正文增量分别回调（最多两次）。
                // 接收方要求停止时，本轮回合结束。这里 break 后仍会走下方收尾
                // （与旧实现 `if (!keepGoing) break;` 的位置一致）。
                if (emitThroughThinkSplitter(session, completeChars.data(),
                                             completeChars.size(), cbs)) {
                    break;
                }
            }
        }

        if (n_ctx > 0 && n_past >= n_ctx) {
            LOGI("context window reached: n_past=%d n_ctx=%d", n_past, n_ctx);
            break;
        }

        llama_token next = newToken;
        gbatch.token[0] = next;
        gbatch.pos[0] = n_past;
        gbatch.logits[0] = 1;
        gbatch.n_tokens = 1;
        LOGI("generation decode #%d n_past=%d", i, (int) n_past);
        int32_t ret = llama_decode(session->ctx, gbatch);
        if (ret != 0) {
            if (ret == 2) {
                LOGI("decode aborted");
                generationDirty = true;
                break;
            }
            if (ret == 1) {
                // KV 满：已生成的内容仍然有效，正常收尾 break 而不是整段判失败。
                LOGI("no KV slot during generation (context full) n_past=%d n_ctx=%d", n_past, n_ctx);
                generationDirty = true;
                break;
            }
            SET_ERR(session, "生成阶段解码失败 llama_decode ret=%d（已生成 %d token）", (int) ret, i);
            session->kvDirty = true;
            llama_batch_free(gbatch);
            return failNow();
        }

        n_past += 1;
    }

    // Flush any remaining buffered UTF-8 bytes (e.g. generation ended mid-character).
    // These bytes may be incomplete — 上层会替换成 0xFFFD，
    // which is the correct behavior for truncated output (better than silently dropping).
    if (!pendingUtf8.empty() && hasToken) {
        emitThroughThinkSplitter(session, pendingUtf8.data(), pendingUtf8.size(), cbs);
        pendingUtf8.clear();
    }

    // 分流器里可能还压着暂缓的尾字节（未闭合思考段、或不完整的标签前缀）。
    // 必须在这里吐出，否则这批字节会被静默丢掉 —— 表现为回复末尾少几个字。
    flushThinkSplitter(session, cbs);

    // ===================== Plan A: 生成结束后的保守尾处理 =====================
    // 生成期间 KV = promptTokens + 本轮裸 assistant token。但下一轮 prompt 是把这段回复经聊天模板
    // 重新渲染的（带 <|im_start|>assistant / <|im_end|> 包装 + 后续 user 轮），两者在生成文本结尾处
    // 必然分叉。若直接续用 KV，下一轮"prefill 前缀"会和真实 prompt 错位 → 上下文错乱。
    // 采用保守方案：丢弃生成尾（回归到干净的 promptTokens 前缀），把 kvPrefix 记为 promptTokens；
    // 这样下一轮 prompt 必以 promptTokens 为严格前缀 → 正常复用。代价：每轮多存/算一点，但绝不分叉。
    // 若本轮被取消/abort/解码失败（generationDirty）→ 不缓存，标记 kvDirty 让下轮全清。
    if (mem != nullptr && !generationDirty) {
        llama_memory_seq_rm(mem, 0, static_cast<int32_t>(promptTokens.size()), -1);
        session->kvPrefix = promptTokens;
        session->kvPast = static_cast<int32_t>(promptTokens.size());
        session->kvDirty = false;
    } else {
        session->kvDirty = true;
    }

    session->decodeTokens += static_cast<int64_t>(generatedTokens.size());
    llama_batch_free(gbatch);
    return true;
}

}  // namespace llm
}  // namespace quro
