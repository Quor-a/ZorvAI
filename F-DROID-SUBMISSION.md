# F-Droid 提交包 — Zorv AI (com.ai.assistance.quro)

**日期**：2026-08-02
**状态**：🔴 阻塞 — 仓库需先修两处红线，方可向 f-droid/data 提 MR
**应用 ID**：`com.ai.assistance.quro`
**当前版本**：1.0.15 (versionCode 451)
**构建提交**：`cdb2b71e93d208bc8ef3d88d2025b53fbe64c4f8`（HEAD）

---

## 0. 结论速览

F-Droid **现在不能直接收**。本仓库有两条硬性红线违反 F-Droid 政策（都和"预编译二进制"有关），且当前 HEAD 还有 4 个未提交 ACI 文件、缺 `v1.0.15` 标签。

我已把"能提前准备的全准备了"：F-Droid `metadata/*.yml` + fastlane 描述（中/英）已落到仓库，表述与 F-Droid 构建后的真实能力一致（已如实声明 `NonFreeNet` 反特性，并注明离线 ASR / proot 沙箱不进 F-Droid 构建）。**剩下的是两处代码级改动 + 打标签**，改完即可一键提 MR。

---

## 1. 已确认的事实（实地核查，非猜测）

| 项 | 结论 | 证据 |
|----|------|------|
| 许可证 | ✅ Apache-2.0 | `LICENSE` |
| 浏览器引擎 | ✅ GeckoView 140.0.x MPL-2.0（F-Droid 允许） | `app/build.gradle.kts:147` |
| Shizuku | ✅ 13.1.5 Apache-2.0 | `libs.versions.toml` |
| QuickJS | ✅ 源码编 `libquroplugin.so` | `app/src/main/cpp/quickjs/` + `CMakeLists.txt` |
| 追踪/广告/Billing SDK | ✅ 无 GMS/Firebase/广告 | 全量 grep 确认 |
| 仅 arm64-v8a | ⚠️ F-Droid 只编此架构 | `app/build.gradle.kts:26` |
| git 标签 | ⚠️ 有 v1.0.11–v1.0.14，**无 v1.0.15** | `git tag` |
| 未提交改动 | ⚠️ 4 个 ACI 文件未 commit | `git status` |

---

## 2. 两条红线（阻塞项）

### 🔴 RED LINE 1 — 6 个预编译 `.so`，无源码构建路径

位置：`app/src/main/jniLibs/arm64-v8a/`
- `libncnn.so` (4.58 MB)
- `libsherpa-ncnn-jni.so` (892 KB)
- `libproot.so` (208 KB)
- `libproot-loader.so` / `libproot-loader32.so` / `libtalloc.so`

核查结论：`app/src/main/cpp/` 下**只有 `quickjs/` 源码**，仓库内**没有任何 ncnn / sherpa / proot 的源码或子模块**，且 `app/build.gradle.kts:123` 注释提到的 `./build-android-arm64-v8a.sh` 在本仓库**不存在**（全局 find 无 .sh）。即这 6 个 `.so` 是纯预编译二进制，F-Droid 政策明确禁止。

**修复方案（推荐 A — F-Droid 风味，务实可行）：**
新建 `fdroid` product flavor，让 F-Droid 构建**不包含**离线 ASR 与 proot Linux 沙箱两个原生特性：
1. 在 `app/build.gradle.kts` 的 `android { productFlavors { ... } }` 增加：
   ```kotlin
   flavorDimensions += "dist"
   productFlavors {
       create("fdroid") { dimension = "dist" }
       create("full")   { dimension = "dist"; isDefault = true }
   }
   ```
2. 用 `BuildConfig.FLAVOR` 守卫原生加载点（`System.loadLibrary("ncnn" / "sherpa" / "proot" / "talloc")`），使 `fdroid` 风味跳过这些加载；UI 层对"离线语音 / Linux 沙箱"入口做风味可见性控制。
3. 在 metadata 已用 `scandelete: [app/src/main/jniLibs/arm64-v8a]` 把预编译二进制从构建树中剔除。

