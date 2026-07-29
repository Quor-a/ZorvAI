# 功能差距分析：alian-android 基准对照 QuroAI

**日期**：2026-07-25
**场景**：功能差距分析（Feature Gap Analysis）— 开源项目基准对标
**参与成员**：主理人（直接执行，本环境 gstack-* 子代理不可用）+ 调查员（代码核实）
**基准对象**：`github.com/xlb1130/alian-android`（原生 Android + Kotlin，VLM 手机自动化助手）
**被测对象**：QuroAI（`D:\Calw OS-project\QuroAI`，v257）

---

## 📌 TL;DR（执行摘要）

- **整体结论**：🟡 部分缺口 — QuroAI 在「MCP 服务端 / 插件运行时(CMS) / 权限分级」上**领先** alian；但在 alian 的**核心卖点——VLM 屏幕理解 + 通话式交互（视频/语音/手机通话）**以及**轻量技能路由层**上**明显缺失**。
- **阻塞项数量**：0（非阻塞，属功能增强路线）
- **最关键缺口**：① VLM 屏幕理解闭环（摄像头帧/截图 → 视觉模型 → 自动操作）；② 通话式交互架构（全双工 AEC 语音 + 轮次打断管理）；③ 轻量 skills.json 能力路由层。
- **下一步**：优先补齐「VLM 屏幕理解 + 通话模式 UX」这一 alian 差异化的核心；MCP 客户端补齐 WS/SSE 与握手以接入现代外部服务器。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟡 条件 Go（非阻塞，建议列入路线图的 P1 增强） |
| 严重度分布 | 🔴 0 / 🟠 3 / 🟡 2 / 🟢 2 |
| 关键行动项 | 5 条 |
| 建议负责人 | QuroAI 工程负责人（架构层）；主理人协调查距 |

---

## 1. 各成员核心结论

### 🔍 主理人（基准对标与路线建议）
- 核心判断：alian 的差异化不在「工具数量」而在**「看懂屏幕 → 自动操作」的交互范式**：VLM 周期性理解画面 + 通话式全双工语音 + 轮次打断管理 + 轻量技能路由。QuroAI 已具备自动化**底层原语**（无障碍 / Shizuku / 设备管理员 / ROOT / 应用内 Linux），但**缺少把原语串成「视觉理解→决策→执行→验证」闭环的上层编排与通话式 UX**。
- 关键建议：不要照搬 alian 的 skills.json（静态 23 条预设），而应以 QuroAI 已有的 **CMS 插件运行时**为底座，补「VLM 视觉理解层 + 通话模式外壳 + 轻量意图→能力路由」三层，形成比 alian 更强的动态能力体系。

### 🔧 调查员（代码核实结论）
- 核心判断：经源码扫描（`grep` 全仓 + 关键文件读取）确认——
  - QuroAI **已有** `core/mcp/`（客户端 `QuroMcpClient` + 服务端 `QuroMcpHttpServer`/`QuroLocalMcpServer`/`QuroLocalMcpManager`/`QuroLocalMcpDispatcher` + `InProcessTransport`）、`QuroMcpService`、`QuroMcpSettingsScreen`、`ui/QuroPermissionScreen`、`core/permissions/QuroPermissionHelper`、`core/privilege/QuroPrivilegeManager`、`core/cms/*`。
  - QuroAI **确无**：`camera/Camera2` VLM 帧循环、`AEC/全双工语音`、`activateTurn/interruptCurrentTurn` 轮次管理、`PhoneCallAgent`、任何 `VideoCall/VoiceCall/PhoneCall` 屏幕、`skills.json`/`SkillManager`。
- 关键建议：缺口集中在「视觉理解闭环」与「通话式交互外壳」两块；MCP 与权限框架本身已具备，仅需**深度补强**而非从零建设。

> 本环境 gstack-* 子代理不可派发，以上由主理人依据调查员核实结果直接汇编。

---

