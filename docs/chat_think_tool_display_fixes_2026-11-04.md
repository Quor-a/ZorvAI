# 对话思考/工具显示 + bot 灵魂注入 + termux 修复批（2026-11-04）

> 对应 WorkBuddy 任务「修复 termux 与可视化卡片问题」中的对话细节部分。
> 全部改动已通过 `:app:compileFullDebugKotlin` + `:termux-host:compileDebugJavaWithJavac`。

## 一、思考显示（「不收起 / 0步 / 219 步混乱」）

| # | 缺陷 | 根因 | 修复 | 位置 |
|---|---|---|---|---|
| 1 | 流式思考整块全量展开（真机 219 步），正文被顶走、数分钟不收 | `ThinkInlineContent` 渲染全部步骤且无上限；busy 覆盖整个编排任务 | 流式内联只挂**最近 3 步**，其余显示「⋯ 已展开 N 步，点此查看全部」；头部可点开「思考过程」全量弹层 | `ui/ChatScreen.kt` `ThinkInlineContent` |
| 2 | 工具循环里思考块在展开/折叠间反复横跳 | `streamingThink = busy && index == lastIndex` 会被末尾 -1/-2 等待/工作**占位行**抢走 lastIndex | 改为 `index == lastRealIdx`（排除 isWaiting/isWorking 占位行） | `ui/ChatScreen.kt` MessageList |
| 3 | 「思考过程 · 0步」 | `ThinkFoldRow` 调用点漏传 `steps`，耗时未知（工具轮/重启回放）一律 0步 | 调用点补传 `steps = msg.think.steps.size` | `ui/ChatScreen.kt` |
| 4 | 重启后「思考了 N秒」口径永久丢失 | `thinkDurationMs` 从不落盘 | 序列化新增 `thinkMs`（解析端向后兼容） | `core/QuroConversationPersistence.kt` |
| 5 | 旧思考行消失/缩水（不可逆） | `TOOL_THINK_KEEP=8` 清理时 `store.all()` 全库过滤，把**历史回合**的 reasoning 也清空 | 作用域收窄到本轮（最后一条 user 消息之后） | `core/QuroAssistant.kt` |
| 6 | 思考步骤大量近重复（「好的，让我执行。」×N 撑出 316+ 步） | 相邻重复行原样并入 | 聚合层相邻去重 + ThinkInlineContent 渲染前去重 | `ui/ChatScreen.kt` |
| 7 | 思考弹层跨会话残留 | 切会话不清 `thinkSheet` | LaunchedEffect(currentId) 里重置 | `ui/ChatScreen.kt` |
| 8 | 集群角色名重启后丢失 | `senderName` 不落盘 | 序列化新增 `senderName` | `core/QuroConversationPersistence.kt` |

## 二、工具调用显示

| # | 缺陷 | 修复 | 位置 |
|---|---|---|---|
| 1 | 「· 250 工具」徽章把思考步数也算进工具数（219 步思考 + 31 工具 = 250），纯思考也显示「N 工具」 | 计数口径改为**只数工具调用**；图标随展开态切换（chevron_up/down） | `ui/ChatScreen.kt` 名字行 TinyChip |
| 2 | 工具明细默认一层都看不见（showActivity 默认 false） | 活动流**默认展开**（汇总行 + 逐条明细直接可见），胶囊只做收起开关 | `ui/ChatScreen.kt` |
| 3 | 同一段推理一屏出现三遍（折叠行 + 活动流条目 + 流式内联） | 思考不再进活动流（有专属「思考了 N秒 ›」行） | `ui/ChatScreen.kt` activityEntries |
| 4 | 每条 AI 消息无条件平铺 8 个操作按钮，窄屏 FlowRow 堆 2-3 行（「顶部太多重新规划」的来源）；生成中照常显示，可对流式消息重复触发重试 | 完整操作行只挂**最后一条 AI 消息且本轮已结束**；历史消息走名字行长按菜单；新增 `showActions` 参数统一门控（动态 UI 分支与普通分支两处） | `ui/ChatScreen.kt` MessageList/MessageRow |
| 5 | 顶部「AI 思考中…」胶囊与消息流内的进行中表达重复（用户拍板：多余） | NotificationIsland 只保留「N 个对话后台生成中」；当前会话状态由消息流内表达 | `ui/ChatScreen.kt` |
| 6 | zx_web_card 参数非法时可视化卡片**凭空消失**（既无内联块也无工具行） | zxSpecOf 失败降级为普通工具行，调用痕迹可见 | `ui/ChatScreen.kt` 聚合层 |

