package com.ai.assistance.quro.core.aidlaci

import android.content.Context
import android.content.SharedPreferences

/**
 * ACI 应用偏好设置管理器。
 *
 * 管理用户选择的默认 ACI 应用和其他相关设置。
 */
object AciAppPreferences {
    private const val PREFS_NAME = "aci_app_preferences"
    private const val KEY_DEFAULT_PACKAGE = "default_aci_package"
    private const val KEY_DEFAULT_APP_NAME = "default_aci_app_name"
    private const val KEY_RECENT_PACKAGES = "recent_aci_packages"
    private const val KEY_AUTO_SELECT = "auto_select_aci_app"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 设置默认 ACI 应用。
     */
    fun setDefaultApp(context: Context, packageName: String, appName: String) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putString(KEY_DEFAULT_PACKAGE, packageName)
            .putString(KEY_DEFAULT_APP_NAME, appName)
            .apply()

        // 添加到最近使用的列表
        addToRecent(context, packageName)
    }

    /**
     * 获取默认 ACI 应用包名。
     */
    fun getDefaultPackage(context: Context): String? {
        return getPrefs(context).getString(KEY_DEFAULT_PACKAGE, null)
    }

    /**
     * 获取默认 ACI 应用名称。
     */
    fun getDefaultAppName(context: Context): String? {
        return getPrefs(context).getString(KEY_DEFAULT_APP_NAME, null)
    }

    /**
     * 清除默认 ACI 应用设置。
     */
    fun clearDefaultApp(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_DEFAULT_PACKAGE)
            .remove(KEY_DEFAULT_APP_NAME)
            .apply()
    }

    /**
     * 设置是否自动选择默认 ACI 应用。
     */
    fun setAutoSelect(context: Context, autoSelect: Boolean) {
        getPrefs(context).edit()
            .putBoolean(KEY_AUTO_SELECT, autoSelect)
            .apply()
    }

    /**
     * 获取是否自动选择默认 ACI 应用。
     */
    fun isAutoSelect(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_SELECT, true)
    }

    /**
     * 添加到最近使用的列表。
     */
    private fun addToRecent(context: Context, packageName: String) {
        val prefs = getPrefs(context)
        val recent = prefs.getStringSet(KEY_RECENT_PACKAGES, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        
        // 移除已存在的（如果存在）
        recent.remove(packageName)
        
        // 添加到开头（最近使用的在最前面）
        val newRecent = mutableSetOf(packageName)
        newRecent.addAll(recent)
        
        // 只保留最近5个
        val limitedRecent = newRecent.take(5).toSet()
        
        prefs.edit()
            .putStringSet(KEY_RECENT_PACKAGES, limitedRecent)
            .apply()
    }

    /**
     * 获取最近使用的 ACI 应用列表。
     */
    fun getRecentPackages(context: Context): List<String> {
        val prefs = getPrefs(context)
        return prefs.getStringSet(KEY_RECENT_PACKAGES, emptySet())?.toList() ?: emptyList()
    }

    /**
     * 清除所有 ACI 应用偏好设置。
     */
    fun clearAll(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}