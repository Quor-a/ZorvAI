# zorv AI（QuroAI）架构差异审计报告

> 生成时间：2026-11-03
> 目的：对照参考实现（droid-mcp-src / ZorvBrowser / ZorvAI-Desktop），逐维度审计 QuroAI 现状，产出缺失与 bug 清单，作为后续补齐的作战底图。
> 结论先行：**QuroAI 在绝大多数维度已反超参考实现**，真正的短板集中在「相机、桌面宠物接线、外观主题三态与硬编码 bug、浏览器内建能力、定时倒计时/日历提醒、模型故障转移接线」这六块。不是"什么都没有"，而是"有骨架、缺接线/缺细节/有硬编码 bug"。

---

## 0. 项目关系（已确认）

| 项目 | 定位 | 规模 | 在本审计中的角色 |
|---|---|---|---|
| **QuroAI** (`com.ai.assistance.quro`) | Android zorv AI 主体 | core 600+ kt / UI 80+ Screen / 258 工具 | **待完善主体** |
| **droid-mcp-src** (stixez/droid-mcp) | 第三方 Android MCP SDK | 42 模块 / 99 工具 | 相机、闹钟/定时、MCP 传输参考 |
| **ZorvBrowser** | 浏览器项目 | aci-browser 43 项 ACI 能力 | 浏览器参考 |
| **ZorvAI-Desktop** | Compose Desktop zorv AI | 几十文件 | 主题 token、统一组件库参考 |

---

## 1. 摄像头 —— 最明确的短板（参考三件套全缺）

### 参考（droid-mcp-camera，4 文件）
- `CameraTools` → `get_camera_capabilities` / `take_photo` / `capture_video`
- `TakePhotoTool`：Camera2 `TEMPLATE_STILL_CAPTURE` + `ImageReader(JPEG)` → `MediaStore.Images`，可选 `return_data`(base64)
- `CaptureVideoTool`：Camera2 + `MediaRecorder(MPEG_4/H264)`，`duration_sec`(1-60) → `MediaStore.Video`
- `GetCameraCapabilitiesTool`：枚举 cameraId / LENS_FACING / FLASH / 最大分辨率

### QuroAI 现状
- **无 CameraX / camera2 依赖**（build.gradle 无相关库）
- 唯一 Camera2 用途在 `service/QuroVideoCallService.kt`：仅 `TEMPLATE_PREVIEW` 预览 + 前后摄切换，**无拍照无录像**
- `core/vision/` 全部是 MediaProjection 屏幕捕获（非相机）
- 手电筒 `ToggleFlashlightTool` 用 `CameraManager` 控 torch

### 缺失
1. 缺 `take_photo`（静拍 JPEG → MediaStore）
2. 缺 `capture_video`（录像 MP4 → MediaStore）
3. 缺 `get_camera_capabilities`（枚举能力）
4. 现有视觉能力是「屏幕截图 + 视频通话预览」，均**不可被 AI function-calling 调用为拍照/录像工具**

---

## 2. 桌面宠物 —— 两套子系统脱节，缺接线

### 现状
- 悬浮窗：`ui/FloatingMiniWindow.kt`（应用内）+ `service/QuroVoiceBallService.kt`（系统级 `TYPE_APPLICATION_OVERLAY`，拖动+轻点）
- 语音球 UI：`ui/QuroVoiceBallView.kt`（Canvas 2000 粒子 3D 球，listening/speaking/paused 状态光环）
- 宠物 SDK：`genuiagent-sdk/.../PetComponent.kt`（`PetSpec`/`PetPhase`/`PetSprite`，blob/cat/ghost 三形态，THINKING/TOOL_CALLING/GENERATING 状态机）
- 宠物设置：`genui/aiapp/ui/PetSettingsScreen.kt`（`PetPrefs` 持久化 `pet_prefs`）
- 桌面宿主：`activity/QuroDesktopActivity.kt`（应用网格 + 硬编码墙纸）

