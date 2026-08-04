package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 端侧 ASR 模型「镜像源 + 部署状态」持久化。
 *
 * 支持：
 *  - 多个内置镜像源（BUILTIN_MIRRORS，可在此扩展）
 *  - 用户自定义镜像（可增删，存于本文件 prefs）
 *  - 单次自定义链接（自由粘贴任意下载地址）
 *  - 多模型部署记忆：每个模型按稳定 key 独立保存「目录 / 名称 / 类型 / 状态」，
 *    解决「部署好的模型返回重进后又变成未下载 / 切换其他模型后部分模型丢失」的问题。
 *  - 当前选中模型持久化：重进设置页时恢复用户上次选择的模型，避免每次回到默认项。
 */
object QuroOnDeviceModelPrefs {
    private const val PREFS = "quro_ondevice_asr"
    private const val KEY_SELECTED_URL = "selected_url"
    private const val KEY_CUSTOM_LINK = "custom_link"
    private const val KEY_CUSTOM_MIRRORS = "custom_mirrors"

    // ── 多模型部署记忆 ──
    private const val KEY_DEPLOYED_MAP = "deployed_map"
    private const val KEY_ACTIVE_KEY = "active_deployed_key"   // 引擎实际加载的模型 key
    // ── 当前选中模型（设置页重进恢复） ──
    private const val KEY_SEL_SPEC = "stt_sel_spec_id"
    private const val KEY_SEL_CUSTOM = "stt_sel_custom"
    private const val KEY_SEL_TYPE = "stt_sel_type"

    const val STATUS_NONE = "none"
    const val STATUS_DOWNLOADING = "downloading"
    const val STATUS_DEPLOYED = "deployed"
    const val STATUS_ERROR = "error"

    /** 单条部署记录。 */
    data class DeployedEntry(
        val dir: String,
        val name: String,
        val type: String,
        val status: String,
    )

    /** 单个镜像源。 */
    data class ModelMirror(val name: String, val url: String, val builtIn: Boolean = true)

    /**
     * 内置镜像源。
     *
     * ⚠️ 历史坑：原本内置 Sherpa-NCNN **SenseVoice** 模型（中英日韩粤，206/417MB）。
     * 但本构建随包的 libsherpa-ncnn-jni.so 仅导出流式 transducer 符号（SherpaNcnn_*），
     * 不含 OfflineRecognizer / SenseVoice 所需的 OfflineStream 符号；用户下载后调用
     * OfflineRecognizer.newFromFile() 必抛 UnsatisfiedLinkError，端侧识别永远起不来。
     *
     * 故此处不再内置任何「下载了也加载不了」的镜像。端侧识别统一走流式 zipformer
     * （见 QuroAsrModels 内置目录，模型自带下载逻辑）。用户仍可用「自定义链接」填入
     * 可加载的流式 transducer 模型（encoder/decoder/joiner .ncnn + tokens.txt）。
     */
    val BUILTIN_MIRRORS: List<ModelMirror> = emptyList()

    /** 自定义链接的占位标识（下拉里选中它进入自由输入模式）。 */
    const val CUSTOM_ENTRY = "__custom__"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── 模型稳定 key ──
    /** 内置模型用 spec.id；自定义模型用 custom::<url>，保证跨会话稳定可查。 */
    fun deployedKeyFor(modelId: String, downloadUrl: String): String =
        if (modelId.startsWith("custom-")) "custom::$downloadUrl" else modelId

    // ── 部署记录 map 读写 ──
    private fun readDeployedMap(ctx: Context): MutableMap<String, DeployedEntry> {
        val raw = prefs(ctx).getString(KEY_DEPLOYED_MAP, null) ?: return mutableMapOf()
        return try {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, DeployedEntry>()
            obj.keys().forEach { k ->
                val e = obj.getJSONObject(k)
                map[k] = DeployedEntry(
                    e.getString("dir"),
                    e.getString("name"),
                    e.getString("type"),
                    e.getString("status"),
                )
            }
            map
        } catch (_: Throwable) { mutableMapOf() }
    }

    private fun writeDeployedMap(ctx: Context, map: Map<String, DeployedEntry>) {
        val obj = JSONObject()
        map.forEach { (k, e) ->
            obj.put(k, JSONObject().apply {
                put("dir", e.dir)
                put("name", e.name)
                put("type", e.type)
                put("status", e.status)
            })
        }
        prefs(ctx).edit().putString(KEY_DEPLOYED_MAP, obj.toString()).apply()
    }

    /** 取某模型的部署记录（无则返回 null）。 */
    fun getDeployedEntry(ctx: Context, key: String): DeployedEntry? = readDeployedMap(ctx)[key]

    /** 全部部署记录（用于下拉里标注「已部署」）。 */
    fun allDeployedEntries(ctx: Context): Map<String, DeployedEntry> = readDeployedMap(ctx)

    /** 写入/更新某模型部署记录；状态为已部署时同步设为当前激活模型。 */
    fun putDeployedEntry(ctx: Context, key: String, entry: DeployedEntry) {
        val map = readDeployedMap(ctx)
        map[key] = entry
        writeDeployedMap(ctx, map)
        if (entry.status == STATUS_DEPLOYED) setActiveKey(ctx, key)
    }

