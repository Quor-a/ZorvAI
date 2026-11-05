# 工具调用「卡」与 AI 不会用终端：三处真实缺陷

> 现象：①「AI 调用工具仍然偶尔卡」；②「终端能切到 zorvAI 了，但系统提示词没更新，AI 仍然不会用」。
> 两者都不是「模型不够聪明」，而是**下发与执行链路上的三处确定缺陷**。

---

## 1. AI 不会用终端：工具根本没下发到模型手里

### 1.1 优先名单里写的是 action 值，不是工具名

`ZorvPromptBudget.PRIORITY_TOOLS` 用来在预算紧张时"先保哪些工具"。原内容：

```kotlin
"terminal_run", "terminal_exec", "terminal_write", "terminal_status",
```

这四个**不是工具名**，是 `terminal` 工具的 **action 参数值**。注册表里不存在
`terminal_run` 这个工具（真实名字是 `override val name = "terminal"`），于是：

- 四个名字一个都匹配不上 → 第一轮"优先保"什么都不保；
- 而**唯一**能进 Termux 原生环境的 `termux` 工具压根不在名单里。

预算一紧（32K 窗口下 system 已占 26K token），工具按注册顺序被逐个丢弃，
`termux` 被整份丢掉 → 模型收到的 `tools` 字段里没有它 → **AI 不是不想用，是看不见**。

### 1.2 名单顺序形同虚设

`fitTools` 第一轮遍历的是 `all`（注册顺序），不是 `PRIORITY_TOOLS` 的声明顺序。
于是"优先集"只保证成员身份、不保证先后 —— 排在注册表末尾的关键工具照样被前面的
长描述工具吃光额度。现在改为**按名单声明顺序**遍历，输出仍按注册顺序还原
（优先级只决定"保谁"，不改变"以什么顺序发"，避免上游行为漂移）。

### 1.3 自检与防回归

- 新增 `ZorvPromptBudget.missingPriorityNames(all)`：纯函数，返回名单里在注册表中
  **不存在**的名字。`ZorvBrain.budgetToolSpecs` 首次调用时比对并打一条 warning。
- `ZorvPromptBudgetTest` 新增 3 例：名字必须全部存在、action 值不得混入、
  顺序必须压过注册顺序。

### 1.4 提示词补「两套环境」

`GenUiRules` 在【工具说明】后新增一段（约 330 字符，刻意短 —— system 越长，
工具被裁越多，长度本身就是成本）：

```
【终端：两套环境，命令不通用】
- termux：Termux 官方 bootstrap（带 pkg / apt）。用户说「终端」默认为它。
- terminal 与 linux_*：沙箱 proot Ubuntu 24.04，只有 apt，没有 pkg。
- 界面里的终端窗口默认就是 Termux；敲 zorv 进沙箱，exit 回 Termux。
- 把 pkg 发给沙箱、或把 apt 发给 Termux，都会 command not found —— 不是环境坏了，是选错了工具。
- 单条命令有超时（默认 120s）：装包、编译这类长任务先说明预计耗时。
```

---

## 2. 调用工具「卡」：超时被重试放大 3 倍

### 2.1 机制

`FailurePolicy.DEFAULT_TABLE` 原来是：

```kotlin
FailureType.TIMEOUT to listOf(RETRY, RETRY, ESCALATE)
```

单个工具默认 `execTimeoutMs = 60s` → 同一份参数**原样重跑两次** → 一次调用占 180s。
而 `QuroToolEngine.execute` 的整轮预算 `MAX_ROUND_BUDGET_MS = 180s` **只对多个串行调用生效**
（`if (serial.size <= 1) Long.MAX_VALUE`，且 `calls.size == 1` 直接走 `runOne`）——
也就是说，**"模型只发了一个调用"恰恰是最不受保护的情况**。
用户侧看到的就是：转圈三分钟，最后吐一句超时。

### 2.2 修法

`TIMEOUT` 改为 `listOf(ESCALATE)`：超时说明"给定预算内做不到"，同参数重跑几乎必然再超时。
真正的瞬时抖动属于 `TRANSPORT`（仍保留两次重试，网络容错不受影响）。
声明了更长 `execTimeoutMs` 的长任务（harness 3600s 等）本来就不会触发这条。

效果：单次调用最长 **60s**，而不是 180s。

### 2.3 界面在执行期间完全静默（另一半"卡"）

`ChatScreen` 的漂浮宠物有 `PetPhase.TOOL_CALLING`，判据是：

```kotlin
state.isStreaming && state.toolCallRecords.lastOrNull()?.result?.isBlank() == true
```

但记录**只在工具执行完成后才追加**（`ChatViewModel` 里 `executeSingleTool` 之后），
所以 `result.isBlank()` 永远为 false —— **这个分支是死代码**。
工具跑 60 秒，界面就静默 60 秒，用户体感就是卡死。

修法：

- 新增 `markToolStarted(name, args)`：执行**前**先落一条 `result = ""` 的占位记录；
- 新增 `markToolFinished(...)`：执行**后**用 `indexOfLast { name 匹配且 result 为空 }`
  在同一个 `update` lambda 内原子替换（找不到占位就追加，绝不丢结果）；
- `PetPhase` 判断把 `TOOL_CALLING` **提到 `GENERATING` 之前**：执行期间 `streamingText`
  可能还留着上一轮的文本，先判 GENERATING 就永远看不出"在等工具"。

---

## 3. 改动清单

| 文件 | 改动 |
|---|---|
| `genui/aiapp/brain/ZorvPromptBudget.kt` | 名单修正为真实工具名；新增 `missingPriorityNames`；`fitTools` 按声明顺序优先、按注册顺序输出 |
| `genui/aiapp/brain/ZorvBrain.kt` | 首次下发时自检名单并告警（只报一次） |
| `genui/aiapp/brain/GenUiRules.kt` | 新增「终端：两套环境」段 |
| `core/agent/loop/FailurePolicy.kt` | `TIMEOUT` 改为首档 ESCALATE |
| `core/tools/QuroTool.kt` | 更新 harness 覆写的注释（全局表已变，覆写理由不同，别误删） |
| `genui/aiapp/viewmodel/ChatViewModel.kt` | 新增 `markToolStarted` / `markToolFinished`，两处工具执行点接入 |
| `genui/aiapp/ui/ChatScreen.kt` | `TOOL_CALLING` 判断提前 |

测试：`ZorvPromptBudgetTest` +3、`ClosedLoopTest` 改 2、`ClosedLoopCorrectionChannelTest` 改 1，
全部通过。

---

## 4. 仍然存在的限制（如实记录）

1. **工具额度是硬约束**：32K 窗口下 system 占 26K token，工具层只剩约 12.5K 字符，
   放不下全部 258 个工具。现在的改进是"保证关键工具优先进入"，不是"全部下发"。
   想要全部工具，需要换更大窗口的模型，或继续压缩 `GenUiRules`。
2. **占位记录的进度是"有没有"，不是"百分之几"**：工具内部没有上报进度，
   界面只能显示"正在调用"，无法显示"装到 40%"。
3. **`TRANSPORT` 仍会重试两次**：网络类工具在真正断网时会占 3 倍时间（但通常是快速失败，
   不是 60s 超时）。
