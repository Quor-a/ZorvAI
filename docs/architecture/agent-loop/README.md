# Agent 循环架构

ZorvAI 的 ReAct 主循环：轮次控制、死循环制动、子智能体、交付闸门、上下文预算与压缩。

盘点时间 2026-10-01。所有类名 / 常量 / 数值均已对照源码核实（行号随版本可能漂移，以类名为准）。

---

## 1. 职责边界

**负责：**

- `while (round < roundLimit)` 的 ReAct 主循环：LLM → tool_calls → 执行 → 回灌 → 再问
- 轮次上限、两套死循环检测、收尾提示与硬停
- 子智能体派发（隔离上下文、工具白名单、限轮数）
- 长程任务编排的两个可注入点：`TaskPlanner`（首轮策划）与 `DeliverabilityJudge`（交付闸门）
- 上下文预算解析与工具结果压缩 / 归档的**接线**（算法本身在 `QuroConversation` 与 `QuroModelContextBudget`）

**不负责：**

- 单个工具怎么跑、失败怎么重试 —— 见 `../tool-system/README.md`
- `tools` 数组的 JSON 拼装与上游协议 —— `QuroLlmClient`
- 思考参数的请求侧编译 —— `QuroReasoningControl`（见 `../云端推理思考与工具调用架构.md`）

---

## 2. 关键类与文件

| 文件路径 | 职责 |
|---|---|
| `core/QuroAssistant.kt` | 主循环 `ask()`、死循环检测、`runSubAgent`、`toolOutputArchiver`、`compactForLocal` |
| `core/QuroConversation.kt` | `compactToolResults` / `truncateToolResult` / `extractKeyLines` / `pruneOrphanToolMessages` / `attachToolNames` / `QuroConversationStore` |
| `core/network/QuroModelContextBudget.kt` | 上下文预算三级解析（接口值 → 模型名族 → 保守回落） |
| `core/agent/orchestration/TaskPlanner.kt` | `fun interface TaskPlanner { suspend fun plan(brief, context): TaskPlan }` |
| `core/agent/orchestration/DeliverabilityJudge.kt` | `DeliverabilityJudge` / `AlwaysDeliverable` / `HeuristicDeliverabilityJudge` |
| `core/agent/orchestration/OrchestrationTrace.kt` | 编排阶段轨迹（strategize / execute / deliver） |
| `core/agent/QuroAgentTrace.kt` | 执行轨迹总线（thought / action / result / status） |
| `core/tools/QuroToolEngine.kt`→实际在 `core/tools/QuroTool.kt:334` | 工具执行（本循环的唯一调用点） |

---

## 3. 数据流 / 控制流

```mermaid
flowchart TD
    P0[解析预算 QuroModelContextBudget.resolve] --> P1[选工具集 coreSpecs / fullSpecs]
    P1 --> P2[摘除 memory_* 若记忆关闭 / 并入或摘除 spawn_subagent]
    P2 --> L{while round < roundLimit}
    L --> R0[ensureActive 取消点 → round++]
    R0 --> R3[round==1 且 planner≠null → 注入隐藏【任务方案】]
    R3 --> R16{round >= 16 且未提示过}
    R16 -->|是| R17[注入【请收尾】隐藏 system]
    R17 --> R18{round >= 80}
    R16 -->|否| R18
    R18 -->|是| HARD[硬停：追加助手消息 + break]
    R18 -->|否| C1[toLlmMessages：压缩 + 预算裁剪 + 孤儿剔除]
    C1 --> C2[client.chat 或 routeLocal]
    C2 --> RES{结果类型}
    RES -->|Text| T1[maybeDeliver 交付闸门]
    T1 -->|可交付| T2[返回文本]
    T1 -->|不可交付 且 <3 次| T3[注入修正提示 → continue]
    RES -->|ToolCalls| A1[唯一 id → 落 hidden assistant 占位]
    A1 --> A2[分流：tool_router / spawn_subagent / engine.execute]
    A2 --> A3[回填 toolCalls + 追加 role=tool 消息]
    A3 --> A4[死循环检测 A：滑动窗口签名]
    A4 --> A5[死循环检测 B：相同签名 + 失败重试]
    A5 -->|repeatStreak >= 10| STOP1[强制停止]
    A5 -->|否| L
    RES -->|Error| E1[复用占位气泡写错误 → 返回]
    HARD --> D1{loopRepeatStreak >= 30 且无文本}
    D1 -->|是| STOP2[循环停止提示]
```

