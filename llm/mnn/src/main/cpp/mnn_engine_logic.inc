// =============================================================================
// L4 · MNN 引擎的纯函数层（由 scripts/refactor_split_mnn.py 从 mnnllmnative.cpp
// 逐字抽取，**不要手改** —— 要改逻辑请改抽取结果对应的引擎文件，
// 若要重新抽取，请修改脚本而不是本文件）
// =============================================================================
// 内容：JSON 转义与拼装、LlmContext → JSON、内置 ChatML 工具说明与回退渲染、
//       结构化（工具调用）提示词渲染核心。
//
// 为什么这些放一个 .inc 而不是散在各 .cpp：
//   它们是**无状态纯函数**（只依赖入参 Llm*，不碰 Session），三个 TU 都要用。
//   本文件只被 mnn_engine.cpp 一个 TU include（定义符号只能出现一次），
//   其余 TU 通过 mnn_engine_impl.h 的声明调用。
// =============================================================================

// ═══ jsonEscape ═══
std::string jsonEscape(const std::string& input) {
    std::string output;
    output.reserve(input.size() + 16);
    for (char ch : input) {
        switch (ch) {
            case '\\':
                output += "\\\\";
                break;
            case '"':
                output += "\\\"";
                break;
            case '\b':
                output += "\\b";
                break;
            case '\f':
                output += "\\f";
                break;
            case '\n':
                output += "\\n";
                break;
            case '\r':
                output += "\\r";
                break;
            case '\t':
                output += "\\t";
                break;
            default:
                output += ch;
                break;
        }
    }
    return output;
}

// ═══ appendIntVectorJson ═══
void appendIntVectorJson(std::ostringstream& oss, const std::vector<int>& values) {
    oss << "[";
    for (size_t i = 0; i < values.size(); ++i) {
        if (i > 0) {
            oss << ",";
        }
        oss << values[i];
    }
    oss << "]";
}

// ═══ llmStatusToString ═══
const char* llmStatusToString(LlmStatus status) {
    switch (status) {
        case LlmStatus::RUNNING:
            return "RUNNING";
        case LlmStatus::NORMAL_FINISHED:
            return "NORMAL_FINISHED";
        case LlmStatus::MAX_TOKENS_FINISHED:
            return "MAX_TOKENS_FINISHED";
        case LlmStatus::USER_CANCEL:
            return "USER_CANCEL";
        case LlmStatus::INTERNAL_ERROR:
            return "INTERNAL_ERROR";
        default:
            return "UNKNOWN";
    }
}

// ═══ contextToJson ═══
std::string contextToJson(const LlmContext* context) {
    if (context == nullptr) {
        return "";
    }

    std::ostringstream oss;
    oss << "{";
    oss << "\"prompt_len\":" << context->prompt_len << ",";
    oss << "\"gen_seq_len\":" << context->gen_seq_len << ",";
    oss << "\"all_seq_len\":" << context->all_seq_len << ",";
    oss << "\"load_us\":" << context->load_us << ",";
    oss << "\"vision_us\":" << context->vision_us << ",";
    oss << "\"audio_us\":" << context->audio_us << ",";
    oss << "\"prefill_us\":" << context->prefill_us << ",";
    oss << "\"decode_us\":" << context->decode_us << ",";
    oss << "\"sample_us\":" << context->sample_us << ",";
    oss << "\"pixels_mp\":" << context->pixels_mp << ",";
    oss << "\"audio_input_s\":" << context->audio_input_s << ",";
    oss << "\"current_token\":" << context->current_token << ",";
    oss << "\"status_code\":" << static_cast<int>(context->status) << ",";
    oss << "\"status\":\"" << llmStatusToString(context->status) << "\",";
    oss << "\"generate_str\":\"" << jsonEscape(context->generate_str) << "\",";
    oss << "\"history_tokens\":";
    appendIntVectorJson(oss, context->history_tokens);
    oss << ",";
    oss << "\"output_tokens\":";
    appendIntVectorJson(oss, context->output_tokens);
    oss << "}";
    return oss.str();
}

// ═══ buildToolsInstruction ═══
std::string buildToolsInstruction(const rapidjson::Document& toolsDoc) {
    if (!toolsDoc.IsArray() || toolsDoc.Empty()) {
        return "";
    }
    std::ostringstream oss;
    oss << "\n\n# Tools\n\n"
        << "You may call one or more functions to assist with the user query.\n\n"
        << "You are provided with function signatures within <tools></tools> XML tags:\n<tools>\n";
    for (const auto& tool : toolsDoc.GetArray()) {
        rapidjson::StringBuffer buf;
        rapidjson::Writer<rapidjson::StringBuffer> writer(buf);
        tool.Accept(writer);
        oss << buf.GetString() << "\n";
    }
    oss << "</tools>\n\n"
        << "For each function call, return a json object with function name and arguments "
        << "within <tool_call></tool_call> XML tags:\n"
        << "<tool_call>\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n</tool_call>";
    return oss.str();
}

