package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
 * - emotionTagsEnabled: LLM 自动组合情绪标签总开关（一键拉取已配置的 TTS 情绪标签）
 * - emotionProviderId: 情绪标签来源服务商（云 TTS 提供商 id；"" = 自动取全局已选风格标签）
 */
object QuroVoiceFeaturePrefs {
    private const val PREFS = "quro_voice_features"
    private const val K_VOICE_BALL = "voice_ball"
    private const val K_AUTO_READ = "auto_read"
    private const val K_DIALOG_VOICE = "dialog_voice_button"
    private const val K_VOICE_NAME = "voice_name"
    private const val K_VOICE_BALL_SESSION = "voice_ball_session"
    private const val K_AUTOSTART = "autostart"
    private const val K_EMOTION_TAGS_ENABLED = "emotion_tags_enabled"
    private const val K_EMOTION_PROVIDER_ID = "emotion_provider_id"
    private const val K_VOICE_COLOR_ROUTING = "voice_color_routing"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getAutoRead(ctx: Context) = prefs(ctx).getBoolean(K_AUTO_READ, false)
    fun setAutoRead(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(K_AUTO_READ, v).apply()

    fun getDialogVoiceButton(ctx: Context) = prefs(ctx).getBoolean(K_DIALOG_VOICE, false)

    /**
     * 可观察的「对话框语音按钮」开关。
     * 修复 #817：ChatScreen 是常驻根屏，原先 [getDialogVoiceButton] 被 remember 一次性缓存，
     * 在「语音设置」里切换后必须退出重进才生效。改为暴露 StateFlow，让常驻界面即时重组成。
     */
    private val _dialogVoiceButtonFlow = MutableStateFlow(false)
    fun dialogVoiceButtonFlow(ctx: Context): StateFlow<Boolean> {
        _dialogVoiceButtonFlow.value = getDialogVoiceButton(ctx)
        return _dialogVoiceButtonFlow
    }
    fun setDialogVoiceButton(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(K_DIALOG_VOICE, v).apply()
        _dialogVoiceButtonFlow.value = v
    }

    /** 默认语音来源：统一复用 [QuroTtsPrefs]（语音播放链路实际读取的唯一数据源），避免「改了不生效」。 */
    fun getSource(ctx: Context) = QuroTtsPrefs.getSource(ctx)
    fun setSource(ctx: Context, v: String) = QuroTtsPrefs.setSource(ctx, v)

    fun getVoiceName(ctx: Context) = prefs(ctx).getString(K_VOICE_NAME, "") ?: ""
    fun setVoiceName(ctx: Context, v: String) = prefs(ctx).edit().putString(K_VOICE_NAME, v).apply()

    /** 默认语速：统一复用 [QuroTtsPrefs.getRate]（语音播放链路实际读取的唯一数据源）。 */
    fun getSpeed(ctx: Context) = QuroTtsPrefs.getRate(ctx)
    fun setSpeed(ctx: Context, v: Float) = QuroTtsPrefs.setRate(ctx, v)

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

    /**
     * LLM 自动组合情绪标签总开关：开启后，构建系统提示词时会注入来自所选服务商的 TTS 情绪标签，
     * 让 AI 在回复里自然地穿插情绪/语气标签（如 [开心]、[严肃]）。
     */
    fun getEmotionTagsEnabled(ctx: Context) = prefs(ctx).getBoolean(K_EMOTION_TAGS_ENABLED, false)
    fun setEmotionTagsEnabled(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean(K_EMOTION_TAGS_ENABLED, v).apply()

    /**
     * 情绪标签来源服务商 id（云 TTS 提供商 id）。空串表示「自动」：
     * 回落到全局已选风格标签，再回落到云 TTS 兜底词库。
     */
    fun getEmotionProviderId(ctx: Context) = prefs(ctx).getString(K_EMOTION_PROVIDER_ID, "") ?: ""
    fun setEmotionProviderId(ctx: Context, id: String) =
        prefs(ctx).edit().putString(K_EMOTION_PROVIDER_ID, id).apply()

    /**
     * 语色路由（AI 自动分配角色音色）总开关：开启后，AI 在朗读时按内容自由为不同段落分配不同音色
     * （如旁白 / 角色音），并「边播边合成」实现无缝衔接。仅在使用云端 / 小米 MiMo 语音合成时生效。
     * 默认开（功能即为此开关服务），用户可随时关闭回落到单一全局音色。
     */
    fun getVoiceColorRoutingEnabled(ctx: Context) = prefs(ctx).getBoolean(K_VOICE_COLOR_ROUTING, true)
    fun setVoiceColorRoutingEnabled(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean(K_VOICE_COLOR_ROUTING, v).apply()
}
