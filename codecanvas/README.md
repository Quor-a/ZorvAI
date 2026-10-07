# CodeCanvas SDK

> 一个脚本驱动、多引擎、多后端的 Android 出图 SDK。
> 写代码（人或 AI） → 执行 → 渲染 → 导出图片/SVG。

---

## 1. 设计核心：DrawCommand 中间层

```
JS ┐
Lua ├─→  ScriptEngine  ─→  DrawList(DrawCommand)  ─→  Renderer  ─→  Bitmap / SVG
Py  │                                                   ├ Canvas
DSL ┘                                                   ├ WebView
                                                        ├ Compose
                                                        └ SVG
```

**脚本只产指令，不碰 Android API；后端只消费指令，不关心脚本来源。**
于是 4 引擎 × 4 后端 = 16 种组合，实现成本是 4 + 4 而不是 16。

副作用：同一份业务逻辑可以在 JS / Lua / Python / Kotlin DSL 之间平移，
换渲染后端不用改一行脚本。

---

## 2. 模块一览

| 模块 | 作用 | 体积代价 | 是否必须 |
|---|---|---|---|
| `codecanvas-core` | 抽象层：引擎/渲染/导出/高亮接口 + 门面 | ~50KB | ✅ |
| `engine-quickjs` | JavaScript（QuickJS） | ~900KB | 选一 |
| `engine-lua` | Lua（LuaJIT/Lua 5.4） | ~600KB | 选一 |
| `engine-python` | Python（Chaquopy） | +10~30MB | 可选 |
| `engine-kotlindsl` | 自研行式 DSL | **0** | 可选 |
| `renderer-canvas` | 原生 Bitmap + Canvas | 极小 | ✅ 推荐 |
| `renderer-webview` | HTML/CSS 排版（Carbon 观感） | 依赖系统 WebView | 代码卡片必选 |
| `renderer-compose` | 输出 ImageBitmap 内联展示 | +Compose | 可选 |
| `renderer-svg` | 矢量 SVG 输出 | 极小 | 可选 |
| `highlight-webview` | highlight.js 高亮 + 内置 lexer 兜底 | ~1MB（js） | 代码卡片必选 |
| `llm-connector` | OpenAI 兼容流式接入 + 代码块抽取 | ~400KB | AI 场景 |

**只引入你需要的 module**，`installDefaults()` 会用反射探测已引入的实现并自动注册，
未引入的不会打进 APK。

---

## 3. 快速开始

### 3.1 依赖

```kotlin
// settings.gradle.kts 已 include 全部模块，宿主只 implementation 需要的
dependencies {
    implementation(project(":codecanvas-core"))
    implementation(project(":engine-quickjs"))
    implementation(project(":renderer-canvas"))
}
```

### 3.2 初始化

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CodeCanvas.installDefaults(this)
    }
}
```

### 3.3 脚本 → 图片（三行）

```kotlin
val bmp = CodeCanvas.session(context)
    .engine(ScriptLanguage.JAVASCRIPT)      // 或 LUA / PYTHON / KOTLIN_DSL
    .renderer(RenderKind.CANVAS)            // 或 WEBVIEW / COMPOSE / SVG
    .canvas { copy(width = 1080f, height = 1440f, scale = 2f) }
    .runAndRender(script)                   // suspend

val uri = CodeCanvas.session(context).export(bmp, ExportFormat.PNG)
```

### 3.4 AI 生成代码 → 代码卡片长图

```kotlin
val llm = LlmStreamClient(baseUrl = "https://your-proxy.example.com", model = "qwen-coder")

// 流式展示（先当纯文本渲染，收流后再高亮，避免半截 ``` 乱码）
llm.stream("用 Kotlin 写一个 SSE 解析器").collect { token -> appendText(token) }

