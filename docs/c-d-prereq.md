# 批次 C / D 实施前置文档（Prerequisites for Batch C & D）

> **文档定位**：本文是工程师实施「批次 C：语音 STT/TTS」与「批次 D：知识库 / RAG」的**唯一权威前置说明**。
> 所有选型均已拍板，**不存在"可以考虑 A 或 B"的选项**。照抄即可，遇到与本文冲突的旧文档/旧注释一律以本文为准。
>
> 架构：高见远　｜　适用工程：`QuroAI`（Android / Kotlin / Compose，abi 仅 `arm64-v8a`，风味 `full` / `fdroid`）

---

## 0. TL;DR — 一屏结论

| 项 | 结论 |
|---|---|
| sherpa-onnx 接入方式 | **预编译 vendor**（禁用 JitPack `com.github.k2-fsa:sherpa-onnx`），与现有 sherpa-ncnn 同构 |
| ORT 运行时 | **唯一一份**，由 `com.microsoft.onnxruntime:onnxruntime-android:1.28.0` 提供；**不拷** sherpa 自带的 `libonnxruntime.so` |
| 新增 native 模块 | **0 个**。embedding 用 ORT 的 Java/Kotlin API 纯 Kotlin 实现 |
| STT | 保持 Sherpa-NCNN 不变，默认锁 SenseVoice int8 |
| TTS 轻量档（**默认**） | `matcha-icefall-zh-baker` + `hifigan_v2.onnx` → **总下载 75.5 MiB** |
| TTS 高音质档 | `vits-melo-tts-zh_en` → 总下载 159.3 MiB，44.1 kHz，中英混读 |
| Embedding | `bge-small-zh-v1.5` ONNX int8，**512 维**（不是 768） |
| 新 `.so` 落点 | 只放 `app/src/full/jniLibs/arm64-v8a/`，`fdroid` 风味自动排除 |
| 最易踩的坑 | ① `fullImplementation` 访问器在本工程不可用，必须 `add("fullImplementation", ...)`　② `embedding_version` 缺失会让 RAG 静默失真（见 §7） |
| 单运行时可行性 | ✅ **已双向 ELF 实测**：sherpa JNI 的 `DT_NEEDED` = `libonnxruntime.so`，ORT 1.28.0 的 `DT_SONAME` = `libonnxruntime.so`，精确匹配（见 §3.1.1） |
| 体积代价 | APK +10~13 MiB，安装占用 +31.8 MiB（见 §3.1.2） |

---

## 1. 依赖落地

### 1.1 `gradle/libs.versions.toml`

在 `[versions]` 段**追加**（保持与现有条目同风格，放在 `shizuku` 之后即可）：

```toml
# 端侧 ONNX 推理运行时（TTS via sherpa-onnx JNI + Embedding via ORT Java API）
# 唯一 ORT 运行时来源；见 docs/c-d-prereq.md §3
onnxruntime = "1.28.0"
```

在 `[libraries]` 段**追加**（放在 shizuku 两条之后、"端侧 ASR 引擎 Sherpa-NCNN" 注释块之前）：

```toml
# 端侧 ONNX Runtime（Microsoft 官方 AAR，MIT）
# 作用有二：
#   1) 为预编译的 libsherpa-onnx-jni.so 提供它 DT_NEEDED 的 libonnxruntime.so
#   2) 为 Embedding（bge-small-zh-v1.5）提供 ai.onnxruntime.* Java/Kotlin API
# 注意：全工程只能有这一份 libonnxruntime.so，切勿再拷 sherpa 包内自带的同名文件。
onnxruntime-android = { group = "com.microsoft.onnxruntime", name = "onnxruntime-android", version.ref = "onnxruntime" }
```

同时**更新**已有的那段 Sherpa-NCNN 说明注释，在其后补一段：

```toml
# 端侧 TTS 引擎 Sherpa-ONNX：同样未使用版本目录条目（仅 native + Kotlin 源码）。
# 禁用 JitPack 的 com.github.k2-fsa:sherpa-onnx —— 构建期拉源码编译、不可复现，F-Droid 明令禁止。
# 集成方式：「Kotlin 封装源码拷入 app/src/full/java/com/k2fsa/sherpa/onnx/」+
#           「libsherpa-onnx-jni.so 放入 app/src/full/jniLibs/arm64-v8a/」。
# 其依赖的 libonnxruntime.so 由上面的 onnxruntime-android AAR 提供，不重复引入。
```

### 1.2 `app/build.gradle.kts`

#### ⚠️ 必读：`fullImplementation` 访问器在本工程**不可用**

`app/build.gradle.kts:137-140` 已有明确记录：`fullImplementation` 访问器曾因 kotlin-dsl 配置探针死锁而永不生成，工程已统一改用 `DependencyHandler.add()`。
**新增依赖必须沿用同一写法**，写 `fullImplementation(...)` 会导致脚本编译失败。

在 `dependencies { }` 内，紧跟现有的两条 `add("fullImplementation", project(...))` 之后追加：

```kotlin
    // 端侧 ONNX 运行时：仅 full 风味。
    // 同时服务于 ① sherpa-onnx TTS 的 JNI（提供 libonnxruntime.so）
    //           ② 知识库 embedding（提供 ai.onnxruntime.* Java API）
    // fdroid 风味不引入，因其不含 libsherpa-onnx-jni.so，且 ORT AAR 内为预编译二进制。
    add("fullImplementation", libs.onnxruntime.android)
```

> 若 `libs.onnxruntime.android` 访问器同样解析不出（与上面同一类死锁问题复发），退化写法：
> `add("fullImplementation", "com.microsoft.onnxruntime:onnxruntime-android:1.28.0")`

#### `packaging` 块：**不要**加 `pickFirsts`

现有 `packaging { jniLibs { useLegacyPackaging = true } }` 保持原样。

**明确结论：不配置 `pickFirsts`。** 理由：
`pickFirsts` 的存在意义是"同名 `.so` 冲突时随便挑一个"。本方案的核心正是**从源头消灭同名冲突**——只保留 Microsoft 一份 `libonnxruntime.so`。
一旦配了 `pickFirsts += "**/libonnxruntime.so"`，就等于给"误拷了 sherpa 自带 ORT"这个错误加了消音器：构建不再报错，但运行期可能挑到旧版本，直接触发 `OrtGetApiBase` 符号崩溃（sherpa-onnx Issue #3261 / #566 的真实成因）。

**因此反向要求：构建后必须有一次断言检查**，见 §3.2 步骤 2。

---

## 2. `.so` 与 Kotlin 封装的 vendor 步骤

### 2.1 现状基线（已核实，勿再猜）

```
app/src/main/jniLibs/arm64-v8a/          ← 空目录（历史遗留，无文件）
app/src/full/jniLibs/arm64-v8a/          ← 真正的预编译 .so 落点，现有 6 个：
    libncnn.so                4,581,184
    libsherpa-ncnn-jni.so       892,600
    libproot.so                 208,368
    libproot-loader.so           17,728
    libproot-loader32.so          1,560
    libtalloc.so                 45,072
app/src/main/java/com/k2fsa/sherpa/ncnn/ ← NCNN 的 Kotlin 封装（纯 JNI 声明，放在 main）
    FeatureConfig.kt / OfflineRecognizer.kt / OfflineStream.kt / WaveReader.kt
```

### 2.2 下载与解包

```bash
# 1) 下载 sherpa-onnx Android 预编译包（33.87 MiB，35,518,911 bytes）
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.18/sherpa-onnx-v1.12.18-android.tar.bz2
tar xvf sherpa-onnx-v1.12.18-android.tar.bz2
```

### 2.3 拷贝清单（**只拷这些，多一个都不行**）

| # | 源（解压包内） | 目标（工程内） | 说明 |
|---|---|---|---|
| 1 | `jniLibs/arm64-v8a/libsherpa-onnx-jni.so` | `app/src/full/jniLibs/arm64-v8a/libsherpa-onnx-jni.so` | **4,582,688 B = 4.37 MiB**，唯一要拷的 `.so` |
| 2 | `jniLibs/arm64-v8a/libonnxruntime.so` | **❌ 不拷** | 15.25 MiB，由 Microsoft AAR 取代，见 §3 |
| 3 | `jniLibs/arm64-v8a/libsherpa-onnx-c-api.so` | **❌ 不拷** | 4.10 MiB，供 C 调用方用，JNI 路径不需要 |
| 4 | `jniLibs/arm64-v8a/libsherpa-onnx-cxx-api.so` | **❌ 不拷** | 0.37 MiB，同上 |
| 5 | `jniLibs/armeabi-v7a/` `x86/` `x86_64/` | **❌ 不拷** | 工程 `abiFilters` 仅 arm64-v8a |
| 6 | `kotlin-api/Tts.kt` 等 Kotlin 封装 | `app/src/full/java/com/k2fsa/sherpa/onnx/` | 见 §2.4 |

> 包内 `jniLibs/arm64-v8a/` 共 5 个文件，**只拷第 1 个**。第 3、4 项名字与第 1 项高度相似，是最容易误拷的两个——拷进去会白白增加 4.5 MiB APK 体积且毫无作用。

> **为什么 Kotlin 封装放 `src/full/` 而不是像 NCNN 那样放 `src/main/`？**
> NCNN 封装是纯 JNI 声明，不引用任何仅 full 才有的类型，所以可以待在 main（靠 `BuildConfig.FLAVOR` 守卫）。
> 本批次新增的 embedding 代码要 `import ai.onnxruntime.*`，而该包只在 `fullImplementation` 下存在——若放 main，`fdroid` 风味会直接**编译失败**（不是运行期失败）。
> 为避免"一半在 main 一半在 full"的割裂，**sherpa-onnx 相关 Kotlin 一律放 `src/full/`**。

### 2.4 Kotlin 封装文件

从解压包的 `kotlin-api/` 目录拷入 `app/src/full/java/com/k2fsa/sherpa/onnx/`，**至少**需要：

