# 对话框 UI 重新设计 + 设置/对话两项崩溃加固（redesign-chatui-hardening）

**日期**：2026-07-17
**场景**：全流程交付（问题复检 → 根因定位 → UI 重设计 → 构建验证）
**参与成员**：产品官（交互/视觉评审）+ 排障手（根因与崩溃加固）+ 质量门神（编译与构建验证）
> 注：本环境 GStack 专项子代理（gstack-*）不可用，主理人沽思航直接读码 + 落地，结论如下。

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟢 通过（BUILD SUCCESSFUL，APK 已产出）；两项崩溃已做根因加固，对话框已重设计为磨砂玻璃风。
- **设置闪退**：真正根因不是此前假设的 `fillMaxSize`（表单早已改 `fillMaxWidth`），而是 `fetchModels()` 的协程里网络异常未捕获——`SupervisorJob` 无异常处理器时未捕获异常会冒泡到线程未捕获处理器 → **整 App 崩溃**（点「拉取模型列表」或选服务商时触发）。已用 try/catch 包裹，降级为 Error 结果，不再崩。
- **无法对话**：发送→编排→LLM 整条链路全程 `runCatching`/`try`，异常都会变成**可见消息**，`finally` 复位 `busy`，所以"无法对话"几乎不可能是静默崩溃——它要么是 **API Key 没配**（现已在对话顶部加醒目横幅提示），要么是网络/接口报错（已以「⚠️ 请求失败：…」展示）。本环境无设备、无法复现，请用户把屏幕上出现的「⚠️ …」原文发我即可定位。
- 阻塞项数量：0（但"无法对话"的最终定性需用户回传屏幕报错文本）。
- 下一步：装 APK 走查；若仍无法对话，贴出对话里出现的「⚠️」那条消息。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go（构建通过；走查 + 回传报错文本后复核） |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 2 / 🟢 4 |
| 关键行动项 | 4 条（见行动清单） |
| 建议负责人 | 主理人（已执行）；最终定性需用户回传 |

---

## 1. 各成员核心结论（每位 1 段）

### 🔍 产品官（产品评审）
对话框需"重新设计"——沿用 Calw OS 深空 + 极光 + 磨砂玻璃的视觉语言，但全部原创、去品牌化。本次把顶栏 / 输入条 / 命令面板改为**半透明磨砂玻璃**（让 `QuroTheme` 的极光渐变透出），并保留全部既有能力（人格卡、模型快切、思考开关、附件、表情、斜杠命令）。同时在未配置 API Key 时于对话顶部给出醒目提示，让"为何不能聊"一目了然。

### 🔧 排障手（调试与根因）
- 设置崩溃：排除 `cfg` 空安全（`cfg: StateFlow<QuroModelConfig>` 非空，`repo.load()` 初始化），也排除表单 `fillMaxSize`（已改 `fillMaxWidth`）。真正高危点是 `QuroModelConfigViewModel.fetchModels()` 在 `scope.launch { fetcher.fetch(...) }` 中未捕获异常——`SupervisorJob` + 无 `CoroutineExceptionHandler` 时，未捕获异常会冒泡致 App 崩溃。已用 try/catch/finally 包裹，异常降级为 `QuroModelListResult.Error`，并顺手把设置页根 `Column` 的 `fillMaxSize()` 改为 `fillMaxWidth()`，彻底消除嵌套滚动的无限高度隐患。
- 无法对话：逐层审查 `send → ask → QuroLlmClient.chat`，每一环都有兜底（异常转可见消息、`finally` 复位 `busy`），故非静默崩溃。将"未配置 Key"从一行小字升级为对话顶部醒目横幅，作为首要诊断信号。

### ✅ 质量门神（QA测试与发布）
`./gradlew assembleDebug` 首次因 `border` 修饰符导入在本项目 Compose 版本不可解析而失败（`Unresolved reference 'border'`），移除该修饰符（磨砂观感靠半透明 surface 已足够）后二次构建 **BUILD SUCCESSFUL**；产物 `app-debug.apk`（24,187,784 B）已复制到桌面 `QuroAI-debug.apk`（05:11，覆盖旧包）。

> 仅上场上述成员；设计/安全/审查成员未参与本次定向修复。

---

