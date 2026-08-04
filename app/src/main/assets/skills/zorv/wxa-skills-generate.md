---
name: wxa-skills-generate
description: 分析小程序项目源代码（含压缩/混淆），识别核心业务步骤，提取网络接口与 JSAPI 调用，生成符合 wx.modelContext 规范的技能分包（含原子接口 + 原子组件），并完成 app.json / project.config.json 配置集成。在以下场景触发：把小程序页面能力改造为小程序 AI 原子接口、生成 skills/ 分包代码、从源项目派生 MCP 工具、小程序 AI 的开发模式代码生成。仅负责静态生成，生成完成后必须交棒 wxa-skills-validate 做校验。
---

# wxa-skill-generate

从小程序源码生成符合 `wx.modelContext` 规范的技能分包（skills/）：**分析源码 → 识别业务 → 提取接口与 JSAPI → 设计原子接口 → 生成代码 → 集成配置 → 交棒校验**。

## 职责边界

- ✅ 本 skill 做：
  - 源码分析、原子接口设计、probe脚本获取真实响应、代码生成、`app.json` / `project.config.json` 集成
  - **生成过程内的自检**（必做）：产物存在性检查（`scripts/check-artifacts.mjs`）、字段忠实自检（§5.8）、`AUTH_MIGRATION.md §6` 鉴权自检、硬性约束 C 代码一致性自检
- ❌ 本 skill 不做：
  - **对生成产物的独立校验**：静态规则校验（V001~V019）、真机 execute 跑通、组件渲染核对——全部由 `wxa-skills-validate` 负责
  - **生成过程内的自检 ≠ 对产物的校验**。前者是本 skill 的组成部分，后者是交棒目标。不要因"校验交给 validate"就跳过生成阶段的自检
- 📦 交付：`skills/{skill-name}/`（含 `mcp.json`、`SKILL.md`、`index.js`、原子接口实现文件、工具模块；组件目录**仅在用户明确要求生成原子组件时**才有）+ 配置文件更新 + `.ai-mode-skills/` 源码忠实产物

## 依赖

- **可读的源码目录**（仅给 appid / URL / 截图 → 触发阻断）
- **开发者工具** probe 阶段需微信开发者工具 + `scripts/probe.mjs`（依赖的`miniprogram-automator` 装 skill 的 `scripts/`，禁止装源项目）。SOP 见 `references/RUNTIME_PROBE.md`（每个业务 api 默认全探，**禁止自行跳过**——环境不可用时必须停下问用户，不得自行回退静态）。

## 术语约定

- **原子接口**：对外暴露给小程序 AI 的可调用能力。约定路径 `skills/{skill}/apis/{name}.js`（validator 也兼容 `tools/services/` / `tools/`）
- **原子组件**：用于渲染原子接口返回数据的 GUI 卡片。**默认不生成**——仅当用户明确要求生成原子组件时才产出；否则原子接口只返回文本 + 数据 + handoff（进接力页，见 D.6）。生成时**强约束路径** `skills/{skill}/components/{name}/`（与 `mcp.json._meta.ui.componentPath` 严格相等）
- **压缩代码**：单行超 500 字符、变量名单字符的产物（含混淆）
- **probe**：阶段名（本 skill 阶段 3.7）。用 **automator** 在**源项目**上触发请求、捕获真实响应，产物落盘 `.ai-mode-skills/probe/`。属于**生成阶段**
- **automator**：工具名（`miniprogram-automator` npm 包）。probe 阶段用它连接开发者工具 WS 端口（9420）操控源项目抓真实请求响应。**仅在 generate 阶段 3.7 使用**
- **execute**：阶段名（`wxa-skills-validate` 阶段 4）。用 **CLI agent tool** 在**已集成的 skills 分包**上真实调用原子接口跑通业务，产物落盘 `cli-agent-run/`。属于**校验阶段**
- **CLI agent**：工具名（微信开发者工具 CLI 的 `agent` 子命令）。execute 阶段用它通过 agent 模式调用注册的原子接口。**仅在 validate 阶段 4 使用**

## 参考资料索引

| 文件 | 用途 | 加载时机 | 不加载条件 |
|------|------|---------|------------|
| `references/ANALYSIS_PATTERNS.md` | 业务流程识别、接口/JSAPI 搜索模式 | 阶段 2 / 3 扫描源码时 | 用户已明确全部能力且无需再扫页面结构时 |
| `references/JSAPI_WHITELIST.md` | wx API 白名单完整清单（接口侧 / 组件侧 / 不可迁移）；D 节只列高频项 | 阶段 1 / 3 / 5（D 节未覆盖目标 API 时必查） | 无（建议每次对照，不要凭印象） |
| `references/CODE_TEMPLATES.md` | 代码与配置模板（`index.js` / utils / apis / `mcp.json` / skill `SKILL.md` / `app.json`） | 阶段 5 / 6 | 纯改已有单行逻辑、不涉及模板结构时 |
| `references/COMPONENT_TEMPLATES.md` | 原子组件模板 | 阶段 5（**仅用户要求生成组件时**） | 用户未要求生成原子组件（默认） |
| `references/ATOMIC_COMPONENT_DESIGN.md` | 组件设计规范（尺寸 / 主题 / 边距 / 字体 / 布局） | 同上（强制前置，优先级最高） | 同上 |
| `references/ATOMIC_COMPONENT_CSS.md` | 组件 WXSS 实现规范 | 同上（写样式时） | 同上 |
| `references/STYLE_MIGRATION.md` | 源样式提取 + 字段映射工作流 | 同上（写 WXML/WXSS 前强制前置） | 同上 |
| `references/HALF_SCREEN.md` | 半屏页 API 与禁用清单 | 按需（源业务确有详情/补充信息语义） | 默认不生成半屏时 |
| `references/RUNTIME_PROBE.md` | probe SOP、plan/result 格式、失败兜底、合并规则；每个业务 api 默认全探 | 阶段 3.7 | 无（有业务 api 即要探；环境不可用走文档兜底路径） |
| `references/AUTH_MIGRATION.md` | auth-spec 契约、鉴权复刻、ensureXxx、§6 自检 | 阶段 1.2 / 3（interface-spec `authRefs`）/ 5.6 | 无（「无登录」≠「无鉴权」，通用 header/query 仍要读） |
| `references/SUBAGENT_PROTOCOL.md` | 大项目 subagent 分工、源码忠实铁律（interface-spec / plan）、回传纪律、校验 subagent 隔离 | 大项目：阶段 1（能力索引）/ 1.2（鉴权 subagent）/ 3（§2.3–§2.4）/ 收尾校验（§2.6） | 小项目（页面 ≤~30 且无多分包） |

---

## 硬性约束

### A. 独立分包禁止项（必须改写）

| 禁止项 | 正确做法 |
|--------|---------|
| `getApp()` | 分包内自行管理状态（模块变量 / `wx.storage`） |
| `require('../../xxx')` 引用主包/兄弟分包 / `import ... from '@/'` | 把依赖**完整拷贝**到当前分包：单 skill 私有放 `{skill}/utils/`，多 skill 复用放 `skills/_shared/` |
| 依赖主包 `wx.cloud.init()` | `utils/util.js` 中 `ensureCloudInit()` 自行初始化 |
| 依赖主包 `app.js` 初始化 storage | `utils/util.js` 中 `ensureStorageInit()` 自行初始化 |
| 从 `getApp().globalData` 读配置 | `baseUrl` / `env` 硬编码在分包 `utils/util.js` |
| 依赖主包登录态 | 每次执行接口前 `ensureLogin()` 主动走一遍登录流程 |
| 使用主包注册的全局组件 | 在分包 JSON 中重新声明 `usingComponents` |

### B. 直接终止生成的阻断规则

出现以下任一情况，立即终止生成并告知用户：

