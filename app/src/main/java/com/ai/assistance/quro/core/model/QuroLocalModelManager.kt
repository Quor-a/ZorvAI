package com.ai.assistance.quro.core.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "QuroLocalModel"

/**
 * 本地模型管理器
 * 
 * 参考 Agora（llama.cpp）和 Kai（LiteRT）的设计，支持：
 * 1. 本地模型管理（下载、加载、卸载）
 * 2. 多引擎支持（MNN、llama.cpp、LiteRT）
 * 3. 模型推理接口
 * 4. 内存优化
 * 5. 模型性能监控
 */
class QuroLocalModelManager(private val context: Context) {
    
    private val loadedModels = mutableMapOf<String, LoadedModel>()
    private val isInitializing = AtomicBoolean(false)
    private val totalMemoryUsed = AtomicLong(0L)
    
    /**
     * 初始化模型管理器
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (isInitializing.getAndSet(true)) return@withContext
        
        try {
            Log.d(TAG, "初始化本地模型管理器")
            
            // 扫描已下载的模型
            scanDownloadedModels()
            
            // 清理无效模型
            cleanupInvalidModels()
            
            Log.d(TAG, "本地模型管理器初始化完成")
        } finally {
            isInitializing.set(false)
        }
    }
    
    /**
     * 扫描已下载的模型
     */
    private fun scanDownloadedModels() {
        val modelsDir = File(context.filesDir, "local_models")
        if (!modelsDir.exists()) return
        
        modelsDir.listFiles()?.forEach { modelDir ->
            if (modelDir.isDirectory) {
                val configFile = File(modelDir, "model_config.json")
                if (configFile.exists()) {
                    try {
                        val config = parseModelConfig(configFile)
                        Log.d(TAG, "发现本地模型: ${config.name}")
                    } catch (e: Exception) {
                        Log.e(TAG, "解析模型配置失败: ${configFile.absolutePath}", e)
                    }
                }
            }
        }
    }
    
    /**
     * 清理无效模型
     */
    private fun cleanupInvalidModels() {
        val modelsDir = File(context.filesDir, "local_models")
        if (!modelsDir.exists()) return
        
        modelsDir.listFiles()?.forEach { modelDir ->
            if (modelDir.isDirectory) {
                val configFile = File(modelDir, "model_config.json")
                val modelFile = File(modelDir, "model.bin")
                
                if (!configFile.exists() || !modelFile.exists()) {
                    Log.d(TAG, "清理无效模型目录: ${modelDir.name}")
                    modelDir.deleteRecursively()
                }
            }
        }
    }
    
    /**
     * 获取可用模型列表
     */
    fun getAvailableModels(): List<LocalModelInfo> {
        val models = mutableListOf<LocalModelInfo>()
        val modelsDir = File(context.filesDir, "local_models")
        
        if (!modelsDir.exists()) return models
        
        modelsDir.listFiles()?.forEach { modelDir ->
            if (modelDir.isDirectory) {
                val configFile = File(modelDir, "model_config.json")
                if (configFile.exists()) {
                    try {
                        val config = parseModelConfig(configFile)
                        models.add(config)
                    } catch (e: Exception) {
                        Log.e(TAG, "解析模型配置失败: ${configFile.absolutePath}", e)
                    }
                }
            }
        }
        
        return models
    }
    
