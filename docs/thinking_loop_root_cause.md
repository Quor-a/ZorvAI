# 死循环思考：根因与修法

> 现象：所有通道（GenUI 与 zorvAI 主对话）都会「一直思考、几分钟不出结果」。
> 不是工具慢，也不是轮次太多 —— 是**推理内容被反复喂回模型**，加上**单轮内无人掐断**。

## 1. 根因一：上一轮的 reasoning 被整段回传给模型

`QuroLlmClient.messageToJson()` 与 `GenUILlmClient.messageToJson()` 都有这一段：

```kotlin
if (emitReasoning && m.reasoning != null) {
    o.put("reasoning_content", m.reasoning)   // 完整回传
}
```

而 `QuroReasoningEcho.shouldEcho()` 的默认值是 **true**：

```kotlin
fun shouldEcho(...) = detectFamily(...) != Family.OPENAI_EFFORT
```

即：**除 o 系 / GPT-5 之外的所有家族**（DeepSeek、Qwen3、MiMo、DashScope、未知网关）都会把
历史里**每一条** assistant 消息的完整 reasoning 发回上游。

于是形成闭环：

```
模型思考 3000 字 → 存进历史 → 下一轮完整回传 → 模型读到自己上次的思考，
顺着「接着想」→ 思考 6000 字 → 再回传 → …
```

模型不是忘了自己在干什么，而是**被自己的草稿推着继续推演**，直到在同一段话里原地复读。

### 修法

两条约束同时生效：

| 约束 | 理由 |
|---|---|
| 只回传**带 `tool_calls`** 的消息 | MiMo 等严格上游缺 `reasoning_content` 会 400/500，这一条不能省 |
| 回传时**截断到 2000 字符** | 让模型知道「上次判断了什么」即可，不需要把整段推演再喂回去 |

普通 assistant 消息的 reasoning **一律不回传** —— 那是模型的内部草稿，不是对话内容。

## 2. 根因二：单轮内的推理没人掐断

流式读取是 `while (!source.exhausted())`，`reasoningAcc` 无上限累积。
模型一旦在 reasoning 里兜圈，这条流会一路吐到 token 上限才结束 —— 期间上层拿不到返回，
界面永远显示「思考中」。

**轮次级的循环检测对这种形态永远不触发**，因为压根没走到下一轮。

### 修法：`ReasoningLoopGuard`（接收过程中判定并断流）

- **复读判据**：相邻两段（各 160 字符）的 trigram Jaccard 相似度 ≥ 0.90，连续 3 次
- **超长判据**：累积超过 24000 字符仍无正文

命中后直接 `break` 出读取循环、关闭连接：

- 有正文 → 按正常结果返回（不打断已产出内容）
- 一个字正文都没有 → 返回 `Error(STALL_NOTE)`，明确告诉用户「模型在推理阶段复读」

效果：等待时间从「几分钟直到 token 耗尽」压到几秒。

### 为什么用 trigram Jaccard

中文没有词边界，字符级 3-gram 对「换几个词说同一件事」足够敏感，
对「引入新概念」又足够不敏感，且一次遍历 O(n)。实测：同一段换几个字重复 → 相似度 > 0.9；
完全不同的话题 → < 0.2。

## 3. 附带修掉的轮次问题（GenUI）

GenUI 的循环上限是 `getMaxToolCallRounds() + MAX_VERIFY_ITERATIONS`，默认 **100 + 5 轮**，
且此前**没有任何循环检测**。新增 `LoopGuard`：

- 同一批调用签名在窗口内重复 → 首次给「请收尾」提示，连续 3 次直接停
- 硬上限 40 轮
- **纯文本轮清零** —— 模型在出结论，不算打转，不会误杀合法长任务

主对话本来就有签名窗口检测（16 轮提示 / 80 轮硬停），未改动。

## 4. 自检

```bash
# 单测（不需要真机）
./gradlew :app:testFullDebugUnitTest --tests "com.ai.assistance.quro.core.network.ReasoningLoopGuardTest" \
  --tests "com.ai.assistance.quro.core.agent.loop.LoopGuardTest"
```

真机上观察：出错/停止时 Logcat 会出现

```
<<< 推理打转，主动断流：连续 3 段推理高度重复（相似度 ≥ 0.9）
```

出现频率高说明该模型本身容易兜圈，可以再从「降低思考强度」或「缩短 system 提示」两侧优化。
