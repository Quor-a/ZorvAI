# QuroAI 集成 Shizuku 修复（v358）实现报告

**日期**：2026-07-27
**场景**：调试复盘 / 集成修复（Shizuku 通道重连）
**参与成员**：排障手（根因调查）+ 质量门神（编译与发布）

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟢 通过（根因定位精准、修复编译通过、合并 manifest 已确认含 ShizukuProvider）
- 阻塞项数量：0（编译期）；1 项真机验证待办（本环境无 adb，需在设备上确认 Shizuku 授权与 binder 连接）
- 根因：QuroAI 先前(约 v72 之后)丢失了 `ShizukuProvider` ContentProvider 注册与 `shizuku-provider` 依赖，导致 Shizuku 管理器无法与本应用建立 binder 连接 —— 表现为「Shizuku 里看不到 / 连不上 QuroAI」。
- 下一步：真机安装 v358 → 在 Shizuku 应用把 QuroAI 加入允许列表 → 权限页点「Shizuku 服务」验证 `pingBinder` 为 true。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go（需真机验证 Shizuku 授权） |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 1 / 🟢 修复已落地 |
| 关键行动项 | 3 条（见下） |
| 建议负责人 | 主理人（代码已交付）/ 用户（真机验证） |

---

## 1. 各成员核心结论

### 🔧 排障手（调试与根因）
- 核心判断：对照 Operit 已验证配置，确认 QuroAI 缺失 `rikka.shizuku.ShizukuProvider` 这一 binder 端点 —— Manifest 只声明了 `moe.shizuku.manager.permission.API` / `API_V23` 权限，但既无 `<provider>` 注册、`app/build.gradle.kts` 也仅依赖 `shizuku.api` 而未依赖 `shizuku.provider`。这是回归（v72 报告曾列明要注册，后续版本丢失）。
- 关键建议：补齐 provider 依赖 + 注册，完全对齐 Operit 的声明（`exported=true`、`permission=INTERACT_ACROSS_USERS_FULL`）；权限声明保持双声明(API+API_V23)以兼容新旧 Shizuku 管理器。

### ✅ 质量门神（QA测试与发布）
- 核心判断：v358 `BUILD SUCCESSFUL`（2m33s），仅余历史弃用告警（ArrowBack/VolumeUp/Send 等 AutoMirrored 提示，与本次无关）；合并 manifest 已确认含 `rikka.shizuku.ShizukuProvider`（authorities=`com.ai.assistance.quro.shizuku`）及 provider 库自带的 `moe.shizuku.client.V3_SUPPORT` 元数据，证明 AAR 正确并入。
- 关键建议：真机验证 `QuroShizuku.isReady`（即 `pingBinder() && checkSelfPermission()==GRANTED`）为 true；若仍失败，按 v72 报告关注反射 `newProcess` 在未来 Shizuku 版本的可用性。

> 产品官 / 安全卫士 / 设计师 本次未上场（纯集成修复，无需评审/设计变更）。

---

## 2. 综合审查发现（去重合并后按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟡 | 集成回归 | `app/build.gradle.kts` + `AndroidManifest.xml` | 缺失 `shizuku-provider` 依赖与 `ShizukuProvider` 注册，Shizuku 无法与本应用建立 binder | 已补齐（对齐 Operit） | 排障手 |
| 2 | 🟡 | 真机验证 | 设备端 | Shizuku 授权弹窗 / binder 连接需在真机确认（本环境无 adb） | 用户装 v358 后验证 | 质量门神 |

---

## ✅ 行动清单（至少 3 条具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 真机装 v358，打开 Shizuku 把 QuroAI 加入允许列表并启动服务 | 用户 | P0 | 安装后 |
| 2 | 在 QuroAI 权限页点「Shizuku 服务」确认状态变为「已就绪」，并跑 `shizuku_status` 工具核对 `ready:true` | 用户 | P0 | 同次验证 |
| 3 | 若仍连不上，取 `Download/QuroAI_logs/` 诊断日志发我（无 adb 亦可），我再排查反射 `newProcess` 或权限问题 | 主理人/用户 | P1 | 视验证结果 |

---

## ⚠️ 待完善 / 已知局限

- **真机未验证**：Windows 无法运行 aarch64，且 Shizuku 需设备上安装 Shizuku 应用并授权，故运行时行为（授权弹窗、binder 连接）只能在真机确认。
- **反射依赖内部 API**：`Shizuku.newProcess` 在 13.x 为 private，经反射调用（见 `QuroShizuku.kt`）；若未来 Shizuku 版本变更内部签名，需迁移到 `UserService` 方案（v72 报告已记录）。
- **`exported=true` 取舍**：严格按 Operit 已验证配置设为 `true`；官方示例为 `false`。`INTERACT_ACROSS_USERS_FULL` 权限已保护该 provider，安全性等价；若你倾向官方 `false`，我可改。

---

## 📚 成员产出索引

- 排障手原始产出：根因比对（Operit `AndroidManifest.xml` 的 `ShizukuProvider` 声明 vs QuroAI 缺失项）；合并 manifest 验证（行 388/389/870）。
- 质量门神原始产出：`BUILD SUCCESSFUL` 编译日志（2m33s，44 任务）、APK `cmp` 一致校验。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