### 缺失
1. **形象与悬浮球脱节**：设置页养的 `PetSprite`（blob/cat/ghost）从不挂到悬浮窗，`QuroVoiceBallService` 挂的是粒子球，二者零引用
2. **宠物无交互层**：`PetSprite` 无 pointerInput/点击/拖拽；`tapOpensPanel/tapInteract` 开关只存布尔无行为
3. **状态不联动**：`PetPhase`（思考/工具调用/生成）与语音球状态（listening/speaking/paused）无映射
4. **宠物不在桌面**：`QuroDesktopActivity` 无常驻宠物
5. **持久化分散**：`pet_prefs` / `quro_ui`(dark_mode) / `QuroVoiceFeaturePrefs` 三处各自为政
6. 参考实现（ZorvAI-Desktop / droid-mcp）**无桌面宠物实现**，无参考可移植；缺的是「形象统一 + 交互 + 桌面常驻 + 状态联动」四块

---

## 3. 外观主题 —— 有主题但缺三态 + 大量硬编码 bug

### 现状
- `ui/theme/QuroTheme.kt`：`QuroLightColorScheme`/`QuroDarkColorScheme` 双主题存在；`QuroTheme(darkOverride)` 接口支持「跟随系统」（传 null）
- 但 `QuroMainScreen.kt:64` 写死 `QuroTheme(darkOverride = darkMode)`，`darkMode` 来自 `dark_mode` 布尔 pref → **实际只有深/浅二选一**

### bug 清单（按严重度）
1. **无「跟随系统」三态**：UI 只有深色开/关布尔开关
2. **大 bug① 设置组件硬编码不随深色切换**：`QuroSettingsComponents.kt` 的 `Card`（恒白 0xFFFFFFFF）、`Accent`/`Line`/`Muted` 是静态 val，深色模式下仍是白卡片浅字；danger 色 `parseColor("#C0432F")` 写死
3. **大 bug② `QuroSettingsTheme` 只有亮色**：`QuroIOSScheme = lightColorScheme`，无 dark 参数
4. **大 bug③ 局部绕过 App 主题**：`QuroChatCards.kt:1961`(MermaidWebView)、`QuroToolCenterScreen.kt:351`、`weather/ToolCallIcon.kt:1086,1221` 直接 `isSystemInDarkTheme()` → 手动开深色但系统浅色时反向渲染
5. **大 bug④ 非 UI 层硬编码品牌色**：`QuroDesktopActivity.kt` 墙纸写死 `0xFFC25A38` 等；`QuroVoiceBallView.kt` 状态环写死 `0xFFEF4444` 等
6. **无主题色/强调色自定义**：token 全 private val 硬编码，无选择器
7. **缺设计 token 体系**：参考 ZorvAI-Desktop 有 `ZorvShapes/ZorvSpace/ZorvType/ZorvElevation`，QuroAI 只有「颜色 + 纸感 brush + 1 个 Typography」

---

## 4. 定时任务 —— 反超但缺倒计时 + 日历提醒 + 半成品 bug

### 现状
- 闹钟（反超）：`SetAlarmTool`/`CancelAlarmTool`/`ListAlarmsTool` + 应用内兜底 `QuroAlarmScheduler`（`setExactAndAllowWhileIdle` 真响铃）+ 开机重建
- 定时任务：`QuroScheduledTask`（RRULE 全支持）+ AI 执行触发 + 全屏通知
- 半成品：`core/model/QuroTaskScheduler.kt` 的 `TaskWorker.doWork()` **空壳**（直接 `Result.success()`）
- 日历：`WriteCalendarTool` **不写 HAS_ALARM / Reminders**

### 缺失 / bug
1. **缺 `create_timer`（倒计时）**：全仓无 `ACTION_SET_TIMER`
2. **缺 `create_reminder`（带提醒日历事件）**：不写 `HAS_ALARM`/`Reminders`
3. **`TaskWorker.doWork()` 空实现**，`AutomationAction`（截图/点击/启动）从未被消费
4. **`QuroTaskSchedulerTool` 参数 key 错位**：Recurring 分支读 `optLong("intervalMs")` 但 schema 是 `interval_ms` → 周期恒 1 小时；Cron 分支用 `OneTimeWorkRequest`（假 cron）

---

## 5. 后台进程 —— 全面反超，仅个别空壳

