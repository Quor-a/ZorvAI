# kbox —— KaleidoBox 插件打包器

把源码 + `kaleido.json` 清单编译/打包成可分发的 `.kbox`（本质是一个 zip：含 `kaleido.json` + `classes.dex` + 可选 `res/`）。

> 在 ZorvAI 里，**一个 KaleidoBox 插件 = 一个独立 App**：ZorvAI 是系统，插件是跑在系统之上的 App。
> 打包只是把"App"做成可安装的分发包。

## 前提

- JDK 17（编译用 `ecj` 或 `javac`）
- Android SDK `d8`（在 `$ANDROID_HOME/build-tools/<version>/d8.jar`）
- kaleidobox-api jar：宿主导出的插件 SDK（含 `KaleidoToolkit` / `KValue` / `UiNode` 等）。宿主构建后可由 `kaleidobox-api.jar` 任务产出。

## 用法

```bash
python kbox.py build \
    --src     examples/HelloApp.java \
    --manifest examples/hello.kaleido.json \
    --api-jar /path/to/kaleidobox-api.jar \
    --d8      $ANDROID_HOME/build-tools/34.0.0/d8.jar \
    --out     hello.kbox
```

多文件项目把 `--src` 指向源码目录即可；资源目录用 `--res` 指定，会原样打进包内 `res/`。

## 安装到 ZorvAI

```kotlin
val box = KaleidoBoxHost.get()
val src = ZipSource("hello.kbox")   // 宿主侧 PackageSource 实现
box.runtime.install(src)
```

装完即出现在「工具包运行器」里，点「打开」就以独立 App（独立 Activity）运行。

## 插件工程结构

```
my-plugin/
├── kaleido.json          # 清单：id / 名称 / 版本 / runtime / units / ui / 能力 / 沙箱
├── src/
│   └── com/zorv/my/MyApp.java   # implements KaleidoToolkit
└── res/                  # 可选：图片 / 字体 / 配置
```

清单最小字段：`id`, `name`(zh/en), `version`, `runtime[]`(lang/engine/entry), `ui[]`(id/surface/render/onAction)。

能力声明（`capabilities`）示例：`data.kv`（持久化 KV）、`ui.toast`、`ui.clipboard`、`net.http`、`ai.chat`、`app.term.run`。
沙箱 `netEgress`：`deny`（默认，只调宿主能力）/ `allow`（自身发网络，但**必须**同时声明 `net.http` 能力，否则清单校验失败）。
