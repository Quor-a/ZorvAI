# QuroAI TTS 试听修复 + 自定义输入 (v30)

**日期**：2026-07-19
**场景**：调试复盘 + 小功能交付（TTS 试听静默根因 + 试听文本框）
**参与成员**：主理人直接处理（本环境 gstack-investigator / gstack-designer 子代理不可用，按约定由主理人代行排障与实现，未伪造成员产出）

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟢 通过（编译成功，逻辑已修正并出包）
- 阻塞项数量：0（编译错误已修复，APK 已产出）
- 用户原点："TTS 点试听还是不行" + "试听应允许输入文本再朗读" + "手机 TTS 不可能初始化失败"。
- 根因收敛：在"初始化不会失败"前提下，静默只能是**朗读期失败**——所选语言语音包缺失导致 `setLanguage` 落到缺失数据 → `speak` 无输出；以及固定 `utteranceId` 被部分引擎丢弃。
- 本轮改动：语言不可用时跳过 `setLanguage` 退回引擎默认语言、每次唯一 `utteranceId`、`speak` 返回状态码、设置页新增"试听文本"输入框并把初始化/朗读失败**显式显示**出来（不再静默）。
- 下一步：真机安装 `QuroAI-debug-2026-07-19-v30.apk`，进"语音合成 (TTS)"→ 输入文本 → 点"试听"。若仍有问题，按钮下方会显示具体状态（初始化失败 / 朗读调用失败 r=-2），据此继续定位。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go（已出包，待真机验证发声） |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 1（诊断可见性，已补）/ 🟢 1（静默根因已修） |
| 关键行动项 | 3 条（见下） |
| 建议负责人 | QuroAI 主理人（Gu） |

---

## 1. 各成员核心结论

### 🔧 排障手（调试与根因，由主理人代行）
- 核心判断：v29 已修掉"初始化进行中回调丢失"路径，但用户仍报静默。结合用户明确"手机 TTS 不可能初始化失败"，将怀疑面从"初始化"收窄到"朗读期"：`setLanguage` 指向未安装语音包的语言（如 zh-TW / yue-Hant / ja-JP 等）时，部分系统引擎 `speak` 直接无输出；固定 utteranceId `quro-tts` 也可能被引擎去重丢弃。
- 关键建议：① 语言不可用时跳过 `setLanguage`，让引擎用默认语言出声；② 每次用唯一 `utteranceId`；③ 把 `speak` / `ensure` 的结果显式回显到 UI，杜绝"点到没声但无任何提示"。

### 🎨 设计师（设置 UI，由主理人代行）
- 核心判断：原"试听"硬编码固定文案，不满足"输入文本再朗读"的诉求。
- 关键建议：在"试听"按钮上方加一个 `OutlinedTextField` 作为"试听文本"，默认填充原测试句；按钮朗读该输入框内容（空则兜底一个空格），并在下方用 Accent / error 色显示状态。

---

## 2. 综合审查发现（去重合并后按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟡 | 可观测性 | `QuroTtsSettingsScreen.kt` | 试听失败全程静默，用户无法区分"没初始化 / 没出声 / 语言不支持" | 回显 `ensure`/`speak` 返回状态 | 排障手 |
| 2 | 🟢 | 健壮性 | `QuroTtsHolder.kt` / `QuroVoiceBallService.kt` | 所选语言语音包缺失时 `setLanguage` 导致静默；固定 utteranceId 可能被引擎丢弃 | 语言不可用跳过 setLanguage + 唯一 utteranceId | 排障手 |

### 关键代码变更

**`core/tools/QuroTtsHolder.kt`**
- `applyTo(ctx, tts, applyLanguage: Boolean = true)`：新增 `applyLanguage` 形参，`if (applyLanguage)` 才调用 `setLanguage`；`setVoice` 仍按"当前引擎确实存在该 voice"才设置（原本已做此保护）。
- `isLanguageAvailable(ctx)`：`runCatching { t.isLanguageAvailable(...) >= 0 }.getOrDefault(false)`（注意 `>= 0` 让 `runCatching` 捕获 `Boolean`，否则 Kotlin 推断为 `Comparable & Serializable` 导致编译失败——这是首编报错的根因）。
- `speak(text)`：先 `isLanguageAvailable`；`applyTo(ctx, t, applyLanguage = langOk)`；唯一 `utteranceId = "quro-tts-${System.nanoTime()}"`；返回 `0`(已入队)/`-1`(未就绪)/`-2`(调用失败)。
- `ensure(context, onResult: (Boolean) -> Unit)`：初始化成功或失败都会回调 `onResult(ready)`，失败也回调，便于报错而非永久静默（v29 已落地，本轮沿用）。

