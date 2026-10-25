# DOM 观察者清单（0.14.10 审计 §7.3 核对结论）

审计 §7.3 的修法建议是「其余观察者改为共享一个 rAF 合帧的 DOM 版本信号」。这一条**没有按原文执行**，
逐条核对后按「能证明行为不变的才动手，证明不了的不动」处理。本文件记录每一条的结论与判据，
供后续 PR 逐条核对。审计原文见 `docs/0.14.9-PERF-POWER-STARTUP-AUDIT-2026-10-21.md` §7.3。

## 一、§7.3 原文对 back-stack 的建议已被实测证伪

原文：`back-stack` 去掉 `childList` 观察，只保留 `OBSERVED_ATTRIBUTES`（返回键前已有一次同步 `sync()` 兜底）。

问题在于那个「兜底」进不去：

```kotlin
// app/src/main/java/com/ai/assistance/quro/dshmobile/BackGate.kt:52-56
fun decide(canGoBack: Boolean, pageStackAvailable: Boolean): Decision = when {
  canGoBack -> Decision.GO_BACK_HISTORY
  pageStackAvailable -> Decision.DISPATCH_PAGE_STACK   // 只有这一支会调 window.__dshBack
  else -> Decision.FINISH_ACTIVITY
}
```

`window.__dshBack` = `popTop()` 只有进到 `DISPATCH_PAGE_STACK` 才会被执行，而进那一支的前提正是
「层栈非空」这个由 JS 上行给壳的信号本身。信号因为看不见 childList 而不产生，壳侧永远进 `FINISH_ACTIVITY`。
所谓「兜底一次同步 sync()」根本不会被调用。

实测（`tests/back-stack-observer.spec.ts`）：把 `childList` 摘掉后，React 提交的 dialog、
触发器菜单、附件来源菜单三层——它们的角色属性都在**创建时**写好，插入文档不产生任何属性变更——
深度全部停在 0。

实际修法：**保留观察面，回调合帧**（一批 DOM 变更只做一次检测），见 `src/client/mobile/back-stack.ts`。

## 二、逐条结论

| 文件 | 审计点名的位置 | 现状 | 结论 |
|---|---|---|---|
| `back-stack.ts` | `:341` 回调 | 世界级观察者 + 属性过滤，回调同步 `sync()` | **已改：回调合帧**（宏任务，见第一节） |
| `main-panel-back.ts` | `:89-99` | 回调只调 `schedule()`，`schedule()` 用 `queueMicrotask` 合帧 | 不动：已有合帧 |
| `settings-nav.ts` | `:226-234` | 回调只调 `schedule()`，`schedule()` 用 `queueMicrotask` 合帧 | 不动：已有合帧 |
| `top-bar-fold.ts` | `:112-115` | 回调只调 `schedule()`，`schedule()` 用 rAF 合帧 | 合帧已有；写入收敛属 §7.9，已在那一项改掉 |
| `sidebar-gestures.ts` | `:350-373` | 回调只登记脏子树，50ms 定时合并；几何与 computed style 延到手势结束后才读 | 不动：本仓最讲究的一处 |
| `sidebar-bubbles.ts` | `:143-149` | 回调先 `records.some(relevant())` 逐条过滤，命中才 rAF 合帧 | 不动：无关批次不进同步路径 |
| `attachment-picker-menu.ts` | `:142-143` | 回调里没有全文档查询：`mountSetDirty()` 只看已挂载节点，未挂载时逐条 `addedNodes` 探针；命中才 `queueMicrotask` 调度全量 `syncMounts()` | 不动：已有两级廉价退出 |
| `reference-menu.ts` | `:215-216` | 回调只调 `schedule()`（rAF 合帧），开销在合帧后的 `enhance()` | 不动：回调本身不做事 |
| `snapshot-panels-observer.ts` | `:88-97` | 回调 `records.some(relevant)` 过滤，相关批次才做一次全文档查询 + 类租约对账 | 不动：无关批次直接返回 |
| `form-marker.ts` | `:54-55` | 回调同步做两次全文档查询，写根元素上的全局事实 | **评估后未改**：合帧会把同步性从语义里拿掉，见第四节 |
| `native-browser-presentation.ts` | `:92-99` | 已拆成结构面与遮挡面两个观察者，各带窄 `attributeFilter` | 已在 §7.1 改掉 |

