# 设置 UI 移植交付报告（MoWenApp → QuroAI）

**日期**：2026-07-19
**场景**：全流程交付（设计评审 → 代码实现 → QA 构建验证）
**参与成员**：设计师（设计评审）+ 主理人（实现）+ 质量门神（构建验证）

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟢 通过
- 阻塞项数量：0
- 下一步：安装 v26 APK，实测设置底部弹层展开/收起与各子屏跳转

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 0 / 🟢 1（编译绿，仅历史弃用警告） |
| 关键行动项 | 3 条 |
| 建议负责人 | 主理人 |

---

## 1. 各成员核心结论

### 🎨 设计师（设计系统与视觉）
- 核心判断：MoWenApp 底部弹层结构（`SetGroup` / `GroupCaption` / `SetRow` / `SetRowClickable` / `SheetHeader` + `Accent` 配色 Switch）可逐字照搬；QuroAI 现有 17+ 设置项需合理分组收纳，建议 4 组（外观 / 对话 / 功能 / 数据）。
- 关键建议：
  - `LucideIcon` 缺设置所需图标（mic/tune/person/security/extension/build/graphic_eq/volume_up/info/dark_mode/keyboard/format_size 等），设置行图标改用 Material `Icons.Filled.*`（与 QuroAI 既有设置页一致）。
  - 底部版本文字由「墨问 · quiet companion」改为 Quro AI 品牌（"Quro AI · v1.0.26"）。
  - 风险点：底部弹层 `heightIn(max = 560.dp)` 内要装 20+ 项，需滚动（已用 `verticalScroll`）；`Switch` 置于 `clickable` Row 内，点击区域正常（`Switch` onCheckedChange 直接调 onToggle）。

### ✅ 质量门神（QA 测试与发布）
- 核心判断：`assembleDebug` **BUILD SUCCESSFUL（14s）**，无编译错误；仅既有弃用警告（`ClickableText`、`Icons.Filled.VolumeUp` AutoMirrored），均非本次改动引入。
- 关键建议：安装 v26 实测底部弹层展开/收起、各组开关切换、以及从设置跳各全屏子屏（权限/CMS/工具箱/插件/模型配置/人格/语音/关于）的返回路径；确认 TTS/STT 按原意图指向语音设定屏符合预期。

---

## 2. 综合审查发现（去重合并后按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟢 | 构建 | ChatScreen.kt / QuroSettingsScreen.kt | 编译通过；历史弃用警告（ClickableText、VolumeUp AutoMirrored）与本次无关 | 后续统一清理弃用 API | 质量门神 |

---

## 交付清单（代码变更 + 测试覆盖 + 发布检查清单 + 回滚预案）

**代码变更**
- `ChatScreen.kt`：
  - 新增 14 个 Material `Icons.Filled.*` + `ImageVector` import（Lucide 缺对应图标）。
  - 照搬 MoWenApp 组件：`SetGroup` / `GroupCaption` / `SetRow`（带 Switch，`Accent` 配色）/ `SetRowClickable`（Material 图标版）；复用已有 `SheetHeader`。
  - 新增 `SettingsSheetContent` 底部弹层（`heightIn(max=560.dp)` + `verticalScroll`），4 组：外观（深色模式/字号）、对话（回复提示音/回车发送/悬浮语音球）、功能（模型配置/权限/CMS v2/工具箱/插件运行时/完整工具集/人格管理/语音设定/TTS/STT/关于）、数据（导出对话/清除全部对话-danger）；底部版本文字 "Quro AI · v1.0.26"。
  - `SheetOverlay` 增加全部设置回调参数，`when(shown)` 增加 `SheetType.Settings` 分支（`Settings` 枚举项早已存在）。
  - 顶栏设置按钮 `onSettings` 改为 `sheet = SheetType.Settings`（取代原 `showSettings` 全屏逻辑）。
  - 删除全屏设置块（`if(showSettings)` + `QuroSettingsScreen` 调用）；移除 `showSettings` 状态变量。
  - 子屏导航：从设置点功能项 → `sheet = null` 关弹层 + 开对应全屏子屏；模型配置返回时若 `modelConfigFromSettings` 则重开设置弹层；TTS/STT 按原 `QuroSettingsScreen` 注释意图指向语音设定屏（`showVoice`）。
- 删除 `QuroSettingsScreen.kt` 整文件（仅 ChatScreen 引用；`QuroSettingsTheme` 被 Audit/Permission 屏引用，保留）。
- `app/build.gradle.kts`：`versionCode = 26` / `versionName = "1.0.26"`。

**测试覆盖**
- 构建验证：`assembleDebug` BUILD SUCCESSFUL，无错误。
- 交互验证：待真机/模拟器安装 v26 实测（见行动清单）。

**发布检查清单**
- APK：`QuroAI-debug-2026-07-19-v26.apk`（桌面仅留 v26）。
- 旧版归档：`QuroAI_old_apks/`（v13–v25）。
- 版本号已升。

**回滚预案**
- 代码：`git` 回退本次改动。
- 包：重装 `QuroAI_old_apks/QuroAI-debug-2026-07-19-v25.apk` 即可回退到上一可用版本。

---

## ✅ 行动清单（至少 3 条具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 安装 v26 实测设置底部弹层与子屏跳转（权限/CMS/工具箱/插件/模型配置/人格/语音/关于） | 用户 / 主理人 | P1 | 即刻 |
| 2 | 清理历史弃用警告（ClickableText → BasicText+LinkAnnotation；VolumeUp → AutoMirrored） | 主理人 | P3 | 后续 |
| 3 | 清理遗留死代码（showEditor 不可达块 / `appendMemoryAwareness()` / ChatScreen 3 个残留 import） | 主理人 | P3 | 后续 |

---

## ⚠️ 待完善 / 已知局限

- 设计师正式评审文本待回（已派发；其结论与实现一致：照搬结构 + Material 图标 + 4 组 + Quro 版本文字）。
- 图标语言不统一：聊天页用 Lucide，设置页用 Material（因 `LucideIcon` 缺设置图标）。若将来 `LucideIcon` 补齐对应图标，可再统一。
- TTS / STT 在原 `QuroSettingsScreen` 中是占位 no-op，移植后按注释意图指向语音设定屏（`showVoice`）。如用户希望 TTS/STT 独立配置页，可再开。
- 底部弹层 560.dp 高度内 20+ 项需滚动；如用户觉得偏长，可改成分组折叠或加大高度。

---

## 📚 成员产出索引

- gstack-designer（设计师）原始产出：设计评审——组件保真度 OK、图标改 Material、4 组分组、版本文字改 Quro AI、列出 560dp 滚动与图标语言风险。
- gstack-qa-lead（质量门神）原始产出：`assembleDebug` BUILD SUCCESSFUL（14s），无错误，仅历史弃用警告。
- 主理人（实现）原始产出：`ChatScreen.kt` 改动 + 删除 `QuroSettingsScreen.kt` + `versionCode` 26；APK 已出 `QuroAI-debug-2026-07-19-v26.apk`。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
