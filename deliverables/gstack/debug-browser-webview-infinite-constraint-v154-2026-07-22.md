# 内置浏览器打开元宝链接崩溃（Size out of range）根因与修复

**日期**：2026-07-22
**场景**：调试复盘（排障手）
**参与成员**：排障手（gstack-investigator，由主理人直调，本环境 gstack 子代理不可用）

---

## 📌 TL;DR（执行摘要）
- 整体结论：🟢 已定位并修复（崩溃根因明确，单点修改）
- 现象：点击聊天气泡里的「查看元宝的回答」（元宝链接卡片 `yb.tencent.com`）→ App 在主线程抛出 `IllegalStateException: Size(986 x 2147483647) is out of range`，UI 崩溃。
- 根因：`QuroBrowserScreen` 的网页 `AndroidView` 使用了 `Modifier.fillMaxSize().weight(1f)`。在 `Column` 子组合（SubcomposeLayout）测量时，`fillMaxSize()` 拿到 `maxHeight = Infinity`（即 `2147483647`），`weight` 又在子流程才分配高度，二者冲突 → 上报了无限高度 → 触发 Compose 的尺寸越界断言。
- 修复：用 `Box(Modifier.fillMaxWidth().weight(1f))` 包一层，`AndroidView` 只留 `Modifier.fillMaxSize()` 填满该有界 Box。
- 阻塞项数量：0（已修，待 v154 出包复测）

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go（修复后需真机复测确认） |
| 严重度分布 | 🔴 1（崩溃）/ 🟠 0 / 🟡 0 / 🟢 0 |
| 关键行动项 | 1 条（装 v154 复测元宝卡点击） |
| 建议负责人 | 主理人（已修复） |

---

## 1. 各成员核心结论

### 🔧 排障手（调试与根因）
- 核心判断：崩溃**不是** v149/v150 组件/元宝卡片接线问题（那些代码此前已审计确认在位）——而是**点开链接后内置浏览器 WebView 的布局约束错误**。崩溃宽度 `986` 为全内容宽度（与 WebView 全宽吻合），高度 `2147483647=Int.MAX_VALUE` 为无限约束，经典 `AndroidView + fillMaxSize + weight` 陷阱。
- 关键建议：将 `AndroidView(modifier = Modifier.fillMaxSize().weight(1f))` 改为「`weight` 的 `Box` 包一层 + `AndroidView(Modifier.fillMaxSize())`」，使 WebView 在**有界** Box 内填充。已于 `QuroBrowserScreen.kt` 落地。

> 本次为单成员（排障手）直调场景，未上场其他成员。

---

## 2. 综合审查发现（按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🔴 | 崩溃/布局 | `ui/QuroBrowserScreen.kt:396-454`（原 `AndroidView` 修饰符） | `AndroidView(modifier = Modifier.fillMaxSize().weight(1f))` 在 Column 子组合测量时拿到无限高度，抛 `Size(986 x 2147483647) is out of range` | 用 `Box(Modifier.fillMaxWidth().weight(1f))` 包裹，`AndroidView` 仅 `fillMaxSize()` | 排障手 |

---

## ✅ 行动清单

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 装 v154 APK（`QuroAI-debug-2026-07-22-v154.apk`）干净重装，点击元宝卡片确认浏览器正常打开、不再崩溃 | 用户/主理人 | P0 | 出包后即时 |
| 2 | 复测同一会话其余功能：组件合体气泡、气泡任务栏（复制/追问/分享/重试）、通知栏语音球+聊天框、系统分享、后台自启动 | 用户 | P1 | 复测时 |

---

## ⚠️ 待完善 / 已知局限

- 其余全屏 WebView（代码预览 `ChatScreen.kt:3537`、文档屏 `QuroDocsScreen.kt`）均采用「`Box(fillMaxSize().weight(1f))` 包裹」的安全写法，未受影响。
- 尚未在真机验证 v154 修复效果（构建中）。
- 元宝链接卡片本身渲染正常（v150 生效），仅「点开」动作曾崩溃——这也反向印证 v149/v150 代码是好的，此前「没实现」系陈旧安装包所致。

---

## 📚 成员产出索引

- 排障手（gstack-investigator，主理人直调）原始产出：根因 = `QuroBrowserScreen` WebView `AndroidView` 的 `fillMaxSize().weight(1f)` 无限约束；修复见 `QuroBrowserScreen.kt` 第 395-455 行（改为 `Box(weight) > AndroidView(fillMaxSize)`）。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
