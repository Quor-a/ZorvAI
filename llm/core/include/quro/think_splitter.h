// =============================================================================
// L4 · 思考段 / 正文段 分流状态机 —— 把「思考构架」从 Kotlin 文本正则收回 native
// =============================================================================
// 【为什么需要这一层】
//
// 引擎契约 `engine_types.h` 里 `TokenChunk::isThinking` 的注释写着
// 「思考段（<think>）与正文分流」—— 但**两栈四个 chunk 发射点全部硬编码
// `isThinking = false`，全仓没有任何一处置 true**。契约声明了能力，实现层是空的。
//
// 后果是同一件事被逼到 Kotlin 里用文本手段实现了三遍：
//   ① `StreamingThinkStripper`（QuroLocalEngineNative.kt:35，约 110 行文本状态机）
//   ② `MnnThinkContent.split()`（终态再切一次）
//   ③ `stripResidualThink`（再兜一遍残留标签）
// 而且它自己承认有漏洞：QuroLocalEngineNative.kt:193 ——
//   「StreamingThinkStripper / stripResidualThink 只认 <think> 标签，
//     对这种**无标签明文推理**完全失效」。
//
// 本类把这件事收回到引擎边界：两端共用一份状态机，`isThinking` 真正有值，
// 上层不必再解析文本。Kotlin 侧那三层降级为「native 未识别时的兜底」。
//
// -----------------------------------------------------------------------------
// 【从 Kotlin 那三份实现里搬过来的真机教训，一条都不能少】
//
// 1. **全角标签**：模型会吐 `＜think＞`（U+FF1C / U+FF1E）而不是 `<think>`。
//    直接按半角匹配 → 检测不到 → 思考原文实时上屏泄漏。
//    （QuroLocalEngineNative.kt:72 的注释就是这个事故。）
//
// 2. **标签变体**：开标签可能是 `<think>` / `<think >` / `<thinking>`。
//    所以匹配的是**前缀**（`<think`），再过一遍 `>` 才算完整标签 ——
//    与 Kotlin 实现「遇到 '>' 即视为开标签结束」的策略一致。
//
// 3. **未闭合标签的尾部必须「暂缓提交」**：不能把可能构成标签前缀的字节提前发出去，
//    否则 UI 上会闪过 `<thi`。这是本类里最容易被"简化"掉、但一简化就出 bug 的部分。
//
// 4. **标签跨 token 边界**：token 是任意字节切片，`<think>` 可能被切成 `<th` + `ink>`。
//
// 5. **思考段内的 <tool_call> 必须保留**：这是当前**真实存在的漏网场景**
//    （QuroLocalEngineNative.kt:1127 的注释：
//      「未命中（模板无 parser / 思考段内 <tool_call>）」）。
//    模型把工具调用写在思考段里时：剥离器当思考丢掉、解析器在正文找不到 ——
//    两条路径谁都接不住。本类显式建模这一态（见 Segment::ToolCallInThinking）。
//
// -----------------------------------------------------------------------------
// 【本类**不负责** UTF-8 边界】
//
// UTF-8 跨 token 边界由调用方既有的 `pendingUtf8` 缓冲处理（两栈都已跑通），
// 本类只处理**标签**边界。职责单一，且不去动已经稳定的链路。
//
// 这么切是安全的，因为：**UTF-8 的多字节序列里不含任何 ASCII 字节**
// （续字节一律 ≥ 0x80，首字节 ≥ 0xC2）。而所有标记的前缀首字节要么是 ASCII（`<`），
// 要么是多字节序列的首字节（≥ 0x80）。因此按**字节**做前缀比较，
// 永远不会把一个合法的多字节字符从中间误判成标记起点。
// =============================================================================
#ifndef QURO_LLM_THINK_SPLITTER_H
#define QURO_LLM_THINK_SPLITTER_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace quro {
namespace llm {

/// 一次 feed 的分流结果。两段都可能为空；也可能同时非空（一个 token 里跨了标签）。
struct ThinkSplit {
    std::string thinking;   // 归属思考段的增量 → 上层应置 isThinking = true
    std::string visible;    // 归属正文段的增量 → 上层应置 isThinking = false

