# Zorv AI 产品概览

Zorv AI 是一款运行在 Android 端的开源 AI Agent 智能体助手应用，包名为 `com.ai.assistance.quro`。

## 核心定位
- 设备端离线运行：核心推理、工具调用、知识库检索均可在设备端完成，无需强制联网。
- 多引擎：支持云端大模型、端侧小模型（Sherpa-NCNN）、以及本地 Linux 沙箱（proot + Alpine）执行环境。
- 工具生态：内置文件读写、终端、定时任务、知识库检索、机器人平台接入等能力。

## 主要模块
1. 对话与推理：基于 `QuroAssistant` 的多轮对话引擎，支持深度思考、自动记忆保存。
2. 工具系统：在 `core/tools` 下注册各类工具，AI 可按需调用。
3. 知识库（RAG）：`core/knowledge/QuroKnowledgeRag.kt` 提供向量语义检索，默认使用用户配置的 `/v1/embeddings` 网关；未配置 API Key 时降级为本地哈希向量（离线可用，非真语义）。
4. 机器人平台：在 `core/bot` 下提供 QQ / 飞书 / 企业微信的接入骨架，生产链路依赖后端 Relay 回传。
5. 定时任务：通过 `ScheduleTaskTool` + `AlarmManager` 实现本地定时调度。
6. 可视化组件库：在设置页可打开「可视化组件库」查看 Material3 组件 Demo。

## 使用提示
- 知识库内容存放于 `Android/data/com.ai.assistance.quro/files/knowledge_base`，首次使用会自动从内置示例播种。
- 检索时调用 `knowledge_rag_search` 工具；如返回「尚未建立索引」，可先执行 `action=reindex` 重建索引。
