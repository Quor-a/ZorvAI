# 审批卡对接 / 上下文窗口补全 / 卡片 WebView 兼容层 / 节点编辑器黑屏 / Bot 体系（2026-11-04 第二批）

> 对应 WorkBuddy 任务「修复 termux 与可视化卡片问题」第二批 + BotHub 反翻译对齐。
> 全部改动已通过 `:app:compileFullDebugKotlin` + `:termux-host:compileDebugJavaWithJavac`。
> 反编译参考：`D:\bothub_ref_172`（1.0.172，调研报告见会话/调研记录）。

## 一、审批卡「总是允许」对接（用户实测：一直允许了还要一直授权）

| # | 缺陷 | 修复 | 位置 |
|---|---|---|---|
| 1 | 「总是允许」只进内存且**无人落盘**，重启后同一个工具问一遍又一遍 | 新增 `ApprovalPersist`：启动回填（QuroApplication.onCreate）+ 拍板后全量落盘 | `core/chat/Approval.kt`、`activity/QuroApplication.kt`、`ui/QuroChatViewModel.resolveApproval` |
| 2 | 审批卡之外还有 CMS broker / 特权管理器**第二道关卡**，点了允许照样被拦 | `ApprovalPolicy.channelFor(toolName)` 判定工具所属通道，拍板「总是允许」时联动把权限面板的 **CMS / 特权开关置为允许**（横幅提示，可在面板改回） | `core/chat/ApprovalPolicy.kt`、`ui/QuroChatViewModel.kt` |
| 3 | 授权卡半透明背景，底下的聊天文字透上来「看不清」 | 卡片背景改不透明 `surfaceVariant`，边框加深 | `ui/chat/ApprovalCard.kt` |

## 二、上下文窗口补全（对照 BotHub 反翻译）

BotHub 分段 = 系统提示词/系统工具/MCP 工具/技能/记忆文件/消息/其他 + 自动压缩预留/剩余空间（运行时上报）。ZorvAI 的段枚举与弹层 UI 本来就支持 8 段，但计算侧只填了 3 段。

- `computeContextUsage` 补齐：**MCP 工具**（`mcp*` 前缀元工具单列）、**技能**（`skillsSectionText` 抽成唯一格式化实现，与注入同源）、**记忆**（persona.soul + memory entries，`memoryEnabled` 关闭时不计）。
- 技能/记忆正文确实拼在系统提示词里下发 → 按 token 估算**从系统提示词段里减出去**单列（estimateTokens 近似线性，coerce 保底非负）。
- 保留既有：自动压缩预留 + 剩余空间 + 回复预留；压缩（可停止/忙碌保护/「以上内容已压缩」分隔线）此前已实现，无需改动。
- 参考实现的「历史对话」是 Bot 工具开关（conversation_context），上下文窗口页无历史轮数控件 —— 不加。

## 三、卡片 WebView 统一兼容层（「外部资源加载不了」「组件超出屏幕」）

新增 `ui/chat/CardHtmlCompat.kt`，所有对话内卡片 WebView 统一走：

1. **https origin**（`https://localhost/`）替代 `null` / `file://` origin —— null/file origin 下 CDN fetch/XHR/ES modules 全被跨域策略拦掉，这是「外部资源直接加载不了」的根因；
2. **viewport 兜底**：无 viewport meta 的 AI HTML 不再按 980px 虚拟宽度渲染；
3. **兜底 CSS**：img/video/canvas/svg/table/iframe max-width:100%、pre/code 强制换行、box-sizing —— 任何 AI 写的卡片都塞得进手机屏；
4. 幂等注入（marker 检测），流式半截 HTML 也安全。

接线点：`ZxWidgetInline`（原 baseUrl=null）、`HtmlPreviewWebView`（原 file:// android_asset）、`run_code` 预览（buildPreviewHtml 完整文档分支 + 工具结果预览）。ZxWidgetInline 另补 mixedContentMode。

## 四、节点编辑器黑屏

| # | 根因 | 修复 |
|---|---|---|
| 1 | 渲染进程崩溃（onRenderProcessGone）无处理 → 永远黑屏 | 捕获后销毁尸体 + epoch 自增强制重建 WebView，自动恢复画布 |
| 2 | 离开面板不销毁 WebView → 渲染进程（有限资源）反复泄漏，第 N 次打开即崩 | 补 `onRelease { destroy() }` |

## 五、Bot 体系第一批（对齐 BotHub 反翻译 Bot.java / bot.json）

用户澄清：**Bot = BotHub 那种对话内一等公民**（模型+提示词+Soul+Memory+工具开关按 Bot 隔离），不是 IM 机器人（IM 灵魂注入第一批已做）。

| 能力 | 对齐字段 | 实现 |
|---|---|---|
| Bot 级默认模型 | `default_model_id` | `QuroPersona.defaultModelId` + 发送时覆盖 `cfg.model`（provider/key 跟随全局）+ SoulUi 编辑页「Bot 默认模型」输入框 |
| Bot 级采样覆盖 | `temperature(_enabled)` / `top_p` | 发送时 `persona.temperatureEnabled` → 覆盖 `cfg.temperature`（此前两条链路都无消费点） |
| Soul 进化调度 | `evolution_min_interval_hours`(默认8) / `evolution_turns_per_check`(默认10) | `QuroPersona.evolutionMinIntervalHours/evolutionTurnsPerCheck`（0–168h / 1–50 轮，序列化容错）+ `BotEvolution.shouldCheck/isCoolingDown` 增加 Bot 级重载 + `maybeAutoEvolve` 接入 |
| 已有对齐 | prompt/roleSetting、Soul、chatSetting、标签、memoryEnabled、evolutionEnabled、proactiveMode、enabledToolCategories、voiceProfile | QuroPersona 原有字段，命名与 BotHub 一一对应 |

**下一批（架构级，未动）**：会话↔Bot 绑定（conversations.botId 换绑 + 聊天页 Bot pill 切换）、Bot 目录与互问（bot_message/bot_threads + description 注入）、Bot 包 .bot.zip 导入导出、主动模式计划预览、Soul 进化 open_items。

## 六、遗留记录

- 小卡片骨架重构（对齐 Widget Lab 设计稿、AI 可写任意卡片、统一 动态UI/富卡片/miniapp/zx 术语）与小程序渲染不完整排查：待第三批，骨架设计以用户 HTML 文稿为准。
- zxwidget 内联编译器与 zx/zxwidget.js 的已知漂移（ZxWidgetRuntime.kt 注释）仍在。
