package com.ai.assistance.quro.core.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import com.ai.assistance.quro.R
import com.ai.assistance.quro.activity.AssistantSkillPlazaActivity
import com.ai.assistance.quro.activity.QuroMainActivity
import com.ai.assistance.quro.util.QuroDiag

/**
 * 长按桌面图标弹出的快捷方式。
 *
 * 对标小布的 `BootstrapShortCutMetaDataActivity`（`com.android.settings.MANUFACTURER_APPLICATION_SETTING`）
 * 与 `FlamingoMainActivity` 的 `MINI_LAUNCHER_MAIN` / `WIDGET_ENTRY`：都是"把核心动作
 * 提到桌面一按直达"，而不是让用户先开 App 再找入口。
 *
 * 只注册**幂等**的入口（重复安装 shortcut 不会累积），失败一律吞掉只记日志——
 * 桌面快捷方式坏了不该影响应用启动，这是硬要求。
 */
object AssistantShortcuts {
    private const val TAG = "assistant-shortcut"

    fun install(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val app = ctx.applicationContext
        runCatching {
            val sm = app.getSystemService(Context.SHORTCUT_SERVICE) as? ShortcutManager ?: return
            val icon = Icon.createWithResource(app, R.mipmap.ic_launcher)
            val list = mutableListOf<ShortcutInfo>()

            list += ShortcutInfo.Builder(app, "quro.skills")
                .setShortLabel("能力")
                .setLongLabel("能力广场：看 ZorvAI 会什么")
                .setIcon(icon)
                .setIntent(
                    Intent(app, AssistantSkillPlazaActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                    }
                )
                .build()

            list += ShortcutInfo.Builder(app, "quro.chat")
                .setShortLabel("对话")
                .setLongLabel("新开一轮对话")
                .setIcon(icon)
                .setIntent(
                    Intent(app, QuroMainActivity::class.java).apply {
                        action = Intent.ACTION_MAIN
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                )
                .build()

            // 用 setDynamicShortcuts 覆盖而不是 add：数量固定，重装不会累积
            sm.dynamicShortcuts = list
            QuroDiag.log(TAG, "shortcuts installed count=${list.size}")
        }.onFailure {
            QuroDiag.log(TAG, "install failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }
}
