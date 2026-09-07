package com.ai.assistance.quro.plugin

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * 插件运行时（Kotlin 侧）——逻辑层 + 渲染层的中转与抽象。
 *
 * 两种逻辑层后端：
 *  - [QuickJsLogicBackend]：插件 JS 跑在 QuickJS（Native 线程，每插件一个 JSRuntime 沙箱）。
 *    对应评审报告"沙箱硬化"（内存上限 / 超时中断 / 关 eval）。需要 libquroplugin.so（NDK 编出）。
 *  - [WebViewLogicBackend]：插件 JS 跑在渲染层 WebView 内（同一页面，逻辑+渲染）。
 *    零 NDK 依赖、即装即跑，适合 MVP / 第一方插件；隔离性弱于 QuickJS。
 *
 * 渲染层固定为 WebView DOM（评审结论：默认渲染层用 WebView DOM，绕开 Cax 的 License:None）。
 */

/** .mext 的 manifest.json 解析结果（加载流程第一步）。 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val permissions: List<String>,
    val entry: String
)

/** setData 的 path-diff 回调 + 宿主能力（my.*）网关回调。由渲染层/宿主实现。 */
interface PluginSetDataCallback {
    /** 逻辑层产生一次 setData diff：path 形如 "data.count"，valueJson 为 JSON 字符串。 */
    fun onSetData(path: String, valueJson: String)

    /** 插件请求宿主能力（my.*）：api 如 "storage.get"，paramsJson 为参数；返回结果 JSON。 */
    fun onHostApi(api: String, paramsJson: String): String
}

/* ===================== QuickJS 引擎（JNI 封装） ===================== */

/**
 * 对应 jni/quro_plugin_bridge.c。加载 libquroplugin.so，封装四个 native 方法。
 * 同一进程内可创建多个实例（每插件一个），各自独立 JSRuntime。
 */
class QuickJsEngine {

    companion object {
        init {
            try {
                System.loadLibrary("quroplugin")
            } catch (t: Throwable) {
                // 未编入 .so 时静默失败：调用方应回退到 WebView 后端。
            }
        }

        fun isAvailable(): Boolean = runCatching { Class.forName("com.ai.assistance.quro.plugin.QuickJsEngine") }
            .isSuccess && nativeLibLoaded()

        @JvmStatic
        external fun nativeCreateRuntime(memLimitBytes: Int, timeoutMs: Int, allowEval: Int): Long
        @JvmStatic
        external fun nativeEvalPlugin(ptr: Long, jsCode: String, callback: PluginSetDataCallback, timeoutMs: Int): String?
        @JvmStatic
        external fun nativeInvokeMethod(ptr: Long, method: String, datasetJson: String?, value: String?): String?
        @JvmStatic
        external fun nativeDestroy(ptr: Long)

        private fun nativeLibLoaded(): Boolean = try {
            // 触发一次符号解析；失败抛 UnsatisfiedLinkError
            System.loadLibrary("quroplugin")
            true
        } catch (t: Throwable) { false }
    }

    private var ptr: Long = 0

    /** 插件模式（默认）：删除 eval/Function，沙箱最小面。 */
    fun create(memLimitBytes: Int = 16 * 1024 * 1024, timeoutMs: Int = 2000): Boolean =
        createInternal(memLimitBytes, timeoutMs, allowEval = false)

    /** 脚本包模式（SandboxPackage / code_runner / ToolPkg）：保留 eval/Function（CommonJS require 需要 new Function 包装模块体）。
     *  安全边界换到：内存上限 + 超时中断 + hostCallApi 权限网关（Kotlin 侧只暴露白名单 fs/net/system 操作）。 */
    fun createScriptRuntime(memLimitBytes: Int = 64 * 1024 * 1024, timeoutMs: Int = 15000): Boolean =
        createInternal(memLimitBytes, timeoutMs, allowEval = true)

    private fun createInternal(memLimitBytes: Int, timeoutMs: Int, allowEval: Boolean): Boolean {
        ptr = nativeCreateRuntime(memLimitBytes, timeoutMs, if (allowEval) 1 else 0)
        return ptr != 0L
    }

