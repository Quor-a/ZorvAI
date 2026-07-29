# TTS 试听无声 —— 参照 Calw OS 重写 QuroTtsHolder

**日期**：2026-07-19
**场景**：调试复盘（TTS 试听功能根因分析 + 原创重写）
**参与成员**：排障手（主理人亲自执行，环境内 investigator/designer 子代理不可用，已如实标注）

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟢 已修复并重写（编译通过、APK 产出，待真机验证）
- 阻塞项数量：0（编译通过）；1 个待真机确认项
- 下一步：装 v32 进「语音合成 (TTS)」→ 输入文本 → 点「试听」验证发声
- 关键动作：放弃此前三版（v29/v30/v31）的 `ensure` 回调状态机，参照上游 Calw OS `SimpleVoiceProvider` 的**已验证正确范式**，原创重写 `QuroTtsHolder`，根治“显示初始化失败 / 静默无声音”两类问题。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go（代码层已修复，待真机确认出声） |
| 严重度分布 | 🔴 0 / 🟠 1（试听功能不可用的根因）/ 🟡 1（稳健性）/ 🟢 0 |
| 关键行动项 | 3 条 |
| 建议负责人 | 主理人 / 用户真机验证 |

---

## 1. 各成员核心结论

### 🔧 排障手（调试与根因）
- **核心判断**：此前三版（v29/v30/v31）的 `QuroTtsHolder.ensure` 用 `pending` 回调 + `initializing` 标志的自定义状态机，存在两类硬伤：① 在 `TextToSpeech(ctx)` 的 `OnInitListener` lambda 内用 `tts?.` 访问对象字段——若 `OnInit` 在极端情况下同步触发，`tts` 字段尚未赋值，`speak` 里 `tts ?: return -1` 会**静默返回 -1（未就绪）**；② `speak` 用 `setLanguage(Locale.forLanguageTag("zh-CN"))` 一刀切，部分引擎中文语音包命名差异使 `isLanguageAvailable` 返回负，进而跳过 `setLanguage`，引擎退回默认（非中文）语言读中文文本 → 部分引擎静默。用户明确“手机 TTS 不可能初始化失败”，因此根因在**代码初始化时序与语言解析**，而非设备。
- **关键建议**：参照上游 Calw OS `SimpleVoiceProvider`（`AccessibilityVoiceProvider.kt`）的正确范式重写——用 `suspendCancellableCoroutine` + `CompletableDeferred` **真正 await 初始化完成**、用局部 `instance` 捕获规避同步 OnInit 竞态、`speak` 内自动兜底初始化、用 `resolveBestLocale`（语音列表精确匹配 → 同语言回退 → `isLanguageAvailable`）稳健解析、加 `UtteranceProgressListener`、utteranceId 用 `UUID`。

---

## 2. 综合审查发现（去重合并后按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟠 | 功能缺陷 | `QuroTtsHolder.ensure` / `speak` | 自定义回调状态机 + lambda 内用对象字段 `tts?`，在 OnInit 时序边界下误报“初始化失败”或静默返回未就绪；`setLanguage` 一刀切导致部分引擎中文无声 | 重写为 suspend + `CompletableDeferred` await 范式，局部 `instance` 捕获，稳健 locale 解析 | 排障手 |
| 2 | 🟡 | 稳健性 | `QuroTtsHolder.speak` | 未设 `UtteranceProgressListener`；utteranceId 用 `nanoTime()` 极端情况可能碰撞 | 已加 `UtteranceProgressListener`；utteranceId 改 `UUID.randomUUID()` | 排障手 |

---

## ✅ 行动清单（至少 3 条具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 真机安装 v32，进「语音合成 (TTS)」→ 输入文本 → 点「试听」验证发声；若仍无声，按钮下方状态会提示：①“初始化失败”→ 查系统 TTS 引擎；②“朗读调用失败(r=-2)”→ 换语言/装语音包；③“正在朗读…”仍听不到 → 设备媒体音量/蓝牙路由 | 用户/主理人 | P0 | 即时 |
| 2 | 验证悬浮语音球朗读（其使用独立 TTS 实例、不经 QuroTtsHolder，本次未改动；若同样无声，按同范式修复） | 用户/主理人 | P1 | 即时 |
| 3 | 后续清理 `quro_voice` 死配置（与 TTS/STT 并存、无消费点），择机归并 | 主理人 | P3 | 后续版本 |

---

## ⚠️ 待完善 / 已知局限

- 重写后的 `speak` 为 `suspend` 函数，调用方须位于协程上下文：设置页用 `rememberCoroutineScope().launch`；`SpeakTool.run` 用 `runBlocking`；语音球仍用**自有独立 TTS 实例**，本次未纳入重写，行为不变。
- 未做单元/UI 自动化测试（环境无设备），仅静态分析 + 编译通过 + 真机待验。
- `resolveBestLocale` 找不到任何匹配时**不 setLanguage**，交由引擎用默认语言保证出声（中文可能读近似音而非静默）——这是有意为之的“保证有声”兜底。

---

## 📚 成员产出索引

- 排障手（主理人执行）原始产出：本次根因分析 + `QuroTtsHolder.kt` 原创重写 + `QuroTtsSettingsScreen.kt` 协程化调用 + `SpeakTool.run` 改 `runBlocking` 调用 + 版本升 32 + 编译通过。

### 附：上游参照与本次原创差异（去品牌化）

- **参照来源**：`D:/Calw OS-project/Calw OS/app/src/main/java/com/ai/assistance/calw/os/api/voice/AccessibilityVoiceProvider.kt` 中的 `SimpleVoiceProvider`（系统 TTS 实现，经 `VoiceService` 抽象层调用）。
- **采用的正确范式**：`suspendCancellableCoroutine` 真正等待 `OnInit`；`speak` 内自动 `if (!ready) init`；`resolveBestLocale` 多层级回退匹配；`setOnUtteranceProgressListener`；`UUID` utteranceId。
- **未照搬的部分（去品牌化 / 简化）**：未引入 `VoiceService` 多供应商抽象（QuroAI 当前仅需系统本地 TTS），未引入 `ArrayDeque` 队列/暂停恢复等云端 TTS 才需要的复杂状态机；仅保留 QuroAI 既有的 `QuroTtsPrefs` 持久化与设置页 UI，逻辑重写为自研稳健实现。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
