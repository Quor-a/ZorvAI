# 多角色集群（Cluster）

> 一个不可替换的「主持」驱动多角色协作，把复杂目标拆成子任务、点名合适的角色、
> 裁决方案、验收产物，直到闭环。
>
> 集群**不是**一群各自为政的对话，而是宿主 AI 的一组工具 —— AI 在普通对话里
> 说「建个集群研究一下」就会真的启动它。

---

## 1. 能力清单

### 1.1 集群 = 9 个 `cluster_*` 工具

集群挂进现有 `QuroToolRegistry`，AI 直接通过function-calling 调：

| 工具 | 作用 | 关键参数 |
|------|------|----------|
| `cluster_start` | 提交目标，主持接管并推进到闭环 | `goal`(必填)、`acceptance`、`sync` |
| `cluster_status` | 查任务进度：状态、子任务、各节点模型、token | `taskId` |
| `cluster_roles` | 列出所有角色（主持永远在第一位且不可删） | — |
| `cluster_models` | 列出可绑定的模型 | — |
| `cluster_enroll` | 把一张已有人格卡登记为角色 | `personaId`(必填)、`role`、`duties`、`taboos`、`skills` |
| `cluster_remove_role` | 把角色移出集群（人格卡本身保留） | `personaId`(必填) |
| `cluster_bind_model` | 给某个角色换模型 | `personaId`、`modelId` |
| `cluster_host_config` | 改主持的熔断 / 并发参数与其模型 | `maxTurns`、`maxReplan`、`proposeFanout` |
| `cluster_abort` | 中止正在跑的任务 | `taskId` |

### 1.2 状态机

```
IDLE → INTAKE → DISPATCHING → PROPOSING → ARBITRATING
      → EXECUTING → VERIFYING → REPLANNING → CONVERGING
      → CLOSED（达标）/ ESCALATED（熔断）
```

关闭原因 `CloseReason`：`GOAL_REACHED`（达标收工）/ `CIRCUIT_BROKEN`（熔断）/ `USER_ABORTED`（用户中止）/ `UNRECOVERABLE`（不可恢复）。

### 1.3 角色发言实时进对话框

集群事件会投影成**当前对话框的气泡**（senderName 形如「集群 · 研究员」/「集群主持 · 主持」），
不是另开一个窗口，也不是只塞进思维链面板。

投影进来的气泡带`excludeFromLlm = true`：
- **用户看得见** —— 集群的工作过程就在对话里；
- **主 LLM 看不见** —— 避免它以为「已经有人答过了」而偷懒不再回答。

只投影用户该看见的事件（发言 / 裁决 / 产出 / 错误 / 重规划 / 收尾）；
验收标准、点名、模型切换这些内部事件留在思维链面板，否则一次闭环会刷出十几条噪声。

### 1.4 各角色可绑不同厂商，各走自己的通道

每个角色独立绑定模型，`ModelProfile` 自带 `baseUrl` / `apiKey`，
gateway **优先用模型自己的通道**，为空才回退全局「当前」配置。

所以「角色 A 用厂商 1 的模型、角色 B 用厂商 2 的模型」是真的打两个不同端点，
而不是把 B 的模型名发到 A 的端点上。

---

## 2. 怎么用

### 2.1 在对话框里（主要路径）

直接说自然语言即可，AI 会调 `cluster_start`：

> 「用集群调研一下国产折叠屏芯片现状，给我一份对比报告」

长任务建议不要干等，让AI 先拿任务 ID 再查进度：

> 「起个集群做 X」→ AI 调 `cluster_start(sync=false)` 拿 `taskId`
> → 之后「那个集群到哪了」→ AI 调 `cluster_status(taskId)`

### 2.2 设置页（编排）

**设置 → 多角色集群**，四段：

| 段 | 能做什么 |
|----|----------|
| **角色** | 启停、编辑、移出、绑定模型 |
| **添加角色** | 从已有��格卡里选一张，指定专家/评审 + 职责 |
| **主持与熔断** | 主持用的模型、最大轮次、最多重规划、一次点名几个角色 |
| **发起任务** | 直接在页面提交目标，同步等结果 |

### 2.3 配一个「成熟的专家」

角色的深度由**两处相乘**决定，缺一不可：

1. **人格卡**（设置 → 人格）：`角色设定` + `描述` + `表达约束` —— 角色的底子；
2. **集群职责**（设置 → 多角色集群 → 角色编辑）：`职责` / `禁忌` / `技能` —— 集群的分工。

⚠ 只填其一就会得到一个「只有名字的空壳」。角色编辑器会在人格卡为空时给出醒目提示。

