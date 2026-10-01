// =============================================================================
// L4 · 思考段分流状态机 实现
// =============================================================================
// 设计要点与取舍，逐条对应 think_splitter.h 里列出的真机教训：
//
// 1. **只按字节比较，天然安全**：UTF-8 多字节序列里不含 ASCII 字节，
//    所有标记首字节要么是 '<'（ASCII），要么是多字节首字节（≥ 0x80）。
//    因此逐字节前缀匹配不可能把一个合法多字节字符从中间切开误判。
//    这也是本类**不碰 UTF-8 边界**的前提（边界由调用方既有 pendingUtf8 处理）。
//
// 2. **长前缀优先**：`<think` 是 `<thinking` 的前缀。若按短优先匹配，
//    `<thinking>` 会被 `<think` 命中，再扫 `>` 时把 "ing" 当成标签内容吞掉。
//    所以候选标记先按 prefix 长度**降序**排。
//
// 3. **terminator 之前只放行空白**（故意比旧实现严格）：
//    Kotlin 老实现是「遇到 '>' 即视为标签结束」，于是正文里的
//    `<think about this>` 也会被当成开标签、把后续正文全吞进思考段。
//    收紧后 `<think>` / `<think >` / `<think\n>` 照常识别，
//    `<think about this>` 不识别 —— 少一个误报源。
//
// 4. **暂缓提交（hold-back）只能缓、不能漏**：
//    尾部若可能构成某个期望标记的前缀，必须留着等下一次 feed。
//    这是最容易被"优化"掉、但一优化就会在 UI 上闪 `<thi` 的部分。
//    加了 maxTagLength 上限兜底：`<think` 后面跟一整篇没有 `>` 的文档时，
//    不会无限缓冲（超限即判定不是标签，按普通文本吐出）。
//
// 5. **思考段内的 <tool_call> 提升到 visible**（本类新增的显式建模）：
//    旧行为下这段内容被剥离器当思考丢掉、工具解析器在正文里又找不到，
//    两条路径谁都接不住（QuroLocalEngineNative.kt:1127 承认的漏网场景）。
//    现在用一个独立的段状态（ToolCallInThinking）接住它，
//    且**标签本身也一起交给 visible** —— 上层解析器需要完整的
//    `<tool_call>…</tool_call>` 文本才能解析。
//
// -----------------------------------------------------------------------------
// 【性能：v2 深度优化，改的只有「怎么找」，没动「怎么判」】
//
// v1 的判断逻辑是对的，但热路径上有三处纯浪费 —— 实测 1 MiB 纯中文正文
// （4 字节切片、故意走全角标点这个最坏形态）只有 5.9 MB/s，1 MiB 要 170 ms：
//
//   ① `matchAt` 每次调用都现场新建两个 vector、拷贝候选、再 stable_sort；
//   ② `nextPossibleStart` 同样每次新建 vector；
//   ③ `emit` 收 `const std::string&`，而调用点传的是 `pending_.substr(...)`
//      —— 每次 feed 都造一个临时 string（堆分配）。
//
//   流式生成的 feed 粒度很小（一个 token 或一个 flush 窗口，几字节到几十字节），
//   1 MiB / 4 字节 = 26 万次 feed，于是 ①②③ 加起来是**上百万次 malloc/free**。
//   那 170 ms 花在这里，跟状态机本身的判断无关。
//
// v2 的对应改法（全是"缓存/免分配"，**判断逻辑一个字节都没改**）：
//   ① 候选列表 → `expected_` 常驻缓存，只在**段切换时**重建（每次标签一次）；
//      重建时排一次序，此后 matchAt 直接按序读，热路径零分配零排序。
//   ② 首字节 → `firstByteTable_[256]` 查表；短 buffer 逐字节查表，
//      长 buffer 才交 `memchr`（走 SIMD）。
//   ③ `emit` 改收 `std::string_view`，子串用视图传递，这一路彻底零分配。
//
//   不变的约束：缓存存**下标**不存指针（见头文件里的说明 —— Session 里是值成员，
//   存指针会在 Session 拷贝后悬垂）；判断逻辑与 v1 逐字节等价，
//   由 `caseChunkInvariance()` 的任意切分不变性与全部断言共同把守。
// =============================================================================
#include "quro/think_splitter.h"

