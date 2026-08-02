package com.k2fsa.sherpa.ncnn

class OfflineStream(var ptr: Long) {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int) =
        acceptWaveform(ptr, samples, sampleRate)

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun use(block: (OfflineStream) -> Unit) {
        try {
            block(this)
        } finally {
            release()
        }
    }

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun delete(ptr: Long)

    companion object {
        init {
            // F-Droid 风味不含离线 ASR 原生库，跳过加载避免 UnsatisfiedLinkError
            if (com.ai.assistance.quro.BuildConfig.FLAVOR != "fdroid") {
                System.loadLibrary("sherpa-ncnn-jni")
            }
        }
    }
}