单轮关键顺序（**不可调换**）：① `ensureActive()` —— 用户点「停止生成」后下一轮立即抛 `CancellationException`，避免卡死在「思考中」；② `toLlmMessages` 组装上下文（压缩与裁剪在**请求前**，每轮重算，归档器因此必须幂等）；③ 发请求，`CancellationException` **原样上抛**、绝不包成「请求失败」假错误；④ `Text` 分支先过交付闸门，不可交付则注入 `nudge` 后 `continue`；⑤ `ToolCalls` 分支先落 `hidden` assistant 占位（UI 立即显示进度），再执行，最后回填。

---

## 4. 关键设计决策

### 4.1 轮次上限：不靠「低轮次封顶」防死循环

```kotlin
val roundLimit = if (isLocal) 12 else if (cfg.maxToolRounds in 1..2000) cfg.maxToolRounds else 2000
```

| 路径 | 上限 |
|---|---|
| 本地（MNN / LLAMA_CPP） | **12** |
| 云端，`cfg.maxToolRounds` 在 1..2000 | `cfg.maxToolRounds` |
| 云端，其它（含默认 0） | **2000** |

`QuroModelConfig.maxToolRounds` 默认值为 `0`（注释：0 = 不限制，ReAct 循环持续到模型给出最终答复）。

**为什么不设小上限**：连续多轮只发工具调用、不出文本，是 AI 修 bug 的**常态**，不是卡死。低轮次封顶会直接腰斩合法长任务。真正的防御是下方的两套精确检测。

**本地为什么是 12**：1.2B~3B 小模型在「无上限历史」下极易把较早轮次当成当前指令回放 / 续写（乱恢复）。根因不在 KV 残留（原生层每轮已 `llm->reset()` 并从完整 history 重新 prefill），而在喂给小模型的上下文过长且无界。12 轮足够覆盖正常多轮。

### 4.2 死循环检测 A：滑动窗口签名（`LOOP_WINDOW = 16`）

```kotlin
val sig = result.calls.joinToString("|") { "${it.name}:${it.arguments}" }
val hitWindow = recentSigs.contains(sig)
if (hitWindow) loopRepeatStreak++ else { loopRepeatStreak = 0; loopSegmentHadText = false; recentSigs.add(sig); if (recentSigs.size > LOOP_WINDOW) recentSigs.removeAt(0) }
```

签名 = 本轮全部 `tool_calls` 的 `name:arguments` 拼接。
- **命中窗口内已有签名** = 原地打转 → 计数累加；
- **新签名** = 合法探索 → 计数归零，签名入窗口（超过 16 个淘汰最旧）。

循环外判停：`loopRepeatStreak >= 30 && !loopSegmentHadText` —— 连续 30 轮原地打转且全程没产出最终文本，才确认真·死循环。

**为什么放在循环外**：只有真正跑完 `roundLimit` 才需要这个兜底；`loopSegmentHadText` 记录当前潜在循环段内是否产出过文本，避免把「边探索边小结」的任务误杀。

### 4.3 死循环检测 B：相同签名 + 失败重试

与 A 并行，判据更窄：`sig == prevCallSig`（**严格等于上一轮**，而非窗口内出现过）且 `results.any { toolResultLooksFailed(it.result) }`。