- `Tts.kt`（`OfflineTts` / `OfflineTtsConfig` / `OfflineTtsModelConfig` / `OfflineTtsVitsModelConfig` / `OfflineTtsMatchaModelConfig` / `GeneratedAudio`）
- `WaveReader.kt`（若 `Tts.kt` 引用）

**必做改造**：包内 `Tts.kt` 的 `companion object { init { System.loadLibrary("sherpa-onnx-jni") } }` 需要按本工程既有范式加风味守卫（与 `com/k2fsa/sherpa/ncnn/OfflineRecognizer.kt:114-117` 完全一致）：

```kotlin
companion object {
    init {
        // F-Droid 风味不含端侧 TTS 原生库，跳过加载避免 UnsatisfiedLinkError
        if (com.ai.assistance.quro.BuildConfig.FLAVOR != "fdroid") {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
```

> `libsherpa-onnx-jni.so` 的 `DT_NEEDED` 里有 `libonnxruntime.so`，Android 动态链接器会在 `System.loadLibrary("sherpa-onnx-jni")` 时**自动**把它一并加载，**无需**手写 `System.loadLibrary("onnxruntime")`。
> 反之，如果 embedding 侧先 `OrtEnvironment.getEnvironment()`，ORT 的 `libonnxruntime4j_jni.so` 也会把同一份 `libonnxruntime.so` 加载进来——同进程只会有一个映射，这正是"单运行时"的目标状态。

---

## 3. ORT 单运行时方案：风险、验证与回退

### 3.1 风险陈述（本方案唯一的不确定点）

`libsherpa-onnx-jni.so` 是 k2-fsa 用**他们自己那份 ORT** 编译链接出来的。我们把它的运行时换成 Microsoft 官方 AAR 里的 `libonnxruntime.so`。这条链路成立需要两个条件同时满足：

1. **SONAME 匹配**：sherpa JNI 的 `DT_NEEDED` 写的是 `libonnxruntime.so`，Microsoft 提供的 `.so` 的 `DT_SONAME` 也必须恰好是 `libonnxruntime.so`（而不是带版本后缀）。
2. **C API 向后兼容**：sherpa JNI 依赖的 ORT C API 版本 ≤ Microsoft 运行时提供的版本。ORT 官方承诺 C API **向后兼容**（新运行时支持旧 API version），所以"旧 JNI + 新运行时"方向是安全的；反方向（新 JNI + 旧运行时）才会炸。

### 3.1.1 ✅ 已实测：条件 ① 与 ② 均成立，方案确认可行

> 架构侧已对 **sherpa-onnx v1.12.18 的 `libsherpa-onnx-jni.so`** 与 **ORT 1.28.0 AAR** 双方做过 ELF 解析实测（非推测）。
> **两端 SONAME 精确匹配，单运行时方案成立。** 工程师无需重复这一步，直接照 §3.2 做冒烟验证即可。

#### 核心对照结论

```
sherpa libsherpa-onnx-jni.so  DT_NEEDED  ─┬─> "libonnxruntime.so"
                                          │        ║  精确匹配 ✅
Microsoft libonnxruntime.so   DT_SONAME  ─┴─> "libonnxruntime.so"
```

**`onnxruntime-android-1.28.0.aar` 内容清单**（AAR 总 45,634,470 B）：

| 路径 | 体积 |
|---|---|
| `classes.jar` | 121,402 B |
| `jni/arm64-v8a/libonnxruntime.so` | **28,637,280 B = 27.31 MiB** |
| `jni/arm64-v8a/libonnxruntime4j_jni.so` | 111,648 B |
| `jni/armeabi-v7a/…` `jni/x86/…` `jni/x86_64/…` | 工程 `abiFilters` 仅 arm64-v8a，**不会进包** |

**`jni/arm64-v8a/libonnxruntime.so` 的 ELF 头**：

```
machine    : 0xb7 (aarch64) ✅
DT_SONAME  : libonnxruntime.so          ← ✅ 纯名字，无版本后缀
DT_NEEDED  : [libdl.so, liblog.so, libandroid.so, libm.so, libc.so]
exports OrtGetApiBase                                  : True ✅
exports OrtSessionOptionsAppendExecutionProvider_Nnapi : True ✅
```

**`jni/arm64-v8a/libonnxruntime4j_jni.so` 的 ELF 头**：

```
DT_NEEDED  : [libonnxruntime.so, libm.so, libdl.so, libc.so]
                ↑ Microsoft 自己的 Java 绑定层也正是用「裸 libonnxruntime.so」这个名字去链接的
```

**`sherpa-onnx-v1.12.18-android.tar.bz2` 内 `jniLibs/arm64-v8a/` 实测清单**：

| 文件 | 体积 | 是否拷入工程 |
|---|---|---|
| `libsherpa-onnx-jni.so` | **4,582,688 B = 4.37 MiB** | ✅ **只拷这一个** |
| `libonnxruntime.so` | 15,988,232 B = 15.25 MiB | ❌ 由 Microsoft AAR 取代 |
| `libsherpa-onnx-c-api.so` | 4,304,688 B | ❌ 供 C 语言调用方用，JNI 路径用不到 |
| `libsherpa-onnx-cxx-api.so` | 390,616 B | ❌ 同上 |
| `README.md` | 403 B | ❌ |

> ⚠️ **勘误**：前序调研称 `libsherpa-onnx-jni.so` 约 3.7 MB，**实测为 4.37 MiB（4,582,688 B）**。以本表为准。
> 另注意包内还有两个 `libsherpa-onnx-*-api.so`，名字很像但**不能拷**——它们是给 C/C++ 调用方的，拷进去只是白白增加 4.7 MiB APK 体积。

**`libsherpa-onnx-jni.so` 的 ELF 头**：

```
machine    : 0xb7 (aarch64) ✅
DT_SONAME  : libsherpa-onnx-jni.so
DT_NEEDED  : [libandroid.so, liblog.so, libonnxruntime.so, libm.so, libdl.so, libc.so]
                                        ↑↑↑ 就是这一项，与 MS 的 DT_SONAME 精确一致 ✅
exports Java_com_k2fsa_sherpa_onnx_OfflineTts_generateImpl : True ✅
```

**结论**：

1. **条件 ① 成立**：sherpa JNI 找的是裸名 `libonnxruntime.so`，Microsoft 提供的 `DT_SONAME` 恰为 `libonnxruntime.so`（**没有** `.so.1.28.0` 之类版本后缀）→ Android 动态链接器可正常解析。
2. **条件 ② 成立**：`OrtGetApiBase` 正常导出 → Issue #3261 / #566 那类"符号找不到"的直接成因已排除。
3. **无 C++ ABI 冲突**：`libsherpa-onnx-jni.so` 的 `DT_NEEDED` 里**没有** `libc++_shared.so`（静态链接了 libc++），Microsoft 的 `libonnxruntime.so` 同样没有。两者不会因 STL 版本打架——这是另一个常见的隐蔽崩溃源，此处已排除。
4. `libonnxruntime4j_jni.so` 依赖同名 `libonnxruntime.so`，说明 Microsoft 官方 Java 通道与 sherpa JNI 通道**会共用同一份映射**——正是"单运行时"的目标状态，不会各自加载一份。
5. 导出符号确认了 JNI 方法名前缀为 `Java_com_k2fsa_sherpa_onnx_...`，因此 **Kotlin 封装的包名必须严格是 `com.k2fsa.sherpa.onnx`**，落点 `app/src/full/java/com/k2fsa/sherpa/onnx/` 正确，改包名会导致 `UnsatisfiedLinkError`。
6. NNAPI EP 符号存在（当前先用 `provider = "cpu"`，后续若要试 NNAPI 提速，运行时具备条件）。

**唯一残余风险**：ORT **C API 版本号**兼容性（`ORT_API_VERSION`）。这属于运行期行为，静态分析看不出来，必须靠 §3.2 步骤 2 的冒烟测试覆盖。理论上 ORT 承诺 C API 向后兼容，"旧 JNI + 新运行时"方向安全。

若想在 vendor 完 `.so` 后自行复核（30 秒）：

```bash
# NDK 自带 llvm-readelf；路径按本机 NDK 版本调整
$ANDROID_HOME/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf -d \
  app/src/full/jniLibs/arm64-v8a/libsherpa-onnx-jni.so | grep -E "NEEDED|SONAME"
```

期望看到 `(NEEDED) Shared library: [libonnxruntime.so]`。

### 3.1.2 ⚠️ 顺带确认的体积影响（需要在 PR 描述里说明）

引入 ORT AAR 会让 `full` 风味**安装后**的原生库体积增加：

```
libonnxruntime.so        27.31 MiB   （Microsoft AAR）
libonnxruntime4j_jni.so   0.11 MiB   （Microsoft AAR）
libsherpa-onnx-jni.so     4.37 MiB   （vendor 的）
                         ─────────
                         ≈ 31.79 MiB
```

工程 `packaging { jniLibs { useLegacyPackaging = true } }`（即 `extractNativeLibs=true`），因此 `.so` 在 APK 内是**压缩存储**、安装时解压到磁盘：

- **APK 体积**增长约 10–13 MiB（压缩后）
- **安装占用**增长约 31 MiB（解压后）

这是本方案的**已知成本**，属于可接受范围（换来 0 个新 native 模块 + 单运行时的确定性），但必须在 PR 里写明，避免 review 时被当成意外回归。
`fdroid` 风味完全不受影响（不含任何上述 `.so`）。



### 3.2 验证步骤（**必须在写业务代码之前跑完**）

**步骤 1 — 构建期：确认 APK 里只有一份 ORT**

```bash
./gradlew :app:assembleFullDebug
cd app/build/outputs/apk/full/debug
unzip -l app-full-debug.apk | grep -E "onnxruntime|sherpa"
```

期望输出**恰好三行** `.so`：

