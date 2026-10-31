package com.ai.assistance.quro.core.mika.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 一次变更;[undoToken] 交给 [MemoryStore.undo] 可恢复到变更之前。 */
sealed interface Change {
    val undoToken: String

    data class Added(val item: MemoryItem, override val undoToken: String) : Change
    data class Updated(val item: MemoryItem, val before: MemoryItem, override val undoToken: String) : Change
    data class Forgot(val before: MemoryItem, override val undoToken: String) : Change
}

/** 整份读写;加密落盘由 app 层实现。 */
interface MemoryPersistence {
    fun load(): List<MemoryItem>
    fun save(items: List<MemoryItem>)
}

/** [code]:`sensitive`|`too_long`|`memory_full`|`not_found`|`unavailable`(记忆文件暂时读不出)。 */
class MemoryRejected(val code: String) : Exception(code)

/**
 * 记忆的唯一写入口。所有写方法共用一把锁:并行任务的写入串行化。
 * 写入前过 [SensitiveFilter];每次变更整份落盘;撤销令牌只在内存里保留最近 [UNDO_KEEP] 个。
 */
class MemoryStore(
    private val persistence: MemoryPersistence,
    val clock: () -> Long,
    private val ids: () -> String,
    val limit: Int = 500,
) {
    private val lock = Any()
    // 读不出(密钥库一时不可用)时先当空的用,但不许写:写了就会用空表盖掉真正的记忆文件
    @Volatile private var loaded = false
    private val _items = MutableStateFlow(tryLoad() ?: emptyList())
    val items: StateFlow<List<MemoryItem>> = _items.asStateFlow()

    /** 记忆文件这次读到了没有;false 时写入一律拒绝(`unavailable`),[reloadIfNeeded] 下次再试。 */
    val available: Boolean get() = loaded

    /** 之前没读出来就再读一次(每个任务开始、每次写入前调用);读到返回 true。 */
    fun reloadIfNeeded(): Boolean = synchronized(lock) {
        if (loaded) return true
        val got = tryLoad() ?: return false
        _items.value = got
        true
    }

    private fun tryLoad(): List<MemoryItem>? = try {
        persistence.load().also { loaded = true }
    } catch (e: Exception) {
        null
    }

    // 插入顺序即新旧顺序,超出时丢最旧的
    private val undos = LinkedHashMap<String, Change>()

    fun add(kind: MemoryKind, content: String, keywords: List<String>, source: MemorySource): Result<Change.Added> = synchronized(lock) {
        if (!reloadIfNeeded()) return Result.failure(MemoryRejected("unavailable"))
        val text = clean(content)
        val kws = cleanKeywords(keywords)
        check(text, kws, source)?.let { return Result.failure(it) }
        if (_items.value.size >= limit) return Result.failure(MemoryRejected("memory_full"))
        val now = clock()
        val item = MemoryItem(
            id = ids(), kind = kind, content = text, keywords = kws, source = source,
            status = if (kind == MemoryKind.procedure) MemoryStatus.pending else MemoryStatus.active,
            createdAt = now, updatedAt = now,
        )
        commit(_items.value + item)
        Result.success(remember { Change.Added(item, it) })
    }

    /**
     * [keywords]/[source] 为 null 时沿用原值。做法被新的非手写来源(模型提取、memory_save)改动后要重新批准;
     * 用户在设置里改(manual)或不带来源的改动保持原状态。
     */
    fun update(id: String, content: String, keywords: List<String>?, source: MemorySource?): Result<Change.Updated> = synchronized(lock) {
        if (!reloadIfNeeded()) return Result.failure(MemoryRejected("unavailable"))
        val before = _items.value.find { it.id == id } ?: return Result.failure(MemoryRejected("not_found"))
        val text = clean(content)
        val kws = keywords?.let(::cleanKeywords) ?: before.keywords
        val src = source ?: before.source
        check(text, kws, src)?.let { return Result.failure(it) }
        val reapprove = before.kind == MemoryKind.procedure && source != null && source.type != "manual"
        val item = before.copy(
            content = text, keywords = kws, source = src, updatedAt = clock(),
            replaces = if (text != before.content) before.content else before.replaces,
            status = if (reapprove) MemoryStatus.pending else before.status,
        )
        commit(_items.value.map { if (it.id == id) item else it })
        Result.success(remember { Change.Updated(item, before, it) })
    }

    fun forget(id: String): Result<Change.Forgot> = synchronized(lock) {
        if (!reloadIfNeeded()) return Result.failure(MemoryRejected("unavailable"))
        val before = _items.value.find { it.id == id } ?: return Result.failure(MemoryRejected("not_found"))
        commit(_items.value.filter { it.id != id })
        Result.success(remember { Change.Forgot(before, it) })
    }

    /**
     * 令牌未知、过期或已用过 → false。更新的撤销只在条目仍是这次改后的样子时才做(之后又被改过/忘掉 → false),
     * 免得把后来的改动一并抹掉。落盘失败时抛出,令牌保留可重试。
     */
    fun undo(token: String): Boolean = synchronized(lock) {
        val change = undos[token] ?: return false
        val cur = _items.value
        val next = when (change) {
            is Change.Added -> cur.filter { it.id != change.item.id }
            is Change.Updated -> {
                // 只看这次改动的字段;之后的使用计数、置顶不算「又被改过」,恢复时也保留
                val now = cur.find { it.id == change.item.id }
                if (now == null || now.content != change.item.content || now.keywords != change.item.keywords || now.source != change.item.source) return false
                val restored = change.before.copy(useCount = now.useCount, lastUsedAt = now.lastUsedAt, pinned = now.pinned)
                cur.map { if (it.id == restored.id) restored else it }
            }
            is Change.Forgot -> cur.filter { it.id != change.before.id } + change.before
        }
        commit(next)
        undos.remove(token)
        true
    }

    fun approve(id: String): Boolean = synchronized(lock) {
        if (!loaded) return false
        val cur = _items.value
        if (cur.none { it.id == id && it.status == MemoryStatus.pending }) return false
        commit(cur.map { if (it.id == id) it.copy(status = MemoryStatus.active) else it })
        true
    }

    fun setPinned(id: String, pinned: Boolean): Unit = synchronized(lock) {
        if (!loaded) return
        val cur = _items.value
        if (cur.none { it.id == id }) return
        commit(cur.map { if (it.id == id) it.copy(pinned = pinned) else it })
    }

    fun markUsed(ids: List<String>): Unit = synchronized(lock) {
        if (ids.isEmpty() || !loaded) return
        val set = ids.toSet()
        val now = clock()
        commit(_items.value.map { if (it.id in set) it.copy(useCount = it.useCount + 1, lastUsedAt = now) else it })
    }

    /** 用户要全部清除:读不出的原文件也一并用空表覆盖,这正是用户的意思。 */
    fun clearAll(): Unit = synchronized(lock) {
        undos.clear()
        loaded = true
        commit(emptyList())
    }

    private fun check(content: String, keywords: List<String>, source: MemorySource): MemoryRejected? {
        if (content.isEmpty() || content.length > MAX_CONTENT) return MemoryRejected("too_long")
        val texts = listOf(content) + keywords + listOfNotNull(source.quote)
        if (texts.any { SensitiveFilter.hit(it) != null }) return MemoryRejected("sensitive")
        return null
    }

    // 换行、连续空白压成一个空格:记忆块按行编号,内容里不能有换行
    private fun clean(content: String) = WHITESPACE.replace(content.trim(), " ")

    private fun cleanKeywords(keywords: List<String>) =
        keywords.map(::clean).filter { it.isNotEmpty() }.distinct().take(MAX_KEYWORDS)

    private fun commit(next: List<MemoryItem>) {
        // 兜底:任何写入都不能在没读到原文件时发生
        check(loaded) { "memory not loaded" }
        persistence.save(next)
        _items.value = next
    }

    private fun <C : Change> remember(make: (String) -> C): C {
        val change = make(ids())
        undos[change.undoToken] = change
        while (undos.size > UNDO_KEEP) undos.remove(undos.keys.first())
        return change
    }

    companion object {
        const val MAX_CONTENT = 200
        const val MAX_KEYWORDS = 8
        const val UNDO_KEEP = 50
        private val WHITESPACE = Regex("\\s+")
    }
}
