# -*- coding: utf-8 -*-
"""把 N4–N9 的实施进度与本轮抓到的 bug 4 / bug 5 补进架构文档。

不走 shell heredoc（它会吞一层反斜杠，正文里有大量 \\b / \\s 正则字面量）。
锚点必须唯一，否则整体放弃写入（防半装载）。
"""

import io

PATH = (r"D:\Calw OS-project\QuroAI\docs\architecture"
        r"\本地推理引擎思考与工具调用架构.md")

with io.open(PATH, encoding="utf-8", newline="") as fh:
    raw = fh.read()

crlf = "\r\n" in raw
doc = raw.replace("\r\n", "\n")

# ───────────────────────── ① 待办 → N4–N9 完成记录 ─────────────────────────
OLD_TODO = """### 待办（原 N4–N7）
- **N4** llama 侧补 `setThinkingMode`（MNN 已有，走 `jinja.context.enable_thinking`），语义对齐。
- **N5** 把 GBNF 入口接出来：`LlamaNative.nativeSetToolCallGrammar(...)`（native 早已实现但**入口不可达**）。
- **N6** `llm/core` 新增引擎无关 `tool_call_parser`，MNN 接入，llama 用作二级兜底。
- **N7** 思考段 × 工具调用的回归用例（当前已确认的真实漏网场景）。
"""

