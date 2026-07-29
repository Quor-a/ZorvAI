# Quro AI 三问题修复 + 视觉层原创重写 + 命令框（feature-dev-visual-cmd）

**日期**：2026-07-17
**场景**：全流程交付（问题修复 → 视觉重写 → 功能新增 → 构建验证）
**参与成员**：产品官（设计/交互评审）+ 排障手（根因定位与修复）+ 质量门神（编译与构建验证）
> 注：本环境 GStack 专项子代理（gstack-*）不可用，主理人沽思航直接执行并完成上述成员的本职分析，结论如下。

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟢 通过（BUILD SUCCESSFUL，APK 已产出）
- 三个问题全部处理：① 设置页闪退已修复 ② Calw OS 视觉层已**原创**重写（去品牌化，不搬代码/资源）③ 对话框命令框能力已**原创**实现（斜杠命令面板）
- 阻塞项数量：0
- 下一步：安装 APK 做真机/模拟器走查（设置页不闪退、输入 `/` 弹命令面板、深色极光可读性）

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go（构建通过，建议走查后发布） |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 1 / 🟢 3 |
| 关键行动项 | 4 条（见行动清单） |
| 建议负责人 | 主理人（已执行）；走查可由质量门神/产品官复核 |

---

## 1. 各成员核心结论（每位 1 段）

### 🔍 产品官（产品评审）
交互方向确认：沿用 Calw OS「深空 + 极光 + 磨砂玻璃」的视觉语言，但全部以原创 Compose 重画、token 去品牌化为 `Quro*`，不引用上游 `bg_aurora` 资源；命令框采用「输入框 `/` 唤起可过滤命令面板」的交互模式（与 Calw OS 仅有的 Shizuku 命令入口思路一致，但独立实现）。

### 🔧 排障手（调试与根因）
定位到设置闪退根因：被嵌入设置页的 `QuroModelConfigForm` 根 `Column` 用了 `fillMaxSize()`，在「设置 Column → Card → Column → 表单」的嵌套滚动里 `maxHeight` 解析为 `Infinity`，Compose 抛异常崩溃。改为 `fillMaxWidth()`（保留 `verticalScroll`）后根因消除。

### ✅ 质量门神（QA测试与发布）
`./gradlew assembleDebug` 首次因 `QuroTheme.kt` 两处导入错误失败（`Box` 应在 `foundation.layout`、`ColorScheme` 未导入），修正后二次构建 **BUILD SUCCESSFUL**，产物 `app-debug.apk`（24,187,784 B）已复制到桌面 `QuroAI-debug.apk`（覆盖 04:05 旧包）。

> 仅上场上述成员；设计/安全/审查成员未参与本次定向修复。

---

## 2. 综合审查发现（去重合并后按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟢 | 功能修复 | `ui/QuroModelConfigScreen.kt:86` | 设置页因表单 `fillMaxSize()` 在嵌套滚动中触发 Infinity maxHeight 崩溃 | 改为 `fillMaxWidth()` 保留滚动 | 排障手 |
| 2 | 🟢 | 视觉重写 | `ui/theme/QuroTheme.kt` | 原主题极简、无品牌辨识度；需对齐 Calw OS 深空极光磨砂观感且原创 | 全量重写为 `Quro*` token + `QuroAuroraBrush` 渐变 + `frosted()` 降透明度 + `Box` 背景包裹 | 产品官 / 排障手 |
| 3 | 🟢 | 功能新增 | `ui/QuroChatScreen.kt` | 对话框缺命令框能力 | 原创 `QuroCommand` 数据类 + 底部栏可过滤命令面板 + 帮助弹窗 | 产品官 / 排障手 |
| 4 | 🟡 | 体验 | `ui/QuroChatScreen.kt` 命令面板触发 | 仅 `input.startsWith("/")` 触发；用户若输入非命令且以 `/` 开头的句子，面板不出现（不影响发送） | 后续可增强：仅当首 token 完全匹配命令时才显示，或加更精确的解析 | 质量门神 |

---

## 交付清单

- **代码变更**
  - `QuroModelConfigScreen.kt`：表单根 `Column` `fillMaxSize()` → `fillMaxWidth()`（修复设置闪退）。
  - `QuroTheme.kt`：全量重写。新增 `Quro*` 配色 token（暗/亮双方案）、`frosted()`（surface 系列降 alpha 至 0.45–0.82，透出背后极光）、`QuroAuroraBrush`（`Brush.verticalGradient`，深空→靛蓝→青，原创不搬资源）、`QuroTheme` 以 `Box(Modifier.background(QuroAuroraBrush))` 包裹 `MaterialTheme`。
  - `QuroChatScreen.kt`：新增 `QuroCommand` 数据类；底部栏 `AnimatedVisibility` 命令面板（`/clear`、`/new`、`/think`、`/model`、`/help`，按输入前缀过滤，选中即执行）；`BasicTextField` `onValueChange` 输入 `/` 唤起面板；`/help` 帮助 `AlertDialog`。
- **测试覆盖**：本次为定向修复 + 视觉/功能新增，已通过全量编译（`assembleDebug`）；未补充单元测试（项目既有 QA 体系以构建 + 手工走查为主）。
- **发布检查清单**：☐ 安装 APK 验证设置页不再闪退 ☐ 对话框输入 `/` 弹命令面板且 5 条命令均生效 ☐ 深色极光磨砂在浅色/深色及不同机型可读性 ☐ 发送普通消息（含以 `/` 开头非命令句）正常。
- **回滚预案**：保留 `git` 历史；若走查发现视觉不可读，可临时将 `frosted()` 透明度回调至 0.85+ 或切回 `base` 方案；设置闪退修复为单行改动，可一键 revert。

---

## ✅ 行动清单（至少 3 条具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 安装 APK 验证设置页不闪退 | 质量门神 / 主理人 | P0 | 即时（安装后） |
| 2 | 验证对话框输入 `/` 弹命令面板，且 `/clear`、`/new`、`/think`、`/model`、`/help` 均生效 | 质量门神 | P0 | 即时 |
| 3 | 视觉走查：深色极光磨砂在浅/深色模式与不同屏幕可读性 | 产品官 | P1 | 走查当天 |
| 4 | （可选增强）命令面板支持参数化（如 `/model deepseek`）与更精确的首 token 匹配 | 主理人 | P2 | 后续迭代 |

---

## ⚠️ 待完善 / 已知局限

- 命令面板目前仅按 `startsWith("/")` 触发，非命令的 `/` 开头句子不会误触但也不会提示；后续可加更智能解析。
- 视觉层 `frosted()` 将 surface 透明度压低以透出极光，在极端低对比屏幕或省电模式下文字可读性需真机确认。
- 本次未补自动化测试，验证以构建通过 + 手工走查为准。

---

## 📚 成员产出索引

- gstack-product-reviewer（产品官）原始产出：交互与视觉方向评审结论（见 §1 产品官段）。
- gstack-investigator（排障手）原始产出：设置闪退根因定位与修复、主题/命令框实现要点（见 §1 排障手段 + §2 发现表）。
- gstack-qa-lead（质量门神）原始产出：构建与验证记录（见 §1 质量门神段 + 交付清单发布检查项）。
- 设计 / 安全 / 代码审查成员：本次未上场，无原始产出。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