**替代方案 B（从源码编，重）：** 把 ncnn / sherpa-ncnn / proot / talloc 的源码作为 git submodule 或 vendored 目录引入，写 CMake / 外部构建步骤让 F-Droid 构建机交叉编译。工作量大（NDK 交叉编译、构建时间长、易碎），不建议作为第一步。

### 🔴 RED LINE 2 — 跨仓预编译 AAR `aci-core`

位置：`app/build.gradle.kts:111`
```kotlin
implementation(files("../aci-browser/libs/aci-core-debug.aar"))
```
`aci-core/` 目录**只有 `build/` + `src/`，没有 `build.gradle(.kts)`**，不是 Gradle 模块；引用的 AAR 是另一个仓库（`aci-browser`）的产物。F-Droid 要求所有依赖可复现构建。

**修复方案：**
1. 把 `aci-core/src` 的源码收进主仓，在 `aci-core/build.gradle.kts` 声明为 Android library（`com.android.library`），包名对齐 `ai.aci.core.*`。
2. `settings.gradle.kts` 增加 `include(":aci-core")`。
3. `app/build.gradle.kts` 把第 111 行改为：
   ```kotlin
   implementation(project(":aci-core"))
   ```
4. 删除 `aci-browser/libs/aci-core-debug.aar` 的 `files()` 引用（可保留文件但不再被依赖）。

这是中等工作量、低风险的结构调整（不影响 `full` 风味功能）。

---

## 3. 其他必须处理项（非阻塞，但提交前要做）

1. **打标签 `v1.0.15`**：当前 HEAD 未打标签，F-Droid `UpdateCheckMode: Tags` 取不到。修完红线后 `git tag v1.0.15 && git push gitlab --tags`（及 origin/gitee 同步）。
2. **提交未提交的 ACI 改动**：`QuroAciAdapter.kt` / `QuroAciRegistry.kt`（新）+ `AciConsoleModel.kt` / `QuroAciManager.kt`（改）共 4 文件当前未 commit；要么先 commit 进 v1.0.15，要么告知 F-Droid 构建的是不含它们的 `cdb2b71`。
3. **仓库须公开**：`jihulab.com/quor-a-group/ZorvAI` 须为 public，且 `cdb2b71`（或新标签提交）已 push 到该远端。三个远端（github / gitee / jihulab）当前都存在，metadata 以 jihulab 为权威源。

---

## 4. 提交流程（改完红线后）

1. Fork `https://gitlab.com/f-droid/fdroiddata` → 新建分支。
2. 将本仓库的 `metadata/com.ai.assistance.quro.yml` 复制到 `fdroiddata/metadata/com.ai.assistance.quro.yml`。
3. （可选）`fastlane/metadata/android/...` 也一并提交，供 F-Droid 抓取描述。
4. 提 MR 到 `f-droid/fdroiddata`，描述里**如实说明**离线 ASR / proot 不在 F-Droid 构建中、已声明 `NonFreeNet`、aci-core 已改为源码模块。
5. 等 F-Droid 构建机跑通；若 `fdroid` 风味构建失败，按构建日志修 `scandelete` / 风味守卫后再更新 MR。

---

## 5. 本仓库已落地的提交物

- `metadata/com.ai.assistance.quro.yml` — F-Droid metadata（含 `Anti-Features: NonFreeNet`、`Gradle: [fdroid]`、`scandelete` 预编译 .so）
- `fastlane/metadata/android/en-US/{title,short_description,full_description}.txt`
- `fastlane/metadata/android/zh-CN/{title,short_description,full_description}.txt`
- 本文档 — 阻塞项与修复方案总览

> 引擎团队 AI 协作生成；关键决策与代码改动请由工程负责人复核与实机编译验证。
