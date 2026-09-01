# Zorv AI · ACI 开发者手册（Agent Capability Interface）

> 版本：v1.0.14（能力清单同步 ZorvAI 浏览器 v1.0.14；新增 §15 HTTP 传输 / http_request · 局域网明文）→ **文档已同步至 v1.0.63** ｜ 适用 SDK：`aidl-aci-core`（原 `aci-core`，v1.0.26 重命名落地）｜ 最后更新：2026-09-02（v1.0.75 新增 §25 主程序 LLM 工具：Python↔浏览器会话桥 / 抓包记全 / 特权终端 `priv_exec` / ADB 终端 `adb_term`）
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
│  QuroAidlAciManager           │  ◀── ACIResponse ───────     │  BaseACIService 子类      │
│  - discover() 发现        │                              │  - onCreateCapabilities   │
│  - bind() 绑定            │  ─── ACTION_WAKE 广播 ──▶    │  - onCall() 处理          │
│  - getCapabilities() 取清单│  (唤醒 stopped 进程)         │  - onCheckPermission()    │
└──────────────────────────┘                              └──────────────────────────┘
```

| 角色 | 职责 | 关键类 |
|------|------|--------|
| **控制端** | 扫描、绑定、取能力清单、发起调用、把结果喂给 LLM | `QuroAidlAciManager` + `aci-core` 的 `IACIService` 桩 |
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

> ⚠️ **权限定义权归控制端（Zorv AI）**。受控端**绝不能**用 `<permission>` 定义 `ai.aci.permission.*`——控制端 ZorvAI 已定义这些权限，受控端只需**引用**（`uses-permission`）。受控端若也定义同名权限，会因「同名 + 异签名 + 双方都定义」触发 `INSTALL_FAILED_DUPLICATE_PERMISSION` 冲突（详见 §14.1）。受控端只需**声明 `<uses-permission>` 引用 + 在 service 上用 `android:permission` 做第一层 Manifest 鉴权**，并**声明 `<queries>` 让系统能发现自身**（Android 11+ 包可见性）：

**权限架构说明：**
- **主应用（控制方）**：声明 `<permission>` 定义权限（使用自定义证书签名）
- **第三方受控端**：只使用 `<uses-permission>` 引用权限（使用 debug 证书签名）
- **ACI核心库 (`aci-core`)**：不包含任何权限声明，只提供 AIDL 接口和基础类

```xml
<manifest ...
    xmlns:tools="http://schemas.android.com/tools">

    <!-- ❌ 禁止：受控端不要 <permission> 定义 ai.aci.permission.*（定义权归控制端 ZorvAI） -->
    <!-- ✅ 仅引用：用 uses-permission 引用控制端已定义的权限 -->
    <uses-permission android:name="ai.aci.permission.CALL" />
    <uses-permission android:name="ai.aci.permission.DISCOVER" />
    <uses-permission android:name="ai.aci.permission.CALL_DANGEROUS" />
    <uses-permission android:name="android.permission.INTERNET" />

    <!-- 若 aci-core 库模块带入了 <permission> 定义，用 tools:node="remove" 在消费端剥除，
         否则合并后会重复定义（同名异签名冲突）。下面三条只为「删除」，不是定义。 -->
    <permission android:name="ai.aci.permission.CALL"           tools:node="remove" />
    <permission android:name="ai.aci.permission.CALL_DANGEROUS" tools:node="remove" />
    <permission android:name="ai.aci.permission.DISCOVER"      tools:node="remove" />

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
            android:permission="ai.aci.permission.CALL">   <!-- 第一层 Manifest 鉴权：引用控制端定义的权限 -->
            <intent-filter>
                <action android:name="ai.aci.core.ACTION_BIND" />
            </intent-filter>
        </service>

        <!-- stopped-state 唤醒接收器（关键，见 4.6） -->
        <receiver
            android:name=".QuroAidlAciWakeReceiver"
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
class QuroAidlAciWakeReceiver : BroadcastReceiver() {
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

控制端（`QuroAidlAciManager.bindWithWake()`）已内置该逻辑：先发唤醒广播，再 `bindService(BIND_AUTO_CREATE)`。

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

1. **Android Manifest 权限（第一层）**：受控端 Service 上的 `android:permission="ai.aci.permission.CALL"` 是**引用**控制端 ZorvAI 已定义的权限，作为第一层 Manifest 鉴权；**权限定义权归控制端，受控端只用 `<uses-permission>` 引用，绝不自行 `<permission>` 定义**（否则同名异签名冲突，见 §16）。调用方（控制端）须声明 `<uses-permission android:name="ai.aci.permission.CALL" />`。
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

控制端流程（以 `QuroAidlAciManager` 为参考）：

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
- **控制端**：若需发现任意受控端，也要在自身 `<queries>` 声明 `ACTION_BIND`（本项目 `QuroAidlAciManager` 已处理）。
- **权限**：受控端**不要定义** `ai.aci.permission.*`，只声明 `<uses-permission>` 引用控制端已定义的权限（定义权归控制端 ZorvAI）；若 `aci-core` 库带入 `<permission>`，用 `tools:node="remove"` 剥除。二者权限名必须一致（受控端引用的是控制端定义的同名权限）。详见 §16。

---

## 11. 真实踩坑与最佳实践

| 坑 | 现象 | 修复 |
|----|------|------|
| `Capability.create(id, "1.0")` | LLM 看不到能力描述，控制端「能力(0)」 | 第 2 参传**自然语言描述**，不是版本号 |
| 漏写 `<queries>` | 发现为空 / 绑定失败（Android 11+） | 受控端 Manifest 补 `<queries>` |
| `onCreateCapabilities` 抛异常 | Service 启动即崩溃、无能力 | `onCreate()` 用 try-catch 包 `super.onCreate()` |
| stopped-state 不唤醒 | 之前能绑、更新后不能 | 控制端 `bindWithWake`（先发 `ACTION_WAKE` 广播） |
| 受控端定义 `ai.aci.permission.*` | `INSTALL_FAILED_CONFLICTING_PERMISSION` / 同名异签名冲突 | 受控端**不要定义**，只 `uses-permission` 引用；库带入的用 `tools:node="remove"` 剥除（见 §16） |
| `browser_read` 直接传完整大 HTML | `TransactionTooLargeException`，调用失败 | 截断 ≤15 万字符 + 大页面 gzip(byte[]) 经 `html_gz` 回传，控制端解压还原（见 §13.3） |

---

## 12. 协议版本与兼容

| 协议版本 | minSdk | 关键能力 |
|----------|--------|----------|
| 1.0 | API 24 | 同步 `call` / `getCapabilities` / `ping` |
| 1.1 | API 26 | 异步 `callAsync` + `IACICallback` |
| 1.1+（已落地） | 控制端 `QuroAidlAciManager` + `aidl-aci-core` | LocalSocket 抽象命名空间高速通道 + 主动探测（`fetchCapabilities` 绑定后 `AidlAciLocalSocketTransport.probe()` 仅 connect 不发包，直接决定首调用走 LocalSocket 还是回落 AIDL） |

`aci-core` v1.0.x 同时实现 1.0 + 1.1（`call` 与 `callAsync` 均可用），`Capability.version` 固定为 `"1.0"`。

---

## 13. 官方受控端能力清单（ZorvAI 浏览器）

ZorvAI 浏览器（受控端，与主程序同源）作为官方参考实现，已向控制端暴露以下 38 个能力（13 基础 + 7 agentic + 2 资源/分享 + 6 完整方案 + 1 虚拟鼠标 + 1 HTTP 传输 + 4 共享工作空间 + 2 语义点击 + 1 语义流 + 1 Uinput 桥接）。控制端 `QuroAidlAciManager` 会把它们喂给 LLM，由 LLM 自动决定调用哪个、传什么参数——**控制端协议零改动**，新增能力对 LLM 完全透明。

> 注意：主程序自身（`QuroMainAciService`）也作为受控端额外暴露 `http_request`（扩展版，含 `tls_verify`/`tls_ca_pem`/`$vault` 凭证引用）与 `aci_protocol` 两个能力，详见 §18。本 §13 仅列**受控浏览器**能力。

### 13.1 能力总览（共 38 项：13 基础 + 7 agentic + 2 资源/分享 + 6 完整方案 + 1 虚拟鼠标 + 1 HTTP 传输 + 4 共享工作空间 + 2 语义点击 + 1 语义流 + 1 Uinput 桥接）

**基础能力（13）**

| 能力 id | 入参 | 出参 | 说明 |
|---------|------|------|------|
| `browser_open` | `url`(string, 必填) / `title`(string, 可选) | `launched`(boolean) + `ready`(boolean) + `url`(string) | 打开并导航到指定网址；**v1.0.12 回归修复**：先登记多标签、再启动 Activity、等待 WebView 注册与页面 `onPageFinished` 完成才返回 `ready`（约 15s 上限，不卡死 binder） |
| `browser_read` | `mode`(string, 可选：full 默认 / clean) | `url` / `title` / `html`(string) + `truncated`(boolean)；大页面额外 `html_gz`(byte[]) + `html_len`(int)；`mode=clean` 额外返回 `cleaned_html`(精简 DOM：去脚本样式、可交互元素打 `data-ai-id`、标视口) | 读取当前页 HTML。**v1.0.8 修复 Binder ~1MB 溢出**（见 13.3） |
| `browser_crawl` | — | `url` / `title` / `text` / `links`(string) + `link_count`(string) + `truncated`(boolean) | 抓取结构化正文（取 `article/main/body` 的 innerText）+ 出站链接 `[{text,href}]` |
| `browser_search` | `query`(string, 必填) / `engine`(string, 可选：bing/google/baidu/ddg，默认 bing) | `query` / `engine` / `url` / `title` / `text` / `links`(string) + `truncated`(boolean) | 用搜索引擎检索关键词，返回结果页结构化数据 |
| `browser_script` | `code`(string, 必填) | `result`(string) + `truncated`(boolean) | 在当前页面执行任意 JavaScript 并返回结果（核心能力，等价于给 AI 一个完整浏览器控制台） |
| `browser_list` | — | `tabs`(string) | 列出当前打开的浏览器标签页 |
| `browser_info` | — | `package` / `versionName` / `versionCode`(string) | 查询受控端包名与版本信息 |
| `browser_find` | `text`(string, 必填) | `count`(int) | 页面内查找文本并高亮，返回命中数 |
| `browser_nav` | `action`(string, 必填：back / forward / reload) | `ok`(boolean) | 导航控制（WebView 操作已主线程安全封装） |
| `browser_screenshot` | — | `path`(string) | 截当前可视区域存 PNG，返回文件路径（无需存储权限） |
| `browser_capture` | `action`(string, 必填：list / clear / enable / disable) | `requests`(string) | 抓包：请求侧拦截 + 响应侧记录，**完整链路 = 请求体(request body) + 响应头/状态码/响应体(response headers / status / body)**；返回请求 URL/方法/请求头/是否主框架，用于调试与审计 |
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

**第四波增强（2 · 虚拟鼠标 + HTTP 传输）**

| 能力 id | 入参 | 出参 | 说明 |
|---------|------|------|------|
| `browser_mouse` | `action`(string, 必填：move/click/dblclick/right/down/up/drag/scroll) / `x`(int, 必填 屏幕绝对像素 X) / `y`(int, 必填 屏幕绝对像素 Y) / `dx`(int, 可选) / `dy`(int, 可选) / `button`(string, 可选：left 默认/right/middle) | `ok`(boolean) + `action` + `x` + `y` | 在页面屏幕坐标模拟鼠标动作；后端按 WebView 在屏位置自动换算视图坐标后派发 `MotionEvent`（主线程 `dispatchTouchEvent` / `dispatchGenericMotionEvent`）。覆盖无稳定ID、无 CSS 选择器的元素与画布交互，与 `browser_action`(id/selector) 构成「坐标 + 语义」双通道。click/drag/dblclick 拟人化：贝塞尔轨迹 + 亚像素高斯抖动 + 可变压力(0.4~0.62) + 可变时序，规避机械直线恒压检测。注：系统 WebView 将触摸事件按触摸处理，右键为尽力而为 |
| `http_request` | `url`(string, 必填) / `method`(string, 可选：GET/POST/PUT/DELETE/PATCH/HEAD，默认 GET) / `headers`(string, 可选 JSON) / `body`(string, 可选 原样发送) | `status_code`(int) + `response_headers`(string JSON) + `response_body`(string) + `truncated`(boolean)；大响应体附 `response_body_gz`(byte[]) | HTTP 传输：经 ACI 让受控浏览器代为发起任意 HTTP 请求。**重点支持同网段 LAN 明文**（http://192.168.x.x、http://10.x、*.local mDNS），访问路由器/NAS/智能家居/私有 API 等局域网设备；受控浏览器已放开局域网明文（networkSecurityConfig base-config 整体允许明文），无需因公网明文限制而犹豫，公网请求仍走 HTTPS。 响应体 >15 万字符自动 gzip（response_body_gz），控制端解压还原 |

**第五波增强（2 · 语义点击闭环）**

| 能力 id | 入参 | 出参 | 说明 |
|---------|------|------|------|
| `ui_snapshot` | — | `nodes`(string_array，每项 `text\|resId\|left,top,right,bottom` 屏幕像素整数) | 当前可视区域元素快照（屏幕坐标）：遍历页面可交互/可见元素，按 WebView 在屏位置 + CSS→屏幕缩放换算成**屏幕绝对像素**返回；视口外元素自动跳过。供控制端 `clickText`/`clickResourceId` 解析锚点坐标；与 `tap` 同一坐标空间（屏幕绝对像素），**无需 AccessibilityService** |
| `tap` | `x`(int, 必填 屏幕绝对像素 X) / `y`(int, 必填 屏幕绝对像素 Y) | `x`(int) + `y`(int) | 在屏幕坐标模拟单击：复用 `browser_mouse` 的坐标换算与视图级 `dispatchTouchEvent` 派发（受控端无系统特权也能用），与 `ui_snapshot` 形成「像人一样点页面」的感知-执行闭环。tap 单击拟人化：贝塞尔逼近 + 亚像素抖动 + 可变压力(0.4~0.62) + 可变时序。控制端语义点击 `clickText`/`clickResourceId` 会自动先 `ui_snapshot` 取节点、解析锚点、再 `tap` |

**第七波增强（1 · 语义流差分流 `ui_diff`）**

| 能力 id | 入参 | 出参 | 说明 |
|---------|------|------|------|
| `ui_diff` | — | `nodes`(string_array，格式 `text\|resId\|left,top,right,bottom\|reliability`) + `count`(int) + `added`(int) + `removed`(int) + `modified`(int) + `added_nodes`(string_array) + `removed_nodes`(string_array) | 语义流差分流：返回当前可视区域元素（每项附**锚点可靠性 reliability** 评分——多次出现累加饱和、久未出现衰减）与相对上次快照的 `added`/`removed`/`modified` 节点。**事件驱动感知**：控制端据 `added`/`removed` 判断弹窗出现 / 页面变化，无需轮询全量 `ui_snapshot`。`reliability` 高（≥0.75）的锚点更稳定，语义点击优先选用 |

> 注：`ui_diff` 在受控浏览器内维护上一次快照与锚点可靠性表（进程内内存，非持久化）；首次调用 `added`=全量、`reliability` 从 0 起累加。它与 `ui_snapshot`（全量快照）共用同一套元素抓取与屏幕坐标换算逻辑。

**第八波增强（1 · Uinput 伪输入设备桥接 `inject_touch`）**

| 能力 id | 入参 | 出参 | 说明 |
|---------|------|------|------|
| `inject_touch` | `action`(string, 必填：down/move/up/click/drag/dblclick，默认 click) / `x`(int, 屏幕像素) / `y`(int, 屏幕像素) / `dx`(int, 可选 drag 终点相对偏移) / `dy`(int, 可选) / `slot`(int, 可选 多点触控 slot，默认 0) / `tracking_id`(int, 可选 MT tracking id，默认 0) / `pressure`(int, 可选 0~255，默认随拟人化抖动) / `major`(int, 可选 触摸主轴 major，默认 8) | `ok`(boolean) + `method`(string, 恒为 `uinput`) + `action`(string) + `events`(int)；不可用附 `error` | 设备级真实触摸注入（**L3 事件面** / 伪输入设备 Uinput）：把语义动作写成内核 `input_event`，经 `/dev/uinput` 输出，作用于**整台设备**（不限于浏览器视图）。与控制面 AIDL / LocalSocket（**L1 控制面**）经 L4 编排协作：信令走 AIDL、内核事件走 Uinput，**二者不是一条线**。仅 root 或系统签名（priv-app + SELinux 放行 `uinput_device`）构建真实生效；普通分发版 `nativeOpen` 失败，本能力明确返回「需 root / 系统签名」而非假装成功（不杜撰功能）。坐标空间 = 屏幕像素（与虚拟设备 ABS 轴范围 1:1）；click/drag/dblclick 做拟人化合成（贝塞尔逼近 + 亚像素高斯抖动 + 可变压力 + 可变时序） |

> 注：`inject_touch` 与 `tap`/`browser_mouse` 是**两套独立通路**：后者在 WebView 视图内派发 `MotionEvent`（无需系统特权，但作用域仅限浏览器自身）；前者向系统注入真实内核触摸事件（需特权，作用域是整个设备）。二者互补而非替代——前者填补「无视图锚点、需系统级真实触摸」的场景（如跨 App 操作、绕过视图级拦截）。原生实现见 `aidl-aci-browser/src/main/cpp/uinput_bridge.c` + `UinputBridge.kt`，由 `build.gradle.kts` 的 `externalNativeBuild` 编入 `libuinput_bridge.so`（arm64-v8a）。

> 注：`browser_action` 在第三波新增 `selector`(CSS 选择器) 参数（与 `id` 二选一，优先级低于 `id`），可直接用选择器定位操作，免去先 `browser_elements` 注入稳定 ID。

**第六波增强（4 · 共享工作空间 `workspace_*`）**

> 这组能力让**授权调用方（主程序 ZorvAI）经 ACI 读写受控浏览器沙箱内的 `filesDir/aci_workspace` 目录**，实现「跨 App 文件/状态中转」。严格说它不是操作系统级的共享文件系统——文件仍在受控浏览器自己的应用沙箱里，主程序是**通过 `aci_call` 调浏览器**来读写的（见 `QuroControlledAidlAciService.kt:569` 注释）。`name` 不含路径、禁止 `../` 越权。

| 能力 id | 入参 | 出参 | 说明 |
|---------|------|------|------|
| `workspace_list` | — | `entries`(string JSON `[{name,size,is_dir,mtime}]`) + `count`(int) + `dir`(string, 仅诊断) | 列出共享工作空间文件清单 |
| `workspace_read` | `name`(string, 必填) | `name` / `content`(string, 文本) / `content_b64`(string, 二进制) / `is_binary`(boolean) / `size`(int) | 读取一个文件；文本返回 `content`，二进制返回 `content_b64` |
| `workspace_write` | `name`(string, 必填) / `content`(string, 可选) / `content_b64`(string, 可选) | `written`(boolean) + `bytes`(int) | 写入一个文件；`content` 与 `content_b64` 二选一 |
| `workspace_delete` | `name`(string, 必填) | `deleted`(boolean) | 删除一个文件 |

> 典型用法：AI 在主程序侧生成脚本/数据 → `aci_call("com.ai.assistance.quro.browser","workspace_write",{name,content})` 放入工作空间 → 受控浏览器 `browser_script` 读取并执行 → 结果 `workspace_write` 回写 → 主程序 `workspace_read` 取回。

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
- 当原始 HTML 超过阈值时，额外用 `GZIPOutputStream` 压成 `byte[]`，经 `html_gz` 回传；控制端 `QuroAidlAciCallTool` 检测到 `html_gz` 即 `GZIPInputStream` 解压，还原完整 HTML 交给 LLM；
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

控制端 `QuroAidlAciCenterScreen` 按 capability id `console_ui` 发现「打开控制台」入口；点击后通过 Binder 拉取 `console_ui` 快照，用本地 `AciConsoleScreen`（`core/aci` 包）渲染；按钮 / 输入经 `console_action` 回传并刷新快照。**全程纯接线、零侵入、零网络。**

---

## 15. HTTP 传输能力（http_request · 局域网/本地组网）

> 对应主程序「设置 → ACI 管理中心 → ACI 被控方接入手册」中的「HTTP 传输（http_request · 局域网/本地组网）」一节，以及主程序系统提示词对 ACI 的 HTTP/LAN 说明。面向**想让自己的受控端也提供 HTTP 传输能力**的开发者。

受控浏览器新增 `http_request` 能力，让 AI 能经 ACI 让浏览器发起任意 HTTP 请求，重点是「本地组网（相同网络下）」：

### 15.1 LAN 明文支持（核心）

Android 9（API 28）+ 默认禁止明文 HTTP（`cleartextTrafficPermitted=false`），`targetSdk≥28` 的 App 直接访问 `http://` 会被系统拦截（`ERR_CLEARTEXT_NOT_PERMITTED`）。受控浏览器通过 `res/xml/network_security_config.xml` 解除该限制：

- `base-config` 的 `cleartextTrafficPermitted="true"`：**整体放开明文**（含私有网段 192.168.0.0/16、10.0.0.0/8）；
- `domain-config` 对 `localhost` / `127.0.0.1` / `10.0.2.2`（模拟器回环）/ `local`（mDNS）单独放开，便于本机与局域网设备互访。

### 15.2 平台限制（重要）

Android NSC **只能按域名或整体 base-config 放开明文，无法按「私有网段」写白名单**（如不能写 `192.168.0.0/16` 一条规则）。要支持 LAN 明文，只能把 `base-config` 整体放开——这是平台限制，不是代码缺陷。受控浏览器定位为本地调试/自动化工具，明文风险由使用者在可信 LAN 内自行把控。

### 15.3 契约（参数 / 返回）

受控端 `onCall("http_request")` 透传 URL 给 OkHttp（无 scheme 校验，明文是否通完全由 NSC 决定）：

| 项 | 说明 |
|----|------|
| 入参 `url` | 目标 URL（必填） |
| 入参 `method` | HTTP 方法，默认 GET；支持 GET/POST/PUT/DELETE/PATCH/HEAD 及任意自定义 |
| 入参 `headers` | 请求头 JSON 对象字符串，如 `{"Authorization":"Bearer x"}` |
| 入参 `body` | 请求体（原样发送，字符串） |
| 返回 `status_code` | HTTP 响应状态码（int） |
| 返回 `response_headers` | 响应头 JSON 对象 |
| 返回 `response_body` | 响应体（字符串；>15 万字符截断，附 `response_body_gz`） |
| 返回 `truncated` | 响应体是否被截断（boolean） |
| 返回 `response_body_gz` | 大响应体 gzip(byte[])，控制端解压还原（Binder ≤900KB 才回传） |

### 15.4 控制端如何渲染

主程序 `QuroAidlAciTools.renderHttpResult` 检测到 `response_body_gz` 即 GZIPInputStream 解压，把「状态码 / 响应头 / 响应体」整理成干净文本喂给 LLM；无 gzip 时直接用截断预览。

### 15.5 安全权衡

明文放开后，公网明文 HTTP（`http://` 公网域名）也会一并放行。请**仅在可信局域网内**用 `http_request` 访问内网地址，不要经它请求公网明文站点；远程生产通信（HTTPS）不受影响。

---

## 16. 受控端自定义权限「签名冲突」真实案例（必读）

> 本节来自一个第三方受控端（WorkflowACI，`com.workflowaci`）接入 Zorv AI 时踩到的**真实生产 bug**，已修复并验证。凡是自己写受控端的人，务必先读本节再写 Manifest。

### 16.1 现象

- 安装 / 覆盖安装受控端时，因自定义权限 `ai.aci.permission.*` 的「同名 + 异签名 + 双方都定义」冲突，触发 `INSTALL_FAILED_CONFLICTING_PERMISSION`；或旧版（Debug 签名）与新版（Release 签名）互更时触发 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
- 根因一句话：**受控端不该定义 `ai.aci.permission.*`，定义权归控制端 ZorvAI**；受控端却用 `<permission>` 定义了同名权限，且与控制端签名不同。

### 16.2 根因

Android 的自定义权限由「权限名 + 定义者签名」唯一确定。当两个 App 都 `<permission>` 定义**同名**自定义权限、且**签名不同**时，后安装的那个会因权限归属冲突而安装失败——**与 `protectionLevel`（normal / dangerous）无关**。

**ACI 核心库 (`aidl-aci-core`) 当前状态：**
- ✅ **不包含任何 `<permission>` 定义**（已移除，只保留注释说明）
- ✅ **不包含主应用 (`com.ai.assistance.quro`) 的硬编码引用**
- ✅ **只提供 AIDL 接口和基础类**（`BaseAidlAciService`, `Capability`, `AidlAciRequest/Response`）

**历史问题：**
早期版本的 `aci-core` 库模块（`aci-core/src/main/AndroidManifest.xml`）可能带入了 `<permission>` 定义；若消费端 Manifest 不做 `tools:node="remove"` 剥除，合并后受控端 APK 里就**重复定义**了这些权限，同样会和控制端冲突。

### 16.3 正确做法（三句话）

1. **定义权归控制端**：`ai.aci.permission.*` 只由 ZorvAI（控制端）定义，受控端**永不 `<permission>` 定义**。
2. **受控端只引用**：用 `<uses-permission android:name="ai.aci.permission.*" />` 引用控制端已定义的权限；受控端 Service 上的 `android:permission="ai.aci.permission.CALL"` **保留**——它是第一层 Manifest 鉴权，引用的是控制端定义的权限，逻辑有效。
3. **剥除库带入的定义**：若 `aci-core` 库带入了 `<permission>`，在受控端 Manifest 用 `<permission android:name="ai.aci.permission.*" tools:node="remove" />` 三条指令剥除（需在 `<manifest>` 声明 `xmlns:tools="http://schemas.android.com/tools"`）。

修复后，合并 Manifest 里**只剩 AGP 自动生成的 `com.workflowaci.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`**（带本应用包名前缀，与 ZorvAI 不同名，不冲突），`ai.aci.permission.*` 全部退化为纯 `uses-permission` 引用。

### 16.4 发布前用 aapt2 校验（必做）

```bash
# 预期：只出现本应用命名空间的 DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION，
#       不得出现任何 ai.aci.permission.* 的 <permission> 定义节点
aapt2 dump xmltree --file AndroidManifest.xml \
  app/build/outputs/apk/release/app-release.apk | grep -E "E: permission"

# 预期：仅 uses-permission 行，无 E: permission 行
aapt2 dump xmltree --file AndroidManifest.xml \
  app/build/outputs/apk/release/app-release.apk | grep -i "ai.aci"
```

若 `grep -E "E: permission"` 仍打出 `ai.aci.permission.*`，说明库带入的定义没剥干净，回去补 `tools:node="remove"`。

### 16.5 安装 / 发布注意事项

- **升级前先卸载旧版受控端**：旧版定义了冲突权限；且若旧版是 Debug 签名、新版是 Release 签名，异签名覆盖安装会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。彻底卸载最稳妥。
- **同签名可覆盖**：Release→Release（同一 `release-key.jks`）可正常覆盖更新；Debug→Release 异签名必须卸载后重装。
- **ACI 生效前提**：控制端 ZorvAI 已安装并授权；受控端通过 `uses-permission` 引用其权限，绑定 service 时由系统校验调用方是否持有该权限。

### 16.6 给开发者文档的更新要点（摘要）

1) 自定义权限「同名 + 异签名 + 双方都定义」必冲突，与 `protectionLevel` 无关。
2) ACI 受控端不应定义 `ai.aci.permission.*`，定义权归控制端；受控端仅 `uses-permission` 引用 + service 上 `android:permission` 校验。
3) 库模块带入的 `<permission>` 必须用 `tools:node="remove"` 在消费端 Manifest 剥除，否则合并后重复定义。
4) 发布前用 `aapt2` 校验合并 Manifest，确认无 `ai.aci.permission.*` 定义节点。
5) 版本升级注意 Debug/Release 签名差异，跨签名安装需先卸载。

