/*
 * Vendored from droid-mcp (stixez/droid-mcp), Apache License 2.0.
 * https://github.com/stixez/droid-mcp — Copyright 2026 stixez.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 * Repackaged into Quro AI (com.ai.assistance.quro.core.mcp).
 */
package com.ai.assistance.quro.core.mcp

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** 权限检查助手（引擎层，源自 droid-mcp，Apache-2.0）。 */
object PermissionHelper {
    fun hasPermissions(context: Context, permissions: List<String>): Boolean =
        permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun missingPermissions(context: Context, permissions: List<String>): List<String> =
        permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
}