    /** 执行插件代码（其内 Page({...}) 会触发初始 setData 经 callback 回传）。返回错误串或 null。 */
    fun evalPlugin(jsCode: String, callback: PluginSetDataCallback, timeoutMs: Int = 2000): String? =
        if (ptr == 0L) "engine not created" else nativeEvalPlugin(ptr, jsCode, callback, timeoutMs)

    /** 渲染层事件入口：执行页面对应 method。返回错误串或 null。 */
    fun invokeMethod(method: String, datasetJson: String?, value: String?): String? =
        if (ptr == 0L) "engine not created" else nativeInvokeMethod(ptr, method, datasetJson, value)

    fun destroy() {
        if (ptr != 0L) { nativeDestroy(ptr); ptr = 0L }
    }
}

/* ===================== 逻辑层后端抽象 ===================== */

interface PluginLogicBackend {
    /** 加载并运行插件逻辑代码。code 内含 Page({...}) 等小程序式定义。 */
    fun loadPlugin(code: String, callback: PluginSetDataCallback): String?
    /** 渲染层事件 → 逻辑层方法。 */
    fun invokeMethod(method: String, datasetJson: String?, value: String?): String?
    fun destroy()
}

/** QuickJS 后端：逻辑跑在 Native 沙箱。需要 libquroplugin.so。 */
class QuickJsLogicBackend : PluginLogicBackend {
    private val engine = QuickJsEngine()
    override fun loadPlugin(code: String, callback: PluginSetDataCallback): String? {
        if (!engine.create()) return "quickjs init failed"
        return engine.evalPlugin(code, callback)
    }
    override fun invokeMethod(method: String, datasetJson: String?, value: String?): String? =
        engine.invokeMethod(method, datasetJson, value)
    override fun destroy() = engine.destroy()
}

/**
 * WebView 后端：逻辑跑在渲染层 WebView 内（同一页面）。
 * 约定：页面暴露 window.PluginRuntime.loadPlugin(code) / invokeMethod(method, datasetJson, value)。
 * setData / hostApi 由页面内部处理（hostApi 经 NativeBridge.callApi 同步取回）。
 */
class WebViewLogicBackend(private val webView: WebView) : PluginLogicBackend {
    override fun loadPlugin(code: String, callback: PluginSetDataCallback): String? {
        // 逻辑与渲染同页，setData 在页内直接 patch DOM；hostApi 由页面经 NativeBridge 调用。
        // 这里只负责把插件代码注入执行。callback 在 WebView 模式下不被直接调用（页内闭环）。
        val esc = code.replace("`", "\\`").replace("$", "\\$")
        webView.post {
            webView.evaluateJavascript("window.PluginRuntime && window.PluginRuntime.loadPlugin(`$esc`)") {}
        }
        return null
    }

    override fun invokeMethod(method: String, datasetJson: String?, value: String?): String? {
        val d = (datasetJson ?: "{}").replace("`", "\\`")
        val v = (value ?: "null").replace("`", "\\`")
        webView.post {
            webView.evaluateJavascript("window.PluginRuntime && window.PluginRuntime.invokeMethod('$method', `$d`, `$v`)") {}
        }
        return null
    }

    override fun destroy() { /* WebView 自身销毁即可 */ }
}

/* ===================== 工具：manifest 解析 + 权限网关 ===================== */

object PluginManifestParser {
    fun parse(json: String): PluginManifest {
        val o = JSONObject(json)
        return PluginManifest(
            id = o.getString("id"),
            name = o.getString("name"),
            version = o.getString("version"),
            permissions = (o.optJSONArray("permissions") ?: JSONArray()).let { a ->
                (0 until a.length()).map { a.getString(it) }
            },
            entry = o.optString("entry", "index")
        )
    }

    /** 权限网关：未申即抛，对应评审报告"my.* 权限网关"。 */
    fun assertPermission(m: PluginManifest, perm: String) {
        require(m.permissions.contains(perm)) {
            "permission denied: '$perm' not declared in manifest of ${m.id}"
        }
    }
}
