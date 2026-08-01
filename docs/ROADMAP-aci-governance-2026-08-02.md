# ZorvAI ACI HTTP/治理层升级 · 产品评审与路线图校准

**日期**：2026-08-02
**场景**：产品评审 / 架构规划（ACI 治理能力与生态短板校准）
**参与成员**：产品评审员（基于主仓 `D:\Calw OS-project\QuroAI` + 受控仓 `D:\ZorvBrowser` 源码核实）

> 本文是对用户提交的「ACI HTTP 传输层 + 治理层 + 跨设备 + 可观测性」缺口分析与 P0–P3 路线图的**逐项源码核实与校准**。核实铁律：先读源码再下结论，不凭空断言。

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟡 有条件通过 —— 用户的"协议层已完整、短板在治理与生态"**总判断成立**，但其中 3 项需要修正/补正。
- 已落地却被低估的：① **响应体大小处理已完备**（2MB 硬上限 + 15万字符截断 + gzip，与主程序 `browser_read` 切片同源思想）；② **超时已具备**（控制器 15s 硬上限 + OkHttp 10/14/14s）；③ **多受控端统一控制台入口已上线**（`QuroAciCenterScreen` 已列全部 App + 每 App「打开控制台」）。
- 真正的 P0 净新增缺口：**凭证托管**、**HTTPS 自签信任策略**、**控制端 ACI/HTTP 调用审计**（现有 `QuroPrivilegeAudit` 只审计"权限提升"，不审计 ACI 调用）。
- 反向 HTTP 入口是**主动的安全非目标**（本地-only 设计），若要上必须做安全评审 + 仅 LAN 开启，不能默认开。
- 下一步最该先做（低风险高价值）：**控制端 ACI 调用审计**（复用现有审计基础设施模式）+ **SDUI 快照加 `schema_version` 字段**。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟡 条件 Go（治理层补强，非协议重建） |
| 严重度分布 | 🔴 3（凭证托管 / HTTPS 自签 / 控制端审计） / 🟠 1（SDUI 版本协商） / 🟡 2（错误模型结构化 / 可观测性） / 🟢 3（响应体大小 / 超时 / 多控台入口 已具备） |
| 关键行动项 | 7 条（见行动清单） |
| 建议负责人 | 主程序 ACI 团队（QuroMainAciService / QuroAciManager / QuroPrivilegeAudit 维护者） |

---

## 1. 用户 9 点分析 · 源码核实结论表

| # | 用户关切 | 判定 | 源码证据 | 建议优先级 |
|---|---------|------|---------|-----------|
| 1 | 鉴权与凭证托管（Bearer/API Key/Cookie 持久化，而非每次 LLM 传明文） | 🔴 确为缺口 | `QuroMainAciService.kt:100-105,135-147`：`headers` 为调用时传入的 JSON 字符串，无密钥库；LLM 必须每次带明文 | P0 |
| 2 | HTTPS 自签信任策略可配置 | 🔴 确为缺口 | `QuroMainAciService.kt:128-133`：`OkHttpClient` 默认构造，无自定义 `SSLContext`/`HostnameVerifier`；`network_security_config` 只解决 LAN 明文，不解决自签证书 | P0 |
| 3 | 请求审计（URL/方法/状态码/耗时持久化） | 🔴 控制端缺失 | `QuroAciManager.kt:279,308,312`：`call()` 仅 `Log.d/w`（Logcat 易失）；`QuroPrivilegeAudit.kt` 仅记录权限提升，不记录 ACI/HTTP 调用。浏览器侧有 `browser_audit`（受控端自记） | P0 |
| 4 | 控制台操作日志持久化 / 事后回放 | 🔴 控制端缺失 | `QuroAciCenterScreen.kt:375-409`：`openConsole`/`consoleAction` 不落盘；无回放 | P1 |
| 5 | SDUI 快照版本兼容策略 | 🟠 部分缺失 | `AciConsoleContract.kt` + `AciConsoleModel.kt:44-74`：快照 JSON **无 `schema_version` 字段**；解析对未知组件"降级为 Text"（`else -> Text("[未知组件]")`），属"宽容退化"而非"版本协商"，无校验器 | P3（加字段即可，低成本） |
| 6 | 反向 HTTP 入口（云端/他设备→本机控制端） | 🔴 主动非目标 | `AciConsoleModel.kt:14-16` 注释明确"本地、离线渲染"，早期 LAN 远程控制台范式已移除；当前为安全本地-only 设计 | P2（须安全评审+仅 LAN） |
| 7 | 超时/重试/熔断 | 🟡 超时已有、重试/熔断无 | `QuroMainAciService.kt:107-133`：`HARD_TIMEOUT_S=15` 硬上限 + OkHttp connect 10s/read 14s/write 14s；`QuroAciManager.kt:329` 仅对 `RemoteException` 重绑重试，无业务级重试/熔断 | P0（补重试/熔断） |
| 8 | 响应体大小处理（截断/gzip/分块） | 🟢 已完备 | `QuroMainAciService.kt:177-207`：Content-Length>2MB 不载入内存直接标 `truncated`；响应>15万字符截断 + `response_body_gz`（gzip，≤900KB 才附）；与 `browser_read` 切片同源思想 | —（已达标，勿重复造） |
| 9 | 可观测性（LLM 编排轨迹/调用链/耗时 SDUI 化） | 🟡 控制端缺失 | `console_ui`/`console_action` 契约面向"操作受控端"（`AciConsoleContract.kt`）；受控端有 `browser_audit`/`browser_events`，但控制端编排观测视图无 | P1 |