    /**
     * 加载模型
     */
    suspend fun loadModel(
        modelId: String,
        engine: ModelEngine = ModelEngine.AUTO
    ): ModelLoadResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "加载模型: $modelId, 引擎: $engine")
            
            // 检查是否已加载
            if (loadedModels.containsKey(modelId)) {
                return@withContext ModelLoadResult.Success(
                    modelId = modelId,
                    engine = engine,
                    loadTimeMs = 0,
                    memoryUsedMB = 0
                )
            }
            
            // 查找模型文件
            val modelDir = File(context.filesDir, "local_models/$modelId")
            if (!modelDir.exists()) {
                return@withContext ModelLoadResult.Error("模型目录不存在: $modelId")
            }
            
            val modelFile = File(modelDir, "model.bin")
            if (!modelFile.exists()) {
                return@withContext ModelLoadResult.Error("模型文件不存在: $modelId/model.bin")
            }
            
            // 选择推理引擎
            val selectedEngine = selectEngine(engine, modelFile)
            
            // 加载模型
            val startTime = System.currentTimeMillis()
            val loadedModel = loadModelWithEngine(modelId, modelFile, selectedEngine)
            val loadTime = System.currentTimeMillis() - startTime
            
            loadedModels[modelId] = loadedModel
            totalMemoryUsed.addAndGet(loadedModel.memoryUsedMB.toLong())
            
            Log.d(TAG, "模型加载成功: $modelId, 耗时: ${loadTime}ms, 内存: ${loadedModel.memoryUsedMB}MB")
            
            ModelLoadResult.Success(
                modelId = modelId,
                engine = selectedEngine,
                loadTimeMs = loadTime,
                memoryUsedMB = loadedModel.memoryUsedMB
            )
        } catch (e: Exception) {
            Log.e(TAG, "模型加载失败: $modelId", e)
            ModelLoadResult.Error("模型加载失败: ${e.message}")
        }
    }
    
    /**
     * 卸载模型
     */
    fun unloadModel(modelId: String): Boolean {
        val loadedModel = loadedModels.remove(modelId) ?: return false
        
        try {
            // 释放引擎资源
            unloadModelWithEngine(loadedModel)
            
            totalMemoryUsed.addAndGet(-loadedModel.memoryUsedMB.toLong())
            
            Log.d(TAG, "模型卸载成功: $modelId, 释放内存: ${loadedModel.memoryUsedMB}MB")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "模型卸载失败: $modelId", e)
            return false
        }
    }
    
    /**
     * 执行推理
     */
    suspend fun inference(
        modelId: String,
        input: String,
        parameters: InferenceParameters = InferenceParameters()
    ): InferenceResult = withContext(Dispatchers.IO) {
        val loadedModel = loadedModels[modelId]
            ?: return@withContext InferenceResult.Error("模型未加载: $modelId")
        
        try {
            Log.d(TAG, "执行推理: $modelId, 输入长度: ${input.length}")
            
            val startTime = System.currentTimeMillis()
            val output = performInference(loadedModel, input, parameters)
            val inferenceTime = System.currentTimeMillis() - startTime
            
            InferenceResult.Success(
                output = output,
                inferenceTimeMs = inferenceTime,
                tokensPerSecond = calculateTokensPerSecond(output, inferenceTime)
            )
        } catch (e: Exception) {
            Log.e(TAG, "推理失败: $modelId", e)
            InferenceResult.Error("推理失败: ${e.message}")
        }
    }
    
    /**
     * 选择推理引擎
     */
    private fun selectEngine(preferred: ModelEngine, modelFile: File): ModelEngine {
        if (preferred != ModelEngine.AUTO) return preferred
        
        // 根据模型文件扩展名和大小自动选择
        val extension = modelFile.extension.lowercase()
        val sizeMB = modelFile.length() / 1024 / 1024
        
        return when {
            extension == "mnn" -> ModelEngine.MNN
            extension == "gguf" || extension == "bin" && sizeMB < 100 -> ModelEngine.LLAMA_CPP
            extension == "tflite" || extension == "lite" -> ModelEngine.LITERT
            sizeMB < 50 -> ModelEngine.LLAMA_CPP  // 小模型用 llama.cpp
            sizeMB < 200 -> ModelEngine.MNN       // 中等模型用 MNN
            else -> ModelEngine.LLAMA_CPP          // 大模型用 llama.cpp（内存效率更好）
        }
    }
    
    /**
     * 使用引擎加载模型
     */
    private fun loadModelWithEngine(
        modelId: String,
        modelFile: File,
        engine: ModelEngine
    ): LoadedModel {
        // 这里需要根据不同的引擎实现实际的加载逻辑
        // 简化实现，返回模拟的 LoadedModel
        
        val memoryUsedMB = modelFile.length() / 1024 / 1024
        
        return LoadedModel(
            modelId = modelId,
            engine = engine,
            modelFile = modelFile,
            memoryUsedMB = memoryUsedMB.toInt(),
            loadedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * 使用引擎卸载模型
     */
    private fun unloadModelWithEngine(loadedModel: LoadedModel) {
        // 这里需要根据不同的引擎实现实际的卸载逻辑
        // 简化实现
        Log.d(TAG, "卸载模型: ${loadedModel.modelId}, 引擎: ${loadedModel.engine}")
    }
    
    /**
     * 执行推理
     */
    private fun performInference(
        loadedModel: LoadedModel,
        input: String,
        parameters: InferenceParameters
    ): String {
        // 这里需要根据不同的引擎实现实际的推理逻辑
        // 简化实现，返回模拟的输出
        
        return when (loadedModel.engine) {
            ModelEngine.MNN -> {
                // MNN 推理
                "MNN 模型推理结果: $input"
            }
            ModelEngine.LLAMA_CPP -> {
                // llama.cpp 推理
                "llama.cpp 模型推理结果: $input"
            }
            ModelEngine.LITERT -> {
                // LiteRT 推理
                "LiteRT 模型推理结果: $input"
            }
            else -> {
                "未知引擎推理结果: $input"
            }
        }
    }
    
    /**
     * 计算 tokens 每秒
     */
    private fun calculateTokensPerSecond(output: String, inferenceTimeMs: Long): Float {
        if (inferenceTimeMs <= 0) return 0f
        val tokenCount = output.length // 简化：用字符数代替 token 数
        return (tokenCount.toFloat() / inferenceTimeMs * 1000)
    }
    
    /**
     * 获取模型状态
     */
    fun getModelStatus(modelId: String): ModelStatus {
        val loadedModel = loadedModels[modelId]
        return if (loadedModel != null) {
            ModelStatus(
                modelId = modelId,
                isLoaded = true,
                engine = loadedModel.engine,
                memoryUsedMB = loadedModel.memoryUsedMB,
                loadedAt = loadedModel.loadedAt
            )
        } else {
            ModelStatus(
                modelId = modelId,
                isLoaded = false
            )
        }
    }
    
    /**
     * 获取总内存使用
     */
    fun getTotalMemoryUsedMB(): Int = totalMemoryUsed.get().toInt()
    
    /**
     * 获取可用内存
     */
    fun getAvailableMemoryMB(): Long {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / 1024 / 1024
        val usedMemory = totalMemoryUsed.get() / 1024 / 1024
        return maxMemory - usedMemory
    }
    
    /**
     * 清理所有模型
     */
    fun cleanup() {
        loadedModels.keys.toList().forEach { modelId ->
            unloadModel(modelId)
        }
        loadedModels.clear()
        totalMemoryUsed.set(0)
    }
    
    /**
     * 解析模型配置
     */
    private fun parseModelConfig(configFile: File): LocalModelInfo {
        // 这里需要实现实际的配置解析
        // 简化实现
        return LocalModelInfo(
            id = configFile.parentFile?.name ?: "",
            name = configFile.parentFile?.name ?: "未知模型",
            description = "",
            engine = ModelEngine.AUTO,
            sizeMB = 0,
            createdAt = configFile.lastModified()
        )
    }
    
    companion object {
        @Volatile
        private var instance: QuroLocalModelManager? = null
        
        fun getInstance(context: Context): QuroLocalModelManager {
            return instance ?: synchronized(this) {
                instance ?: QuroLocalModelManager(context.applicationContext).also { 
                    instance = it 
                }
            }
        }
    }
}

/**
 * 推理引擎
 */
enum class ModelEngine {
    AUTO,       // 自动选择
    MNN,        // MNN 推理引擎
    LLAMA_CPP,  // llama.cpp 推理引擎
    LITERT,     // LiteRT 推理引擎
    ONNX,       // ONNX Runtime
    TENSORFLOW  // TensorFlow Lite
}

/**
 * 本地模型信息
 */
data class LocalModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val engine: ModelEngine,
    val sizeMB: Long,
    val createdAt: Long,
    val author: String = "",
    val version: String = "",
    val license: String = ""
)

