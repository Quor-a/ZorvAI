# Zorv AI 知识库与机器人接入说明

## 知识库（RAG）工作机制
知识库位于 App 私有目录 `knowledge_base/`，支持 Markdown / TXT / JSON / Office 文档。
索引流程：
1. 文档按段落切分为约 800 字符的片段（含 80 字符重叠）。
2. 通过 Embedder 生成向量：配置了网关 `baseUrl` + `apiKey` 时走远程语义向量；否则走本地哈希向量。
3. 向量存入内置 SQLite 数据库 `quro_rag.db`。
4. 检索时把问题向量化，取最相近的 topK 片段返回。

检索工具：`knowledge_rag_search`
- `{"query":"问题"}` 语义检索
- `{"action":"reindex"}` 重建索引
- `{"action":"count"}` 查看索引片段数量

## 机器人平台（Bot）接入
QQBot / 飞书 / 企业微信三者均无法在 Android 端独立长期收消息，需公网回调或长连接，且验签解密在服务端。
因此生产链路为：后端 Relay 接收平台消息 → 转发给 App → App 内 `QuroBotReplyEngine` 用 `QuroAssistant` 产出回复 → 回传 Relay 发送。

App 端当前已具备：
- `QuroBotManager`：平台枚举 + 统一收消息入口 `handleInbound`。
- `QuroLocalBotAdapter`：本地测试适配器（内存回显），可用于 Phase 1 端到端打通。
- `QuroRelayBotAdapter`：QQ / 飞书 / 企微共用基类，预留 `decodeInbound` 与回传 POST。
- `ui/QuroBotSettingsScreen`：四平台开关 + 凭据输入 + 本地测试消息框。

如需接入真实平台，需提供各自的中间件（Relay）端点与凭据。
