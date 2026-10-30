package com.ai.assistance.quro.core.assistant

import android.content.Context
import com.ai.assistance.quro.util.QuroDiag
import java.util.Calendar

/**
 * 播报场景。
 *
 * 对标小布设置页「播报设置 → 播报场景设置」：小布不是"一律念"或"一律不念"，
 * 而是**按场景分别开关**（回复内容 / 提醒 / 通知各有独立开关）。
 */
enum class SpeakScene(val wire: String, val label: String, val defaultOn: Boolean) {
    /** 对话回复正文 */
    REPLY("reply", "对话回复", true),

    /** 技能执行结果（查手机、诊断等） */
    SKILL_RESULT("skill-result", "技能执行结果", true),

    /** 提醒与日程（对标小布的提醒播报） */
    REMINDER("reminder", "提醒与日程", true),

    /** 失败与错误提示（"没装环境""连不上"这类必须让人听到） */
    ERROR("error", "错误与失败提示", true),

    /** 一般系统通知（默认关，避免噪音） */
    NOTICE("notice", "系统通知", false),
}

/**
 * 播报策略：音量 / 音色 / 场景 / 静默时段。
 *
 * 对标小布「播报设置：音量与音色、播报场景设置」。
 *
 * ## 为什么要有静默时段
 * 语音助手最招人烦的不是声音难听，是**半夜说话**。小布的播报设置里有场景开关，
 * 我们再加一条静默时段：到点自动闭嘴（错误提示可选穿透），这是"能长期开着"的前提。
 *
 * 本类只管**策略判断**，不直接调 TTS —— 具体合成仍走 `QuroTtsHolder`（全场唯一实例），
 * 避免出现第二个 TextToSpeech 抢引擎连接。
 */
object AssistantSpeakPolicy {
    private const val TAG = "assistant-speak"
    private const val PREFS = "quro_assistant_speak"

    private const val KEY_ENABLED = "speak_enabled"
    private const val KEY_VOLUME = "volume_scale"
    private const val KEY_VOICE = "voice_id"
    private const val KEY_QUIET = "quiet_range"
    private const val KEY_QUIET_ALLOW_ERROR = "quiet_allow_error"
    private const val PREFIX_SCENE = "scene_"

    /** 静默时段默认值：不启用（空串）。 */
    private const val DEFAULT_QUIET = ""

    private fun prefs(ctx: Context) =
        runCatching { ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()

    // ── 总开关 ──────────────────────────────────────────────
    fun isSpeakEnabled(ctx: Context): Boolean =
        prefs(ctx)?.getBoolean(KEY_ENABLED, true) ?: true

    fun setSpeakEnabled(ctx: Context, on: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
        QuroDiag.log(TAG, "speak ${if (on) "on" else "off"}")
    }

    // ── 音量（0..1，相对系统媒体音量，不直接改系统设置）───────
    fun volumeScale(ctx: Context): Float =
        (prefs(ctx)?.getFloat(KEY_VOLUME, 1f) ?: 1f).coerceIn(0f, 1f)

    fun setVolumeScale(ctx: Context, v: Float) {
        prefs(ctx)?.edit()?.putFloat(KEY_VOLUME, v.coerceIn(0f, 1f))?.apply()
    }

    // ── 音色（交给 TTS 引擎的 voice 名）─────────────────────
    fun voiceId(ctx: Context): String = prefs(ctx)?.getString(KEY_VOICE, "").orEmpty()

    fun setVoiceId(ctx: Context, id: String) {
        prefs(ctx)?.edit()?.putString(KEY_VOICE, id.trim())?.apply()
        QuroDiag.log(TAG, "voice=$id")
    }

    // ── 场景 ────────────────────────────────────────────────
    fun isSceneOn(ctx: Context, scene: SpeakScene): Boolean =
        prefs(ctx)?.getBoolean(PREFIX_SCENE + scene.wire, scene.defaultOn) ?: scene.defaultOn

    fun setSceneOn(ctx: Context, scene: SpeakScene, on: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(PREFIX_SCENE + scene.wire, on)?.apply()
        QuroDiag.log(TAG, "scene ${scene.wire} ${if (on) "on" else "off"}")
    }

    // ── 静默时段 ────────────────────────────────────────────
    /** 形如 `22:00-07:00`；空串表示不启用。 */
    fun quietRange(ctx: Context): String =
        prefs(ctx)?.getString(KEY_QUIET, DEFAULT_QUIET).orEmpty()

    fun setQuietRange(ctx: Context, range: String) {
        val v = range.trim()
        if (v.isNotEmpty() && parseQuietRange(v) == null) {
            QuroDiag.log(TAG, "quiet range 格式非法，忽略: $v")
            return
        }
        prefs(ctx)?.edit()?.putString(KEY_QUIET, v)?.apply()
        QuroDiag.log(TAG, "quiet=$v")
    }

    /** 静默时段内是否允许错误播报穿透（默认允许：出事了要让人知道）。 */
    fun quietAllowError(ctx: Context): Boolean =
        prefs(ctx)?.getBoolean(KEY_QUIET_ALLOW_ERROR, true) ?: true

    fun setQuietAllowError(ctx: Context, on: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_QUIET_ALLOW_ERROR, on)?.apply()
    }

