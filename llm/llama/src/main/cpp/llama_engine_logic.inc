// 本文件由 scripts/refactor_split_llama.py 从 llama_jni_stub.cpp 自动抽取，
// **不要手改** —— 改逻辑请改 llama_engine.cpp / llama_engine_chat.cpp，
// 若要重新抽取，请修改脚本而不是本文件。
//
// 内容：llama.cpp 引擎的会话级辅助逻辑（温控调档、采样链重建、分词、
// 聊天模板初始化、工具调用文法抽取、KV 预填充辅助、native 崩溃落盘）。
// 全部逐字来自旧实现，仅做分层归属调整（见脚本头部说明）。

// ═══ 采样链构造 ═══
llama_sampler * createSamplerChain(
        const llama_vocab * vocab,
        float temperature,
        float topP,
        int32_t topK,
        int32_t penaltyLastN,
        float repeatPenalty,
        float frequencyPenalty,
        float presencePenalty,
        uint32_t seed,
        const ToolCallGrammarConfigNative * grammarConfig
) {
    if (topP < 0.0f) topP = 0.0f;
    if (topP > 1.0f) topP = 1.0f;
    if (topK < 0) topK = 0;
    if (penaltyLastN < -1) penaltyLastN = -1;
    if (repeatPenalty < 0.0f) repeatPenalty = 0.0f;

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * chain = llama_sampler_chain_init(sparams);
    if (!chain) return nullptr;

    llama_sampler_chain_add(chain, llama_sampler_init_penalties(
            penaltyLastN,
            repeatPenalty,
            frequencyPenalty,
            presencePenalty
    ));

    llama_sampler_chain_add(chain, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));

    if (grammarConfig != nullptr && !grammarConfig->grammar.empty()) {
        if (vocab == nullptr) {
            llama_sampler_free(chain);
            return nullptr;
        }

        llama_sampler * grammarSampler = nullptr;
        if (grammarConfig->lazy) {
            std::vector<const char *> triggerPatternsC;
            triggerPatternsC.reserve(grammarConfig->triggerPatterns.size());
            for (const auto & pattern : grammarConfig->triggerPatterns) {
                if (!pattern.empty()) {
                    triggerPatternsC.push_back(pattern.c_str());
                }
            }

            grammarSampler = llama_sampler_init_grammar_lazy_patterns(
                vocab,
                grammarConfig->grammar.c_str(),
                "root",
                triggerPatternsC.data(),
                triggerPatternsC.size(),
                grammarConfig->triggerTokens.data(),
                grammarConfig->triggerTokens.size()
            );
        } else {
            grammarSampler = llama_sampler_init_grammar(
                vocab,
                grammarConfig->grammar.c_str(),
                "root"
            );
        }

        if (!grammarSampler) {
            llama_sampler_free(chain);
            return nullptr;
        }

        llama_sampler_chain_add(chain, grammarSampler);
    }

    llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));

    return chain;
}

