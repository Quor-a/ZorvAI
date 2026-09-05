package com.ai.assistance.quro.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 人格卡里的两个「功能开关」——各自独立、互不影响、不合并。
 *
 * 1. feat_dynamic_ui ：已有的「动态 UI 组件」(quro-ui DSL) 总开关。默认开。
 * 2. feat_self_card ：「可视化小卡片（自研卡片渲染）」（7 层自研架构，见 core.ui.card），
 *                      与动态 UI 完全无关，不合并。v1.0.82 起功能补全（提示词接线 + 渲染器补齐），
 *                      默认开——AI 用 ```quro-card 围栏下发小卡片。
 */
object PersonaFeatureToggles {
    private const val PREFS = "quro_persona_features"
    private const val KEY_DYNAMIC_UI = "feat_dynamic_ui"
    private const val KEY_SELF_CARD = "feat_self_card"

    fun isDynamicUiEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DYNAMIC_UI, true)

    fun setDynamicUiEnabled(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DYNAMIC_UI, v).apply()
    }

    fun isSelfCardEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SELF_CARD, true)

    fun setSelfCardEnabled(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SELF_CARD, v).apply()
    }
}

/** 人格卡内读取两个开关的当前值（可组合作用域内调用）。 */
@Composable
fun rememberPersonaFeatureStates(): Pair<Boolean, Boolean> {
    val ctx = LocalContext.current
    return remember(ctx) {
        PersonaFeatureToggles.isDynamicUiEnabled(ctx) to PersonaFeatureToggles.isSelfCardEnabled(ctx)
    }
}