---

## 17. v1.0.25 / v1.0.26 ACI 框架增强（已落地，控制端 + 受控端）

> 本节补记 v1.0.25 落地的框架增强与 v1.0.26 的健壮性修复。完整技术说明见仓库 [README §「v1.0.25 · ACI 升级功能技术说明」](../README.md)。
> **重要兼容性提示**：v1.0.26 将 Java 包 / 类名由 `aci` 重命名为 `aidlaci`（见 §17.6），但 **Android 运行时 Intent Action 与权限字符串（`ai.aci.core.ACTION_BIND` / `ai.aci.core.ACTION_WAKE` / `ai.aci.permission.*`）属于线缆协议、保持不变**——已发布的受控端 App（含独立仓库 ZorvBrowser）无需修改即可继续连通。

### 17.1 callId 链路追踪（可观测性基座）
`AidlAciRequest` / `AidlAciResponse` 携带 `callId` 字段；受控端在成功 / 鉴权失败 / 能力缺失 / 异步各返回路径统一回显请求侧 `callId`；控制端每次 `call()` 生成 UUID 并随 LocalSocket / AIDL 双通道回填。一次 AI 操作（发现 → 绑定 → 调用 → 结果）可被完整串成调用链，便于排障与审计。

### 17.2 LocalSocket 抽象命名空间高速通道 + 主动探测
`aidl-aci-core` 的 `AidlAciLocalSocketTransport` 提供基于 Linux 抽象命名空间 LocalSocket 的高速通道，作为 AIDL Binder 的增强型替代传输；失败 / 不可用时由调用路径自动回落 AIDL。
控制端 `QuroAidlAciManager.fetchCapabilities()` 在绑定后主动 `probe(endpoint)`（仅 connect 不发包，无副作用），直接决定首调用走 LocalSocket 还是回落 AIDL，**不必等到第一次调用失败才切换**。

