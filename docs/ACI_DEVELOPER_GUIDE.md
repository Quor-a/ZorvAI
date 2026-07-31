# Zorv AI · ACI 开发者手册（Agent Capability Interface）

> 版本：v1.0.13（能力清单同步 ZorvAI 浏览器 v1.0.12；新增 §14 LAN 控制台 / 控制台后台接入）｜ 适用 SDK：`aci-core` ｜ 最后更新：2026-07-31
>
> 本文档面向**希望让自己的 Android App 被 Zorv AI（或其他 ACI 控制端）调用**的第三方开发者，也适用于**想基于 `aci-core` 自建控制端**的开发者。

---

## 1. 什么是 ACI

**ACI（Agent Capability Interface，智能体能力接口）** 是一套运行在**同一台 Android 设备内**、**无需 Root**、基于 **AIDL Binder** 的本地跨应用调用框架。

- 控制端（如 Zorv AI）发现并绑定受控端 Service；
- 受控端（你的 App）通过 `BaseACIService` 声明「能力（Capability）」；
- 控制端把能力清单喂给 LLM，由 LLM 决策调用哪个能力、传什么参数；
- 调用通过 Binder 同步/异步完成，结果回传。

ACI 的设计目标是让「手机上的任意 App 能力」成为 AI Agent 可编排的工具，无需公网、无需云端中转。

---

## 2. 架构与角色

```
┌──────────────────────────┐         AIDL Binder           ┌──────────────────────────┐
│   控制端（Zorv AI）        │  ─── call / callAsync ───▶   │   受控端（你的 App）       │
│  QuroAciManager           │  ◀── ACIResponse ───────     │  BaseACIService 子类      │
│  - discover() 发现        │                              │  - onCreateCapabilities   │
│  - bind() 绑定            │  ─── ACTION_WAKE 广播 ──▶    │  - onCall() 处理          │
│  - getCapabilities() 取清单│  (唤醒 stopped 进程)         │  - onCheckPermission()    │
└──────────────────────────┘                              └──────────────────────────┘
```

| 角色 | 职责 | 关键类 |
|------|------|--------|
| **控制端** | 扫描、绑定、取能力清单、发起调用、把结果喂给 LLM | `QuroAciManager` + `aci-core` 的 `IACIService` 桩 |
| **受控端** | 继承 `BaseACIService`，声明能力，实现处理逻辑 | `BaseACIService` / `Capability` / `ACIRequest` / `ACIResponse` |
| **Binder 契约** | 定义跨进程方法 | `IACIService.aidl` / `IACICallback.aidl` |
| **权限** | 5 层鉴权 | `aci_permissions.xml` + `onCheckPermission` |

---

## 3. 接入准备：获取 `aci-core`

`aci-core` 是纯本地库（仅依赖 `androidx.annotation:annotation:1.7.1`），以 **AAR** 形式分发。

### 方式 A：从本仓库 Release 直接下载

