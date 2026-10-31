package com.ai.assistance.quro.core.mika.phone.a11y

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings

/**
 * 目标 App 调起的系统界面所在的包(权限框、选择器、文件和照片选择器):出现在前台不算被切走。
 * 按标准 Intent 解析得到,不写死包名。
 */
internal object SystemPickers {
    /** 系统自带的选择器(ResolverActivity 等)所在的包。 */
    const val SYSTEM_PACKAGE = "android"
    private const val ACTION_REQUEST_PERMISSIONS = "android.content.pm.action.REQUEST_PERMISSIONS"

    fun intents(): List<Intent> = buildList {
        add(Intent(ACTION_REQUEST_PERMISSIONS))
        add(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain"), null))
        add(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Intent(MediaStore.ACTION_PICK_IMAGES))
    }

    fun settingsPackage(pm: PackageManager): String? =
        runCatching { pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName }.getOrNull()

    fun resolve(pm: PackageManager): Set<String> =
        intents().mapNotNull { runCatching { pm.resolveActivity(it, 0)?.activityInfo?.packageName }.getOrNull() }.toSet() + SYSTEM_PACKAGE
}
