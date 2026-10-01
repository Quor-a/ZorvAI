package com.ai.assistance.quro.llm

/**
 * :llm 进程与主进程之间的错误码。
 *
 * 为什么不用异常类型跨进程传：
 *   Binder 只能传基础类型与 Parcelable。把 Java 异常类型名塞进字符串再在客户端
 *   反射还原，等于把「服务端实现细节」耦合进客户端 —— 客户端一升级就崩。
 *   约定一组稳定数字码 + 一句人话 message 是唯一能长期维护的做法。
 *
 * 码段划分（给以后留空间，别随手加）：
 *   1xxx 调用方用法错误（参数、状态机）
 *   2xxx 模型/文件问题
 *   3xxx 引擎加载失败
 *   4xxx 推理期失败
 *   5xxx 资源不足（内存 / 温控 / 并发）
 */
object QuroLlmError {

    // ── 1xxx 调用方用法 ──────────────────────────────────────────────
    const val BAD_ARGUMENT = 1001
    const val SESSION_NOT_FOUND = 1002
    const val SESSION_BUSY = 1003            // 同一会话已有进行中的生成
    const val SERVICE_NOT_BOUND = 1004        // 客户端还没 bind 上（:llm 进程未起）
    const val TIMEOUT = 1005                  // 客户端等待超时
    const val CANCELLED = 1006

    // ── 2xxx 模型 ───────────────────────────────────────────────────
    const val MODEL_NOT_FOUND = 2001
    const val MODEL_FORMAT_UNSUPPORTED = 2002
    const val MODEL_INCOMPLETE = 2003         // 例如 MNN 目录缺 llm_config.json

    // ── 3xxx 加载 ───────────────────────────────────────────────────
    const val ENGINE_UNAVAILABLE = 3001       // 该风味/该 ABI 未编入此引擎
    const val LOAD_FAILED = 3002
    const val OUT_OF_MEMORY_ON_LOAD = 3003

    // ── 4xxx 推理 ───────────────────────────────────────────────────
    const val GENERATE_FAILED = 4001
    const val EMPTY_OUTPUT = 4002

    // ── 5xxx 资源 ───────────────────────────────────────────────────
    const val MEMORY_BUDGET_EXCEEDED = 5001
    const val ENGINE_SWITCH_REQUIRED = 5002   // 另一引擎正持有权重，需先卸载

    /** 给日志/排障用的人话名。别在 UI 上直接显示英文码名。 */
    fun name(code: Int): String = when (code) {
        BAD_ARGUMENT -> "参数错误"
        SESSION_NOT_FOUND -> "会话不存在"
        SESSION_BUSY -> "该会话已有正在进行的生成"
        SERVICE_NOT_BOUND -> "推理进程未就绪"
        TIMEOUT -> "等待超时"
        CANCELLED -> "已取消"
        MODEL_NOT_FOUND -> "模型文件不存在"
        MODEL_FORMAT_UNSUPPORTED -> "不支持的模型格式"
        MODEL_INCOMPLETE -> "模型文件不完整"
        ENGINE_UNAVAILABLE -> "本地推理引擎不可用"
        LOAD_FAILED -> "模型加载失败"
        OUT_OF_MEMORY_ON_LOAD -> "内存不足，模型加载失败"
        GENERATE_FAILED -> "推理失败"
        EMPTY_OUTPUT -> "推理未产生任何输出"
        MEMORY_BUDGET_EXCEEDED -> "超出内存预算"
        ENGINE_SWITCH_REQUIRED -> "需先卸载另一个引擎的模型"
        else -> "未知错误($code)"
    }
}
