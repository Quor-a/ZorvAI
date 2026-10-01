#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
N8：把模型**真实**的思考标签喂给分流器。

缺口的本质：
  ThinkSplitter 的默认标记集只覆盖 `<think>` / `<thinking>` / 全角三种形态，
  而 llama.cpp 的模板 detector **知道这个模型到底用什么** —— 答案就写在
  common_chat_params 里（thinking_start_tag / thinking_end_tags）：
      [THINK] / [/THINK]
      <|channel|>analysis<|message|> / <|end|>
      <|channel>thought / <channel|>
      <think> / </think>
      （autoparser 分支则是动态分析模板得出）
  两者从来没接上。于是用非标准标签的模型，思考原文会**直接上屏** ——
  分流器认不出开标签，整段推理被当正文。

改动：
  ① ThinkSplitter 支持运行期改标记集（setConfig / addMarkers，幂等）
  ② llama 引擎在渲染模板后把探测到的真实标签并进去
  ③ C++ 单测覆盖：默认集认不出 [THINK] → 注入后切开、超长标签、幂等、并集、复位
"""
import sys

ROOT = r"D:\Calw OS-project\QuroAI"

HDR = ROOT + r"\llm\core\include\quro\think_splitter.h"
SRC = ROOT + r"\llm\core\src\think_splitter.cpp"
LLAMA_CHAT = ROOT + r"\llm\llama\src\main\cpp\llama_engine_chat.cpp"
TEST = ROOT + r"\llm\core\test\think_splitter_test.cpp"

# ═══════════════════════ ① header ═══════════════════════
H_PUBLIC_OLD = """    /// 当前配置（只读）。
    const Config& config() const { return config_; }
"""
H_PUBLIC_NEW = """    // ───────────── 运行期改标记集（引擎探测到真实标签后调用）─────────────
    //
    /// 把若干额外标记**并进**当前标记集（幂等：同 prefix 只保留一份）。
    ///
    /// 用途：引擎从上游探测到模型**真实**使用的思考标签后补进来
    /// （llama.cpp 的 common_chat_params::thinking_start_tag / thinking_end_tags）。
    /// 默认集只覆盖 `<think>` / `<thinking>` / 全角三种形态，而真实模型可能用
    /// `[THINK]`、`<|channel|>analysis<|message|>`、`<|channel>thought` …… ——
    /// 不补进来的话，那些模型的思考原文会**直接上屏**（分流器认不出开标签，
    /// 整段推理被当成正文）。
    ///
    /// 语义是**并集而非替换**：探测可能为空（非 autoparser 路径），一个模型也
    /// 可能同时用多种形态；并集在两种情况下都不会退化。
    ///
    /// 幂等：引擎每轮渲染 prompt 都会调一次，重复调用**不会复位** ——
    /// 否则会把进行中的段打断。
    void addMarkers(const std::vector<Marker>& extra);

    /// 运行期整体替换配置（含复位与匹配缓存重建）。
    void setConfig(const Config& config);

    /// 当前配置（只读）。
    const Config& config() const { return config_; }
"""

H_PRIVATE_OLD = """    /// 确保 expected_ / firstByteTable_ 与 segment_ 一致（不一致就重建）。
    void ensureExpected() const;
"""
H_PRIVATE_NEW = """    /// 确保 expected_ / firstByteTable_ 与 segment_ 一致（不一致就重建）。
    void ensureExpected() const;

    /// 按 config_ 重建 markers_（含 tool_call 两个边界）与那两个下标。
    /// 构造函数 / setConfig / addMarkers 三处共用 —— 抽出来是为了让「改配置」
    /// 只有**一条**路径，不会出现"构造函数改了、运行期忘了改"的漂移。
    void rebuildMarkers();
