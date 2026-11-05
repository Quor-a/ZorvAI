# 后台虚拟屏：排查与修复（对齐 Mika 上游）

源码根 `D:\Calw OS-project\QuroAI`，参照实现 `github.com/wangzan101/Mika`（本地副本 `D:\mika_src`）。
本轮针对用户反馈的三个症状：**启动慢**、**AI 看屏有问题**、**OCR 没真正接上**。

---

## 1. 症状 → 真因 → 修法

| 症状 | 真因（代码级） | 修法 |
| --- | --- | --- |
| 截图一直是同一张 / 点按没反应 / 启动变慢，三者同时出现 | 建屏带了 `OWN_DISPLAY_GROUP`（Android 13+ 可信屏），这块屏**自成一个显示组、有自己的息屏计时**；AI 注入的输入不算用户操作，到点这组屏就睡了。整条链路上**没有任何异常或错误码**，帧就是不来 | 移植上游 `DisplayAwake`：屏在时每 4s 替它报一次用户活动（`IPowerManager.userActivity(displayId, …)`）。shell 侧新增 `pokeUserActivity(displayId)`；只对自建屏报，只在 13+ 报，报不了就永久收手 |
| 明明界面有字，AI 说「读不到界面结构」 | `read` 里 `readScreen()` 返回 null 时**直接 return 错误串**，OCR 兜底根本没机会跑。无障碍没开 / 自绘 UI / 防截屏三种情况全是这条路径 | 改成统一的 `readForAi()`：结构为主，结构稀薄（<3 元素或 <3 文字）时自动补 OCR；没有 a11y 也照样读 |
| OCR 经常认到「Loading…」「正在加载」或一片空白 | 来了哪帧就认哪帧。App 冷启动会连续出「启动图 → 白屏 → 骨架屏 → 真界面」 | `FrameGate.awaitStable()`：连续两次采样签名一致才认，最多等 1.4s；等不到就交帧但标 `stable=false`，并在给模型的文本里说明「界面可能还在加载」 |
| 银行/支付类界面 OCR 空，AI 以为屏是空的 | 防截屏界面（`FLAG_SECURE`）拿到的是**纯黑帧**，OCR 当然没字 | `FrameGate.probe()` 采样 24×48 点，≥98.5% 近黑即判黑屏，直接告诉模型「可能禁止截屏」，不再浪费一次 OCR |
| AI 看到「设置 设置 网络 网络」 | 中文模型也能读英文，双识别器把同一行各报一次，直接拼接 | `ScreenOcrBridge.dedupeLines`：文本相同（忽略大小写/空白）+ 中心点相邻 → 合并，留置信度高的 |
| ML Kit 崩了、模型没下载，AI 却说「屏上没有文字」 | 旧桥接把所有异常 `runCatching{}.getOrElse{ emptyList() }` 吞成空列表 | 改为 `Result(status, lines, reason)`：`OK / EMPTY / FAILED / UNAVAILABLE` 分开。FAILED 时给模型的文本明确写「识别器出错，**这不代表屏上没有字**」 |
| 每次 open 都慢将近 1 秒 | `rescueIfOnMainScreen` 里**无条件** `Thread.sleep(900)`，可绝大多数启动根本不会外溢到主屏 | 先查 `mainArrivals`，**没有新到达就直接返回**；有才等这 900ms |
| 第一次看屏明显卡一下 | 两套 ML Kit 模型首次加载约 1s，正好压在第一次 read 上 | 建屏后立刻 `ScreenOcrBridge.warmUp()`，把这笔钱提前付掉 |
| 点了之后 AI 不知道生效没，对同一控件连点三四次 | 工具只回「已点击」，模型得自己 read + 比对（它常常懒得比） | 点击前后各取一次帧签名，用 `FrameGate.awaitChange` 判定，直接回「界面已变化」/「看起来没变，别原地重试」 |
| 分不清「搜索框」和普通输入框、不知道焦点落在哪 | `BgElement` 缺 `resourceId / hint / focused / placeholder` | 补齐这四个字段，并按上游规则过滤混淆过的资源 ID（`a1`、`b2` 这类没信息量的不给） |

---

## 2. 保活：为什么这不是「优化」而是修 bug

```text
VirtualDisplay(flags 含 FLAG_OWN_DISPLAY_GROUP)
        └── 自成 DisplayGroup（Android 13+ 可信屏）
                └── 独立息屏计时（默认 30s~2min，取决于系统设置）
                        └── 到点 → 该组屏 sleep
                                ├── Surface 不再合成 → ImageReader 不来帧 → 截图定格在最后一帧
                                ├── injectInputEvent 注入到睡着的屏 → 系统直接丢弃
                                └── launch 找不到活动显示组 → 启动变慢/静默失败
```