| 阻断情况 | 检测时机 | 告知文案 |
|---------|---------|---------|
| 依赖小程序插件（`plugin://` / `requirePlugin` / `app.json` 的 `plugins`） | 阶段 1/3 | "该功能依赖小程序插件，当前暂不支持自动生成，需手动接入" |
| 用户声明的能力在源码中找不到任何对应接口或页面 | 阶段 3 | "未能在源码中定位到 `<能力名>`，无法生成，请确认能力名称或补充源码" |
| 未提供可读的源码目录（只给 appid / URL / 截图） | 阶段 1 前 | "请提供小程序完整源码目录，当前无法基于非源码资产生成" |
| 所有候选实现都依赖非白名单 JSAPI 且无替代方案 | 阶段 3 | "该能力依赖非白名单 JSAPI（如 `<api>`），无法自动生成" |
| `app.json` 缺 `"lazyCodeLoading": "requiredComponents"` 配置 | 阶段 1 | "项目 `app.json` 顶层缺少 `\"lazyCodeLoading\": \"requiredComponents\"`，否则独立分包内的原子接口被小程序 AI 路由调用时无法正确加载执行。请在 `app.json` 顶层添加该字段后重新触发生成" |
| 静态分析 + probe 均无法获取真实响应结构 | 阶段 3 | "接口 `<api>` 无法通过静态分析或运行时探测获取真实接口信息，无法生成" |

### C. 代码一致性（不增不减 + 封装层强制复用）

本 skill 的唯一目标：将参考源码迁移/转换为目标格式，保持逻辑、结构、行为与参考源码**完全一致**。

迁移 = 忠实搬移，不是重写。源码中存在的每一项逻辑都必须保留到产物中，源码中不存在的不得添加。

**封装层强制复用**：`utils/request.js` 是网络请求唯一入口，所有 `apis/*.js` **必须通过它发请求**，禁止 API 文件中直接调 `wx.request` 或自行拼 URL/header/query——否则鉴权参数全部丢失导致 403/空数据。

#### C.1 禁止添加（源码中不存在的逻辑）

- 添加参考源码中不存在的错误处理（try/catch、if 判断等）
- 添加参考源码中不存在的默认值或兜底逻辑
- 添加参考源码中不存在的输入校验
- 「优化」、「修正」、「补全」参考源码中看起来不完整的逻辑
- 任何形式的「我觉得这里应该加上...」

如果参考源码本身没有处理某种情况，输出也不应该处理。如果参考源码某处看起来像是 bug 或缺失，原样保留，不要修正。

**唯一例外——响应字段类型安全**：对 API 响应中的数组字段调用 `.map()`/`.filter()`/`.some()`/`.every()` 前，**必须加类型保护**（`(x || []).method()` 或 `Array.isArray(x) ? x.method() : []`）。分包环境下 API 响应可能因鉴权不完整等原因返回异常结构（字段为 `null`/对象而非数组），不加保护会抛 `TypeError` 崩溃。这不是"添加源码中不存在的逻辑"，而是保证源码逻辑在分包环境下不崩溃的必要防御。

#### C.2 禁止丢弃（源码中存在的逻辑）

> **核心原则：你无权判断"这个参数是否必要"。** 后端校验规则对你是黑盒，源码 request 封装中每一个 header/query 都必须保留。

- **鉴权参数完整保留**：见 `references/AUTH_MIGRATION.md`；生成后按 AUTH_MIGRATION §6 自检
- **依赖完整内联**：阶段 3.2 追踪到的依赖，阶段 5 完整拷贝到分包

### D. wx API 白名单（每次生成必须对照）

> 阶段 1 鉴权扫描、阶段 3 JSAPI 提取、阶段 5 代码生成时**必须对照白名单**。源码用到清单之外的 JSAPI → 按"不可迁移 JSAPI"处理。
>
> **完整清单**（接口侧 / 组件侧 / 不可迁移）见 **`references/JSAPI_WHITELIST.md`**。下文 D.1 / D.2 / D.6 仅列高频条目，覆盖业务时必查 reference 完整列表，不要凭印象。

#### D.1 接口侧白名单

> "接口侧"指通过 `wx.modelContext.registerAPI()` 注册的处理函数及其依赖的纯 JS 模块——常规放在 `<skill>/apis/`（也可放 `tools/services/` / `tools/`，validator 会按这三个候选目录解析），引用的工具模块目录名（如 `utils/` / `services/` / `helpers/` / 自定义名）不限。**作用域以"是否在原子接口处理函数链路上"判定，不以目录名判定**。

| 分类 | 高频接口 |
|------|---------|
| 小程序 AI | `wx.modelContext.registerAPI`、`wx.modelContext.createSkill`（返回 `{ use, registerAPI }`）、`wx.modelContext.expireAllCards`、`wx.modelContext.getSessionId`（获取会话 ID） |
| 登录 | `wx.login`、`wx.checkSession` |
| 网络 | `wx.request`、网络状态 `getNetworkType` / `on*NetworkStatusChange` |
| 云开发 | `wx.cloud.init` / `callFunction` / `database` |
| 位置 | `wx.getLocation` / `getFuzzyLocation`（**不含** `chooseLocation` / `openLocation`） |
| 系统 | `wx.getDeviceInfo`、`wx.getAppBaseInfo`、`wx.getWindowInfo` |
| 数据缓存 | `wx.{get,set,remove,clear,batchGet,batchSet}Storage`（含 `Sync`）、`wx.getStorageInfo` |
| 上传下载 | `wx.uploadFile`、`wx.downloadFile` |
| 订阅消息 | `wx.requestSubscribeMessage` |
| 授权设置 | `wx.authorize`、`wx.getSetting`（**不含** `openSetting`） |
| 图片 | `wx.getImageInfo` |
| 手机号 | `wx.getPhoneNumber`、`wx.getRealtimePhoneNumber` |
| 账号 | `wx.getAccountInfoSync`（接口与组件均可调） |

> 支付类、系统选择器/采集（`choose*` / `scanCode` / `saveImageToPhotosAlbum`）、主动打开原生页/面板（`openLocation` / `makePhoneCall` / `openDocument` / `shareAppMessage` / `openSetting` / `openPrivacyContract`）**不在接口侧**——见 D.2 / `references/JSAPI_WHITELIST.md §2.1`（动态组件）。其他场景（人脸核身、微信运动、加密、WiFi、蓝牙/BLE、WebSocket、TCP/UDP、mDNS、传感器等）查 **`references/JSAPI_WHITELIST.md §1`** 完整表。

源码用到清单之外的 JSAPI → 按 D.9 判定规则处理。**阶段 1/3/5 每次对照白名单，不要凭印象**。

#### D.2 组件侧白名单

> "组件侧"指原子组件 `Component({})` 内的代码及其引用的纯 JS 模块。组件目录路径**强约束**为 `<skill>/components/<name>/index.{js,json,wxml,wxss}`（与 `mcp.json` 中接口的 `_meta.ui.componentPath` 严格相等）。

**完整清单见 `references/JSAPI_WHITELIST.md §2`**（含小程序 AI getContext/getViewContext/expireAllCards/expirePreviousCards、界面 previewMedia/showToast、系统、缓存、文件、账号、位置 openLocation、设备、设置、分享、振动、隐私、地图 MapContext 全方法等）。

**组件侧禁用**：`wx.cloud.*` / 位置 / 登录 / 支付 / 其它任何接口侧业务接口。组件只能收数据（接口返回的 `structuredContent` / `_meta`）、做预览、读系统信息、读写本地缓存、读账号信息、操作 `MapContext`、发声明过能力的网络请求。组件与接口处于不同 JS 上下文，**全局变量不共享**。在 `methods` / tap handler / 异步回调里主动调 `sendFollowUpMessage` / `getDimensions` 时必须现取 `wx.modelContext.getContext(this)` / `getViewContext(this)`，不要通过 `this._modelCtx` 等缓存引用调（详见 `references/COMPONENT_TEMPLATES.md`）。