---

## 2. 对用户 P0–P3 路线图的校准

### P0 —— 协议与治理层补全（维持，但细化落地点）

1. **凭证托管**（净新增，落点 `QuroMainAciService.handleHttpRequest` + 新建 `QuroCredentialStore.kt`）
   - Android Keystore 加密 + DataStore 持久化；`http_request` 增 `credential_id` 参数引用托管密钥，`Authorization` 等头由后端注入，LLM 只见 `credential_id` 不见明文。
   - 复用既有 `libs.okhttp`；密钥绝不出现在 `params` 明文或 Logcat。

2. **HTTPS 自签信任策略**（净新增，落点 `QuroMainAciService.doHttp`）
   - 增 `tls_mode` 参数：`system`（默认）/ `pin_sha256:<hash>` / `insecure_lan`（仅放行私有网段 192.168/10/172.16-31/127，公网域名拒绝）。自定义 `SSLContext` 仅在此三态切换时构造，杜绝"全局信任所有证书"。

3. **标准化错误模型**（部分已有，补结构化修复建议）
   - `aci-core` 的 `ACIError` 已有 `REQUEST_NULL/BAD_REQUEST/CAPABILITY_NOT_FOUND/INTERNAL_ERROR` 码（`QuroMainAciService.kt:96,114,166`），贯通 Binder+HTTP。缺的是**LLM 可解析的 `remediation` 字段**（结构化修复提示）。补一个 `error→remediation` 注册表即可。

4. **控制端 ACI 调用审计**（最大真实治理缺口，落点 新建 `QuroAciCallAudit.kt` + 扩展 `QuroAuditScreen`）
   - 直接复用 `QuroPrivilegeAudit` 的 JSONL/ring-buffer 模式（`QuroPrivilegeAudit.kt:28-66`），但记录对象改为每次 ACI 调用：包名 / 能力 / 参数哈希 / 状态码 / 耗时ms / 时间戳。在"CapOS 审计"页新增"ACI 调用"Tab。这同时闭合用户第 3、4 点的控制端侧诉求（浏览器侧 `browser_audit` 已解决受控端侧）。

### P1 —— 控制台能力进化

1. **多受控端统一控制台入口**：⚠️ **已上线**，用户低估。`QuroAciCenterScreen.kt:547-573` 已列出所有发现的 ACI App，每张卡 `onOpenConsole` 拉取该 App 的 `console_ui` 渲染。下一步是体验增强（记住每 App 上次快照、免重开 Dialog 切换），而非从零建设。
2. **控制台内可观测性视图**：复用 SDUI，新增"编排观测"能力或本地屏读取 `QuroAciCallAudit`，以组件化 JSON 渲染调用链/耗时/成功率/LLM 决策轨迹。
3. **控制台操作回放与导出**：基于 `QuroAciCallAudit` 记录，支持重放某次 `console_action`/`http_request` 并导出 JSON。

### P2 —— 生态与跨设备

1. **官方高频适配器**（日历/邮件/短信/文件/地图）：注意与现有 CMS 引擎、终端沙箱有重叠，优先补"浏览器之外最高频且无替代"的受控端。
2. **ACI 2.0 跨设备 HTTP 网关**（反向入口）：**必须显式Gate**——默认关闭、opt-in、仅绑定 LAN、用一次性 ephemeral token 鉴权、走安全评审。当前本地-only 是有意安全设计，不等于"没做"，而是"刻意不做"。
3. **能力市场/注册中心**：发现服务，优先级最低。

