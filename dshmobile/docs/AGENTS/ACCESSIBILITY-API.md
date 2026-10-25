# ACCESSIBILITY-API.md — 无障碍控制通道 API 与权限面全量参考

> **权威数据来源**：本机 Android SDK `platforms/android-36/data/api-versions.xml`（API 级别）
> + AOSP `core/java/android/accessibilityservice/AccessibilityService.java` 源码
> （2026-09-10 抽取；SDK 升级后重跑 `node .deploy-tmp/0135/api-surface.mjs <api-versions.xml> <class>` 复核）。
>
> 本文件是**能力面与权限面**的唯一依据；实现与本文不一致时以源码为准并当场修正。
> 通道设计（队列协议/门禁矩阵/协作结论）见协调仓 `docs/A11Y-CONTROL-DESIGN.md`。

## 0. 一句话

无障碍通道**不是 ADB 的降级替代**：Android 11（API 30）起自带 `takeScreenshot()`（截屏不再需要 ADB）。
Android 13 起 AOSP 存在无障碍输入法（`FLAG_INPUT_METHOD_EDITOR`）设计，但**本项目实测不可用**：
javap 校验 android-36 的 `android.jar`，`AccessibilityNodeInfo` 无该符号（见 known-gaps.md 与
0.13.8 批 F 登记），中文输入由 `ACTION_SET_TEXT` + ADBKeyboard 承担。
真正只能靠系统面/ADB 的：**shell 执行、pm/dumpsys/appops 等系统面、以及 API <30 的字节级截图**。

## 1. 服务级能力（`AccessibilityServiceInfo.CAPABILITY_*` ↔ XML 属性）

| 能力常量 | API | XML 属性 | 本通道用法 |
|---|---|---|---|
| `CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT` | 18 | `canRetrieveWindowContent="true"` | 读节点树（已用） |
| `CAPABILITY_CAN_PERFORM_GESTURES` | 24 | `canPerformGestures="true"` | `dispatchGesture` 坐标兜底（已用） |
| `CAPABILITY_CAN_TAKE_SCREENSHOT` | **30** | **`canTakeScreenshot="true"`** | **`takeScreenshot()` 截屏（0.13.5 新增）** |
| `CAPABILITY_CAN_CONTROL_MAGNIFICATION` | 24 | `canControlMagnification` | 未用 |
| `CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION` | 18 | `canRequestTouchExplorationMode` | 不用（会改变用户触摸语义） |
| `CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS` | 18 | `canRequestFilterKeyEvents` | 不用 |
| `CAPABILITY_CAN_REQUEST_ENHANCED_WEB_ACCESSIBILITY` | 18 | `canRequestEnhancedWebAccessibility` | 不用（已废弃方向） |
| `CAPABILITY_CAN_REQUEST_FINGERPRINT_GESTURES` | 26 | `canRequestFingerprintGestures` | 不用 |
| `FLAG_INPUT_METHOD_EDITOR` | 33 | （flag） | **不可用**：android-36 android.jar 无相关符号（known-gaps 已核实）；中文输入走 `ACTION_SET_TEXT` + ADBKeyboard |

## 2. 服务 API（含 API 级别）

### 2.1 树与窗口

| API | 级别 | 备注 |
|---|---|---|
| `getRootInActiveWindow()` | 16 | 当前活动窗口根节点 |
| `getRootInActiveWindow(displayId)` | 33 | 指定屏 |
| `getWindows()` | 21 | 需 `flagRetrieveInteractiveWindows` |
| `getWindowsOnAllDisplays()` | 30 | 跨屏 |
| `getServiceInfo()` / `setServiceInfo()` | 16 | 动态只能改 eventTypes/feedbackType/flags/notificationTimeout/packageNames |

### 2.2 动作

| API | 级别 | 备注 |
|---|---|---|
| `performGlobalAction(action)` | 16 | 见 §3 |
| `getSystemActions()` | 30 | **设备实际支持的动作集合，必须以此为准** |
| `dispatchGesture(gesture, callback, handler)` | 24 | 点击/滑动/长按/多指；回调在指定 handler |
| `disableSelf()` | 24 | 自我停用 |

### 2.3 截屏

| API | 级别 | 说明 |
|---|---|---|
| **`takeScreenshot(displayId, Executor, TakeScreenshotCallback)`** | **30** | 回调给 `ScreenshotResult{hardwareBuffer, colorSpace, timestamp}`；`Bitmap.wrapHardwareBuffer(...)` 转位图；**限频约 333ms**（`ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS`） |
| `takeScreenshotOfWindow(windowId, …)` | 34 | 单窗口 |
| `GLOBAL_ACTION_TAKE_SCREENSHOT` | 28 | 走系统截屏（**存相册，不回传数据**）——API<30 的降级 |