#### D.3 组件配置（原子组件按需生成 + 网络能力）

**默认不生成原子组件**。原子接口只返回文本 + `structuredContent` + `handoff`（进接力页，见 D.6）。**仅当用户明确要求生成原子组件（GUI 卡片）时**才生成：对应接口声明 `_meta.ui.componentPath`，并在 `mcp.json` 顶层 `components[]` 声明一条记录，**`path` 必须与该接口 `_meta.ui.componentPath` 字符串完全相等**（含末尾 `/index`，严格相等比对）。网络能力（`permissions.scope.dynamic`）按需声明。

```json
{
  "components": [
    {
      "path": "components/order-list/index"
    },
    {
      "path": "components/weather-card/index",
      "permissions": { "scope.dynamic": { "desc": "声明使用场景" } }
    }
  ]
}
```

运行时若需要给关联页面附加 query 参数，在组件 `created` 里现取 `viewCtx.setRelatedPage({ query })`，示例代码见 `references/CODE_TEMPLATES.md` 第四节。该约束被静态规则强制校验。

#### D.4 组件过期态声明（按需，非强制）

默认不生成。**仅当**源业务上存在"卡片到某时刻作废、不应再被点"语义（成交、关店、活动结束、超时）时，在 `components[]` 记录上加 `expirable: true` + 业务化 `expiredText`。声明与调用必须配对。

触发 API 二选一（**不要同时调**）、精细过滤（`componentPaths` / `match: 'latest'`）、代码示例详见 **`references/COMPONENT_TEMPLATES.md` "卡片过期"节**。

#### D.5 半屏页面（按需，非强制，**默认不生成**）

仅当源业务确有"详情 / 用户补充信息"语义时挂上。入口仅在原子组件 `methods` 内（`getViewContext(this).openDetailPage`，**原子接口无 `this` 不可调**）。半屏内上行 `sendFollowUpMessage`、禁用清单（跳出类 / 页面路由 / 聊天工具 / 广告 / 导航组件等）、场景值、关闭按钮适配详见 **`references/HALF_SCREEN.md`**。

#### D.6 handoff 接力页（进小程序的主要方式）

进小程序统一走 **handoff**。默认流程：原子接口返回**文本 + 小程序卡片**，用户点卡片后由平台 handoff 进入小程序内的**接力业务页**继续操作。

**何时必须配**：若某原子接口执行完会**停下等用户确认**（展示小程序卡片、等用户点击进小程序），必须为它配置 `pagePath`，否则用户无法进入业务页。纯数据、无停顿接续的接口可不配。

四项适配（详见 `references/CODE_TEMPLATES.md` "handoff 接力页" 节）：

1. **`mcp.json`**：在该接口 `apis[]._meta.ui` 加 `pagePath`（接力页 path，**不含 query**；与 `componentPath` 同级，`componentPath` 仅在生成组件时才有）。
2. **原子接口返回值**：顶层（与 `content` / `structuredContent` 同级）增加 `handoff`，**兼容两种形态**：
   - **对象（立即模式，更快）**：`handoff: { query, payload?, card? }`——模型无需筛选数据时直接返回对象，平台即时生成 handoff，链路更短、更快。
   - **函数（延迟模式）**：`handoff: ({ result }) => ({ query, payload?, card? })`——需要用**模型修改后的 result** 时返回函数，入参对象的 `result` 即模型修改后的完整 result。
   字段：`query` 为 **string**（页面 query 字符串，如 `'drugId=xxx'`，原样注入 `onLoad(query)` 与 `wx.onAgentHandoff` 回调，宜短）；`payload` 可选（接力页首屏加速数据）；`card` 可选（卡片展示信息，如 `{ title }`）。
3. **`app.js`**：`onLaunch` 内注册 `wx.onAgentHandoff(cb)`（须早于 handoff 触发的 `onBeforeAppRoute`），把 `{ path, query, payload }` 按 `pageId` 暂存。
4. **接力业务页**：`onLoad(query)` 中 `query` 为 string（与 `handoff.query` 一致），按页面原有方式解析 query 字符串；若 `wx.onAgentHandoff` 投递了 `payload` 则先 `setData` 加速首屏。

平台代做（无需自己实现路由）：按 `pagePath` 打开目标页 → 把 `handoff.query`（string）原样注入 `onLoad(query)` 与 `wx.onAgentHandoff` 回调 → 通过 `wx.onAgentHandoff` 按 `pageId` 投递 `payload`。

> **禁用**：`wx.openAgent` / `wx.navigateBackAgent` 当前基础库侧未打通，调用会失败——接力页内**不要**依赖"打开 Agent / 返回 Agent 对话"，后续流程由业务页自行完成。

#### D.7 不可迁移 JSAPI（接口与组件均禁用，高频示例；完整清单见 `references/JSAPI_WHITELIST.md §3`）

| 不可用 API | 替代策略 |
|-----------|---------|
| `wx.showToast` / `showModal` / `showLoading` / `showActionSheet` 等 UI 反馈 | 结果通过 `content` / `structuredContent` 回馈，小程序 AI 无 loading/modal 概念 |
| `wx.navigateTo` / `redirectTo` / `switchTab` / `reLaunch` / `navigateBack` | 删除，小程序 AI 不在页面栈内导航 |
| `wx.chooseImage` / `wx.chooseVideo` / `wx.previewImage`（老接口） | 改用 `wx.chooseMedia`（接口侧）/ `wx.previewMedia`（组件侧） |
| `wx.setClipboardData` / `getClipboardData` | 跳过 |
| `wx.getUserInfo` / `getUserProfile` | 改用登录 + 后端资料接口 |
| `wx.createSelectorQuery` / `createCanvasContext` | 接口侧不适用；组件侧仅允许通过 `this.createSelectorQuery().select('#mapId').context()` 获取 `MapContext`（详见 D.2） |
| `wx.pageScrollTo` / `wx.createAnimation` | 容器不支持滚动；动画用 CSS `transition/animation`（限 opacity/transform） |

> 其它老接口、Taro 特有不可迁移项（Hook、Pinia/Vuex、Vue setup 等）见 `references/JSAPI_WHITELIST.md §3`。

#### D.8 `button` 的 `open-type` 改写

组件内 `button` 禁用 `open-type`（`share` / `getPhoneNumber` / `getRealtimePhoneNumber`）→ 去掉 `open-type`，改 `bindtap`，在 tap handler 内调对应白名单 JSAPI（`wx.shareAppMessage` / `wx.getPhoneNumber` / `wx.getRealtimePhoneNumber`）。

#### D.9 判定规则

1. 能力**仅能**通过不可迁移 JSAPI 实现（如"扫码核验"且源码无网络 API 替代）→ 触发阻断规则 B
2. 能力核心逻辑可用网络请求实现 → 生成纯网络请求版本，丢掉不可迁移的 JSAPI 调用
3. 老接口有白名单内新接口替代（`chooseImage` → `chooseMedia`、`previewImage` → `previewMedia`）→ 自动替换

### E. 原子组件约束

