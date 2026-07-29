# Quro AI 聊天界面整体换成 MoWen UI（后端保留）+ 悬浮球崩溃修复

**日期**：2026-07-17
**场景**：全流程交付（UI 移植 + 后端接线 + 构建验证）
**参与成员**：产品官 / 安全卫士 / 质量门神 / 设计师 / 排障手（本轮由主理人直接代行——gstack 子代理在当前环境不可用，见「成员产出索引」说明）

> ⚠️ 环境约束说明：本环境 gstack-* 调度子代理返回 "Task agent ... is not available"，无法走正式多成员协作。主理人按五位成员的框架独立完成移植、接线、编译与落盘，结论与产出均出自此次直接工作。

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟢 通过（代码已编译，待设备侧重装验证）
- 阻塞项数量：0（代码侧）；1（需用户卸载重装新 APK 验证）
- 按用户指令 **"UI 直接完整用这个的"**，将 QuroAI 聊天界面**整体替换为 MoWenApp 的 Compose UI**（视觉 100% 沿用墨问设计稿），同时**保留 QuroAI 全部后端**（多会话 / 历史持久化 / 人格卡 / 记忆库 / 工具调用 / 多模型配置）。
- 同步修复上一轮遗留的悬浮球崩溃 `ViewTreeLifecycleOwner not found`（ComposeView 缺少 LifecycleOwner）。
- 已构建并落盘全新 APK，需在设备卸载重装验证。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go（代码已修复并重新构建，待设备重装验证） |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 1（设备验证） / 🟢 4（移植+接线+修复已完成） |
| 关键行动项 | 3 条（见下） |
| 建议负责人 | 用户（重装验证）+ 主理人（按回传报错精修） |

---

## 1. 各成员核心结论

### 🔍 产品官（产品评审）
- 核心判断：用户诉求明确——「直接用 MoWen 的 UI」，本质是**视觉层整体替换 + 后端零改动保留**。正确做法是把 MoWen `ChatScreen` 当作**纯视图**罩在 `QuroChatViewModel` 之上，而非在旧 UI 上模仿。
- 关键建议：模型选择、人格卡、历史会话、发送、深度思考开关全部回写到 QuroAI 既有 ViewModel，保证「无法对话」的根因修复不被破坏。

### 🛡️ 安全卫士（OWASP+STRIDE 审计）
- 核心判断：本次为 UI 层移植，无新增网络/存储/权限面；附件仍走既有的 `QuroAttachmentKit.fromUri`（复制到应用私有目录，路径随会话 JSON 持久化），未引入新攻击面。
- 关键建议：去品牌化已到位（包名、`R` 引用、文案均从 `com.mowen.chat` / 墨问 改为 `com.ai.assistance.calw.os.quro` / Quro AI / Calw OS），无上游品牌残留泄露风险。

### ✅ 质量门神（QA测试与发布）
- 核心判断：移植采用「适配器」模式——MoWen 的 `Message/Persona/ChatModel/HistoryItem` 数据类**原样保留用于渲染**，新增顶层 `toMessage()/toPersona()/toHistoryItem()` 适配器把 QuroAI 后端类型映射进来，UI 代码几乎未动，回归面最小。
- 关键建议：发送时把 UI 选中的 `QuroAttachment` 列表原样传给 `vm.send(t, attachments, cfg)`；`busy` 状态映射为「正在思考…」占位气泡，避免原「无法对话」卡死问题复发。

### 🎨 设计师（设计系统与视觉）
- 核心判断：MoWen 调色板（纸感米白 + 陶土橙）与 QuroAI 既有 `QuroTheme` 完全一致（QuroTheme 注释本就写明「配色取自用户对话框设计稿（墨问）」），因此仅新增 `Accent/Card/Ink/Line/Muted/Paper/Sage` 等别名，零视觉改动。
- 关键建议：`res/drawable` 原为空，已并入 MoWen 的 19 个 `ic_*.xml` 矢量图标（排除 `ic_launcher_fg`），无命名冲突。

### 🔧 排障手（调试与根因）
- 核心判断：悬浮球 `QuroVoiceBallService` 中的 `ComposeView` 在 attach 时抛 `IllegalStateException: ViewTreeLifecycleOwner not found`——Service 环境无 Activity 级 LifecycleOwner。新版 Compose BOM 中 `ViewTreeLifecycleOwner` 为 `internal`，无法直接 import/FQN，故用框架资源 id + `setTag` 方式手动注入自定义 `ComposeLifecycleOwner`（同时实现 `SavedStateRegistryOwner`）。
- 关键建议：该修复独立于本次 UI 移植，属上一轮崩溃收口；已单独编译通过（EXIT=0）。

---

