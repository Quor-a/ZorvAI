// =============================================================================
// L4 · 思考段分流状态机 单元测试
// =============================================================================
// 【为什么这些测试必须存在】
//
// 本类要替掉 Kotlin 侧那三份文本实现，而那三份的每一行注释背后都是一次真机事故。
// 如果这里不把那些场景固化成用例，"重构后行为不一致"就只能等用户真机上发现 ——
// 而这个类恰恰是**最容易写出"看起来对"的实现**的地方：
//   暂缓提交写错一个字节，表现为 UI 上闪一下 `<thi`，很难复现、很难定位。
//
// 【最重要的一条：任意切分不变性】
//
// 本类的输入是**任意字节切片**（token 边界不可控）。所以正确性不能只测
// "整串喂进去对不对"，必须测 **同一串按任意大小切碎后喂，结果完全相同**。
// `chunkInvarianceCase()` 把每个用例都按 1..N 字节逐个切片跑一遍 ——
// 这是本文件里价值最高的一组断言。
//
// 编译运行（宿主）：
//   zig c++ -std=c++17 -I llm/core/include llm/core/src/think_splitter.cpp \
//           llm/core/test/think_splitter_test.cpp -o /tmp/t && /tmp/t
// =============================================================================
#include "quro/think_splitter.h"

#include <algorithm>   // std::min（runChunked 用；不依赖其他头间接带入）
#include <chrono>
#include <cstdio>
#include <string>
#include <vector>

