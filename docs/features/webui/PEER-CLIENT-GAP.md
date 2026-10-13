# Web UI 对等客户端改造：功能差距清单与方案

> 用户指令（2026-10-13）：「web ui拥有zorvAI所有功能，界面一样，功能一样，另外一个zorvAI对话框」。
> 本文档逐项对照原生 ZorvAI 对话框，记录 Web UI 现状差距与改造方案。

## 一、现状定性：Web UI 是「投影接收端」，不是对等客户端

代码里的自证（必须全部消除）：

| 位置 | 原文 |
|---|---|
| `web-chat/chat.js:2` | 「Web UI 运行时 —— **ZorvAI 对话框投影的接收端**」 |
| `web-chat/chat.js:665-680` | 权限开关点击后：「这里是投影，开关请回 ZorvAI 原生对话框设置」 |
| `web-chat/index.html:14` | 「Web UI —— ZorvAI 对话框的**投影**」 |
| `WebUiServer.kt:25` | 「ZorvAI 对话框的**投影服务**」 |
| `WebUiServer.kt:32-33` | 「它**不是**一个独立应用的服务器…网页是投影的接收端，不是第二个产品」 |

## 二、原生对话框功能清单（权威索引）

### A. 顶栏 ChatTopBar
- 标题、当前模型名、当前人格名、在线点、**新对话**按钮。

### B. 权限模式条 PermissionModeBar（原生是**可点开关**）
| 开关 | 状态源 | 原生切换 |
|---|---|---|
| 深度思考 | `vm.thinking` | `vm.setThinking` |
| 记忆 | `vm.autoSaveMemory` | `vm.setAutoSaveMemory` |
| 子智能体 | `vm.subAgentEnabled` | `vm.setSubAgentEnabled` |
| 自动朗读 | `QuroVoiceFeaturePrefs` | `ChatScreen.toggleAutoRead` |
| 看懂屏幕 | `vm.visionEnabled` | `vm.setVisionEnabled` |
| CMS 策略 | `QuroPolicyStore.getCms` | `QuroPolicyStore.setCms` |
| 特权策略 | `QuroPolicyStore.getPriv` | `QuroPolicyStore.setPriv` |
| 工作区 | `WorkspacePreferences` | `WorkspaceSelectionDialog` |

### C. 模型选择弹层（SheetType.Model）
- 分组：当前配置 / 已保存预设(`__profile__*`) / 自定义供应商(`__custom__*`) / 本地模型(`__local__*`) / API 拉取。
- 选择逻辑：预设→`profileRepo.applyToConfig`；自定义→`modelVm.update{provider="OTHER"...}`；本地→`modelVm.update{provider=type, localModelPath, model}`；普通→`modelVm.update{copy(model=id)}`。
- 附：「添加模型」→ 模型配置屏；「从 API 拉取」→ `modelVm.fetchModels()`。

### D. 人格选择弹层（SheetType.Persona）
- 人格列表 `personaVm.personas`，激活 `personaVm.activePersona`，切换 `personaVm.setActive(id)`。
- 「添加人格」→ 全屏人格编辑。

### E. 上传（+工具面板 SheetType.Upload）
- 图片/文件/视频选择：`pickLauncher.launch(mime)` → `QuroAttachmentKit.fromUri` → `attachments`。
- 工具面板：导入工具 / AI 搜索 / 文档生成 / 上传 / 语音。

### F. 语音面板（SheetType.Voice）
- TTS / STT / 语音服务设置入口。

### G. 设置弹层（SheetType.Settings）
- 深色模式、声音、回车发送、字体、模型配置、语音、关于、外观、灵魂、记忆、权限中心、CMS、系统状态、组件画廊、集群、工具箱、插件、技能、知识库、终端、编辑器、OnlyOffice、构建台、KaleidoBox、浏览器、文件管理器、调度、工作流、Bots、MCP、ACI、USB 调试、默认应用…

### H. UI 动作工具（QuroToolsUiActions，AI 可调用的全部面板）
`ui_open_onlyoffice / knowledge / terminal / editor / toolbox / kaleidobox / build / plugins / skills / cms / aci / permission / model_config / voice / tts / stt / voice_service / about / appearance / soul / memory / sheet_model / sheet_persona / sheet_settings / sheet_upload / sheet_voice / upload / import_tool / ai_search / doc_generate / webui / toggle_deepthink / toggle_memory / clear_chat / new_chat`

### I. 消息渲染（原生 MessageRow）
- 思考 → 正文块（text/heading/quote/rule/code/table + 原生专属 mermaid/aip/dynamic_ui/self_card/card/miniapp）→ 工具调用 → 互动UI界面(zxwidget) → 附件。
- 代码块复制、流式光标、自动滚动。

