# 应用内终端与 Linux 沙箱

在 Android 免 ROOT 前提下，用 `proot` + Ubuntu 24.04 ARM64 提供一条可常驻、可被 AI 驱动、可被外部应用跨进程调用的 shell 会话管线。

## 1. 职责边界

**负责**
- 终端会话全生命周期：创建 / 列表 / 切换默认 / 销毁 / 跨重启历史。
- 命令路由：Linux 环境（proot + rootfs）优先，回退设备 `/system/bin/sh`（Toybox）。
- Linux 环境自身：proot 二进制定位、rootfs 安装与完整性校验、发行版与包管理器探测、apt 源切换。
- 保活与回收：前台服务常驻、15 秒巡检、开机自启、孤儿进程（phantom killer）清理。
- 对外 IPC：ACI 能力（AIDL / HTTP / MCP 桥）、ContentProvider、Deep Link、透明 Activity、BroadcastReceiver。
- 命令副作用分级守卫（SAFE / WRITABLE / DESTRUCTIVE）。

**不负责**
- 特权执行通道：ROOT / Shizuku / LSPosed 属 `QuroRootGateway` 与 `priv_exec` / `root_exec` 工具，本模块只在无特权时降级。
- 终端 UI 的键盘、手势、快照滚动：`terminal/vt/` 与 `core/novaterm/ui/` 自行承担。
- Python / Node 等语言运行时：属 `QuroLanguageRunner`、`PyEngine`、CMS 引擎。
- CMS 模块业务编排：`CmsTerminalRuntime` / `CmsTerminalDeployer` 只借用本模块的沙箱与文件读写。

## 2. 分层与关键类

| 层 | 文件 | 职责 |
|---|---|---|
| UI 层 | `app/.../core/novaterm/ui/TerminalScreen.kt`、`TerminalViewModel.kt` | Compose 终端界面与状态 |
| UI 层 | `app/.../terminal/vt/QuroTerminalPane.kt`、`QuroTerminalInputView.kt`、`VtParser.kt`、`TerminalSnapshot.kt` | VT100/xterm 渲染与键盘/鼠标/快照 |
| 接入层（工具） | `app/.../core/tools/QuroToolsTerminal.kt` | `terminal_exec` / `terminal_write` / `terminal_kill` / `terminal_status` / `terminal_interrupt` / `terminal_sessions` / `terminal_session_new` / `terminal_session_switch` / `terminal_session_kill` |
| 接入层（工具） | `app/.../core/tools/QuroToolsTerminalDrive.kt`、`QuroTerminalTool.kt` | 终端驱动与动作映射 |
| 门面层 | `app/.../core/terminal/QuroTerminalBridge.kt` | 执行 / 可见终端 / 环境探测 / rootfs 文件读写 / 包管理 的单一入口 |
| 控制层 | `app/.../core/terminal/QuroTerminalController.kt` | `ShellResult` 定义、命令路由、两阶段中断 |
| 会话层 | `app/.../core/terminal/QuroTerminalSessionManager.kt` | 默认 / 额外 / UI / 历史会话；`ensureDefault` 升级逻辑；持久化 `quro_terminal_sessions.json` |
| 会话层 | `app/.../core/terminal/QuroShellSession.kt` | 常驻 shell 进程、`ShellMode`、滚动缓冲区、VT 挂载、软/硬中断 |
| 会话层 | `app/.../core/terminal/QuroTerminalSentinel.kt` | 每会话随机哨兵 token 与 `<RS>QURO_DONE_<hex>:<exit>:<cwd><RS>` 解析 |
| 会话层 | `app/.../core/terminal/QuroShellCommandGuard.kt` | 命令副作用分级（正则白/黑名单） |
| 会话层 | `app/.../core/terminal/QuroTerminalPrefs.kt` | `use_pty` / `warn_destructive` / `require_destructive_confirm` |
| 环境层 | `app/.../core/linux/QuroLinuxEnv.kt` | `shellLaunch` / `shellEnv` / `rootfsPath` / `homePath` / `tmpPath` / `probeLenient` / `ensureInstalledBlocking` / 快照回滚 |
| 环境层 | `app/.../core/linux/CommandTranslator.kt`、`QuroPackageManager.kt`、`SourceManager.kt`、`MirrorSource.kt`、`QuroDesktopInstaller.kt` | 命令翻译、包管理器规格、镜像源管理 |
| 原生后端 | `app/.../core/terminal/QuroHostBridge.kt` | `libqurohost.so` 启动器与控制行协议 |
| 服务层 | `app/.../service/QuroTerminalKeepAliveService.kt` | `specialUse` 前台保活，15 秒巡检，失败降级 `DATA_SYNC` |
| 服务层 | `app/.../service/QuroTerminalAciService.kt` | 继承 `BaseAidlAciService`，暴露 26 个 ACI 能力 + 审计日志 |
| 服务层 | `app/.../receiver/QuroTerminalBootReceiver.kt` | `BOOT_COMPLETED` 自启 |
| 服务层 | `app/.../core/terminal/QuroTerminalReaper.kt` | `killTree` / `reapOrphans`（phantom killer） |
| IPC 层 | `app/.../core/terminal/TerminalProvider.kt` | `content://com.ai.assistance.quro.terminal/...` |
| IPC 层 | `app/.../core/terminal/TerminalDeepLinkHandler.kt` | `quro://terminal/...` |
| IPC 层 | `app/.../core/terminal/TerminalIntentActivity.kt`、`TerminalIntentHandler.kt` | 透明 Activity，8 个 Action |
| IPC 层 | `app/.../core/terminal/TerminalBroadcastReceiver.kt` | 7 个 Action + `TERMINAL_RESULT` 回传 |
| 终端 4.0 栈 | `terminal-core/.../TerminalManager.kt`、`runtime/TerminalEnvironment.kt`、`Pty.kt`、`TerminalSession.kt`、`domain/SessionManager.kt`、`OutputProcessor.kt`、`command/CommandDispatcher.kt` | 独立模块：rootfs 资产解压 + bash/busybox 符号链接 + PTY 会话 |

