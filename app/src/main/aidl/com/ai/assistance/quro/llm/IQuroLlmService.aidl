// =============================================================================
// L1 · 编排 ↔ 引擎 的 AIDL 契约（:llm 独立进程）
// =============================================================================
// 为什么必须进程隔离（硬规则第 1 条）：
//   4GB 级量化模型加载后，native heap 可以飙到 3.8GB。放在主进程里，低端机上
//   LMK 会在几十秒内把整个 App 杀掉 —— 用户看到的是「聊两句就整个界面闪退」。
//   放进 :llm 独立进程后，被杀的是引擎进程，主界面活着并能给出可读提示。
//   附带收益：GPU delegate 的 native crash 只 kill 自己，不会带走 UI。
//   代价是多 30–50MB 进程开销 —— 完全值。
//
// 两条接口纪律：
//   1. 请求必须带 ID（reqId / sessionId），因为一个进程里可能同时有多个会话，
//      且 oneway 回调无法靠调用顺序分辨归属。
//   2. 取消必须是独立方法（cancel(reqId)），不能靠「关掉会话」代替 ——
//      关会话要等 unload，而用户按「停止」时期望的是立刻停。
// =============================================================================
package com.ai.assistance.quro.llm;

import com.ai.assistance.quro.llm.IQuroLlmCallback;

interface IQuroLlmService {
    // ── 会话生命周期 ────────────────────────────────────────────────────

    /**
     * 加载模型并建立会话（耗时操作：大模型可达数十秒）。
     *
     * 实现要求：本方法**阻塞**，由客户端在 IO 协程里调用并自行加超时。
     * 之所以不做成异步 + 回调：加载期的错误（路径不对 / 内存不足 / 权重损坏）
     * 是同步语义，用回调反而要在客户端再拼一次状态机。
     *
     * ⚠️ 返回 JSON 信封而**不是**靠抛异常传错误，这是刻意的：
     *   Binder 的 Parcel.writeException 只对内置的少数异常类型（IllegalArgument
     *   / IllegalState / Security / …）保留类型与字段；自定义异常一律降级成
     *   一个 message 为 "类名: 原文" 的通用 RuntimeException，错误码直接丢失。
     *   而 android.os.ServiceSpecificException 又不在公开 SDK 里（@hide），
     *   编译期 Unresolved reference。两个坑叠起来，结论就是：跨进程不要用异常
     *   传业务错误，用一个自描述的信封。
     *
     * @param modelPath   模型路径。*.gguf → llama.cpp；含 llm_config.json 的目录
     *                    或 *.mnn → MNN。路由由原生层按格式判定，客户端不猜。
     * @param optionsJson {"threads":4,"gpuLayers":0,"mnnBackend":"cpu",
     *                    "contextSize":2048,"precision":"low", …}
     * @return 成功：{"ok":true,"sessionId":123}
     *         失败：{"ok":false,"code":2001,"message":"模型不存在：…"}
     *         code 见 QuroLlmError；message 已可直接展示给用户。
     */
    String openSession(String modelPath, String optionsJson) = 1;

    /** 卸载会话并释放权重。幂等：对已关闭的 sessionId 调用不报错。 */
    void closeSession(long sessionId) = 2;

    boolean sessionLoaded(long sessionId) = 3;

    // ── 推理 ────────────────────────────────────────────────────────────

    /**
     * 开始一次生成。**oneway**：投递即返回，结果经 cb 回流。
     *
     * 为什么要 oneway：一次生成长达数十秒，同步调用会占死一个 Binder 线程；
     * 更糟的是 cancel 会被排在它后面，导致「点停止没反应」。
     *
     * 实现要求：服务端把这次生成丢到自己的推理线程池执行，并且**同一个
     * sessionId 上同时只允许一个进行中的 reqId**（引擎的 KV cache 是单份的，
     * 并发写会直接数据竞争）。第二次调用必须回 SESSION_BUSY。
     *
     * @param reqId      客户端生成，全局唯一；所有回调都带它。
     * @param requestJson 单个自描述 JSON，避免「prompt 还是 messages」的歧义 ——
     *                    两个引擎的输入形态天生不同：llama.cpp 侧是渲染好的
     *                    原始 prompt（generateStream），MNN 侧是结构化 messages
     *                    （generateStreamStructured，由原生层套模型自带模板）。
     *                    用一个 JSON 承载两者，服务端按字段有无分流，
     *                    客户端不需要知道当前跑的是哪个引擎。
     *
     *    {
     *      "prompt":   "…渲染好的完整 prompt（llama.cpp 路径用）",
     *      "messages": [{"role":"user","content":"…"}],   // 有则走结构化路径
     *      "tools":    [ …OpenAI 兼容 tools 数组，可选… ],
     *      "maxTokens": 512,
     *      "temperature": 0.7, "topP": 0.9, "topK": 40,
     *      "repetitionPenalty": 1.1, "frequencyPenalty": 0.0, "presencePenalty": 0.0
     *    }
     */
    oneway void generate(long sessionId, long reqId, String requestJson,
                         IQuroLlmCallback cb) = 4;

