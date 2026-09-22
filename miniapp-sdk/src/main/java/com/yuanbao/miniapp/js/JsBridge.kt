package com.yuanbao.miniapp.js

/**
 * Static callback target invoked from C++ (see jni_bridge.cpp).
 * The engine's logic thread calls these; they must be thread-safe and fast.
 */
internal object JsBridge {

    @Volatile
    var hostInvoker: ((name: String, argsJson: String) -> String)? = null

    @Volatile
    var logger: ((level: String, message: String) -> Unit)? = null

    @Volatile
    var timerScheduler: ((delayMs: Int, repeat: Boolean, fnId: Int) -> Int)? = null

    @Volatile
    var timerCanceller: ((id: Int) -> Unit)? = null

    @JvmStatic
    fun invokeHost(name: String, argsJson: String): String {
        return try {
            hostInvoker?.invoke(name, argsJson) ?: "null"
        } catch (t: Throwable) {
            "{\"__error\":\"${t.message?.replace("\"", "'") ?: "host error"}\"}"
        }
    }

    @JvmStatic
    fun onLog(level: String, message: String) {
        logger?.invoke(level, message)
    }

    @JvmStatic
    fun onScheduleTimer(delayMs: Int, repeat: Int, fnId: Int): Int {
        return timerScheduler?.invoke(delayMs, repeat == 1, fnId) ?: 0
    }

    @JvmStatic
    fun onClearTimer(id: Int) {
        timerCanceller?.invoke(id)
    }
}
