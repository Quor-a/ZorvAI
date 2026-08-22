package com.ai.assistance.quro.core.policy

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局权限策略（原创）：CMS v2 能力模块系统 与 CapOS 权限子系统 各自一套三态策略。
 *
 * - [ALLOW]  允许：全部允许，不需要再问权限（应用内确认被跳过；系统级授权仍需用户在系统界面开启）。
 * - [DENY]  禁止：任何提升/调用都被直接拒绝。
 * - [ASK]   询问：每次都弹确认（权限子系统弹四阶段确认框；CMS v2 弹 4 级授权框）。
 *
 * 设计为单例 + StateFlow，便于对话内控制条与设置页实时反映；首次访问用任意 Context 惰性初始化。
 */
enum class QuroPolicy {
    ALLOW,
    DENY,
    ASK;

    /** 给 AI 的提示文案，说明当前策略下应当如何行动。 */
    fun usageHint(): String = when (this) {
        ALLOW -> "当前为「允许」：可不经询问直接调用能力 / 提升权限，放心执行。"
        DENY -> "当前为「禁止」：任何能力调用 / 权限提升都会被拒绝，请直接告知用户该功能被禁用。"
        ASK -> "当前为「询问」：调用能力前需用户在对话底部控制条确认；被拦截时告知用户去切换即可。"
    }
}

object QuroPolicyStore {
    private const val PREFS = "quro_policy"
    private const val KEY_CMS = "cms"
    private const val KEY_PRIV = "priv"

    private var prefs: android.content.SharedPreferences? = null

    private val _cms = MutableStateFlow(QuroPolicy.ASK)
    private val _priv = MutableStateFlow(QuroPolicy.ASK)

    val cmsFlow: StateFlow<QuroPolicy> = _cms.asStateFlow()
    val privFlow: StateFlow<QuroPolicy> = _priv.asStateFlow()

    @Synchronized
    private fun ensure(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _cms.value = QuroPolicy.valueOf(prefs!!.getString(KEY_CMS, "ASK")!!)
        _priv.value = QuroPolicy.valueOf(prefs!!.getString(KEY_PRIV, "ASK")!!)
    }

    /** CMS v2 能力模块系统 当前策略（惰性初始化）。 */
    fun getCms(ctx: Context): QuroPolicy {
        ensure(ctx)
        return _cms.value
    }

    /** CapOS 权限子系统 当前策略（惰性初始化）。 */
    fun getPriv(ctx: Context): QuroPolicy {
        ensure(ctx)
        return _priv.value
    }

    fun setCms(ctx: Context, p: QuroPolicy) {
        ensure(ctx)
        _cms.value = p
        prefs!!.edit { putString(KEY_CMS, p.name) }
    }

    fun setPriv(ctx: Context, p: QuroPolicy) {
        ensure(ctx)
        _priv.value = p
        prefs!!.edit { putString(KEY_PRIV, p.name) }
    }
}
