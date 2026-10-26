#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""开源组件与许可证清单的**唯一数据源**（#485 全项目排查）。

用法：
    python tools/gen_licenses.py

生成两个产物（同一份数据，避免两处不一致）：
  1. app/src/main/assets/legal/third_party_licenses.json —— 「设置 → 关于 Zorv AI → 开源许可声明」运行时读取
  2. NOTICE                                              —— 仓库根的分发声明（随包/随仓库交付）

排查口径（2026-10-26 全项目）：
  · Gradle：gradle/libs.versions.toml + 各模块 build.gradle.kts 的实际坐标，
    **许可证字段优先取自 Maven 元数据（POM <licenses>）或工件内 META-INF/LICENSE**，
    取不到时才用上游公开事实（例如 AndroidX 全系 Apache-2.0）。
    实测差异：Shizuku 是 **MIT**（旧清单写 Apache-2.0，错）；Yoga 是 MIT；
    xz 是 0BSD；org.json 是 Public Domain；kaml/kotlinx-serialization 是 Apache-2.0。
  · 原生二进制：app/src/full/jniLibs、terminal-core/src/main/jniLibs、
    assets/linux_env、assets/*.pack、assets/*.tar.xz。
  · 随包前端库：assets/runtimes、assets/libs、assets/www、assets/docs
    （许可证与版本取自文件 banner；p5.js=LGPL-2.1、d3=ISC、GSAP=非 OSI 的 GreenSock 许可）。
  · 内置 agent 引擎：dsh-runtime.pack 内 556 个 npm 包（实测分布见 engine_npm 段）。
  · 已剔除的过期条目（旧 NOTICE 有、仓内已不存在）：React/ReactDOM/Recharts/Sucrase（genui 模块已删）、
    libVLC（声明但零使用的死依赖）。
"""

import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JSON_OUT = os.path.join(ROOT, "app/src/main/assets/legal/third_party_licenses.json")
NOTICE_OUT = os.path.join(ROOT, "NOTICE")

GENERATED_AT = "2026-10-26"

# copyleft: 0=宽松  1=弱 copyleft（LGPL/MPL/EPL）  2=强 copyleft（GPL/AGPL）
E = lambda cat, name, lic, zh, en="", copyleft=0: dict(
    cat=cat, name=name, lic=lic, zh=zh, en=en, copyleft=copyleft
)

CATEGORIES = [
    dict(id="app",        zh="本应用",                 en="This application"),
    dict(id="feature",    zh="功能模块与第三方代码",     en="Feature modules & bundled code"),
    dict(id="android",    zh="Android / Jetpack / Compose", en="Android / Jetpack / Compose"),
    dict(id="kotlin",     zh="Kotlin 生态",             en="Kotlin ecosystem"),
    dict(id="javalibs",   zh="第三方 Java / Kotlin 库",  en="Third-party Java / Kotlin libraries"),
    dict(id="engines",    zh="离线模型引擎",            en="On-device model engines"),
    dict(id="runtimes",   zh="内置运行时与原生工具",      en="Bundled runtimes & native tools"),
    dict(id="engine_npm", zh="内置 agent 引擎（DeepSeek Harness）", en="Bundled agent engine (DeepSeek Harness)"),
    dict(id="frontend",   zh="随包前端库",              en="Bundled front-end libraries"),
    dict(id="docs",       zh="文档处理与字体",           en="Document processing & fonts"),
    dict(id="system",     zh="系统镜像",                en="System image"),
]

ENTRIES = [
    # ── 本应用 ───────────────────────────────────────────────────────────
    E("app", "ZorvAI（本应用）", "Apache-2.0",
      "本应用自身代码。仓库内含去品牌化移植的上游组件，其原始许可保留在各自文件头。",
      "First-party code. De-branded ports of upstream components keep their original notices."),

    # ── Android / Jetpack / Compose ──────────────────────────────────────
    E("android", "AndroidX（Core / AppCompat / Activity / Lifecycle / Navigation / Room / WorkManager / WebKit / Health Connect / DocumentFile / Annotation / DynamicAnimation / Concurrent-futures / SQLite）", "Apache-2.0",
      "Google AndroidX 全系库，逐条取自各 artifact 的 Maven 元数据。"),
    E("android", "Jetpack Compose（UI / Foundation / Material3 / Animation / Runtime）", "Apache-2.0",
      "声明式 UI 框架（BOM 2026.01.01 统一版本）。"),
    E("android", "Material Components for Android", "Apache-2.0", "Material Design 组件与主题。"),
    E("android", "Coil（io.coil-kt:coil-compose）", "Apache-2.0", "Compose 图片加载。"),
    E("android", "AndroidSVG（com.caverock:androidsvg）", "Apache-2.0", "SVG 渲染（GenUI 画布兜底）。"),
    E("android", "Facebook Yoga（com.facebook.yoga:yoga）", "MIT",
      "Flexbox 布局引擎。**注意旧清单标为 Apache-2.0，实测 Maven 元数据为 MIT。**"),
    E("android", "ZXing Core（com.google.zxing:core）", "Apache-2.0", "二维码编解码。"),
    E("android", "android-image-cropper（com.vanniktech）", "Apache-2.0", "图片裁剪。"),
    E("android", "Shizuku（dev.rikka.shizuku:api / :provider）", "MIT",
      "ADB 级特权通道。**注意旧清单标为 Apache-2.0，实测 Maven 元数据为 MIT。**"),
    E("android", "Markwon（core / ext-tables / ext-strikethrough）", "Apache-2.0", "Markdown 渲染（GenUI 频道）。"),
    E("android", "kaml（com.charleskorn.kaml）", "Apache-2.0", "YAML 解析（GenUI 扁平文档）。"),
    E("android", "GeckoView（org.mozilla.geckoview）", "MPL-2.0",
      "Gecko 浏览器内核（包内含 libxul.so / omni.ja / libnss3 网络安全服务）。弱 copyleft："
      "仅在修改其源码文件时要求公开该文件。其二进制还含 Mozilla 的 lgpllibs 组件（LGPL-2.1）。", copyleft=1),

    # ── Kotlin 生态 ─────────────────────────────────────────────────────
    E("kotlin", "Kotlin 标准库 / 编译器插件", "Apache-2.0", "JetBrains Kotlin。"),
    E("kotlin", "kotlinx.coroutines", "Apache-2.0", "协程运行时。"),
    E("kotlin", "kotlinx.serialization", "Apache-2.0", "JSON 序列化。"),

    # ── 第三方 Java / Kotlin 库 ─────────────────────────────────────────
    E("javalibs", "OkHttp（com.squareup.okhttp3）", "Apache-2.0", "HTTP 客户端。"),
    E("javalibs", "org.json", "Public Domain", "JSON 解析（20220924 起为公有领域）。"),
    E("javalibs", "Apache Commons Compress", "Apache-2.0", "压缩格式读写。"),
    E("javalibs", "XZ for Java（org.tukaani:xz）", "0BSD", "XZ 解压（Maven 元数据实测 0BSD）。"),
    E("javalibs", "Apache POI（poi / poi-ooxml）", "Apache-2.0", "Office 文档读写。"),
    E("javalibs", "StAX API（stax:stax-api）", "Apache-2.0", "XML 流式解析 API。"),
    E("javalibs", "Bouncy Castle（bcprov / bcpkix）", "Bouncy Castle Licence",
      "端侧签名所需的密码学库（MIT 风格）。"),
    E("javalibs", "Apache MINA SSHD", "Apache-2.0", "内置 SSH 服务端。"),
    E("javalibs", "SLF4J（api / nop）", "MIT", "日志门面。"),
    E("javalibs", "Apache FtpServer", "Apache-2.0", "内置 FTP 服务端。"),
    E("javalibs", "Eclipse JGit", "EPL-2.0",
      "纯 Java Git 实现。弱 copyleft：修改 JGit 源文件需公开该文件。", copyleft=1),
    E("javalibs", "SQLCipher for Android（net.zetetic）", "BSD-style (Zetetic)",
      "加密 SQLite（集群数据库）。"),
    E("javalibs", "JUnit 4", "EPL-2.0", "单元测试（不随发布包分发）。", copyleft=1),

    # ── 离线模型引擎 ────────────────────────────────────────────────────
    E("engines", "MNN（Alibaba Mobile Neural Network）", "Apache-2.0", "端侧推理引擎（源码编译，full flavor）。"),
    E("engines", "llama.cpp", "MIT", "GGUF 推理（源码编译，full flavor）。"),
    E("engines", "ncnn（Tencent）", "BSD-3-Clause", "端侧推理（libncnn.so）。"),
    E("engines", "Sherpa-NCNN（k2-fsa）", "Apache-2.0", "端侧语音识别/合成（Kotlin 封装源码 + 预编译 .so）。"),
    E("engines", "语音模型权重（MeloTTS 等）", "MIT",
      "随包模型权重按其上游模型卡许可分发（多为 MIT，允许商用）。"),

    # ── 离线模型引擎的第三方依赖（源码编译进 .so，实测各 LICENSE 文件）──
    E("engines", "MNN 第三方依赖（flatbuffers / protobuf / rapidjson / half / glm）", "Apache-2.0 / BSD-3-Clause / MIT",
      "随 MNN 编译进 libMNNWrapper.so：flatbuffers=Apache-2.0、protobuf=BSD-3-Clause、"
      "rapidjson=MIT、half=MIT、glm=MIT（已逐份比对各 3rd_party LICENSE）。"),
    E("engines", "Arm KleidiAI（MNN Arm 后端）", "Apache-2.0",
      "MNN 的 Arm 指令优化后端（kleidiai），编译进 libMNNWrapper.so。"),
    E("engines", "llama.cpp 第三方依赖（cpp-httplib / nlohmann-json / big-integer / nerdamer）", "MIT",
      "随 llama.cpp 编译进 libLlamaWrapper.so：cpp-httplib=MIT、nlohmann-json(jsonhpp)=MIT、"
      "big-integer=MIT、nerdamer=MIT。"),
    E("engines", "Sherpa-NCNN 依赖（k2-fsa）", "Apache-2.0", "端侧语音前后端，随 sherpa-ncnn 编译。"),

    # ── 内置运行时与原生工具 ────────────────────────────────────────────
    E("runtimes", "Node.js（含 V8 / OpenSSL / ICU / zlib）", "MIT",
      "内置 agent 引擎的运行时。V8 为 BSD-3-Clause、OpenSSL 为 Apache-2.0、ICU 为 Unicode 许可。"),
    E("runtimes", "Python 3.14（含 OpenSSL / SQLite / zlib）", "PSF-2.0",
      "内置 Python 运行时（libpython3.14.so 等）。"),
    E("runtimes", "GNU Bash", "GPL-3.0-or-later",
      "终端 shell。强 copyleft：源码随仓库提供（源码获取见 NOTICE）。", copyleft=2),
    E("runtimes", "BusyBox", "GPL-2.0-only",
      "常用命令集（ls/grep/find 等）。强 copyleft：源码随仓库提供。", copyleft=2),
    E("runtimes", "proot", "GPL-2.0-or-later",
      "无 root 的 chroot/挂载重定向。强 copyleft：源码随仓库提供。", copyleft=2),
    E("runtimes", "talloc", "LGPL-3.0-or-later",
      "proot 依赖的内存池库。弱 copyleft。", copyleft=1),
    E("runtimes", "sudo", "ISC", "特权命令包装（内置终端）。"),
    E("runtimes", "box64", "MIT", "x86_64 → arm64 指令翻译（部分内置通道）。"),
    E("runtimes", "Zig（mo-cc.pack）", "MIT", "墨语言工具链的 C 编译器后端。"),
    E("runtimes", "QEMU（qemu-x86_64-static，molang-ubuntu-addon.pack）", "GPL-2.0",
      "墨语言⇢Ubuntu 通道的用户态指令翻译器。强 copyleft。", copyleft=2),
    E("runtimes", "termux-exec / libandroid-support", "Apache-2.0",
      "Termux 生态的 exec 钩子与兼容层（Android 15+ 走 linker64 的前提）。"),
    E("runtimes", "ripgrep", "MIT OR Unlicense",
      "glob / grep 工具的搜索引擎（随包替换为 Android PIE 构建）。"),
    E("runtimes", "PCRE2", "BSD-3-Clause", "ripgrep 的 PCRE 后端（libpcre2-8.so）。"),
    E("runtimes", "QuickJS（Fabrice Bellard）", "MIT",
      "插件运行时的 JS 引擎，源码直接编译进 libquroplugin.so（app/src/main/jni/quickjs）。"),
    E("runtimes", "Termux terminal-emulator（termux.c + libtermux-terminal.so）", "GPL-3.0-or-later",
      "终端仿真核心（app/src/main/jni/termux/termux.c 与 libtermux-terminal.so），与上游 termux-app "
      "实现一致；上游 termux-app 为 GPL-3.0。**本次审计新发现，属需复核的强 copyleft 项**："
      "若要保持整包仅 Apache-2.0，需替换/重写该桥或按 GPL 义务提供源码。"
      "注意：dshmobile/dsh-shell-termux 是**另一组件（MIT）**，与此 GPL 终端库无关。", copyleft=2),
    E("runtimes", "dsh-shell-termux（harness 终端壳）", "MIT",
      "DeepSeek Harness 的 termux 风格 shell 包装（dshmobile/dsh-shell-termux，MIT）。"
      "与上方 termux-app 的 GPL 终端库是不同组件，本项无 copyleft 义务。"),
    E("runtimes", "Eclipse Compiler for Java（ECJ）", "EPL-2.0",
      "端侧 APK 构建用的 Java 编译器（assets/libs/common/ecj*.jar）。弱 copyleft。", copyleft=1),
    E("runtimes", "D8 / R8（AOSP）", "Apache-2.0",
      "dex 编译器与压缩器（assets/libs/common/d8*.jar）。"),
    E("runtimes", "Android 平台库 android.jar（AOSP）", "Apache-2.0",
      "端侧编译用的 Android 框架桩（assets/libs/common/android.jar）。"),

    # ── 内置 agent 引擎 ─────────────────────────────────────────────────
    E("engine_npm", "DeepSeek Harness 引擎（@deepseek-ai/* 包）", "见上游许可",
      "内置 agent 引擎本体与其 dsh-* 包，许可以上游仓库声明为准。"),
    E("engine_npm", "引擎内第三方 npm 包（约 900 个）", "MIT / Apache-2.0 / BSD / ISC 等",
      "内置 agent 引擎本体（dsh-runtime.pack 内约 556 个：MIT 468 / Apache-2.0 53 / BSD-3-Clause 15 /"
      "ISC 10 / MPL-2.0 2 / LGPL-3.0 2 / BSD-2-Clause 2 / 0BSD 1 / Public-Domain 2）"
      "与 harness 响应式前端（dsh-client-ui-responsive 内 343 个：MIT 320 / ISC 8 / Apache-2.0 4 /"
      "BSD-3-Clause 4 / BSD-2-Clause 2 / MPL-2.0 2 / MIT-0 1 / Python-2.0 1 / CC0-1.0 1）合计约 900 个。"),
    E("engine_npm", "LibreOffice Kit（@deepseek-ai/libreoffice-kit / -wasm）", "MPL-2.0",
      "文档转换的 LibreOffice wasm 构建。弱 copyleft：修改其源文件需公开该文件。", copyleft=1),
    E("engine_npm", "libvips（@img/sharp-libvips-*）", "LGPL-3.0-or-later",
      "sharp 的图像处理后端。弱 copyleft：以动态链接方式使用，可替换其 .so。", copyleft=1),
    E("engine_npm", "sharp（@img/sharp-*）", "Apache-2.0", "图像编解码（read_image 工具）。"),
    E("engine_npm", "tslib", "0BSD", "TypeScript 运行时助手。"),
    E("engine_npm", "argparse（node）", "Python-2.0", "CLI 参数解析。"),
    E("engine_npm", "fast-sha256", "Unlicense", "SHA-256 实现。"),

    # ── 随包前端库 ──────────────────────────────────────────────────────
    E("frontend", "Three.js", "MIT", "3D 渲染。"),
    E("frontend", "Draco（Google）", "Apache-2.0", "3D 网格压缩解码。"),
    E("frontend", "Babylon.js", "Apache-2.0", "3D 引擎（runtimes/babylon.min.js）。"),
    E("frontend", "p5.js", "LGPL-2.1",
      "创意编程库。弱 copyleft（以未修改的原文件分发）。", copyleft=1),
    E("frontend", "D3.js", "ISC", "数据驱动可视化。**旧 NOTICE 未标许可证，实测 ISC。**"),
    E("frontend", "ECharts（Apache）", "Apache-2.0", "图表库。"),
    E("frontend", "Chart.js", "MIT", "图表库。"),
    E("frontend", "Plotly.js", "MIT", "图表库。"),
    E("frontend", "KaTeX", "MIT",
      "数学公式排版（JS 为 MIT；其字体 KaTeX_*.ttf 为 SIL OFL-1.1，随 dsh 前端打包）。"),
    E("frontend", "marked.js", "MIT", "Markdown 渲染。"),
    E("frontend", "highlight.js", "BSD-3-Clause", "代码高亮。"),
    E("frontend", "Mermaid", "MIT", "图表与流程图。"),
    E("frontend", "CodeMirror", "MIT", "代码编辑器。"),
    E("frontend", "Brython", "BSD-3-Clause", "浏览器内 Python。"),
    E("frontend", "Vue 3 / Vue Router", "MIT", "前端框架。"),
    E("frontend", "React / ReactDOM", "MIT", "前端框架（runtimes/）。"),
    E("frontend", "Element Plus", "MIT", "Vue 组件库。"),
    E("frontend", "Bootstrap", "MIT", "CSS 框架。"),
    E("frontend", "Tailwind CSS（浏览器构建）", "MIT", "原子化 CSS。"),
    E("frontend", "Swiper", "MIT", "轮播组件。"),
    E("frontend", "Alpine.js", "MIT", "轻量响应式框架。"),
    E("frontend", "Matter.js", "MIT", "2D 物理引擎。"),
    E("frontend", "anime.js", "MIT", "动画引擎。"),
    E("frontend", "Zdog", "MIT", "伪 3D 绘制。"),
    E("frontend", "Obelisk.js", "MIT", "等距像素绘制。"),
    E("frontend", "CountUp.js", "MIT", "数字滚动。"),
    E("frontend", "htm", "Apache-2.0", "JSX 替代模板。"),
    E("frontend", "Dexie.js", "Apache-2.0", "IndexedDB 封装。"),
    E("frontend", "Lucide（图标）", "ISC", "界面图标集（res/drawable 内的矢量图标由其改写）。"),
    E("frontend", "GSAP（GreenSock）", "GreenSock Standard License",
      "动画库。**非 OSI 开源许可**：允许免费用于大多数场景，但再分发/商业插件条款独立，"
      "本项目仅以未修改的原文件随包分发。"),

    # ── 文档处理与字体 ──────────────────────────────────────────────────
    E("docs", "pdf.js（Mozilla）", "Apache-2.0", "PDF 渲染与预览。"),
    E("docs", "Adobe CMap Resources", "BSD-style (Adobe)", "CJK PDF 的 CMap 映射表。"),
    E("docs", "Mammoth.js", "BSD-2-Clause", "docx → HTML。"),
    E("docs", "SheetJS（xlsx.js）", "MIT",
      "电子表格读写。包内为 npm xlsx@0.18.5（MIT）；注意 SheetJS 官方 CDN ≥0.19 已转 GPLv3，"
      "须确认来源版本 ≤0.18.5（已核验包内文件无 GPL 声明，与 MIT 版一致）。"),
    E("docs", "Liberation Sans", "SIL OFL-1.1", "PDF 标准字体（可自由嵌入）。"),
    E("docs", "Foxit PDF Fonts（PDFium）", "BSD-3-Clause", "PDF 标准 14 字体替代。"),
    E("docs", "Inter", "SIL OFL-1.1", "界面字体（runtimes/fonts）。"),
    E("docs", "JetBrains Mono", "SIL OFL-1.1", "等宽字体（runtimes/fonts）。"),
    E("docs", "Space Grotesk", "SIL OFL-1.1", "标题字体（runtimes/fonts）。"),

    # ── 系统镜像 ────────────────────────────────────────────────────────
    E("system", "Ubuntu 24.04 LTS（Noble，aarch64 rootfs）", "混合（各软件包各自许可）",
      "内置的 Ubuntu 用户空间含数百个软件包，各自按其上游许可分发（GPL/LGPL/MIT/Apache 等）；"
      "完整清单见镜像内 /usr/share/doc/*/copyright。"),
    E("system", "Alpine Linux 环境（assets/linux_env）", "混合（各软件包各自许可）",
      "轻量 Linux 用户空间，各包按其上游许可分发。"),

    # ── 功能模块（逐特征排查；回应「全项目而非单页」审计要求）────────────
    E("feature", "LSPosed / Xposed 模块（app 自身即 opt-in 模块）", "Apache-2.0",
      "本应用经 assets/xposed_init 注册为可选 LSPosed 模块（QuroXposedModule，自有代码 Apache-2.0）；"
      "Xposed API 仅以 compileOnly 引用 xposed-stub（最小 API 桩，并非 Xposed 框架的 GPL 源码），"
      "Xposed/LSPosed 框架类由运行时的 LSPosed 注入，**不随 APK 分发**，故本模块无 GPL 再分发义务。"),
    E("feature", "USB / 无线调试（Shizuku + 自研 ADB-over-TCP）", "MIT",
      "基于 Shizuku（MIT）的 ADB 级通道 + 自研无线调试配对流程；未 fork 任何 GPL 项目（如 LADB）。无 copyleft。"),
    E("feature", "DeepSeek Harness（dshmobile 模块）", "GPL-3.0（Termux 快照）+ 引擎内 MPL/LGPL",
      "fork 自 kelai141/dsh-mobile-apk；内置 Termux 衍生运行时快照（Termux 基线 0.118.3，GPL-3.0，"
      "**强 copyleft 复核项**）与 npm 引擎（LibreOfficeKit MPL-2.0、libvips LGPL-3.0）。"
      "以独立可执行/动态库形式分发，不静态链接。", copyleft=2),
    E("feature", "墨语言 / Molang（terminal-core，自研语言）", "Apache-2.0（语言）+ 运行时含 GPL",
      "墨语言为自研语言（MolangIso 等自有代码）；但其运行时捆绑 proot(GPL-2.0+)/BusyBox(GPL-2.0)/"
      "GNU Bash(GPL-3.0)/QEMU(GPL-2.0)/Ubuntu rootfs(GPL-3.0)。语言本身无 GPL，运行时见『内置运行时』分类。"),
    E("feature", "GenUI Agent（genuiagent-sdk）", "Apache-2.0",
      "去品牌化移植自 Quor-a/GenUI-Agent（sdk 模块）；纯 Kotlin/Compose，无打包第三方库，无 GPL。"),
    E("feature", "APK 插件框架（plugin-* 系列）", "Apache-2.0（框架）+ QuickJS MIT",
      "自有插件框架（DexClassLoader 加载独立插件 APK + QuickJS MIT 运行时）；示例插件均为自研，无第三方 GPL。"),
    E("feature", "工具包运行时（kaleidobox）", "Apache-2.0（自研）+ EPL-2.0（ECJ 编译）",
      "去品牌化移植自 kaleido；设备内插件编译用 Eclipse ECJ（EPL-2.0，弱 copyleft）。无 GPL 源码打包。"),
    E("feature", "构建台（端侧 APK 编译）", "EPL-2.0（ECJ）+ Apache-2.0（D8/apksigner/android.jar）",
      "端侧编译链：ECJ(EPL-2.0，弱 copyleft)、D8/R8(Apache-2.0)、apksigner(Apache-2.0)、android.jar(AOSP Apache-2.0)。无 GPL。", copyleft=1),
    E("feature", "节点编辑器（自研 + Mermaid）", "MIT",
      "自研 HTML/SVG 节点图编辑器；仅导出预览用 Mermaid（MIT）。未使用 rete/litegraph/drawflow 等第三方图库。"),
    E("feature", "小程序（miniapp-sdk 自研 C++ JS 引擎）", "Apache-2.0",
      "自研 WXML/WXSS JS 引擎（miniapp-sdk，零外部依赖）+ WebView 运行时；所用 JS 库来自 assets/runtimes（MIT/Apache）。无 GPL。"),
    E("feature", "可视化编程（GenUI DSL + 动态 UI 解释器）", "Apache-2.0",
      "自研：GenUI JSON DSL → Compose 原生渲染、动态 UI 解释器（QuroUiNode 等）；未使用 Blockly/Node-RED。无 GPL。"),
]

