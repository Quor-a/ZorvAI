package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.SharedPreferences

/**
 * 语音功能开关与默认配置的统一持久化层。
 * 取代原本散落在 Activity 内存态（voiceBallEnabled）与 quro_voice SharedPreferences 的写法，
 * 让「语音设置」屏、设置页、对话框、悬浮语音球都能读写同一份数据。
 *
 * 字段：
 * - voiceBall: 悬浮语音球总开关
 * - autoRead: AI 回复自动朗读（TTS）
 * - dialogVoiceButton: 对话框输入框是否显示语音输入按钮
 * - source: 默认语音来源（local / cloud / mimo）
 * - voiceName: 默认音色（自然语言描述，供 TTS 使用）
 * - speed: 默认语速 0.5x–2.0x
 * - voiceBallSessionId: 语音球绑定的对话框 id（"" = 跟随当前正在看的对话框）
 */
object QuroVoiceFeaturePrefs {
    private const val PREFS = "quro_voice_features"
    private const val K_VOICE_BALL = "voice_ball"
    private const val K_AUTO_READ = "auto_read"
    private const val K_DIALOG_VOICE = "dialog_voice_button"
    private const val K_SOURCE = "source"
    private const val K_VOICE_NAME = "voice_name"
    private const val K_SPEED = "speed"
    private const val K_VOICE_BALL_SESSION = "voice_ball_session"
    private const val K_AUTOSTART = "autostart"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getAutoRead(ctx: Context) = prefs(ctx).getBoolean(K_AUTO_READ, false)
    fun setAutoRead(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(K_AUTO_READ, v).apply()

    fun getDialogVoiceButton(ctx: Context) = prefs(ctx).getBoolean(K_DIALOG_VOICE, false)
    fun setDialogVoiceButton(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(K_DIALOG_VOICE, v).apply()

    fun getSource(ctx: Context) = prefs(ctx).getString(K_SOURCE, "local") ?: "local"
    fun setSource(ctx: Context, v: String) = prefs(ctx).edit().putString(K_SOURCE, v).apply()

    fun getVoiceName(ctx: Context) = prefs(ctx).getString(K_VOICE_NAME, "") ?: ""
    fun setVoiceName(ctx: Context, v: String) = prefs(ctx).edit().putString(K_VOICE_NAME, v).apply()

    fun getSpeed(ctx: Context) = prefs(ctx).getFloat(K_SPEED, 1.0f)
    fun setSpeed(ctx: Context, v: Float) = prefs(ctx).edit().putFloat(K_SPEED, v).apply()

    /** 语音球绑定的对话框 id；空串表示「跟随当前正在看的对话框」（自动）。 */
    fun getVoiceBallSessionId(ctx: Context) = prefs(ctx).getString(K_VOICE_BALL_SESSION, "") ?: ""
    fun setVoiceBallSessionId(ctx: Context, id: String) =
        prefs(ctx).edit().putString(K_VOICE_BALL_SESSION, id).apply()

    /** 后台自启动：开机后自动拉起常住语音球（含通知栏），默认关。 */
    fun getAutostart(ctx: Context) = prefs(ctx).getBoolean(K_AUTOSTART, false)
    fun setAutostart(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(K_AUTOSTART, v).apply()

    /** 悬浮语音球总开关（持久化）：true=显示可拖拽语音球。通知栏常驻与此无关。 */
    fun getVoiceBall(ctx: Context) = prefs(ctx).getBoolean(K_VOICE_BALL, false)
    fun setVoiceBall(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(K_VOICE_BALL, v).apply()
}
