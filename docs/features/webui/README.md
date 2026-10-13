# Web UI —— ZorvAI 对话框的对等客户端

> 定位（用户 2026-10-13 拍板）：**「web ui拥有zorvAI所有功能，界面一样，功能一样，另外一个zorvAI对话框」**。
> Web UI 不是投影，不是只读镜像，而是 ZorvAI 对话框在浏览器里的**对等客户端**。

## 能力清单

| 能力 | 实现 |
|---|---|
| 消息收发 / 停止 / 新对话 / 清空 | `/api/send` `/api/stop` `/api/new` `/api/clear` |
| 消息渲染（思考/正文/工具/附件/zxwidget） | 与原生同源（`WebUiProjection` + `parseBlocks` + `/zx/shell` 同一份运行时） |
| SSE 流式 | `/api/events`（80ms 指纹，变化即推） |
| 深度思考 / 记忆 / 子智能体 / 自动朗读 / 看懂屏幕 | `/api/toggle` 写回原生同一真源 |
| CMS / 特权策略 | `/api/policy`（allow/deny/ask 循环） |
| 工作区 | `/api/workspace` |
| 模型选择弹层 | `/api/models` + `/api/models/select`（当前/预设/自定义/本地/API 分组） |
| 人格选择弹层 | `/api/personas` + `/api/personas/select` |
| 上传图片/文件/视频 | `/api/upload`（base64 → 应用私有目录 → 随消息发送） |
| 工具面板 / 语音 / 设置 / 全部功能入口 | `/api/ui_action` → `QuroUiActionBridge` 打开原生面板 |

## 关键设计

1. **同一个会话、同一份状态、同一条渲染链**：Web 端读取原生 `QuroChatViewModel` 与各仓库，
   所有开关、选择、上传都写回原生同一真源（`vm` / prefs / `QuroPolicyStore` / `WorkspacePreferences` / 模型人格仓库）。
2. **模型切换复刻原生分支逻辑**：预设走 `QuroSavedProfileRepository.applyToConfig`，
   自定义走 `provider=OTHER` + `ApiProviderConfigs.detectProviderFromUrl` 自动识别，
   本地走 `provider=type + localModelPath`，普通模型只改 `model` 字段。
3. **后台常驻**：`QuroWebUiService`（前台服务）持有 `QuroWebUiHost` 单例，App 内只留设置对话框。

## 验证

- `node scripts/webchat/api_contract_check.js` —— 前后端路由/字段契约
- `node scripts/webchat/e2e_run.js` —— 真浏览器 E2E（含对等客户端交互）
- `node scripts/zx/zx_runtime_smoke.js` —— zxwidget 冒烟 477 例
- `node scripts/zx/zxwidget_parity_check.js` —— 双运行时同源比对
- 资产 SHA-256 与源码一致 + release 签名 + `assembleFullRelease`