NEW_TODO = """### ✅ N4 · llama 侧思考开关（三态）—— 已完成并验证

`common_chat_templates_inputs` 有两个**极易混淆**的字段：

| 字段 | 真义 | 来源 |
|---|---|---|
| `enable_thinking` | 传给模板的 jinja 变量，决定模板**要不要**渲染思考段 | 上层设置 |
| `supports_thinking` | 模板**有没有**思考段结构 | autoparser 解析模板得出（chat.cpp:2869，`autoparser.reasoning.mode != NONE`） |

🔴 **陷阱**：上游函数名 `common_chat_templates_support_enable_thinking()` 返回的是
`params.supports_thinking` —— 它问的是「模板**有**思考段结构」，
等价于 MNN 的 `emitsThinkBlock`，**不是**「模板里出现 `enable_thinking` 字面」。
后者会把「吐明文推理的小模型」推进 thinking 模式，剥离逻辑失效、推理独白混进正文 ——
正是 MNN 侧 v1.0.50 那次修正用血换来的教训。

`Session` 用**三态** `int32_t thinkingMode`（-1 未设置 / 0 关 / 1 开）而不是 bool：
"上层没管思考"与"上层明确关掉思考"必须是两件事，否则两栈会静默分歧。

两条渲染路径（`applyChatTemplate` / `applyStructuredChatTemplate`）各注入一次；
`releaseResources` 复位为 -1；`setThinkingMode` 只写 `err` **不写 `lastError`**
（否则聊天气泡会冒出误导性错误）。

**驱动层接线**：`QuroLocalEngineNative` 在 `LlamaSession.create` 成功后
`supportsThinking()` 探测 → `setThinkingMode(emitsThinkBlock)`。
此前 MNN 有这个开关、llama 完全没有 —— 同一个模型走两个后端，行为不一致。

### ✅ N5 · GBNF 入口接出（修一个真 bug）—— 已完成并验证

🔴 `Java_..._nativeSetToolCallGrammar` 在 `llama_jni.cpp` 上半部分**有实现**
（含事务回滚，注释齐全），但 `kLlamaNativeMethods` 注册表里**没有它**、
`LlamaNative.kt` 也**没有声明** → 上层一调就是 `UnsatisfiedLinkError`，
`LlamaEngine::setToolCallGrammar` 根本不可达。

补注册后 `kLlamaNativeCount` 14 → 17（另含 N4 的两个方法）。

**同时澄清一件事**：文法**并非空转**。`applyStructuredChatTemplate` 内部
（llama_engine_chat.cpp:460）已自动 `buildToolCallGrammarConfig(params)`
装载 GBNF 并重建采样链。`setToolCallGrammar` 只是**手动覆盖**入口。
所以「工具调用没被文法约束」这个猜测**不成立**。

### ✅ N6 · 调用链调研 —— 结论：无需新增，已有完整实现

- Kotlin 侧 `QuroLocalToolsCodec` 提供 `encodeTools / buildToolInstruction /
  withToolInstruction / encodeMessages / parseToolCalls / parseDetailed` —— 已是完整的双向编解码器。
- MNN 侧 native 有 `applyChatTemplateWithStructuredMessages` + `generateStructured`
  （均收 `toolsJson`）。
- llama 侧结构文法 + `common_chat_parser_params`（官方 parser）齐备。
- `app` 层 `git grep -ni "gbnf\\|grammar"` 只命中一条注释 —— 因为上层**不需要**生成文法。

**唯一真实的架构落差**：MNN 上游（`transformers/llm/engine`）**完全没有 grammar/GBNF 支持**
（源码 grep 零命中），也**没有** llama 那种 autoparser —— MNN 的工具调用解析只能靠 Kotlin 反解。
这是**上游能力边界**，不是本仓的缺口。`llm/core` 引擎无关 `tool_call_parser`
（让两端反解结果一致）仍是**可选的设计层重构**，不是 bug 修复。

### ✅ N7 · 思考段 × 工具调用 回归用例 —— 已完成并验证

- 新建 `StreamingThinkStripperTest.kt`（11 例）：标签切分、跨 chunk 标签不泄漏、全角归一、
  未闭合思考段不外泄、逐字符喂入与整串喂入结果一致、`reset` 清空全部视图。
  **其中 1 例专门守 N3 回归**（native 思考段重新贴标签后 `rawText()` 必须恢复完整原文）。
- `QuroLocalToolsCodecTest.kt` 追加 4 例：思考段内单个/多个 `<tool_call>` 恢复、
  思考段与正文都含调用时全部收集、思考段被截断时仍能恢复。

实测 `44 tests completed, 3 failed` —— 新增 13 例**全绿**；3 个失败经
`git stash push -- <该文件>` 跑基线确认为**既有失败**（`encodeMessages` 系列），与本次改动无关。

### ✅ N8 · 把上游探测到的真实思考标签喂给分流器 —— 已完成并验证

`ThinkSplitter` 默认标记集只认 `<think>` / `<thinking>` / 全角三种形态。
但 llama.cpp 的模板 detector（`common_chat_params::thinking_start_tag /
thinking_end_tags`）为**每个**模板家族赋了真实标签：
`[THINK]`/`[/THINK]`、`<|channel|>analysis<|message|>`/`<|end|>`、
`<|channel>thought`/`<channel|>`、`<think>`/`</think>`。

→ 不注入的话，用非标准标签的模型**思考原文直接上屏**。

`ThinkSplitter` 新增运行期 `addMarkers()` / `setConfig()`，并抽出 `rebuildMarkers()`
让「构造函数 / setConfig / addMarkers」三条路径共用同一段建表逻辑
（避免"构造函数改了、运行期忘了改"的漂移）。

两个设计要点：

- **并集而非替换**：`addMarkers` 在现有集合上追加去重；`extra` 为空时**什么都不做**，
  保留默认集 —— 探测不到标签时行为与本改动之前**完全一致**。
- **幂等是必须的，不是优化**：引擎每轮渲染 prompt 都会调一次；无条件 `reset()`
  会把已切一半的思考段打断，表现为「思考碎片漏进正文，且只在多轮对话里复现」。

标签用**定长标记**（`terminator` 为空）：`<|channel|>analysis<|message|>` 有 28 字节，
远超 `maxTagLength`(=16) 的保护上限 —— 定长匹配不走那条保护，因此不受限。

⚠️ 需知晓的副作用：`applyChatTemplate` / `applyStructuredChatTemplate`
**从此不再是纯函数**（会修改 session 的标记集）。

实测：C++ 单测 **223 条断言 0 失败**（N1 的 209 + N8 的 14）；
性能未退化（A 纯正文 14.14 ms / 70.7 MB/s；B 含标签 17.35 ms / 57.6 MB/s，
思考 422464 B / 正文 512992 B）。

### ✅ N9 · MNN 侧同源缺口对齐 —— 已完成并验证

同一个缺口在 MNN 侧**独立存在**（两边不走同一份代码）：
`MnnModelCapabilities.emitsThinkBlock` 只认 `<think>`，
且 MNN 没有 llama.cpp 那种「模板 detector 自动给标签」的路径。

修法：`MnnModelCapabilities` 增加 `THINK_TAG_PAIRS` 探测
（**要求开闭成对出现**，避免把正文里偶发的单个标签当成特征），
把探测到的标签经 `nativeSetThinkMarkers` → `MnnEngine::setThinkMarkers`
→ `ThinkSplitter::addMarkers` 注入。

**顺序不能反**（驱动层注释已写明）：标签决定原生**能不能切开**思考段，
开关决定要不要**产出**思考段。默认集认不出标签时，症状就是「开关开了、剥离却没生效」。

`kMNNLlmNativeCount` 22 → 23；MNN 能力单测 16 例 0 失败。

### ⏳ 明确未做（记下，不是遗忘）

- **`llm/core` 引擎无关 `tool_call_parser`**：让 MNN 不再依赖 Kotlin `QuroLocalToolsCodec`，
  两端反解结果一致。属设计层重构（两端当前各自可用，无功能性 bug）。
- **`StreamingThinkStripper` 显式降级为纯兼容 fallback**：当前已通过
  「native 通道优先 + stripper 兜底 + `nativeThinkingSeen` 去重」达成互斥共存，可再显式化一层。
- **端到端联调**（JNI 路由 + stripper + parser 串起来跑真实模型）—— 需真机。
"""