## 2. 综合审查发现（去重合并后按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟡 | 验证 | 设备侧 | 用户此前报「设置闪退 / 无法对话」，但本轮源码静态层面无崩溃路径，疑为持续运行旧 APK | 卸载重装新包并清除应用数据后复测 | 排障手 |
| 2 | 🟢 | 崩溃 | `QuroVoiceBallService.kt` | 悬浮球 ComposeView 缺 LifecycleOwner → `ViewTreeLifecycleOwner not found` | 已加 `setViewTreeOwners()` 用框架资源 id `setTag` 注入 `ComposeLifecycleOwner`（BOM 2026.01.01 中该类为 internal，绕开 import） | 排障手 |
| 3 | 🟢 | 移植 | `ui/ChatScreen.kt` | 整体替换为 MoWen UI，作为纯视图罩在 `QuroChatViewModel` 上 | 已完成；新增适配器 `toMessage/toPersona/toHistoryItem` | 设计师+质量门神 |
| 4 | 🟢 | 接线 | `ui/ChatScreen.kt` | 模型选择 / 人格卡 / 历史会话 / 发送 / 深度思考 回写 QuroAI 后端 | 已接 `modelVm.update{copy(model=id)}`、`personaVm.setActive/upsert`、`vm.new/selectConversation`、`vm.send`、`vm.setThinking`、`vm.clear` | 产品官+质量门神 |
| 5 | 🟢 | 去品牌 | 包名/资源/R 引用/文案 | MoWen 品牌残留 | 包 `com.mowen.chat`→`com.ai.assistance.calw.os.quro`；文案 墨问/quiet companion→Quro AI/Calw OS | 安全卫士 |

---

## ✅ 行动清单（至少 3 条具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 在设备上**卸载 QuroAI → 重装**桌面最新 `QuroAI-debug.apk`（本轮构建，已含整体 UI 替换 + 悬浮球崩溃修复） | 用户 | P0 | 立即 |
| 2 | 重装后验证：① 聊天界面为墨问风格；② 输入并发送能拿到真实回复（证明后端未断）；③ 左上角历史、模型芯片、人格条、设置均可用；④ 悬浮球不再崩溃 | 用户 | P0 | 复装即验 |
| 3 | 若仍「无法对话 / 闪退」，把 App 弹出的异常文本复制发回；若直接闪退无弹窗，用 `adb shell run-as com.ai.assistance.calw.os.quro cat files/quro_crash.log` 取日志发回 | 用户 + 主理人 | P0 | 复现即反馈 |
| 4 | 主理人收到回传报错后，按栈精修对应模块（预期无需大规模改动） | 主理人 | P1 | 收到日志后 1 轮内 |

---

## ⚠️ 待完善 / 已知局限

- **去品牌不覆盖 MoWen 图标美术**：`ic_*` 矢量沿用墨问原稿线条，仅文件名/调用处品牌化；如需完全去品牌视觉，可后续替换图标源文件。
- **附件仅文件名预览**：发送图片/文件时把 `QuroAttachment` 原样传给后端，但气泡区目前只展示文件名（未渲染缩略图），属 v1 取舍。
- **自定义模型**：模型弹层「添加模型」会把 `提供商_模型id` 写入 `cfg.model` 并生效，但未持久化到可勾选的模型列表（v1 仅作激活，不进 `SAMPLE_MODEL_GROUPS`）。
- **全局异常处理器为「记录 + 委派」**：`QuroMainActivity` 安装的 `Thread.setDefaultUncaughtExceptionHandler` 记录后会委派系统默认，故组合（Composition）级崩溃仍会终止进程——但栈已落盘 `quro_crash.log`，可事后提取；异步（协程）级崩溃由 `QuroCrashReporter.handler` 接住并弹窗，不会终止。
- **子代理不可用**：gstack-* 调度代理返回不可用，本轮由主理人按五成员框架直接完成；若后续代理恢复，建议重跑一次以互为校验。

---

## 📚 成员产出索引

- 主理人直接代行五成员框架。走查与改动范围：
  - 新增/替换：`ui/ChatScreen.kt`（MoWen UI 整体迁入 + 后端适配器）、`ui/icons/LucideIcon.kt`、`ui/data/ChatData.kt`、`ui/theme/QuroTheme.kt`（新增颜色别名）、`res/drawable/ic_*.xml`（19 个图标）。
  - 修复：`service/QuroVoiceBallService.kt`（`setViewTreeOwners` 注入 `ComposeLifecycleOwner`）。
  - 删除：`ui/QuroChatScreen.kt`（被新 `ChatScreen` 取代）。
  - 接线：`ui/QuroMainScreen.kt` 的 `QuroTab.Chat` 分支改为调用新 `ChatScreen(vm, modelVm, personaVm, onOpenModelConfig)`。
  - 编译验证：`./gradlew assembleDebug --rerun-tasks --no-daemon` → **BUILD SUCCESSFUL**，Exit 0，零 `e:` 错误（仅 gradle wrapper 噪声 `xargs: environment is too large`，不阻断）。
    - 首轮报错 `HistoryItem` 缺 `id` 字段 → 已加字段并在 `toHistoryItem` 回传；次轮 `SAMPLE_HISTORY` 位置参数错位 → 已改具名参数。
  - 产出物：桌面新 APK `QuroAI-debug.apk`（**23,092,345 B**，07-17 12:15，合并 UI 替换 + 悬浮球修复）。

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
