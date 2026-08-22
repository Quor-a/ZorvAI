# 本地离线 LLM 引擎移植报告（operit → QuroAI，去品牌化）

**日期**：2026-08-03
**场景**：全流程交付（原生模块移植 Phase 1 + 薄适配器接入 Phase 2）
**参与成员**：主理人直接执行（本环境团队编排不可用，按既定约定主理人干 + 落盘）
**关联任务**：Task #1101（本地 LLM 引擎移植）、#1103（薄适配器）、#1104（反射接入 + 契约文档）

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟢 通过（实现 + 编译 + 风味隔离三重验证）；⏳ 仅余真机首轮推理验证（需真机，P0）。
- 编译门禁已关闭：`:app:assembleFullDebug` 与 `:app:assembleFdroidDebug` 均 **BUILD SUCCESSFUL**（full 50s、fdroid 29s；原生库经 Gradle 缓存跳过重型 NDK 重编）。
- 已落地：从 operit 移植 **MNN + llama.cpp 双后端**（保留包名以锁定 JNI 符号），去品牌化为 `quro_*`，仅编入 `full` 风味。
- 已落地：写**薄适配器** `QuroLocalEngineNative`（`app/src/full/java`）直接驱动 `MNNLlmSession`/`LlamaSession` JNI，经 `QuroAssistant.routeLocal` **反射**接入，fdroid 反射失败回退 `QuroLocalEnginePlaceholder`（不崩溃）。
- 风味隔离验证通过：full APK 含 29 个 `.so`（含 libMNN/libllama/libggml/libquroplugin）；fdroid APK 零 MNN/llama/ggml（仅含项目既有 libquroplugin + GeckoView 系列，后者为历史债务）。
- 下一步：真机安装 full APK，载入 `.mnn`（目录含 llm_config.json）与 `.gguf`，跑通首轮 MNN / llama 推理。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go（full + fdroid 编译通过，风味隔离验证通过；仅余真机推理） |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 1（真机首轮推理未验）/ 🟢 多项 |
| 关键行动项 | 3 条（见下） |
| 建议负责人 | 主程（本地 NDK 构建 + 真机验证） |

---

## 1. 架构决策（为什么是「薄适配器」而非 verbatim fork）

operit 的 `MNNProvider`/`LlamaProvider` 实现 operit 自有 `AIService` 聊天接口，依赖约 30 个 operit 专有类（`PromptTurn`/`Stream`/`StructuredToolCallBridge`/`ChatUtils`/`FFmpegUtil`/媒体池/`ModelListFetcher`/`R.string`/`AppLogger` 等）。逐字 fork 会把整个 chat 子系统拖进 QuroAI，与现有 `QuroLocalEngine`/`QuroChatMessage`/`QuroAssistant` 架构冲突。

**决策**：写薄适配器 `QuroLocalEngineNative`，直接驱动移植的 `MNNLlmSession`/`LlamaSession` JNI 会话，把 `QuroChatMessage` 历史映射为原生输入、token 流累积为 `QuroLlmResult.Text`，**不引入** operit 的 `AIService`/`PromptTurn`/`Stream`。

### 风味隔离机制（关键约束）
- `:mnn` / `:llama` 仅 `fullImplementation`（见 `app/build.gradle.kts` 128–130 行），fdroid 风味 classpath 不含这两个模块。
- 因此任何引用 `com.ai.assistance.mnn`/`.llama` 的代码**必须放在 `app/src/full/java` 风味源码集**，并经 `main` 源码集通过**反射**调用。
- `routeLocal`（位于 `main`）用 `Class.forName("...QuroLocalEngineNative").getDeclaredConstructor().newInstance()` 实例化；fdroid 下该类不存在 → 反射抛异常 → 回退 `QuroLocalEnginePlaceholder`。二者均实现 `main` 中的 `QuroLocalEngine` 接口，编译期无跨风味硬依赖。

---

## 2. 交付清单（Phase 1 + Phase 2）

