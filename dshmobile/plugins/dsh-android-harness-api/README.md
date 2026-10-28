# dsh-android-harness-api

ZorvAI ⇄ DeepSeek Harness 互控 API 面：把引擎已装配的**工具注册表**与**技能库**以 HTTP JSON 暴露给宿主应用，让 ZorvAI 侧的 AI 能任意调用 harness 的插件工具（plugins）与技能（skills）。

## 路由（均挂引擎自己的 webServer，127.0.0.1 本地）

| 方法 | 路径 | 作用 | 底层 |
| --- | --- | --- | --- |
| GET | `/api/harness/tools` | 列举全部可见工具（name/description/parameters/deferLoading） | `ctx.tools.schemas()` |
| POST | `/api/harness/tools/call` | 以引擎全管线执行任一工具 | `ctx.tools.execute()` |
| GET | `/api/harness/skills` | 列举技能目录（SkillSummary） | `ctx.skills.list()` |
| POST | `/api/harness/skills/load` | 加载任一技能完整定义（含正文） | `ctx.skills.get(name)` |

### tools/call 请求体

```json
{ "name": "zorvai_bridge", "arguments": { "refresh": true }, "timeoutMs": 120000 }
```

- `name`：必填，非空字符串。
- `arguments`：可选，原样透传（工具自校验 schema）；缺省 `{}`。
- `timeoutMs`：可选，夹取到 `[1, 600000]`，缺省 `120000`；超时经 `AbortSignal` 中断执行。

### tools/call 响应体

```json
{
  "ok": true, "name": "zorvai_bridge", "callId": "harness-api-xxx",
  "isError": false, "durationMs": 42,
  "text": "扁平回执文本", "content": [/* 原始 content blocks */],
  "value": { }
}
```

- `ok`：**API 面自身**是否成功（协议/管线异常时为 false，配 4xx/5xx 状态码）。
- `isError`：**工具级**结果（与模型发起调用所见完全一致；失败时带 `error.message`/`error.info`）。

### 状态码

- `200`：成功（含工具级失败 `isError:true`）。
- `400`：请求体超 1MiB / 非法 JSON / 缺 name。
- `401` / `403`：connection 信任栅栏拒绝（Host/Origin 不合或未认证）。
- `404`：技能不存在。
- `405`：方法不符（带 `allow` 头）。
- `500`：注册表投影/执行管线异常、技能定义校验抛错。

## 安全

- 每条路由先问 composition 的 `connection.requestRejection(req)`（Host/Origin 栅栏挡 DNS rebinding 与跨站调用，登录 cookie 挡未认证调用）；栅栏服务自身异常时**失败关闭**按 401 处理，绝不放行。
- 请求体 1MiB 上限（超限排空流后 400）；响应 `cache-control: no-store`。
- 全部执行发生在引擎进程内，与模型走同一 `ToolRuntime`：visibility、`UNKNOWN_TOOL`、PTC collapse、审批门（无 agent 时 fail-closed deny，不挂起）全部保留。

## 开发

```bash
npm run build   # tsc → lib/
npm test        # node --test（26 项，桩 ctx + 桩 HTTP 面）
```

## 边界

- 本插件只做「注册表投影 + 调用转发」，不持有任何工具/技能业务逻辑。
- 插件管理面（列举/装卸引擎侧插件）由 `HarnessBridge.capabilities` 覆盖（直读 node_modules），不做重复路由。
- `skills/load` 的 500（`validateDefinition` 抛错）同时是 `market-skill-source-S1` 补丁修复效果的自诊断探针。
