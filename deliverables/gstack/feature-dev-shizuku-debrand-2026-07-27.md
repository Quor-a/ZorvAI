# 集成 Shizuku + 去品牌化 交付报告

**日期**：2026-07-27
**场景**：全流程交付（Shizuku 集成修复 + 上游品牌清除）
**参与成员**：排障手（Shizuku 根因定位与修复）+ 设计师（品牌一致性审计与清除）

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟢 通过（代码层阻塞项 0；真机 Shizuku 授权需用户侧验证）
- **Shizuku 集成**（v358）：补齐了此前回归丢失的 `rikka.shizuku.ShizukuProvider` 端点与 `shizuku-provider` 依赖，合并 manifest 已确认正确并入。
- **去品牌化**（v359）：清除全部 8 处上游「Operit / Calw OS」字样（6 处代码注释 + 1 处 manifest 注释 + 1 处代码注释归一），`app/src/main` 内已 **0 处 Operit 残留**。
- 用户可见品牌原本已是 `Quro AI` / 包名 `com.ai.assistance.quro` / 资源 `quro_*`，无上游品牌暴露。
- 下一步：真机装 v359，在 Shizuku 管理器把 QuroAI 加入允许列表并验证 `ready:true`。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go（需真机验证 Shizuku 授权） |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 1（Calw OS 中间命名可选归一）/ 🟢 已修复 |
| 关键行动项 | 3 条 |
| 建议负责人 | 集成与去品牌化已交付；真机 Shizuku 验证交用户 |

---

## 1. 各成员核心结论

### 🔧 排障手（Shizuku 根因定位）
- 核心判断：QuroAI 仅声明了 `moe.shizuku.manager.permission.API/API_V23` 权限，**缺 `ShizukuProvider` 这个 binder 端点**——`build.gradle.kts` 只依赖 `shizuku.api` 没依赖 `shizuku.provider`，manifest 也无 `<provider>` 注册。没有它，Shizuku 管理器无法与本应用建立连接，表现为「Shizuku 里看不到/连不上 QuroAI」。这是 v72 当年集成过、后续版本回归丢失的。
- 关键建议：严格对照 Operit 已验证配置补齐 `ShizukuProvider`（`authorities=${applicationId}.shizuku`、`exported=true`、`permission=INTERACT_ACROSS_USERS_FULL`）+ `implementation(libs.shizuku.provider)`；合并 manifest 已验证含该 provider 及库自带 `moe.shizuku.client.V3_SUPPORT` 元数据。

### 🎨 设计师（品牌一致性审计）
- 核心判断：用户可见品牌已彻底去上游化——显示名 `Quro AI`、包名 `com.ai.assistance.quro`、所有 drawable/xml 资源均为 `quro_*`、strings.xml 无任何 Operit。残留品牌字样**仅存在于 6 处代码注释 + 1 处 manifest 注释**（非用户可见），属「代码内品牌字样」需清除范畴。
- 关键建议：将 6 处注释中的「Operit」归一为中性「上游」或我方品牌「QuroAI」；`Calw OS` 中间命名仅 2 处注释提及（属我方历史更名，非上游品牌），已将其一并归一为「QuroAI」以保证注释内品牌一致。README/NOTICE 的 Operit 属合规开源署名，依法保留未动。

> 本任务未上场：产品官、安全卫士、质量门神（无产品评审/安全审计/发布门槛类新增工作，集成与清理均为既定修复）。

---

## 2. 综合审查发现（去重合并后按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟡 | 集成 | AndroidManifest.xml | 缺 `ShizukuProvider` 注册，Shizuku 无法绑定 | 注册 `rikka.shizuku.ShizukuProvider` | 排障手 |
| 2 | 🟡 | 依赖 | app/build.gradle.kts | 仅依赖 `shizuku.api`，缺 `shizuku.provider` | 加 `implementation(libs.shizuku.provider)` | 排障手 |
| 3 | 🟢 | 品牌 | 5×.kt + AndroidManifest.xml | 8 处 Operit/Calw OS 注释字样 | 已清除并归一为「上游」/「QuroAI」 | 设计师 |
| 4 | 🟡 | 品牌(可选) | QuroLlmClient.kt / ToolCallIcon.kt | `Calw OS` 中间命名残留 2 处注释 | 已归一为 QuroAI；如要彻底无 Calw 字样可再确认 | 设计师 |

---

## ✅ 行动清单（至少 3 条具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 真机装 v359 → Shizuku 管理器把 QuroAI 加入允许列表并启动服务 | 用户 | P0 | 装包后即时 |
| 2 | QuroAI 权限页点「Shizuku 服务」，确认状态变「已就绪」（`pingBinder()==true`）；或跑 `shizuku_status` 工具核对 `ready:true` | 用户 | P0 | 装包后即时 |
| 3 | 若仍连不上，用手机文件管理器进 `Download/QuroAI_logs/` 取诊断日志发我（无需 adb），查反射 `newProcess` 或权限问题 | 我 | P1 | 收到日志后 |
| 4 | 如希望把注释内 `Calw OS` 也彻底移除（非品牌暴露，纯洁癖），给一句话指示即可 | 我 | P3 | 按需 |

---

## ⚠️ 待完善 / 已知局限

- **Shizuku 真机授权与 binder 连接需在设备上验证**：本环境无 adb、设备不连工作机，仅能验证编译与合并 manifest，无法实跑连接。
- **合规署名保留**：`README.md` / `NOTICE` / `docs/` 中的 Operit 提及属开源许可证要求的 upstream 署名，依法未清除（07-20 许可证合规审计已处理）。
- **`calw_` 前缀**：`ToolCallIcon.kt` 中 `calw_` 是真实工具名前缀的剥离逻辑（代码行为，非品牌展示），保留不动。
- v359 相对 v358 **仅注释变化，无任何行为/资源改动**；构建 44 条警告均为历史废弃 API（ArrowBack/VolumeUp/ClickableText 等），无本次引入。

---

## 📚 成员产出索引

- 排障手（Shizuku 根因与修复）原始产出：`deliverables/gstack/fix-shizuku-provider-2026-07-27.md`
- 设计师（品牌审计与清除）原始产出：见本报告第 1、2 节（8 处注释 scrub 清单：QuroMainActivity.kt×2、AnrMonitor.kt×1、QuroQqBotAdapter.kt×3、QuroLlmClient.kt×1、AndroidManifest.xml×1）
- 构建产物：`QuroAI-debug-2026-07-27-v359.apk`（374 MB，`cmp` 一致，C 盘剩 3.3 GB）

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
