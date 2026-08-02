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

data class QuroLocalModel(
    val id: String = "",                 // UUID
    val type: QuroLocalModelType = QuroLocalModelType.MNN,
    val name: String = "",               // 展示名
    val path: String = "",               // MNN: 含 llm_config.json 的模型目录绝对路径（或目录内 .mnn 文件）；LLAMA_CPP: 文件夹绝对路径
    val modelNames: List<String> = emptyList(), // 该来源下可用的模型名（llama.cpp 扫描 .gguf 得到）
)

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
        }
    }
}
