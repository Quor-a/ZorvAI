# PR #355 审查结论

审查时间：2026-10-14
PR：https://github.com/kelai141/dsh-mobile-apk/pull/355
审查 head：`ee9f5c8de180db77453394351a862d224fbabfeb`
审查 base：`a35ebd5acbe113e21567619a43c17c6770b6a476`

结论：保留本地 0.14.7 修复，关闭 PR #355。该 PR 在首次单击切换会话后就会收起抽屉，可能截断上游标题双击重命名；本地实现延迟收起并允许第二次点击取消。PR 的设备脚本没有覆盖双击，且其通用状态订阅没有本地的 composer 自动聚焦/IME 保护。

## 根因与回归证据

上游实际实现位于 `dsh-client-ui-responsive/node_modules/@deepseek-ai/dsh-client-ui-workspace/lib/client.js`：`SessionNodeItem` 的 `onClick` 调用 `onOpen(node.id)`（1597-1599 行），标题的 `onDoubleClick` 才调用 `onRenameRequest`（1628-1631 行）。PR 的 `watchSessionNavigation` 在 store 通知后的微任务立即调用 `drawer.sync()`（PR head `dsh-client-ui-responsive/src/client/mobile/panel-nav-drawer.ts` 97-105 行）；`sync()` 在主会话 ID 改变时立即收起手机抽屉（62-72 行）。因此首次点击已经提交选择后，第二次标题点击可能无法到达原行。

PR 的 `scripts/verify-session-navigation.mjs` 用 `HTMLElement.click()` 做单次点击，没有派发 `dblclick` 或断言重命名仍可用。PR 单测验证状态变化、延迟更新、重开抽屉和订阅卸载，但未覆盖真实上游标题双击接线。

本地 `dsh-client-ui-responsive/src/client/mobile/sidebar-session-dismiss.ts` 在第一次 click 后等待 500ms，并在 `event.detail > 1` 时取消待执行收起（50-65 行）。`tests/sidebar-session-dismiss.spec.ts` 的“标题双击重命名保留”用例验证这个路径。本地同一监听在 capture 阶段启用 composer 焦点保护，并在用户主动 pointerdown 到 composer 后解除保护（69-79 行）；对应测试验证自动 focus 被阻止且用户主动 focus 可用。另有父代理报告的 G2 AVD 真实点击验证为 PASS，本审查未重新操作设备，以免干扰 SN17 构建。

## 远端状态和验证

审查时 PR 为 OPEN，head/base 与上列 SHA 一致，GitHub API `mergeable=true`、`mergeable_state=blocked`。head 上以下检查均已完成且结论为 SUCCESS：

- `冲突标记与敏感信息扫描`
- `Kotlin 编译门禁`
- `维护文档一致性（提示）`

PR 描述另外报告插件 533 项、Kotlin 1,276 项、竖屏/宽屏 CDP 和 Android 15 x86_64 AVD 原生操作通过；这些描述中的本地运行与设备结果未由本审查独立重跑。描述明确指出未验证重新打包 APK、arm64 真机和报告中的 Android 17 小米设备。

审查后已按用户授权在 PR 下留一条中文理由并关闭。关闭前重新确认 head SHA 未漂移，关闭后 API 确认为 CLOSED。评论：https://github.com/kelai141/dsh-mobile-apk/pull/355#issuecomment-6166234995

## 本地对应改动

保留本地 `SidebarSessionDismiss` 的延迟收起、双击取消与焦点保护；不删除其测试或相关接线。PR 只关闭，未合并；本审查没有修改当前两仓的功能代码、stage/reset/switch，也没有运行构建、单测或设备套件。
