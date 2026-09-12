package com.ai.assistance.quro.genui.app.store

import android.content.Context
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * GenUI 的本地存储：零第三方依赖，JSON 文件持久化（界面栈/键值）。
 *
 * 注意：模型服务（providers / routing）已不再落本地的 gen/config.json，
 * 而是经 QuroGenUiBridge 1:1 映射到 QuroAI 统一模型配置（SharedPreferences quro_model_config），
 * 使 GenUI 的「模型服务」页直接编辑全应用共用的那一份模型配置。
 *
 * - pages     : 界面栈（AI 生成过的全部界面，可回溯）
 *
 * 存储位置：context.filesDir/gen/ —— 应用私有目录，卸载即清除。
 */
class GenStore(context: Context) {

    private val dir = File(context.filesDir, "gen").apply { mkdirs() }
    private val pagesFile = File(dir, "pages.jsonl")

    /** 供需要同一私有目录的子系统（记忆库/灵魂/权限）取 Context */
    fun context(): android.content.Context = contextPrivate

    private val contextPrivate: android.content.Context = context

    // ---------- 配置（模型服务：委托 QuroAI 统一配置 quro_model_config） ----------
    // 集成模式：GenUI 不再维护自己的 config.json/providers，而是把 QuroAI 单一活动模型配置
    // 当作唯一数据源，经 QuroGenUiBridge 1:1 映射成一个固定 id="quro_main" 的供应商。

    @Synchronized
    fun loadProviders(): MutableList<ModelProvider> {
        val cfg = QuroModelConfigRepository(contextPrivate).load()
        return mutableListOf(QuroGenUiBridge.toModelProvider(cfg))
    }

    @Synchronized
    fun saveProviders(list: List<ModelProvider>) {
        val repo = QuroModelConfigRepository(contextPrivate)
        // 只认 quro_main 这一个供应商；没有则取第一个，避免误把空列表清空真机配置。
        val p = list.firstOrNull { it.id == QuroGenUiBridge.MAIN_ID } ?: list.firstOrNull() ?: return
        repo.save(QuroGenUiBridge.toModelConfig(p, repo.load()))
    }

    @Synchronized
    fun loadRouting(): FeatureRouting = FeatureRouting(
        mainProviderId = QuroGenUiBridge.MAIN_ID,
        fastProviderId = QuroGenUiBridge.MAIN_ID
    )

    @Synchronized
    fun saveRouting(r: FeatureRouting) {
        // 集成模式：只有一个供应商，主脑/快速都指向 quro_main，分派无需持久化。
    }

    // ---------- 界面栈 ----------

    @Synchronized
    fun loadPages(): MutableList<GeneratedPage> {
        if (!pagesFile.exists()) return mutableListOf()
        return runCatching {
            pagesFile.readLines().filter { it.isNotBlank() }
                .map { GeneratedPage.fromJson(JSONObject(it)) }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    @Synchronized
    fun appendPage(p: GeneratedPage) {
        pagesFile.appendText(p.toJson().toString() + "\n")
        // 上限 100 张纸，防止无限膨胀
        val lines = pagesFile.readLines().filter { it.isNotBlank() }
        if (lines.size > 100) pagesFile.writeText(lines.takeLast(100).joinToString("\n") + "\n")
    }

    @Synchronized
    fun clearPages() {
        pagesFile.delete()
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().take(8)
    }

    // ---------- MoBridge 键值存储（AI 界面的持久数据，独立于界面栈） ----------

    private val kvFile = File(dir, "bridge_kv.json")

    @Synchronized
    fun getPageValue(key: String): Any? = readKV().opt(key)

    @Synchronized
    fun putPageValue(key: String, value: Any?) {
        require(key.isNotBlank()) { "key 不能为空" }
        val o = readKV().put(key, value ?: JSONObject.NULL)
        kvFile.writeText(o.toString())
    }

    @Synchronized
    fun deletePageValue(key: String) {
        val o = readKV()
        o.remove(key)
        kvFile.writeText(o.toString())
    }

    @Synchronized
    fun pageKeys(): JSONArray {
        val arr = JSONArray()
        readKV().keys().forEach { arr.put(it) }
        return arr
    }

    private fun readKV(): JSONObject = runCatching { JSONObject(kvFile.readText()) }
        .getOrDefault(JSONObject())
}