namespace {

int g_failures = 0;
int g_checks = 0;

void check(bool ok, const std::string& what) {
    ++g_checks;
    if (!ok) {
        ++g_failures;
        std::printf("  [FAIL] %s\n", what.c_str());
    }
}

void checkEq(const std::string& got, const std::string& want, const std::string& what) {
    ++g_checks;
    if (got != want) {
        ++g_failures;
        std::printf("  [FAIL] %s\n         期望=[%s]\n         实际=[%s]\n",
                    what.c_str(), want.c_str(), got.c_str());
    }
}

/// 一次性喂入整串。
struct Result {
    std::string thinking;
    std::string visible;
};

Result runOnce(const std::string& input, quro::llm::ThinkSplitter::Config cfg = {}) {
    quro::llm::ThinkSplitter sp(cfg);
    const auto s = sp.feed(input);
    const auto t = sp.finish();
    return Result{s.thinking + t.thinking, s.visible + t.visible};
}

/// 按固定大小切片喂入 —— 模拟 token 边界。
Result runChunked(const std::string& input, size_t chunk,
                  quro::llm::ThinkSplitter::Config cfg = {}) {
    quro::llm::ThinkSplitter sp(cfg);
    std::string th, vi;
    if (chunk == 0) chunk = 1;
    for (size_t i = 0; i < input.size(); i += chunk) {
        const size_t n = std::min(chunk, input.size() - i);
        const auto s = sp.feed(input.data() + i, n);
        th += s.thinking;
        vi += s.visible;
    }
    const auto t = sp.finish();
    return Result{th + t.thinking, vi + t.visible};
}

/// 核心不变性：任意切分必须与整串一致。
void chunkInvarianceCase(const std::string& input, quro::llm::ThinkSplitter::Config cfg,
                         const std::string& label) {
    const Result whole = runOnce(input, cfg);
    for (size_t c = 1; c <= input.size() && c <= 9; ++c) {
        const Result part = runChunked(input, c, cfg);
        checkEq(part.thinking, whole.thinking,
                label + "：切分粒度 " + std::to_string(c) + " 的 thinking 与整串不一致");
        checkEq(part.visible, whole.visible,
                label + "：切分粒度 " + std::to_string(c) + " 的 visible 与整串不一致");
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 用例
// ─────────────────────────────────────────────────────────────────────────────

/// 1. 最基本：思考段与正文分流。
void caseBasic() {
    std::printf("· 基本分流\n");
    const auto r = runOnce("<think>推理内容</think>最终答案");
    checkEq(r.thinking, "推理内容", "思考段内容");
    checkEq(r.visible, "最终答案", "正文内容（标签必须被吃掉，不能出现在任何一段）");
}

/// 2. 只有正文，没有思考段。
void caseNoThinking() {
    std::printf("· 无思考段\n");
    const auto r = runOnce("就是一段普通回复");
    checkEq(r.thinking, "", "不应有思考内容");
    checkEq(r.visible, "就是一段普通回复", "正文原样");
}

/// 3. 全角标签（真机事故：模型吐 ＜think＞，半角匹配失效 → 思考原文实时上屏）。
void caseFullWidth() {
    std::printf("· 全角标签 ＜think＞\n");
    const auto r = runOnce("＜think＞推理＜/think＞答案");
    checkEq(r.thinking, "推理", "全角标签也要能分流");
    checkEq(r.visible, "答案", "全角闭标签后的正文");
}

/// 4. 未闭合标签：尾部必须暂缓，绝不能提前吐出可能构成标签的字节。
void caseHoldBack() {
    std::printf("· 未闭合标签暂缓提交\n");
    quro::llm::ThinkSplitter sp;

    // 只喂 `<thi`（`<think` 的真前缀）—— 什么都还不能确定。
    const auto a = sp.feed("<thi");
    checkEq(a.visible, "", "`<thi` 是 `<think` 的前缀，绝不能当正文吐出");
    checkEq(a.thinking, "", "也不该进思考段");
    check(a.empty(), "`<thi` 阶段应完全无输出");

    // 补全后立刻按思考段处理。
    const auto b = sp.feed("nk>ab");
    checkEq(b.thinking, "ab", "补全成 <think> 后应进入思考段");
    checkEq(b.visible, "", "不应有正文");

    const auto c = sp.finish();
    checkEq(c.thinking, "", "未闭合的思考段在 finish 后无剩余");
}

/// 5. 标签变体：`<think >` / `</think >`（模型会多打空格）。
void caseTagVariant() {
    std::printf("· 标签变体（含空格）\n");
    const auto r = runOnce("<think >推理</think >答案");
    checkEq(r.thinking, "推理", "带空格的开标签要识别");
    checkEq(r.visible, "答案", "带空格的闭标签要识别");
}

/// 6. 长前缀优先：`<thinking>` 不能被 `<think` 抢走（否则 "ing" 被当标签内容吞掉）。
void caseLongestPrefixWins() {
    std::printf("· 长前缀优先（<thinking 不被 <think 抢走）\n");
    const auto r = runOnce("<thinking>推理</thinking>答案");
    checkEq(r.thinking, "推理", "<thinking> 应整段识别为标签");
    checkEq(r.visible, "答案", "正文正确");
}

/// 7. 正文里的 `<think about this>` 不能被误判成开标签（旧实现的宽松匹配会吞掉后续正文）。
void caseNoFalsePositive() {
    std::printf("· 正文里的伪标签不误判\n");
    const auto r = runOnce("让我们 <think about this> 一下");
    checkEq(r.thinking, "", "不应误入思考段");
    checkEq(r.visible, "让我们 <think about this> 一下", "伪标签应原样保留在正文");
}

/// 8. 思考段内的 <tool_call> 必须被保留并提升到正文通道 —— 这是当前真实的漏网场景。
void caseToolCallInsideThinking() {
    std::printf("· 思考段内的 <tool_call>（当前漏网场景）\n");
    const auto r = runOnce(
        "<think>想一下<tool_call>{\"name\":\"search\"}</tool_call>想完了</think>好");
    checkEq(r.thinking, "想一下想完了", "思考段内容（不含工具调用块）");
    checkEq(r.visible, "<tool_call>{\"name\":\"search\"}</tool_call>好",
            "工具调用块必须**连标签一起**出现在正文通道，供工具解析器使用");
    check(r.visible.find("<tool_call>") == 0, "工具调用块的开标签不能被吞掉");
}

/// 8b. 正文段内的 <tool_call> 必须**逐字节原样**保留在 visible。
///
/// 【这条是补写的，起因是一个真 bug —— 记在这里，因为它说明了"断言全绿"多不可靠】
///
/// 性能优化时把 tool_call 的边界标记并进了统一存储 markers_，
/// 然后 rebuildExpected 里用 `open` 布尔值一刀切筛候选，
/// 于是 **Visible 段也把 `<tool_call>` 当成了候选边界标记**。
/// 而 applyMatch 在 Visible 段没有对应动作 → 标签被**静默吞掉**。
///
/// 表现：调用方拿到的工具调用块缺开标签，工具解析器直接解析失败。
/// 后果：1 MiB 用例里 visible 少了 82984 字节 = 7544 个单元 × `<tool_call>` 的 11 字节。
///
/// **而当时全部 200 条断言仍然全绿** —— 因为原有断言只覆盖"思考段内"的工具调用
/// （caseToolCallInsideThinking），"正文段内"这条路径根本没人守。
/// 最后是靠 casePerf 打印的 visible 字节数与上一次不一致才发现的。
///
/// 教训：一个"看起来更统一"的重构，会把某段独有的语义悄悄放宽。
/// 必须为**每个段的每种标记组合**都留一条断言。
void caseToolCallInVisible() {
    std::puts("· 正文段内的 <tool_call>（必须原样保留）");
    {
        // 正文里的工具调用块必须逐字节出现在 visible，且不能进 thinking。
        const auto r = runOnce("答案：<tool_call>{\"name\":\"search\"}</tool_call>完毕");
        checkEq(r.thinking, "", "正文段的工具调用不应产生思考内容");
        checkEq(r.visible, "答案：<tool_call>{\"name\":\"search\"}</tool_call>完毕",
                "正文段内的工具调用块必须**逐字节**原样保留，标签一个都不能少");
    }
    {
        // 只有开标签、没有闭标签（模型截断）：开标签也必须照样留着。
        const auto r = runOnce("前缀<tool_call>{\"name\":\"x\"}");
        checkEq(r.visible, "前缀<tool_call>{\"name\":\"x\"}",
                "残缺的工具调用也必须原样保留（解析器要靠它给出可读的失败原因）");
    }
    {
        // 与思考段内的形态同时出现：两处都要完整。
        const auto r = runOnce(
            "<think>想<tool_call>{\"a\":1}</tool_call></think>"
            "答<tool_call>{\"b\":2}</tool_call>");
        checkEq(r.thinking, "想", "思考段内容");
        checkEq(r.visible,
                "<tool_call>{\"a\":1}</tool_call>答<tool_call>{\"b\":2}</tool_call>",
                "思考段内与正文段内的工具调用**都要**完整出现在 visible");
    }
    {
        // 分隔符保护：`<tool_callx>` 不是 `<tool_call>`，必须原样留在正文。
        const auto r = runOnce("提到 <tool_calls> 这个词");
        checkEq(r.visible, "提到 <tool_calls> 这个词",
                "长前缀必须优先：<tool_call> 不能吃掉 <tool_calls> 的开头");
    }
}

/// 9. emitThinking=false：思考不累积，但工具调用**仍不能丢**。
void caseDropThinkingKeepsToolCall() {
    std::printf("· 丢弃思考但保留工具调用\n");
    quro::llm::ThinkSplitter::Config cfg;
    cfg.emitThinking = false;
    const auto r = runOnce("<think>推理<tool_call>{\"name\":\"x\"}</tool_call></think>答案", cfg);
    checkEq(r.thinking, "", "思考内容应被丢弃");
    checkEq(r.visible, "<tool_call>{\"name\":\"x\"}</tool_call>答案",
            "工具调用不能因为丢弃思考而丢失");
}

/// 10. finish() 不丢字节：未闭合思考段 + 不完整的标签前缀都要吐出来。
void caseFinishNoLoss() {
    std::printf("· finish 不丢字节\n");
    {
        quro::llm::ThinkSplitter sp;
        const auto a = sp.feed("<think>还没想完");
        const auto b = sp.finish();
        checkEq(a.thinking + b.thinking, "还没想完", "未闭合思考段的内容要在 finish 时吐出");
    }
    {
        quro::llm::ThinkSplitter sp;
        const auto a = sp.feed("正文<thi");   // 正文里一个不完整的标签前缀
        const auto b = sp.finish();
        checkEq(a.visible + b.visible, "正文<thi",
                "final 时已不可能成为标签，必须当正文吐出，绝不丢字节");
    }
}

/// 11. UTF-8：多字节内容在任意切分下都不能被破坏。
void caseUtf8() {
    std::printf("· UTF-8 多字节\n");
    const std::string emoji = "🎉";
    const auto r = runOnce("<think>中文推理" + emoji + "</think>正文" + emoji);
    checkEq(r.thinking, "中文推理" + emoji, "多字节思考内容");
    checkEq(r.visible, "正文" + emoji, "多字节正文内容");
}

/// 12. `<think` 后跟很长一段没有 `>` 的文本：不能无限缓冲，最终当正文吐出。
void caseLongFillerBounded() {
    std::printf("· 超长 non-terminator 不无限缓冲\n");
    quro::llm::ThinkSplitter sp;
    std::string tail(200, 'x');
    const auto a = sp.feed("<think" + tail);
    const auto b = sp.finish();
    checkEq(a.visible + b.visible, "<think" + tail,
            "`<think` 后跟 200 个非 terminator 字符，整体应作为正文吐出");
    checkEq(a.thinking + b.thinking, "", "不应误入思考段");
}

/// 13. 连续多轮思考段（有些模型会反复开闭）。
void caseRepeated() {
    std::printf("· 多轮开闭\n");
    const auto r = runOnce("<think>一</think>甲<think>二</think>乙");
    checkEq(r.thinking, "一二", "两段思考内容按顺序拼接");
    checkEq(r.visible, "甲乙", "两段正文按顺序拼接");
}

/// 14. 复位：换会话时必须清干净。
void caseReset() {
    std::printf("· reset\n");
    quro::llm::ThinkSplitter sp;
    sp.feed("<think>残留");
    check(sp.inThinking(), "此时应在思考段内");
    sp.reset();
    check(!sp.inThinking(), "reset 后应回到正文段");
    const auto r = sp.feed("新会话");
    checkEq(r.visible, "新会话", "reset 后新内容应进正文");
    checkEq(r.thinking, "", "reset 后不应带出上一会话的思考残留");
}

/// 16. 吞吐测量。
///
/// 本组**不设通过阈值** —— 门限值在别的机器上必然抖动，写死只会变成假失败。
/// 这里只打印数字，作用有两个：
///   ① 让「深度优化」这件事有可对比的量化证据，而不是嘴上说优化了；
///   ② 输入刻意选**最坏形态**：纯中文 + 大量全角标点。
///      全角标点（，。）的 UTF-8 首字节是 0xEF，与全角标签 ＜think 的首字节相同 ——
///      于是每个全角标点都会让「寻找标记起点」的扫描停一次并进入候选比较。
///      这是本状态机在真实中文输出下最慢的形态，比英文正文慢得多。
void casePerf() {
    std::puts("· 吞吐测量（中文 + 全角标点 = 最坏形态）");
    const size_t kTarget = 1u << 20;   // 1 MiB

    // ── 场景 A：纯正文（无任何标签），走扫描快路径 ──────────────────────
    std::string body;
    body.reserve(kTarget + 128);
    while (body.size() < kTarget) {
        body += "这是一段普通的中文正文，包含全角标点，也包含半角标点, 和英文单词 test。";
    }
    {
        quro::llm::ThinkSplitter sp;
        size_t outBytes = 0;
        const auto t0 = std::chrono::steady_clock::now();
        for (size_t i = 0; i < body.size(); i += 4) {
            const size_t n = std::min<size_t>(4, body.size() - i);
            outBytes += sp.feed(body.data() + i, n).visible.size();
        }
        outBytes += sp.finish().visible.size();
        const auto t1 = std::chrono::steady_clock::now();
        const double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
        const double mbps = (body.size() / 1048576.0) / (ms / 1000.0);
        std::printf("  A 纯正文 %8zu B / 4B 切片 : %8.2f ms  %8.1f MB/s  输出 %zu B\n",
                    body.size(), ms, mbps, outBytes);
        checkEq(std::to_string(outBytes), std::to_string(body.size()),
                "纯正文必须逐字节无损输出");
        // 纯正文里没有标签，所以还要顺带守住"没有任何字节被吞"。
        check(outBytes == body.size(), "纯正文路径不允许丢任何一个字节");
    }

    // ── 场景 B：含半角/全角思考标签 + 思考段内工具调用，走状态切换路径 ──
    // 用 C++17 原始字符串字面量，避免 JSON 里的引号需要转义。
    std::string tagged;
    tagged.reserve(kTarget + 256);
    while (tagged.size() < kTarget) {
        tagged += "<think>推理一段中文。＜think＞全角形态也要认。</think>";
        tagged += R"(<tool_call>{"name":"x"}</tool_call>)";
        tagged += "正文一段中文，带标点。";
    }
    {
        quro::llm::ThinkSplitter sp;
        size_t th = 0, vi = 0;
        const auto t0 = std::chrono::steady_clock::now();
        for (size_t i = 0; i < tagged.size(); i += 4) {
            const size_t n = std::min<size_t>(4, tagged.size() - i);
            const auto s = sp.feed(tagged.data() + i, n);
            th += s.thinking.size();
            vi += s.visible.size();
        }
        const auto s = sp.finish();
        th += s.thinking.size();
        vi += s.visible.size();
        const auto t1 = std::chrono::steady_clock::now();
        const double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
        const double mbps = (tagged.size() / 1048576.0) / (ms / 1000.0);
        std::printf("  B 含标签 %8zu B / 4B 切片 : %8.2f ms  %8.1f MB/s  思考 %zu B / 正文 %zu B\n",
                    tagged.size(), ms, mbps, th, vi);
        check(th + vi > 0, "含标签场景必须有输出");

        // ── 精确不变量：输出总字节 = 输入字节 - 被吃掉的思考标签 ──────────
        //
        // 这条断言**不依赖**我对 thinking/visible 具体怎么划分的推导，
        // 只依赖唯一一条铁律：**标签被吃掉、其余字节一个不丢**。
        //
        // 它正是当初抓出那个真 bug 的判据（Visible 段误吞 <tool_call> 导致
        // visible 少 82984 字节）。只写 `vi > th / 2` 这种宽松判据会漏掉它。
        //
        // 注意 ＜think＞（全角）**不进**被吃掉的标签：它出现在思考段内部，
        // 而思考段只认闭标记，所以它作为普通文本归属思考内容，字节保留。
        const std::string unitA = "<think>推理一段中文。＜think＞全角形态也要认。</think>";
        const std::string unitB = R"(<tool_call>{"name":"x"}</tool_call>)";
        const std::string unitC = "正文一段中文，带标点。";
        const size_t unitLen = unitA.size() + unitB.size() + unitC.size();
        const size_t unitCount = tagged.size() / unitLen;
        const size_t eatenPerUnit = std::string("<think>").size() + std::string("</think>").size();
        const size_t expectTotal = tagged.size() - unitCount * eatenPerUnit;

        checkEq(std::to_string(th + vi), std::to_string(expectTotal),
                "输出总字节必须 = 输入 - 被吃掉的思考标签（绝不丢字节）");

        // ── 精确分流断言：thinking 与 visible 各自应当分到多少 ──────────
        // thinking 每单元 = 「推理一段中文。」+「＜think＞」+「全角形态也要认。」
        // visible  每单元 = unitB（含标签，逐字节）+ unitC
        const std::string thA = "推理一段中文。";
        const std::string thB = "＜think＞";
        const std::string thC = "全角形态也要认。";
        checkEq(std::to_string(th), std::to_string(unitCount * (thA.size() + thB.size() + thC.size())),
                "思考通道字节数必须精确匹配（多一个字节说明标签没吃干净）");
        checkEq(std::to_string(vi), std::to_string(unitCount * (unitB.size() + unitC.size())),
                "正文通道字节数必须精确匹配（少一个字节说明有标签被误吞）");
    }
}

/// 15. 分片不变性（本文件价值最高的一组断言，覆盖上面所有输入形态）。
void caseChunkInvariance() {
    std::printf("· 任意切分不变性（逐字节 … 9 字节）\n");
    chunkInvarianceCase("<think>推理内容</think>最终答案", {}, "基本");
    chunkInvarianceCase("＜think＞推理＜/think＞答案", {}, "全角");
    chunkInvarianceCase("<thinking>推理</thinking>答案", {}, "长前缀");
    chunkInvarianceCase("<think>想一下<tool_call>{\"name\":\"search\"}</tool_call>完</think>好",
                        {}, "思考段内工具调用");
    chunkInvarianceCase("让我们 <think about this> 一下", {}, "伪标签");
    chunkInvarianceCase("<think>中文推理🎉</think>正文🎉", {}, "UTF-8");
    chunkInvarianceCase("<think>一</think>甲<think>二</think>乙", {}, "多轮开闭");
    chunkInvarianceCase("<think >a</think >b", {}, "带空格变体");
    {
        quro::llm::ThinkSplitter::Config cfg;
        cfg.emitThinking = false;
        chunkInvarianceCase("<think>推理<tool_call>{\"n\":1}</tool_call></think>答案", cfg,
                            "丢弃思考");
    }
}

}  // namespace

int main() {
    std::printf("=== think_splitter 单元测试 ===\n");

    caseBasic();
    caseNoThinking();
    caseFullWidth();
    caseHoldBack();
    caseTagVariant();
    caseLongestPrefixWins();
    caseNoFalsePositive();
    caseToolCallInsideThinking();
    caseToolCallInVisible();
    caseDropThinkingKeepsToolCall();
    caseFinishNoLoss();
    caseUtf8();
    caseLongFillerBounded();
    caseRepeated();
    caseReset();
    caseChunkInvariance();
    casePerf();

    std::printf("\n=== 断言 %d 条，失败 %d 条 ===\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