// ═══ 会话级辅助逻辑 ═══
// ─────────────────────────── L5 · 温控自适应 ───────────────────────────
// 这是「真正决定流畅度的两个开关」之一，也是比语言选型影响更大的那一个：
//   持续推理 5–10 分钟后 SoC 必然降频（实测骁龙 8 Gen 3 上裸跑 30 分钟吞吐掉约 69%）。
//   每约 2 秒读一次 thermal headroom 并**主动**小幅降线程，可以保住约 77% 峰值 ——
//   因为主动降档是平滑的，被动降频是断崖式的。
//
// 为什么必须"主动"：平台的 thermal throttling 是内核/固件行为，降频时不会通知应用。
//   等我们发现变慢再反应，已经掉进断崖。唯一办法是**提前**看余量（headroom），
//   在还有余量时就小幅让步，把温度压在阈值下方。
//
// 三条实现纪律：
//   1) **默认关闭**（thermalPollMs == 0），见 Session 的字段注释。
//   2) **只在 token / chunk 边界应用**。绝不在一次 llama_decode 中途改线程数 ——
//      ggml 的线程池/图节点假设会被破坏，那是内存损坏级的风险，不是"慢一点"。
//   3) **档位没变就一次系统调用都不做**（见下面的 want == thermalThreads 判断）。
//
// 探测不到任何温度源时 ThermalGovernor 会返回 UNKNOWN，这里直接 return 不干预 ——
// 明确什么都不做，好过瞎猜一个档位把用户的机器降速。
void applyThermalAdvice(Session * session) {
    if (session == nullptr || session->ctx == nullptr) return;
    if (session->thermalPollMs <= 0) return;   // 未启用

    const auto now = std::chrono::steady_clock::now();
    const auto sinceMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                             now - session->thermalLastCheck)
                             .count();
    if (sinceMs < session->thermalPollMs) return;
    session->thermalLastCheck = now;

    const quro::llm::ThermalAdvice advice = quro::llm::ThermalGovernor::instance().sample();
    if (advice.level == quro::llm::ThermalLevel::UNKNOWN) return;   // 无数据源 → 不干预

    const int32_t want = std::max<int32_t>(1, advice.threads);
    if (want == session->thermalThreads) return;   // 档位未变 → 一次调用都不做

    const int32_t prev = session->thermalThreads;
    session->thermalThreads = want;
    llama_set_n_threads(session->ctx,
                        static_cast<uint32_t>(want),
                        static_cast<uint32_t>(want));
    LOGI("温控调档：线程 %d → %d | 档位=%s headroom=%d%% status=%d | %s",
         (int) prev, (int) want,
         quro::llm::thermalLevelName(advice.level),
         advice.headroomPct, advice.platformStatus,
         advice.reason.c_str());
}

// UTF-8 character byte-length from leading byte (0 = invalid/incomplete).
// Mirrors MNN's utf8CharLength (mnnllmnative.cpp:728-734) for UTF-8 boundary buffering:
// when a multi-byte CJK character spans two tokens, the streaming detokenize delta may
// end with an incomplete UTF-8 sequence. Without buffering, bytesUtf8ToJstring replaces
// those bytes with 0xFFFD (�), producing the garbled text users reported.
// ★ 这里**不能**留 `inline`：本文件只被 llama_engine.cpp 一个 TU include，
// 而调用方在 llama_engine_generate.cpp。inline 函数若在本 TU 内没有被取地址，
// 编译器通常**不发射符号**，于是另一个 TU 的引用在链接期炸 undefined symbol。
// （静态库不解析符号，所以编译期完全看不出来 —— 和 ThermalGovernor::instance()
// 那次是同一类坑：都是"单 TU 编过 ≠ 符号存在"。）
int utf8CharLength(unsigned char byte) {
    if ((byte & 0x80) == 0) return 1;
    if ((byte & 0xE0) == 0xC0) return 2;
    if ((byte & 0xF0) == 0xE0) return 3;
    if ((byte & 0xF8) == 0xF0) return 4;
    return 0;
}

static std::once_flag gBackendInitOnce;

// ---- Native crash tombstone writer (async-signal-safe subset) -------------------
static void quroCrashWriteStr(int fd, const char * s) {
    if (s == nullptr) return;
    size_t n = strlen(s);
    if (n > 0) write(fd, s, n);
}

static void quroCrashWriteNum(int fd, long v) {
    char buf[24];
    int i = 0;
    if (v < 0) { buf[i++] = '-'; v = -v; }
    char tmp[20];
    int j = 0;
    if (v == 0) tmp[j++] = '0';
    while (v > 0) { tmp[j++] = (char)('0' + (v % 10)); v /= 10; }
    while (j > 0) buf[i++] = tmp[--j];
    write(fd, buf, i);
}

