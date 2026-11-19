# 引擎 stderr 与诊断日志去向（issue #25）

## 1. DSH 引擎不经过 proot

`EngineManager.startWithArgs()` 直接 `exec files/usr/bin/node`（被 Android exec 策略拒绝时回退到
`/system/bin/linker64` 加载同一个 node ELF），**不通过 proot 启动引擎**。因此引擎启动失败时不应在
proot 侧找原因；`logcat` 里的 `libproot.so SIGTRAP` 来自 `app/` 主模块的终端模拟层，是另一条独立
进程链，不是 DSH 引擎启动失败的直接证据。

## 2. stderr 去哪了

spawn 时同时设置了：

```kotlin
b.redirectErrorStream(true)      // stderr 合并进 stdout
b.redirectOutput(engine.log)     // stdout/stderr 都写 filesDir/engine.log
```

所以引擎的 stderr **不会丢**，它在 `filesDir/engine.log` 里，与 stdout 按写入顺序交错。
`engine.log` 每次 spawn 前被 `rotateEngineLog()` 轮转，保留 5 代：`engine.log` / `.1` / `.2` / `.3` / `.4` / `.5`。

「engine.log 0 字节」只有三种成因：

1. spawn 从未成功（进程没起来）；
2. 进程瞬间退出且一句都没写（native 崩溃 / 缺 so / 动态链接失败）；
3. 本轮输出被下一次 spawn 的轮转滚进 `engine.log.1..5`。

## 3. 失败时去哪取

- **`files/engine.log` 及 `.1..5`**：引擎 stdout+stderr 全文（含 token，本体未脱敏，仅供本机排障）。
- **`files/logcat-bootfail.txt`**：失败当拍同步落盘的系统级 logcat 尾部快照（覆盖写，保留最后一次失败窗口，
  无 `--pid` 限制，因此含 `libproot.so SIGTRAP` 等系统行；已经过 `LogSanitizer` 脱敏）。
- **`files/boot-fail.log`**：`dsh-boot-fail` 单行诊断。引擎死亡路径带 `processInfo=` 字段，
  其中 `argv=` / `exit=` / `signal=` / `engineLogTail=` 是**无条件存在**的——即使引擎一句日志都没写、
  `engineProcess` 句柄已被观测点置空，也能从进程级 spawn 取证记录还原。
  `exit > 128` 时按 shell 约定追加 `signal=<exit-128>`（SIGKILL → exit=137 → signal=9）。
- **诊断包 ZIP 的 `logcat.txt` / `logcat-bootfail.txt` / `info.txt`**：
  `logcat.txt` 取尾部 3000 行、只留最后 512KB（`ProcIo.readBoundedTail`，不再头部截断）；
  `logcat-bootfail.txt` 优先于现场现取；`info.txt` 的 `engine_process_diagnostic:` 与 boot-fail 同源。
  诊断目录里另有 `logcat-recent.txt`（同为尾部 3000 行）。

## 4. 取证链路小结

```
startWithArgs()  ──记录──> EngineManager.lastSpawnDiagnostic (进程级，句柄置空后仍在)
                     └──> engine.log (stderr 合并)
死亡观测点(engineProcessAlive/probeAvailability/killExistingEngine)
                  ──记录──> EngineManager.lastObservedExit
boot-fail.log     <──── engineProcessDiagnostic() = spawn 记录 + exit/signal + engine.log 尾部摘要
logcat-bootfail.txt <── writeBootFail 当拍 dump（系统级、脱敏、覆盖写）
诊断包 ZIP        <── 上述文件 + 尾部 logcat，全部经 LogSanitizer
```
