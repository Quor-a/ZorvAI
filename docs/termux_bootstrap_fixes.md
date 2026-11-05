# Termux 环境层修复记录（对照官方开源逐条核对）

> 对照对象：`D:\termux-app-official`（Termux 官方 app 源码）
> ＋ 内置 bootstrap `app/src/main/assets/termux-bootstrap-aarch64.zip`（官方 release 包 `bootstrap-2026.11.01-r1+apt.android-7`）
> 涉及代码：`app/src/main/java/com/ai/assistance/quro/core/termux/TermuxEnvironment.kt`

---

## 0. 先把「官方到底怎么跑起来」看清楚

官方 `TermuxInstaller.setupBootstrapIfNeeded()` 的顺序是：

1. 建 `$PREFIX.staging` → 解压 zip 到 staging（按条目建目录、写文件）；
2. 记录 `SYMLINKS.txt`（格式 `<目标>←<相对 PREFIX 的链接名>`），全部解压完再统一建软链；
3. 只对 `bin/`、`libexec`、`lib/apt/apt-helper`、`lib/apt/methods` 设 `0700`；
4. **`staging.renameTo(PREFIX)`** —— 原子切换，避免留下半套前缀；
5. 官方 `bin/login`（交互入口）负责：导出 `SHELL`、`TERMUX_MAIN_PACKAGE_FORMAT`、
   **`LD_PRELOAD=$PREFIX/lib/libtermux-exec-ld-preload.so`**（并自检，失败就 unset），
   最后 `exec "$SHELL" -l "$@"`。

再对照 bootstrap 内部脚本（`bin/*`、`etc/profile`、`termux-tools`）读的环境变量：

| 变量 | 出现次数（脚本里） | 用途 |
| --- | --- | --- |
| `TERMUX__PREFIX` | 58 | termux-exec 重写 shebang、pkg/termux-tools 定位前缀 |
| `TERMUX__USER_ID` | 40 | 多用户/工作资料判定 |
| `TERMUX_APP_PACKAGE_MANAGER` | 30 | pkg 决定走 apt 还是 pacman |
| `TERMUX__ROOTFS` | 24 | 根文件系统位置 |
| `TERMUX_APP__PACKAGE_NAME` | 23 | 新命名（v0.119+）包名 |
| `TERMUX_APP__DATA_DIR` | 15 | 应用数据目录 |

---

## 1. 发现的问题 → 修法

| # | 问题（用户可见症状） | 官方依据 | 修法 |
| --- | --- | --- | --- |
| 1 | **`pkg install` 装完的包完全跑不起来**（`No such file or directory`） | `bin/login` 里 `export LD_PRELOAD=…libtermux-exec-ld-preload.so` | 执行时注入 `LD_PRELOAD`（termux-exec），由它把新包里写死的官方前缀 shebang 在 `execve` 时改写成真实前缀；不兼容时自动**退回无预加载**并落标记 |
| 2 | `pkg` / `termux-change-repo` / `termux-setup-storage` 静默失败 | 脚本读 `TERMUX__PREFIX` 等新名字 | 环境变量补齐**两代命名**：`TERMUX__PREFIX/HOME/ROOTFS/VAR/USER_ID`、`TERMUX_APP__PACKAGE_NAME/DATA_DIR/LEGACY_DATA_DIR/FILES_DIR`、`TERMUX_APP_PACKAGE_MANAGER=apt` |
| 3 | 装到一半被杀 → 之后**永远「已就绪」但 bash 起不来** | 官方用 staging + `renameTo` 原子切换 | 解压到 `usr.staging` → 完成后 `rename`；`isReady` 追加「安装完成标记」判定，半套文件一律重装 |
| 4 | `less` / `nano` / `man` 等工具乱码或报错 | —（官方不重定位前缀，因为它的前缀就是 `com.termux`） | 前缀重定位改为**字节级替换**：旧实现 `bytes.toString(UTF_8).replace().writeText()` 会把 `share/`、`terminfo` 里的非法 UTF-8 写成 `U+FFFD`，静默损坏文件 |
| 5 | Android 12 及以下机型一装就 `NoSuchMethodError` | — | `ensureElfExecutable` 里读 4 字节改用 `read(ByteArray)` 手写循环；`InputStream.readNBytes` 是 **API 33** 才有的方法，minSdk 26 不能直接用 |
| 6 | 超时后重装包报 `dpkg was interrupted` | — | 超时杀**整棵进程树**（Android 没有 `ProcessHandle`，读 `/proc/<pid>/task/<tid>/children` 递归发 `SIGKILL`），不只杀 bash |
| 7 | 换 bootstrap 后不重装 / 每次启动重装一次 | — | 版本戳改成常量 `BOOTSTRAP_VERSION`；不再用 `assets.open().available()`（对压缩资产不可靠） |
| 8 | `apt` 报找不到数据库 / 源 | apt 的 `Dir::*` 编译进二进制 | `APT_CONFIG` 里补全 `Dir`、`Dir::State::lists`、`Dir::Cache::archives`、`Dir::Etc::trusted(parts)`、`Dir::Log`、`Dir::Bin::dpkg`，并预建 `etc/apt/{trusted.gpg.d,preferences.d,sources.list.d}`、`var/log/apt` |
| 9 | 个别脚本 source `$PREFIX/etc/termux/termux.env` 读不到变量 | 官方 `TermuxShellEnvironment.writeEnvironmentToFile()` | 安装后生成 `etc/termux/termux.env`（与运行期注入的变量同源） |
| 10 | 软链还原偶发失败（残留悬空链接） | 官方先确保父目录再 `Os.symlink` | 建链前无条件 `delete()`（`exists()` 对悬空链接返回 false，旧判断会跳过删除 → `EEXIST`） |

