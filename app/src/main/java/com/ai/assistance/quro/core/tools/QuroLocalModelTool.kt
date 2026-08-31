package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.QuroToolResult
import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.model.QuroLocalModelManager
import com.ai.assistance.quro.core.model.ModelEngine
import com.ai.assistance.quro.core.model.InferenceParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

/**
 * 本地模型管理工具
 * 
 * 提供本地模型的管理功能，包括加载、卸载、推理等。
 */
class QuroLocalModelTool(private val context: Context) : QuroTool {
    
    private val modelManager = QuroLocalModelManager.getInstance(context)
    
    /**
     * 工具规格定义
     */
    // ===== QuroTool 契约实现 =====
    // 本类原先只是「独立组件」（只有 getToolSpec + suspend execute），既未实现 QuroTool，
    // 也未注册进工具注册表 —— AI 根本调不到，属于死代码。补上契约后由
    // buildQuroRegistry 注册，才真正进入模型的 function calling 工具集。
    override val name: String get() = getToolSpec().name
    override val description: String get() = getToolSpec().description
    override val parametersJson: String get() = getToolSpec().parametersJson

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        // QuroTool.run 是同步契约，而内部实现是 suspend：这里用 runBlocking 桥接。
        // 工具本身都在 IO/Default 线程执行，不会阻塞主线程。
        return runBlocking {
            runCatching { execute(args) }
                .getOrElse { e -> QuroToolResult.Error("执行失败：${e.message ?: e::class.simpleName}") }
                .let { r -> if (r.name == "error") "❌ ${r.result}" else r.result }
        }
    }

    fun getToolSpec(): QuroToolSpec {
        return QuroToolSpec(
            name = "local_model",
            description = "管理本地模型，支持加载、卸载、推理、查看状态等操作。可以使用 MNN、llama.cpp、LiteRT 等推理引擎。",
            // QuroToolSpec 第三个参数是 parametersJson（JSON Schema 字符串）。项目只依赖 org.json，
            // 没有 parameters=mapOf(...) + QuroToolSpec.Parameter(...) 这套 DSL，必须手写 schema。
            parametersJson = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "string")
                        put("description", "操作类型：list（列出可用模型）、load（加载模型）、unload（卸载模型）、inference（执行推理）、status（查看模型状态）、info（查看系统信息）")
                    })
                    put("model_id", JSONObject().apply {
                        put("type", "string")
                        put("description", "模型ID（load、unload、inference、status操作需要）")
                    })
                    put("engine", JSONObject().apply {
                        put("type", "string")
                        put("description", "推理引擎（MNN、LLAMA_CPP、LITERT、AUTO）")
                    })
                    put("input", JSONObject().apply {
                        put("type", "string")
                        put("description", "推理输入文本（inference操作需要）")
                    })
                    put("max_tokens", JSONObject().apply {
                        put("type", "integer")
                        put("description", "最大生成token数")
                    })
                    put("temperature", JSONObject().apply {
                        put("type", "number")
                        put("description", "生成温度")
                    })
                })
                put("required", JSONArray().apply { put("action") })
            }.toString()
        )
    }
    
    /**
     * 执行工具操作
     */
    suspend fun execute(args: JSONObject): QuroToolResult {
        return withContext(Dispatchers.IO) {
            try {
                val action = args.optString("action", "list")
                
                when (action) {
                    "list" -> listModels()
                    "load" -> loadModel(args)
                    "unload" -> unloadModel(args)
                    "inference" -> performInference(args)
                    "status" -> getModelStatus(args)
                    "info" -> getSystemInfo()
                    "cleanup" -> cleanupModels()
                    else -> QuroToolResult.Error("未知操作: $action")
                }
            } catch (e: Exception) {
                QuroToolResult.Error("执行操作失败: ${e.message}")
            }
        }
    }
    
    /**
     * 列出可用模型
     */
    private fun listModels(): QuroToolResult {
        val models = modelManager.getAvailableModels()
        
        if (models.isEmpty()) {
            return QuroToolResult.Success("没有可用的本地模型。请先下载模型文件到 local_models 目录。")
        }
        
        val result = StringBuilder("可用本地模型:\n")
        models.forEachIndexed { index, model ->
            val status = modelManager.getModelStatus(model.id)
            result.append("${index + 1}. ${model.name}\n")
            result.append("   ID: ${model.id}\n")
            result.append("   引擎: ${model.engine.name}\n")
            result.append("   大小: ${model.sizeMB}MB\n")
            result.append("   状态: ${if (status.isLoaded) "已加载" else "未加载"}\n")
            if (model.description.isNotBlank()) {
                result.append("   描述: ${model.description}\n")
            }
            result.append("\n")
        }
        
        return QuroToolResult.Success(result.toString())
    }
    
    /**
     * 加载模型
     */
    private suspend fun loadModel(args: JSONObject): QuroToolResult {
        val modelId = args.optString("model_id", "")
        if (modelId.isBlank()) {
            return QuroToolResult.Error("模型ID不能为空")
        }
        
        val engineStr = args.optString("engine", "AUTO")
        val engine = try {
            ModelEngine.valueOf(engineStr.uppercase())
        } catch (e: IllegalArgumentException) {
            ModelEngine.AUTO
        }
        
        val result = modelManager.loadModel(modelId, engine)
        
        return when (result) {
            is com.ai.assistance.quro.core.model.ModelLoadResult.Success -> {
                QuroToolResult.Success(
                    "模型加载成功!\n" +
                    "模型ID: ${result.modelId}\n" +
                    "引擎: ${result.engine.name}\n" +
                    "加载时间: ${result.loadTimeMs}ms\n" +
                    "内存使用: ${result.memoryUsedMB}MB"
                )
            }
            is com.ai.assistance.quro.core.model.ModelLoadResult.Error -> {
                QuroToolResult.Error(result.message)
            }
        }
    }
    
    /**
     * 卸载模型
     */
    private fun unloadModel(args: JSONObject): QuroToolResult {
        val modelId = args.optString("model_id", "")
        if (modelId.isBlank()) {
            return QuroToolResult.Error("模型ID不能为空")
        }
        
        val success = modelManager.unloadModel(modelId)
        
        return if (success) {
            QuroToolResult.Success("模型 '$modelId' 卸载成功")
        } else {
            QuroToolResult.Error("模型卸载失败，模型可能未加载或ID无效")
        }
    }
    
    /**
     * 执行推理
     */
    private suspend fun performInference(args: JSONObject): QuroToolResult {
        val modelId = args.optString("model_id", "")
        if (modelId.isBlank()) {
            return QuroToolResult.Error("模型ID不能为空")
        }
        
        val input = args.optString("input", "")
        if (input.isBlank()) {
            return QuroToolResult.Error("推理输入不能为空")
        }
        
        val parameters = InferenceParameters(
            temperature = args.optDouble("temperature", 0.7).toFloat(),
            maxTokens = args.optInt("max_tokens", 1024)
        )
        
        val result = modelManager.inference(modelId, input, parameters)
        
        return when (result) {
            is com.ai.assistance.quro.core.model.InferenceResult.Success -> {
                QuroToolResult.Success(
                    "推理结果:\n" +
                    "输出: ${result.output}\n" +
                    "推理时间: ${result.inferenceTimeMs}ms\n" +
                    "速度: ${result.tokensPerSecond} tokens/s"
                )
            }
            is com.ai.assistance.quro.core.model.InferenceResult.Error -> {
                QuroToolResult.Error(result.message)
            }
        }
    }
    
    /**
     * 获取模型状态
     */
    private fun getModelStatus(args: JSONObject): QuroToolResult {
        val modelId = args.optString("model_id", "")
        if (modelId.isBlank()) {
            // 显示所有模型状态
            val models = modelManager.getAvailableModels()
            val result = StringBuilder("模型状态概览:\n")
            
            models.forEach { model ->
                val status = modelManager.getModelStatus(model.id)
                result.append("- ${model.name}: ${if (status.isLoaded) "已加载" else "未加载"}\n")
                if (status.isLoaded) {
                    result.append("  引擎: ${status.engine?.name}\n")
                    result.append("  内存: ${status.memoryUsedMB}MB\n")
                }
            }
            
            result.append("\n总内存使用: ${modelManager.getTotalMemoryUsedMB()}MB")
            result.append("\n可用内存: ${modelManager.getAvailableMemoryMB()}MB")
            
            return QuroToolResult.Success(result.toString())
        } else {
            // 显示指定模型状态
            val status = modelManager.getModelStatus(modelId)
            val result = StringBuilder("模型 '$modelId' 状态:\n")
            result.append("已加载: ${status.isLoaded}\n")
            if (status.isLoaded) {
                result.append("引擎: ${status.engine?.name}\n")
                result.append("内存使用: ${status.memoryUsedMB}MB\n")
                result.append("加载时间: ${status.loadedAt}\n")
            }
            
            return QuroToolResult.Success(result.toString())
        }
    }
    
    /**
     * 获取系统信息
     */
    private fun getSystemInfo(): QuroToolResult {
        val runtime = Runtime.getRuntime()
        val result = StringBuilder("本地模型系统信息:\n")
        result.append("可用处理器: ${runtime.availableProcessors()} 核\n")
        result.append("最大内存: ${runtime.maxMemory() / 1024 / 1024}MB\n")
        result.append("已用内存: ${(runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024}MB\n")
        result.append("模型内存使用: ${modelManager.getTotalMemoryUsedMB()}MB\n")
        result.append("可用内存: ${modelManager.getAvailableMemoryMB()}MB\n")
        
        // 支持的引擎
        result.append("\n支持的推理引擎:\n")
        result.append("- MNN: 移动端神经网络推理引擎\n")
        result.append("- llama.cpp: CPU/GPU 推理引擎\n")
        result.append("- LiteRT: TensorFlow Lite 推理引擎\n")
        
        return QuroToolResult.Success(result.toString())
    }
    
    /**
     * 清理所有模型
     */
    private fun cleanupModels(): QuroToolResult {
        modelManager.cleanup()
        return QuroToolResult.Success("所有模型已清理，内存已释放")
    }
}