# ────────────────────────────────────────────────────────────────────────
# 强/弱 copyleft 的展示顺序与义务说明
COPYLEFT_NOTE_ZH = (
    "本应用自身以 Apache-2.0 发布。上表标注为 GPL（强 copyleft）的组件（proot、BusyBox、"
    "GNU Bash、QEMU、Ubuntu rootfs、Termux 终端仿真、dshmobile 的 Termux 运行时快照）以**独立可执行程序 / 动态库**"
    "形式随包分发，本应用不与其静态链接、不修改其源码；对应源码可从各上游项目获取，或在仓库 NOTICE 中列出的地址获取。"
    "其中两处属**需复核项**：① Termux 终端库（GPL-3.0）编译进了应用自身的 libtermux-terminal.so；"
    "② dshmobile 的 Termux 衍生运行时快照（Termux 基线 0.118.3，GPL-3.0）随 DeepSeek Harness 分发。"
    "LGPL / MPL / EPL 组件（talloc、libvips、p5.js、LibreOffice Kit、GeckoView 及其 lgpllibs、"
    "JGit、ECJ）以**未修改**形式动态链接或原文件分发，如需替换其版本可自行重新打包。"
)
COPYLEFT_NOTE_EN = (
    "This application is licensed under Apache-2.0. Components marked GPL (strong copyleft: proot, "
    "BusyBox, GNU Bash, QEMU, Ubuntu rootfs, Termux terminal, dshmobile Termux runtime snapshot) ship "
    "as separate executables or dynamic libraries; the app neither statically links them nor modifies "
    "their sources. Two are review items: ① the Termux terminal library (GPL-3.0) is compiled into the "
    "app's own libtermux-terminal.so; ② the dshmobile Termux-derived runtime snapshot (Termux baseline "
    "0.118.3, GPL-3.0) ships with DeepSeek Harness. LGPL / MPL / EPL components (talloc, libvips, p5.js, "
    "LibreOffice Kit, GeckoView + its lgpllibs, JGit, ECJ) are used unmodified, linked dynamically or "
    "shipped as original files."
)