- 仅支持 `tap` 事件
- **支持的内置组件**：`view`（含 `hover-class`）/ `text`（不含 `user-select`）/ `image`（仅网络地址）/ `map` / `button`（**不含 `open-type`**）/ `canvas` / `scroll-view`（**仅横向滚动 `scroll-x`，禁纵向 `scroll-y`**）
- **不支持的内置组件**：`swiper` / `swiper-item` / `input` / `textarea` / `picker` / `picker-view` / `checkbox` / `radio` / `form` / `label` / `slider` / `switch` / `editor` / `rich-text` / `icon` / `progress` / `navigator` / `web-view` / `movable-area` / `movable-view` / `root-portal` / `match-media` 等
- `button` 用 `open-type` → 按 D.8 改写为 `bindtap` + 白名单 JSAPI
- 渲染容器：宽度随屏幕，宽高比 4:1（最小高） ~ 1:1（最大高），**超出裁剪、不支持纵向滚动**（横向超长内容用 `<scroll-view scroll-x="true">` 包裹）
- 不支持打开小程序接口；不可声明为虚拟组件；组件与接口处于不同 JS 上下文，全局变量不共享
- **每个可交互元素必须绑 `bindtap`**，tap handler 上行 `content` 数组（① 单 `text` 或 ② `text` + `api/call` 组合，推荐 ②）。详见阶段 5.3 + `references/COMPONENT_TEMPLATES.md` "上行消息"节
- **数据通道禁止 `properties` / `observer` / `dataSource`**：必须通过 `NotificationType.Result` 取 `structuredContent`（详见 `references/COMPONENT_TEMPLATES.md` 与阶段 5.4）
- **WXML 表达式限制**：`{{ }}` 中不支持数组下标（如 `[0]`）、函数调用（如 `.slice()`）、模板字符串等复杂 JS。需要计算的字段一律在 `index.js` 归一化阶段预处理好再 `setData`

---

## 执行清单（复制后勾选）

> **产物检查脚本**：每个阶段完成后运行 `node scripts/check-artifacts.mjs <project-path> --stage <N>` 做确定性检查——**只验文件是否存在 + JSON 能解析 + 目录结构正确，不校验文件内容**。有缺失 → 脚本退出码 1 并列出缺失项 + 该回哪个阶段补。**禁止跳过此检查直接进入下一阶段**。不传 `--stage` 时检查全部已应完成的阶段。

```
阶段 0 — 业务需求澄清（强制前置）
- [ ] 判定用户场景是否明确（两项判定）
- [ ] 不明确 → 最小扫描 + 引导澄清 + 等待确认
- [ ] 确认是否生成原子组件（用户未明确要求 → 默认不生成，只做原子接口 + handoff）
- [ ] 产出"目标业务场景 + 期望原子能力"清单

阶段 1 — 项目扫描
- [ ] **首检 `lazyCodeLoading`**（缺则阻断 B）
- [ ] 提取 app.json / app.js / project.config.json 关键字段
- [ ] 产出云开发 / 插件 / storage 初始化清单
- [ ] 产出 **auth-spec** 两份产物 + 鉴权核对 PASS（见 `AUTH_MIGRATION.md` §2/§3；大项目走 `SUBAGENT_PROTOCOL.md` §2.1/§2.2）
- [ ] 大项目：产出 `capability-index.json`（见 `SUBAGENT_PROTOCOL.md` §一）
- [ ] `node scripts/check-artifacts.mjs <project-path> --stage 1`

阶段 2 — 业务功能识别（用户已明确时跳过）
- [ ] 产出结构化功能清单 JSON
- [ ] 用户二次确认

阶段 3 — 接口与 JSAPI 提取 + 可行性校验
- [ ] 逐能力产 `interface-spec.<cap>.md`（大项目 `SUBAGENT_PROTOCOL.md` §2.3 subagent；小项目见阶段 3.2）
- [ ] 产 `probe/plan.json` → 执行 probe（每次落盘 `probe/<run-id>.json`）→ 合并 **一份** `merged-result.json`（见 `RUNTIME_PROBE.md`；大项目 plan 由 `SUBAGENT_PROTOCOL.md` §2.4 subagent）
- [ ] `node scripts/check-artifacts.mjs <project-path> --stage 3`

阶段 4 — 原子接口设计
- [ ] 原子接口清单（含 name / description / inputSchema / outputSchema；进小程序的接口配 _meta.ui.pagePath + 返回 handoff；_meta.ui.componentPath 仅当用户要求生成原子组件时才有）
- [ ] API 依赖图
- [ ] storage key 清单

阶段 5 — 代码生成
- [ ] 进小程序的接口已配 _meta.ui.pagePath + 返回值顶层 handoff（见 D.6）
- [ ] `utils/request.js` 按 `AUTH_MIGRATION.md` §5/§6 生成并自检
- [ ] 源码忠实度：interface-spec 符合 `SUBAGENT_PROTOCOL.md` 源码忠实铁律；`ensureXxx`/`await` 见 `AUTH_MIGRATION.md` §4/§6
- [ ] 字段忠实自检（每写完一个 apis/<name>.js 立即做，见 5.8）：其 structuredContent 字段集与 merged-result 该 api 的 probe 真实响应一致
- [ ] （仅当用户要求生成原子组件时）每个原子组件符合 `ATOMIC_COMPONENT_DESIGN.md` 并走完 `STYLE_MIGRATION.md` 7 步；可交互元素绑 `bindtap`，tap handler 优先上行 `content` 组合（text 简短中文、`name` 在 mcp.json 中存在、`arguments` 对齐 inputSchema），无法映射时退回单 `text`
- [ ] `skills/{skill-name}/` 目录完整（mcp.json / SKILL.md / index.js / apis/* / utils/*；仅生成组件时含 components/*）
- [ ] SKILL.md 按 `CODE_TEMPLATES.md` 第五节 5 节结构写完（路由说明，非接口手册）
- [ ] `node scripts/check-artifacts.mjs <project-path> --stage 5`

阶段 6 — 配置集成
- [ ] app.json 加 agent.skills（每项含 `{ name, description, path }`）+ subPackages
- [ ] project.config.json 的 packOptions.include 加 skills
- [ ] `node scripts/check-artifacts.mjs <project-path> --stage 6`

收尾 — 交棒给 wxa-skills-validate
- [ ] 明确告知用户："请使用 wxa-skills-validate 做校验"
- [ ] 提示 skills 路径与 project-path
```

---

## 跨阶段跳转规则

| 场景 | 流向 |
|------|------|
| 正常主干 | 0 → 1 → (2) → 3 → 4 → 5 → 6 → 交棒 `wxa-skills-validate` |
| 用户已明确能力 | 跳过 2，0 → 1 → 3 |
| 阶段 3 | 3.6 → 3.7（probe，见 `RUNTIME_PROBE.md`）→ 4 |
| probe 多轮仍失败 | 标 `verified:false` / `[ai-mode:UNVERIFIED]`，交棒时声明（见 `RUNTIME_PROBE.md` §四） |
| validator 反馈 T1~T6 / A/B/C/D 类错误 | 回本 skill 阶段 5 改代码 |
| validator 反馈 T7/T8（接口划分 / 依赖链路） | 回本 skill 阶段 4 重设计 |
| 任一阶段触发阻断规则 B | 立即终止，输出阻断原因 |

**核心原则**：

1. 业务场景不明确时，**必须先澄清后生成**，严禁跳过阶段 0
2. 每个阶段必须完整产出"产出物清单"中的全部项才能跳转到下一阶段

### 增量与重入

工作区已存在 `skills/` 产物时：

| 用户意图 | 入口阶段 | 说明 |
|---------|---------|------|
| 新增一个原子能力 | 阶段 0（轻量）→ 阶段 3 | 先澄清新能力，扫描接口并入增量清单 |
| 修改已有原子接口的行为 | 阶段 4 | 更新接口清单 → 5 → 6 → 交棒 |
| 修改组件样式/模板 | 阶段 5 | 仅改 `components/{x}/`，重新走 5 → 6 → 交棒 |
| validator T1~T6 / A/B/C/D 反馈 | 阶段 5 | 按报告定位文件，改完交棒 |
| validator T7/T8 反馈 | 阶段 4 | 重设计后 5 → 6 → 交棒 |
| 仅做验证 | **不进入本 skill**，直接给 `wxa-skills-validate` | — |

> 重入时已生成且未触及的文件保持不变，只更新受影响的文件。

---

## 阶段 0 — 业务需求澄清（强制前置）

**契约**：

| 项 | 内容 |
|---|------|
| 入口条件 | 用户发起生成请求（任何请求都必须从本阶段开始） |
| 产出物 | 判定结果 + 必要时的澄清清单 |
| 下一步 | "明确"或澄清确认完毕 → 阶段 1 |

