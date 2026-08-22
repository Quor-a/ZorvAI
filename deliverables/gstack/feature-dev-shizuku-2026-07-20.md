# QuroAI 集成 Shizuku（v72）实现报告

**日期**：2026-07-20
**场景**：全流程交付（计划 → 代码 → 编译 → 出包）
**参与成员**：主理人（工程实现，降级单 Agent 直调；`gstack-*` 子 agent 在本运行时不可用，按角色定义走降级执行）

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟡 有条件通过（代码完成、编译通过；真机 Shizuku 授权与 proot 经 Shizuku 启动需用户验证）
- 阻塞项数量：0（编译期阻塞已全部清除）；1 项真机验证待办
- 下一步：在 Android 设备上安装 Shizuku/Sui 并授权，先跑 `shizuku_status` 确认就绪，再试 `linux_exec(via=shizuku)` 与 `shizuku_exec`

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟡 条件 Go（需真机验证 Shizuku 授权 + 反射桥接的 newProcess 在目标 Shizuku 版本可用） |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 1 / 🟢 编译通过 |
| 关键行动项 | 3 条（见下） |
| 建议负责人 | 主理人（代码）/ 用户（真机验证） |

---

## 1. 各成员核心结论

### 🔍 产品官 / 工程（主理人直实现）
- 核心判断：Shizuku 已作为「更高权限命令通道」完整集成，提供 `shizuku_exec` / `shizuku_status` 两个工具，并让既有的 `linux_exec` 增加 `via=shizuku` 后端，使 proot 容器能以 Shizuku 服务身份运行。
- 关键建议：运行身份由 Shizuku 启动方式决定（adb=shell uid 2000 / root=root uid 0），不是每次调用可切换；如需 root 容器，应让用户在 root 模式下启动 Shizuku。

### 🛡️ 安全卫士（权限面审查）
- 核心判断：Shizuku 授权是敏感能力面。本应用仅在 AI/用户显式发起命令时以 shell/root 身份执行，命令内容来自工具参数（受 JSON Schema 约束），未引入任意代码执行入口；Manifest 声明了 `moe.shizuku.manager.permission.API_V23` 兼容旧版。
- 关键建议：保持「命令来源可控」——Shizuku 通道只暴露给已授权的本应用，且 `shizuku_exec` 在未授权时会尝试弹窗授权；后续若开放给第三方技能，需加二次确认。

### ✅ 质量门神（编译与发布）
- 核心判断：v72 `BUILD SUCCESSFUL`（1m35s），仅余无关文件的既有弃用告警。APK 已产出并拷至桌面。
- 关键建议：`newProcess` 在 13.1.5 为 private，采用反射桥接内部方法——这是唯一编译期风险点，需在目标 Shizuku 版本上真机确认。

### 🔧 排障手（编译期根因）
- 核心判断：编译期连续暴露 4 类 API 误用，已全部修正：① `rikka.shizuku.Sui` 类不存在（13.x 由 `ShizukuProvider` 自动初始化，已删除 Sui 引用）；② `newProcess` 无 `isRoot` 参数且为 private（改为反射桥接 `newProcess(String[], String[]?, String?)`）；③ `QuroLinuxEnv.execViaShizuku` 返回类型与 `QuroShizuku.ExecResult` 不匹配（已映射回 `QuroLinuxEnv.ExecResult`）；④ 线程 lambda 内 `try/catch` 括号错位（已修正）。
- 关键建议：Shizuku 13.x 公开进程执行 API 已改为 `UserService`；若反射桥接在未来版本失效，需迁移到 `UserService` 方案。

---