```
lib/arm64-v8a/libonnxruntime.so          ← 来自 Microsoft AAR
lib/arm64-v8a/libonnxruntime4j_jni.so    ← 来自 Microsoft AAR（Java 绑定层）
lib/arm64-v8a/libsherpa-onnx-jni.so      ← 我们 vendor 的
```

若出现两条 `libonnxruntime.so` 或构建报 `duplicate file` → 说明误拷了 sherpa 自带的那份，删掉 `app/src/full/jniLibs/arm64-v8a/libonnxruntime.so` 重来。**不要**用 `pickFirsts` 掩盖。

**步骤 2 — 运行期冒烟测试：合成一句中文**

在 `full` 风味 debug 包里挂一个临时入口（或写成 androidTest），跑：

```kotlin
// 前提：matcha 模型已下载到 filesDir/ondevice_model/matcha-icefall-zh-baker/
val dir = File(context.filesDir, "ondevice_model/matcha-icefall-zh-baker").absolutePath
val voc = File(context.filesDir, "ondevice_model/matcha-icefall-zh-baker/hifigan_v2.onnx").absolutePath
val cfg = OfflineTtsConfig(
    model = OfflineTtsModelConfig(
        matcha = OfflineTtsMatchaModelConfig(
            acousticModel = "$dir/model-steps-3.onnx",
            vocoder       = voc,
            lexicon       = "$dir/lexicon.txt",
            tokens        = "$dir/tokens.txt",
            dictDir       = "$dir/dict",
        ),
        numThreads = 2,
        debug = true,
        provider = "cpu",
    ),
    ruleFsts = "$dir/phone.fst,$dir/date.fst,$dir/number.fst",
    maxNumSentences = 1,
)
val tts = OfflineTts(config = cfg)          // ← 崩溃点 A：初始化
val audio = tts.generate("你好，这是端侧语音合成测试。今天是二零二五年。", sid = 0, speed = 1.0f)
Log.i("TTSProbe", "sampleRate=${audio.sampleRate} samples=${audio.samples.size}")
tts.release()
```

**判定标准**：

| 现象 | 结论 |
|---|---|
| 正常返回，`sampleRate == 22050`，`samples.size > 0` | ✅ 方案成立，继续 |
| `UnsatisfiedLinkError: dlopen failed: library "libonnxruntime.so" not found` | SONAME 不匹配 → 走 §3.3 回退 |
| `java.lang.UnsatisfiedLinkError: ... undefined symbol: OrtGetApiBase` | ORT 版本/符号不兼容 → 先降 ORT 版本重试（见下），仍失败则走 §3.3 |
| native crash（SIGSEGV / `abort`），logcat 有 `onnxruntime` 帧 | API 版本不兼容 → 先降版本重试，仍失败走 §3.3 |

**降版本重试（回退前的低成本尝试）**：把 `libs.versions.toml` 的 `onnxruntime` 依次改为 `1.27.0` → `1.23.2` → `1.17.3` 各试一次。
sherpa-onnx 长期基于 ORT 1.17.x 构建，`1.17.3` 是与之最贴近的版本，成功率最高。**只有这三档都失败，才启动 §3.3。**

**步骤 3 — Embedding 侧同样冒烟**

```kotlin
val env = OrtEnvironment.getEnvironment()
Log.i("ORTProbe", "ORT version = ${OrtEnvironment.getVersion()}")
```

必须在**步骤 2 已经成功之后、同一个进程内**再跑一次，确认两条链路共用一份运行时不打架。

### 3.3 回退预案（Plan B：sherpa 自带 ORT 作唯一运行时 + embedding 走自研 JNI）

> 触发条件：§3.2 步骤 2 在 ORT `1.28.0 / 1.27.0 / 1.23.2 / 1.17.3` 四个版本上**全部**失败。
> 下面每一步都可直接照做，**不需要回来问架构**。

**Plan B 的取舍**：保端侧 TTS（用户可感知的功能），牺牲 embedding 的实现便利性。因为 TTS 依赖的是二进制兼容性（无法绕过），而 embedding 的推理路径我们有替代方案。

> **实测补充**：sherpa 包内自带的 `jniLibs/arm64-v8a/libonnxruntime.so` 为 **15,988,232 B = 15.25 MiB**，
> 比 Microsoft 版的 27.31 MiB **小 12 MiB**（k2-fsa 用裁剪配置编译）。
> 因此 Plan B 在**体积上反而更优**：`15.25 + 4.37 + ~0.3(libquro_embed) ≈ 19.9 MiB`，比 Plan A 的 31.79 MiB 少约 37%。
> 但这**不构成优先选 Plan B 的理由**——Plan B 要多维护一套 C++/JNI 模块（工程已有 mnn/llama/ncnn 三套 native，第四套的长期成本远高于 12 MiB 体积收益）。**Plan A 仍是首选，Plan B 仅在验证失败时启用。**

**B-1. 恢复 sherpa 自带的 ORT**

```bash
cp sherpa-onnx-v1.12.18-android/jniLibs/arm64-v8a/libonnxruntime.so \
   app/src/full/jniLibs/arm64-v8a/
```

**B-2. 移除 Microsoft AAR**

| 文件 | 改动 |
|---|---|
| `gradle/libs.versions.toml` | 删 `[versions]` 的 `onnxruntime = "1.28.0"`；删 `[libraries]` 的 `onnxruntime-android` 条目 |
| `app/build.gradle.kts` | 删 `add("fullImplementation", libs.onnxruntime.android)` |

**B-3. Embedding 改走自研 JNI**

Microsoft AAR 一走，`ai.onnxruntime.*` Java API 就没了，但 `libonnxruntime.so` 还在（sherpa 那份，只有 C API，没有 Java 绑定层 `libonnxruntime4j_jni.so`）。因此必须自己写一层 JNI 桥。

需要新增/改动的文件：

```
app/src/main/cpp/CMakeLists.txt                          ← 改：新增 quro_embed target
app/src/main/cpp/embed/quro_embed.cpp                    ← 新增：JNI 实现（~250 行）
app/src/main/cpp/embed/onnxruntime_c_api.h               ← 新增：从 sherpa 包 headers/ 拷入
app/src/full/java/com/ai/assistance/quro/rag/QuroEmbedNative.kt  ← 新增：external fun 声明
app/src/full/java/com/ai/assistance/quro/rag/QuroEmbedder.kt     ← 改：推理实现从 ORT Java API 换成 QuroEmbedNative
```

`quro_embed.cpp` 要实现的 4 个 native 方法（签名固定，Kotlin 侧照此声明）：

```kotlin
object QuroEmbedNative {
    init { if (BuildConfig.FLAVOR != "fdroid") System.loadLibrary("quro_embed") }
    external fun createSession(modelPath: String, numThreads: Int): Long
    external fun releaseSession(handle: Long)
    /** ids/mask/typeIds 长度均为 seqLen；返回 float[hiddenSize] 的 CLS 向量（未归一化） */
    external fun runCls(handle: Long, ids: LongArray, mask: LongArray, typeIds: LongArray, seqLen: Int): FloatArray
    external fun hiddenSize(handle: Long): Int
}
```

C++ 侧要点：`OrtGetApiBase()->GetApi(ORT_API_VERSION)` 拿 API 表 → `CreateEnv` / `CreateSessionOptions` / `SetIntraOpNumThreads` / `CreateSession` → 三个 `CreateTensorWithDataAsOrtValue`（`ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64`，shape `{1, seqLen}`）→ `Run` → 取 output 0，shape `{1, seqLen, H}`，拷 `[0, 0, :]` 即 CLS → 释放全部 `OrtValue`。
CMake 侧 `target_link_libraries(quro_embed ${CMAKE_SOURCE_DIR}/../../full/jniLibs/${ANDROID_ABI}/libonnxruntime.so log)`。

**B-4. F-Droid metadata 同步**

`app/src/full/jniLibs/arm64-v8a/` 多了 `libonnxruntime.so`，`scandelete` 路径不变（已覆盖整个目录），**无需改 metadata**。但要更新 `F-DROID-SUBMISSION.md` 的预编译 `.so` 清单（6 个 → 8 个）。

**B-5. 回退后的验证**：重跑 §3.2 步骤 1（此时期望 APK 内只有 `libonnxruntime.so` + `libsherpa-onnx-jni.so` + `libquro_embed.so`，**没有** `libonnxruntime4j_jni.so`）与步骤 2。

---

## 4. 四个模型的 Spec 表

> 所有 URL 均已实测可达，体积为 **HTTP `Content-Length` 实测字节数**，非文档估算。

### 4.1 STT：SenseVoice（Sherpa-NCNN，保持不变）

| 字段 | 值 |
|---|---|
| id | `sherpa-ncnn-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17` |
| kind | `ASR_NCNN` |
| 下载 | 沿用 `QuroAsrModels.kt:174-203` `AsrModelCatalog.BUILTIN` 中既有条目 |
| 压缩包 | ≈ 95–100 MB |
| 解压后 | `model.ncnn.bin` 222 MB + `model.ncnn.param` + `tokens.txt` |
| 许可 | Apache-2.0（FunAudioLLM/SenseVoice，权重包内带 `LICENSE` 文件；**无任非商业限制**，全链路见 §9.4.8） |
| 本批次改动 | **仅三件事**：① 设为默认选中项；② UI 标注体积/峰值内存/推荐机型；③ 低内存机（`ActivityManager.MemoryInfo.totalMem < 4 GB`）隐藏大变体 |

> ⚠️ 用户反馈"模型不适合手机"的**真实根因是 `AsrDeviceCompat` 的 ABI 限制（仅 arm64-v8a）**，不是模型选型。非 arm64 设备 `System.loadLibrary` 抛 `UnsatisfiedLinkError`，模型永远加载不了（`QuroAsrModels.kt:44-48` 已有注释）。UI 上要把这条明确告知，不要让用户以为换模型能解决。

### 4.2 TTS 轻量档（**默认**）：matcha-icefall-zh-baker + hifigan_v2

