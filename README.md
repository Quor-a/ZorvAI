# aci-core · Agent Capability Interface (Core Library)

> 独立开源分支，提供 `aci-core` Android 库的**完整可构建源码**。主仓库见 [Quor-a/ZorvAI](https://github.com/Quor-a/ZorvAI)。

`aci-core` 是 Zorv AI **ACI（Agent Capability Interface）** 的核心库：一套同设备、无 Root、基于 AIDL Binder 的本地跨应用调用框架。任意 Android App 继承 `BaseACIService` 即可把自己暴露成「可被 AI 调用的能力」。

## 模块结构

```
aci-core/
├── build.gradle                 # 库模块（compileSdk 34 / minSdk 24 / targetSdk 34）
└── src/main/
    ├── AndroidManifest.xml       # ACI 权限定义（随 AAR 合并进消费方）
    ├── aidl/ai/aci/core/         # IACIService.aidl / IACICallback.aidl / ACIRequest.aidl / ACIResponse.aidl
    ├── java/ai/aci/core/         # BaseACIService / Capability / ACIRequest / ACIResponse / ACIError
    └── res/values/...            # （权限已移至 Manifest）
```

## 构建

```bash
# 需要：JDK 17 + Android SDK（在 local.properties 配置 sdk.dir）
./gradlew assembleRelease
# 产物：aci-core/build/outputs/aar/aci-core-release.aar
```

> 若未配置 `local.properties`，可在命令后追加 `-Psdk.dir=/path/to/Android/Sdk`。

## 消费方式

把构建出的 `aci-core-release.aar` 放入你的模块 `libs/`，并声明依赖：

```kotlin
// 你的模块 build.gradle.kts
dependencies {
    implementation(files("libs/aci-core-release.aar"))
}
```

受控端接入（5 步）、能力定义、权限模型与真实踩坑，详见主仓库
[docs/ACI_DEVELOPER_GUIDE.md](https://github.com/Quor-a/ZorvAI/blob/main/docs/ACI_DEVELOPER_GUIDE.md)。

## 协议版本

`aci-core` 同时实现 ACI 1.0（同步 `call` / `getCapabilities` / `ping`）与 1.1（异步 `callAsync` + `IACICallback`）。`Capability.version` 固定为 `"1.0"`。

## 许可证

Apache-2.0。
