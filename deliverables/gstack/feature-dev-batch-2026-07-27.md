# QuroAI 十项任务批次进度 · 交付报告

**日期**：2026-07-27
**场景**：全流程交付 / 多任务批次（含 bug 修复 + 功能集成 + 去品牌化）
**参与成员**：排障手（调试/根因）、产品官（需求评审/命名决策）、设计顾问（纸感 UI 落地）、安全卫士（Shizuku 许可证/去品牌化审查）
**版本**：v360（versionCode 360 / versionName 1.0.360）

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟡 有条件通过（2 项已修 + 1 项方案就绪 + 1 项待澄清 + 6 项待做）
- 已落地：#817 语音开关重启才生效（根因修复）、#821 ACI 管理中心改名为「ACT 关联启动」
- 方案就绪：#816 集成 Shizuku App 本体（已摸清仓库结构 + 许可证约束，分阶段执行，未贸然合代码）
- 已修复：#822 删除顶部「虾哥」PersonaBar 胶囊 + 人格卡下方 trait 芯片（表情/超问/刀子/羞耻）
- 编译状态：✅ `compileDebugKotlin` BUILD SUCCESSFUL（47s），无新增告警
- 下一步：确认 #822 删哪个组件 → 推进 #816 Phase 1 → 其余项陆续开做

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟡 条件 Go（已修 2 项编译通过；#816 大工程分阶段；#822 待澄清） |
| 严重度分布 | 🔴 0 / 🟠 1（#816 集成风险高）/ 🟡 2（#822 待澄清、其余待做）/ 🟢 2（#817 #821 已修） |
| 关键行动项 | 8 条（见行动清单） |
| 建议负责人 | 主理人（本环境 gstack 子 Agent 不可派发，由主理人直接执行各成员框架） |

---

## 1. 各成员核心结论

### 🔧 排障手（调试与根因）
- **#817 根因**：`ChatScreen.kt:775` 原 `val voiceInputEnabled = remember { QuroVoiceFeaturePrefs.getDialogVoiceButton(ctx) }` 用 `remember{}` 无 key，只在 ChatScreen 首次组合时读一次；ChatScreen 是常驻根屏不销毁，设置屏写入 `SharedPreferences` 后它不重组，必须退出重进才生效。
- **修复**：在 `QuroVoiceFeaturePrefs` 暴露 `dialogVoiceButtonFlow(ctx): StateFlow<Boolean>`，ChatScreen 改用 `collectAsState()` 即时响应。同步更新 setter 发射新值。
- **#816 架构评估**：Shizuku 是多模块工程（manager/server/starter/shell/common/api），server 依赖 hidden-api 与 adb/root 启动流程，整体 fork 进 QuroAI 风险高，须分阶段。

### 🔍 产品官（需求评审 / 命名）
- **#821 决策**：用户要求把 v357 改名后的「ACI 管理中心」改为「ACT 关联启动」。底层 AIDL 协议名（`QuroAciManager` / `ai.aci.core`）属真实框架标识符，**不动**，仅改可见标题与用户文案，避免破坏 ACI 调用链路。
- **#816 范围**：用户明确要 fork `RikkaApps/Shizuku` **App 本体**（不是 v358 只加 provider 权限端点）。这是头条任务，需完整集成 + 去品牌化。

### 🎨 设计师（纸感 UI 落地）
- **#821 落地**：可见标题 `Text("ACI 管理中心")` → `Text("ACT 关联启动")`；顶部说明、开发者文档内两处「ACI 管理中心」引用同步改为「ACT 关联启动」。代码内 KDoc 注释保留 ACI 技术描述（准确）。

### 🛡️ 安全卫士（许可证 / 去品牌化审查）
- **#816 许可证硬约束**（Apache 2.0，§6 禁止项）：
  - ❌ 不得使用 `manager/src/main/res/mipmap*/ic_launcher*.png`
  - ❌ 不得使用 `Shizuku` 作应用名 / `moe.shizuku.privileged.api` 作 applicationId
  - ❌ 不得声明 `moe.shizuku.manager.permission.*`
  - ✅ 代码文件可 fork（需保留 NOTICE/Apache 署名，不动合规文件）
- 去品牌化策略：包名/应用名/资源图标/权限名全部改 QuroAI 体系；`rikka.shizuku.*` API 客户端库（v358 已引 `shizuku-provider`）可复用。

> 未上场：质量门神（QA）本批次仅做编译验证，未跑完整 QA 套件。

---