| 字段 | 值 |
|---|---|
| id | `matcha-icefall-zh-baker` |
| kind | `TTS_SHERPA_ONNX_MATCHA` |
| 文件 1（声学模型） | `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/matcha-icefall-zh-baker.tar.bz2` |
| 文件 1 体积 | **75,463,442 B = 71.97 MiB** |
| 文件 2（声码器） | `https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/hifigan_v2.onnx` |
| 文件 2 体积 | **3,749,714 B = 3.58 MiB** |
| **总下载** | **79,213,156 B ≈ 75.5 MiB** |
| 解压后磁盘 | ≈ 78 MiB |
| 采样率 | **22050 Hz** |
| 说话人 | 1（女声） |
| 中英混读 | **❌ 仅中文** |
| RTF（RPi4 B, vocos, 1/2/3/4 线程） | 0.892 / **0.536** / 0.432 / 0.391（换 hifigan_v2 后更快） |
| 许可 | 训练代码 icefall = Apache-2.0；**数据集标贝 Baker 开源集 = 仅限非商业使用** |

**解压后目录结构**（`filesDir/ondevice_model/matcha-icefall-zh-baker/`）：

```
matcha-icefall-zh-baker/
├── model-steps-3.onnx      72 MB   ← 声学模型
├── lexicon.txt            1.3 MB
├── tokens.txt              19 KB
├── date.fst                58 KB
├── number.fst              63 KB
├── phone.fst               87 KB
├── dict/                           ← jieba 词典目录
├── README.md              370 B
└── hifigan_v2.onnx        3.6 MB   ← 单独下载，直接落在同目录（不在 tar 包内！）
```

**`expectedFiles`**：`["model-steps-3.onnx", "lexicon.txt", "tokens.txt", "phone.fst", "date.fst", "number.fst", "dict", "hifigan_v2.onnx"]`

**sherpa `OfflineTts` 完整加载参数（matcha 分支，照抄）**：

```kotlin
OfflineTtsConfig(
    model = OfflineTtsModelConfig(
        matcha = OfflineTtsMatchaModelConfig(
            acousticModel = "$dir/model-steps-3.onnx",   // 必填
            vocoder       = "$dir/hifigan_v2.onnx",      // 必填，缺了直接 crash
            lexicon       = "$dir/lexicon.txt",          // 中文模型必填（英文 ljspeech 版才用 dataDir）
            tokens        = "$dir/tokens.txt",           // 必填
            dictDir       = "$dir/dict",                 // 中文分词词典
            dataDir       = "",                          // ⚠️ 中文 matcha 留空！dataDir 是 espeak-ng 用的
            noiseScale    = 1.0f,
            lengthScale   = 1.0f,
        ),
        numThreads = 2,
        debug      = false,
        provider   = "cpu",
    ),
    // 文本规整（读数字/日期/多音字），顺序不能乱
    ruleFsts        = "$dir/phone.fst,$dir/date.fst,$dir/number.fst",
    maxNumSentences = 1,
)
```

> ⚠️ **`dataDir` vs `dictDir` 是最常见的写错点**：
> `dataDir` 给的是 **espeak-ng-data**（英文 matcha 模型用），中文 baker 模型**没有**这个目录，必须留空字符串；
> `dictDir` 给的才是中文 **jieba 分词词典**。两者写反 → 初始化失败或读音全错。

**为什么 vocoder 选 `hifigan_v2` 而不是官方示例的 `vocos-22khz-univ`？**

| vocoder | 体积 | 说明 |
|---|---|---|
| `vocos-22khz-univ.onnx` | 53,884,024 B = 51.4 MiB | 官方示例用；音质最好，但**一个声码器就顶掉半个声学模型** |
| `hifigan_v1.onnx` | 55,750,124 B = 53.2 MiB | 更大，没有理由选 |
| **`hifigan_v2.onnx`** | **3,749,714 B = 3.58 MiB** | ✅ **本项目选定**。HiFi-GAN V2 仅 0.92M 参数，原论文 MOS 4.23（V1 为 4.36），差距在手机扬声器上基本不可闻 |
| `hifigan_v3.onnx` | 5,863,729 B = 5.59 MiB | 比 V2 更大但 MOS 更低（4.05），无优势 |

选 `hifigan_v2` 让总下载从 123.4 MiB 降到 **75.5 MiB**，直接砍掉 39%，这才是"轻量档"名副其实的关键。**此项已拍板，不给用户提供切换声码器的选项。**

#### 选型依据：为什么是 matcha 而不是其他候选

全部候选实测对比（体积为 HTTP `Content-Length` 实测值，RTF 为官方 Raspberry Pi 4B 数据）：

| 候选 | 总下载 | 采样率 | 说话人 | RTF@2线程 | 许可可追溯性 | 判定 |
|---|---|---|---|---|---|---|
| **matcha-icefall-zh-baker + hifigan_v2** | **75.5 MiB** | **22050** | 1 女 | **0.536** | icefall Apache-2.0；数据 Baker 仅限非商业（§9.4） | ✅ **选定** |
| sherpa-onnx-vits-zh-ll | 113.3 MiB | 16000 | 5 | 2.494 | ❌ 社区微调，HF 仓库**无 License 声明** | ❌ |
| vits-zh-hf-fanchen-C | 113.8 MiB | 16000 | 187 | 2.451 | ❌ 同上 | ❌ |
| vits-zh-hf-theresa | 115.0 MiB | 22050 | 804 | 3.448 | ❌ 同上 | ❌ |
| vits-zh-hf-eula | ≈115 MiB | 22050 | 804 | 3.473 | ❌ 同上 | ❌ |
| vits-icefall-zh-aishell3 | 30.1 MiB | **8000** | 174 | 0.220 | icefall Apache-2.0 | ❌ 8 kHz 电话音质，**已核实属实**，不可用 |
| （对照）vits-melo-tts-zh_en | 159.3 MiB | 44100 | 1 | 3.877 | MIT | 留作高音质档 |

**matcha 胜出的四个理由**：

1. **体积**：75.5 MiB，比次优候选（vits-zh-ll 113.3 MiB）**小 33%**，是唯一真正落在"70–120 MiB"目标区间下沿的方案。相对高音质档 159.3 MiB 形成 **2.1 倍**的清晰分层——如果选 vits-zh-ll，两档只差 1.4 倍，"轻量档"这个概念就立不住了。
2. **速度（决定性因素）**：RTF 0.536 vs vits 系的 2.45–3.47，**快 4.6–6.5 倍**。
   RPi4（Cortex-A72 @1.5 GHz）到中端手机大致有 3–5 倍算力差，据此外推：matcha 在手机上 RTF ≈ 0.11–0.18（说 10 秒话算 1–2 秒），而 vits 系 ≈ 0.5–0.8，低端机上会 **>1.0（比实时还慢）**，语音助手场景直接不可用。
   Matcha 是 flow-matching 架构、仅 3 步采样，本质上就比 VITS 快一个量级。
3. **音质**：22050 Hz，与 theresa/eula 持平，优于 vits-zh-ll / fanchen-C 的 16000 Hz。
4. **许可条款明确可追溯**：`vits-zh-hf-*` 与 `vits-zh-ll` 全部是社区用 `VITS-fast-fine-tuning` 微调的产物，HF 仓库 README **只有一行出处、无任何 License 声明**，权重来源不可追溯——对一个要进 F-Droid 的开源项目而言，这比"非商业限制"更糟（后者至少条款明确、可在 UI 上如实告知）。（注：「可追溯」指条款明确，**不等于可商用**——matcha 的数据集仍仅限非商业，见 §9.4。）

**已知代价（必须正视，不是被忽略）**：

- **仅中文**，不支持中英混读 → 用 §4.4 的路由规则处理，英文内容交给高音质档或降级链
- **数据集仅限非商业使用** → 用 §9.4 的双重标注处理（模型卡 + `NOTICE`）

这两点均有明确的工程对策，而 vits 系的"慢到不可用"与"许可不可追溯"**没有对策**。故选定 matcha。

### 4.3 TTS 高音质档：vits-melo-tts-zh_en

| 字段 | 值 |
|---|---|
| id | `vits-melo-tts-zh_en` |
| kind | `TTS_SHERPA_ONNX_VITS` |
| 下载 | `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-zh_en.tar.bz2` |
| 压缩包 | **167,006,755 B = 159.3 MiB** |
| 解压后磁盘 | ≈ 170 MiB |
| 采样率 | **44100 Hz** |
| 说话人 | 1 |
| 中英混读 | **✅ 支持** |
| RTF（RPi4 B, 1/2/3/4 线程） | 6.727 / 3.877 / 2.914 / 2.518 |
| 许可 | MeloTTS 上游 MIT；sherpa-onnx 转换/推理工具链 Apache-2.0。**无非商业限制**，与轻量档不同 |

**解压后目录结构**：

```
vits-melo-tts-zh_en/
├── model.onnx       163 MB
├── lexicon.txt      6.5 MB
├── tokens.txt        655 B
├── date.fst           58 KB
├── number.fst         63 KB
├── phone.fst          87 KB
└── dict/
```

**`expectedFiles`**：`["model.onnx", "lexicon.txt", "tokens.txt", "dict", "phone.fst", "date.fst", "number.fst"]`

**加载参数（vits 分支）**：

```kotlin
OfflineTtsConfig(
    model = OfflineTtsModelConfig(
        vits = OfflineTtsVitsModelConfig(
            model   = "$dir/model.onnx",
            lexicon = "$dir/lexicon.txt",
            tokens  = "$dir/tokens.txt",
            dataDir = "",                 // ⚠️ 同样留空
            dictDir = "$dir/dict",
        ),
        numThreads = 2, debug = false, provider = "cpu",
    ),
    ruleFsts        = "$dir/phone.fst,$dir/date.fst,$dir/number.fst",
    maxNumSentences = 1,
)
```