### 现状
- 14+ 真前台服务（`startForeground` + Android 14 type）：`QuroMcpService`/`QuroBotBootstrapService`/`QuroVoiceBallService`/`QuroVideoCallService`/`QuroMediaProjectionService`/`QuroBgScreenOverlayService`/`QuroMiniWindowService`/`QuroTerminalKeepAliveService` 等
- 双开机接收器：`QuroBootReceiver` + `QuroScheduleBootReceiver`（`BOOT_COMPLETED`/`MY_PACKAGE_REPLACED`/`QUICKBOOT_POWERON`）
- ACI/无障碍/IME/通知监听系统服务矩阵

### 缺失
- 参考 droid-mcp **零后台机制**（FOREGROUND_SERVICE 声明了但没用）→ QuroAI 全面反超
- 仅个别空壳：`QuroNotificationListenerService`（72 行空壳）、`QuroAidlAciWakeReceiver`（33 行）、`TaskWorker`（见维度 4）

---

## 6. 终端会话 —— 已大幅超越，仅按键映射/历史上翻细节

### 现状（terminal-core 60+ 文件 + core/terminal 17 + core/novaterm 19）
- `QuroShellSession`：常驻 shell + 哨兵协议 + 滚动缓冲 + 可选 VT + 两阶段中断 + 真实 PTY
- `QuroTerminalSessionManager`：多会话（LINUX_PROOT/DEVICE_SH/TERMUX_NATIVE/VM_LINUX）
- `AnsiTerminalEmulator`：光标/颜色/粗斜体/自动换行全支持
- `CanvasTerminalView`：文本选择/长按选词/复制/粘贴检测

### 可补齐
1. 完整 extraKeys 按键映射面板（当前只显式处理 DEL/ENTER）
2. 「↑ 上翻历史」「快捷命令面板」UI 呈现（`QuroTerminalHistory` 已有但无 UI）
3. OSC 52 剪贴板同步、超链接/marker 等 shell 集成高级项

---

## 7. 浏览器 —— 参考 ZorvBrowser 更完善，QuroAI 靠 aci_call 委托

### 现状
- `ui/QuroBrowserScreen.kt`：被动浏览器（书签/历史/桌面模式 UA/查找/阅读模式/脚本编辑器）
- `core/tools/QuroBrowserController.kt`：应用内 WebView 控制器（点击/填表/DOM/导航/截图/抓包）
- `core/tools/QuroToolsAiBrowser.kt`（`ai_browser`）：后台 search/read/open/automate/download
- `core/aidlaci/QuroAidlAciTools.kt`（`aci_call`）：委托 ZorvBrowser 的 ACI 能力

### 缺失（相对参考 43 项）
1. **广告拦截 / adblock**（参考有，QuroAI 无）
2. **无痕 / 隐私 / Cookie / Storage 管理**（`browser_cookie`/`browser_storage`）
3. **多标签**（`browser_tabnew/tabs/tab/tabclose`）—— QuroBrowserScreen 单 WebView
4. 触摸注入 `inject_touch` / 虚拟鼠标 `browser_mouse`
5. 快照回滚 / 事件 / 审计 / 媒体 / 分享 / 设备信息 / 剪贴板 / 全屏 / 权限
> 真正的差距集中在「广告拦截、无痕隐私、Cookie/Storage、多标签」四点

---

## 8. 对话框 —— 类型覆盖已远超参考，缺内联组件 + 交互细节

### 现状
- `ApprovalCard`（防注入审批卡，AllowScope 三档）
- `VisualQuestionDialog`/`VisualPopupDialog`/`VisualConfigDialogs`/`VisualToolDialogs`（问答/自由弹窗/配置/选择器）
- `dialog/RichText.kt`（80KB 完整 Markdown，GitHub Alerts 等）

### 缺失
1. **缺聊天气泡内联交互组件**：Slider/BarChart/LineChart/ColorPicker/Tabs/ToggleGroup（参考 `VisualComponent` 有 `[组件:类型|参数]` DSL）
2. **缺内联组件 DSL**：QuroAI 的 visual_question/popup 是弹窗而非内联
3. 交互细节：无进入动画、`contentDescription` 多为 null、`OutlinedTextField` 无 FocusRequester 自动聚焦、无 IME 动作绑定

---

## 9. 工具调用 —— 已全面超越，缺流式进度 + 单条重试按钮

