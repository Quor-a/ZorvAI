package com.ai.assistance.quro.core.mika.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.icu.util.ULocale
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * App 界面的语言:跟随系统、简体中文、繁體中文、English。资源里英文是默认(`values/`),简体中文在 `values-zh/`、繁体中文在 `values-b+zh+Hant/`,
 * 安卓按文字体系挑资源,所以「跟随系统」时简体、繁体中文的系统各显示对应的中文,其他语言的系统显示英文。
 *
 * 安卓 13 起由系统的「应用语言」管(LocaleManager):系统记住选择、套到整个进程、重建界面,
 * 系统设置里的「应用语言」和这里的开关是同一个值。安卓 11、12 没有这项,由这里记在本地,
 * 经 [wrap] 套到界面的 Context、经 [resources] 套到界面以外取文字的地方,换了之后重建当前界面。
 */
enum class AppLanguage(val tag: String?) {
    System(null),
    SimplifiedChinese("zh-Hans"),
    TraditionalChinese("zh-Hant"),
    English("en");

    companion object {
        private const val PREFS = "app_language"
        private const val KEY_TAG = "tag"

        /** 安卓 12 及以前选定的语言;null 表示跟随系统。进程里读一次,换的时候更新。 */
        @Volatile
        private var legacyTag: String? = null
        @Volatile
        private var legacyLoaded = false

        /** 安卓 12 及以前按选定语言建出的资源,跟着语言换。 */
        @Volatile
        private var cached: Pair<String, Resources>? = null

        private val systemManaged get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        fun current(context: Context): AppLanguage = fromTag(
            if (systemManaged) {
                context.getSystemService(LocaleManager::class.java).applicationLocales.takeUnless { it.isEmpty }?.get(0)?.toLanguageTag()
            } else {
                legacy(context)
            },
        )

        /** 语言标签对应哪一项:见 [fromTag];标签里没写文字体系时,按 ICU 的常见写法补出来。 */
        fun fromTag(tag: String?): AppLanguage = fromTag(tag) { ULocale.addLikelySubtags(ULocale.forLanguageTag(it)).script }

        /**
         * 中文按文字体系分:写的或 [likelyScript] 补出的是 Hant 就是繁體中文,别的中文是简体中文;
         * 其他非空的标签是英文。以前选「中文」存下的 zh-Hans 仍是简体中文。
         */
        internal fun fromTag(tag: String?, likelyScript: (String) -> String): AppLanguage {
            if (tag.isNullOrEmpty()) return System
            val l = Locale.forLanguageTag(tag)
            if (l.language != "zh") return English
            return if (l.script.ifEmpty { likelyScript(tag) } == "Hant") TraditionalChinese else SimplifiedChinese
        }

        /** 换语言并重建界面。安卓 13 起交给系统(系统自己重建);之前记在本地,重建 [context] 所在的界面。 */
        fun set(context: Context, language: AppLanguage) {
            if (systemManaged) {
                context.getSystemService(LocaleManager::class.java).applicationLocales =
                    language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
                return
            }
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TAG, language.tag).commit()
            legacyTag = language.tag
            legacyLoaded = true
            cached = null
            applyDefaultLocale(context)
            context.activity()?.recreate()
        }

        /** App 启动时调:安卓 12 及以前选了语言时,把进程的默认 Locale(日期、数字的格式)也换成它。 */
        fun applyDefaultLocale(context: Context) {
            if (systemManaged) return
            val locales = legacyLocales(context) ?: Resources.getSystem().configuration.locales
            Locale.setDefault(locales[0])
        }

        /** 套上 App 语言的 Context:在 Activity 的 attachBaseContext 和非 Activity 建界面的地方用。安卓 13 起原样返回。 */
        fun wrap(base: Context): Context {
            val locales = legacyLocales(base) ?: return base
            return base.createConfigurationContext(Configuration().apply { setLocales(locales) })
        }

        /**
         * 界面以外取文字用的资源:跟着 App 语言。安卓 13 起也按系统记下的应用语言另建一份,
         * 不直接用 Application 的资源:进程不是系统按 App 正常拉起时(如测试启动),那份资源还是系统语言。
         */
        fun resources(app: Context): Resources {
            val tag = if (systemManaged) {
                app.getSystemService(LocaleManager::class.java).applicationLocales.takeUnless { it.isEmpty }?.toLanguageTags()
            } else {
                legacy(app)
            }
            if (tag == null) return app.resources
            cached?.takeIf { it.first == tag }?.let { return it.second }
            val r = app.createConfigurationContext(Configuration().apply { setLocales(LocaleList.forLanguageTags(tag)) }).resources
            cached = tag to r
            return r
        }

        private fun legacyLocales(context: Context): LocaleList? =
            if (systemManaged) null else legacy(context)?.let(LocaleList::forLanguageTags)

        private fun legacy(context: Context): String? {
            if (!legacyLoaded) {
                legacyTag = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null)
                legacyLoaded = true
            }
            return legacyTag
        }

        private fun Context.activity(): Activity? {
            var c: Context? = this
            while (c is ContextWrapper) {
                if (c is Activity) return c
                c = c.baseContext
            }
            return null
        }
    }
}