- **成功的重复调用完全不干预**（连计数都不累积）—— 批量 `input_text` 逐字输入、连续 `tap_screen` 都是合法的。
- 真正需要干预的是：调用重复**且**结果呈现失败特征 → 说明调用未生效、模型在盲目重试。
- 首次命中注入一条 hidden system 提示；同一签名只提示一次（`warnedForSig`），避免污染上下文。
- `repeatStreak >= 10` 才强制停止并落助手消息。

`toolResultLooksFailed` 是**高置信判定**：先看明确成功信号（"成功"/`"ok"`/`"success"`/`done`）→ 直接不算失败；再匹配强失败短语（`"error:"`/`Traceback`/`http 5`/`no such file`…）；最后仅当结果 ≤200 字时才接受裸「失败/错误/异常/超时」整词。**踩过的坑**：用裸子串匹配会把「正文里恰好提到这些字样」的成功结果误判为失败，进而误灌「停止重试」提示、打断正常调用。

### 4.4 收尾提示与硬停

| 阈值 | 行为 |
|---|---|
| `round >= 16` | 注入一次 hidden system：「请基于已有结果直接给出最终结论，不要再发起新的工具调用」（`concludedNudgeInjected` 保证只注入一次） |
| `round >= 80` | 追加可见助手消息「（已自动停止：连续执行的工具调用过多…）」并 `break` |

**为什么需要 16 这一档**：终端 / 排查类任务下，模型会反复发起**全新**的探测命令（签名各不相同），检测 A 的计数持续归零 —— 跑到 2000 轮都不停，对话框持续重复「测试终端」式文本。`round >= 16` 是补这个盲区的软引导；`round >= 80` 是硬闸。

### 4.5 子智能体：隔离 + 白名单 + 限轮

```kotlin
private val SUBAGENT_SAFE_TOOLS = setOf( /* 21 个只读 / 检索 / 计算工具 */ )
const val SUBAGENT_TOOL_NAME = "spawn_subagent"
```

| 约束 | 值 |
|---|---|
| 最大轮数 | `maxRounds = 6`（`runSubAgent` 内 `for (round in 0 until 6)`） |
| 工具集 | `registry.coreSpecs().filter { it.name in SUBAGENT_SAFE_TOOLS }` |
| 上下文 | 独立 `QuroConversationStore`，不污染主会话 |
| 递归 | 白名单内不含 `spawn_subagent`，且下发时已摘除 |
| 越权 | 白名单外的调用返回 `子智能体无权使用该工具：<name>` |

白名单内容：`get_current_time`、`calculate`、`get_device_info`、`get_battery`、`get_network_info`、`get_wifi_info`、`web_search`、`read_url`、`http_request`、`knowledge_search`、`knowledge_rag_search`、`memory_search`、`read_text_file`、`browse_files`、`list_files`、`file_read`、`file_info`、`find_files`、`github_search`、`github_read`、`crossref_search`。

**本地不派发**：`subAgentEnabled && !isLocal` 才并入下发列表（本地小模型不适用）。

**⚠️ 已修的坑**：子智能体路径 `subStore.toLlmMessages(system, 0, 0)` 传 `contextWindow = 0` 会走「完全不设防」分支（`ceiling = MODEL_MAX_INPUT_TOKENS`，即 1M）。现在 `toLlmMessages` 对 `contextWindow <= 0` 一律按 `CONSERVATIVE_INPUT_TOKENS` 兜底。

### 4.6 任务级闭环：`LongHorizonOrchestrator` 接进主循环

**这是 Agent 的固有环节，不是可关的开关。** 此前编排器写好了却全仓零调用（死代码），现已由 `QuroAssistant.ask()` 真正驱动。