---

## 2. 为什么仍然用 `bash -lc`，而不是直接 exec 官方 `bin/login`

`login` 是**交互入口**：会打 motd、探测 `$SHELL`、跑 `$LD_PRELOAD` 自检、`exec "$SHELL" -l`。
在「一次性执行命令并把输出交给 AI」的场景里，这些都会变成噪声（motd 文案、`[ -G ]` 之类的
探测输出混进工具返回值，AI 会当成命令输出解读）。

所以这里保留 `bash -lc`，但把 `login` 做的事**等价地做在环境里**（`LD_PRELOAD` + 全套变量 +
`SHELL`/`PATH`/`TMPDIR`/证书变量），语义一致、输出干净。后续若要接真正的交互式终端 UI，
应该直接走 `termux-host`（官方 Activity/Service 已移植），而不是继续加厚这一层。

---

## 3. Android 10+ 的 W^X：`run` / `pkg` 一直不可用的真因（2026-11 定案）

### 3.1 症状

`termux(action="run")` / `pkg` 恒失败，`status` 报：

```
⚠️ 文件已解压但本进程 exec 失败（权限/可执行位异常，需 repair）
```

`repair`（重解压 bootstrap、给所有 ELF 补 `x` 位）跑多少次都没用 —— 因为**根本不是权限位的问题**。

### 3.2 真因（SELinux 策略，不是文件属性）

Android 10（API 29）的 `system/sepolicy` 提交 `0dd738d8`（W^X）把 `untrusted_app`
域对 **`app_data_file` 的 `execute` 权限删掉了**，只留两个向后兼容域：

| SE 域 | 对应 targetSdk | 能否 exec `app_data_file` |
| --- | --- | --- |
| `untrusted_app_25` | ≤ 25 | ✅ |
| `untrusted_app_27` | 26 ~ 28 | ✅ |
| `untrusted_app`（含 `_34` 等） | ≥ 29 | ❌ |

本应用 `targetSdk = 34` → 落在 `untrusted_app` → `/data/data/<pkg>/files/termux/usr/bin/bash`
**无论 chmod 0755 / 换属主 / 重装多少次，`execve()` 都是 EACCES**。
官方 Termux 至今把 targetSdk 钉在 28，正是为了避开这条（也说明「官方完整版」不是解法）。

顺带说清两个易混淆点：

- `File.canExecute()` / `access(X_OK)` **不反映** SELinux 的 exec 禁令，所以旧版
  `isReady` 看着是「✅ 就绪」，一跑就 `Permission denied` —— 诊断因此指向了错误的修复方向。
- 静态 ELF 也一样被拒（限制在 `execve` 这一层，与要不要动态链接器无关）。

### 3.3 官方解法：`system_linker_exec`

内核只检查「被 exec 的那个文件」。`/system/bin/linker64` 属 `system_linker_exec` 域，
允许被 app exec；它自己被 exec 之后，可以用**读权限**把任意 app 私有目录里的 ELF
`mmap` 进来执行。官方 termux-exec 文档原文：