**`ui/QuroTtsSettingsScreen.kt`**
- 新增 `previewText` 状态（默认原测试句）与 `speakStatus` 状态。
- 新增 `OutlinedTextField`（"试听文本"，2–4 行）。
- "试听"按钮：`ensure(ctx) { ok -> if(!ok) 显示"初始化失败" else 显示 speak 返回状态 }`，朗读 `previewText.ifBlank { " " }`。

**`service/QuroVoiceBallService.kt`**
- `speak(text)`：同样用 `langOk = isLanguageAvailable >= 0` 决定是否 `applyLanguage`；utteranceId 改为 `"quro_tts_${System.nanoTime()}"`（与设置页一致的逻辑，避免语音球同类静默）。

**`app/build.gradle.kts`**
- versionCode 29 → 30，versionName "1.0.29" → "1.0.30"。

---

## ✅ 行动清单（具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 真机安装 v31 APK，进"语音合成 (TTS)"，输入文本点"试听"验证发声（"初始化失败"已修复为假失败） | 用户/主理人 | P0 | 即时 |
| 2 | 若仍无声：读取按钮下方状态——"初始化失败"→查系统 TTS 引擎设置；"朗读调用失败(r=-2)"→换语言/装语音包后重试 | 主理人 | P1 | 反馈后 |
| 3 | 后续若开放"已配置模型"TTS 来源，沿用本套 `speak` 返回码 + 状态回显约定 | 主理人 | P2 | v31+ |

---

## ⚠️ 待完善 / 已知局限

- 本环境无法真机播放，发声需用户真机确认；代码层面已消除静默失败路径并加上可见诊断。
- `model` 语音来源仍为占位（"敬请期待"），与 `local` 共用 `applyTo`，尚未实现云端/模型配音。
- 旧 `quro_voice` 预存的"语音设定"入口仍是无消费点的死配置，后续择机归并到 `quro_tts`。
- 首次编译曾因 `runCatching { isLanguageAvailable(...) }.getOrDefault(false)` 推断非 `Boolean` 而失败，已用 `>= 0` 修正；该写法若复用请留意。

---

## v31 补丁（ensure 初始化竞态修复）

**用户反馈**：手机系统 TTS 本身正常，但设置页点「试听」却显示"初始化失败"。
**根因（排障手，主理人代行）**：v30 的 `QuroTtsHolder.ensure()` 使用 `when { tts==null -> 建实例+入队; else -> onResult(ready) }`。问题在于 `TextToSpeech(ctx)` 的实例是**同步赋值**的，而其 `OnInitListener` 是**异步回调**。于是用户进设置页（LaunchedEffect 触发首次 ensure 开始初始化）后若**在初始化回调尚未触发前**就点「试听」，第二次 ensure 看到 `tts != null` 立刻走 `else` 分支返回 `ready=false` → UI 误报"初始化失败"。这是逻辑竞态产生的**假失败**，与手机 TTS 是否正常无关。

**修复（`core/tools/QuroTtsHolder.kt`）**：把 ensure 改成显式状态机，新增 `initializing` 标志与 `startInit`/`reinit` 私有方法：
- `ready` → 直接 `onResult(true)`；
- `tts == null` → `startInit`（建实例 + `initializing=true` + 入队）；
- `initializing`（实例已建、回调未触发）→ **入队等待真实结果**，不再返回 false；
- `initError`（上次失败，多为引擎首启偶发）→ `reinit` 销毁重建一次再等；
- 兜底 `else` → 入队等回调。
初始化回调里统一置 `ready/initError/initializing` 并触发 `pending` 队列。这样无论用户多快点「试听」，拿到的都是**真实**初始化结果，杜绝假"初始化失败"。

**`app/build.gradle.kts`**：versionCode 30 → 31，versionName "1.0.30" → "1.0.31"。
**构建**：`clean assembleDebug` BUILD SUCCESSFUL(1m7s)。APK 桌面 `QuroAI-debug-2026-07-19-v31.apk`（v30 归档 `QuroAI_old_apks/`）。
**验证预期**：装 v31 后进「语音合成 (TTS)」→ 输入文本 → 点「试听」应正常朗读；"初始化失败"仅在 TTS 真正不可用时才出现（此时确应检查系统 TTS 引擎设置）。

---

## 📚 成员产出索引

- 排障手（调试根因）：由主理人直接执行，结论见 §1 / §2。
- 设计师（设置 UI）：由主理人直接执行，结论见 §1。
- 未上场：产品官、安全卫士、质量门神（本轮为单路由排障+小改，未触发多成员协作）。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