"""

# ═══════════════════════ ② source ═══════════════════════
# 在构造函数体中间开一个函数边界，把余下部分变成 rebuildMarkers 的体。
# 缩进天然一致（都在类作用域内 4 空格），改动最小。
SRC_CTOR_OLD = """ThinkSplitter::ThinkSplitter(Config config) : config_(std::move(config)) {
    if (config_.markers.empty()) {
        config_.markers = defaultMarkers();
    }
"""
SRC_CTOR_NEW = """ThinkSplitter::ThinkSplitter(Config config) : config_(std::move(config)) {
    rebuildMarkers();
}

void ThinkSplitter::rebuildMarkers() {
    // 空 = 使用默认集（见 Config::markers 的注释）。
    // 这一步放在这里而不是构造函数里：addMarkers/setConfig 也走同一条路，
    // 配置为空时同样要能正确展开成默认集。
    if (config_.markers.empty()) {
        config_.markers = defaultMarkers();
    }
"""

SRC_TAIL_OLD = """    // 排序之后 markers_ 永久不变 —— 这是 expected_ 存下标能长期有效的前提。
    expectedValid_ = false;
}
"""
SRC_TAIL_NEW = """    // 排序之后 markers_ 永久不变 —— 这是 expected_ 存下标能长期有效的前提。
    expectedValid_ = false;
}

void ThinkSplitter::setConfig(const Config& config) {
    config_ = config;
    // 先复位再重建：旧的 pending_ / segment_ 是用**旧**标记集切出来的，
    // 换了规则还接着切，等于把两套规则的产物拼在一起 —— 那是最难查的一类错。
    reset();
    rebuildMarkers();
    expectedValid_ = false;
}