### 17.3 自愈：健康看护 + 指数退避重绑
控制端 `startHealthWatch(10s)` 定时 `healthCheck()`（`ping`）；ping 失败即 `ensureBound()`（含 wake 广播 + `startService` + 重绑）。`scheduleRebind` 改为 **800ms → … → 8s 指数退避**，成功绑定即清零，避免对不可达端高频空转。

### 17.4 会话 trace 可观测性面板
控制端维护环形 `traceQueue`（最近 50 条 `AciCallTrace{ts, callId, target, capability, transport, code, success, latencyMs}`），并提供 `getTrace()` / `clearTrace()` / `socketStatus(pkg)`。ACI 管理中心「诊断」面板据此展示每次调用的传输路径（LocalSocket / AIDL）、耗时与成功率。

### 17.5 onServiceDisconnected NPE 修复（v1.0.26）
控制端 `QuroAidlAciManager.onServiceDisconnected(...)` 在断连时清理 `socketOk` 状态。原写法 `socketOk[packageName] = null` 对 `ConcurrentHashMap` 非法（禁止 null value），会在后续读取该探测状态时抛 `NullPointerException`；v1.0.26 改为 `socketOk.remove(packageName)`，彻底消除悬空引用导致的断连后 NPE。

### 17.6 框架重命名落地（v1.0.26，仅标识符、不动线缆协议）
Java 包 / 模块 / 类名由 `aci` 重命名为 `aidlaci`（去第三方方案归因，功能与协议字段不变）：

| 旧（v1.0.25 及之前） | 新（v1.0.26 起） |
|---|---|
| 模块 `aci-core` | 模块 `aidl-aci-core`（受控端 `aci-browser` → `aidl-aci-browser`） |
| 包 `ai.aci.core` | 包 `ai.aidl.aci.core` |
| `BaseACIService` | `BaseAidlAciService` |
| `IACIService` / `IACICallback` | `IAidlAciService` / `IAidlAciCallback` |
| `ACIRequest` / `ACIResponse` | `AidlAciRequest` / `AidlAciResponse` |
| `QuroControlledAciService` | `QuroControlledAidlAciService` |

**不变（线缆协议，请勿改）**：Android Manifest 中的 `android:name="ai.aci.core.ACTION_BIND"` / `ACTION_WAKE` 与权限 `ai.aci.permission.CALL` / `CALL_DANGEROUS` / `DISCOVER` 仍沿用旧字符串——它们是与已发布受控端协商的契约，改名会破坏连通性。

---

---

## 18. 主程序自身作为受控端（QuroMainAciService）

主程序 QuroAI 既是 **控制端**（`QuroAidlAciManager`），也通过 `QuroMainAciService` 同时充当 **受控端**。这让 AI 可以经 `aci_call("com.ai.assistance.quro", "http_request", ...)` 让主程序代为发起任意 HTTP 请求——主程序不再依赖受控浏览器也能做 HTTP 传输。

### 18.1 暴露的能力

| 能力 id | 入参 | 出参 | 说明 |
|---------|------|------|------|
| `http_request` | `url`*(string) / `method`(string, 默认 GET) / `headers`(string JSON) / `body`(string) / **`tls_verify`**(string, 默认 `"true"`) / **`tls_ca_pem`**(string, 可选) | `status_code`(int) + `response_headers`(string JSON) + `response_body`(string) + `truncated`(boolean) + （大响应）`response_body_gz`(byte[]) / `response_body_len`(int) | 比浏览器端 `http_request` **多两个安全参数**：`tls_verify=false` 放行自签/LAN HTTPS；`tls_ca_pem` 固定自定义 CA（优先级高于 `tls_verify`）。`headers` 的值可写 `"$vault:NAME"` 引用已托管凭证（见 §19.6） |
| `aci_protocol` | — | `protocol_version`(string) + `semver`(string) + `supported`(string, 逗号分隔) | ACI 2.0 协议版本协商层：返回 `protocol_version="aci-protocol-v1"`、`semver="1.0.0"`、`supported="aci-protocol-v1"` |

