package com.yuanbao.miniapp.core

import android.content.Context
import com.yuanbao.miniapp.pack.AppConfig
import com.yuanbao.miniapp.pack.MiniPackage
import java.io.File
import java.io.FileInputStream

/**
 * Public entry point of the SDK.
 *
 * Everything used here is either platform-native (View/Canvas, GLES20,
 * HttpURLConnection, SharedPreferences) or implemented inside this SDK:
 * the JS engine (C++), the flex layout engine, the text layout and the painters.
 *
 * Usage:
 *   val view = MiniAppEngine.create(context, "hello")
 *   container.addView(view)
 */
object MiniAppEngine {

    data class Config(
        val enableLog: Boolean = true,
        val renderBackend: RenderBackend = RenderBackend.CANVAS,
        val maxFps: Int = 60
    )

    enum class RenderBackend { CANVAS, OPENGL_ES }

    @Volatile
    var config: Config = Config()
        private set

    private var initialized = false

    fun init(context: Context, config: Config = Config()) {
        this.config = config
        initialized = true
    }

    /** Creates a running mini program view from an asset directory. */
    fun create(context: Context, appId: String): MiniAppView {
        val pkg = MiniPackage.fromAssets(context, appId)
        return create(context, pkg)
    }

    /** 用户程序根目录：GenUI 的 AI 生成小程序都落地这里（filesDir/miniapps/<appId>/） */
    fun userAppsRoot(context: Context): File =
        File(context.filesDir, "miniapps").apply { mkdirs() }

    /** 列出全部可用 appId：内置 assets 优先级低，用户目录优先 */
    fun listAppIds(context: Context): List<String> {
        val ids = LinkedHashSet<String>()
        runCatching { context.assets.list("miniprograms")?.forEach { ids.add(it) } }
        userAppsRoot(context).listFiles { f -> f.isDirectory }?.forEach { ids.add(it.name) }
        return ids.toList()
    }

    /** 按 appId 解析加载源：用户目录 → assets；不存在返回 null */
    fun resolvePackage(context: Context, appId: String): MiniPackage? {
        val dir = File(userAppsRoot(context), appId)
        if (dir.isDirectory && (File(dir, "app.json").exists())) {
            return MiniPackage.fromDirectory(dir, appId)
        }
        return runCatching {
            if (context.assets.list("miniprograms/$appId")?.isNotEmpty() == true) {
                MiniPackage.fromAssets(context, appId)
            } else null
        }.getOrNull()
    }

    /** 独立 JS 引擎实例（语法预检等非渲染用途；用完必须 close()） */
    fun createEngine(): com.yuanbao.miniapp.js.JsEngine = com.yuanbao.miniapp.js.JsEngine()

    /** Creates a running mini program view by appId（用户目录优先，回退内置 assets） */
    fun createResolved(context: Context, appId: String): MiniAppView? {
        val pkg = resolvePackage(context, appId) ?: return null
        return create(context, pkg)
    }

    /** Creates a running mini program view from a .mapkg (zip) file. */
    fun createFromPackage(context: Context, file: File): MiniAppView {
        val pkg = FileInputStream(file).use { MiniPackage.fromZip(it, file.nameWithoutExtension) }
        return create(context, pkg)
    }

    fun create(context: Context, pkg: MiniPackage): MiniAppView {
        val appConfig = AppConfig.parse(pkg.appJson)
        val view = MiniAppView(context)
        // 监听必须在 start 之前装好：start 内部同步跑 pushPage/loadPage，
        // 这期间产生的错误若没人接，就只剩 Logcat —— 界面上只留一块白，什么提示都没有。
        view.setErrorListener { android.util.Log.e("MiniApp", it) }
        if (config.enableLog) {
            view.setLogListener { level, msg -> android.util.Log.d("MiniApp", "[$level] $msg") }
        }
        view.start(pkg, appConfig)
        return view
    }

    fun destroy(view: MiniAppView) = view.stop()
}