def build_json():
    by_cat = {}
    for e in ENTRIES:
        by_cat.setdefault(e["cat"], []).append(
            dict(name=e["name"], license=e["lic"], copyleft=e["copyleft"],
                 noteZh=e["zh"], noteEn=e["en"] or e["zh"])
        )
    # 许可证分布（按 license 字符串的主许可归并，便于界面显示概览）
    dist = {}
    for e in ENTRIES:
        key = e["lic"].split(" / ")[0].split(" OR ")[0].strip()
        dist[key] = dist.get(key, 0) + 1
    return dict(
        schema=1,
        generatedAt=GENERATED_AT,
        app=dict(name="ZorvAI", license="Apache-2.0",
                 source="https://github.com/Quor-a/ZorvAI"),
        total=len(ENTRIES),
        distribution=sorted(
            [dict(license=k, count=v) for k, v in dist.items()],
            key=lambda x: -x["count"],
        ),
        categories=[
            dict(id=c["id"], titleZh=c["zh"], titleEn=c["en"], entries=by_cat.get(c["id"], []))
            for c in CATEGORIES if by_cat.get(c["id"])
        ],
        notice=dict(zh=COPYLEFT_NOTE_ZH, en=COPYLEFT_NOTE_EN),
    )


def build_notice(data):
    lines = [
        "ZorvAI",
        "Copyright 2025-2026 ZorvAI",
        "",
        "本文件由 tools/gen_licenses.py 生成（数据源与「设置 → 关于 ZorvAI → 开源许可声明」",
        "在应用内展示的清单为同一份数据），生成日期：" + data["generatedAt"] + "。",
        "",
        "本应用自身以 Apache License 2.0 发布（见 LICENSE）。随包分发或链接的第三方组件",
        "各自保留其原有许可，摘要如下。标注 GPL 的组件以独立可执行程序分发，本应用不与",
        "其静态链接；对应源码可从上游项目获取。",
        "",
        "This file is generated by tools/gen_licenses.py; the in-app licence dialog renders the",
        "same dataset. The application itself is Apache-2.0 (see LICENSE).",
        "",
        "-" * 70,
        "THIRD-PARTY COMPONENTS AND LICENSES",
        "-" * 70,
        "",
    ]
    for cat in data["categories"]:
        # 中英标题相同的分类（如 Android / Kotlin 生态）不要重复打印成 "X / X"。
        heading = cat["titleZh"] if cat["titleZh"] == cat["titleEn"] else (cat["titleZh"] + " / " + cat["titleEn"])
        lines.append("## " + heading)
        lines.append("")
        for e in cat["entries"]:
            lines.append("  " + e["name"])
            lines.append("    License: " + e["license"])
            if e["noteZh"]:
                lines.append("    Note:    " + e["noteZh"])
        lines.append("")
    lines += [
        "-" * 70,
        "COPYLEFT OBLIGATIONS / copyleft 义务",
        "-" * 70,
        "",
        COPYLEFT_NOTE_ZH,
        "",
        COPYLEFT_NOTE_EN,
        "",
        "-" * 70,
        "SOURCE AVAILABILITY",
        "-" * 70,
        "",
        "  ZorvAI:        https://github.com/Quor-a/ZorvAI",
        "  proot:         https://proot-me.github.io/",
        "  BusyBox:       https://busybox.net/",
        "  GNU Bash:      https://www.gnu.org/software/bash/",
        "  QEMU:          https://www.qemu.org/",
        "  talloc:        https://talloc.samba.org/",
        "  Termux:        https://github.com/termux/termux-app",
        "  dsh-mobile:    https://github.com/kelai141/dsh-mobile-apk （Termux 快照基线 0.118.3, GPL-3.0）",
        "  SheetJS:       https://github.com/SheetJS/sheetjs （npm xlsx@0.18.5, MIT；CDN ≥0.19 为 GPLv3）",
        "  GeckoView:     https://hg.mozilla.org/mozilla-central/",
        "  MNN:           https://github.com/alibaba/MNN",
        "  llama.cpp:     https://github.com/ggml-org/llama.cpp",
        "  sherpa-ncnn:   https://github.com/k2-fsa/sherpa-ncnn",
        "  Ubuntu 24.04:  https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/",
        "",
        "  （注：reference-projects/ 下的 Agora/Eta/Kai/TinyContainer 等为独立示例工程，",
        "   各自带 LICENSE，不参与本 APK 构建，故不在上方清单内。）",
        "",
    ]
    return "\n".join(lines)


def main():
    data = build_json()
    os.makedirs(os.path.dirname(JSON_OUT), exist_ok=True)
    with open(JSON_OUT, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
        f.write("\n")
    with open(NOTICE_OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(build_notice(data))
    print("entries  =", data["total"])
    print("cats     =", len(data["categories"]))
    print("json     =", JSON_OUT)
    print("notice   =", NOTICE_OUT)
    for d in data["distribution"]:
        print("  %-32s %d" % (d["license"], d["count"]))


if __name__ == "__main__":
    main()
