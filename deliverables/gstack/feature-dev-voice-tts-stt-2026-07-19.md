# 语音合成 (TTS) / 语音识别 (STT) 设置功能实现

**日期**：2026-07-19
**场景**：全流程交付（设计 + 实现 + 编译验证，无独立 QA/安全成员上场）
**参与成员**：主理人（沽思航）+ 设计师（gstack-designer，调度失败，未上场）
**版本**：versionCode 26 → 27，versionName 1.0.26 → 1.0.27

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟢 通过（编译成功、APK 已产出）
- 阻塞项数量：0（1 个成员调度失败，已主理人兜底，不影响交付）
- 下一步：真机安装 v27，进入「设置 → 语音合成 (TTS)」「设置 → 语音识别 (STT)」逐项验收（引擎/音色/语速/音高/试听；识别语言/部分结果）。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go（编译通过，待真机功能验收） |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 1（设计师不可用的流程缺口）/ 🟢 0 |
| 关键行动项 | 3 条（真机验收、清理死代码、可选设计复审） |
| 建议负责人 | QuroAI 维护者 / 主理人 |

---

## 1. 各成员核心结论

### 🔍 产品官（产品评审）
- 未单独上场。需求明确：将设置里「语音合成 (TTS)」「语音识别 (STT)」两个原本空心（仅占位、写入 prefs 但从不应用）的入口，做成真正可配置的独立子页，并与既有「语音设定」入口共存。已按既有 MoWenApp 风格落地。

### 🎨 设计师（设计系统与视觉）
- **未上场（调度失败）**：gstack-designer 在本环境不可用（"Task agent gstack-designer is not available"）。未产出任何设计评审/视觉规范。以下内容由主理人按既有约定（MoWenApp 风格 + Accent 陶土色 + Material `Icons.Filled.*`）自行实现，**未经过设计师独立复核**，特此声明，避免伪造成员产出。

### 🛡️ 安全卫士（OWASP+STRIDE 审计）
- 未上场。本变更仅涉及本地 SharedPreferences 与系统 TTS/语音识别引擎调用，无网络、无权限新增、无外部输入解析，攻击面无显著变化。

### ✅ 质量门神（QA测试与发布）
- 未上场。已完成的验证为**编译级**：`./gradlew assembleDebug` BUILD SUCCESSFUL（26s，仅有历史遗留 deprecation 警告，无新错误）。功能级验收（真机朗读/识别）待用户安装后确认。

### 🔧 排障手（调试与根因）
- 修复 2 个编译期阻断：
  1. **重复类定义**：旧文件 `QuroToolsTts.kt` 与新文件 `QuroTtsHolder.kt` 同时声明 `object QuroTtsHolder` / `SpeakTool` / `StopSpeakTool`，导致重定义冲突。已删除旧 `QuroToolsTts.kt`（无任何引用，grep 零命中）。
  2. **缺失 import**：`QuroTtsSettingsScreen.kt` 使用 `Modifier.border(...)` 但未导入 `androidx.compose.foundation.border`，首次编译报 `Unresolved reference 'border'`。已补 import。

> 只包含本次实际上场的成员（主理人实现 + 排障手结论）；设计师/产品官/安全卫士/质量门神因未上场或未独立产出，不列其原文。

---

## 2. 综合审查发现（去重合并后按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟡 | 流程 | 团队调度 | gstack-designer 在本环境不可用，UI 未经独立设计复审 | 环境就绪后重跑设计师评审，或主理人按约定补一份视觉自检 | 主理人 |
| 2 | 🟢 | 代码质量 | core/tools/QuroToolsTts.kt | 旧 TTS holder 文件残留，与新文件重复定义同类 | 已删除 | 排障手 |
| 3 | 🟢 | 编译 | ui/QuroTtsSettingsScreen.kt:137 | 缺 `border` import | 已补 `androidx.compose.foundation.border` | 排障手 |

---

## ✅ 行动清单（至少 3 条具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 真机安装 v27，进入「设置→语音合成 (TTS)」逐项验收：切换引擎/音色、拖动语速/音高、点「试听」确认朗读生效 | QuroAI 维护者 | P0 | 安装后即时 |
| 2 | 真机验收「设置→语音识别 (STT)」：切换识别语言、开/关「部分结果」，在悬浮语音球实测识别语言是否跟随 | QuroAI 维护者 | P0 | 安装后即时 |
| 3 | 清理死代码：旧 `QuroVoiceSettingsScreen` 仍读写 `quro_voice` prefs 但无任何消费点（与 TTS/STT 的 `quro_tts`/`quro_stt` 并存，易混淆），择机统一或标注废弃 | 主理人 | P2 | 后续迭代 |
| 4 | 设计师可用后，补一次 TTS/STT 子页视觉复审（对齐 MoWenApp 风格、Accent 配色一致性） | 设计师 | P2 | 环境恢复后 |

---

## ⚠️ 待完善 / 已知局限

