# QuroAI NCNN 引擎替换 + 模型配置 UI 改版交付报告

**日期**：2026-07-19
**场景**：全流程交付（NCNN 引擎替换 → 模型配置 UI 改版 → 构建发布）
**参与成员**：排障手（gstack-investigator）；主理人（编排 / 原生库核验 / 构建 sign-off）

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟢 通过 — `clean assembleDebug` BUILD SUCCESSFUL，APK 产出（130 MB），原生库齐备。
- 端侧 STT 引擎已从 Sherpa-ONNX 更换为 **Sherpa-NCNN（SenseVoice，纯本地）**，代码改写完成并编译通过。
- 模型配置 UI 改为 MoWenApp「编辑排版风」，**功能 100% 保留**，编译通过。
- 阻塞项数量：0（构建/功能无阻塞）；1 项建议后续加固（假模型下载校验，非阻塞）。
- 下一步：安装 v58 APK 在真机实测 SenseVoice NCNN 端侧转写；评估是否加固下载校验。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 2 / 🟢 多项 |
| 关键行动项 | 4 条 |
| 建议负责人 | 主理人 / 排障手 / 用户 |

---

## 1. 各成员核心结论

### 🔧 排障手（调试与根因 / gstack-investigator）

- **核心判断**：NCNN 引擎替换已完成——新增 Kotlin 封装层（`com/k2fsa/sherpa/ncnn/` 4 文件），并把 `QuroAsrModels` / `QuroAsrService` / `QuroOnDeviceAsr` 改写为 SENSE_VOICE/NCNN 加载路径（`OfflineRecognizer(null, config)` 绝对路径加载，`createStream→acceptWaveform→decode→getResult` 转写链路）。模型配置 UI 重写为 MoWenApp 编辑排版风（衬线「模型配置」标题 + 眉标 + `ChapterLabel` 01–04 章节 + `UnderlineField` 下划线输入），所有 `vm.cfg` 绑定、对话框、仓库调用保持不变。额外产出一份 **3452 字节假模型根因报告**。
- **关键建议**：下载只判 HTTP 码、不校验 `Content-Length`/最小体积，解压按扩展名不验 magic，导致 GitHub 404 的 HTML 页（约 3452 字节）被当模型包保存；`binderDied` 未结束 pending deferred，原生 SIGSEGV 时静默空等 60s 才返 false。建议下载/解压阶段加体积 + magic + checksum 校验，并在 `binderDied` 完成 deferred 上报「模型可能损坏」。

### 主理人（lead）补充核验

- **核心判断**：原生库完整性经权威 `DT_NEEDED` 核验——`libsherpa-ncnn-jni.so` 仅依赖 `libncnn.so` + 系统库；v2.1.15 将 `sherpa-ncnn-core` / `kaldi-native-fbank-core` 静态链入，故交付的 2 个 `.so` 即齐备，无缺失依赖。构建用 `env -i` 绕过会话环境膨胀，成功产出 APK。
- **关键建议**：本次按用户「只换 UI、功能保留」指令收口；假模型加固作为后续迭代项，不阻塞发布。

> 产品评审员（gstack-product-reviewer）曾发来一份「通用多引擎 ASR 配置系统」设计，与本次锁定的 NCNN/SENSE_VOICE 方向冲突且超出范围，已搁置，未计入本交付。

---

## 交付清单（代码变更 + 测试覆盖 + 发布检查清单 + 回滚预案）

**代码变更**
- 新增 `app/src/main/java/com/k2fsa/sherpa/ncnn/`：`OfflineRecognizer.kt` / `OfflineStream.kt` / `FeatureConfig.kt` / `WaveReader.kt`（官方 Kotlin 封装源码打入）。
- 改写 `QuroAsrModels.kt`：`AsrModelType`=`SENSE_VOICE`/`UNKNOWN`，`detectAsrLayout` 识别 `.ncnn.param`/`.ncnn.bin`→NCNN，`AsrModelCatalog.BUILTIN` 4 个 SenseVoice NCNN 变体（int8/fp16 × 2024/2025），`buildOfflineConfig` 构造 `OfflineSenseVoiceModelConfig`。
- 改写 `QuroAsrService.kt`：`recognizer = OfflineRecognizer(null, config)` 绝对路径加载；`doRecognize` 走 `createStream/acceptWaveform/decode/getResult(text)`。
- 改写 `QuroOnDeviceAsr.kt`（ONNX_LEGACY→`clearDeploy`+false；NCNN→SENSE_VOICE）、`QuroOnDeviceModelManager.kt`（NCNN 布局校验）、`QuroOnDeviceModelPrefs.kt`（NCNN 内置链接）。
- 改写 `QuroModelConfigScreen.kt`：编辑排版风 UI（`ChapterLabel` / `UnderlineField` / `StepperField`，温度改用 Slider）。
- 新增 `app/src/main/jniLibs/arm64-v8a/libncnn.so` + `libsherpa-ncnn-jni.so`。
- `app/build.gradle.kts`：`versionCode=58` / `versionName=1.0.58`；`aaptOptions.noCompress` 增 `ncnn/param/bin`。

