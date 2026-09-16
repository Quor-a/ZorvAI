# ZorvAI APK 级插件开发指南

插件是**独立 APK**，宿主用 `DexClassLoader` 加载，插件通过「扩展点」往宿主里加功能。
宿主事先不认识这些能力，也能调度它们 —— **加插件不需要改宿主一行代码**（Tool-first 设计）。

---

## 一、30 秒理解框架

```
插件 APK ──(反射实例化)──> PluginEntry.onCreate(ctx)
                                │
                                └─ plugin(ctx) { aiTool(...) / aciCapability(...) / command(...) ... }
                                        │
                                        ▼
                              ExtensionRegistry（宿主全局收纳槽，按类型分）
                                        │
              ┌─────────────────────────┼──────────────────────────┐
              ▼                         ▼                          ▼
     AI_TOOL → LLM 工具集        ACI_CAPABILITY → ACI 对外清单    COMMAND/SETTING/...
     （function calling 可见）   （其他 App / Agent 可调）        （对应宿主场景取用）
```

核心只有一个接口：插件把实现放进收纳槽，宿主在对的场景取出来用。

---

## 二、目录与模块

| 路径 | 作用 |
|---|---|
| `plugin-contract/` | 契约层：`PluginEntry` / `PluginContext` / `ToolSpec` / `ExtensionPoints` / `PluginDsl`。**插件只依赖这个** |
| `plugin-engine/` | 引擎层：`QuroPluginEngine`（加载）/ `PluginInstaller`（安装）/ `ExtensionRegistry`（收纳槽）/ `AciBridge`、`HostToolBridge`（双向桥） |
| `plugin-express/` | 示例插件（快递查询），可直接构建出可安装的插件 APK |
| `ai-template/` | 源码与清单模板（`build_plugin.py` 用它生成骨架） |
| `tools/build_plugin.py` | 一条命令：生成模块 → 写 settings → 构建 → 产出**已签名** APK |
| 宿主接入点 | `core/plugin/QuroPluginHost.kt`（唯一），`Application.onCreate` 里 `attach()` |

---

## 三、写一个插件（最小可用）

### 1. 一条命令生成骨架

```bash
python tools/build_plugin.py \
  --name "快递查询" \
  --package com.zorv.plugin.express \
  --tool express_query \
  --desc "根据快递单号查询物流轨迹。当用户询问快递到哪了、物流状态时调用。"
```

脚本会：在仓库根生成 `plugin-express/` 模块 → 自动写入 `settings.gradle.kts` →
复用根目录 `keystore.properties` 的签名 → 构建出 `plugin-express-release.apk`。

### 2. 手写入口（核心就这一处）

```kotlin
class ExpressEntry : PluginEntry {
    override fun onCreate(ctx: PluginContext) {
        plugin(ctx) {
            aiTool(
                name = "express_query",
                // ★ 描述写清「什么时候用」——LLM 才会在对的时机调用它；不要写成版本号
                description = "根据快递单号查询物流轨迹。用户问快递到哪了时调用。"
            ) {
                param("no", ParamType.STRING, "快递单号")
                param("company", ParamType.STRING, "公司编码", required = false,
                      enum = listOf("sf", "jd", "zto"))
                execute { args ->
                    if (args.string("no").isBlank()) return@execute ToolResult.error("缺少单号")
                    ToolResult.text(ExpressApi.query(args.string("no")).joinToString("\n"))
                }
            }
        }
    }
    override fun onDestroy(ctx: PluginContext) { ctx.unregisterAll() }
}
```

### 3. 清单必须声明入口类

```xml
<meta-data android:name="quro.plugin.entry"
           android:value="com.zorv.plugin.express.ExpressEntry" />
```

### 4. 依赖必须用 `compileOnly`

```kotlin
dependencies {
    compileOnly(project(":plugin-contract"))  // ★ 绝不能 implementation，否则 ClassCastException
    compileOnly(libs.coroutines.core)         // 宿主已有 kotlinx.*，不必重复打包
}
```

---

## 四、扩展点清单

