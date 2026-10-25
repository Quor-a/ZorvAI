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
