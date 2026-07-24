# QuroAI TTS 试听「未播放」根因审查 + 音频环境自检

**日期**：2026-07-19
**场景**：调试复盘（TTS 试听无声）
**参与成员**：排障手（主理人代行；investigator 子代理本环境不可用）

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟡 有条件通过（代码层无致死 bug，但"未播放"需真机诊断定位）
- 阻塞项数量：1（需用户在真机点「试听」后回看 UI 显示的诊断信息）
- 关键发现：逐行审查 QuroAI v33 与上游 Calw OS `SimpleVoiceProvider` 后，**两者代码层均无导致完全无声的硬性 bug**；"未播放"根因收敛到**设备/运行时层**（媒体音量=0、蓝牙路由、或特定 ROM 的引擎行为），这部分代码无法自解，必须在 UI 上亮出诊断
- 本轮交付：给 QuroAI 试听按钮加「音频环境自检」，点一下即显示媒体音量 / 路由 / speak 返回值

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟡 条件 Go（装 v34 真机验证 + 看诊断） |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 1（缺诊断可见性）/ 🟢 代码正确 |
| 关键行动项 | 3 条 |
| 建议负责人 | 用户真机验证 + 主理人据诊断结论收口 |

---

## 1. 排障手核心结论

- **核心判断**：QuroAI v33 `QuroTtsHolder` 与上游 `SimpleVoiceProvider` 代码路径一致且正确——`ensureReady` 真正 await `TextToSpeech.OnInit`、`speak` 自动兜底初始化、`QUEUE_FLUSH` 提交、`UUID` utteranceId、`resolveBestLocale` 多层回退、`applyParams` 失败仅 log 不阻止。"未播放"非代码致死 bug。
- **关键建议**：停止改代码猜根因；改为在 UI 暴露音频环境（音量/路由）+ speak 返回值，让"未播放"有可查原因。已落地 v34。

---

## 2. 综合审查发现

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟡 | 诊断可见性 | QuroTtsSettingsScreen 试听按钮 | 此前试听失败只在 logcat，UI 无原因，导致反复盲改 | v34 加 `audioDiagnostics` 显示音量/路由/返回值 | 排障手 |
| 2 | 🟢 | 代码正确性 | QuroTtsHolder.ensureReady | 同步 OnInit 竞态仅导致 listener/rate/pitch 在极端同步场景跳过，speak 内 `applyParams` 会补设，不影响出声 | 暂不改（改动有引入新 bug 风险） | 排障手 |
| 3 | 🟡 | 设备依赖 | 运行时 | "未播放"在代码健全时指向媒体音量=0 / 蓝牙路由 / 特定 ROM 引擎行为 | 真机据 v34 诊断结论反馈 | 排障手 |

---

## ✅ 行动清单

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 装 `QuroAI-debug-2026-07-19-v34.apk` → 设置 → 语音合成 (TTS) → 点「试听」，看按钮下方诊断 | 用户 | P0 | 即时 |
| 2 | 若诊断显示「媒体音量=0 ⚠️已静音」→ 调高媒体音量重试 | 用户 | P0 | 即时 |
| 3 | 若音量正常仍无输出 → 把诊断整行（含"路由="、"已发送朗读请求 ✅"）发回，据此定位引擎/路由问题 | 用户 + 主理人 | P1 | 即时 |

---

## ⚠️ 待完善 / 已知局限

- v34 仅增强「可见性」，未改变 TTS 调用路径；若真因是设备/ROM 特定行为，仍需据诊断结论二次处理。
- Calw OS 上游 `SimpleVoiceProvider` 在同设备也呈现"未播放"，印证问题在运行时层而非 QuroAI 自研代码。

---

## 代码改动摘要（v34）

- `core/tools/QuroTtsHolder.kt`：新增 `audioDiagnostics(ctx): String`，用 `AudioManager` 读取 `STREAM_MUSIC` 音量/最大音量、蓝牙 A2DP 状态、扬声器状态，拼成一行可读诊断。
- `ui/QuroTtsSettingsScreen.kt`：「试听」按钮点击后先取 `audioDiagnostics`，再 `ensureReady`+`speak`，状态文字含诊断行（媒体音量/路由）。
- `app/build.gradle.kts`：versionCode 33 → 34，versionName 1.0.33 → 1.0.34。

---

## 📚 成员产出索引

- 排障手（主理人代行）原始产出：逐行审查 `QuroTtsHolder.kt` / `QuroTtsSettingsScreen.kt` / 上游 `AccessibilityVoiceProvider.kt` / `TextToSpeechScreen.kt`，结论为代码层无致死 bug、根因在运行时层、补音频诊断可见性。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
