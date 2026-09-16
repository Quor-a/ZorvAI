package com.ai.assistance.quro.plugin.engine.core

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import com.ai.assistance.quro.plugin.contract.PluginContext
import com.ai.assistance.quro.plugin.contract.PluginEntry
import com.ai.assistance.quro.plugin.engine.bridge.AciBridge
import com.ai.assistance.quro.plugin.engine.install.InstallResult
import com.ai.assistance.quro.plugin.engine.install.PluginInstaller
import com.ai.assistance.quro.plugin.engine.install.PluginRecord
import com.ai.assistance.quro.plugin.engine.registry.ExtensionRegistry
import java.io.File

/**
 * 插件引擎门面。宿主（ZorvAI）在 Application.onCreate 里 init 一次。
 *
 * <pre>
 * QuroPluginEngine.init(this, setOf("llm","tts","memory","aci"))
 * QuroPluginEngine.loadAllInstalled()
 * </pre>
 */
object QuroPluginEngine {

    private var hostContext: Context? = null
    private var hostCaps: Set<String> = emptySet()
    private var installer: PluginInstaller? = null
    private val loaded = java.util.concurrent.ConcurrentHashMap<String, LoadedPlugin>()

    internal class LoadedPlugin(
        val record: PluginRecord,
        val context: PluginContext,
        val entry: PluginEntry,
        val classLoader: PluginClassLoader,
        val resources: Resources
    )

    fun init(context: Context, hostCapabilities: Set<String>) {
        hostContext = context.applicationContext
        hostCaps = hostCapabilities
        installer = PluginInstaller(context)
    }

    fun isReady(): Boolean = installer != null

    // ==================== 安装 / 卸载 ====================

    /** 从文件导入安装（本地 APK 或已下载的插件包） */
    fun install(apk: File, requireSameSignature: Boolean = true): InstallResult =
        installer!!.install(apk, requireSameSignature).also {
            if (it.success) {
                runCatching { load(it.pluginId) }
                AciBridge.publishPluginCapabilities()
            }
        }

    fun uninstall(pluginId: String): Boolean {
        unload(pluginId)
        val ok = installer!!.uninstall(pluginId)
        AciBridge.publishPluginCapabilities()
        return ok
    }

    fun installed(): List<PluginRecord> = installer?.list() ?: emptyList()

    // ==================== 加载 / 卸载 ====================

    fun load(pluginId: String): Boolean {
        if (loaded.containsKey(pluginId)) return true
        val ctx = hostContext ?: return false
        val record = installer?.get(pluginId) ?: return false
        val apk = File(record.apkPath)
        if (!apk.exists()) return false

        return try {
            val entryClass = readEntryClass(ctx, apk)
            val odex = File(ctx.filesDir, "plugins/.odex/$pluginId").apply { mkdirs() }
            val cl = PluginClassLoader(
                apk.absolutePath, odex.absolutePath,
                record.nativeLibDir.ifEmpty { null }, ctx.classLoader
            )
            val res = createResources(ctx, apk.absolutePath)
            val pctx = PluginContextImpl(
                pluginId = pluginId,
                pluginVersion = record.versionName,
                hostContext = ctx,
                hostCapabilities = hostCaps
            )
            val entry = cl.loadClass(entryClass)
                .getDeclaredConstructor().newInstance() as PluginEntry
            loaded[pluginId] = LoadedPlugin(record, pctx, entry, cl, res)
            entry.onCreate(pctx)
            android.util.Log.i(TAG, "插件已加载：$pluginId，注册 ${ExtensionRegistry.countOf(pluginId)} 个扩展")
            true
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "插件加载失败：$pluginId", t)
            false
        }
    }

    fun unload(pluginId: String) {
        loaded.remove(pluginId)?.let {
            runCatching { it.entry.onDestroy(it.context) }
            it.context.unregisterAll()
        }
    }

    fun reload(pluginId: String): Boolean {
        unload(pluginId)
        return load(pluginId)
    }

    fun loadAllInstalled() = installed().forEach { runCatching { load(it.pluginId) } }

    fun isLoaded(pluginId: String) = loaded.containsKey(pluginId)
    fun loadedIds(): List<String> = loaded.keys.toList()

    fun resourcesOf(pluginId: String): Resources? = loaded[pluginId]?.resources

    /** 最近一次加载失败原因（供插件面板展示） */
    var lastLoadError: String? = null
        private set

    // ==================== 内部 ====================

    private fun readEntryClass(ctx: Context, apk: File): String {
        @Suppress("DEPRECATION")
        val info = ctx.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            android.content.pm.PackageManager.GET_META_DATA
        ) ?: error("不是合法 APK")
        return info.applicationInfo?.metaData?.getString(META_ENTRY)
            ?: error("插件未声明 meta-data: $META_ENTRY")
    }

    /**
     * 用插件 APK 的资源路径构建 Resources，供插件读自己的 strings/layout/图片。
     * ★ 必须同时挂宿主自身资源路径，否则插件布局里引用宿主主题/系统属性会解析失败。
     */
    private fun createResources(ctx: Context, apkPath: String): Resources {
        val host = ctx.resources
        val am = AssetManager::class.java.newInstance()
        val add = AssetManager::class.java
            .getDeclaredMethod("addAssetPath", String::class.java)
        add.isAccessible = true
        add.invoke(am, ctx.packageResourcePath)
        add.invoke(am, apkPath)
        return Resources(am, host.displayMetrics, host.configuration)
    }

    const val META_ENTRY = "quro.plugin.entry"
    private const val TAG = "QuroPluginEngine"
}