void ThinkSplitter::addMarkers(const std::vector<Marker>& extra) {
    if (extra.empty()) {
        return;
    }
    // config_.markers 为空时语义是"用默认集"。并集必须建在**实际生效**的那一份上，
    // 否则 addMarkers 会把默认集整个顶掉（默认形态全失效）。
    if (config_.markers.empty()) {
        config_.markers = defaultMarkers();
    }

    bool changed = false;
    for (const Marker& m : extra) {
        if (m.prefix.empty()) {
            continue;   // 空 prefix 匹配阶段本来就会跳过，收进来只是污染配置
        }
        bool duplicate = false;
        for (const Marker& existing : config_.markers) {
            if (existing.prefix == m.prefix) {
                duplicate = true;
                break;
            }
        }
        if (!duplicate) {
            config_.markers.push_back(m);
            changed = true;
        }
    }

    if (!changed) {
        // 🔴 幂等是**必须**的，不是优化：引擎每轮渲染 prompt 都会调一次
        // （applyChatTemplate 之后注入真实标签）。若这里无条件复位，
        // 同一轮内第二次调用就会把已经切了一半的思考段打断 —— 表现为
        // 思考内容碎片漏进正文，而且只在多轮对话里才复现。
        return;
    }

    reset();
    rebuildMarkers();
    expectedValid_ = false;
}
"""

# ═══════════════════════ ③ llama 引擎注入 ═══════════════════════
LLAMA_HELPER_ANCHOR = """bool LlamaEngine::setThinkingMode(bool enabled, bool* supported, std::string* err) {
"""
LLAMA_HELPER_NEW = """namespace {

/// 去掉首尾空白（上游部分标签常量带尾空格，如 `<|channel>thought `）。
std::string trimTag(const std::string& s) {
    size_t b = 0;
    size_t e = s.size();
    while (b < e && (s[b] == ' ' || s[b] == '\\t' || s[b] == '\\n' || s[b] == '\\r')) ++b;
    while (e > b && (s[e - 1] == ' ' || s[e - 1] == '\\t' || s[e - 1] == '\\n' || s[e - 1] == '\\r')) --e;
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
"""

# applyChatTemplate 的注入点（纯渲染路径，本次带上会话级副作用）
LLAMA_CALL_A_OLD = """        const common_chat_params params =
            common_chat_templates_apply(session->chatTemplates.get(), inputs);
        if (params.prompt.empty()) {
            failWith(session, err, "聊天模板渲染结果为空（模板与消息不匹配）。");
            return false;
        }
        *out = params.prompt;
        return true;"""
LLAMA_CALL_A_NEW = """        const common_chat_params params =
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
        return true;"""

LLAMA_CALL_B_OLD = """        const common_chat_params params =
            common_chat_templates_apply(session->chatTemplates.get(), inputs);
        if (params.prompt.empty()) {
            if (err) *err = "结构化聊天模板渲染结果为空。";
            return false;
        }"""
LLAMA_CALL_B_NEW = """        const common_chat_params params =
            common_chat_templates_apply(session->chatTemplates.get(), inputs);
        if (params.prompt.empty()) {
            if (err) *err = "结构化聊天模板渲染结果为空。";
            return false;
        }
        // 🧠 与 applyChatTemplate 同一处理：工具轮的模板同样可能用非标准思考标签。
        applyDetectedThinkingTags(session, params);"""

# ═══════════════════════ ④ C++ 单测 ═══════════════════════
TEST_CASE_ANCHOR = """}  // namespace

int main() {"""
TEST_CASE_NEW = """// ─────────────────────────────────────────────────────────────────────────────
// 运行期标记注入（N8：模型的真实标签）
// ─────────────────────────────────────────────────────────────────────────────
// 默认标记集只覆盖 <think>/<thinking>/全角三种形态，而真实模型可能用 [THINK]、
// <|channel|>analysis<|message|>、<|channel>thought 之类。llama.cpp 的模板
// detector 知道答案，引擎会把探测到的标签在运行期补进来 —— 那一步必须真的能
// 把非默认标签切开，否则用非标准标签的模型，思考原文会**直接上屏**。
void caseRuntimeMarkers() {
    // ① 未注入时默认集认不出 [THINK] —— 这就是"思考原文上屏"的成因本身
    {
        ThinkSplitter s;
        const ThinkSplit r = s.feed("[THINK]推理[/THINK]正文");
        checkEq(r.visible, "[THINK]推理[/THINK]正文", "默认集不该认 [THINK]（注入前）");
        checkEq(r.thinking, "", "默认集下 [THINK] 不是思考标记");
    }

    // ② 注入后必须切开
    {
        ThinkSplitter s;
        std::vector<ThinkSplitter::Marker> extra;
        extra.push_back({"[THINK]", "", true});
        extra.push_back({"[/THINK]", "", false});
        s.addMarkers(extra);
        const ThinkSplit r = s.feed("[THINK]推理[/THINK]正文");
        checkEq(r.thinking, "推理", "注入后 [THINK] 段应归思考");
        checkEq(r.visible, "正文", "注入后正文里不应再有标签");
    }

    // ③ 超长标签（28 字节，远超 maxTagLength=16）：走定长匹配，不受该上限约束
    {
        ThinkSplitter s;
        const std::string openTag = "<|channel|>analysis<|message|>";
        const std::string closeTag = "<|end|>";
        std::vector<ThinkSplitter::Marker> extra;
        extra.push_back({openTag, "", true});
        extra.push_back({closeTag, "", false});
        s.addMarkers(extra);
        const ThinkSplit r = s.feed(openTag + "思考内容" + closeTag + "答案");
        checkEq(r.thinking, "思考内容",
                "28 字节长标签应能切开（定长匹配不受 maxTagLength 限制）");
        checkEq(r.visible, "答案", "长标签之后的内容才是正文");
    }

    // ④ 幂等：引擎每轮渲染 prompt 都会调一次，重复注入**绝不能复位** ——
    //    否则会把进行中的段打断，思考碎片漏进正文，且只在多轮对话里复现。
    {
        ThinkSplitter s;
        std::vector<ThinkSplitter::Marker> extra;
        extra.push_back({"[THINK]", "", true});
        extra.push_back({"[/THINK]", "", false});
        s.addMarkers(extra);
        const ThinkSplit first = s.feed("[THINK]半段");
        s.addMarkers(extra);   // 同样的标记，什么都不该发生
        const ThinkSplit second = s.feed("继续[/THINK]正文");
        checkEq(first.thinking + second.thinking, "半段继续", "重复注入不得复位进行中的段");
        checkEq(second.visible, "正文", "重复注入后仍能正常切回正文");
    }

    // ⑤ 注入是**并集**：默认标签必须继续有效
    {
        ThinkSplitter s;
        std::vector<ThinkSplitter::Marker> extra;
        extra.push_back({"[THINK]", "", true});
        extra.push_back({"[/THINK]", "", false});
        s.addMarkers(extra);
        const ThinkSplit a = s.feed("<think>默认段</think>");
        const ThinkSplit b = s.feed("[THINK]新段[/THINK]尾");
        checkEq(a.thinking, "默认段", "注入新标记后默认标记必须继续有效");
        checkEq(b.thinking, "新段", "新标记也要有效");
        checkEq(b.visible, "尾", "尾段归正文");
    }

    // ⑥ setConfig 必须复位：未闭合的旧段不能带着旧标记集继续
    {
        ThinkSplitter s;
        s.feed("<think>未闭合");
        ThinkSplitter::Config cfg;
        cfg.markers.clear();   // 空 = 用默认集
        s.setConfig(cfg);
        const ThinkSplit r = s.feed("裸文本");
        checkEq(r.visible, "裸文本", "setConfig 必须复位（旧段不能带过来）");
    }

    // ⑦ 空 markers 的注入是无害 no-op（探测器什么都没给时的常态）
    {
        ThinkSplitter s;
        s.addMarkers({});
        const ThinkSplit r = s.feed("<think>x</think>y");
        checkEq(r.thinking, "x", "空注入不得影响默认集工作");
        checkEq(r.visible, "y", "空注入后正文照常");
    }
}

}  // namespace

int main() {"""

TEST_MAIN_OLD = """    caseReset();
    caseChunkInvariance();"""
TEST_MAIN_NEW = """    caseReset();
    caseRuntimeMarkers();
    caseChunkInvariance();"""

PATCHES = [
    (HDR, [(H_PUBLIC_OLD, H_PUBLIC_NEW), (H_PRIVATE_OLD, H_PRIVATE_NEW)]),
    (SRC, [(SRC_CTOR_OLD, SRC_CTOR_NEW), (SRC_TAIL_OLD, SRC_TAIL_NEW)]),
    (LLAMA_CHAT, [
        (LLAMA_HELPER_ANCHOR, LLAMA_HELPER_NEW),
        (LLAMA_CALL_A_OLD, LLAMA_CALL_A_NEW),
        (LLAMA_CALL_B_OLD, LLAMA_CALL_B_NEW),
    ]),
    (TEST, [(TEST_CASE_ANCHOR, TEST_CASE_NEW), (TEST_MAIN_OLD, TEST_MAIN_NEW)]),
]


def main():
    staged, failed = [], False
    for path, pairs in PATCHES:
        raw = open(path, "rb").read()
        crlf = b"\r\n" in raw
        s = raw.decode("utf-8").replace("\r\n", "\n")
        for old, new in pairs:
            n = s.count(old)
            if n != 1:
                print("FAIL %s: hits=%d (need 1)\n     -> %s"
                      % (path.split("\\")[-1], n, old.split("\n")[0][:70]))
                failed = True
                continue
            s = s.replace(old, new, 1)
        staged.append((path, s, crlf))
    if failed:
        print("\n整体放弃写入（防半装载）。")
        return 1
    for path, s, crlf in staged:
        out = s.replace("\n", "\r\n") if crlf else s
        open(path, "wb").write(out.encode("utf-8"))
        print("PATCHED %-32s (%s, %d bytes)"
              % (path.split("\\")[-1], "CRLF" if crlf else "LF", len(out.encode("utf-8"))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