| 类型 | DSL | 宿主行为 |
|---|---|---|
| **AI 工具** ★ | `aiTool(name, desc) { param(...); execute { } }` | 自动进 LLM 工具集，模型直接调用 |
| **ACI 能力** | `aciCapability(id, desc) { ... }` | 并入宿主 ACI 对外能力清单，其他 App/Agent 可调 |
| **界面表面** ★ | `uiSurface(id, label, title) { actCtx, host -> View }` | 宿主用 `PluginSurfaceActivity` 全屏承载插件返回的 View 树 |
| 斜杠指令 | `command(name, usage) { args -> Bool }` | 用户输入 `/name args` 触发 |
| 对话卡片 | `chatCard(id, label) { data, ctx -> RenderedCard }` | 聊天气泡渲染自定义卡片 |
| 设置项 | `setting(id, label, kind, default, options)` | 设置页出现该配置项 |
| 模型接入 | `modelProvider(id, label, models) { model, msgs -> String }` | 新增一种大模型接入方式 |
| RAG 数据源 | `ragSource(id, label) { query, topK -> List<String> }` | 新知识源参与检索 |
| 定时任务 | `scheduleTask(id, label) { payload -> String }` | 周期任务执行体 |
| 文件处理器 | `fileHandler(id, label, "ext") { path -> ToolResult }` | 接管某扩展名的打开方式 |
| 代码运行时 | `codeRuntime(id, label, lang) { code -> String }` | 新增一种脚本语言引擎 |

### 四之二、界面表面（`uiSurface`）—— 插件怎么拥有自己的界面

插件是独立 APK，**不是系统安装的应用**，系统 PackageManager 里查不到它，
所以插件自己的 `Activity` 起不来。解法与 KaleidoBox 的 `KaleidoActivity` 同一套：
**宿主提供舞台，插件只出布景**。

```kotlin
uiSurface(
    id = "my_panel",
    label = "我的面板",
    title = "面板标题",
    build = { actCtx, host ->        // ★ 主线程调用，拿到的是 Activity Context（能弹 Dialog / 用 WebView）
        LinearLayout(actCtx).apply { /* 构建 View 树 */ }
    },
    onRelease = { /* 界面关闭：解绑资源、摘掉 WebView */ },
)
```

- **打开方式**：工具中心 →「插件」→ 该插件的「打开界面」按钮；或 AI 调 `plugin_surface_open { surface_id }`（不传 id 则列出全部可用界面）。
- **返回键**：插件的根 View 可实现 `SurfaceBackHandler`，`onSurfaceBack()` 返回 `true` 表示已消费（如浏览器返回上一页），`false` 交给宿主关闭界面。
- **`SurfaceHost`（宿主回调）**：`pluginId` / `close()` / `toast(msg)` / `runOnUi {}`。
- **生命周期**：`build` 在主线程调，`onRelease` 在 Activity `onDestroy` 时调一次；
  插件应长期持有 View 实例（不要每次重建），`onRelease` 里只做解绑（例如把 WebView 从窗口摘下），
  不要把引擎状态一起销毁 —— 这样界面关了，AI 工具仍然能用同一个引擎。
- **别忘 `configChanges`**：宿主 `PluginSurfaceActivity` 已声明 `orientation|screenSize|...`，
  避免转屏重建 Activity 导致 WebView 被反复挂载。

---

## 五、宿主提供的上下文能力

```kotlin
ctx.pluginId / ctx.pluginVersion            // 插件身份
ctx.hasHostCapability("llm" | "tts" | "memory" | "aci" | "terminal" | "file" | "web" | "screen" | "shell")
ctx.getString/putString/getBool/putBool     // 插件私有存储（按包名隔离的 SharedPreferences）
ctx.getFilesDir()                           // 插件私有目录 files/plugins/<pluginId>/
ctx.appContext                              // ★ 宿主 Application Context（电池/存储/网络/传感器等系统服务）
ctx.callCapability(id, args)                // 调其他插件或外部 ACI 能力
ctx.log(tag, msg)
```

---

## 五之二、内置示例插件（宿主 assets/plugins/，界面一键装）

宿主随包内置 6 个示例插件，工具中心 →「插件」→「一键安装内置示例插件」，用来验证整条链路。
它们同时也是写插件的参考实现：

| 插件 | 包名 | 注册的扩展点 | 演示什么 |
|---|---|---|---|
| 快递查询 | `com.zorv.plugin.express` | AI_TOOL ×1、ACI_CAPABILITY ×1 | 最小可用插件；同一能力同时给 AI 用 + 对外 ACI 暴露 |
| 开发工具箱 | `com.zorv.plugin.devkit` | AI_TOOL ×6、ACI_CAPABILITY ×1 | **一个插件注册一整套工具**：`dev_json` / `dev_base64` / `dev_hash` / `dev_url` / `dev_regex` / `dev_uuid`；枚举参数与可选参数 |
| 单位换算 | `com.zorv.plugin.units` | AI_TOOL ×1 | 数值参数 + 8 类枚举（长度/重量/温度/面积/体积/速度/数据/时间），支持中文单位别名 |
| 待办清单 | `com.zorv.plugin.todo` | AI_TOOL ×5 | **插件私有存储持久化**（`ctx.getString/putString`）：`todo_add` / `todo_list` / `todo_done` / `todo_remove` / `todo_clear` |
| 设备体检 | `com.zorv.plugin.sysinfo` | AI_TOOL ×4 | **访问 Android 系统服务**（`ctx.appContext`）：电池/内存/存储/CPU/屏幕，无需任何权限 |
| **ZorvWeb 网页引擎** | `com.zorv.plugin.zorvweb` | AI_TOOL ×15、ACI_CAPABILITY ×10、UI_SURFACE ×1 | **大件插件**：设备端无头浏览器（AI 读写网页）+ 宿主承载的浏览器窗口（手输地址栏与 AI 共用同一引擎）。由跨进程 ACI 受控端浏览器改编为进程内插件 |