### 4.4 两档 TTS 的路由规则（因为轻量档不支持英文）

轻量档是中文单语模型，`tokens.txt` 只有拼音 token，英文单词无法映射会被静默丢弃（读出来缺字）。因此需要一条明确的路由规则：

```kotlin
/** 拉丁字母占比 > 15% 且连续英文单词长度 ≥ 4 时，判定为"含实质英文内容" */
private fun needsBilingual(text: String): Boolean {
    if (text.isEmpty()) return false
    val latin = text.count { it in 'A'..'Z' || it in 'a'..'z' }
    val hasLongWord = Regex("[A-Za-z]{4,}").containsMatchIn(text)
    return hasLongWord && latin * 100 / text.length > 15
}
```

- `needsBilingual == true` 且高音质档**已下载** → 用 melo
- `needsBilingual == true` 且高音质档**未下载** → 按 §8 降级链走云端 / 系统 TTS，并在设置页提示"当前文本含较多英文，建议下载高音质档"
- 其余情况 → 用轻量档 matcha

### 4.5 Embedding：bge-small-zh-v1.5 ONNX int8

| 字段 | 值 |
|---|---|
| id | `bge-small-zh-v1.5-onnx-int8` |
| kind | `EMBED_ONNX` |
| 主源 | ModelScope `Maiteka/bge-small-zh-v1.5-onnx` |
| URL 模板 | `https://modelscope.cn/models/Maiteka/bge-small-zh-v1.5-onnx/resolve/master/<file>` |
| 文件（逐个拉，非压缩包） | `model_qint8.onnx`、`vocab.txt`、`config.json`、`tokenizer_config.json`、`special_tokens_map.json` |
| **向量维度** | **512**（不是 768！small 版就是 512） |
| 结构 | BERT / WordPiece，vocab **21128** 行，maxLen **512** |
| 许可 | MIT |

**推理输入输出（照抄）**：

- 输入 3 个张量，dtype 均为 **int64**，shape 均为 `{1, seqLen}`：`input_ids`、`attention_mask`、`token_type_ids`
- 输出 0 shape `{1, seqLen, 512}` → 取 `[0, 0, :]`（**CLS 池化**，不是 mean pooling）
- 再做 **L2 归一化** → 最终 512 维单位向量

```kotlin
// ORT Java/Kotlin API（Plan A 路径）
val env = OrtEnvironment.getEnvironment()
val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
val session = env.createSession(modelPath, opts)

val shape = longArrayOf(1, seqLen.toLong())
val inputs = mapOf(
    "input_ids"      to OnnxTensor.createTensor(env, LongBuffer.wrap(ids),   shape),
    "attention_mask" to OnnxTensor.createTensor(env, LongBuffer.wrap(mask),  shape),
    "token_type_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(types), shape),
)
session.run(inputs).use { res ->
    @Suppress("UNCHECKED_CAST")
    val out = (res[0].value as Array<Array<FloatArray>>)   // [1][seqLen][512]
    val cls = out[0][0]                                    // CLS
    var n = 0f; for (v in cls) n += v * v
    n = kotlin.math.sqrt(n).coerceAtLeast(1e-12f)
    return FloatArray(512) { cls[it] / n }
}
inputs.values.forEach { it.close() }
```

> **注意**：`session.run()` 返回的 `OrtSession.Result` 与所有 `OnnxTensor` **必须 close**，否则 native 内存泄漏，反复检索会 OOM。上面用 `.use { }` + 显式 `forEach { close() }` 覆盖两侧。

**`expectedFiles`**：`["model_qint8.onnx", "vocab.txt", "config.json", "tokenizer_config.json", "special_tokens_map.json"]`

---

## 5. `QuroModelDownloadManager` 设计

### 5.1 现状与缺口

现有 `app/src/main/java/com/ai/assistance/quro/core/tools/QuroOnDeviceModelManager.kt`（`object` 单例）：

- `downloadAndDeploy(ctx, AsrModelSpec, onProgress, onState): Boolean`
- 下载到 `cacheDir/ondevice_dl/` → 解压到 `filesDir/ondevice_model/<modelName>/` → **NCNN 布局校验（硬编码）** → 写 `QuroOnDeviceModelPrefs`
- `downloadFile` 用 `HttpURLConnection`（连接超时 15s、跟随重定向、每 256 KB 回调），支持 zip / tar.gz / tar.bz2（Apache Commons Compress），有路径穿越防护

**四个缺口**：① 无断点续传　② 无 sha256 校验　③ 无 WiFi-only　④ 校验器写死 NCNN 布局，装不下 TTS / Embedding。

### 5.2 目标设计

**落点**：`app/src/main/java/com/ai/assistance/quro/core/tools/QuroModelDownloadManager.kt`（放 `main`，因为它不 import 任何 ORT 类型，`fdroid` 风味也要能编译；实际下载入口由上层按风味守卫）

**保留** `QuroOnDeviceModelManager` 不动（ASR 现有调用方不改），新管理器**内部复用**其 `downloadFile` 的成熟部分（重定向、进度回调、解压、路径穿越防护），仅新增续传 / 校验 / 网络策略。

```kotlin
/** 模型种类。决定解压后的布局校验规则与部署路径。 */
enum class ModelKind {
    ASR_NCNN,                  // Sherpa-NCNN ASR（现有）
    TTS_SHERPA_ONNX_VITS,      // sherpa-onnx VITS 单文件模型（melo）
    TTS_SHERPA_ONNX_MATCHA,    // sherpa-onnx Matcha（声学模型 + 独立 vocoder，双文件）
    EMBED_ONNX,                // BERT 类 embedding（多个裸文件，无压缩包）
}

/** 单个待下载文件。一个模型可能由多个来源不同的文件组成。 */
data class ModelFile(
    val url: String,
    /** 落到模型目录内的相对路径；压缩包填 null（解压后由包内结构决定） */
    val targetRelPath: String? = null,
    /** true = tar.bz2 / tar.gz / zip，需要解压；false = 裸文件直接落盘 */
    val archive: Boolean = false,
    val expectedSha256: String? = null,
    val minSizeBytes: Long = 0L,
)

/** 通用模型描述。取代原来只服务 ASR 的 AsrModelSpec。 */
data class ModelSpec(
    val id: String,                       // 唯一 id，同时用作目录名
    val kind: ModelKind,
    val displayName: String,
    val files: List<ModelFile>,           // 多文件模型（matcha / embedding）靠它
    /** 部署完成后必须存在的文件/目录名（相对模型根目录），用于布局校验 */
    val expectedFiles: List<String>,
    val totalDownloadBytes: Long,         // 用于 UI 显示与磁盘空间预检
    val approxDiskBytes: Long,
    val license: String,
    val licenseNote: String? = null,      // 例如 matcha 的"仅限非商业使用"
    val minRamBytes: Long = 0L,           // 低内存机隐藏用
    val sampleRate: Int = 0,              // TTS 专用
    val embeddingDim: Int = 0,            // EMBED 专用
)

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val fileIndex: Int, val fileCount: Int,
                           val bytesDone: Long, val bytesTotal: Long) : DownloadState
    data class Extracting(val fileIndex: Int) : DownloadState
    data object Verifying : DownloadState
    data object Done : DownloadState
    data class Failed(val reason: String, val retryable: Boolean) : DownloadState
}

object QuroModelDownloadManager {

    fun modelDir(ctx: Context, spec: ModelSpec): File =
        File(ctx.filesDir, "ondevice_model/${spec.id}")

    fun isDeployed(ctx: Context, spec: ModelSpec): Boolean

    suspend fun downloadAndDeploy(
        ctx: Context,
        spec: ModelSpec,
        wifiOnly: Boolean,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit,
        onState: (DownloadState) -> Unit,
    ): Boolean

    /** 删除模型目录并清 prefs；返回释放的字节数 */
    suspend fun remove(ctx: Context, spec: ModelSpec): Long
}
```

### 5.3 四个实现要点

**① 断点续传**

- 临时文件固定为 `cacheDir/ondevice_dl/<specId>__<fileIdx>.part`，**下载中途不删**（现有实现失败即删，是无法续传的根因）
- 发起请求前 `val have = part.length()`；若 `have > 0` 则设 `conn.setRequestProperty("Range", "bytes=$have-")`
- 按响应码分支：
  - `206 Partial Content` → `FileOutputStream(part, /* append = */ true)` 续写，进度基数 = `have`
  - `200 OK` → 服务端不支持 Range（或文件已变），**截断重下**：`part.delete()`，`append = false`，进度基数 = 0
  - `416 Range Not Satisfiable` → 本地 `.part` 比远端还大（文件换了），同样删除重下
- 全部文件成功且校验通过后，才把 `.part` `renameTo` 正式文件；任何一步失败**保留** `.part` 供下次续传
- 失败重试上限 3 次，指数退避 2s / 4s / 8s

**② sha256 校验**

```kotlin
private fun sha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().buffered(1 shl 16).use { ins ->
        val buf = ByteArray(1 shl 16)
        while (true) { val n = ins.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
```

- `expectedSha256 != null` → 严格比对，不符即判失败并**删除** `.part`（内容已损坏，续传无意义）
- `expectedSha256 == null` → 退化为 `minSizeBytes` 下限检查（防 HTML 错误页当模型存下来的经典事故）
- **本批次三个模型暂不填 `expectedSha256`**（上游未公布官方校验值，自己算的值会在上游重发布时失效）。字段先留好，`minSizeBytes` 按 §4 实测值的 **98%** 填。

**③ WiFi-only**

```kotlin
private fun isUnmeteredWifi(ctx: Context): Boolean {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.activeNetwork ?: return false
    val cap = cm.getNetworkCapabilities(net) ?: return false
    return cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
           cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}
```

