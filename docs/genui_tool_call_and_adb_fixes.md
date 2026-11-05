# GenUI 工具调用 / 调用卡顿 / 无线调试 三处排查与修复

> 本轮对照上游 [Mika](https://github.com/wangzan101/Mika) 逐文件核对，结论全部来自代码而不是推测。
> 涉及模块：`genuiagent-sdk`（DSL + 交互）、`app/genui`（GenUI Agent）、`app/core/tools`（工具引擎）、
> `app/core/adb`（无线调试）。

---

## 一、GenUI 为什么「调不了工具」

### 结论：不是模型不肯调，是这条通路在 SDK 层根本不存在

逐个环节查完，缺的不是一处，是**整条链**：

| 环节 | 应有能力 | 实际情况 |
|---|---|---|
| `GenUIAction`（DSL） | `call_tool` 动作 | **没有**。只有 navigate / open_url / api / toast / scroll / send_message 等 |
| `ActionHost`（接口） | `callTool(name, args)` | **没有** |
| `ActionExecutor`（分发） | 把动作派到宿主工具 | **没有对应分支** |
| `AiActionHost`（宿主实现） | 真实执行工具 | **没有**，`executeAction` 只有 6 个硬编码 Intent |
| `AiActionHost.addListener` | 有人消费 `Custom`/`Emit` 事件 | **一处都没有**（`ChatScreen` 只注入了 3 个回调） |

也就是说，GenUI 画出来的 500+ 原生组件里，任何「要真做事」的按钮**最多只能**：

- `send_message` → 把一句话发回对话，让模型再绕一轮 LLM（慢、且可能被理解偏）；
- `open_url` / `open_app` / `execute` → 只能拉起系统 Intent；
- `custom` → dispatch 一个 `AiEvent.Custom`，**但没有人监听**，点了等于没点。

用户看到的现象就是「GenUI 用不了工具」。

### 修法（三端补齐）

1. **SDK 新增动作**
   ```json
   {"action":"call_tool","name":"web_search","arguments":{"q":"今天的汇率"},"showResult":true}
   ```
   `GenUIAction.CallTool(name, arguments, showResult)`，附 `argumentsJson` 便捷属性
   （宿主工具层吃的就是 JSON 文本，免得各宿主自己序列化写歪）。

2. **`ActionHost.callTool` + `supportsToolCall()`**
   给了**默认实现**（返回可判定的失败 JSON），保证任何第三方实现方不因接口升级而编译失败；
   返回 JSON 而不是抛异常 —— 抛异常会崩在 Compose 的点击回调（主线程）上，一个按钮点崩整个画布。

3. **`ActionExecutor` 分支**：`scope.launch { host.callTool(...) }`
   **必须 launch**：`executeOne` 跑在点击回调（主线程），工具最慢 60s，同步等就是 ANR。
   执行完发一条 `tool_result` 事件（带 tool / ok / result / show），界面可用它做局部刷新。

4. **`AiActionHost` 实现**：`onCallTool` 回调由 `ChatScreen` 注入
   `ChatViewModel.runToolFromUi(...)`。为 null 时返回明确 JSON，不静默。

5. **`ChatViewModel.runToolFromUi`**：与对话内的 `executeSingleTool` 同一条分派
   （本地 GenUI 工具 → 宿主工具），差别只在收尾：
   - **不写 conversationHistory**——界面按钮不该篡改对话上下文。否则用户点五次「刷新」，
     下一轮模型会看到五条莫名其妙的工具结果，直接答非所问；
   - 结果只进 `ToolCallRecord`（底部「工具调用」抽屉可查），行为可追溯；
   - 统一 `Dispatchers.IO` + 60s 超时（本地工具不受宿主超时保护，会永久挂住按钮）。

6. **`GenUiRules` 补规则**：告诉模型有 `call_tool`、什么时候用（要真做事）、
   什么时候**不要**用（需要它组织语言的，走 `send_message`）。

### 顺带修掉的一个高频 400

`GenUILlmClient.normalizeToolCallMessages` 原先**只剔除空 assistant 占位，完全不检查配对**。
而 function calling 的硬约束是：

- 每条 `tool` 消息必须紧跟带 `tool_calls` 的 assistant，`tool_call_id` 要对得上；
- 带 `tool_calls` 的 assistant 之后，**每个 id 都必须有一条 tool 回应**。

本项目有两条路会破坏它：

1. **用户点「停止」**：协程取消，`handleToolCallsResult` 已经把 `assistant[tool_calls]`
   写进历史，但 tool 结果还没写 → 孤儿 assistant[tool_calls] → **此后每一轮请求都 400**。
   界面表现：「一直转圈然后报错，发什么都不回」。
2. **持久化恢复**只认 `(role, content)`，`toolCallId` 丢失 → 孤儿 tool 消息。

现在统一在 `ChatHistory.normalizeToolMessages()`（纯逻辑、可单测）里兜住：
孤儿 tool 丢掉、缺失的回应补一条可判定的失败结果。
覆盖用例见 `GenUiToolPairingTest`（6 个）。

### 顺带挖出的一个「静默全面瘫痪」隐患

补 `call_tool` 时发现 `DslParser` 的显式注册表里，v1.9 那一批动作
（`open_app` / `play_media` / `stop_media` / `open_screen` / `open_html` / `execute` / `send_message`）
**一个都没注册**。往上查原因，找到了根：

```kotlin
data class Execute(val action: String, ...)   // ← 字段名与判别符同名
data class OpenApp(val packageName: String, val action: String, ...)

val json = Json { classDiscriminator = "action" }   // ← JSON 的类判别符就是 action
```

kotlinx-serialization 对「属性名 == 类判别符」直接抛：

> `IllegalArgumentException: Polymorphic serializer for class GenUIAction$Execute
> has property 'action' that conflicts with JSON class discriminator`

而这个异常发生在 **`DslParser` 的类初始化**（`SerializersModule { ... }` 是立即求值的），
也就是说：**只要把这个子类注册进去，DslParser 就再也初始化不了** ——
不是少一个动作，而是**所有 GenUI 界面统一变成「⚠️ UI 解析失败」卡片**，
编译期毫无提示。之前的人大概踩过一次，于是把整批都留空、绕过去了。

顺带说明为什么规则文档里写的是 `{"action":"execute","task":"dial"}`：
`task` 这个字段**在代码里根本不存在**（代码字段叫 `action`），本来就是错位的，
靠「不注册」掩盖着 —— 模型真写了 `execute`，解析必失败。

修法：
- `Execute.action` → `@SerialName("task") val action: String = ""`（与规则文档对齐，且给默认值：
  模型漏写 `task` 时只是动作无效，不会炸整份界面）；
- `OpenApp.action` → `@SerialName("intentAction")`；
- 其余 v1.9 动作全部补进注册表；
- 新增 `DslActionParseTest`（4 例）钉死「DslParser 能初始化 + 每个动作都能解析」——
  这类「一注册就全线瘫」的坑，只有测试能防住。

---

## 二、「AI 调用工具会卡」

查到三个层次的原因，逐个消掉：

### 2.1 GenUI 的工具集构造在主线程做同步 IO

`ChatViewModel.startTurn` 跑在 `viewModelScope.launch`（默认 **Main**），
其中 `brain.toolSpecs(config)` 会：

- 读技能库文件（`QuroSkillStore.designSkillsForGenUI`）
- 读记忆库 / 人格库
- 拼一遍 38K 字符的 system 提示词

→ 用户侧就是「点发送之后先卡一下才出字」。

**修**：工具集构造整段切 `Dispatchers.IO`；并把 system 的**真实**字符数传下去（见 2.2）。

### 2.2 工具预算按错误的 system 长度算 → 工具被裁到只剩一两个

`ZorvBrain.toolSpecs()` 原本自己调 `systemPrompt()`（`forced = null` 的基线版，38K），
而当轮实际 system 可能是 markdown/html 通道的短版本。**按 38K 扣「已用」→ 剩余额度被低估
→ 工具被裁到只剩一两个**，表现就是「这个通道下 AI 什么工具都调不了」。

**修**：

- `toolSpecs(cfg, systemChars = -1)`：调用方传真实长度；
- `systemPrompt` 加 2s TTL 缓存（同一轮里不再重建两遍），并提供 `invalidateSystemPrompt()`；
- 下发时打日志：`工具集下发：N 个 / X 字符（system Y 字符，通道 Z）` —— 出问题能直接归因。

### 2.3 一轮里多个串行工具，每个默认 60s，无限期占着这一轮

`QuroToolEngine.execute` 对**有副作用**的调用严格串行（正确，不能并发写）。
但模型一次给 6 个写操作，最坏就是 6 分钟，中间没有任何异常、进度条一直转。

**修**：整轮预算 `MAX_ROUND_BUDGET_MS = 180s`

- 单个调用不受限（交给它自己的 `execTimeoutMs`）；
- 有工具声明了 > 默认超时（如 harness 3600s）→ 按各工具预算之和，**不封顶**，避免误杀长任务；
- 超出的调用**不执行**，直接按超时回喂「请分步重试」，模型据此收敛。

### 2.4 无线调试的同步 mDNS（同一条链上的另一处卡）

见第三节「3.4」。

---

## 三、无线调试：对照 Mika 逐项比对

### 3.1 我们缺的「最后一条命」（最关键）

Mika 的 `AdbConnectPort` 有多路候选，我们只有两路：

| 候选来源 | Mika | 我们（修复前） | 说明 |
|---|---|---|---|
| 上次成功的端口 | ✅ | ✅ | 失效时被拒得快，排第一最划算 |
| 系统属性 `service.adb.tls.port` | ✅ | ❌ | 读不到是常态，但读到就是最快的 |
| mDNS `_adb-tls-connect` | ✅ | ✅ | **只在打开无线调试时登记一次** |
| **扫本机端口**（CNXN→STLS 核对） | ✅ | ❌ 代码移植了但**从未接入** | 见下 |
| 用户手填端口 | ✅ | ✅ | |

**为什么扫本机端口是必需的**：adbd 只在「打开无线调试」时向 mDNS 登记一次。
以下任一情况登记就没了，但 **adbd 进程和端口都还在**：

- mDNS 守护进程被系统回收 / 重启；
- 关过 Wi-Fi、切过数据卡、开关过飞行模式；
- 部分路由器拦组播。

只靠 mDNS 的实现会一直报「没找到连接端口」，而实际连接完全可用 ——
用户感受是「昨天还能连，今天就废了」。Mika 的 `LocalPortScan`
（`java.nio` 非阻塞、200 并发、每个 150ms、扫临时端口范围、对每个开着的端口发 CNXN
只有回 STLS 的才算 adbd）就是为这个存在的，**我们的仓库里早就有这个文件，只是没人调用它**。

### 3.2 「配对成功但连不上」—— TLS 握手被别的服务抢了

上游注释写得很清楚：`_adb-tls-connect` 端口上**第一次** TLS 握手有时会被同机另一个服务
先接住，那个服务对谁的证书都拒，表现为 `UNAUTHORIZED` / `TLS_FAILED`。
紧接着 300ms 后再连，才落到真正的 adbd 上。

我们的版本：见到 `UNAUTHORIZED` **立刻抛出**「要重新配对」→ 一个其实已配对好的设备被误判，
用户被要求反复重配。

**修**：对齐上游 —— 首次 TLS 拒绝时隔 300ms 原端口重试一次；
只有**试遍所有候选**且有端口明确拒过我们，才报 `UNAUTHORIZED`。
另外：已经有端口拒过我们就不再扫本机（adbd 已经被找到，扫出来也只会是它），省 10s。

### 3.3 一配对就崩（Android 12 及以下）

`Ed25519` 用了 `BigInteger.TWO` —— 这个字段是 **Android 13 (API 33)** 才加进
`java.math.BigInteger` 的。本项目 minSdk 26，在 Android 12 上 `Ed25519` 的 companion
一被类加载就抛 `NoSuchFieldError`，而类加载发生在**配对那一刻** → 表现为「一点配对整个 App 崩掉」，
崩溃栈指向初始化器，看着像密码学实现有问题。

**修**：`private val TWO = BigInteger.valueOf(2)`，并调整声明顺序（`P` / `L` 初始化要用它）。
上游 Mika 已经这么修了，我们这份是旧写法。

### 3.4 查一次状态要等十几秒

旧 `status()`：

- `findConnectPorts(3s)` + `findPairingPorts(3s)`；
- 两个都用 `runBlocking` 包 mDNS；
- `findPairingPorts` 平时**必然为空**（配对服务只在「使用配对码配对设备」弹窗开着的那几十秒存在）
  → 纯浪费 3s；
- `findPairingPorts` / `findConnectPorts` 默认超时是 **8s**，在工具调用路径上就是「问一句 ADB 状态，AI 卡十几秒」。

**修**：

- mDNS 超时 8000 → **3000**（系统 mDNS 通常 300ms 内回报，等一下只是给「同时公布多条」收齐）；
- `status()` 只探一次连接端口，**不再探配对端口**；
- 新增诊断行：上次成功端口 / 上次各来源候选数（缓存·属性·mDNS·扫描结果）/ 系统属性端口。

### 3.5 现在的状态摘要长这样

```
无线调试连接：✅ 已连接
配对状态：✅ 已配对（本次进程内）
连接服务端口：37xxx
上次成功端口：37xxx
上次找端口：缓存=1 属性=0 mDNS=1 扫描=未扫
密钥身份：quro@Pixel
```

---

## 四、虚拟屏看屏：看的是哪块屏

用户问「虚拟屏看屏是看虚拟屏还是什么」，逐行核对过，**三路数据源全部锁定在本 App 建的虚拟屏**：

| 数据 | 来源 | 会不会退回主屏 |
|---|---|---|
| 界面结构（ref/坐标/属性） | `QuroAccessibilityService.windowsOn(displayId)` | **不会**。`displayId < 0` 直接返回空；取不到窗口返回空快照，绝不 `rootInActiveWindow` |
| 图像 / OCR | `QuroAgentDisplay.latestFrameBitmap()`（VirtualDisplay 的 ImageReader 帧） | 不会 |
| 强兜底截图 | shell `screencap -d <displayId>` | 不会，显式带 displayId |

与 Mika 一致：Mika 的 `screenshotPng()` = `display.latestFramePng()`，
`readVisibleText()` = `display.latestFrameBitmap() → PhoneOcr.read()`，
`read()` = `ScreenReader.read(a11y.windowsOn(displayId))` —— 同样是虚拟屏。

现在 `bg_screen status` 会直接打印这一行，用户/AI 不必再猜：

```
读屏目标：虚拟屏 displayId=18（结构=无障碍 windowsOn 该屏 · 图像=VirtualDisplay 帧 ·
兜底=screencap -d 该屏）；**不是用户手机主屏**
```

---

## 五、验证

| 项 | 结果 |
|---|---|
| `:app:compileFullDebugKotlin` | BUILD SUCCESSFUL |
| `GenUiToolPairingTest`（新增 6 例） | 全绿 |
| 既有 genui / viewmodel 测试 | 全绿 |

---

## 六、还没做的（明确记录，不假装完成）

- **adb 协议栈有两份副本，必须合并**：
  `app/src/main/java/com/ai/assistance/quro/core/adb/mika/`（1472 行，`QuroAdbWireless` 实际用的这份）
  与 `mika-core/src/main/kotlin/com/ai/assistance/quro/core/mika/adb/`（1474 行，几乎逐行相同）。
  本轮把 app 那份对齐了上游（TLS 重试、Ed25519 常数），但 **mika-core 那份本来就是对的** ——
  也就是说，这轮差点只修了一半的运行时代码。两份并存意味着**下一次修复会再次只修一半**，
  这会持续制造「同一个 bug，一处修好一处复发」。建议后续统一到 `mika-core`，app 侧只留门面。
- `WirelessDebuggingAutoOn`（Android 11+ 无线调试自动开启 + 配对页自动填码）未移植：
  需要无障碍读配对码弹窗，属于另一条独立能力链。
- 系统属性候选在多数机型上读不到（SELinux），真正稳定的取值方式要用 Shizuku/shell
  执行 `getprop`，这需要把 `QuroAdbWireless` 的候选来源改成 suspend 并接 shell 服务。
- `AiActionHost.addListener` 目前仍**没有真实消费者**：`AiEvent.Custom` / `Emit`
  由界面发出后无人处理（`call_tool` 走的是 `onCallTool` 回调，不依赖它）。
  要么接上（让界面的自定义动作能回传），要么删掉这条死通路，不要留着让人误以为可用。

