# Quro AI

> 开源 AI 助手 · 原创构建。
> 一个运行在 Android 上的原生 AI 助手，具备工具调用、灵魂注入（人格卡）、记忆库、CMS v2 能力模块、终端/开发环境、语音交互与完整内置工具箱。

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](./LICENSE)
[![Release](https://img.shields.io/github/v/release/Quor-a/QuroAI)](https://github.com/Quor-a/QuroAI/releases)

- **开源地址**：https://github.com/Quor-a/QuroAI
- **许可证**：[Apache-2.0](./LICENSE)（本应用源码）；第三方依赖各自保留其许可证，详见 [NOTICE](./NOTICE)

---

## ✨ 核心特性

- 🤖 **主动工具调用**：AI 可自主判断并调用设备能力（打开应用、查设备信息、文件操作、HTTP、TTS、内置浏览器等），无需你逐条下令。
- 🎭 **灵魂注入（人格卡）**：每张卡是独立真实身份，可切换；支持 AI 自动孵化、记忆库沉淀、语音风格组合。
- 🧩 **CMS v2 能力模块**：可扩展的能力插件系统，AI 可在应用沙箱内调用。
- 💻 **终端与开发环境**：应用内终端、Node/Python/SSH/Java/Rust/Go 等多语言开发环境供给。
- 🗣️ **语音交互**：本地/云端 TTS、多服务商音色与情绪标签、语音球悬浮窗随时唤醒。
- 🔌 **丰富工具生态**：40+ 内置工具 + 内置浏览器 + 知识库 + 代码运行 + 媒体播放。
- 🎨 **原创 UI**：Jetpack Compose 重设计，支持主题/字体/深浅色。
- 💾 **记忆库**：AI 自动分类管理长期记忆，智能搜索历史对话。

---

## 📱 系统要求

- Android 8.0+ (API 26+)
- 建议 4GB+ 内存，存储空间 200MB+

---

## 🚀 快速开始

### 下载安装（推荐）

直接从 [Release 页面](https://github.com/Quor-a/QuroAI/releases) 下载最新 APK，安装后启动并按应用内引导完成设置（配置你的模型 API Key 等）。

> 请务必从官方 Release 页面下载本应用，通过未知渠道获取的安装包可能被篡改，存在隐私泄露风险。

### 从源码构建

```bash
# 克隆仓库
git clone https://github.com/Quor-a/QuroAI.git
cd QuroAI

# 准备环境
# - JDK 17
# - Android SDK（compileSdk 36 / minSdk 26 / targetSdk 34）
# - 在 local.properties 中配置 sdk.dir

# 构建 debug 包
./gradlew clean assembleDebug
```

构建产物：`app/build/outputs/apk/debug/app-debug.apk`

> 注：部分原生模块（如 QuickJS 沙箱、Sherpa-NCNN 语音识别、GeckoView 内置浏览器）
> 以源码或预编译库形式集成，无需额外下载外部依赖包。

---

## 🔐 权限模型

AI 默认可见并可在应用沙箱内调用能力；更高层级的系统级执行通道
（无障碍 / Shizuku / 设备管理员 / ROOT / 应用内 Linux）**均需用户显式授权**后
才会启用，未授权时返回引导提示，绝不静默越权。

---

## 📌 版本历程

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0.2 | 2026-07-29 | 初始开源版本：Shizuku 授权按钮修复、AI 键盘输入通道、权限模型引导、GeckoView 内置浏览器、记忆库与 CMS v2 能力模块 |

---

## 📄 开源声明

Quro AI 以 **百分百开源** 为目标：应用全部源码公开，欢迎参与共建。

- 本应用源码采用 **Apache-2.0** 许可证。
- Quro AI 为**独立原创实现**，不搬运任何上游项目源码。
- 对于随包分发、带 Copyleft 义务的组件（GeckoView → MPL-2.0），
  我们按对应许可证要求提供相应源代码。完整第三方许可证清单见 [NOTICE](./NOTICE)。

如需以 **LGPLv3** 发布本应用，可将 `LICENSE` 替换为
LGPLv3 文本并在 `NOTICE` 中同步说明——这是一处可一键切换的许可选择。

---

## 🤝 贡献

欢迎各种贡献：核心功能开发、内置工具、CMS 模块、文档与翻译。

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/xxx`)
3. 提交变更 (`git commit -m 'feat: xxx'`)
4. 推送分支 (`git push origin feature/xxx`)
5. 提交 Pull Request

---

## 📝 问题反馈

遇到问题或有建议？欢迎 [提交 Issue](https://github.com/Quor-a/QuroAI/issues)。
请尽量提供：清晰描述、复现步骤、设备型号与系统版本、相关截图。

如果觉得项目不错，欢迎点个 ⭐ Star 支持我们！

---

## 🌐 English

**Quro AI** is a fully open-source, on-device AI agent app for Android, built natively with Kotlin and Jetpack Compose.

- Runs locally on your device; cloud models are called directly from the device using your own API Key / endpoint — Quro AI does not proxy your chats.
- Features: autonomous tool use, persona cards ("soul injection"), memory库, CMS v2 capability modules, an in-app terminal & multi-language dev environment (Node/Python/SSH/Java/Rust/Go), voice interaction, 40+ built-in tools, and an embedded GeckoView browser.
- **License**: [Apache-2.0](./LICENSE). Quro AI is an independent, original implementation and does not incorporate any upstream project source code. Components shipped with copyleft obligations (GeckoView → MPL-2.0) have their source provided per their license; see [NOTICE](./NOTICE).
- Download the latest APK from [Releases](https://github.com/Quor-a/QuroAI/releases).

---

Made with ❤️ by the Quro AI Team
