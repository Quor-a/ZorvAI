# -*- coding: utf-8 -*-
"""把 N4–N9 + 工具锚点真 bug 追加进 2026-10-01 工作日志（append-only）。"""

import io

PATH = (r"D:\WyDownloads\2026-08-28-00-11-37\.workbuddy\memory"
        r"\2026-10-01.md")

BLOCK = """

---

## N4–N9 + 工具锚点真 bug（思考/工具调用构架续）

承接提交 `7478aae`（N1–N3：思考分流器 + 两栈接线 + isThinking 上行）。

### 完成项

**N4 · llama 侧思考开关（三态）**

- `Session.thinkingMode` 用 `int32_t`（-1 未设置 / 0 关 / 1 开）而非 bool ——
  必须区分「上层没管思考」与「上层明确关掉思考」，否则两栈静默分歧。
- 上游字段语义澄清（写进两层注释，防再次误改）：
  - `enable_thinking` = 传给模板的 jinja 变量，管「模板**要不要**渲染思考段」；
  - `supports_thinking` = autoparser 从模板解析得出（`chat.cpp:2869`
    `autoparser.reasoning.mode != NONE`），管「模板**有没有**思考段结构」。
  - 🔴 上游函数名 `common_chat_templates_support_enable_thinking()` 有误导性：
    它返回的是 `supports_thinking`，等价 MNN 的 `emitsThinkBlock`，
    **不是**「模板里出现 `enable_thinking` 字面」。后者会把吐明文推理的小模型
    推进 thinking 模式，剥离逻辑失效、推理独白混进正文（v1.0.50 的血债）。
- 两条渲染路径各注入一次；`releaseResources` 复位为 -1；
  `setThinkingMode` 只写 `err` **不写 `lastError`**（否则聊天气泡冒误导性错误）。
- 驱动层接线：`QuroLocalEngineNative` 在 `LlamaSession.create` 成功后
  `supportsThinking()` 探测 → `setThinkingMode(emitsThinkBlock)`。
  此前 MNN 有该开关、llama 完全没有 —— 同一模型走两后端行为不一致。

**N5 · GBNF 入口接出（真 bug）**

- `Java_..._nativeSetToolCallGrammar` 有实现，但**注册表没有、Kotlin 声明也没有**
  → 上层一调就 `UnsatisfiedLinkError`，`LlamaEngine::setToolCallGrammar` 不可达。
- 补注册，`kLlamaNativeCount` 14 → 17。
- **澄清**：文法**不是空转** —— `applyStructuredChatTemplate`（第 460 行）已自动
  `buildToolCallGrammarConfig(params)` 装载 GBNF 并重建采样链。
  `setToolCallGrammar` 只是**手动覆盖**入口。原猜测「工具调用没被文法约束」不成立。

**N6 · 调用链调研 → 结论「无需新增」**

- Kotlin `QuroLocalToolsCodec` 已是完整双向编解码器；MNN native 有
  `applyChatTemplateWithStructuredMessages` / `generateStructured`（均收 toolsJson）；
  llama 有结构文法 + 官方 `common_chat_parser_params`。
- 唯一架构落差：**MNN 上游完全没有 grammar/GBNF**（源码 grep 零命中），
  也无 autoparser → MNN 的工具调用解析只能靠 Kotlin 反解。属**上游能力边界**。
- `llm/core` 引擎无关 `tool_call_parser` = **可选设计层重构**，不是 bug。

**N7 · 思考段 × 工具调用 回归用例**

- 新建 `app/src/testFull/.../StreamingThinkStripperTest.kt` 11 例
  （含 1 例专守 N3 回归：native 思考段贴回 `<think>` 标签后 `rawText()` 恢复完整原文）。
- `QuroLocalToolsCodecTest.kt` 追加 4 例（思考段内单个/多个调用恢复、双段收集、截断恢复）。
- 结果 `44 tests completed, 3 failed`；新增 13 例全绿；
  3 失败经 `git stash push -- <该文件>` 跑基线确认为**既有失败**。

**N8 · 上游真实思考标签注入 llama 分流器**

- `ThinkSplitter` +`addMarkers` / `setConfig`，抽出 `rebuildMarkers()`
  让「构造函数 / setConfig / addMarkers」三条路径共用同一段建表逻辑。
- 并集不替换；传空则**什么都不做**（保留默认集，行为与本改动前一致）。
- **幂等是必须的，不是优化**：引擎每轮渲染 prompt 都调一次；无条件 `reset()`
  会打断已切一半的思考段，表现为「思考碎片漏进正文，只在多轮对话里复现」。
- 用**定长标记**（`terminator` 空）绕过 `maxTagLength=16`，
  容纳 28 字节的 `<|channel|>analysis<|message|>`。
- ⚠️ 副作用：`applyChatTemplate` / `applyStructuredChatTemplate` **不再是纯函数**。
- C++ 单测 **223 断言 0 失败**；性能未退化（A 14.14ms/70.7MB/s，B 17.35ms/57.6MB/s）。

**N9 · MNN 侧同源缺口对齐**

- `MnnModelCapabilities.THINK_TAG_PAIRS`（`[THINK]` / `<|channel|>analysis<|message|>` /
  `<|channel>thought`），**要求开闭成对出现**才算。
- 链路：`nativeSetThinkMarkers` → `MnnEngine::setThinkMarkers` → `ThinkSplitter::addMarkers`；
  `kMNNLlmNativeCount` 22 → 23。
- 驱动层顺序铁律：`setThinkMarkers` **必须在** `setThinkingMode` 之前
  （标签决定能不能切开，开关决定要不要产出）。

**工具锚点真 bug（顺手抓到，MNN 侧）**

- `TOOLS_ANCHORS` 里 `"{% if tools"`，注释写「含 `{%- if tools` 变体」，
  但那条子串**匹配不到** `{%- if tools %}`（`{%` 后多了空白控制符 `-`）。
  Qwen3 / Hermes 系官方模板恰恰是这种写法。
- 长期没暴露的原因很讽刺：那些模板同时含 `for tool in tools`，被另一条锚点兜住。
- 改正则 `\\{%[-+]?\\s*if\\s+[^%{]*\\btools\\b`（覆盖 `{%-` / `{%+` / 任意空格），
  补 2 例只测该变体的回归。MNN 能力单测 **16 例 0 失败**。

### 本轮踩到的坑

- **heredoc 吞反斜杠**：bash `<<'PYEOF'` 里写 `\\\\b` 到 Python 手里变 `\\b` →
  Python 解释为退格符 → **真退格符（0x08）写进 Kotlin 源码**，
  `\\btools\\b` 变成 `<BS>tools<BS>`。肉眼看不出来，正则语义已坏。
  → 凡含反斜杠的写入，一律用 Write 工具写脚本文件，**别用 heredoc**。
- **补丁脚本 NEW 段与 ANCHOR 常量不一致**：我把匹配用的锚点常量从 `jboolean` 改成
  `void`，但 NEW 段里硬编码的 `jboolean` 没跟着改 → `nativeCancel` 被改成非 void
  却没加 return → `-Wreturn-type` 报错。改锚点时必须**同步 NEW 段**。
- **锚点要按真实签名写**：`nativeCancel` 是 `JNIEXPORT void`，
  我按 `jboolean` 写 → hits=0，整体放弃写入（好在防半装载机制生效）。

### 验收

- `:app:assembleFullRelease` **BUILD SUCCESSFUL in 35s**；
  `libMNNWrapper.so`（182 MB，13:20）含 `nativeSetThinkMarkers` 字串。
- `:mnn:testDebugUnitTest` **16 例 0 失败** —— **首次在本机跑通**
  （之前 `--offline` 缺 `concurrent-futures` / `listenablefuture`，去掉 offline 才跑起来；
  也正因如此，那个 `{%- if tools` 的既有失败一直潜伏到本轮才被发现）。
- `:app:testFullDebugUnitTest` 44 例 3 失败（既有，已基线确认）。

### 明确未做（不是遗忘）

- `llm/core` 引擎无关 `tool_call_parser`（设计层重构，两端当前各自可用）。
- `StreamingThinkStripper` 显式降级为纯兼容 fallback。
- 端到端联调（JNI 路由 + stripper + parser 串起来跑真实模型）—— 需真机。
- 构建工具链 P1–P4（Gradle / CMake / NDK 工具化，见
  `docs/architecture/端侧构建工具链与智能体闭环技术架构.md`）。
- 文档已更新：`docs/architecture/本地推理引擎思考与工具调用架构.md`
  §六（N4–N9 进度）+ §七（bug 3 → 5 个）。
"""

with io.open(PATH, "r", encoding="utf-8", newline="") as fh:
    raw = fh.read()

crlf = "\r\n" in raw
tail = raw[-1:]
block = BLOCK.replace("\n", "\r\n") if crlf else BLOCK
if not raw.endswith("\n"):
    block = ("\r\n" if crlf else "\n") + block

with io.open(PATH, "a", encoding="utf-8", newline="") as fh:
    fh.write(block)

with io.open(PATH, "r", encoding="utf-8", newline="") as fh:
    after = fh.read()
print("APPENDED to 2026-10-01.md  (crlf=%s, %d -> %d chars)"
      % (crlf, len(raw), len(after)))