可调的推理参数：可见历史轮次、温度、最大输出 token、是否可见其他角色的发言
（关掉可避免从众，评审角色建议关）。

---

## 3. 技术实现

### 3.1 位置

```
app/src/main/java/com/ai/assistance/quro/core/cluster/
├── ClusterEngine.kt        状态机主体：submit() / drive()，事件总线
├── ClusterModel.kt         数据模型：ClusterTask / ClusterNode / RoleProfile / ClusterEvent
├── ClusterModelSource.kt   模型清单采集（当前配置 / 自定义厂商 / 离线模型）
├── LlmGateway.kt           云端 + 端侧调用，含多通道归属
├── RoleRegistry.kt         角色注册表（文件存储 quro_cluster_roles.json）
├── ClusterTools.kt         9 个 cluster_* 工具 + ClusterRuntime 单例
├── ClusterTrace.kt         事件 → QuroAgentTrace（思维链面板）
└── ClusterChatBridge.kt    事件 → 对话框气泡（消息流）
```

接入点三处：
- `core/tools/QuroBuiltInTools.kt` → `ClusterToolSet.registerAll(r)`
- `activity/QuroApplication.kt` → `ClusterRuntime.init(this)` + `ClusterTraceBridge.start(...)`
- `ui/QuroChatViewModel.kt` → `ClusterChatBridge.registerSink(store, engine)`（`onCleared` 解绑）

### 3.2 两个必须遵守的时序

**① `cluster_*` 必须先于 `ToolCapabilityDirectory.install()` 注册。**
目录与 RAG 索引都以 install 那一刻的 `fullSpecs()` 为快照；晚注册的工具不进目录，
`match_intent` 意图检索永远召不回 `cluster_start`
（`list_tools` / `get_schema` 走 `allSpecs` 实时集，所以只有「按意图找」这条路会失效，
表现为「明明有集群功能，助手却说不会」）。

**② `cluster_*` 必须在 `QuroToolRouter.categorize()` 里有分类规则。**
`categorize()` 结尾是 `else -> null`，漏规则 = 工具从路由索引消失
（`ToolRagCoverageTest > 没有工具在分类推断里落空` 会直接失败）。

### 3.3 写入路径要批量，不能逐条

集群一次闭环可能产生十几条发言。逐条 `store.add()` = 十几次 `onMutated`
→ 十几次落盘 + 十几次主线程重组 —— 这正是历史 ANR / OOM 风暴的成因。
`ClusterChatBridge` 统一走 `store.addAll()`，只触发**一次**回调。

---

## 4. 关键设计决策

**为什么集群是工具而不是新框架？**
宿主已是 tool-first 架构（一切皆 `QuroTool`）。集群若外挂一套框架，
AI 看不见它、也无法调。做成工具后 AI 在普通对话里就能启动，且天然支持云端/端侧。

**为什么主持不可替换？**
集群需要一个中立的仲裁者。若主持可换，多个角色互相表决就退化成群聊。
`RoleRegistry` 三道锁：固定 ID `persona_cluster_host`、`delete` 直接抛异常、
`upsert` 强制回写身份字段。

**为什么角色发言不进主 LLM 上下文？**
它们已在各自引擎上下文跑完。塞回主 LLM 既浪费 token，
又会让它误以为「集群已经替我回答过了」而不再自己干活。
故用 `excludeFromLlm = true`，与`hidden`（用户看不见、LLM 看得见）语义相反，两个都不能省。

**为什么集群角色不发工具？**
见 `LlmGateway.callCloud`：`enableTools = false`。N 个角色 × 259 个工具会撑爆上下文；
工具由主持统一调度。

---

## 5. 存储位置

| 内容 | 位置 |
|------|------|
| 角色注册表 | `filesDir/quro_cluster_roles.json` |
| 人格卡 | `filesDir/quro_personas.json` + `SharedPreferences("quro_persona")` |
| 对话历史 | 集群气泡随对话一起落盘（经`onMutated` 钩子） |

---

## 6. 已知约束

- 集群运行时是**进程级单例**，对话框 store 是**会话级**；切会话时重新绑定，
  否则发言会落到上一个会话里。
- 旧集群的 Gradle 模块（`:cluster-model` / `:cluster-bridge` / `:cluster-engine` /
  `:cluster-storage` / `:cluster-ui` / `:cluster-fake`）已停止被app 源码引用，
  仍include 在 `settings.gradle.kts` 里，属遗留死重（`:cluster-storage` 传递提供
  SQLCipher，摘除需单独评估）。
- 端侧模型并发闸门固定为 1（串行），避免 OOM。