    /**
     * 取消指定请求。**oneway**，任意线程可调，且必须在毫秒级生效
     * （原生层靠引擎自己的 abort 标志打断 decode 循环，不是等它自然结束）。
     */
    oneway void cancel(long reqId) = 5;

    /** 取消某个会话上所有进行中的请求（切模型 / 退出对话时用）。 */
    oneway void cancelAll(long sessionId) = 6;

    // ── 可观测性 ────────────────────────────────────────────────────────

    /** 会话统计（tokens / 耗时 / 实测 tps / 温控档位）。JSON。 */
    String stats(long sessionId) = 7;

    /** 设备画像（大核数、可用内存、内存预算）。JSON。用于排障与参数推荐。 */
    String deviceProfile() = 8;

    /** 服务端自检：进程名、已注册引擎、当前温控档位。JSON。 */
    String diagnostics() = 9;

    /** 主动退出 :llm 进程（换模型释放内存时用，避免常驻 30–50MB）。 */
    oneway void shutdown() = 10;

    // ── 兼容通道：把「完整的本地引擎执行」整段搬进 :llm ──────────────────

    /**
     * 在 :llm 进程内执行一次完整的本地推理，语义与主进程的
     * `QuroLocalEngine.run(...)` **逐字等价**。
     *
     * 为什么需要它（与 [generate] 的分工）：
     *   [generate] 是「L3 统一抽象」路径 —— 会话由服务端持有，客户端只发
     *   prompt/messages，适合未来收敛到 C++ `quro::llm::Engine` 单一入口。
     *   但迁移不能一步到位：现网跑通的 prompt 组装（chat template + tools 渲染）、
     *   思考段流式剥离、MNN 抗复读采样链、常驻会话闸门，全部长在
     *   `QuroLocalEngineNative` 里且已被真机验证。
     *   本方法让**同一份代码换个进程跑**成为可能 —— 进程隔离（硬规则第 1 条）
     *   因此可以独立于 L3 抽象先行落地，功能零退化。
     *
     * 取消语义：[cancel] 对本方法同样有效（按 reqId 命中）。实现侧必须在
     *   原生 decode 循环里轮询取消标志，不能等这次生成自然结束。
     *
     * @param requestJson {
     *   "model":   {"id":"…","name":"…","path":"…","type":"MNN"|"LLAMA_CPP"},
     *   "modelName": "…",                  // 展示名，供 llama 文件名解析
     *   "messages": [{"role":"…","content":"…","toolCallId":null,"toolCalls":[…]}, …],
     *   "temperature": 0.7,
     *   "maxTokens": 512,
     *   "contextWindow": 4096,
     *   "toolSpecsJson": "…"               // 可选，非空时走工具调用模板
     * }
     *
     * 回调约定（复用 [IQuroLlmCallback] 的既有方法，不新增方法以免破坏已发布的契约）：
     *   · [IQuroLlmCallback.onReady]   —— 开始时一次，engineName 取 "MNN"/"llama.cpp"
     *   · [IQuroLlmCallback.onToken]   —— **累计**文本（与 QuroLocalEngine.onToken 一致）
     *   · [IQuroLlmCallback.onDone]    —— 唯一的正常终态。`statsJson` 字段承载
     *     序列化后的 QuroLlmResult：
     *     {"kind":"text","content":"…"}
     *     {"kind":"tool_calls","calls":[{"id":"…","name":"…","arguments":"…"}]}
     *     {"kind":"error","message":"…"}   ← 引擎级业务失败（含聊天门禁提示），
     *                                        不是传输失败，故走 onDone 而非 onError
     *   · [IQuroLlmCallback.onError]   —— 仅用于**执行期异常**（OOM / 原生崩溃前
     *     的兜底 / 请求 JSON 非法），带 QuroLlmError 码。
     */
    oneway void execLocal(long reqId, String requestJson,
                          IQuroLlmCallback cb) = 11;
}
