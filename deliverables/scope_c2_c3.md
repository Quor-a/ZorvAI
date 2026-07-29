# QuroAI 范围报告：C2 机器人平台集成 + C3 知识库 RAG 重做

> 评审视角：GStack Product Reviewer
> 约束遵守：仅新建自包含文件；未修改 `QuroToolsKnowledge.kt` / `QuroChatViewModel.kt` / `core/model/*` 等任何现有文件；未运行 gradle 构建；未引入与现有架构冲突的重依赖（仅复用既有 OkHttp / Compose / SQLite）。未 push。

---

## 0. 现状速览（从源码确认的事实）

| 事实 | 位置 |
|------|------|
| 对话内核 `QuroAssistant`（ReAct 工具循环）构造器 `(client, registry, store)` | `core/QuroAssistant.kt:27` |
| `QuroLlmClient` 是 OpenAI 兼容 `/chat/completions` 客户端，支持 tool calling | `core/network/QuroLlmClient.kt:35` |
| 工具注册表 `buildQuroRegistry(context)` 已注册全部内置工具（含 `knowledge_search/knowledge_add/knowledge_manage`） | `core/tools/QuroBuiltInTools.kt:149` |
| 玩具级知识检索：仅文件名 + 行 `contains`，无向量/语义 | `core/tools/QuroToolsKnowledge.kt:51-83` |
| 知识库目录 `QuroKnowledgeFiles.dir(context)` → `knowledge_base/` | `core/tools/QuroToolsKnowledge.kt:14` |
| Office 解析 `extractOfficeText(file): String` 已存在，可复用 | `ui/QuroDocumentViewer.kt:235` |
| 会话存储 `QuroConversationStore`（`add/clear/toLlmMessages`）+ `QuroMessage` | `core/QuroConversation.kt:37` `:13` |
| 模型配置 `QuroModelConfig`（`baseUrl/apiKey/model`…）由 `QuroModelConfigRepository` 持久化 | `core/model/QuroModelConfig.kt:11` `:28` |
| 主对话 ViewModel 有 `companion object instance`（bot 可选镜像入口） | `ui/QuroChatViewModel.kt:101` |
| 设置/知识页入口模式：`ChatScreen.kt:1267` 的 `showKnowledge` + `QuroKnowledgeScreen(onClose=...)` | `ui/ChatScreen.kt:1267` |
| 依赖：OkHttp 4.12、Coroutines 1.9、Compose BOM、Material3、icons-extended；**无 Room / 无 ML 框架** | `app/build.gradle.kts:100-141` |
| Manifest 已声明 `INTERNET` / `FOREGROUND_SERVICE_DATA_SYNC` / `POST_NOTIFICATIONS`，无机器人专属组件 | `AndroidManifest.xml` |

**关键结论**：两个功能都不是从零起步——可完整复用 `QuroAssistant + QuroLlmClient + buildQuroRegistry + QuroConversationStore + QuroModelConfigRepository`，机器人回复与 RAG 检索都只是「把这些零件按新入口接线」。这大幅降低风险。

---

## 1. C2：QQBot / 飞书 / 微信Bot 集成

### 1.1 逐平台 MVP 集成方案（授权 / 链路 / 复用点 / 最小依赖）

| 平台 | 授权方式 | 消息收发链路（MVP） | 复用点 | 最小依赖 |
|------|----------|----------------------|--------|----------|
| **QQ 机器人** | 机器人 `appid` + `bot_token`（QQ 开放平台） | 平台 → **后端 Relay**（公网回调/反向 WS）→ App `handleInbound` → `QuroBotReplyEngine` → 回复 → `deliver` → 后端 Relay → 平台发送接口 | 全部复用（见 1.3） | 复用 OkHttp；**后端中转必需** |
| **飞书机器人** | `app_id`/`app_secret` + `encrypt_key`（事件订阅） | 同上（后端做 URL 验证 challenge + 事件解密） | 同上 | 复用 OkHttp；**后端中转必需** |
| **企业微信** | `CorpID`/`Secret` + `AESKey`/`Token`（可信回调） | 同上（后端做 `msg_signature` 校验 + AES 解密 XML） | 同上 | 复用 OkHttp；**后端中转必需** |
| **本地测试**（Phase 1 打通用） | 无 | `sendLocalTest(text)` → `handleInbound(LOCAL)` → 回复引擎 → 内存监听器回显到设置页 | 同上 | 零依赖 |

### 1.2 哪些能在 Android 端直接跑？哪些必须后端中转？（明确结论）