#include <algorithm>
#include <cstring>

namespace quro {
namespace llm {

namespace {

/// 标签里 prefix 与 terminator 之间允许出现的字符：**只允许空白**。见文件头第 3 条。
inline bool isTagFiller(unsigned char c) {
    return c == ' ' || c == '\t' || c == '\r' || c == '\n';
}

/// `nextPossibleStart` 的分界点：短于此值走逐字节查表，长于此值才交 memchr。
///
/// 为什么需要分界：流式场景下 `pending_` 通常只有几十字节（hold-back 上限
/// 就是 maxTagLength + 标记长度），此时 memchr 的函数调用与内部对齐检查
/// 比直接逐字节查表更贵；memchr 的优势要到几百字节以上才显现
/// （一次性喂入整段长文本的场景）。
constexpr size_t kMemchrThreshold = 64;

}  // namespace

std::vector<ThinkSplitter::Marker> ThinkSplitter::defaultMarkers() {
    // 默认集合**故意保守**：只列真实见过、且不会在正常正文里出现的形态。
    //
    // 没放进来但可配的（交给上层按模型聊天模板决定）：
    //   `<|think|>`（部分模板里是系统提示后缀，单独出现没有配对的闭标记）
    //   `<reasoning>` / `</reasoning>`（Qwen 系变体）
    //   无标签明文推理（模型直接吐推理文字，没有标签）——
    //     这种只能靠上层挑选合适的标记或干脆不开思考分流，本类无能为力，
    //     但**至少不会像旧实现那样假装处理了**。
    std::vector<Marker> markers;

    // 半角：最常见
    markers.push_back({"<think", ">", true});
    markers.push_back({"</think", ">", false});

    // 半角变体：部分模型吐 <thinking>
    markers.push_back({"<thinking", ">", true});
    markers.push_back({"</thinking", ">", false});

    // 全角：真机事故（QuroLocalEngineNative.kt:72 的注释就是这个）——
    // 少了这两条，模型吐 ＜think＞ 时检测不到开标签，
    // 思考原文会**实时上屏泄漏**。
    markers.push_back({"＜think", "＞", true});
    markers.push_back({"＜/think", "＞", false});

    return markers;
}

ThinkSplitter::ThinkSplitter() : ThinkSplitter(Config()) {}

ThinkSplitter::ThinkSplitter(Config config) : config_(std::move(config)) {
    rebuildMarkers();
}

void ThinkSplitter::rebuildMarkers() {
    // 空 = 使用默认集（见 Config::markers 的注释）。
    // 这一步放在这里而不是构造函数里：addMarkers/setConfig 也走同一条路，
    // 配置为空时同样要能正确展开成默认集。
    if (config_.markers.empty()) {
        config_.markers = defaultMarkers();
    }

    // 把 tool_call 的两个边界**合成为普通标记**，复用同一套匹配逻辑，
    // 不另写分支 —— 少一条分支就少一处会漂移的实现。
    // terminator 留空 = 定长标记（prefix 本身就是完整标签）。
    //
    // 用局部变量而不是成员：这两个定义只在构造期用一次，之后一律走
    // markers_[toolOpenIndex_] / markers_[toolCloseIndex_]。少两个成员就少两处
    // 可能与 markers_ 不一致的副本（那正是"改了配置没生效"这类 bug 的温床）。
    Marker toolOpen;
    toolOpen.prefix = config_.toolCallOpen;
    toolOpen.terminator.clear();
    toolOpen.open = true;

    Marker toolClose;
    toolClose.prefix = config_.toolCallClose;
    toolClose.terminator.clear();
    toolClose.open = false;

    // ── 建统一存储 markers_ = config_.markers + toolOpen + toolClose ────────
    // 空 prefix 也照收：① 让下标映射保持简单（不跳过就不会算错位置）；
    //                 ② 匹配阶段本来就会跳过空 prefix，收进来无害。
    markers_.clear();
    markers_.reserve(config_.markers.size() + 2);
    markers_ = config_.markers;

    toolOpenIndex_ = static_cast<uint16_t>(markers_.size());
    markers_.push_back(toolOpen);
    toolCloseIndex_ = static_cast<uint16_t>(markers_.size());
    markers_.push_back(toolClose);

    // ── 一次性排序：长前缀优先（文件头第 2 条）────────────────────────────
    //
    // 这里**不能**直接对 markers_ 本体排序 —— 那样 toolOpenIndex_/toolCloseIndex_
    // 就指错了对象（expected_ 缓存、以及 matchAt 判定 isToolCall 全靠这两个下标，
    // 指错会静默走错分支）。所以排「下标」再按序重建，并同步映射下标。
    const size_t total = markers_.size();
    std::vector<uint16_t> order(total);
    for (uint16_t i = 0; i < total; ++i) {
        order[i] = i;
    }
    std::stable_sort(order.begin(), order.end(), [this](uint16_t a, uint16_t b) {
        return markers_[a].prefix.size() > markers_[b].prefix.size();
    });

    std::vector<Marker> sorted;
    sorted.reserve(total);
    std::vector<uint16_t> newPos(total);
    for (uint16_t k = 0; k < total; ++k) {
        newPos[order[k]] = k;
        sorted.push_back(std::move(markers_[order[k]]));
    }
    markers_.swap(sorted);
    toolOpenIndex_ = newPos[toolOpenIndex_];
    toolCloseIndex_ = newPos[toolCloseIndex_];

    // 排序之后 markers_ 永久不变 —— 这是 expected_ 存下标能长期有效的前提。
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

void ThinkSplitter::reset() {
    segment_ = Segment::Visible;
    pending_.clear();
    expectedValid_ = false;   // 段变了，候选缓存必须重建
}

// ─────────────────────────────────────────────────────────────────────────────
// 候选缓存
// ─────────────────────────────────────────────────────────────────────────────

void ThinkSplitter::ensureExpected() const {
    if (!expectedValid_) {
        rebuildExpected();
        expectedValid_ = true;
    }
}

void ThinkSplitter::rebuildExpected() const {
    expected_.clear();
    std::memset(firstByteTable_, 0, sizeof(firstByteTable_));

    switch (segment_) {
        case Segment::Visible:
            // 正文段只需要认「进入思考段」的标记 —— 且**只认配置里的思考标记**。
            //
            // 【这里踩过一个真 bug，数字对不上才暴露出来】
            // toolOpen_/toolClose_ 合并进 markers_ 之后，如果这里只按 `open` 布尔值
            // 一刀切（`if (markers_[i].open)`），就会把 toolOpen_ 也算成候选 ——
            // 于是正文里的 `<tool_call>` 被匹配成"边界标记"，而 applyMatch 在
            // Visible 段没有对应动作，标签就**被静默吞掉**了。
            // 表现：调用方拿到的工具调用块缺开标签 → 工具解析器直接解析失败。
            // 用 1 MiB 重复单元测出来是 visible 少了 82984 字节
            // （正好 = 7544 个单元 × `<tool_call>` 的 11 字节）。
            //
            // 根因是：tool_call 边界的语义与 `open` 布尔值是**正交**的 ——
            // 「它是开还是闭」不能推出「哪一段该认它」。所以必须显式排除。
            for (uint16_t i = 0; i < markers_.size(); ++i) {
                if (i == toolOpenIndex_ || i == toolCloseIndex_) {
                    continue;
                }
                if (markers_[i].open) {
                    expected_.push_back(i);
                }
            }
            break;

        case Segment::Thinking:
            // 思考段内：认「退出思考段」的标记，
            // 外加（可选）「思考段里的工具调用块入口」。
            // 同样必须排除 toolClose_：否则思考段里出现 `</tool_call>`
            // 会被当成 `</think>` 用，把段切回正文。
            for (uint16_t i = 0; i < markers_.size(); ++i) {
                if (i == toolOpenIndex_ || i == toolCloseIndex_) {
                    continue;
                }
                if (!markers_[i].open) {
                    expected_.push_back(i);
                }
            }
            if (config_.keepToolCallsInThinking) {
                expected_.push_back(toolOpenIndex_);
            }
            break;

        case Segment::ToolCallInThinking:
            // 工具调用块内：只认出口。
            // 块内的 </think> 不算出口（先出 tool_call 块再说）——
            // 这是刻意的：半个工具调用交给上层只会让解析器更难判。
            expected_.push_back(toolCloseIndex_);
            break;
    }

    // 上面对 markers_ 是顺序遍历，而 markers_ 已按长度降序，
    // 所以候选基本已经有序；但 Thinking 段额外补进来的 toolOpenIndex_
    // 未必落在正确位置，因此这里**统一再排一次**。
    //
    // 只在本函数里排序（即只在段切换时），不在热路径上 ——
    // 这正是 v1「每次 feed 都排一次」被消掉的地方。
    std::stable_sort(expected_.begin(), expected_.end(), [this](uint16_t a, uint16_t b) {
        return markers_[a].prefix.size() > markers_[b].prefix.size();
    });

    // 首字节查表：热路径用它 O(1) 判定，取代「遍历候选比首字节」。
    for (uint16_t i : expected_) {
        const Marker& m = markers_[i];
        if (!m.prefix.empty()) {
            firstByteTable_[static_cast<unsigned char>(m.prefix[0])] = true;
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 主循环
// ─────────────────────────────────────────────────────────────────────────────

ThinkSplit ThinkSplitter::feed(const char* data, size_t len) {
    ThinkSplit out;
    if (data != nullptr && len > 0) {
        pending_.append(data, len);
    }
    drain(&out, /*final=*/false);
    return out;
}

ThinkSplit ThinkSplitter::finish() {
    ThinkSplit out;
    drain(&out, /*final=*/true);
    return out;
}

void ThinkSplitter::drain(ThinkSplit* out, bool final) {
    size_t pos = 0;
    const size_t n = pending_.size();

    while (pos < n) {
        const Match m = matchAt(pos, final);

        if (m.kind == Kind::Full) {
            applyMatch(m, out);
            pos += m.length;
            continue;
        }

        // 需要更多字节 —— 暂缓。**绝不**把可能构成标签的字节提前吐出（文件头第 4 条）。
        if (m.kind == Kind::Partial && !final) {
            break;
        }

        // 走到这里：此处没有标记，且它也不可能再变成标记（final 时的 Partial 同样归此）。
        // 快路径：把到下一个「可能的标记起点」之前的普通文本一次性吐出，
        // 避免逐字节 substr 在大段正文上退化成 O(n²)。
        const size_t next = nextPossibleStart(pos + 1);
        size_t take = (next == std::string::npos ? n : next) - pos;
        if (take == 0) {
            take = 1;   // 理论上不可达；留着做防御，绝不无限循环
        }
        // 用 string_view 交子串：v1 这里传的是 `pending_.substr(pos, take)`，
        // 每次都会造一个临时 string（堆分配）—— 流式下这是热路径，
        // 26 万次 feed 就是 26 万次多出来的 malloc/free。改视图后零分配。
        emit(out, std::string_view(pending_).substr(pos, take));
        pos += take;
    }

    if (pos > 0) {
        pending_.erase(0, pos);
    }

    // final 之后必须清空：残留只可能是被 maxTagLength 判定为非标签的内容，
    // 上面循环已处理完；这里做一次兜底，保证 finish() 之后不吞字节。
    if (final && !pending_.empty()) {
        emit(out, pending_);
        pending_.clear();
    }
}

size_t ThinkSplitter::nextPossibleStart(size_t pos) const {
    ensureExpected();

    const size_t n = pending_.size();
    if (pos >= n) {
        return std::string::npos;
    }

    if (n - pos < kMemchrThreshold) {
        // 短 buffer（流式场景的常态）：逐字节查表。见 kMemchrThreshold 的说明。
        const unsigned char* d = reinterpret_cast<const unsigned char*>(pending_.data());
        for (size_t i = pos; i < n; ++i) {
            if (firstByteTable_[d[i]]) {
                return i;
            }
        }
        return std::string::npos;
    }

    // 长 buffer：对每个首字节各做一次 memchr（走 SIMD），取最小位置。
    // 首字节种类极少（通常就是 '<' 与 0xEF 两个），所以最多两次扫描。
    size_t best = std::string::npos;
    const char* base = pending_.data();
    for (size_t c = 0; c < 256; ++c) {
        if (!firstByteTable_[c]) {
            continue;
        }
        const void* hit = std::memchr(base + pos, static_cast<int>(c), n - pos);
        if (hit == nullptr) {
            continue;
        }
        const size_t off = static_cast<size_t>(static_cast<const char*>(hit) - base);
        if (best == std::string::npos || off < best) {
            best = off;
        }
    }
    return best;
}

ThinkSplitter::Match ThinkSplitter::matchAt(size_t pos, bool final) const {
    ensureExpected();

    const size_t n = pending_.size();
    bool sawProperPrefix = false;

    // expected_ 已按 prefix 长度降序（在 rebuildExpected 里排好），
    // 所以这里直接顺序遍历即可 —— v1 每次都要现场建 vector + 排序，
    // 那才是热路径开销所在。
    for (uint16_t idx : expected_) {
        const Marker& m = markers_[idx];
        if (m.prefix.empty()) {
            continue;
        }

        const size_t plen = m.prefix.size();
        const size_t remain = n - pos;

        // prefix 还没收全：它可能是这个标记的合法前缀，先记下"需要更多字节"。
        if (remain < plen) {
            if (isProperPrefix(pending_.data() + pos, remain, m.prefix)) {
                sawProperPrefix = true;
            }
            continue;
        }

        if (pending_.compare(pos, plen, m.prefix) != 0) {
            continue;
        }

        // 定长标记（如 `<tool_call>`）：prefix 命中即完整命中。
        if (m.terminator.empty()) {
            Match r;
            r.kind = Kind::Full;
            r.length = plen;
            r.isOpen = m.open;
            r.isToolCall = (idx == toolOpenIndex_ || idx == toolCloseIndex_);
            return r;
        }

        // 带 terminator 的标记：从 prefix 之后扫空白，然后必须以 terminator 收尾。
        size_t i = pos + plen;
        size_t fillerCount = 0;
        while (i < n && isTagFiller(static_cast<unsigned char>(pending_[i]))) {
            ++i;
            ++fillerCount;
        }

        if (fillerCount > config_.maxTagLength) {
            continue;   // 空白太长，判定不是标签（不设上限会被 `<think` + 长文档拖住）
        }

        if (i >= n) {
            // 还没等到 terminator。非 final 时暂缓；final 时判定它不是标签。
            if (!final) {
                sawProperPrefix = true;
            }
            continue;
        }

        if (pending_.compare(i, m.terminator.size(), m.terminator) == 0) {
            Match r;
            r.kind = Kind::Full;
            r.length = (i + m.terminator.size()) - pos;
            r.isOpen = m.open;
            r.isToolCall = (idx == toolOpenIndex_ || idx == toolCloseIndex_);
            return r;
        }

        // terminator 可能只是被 token 边界切断了（全角 '＞' 有 3 字节，
        // 完全可能只收到前 1–2 字节）。这时必须暂缓，否则会在 UI 上闪过半个标签。
        const size_t termRemain = n - i;
        if (termRemain > 0 && termRemain < m.terminator.size() &&
            std::memcmp(pending_.data() + i, m.terminator.data(), termRemain) == 0) {
            if (!final) {
                sawProperPrefix = true;
            }
        }
        // 否则：prefix 后面不是空白也不是 terminator → 这里不是该标记。
    }

    if (sawProperPrefix && !final) {
        Match r;
        r.kind = Kind::Partial;
        return r;
    }
    return Match{};
}

void ThinkSplitter::applyMatch(const Match& m, ThinkSplit* out) {
    if (m.isToolCall) {
        // 工具调用边界：**标签本身也直接追加到 visible**，且必须**先切段再写**。
        //
        // 这里踩过一个坑：如果按"先 emit 再切段"写，开标签会在
        // segment_ 还是 Thinking 时被写进 out->thinking —— 于是上层拿到的
        // tool_call 块是**没有开头标签**的残块，工具解析器直接解析失败。
        // 同理闭标签若按"先切回 Thinking 再 emit"写，会掉进 thinking 通道。
        //
        // 结论：两个边界都必须**无条件写 visible**，让上层拿到连续的
        // `<tool_call>…</tool_call>` 完整文本。
        if (segment_ == Segment::Thinking) {
            segment_ = Segment::ToolCallInThinking;
            out->visible += markers_[toolOpenIndex_].prefix;
        } else if (segment_ == Segment::ToolCallInThinking) {
            segment_ = Segment::Thinking;
            out->visible += markers_[toolCloseIndex_].prefix;
        } else {
            // ── 防丢字节兜底 ──────────────────────────────────────────────
            // Visible 段本不该把 tool_call 边界列为候选（rebuildExpected 里已显式排除）。
            // 但"丢字节"是本类最不能犯的错：一旦候选集配错，静默吞标签会表现为
            // 工具调用块缺标签、解析器无声失败 —— 那种 bug 极难定位。
            // 所以这里原样吐出去，宁可多给一个标签字节，也绝不吞。
            // 真走到这里说明候选集配置错了，输出仍保持完整可解析。
            out->visible += m.isOpen ? markers_[toolOpenIndex_].prefix
                                     : markers_[toolCloseIndex_].prefix;
        }
        expectedValid_ = false;   // 段变了，候选缓存作废
        return;
    }

    segment_ = m.isOpen ? Segment::Thinking : Segment::Visible;
    expectedValid_ = false;   // 段变了，候选缓存作废

    // 思考标记本身**不进入任何一段的输出**：
    // 上层拿到的是干净的分流内容，不需要再自己剔标签
    // （这正是旧实现里 StreamingThinkStripper 那一百行在做的事）。
}

void ThinkSplitter::emit(ThinkSplit* out, std::string_view text) {
    if (text.empty()) {
        return;
    }
    switch (segment_) {
        case Segment::Visible:
        case Segment::ToolCallInThinking:
            // 工具调用块内的内容走 visible —— 工具解析器只认正文通道。
            out->visible.append(text.data(), text.size());
            break;
        case Segment::Thinking:
            if (config_.emitThinking) {
                out->thinking.append(text.data(), text.size());
            }
            // emitThinking=false 时直接丢弃思考字节（不累积），
            // 但**不影响** ToolCallInThinking（那条路径走 visible，不会丢工具调用）。
            break;
    }
}

bool ThinkSplitter::isProperPrefix(const char* data, size_t n, const std::string& full) {
    if (n == 0 || n >= full.size() || data == nullptr) {
        return false;
    }
    return std::memcmp(data, full.data(), n) == 0;
}

}  // namespace llm
}  // namespace quro
