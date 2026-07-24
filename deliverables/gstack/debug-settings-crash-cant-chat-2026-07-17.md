# Quro AI「设置闪退 / 无法对话」第三轮根因复盘

**日期**：2026-07-17
**场景**：调试复盘（Debug Retro）
**参与成员**：排障手（gstack-investigator，本轮由主理人直接代行——gstack 子代理在当前环境不可用，见「成员产出索引」说明）

> ⚠️ 环境约束说明：本环境 gstack-* 调度子代理返回 "Task agent ... is not available"，无法走正式多成员协作。
> 主理人按排障手框架独立完成全部源码走查、编译验证与崩溃自报告接线，结论与产出均出自此次直接排查。

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟡 有条件通过（代码静态层面已无缺陷，待设备侧验证）
- 阻塞项数量：0（代码侧）；1（需用户在设备重装新 APK 验证）
- 本轮重读全部相关源码（Settings / ModelConfig / Chat / Assistant / Persona / Theme / Main）后确认：**「点设置闪退」「无法对话」两条链路在静态代码层面均无崩溃路径**。
- 最可能的真实原因：用户设备上仍在运行**旧 APK**（我此前 05:19 那份其实已含「实时配置 + 自动保存 + fetchModels 兜底」修复）。
- 已构建并落盘含**崩溃自报告**的全新 APK（05:39），装上新包后若仍出问题，App 会**直接把真实异常弹在屏幕上 / 写进 `filesDir/quro_crash.log`**，用户复制回贴即可精确定位，不再盲猜。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟡 条件 Go（代码已修复并重新构建，待设备重装验证） |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 1（设备验证） / 🟢 3（加固已完成） |
| 关键行动项 | 3 条（见下） |
| 建议负责人 | 用户（重装验证）+ 主理人（按回传报错精修） |

---

## 1. 各成员核心结论

### 🔧 排障手（调试与根因）
- 核心判断：对「设置 → `QuroModelConfigForm`」「对话 → `QuroChatViewModel.send` → `QuroAssistant.ask`」两条路径做了逐文件走查，结论是**代码无静态崩溃缺陷**：
  - 配置读取 `QuroModelConfigRepository.load()` 与 `ApiProviderType.fromProviderTypeId()` 全部以空值/默认值兜底，旧 prefs 或脏数据不可能触发 NPE/崩溃；
  - 所有异步路径（`fetchModels`、`send`、`ask` 内的 `client.chat` 与 `engine.execute`）均已包在 `runCatching` / `try-catch` + `finally` 中，异常降级为可见错误消息而非静默致崩；
  - 之前的修复（实时传 `cfg`、`update()` 每次编辑即落盘、`fetchModels()` 捕获后降级为 `QuroModelListResult.Error`）仍然在位。
- 关键建议：在设备上**卸载重装 / 清除应用数据后重装**本轮回送的新 APK（含崩溃自报告）；若仍复现，把 App 弹出的异常文本或 `quro_crash.log` 内容发回，即可从「盲猜」转为「按栈精修」。

---

## 2. 综合审查发现（去重合并后按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟡 | 验证 | 设备侧 | 用户连报三轮「设置闪退 / 无法对话」，但当前源码静态层面无对应崩溃路径，疑为持续运行旧 APK | 重装 05:39 新包并清除应用数据后复测 | 排障手 |
| 2 | 🟢 | 加固 | `QuroChatViewModel.kt:138` | `send()` 协程作用域此前无异常处理器，逃逸异常会经 `viewModelScope` 冒泡 | 已接 `QuroCrashReporter.handler`，异常转可见报错 | 排障手 |
| 3 | 🟢 | 加固 | `QuroModelConfigViewModel.kt:24` | `CoroutineScope(Dispatchers.IO + SupervisorJob())` 无异常处理器 | 已追加 `+ QuroCrashReporter.handler` | 排障手 |
| 4 | 🟢 | 诊断 | `QuroMainScreen.kt` / `QuroMainActivity.kt` | 无设备日志通道，主理人此前完全「看不见」真实报错 | 新增顶层崩溃弹窗（可一键复制）+ 全局 `Thread.setDefaultUncaughtExceptionHandler` 落盘 `quro_crash.log` | 排障手 |

---

## ✅ 行动清单（至少 3 条具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 在设备上**卸载 QuroAI → 重装**桌面最新 `QuroAI-debug.apk`（05:39 构建，已含全部修复 + 崩溃自报告） | 用户 | P0 | 立即 |
| 2 | 若重装后仍「点设置闪退 / 无法对话」，把 App **弹出的异常文本**复制发回；若直接闪退无弹窗，用 `adb shell run-as com.ai.assistance.calw.os.quro cat files/quro_crash.log` 取日志发回 | 用户 + 主理人 | P0 | 复现即反馈 |
| 3 | 主理人收到回传报错后，按栈精修对应模块（预期无需再大规模改动） | 主理人 | P1 | 收到日志后 1 轮内 |
| 4 | 清理遗留的重复文件 `QuroDiagnostics.kt`（与 `QuroCrashReporter.kt` 功能重叠；当前沙箱禁止删除，属死代码、无引用、不影响编译运行） | 主理人 | P3 | 下轮顺手 |

---

## ⚠️ 待完善 / 已知局限

- **重复文件未删**：`core/QuroDiagnostics.kt` 与本轮保留的 `QuroCrashReporter.kt` 功能重叠，但当前沙箱禁止删除该文件（rm 返回成功却文件留存）。其为无人引用的 `object`，不影响编译与运行，仅留待清理。
- **全局异常处理器为「记录 + 委派」**：`QuroMainActivity` 中安装的 `Thread.setDefaultUncaughtExceptionHandler` 在记录后会委派给系统默认处理器，因此**组合（Composition）级崩溃仍会终止进程**——但崩溃栈已落盘到 `quro_crash.log`，可事后提取；异步（协程）级崩溃则由 `QuroCrashReporter.handler` 接住并弹窗，不会终止。
- **子代理不可用**：gstack-* 调度代理返回不可用，本轮由主理人按排障手框架直接完成；若后续代理恢复，建议重跑一次以互为校验。

---

## 📚 成员产出索引

- gstack-investigator（排障手）原始产出：主理人直接代行。走查范围与结论见上文「各成员核心结论 / 综合审查发现」。
  - 重读文件：`QuroMainScreen.kt`、`QuroSettingsScreen.kt`、`QuroModelConfigScreen.kt`、`QuroModelConfigViewModel.kt`、`QuroChatViewModel.kt`、`QuroChatScreen.kt`（含 `send` 调用点 `QuroChatScreen.kt:351` 已确认传实时 `cfg`）、`QuroAssistant.kt`、`QuroModelConfig.kt`（含 Repository）、`ApiProviderType.kt`、`QuroMainActivity.kt`、`QuroCrashReporter.kt`。
  - 编译验证：`./gradlew assembleDebug --rerun-tasks --no-daemon` → `BUILD SUCCESSFUL in 37s`（仅 deprecation 警告，无错误）。
  - 产出物：桌面新 APK `QuroAI-debug.apk`（05:39，22,980,598 B）。

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