// Minimal signal handler: write a symbolized backtrace (signal + fault address +
// fault PC + a backtrace resolved to lib!function+offset) to Download/QuroAI_logs/
// so the user can retrieve it without adb, then re-raise so the OS still produces
// a real tombstone. Only best-effort async-signal-safe-ish calls are used:
// backtrace/backtrace_symbols_fd/dladdr are commonly used in crash handlers;
// write/open/close are async-signal-safe. No C++ streams / STL formatting.
static void quroNativeCrashSigHandler(int sig, siginfo_t * info, void * uctx) {
    mkdir("/sdcard/Download/QuroAI_logs", 0755);

    int fd = open("/sdcard/Download/QuroAI_logs/quro_native_crash.log",
                  O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd >= 0) {
        quroCrashWriteStr(fd, "[QuroNativeCrash] signal=");
        quroCrashWriteNum(fd, sig);
        quroCrashWriteStr(fd, " si_addr=");
        quroCrashWriteNum(fd, info ? reinterpret_cast<long>(info->si_addr) : 0L);

        // Faulting PC from the ucontext (the most useful single address).
        ucontext_t * uc = reinterpret_cast<ucontext_t *>(uctx);
        uintptr_t faultPc = 0;
#if defined(__aarch64__)
        faultPc = (uintptr_t) uc->uc_mcontext.pc;
#elif defined(__x86_64__)
        faultPc = (uintptr_t) uc->uc_mcontext.gregs[REG_RIP];
#elif defined(__arm__)
        faultPc = (uintptr_t) uc->uc_mcontext.arm_pc;
#endif
        quroCrashWriteStr(fd, " fault_pc=");
        quroCrashWriteNum(fd, (long) faultPc);
        quroCrashWriteStr(fd, " (use with addr2line / ndk-stack)\n");
        quroCrashWriteStr(fd, "[QuroNativeCrash] backtrace:\n");

        // bionic has no glibc backtrace(); use the Itanium _Unwind_Backtrace API
        // (available via <unwind.h>) and resolve each frame with dladdr so we get
        // lib!function+offset (ASLR-stripped), which maps directly to addr2line.
        struct QuroUnwindState {
            void * frames[32];
            int count;
        };
        QuroUnwindState st;
        st.count = 0;
        auto quroUnwindCb = +[](struct _Unwind_Context * ctx, void * arg) -> _Unwind_Reason_Code {
            auto * s = static_cast<QuroUnwindState *>(arg);
            if (s->count >= 32) return _URC_END_OF_STACK;
            uintptr_t ip = static_cast<uintptr_t>(_Unwind_GetIP(ctx));
            // _Unwind_GetIP returns the return address (next insn); subtract 1 so
            // the address falls inside the calling instruction for symbolization.
            s->frames[s->count++] = reinterpret_cast<void *>(ip ? ip - 1 : ip);
            return _URC_NO_REASON;
        };
        _Unwind_Backtrace(quroUnwindCb, &st);
        // Skip frame 0 (our signal handler) and frame 1 (the libc raise/abort
        // trampoline) so the first printed frame is the real llama.cpp call site.
        for (int i = 2; i < st.count; i++) {
            Dl_info dlinfo;
            memset(&dlinfo, 0, sizeof(dlinfo));
            dladdr(st.frames[i], &dlinfo);
            quroCrashWriteStr(fd, "  #");
            quroCrashWriteNum(fd, i);
            quroCrashWriteStr(fd, " ");
            if (dlinfo.dli_fname) {
                quroCrashWriteStr(fd, dlinfo.dli_fname);
            } else {
                quroCrashWriteStr(fd, "<unknown>");
            }
            quroCrashWriteStr(fd, "!");
            if (dlinfo.dli_sname) {
                quroCrashWriteStr(fd, dlinfo.dli_sname);
            } else {
                quroCrashWriteStr(fd, "??");
            }
            if (dlinfo.dli_fbase) {
                quroCrashWriteStr(fd, "+0x");
                quroCrashWriteNum(fd, (long)((uintptr_t)st.frames[i] - (uintptr_t)dlinfo.dli_fbase));
            } else {
                quroCrashWriteStr(fd, " @0x");
                quroCrashWriteNum(fd, (long)(uintptr_t)st.frames[i]);
            }
            if ((uintptr_t)st.frames[i] == faultPc) {
                quroCrashWriteStr(fd, "   <-- fault");
            }
            quroCrashWriteStr(fd, "\n");
        }
        close(fd);
    }

    // Restore default disposition and re-raise so the system still produces a tombstone.
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = SIG_DFL;
    sigaction(sig, &sa, nullptr);
    raise(sig);
}

