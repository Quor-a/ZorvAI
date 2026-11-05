# 双路径终端：默认 Termux，`zorv` 切 zorvAI 沙箱

> 状态：已实现，待真机验证。本轮**未** commit / tag / push。

## 1. 为什么做这个

设备上原本就并存两套 Linux，但用户只能摸到其中一套：

| 环境 | 落盘位置 | 包管理器 | 能力 |
|---|---|---|---|
| `termux` | `files/termux/usr`（Termux 官方 bootstrap） | `pkg` | Android 原生，无 systemd / 无 root，能拿到 `pkg` 全部生态 |
| `ubuntu` | `files/linux-sandbox/rootfs`（proot + Ubuntu 24.04） | `apt` | 桌面工具链齐全，跑在沙箱里 |

而**终端界面只起得来 Ubuntu**（`QuroShellSession.create → QuroLinuxEnv.shellLaunch`），
于是出现了那个反复被误解的现象：

> 「Termux 原生环境完全可用：`python` / `node` / `dd` 都跑得通」
> 「但 `pkg` command not found」

前半句是真的，但那是 **Ubuntu 里的 python/node**；`pkg` 是 Termux 的包管理器，
Ubuntu 里本来就没有。判断错了环境，结论就整个反了。

所以本轮把两条路径都放出来：**默认进 Termux，命令切沙箱**。

## 2. 关键难点：PTY 里怎么跑 Termux bash

`untrusted_app` 域不允许 exec 应用私有目录里的 ELF（见 `termux_bootstrap_fixes.md` 第 3 章），
所以 `files/termux/usr/bin/bash` 必须由 `/system/bin/linker64` 加载。

一次性执行通道（`termux` 工具的 `run`）走 `ProcessBuilder`，加个前缀就行；
但终端界面走的是 `QuroTerminalJNI.createSubprocess`（native `fork + execvp`），
它**不做 argv 补齐**：Java 侧传什么，`argv` 就是什么。

于是有了下面这条容易踩空的规则：

```
createSubprocess(cmd, cwd, args, env)  →  execvp(cmd, args)
```

- PTY：`args` 必须**含 `argv[0]`** → 传 `[cmd] + argv`
- 管道（`ProcessBuilder`）：列表首项既是程序、又被自动设为 `argv[0]` → 传 `[cmd] + argv.drop(1)`

而 linker 的语义是「`argv[1]` 才是要执行的 ELF」，所以最终 argv 必须是：

```
linked  : [ /system/bin/linker64, <prefix>/bin/bash, -l ]
direct  : [ <prefix>/bin/bash, -l ]
```

这条写在 `TermuxEnvironment.shellLaunch()` 里，PTY 与管道共用同一份参数，只差 `argv[0]` 的处理。

## 3. `zorv` 命令（原创）

不是 `proot-distro` 的翻版：脚本由 App 生成，模板在 `assets/zorv/zorv.sh.tmpl`，
**每次会话启动都按本机真实路径与当前 proot 参数重写**，因此换 rootfs 位置、
改 `--bind` 策略时脚本自动跟着变，不需要手工同步。

### 3.1 命令规格

```
zorv                进入 zorvAI 终端（Ubuntu 沙箱）
zorv termux         回 Termux（在沙箱里请直接 exit）
zorv status         两套环境体检：装没装、什么版本、当前在哪
zorv which CMD      一条命令属于哪套环境（pkg / apt / python3 / node …）
zorv help
```

`zorv which` 是专门治「`pkg: command not found`」这类困惑的：

```
$ zorv which pkg
  termux   /data/user/0/.../files/termux/usr/bin/pkg
提示：pkg 只在 Termux 侧，先 exit 回 Termux 再执行
```

`zorv status` 则直接告诉你在哪、另一套装没装。

### 3.2 为什么同时是「脚本」和「bash 函数」

- `$PREFIX/bin/zorv`（脚本）：给非交互 shell、其他 shell、脚本调用用；
- `~/.bashrc` + `~/.profile` 里 source 它并定义 `zorv()` 函数：**交互式 Termux 走这条**。

