# ACI 管理中心重做交付报告

**日期**：2026-07-27
**场景**：UI 重做（设计系统对齐）+ 功能补全（关联启动 / 手动启动 / 合体）
**参与成员**：设计师（设计系统）+ 排障手（构建与编译排错）

---

## 📌 TL;DR（执行摘要，3-5 行）
- 整体结论：🟢 通过
- 阻塞项数量：0
- #804 ACI 管理中心 5 子项全部落地：重命名(ACT→ACI) + 关联启动 / 手动注册+名称搜索 / 合体 / 已发现 App 手动启动 / UI 重做。
- 底层 `QuroAidlAciManager` 逻辑零改动，仅 UI 层重写（无回归风险面）。
- `clean assembleDebug` BUILD SUCCESSFUL（仅历史废弃警告）；APK 桌面 `QuroAI-debug-2026-07-27-v357.apk`（374,280,891 B，cmp 一致）。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 0 / 🟢 5（5 项需求均已实现） |
| 关键行动项 | 3 条（见下） |
| 建议负责人 | 设计师（已交付）+ 排障手（已交付）；真机验证由用户执行 |

---

## 1. 各成员核心结论

### 🎨 设计师（设计系统与视觉）
- 核心判断：原 ACI 管理中心用裸 `Card` + `OutlinedTextField` + 散落 `TextButton`，与 App 既有「纸感」体系（STT/语音设置页）严重割裂；标题仍带旧概念痕迹。
- 关键建议：整体迁移到 `ChapterLabel`(01/02/03 章节) + `SetGroup`(白底+Line 描边+16dp 圆角) + `UnderlineField` + `PrimaryButton` + `InfoBox` 体系，与原生语音设置页保持同一视觉语言；把「手动注册」与「按名称搜索」两个割裂卡片**合体**为单一「添加 ACI 应用」入口。

### 🔧 排障手（调试与根因）
- 核心判断：编译 2 轮失败均为导入缺失（`clip` / `RoundedCornerShape` / `BorderStroke` 在重写时漏带），与逻辑无关；补回导入后一次性通过。
- 关键建议：UI 重写务必保留 `androidx.compose.ui.draw.clip`、`androidx.compose.foundation.shape.RoundedCornerShape`、`androidx.compose.foundation.BorderStroke` 三项导入；`Card` 主题色与 `Card` 组件同名，已用 `import ... as PaperCard` 别名规避冲突。

---

## 2. 综合审查发现（去重合并后按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟢 | 设计 | QuroAidlAciCenterScreen.kt | 原 UI 与纸感设计系统割裂，且「手动注册」「按名称搜索」两卡片功能重叠 | 合体为「添加 ACI 应用」统一入口 | 设计师 |
| 2 | 🟢 | 功能 | 已发现 App 卡片 | 原仅有「重绑」，缺少「手动启动」 | 卡片加「启动」按钮（调用 `mgr.launchApp`） | 设计师 |
| 3 | 🟢 | 功能 | 添加流程 | 原手动注册仅绑定不启动，无「关联启动」 | 新增「按包名注册并启动」+ 搜索结果「注册并启动」 | 设计师 |
| 4 | 🟢 | 编译 | QuroAidlAciCenterScreen.kt | 重写漏带 clip/RoundedCornerShape/BorderStroke 导入 | 补回 3 项导入 | 排障手 |

---

## 交付清单（代码变更 + 测试覆盖 + 发布检查清单 + 回滚预案）

**代码变更**
- `app/src/main/java/com/ai/assistance/quro/ui/QuroAidlAciCenterScreen.kt`：全量重写。
  - 结构：01 添加 ACI 应用（UnderlineField 统一入口 + 「搜索」`PrimaryButton` + 「按包名注册并启动」描边按钮 + 搜索结果行含「启动/注册并启动」+ 空结果 `InfoBox`）／02 已发现的 ACI 应用（`InfoBox` 空态引导 + `AciAppCard` 加重绑/启动）／03 开发者文档（可折叠 `SetGroup`）。
  - `AciAppCard` 改用 `CardDefaults.cardColors(containerColor = PaperCard)`（与 `SetGroup` 同纸白底）+ `Line` 描边；新增 `onLaunch` 参数。
  - 移除 `OutlinedTextField`/`Button` 旧控件，全部走设计系统。
- `app/build.gradle.kts`：`versionCode 356→357`、`versionName "1.0.356"→"1.0.357"`。
- `QuroAidlAciManager.kt` / `QuroAidlAciTools.kt`：**未改动**（功能已齐备：`registerPackage` / `searchInstalledApps` / `launchApp` / `rebind`）。

**测试覆盖**
- 编译验证：`compileDebugKotlin` + `assembleDebug` BUILD SUCCESSFUL（1m59s），无本次改动引入的新警告。
- 待真机验证（用户执行）：① 搜索本机应用 → 启动 / 注册并启动；② 手动输入包名 → 按包名注册并启动；③ 已发现卡片「启动」「重绑」；④ 右上「刷新」；⑤ 开发者文档展开。

**发布检查清单**
- APK 已生成并落盘桌面：`QuroAI-debug-2026-07-27-v357.apk`（374,280,891 B，`cmp` 与构建产物一致）。
- C 盘剩余空间 4.1 GB（≥500 MB 阈值，满足装包条件）。

**回滚预案**
- 如真机验证出现 UI 异常：将 `QuroAidlAciCenterScreen.kt` 回退至 v356 版本 + `versionCode/Name` 回退 356，重新 `assembleDebug` 出包即可，不影响 `QuroAidlAciManager` 运行时逻辑。

---

## ✅ 行动清单（至少 3 条具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 真机安装 v357，验证「添加 ACI 应用」搜索/注册并启动/启动/重绑/刷新全链路交互 | 用户 | P1 | 装包后 |
| 2 | 用一台已装 ACI 被控 App 的真机确认发现→绑定→能力清单→`aci_call` 仍通（manager 未动，预期无回归） | 用户 | P2 | 装包后 |
| 3 | 若需进一步「改名字」（如把「ACI 管理中心」换成更口语的标题），给出目标命名后我再改顶栏 `title` 一处即可 | 用户/设计 | P3 | 待定 |

---

## ⚠️ 待完善 / 已知局限

- 「关联启动」对**未安装**包名会 `registerPackage` 返回 false 并提示「未找到 ACI 服务」，但仍会尝试 `launchApp`（此时 `getLaunchIntentForPackage` 返回 null → 启动失败 Toast），属预期兜底，不静默。
- 搜索结果列表未做分页，上限 50 条（沿用 `mgr.searchInstalledApps` 既有 `take(50)`），超量应用需更精确关键词。
- 本环境无法派发 gstack 子 Agent，主理人按 `gstack-lead` 约定直接执行「设计师 + 排障手」框架并汇编，成员结论为主理人依据设计系统与编译实测转述。

---

## 📚 成员产出索引

- gstack-designer（设计师）原始产出：QuroAidlAciCenterScreen.kt v357 重写稿（纸感设计系统迁移 + 合体 + 关联启动 + 手动启动）。
- gstack-investigator（排障手）原始产出：编译 2 失败→补 3 导入→1 成功；APK `cmp` 校验通过。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