    bool empty() const { return thinking.empty() && visible.empty(); }
    void clear() {
        thinking.clear();
        visible.clear();
    }
};

/**
 * 思考段 / 正文段分流器。
 *
 * 典型用法（每次拿到一个 token 或一段字节后调用）：
 * @code
 *   ThinkSplit s = splitter.feed(text, len);
 *   if (!s.thinking.empty()) { TokenChunk c; c.text = s.thinking.data();
 *                              c.len = s.thinking.size(); c.isThinking = true;
 *                              cbs.onToken(cbs.user, c); }
 *   if (!s.visible.empty())  { ... c.isThinking = false; ... }
 *   // 生成结束时：
 *   ThinkSplit tail = splitter.finish();
 * @endcode
 *
 * 线程安全：**否**。每个会话一个实例，由该会话的生成线程独占
 * （与 Session 的生命周期一致，不需要额外锁 —— 生成本身已被 genLock 串行化）。
 */
class ThinkSplitter {
public:
    /**
     * 一个标记。
     *
     * @c prefix     起始字面量，如 `<think`（故意不带 `>`，以吸收 `<think >` 变体）
     * @c terminator 空 = 定长标记（`prefix` 本身就是完整标记）；
     *               非空 = 从 `prefix` 之后扫到该字面量才算完整标记（如 `>`、`＞`）
     * @c open       true = 进入思考段；false = 退出思考段
     */
    struct Marker {
        std::string prefix;
        std::string terminator;
        bool open = true;
    };

    struct Config {
        /// 空 = 使用 defaultMarkers()。
        std::vector<Marker> markers;

        /**
         * 是否把思考段的内容也交给上层。
         * false = 思考段字节直接**丢弃**（不累积），适合"用户不需要看推理过程"的场景。
         * 注意：即使 false，ToolCallInThinking 里的工具调用仍会走 visible（不会丢）。
         */
        bool emitThinking = true;

        /**
         * 思考段内的 `<tool_call>…</tool_call>` 是否提升到 visible。
         *
         * 默认 true，取舍是「宁误报不漏报」：
         *   关闭 → 思考段里的工具调用**确定**丢失（当前的 bug）；
         *   开启 → 极小概率把「模型只是在讨论 tool_call 写法」的文本也交给解析器，
         *          但解析器本身要求结构合法，误报会被它挡掉。
         */
        bool keepToolCallsInThinking = true;

        std::string toolCallOpen = "<tool_call>";
        std::string toolCallClose = "</tool_call>";

        /// 标签最大长度保护：`prefix` 之后超过这么多字节仍没遇到 terminator，
        /// 就认定它不是标签（防止 `<think` 后面跟了一整篇没有 `>` 的文档时无限缓冲）。
        size_t maxTagLength = 16;
    };

    /// 当前所处的段（诊断用）。
    enum class Segment {
        Visible,              // 正文
        Thinking,             // 思考段（<think>…</think> 内部）
        ToolCallInThinking,   // 思考段内部、且已进入 <tool_call> 块（内容走 visible）
    };

    /// 默认构造（等价于用 Config() 构造）。
    ///
    /// 这里**不能**写成 `explicit ThinkSplitter(Config config = Config())` ——
    /// 那是 C++ 的一个硬规则陷阱，已实测报错：
    ///   error: default member initializer for 'emitThinking' needed within
    ///          definition of enclosing class 'ThinkSplitter' outside of member functions
    /// 原因：`Config()` 的隐式默认构造要用到 Config 各成员的默认初始化器，
    /// 而默认实参的求值点仍在 `ThinkSplitter` 的定义**内部**，
    /// 此时外围类尚未完整。拆成两个构造函数即可绕开。
    ThinkSplitter();

    /// 指定配置构造。
    explicit ThinkSplitter(Config config);

    /**
     * 喂入一段 UTF-8 增量。
     *
     * @param data 字节指针（可含 NUL —— 长度由 @p len 决定）
     * @param len  字节数
     * @return 本次可确定归属的增量。**可能两段同时非空**，调用方需分别回调。
     *
     * 尾部若可能构成某个标记的前缀，会被**暂缓**到下次 feed 或 finish()，
     * 因此返回值不包含它 —— 这是有意的，不是丢数据。
     */
    ThinkSplit feed(const char* data, size_t len);

    /// 便捷重载。
    ThinkSplit feed(const std::string& data) { return feed(data.data(), data.size()); }

    /**
     * 生成结束：把暂缓的尾部全部吐出。
     *
     * 未闭合的思考段：其剩余内容按**思考段**归属吐出（它本来就是思考内容）。
     * 不完整标签前缀（如正文里的 `<thi`）：按**当前段**归属吐出，不丢字节。
     */
    ThinkSplit finish();

    /// 当前段。
    Segment segment() const { return segment_; }

    /// 是否正处在思考段内（含思考段里的 tool_call 块）。
    bool inThinking() const { return segment_ != Segment::Visible; }

    /// 是否正处在「思考段里的工具调用块」内。
    bool inToolCallInThinking() const { return segment_ == Segment::ToolCallInThinking; }

    /// 复位到初始状态（**换会话/换模型时必须调**，否则状态串味）。
    void reset();

