# ZorvAI：一个真正能「操作手机」的安卓端 AI Agent 开源项目

> **关键词**：Android AI Agent、安卓智能体、本地优先 AI、Kotlin Jetpack Compose、ACI 跨应用调用、设备端 LLM 助手、开源 AI 助手
>
> **项目地址**：[GitHub](https://github.com/Quor-a/ZorvAI) · [Gitee（国内镜像）](https://gitee.com/ZorvAI/ZorvAI) · [APK 下载（v1.0.14）](https://github.com/Quor-a/ZorvAI/releases/tag/v1.0.14)

---

## 写在前面：为什么做这个项目？

市面上的 AI 助手 App 大多是「套壳聊天」——一个 WebView 包着 ChatGPT 的 API，能对话，但**碰不了你手机上的任何东西**。

ZorvAI 的出发点很简单：**如果 AI 真的要成为你的「助手」，它得能操作你的手机**——打开 App、输入文字、读取屏幕、执行代码、管理文件、定时跑任务、甚至通过飞书/QQ/微信随时在线响应。

这不是又一个聊天玩具。这是一个运行在 Android 设备上的 **完整 AI Agent 运行时环境**。

---

## 一、ZorvAI 是什么？

**ZorvAI（Zorv AI）** 是一个完全开源的、运行在 Android 端的**本地优先 AI Agent 智能体助手**。

核心特点：

| 特性 | 说明 |
|------|------|
| 🤖 **设备端 Agent** | 不依赖云端中转，所有能力在本地执行 |
| 🔐 **5 层权限体系** | 无障碍 → Shizuku → 设备管理员 → ROOT → 应用内 Linux（proot + Alpine） |
| 🧰 **30+ 内置工具** | 打开应用、操控屏幕、读写文件、运行代码、定时任务、记忆系统 |
| 🔌 **ACI 跨应用调用** | 自研 Agent Capability Interface，让任意 App 能力变成 AI 可调用的工具 |
| 🌐 **多通道接入** | 飞书（WebSocket）/ QQ Bot / 微信 iLink |
| ⚡ **共享运行时** | 内置 Node.js / Python / SSH / Java / Rust / Go 执行引擎 |
| 🎙️ **全双工语音** | 本地 STT（语音转文字）+ TTS（文字转语音） |
| 💬 **人格系统** | 多人格切换，每个角色有独立设定和记忆 |
| 📱 **Kotlin + Jetpack Compose** | 原生 Android UI，非 WebView 套壳 |

技术栈：**Kotlin 2.0 + Jetpack Compose 1.7 + compileSdk 36 / minSdk 26**

---

## 二、架构设计：Agent 是怎么「操作手机」的？

```
┌─────────────────────────────────────────────┐
│              用户界面 (Compose)               │
│   ChatScreen │ PersonaBar │ PermissionBar    │
├─────────────────────────────────────────────┤
│            Agent 核心 (ViewModel)             │
│   多会话隔离 │ 工具注册表 │ 技能系统 │ 记忆     │
├──────────┬──────────┬──────────┬────────────┤
│ 工具/能力层 │ 特权层  │ 引擎/运行时│ IM 通道    │
│           │         │          │            │
│ launch_app│ L1 无障碍│ CMS 引擎 │ 飞书 WS    │
│ input_text│ L2 Shizuku│ Node.js │ QQ Bot    │
│ tap_screen│ L3 DevAdmin│ Python │ 微信 iLink │
│ read_screen│ L4 ROOT │ SSH/Rust│            │
│ scheduler │ L5 proot │ Java/Go │            │
│ memory_*  │ Alpine  │         │            │
└──────────┴─────────┴─────────┴────────────┘
```

### 权限分层（L1–L5）

ZorvAI 不是一上来就要 ROOT。它采用**渐进式授权**：

- **L1 无障碍**：点击、输入、读屏（节点树，非截图）——零门槛，系统设置里开就行
- **L2 Shizuku**：高权限 shell 命令（需装 Shizuku App 并配对）
- **L3 设备管理员**：锁定屏幕等策略级操作
- **L4 ROOT**：完整 root 权限（`su -c` 执行）
- **L5 应用内 Linux**：proot + Alpine rootfs，真 Linux 用户态执行

**未授权的能力不会静默越权**，而是返回引导提示让用户去开启对应权限。

---

## 三、ACI：让任何 App 的能力变成 AI 工具

这是 ZorvAI 最有技术亮点的部分——**ACI（Agent Capability Interface）**。

### ACI 是什么？

ACI 是一套基于 Android AIDL Binder 的**跨应用能力接口协议**。它的设计目标是：

> 让「手机上任意 App 的能力」变成 AI Agent 可以编排调用的工具，无需公网、无需云端中转。

### 架构角色

```
控制端 (ZorvAI 主程序)          受控端 (你的 App)
┌──────────────────┐    AIDL    ┌──────────────────┐
│ QuroAciManager   │ ──call──▶ │ BaseACIService   │
│ - discover() 发现 │ ◀─response│ - 注册能力        │
│ - bind() 绑定    │          │ - onCall() 处理   │
│ - 发起调用       │          │ - 权限校验        │
└──────────────────┘          └──────────────────┘
```

### 实战案例：受控浏览器 30 项能力

ZorvAI 官方提供了一个受控端实现——**ZorvAI 浏览器**（独立开源仓库），它向控制端暴露了 **30 项能力**：

| 能力分类 | 能力列表 |
|---------|---------|
| **导航类** | `browser_open`（打开网址）、`browser_nav`（前进/后退/刷新） |
| **读取类** | `browser_read`（读页面 HTML）、`browser_crawl`（抓结构化正文+链接）、`browser_elements`（DOM 元素树） |
| **交互类** | `browser_action`（CSS 选择器操作：click/input/text/scroll/select）、`browser_mouse`（虚拟鼠标坐标级操作） |
| **搜索类** | `browser_search`（搜索引擎检索：bing/google/baidu/ddg） |
| **脚本类** | `browser_script`（执行任意 JavaScript） |
| **标签页** | `browser_list`（列标签页）、`browser_tabnew`/`browser_tab`/`browser_tabclose` |
| **信息类** | `browser_info`（版本信息）、`browser_wait`（条件等待） |
| **截图/快照** | `browser_screenshot`（截图）、`browser_snapshot`（状态快照）、`browser_capture`（拦截网络请求） |
| **控制台** | `console_ui`（UI 描述 JSON）、`console_action`（控制台操作） |
| **HTTP** | `http_request`（HTTP 请求，支持局域网明文） |

**实测结果（2026-08-01）**：30 项能力全部测试通过 ✅（28/30 完全通过，2 项为已知限制已文档化）。

### http_request：局域网 HTTP 传输

v1.0.14 新增的 `http_request` 能力让受控端可以代为发起任意 HTTP 请求：

```json
// 入参
{"url": "http://192.168.1.1/admin", "method": "GET", "headers": {}}

// 返回
{
  "status_code": 200,
  "response_headers": {"content-type": "text/html"},
  "response_body": "<!DOCTYPE html>...",
  "truncated": false
}
```

支持同网段 LAN 明文访问（`192.168.x.x` / `10.x` / `*.local` mDNS），适用于**局域网设备管理、路由器配置、内网 API 调试**等场景。

---

## 四、内置工具一览

ZorvAI 的 AI 不只是「能聊天」，它真的能**干活**：

### 📱 设备控制
- 打开应用、查看电量/WiFi/网络状态
- 开关手电筒、振动、锁屏
- 读取通知、剪贴板、传感器数据

### 📝 信息管理
- 读写日历、发短信、查联系人
- 读取短信、查看蓝牙状态

### 📁 文件操作
- 读写文件、创建文件夹、搜索文件
- 生成 Word/Excel/PPT/PDF 文档

### 💻 代码与终端
- 运行 Python/Node.js/Shell 代码
- Linux 环境（proot + Alpine）执行命令

### 🌐 联网能力
- AI 自动化搜索、抓取网页、下载文件
- 打开内置浏览器（GeckoView，MPL-2.0）

### 🧠 智能能力
- 长期记忆存储与检索
- 人格系统（多角色独立设定）

### ⏰ 定时任务
- 基于 rrule 的 cron 式定时任务
- 支持一次性 / 循环 / 结束时间

---

## 五、CMS 引擎与模块系统

ZorvAI 内置了 **CMS（Common Module System）引擎**，可以在应用内的 **proot/Alpine Linux 沙箱**中运行模块：

- **Web 搜索与浏览模块**（`quoro.web`）：AI 自动化搜索与浏览
- **自定义模块**：支持导入/导出/添加模块
- **一键部署到终端**：模块自动部署到 proot/Linux 环境

首次使用需在「终端」页安装 Linux 环境（约 30MB），之后所有模块在沙箱内运行，不影响主系统。

---

## 六、插件与扩展体系

ZorvAI 支持多种扩展方式：

| 扩展类型 | 说明 |
|---------|------|
| **插件运行时** | 小程序式插件 Demo |
| **灵魂注入** | 灵魂注入 · 灵魂卡 · 记忆库（人格深度定制） |
| **MCP 服务** | 把内置工具以 MCP 协议暴露给本机客户端 |
| **ACI 管理中心** | 已发现第三方 App / 绑定状态 / 能力清单 / 手动注册/解绑 |

---

## 七、快速开始

### 方式一：直接下载 APK（推荐）

从 Releases 页下载最新 debug 包：

- **主程序**：[ZorvAI-debug-v1.0.14.apk](https://github.com/Quor-a/ZorvAI/releases/download/v1.0.14/ZorvAI-debug-v1.0.14.apk)（~315MB）
- **受控浏览器**：[ZorvBrowser-aci-debug-v1.0.14.apk](https://github.com/Quor-a/ZorvBrowser/releases/download/v1.0.14/ZorvBrowser-aci-debug-v1.0.14.apk)（~1.5MB）
- **Gitee 镜像（国内下载更快）**：[gitee.com/ZorvAI/ZorvAI](https://gitee.com/ZorvAI/ZorvAI)

### 方式二：从源码构建

```bash
# 克隆仓库
git clone https://github.com/Quor-a/ZorvAI.git
cd QuroAI

# 准备环境
# JDK 17
# Android SDK (compileSdk 36 / minSdk 26 / targetSdk 34)

# 构建
./gradlew clean :app:assembleDebug

# 产物位置
# app/build/outputs/apk/debug/app-debug.apk
```

### 系统要求

- Android 8.0+ (API 26+)
- 建议 4GB+ 内存
- 存储空间 200MB+（本地语音模型另需约 85MB）

---

## 八、为什么选 ZorvAI？

| 对比维度 | ZorvAI | 普通 AI 助手 App |
|---------|--------|------------------|
| 能否操作手机 | ✅ 30+ 种操控能力 | ❌ 只能聊天 |
| 是否本地优先 | ✅ 核心能力全部本地 | ☁️ 依赖云端 API |
| 是否开源 | ✅ Apache 2.0 完全开源 | ❌ 闭源/套壳 |
| 跨应用调用 | ✅ ACI 协议 | ❌ 无 |
| 多通道接入 | ✅ 飞书/QQ/微信 | ❌ 单一 App |
| 代码执行 | ✅ Python/Node/Shell/Linux | ❌ 无 |
| 定时任务 | ✅ rrule cron | ❌ 无或极简 |
| 隐私保护 | ✅ 数据不出设备 | ⚠️ 数据上传云端 |

---

## 九、开源协议与社区

- **协议**：Apache License 2.0
- **GitHub**：[github.com/Quor-a/ZorvAI](https://github.com/Quor-a/ZorvAI)
- **Gitee**：[gitee.com/ZorvAI/ZorvAI](https://gitee.com/ZorvAI/ZorvAI)
- **问题反馈**：[GitHub Issues](https://github.com/Quor-a/ZorvAI/issues)
- **ACI 开发者手册**：[docs/ACI_DEVELOPER_GUIDE.md](https://github.com/Quor-a/ZorvAI/blob/main/docs/ACI_DEVELOPER_GUIDE.md)

---

> 如果你觉得这个项目有意思，欢迎 **Star ⭐** 支持！也欢迎提交 Issue 和 PR 参与贡献。
>
> **关键词**：#Android #AIAgent #智能体 #Kotlin #JetpackCompose #开源 #本地AI #设备端AI #ACI #跨应用调用 #飞书接入 #QQ机器人 #微信接入 #语音助手 #TTS #STT #MCP #proot #Linux