**判定规则**（必须同时满足 2 项才算"明确"）：

| # | 判定项 | 示例 |
|---|--------|------|
| ① | 指明**具体业务名词** | "商品检索""订单管理""地址管理""签到"；而不是"核心功能""主要能力" |
| ② | 可推断**至少 2-3 个原子能力的粒度** | "检索商品 + 展示列表 + 查看详情"；而不是"业务相关" |

任一不满足 → 进入下方澄清流程。

### 不明确时的引导流程

1. **最小扫描**：只读 `app.json` 的 `tabBar.list`、`pages`（一级路径）、`subPackages.root`。**禁止**读 JS/WXML/WXSS，禁止做依赖分析。
2. **归纳候选**：基于路径关键词（见 `references/ANALYSIS_PATTERNS.md` 页面功能识别表）归纳 3~6 个候选场景。
3. **向用户提问**（一次问完，别反复打断）：
   - 希望把哪些业务场景做成小程序 AI 的 SKILL？
   - 每个场景希望暴露给小程序 AI 的原子能力大致是什么？
   - 是否涉及登录态、支付、位置、云开发等敏感能力？
   - 是否需要生成**原子组件（GUI 卡片）**？**默认不生成**——只做原子接口 + handoff（点小程序卡片进接力页）；仅当你明确需要对话内卡片式 GUI 时才生成。用户未提及即按"不生成"处理。
4. **等用户回复后**才能进入阶段 1。严禁在用户确认前扫描源码或生成代码。

**澄清输出清单模板**：

```
目标业务场景：
  - 场景 A：<名称> → 期望原子能力：<能力 1>、<能力 2>
  - 场景 B：<名称> → 期望原子能力：<能力 3>

技术约束：
  - 是否涉及支付/登录/位置：是/否
  - 是否使用云开发：待阶段 1 扫描确认
  - 是否生成原子组件（GUI 卡片）：是/否（用户未明确 → 默认否，只生成原子接口 + handoff）
```

---

## 阶段 1 — 项目扫描

### 项目结构速览 + 读取策略分流

读 `app.json` 映射阶段 0 目标到页面/分包。按规模分流：

| 规模 | 判定 | 策略 |
|------|------|------|
| **小项目** | 页面 ≤ ~30、无多分包 | 主 agent 直接 `read`/`grep` |
| **大项目** | 页面 > ~30 / 多分包 / 单文件巨大 | 按 `references/SUBAGENT_PROTOCOL.md` 执行（能力索引 → 五类 subagent） |

**契约**：

| 项 | 内容 |
|---|------|
| 入口条件 | 阶段 0 产出明确 |
| 产出物 | 配置字段、云开发/插件、**auth-spec**（核对 PASS）、storage 清单；大项目加 `capability-index.json` |
| 下一步 | 已明确能力 → 阶段 3；否则 → 阶段 2 |
| 阻断条件 | 缺 `lazyCodeLoading` / 无源码 / 依赖插件 |
| 产物校验 | 进入阶段 2/3 前**必须确认以下文件已落盘**：`.ai-mode-skills/auth-spec.md` + `.ai-mode-skills/auth-spec.snippets.txt`（鉴权核对 PASS）；大项目还需 `.ai-mode-skills/capability-index.json`。缺任一 → 回本阶段补落盘 |

### 1.1 配置扫描

读 `app.json` / `app.js` / `project.config.json`，提取 `pages` / `subPackages` / `tabBar` / 已有 `agent` / `appid` / `packOptions`；扫云开发（`wx.cloud` 调用 / `cloudfunctions/` 目录）与云环境 ID（`wx.cloud.init({ env })`）。**`lazyCodeLoading` 必检**：缺 `"lazyCodeLoading": "requiredComponents"` → 阻断规则 B（不要"代为补全"）。云开发项目同时扫 `cloudfunctionRoot/<fn>/index.js` 的入参/返回结构。

### 1.2 鉴权逻辑扫描（必做）

按 `references/AUTH_MIGRATION.md` §2/§3 落盘 `<源项目>/.ai-mode-skills/auth-spec.md` + `auth-spec.snippets.txt`（事实结构化 + 代码 verbatim 拷贝，禁止把签名写成步骤数组）。

- **小项目**：主 agent 读 `app.js`/request 封装/登录文件，自行填产物
- **任意规模**：鉴权核对 subagent 回比源码 PASS 后才进阶段 3（大项目鉴权提取见 `SUBAGENT_PROTOCOL.md` §2.1/§2.2）

> auth-spec 经核对 PASS 后才是"可信事实"。后续阶段 3 / 5.6 直接引用，不重读同一鉴权函数。

### 1.3 主包 storage 初始化扫描（必做）

扫 `app.js` 与主包 `.js` 中的 `wx.{set,get,clear}Storage*`，提取 `key` / `defaultValue` / `initCondition` / `sourceFile`。迁移：① `setStorageSync` 初始化值 → 分包 `ensureStorageInit()` 重建；② `getApp().globalData` 运行时缓存 → 模块级变量或按需写 storage；③ `onLaunch` 异步获取后写 storage → 分包首次调用时自行重发请求并缓存。形成 **storage 初始化清单**（与阶段 4 内部"接口间数据传递的 storage key 清单"不是同一张表）。

### 1.4 压缩代码处理

识别：单行 >500 字符 / 单双字符变量名 / 缺注释空行。处理顺序：① 优先问用户要未压缩源码；② 否则尝试 prettier 格式化后再提取；③ 格式化后关键字段仍全是 `a.b.c.d` → 阻断规则 B。**禁止盲目猜变量名**——猜出来的代码会在 validator 大量失败。

### 1.5 插件检测

扫 `app.json` 的 `plugins` 字段、页面/组件 JSON 的 `usingComponents` 中的 `plugin://` 引用。目标能力依赖插件 → 阻断规则 B。

---

## 阶段 2 — 业务功能识别（用户已明确时跳过）

**契约**：

| 项 | 内容 |
|---|------|
| 入口条件 | 阶段 1 完成 **且** 用户仅给源码未明确原子能力 |
| 产出物 | 结构化功能清单（JSON）**且已获得用户二次确认** |
| 下一步 | 用户确认 → 阶段 3 |
| 阻断条件 | 用户始终无法确认 → 停留本阶段 |
| 产物校验 | 进入阶段 3 前**必须确认** `.ai-mode-skills/auth-spec.md` + `auth-spec.snippets.txt` 已落盘且核对 PASS（阶段 1 产物）。缺 → 回阶段 1.2 补 |

**动作**：

1. 针对阶段 0 选定的候选场景对应页面，按 `references/ANALYSIS_PATTERNS.md` 的模式分析页面用途、交互事件、数据流向
2. 从用户视角识别功能点（每个功能 = 一个原子接口）
3. 分析数据依赖（A 的返回值被 B 使用）

**mcp.json 只注册用户直接使用的原子能力（硬约束）**：

以下接口**不收集为原子能力**、不写 mcp.json、不生成 `apis/*.js`：

1. **鉴权/登录类**：`wx.login` / `wx.checkSession` / `ensureLogin()` / token 刷新 / 获取 sessionId 等——这些是**基础设施**，由 `utils/` 内 `ensureLogin()` 等函数在每次接口调用前自动执行，不是用户直接使用的能力
2. **系统级/生命周期接口**：app 初始化（`onLaunch` / `onShow`）、storage 初始化、配置获取、全局状态读取等——这些是框架/运行时行为，不是用户主动触发的业务功能
3. **内部辅助接口**：被其他原子接口调用但不直接面向用户的 API（如"获取子列表"被"搜索"内部调用但用户不直接用）——只收集用户视角的功能入口
4. **工具函数**：`utils/` 中的纯函数（格式化、计算、URL 拼接等）——这些是代码组织，不是独立能力
5. **敏感接口**（不可逆副作用）：注销/删除/解绑等——见下方专门规则
6. **管理端接口**：管理端操作（如"导出数据"、"管理成员"、"删除商品"、"发布公告"等）——这些是管理端操作，不是用户直接使用的能力

