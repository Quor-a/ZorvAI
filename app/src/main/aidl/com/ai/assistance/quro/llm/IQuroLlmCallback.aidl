// =============================================================================
// L1 · 编排 ↔ 引擎 的回调通道（跨进程，:llm 独立进程 → 主进程）
// =============================================================================
// 为什么要 oneway：
//   推理跑在 :llm 进程的工作线程上，每个 token 都要回吐一次。如果这是同步
//   Binder 调用，服务端会被客户端的处理速度**反向拖死** —— 客户端一卡，
//   整个推理循环跟着堵在整个 Binder 事务队列后面，表现为「吐字一顿一顿」。
//   oneway 让它变成「投递即返回」，服务端永不被客户端阻塞。
//
// 代价与对策：
//   oneway 不保证送达、也不保证顺序被观测到（同一 binder 上有序，跨线程不保证）。
//   因此：
//     - 所有事件都带 reqId，客户端按 reqId 路由；乱序/迟到的事件直接丢弃；
//     - **终态事件只有一条**（onDone 或 onError 二选一），客户端以终态为唯一
//       结束判据，不依赖「收到几个 token」；
//     - 客户端必须有超时兜底：oneway 丢了终态就永远等不到，靠超时解锁。
// =============================================================================
package com.ai.assistance.quro.llm;

oneway interface IQuroLlmCallback {
    /**
     * 会话就绪：权重已加载、后端已解析。
     * @param backendJson  形如 {"prefill":"VULKAN","decode":"CPU","thermal":"COOL"}
     *                     实际生效的后端 —— 别让 UI 猜，也别用「用户选了什么」冒充。
     */
    void onReady(long reqId, String engineName, String backendJson) = 1;

    /** 预填充进度（prompt 越长越有意义，可用来画进度条）。 */
    void onPrefill(long reqId, int done, int total) = 2;

    /** 增量文本。可能被切分成任意大小的片段，客户端负责拼接。 */
    void onToken(long reqId, String text) = 3;

    /**
     * 正常结束。
     * @param finishReason "stop" / "length" / "cancel" / "tool_calls"
     * @param statsJson    形如 {"tokens":128,"prefillMs":840,"decodeMs":5210,
     *                             "decodeTps":24.6,"thermalHeadroom":41,
     *                             "throttled":false,"weightBytes":2415919104}
     */
    void onDone(long reqId, String finishReason, String statsJson) = 4;

    /**
     * 失败。终态，之后不会再有本 reqId 的任何事件。
     * @param code 与 QuroLlmError 常量对应；不要把 Java 异常类型名塞进来，
     *             跨进程后那些类型信息没有意义。
     */
    void onError(long reqId, int code, String message) = 5;

    /**
     * 思考段（reasoning）流式更新。**累计**文本，与 onToken 的累计语义一致。
     *
     * 存在原因：思考模型（Qwen3 / DeepSeek-R1 / MiMo 系）的 `<think>…</think>`
     * 会实时上屏到独立的思考气泡（`QuroAssistant.emitThinkingToken`）。
     * 这一路不是「可选的调试信息」—— 缺了它，本地思考模型在 :llm 隔离后
     * 会出现「思考阶段整个 UI 空白十几秒」，与引入进程隔离前的观感不一致。
     *
     * 与 onToken 的关系：同一段文本**不会**同时出现在两者里 —— 服务端在源头
     * 按 `<think>` 边界分流，onThinking 拿到思考段、onToken 拿到可见答案。
     */
    void onThinking(long reqId, String text) = 6;
}