```
/system/bin/linker64 /data/data/com.foo/executable [args]
/system/bin/linker64 /path/to/interpreter /data/data/com.foo/script.sh [args]
```

bootstrap 里自带的 `termux-exec 2.5.0` 已经把这条封成模式开关，判定规则写在
`include/termux-exec/.../TermuxExecLDPreload.h#isSystemLinkerExecEnabled()` 与
`bin/termux-exec-system-linker-exec is-enabled`：

- `TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE` = `disable` / `enable`（默认，按需启用）/ `force`
- 需要满足：SDK ≥ 29、uid ≠ 0/2000、SE 域不是 `untrusted_app_25/27`
- **只作用于已经跑起来的进程之后的 exec**

### 3.4 旧实现缺的三块（逐个补上）

| # | 缺口 | 修法 | 代码 |
| --- | --- | --- | --- |
| 1 | **第一个进程**（bash 本体）由 App 自己 `ProcessBuilder(bash)` 直接 execve → 恒定 EACCES，termux-exec 根本没机会介入 | 起步改成 `execve(/system/bin/linker64, [<prefix>/bin/bash, -lc, cmd])`。bionic `linker_main` 把 `argv[1]` 当程序，bash 的 `argv[0]` 仍是自己 | `TermuxExec.launchArgv()` / `TermuxEnvironment.execOnce()` |
| 2 | `LD_PRELOAD` 主库用的是 **direct 变体**；`system_linker_exec` 场景官方要求 **linker 变体** | 等价官方 `termux-exec-ld-preload-lib setup`：按通道把 `libtermux-exec-linker-ld-preload.so` 装成 `libtermux-exec-ld-preload.so`（先 `rm` 再写，原地覆盖会让已 mmap 的进程 SIGSEGV） | `TermuxEnvironment.installPreloadVariant()` |
| 3 | 环境变量缺 `TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE` / `PROC_SELF_EXE` / `SE_PROCESS_CONTEXT` | `exportLines` 补齐；`TERMUX_APP__DATA_DIR` 与真实前缀同源（不一致 termux-exec 就不会启用 linker 通道） | `TermuxEnvironment.exportLines()` |

另外两处**判定逻辑**的修正：

- `isReady` 不再要求 `canExecute()`（走 linker 只要读权限，x 位无意义），能不能跑一律以
  **实测 `bash -c true` 的退出码**为准（`probe`）。
- 执行通道**先按政策判、再实测校正**（`resolveMode`）：个别 ROM 的 SE 域字符串与 AOSP 不一致，
  政策判错时会自动换另一条通道并落盘缓存（`.execmode`），而不是静默 EACCES。

### 3.5 告警：repair 不再自动触发

旧版 `ensureTermux` 在「文件在位但 exec 失败」时无脑后台 repair，而那个失败**不是文件问题**，
于是每次调用都重解压一遍、永远修不好（用户看到的「repair 多次未能解决」就是这个循环）。
现在：

- 文件问题（半套树 / 缺库 / `CANNOT LINK`）→ 仍可 `termux(repair)`，但**必须显式调**；
- 通道问题 → 自动换通道，并在 `status` / `run` 里给出归因（SE 域、uid、SDK、政策判定 vs 实测、
  试过的预加载变体），不再让用户「再 repair 一次试试」。

### 3.6 已知代价（官方文档列明的，别当 bug）

1. **静态链接的 ELF 跑不了**：没有 `PT_INTERP`，linker64 加载不了。Termux 官方包基本都是
   `ET_DYN`（PIE），实测 `bin/bash` / `bin/apt` / `bin/dpkg` 均为 `ET_DYN + PT_INTERP=/system/bin/linker64`。
2. `/proc/<pid>/exe`、`ps` 会显示成 `linker64`；官方用 `$TERMUX_EXEC__PROC_SELF_EXE` 补偿，
   依赖 `pgrep`/`pkill` 按进程名匹配的程序需要改 `-f`。
3. 直接调 `syscall(SYS_execve,...)` 的后门程序不会被拦截（官方同样如此），这类要走 proot。

### 3.7 自检指令（真机上验证这套是否生效）

```bash
# 官方自带的判定器：需要/不需要 system_linker_exec
$PREFIX/bin/termux-exec-system-linker-exec is-enabled     # 期望 true

# 通道实跑
$PREFIX/bin/bash -c true                                  # 由 App 侧包成 linker64 调用

# 静态 ELF 反例（应当失败，属预期）
```