### 18.2 与浏览器端 `http_request` 的差异

| 维度 | 受控浏览器 `http_request` | 主程序 `http_request` |
|------|--------------------------|----------------------|
| `tls_verify` / `tls_ca_pem` | 无（依赖 NSC base-config 放开明文） | **有**（OkHttp 层 TLS 策略，自签/LAN 可控） |
| `$vault:NAME` 凭证引用 | 无 | **有**（`headers` 值经 `QuroAidlAciCredentialVault.resolve` 解析） |
| 大响应保护 | >15 万字符截断 + gzip | 同；且 `Content-Length > 2MB` 直接不载入内存、标记 `truncated` |
| 调用方白名单 | 自身 + 主程序 | 自身（`SELF_PKG`）+ 受控浏览器（`BROWSER_PKG`） |

### 18.3 HTTP 调用审计

主程序受控端每次 `http_request` 都会写 `filesDir/http_call_audit.json`（最多 500 条：`timestamp/method/url/code/durationMs`），供用户事后审查「AI 发过哪些 HTTP 请求」。注意它与控制端的 `aci_call_audit.json`（记录 ACI 调用本身）是两份独立审计（见 §21.3）。

---

## 19. 控制端编排与 LLM 工具（自建控制端参考）

> 本节面向**想基于 `aidl-aci-core` 自建控制端**的开发者，梳理 Zorv AI 主程序 `QuroAidlAciManager` 及其辅助类的真实实现。受控端接入见 §4，本节是控制端侧。

### 19.1 发现（Discovery）

`QuroAidlAciManager.discover()` 用 `PackageManager.queryIntentServices(new Intent("ai.aci.core.ACTION_BIND"), GET_META_DATA)` 扫描本机声明了该 Intent 的受控端；命中即缓存包名/类名并立即 `bindWithWake`。**无独立清单缓存文件**，能力清单来自运行时包扫描（Android 11+ 受控端 `<queries>` 缺一不可，见 §10）。

### 19.2 绑定生命周期

- **绑定标志**：`BIND_AUTO_CREATE or BIND_IMPORTANT`。
- **`onServiceConnected`**：写入 `serviceMap`、清零退避计数、注册 `IBinder.DeathRecipient`（**事件驱动**断线感知，取代轮询）、调用 `fetchCapabilities`。
- **`onServiceDisconnected`**：清 `serviceMap` 但**保留 `capMap` 能力缓存**（断线期间仍能看到能力列表）、复位 `socketOk`、触发 `scheduleRebind`。
- **`bindWithWake`**：先直绑；失败则发 `ACTION_WAKE` 广播（带 `FLAG_INCLUDE_STOPPED_PACKAGES`）拉起 stopped 进程再绑（修复 ColorOS/Android 11+ 停止态绑不上，见 §4.6）。
- **多目标并存**：`capMap: ConcurrentHashMap<包的, List<Capability>>`，一个控制端可同时管理多个受控端；`aci_call` 必须带 `target_package` 指定目标。

### 19.3 健康看护 + 指数退避重绑

- `startHealthWatch(10s)` 定时 `healthCheck()`（`ping()`）；死亡即 `ensureBound()`（含 wake 广播 + `startService` + 重绑）。
- `scheduleRebind`：**800ms → 1.6s → … → 8s 指数退避**，成功绑定即清零，避免对不可达端高频空转。

### 19.4 LocalSocket 探测与 AIDL 回退

- 绑定后 `fetchCapabilities()` 主动 `AidlAciLocalSocketTransport.probe(pkg)`（仅 connect 不发包），结果存入 `socketOk`。
- `call()`：`if (socketOk[pkg] != false)` 优先走 LocalSocket；抛异常即置 `socketOk=false` 并**回落 AIDL**，无需等到首次调用失败才切换（线缆细节见 §20）。

### 19.5 调用、超时与重试

- 同步 `call()`：`callTimeoutMs = 15_000ms`，用 `TimeoutResult + wait/notifyAll` 实现超时，超时返回 `504`。
- `doCallWithRetry()`：途中 `RemoteException` 清引用并 `ensureBound` 重绑后**重试一次**。
- 异步 `callAsync()`：经 `IAidlAciCallback.onResult` / `onProgress` 回传。

### 19.6 会话 trace 与语义点击脚手架

- **trace**：环形 `traceQueue`（最近 50 条 `AciCallTrace{ts, callId, target, capability, transport, code, success, latencyMs}`），提供 `getTrace()` / `clearTrace()` / `socketStatus(pkg)`；ACI 管理中心「诊断」面板据此展示每次调用的传输路径、耗时与成功率。
- **语义点击**：`clickText` / `clickResourceId` 依赖 `ui_snapshot` + `tap` 双能力（先取节点→解析锚点→`tap`）；若目标未暴露这两能力则返回 `412`。

### 19.7 LLM 工具：`aci_list` 与 `aci_call`

控制端把 ACI 暴露给 LLM 的不是 JSON-Schema function-calling，而是**自然语言 prompt 注入 + 一个小 JSON Schema 的 CALL 工具**：

- **`aci_list`**（受控能力清单）：`getCapabilityPrompt()` 遍历 `capMap`，输出「【应用名】(包名) - id: 描述 · 参数…」文本喂给 LLM，让模型知道有哪些工具可用。
- **`aci_call`**（执行调用）：JSON Schema 仅 `target_package` / `capability` / `args` / `confirm` 四键；能力细节靠 prompt 文本承载。运行逻辑含：
  - `requireUserConfirm` 确认门禁（危险能力需用户确认）；
  - `$vault:NAME` 凭证自动解析（见下文）；
  - `html_gz` / `response_body_gz` 自动 GZIP 解压还原完整内容后再交给 LLM。

### 19.8 凭据保险库 `QuroAidlAciCredentialVault`

`store` / `resolve` / `list` / `delete`。真实凭证用 **AndroidKeyStore 主密钥（别名 `aci_cred_kv1`）+ AES-GCM** 加密后落盘 `filesDir/aci_credentials.json`，主密钥永不导出、即使文件被读也无法解密。`resolve(input)` 识别 `"$vault:NAME"` 前缀返回明文，非此前缀原样返回（兼容旧明文用法）。受控端 `http_request` 的 `headers` 值在发出前经此解析，实现「配置里只写 `$vault:NAME`，运行时注入真实 Token」。

### 19.9 能力注册表 `QuroAidlAciRegistry`

`store["$pkg::$id"]` 保存能力元数据；`inferTags(capId, capDesc)` 启发式推断**语义标签**用于检索/分组：`network` / `web` / `fs` / `messaging` / `calendar` / `media` / `location` / `execute` / `ui` / `auth` / `misc`（无匹配归 `misc`）。提供 `byPackage` / `queryByTag` / `queryByTagsAny` / `queryByTagsAll`。注：`aci-core` 的 `Capability` 当前无 tags 字段，标签是控制端侧推断（正式 tags 字段落地在 aci-core 2.0 Roadmap）。

### 19.10 错误模型 `QuroAidlAciErrors`

结构化错误 `aci_error{code, message, suggestion, layer}`，让 LLM 能自助纠错：

| 语义码 | 值 | 含义 |
|--------|----|------|
| `E_SERVICE_UNBOUND` | `1503` | 目标未绑定 |
| `E_TIMEOUT` | `1504` | 调用超时 |
| `E_BAD_REQUEST` | `2400` | 参数错误 |
| `E_HTTP_CLIENT` | `2500` | HTTP 请求失败 |
| `E_HTTP_TLS` | `2520` | HTTPS 证书校验失败 |
| `E_INTERNAL` | `2599` | 内部错误 |

`layer ∈ {binder, http, protocol}`；`parse()` 可从 `errorMessage` 反解，`fromBinderResponse` / `httpSuggestion` 生成给 LLM 的修复建议。受控端 `QuroMainAciService` 已用此模型产出 `{code,message,suggestion,layer}` JSON 内嵌在 `errorMessage` 中。

### 19.11 事件总线 `QuroAidlAciEvents` 与协议协商 `QuroAidlAciProtocol`

- **事件总线**：进程内 `subscribe` / `emit`，5 类事件：`service_bound` / `service_unbound` / `call_failed` / `discovered` / `protocol_negotiated`。
- **协议协商**：`PROTOCOL_VERSION="aci-protocol-v1"`、`PROTOCOL_SEMVER="1.0.0"`、`SUPPORTED=["aci-protocol-v1"]`；`negotiate(peer)` 比对双方支持的最高共同版本（主程序 `aci_protocol` 能力即返回这些值）。

### 19.12 传输抽象 `QuroAidlAciAdapter` / `BinderAciAdapter`

`AidlAciAdapter` 接口把传输抽象为 `transport` / `call` / `listCapabilities` / `negotiateProtocol`，当前唯一实现 `BinderAciAdapter`（Binder + LocalSocket 双通道）。这是为未来 WS/HTTP 传输预留的扩展点，不改变现有热路径。

---

## 20. LocalSocket 线缆协议细节（`AidlAciLocalSocketTransport`）

LocalSocket 是 AIDL Binder 的**增强型替代传输**：控制端优先尝试，失败/不可用时自动回落 AIDL。

### 20.1 端点与命名空间

- 抽象命名空间地址前缀 `SOCK_PREFIX = "ai.aidl.aci.core.sock."`，端点 = `前缀 + 自身包名`。
- `LocalSocketAddress.Namespace.ABSTRACT`：**不落盘、不暴露为文件节点**，仅本机同用户可见。

### 20.2 帧格式

```
┌────────────┬──────────────────────┬──────────────────────────────────┐
│ 4 字节魔数  │ 4 字节大端 payload 长度 │ payload（AidlAciRequest/Response 经 Parcel 序列化）│
└────────────┴──────────────────────┴──────────────────────────────────┘
```

- 请求魔数 `ACIS`，响应魔数 `ACIR`。
- `MAX_PAYLOAD = 8MB`，超出即视为畸形帧丢弃，防内存撑爆。

### 20.3 握手与鉴权

- **无独立握手**：受控端 `BaseAidlAciService.onCreate` 起 `Server` 监听，收到帧先校验魔数，非法即关连接；合法则反序列化后复用 `handleCall`（与 AIDL 的 `call()` 走完全相同逻辑）。
- **鉴权沿用 Binder 链路**：控制端发送前 `setCallerPkg(本包名)`，受控端 `onCheckPermission` 按白名单裁决——LocalSocket 通道不引入独立鉴权。
- **探测 `probe(pkg)`**：只 `connect` 不发包，成功即 `true`（绑定后判定通道可用性，首调用直接选最优路径）。

### 20.4 与 AIDL 并存

受控端 `BaseAidlAciService.onCreate` **同时**启动 LocalSocket Server，`onDestroy` 关闭；AIDL 与 LocalSocket 共用 `handleCall` 派发。任一通道可用即可通信，互为兜底。

---

## 21. ACI Token 认证机制（应用层认证）

> 本节介绍 ACI 的应用层 Token 认证机制，用于解决第三方受控端与控制端签名不同的鉴权问题。

### 21.1 Token 认证原理

ACI Token 认证是一种应用层认证机制，通过在每次调用时传递 Token 来验证调用方身份，完全绕过 Android 系统签名验证。