**判定标准**：问自己"用户会对小程序 AI 说'帮我做 X'吗？"——会 → X 是原子能力；"系统自动做的"或"另一个接口内部调的" → 不是。

**敏感接口默认不收集为原子能力（硬约束）**：

会产生**不可逆副作用**的接口，**默认不收集为原子能力**、不写 mcp.json、不生成 `apis/*.js`。理由：这类接口风险高（误触发不可恢复）、低频、应由用户在原生页面主动操作，不适合 AI agent 自动调用。

**判定原则**：
- **不可逆的状态变更**：注销账号、删除数据、清空记录等执行后无法撤销的操作，解绑、退订、解散团队、踢出成员、退出等无法恢复的操作

**关键词提示清单**（命中≠一定敏感，仍需结合语义判断；用于辅助筛选，非穷尽）：
`log_off / 注销 / 销户 / cancel / delete / 删除 / remove / 移除 / unbind / 解绑 / dissolve / 解散 / kick / 踢出`

**处理方式**：识别到敏感接口时，**跳过不选**，并在功能清单 JSON 里标注 `"skipped": "destructive"`（附原因）。这些功能由用户在小程序原生页面自己实现，不暴露给 AI agent。若用户**明确要求**把某个敏感接口做成原子能力，才收集，并在 `probe/plan.json` 对应 api 项标 `"destructive": true` + `"destructiveReason"`

**产出物 JSON**（字段统一 camelCase）：

```json
[
  {
    "functionName": "检索商品",
    "pages": ["pages/items/list", "pages/search/index"],
    "sourceApis": ["GET /api/items/search"],
    "suggestedAtomicInterfaces": ["searchItems"],
    "needsComponent": true
  }
]
```

**必须将清单发给用户二次确认**才能进入阶段 3。

---

## 阶段 3 — 接口与 JSAPI 提取 + 可行性校验

**契约**：

| 项 | 内容 |
|---|------|
| 入口条件 | 已有用户确认的目标原子能力清单 + 已 PASS 的 auth-spec |
| 产出物 | 逐能力 `interface-spec.<cap>.md`（真实入口 + 请求构造 + 每入参赋值来源 + `authRefs` 引用 auth-spec + `response.pendingProbe`）+ 可行性校验结果 |
| 下一步 | 所有能力均找到对应实现 → 阶段 4 |
| 阻断条件 | 任一能力找不到对应实现 / 依赖链路含插件 → 阻断规则 B |
| 产物校验 | 进入阶段 4 前**必须确认以下文件已落盘**：每个能力的 `.ai-mode-skills/interface-spec.<name>.md` + `.ai-mode-skills/merged-result.json`（含 probe 回填的真实响应）+ `.ai-mode-skills/probe/plan.json`。任一 api 仍 `pendingProbe` → 禁止进入（回 3.7 补 probe）；缺 interface-spec → 回 3.2 补 |

详细匹配模式见 `references/ANALYSIS_PATTERNS.md`。

**3.1 提取范围**：仅扫用户已确认能力对应的页面/模块，搜索网络调用（`wx.request` / `wx.cloud.{callFunction,database,callContainer}`）+ 白名单内 JSAPI（高频列表见"硬性约束 D"，完整清单见 `references/JSAPI_WHITELIST.md`）。

**3.2 依赖追踪（读真实源码，逐字复刻）**：

逐能力先定位**承载它的真实交互入口**——源码中触发该能力的那段代码（页面生命周期 `onLoad`/`onShow`、按钮 / 输入框等事件 handler，或对应业务函数）。**该入口实际调用的接口，就是这个能力的唯一标准接口**；连同它传入的分支参数（类型 / 模式标志位等）一起逐字复刻。能力与接口是一一对应关系，不要在多个名字相近的接口间"挑一个更好实现的"——一切以源码真实入口为准。

**3.3 鉴权依赖确认**：结合 auth-spec，对每个目标接口确认 ① 是否需要登录态 ② token 来源（storage 直读 / 需先登录）③ 登录方式（`wx.login` + 换 token / 其他）。interface-spec 只填 `authRefs` 引用 auth-spec（`requiresLogin` / `signing` / `dynamicValues` / 通用参数 inherit），**不重新定义鉴权事实**，避免与 auth-spec 漂移。详见 `AUTH_MIGRATION.md` §2/§5。

**3.4 签名 / 可请求性**：若接口请求含签名 / 反爬字段（sign / timestamp+nonce / 指纹等），记入 auth-spec §2.6（`id` / `scope` / 触发 / 密钥来源 / 输出字段 / 依赖模块 / 原文片段指针），函数体 verbatim 入 `auth-spec.snippets.txt`。**不得因「更好实现」换接口或简化签名**——签名一错全废。可请求性判定：依赖验证码/短信等运行时人机交互 → `replicable: false` + `blockers` → 阻断或人工接入。

**3.5 插件依赖**：依赖链路含 `requirePlugin` / `require('../plugin/')` / `plugin://` → 阻断规则 B。

**3.6 可行性三级校验**：

| 级别 | 识别特征 | 处理 |
|------|---------|------|
| ✅ 高置信 | 真实入口唯一确定接口，参数/返回路径清晰 | 直接进阶段 4 |
| ⚠️ 中置信 | 参数/返回模糊，或多个并列真实入口 | 补读或问用户 |
| ❌ 无置信 | 找不到任何实现 | 阻断 B |

**中置信询问模板**：

```
以下原子能力在源码中存在多个并列的真实入口，请确认对应哪一个：

能力：<能力名>
入口 1：<页面/事件> 调用 <接口/云函数> — 参数 <x>、返回 <y>（来自 pages/xxx.js 第 N 行）
入口 2：<页面/事件> 调用 <接口/云函数> — 参数 <x>、返回 <y>（来自 pages/yyy.js 第 M 行）

请按你实际想暴露的小程序功能确认对应哪个入口（接口由能力入口唯一确定，不以实现难易为取舍）。
```

**3.7 运行时探测（probe）**

🔴 **生成阶段必做、非 validate 阶段的功能**；跑**源项目**普通开发者工具即可（与agent无关）。完整 SOP 见 **`references/RUNTIME_PROBE.md`**。

**注意，只有使用了 automator，捕获真实的请求响应，才能在生成的时候使用正确的数据结构，禁止猜测接口的返回字段。**

要点：

1. 选出的每个业务 `api_name` 各一条 plan 条目 → 批量 probe（每次执行落盘 `probe/<run-id>.json`，重试可多个）→ 读成功 run 回填 `interface-spec.response` → 合并写**一份** `merged-result.json`（阶段 4 只读此文件，**schema 见 `RUNTIME_PROBE.md` §5.2，`check-artifacts.mjs --stage 3` 校验结构**）
2. 大项目 plan 由 `SUBAGENT_PROTOCOL.md` §2.4 产出；小项目主 agent 从 interface-spec 写 plan
3. **一键执行**：`node scripts/probe.mjs --project <源项目> --plan <plan.json>`——默认 `--mode auto`，自动完成 cli open → cli auto → 端口检查 → connect，失败自动重试 3 轮（`--mode connect` 跳过 cli open/auto 需预先拉起；`--mode launch` 不推荐；详见 `RUNTIME_PROBE.md` §四）
4. 连接失败：**≥3 轮重试**后仍失败 → **必须停下告知用户**"probe 未执行，接口响应结构未经真机验证，请协助排查环境"，等待用户协助或明确同意回退。**禁止模型自行决定回退静态**——必须实际执行 cli open / cli auto / probe 并由输出证明环境不可用；回退后所有接口标 `verified:false` / `[ai-mode:UNVERIFIED]`；**禁止手写 `probe/*.json` 伪造探测结果**
5. `apis/<name>.js` 顶部须在成功时标注 `[ai-mode:probe]` 注释溯源，失败标注 `[ai-mode:static]`