    /// 默认标记集合（6 条，见 src/think_splitter.cpp）：
    ///   半角 `<think` / `</think`、`<thinking` / `</thinking`、
    ///   全角 `＜think` / `＜/think`。
    /// 注意 terminator 不含在 prefix 里：`<think >` 这类变体也认。
    static std::vector<Marker> defaultMarkers();

    /// 当前配置（只读）。
    const Config& config() const { return config_; }

private:
    /// 匹配结果。
    enum class Kind {
        None,     ///< 此处没有任何标记，也不是任何标记的前缀
        Partial,  ///< 此处是某个标记的**真前缀**，需要更多字节才能判定
        Full,     ///< 完整命中一个标记
    };

    struct Match {
        Kind kind = Kind::None;
        size_t length = 0;     ///< Full 时：整个标记的字节长度
        bool isOpen = false;   ///< Full 时：该标记是开标记还是闭标记
        bool isToolCall = false;  ///< Full 时：命中的是 tool_call 边界而非思考标记
    };

    /// 扫出并分流 @p pending_ 中所有可确定的字节。
    void drain(ThinkSplit* out, bool final);

    /// 在 @p pos 处尝试匹配一个「当前段期望的」标记。
    Match matchAt(size_t pos, bool final) const;

    /// 从 @p pos 之后（含）找下一个「可能是当前段期望标记起点」的字节下标。
    /// 找不到返回 npos。用于快路径：中间一大段普通文本可以一次性吐出。
    size_t nextPossibleStart(size_t pos) const;

    /// 重建「当前段期望的候选标记」缓存（存下标进 markers_，已按 prefix 长度降序）。
    /// **唯一的调用时机**是段发生变化之后，由 ensureExpected() 懒触发。
    void rebuildExpected() const;

    /// 确保 expected_ / firstByteTable_ 与 segment_ 一致（不一致就重建）。
    void ensureExpected() const;

    /// 把 @p text 按当前段归属写进 @p out。
    ///
    /// 收 `string_view` 而不是 `const std::string&`：drain 的快路径要交出一段
    /// `pending_` 的子串，传 string 会在**每次 feed 的热路径**上多一次堆分配。
    /// 改为视图后这一路完全零分配（实测吞吐提升的主要来源之一）。
    void emit(ThinkSplit* out, std::string_view text);

    /// 命中标记后切换状态。
    void applyMatch(const Match& m, ThinkSplit* out);

    /// @p data 的前 @p n 字节是否是 @p marker 的真前缀（n < marker.size()）。
    static bool isProperPrefix(const char* data, size_t n, const std::string& full);

    Config config_;

    /// **全部**标记的稳定存储：config_.markers + toolOpen_ + toolClose_，
    /// 已按 prefix 长度降序排列（长前缀优先，见文件头第 2 条）。
    ///
    /// 为什么把 tool_call 两个边界也并进来：这样「候选」就只是本数组的一段下标，
    /// 一套匹配逻辑覆盖所有标记，不必另写分支 —— 少一条分支就少一处会漂移的实现。
    ///
    /// 为什么不直接塞进 config_.markers：`config()` 是对外只读契约，
    /// 往里混入 tool_call 会让调用方看到并非它配置过的东西。
    std::vector<Marker> markers_;

    /// tool_call 两个边界在 markers_ 里的下标（排序后重算）。
    uint16_t toolOpenIndex_ = 0;
    uint16_t toolCloseIndex_ = 0;

    Segment segment_ = Segment::Visible;

    /// 已收到但**归属未定**的尾字节（可能是标记前缀）。
    std::string pending_;

    /// 当前段期望的候选标记下标（进 markers_），已按长度降序。
    ///
    /// 【为什么存下标而不是指针 —— 这一点必须写在这里】
    /// 本类在 `llama_detail::Session` / `mnn_detail::Session` 里是**值成员**。
    /// Session 一旦被拷贝，存指针的缓存就会指向**原对象**的 markers_，
    /// 变成悬垂引用 —— 而且是"平时不崩、偶发才崩"的那种，最难定位。
    /// 下标不会。markers_ 本身也是值，本类因此保持可安全拷贝/移动。
    ///
    /// 【为什么是 mutable】缓存按需重建，而重建发生在只读的查询路径上。
    mutable std::vector<uint16_t> expected_;
    mutable bool expectedValid_ = false;

    /// 当前段期望标记的**首字节**查找表（O(1) 判定，取代"遍历候选比首字节"）。
    /// 实际只会用到两个值：半角 '<'（0x3C）与全角 '＜'（首字节 0xEF）。
    mutable bool firstByteTable_[256] = {};
};

}  // namespace llm
}  // namespace quro

#endif  // QURO_LLM_THINK_SPLITTER_H
