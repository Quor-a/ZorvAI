package com.ai.assistance.quro.plugin.engine.core

import dalvik.system.DexClassLoader

/**
 * 插件类加载器。
 *
 * ★ 契约包名强制走 parent：否则插件和宿主各加载一份接口类，
 *   调用时直接 ClassCastException —— 自研插件框架第一大坑。
 * 其余类插件优先（打破双亲委派），插件可自带依赖版本而不影响宿主。
 */
internal class PluginClassLoader(
    apk: String, odex: String, lib: String?, parent: ClassLoader,
    private val sharedPrefixes: List<String> = DEFAULT_SHARED
) : DexClassLoader(apk, odex, lib, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (sharedPrefixes.any { name.startsWith(it) }) return super.loadClass(name, resolve)
        findLoadedClass(name)?.let { return it }
        try { findClass(name)?.let { return it } } catch (_: ClassNotFoundException) {}
        return super.loadClass(name, resolve)
    }

    companion object {
        val DEFAULT_SHARED = listOf(
            "com.ai.assistance.quro.plugin.contract.",
            "com.ai.assistance.quro.plugin.extension.",
            "com.ai.assistance.quro.plugin.dsl.",
            "kotlin.", "kotlinx.", "java.", "javax.",
            "android.", "androidx.", "dalvik.", "org.json."
        )
    }
}
