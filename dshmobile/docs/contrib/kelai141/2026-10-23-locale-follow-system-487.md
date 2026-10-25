# 语言切到 English 后切不回「跟随系统」（issue 487，2026-10-23）

来源：apk#487 用户上报「语言选 English 后点『跟随系统』页面仍是英文，重进设置语言仍是 English」。
复现与验收在 MuMu 实例 127.0.0.1:16448（900x1600，density 240，系统语言 zh-Hans-CN）。

## 现象（实测复现，非推断）

1. 设置 → 通用设置 → 「Android 显示」区块的语言分段按钮，选 English：页面切英文，壳偏好存 en，profile patch 出现 `- id: locale / preference: en`。
2. 再点「跟随系统」：壳偏好确实一度写回 system，profile 的 locale 行也被删掉。
3. 但页面重载后，壳偏好又被写成 `zh`，profile 又长出 `- id: locale / preference: zh`。
   表面症状就是「跟随系统这一档不可用」——用户看到的是重进设置还停在某个固定语言。

## 真因

壳侧 `AppLanguage.bootstrapScript` 注入的 `__DSH_LOCALE__.onChange` 是引擎到壳侧的唯一通道，
而引擎在**页面加载期就会自己发布**，且不止一次：

- `apply()` 先无条件用「它激活时解析出的语言」调一次 `bridge.onChange(active)`；
- 紧接着 `LocaleRuntime.adopt(host)` 发现持久化设置作用域里的值与当前不同，`publish(target, true)`
  再触发 `locale/change`，订阅端又调一次 `bridge.onChange`；
- 配置镜像每次刷新（`settings/document-updated`）都会再走一遍 adopt。

旧判据是「只忽略第一次调用」（`var first=true`），假设页面加载只会回调一次。实测这假设不成立：
第二次自动发布被当成用户显式选择写回壳偏好，于是「跟随系统」被钉成设备语言（zh）。

这里有个隐蔽点：切回 system 时壳侧同时做了两件事——删掉 profile 的 locale 行、重载页面。
重载与 profile 重写是并发的，引擎这次读到的是「已删的行 + 内存里还残留 en 的宿主作用域」，
两次自动发布的值不同，于是第二次恰好不是被丢弃的那一次。

## 修法

判据从「第几次调用」改为「有没有真实用户意图」，两条同时成立才回写：

1. **必须有页内真实交互**：document 捕获阶段监听 `click` / `keydown` / `touchend`，置一个只在当前任务内有效的
   `armed` 标志（`setTimeout(0)` 清除）。引擎的自动发布永远不带交互，因此一律不写回。
2. **必须与壳当前显示的语言不同**：基准在 `onChange` 时向桥现读（`getUiLanguage()`），
   system 档按与 dsh 相同的规则本地解析（设备标签里第一个命中已发布 locale id 的，精确优先于主语言子标签，
   都不命中回落 en）。用户选了与当前显示相同的语言不构成新偏好，不写回。

两条合起来覆盖了「自动发布」与「用户点了但没改变显示语言」两种不该写的场景，
而真正改变显示语言的选择仍然照旧走桥 `setUiLanguage`，壳侧落偏好、同步 profile、重建原生界面。

### 第二版修正：基准不能是 Activity 创建期的常量

第一版把「当前显示的语言」烘成脚本里的常量。这是错的，因为 document-start 脚本
**每次 Activity 创建只注册一次**（`installLocaleBootstrap` 只在 onCreate 路径调用），
而 `reloadWebUI` 只做 `webView.reload()`、不重注册。于是同一次 Activity 内的第二次切换，
基准还是第一次创建时的值：

- Activity 创建时 mode=system（基准烘成 zh）→ 壳侧语言行选 English（壳偏好 en、页面英文）。
- 用户在引擎原生语言行选「中文」→ 引擎发布 zh，带真实点击，`armed` 命中。
- 但「zh 等于基准 zh」→ 写入被丢弃。profile 变 zh、页面变中文，壳偏好仍 en、原生界面仍英文。
  这是与 #487 同类后果（双真源分裂），方向相反。

所以基准改为 `onChange` 时调 `window.androidBridge.getUiLanguage()` 现读，烘入值只作读不到桥时的回退。
顺带把 `onChange` 的三档过滤加回来：桥只认 `system`/`zh`/`en`，语言包注册的其它 id
若照写会被 `setMode` 静默归一成 `system`，等于悄悄改了语义。

## 复验

- 代码层：`AppLanguageTest` 18 例全绿（新增 6 例：不信任调用次数、基准现读、烘入值只作回退、
  非三档丢弃、expectedLocale 两种档位）；`gradlew :app:testDebugUnitTest` 全量 0 失败；
  `check-kotlin-test-count.mjs` PASSED（逐类不低于基线）。
- 反证：把判据改回 `var first=true` 的旧形态，`bootstrapScriptDoesNotTrustAnInvocationCount` 立刻变红；
  把基准改回烘入常量，`bootstrapScriptReadsTheDisplayedLocaleAtCallTime` 变红。
- 场景仿真（Node 里跑同一份脚本，桩桥）：加载期自动发布 0 写、点 English 写 en、
  跟随系统重载 0 写、**「壳 en 时在引擎行选中文」写 zh**（第二版修正点）、
  非三档 id 丢弃、无手势发布 0 写。
- CDP 层与 ADB 用户层：见 PR 描述里的结论行与 `.deploy-tmp/issue-487/ui-*.png`。

## 为什么不是「切回 system 时顺带重启引擎」

issue 建议里提到这条兜底。它确实能绕开「宿主作用域残留 en」，代价是把改语言变成停引擎 + 起引擎
（现场实测引擎冷启 10s 量级），而且它治的是症状：只要 `onChange` 仍然把自动发布当用户选择，
下一次引擎重启、下一次配置推送还会复发。交互门控让这个通道本身只承载用户意图，不需要重启。
