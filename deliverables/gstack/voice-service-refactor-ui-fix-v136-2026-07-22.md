# v136 语音服务重构 + UI 修复报告

**日期**：2026-07-22
**场景**：语音服务重构 + 去重 + 行动轨迹修复 + 可视化边界优化
**参与成员**：主理人（Gu）

---

## TL;DR

- 整体结论：🟢 通过
- 阻塞项数量：0
- 下一步：用户安装测试，验证语音服务三入口导航流程

## 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 0 / 🟢 6（全部为功能增强/修复） |
| 关键行动项 | 6 条 |
| 建议负责人 | 主理人 |

---

## 1. 修复内容详情

### 🔧 语音服务重构（核心变更）

**问题**：原 `QuroVoiceServiceScreen` 是 TTS 配置页（服务商选择/配置/音色/标签/试听），顶部仅有一个「语音识别 (STT) 设置」按钮跳转到 STT 页。用户反馈「点语音服务→语音识别看不到界面」「缺少语音设置」。

**方案**：将 `QuroVoiceServiceScreen` 从「TTS 配置页」重写为「三入口导航 Hub」：

| 入口 | 图标 | 描述 | 跳转目标 |
|------|------|------|---------|
| 语音合成 (TTS) | VolumeUp | 云端服务商、音色、风格标签、试听 | `onOpenTts` → QuroTtsSettingsScreen |
| 语音识别 (STT) | Mic | 本地/云端模型/端侧引擎 | `onOpenStt` → QuroSttSettingsScreen |
| 语音设置 | Settings | 悬浮语音球、自动朗读、对话框语音按钮 | `onOpenVoiceSettings` → QuroVoiceSettingsScreen |

**修改文件**：
- `ui/QuroVoiceServiceScreen.kt` — 完整重写（从 444 行精简到 ~150 行导航 Hub）
- `ui/ChatScreen.kt:910` — 更新调用参数（新增 `onOpenTts`/`onOpenVoiceSettings` 回调）

### 🗑️ 重复按钮删除（3 处）

**问题**：设置页和工具磁贴存在重复的语音入口。

| 位置 | 删除内容 | 替代方案 |
|------|---------|---------|
| 工具磁贴（+工具） | 「语音识别 (STT)」tile | 保留「语音服务」tile → 进入 Hub |
| 设置页 | 「语音合成 (TTS)」行 | 合并为单一「语音服务」入口 |
| 设置页 | 「语音识别 (STT)」行 | 同上 |
| 设置页 | 「语音设定」行 | 合并为单一「语音服务」入口 |

**修改文件**：
- `ui/ChatScreen.kt:2640` — 删除工具磁贴中的 STT tile
- `ui/ChatScreen.kt:2351-2357` — 设置页 3 行语音入口合并为 1 行
- `ui/ChatScreen.kt:2316-2338` — SettingsSheetContent 参数精简（3 voice params → 1 onOpenVoiceService）
- `ui/ChatScreen.kt:2837-2856` — VoiceSheetContent 新增第三张「语音设置」卡片

### 🐛 新建对话行动轨迹修复

**问题**：新建对话时 `AgentTracePanel`（执行追踪面板）直接显示在空消息列表中，因为条件 `lastToolIdx < 0` 在无消息时恒为 true。

**修复**：
```kotlin
// 修复前
if (lastToolIdx < 0) { item { AgentTracePanel() } }

// 修复后
if (lastToolIdx < 0 && messages.isNotEmpty()) { item { AgentTracePanel() } }
```

**修改文件**：`ui/ChatScreen.kt:1168-1170`

### 📐 可视化边界收紧

**问题**：截图显示各面板/sheet 高度过大，占用过多屏幕空间。

| 组件 | 旧值 | 新值 | 收紧幅度 |
|------|------|------|---------|
| AgentTracePanel 外层 | max 172dp | max 120dp | -30% |
| AgentTracePanel 内层 LazyColumn | max 132dp | max 90dp | -32% |
| 各设置 sheet（6 处） | max 560dp | max 480dp | -14% |
| VoiceSheetContent | max 520dp | max 420dp | -19% |

**修改文件**：`ui/ChatScreen.kt`（4 处 heightIn 修改）

### 🔧 v135 编译错误修复（4 个遗留）

| # | 文件:行 | 错误 | 修复 |
|---|--------|------|------|
| 1 | QuroToolsMediaPlayer.kt:104 | `String?.ifEmpty{}` 不安全 | 改 `?.takeIf{} ?: fallback` |
| 2 | ChatScreen.kt:2002 | `showMusicPlayer` 在 Composer 子函数不可见 | 加 `onOpenMusicPlayer` 回调参数 |
| 3 | ChatScreen.kt:2902 | `Icons.Filled.OpenInFull` 不存在 | 改 `Icons.AutoMirrored.Filled.OpenInNew` |
| 4 | QuroDocumentViewer.kt:402 | 嵌套 lambda `it` 歧义 | 显式命名参数 `nv`/`lst` |

---

## 2. 构建结果

```
BUILD SUCCESSFUL in 1m 27s
39 actionable tasks: 10 executed, 29 up-to-date
```

- **versionCode**: 136
- **versionName**: "1.0.136"
- **APK 大小**: 148,973,681 bytes (~142 MB)
- **APK 路径**: `C:\Users\admin\Desktop\QuroAI-debug-2026-07-22-v136.apk`
- **编译警告**: 仅 deprecation（Icons.Filled.XXX → AutoMirrored 版本），无错误

---

## 3. 行动清单

| # | 行动 | 负责方 | 紧急度 | 状态 |
|---|------|--------|--------|------|
| 1 | 安装 v136 APK 测试语音服务三入口导航 | 用户 | P0 | 待验证 |
| 2 | 验证新对话不再显示行动轨迹 | 用户 | P0 | 待验证 |
| 3 | 验证设置页只有单一「语音服务」入口 | 用户 | P0 | 待验证 |
| 4 | 后续迭代：将 Icons.Filled deprecation 全量替换为 AutoMirrored 版本 | 开发 | P2 | 已知 |

---

## 4. 已知局限

- Icons.Filled 部分图标已标记 deprecated（ArrowBack/Send/VolumeUp/VolumeOff/Chat 等），建议后续统一替换为 AutoMirrored 版本。
- `QuroToolboxScreen.kt:127` 有一个既有 `String?`/`String` 类型不匹配 warning（非本轮引入）。
- 语音设置页面（`onOpenVoiceSettings` → `showVoice` → QuroVoiceSettingsScreen）目前复用旧的「语音设定」界面，后续可考虑增强为更完整的功能开关面板。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