- **结论：QQBot / 飞书 / 企业微信 三者都无法在 Android 端独立长期收消息，生产链路一律需要后端 Relay。**
  - 原因：平台都要求**公网可访问的回调 URL** 或**反向 WebSocket 长连**；手机 App 在后台/无公网 IP 不现实，且验签/解密（QQ 签名头、飞书 `encrypt_key`、企业微信 AES）都在服务端完成。
  - 不要试图把官方 SDK 塞进 App 直接收消息——这是架构死路，会浪费大量时间。
- **App 端角色（明确边界）**：
  1. 存凭据 + 中继地址（`SharedPreferences "quro_bots"`）。
  2. 提供**回复引擎**（核心能力，直接复用 `QuroAssistant`）。
  3. 把回复 `deliver` 回中继（`OkHttp` POST，脚手架已实现为真实调用）。
  4. 可选经 `FCM`/长轮询/`MQTT` 接收中继推来的入站消息 → `handleInbound`。
- **唯一能在 App 内完整跑通的是「本地测试机器人」**：它不依赖任何外部服务，证明端到端管线（收→答→回传）可编译可运行。这是 Phase 1「一个平台打通」的务实选择。

### 1.3 分阶段实施计划

- **Phase 1（骨架 + 一个平台打通，约 1 周）**
  1. 落脚手架（已交付）：`QuroBotManager` + `QuroBotReplyEngine` + `QuroLocalBotAdapter` + `QuroBotSettingsScreen`。
  2. 在 `QuroApplication.onCreate` 调 `QuroBotManager.instance(app).registerDefaults(app)`（见 §3 落点 3）。
  3. 在 `ChatScreen` 加机器人设置入口（见 §3 落点 2）。
  4. 验证：设置页发测试消息 → 看到 AI 回复（端到端跑通）。
- **Phase 2（余下平台 + 后端 Relay，约 2-3 周）**
  1. 部署一个极简后端 Relay（任一语言）：接收平台事件 → 验签/解密 → 推/长轮询给 App；接收 App 的 `/send` → 调平台发送接口。
  2. 在 `QuroRelayBotAdapter.start()` 接长轮询/WebSocket（`decodeInbound` 按平台解析事件体）。
  3. 逐平台按 `QuroQqBotAdapter` / `QuroFeishuBotAdapter` / `QuroWecomBotAdapter` 的 TODO 落地。
  4. 按平台需要在 Manifest 加 `FOREGROUND_SERVICE_DATA_SYNC`（已声明）与一个 `QuroBotRelayService`（Phase 2 加，不改现有）。

### 1.4 脚手架文件清单（新建，未改现有）

| 文件 | 作用 |
|------|------|
| `core/bot/QuroBotManager.kt` | 平台枚举、`QuroInboundMessage`/`QuroOutboundMessage`、`QuroBotAdapter` 接口、`QuroBotManager` 总控（进程单例 + `handleInbound` 入口） |
| `core/bot/QuroBotReplyEngine.kt` | 复用 `QuroAssistant`+`buildQuroRegistry`+`QuroModelConfigRepository`，按「平台:用户」维护多轮会话，产出回复文本 |
| `core/bot/adapters/QuroLocalBotAdapter.kt` | Phase 1 打通的本地适配器（内存监听器回显） |
| `core/bot/adapters/QuroRelayBotAdapter.kt` | QQ/飞书/企业微信共用基类：读凭据 + `OkHttp` 把回复 POST 给中继 + `start/stop`/`decodeInbound` TODO |
| `core/bot/adapters/QuroQqBotAdapter.kt` | QQ 适配器占位（含 Phase 2 TODO） |
| `core/bot/adapters/QuroFeishuBotAdapter.kt` | 飞书适配器占位（含 Phase 2 TODO） |
| `core/bot/adapters/QuroWecomBotAdapter.kt` | 企业微信适配器占位（含 Phase 2 TODO） |
| `ui/QuroBotSettingsScreen.kt` | 设置页：四平台开关 + 凭据输入 + 本地测试消息框（Material3，同 `QuroKnowledgeScreen` 风格） |

### 1.5 接入点（file:line，仅记录，不改）

- 启动注册：`activity/QuroApplication.kt` `onCreate` → `QuroBotManager.instance(app).registerDefaults(app); startEnabled(app)`。
- UI 入口：`ui/ChatScreen.kt:1267` 同款 `showBots` 分支 → `QuroBotSettingsScreen(onClose=...)`。
- 入站收消息：后台 `Service`/`Receiver` 收到平台事件 → `QuroBotManager.instance(ctx).handleInbound(QuroInboundMessage(...))`。
- 可选 UI 镜像：把 bot 回复写入主对话 → `QuroChatViewModel.kt:101` 的 `instance.send(...)` 或 `voiceBallTurn(...)`。

