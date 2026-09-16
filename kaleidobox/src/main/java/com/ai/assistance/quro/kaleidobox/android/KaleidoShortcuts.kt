package com.ai.assistance.quro.kaleidobox.android

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import com.ai.assistance.quro.kaleidobox.core.model.KValue

/**
 * 把插件固定到桌面的"真 Android 组件"逻辑。
 *
 * 放在 kaleidobox 的 android 包（而非 app 模块），是因为：
 *   - 能力 `app.shortcut.create` 在运行时侧注册，需要直接调 [ShortcutManager]；
 *   - [KaleidoActivity]（app 模块）的"加到桌面"按钮也复用同一份逻辑，避免两端各写一遍。
 *
 * 固定意图用 [KaleidoAppContract.deepLink] 拼成隐式 VIEW + BROWSABLE 的 deep-link，
 * 这样不依赖 app 模块里具体 Activity 的类名（kaleidobox 模块也不该反向依赖 app）。
 */
object KaleidoShortcuts {

    /** 把某插件（某 UI 表面）固定到桌面。launcher 支持时弹确认框；否则返回失败原因。 */
    fun pinPluginShortcut(context: Context, pkgId: String, surfaceId: String?, label: String): KValue {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return KValue.fail("E_UNSUPPORTED", "需 Android 8.0+ 才能固定快捷方式")
        }
        val app = context.applicationContext
        val sm = app.getSystemService(ShortcutManager::class.java)
            ?: return KValue.fail("E_UNSUPPORTED", "系统无 ShortcutManager")
        if (!sm.isRequestPinShortcutSupported) {
            return KValue.fail("E_UNSUPPORTED", "当前启动器不支持固定快捷方式")
        }

        val uri = KaleidoAppContract.deepLink(pkgId, surfaceId)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val iconRes = runCatching { app.packageManager.getApplicationInfo(app.packageName, 0).icon }
            .getOrElse { android.R.drawable.sym_def_app_icon }

        val id = "kaleido_${pkgId.replace("[^A-Za-z0-9_]".toRegex(), "_")}_${surfaceId ?: "main"}"
        val shortcut = ShortcutInfo.Builder(app, id).apply {
            setShortLabel(label.take(10))
            setLongLabel(label)
            setIcon(Icon.createWithResource(app, iconRes))
            setIntent(intent)
        }.build()

        return runCatching { sm.requestPinShortcut(shortcut, null) }
            .fold(
                onSuccess = { KValue.ok(true) },
                onFailure = { KValue.fail("E_PIN", it.message ?: "固定失败") },
            )
    }
}
