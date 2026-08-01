# ACI SDK 2.0 架构提案 · 产品/架构评审与 Roadmap 校准

**日期**：2026-08-02
**场景**：产品评审 / 架构规划（ACI SDK 2.0 升级提案）
**参与成员**：产品官（gstack-product-reviewer）
**源码基线**：`D:\Calw OS-project\QuroAI`（主仓 commit 7ffeec5）、`D:\ZorvBrowser`（受控浏览器仓）

---

## 📌 TL;DR（执行摘要）

- **总体判断**：🟢 提案方向正确且必要。当前 ACI 是"能力接口"，SDK 2.0 把它升为"平台"的路径是对的；上一轮"治理缺失 > 协议缺失"的结论在此提案中得到延续与放大——**2.0 的本质就是补协议内核 + 治理横切 + 生态**。
- **最关键的单一阻塞项**：协议版本化（`protocol_version` + SemVer + 协商）今天**完全为零**（`Capability.create` 写死 `version="1.0"`）。不先立版本，提案里所有"兼容适配器 / 向前向后兼容 / Major 大版本兼容"都是空谈。**这是 Phase 0 的唯一硬目标。**
- **最大过度设计风险**：一上来铺 4 种传输（IPC/WS/HTTP/gRPC）+ 4 种语言（C/C++/Python/TS/Go）。建议先抽象接口 + 复用已有 Binder，再分期加 HTTP/WS，gRPC 与 C/C++ 延后。
- **需要降噪的两点**：① 跨设备网关是双刃剑（当前 ACI 刻意 local-only），必须默认关 + LAN-only + 临时令牌；② 动态能力热加载在 Android 上坑极多，先做配置驱动清单，真·热卸载放后期。
- **建议分层顺序（比原提案更清晰）**：Kernel（协议+Adapter+治理）→ Transport（可插拔）→ Runtime（任务编排）→ 多语言绑定 → 工具链。Runtime 应建在 Transport 之上，而非并列。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go（方向认可），但需按 Phase 0→4 分期，禁止一步到位 |
| 严重度分布（提案内部） | 🔴 1（协议零版本化，阻塞一切兼容承诺）/ 🟠 2（传输/语言过度铺开、跨设备网关安全）/ 🟡 2（热加载、Runtime 与 Transport 边界模糊） |
| 关键行动项 | 5 条（见行动清单） |
| 建议负责人 | 主程（Kernel 重构）+ 平台组（Transport/多语言）+ 安全官（网关评审） |

---

## 1. 提案逐项核实（源码对照）

### 🔍 产品官（产品评审）

#### A. 提案各模块的"当前具备度"判定

| 提案模块 | 判定 | 源码证据 | 说明 |
|---|---|---|---|
| Runtime 执行层（任务调度/断点/超时熔断/上下文/长会话） | 🔴 净新增 | `QuroMainAciService` 仅 request/response 单次 Binder 调用；`QuroAciManager.call` 无任务态（上一轮核实） | 当前无任何任务编排，纯同步 RPC |
| Transport 可插拔（IPC/WS/HTTP/gRPC） | 🟡 部分具备 | 控制↔受控 = Binder（`IACIService.Stub`，手册:182）；`http_request` 是"受控→第三方"的能力，非控制面传输 | "传输层"今天 = Binder 硬编码，无 Adapter 抽象 |
| 契约与运行时分离 + ACI Adapter 抽象 | 🔴 净新增（根上架构债） | 扩展模型 = `BaseACIService` 直接实现 Binder Stub；无 `Adapter` 接口 | 这正是提案最核心的价值点 |
| 版本化协议 SemVer + 协商 | 🔴 净新增 | `Capability.create` 写死 `version="1.0"`（README:198/手册:191）；SDUI 快照无 `schema_version` | 零版本、零协商，是兼容承诺的前提缺口 |
| 任务原语增强（TaskCreate/Snapshot/Resume/Delegate） | 🔴 净新增 | 无对应代码 | 多智能体集群的前置 |
| 事件订阅模型（被动监听） | 🔴 净新增 | 当前仅主动 `call`；控制台 `console_ui/console_action` 是拉取式（上轮核实） | 需协议版本 bump |
| 分层能力发现（静态+动态热加载） | 🟡 静态已有/动态净新增 | 能力在 `onCreateCapabilities` 静态 `Capability.create` 列表（浏览器侧 28+ 项） | 无注册表、无标签检索、无热加载 |
| 多语言 SDK（C/C++/Python/TS/Go） | 🔴 净新增 | `aci-core` 是 Kotlin/AAR，JVM/Android only | 无原生/脚本绑定 |
| High/Low-level API 分层 + 配置外部化 | 🔴 净新增 | 当前仅一套 API，配置散落 | — |
| 治理横切（凭证/HTTPS 信任/审计/错误模型） | 🔴 净新增（控制端侧） | `http_request` headers 明文传入（`QuroMainAciService:100-147`）；`QuroAciManager.call` 仅 Logcat；`QuroPrivilegeAudit` 只审权限提升 | 上轮 P0，应并进 SDK 内核 |
| 工具链（CLI/可视化编辑器/.aciplug/兼容测试） | 🔴 净新增 | 无 | 生态放大器 |

