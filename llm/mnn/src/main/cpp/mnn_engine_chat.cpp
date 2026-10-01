// =============================================================================
// L3/L4 · MnnEngine 实现（三）：聊天模板 / 结构化工具调用 / 上下文与配置导出
// =============================================================================
// 本 TU 的分工：
//   · 模型自带 chat template 的两条渲染路径（单条内容 / 多轮历史）
//   · 结构化（OpenAI messages+tools JSON）渲染 —— 工具轮入口
//   · 上下文预算（渲染 + 分词后的 token 数）
//   · dump_config / LlmContext → JSON 两个诊断出口
//
// 分层边界：本 TU **没有**一个 JNI 类型。旧实现把 `jobject jhistory` 的解析
// （parseChatHistory，用 FindClass/GetMethodID 逐项取 kotlin.Pair）写在同一个
// 文件里，于是"聊天模板渲染"这件事和"Kotlin 集合反射"绑死。
// 现在那部分在 mnn_jni.cpp，本 TU 只认 std::vector<std::pair<std::string,std::string>>。
// =============================================================================

#include "mnn_engine_impl.h"

#include <exception>
#include <string>
#include <utility>
#include <vector>

namespace quro {
namespace llm {

namespace {

/// 取 Session 而不是 MnnEngine::Impl —— Impl 是 MnnEngine 的**私有**嵌套类型，
/// 类外的自由函数一旦在签名里写出 MnnEngine::Impl 就会触发访问权限编译错误。
/// 这里只需要 session，直接收 Session* 既够用又不越界。
bool requireLl(const mnn_detail::Session* session, std::string* err) {
    if (session == nullptr || session->llm == nullptr) {
        if (err != nullptr) *err = "引擎尚未创建 MNN 实例。";
        return false;
    }
    return true;
}

}  // namespace

// ═════════════════════════════════════════════════════════════════════════
// 模型自带模板渲染
// ═════════════════════════════════════════════════════════════════════════

bool MnnEngine::applyChatTemplate(const std::string& userContent, std::string* out,
                                  std::string* err) {
    if (out == nullptr) {
        if (err) *err = "输出参数为空。";
        return false;
    }
    out->clear();
    if (!requireLl(&impl_->session, err)) return false;

    try {
        *out = impl_->session.llm->apply_chat_template(userContent);
        return true;
    } catch (const std::exception& e) {
        LOGE("Exception in applyChatTemplate: %s", e.what());
        if (err) *err = std::string("聊天模板渲染异常：") + e.what();
        return false;
    } catch (...) {
        LOGE("Unknown exception in applyChatTemplate");
        if (err) *err = "聊天模板渲染异常（未知错误）。";
        return false;
    }
}

bool MnnEngine::applyChatTemplateWithHistory(const ChatMessages& history, std::string* out,
                                             std::string* err) {
    if (out == nullptr) {
        if (err) *err = "输出参数为空。";
        return false;
    }
    out->clear();
    if (!requireLl(&impl_->session, err)) return false;

    try {
        *out = impl_->session.llm->apply_chat_template(history);
        return true;
    } catch (const std::exception& e) {
        LOGE("Exception in applyChatTemplateWithHistory: %s", e.what());
        if (err) *err = std::string("聊天模板渲染异常：") + e.what();
        return false;
    } catch (...) {
        LOGE("Unknown exception in applyChatTemplateWithHistory");
        if (err) *err = "聊天模板渲染异常（未知错误）。";
        return false;
    }
}

// ═════════════════════════════════════════════════════════════════════════
// 结构化（工具调用）渲染
// ═════════════════════════════════════════════════════════════════════════
// 真正干活的是 mnn_detail::applyStructuredChatTemplateCore（在 logic.inc 里，
// 由脚本从旧实现逐字抽取）。本方法只做两件事：转发 + 把错误码写进 lastError。
//
// 为什么错误要同时写 lastError：上层 Kotlin 侧读 nativeGetLastError 才能把
// `E_MNN_TEMPLATE_EMPTY|...` 翻译成人话显示给用户；只返回 false 的话，
// 用户看到的永远是那句毫无信息量的「MNN 推理未产生任何输出（ok=false）」。
bool MnnEngine::applyChatTemplateWithStructuredMessages(const std::string& messagesJson,
                                                        const std::string& toolsJson,
                                                        std::string* out, std::string* err) {
    if (out == nullptr) {
        if (err) *err = "输出参数为空。";
        return false;
    }
    out->clear();
    if (!requireLl(&impl_->session, err)) return false;

    try {
        std::string templateError;
        const std::string templated = mnn_detail::applyStructuredChatTemplateCore(
            impl_->session.llm, messagesJson, toolsJson, &templateError);
        if (templated.empty()) {
            const std::string reason =
                templateError.empty() ? std::string("E_MNN_TEMPLATE_EMPTY|rendered prompt is empty")
                                      : templateError;
            impl_->session.lastError = reason;
            if (err) *err = reason;
            return false;
        }
        *out = templated;
        return true;
    } catch (const std::exception& e) {
        LOGE("Exception in applyChatTemplateWithStructuredMessages: %s", e.what());
        if (err) *err = std::string("结构化模板渲染异常：") + e.what();
        return false;
    } catch (...) {
        LOGE("Unknown exception in applyChatTemplateWithStructuredMessages");
        if (err) *err = "结构化模板渲染异常（未知错误）。";
        return false;
    }
}

// ═════════════════════════════════════════════════════════════════════════
// 上下文预算：渲染 + 分词后的 token 数
// ═════════════════════════════════════════════════════════════════════════

int32_t MnnEngine::countTokensWithHistory(const ChatMessages& history, std::string* err) {
    if (!requireLl(&impl_->session, err)) return -1;
    try {
        // 必须"先渲染再分词"：直接对原始文本分词会漏掉模板注入的角色分隔符
        // （<|im_start|>assistant 一类），预算偏小 → 真机上表现为上下文被提前截断。
        const std::string templated = impl_->session.llm->apply_chat_template(history);
        return static_cast<int32_t>(impl_->session.llm->tokenizer_encode(templated).size());
    } catch (const std::exception& e) {
        LOGE("Exception in countTokensWithHistory: %s", e.what());
        if (err) *err = std::string("上下文预算计算异常：") + e.what();
        return -1;
    } catch (...) {
        LOGE("Unknown exception in countTokensWithHistory");
        if (err) *err = "上下文预算计算异常（未知错误）。";
        return -1;
    }
}

int32_t MnnEngine::countTokensWithStructuredMessages(const std::string& messagesJson,
                                                     const std::string& toolsJson,
                                                     std::string* err) {
    if (!requireLl(&impl_->session, err)) return -1;
    try {
        const std::string templated = mnn_detail::applyStructuredChatTemplateCore(
            impl_->session.llm, messagesJson, toolsJson, nullptr);
        if (templated.empty()) {
            // 与旧实现一致：渲染不出来就返回 0（而不是报错）——
            // 预算为 0 会让上层走"截断更狠"的分支，比整条链路失败要好。
            return 0;
        }
        return static_cast<int32_t>(impl_->session.llm->tokenizer_encode(templated).size());
    } catch (const std::exception& e) {
        LOGE("Exception in countTokensWithStructuredMessages: %s", e.what());
        if (err) *err = std::string("上下文预算计算异常：") + e.what();
        return -1;
    } catch (...) {
        LOGE("Unknown exception in countTokensWithStructuredMessages");
        if (err) *err = "上下文预算计算异常（未知错误）。";
        return -1;
    }
}

// ═════════════════════════════════════════════════════════════════════════
// 诊断出口
// ═════════════════════════════════════════════════════════════════════════

bool MnnEngine::dumpConfig(std::string* out, std::string* err) {
    if (out == nullptr) {
        if (err) *err = "输出参数为空。";
        return false;
    }
    out->clear();
    if (!requireLl(&impl_->session, err)) return false;
    try {
        *out = impl_->session.llm->dump_config();
        return true;
    } catch (const std::exception& e) {
        LOGE("Exception in dumpConfig: %s", e.what());
        if (err) *err = std::string("配置导出异常：") + e.what();
        return false;
    } catch (...) {
        LOGE("Unknown exception in dumpConfig");
        if (err) *err = "配置导出异常（未知错误）。";
        return false;
    }
}

bool MnnEngine::contextInfoJson(std::string* out, std::string* err) {
    if (out == nullptr) {
        if (err) *err = "输出参数为空。";
        return false;
    }
    out->clear();
    if (!requireLl(&impl_->session, err)) return false;
    try {
        const LlmContext* context = impl_->session.llm->getContext();
        if (context == nullptr) {
            if (err) *err = "MNN 未提供上下文（可能尚未加载）。";
            return false;
        }
        const std::string json = mnn_detail::contextToJson(context);
        if (json.empty()) {
            if (err) *err = "上下文序列化结果为空。";
            return false;
        }
        *out = json;
        return true;
    } catch (const std::exception& e) {
        LOGE("Exception in getContextInfo: %s", e.what());
        if (err) *err = std::string("上下文导出异常：") + e.what();
        return false;
    } catch (...) {
        LOGE("Unknown exception in getContextInfo");
        if (err) *err = "上下文导出异常（未知错误）。";
        return false;
    }
}

}  // namespace llm
}  // namespace quro