**核心优势：**
- **签名无关**：控制端和受控端可以使用不同的签名证书
- **安全可靠**：Token 使用 AndroidKeyStore 加密存储，防止泄露
- **应用层实现**：不依赖系统签名机制，适合第三方开发者

### 21.2 Token 格式与存储

**Token 格式：**
```
aci_token_{packageName}_{timestamp}_{random}
```

**存储方式：**
- 使用 AndroidKeyStore 加密存储（AES/GCM/NoPadding）
- 内存缓存 + SharedPreferences 持久化
- 每个目标应用一个独立 Token

### 21.3 控制端实现（已内置）

控制端 `QuroAidlAciManager` 在每次调用时自动添加 Token：

```kotlin
// 在 call() 和 callAsync() 方法中自动添加
val tokenManager = AciTokenManager.getInstance(appContext)
val token = tokenManager.getOrCreateToken(targetPackage)
if (token != null && token.isNotEmpty()) {
    params.putString("_aci_token", token)
}
```

### 21.4 受控端验证（推荐实现）

受控端可以在 `onCheckPermission` 或 `onCall` 中验证 Token：

```java
// 在 BaseAidlAciService 子类中
@Override
protected AciTokenVerifier.TokenResult onVerifyToken(AidlAciRequest request) {
    return AciTokenVerifier.verify(this, request);
}
```

**验证逻辑：**
1. 从请求参数中提取 `_aci_token`
2. 验证 Token 格式和有效性
3. 返回验证结果（成功/失败）

### 21.5 第三方开发者接入指南

**步骤 1：添加依赖**
```kotlin
dependencies {
    implementation(files("libs/aidl-aci-core-release.aar"))
}
```

**步骤 2：实现 Token 验证（可选）**
```kotlin
class MyAciService : BaseAidlAciService() {
    override fun onVerifyToken(request: AidlAciRequest): AciTokenVerifier.TokenResult {
        // 自定义验证逻辑，或使用默认验证
        return AciTokenVerifier.verify(this, request)
    }
}
```

**步骤 3：配置白名单**
```kotlin
override fun onCheckPermission(request: AidlAciRequest, callerPkg: String): Boolean {
    // 验证 Token 后，允许调用
    val tokenResult = onVerifyToken(request)
    return tokenResult.isSuccess()
}
```

### 21.6 Token 安全性

- **加密存储**：使用 AndroidKeyStore 的 AES-GCM 加密
- **随机生成**：包含时间戳和随机数，防止重放攻击
- **进程隔离**：Token 存储在应用私有目录，其他应用无法访问
- **自动轮换**：每个目标应用独立 Token，可单独撤销

### 21.7 兼容性说明

- **向后兼容**：旧版受控端不验证 Token 也能正常工作
- **可选实现**：Token 验证是可选的，受控端可以选择是否启用
- **默认模式**：不配置 Token 验证时，允许所有调用通过

---

## 22. 唤醒接收器与诊断

### 22.1 两处 WakeReceiver 实现差异

两者都靠 `FLAG_INCLUDE_STOPPED_PACKAGES` 穿透 stopped 态，但被拉起的方式不同：

| 文件 | 收到 `ai.aci.core.ACTION_WAKE` 后的行为 |
|------|------|
| 浏览器端 `aidl-aci-browser/.../QuroAidlAciWakeReceiver.kt` | `getLaunchIntentForPackage` **启动主 Activity** + 写诊断日志 `quro_browser_diag.log` |
| 主程序端 `app/.../service/QuroAidlAciWakeReceiver.kt` | 主程序无 Activity 壳，**直接 `startService(QuroMainAciService)`** 拉起 ACI Service，不弹 UI |

### 21.2 DiagBuffer（仅浏览器模块）

进程级共享诊断缓冲（`CopyOnWriteArrayList`），`append(tag, msg)` 带时间戳并同时写 Logcat。覆盖：权限自动授权、WebView 读取/抓取/截图/鼠标/点击、`onCreate` / `onCreateCapabilities` / `onCall` 各步成败。`getAll()` 供 Activity 渲染到屏幕顶部；`persist(ctx)` 落盘 `getExternalFilesDir("QuroAI_logs")/browser_v5_diag_<date>.log`。主程序受控端无 DiagBuffer，诊断改用 `android.util.Log`。

### 21.3 审计落盘

| 文件 | 记录内容 | 上限 |
|------|----------|------|
| 控制端 `filesDir/aci_call_audit.json` | 每次 `aci_call`：`timestamp/pkg/capability/code/ok/durationMs` | 500 条 |
| 主程序受控端 `filesDir/http_call_audit.json` | 每次 `http_request`：`timestamp/method/url/code/durationMs` | 500 条 |

> 二者独立：`aci_call_audit` 记录「AI 调了哪个 App 的哪个能力」，`http_call_audit` 记录「主程序受控端实际发出的 HTTP 请求」。

### 21.4 ACI 管理中心 UI（`QuroAidlAciCenterScreen`）

主程序「设置 → ACI 管理中心」向用户暴露三大区块：

1. **01 添加 ACI 应用**：输入包名/应用名 →「搜索」（模糊匹配本机应用）或「按包名注册并启动」。
2. **02 已发现的 ACI 应用**：逐卡显示绑定态（已绑定/未绑定）、能力清单（含「⚠️需确认」标记）、最近活动时间，并提供「重绑」「启动」「打开控制台」按钮（控制台需该端暴露 `console_ui`）。
3. **03 开发者文档**：内嵌 `ACI_DEV_DOC` 长文（可折叠）+ 一键「保存依赖模板 / 下载开发者文档」。

控制台弹层：点「打开控制台」经 Binder 拉 `console_ui` 快照 → `AciConsoleModel.parse` → `AciConsoleScreen`（纯 Compose）渲染。该界面标题显示为「ACT 关联启动」，底层即 ACI。

---

## 23. MCP-ACI 桥接（让 ACI 控制方调用外部 MCP 服务器工具）

> 版本：v1.0.62 新增 | 适用 SDK：`aidl-aci-core` + Zorv AI 主程序 | 最后更新：2026-08-24

### 23.1 什么是 MCP-ACI 桥接

**MCP-ACI 桥接** 是 Zorv AI v1.0.62 新增的功能，它将外部 MCP（Model Context Protocol）服务器的工具转换为 ACI（Agent Capability Interface）能力，让 ACI 控制方能够调用 MCP 工具。

**核心价值**：
- 统一工具调用入口：AI 可以通过 `aci_call` 同时调用 ACI 原生能力和 MCP 工具
- 扩展 ACI 能力范围：MCP 生态的工具自动成为 ACI 能力
- 无缝集成：无需修改现有 ACI 代码，MCP 工具自动映射为 ACI 能力

### 23.2 架构设计

```
┌──────────────────────────┐         ACI Binder          ┌──────────────────────────┐
│   ACI 控制端（Zorv AI）    │  ─── aci_call ────────▶    │   McpAciBridge 桥接器     │
│  QuroAidlAciManager           │  ◀── ACIResponse ───────     │  - 能力映射             │
│  - aci_call(capability)   │                              │  - 工具调用路由          │
│  - aci_list              │                              │  - MCP 客户端集成        │
└──────────────────────────┘                              └───────────┬──────────────┘
                                                                     │
                                                                     ▼
                                                          ┌──────────────────────────┐
                                                          │   外部 MCP 服务器        │
                                                          │  - tools/list           │
                                                          │  - tools/call           │
                                                          └──────────────────────────┘
```

### 23.3 能力映射规则

MCP 工具自动映射为 ACI 能力，映射规则如下：

| MCP 工具 | ACI 能力 ID | 说明 |
|----------|-------------|------|
| `{toolName}` | `mcp_{toolName}` | 所有 MCP 工具都以 `mcp_` 前缀映射 |

**示例**：
- MCP 工具 `web_search` → ACI 能力 `mcp_web_search`
- MCP 工具 `weather_get` → ACI 能力 `mcp_weather_get`

### 23.4 控制端实现（已内置）

Zorv AI 主程序已内置 MCP-ACI 桥接功能，无需额外配置。

**核心组件**：
- `McpAciBridge`：桥接器核心，负责 MCP 工具到 ACI 能力的映射和调用
- `QuroAidlAciManager`：ACI 管理器，集成 MCP 桥接功能
- `QuroMcpAciTools`：MCP-ACI 桥接工具集

**使用方式**：

1. **配置 MCP 服务器**：在「设置 → MCP 服务」中添加外部 MCP 服务器
2. **查看可用工具**：AI 调用 `mcp_aci_list()` 查看所有可通过 ACI 调用的 MCP 工具
3. **调用 MCP 工具**：AI 调用 `mcp_aci_call(capability="mcp_{工具名}", args={参数})` 调用 MCP 工具
4. **管理桥接器**：AI 调用 `mcp_aci_bridge(action="refresh|status")` 管理桥接器

### 23.5 LLM 工具：`mcp_aci_list`、`mcp_aci_call`、`mcp_aci_bridge`

| 工具名 | 参数 | 说明 |
|--------|------|------|
| `mcp_aci_list` | `{}` | 列出所有可通过 ACI 调用的 MCP 工具 |
| `mcp_aci_call` | `{"capability":"mcp_{工具名}","args":{...}}` | 通过 ACI 调用 MCP 工具 |
| `mcp_aci_bridge` | `{"action":"refresh\|status"}` | 管理 MCP-ACI 桥接器 |

**调用示例**：

```kotlin
// 1. 查看可用 MCP 工具
val tools = mcpAciBridge.getMcpCapabilities()

// 2. 调用 MCP 工具
val response = QuroAidlAciManager.getInstance().call(
    "mcp_bridge",  // 虚拟包名
    "mcp_web_search",  // MCP 工能的 ACI 能力 ID
    Bundle().apply {
        putString("query", "AI 新闻")
    }
)
```

### 23.6 数据流

```
AI → mcp_aci_call(capability="mcp_{tool}", args={...})
    → QuroAidlAciManager.call()
    → handleMcpAciCall()
    → McpAciBridge.callMcpTool()
    → QuroMcpClient.callTool()
    → 外部 MCP 服务器
```

### 23.7 错误处理

| 错误码 | 说明 | 处理建议 |
|--------|------|----------|
| 404 | MCP 工具未找到 | 检查 MCP 服务器配置，确保工具存在 |
| 404 | MCP 服务器未找到 | 检查 MCP 服务器配置，确保服务器已添加 |
| 500 | MCP 工具调用失败 | 检查 MCP 服务器状态，查看详细错误信息 |

### 23.8 兼容性说明

- **向后兼容**：MCP-ACI 桥接功能是新增功能，不影响现有 ACI 功能
- **MCP 服务器要求**：外部 MCP 服务器需要支持标准 MCP 协议（tools/list、tools/call）
- **性能考虑**：MCP 工具调用会增加网络延迟，建议在本地 MCP 服务器上使用

---

## 24. ZorvBrowser-ACI 桥接（让 ACI 控制方调用 ZorvBrowser 浏览器工具）

> 版本：v1.0.63 新增 | 适用 SDK：`aidl-aci-core` + Zorv AI 主程序 + ZorvBrowser | 最后更新：2026-08-24

### 24.1 什么是 ZorvBrowser-ACI 桥接

