# 可视化组键合体聊天气泡 · 富组件自由化（v149）

**日期**：2026-07-22
**场景**：全流程交付（产品设计 + 架构 + 实现 + 构建验证）
**参与成员**：软件工坊主理人（兼产品官 / 设计师 / 实现 / QA）— 本环境 gstack 子代理不可用，由主理人直接编排并落地

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟢 通过（隔离构建 BUILD SUCCESSFUL，APK 已产出并拷至桌面）
- 阻塞项数量：0（构建已通过，无残留编译阻塞）
- 下一步：装 `QuroAI-debug-2026-07-22-v149.apk`，在对话中让 AI 下发 `{"type":"quickreply",...}` 等组件，验证其在聊天气泡内渲染并可交互。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go（构建通过后） |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 0 / 🟢 全部为新增能力 |
| 关键行动项 | 3 条（见下） |
| 建议负责人 | 软件工坊主理人 |

---

## 1. 各成员核心结论

### 🔍 产品官（产品评审）
- 核心判断：用户要的并不是"更多独立卡片栏"，而是**组件成为聊天气泡本身的一部分**——气泡要"自化"。
- 关键建议：把"可视化组键"从对话框底部的独立 `QuroChatCardTray` 通道，升级为**消息一等公民字段 `Message.cards`**，与既有"AI 文本内联 JSON 抽卡"双通道合体进气泡；并让组件能**反向驱动对话**（点建议即回发）。

### 🎨 设计师（设计系统与视觉）
- 核心判断：在 9 种新组件上统一沿用 `MaterialTheme.colorScheme` token（与 v147 重做的卡片栏同套主题语言），深浅主题自适应，不引入新硬编码。
- 关键建议：新增可视化类型覆盖"时间 / 强度 / 对比 / 能力 / 计时 / 轮播 / 看板 / 快捷回复 / 快捷动作"九类，形成"展示 + 操作 + 关联功能"三档能力梯度。

### 🔧 排障手 + 质量门神（实现 / QA）
- 核心判断：渲染复用既有 `QuroChatCardView` 与 `CardShell`；新增类型仅在密封接口 + `parseComponentSpec` + `when` 三处扩展，改动面收敛、低风险。
- 关键建议：组件"自由关联功能"复用既有命令总线——`ui_open_*` 开应用内界面、`run:` 喂终端、`reply:` 经 `handleCardCommand` → `send()` 回发聊天；持久化模型 `QuroMessage` 与 UI `Message` 解耦，加 `cards` 字段**不影响会话存档**。

> 本环境 gstack 子代理（product-reviewer / designer / investigator / qa-lead / security-officer）不可用，以上为同一主理人按各角色框架给出的结论，非多实例并行产出。

---

## 2. 综合审查发现（按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟢 | 功能 | `ui/data/ChatData.kt` | `Message` 新增 `cards: List<QuroChatCard>` 一等公民字段，气泡内渲染 `msg.cards + inlineCards` | 已落地；映射默认空列表，零破坏 | 产品官/实现 |
| 2 | 🟢 | 功能 | `core/cards/QuroChatCard.kt` | 新增 9 种组件密封子类 + `parseComponentSpec` 解析分支 | 已落地 | 设计师/实现 |
| 3 | 🟢 | 功能 | `ui/QuroChatCards.kt` | `QuroChatCardView` 增加 9 分支 + 9 个主题化可交互渲染 Composable + `cardIcon` 映射 | 已落地 | 设计师/实现 |
| 4 | 🟢 | 功能 | `ui/ChatScreen.kt` | `handleCardCommand` 增加 `reply:` 路由 → `send()`；气泡内合并渲染 | 已落地 | 实现/QA |
| 5 | 🟢 | 文档 | `core/tools/QuroToolsUiWidget.kt` | `ui_widget` 描述与错误提示补充 v149 类型 | 已落地 | 产品官 |
| 6 | 🟢 | 构建 | `app/build.gradle.kts` | versionCode/Name 148→149 | 已落地；隔离构建验证中 | QA |

---

## ✅ 行动清单（至少 3 条具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 装 v149 APK，发送"给我三个追问建议 / 做一个能力雷达图"类请求，验证气泡内组件渲染与点击交互 | 用户 / 主理人 | P0 | 构建通过后 |
| 2 | 验证 `quickreply` 点按是否真的回发聊天（`reply:` 路由），以及 `quickaction` 磁贴能否经 `ui_open_*` 打开对应界面 | 主理人 | P0 | 构建通过后 |
| 3 | 若需让 `ui_widget` 工具下发的组件也进入气泡（而非仅底部卡片栏），将工具输出挂载到当前助手消息 `cards` 字段——本期保留双通道，下期可选收敛 | 主理人 | P2 | 后续迭代 |

---

## ⚠️ 待完善 / 已知局限

- 本期 `ui_widget` 工具仍写入全局 `QuroChatCardStore`（底部卡片栏）作为兼容通道；气泡内组件主要由 AI 文本内联 JSON 与（未来的）消息 `cards` 字段驱动。两条通道并存，不冲突。
- `Message.cards` 当前由 UI 层默认空列表提供；核心 `QuroMessage` 持久化模型未携带组件，故历史消息重开时气泡内组件以"文本内联 JSON 重新抽取"方式恢复，已足够。
- 计时器（`timer`）为纯前端交互，倒计时结束回传 `command` 供 AI 后续动作；未做后台保活（切走气泡即重置），属于预期内的轻量交互。
- 构建结果：✅ BUILD SUCCESSFUL（后台任务 `LEeAxf`，日志 `/tmp/v149_build2.log`）；APK 已拷至桌面 `QuroAI-debug-2026-07-22-v149.apk`，旧 v147 已移至 `D:\QuroAI_old_apks_backup`。修复了 2 个编译期错误：`ChatScreen.kt` 的 `reply:` 路由改为内联 `vm.send(...)`（避免局部函数前向引用未解析）；`QuroChatCards.kt` 的 `CompareSideView` 将 `weight(1f)` 上移至 `CompareCardView` 的 `Row` 调用处（避免脱离 RowScope 时 `Modifier.weight` 解析为 Float 而报错）。

---

## 📚 成员产出索引

- 主理人（产品官/设计师/实现/QA）原始产出：本次改动涉及 `QuroChatCard.kt`、`QuroChatCards.kt`、`ChatData.kt`、`ChatScreen.kt`、`QuroToolsUiWidget.kt`、`app/build.gradle.kts` 六个文件，diff 已落盘于工程内。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