### 现状
- `QuroTool`（name/description/parametersJson/run + execTimeoutMs/requiredPermissions/readOnly/requiresApproval/risk/category）
- `QuroToolRouter`（渐进式披露，43 核心集 + RAG 检索）
- `ApprovalGate`/`ApprovalCard`（审批）、`FailurePolicy`/`RecoveryAction`（失败重试恢复）、`ClosedLoopExecutor`/`Verifier`（闭环验证）
- `ToolCallRichCard`（状态/耗时/参数展开/JSON/CODE/DIFF 渲染/400 行截断）

### 缺失
1. **缺流式工具进度（增量输出）**：`ToolCallUi.result` 一次性回填（null=运行中），RUNNING 只有脉冲动画无实时输出
2. **缺单条 ToolCallRichCard 的「重试/取消」按钮**（有整体中断 + 自动重试，无单条手动重试）

---

## 10. 模型服务 / 外部调用 —— 功能全但「重建设轻接线」

### 现状（43 供应商 / 故障转移 / 思考协议编译 / token 计量）
- `QuroLlmClient`（2000 行）：共享连接池/DNS 缓存/90s 硬超时/重试/端点补全/推理模型 max_completion_tokens/多模态/工具调用顺序不变式/sanitize/4MB 截断/日志落盘
- `QuroReasoningControl`（思考协议编译层）、`QuroToolCallRepair`（49KB）
- `QuroModelConfig`（contextWindow 默认 1M/双阈值压缩）+ `QuroMultiProviderRepository` + `QuroFailoverManager`（三种策略）
- `LlmGateway`（统一 LLM 通道 + 并发闸门 + token 计量）

### 缺失 / bug（关键）
1. **多提供商体系与主链路脱节**：`QuroAssistant.kt:153` 直接 `QuroLlmClient().chat()`，**绕过 QuroFailoverManager**；`QuroMultiProviderTool.kt:286` failoverManager 实例化**被注释掉**
2. **`chatWithDefaultProvider()` 空实现**：故障转移禁用时 `return Error("默认提供商配置未实现")`
3. **健康检查假探活**：`performHealthCheck()` 只查字段非空，不发真实请求
4. **模型列表拉取与缓存未串成一条线**：`QuroModelListFetcher` 与 `QuroModelConfigRepository.saveModelListCache` 无调用链
5. **本地推理占位**：`QuroLocalEnginePlaceholder` 返回「MNN/llama.cpp AAR 未接入」
6. 无 token 统计/计费仪表盘（跨会话聚合/预算可视化）
7. 两套 provider 配置并存（`QuroModelConfig` vs `QuroMultiProviderRepository`）

---

## 11. 搜索服务 —— 全链路自研、碾压参考（一处误读已澄清）

### 误读澄清（已查清）
全库核对确认该名字与搜索服务无关；真正的搜索参考是 `droid-mcp-web` 的 DDG 单引擎（远弱于 QuroAI）。

### 现状（core/websearch 28 文件，碾压参考）
- 7 引擎（Bing RSS+HTML 双通道/Baidu/Brave/DDG/GoogleNewsRSS/SearXNG）+ 熔断 `EngineHealth`
- `WebSearchOrchestrator`（改写→多查询并发→RRF 融合→重排→抓正文→语义切块→证据排序→token 打包）
- `IntentRouter`（HOT_NEWS/ENTITY_SELF/GENERAL）+ `QueryRewriter`（LLM 改写 + 中文实体保护）
- `ResultReranker`/`EvidenceReranker`/`DomainTrust`/`ContextPacker`/`CitationVerifier`

### 缺失
1. 缺图片/视频搜索（image_search/video_search）
2. 缺新闻聚合/订阅（`HotNewsFetcher` 只实现 B 站，微博/知乎/百度预留未实现）
3. 缺商业 API 引擎（Google CSE/SerpAPI/Tavily/You/Bing API）
4. **缺 API key 配置界面**：`WebSearchKeys` 只存 brave_api_key，全库无设置页写它；`searxngUrl()` 硬编码空串
5. 缺持久化缓存（`MemoryCacheStore` 纯内存，重启失效）
6. 缺实时数据源（天气/股价/汇率/航班结构化 API）
7. 缺语义级去重（只有 URL 归一化）
8. 缺全局限流/配额管理
9. 缺「换一批」/分页消费入口

---

## 12. UI 组件层 / 动效层 / 交互层 —— 组件数量反超，但分裂 + 动效弱