---

## 阶段 4 — 原子接口设计

**契约**：

| 项 | 内容 |
|---|------|
| 入口条件 | 确认 3.7 probe 阶段已经执行，`<源项目>/.ai-mode-skills/merged-result.json` 已生成（由 auth-spec + 各 interface-spec 合并、probe 回填响应而成），且每个原子接口所用 api 的真实请求参数与响应数据结构已持久化其中。**任一 api 仍停留在 `pendingProbe` 未探状态则禁止进入本阶段**，鉴权设计直接引 interface-spec 的 `authRefs` → auth-spec |
| 产出物 | ① 原子接口清单；② API 依赖图；③ storage key 清单 |
| 下一步 | 三份产出物齐全 → 阶段 5 |
| 产物校验 | 进入阶段 5 前确认 `merged-result.json` 存在且无 `pendingProbe` 残留（阶段 3 产物已在阶段 3 入口条件校验，此处只复查）|

**4.1 技能划分**：同业务域（商品/订单/地址）原子接口聚合到同一 skill；共享 storage 上下文的接口必须在同一 skill 内；每 skill 推荐 3-8 个原子接口（更多则按子业务拆分）。

**4.2 接口字段**：每条接口含 `name`（驼峰、全局唯一）/ `description`（含内部串联操作，帮助小程序 AI 决策）/ `inputSchema`（仅小程序 AI 需从用户获取的参数；无参用 `{"type":"object","properties":{}}`）/ `outputSchema`（对应 `structuredContent`）/ `_meta.ui.pagePath`（**按需**，接力页 path、不含 query；"执行完停下等用户确认"类接口需配，配合返回值 `handoff`，详见 D.6）/ `_meta.ui.componentPath`（**仅当用户明确要求生成原子组件时**才声明，格式 `components/xxx/index`；声明则组件目录必须 4 文件齐全）。

> **多模态入参**：当接口需要用户上传图片（如 P 图、图像识别）时，对应 `inputSchema.properties.<field>` 加 `"format": "image"`，类型为 `string`（运行时填本地图片路径）。小程序 AI 输入框会据此识别为多模态字段、引导用户上传图片。

> **敏感接口标记**：阶段 2 已规定敏感接口默认不收集为原子能力。若因用户明确要求或疏漏导致敏感接口进入阶段 4，**必须**在 `probe/plan.json` 对应 api 项写 `"destructive": true` 和 `"destructiveReason": "<一句话原因>"`（probe 据此跳过）。判定原则与关键词清单见阶段 2

**4.3 进小程序方式（默认 handoff，不默认生成组件）**：默认**不生成原子组件**——需接续操作/查看详情的接口，配 `_meta.ui.pagePath` + 返回 `handoff`（见 D.6），用户点小程序卡片进接力页。**仅当用户明确要求生成原子组件（GUI 卡片）时**，才按返回值类型对照组件模板（详见 `references/COMPONENT_TEMPLATES.md`）：列表/卡片项 → 通用列表；详情/单对象 → 详情卡片；购物车/带数量总价 → 购物车；下单成功/支付结果/操作确认 → 状态结果。

**4.4 产出物示例**（默认形态：无组件，配 handoff）：

```json
[{
  "skill": "business",
  "name": "searchItems",
  "title": "检索商品",
  "description": "根据关键词检索商品，返回商品列表",
  "inputSchema": { "type": "object", "properties": {} },
  "outputSchema": { "type": "object", "properties": { "items": { "type": "array" } } },
  "_meta": { "ui": { "pagePath": "/pages/goods/list" } }
}]
```

> 用户明确要求生成原子组件时，才在 `_meta.ui` 追加 `componentPath: "components/item-list/index"` 并生成组件目录。

API 依赖图（仅在通过 storage 传上下文时必备）：

```
searchProducts ──(storage: skills_shopping_lastSearchResult)──▶ addToCart
              └─(storage: skills_shopping_lastSearchResult)──▶ getProductDetail
```

storage key 命名统一 `skills_{skillName}_{dataName}`，列表含 `key` / 写入方 / 读取方 / 数据结构。

---

## 阶段 5 — 代码生成

**契约**：

| 项 | 内容 |
|---|------|
| 入口条件 | 阶段 4 三份产出物齐全；`.ai-mode-skills/` 源码忠实产物齐全（`auth-spec.md` / `merged-result.json` / `probe/plan.json` + 每个 api 的 `interface-spec.<name>.md`）——缺任一则回对应阶段补落盘（auth-spec→1.2、interface-spec→3.2、merged-result→3.7） |
| 产出物 | 完整的 `skills/{skill-name}/`（`mcp.json` / `SKILL.md` / `index.js` / `apis/*` / `utils/*` / `components/*`）；每个 `apis/<name>.js` 经 5.8 字段忠实自检 |
| 下一步 | 代码生成完成 → 阶段 6 |
| 阻断条件 | 产出物缺失 → 停留本阶段补齐 |

代码模板见 `references/CODE_TEMPLATES.md`、组件模板见 `references/COMPONENT_TEMPLATES.md`、**设计规范见 `references/ATOMIC_COMPONENT_DESIGN.md`（最高优先级）**、CSS 实现规范见 `references/ATOMIC_COMPONENT_CSS.md`。

### 5.1–5.4 组件四个强制前置（**仅当用户明确要求生成原子组件时**适用；写任何组件 WXML/WXSS/JS 前必须按序走完）

> 默认不生成原子组件 → 本节整节跳过，只生成原子接口 + handoff。仅当用户明确要求生成 GUI 卡片时才执行。
>
> 一旦要生成组件，进入阶段 5 前模型必须**完整阅读** `references/COMPONENT_TEMPLATES.md` 和 `references/ATOMIC_COMPONENT_DESIGN.md`，不得跳过。违反此条是导致"接口请求成功但组件不渲染"的常见根因。

| 编号 | 主题 | 关键要点 | 详见 |
|------|------|---------|------|
| **5.1** 设计规范（最高优先级） | 尺寸/主题/边距/字体/布局/操作区 | ① 5 档宽高比 + 圆角 4px；② 主题色按 §2.1 流程从主包 `app.json`/`app.wxss` 抽（浅 + 暗都抽，wxss 顶部注释"色源=…"链路；主包 6 步都查不到才走 §2.3 兜底）；③ 边距 屏幕 16 / 卡片 12 / 元素 8·16；④ 字号 17/15/12 三档 + 同一基色 0.9/0.45/0.3 透明度分层；⑤ 主轴上下/左右布局；横向超长可用 `<scroll-view scroll-x>`，禁纵向滚动、禁 >2 列网格；⑥ ≤3 控件、主动作 ≤1、动宾文案、主按钮居右 | `references/ATOMIC_COMPONENT_DESIGN.md` |
| **5.2** 源样式提取 + 字段映射 | 7 步工作流 | 与设计规范冲突时以**设计规范为准**，仅迁移源项目品牌色与字段映射结果。**自检**：wxss 主色是 `#07c160` / `#ff4d4f` 且源页面未用，或 wxml 出现 `item.imageUrl` 但源 API 字段是 `cover`/`pic`/`thumb` — 视为"照抄模板"，必须回炉重做 | `references/STYLE_MIGRATION.md` |
| **5.3** 组件交互行为 | 组件是小程序 AI 的"回合出口"，不是"页面入口" | 每个组件都要同时考虑"展示什么"+"用户下一步做什么"——按 `mcp.json.apis[].description` + API 依赖图列出下一步，映射到 `mcp.json.apis[].name` **已存在**的接口；不存在则去掉按钮，**不要上行不存在的 name**。每个可交互元素绑 `bindtap` + `hover-class`，关键实体用 `data-*` 携带 | `references/COMPONENT_TEMPLATES.md` "上行消息"节 |
| **5.4** 组件 JS 强制骨架（数据接入） | 数据只能经 `NotificationType.Result` 下发 | 禁止 `properties` / `dataSource` / `observers`；按 `COMPONENT_TEMPLATES.md` 骨架在 `created` 里绑定 Result 与 Overflow，并打印 `[ai-mode] {componentName} overflow monitor=on` | `references/COMPONENT_TEMPLATES.md` "组件 JS 骨架"节 + "溢出处理模板"节 |