#### B. 值得肯定的已有资产（别重建）

- **多受控端统一控制台入口**已上线（`QuroAciCenterScreen` 列出所有 ACI App 并「打开控制台」）—— 提案的"多端管理"雏形已有。
- **Adapter 模式已有成功先例**：`QuroBotAdapter` / `QuroDirectBotAdapter`（微信/飞书/QQ 适配器）证明团队已掌握 adapter 抽象，ACI Adapter 应直接复用同一思想。
- **响应体大小处理 / 超时**已在 `http_request` 落地（>2MB 标 truncated、>15万字符 gzip、控制器 15s 硬上限 + OkHttp 分层超时）—— 提案的"传输健壮性"Transport 层不必从零造。
- **SDUI 控制台基础设施**已就绪（`AciConsoleContract` / `AciConsoleModel`），事件订阅与可观测性可在其上自然延伸。

---

## 2. 架构分层澄清（对原提案的修正）

原提案把 Runtime、Transport、协议规范、多语言 SDK 并列罗列，边界略模糊。建议明确为**分层栈**，下层为上层提供契约：

```
┌─ 工具链层   CLI / Manifest 可视化编辑器 / .aciplug 打包 / 兼容测试套件
├─ 多语言绑定  Core(C/C++) · Python · TS/JS · Go   （High/Low-level API + 配置外部化 + 能力注册表）
├─ Runtime 层   任务编排（Create/Snapshot/Resume/Cancel/Delegate）· 超时熔断 · 上下文/长会话 · 事件订阅
├─ Transport 层  ACIAdapter 抽象：Binder(IPC) · HTTP · WebSocket · (gRPC 延后) · 跨设备网关(安全评审后)
└─ Kernel 层    aci-protocol SemVer + 协商 · 契约(Capability/Request/Response/Error) · 治理(凭证/HTTPS信任/审计/错误模型)
```

**关键点**：Runtime 必须建在 Transport 之上（任务跨多次调用/跨传输），而非与 Transport 并列；协议版本化是整个栈的"地基开关"。

---

## 3. 校准后的分阶段 Roadmap（可直接写入 Roadmap）

### Phase 0 — SDK Kernel 地基（必须先做，阻塞一切后续）
- **P0-1 协议版本化**：引入 `aci-protocol` 独立 SemVer（脱离 app version），`Capability`/`ACIRequest`/`ACIResponse` 增加 `protocol_version` 字段；启动协商两端最高兼容版本。落点：`aci-core` 模块。
- **P0-2 Adapter 抽象**：新增 `ACIAdapter` 接口（send/receive/lifecycle）；把现有 Binder 实现下沉为 `BinderAciAdapter`；`BaseACIService` 重构为薄封装，仅桥接 adapter ↔ 能力分发。落点：`aci-core` + `BaseACIService` 重构。
- **P0-3 标准化错误模型**：`ACIError` 增加 `code` + `message` + `llm_fix_hint`（LLM 可解析修复建议），贯通 Binder 与 HTTP 调用。落点：`aci-core`。
- **P0-4 治理横切（并入内核）**：凭证托管（密钥库，非每次 LLM 明文 headers）、HTTPS 自签信任策略（可配置）、控制端 ACI 调用审计（复用 `QuroPrivilegeAudit` 的 JSONL 模式，在 `QuroAciManager.call` 落盘）。落点：`aci-core` + 主仓 `service/`。
- **P0-5 SDUI 快照加 `schema_version`** + `AciConsoleModel` 兼容检查（未知组件当前静默降级为 Text，加版本号后给明确错版提示）。

### Phase 1 — Runtime 执行层（治理稳定后）
- **P1-1 任务原语**：`TaskCreate / TaskSnapshot / TaskResume / TaskCancel / TaskDelegate`（子任务委派，为多智能体铺路）。
- **P1-2 断点续执行**：任务状态持久化（JSONL/ Room），进程崩溃可恢复。
- **P1-3 超时熔断框架**：per-call 超时 + 业务级重试 + 简单熔断（当前仅对 `RemoteException` 重绑）。
- **P1-4 上下文/长会话**：会话级 Context 对象，适配自治 Agent 持续执行。
- 落点：新增 `aci-runtime` 模块。

