package com.ai.assistance.quro.core.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地离线模型管理：
 *
 * - MNN：用户添加一个**模型目录**（含 `llm_config.json` 与权重文件，通常是从模型发布包解压出的文件夹）。
 *   记录该目录的绝对路径即可被加载使用（若只选了目录内的 `.mnn` 文件，适配层会回退到其父目录）。
 * - llama.cpp：用户添加一个**文件夹**，从该文件夹扫描出 `.gguf` 模型文件，
 *   每个 .gguf 文件名即为一个可用模型。
 *
 * 本仓库仅负责「离线模型的登记与管理」（路径 / 名称 / 扫描到的模型名列表）。
 * 真正的本地推理执行由 [com.ai.assistance.quro.core.network.QuroLocalEngine] 负责，
 * 当前提供可插拔接口与占位实现，原生运行时（MNN / llama.cpp AAR）按需接入。
 *
 * 持久化：应用私有文件 `quro_local_models.json`。
 */
enum class QuroLocalModelType { MNN, LLAMA_CPP }

/**
 * 一个已登记的本地模型（含**运行参数**）。
 *
 * 运行参数一律「0 / 空字符串 = 自动」，未配置时由 [resolveThreads] 等方法给出与参考实现
 * （operit）一致的安全默认值。加这些字段的原因：此前引擎侧全部走硬编码默认值，
 * 用户无处调线程数 / 计算精度 / 后端类型 / 上下文长度，MNN 只能单核 CPU、
 * llama.cpp 只能 CPU 且窗口固定——这就是「模型配置没搞好」。
 */
