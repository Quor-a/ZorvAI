package com.ai.assistance.quro.core.mika.vision

import java.util.UUID

/** 0–999 归一化坐标,与 tap 同一坐标系。 */
data class Point(val x: Int, val y: Int)

/**
 * find_visually 找到的点:按 (ref, snapshotId) 登记,只留最近 [capacity] 个(先进先出),
 * 记下找到时那张截图的哈希,tap 时据此判断画面变没变。任何写操作后由调用方 [clear]。
 */
class LocateRegistry(private val capacity: Int = 20) {
    private class Entry(val point: Point, val hash: Long)

    private var next = 1
    private val entries = LinkedHashMap<String, Entry>()

    /** 返回 (ref, snapshotId),形如 ("@v3", "loc_…")。编号在一次任务里不复用。 */
    fun put(x: Int, y: Int, hash: Long): Pair<String, String> {
        val ref = "@v${next++}"
        val snapshotId = "loc_${UUID.randomUUID()}"
        entries[key(ref, snapshotId)] = Entry(Point(x, y), hash)
        while (entries.size > capacity) entries.remove(entries.keys.first())
        return ref to snapshotId
    }

    fun get(ref: String, snapshotId: String): Pair<Point, Long>? = entries[key(ref, snapshotId)]?.let { it.point to it.hash }

    fun clear() = entries.clear()

    /** [snapshotId] 是这里登记过、还留着的点的。 */
    fun has(snapshotId: String): Boolean = entries.keys.any { it.startsWith("$snapshotId|") }

    private fun key(ref: String, snapshotId: String) = "$snapshotId|$ref"
}