```kotlin
// QuroAssistant.kt
val roundLimit = if (isLocal) 12 else if (cfg.maxToolRounds in 1..2000) cfg.maxToolRounds else 2000

suspend fun reactPass(): String {          // 一趟 = 一条完整 ReAct
    var round = 0
    while (round < roundLimit) { /* 工具执行、视觉注入、检查点落盘 */ }
    return lastText                          // 只产出候选答复，不自判可交付
}

val taskResult = orchestration.runTask(
    context = context,
    taskBrief = userBrief(),
    planner = { brief, planCtx ->            // 🔗 包一层：拿到方案立刻注入 store
        val plan = taskPlanner.plan(brief, planCtx)
        store.add(QuroMessage(role = "system", content = planText, hidden = true)); emit()
        plan
    },
    steps = listOf(TaskStep(REACT_PASS_STEP_ID, "执行一轮 ReAct 工具循环并给出答复")),
    stepExecutor = { _, _ -> ToolOutcome.Success(reactPass()) },
    judge = deliverabilityJudge,
    maxIterations = maxDeliverAttempts,       // 默认 3
    extraPlanContext = { recentFailureBrief()?.let { "最近工具失败：$it\n" } },
)
```

链路：**策划 → 执行一趟 ReAct → 交付闸门 → 不可交付则带记忆重新策划 → 再跑一趟 → 超限按最后一趟强制交付**。

**职责切分**：`runTask` 负责「何时重新策划」；`ask()` 负责「把方案落成模型看得见的提示」（注入 hidden system）。编排层因此不需要认识 `QuroMessage` / `store`。

**闸门唯一归属 `runTask`**：执行体内部**不得**再自判一次，否则一趟之内判两遍、白烧一轮 LLM。`ask()` 内的 `maybeDeliver()` / `lastGateReason` / 旧策划块已整体删除。

#### 两个会让闸门彻底失明的坑（接线时必须一起修）

| 坑 | 原状 | 后果 | 现状 |
|---|---|---|---|
| 重规划上下文是占位符 | `planner.plan(taskBrief, context?.let { "ctx" } ?: "")` | 第二个参数**恒为字面量 `"ctx"`**，重规划 100% 是盲的 | 编排器自累积 `lastGate`（打回原因 + suggestion）+ `lastFailures`（失败步骤摘要），另开 `extraPlanContext` 出口 |
| 闸门判的是加了前缀的摘要 | `summary = "OK: ${it.raw.take(200)}"` | `HeuristicDeliverabilityJudge` 的 `startsWith("工具执行失败")` **永远不成立** → 闸门恒判可交付、形同虚设 | 改判**产物原文**（最后一步的 `raw` / `message`），`summary` 只留给诊断 |

> `extraPlanContext` 存在的理由：执行体把整趟 ReAct **压成一步**，所以工具层失败不会体现在 `ToolOutcome` 上 —— 失败明细只有 `ask()` 看得到。

`HeuristicDeliverabilityJudge` 的 `BAD` 列表：`工具执行失败` / `工具执行异常` / `工具执行超时` / `未知工具` / `需要权限`；空产物或 `"(已思考完毕)"` 也判不可交付。

回归由 `LongHorizonWiringTest`（12 例）钉住：判原文不被加前缀、长产物不被截断、失败步骤取 `message` 不被丢空串、首轮 planner 上下文为空串、第 2 轮拿到打回原因 + 建议 + 失败步骤且以 `\n` 收尾、`Escalated` 仍带最后一趟产物。

### 4.7 工具结果压缩：头 + 尾（关键行抽取已回滚）

```kotlin
internal const val TOOL_RESULT_CAP = 1600   // 单条上限
```

当前实现是**单阶段**：超 1600 字符 → 头 40% + 尾 60% + 截断说明，工具轮始终 call↔result 成对（只改 content、不删消息）。按码点而非 UTF-16 字符截断，避免把 emoji / 代理对切成孤立代理项导致严格上游 JSON 解析 500。