### 现状
- 组件：`QuroSettingsComponents`（14 设置组件）+ `QuroChatCards`（50+ 卡片视图）+ `genuiagent-sdk`（GenUI）三套并存，命名不统一
- `QuroComponentGalleryScreen`（名册驱动 80 种组件自动生成，参考没有）

### 缺失
1. **组件体系分裂**：无统一 Button/Card/TextField 基元（参考是 `ZorvButton/ZorvCard/ZorvTextField` 一套）
2. **动效层明显弱**：`animateContentSize` 全库 0 命中；`updateTransition`/`Crossfade`/`animateColorAsState`/`animateDpAsState` UI 层几乎 0 命中；`QuroChatCards.kt` 的 `AnimatedVisibility` 只 import 未用
3. **交互反馈不完善**：点击态全靠 Material3 默认；`SetRow/PrimaryButton` 自绘无 ripple/按压态/长按；`ChatScreen.kt:6178` 主动关 ripple
4. 无 hover（移动端可理解，但按压态/长按可补）

---

## 13. 优先级排序（建议补齐顺序）

| 优先级 | 维度 | 理由 |
|---|---|---|
| P0 | 摄像头三件套 | 完全缺失，参考清晰，独立成块，风险低 |
| P0 | 外观主题三态 + 4 个硬编码 bug | 你明确点名「大 bug」，影响所有界面观感 |
| P1 | 桌面宠物接线（形象统一+交互+状态联动+桌面常驻） | 你明确点名「完全没有」，当前是脱节骨架 |
| P1 | 模型故障转移接线（接入主链路 + 健康检查真探活） | 半成品，直接影响核心对话稳定性 |
| P1 | 定时倒计时 + 日历提醒 + 修 TaskWorker 空壳 + 参数 key bug | 明确的缺失 + 明确的 bug |
| P2 | 浏览器内建（广告拦截/无痕/Cookie/多标签） | 工作量较大，且已有 aci_call 委托兜底 |
| P2 | 搜索 API key 配置界面 + 新闻聚合 + 图片搜索 | 增强项，现有已碾压参考 |
| P2 | UI 组件统一 + 动效层 | 重构面大，建议独立专项 |
| P3 | 终端按键映射/历史上翻、对话框内联组件、工具流式进度/重试按钮 | 打磨项 |

---

## 附：关键文件索引

- 参考相机：`droid-mcp-src/droid-mcp-camera/src/main/kotlin/io/droidmcp/camera/*.kt`
- 参考闹钟：`droid-mcp-src/droid-mcp-alarms/src/main/kotlin/io/droidmcp/alarms/*.kt`
- 参考主题：`ZorvAI-Desktop/src/main/kotlin/com/zorvai/desktop/ui/theme/Theme.kt`
- 参考组件：`ZorvAI-Desktop/src/main/kotlin/com/zorvai/desktop/ui/Components.kt`
- 参考浏览器：`ZorvBrowser/aci-browser/src/main/java/com/ai/assistance/quro/browser/BrowserCore.kt`
- 参考模型网络：`ZorvAI-Desktop/src/main/kotlin/com/zorvai/desktop/core/network/LlmClient.kt`
- QuroAI 相机现状：`app/src/main/java/com/ai/assistance/quro/service/QuroVideoCallService.kt`
- QuroAI 主题：`app/src/main/java/com/ai/assistance/quro/ui/theme/QuroTheme.kt`
- QuroAI 宠物：`app/src/main/java/com/ai/assistance/quro/{ui/QuroVoiceBallView.kt, service/QuroVoiceBallService.kt, genui/aiapp/ui/PetSettingsScreen.kt}` + `genuiagent-sdk/.../PetComponent.kt`
- QuroAI 定时：`app/src/main/java/com/ai/assistance/quro/{core/model/QuroTaskScheduler.kt, core/tools/QuroScheduledTask.kt, core/tools/QuroTaskSchedulerTool.kt}`
- QuroAI 模型：`app/src/main/java/com/ai/assistance/quro/core/{model/QuroFailoverManager.kt, network/QuroLlmClient.kt, cluster/LlmGateway.kt}`
- QuroAI 搜索：`app/src/main/java/com/ai/assistance/quro/core/websearch/*.kt`
