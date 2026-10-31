# 后台虚拟屏 · 完整移植记录（Mika → ZorvAI）

> 目标：把 Mika 的**后台虚拟屏**以及**它运行所需的一切**完整搬进 ZorvAI。
> 不做「我觉得该搬什么」的主观裁剪 —— 边界由依赖闭包算出来。

---

## 1. 为什么不能按「感觉」切分

一开始我用「种子文件 + import 递归」算闭包，得到 123 文件 / 16,738 行。但一编译就 1267 个错误：
`ActionExecutor` 找不到 `Decision`、`AndroidScreen` 找不到 `ConfirmDecision`……

原因是 **Kotlin 同包内跨文件引用不经过 import**，只跟 import 必然漏。
把闭包改成「包级纳入」（闭包里每个文件所在的整个 package 都进来）后才是真实边界：

```
种子 = cap/phone/a11y/*.kt + cap/phone/display/*.kt
包级闭包稳定点 = 1128 文件 / 161,328 行
其中 core 模块命中 624 / 627 文件
Mika 全仓         = 1145 文件 / 162,417 行
```

**结论（硬事实）**：后台虚拟屏在 Mika 里不是可切分的能力单元 —— 它和整个内核长在一起。
要「它需要的一切」，就是整个 `core` 模块；再往上就只剩 App 的 UI 与业务外壳。

脚本：
- `docs/tools/mika_closure.py` — import 级闭包（快速估算）
- `docs/tools/mika_closure_pkg.py` — 包级闭包（真实边界，用来定方案）
- `docs/tools/mika_phone_closure.py` — app 侧闭包（确认 a11y 对外部大包零依赖）

关键旁证：`grep -rl "^import android" core/src/main/kotlin/` 结果为 **0** ——
Mika 的 core 是纯 Kotlin JVM，可以干净地 vendor 成独立模块，不引入 Android 语义。

---

## 2. 最终结构

```
:app
 └─ implementation(:mika-phone)
      └─ api(:mika-core)
```

| 模块 | 内容 | 规模 |
|---|---|---|
| `:mika-core` | Mika `core` 模块整体（uistate / visual / vision / action / gate / agent / tool / cap…） | 338 源码文件 / 8.2 万行 + 161 资源文件（knowledge/prompts/skills，1.5MB） |
| `:mika-phone` | 后台虚拟屏操作层：`cap/phone/a11y` 15 个 + `cap/phone/display` 3 个 + `shell` 3 个 + `i18n` 1 个 + `vision` 1 个 | 24 文件 / 约 4,000 行（含 `AndroidScreen` 1,810 行动作编排） |
| `:app` 的适配层 | `QuroShellPortAdapter`（把 `IQuroShellService` 映射成 `QuroShellPort`） | 1 文件 |

搬运脚本（可重复执行，产物只差包名与品牌词）：
```bash
python docs/tools/vendor_mika_core.py     # core → mika-core
python docs/tools/vendor_mika_phone.py    # a11y/display → mika-phone
```

---

## 3. 与上游的差异（只有两处，且都是宿主绑定）

### 3.1 包名
`com.wangzan101.mika.core` → `com.ai.assistance.quro.core.mika`
`com.wangzan101.mika.cap.phone` → `com.ai.assistance.quro.core.mika.phone`
（`core.vision` 与 App 层 `vision` 同名，后者映射为 `appvision`）

### 3.2 特权服务契约
上游 `AndroidScreen` 直接吃 AIDL 的 `IMikaShell`（43 方法）与 `ShellService` 常量。
ZorvAI 有自己的 `IQuroShellService`（Shizuku UserService，uid 2000）。
为让操作逻辑**逐字节保真**，定义同构接口：

- `mika-phone/.../phone/shell/QuroShellPort.kt` — 43 方法，签名/返回码与上游一致
- `ShellCodes` — 返回码常量，与上游**逐值相同**（`START_OK=0 … START_USER_NOT_ALLOWED=8`、`CLIP_SET=0…`）
- `app/.../core/mika/bridge/QuroShellPortAdapter.kt` — AIDL → Port 的**纯转发**，不做加工