    fun inQuietHours(ctx: Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val r = parseQuietRange(quietRange(ctx)) ?: return false
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return inQuietWindow(r, minutes)
    }

    /**
     * 最终判决：这个场景现在该不该出声。
     *
     * 顺序：总开关 → 场景开关 → 静默时段（错误提示可穿透）。
     */
    fun shouldSpeak(ctx: Context, scene: SpeakScene): Boolean {
        if (!isSpeakEnabled(ctx)) return false
        if (!isSceneOn(ctx, scene)) return false
        if (inQuietHours(ctx)) {
            if (scene == SpeakScene.ERROR && quietAllowError(ctx)) return true
            return false
        }
        return true
    }

    /** 设置页用的整体快照。 */
    fun snapshot(ctx: Context): Map<String, Any?> = linkedMapOf(
        "enabled" to isSpeakEnabled(ctx),
        "volume" to volumeScale(ctx),
        "voice" to voiceId(ctx),
        "quiet" to quietRange(ctx),
        "quietAllowError" to quietAllowError(ctx),
        "scenes" to SpeakScene.entries.associate { it.wire to isSceneOn(ctx, it) },
    )
}

/**
 * `HH:mm-HH:mm` → (起始分钟, 结束分钟)；非法返回 null。
 *
 * 提成顶层函数是为了**能在没有 Context 的 JVM 单测里直接测** —— 跨零点区间判断
 * （22:00-07:00 到底含 23:30 还是 06:00）是这类代码最容易写错的地方，真机手测覆盖不到边界。
 * 注意允许单位数小时（9:00-18:00），用户手输时很常见。
 */
internal fun parseQuietRange(s: String): Pair<Int, Int>? {
    val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})\s*$""").find(s) ?: return null
    val h1 = m.groupValues[1].toIntOrNull() ?: return null
    val m1 = m.groupValues[2].toIntOrNull() ?: return null
    val h2 = m.groupValues[3].toIntOrNull() ?: return null
    val m2 = m.groupValues[4].toIntOrNull() ?: return null
    if (h1 !in 0..23 || h2 !in 0..23 || m1 !in 0..59 || m2 !in 0..59) return null
    return (h1 * 60 + m1) to (h2 * 60 + m2)
}

/** 给定区间与「当天第几分钟」，判断是否落在静默时段内（支持跨零点）。 */
internal fun inQuietWindow(range: Pair<Int, Int>, minutes: Int): Boolean {
    val (from, to) = range
    // 跨零点：22:00-07:00 → from > to，命中 [from,1440) ∪ [0,to]
    return if (from <= to) minutes in from..to else (minutes >= from || minutes <= to)
}