### 2.1 Phase 1 — 原生模块移植（已完成并验证）
- `llm/mnn/`、`llm/llama/`：28 个源 blob 从 operit 搬入，`package com.ai.assistance.mnn` / `.llama` **保留**（含量 `.so` 无关，仅 `llm_config`/JNI 注册靠此；改包名需同步改 cpp JNI 注册，故保留）。
- `cmake/quro_git_source.cmake`：去品牌为 `quro_*`（函数名）/ `QURO_*`（cache 变量前缀）。
- `llm/{mnn,llama}/CMakeLists.txt`：`operit_prepare_git_source` → `quro_prepare_git_source`；`OPERIT_*` → `QURO_*`；**保留** `target_compile_definitions(LlamaWrapper PRIVATE OPERIT_HAS_LLAMA_CPP=1)`（JNI 真实实现受该宏保护）。
- `settings.gradle.kts`：`include(":mnn", ":llama")` + `projectDir = file("llm/mnn"|"llm/llama")`。
- `app/build.gradle.kts`：`flavorDimensions += "distribution"`；`productFlavors { full{...}, fdroid{...} }`；`fullImplementation(project(":mnn"))` / `fullImplementation(project(":llama"))`。
- 已丢弃 `llm/mnn/src/main/jniLibs/arm64-v8a/libsherpa-mnn-jni.so`（违反 F-Droid 红线：预编译二进制无源码）。

### 2.2 Phase 2 — 薄适配器接入（本次实现）
**新增文件** `app/src/full/java/com/ai/assistance/quro/core/network/QuroLocalEngineNative.kt`：
- `class QuroLocalEngineNative : QuroLocalEngine`，实现 `run(model, modelName, messages, temperature, maxTokens): QuroLlmResult`。
- **MNN 分支** `runMnn`：
  - `resolveMnnDir(path)`：path 是目录直接用；是 `.mnn` 文件回退父目录；否则报错。
  - `MNNLlmSession.create(modelDir)` → `generateStream(history: List<Pair<role,content>>, maxTokens, onToken)` 累积 token。
  - `buildMnnHistory` 把 `QuroChatMessage` 映射为 `(role, content)`；`tool` 角色退化为 `user`（MNN 无 tool 角色）。
- **llama.cpp 分支** `runLlama`：
  - `resolveLlamaModelFile(folder, modelName)`：优先 `<modelName>.gguf`，否则按名模糊匹配首个 `.gguf`。
  - `LlamaSession.create(path, Config())` → `setSamplingParams(temperature, ...)` → `applyChatTemplate(roles, contents, addAssistant=false)` 拼 prompt → `generateStream(prompt, maxTokens, onToken)`。
  - 每次 run 重建会话（不跨对话缓存 KV-Cache，正确性优先）。
- 异常统一收口为 `QuroLlmResult.Error(...)`；`finally` 中 `runCatching { session.release() }`。

**修改文件**：
- `app/src/main/java/.../QuroAssistant.kt`：`routeLocal` 改为调用 `resolveLocalEngine().run(...)`；新增 `resolveLocalEngine()` 反射实例化（fdroid 回退 placeholder）。
- `app/src/main/java/.../QuroLocalModelRepository.kt`：修正契约文档 —— MNN `path` 现为「含 `llm_config.json` 的模型目录绝对路径（或目录内 `.mnn` 文件）」，与 `MNNLlmSession.create` 实际要求一致（之前文档误写为 `.mnn` 文件）。

### 2.3 接口签名对账（已逐条核对，编译契约正确）
| 调用点 | 原生 API | 状态 |
|--------|----------|------|
| `MNNLlmSession.create(modelDir)` | `create(modelDir, backendType="cpu", threadNum=4, precision="low", memory="low", tmpPath=null)` | ✅ 默认参生效 |
| `session.generateStream(history, maxTokens){...}` | `generateStream(history: List<Pair<String,String>>, maxTokens=-1, onToken:(String)->Boolean): Boolean` | ✅ |
| `LlamaSession.create(path, Config())` | `create(pathModel: String, config: Config): LlamaSession?` | ✅ |
| `session.setSamplingParams(...)` | 7 参（penaltyLastN=64 默认） | ✅ |
| `session.applyChatTemplate(roles, contents, false)` | `applyChatTemplate(roles: List<String>, contents: List<String>, addAssistant: Boolean): String?` | ✅ |
| `session.generateStream(prompt, maxTokens){...}` | `generateStream(prompt: String, maxTokens: Int, onToken:(String)->Boolean): Boolean` | ✅ |
| `QuroLocalEngineNative.run(...)` | 实现 `QuroLocalEngine.run(model, modelName, messages, temperature, maxTokens)` | ✅ 签名一致 |