> 特别注意：早期 `QuroShellService` 里 `START_IN_USE_BY_OTHER = 4`，而上游 4 是
> `START_OK_COLD_IGNORED`、`IN_USE_BY_OTHER` 才是 7。这种错位表现为
> 「App 明明启动了却报没入口」，极难查 —— 已按上游值纠正。

### 3.3 AIDL 扩展
`IQuroShellService` 从 17 → **46** 方法，补齐 `AndroidScreen` 真正调用的能力：
`restartApp / packageOfTask / userOfTask / displayOfPackage[InUser] / injectCtrlKey /
setAudioMuted / getAudioMode / setAudioMode / setClipText / restoreClip / savedClipText /
restoreClipText / startView / startSystemAction / startShare / runSystemCommand / runCommand /
listUsers / listPackages / isSystemPackage / appCopyUsers / startAppInUser /
wakeDevice / dismissDeviceKeyguard / enterDeviceCredential / identity / releaseAll / openDevtoolsSocket`

`QuroShellService` 里已逐个实现（反射隐藏 API 的地方与上游同法：`KeyEvent.setDisplayId`、
`IActivityTaskManager.moveRootTaskToDisplay`、`startActivityAsUser` 12 参重载）。

---

## 4. 无障碍服务

`ScreenAccessibility`（上游 `MikaAccessibilityService` 的父类）在 `mika-phone` 里注册为
**独立无障碍服务**，配置照搬上游 `accessibility_service.xml`：

```xml
android:accessibilityFlags="flagRetrieveInteractiveWindows|flagReportViewIds|flagIncludeNotImportantViews"
android:canPerformGestures="true" android:canRetrieveWindowContent="true" android:canTakeScreenshot="true"
```

- `flagRetrieveInteractiveWindows` 是**按 displayId 枚举窗口**的前提。没有它只能读主屏，
  虚拟屏永远是空的，AI 就只剩截图猜坐标。
- 为什么不合并进 `QuroAccessibilityService`：后者已继承 `DeviceControlService`（#484 合并了重复授权），
  Kotlin 单继承下无法再继承 `ScreenAccessibility`。
- **代价要如实说**：用户需要在系统「无障碍」里为「ZorvAI 后台虚拟屏」**单独开一次**。

---

## 5. 编译状态

```
:mika-core:compileReleaseKotlin   BUILD SUCCESSFUL
:mika-phone:compileReleaseKotlin  BUILD SUCCESSFUL
:app:compileFullReleaseKotlin     BUILD SUCCESSFUL
```

单测：`MainScreenSpillTest` 11 项（主屏「冒出来」判定：时间窗/用户点过/跨屏挪来/桌面类/去重/from 记录）。

---

## 6. 还没接的线（下一批）

| 项 | 说明 |
|---|---|
| `AndroidScreen` 接进 `bg_screen` | 目前 `bg_screen` 用的是自研轻量读树（`BgScreenReader`）。要用上游全套（等界面安静 → 指纹核实 → 判断被切走 → 重定位），需把 `QuroVirtualScreen` 的 read/tap 换成 `AndroidScreen`（构造需要 `QuroShellPort` + `LazyAgentDisplay` + `ScreenAccessibility`） |
| 剪贴板兜底链 | `AndroidScreen` 的粘贴依赖 `setClipText/restoreClipText`（已实现），但 UI 侧还没展示「剪贴板被临时替换」的状态 |
| 音量静音恢复 | `setAudioMuted` 已实现；进程崩溃后的恢复依赖 `QuroDshKeepAlive` 式的持久化记录，未做 |
| 设备解锁 | `enterDeviceCredential` **不支持**（shell 身份无法替用户提交锁屏凭据），已如实返回 false |

---

## 7. 复现方式

```bash
# 1. 拉上游
git clone --depth 1 https://github.com/wangzan101/Mika D:/CalwOS-build/tmp/Mika

# 2. 算边界（确认没有漏文件）
python docs/tools/mika_closure_pkg.py

# 3. 搬运（只改包名与品牌词，见脚本内的 debrand 保护：包名不参与品牌替换）
python docs/tools/vendor_mika_core.py
python docs/tools/vendor_mika_phone.py

# 4. 编译
./gradlew :mika-core:compileReleaseKotlin :mika-phone:compileReleaseKotlin :app:compileFullReleaseKotlin
```
