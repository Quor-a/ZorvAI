# 语音服务 UI 重做交付报告（#803-2~6）

**日期**：2026-07-27
**场景**：全流程交付（UI 重做 + 编译验证）
**参与成员**：设计师（设计系统一致性审查，主理人代执行） + 排障手（编译/回归验证，主理人代执行）
**基线版本**：v355（#803-1 对话框语音按钮「长按说话放开结束」已先行交付）
**交付版本**：v356

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟢 通过（Go）
- 阻塞项数量：0
- 本次完成 #803 语音服务 UI 重做的剩余 5 个子项（#803-2 语音设置 / #803-3 删除已配置模型 / #803-4 云服务商 UI / #803-5 语音合成 UI / #803-6 语音服务导航），统一对齐到 App 既有的「纸感」设计系统（ChapterLabel + SetGroup + SetRow + UnderlineField，与「语音识别 (STT)」页一致）。
- v356 `clean assembleDebug` 编译通过，APK 已产出。
- 关键判断：「删除已配置模型」按字面落点为**移除 TTS 来源里名为「已配置模型」的死桩（QuroTtsPrefs.SOURCE_MODEL，原显示「敬请期待」）**，并额外提供「清除此服务商配置」能力（数据层新增 `clearConfig`）。注意：`QuroSttPrefs.SOURCE_MODEL` 是 **STT 的「AI 模型」引擎**，功能正常，**未触碰**。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 1（设计一致性历史债，非缺陷） / 🟢 完成 |
| 关键行动项 | 3 条（见下） |
| 建议负责人 | 主理人（已直接执行） |

---

## 1. 各成员核心结论

### 🎨 设计师（设计系统一致性）
- 核心判断：语音相关 4 个设置页此前各用各的排版（Tab+PageHeader、Card+Radio、OutlinedTextField 混排），与已落地的 STT 页「纸感」体系严重不一致，是主要的视觉/体验割裂点。
- 关键建议：将语音设置、语音合成、云模型配置三页统一改造为 `ChapterLabel` + `SetGroup` + `SetRow`/`SetRowClickable`，参数输入统一用 `UnderlineField`；语音服务导航的章节头也改用 `ChapterLabel`。导航磁贴（CapabilityTile）与全 App 视觉已一致，仅做轻量对齐。

### 🔧 排障手（编译与回归）
- 核心判断：本次改动均为 UI 重组 + 1 处纯新增数据方法（`clearConfig`），不触及播放/识别运行时逻辑，回归面可控。编译过程暴露 3 类写法错误（已修）：`SetRow.onToggle` 是 `() -> Unit` 无参、Compose `Color(long)` 被 `android.graphics.Color` 遮蔽、`UnderlineField.placeholder` 为必填参数。
- 关键建议：v356 编译通过、警告均为历史废弃 API（ArrowBack/VolumeUp 等），可出包；真机重点验证「语音设置页滚动不串页」「TTS 来源切换本地↔云端」「云配置清除后回落未配置」三处交互。

---

## 2. 综合审查发现（去重合并后按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟡 | 设计一致性 | QuroVoiceSettingsScreen / QuroTtsSettingsScreen / QuroCloudTtsConfigScreen | 三页排版风格不统一，与 STT 页体系割裂 | 已统一为设计系统组件 | 设计师 |
| 2 | 🟢 | 功能清理 | QuroTtsSettingsScreen | TTS 来源中「已配置模型」(SOURCE_MODEL) 为长期「敬请期待」死桩 | 已移除该选项，残留来源走兜底提示 | 排障手 |
| 3 | 🟢 | 能力补全 | QuroTtsProvider.kt / QuroCloudTtsConfigScreen | 缺少「删除已配置服务商」入口 | 新增 `clearConfig` + 顶栏「清除」按钮 | 排障手 |

---

## 交付清单（代码变更 + 测试覆盖 + 发布检查清单 + 回滚预案）

