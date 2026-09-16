# KaleidoBox 插件平台手册（插件 = 一个 App）

> ZorvAI 是系统，一个 KaleidoBox 插件就是一个独立 App。
> 本文档是插件开发者与宿主集成方的权威参考，并标注了本次升级对照行业方案补上的能力。

## 1. 定位与架构

```
ZorvAI（系统 / 宿主 App）
└── KaleidoBox 运行时（KaleidoRuntime）
    ├── 引擎：JVM / Dex（进程内 DexClassLoader 加载 Kotlin/Java 工具包）
    ├── 能力网关：HostBridge（fs / net / ui / ai / app.* 受权限门控）
    ├── 清单校验：ManifestValidator（声明式能力 + 沙箱策略）
    └── 插件 = App：私有存储 + 生命周期 + 应用上下文
```

一个插件由 **一份 `kaleido.json` 清单 + 一个实现了 `KaleidoToolkit` 的类** 组成，打包成 `.kbox` 分发。

## 2. 插件 SDK（开发者视角）

| 概念 | 说明 |
| --- | --- |
| `KaleidoToolkit` | 插件入口。`attach(host)` 注入宿主桥；`invoke(fn,args,ctx)` 分发 render/onAction/unit；`onAppLifecycle(ev,ctx)` 响应前后台与销毁（默认空实现，可选重写）。 |
| `ToolkitHost` | 宿主投影。`call(capability,args)` 调能力；`log(...)` 打日志；`appContext` 取本包私有目录。 |
| `KaleidoAppContext` | **App 级上下文**：`filesDir`（私有文件，持久）、`cacheDir`（缓存）、`hostContext`（仅取系统服务）。 |
| `AppLifecycle` | `CREATE / RESUME / PAUSE / DESTROY`，由 `KaleidoActivity` 转发。 |
| `KValue` | 跨边界统一值（`Str/I64/F64/Bool/Arr/Obj/Err/...`），`KValue.obj(...)`、`KValue.of(...)` 等构造糖。 |
| `UiNode` | 声明式 UI 纯数据树（Column/Row/Text/Button/Card/Scroll/...），宿主负责渲染。 |
| `UiCodec` | `encode(node)→Map`、`fromK(kvalue)→UiNode`，插件侧用 `KValue.Str(Json.write(UiCodec.encode(node)))` 返回 UI。 |

### 开发者最小骨架

```kotlin
class MyApp : KaleidoToolkit {
    lateinit var host: ToolkitHost
    override fun attach(host: ToolkitHost) { this.host = host }
    override fun invoke(fn: String, args: KValue, ctx: InvokeContext): KValue = when (fn) {
        "render" -> KValue.Str(Json.write(UiCodec.encode(buildUi(args))))
        "onAction" -> { host.call("ui.toast", KValue.obj("text" to "hi")); KValue.Obj(emptyMap()) }
        "greet" -> KValue.Str("你好")
        else -> KValue.fail("E_NO_UNIT", fn)
    }
    override fun onAppLifecycle(event: AppLifecycle, appContext: KaleidoAppContext) {
        if (event == AppLifecycle.CREATE) { /* 初始化私有存储 */ }
    }
}
```

## 3. 本次升级补上的"App 级"能力（对照行业方案）

调研来源：AOSP **SystemUI Plugin Framework**（插件独立 `PluginContext` + 生命周期 + 签名/熔断）、
Android **SDK Runtime**（SDK=独立进程 + 自有存储 + IPC）、Android 应用沙箱（UID 隔离 + 私有数据目录）、
`DexClassLoader` / `InMemoryDexClassLoader` + `AssetManager.addAssetPath`（dex/资源加载）。

| 能力 | 升级前 | 升级后 |
| --- | --- | --- |
| 私有存储 | 纯内存 `ConcurrentHashMap`，重启即丢 | `PluginStorage`：每包 `kv.json` 落盘，重启仍在 |
| 应用上下文 | 无（拿不到私有目录） | `KaleidoAppContext`（filesDir/cacheDir）+ `ToolkitHost.appContext` |
| 生命周期 | 无 | `onAppLifecycle` + `KaleidoActivity` 转发 CREATE/RESUME/PAUSE/DESTROY |
| 打包 | 无外部工具 | `kbox`：源码→dex→`.kbox` 分发包 |
| 开发手册 | 无 | 本文档 + `kbox/README.md` + 示例 `HelloApp` |

## 4. 清单（kaleido.json）字段速查

```jsonc
{
  "id": "com.zorv.my",                 // 包 id，唯一
  "name": { "zh": "我的应用", "en": "My App" },
  "version": "1.0.0",
  "capabilities": ["data.kv", "ui.toast"],   // 声明用到的宿主能力
  "sandbox": { "level": "in_process", "memoryMb": 32, "netEgress": "deny" },
  "runtime": [ { "id": "main", "lang": "kotlin", "engine": "jvm_dex",
                 "entry": "com.zorv.my.MyApp", "warm": true } ],
  "units": [ { "name": "greet", "timeoutMs": 5000 } ],
  "ui": [ { "id": "main", "surface": "toolbox",
            "render": "main:render", "onAction": "main:onAction", "title": { "zh": "我的应用" } } ]
}
```

> ⚠️ `netEgress: allow` **必须**同时声明 `net.http` 能力，否则 `ManifestValidator` 报 ERROR 中止安装
> （这是早期"终端/AI 助手装不上"的根因）。

## 5. 宿主集成（三步）

```kotlin
// 1. 初始化（Application.onCreate）
val box = KaleidoBoxHost.init(applicationContext)

// 2. 安装插件（市场 / 侧载 / 内置）
box.runtime.install(ZipSource("plugin.kbox"))

// 3. 打开插件（独立 App 窗口）
context.startActivity(Intent(context, KaleidoActivity::class.java).apply {
    putExtra(KaleidoAppContract.EXTRA_PKG_ID, "com.zorv.my")
    putExtra(KaleidoAppContract.EXTRA_SURFACE_ID, "main")
})
```

## 6. 未来可演进方向（TODO，非本次范围）

- **进程隔离**：`SandboxLevel.ISOLATED_PROCESS` 已定义枚举，下一步用 `android:process=":kbox_<pkg>"` + Binder IPC 实现真正沙箱。
- **资源系统**：当前 `readResource` 返回裸字节；可加 `AssetManager.addAssetPath` 让插件带 `res/drawable`。
- **多语言引擎**：脚手架已支持 JS/Python/Lua/WASM（`EngineFabric`），按需注入对应原生运行时。
- **热更新 / 版本回滚**：清单含 `version`，可加 `rollbackTo(prev)` 与签名校验。