在 [ZorvAI Releases](https://github.com/Quor-a/ZorvAI/releases) 的 **v1.0.6（含 AAR）** 中下载 `aci-core-release.aar`，放入你模块的 `libs/` 目录。

> 开源独立分支：`aci-core` 分支（仓库根即一个可独立 `./gradlew assembleRelease` 的 Android 库工程），你可 `git checkout aci-core` 后自行构建或改源码。

### 方式 B：Gradle 依赖（私有/本地仓库）

```kotlin
// app/build.gradle.kts（或你的库模块）
dependencies {
    implementation(files("libs/aci-core-release.aar"))
}
```

> ⚠️ `aci-core` 当前以 AAR 二进制分发（非 Maven Central 坐标）。开源分支提供完整源码，可接入你自己的 Maven 仓库后改用坐标依赖。

---

## 4. 受控端接入（5 步）

### 4.1 添加依赖

把 `aci-core-release.aar` 放到受控端模块的 `libs/`，并在该模块 `build.gradle.kts` 加：

```kotlin
dependencies {
    implementation(files("libs/aci-core-release.aar"))
}
```

### 4.2 声明权限与清单（受控端 `AndroidManifest.xml`）

受控端需在 `<manifest>` 中**自定义并声明 ACI 权限**，并**声明 `<queries>` 让系统能发现自身**（Android 11+ 包可见性）：

```xml
<manifest ...>

    <!-- ACI 权限定义（受控端定义，控制端需声明 uses-permission 才能调用） -->
    <permission android:name="ai.aci.permission.CALL"
        android:protectionLevel="normal" />
    <permission android:name="ai.aci.permission.CALL_DANGEROUS"
        android:protectionLevel="dangerous" />
    <permission android:name="ai.aci.permission.DISCOVER"
        android:protectionLevel="normal" />

    <uses-permission android:name="ai.aci.permission.CALL" />
    <uses-permission android:name="ai.aci.permission.DISCOVER" />
    <uses-permission android:name="ai.aci.permission.CALL_DANGEROUS" />
    <uses-permission android:name="android.permission.INTERNET" />

    <!-- 包可见性：声明本 App 提供/响应的 ACI 意图 -->
    <queries>
        <intent>
            <action android:name="ai.aci.core.ACTION_BIND" />
        </intent>
        <intent>
            <action android:name="ai.aci.core.ACTION_WAKE" />
        </intent>
    </queries>

    <application ...>
        <service
            android:name=".QuroControlledAciService"
            android:exported="true"
            android:permission="ai.aci.permission.CALL">
            <intent-filter>
                <action android:name="ai.aci.core.ACTION_BIND" />
            </intent-filter>
        </service>

        <!-- stopped-state 唤醒接收器（关键，见 4.6） -->
        <receiver
            android:name=".QuroAciWakeReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="ai.aci.core.ACTION_WAKE" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

> 🔑 **`<queries>` 必须写在受控端**。很多「找不到服务 / 绑定失败」的根因是受控端漏写 `<queries>`，导致控制端 `queryIntentServices(ACTION_BIND)` 在 Android 11+ 上返回空。

### 4.3 实现 Service（继承 `BaseACIService`）

```kotlin
class QuroControlledAciService : BaseACIService() {

    override fun onCreate() {
        // 用 try-catch 包 super.onCreate()，避免 onCreateCapabilities 中抛异常直接炸掉 Service
        try {
            super.onCreate()
        } catch (e: Throwable) {
            Log.e("ACI", "onCreateCapabilities failed", e)
        }
    }

    override fun onCreateCapabilities(capabilities: MutableList<Capability>) {
        capabilities.add(
            Capability.create("browser_open", "打开并导航浏览器到指定网址")
                .addParam("url", "string", true, "要打开的网址")
                .addResult("opened", "boolean", "是否成功打开")
                .addFlag(Capability.FLAG_BACKGROUND)
                .addFlag(Capability.FLAG_NO_UI)
        )
        // ... 更多能力
    }

    override fun onCheckPermission(request: ACIRequest, callerPkg: String): Boolean {
        // 仅放行白名单调用方（如 Zorv AI 主包名与自身）
        return callerPkg == "com.ai.assistance.quro" || callerPkg == packageName
    }

    override fun onCall(request: ACIRequest): ACIResponse {
        return when (request.capability) {
            "browser_open" -> handleOpen(request)
            else -> ACIResponse.error(ACIError.CAPABILITY_NOT_FOUND, "unknown capability")
        }
    }
}
```

`BaseACIService` 提供的方法：

| 方法 | 是否必重写 | 说明 |
|------|-----------|------|
| `onCreateCapabilities(List<Capability>)` | ✅ 必重写 | 注册你的能力清单 |
| `onCall(ACIRequest): ACIResponse` | ✅ 必重写 | 同步处理单次调用 |
| `onCallAsync(ACIRequest, IACICallback)` | 可选 | 异步处理；默认实现会切线程后调 `onCall` |
| `onCheckPermission(ACIRequest, callerPkg): Boolean` | 可选 | 自定义调用方校验，默认返回 `true` |
| `onBeforeCall(ACIRequest)` / `onAfterCall(ACIRequest, ACIResponse)` | 可选 | 钩子，默认仅打日志 |

`BaseACIService` 内部已实现 `IACIService.Stub`（`call` / `callAsync` / `getCapabilities` / `ping`），并在 `onBind()` 返回该 Binder。

### 4.4 定义能力：`Capability.create` 的**正确签名**

```java
public static Capability create(String id, String description)
```

> ⚠️ **第 2 个参数是 `description`（给 LLM 的自然语言描述），不是 `version`！**
> 方法内部固定 `version = "1.0"`。历史版本曾误把 `"1.0"` 当作 description 传入，导致 LLM 看不到能力说明、控制端「能力(0)」。
> **正确写法**：`Capability.create("browser_open", "打开浏览器到指定网址")`。

`Capability` 链式构建方法：

| 方法 | 作用 |
|------|------|
| `create(id, description)` | 创建能力；`version` 固定 `"1.0"` |
| `addParam(name, type, required, desc)` | 声明入参（`type`: `string/int/boolean/double/byte[]`） |
| `addResult(name, type, desc)` | 声明出参 |
| `addFlag(flag)` | 添加行为标志（见下） |
| `setPermission(perm)` | 该能力额外要求的权限字符串 |
| `setUserConfirm(bool)` | 是否要求用户确认后再执行 |

行为标志常量：

| 常量 | 值 | 含义 |
|------|----|------|
| `FLAG_BACKGROUND` | `"BACKGROUND_EXECUTABLE"` | 可在后台执行 |
| `FLAG_NO_UI` | `"NO_UI_REQUIRED"` | 执行不需要 UI |
| `FLAG_DANGEROUS` | `"DANGEROUS_ACTION"` | 危险操作，需用户确认 |

### 4.5 处理调用：`ACIRequest` / `ACIResponse`

**请求**（控制端传来的）：

```kotlin
val cap = request.capability          // 能力 id，如 "browser_open"
val caller = request.callerPkg        // 调用方包名
val url = request.params.getString("url")   // 入参（Bundle）
val callId = request.callId           // UUID，用于异步回调对应
```

**响应**（你返回的）：

```kotlin
// 成功（无数据）
ACIResponse.success()

// 成功（带结果）
ACIResponse.success().putResult("opened", true)

// 失败
ACIResponse.error(ACIError.PERMISSION_DENIED, "caller not allowed")
ACIResponse.error(ACIError.BAD_REQUEST, "missing param: url")
```

`ACIResponse` 字段：`success`(Boolean) / `result`(Bundle) / `errorCode`(Int) / `errorMessage`(String) / `callId`(String)。

### 4.6 stopped-state 唤醒（关键！）

在 **ColorOS / Android 11+** 上，受控端若处于 **stopped 状态**，控制端裸 `bindService` **拉不起进程**，表现为「发现/绑定成功但 `getCapabilities` 返回空或调用无响应」。

修复方式：受控端提供 `WakeReceiver`，控制端在 `bindService` 前先发 `ACTION_WAKE` 广播（带 `FLAG_INCLUDE_STOPPED_PACKAGES`）把进程拉起。

```kotlin
class QuroAciWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "ai.aci.core.ACTION_WAKE") {
            val launch = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch != null) context.startActivity(launch)
        }
    }
}
```

控制端（`QuroAciManager.bindWithWake()`）已内置该逻辑：先发唤醒广播，再 `bindService(BIND_AUTO_CREATE)`。

> 🔑 这是「Zorv AI 之前能绑定、某次更新后不行」的真实根因——修复点在**控制端**的绑定逻辑，而非受控端能力注册。

---

## 5. 请求与响应模型

`ACIRequest`（Parcelable）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `capability` | String | 能力 id |
| `version` | Bundle | 约定版本（默认 1.0） |
| `params` | Bundle | 调用入参 |
| `callId` | UUID | 调用唯一标识 |
| `callerPkg` | String | 调用方包名 |

`ACIRequest.Builder` 支持 `param(String/int/boolean/double/byte[])`、`version(...)`、`build()`。

`ACIResponse`（见 4.5）。

---

## 6. 权限模型（5 层）

ACI 采用纵深防御，调用需依次通过：

1. **Android Manifest 权限**：受控端 Service 声明 `android:permission="ai.aci.permission.CALL"`，调用方须声明 `<uses-permission android:name="ai.aci.permission.CALL" />`。
2. **Binder UID 校验**：受控端可通过 `Binder.getCallingUid()` 校验调用方身份。
3. **`onCheckPermission(request, callerPkg)`**：受控端自定义业务级白名单。
4. **能力级 `requirePermission`**：单个能力可要求额外权限（`setPermission`）。
5. **用户确认**：危险能力（`setUserConfirm(true)` / `FLAG_DANGEROUS`）需用户显式确认。

预定义权限（`aci_permissions.xml`）：

| 权限 | 级别 | 用途 |
|------|------|------|
| `ai.aci.permission.CALL` | normal | 常规调用 |
| `ai.aci.permission.CALL_DANGEROUS` | dangerous | 危险操作调用 |
| `ai.aci.permission.DISCOVER` | normal | 服务发现 |

---

## 7. 控制端调用（发现与绑定）

控制端流程（以 `QuroAciManager` 为参考）：

```kotlin
// 1. 发现：扫描声明了 ACTION_BIND 的受控端
val q = Intent("ai.aci.core.ACTION_BIND")
val resolved = context.packageManager.queryIntentServices(q, 0)