### P3 —— 开发者体验

1. **SDUI 快照 Schema 版本兼容校验器**（低成本高价值的那一项）：`AciConsoleModel` 快照加 `schema_version` 字段 + 一个兼容检查函数（CI lint）。当前"未知组件降级为 Text"已具韧性，加版本号即可让受控端/控制端错版时给明确提示而非静默退化。
2. **ACI 描述校验器**（LLM 友好规范）CI 集成。
3. **Mock 控制端 / 单元测试框架**。
4. **IDE 插件**：一键生成 `BaseACIService` + `ConsoleBackend` 骨架（现有 `ACI_STUB_SOURCE` 模板已可下载，插件化是自然延伸）。

---

## ✅ 行动清单（按"现在就能做 / 需排期 / 须评审"分层）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 控制端 ACI/HTTP 调用审计：新建 `QuroAciCallAudit`（复用 `QuroPrivilegeAudit` 模式），`QuroAciManager.call` 落盘调用记录；审计页加 Tab | 主程序 ACI 团队 | P0（现在可做，低风险高价值） | 下个补丁 |
| 2 | SDUI 快照加 `schema_version` 字段 + `AciConsoleModel` 兼容检查（CI lint） | 控制台维护者 | P0（现在可做，低成本） | 下个补丁 |
| 3 | 凭证托管：`QuroCredentialStore`（Keystore+DataStore）+ `http_request` 增 `credential_id` | 主程序 ACI 团队 | P0（需设计+安全评审） | 排期 |
| 4 | HTTPS 自签信任：`doHttp` 增 `tls_mode`（system/pin_sha256/insecure_lan，仅私网放行） | 主程序 ACI 团队 | P0（需安全评审） | 排期 |
| 5 | 错误模型补 `remediation` 结构化字段（LLM 可解析修复建议） | aci-core 维护者 | P1 | 排期 |
| 6 | 框架级重试/熔断语义（业务级，非仅 RemoteException 重绑） | 主程序 ACI 团队 | P1 | 排期 |
| 7 | 反向 HTTP 网关：默认关、opt-in、仅 LAN、ephemeral token，先过安全评审再动工 | 安全官 + 架构 | P2（须评审） | 评审后 |

---

## ⚠️ 待完善 / 已知局限

- 本次评审仅核实**主程序** `QuroMainAciService`/`QuroAciManager`/`QuroAciCenterScreen`/`AciConsoleModel` 与受控仓 `http_request` 文档描述（docs 第 241–253 行）。未在真机跑 `http_request` 四例（GET/POST/大响应/超时）验证，建议与 v1.0.14 真机验证一并闭环。
- 受控端（浏览器）侧已有 `browser_audit`/`browser_events` 的"受控端可观测性"，本文聚焦"控制端治理缺失"，不重复评估受控端。
- 反向 HTTP 网关若实施，需同步修订 `AciConsoleModel` 的"本地-only"设计注释与安全风险文档。

---

## 📚 成员产出索引

- 产品评审员（gstack-product-reviewer）原始产出：本文件即评审结论（主理人直接执行，因本环境 TeamCreate 不可用，按降级策略主理人亲核源码并汇编）。
- 源码证据索引：
  - `app/src/main/java/com/ai/assistance/quro/service/QuroMainAciService.kt`（http_request 实现：100-224）
  - `app/src/main/java/com/ai/assistance/quro/core/aci/QuroAciManager.kt`（call 仅 Logcat：279/308/312；RemoteException 重绑：329）
  - `app/src/main/java/com/ai/assistance/quro/ui/QuroAciCenterScreen.kt`（多控台入口已上线：547-573）
  - `app/src/main/java/com/ai/assistance/quro/core/aci/AciConsoleModel.kt`（快照无版本字段，宽容解析：44-74）
  - `app/src/main/java/com/ai/assistance/quro/core/aci/AciConsoleContract.kt`（契约说明）
  - `app/src/main/java/com/ai/assistance/quro/core/privilege/QuroPrivilegeAudit.kt`（权限审计，非 ACI 调用审计）
  - `docs/ACI_DEVELOPER_GUIDE.md`（http_request 文档：241-253；console 契约：265-343）

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
