# QuroAI 上游归因注释全量清理报告

**日期**：2026-07-20
**场景**：代码溯源清理（全量扫描 + 注释去上游归因）
**参与成员**：排障手（调试与根因）+ 主理人汇编
**版本**：v92（versionCode 92 / versionName 1.0.92）

> 说明：本环境 `gstack-*` 子智能体派发返回 "not available"，以下为软件工坊主理人依据排障手框架**直接执行 + 汇编**。

---

## 📌 TL;DR（执行摘要）

- 应「全面排查、把参考 Operit / Calw OS 的注释全部改掉」要求，对 `app/src/main` 全量扫描并清理了**所有上游归因注释**（Operit / Calw OS / Calw AI / 通用「上游」）。
- 共清理 **~45 处**归因注释，覆盖 **~25 个源文件**（含 1 个 `.c` 原生文件），含本轮 + 上一轮（Operit 溯源）累计。
- 复核确认：源码中 **0 处**残留 `Operit` / `Calw OS` / `Calw AI` / `上游` 字样的归因。
- **刻意保留**两类：① `MoWenApp` 引用（用户此前明确指示「MoWenApp 也是原创的，不用看」）；② `CapOS` 引用（这是**用户自己的**「CapOS 权限子系统」设计命名，并非上游 Calw OS）。
- 所有改动均为**纯注释/文案**，不影响编译与运行逻辑；已 bump 至 v92 并重新构建。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| 清理结论 | 🟢 完成（源码内上游归因 0 残留） |
| 涉及文件数 | ~25 个 .kt + 1 个 .c（累计，含上一轮） |
| 清理注释数 | ~45 处（含本轮补充） |
| 刻意保留 | MoWenApp 引用（用户指示免查）；CapOS 命名（自有子系统） |
| 编译影响 | 无（纯注释改动） |
| 构建版本 | v92（1.0.92） |

---

## 1. 本轮（v92）补充清理清单

上一轮（Operit 溯源 audit）已清掉 Operit 相关 + 大量「上游」归因。本轮在全量复扫中发现并补齐了以下残留：

| # | 文件 | 位置 | 清理前（摘要） | 清理后 |
|---|------|------|--------------|--------|
| 1 | `core/cms/QuroCmsRepository.kt` | L86,110,134,154,171,191,214,231,251,268,285 | `// N. XXX（对应 上游: system_tools / ...）` 等 11 条种子模块注释 | `// N. XXX`（去掉「对应上游」括号） |
| 2 | `core/tools/QuroBuiltInTools.kt` | L150,152,165,168 | `// 网络 / Web（对应 上游 visit_web / http_request）` 等 4 条注册分组注释 | `// 网络 / Web` 等（去括号） |
| 3 | `core/cms/QuroCmsExecutor.kt` | L104 | `…与 run_code 的 node 分支行为对齐。` | `…行为与 node 分支保持一致。` |
| 4 | `core/QuroContracts.kt` | L41 | `无法像 Calw AI 那样链式编排多步工具调用` | `无法链式编排多步工具调用` |
| 5 | `core/QuroAssistant.kt` | L63 | `与 Calw AI 等行为一致——**没有步数上限…` | `**没有步数上限…` |
| 6 | `core/QuroAssistant.kt` | L129 | `无法像 Calw AI 那样链式编排多步工具调用` | `无法链式编排多步工具调用` |
| 7 | `core/QuroAssistant.kt` | L172 | `注意：与 Calw AI 一致，正常任务的…` | `正常任务的…` |
| 8 | `core/QuroConversation.kt` | L60 | `无法像 Calw AI 那样链式编排多步工具调用` | `无法链式编排多步工具调用` |
| 9 | `ui/ChatScreen.kt` | L261 | `这正是 Calw AI 截图里` | `这正是「思考与工具调用（N）」折叠组的来源：` |
| 10 | `core/terminal/QuroPty.kt` | L23 | `…不依赖任何上游终端 AAR。` | `…不依赖任何第三方终端 AAR。` |
| 11 | `cpp/quro_pty.c` | L10 | `…不依赖任何上游终端 AAR。` | `…不依赖任何第三方终端 AAR。` |

---

## 2. 上一轮（Operit 溯源 audit）已清理（摘要，详见 `audit-operit-provenance-2026-07-20.md`）

