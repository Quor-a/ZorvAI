# 右侧多任务气泡标签导航

窗口小于 768 CSS 像素时，使用当前面板标题和右上角三点按钮。展开后显示可滚动胶囊标签，选择后收起；长按 400ms 后在原分栏内排序，靠近边缘自动滚动。支持关闭、键盘操作、减少动画偏好和 Android 返回。

窗口扩大后恢复原横向标签和并排布局。导航沿用原始标签的顺序、名称、状态、关闭资格与动作；业务面板和工具资源继续由原提供方管理。原生 BrowserHost 在气泡打开及退出动画期间仅隐藏显示层，结束后恢复同一页面。

排序由配套 `sidebar-bubble-reorder-B1` 补丁提供，基于原 placeTab/focusTab 提交，拒绝失效会话、浮动页、跨分栏目标和未知产物。发布包及 Android 源码构建均有真实夹具、幂等与拒绝写入反例。

新增回归覆盖原标签栏重建、长按取消后的首次返回及焦点恢复、普通滚动与拖拽隔离、原生 IME 不缩小 visualViewport 时的边界、快速开关和原生浏览器显示避让。设备验收使用真实会话与工具、CDP 语义读回和 ADB 输入。未改版本号，不触发发布。

---

# 移动端表格浏览修复

目标：改善窄屏表格可读性，避免横向浏览表格时误开侧栏；不发布新版本。

基线：UI 4662e4d；APK 镜像 d19ab1b。原脏工作区保留，修改来自远端 main 的独立 worktree。

视频：27.63 秒。说明列被挤成逐字换行的高行；横滑时打开右侧文件面板。
根因：上游宽表格静止时 overflow-x:hidden，依赖 hover 才可滚动；首触点被侧栏接管。30vw 的单元格上限加剧说明换行。
修复：触屏/窄屏宽表格常驻原生横向滚动，提前配置 touch-action；按内容分配列宽，长说明最多 55vw/240px，字号不变。普通小表格及宽屏鼠标保留上游行为。

## 验证范围

- 代码层：构建、类型检查、70 项侧栏回归通过，含旧样式负控。CI 48 文件 / 718 PASS / 1 SKIP，SKIP 不计为通过。独立审查无可确认缺陷。
- 运行时：client.js SHA-256 为 9d12e596462595271eaafd379ed170d4a639f881514cb4ec52a8917236450363，与 16416 设备热推文件逐字节一致。该设备原壳 versionName=0.14.5/versionCode=47；证据只覆盖热推 runtime，不能当最终 APK 验收。
- 真实任务：模型在会话生成六列四行方案对照（长说明、行内代码、末列标记）及两列简表；只发目标和限制，不喂操作序列。原生 ADB 完成两端浏览、纵滑及表格外侧栏操作。
- CDP/ADB：16416 竖屏 360px：scrollLeft 0→282→776→0，侧栏均关闭；表格外横滑仍可打开右栏；表格内纵滑改变 transcript 位置。长说明内容宽 198px，短类型列内容宽 100px，小表格无页面溢出。
- 受控负控：仅禁用新 CSS 后，同一表格 overflow-x:hidden、scrollLeft=0，ADB 横滑触发 rightOpen=true；恢复 CSS 后原生横滚正常。
- 16416 横屏 800px：原生横滚 0→444，能看最右列；点击展开右栏有效。
- 桌面 1280px 细指针：静止 hidden / hover scroll，保持上游行为。
- 16384：安装新壳开发 APK（SHA-256 140d21c5f40be9761f47973aa8f95ef345e1c491d7c7e4fde371dd65a26446a8）后热推相同表格 JS，真实模型生成六列六方案对照。原生横屏 1067px 横滚 0→677→1005→0，竖屏 600px 横滚 0→530→1026；侧栏均关闭，无页面溢出。开发运行时完成代码/CDP/ADB 三层，非最终发布包。

本地证据：/mnt/d/coding/table-gesture-evidence/，其中 portrait-*.json/png、landscape-16416-*.json/png、desktop.json、sidebar-tests.log、ui-ci.log、code-map.log。
关联：源码 PR https://github.com/kelai141/dsh-client-ui-responsive/pull/40；独立主题修复 https://github.com/kelai141/dsh-mobile-apk/pull/384。

未创建版本。最终发版前必须从最终源码构建 APK，再装机验收。
