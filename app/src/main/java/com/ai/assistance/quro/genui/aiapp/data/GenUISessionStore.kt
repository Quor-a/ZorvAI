package com.ai.assistance.quro.genui.aiapp.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * GenUI 会话持久化（历史对话 + 上次界面 + 历史作品 + 灵魂注入）
 *
 * 自包含模块：仅依赖 Context 与 JSON，可直接移植到任意 Android 项目（如 zorvAI）。
 * - 历史对话：LLM 多轮上下文（role/content），恢复时按当前灵魂重建 system 消息
 * - 上次界面：冷启动直接恢复最后一次生成的 GenUI 画布
 * - 历史作品：最近 20 次生成结果，可随时回看
 * - 灵魂注入：当前人格 id 持久化
 */
object GenUISessionStore {

    private const val PREFS = "genui_session"
    private const val KEY_HISTORY = "history_json"
    private const val KEY_LAST_GENUI = "last_genui"
    private const val KEY_LAST_REQUEST = "last_request"
    private const val KEY_WORKS = "works_json"
    private const val KEY_ASK_CHANNEL = "ask_channel_each_turn"
    private const val MAX_WORKS = 20

    /**
     * 一条历史作品。
     *
     * @property renderType 渲染类型（[RenderChannel.key]）：genui / a2ui / markdown / html。
     *   回放时必须靠它重新路由，否则 A2UI 的扁平表会被当成 GenUI DSL 解析。
     *   老数据（本次改造前存的）没有这个字段，读盘时按 payload 形态自动补全。
     */
    data class WorkItem(
        val title: String,
        val request: String,
        val json: String,
        val time: Long,
        val renderType: String = ""
    ) {
        /** 渲染通道对象（老数据自动推断，永不返回 null，界面上不会出现"未知"） */
        val channel: RenderChannel
            get() = RenderChannel.fromKey(renderType) ?: RenderChannel.infer(json)
    }

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 保存历史对话（跳过 system，恢复时由灵魂重建） */
    fun saveHistory(ctx: Context, history: List<Pair<String, String>>) {
        val arr = JSONArray()
        history.forEach { (role, content) ->
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        prefs(ctx).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun loadHistory(ctx: Context): List<Pair<String, String>> {
        val raw = prefs(ctx).getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                o.optString("role") to o.optString("content")
            }
        }.getOrDefault(emptyList())
    }

    fun saveLastPage(ctx: Context, genUI: String?, request: String) {
        prefs(ctx).edit().putString(KEY_LAST_GENUI, genUI ?: "").putString(KEY_LAST_REQUEST, request).apply()
    }

    fun loadLastGenUI(ctx: Context): String? =
        prefs(ctx).getString(KEY_LAST_GENUI, null)?.takeIf { it.isNotBlank() }

    fun loadLastRequest(ctx: Context): String =
        prefs(ctx).getString(KEY_LAST_REQUEST, "") ?: ""

    /** 保存历史作品（最新的在前面，最多 20 条） */
    fun saveWorks(ctx: Context, works: List<WorkItem>) {
        val arr = JSONArray()
        works.take(MAX_WORKS).forEach { w ->
            arr.put(
                JSONObject().put("title", w.title).put("request", w.request)
                    .put("json", w.json).put("time", w.time)
                    // 渲染类型显式落盘：缺了它回放只能靠猜
                    .put("renderType", w.renderType.ifBlank { w.channel.key })
            )
        }
        prefs(ctx).edit().putString(KEY_WORKS, arr.toString()).apply()
    }

    fun loadWorks(ctx: Context): List<WorkItem> {
        val raw = prefs(ctx).getString(KEY_WORKS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val payload = o.optString("json")
                // 老数据没有 renderType → 就地按 payload 形态补上，下次落盘即固化
                val rt = o.optString("renderType").ifBlank { RenderChannel.infer(payload).key }
                WorkItem(
                    o.optString("title"), o.optString("request"), payload,
                    o.optLong("time"), rt
                )
            }
        }.getOrDefault(emptyList())
    }

    fun clearAll(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    /**
     * 每轮生成前是否先弹一次「用哪条渲染通道」。
     *
     * 默认开：同一段需求走 GenUI / A2UI / Markdown / HTML，出来的东西完全不一样，
     * 不先问一句就只能靠模型猜，猜错用户看到的就是「乱七八糟」。
     * 关掉则回落到 [RenderChannel.DEFAULT]（GenUI SDK）。
     */
    fun askChannelEachTurn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ASK_CHANNEL, true)

    fun setAskChannelEachTurn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ASK_CHANNEL, on).apply()
    }
}
