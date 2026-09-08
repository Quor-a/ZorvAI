package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.build.BuildEngine
import org.json.JSONObject
import java.io.File

/**
 * 端侧 APK 构建工具（AI 可真正调用的构建入口）。
 *
 * 与「ui_open_build」（只打开构建台界面）互补：本工具在**不打开 UI** 的情况下，
 * 直接对构建台工程目录（context.filesDir/buildproject/src 下的 Java 工程）执行
 * ecj 编译 → d8 转 DEX → 注入 base.apk 模板并签名打包，产出可直接安装的 APK；
 * 全程复用 [BuildEngine] 同一套离线工具链（与构建台 UI 共用同一份用户源码）。
 *
 * 设计原则「绝不假装能编」：src 无 .java、工具链缺失、编译/打包报错时，如实返回日志，
 * 不会编造成功。与构建台 UI 的工程目录完全一致——AI 通过 workspace_write 写进去的源码、
 * 或用户在构建台里写的源码，都能被本工具直接编译打包。
 */
class BuildApkTool : QuroTool {
    override val name = "build_apk"
    override val description = """端侧 APK 构建：把构建台工程目录(filesDir/buildproject/src 下的 Java 工程)编译为 classes.dex，注入 base.apk 模板并签名打包成可直接安装的 APK。
全程离线、免 aapt2，复用与构建台 UI 相同的 ecj/d8/apksig 工具链。
参数：{"package_name":"APK 包名(可选,默认 com.example.buildapp)","app_label":"应用显示名(可选,默认 BuildApp)","version_name":"版本名(可选,默认 1.0.0)"}
当用户要求「构建/打包/编译 APK、生成安卓安装包、把 Java 代码打成 apk、端侧打包」时使用。
构建结果（APK 路径）与 ecj/d8 编译日志会一并返回；编译失败会返回真实错误日志而非假装成功。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "package_name":{"type":"string","description":"APK 包名,如 com.example.myapp(可选,默认 com.example.buildapp)"},
            "app_label":{"type":"string","description":"应用显示名(可选,默认 BuildApp)"},
            "version_name":{"type":"string","description":"版本名(可选,默认 1.0.0)"}
        },
        "required":[]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrNull() ?: JSONObject()

        val projectRoot = File(context.filesDir, "buildproject")
        val srcDir = File(projectRoot, "src")
        val outDir = File(projectRoot, "out")

        if (!srcDir.exists()) {
            return "构建台工程目录不存在（${srcDir.absolutePath}）。请先调用 ui_open_build 打开构建台并写入/创建 Java 工程，或用 workspace_write 写入源码后再构建。"
        }

        // 1) 编译 Java 工程 → classes.dex
        val compile = BuildEngine.compileProject(context, srcDir, outDir)
        if (!compile.ok || compile.dexPath == null) {
            return "❌ 编译失败（ecj/d8 日志如下）：\n${compile.log}"
        }

        // 2) 注入 base.apk 模板并签名打包
        val cfg = BuildEngine.BuildConfig(
            packageName = args.optString("package_name", "com.example.buildapp").ifBlank { "com.example.buildapp" },
            appLabel = args.optString("app_label", "BuildApp").ifBlank { "BuildApp" },
            versionName = args.optString("version_name", "1.0.0").ifBlank { "1.0.0" },
        )
        val outApk = File(projectRoot, "app-${System.currentTimeMillis()}.apk")
        val res = BuildEngine.assembleApk(context, compile.dexPath!!, outApk, cfg)

        return if (res.ok && res.apkPath != null) {
            "✔ 构建成功！\nAPK 路径：${res.apkPath}\n\n${res.log}"
        } else {
            "❌ APK 打包失败（日志如下）：\n${res.log}"
        }
    }
}