---

## 2. C3：知识库 RAG 重做

### 2.1 RAG 重做方案（embedding / 向量库 / 检索-增强管线 / 接入点）

- **Embedding 选型（务实两档）**
  - **首选（真语义）**：复用用户已配置的模型网关 `/v1/embeddings`（`QuroRemoteEmbedder`，OkHttp 实现，零新增依赖）。绝大多数 OpenAI 兼容网关（OpenAI / DeepSeek / SiliconFlow / 本地 vLLM）都支持。默认模型 `text-embedding-3-small`，可配置。
  - **降级（离线开发）**：`QuroLocalHashEmbedder`（哈希 trick + L2 归一化），仅保证无网络也能跑通管线，**非真语义**，接真实 Embedding 后即弃用（已在注释标注 TODO）。
  - **端侧模型（未来）**：若要走完全离线，可用 ONNX Runtime / MNN 跑小句向量模型（如 bge-small），但属重依赖，建议 Phase 3 再评估——Phase 1/2 用 API Embedding 即可。
- **向量存储（轻量、零重依赖）**
  - `QuroSqliteVectorStore`：直接用 Android 内置 `SQLiteDatabase`（**不引入 Room**），向量以 `BLOB` 存 `FloatArray`，小库暴力余弦即可。
  - 规模上来后（>数万片段）接 `FTS5` + `sqlite-vss` 或换专用向量库（TODO，架构已留 `QuroVectorStore` 接口可平滑替换）。
- **检索-增强管线**（`QuroRagPipeline`）
  - 索引：文档 → 按段分块（~800 字 + 80 字重叠）→ `embedder.embed` → `store.upsert`（按 `doc_id` 去重）。
  - 检索：`embed(query)` → `store.query(topK)` → 拼成「来源 + 片段」上下文文本返回。
- **接入点（不改现有）**：现有 `KnowledgeSearchTool`/`KnowledgeManageTool` 注册于 `core/tools/QuroBuiltInTools.kt:242`（及 `:283`）。新增 `QuroRagKnowledgeTool`（`name=knowledge_rag_search`），与旧工具并存；注册落点见 §3 落点 1。旧玩具检索逻辑**原样保留**，不影响现有行为。

### 2.2 第三方平台接入 MVP（Notion / Obsidian / 本地文件）

| 平台 | 授权与同步方式（MVP） | 最小依赖 |
|------|------------------------|----------|
| **本地文件**（Phase 1 重点） | 直接读 `knowledge_base/` 下的 md/txt/json/Office（已支持），RAG 化 | 零（复用 `QuroKnowledgeFiles` + `extractOfficeText`） |
| **Obsidian** | 库即本地 Markdown 文件夹 → 直接当本地文件索引；Vault 同步走用户自身同步盘 | 零 |
| **Notion** | OAuth（`auth_service_*` 保险库已存在）拿 token → 拉取 page/block → 文本化 → 索引；增量同步需 Webhook/轮询 | 复用 OkHttp；OAuth 可走现有 `AuthServiceAddTool` 思路 |
| **其他（语雀/飞书文档/Confluence 等）** | 同上：OAuth/API token + 拉取文本化 + 索引 | 复用 OkHttp |

> 第三方接入本质是「把远端文档拉成文本 → 喂给现有 `QuroRagPipeline.indexFile/Text`」。脚手架已把索引逻辑与来源解耦（`indexDirectory` 只认本地文件），Phase 2 加一个 `QuroThirdPartySource` 拉取器即可，不改核心管线。

### 2.3 分阶段实施计划

- **Phase 1（本地文件向量化 RAG 跑通，约 1 周）**
  1. 落脚手架（已交付）：`QuroKnowledgeRag.kt`（`QuroEmbedder`/`QuroVectorStore`/`QuroRagPipeline`/`QuroRagKnowledgeTool`）。
  2. 把 `QuroRagKnowledgeTool` 注册进 `buildQuroRegistry`（落点 1）。
  3. 验证：`knowledge_rag_search` 首次调用自动建索引 → 语义召回优于 `knowledge_search`。
- **Phase 2（第三方平台，约 2 周）**
  1. 实现 `QuroThirdPartySource`（Notion/Obsidian/…）拉取 + 定时增量同步。
  2. 接 `AuthServiceAddTool` 体系做 OAuth 凭据托管。
  3. 索引按 `source` 命名空间隔离，`reindex` 支持按来源。

### 2.4 脚手架文件清单（新建，未改现有）

