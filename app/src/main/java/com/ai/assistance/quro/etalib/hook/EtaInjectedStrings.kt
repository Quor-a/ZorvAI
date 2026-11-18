package com.ai.assistance.quro.etalib.hook

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import com.ai.assistance.quro.etalib.core.ModuleConfig
import java.util.Locale

/**
 * 目标进程只读 Eta 的安装包资源。缓存不保存翻译结果，系统语言变化后会重建配置 Context。
 * 任何解析失败都回退英文，不能让资源问题影响厂商助手原有进程。
 */
internal object EtaInjectedStrings {
    private data class CachedContext(
        val localeTags: String,
        val context: Context,
    )

    @Volatile
    private var cachedContext: CachedContext? = null

    fun get(
        targetContext: Context?,
        @StringRes resourceId: Int,
        englishFallback: String,
        vararg formatArgs: Any,
    ): String {
        if (targetContext == null) return formatFallback(englishFallback, formatArgs)
        return runCatching {
            val localeTags = targetContext.resources.configuration.locales.toLanguageTags()
            val localizedContext = cachedContext
                ?.takeIf { it.localeTags == localeTags }
                ?.context
                ?: createLocalizedContext(targetContext, localeTags).also { context ->
                    cachedContext = CachedContext(localeTags, context)
                }
            localizedContext.getString(resourceId, *formatArgs)
        }.getOrElse {
            formatFallback(englishFallback, formatArgs)
        }
    }

    private fun createLocalizedContext(targetContext: Context, localeTags: String): Context {
        val etaContext = targetContext.createPackageContext(
            ModuleConfig.ETA_PACKAGE,
            Context.CONTEXT_IGNORE_SECURITY,
        )
        val configuration = Configuration(targetContext.resources.configuration).apply {
            setLocales(android.os.LocaleList.forLanguageTags(localeTags))
        }
        return etaContext.createConfigurationContext(configuration)
    }

    private fun formatFallback(pattern: String, args: Array<out Any>): String =
        if (args.isEmpty()) pattern else String.format(Locale.ENGLISH, pattern, *args)
}
