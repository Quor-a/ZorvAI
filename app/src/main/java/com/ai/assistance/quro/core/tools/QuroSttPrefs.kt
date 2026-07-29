package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.SharedPreferences

/** STT（语音识别）持久化参数：识别引擎来源 + 模型选择 + 识别语言 + 是否返回部分结果。供设置屏与语音球读取。 */
object QuroSttPrefs {
    private const val PREFS = "quro_stt"
    private const val KEY_SOURCE = "stt_source"
    private const val KEY_MODEL_REF = "stt_model_ref"
    private const val KEY_MODEL_NAME = "stt_model_name"
    private const val KEY_MODEL_PROVIDER = "stt_model_provider"
    private const val KEY_LANG = "stt_language"
    private const val KEY_PARTIAL = "stt_partial"
    private const val KEY_USE_CHAT_COMPLETIONS = "stt_use_chat_completions"

    const val SOURCE_LOCAL = "local"
    const val SOURCE_MODEL = "model"
    const val SOURCE_ONDEVICE = "ondevice"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── 引擎来源（本地识别 / AI 模型 / 本地模型端侧） ──
    fun getSource(ctx: Context): String {
        val s = prefs(ctx).getString(KEY_SOURCE, SOURCE_LOCAL)
        return when (s) {
            SOURCE_MODEL, SOURCE_ONDEVICE -> s
            else -> SOURCE_LOCAL
        }
    }
    fun setSource(ctx: Context, s: String) {
        val v = when (s) {
            SOURCE_MODEL, SOURCE_ONDEVICE -> s
            else -> SOURCE_LOCAL
        }
        prefs(ctx).edit().putString(KEY_SOURCE, v).apply()
    }

    // ── AI 模型选择三件套（镜像 TTS 写法） ──
    /** 模型引用：空串=未选；"active"=当前活跃配置；否则为已保存预设的 id。 */
    fun getModelRef(ctx: Context): String = prefs(ctx).getString(KEY_MODEL_REF, "") ?: ""
    fun setModelRef(ctx: Context, ref: String) = prefs(ctx).edit().putString(KEY_MODEL_REF, ref).apply()

    fun getModelName(ctx: Context): String = prefs(ctx).getString(KEY_MODEL_NAME, "") ?: ""
    fun setModelName(ctx: Context, name: String) = prefs(ctx).edit().putString(KEY_MODEL_NAME, name).apply()

    fun getModelProvider(ctx: Context): String = prefs(ctx).getString(KEY_MODEL_PROVIDER, "") ?: ""
    fun setModelProvider(ctx: Context, provider: String) = prefs(ctx).edit().putString(KEY_MODEL_PROVIDER, provider).apply()

    /** 一次性写入模型选择三件套，保持原子一致。 */
    fun setModelSelection(ctx: Context, ref: String, name: String, provider: String) {
        prefs(ctx).edit().apply {
            putString(KEY_MODEL_REF, ref)
            putString(KEY_MODEL_NAME, name)
            putString(KEY_MODEL_PROVIDER, provider)
            apply()
        }
    }

    // ── 语言 / 部分结果（兼容旧字段） ──
    fun getLanguage(ctx: Context): String = prefs(ctx).getString(KEY_LANG, "zh-CN") ?: "zh-CN"
    fun setLanguage(ctx: Context, lang: String) = prefs(ctx).edit().putString(KEY_LANG, lang).apply()

    fun getPartial(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PARTIAL, true)
    fun setPartial(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_PARTIAL, on).apply()

    /** 云端转写是否走 /chat/completions（多模态音频）而非 /audio/transcriptions。
     *  适用：部分网关（如 MIMO）不支持 /audio/transcriptions（404），但支持在 chat 消息里带音频。 */
    fun getUseChatCompletions(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_USE_CHAT_COMPLETIONS, false)
    fun setUseChatCompletions(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_USE_CHAT_COMPLETIONS, on).apply()
}