**ZorvBrowser-ACI 桥接** 是 Zorv AI v1.0.63 新增的功能，它将 ZorvBrowser 浏览器的 30 个 ACI 工具暴露给 ACI 控制方，让 AI 能够通过 ACI 直接操控 ZorvBrowser 浏览器。

**核心价值**：
- **完整浏览器控制**：30 个工具覆盖导航、DOM 操作、内容提取、JavaScript 执行、输入模拟、HTTP 请求等
- **系统级输入模拟**：通过 `/dev/uinput` 注入真实触摸事件，绕过反爬检测
- **LAN 明文支持**：`http_request` 工具支持局域网明文 HTTP，访问路由器/NAS/智能家居
- **MCP 集成**：支持通过 MCP 协议调用 ZorvBrowser 工具
- **ACI Token 认证**：支持 ACI Token 认证机制，增强安全性

### 24.2 架构设计

```
┌──────────────────────────┐         ACI Binder          ┌──────────────────────────┐
│   ACI 控制端（Zorv AI）    │  ─── aci_call ────────▶    │  ZorvBrowser 浏览器      │
│  QuroAidlAciManager           │  ◀── ACIResponse ───────     │  - 30 个 ACI 工具       │
│  - aci_call(capability)   │                              │  - GeckoView 渲染       │
│  - aci_list              │                              │  - uinput 触摸注入      │
│  - browser_aci_*         │                              │  - HTTP LAN 明文        │
└──────────────────────────┘                              └──────────────────────────┘
         │
         │  内部调用
         ▼
┌──────────────────────────┐
│  ZorvBrowserAciBridge    │
│  - 能力映射              │
│  - 工具调用路由          │
│  - ACI Token 生成        │
└──────────────────────────┘
```

### 24.3 工具清单（30 个）

**基础导航（6 个）**

| 工具名 | 参数 | 说明 |
|--------|------|------|
| `browser_open` | `url`(string, 必填) | 打开网页 |
| `browser_back` | `{}` | 返回上一页 |
| `browser_forward` | `{}` | 前进下一页 |
| `browser_reload` | `{}` | 刷新当前页面 |
| `browser_close` | `{}` | 关闭当前标签页 |
| `browser_screenshot` | `fullPage`(boolean, 可选) | 截取当前页面截图 |

**标签页管理（4 个）**

| 工具名 | 参数 | 说明 |
|--------|------|------|
| `browser_tabs_list` | `{}` | 列出所有标签页 |
| `browser_tabs_switch` | `tabId`(string, 必填) | 切换到指定标签页 |
| `browser_tabs_new` | `url`(string, 可选) | 新建标签页 |
| `browser_tabs_close` | `tabId`(string, 必填) | 关闭指定标签页 |

**DOM 操作（5 个）**

| 工具名 | 参数 | 说明 |
|--------|------|------|
| `browser_dom_query` | `selector`(string, 必填) | 查询页面元素 |
| `browser_dom_text` | `selector`(string, 必填) | 获取元素文本 |
| `browser_dom_attr` | `selector`(string, 必填), `attribute`(string, 必填) | 获取元素属性 |
| `browser_dom_click` | `selector`(string, 必填) | 点击元素 |
| `browser_dom_type` | `selector`(string, 必填), `text`(string, 必填) | 在元素中输入文本 |

**内容提取（4 个）**

| 工具名 | 参数 | 说明 |
|--------|------|------|
| `browser_crawl` | `{}` | 提取页面结构化正文和出站链接 |
| `browser_html` | `{}` | 获取页面完整 HTML |
| `browser_text` | `{}` | 获取页面纯文本 |
| `browser_links` | `{}` | 获取页面所有链接 |

**JavaScript 执行（1 个）**

| 工具名 | 参数 | 说明 |
|--------|------|------|
| `browser_script` | `script`(string, 必填) | 在页面上下文执行 JavaScript |

**输入模拟（3 个）**

| 工具名 | 参数 | 说明 |
|--------|------|------|
| `browser_input_click` | `x`(int, 必填), `y`(int, 必填) | 模拟点击（系统级 uinput） |
| `browser_input_type` | `text`(string, 必填) | 模拟键盘输入（系统级） |
| `browser_input_scroll` | `deltaX`(int, 可选), `deltaY`(int, 可选) | 模拟滚动（系统级） |

**HTTP 请求（1 个）**

| 工具名 | 参数 | 说明 |
|--------|------|------|
| `browser_http_request` | `url`(string, 必填), `method`(string, 可选), `headers`(object, 可选), `body`(string, 可选) | 发送 HTTP 请求（支持 LAN 明文） |

**高级功能（3 个）**

| 工具名 | 参数 | 说明 |
|--------|------|------|
| `browser_find_text` | `text`(string, 必填) | 在页面中查找文本 |
| `browser_pdf` | `filename`(string, 可选) | 将当前页面导出为 PDF |
| `browser_print` | `{}` | 打印当前页面 |

**书签和历史（3 个）**

| 工具名 | 参数 | 说明 |
|--------|------|------|
| `browser_bookmarks_list` | `{}` | 列出所有书签 |
| `browser_bookmarks_add` | `url`(string, 必填), `title`(string, 必填) | 添加书签 |
| `browser_history_list` | `limit`(int, 可选) | 列出浏览历史 |

### 24.4 控制端实现（已内置）

Zorv AI 主程序已内置 ZorvBrowser-ACI 桥接功能，无需额外配置。

**核心组件**：
- `ZorvBrowserAciBridge`：桥接器核心，负责浏览器工具到 ACI 能力的映射和调用
- `QuroAidlAciManager`：ACI 管理器，集成 ZorvBrowser 桥接功能
- `ZorvBrowserAciTools`：ZorvBrowser-ACI 桥接工具集

**使用方式**：

1. **安装 ZorvBrowser**：从 GitHub 仓库 `Quor-a/ZorvBrowser` 下载并安装
2. **查看可用工具**：AI 调用 `browser_aci_list()` 查看所有可通过 ACI 调用的浏览器工具
3. **调用浏览器工具**：AI 调用 `browser_aci_call(tool="browser_open", args={"url":"https://example.com"})` 调用浏览器工具
4. **管理桥接器**：AI 调用 `browser_aci_bridge(action="refresh|status")` 管理桥接器

### 24.5 LLM 工具：`browser_aci_list`、`browser_aci_call`、`browser_aci_bridge`

| 工具名 | 参数 | 说明 |
|--------|------|------|
| `browser_aci_list` | `{}` | 列出所有可通过 ACI 调用的 ZorvBrowser 工具 |
| `browser_aci_call` | `{"tool":"browser_*","args":{...}}` | 通过 ACI 调用 ZorvBrowser 工具 |
| `browser_aci_bridge` | `{"action":"refresh\|status"}` | 管理 ZorvBrowser-ACI 桥接器 |

**调用示例**：

```kotlin
// 1. 查看可用浏览器工具
val tools = ZorvBrowserAciBridge.getAllBrowserToolMappings()

// 2. 调用浏览器工具：打开网页
val response = QuroAidlAciManager.getInstance().call(
    "com.ai.assistance.quro.browser",  // ZorvBrowser 包名
    "browser_open",  // 浏览器工具名
    Bundle().apply {
        putString("url", "https://example.com")
    }
)

// 3. 调用浏览器工具：执行 JavaScript
val jsResponse = QuroAidlAciManager.getInstance().call(
    "com.ai.assistance.quro.browser",
    "browser_script",
    Bundle().apply {
        putString("script", "document.title")
    }
)
```

### 24.6 数据流

```
AI → browser_aci_call(tool="browser_*", args={...})
    → ZorvBrowserAciBridge.callBrowserTool()
    → QuroAidlAciManager.call()
    → ZorvBrowser ACI Service
    → 浏览器操作
    → AidlAciResponse
```

### 24.7 ACI Token 认证

ZorvBrowser-ACI 桥接支持 ACI Token 认证机制：

**Token 格式**：
```
aci_token_{packageName}_{timestamp}_{random}
```

**示例**：
```
aci_token_com.ai.assistance.quro.browser_1787583870335_iNk69+bPempKATGxWMUnQ7ptxZOYgu1kkMHA5OZ0AKU=
```

**认证流程**：
1. 控制端（ZorvAI）在调用前自动生成 ACI Token
2. Token 通过 `_aci_token` 参数传递给受控端（ZorvBrowser）
3. 受控端可选择验证 Token 以增强安全性
4. Token 使用 AndroidKeyStore 加密存储，每个目标应用独立 Token

**安全特性**：
- **加密存储**：Token 使用 AES/GCM/NoPadding 加密，存储在 AndroidKeyStore
- **独立 Token**：每个目标应用生成独立的 Token，互不影响
- **向后兼容**：旧版受控端不验证 Token 也能正常工作
- **可选实现**：Token 验证是可选的，受控端可以选择是否启用

### 24.8 错误处理

| 错误码 | 说明 | 处理建议 |
|--------|------|----------|
| 404 | 浏览器工具未找到 | 检查工具名称是否正确，确保以 `browser_` 开头 |
| 500 | 浏览器服务未绑定 | 确保 ZorvBrowser 已安装且正在运行 |
| 500 | 浏览器工具调用失败 | 检查 ZorvBrowser 版本，查看详细错误信息 |

### 24.9 兼容性说明

- **向后兼容**：ZorvBrowser-ACI 桥接功能是新增功能，不影响现有 ACI 功能
- **ZorvBrowser 要求**：需要安装 ZorvBrowser 应用（GitHub: `Quor-a/ZorvBrowser`）
- **协议版本**：支持新旧两种 AIDL 契约（`ai.aidl.aci.core` 和 `ai.aci.core`）
- **性能考虑**：浏览器操作会增加延迟，建议在需要时调用

### 24.10 典型使用场景

**场景 1：网页数据抓取**
```
AI → browser_aci_call(tool="browser_open", args={"url":"https://example.com"})
AI → browser_aci_call(tool="browser_crawl")
AI → 分析抓取的数据
```

**场景 2：自动化表单填写**
```
AI → browser_aci_call(tool="browser_open", args={"url":"https://form.example.com"})
AI → browser_aci_call(tool="browser_dom_type", args={"selector":"#email", "text":"user@example.com"})
AI → browser_aci_call(tool="browser_dom_click", args={"selector":"#submit"})
```

**场景 3：局域网设备访问**
```
AI → browser_aci_call(tool="browser_http_request", args={"url":"http://192.168.1.1/api/status"})
AI → 分析设备状态
```

**场景 4：JavaScript 注入执行**
```
AI → browser_aci_call(tool="browser_open", args={"url":"https://example.com"})
AI → browser_aci_call(tool="browser_script", args={"script":"document.querySelectorAll('a').length"})
AI → 获取页面链接数量
```

---

## 25. 终端 ACI 受控端完整文档（v1.0.67）

> Zorv AI 内置终端作为 ACI 受控端，暴露 12 个能力 + 前台服务保活 + Intent/Provider/BroadcastReceiver/Deep Link 四种跨进程接入方式。

### 25.1 架构概览