// 2. 绑定（含唤醒 stopped 进程）
val intent = Intent("ai.aci.core.ACTION_BIND").setClassName(pkg, svcClass)
context.bindService(intent, conn, Context.BIND_AUTO_CREATE)

// 3. 取能力清单
val caps: Array<String> = stub.getCapabilities()

// 4. 发起同步调用
val req = ACIRequest.Builder()
    .capability("browser_open")
    .param("url", "https://example.com")
    .build()
val resp: ACIResponse = stub.call(req)
```

---

## 8. 异步调用

受控端重写 `onCallAsync(request, callback)` 时，通过 `IACICallback` 回传进度与结果：

```kotlin
override fun onCallAsync(request: ACIRequest, callback: IACICallback) {
    thread {
        callback.onProgress(50, "processing...")
        val resp = doWork(request)
        callback.onResult(resp)
    }
}
```

`IACICallback.aidl`：`onResult(ACIResponse)` / `onProgress(int, String)`。

---

## 9. 错误码（`ACIError`）

| 常量 | 值 | 含义 |
|------|----|------|
| `SUCCESS` | `0` | 成功 |
| `REQUEST_NULL` | `-1` | 请求为空 |
| `BAD_REQUEST` | `400` | 参数错误 |
| `PERMISSION_DENIED` | `403` | 权限不足 |
| `CAPABILITY_NOT_FOUND` | `404` | 能力不存在 |
| `INTERNAL_ERROR` | `500` | 内部错误 |
| `SERVICE_UNAVAILABLE` | `503` | 服务不可用 |
| `TIMEOUT` | `504` | 超时 |
| `BINDER_DIED` | `505` | Binder 连接断开 |

`getCapabilities()` 返回空数组 **不等于** 调用失败——常见原因是受控端漏写 `<queries>` 或 `onCreateCapabilities` 抛异常（务必用 try-catch 包裹 `super.onCreate()`，见 4.3）。

---

## 10. 发布清单配置要点（包可见性）

- **受控端**：必须写 `<queries>`（ACTION_BIND + ACTION_WAKE），否则 Android 11+ 控制端发现不到。
- **控制端**：若需发现任意受控端，也要在自身 `<queries>` 声明 `ACTION_BIND`（本项目 `QuroAciManager` 已处理）。
- **权限**：受控端定义权限、控制端声明 `uses-permission`，二者包名/权限名必须完全一致。

---

## 11. 真实踩坑与最佳实践

| 坑 | 现象 | 修复 |
|----|------|------|
| `Capability.create(id, "1.0")` | LLM 看不到能力描述，控制端「能力(0)」 | 第 2 参传**自然语言描述**，不是版本号 |
| 漏写 `<queries>` | 发现为空 / 绑定失败（Android 11+） | 受控端 Manifest 补 `<queries>` |
| `onCreateCapabilities` 抛异常 | Service 启动即崩溃、无能力 | `onCreate()` 用 try-catch 包 `super.onCreate()` |
| stopped-state 不唤醒 | 之前能绑、更新后不能 | 控制端 `bindWithWake`（先发 `ACTION_WAKE` 广播） |
| 权限名不一致 | `SecurityException` / 绑定被拒 | 受控端定义权限与控制端 `uses-permission` 完全一致 |
| `browser_read` 直接传完整大 HTML | `TransactionTooLargeException`，调用失败 | 截断 ≤15 万字符 + 大页面 gzip(byte[]) 经 `html_gz` 回传，控制端解压还原（见 §13.3） |

---

## 12. 协议版本与兼容

| 协议版本 | minSdk | 关键能力 |
|----------|--------|----------|
| 1.0 | API 24 | 同步 `call` / `getCapabilities` / `ping` |
| 1.1 | API 26 | 异步 `callAsync` + `IACICallback` |
| 2.0（规划） | — | LocalSocket 高速通道 |

`aci-core` v1.0.x 同时实现 1.0 + 1.1（`call` 与 `callAsync` 均可用），`Capability.version` 固定为 `"1.0"`。

---

## 13. 官方受控端能力清单（ZorvAI 浏览器）

ZorvAI 浏览器（受控端，与主程序同源）作为官方参考实现，已向控制端暴露以下 28 个能力（13 基础 + 7 agentic + 2 资源/分享 + 6 完整方案）。控制端 `QuroAciManager` 会把它们喂给 LLM，由 LLM 自动决定调用哪个、传什么参数——**控制端协议零改动**，新增能力对 LLM 完全透明。

### 13.1 能力总览（共 28 项：13 基础 + 7 agentic + 2 资源/分享 + 6 完整方案）

**基础能力（13）**

| 能力 id | 入参 | 出参 | 说明 |
|---------|------|------|------|
| `browser_open` | `url`(string, 必填) | `launched`(boolean) | 打开并导航到指定网址（带空格自动转搜索引擎查询） |
| `browser_read` | `mode`(string, 可选：full 默认 / clean) | `url` / `title` / `html`(string) + `truncated`(boolean)；大页面额外 `html_gz`(byte[]) + `html_len`(int)；`mode=clean` 额外返回 `cleaned_html`(精简 DOM：去脚本样式、可交互元素打 `data-ai-id`、标视口) | 读取当前页 HTML。**v1.0.8 修复 Binder ~1MB 溢出**（见 13.3） |
| `browser_crawl` | — | `url` / `title` / `text` / `links`(string) + `link_count`(string) + `truncated`(boolean) | 抓取结构化正文（取 `article/main/body` 的 innerText）+ 出站链接 `[{text,href}]` |
| `browser_search` | `query`(string, 必填) / `engine`(string, 可选：bing/google/baidu/ddg，默认 bing) | `query` / `engine` / `url` / `title` / `text` / `links`(string) + `truncated`(boolean) | 用搜索引擎检索关键词，返回结果页结构化数据 |
| `browser_script` | `code`(string, 必填) | `result`(string) + `truncated`(boolean) | 在当前页面执行任意 JavaScript 并返回结果（核心能力，等价于给 AI 一个完整浏览器控制台） |
| `browser_list` | — | `tabs`(string) | 列出当前打开的浏览器标签页 |
| `browser_info` | — | `package` / `versionName` / `versionCode`(string) | 查询受控端包名与版本信息 |
| `browser_find` | `text`(string, 必填) | `count`(int) | 页面内查找文本并高亮，返回命中数 |
| `browser_nav` | `action`(string, 必填：back / forward / reload) | `ok`(boolean) | 导航控制（WebView 操作已主线程安全封装） |
| `browser_screenshot` | — | `path`(string) | 截当前可视区域存 PNG，返回文件路径（无需存储权限） |
| `browser_capture` | `action`(string, 必填：list / clear / enable / disable) | `requests`(string) | 抓包：请求侧拦截，返回请求 URL/方法/请求头/是否主框架 |
| `console_ui` | — | `snapshot`(string, JSON) | 返回控制台 SDUI 快照（组件 JSON），供控制端通用渲染，与手动控制台共用同一 ConsoleBackend |
| `console_action` | `action`(string, 必填) / `payload`(string, 可选 JSON) | `ok`(boolean) + `action`(string) | 执行控制台动作（点击 / JS / 导航等），与手动控制台单一事实源 |

**agentic 增强（7 · 元素级操控 + 状态/事件/审计）**

| 能力 id | 入参 | 出参 | 说明 |
|---------|------|------|------|
| `browser_elements` | — | `count`(int) + `elements`(string JSON) | 扫描可交互元素，自动标注稳定 ID（`data-aci-eid`），返回元素树：id/标签/类型/文本/值/链接/位置(x,y,w,h)/可见性 |
| `browser_action` | `id`(string, 必填) / `op`(string, 必填：click/type/scroll_to/select) / `arg`(string, 可选) | `ok`(boolean) + `op` | 按元素稳定 ID 执行操作（type 兼容 React/Vue 受控输入） |
| `browser_wait` | `cond`(string, 必填：visible/hidden/text_contains/network_idle) / `id`(string, 可选) / `arg`(string, 可选) / `timeout_ms`(int, 可选) | `ok`(boolean) + `cond` + `waited_ms` | 条件等待引擎（network_idle 自动打桩 XHR/fetch 计数判定 SPA 加载完成） |
| `browser_snapshot` | `action`(string, 必填：save/list) / `label`(string, 可选) | `id`(string) 或 `list`(string) | 页面状态快照（save 按 label 覆盖 / list 列出） |
| `browser_restore` | `id`(string, 必填) | `ok`(boolean) | 页面状态回滚：导航回指定快照记录的 URL |
| `browser_events` | `limit`(int, 可选) | `events`(string JSON) | 页面事件总线：page_started / page_finished / request / load_resource |
| `browser_audit` | `limit`(int, 可选) | `audit`(string JSON) | ACI 调用审计：每次外部调用（能力/参数/成败）一条记录 |

**第二波增强（2 · 资源回传 + 分享）**

| 能力 id | 入参 | 出参 | 说明 |
|---------|------|------|------|
| `browser_media` | — | `count`(int) + `resources`(string JSON) | 扫描当前页 `video/audio/source/a[download]/img`，返回绝对直链 + 类型 + 文本；`video/audio` 额外含 `current_time`/`duration`/`paused`/`poster`；`a[download]` 含 `download`。控制方可直接拿直链播放或下载 |
| `browser_share` | `type`(string, 必填：page/text) / `text`(string, 可选) | `launched`(boolean) + `type` | 调起系统分享面板：page 分享当前页 URL / text 分享自定义文本 |

**第三波增强（6 · 完整方案：控制台捕获 + 选择器操控 + 轻量多标签）**

| 能力 id | 入参 | 出参 | 说明 |
|---------|------|------|------|
| `browser_console` | `action`(string, 可选：list 默认/clear/enable/disable) / `limit`(int, 可选) / `filter`(string, 可选) | `entries`(string JSON) + `count`(int) + `enabled`(boolean) | 抓取当前页 console.* 输出（log/warn/error/info）；原生 `WebChromeClient.onConsoleMessage` 钩取，默认开启 |
| `browser_query` | `selector`(string, 必填 CSS 选择器) | `count`(int) + `matches`(string JSON) | 按 CSS 选择器查询 DOM，返回匹配元素：index/标签/文本/值/链接/id/class/位置/可见性 |
| `browser_tabnew` | `url`(string, 必填) / `title`(string, 可选) | `tab_id`(string) + `url` + `active`(boolean) | 轻量多标签·新建并打开（单引擎，标签记录 URL + 切换重载） |
| `browser_tabs` | — | `count`(int) + `tabs`(string JSON) + `active_id`(string) | 轻量多标签·列出（含 active 标记） |
| `browser_tab` | `id`(string, 必填) | `ok`(boolean) + `url` + `id` | 轻量多标签·切换到指定标签（重载其 URL） |
| `browser_tabclose` | `id`(string, 必填) | `ok`(boolean) + `remaining`(int) | 轻量多标签·关闭（激活标签关闭后自动回退最近一个） |

> 注：`browser_action` 在第三波新增 `selector`(CSS 选择器) 参数（与 `id` 二选一，优先级低于 `id`），可直接用选择器定位操作，免去先 `browser_elements` 注入稳定 ID。

> 所有能力均带 `FLAG_BACKGROUND` + `FLAG_NO_UI`，可在后台、无需 UI 执行。`browser_crawl` / `browser_search` / `browser_script` 的正文与结果均**截断到约 15 万字符**（返回 `truncated=true` 表示被截断）。`browser_read` 大页面额外 gzip 经 `html_gz` 回传（见 13.3）。⚠️ 所有 WebView 操作须在主线程执行（见 §11 坑表），受控端已封装 `mainHandler.post + CountDownLatch`，禁止在 ACI Binder 线程直接调用 WebView。

### 13.2 调用示例（控制端视角）

```kotlin
// 检索「如何部署 ACI 受控端」
val resp = stub.call(
    ACIRequest.Builder()
        .capability("browser_search")
        .param("query", "如何部署 ACI 受控端")
        .param("engine", "bing")
        .build()
)
val text  = resp.result.getString("text")   // 结果页正文（已截断到 ~15 万字符）
val links = resp.result.getString("links")  // JSON 数组：[{text,href}]

