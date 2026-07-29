# 终端设置：Toast 跨线程崩溃 + proot/SSH 开关空实现

**日期**：2026-07-20
**场景**：调试复盘（崩溃修复 + 配置接通）
**参与成员**：排障手（调试与根因）
**版本**：v93（versionCode 93 / versionName 1.0.93）

> 说明：本环境 `gstack-*` 子智能体派发返回 "not available"，以下为软件工坊主理人依据排障手框架**直接执行 + 汇编**。

---

## 📌 TL;DR（执行摘要）

- **崩溃**：`QuroTerminalSettings.kt` 里 FTP 启动/停止、软件源、重置、SSH 保存等动作在 `scope.launch(Dispatchers.IO)` 内直接 `Toast.makeText().show()`，IO 线程无 Looper → `NullPointerException: Can't toast on a thread that has not called Looper.prepare()`。已用主 Looper 的 `Handler.post` 统一兜底，彻底规避。
- **配置空开关**：设置页「proot 模式」「SSH 启用」两个开关此前**只改本地 state、零持久化、零生效**。`QuroLinuxEnv` 里根本没有对应 getter/setter，终端也不读取。已补 `getLinuxMode/setLinuxMode`、`getSshEnabled/setSshEnabled` 并接通：
  - 「proot 模式」→ 终端 `useLinux` 的**持久默认值**（终端启动即读取）。
  - 「SSH 启用」→ 持久化；关闭时 best-effort 移除容器内 `~/.ssh/config`。
- 构建验证：v93 BUILD SUCCESSFUL（见末尾「构建与产物」）。
- **附带排查**：全工程其余 `Toast.makeText` 均在主线程（onClick / UI 回调）或已用 `Handler(Looper.getMainLooper()).post`（PluginsScreen），无同款隐患。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| 崩溃 | 🟢 已修复（主线程安全 Toast） |
| 配置空开关 | 🟢 已接通（proot 模式 + SSH 启用持久化并生效） |
| 严重度 | 🔴 崩溃 1 处（P0） / 🟠 配置空实现 2 处（P1） |
| 修改文件 | 3 个：QuroTerminalSettings.kt / QuroLinuxEnv.kt / QuroTerminalScreen.kt |
| 编译影响 | 纯逻辑修复，无破坏性改动 |
| 构建版本 | v93（1.0.93） |

---

## 1. 根因

### 1.1 Toast 跨线程崩溃
`QuroTerminalSettings.kt` 中多个动作包在 `scope.launch(Dispatchers.IO) { ... }` 内（FTP 启停、软件源应用、环境重置、SSH 保存）。这些协程跑在 `DefaultDispatcher-worker-N` 线程，而 `Toast.makeText().show()` **必须在有 Looper 的线程（主线程）调用**。在 IO 线程调用即抛：
```
java.lang.NullPointerException: Can't toast on a thread that has not called Looper.prepare()
  at android.widget.Toast.getLooper(Toast.java:188)
  at android.widget.Toast.<init>(Toast.java:173)
  at android.widget.Toast.makeText(Toast.java:518)
  at ...QuroTerminalSettingsKt$...invokeSuspend(QuroTerminalSettings.kt:157)
```
报告中的堆栈落在 157 行（FTP 启动**失败**分支），但**所有 IO 块里的 Toast 都会崩**（156/157/162/108/519/522/546）。

### 1.2 终端配置开关空实现
- 「proot 模式」开关（`prootModeEnabled`）仅 `mutableStateOf(false)` 初始化，`onCheckedChange = { prootModeEnabled = it }` —— 不落盘、不被终端读取。`QuroLinuxEnv` 中无 `getLinuxMode/setLinuxMode`。
- 终端 `QuroTerminalScreen` 的 `useLinux` 也硬编码 `mutableStateOf(false)`，不读任何持久化值；顶栏 Shell/Linux 胶囊仅是**会话内临时**切换。
- 「SSH 启用」开关（`sshEnabled`）同上，零持久化。
- 结果：用户拨动这两个开关，界面有反馈但**重启/重进后还原、且对终端实际行为无任何影响** → “终端配置没有实现功能”。

