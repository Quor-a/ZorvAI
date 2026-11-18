package com.ai.assistance.quro.core.hint

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 外部提示的轻量账本：已投递 id、已归档 id、待投递内容、待归档文件、最近事件。
 *
 * 落盘在应用私有 filesDir，采用临时文件 + 原子替换，避免进程被杀时留下半截 JSON。
 * 所有公开方法都在同一把锁内完成「读 → 改 → 写」，因为归档发生在生成协程、
 * 补投发生在主线程，两步式读写交错会丢掉待投递事件。
 */
object ExternalHintLedger {
    private const val FILE = "external_hints.json"
    private const val MAX_SEEN = 3000
    private const val MAX_ACKED = 3000
    private const val MAX_PENDING = 40
    private const val MAX_RECENT = 30
    private const val MAX_RECORDS = 3000

    data class Pending(val text: String, val ids: List<String>, val ts: Long)

    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** 仅在 [tx] / [query] 的锁内调用。 */
    private fun readNow(context: Context): JSONObject =
        runCatching { JSONObject(file(context).readText()) }.getOrDefault(JSONObject())

    /** 仅在 [tx] 的锁内调用。 */
    private fun writeNow(context: Context, obj: JSONObject) {
        runCatching {
            val target = file(context)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(target)) target.writeText(obj.toString())
        }
    }

    /** 读改写事务：整段串行，返回块的结果。 */
    private fun <T> tx(context: Context, block: (JSONObject) -> T): T = synchronized(lock) {
        val obj = readNow(context)
        val result = block(obj)
        writeNow(context, obj)
        result
    }

    /** 只读事务。 */
    private fun <T> query(context: Context, block: (JSONObject) -> T): T = synchronized(lock) {
        block(readNow(context))
    }

    private fun array(obj: JSONObject, key: String): JSONArray =
        obj.optJSONArray(key) ?: JSONArray().also { obj.put(key, it) }

    private fun trimTail(arr: JSONArray, max: Int) {
        while (arr.length() > max) arr.remove(0)
    }

    private fun idsOf(arr: JSONArray): List<String> =
        (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { s -> s.isNotEmpty() } }

    fun isSeen(context: Context, id: String): Boolean {
        if (id.isBlank()) return false
        return query(context) { obj -> id in idsOf(array(obj, "seen")) }
    }

    fun markSeen(context: Context, ids: List<String>) {
        if (ids.isEmpty()) return
        tx(context) { obj ->
            val arr = array(obj, "seen")
            ids.forEach { arr.put(it) }
            trimTail(arr, MAX_SEEN)
        }
    }

    /**
     * 已归档 id。会话历史里的提醒每轮都会被扫到，没有这层去重就会反复归档、
     * 反复清空待投递队列，并把同一事件重复计数。
     * @return 本次真正需要处理的 id（已处理过的会被过滤掉）。
     */
    fun filterUnacked(context: Context, ids: List<String>): List<String> {
        if (ids.isEmpty()) return emptyList()
        return tx(context) { obj ->
            // 与 note() 里的 "acked" 计数分开存放，否则数组会覆盖计数、统计永远归零。
            val arr = array(obj, "ackedIds")
            val known = idsOf(arr).toHashSet()
            val fresh = ids.distinct().filter { it.isNotBlank() && it !in known }
            fresh.forEach { arr.put(it) }
            trimTail(arr, MAX_ACKED)
            fresh
        }
    }

    fun putFile(context: Context, id: String, path: String) {
        if (id.isBlank() || path.isBlank()) return
        tx(context) { obj ->
            val files = obj.optJSONObject("files") ?: JSONObject().also { obj.put("files", it) }
            files.put(id, path)
            trimRecords(files)
        }
    }

    /** 该事件已投递但来源文件还没归档（AI 尚未处理完或进程被杀）。 */
    fun hasPendingFile(context: Context, id: String): Boolean = query(context) { obj ->
        obj.optJSONObject("files")?.optString(id, "").isNullOrBlank().not()
    }

    /** 取出并移除 id 对应的待归档文件路径（ack / 静音 / 丢弃时用）。 */
    fun takeFiles(context: Context, ids: List<String>): List<String> {
        if (ids.isEmpty()) return emptyList()
        return tx(context) { obj ->
            val files = obj.optJSONObject("files") ?: return@tx emptyList()
            ids.mapNotNull { id ->
                val p = files.optString(id, "").takeIf { it.isNotBlank() }
                files.remove(id)
                p
            }
        }
    }

    fun addPending(context: Context, pending: Pending) {
        tx(context) { obj ->
            val arr = array(obj, "pending")
            arr.put(JSONObject().apply {
                put("text", pending.text)
                put("ids", JSONArray(pending.ids))
                put("ts", pending.ts)
            })
            trimTail(arr, MAX_PENDING)
        }
    }

    fun takePending(context: Context): List<Pending> = tx(context) { obj ->
        val arr = array(obj, "pending")
        val out = mutableListOf<Pending>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val ids = idsOf(item.optJSONArray("ids") ?: JSONArray())
            val text = item.optString("text", "")
            if (text.isBlank()) continue
            out.add(Pending(text, ids, item.optLong("ts", System.currentTimeMillis())))
        }
        obj.put("pending", JSONArray())
        out
    }

    fun pendingCount(context: Context): Int = query(context) { obj -> array(obj, "pending").length() }

    /**
     * 从待投递记录里摘掉已处理的 id，保留混合批次中的其余部分。
     * 单事务完成，避免「取走 → 放回」两步之间被补投插队导致事件丢失。
     */
    fun dropIdsFromPending(context: Context, done: Set<String>) {
        if (done.isEmpty()) return
        tx(context) { obj ->
            val arr = array(obj, "pending")
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val left = idsOf(item.optJSONArray("ids") ?: JSONArray()).filterNot { it in done }
                if (left.isEmpty()) continue
                item.put("ids", JSONArray(left))
                kept.put(item)
            }
            obj.put("pending", kept)
        }
    }

    fun note(context: Context, id: String, source: String, state: String) {
        tx(context) { obj ->
            val arr = array(obj, "recent")
            arr.put(JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("id", id.take(80))
                put("source", source.take(60))
                put("state", state)
            })
            trimTail(arr, MAX_RECENT)
            obj.put("acked", obj.optInt("acked", 0) + if (state == "acked") 1 else 0)
        }
    }

    fun ackedCount(context: Context): Int = query(context) { obj -> obj.optInt("acked", 0) }

    /** 最近事件的可读列表，新事件在前。 */
    fun recentLines(context: Context): List<String> = query(context) { obj ->
        val arr = array(obj, "recent")
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val out = mutableListOf<String>()
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            val label = when (o.optString("state")) {
                "muted" -> "已静音"
                "disabled" -> "未启用"
                "duplicate" -> "重复丢弃"
                "queued" -> "已投递"
                "acked" -> "已处理"
                "retry" -> "重投"
                else -> o.optString("state")
            }
            out.add("${fmt.format(java.util.Date(o.optLong("ts")))}  ${o.optString("source")}  $label  ${o.optString("id").take(24)}")
        }
        out
    }

    /** files 是 JSONObject，没有 trimTail；按 key 数量做上限保护。 */
    private fun trimRecords(files: JSONObject) {
        val it = files.keys()
        val keys = mutableListOf<String>()
        while (it.hasNext()) keys.add(it.next())
        if (keys.size > MAX_RECORDS) {
            keys.take(keys.size - MAX_RECORDS).forEach { files.remove(it) }
        }
    }
}