- `wifiOnly == true` 且不满足 → 立刻 `DownloadState.Failed("当前非 WiFi 网络", retryable = true)`，**不发起任何请求**
- 下载**中途**掉出 WiFi → 当前分片自然失败，`.part` 保留，状态置 `Failed(retryable = true)`，等用户回到 WiFi 点重试即可续传
- 该开关默认 **ON**，存 `QuroOnDeviceModelPrefs`

**④ 按 kind 分派校验**

```kotlin
private fun verify(dir: File, spec: ModelSpec): String? {   // 返回 null = 通过，否则为失败原因
    // 通用：expectedFiles 全部存在且非空
    for (rel in spec.expectedFiles) {
        val f = File(dir, rel)
        if (!f.exists()) return "缺少文件：$rel"
        if (f.isFile && f.length() == 0L) return "文件为空：$rel"
    }
    return when (spec.kind) {
        ModelKind.ASR_NCNN -> verifyNcnnLayout(dir)                 // 复用现有逻辑
        ModelKind.TTS_SHERPA_ONNX_VITS ->
            if (File(dir, "model.onnx").length() < 1 shl 20) "model.onnx 体积异常" else null
        ModelKind.TTS_SHERPA_ONNX_MATCHA -> {
            if (File(dir, "model-steps-3.onnx").length() < 1 shl 20) return "声学模型体积异常"
            if (File(dir, "hifigan_v2.onnx").length() < 1 shl 20) return "声码器缺失或体积异常"
            null
        }
        ModelKind.EMBED_ONNX -> {
            val vocabLines = File(dir, "vocab.txt").useLines { it.count() }
            if (vocabLines < 20000) "vocab.txt 行数异常（$vocabLines），疑似下载不完整" else null
        }
    }
}
```

**⑤ 磁盘空间预检**（顺带补上，成本极低但能挡掉一类恶心的半途失败）

下载前检查 `ctx.filesDir.freeSpace > spec.approxDiskBytes + spec.totalDownloadBytes + 200 MB`，不足则直接 `Failed`，提示用户需要清理多少空间。

---

## 6. WordPiece Tokenizer 实现要点

**落点**：`app/src/full/java/com/ai/assistance/quro/rag/QuroWordPieceTokenizer.kt`（纯 Kotlin，无第三方依赖）

**构造**：读 `vocab.txt`（21128 行），行号即 token id，建 `HashMap<String, Int>`。特殊 token：`[PAD]=0`、`[UNK]=100`、`[CLS]=101`、`[SEP]=102`（BERT 中文标准布局，以 vocab 实际查表为准，不要硬编码）。

**切分流程**（顺序不能变）：

1. **清洗**：去控制字符，`\t\n\r` 归一为空格
2. **小写 + 去重音**：BGE 中文模型 `tokenizer_config.json` 里 `do_lower_case = true`。统一 `lowercase()`，并用 `Normalizer.normalize(s, Normalizer.Form.NFD)` 后剔除 `Mn` 类字符
3. **CJK 逐字切**：对每个 CJK 字符，**前后各插一个空格**，使其成为独立 token。CJK 判定用码点区间：
   `0x4E00..0x9FFF`、`0x3400..0x4DBF`、`0x20000..0x2A6DF`、`0x2A700..0x2B73F`、`0x2B740..0x2B81F`、`0x2B820..0x2CEAF`、`0xF900..0xFAFF`、`0x2F800..0x2FA1F`
4. **标点切分**：把标点也切成独立 token
5. **按空白分词**，对每个词做 **贪心最长匹配 WordPiece**：
   - 从词首开始，取尽可能长的子串在 vocab 中查找
   - 首个子片段原样查；**后续片段前缀 `##` 再查**
   - 任一位置匹配不到 → 整个词退化为 `[UNK]`
   - 单词长度 > 100 直接 `[UNK]`（BERT 原实现的保护）
6. **加特殊 token**：`[CLS]` + tokens + `[SEP]`
7. **截断 / 填充到 512**：
   - 超长 → 截断到 510 个内容 token 后再加 `[CLS]`/`[SEP]`（**先截断再加**，否则 `[SEP]` 会被截掉）
   - 不足 → 用 `[PAD]`(0) 补齐
8. **产出三个 `LongArray(512)`**：
   - `input_ids`：token id
   - `attention_mask`：真实 token 位 1，pad 位 0
   - `token_type_ids`：**全 0**（单句任务）

**BGE 查询前缀（原文，一字不改）**：

```
Represent this sentence for searching relevant passages:
```

- **只对"查询句"拼前缀**，格式为 `前缀 + " " + query`（前缀与内容之间一个空格）
- **文档/段落入库时不拼前缀**
- 前缀拼错或双边都拼 → 检索质量显著下降但不会报错，属于静默故障，务必写单测覆盖

**建议单测**（放 `app/src/test/`）：`"你好world"` → 期望 `[CLS] 你 好 world [SEP]`；`"ChatGPT"` → 期望切成 `chat ##gp ##t` 一类的多片段（具体以 vocab 为准，断言"不为 UNK"即可）。

---

## 7. ⚠️ 专章：`embedding_version`（批次 D 最容易踩的坑）

### 7.1 问题本质

向量检索的相似度计算**默认所有向量来自同一个模型的同一个语义空间**。
一旦换了 embedding 模型（甚至同一模型换了量化方式、改了池化策略、改了查询前缀），新算出来的向量与库里的旧向量就**不在同一个空间**。

此时余弦相似度**仍然能算出一个数**——不会报错、不会崩溃、不会有任何日志——但这个数**毫无意义**。表现为：

- 检索结果看起来"有内容"但完全不相关
- 用户以为知识库坏了，实际是向量污染
- **最恶劣的是它不可观测**：没有异常、没有降级、没有告警，只有"AI 变笨了"

维度不同（512 vs 768）时至少会数组越界报错；**维度恰好相同但模型不同**（例如以后从 `bge-small-zh-v1.5` 换到另一个 512 维模型）才是真正的静默杀手。所以**不能靠维度检查兜底，必须显式版本化**。

### 7.2 Schema 改动（必做）

向量表加两列，并**在写入路径上强制填充**：

```sql
ALTER TABLE kb_chunk_vector ADD COLUMN embedding_version TEXT NOT NULL DEFAULT '';
ALTER TABLE kb_chunk_vector ADD COLUMN embedding_dim    INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_kb_vec_ver ON kb_chunk_vector(embedding_version);
```

同时在知识库元信息表记录当前库的版本：

```sql
ALTER TABLE kb_index_meta ADD COLUMN embedding_version TEXT NOT NULL DEFAULT '';
ALTER TABLE kb_index_meta ADD COLUMN embedding_dim    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE kb_index_meta ADD COLUMN reindex_required INTEGER NOT NULL DEFAULT 0;
```

> ⚠️ **表边界（批次 D 工程师必读）**：上面的 `kb_chunk_vector`（逐 chunk 向量，带 `embedding_version`）与 `kb_index_meta`（库级版本）是**向量检索专属表**，由后续 **embedding 批次**创建与迁移，**不要与批次 D 新建的"知识库文档表"（存抽取文本 / 元数据 / 解析来源 / 预览态）混为同一张表**。
> - 批次 D 的文档表**不应**加 `embedding_version` 列（向量是 per-chunk 而非 per-doc，预留列会语义错乱并和后续迁移撞车）。
> - 批次 D 只需给文档表一个**稳定、唯一的 `doc_id` 主键**（如 UUID），供未来向量表以 `doc_id` + `chunk_id` 外键式引用即可。这是两张表之间唯一的契约。
> - 向量表与迁移逻辑全部留给 embedding 批次，按 §7.2–7.4 执行；批次 D 本批不碰向量 schema。

**版本号取值规则**（写死成常量，改模型时必须同步改）：

```kotlin
object EmbeddingVersion {
    /** 格式：<modelId>@<dim>@<poolStrategy>@<promptRev> —— 任何一项变化都必须换版本号 */
    const val CURRENT = "bge-small-zh-v1.5-int8@512@cls@p1"
}
```

> 命名里带上 `pool` 与 `prompt` 修订号，是因为**换池化方式或改查询前缀同样会破坏空间一致性**，而这两件事很容易被当成"小改动"顺手做掉。

### 7.3 迁移逻辑

**Room `Migration`（旧库升级）**：

```kotlin
val MIGRATION_N_TO_N1 = object : Migration(N, N + 1) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE kb_chunk_vector ADD COLUMN embedding_version TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE kb_chunk_vector ADD COLUMN embedding_dim INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_kb_vec_ver ON kb_chunk_vector(embedding_version)")
        db.execSQL("ALTER TABLE kb_index_meta ADD COLUMN embedding_version TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE kb_index_meta ADD COLUMN embedding_dim INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE kb_index_meta ADD COLUMN reindex_required INTEGER NOT NULL DEFAULT 0")
        // 旧数据一律标记为「未知版本」并强制要求重建——绝不假定它们与新模型兼容
        db.execSQL("UPDATE kb_index_meta SET reindex_required = 1")
    }
}
```

**启动时一致性检查**（每次打开知识库都跑）：

```kotlin
suspend fun checkIndexHealth(kbId: String): IndexHealth {
    val meta = dao.meta(kbId) ?: return IndexHealth.Empty
    return when {
        meta.embeddingVersion.isEmpty()                        -> IndexHealth.NeedsReindex("索引由旧版本创建")
        meta.embeddingVersion != EmbeddingVersion.CURRENT      -> IndexHealth.NeedsReindex("嵌入模型已更新")
        meta.embeddingDim != EmbeddingVersion.DIM              -> IndexHealth.NeedsReindex("向量维度不匹配")
        meta.reindexRequired                                   -> IndexHealth.NeedsReindex("等待重建")
        else                                                   -> IndexHealth.Ok
    }
}
```

**三条硬性规则（不允许放宽）**：

1. **检索时强制过滤**：所有向量检索 SQL **必须**带 `WHERE embedding_version = :current`。
   即便上层已做健康检查，这一层也不能省——它是防止新旧混用的最后一道闸。
