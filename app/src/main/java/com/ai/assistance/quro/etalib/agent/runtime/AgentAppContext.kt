package com.ai.assistance.quro.etalib.agent.runtime

import android.content.Context

internal object AgentAppContext {
    fun resolve(): Context? {
        val activityThreadClass = runCatching {
            Class.forName("android.app.ActivityThread")
        }.getOrNull()
        runCatching {
            activityThreadClass
                ?.getDeclaredMethod("currentApplication")
                ?.apply { isAccessible = true }
                ?.invoke(null) as? Context
        }.getOrNull()?.let { return it.applicationContext ?: it }

        runCatching {
            Class.forName("android.app.AppGlobals")
                .getDeclaredMethod("getInitialApplication")
                .apply { isAccessible = true }
                .invoke(null) as? Context
        }.getOrNull()?.let { return it.applicationContext ?: it }

        return null
    }
}
