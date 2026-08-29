# Zorv AI ACI 技术架构与开发手册

> **开源地址**：[https://github.com/Quor-a/ZorvAI](https://github.com/Quor-a/ZorvAI)
>
> **版本**：v1.0.67 | **最后更新**：2026-08-29
>
> **作者**：Zorv AI 开发团队

---

## 目录

- [1. 概述](#1-概述)
- [2. ACI 框架设计](#2-aci-框架设计)
  - [2.1 核心概念](#21-核心概念)
  - [2.2 架构图](#22-架构图)
  - [2.3 设计原则](#23-设计原则)
- [3. 核心组件详解](#3-核心组件详解)
  - [3.1 BaseAidlAciService — ACI 服务基类](#31-baseaidlaciervice--aci-服务基类)
  - [3.2 AidlAciRequest / AidlAciResponse — 请求响应模型](#32-aidlacirequest--aidlaciesponse--请求响应模型)
  - [3.3 AidlAciCapability — 能力定义](#33-aidlacicapability--能力定义)
  - [3.4 AidlAciError — 错误码](#34-aidlacierror--错误码)
  - [3.5 AidlAciManager — 控制端管理器](#35-aidlacimanager--控制端管理器)
- [4. 传输层详解](#4-传输层详解)
  - [4.1 AIDL 本地绑定（默认）](#41-aidl-本地绑定默认)
  - [4.2 ACI HTTP 传输（局域网/本地）](#42-aci-http-传输局域网本地)
  - [4.3 MCP 桥接](#43-mcp-桥接)
  - [4.4 传输层对比](#44-传输层对比)
- [5. Token 认证与安全](#5-token-认证与安全)
  - [5.1 Token 生成与验证](#51-token-生成与验证)
  - [5.2 权限控制](#52-权限控制)
  - [5.3 高危能力确认机制](#53-高危能力确认机制)
- [6. 终端 ACI 集成（12 个能力）](#6-终端-aci-集成12-个能力)
  - [6.1 能力清单](#61-能力清单)
  - [6.2 exec 能力详解](#62-exec-能力详解)
  - [6.3 会话管理能力](#63-会话管理能力)
  - [6.4 环境变量能力](#64-环境变量能力)
  - [6.5 服务能力](#65-服务能力)
- [7. 原生 ACI 桥接（C/C++）](#7-原生-aci-桥接cc)
  - [7.1 libacihost.so](#71-libacihostso)
  - [7.2 C API 接口](#72-c-api-接口)
  - [7.3 JNI 回调](#73-jni-回调)
- [8. MCP-ACI 桥接](#8-mcp-aci-桥接)
  - [8.1 桥接原理](#81-桥接原理)
  - [8.2 能力映射](#82-能力映射)
  - [8.3 使用示例](#83-使用示例)
- [9. 控制台 UI（LAN 控制台）](#9-控制台-ui-lan-控制台)
  - [9.1 架构](#91-架构)
  - [9.2 功能](#92-功能)
- [10. 跨应用接入指南](#10-跨应用接入指南)
  - [10.1 作为控制端（调用其他 ACI 服务）](#101-作为控制端调用其他-aci-服务)
  - [10.2 作为受控端（暴露能力给其他应用）](#102-作为受控端暴露能力给其他应用)
  - [10.3 终端 ACI 接入示例](#103-终端-aci-接入示例)
- [11. ACI 工具集](#11-aci-工具集)
  - [11.1 工具清单](#111-工具清单)
  - [11.2 aci_call 详解](#112-aci_call-详解)
  - [11.3 aci_list 详解](#113-aci_list-详解)
- [12. 常见问题与故障排除](#12-常见问题与故障排除)
- [13. 开发指南](#13-开发指南)
  - [13.1 环境准备](#131-环境准备)
  - [13.2 添加新的受控端能力](#132-添加新的受控端能力)
  - [13.3 添加新的控制端工具](#133-添加新的控制端工具)
  - [13.4 测试建议](#134-测试建议)
- [14. 开源信息](#14-开源信息)

---

## 1. 概述

ACI（Abstract Control Interface，抽象控制接口）是 Zorv AI 的**跨进程能力调用框架**。它定义了一套标准化的协议，让不同的应用（控制端和受控端）可以互相发现和调用能力，而无需了解对方的内部实现。

**核心价值**：

- **标准化**：统一的能力发现和调用协议，降低跨应用集成成本
- **安全**：Token 认证 + 权限控制 + 高危能力确认机制
- **灵活**：支持 AIDL 本地绑定、HTTP 局域网传输、MCP 桥接三种传输方式
- **可扩展**：添加新能力无需修改 AIDL 接口，只需在受控端注册新能力

**开源地址**：[https://github.com/Quor-a/ZorvAI](https://github.com/Quor-a/ZorvAI)

**相关文档**：
- [终端技术架构与开发指南](./TERMINAL_ARCHITECTURE.md)
- [ACI 开发者手册（完整 API）](./ACI_DEVELOPER_GUIDE.md)

---

## 2. ACI 框架设计

### 2.1 核心概念

| 概念 | 说明 |
|------|------|
| **控制端（Controller）** | 发起能力调用的应用，如 Zorv AI 主应用 |
| **受控端（Controlled）** | 暴露能力供其他应用调用的应用，如终端 ACI 服务 |
| **能力（Capability）** | 受控端暴露的一个功能单元，如 `exec`、`create_session` |
| **传输层（Transport）** | 控制端与受控端之间的通信方式（AIDL/HTTP/MCP） |
| **Token** | 用于身份认证的令牌，由 AndroidKeyStore 生成和管理 |
| **审计日志** | 记录所有能力调用的详细日志，用于安全审计 |

### 2.2 架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                        控制端（Controller）                          │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                  AidlAciManager                             │    │
│  │  - 绑定受控端服务                                            │    │
│  │  - 生成/验证 Token                                          │    │
│  │  - 发送能力调用请求                                          │    │
│  └──────────────────────────┬──────────────────────────────────┘    │
│                             │                                       │
│  ┌──────────────────────────▼──────────────────────────────────┐    │
│  │                  MCP-ACI Bridge                             │    │
│  │  - 将 MCP 工具转换为 ACI 能力                               │    │
│  │  - 支持 mcp_aci_call / mcp_aci_list 工具                   │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                     │
└───────────────────────────┬─────────────────────────────────────────┘
                            │
                            │ 传输层
                            │ AIDL / HTTP / MCP
                            │
┌───────────────────────────▼─────────────────────────────────────────┐
│                        受控端（Controlled）                          │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │               BaseAidlAciService                            │    │
│  │  - Token 验证                                               │    │
│  │  - 能力注册与发现                                            │    │
│  │  - 请求路由与响应                                            │    │
│  │  - 审计日志记录                                              │    │
│  └──────────────────────────┬──────────────────────────────────┘    │
│                             │                                       │
│  ┌──────────────────────────▼──────────────────────────────────┐    │
│  │               具体能力实现                                   │    │
│  │                                                             │    │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐        │    │
│  │  │   exec      │  │create_session│  │ list_sessions│        │    │
│  │  │  执行命令   │  │  创建会话   │  │  列出会话   │        │    │
│  │  └─────────────┘  └─────────────┘  └─────────────┘        │    │
│  │                                                             │    │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐        │    │
│  │  │send_input   │  │get_session  │  │ set/get_env │        │    │
│  │  │  发送输入   │  │  会话状态   │  │  环境变量   │        │    │
│  │  └─────────────┘  └─────────────┘  └─────────────┘        │    │
│  │                                                             │    │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐        │    │
│  │  │ capabilities│  │service_status│  │  audit_log  │        │    │
│  │  │  列出能力   │  │  服务状态   │  │  审计日志   │        │    │
│  │  └─────────────┘  └─────────────┘  └─────────────┘        │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.3 设计原则

| 原则 | 说明 |
|------|------|
| **能力即接口** | 一个能力 = 一个功能单元，通过 `capability` 字符串标识 |
| **统一请求模型** | 所有调用使用 `AidlAciRequest`（capability + Bundle params） |
| **统一响应模型** | 所有响应使用 `AidlAciResponse`（success + Bundle data + error） |
| **传输层透明** | 受控端无需关心传输层，AIDL/HTTP/MCP 都走同一套能力定义 |
| **安全优先** | Token 认证 + 权限控制 + 高危能力确认 + 审计日志 |
| **向后兼容** | 新增能力不修改 AIDL 接口，旧版本控制端调用新能力会返回 `CAPABILITY_NOT_FOUND` |

---

## 3. 核心组件详解

### 3.1 BaseAidlAciService — ACI 服务基类

**文件位置**：`aidl-aci-core/src/main/java/.../BaseAidlAciService.kt`

**职责**：ACI 受控端的基类，提供 Token 验证、能力注册、请求路由、审计日志等通用功能。

**核心代码**：

```kotlin
abstract class BaseAidlAciService : Service() {
    // 能力注册
    protected open fun onCreateCapabilities(): List<AidlAciCapability> {
        return emptyList()  // 子类重写
    }

    // Token 验证
    protected open fun onVerifyToken(token: String): Boolean {
        return AidlAciTokenManager.verifyToken(this, token)
    }

    // 请求处理
    protected abstract fun onCall(request: AidlAciRequest): AidlAciResponse

    // AIDL Binder
    private val binder = object : IAidlAciService.Stub() {
        override fun call(request: AidlAciRequest): AidlAciResponse {
            // 1. 验证 Token
            if (!onVerifyToken(request.token)) {
                return AidlAciResponse.error(AidlAciError.AUTH_FAILED, "认证失败")
            }
            // 2. 检查能力是否存在
            val capability = request.capability
            if (capability !in capabilities) {
                return AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND, "能力不存在: $capability")
            }
            // 3. 调用具体实现
            val response = onCall(request)
            // 4. 记录审计日志
            auditLog.record(request, response)
            return response
        }

        override fun listCapabilities(): List<AidlAciCapability> {
            return capabilities
        }
    }
}
```

**生命周期**：

```
Service.onCreate()
    → onCreateCapabilities() 注册能力
    → 绑定到 ServiceManager
    → 等待控制端调用

Service.onBind()
    → 返回 IAidlAciService.Stub

控制端调用 call(request)
    → 验证 Token
    → 检查能力
    → onCall(request) 处理请求
    → 记录审计日志
    → 返回 AidlAciResponse
```

### 3.2 AidlAciRequest / AidlAciResponse — 请求响应模型

**AidlAciRequest（请求）**：

```kotlin
data class AidlAciRequest(
    val capability: String,      // 能力名称，如 "exec"
    val params: Bundle,          // 参数（键值对）
    val token: String = "",      // 认证令牌
    val timeout: Long = 14000    // 超时时间（毫秒）
)
```

**AidlAciResponse（响应）**：

```kotlin
data class AidlAciResponse(
    val success: Boolean,        // 是否成功
    val data: Bundle,            // 返回数据
    val error: AidlAciError?,    // 错误码（失败时）
    val errorMessage: String?    // 错误信息（失败时）
)
```

**使用示例**：

```kotlin
// 构建请求
val request = AidlAciRequest(
    capability = "exec",
    params = Bundle().apply {
        putString("command", "uname -a")
        putLong("timeout", 14000)
    },
    token = AidlAciManager.generateToken(context)
)

// 发送请求
val response = aidlAciManager.call("com.ai.assistance.quro", request)

// 处理响应
if (response.success) {
    val output = response.data.getString("output")
    val exitCode = response.data.getInt("exit_code")
    Log.d("ACI", "Output: $output, Exit: $exitCode")
} else {
    Log.e("ACI", "Error: ${response.errorMessage}")
}
```

### 3.3 AidlAciCapability — 能力定义

```kotlin
data class AidlAciCapability(
    val name: String,           // 能力名称，如 "exec"
    val description: String,    // 能力描述
    val flags: Int = 0,         // 标志位（如 FLAG_DANGEROUS）
    val params: List<String> = emptyList(),  // 参数列表
    val returnType: String = "Bundle"        // 返回类型
)
```

**标志位**：

| 标志 | 值 | 说明 |
|------|----|------|
| `FLAG_NONE` | 0 | 无特殊标志 |
| `FLAG_DANGEROUS` | 1 | 高危能力，需要用户确认 |
| `FLAG_REQUIRES_CONFIRM` | 2 | 需要 `confirm=true` 参数 |

### 3.4 AidlAciError — 错误码

```kotlin
enum class AidlAciError(val code: Int, val message: String) {
    SUCCESS(0, "成功"),
    UNKNOWN_ERROR(1, "未知错误"),
    AUTH_FAILED(2, "认证失败"),
    CAPABILITY_NOT_FOUND(3, "能力不存在"),
    INVALID_PARAMS(4, "参数无效"),
    TIMEOUT(5, "执行超时"),
    INTERNAL_ERROR(6, "内部错误"),
    NOT_ALLOWED(7, "操作不允许"),
    USER_CANCELLED(8, "用户取消"),
    SERVICE_UNAVAILABLE(9, "服务不可用")
}
```

### 3.5 AidlAciManager — 控制端管理器

**文件位置**：`app/src/main/java/com/ai/assistance/quro/aci/QuroAidlAciManager.kt`

**职责**：控制端的核心管理器，负责绑定受控端服务、发送请求、管理连接。

**核心代码**：

```kotlin
class QuroAidlAciManager(private val context: Context) {
    // 绑定受控端服务
    private var service: IAidlAciService? = null
    private var connection: ServiceConnection? = null

    fun bind(packageName: String) {
        val intent = Intent("ai.aci.core.ACTION_BIND").apply {
            setPackage(packageName)
        }
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IAidlAciService.Stub.asInterface(binder)
            }
            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }
        }
        context.bindService(intent, connection!!, Context.BIND_AUTO_CREATE)
    }

    // 调用能力
    fun call(request: AidlAciRequest): AidlAciResponse {
        return service?.call(request)
            ?: AidlAciResponse.error(AidlAciError.SERVICE_UNAVAILABLE, "服务未连接")
    }

    // 列出能力
    fun listCapabilities(): List<AidlAciCapability> {
        return service?.listCapabilities() ?: emptyList()
    }

    // 生成 Token
    fun generateToken(): String {
        return AidlAciTokenManager.generateToken(context)
    }
}
```

---

## 4. 传输层详解

### 4.1 AIDL 本地绑定（默认）

**原理**：通过 Android AIDL（`bindService`）进行本地进程间通信。

**优点**：
- 最低延迟（本地调用）
- 无需网络权限
- Android 原生支持

**缺点**：
- 仅支持同一设备上的应用
- 需要绑定服务

**使用示例**：

```kotlin
// 控制端
val manager = QuroAidlAciManager(context)
manager.bind("com.ai.assistance.quro")  // 绑定受控端

val request = AidlAciRequest(
    capability = "exec",
    params = Bundle().apply {
        putString("command", "ls -la")
    }
)
val response = manager.call(request)
```

### 4.2 ACI HTTP 传输（局域网/本地）

**原理**：通过本地 HTTP 服务器暴露 ACI 能力，支持局域网调用。

**优点**：
- 支持局域网内其他设备调用
- 无需 AIDL 绑定
- 支持跨平台（iOS/Web 也能调用）

**缺点**：
- 需要网络权限
- 延迟较高（HTTP 开销）

**使用示例**：

```kotlin
// 启动 ACI HTTP 服务器
val httpServer = AidlAciHttpServer(port = 8080)
httpServer.start()

// 远程调用
val url = "http://192.168.1.100:8080/aci/call"
val response = httpClient.post(url, body = mapOf(
    "capability" to "exec",
    "params" to mapOf("command" to "uname -a")
))
```

### 4.3 MCP 桥接

**原理**：通过 MCP（Model Context Protocol）桥接，将 ACI 能力转换为 MCP 工具。

**优点**：
- 支持 AI 模型直接调用
- 与 MCP 生态集成
- 支持远程 MCP 服务器

**缺点**：
- 需要 MCP 客户端支持
- 延迟较高

**使用示例**：

```kotlin
// MCP 工具调用
// AI 在对话中使用 mcp_aci_call 工具
{
  "name": "mcp_aci_call",
  "arguments": {
    "serverAlias": "terminal",
    "toolName": "exec",
    "arguments": {"command": "python3 --version"}
  }
}
```

### 4.4 传输层对比

| 特性 | AIDL 本地绑定 | ACI HTTP 传输 | MCP 桥接 |
|------|---------------|---------------|----------|
| **延迟** | 最低（<1ms） | 中等（10-100ms） | 较高（100-500ms） |
| **网络要求** | 无需网络 | 需要局域网 | 需要网络 |
| **跨平台** | 仅 Android | 跨平台 | 跨平台 |
| **安全性** | 高（系统级） | 中等（HTTP） | 中等（MCP） |
| **适用场景** | 同设备应用调用 | 局域网内其他设备 | AI 模型调用 |

---

## 5. Token 认证与安全

### 5.1 Token 生成与验证

**Token 格式**：`aci_token_{pkg}_{ts}_{rand}`

- `pkg`：调用方包名
- `ts`：时间戳
- `rand`：随机字符串

**生成逻辑**：

```kotlin
// AidlAciTokenManager.kt
object AidlAciTokenManager {
    fun generateToken(context: Context): String {
        val pkg = context.packageName
        val ts = System.currentTimeMillis()
        val rand = UUID.randomUUID().toString().take(8)
        val raw = "aci_token_${pkg}_${ts}_${rand}"

        // 使用 AndroidKeyStore 加密
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val secretKey = getOrCreateKey(keyStore)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val encrypted = cipher.doFinal(raw.toByteArray())

        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun verifyToken(context: Context, token: String): Boolean {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            val secretKey = keyStore.getKey("aci_token_key", null) as SecretKey

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decrypted = cipher.doFinal(Base64.decode(token, Base64.NO_WRAP))

            val raw = String(decrypted)
            // 验证格式
            raw.startsWith("aci_token_") &&
            // 验证时间戳（5分钟内有效）
            (System.currentTimeMillis() - raw.split("_")[2].toLong()) < 300_000
        } catch (e: Exception) {
            false
        }
    }
}
```

### 5.2 权限控制

**Manifest 声明**：

```xml
<!-- 控制端声明权限 -->
<permission
    android:name="ai.aci.permission.CALL"
    android:protectionLevel="signature" />

<!-- 受控端声明权限 -->
<uses-permission android:name="ai.aci.permission.CALL" />
```

**权限检查**：

```kotlin
// 受控端检查调用方权限
override fun onVerifyToken(token: String): Boolean {
    // 1. 验证 Token 有效性
    if (!AidlAciTokenManager.verifyToken(this, token)) {
        return false
    }

    // 2. 验证调用方包名
    val callerPackage = getCallingPackage()
    if (callerPackage !in allowedPackages) {
        return false
    }

    // 3. 验证签名
    if (!verifySignature(callerPackage)) {
        return false
    }

    return true
}
```

### 5.3 高危能力确认机制

**FLAG_DANGEROUS 标志**：

```kotlin
// 标记高危能力
val dangerousCapability = AidlAciCapability(
    name = "exec",
    description = "执行命令（可能删除文件）",
    flags = AidlAciCapability.FLAG_DANGEROUS
)

// 控制端调用时必须传入 confirm=true
val request = AidlAciRequest(
    capability = "exec",
    params = Bundle().apply {
        putString("command", "rm -rf /")
        putBoolean("confirm", true)  // 必须确认
    }
)
```

**受控端检查**：

```kotlin
// BaseAidlAciService.kt
override fun onCall(request: AidlAciRequest): AidlAciResponse {
    val capability = capabilities[request.capability]

    // 检查是否需要确认
    if (capability?.flags?.and(AidlAciCapability.FLAG_DANGEROUS) != 0) {
        val confirm = request.params.getBoolean("confirm", false)
        if (!confirm) {
            return AidlAciResponse.error(
                AidlAciError.NOT_ALLOWED,
                "高危能力需要 confirm=true"
            )
        }
    }

    // 处理请求
    return onCall(request)
}
```

---

## 6. 终端 ACI 集成（12 个能力）

### 6.1 能力清单

Zorv AI 终端通过 `QuroTerminalAciService` 暴露 12 个标准化能力：

| 序号 | 能力 | 入参 | 返回 | 说明 |
|------|------|------|------|------|
| 1 | `exec` | `command`(必填) / `timeout`(可选) / `session_id`(可选) | `output` / `exit_code` / `error` | 执行命令 |
| 2 | `create_session` | `name`(可选) | `session_id` / `name` | 创建会话 |
| 3 | `destroy_session` | `session_id`(必填) | `destroyed` | 销毁会话 |
| 4 | `send_input` | `session_id`(必填) / `input`(必填) | `sent` | 发送输入 |
| 5 | `get_session_status` | `session_id`(必填) | `session_id` / `is_alive` / `pid` / `uptime` | 会话状态 |
| 6 | `list_sessions` | — | `sessions` (array) | 列出所有会话 |
| 7 | `set_session_env` | `session_id` / `key` / `value` | `set` | 设置环境变量 |
| 8 | `get_session_env` | `session_id` / `key` | `value` | 获取环境变量 |
| 9 | `list_capabilities` | — | `capabilities` (array) | 列出能力 |
| 10 | `get_service_status` | — | `running` / `session_count` / `uptime` | 服务状态 |
| 11 | `get_audit_log` | `limit`(可选) | `logs` (array) | 审计日志 |
| 12 | `help` | — | `help_text` | 帮助信息 |

### 6.2 exec 能力详解

**功能**：在终端中执行命令并返回结果。

**入参**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `command` | String | 是 | 要执行的命令 |
| `timeout` | Long | 否 | 超时时间（毫秒），默认 14000 |
| `session_id` | String | 否 | 指定会话 ID，默认使用当前会话 |

**返回**：

| 参数 | 类型 | 说明 |
|------|------|------|
| `output` | String | 命令输出 |
| `exit_code` | Int | 退出码（0 表示成功） |
| `error` | String | 错误信息（失败时） |

**调用示例**：

```kotlin
// 构建请求
val request = AidlAciRequest(
    capability = "exec",
    params = Bundle().apply {
        putString("command", "uname -a")
        putLong("timeout", 14000)
    }
)

// 发送请求
val response = aidlAciManager.call("com.ai.assistance.quro", request)

// 处理响应
if (response.success) {
    val output = response.data.getString("output")
    val exitCode = response.data.getInt("exit_code")
    println("Output: $output")
    println("Exit Code: $exitCode")
}
```

**实现逻辑**：

```kotlin
// Qu roTerminalAciService.kt
private fun handleExec(params: Bundle): AidlAciResponse {
    val command = params.getString("command")
        ?: return AidlAciResponse.error(AidlAciError.INVALID_PARAMS, "缺少 command 参数")

    val timeout = params.getLong("timeout", 14000)
    val sessionId = params.getString("session_id")

    return try {
        val session = if (sessionId != null) {
            QuroTerminalSessionManager.getSession(sessionId)
        } else {
            ensureTerminalSession()
        }

        if (session == null) {
            return AidlAciResponse.error(AidlAciError.SERVICE_UNAVAILABLE, "终端会话不可用")
        }

        val result = QuroTerminalController.getInstance().runCommand(command, timeout)
        AidlAciResponse.success(Bundle().apply {
            putString("output", result.output)
            putInt("exit_code", result.exitCode)
            putString("error", result.error)
        })
    } catch (e: TimeoutException) {
        AidlAciResponse.error(AidlAciError.TIMEOUT, "命令执行超时")
    } catch (e: Exception) {
        AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, e.message ?: "未知错误")
    }
}
```

### 6.3 会话管理能力

**create_session**：

```kotlin
private fun handleCreateSession(params: Bundle): AidlAciResponse {
    val name = params.getString("name") ?: "session_${System.currentTimeMillis()}"

    return try {
        val session = QuroTerminalSessionManager.createSession(this, name)
        AidlAciResponse.success(Bundle().apply {
            putString("session_id", session.id)
            putString("name", session.name)
        })
    } catch (e: Exception) {
        AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, e.message ?: "创建会话失败")
    }
}
```

**list_sessions**：

```kotlin
private fun handleListSessions(): AidlAciResponse {
    val sessions = QuroTerminalSessionManager.listSessions()
    val sessionArray = sessions.map { session ->
        Bundle().apply {
            putString("id", session["id"] as String)
            putString("name", session["name"] as String)
            putBoolean("is_alive", session["is_alive"] as Boolean)
            putInt("pid", session["pid"] as Int)
            putLong("uptime", session["uptime"] as Long)
        }
    }.toTypedArray()

    return AidlAciResponse.success(Bundle().apply {
        putParcelableArray("sessions", sessionArray)
    })
}
```

### 6.4 环境变量能力

**set_session_env**：

```kotlin
private fun handleSetSessionEnv(params: Bundle): AidlAciResponse {
    val sessionId = params.getString("session_id")
        ?: return AidlAciResponse.error(AidlAciError.INVALID_PARAMS, "缺少 session_id")
    val key = params.getString("key")
        ?: return AidlAciResponse.error(AidlAciError.INVALID_PARAMS, "缺少 key")
    val value = params.getString("value")
        ?: return AidlAciResponse.error(AidlAciError.INVALID_PARAMS, "缺少 value")

    val session = QuroTerminalSessionManager.getSession(sessionId)
        ?: return AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND, "会话不存在")

    session.setEnv(key, value)
    return AidlAciResponse.success(Bundle().apply {
        putBoolean("set", true)
    })
}
```

### 6.5 服务能力

**get_service_status**：

```kotlin
private fun handleGetServiceStatus(): AidlAciResponse {
    return AidlAciResponse.success(Bundle().apply {
        putBoolean("running", true)
        putInt("session_count", QuroTerminalSessionManager.getSessionCount())
        putLong("uptime", getUptime())
    })
}
```

---

## 7. 原生 ACI 桥接（C/C++）

### 7.1 libacihost.so

**文件位置**：`app/src/main/cpp/aci/aci_native.{h,c}`

**编译目标**：`libacihost.so`（CMake 目标 `acihost`）

**职责**：让 C/C++ 代码直接调用 ACI 能力，无需经过 Kotlin 层。

### 7.2 C API 接口

```c
// aci_native.h
#ifndef ACI_NATIVE_H
#define ACI_NATIVE_H

#include <stdbool.h>

// 检查 ACI 是否可用
bool aci_available();

// 列出可用能力
bool aci_list(char* out, int out_size, const char* cap);

// 调用能力
bool aci_call(
    const char* pkg,        // 目标包名
    const char* cap,        // 能力名称
    const char* args,       // 参数 JSON
    int conf,               // 是否确认
    char* out,              // 输出缓冲区
    int out_size,           // 输出缓冲区大小
    char* cap_out           // 能力输出
);

#endif
```

### 7.3 JNI 回调

```kotlin
// AciNativeBridge.kt
object AciNativeBridge {
    @JvmStatic
    fun ensureLoaded() {
        System.loadLibrary("acihost")
    }

    @JvmStatic
    fun aciAvailable(): Boolean {
        return try {
            nativeAciAvailable()
        } catch (e: Throwable) {
            Log.e(TAG, "ACI native 调用失败", e)
            false  // 异常吞掉，返回 false
        }
    }

    @JvmStatic
    fun aciCall(pkg: String, cap: String, args: String): String {
        return try {
            nativeAciCall(pkg, cap, args)
        } catch (e: Throwable) {
            Log.e(TAG, "ACI native 调用失败", e)
            "{\"ok\":false,\"error\":\"${e.message}\"}"
        }
    }

    private external fun nativeAciAvailable(): Boolean
    private external fun nativeAciCall(pkg: String, cap: String, args: String): String
}
```

**⚠️ 关键约束**：`qurohost` 是独立原生进程（不在 JVM 内），不能用 JNI / `libacihost.so`。它的控制协议是 `@qurohost`（US 前缀），Kotlin 主动请求 → qurohost 响应的单向模式。

---

## 8. MCP-ACI 桥接

### 8.1 桥接原理

```
AI 模型 → MCP 工具调用 → MCP-ACI Bridge → ACI 能力调用 → 受控端
```

### 8.2 能力映射

MCP 工具名称自动转换为 ACI 能力 ID：

| MCP 工具 | ACI 能力 | 说明 |
|----------|----------|------|
| `weather_query` | `mcp_weather_query` | 天气查询 |
| `web_search` | `mcp_web_search` | 网页搜索 |
| `terminal_exec` | `mcp_terminal_exec` | 终端命令执行 |

### 8.3 使用示例

```json
{
  "name": "mcp_aci_call",
  "arguments": {
    "serverAlias": "weather",
    "toolName": "get_current_weather",
    "arguments": {"city": "北京"}
  }
}
```

**桥接工具**：

| 工具 | 参数 | 说明 |
|------|------|------|
| `mcp_aci_list` | — | 列出所有可通过 ACI 调用的 MCP 工具 |
| `mcp_aci_call` | `serverAlias`(必填) / `toolName`(必填) / `arguments`(可选) | 通过 ACI 调用 MCP 工具 |
| `mcp_aci_bridge` | `action`(必填: refresh/list) | 管理 MCP-ACI 桥接器 |

---

## 9. 控制台 UI（LAN 控制台）

### 9.1 架构

```
┌─────────────────────────────────────────────────┐
│               控制台 UI（WebView）                │
│  http://192.168.1.100:8080                      │
└───────────────────────────┬─────────────────────┘
                            │ HTTP
┌───────────────────────────▼─────────────────────┐
│               ACI HTTP 服务器                    │
│  /aci/list  → 列出能力                          │
│  /aci/call  → 调用能力                          │
└───────────────────────────┬─────────────────────┘
                            │
┌───────────────────────────▼─────────────────────┐
│               受控端服务                          │
│  Qu roTerminalAciService / 其他 ACI 服务          │
└─────────────────────────────────────────────────┘
```

### 9.2 功能

- **能力发现**：自动扫描局域网内所有 ACI 服务
- **能力调用**：通过 Web 界面调用任意能力
- **结果展示**：实时显示调用结果
- **日志查看**：查看审计日志

---

## 10. 跨应用接入指南

### 10.1 作为控制端（调用其他 ACI 服务）

**步骤 1**：添加 `aidl-aci-core` 依赖

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":aidl-aci-core"))
}
```

**步骤 2**：绑定受控端服务

```kotlin
val manager = QuroAidlAciManager(context)
manager.bind("com.other.app")  // 绑定目标应用

// 等待连接
while (manager.isConnected().not()) {
    delay(100)
}

// 调用能力
val request = AidlAciRequest(
    capability = "exec",
    params = Bundle().apply {
        putString("command", "ls -la")
    }
)
val response = manager.call(request)
```

### 10.2 作为受控端（暴露能力给其他应用）

**步骤 1**：继承 `BaseAidlAciService`

```kotlin
class MyAciService : BaseAidlAciService() {
    override fun onCreateCapabilities(): List<AidlAciCapability> {
        return listOf(
            AidlAciCapability("my_capability", "我的能力")
        )
    }

    override fun onCall(request: AidlAciRequest): AidlAciResponse {
        return when (request.capability) {
            "my_capability" -> handleMyCapability(request.params)
            else -> AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND, "能力不存在")
        }
    }

    private fun handleMyCapability(params: Bundle): AidlAciResponse {
        // 实现逻辑
        return AidlAciResponse.success(Bundle().apply {
            putString("result", "成功")
        })
    }
}
```

**步骤 2**：注册到 Manifest

```xml
<service
    android:name=".MyAciService"
    android:exported="true"
    android:permission="ai.aci.permission.CALL">
    <intent-filter>
        <action android:name="ai.aci.core.ACTION_BIND" />
    </intent-filter>
</service>
```

### 10.3 终端 ACI 接入示例

**通过 AIDL 调用终端 exec**：

```kotlin
// 1. 绑定终端 ACI 服务
val manager = QuroAidlAciManager(context)
manager.bind("com.ai.assistance.quro")

// 2. 调用 exec 能力
val request = AidlAciRequest(
    capability = "exec",
    params = Bundle().apply {
        putString("command", "python3 -c 'print(1+2)'")
        putLong("timeout", 14000)
    }
)
val response = manager.call(request)

// 3. 获取结果
if (response.success) {
    val output = response.data.getString("output")  // "3"
    val exitCode = response.data.getInt("exit_code")  // 0
}
```

**通过 HTTP 调用终端 exec**：

```bash
# curl 示例
curl -X POST http://192.168.1.100:8080/aci/call \
  -H "Content-Type: application/json" \
  -d '{
    "capability": "exec",
    "params": {
      "command": "uname -a"
    }
  }'
```

**通过 Deep Link 调用终端 exec**：

```kotlin
// Android Intent
val intent = Intent(Intent.ACTION_VIEW,
    Uri.parse("quro://terminal/exec?cmd=ls -la"))
startActivity(intent)
```

**通过 ContentProvider 调用终端 exec**：

```kotlin
// 查询 ContentProvider
val cursor = contentResolver.query(
    Uri.parse("content://com.ai.assistance.quro.terminal/exec?cmd=uname -a"),
    null, null, null, null
)
```

---

## 11. ACI 工具集

### 11.1 工具清单

Zorv AI 提供以下 ACI 工具供 AI 在对话中使用：

| 工具 | 说明 | 参数 |
|------|------|------|
| `aci_list` | 列出可用 ACI 服务 | `package`(可选) |
| `aci_call` | 调用 ACI 能力 | `target_package`(必填) / `capability`(必填) / `args`(可选) |
| `aci_help` | 显示 ACI 帮助 | — |
| `mcp_aci_list` | 列出可通过 ACI 调用的 MCP 工具 | — |
| `mcp_aci_call` | 通过 ACI 调用 MCP 工具 | `serverAlias`(必填) / `toolName`(必填) / `arguments`(可选) |
| `mcp_aci_bridge` | 管理 MCP-ACI 桥接器 | `action`(必填: refresh/list) |

### 11.2 aci_call 详解

**功能**：调用指定应用的 ACI 能力。

**参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `target_package` | String | 是 | 目标应用包名 |
| `capability` | String | 是 | 能力名称 |
| `args` | String | 否 | 参数 JSON 字符串 |
| `confirm` | Boolean | 否 | 是否确认高危操作，默认 false |

**使用示例**：

```json
{
  "name": "aci_call",
  "arguments": {
    "target_package": "com.ai.assistance.quro",
    "capability": "exec",
    "args": "{\"command\": \"ls -la\"}",
    "confirm": false
  }
}
```

### 11.3 aci_list 详解

**功能**：列出所有可用的 ACI 服务及其能力。

**返回**：

```json
{
  "services": [
    {
      "package": "com.ai.assistance.quro",
      "capabilities": [
        {"name": "exec", "description": "执行命令"},
        {"name": "create_session", "description": "创建会话"},
        {"name": "list_sessions", "description": "列出所有会话"}
      ]
    }
  ]
}
```

---

## 12. 常见问题与故障排除

| 现象 | 说明 / 处理 |
|------|-------------|
| **ACI 调用返回 `AUTH_FAILED`** | Token 无效或过期（5分钟有效期）。重新生成 Token：`AidlAciManager.generateToken()` |
| **ACI 调用返回 `CAPABILITY_NOT_FOUND`** | 能力名称错误。使用 `aci_list` 查看可用能力 |
| **ACI 调用返回 `SERVICE_UNAVAILABLE`** | 受控端服务未运行。检查服务是否在 Manifest 中注册，是否已启动 |
| **ACI 调用返回 `TIMEOUT`** | 命令执行超时。增加 `timeout` 参数，或优化命令性能 |
| **ACI 调用返回 `NOT_ALLOWED`** | 高危能力未确认。添加 `confirm=true` 参数 |
| **终端 ACI 服务未启动** | 检查 `QuroTerminalAciService` 是否在 Manifest 中注册，权限 `ai.aci.permission.CALL` 是否声明 |
| **终端 exec 返回空输出** | 检查命令是否正确，超时是否太短。使用 `list_sessions` 确认会话状态 |
| **终端会话丢失** | 前台服务每 15 秒自动重建会话。检查通知栏是否显示「终端运行中」 |
| **MCP-ACI 桥接失败** | 检查 MCP 服务器是否连接，工具名称是否正确 |
| **原生 ACI 调用失败** | `libacihost.so` 未加载。检查 `AciNativeBridge.ensureLoaded()` 是否调用 |

---

## 13. 开发指南

### 13.1 环境准备

- **Android Studio**：2024.1+ (Koala)
- **JDK**：17+
- **Android SDK**：compileSdk 36, minSdk 24 (ACI 库) / 26 (App)
- **Gradle**：8.13+
- **Kotlin**：2.3+

### 13.2 添加新的受控端能力

**步骤 1**：在 `onCreateCapabilities` 中添加能力定义

```kotlin
class MyAciService : BaseAidlAciService() {
    override fun onCreateCapabilities(): List<AidlAciCapability> {
        val caps = super.onCreateCapabilities().toMutableList()
        caps.add(AidlAciCapability(
            name = "my_new_capability",
            description = "我的新能力",
            flags = AidlAciCapability.FLAG_NONE
        ))
        return caps
    }
}
```

**步骤 2**：在 `onCall` 中添加能力处理逻辑

```kotlin
override fun onCall(request: AidlAciRequest): AidlAciResponse {
    return when (request.capability) {
        "my_new_capability" -> handleMyNewCapability(request.params)
        // ... 其他能力
        else -> AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND, "能力不存在")
    }
}

private fun handleMyNewCapability(params: Bundle): AidlAciResponse {
    // 实现逻辑
    return AidlAciResponse.success(Bundle().apply {
        putString("result", "成功")
    })
}
```

**步骤 3**：在 `AndroidManifest.xml` 中注册服务

```xml
<service
    android:name=".MyAciService"
    android:exported="true"
    android:permission="ai.aci.permission.CALL">
    <intent-filter>
        <action android:name="ai.aci.core.ACTION_BIND" />
    </intent-filter>
</service>
```

### 13.3 添加新的控制端工具

**步骤 1**：在 `QuroBuiltInTools.kt` 中注册工具

```kotlin
registerTool(ToolDefinition(
    name = "my_aci_tool",
    description = "我的 ACI 工具",
    parameters = listOf(
        ParameterDefinition("target_package", "目标包名", required = true),
        ParameterDefinition("capability", "能力名称", required = true)
    )
))
```

**步骤 2**：实现工具执行逻辑

```kotlin
override suspend fun executeTool(name: String, args: Map<String, Any>): ToolResult {
    return when (name) {
        "my_aci_tool" -> {
            val targetPackage = args["target_package"] as String
            val capability = args["capability"] as String
            val manager = QuroAidlAciManager(context)
            manager.bind(targetPackage)
            val response = manager.call(AidlAciRequest(capability, Bundle()))
            ToolResult.success(response.data.toString())
        }
        // ... 其他工具
    }
}
```

### 13.4 测试建议

**功能测试**：
- 测试 Token 生成与验证
- 测试能力注册与发现
- 测试请求路由与响应
- 测试错误码返回

**安全测试**：
- 测试无效 Token 拒绝
- 测试高危能力确认机制
- 测试审计日志记录

**性能测试**：
- 测试 AIDL 本地调用延迟
- 测试 HTTP 传输延迟
- 测试并发调用能力

**兼容性测试**：
- 测试 Android 8.0+ 兼容性
- 测试不同传输层
- 测试跨应用调用

---

## 14. 开源信息

| 项目 | 信息 |
|------|------|
| **开源地址** | [https://github.com/Quor-a/ZorvAI](https://github.com/Quor-a/ZorvAI) |
| **终端技术架构** | [docs/TERMINAL_ARCHITECTURE.md](./TERMINAL_ARCHITECTURE.md) |
| **ACI 开发者手册（完整 API）** | [docs/ACI_DEVELOPER_GUIDE.md](./ACI_DEVELOPER_GUIDE.md) |
| **当前版本** | v1.0.67 |
| **许可证** | Apache License 2.0 |
| **技术栈** | Kotlin 2.3 + Jetpack Compose |
| **最低支持** | Android 8.0 (API 26) |
| **目标版本** | Android 14 (API 34) |

---

> 本文档由 Zorv AI 开发团队维护。如有问题或建议，请在 [GitHub Issues](https://github.com/Quor-a/ZorvAI/issues) 反馈。