2. **写入时强制标注**：任何写向量的路径都必须同时写 `embedding_version` 与 `embedding_dim`，不允许使用默认值落库。建议在 DAO 的 insert 上做 `require(v.embeddingVersion.isNotEmpty())`。
3. **未重建时禁止语义检索**：`IndexHealth.NeedsReindex` 状态下，语义检索通道**直接关闭**，按 §8 降级到词法检索。**绝不允许"先凑合用着"**——静默失真比功能缺失危害大得多。

### 7.4 UI 提示

知识库详情页顶部挂一条**不可忽略**的 banner（不是 toast、不是 snackbar，要常驻）：

```
⚠️ 嵌入模型已更新，当前索引需要重建
在重建完成前，本知识库将使用关键词检索（语义检索已暂停）。
预计耗时：约 N 分钟（M 个片段）        [ 立即重建 ]  [ 稍后 ]
```

- 点【立即重建】→ 前台 Service + 通知栏进度，逐批重算并 `UPDATE` 向量与版本号，完成后清 `reindex_required`
- 点【稍后】→ banner 保留，语义检索继续关闭
- 重建过程**必须可中断可续跑**：按 `chunk_id` 升序处理，已完成的 chunk 其 `embedding_version` 已是新值，续跑时 `WHERE embedding_version != :current` 自然跳过
- 重建**不要**先 `DELETE` 再插入——那样中途失败会丢数据。就地 `UPDATE`，全程可回退

---

## 8. 降级链

### 8.1 TTS

```
① 端侧 sherpa-onnx（轻量档 matcha / 高音质档 melo，按 §4.4 路由）
      ↓ 失败（模型未下载 / 加载失败 / UnsatisfiedLinkError / 合成异常 / fdroid 风味）
② 云端 TTS provider（用户已配置的服务商）
      ↓ 失败（无 key / 网络错误 / 配额耗尽）
③ Edge TTS
      ↓ 失败（网络错误 / 接口变更）
④ 系统 android.speech.tts.TextToSpeech
      ↓ 失败（设备无 TTS 引擎 / 中文语音包缺失）
⑤ 静默失败：仅记录日志 + UI 上把"朗读"按钮置灰并附原因
```

**实现要求**：

- 每一级失败都要 `Log.w` 记录**具体原因**并把原因带到下一级；最终若走到 ⑤，UI 要能告诉用户"为什么不能朗读"，而不是按钮点了没反应
- 降级判定要**快**：端侧引擎初始化加 8s 超时，不能让用户对着转圈等
- 降级结果要**缓存到本次会话**：一旦端侧判定不可用，同一进程内不要每句话都重试一遍初始化
- `fdroid` 风味**直接从 ② 开始**（`BuildConfig.FLAVOR == "fdroid"` 时跳过 ①）

### 8.2 Embedding / RAG

```
① 端侧 bge-small-zh-v1.5 语义检索
      ↓ 模型未下载 / 加载失败 / §7.3 索引健康检查未通过 / fdroid 风味
② 现有 CJK 二元（bigram）词法检索
```

**明确边界**：

- 这条降级**只影响本地语义检索通道**。**远程语义路径（云端 embedding / 云端 RAG）不受影响**，两者是并行的独立通道，不要把它们串在一条链上
- 降级到词法检索时，检索结果要打标记（`retrievalMode = LEXICAL`），便于上层在 prompt 里说明"以下为关键词匹配结果"，也便于排障
- 词法检索是**永远可用的兜底**，不允许再往下降级到"无检索"

---

## 9. F-Droid 注意事项

### 9.1 已核实的文档不一致（必须修正）

| 文件 | 行 | 现状 | 应改为 |
|---|---|---|---|
| `F-DROID-SUBMISSION.md` | 38 | `位置：app/src/main/jniLibs/arm64-v8a/` | `位置：app/src/full/jniLibs/arm64-v8a/` |
| `F-DROID-SUBMISSION.md` | 57 | ``scandelete: [app/src/main/jniLibs/arm64-v8a]`` | ``scandelete: [app/src/full/jniLibs/arm64-v8a]`` |

> **重要澄清（与前序结论的偏差，以此处为准）**：
> `metadata/com.ai.assistance.quro.yml:70` 的 `scandelete` **写的已经是正确路径** `app/src/full/jniLibs/arm64-v8a`。
> 也就是说 **F-Droid 实际构建行为是对的，不存在会被拒的红线**——问题仅存在于 `F-DROID-SUBMISSION.md` 这份说明文档里，它引用了一个**空目录** `app/src/main/jniLibs/arm64-v8a/`。
> 严重性因此从「构建红线」下调为「文档陈述错误」。但仍必须修，否则下一个人照着这份文档去改 metadata，就会真的把 `scandelete` 改错，届时才变成红线。

### 9.2 本批次新增 `.so` 后的连带更新

`app/src/full/jniLibs/arm64-v8a/` 将从 6 个 `.so` 变为 **7 个**（Plan A）：

```
libncnn.so / libsherpa-ncnn-jni.so / libproot.so / libproot-loader.so
libproot-loader32.so / libtalloc.so / libsherpa-onnx-jni.so   ← 新增
```

> Microsoft ORT 的 `libonnxruntime.so` 与 `libonnxruntime4j_jni.so` 来自 **AAR 依赖**，不落在源码树，`scandelete` 管不到也不需要管；它们随 `fullImplementation` 走，`fdroid` 风味天然不含。

需同步更新：

- `F-DROID-SUBMISSION.md` 的「RED LINE 1 — 6 个预编译 `.so`」清单 → 改为 7 个，补 `libsherpa-onnx-jni.so (4.37 MB)`
- `metadata/com.ai.assistance.quro.yml` 的 `scandelete` **无需改动**（已覆盖整个目录）
- 若走了 §3.3 Plan B，数量变为 8 个（多 `libonnxruntime.so`），同样只需改说明文档

### 9.3 `System.loadLibrary` 风味守卫（逐一核对）

工程既有范式（`OfflineRecognizer.kt:114-117` / `OfflineStream.kt:29-32` / `WaveReader.kt:67-70`）：

```kotlin
if (com.ai.assistance.quro.BuildConfig.FLAVOR != "fdroid") {
    System.loadLibrary("<name>")
}
```

本批次需要加守卫的**全部**位置：

| 文件 | 库名 | 备注 |
|---|---|---|
| `app/src/full/java/com/k2fsa/sherpa/onnx/Tts.kt` | `sherpa-onnx-jni` | 从上游拷入后**必须手工加**，上游原版没有守卫 |
| `app/src/full/java/com/k2fsa/sherpa/onnx/*.kt`（其它含 `init` 块的） | `sherpa-onnx-jni` | 上游每个含 JNI 的类都有一份 `init`，逐个加 |
| （Plan B 才有）`QuroEmbedNative.kt` | `quro_embed` | 见 §3.3 B-3 |

> **ORT Java API 不需要守卫**：`ai.onnxruntime.OrtEnvironment` 自己会 `loadLibrary`，但由于整个 `src/full/` 源集在 `fdroid` 风味下不参与编译，这条路径根本不存在。**前提是 embedding 代码确实放在 `src/full/`**——这也是 §2.3 强调该落点的原因。

### 9.4 轻量 TTS 档许可证三层拆解（matcha-icefall-zh-baker）

> ⚠️ **更正声明**：此前某次口头汇报将本档概括为「许可证 Apache-2.0（权重可追溯）」并列为胜出理由，这是**错误 / 误导**的。Apache-2.0 只覆盖**训练代码**（Layer 1），**模型权重本身没有 Apache-2.0 授权**，且被**非商业数据集**（Layer 3）约束。真实状态见下面三层拆解；§4.2 的「已知代价」与本节一致，请以本节为准。

#### 9.4.1 Layer 1 — 代码 / 工具链许可：✅ Apache-2.0

| 组件 | 许可 | 证据 |
|---|---|---|
| sherpa-onnx（推理代码、JNI、APK） | Apache-2.0 | 仓库 LICENSE：https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE ；官方 APK 页明确写「The code of Next-gen Kaldi is using Apache-2.0 license」：https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine-cn.html |
| icefall（matcha 训练脚本 `egs/baker_zh/TTS/matcha`） | Apache-2.0 | https://github.com/k2-fsa/icefall （根目录 LICENSE 为 Apache-2.0） |

**含义**：可自由使用、修改、再分发 sherpa-onnx 与 icefall 的代码。这一层无限制。

#### 9.4.2 Layer 2 — 模型权重文件分发许可：⚠️ 权重本身无 License，发布方明示「仅限非商业」

- `matcha-icefall-zh-baker.tar.bz2` 与 `hifigan_v2.onnx` **本身不携带任何 LICENSE 文件**（包内 `README.md` 仅 370 B 基础说明，无授权条款）。
- 但**发布方在官方模型页直接挂了非商业警示**：
  - sherpa-onnx 官方模型页（matcha 总览）：*"The dataset used to train the model is from https://en.data-baker.com/datasets/freeDatasets/. **Caution The dataset is for non-commercial use only.**"* — https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/matcha.html
  - 单模型页同样引用该警示：https://k2-fsa.github.io/sherpa/onnx/tts/all/Chinese/matcha-icefall-zh-baker.html
  - ModelScope 镜像：「数据集来自 DataBaker，**仅限非商业用途**；请遵循子目录 README 中的声明」 — https://modelscope.cn/models/gomodels/sherpa
- **关键判断**：权重文件没有独立的 Apache-2.0 授权；其可分发性受制于训练数据的许可（见 Layer 3）。「文件挂了 Apache-2.0」的说法不成立——挂 Apache-2.0 的是**代码**，不是**权重**。

#### 9.4.3 Layer 3 — 训练数据集许可（标贝 Baker BZNSYP）：❌ 明确非商业

BZNSYP（中文标准女声音库，10000 句 / 约 12 小时）由标贝（北京）科技有限公司发布，**仅限非商业用途**，这是数据集方的硬性条款：

