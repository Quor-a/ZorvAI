# 聊天气泡内「元宝回答」链接预览卡（v150）

**日期**：2026-07-22
**场景**：功能新增（产品设计 + 实现 + 构建验证）
**参与成员**：软件工坊主理人（兼产品官 / 设计师 / 实现 / QA）— 本环境 gstack 子代理不可用，由主理人直接编排并落地

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟢 通过（隔离构建 BUILD SUCCESSFUL，APK 已产出并拷至桌面）
- 阻塞项数量：0（构建已通过，无残留编译阻塞）
- 下一步：装 `QuroAI-debug-2026-07-22-v150.apk`，在对话中发送/收到含 `https://yb.tencent.com/s/...` 的消息，验证气泡内出现「腾讯元宝回答」预览卡，点击即在应用内浏览器打开该回答。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go（构建通过后） |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 0 / 🟢 全部为新增能力 |
| 关键行动项 | 3 条（见下） |
| 建议负责人 | 软件工坊主理人 |

---

## 1. 各成员核心结论

### 🔍 产品官（产品评审）
- 核心判断：用户要的是"原生安卓点击查看元宝的回答"——在聊天气泡里对腾讯元宝分享链接渲染一张可点击的预览卡，点按即在应用内浏览器打开该回答，等价于原生安卓的链接预览/打开体验。
- 关键建议：不做成"打开某个独立界面"的工具，而是把元宝链接本身变成气泡内的可视化预览组件，用户零学习成本即可"看到链接→点一下→看答案"。

### 🎨 设计师（设计系统与视觉）
- 核心判断：复用 v147/v149 既有的 `CardShell` + `MaterialTheme.colorScheme` token 语言，卡片含元宝标识、标题「腾讯元宝回答」、URL（单行省略）与打开图标，深浅主题自适应。
- 关键建议：与 v149 九种富组件保持同一套视觉语法；元宝卡作为"展示型"组件，点击即触发浏览动作，无需命令总线。

### 🔧 排障手 + 质量门神（实现 / QA）
- 核心判断：仅在 `sealed interface` + `parseComponentSpec`/`when` 三处扩展一个新 `YuanbaoCard`，并在消息链接抽取 `extractInlineComponents` 增加一条正则；点击复用既有 `QuroBrowserBridge.open(url)`（与 `MediaPlayCard` 同一通道），零持久化改动。
- 关键建议：元宝域名走"预览卡"通道，其余外链保留既有 `ClickableText` 内联蓝字行为（双通道并存、不冲突）；正则仅匹配 `yb.tencent.com` / `yuanbao.tencent.com`，不影响其它链接。

> 本环境 gstack 子代理（product-reviewer / designer / investigator / qa-lead / security-officer）不可用，以上为同一主理人按各角色框架给出的结论，非多实例并行产出。

---

## 2. 综合审查发现（按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟢 | 功能 | `core/cards/QuroChatCard.kt` | 新增 `YuanbaoCard(id, title, url)` 密封子类 | 已落地 | 产品官/实现 |
| 2 | 🟢 | 功能 | `ui/QuroChatCards.kt` | `QuroChatCardView` 增加 `is YuanbaoCard` 分支 + `YuanbaoCardView`（点击 `QuroBrowserBridge.open(card.url)`） | 已落地 | 设计师/实现 |
| 3 | 🟢 | 功能 | `ui/ChatScreen.kt` | `extractInlineComponents` 正则抽取 `yb/yuanbao.tencent.com` 链接 → 渲染预览卡并剔除原文 URL | 已落地 | 实现/QA |
| 4 | 🟢 | 文档 | `core/tools/QuroToolsUiWidget.kt` | `ui_widget` 描述补充"元宝链接自动渲染预览卡"说明 + 错误提示补全类型 | 已落地 | 产品官 |
| 5 | 🟢 | 构建 | `app/build.gradle.kts` | versionCode/Name 149→150 | 已落地；隔离构建验证中 | QA |

---

## ✅ 行动清单（至少 3 条具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 装 v150 APK，发送/收到含 `https://yb.tencent.com/s/...` 的消息，验证气泡内出现「腾讯元宝回答」卡且点击打开应用内浏览器 | 用户 / 主理人 | P0 | 构建通过后 |
| 2 | 验证非元宝外链（如 `github.com`）仍走内联蓝字 `ClickableText`，不被误转为预览卡 | 主理人 | P1 | 构建通过后 |
| 3 | 若需升级为"所有外链通用预览卡"，将正则放宽并把域名带入卡片标题（后续迭代，不破坏现有双通道） | 主理人 | P2 | 后续 |

---

## ⚠️ 待完善 / 已知局限

- 本期仅做**链接预览 + 应用内浏览器打开**，未抓取元宝回答正文做结构化展示；答案内容依赖 `QuroBrowserScreen` 的 WebView 渲染。
- 卡片点击走**应用内浏览器**（WebView 覆盖层），未尝试拉起元宝 App 深链；若需"跳元宝 App"，后续可在 `YuanbaoCardView` 增加 `Intent` 深链判断。
- 链接抽取只对**消息文本中的裸链接**生效；若 AI 把链接包进某个 JSON 组件，则走组件通道（不会出现重复卡）。
- 构建结果：✅ BUILD SUCCESSFUL（后台任务 `0xjpYm`，日志 `/tmp/v150_build.log`）；APK 已拷至桌面 `QuroAI-debug-2026-07-22-v150.apk`，旧 v149 已移至 `D:\QuroAI_old_apks_backup`。

---

## 📚 成员产出索引

- 主理人（产品官/设计师/实现/QA）原始产出：改动涉及 `QuroChatCard.kt`、`QuroChatCards.kt`、`ChatScreen.kt`、`QuroToolsUiWidget.kt`、`app/build.gradle.kts` 五个文件，diff 已落盘于工程内。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
