// =============================================================================
// L3/L4 · LlamaEngine 实现（三）：聊天模板 / 工具调用文法 / 采样参数
// =============================================================================
// 本 TU 的分工（对照架构图 L4「引擎与后端」里 llama.cpp 那一块）：
//   · 采样链参数落地（temperature / topP / topK / 重复惩罚 / 频率·存在惩罚）
//   · 工具调用文法（GBNF）的装载与清除 —— 惰性触发模式
//   · GGUF 内嵌 chat template 的两条渲染路径：
//       ① 并行数组形式（roles[] / contents[]）—— 普通对话
//       ② OpenAI 结构化 JSON + tools —— 工具轮（同时写解析器状态）
//   · 模型输出反解成 OpenAI 风格 tool_calls JSON
//
// 与「旧构架」的分界：
//   旧：这些逻辑直接写在 Java_com_ai_assistance_llama_LlamaNative_* 里，
//       jstring/jobjectArray 与 llama.cpp 的调用交织在同一个函数体；
//       引擎抽象层（quro::llm::Engine）没有任何实现类，registerEngine 零调用点。
//   新：本 TU 只出现 std::string / std::vector<std::string> / bool —— 一个 JNI 类型
//       都没有。JNI 的解包与回转全部留在 llama_jni.cpp。
//
// 真机长注释原样保留：它们记录的是被踩过的坑，不是文风偏好。
// =============================================================================

#include "llama_engine_impl.h"

#include <cstdio>
#include <cstdlib>
#include <exception>
#include <string>
#include <utility>
#include <vector>

// 日志与 SET_ERR 宏由 llama_engine_impl.h 统一提供（三个 TU 共用一套 TAG）。
// Session / SamplingParamsNative / ToolCallGrammarConfigNative 与全部辅助函数
// 的声明也在那里；**不要**在本 TU include llama_engine_logic.inc ——
// 那个 .inc 定义符号，只能被 llama_engine.cpp 一个 TU include（否则重复定义）。

