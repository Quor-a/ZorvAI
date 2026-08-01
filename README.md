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

## 🗓️ 版本历程 / 升级说明

| 版本 | 日期 | 说明 |
|------|------|------|
| aci-core 2026-08-01 | 2026-08-01 | **ACI 2.0 治理层错误模型 + 协议版本常量**：`ACIError` 纯增量新增语义错误码命名空间——15xx 服务/协议治理层（`SERVICE_UNBOUND=1503` / `PROTOCOL_NEGOTIATE_FAIL=1505` / `CALL_FAILED=1506`）、24xx 请求语义层（`BAD_REQUEST_PARAM=2400` / `MISSING_FIELD=2401` / `UNSUPPORTED_PROTOCOL=2403`）、25xx HTTP 传输层（`HTTP_CLIENT_ERROR=2500` / `HTTP_SERVER_ERROR=2510` / `HTTP_DNS_FAILED=2521` / `HTTP_TLS_ERROR=2522` / `HTTP_CONNECT_FAILED=2523` / `HTTP_TOO_LARGE=2524` / `HTTP_UNKNOWN=2599`）；新增 `message(int)` 对应文案与 `isAciProtocol(int)` 判定；新增协议版本常量 `PROTOCOL_V1="aci-protocol-v1"` / `PROTOCOL_LATEST`。**不改动任何既有错误码与公开 API**，向后兼容。消费方（如 ZorvAI 主程序 `QuroAciErrors`）复用同一命名空间，避开内核标准码 0/400/403/404/500/503/504/505 |
| aci-core 2026-01 | 2026-01 | 初始开源分支：AIDL Binder 框架、`BaseACIService` / `Capability` / 内核标准错误码、`aci-core-release.aar` 可构建源码 |

> ℹ️ 本分支为 **SDK 源码分支**，已推送至 GitHub(`origin/aci-core`) 与 Gitee(`gitee/aci-core`) 双远端。消费方通过主仓库 Release 附带的 `aci-core-release.aar` 引用；如需本地联编，切到本分支 `./gradlew :aci-core:assembleRelease` 构建 AAR。

## 许可证

Apache-2.0。
