# operit 本地 LLM 原生模块调研报告（MNN + llama.cpp）

**日期**：2026-08-03
**场景**：调研 / 调试复盘（为 ZorvAI 本地离线 LLM 引擎移植做准备）
**参与成员**：排障手（调查员，主导调研）

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟢 **可行** —— MNN、llama.cpp 两套本地 LLM 后端均可移植，F-Droid 合规路径清晰。
- operit 的 `:mnn` / `:llama` 是**仓库内源码模块**（Kotlin 封装 + JNI cpp），不是预编译 AAR，也不是 git submodule。
- 关键发现：上游 **MNN / kleidiai / llama.cpp 的 C++ 源码并不在仓库内**，而是在 **CMake 配置期通过自定义 FetchContent 从 GitHub 拉取**（`git ls-remote` 解析引用 + 下载 GitHub archive tarball）。
- F-Droid 合规路径：把两个原生模块**关在 `full` 风味**（fdroid 风味保持零 `.so`），与已落地的 sherpa-ncnn 守卫策略一致。fdroid 风味永远不会触发 FetchContent（无网络需求）。
- 阻塞项数量：**0**（调研层面）；实现层需注意 3 个已知风险点（见综合发现 / 行动清单）。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go（移植可行） |
| 严重度分布 | 🔴 0 / 🟠 1 / 🟡 2 / 🟢 3 |
| 关键行动项 | 6 条（见行动清单） |
| 建议负责人 | 主理人直接干（本环境 gstack 团队编排不可用，见工作记忆） |

---

## 1. 各成员核心结论

### 🔧 排障手（调研结论）

- **核心判断**：原生层为 in-repo 源码模块，构建期 FetchContent 上游 C++。两个模块的包名 `com.ai.assistance.mnn` / `com.ai.assistance.llama` **不含 "operit" 字样**，且 Kotlin 用 `external fun` 动态查找 JNI 符号（`Java_com_ai_assistance_mnn_MNNLlmNative_native*` / `Java_com_ai_assistance_llama_LlamaNative_native*`）。→ **建议保留这两个包名不变**，仅在 Provider / 下载管理器 / UI / String 层做去品牌化，避免改动 cpp 里的 JNI 注册字符串。
- **关键建议**：
  1. 两个模块关在 `full` 风味，fdroid 风味零 `.so`；
  2. 删除 `llm/mnn` 模块自带的预编译 `libsherpa-mnn-jni.so`（F-Droid 红线 + ZorvAI 已有 sherpa-ncnn ASR，不需要）；
  3. MNN 引用从浮动 `master` 钉死到固定 tag/commit，保证 `full` 风味可复现构建。

---

## 2. 综合调研发现（去重合并后按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟠 | 可复现性 | `llm/mnn/CMakeLists.txt` | MNN 引用 `master`（浮动分支），每次构建可能拉到不同代码，无法复现 | 钉到固定 tag/commit（如 MNN 2.9.x 或已知好用的 commit） | 排障手 |
| 2 | 🟡 | F-Droid 红线 | `llm/mnn/src/main/jniLibs/arm64-v8a/libsherpa-mnn-jni.so` | 仓库内提交了预编译 `.so`（sherpa-mnn ASR 的 JNI 库） | 移植时**删除**该 `.so`；ZorvAI 已有 sherpa-ncnn ASR，不需要 MNN-ASR | 排障手 |
| 3 | 🟡 | 构建依赖 | `cmake/operit_git_source.cmake` | FetchContent 在 CMake 配置期需网络（`git ls-remote` + 下载 GitHub archive） | 仅 `full` 风味本地构建用；fdroid 风味不引入模块故不触发 | 排障手 |
| 4 | 🟢 | 架构 | `llm/mnn`、`llm/llama` | 模块为源码（Kotlin + cpp），非 git submodule、非预编译 AAR | 直接作为 `:mnn` / `:llama` 源码模块搬入 ZorvAI | 排障手 |
| 5 | 🟢 | 编译耗时 | `llm/mnn/build.gradle.kts` cmake args | GPU 后端（OPENCL/OPENGL/VULKAN）在 build.gradle 被强制 OFF，实际编译 CPU-only MNN + kleidiai | 保持现状，显著降低编译耗时 | 排障手 |
| 6 | 🟢 | 接口完备 | `MNNLlmSession` / `LlamaSession` | 提供完整流式 / 结构化 / 工具调用 / 采样参数 API，可直接对接 `QuroLocalEngine` | 适配 `run(model)` ↔ session 流式 API | 排障手 |

---

## 关键事实（JNI 契约，移植时必须保留）