```
┌─────────────────────────────────────────────────────┐
│              QuroTerminalAciService                  │
│              (前台服务, ACI 受控端)                    │
│              foregroundServiceType=specialUse         │
├─────────────────────────────────────────────────────┤
│  12 个 ACI 能力                                      │
│  ┌──────────────┬──────────────┬──────────────────┐  │
│  │ exec         │ create_sess  │ destroy_session  │  │
│  │ send_input   │ get_status   │ list_sessions    │  │
│  │ set_env      │ get_env      │ list_capabilities│  │
│  │ get_status   │ get_audit    │ help             │  │
│  └──────────────┴──────────────┴──────────────────┘  │
├─────────────────────────────────────────────────────┤
│  前台服务保活（QuroTerminalKeepAliveService）          │
│  - specialUse 类型，Android 14+ 兼容                 │
│  - 每 15s 巡检：会话死亡自动重建                      │
│  - 开机自启动（BOOT_COMPLETED）                       │
├─────────────────────────────────────────────────────┤
│  4 种跨进程接入                                      │
│  ┌──────────┬──────────┬──────────┬──────────┐      │
│  │ ACI AIDL │ Provider │ DeepLink │ Broadcast│      │
│  │ (Binder) │ (content)│ (quro://)│ (6 个)   │      │
│  └──────────┴──────────┴──────────┴──────────┘      │
└─────────────────────────────────────────────────────┘
```

### 25.2 ACI 能力清单

| 能力 ID | 描述 | 入参 | 出参 | 标志 |
|---------|------|------|------|------|
| `exec` | 在终端中执行命令并返回结果 | `command`(string,必填), `timeout`(int,默认14), `interactive`(boolean) | `exit_code`(int), `output`(string), `error`(string), `timed_out`(boolean) | FLAG_BACKGROUND |
| `create_session` | 创建新的终端会话 | `name`(string), `mode`(linux/device) | `session_id`(string), `session_name`(string), `created`(boolean) | FLAG_BACKGROUND |
| `destroy_session` | 销毁指定终端会话 | `session_id`(string,必填) | `destroyed`(boolean) | FLAG_BACKGROUND |
| `send_input` | 向指定会话发送输入 | `session_id`(string,必填), `input`(string,必填) | `sent`(boolean) | FLAG_BACKGROUND |
| `get_session_status` | 获取会话状态 | `session_id`(string,必填) | `session_id`, `name`, `mode`, `alive`(boolean), `busy`(boolean), `cwd`(string), `last_exit`(int) | FLAG_NO_UI |
| `list_sessions` | 列出所有会话 | 无 | `sessions`(string, JSON数组) | FLAG_NO_UI |
| `set_session_env` | 设置环境变量 | `session_id`, `key`, `value` | `set`(boolean) | FLAG_BACKGROUND |
| `get_session_env` | 获取环境变量 | `session_id` | `env`(string, JSON对象) | FLAG_NO_UI |
| `list_capabilities` | 列出所有能力 | 无 | `capabilities`(string, JSON数组) | FLAG_NO_UI |
| `get_service_status` | 获取服务状态 | 无 | `running`(boolean), `sessions_count`(int), `uptime`(long), `version`(string) | FLAG_NO_UI |
| `get_audit_log` | 获取审计日志 | `limit`(int,默认100) | `audit_log`(string, JSON数组) | FLAG_NO_UI |
| `help` | 显示帮助信息 | 无 | `help`(string) | FLAG_NO_UI |

### 25.3 前台服务保活

**核心机制**：`QuroTerminalKeepAliveService` 以前台服务身份运行，shell 子进程归属于服务进程 → 服务存活 = 终端存活。

**Manifest 声明**（Android 14+ 必需 `<property>` 标签）：
```xml
<service
    android:name=".service.QuroTerminalKeepAliveService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="终端会话保活：在服务进程内 fork shell 子进程并持续巡检存活，脱离 UI 生命周期" />
</service>
```

**必需权限**：
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

**保活逻辑**：
1. `Application.onCreate()` → `ensureStarted()` → `startForegroundService()`
2. `onCreate()` → `startForeground(NOTIF_ID, notification, SPECIAL_USE)`
3. 每 15 秒巡检：会话死亡则 `QuroShellSession.create()` 重建
4. 同时确保 `QuroTerminalAciService` 运行

### 25.4 Intent 接入

**Action 列表**：

| Action | 附加参数 | 返回 |
|--------|---------|------|
| `com.ai.assistance.quro.action.TERMINAL_EXEC` | `command`(string), `timeout`(long,可选) | `exit_code`(int), `output`(string), `error`(string) |
| `com.ai.assistance.quro.action.TERMINAL_STATUS` | 无 | `running`(boolean), `sessions_count`(int) |
| `com.ai.assistance.quro.action.TERMINAL_SESSIONS` | 无 | `sessions`(string, JSON数组) |
| `com.ai.assistance.quro.action.TERMINAL_CREATE_SESSION` | `name`(string,可选) | `session_id`(string), `created`(boolean) |
| `com.ai.assistance.quro.action.TERMINAL_DESTROY_SESSION` | `session_id`(string) | `destroyed`(boolean) |
| `com.ai.assistance.quro.action.TERMINAL_SEND_INPUT` | `session_id`(string), `input`(string) | `sent`(boolean) |

**调用示例**：
```kotlin
// 执行命令
val intent = Intent("com.ai.assistance.quro.action.TERMINAL_EXEC")
intent.setPackage("com.ai.assistance.quro")
intent.putExtra("command", "ls -la /tmp")
intent.putExtra("timeout", 10000L)
startActivityForResult(intent, REQUEST_CODE)

// 通过 Bundle 获取结果
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    val exitCode = data?.getIntExtra("exit_code", -1)
    val output = data?.getStringExtra("output")
    val error = data?.getStringExtra("error")
}
```

### 25.5 ContentProvider 接入

**Authority**：`com.ai.assistance.quro.terminal`

**URI 路径**：

| URI | 方法 | 返回 |
|-----|------|------|
| `content://com.ai.assistance.quro.terminal/sessions` | query | 会话列表 JSON |
| `content://com.ai.assistance.quro.terminal/exec?cmd=ls -la` | query | 执行结果 JSON |
| `content://com.ai.assistance.quro.terminal/status` | query | 服务状态 JSON |
| `content://com.ai.assistance.quro.terminal/env?key=PATH` | query | 环境变量值 |

**调用示例**：
```kotlin
// 查询会话列表
val cursor = contentResolver.query(
    Uri.parse("content://com.ai.assistance.quro.terminal/sessions"),
    null, null, null, null
)
cursor?..moveToFirst()
val sessions = cursor?.getString(0) // JSON 字符串

// 执行命令
val cursor = contentResolver.query(
    Uri.parse("content://com.ai.assistance.quro.terminal/exec?cmd=python3 --version"),
    null, null, null, null
)
```

### 25.6 Deep Link 接入

**Scheme**：`quro://terminal/...`

**路径**：

| Deep Link | 功能 |
|-----------|------|
| `quro://terminal/exec?cmd=ls -la` | 执行命令 |
| `quro://terminal/sessions` | 查看会话列表 |
| `quro://terminal/create?name=my-session` | 创建新会话 |
| `quro://terminal/destroy?id=default` | 销毁会话 |
| `quro://terminal/status` | 查看服务状态 |
| `quro://terminal/input?id=default&input=ls` | 向会话发送输入 |

**调用示例**：
```kotlin
// 通过浏览器/其他App打开
val intent = Intent(Intent.ACTION_VIEW, Uri.parse("quro://terminal/exec?cmd=uname -a"))
startActivity(intent)
```

### 25.7 BroadcastReceiver 接入

**Action 列表**：

| Action | 附加参数 | 结果 Extras |
|--------|---------|------------|
| `com.ai.assistance.quro.action.TERMINAL_EXEC` | `command`(string), `timeout`(long) | `exit_code`(int), `output`(string), `error`(string) |
| `com.ai.assistance.quro.action.TERMINAL_STATUS` | 无 | `running`(boolean), `sessions_count`(int) |
| `com.ai.assistance.quro.action.TERMINAL_SESSIONS` | 无 | `sessions`(string, JSON) |
| `com.ai.assistance.quro.action.TERMINAL_CREATE_SESSION` | `name`(string) | `session_id`(string), `created`(boolean) |
| `com.ai.assistance.quro.action.TERMINAL_DESTROY_SESSION` | `session_id`(string) | `destroyed`(boolean) |
| `com.ai.assistance.quro.action.TERMINAL_SEND_INPUT` | `session_id`(string), `input`(string) | `sent`(boolean) |

**调用示例**（带结果回调）：
```kotlin
val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val exitCode = intent.getIntExtra("exit_code", -1)
        val output = intent.getStringExtra("output")
        val error = intent.getStringExtra("error")
        Log.d("Terminal", "exit=$exitCode output=$output error=$error")
    }
}

registerReceiver(receiver, IntentFilter("com.ai.assistance.quro.action.TERMINAL_RESULT"))

val intent = Intent("com.ai.assistance.quro.action.TERMINAL_EXEC")
intent.setPackage("com.ai.assistance.quro")
intent.putExtra("command", "echo hello")
sendBroadcast(intent)
```

### 25.8 ACI 调用示例（通过 Zorv AI）

**AI 工具调用**：
```
AI → aci_call(target="com.ai.assistance.quro", capability="exec", args={"command":"ls -la /tmp"})
AI → 获取命令执行结果
```

**创建并管理会话**：
```
AI → aci_call(capability="create_session", args={"name":"build-session"})
AI → aci_call(capability="send_input", args={"session_id":"abc123","input":"cd /project && make"})
AI → aci_call(capability="get_session_status", args={"session_id":"abc123"})
```

### 25.9 注意事项

1. **Android 14+**：前台服务必须声明 `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"/>`
2. **权限**：受控端只需 `<uses-permission>` 引用，不要用 `<permission>` 定义 `ai.aci.permission.*`
3. **超时**：`exec` 默认 14 秒超时，长时间命令需增大 `timeout` 参数
4. **会话持久化**：默认会话在应用重启后自动恢复（元数据持久化到 JSON 文件）
5. **大输出截断**：命令输出超过一定长度会被截断，避免 `TransactionTooLargeException`

### 25.10 Intent / ContentProvider / BroadcastReceiver 升级说明（v1.0.67）

> 本节说明 Android 标准组件的完整实现，符合 Android 官方最佳实践。

#### 25.10.1 Intent Activity 升级（TerminalIntentActivity）

透明 Activity，无可见 UI，仅作为 Intent 处理器，处理完毕后立即 `finish()`。
外部应用通过 Intent 调用终端的标准 Android 入口。

**显式 Intent（推荐，指定 ComponentName）**：
```kotlin
// 启动终端执行命令
val intent = Intent()
intent.component = ComponentName("com.ai.assistance.quro",
    "com.ai.assistance.quro.core.terminal.TerminalIntentActivity")
intent.action = TerminalIntentActivity.ACTION_EXEC
intent.putExtra("command", "python3 -c 'print(1+2)'")
intent.putExtra("timeout", 14L)
startActivityForResult(intent, 0)  // 结果通过 onActivityResult 回传
```

**隐式 Intent**：
```kotlin
// 声明 action，由系统匹配 Intent Filter
val intent = Intent(TerminalIntentActivity.ACTION_EXEC)
intent.putExtra("command", "uname -a")
startActivity(intent)
```

**ACTION_SEND（分享文本到终端）**：
```kotlin
val intent = Intent(Intent.ACTION_SEND)
intent.type = "text/plain"
intent.putExtra(Intent.EXTRA_TEXT, "echo hello world")
intent.setPackage("com.ai.assistance.quro")
startActivity(intent)
```

