# 悬浮语音球增强交付报告（拖动 + 实时对话 + TTS/STT 饰配）

**日期**：2026-07-19
**场景**：全流程交付（产品评审 → 实现 → QA 测试 + 发布）
**参与成员**：产品官（gstack-product-reviewer）+ 排障手（gstack-investigator）+ 质量门神（gstack-qa-lead）

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟢 通过（v42 已编译通过并产出桌面 APK）
- 悬浮语音球完成三项增强：**饰配既有 TTS/STT 能力**、**屏幕内任意拖动**、**开开关即进入实时对话循环（听→LLM→TTS→续听）**，无需每轮点击/长按。
- QA 独立构建并审查出 4 处缺陷（1 中 / 2 轻 / 1 绿），实现成员已并入修复，主理人补 `import kotlinx.coroutines.cancel` 解决编译阻塞，最终 `BUILD SUCCESSFUL`（42s）。
- 阻塞项数量：0（编译错误已解决）
- 下一步：桌面安装 `QuroAI-debug-2026-07-19-v42.apk` 真机验证实时对话回声表现；Phase 2 处理单例 Recognizer 并发与日志串扰。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go |
| 严重度分布 | 🔴 0 / 🟠 1 / 🟡 2 / 🟢 1 |
| 关键行动项 | 4 条（见行动清单） |
| 建议负责人 | 实现成员 / 主理人复核 |

---

## 1. 各成员核心结论

### 🔍 产品官（产品评审）
- 核心判断：用户「语音球没开发 TTS/STT」是旧印象——v39/v41 已将 `QuroTtsHolder` 单例与 `QuroSttHolder` 接入语音球；本轮真正缺口是**拖动**与**开开关即实时对话**。
- 关键建议：实时对话应采用「`conversationActive` 主开关 + `onDone` 续听」状态机，避免回声与抢占；球点击语义改为暂停/恢复而非单轮触发。评审方案已落盘 `deliverables/gstack/scope-review-voiceball-realtime.md`。

### 🔧 排障手（调试与根因）
- 核心判断：编译失败根因是 `coroutineContext.cancel()` 缺 `kotlinx.coroutines.cancel` 扩展导入（非重复调用问题）；运行时回声根因是 `speak` 失败回退 `speakMinimal` 导致 `onDone` 双触发。
- 关键建议：用 `AtomicBoolean` 一次性守卫隔离 `onDone`；`onDestroy` 取消协程作用域；`isRecognitionAvailable==false` 时主动 `stopConversation()` 防止卡死。

### ✅ 质量门神（QA测试与发布）
- 核心判断：实现成员「假完成」问题再次出现（曾报完成但桌面 APK 缺失/旧构建），主理人已亲自验证 `app-debug.apk` 实物并覆盖桌面。四项缺陷均已落入文件，最终构建通过。
- 关键建议：出包铁律——任何实现成员出包前必须真跑 `clean assembleDebug` 并确认 `app/build/outputs/apk/debug/app-debug.apk` 产出；主理人须核验 APK 实物再交付。

---

## 交付清单（代码变更 + 测试覆盖 + 发布检查清单 + 回滚预案）

**代码变更**
- `QuroVoiceBallService.kt`：新增 `conversationActive`/`speaking`/`emptyCount` 状态与常量 `MOVE_THRESHOLD_DP=8`/`BACKOFF_MS=600L`/`MAX_CONSECUTIVE_EMPTY=3`；`addBall()` 改绝对坐标 + `setOnTouchListener` 拖动（阈值区分点击/拖动）；`onBallClick()`→暂停/恢复；`startListening()` 加 `isRecognitionAvailable` 守卫；`process()`→`speak{ 续听 }`；新增 `speak` 包装含 `AtomicBoolean` once-guard；`onDestroy()` 加 `coroutineContext.cancel()`；补 `import kotlinx.coroutines.cancel`。
- `QuroVoiceBallView.kt`：签名改 `QuroVoiceBall(listening, speaking, paused, status)`，去掉点击事件，着色 listening=红 / speaking=蓝 / paused=灰+边框。
- `QuroTtsHolder.kt`：新增 `doneCallbacks: ConcurrentHashMap<String, (() -> Unit)?>`，`speak`/`speakMinimal` 支持 `onDone`，`UtteranceProgressListener.onDone(u)` 按 id 触发回调。
- `app/build.gradle.kts`：`versionCode 42` / `versionName "1.0.42"`。

**测试覆盖**：QA 独立 `clean assembleDebug` 通过；静态审查 4 处缺陷已修复并复核落盘；真机交互（拖动、实时对话回声、识别不可用）待用户安装后验证。