## 三、bot 对接灵魂注入（QuroBotReplyEngine 重写）

旧实现：QQ/飞书/微信三条 IM 通道共用一份**无人格硬编码提示词**——激活人格卡的 roleSetting/Soul/聊天设定/标签/记忆/技能全部丢失，openid 被当用户名。

新实现与主聊天（`QuroChatViewModel.buildSystemPrompt`）同构：
1. `QuroPlatformManifest.SYSTEM` 品牌基座
2. `QuroReplyLanguage` 回复语言 + 思考语言指令
3. `QuroSoulPromptEngine.build(SoulContext)` 灵魂层（身份/roleSetting/**Soul**/聊天设定/语气标签/记忆）
4. 模板变量 `{{current_datetime}}` / `{{device_info}}` / `{{location}}` 发送前替换（location 走 Geocoder，与主聊天同逻辑）
5. 应用中心服务目录 `QuroServiceCatalog.promptSection`
6. 用户技能 `QuroSkillStore.enabledListFor(persona.id)`
7. 用户称呼：`QuroBotManager` 透传 `message.userName`（修 openid 问题）
8. IM 场景约束段（保留原引擎的场景提醒）

行为参数：`autoSaveMemory` 跟随 `persona.memoryEnabled`（原来写死 false）；`enabledToolCategories` 跟随人格卡白名单（原来不传=全量工具）；`persona.temperatureEnabled` 时覆盖采样温度。模型按 Bot 隔离（G7）、Soul 进化触发（G13）、音色（G14）属架构级，本轮未动。

## 四、termux

| # | 缺陷 | 修复 |
|---|---|---|
| 1 | `TermuxInstaller.loadZipBytes` 里 `System.loadLibrary("termux-bootstrap")`，但 `libtermux-bootstrap.so` 全工程不存在——首次打开 TermuxActivity（前缀为空走安装线程）抛 `UnsatisfiedLinkError`（Error 接不住）直接崩溃 | 新增 `loadZipBytesOrNull()` 包 Throwable；安装线程判空后走统一 bootstrap 错误弹窗，不再崩进程 |
| 2 | 前缀路径分裂：TermuxConstants 指向 `filesDir/usr`，App 侧 TermuxEnvironment 实际装在 `filesDir/termux/usr`——界面与 `termux run` 不是同一套文件 | `TERMUX_PREFIX_DIR_PATH`/`TERMUX_HOME_DIR_PATH` 统一移入 `/termux`（proot rootfs 由 terminal-core 自行计算路径，不受影响） |
| 3 | `termux(open)`/界面拉起时前缀可能未安装，宿主又没有可用的 bootstrap so | `TermuxTool.openTermux` 先 `needsInstall → install()` 再拉起 Activity；装好即「前缀已存在 → 直达会话」 |

## 五、未做（记录在案）

- TurnOutcomeBar 与消息行「重试」的重复：TurnOutcomeBar 仅在轮次异常收尾时出现，保留。
- ClusterChatBridge 的「↻ 第 N 次重规划」消息行：信息性内容，保留。
- 模型按 Bot 隔离 / Bot Soul 进化 / Bot 音色：架构级，需单独推进。
- x86_64 bootstrap 包（当前 abi 仅 arm64-v8a）。