**Deep Link**：
```kotlin
val intent = Intent(Intent.ACTION_VIEW, Uri.parse("quro://terminal/exec?cmd=ls -la"))
startActivity(intent)
```

**有序广播（BroadcastReceiver）**：
```kotlin
// 按优先级依次传递，接收器可修改结果、中止传播
val intent = Intent(TerminalBroadcastReceiver.ACTION_EXEC)
intent.putExtra("command", "ls -la")
sendOrderedBroadcast(intent, "ai.aci.permission.SEND_TERMINAL_BROADCAST")
```

**ACTION_PICK 模式（Intent + ContentProvider 协作）**：
```kotlin
// 1. 发送 ACTION_PICK Intent
val intent = Intent(TerminalIntentActivity.ACTION_PICK_SESSION)
intent.type = "vnd.android.cursor.dir/vnd.com.ai.assistance.quro.terminal.sessions"
startActivityForResult(intent, REQUEST_PICK_SESSION)

// 2. 在 onActivityResult 中：
val sessionUri = data.data  // content://com.ai.assistance.quro.terminal/sessions/{id}
val cursor = contentResolver.query(sessionUri, null, null, null, null)
```

#### 25.10.2 ContentProvider 升级

**完整 CRUD 接口**：
```kotlin
// 查询所有会话
val cursor = contentResolver.query(
    Uri.parse("content://com.ai.assistance.quro.terminal/sessions"),
    null, null, null, null
)

// 查询指定会话
val cursor = contentResolver.query(
    Uri.parse("content://com.ai.assistance.quro.terminal/sessions/abc123"),
    null, null, null, null
)

// 查询会话输出历史
val cursor = contentResolver.query(
    Uri.parse("content://com.ai.assistance.quro.terminal/sessions/abc123/output?limit=50"),
    null, null, null, null
)

// 执行命令（通过 insert）
val values = ContentValues().apply {
    put("command", "uname -a")
    put("timeout", 14L)
}
val resultUri = contentResolver.insert(
    Uri.parse("content://com.ai.assistance.quro.terminal/exec"),
    values
)

// 创建新会话
val values = ContentValues().apply {
    put("session_name", "my-session")
}
val sessionUri = contentResolver.insert(
    Uri.parse("content://com.ai.assistance.quro.terminal/sessions"),
    values
)

// 销毁会话
contentResolver.delete(
    Uri.parse("content://com.ai.assistance.quro.terminal/sessions/abc123"),
    null, null
)

// 获取服务状态
val cursor = contentResolver.query(
    Uri.parse("content://com.ai.assistance.quro.terminal/status"),
    null, null, null, null
)

// 获取能力列表
val cursor = contentResolver.query(
    Uri.parse("content://com.ai.assistance.quro.terminal/capabilities"),
    null, null, null, null
)
```

**细粒度权限控制**：
```xml
<!-- AndroidManifest.xml -->
<provider
    android:name=".core.terminal.TerminalProvider"
    android:authorities="com.ai.assistance.quro.terminal"
    android:exported="true"
    android:readPermission="ai.aci.permission.READ_TERMINAL"
    android:writePermission="ai.aci.permission.WRITE_TERMINAL"
    android:grantUriPermissions="true" />
```

**URI 临时权限授予**：
```kotlin
// 通过 Intent 授予临时读权限
val intent = Intent(Intent.ACTION_VIEW, sessionUri)
intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
startActivity(intent)
```

**TerminalIntentActivity Manifest 配置**：
```xml
<!-- AndroidManifest.xml — 透明 Activity，外部应用的标准入口 -->
<activity
    android:name=".core.terminal.TerminalIntentActivity"
    android:exported="true"
    android:theme="@style/Theme.Quro.TerminalIntent"
    android:excludeFromRecents="true"
    android:noHistory="true"
    android:launchMode="singleTop"
    android:taskAffinity=""
    android:permission="ai.aci.permission.CALL">
    <!-- 终端 Action + ACTION_PICK -->
    <intent-filter>
        <action android:name="com.ai.assistance.quro.action.TERMINAL_EXEC" />
        <action android:name="com.ai.assistance.quro.action.TERMINAL_STATUS" />
        <action android:name="com.ai.assistance.quro.action.TERMINAL_SESSIONS" />
        <action android:name="com.ai.assistance.quro.action.TERMINAL_CREATE_SESSION" />
        <action android:name="com.ai.assistance.quro.action.TERMINAL_DESTROY_SESSION" />
        <action android:name="com.ai.assistance.quro.action.TERMINAL_SEND_INPUT" />
        <action android:name="com.ai.assistance.quro.action.TERMINAL_GET_OUTPUT" />
        <action android:name="com.ai.assistance.quro.action.TERMINAL_PICK_SESSION" />
        <action android:name="android.intent.action.PICK" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="vnd.android.cursor.dir/vnd.com.ai.assistance.quro.terminal.sessions" />
    </intent-filter>
    <!-- ACTION_SEND（分享文本到终端） -->
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" />
    </intent-filter>
    <!-- Deep Link（quro://terminal/...） -->
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="quro" android:host="terminal" />
    </intent-filter>
</activity>
```

#### 25.10.3 BroadcastReceiver 升级

**普通广播（异步，所有接收器同时收到）**：
```kotlin
val intent = Intent(TerminalBroadcastReceiver.ACTION_EXEC)
intent.putExtra("command", "ls -la")
sendBroadcast(intent)
```

**有序广播（按优先级依次传递）**：
```kotlin
val intent = Intent(TerminalBroadcastReceiver.ACTION_EXEC)
intent.putExtra("command", "uname -a")
sendOrderedBroadcast(intent, "ai.aci.permission.SEND_TERMINAL_BROADCAST")
```

**带权限的广播**：
```kotlin
// 仅拥有此权限的接收器能收到
val intent = Intent(TerminalBroadcastReceiver.ACTION_EXEC)
intent.putExtra("command", "whoami")
sendBroadcast(intent, "ai.aci.permission.SEND_TERMINAL_BROADCAST")
```

**结果回调**：
```kotlin
// 注册结果接收器
val receiver = TerminalBroadcastReceiver.ResultReceiver()
registerReceiver(receiver, IntentFilter(TerminalBroadcastReceiver.ACTION_RESULT))

// 发送有序广播并获取结果
val intent = Intent(TerminalBroadcastReceiver.ACTION_EXEC)
intent.putExtra("command", "ls -la")
sendOrderedBroadcast(intent, null, receiver, null, 0, null, null)

// 在 ResultReceiver.onReceive() 中获取结果
override fun onReceive(context: Context, intent: Intent) {
    val output = intent.getStringExtra("result_output")
    val exitCode = intent.getIntExtra("exit_code", -1)
    val timedOut = intent.getBooleanExtra("timed_out", false)
}
```

**支持的广播 Action**：
| Action | 说明 | 权限 |
|--------|------|------|
| `TERMINAL_EXEC` | 执行命令 | `ai.aci.permission.SEND_TERMINAL_BROADCAST` |
| `TERMINAL_STATUS` | 获取状态 | 无 |
| `TERMINAL_SESSIONS` | 列出会话 | 无 |
| `TERMINAL_CREATE_SESSION` | 创建会话 | `ai.aci.permission.SEND_TERMINAL_BROADCAST` |
| `TERMINAL_DESTROY_SESSION` | 销毁会话 | `ai.aci.permission.SEND_TERMINAL_BROADCAST` |
| `TERMINAL_SEND_INPUT` | 发送输入 | `ai.aci.permission.SEND_TERMINAL_BROADCAST` |
| `TERMINAL_GET_OUTPUT` | 获取输出 | 无 |
| `TERMINAL_RESULT` | 结果回调 | `ai.aci.permission.RECEIVE_TERMINAL_BROADCAST` |

---

> 本手册由软件工坊基于 `aidl-aci-core` 源码与 Zorv AI 真实接入经验整理。协议细节以 [ACI_PROTOCOL.md](https://github.com/Quor-a/ZorvAI)（开源分支）为准。

---

## 25. 主程序 LLM 工具：Python↔浏览器会话桥 与 终端特权/ADB 工具（v1.0.75）

> 版本：v1.0.75 新增 | 适用：Zorv AI 主程序（`com.ai.assistance.quro`）| 最后更新：2026-09-02

本节补充主程序自身在 v1.0.75 新增 / 增强、与 ACI 浏览器链路直接相关的 LLM 工具。它们**不是**对外暴露的 ACI 能力（不进入 `Capability` 清单），而是主程序注册给 LLM 的内置工具（`QuroBuiltInTools`），但都与受控浏览器 / 终端子系统深度联动，故在此统一说明。

### 25.1 Python ↔ 浏览器会话桥（`QuroSessionBridge`）

对话框内的 Brython(Python) 运行环境与内置浏览器共享**同一会话上下文**，解决「在 Python 里算完数据，还得手动把 Cookie/登录态喂给浏览器」的割裂：

- **Cookie 双向同步**：经全局 `android.webkit.CookieManager` 共享，Python 侧发起的 HTTP 与浏览器访问同一站点时携带相同 Cookie（含 `HttpOnly` / 跨域）。
- **Storage 镜像**：`localStorage` / `sessionStorage` 经 `SharedPreferences` 镜像，浏览器与 Python 侧可读写同一份键值。
- **Python 驱动浏览器**：Python 侧经注入对象 `window.QuroSession.browserAct(action, params)` 直接调用 `QuroBrowserController` 的 `open / read / crawl / script / act`，无需把页面 HTML 复制回 Python 再贴回。

```python
# 对话框内 Brython 示例：用浏览器已登录态抓取并解析页面
from browser import window
result = window.QuroSession.browserAct("crawl", {"url": "https://example.com/dashboard"})
print(result["text"])
```

> 注意：此「Python」指对话框内联的 **Brython（WebView 内 JS 桥）**，与在 proot Ubuntu 容器内跑的 `python3`（`PythonRunTool` 数据处理路径）是两条独立链路，勿混淆。

### 25.2 终端特权执行工具 `priv_exec`

- `run`：以 **ZorvAI 授权 / Shizuku / ROOT（`QuroRootGateway` 自动降级）** 执行命令，阻塞调用须在 IO 线程；返回经 `RootResult.render()` 的文本。
- `status`：汇总查询特权通道可用状态——Root（`cachedRootAvailable`）、Shizuku（`QuroShizuku.isReady`）、LSPosed（`QuroLSPosed.statusText`）、ZorvAI。
- LSPosed 仅做**管理器探测**（清单 `KNOWN_MANAGERS`），不注入任何钩子，符合「受控端不得定义 `ai.aci.permission.*`」铁律。

### 25.3 ADB 终端工具 `adb_term`

把 ADB 当终端用（无线调试中枢，复用 `QuroAdbDebug`）：

| 动作 | 说明 |
|------|------|
| `shell` | 本机 ADB shell（等同在电脑上 `adb shell`） |
| `tcp_status` | 查询 TCP/IP 无线调试状态：是否有特权通道、`adbd` 是否在监听、WiFi IP、当前端口、USB 调试是否开启 |
| `tcp_enable` | 开启 TCP/IP 无线调试（指定端口，默认 5555） |
| `tcp_disable` | 关闭 TCP/IP 无线调试 |

> 这三个工具（§25.1–25.3）均与既有 pipe/shell 会话链路隔离、不破坏已工作的终端 ACI 12 能力。
