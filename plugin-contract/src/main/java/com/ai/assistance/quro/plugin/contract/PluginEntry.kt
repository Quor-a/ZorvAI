package com.ai.assistance.quro.plugin.contract

/**
 * 插件入口。宿主加载 APK 后反射实例化这个类。
 *
 * 插件必须在 AndroidManifest.xml 声明：
 * <pre>
 * &lt;meta-data android:name="quro.plugin.entry"
 *            android:value="com.quro.plugin.express.ExpressEntry" /&gt;
 * </pre>
 */
interface PluginEntry {
    fun onCreate(ctx: PluginContext)
    fun onDestroy(ctx: PluginContext) {}
}