/**
 * 已加载模型
 */
data class LoadedModel(
    val modelId: String,
    val engine: ModelEngine,
    val modelFile: File,
    val memoryUsedMB: Int,
    val loadedAt: Long
)

/**
 * 模型加载结果
 */
sealed class ModelLoadResult {
    data class Success(
        val modelId: String,
        val engine: ModelEngine,
        val loadTimeMs: Long,
        val memoryUsedMB: Int
    ) : ModelLoadResult()
    
    data class Error(val message: String) : ModelLoadResult()
}

/**
 * 推理参数
 */
data class InferenceParameters(
    val temperature: Float = 0.7f,
    val maxTokens: Int = 1024,
    val topP: Float = 0.9f,
    val topK: Int = 50,
    val repeatPenalty: Float = 1.1f,
    val seed: Int = -1
)

/**
 * 推理结果
 */
sealed class InferenceResult {
    data class Success(
        val output: String,
        val inferenceTimeMs: Long,
        val tokensPerSecond: Float
    ) : InferenceResult()
    
    data class Error(val message: String) : InferenceResult()
}

/**
 * 模型状态
 */
data class ModelStatus(
    val modelId: String,
    val isLoaded: Boolean,
    val engine: ModelEngine? = null,
    val memoryUsedMB: Int = 0,
    val loadedAt: Long = 0
)
