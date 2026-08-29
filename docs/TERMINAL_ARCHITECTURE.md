# Zorv AI 终端技术架构与开发指南

> **开源地址**：[https://github.com/Quor-a/ZorvAI](https://github.com/Quor-a/ZorvAI)
>
> **版本**：v1.0.67 | **最后更新**：2026-08-29
>
> **作者**：Zorv AI 开发团队

---

## 目录

- [1. 概述](#1-概述)
- [2. 架构设计](#2-架构设计)
  - [2.1 整体架构图](#21-整体架构图)
  - [2.2 设计原则](#22-设计原则)
  - [2.3 技术选型](#23-技术选型)
- [3. 核心组件详解](#3-核心组件详解)
  - [3.1 QuroTerminalController — 终端控制器](#31-quroterminalcontroller--终端控制器)
  - [3.2 QuroShellSession — PTY Shell 会话载体](#32-quroshellsession--pty-shell-会话载体)
  - [3.3 QuroTerminalSessionManager — 多会话管理器](#33-quroterminalsessionmanager--多会话管理器)
  - [3.4 QuroLinuxEnv — Linux 环境后端](#34-qurolinuxenv--linux-环境后端)
  - [3.5 Qu roTerminalKeepAliveService — 前台保活服务](#35-quroterminalkeepaliveservice--前台保活服务)
  - [3.6 Qu roTerminalAciService — ACI 受控端服务](#36-quroterminalaciservice--aci-受控端服务)
- [4. Linux 沙箱（proot + Ubuntu 24.04 ARM64）](#4-linux-沙箱proot--ubuntu-2404-arm64)
  - [4.1 架构原理](#41-架构原理)
  - [4.2 rootfs 管理](#42-rootfs-管理)
  - [4.3 内置工具链](#43-内置工具链)
  - [4.4 CMS 运行时集成](#44-cms-运行时集成)
- [5. 前台服务保活机制](#5-前台服务保活机制)
  - [5.1 为什么需要前台服务](#51-为什么需要前台服务)
  - [5.2 specialUse 类型选择](#52-specialuse-类型选择)
  - [5.3 会话所有权模型](#53-会话所有权模型)
  - [5.4 巡检与自愈机制](#54-巡检与自愈机制)
  - [5.5 Android 14+ 兼容性](#55-android-14-兼容性)
  - [5.6 开机自启动](#56-开机自启动)
- [6. PTY 伪终端实现](#6-pty-伪终端实现)
  - [6.1 伪终端工作原理](#61-伪终端工作原理)
  - [6.2 核心代码实现](#62-核心代码实现)
  - [6.3 输出流处理](#63-输出流处理)
- [7. 跨进程接入方式](#7-跨进程接入方式)
  - [7.1 ContentProvider（TerminalProvider）](#71-contentproviderterminalprovider)
  - [7.2 Deep Link（TerminalDeepLinkHandler）](#72-deep-linkterminaldeeplinkhandler)
  - [7.3 Intent Handler（TerminalIntentHandler）](#73-intent-handlerterminalintenthandler)
  - [7.4 BroadcastReceiver（TerminalBroadcastReceiver）](#74-broadcastreceiverterminalbroadcastreceiver)
  - [7.5 对比与选型](#75-对比与选型)
- [8. 命令执行路由](#8-命令执行路由)
  - [8.1 环境自动检测](#81-环境自动检测)
  - [8.2 proot 命令构建](#82-proot-命令构建)
  - [8.3 超时与错误处理](#83-超时与错误处理)
- [9. 终端 UI 集成](#9-终端-ui-集成)
  - [9.1 入口方式](#91-入口方式)
  - [9.2 会话切换](#92-会话切换)
  - [9.3 输入输出渲染](#93-输入输出渲染)
- [10. 关键特性总结](#10-关键特性总结)
- [11. 常见问题与故障排除](#11-常见问题与故障排除)
- [12. 开发指南](#12-开发指南)
  - [12.1 环境准备](#121-环境准备)
  - [12.2 从源码构建](#122-从源码构建)
  - [12.3 添加新的终端能力](#123-添加新的终端能力)
  - [12.4 测试建议](#124-测试建议)
- [13. 开源信息](#13-开源信息)

---

## 1. 概述

Zorv AI 终端是一个**完整的 Android 终端模拟器**，集成在 Zorv AI 应用中。它不仅仅是一个简单的命令行界面，而是一个具备以下核心能力的完整终端系统：

- **真实 Linux 用户空间**：基于 proot + Ubuntu 24.04 ARM64，提供完整的 Linux 工具链（Python、apt、bash 等）
- **PTY 伪终端**：标准的 `/dev/ptmx` 伪终端实现，支持交互式 shell 会话
- **前台服务保活**：通过 Android Foreground Service 让终端会话脱离 UI 生命周期，息屏/切换应用不被杀死
- **ACI 跨进程调用**：12 个标准化能力，支持其他应用通过 AIDL/HTTP/MCP 调用终端
- **多种 IPC 接入**：ContentProvider、Deep Link、Intent、BroadcastReceiver 四种标准 Android IPC 方式
- **多会话管理**：支持同时运行多个独立的终端会话
- **开机自启动**：设备重启后自动恢复终端保活

**开源地址**：[https://github.com/Quor-a/ZorvAI](https://github.com/Quor-a/ZorvAI)

---

## 2. 架构设计

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                          终端 UI 层                                  │
│  ChatScreen 输入框「+」→ 终端  /  AI 调用 ui_open_terminal           │
└───────────────────────────┬─────────────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────────────┐
│                      QuroTerminalController                         │
│  会话管理 · 命令路由 · proot/设备shell 自动选择 · 超时控制            │
└───┬──────────────────────┬──────────────────────┬───────────────────┘
    │                      │                      │
┌───▼───────────┐  ┌───────▼────────┐  ┌─────────▼──────────────────┐
│QuroShellSession│  │QuroLinuxEnv    │  │QuroTerminalSessionManager  │
│PTY 会话载体    │  │proot+Ubuntu    │  │多会话管理·跨进程访问        │
│/dev/ptmx      │  │24.04 ARM64     │  │默认/额外/UI/历史会话        │
│fork/exec      │  │rootfs下载/解压  │  │会话隔离·状态同步            │
└───┬───────────┘  └───────┬────────┘  └─────────┬──────────────────┘
    │                      │                      │
┌───▼──────────────────────▼──────────────────────▼───────────────────┐
│                       前台服务层（保活）                               │
│   Qu roTerminalKeepAliveService · Qu roTerminalAciService            │
│   specialUse 前台服务 · shell 子进程归属服务进程 · 15 秒巡检          │
└───┬──────────────────────┬──────────────────────┬───────────────────┘
    │                      │                      │
┌───▼───────────┐  ┌───────▼────────┐  ┌─────────▼──────────────────┐
│ACI 跨进程     │  │Intent/Provider │  │BroadcastReceiver/DeepLink  │
│12 个能力      │  │ContentProvider │  │6 个广播 Action              │
│AIDL 绑定     │  │content:// URI  │  │quro://terminal/...         │
└───────────────┘  └────────────────┘  └────────────────────────────┘
```

### 2.2 设计原则

| 原则 | 说明 |
|------|------|
| **进程归属** | shell 子进程必须归属前台服务进程，服务存活 = 终端存活 |
| **环境自动选择** | 优先使用 proot Linux 环境，不可用时自动降级到设备 shell |
| **多会话隔离** | 每个终端会话独立运行，互不干扰 |
| **跨进程标准化** | 通过 ACI 协议和标准 Android IPC 暴露能力 |
| **Android 版本兼容** | 支持 Android 8.0（API 26）到 Android 15+ |

### 2.3 技术选型

| 技术 | 选择 | 原因 |
|------|------|------|
| **Linux 用户空间** | proot + Ubuntu 24.04 ARM64 | 无需 ROOT，完整 Ubuntu 工具链 |
| **伪终端** | `/dev/ptmx` PTY | 标准 Linux 伪终端，支持交互式 shell |
| **前台服务** | `specialUse` 类型 | Android 14+ 兼容，无需真实数据同步活动 |
| **跨进程通信** | ACI AIDL + HTTP + MCP | 多种调用方式，适配不同场景 |
| **IPC 接入** | Provider + DeepLink + Intent + Broadcast | 标准 Android IPC，无需特殊权限 |
| **会话管理** | SessionManager 单例 | 统一管理所有会话，支持跨进程访问 |

---

## 3. 核心组件详解

### 3.1 QuroTerminalController — 终端控制器

**文件位置**：`app/src/main/java/com/ai/assistance/quro/core/terminal/QuroTerminalController.kt`

**职责**：终端的核心控制器，负责命令路由、环境选择、超时控制。

**核心能力**：

```kotlin
// 命令执行入口
fun runCommand(command: String, timeout: Long = 14000): String {
    val env = QuroLinuxEnv.getInstance(context)
    return if (env.isReady()) {
        // Linux 环境可用 → 使用 proot
        runCommandInLinux(command, timeout)
    } else {
        // 回退到设备 shell
        runCommandInDeviceShell(command, timeout)
    }
}
```

**关键特性**：
- **环境自动检测**：检查 `QuroLinuxEnv.isReady()`，自动选择 proot 或设备 shell
- **超时控制**：默认 14 秒超时，防止命令挂起
- **错误处理**：捕获所有异常，返回可读错误信息
- **进程管理**：管理子进程生命周期，支持中断执行

### 3.2 QuroShellSession — PTY Shell 会话载体

**文件位置**：`app/src/main/java/com/ai/assistance/quro/core/terminal/QuroShellSession.kt`

**职责**：PTY 伪终端的完整实现，管理 shell 进程的创建、通信和销毁。

**核心能力**：

```kotlin
// 会话创建
companion object {
    suspend fun create(
        context: Context,
        env: Map<String, String>,
        name: String = "default",
        onOutput: (String) -> Unit
    ): QuroShellSession {
        // 1. 打开伪终端主设备
        val masterFd = Os.open("/dev/ptmx", O_RDWR or O_NOCTTY)
        // 2. 授权并解锁从设备
        Os.grantpt(masterFd)
        Os.unlockpt(masterFd)
        // 3. 获取从设备名
        val slaveName = Os.slavename(masterFd)
        // 4. 打开从设备
        val slaveFd = Os.open(slaveName, O_RDWR or O_NOCTTY)
        // 5. 设置窗口大小
        val winsize = Winsize(24, 80, 0, 0)
        Os.ioctl(masterFd, TIOCSWINSZ, winsize)
        // 6. fork 子进程
        val pid = fork()
        if (pid == 0) {
            // 子进程：重定向标准 I/O 到从设备
            Os.dup2(slaveFd, 0)
            Os.dup2(slaveFd, 1)
            Os.dup2(slaveFd, 2)
            Os.execve("/bin/sh", arrayOf("/bin/sh"), envp)
        }
        // 7. 父进程：创建会话对象
        return QuroShellSession(masterFd, pid, name, onOutput)
    }
}
```

**关键特性**：
- **PTY 伪终端**：标准 `/dev/ptmx` 实现，支持交互式 shell
- **进程控制**：`fork/exec` 创建子进程，`TIOCSWINSZ` 设置窗口大小
- **输出流读取**：异步读取子进程输出，通过回调传递给 UI
- **会话状态**：跟踪进程 PID、运行状态、启动时间
- **会话销毁**：发送 SIGTERM/SIGKILL 信号，清理资源

### 3.3 QuroTerminalSessionManager — 多会话管理器

**文件位置**：`app/src/main/java/com/ai/assistance/quro/core/terminal/QuroTerminalSessionManager.kt`

**职责**：统一管理所有终端会话，支持多会话并发和跨进程访问。

**核心能力**：

```kotlin
// 会话管理器（单例）
object QuroTerminalSessionManager {
    // 会话存储
    private val sessions = mutableMapOf<String, QuroShellSession>()

    // 创建会话
    suspend fun createSession(
        context: Context,
        name: String = "session_${System.currentTimeMillis()}",
        installIfMissing: Boolean = false
    ): QuroShellSession {
        val env = QuroLinuxEnv.getInstance(context)
        if (installIfMissing && !env.isReady()) {
            env.ensureInstalled(context)
        }
        val session = QuroShellSession.create(context, env.getEnv(), name) { output ->
            // 输出回调
        }
        sessions[session.id] = session
        return session
    }

    // 获取会话
    fun getSession(sessionId: String): QuroShellSession? = sessions[sessionId]

    // 列出所有会话
    fun listSessions(): List<Map<String, Any>> {
        return sessions.map { (id, session) ->
            mapOf(
                "id" to id,
                "name" to session.name,
                "is_alive" to session.isAlive(),
                "pid" to session.pid,
                "uptime" to session.getUptime()
            )
        }
    }

    // 销毁会话
    fun destroySession(sessionId: String): Boolean {
        val session = sessions.remove(sessionId) ?: return false
        session.destroy()
        return true
    }
}
```

**会话类型**：

| 类型 | 说明 | 生命周期 |
|------|------|----------|
| **默认会话** | 主终端界面使用的会话 | 前台服务保活，最长生命周期 |
| **额外会话** | 用户手动创建的会话 | 跟随应用进程 |
| **UI 会话** | 终端 UI 界面的会话 | 跟随 UI 生命周期 |
| **历史会话** | 已结束的会话记录 | 仅保留状态信息 |

### 3.4 QuroLinuxEnv — Linux 环境后端

**文件位置**：`app/src/main/java/com/ai/assistance/quro/core/linux/QuroLinuxEnv.kt`

**职责**：管理 proot + Ubuntu 24.04 ARM64 用户空间，提供 Linux 环境的安装、检测和配置。

**核心能力**：

```kotlin
class QuroLinuxEnv(private val context: Context) {
    // 路径配置（全动态，无 hardcode）
    private val rootfsPath = File(context.filesDir, "linux-sandbox/rootfs")
    private val prootPath = "${context.applicationInfo.nativeLibraryDir}/libproot.so"
    private val homePath = context.getExternalFilesDir(null)

    // 检测 Linux 环境是否就绪
    fun isReady(): Boolean {
        return rootfsPath.exists() &&
               File(rootfsPath, "usr/bin/sh").exists() &&
               File(prootPath).exists()
    }

    // 安装 Linux 环境（下载 rootfs）
    suspend fun ensureInstalled(context: Context) {
        if (isReady()) return
        // 从 Ubuntu 官方镜像下载 rootfs
        downloadRootfs(context)
        // 解压 rootfs
        extractRootfs(context)
        // 配置 apt 源
        configureAptSources()
    }

    // 获取 proot 启动参数
    fun getProotArgs(): List<String> {
        return listOf(
            "-0", "root",
            "--link2symlink",
            "-w", "/root",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=/dev",
            "--bind=/sdcard:/mnt/sdcard"
        )
    }

    // 获取环境变量
    fun getEnv(): Map<String, String> {
        return mapOf(
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8",
            "TMPDIR" to "/tmp"
        )
    }
}
```

**技术细节**：

| 项目 | 说明 |
|------|------|
| **rootfs 格式** | `ubuntu-noble-aarch64-pd-v4.18.0.tar.xz`（Ubuntu 24.04 Noble ARM64） |
| **rootfs 大小** | 约 80MB（压缩后），解压后约 300MB |
| **下载源** | Ubuntu 官方镜像（aliyun / tuna / cdimage） |
| **内置工具** | `proot`（`.so` 形式）、`libbash`、`libbusybox` |
| **路径** | `rootfsPath=File(context.filesDir,"linux-sandbox")`，全动态无 hardcode |

### 3.5 Qu roTerminalKeepAliveService — 前台保活服务

**文件位置**：`app/src/main/java/com/ai/assistance/quro/service/QuroTerminalKeepAliveService.kt`

**职责**：以前台服务身份存活，让终端会话脱离 UI 生命周期，息屏/切 App 不被杀。

**核心原理**：

```
前台服务调 startForeground()
    → 系统不杀这个进程
    → 进程内 fork 的 shell 子进程也不会被杀
    → 息屏/切 App 不死
```

**核心代码**：

```kotlin
class Qu roTerminalKeepAliveService : Service() {
    private var heldSession: QuroShellSession? = null  // 服务直接持有终端会话

    override fun onCreate() {
        // 启动前台服务（specialUse 类型）
        startForeground(NOTIF_ID, buildNotification("终端运行中…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        // 启动巡检循环
        startLoop()
    }

    private fun ensureSessionSafe() {
        // 在服务进程内创建 shell 子进程
        heldSession = QuroShellSession.create(
            context = this,
            env = QuroLinuxEnv.getInstance(this).getEnv(),
            name = "keepalive"
        ) { output ->
            // 输出回调
        }
        // shell 子进程是服务进程的 fork → 服务存活 = shell 子进程存活
    }

    private fun startLoop() {
        // 每 15 秒巡检
        coroutineScope.launch {
            while (isActive) {
                ensureSessionSafe()  // 确保会话存活
                ensureAciService()  // 确保 ACI 服务运行
                updateNotification() // 更新通知
                delay(15_000)       // 15 秒间隔
            }
        }
    }
}
```

**关键特性**：

| 特性 | 实现 |
|------|------|
| **前台服务类型** | `specialUse`（Android 14+ 兼容） |
| **巡检间隔** | 每 15 秒检查会话状态，死亡自动重建 |
| **通知栏** | 常驻「Zorv AI 终端运行中」，点击跳转主界面 |
| **会话所有权** | shell 子进程归属服务进程，服务存活 = 终端存活 |
| **ACI 服务管理** | 自动启动/重启 `QuroTerminalAciService` |

### 3.6 Qu roTerminalAciService — ACI 受控端服务

**文件位置**：`app/src/main/java/com/ai/assistance/quro/service/QuroTerminalAciService.kt`

**职责**：继承 `BaseAidlAciService`，暴露终端全部能力给外部应用调用。

**12 个 ACI 能力**：

| 能力 | 入参 | 返回 | 说明 |
|------|------|------|------|
| `exec` | `command`(必填) / `timeout`(可选) / `session_id`(可选) | `output` / `exit_code` / `error` | 执行命令 |
| `create_session` | `name`(可选) | `session_id` / `name` | 创建会话 |
| `destroy_session` | `session_id`(必填) | `destroyed` | 销毁会话 |
| `send_input` | `session_id`(必填) / `input`(必填) | `sent` | 发送输入 |
| `get_session_status` | `session_id`(必填) | `session_id` / `is_alive` / `pid` / `uptime` | 会话状态 |
| `list_sessions` | — | `sessions` (array) | 列出所有会话 |
| `set_session_env` | `session_id` / `key` / `value` | `set` | 设置环境变量 |
| `get_session_env` | `session_id` / `key` | `value` | 获取环境变量 |
| `list_capabilities` | — | `capabilities` (array) | 列出能力 |
| `get_service_status` | — | `running` / `session_count` / `uptime` | 服务状态 |
| `get_audit_log` | `limit`(可选) | `logs` (array) | 审计日志 |
| `help` | — | `help_text` | 帮助信息 |

**详细文档**：[ACI 技术架构与开发手册](./ACI_DEVELOPER_GUIDE.md)

---

## 4. Linux 沙箱（proot + Ubuntu 24.04 ARM64）

### 4.1 架构原理

```
┌─────────────────────────────────────────┐
│           Android 应用进程                │
│                                         │
│  ┌─────────────────────────────────┐    │
│  │         proot 进程               │    │
│  │  ┌─────────────────────────┐    │    │
│  │  │    Ubuntu 24.04 ARM64   │    │    │
│  │  │    rootfs 用户空间       │    │    │
│  │  │                         │    │    │
│  │  │  /bin/sh  ← 终端 shell  │    │    │
│  │  │  /usr/bin/python3       │    │    │
│  │  │  /usr/bin/apt           │    │    │
│  │  │  /usr/bin/bash          │    │    │
│  │  └─────────────────────────┘    │    │
│  │                                 │    │
│  │  --bind=/proc  ← 挂载 proc     │    │
│  │  --bind=/sys   ← 挂载 sys      │    │
│  │  --bind=/dev   ← 挂载 dev      │    │
│  │  --link2symlink ← 符号链接兼容  │    │
│  └─────────────────────────────────┘    │
│                                         │
│  proot 路径: nativeLibraryDir/libproot.so │
│  rootfs: filesDir/linux-sandbox/rootfs  │
└─────────────────────────────────────────┘
```

### 4.2 rootfs 管理

| 阶段 | 说明 |
|------|------|
| **首次使用** | 检测到 `rootfsPath` 不存在，自动触发下载 |
| **下载源** | Ubuntu 官方镜像（aliyun / tuna / cdimage） |
| **下载格式** | `ubuntu-noble-aarch64-pd-v4.18.0.tar.xz`（约 80MB） |
| **解压位置** | `context.filesDir/linux-sandbox/rootfs/` |
| **解压工具** | `busybox tar xf`（内置） |
| **后续使用** | 检测到 rootfs 存在，直接挂载启动 |

### 4.3 内置工具链

| 工具 | 说明 | 来源 |
|------|------|------|
| `proot` | 用户空间模拟 | 内置为 `libproot.so`（`nativeLibraryDir`） |
| `bash` | Bash shell | 内置为 `libbash.so` |
| `busybox` | POSIX 工具集 | 内置为 `libbusybox.so` |
| `python3` | Python 3 运行时 | rootfs 内置（Ubuntu apt 安装） |
| `apt` | 包管理器 | rootfs 内置（Ubuntu apt 安装） |
| `gcc` | C 编译器 | rootfs 可选安装（`apt install gcc`） |

### 4.4 CMS 运行时集成

CMS 引擎通过 `bootstrap.sh` 脚本提供共享运行时：

```bash
# CMS 运行时安装
bootstrap.sh --install NODE     # Node.js
bootstrap.sh --install PYTHON   # Python
bootstrap.sh --install RUST     # Rust
bootstrap.sh --install GO       # Go
bootstrap.sh --install JAVA     # Java
bootstrap.sh --install SSH      # OpenSSH
```

**运行时共享**：所有终端会话共享同一套 CMS 运行时，无需重复安装。

---

## 5. 前台服务保活机制

### 5.1 为什么需要前台服务

Android 系统会在以下情况杀死后台进程：
- **内存不足**：系统会回收低优先级进程
- **电量优化**：Doze 模式限制后台活动
- **用户手动清理**：从最近任务列表滑动清除
- **应用切换**：切到其他应用后，原应用可能被杀死

**前台服务**是 Android 提供的最高优先级服务类型，系统几乎不会杀死前台服务进程。因此，让终端会话归属于前台服务进程，就能保证息屏/切 App 不被杀。

### 5.2 specialUse 类型选择

Android 14+ 对前台服务类型有严格限制：

| 类型 | 限制 | 适用场景 |
|------|------|----------|
| `dataSync` | 必须有真实数据同步活动 | 文件同步、云备份 |
| `mediaPlayback` | 必须正在播放媒体 | 音乐播放器 |
| `location` | 必须正在获取位置 | 导航应用 |
| `specialUse` | 需要 `<property>` 标签说明用途 | **终端保活**（我们的选择） |

**选择 `specialUse` 的原因**：
1. 终端保活不属于 dataSync（没有真实数据同步）
2. `specialUse` 需要 `<property>` 标签说明用途，Google Play 审核时会检查
3. 添加 `<property>` 后，`startForeground()` 不会被系统静默拒绝

### 5.3 会话所有权模型

```
传统模型（不保活）：
App 进程 → fork shell 子进程 → App 被杀 → 子进程也被杀

前台服务模型（保活）：
App 进程 → 前台服务 → fork shell 子进程
    ↑
    系统不杀这个进程
    → shell 子进程也不会被杀
    → 息屏/切 App 不死
```

**关键代码**：

```kotlin
// Qu roTerminalKeepAliveService.kt
private var heldSession: QuroShellSession? = null

private fun ensureSessionSafe() {
    // 在服务进程内创建 shell 子进程
    heldSession = QuroShellSession.create(
        context = this,
        env = QuroLinuxEnv.getInstance(this).getEnv(),
        name = "keepalive"
    ) { output ->
        // 输出回调
    }
    // shell 子进程是服务进程的 fork
    // 服务存活 = shell 子进程存活
}
```

### 5.4 巡检与自愈机制

```kotlin
private fun startLoop() {
    coroutineScope.launch {
        while (isActive) {
            // 1. 检查会话是否存活
            val session = heldSession
            if (session == null || !session.isAlive()) {
                Log.w(TAG, "会话已死亡，重建中…")
                ensureSessionSafe()
            }

            // 2. 检查 ACI 服务是否运行
            ensureAciService()

            // 3. 更新通知
            updateNotification()

            // 4. 等待 15 秒
            delay(15_000)
        }
    }
}
```

### 5.5 Android 14+ 兼容性

**Manifest 配置**：

```xml
<!-- 权限声明 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<!-- 服务声明 -->
<service
    android:name=".service.QuroTerminalKeepAliveService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="终端会话保活：保持终端会话在息屏/切换应用时不被杀死" />
</service>
```

**启动代码**：

```kotlin
override fun onCreate() {
    super.onCreate()
    try {
        startForeground(
            NOTIF_ID,
            buildNotification("终端运行中…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    } catch (e: Throwable) {
        Log.e(TAG, "前台通知创建失败", e)
        stopSelf()
        return
    }
}
```

### 5.6 开机自启动

```kotlin
// Qu roTerminalBootReceiver.kt
class Qu roTerminalBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 开机自启动前台服务
            val serviceIntent = Intent(context, Qu roTerminalKeepAliveService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
```

**Manifest 注册**：

```xml
<receiver
    android:name=".service.Qu roTerminalBootReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

---

## 6. PTY 伪终端实现

### 6.1 伪终端工作原理

```
┌─────────────────────────────────────────────────┐
│                  终端 UI 进程                     │
│                                                 │
│  ┌─────────────────────────────────────────┐    │
│  │         master fd（主设备）               │    │
│  │  /dev/ptmx                              │    │
│  └──────────────────┬──────────────────────┘    │
│                     │                           │
│                     │ 内核伪终端驱动              │
│                     │                           │
│  ┌──────────────────▼──────────────────────┐    │
│  │         slave fd（从设备）                │    │
│  │  /dev/pts/N                             │    │
│  └──────────────────┬──────────────────────┘    │
│                     │                           │
│                     │ dup2 重定向                │
│                     │                           │
│  ┌──────────────────▼──────────────────────┐    │
│  │         shell 子进程                     │    │
│  │  /bin/sh                                │    │
│  │  stdin  ← slave fd                      │    │
│  │  stdout → slave fd                      │    │
│  │  stderr → slave fd                      │    │
│  └─────────────────────────────────────────┘    │
└─────────────────────────────────────────────────┘
```

### 6.2 核心代码实现

```kotlin
// QuroShellSession.kt 核心创建逻辑
val masterFd = Os.open("/dev/ptmx", O_RDWR or O_NOCTTY)
val slaveName = Os.slavename(masterFd)
Os.grantpt(masterFd)
Os.unlockpt(masterFd)
val slaveFd = Os.open(slaveName, O_RDWR or O_NOCTTY)

// 设置窗口大小
val winsize = Winsize(24, 80, 0, 0)
Os.ioctl(masterFd, TIOCSWINSZ, winsize)

// 创建新会话（必须）
Os.setsid()

// fork 子进程
val pid = Os.fork()
if (pid == 0) {
    // 子进程
    Os.dup2(slaveFd, 0)  // stdin
    Os.dup2(slaveFd, 1)  // stdout
    Os.dup2(slaveFd, 2)  // stderr
    Os.execve("/bin/sh", arrayOf("/bin/sh"), envp)
}
```

### 6.3 输出流处理

```kotlin
// 异步读取输出
private fun readOutputLoop() {
    val buffer = ByteArray(4096)
    while (isRunning) {
        val bytesRead = Os.read(masterFd, buffer)
        if (bytesRead > 0) {
            val output = String(buffer, 0, bytesRead)
            onOutput(output)  // 回调给 UI
        } else if (bytesRead == 0) {
            break  // EOF
        }
    }
}
```

---

## 7. 跨进程接入方式

### 7.1 ContentProvider（TerminalProvider）

**Authority**：`content://com.ai.assistance.quro.terminal`

**支持的路径**：

| 路径 | 方法 | 说明 |
|------|------|------|
| `/sessions` | query | 列出所有会话 |
| `/exec?cmd=...` | query | 执行命令并返回结果 |
| `/status` | query | 获取服务状态 |
| `/session/{id}` | query | 获取指定会话信息 |

**使用示例**：

```kotlin
// 列出会话
val cursor = contentResolver.query(
    Uri.parse("content://com.ai.assistance.quro.terminal/sessions"),
    null, null, null, null
)

// 执行命令
val cursor = contentResolver.query(
    Uri.parse("content://com.ai.assistance.quro.terminal/exec?cmd=uname -a"),
    null, null, null, null
)
```

### 7.2 Deep Link（TerminalDeepLinkHandler）

**Scheme**：`quro://terminal/...`

**支持的路径**：

| 路径 | 说明 |
|------|------|
| `exec?cmd=...` | 执行命令 |
| `sessions` | 会话列表 |
| `create?name=...` | 创建会话 |
| `status` | 服务状态 |

**使用示例**：

```kotlin
// 执行命令
val intent = Intent(Intent.ACTION_VIEW,
    Uri.parse("quro://terminal/exec?cmd=python3 --version"))
startActivity(intent)

// 创建会话
val intent = Intent(Intent.ACTION_VIEW,
    Uri.parse("quro://terminal/create?name=my-session"))
startActivity(intent)
```

### 7.3 Intent Handler（TerminalIntentHandler）

**支持的 Action**：

| Action | Extra | 说明 |
|--------|-------|------|
| `com.ai.assistance.quro.action.TERMINAL_EXEC` | `command` | 执行命令 |
| `com.ai.assistance.quro.action.TERMINAL_STATUS` | — | 获取状态 |
| `com.ai.assistance.quro.action.TERMINAL_SESSIONS` | — | 列出会话 |
| `com.ai.assistance.quro.action.TERMINAL_CREATE_SESSION` | `name` | 创建会话 |

**使用示例**：

```kotlin
// 执行命令
val intent = Intent("com.ai.assistance.quro.action.TERMINAL_EXEC")
intent.putExtra("command", "ls -la /home")
sendBroadcast(intent)
```

### 7.4 BroadcastReceiver（TerminalBroadcastReceiver）

**支持的 Action（6 个）**：

| Action | Extra | 返回 |
|--------|-------|------|
| `TERMINAL_EXEC` | `command` | `output` / `exit_code` / `error` |
| `TERMINAL_STATUS` | — | `running` / `session_count` |
| `TERMINAL_SESSIONS` | — | `sessions` (array) |
| `TERMINAL_CREATE_SESSION` | `name` | `session_id` |
| `TERMINAL_DESTROY_SESSION` | `session_id` | `destroyed` |
| `TERMINAL_SEND_INPUT` | `session_id` / `input` | `sent` |

**使用示例**：

```kotlin
// 发送广播并接收结果
val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val output = intent.getStringExtra("output")
        val exitCode = intent.getIntExtra("exit_code", -1)
        Log.d("Terminal", "Output: $output, Exit: $exitCode")
    }
}

registerReceiver(receiver, IntentFilter("com.ai.assistance.quro.action.TERMINAL_RESULT"))

val intent = Intent("com.ai.assistance.quro.action.TERMINAL_EXEC")
intent.putExtra("command", "echo hello")
sendBroadcast(intent)
```

### 7.5 对比与选型

| 方式 | 适用场景 | 优点 | 缺点 |
|------|----------|------|------|
| **ContentProvider** | 数据查询、跨应用数据共享 | 标准 Android API，支持 CRUD | 不适合长时间运行的命令 |
| **Deep Link** | 用户点击链接触发操作 | 直观，支持 URL 分享 | 不适合后台调用 |
| **Intent** | 应用间简单通信 | 简单，支持 extras | 结果返回需要额外机制 |
| **BroadcastReceiver** | 异步通知、事件驱动 | 解耦，支持一对多 | 结果返回需要额外注册 |
| **ACI** | 复杂跨进程调用 | 12 个标准化能力，支持 AIDL/HTTP/MCP | 需要绑定服务 |

---

## 8. 命令执行路由

### 8.1 环境自动检测

```kotlin
// QuroTerminalController.kt
fun runCommand(command: String, timeout: Long = 14000): String {
    val env = QuroLinuxEnv.getInstance(context)
    return if (env.isReady()) {
        // Linux 环境可用 → 使用 proot
        runCommandInLinux(command, timeout)
    } else {
        // 回退到设备 shell
        runCommandInDeviceShell(command, timeout)
    }
}
```

### 8.2 proot 命令构建

```kotlin
private fun runCommandInLinux(command: String, timeout: Long): String {
    val prootPath = "${applicationInfo.nativeLibraryDir}/libproot.so"
    val prootArgs = QuroLinuxEnv.getInstance(context).getProotArgs()
    // 直接使用 prootArgs + 命令，不重复添加参数
    val fullCommand = listOf(prootPath) + prootArgs + listOf("/bin/sh", "-c", command)

    val process = ProcessBuilder(fullCommand)
        .redirectErrorStream(true)
        .start()

    // 超时控制
    val completed = process.waitFor(timeout, TimeUnit.MILLISECONDS)
    if (!completed) {
        process.destroyForcibly()
        throw TimeoutException("命令执行超时: $command")
    }

    return process.inputStream.bufferedReader().readText()
}
```

### 8.3 超时与错误处理

```kotlin
// 超时控制
val completed = process.waitFor(timeout, TimeUnit.MILLISECONDS)
if (!completed) {
    process.destroyForcibly()
    throw TimeoutException("命令执行超时: $command")
}

// 错误处理
try {
    val result = runCommand(command, timeout)
    return mapOf(
        "output" to result,
        "exit_code" to 0,
        "error" to ""
    )
} catch (e: TimeoutException) {
    return mapOf(
        "output" to "",
        "exit_code" to -1,
        "error" to "命令执行超时"
    )
} catch (e: Exception) {
    return mapOf(
        "output" to "",
        "exit_code" to -1,
        "error" to e.message ?: "未知错误"
    )
}
```

---

## 9. 终端 UI 集成

### 9.1 入口方式

| 入口 | 说明 |
|------|------|
| **对话框输入框「+」** | 点击输入框左侧「+」按钮，选择「终端」 |
| **AI 调用 `ui_open_terminal`** | AI 在对话中主动打开终端界面 |
| **Deep Link** | `quro://terminal/exec?cmd=...` 直接启动 |
| **ACI 跨进程调用** | 其他应用通过 ACI 协议调用终端能力 |

### 9.2 会话切换

```kotlin
// QuroTerminalSessionManager.kt
// 创建新会话
val newSession = QuroTerminalSessionManager.createSession(context, "my-session")

// 切换到指定会话
val session = QuroTerminalSessionManager.getSession("session-id")

// 销毁会话
QuroTerminalSessionManager.destroySession("session-id")
```

### 9.3 输入输出渲染

终端 UI 使用 Jetpack Compose 实现：

```kotlin
@Composable
fun TerminalScreen() {
    val output = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        // 启动终端会话
        val session = QuroTerminalSessionManager.createSession(context) { line ->
            output.add(line)
        }
    }

    LazyColumn {
        items(output) { line ->
            Text(
                text = line,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
    }
}
```

---

## 10. 关键特性总结

| 特性 | 状态 | 说明 |
|------|------|------|
| **真实用户空间** | ✅ | Ubuntu 24.04 ARM64，完整的 Linux 工具链 |
| **PTY 伪终端** | ✅ | `/dev/ptmx` + `fork/exec` + `TIOCSWINSZ` |
| **前台服务保活** | ✅ | `specialUse` 类型，息屏/切 App 不被杀 |
| **ACI 跨进程** | ✅ | 12 个能力，AIDL/HTTP/MCP 三种调用方式 |
| **Intent/Provider** | ✅ | ContentProvider + Deep Link + Intent + BroadcastReceiver |
| **多会话支持** | ✅ | 默认/额外/UI/历史会话，会话隔离 |
| **开机自启动** | ✅ | `BOOT_COMPLETED` 广播接收器 |
| **Android 14+ 兼容** | ✅ | `specialUse` + `<property>` 标签 |
| **proot 沙箱** | ✅ | 无需 ROOT，`link2symlink` 符号链接 |
| **CMS 运行时** | ✅ | NODE/PYTHON/RUST/GO/JAVA 共享环境 |
| **超时控制** | ✅ | 默认 14 秒超时，防止命令挂起 |
| **错误处理** | ✅ | 完善的异常捕获和错误返回 |

---

## 11. 常见问题与故障排除

| 现象 | 说明 / 处理 |
|------|-------------|
| **终端息屏/切 App 后被杀** | 确认前台服务已启动：通知栏应显示「Zorv AI 终端运行中」。检查 AndroidManifest 中 `foregroundServiceType="specialUse"` 和 `<property>` 标签 |
| **终端 ACI 跨进程调用失败** | 检查 `QuroTerminalAciService` 是否在 Manifest 中注册，权限 `ai.aci.permission.CALL` 是否声明 |
| **终端 Intent/Provider 不响应** | 检查 `TerminalProvider`、`TerminalBroadcastReceiver`、`TerminalDeepLinkHandler` 是否在 Manifest 中注册 |
| **终端会话状态不一致** | `QuroTerminalSessionManager` 管理多会话，调用 `listSessions()` 获取真实状态 |
| **终端命令执行报 Illegal option -0** | proot 参数重复问题，更新到 v1.0.67+ 已修复 |
| **应用内 Linux（L5）无法运行** | 首次进入终端会提示「安装 Linux 环境」，rootfs 需联网从 Ubuntu 官方镜像下载 |
| **命令执行超时** | 默认 14 秒超时，可通过 `timeout` 参数调整 |
| **会话输出乱码** | 检查 `TERM` 环境变量是否设置为 `xterm-256color` |

---

## 12. 开发指南

### 12.1 环境准备

- **Android Studio**：2024.1+ (Koala)
- **JDK**：17+
- **Android SDK**：compileSdk 36, minSdk 26, targetSdk 34
- **Gradle**：8.13+
- **Kotlin**：2.3+

### 12.2 从源码构建

```bash
# 克隆仓库
git clone https://github.com/Quor-a/ZorvAI.git
cd ZorvAI

# 构建 full release APK
./gradlew :app:assembleFullRelease

# 输出路径
# app/build/outputs/apk/full/release/app-full-release.apk
```

### 12.3 添加新的终端能力

**步骤 1**：在 `QuroTerminalAciService.kt` 的 `onCreateCapabilities` 中添加能力定义

```kotlin
override fun onCreateCapabilities(): List<AidlAciCapability> {
    val caps = super.onCreateCapabilities().toMutableList()
    caps.add(AidlAciCapability("my_new_capability", "我的新能力"))
    return caps
}
```

**步骤 2**：在 `onCall` 中添加能力处理逻辑

```kotlin
override fun onCall(request: AidlAciRequest): AidlAciResponse {
    return when (request.capability) {
        "my_new_capability" -> handleMyNewCapability(request.params)
        // ... 其他能力
    }
}
```

### 12.4 测试建议

**功能测试**：
- 测试 `exec` 能力：执行简单命令、复杂命令、带超时的命令
- 测试 `list_sessions` 能力：查看会话状态
- 测试 `help` 能力：获取帮助信息

**跨进程测试**：
- 测试其他应用通过 ACI 调用终端
- 测试 Intent/Provider/BroadcastReceiver 接入

**前台服务测试**：
- 测试息屏后服务是否持续运行
- 测试切 App 后服务是否持续运行
- 测试通知栏是否显示「终端运行中」

**Linux 环境测试**：
- 测试 proot 环境是否正常工作
- 测试设备 shell 回退机制
- 测试 rootfs 下载和解压

---

## 13. 开源信息

| 项目 | 信息 |
|------|------|
| **开源地址** | [https://github.com/Quor-a/ZorvAI](https://github.com/Quor-a/ZorvAI) |
| **ACI 开发者手册** | [docs/ACI_DEVELOPER_GUIDE.md](./ACI_DEVELOPER_GUIDE.md) |
| **当前版本** | v1.0.67 |
| **许可证** | Apache License 2.0 |
| **技术栈** | Kotlin 2.3 + Jetpack Compose |
| **最低支持** | Android 8.0 (API 26) |
| **目标版本** | Android 14 (API 34) |

---

> 本文档由 Zorv AI 开发团队维护。如有问题或建议，请在 [GitHub Issues](https://github.com/Quor-a/ZorvAI/issues) 反馈。