> 🔴 **已回滚，不要照此文档实现**：本节此前还有「合计预算 24000 + 从最旧二次激进截到 400」与「从被丢弃的中间段按中英双语词表抽回关键行（`extractKeyLines`）」两层，以及 4.9 的真实归档落盘。commit `af5fe17` 已把这些**连同 21 例单测整体删除** —— 真机反馈是压缩后 AI 拿到的信息反而更少，回复开始答非所问 / 复读。`QuroModelContextBudget.kt` 类文件因仍被单测引用而保留，但**主链路已无任何调用**（`git grep` 确认），`MODEL_MAX_INPUT_TOKENS` 硬编码回 `1048576`。
>
> **当前方针：不再新增任何压缩 / 截断层。**

### 4.8 孤儿工具消息必须剔除

`pruneOrphanToolMessages` 在 `toLlmMessages` 收尾处成对校验：`role=tool` 且其 `tool_call_id` 在全部 `assistant.tool_calls` 的 id 集合里找不到 → 丢弃；`assistant` 且其 `toolCalls` 中任一 id 在 `role=tool` 的 `tool_call_id` 集合里找不到 → 整条丢弃。

**为什么**：`capRecentRounds`（按轮数 `takeLast`）与 token 预算裁剪（丢最旧）都可能把工具轮的边界切断，形成「带 tool_calls 却没有对应结果」的非法组合 → 严格上游 400 / 工具调用失效 / 模型乱回复。云端路径此前一直缺失这层（本地路径在 `compactForLocal` 有同款），现已统一。

`attachToolNames` 在其后按 id 反查补全 tool 消息的 `name` 字段 —— Kimi K3 会 400 报 `tool messages need a resolvable tool name`。

### 4.9 巨型消息降权，但必须按原始下标还原顺序

`GIANT_THRESHOLD = 3000`：超 3000 字符的消息（HTML / 代码 / 长文本）降为低优先级，预算紧张时率先被裁，降低「把旧任务结果当当前回复」的串台。

**踩过的坑**：旧逻辑把 normal / giant 分两路各自 `asReversed` 后拼接、再整体 `asReversed`，会把**所有巨型消息**排到**所有普通消息**之前，彻底打乱时序 —— `role=tool` 可能排到它对应的 `tool_calls` 之前 → 严格上游 400/500。现在 greedy 决策只收集**下标**，最后一步 `keptIdx.sort()` 按原始顺序还原。

---

## 5. 对外接口 / 契约

### `QuroAssistant.ask`

```kotlin
suspend fun ask(context: Context, cfg: QuroModelConfig, systemPrompt: String = "", autoSaveMemory: Boolean = true,
    stream: Boolean = false, historyRounds: Int = 0, deepThink: Boolean = false, subAgentEnabled: Boolean = true,
    onUpdate: (() -> Unit)? = null): String
```

编排相关字段：`var maxDeliverAttempts = 3`（= `runTask` 的 `maxIterations`）、`private val orchestration = LongHorizonOrchestrator()`、`private val REACT_PASS_STEP_ID = "react_pass"`；注入点 `var taskPlanner: TaskPlanner?`、`var deliverabilityJudge: DeliverabilityJudge?`。

`runTask` 是 `runTask` 任务级闭环的唯一生产调用点（`QuroAssistant.kt:1023`）。

### 子智能体常量（`QuroAssistant.Companion`）

| 常量 | 值 |
|---|---|
| `SUBAGENT_TOOL_NAME` | `"spawn_subagent"` |
| `SUBAGENT_TOOL_DESC` | 「派发一个独立的子智能体去完成一个聚焦的子任务…」 |
| `SUBAGENT_TOOL_PARAMS` | `{"type":"object","properties":{"task":{...}},"required":["task"]}` |
| `SUBAGENT_SYSTEM_PROMPT` | 聚焦、克制、只产出结果的三条要求 |

### 归档常量（`QuroAssistant.kt` 文件级 private · 🔴 已随 4.9 归档层回滚删除）

