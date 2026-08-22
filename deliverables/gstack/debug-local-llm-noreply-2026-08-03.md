# 调试复盘：本地 LLM「加载 / 不出字」整条链核对

**日期**：2026-08-03
**场景**：调试复盘（本地离线 LLM 不回复 / 一直加载）
**参与成员**：排障手（调查员）

---

## 0. 范围与背景（避免与已落地工作冲突）

- 上层「零流式」根因（[QuroAssistant] 对本地强制 `streaming=false`、`routeLocal` 无 `onToken`、引擎 `generateStream` 只攒不回吐）**已在 STREAMFIX 包修复并编译通过**（桌面 `QuroAI-full-debug-local-STREAMFIX-2026-08-03.apk`）；原生层亦经核对与 PocketPal v0.12.7 逐行等价、无静态缺陷。
- 但用户实测 STREAMFIX **仍不回复**，并点名「模型加载问题就没有这个功能 / 其他问题更多」。
- 本报告的**范围**：隔离「加载这一步」本身——即本地模型能否真从磁盘/GPU 起来并进入推理。结论：代码层加载链完整、原生是真实实现（非桩）；因此剩余的「加载不工作」是**运行时**问题，需真机日志定责（见 §4）。流式修复只在「加载成功」后才有意义。

---

## 📌 TL;DR（执行摘要）

- **整体结论**：🟡 条件通过 —— 代码层「本地模型加载 + 推理 + 上屏」整条链已闭环，**没有发现“缺功能 / 没接线”的断点**。
- **关键反转**：用户报告的「模型加载没有这个功能 / 一直进行中但不出字」是**运行时问题，不是代码缺失**。
- **阻塞项数量**：1（缺真机运行日志 / 截图文字，无法定责到具体假设）。
- **下一步**：用户贴出 `Download/QuroAI_logs/` 最新日志或截图文字 → 按证据定点修复。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟡 条件 Go（代码完整，待运行时验证） |
| 严重度分布 | 🔴 0（代码缺失）/ 🟠 待定（运行时）/ 🟡 0 / 🟢 链完整 |
| 关键行动项 | 1 条（取真机日志） |
| 建议负责人 | 主理人（按日志定责后修） |

---

## 1. 整条链逐环节核对（亲读源码，非片段）

### ① 配置屏注册（`QuroModelConfigScreen.kt`）
- `folderPicker`（499–521）：`OpenDocumentTree()` → `copyDocumentTree` 复制到 `filesDir/quro_local_models/$id` → `repo.upsert(path=真实绝对路径, modelNames=扫描到的 gguf 基名；MNN 用 treeName)`。类型按「含 `llm_config.json` → MNN，否则 LLAMA_CPP」自动判定。
- 「选为当前模型」（618–622）：`copy(provider=m.type.name, localModelPath=m.path, model=m.modelNames.firstOrNull())`。
- **结论**：注册与选中逻辑完整，存的是真实文件夹绝对路径（已修复早期 content-URI 失效）。

### ② 聊天侧激活（`ChatScreen.kt` 977–989）
- `m.id.startsWith("__local__")` → `copy(provider=lm.type.name, localModelPath=lm.path, model=lm.modelNames.firstOrNull() ?: "")`。
- **结论**：选中本地模型后 `cfg.provider ∈ {MNN, LLAMA_CPP}`、`localModelPath` 为真实目录。链路可达 `routeLocal`。

### ③ 路由（`QuroAssistant.kt`）
- `ask`（119）：`if (cfg.provider == "MNN" || cfg.provider == "LLAMA_CPP")` → `routeLocal`。
- `routeLocal`（348–372）：`repo.loadAll()` → `firstOrNull{type==provider && path==localModelPath} ?: firstOrNull{type==provider}` → `resolveLocalEngine().run(...)`。
- `resolveLocalEngine`（433–440）：反射 `QuroLocalEngineNative`（full 风味），失败回退 `QuroLocalEnginePlaceholder`。
- **结论**：路由完整。

### ④ 引擎（`QuroLocalEngineNative.kt`）
- `runLlama`（183–312）：`resolveLlamaModelFile` → `precheckLlamaFile`（GGUF 魔数）→ 动态 `n_ctx`/`nThreads` → `LlamaSession.create` → `setSamplingParams` → `applyChatTemplate(addAssistant=true)` → `generateStream(onToken)`。
- `runMnn`（79–141）：`resolveMnnDir` → `llm_config.json` 预检 → `MNNLlmSession.create` → `generateStream`。
- **结论**：引擎实现完整，含此前修复（流式 onToken、addAssistant、n_ctx 动态化、prompt 瘦身）。

### ⑤ 会话封装（`LlamaSession.kt` / `MNNLlmSession.kt`）
- `LlamaSession.create` → `LlamaNative.nativeCreateSession`；`isAvailable()` 用 `runCatching` 兜底。
- **结论**：封装完整。