data class QuroLocalModel(
    val id: String = "",                 // UUID
    val type: QuroLocalModelType = QuroLocalModelType.MNN,
    val name: String = "",               // 展示名
    val path: String = "",               // MNN: 含 llm_config.json 的模型目录绝对路径（或目录内 .mnn 文件）；LLAMA_CPP: 文件夹绝对路径
    val modelNames: List<String> = emptyList(), // 该来源下可用的模型名（llama.cpp 扫描 .gguf 得到）

    // ---- 通用运行参数 ----
    /** 推理线程数；0 = 自动（CPU 核数，夹在 2..8）。 */
    val threads: Int = 0,
    /** 上下文窗口 token 数；0 = 自动（llama 按 prompt 估算，MNN 由 llm_config.json 决定）。 */
    val contextSize: Int = 0,

    // ---- llama.cpp 专用 ----
    /** offload 到 GPU 的层数；0 = 纯 CPU（手机端绝大多数 GGUF 构建无 GPU 后端，保持 0 最稳）。 */
    val gpuLayers: Int = 0,
    /** 是否 mmap 权重。外部存储上的 GGUF 用 mmap 会卡死加载，默认 false。 */
    val useMmap: Boolean = false,
    /** 统一 KV 缓存（单序列推理省内存、加载更快）。默认 true。 */
    val kvUnified: Boolean = true,

    // ---- MNN 专用 ----
    /** 计算后端：""/cpu / opencl / opengl / vulkan。空 = cpu。 */
    val backend: String = "",
    /** 计算精度（用户口中的"计算类型"）：""/low / normal / high。空 = low（手机端最快）。 */
    val precision: String = "",
    /** 内存模式：""/low / normal。空 = 自动（GPU 后端用 normal，CPU 用 low）。 */
    val memoryMode: String = "",
) {
    /**
     * 线程数：
     * - 用户显式设了(>0)：照办（夹 1..16）。
     * - 自动(0)：CPU 核数 - 2，夹在 2..6，**至少留 2 核给系统/UI**。
     *   原因：本地推理是纯 CPU 密集型，若开满全部核（如 8 核开 8 线程）会把主线程饿死，
     *   表现为 UI 冻住 + AnrMonitor 误判 ANR（详见 2026-08-03 排查：realme RMX8899 上
     *   自动 8 线程 → 主线程抢不到时间片 → 健康 ping 延迟 → 假 ANR）。留核后体感卡顿与误报均消失。
     */
    fun resolveThreads(): Int =
        if (threads > 0) threads.coerceIn(1, 16)
        else (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

    /** MNN 后端类型字符串，空 → "cpu"。 */
    fun resolveBackend(): String = backend.ifBlank { "cpu" }

    /** MNN 计算精度，空 → "low"。 */
    fun resolvePrecision(): String = precision.ifBlank { "low" }

    /** MNN 内存模式，空 → GPU 后端 "normal"、CPU "low"（与 operit MNNProvider 一致）。 */
    fun resolveMemoryMode(): String = memoryMode.ifBlank {
        if (resolveBackend() == "cpu") "low" else "normal"
    }
}

class QuroLocalModelRepository(context: Context) {
    private val file = File(context.filesDir, "quro_local_models.json")

    fun loadAll(): List<QuroLocalModel> {
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrElse { return emptyList() }
        if (text.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<QuroLocalModel>()
        for (i in 0 until arr.length()) {
            runCatching { parse(arr.getJSONObject(i)) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    fun findById(id: String): QuroLocalModel? = loadAll().firstOrNull { it.id == id }

    fun upsert(m: QuroLocalModel) {
        val all = loadAll().toMutableList()
        val idx = all.indexOfFirst { it.id == m.id }
        if (idx >= 0) all[idx] = m else all.add(m)
        saveAll(all)
    }

    fun delete(id: String) {
        saveAll(loadAll().filter { it.id != id })
    }

    /** 扫描一个文件夹，返回其中所有 .gguf 文件名（不含扩展名作为模型名）。 */
    fun scanGguf(folder: File): List<String> {
        if (!folder.isDirectory) return emptyList()
        return folder.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            ?.map { it.name.removeSuffix(".gguf").removeSuffix(".GGUF") }
            ?: emptyList()
    }

    private fun saveAll(list: List<QuroLocalModel>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(serialize(it)) }
            file.writeText(arr.toString())
        }
    }

    private fun parse(o: JSONObject): QuroLocalModel {
        val type = when (o.optString("type", "MNN")) {
            "LLAMA_CPP" -> QuroLocalModelType.LLAMA_CPP
            else -> QuroLocalModelType.MNN
        }
        val namesArr = o.optJSONArray("modelNames")
        val names = if (namesArr != null) {
            (0 until namesArr.length()).map { namesArr.optString(it, "") }.filter { it.isNotBlank() }
        } else emptyList()
        return QuroLocalModel(
            id = o.optString("id", ""),
            type = type,
            name = o.optString("name", ""),
            path = o.optString("path", ""),
            modelNames = names,
            // 运行参数：老版本 JSON 无这些键，optXxx 会取默认值 → 向后兼容。
            // ⚠️ useMmap / kvUnified 的兜底值必须与 QuroLocalModel 的默认值一致
            //   （false / true），否则老记录读出来会退回"卡加载"的旧行为。
            threads = o.optInt("threads", 0),
            contextSize = o.optInt("contextSize", 0),
            gpuLayers = o.optInt("gpuLayers", 0),
            useMmap = o.optBoolean("useMmap", false),
            kvUnified = o.optBoolean("kvUnified", true),
            backend = o.optString("backend", ""),
            precision = o.optString("precision", ""),
            memoryMode = o.optString("memoryMode", ""),
        )
    }

    private fun serialize(m: QuroLocalModel): JSONObject {
        val namesArr = JSONArray()
        m.modelNames.forEach { namesArr.put(it) }
        return JSONObject().apply {
            put("id", m.id)
            put("type", m.type.name)
            put("name", m.name)
            put("path", m.path)
            put("modelNames", namesArr)
            put("threads", m.threads)
            put("contextSize", m.contextSize)
            put("gpuLayers", m.gpuLayers)
            put("useMmap", m.useMmap)
            put("kvUnified", m.kvUnified)
            put("backend", m.backend)
            put("precision", m.precision)
            put("memoryMode", m.memoryMode)
        }
    }
}