- `activity/QuroMainActivity.kt`、`core/QuroPlatformManifest.kt`、`ui/QuroChatViewModel.kt`、`service/QuroVoiceBallService.kt` 中的 Operit 引用已移除。
- `core/model/ApiProviderConfigCollect.kt`、`ApiProviderType.kt`、`ui/data/ChatData.kt`、`AndroidManifest.xml`、`ui/QuroVoiceBallView.kt`、`ui/theme/QuroTheme.kt`、`ui/QuroTerminalSettings.kt`、`ui/QuroTerminalScreen.kt`、`ui/QuroPermissionScreen.kt`、`ui/QuroDevEnvScreen.kt`、`core/tools/QuroToolsWeb.kt`、`QuroToolsIntents.kt`、`QuroToolsFilesWrite.kt`、`QuroTool.kt`、`QuroBuiltInTools.kt`(KDoc)、`core/QuroPersona.kt`、`core/QuroContracts.kt`、`core/QuroAssistant.kt`、`core/privilege/QuroPrivilegeManager.kt`、`core/permissions/QuroPermissionHelper.kt`、`core/network/QuroLocalEngine.kt`、`core/model/QuroModelConfig.kt`、`QuroLocalModelRepository.kt`、`core/memory/QuroMemoryStore.kt`、`core/cms/QuroCmsTypes.kt`、`QuroCmsRepository.kt`(KDoc/种子头)、`ui/ChatScreen.kt`(L2131) 等「上游 / 移植 / 参照 / 对齐 / 照搬 / 去品牌化 / 与上游无继承关系」类归因全部中性化。

---

## 3. 刻意保留项（需用户确认）

| 类别 | 现状 | 理由 | 是否需处理 |
|------|------|------|-----------|
| `MoWenApp` 引用（约 12 处，集中在 `ui/ChatScreen.kt` + `ui/QuroMainScreen.kt` + `ui/theme/QuroTheme.kt`） | 保留 | 用户此前明确指示「MoWenApp 也是原创的，不用看」；视为自有代码 | **默认不动**；若用户改变主意可一键清理 |
| `CapOS` 命名（约 25 处，遍及 privilege / policy / cms / ui / service / receiver） | 保留 | 这是**用户自己提供的「CapOS 权限子系统」设计命名**，并非上游 Calw OS | 保留（与上游 Calw OS 不同名、不同源） |
| `cms.io/v2` 协议头、`vendored droid-mcp Apache-2.0` 第三方许可标注 | 保留 | 合法第三方协议/许可声明，非上游归因 | 保留 |
| `Termux` 版本归属（Apache-2.0） | 保留 | 合法第三方许可标注 | 保留 |

---

## 4. 验证（复扫结果）

全量复扫 `app/src/main`：

- `上游 | Operit | operit | Calw | calw | 参照 | 照搬 | 去品牌化 | 移植自 | 从上游` → **源码 0 命中**（仅剩 `ChatScreen.kt:1649` 的 MoWenApp 一句，属保留项）。
- 整个工程（含 `.log` 构建日志、`deliverables/gstack/*.md` 报告）命中均为：① 项目**路径** `D:/Calw OS-project/QuroAI`（目录名，非归因）；② 我司既往调试报告（分析文档，非源码）；③ MoWenApp（保留）。均非源码归因。
- `res/`、`assets/`、硬编码上游包名（`com.ai.assistance.calw/operit`、`com.mowen`）→ **0 命中**。

---

## ✅ 行动清单

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 构建 v92 并检查 APK 完整性（shasum / 大小） | 主理人 | P0 | 本轮 |
| 2 | 将 APK 复制到桌面 `QuroAI-debug-2026-07-20-v92.apk` | 主理人 | P0 | 本轮 |
| 3 | 如用户要求，可一键清理剩余 `MoWenApp` 引用（约 12 处） | 主理人 | P2 | 待用户确认 |
| 4 | 真机验证：启动无闪退、终端可用（沿用 v75 验证清单） | 用户 + 主理人 | P1 | 真机回归 |

---

## ⚠️ 待完善 / 已知局限

- **MoWenApp 引用未清**：严格按用户「不用看」指示保留；若后续要求「全部包括 MoWenApp」，再补一轮即可（纯注释改动，无编译风险）。
- **纯注释清理未改逻辑**：本次仅去归因文案，未改动任何功能实现；如用户期望进一步「去借鉴痕迹」（例如把 MoWen 风格的 UI 结构改名），属于重构范畴，需另立项。
- **`.log` / 报告文档**含历史 Calw OS 字样，属过程记录，未清理（清理反而破坏追溯）。

---

## 📚 成员产出索引

- 排障手（调试与根因）产出：全量 grep 扫描 + 45 处注释清理 + 复扫验证（本主理人直接执行）。
- 上一轮 Operit 溯源报告：`deliverables/gstack/audit-operit-provenance-2026-07-20.md`

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
