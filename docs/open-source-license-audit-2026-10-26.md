# ZorvAI 开源协议全项目审计报告（2026-10-26）

> 范围：整个工程（30+ Gradle 模块），不仅「设置→关于→开源许可证」单页。
> 数据源：`tools/gen_licenses.py`（单一数据源）→ 同时生成 `app/src/main/assets/legal/third_party_licenses.json`（应用内展示）与根目录 `NOTICE`（随包/随仓库分发）。
> 生成日期：2026-10-26；当前条目 117 条、11 个分类。
> 重要更正：上一轮（1600）只排查了「依赖层 + native 层」，未逐功能模块登记。本轮补齐 11 个功能模块。

## 1. 方法论

- Gradle 坐标：`gradle/libs.versions.toml` + 各模块 `build.gradle.kts` 实际坐标；协议优先取 Maven POM `<licenses>` 或工件内 `META-INF/LICENSE`。
- 原生二进制：`app/src/full/jniLibs`、`terminal-core/.../jniLibs`、`assets/*.pack`、`assets/*.tar.xz`、`dshmobile/.../snapshot.tar.xz`。
- 随包前端库：`assets/runtimes`、`assets/libs`、`assets/www`、`assets/docs`（协议取自文件 banner）。
- 内置 agent 引擎：dsh-runtime.pack（约 556 npm 包）+ dsh-client-ui-responsive（约 343 npm 包）。
- 功能模块：逐模块读源码与构建文件，确认 fork 上游、打包的第三方二进制、GPL 是否实际进 APK（而非仅 compileOnly stub）。

## 2. 逐功能模块排查结果（回应点名清单）

| # | 功能 | 模块 | 第三方代码 / 上游 | GPL 是否进 APK | 结论 |
|---|------|------|-------------------|---------------|------|
| 1 | LSPosed 模块 | `app`（opt-in 模块）+ `xposed-stub` | 自有 `QuroXposedModule`（Apache-2.0）；Xposed API 仅 `compileOnly` stub，框架由运行时 LSPosed 注入 | **否** | 无 GPL 再分发义务 |
| 2 | USB / 无线调试 | `app/core/adb`、`terminal-core` | Shizuku（MIT）+ 自研 ADB-over-TCP | 否 | 未 fork LADB 等 GPL 项目 |
| 3 | DeepSeek Harness | `dshmobile` | fork `kelai141/dsh-mobile-apk`；Termux 快照（GPL-3.0）+ npm 引擎（LibreOfficeKit MPL-2.0、libvips LGPL-3.0） | **是** | **强 copyleft 复核项** |
| 4 | 墨语言 / Molang | `terminal-core` | 自研语言；运行时捆绑 proot(BusyBox GPL-2.0)/Bash(GPL-3.0)/QEMU(GPL-2.0)/Ubuntu rootfs(GPL-3.0) | 是 | 语言本身无 GPL，运行时见 runtime 分类 |
| 5 | GenUI Agent | `genuiagent-sdk` | fork `Quor-a/GenUI-Agent`（去品牌化，Apache-2.0 链） | 否 | 纯 Kotlin/Compose，无第三方 |
| 6 | APK 插件框架 | `plugin-*` 系列 | 自有（DexClassLoader + QuickJS MIT 运行时） | 否 | 示例插件均自研 |
| 7 | 工具包运行时 | `kaleidobox` | fork `kaleido`；设备内编译用 ECJ（EPL-2.0） | 否 | 无 GPL 源码 |
| 8 | 构建台 | `app/build/*` | ECJ(EPL-2.0) + D8/R8(apksigner/android.jar, Apache-2.0) | 否 | 仅弱 copyleft(ECJ) |
| 9 | 节点编辑器 | `app/assets/www/node_editor.html` | 自研 SVG 图；预览用 Mermaid（MIT） | 否 | 未用 rete/litegraph/drawflow |
| 10 | 小程序 | `miniapp-sdk` | 自研 C++ WXML/WXSS JS 引擎（零外部依赖）+ WebView 运行时 | 否 | 所用 JS 库来自 assets/runtimes |
| 11 | 可视化编程 | `genui/aiapp` + `core/ui/dynamicui` | 自研 GenUI DSL→Compose、动态 UI 解释器 | 否 | 未用 Blockly/Node-RED |

## 3. 强 copyleft（GPL）登记与合规立场

**实际随包分发的 GPL 组件（非仅 stub）：**
- `proot`（GPL-2.0-or-later）、`BusyBox`（GPL-2.0-only）、`GNU Bash`（GPL-3.0-or-later）—— 独立可执行，终端/墨语言通道。
- `QEMU`（GPL-2.0）—— 墨语言⇢Ubuntu 翻译器。
- `Ubuntu 24.04 rootfs` —— 混合许可，含大量 GPL-3.0 二进制（apt/bash/coreutils 等）。
- `Termux 终端库`（GPL-3.0）—— 编译进 `libtermux-terminal.so`（**需复核项①**）。
- `dshmobile` 的 `Termux 运行时快照`（GPL-3.0，基线 0.118.3）—— 随 DeepSeek Harness 分发（**需复核项②**）。

**项目立场（NOTICE 已写明）：** 标注 GPL 的组件以独立可执行程序 / 动态库形式分发，不与本应用静态链接、不修改其源码；对应源码可从各上游获取。该立场对 GPL-3.0 传染性是否成立**仍需法律复核**——这是本仓最大开源协议风险点。

**弱 copyleft（已登记，非 GPL）：** GeckoView(MPL-2.0)、JGit(EPL-2.0)、ECJ(EPL-2.0)、talloc(LGPL-3.0+)、libvips(LGPL-3.0)、LibreOfficeKit(MPL-2.0)、p5.js(LGPL-2.1)、GSAP(GreenSock 非 OSI 许可)。

## 4. 本轮核实的关键事实

- **SheetJS / xlsx**：包内 `xlsx.full.min.js` 文件头仅 `SheetJS -- sheetjs.com` 版权行，**无 GPL/SPDX 声明**，与 npm `xlsx@0.18.5`（MIT，最后一个 MIT 版）一致。注意 SheetJS 官方 CDN ≥0.19 已转 GPLv3，须确认来源版本 ≤0.18.5。
- **Shizuku**：实测 Maven 元数据为 MIT（旧清单误写 Apache-2.0，已更正）。
- **Yoga**：MIT（旧清单误写 Apache-2.0，已更正）。
- **D3.js**：ISC（旧清单未标注，已更正）。
- **p5.js**：LGPL-2.1（保留原判定，待二次确认）。

## 5. 行动项 / 待用户拍板

- [ ] Termux 终端库（GPL-3.0，编译进 `libtermux-terminal.so`）与 dshmobile Termux 快照（GPL-3.0）：替换实现 or 附源码？当前按「独立可执行 + 不静态链接」辩解。
- [ ] 是否将本审计随下一次发版进入 APK（需重新 `assembleFullRelease` 并把 APK 放桌面供真机验证）。
- [ ] SheetJS 来源版本锁定为 ≤0.18.5（MIT）并写入构建门禁，防止误拉 GPLv3 CDN 版。

## 6. 不在排查范围（独立示例工程）

`reference-projects/`（Agora/Eta/Kai/TinyContainer 等）各自带 LICENSE，**不参与本 APK 构建**，故未计入清单。
