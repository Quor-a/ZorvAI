# 桌面 / 多窗口 / 后台屏参考项目 · 实测笔记

> 2026-11-01 实测：全部 `git clone --depth 1` 到 `D:/CalwOS-build/ref/`，逐一看结构，不凭印象。

## 一句话结论表

| 项目 | 定位 | 能进 APK 吗 | 依据 |
|---|---|---|---|
| **farmerbb/libtaskbar** | Android 10 桌面模式**官方库**（Taskbar 拆出） | ✅ **已接入**（JitPack AAR 2.2.0，804KB） | 上游仓库 `Taskbar/` 目录为空，只发布预编译 AAR ——「当库引入」是它唯一形态 |
| farmerbb/Taskbar | libtaskbar 的宿主 App（完整桌面） | ⚠️ 仅参考 | 它本身就是那套桌面，集成 = 用 libtaskbar |
| **NarYuki/Dextop** | 用虚拟屏做**桌面合成工作区**，自带特权运行时 | ⚠️ 部分可借鉴 | `MirrorService.kt` **13,773 行**、`CompositedWindowShell.kt` 3,929 行，重 |
| **legendsayantan/Extendroid** | 多窗口管理器（Shizuku + 弹窗窗口） | ✅ 可搬（路线一致） | `DisplayHelper.kt` 492 + `PopupWindow.kt` 360 + `OverlayMenu.kt` 509，共 7,433 行 |
| JuanArton/reYAMF | YAMF 分支，多窗口/最小化/缩放 | ❌ 硬前提不符 | 需 **LSPosed**（Xposed 框架），且 Android 13+ |
| boringdroid | AOSP 9–14 **系统层补丁集** | ❌ 无法装 | 要编 ROM，需要 framework 改动 + 系统签名 |

---

## 1. libtaskbar（已接入）

**源码形态**：仓库里 `Taskbar/` 是空目录，真实实现打包成 AAR 发到 JitPack：

```
implementation 'com.github.farmerbb:libtaskbar:2.2.0'   // 804,479 字节
入口类：com.farmerbb.taskbar.lib.Taskbar
  · Taskbar.openSettings(context)
  · Taskbar.openSettings(context, title, themeRes)
```

AAR 的 manifest 注明：**所有组件跑在 `:libtaskbar` 独立进程**，且 `SecondaryHomeActivity` 的
order 设为 100，避免与 Launcher3 抢。

**生效三前提**（缺一不可，已写进 ZorvAI 的 UI 提示）
1. 宿主 App 是**系统默认 launcher**；
2. 开发者选项打开「Enable freeform windows」+「Force desktop mode」；
3. 接外接屏（HDMI / 投屏）时才出现任务栏。

**ZorvAI 侧已做的接线**
- `app/build.gradle.kts` 加依赖（JitPack 仓库已在 settings 里）；
- 新增 `QuroDesktopActivity`（应用网格 + 桌面模式设置 / 开发者选项入口），
  manifest 声明 `HOME` + `DEFAULT` —— **只有用户自己设成默认桌面才接管**，未设不影响；
- 设置里新增「桌面模式（Taskbar）」一行。

---

## 2. Dextop（可借鉴，谨慎）

``` 
android/app/src/main/kotlin/moe/n4tsu/dextop/
  MirrorService.kt              13,773 行   ← 虚拟屏镜像 + 桌面合成 + 输入分发（巨型）
  plasma/CompositedWindowShell.kt 3,929 行   ← 多窗口合成外壳
  plasma/DextopAutoView.kt       2,695 行
  合计 37,533 行 Kotlin（229 个文件 + cardex 10 个）
```

它和我们的后台虚拟屏**思路一致但方向不同**：
- 我们：一块屏 + 一个 App + 无障碍读树/注入输入（轻，够 AI 干活）；
- Dextop：一块屏 + **多个 App 窗口合成成桌面**（重，给人用）。

可直接借鉴的点：
1. **输入坐标从「屏坐标」映射到「窗口坐标」**（多窗口叠加时必须做，否则点击错位）；
2. **窗口生命周期与显示绑定**（App 退出后窗口残留的处理）；
3. 它带「内置特权运行时」—— 与我们的 Shizuku UserService 是同类方案，可对照其
   `createVirtualDisplay` 的 flag 组合。

不建议直接搬 `MirrorService`：13,773 行、与它自己的 WindowManager 抽象深度耦合，
搬进来等于引入第二套窗口系统。

---

## 3. Extendroid（最值得搬，路线一致）

```
app/src/main/java/dev/legendsayantan/extendroid/
  lib/DisplayHelper.kt     492 行   ← 虚拟屏创建/尺寸/DPI
  ui/PopupWindow.kt        360 行   ← 自由窗口（可移动/缩放）
  ui/OverlayMenu.kt        509 行   ← 悬浮菜单
  services/ExtendService.kt 368 行   ← 前台服务 + 生命周期
  echo/MotionEventParser.kt 361 行   ← 输入事件解析（远程控制用）
  合计 7,433 行
```

要求：**Android 10+ 与 Shizuku** —— 与 ZorvAI 现有架构完全一致（`QuroShellService` 就是 Shizuku UserService）。

特性对齐我们的需求：
| Extendroid 特性 | 我们现状 |
|---|---|
| 在弹窗里启动并控制任意 App | ✅ 已有 `bg_screen open/tap/swipe` |
| 自由移动 / 缩放窗口 | ❌ 缺（我们的虚拟屏尺寸固定） |
| 最小化但保持运行 | ❌ 缺 |
| 最小化应用实时预览 | ⚠️ 部分（`QuroAgentDisplay` 有帧流） |
| 关屏保持活跃 | ❌ 缺 |

**下一批：搬 `DisplayHelper` + `PopupWindow` 的窗口尺寸/位置管理与「最小化保活」**，
接到 `QuroVirtualScreen` 上（自由窗口 = 可改虚拟屏尺寸 + 单 App 窗口位置）。

---

## 4. reYAMF（不能进 APK）

71 个源文件，README 明确要求：
- Android 13+（API 33）
- **LSPosed**（Xposed 框架）

它的多窗口是**改 framework 行为**（hook ActivityTaskManager / freeform），
没有 LSPosed 就完全跑不起来。ZorvAI 不能强制用户装框架，也拿不到 hook 权限。

可用部分：它的窗口最小化/缩放**参数与手势阈值**可作设计参考（纯数值，不涉及 hook）。

---

## 5. boringdroid（不能进 APK）

AOSP 补丁集（`boringdroid-14.0.0` 分支，跟 9.0–14.0）：
- 在 system_server / WindowManager 层加任务栏与自由窗口；
- 必须**编译 ROM**，需要系统签名与 framework 改动。

App 层面**无法复用**。但它的设计可以作为「如果以后做 ZorvAI 自己的系统镜像」的参考。

---

## 复现

```bash
mkdir -p D:/CalwOS-build/ref && cd D:/CalwOS-build/ref
for r in farmerbb/Taskbar farmerbb/libtaskbar NarYuki/Dextop legendsayantan/Extendroid JuanArton/reYAMF boringdroid/boringdroid; do
  git clone --depth 1 https://github.com/$r.git
done
```