对照验证点：
- 装「开发工具箱」→ 问「把这段 JSON 美化一下 ...」→ 应调 `dev_json`；问「这段的 sha256」→ `dev_hash`。
- 装「单位换算」→ 问「100 公里等于多少英里」→ `unit_convert(category=length)`。
- 装「待办清单」→ 说「记一下 明天交周报」→ `todo_add`；**杀掉应用重开**再问「我的待办」→ 数据仍在（验证私有存储）。
- 装「设备体检」→ 问「我手机电池怎么样」→ `sys_battery`（宿主本身没有这个工具）。
- 装「ZorvWeb 网页引擎」→ ① 说「打开 example.com 看看」→ 应调 `web_open` + `web_crawl`；
  ② 问「帮我搜一下 xxx」→ `web_search_page`；③ 说「打开浏览器窗口」→ `plugin_surface_open` 弹出宿主承载的浏览器界面；
  ④ 在界面上手动输地址跳转，再回对话框问「现在这个页面讲了什么」→ AI 读到的是**同一个页面**（验证一个引擎两个入口）。

---

## 六、安装与安全边界

**插件拥有与宿主同等的进程权限**（同一进程内运行），所以安全边界只有一道：
**插件 APK 必须与宿主同签名**（SHA-256 证书一致），否则 `PluginInstaller` 直接拒绝安装。

其他约束：
1. 必须有 `quro.plugin.entry` meta-data；
2. 只安装到 `/data/data/<宿主>/files/plugins/`（绝不放外置存储，避免被篡改）；
3. 原子替换 + 失败回滚（旧版本不会被写坏）。

### 三种安装方式
- **界面**：工具中心 → 插件 → 导入插件 APK
- **AI**：直接说「把这个 APK 装上」→ 模型调 `plugin_install { path }`
- **命令行**：`./gradlew :plugin-express:assembleRelease` 后把 APK 拷到设备

### 装完之后
插件贡献的 AI 工具**立刻**出现在模型工具集里（下一轮 function calling 可见），
不需要重启宿主。改完插件重新安装后用 `plugin_reload` 热重载即可。

---

## 七、AI 可用的插件管理工具

| 工具 | 作用 |
|---|---|
| `plugin_list` | 列出已装插件 + 扩展点统计 |
| `plugin_info` | 看某插件的扩展点明细（含 AI 工具名 / ACI 能力名） |
| `plugin_install` | 从本地 APK 安装（含同签名校验） |
| `plugin_uninstall` | 卸载并移除其全部能力 |
| `plugin_reload` | 热重载指定插件或全部插件 |

---

## 八、最容易踩的坑

| 现象 | 原因 | 处理 |
|---|---|---|
| `ClassCastException` 调接口时 | 契约类被 `implementation` 打进了插件 APK，插件与宿主各持一份 Class | 改 `compileOnly(project(":plugin-contract"))` |
| 安装被拒「签名与宿主不一致」 | 插件用了 debug 签名或另一把 key | 复用根目录 `keystore.properties` |
| 装上了但 AI 看不到工具 | 插件 Entry 没跑（看 logcat `QuroPluginEngine`）、或 `aiTool` 没有 `execute {}` | 检查 `onCreate` 与 DSK 报错 |
| 改了插件没生效 | 插件已加载的类不会被重新加载 | 用 `plugin_reload`（内部会 unload → load） |
| `log()` 编译不过 | `android.util.Log.i` 返回 Int，接口要求 Unit | 用花括号体写，不要用 `= Log.i(...)` |

---

## 九、真机验证要点

1. 装宿主（Release，签名 `D9:5B:1B:…:C7:99`）；
2. 工具中心 → 插件 → 导入 `plugin-express-release.apk` → 应显示「已加载」+「扩展点：AI 工具×1、ACI 能力×1、斜杠指令×1」；
3. 对话框问「帮我查快递 SF1234567890」→ AI 应调用 `express_query` 并返回模拟轨迹；
4. 卸载插件 → AI 再问快递 → 应回复没有该工具（能力已随插件移除）。
