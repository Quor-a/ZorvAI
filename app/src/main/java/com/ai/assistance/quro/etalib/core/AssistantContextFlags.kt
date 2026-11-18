package com.ai.assistance.quro.etalib.core

import android.service.voice.VoiceInteractionSession

internal object AssistantContextFlags {
    fun forSdk(sdk: Int): Int =
        VoiceInteractionSession.SHOW_WITH_ASSIST or
            VoiceInteractionSession.SHOW_WITH_SCREENSHOT or
            if (sdk >= 37) VoiceInteractionSession.SHOW_WITH_ASSIST_STRUCTURE_SCREEN_CONTENT else 0
}