    /** 更新某模型部署状态；状态为已部署时同步设为当前激活模型。 */
    fun setEntryStatus(ctx: Context, key: String, status: String) {
        val map = readDeployedMap(ctx)
        val e = map[key] ?: DeployedEntry("", "", "UNKNOWN", status)
        map[key] = e.copy(status = status)
        writeDeployedMap(ctx, map)
        if (status == STATUS_DEPLOYED) setActiveKey(ctx, key)
    }

    /** 清除某模型部署记录；若恰为激活模型则一并清空激活项。 */
    fun clearEntry(ctx: Context, key: String) {
        val map = readDeployedMap(ctx)
        map.remove(key)
        writeDeployedMap(ctx, map)
        if (getActiveKey(ctx) == key) {
            prefs(ctx).edit().remove(KEY_ACTIVE_KEY).apply()
        }
    }

    fun getActiveKey(ctx: Context): String? {
        val k = prefs(ctx).getString(KEY_ACTIVE_KEY, null)
        return if (k.isNullOrEmpty()) null else k
    }
    fun setActiveKey(ctx: Context, key: String) =
        prefs(ctx).edit().putString(KEY_ACTIVE_KEY, key).apply()

    // ── 当前选中模型（设置页重进恢复，避免部署好的模型又变未下载） ──
    fun getSelectedSpecId(ctx: Context): String {
        val s = prefs(ctx).getString(KEY_SEL_SPEC, null)
        return if (s.isNullOrEmpty()) "" else s
    }
    fun setSelectedSpecId(ctx: Context, id: String) =
        prefs(ctx).edit().putString(KEY_SEL_SPEC, id).apply()

    fun getCustomMode(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SEL_CUSTOM, false)
    fun setCustomMode(ctx: Context, b: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SEL_CUSTOM, b).apply()

    fun getCustomType(ctx: Context): String = prefs(ctx).getString(KEY_SEL_TYPE, "") ?: ""
    fun setCustomType(ctx: Context, t: String) =
        prefs(ctx).edit().putString(KEY_SEL_TYPE, t).apply()

    // ── 兼容层：引擎（ensureLoaded / getDeployedModelFiles）仍按「当前激活模型」读单条 ──
    fun getDeployedDir(ctx: Context): String? =
        getActiveKey(ctx)?.let { getDeployedEntry(ctx, it)?.dir }
    fun getDeployedName(ctx: Context): String? =
        getActiveKey(ctx)?.let { getDeployedEntry(ctx, it)?.name }
    fun getDeployedType(ctx: Context): String? {
        val t = getActiveKey(ctx)?.let { getDeployedEntry(ctx, it)?.type } ?: return null
        return if (t.isEmpty() || t == "UNKNOWN") null else t
    }
    fun getStatus(ctx: Context): String =
        getActiveKey(ctx)?.let { getDeployedEntry(ctx, it)?.status } ?: STATUS_NONE
    fun setStatus(ctx: Context, status: String) {
        val k = getActiveKey(ctx) ?: return
        setEntryStatus(ctx, k, status)
    }
    fun setDeployed(ctx: Context, name: String, dir: String, type: String) {
        val k = getActiveKey(ctx) ?: return
        putDeployedEntry(ctx, k, DeployedEntry(dir, name, type, STATUS_DEPLOYED))
    }
    fun clearDeploy(ctx: Context) {
        val k = getActiveKey(ctx) ?: return
        clearEntry(ctx, k)
    }

    // ── 选中的下载地址 ──
    fun getSelectedUrl(ctx: Context): String {
        val s = prefs(ctx).getString(KEY_SELECTED_URL, null)
        return if (s.isNullOrEmpty()) BUILTIN_MIRRORS.first().url else s
    }
    fun setSelectedUrl(ctx: Context, url: String) =
        prefs(ctx).edit().putString(KEY_SELECTED_URL, url).apply()

    // ── 单次自定义链接 ──
    fun getCustomLink(ctx: Context): String =
        prefs(ctx).getString(KEY_CUSTOM_LINK, "") ?: ""

    fun setCustomLink(ctx: Context, link: String) =
        prefs(ctx).edit().putString(KEY_CUSTOM_LINK, link).apply()

    // ── 用户自定义镜像（增删） ──
    fun getCustomMirrors(ctx: Context): List<ModelMirror> {
        val raw = prefs(ctx).getString(KEY_CUSTOM_MIRRORS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<ModelMirror>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(ModelMirror(o.getString("name"), o.getString("url"), false))
            }
            list
        } catch (_: Throwable) { emptyList() }
    }

    fun addCustomMirror(ctx: Context, name: String, url: String) {
        if (url.isBlank()) return
        val list = getCustomMirrors(ctx).toMutableList()
        if (list.any { it.url == url }) return
        list.add(ModelMirror(name.ifBlank { url }, url, false))
        saveCustomMirrors(ctx, list)
    }

    fun removeCustomMirror(ctx: Context, url: String) {
        val list = getCustomMirrors(ctx).filter { it.url != url }
        saveCustomMirrors(ctx, list)
    }

    private fun saveCustomMirrors(ctx: Context, list: List<ModelMirror>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(JSONObject().apply { put("name", m.name); put("url", m.url) })
        }
        prefs(ctx).edit().putString(KEY_CUSTOM_MIRRORS, arr.toString()).apply()
    }

    /** 当前完整镜像列表（内置 + 自定义）。 */
    fun allMirrors(ctx: Context): List<ModelMirror> =
        BUILTIN_MIRRORS + getCustomMirrors(ctx)
}