为什么要函数：脚本要靠 shebang 起 `$PREFIX/bin/bash`，而那个 bash 同样在私有目录，
必须由 termux-exec 改写 `execve` 才不 EACCES。函数是 bash 内部对象，**完全不经过 exec**，
交互场景最稳。两条路都留，任何一条挂了另一条兜住。

脚本末尾用 `if [ "${BASH_SOURCE[0]:-$0}" = "$0" ]` 区分「被执行」与「被 source」：
被 source 时只定义函数，不会一开终端就自动跳进沙箱。

### 3.3 进沙箱是真 exec

```bash
exec "$ZORV_PROOT" "${ZORV_PROOT_ARGS[@]}" /usr/bin/env ZORV_ENV=ubuntu /bin/bash -l
```

当前进程直接换成 proot，不留残壳，PTY 会话保持。回 Termux 用 `exit` ——
proot 无法反向脱壳，这是机制限制（不是偷懒）。

## 4. 回退链（绝对不会把终端开成空白）

```
Termux 原生  →  沙箱(PTY)  →  沙箱(管道)  →  设备 sh
```

`QuroShellSession.create` 逐级降级，任何一级抛异常都被吃掉。
只要 Termux 通道不通（bootstrap 没装 / linker 起来失败），自动回落沙箱，行为与升级前一致。

## 5. 涉及文件

| 文件 | 改动 |
|---|---|
| `core/termux/TermuxEnvironment.kt` | 新增 `shellLaunch()` / `ShellLaunch`：交互式启动参数（含 linker argv 规则）；`BOOTSTRAP_VERSION` 公开 |
| `core/termux/ZorvSwitch.kt` | 新增：命令生成、两侧安装、`~/.bashrc` / `~/.profile` 引导 |
| `assets/zorv/zorv.sh.tmpl` | 新增：命令模板（`fill()` 做占位符替换，并强制 LF 行尾） |
| `core/terminal/QuroShellSession.kt` | `ShellMode.TERMUX`、提示符 `zorv@termux:…`、`create()` 双路径 + `createTermux()` |
| `core/terminal/QuroTerminalPrefs.kt` | 新增 `defaultEnv`（默认 `termux`） |
| `core/terminal/QuroTerminalSessionManager.kt` | `Backend.TERMUX_NATIVE`；默认环境变化时重建会话；默认 Termux 时不再为「新建会话」装 rootfs |
| `core/terminal/QuroTerminalExport.kt` | 导出文案补 `TERMUX` |
| `test/.../ZorvSwitchTest.kt` | 新增 7 例：shebang、占位符全替换、proot 参数引号化、source 不误执行等 |

## 6. 已知限制（别当成 bug）

1. **反向切换要 `exit`**：proot 无法回到宿主，回 Termux 只能退出沙箱，会话随之中断。
   如需「无感切回」得让 UI 在 `onExit` 时自动重建会话，属下一轮。
2. **UI 设置项还没加**：现在切默认环境只能改 `QuroTerminalPrefs.defaultEnv`（默认 `termux`）。
   设置页开关随后补。
3. **`zorv` 依赖 proot 已安装**：沙箱没装时 `zorv` 会明确提示去「设置 → 功能」安装。
4. 沙箱侧的 `/usr/local/bin/zorv` 只在 rootfs 已有 `etc/` 时写入；rootfs 重装后下次开会话会补。

## 7. 真机自检

```
# 默认进来应当是 Termux
termux(action="status")          # 执行通道=system_linker，探针 ✅，体检 pkg=✅

# 终端界面提示符应当是 zorv@termux:~$
zorv            # 进 Ubuntu，提示符变成 root@…:/root#
zorv status     # 两套环境都 ✅
zorv which pkg  # 指向 Termux 侧并提示「先 exit」
exit            # 回 Termux（会话结束，重开终端即 Termux）

# 反例：在 Termux 里敲 apt / 在 Ubuntu 里敲 pkg 的困惑，用 zorv which 直接定位
```