// ═══ renderChatMlFallback ═══
std::string renderChatMlFallback(const rapidjson::Document& messagesDoc,
                                        const std::string& toolsField) {
    rapidjson::Document toolsDoc;
    toolsDoc.Parse(toolsField.c_str());
    const std::string toolsInstruction = buildToolsInstruction(toolsDoc);

    // 先探一遍有没有 system 消息，决定工具说明是"挂到已有 system"还是"新造一条 system"。
    bool hasSystem = false;
    for (const auto& msg : messagesDoc.GetArray()) {
        if (msg.IsObject() && msg.HasMember("role") && msg["role"].IsString() &&
            std::string(msg["role"].GetString()) == "system") {
            hasSystem = true;
            break;
        }
    }

    std::ostringstream oss;
    if (!hasSystem && !toolsInstruction.empty()) {
        oss << "<|im_start|>system\nYou are a helpful assistant." << toolsInstruction << "<|im_end|>\n";
    }

    bool toolsAttached = hasSystem ? false : true;  // 无 system 时上面已挂载
    bool anyMessage = false;
    for (const auto& msg : messagesDoc.GetArray()) {
        if (!msg.IsObject() || !msg.HasMember("role") || !msg["role"].IsString()) {
            continue;
        }
        const std::string role = msg["role"].GetString();
        const std::string content = (msg.HasMember("content") && msg["content"].IsString())
            ? msg["content"].GetString() : "";

        if (role == "tool") {
            // 工具执行结果：ChatML 里没有 tool 角色，按 Qwen 约定降级为 user + <tool_response>。
            oss << "<|im_start|>user\n<tool_response>\n" << content << "\n</tool_response><|im_end|>\n";
            anyMessage = true;
            continue;
        }

        if (role == "assistant" && msg.HasMember("tool_calls") && msg["tool_calls"].IsArray()) {
            // 助手上一轮发起的工具调用：回放成 <tool_call> 包裹，保证多轮上下文自洽。
            oss << "<|im_start|>assistant\n";
            if (!content.empty()) {
                oss << content << "\n";
            }
            for (const auto& call : msg["tool_calls"].GetArray()) {
                const rapidjson::Value* fn = nullptr;
                if (call.IsObject() && call.HasMember("function")) {
                    fn = &call["function"];
                } else if (call.IsObject()) {
                    fn = &call;
                }
                if (fn == nullptr) continue;
                rapidjson::StringBuffer buf;
                rapidjson::Writer<rapidjson::StringBuffer> writer(buf);
                fn->Accept(writer);
                oss << "<tool_call>\n" << buf.GetString() << "\n</tool_call>\n";
            }
            oss << "<|im_end|>\n";
            anyMessage = true;
            continue;
        }

        if (role == "system") {
            oss << "<|im_start|>system\n" << content;
            if (!toolsAttached && !toolsInstruction.empty()) {
                oss << toolsInstruction;
                toolsAttached = true;
            }
            oss << "<|im_end|>\n";
            anyMessage = true;
            continue;
        }

        // user / 其它未知角色统一按 user 处理，避免把模型没见过的角色名喂进去。
        const std::string safeRole = (role == "assistant") ? "assistant" : "user";
        oss << "<|im_start|>" << safeRole << "\n" << content << "<|im_end|>\n";
        anyMessage = true;
    }

    if (!anyMessage) {
        return "";
    }
    // generation prompt：告诉模型「轮到你说话了」，缺这一句是 0 token 输出的头号原因。
    oss << "<|im_start|>assistant\n";
    return oss.str();
}