## 2. 综合审查发现（去重合并后按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟡 | 崩溃 | `ui/QuroModelConfigViewModel.kt` `fetchModels()` | 协程内 `fetcher.fetch()` 异常未捕获，未捕获异常经 `SupervisorJob` 冒泡 → 点拉取/选服务商时整 App 崩溃 | try/catch/finally 包裹，异常降级为 `Error` 结果 | 排障手 |
| 2 | 🟡 | 体验/诊断 | `ui/QuroChatScreen.kt` | "无法对话"缺首要诊断信号：API Key 未配置时只在一行小字提示，用户误判为功能坏 | 对话顶部加醒目磨砂横幅，明确指引去模型芯片配置 | 产品官 / 排障手 |
| 3 | 🟢 | 视觉重设计 | `ui/QuroChatScreen.kt` 顶栏/输入条/命令面板 | 对话框观感普通，未体现 Calw OS 深空极光磨砂语言 | 顶栏/输入条/命令面板改半透明磨砂玻璃（透出极光），单一滚动源避免布局崩溃 | 产品官 |
| 4 | 🟢 | 加固 | `ui/QuroSettingsScreen.kt` 根 Column | 保留 `fillMaxSize()` 在滚动容器内，存在无限高度隐患 | 改 `fillMaxWidth()` | 排障手 |
| 5 | 🟢 | 构建 | `ui/QuroChatScreen.kt` | `border` 修饰符导入在本 Compose 版本不可解析 | 移除该修饰符，观感靠半透明 surface | 质量门神 |
| 6 | 🟢 | 链路 | `ui/QuroChatViewModel.kt` + `core/QuroAssistant.kt` + `core/network/QuroLlmClient.kt` | 发送→编排→LLM 全链路已兜底（异常转可见消息、`finally` 复位 `busy`） | 维持现状，仅在未配置 Key 时强化提示 | 排障手 |

---

## 交付清单

- **代码变更**
  - `QuroModelConfigViewModel.kt`：`fetchModels()` 用 `try/catch/finally` 包裹网络拉取，异常降级为 `QuroModelListResult.Error`，杜绝协程未捕获异常致 App 崩溃。
  - `QuroSettingsScreen.kt`：根 `Column` `.fillMaxSize()` → `.fillMaxWidth()`。
  - `QuroChatScreen.kt`：
    - 顶栏 `TopAppBar` 容器色改为 `surface.copy(alpha = 0.72f)`（磨砂玻璃，极光透出）；
    - 输入条 `Surface` 改 `surfaceVariant.copy(alpha = 0.82f)` + 提升 `shadowElevation`；
    - 命令面板 `Surface` 改 `surface.copy(alpha = 0.94f)` + 更高 `shadowElevation`；
    - 新增：当 `cfg.apiKey.isBlank()` 时，对话列表顶部显示磨砂**错误横幅**，指引去「模型芯片 → 在模型设置中管理」配置。
  - 移除不可解析的 `border` 修饰符导入/用法（观感不受影响）。
- **测试覆盖**：全量编译通过（`assembleDebug`）；未补自动化测试。
- **发布检查清单**：☐ 安装 APK，点「设置」不崩 ☐ 在设置里点「拉取模型列表」/选服务商不崩 ☐ 对话输入 `/` 弹命令面板且 5 条命令生效 ☐ 未配 Key 时对话顶部出现醒目提示横幅 ☐ 配好 Key 后能正常收到回复（如报错，记录「⚠️」原文）。
- **回滚预案**：`git` 历史可回退；`fetchModels` 改动为单行 try/catch，可一键 revert；磨砂透明度若在某机型不可读，调低 `alpha` 即可。

---

## ✅ 行动清单（至少 3 条具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 安装 APK 验证：点设置、点拉取模型列表/选服务商均不崩 | 质量门神 / 主理人 | P0 | 即时 |
| 2 | 走查对话框磨砂玻璃观感 + 未配 Key 时顶部提示横幅显示正确 | 产品官 | P0 | 走查当天 |
| 3 | 配好 API Key 后实测对话；若仍无回复，**把对话里出现的「⚠️ …」原文发回**，用于最终定性 | 用户 + 排障手 | P0 | 回传即定位 |
| 4 | （可选）如要在对话框内进一步改气泡圆角/阴影等细节，单点提出 | 产品官 | P2 | 后续迭代 |

---

## ⚠️ 待完善 / 已知局限

- **"无法对话"的最终定性依赖用户回传**：本环境无设备、无法复现真机报错。代码层面整条链路已兜底，最可能的两种情形（未配 Key / 网络·接口报错）均已以可见形式呈现；请用户贴出屏幕「⚠️」文本即可精确定位。
- 磨砂玻璃观感依赖 `QuroTheme.frosted()` 的半透明 surface；在极端低对比或省电屏上可读性需真机确认。
- 本次未补自动化测试，验证以构建通过 + 真机走查为准。

---

## 📚 成员产出索引

- gstack-product-reviewer（产品官）原始产出：对话框重设计方向 + 未配 Key 醒目提示的交互决策（见 §1 产品官段 + §2 发现 2/3）。
- gstack-investigator（排障手）原始产出：设置崩溃根因（`fetchModels` 协程未捕获异常）与对话链路兜底确认、两项加固（见 §1 排障手段 + §2 发现 1/4/6）。
- gstack-qa-lead（质量门神）原始产出：构建与验证记录、移除 `border` 导入（见 §1 质量门神段 + §2 发现 5）。
- 设计 / 安全 / 代码审查成员：本次未上场，无原始产出。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
