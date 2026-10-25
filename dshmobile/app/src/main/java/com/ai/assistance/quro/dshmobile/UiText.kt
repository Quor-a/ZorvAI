package com.ai.assistance.quro.dshmobile

import android.content.Context

/**
 * Bilingual copy for native screens.
 *
 * Strings that only exist inline keep both languages at the call site, so the
 * English text cannot drift away from the Chinese one it replaces. Long-lived
 * screens read the preference on each render; the preference only changes from
 * the settings page, which reloads the Web UI and rebuilds native screens.
 */
internal object UiText {
  /** True when the English locale is active; an absent context keeps Chinese. */
  fun isEnglish(context: Context?): Boolean = context != null && AppLanguage.dshLocale(context) == AppLanguage.MODE_EN

  /** Pick the copy for the active locale. */
  fun t(context: Context?, zh: String, en: String): String = if (isEnglish(context)) en else zh
}