## 2. 综合审查发现（按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟠 | 集成风险 | 全局 | #816 集成 Shizuku server（hidden-api + adb/root 启动）工程量大、易破坏编译 | 分阶段：先客户端+引导 UI，再嵌 server/starter | 安全卫士 + 排障手 |
| 2 | 🟡 | UI 错位 | ChatScreen | #822 虾哥人格标签栏（虾哥 + 表情/超问/刀子/羞耻）误显示在消息列表，应仅在对话框头像处 | 待用户确认组件后删除/归位 | 设计师 + 排障手 |
| 3 | 🟡 | 待做 | 全局 | #818 机器人平台 UI 重做、#819 飞书权限说明、#820 人格心跳孵化 ANR、#823 上下文压缩、#824 慢慢流式、#825 头像上传裁剪 | 按计划逐项 | 产品官 |
| 4 | 🟢 | Bug 已修 | ChatScreen:775 / QuroVoiceFeaturePrefs | #817 语音开关需重启生效 | 已改 StateFlow + collectAsState | 排障手 |
| 5 | 🟢 | 改名已做 | QuroAciCenterScreen | #821 ACI→ACT 关联启动 | 已改可见标题与文案 | 产品官 + 设计师 |

### #816 威胁建模 + 许可证检查表（STRIDE 简版）
- **S（伪造）**：自托管 server 绑定到 QuroAI 自身 uid，避免第三方伪造 Shizuku 服务 → 风险低。
- **T（篡改）**：fork 源码须保留 Apache 署名，改动处加注释 → 合规。
- **I（否认）**：本地 AIDL，无网络，无否认问题。
- **D（信息泄露）**：server 可访问系统 API（安装包/权限），须限定仅 QuroAI 自身调用（Binder 校验 uid）。
- **E（提权）**：adb/root 启动链路是提权核心，须严格校验调用方与参数，防被恶意 App 借道。
- **D（拒绝服务）**：server 崩溃不影响主 App（独立进程）→ 隔离。

---

## ✅ 行动清单

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | #822：已删顶部 PersonaBar（虾哥胶囊）+ 人格卡下方 trait 芯片（PersonaSmallCard L267-285）| 主理人 ✅ | ✅ 完成 | v360 |
| 2 | #816 Phase 1：在 QuroAI 内置「Shizuku 启动引导」Activity，复用 v358 的 `shizuku-provider` 客户端；先不嵌 server | 主理人 | P0 | 下个版本 |
| 3 | #816 Phase 2：评估 fork `server`+`starter` 模块（hidden-api bypass + app_process 启动），去品牌化后合入独立 module | 主理人 + 安全卫士 | P1 | 后续版本 |
| 4 | #817/#821 已修，出 v360 调试包供真机验证（APK 置桌面，命名 QuroAI-debug-2026-07-27-v360.apk） | 主理人 | P1 | 本批次内 |
| 5 | #818 机器人平台 UI 重做（对齐纸感；核查右侧橙色悬浮按钮是否多余） | 设计师 | P2 | 待排期 |
| 6 | #819 飞书机器人页添加权限说明（im.message.receive_v1 / im:message / im:message:send_as_bot） | 产品官 | P2 | 待排期 |
| 7 | #820 复用 AnrMonitor 双写机制，监测「AI 人格心跳孵化」主线程阻塞 | 排障手 | P2 | 待排期 |
| 8 | #823/#824 对话框上下文压缩 + 慢慢流式：先 fetch Operit 上游方案再实现 | 排障手 + 产品官 | P2 | 待排期 |
| 9 | #825 修复用户资料头像上传不能裁剪（核查 crop Intent） | 排障手 | P2 | 待排期 |

---

## ⚠️ 待完善 / 已知局限

- **#822 已修复**：用户确认"虾哥 + 表情/超问/刀子/羞耻"是**灵魂注入面板里人格卡下方的 trait 芯片**——定位到 `QuroSoulUi.PersonaSmallCard`（L267-285）渲染 `persona.tags` 为 `SuggestionChip`，已删除该芯片行；同时删除聊天顶部 `PersonaBar`（虾哥名字胶囊，ChatScreen L745），因 AI 消息头像旁已内联显示虾哥身份（L1783/L1803），顶部胶囊冗余。assembleDebug BUILD SUCCESSFUL（34s）。
- **APK**：`QuroAI-debug-2026-07-27-v360.apk` 已置桌面（374,282,827 B，cmp 一致），供真机验证 #817/#821/#822。
- **#816 未合代码**：仅完成仓库结构调研与许可证/去品牌化约束梳理，未实际 fork 源码（避免破坏编译）。Phase 1 先行。
- **QA 未跑全套**：本批次仅 `compileDebugKotlin` 验证；完整 assemble + 真机安装验证待出包。
- **gstack 子 Agent 不可派发**：本环境 `Agent(gstack-*)` 不可用，主理人直接按各成员框架执行并汇编。

---

## 📚 成员产出索引

- 排障手（#817 根因 + 修复、#816 架构评估）：`QuroVoiceFeaturePrefs.kt` 新增 `dialogVoiceButtonFlow` + `ChatScreen.kt:775` 改 `collectAsState`；Shizuku 模块结构分析。
- 产品官（#821 命名决策、#816 范围确认）：决策记录见上。
- 设计师（#821 UI 落地）：`QuroAciCenterScreen.kt` 标题与文案修改。
- 安全卫士（#816 许可证审查）：Apache 2.0 §6 三条禁止项 + 去品牌化策略。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