// 在已打开的页面执行 JS，抽取所有图片 src
val js = """JSON.stringify(
    Array.from(document.images).map { i -> i.src }
)"""
val r2 = stub.call(
    ACIRequest.Builder().capability("browser_script").param("code", js).build()
)
val imgs = r2.result.getString("result")
```

### 13.3 关于 `browser_read` 的 Binder 1MB 溢出修复（v1.0.8）

AIDL Binder 单次事务上限约 **1MB**。早期实现直接 `putResult("html", fullHtml)` 传完整大页面 HTML，会在 `transact` 时抛 `TransactionTooLargeException` 导致调用失败。

**修复方案（混合绕过，向后兼容）：**
- 受控端 `handleRead` **始终**返回「安全截断的 html 字符串」（≤150,000 字符），永不过 Binder；
- 当原始 HTML 超过阈值时，额外用 `GZIPOutputStream` 压成 `byte[]`，经 `html_gz` 回传；控制端 `QuroAciCallTool` 检测到 `html_gz` 即 `GZIPInputStream` 解压，还原完整 HTML 交给 LLM；
- gzip 后若仍 >900KB，放弃 `html_gz`，仅返回截断预览（极端大页面兜底）。

> 这是**受控端 + 控制端**双侧改动：受控端负责截断/压缩，控制端负责解压还原。第三方受控端若也要回传大结果，建议复用同一模式。

---

---

## 14. LAN 控制台 / 控制台后台接入 ZorvAI

> 本节对应主程序「设置 → ACI 管理中心 → ACI 被控方接入手册」中的「控制台后台（ConsoleBackend）开发接入 ZorvAI」一节，面向**想让自己的受控端在 Zorv AI 里显示一个可交互控制台**的开发者。

Zorv AI 的控制台 UI 采用 **SDUI（Server-Driven UI）** 模式：受控端只暴露两个能力——`console_ui`（返回界面快照 JSON）与 `console_action`（处理用户操作），控制端负责**纯本地渲染**，**不经过任何网络**（同设备 Binder 调用，WiFi / 移动网络均不影响）。早期版本曾误建「app 自连 `127.0.0.1` 环回 HTTP 控制台」（`lanui` 模块），已于 2026-07-31 彻底移除；现行方案即本节所述。

### 14.1 接入方式

受控端二选一：

- **方式 A（推荐）**：实现 `AciConsoleContract` 接口（`buildUiSnapshot(): JSONObject` + `applyAction(action, payload): JSONObject`），并注册 `console_ui` / `console_action` 两个 ACI 能力，在 `onCall` 中转发到你的 `ConsoleBackend` 实现。
- **方式 B（极简）**：不实现接口，直接在 `onCall` 里处理 `console_ui` / `console_action` 两个 capability，返回约定的 JSON。

### 14.2 快照 JSON Schema（`console_ui` 返回）

```json
{
  "title": "受控端控制台",
  "subtitle": "可选副标题",
  "updatedAt": "2026-07-31T12:00:00",
  "components": [
    { "type": "heading", "text": "标题" },
    { "type": "text", "text": "一段说明文字" },
    { "type": "card", "title": "卡片", "body": "卡片内容" },
    { "type": "button", "key": "reset", "text": "重置" },
    { "type": "divider" },
    { "type": "spacer", "height": 8 },
    { "type": "input", "key": "note", "hint": "请输入备注", "value": "" },
    { "type": "listitem", "title": "项", "subtitle": "子项" }
  ]
}
```

组件类型：`heading` / `text` / `card` / `button` / `divider` / `spacer` / `input` / `listitem`。

### 14.3 动作契约（`console_action` 入参）

- **按钮点击**：`payload` 为空 `{}`（或仅含 `action=按钮 key`）；受控端按 `key` 执行对应逻辑。
- **输入框提交**：`payload` 为 `{ "<input 的 key>": "<用户输入值>" }`，例如 `{ "note": "hello" }`。

> ⚠️ **兼容性铁律**：控制端（Zorv AI 主程序）的 Compose 控制台在输入框提交时回传 `{ "value": "...", "key": "..." }`，而受控端 `ConsoleBackend.applyAction` 是按**输入框 key** 读取参数的（`p.optString(key)`）。受控端务必以 `key` 为准读取入参，不要依赖 `value` 字段，否则会收不到输入。

### 14.4 最小 Kotlin 示例

```kotlin
// ConsoleBackend.kt（受控端内部）
object ConsoleBackend {
    var count = 0
    var lastNote = ""
    fun buildUiSnapshot(): JSONObject = JSONObject().apply {
        put("title", "我的控制台")
        put("components", JSONArray().apply {
            put(JSONObject().put("type", "heading").put("text", "计数器"))
            put(JSONObject().put("type", "text").put("text", "当前：$count"))
            put(JSONObject().put("type", "button").put("key", "inc").put("text", "加一"))
            put(JSONObject().put("type", "input").put("key", "note").put("hint", "备注"))
        })
    }
    fun applyAction(action: String, payload: JSONObject): JSONObject {
        when (action) {
            "inc" -> count++
            "note" -> lastNote = payload.optString("note")
        }
        return buildUiSnapshot()
    }
}
```

### 14.5 复用 `consolekit`（可选）

Zorv AI 主程序内置 `consolekit` 包，受控端或控制端都可复用：

- `LocalConsoleEndpoint`：同进程直连 `ConsoleBackend`；
- `RemoteConsoleEndpoint`：跨进程 `bindService` 到目标包名（可替换 `targetPackage`）；
- `AciConsoleRenderer`：纯 `View` 实现的 SDUI 渲染器（不依赖 Compose）；
- `ManualConsolePanel`：单线程 worker，避免 WebView 主线程 `evaluateJavascript` 死锁。

### 14.6 控制端如何驱动

控制端 `QuroAciCenterScreen` 按 capability id `console_ui` 发现「打开控制台」入口；点击后通过 Binder 拉取 `console_ui` 快照，用本地 `AciConsoleScreen`（`core/aci` 包）渲染；按钮 / 输入经 `console_action` 回传并刷新快照。**全程纯接线、零侵入、零网络。**

---

> 本手册由软件工坊基于 `aci-core` 源码与 Zorv AI 真实接入经验整理。协议细节以 [ACI_PROTOCOL.md](https://github.com/Quor-a/ZorvAI)（开源分支）为准。