`termux(action="status")` 输出里应当看到：

```
执行通道=system_linker → 归因：Android 34 ≥ 10 且 SE 域 u:r:untrusted_app_34:... 非豁免域…
```

---

### 3.8 「Invalid environment variable value」：不是 Android 17 的策略收紧（2026-11 二次定案）

#### 症状

`termux(status)` / `termux(run)` 返回：

```
执行失败：Invalid environment variable value: "u:r:untrusted_app:..."
```

`repair` 反复执行都是同一句。很容易误判成「Android 17 开始校验 SELinux 上下文格式，
应用层绕不过」。

#### 真因：`ProcessBuilder` 的环境变量校验，只认一个字符

AOSP `libcore/ojluni/src/main/java/java/lang/ProcessEnvironment.java` 原文：

```java
private static void validateValue(String value) {
    if (value.indexOf('\u0000') != -1)
        throw new IllegalArgumentException
            ("Invalid environment variable value: \"" + value + "\"");
}
```

它**只查 NUL**，不看格式、不看冒号、不看 SELinux 语义。但 `ProcessBuilder.environment()`
是**整批生效**：只要有一个变量含 `\0`，命令连 `fork` 都到不了，一次也起不来。
异常文案还会把「值」原样打印，于是看起来像「系统拒了这个 SELinux 上下文」。

本机脏值来自 `/proc/self/attr/current`：部分内核/ROM 上这个 proc 文件 read 回来是
**定长 NUL 填充**（`u:r:untrusted_app:s0:c512,c768\0\0\0...`）。`String.trim()` 只去空白，
**去不掉 `\0`**，于是一路进了 `TERMUX__SE_PROCESS_CONTEXT`，把整条通道炸掉。
和 targetSdk、Android 版本、SELinux 策略都无关 —— 换个 Android 10 手机同样会炸。

#### 修法

1. `TermuxExec.sanitizeEnvValue()`：剔除 `\0` 与控制字符后 trim，空值直接不设。
2. `TermuxExec.sanitizeSeContext()`：额外要求 `u:r:` 前缀、至少三段冒号、字符集
   `[A-Za-z0-9_:,.-]`、长度 ≤ 128；不合法返回 `null` → 该变量干脆不设。
   termux-exec 拿不到这个变量会自己读 `/proc/self/attr/current`
   （`se_process_context_from_file`），所以「不设」是安全降级，不丢功能。
3. `exec()` 改为**逐个 put + 逐个净化**，任何单个变量非法都只丢它、不炸整条命令
   （旧代码 `putAll(env)` 是一票否决）。
4. `.execmode` 落盘标记不再当权威：只作「优先尝试顺序」，仍要实测通过才采用。
   否则旧版本在脏环境下写下的错误通道会被永久信任。

#### 为什么 `repair` 修不好

`repair` 动的是文件（重解压、补权限位），而这里是**内存里一个字符串带了 NUL**。
文件层完全正常，所以 repair 循环永远无效 —— 这与 3.2 的 `EACCES` 是两类不同的故障，
但症状都是「run 不可用」，当年被合并成同一句诊断，才出现了「repair 多次未解决」。

#### 自检

```
termux(action="status")
```

应当看到 `执行通道=system_linker`、`探针：✅ bash 可执行`，以及新增的体检行：

```
体检：pkg=✅ apt=✅ bash=✅ 未重写前缀残留=0个
```

`未重写前缀残留` 是给「`pkg: command not found`」准备的判据：

- `pkg=❌缺失` → 解压不全，该 repair；
- `pkg=✅` 但仍 not found → **PATH 没指到这个前缀**（另一条终端链路用了别的前缀），
  repair 无效，见第 4 章第 1 条；
- `未重写前缀残留 > 0` → 一批脚本的 shebang 还指向官方 `com.termux` 前缀，解释器找不到，
  表现正是 bad interpreter / command not found。

---

## 3.9 `pkg install` 失败：「apt 的 https method 路径不匹配」（2026-11 三次定案）

### 3.9.1 症状

`run` 通道修好之后，`pkg install python` 报 method 相关错误（`The method driver ... could not be found`
／「路径不匹配」），换源、`apt update` 都一样。

### 3.9.2 真因：三层同时成立，缺一层都白搭