失败错误码（`onFailure(errorCode)`）：

| 常量 | 含义 |
|---|---|
| `ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS` | 无障碍访问未就绪 |
| `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` | 调用过频（~333ms 间隔） |
| `ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY` | 无效 display id |
| `ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR` | 内部错误 |
| `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` | 窗口含 `FLAG_SECURE`（如银行/密码页） |

### 2.4 输入法与键盘

| API | 级别 | 说明 |
|---|---|---|
| `getSoftKeyboardController().switchToInputMethod(imeId)` | 24 | 切换输入法（**替代 ADB `ime set`**） |
| `getSoftKeyboardController().setInputMethodEnabled(imeId, enabled)` | 33 | 启用/停用 IME（限同包） |
| `onCreateInputMethod()` / `getInputMethod()` | 33 | 无障碍输入法（配 `FLAG_INPUT_METHOD_EDITOR`）——**本 SDK 无此符号，不可用**（known-gaps） |
| `getSoftKeyboardController().setShowMode(...)` | 24 | 软键盘显示策略 |

### 2.5 其他

| API | 级别 | 说明 |
|---|---|---|
| `getMagnificationController()` | 24 | 缩放 |
| `attachAccessibilityOverlayToWindow/Display` | 34 | 无障碍悬浮层（`TYPE_ACCESSIBILITY_OVERLAY`，免 `SYSTEM_ALERT_WINDOW`） |

## 3. 全局动作（`GLOBAL_ACTION_*`）

| 动作 | API |
|---|---|
| `BACK` / `HOME` / `RECENTS` / `NOTIFICATIONS` | 16 |
| `QUICK_SETTINGS` | 17 |
| `POWER_DIALOG` | 21 |
| `TOGGLE_SPLIT_SCREEN` | 24 |
| `LOCK_SCREEN` | 28 |
| `TAKE_SCREENSHOT` | 28 |
| `KEYCODE_HEADSETHOOK` / `ACCESSIBILITY_BUTTON` / `ACCESSIBILITY_BUTTON_CHOOSER` / `ACCESSIBILITY_SHORTCUT` / `ACCESSIBILITY_ALL_APPS` / `DISMISS_NOTIFICATION_SHADE` | 31 |
| `DPAD_UP` / `DPAD_DOWN` / `DPAD_LEFT` / `DPAD_RIGHT` / `DPAD_CENTER` | 33 |
| `MEDIA_PLAY_PAUSE` / `MENU` | 36 |

## 4. 节点动作（`AccessibilityNodeInfo.AccessibilityAction`）

| 动作 | API | 用途 |
|---|---|---|
| `ACTION_CLICK` / `ACTION_LONG_CLICK` | 21 | 点击 / 长按 |
| `ACTION_FOCUS` / `ACTION_CLEAR_FOCUS` | 21 | 焦点 |
| `ACTION_SET_TEXT` | 21 | **原子写入文本**（中文/空格/长文本可靠，绕开 IME） |
| `ACTION_SCROLL_FORWARD` / `ACTION_SCROLL_BACKWARD` | 21 | 上下滚动 |
| `ACTION_SCROLL_UP/DOWN/LEFT/RIGHT` | 23 | 方向滚动 |
| `ACTION_SCROLL_TO_POSITION` | 23 | 滚到指定位置 |
| `ACTION_SELECT` / `ACTION_CLEAR_SELECTION` / `ACTION_SET_SELECTION` | 21 | 选择 |
| `ACTION_COPY` / `ACTION_CUT` / `ACTION_PASTE` | 21 | 剪贴板 |
| `ACTION_EXPAND` / `ACTION_COLLAPSE` | 21 | 展开/折叠 |
| `ACTION_DISMISS` | 21 | 关闭 |
| `ACTION_SHOW_ON_SCREEN` | 23 | 滚到可见 |
| `ACTION_CONTEXT_CLICK` | 23 | 上下文菜单 |
| `ACTION_PAGE_UP/DOWN/LEFT/RIGHT` | 29 | 翻页 |
| `ACTION_PRESS_AND_HOLD` | 30 | 长按（新版） |
| `ACTION_IME_ENTER` | 30 | 触发输入法回车 |
| `ACTION_DRAG_START/DROP/CANCEL` | 32 | 拖放 |
| `ACTION_SHOW_TEXT_SUGGESTIONS` | 33 | 文本建议 |
| `ACTION_SCROLL_IN_DIRECTION` | 34 | 带方向的滚动 |

