# 顶部重新规划 / 改名人格 / 界面清理（2026-11-04 第四批）

## 已完成（全部编译通过，fix4 APK）

| # | 任务 | 实现 | 位置 |
|---|---|---|---|
| 1 | 顶栏重新规划：删顶部「上下文窗口」按钮（输入区已有同入口）、「视频通话」挪进侧边栏 | ChatTopBar 只留 工具中心+设置+最小化；HistoryDrawer 在 DeepSeek Harness 下方新增「视频通话」行 | `ui/chat/ChatTopBar.kt`、`ui/ChatScreen.kt` HistoryDrawer |
| 2 | 设置删「搜索服务」行（用户裁定乱搞） | 行已删（屏幕代码保留，联网开关运行时不受影响） | `ui/ChatScreen.kt` 设置组 |
| 3 | 虚拟屏默认宽度比例 → 外观与对话 | BgOverlayRow（滑块）从设置页挪到「外观与对话」首位 | `ui/ChatScreen.kt` |
| 4 | 灵魂注入体系**不叫 Bot** → 改名「人格」 | SoulUi 里「Bot 默认模型」→「人格默认模型」（代码注释保留对齐说明）；人格卡=BotHub Bot 的对应物 | `ui/QuroSoulUi.kt` |
| 5 | 人格默认模型**直接拉模型配置**，不再手动填 | 读 `QuroModelConfigRepository.loadModelListCache(baseUrl)`（模型设置页拉取过的 /models 缓存）做下拉；当前全局模型置顶兜底；缓存为空才回退手填 | `ui/QuroSoulUi.kt` |
| 6 | Web 应用双重标题（截图2） | 面板内重复的「Web 应用」标题删除，只留 导入HTML/新建示例 行 | `ui/QuroToolCenterScreen.kt` MiniAppPanel |
| 7 | 小程序列表双重标题（截图3） | 同上处理 | `ui/QuroToolCenterScreen.kt` MiniAppSdkPanel |
| 8 | 桌面模式设置一片黑（截图5） | libtaskbar 的 MainActivity/EnableAdditionalSettings/Dashboard 强制 `Theme.Quro.TaskbarSettings`（AppCompat Light + 米白底），合并清单 `tools:replace` 覆盖库主题 | `AndroidManifest.xml`、`res/values/themes.xml` |

## 未完成（诚实清单，均已定位）

| 项 | 现状与已查明信息 |
|---|---|
| **人格孵化+自动进化合体（自我进化重写）** | 现状：孵化=IncubationWorkshopScreen 产出 persona.incubation 文本（确实只是"改笔记"）；进化=maybeAutoEvolve→BotEvolution（改 soul/memory）。合体方向已定：统一为「学习进步」管线——按 BotHub la1.java 的进化 prompt 骨架（输入=最近对话分批+Soul≤4000+Memory≤12000，输出=严格 JSON：memory_reason/memory_md/soul_reason/soul_md/motto/open_items[]，open_items 带 todo≤3/title≤24字/reply≤60字/due_date），**产出 open_items 驱动"下一步学什么"**，而非直接改写灵魂编辑文本。参考插件已定位：DSH 插件注册表 `ZorvAI-Desktop-KMP/resources/common/engine/dsh.py:340`（大肥鱼桌宠 desk-pet git 插件，其自进化 prompt 可从其 git 源取）。 |
| **视频通话用不上视觉模型** | 链路排查完毕：`QuroVideoCallService.injectFrameContext` + `QuroFrameVisionRouter`（主模型视觉→视频通话模型→图像识别模型 三级降级）存在且主模型路径默认乐观判定。用户实测"LLM 自带视觉用不了"——疑似主模型帧注入后 ask 链路对 store 附件的下发缺失或 MAIN_MODEL 分支的帧未真正进入本轮消息。下一步：真机日志 `QuroDiag`/`NodeEditor` 同级埋点核对 store→toLlmMessages 的 attachments 传递。 |
| 小卡片骨架重构（Widget Lab 设计稿） | 等用户 HTML 渲染设计稿 |
