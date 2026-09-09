package com.ai.assistance.quro.core.tools

import android.content.Context
import android.os.Environment
import androidx.core.content.FileProvider
import com.ai.assistance.quro.build.BuildEngine
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 端侧 APK 构建工具（AI 可真正调用的构建入口）。
 *
 * 与「ui_open_build」（只打开构建台界面）互补：本工具在**不打开 UI** 的情况下，
 * 执行 ecj 编译 → d8 转 DEX → 注入 base.apk 模板并签名打包，产出可直接安装的 APK；
 * 全程复用 [BuildEngine] 同一套离线工具链。
 *
 * 关键：本工具编译的是**传入的源码目录**，默认优先用 AI 通过 workspace_write 写进工作区
 * （QuroWorkspace）的源码，而不是构建台内置的示例工程。这样 AI 写的代码才会真正被编进去。
 *
 * 设计原则「绝不假装能编」：src 无 .java、工具链缺失、编译/打包报错时，如实返回日志，
 * 不会编造成功。
 */
class BuildApkTool : QuroTool {
    override val name = "build_apk"
    override val description = """端侧 APK 构建（多语言）：把指定目录下的源码编译/打包成可直接安装的 APK。支持：
· Java（默认，lang="java"）：src 目录下 .java → ecj → d8 → 注入 base.apk 模板签名。
· HTML / JS / CSS（lang="html" 或 "web"）：把网页工程（index.html + js/css/资源）注入 WebView 壳模板，打成可安装 APK，无需写 Java。
【重要】编译的是「AI 用 workspace_write 写进工作区的源码」，不是构建台内置示例！必须告诉它源码在哪：
· Java：src_path="MyApp/src"（你写的是 MyApp/src/Main.java）；不传默认 工作区/src。
· HTML：src_path="MyWeb"（其下有 index.html）；不传自动在 workspace 找 index.html。
【入口类·任意包名/类名】Java 工程无需再固定 com.example.hello.Main：构建台会自动扫描编出的类，
找到带 public main/run 入口方法的类（静态或实例均可：main(String[])/main(Activity,String[])/run(Activity)/run()），
把其全限定名写入 APK 的 assets/zorv_entry.txt，宿主运行时动态反射调用。也可用 entry_class="com.xxx.Yyy" 显式指定。
端侧离线环境**无法**真正把 Go / C / C++ / Python 编译成 APK（需打进整套交叉编译/解释器工具链，体积巨大且不现实）；
若传 lang="go"/"c"/"c++"/"python"，会如实说明并给出替代方案，不会假装成功。
参数：{"lang":"语言(java/html/web/go/c/c++/python;不传按目录自动判断:有 index.html 且无 .java → web,否则 java)","src_path":"源码目录(相对工作区或绝对路径;Java 含 .java,HTML 含 index.html)","entry_class":"Java 入口类全限定名(可选,如 com.example.snake.Game;不传自动探测带 main/run 的类)","package_name":"APK 包名(可选)","app_label":"应用显示名(可选)","version_name":"版本名(可选)"}
当用户要求「构建/打包/编译 APK、生成安卓安装包、把代码打成 apk、端侧打包」时使用。构建成功自动导出到可访问位置并返回安装 URI。
【自定义包名 / 应用名 / 版本】用 package_name / app_label / version_name。
【Release 签名·生成并使用】传 generate_keystore=true 由端侧自动生成自定义 keystore（release 签名），并用 keystore_alias / store_password / key_password 指定别名与密码；也可传 keystore_path 指向已有的 .jks/.p12 由构建台使用。不传则默认用内置 debug.keystore。
【构建时引用依赖】传 dependencies（JAR 路径数组）：每个元素可以是绝对路径，或相对于「构建台依赖目录 buildproject/deps」/「工作区 deps」的文件名，构建台把它们加入编译 classpath 与 d8 lib，一起编进 APK（适合引入第三方库）。
【AI 自制图标（头像）】传 icon_path 指向一张 PNG（AI 用 image_gen 生成或 workspace_write 写入的图片文件），构建台把它写入 APK 作为启动图标。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "lang":{"type":"string","description":"源码语言：java(默认) / html 或 web(网页工程) / kotlin / go / c / c++ / python。不传则按目录自动判断（有 index.html 且无 .java → web，否则 java）。Go/C/C++/Python 端侧离线无法编译成 APK，会给出替代方案。"},
            "src_path":{"type":"string","description":"源码目录：相对当前工作区(QuroWorkspace)的路径或绝对路径。Java 工程含 .java 文件；HTML 工程含 index.html（及 js/css/资源）。如 AI 写的是 MyApp/src/Main.java 则传 MyApp/src；网页工程 MyWeb/index.html 则传 MyWeb。"},
            "package_name":{"type":"string","description":"APK 包名,如 com.example.myapp(可选,默认 java→com.example.buildapp / web→com.example.webapp)"},
            "app_label":{"type":"string","description":"应用显示名(可选,默认 java→BuildApp / web→WebApp)"},
            "version_name":{"type":"string","description":"版本名(可选,默认 1.0.0)"},
            "entry_class":{"type":"string","description":"Java 入口类全限定名(可选,如 com.example.snake.Game);不传则由构建台自动探测带 public main/run 入口方法的类(任意包名/类名均可)"},
            "generate_keystore":{"type":"boolean","description":"是否端侧自动生成自定义 release 签名 keystore（true=生成并使用，适合发布版 APK）；不传=false，默认用内置 debug.keystore。"},
            "keystore_alias":{"type":"string","description":"自定义 keystore 别名（generate_keystore=true 或 keystore_path 时生效；不传默认 zorvkey）"},
            "store_password":{"type":"string","description":"keystore 密码（生成或使用时；不传默认 zorvai）"},
            "key_password":{"type":"string","description":"密钥密码（生成或使用时；不传默认 zorvai）"},
            "keystore_path":{"type":"string","description":"（可选）已存在的 keystore 绝对路径(.jks/.p12)，由构建台使用它签名；与 generate_keystore 二选一"},
            "dependencies":{"type":"array","items":{"type":"string"},"description":"构建时依赖的第三方 JAR 路径数组：绝对路径，或相对「buildproject/deps」/「工作区 deps」的文件名。构建台把它们加入编译 classpath 与 d8 lib。"},
            "icon_path":{"type":"string","description":"（可选）自定义启动图标 PNG 的绝对路径（AI 用 image_gen 生成或 workspace_write 写入的图片文件），写入 APK 作为应用图标（头像）。"}
        },
        "required":[]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrNull() ?: JSONObject()
        val lang = (args.optString("lang", "").trim()).lowercase().ifBlank { autoDetectLang(context, args) }
        return when (lang) {
            "html", "web", "h5" -> buildWeb(context, args)
            "java", "kotlin" -> buildJavaOrKotlin(context, args, lang)
            else -> unsupportedLang(lang)
        }
    }

    // ---- Web / HTML 模式：网页工程 → WebView 壳 APK ----
    private fun buildWeb(context: Context, args: JSONObject): String {
        val webDir = resolveWebDir(context, args)
        if (webDir == null || !webDir.isDirectory) {
            return "网页工程目录不存在或未找到 index.html。请用 workspace_write 把 index.html（及 js/css/资源）写到工作区，例如 workspace_write(path=\"MyWeb/index.html\", content=...)，再调用 build_apk 时传 src_path=\"MyWeb\"；或把 index.html 直接放在工作区根目录（不传 src_path 会自动查找）。"
        }
        val signing = resolveSigning(context, args)
        val cfg = BuildEngine.BuildConfig(
            packageName = args.optString("package_name", "com.example.webapp").ifBlank { "com.example.webapp" },
            appLabel = args.optString("app_label", "WebApp").ifBlank { "WebApp" },
            versionName = args.optString("version_name", "1.0.0").ifBlank { "1.0.0" },
            iconBytes = resolveIconBytes(context, args),
            keystore = signing.keystore,
            keyAlias = signing.alias,
            storePassword = signing.storePassword,
            keyPassword = signing.keyPassword,
        )
        val outApk = File(context.filesDir, "buildproject/web-${System.currentTimeMillis()}.apk")
        val res = BuildEngine.assembleWebApk(context, webDir, outApk, cfg)
        if (!res.ok || res.apkPath == null) {
            return "❌ WebView APK 构建失败（日志如下）：\n${res.log}"
        }
        val built = File(res.apkPath)
        val exportName = "webapp-${cfg.packageName.replace('.', '_')}-v${cfg.versionName}.apk"
        val export = exportForZorvAI(context, built, exportName)
        return buildExportReport(export, built, res.log, "WebView APK（HTML/JS/CSS 工程）")
    }

    // ---- Java / Kotlin 模式 ----
    private fun buildJavaOrKotlin(context: Context, args: JSONObject, lang: String): String {
        if (lang == "kotlin") {
            // 端侧 Kotlin 需要 kotlinc 工具链（dexed kotlinc jar），当前安装包未内置。
            val libs = BuildEngine.ensureAssets(context)
            if (!File(libs, "kotlinc_dex.jar").exists()) {
                return "⚠️ Kotlin 端侧编译需要 kotlinc 工具链（约数十 MB，需打包进构建台 assets 才能离线编译），当前安装包未内置。\n" +
                    "已可用的语言：Java（直接编译）、HTML/JS/CSS（WebView 壳打包）。\n" +
                    "需要我把 kotlinc 工具链内置进构建台、让 Kotlin 也能离线编译吗？确认后我会打包 kotlinc_dex.jar 并接入编译链路。"
            }
        }
        val projectRoot = File(context.filesDir, "buildproject")
        val srcDir = resolveBuildSrcDir(context, args)
        val outDir = File(projectRoot, "out")
        if (!srcDir.exists() || !srcDir.isDirectory) {
            return "源码目录不存在（${srcDir.absolutePath}）。请先用 workspace_write 把 .java 源码写进工作区的源码目录（如 MyApp/src/Main.java），再调用 build_apk 时传 src_path=\"MyApp/src\"；或在构建台 UI 里写代码。"
        }
        // 1) 编译 Java 工程 → classes.dex（自动探测入口类，支持任意包名/类名）
        val explicitEntry = args.optString("entry_class", "").trim().ifBlank { null }
        val dependencyJars = resolveDependencyJars(context, args)
        val compile = BuildEngine.compileProject(context, srcDir, outDir, explicitEntry, dependencyJars)
        if (!compile.ok || compile.dexPath == null) {
            return "❌ 编译失败（ecj/d8 日志如下）：\n${compile.log}"
        }
        // 2) 注入 base.apk 模板并签名打包
        val signing = resolveSigning(context, args)
        val cfg = BuildEngine.BuildConfig(
            packageName = args.optString("package_name", "com.example.buildapp").ifBlank { "com.example.buildapp" },
            appLabel = args.optString("app_label", "BuildApp").ifBlank { "BuildApp" },
            versionName = args.optString("version_name", "1.0.0").ifBlank { "1.0.0" },
            entryClass = compile.entryClass,
            iconBytes = resolveIconBytes(context, args),
            keystore = signing.keystore,
            keyAlias = signing.alias,
            storePassword = signing.storePassword,
            keyPassword = signing.keyPassword,
        )
        val outApk = File(projectRoot, "app-${System.currentTimeMillis()}.apk")
        val res = BuildEngine.assembleApk(context, compile.dexPath, outApk, cfg)
        if (!res.ok || res.apkPath == null) {
            return "❌ APK 打包失败（日志如下）：\n${res.log}"
        }
        val built = File(res.apkPath)
        val exportName = "buildapp-${cfg.packageName.replace('.', '_')}-v${cfg.versionName}.apk"
        val export = exportForZorvAI(context, built, exportName)
        return buildExportReport(export, built, res.log, "Java APK")
    }

    // 端侧离线无法编译的语言（Go/C/C++/Python 等）：如实说明并给替代方案，绝不假装成功。
    private fun unsupportedLang(lang: String): String {
        return "⚠️ 语言「$lang」端侧离线构建暂不支持。\n" +
            "原因：在端侧离线环境里，Go / C / C++ / Python 无法真正编译/解释成可独立安装的 APK——\n" +
            "那需要把整套交叉编译工具链（NDK clang、Go 工具链）或解释器运行时（CPython）打进 App，体积巨大且在 Android 上跑编译器本身不现实。\n" +
            "替代方案：\n" +
            "· 想要「网页类应用」→ 用 lang=\"html\"，把 HTML/JS/CSS 工程打包成 WebView APK（已支持）；\n" +
            "· 想要「原生 Java 应用」→ 用默认 Java 模式（已支持）；\n" +
            "· 真要用 Go/C/C++/Python 产物 → 请在 PC/服务器上交叉编译/构建好 APK 或可执行文件，再用 export_apk 导入到可访问位置安装。"
    }

    private fun buildExportReport(export: ZorvExport, built: File, log: String, kind: String): String {
        val reachable = export.externalFile != null || export.mediaStoreUri != null
        val sb = StringBuilder()
        if (reachable) sb.appendLine("✔ $kind 构建成功，APK 已导出到可访问位置：")
        else sb.appendLine("⚠️ 编译/打包成功，但 APK 仅落在应用沙箱私有目录、外部无法访问也无法安装：")
        sb.appendLine("内部产物（沙箱私有）：${built.absolutePath}")
        sb.appendLine()
        if (export.externalFile != null) {
            sb.appendLine("📦 应用外部目录（文件管理器可见、ZorvAI 可直读安装）：")
            sb.appendLine("   ${export.externalFile.absolutePath}")
            if (export.contentUri != null) sb.appendLine("   FileProvider 安装 URI：${export.contentUri}")
        }
        if (export.mediaStoreUri != null) sb.appendLine("📥 系统下载 Download/Quro：${export.mediaStoreUri}")
        if (!reachable) sb.appendLine("（导出到外部存储失败：请确认安装的是含本修复的版本、外部存储可用且未禁用「文件和媒体」权限；也可在构建台 UI 用「导出产物」手动取出。）")
        sb.appendLine()
        sb.appendLine(log)
        return sb.toString().trimEnd()
    }

    private fun autoDetectLang(context: Context, args: JSONObject): String {
        val sp = args.optString("src_path", "").trim()
        val base = if (sp.isNotEmpty()) {
            val f = File(sp); if (f.isAbsolute) f else File(workspaceRoot(context), sp)
        } else workspaceRoot(context)
        val hasHtml = runCatching { base.exists() && base.walkTopDown().any { it.isFile && it.name.equals("index.html", ignoreCase = true) } }.getOrDefault(false)
        val hasJava = runCatching { base.exists() && base.walkTopDown().any { it.isFile && it.name.endsWith(".java", ignoreCase = true) } }.getOrDefault(false)
        return if (hasHtml && !hasJava) "web" else "java"
    }

    private fun resolveWebDir(context: Context, args: JSONObject): File? {
        val sp = args.optString("src_path", "").trim()
        if (sp.isNotEmpty()) {
            val f = File(sp)
            val dir = if (f.isAbsolute) f else File(workspaceRoot(context), sp)
            return if (dir.exists() && dir.isDirectory) dir else dir.parentFile?.takeIf { it.isDirectory }
        }
        // 不传 src_path：在 workspace 根递归找 index.html，取其所在目录
        return runCatching {
            workspaceRoot(context).walkTopDown()
                .firstOrNull { it.isFile && it.name.equals("index.html", ignoreCase = true) }?.parentFile
        }.getOrNull()
    }

    /**
     * 解析本次构建要编译的源码目录。
     * 默认优先用 AI 通过 workspace_write 写进工作区(QuroWorkspace)的源码，而不是构建台内置示例工程——
     * 否则 build_apk 永远只在编内置示例，AI 写的代码根本进不去（用户实测现象）。
     * - src_path 绝对路径：直接用；
     * - src_path 相对路径：相对当前工作区根解析；
     * - 未传：若 工作区/src 下含 .java 则用工作区/src，否则回退 构建台内置示例工程(files/buildproject/src)。
     */
    private fun resolveBuildSrcDir(context: Context, args: JSONObject): File {
        val sp = args.optString("src_path", "").trim()
        if (sp.isNotEmpty()) {
            val f = File(sp)
            if (f.isAbsolute) return f
            return File(workspaceRoot(context), sp)
        }
        val wsRoot = workspaceRoot(context)
        val wsSrc = File(wsRoot, "src")
        val hasJavaInSrc = runCatching {
            wsSrc.isDirectory && wsSrc.walkTopDown()
                .any { it.isFile && it.name.endsWith(".java", ignoreCase = true) }
        }.getOrDefault(false)
        if (hasJavaInSrc) return wsSrc
        // 没在 src/ 下：若整个工作区有 .java（如 MyApp/src/Main.java），直接以工作区根为源码根编译
        val hasJavaAnywhere = runCatching {
            wsRoot.isDirectory && wsRoot.walkTopDown()
                .any { it.isFile && it.name.endsWith(".java", ignoreCase = true) }
        }.getOrDefault(false)
        if (hasJavaAnywhere) return wsRoot
        return File(context.filesDir, "buildproject/src")
    }

    // ══ #668：release 签名解析（生成自定义 keystore 或使用已有）══
    private data class ResolvedSigning(
        val keystore: File?,
        val alias: String,
        val storePassword: String,
        val keyPassword: String,
    )

    private fun resolveSigning(context: Context, args: JSONObject): ResolvedSigning {
        val alias = args.optString("keystore_alias", "zorvkey").ifBlank { "zorvkey" }
        val storePass = args.optString("store_password", "zorvai").ifBlank { "zorvai" }
        val keyPass = args.optString("key_password", "zorvai").ifBlank { "zorvai" }
        // 1) 已有 keystore 路径优先
        val kp = args.optString("keystore_path", "").trim()
        if (kp.isNotEmpty()) {
            val f = File(kp)
            if (f.isFile) return ResolvedSigning(f, alias, storePass, keyPass)
        }
        // 2) 端侧自动生成自定义 release keystore
        val gen = args.optBoolean("generate_keystore", false)
        if (gen) {
            val keyDir = File(context.filesDir, "buildproject/keys").apply { mkdirs() }
            val f = File(keyDir, "$alias.jks")
            return runCatching {
                BuildEngine.generateKeystore(f, alias, storePass, keyPass)
                ResolvedSigning(f, alias, storePass, keyPass)
            }.getOrElse { ResolvedSigning(null, alias, storePass, keyPass) }
        }
        // 3) 未指定：引擎默认用内置 debug.keystore（keystore=null）
        return ResolvedSigning(null, alias, storePass, keyPass)
    }

    // ══ #668：AI 自制图标（头像）══
    private fun resolveIconBytes(context: Context, args: JSONObject): ByteArray? {
        val p = args.optString("icon_path", "").trim()
        if (p.isEmpty()) return null
        val f = File(p)
        if (!f.isFile) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    // ══ #668：构建时依赖引用（第三方 JAR 路径数组 → File 列表）══
    private fun resolveDependencyJars(context: Context, args: JSONObject): List<File> {
        val arr = args.optJSONArray("dependencies") ?: return emptyList()
        val wsRoot = workspaceRoot(context)
        val depsDir = File(context.filesDir, "buildproject/deps")
        val wsDeps = File(wsRoot, "deps")
        val out = mutableListOf<File>()
        for (i in 0 until arr.length()) {
            val raw = arr.optString(i, "").trim()
            if (raw.isEmpty()) continue
            val f = File(raw)
            val cand = listOfNotNull(
                if (f.isAbsolute && f.isFile) f else null,
                File(depsDir, raw).takeIf { it.isFile },
                File(wsDeps, raw).takeIf { it.isFile },
                File(wsRoot, raw).takeIf { it.isFile },
            )
            cand.firstOrNull()?.let { out.add(it) }
        }
        return out
    }
}

/**
 * 让 AI 也能「导出产物」：构建台 UI 里的「导出产物」是 CreateDocument 文件选择器（要人手点），
 * AI（无界面工具）根本调不起来。本工具把任意已构建的 APK 导出到可访问位置，
 * 复用与 [BuildApkTool] 相同的导出逻辑（外部目录 apk/ + 系统下载 Download/Quro + 兜底公共 Download）。
 *
 * 不传 apk_path 时，自动取 files/buildproject 下最新的 *.apk（构建台/ build_apk 的产物都落在那里）。
 */
class ExportApkTool : QuroTool {
    override val name = "export_apk"
    override val description = """导出已构建的 APK 到可访问位置（构建台 UI 的「导出产物」是文件选择器、AI 用不了，本工具替代它）。
把 APK 复制/写入到：① 应用外部目录 apk/（文件管理器可见、ZorvAI 可直读安装）；② 系统下载 Download/Quro（最易检索/安装）；并返回 FileProvider 安装 URI。
参数：{"apk_path":"（可选）已构建 APK 的绝对路径；不传则自动取 files/buildproject 下最新的 *.apk"}
当用户/AI 已经构建出 APK（无论经 build_apk 还是构建台 UI）但拿不到/装不上时使用，把产物导出到可访问目录。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "apk_path":{"type":"string","description":"（可选）已构建 APK 的绝对路径；不传则自动取 files/buildproject 下最新的 *.apk"}
        },
        "required":[]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrNull() ?: JSONObject()
        val apk = resolveApk(context, args.optString("apk_path", "").trim())
        if (apk == null) {
            return "⚠️ 找不到可导出的 APK。请先用 build_apk 构建，或在构建台 UI 构建后把 apk_path 传给我；或在构建台用「导出产物」手动取出。"
        }
        val export = exportForZorvAI(
            context,
            apk,
            "export-${apk.nameWithoutExtension}.apk",
        )
        val reachable = export.externalFile != null || export.mediaStoreUri != null
        val sb = StringBuilder()
        if (reachable) {
            sb.appendLine("✔ 已导出 APK 到可访问位置：")
        } else {
            sb.appendLine("⚠️ 导出失败，APK 仅留在沙箱私有目录（无法外部访问/安装）：${apk.absolutePath}")
        }
        sb.appendLine("源文件：${apk.absolutePath}（${apk.length()} 字节）")
        sb.appendLine()
        if (export.externalFile != null) {
            sb.appendLine("📦 应用外部目录：${export.externalFile.absolutePath}")
            if (export.contentUri != null) sb.appendLine("   FileProvider 安装 URI：${export.contentUri}")
        }
        if (export.mediaStoreUri != null) {
            sb.appendLine("📥 系统下载 Download/Quro：${export.mediaStoreUri}")
        }
        if (!reachable) {
            sb.appendLine("（导出失败：确认外部存储可用、未禁用「文件和媒体」权限；或直接在构建台 UI 用「导出产物」手动取出。）")
        }
        return sb.toString().trimEnd()
    }

    private fun resolveApk(context: Context, apkPath: String): File? {
        if (apkPath.isNotEmpty()) {
            val f = File(apkPath)
            if (f.isFile) return f
        }
        // 默认：files/buildproject 下最新的 *.apk
        val dir = File(context.filesDir, "buildproject")
        return runCatching {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".apk", ignoreCase = true) }
                ?.maxByOrNull { it.lastModified() }
        }.getOrNull()
    }
}

