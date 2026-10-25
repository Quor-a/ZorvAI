# 手动浅深色主题稳定性修复

- 基于最新已提交 APK 基线；原脏工作区未修改。未改版本号，未创建 tag 或 Release。
- 系统深色下选浅色，旧版偏好按钮和 DOM 均是浅色，WebView 却把白底再次压黑。仅引擎 WebView 关闭 algorithmic darkening / FORCE_DARK，外站与控制台保留原策略。
- 后续验证发现系统桥还会在 UI 挂载后覆盖 html 的 color-scheme。启动空画布仍保留 OS 底色兜底；UI 挂载后由页面主题服务独占配色，桥只通知 MQL。

## 代码与构建证据

- WebViewShimTest 13/13 通过；新断言在原始 MainActivity 接线临时副本上失败，修复副本通过。
- 全 Kotlin 115 类、1299 项：1297 PASS、2 个 Windows symlink 场景 SKIP、0 FAIL；数量/新鲜度门禁通过且 SKIP=0。
- host watchdog 44 项通过，含移除 guard 时手动浅/深色断言必红；其他 13 项与 smoke 通过。
- 完整开发构建第五轮成功：`theme-build-5.log`。x86_64 APK 为 200906437 字节，SHA-256 `140d21c5f40be9761f47973aa8f95ef345e1c491d7c7e4fde371dd65a26446a8`，v1/v2/v3 签名通过。先前失败日志全部保留，没有修改门禁或白名单。

## 三层设备证据与边界

- 独立模拟器 16384 覆盖安装上述新壳；升级指纹变为 `e0f5a2e35cd8633d9423ceaf3ae3667432089a24740ce954f59c5b4f1563bb19`，transaction/stage/previous 全清后才验 UI。未清用户数据。
- 该 APK 未包含后发现的 host guard；开发运行时再热推 guard `lib/index.js`，设备与本地 SHA-256 `f7030c3dc8df8becc3483d26b729bddc33f2d5509d5e9555b0b28a470f2b8fdb` 一致，双 profile 成功。
- 组合运行时 12/12 CDP 断言通过：手动浅/深色均跨 OS 明暗保持选中偏好、scheme、body 色一致；system 正常跟随；设置往返与应用进程重启后持久化正确。
- 横屏 1067×600、竖屏 600×1067 均有真实 ADB 点选、截图和人眼核对，浅色确为白底、深色确为深色。恢复原 app 跟随系统、night yes、auto1/user0与 rotation free。
- 本证据覆盖真实新壳 APK 与 guard 热推组合，不代表最终发布 APK 已验收；双 ABI/真机发布验收未做，本轮不发版。
- 证据总索引：`/mnt/d/coding/table-gesture-evidence/THEME-ACCEPTANCE.md`。最终完整镜像见下节。

## 完整镜像收口

剪贴板前置 PR #387 已独立合入。随后完整同步 host main 26512689，10 个镜像文件逐字节一致；lib/index.js SHA-256 为 3b4d6670e329893ed43d9e950e1265ac0b0a0ceecbb390054e9a48e0020842c2。完整版本热推到同一新壳后，手动浅/深色跨 OS 明暗的四项复验通过，跟随系统明暗两项复验通过；原生截图与 CDP 一致。原先12项记录仍保留，不冒充最终发布 APK 验收。