## 3. 数据流

```mermaid
flowchart TD
    A[用户输入 / AI terminal_exec / 外部 App IPC] --> B[QuroTerminalBridge]
    B -->|非交互 exec| C[QuroTerminalController.runCommand]
    C --> D[QuroShellCommandGuard.shouldBlock]
    D -->|DESTRUCTIVE 且未确认| E[拦截并回 blockReason]
    D -->|放行| F{QuroLinuxEnv.shellLaunch != null ?}
    F -->|是| G[proot --rootfs=... -0 -w /root /bin/sh -c]
    F -->|否| H[/system/bin/sh -c]
    G --> I[ShellResult 结构化返回]
    H --> I
    B -->|可见终端| J[QuroTerminalSessionManager.ensureDefault]
    J --> K[QuroShellSession 常驻 shell]
    K --> L[stdin 写命令 + 追加哨兵行]
    L --> M[drain 读 stdout/stderr]
    M --> N{命中哨兵?}
    N -->|否| O[追加到 lines / 喂 VT]
    N -->|是| P[解析 exitCode / cwd，复位 busy，打印提示符]
    Q[QuroTerminalKeepAliveService] -->|15s 巡检| J
    R[QuroTerminalReaper] -->|启动期扫 /proc| S[回收孤儿 proot 进程树]
```

## 4. 关键设计决策

**1. 常驻 shell + 哨兵协议，而不是每命令起进程**
- 为什么：保住 `cd` / `export` / 后台任务的会话连续性；一次性 `ProcessBuilder` 无法承载交互式程序。
- 踩过的坑：Termux `TerminalEmulator` 在 Compose 布局期因 `mRenderer.mFontWidth` 空指针崩溃，因此渲染改走自持 VT（`terminal/vt/TerminalScreen`），进程侧不再依赖 Termux 的终端模拟。

**2. 哨兵 token 每会话随机，格式用八进制 `\036`**
- 为什么：旧实现用固定 `QURO_DONE`，`echo QURO_DONE` / `grep -r QURO_DONE` 会误判「命令已结束」，导致 `lastExit` / `cwdState` 停留上一条命令的值——静默错误。
- 踩过的坑：`\x1e` 是 bash/GNU 扩展，dash 与部分 BusyBox `printf` 会原样输出 `x1e`；POSIX 只保证八进制 `\ddd`，故改用 `\036`。哨兵经 `>&2` 写出，绕开 stdout 行缓冲。

**3. proot 二进制必须从 `applicationInfo.nativeLibraryDir` 取执行权限**
- 为什么：Android 只在该目录对 `.so` 授予可执行位，这是早期「终端跑不起来」的根因。
- 兜底：`QuroLinuxEnv.findNativeLibWithAssetsFallback` 在 `nativeLibraryDir` 缺失时从 `assets/linux_env/proot` 解压到 `filesDir/native-libs` 并 `setExecutable(true, false)`。

**4. `/root` 绑定应用内部存储（`linux-sandbox/sandbox-home`）**
- 为什么：早期绑外部存储 `getExternalFilesDir`，外部 FUSE/sdcardfs 对 proot 子进程有写入与符号链接限制，容器内 `/root` 实际不可写。
- 踩过的坑：表现为 `python3 -m venv /root/cms-venv` 静默失败、CMS 引擎目录建不起来，用户侧只看到「Python 未注册」。改绑内部 ext4/f2fs 目录后，宿主 `homePath` 与容器 `/root` 仍是同一目录。