/**
 * 把构建出的 APK 复制到一个 ZorvAI/用户可检索的位置，并返回安装用信息。
 * 文件级私有（整个 .kt 文件内两个工具类共用）：
 * - 应用外部目录 getExternalFilesDir("apk")：文件管理器可见、应用自身可直读、FileProvider 可分享安装；
 *   对应 file_paths.xml 的 <external-files-path name="quro_sandbox_ext" path="." />，无需额外声明。
 * - 系统下载 Download/Quro（MediaStore）：用户在文件管理器/下载中心可直接找到并安装，最易检索。
 */
private data class ZorvExport(
    val externalFile: File? = null,
    val contentUri: String? = null,
    val mediaStoreUri: String? = null,
)

private fun exportForZorvAI(context: Context, built: File, displayName: String): ZorvExport {
    val safeName = displayName
    var externalFile: File? = null
    var contentUri: String? = null
    var mediaStoreUri: String? = null

    // 1) 优先：系统下载 Download/Quro（MediaStore 公共目录，用户/文件管理器/终端 ls 均可访问）
    runCatching {
        val stored = QuroDownloadUtil.saveFileToDownloads(
            context,
            built,
            safeName,
            "application/vnd.android.package-archive",
        )
        if (!stored.isNullOrBlank()) mediaStoreUri = stored
    }

    // 2) 应用外部目录 apk/（FileProvider 可安装、应用自身可直读）
    runCatching {
        val dir = context.getExternalFilesDir("apk")
        if (dir != null) {
            dir.mkdirs()
            val out = File(dir, safeName)
            built.inputStream().use { ins ->
                FileOutputStream(out).use { os -> ins.copyTo(os) }
            }
            externalFile = out
            if (contentUri == null) {
                contentUri = FileProvider.getUriForFile(
                    context,
                    context.packageName + ".fileprovider",
                    out,
                ).toString()
            }
        }
    }

    // 3) 兜底：旧版公共 Download/Quro（API<29 或 MediaStore 不可用时）
    if (mediaStoreUri == null) {
        runCatching {
            val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val out = File(pub, "Quro/$safeName").also { it.parentFile?.mkdirs() }
            built.inputStream().use { ins ->
                FileOutputStream(out).use { os -> ins.copyTo(os) }
            }
            externalFile = externalFile ?: out
            if (contentUri == null) {
                contentUri = FileProvider.getUriForFile(
                    context,
                    context.packageName + ".fileprovider",
                    out,
                ).toString()
            }
            mediaStoreUri = out.absolutePath
        }
    }

    return ZorvExport(externalFile, contentUri, mediaStoreUri)
}