| 常量 | 值 |
|---|---|
| `TOOL_ARCHIVE_DIR` | `"tool_outputs"` |
| `TOOL_ARCHIVE_KEEP` | `60` |

### `QuroConversationStore.toLlmMessages`

```kotlin
fun toLlmMessages(system: QuroMessage? = null, contextWindow: Int = 0, historyRounds: Int = 0): List<QuroChatMessage>
```

🔴 `archive` 参数已随 4.7 压缩层回滚删除。`contextWindow <= 0` → 按 `MODEL_MAX_INPUT_TOKENS`（`1048576`）兜底，**不再走 `CONSERVATIVE_INPUT_TOKENS`**。

### 压缩函数（`QuroConversation.kt`，internal）

```kotlin
internal fun compactToolResults(list: List<QuroChatMessage>): List<QuroChatMessage>
internal fun truncateToolResult(text: String): String
```

🔴 `extractKeyLines` 与 `archive` 回调已随回滚删除，签名中**没有** `cap` / `archive` 参数。

### `QuroModelContextBudget`（🔴 主链路已无调用）

```kotlin
object QuroModelContextBudget {
    const val ABSOLUTE_MAX_INPUT_TOKENS: Int = 1_048_576
    const val CONSERVATIVE_INPUT_TOKENS: Int = 32_768
    fun resolve(modelName: String, provider: String = "", apiContextLength: Int = 0, userContextWindow: Int = 0): Budget
    fun describe(budget: Budget): String
}
enum class Source { API_META, MODEL_TABLE, USER_SETTING, CONSERVATIVE }
```

类文件与单测仍在（`core/network/QuroModelContextBudget.kt` + 同名单测），但 `git grep` 确认**主链路（`app/src/main`）除自身定义外零调用** —— `MODEL_MAX_INPUT_TOKENS` 已改回 `QuroConversation.kt` 内的硬编码 `1048576`。保留它只是因为单测仍引用；**不要按它接线**。

---

## 6. 已知约束与待办

| # | 约束 / 待办 | 位置 |
|---|---|---|
| 1 | 无 `FinishTool` 式显式结束工具（PokeClaw 有），任务终止靠 `round >= 16` 收尾提示 + 系统提示约定 | `QuroAssistant.kt:376` |
| 2 | `deliverAttempts >= 3` 是硬编码的交付闸门上限，不可配置 | `QuroAssistant.kt:120` |
| 3 | 无 token 成本护栏：`QuroLlmMeta.usage` 已解析（`finish_reason` / `reasoning_tokens`），但**未累计、未设成本上限** | — |
| 4 | `tool_choice` 当前硬编码 `auto`，`parallel_tool_calls` 未下发 | `QuroLlmClient` |
| 5 | 子智能体是**串行同步等待**（在主会话的 IO 协程内 `runSubAgent`），不能并行派发多个 | `QuroAssistant.kt:1174` |
| 6 | 子智能体的工具结果**不走主循环的压缩与归档**（它自己调 `engine.execute`，结果直接进 `subStore`） | `QuroAssistant.kt:1231` |
| 7 | 死循环检测 A 的 `>= 30` 判停只在**循环正常跑完 `roundLimit` 后**才生效；`round >= 80` 硬停会先触发，云端默认 2000 轮下 A 实际很少走到 | `QuroAssistant.kt:381 / 950` |
| 8 | `QuroToolRouter.loaded` 跨 `ask()` 不重置（PROGRESSIVE 未启用，暂不暴露） | `QuroToolRouter.kt:109` |
| 9 | `estTokens` 是 `length / 4` 的粗估，中英混排下偏差较大；裁剪精度依赖它 | `QuroConversation.kt:439` |
| 10 | `cfg.maxToolRounds` 默认 0 → 云端按 2000 天花板跑，`round >= 80` 是唯一现实制动 | `QuroModelConfig.kt:23` |
