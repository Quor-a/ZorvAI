# 情绪标签「流式路径」修复（v349 续修）

**日期**：2026-07-27
**场景**：调试复盘（情绪/风格标签解析 + 流式合成）
**参与成员**：排障手（调试与根因）

---

## 📌 TL;DR（执行摘要，3-5 行）
- 整体结论：🟢 通过（已修复并通过构建验证）
- 用户澄清：所谓"所有 `()` 都消失" = **标签在流式合成路径被全部剥掉，导致语音毫无情绪**。
- 根因：`QuroTtsClients.mimoStream` 写死 `QuroVoiceStyle.strip(req.text)`，而**流式是默认低延迟路径** → 全部情绪标签被剥光后才送 MiMo → 无情绪。
- 修复：新增 `QuroVoiceStyle.toMimoMarkup`（按 MiMo `(标签) 文本` 格式重建、保留标签），`mimoStream` 改用它；配合 v348 多标签解析，`(唱歌)(东北话)` 在 MiMo 流式/非流式均生效。
- 已构建 v349 APK（`QuroAI-debug-2026-07-27-v349.apk`）并通过 `cmp` 校验。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 1 / 🟢 0 |
| 关键行动项 | 3 条 |
| 建议负责人 | 排障手 |

---

## 1. 各成员核心结论（每位 1 段，别整段复制成员原文）

### 🔧 排障手（调试与根因）
- 核心判断：用户说"所有 `()` 消失、要语音真出情绪"，顺着数据流追到 `mimoStream`（`QuroTtsClients.kt:502`）——它把整段文本 `strip` 后才送 MiMo，而流式是默认路径，等于只要是流式 MiMo，情绪标签**全没了**。v348 只修了非流式 `segment` 那段，漏了流式。
- 关键建议：新增 `QuroVoiceStyle.toMimoMarkup(input, availableTags)`，按行把行首标签重建为 `(标签) 文本` 的 MiMo 标记格式（复用 v348 的 `extractLeadingMarkers`，多标签 `(a)(b)`/`(a b)` 同样保留）；`mimoStream` 改用它，不再 `strip`。Gradle 构建 `BUILD SUCCESSFUL`。

> 仅排障手上场，其余成员未参与。

---

## 2. 综合审查发现（去重合并后按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟡 | 功能 | `QuroTtsClients.kt:502`（旧 `mimoStream`） | 流式 MiMo 合成前 `strip` 剥光全部情绪标签 → 语音无情绪（与 v348 非流式修复脱节） | `mimoStream` 改用 `toMimoMarkup` 保留标签 | 排障手 |
| 2 | 🟢 | 已修 | `QuroVoiceStyle.kt`（v348） | 多标签连写 `(a)(b)` 仅解析首标签（前次已修，本次作为配套确认） | 已由 `extractLeadingMarkers` 覆盖 | 排障手 |

---

## 交付清单（代码变更 + 测试覆盖 + 发布检查清单 + 回滚预案）

- **代码变更**：
  - `QuroVoiceStyle.kt`：新增 `toMimoMarkup(input, availableTags)`（按行重建 MiMo 标记格式 `(标签) 文本`，复用 `extractLeadingMarkers`，白名单容错），置于 `strip` 之后。
  - `QuroTtsClients.kt`：`mimoStream` 内 `val assistantContent = QuroVoiceStyle.strip(req.text)` → 改为先取 `availableTags = req.def.providerTags.takeIf{...} ?: QuroCloudTtsCatalog.EMOTION_TAGS`，再 `QuroVoiceStyle.toMimoMarkup(req.text, availableTags)`。
  - `app/build.gradle.kts`：`versionCode 348 → 349`、`versionName "1.0.348" → "1.0.349"`。
- **测试覆盖**：Python 等价预演 `toMimoMarkup` 用例——`(唱歌)(东北话) 文本 → (唱歌 东北话) 文本`、`(开心) 你好 → (开心) 你好`、普通行原样、未闭合括号容错；非流式 `segment`/`mimoSynthOne` 重建逻辑（v348 已验证）与流式 `toMimoMarkup` 输出格式一致。
- **发布检查清单**：✅ 编译通过（仅历史 deprecation 警告，无错误）｜✅ APK 落桌面并 `cmp` 一致（CMP_OK）｜✅ 版本号自增。
- **回滚预案**：git 回退 `QuroVoiceStyle.kt` / `QuroTtsClients.kt` / `app/build.gradle.kts` 三文件至 v348 提交即可完整回滚。

---

## ✅ 行动清单（至少 3 条具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 真机验收：MiMo 源 + 默认（流式）下，`(唱歌)(东北话) 文本` 是否真的带唱歌+东北话音色 | 用户/QA | P1 | 装包后 |
| 2 | 非 MiMo 云服务商（Edge/火山/讯飞/腾讯/MiniMax）仍不解析中文括号、无真情感 —— 若用户要在这些源也真合成，需把括号翻译为各服务商 style 参数（较大改造，下个迭代） | 排障手 | P2 | 下个迭代 |
| 3 | 排查其它流式云客户端（火山/讯飞等若也走流式且 strip）是否同样丢失情绪，统一处理 | 排障手 | P3 | 下个迭代 |

---

## ⚠️ 待完善 / 已知局限

- **非 MiMo 云服务商 / 本地系统 TTS**：中文括号仍被剥离、无真情感合成，仅靠 `systemHintNatural` 的自然语言近似（v347 即标注的已知局限，本次未变）。**真·情感出声必须选小米 MiMo**。
- 流式 `toMimoMarkup` 是整段一次性送 MiMo，不如非流式逐段精细切换情绪；但用户主用例（单行 `(唱歌)(东北话)`）正常。
- 本环境无真机/语音实测，仅编译级 + 格式级验证，需用户实机听感复验。

---

## 📚 成员产出索引

- 排障手（排障手）原始产出：本会话内主理人直接执行——根因定位（`mimoStream` 剥光标签）+ 新增 `toMimoMarkup` + 改写 `mimoStream` + 构建与 APK 交付，详见本报告正文。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
