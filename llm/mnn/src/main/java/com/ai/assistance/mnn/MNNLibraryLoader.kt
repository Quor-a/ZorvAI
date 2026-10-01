package com.ai.assistance.mnn

import android.util.Log

/**
 * MNN 库加载器
 * 确保 MNN 和 MNNWrapper 库只被加载一次
 */
internal object MNNLibraryLoader {
    private const val TAG = "MNNLibraryLoader"
    
    @Volatile
    private var loaded = false
    
    private val lock = Any()
    
    /**
     * 加载 MNN 库
     * 如果库已经加载，则不会重复加载
     */
    fun loadLibraries() {
        if (loaded) {
            return
        }
        
        synchronized(lock) {
            if (loaded) {
                return
            }
            
            try {
                // 【L2 · 符号隔离】MNN 已改为静态链入 MNNWrapper（见 llm/mnn/CMakeLists.txt），
                // 因此 libMNN.so 不再产出。这里保留一次探测：万一将来关掉
                // QURO_LLM_MONOLITHIC 回退到共享构建，老路径依然可用。
                try {
                    System.loadLibrary("MNN")
                    Log.d(TAG, "MNN library loaded successfully (separate .so)")
                } catch (e: UnsatisfiedLinkError) {
                    Log.d(TAG, "libMNN.so 不存在，按静态链接布局继续（预期行为）")
                }

                // 然后加载我们的 JNI 包装库（静态构建下它自带全部 MNN 代码）
                System.loadLibrary("MNNWrapper")
                Log.d(TAG, "MNNWrapper library loaded successfully")
                
                loaded = true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load MNN libraries", e)
                throw e
            }
        }
    }
    
    /**
     * 检查库是否已加载
     */
    fun isLoaded(): Boolean = loaded
}