### Phase 2 — Transport 可插拔 + 事件订阅
- **P2-1 Adapter 实现**：Binder（已有）、HTTP（复用 `http_request` 底座）、WebSocket（新增，跨进程/跨设备长连）；gRPC 延后按需。
- **P2-2 事件订阅模型**：受控端主动推送（文件变更/窗口变化/消息通知），控制端被动监听——需协议 minor bump + 能力 flag。
- **P2-3 跨设备网关（安全敏感，独立评审）**：默认关、仅 LAN、ephemeral token；绝不默认开（当前 ACI 刻意 local-only，`AciConsoleModel` 注释明示）。
- 落点：`aci-transport` 模块 + Adapter 注册表。

### Phase 3 — 多语言 SDK + 分层能力发现
- **P3-1 能力注册表 + 标签检索**；静态 + **配置驱动的动态清单**（真·dex 热卸载放后期，Android 坑多）。
- **P3-2 语言优先级**：Python（社区需求最大）→ TS/JS（WebUI/Node）→ Go（后端/Docker）→ C/C++ Core（桌面原生，工作量最大，最后）。
- **P3-3 High/Low-level API 分层 + 配置外部化 + 热重载**。
- 落点：独立 `aci-sdk-{py,ts,go,core}` 包/仓。

### Phase 4 — 工具链 + 生态
- **P4-1 ACI CLI**（能力清单/本地调用/schema 校验/一键生成模板）。
- **P4-2 Manifest 可视化编辑器（Web）**。
- **P4-3 .aciplug 插件包 + 热安装**。
- **P4-4 兼容性测试套件（CI）**。
- **P4-5 官方高频 App 适配器参考实现**（日历/邮件/短信/文件/地图）。
- **P4-6 能力市场/注册中心**（最远期）。

---

## ✅ 行动清单（关键可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 立 `aci-protocol` SemVer + `protocol_version` 字段 + 启动协商；先把 `Capability.create` 写死的 `1.0` 改为真实版本 | 主程（Kernel） | P0 | Phase 0 起始 |
| 2 | 抽 `ACIAdapter` 接口，Binder 下沉为 `BinderAciAdapter`，`BaseACIService` 重构为薄桥接；复用 `QuroBotAdapter` 模式 | 主程 | P0 | Phase 0 |
| 3 | 治理横切并进内核：凭证托管 + HTTPS 自签信任 + 控制端调用审计（JSONL 落盘于 `QuroAciManager.call`）+ `ACIError` 错误模型 | 主程 + 安全官 | P0 | Phase 0 |
| 4 | SDUI 快照补 `schema_version` + 兼容降级提示 | 前端/主程 | P0 | Phase 0 |
| 5 | 跨设备网关单列安全评审（默认关/LAN-only/临时令牌），不与通用传输同日而语 | 安全官 | P2（前置评审） | Phase 2 前 |

---

## ⚠️ 待完善 / 已知局限

- 提案未给出 Phase 0 之前"过渡兼容"方案：现有 v1.0.x 受控端（写死 `version=1.0`）如何与 2.0 控制端协商？建议在 Kernel 阶段定义"无版本字段 = 视为 v1"的兜底规则，避免存量设备集体失联。
- Runtime 的"长会话/自治 Agent 持续执行"与 Android 后台限制（Doze/电池优化/前台服务）强相关，需单独评估保活策略，提案未触及。
- 多语言 SDK 的 C/C++ Core 若要做"桌面原生跨平台"，需评估与现有 Kotlin `aci-core` 的关系（是源码共享还是协议对齐），避免双份维护。
- 动态能力热加载在 Android 涉及 dex/classloader/签名，建议 Phase 3 先用"配置驱动清单"，真·热卸载放更后期。

---

## 📚 成员产出索引

- gstack-product-reviewer（产品官）原始产出：本文档即产品评审员基于源码核实（`Capability.create` 写死版本、`BaseACIService` Binder 耦合、`QuroBotAdapter` 先例、`QuroAciManager.call` 仅 Logcat、`http_request` 明文 headers 等）产出的 ACI SDK 2.0 架构校准评审与分期 Roadmap。

---

> 本报告由软件工坊 AI 协作生成，关键决策（尤其 Kernel 重构与跨设备网关安全评审）请由工程负责人复核。