**发布检查清单**
- [x] `BUILD SUCCESSFUL`，APK 实物存在（25,014,214 B，13:45）
- [x] `versionCode`/`versionName` 已升
- [x] 桌面 APK 命名 `QuroAI-debug-2026-07-19-v42.apk`
- [ ] 真机验证实时对话回声表现
- [ ] 验证 Android 11+ 包可见性（TTS 引擎绑定，已用 QUERY_ALL_PACKAGES）

**回滚预案**：保留 v41 产物；若实时对话回声不可接受，将 `QuroVoiceBallService` 的 `startConversation()` 改为默认不自动续听（仅首轮），或临时将 `MAX_CONSECUTIVE_EMPTY` 调小；必要时 `git revert` 至 v41 提交并重编。

---

## 2. 综合审查发现（去重合并后按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟠 | 运行时/回声 | QuroVoiceBallService.kt `speak` 包装 | `speak` 入队失败立即 invoke `onDone`（→马上续听），随后 `speakMinimal` 播完再 invoke，导致播放期间抢听（回声根因） | `AtomicBoolean` 一次性守卫，仅触发一次 | 质量门神 + 排障手 |
| 2 | 🟡 | UI 语义 | QuroVoiceBallService.kt:173 | 原传 `conversationActive` 给 paused，着色语义反（激活态显示成暂停灰） | 改为传 `!conversationActive` | 排障手 |
| 3 | 🟡 | 生命周期 | QuroVoiceBallService.kt `onDestroy` | Service 销毁未取消协程作用域，续听/播放协程可能泄漏 | `coroutineContext.cancel()`（需补 `import kotlinx.coroutines.cancel`） | 排障手 |
| 4 | 🟢 | 健壮性 | QuroVoiceBallService.kt `startListening` | `SpeechRecognizer.isRecognitionAvailable==false` 时不退出，可能卡死 | 该分支主动 `stopConversation()` | 质量门神 |

---

## ✅ 行动清单（至少 3 条具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 真机安装 v42 APK，验证拖动顺滑、实时对话回声可控（建议戴耳机） | 用户 | P1 | 安装后当天 |
| 2 | Phase 2：解决单例 `SpeechRecognizer` 在「设置页测试 vs 后台语音球」并发互相销毁（`QuroSttHolder` 互斥/独立实例） | 实现成员 | P2 | 下个迭代 |
| 3 | Phase 2：修复 `QuroSttHolder` 日志回调跨界面串扰（语音球日志混入设置页） | 实现成员 | P2 | 下个迭代 |
| 4 | 出包铁律固化：实现成员交付前必须真跑 `clean assembleDebug` 并核实 `app-debug.apk` 实物，主理人核验后再交付 | 主理人 | P0 | 立即 |

---

## ⚠️ 待完善 / 已知局限

- 单例 `SpeechRecognizer` 并发互斥问题（v41 已知 🟡）：设置页测试与后台语音球会互相 `destroy`，Phase 2 做互斥/独立实例。
- 日志回调串扰（v41 已知 🟡）：语音球 `pushLog` 混入设置页，Holder 改用回调集合或语音球自记。
- 实时对话当前走**原生 `SpeechRecognizer`**；选「AI 模型」转写时回退原生，真实 `/audio/transcriptions` 转写属 Phase 2（有触发条件才做）。
- Android 11+（API 30+）包可见性铁律（v40）：绑定其他 app 的 TTS Service 需 Manifest `<queries>` 或 `QUERY_ALL_PACKAGES`；本 app 已加，勿删。
- 回声抑制仅靠 `onDone` 续听 + 600ms 退避 + 连续 3 次无语音自动暂停；若设备 STT 与 TTS 同时外放仍可能回声，后续可加音频路由/采集控制。
- 旧「语音设定」入口（`quro_voice` prefs）仍为无消费点死配置，与 TTS/STT 并存，后续择机归并。

---

## 📚 成员产出索引

- gstack-product-reviewer（产品官）原始产出：`deliverables/gstack/scope-review-voiceball-realtime.md`（评审方案）
- gstack-investigator（排障手）原始产出：根因分析（编译 `cancel` 导入缺失 + 运行时 `onDone` 双触发），已并入实现
- gstack-qa-lead（质量门神）原始产出：独立构建验证 + 4 项缺陷审查，已并入实现
- 实现成员产出：上述全部代码变更已落入 `QuroVoiceBallService.kt` / `QuroVoiceBallView.kt` / `QuroTtsHolder.kt` / `app/build.gradle.kts`

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