static void installNativeCrashHandler() {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = quroNativeCrashSigHandler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGBUS, &sa, nullptr);
    sigaction(SIGABRT, &sa, nullptr);
    sigaction(SIGILL, &sa, nullptr);
    sigaction(SIGFPE, &sa, nullptr);
}

void ensureBackendInit() {
    std::call_once(gBackendInitOnce, []() {
        installNativeCrashHandler();
        llama_backend_init();
        std::srand(static_cast<unsigned int>(std::time(nullptr)));
        LOGI("llama_backend_init done");
    });
}

uint32_t positiveOrDefaultUInt(int32_t value, uint32_t defaultValue) {
    return value > 0 ? static_cast<uint32_t>(value) : defaultValue;
}

int32_t positiveOrDefaultInt(int32_t value, int32_t defaultValue) {
    return value > 0 ? static_cast<int32_t>(value) : defaultValue;
}

bool abortCallback(void * user_data) {
    auto * session = reinterpret_cast<Session *>(user_data);
    return session != nullptr && session->cancel.load();
}

bool rebuildSamplerForSession(Session * session) {
    if (session == nullptr || session->model == nullptr || session->ctx == nullptr) {
        return false;
    }

    const llama_vocab * vocab = llama_model_get_vocab(session->model);

    llama_sampler * next = createSamplerChain(
        vocab,
        session->samplingParams.temperature,
        session->samplingParams.topP,
        session->samplingParams.topK,
        session->samplingParams.penaltyLastN,
        session->samplingParams.repeatPenalty,
        session->samplingParams.frequencyPenalty,
        session->samplingParams.presencePenalty,
        session->samplingParams.seed,
        &session->toolCallGrammar
    );

    if (!next) {
        return false;
    }

    if (session->sampler) {
        llama_sampler_free(session->sampler);
        session->sampler = nullptr;
    }

    session->sampler = next;
    return true;
}