| # | 层 | 事实 | 证据 |
|---|---|---|---|
| 1 | **路径** | apt / apt-helper 的 ELF 把 method 目录**编译死**成 `/data/data/com.termux/files/usr/lib/apt/methods`；本应用前缀是 `/data/user/0/<pkg>/files/termux/usr` | `strings bin/apt` 里就这一条 `/data/data/com.termux/files/usr/lib` |
| 2 | **method 缺失** | 这套 bootstrap 的 `lib/apt/methods/` 只有 `http / file / copy / gpgv / rsh / store`，**没有 `https`**；而 `sources.list` 与 `etc/termux/mirrors/*` 全是 `https://` | `unzip -l` 清单 + 目录实测 |
| 3 | **密钥环空** | `etc/apt/trusted.gpg.d/` 是空目录，公钥躺在 `share/termux-keyring/*.gpg`（官方 `termux-keyring` 包的 postinst 才把它们拷过去） | 目录实测；不拷 → `NO_PUBKEY` |

ELf 里的绝对路径改不了，所以第 1 层只能靠**配置覆盖**；第 2 层只能**降级 http**；第 3 层**拷公钥**。

### 3.9.3 修法（全部在 `applyRuntimeFixes`，幂等）

1. `etc/apt/apt-quro.conf`（`APT_CONFIG` 指向它）补：
   ```
   Dir::Bin::methods "<前缀>/lib/apt/methods";   ← 第 1 层的解药
   Dir::Bin::dpkg / apt-key / gpg / gpgv
   APT::Sandbox::User "root";                    ← Android 上不存在 _apt 用户
   Acquire::Check-Valid-Until "false";           ← Termux stable 通道 Release 常过期
   ```
   **不设** `AllowUnauthenticated` / `AllowInsecureRepositories`：降级 http 后签名是唯一安全边界。
2. 拷 `share/termux-keyring/*.gpg` → `etc/apt/trusted.gpg.d/`（按长度判重，幂等）。
3. `lib/apt/methods/https` 不存在时，把 `etc/termux/mirrors/*` **和** `sources.list` 的
   `https://` 换成 `http://`。
   为什么两个都要改：`bin/pkg` 第 346 行会用 mirrors 里的 `$MAIN` **重写** `sources.list`，
   只改 sources.list 会被覆盖回去。

### 3.9.4 为什么 `repair` 对这个错也无效

它处理的是「文件在不在、权限对不对」，而这里是**配置与编译期路径不匹配**：
文件全都在（`http` method 就在目录里），apt 只是去了另一个目录找。重解压不会改 apt 的
`Dir::Bin::methods`，所以修不好。

### 3.9.5 自检

```bash
termux(action="run", command="apt-config dump | grep -E 'Dir::Bin::methods|Sandbox'")
# 期望：Dir::Bin::methods "<真实前缀>/lib/apt/methods";
termux(action="run", command="ls $PREFIX/etc/apt/trusted.gpg.d | head")
# 期望：至少一个 .gpg
termux(action="pkg", package="python")
```

`Acquire::Check-Valid-Until=false` 只关「仓库过期」检查，**签名仍然校验**；
若真机上出现 `NO_PUBKEY`，说明 keyring 没拷成功（见 3.9.3 第 2 步），而不是网络问题。

---

## 4. 仍未处理（已知缺口，别当成已修）

1. **`termux-host` 模块的前缀与实际安装路径不一致**：
   `termux-host/.../TermuxConstants.java` 里写死 `/data/data/com.ai.assistance.quro.termux/files/usr`
   （一个并不存在的「Termux 插件包」路径），而 `TermuxEnvironment` 实际解压在**主应用**
   `filesDir/termux/usr`。也就是 `termux` 工具的 `open`（反射拉起 `TermuxActivity`）打开的那个
   界面会找不到前缀。
   修法建议：给 `TermuxConstants` 加运行期覆盖入口（`setPrefixRoot(File)`），在 `Application.onCreate`
   里用 `TermuxEnvironment` 的路径注入；`TerminalSession` 的 `PREFIX`/`HOME` 也一并从那里取。
   这属于 Java 宿主层的批量改动，建议单独一轮做，不要和本次环境层修复混在一起。
2. **bootstrap 只有 aarch64**：x86_64 模拟器/少数设备需要另放一份
   `termux-bootstrap-x86_64.zip`，并在 `install()` 里按 `Build.SUPPORTED_ABIS` 选包。
3. **`TERMUX__APPS_DIR` 未设置**：只有用到 `termux-am`（`am` 桥）的场景才需要，当前工具面用不到。