// ═══ applyStructuredChatTemplateCore ═══
std::string applyStructuredChatTemplateCore(Llm* llm,
                                        const std::string& messagesJson,
                                        const std::string& toolsJson,
                                        std::string* errorOut = nullptr) {
    auto fail = [&errorOut](const char* code, const std::string& detail) -> std::string {
        LOGE("applyStructuredChatTemplateCore failed: %s | %s", code, detail.c_str());
        if (errorOut != nullptr) {
            *errorOut = std::string(code) + "|" + detail;
        }
        return "";
    };

    if (llm == nullptr) {
        return fail("E_MNN_NO_MESSAGES", "llm handle is null");
    }
    if (messagesJson.empty()) {
        return fail("E_MNN_NO_MESSAGES", "messages json is empty");
    }

    // Parse messages JSON array
    rapidjson::Document messagesDoc;
    messagesDoc.Parse(messagesJson.c_str());
    if (messagesDoc.HasParseError() || !messagesDoc.IsArray()) {
        return fail("E_MNN_BAD_MESSAGES", "messages json is not a valid JSON array");
    }
    if (messagesDoc.Empty()) {
        return fail("E_MNN_NO_VALID_MESSAGE", "messages array is empty");
    }

    // Validate tools JSON (if present) — invalid tools are silently ignored.
    // Empty array "[]" reliably clears stale tools from previous calls
    // (ujson merge replaces arrays, see ujson.hpp:292-301).
    std::string toolsField = "[]";
    if (!toolsJson.empty()) {
        rapidjson::Document toolsDoc;
        toolsDoc.Parse(toolsJson.c_str());
        if (!toolsDoc.HasParseError() && toolsDoc.IsArray()) {
            toolsField = toolsJson;
        } else {
            LOGE("applyStructuredChatTemplateCore: invalid tools json, ignoring");
        }
    }

    // Inject jinja context via set_config (same pattern as MNNLlmSession.setThinkingMode).
    // The vendored jinja engine (LLM_USE_JINJA) picks up "tools" from the context when
    // apply_chat_template is called.
    // 🛡️ B-5 防御：set_config 注入 jinja tools 上下文必须在 try 内保护。
    // 旧代码未加保护：在部分 MNN 构建（未定义 LLM_USE_JINJA，或 chat_template_context_
    // 管线缺失）下该调用会抛异常，异常穿透 applyStructuredChatTemplateCore 逃逸到
    // nativeGenerateStreamStructured 的 catch → E_MNN_STREAM_THROW → JNI_FALSE，
    // 即上层看到的「MNN 推理未产生任何输出（ok=false）」。这是工具调用路径独有的崩溃点，
    // 非工具路径根本不调 set_config，因此只有开启工具调用才触发。
    // 即便注入失败也绝不阻断：主路径 apply_chat_template 仍会渲染 prompt；
    // 若主路径因缺 tools 渲染为空，renderChatMlFallback 会把工具描述写进 system，
    // 模型依旧能看到工具，而不是整条链路崩成 ok=false。
    std::string configStr = "{\"jinja\":{\"context\":{\"tools\":" + toolsField + "}}}";
    try {
        llm->set_config(configStr);
    } catch (const std::exception& e) {
        LOGE("applyStructuredChatTemplateCore: set_config(tools) threw (ignored, "
             "falling back to ChatML tool injection): %s", e.what());
    } catch (...) {
        LOGE("applyStructuredChatTemplateCore: set_config(tools) threw unknown "
             "(ignored, falling back to ChatML tool injection)");
    }

    // Build ChatMessages from messages JSON.
    // Per MNN docs (llm.md:636): messages with extra fields (tool_calls, reasoning_content,
    // tool_call_id) or tool role use "json" as first, full JSON object string as second.
    ChatMessages chatMessages;
    for (const auto& msg : messagesDoc.GetArray()) {
        if (!msg.IsObject() || !msg.HasMember("role") || !msg["role"].IsString()) continue;
        std::string role = msg["role"].GetString();

        bool hasExtras = msg.HasMember("tool_calls") || msg.HasMember("reasoning_content") ||
                         msg.HasMember("tool_call_id") || role == "tool";

        if (hasExtras) {
            rapidjson::StringBuffer msgBuffer;
            rapidjson::Writer<rapidjson::StringBuffer> msgWriter(msgBuffer);
            msg.Accept(msgWriter);
            chatMessages.push_back({"json", std::string(msgBuffer.GetString())});
        } else {
            std::string content = (msg.HasMember("content") && msg["content"].IsString())
                ? msg["content"].GetString() : "";
            chatMessages.push_back({role, content});
        }
    }

    if (chatMessages.empty()) {
        return fail("E_MNN_NO_VALID_MESSAGE",
                    "no usable message after filtering (all entries missing role)");
    }

    // Apply chat template via public API (picks up tools from jinja context).
    // 主路径：模型自带 chat_template + vendored jinja。异常不再向上抛，转回退。
    std::string prompt;
    std::string templateError;
    try {
        prompt = llm->apply_chat_template(chatMessages);
    } catch (const std::exception& e) {
        templateError = e.what();
        LOGE("applyStructuredChatTemplateCore: apply_chat_template threw: %s", e.what());
        prompt.clear();
    } catch (...) {
        templateError = "unknown exception";
        LOGE("applyStructuredChatTemplateCore: apply_chat_template threw unknown exception");
        prompt.clear();
    }

    // 回退路径：主路径没产出可用 prompt 时，用内置 ChatML 兜底，避免直接 0 输出。
    if (prompt.empty()) {
        LOGE("applyStructuredChatTemplateCore: primary template produced empty prompt, "
             "falling back to built-in ChatML");
        prompt = renderChatMlFallback(messagesDoc, toolsField);
        if (!prompt.empty()) {
            LOGI("applyStructuredChatTemplateCore: ChatML fallback produced %zu chars", prompt.size());
        }
    }

    if (prompt.empty()) {
        if (!templateError.empty()) {
            return fail("E_MNN_TEMPLATE_THROW",
                        "chat_template threw: " + templateError +
                        "; built-in ChatML fallback also produced nothing");
        }
        return fail("E_MNN_TEMPLATE_EMPTY",
                    "model chat_template rendered empty and built-in ChatML fallback "
                    "also produced nothing");
    }

    return prompt;
}