## 三、为什么不做一个「共享的 rAF 合帧 DOM 版本信号」

方案本身可行，但**它会把「谁在看什么」这件事从各模块手里拿走**，而本仓的观察者恰好是一人一套判据：

- `snapshot-panels-observer` 要的是「有 snapshot 覆盖层出现/离开」，不是「DOM 变过」；
- `sidebar-bubbles` 的 `relevant()` 是一张逐属性白名单（`class`/`style` 也要，但只在特定祖先上算数）；
- `attachment-picker-menu` 与 `main-panel-back` 都靠「已挂载节点是否还连着」做廉价退出，
  这个判据必须看到**自己挂的那几个节点**，共享信号给不了。

共享信号能省的只是「多个模块在同一帧被各叫一次」的那点调度开销；而一旦把「版本号变了」当成
「该做全量同步」，本来靠 `relevant()`/`dirty()` 廉价退出的批次就会开始做全文档查询——**比现在更重**。
要把这点省回来，就得给共享信号加上「哪些节点变了」的语义，那它就不再是「一个版本号」，
而是一个各模块判据的并集；判据并集等于每个模块都开始为别人的变更买单。

结论：审计说的「宁可少合并，也不要引入语义漂移」在这里就是判据——**不合并**。
每个模块各自的观察面与廉价退出的判据留在模块里，能被单独核对、单独回归。

## 四、form-marker.ts：为什么合帧反而会丢语义

`form-marker.ts` 的 `syncDom()` 在回调里同步做两次全文档查询（`[data-rightbar-col]` 的父元素、
`[role='dialog'][aria-modal='true']` 列表），看起来最像该被合帧的一个。评估后**没有改**：

它写的是**根元素上的两个全局事实**（`data-dsh-modal-open` 与 `data-dsh-settings-open`），
这两个属性是窄屏样式表里抽屉定位规则的祖先匹配输入。改成 rAF 合帧意味着：

1. 从「dialog 插入」到「根属性写上去」之间多出至少一帧。这一帧里 dialog 已经可见、抽屉却仍按
   「没有模态」定位——真机上就是一次可见的跳位。
2. 页面切到后台时 rAF 不派发，根属性会停在旧值上；而这个事实是给 CSS 用的，没有「回到前台补一次」
   的等价路径（不像 back-stack 有 `visibilitychange` 可以补结算）。

也就是说这一处「同步」不是没做优化，而是**它的消费者是 CSS 的祖先匹配**，同步性本身是语义的一部分。
真要省，该省的是那两个查询本身的成本（例如把 dialog 的查询范围收窄），那是另一件事，不在本条。

## 五、结论

- 审计 §7.3 对 back-stack 的具体建议（去 childList）经实测证伪，照做会导致返回键直接退出应用；
  实际修法为保留观察面 + 回调合帧。
- 其余 10 个观察者里：4 个回调只调各自的 `schedule()` 且 `schedule()` 已合帧
  （`main-panel-back` / `settings-nav` 用 `queueMicrotask`，`top-bar-fold` / `reference-menu` 用 rAF）；
  `sidebar-gestures` / `sidebar-bubbles` / `snapshot-panels-observer` / `attachment-picker-menu`
  在回调里按记录做增量过滤，无关批次不进入同步路径；`form-marker` 的同步性是语义的一部分（第四节）；
  `native-browser-presentation` 已在 §7.1 拆过。
- 「共享 rAF DOM 版本信号」未采用，理由见第三节。
