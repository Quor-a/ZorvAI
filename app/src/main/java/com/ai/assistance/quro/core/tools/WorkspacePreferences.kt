package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.SharedPreferences

/**
 * 工作区偏好管理：持久化用户选择的工作区路径。
 */
object WorkspacePreferences {
    private const val PREFS_NAME = "workspace_preferences"
    private const val KEY_CURRENT_WORKSPACE = "current_workspace"
    private const val KEY_RECENT_WORKSPACES = "recent_workspaces"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 设置当前工作区路径。
     */
    fun setCurrentWorkspace(context: Context, path: String?) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putString(KEY_CURRENT_WORKSPACE, path)
            .apply()

        // 添加到最近使用列表
        if (path != null) {
            addToRecent(context, path)
        }
    }

    /**
     * 获取当前工作区路径。返回 null 表示使用默认工作区。
     */
    fun getCurrentWorkspace(context: Context): String? {
        return getPrefs(context).getString(KEY_CURRENT_WORKSPACE, null)
    }

    /**
     * 清除当前工作区设置（恢复默认）。
     */
    fun clearCurrentWorkspace(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_CURRENT_WORKSPACE)
            .apply()
    }

    /**
     * 添加到最近使用的列表。
     */
    private fun addToRecent(context: Context, path: String) {
        val prefs = getPrefs(context)
        val recent = prefs.getStringSet(KEY_RECENT_WORKSPACES, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        recent.remove(path)
        val newRecent = mutableSetOf(path)
        newRecent.addAll(recent)
        val limitedRecent = newRecent.take(10).toSet()
        prefs.edit()
            .putStringSet(KEY_RECENT_WORKSPACES, limitedRecent)
            .apply()
    }

    /**
     * 获取最近使用的工作区列表。
     */
    fun getRecentWorkspaces(context: Context): List<String> {
        val prefs = getPrefs(context)
        return prefs.getStringSet(KEY_RECENT_WORKSPACES, emptySet())?.toList() ?: emptyList()
    }

    /**
     * 清除所有工作区偏好设置。
     */
    fun clearAll(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}