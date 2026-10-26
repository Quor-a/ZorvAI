# @dsh-android/dsh-android-zorvai-tools

ZorvAI 宿主能力**内置插件通道**（互控多通道之一）。2026-10-26 用户裁定：「mCP 是一种，
内置插件也是一种，多通道应该做出来」。

## 定位

ZorvAI（Android 应用，`com.ai.assistance.quro`）与 DeepSeek Harness 引擎之间的互控，
同一批 ZorvAI 工具走**两条通道**暴露给引擎里的模型：

| 通道 | harness 侧载体 | 协议面 | 工具名 | 生效方式 |
|---|---|---|---|---|
| ① MCP | `@deepseek-ai/dsh-mcp-client` | `/mcp` JSON-RPC 2.0 | `mcp__zorvai__*` | patch 启动期读，需重启 harness |
| ② 内置插件（本插件） | 本包（随快照装配） | `/tool/<工具名>` 纯 HTTP | `zorvai_*` | 端点清单落盘即生效 |

两条通道调用**同一批** ZorvAI 工具、同一个壳侧分发器（`QuroLocalMcpDispatcher`），
语义等价；一条失败换另一条重试即可。

## 零配置发现

壳侧 `HarnessBridge.enable` 在 `<dshHome>/zorvai-tools.json` 落一份端点清单：

```json
{
  "version": 1,
  "alias": "zorvai",
  "baseUrl": "http://127.0.0.1:<port>",
  "toolPath": "/tool",
  "updatedAt": "2026-10-26T04:06:00Z",
  "tools": [{ "name": "<裸工具名>", "description": "...", "parameters": { } }]
}
```

- **清单里的 `name` 是裸工具名**（与 MCP 通道同一份定义）；插件侧统一加 `zorvai_`
  前缀注册，execute 时按裸名 POST `<baseUrl><toolPath>/<name>`。
- **每次 execute 前重读清单**：壳侧重部署换随机端口（本地 server 每次启动都换）、
  或应用重启后 `startAll` 刷新端口，都无需重启引擎。
- **清单缺席 = 桥未启用**：只注册元工具 `zorvai_bridge`，引擎照常启动——
  宿主桥缺席不炸 boot（`apply` 与 `syncTools` 全程 try/catch）。

## 工具面

- `zorvai_<工具名>`（动态，`deferLoading: true`）：引擎渐进披露，初始可见集不膨胀；
  参数面由壳侧 JSON Schema 逐属性转写成 dsh-tools 属性表 DSL（根 `required` 数组
  → 逐属性 `required: true`；`enum`/`const` 只对基础类型；畸形属性跳过不炸 boot）。
  返回值恒为 `{ok, text}`（lossless JSON，无 undefined 成员）；execute 绝不抛错——
  网络失败/超时（65s）/非 JSON 响应一律转 `{ok:false,text}` 回执。
- `zorvai_bridge`（元工具，常驻可见）：报告桥连接状态、端点、已注册工具清单；
  `refresh=true` 强制重读清单并补注册新工具。桥未启用时它是在场唯一可见的
  ZorvAI 工具，供模型自诊断。

## 边界

本插件只做「调用转发 + 状态自诊断」，不持有任何 ZorvAI 业务逻辑；全部执行发生在
ZorvAI 应用进程内的本地服务器上（127.0.0.1，不出设备）。

## 登记链（改本插件必须一次改齐）

1. `scripts/plugin-dirs.json` —— `dirs` 列表；
2. `scripts/contract.json` —— `inserted` 列表（`id/pkg/repo`）；
3. `scripts/profile-web.cordis.patch.yml` —— `insert` 条目（随快照装配）。

`scripts/inject_and_gate.sh` 的 `DSH_ANDROID` 列表同步加入
`plugins/dsh-android-zorvai-tools`（该脚本不跑 npm build，直接用预构建 `lib/`）。

## 构建与自检

```powershell
cd plugins/dsh-android-zorvai-tools
npm install --no-audit --no-fund
npm run build     # tsc -p .（ES2022/NodeNext/strict，产物 lib/）
npm test          # node --test test/*.test.mjs（8 项）
```

`npm install` 会从 registry 拿带 `.d.ts` 的真类型（与云端 `npm ci` 同构）；
`@deepseek-ai/cordis` / `@deepseek-ai/dsh-tools` 是精确版本 peerDependencies
（boot 期 peer 校验读 package.json）。

门禁（单独驱动，全仓门禁入口见 `scripts/`）：

```powershell
node scripts/check-tool-output-schema.mjs --require plugins/dsh-android-zorvai-tools
node scripts/check-plugin-tests.mjs plugins/dsh-android-zorvai-tools
```

注意：动态工具经 `buildBridgedTool` helper 构造（不是 `defineTool({...})` 字面量），
这是 `check-tool-output-schema.mjs` 的「源码级 defineTool 名 vs 运行时注册名」
差集判据的要求——差集必须为 0，故元工具用字面量 `name: 'zorvai_bridge'`，
动态工具走 helper + 显式泛型实参双保险。