assert doc.count(OLD_TODO) == 1, "TODO anchor count=%d" % doc.count(OLD_TODO)
doc = doc.replace(OLD_TODO, NEW_TODO, 1)

# ───────────────────────── ② 七章标题：3 → 5 个 bug ─────────────────────────
OLD_TITLE = '## 七、实施中抓到的 3 个真 bug（都是"看起来对"的那种）'
NEW_TITLE = '## 七、实施中抓到的 5 个真 bug（都是"看起来对"的那种）'
assert doc.count(OLD_TITLE) == 1, "TITLE anchor count=%d" % doc.count(OLD_TITLE)
doc = doc.replace(OLD_TITLE, NEW_TITLE, 1)

# ───────────────────────── ③ 追加 bug 4 / bug 5 ─────────────────────────
TAIL = '''**教训**：任何"跨轮复用的状态机"都要先问一句"它的生命周期是哪一段"，
并在那个生命周期的起点显式复位。
'''

BUG45 = '''**教训**：任何"跨轮复用的状态机"都要先问一句"它的生命周期是哪一段"，
并在那个生命周期的起点显式复位。

### bug 4 · JNI 实现存在但没注册（N5）—— 编译期完全看不出来

`Java_..._nativeSetToolCallGrammar` 写在 `llama_jni.cpp` 上半部分，
实现完整（含事务回滚）、注释齐全，**但不在 `kLlamaNativeMethods` 注册表里**，
Kotlin 侧也没有对应的 `external fun`。

三处里缺两处，**编译一切正常** —— 只有上层真的调用才炸 `UnsatisfiedLinkError`，
而那个报错只会说"找不到方法"，不会告诉你"实现其实早就写好了"。

**教训**：加一个 native 入口要**同时**改三处（JNI 实现 / 注册表 / Kotlin 声明），
任何一处缺失都是"沉默的半成品"。可以加一条静态检查：
扫 `Java_*` 定义与注册表条目做差集，差集非空就报错。

### bug 5 · 注释声称覆盖的变体，其实匹配不到（MNN 工具锚点）

```kotlin
"{% if tools",        // jinja 条件分支消费 tools（含 {%- if tools 变体）
```

注释写着"含 `{%- if tools` 变体"，但那串子串**匹配不到** `{%- if tools %}`
（`{%` 之后多了空白控制符 `-`）。而 Qwen3 / Hermes 系官方模板写的恰恰是 `{%- if tools %}`。

长期没暴露的原因很讽刺：那些模板**同时**含 `for tool in tools`，
被另一条锚点兜住了。本轮做"模板变体审计"时，随机试了
「只写条件分支、不写循环」的模板才炸出来。

后果：这类模板被判为「不支持工具调用」→ 工具 schema 从「模板原生消费」
降级成「system 文本注入」，表现为"模型明明支持 tool call，却总不按 schema 出参"。

修法：改用正则 `\\{%[-+]?\\s*if\\s+[^%{]*\\btools\\b`（覆盖 `{%-` / `{%+` / 任意空格），
并补 2 例只测该变体的回归。

**教训**：注释里"我涵盖了 X"这句话本身没有约束力。凡注释声称覆盖某个变体，
就必须有一条**只测那个变体**的用例 —— 否则这条注释迟早变成谎言。

> 这是本轮唯一一个「不是靠读代码、而是靠**构造反例**发现」的 bug。
> 前四个都能靠静态审计抓到，这一个必须真的去试边界输入。
'''

assert doc.count(TAIL) == 1, "TAIL anchor count=%d" % doc.count(TAIL)
doc = doc.replace(TAIL, BUG45, 1)

out = doc.replace("\n", "\r\n") if crlf else doc
with io.open(PATH, "w", encoding="utf-8", newline="") as fh:
    fh.write(out)

print("UPDATED %s  (crlf=%s, %d chars)" % (PATH.split("\\")[-1], crlf, len(doc)))