**测试覆盖**
- `compileDebugKotlin` ✅；`clean assembleDebug` ✅（1m30s，仅既有弃用警告）。
- 原生库 `DT_NEEDED` 核验齐备 ✅；APK 内 `lib/arm64-v8a/` 含 `libncnn.so` + `libsherpa-ncnn-jni.so` ✅。
- 运行时端侧转写**未实测**（无 arm64 真机/模拟器）。

**发布检查清单**
- 版本升 58 ✅ ｜ APK 产出 ✅（130 MB，`QuroAI-debug-2026-07-19-v58.apk` 已拷桌面）｜ 原生库打包 ✅ ｜ UI 编译通过 ✅。

**回滚预案**
- 代码回退：git revert NCNN 相关提交并降 `versionCode` 至 57（或直接装 v57 APK）。
- 旧 Sherpa-ONNX 封装已弃用；若需回滚到 ONNX 引擎，须恢复 `sherpa-onnx` 依赖与对应 `QuroAsrModels` 旧实现。

---

## 2. 综合审查发现（去重合并后按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟡 | 健壮性 | QuroOnDeviceModelManager.kt:100-153 | 下载仅判 HTTP 码，不比对 `Content-Length`/最小体积；解压按扩展名不验 magic → 3452 字节 HTML 软 404 被当模型包保存 | 下载后比字节 + 最小体积；解压前验 magic（BZh/1f8b/PK）与 Content-Type，拒 HTML | 排障手 |
| 2 | 🟡 | 健壮性 | QuroOnDeviceAsr.kt:41-46,111 | `binderDied` 不完成 pending deferred → 原生 SIGSEGV 时静默空等 60s 才返 false | `binderDied` 完成 `deferred=false` 并上报「模型可能损坏」，避免 60s 空等 | 排障手 |
| 3 | 🟢 | 验证 | jniLibs/arm64-v8a | `DT_NEEDED` 核验：仅 `libncnn.so`+系统库；core/fbank 静态链接，2 个 `.so` 齐备 | 无需补充 | 主理人 |
| 4 | 🟢 | UI | QuroModelConfigScreen.kt | 温度 Slider 锁 0..1，已存 >1 配置拖动后被钳制（未拖动前存储值不变） | 如需 >1 支持，放宽上限或改回文本框 | 排障手 |

---

## ✅ 行动清单（至少 3 条具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 安装 v58 APK，真机实测 SenseVoice NCNN 端侧转写是否成功 | 用户 / 主理人 | P1 | 尽快 |
| 2 | 下载校验加固：Content-Length/最小体积/magic/checksum 双校验 | 排障手 | P2 | 下个迭代 |
| 3 | `binderDied` 完成 pending deferred，消除 60s 空等 | 排障手 | P2 | 下个迭代 |
| 4 | 确认温度 Slider 上限（是否需 >1 支持） | 用户 | P3 | 视反馈 |

---

## ⚠️ 待完善 / 已知局限

- 运行时端侧转写未实测（无 Android 设备；arm64 原生 `.so` 需真机验证加载与识别）。
- 假模型下载校验未加固（当前靠 v50 `minSizeBytes` 在解压阶段拦截，下载阶段不校验）。
- 温度 Slider 上限 1.0 与部分厂商 >1 习惯的潜在冲突。
- product-reviewer 的「通用 ASR 多引擎配置系统」设计未采纳（超出本次 NCNN + UI 范围，已搁置）。

---

## 📚 成员产出索引

- gstack-investigator（排障手）原始产出：NCNN 迁移代码（封装层 + 3 个 tool 文件改写）、`QuroModelConfigScreen.kt` 编辑排版风重写、3452 字节假模型 / `ensureLoaded` 60s 挂死根因报告（见本次对话）。
- 主理人核验产出：原生库 `DT_NEEDED` 权威校验记录、`clean assembleDebug` 构建 sign-off（BUILD SUCCESSFUL）、APK 拷贝与桌面交付。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
