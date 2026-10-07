# -*- coding: utf-8 -*-
"""原子追加 R7-20（集群 UI 重构）到 2026-10-06.md。"""
import os

PATH = "D:/WyDownloads/2026-08-28-00-11-37/.workbuddy/memory/2026-10-06.md"
TMP = PATH + ".tmp"

SECTION = """
## R7-20 · 多角色集群 UI 重构（截图打回后的返工）

> 用户看截图后原话：「完全乱来你这个一半功能都对不上」+ 逐条列出主持/角色/技能/模型诉求。
> 截图里那个只有「三个 chip + 目标框 + 开始」的面板确实对不上。

### 🔴 决定性发现：引擎层早就实现了，缺的是 UI

排查后确认**用户要的 8 项能力引擎层 100% 已实现**，只是 `ZuroClusterSheet` 是个 595 行薄壳
只做了「建集群 + 下目标 + 开始」，其余能力**有引擎没入口，等于没做**：

| 用户诉求 | 早已存在的实现 |
|---|---|
| 每个 AI 有身份/角色/提示词/职责 | `AgentConfig.identity/persona/systemPrompt/duties/taboos/triggerKeywords` |
| 每个角色挂自己的 skills | `AgentConfig.skillIds` + `SkillLoader` 三级披露 L1/L2/L3 |
| 每个角色不同模型 | `AgentBinding.hostModelId` + `fallbackChain`（宿主模型配置 ID） |
| **同模型但每个角色独立请求** | `ModelBinding.params`（temperature/topP/…）+ `ContextStrategy`（历史窗口/黑板白名单/上下文上限） |
| 以上可改可 AI 自创 | `updateAgent` / `forgeAgentDraft` / `installSkill` |
| 主持身份不可改但技能能力可改 | `HostIdentity` 私有构造+全 val+init 校验；`HostConfig` 承载可变部分 |
| 主持任务不结束不能下班 | `HostIdentity.oath` 四条禁令 + `TerminationPolicy` 五种下班方式 |
| 主持点名 + 驱动提案再执行 | `HostAgent.drive` / `Router.pick` / `proposeFanout` / `Verdict` |

**结论：这不是新增能力，是接线。** 以后遇到「AI 查不到 / 功能对不上」类反馈，
先查引擎层有没有现成 API，再决定是写引擎还是补 UI —— 顺序反了就是白干。

### 引擎侧补的真实缺口（不是 UI 问题）

1. 🔴 **`ClusterEngine.clusters()` 只返回内存 `recentClusters`**：
   旧实现 `recentClusters.toList().mapNotNull { store.cluster(it) }`，
   而 `recentClusters` 是进程内 `LinkedHashSet` —— App 一重启就空，
   用户建过的集群全部消失，表现为「明明建过，列表却是空的」。
   改：新增 `ClusterStore.allClusters()`（Room 侧 `SELECT * FROM clusters ORDER BY updatedAt DESC`
   本来就存在，只是没提到接口上）+ `ClusterEngine.tasks(clusterId)`（新增 `TaskDao.tasks` 查询）。
2. 🔴 **默认集群一个工具都没挂**：三个角色全是光身份，模型能规划但落不了地。
   加 `DEFAULT_AGENT_TOOLS`（研究员→web_search/read_url/knowledge_rag_search；
   写手→read_text_file/write_file/aiwps_create；校对→read_text_file）。
   工具名**实测核实**（`aiwps_create` 而非猜的 `create_doc`）。
3. 补 `triggerKeywords`（决定 Router 何时选它）、`duties` 扩项、`termination` 默认值。

### UI 重构：三个文件

- `ZuroClusterSheet.kt`（重写）：三层导航 = 集群列表 → 控制台 → 编辑器。
  主持卡 + 角色卡横排（角色卡显示 emoji/名字/职责/模型/技能数/工具数/AI 徽标 + 启停开关），
  多集群切换条，支持建空白集群。
- `ZuroClusterEditors.kt`（新建）：**八项属性全可改**的编辑器 +
  主持设置面板 + 通用控件（ListField/ModelPicker/ParamsEditor/ContextEditor/
  SkillPicker/PolicyEditor/OrchestrationEditor/TerminationEditor/ForgeDialog）。
- `ZuroClusterTaskPane.kt`（新建）：执行可视化 —— 子任务行（**点归派人可手动改派**，
  发 `UserCommand.Reassign`）、发言气泡（主持气泡视觉上必须明显不同：primary 底 + 边框 + 🎯，
  否则用户分不清哪句是裁决哪句是执行）、token 账本、状态机中文名（`INTAKE`→「接收目标」）。

### 关键设计决策

- **主持身份在 UI 上是「不渲染输入框」而非「禁用输入框」**：
  `HostIdentity` 物理上改不了，界面就给一份只读展示（连同 oath 全文），
  让用户看清「不可改的是什么」，而不是让他白试。
- **`describeCreateFailure` 从 ZuroClusterSheet 移到 Editors 文件**并改为 `internal`：
  两个文件都要用，放同一包即可，不必 public。
- **编辑后必须 `reloadAgents`**：主持的 `Router` 读的是角色的 duties/triggerKeywords，
  不刷新则新配置不生效。
- `forgeAgentDraft` 起草后**直接进编辑器让用户改**，不自动落库（AI 创的东西必须用户点头）。

### 编译踩坑（7 类，Kotlin + Compose）

1. **中文文案里的英文双引号** `"不知道"` 会把 Kotlin 字符串提前结束
   → 一律用中文书名号 `「」`。报错形态：`Literals must be surrounded by whitespace`。
2. **`ClusterId?` 传不进要 `ClusterId` 的函数**：Compose 的 `when` 分支里编译器不做 smart-cast 到 lambda。
   → 在分支开头 `val active = current!!` 取一次非空局部量给所有闭包用。
3. **`cid` 定义在 `when` 分支内**，分支外（含末尾的编辑器 sheet）访问不到
   → 提到组件顶层 `val cid = current?.id`。
4. **`suspend fun` 不能在 `@Composable` 函数体里直接调**（`engine.hostModels()`/`skills()`）
   → 用 `var hostModels by remember {}` + `LaunchedEffect(Unit)` 预加载成缓存。
5. `return@let` 在 Compose 的 trailing lambda 里**非法**（`'return' is prohibited here`）
   → 改 `if (clusterId != null) { ... }`。
6. **英文 i18n 里的裸撇号** `other roles' utterances` 被 aapt 当转义 → 报
   `Failed to flatten XML ... Invalid unicode escape sequence`；改成 `utterances from other roles`。
7. `HostModelInfo.displayName` 是**属性**不是函数（`displayName()` 报 `cannot be invoked as a function`）；
   `Cluster` 领域模型**没有** `updatedAt`（只有 `ClusterEntity` 有，排序是存储层的事）。

### 验证

- `:app:compileFullDebugKotlin` 从 **27 个错 → BUILD SUCCESSFUL**（1m44s）。
- 新增 `ClusterModelContractTest` **12 例全过**：八项属性互不干扰、同模型不同请求参数隔离、
  降级链独立、主持单例、主持能力全可改、**下班方式只有五种且无 ABANDON**、
  终止策略四道上限齐备、重启不丢、删除不残留。
  刻意断言 `CloseReason.entries.size == 5` 且名字不含 `ABANDON`/`GIVE_UP` ——
  「主持不能自己说『我尽力了』就结束」这条必须被钉死。
- 全量 **1225 例 / 13 失败 = 基线 13**，零新增（1212 + 13 = 1225 对得上）。
- i18n 追加 **91 键 × 2 语言 = 182 条**（qk_03961~qk_04051），脚本 `scripts/append_cluster_i18n.py`，
  原子写 + 复核「前 200 字节完好 + 新键齐全」。
- 测试里的内存存储**手写 `ClusterStore` 实现**，不给 app 加 `cluster-fake` 依赖
  （`cluster-fake` 未被 app 依赖，加依赖会动 build.gradle.kts，代价大于收益）。
"""

with open(PATH, "rb") as f:
    raw = f.read()

assert b"R7-20" not in raw, "R7-20 已存在，不要重复追加"
assert b"R7-19" in raw, "前文（应含 R7-19）被破坏，中止"

body = raw.decode("utf-8")
if not body.endswith("\n"):
    body += "\n"

with open(TMP, "w", encoding="utf-8", newline="\n") as f:
    f.write(body + SECTION)
os.replace(TMP, PATH)

check = open(PATH, "rb").read()
assert check.endswith(b"\n")
assert b"R7-19" in check and b"R7-20" in check
assert check.startswith(raw[:200])
print("OK size", len(raw), "->", len(check))
