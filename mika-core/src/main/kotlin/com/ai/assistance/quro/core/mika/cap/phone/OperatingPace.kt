package com.ai.assistance.quro.core.mika.cap.phone

import com.ai.assistance.quro.core.mika.uistate.PlacePace
import kotlin.random.Random

/**
 * 设置里的「操作节奏」:在 App 里操作快还是像人。手势本身(落指停顿、按住时长、落点偏差、弧线)三种都像人,见 [GesturePacing]。
 *
 * - [FAST]:动作之间不另停、打字一次写入全文、不设屏数上限(以前的做法);
 * - [STANDARD](默认):打字一段一段打出来,动作之间停一小会儿(同一屏约 0.5–1.2 秒、新的一屏约 1–2 秒),
 *   App 知识说限制浏览快慢的 App 按慢的那一套(停得更久、按字数读、屏数有上限、打字更慢);
 * - [HUMAN]:所有 App 都按慢的那一套停留和打字;屏数上限仍只给限制浏览快慢的 App。
 */
enum class OperatingPace {
    FAST, STANDARD, HUMAN;

    /** 这个任务在 App 里一步一步操作的节奏。 */
    fun appPacing(random: Random = Random.Default): AppPacing = when (this) {
        FAST -> AppPacing.Off
        STANDARD -> AppPacing.human(random)
        HUMAN -> AppPacing.human(random, limits = AppPacing.Limits.CAREFUL)
    }

    /** 在 App 知识说 [pace] 的 App 里打字的节奏。 */
    fun typing(pace: PlacePace, random: Random = Random.Default): TypingPacing = when {
        this == FAST -> TypingPacing.Instant
        this == HUMAN || pace == PlacePace.CAREFUL -> TypingPacing.human(random, TypingPacing.Limits.CAREFUL)
        else -> TypingPacing.human(random)
    }

    companion object {
        val DEFAULT = STANDARD

        /** 存下的名字(大小写不论);认不出为 null。 */
        fun parse(value: String?): OperatingPace? = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}
