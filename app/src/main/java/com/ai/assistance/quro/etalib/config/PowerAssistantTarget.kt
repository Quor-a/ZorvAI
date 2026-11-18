package com.ai.assistance.quro.etalib.config

internal enum class PowerAssistantTarget(
    val persistedValue: String,
) {
    ETA("eta"),
    GEMINI("gemini"),
    OEM("oem"),
    ;

    companion object {
        fun resolve(
            persistedValue: String?,
            legacyPowerKeyTakeover: Boolean,
        ): PowerAssistantTarget = entries.firstOrNull {
            it.persistedValue == persistedValue
        } ?: if (legacyPowerKeyTakeover) {
            GEMINI
        } else {
            OEM
        }
    }
}
