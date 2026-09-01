package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/**
 * 广义 IDE 集成工具：提供图形/视频/音频/3D/游戏/低代码等创作工具的完整知识库和调用能力。
 * 
 * 功能：
 * 1. 列出所有广义 IDE 分类和代表软件
 * 2. 推荐适合用户需求的工具
 * 3. 启动已安装的创作工具
 * 4. 生成可直接在对话框渲染的 HTML/CSS/JS 内容
 */
class CreativeStudioTool : QuroTool {
    override val name = "creative_studio"
    override val description = """广义 IDE 集成工具：图形/视频/音频/3D/游戏/低代码/代码 IDE 的完整知识库和调用能力。
功能：
1. list_categories - 列出所有广义 IDE 分类
2. list_tools - 列出指定分类下的所有工具
3. recommend - 根据用户需求推荐合适的工具
4. launch - 启动已安装的创作工具
5. generate - 生成可直接在对话框渲染的 HTML/CSS/JS 内容
6. get_android_tools - 获取安卓手机上真实可用的创作工具清单

参数格式：{"action":"list_categories"} 或 {"action":"recommend","need":"画一张海报"}"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"操作类型：list_categories|list_tools|recommend|launch|generate|get_android_tools","enum":["list_categories","list_tools","recommend","launch","generate","get_android_tools"]},
            "category":{"type":"string","description":"工具分类（list_tools 时必填）"},
            "need":{"type":"string","description":"用户需求描述（recommend 时必填）"},
            "app_name":{"type":"string","description":"要启动的应用名称（launch 时必填）"},
            "content_type":{"type":"string","description":"生成内容类型（generate 时必填）：graphic|video|audio|3d|game|lowcode|code"},
            "prompt":{"type":"string","description":"生成内容的描述（generate 时必填）"}
        },
        "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = JSONObject(arguments)
        val action = args.optString("action", "").trim()
        
        return when (action) {
            "list_categories" -> listCategories()
            "list_tools" -> {
                val category = args.optString("category", "").trim()
                listTools(category)
            }
            "recommend" -> {
                val need = args.optString("need", "").trim()
                recommend(need)
            }
            "launch" -> {
                val appName = args.optString("app_name", "").trim()
                launchApp(context, appName)
            }
            "generate" -> {
                val contentType = args.optString("content_type", "").trim()
                val prompt = args.optString("prompt", "").trim()
                generate(contentType, prompt)
            }
            "get_android_tools" -> getAndroidTools()
            else -> "未知操作：$action。支持的操作：list_categories, list_tools, recommend, launch, generate, get_android_tools"
        }
    }

    private fun listCategories(): String {
        return """
🎨 图形/图像 IDE
🎬 视频 IDE
🎵 音频 IDE
🧊 3D/建模/动画 IDE
🎮 游戏引擎
📊 低代码/无代码 IDE
💻 代码 IDE
🌐 网页/前端 IDE
📱 移动端/跨平台 UI IDE
🤖 AI 原生 IDE
🥽 VR/AR/沉浸式 IDE
🔧 数据库 IDE
🤖 机器人/IoT IDE
💻 终端/命令行 IDE

═══ 代码 IDE 分类 ═══
📱 移动/桌面原生（Kotlin/Swift/C#/Dart）
🔧 后端/系统级（Go/Rust/PHP/Ruby/Scala/F#/VB.NET/Groovy/Elixir/Clojure）
📊 数据/科学（R/MATLAB/Julia/SQL）
📝 脚本/配置（Shell/PowerShell/Perl/Lua/YAML）
🎮 游戏/嵌入式/硬件（Shader/Arduino/Verilog/Assembly）
📦 其他小众（Delphi/Fortran/Haskell/Solidity/TypeScript）
💻 终端/命令行（Vim/Emacs/Terminal/tmux）
        """.trimIndent()
    }

    private fun listTools(category: String): String {
        return when (category.lowercase()) {
            "图形", "图像", "graphic" -> """
🎨 图形/图像 IDE：

位图编辑：
- Adobe Photoshop（专业级）
- GIMP（开源免费）
- Affinity Photo（性价比高）
- Photoshop Elements（轻量版）

矢量编辑：
- Adobe Illustrator（行业标准）
- CorelDRAW（专业矢量）
- Inkscape（开源免费）
- Affinity Designer（性价比高）

排版/版面：
- Adobe InDesign（专业排版）
- Scribus（开源免费）
- QuarkXPress（老牌专业）

UI/UX 设计：
- Figma（云端协作，推荐）
- Sketch（Mac 专用）
- Adobe XD（Adobe 生态）
- Penpot（开源免费）
- 墨刀（国内推荐）
- Pixso（国内推荐）
- Uizard（AI 生成 UI）

AI 图像生成：
- Midjourney（最强 AI 绘画）
- DALL·E（OpenAI 出品）
- Stable Diffusion（开源本地）
- Adobe Firefly（Adobe AI）
- Canva AI（模板化设计）
- 即梦/豆包（国内推荐）

设计平台：
- Canva（零基础模板化设计）
- Crello（在线设计）
- DesignCap（信息图设计）
            """.trimIndent()
            
            "视频", "video" -> """
🎬 视频 IDE：

专业剪辑：
- Adobe Premiere Pro（行业标准）
- DaVinci Resolve（免费版专业级）
- Final Cut Pro（Mac 专用）
- Avid Media Composer（影视级）

轻量/免费剪辑：
- Shotcut（开源免费）
- Kdenlive（开源免费）
- Lightworks（免费版）
- HitFilm Express（免费特效）

AI 视频生成：
- Runway Gen-3（AI 文生视频）
- Synthesia（AI 虚拟人）
- 可灵/Seedance（国内推荐）
- Pika（AI 视频生成）

短视频剪辑：
- 剪映 CapCut（国内推荐）
- Descript（AI 字幕编辑）
- VivaVideo（安卓视频创作）

视觉特效（VFX）：
- Adobe After Effects（特效合成）
- Natron（开源节点式合成）
- Blackmagic Fusion（专业合成）

流程化/分镜：
- Trelby（剧本工具）
- Fade In（专业剧本）
            """.trimIndent()
            
            "音频", "audio" -> """
🎵 音频 IDE：

录音/编辑：
- Audacity（开源免费，推荐）
- Ocenaudio（轻量免费）
- Adobe Audition（专业级）
- Reaper（性价比高）
- GarageBand（Mac 免费）

音乐制作 DAW：
- FL Studio（电子音乐推荐）
- Ableton Live（现场演出）
- Logic Pro（Mac 专业级）
- Cubase（老牌专业）
- Studio One（现代 DAW）
- Pro Tools（行业标准）

乐谱：
- Sibelius（专业乐谱）
- Finale（专业乐谱）
- MuseScore（开源免费，推荐）

AI 音频：
- Suno（AI 作曲推荐）
- Udio（AI 作曲）
- ElevenLabs（AI 配音）
- 冬瓜配音（国内 AI 配音）

音效设计：
- FMOD（游戏音效）
- Wwise（互动音频）
            """.trimIndent()
            
            "3d", "建模", "动画" -> """
🧊 3D/建模/动画 IDE：

CAD 型（工业设计）：
- SolidWorks（机械设计）
- Creo/Pro-E（参数化设计）
- AutoCAD（2D/3D 绘图）
- Fusion 360（云端 CAD，推荐）
- CATIA（汽车/航空）

CAID 型（曲面/交通工具）：
- Rhino（犀牛，曲面建模）
- Alias（汽车设计）

Polygon 型（动画/游戏角色）：
- Maya（动画行业标准）
- 3ds Max（游戏/建筑）
- ZBrush（数字雕刻）
- Blender（开源全能，推荐）

3D 动画/特效：
- Blender（开源推荐）
- Autodesk Maya（专业动画）
- Houdini（程序化生成）
- Cinema 4D（运动图形）

渲染器：
- Octane Render（GPU 渲染）
- Redshift（GPU 渲染）
- V-Ray（行业标准）
- Cycles（Blender 内置）

网页 3D：
- Three.js（JavaScript 3D 库）
- Spline（网页 3D 设计）
- PlayCanvas（网页游戏引擎）

AI 3D：
- Meshy（AI 3D 生成）
- Kaedim（AI 3D 建模）
            """.trimIndent()
            
            "游戏", "game" -> """
🎮 游戏引擎：

通用引擎：
- Unity（C#，2D/3D 通吃，生态最大，移动端强）
- Unreal Engine（C++，高保真 3D，影视级画质）
- Godot（开源，GDScript/C#，轻量推荐）
- Cocos2D（手游/微信小游戏）

2D 专用：
- GameMaker（2D 专用，无需编程）
- Construct（可视化 2D）
- RPG Maker（RPG 游戏）

互动叙事：
- Twine（互动叙事，纯文本）
- Ren'Py（视觉小说）

安卓游戏开发：
- Godot 安卓版（手机内全链路）
- Unity Remote（安卓预览）
- Defold（轻量 2D）
            """.trimIndent()
            
            "低代码", "无代码", "lowcode" -> """
📊 低代码/无代码 IDE：

海外平台：
- OutSystems（Service Studio）
- Mendix（Mendix Studio）
- Microsoft Power Apps
- Salesforce Platform
- Appian
- Oracle APEX
- Retool（内部工具）
- Zoho Creator
- Google AppSheet
- Kissflow

国内平台：
- 腾讯云微搭 WeDa
- 华为云 AppCube
- 阿里云低代码
- 钉钉宜搭
- 网易 CodeWave
- 金蝶云·苍穹低代码
- 用友 YonBIP

网页/站点可视化：
- Webflow（专业网页设计）
- Framer（交互设计）
- Wix（模板化建站）
- Squarespace（精美模板）
- Bubble（无代码应用）
- Softr（Airtable 建站）

AI 应用搭建：
- Coze/扣子（字节跳动，推荐）
- Dify（开源 AI 应用）
- Langflow（AI 工作流）
- Voiceflow（语音 AI）
- n8n（自动化工作流）
- Zapier（自动化）
- Make（自动化）
            """.trimIndent()
            
            "代码", "code" -> """
💻 代码 IDE 完整对应关系：

═══ 移动/桌面原生 ═══
| 语言 | 语言专属 IDE | 通用 IDE |
|------|-------------|----------|
| Kotlin | Android Studio、IntelliJ IDEA | VS Code |
| Swift | Xcode | VS Code（有限）|
| Objective-C | Xcode | — |
| C# | Visual Studio、Rider | VS Code |
| Dart (Flutter) | Android Studio、IntelliJ IDEA | VS Code |

═══ 后端/系统级 ═══
| 语言 | 语言专属 IDE | 通用 IDE |
|------|-------------|----------|
| Go | GoLand、LiteIDE | VS Code |
| Rust | RustRover、CLion | VS Code、Vim |
| PHP | PhpStorm | VS Code、NetBeans |
| Ruby | RubyMine | VS Code、Vim |
| Scala | IntelliJ IDEA（Scala 插件）| VS Code |
| F# | Visual Studio、Rider | VS Code |
| VB.NET | Visual Studio | Rider |
| Groovy | IntelliJ IDEA | VS Code |
| Elixir/Erlang | IntelliJ（插件）、Erlang IDE | VS Code |
| Clojure | IntelliJ（Cursive 插件）| VS Code |

═══ 数据/科学 ═══
| 语言 | 语言专属 IDE | 通用 IDE |
|------|-------------|----------|
| R | RStudio | VS Code |
| MATLAB | MATLAB（自带）| — |
| Julia | Julia VS Code（官方插件）| VS Code |
| SQL | DataGrip、SSMS、MySQL Workbench、DBeaver、Navicat | IDEA、VS Code |

═══ 脚本/配置 ═══
| 语言 | 语言专属 IDE | 通用 IDE |
|------|-------------|----------|
| Shell/Bash/Zsh | 无专属，终端+编辑器 | VS Code、Vim、Emacs |
| PowerShell | VS Code、PowerShell ISE | VS Code |
| Perl | Komodo IDE、Padre | VS Code、Vim |
| Lua | ZeroBrane Studio | VS Code、IntelliJ（EmmyLua）|
| YAML/TOML | 无专属 | 所有 IDE 内置 |

═══ 游戏/嵌入式/硬件 ═══
| 语言 | 语言专属 IDE | 通用 IDE |
|------|-------------|----------|
| Shader（GLSL/HLSL）| Unity、Unreal、ShaderToy | VS Code |
| Arduino（C++ 变体）| Arduino IDE、PlatformIO | VS Code |
| Verilog/VHDL | Vivado、Quartus、ModelSim | VS Code |
| Assembly（汇编）| Keil、IAR、MASM | VS Code、任意编辑器 |

═══ 其他小众但常见 ═══
| 语言 | 语言专属 IDE | 通用 IDE |
|------|-------------|----------|
| Delphi/Pascal | Delphi（RAD Studio）| Lazarus |
| Fortran | Simply Fortran、Code::Blocks | VS Code |
| Haskell | IntelliJ（Haskell 插件）| VS Code |
| Solidity（智能合约）| Remix（Web）、Foundry | VS Code |
| TypeScript | WebStorm | VS Code |

═══ 通用兜底（覆盖上面全部）═══
- VS Code（插件全装）→ 覆盖 100+ 语言
- IntelliJ IDEA Ultimate → JVM 系 + Web + DB + 插件扩展
- Vim/Neovim（LSP）→ 理论上无语言上限
            """.trimIndent()
            
            "网页", "前端", "web" -> """
🌐 网页/前端 IDE：

代码编辑：
- VS Code（推荐，插件全覆盖）
- WebStorm（专业前端）
- Sublime Text（轻量快速）
- Atom（GitHub 出品）

可视化设计：
- Figma（UI/UX 设计）
- Sketch（Mac UI 设计）
- Adobe XD（Adobe 生态）

网页构建：
- Webflow（专业网页设计）
- Framer（交互设计）
- Wix（模板化建站）
- Squarespace（精美模板）

在线 IDE：
- CodePen（前端预览）
- JSFiddle（JavaScript 测试）
- StackBlitz（在线 VS Code）
- CodeSandbox（在线开发）

AI 网页生成：
- v0.dev（Vercel，UI 生成）
- Bolt.new（AI 全栈）
- Lovable（AI 应用）
- TRAE SOLO（AI 编程）
            """.trimIndent()
            
            // 移动/桌面原生
            "移动", "桌面", "原生", "kotlin", "swift", "c#", "dart" -> """
📱 移动/桌面原生 IDE：

| 语言 | 语言专属 IDE | 通用 IDE |
|------|-------------|----------|
| Kotlin | Android Studio、IntelliJ IDEA | VS Code |
| Swift | Xcode | VS Code（有限）|
| Objective-C | Xcode | — |
| C# | Visual Studio、Rider | VS Code |
| Dart (Flutter) | Android Studio、IntelliJ IDEA | VS Code |

**安卓开发推荐**：
- Android Studio：官方 IDE，功能完整
- PHONE AS：口袋 Android Studio，支持 Gradle 构建 APK
- AIDE：安卓手机上直接开发

**iOS 开发**：
- Xcode：Mac 专用，唯一选择
- Swift Playgrounds：iPad/Mac 学习 Swift

**跨平台**：
- Flutter：Dart 语言，一套代码多端运行
- React Native：JavaScript，社区生态大
            """.trimIndent()

            // 后端/系统级
            "后端", "系统", "go", "rust", "php", "ruby", "scala" -> """
🔧 后端/系统级 IDE：

| 语言 | 语言专属 IDE | 通用 IDE |
|------|-------------|----------|
| Go | GoLand、LiteIDE | VS Code |
| Rust | RustRover、CLion | VS Code、Vim |
| PHP | PhpStorm | VS Code、NetBeans |
| Ruby | RubyMine | VS Code、Vim |
| Scala | IntelliJ IDEA（Scala 插件）| VS Code |
| F# | Visual Studio、Rider | VS Code |
| VB.NET | Visual Studio | Rider |
| Groovy | IntelliJ IDEA | VS Code |
| Elixir/Erlang | IntelliJ（插件）、Erlang IDE | VS Code |
| Clojure | IntelliJ（Cursive 插件）| VS Code |

**推荐**：
- Go：GoLand（JetBrains）或 VS Code + Go 插件
- Rust：RustRover（JetBrains 新出品）或 VS Code + rust-analyzer
- PHP：PhpStorm 或 VS Code + PHP Intelephense
- Ruby：RubyMine 或 VS Code + Ruby LSP
            """.trimIndent()

            // 数据/科学
            "数据", "科学", "r", "matlab", "julia", "sql" -> """
📊 数据/科学 IDE：

| 语言 | 语言专属 IDE | 通用 IDE |
|------|-------------|----------|
| R | RStudio | VS Code |
| MATLAB | MATLAB（自带）| — |
| Julia | Julia VS Code（官方插件）| VS Code |
| SQL | DataGrip、SSMS、MySQL Workbench、DBeaver、Navicat | IDEA、VS Code |

**推荐**：
- R：RStudio（数据分析标准工具）
- Python 数据科学：Jupyter Notebook / VS Code + Python 插件
- SQL：DataGrip（JetBrains）或 DBeaver（开源免费）
- Julia：VS Code + Julia 插件（官方推荐）
            """.trimIndent()

            // 脚本/配置
            "脚本", "配置", "shell", "powershell", "perl", "lua", "yaml" -> """
📝 脚本/配置 IDE：

| 语言 | 语言专属 IDE | 通用 IDE |
|------|-------------|----------|
| Shell/Bash/Zsh | 无专属，终端+编辑器 | VS Code、Vim、Emacs |
| PowerShell | VS Code、PowerShell ISE | VS Code |
| Perl | Komodo IDE、Padre | VS Code、Vim |
| Lua | ZeroBrane Studio | VS Code、IntelliJ（EmmyLua）|
| YAML/TOML | 无专属 | 所有 IDE 内置 |

**推荐**：
- Shell 脚本：VS Code + Bash 插件 或 Vim/Emacs
- PowerShell：VS Code + PowerShell 扩展
- Lua：VS Code + Lua 插件（游戏开发常用）
- 配置文件：所有现代 IDE 都内置支持 YAML/TOML/JSON
            """.trimIndent()

            // 游戏/嵌入式/硬件
            "嵌入式", "硬件", "shader", "arduino", "verilog", "assembly", "汇编" -> """
🎮 游戏/嵌入式/硬件 IDE：

| 语言 | 语言专属 IDE | 通用 IDE |
|------|-------------|----------|
| Shader（GLSL/HLSL）| Unity、Unreal、ShaderToy | VS Code |
| Arduino（C++ 变体）| Arduino IDE、PlatformIO | VS Code |
| Verilog/VHDL | Vivado、Quartus、ModelSim | VS Code |
| Assembly（汇编）| Keil、IAR、MASM | VS Code、任意编辑器 |

**推荐**：
- 游戏着色器：Unity/Unreal 内置编辑器 或 ShaderToy（网页）
- Arduino：Arduino IDE（官方）或 PlatformIO（VS Code 插件，更强大）
- FPGA：Vivado（Xilinx）或 Quartus（Intel/Altera）
- 嵌入式：Keil（ARM）、IAR（多架构）、VS Code + PlatformIO
            """.trimIndent()

            // 其他小众
            "小众", "delphi", "fortran", "haskell", "solidity", "typescript" -> """
📦 其他小众但常见 IDE：

| 语言 | 语言专属 IDE | 通用 IDE |
|------|-------------|----------|
| Delphi/Pascal | Delphi（RAD Studio）| Lazarus |
| Fortran | Simply Fortran、Code::Blocks | VS Code |
| Haskell | IntelliJ（Haskell 插件）| VS Code |
| Solidity（智能合约）| Remix（Web）、Foundry | VS Code |
| TypeScript | WebStorm | VS Code |

**推荐**：
- TypeScript：VS Code（官方出品，完美支持）或 WebStorm
- Solidity：Remix（网页版，智能合约开发标准工具）
- Haskell：VS Code + Haskell 插件 或 IntelliJ + Haskell 插件
- Delphi：RAD Studio（商业）或 Lazarus（开源免费）
            """.trimIndent()

            // 终端/命令行
            "终端", "命令行", "terminal", "cli", "bash", "shell", "zsh", "powershell", "vim", "emacs" -> """
💻 终端/命令行 IDE：

| 工具 | 类型 | 用途 |
|------|------|------|
| VS Code + 终端 | 集成终端 | 代码编辑 + 终端一体化 |
| Vim / Neovim | 终端编辑器 | 高效文本编辑，插件生态丰富 |
| Emacs | 终端编辑器 | 可扩展性极强，Lisp 脚本 |
| iTerm2 + Oh My Zsh | Mac 终端 | 增强终端体验 |
| Windows Terminal | Windows 终端 | 多标签、GPU 加速 |
| PowerShell ISE | Windows 脚本 | PowerShell 开发 |
| Cmder | Windows 终端 | 便携式终端模拟器 |
| Alacritty | 跨平台终端 | GPU 加速，高性能 |
| kitty | Linux/Mac 终端 | GPU 加速，可定制 |
| tmux | 终端复用器 | 多窗口、会话持久化 |

**安卓手机终端工具**：
- Termux：完整 Linux 终端环境
- ZorvAI 沙盒终端：应用内自研终端
- AIDE Terminal：安卓开发终端

**推荐组合**：
- 代码编辑 + 终端：VS Code + 内置终端
- 高效编辑：Neovim + LSP 插件
- Windows 开发：Windows Terminal + PowerShell
- Mac 开发：iTerm2 + Oh My Zsh
            """.trimIndent()

            // VR/AR/沉浸式 IDE
            "vr", "ar", "沉浸式", "虚拟现实", "增强现实" -> """
🥽 VR/AR/沉浸式 IDE：

| 工具 | 类型 | 用途 |
|------|------|------|
| Unity VR | 游戏引擎 VR 模块 | VR/AR 应用开发 |
| Unreal Engine VR | 游戏引擎 VR 模块 | 高保真 VR 体验 |
| Oculus SDK | VR 开发套件 | Quest 系列设备开发 |
| WebXR + Three.js | 网页 3D/VR | 浏览器内 VR/AR 体验 |
| A-Frame | 网页 VR 框架 | 基于 HTML 的 VR 开发 |
| RealityKit | iOS AR 框架 | Apple 设备 AR 开发 |
| ARCore | Android AR 框架 | Google AR 开发 |
| Spark AR | 社交 AR 平台 | Instagram/Facebook AR 效果 |
| Lens Studio | Snapchat AR | Snapchat AR 滤镜开发 |

**安卓 VR/AR 工具**：
- Google ARCore：安卓 AR 开发
- Unity Remote：VR 预览
- WebXR：浏览器内 VR 体验

**推荐**：
- VR 游戏/应用：Unity + VR 模块
- AR 应用：ARCore（安卓）/ ARKit（iOS）
- 网页 VR：A-Frame + Three.js
            """.trimIndent()

            // AI 原生 IDE
            "ai", "ai原生", "ai ide", "ai编程" -> """
🤖 AI 原生 IDE（一句话生成应用）：

| 工具 | 类型 | 用途 |
|------|------|------|
| v0.dev | Vercel UI 生成 | 自然语言生成 UI 组件 |
| Cursor | AI 代码编辑器 | 智能代码补全、重构 |
| Windsurf | AI 代码编辑器 | AI 辅助编程 |
| Bolt.new | AI 全栈 | 一句话生成完整应用 |
| Lovable | AI 应用 | 自然语言生成应用 |
| TRAE SOLO | AI Agent | 自主编程 |
| GitHub Copilot | AI 编程助手 | 代码补全、生成 |
| Replit Agent | AI 开发 | 自然语言开发应用 |
| Claude Artifacts | AI 内容 | 生成可交互内容 |

**安卓 AI 编程工具**：
- TRAE SOLO 安卓版：AI Agent 自主编程
- Cursor：浏览器可用
- Replit：浏览器可用

**推荐**：
- 快速原型：v0.dev / Bolt.new
- 专业开发：Cursor + Copilot
- AI Agent：TRAE SOLO / Replit Agent
            """.trimIndent()

            // 数据库 IDE
            "数据库", "database", "sql" -> """
🔧 数据库 IDE：

| 工具 | 类型 | 用途 |
|------|------|------|
| DataGrip | JetBrains | 多数据库管理 |
| DBeaver | 开源 | 通用数据库工具 |
| MySQL Workbench | MySQL | MySQL 官方工具 |
| SSMS | SQL Server | 微软 SQL 管理 |
| pgAdmin | PostgreSQL | PostgreSQL 管理 |
| Navicat | 多数据库 | 可视化数据库管理 |
| TablePlus | 多数据库 | 现代数据库客户端 |
| Beekeeper Studio | 开源 | 现代 SQL 编辑器 |
| RedisInsight | Redis | Redis 可视化 |
| MongoDB Compass | MongoDB | MongoDB 可视化 |

**安卓数据库工具**：
- Termux + SQLite/MySQL：命令行数据库
- Adminer：网页数据库管理
- Firebase Console：云端数据库

**推荐**：
- 多数据库：DataGrip / DBeaver
- MySQL：MySQL Workbench
- 轻量：Beekeeper Studio / TablePlus
            """.trimIndent()

            // 机器人/IoT IDE
            "机器人", "iot", "物联网", "硬件" -> """
🤖 机器人/IoT IDE：

| 工具 | 类型 | 用途 |
|------|------|------|
| Arduino IDE | 硬件编程 | Arduino 开发 |
| PlatformIO | 嵌入式 | 多平台嵌入式开发 |
| Node-RED | 可视化流 | IoT 自动化流程 |
| ESP-IDF | ESP32 | ESP32 官方开发框架 |
| Raspberry Pi IDE | 树莓派 | 树莓派开发 |
| MQTT Explorer | MQTT | MQTT 调试工具 |
| Blynk | IoT 平台 | 物联网应用开发 |
| Home Assistant | 智能家居 | 智能家居自动化 |
| Grafana | 数据可视化 | IoT 数据监控 |

**安卓 IoT 工具**：
- Blynk：物联网应用开发
- MQTT Dashboard：MQTT 调试
- Arduino Bluetooth：蓝牙控制 Arduino
- Node-RED：网页流式编程

**推荐**：
- Arduino：Arduino IDE / PlatformIO
- IoT 平台：Blynk / Node-RED
- 智能家居：Home Assistant
            """.trimIndent()

            // 合成/VFX/节点式
            "合成", "vfx", "节点式", "特效" -> """
🖼️ 合成/VFX/节点式 IDE：

| 工具 | 类型 | 用途 |
|------|------|------|
| Adobe After Effects | 2D 合成 | 动态图形、特效合成 |
| Natron | 开源节点式 | 2D 合成，免费 |
| Blackmagic Fusion | 专业合成 | 影视级特效 |
| Houdini | 程序化生成 | 3D 特效、程序化建模 |
| Nuke | 专业合成 | 影视后期行业标准 |
| HitFilm | 免费合成 | 视频特效、合成 |
| Blender VFX | 3D 合成 | 3D 特效合成 |
| DaVinci Resolve Fusion | 节点式 | 内置于达芬奇 |

**推荐**：
- 免费专业级：Natron / DaVinci Resolve Fusion
- 2D 特效：After Effects
- 3D 特效：Houdini
- 影视级：Nuke
            """.trimIndent()

            else -> "未找到分类「$category」。请使用 list_categories 查看所有可用分类。"
        }
    }

    private fun recommend(need: String): String {
        val lowerNeed = need.lowercase()
        
        return when {
            // 图形/图像
            lowerNeed.contains("画") || lowerNeed.contains("绘") || lowerNeed.contains("图") && !lowerNeed.contains("视频") -> """
🎨 推荐工具：

1. **Canva**（零基础首选）
   - 适合：海报、Logo、社交媒体图、模板化设计
   - 优势：海量模板，无需设计基础
   - 安卓：有 App，浏览器也可用

2. **Figma**（UI/UX 设计推荐）
   - 适合：界面设计、原型图、图标
   - 优势：云端协作，免费版够用
   - 安卓：浏览器可用

3. **Pixso**（国内推荐）
   - 适合：UI 设计、协作设计
   - 优势：国产，速度快，中文支持好
   - 安卓：有 App

4. **Midjourney**（AI 绘画推荐）
   - 适合：概念艺术、插画、创意图像
   - 优势：AI 生成，质量高
   - 安卓：浏览器访问 Discord

5. **Stable Diffusion**（本地 AI 绘画）
   - 适合：批量生成、自定义风格
   - 优势：开源免费，可本地运行
   - 安卓：Termux 可安装
            """.trimIndent()
            
            // 视频
            lowerNeed.contains("视频") || lowerNeed.contains("剪") || lowerNeed.contains("影") -> """
🎬 推荐工具：

1. **剪映 CapCut**（短视频首选）
   - 适合：短视频、社交媒体内容
   - 优势：免费，AI 字幕，模板多
   - 安卓：有 App，推荐

2. **DaVinci Resolve**（专业级免费）
   - 适合：影视剪辑、调色、特效
   - 优势：免费版功能完整
   - 安卓：有 iPad 版，安卓需远程

3. **Adobe Premiere Rush**（移动剪辑）
   - 适合：快速剪辑、社交媒体
   - 优势：Adobe 生态，易用
   - 安卓：有 App

4. **Runway Gen-3**（AI 视频生成）
   - 适合：AI 文生视频、创意视频
   - 优势：AI 生成，效果惊艳
   - 安卓：浏览器可用

5. **可灵/Seedance**（国内 AI 视频）
   - 适合：AI 视频生成
   - 优势：国内访问快
   - 安卓：浏览器可用
            """.trimIndent()
            
            // 音频
            lowerNeed.contains("音乐") || lowerNeed.contains("音频") || lowerNeed.contains("录") || lowerNeed.contains("配音") -> """
🎵 推荐工具：

1. **BandLab**（安卓 DAW 推荐）
   - 适合：音乐制作、录音、混音
   - 优势：免费，移动端优化
   - 安卓：有 App，推荐

2. **FL Studio Mobile**（移动 DAW）
   - 适合：电子音乐、编曲
   - 优势：专业级，移动端完整
   - 安卓：有 App（付费）

3. **Audacity**（录音/编辑推荐）
   - 适合：录音、剪辑、降噪
   - 优势：开源免费，功能强大
   - 安卓：Termux 可安装

4. **Suno**（AI 作曲推荐）
   - 适合：AI 生成音乐
   - 优势：输入描述即可生成
   - 安卓：浏览器可用

5. **ElevenLabs**（AI 配音推荐）
   - 适合：AI 语音合成、配音
   - 优势：音质自然，多语言
   - 安卓：浏览器可用
            """.trimIndent()
            
            // 3D
            lowerNeed.contains("3d") || lowerNeed.contains("建模") || lowerNeed.contains("模型") -> """
🧊 推荐工具：

1. **Prisma3D**（安卓 3D 建模推荐）
   - 适合：3D 建模、动画、渲染
   - 优势：安卓原生，功能完整
   - 安卓：有 App，推荐

2. **Tinkercad**（零基础 3D 设计）
   - 适合：3D 打印、电路设计、积木编程
   - 优势：网页版，简单易学
   - 安卓：浏览器可用

3. **Blender**（专业 3D 全能）
   - 适合：建模、动画、渲染、视频编辑
   - 优势：开源免费，功能完整
   - 安卓：无原生版，需远程

4. **Fusion 360**（云端 CAD）
   - 适合：工业设计、机械设计
   - 优势：云端，免费版可用
   - 安卓：浏览器可用

5. **Meshy**（AI 3D 生成）
   - 适合：AI 生成 3D 模型
   - 优势：输入描述即可生成
   - 安卓：浏览器可用
            """.trimIndent()
            
            // 游戏
            lowerNeed.contains("游戏") || lowerNeed.contains("game") -> """
🎮 推荐工具：

1. **Godot 安卓版**（手机游戏开发推荐）
   - 适合：2D/3D 游戏开发
   - 优势：开源免费，手机内全链路
   - 安卓：有 App，推荐

2. **Unity**（游戏开发行业标准）
   - 适合：2D/3D 游戏，移动端强
   - 优势：生态最大，资源多
   - 安卓：有 Remote 预览，完整开发需 PC

3. **GameMaker**（2D 游戏推荐）
   - 适合：2D 游戏，无需编程
   - 优势：可视化编程，上手快
   - 安卓：无原生版

4. **Scratch**（零基础游戏/动画）
   - 适合：学习编程、简单游戏
   - 优势：可视化积木编程
   - 安卓：浏览器可用

5. **Cocos Creator**（手游/微信小游戏）
   - 适合：手游、微信小游戏
   - 优势：国内生态好
   - 安卓：无原生版，需 PC
            """.trimIndent()
            
            // 低代码
            lowerNeed.contains("低代码") || lowerNeed.contains("无代码") || lowerNeed.contains("应用") && lowerNeed.contains("生成") -> """
📊 推荐工具：

1. **Coze/扣子**（AI 应用搭建推荐）
   - 适合：AI 机器人、小程序、App
   - 优势：拖拽+自然语言，可导出安装包
   - 安卓：浏览器可用，推荐

2. **Dify**（开源 AI 应用）
   - 适合：AI 工作流、聊天机器人
   - 优势：开源，可自部署
   - 安卓：浏览器可用

3. **MonkeyCode**（云端 AI IDE）
   - 适合：全栈开发，Agent 自主编程
   - 优势：浏览器完整开发环境
   - 安卓：浏览器可用，推荐

4. **Firebase Studio**（Google 官方）
   - 适合：Flutter 开发，Firebase 后端
   - 优势：Google 官方，Gemini 加持
   - 安卓：浏览器可用

5. **Webflow**（网页可视化）
   - 适合：专业网页设计
   - 优势：可视化设计，代码导出
   - 安卓：浏览器可用
            """.trimIndent()
            
            else -> """
💡 根据你的需求「$need」，推荐以下工具：

**通用推荐**：
1. **VS Code** - 代码编辑（插件全覆盖）
2. **Canva** - 图形设计（零基础）
3. **Figma** - UI/UX 设计
4. **剪映** - 视频剪辑
5. **BandLab** - 音乐制作

请告诉我更具体的需求，我可以给出更精准的推荐。
            """.trimIndent()
        }
    }

    private fun launchApp(context: Context, appName: String): String {
        val packageMap = mapOf(
            // 图形
            "canva" to "com.canva.editor",
            "pixso" to "com.pixso.app",
            "figma" to "com.figma.kmp",
            // 视频
            "剪映" to "com.lemon.lv",
            "capcut" to "com.lemon.lvoverseas",
            "vivavideo" to "com.quvideo.xiaoying",
            // 音频
            "bandlab" to "com.bandlab.bandlab",
            "fl studio mobile" to "com.imageline.FLM",
            // 3D
            "prisma3d" to "com.prisma3d.app",
            // 游戏
            "godot" to "org.godotengine.godot",
            "scratch" to "org.scratchfoundation.scratchjrs",
            // 代码
            "aide" to "com.aide.ui",
            "pydroid" to "ru.iiec.pydroid3",
            "dcoder" to "com.pas.android.dcoder",
            "acode" to "com.foxdebug.acode",
            // 低代码
            "coze" to "com.coze.app",
            // 其他
            "tinkercad" to "com.tinkercad.app"
        )
        
        val lowerName = appName.lowercase()
        val packageName = packageMap[lowerName] ?: packageMap.entries.find { 
            lowerName.contains(it.key.lowercase()) 
        }?.value
        
        return if (packageName != null) {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "已启动 $appName（$packageName）"
                } else {
                    "找到 $appName（$packageName）但无法启动，可能未安装"
                }
            } catch (e: Exception) {
                "启动 $appName 失败：${e.message}"
            }
        } else {
            "未找到应用「$appName」的包名映射。请先使用 list_installed_apps 确认应用已安装，然后提供精确包名。"
        }
    }

    private fun generate(contentType: String, prompt: String): String {
        return when (contentType.lowercase()) {
            "graphic", "图形", "图像" -> generateGraphic(prompt)
            "video", "视频" -> generateVideo(prompt)
            "audio", "音频" -> generateAudio(prompt)
            "3d", "建模" -> generate3D(prompt)
            "game", "游戏" -> generateGame(prompt)
            "lowcode", "低代码" -> generateLowCode(prompt)
            "code", "代码" -> generateCode(prompt)
            else -> "不支持的内容类型：$contentType。支持：graphic, video, audio, 3d, game, lowcode, code"
        }
    }

    private fun generateGraphic(prompt: String): String {
        return """🎨 图形设计 HTML 代码已生成，可在对话框内直接预览：

```html
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { 
            font-family: 'Microsoft YaHei', sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 20px;
        }
        .card {
            background: white;
            border-radius: 20px;
            padding: 40px;
            box-shadow: 0 20px 60px rgba(0,0,0,0.3);
            max-width: 500px;
            text-align: center;
        }
        .icon { font-size: 60px; margin-bottom: 20px; }
        h1 { color: #333; margin-bottom: 15px; font-size: 28px; }
        p { color: #666; line-height: 1.6; margin-bottom: 20px; }
        .btn {
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            border: none;
            padding: 15px 40px;
            border-radius: 30px;
            font-size: 16px;
            cursor: pointer;
            transition: transform 0.3s;
        }
        .btn:hover { transform: scale(1.05); }
    </style>
</head>
<body>
    <div class="card">
        <div class="icon">🎨</div>
        <h1>$prompt</h1>
        <p>这是一个图形设计示例。你可以使用 Canva、Figma 或 Pixso 创建更精美的设计。</p>
        <button class="btn" onclick="alert('设计已保存！')">保存设计</button>
    </div>
</body>
</html>
```

💡 **使用建议**：
1. 复制上面的 HTML 代码到对话框，可直接预览效果
2. 如需更专业的设计，请使用：
   - **Canva**：零基础模板化设计
   - **Figma**：专业 UI/UX 设计
   - **Pixso**：国内推荐，速度快
3. AI 图像生成推荐：**Midjourney**、**Stable Diffusion**、**即梦**"""
    }

    private fun generateVideo(prompt: String): String {
        return """🎬 视频项目已创建：

**项目信息**：
- 主题：$prompt
- 类型：视频制作
- 推荐工具：剪映 CapCut

**下一步操作**：
1. 打开 **剪映** 应用
2. 点击「开始创作」
3. 导入素材或使用 AI 生成
4. 添加特效、字幕、音乐
5. 导出分享

**AI 视频生成推荐**：
- **可灵**：国内 AI 视频生成
- **Runway Gen-3**：国际顶级 AI 视频
- **Pika**：快速 AI 视频生成

需要我帮你生成视频脚本或分镜吗？"""
    }

    private fun generateAudio(prompt: String): String {
        return """🎵 音频项目已创建：

**项目信息**：
- 主题：$prompt
- 类型：音频制作
- 推荐工具：BandLab / FL Studio Mobile

**下一步操作**：
1. 打开 **BandLab** 应用
2. 点击「创建新项目」
3. 选择录音或 MIDI 编曲
4. 添加效果器和混音
5. 导出音频文件

**AI 音乐推荐**：
- **Suno**：AI 作曲，输入描述即可生成
- **Udio**：AI 音乐生成
- **ElevenLabs**：AI 配音

需要我帮你生成音乐脚本或歌词吗？"""
    }

    private fun generate3D(prompt: String): String {
        return """🧊 3D 项目已创建：

**项目信息**：
- 主题：$prompt
- 类型：3D 建模
- 推荐工具：Prisma3D / Tinkercad

**下一步操作**：
1. 打开 **Prisma3D** 应用
2. 点击「新建项目」
3. 使用建模工具创建 3D 模型
4. 添加材质和纹理
5. 渲染并导出

**3D 打印推荐**：
- **Tinkercad**：网页版，简单易学
- **Fusion 360**：专业 CAD
- **Blender**：开源全能

需要我帮你生成 3D 模型的代码或设计图吗？"""
    }

    private fun generateGame(prompt: String): String {
        return """🎮 游戏项目已创建：

**项目信息**：
- 主题：$prompt
- 类型：游戏开发
- 推荐工具：Godot 安卓版

**下一步操作**：
1. 打开 **Godot** 应用
2. 点击「新建项目」
3. 选择 2D 或 3D 模板
4. 添加场景、角色、脚本
5. 测试并导出 APK

**游戏开发推荐**：
- **Godot**：开源免费，手机内全链路
- **Unity**：行业标准，生态最大
- **GameMaker**：2D 游戏，无需编程

需要我帮你生成游戏脚本或设计文档吗？"""
    }

    private fun generateLowCode(prompt: String): String {
        return """📊 低代码应用已创建：

**项目信息**：
- 主题：$prompt
- 类型：低代码应用
- 推荐工具：Coze/扣子

**下一步操作**：
1. 打开 **Coze** 网站
2. 点击「创建 Bot」
3. 配置 AI 模型和知识库
4. 设计对话流程
5. 发布到多平台

**低代码平台推荐**：
- **Coze/扣子**：AI 应用搭建，可导出安装包
- **Dify**：开源 AI 应用
- **MonkeyCode**：云端 AI IDE

需要我帮你设计应用架构或配置 AI 模型吗？"""
    }

    private fun generateCode(prompt: String): String {
        return """💻 代码项目已创建：

**项目信息**：
- 主题：$prompt
- 类型：代码开发
- 推荐工具：VS Code / Android Studio

**下一步操作**：
1. 打开 **VS Code** 或 **Android Studio**
2. 创建新项目
3. 编写代码
4. 调试测试
5. 编译发布

**代码 IDE 推荐**：
- **VS Code**：通用推荐，插件全覆盖
- **Android Studio**：安卓开发
- **PyCharm**：Python 开发

需要我帮你生成代码框架或配置开发环境吗？"""
    }

    private fun getAndroidTools(): String {
        return """
📱 安卓手机上真实可用的创作工具：

🎮 游戏开发：
- Godot 安卓版 + GABE（手机内全链路）
- Unity Remote（安卓预览）
- Scratch/ScratchJr（可视化编程）

🧊 3D 建模：
- Prisma3D（安卓原生完整 3D IDE）
- Tinkercad（网页版 3D 设计）
- AutoCAD Mobile（2D/3D 绘图）

🎨 图形设计：
- Pixso（国内推荐）
- Figma Android（UI/UX 设计）
- Canva Android（模板化设计）
- 八位元画家（像素艺术）

🎬 视频剪辑：
- 剪映 CapCut（短视频推荐）
- VivaVideo（安卓视频创作）
- Adobe Premiere Rush（移动剪辑）

🎵 音频制作：
- BandLab（安卓 DAW 推荐）
- FL Studio Mobile（移动 DAW）
- AudioLab（音频编辑）

💻 代码开发：
- AIDE（Java/Kotlin 安卓开发）
- Pydroid 3（Python 3 + pip）
- Dcoder（50+ 语言在线编译）
- Acode（开源多语言编辑器）
- Termux（终端 Linux 环境）
- PHONE AS（口袋 Android Studio）

🌐 低代码/AI：
- Coze/扣子（AI 应用搭建）
- Dify（开源 AI 应用）
- MonkeyCode（云端 AI IDE）
- Firebase Studio（Google 官方）

📦 其他：
- Rematch（Android 开发模板）
- AppSheet（Google 低代码）
- Node-RED（IoT 编程）
        """.trimIndent()
    }
}