- **MNN 模块 JNI 符号**：`Java_com_ai_assistance_mnn_MNNLlmNative_native*`（由 `MNNLlmNative.kt` 的 `external fun` 动态查找）。保留包名 `com.ai.assistance.mnn` 即可不动 cpp。
- **Llama 模块 JNI 符号**：`Java_com_ai_assistance_llama_LlamaNative_native*`。保留包名 `com.ai.assistance.llama`。
- **MNNLlmSession.create(modelDir, backendType="cpu", threadNum=4, precision="low", memory="low", tmpPath=null)**：要求 `modelDir` 含 `llm_config.json`；operit 模型目录约定 `Downloads/Operit/models/mnn` → 改为 `Downloads/Quro/models/mnn`。
- **LlamaSession.create(pathModel, Config(nThreads, nCtx, nBatch, nUBatch, nGpuLayers, useMmap, flashAttention, kvUnified, offloadKqv))**：模型为单个 `.gguf` 路径。
- 两个模块产物 `.so` 名：`MNNWrapper`（mnn）、`LlamaWrapper`（llama），由各自 `LibraryLoader` 在 `full` 风味 `loadLibrary`。
- MNN 模块 CMakeLists 的 `add_library(MNNWrapper ...)` 仅编 `mnnnetnative.cpp` / `mnnmodulennative.cpp` / `mnnllmnative.cpp`（**不含** `mnnportraitnative.cpp`，注释明确说明 portrait 用不同包名、不需要）。

---

## ✅ 行动清单（移植 Task #1101 / #1102 执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 在 ZorvAI 建 `llm/mnn`、`llm/llama` 源码模块（搬 operit 的 Kotlin + cpp + CMakeLists + build.gradle）；包名保留 `com.ai.assistance.mnn` / `.llama`；**删除 `libsherpa-mnn-jni.so`** | 主理人 | P0 | Task #1101 |
| 2 | 复制 `cmake/operit_git_source.cmake` → 改名 `cmake/quro_git_source.cmake`；更新两处 CMakeLists 的 include 路径；MNN 引用 `master` → 固定 tag | 主理人 | P0 | Task #1101 |
| 3 | `settings.gradle.kts` 增加 `:mnn` / `:llama`；`app/build.gradle.kts` 仅在 `full` 风味 `implementation(project(...))`，fdroid 风味不引入 | 主理人 | P0 | Task #1101 |
| 4 | 移植 `MNNProvider` / `LlamaProvider` / `MnnModelDownloadManager` 到 `com.ai.assistance.quro`（去品牌：Operit→Quro/Zorv AI，模型目录 `Downloads/Quro/models`，字符串本地化） | 主理人 | P1 | Task #1101 |
| 5 | 适配 `QuroLocalEngine.run(model)` ↔ `AIService` 流式 API（替换现有 `QuroLocalEnginePlaceholder` 报错桩） | 主理人 | P1 | Task #1102 |
| 6 | `full` 风味本地编译验证（NDK 交叉编译 CPU-only MNN + kleidiai + llama.cpp）；确认 fdroid 风味 APK 零 `.so` | 主理人 | P1 | Task #1102 |

---

## ⚠️ 待完善 / 已知局限

- **编译耗时**：MNN 从源码编译（即便 CPU-only）在 Windows + NDK 下可能 20–40 分钟，首次 `full` 构建建议在后台执行。
- **缓存误提交风险**：FetchContent 缓存位于 `.cxx/operit_deps/`（搬入后路径随 cmake 源目录），须确保 `.gitignore` 排除该目录，避免把上游源码误提交进仓库。
- **真机验证缺口**：移植后仍需真机验证 MNN-LLM 与 llama.cpp 在目标 arm64 设备上的推理正确性、tool-call、thinking 模式。
- **与本任务无关但待收尾**：gitee 的 `v1.0.15` 标签仍停在旧 commit（`cdb2b71`），需 `--force-with-lease` 修正；属 F-Droid 红线收尾，不阻塞本次移植。

---

## 📚 成员产出索引

- 排障手（调查员）调研产出：本文件。事实来源为 operit 仓库 `origin/main` @ `17df7f9` 直接读取的 `llm/mnn/CMakeLists.txt`、`llm/mnn/build.gradle.kts`、`llm/mnn/src/main/java/com/ai/assistance/mnn/*`、`llm/llama/CMakeLists.txt`、`llm/llama/src/main/java/com/ai/assistance/llama/*`、`cmake/operit_git_source.cmake`，以及 `.gitmodules` / `settings.gradle.kts` 的模块声明。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
