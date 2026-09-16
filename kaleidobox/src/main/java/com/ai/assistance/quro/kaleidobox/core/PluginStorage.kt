package com.ai.assistance.quro.kaleidobox.core

import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.util.Json
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 插件 App 级持久化 KV 存储。
 *
 * 之前运行时的包私有 KV 是纯内存 [ConcurrentHashMap]——进程一死、插件所有数据全丢，
 * 这不是"App"该有的行为。这里改为**按包落盘**的 JSON 文件
 * `<rootDir>/<pkgId>/kv.json`，每次写入同步刷盘，重启后数据还在。
 *
 * 这与宿主已有的 `data.kv` 能力（SharedPreferences）互补：本类服务运行时的内部 kvGet/kvSet，
 * 让"插件 = App"在存储语义上成立。
 */
class PluginStorage(private val rootDir: File) {

    private val cache = ConcurrentHashMap<String, PersistentMap>()

    fun store(pkgId: String): MutableMap<String, KValue> =
        cache.getOrPut(pkgId) {
            val f = fileFor(pkgId)
            val initial = if (f.exists()) {
                runCatching {
                    (Json.toK(Json.parse(f.readText())) as? KValue.Obj)?.value ?: emptyMap()
                }.getOrDefault(emptyMap())
            } else {
                emptyMap()
            }
            PersistentMap(f, initial)
        }

    private fun fileFor(pkgId: String) = File(File(rootDir, sanitize(pkgId)), "kv.json")

    /** 包 id 可能含点/斜杠，转成安全目录名避免穿越。 */
    private fun sanitize(pkgId: String) = pkgId.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    /** 落盘 MutableMap：所有写操作结束后同步刷新 JSON。 */
    private class PersistentMap(
        private val file: File,
        initial: Map<String, KValue>,
    ) : MutableMap<String, KValue> {

        private val backing = LinkedHashMap(initial)
        private val lock = Any()

        private fun flush() = synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(Json.write(Json.fromK(KValue.Obj(backing.toMap()))))
            }
        }

        override val size: Int get() = backing.size
        override fun isEmpty(): Boolean = backing.isEmpty()
        override fun containsKey(key: String): Boolean = backing.containsKey(key)
        override fun containsValue(value: KValue): Boolean = backing.containsValue(value)
        override fun get(key: String): KValue? = backing[key]
        override val keys: MutableSet<String> get() = backing.keys
        override val values: MutableCollection<KValue> get() = backing.values
        override val entries: MutableSet<MutableMap.MutableEntry<String, KValue>> get() = backing.entries

        override fun put(key: String, value: KValue): KValue? =
            backing.put(key, value).also { flush() }

        override fun remove(key: String): KValue? = backing.remove(key).also { flush() }

        override fun putAll(from: Map<out String, KValue>) {
            backing.putAll(from)
            flush()
        }

        override fun clear() {
            backing.clear()
            flush()
        }
    }
}
