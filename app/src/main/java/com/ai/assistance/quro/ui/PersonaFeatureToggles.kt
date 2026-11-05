package com.ai.assistance.quro.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 人格卡里的两个「功能开关」——各自独立、互不影响、不合并。
 *
 * 1. feat_dynamic_ui ：「动态 UI 组件」(```quro-ui DSL) 总开关。默认开。
 * 2. feat_self_card ：「可视化小卡片」总开关。默认开。
 *    🔴 2026-11-05 重写：可视化小卡片是**独立分类**，唯一出口为 `visual_card` 工具
 *    （配套 `visual_card_skeleton` 骨架库 + Widget Lab 统一卡壳），不再是 ```quro-card
 *    Canvas 围栏。旧围栏渲染能力保留为 legacy，但不再作为小卡片教给模型。
 *    它既不是动态 UI 组件，也不是富组件 / 弹窗 / 网页预览，严禁互相顶替。
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
