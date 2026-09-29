package com.ai.assistance.quro.util

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale

/**
 * 非 @Composable 上下文使用的 i18n 运行时。
 *
 * 为什么需要它：
 *   `stringResource()` 只能在 @Composable 作用域调用。而项目里大量界面文案出现在
 *   `remember { }` / `ifBlank { }` / 协程 / onClick 回调 / 顶层普通函数里，这些位置
 *   既不是 Composable，也不一定在词法上可见一个 Context 变量。靠静态推断「此处是否
 *   可见 Context」在真实工程里会大量误判，因此统一退化为运行时取串。
 *
 * 为什么不能直接用 applicationContext：
 *   `AppCompatDelegate.setApplicationLocales()` 在 Android 12 及以下只更新 Activity 的
 *   资源配置，**不会**更新 Application 的 resources。若直接拿 applicationContext.getString，
 *   应用内切换语言后这里取到的仍是系统语言（中文），表现就是「切了语言还是全中文」。
 *   因此这里按当前选择语言用 createConfigurationContext 派生一个带正确 locale 的 Context。
 */
object QuroI18nRt {

    @Volatile
    var appContext: Context? = null

    /** 应用内选择的语言标签；"system" 或空表示跟随系统。 */
    @Volatile
    private var langTag: String = "system"

    @Volatile
    private var localized: Context? = null

    /** 语言切换时同步（由 QuroLocale.apply 调用）。 */
    fun setLocale(language: String?) {
        val t = language?.takeIf { it.isNotBlank() } ?: "system"
        if (t != langTag) localized = null
        langTag = t
    }

    private fun contextForStrings(): Context? {
        val base = appContext ?: return null
        localized?.let { return it }
        if (langTag.isEmpty() || langTag == "system") {
            localized = base
            return base
        }
        return try {
            val cfg = Configuration(base.resources.configuration)
            val locale = Locale.forLanguageTag(langTag.replace('_', '-'))
            cfg.setLocale(locale)
            cfg.setLocales(LocaleList(locale))
            base.createConfigurationContext(cfg).also { localized = it }
        } catch (e: Throwable) {
            localized = base
            base
        }
    }

    fun get(@StringRes id: Int, vararg args: Any): String {
        val c = contextForStrings() ?: return ""
        return try {
            if (args.isEmpty()) c.getString(id) else c.getString(id, *args)
        } catch (e: Exception) {
            ""
        }
    }
}

/** 非 @Composable 位置的取串入口（见 [QuroI18nRt]）。 */
fun qstr(@StringRes id: Int, vararg args: Any): String = QuroI18nRt.get(id, *args)
