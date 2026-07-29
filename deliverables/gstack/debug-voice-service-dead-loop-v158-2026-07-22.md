# 语音服务「进不去」根因复盘（v158）

**日期**：2026-07-22
**场景**：调试复盘（导航死循环 + 嵌套 Scaffold 崩溃收尾）
**参与成员**：排障手（调试与根因）

---

## 📌 TL;DR（执行摘要）
- 整体结论：🟢 已修复（v158 构建通过、已出包）
- 阻塞项数量：0
- v157 只解决了「闪退」没解决「进不去」；本版彻底删除 TTS 页内嵌语音服务方案，回归 ChatScreen 单一 Hub 路由。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go |
| 严重度分布 | 🔴 0 / 🟠 1（导航死循环，功能不可达）/ 🟡 0 / 🟢 0 |
| 关键行动项 | 1 条（删内嵌方案、三入口改 `onOpenCloudConfig`） |
| 建议负责人 | 排障手 |

---

## 1. 各成员核心结论

### 🔧 排障手（调试与根因）
- 核心判断：v157 修崩溃时保留了「TTS 页内嵌语音服务 Hub」的壳，但其三个卡片回调写死成死循环——`onOpenTts` 弹回 TTS、`onOpenStt/onOpenVoiceSettings` 又只 `showVoiceService=true` 重开同一 Hub，用户永远到不了真实子设置页，表现为「进不去」。ChatScreen 级 Hub（line 987）的回调（`onOpenTts→showTts` / `onOpenStt→showStt` / `onOpenVoiceSettings→showVoice`）才是正确的路由目标。
- 关键建议：彻底弃用内嵌方案，让 TTS 页三个入口直接调 `onOpenCloudConfig()`（= 关 TTS 面板、开 ChatScreen Hub），由 ChatScreen 统一路由。

---

## 2. 综合审查发现

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟠 | 导航 | QuroTtsSettingsScreen.kt（embeddedVoiceService 三入口 + QuroVoiceServiceScreen 内嵌回调） | 内嵌 Hub 回调死循环，点卡片弹回 TTS 或重开 Hub，无法进入 STT/语音设置子面板 | 删除内嵌方案，入口改 `onOpenCloudConfig()` | 排障手 |

---

## ✅ 行动清单

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | TTS 页三入口改 `onOpenCloudConfig()` + 删 QuroVoiceServiceScreen `embedded` 分支 | 排障手 | 已完成(v158) | — |

---

## ⚠️ 待完善 / 已知局限

- v146→v152→v157→v158 四轮迭代才彻底解决「TTS 页进语音服务」：初因是跨 overlay 时序竞争（v146），后误引入内嵌 Scaffold 崩溃（v152/v157），再误留内嵌死循环（v157 修崩时未清）。历史债已在 v158 清零。
- 端侧 ASR 资产、MCP Server Bearer 鉴权仍为既有挂起项（与本次无关）。

---

## 📚 成员产出索引

- 排障手原始产出：本次对话内联根因定位与修复（无独立子代理文件，gstack-* 子代理本运行时未注册）。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