int32_t tokenizeText(const llama_vocab * vocab, const std::string & text, bool addSpecial) {
    if (vocab == nullptr) return 0;
    int32_t capacity = static_cast<int32_t>(text.size()) + 8;
    std::vector<llama_token> tokens;
    tokens.resize(std::max(16, capacity));

    int32_t n = llama_tokenize(
        vocab,
        text.c_str(),
        static_cast<int32_t>(text.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        addSpecial,
        true
    );

    if (n < 0) {
        tokens.resize(static_cast<size_t>(-n));
        n = llama_tokenize(
            vocab,
            text.c_str(),
            static_cast<int32_t>(text.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            addSpecial,
            true
        );
    }

    return std::max<int32_t>(0, n);
}

std::vector<llama_token> tokenizeTextToVector(const llama_vocab * vocab, const std::string & text, bool addSpecial) {
    std::vector<llama_token> tokens;
    if (vocab == nullptr || text.empty()) {
        return tokens;
    }

    int32_t capacity = static_cast<int32_t>(text.size()) + 8;
    tokens.resize(std::max(16, capacity));

    int32_t n = llama_tokenize(
        vocab,
        text.c_str(),
        static_cast<int32_t>(text.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        addSpecial,
        true
    );

    if (n < 0) {
        tokens.resize(static_cast<size_t>(-n));
        n = llama_tokenize(
            vocab,
            text.c_str(),
            static_cast<int32_t>(text.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            addSpecial,
            true
        );
    }

    if (n <= 0) {
        tokens.clear();
        return tokens;
    }

    tokens.resize(static_cast<size_t>(n));
    return tokens;
}

ToolCallGrammarConfigNative buildToolCallGrammarConfig(const common_chat_params & params) {
    ToolCallGrammarConfigNative config;
    config.grammar = params.grammar;
    config.lazy = params.grammar_lazy;
    config.generationPrompt = params.generation_prompt;

    for (const auto & trigger : params.grammar_triggers) {
        switch (trigger.type) {
            case COMMON_GRAMMAR_TRIGGER_TYPE_WORD:
                config.triggerPatterns.push_back(regex_escape(trigger.value));
                break;
            case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN:
                config.triggerPatterns.push_back(trigger.value);
                break;
            case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN_FULL:
                if (trigger.value.empty()) {
                    config.triggerPatterns.push_back("^$");
                } else {
                    std::string anchored;
                    if (trigger.value.front() != '^') {
                        anchored.push_back('^');
                    }
                    anchored += trigger.value;
                    if (trigger.value.back() != '$') {
                        anchored.push_back('$');
                    }
                    config.triggerPatterns.push_back(std::move(anchored));
                }
                break;
            case COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN:
                if (trigger.token != LLAMA_TOKEN_NULL) {
                    config.triggerTokens.push_back(trigger.token);
                }
                break;
            default:
                break;
        }
    }

    return config;
}

void resetToolCallState(Session * session) {
    if (session == nullptr) {
        return;
    }

    session->toolCallGrammar = ToolCallGrammarConfigNative{};
    session->toolCallParserParams = common_chat_parser_params();
    session->hasToolCallParser = false;
}

bool initializeChatTemplatesForSession(Session * session) {
    if (session == nullptr || session->model == nullptr) {
        return false;
    }

    try {
        session->chatTemplates = common_chat_templates_init(session->model, "");
        return static_cast<bool>(session->chatTemplates);
    } catch (const std::exception & e) {
        LOGE("Failed to initialize chat templates: %s", e.what());
        session->chatTemplates.reset();
        return false;
    } catch (...) {
        LOGE("Failed to initialize chat templates: unknown error");
        session->chatTemplates.reset();
        return false;
    }
}

bool buildChatMessages(
        const std::vector<std::string> & roles,
        const std::vector<std::string> & contents,
        std::vector<common_chat_msg> & outMessages
) {
    if (roles.size() != contents.size()) {
        return false;
    }

    outMessages.clear();
    outMessages.reserve(roles.size());

    for (size_t i = 0; i < roles.size(); ++i) {
        common_chat_msg msg;
        msg.role = roles[i];
        msg.content = contents[i];
        outMessages.push_back(std::move(msg));
    }

    return true;
}

bool tokenToPiece(const llama_vocab * vocab, llama_token token, std::string & out) {
    if (vocab == nullptr) return false;
    std::vector<char> buf;
    buf.resize(256);

    int32_t n = llama_token_to_piece(vocab, token, buf.data(), static_cast<int32_t>(buf.size()), 0, true);
    if (n < 0) {
        buf.resize(static_cast<size_t>(-n));
        n = llama_token_to_piece(vocab, token, buf.data(), static_cast<int32_t>(buf.size()), 0, true);
    }
    if (n <= 0) return false;
    out.assign(buf.data(), buf.data() + n);
    return true;
}

void prefillToolCallGenerationPrompt(Session * session) {
    if (session == nullptr || session->model == nullptr || session->sampler == nullptr) {
        return;
    }
    if (session->toolCallGrammar.grammar.empty() || session->toolCallGrammar.generationPrompt.empty()) {
        return;
    }

    const llama_vocab * vocab = llama_model_get_vocab(session->model);
    auto tokens = tokenizeTextToVector(vocab, session->toolCallGrammar.generationPrompt, false);
    for (const auto token : tokens) {
        llama_sampler_accept(session->sampler, token);
    }
}