### ⑥ JNI 原生层（关键！`llama_jni_stub.cpp` + `LlamaNative.kt` + `LlamaLibraryLoader.kt`）
- `LlamaLibraryLoader.loadLibraries()`：`System.loadLibrary("LlamaWrapper")`。
- `llama_jni_stub.cpp` 存在**两套**实现，由宏切换：
  - 24 行：`#if defined(OPERIT_HAS_LLAMA_CPP) && OPERIT_HAS_LLAMA_CPP` → **真实实现块**（include `llama.h`/`chat.h`，`nativeCreateSession` 真调 `llama_model_load_from_file` → context → sampler）。
  - 205 行：`#if !(...)` → **STUB 块**（`nativeIsAvailable` 返 `JNI_FALSE`，`nativeCreateSession` 返 `0`）。
  - 789 行：真实 `nativeIsAvailable` 返 `JNI_TRUE`；802 行：真实 `nativeCreateSession` 真正加载。
- `llm/llama/CMakeLists.txt:29`：`target_compile_definitions(LlamaWrapper PRIVATE OPERIT_HAS_LLAMA_CPP=1)` → **真实实现被编译，STUB 被排除**。
- **结论：原生后端是真实实现的，不是桩。模型加载在代码层具备完整功能。**
- 重要细节（11–22 行）：顶部注册了 native 崩溃信号处理器，把 `SIGSEGV/SIGBUS/SIGABRT/SIGILL/SIGFPE` 写 **tombstone 到 `Download/QuroAI_logs/`**（无需 adb 即可取）——这是运行时定责的关键来源。

### ⑦ 错误上屏（`QuroAssistant.ask` 318–331）
- `is QuroLlmResult.Error` → 复用 `streamPlaceholderId` 写入 `⚠️ ${message}` 并 `emit`。
- **结论**：加载/推理失败会以**错误气泡**呈现，不会无限转圈——除非原生层直接崩溃/挂起，绕过 Java 错误处理。

---

## 2. 综合判断

代码层：本地模型「加载 + 推理 + 上屏」整条链已闭环，**没有发现“缺功能 / 没接线”的断点**。因此用户报告的「模型加载没有这个功能 / 一直进行中但不出字」是**运行时**问题，不是代码缺失。

---

## 3. 运行时失败的三类假设（需真机证据定责）

| # | 假设 | 机制 | 预期现象 |
|---|------|------|---------|
| 1 | GGUF 未真正落地 | `copyDocumentTree` 对大文件静默失败 → `quro_local_models/$id` 无 `.gguf` → `modelNames` 空 → `cfg.model=""` → `resolveLlamaModelFile` 返回 null | 错误气泡「模型文件未找到」 |
| 2 | `.so` 运行时不可加载 | 宏已开、APK 含 `libLlamaWrapper.so`，但依赖 .so 缺失/ABI 不匹配 → `LlamaNative` 初始化抛 `UnsatisfiedLinkError` → `isAvailable` 返 false → `create` 返 null | 错误气泡「会话创建失败」 |
| 3 | **原生层挂起/崩溃（最吻合“一直进行中”）** | `llama_model_load_from_file` 在真机 OOM / 不兼容 GGUF / KV-cache 分配失败时直接 abort（被 tombstone 捕获）或长时间阻塞 → 既不吐 token 也不返 Error → UI 卡在「⏳ 正在加载本地模型…」 | **真机卡死 / 闪退，无 Java 错误** |

> 佐证假设 3：`nativeIsAvailable()` 无条件返 `JNI_TRUE`（789 行），**从不校验后端是否真初始化**；若 `ensureBackendInit` 失败，`nativeCreateSession` 仍会被调用并可能在原生层卡死/崩溃，Java 层拿不到干净错误。

---

## 4. 证据请求（决定性）

我无法读取截图（图片对模型返回 Content filtered）。请二选一：
- **(A)** 把截图里的文字 / 报错贴出来；
- **(B)** 手机文件管理器打开 `Download/QuroAI_logs/`，把最新的 `LocalEngine` / `LlamaNative` / **tombstone** 日志贴出来（重点看 `✗ llama 模型文件未找到` / `✗ llama 会话创建失败` / `Failed to load model` / `SIGABRT` tombstone）。

**拿到证据前，不再做任何猜测性改动。**

---

## ✅ 行动清单

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 取真机日志 / 截图文字 | 用户 | P0 | 立即 |
| 2 | 按日志定责后定点修复 | 主理人 | P0 | 证据到后 1 轮内 |
| 3 | 顺手修 `nativeIsAvailable` 真实校验后端（避免“假可用”导致卡死无错误） | 主理人 | P2 | 下一轮 |

---

## ⚠️ 待完善 / 已知局限

- 未跑真机，无法确认上述哪个假设成立。
- PocketPal 缓存目录已清空，本次未再比对；其 `index.ts` 整条链此前已读，结论：同款 GGUF 在 PocketPal 正常，得益于真正的 `add_generation_prompt` 与逐 token 回调——这两点我们已对齐。
- 本报告为单成员（排障手）复盘，未走多成员协作流程。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