## 2. 综合审查发现（去重合并后按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟡 | 兼容性 | `core/shizuku/QuroShizuku.kt` | `Shizuku.newProcess` 在 13.1.5 为 private，经反射调用内部方法运行进程 | 在目标 Shizuku 版本真机验证；若失效则迁 `UserService` | 质量门神 / 排障手 |
| 2 | 🟡 | 能力边界 | `shizuku_exec` / `linux_exec` | 运行身份由 Shizuku 启动方式决定，无 per-call isRoot 切换 | 文档化；root 容器需 root 模式启动 Shizuku | 产品官 |
| 3 | 🟢 | 构建 | 全仓 | v72 编译通过 | 无 | 质量门神 |

---

## ✅ 行动清单（至少 3 条具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 真机安装 Shizuku/Sui 并授权，运行 `shizuku_status` 确认 ready+granted | 用户 | P0 | 安装 v72 后 |
| 2 | 真机验证 `linux_exec(via=shizuku)` 能启动 proot 容器、`shizuku_exec` 能跑 shell 命令 | 用户 | P0 | 同次验证 |
| 3 | 若反射 `newProcess` 在未来 Shizuku 版本失效，迁移到 `UserService` 执行方案 | 主理人 | P2 | 视 Shizuku 更新 |

---

## ⚠️ 待完善 / 已知局限

- **真机未验证**：Windows 无法运行 aarch64，且 Shizuku 需设备上安装 Shizuku 应用并授权，故运行时行为（授权弹窗、proot 经 Shizuku 启动）只能在真机确认。
- **反射依赖内部 API**：`newProcess` 为 private，反射调用在 Shizuku 升级时可能失效（已加 `@Suppress("UNCHECKED_CAST")` 与注释标注）。
- **Sui 支持**：依赖 `ShizukuProvider` 自动初始化 Sui（Magisk 模块路径），未显式调用 `Sui.init`（13.x 该类不存在）。
- **ptrace + Shizuku 交互**：`linux_exec(via=shizuku)` 让 proot 以更高身份运行，可能与部分厂商 SELinux 对 ptrace 的限制叠加，需真机观察。

---

## 交付清单

- **代码变更**
  - 新增 `core/shizuku/QuroShizuku.kt`：Shizuku 初始化、权限请求（协程挂起）、`exec`/`execRaw`、反射桥接 `newProcess`。
  - 新增 `activity/QuroApplication.kt`：自定义 Application，onCreate 调 `QuroShizuku.init`。
  - 新增 `core/tools/QuroToolsShizuku.kt`：`ShizukuExecTool`(shizuku_exec) + `ShizukuStatusTool`(shizuku_status)。
  - 修改 `core/linux/QuroLinuxEnv.kt`：抽出 `buildProotArgs`，新增 `execViaShizuku`（经 Shizuku 启动 proot）。
  - 修改 `core/tools/QuroToolsLinux.kt`：`linux_exec` 增加 `via` 参数（self/shizuku）。
  - 修改 `QuroBuiltInTools.kt` / `QuroTool.kt`：注册并白名单 `shizuku_exec` / `shizuku_status`。
  - 修改 `libs.versions.toml` / `build.gradle.kts`：新增 `dev.rikka.shizuku:api:13.1.5` + `:provider:13.1.5`，versionCode/Name → 72。
  - 修改 `AndroidManifest.xml`：注册 `QuroApplication`、`ShizukuProvider`、声明 `API_V23` 权限。
- **测试覆盖**：编译通过（BUILD SUCCESSFUL）；运行时需真机验证（见行动清单）。
- **发布检查清单**：✅ 编译 ✅ 版本升 72 ✅ APK 产出并拷桌面；⏳ 真机 Shizuku 授权验证。
- **回滚预案**：若 Shizuku 集成导致启动崩溃，可回退 versionCode 至 71（v71 不含 Shizuku）；或仅移除 `shizuku_exec`/`shizuku_status` 注册与 `QuroApplication` 中 `QuroShizuku.init` 调用。

---

## 📚 成员产出索引

- 主理人（工程实现）原始产出：上述全部代码文件，已在 `D:/Calw OS-project/QuroAI/app/src/main/java/com/ai/assistance/quro/` 下落地。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
