package com.ai.assistance.mnn

/**
 * MNN 采样层的抗复读兜底参数。
 *
 * ## 背景
 * MNN 引擎默认配置下**重复惩罚是完全失效的**：
 * - `mixed_samplers` 默认 = `[topK, tfs, typical, topP, min_p, temperature]`（llmconfig.hpp:445），
 *   不含 `"penalty"`，而 `Sampler::buildPipeline`（sampler.cpp:208-210）只有在列表里看到
 *   `"penalty"` 才会把 `stepPenalty` 挂进管线；
 * - `repetition_penalty` 默认 = 1.0（llmconfig.hpp:485），而 `stepPenalty`（sampler.cpp:278）
 *   在 `repPenalty <= 1.0 && presPenalty <= 0 && freqPenalty <= 0` 时直接 return。
 *
 * 结果是模型对复读零约束，一旦采样进入退化态就会一路刷到 `max_new_tokens`。
 * 本类的默认值用于把这条链路补齐，参数取值与 llama.cpp 路径保持同一量级
 * （llama.cpp 侧已显式设置 `repetitionPenalty = 1.1`）。
 *
 * @param enabled 是否注入抗复读采样配置。false = 完全沿用模型自带配置（不推荐，仅用于排障对照）。
 * @param repetitionPenalty 重复惩罚系数，> 1.0 才生效。仅在模型未配置有效值时使用。
 * @param penaltyWindow 惩罚窗口（token 数）。引擎的 `stepPenalty` 惩罚的是
 *   `mContext->history_tokens`（含 prompt），不限窗会连 system prompt 里的人设词一起压制，
 *   反而损害正常表达，因此必须限窗。
 * @param nGram n-gram 重复检测长度，配合 [nGramFactor] 使用。
 * @param nGramFactor n-gram 惩罚放大系数，必须 > 1.0 才会启用（sampler.cpp:277）。
 *   尾部 n-gram 完整命中时惩罚会被拉到 `max_penalty`（默认 10.0，sampler.hpp:52），
 *   等效于直接掐断死循环。
 * @param temperature 可选覆盖；null = 沿用模型自带配置。
 * @param topK 可选覆盖；null = 沿用模型自带配置。
 * @param topP 可选覆盖；null = 沿用模型自带配置。
 */
data class MnnSamplerTuning(
    val enabled: Boolean = true,
    val repetitionPenalty: Float = 1.1f,
    val penaltyWindow: Int = 256,
    val nGram: Int = 8,
    val nGramFactor: Float = 1.02f,
    val temperature: Float? = null,
    val topK: Int? = null,
    val topP: Float? = null,
)
