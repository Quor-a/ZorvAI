package com.ai.assistance.quro.util
import com.ai.assistance.quro.R

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.Locale

/**
 * 应用语言策略。
 *
 * - "system"（默认）：跟随手机系统语言，系统切换语言时应用跟着切换。
 * - 具体语言代码（如 "zh" / "en" / "ja"）：固定为所选语言，不随系统变化；
 *   未提供对应语言资源时 Android 自动回退到默认(中文)。
 *
 * 【为什么不能只依赖 AppCompatDelegate.setApplicationLocales】
 * 该 API 只对 AppCompatActivity 生效（由 AppCompat 在 Activity 创建时把 locale 应用到 base
 * Context）。本应用的主界面等是 ComponentActivity，因此切语言后界面会**一直是中文**。
 * 所以这里额外提供 [wrap]，由每个 Activity 在自己的 attachBaseContext 里调用，
 * 不依赖 AppCompat 也能生效；并在 [apply] 后主动重建已打开的 Activity 让新语言立即生效。
 */
object QuroLocale {
    const val PREFS = "quro_ui"
    const val KEY = "app_language"

    /** 应用首次启动时捕获的系统语言，作为无显式选择时的回退基准。 */
    @Volatile
    var appDefaultLocale: LocaleListCompat = LocaleListCompat.getDefault()
        private set

    private val activities =
        Collections.synchronizedList(ArrayList<WeakReference<Activity>>())

    @Volatile
    private var tracked = false

    fun captureDefaultIfNeeded() {
        if (appDefaultLocale.size() == 0) {
            appDefaultLocale = LocaleListCompat.getDefault()
        }
    }

    /** 记录所有已创建的 Activity，供切语言后重建用。 */
    fun track(app: Application) {
        if (tracked) return
        tracked = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                activities.add(WeakReference(a))
            }

            override fun onActivityStarted(a: Activity) {}

            override fun onActivityResumed(a: Activity) {}

            override fun onActivityPaused(a: Activity) {}

            override fun onActivityStopped(a: Activity) {}

            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}

            override fun onActivityDestroyed(a: Activity) {
                activities.removeAll { it.get() == null || it.get() === a }
            }
        })
    }

    /** 当前偏好里的语言标签（原始值，可能是 "system"）。 */
    fun currentTag(base: Context): String = try {
        base.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "system")?.trim()?.takeIf { it.isNotEmpty() } ?: "system"
    } catch (_: Throwable) {
        "system"
    }

    /**
     * 把语言配置包进任意 Context。
     * ComponentActivity 不走 AppCompat，必须在 Activity.attachBaseContext 里自己包一层，
     * 否则 Compose 的 stringResource 永远拿系统语言（表现为「切了语言还是中文」）。
     */
    fun wrap(base: Context): Context {
        val tag = currentTag(base)
        if (tag == "system") return base
        return try {
            val cfg = Configuration(base.resources.configuration)
            val locale = Locale.forLanguageTag(tag.replace('_', '-'))
            cfg.setLocale(locale)
            cfg.setLocales(LocaleList(locale))
            base.createConfigurationContext(cfg)
        } catch (_: Throwable) {
            base
        }
    }

    /**
     * 应用语言设置。
     * @param language "system"=跟随系统；否则为 BCP-47 语言标签（如 "en"/"ja"/"zh-rCN"），空串视为系统。
     */
    fun apply(language: String) {
        captureDefaultIfNeeded()
        val locales = when {
            language.isBlank() || language == "system" -> LocaleListCompat.getEmptyLocaleList()
            else -> LocaleListCompat.forLanguageTags(language)
        }
        // 1) AppCompat 路线：对本应用里的 AppCompatActivity 有效。
        try {
            AppCompatDelegate.setApplicationLocales(locales)
        } catch (_: Throwable) {
        }
        // 2) 自管路线：qstr() 与非 AppCompat 的 Activity 都靠这里。
        QuroI18nRt.setLocale(if (locales.isEmpty) "system" else locales.toLanguageTags())
        // 3) 已打开的界面立即重建，否则要等下次冷启才换语言（用户会以为「切了没反应」）。
        recreateAll()
    }

    /** 重建所有已创建的 Activity，使新语言立即生效。 */
    fun recreateAll() {
        val snapshot = synchronized(activities) { activities.toList() }
        for (ref in snapshot) {
            val a = ref.get() ?: continue
            try {
                if (!a.isFinishing) a.recreate()
            } catch (_: Throwable) {
            }
        }
    }

    /** 语言代码 → 中文显示名（用于设置界面与系统提示词）。 */
    val LANGUAGE_NAMES: Map<String, String>
        get() = mapOf(
        "system" to qstr(R.string.qk_03872),
        "zh" to "简体中文", "zh-rCN" to "简体中文", "zh-rTW" to "繁體中文",
        "en" to "English", "ja" to qstr(R.string.qk_02504), "ko" to "한국어",
        "fr" to "Français", "de" to "Deutsch", "es" to "Español",
        "ru" to "Русский", "pt" to "Português", "ar" to "العربية", "hi" to "हिन्दी"
    )

    /** 大国语言列表（设置界面选择器使用；小国语言后续补充）。 */
    val MAJOR_LANGUAGES: List<Pair<String, String>>
        get() = listOf(
        "system" to (LANGUAGE_NAMES["system"] ?: qstr(R.string.qk_03872)),
        "zh" to (LANGUAGE_NAMES["zh"] ?: "简体中文"),
        "en" to (LANGUAGE_NAMES["en"] ?: "English"),
        "ja" to (LANGUAGE_NAMES["ja"] ?: qstr(R.string.qk_02504)),
        "ko" to (LANGUAGE_NAMES["ko"] ?: "한국어"),
        "fr" to (LANGUAGE_NAMES["fr"] ?: "Français"),
        "de" to (LANGUAGE_NAMES["de"] ?: "Deutsch"),
        "es" to (LANGUAGE_NAMES["es"] ?: "Español"),
        "ru" to (LANGUAGE_NAMES["ru"] ?: "Русский"),
        "pt" to (LANGUAGE_NAMES["pt"] ?: "Português"),
        "ar" to (LANGUAGE_NAMES["ar"] ?: "العربية"),
        "hi" to (LANGUAGE_NAMES["hi"] ?: "हिन्दी")
    )
}