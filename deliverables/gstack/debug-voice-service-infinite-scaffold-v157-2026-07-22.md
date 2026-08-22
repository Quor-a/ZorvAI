# 调试复盘：TTS 页「前往语音服务设置」无限高度崩溃（v157）

**日期**：2026-07-22
**场景**：调试复盘（崩溃根因定位 + 修复）
**参与成员**：排障手（gstack-investigator）

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟢 已定位并修复（v157，BUILD SUCCESSFUL）
- 崩溃：`java.lang.IllegalStateException: Size(986 x 2147483647) is out of range`
- 关键澄清：**v154/v155（浏览器）与 v156（`fillMaxSize()`）的修复方向全部错误**，真正崩溃源是 **TTS 设置页内嵌的 `QuroVoiceServiceScreen` 自带 Scaffold，被放进父级 `verticalScroll` Column → 拿到无限高度约束**。
- 下一步：用户卸载旧包、装 v157（1.0.157）复测 TTS→语音服务导航。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 修复已出包（v157 / 1.0.157） |
| 严重度分布 | 🔴 1（崩溃）/ 🟢 0 / 🟡 0 |
| 关键行动项 | 1 条（装 v157 复测） |
| 建议负责人 | 排障手 / 用户侧验收 |

---

## 1. 各成员核心结论

### 🔧 排障手（调试与根因）
- 核心判断：崩溃栈顶 `ScaffoldKt.ScaffoldLayout` 表明某个 **Scaffold 在测量时收到 `maxHeight=Infinity`**（2147483647=Int.MAX_VALUE）。排查发现：`QuroTtsSettingsScreen.kt` 的 content `Column(...verticalScroll(...))` 在 `embeddedVoiceService=true` 时直接渲染 `QuroVoiceServiceScreen`，而后者顶层有独立 `Scaffold`。该 Scaffold 被 `verticalScroll` 包裹 → 收到无限高度 → 崩溃。
- 关键建议：嵌入式复用组件时**绝不可再套 Scaffold**；给 `QuroVoiceServiceScreen` 加 `embedded` 开关，内嵌态只渲染内容 `Column`。并修正此前 v156「删 `fillMaxSize()` 即可」的误诊。

> 仅排障手实际上场（单成员调试场景）。

---

## 2. 综合审查发现

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🔴 | 崩溃/布局 | `QuroTtsSettingsScreen.kt:112-125` + `QuroVoiceServiceScreen.kt:41` | `QuroVoiceServiceScreen` 自带 Scaffold 被嵌入父级 `verticalScroll` Column，内层 Scaffold 收到 `maxHeight=Infinity` → `Size(986 x 2147483647)` | 内嵌态去掉 Scaffold，只渲染内容 | 排障手 |

### 历史误诊记录（务必吸取）
- v154/v155：误判为「内置浏览器 WebView `AndroidView.weight` 无限约束」。用户纠正「不是浏览器」后此方向作废。
- v156：误判为「TTS/STT/VoiceService 三屏 content `Column(fillMaxSize())` 在有界 Surface 下拿无限约束」。实际 `Surface(heightIn(max=560.dp))` 给的是**有限**约束，删 `fillMaxSize()` 无害但**未触及真因**，故 v156 仍崩。
- **真因共性**：崩溃发生在 `Scaffold` 被放进 `verticalScroll` 等「给子项无限高度」的容器时。外层容器的有限约束管不到被 scroll 包裹的更深嵌套层。

---

## ✅ 行动清单

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 卸载旧包 → 装 v157（1.0.157）→ 进 TTS 设置页点「前往语音服务设置 ›」验证不崩 | 用户验收 | P0 | 立即 |
| 2 | 全工程建立约定：任何会被嵌入 `verticalScroll`/`Lazy` 的复用组件，禁止自带 Scaffold | 工程负责人 | P2 | 下个重构周期 |

---

## ⚠️ 待完善 / 已知局限

- 若装 v157 后仍在**其他路径**复现同类崩溃，需进一步排查是否还有别的 Scaffold 被嵌进 scroll（当前全工程复扫仅此一处）。
- 仍挂起（与本次无关）：MCP 部署到终端 + 实时更新 + 内置若干 MCP 服务器；MCP Server 端点 Bearer 鉴权 + 高危工具白名单（P1）。

---

## 📚 成员产出索引

- gstack-investigator（排障手）原始产出：源码审计结论 —— `QuroVoiceServiceScreen` 顶层 Scaffold 嵌于 `QuroTtsSettingsScreen` 的 `verticalScroll` Column 致无限高度约束；修复见 `QuroVoiceServiceScreen.kt`（`embedded` 参数）+ `QuroTtsSettingsScreen.kt:120`（`embedded = true`）。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