// 收流后抽取并出图
val gen = llm.generateCode("用 Kotlin 写 OkHttp SSE 流式解析", "kotlin")
if (gen.complete && gen.code != null) {
    val out = CodeCanvas.session(context)
        .renderer(RenderKind.WEBVIEW)
        .render(RenderSource.CodeCard(gen.code!!, gen.language!!, title = "SseParser.kt"))
    val uri = CodeCanvas.session(context).export(out, ExportFormat.PNG)
}
```

---

## 4. 脚本 API（四语言一致）

| JS / Lua `:` / Python `.` | DSL | 说明 |
|---|---|---|
| `canvas.background('#0d1117')` | `bg #0d1117` | 铺背景 |
| `canvas.fill('#58a6ff')` | `fill #58a6ff` | 填充色 |
| `canvas.stroke('#fff', 2)` | `stroke #fff 2` | 描边色+宽度 |
| `canvas.rect(x,y,w,h,r?)` | `rect x y w h r` | 矩形（r 圆角） |
| `canvas.circle(cx,cy,r)` | `circle cx cy r` | 圆 |
| `canvas.line(x1,y1,x2,y2)` | `line …` | 线 |
| `canvas.path('M0 0 L100 100')` | `path 'M0 0 L100 100'` | SVG path 子集 |
| `canvas.text('hi',x,y,size,align)` | `text "hi" x y size align` | 文本 |
| `canvas.richText(spans,x,y)` | — | 已高亮的代码行 |
| `canvas.log(msg)` | `log msg` | 调试输出，进 ScriptResult.logs |

DSL 额外支持变量与表达式：`$x = {100 + $i * 60}`、`repeat 12 i { }`、`if {…} { }`。

**四种语言同一段逻辑的对照见 `sample/MainActivity.kt` 顶部。**

---

## 5. 选型建议

| 场景 | 引擎 | 后端 |
|---|---|---|
| 动态下发的绘图脚本 | JavaScript | Canvas |
| 规则化模板 / 配置驱动 | Lua | Canvas |
| 科学绘图、要 matplotlib | Python | Canvas（matplotlib 出 base64） |
| 体积极度敏感 | Kotlin DSL | Canvas |
| **代码卡片长图** | 不需要引擎 | **WebView** |
| 流程图 / 架构图 | JavaScript | SVG |
| UI 里内联预览 | JS / DSL | Compose |

---

## 6. 必读的工程细节

1. **沙箱**：脚本默认隔离，`ScriptSandbox.STRICT` 下 2s 超时 + 5000 指令上限 + 禁 import/网络。
   超时靠「中断标志 + 回调抛异常」软中断（JS/Lua 是同步执行，无法硬杀线程）。
2. **长图 OOM**：任一边超过 `maxEdgePx`（默认 4096）时 Canvas 后端自动降采样，
   WebView 后端分段绘制后拼接。不要直接创建超大 Bitmap。
3. **WebView 不可用**：Android Go / 系统 WebView 更新中 / MDM 禁用时，
   `WebViewHighlighter` 会自动降级到内置的 `BuiltinLexer`（正则高亮，零依赖）。
4. **highlight.min.js 需自行放置**：
   下载 `highlight.min.js` 放到 `highlight-webview/src/main/assets/codecanvas/highlight/`，
   否则自动走内置 lexer（内置支持 Kotlin/Java/JS/TS/Python/Go/Rust/JSON/Bash）。
5. **API Key 不下发到端**：`LlmStreamClient` 的 `baseUrl` 请指向你自己的后端代理。
6. **Python 首启慢**：解释器冷启动 300~800ms，务必后台 `prepare()` 预热。

---

## 7. 扩展

**加一种脚本语言**：实现 `ScriptEngine`，把脚本调用转发到 `DrawListBinding` 即可，
渲染层零改动。

**加一种渲染后端**：实现 `Renderer`，消费 `DrawCommand`，
然后 `CodeCanvas.registerRenderer(RenderKind.XXX) { MyRenderer() }`。

## License

