# ZorvAI APK 插件开发手册

> 版本：v1.0.90 ｜ 适用宿主：Zorv AI（`com.ai.assistance.quro`）≥ 1.0.89
> 分支：`apk-plugin-arch`

---

## 目录

1. [这是什么](#1-这是什么)
2. [五分钟跑通第一个插件](#2-五分钟跑通第一个插件)
3. [架构总览](#3-架构总览)
4. [契约层 API 参考](#4-契约层-api-参考)
5. [14 种扩展点](#5-14-种扩展点)
6. [DSL 完整参考](#6-dsl-完整参考)
7. [工程配置（三个必守规则）](#7-工程配置三个必守规则)
8. [打包与安装](#8-打包与安装)
9. [让 AI 用上你的插件](#9-让-ai-用上你的插件)
10. [插件界面（uiSurface）](#10-插件界面uisurface)
11. [ACI 能力与插件互调](#11-aci-能力与插件互调)
12. [存储、宿主能力与日志](#12-存储宿主能力与日志)
13. [调试与排错](#13-调试与排错)
14. [版本管理与热重载](#14-版本管理与热重载)
15. [安全边界](#15-安全边界)
16. [完整示例：快递查询插件](#16-完整示例快递查询插件)
17. [附录：速查表](#17-附录速查表)

---

## 1. 这是什么

Zorv AI 的 **APK 级插件框架**：插件是一个**独立 APK**，装进宿主后向宿主注册「**扩展点**」，
宿主在对应场景自动取用插件提供的实现。

一句话理解：

> 宿主不认识任何具体插件，只认识「扩展点」这一个抽象。插件往槽里放实现，宿主负责调度。

**宿主不需要为任何插件改一行代码。** 装完插件，AI 的工具集里立刻多出新工具。

### 能加什么能力

| 你想做的 | 用哪个扩展点 |
|---|---|
| 给 AI 加一个可调用的工具 | `AI_TOOL` ★ 最常用 |
| 把能力暴露给别的 App / 别的 Agent | `ACI_CAPABILITY` |
| 做一个插件自己的完整界面 | `UI_SURFACE` |
| 在聊天气泡里渲染自定义卡片 | `CHAT_CARD` |
| 在气泡里放可交互控件 | `UI_WIDGET` |
| 加一条 `/斜杠指令` | `COMMAND` |
| 接一个新的模型服务商 | `MODEL_PROVIDER` |
| 接一个新的知识库来源 | `RAG_SOURCE` |
| 加一个定时任务类型 | `SCHEDULE_TASK` |
| 加一个消息渠道（飞书/QQ/微信之外） | `CHANNEL` |
| 加一种文件类型的打开方式 | `FILE_HANDLER` |
| 加一个脚本语言运行时 | `CODE_RUNTIME` |
| 加一个 TTS / STT 引擎 | `SPEECH` |
| 在设置页加配置项 | `SETTING` |

---

## 2. 五分钟跑通第一个插件

### 2.1 复制模板

```
plugin-express/            ← 抄这个模块，改名字
├── build.gradle.kts
├── src/main/AndroidManifest.xml
└── src/main/java/com/zorv/plugin/express/ExpressEntry.kt
```

在 `settings.gradle.kts` 里注册你的模块：

```kotlin
include(":plugin-myplugin")
```

### 2.2 写入口类

```kotlin
package com.example.plugin.hello

import com.ai.assistance.quro.plugin.contract.*
import com.ai.assistance.quro.plugin.dsl.plugin

class HelloEntry : PluginEntry {
    override fun onCreate(ctx: PluginContext) {
        plugin(ctx) {
            aiTool(
                name = "hello_greet",
                description = "向某人打招呼。当用户说「跟某人打招呼」「问好」时调用。"
            ) {
                param("who", ParamType.STRING, "要打招呼的对象名字")
                execute { args ->
                    ToolResult.text("你好，${args.string("who")}！我是来自插件的问候。")
                }
            }
        }
    }

    override fun onDestroy(ctx: PluginContext) {
        ctx.unregisterAll()
    }
}
```

### 2.3 声明入口类（**必须**）

`src/main/AndroidManifest.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="@string/plugin_name">
        <!-- ★ 宿主靠这条 meta-data 反射实例化插件入口类，缺了安装直接失败 -->
        <meta-data
            android:name="quro.plugin.entry"
            android:value="com.example.plugin.hello.HelloEntry" />
    </application>
</manifest>
```

### 2.4 构建

```bash
./gradlew :plugin-myplugin:assembleRelease
# 产物：plugin-myplugin/build/outputs/apk/release/plugin-myplugin-release.apk
```

### 2.5 安装并验证

三种方式任选：

1. **插件桌面** → 底部 Dock「导入 APK」→ 选你的 APK
2. **让 AI 装**：`apk_plugin(action="install", path="/storage/emulated/0/Download/xxx.apk")`
3. **随宿主内置**：把 APK 放进宿主 `app/src/main/assets/plugins/`，然后「一键安装内置插件」

安装成功后：

- 插件桌面会出现你的插件图标（图标就是插件 APK 的 launcher 图标）
- 让 AI 调 `apk_plugin(action="tools")`，能列出 `hello_greet`
- 在对话框说「跟小明打个招呼」，AI 应该会调用它

---

## 3. 架构总览

```
┌──────────────────────────── 宿主 App（:app） ────────────────────────────┐
│                                                                          │
│  QuroApplication.onCreate()                                              │
│        └── QuroPluginHost.attach(this)        ← 宿主唯一一次性接线点      │
│                 ├── QuroPluginEngine.init(app, HOST_CAPS)                │
│                 ├── AciBridge 双向注入                                   │
│                 └── QuroPluginEngine.loadAllInstalled()                  │
│                                                                          │
│  AI 工具链                                                                │
│    QuroToolRegistry.coreSpecs()                                          │
│        └── pluginHostToolSpecs()                                         │
│              ├── ApkPluginTool          → 单一工具 apk_plugin             │
│              └── QuroPluginHost.toolSpecs()  → 插件贡献的 AI 工具          │
│                                                                          │
│  QuroToolEngine.execute()                                                │
│        ├── 命中插件工具 → QuroPluginHost.executePluginTool()（suspend）    │
│        └── 否则走内置注册表 droid-mcp 派发                                │
│                                                                          │
│  界面                                                                     │
│    PluginManagerScreen   ← 启动器式插件桌面（网格/搜索/长按菜单/Dock）      │
│    PluginSurfaceActivity ← 通用界面承载 Activity（显示插件返回的 View）     │
└──────────────────────────────────────────────────────────────────────────┘
                                   ▲
                    HostToolBridge / AciBridge（宿主取用桥）
                                   │
┌─────────────────── :plugin-engine（引擎层）───────────────────────────────┐
│  QuroPluginEngine    加载 / 卸载 / 热重载（DexClassLoader）                │
│  PluginInstaller     清单解析 · 同签名校验 · 原子替换 · .so 提取            │
│  ExtensionRegistry   14 个扩展点「收纳槽」                                 │
│  PluginContextImpl   插件运行时上下文（存储 / 日志 / 互调）                  │
└──────────────────────────────────────────────────────────────────────────┘
                                   ▲
┌────────────────── :plugin-contract（契约层｜compileOnly）─────────────────┐
│  PluginEntry  PluginContext  ExtensionType  PluginDsl  ToolSpec...        │
└──────────────────────────────────────────────────────────────────────────┘
                                   ▲
┌──────────────────────── 你的插件 APK（独立进程外 APK）─────────────────────┐
│  YourEntry : PluginEntry  →  onCreate 里 plugin(ctx) { ... }              │
└──────────────────────────────────────────────────────────────────────────┘
```

### 加载流程（宿主观）

```
install(apk)
  ├─ 1. PackageManager 读清单 → 校验 meta-data quro.plugin.entry，缺失即拒
  ├─ 2. 校验 APK 签名 SHA-256 == 宿主签名，不一致即拒
  ├─ 3. 取 pluginId / versionCode / versionName / label（桌面显示名）
  ├─ 4. 原子替换写入 /data/data/<host>/files/plugins/<pluginId>/active/base.apk
  ├─ 5. 从 APK 提取 lib/<abi>/*.so → active/lib/
  ├─ 6. 写 SharedPreferences 记录（quro_plugins/record_<pluginId>）
  └─ 7. load(pluginId)
        ├─ 新建 PluginClassLoader(base.apk, files/plugins/.odex/<pluginId>, nativeLibDir, 宿主 ClassLoader)
        ├─ Android.Resources 挂「宿主资源路径 + 插件资源路径」（插件能用宿主主题）
        ├─ 反射 newInstance() → YourEntry
        └─ YourEntry.onCreate(ctx)  ← 你在这里注册扩展点
```

> **插件跑在宿主进程内**，通过 `DexClassLoader` 加载（不是系统安装的应用，所以插件**不能有自己的 Activity**，
> 要界面请用 `UI_SURFACE`；`PackageManager` 也不会把插件当应用列出）。

---

## 4. 契约层 API 参考

包名：`com.ai.assistance.quro.plugin.contract`（模块 `:plugin-contract`）

### 4.1 `PluginEntry`

```kotlin
interface PluginEntry {
    fun onCreate(ctx: PluginContext)      // 注册扩展点，必须
    fun onDestroy(ctx: PluginContext) {}  // 默认空实现；务必 unregisterAll
}
```

### 4.2 `PluginContext`

| 成员 | 说明 |
|---|---|
| `val pluginId: String` | 插件包名 |
| `val pluginVersion: String` | 宿主记录里的 versionName |
| `fun register(extension: PluginExtension)` | 注册一个扩展点 |
| `fun unregisterAll()` | 注销本插件的全部扩展点 |
| `fun hasHostCapability(name: String): Boolean` | 查询宿主能力（**可选依赖**判断） |
| `fun log(tag: String, message: String)` | 写 logcat，TAG 为 `QuroPlugin/[<pluginId>]` |
| `fun getString(key, default = ""): String` | 读字符串配置 |
| `fun putString(key, value)` | 写字符串配置 |
| `fun getBool(key, default = false): Boolean` | 读布尔配置 |
| `fun putBool(key, value)` | 写布尔配置 |
| `fun getFilesDir(): File` | 插件私有目录 `<host>/files/plugins/<pluginId>/` |
| `val appContext: Context` | **宿主 Application Context**（能拿系统服务） |
| `suspend fun callCapability(capabilityId, args): ToolResult` | 调用其他插件 / 外部 ACI 能力 |

**宿主能力清单**（`hasHostCapability` 可查，宿主声明于 `QuroPluginHost.HOST_CAPS`）：

`llm` · `memory` · `tts` · `stt` · `aci` · `terminal` · `file` · `web` · `screen` · `shell`

```kotlin
if (ctx.hasHostCapability("llm")) {
    // 有 LLM，做智能摘要；没有就降级成简单拼接
}
```

### 4.3 `ToolSpec` / `ToolParamSpec` / `ParamType`

```kotlin
data class ToolSpec(
    val name: String,                    // 全局唯一，建议加插件前缀，如 express_query
    val description: String,             // ★ 给 LLM 看的，写得越清楚它越会用
    val parameters: List<ToolParamSpec>,
    val requiresConfirm: Boolean,        // 危险操作（删文件/发短信）置 true
    val capabilities: Set<String>        // "network" / "location" / "sms" ...
)

data class ToolParamSpec(
    val name: String, val type: ParamType, val description: String,
    val required: Boolean = true,
    val enumValues: List<String> = emptyList(),
    val defaultValue: String? = null
)

enum class ParamType(val jsonType: String) {
    STRING("string"), INT("integer"), NUMBER("number"),
    BOOLEAN("boolean"), ARRAY("array"), OBJECT("object")
}
```

### 4.4 `ToolArgs`

```kotlin
class ToolArgs {
    fun string(key: String): String
    fun int(key: String, default: Int = 0): Int
    fun number(key: String, default: Double = 0.0): Double
    fun boolean(key: String, default: Boolean = false): Boolean
    fun has(key: String): Boolean
    fun raw(): Map<String, Any?>
}
```

> 参数都做过宽松解析：LLM 把数字传成字符串也能取到（`int()` / `number()` 会兜底转）。

### 4.5 `ToolResult`

```kotlin
sealed class ToolResult {
    data class Text(val text: String)      // 纯文本结果（AI 直接读）
    data class Json(val json: String)      // 结构化数据（宿主可渲染成卡片）
    data class Error(val message: String)  // 错误
    companion object {
        fun text(t: String): ToolResult
        fun json(j: String): ToolResult
        fun error(m: String): ToolResult
        fun ok(t: String): ToolResult
    }
}
```

### 4.6 `ToolExecutor`

```kotlin
fun interface ToolExecutor {
    suspend fun execute(args: ToolArgs): ToolResult
}
```

> **是 suspend 的**，所以插件里可以直接发网络请求、读数据库，不会阻塞主线程。

---

## 5. 14 种扩展点

`ExtensionType` 枚举定义在 `plugin-contract/.../extension/ExtensionPoints.kt`。

| ExtensionType | 数据类 | 宿主消费方 | 说明 |
|---|---|---|---|
| `AI_TOOL` | `AiToolExtension` | `HostToolBridge.collectToolSpecs()` → `QuroToolRegistry` | LLM 自动发现并调用 ★ |
| `ACI_CAPABILITY` | `AciCapabilityExtension` | `AciBridge` → ACI 服务端清单 | 暴露给其他 App / Agent |
| `UI_SURFACE` | `UiSurfaceExtension` | `PluginSurfaceActivity` | 插件自带的完整界面（View 树） |
| `CHAT_CARD` | `ChatCardExtension` | 聊天渲染层 | 气泡内自定义结构化卡片 |
| `UI_WIDGET` | — | 内联组件渲染 | 气泡内可交互控件 |
| `MODEL_PROVIDER` | `ModelProviderExtension` | 模型配置层 | 新增一种大模型接入 |
| `RAG_SOURCE` | `RagSourceExtension` | 知识库层 | 新增知识来源 |
| `COMMAND` | `CommandExtension` | 输入框 `/xxx` 解析 | 斜杠指令 |
| `SETTING` | `SettingExtension` | 设置页 | 新增配置项 |
| `SCHEDULE_TASK` | `ScheduleTaskExtension` | 定时任务调度 | 新增周期任务执行体 |
| `CHANNEL` | — | 消息渠道层 | 新增 IM 接入 |
| `FILE_HANDLER` | `FileHandlerExtension` | 文件打开分发 | 新增文件类型的打开/预览 |
| `CODE_RUNTIME` | `CodeRuntimeExtension` | 脚本执行层 | 新增脚本语言引擎 |
| `SPEECH` | — | TTS/STT 层 | 新增语音引擎 |

---

## 6. DSL 完整参考

包名：`com.ai.assistance.quro.plugin.dsl`。在 `onCreate` 里用 `plugin(ctx) { ... }` 包住全部注册。

```kotlin
fun plugin(ctx: PluginContext, block: PluginBuilder.() -> Unit)
```

### 6.1 `aiTool` — AI 工具

```kotlin
aiTool(name = "my_tool", description = "描述") {
    param("text", ParamType.STRING, "输入文本")
    param("mode", ParamType.STRING, "模式", required = false, enum = listOf("fast", "deep"))
    param("limit", ParamType.INT, "条数", required = false, default = "10")
    requireConfirm(setOf("sms"))          // 可选：标记为需要确认的危险操作
    execute { args ->
        ToolResult.text("结果：${args.string("text")}")
    }
}
```

### 6.2 `aciCapability` — ACI 能力

```kotlin
aciCapability(id = "query_express", description = "查询快递物流轨迹") {
    param("no", ParamType.STRING, "快递单号")
    execute { args -> ToolResult.text(...) }
}
```

### 6.3 `command` — 斜杠指令

```kotlin
command("express", "/express <单号>  查询物流") { rawArgs ->
    // 返回 true 表示已处理
    true
}
```

### 6.4 `chatCard` — 对话卡片

```kotlin
chatCard("weather_card", "天气卡片") { data, renderCtx ->
    RenderedCard(title = "天气", body = data)
}
```

### 6.5 `uiSurface` — 插件界面

```kotlin
uiSurface("my_panel", "我的面板", "标题") { activityContext, host ->
    LinearLayout(activityContext).apply { /* 构建 View 树 */ }
}
```

### 6.6 其他

```kotlin
setting("api_key", "API Key", SettingKind.TEXT, default = "")
modelProvider("my_llm", "我的模型", listOf("my-1")) { model, messages -> "回复" }
ragSource("my_docs", "我的文档") { query, topK -> listOf("片段1", "片段2") }
scheduleTask("daily_report", "每日报告") { payload -> "已生成" }
fileHandler("md_viewer", "Markdown 预览", "md", "markdown") { path -> ToolResult.text(path) }
codeRuntime("lua", "Lua 引擎", "lua") { code -> "执行结果" }
```

---

## 7. 工程配置（三个必守规则）

### 规则一：契约层必须 `compileOnly`

```kotlin
dependencies {
    // ✅ 正确：编译期可见，运行期由宿主提供
    compileOnly(project(":plugin-contract"))
    compileOnly(libs.coroutines.core)   // 宿主已有 kotlinx.*，不要重复打包

    // ❌ 错误：implementation / api → 插件与宿主各持一份接口 Class
    //          → 调用时立即 ClassCastException（本框架第一大坑）
}
```

### 规则二：签名必须与宿主一致

```kotlin
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(keystorePropertiesFile.inputStream())
}
val keystorePath = (keystoreProperties["storeFile"] as String?).orEmpty().removePrefix("../")

android {
    signingConfigs {
        create("release") {
            if (keystorePath.isNotEmpty()) {
                storeFile = rootProject.file(keystorePath)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                enableV1Signing = true; enableV2Signing = true; enableV3Signing = true
            }
        }
    }
    buildTypes {
        release { if (keystorePath.isNotEmpty()) signingConfig = signingConfigs.getByName("release") }
        debug   { if (keystorePath.isNotEmpty()) signingConfig = signingConfigs.getByName("release") }
    }
}
```

宿主 ZorvAI 的 Release 证书 SHA-256：

```
D9:5B:1B:EC:57:B9:D5:EE:88:96:05:9C:0F:3C:B5:09:E5:E9:CE:7C:CD:AE:DB:9C:6B:2E:98:49:BA:10:C7:99
```

自检：

```bash
keytool -printcert -jarfile your-plugin-release.apk | grep SHA256
```

### 规则三：不要写自己的 Activity

插件不是系统安装的应用，`Activity` / `Service` / `BroadcastReceiver` 都起不来。
要界面 → 注册 `uiSurface`，宿主用通用 `PluginSurfaceActivity` 承载你返回的 View。

### 完整 `build.gradle.kts` 模板

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) keystoreProperties.load(keystorePropertiesFile.inputStream())
val keystorePath = (keystoreProperties["storeFile"] as String?).orEmpty().removePrefix("../")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.plugin.hello"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.plugin.hello"   // 这个就是 pluginId
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs { /* 见规则二 */ }

    buildTypes {
        release { isMinifyEnabled = false; /* signingConfig = ... */ }
        debug   { /* signingConfig = ... */ }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = false }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    compileOnly(project(":plugin-contract"))
    compileOnly(libs.coroutines.core)
}
```

### Manifest 模板

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 插件实际运行在宿主进程，用的是宿主的权限；这里声明便于打包自检 -->
    <uses-permission android:name="android.permission.INTERNET" />

    <application android:label="@string/plugin_name">
        <!-- ★ 必须：宿主靠它反射实例化入口类 -->
        <meta-data
            android:name="quro.plugin.entry"
            android:value="com.example.plugin.hello.HelloEntry" />
    </application>
</manifest>
```

`res/values/strings.xml`：

```xml
<resources>
    <string name="plugin_name">我的插件</string>   <!-- 插件桌面显示的图标名 -->
</resources>
```

---

## 8. 打包与安装

### 8.1 构建

```bash
./gradlew :plugin-hello:assembleRelease
```

### 8.2 安装的三种方式

| 方式 | 操作 | 适用 |
|---|---|---|
| 插件桌面导入 | 插件桌面 → Dock「导入 APK」→ 选文件 | 手动测试 |
| AI 安装 | `apk_plugin(action="install", path="绝对路径")` | 让 AI 自己装 |
| 内置随包 | APK 放进宿主 `app/src/main/assets/plugins/` | 官方示例插件 |

调试时若签名暂时对不上，可用 `apk_plugin(action="install", path=..., skip_signature_check=true)` 跳过校验
（**仅限调试，正式分发绝不能跳过**）。

### 8.3 安装校验链（宿主侧）

| 步骤 | 失败表现 |
|---|---|
| 文件存在且非空 | 「文件不存在或为空」 |
| 是合法 APK | 「不是合法 APK 文件」 |
| 声明 `quro.plugin.entry` | 「未声明 meta-data: quro.plugin.entry」 |
| 签名 SHA-256 与宿主一致 | 「签名与宿主不一致，拒绝安装」 |
| 写入私有目录（原子替换，失败回滚） | 「写入安装目录失败」 |

安装目录（**只存私有目录，绝不放外置存储**）：

```
/data/data/com.ai.assistance.quro/files/plugins/<pluginId>/
├── active/
│   ├── base.apk        ← 插件本体（只读）
│   └── lib/            ← 从 APK 提取的 lib/<abi>/*.so（按设备 ABI 选）
└── .odex/<pluginId>/   ← DexClassLoader 的优化产物
```

### 8.4 原生库（.so）

插件若带 JNI，把 `.so` 放在标准的 `src/main/jniLibs/<abi>/`。
宿主安装时会自动**按设备 ABI 提取**到 `active/lib/`，并作为 `DexClassLoader` 的 `librarySearchPath` 传入。

---

## 9. 让 AI 用上你的插件

### 9.1 自动进工具集

`AI_TOOL` 扩展注册成功后，宿主的 `pluginHostToolSpecs()` 会把它们转成 `QuroToolSpec`，
并入 `QuroToolRegistry.coreSpecs()`——**下一轮 function calling 模型就能看到并调用**。

链路：

```
YourEntry.onCreate → ctx.register(AiToolExtension)
   → ExtensionRegistry（收纳槽）
   → HostToolBridge.collectToolSpecs()
   → QuroPluginHost.toolSpecs()（ToolParamSpec → JSON Schema）
   → pluginHostToolSpecs() → QuroToolRegistry.coreSpecs()
   → LLM tools 字段
执行：QuroToolEngine.execute(call)
   → QuroPluginHost.executePluginTool(name, args)（suspend 桥接）
   → HostToolBridge.executeTool → YourExecutor.execute(ToolArgs)
```

### 9.2 兜底通道：`apk_plugin(action="call")`

工具集被裁剪、或插件刚装完还没轮到下一轮时，AI 仍可这样调用你的工具：

```
apk_plugin(action="call", name="hello_greet", args="{\"who\":\"小明\"}")
```

宿主侧等价于直接调 `QuroPluginHost.executePluginTool`，**不经过工具集**。
所以「插件装了但 AI 用不了」这个死角不存在。

### 9.3 写工具描述的技巧（决定 AI 会不会用）

宿主的系统提示里有一整章讲插件框架，但**真正决定调用率的是你的 `description`**：

| 做法 | 效果 |
|---|---|
| ❌ `"快递查询工具"` | AI 不知道什么时候该用 |
| ✅ `"根据快递单号查询物流轨迹。当用户询问快递到哪了、物流状态、包裹进度时调用。"` | AI 能把口语映射过来 |
| ❌ `param("no", STRING, "单号")` | AI 可能传错格式 |
| ✅ `param("no", STRING, "快递单号，如 SF1234567890")` | 有示例，命中率高 |
| ✅ 用 `enum` 约束取值为有限集合的参数 | 避免 AI 自由发挥传错值 |
| ✅ 工具名加插件前缀（`express_query`） | 避免与其他插件的工具名冲突 |

**工具名冲突规则**：`ExtensionRegistry` 按 `(ExtensionType, id)` 唯一索引，
后注册的同名扩展会**覆盖**前一个。给工具名加插件前缀是本框架的强约定。

---

## 10. 插件界面（uiSurface）

### 10.1 为什么不能写 Activity

插件 APK 不是系统安装的应用（没有 launcher entry、没有真实的 applicationId 安装记录），
`startActivity` 一个插件里的 Activity 会直接失败。所以宿主提供了**通用承载 Activity**：
`com.ai.assistance.quro.ui.PluginSurfaceActivity`。

插件只负责**返回一棵 View 树**，宿主负责把它放进 Activity 显示。

### 10.2 注册

```kotlin
override fun onCreate(ctx: PluginContext) {
    plugin(ctx) {
        uiSurface(
            id = "my_panel",
            label = "我的面板",
            title = "我的插件面板",
            build = { actCtx, host ->
                LinearLayout(actCtx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(actCtx).apply { text = "来自插件" })
                    addView(Button(actCtx).apply {
                        text = "点我"
                        setOnClickListener { host.toast("你好") }
                    })
                }
            },
            onRelease = {
                // ★ 界面关闭时释放资源（detach WebView、注销监听、停计时器…）
            }
        )
    }
}
```

> Kotlin 的块注释可以**嵌套**，所以在 KDoc 里不要出现成对的 `/*` `*/`（曾导致整个契约模块编译失败）。

### 10.3 `SurfaceHost` 回调

```kotlin
interface SurfaceHost {
    val pluginId: String
    fun close()                      // 关闭当前界面
    fun toast(message: String)       // 宿主 Toast
    fun runOnUi(block: () -> Unit)   // 切主线程
}
```

### 10.4 接管系统返回键 / 返回手势

让插件根 View 实现 `SurfaceBackHandler`：

```kotlin
class MyRootView(ctx: Context) : FrameLayout(ctx), SurfaceBackHandler {
    override fun onSurfaceBack(): Boolean {
        // 返回 true = 已消费（不关闭界面，比如先退一层内部页面）
        // 返回 false = 交给宿主关闭界面
        return false
    }
}
```

### 10.5 用 WebView（做完整前端界面）

`build` 回调拿到的是 **Activity Context**，可以直接 `WebView(this).loadUrl(...)`。
配合插件的 `assets/` 目录做一套完整前端（记得在 `onRelease` 里 `destroy()`）。

### 10.6 打开插件界面

| 入口 | 操作 |
|---|---|
| 插件桌面 | 单击图标（有界面就直接打开）/ 长按 →「打开界面」/ 详情面板「打开界面」 |
| AI | `apk_plugin(action="open", surface_id="my_panel")` |
| 先看有哪些 | `apk_plugin(action="surfaces")` |

---

## 11. ACI 能力与插件互调

### 11.1 暴露给外部

```kotlin
aciCapability(
    id = "query_express",
    description = "查询快递物流轨迹，输入快递单号返回最新状态"
) {
    param("no", ParamType.STRING, "快递单号")
    execute { args -> ToolResult.text(ExpressApi.query(args.string("no")).lastOrNull() ?: "无记录") }
}
```

注册后宿主自动把插件能力**并入 ACI 服务端对外清单**（`AciBridge.capabilitySink` → `QuroPluginAciRegistry.publish`），
其他 App / 其他 Agent 就能通过 ACI 调用。

### 11.2 调用别人

```kotlin
// 先找插件能力，再找外部 ACI 受控端能力
val r = ctx.callCapability("query_express", mapOf("no" to "SF1234567890"))
```

### 11.3 反向：外部 ACI 能力 → AI 工具

宿主会定期 `refreshAciMirror()`，把已发现的外部 ACI 能力镜像成 AI 工具，
AI 可以用 `aci_list` / `aci_call` 调用。

---

## 12. 存储、宿主能力与日志

### 12.1 键值存储

用 `PluginContext` 的 `getString/putString/getBool/putBool`——
底层是宿主按插件隔离的 `SharedPreferences`（`quro_plugin_<pluginId>`）。

```kotlin
val key = ctx.getString("api_key")
if (key.isBlank()) ctx.putString("api_key", "default")
```

### 12.2 文件存储

```kotlin
val dir = ctx.getFilesDir()          // <host>/files/plugins/<pluginId>/
File(dir, "cache.json").writeText("{}")
```

### 12.3 访问系统服务

```kotlin
val tm = ctx.appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
```

> ⚠ `appContext` 是**宿主的 Application Context**，插件与宿主同进程、同权限——
> 这正是「插件必须与宿主同签名」这条安全边界存在的原因。

### 12.4 日志

```kotlin
ctx.log("MyTag", "插件启动 v${ctx.pluginVersion}")
// logcat: I/QuroPlugin/[com.example.plugin.hello]: [MyTag] 插件启动 v1.0.0
```

调试时：

```bash
adb logcat -s QuroPlugin QuroPluginEngine PluginInstaller QuroPluginHost
```

---

## 13. 调试与排错

### 13.1 常见错误速查

| 现象 | 根因 | 修复 |
|---|---|---|
| 安装失败：「未声明 meta-data: quro.plugin.entry」 | Manifest 少了 meta-data，或入口类全名写错 | 补 `<meta-data android:name="quro.plugin.entry" .../>` |
| 安装失败：「签名与宿主不一致，拒绝安装」 | 插件用了自己的 debug/release key | 用宿主同一份 keystore（见规则二） |
| 调用插件工具直接 `ClassCastException` | 契约层被打进了插件 APK | `compileOnly(project(":plugin-contract"))`，**不能** `implementation`/`api` |
| `NoSuchMethodError` / `NoClassDefFoundError` on kotlinx | 协程库重复打包，版本不匹配 | `compileOnly(libs.coroutines.core)` |
| 插件加载成功但工具不出现 | 忘了 `execute {}`，或 `aiTool` 没被调用 | `plugin(ctx) { ... }` 里注册；`execute` 缺失会直接抛错 |
| 插件工具名被别的插件覆盖 | 工具名冲突（同名后注册覆盖前一个） | 工具名加插件前缀 |
| 打开界面失败 / 黑屏 | 插件里写了 Activity，或 `build` 返回 null | 用 `uiSurface` 返回 View；不要写 Activity |
| 插件图标不显示，只有首字母色块 | 插件 APK 没配 launcher 图标 | 在 Manifest 里给 `<application android:icon="@mipmap/ic_launcher">` |
| `UnsatisfiedLinkError` | `.so` 没放对 ABI，或宿主没提取到 | 放 `src/main/jniLibs/<abi>/`，确认设备 ABI |
| 界面退出后内存不释放 / 崩溃 | `onRelease` 没做清理 | 在 `onRelease` 里 detach WebView、注销监听 |

### 13.2 自检清单

```
□ apk_plugin(action="status")    → engine_ready=true
□ apk_plugin(action="list")      → 你的 pluginId 在列，loaded=true
□ apk_plugin(action="info")      → extensions 里有 AI_TOOL / UI_SURFACE
□ apk_plugin(action="tools")     → 你的工具名在列
□ apk_plugin(action="call", name="你的工具", args="{...}")  → 有返回
□ 对话框里用自然语言触发一次      → AI 主动调用了你的工具
```

### 13.3 热重载（改完立刻生效）

```bash
./gradlew :plugin-hello:assembleRelease
# 重新安装（会原子替换）
# 然后：
```

```
apk_plugin(action="reload", plugin_id="com.example.plugin.hello")
```

或插件桌面长按图标 →「重载」。**不需要重启宿主 App。**

---

## 14. 版本管理与热重载

### 14.1 版本号

`versionCode` 每次发版 **+1**（宿主用它判断是否更新）；`versionName` 给人看，插件桌面会显示 `v1.0.0 (1)`。

### 14.2 升级安装

直接安装新版本 APK 即可——宿主会**原子替换**：
先写 `.tmp` → 旧版重命名为 `.bak` → `.tmp` 改名为 `active` → 删除 `.bak`。
任一步失败都会回滚，不会出现「装一半坏掉」。

### 14.3 卸载

卸载会：`unload`（调 `onDestroy` + `unregisterAll`）→ 删除 `plugins/<pluginId>/` → 清 SharedPreferences 记录。
**插件贡献的扩展点会一并从收纳槽移除**，AI 的工具集里也不再出现。

---

## 15. 安全边界

| 边界 | 机制 | 为什么必须 |
|---|---|---|
| **同签名** | 宿主比对 APK 证书 SHA-256 | 插件跑在宿主进程内、拥有同等权限，等同于「代码注入」；不同签名 = 不可信代码 |
| **只放私有目录** | `/data/data/<host>/files/plugins/` | 外置存储可被其他 App 篡改 |
| **必须是声明了 entry 的 APK** | 清单解析 | 防止误装普通 APK |
| **原子替换** | tmp → active，失败回滚 | 避免安装中断导致插件损坏 |
| **卸载即注销** | `unregisterAll()` | 不留悬挂扩展点 |

### 分发插件前的自检

- [ ] 用宿主同 keystore 签名，并已 `keytool -printcert` 自检 SHA-256
- [ ] 契约层是 `compileOnly`，**没有**打进插件 APK（`unzip -l` 确认没有 `com/ai/assistance/quro/plugin/contract/`）
- [ ] 没有声明 Activity / Service / Receiver
- [ ] 工具名有插件前缀，不会与其他插件冲突
- [ ] `onDestroy` 里调了 `ctx.unregisterAll()`
- [ ] `uiSurface` 的 `onRelease` 做了资源释放
- [ ] 涉及危险的 AI 工具（删文件、发短信、支付）在 `execute` 里做了二次确认或 `requireConfirm`

---

## 16. 完整示例：快递查询插件

`plugin-express/src/main/java/com/zorv/plugin/express/ExpressEntry.kt`（宿主仓库里可直接编译）：

```kotlin
package com.zorv.plugin.express

import com.ai.assistance.quro.plugin.contract.ParamType
import com.ai.assistance.quro.plugin.contract.PluginContext
import com.ai.assistance.quro.plugin.contract.PluginEntry
import com.ai.assistance.quro.plugin.contract.ToolResult
import com.ai.assistance.quro.plugin.dsl.plugin

class ExpressEntry : PluginEntry {

    override fun onCreate(ctx: PluginContext) {
        ctx.log("ExpressEntry", "插件启动 v${ctx.pluginVersion}")

        plugin(ctx) {

            // 1. AI 工具 —— LLM 自动发现，用户问「我的快递到哪了」就会调用
            aiTool(
                name = "express_query",
                description = "根据快递单号查询物流轨迹。当用户询问快递到哪了、物流状态、包裹进度时调用。"
            ) {
                param("no", ParamType.STRING, "快递单号，如 SF1234567890")
                param(
                    "company", ParamType.STRING, "快递公司编码，可留空自动识别",
                    required = false, enum = listOf("sf", "jd", "zto", "yto")
                )
                execute { args ->
                    val no = args.string("no")
                    if (no.isBlank()) ToolResult.error("缺少快递单号")
                    else {
                        val tracks = ExpressApi.query(no)
                        if (tracks.isEmpty()) ToolResult.text("未查询到单号 $no 的物流信息")
                        else ToolResult.text(tracks.joinToString("\n"))
                    }
                }
            }

            // 2. ACI 能力 —— 融合进 ACI 生态，外部 App 可调
            aciCapability(
                id = "query_express",
                description = "查询快递物流轨迹，输入快递单号返回最新状态"
            ) {
                param("no", ParamType.STRING, "快递单号")
                execute { args ->
                    ToolResult.text(ExpressApi.query(args.string("no")).lastOrNull() ?: "无记录")
                }
            }

            // 3. 斜杠指令 —— 用户可直接输入 /express SF1234567890
            command("express", "/express <单号>  查询物流") { args ->
                val tracks = ExpressApi.query(args.trim())
                ctx.log("command", tracks.lastOrNull() ?: "无记录")
                tracks.isNotEmpty()
            }
        }
    }

    override fun onDestroy(ctx: PluginContext) {
        ctx.unregisterAll()
    }
}

internal object ExpressApi {
    fun query(no: String): List<String> = listOf(
        "【$no】已揽收 - 深圳中转场",
        "【$no】运输中 - 已到达广州",
        "【$no】派送中 - 快递员 138****8888"
    )
}
```

**这个插件加到宿主的三样东西**：1 个 AI 工具（`express_query`）、1 个 ACI 能力（`query_express`）、
1 条斜杠指令（`/express`）——**宿主代码零改动**。

### 换成真实 HTTP 请求

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

internal object ExpressApi {
    suspend fun queryReal(no: String): List<String> = withContext(Dispatchers.IO) {
        val conn = (URL("https://api.example.com/track?no=$no").openConnection() as HttpURLConnection)
            .apply { connectTimeout = 8000; readTimeout = 8000 }
        try {
            conn.inputStream.bufferedReader().readText().lines()
        } finally {
            conn.disconnect()
        }
    }
}
```

> `execute {}` 是 suspend 的，直接 `withContext(Dispatchers.IO)` 即可；
> 记得在插件 Manifest 声明 `INTERNET` 权限（插件用宿主权限，但保持声明便于自检）。

---

## 17. 附录：速查表

### 17.1 单入口工具 `apk_plugin` 全部 action

| action | 必填参数 | 作用 |
|---|---|---|
| `status` | — | 框架状态 |
| `list` | — | 已装插件清单 |
| `info` | `plugin_id` | 插件明细 |
| `tools` | — | 插件贡献的全部 AI 工具 |
| `surfaces` | — | 插件界面清单 |
| `open` | `surface_id` | 打开插件界面 |
| `install` | `path`（可选 `skip_signature_check`） | 从 APK 安装 |
| `install_builtin` | — | 装宿主内置示例插件 |
| `uninstall` | `plugin_id` | 卸载 |
| `reload` | `plugin_id`（可选，不传=全部） | 热重载 |
| `call` | `name`（可选 `args`） | ★ 直接调用插件 AI 工具 |

### 17.2 类与文件速查

| 用途 | 全名 |
|---|---|
| 插件入口 | `PluginEntry` |
| 插件上下文 | `PluginContext` |
| 声明式 DSL | `com.ai.assistance.quro.plugin.dsl.plugin` |
| 扩展点枚举 | `ExtensionType` |
| 扩展数据类 | `AiToolExtension` / `AciCapabilityExtension` / `UiSurfaceExtension` / … |
| 工具规格 | `ToolSpec` / `ToolParamSpec` / `ParamType` |
| 工具参数 | `ToolArgs` |
| 工具结果 | `ToolResult` |
| 界面承载 Activity | `com.ai.assistance.quro.ui.PluginSurfaceActivity` |
| 插件桌面 | `com.ai.assistance.quro.ui.PluginManagerScreen` |
| 宿主接线 | `com.ai.assistance.quro.core.plugin.QuroPluginHost` |
| AI 入口工具 | `com.ai.assistance.quro.core.tools.ApkPluginTool` |
| 引擎 | `com.ai.assistance.quro.plugin.engine.core.QuroPluginEngine` |
| 安装器 | `com.ai.assistance.quro.plugin.engine.install.PluginInstaller` |
| 收纳槽 | `com.ai.assistance.quro.plugin.engine.registry.ExtensionRegistry` |
| 宿主取用桥 | `com.ai.assistance.quro.plugin.engine.bridge.HostToolBridge` / `AciBridge` |

### 17.3 模块速查

| 模块 | 角色 |
|---|---|
| `:plugin-contract` | 契约层（插件 `compileOnly` 依赖） |
| `:plugin-engine` | 引擎层（宿主依赖） |
| `:plugin-devkit` `:plugin-express` `:plugin-todo` `:plugin-units` `:plugin-sysinfo` `:plugin-zorvweb` | 6 个示例插件 |

### 17.4 网络参考资料

- 仓库 README →「APK 级插件框架 · APK Plugin Framework」
- `docs/PLUGIN_DEV_GUIDE.md`（早期增量笔记，可对照阅读）
- `plugin-contract/.../extension/ExtensionPoints.kt`（扩展点权威定义）
- `plugin-contract/.../dsl/PluginDsl.kt`（DSL 权威定义）

---

## FAQ

**Q：插件能不能有自己的后台服务？**
A：不能直接写 `Service`。替代方案：注册 `SCHEDULE_TASK`（宿主定时调度）或 `COMMAND` + 宿主的定时任务工具。

**Q：插件能不能读写宿主的数据？**
A：能。插件与宿主同进程、同权限，`ctx.appContext` 就是宿主 Application Context。这也是必须同签名的原因。

**Q：两个插件能不能互相调用？**
A：能。用 `ctx.callCapability(id, args)`，先查插件能力、再查外部 ACI 能力。

**Q：插件能不能改宿主的界面？**
A：能，但只能通过扩展点（`UI_SURFACE` / `CHAT_CARD` / `UI_WIDGET` / `SETTING`），不能直接改宿主源码或布局。

**Q：插件会不会拖慢宿主启动？**
A：`onCreate` 在 `Application.onCreate` 的 `loadAllInstalled()` 里执行，**保持轻量**——
把耗时初始化放到首次 `execute` 时惰性执行，或放到自己的后台线程。

**Q：热重载会不会丢状态？**
A：会。`reload` = `unload` + `load`，插件实例重建。需要持久化的东西放 `ctx.putString` / `ctx.getFilesDir()`。

---

*本手册随 `apk-plugin-arch` 分支维护。*