## 2. 综合审查发现（去重合并后按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟠 | 交互范式 | `core/` 缺失 VLM 闭环 | 无「摄像头帧/截图 → VLM → 自动操作」循环。alian 的 `VideoCallViewModel` 每 5s 送帧给 `VLMClient`(qwen-vl-max)，并把识别结果注入下一轮对话；QuroAI 无任何视觉理解入口。 | 新增 `QuroVisionLoop`：采集屏幕（MediaProjection/无障碍截图）→ 周期送 VLM → 结果作为上下文注入 `QuroAssistant`。 | 主理人/调查员 |
| 2 | 🟠 | 语音架构 | `core/` 缺失 AEC 全双工 | 无 `AecVoiceCallAudioManager` 等价物。alian 用 AEC 防自身 TTS 回声被重新识别，实现全双工；QuroAI 仅有 STT+TTS（见 `QuroVoiceBallService`），缺回声消除与全双工。 | 引入 AEC 音频管理（WebRTC AEC 或自带滤波），让通话模式下 AI 边说边听不被自己回声打断。 | 主理人 |
| 3 | 🟠 | 交互管理 | `core/` 缺失轮次打断 | 无 `activateTurn/completeTurn/interruptCurrentTurn/isStaleTurn` 轮次管理。alian 用其处理用户中途插话（barge-in）；QuroAI 无对应对话轮次状态机。 | 在 `QuroChatViewModel` 增加 Turn 状态机，支持打断当前 TTS/生成并切换上下文。 | 主理人 |
| 4 | 🟡 | 技能路由 | `assets/` 缺失 skills.json | 无轻量「意图关键词 → 应用委派/ GUI 自动化」预设层（alian `skills.json` 含 23 条：打车/外卖/导航/发消息…）。QuroAI 有重负载 `core/cms/*` 插件运行时但**未以用户可编辑的能力预设形式对外暴露**。 | 以 CMS 为底座封装「能力预设」层（意图→插件/应用委派），在设置页提供 Skills 管理 UI，避免与 alian 静态 JSON 同质化。 | 主理人 |
| 5 | 🟡 | MCP 客户端 | `core/mcp/QuroMcpClient.kt` | 客户端仅支持 plain-JSON HTTP POST，**未实现 `initialize` 握手、无 `mcp-session-id` 跟踪、不支持 WebSocket / SSE（streamable HTTP）**。alian `MCPClient` 已支持 WS+StreamableHTTP+SSE 三传输 + 握手。 | 给 `QuroMcpClient` 增加握手与 `mcp-session-id`，并补 WS/SSE 传输以接入现代 MCP 服务器。 | 调查员 |
| 6 | 🟢 | 屏幕自动化 | `core/agent/` 缺失 PhoneCallAgent | 无 `Manager→Executor→Reflector` + VLM 验证的多步自动化循环（alian `PhoneCallAgent` 每步截图→VLM 验证）。QuroAI 已有无障碍/Shizuku/ROOT 原语，缺**编排闭环**。 | 将现有自动化原语封装为「规划→执行→视觉验证」循环，复用 CMS 执行引擎。 | 主理人 |
| 7 | 🟢 | 权限策略门控 | `core/privilege/QuroPrivilegeManager.kt` | alian `PermissionManager` 把权限状态与执行策略（shizuku_only/accessibility_only/hybrid/auto）绑定为 `ExecutionPromptInfo`；QuroAI 用 L1–L5 分级授权，模型不同但目标一致，**非阻塞**。 | 评估是否把「执行策略 gating」显式化到 CMS 工具调用前检查，提升透明性。 | 调查员 |

---

## ✅ 行动清单（具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 新增 `QuroVisionLoop`：MediaProjection/无障碍截图 → 周期 VLM 推理 → 结果注入对话上下文 | QuroAI 工程 | P0 | v260 前后 |
| 2 | 引入 AEC 全双工音频管理 + Turn 轮次状态机（支持 barge-in 打断） | QuroAI 工程 | P0 | v260–v262 |
| 3 | 在 `QuroMcpClient` 补 `initialize` 握手 + `mcp-session-id` + WebSocket/SSE 传输 | QuroAI 工程 | P1 | v261 |
| 4 | 以 CMS 为底座封装「能力预设/技能路由」层 + 设置页 Skills 管理 UI | QuroAI 工程 | P1 | v262–v264 |
| 5 | 将自动化原语封装为「规划→执行→VLM 验证」闭环，复用 CMS 执行引擎 | QuroAI 工程 | P2 | v264+ |

---

## ⚠️ 待完善 / 已知局限

- 本分析基于 alian-android `main` 分支源码快照（2026-07-25 拉取）与 QuroAI v257 源码扫描；alian 未提供等价「MCP 服务端」「插件运行时」，**QuroAI 在此两项事实上领先**，差距分析已据此校正（原假设「QuroAI 完全没有 MCP/权限」已被源码证伪）。
- QuroAI 的 `QuroMcpClient` 已能对接「外部 MCP 服务器」（remote/local），与 alian 的客户端目标一致，仅传输深度不足。
- VLM 闭环需评估模型来源（云端 VLM API 或端侧多模态模型）与隐私/资费影响，建议先以云端 VLM 打通链路。
- 通话式 UX 涉及常驻悬浮窗 + 前台服务 + 录音/相机常驻，需在设置中明示授权与耗电提示，遵循现有 L1–L5 授权框架。

---

## 📚 成员产出索引

- 主理人（基准对标与路线建议）：本文件第 1、2、✅ 节直接汇编。
- 调查员（代码核实）：全仓 `grep`（MCP / skills / VideoCall|VoiceCall|PhoneCall / 权限 / camera / AEC / Turn）+ `QuroMcpClient.kt` 头注释 + alian `skills.json`/`PermissionManager.kt`/`PhoneCallAgent.kt` 抓取比对。
- alian-android 参考文件（只读）：`VideoCallViewModel.kt`、`MCPClient.kt`、`skills.json`、`PermissionManager.kt`、`PhoneCallAgent.kt`（经 GitHub API/raw 获取）。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