## 三、Web UI 现状 vs 目标差距

| 能力 | 原生 | Web UI 现状 | 差距 |
|---|---|---|---|
| 消息收发/停止/新对话 | ✅ | ✅ | 无 |
| 消息渲染（块） | ✅ | ✅ 但原生专属块只显示源码 | 需保留 |
| 思考/工具/附件 | ✅ | ✅ | 无 |
| zxwidget 真画 | ✅ | ✅ iframe 同源 | 无 |
| SSE 流式 | ✅ | ✅ | 无 |
| 后台常驻 | ✅ FGS | ✅ QuroWebUiService | 无 |
| 深度思考/记忆/子智能体/朗读/视觉 | ✅ 可点 | ❌ 只读 | **需改可写** |
| CMS/特权策略 | ✅ 可点 | ❌ 只读 | **需改可写** |
| 工作区选择 | ✅ | ❌ 只读 | **需改可写** |
| 模型选择弹层 | ✅ | ❌ 无 | **需新增** |
| 人格选择弹层 | ✅ | ❌ 无 | **需新增** |
| 上传图片/文件/视频 | ✅ | ❌ 无 | **需新增** |
| 语音面板（TTS/STT） | ✅ | ❌ 无 | **需新增** |
| 设置弹层 | ✅ | ❌ 无 | **需新增** |
| 工具面板（+） | ✅ | ❌ 无 | **需新增** |
| 50+ 功能面板入口 | ✅ | ❌ 无 | **需新增入口** |
| 新对话确认/历史会话 | ✅ | ❌ 无 | **需新增** |
| 主题/字体/声音/回车发送 | ✅ | ❌ 无 | **需新增** |

## 四、改造方案

### 原则
1. **同一个会话、同一份状态、同一条渲染链**：Web 端仍是读取原生 `QuroChatViewModel` 与各仓库，但把「只读投影」升级为「可写客户端」——所有开关、选择、上传都写回原生同一真源。
2. **界面与原生一致**：沿用现有顶栏/权限条/输入行布局，补齐可点击交互与弹层。
3. **不重写渲染链**：消息块继续走 `WebUiProjection`（与原生 `parseBlocks` 同源）。

### API 契约扩展（WebUiServer）
新增/扩展 POST 接口（全部写回原生真源）：

| 接口 | 方法 | 作用 |
|---|---|---|
| `/api/toggle` | POST | `{key:"deepThink"|"autoSaveMemory"|"subAgent"|"autoRead"|"vision", on:bool}` |
| `/api/policy` | POST | `{key:"cms"|"priv", value:"allow"|"deny"|"ask"}` |
| `/api/workspace` | POST | `{path:"..."}` 设置工作区 |
| `/api/models` | GET | 返回模型分组列表（当前/预设/自定义/本地/API） |
| `/api/models/select` | POST | `{id:"..."}` 走原生 `onSelectModel` 同一分支逻辑 |
| `/api/personas` | GET | 返回人格列表 + 激活 id |
| `/api/personas/select` | POST | `{id:"..."}` → `personaVm.setActive(id)` |
| `/api/upload` | POST | multipart 文件 → `QuroAttachmentKit.fromUri` 存入 attachments |
| `/api/voice` | GET/POST | 语音面板状态与 TTS/STT 动作 |
| `/api/ui_action` | POST | `{action:"ui_open_xxx"}` → `QuroUiActionBridge.request(action)` 打开原生面板 |
| `/api/export` | POST | 导出当前会话 |
| `/api/clear` | POST | 清空对话 |

前端新增：模型/人格弹层、上传按钮、语音按钮、工具面板（+）、设置弹层、各功能面板入口网格。
所有开关改为可点击并即时写回；去掉「这里是投影」提示。

### 文件改动清单
1. `WebUiServer.kt`：去掉「投影服务」定位注释；新增上述 API 路由与模型/人格/上传处理。
2. `WebUiProjection.kt`：保持块渲染同源；`composer` 增加 `modelsAvailable`/`personasAvailable` 等元信息（可选）。
3. `web-chat/index.html`：顶栏加模型/人格点击、加号工具面板、设置按钮；布局对齐原生。
4. `web-chat/chat.js`：全部交互实现。
5. `web-chat/style.css`：弹层/面板/上传样式。
6. `scripts/webchat/stub_server.js` 与 `e2e_run.js`：新增接口桩与 E2E 用例。
7. `docs/features/webui/README.md`：更新定位为「对等客户端」。

### 验证
- 契约检查 + E2E（新增 toggle/policy/models/personas/upload/ui_action 用例）。
- 冒烟测试、parity、资产 SHA-256、release 签名、`assembleFullRelease`。