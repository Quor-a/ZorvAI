# adb-chain.md — ADB 真实通道链路（配对/端口/执行）

> 详档：坑全量 grep docs/AGENTS/gotchas.md（坑 24/25/26/31/32）；模块锚点 grep AdbState docs/AGENTS/modules.md；逐方法签名 grep BRIDGE-API.md。

## 链路总览

1. **授权模型（三道门）**：门1 All Files Access（live prefs 键 `fullAccess`）→ 门2 允许开关（live prefs）→ 门3 真实配对（`adb pair` 握手）。ADB 能力（含观察类）仅 danger-full-access 会话档位；自动审批不参与。
2. **配对四件套（坑 25 修复）**：prewarm 常驻预热（60s 节流）→ ensureAdbServer 真实 devices 往返就绪判定 → setAdbPair 返回结构化 JSON `{ok,reason,message}` → 前端 AdbAuthSection 双错误通道按 reason 分流文案。
3. **端口发现**：系统属性直读（vivo 上被 SELinux 拒，坑 26，勿依赖）→ NSD/mDNS `_adb-tls-pairing/_adb-tls-connect`（2s 超时 + 后台预取 + 15s TTL 缓存）→ 手动输入硬回退（盲扫已剔除）。
4. **执行**：spawnAdb（app 域 exec EACCES → /system/bin/linker64 降级加载）+ retryRunAdb（protocol fault 自愈重建）；远端命令 PATH 前置纯系统路径 + 整体单引号转义（bridge F3）。

## 常见故障速查

| 症状 | 根因 | 处置 |
|---|---|---|
| 配对光速报错 protocol fault | 冷启动 fork-server 握手坏（坑 24） | ensureAdbServer/retryRunAdb 已自愈；复现即提 issue 带审计 |
| 窗口已关 Connection refused | 配对弹窗端口只在弹窗存活期监听（坑 25） | 文案明示「重开弹窗」；预热降低窗口消耗 |
| 工具输出 [object Object] | dsh-shell 返回 CollectedOutput 结构体（坑 28） | collectText/pickText 已修；复现查插件版本 |
| 宿主探活 000 但设备正常 | adb forward 静默失效（坑 32） | 先 `adb forward --list` 再重 forward |
| 真机引擎查不到进程 | 进程名是 linker64 非 node（坑 31） | `ps -A | grep linker64`；run-as kill 全部 → 看门狗自愈 |

## 0.13.3 增量

- ADB 工具链语义（android_ui_dump/click/scroll/input + ADBKeyboard IME）不在本仓——见协调仓 plugins/dsh-android-manage 与 bridge 插件。

## 0.14.0 方向（U-4：Shizuku 硬切换，ADB 转迁移/诊断面）

- 用户拍板：正式特权 transport = **Shizuku**（`ShizukuTransport` + `ShizukuUserService`，api/provider 13.1.5），
  不保留自跑无线调试 ADB 框架作为正式兜底；被替换的是本文件描述的三道人门通道。
- 现状：ADB 链路代码仍在（`AdbState.kt`，含配对/端口发现/审计；设置页「设备控制授权」下方的
  ADB 高级/脚本面），定位改为**迁移与诊断**；待 Shizuku 全链设备实测通过后按 U-4 退役。
- 过渡期注意：`ControlPolicy` 的 ADB fallback 仍会执行 ADB 观察/动作——**开放屏幕范围（ScreenScope）
  必须在执行点复查**（坑 96），不能只靠页面设置。
- Shizuku 侧没有「配对」概念：授权由用户在 Shizuku App 内授予，壳侧只做标准请求与状态读；
  端口发现/配对码/`adb pair` 等本文件机制不应带入 Shizuku 路径。