关键点：**这三个症状看起来是三个不同的 bug，其实同源**。而且从 App 侧完全看不出来——
没有异常、没有错误码、`release()` 也没被调过，只是「帧不动了」。

实现（`core/display/DisplayAwake.kt`）三条纪律：

1. **只报自建屏**：报给默认组会让**用户自己的主屏**也一直不灭，比屏睡着更糟。
2. **只在 13+ 报**：`OWN_DISPLAY_GROUP` 只在 13+ 被加进 flags，低版本报活动等于报到默认组。
3. **报不了就永久停手**：第一轮 `poke` 返回 false 即置 `unavailable`，不再每 4 秒刷一次日志、空转一次 binder。

---

## 3. 读屏三层：结构 / OCR / 诊断

`QuroVirtualScreen.readForAi()` 一次给全，`BgReading.toPromptText()` 是唯一输出格式
（`BgSnapshot.toPromptText` 已删除——两份格式长期必然走样）。

```text
① 控件树（无障碍）  → ref / role / 名称 / 像素中心 / #resourceId / 占位语 / [可点,可输入,已聚焦…]
② 端侧 OCR（结构稀薄时）→ "文字" (cx,cy)，坐标可直接 tap；状态 OK/EMPTY/FAILED/UNAVAILABLE
③ 画面诊断            → 全黑=禁止截屏 / 一直在变=还在加载 / 都没有=确认 App 真在这块屏上
```

第 ③ 层是这次的重点：**以前这四种坏状态在模型眼里长得一模一样**（都是「没有元素」），
模型只能瞎猜，这就是「AI 看屏有问题」的观感来源。

---

## 4. OCR 完整移植的接线语义

引擎（`mika-phone/.../a11y/PhoneOcr.kt`）早已与上游一致：双识别器并行、本机运行、
两套都失败时**抛异常而不是返回空列表**（上游刻意的语义：失败 ≠ 没字）。

我们缺的一直是**接线层**，这次补齐四件：

| 项 | 之前 | 现在 |
| --- | --- | --- |
| 失败语义 | 全部吞成 `emptyList()` | `Result.status` 四态，FAILED 附 reason |
| 位图传递 | `Bitmap → PNG(quality=100) → decode → OCR` | 直接喂 `latestFrameBitmap()` 副本，省掉 400~800ms |
| 重复行 | 中文+拉丁各报一次，直接拼接 | 文本+位置去重，留高置信度 |
| 坐标 | 只给中心点（像素） | 给像素包围盒 + 中心点，调用方自己算误差 |
| 预热 | 无 | 建屏后后台 `warmUp()` |

---

## 5. 本轮改动的文件

新增：
- `app/src/main/java/com/ai/assistance/quro/core/display/DisplayAwake.kt`
- `app/src/main/java/com/ai/assistance/quro/core/display/FrameGate.kt`
- `app/src/test/java/com/ai/assistance/quro/core/display/BgScreenDiagTest.kt`（14 个用例）

修改：
- `IQuroShellService.aidl`：`pokeUserActivity(int displayId) = 47`
- `QuroShellService.kt`：`pokeUserActivity` 实现（IPowerManager 反射 + 三道闸）
- `QuroVirtualScreen.kt`：保活接线、`readForAi()`、`BgReading`、`frameSignature()/awaitFrameChange()`
- `BgScreenReader.kt`：`resourceId/hint/focused/placeholder`、子节点名称聚合、删掉重复的 `toPromptText`
- `ScreenOcrBridge.kt`：重写为四态 `Result` + 去重 + 像素包围盒 + `warmUp()`
- `PhoneOcr.kt`（mika-phone）：新增 `warmUp()`
- `BackgroundScreenTool.kt`：`read` 走 `readForAi`、删除旧 `bgOcrFallback`、点击后回「变没变」、`status` 增加保活/无障碍/OCR 三行
- `QuroToolsAccessibility.kt`：主屏 OCR 兜底改用新 `Result`，失败不再伪装成「没字」

---

## 6. 仍未处理（下一轮候选）

1. **没有 `UiTreeNode` 窗口树**：上游 `ScreenReader` 会把每个窗口的节点树一并返回，
   用于诊断「弹窗盖住了什么」「滚动容器里还有几屏」。我们只有扁平元素表。
2. **没有滚轮选择器识别**（上游 `WheelMath.looksLikeWheel`）：日期/地区选择器只能靠滑动硬怼。
3. **`open` 之后没有 `awaitOpened` 轮询**：上游会轮询「目标包是否真的到了前台」再返回，
   我们靠下一次 `read` 的帧稳定等待兜底，语义上仍弱一层。
4. **`screenshot` 仍是全尺寸 PNG**（`quality` 对 PNG 无效，编码 300~800ms）：
   若后续截图频率上升，应改 JPEG 或按需缩放。