**5. `shellLaunch` 不再预调 `probe` 判「不可用」**
- 为什么：与一次性执行 `run` 的行为对齐——`run` 从不 probe，直接 launch 让 proot 自己报错。
- 踩过的坑：rootfs 安装中目录瞬时为空、或某些 ROM 的 SELinux 限制，会让 probe 误判，UI 终端静默回退 Toybox，而 AI 的 `run()` 正常 —— 这就是「AI 终端能用 Linux 命令，界面里的终端只能基础命令」的真机根因。

**6. `ensureDefault` 会把 DEVICE 默认会话透明升级为 LINUX**
- 为什么：开机保活服务在 proot 装好前用 `installIfMissing=false` 抢先建出 DEVICE 会话。
- 踩过的坑：旧的快速路径 `if (defaultEntry?.alive == true) return` 一旦锁成 DEVICE 就永不升级，终端界面永久停在基础 shell。现在在 `mutex` 内判断 `shell.mode == ShellMode.DEVICE` 且 proot 可用 → 销毁重建。

**7. 两阶段中断（软 ETX → 强杀重建）**
- 为什么：会话 stdin 是管道不是 PTY，内核不把 `^C` 转成 SIGINT，`ping`（无 `-c`）、`cat`（无参）只能靠杀进程停下。
- 重建前保存 `cwdState` 与 `lines`，新会话 `prependHistory` + `restoreCwd`，避免用户看到屏幕被清空以为崩了。

**8. 命令副作用分级放在两个入口**
- `QuroTerminalController.runCommand`（AI / ACI / CMS 程序化路径）：`DESTRUCTIVE` 默认拦截，需显式 `confirmed=true`。
- `QuroShellSession.sendCommand`（交互终端）：默认只警告，`QuroTerminalPrefs.requireDestructiveConfirm` 默认关，开启后走「挂起 → 再次发送同命令或 `confirm`」授权门。

**9. 前台服务保活的进程归属模型**
- 为什么：`startForeground()` 后系统不杀该进程，而 shell 子进程是服务进程 fork 出来的，服务存活即子进程存活。
- Android 14+ 用 `specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`；`specialUse` 失败会降级 `dataSync` 再失败才 `stopSelf`。

**10. `QuroTerminalReaper` 用「二进制路径 + rootfs 路径」双重身份判定**
- 为什么：App 崩溃/被杀后上一轮 proot 被 reparent 到 init，仍占资源并可能锁住 dpkg 锁或端口。
- 护栏：`rootPid <= 1` 或等于自身 pid 直接返回，避免自杀或误伤 init。

**11. proot 参数里的几个非显然项**
- `--link2symlink`：兼容 Android 的符号链接限制。
- `--bind=/proc/net:/proc/net`：修复容器内 `/proc/net/dev` 为空导致 glibc 默认路由解析失败，`apt-get` / `pip` / `npm` 报 Network unreachable。
- apt 源在 arm64 上必须走 `ubuntu-ports` 而非 `ubuntu`。

## 5. 对外接口 / 契约

**结构化结果**
```kotlin
data class ShellResult(val output: String, val exitCode: Int, val timedOut: Boolean, val error: String)
// success = error.isEmpty() && !timedOut && exitCode == 0
```

**会话模型**（`QuroTerminalSessionManager`）
```kotlin
enum class ShellMode { DEVICE, LINUX, VM }
enum class Backend { LINUX_PROOT, DEVICE_SH, VM_LINUX }
enum class Kind { DEFAULT, EXTRA, UI_TERMUX }
data class SessionInfo(id, name, kind, backend, isDefault, alive, createdAt)
```
持久化文件：`filesDir/quro_terminal_sessions.json`（只存元数据，进程重启后 `alive=false`）。

**AI 工具**（注册于 `QuroTool.kt` / `QuroToolRouter.kt`）
`terminal_exec` / `terminal_write` / `terminal_kill` / `terminal_status` / `terminal_interrupt` / `terminal_sessions` / `terminal_session_new` / `terminal_session_switch` / `terminal_session_kill`。
`terminal_exec` 返回 JSON：`{source, exit_code, success, timed_out, output[, error][, hint]}`。