namespace quro {
namespace llm {

namespace {

/// 统一的失败出口：既写回人类可读原因（给上层显示），也写回 err。
/// 为什么两处都写：lastError 是给"聊天气泡"用的（用户能看见），
/// err 是给调用方控制流用的（JNI 层据此返回 JNI_FALSE / nullptr）。
/// 旧实现只设置了一处，结果 Java 侧拿到 false 也说不清为什么。
void failWith(llama_detail::Session* session, std::string* err, const std::string& msg) {
    if (session != nullptr) session->lastError = msg;
    if (err != nullptr) *err = msg;
    LOGE("%s", msg.c_str());
}

}  // namespace

// ═════════════════════════════════════════════════════════════════════════
// 采样参数
// ═════════════════════════════════════════════════════════════════════════
// 每次设置都会**重建整条采样链**（`rebuildSamplerForSession`），而不是改字段。
// 原因：llama.cpp 的采样链是"建好后不可变"的，参数变化必须重建，
// 否则惩罚历史（penaltyLastN）与文法约束的挂载点会与新参数不一致。
bool LlamaEngine::setSamplingParams(float temperature, float topP, int32_t topK,
                                    float repetitionPenalty, float frequencyPenalty,
                                    float presencePenalty, int32_t penaltyLastN,
                                    std::string* err) {
    if (!impl_) {
        if (err) *err = "引擎内部状态缺失（Impl 为空）。";
        return false;
    }
    llama_detail::Session* session = &impl_->session;
    if (session->ctx == nullptr || session->model == nullptr) {
        failWith(session, err, "引擎尚未加载模型，无法设置采样参数。");
        return false;
    }

    session->samplingParams.temperature = static_cast<float>(temperature);
    session->samplingParams.topP = static_cast<float>(topP);
    session->samplingParams.topK = static_cast<int32_t>(topK);
    session->samplingParams.penaltyLastN = static_cast<int32_t>(penaltyLastN);
    session->samplingParams.repeatPenalty = static_cast<float>(repetitionPenalty);
    session->samplingParams.frequencyPenalty = static_cast<float>(frequencyPenalty);
    session->samplingParams.presencePenalty = static_cast<float>(presencePenalty);
    // 每次改参数都重掷种子：旧实现如此，保持行为一致。
    // （同一 seed + 同一 prompt 会让"重新生成"给出完全相同的文本，用户会以为没生效。）
    session->samplingParams.seed = static_cast<uint32_t>(std::rand());

    if (!llama_detail::rebuildSamplerForSession(session)) {
        failWith(session, err, "采样链重建失败（参数非法或文法约束冲突）。");
        return false;
    }
    return true;
}

// ═════════════════════════════════════════════════════════════════════════
// 工具调用文法（GBNF）
// ═════════════════════════════════════════════════════════════════════════
// lazy 模式的语义（旧实现原样保留，很关键）：
//   有 triggerPatterns → 不一开始就约束输出，等模型自己吐出触发词后才切进文法。
//   这对"模型可能直接回答、也可能调工具"的场景是必须的：
//   强制文法会让模型永远只能输出工具调用，普通问答直接被毁。
bool LlamaEngine::setToolCallGrammar(const std::string& grammar,
                                     const std::vector<std::string>& triggerPatterns,
                                     std::string* err) {
    if (!impl_) {
        if (err) *err = "引擎内部状态缺失（Impl 为空）。";
        return false;
    }
    llama_detail::Session* session = &impl_->session;
    if (session->ctx == nullptr || session->model == nullptr) {
        failWith(session, err, "引擎尚未加载模型，无法装载工具调用文法。");
        return false;
    }
    if (grammar.empty()) {
        failWith(session, err, "工具调用文法为空。");
        return false;
    }

    // ── 事务语义 ──
    // 文法装载会连带重建采样链（失败是有可能的：GBNF 语法错误、与已有约束冲突）。
    // 旧实现的做法是先把旧状态存下来，失败后**回滚并再重建一次**，保证会话
    // 不会停在"文法半装载"的状态。这个回滚不能省：半装载状态下的采样链
    // 会一直吐出不合法的 token，用户看到的是持续乱码而不是一次报错。
    const llama_detail::ToolCallGrammarConfigNative previousConfig = session->toolCallGrammar;
    const common_chat_parser_params previousParserParams = session->toolCallParserParams;
    const bool previousHasParser = session->hasToolCallParser;

    session->toolCallGrammar.grammar = grammar;
    session->toolCallGrammar.lazy = !triggerPatterns.empty();
    session->toolCallGrammar.triggerPatterns = triggerPatterns;
    session->toolCallGrammar.triggerTokens.clear();
    session->toolCallGrammar.generationPrompt.clear();
    session->toolCallParserParams = common_chat_parser_params();
    session->hasToolCallParser = false;

    if (!llama_detail::rebuildSamplerForSession(session)) {
        session->toolCallGrammar = previousConfig;
        session->toolCallParserParams = previousParserParams;
        session->hasToolCallParser = previousHasParser;
        (void) llama_detail::rebuildSamplerForSession(session);
        failWith(session, err, "工具调用文法装载失败，已回滚到上一次配置。");
        return false;
    }

    LOGI("Tool-call grammar enabled. trigger_patterns=%zu lazy=%d",
         session->toolCallGrammar.triggerPatterns.size(),
         session->toolCallGrammar.lazy ? 1 : 0);
    return true;
}

bool LlamaEngine::clearToolCallGrammar(std::string* err) {
    if (!impl_) {
        if (err) *err = "引擎内部状态缺失（Impl 为空）。";
        return false;
    }
    llama_detail::Session* session = &impl_->session;
    if (session->ctx == nullptr || session->model == nullptr) {
        failWith(session, err, "引擎尚未加载模型，无需清除工具调用文法。");
        return false;
    }

    const llama_detail::ToolCallGrammarConfigNative previousConfig = session->toolCallGrammar;
    const common_chat_parser_params previousParserParams = session->toolCallParserParams;
    const bool previousHasParser = session->hasToolCallParser;

    llama_detail::resetToolCallState(session);

    if (!llama_detail::rebuildSamplerForSession(session)) {
        session->toolCallGrammar = previousConfig;
        session->toolCallParserParams = previousParserParams;
        session->hasToolCallParser = previousHasParser;
        (void) llama_detail::rebuildSamplerForSession(session);
        failWith(session, err, "清除工具调用文法后采样链重建失败，已回滚。");
        return false;
    }
    return true;
}

// ═════════════════════════════════════════════════════════════════════════
// 聊天模板渲染（普通对话）
// ═════════════════════════════════════════════════════════════════════════
// roles / contents 是**两条等长并行数组**（不是 map）—— 因为对话里同一角色
// 可以连续出现多次，用 map 会把相邻的 user 消息合并掉，模板语义被破坏。
// ═════════════════════════════════════════════════════════════════════════
// 思考段开关（与 MNN 的 MNNLlmSession.setThinkingMode 对齐）
// ═════════════════════════════════════════════════════════════════════════
// 上游机制，不是自研：
//   llama.cpp 的 common_chat_templates_inputs 自带 enable_thinking 字段，
//   common_chat_templates_apply 会把它作为**同名 jinja 变量**注入模板上下文
//   （common/chat.cpp: {"enable_thinking", inputs.enable_thinking}）。
//   MNN 侧同理，只是走 set_config 的 jinja.context。
//   于是两侧的**用户可见行为**完全一致：模板里
//   `{%- if enable_thinking is false %}` 那一支被选中，思考段不再产出。
//
// 能力探测同样用上游函数（chat.h: common_chat_templates_support_enable_thinking）。
// 它内部会把模板真渲染一次、再看 params.supports_thinking —— 比自己扫模板字符串
// 找关键字可靠得多（变量名可能出现在注释、转义或默认值里）。
namespace {

/// 探测 + 缓存。返回模板**是否会产出思考段** —— 注意：不是"模板认识
/// enable_thinking 这个变量"。
/// 🔎 别被上游函数名骗了：common_chat_templates_support_enable_thinking 返回的是
///    params.supports_thinking，它由 autoparser 解析模板得出
///    （chat.cpp: supports_thinking = autoparser.reasoning.mode != NONE），
///    **与 enable_thinking 的取值毫无关系**。等价于 MNN 侧的
///    MnnModelCapabilities.emitsThinkBlock（模板里有 <think> 段），
///    而不是 supportsThinkingToggle（模板里出现 "enable_thinking" 字面）。
///    驱动层正是靠这个值决定要不要开思考 —— 与 MNN 路径同一口径。
/// 探测要让上游真渲染一次模板，而模型作者写坏的模板会在 render 时抛异常 ——
/// 异常穿过 JNI 帧就是 std::terminate（App 直接消失，没有 Java 异常可抓），
/// 所以这里必须兜住。
bool detectSupportsThinking(llama_detail::Session* session) {
    if (session == nullptr) return false;
    if (session->supportsThinking >= 0) return session->supportsThinking == 1;
    bool ok = false;
    if (session->chatTemplates != nullptr) {
        try {
            ok = common_chat_templates_support_enable_thinking(session->chatTemplates.get());
        } catch (const std::exception& e) {
            LOGE("探测 enable_thinking 支持失败：%s", e.what());
            ok = false;
        } catch (...) {
            LOGE("探测 enable_thinking 支持失败（未知错误）。");
            ok = false;
        }
    }
    session->supportsThinking = ok ? 1 : 0;
    return ok;
}

}  // namespace

namespace {

/// 去掉首尾空白（上游部分标签常量带尾空格，如 `<|channel>thought `）。
std::string trimTag(const std::string& s) {
    size_t b = 0;
    size_t e = s.size();
    while (b < e && (s[b] == ' ' || s[b] == '\t' || s[b] == '\n' || s[b] == '\r')) ++b;
    while (e > b && (s[e - 1] == ' ' || s[e - 1] == '\t' || s[e - 1] == '\n' || s[e - 1] == '\r')) --e;
    return s.substr(b, e - b);
}

/// 把上游 detector / autoparser 探测到的**真实**思考标签并进分流器。
///
/// 为什么这一步必须有：ThinkSplitter 的默认标记集只覆盖
/// `<think>` / `<thinking>` / 全角三种形态（见 defaultMarkers 的注释），
/// 而 llama.cpp 的模板 detector 已经把答案写在 common_chat_params 里：
///     [THINK] / [/THINK]
///     <|channel|>analysis<|message|> / <|end|>
///     <|channel>thought / <channel|>
///     <think> / </think>
///     （autoparser 分支则是真解析模板后动态得出）
/// 不接这一段，用非标准标签的模型就会**思考原文直接上屏** ——
/// 分流器认不出开标签，整段推理被当成正文。
///
/// 用**定长标记**（terminator 为空）而不是 `prefix + '>'`：
/// 上游给的是完整标签串，而 `<|channel|>analysis<|message|>` 有 28 字节，
/// 远超 maxTagLength(=16) 的保护上限 —— 定长匹配不走那条保护，因此不受限。
///
/// 探测不到（thinking_start_tag 为空）时**什么都不做**，保留默认集 ——
/// 也就是行为与本改动之前完全一致，不会因为拿不到标签而退化。
void applyDetectedThinkingTags(llama_detail::Session* session,
                               const common_chat_params& params) {
    if (session == nullptr || params.thinking_start_tag.empty()) {
        return;
    }
    std::vector<ThinkSplitter::Marker> extra;

    const std::string openTag = trimTag(params.thinking_start_tag);
    if (!openTag.empty()) {
        ThinkSplitter::Marker openMarker;
        openMarker.prefix = openTag;
        openMarker.terminator.clear();   // 定长
        openMarker.open = true;
        extra.push_back(std::move(openMarker));
    }
    for (const std::string& rawEnd : params.thinking_end_tags) {
        const std::string endTag = trimTag(rawEnd);
        if (endTag.empty()) {
            continue;
        }
        ThinkSplitter::Marker closeMarker;
        closeMarker.prefix = endTag;
        closeMarker.terminator.clear();
        closeMarker.open = false;
        extra.push_back(std::move(closeMarker));
    }
    session->thinkSplitter.addMarkers(extra);
}

}  // namespace

bool LlamaEngine::setThinkingMode(bool enabled, bool* supported, std::string* err) {
    if (!impl_) {
        if (err) *err = "引擎内部状态缺失（Impl 为空）。";
        return false;
    }
    llama_detail::Session* session = &impl_->session;
    if (session->chatTemplates == nullptr) {
        // 刻意**不**写 lastError：与 applyStructuredChatTemplate 同一理由 ——
        // "GGUF 没内嵌 chat template"是可预期常态（驱动层每个模型都会探测一次
        // 思考能力），写进 lastError 会让聊天气泡冒出一条误导性的错误。
        // 调用方拿 err 判断即可。
        if (err) *err = "模型未就绪或该 GGUF 未内嵌聊天模板（chat template 缺失）。";
        return false;
    }
    // 刻意**不**因"模板不支持"而拒绝：与 MNN 对齐 —— 配置写入成功即成功。
    // 模板不支持时这次设置是无害的空操作，上层用 supportsThinking() 单独判断。
    session->thinkingMode = enabled ? 1 : 0;
    if (supported != nullptr) *supported = detectSupportsThinking(session);
    LOGI("setThinkingMode: enabled=%d supported=%d",
         enabled ? 1 : 0, session->supportsThinking == 1 ? 1 : 0);
    return true;
}

bool LlamaEngine::supportsThinking(std::string* err) {
    if (!impl_) {
        if (err) *err = "引擎内部状态缺失（Impl 为空）。";
        return false;
    }
    llama_detail::Session* session = &impl_->session;
    if (session->chatTemplates == nullptr) {
        if (err) *err = "模型未就绪或该 GGUF 未内嵌聊天模板（chat template 缺失）。";
        return false;
    }
    return detectSupportsThinking(session);
}

bool LlamaEngine::applyChatTemplate(const std::vector<std::string>& roles,
                                    const std::vector<std::string>& contents,
                                    bool addAssistant, std::string* out, std::string* err) {
    if (out == nullptr) {
        if (err) *err = "输出参数为空。";
        return false;
    }
    out->clear();
    if (!impl_) {
        if (err) *err = "引擎内部状态缺失（Impl 为空）。";
        return false;
    }
    llama_detail::Session* session = &impl_->session;
    if (session->model == nullptr || !session->chatTemplates) {
        failWith(session, err, "模型未就绪或该 GGUF 未内嵌聊天模板（chat template 缺失）。");
        return false;
    }

    if (roles.empty() || contents.empty() || roles.size() != contents.size()) {
        char buf[128];
        snprintf(buf, sizeof(buf), "消息数组非法（roles=%zu contents=%zu）",
                 roles.size(), contents.size());
        failWith(session, err, buf);
        return false;
    }

    std::vector<common_chat_msg> messages;
    if (!llama_detail::buildChatMessages(roles, contents, messages)) {
        failWith(session, err, "构建聊天消息失败（角色/内容为空或非法）。");
        return false;
    }

    common_chat_templates_inputs inputs;
    inputs.messages = std::move(messages);
    inputs.add_generation_prompt = addAssistant;
    inputs.use_jinja = true;
    // 三态：**只有上层显式设置过**才覆盖 llama.cpp 的默认值(true)。
    // "上层没管思考"与"上层明确关掉思考"必须是两件事。
    if (session->thinkingMode >= 0) {
        inputs.enable_thinking = session->thinkingMode == 1;
    }

    // try/catch 是必需的：jinja 模板执行由 llama.cpp 内部的模板引擎负责，
    // 模型作者写坏的模板会在 render 时抛 std::runtime_error。
    // 让它穿过 JNI 帧就是 std::terminate（整个 App 直接消失，没有 Java 异常可抓）。
    try {
        const common_chat_params params =
            common_chat_templates_apply(session->chatTemplates.get(), inputs);
        if (params.prompt.empty()) {
            failWith(session, err, "聊天模板渲染结果为空（模板与消息不匹配）。");
            return false;
        }
        // 🧠 本函数由此**不再是纯函数**：顺带把模板真正使用的思考标签并进分流器。
        // 放这里的理由是它每轮生成前必被调用一次，而标签只有渲染时才知道；
        // addMarkers 幂等，重复注入无副作用（见其实现注释）。
        applyDetectedThinkingTags(session, params);
        *out = params.prompt;
        return true;
    } catch (const std::exception& e) {
        failWith(session, err, std::string("聊天模板渲染异常：") + e.what());
        return false;
    } catch (...) {
        failWith(session, err, "聊天模板渲染异常（未知错误）。");
        return false;
    }
}

// ═════════════════════════════════════════════════════════════════════════
// 聊天模板渲染（工具轮 · OpenAI 结构化）
// ═════════════════════════════════════════════════════════════════════════
// 与上面的普通路径有两点本质差别：
//   ① 输入是 OpenAI 风格的 messages / tools JSON，由 llama.cpp 的 oaicompat 转换；
//   ② 渲染的同时**把解析器状态写进会话**（文法 + parser params），
//      这样生成结束后才能把模型输出反解成结构化 tool_calls。
//      也就是说本函数是有副作用的 —— 名字看起来像纯渲染，实际是"进入工具轮"。
bool LlamaEngine::applyStructuredChatTemplate(const std::string& messagesJson,
                                              const std::string& toolsJson,
                                              bool addAssistant, std::string* out,
                                              std::string* err) {
    if (out == nullptr) {
        if (err) *err = "输出参数为空。";
        return false;
    }
    out->clear();
    if (!impl_) {
        if (err) *err = "引擎内部状态缺失（Impl 为空）。";
        return false;
    }
    llama_detail::Session* session = &impl_->session;
    if (session->model == nullptr || !session->chatTemplates || session->ctx == nullptr) {
        // 这里刻意**不**写 lastError：旧实现直接 return nullptr，
        // 而"模型未就绪"在工具轮是一个可预期的常态（首次调用即触发），
        // 写进去会让聊天气泡冒出一条误导性的错误。
        if (err) *err = "模型或上下未就绪。";
        return false;
    }
    if (messagesJson.empty()) {
        if (err) *err = "消息 JSON 为空。";
        return false;
    }

    // 与 setToolCallGrammar 同一套事务语义：失败必须回滚，不能留半装载状态。
    const llama_detail::ToolCallGrammarConfigNative previousConfig = session->toolCallGrammar;
    const common_chat_parser_params previousParserParams = session->toolCallParserParams;
    const bool previousHasParser = session->hasToolCallParser;

    try {
        const auto messages = nlohmann::ordered_json::parse(messagesJson);
        const auto tools = toolsJson.empty()
            ? nlohmann::ordered_json()
            : nlohmann::ordered_json::parse(toolsJson);

        common_chat_templates_inputs inputs;
        inputs.messages = common_chat_msgs_parse_oaicompat(messages);
        inputs.tools = common_chat_tools_parse_oaicompat(tools);
        inputs.tool_choice = inputs.tools.empty()
            ? COMMON_CHAT_TOOL_CHOICE_NONE
            : COMMON_CHAT_TOOL_CHOICE_AUTO;
        inputs.add_generation_prompt = addAssistant;
        inputs.use_jinja = true;
        // 工具轮同样受思考开关约束（见 applyChatTemplate 处的说明）。
        if (session->thinkingMode >= 0) {
            inputs.enable_thinking = session->thinkingMode == 1;
        }

        const common_chat_params params =
            common_chat_templates_apply(session->chatTemplates.get(), inputs);
        if (params.prompt.empty()) {
            if (err) *err = "结构化聊天模板渲染结果为空。";
            return false;
        }
        // 🧠 与 applyChatTemplate 同一处理：工具轮的模板同样可能用非标准思考标签。
        applyDetectedThinkingTags(session, params);

        session->toolCallGrammar = llama_detail::buildToolCallGrammarConfig(params);
        session->toolCallParserParams = common_chat_parser_params(params);
        session->toolCallParserParams.parse_tool_calls = true;
        session->hasToolCallParser = !params.parser.empty();
        if (session->hasToolCallParser) {
            session->toolCallParserParams.parser.load(params.parser);
        }

        if (!llama_detail::rebuildSamplerForSession(session)) {
            session->toolCallGrammar = previousConfig;
            session->toolCallParserParams = previousParserParams;
            session->hasToolCallParser = previousHasParser;
            (void) llama_detail::rebuildSamplerForSession(session);
            failWith(session, err, "结构化聊天模板的采样链重建失败，已回滚。");
            return false;
        }

        *out = params.prompt;
        return true;
    } catch (const std::exception& e) {
        session->toolCallGrammar = previousConfig;
        session->toolCallParserParams = previousParserParams;
        session->hasToolCallParser = previousHasParser;
        (void) llama_detail::rebuildSamplerForSession(session);
        failWith(session, err, std::string("结构化聊天模板渲染失败：") + e.what());
        return false;
    } catch (...) {
        session->toolCallGrammar = previousConfig;
        session->toolCallParserParams = previousParserParams;
        session->hasToolCallParser = previousHasParser;
        (void) llama_detail::rebuildSamplerForSession(session);
        failWith(session, err, "结构化聊天模板渲染失败（未知错误）。");
        return false;
    }
}

// ═════════════════════════════════════════════════════════════════════════
// 工具调用反解
// ═════════════════════════════════════════════════════════════════════════
// 「无工具调用」是**正常结果**，不是错误：返回 false + out 清空 + **不写 err**。
// 调用方（JNI 层）据此返回 Java 的 null，Kotlin 侧把这段文本当普通回答处理。
// 若这里写成"失败"，模型每一句闲聊都会让 UI 弹一次错误提示。
bool LlamaEngine::parseToolCallResponse(const std::string& content, std::string* out,
                                        std::string* err) {
    if (out == nullptr) {
        if (err) *err = "输出参数为空。";
        return false;
    }
    out->clear();
    if (!impl_) {
        if (err) *err = "引擎内部状态缺失（Impl 为空）。";
        return false;
    }
    llama_detail::Session* session = &impl_->session;
    if (!session->hasToolCallParser) return false;
    if (content.empty()) return false;

    try {
        const common_chat_msg parsed =
            common_chat_parse(content, false, session->toolCallParserParams);
        if (parsed.tool_calls.empty()) return false;

        // 只保留 tool_calls 字段：上层要的就是 OpenAI 的 tool_calls 数组，
        // 原样 dump 会把 content/role 一起带出去，Java 侧还要再筛一遍。
        auto normalized = nlohmann::ordered_json::object();
        normalized["tool_calls"] = parsed.to_json_oaicompat()["tool_calls"];
        *out = normalized.dump();
        return true;
    } catch (const std::exception& e) {
        // 解析失败**不写 err**（同"无工具调用"的理由：模型输出不合法很常见，
        // 上层能靠 content 兜底）；只留日志。
        LOGE("Failed to parse tool-call response: %s", e.what());
        return false;
    } catch (...) {
        LOGE("Failed to parse tool-call response: unknown error");
        return false;
    }
}

}  // namespace llm
}  // namespace quro