### 2.4 发布检查清单（F-Droid 红线合规）
- [x] **本次新增的 MNN/llama/ggml 引擎隔离成功**：`:mnn`/`:llama` 仅 `fullImplementation`，fdroid 风味 APK **零** libMNN/libLlamaWrapper/libllama/libggml（实测 fdroid APK 共 14 个 `.so` 中无任何 MNN/llama 相关项；隔离验证通过 ✅）。
- [x] `full` 风味 APK 含 29 个 `.so`，含 libMNN.so / libMNNWrapper.so / libLlamaWrapper.so / libllama.so / libggml*.so / libquroplugin.so（本地 LLM 引擎 + 插件桥齐备）。
- [x] `full` 风味原生库由源码经 NDK 编译（MNN / llama.cpp 源码由 `cmake/quro_git_source.cmake` 在 cmake 配置期 FetchContent 拉取并编译，非预编译 aar）。
- [x] `app/src/full/java` 风味源码集不污染 `main` / `fdroid` 编译（反射解耦，fdroid 反射失败回退 placeholder，`assembleFdroidDebug` BUILD SUCCESSFUL 29s）。
- [x] **编译门禁关闭**：`:app:assembleFullDebug`（BUILD SUCCESSFUL 50s）与 `:app:assembleFdroidDebug`（BUILD SUCCESSFUL 29s）均通过。
- ⚠️ **已知历史债务（非本次引入，不阻塞）**：fdroid APK 仍含 `libquroplugin.so`（app/src/main/cpp 源码编译的 QuickJS 插件桥，项目既定 fdroid 设计本就保留——源码编译符合 F-Droid 红线）+ GeckoView 系列预编译 `.so`（moz*/nss3/xul 等，来自预编译 AAR，属项目级历史债务，F-Droid 全量「零预编译 `.so`」红线需另立 task 清理）。本次 LLM 引擎移植的隔离目标已 100% 达成。

### 2.5 回滚预案
- 若 `full` 原生编译/加载失败：删 `app/src/full/java/.../QuroLocalEngineNative.kt` + 还原 `QuroAssistant.routeLocal` 为 `QuroLocalEnginePlaceholder.run(...)`，即回到「模型登记但提示未接入」的安全态，不影响联网 API 路径。
- 若 fdroid 风味误引入原生依赖：撤销 `app/build.gradle.kts` 的 `fullImplementation` 改动、把适配器移出 `main` 即可。

---

## ✅ 行动清单

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 执行 `full` + `fdroid` 风味 `assembleDebug`，验证 Kotlin + NDK 交叉编译 + 风味隔离 | 主程 | ✅ 已完成（BUILD SUCCESSFUL，full 50s / fdroid 29s） | #1102 |
| 2 | 真机载入 `.mnn`（目录含 llm_config.json）与 `.gguf`，跑通首轮 MNN / llama 推理，确认 token 流正确累积为 `QuroLlmResult.Text` | 主程 | P0 | Phase 3 前 |
| 3 | Phase 3：把 `QuroLocalEngine.run` 适配到会话**流式** API（当前为同步整段返回；工具调用 grammar 走 `applyStructuredChatTemplate` 归此步） | 主程 | P1 | 下一迭代 |

---

## ⚠️ 待完善 / 已知局限

- **MNN 采样温度未接入**：`MNNLlmSession.create` 不接收 temperature，`runMnn` 暂未设温度（MNN 走默认采样）。llama 已接 temperature。
- **无跨对话 KV-Cache 复用**：每次 `run` 重建原生会话（正确性优先）；会话保活/池化是后续性能优化项。
- **工具调用 / grammar**：当前仅做文本生成；Phase 3 接入流式 + `applyStructuredChatTemplate` / `setToolCallGrammar`。
- **`maxTokens` 透传**：`cfg.maxTokens` 原样转发给原生层；若配置为 0/负数，原生层行为取决于各自实现（MNN 默认 -1 表示模型默认），未做夹紧。
- **编译已验**：`full`/`fdroid` 风味均 BUILD SUCCESSFUL；MNN/llama/ggml 隔离验证通过。仅余真机首轮推理（见行动清单 #2，需真机）。

---

## 📚 成员产出索引 / 文件清单

- 新增：`app/src/full/java/com/ai/assistance/quro/core/network/QuroLocalEngineNative.kt`
- 修改：`app/src/main/java/com/ai/assistance/quro/core/QuroAssistant.kt`（routeLocal + resolveLocalEngine）
- 修改：`app/src/main/java/com/ai/assistance/quro/core/model/QuroLocalModelRepository.kt`（MNN 目录契约文档）
- Phase 1 源：`llm/mnn/`、`llm/llama/`、`cmake/quro_git_source.cmake`、`settings.gradle.kts`、`app/build.gradle.kts`
- 前置研究：`deliverables/gstack/research-operit-native-modules-2026-08-03.md`（#1100，结论 🟢 可行）

---

> 本报告由软件工坊 AI 协作生成，关键决策（尤其 F-Droid 风味隔离与编译门禁）请由工程负责人复核。