- **设计师未上场**：本环境 `gstack-designer` 不可用，UI 实现未经独立设计评审，风险已标注为 🟡 流程缺口，非功能缺陷。
- **功能级 QA 缺位**：仅完成编译验证，未做真机朗读/识别端到端验证（受限于无设备/无 QA 成员）。
- **旧「语音设定」入口仍并存**：`quro_voice` prefs 当前无消费点，属遗留死配置，与本功能的 `quro_tts`/`quro_stt` 无耦合，不影响本次行为，但建议后续归并。
- **TTS 引擎切换为全局重建**：`QuroTtsHolder.recreate` 会 shutdown 旧实例并新建，若正在朗读会被中断；属预期行为（切换引擎必须重建）。

---

## 交付清单

### 代码变更
- 新增 `core/tools/QuroTtsHolder.kt`（`object QuroTtsHolder` 增强：`recreate`/`getVoices`/`getEngines`；`speak()` 朗读前调用 `QuroTtsPrefs.applyTo`）+ `object QuroTtsPrefs`（语言/引擎/音色/语速/音高 持久化与应用）。
- 新增 `core/tools/QuroSttPrefs.kt`（`object QuroSttPrefs`：识别语言 + 部分结果开关）。
- 新增 `ui/QuroTtsSettingsScreen.kt`（语言/引擎/音色单选/语速/音高滑块/试听）。
- 新增 `ui/QuroSttSettingsScreen.kt`（识别语言/部分结果开关）。
- 修改 `service/QuroVoiceBallService.kt`：`speak()` 前 `QuroTtsPrefs.applyTo`；`startListening()` 的 `EXTRA_LANGUAGE`/`EXTRA_PARTIAL_RESULTS` 改读 `QuroSttPrefs`。
- 修改 `ui/ChatScreen.kt`：新增 `showTts`/`showStt` 状态；设置底部弹层 TTS/STT 行由原来指向旧「语音设定」改为分别打开新子页；新增两个全屏覆盖层渲染块。
- 删除 `core/tools/QuroToolsTts.kt`（重复类，已无引用）。
- `build.gradle.kts`：versionCode 26→27，versionName 1.0.26→1.0.27。

### 测试覆盖
- 编译：`assembleDebug` BUILD SUCCESSFUL（仅历史 deprecation 警告）。
- 功能级：未执行（待真机）。

### 发布检查清单
- [x] 编译通过
- [x] versionCode/versionName 已升
- [x] APK 产出并命名 `QuroAI-debug-2026-07-19-v27.apk` 置于桌面
- [x] 旧 v26 APK 已归档至 `QuroAI_old_apks/`
- [ ] 真机功能验收（TTS 朗读/STT 识别）
- [ ] 设计复审（待设计师可用）

### 回滚预案
- 若 v27 出现运行期 TTS/STT 异常：桌面已保留 v26（`QuroAI_old_apks/QuroAI-debug-2026-07-19-v26.apk`）可回装；代码回滚只需 `git revert` 本次涉及提交（QuroTtsHolder/QuroSttPrefs/两个设置屏/ChatScreen/QuroVoiceBallService + 恢复 `QuroToolsTts.kt` 旧文件）。

---

## 📚 成员产出索引

- gstack-designer（设计师）原始产出：无（调度失败，未上场）
- 主理人实现说明：见「交付清单 / 代码变更」
- 排障手结论：见「各成员核心结论 → 排障手」与「综合审查发现 #2/#3」

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。

---

## ➕ v28 范围收敛追加（2026-07-19，用户语音指令）

**用户指令**："语音合成先做使用手机系统TTS的云模型的后面再做，文本语音可以使用手机本地的也可以拉取现在已经配置的模型的"

**范围裁定**
- TTS **现在只用手机系统 TTS 引擎（本地）** —— 即 v27 已实现部分，保持不变。
- **云模型 / 已配置模型 TTS 放后面做**（defer，本版不实现）。
- 设计上 TTS 应支持两种来源：**本地系统引擎**（可用）与 **已配置模型**（拉取对话中已配置的 AI 模型配音，敬请期待）。据此在设置页新增「语音来源」选择，作为后续扩展点。

**变更清单（v27 → v28）**
- `core/tools/QuroTtsHolder.kt`（`QuroTtsPrefs`）：新增 `tts_source`（key `tts_source`，默认 `local`；常量 `SOURCE_LOCAL` / `SOURCE_MODEL`）。
- `ui/QuroTtsSettingsScreen.kt`：
  - 顶部新增「语音来源」卡片（两个 RadioButton：本地系统 / 已配置模型）。
  - `source == local` 才显示语言/引擎/音色/语速/音高/试听；`source == model` 显示"敬请期待"占位卡并隐藏试听。
  - 朗读与悬浮语音球**当前不读取 source**（model 未实现，仍走本地系统引擎）。
- `app/build.gradle.kts`：versionCode 27 → 28，versionName 1.0.27 → 1.0.28。

**构建**：`clean assembleDebug` BUILD SUCCESSFUL（43s，仅历史 deprecation 警告）。
**产物**：`QuroAI-debug-2026-07-19-v28.apk`（桌面）；aapt 确认 versionCode=28 / versionName=1.0.28；v27 已归档 `QuroAI_old_apks/`。

**后续 TODO**
- 实现 `source == model` 路径：直接调用对话中已配置 AI 模型的 TTS 接口生成音频（届时 `QuroTtsHolder.speak` / `SpeakTool` / 悬浮语音球需按 source 分支）。
- 旧「语音设定」`quro_voice` 死配置归并（与 `quro_tts` / `quro_stt` 并存）。