**ACI 能力**（`QuroTerminalAciService`，源码实际 26 个）
终端 14 个：`exec` / `create_session` / `destroy_session` / `send_input` / `switch_session` / `send_interrupt` / `get_session_status` / `list_sessions` / `set_session_env` / `get_session_env` / `list_capabilities` / `get_service_status` / `get_audit_log` / `help`。
文件 4 个：`file_read` / `file_write` / `file_delete` / `file_list`。
包管理 8 个：`pkg_install` / `pkg_remove` / `pkg_update` / `pkg_upgrade` / `pkg_search` / `pkg_list` / `pkg_info` / `pkg_clean`。
调用方式：`bindService()` AIDL、本地 HTTP、`McpAciBridge` 转 MCP 工具。

**ContentProvider**（`TerminalProvider.AUTHORITY = "com.ai.assistance.quro.terminal"`）
`sessions`、`sessions/{id}`、`sessions/{id}/output`、`exec`（insert）、`status`、`capabilities`。

**Deep Link**（`TerminalDeepLinkHandler`，`SCHEME="quro"` / `HOST="terminal"`）
`quro://terminal/exec?cmd=...&timeout=...`、`/sessions`、`/sessions/{id}`、`/status`、`/create?name=...&mode=...`。

**Broadcast**（`TerminalBroadcastReceiver`）
7 个 Action：`TERMINAL_EXEC` / `TERMINAL_STATUS` / `TERMINAL_SESSIONS` / `TERMINAL_CREATE_SESSION` / `TERMINAL_DESTROY_SESSION` / `TERMINAL_SEND_INPUT` / `TERMINAL_GET_OUTPUT`；结果经 `TERMINAL_RESULT` 回传，extras 为 `output` / `exit_code` / `error`。

**Intent Activity**（`TerminalIntentActivity`）
上述 7 个 Action + `TERMINAL_PICK_SESSION`（ACTION_PICK）+ `ACTION_SEND`（分享文本到终端）+ `ACTION_VIEW`（Deep Link）。extras：`command` / `timeout` / `session_name` / `session_id` / `input` / `output_limit`。

**qurohost 行协议**（`QuroHostBridge`）
- Kotlin → qurohost：`US(0x1f) + "@qurohost "` 开头的行是控制命令（CMS / devenv），其余透传子 shell。
- qurohost → Kotlin：`US + "@qurohost-resp "` 开头的行是 JSON 控制响应；`resolveBinary` 返回 null 时整体回退旧直连路径。

## 6. 已知约束与待办

- **README 与源码不一致（待同步）**：`README.md` §4.5 写「12 个 ACI 能力」，源码已扩到 26 个；§4.3 写 rootfs 为 `assets/linux_env/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz`，实际该文件名只在 `terminal-core` 的 `TerminalEnvironment.UBUNTU_FILENAME` 中出现，主 app 栈（`QuroLinuxEnv`）是从 `UBUNTU_ROOTFS_MIRRORS` 联网下载 ubuntu-base 并解压。
- **DEVICE 回退模式能力缺失**：无 `apt-get` / `dpkg` / `python3` / `node`。检测到该模式时，`QuroShellSession` 会把 proot 缺失原因、`prootPath`、`rootfsPath` 条目数直接打进滚动缓冲区，便于自查。
- **真实 PTY 默认关闭**（`QuroTerminalPrefs.usePty = false`）：打开后经 `QuroTerminalJNI.createSubprocess` 把 shell 挂伪终端，`vim` / `top` / python REPL 可真正交互、SIGINT 可投递；否则软中断对不读 stdin 的命令无效。
- **chroot 模式需 `su`**：`isChrootAvailable` 为真时 `shellLaunch` 返回 `su -c <脚本>`，未 root 设备不可用。
- **共享存储 `/sdcard` 挂载受权限闸门**：Android 11+ 需 `MANAGE_EXTERNAL_STORAGE`，否则跳过挂载（宁可不挂，也不挂上却 `EACCES`）。
- **安装失败 60 秒冷却**（`setupCooldownMs`）：避免 UI 的自动修复 effect 与用户重试把设备打成「死循环装环境」。
- **两套终端栈并存**：主 app 栈（`QuroShellSession` + `QuroLinuxEnv`）与 `terminal-core`（终端 4.0，`TerminalManager` + `TerminalEnvironment` + PTY）目前都在仓内。会话管理器里 `registerUiSession` 登记的是 `Kind.UI_TERMUX`（Termux PTY），两套栈的会话不互通，需明确收敛路径。
- **`ShellMode.VM` 待核实**：`QuroVmEnv` 与 VM 分支在 `QuroShellSession` 中存在，但 `create()` 当前走「直连 proot」v1.0.70 行为，VM 路径是否仍启用需确认。
- **输出截断**：`QuroLinuxEnv.MAX_OUTPUT_LENGTH = 15000`，超长输出会被截断，构建日志类命令需注意。