tap handler 优先形态 2（`text` + `api/call` 组合）：

```js
// 使用点必须现取 ctx；不要用 this._modelCtx 之类的缓存引用
wx.modelContext.getContext(this).sendFollowUpMessage({
  content: [
    { type: 'text', text: '<用户视角的简短中文，例如：选择拿铁>' },
    { type: 'api/call', data: { name: '<mcp.json 已声明的 api name>', arguments: { /* 对齐该接口 inputSchema */ } } },
  ],
})
```

只有当点击动作无法映射到原子接口时才退回形态 1（单 `text`）。每次上行 `api/call` 前打一行 `[ai-mode] {componentName} send api/call name=... args=...` console.info。**禁止**：组件内直调业务接口、单独发 `api/call` 不带前导 `text`、`arguments` 用占位值、`name` 不在 `mcp.json` 中、只展示不响应的"死"按钮、用 `this._modelCtx.sendFollowUpMessage(...)` 缓存引用调方法。

### 5.5 目录结构

```
{项目根目录}/
├── app.json                              # 含 agent.skills 注册
└── skills/                               # 独立分包（多 skill 共用）
    ├── _shared/                          # 可选：≥2 个 skill 共用的工具函数才放这里
    └── {skill-name}/
        ├── mcp.json                      # 原子接口 Schema 定义
        ├── SKILL.md                      # skill 路由说明
        ├── index.js                      # 接口注册入口
        ├── apis/                         # 原子接口实现（推荐目录；validator 兼容 tools/services/、tools/）
        ├── utils/                        # 工具模块（目录名不强制，常见 utils/services/helpers）
        └── components/{component-name}/  # index.js/json/wxml/wxss（路径强约束，与 mcp.json _meta.ui.componentPath 严格相等）
```

> **目录分层**：跨 skill **禁止** `require('../../{otherSkill}/...')`；多 skill 复用走 `skills/_shared/`（不在 `mcp.json` 注册、不调 `registerAPI`）。

### 5.6 鉴权代码生成

按 `AUTH_MIGRATION.md` §5/§6 生成 `utils/request.js` + `index.js`；模板见 `CODE_TEMPLATES.md`。生成后过 AUTH_MIGRATION §6 自检；interface-spec 忠实度见 `SUBAGENT_PROTOCOL.md` 源码忠实铁律。

### 5.7 mcp.json + 技能自身 SKILL.md + 返回值 + 日志

- **`mcp.json`**：顶层 `{ "apis": [...] }`，每项必含 `name` / `description` / `inputSchema` / `outputSchema`；进小程序的接口按需加 `_meta.ui.pagePath`（配合返回值 `handoff`）；`_meta.ui.componentPath` 与 `components[]` **仅在用户明确要求生成原子组件时**才有（`components[]` 声明组件网络能力，详见 D.3）。完整字段示例见 `references/CODE_TEMPLATES.md` 第四节
- **技能自身 `SKILL.md`**（**文件名严格全大写**）定位"路由说明"，**只允许 5 节按序**：能力域定位 → 触发场景（用户原话 few-shot）→ 不适用范围 → 前置条件 → 使用顺序。**通篇禁止**：驼峰 apiName / `inputSchema` / `outputSchema` / 参数表 / 返回值表 / `componentPath` / storage key / 接口依赖图 / 安装 CLI 运维。完整模板见 `references/CODE_TEMPLATES.md` 第五节
- **返回值格式**：`{ isError?, content: [{type:'text', text}], structuredContent?, _meta?, handoff? }`——`content` 给 LLM 文本，`structuredContent` 对应 `outputSchema`，`_meta` 对 LLM 不可见可传 UI 组件；`handoff`（**进小程序按需**，顶层与上述字段同级）为 `{ query: string, payload? }`，承接卡片点击进接力页，详见 D.6
- **日志规范**：原子接口必打 入口 / 入参 / 请求前后 / 出口 / catch；原子组件必打 `created`/`attached` / 收到 Result / `setData` / `NotificationType.Overflow`（**必监听**，用于校验裁剪）。统一前缀 `[ai-mode]`。**日志不打够等于没日志**——真机失败看不到关键节点 → 回阶段 5 补齐重跑

### 5.8 字段忠实自检

> 每写完一个 `apis/<name>.js`，就地拿它的 `structuredContent` 字段集与 `merged-result.json` 中该 api 的 `response.fields`（probe 真实响应字段集）比对，防止臆造字段。生成一个查一个，不要攒到收尾。如果没有probe产物，重新执行probe

- 字段一致 → 通过，继续下一个
- 缺少/多出字段 → 代码臆造或未忠实 probe，以 probe 为准修正 `structuredContent` 与 `mcp.json` `outputSchema`
- 该 api 标 `verified:false` / `pendingProbe`（probe 未成功）→ 跳过比对，但交棒时须声明未验证


## 阶段 6 — 配置集成

**契约**：

| 项 | 内容 |
|---|------|
| 入口条件 | 阶段 5 生成完整 `skills/{skill-name}/` |
| 产出物 | `app.json` 含 `agent.skills` + `subPackages`；`project.config.json` 的 `packOptions.include` 含 `skills` |
| 下一步 | 两份配置均已更新 → 交棒 `wxa-skills-validate` |
| 阻断条件 | 未更新配置直接交棒 → 必定失败，停留本阶段 |
| 产物校验 | 交棒前**必须确认以下文件存在**：`skills/{skill-name}/mcp.json` + `skills/{skill-name}/index.js` + 每个 `apis/*.js` + `utils/request.js`（或 `utils/util.js`）；`app.json` 的 `agent.skills[]` 含本 skill 条目且 `subPackages` 含 `skills` 独立分包；`project.config.json` 的 `packOptions.include` 含 `skills`。缺任一 → 回阶段 5 补 |

配置格式见 `references/CODE_TEMPLATES.md` 第六节。关键要点：

- `agent.skills[].path` 指向 `skills/{skill-name}` 目录
- `subPackages` 中 `skills` 整体作为 `independent: true` 的独立分包；**多 skill 共用同一个分包**——新增 skill 只在 `agent.skills[]` 里追加，**不要**为每个 skill 加一条 `subPackages` 条目
- `project.config.json` 的 `packOptions.include` 需含 `{ "type": "folder", "value": "skills" }`
- **handoff（按需）**：若有接口配了 `_meta.ui.pagePath` 并返回 `handoff`，在主包 `app.js` 的 `onLaunch` 内注册 `wx.onAgentHandoff`（详见 D.6 与 `references/CODE_TEMPLATES.md` "handoff 接力页" 节）

---

## 收尾 — 交棒给 wxa-skills-validate（强制）

阶段 6 完成后，**必须**在回复中明确告知用户：

```
代码生成与配置集成已完成。下一步请使用 `wxa-skills-validate` skill 对产物进行校验与真机验证：

- skills 路径：<abs-path>/skills
- project-path：<abs-path>（含 project.config.json 的 appid 为 <appid>）

wxa-skills-validate 会依次执行：静态校验 → cli agent tool execute → cli agent render → 交付文档。
```

**项目校验必须用 subagent 隔离**：当本项目按 `SUBAGENT_PROTOCOL.md` 走 subagent 协议时，校验也**必须派 subagent 执行**（`SUBAGENT_PROTOCOL.md` §2.6），禁止在主 agent 上下文中直接运行 validate。

**交棒步骤不可省略**。仅输出代码不算完成，必须在对话中显式提示用户切换到校验 skill。