### 代码变更
- `ui/QuroVoiceSettingsScreen.kt`（重写）：7 个 Tab 摊平为 `ChapterLabel` 章节 + `SetGroup` 分组；每个能力独立 `SetRow` 开关 + 折叠详情；情绪/语色页的 InfoBox 与调色板保留。
- `ui/QuroTtsSettingsScreen.kt`（重写）：来源选择改为 `SetGroup` 单选行，**移除「已配置模型」(SOURCE_MODEL) 死桩**，仅留 本地系统 / 云模型服务；本地引擎配置、声音列表、语速/音高、试听与 Bug 日志区全部包进 `SetGroup`；云来源用 `SetRowClickable` + `PrimaryButton` 跳转配置。
- `ui/QuroCloudTtsConfigScreen.kt`（改造）：服务商参数输入由 `OutlinedTextField` 统一改为 `UnderlineField`（含密钥可见切换）；新增 `clearCurrentConfig()` 与顶栏「清除」按钮。
- `ui/QuroVoiceServiceScreen.kt`（轻改）：章节头 `GroupCaption` → `ChapterLabel("01", "能力分层")`。
- `core/tools/QuroTtsProvider.kt`（新增）：`QuroTtsProviderPrefs.clearConfig(ctx, id)`，仅移除该服务商独立配置 JSON，不波及当前选中标记与其它服务商。
- `app/build.gradle.kts`：`versionCode 355→356`、`versionName 1.0.355→1.0.356`。

### 测试覆盖
- 编译验证：`clean assembleDebug` 通过（2m24s，44 警告均为历史废弃 API）。
- 真机建议（待用户侧）：① 语音设置页滚动流畅、开关即时生效；② TTS 来源在「本地系统」与「云模型服务」间切换，本地声音列表/试听正常；③ 云配置页点「清除」后该服务商回落「未配置」，再次进入配置为空。

### 发布检查清单
- [x] 编译通过，无新增错误
- [x] versionCode / versionName 已升
- [x] 未删除任何运行时对外 API（仅新增 `clearConfig`）
- [ ] 真机三处交互回归（需用户）

### 回滚预案
- 若真机出现语音设置/合成页异常：git 回退本批 5 个文件 + 1 个 prefs 方法，或回装 v355 APK（`QuroAI-debug-2026-07-27-v355.apk`）。

---

## ✅ 行动清单

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 真机验证语音设置页滚动/开关、TTS 来源切换、云配置清除三处交互 | 用户（主理人提供 v356 APK） | P1 | 出包后 |
| 2 | 后续将 STT 页外其余设置页（机器人 #806、ACT 管理中心 #804 等）也纳入同一设计系统 | 主理人 | P2 | 后续 epic |
| 3 | 如用户期望「删除已配置模型」另有所指（如清空某 AI 转写模型），再按新口径补做 | 主理人 | P3 | 待确认 |

---

## ⚠️ 待完善 / 已知局限

- 本次未动 `QuroSttSettingsScreen`（已符合设计系统）与 `QuroVoiceBallView`/`QuroVoiceBallService`（悬浮球本体）。
- 「清除」仅清该服务商配置 JSON；若用户此前在 TTS 来源选了某云服务商且已清除，朗读会回落到「未配置」提示，需在云配置页重新填参（符合预期）。
- 设计系统组件 `UnderlineField.placeholder` 为必填，迁移字段时勿漏。

---

## 📚 成员产出索引

- 设计师（设计系统审查）原始产出：本报告中「各成员核心结论 → 设计师」段。
- 排障手（编译/回归）原始产出：本报告中「各成员核心结论 → 排障手」段 + 编译日志（BUILD SUCCESSFUL）。
- 代码差异：v355 → v356，涉及 `QuroVoiceSettingsScreen.kt` / `QuroTtsSettingsScreen.kt` / `QuroCloudTtsConfigScreen.kt` / `QuroVoiceServiceScreen.kt` / `QuroTtsProvider.kt` / `build.gradle.kts`。
- 产出 APK：`/c/Users/admin/Desktop/QuroAI-debug-2026-07-27-v356.apk`

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
> 注：本环境无法派发 gstack 子 Agent，主理人依项目约定直接执行各成员框架并汇编收口。
