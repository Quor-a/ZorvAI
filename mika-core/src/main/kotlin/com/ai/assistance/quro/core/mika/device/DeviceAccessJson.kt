package com.ai.assistance.quro.core.mika.device

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

fun DeviceAccessResult.toJson() = buildJsonObject {
    put("status", this@toJson.status); put("enabled", this@toJson.enabled)
    put("credential_configured", this@toJson.credentialConfigured); put("attempt_blocked", this@toJson.attemptBlocked)
    this@toJson.state?.let { s -> putJsonObject("device") {
        put("interactive", s.interactive); put("keyguard_showing", s.keyguardShowing)
        put("device_locked", s.deviceLocked); put("secure", s.secure)
        put("helper_available", s.helperAvailable); put("user_unlocked", s.userUnlocked); put("ready", s.ready)
    } }
}