节点读取面（节选，级别见 SDK）：`getText` / `getContentDescription` / `getViewIdResourceName`(18) /
`getBoundsInScreen`(14) / `getBoundsInWindow`(34) / `isClickable` / `isEditable`(18) / `isScrollable` /
`isChecked` / `isVisibleToUser`(16) / `getHintText`(26) / `getTooltipText`(28) / `getPaneTitle`(28) /
`getCollectionInfo`(19) / `getActionList`(21) / `getExtras`(19) / `findAccessibilityNodeInfosByText`(14) /
`findAccessibilityNodeInfosByViewId`(18) / `refresh`(18)。

## 5. 权限面

| 层 | 要求 | 备注 |
|---|---|---|
| 清单 | `android.permission.BIND_ACCESSIBILITY_SERVICE` + `<intent-filter action="android.accessibilityservice.AccessibilityService">` | 只有系统可绑定；缺一即被系统忽略 |
| 服务声明 | `android:exported="true"` + `<meta-data android:name="android.accessibilityservice" android:resource="@xml/accessibility_service_config"/>` | 能力只能走 XML |
| 用户开关 | 系统设置 → 无障碍 → 已下载的服务 → 「DSH 设备控制」 | 一次性；`disableSelf()` 可自关 |
| Android 13+ | 侧载应用可能被「受限设置」挡住开关 | `appops set <pkg> ACCESS_RESTRICTED_SETTINGS allow`（本仓 `AdbState.unlockRestrictedSettings`，走 ADB 通道） |
| 截屏 | `canTakeScreenshot="true"`（API 30+） | 无额外运行时权限；`FLAG_SECURE` 窗口拒绝；~333ms 限频 |
| 手势 | `canPerformGestures="true"`（API 24+） | |
| 读树 | `canRetrieveWindowContent="true"` | |
| 多窗口 | `flagRetrieveInteractiveWindows` | |

**无额外危险权限**：不需要 `SYSTEM_ALERT_WINDOW`（无障碍层用 `TYPE_ACCESSIBILITY_OVERLAY`）、不需要 root、
不需要 Shizuku。

## 6. 本仓实现映射

| 能力 | 落点 |
|---|---|
| 服务与能力声明 | `app/src/main/AndroidManifest.xml`（`DeviceControlService`）+ `res/xml/accessibility_service_config.xml` |
| 树快照（路径 id + attrs 同构 uiautomator XML） | `DeviceControlService.buildSnapshot()` / `nodeAtPath()` |
| 动作（click/setText/scroll/global/screenshot） | `DeviceControlService.handle*()` |
| 屏幕范围失败关闭 | `ScreenScopePrefs` + `DeviceControlService.realScreenScopeError()`；每次 real op 重查，切换范围使旧 snapshot/ref 失效；`virtual-1` 未 ready 时不映射 display 0 |
| 队列客户端（长轮询 + 心跳 + 回填） | `ControlPoller.kt` |
| 状态与令牌 | `DeviceControlService.statusJson()` / `token()`；prefs `dsh-adb.xml` 的 `a11yEnabled` / `controlToken` |
| 桥方法 | `AndroidBridge.a11yStatus()/openA11ySettings()/unlockRestrictedSettings()`（MainActivity 接线） |
| 引擎侧门禁与路由 | 协调仓 `plugins/dsh-android-bridge/src/control-policy.ts` / `control-queue.ts`；工具面在 `plugins/dsh-android-manage` |

## 7. 状态与待办（2026-09-14 对账）

已完成：服务 + 能力声明、树快照、click/setText/scroll/global、队列长轮询 + 心跳、门禁双通道、
设置页无障碍优先、`takeScreenshot`（设备实测通过）、**全局动作面由 `getSystemActions()` 驱动**
（0.13.8 E6，`GlobalActionCatalog`）、**无障碍截屏失败回落 ADB**（E6）、**屏幕范围执行点复查**
（`ScreenScopePrefs` + `DeviceControlService.realScreenScopeError`）。

待办：① ~~无障碍输入法~~ **已核实不可用**（android-36 无符号）；② ~~`getSystemActions()` 驱动全局动作面~~ **已落地**；
③ 长按已接，展开/折叠/复制/翻页/拖拽等节点动作仍未暴露给模型；④ `takeScreenshotOfWindow`(34) 单窗口观察未接；
⑤ 多窗口 `getWindows()` 的窗口选择面未接；⑥ 回落 ADB 路径只在本机 API 35 验过「无障碍可用」分支，API<30 真机回落未验。
完整缺口清单维护在 `known-gaps.md`（本文件只登记 API 参考与实现映射）。