| 文件 | 作用 |
|------|------|
| `core/knowledge/QuroKnowledgeRag.kt` | 完整 RAG 骨架：`QuroEmbedder`（远程/本地）、`QuroVectorStore` + `QuroSqliteVectorStore`、`QuroChunk`/`ScoredChunk`、`QuroRagPipeline`、`QuroRagKnowledgeTool`（`knowledge_rag_search`，支持 `action=reindex/count`） |

### 2.5 接入点（file:line，仅记录，不改）

- 检索逻辑现状：`core/tools/QuroToolsKnowledge.kt:51-83`（行 contains 玩具检索，保留不动）。
- 新工具注册落点：`core/tools/QuroBuiltInTools.kt:242` 附近加 `r.register(QuroRagKnowledgeTool())`。
- 源文档目录：`QuroKnowledgeFiles.dir(context)`（`QuroToolsKnowledge.kt:14`）。
- Office 解析：`extractOfficeText(file): String`（`ui/QuroDocumentViewer.kt:235`）。

---

## 3. 统一接入落点（需你手动添加，3 处；不添加也不影响新文件编译）

新文件**自包含、可独立编译**——它们只依赖既有稳定 API。要让功能真正「被调用」，后续需加 3 个薄落点（均不改逻辑，只加一行接线）：

1. **注册 RAG 工具**：`core/tools/QuroBuiltInTools.kt` 的 `buildQuroRegistry` 内（约 `:242` 处）加
   ```kotlin
   r.register(QuroRagKnowledgeTool())
   ```
2. **加机器人设置入口**：`ui/ChatScreen.kt`（参考 `:1267` 的 `showKnowledge` 模式）加
   ```kotlin
   var showBots by remember { false }
   // 在菜单/设置项里置 showBots = true
   if (showBots) {
       QuroBotSettingsScreen(onClose = { showBots = false })
   }
   ```
3. **初始化机器人管理器**：`activity/QuroApplication.kt` 的 `onCreate` 加
   ```kotlin
   QuroBotManager.instance(this).registerDefaults(this)
   QuroBotManager.instance(this).startEnabled(this)
   ```

> 注：以上 3 处未执行时，新文件仍 100% 编译通过（它们不反向依赖这些落点）。统一构建验证时一并加入即可。

---

## 4. 编译与风险说明

- **编译就绪性**：所有新文件仅 import 项目既有类/依赖（OkHttp、Coroutines、Compose、Material3、Android SQLite、`org.json`），无未定义引用、无新增第三方依赖。**未运行 gradle**（遵守约束，避免与 v241 构建冲突）；统一构建时由 CI/你本地验证。
- **主要风险**
  - C2：Phase 1 仅本地适配器真实打通；QQ/飞书/企业微信的「真连通」取决于 Phase 2 后端 Relay 是否落地——这是工作量主体，不是 App 端。
  - C3：无 API Key 时降级哈希向量**不是真语义**，召回质量有限；需用户已配置支持 embeddings 的网关才能体验真 RAG。
  - C3 规模：SQLite 暴力余弦适合中小知识库；大库需按计划接向量索引（架构已留接口）。

---

## 5. 新增文件树

```
app/src/main/java/com/ai/assistance/quro/
├── core/bot/
│   ├── QuroBotManager.kt
│   ├── QuroBotReplyEngine.kt
│   └── adapters/
│       ├── QuroLocalBotAdapter.kt
│       ├── QuroRelayBotAdapter.kt
│       ├── QuroQqBotAdapter.kt
│       ├── QuroFeishuBotAdapter.kt
│       └── QuroWecomBotAdapter.kt
├── core/knowledge/
│   └── QuroKnowledgeRag.kt
└── ui/
    └── QuroBotSettingsScreen.kt
```

---

## 6. 结论

- 两项功能都**不是空壳从零造**，而是把既有 `QuroAssistant / QuroLlmClient / buildQuroRegistry / QuroConversationStore / QuroModelConfigRepository` 按新入口接线——风险可控。
- **C2 的核心认知**：QQ/飞书/企业微信都需后端 Relay，App 端只做「存凭据 + 回复引擎 + 回传」。Phase 1 用本地适配器把端到端链路跑通，Phase 2 补后端与三平台适配。
- **C3 的核心认知**：用「API Embedding + 内置 SQLite 向量库 + 分块检索管线」立刻得到真 RAG，复用 `knowledge_base/` 与 `extractOfficeText`；第三方平台只是「拉文本喂管线」。
- 已交付**最小可编译脚手架（9 个新文件）** + 本范围报告。未 push。