MIT

---

## 与服务端版本的差异（重要）

服务端（Python）跑在独立进程里，**死循环可以直接 kill**；
移动端 QuickJS/LuaJIT 是**同步执行、无法从外部中断**，
`while(true){}` 会永久占住线程。因此移动端做了三层防御：

| 层 | 手段 | 能拦住 |
|---|---|---|
| 1 | `ScriptSandbox.staticLoopGuard` 静态预检 | `while(true)` / `for(;;)` 在入口被拒 |
| 2 | `instructionBudget` 指令预算 | `while(true){ canvas.circle(...) }` |
| 3 | 专用线程 + 超时弃用 | 宿主线程不被拖死，下次请求可用 |

仍然拦不住的是**纯计算死循环**（循环体不调 canvas 且绕过静态预检）。
生产环境处理不可信脚本，请送到服务端执行。

## v1.1 修复记录

### P0 编译阻断
- `PlainHighlighter.highlightToSpans` 返回类型与接口声明不一致 → core 编译失败。
  接口统一为 `highlightToLines(): List<HighlightedLine>`（保留行号，卡片排版需要）。

### P0 功能 Bug
- **`canvas.background()` 不生效**：只发了 Rect 指令，没写 fill 也没带样式。
- **Text.align 完全未实现**：CANVAS / COMPOSE 两个后端一律左对齐，
  `text(..., 'center')` 被静默忽略。现在按 measureText 反算起点。
- **SVG 后端产出非法文档**：Translate/Rotate/Scale 开的 `<g>` 从不闭合。
  已改为统一 `openGroups` 计数收口（6 个场景全部验证通过）。
- **SVG 渐变忽略角度**：x2/y2 硬编码 100%，angleDeg 形同虚设。
- **`isMdComplete` 判定反了**：完整代码块也被判成未完成（7 个用例错 5 个），
  AI 场景里卡片永远渲染不出来。改为纯围栏计数。

### P1 能力补齐
- **代码卡片不再依赖 WebView**：新增 `CodeCardLayout`，
  CANVAS / COMPOSE / SVG 三个后端共用，WebView 不可用时链路不断。
- **SVG 的 CodeCard 从空壳到完整**：此前只有文本行，无窗口栏/行号/高亮/背景。
- **highlight.js 改为逐行调用**（`ccHighlightLines`），
  避免整体高亮后按 `\n` 反切导致跨行字符串/块注释着色错位。
- **DSL 支持嵌套块**（此前块内再写块会报「未知指令: }」）。
- **GuardedBridge 覆盖全部 API**（此前只守卫 6 个，改用 oval/arc 即可绕过预算）。
- **`arc` 补齐 useCenter**，`richText` 支持字号。

### P1 工程质量
- 新增 JVM 单元测试（`codecanvas-core/src/test`，Robolectric + Truth），
  覆盖沙箱参数、DrawList 上限、颜色、代码块抽取、卡片布局。
- `Exporter` 的 Vector/ComposeBitmap 失败路径给出可操作提示，
  不再抛裸 `NoClassDefFoundError`。

## 编译与测试

```bash
./gradlew :codecanvas-core:test          # JVM 单元测试
./gradlew :codecanvas-core:assembleRelease
```

> **注意**：`highlight.min.js` 需自行放入
> `highlight-webview/src/main/assets/codecanvas/highlight/`，
> 缺失时自动降级为内置的 `BuiltinLexer`（覆盖 9 种语言）。

## 已知限制

1. QuickJS 无法中断纯计算死循环（见上表）。
2. `DrawCommand.Image` 由宿主自行解码绘制，SDK 不强制依赖图片库。
3. Compose 后端的 `Polyline` 用 `drawPoints`，闭合多边形不填充。
4. 静态死循环预检有误杀可能（`while(true){ if(x) break; }`），
   可在 `ScriptSandbox` 里关闭 `staticLoopGuard`。
