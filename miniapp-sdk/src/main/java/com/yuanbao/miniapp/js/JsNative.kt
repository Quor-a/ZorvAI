package com.yuanbao.miniapp.js

/**
 * Declarations of the native (self-developed C++ engine) entry points.
 * All native methods are implemented in sdk/src/main/cpp/jni_bridge.cpp.
 */
internal object JsNative {
    init {
        System.loadLibrary("miniappjs")
    }

    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeEvaluate(handle: Long, source: String): String
    external fun nativeRegisterHostFunction(handle: Long, name: String)
    external fun nativeInvokeFunction(handle: Long, fnId: Int, argsJson: String): String
    external fun nativeCompileFunction(handle: Long, source: String): Int
    external fun nativeCallGlobal(handle: Long, name: String, argsJson: String): String
    external fun nativeLastError(handle: Long): String
}