---

## 2. 修复

### 2.1 Toast 主线程安全（`QuroTerminalSettings.kt`）
新增文件级助手，统一经主 Looper 派发：
```kotlin
private fun showToast(ctx: Context, msg: String, duration: Int) {
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(ctx, msg, duration).show()
    }
}
```
并将全部 `Toast.makeText(ctx, …).show()` 改为 `showToast(ctx, …)`（共 11 处，含原本在主线程的复制/字体/按键反馈——统一走主 Looper 也安全）。新增 import：`android.os.Handler`、`android.os.Looper`。

### 2.2 接通 proot 模式（`QuroLinuxEnv.kt` + 两处 UI）
`QuroLinuxEnv.kt` 新增（沿用既有 `tprefs("quro_terminal_prefs")`）：
```kotlin
fun getLinuxMode(context: Context): Boolean = tprefs(context).getBoolean("linux_mode", false)
fun setLinuxMode(context: Context, v: Boolean) {
    tprefs(context).edit().putBoolean("linux_mode", v).apply()
}
```
- 设置页：`prootModeEnabled` 初始化为 `linux.getLinuxMode(ctx)`；`onCheckedChange` 调 `linux.setLinuxMode(ctx, it)`。
- 终端页：`useLinux` 初始化为 `QuroLinuxEnv.getLinuxMode(ctx)` —— 即设置页为该**默认模式**，顶栏 Shell/Linux 胶囊仍可在会话内临时覆盖。
- 顺手修正误导文案：关闭态由“已禁用 - 使用 proot 启动 Alpine”改为“已禁用 - 使用设备 Shell（默认）”。

### 2.3 接通 SSH 启用（`QuroLinuxEnv.kt` + 设置页）
`QuroLinuxEnv.kt` 新增：
```kotlin
fun getSshEnabled(context: Context): Boolean = tprefs(context).getBoolean("ssh_enabled", false)
fun setSshEnabled(context: Context, v: Boolean) {
    tprefs(context).edit().putBoolean("ssh_enabled", v).apply()
}
```
- 设置页：`sshEnabled` 初始化为 `linux.getSshEnabled(ctx)`；`onCheckedChange` 调 `linux.setSshEnabled(ctx, it)`，且**关闭时**在 IO 协程里 `rm -f /root/.ssh/config`（best-effort，结果忽略，使其真正“禁用 SSH”）。
- SSH 配置保存对话框 `onSave` 同步写 `linux.setSshEnabled(ctx, sshConfigured)`。

---

## ✅ 行动清单

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 真机验证：进入终端设置拨“proot 模式”→ 返回打开终端，默认进入 Linux 模式；重启 App 后默认值保持 | 用户 + 主理人 | P0 | 真机回归 |
| 2 | 真机验证：FTP 启动/停止、软件源应用、环境重置、SSH 保存均不再崩溃（Toast 正常弹出） | 用户 + 主理人 | P0 | 真机回归 |
| 3 | 真机验证：SSH 启用关闭后 `ssh quro` 失效（容器配置已移除），重新开启并保存配置后恢复 | 用户 + 主理人 | P1 | 真机回归 |

---

## ⚠️ 待完善 / 已知局限

- **FTP 常驻性**：`startFtp` 依赖一次性 `exec`（proot 容器命令返回即拆除），后台 FTP 进程在严苛 ROM 下可能无法长期存活（代码注释已注明，属既有架构限制，非本次引入）。
- **proot 模式生效依赖设备能力**：`useLinux=true` 时 `buildLinuxSession` 在 Android 16 SELinux enforcing 下可能失败并回退设备 Shell（终端会显示“Linux(proot) 暂不可用，已回退到设备 shell”）。属 best-effort，非缺陷。
- 未改动 `res/`、`assets/`、硬编码包名等，纯 Kotlin 逻辑修复。

---

## 📚 成员产出索引

- 排障手（调试与根因）产出：根因定位（Toast 跨线程 + 开关空实现）、11 处 Toast 改造、proot/SSH 持久化接通、全工程同类隐患排查。由主理人直接执行。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
