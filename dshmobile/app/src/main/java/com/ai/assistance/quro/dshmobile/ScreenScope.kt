package com.ai.assistance.quro.dshmobile

import android.content.Context

/**
 * User-owned model screen-access scope.
 *
 * This preference is deliberately native-owned: tool/model paths may read it for fail-closed
 * enforcement, but only the DSH settings UI invokes the bridge setter. Android display ids are not
 * stored here; stable product aliases express the user's permitted screen family.
 */
enum class ScreenScope(val wire: String) {
  VIRTUAL_ONLY("virtual-only"),
  REAL_ONLY("real-only"),
  ALL("all");

  /** Whether this user choice includes one stable product screen alias. */
  fun allows(screenId: String): Boolean = when (this) {
    VIRTUAL_ONLY -> ScreenTargets.isVirtual(screenId)
    REAL_ONLY -> screenId == ScreenTargets.REAL
    ALL -> screenId == ScreenTargets.REAL || ScreenTargets.isVirtual(screenId)
  }

  /** Stable execution-point denial for virtual-display input. */
  fun inputDenial(screenId: String): String? =
    if (allows(screenId)) null else "screen-out-of-scope"

  companion object {
    fun fromWire(value: String?): ScreenScope = entries.firstOrNull { it.wire == value } ?: VIRTUAL_ONLY
  }
}

/** Stable model-facing aliases. Only [REAL] has a fixed Android display id. */
object ScreenTargets {
  const val REAL = "real"
  /** 规格 §2.1：壳侧分配 1..N 编号；本版上限 1 屏。 */
  const val VIRTUAL = "virtual-1"
  const val REAL_DISPLAY_ID = 0

  private val VIRTUAL_PATTERN = Regex("^virtual-([1-9][0-9]{0,2})$")

  /** `virtual-N` 编号；非虚拟别名返回 null。 */
  fun virtualOrdinal(screenId: String?): Int? =
    VIRTUAL_PATTERN.matchEntire(screenId ?: "")?.groupValues?.get(1)?.toIntOrNull()

  fun isVirtual(screenId: String?): Boolean = virtualOrdinal(screenId) != null

  fun known(screenId: String): Boolean = screenId == REAL || isVirtual(screenId)
}

/** Native source of truth for the screen scope selector. */
object ScreenScopePrefs {
  private const val PREFS = "dsh_screen_scope"
  private const val KEY_SCOPE = "scope"

  /** @return current normalized scope; absent/corrupt data fails closed to virtual-only. */
  fun current(context: Context): ScreenScope = ScreenScope.fromWire(
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SCOPE, null),
  )

  /** Store one user-selected wire value and return its normalized value. */
  fun set(context: Context, value: String?): ScreenScope {
    val scope = ScreenScope.fromWire(value)
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit().putString(KEY_SCOPE, scope.wire).apply()
    return scope
  }
}