- 标贝官方数据页：「本次开放的数据**仅支持非商业用途**」 — http://www.data-baker.com/data/index/TNtts
- 标贝 2018 开放公告（微博）：「本次开放的数据**仅支持非商用！**」 — https://weibo.com/ttarticle/p/show?id=2309404304785795883302
- 数据集登记站：「许可证 仅限非商用 / 商用状态 非商用」 — https://www.global-datasets.com/d/databaker_chinese_standard_female
- HuggingFace 衍生模型 MODEL_CARD 明确引用：「License: Non-commercial use (see https://www.data-baker.com/data/index/TNtts/)」 — https://huggingface.co/csukuangfj2/vits-piper-zh_CN-xiao_ya-medium-fp16/blob/main/MODEL_CARD

**含义**：用该数据集训练出的任何模型权重，**不得用于商业目的**。这是一个会向下游传递的限制。

#### 9.4.4 可执行结论（钉死，团队以此为准）

1. **本轻量档不是「Apache-2.0 可商用」。** 它是「Apache-2.0 代码 + 非商业数据（BZNSYP）训练的权重」。权重被 Layer 3 的非商业条款约束，**不能**以"代码是 Apache-2.0"为由商用。
2. **团队裁定（最终）：保留轻量档，但按最严标注处理。**
   - 模型是**按需下载**，不进 APK；分发的安装包本身不含任何非商业内容，用户知情后自行下载——这与"把非商业权重打包进 APK 分发"性质不同。
   - 但"知情"必须是**真知情**：按 §9.4.5 双重标注执行（下载卡片 + 设置页常驻文案 + 下载前一次性确认弹窗，缺一不可），`NOTICE` 补段落也要有。
   - F-Droid 侧按 Anti-Feature 如实申报，不藏。
   - **不做不可逆的删除动作**：melo 现已核实干净（§9.4.7），轻量档保留为"受控的可选项"，不预删。
3. **任何把权重打进 APK 随包分发、或用于付费 / 广告 / `full` 风味 monetize 的行为**：仍属风险不可接受，禁止。
4. **主链路结论（关键）**：默认档 `vits-melo-tts-zh_en` 与 STT `sense-voice` 经全链路核实均为**宽松许可、无任非商业限制**（§9.4.7 / §9.4.8）。**主链路不塌方，无需退回系统 TTS**；只有轻量档这一个可选项带非商业约束，且已用最严标注隔离。

#### 9.4.5 双重标注落地（§4.2 承诺的「模型卡 + NOTICE」）

若决定保留该档（仅限非商业场景），必须两处明示：

- **模型下载卡片 / TTS 设置页常驻文案**：
  > 本语音模型基于标贝（DataBaker）BZNSYP 开源语音数据集训练，**仅限非商业用途**。数据集许可：http://www.data-baker.com/data/index/TNtts
- **`NOTICE` 文件追加段落**：说明 sherpa-onnx（Apache-2.0）与 Baker BZNSYP 数据集（仅限非商业）的出处与许可，并标注本档的 non-commercial 限制。
- 下载前强制一次性的「仅限非商业用途」确认弹窗（勾选后才允许下载）。

#### 9.4.6 melo / sense-voice 全链路核查结论（已核完，非"后续项"）

- `vits-melo-tts-zh_en`（默认高音质档）：✅ **已核实干净，可作为默认推荐档**。上游 MeloTTS（myshell-ai/MIT）明确 "free for both commercial and non-commercial use"；权重包内带 `LICENSE` 文件（1.0K）；英文训练数据 LibriTTS 为 CC BY 4.0（可商用）；中文训练数据上游未逐条列举，但全项目以 MIT + 商用自由为明示许可立场，且**无任何非商业红旗**（与 matcha 的"双重非商业标注"相反）。唯一残留风险 = 中文训练数据未逐条列证（低）。
- `sense-voice`（STT 替换件）：✅ **已核实干净，可作为默认 STT**。上游 FunAudioLLM/SenseVoice 由阿里巴巴以 **Apache-2.0** 发布并明示可免费商用；权重包内带 `LICENSE` 文件（71B）+ 导出脚本；模型页**无任何非商业警示**（与 matcha 相反）。训练数据 ~400k 小时未逐条列举，其中公开部分 WenetSpeech 官方标注 "non-commercial under CC BY 4.0"（存在争议，部分注册站标可商用）——但模型本身由权利方 Apache-2.0 发布，该发布即构成商用授权，训练数据灰区**不构成 matcha 式的硬约束**。
- **结论**："砍轻量档只留 melo"的退路现在成立——melo 干净；且 sense-voice 也干净，STT 主链路无虞。轻量档保留为受控可选项（最严标注），不预删。

#### 9.4.7 `vits-melo-tts-zh_en`（默认档）全链路三层拆解

| 层 | 结论 | 证据 |
|---|---|---|
| L1 代码/工具链 | ✅ MIT | sherpa-onnx Apache-2.0（https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE）；MeloTTS MIT（https://github.com/myshell-ai/MeloTTS ；http://docs.myshell.ai/technology/melotts 明示 free for commercial） |
| L2 权重文件 | ✅ 包内带 `LICENSE`（1.0K），无警示 | sherpa 模型页 "converted from https://huggingface.co/myshell-ai/MeloTTS-Chinese"（https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html）；HF 卡 "under MIT License, free for both commercial and non-commercial use"（https://huggingface.co/myshell-ai/MeloTTS-Chinese） |
| L3 训练数据 | ✅ 无红旗 | 英文 LibriTTS = CC BY 4.0 可商用（https://www.openslr.org/60/）；中文未逐条列举，上游 MIT 商用自由立场、无非商业红旗 |

**判定**：可作为默认推荐档。风险低；NOTICE 注明 MeloTTS MIT + 数据集出处。

#### 9.4.8 `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`（STT 主链路）全链路三层拆解

| 层 | 结论 | 证据 |
|---|---|---|
| L1 代码/工具链 | ✅ Apache-2.0 | sherpa-onnx Apache-2.0（同上）；FunAudioLLM/SenseVoice Apache-2.0（https://github.com/FunAudioLLM/SenseVoice ；https://techwan.org/article/2512.html 称 fully open-sourced under Apache 2.0） |
| L2 权重文件 | ✅ 包内带 `LICENSE`（71B）+ 导出脚本，无警示 | sherpa 模型页 "converted from https://www.modelscope.cn/models/iic/SenseVoiceSmall"（https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/sense-voice/pretrained.rst）；模型页无任何 non-commercial 警示 |
| L3 训练数据 | ⚠️ 灰区但已被权利方发布覆盖 | ~400k 小时未逐条列举；公开部分 WenetSpeech 官方标 "non-commercial under CC BY 4.0"（https://wenet-e2e.github.io/WenetSpeech ，存在争议）；但模型由权利方阿里巴巴 Apache-2.0 发布，构成商用授权，非 matcha 式硬约束 |

**判定**：可作为默认 STT。风险低；NOTICE 注明 SenseVoice Apache-2.0。

#### 9.4.9 三模型许可态势总表

| 模型 | 角色 | L1 代码 | L2 权重 | L3 训练数据 | 非商业红旗 | 判定 |
|---|---|---|---|---|---|---|
| `matcha-icefall-zh-baker` | 轻量档（可选） | Apache-2.0 | 无 LICENSE，页明示非商业 | BZNSYP 明确非商业 | ❌ 有（双层） | 受控可选项，最严标注（§9.4.5） |
| `vits-melo-tts-zh_en` | 默认高音质档 | MIT | 包内 LICENSE（MIT） | LibriTTS CC BY4.0 + 中文未列（无红旗） | ✅ 无 | 可作默认推荐档 |
| `sherpa-*-sense-voice-*` | 默认 STT | Apache-2.0 | 包内 LICENSE + 导出脚本 | ~400k hrs，WenetSpeech 灰区（权利方 Apache 发布） | ✅ 无 | 可作默认 STT |

> **一句话**：主链路（melo + sense-voice）均无任非商业约束；只有轻量档 matcha 带非商业约束且已用最严标注隔离。**无需退回系统 TTS。**

### 9.5 运行时下载模型的 Anti-Feature 影响

metadata 已声明 `Anti-Features: NonFreeNet`。本批次新增的三个模型均为**用户主动触发的运行时下载**，不随 APK 分发，因此**不污染 APK 的授权、无需新增 anti-feature**。

但轻量档 `matcha-icefall-zh-baker` 的非商业许可约束（见 §9.4）仍须在 UI 与 `NOTICE` 中如实标注——这不属于 anti-feature 新增，而是 §9.4.5 的双重标注要求。若后续有商业分发计划，轻量档需换成无此限制的模型（或整档砍掉，见 §9.4.4）。

---

## 10. 实施顺序建议

| 步骤 | 内容 | 阻塞关系 |
|---|---|---|
| 0 | **先做 §3.2 验证**（vendor `.so` + 加 ORT 依赖 + 冒烟合成一句中文） | **阻塞所有 TTS/Embedding 工作**。不通过就走 §3.3，路线完全不同 |
| 1 | `QuroModelDownloadManager`（§5） | 被 2 / 3 阻塞 |
| 2 | TTS 两档接入 + 路由 + 降级链（§4.2 / 4.3 / 4.4 / 8.1） | 依赖 0、1 |
| 3 | WordPiece tokenizer + 单测（§6） | 可与 2 并行，不依赖 native |
| 4 | Embedding 推理 + `embedding_version` schema/迁移/UI（§4.5 / §7） | 依赖 0、1、3 |
| 5 | STT 默认值锁定 + UI 标注（§4.1） | 独立，随时可做 |
| 6 | F-Droid 文档修正（§9.1 / 9.2） | 独立，随时可做 |

> 步骤 0 是**唯一的高风险项**，务必第一个做，不要等到 TTS 业务代码写完才发现要走 Plan B。
