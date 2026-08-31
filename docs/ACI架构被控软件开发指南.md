# ACI 架构被控软件开发指南

> **版本**：v1.0.69 | **最后更新**：2026-08-30
>
> **适用 SDK**：`aidl-aci-core`（原 `aci-core`，v1.0.26 重命名）

## 📋 目录

- [1. ACI 架构概述](#1-aci-架构概述)
- [2. 核心概念与角色](#2-核心概念与角色)
- [3. 完整 ACI 功能清单](#3-完整-aci-功能清单)
- [4. 受控端开发实战](#4-受控端开发实战)
- [5. 能力定义与实现](#5-能力定义与实现)
- [6. Token 认证与安全](#6-token-认证与安全)
- [7. 传输层详解](#7-传输层详解)
- [8. 控制端集成指南](#8-控制端集成指南)
- [9. 终端 ACI 集成（12 个能力）](#9-终端-aci-集成12-个能力)
- [10. 原生 ACI 桥接（C/C++）](#10-原生-aci-桥接cc)
- [11. MCP-ACI 桥接](#11-mcp-aci-桥接)
- [12. 跨应用接入示例](#12-跨应用接入示例)
- [13. 调试与故障排除](#13-调试与故障排除)
- [14. 最佳实践](#14-最佳实践)

---

## 1. ACI 架构概述

### 1.1 什么是 ACI

**ACI（Agent Capability Interface，智能体能力接口）** 是一套运行在**同一台 Android 设备内**、**无需 Root**、基于 **AIDL Binder** 的本地跨应用调用框架。

**核心价值**：
- **标准化**：统一的能力发现和调用协议，降低跨应用集成成本
- **安全**：Token 认证 + 权限控制 + 高危能力确认机制
- **灵活**：支持 AIDL 本地绑定、HTTP 局域网传输、MCP 桥接三种传输方式
- **可扩展**：添加新能力无需修改 AIDL 接口，只需在受控端注册新能力

### 1.2 架构图

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

---

## 2. 核心概念与角色

### 2.1 角色定义

| 角色 | 职责 | 关键类 |
|------|------|--------|
| **控制端（Controller）** | 发起能力调用的应用，如 Zorv AI 主应用 | `QuroAidlAciManager` + `aci-core` 的 `IAidlAciService` 桩 |
| **受控端（Controlled）** | 暴露能力供其他应用调用的应用 | `BaseAidlAciService` / `Capability` / `AidlAciRequest` / `AidlAciResponse` |
| **传输层（Transport）** | 控制端与受控端之间的通信方式 | AIDL / HTTP / MCP |
| **Token** | 用于身份认证的令牌 | AndroidKeyStore AES/GCM |

### 2.2 核心数据结构

#### AidlAciRequest（请求）
```java
public class AidlAciRequest implements Parcelable {
    public String token;           // 认证令牌
    public String capability;      // 能力名称
    public Bundle params;          // 参数键值对
    public long timestamp;         // 请求时间戳
    public String sourcePackage;   // 调用方包名
}
```

#### AidlAciResponse（响应）
```java
public class AidlAciResponse implements Parcelable {
    public boolean success;        // 是否成功
    public Bundle data;            // 返回数据
    public int errorCode;          // 错误码
    public String errorMessage;    // 错误信息
}
```

#### Capability（能力定义）
```java
public class Capability {
    public String id;              // 能力 ID（如 "exec"）
    public String name;            // 显示名称
    public String description;     // 描述
    public boolean requireUserConfirm; // 是否需要用户确认
    public Bundle metadata;        // 元数据
}
```

---

## 3. 完整 ACI 功能清单

### 3.1 基础能力

| 能力 | 说明 | 参数 | 返回值 |
|------|------|------|--------|
| `ping` | 健康检查 | 无 | `{"pong": true}` |
| `capabilities` | 列出所有能力 | 无 | 能力列表 JSON |
| `service_status` | 服务状态 | 无 | 运行时间、内存使用等 |

### 3.2 终端相关能力

| 能力 | 说明 | 参数 | 返回值 |
|------|------|------|--------|
| `exec` | 执行命令 | `{"command": "ls -la", "session_id": "..."}` | 命令输出 |
| `create_session` | 创建终端会话 | `{"mode": "linux"}` | 会话 ID |
| `list_sessions` | 列出所有会话 | 无 | 会话列表 |
| `get_session` | 获取会话状态 | `{"session_id": "..."}` | 会话信息 |
| `send_input` | 发送输入 | `{"session_id": "...", "input": "ls"}` | 无 |
| `interrupt` | 中断会话 | `{"session_id": "..."}` | 无 |
| `destroy_session` | 销毁会话 | `{"session_id": "..."}` | 无 |

### 3.3 环境变量能力

| 能力 | 说明 | 参数 | 返回值 |
|------|------|------|--------|
| `get_env` | 获取环境变量 | `{"name": "PATH"}` | 变量值 |
| `set_env` | 设置环境变量 | `{"name": "MY_VAR", "value": "hello"}` | 无 |
| `list_env` | 列出所有环境变量 | 无 | 环境变量列表 |

### 3.4 文件系统能力

| 能力 | 说明 | 参数 | 返回值 |
|------|------|------|--------|
| `read_file` | 读取文件 | `{"path": "/sdcard/file.txt"}` | 文件内容 |
| `write_file` | 写入文件 | `{"path": "...", "content": "..."}` | 无 |
| `list_dir` | 列出目录 | `{"path": "/sdcard/"}` | 目录内容 |
| `file_info` | 获取文件信息 | `{"path": "..."}` | 文件元数据 |

### 3.5 应用交互能力

| 能力 | 说明 | 参数 | 返回值 |
|------|------|------|--------|
| `intent` | 发送 Intent | `{"action": "...", "package": "..."}` | 无 |
| `provider` | 读写 ContentProvider | `{"uri": "...", "op": "query"}` | 查询结果 |
| `broadcast` | 发送广播 | `{"action": "...", "extras": {...}}` | 无 |
| `notification` | 显示通知 | `{"title": "...", "text": "..."}` | 无 |

### 3.6 系统信息能力

| 能力 | 说明 | 参数 | 返回值 |
|------|------|------|--------|
| `device_info` | 获取设备信息 | 无 | 设备型号、系统版本等 |
| `battery_status` | 电池状态 | 无 | 电量、充电状态 |
| `network_info` | 网络信息 | 无 | 网络类型、连接状态 |
| `installed_apps` | 已安装应用 | 无 | 应用列表 |

### 3.7 安全相关能力

| 能力 | 说明 | 参数 | 返回值 |
|------|------|------|--------|
| `audit_log` | 获取审计日志 | `{"limit": 100}` | 日志记录 |
| `permission_check` | 检查权限 | `{"permission": "..."}` | 权限状态 |
| `token_info` | Token 信息 | 无 | Token 元数据 |

### 3.8 高危能力（需用户确认）

| 能力 | 说明 | 风险等级 |
|------|------|----------|
| `install_app` | 安装应用 | 🔴 高 |
| `uninstall_app` | 卸载应用 | 🔴 高 |
| `root_command` | 执行 root 命令 | 🔴 高 |
| `delete_file` | 删除文件 | 🟡 中 |
| `send_sms` | 发送短信 | 🟡 中 |
| `make_call` | 拨打电话 | 🟡 中 |
| `access_camera` | 访问摄像头 | 🟡 中 |
| `access_location` | 访问位置 | 🟡 中 |

---

## 4. 受控端开发实战

### 4.1 环境准备

#### 4.1.1 获取 aidl-aci-core 库

**方式 A：从 Release 下载**
```bash
# 从 GitHub Release 下载最新的 aidl-aci-core-release.aar
wget https://github.com/Quor-a/ZorvAI/releases/download/v1.0.69/aidl-aci-core-release.aar
```

**方式 B：Gradle 依赖**
```kotlin
// app/build.gradle.kts
dependencies {
    implementation(files("libs/aidl-aci-core-release.aar"))
}
```

### 4.2 添加依赖

在你的受控端模块的 `build.gradle.kts` 中添加：
```kotlin
dependencies {
    implementation(files("libs/aidl-aci-core-release.aar"))
    implementation("androidx.annotation:annotation:1.7.1")
}
```

### 4.3 声明权限与清单

在受控端 `AndroidManifest.xml` 中添加：

```xml
<manifest ...>
    <!-- 引用控制端定义的权限（不要自己定义！） -->
    <uses-permission android:name="ai.aci.permission.CALL" />
    <uses-permission android:name="ai.aci.permission.DISCOVER" />
    <uses-permission android:name="ai.aci.permission.CALL_DANGEROUS" />
    <uses-permission android:name="android.permission.INTERNET" />
    
    <!-- 剥除库可能带入的权限定义（避免同名异签名冲突） -->
    <permission android:name="ai.aci.permission.CALL" tools:node="remove" />
    <permission android:name="ai.aci.permission.DISCOVER" tools:node="remove" />
    <permission android:name="ai.aci.permission.CALL_DANGEROUS" tools:node="remove" />
    
    <!-- 声明查询（Android 11+ 包可见性） -->
    <queries>
        <intent>
            <action android:name="ai.aci.intent.BIND" />
        </intent>
    </queries>
    
    <application ...>
        <!-- 注册 ACI 服务 -->
        <service
            android:name=".MyAciService"
            android:exported="true"
            android:permission="ai.aci.permission.CALL">
            <intent-filter>
                <action android:name="ai.aci.intent.BIND" />
            </intent-filter>
        </service>
    </application>
</manifest>
```

### 4.4 实现受控端服务

创建你的 ACI 服务类，继承 `BaseAidlAciService`：

```kotlin
package com.example.myapp.aci

import ai.aidl.aci.core.BaseAidlAciService
import ai.aidl.aci.core.Capability
import ai.aidl.aci.core.AidlAciRequest
import ai.aidl.aci.core.AidlAciResponse
import android.os.Bundle
import android.util.Log

class MyAciService : BaseAidlAciService() {
    
    companion object {
        private const val TAG = "MyAciService"
    }
    
    override fun onCreateCapabilities(capabilities: MutableList<Capability>) {
        Log.d(TAG, "onCreateCapabilities: 注册能力")
        
        // 注册基础能力
        capabilities.add(Capability(
            id = "greet",
            name = "打招呼",
            description = "返回问候语",
            requireUserConfirm = false
        ))
        
        // 注册需要用户确认的能力
        capabilities.add(Capability(
            id = "dangerous_action",
            name = "危险操作",
            description = "执行需要用户确认的操作",
            requireUserConfirm = true
        ))
        
        // 注册带参数的能力
        capabilities.add(Capability(
            id = "calculate",
            name = "计算器",
            description = "执行数学计算，参数: expression",
            requireUserConfirm = false
        ))
        
        // 注册文件操作能力
        capabilities.add(Capability(
            id = "read_file",
            name = "读取文件",
            description = "读取指定路径的文件内容",
            requireUserConfirm = false
        ))
        
        // 注册设备信息能力
        capabilities.add(Capability(
            id = "device_info",
            name = "设备信息",
            description = "获取设备详细信息",
            requireUserConfirm = false
        ))
        
        Log.d(TAG, "注册了 ${capabilities.size} 个能力")
    }
    
    override fun onCall(request: AidlAciRequest): AidlAciResponse {
        Log.d(TAG, "onCall: capability=${request.capability}, params=${request.params}")
        
        return when (request.capability) {
            "greet" -> handleGreet(request)
            "dangerous_action" -> handleDangerousAction(request)
            "calculate" -> handleCalculate(request)
            "read_file" -> handleReadFile(request)
            "device_info" -> handleDeviceInfo(request)
            else -> AidlAciResponse().apply {
                success = false
                errorCode = -1
                errorMessage = "未知能力: ${request.capability}"
            }
        }
    }
    
    private fun handleGreet(request: AidlAciRequest): AidlAciResponse {
        val name = request.params?.getString("name") ?: "世界"
        val greeting = "你好, $name! 来自 MyAciService 的问候 👋"
        
        return AidlAciResponse().apply {
            success = true
            data = Bundle().apply {
                putString("greeting", greeting)
                putString("timestamp", System.currentTimeMillis().toString())
            }
        }
    }
    
    private fun handleDangerousAction(request: AidlAciRequest): AidlAciResponse {
        // 这个能力需要用户确认（requireUserConfirm = true）
        // 如果控制端没有传 confirmed=true，会在这里被拦截
        
        val action = request.params?.getString("action") ?: "default"
        
        return AidlAciResponse().apply {
            success = true
            data = Bundle().apply {
                putString("result", "危险操作 '$action' 已执行")
                putBoolean("confirmed", true)
            }
        }
    }
    
    private fun handleCalculate(request: AidlAciRequest): AidlAciResponse {
        val expression = request.params?.getString("expression") ?: return AidlAciResponse().apply {
            success = false
            errorCode = -2
            errorMessage = "缺少 expression 参数"
        }
        
        return try {
            // 简单计算器实现（生产环境请使用安全的表达式解析器）
            val result = evaluateExpression(expression)
            
            AidlAciResponse().apply {
                success = true
                data = Bundle().apply {
                    putString("expression", expression)
                    putDouble("result", result)
                }
            }
        } catch (e: Exception) {
            AidlAciResponse().apply {
                success = false
                errorCode = -3
                errorMessage = "计算错误: ${e.message}"
            }
        }
    }
    
    private fun handleReadFile(request: AidlAciRequest): AidlAciResponse {
        val path = request.params?.getString("path") ?: return AidlAciResponse().apply {
            success = false
            errorCode = -2
            errorMessage = "缺少 path 参数"
        }
        
        return try {
            val file = java.io.File(path)
            if (!file.exists()) {
                return AidlAciResponse().apply {
                    success = false
                    errorCode = -4
                    errorMessage = "文件不存在: $path"
                }
            }
            
            if (!file.canRead()) {
                return AidlAciResponse().apply {
                    success = false
                    errorCode = -5
                    errorMessage = "无法读取文件: $path"
                }
            }
            
            val content = file.readText()
            
            AidlAciResponse().apply {
                success = true
                data = Bundle().apply {
                    putString("path", path)
                    putString("content", content)
                    putLong("size", file.length())
                    putLong("lastModified", file.lastModified())
                }
            }
        } catch (e: Exception) {
            AidlAciResponse().apply {
                success = false
                errorCode = -6
                errorMessage = "读取文件失败: ${e.message}"
            }
        }
    }
    
    private fun handleDeviceInfo(request: AidlAciRequest): AidlAciResponse {
        return AidlAciResponse().apply {
            success = true
            data = Bundle().apply {
                putString("manufacturer", android.os.Build.MANUFACTURER)
                putString("model", android.os.Build.MODEL)
                putString("device", android.os.Build.DEVICE)
                putString("sdk_version", android.os.Build.VERSION.SDK_INT.toString())
                putString("android_version", android.os.Build.VERSION.RELEASE)
                putString("app_version", getAppVersion())
            }
        }
    }
    
    private fun getAppVersion(): String {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            "${pInfo.versionName} (${pInfo.versionCode})"
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    private fun evaluateExpression(expression: String): Double {
        // 简单的表达式求值（仅支持基本四则运算）
        // 生产环境请使用 JavaScript 引擎或专门的表达式解析器
        val cleanExpression = expression.replace(" ", "")
        
        // 使用 JavaScript 引擎
        val engine = javax.script.ScriptEngineManager().getEngineByName("JavaScript")
            ?: throw RuntimeException("JavaScript 引擎不可用")
        
        return engine.eval(cleanExpression) as Double
    }
    
    override fun onCheckPermission(request: AidlAciRequest): Boolean {
        // 自定义权限检查逻辑
        // 默认实现检查 token 有效性
        return super.onCheckPermission(request)
    }
}
```

### 4.5 注册服务

在 `AndroidManifest.xml` 中注册服务：

```xml
<service
    android:name=".MyAciService"
    android:exported="true"
    android:permission="ai.aci.permission.CALL">
    <intent-filter>
        <action android:name="ai.aci.intent.BIND" />
    </intent-filter>
</service>
```

### 4.6 测试服务

创建测试类验证服务：

```kotlin
package com.example.myapp.aci

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.aidl.aci.core.AidlAciRequest
import ai.aidl.aci.core.AidlAciManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MyAciServiceTest {
    
    private lateinit var context: Context
    private lateinit var aciManager: AidlAciManager
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        aciManager = AidlAciManager.getInstance(context)
    }
    
    @Test
    fun testGreetCapability() {
        // 绑定到服务
        val intent = Intent(context, MyAciService::class.java)
        aciManager.bind(intent)
        
        // 创建请求
        val request = AidlAciRequest().apply {
            capability = "greet"
            params = Bundle().apply {
                putString("name", "测试用户")
            }
        }
        
        // 发送请求
        val response = aciManager.call(request)
        
        // 验证结果
        assert(response.success)
        assert(response.data?.getString("greeting")?.contains("测试用户") == true)
    }
    
    @Test
    fun testCalculateCapability() {
        val intent = Intent(context, MyAciService::class.java)
        aciManager.bind(intent)
        
        val request = AidlAciRequest().apply {
            capability = "calculate"
            params = Bundle().apply {
                putString("expression", "2 + 3 * 4")
            }
        }
        
        val response = aciManager.call(request)
        
        assert(response.success)
        assert(response.data?.getDouble("result") == 14.0)
    }
}
```

---

## 5. 能力定义与实现

### 5.1 能力命名规范

```kotlin
// 能力 ID 使用小写字母、数字和下划线
"read_file"      // ✅ 好
"get_device_info" // ✅ 好
"readFile"       // ❌ 避免驼峰
"READ_FILE"      // ❌ 避免全大写
```

### 5.2 参数设计原则

```kotlin
capabilities.add(Capability(
    id = "search_files",
    name = "搜索文件",
    description = "在指定目录中搜索文件",
    requireUserConfirm = false,
    metadata = Bundle().apply {
        // 定义参数schema（供控制端LLM理解）
        putString("params_schema", """
            {
                "type": "object",
                "properties": {
                    "directory": {
                        "type": "string",
                        "description": "搜索目录路径"
                    },
                    "pattern": {
                        "type": "string",
                        "description": "文件名模式（支持通配符）"
                    },
                    "max_results": {
                        "type": "integer",
                        "description": "最大返回结果数",
                        "default": 100
                    }
                },
                "required": ["directory"]
            }
        """.trimIndent())
    }
))
```

### 5.3 错误处理

```kotlin
override fun onCall(request: AidlAciRequest): AidlAciResponse {
    return try {
        // 业务逻辑
        processRequest(request)
    } catch (e: SecurityException) {
        AidlAciResponse().apply {
            success = false
            errorCode = AidlAciError.PERMISSION_DENIED
            errorMessage = "权限不足: ${e.message}"
        }
    } catch (e: IllegalArgumentException) {
        AidlAciResponse().apply {
            success = false
            errorCode = AidlAciError.INVALID_REQUEST
            errorMessage = "参数错误: ${e.message}"
        }
    } catch (e: Exception) {
        AidlAciResponse().apply {
            success = false
            errorCode = AidlAciError.INTERNAL_ERROR
            errorMessage = "内部错误: ${e.message}"
        }
    }
}
```

### 5.4 异步能力实现

```kotlin
// 对于耗时操作，实现异步版本
capabilities.add(Capability(
    id = "download_file",
    name = "下载文件",
    description = "下载文件到指定路径",
    requireUserConfirm = true,
    metadata = Bundle().apply {
        putBoolean("async", true)
    }
))

override fun onCallAsync(
    request: AidlAciRequest,
    callback: IAidlAciCallback
) {
    // 在后台线程执行
    thread {
        try {
            val result = processDownload(request)
            callback.onResult(AidlAciResponse().apply {
                success = true
                data = result
            })
        } catch (e: Exception) {
            callback.onResult(AidlAciResponse().apply {
                success = false
                errorMessage = e.message
            })
        }
    }
}
```

---

## 6. Token 认证与安全

### 6.1 Token 生成与验证

```kotlin
// 控制端生成 Token
val token = AidlAciTokenGenerator.generate(
    packageName = "com.example.myapp",
    timestamp = System.currentTimeMillis(),
    secret = "your-secret-key"
)

// 受控端验证 Token
override fun onVerifyToken(request: AidlAciRequest): Boolean {
    val token = request.token ?: return false
    
    return AidlAciTokenVerifier.verify(
        token = token,
        expectedPackage = request.sourcePackage,
        maxAgeMs = 5 * 60 * 1000 // 5分钟有效期
    )
}
```

### 6.2 权限控制

```kotlin
// 自定义权限检查
override fun onCheckPermission(request: AidlAciRequest): Boolean {
    val capability = request.capability
    
    // 高危能力需要额外检查
    return when {
        capability in listOf("install_app", "uninstall_app") -> {
            // 检查调用方是否为系统应用
            isSystemApp(request.sourcePackage)
        }
        capability == "root_command" -> {
            // 检查调用方是否有root权限
            checkRootPermission()
        }
        else -> true
    }
}
```

### 6.3 高危能力确认机制

```kotlin
// 定义高危能力
capabilities.add(Capability(
    id = "delete_file",
    name = "删除文件",
    description = "删除指定文件（不可恢复）",
    requireUserConfirm = true,  // 必须用户确认
    metadata = Bundle().apply {
        putString("risk_level", "high")
        putString("confirmation_message", "确定要删除这个文件吗？此操作不可逆。")
    }
))

// 控制端调用时必须传 confirmed=true
val request = AidlAciRequest().apply {
    capability = "delete_file"
    params = Bundle().apply {
        putString("path", "/sdcard/important_file.txt")
        putBoolean("confirmed", true)  // 用户已确认
    }
}
```

---

## 7. 传输层详解

### 7.1 AIDL 本地绑定（默认）

```kotlin
// 控制端绑定服务
val intent = Intent("ai.aci.intent.BIND")
intent.setPackage("com.example.myapp")

bindService(intent, object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        val aciService = IAidlAciService.Stub.asInterface(service)
        
        // 发送请求
        val request = AidlAciRequest().apply {
            capability = "greet"
            params = Bundle().apply {
                putString("name", "测试")
            }
        }
        
        val response = aciService.call(request)
        Log.d("ACI", "响应: ${response.data}")
    }
    
    override fun onServiceDisconnected(name: ComponentName) {
        Log.w("ACI", "服务断开")
    }
}, Context.BIND_AUTO_CREATE)
```

### 7.2 HTTP 传输（局域网）

```kotlin
// 受控端启动 HTTP 服务
val httpServer = AidlAciHttpServer(port = 8080)
httpServer.start()

// 控制端通过 HTTP 调用
val url = "http://192.168.1.100:8080/aci"
val response = httpClient.post(url, request)
```

### 7.3 MCP 桥接

```kotlin
// 通过 MCP 协议调用
val mcpClient = McpAciBridge(mcpServerUrl = "http://localhost:3000")
val result = mcpClient.call("greet", mapOf("name" to "测试"))
```

### 7.4 传输层对比

| 特性 | AIDL | HTTP | MCP |
|------|------|------|-----|
| **延迟** | 极低（μs级） | 低（ms级） | 中（ms级） |
| **安全** | 高（本地绑定） | 中（可加密） | 中（可加密） |
| **跨设备** | ❌ 仅本机 | ✅ 局域网 | ✅ 局域网/公网 |
| **复杂度** | 低 | 中 | 高 |
| **适用场景** | 本机应用交互 | 局域网应用 | AI Agent 集成 |

---

## 8. 控制端集成指南

### 8.1 绑定受控端服务

```kotlin
class MyController {
    
    private lateinit var aciManager: AidlAciManager
    
    fun init(context: Context) {
        aciManager = AidlAciManager.getInstance(context)
    }
    
    fun connectToControlledApp(packageName: String) {
        // 发现服务
        val services = aciManager.discover(packageName)
        
        if (services.isNotEmpty()) {
            // 绑定到第一个服务
            aciManager.bind(services[0])
            
            // 获取能力列表
            val capabilities = aciManager.getCapabilities()
            Log.d("Controller", "发现 ${capabilities.size} 个能力")
        }
    }
    
    fun callCapability(capability: String, params: Bundle) {
        val request = AidlAciRequest().apply {
            this.capability = capability
            this.params = params
        }
        
        // 同步调用
        val response = aciManager.call(request)
        
        if (response.success) {
            Log.d("Controller", "调用成功: ${response.data}")
        } else {
            Log.e("Controller", "调用失败: ${response.errorMessage}")
        }
    }
    
    fun callCapabilityAsync(
        capability: String,
        params: Bundle,
        callback: (AidlAciResponse) -> Unit
    ) {
        val request = AidlAciRequest().apply {
            this.capability = capability
            this.params = params
        }
        
        // 异步调用
        aciManager.callAsync(request, object : IAidlAciCallback.Stub() {
            override fun onResult(response: AidlAciResponse) {
                callback(response)
            }
        })
    }
}
```

### 8.2 发现可用服务

```kotlin
fun discoverAllAciServices(context: Context) {
    val packageManager = context.packageManager
    val intent = Intent("ai.aci.intent.BIND")
    
    val resolveInfos = packageManager.queryIntentServices(intent, 0)
    
    for (info in resolveInfos) {
        val packageName = info.serviceInfo.packageName
        val serviceName = info.serviceInfo.name
        
        Log.d("Discovery", "发现 ACI 服务: $packageName/$serviceName")
        
        // 尝试绑定并获取能力
        try {
            val intent = Intent().apply {
                setClassName(packageName, serviceName)
            }
            
            // 绑定服务...
        } catch (e: Exception) {
            Log.w("Discovery", "绑定失败: ${e.message}")
        }
    }
}
```

### 8.3 AI 工具集成

```kotlin
// 将 ACI 能力暴露为 AI 工具
class AciToolProvider {
    
    fun getTools(): List<AiTool> {
        return listOf(
            AiTool(
                name = "aci_call",
                description = "调用 ACI 受控端的能力",
                parameters = mapOf(
                    "package" to "受控端包名",
                    "capability" to "能力名称",
                    "params" to "参数JSON"
                )
            ),
            AiTool(
                name = "aci_list",
                description = "列出所有可用的 ACI 受控端及其能力",
                parameters = emptyMap()
            )
        )
    }
    
    suspend fun executeAciCall(
        packageName: String,
        capability: String,
        params: Map<String, Any>
    ): AiToolResult {
        return try {
            val request = AidlAciRequest().apply {
                this.capability = capability
                this.params = Bundle().apply {
                    params.forEach { (key, value) ->
                        when (value) {
                            is String -> putString(key, value)
                            is Int -> putInt(key, value)
                            is Double -> putDouble(key, value)
                            is Boolean -> putBoolean(key, value)
                        }
                    }
                }
            }
            
            val response = aciManager.call(request)
            
            AiToolResult(
                success = response.success,
                data = response.data?.let { bundleToMap(it) },
                error = response.errorMessage
            )
        } catch (e: Exception) {
            AiToolResult(success = false, error = e.message)
        }
    }
    
    private fun bundleToMap(bundle: Bundle): Map<String, Any?> {
        return bundle.keySet().associateWith { bundle.get(it) }
    }
}
```

---

## 9. 终端 ACI 集成（12 个能力）

### 9.1 能力清单

| # | 能力 | 说明 | 参数 |
|---|------|------|------|
| 1 | `exec` | 执行命令 | `command`, `session_id`, `timeout` |
| 2 | `create_session` | 创建会话 | `mode` (linux/device) |
| 3 | `list_sessions` | 列出会话 | 无 |
| 4 | `get_session` | 获取会话状态 | `session_id` |
| 5 | `send_input` | 发送输入 | `session_id`, `input` |
| 6 | `interrupt` | 中断会话 | `session_id` |
| 7 | `destroy_session` | 销毁会话 | `session_id` |
| 8 | `get_env` | 获取环境变量 | `name` |
| 9 | `set_env` | 设置环境变量 | `name`, `value` |
| 10 | `list_env` | 列出环境变量 | 无 |
| 11 | `service_status` | 服务状态 | 无 |
| 12 | `audit_log` | 审计日志 | `limit` |

### 9.2 exec 能力详解

```kotlin
// 执行命令的能力实现
private fun handleExec(request: AidlAciRequest): AidlAciResponse {
    val command = request.params?.getString("command") ?: return errorResponse("缺少 command")
    val sessionId = request.params?.getString("session_id")
    val timeout = request.params?.getLong("timeout", 30000) ?: 30000L
    
    return try {
        val result = terminalManager.exec(command, sessionId, timeout)
        
        AidlAciResponse().apply {
            success = true
            data = Bundle().apply {
                putString("output", result.output)
                putInt("exit_code", result.exitCode)
                putLong("duration_ms", result.durationMs)
                putBoolean("timed_out", result.timedOut)
            }
        }
    } catch (e: Exception) {
        errorResponse("执行失败: ${e.message}")
    }
}
```

---

## 10. 原生 ACI 桥接（C/C++）

### 10.1 libacihost.so

原生库提供 C API，允许 C/C++ 代码直接调用 ACI 能力：

```c
// aci_native.h
#ifndef ACI_NATIVE_H
#define ACI_NATIVE_H

#ifdef __cplusplus
extern "C" {
#endif

// 检查 ACI 是否可用
int aci_available();

// 列出所有受控端及其能力
int aci_list(char* out_buffer, int out_size);

// 调用能力
int aci_call(
    const char* package,
    const char* capability,
    const char* args_json,
    int confirmed,
    char* out_buffer,
    int out_size
);

#ifdef __cplusplus
}
#endif

#endif // ACI_NATIVE_H
```

### 10.2 使用示例

```c
#include "aci_native.h"
#include <stdio.h>

int main() {
    // 检查 ACI 是否可用
    if (!aci_available()) {
        printf("ACI 不可用\n");
        return 1;
    }
    
    // 列出所有能力
    char list_buf[4096];
    aci_list(list_buf, sizeof(list_buf));
    printf("可用能力:\n%s\n", list_buf);
    
    // 调用能力
    char call_buf[4096];
    int result = aci_call(
        "com.example.myapp",
        "greet",
        "{\"name\": \"Native测试\"}",
        1,  // confirmed
        call_buf,
        sizeof(call_buf)
    );
    
    if (result == 0) {
        printf("调用成功:\n%s\n", call_buf);
    } else {
        printf("调用失败: %d\n", result);
    }
    
    return 0;
}
```

---

## 11. MCP-ACI 桥接

### 11.1 桥接原理

MCP-ACI 桥接将 ACI 能力转换为 MCP 工具，让 AI Agent 可以通过 MCP 协议调用 ACI 能力。

```
AI Agent → MCP 协议 → MCP Server → ACI Bridge → ACI 控制端 → ACI 受控端
```

### 11.2 能力映射

```kotlin
// MCP 工具定义
val mcpTools = listOf(
    McpTool(
        name = "mcp_aci_call",
        description = "通过 ACI 调用受控端能力",
        inputSchema = mapOf(
            "package" to "受控端包名",
            "capability" to "能力名称",
            "args" to "参数JSON"
        )
    ),
    McpTool(
        name = "mcp_aci_list",
        description = "列出所有可用的 ACI 受控端",
        inputSchema = emptyMap()
    )
)
```

### 11.3 使用示例

```python
# Python MCP 客户端示例
import mcp

async def call_aci_capability(package: str, capability: str, args: dict):
    async with mcp.Client("http://localhost:3000") as client:
        result = await client.call_tool(
            "mcp_aci_call",
            {
                "package": package,
                "capability": capability,
                "args": json.dumps(args)
            }
        )
        return result

# 使用示例
result = await call_aci_capability(
    "com.example.myapp",
    "greet",
    {"name": "MCP测试"}
)
print(result)
```

---

## 12. 跨应用接入示例

### 12.1 作为控制端（调用其他 ACI 服务）

```kotlin
class MyControlledApp {
    
    fun callExternalAciService() {
        // 1. 发现服务
        val intent = Intent("ai.aci.intent.BIND")
        intent.setPackage("com.zorvai.app")
        
        // 2. 绑定服务
        bindService(intent, object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val aciService = IAidlAciService.Stub.asInterface(service)
                
                // 3. 获取能力列表
                val capabilities = aciService.capabilities
                
                // 4. 调用能力
                val request = AidlAciRequest().apply {
                    token = generateToken()
                    capability = "terminal_exec"
                    params = Bundle().apply {
                        putString("command", "ls -la")
                    }
                }
                
                val response = aciService.call(request)
                Log.d("External", "结果: ${response.data}")
            }
            
            override fun onServiceDisconnected(name: ComponentName) {}
        }, Context.BIND_AUTO_CREATE)
    }
}
```

### 12.2 作为受控端（暴露能力给其他应用）

```kotlin
class MyExposedService : BaseAidlAciService() {
    
    override fun onCreateCapabilities(capabilities: MutableList<Capability>) {
        // 暴露文件操作能力
        capabilities.add(Capability(
            id = "share_file",
            name = "分享文件",
            description = "让其他应用访问我的文件",
            requireUserConfirm = true
        ))
        
        // 暴露数据查询能力
        capabilities.add(Capability(
            id = "query_data",
            name = "查询数据",
            description = "查询我的数据库",
            requireUserConfirm = false
        ))
    }
    
    override fun onCall(request: AidlAciRequest): AidlAciResponse {
        // 实现具体逻辑...
        return AidlAciResponse()
    }
}
```

---

## 13. 调试与故障排除

### 13.1 常见问题

#### 问题 1：服务无法绑定

```kotlin
// 检查清单
val checks = listOf(
    "AndroidManifest.xml 中是否声明了 <service>",
    "intent-filter 是否包含 'ai.aci.intent.BIND'",
    "android:permission 是否正确",
    "受控端应用是否已安装并运行"
)

// 调试代码
val intent = Intent("ai.aci.intent.BIND")
intent.setPackage("com.example.myapp")

val resolveInfos = packageManager.queryIntentServices(intent, 0)
if (resolveInfos.isEmpty()) {
    Log.e("ACI", "未找到 ACI 服务，请检查 AndroidManifest.xml")
}
```

#### 问题 2：Token 验证失败

```kotlin
// 检查 Token 生成和验证逻辑
val token = AidlAciTokenGenerator.generate(
    packageName = "com.example.myapp",
    timestamp = System.currentTimeMillis()
)

// 验证 Token
val isValid = AidlAciTokenVerifier.verify(
    token = token,
    expectedPackage = "com.example.myapp",
    maxAgeMs = 5 * 60 * 1000
)

Log.d("Token", "Token 验证结果: $isValid")
```

#### 问题 3：权限不足

```kotlin
// 检查权限声明
val permissions = listOf(
    "ai.aci.permission.CALL",
    "ai.aci.permission.DISCOVER",
    "ai.aci.permission.CALL_DANGEROUS"
)

for (permission in permissions) {
    val granted = context.checkSelfPermission(permission)
    Log.d("Permission", "$permission: ${if (granted == GRANTED) "已授权" else "未授权"}")
}
```

### 13.2 日志分析

```kotlin
// 启用详细日志
AidlAciConfig.setLogLevel(AidlAciConfig.LOG_LEVEL_DEBUG)

// 查看 ACI 日志
Runtime.getRuntime().exec("logcat -s AidlAciManager:* BaseAidlAciService:*")

// 导出审计日志
val auditLog = aciManager.getAuditLog(100) // 最近100条
for (entry in auditLog) {
    Log.d("Audit", "${entry.timestamp} - ${entry.capability} - ${entry.success}")
}
```

### 13.3 性能监控

```kotlin
// 监控 ACI 调用性能
class AciPerformanceMonitor {
    
    private val metrics = mutableMapOf<String, MutableList<Long>>()
    
    fun recordCall(capability: String, durationMs: Long) {
        metrics.getOrPut(capability) { mutableListOf() }.add(durationMs)
    }
    
    fun getStats(capability: String): AciStats? {
        val durations = metrics[capability] ?: return null
        
        return AciStats(
            count = durations.size,
            avgMs = durations.average(),
            minMs = durations.min(),
            maxMs = durations.max(),
            p95Ms = durations.sorted()[(durations.size * 0.95).toInt()]
        )
    }
}
```

---

## 14. 最佳实践

### 14.1 安全性

1. **永远不要在受控端定义权限**：权限定义权归控制端
2. **高危能力必须设置 `requireUserConfirm = true`**
3. **验证所有输入参数**：防止注入攻击
4. **定期轮换 Token**：避免长期有效 Token
5. **记录所有审计日志**：便于安全审计

### 14.2 性能优化

1. **使用异步调用**：避免阻塞主线程
2. **缓存能力列表**：避免频繁查询
3. **限制响应大小**：避免传输大量数据
4. **使用连接池**：复用 AIDL 连接

### 14.3 错误处理

1. **返回详细的错误信息**：便于调试
2. **使用标准错误码**：保持一致性
3. **实现重试机制**：处理临时故障
4. **提供降级方案**：服务不可用时的备选

### 14.4 文档化

1. **为每个能力编写详细描述**
2. **提供使用示例**
3. **说明参数要求和返回值**
4. **记录已知限制和注意事项**

---

## 📚 相关资源

- **ZorvAI GitHub**: https://github.com/Quor-a/ZorvAI
- **ACI 开发者手册**: [ACI_DEVELOPER_GUIDE.md](./ACI_DEVELOPER_GUIDE.md)
- **ACI 技术架构**: [ACI_TECHNICAL_ARCHITECTURE.md](./ACI_TECHNICAL_ARCHITECTURE.md)
- **终端技术架构**: [TERMINAL_ARCHITECTURE.md](./TERMINAL_ARCHITECTURE.md)

---

## 📞 获取帮助

1. **查看日志**：`logcat | grep -i aci`
2. **检查权限**：使用 `adb shell dumpsys package`
3. **测试连接**：使用 ZorvAI 的 ACI 管理中心
4. **提交 Issue**：GitHub Issues

---

*最后更新：2026年8月30日*