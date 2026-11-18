package com.ai.assistance.quro.core.xposed

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * 小布图片请求捕获（净室实现）。
 *
 * 对应行为规格：小布一轮对话里图片有三条进入通道，每条只读「已确认的字符串字段」，
 * **不在 Hook 热路径读 URI / 读文件 / 编码图片**——重活全部交给 app 主进程的运行时。
 *
 * ## 三条捕获通道
 *
 * | 通道 | 触发点 | 说明 |
 * |---|---|---|
 * | `capturePayload` | `Nlp.Doc`（namespace=Nlp, name=Doc） | `getType()=="image"` 时读 `getImagePreProcessResult` + `getUrl` |
 * | `captureClientResult` | `AIChatViewBean.clientResult` type=104 | 12.9.6 多图先进 clientResult，不保证同时出现 Nlp.Doc；读 `getExtraWithType→getMImageList→getDocEntity→getData` |
 * | `captureText` | 文本 / JSON 字符串 | 带图片提示的文本或直接图片引用，JSON 解析延迟到后台线程 |
 *
 * ## 安全上限
 *
 * 图片输入结构不可信（厂商协议、用户粘贴都可能超限），所有通道都设硬上限：
 * 最多 4 张图、16 条输入、JSON 深度 4 / 节点 128、单条 9MB、URI 16KB。
 * 超限不抛异常，返回结构化 [Resolution.Failure]，由调用方降级。
 *
 * ## 与运行时的分工
 *
 * 本层只产出 [Reference]（引用字符串 + 来源），不解码图片。运行时收到引用后按类型
 * （`content://` / `file://` / `data:image` / `http(s)://` / 预处理 URL）解码为多模态消息。
 */
internal object QuroBreenoImages {

    /** 小布多图 clientResult 的 type 值。 */
    private const val BREENO_MULTI_IMAGE_TYPE = 104
    private const val MAX_IMAGES = 4
    private const val MAX_INPUTS = 16
    private const val MAX_CANDIDATES = 32
    private const val MAX_JSON_DEPTH = 4
    private const val MAX_JSON_NODES = 128
    private const val MAX_NESTED_IMAGE_ITEMS = 8
    private const val MAX_INPUT_CHARS = 9 * 1024 * 1024
    private const val MAX_URI_CHARS = 16 * 1024
    private const val MAX_LEADING_WHITESPACE = 256
    private const val PARCEL_STRING_BYTES_PER_CHAR = 2L

    private val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".heic", ".heif")

    /** 失败码：与运行时约定，用于结构化降级。 */
    enum class FailureCode(val value: String) {
        IMAGE_DATA_LIMIT_EXCEEDED("image_data_limit_exceeded"),
        IMAGE_COUNT_LIMIT_EXCEEDED("image_count_limit_exceeded"),
        IMAGE_INPUT_LIMIT_EXCEEDED("image_input_limit_exceeded"),
        IMAGE_REFERENCE_CACHE_LIMIT_EXCEEDED("image_reference_cache_limit_exceeded"),
        IMAGE_REFERENCE_UNREADABLE("image_reference_unreadable"),
    }

    sealed interface Resolution {
        /** 成功：图片引用列表，交运行时解码。 */
        data class Success(val references: List<Reference>) : Resolution

        /** 失败：结构化原因，调用方据此降级（不抛异常）。 */
        data class Failure(
            val code: FailureCode,
            val message: String,
            val imageCount: Int,
            val estimatedBytes: Long,
            val maxBytes: Long,
        ) : Resolution
    }

    /** 一条图片引用：值 + 来源，来源仅用于诊断与去重。 */
    data class Reference(val value: String, val source: String)

    /** 捕获快照：未解析的输入集合，可合并、可缓存。 */
    internal class Snapshot internal constructor(
        internal val inputs: List<Input>,
        internal val failure: Resolution.Failure? = null,
    ) {
        val inputCount: Int get() = inputs.size
        val isEmpty: Boolean get() = inputs.isEmpty() && failure == null

        internal val estimatedChars: Long
            get() = inputs.sumOf { it.value.length.toLong() + it.source.length.toLong() } +
                (failure?.message?.length?.toLong() ?: 0L)
    }

    internal class Input internal constructor(
        val value: String,
        val source: String,
        val imageHint: Boolean,
    )

    private data class Candidate(val value: String, val source: String)

    private class TraversalBudget {
        var nodes: Int = 0
            private set
        var exceeded: Boolean = false
            private set

        fun consume(): Boolean {
            if (nodes >= MAX_JSON_NODES) {
                exceeded = true
                return false
            }
            nodes++
            return true
        }

        fun markExceeded() {
            exceeded = true
        }
    }

    val Empty = Snapshot(emptyList())

    /**
     * CDM 图片引用按一次请求保存为一个条目，别名只作为索引。
     * 所有变更在同一把锁内完成，命中任一别名后会连同其他别名一起消费。
     */
    internal class SnapshotCache(
        private val maxEntries: Int,
        private val maxAliases: Int,
        private val maxEstimatedChars: Long,
        private val ttlMillis: Long,
        private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
    ) {
        init {
            require(maxEntries > 0)
            require(maxAliases > 0)
            require(maxEstimatedChars > 0)
            require(ttlMillis > 0)
        }

        enum class StoreResult { STORED, STORED_FAILURE, EMPTY, TOO_MANY_ALIASES, TOO_LARGE }

        data class Stats(val entries: Int, val aliases: Int, val estimatedChars: Long)

        private data class Entry(
            val id: Long,
            val aliases: Set<String>,
            val snapshot: Snapshot,
            val estimatedChars: Long,
            val expiresAtMillis: Long,
        )

        private val entries = LinkedHashMap<Long, Entry>()
        private val entryIdByAlias = HashMap<String, Long>()
        private var nextEntryId = 1L
        private var estimatedChars = 0L

        @Synchronized
        fun store(aliases: Collection<String>, snapshot: Snapshot): StoreResult {
            if (snapshot.isEmpty) return StoreResult.EMPTY
            val normalizedAliases = aliases.asSequence()
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
            if (normalizedAliases.isEmpty()) return StoreResult.EMPTY
            if (normalizedAliases.size > maxAliases) return StoreResult.TOO_MANY_ALIASES

            val aliasChars = normalizedAliases.sumOf { it.length.toLong() }
            var storedSnapshot = snapshot
            var entryChars = storedSnapshot.estimatedChars + aliasChars
            var storedAsFailure = false
            if (entryChars > maxEstimatedChars) {
                storedSnapshot = Snapshot(
                    inputs = emptyList(),
                    failure = Resolution.Failure(
                        code = FailureCode.IMAGE_REFERENCE_CACHE_LIMIT_EXCEEDED,
                        message = "图片引用数据过大，无法安全暂存，请重新选择图片或使用远程图片链接",
                        imageCount = snapshot.inputCount.coerceAtLeast(1),
                        estimatedBytes = estimatedBytesForChars(snapshot.estimatedChars),
                        maxBytes = estimatedBytesForChars(maxEstimatedChars),
                    ),
                )
                entryChars = storedSnapshot.estimatedChars + aliasChars
                if (entryChars > maxEstimatedChars) return StoreResult.TOO_LARGE
                storedAsFailure = true
            }

            val now = nowMillis()
            purgeExpired(now)
            normalizedAliases.mapNotNullTo(linkedSetOf()) { entryIdByAlias[it] }
                .forEach(::removeEntry)

            while (
                entries.size >= maxEntries ||
                entryIdByAlias.size + normalizedAliases.size > maxAliases ||
                estimatedChars + entryChars > maxEstimatedChars
            ) {
                val oldestId = entries.keys.firstOrNull() ?: break
                removeEntry(oldestId)
            }

            val entryId = nextEntryId++
            val entry = Entry(
                id = entryId,
                aliases = normalizedAliases.toSet(),
                snapshot = storedSnapshot,
                estimatedChars = entryChars,
                expiresAtMillis = expiresAt(now),
            )
            entries[entryId] = entry
            entry.aliases.forEach { alias -> entryIdByAlias[alias] = entryId }
            estimatedChars += entryChars
            return if (storedAsFailure) StoreResult.STORED_FAILURE else StoreResult.STORED
        }

        @Synchronized
        fun consume(aliases: Collection<String>): Snapshot {
            purgeExpired(nowMillis())
            val entryId = aliases.asSequence()
                .filter { it.isNotBlank() }
                .mapNotNull(entryIdByAlias::get)
                .firstOrNull()
                ?: return Empty
            return removeEntry(entryId)?.snapshot ?: Empty
        }

        @Synchronized
        fun stats(): Stats {
            purgeExpired(nowMillis())
            return Stats(entries.size, entryIdByAlias.size, estimatedChars)
        }

        private fun purgeExpired(now: Long) {
            entries.values.asSequence()
                .filter { entry -> now >= entry.expiresAtMillis }
                .map { it.id }
                .toList()
                .forEach(::removeEntry)
        }

        private fun removeEntry(entryId: Long): Entry? {
            val entry = entries.remove(entryId) ?: return null
            entry.aliases.forEach { alias -> entryIdByAlias.remove(alias, entryId) }
            estimatedChars -= entry.estimatedChars
            return entry
        }

        private fun expiresAt(now: Long): Long =
            if (now > Long.MAX_VALUE - ttlMillis) Long.MAX_VALUE else now + ttlMillis

        private fun estimatedBytesForChars(chars: Long): Long =
            if (chars > Long.MAX_VALUE / PARCEL_STRING_BYTES_PER_CHAR) {
                Long.MAX_VALUE
            } else {
                chars * PARCEL_STRING_BYTES_PER_CHAR
            }
    }

    // ---- 通道一：Nlp.Doc ----

    /**
     * Hook 热路径只读取已确认的图片字段并保存字符串，不读取 URI/文件，也不编码图片。
     */
    fun capturePayload(namespace: String?, name: String?, payload: Any?): Snapshot {
        if (payload == null || namespace != "Nlp" || name != "Doc") return Empty
        val type = QuroHookSupport.invokeNoArgs(payload, "getType") as? String
        if (!type.equals("image", ignoreCase = true)) return Empty
        val inputs = ArrayList<Input>(4)

        var failure = collectKnownImageItems(
            value = QuroHookSupport.invokeNoArgs(payload, "getImagePreProcessResult"),
            source = "message.nlp.doc.imagePreProcessResult",
            inputs = inputs,
        )
        if (failure == null) {
            failure = addInput(
                inputs = inputs,
                value = QuroHookSupport.invokeNoArgs(payload, "getUrl") as? String,
                source = "message.nlp.doc.url",
            )
        }
        return Snapshot(inputs.toList(), failure)
    }

    // ---- 通道二：type=104 多图 clientResult ----

    /**
     * 小布 12.9.6 的图片输入先进入 AIChatViewBean.clientResult(type=104)，
     * 不保证同时出现 Nlp.Doc。这里只读取已确认的多图结构，不遍历未知对象。
     */
    fun isMultiImageClientResult(clientResult: Any?): Boolean =
        clientResult != null &&
            (QuroHookSupport.invokeNoArgs(clientResult, "getType") as? Number)?.toInt() ==
            BREENO_MULTI_IMAGE_TYPE

    fun captureClientResult(clientResult: Any?): Snapshot {
        if (!isMultiImageClientResult(clientResult)) return Empty
        val inputs = ArrayList<Input>(4)
        val multiImageList = QuroHookSupport.invokeNoArgs(clientResult, "getExtraWithType")
        val failure = collectBreenoMultiImageItems(
            value = multiImageList?.let { QuroHookSupport.invokeNoArgs(it, "getMImageList") },
            inputs = inputs,
        )
        if (failure == null && inputs.isEmpty()) {
            val fallback = captureText(
                text = QuroHookSupport.invokeNoArgs(clientResult, "getExtra") as? String,
                source = "aichat.multiImage.extra",
            )
            return if (fallback.isEmpty) {
                Snapshot(inputs = emptyList(), failure = unreadableReferenceFailure(1))
            } else {
                fallback
            }
        }
        return Snapshot(inputs.toList(), failure)
    }

    // ---- 通道三：文本 / JSON ----

    /** 保存可能包含图片引用的文本，JSON 解析延迟到 Agent 后台线程。 */
    fun captureText(text: String?, source: String): Snapshot {
        if (text.isNullOrEmpty()) return Empty
        val imageHint = source.hasImageHint()
        if (!imageHint && !text.isPotentialImageInput()) return Empty
        if (text.length > MAX_INPUT_CHARS) {
            return Snapshot(
                inputs = emptyList(),
                failure = oversizedReferenceFailure(text.length, source),
            )
        }
        return Snapshot(listOf(Input(text, source, imageHint)))
    }

    /** 合并多个快照，超输入上限时返回结构化失败。 */
    fun merge(vararg snapshots: Snapshot): Snapshot {
        val totalInputs = snapshots.sumOf { it.inputs.size.toLong() }
        val failure = snapshots.firstNotNullOfOrNull(Snapshot::failure)
            ?: if (totalInputs > MAX_INPUTS) {
                inputLimitFailure(totalInputs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            } else {
                null
            }
        return Snapshot(
            inputs = snapshots.asSequence()
                .flatMap { it.inputs.asSequence() }
                .take(MAX_INPUTS)
                .toList(),
            failure = failure,
        )
    }

    /**
     * 解析快照为图片引用列表。**必须在后台线程调用**（JSON 解析与遍历在此发生）。
     *
     * 这里只做引用收集与去重，不解码图片——解码是运行时的职责。
     */
    fun resolve(snapshot: Snapshot): Resolution {
        snapshot.failure?.let { return it }
        if (snapshot.isEmpty) return Resolution.Success(emptyList())
        val candidates = linkedMapOf<String, Candidate>()
        val budget = TraversalBudget()
        snapshot.inputs.forEach { input ->
            collectFromString(
                text = input.value,
                source = input.source,
                candidates = candidates,
                imageHint = input.imageHint,
                depth = 0,
                budget = budget,
            )
        }
        if (budget.exceeded) {
            return inputLimitFailure(snapshot.inputCount)
        }
        if (candidates.size > MAX_IMAGES) {
            return Resolution.Failure(
                code = FailureCode.IMAGE_COUNT_LIMIT_EXCEEDED,
                message = "一次最多支持 $MAX_IMAGES 张图片，请减少图片数量后再试",
                imageCount = candidates.size,
                estimatedBytes = 0,
                maxBytes = 0,
            )
        }
        val references = candidates.values.map { it.value }
            .distinct()
            .map { Reference(it, "breeno.image") }
        return Resolution.Success(references)
    }

    private fun unreadableReferenceFailure(imageCount: Int): Resolution.Failure =
        Resolution.Failure(
            code = FailureCode.IMAGE_REFERENCE_UNREADABLE,
            message = "无法读取请求中的全部图片，请重新选择图片后再试",
            imageCount = imageCount,
            estimatedBytes = 0,
            maxBytes = 0,
        )

    private fun inputLimitFailure(inputCount: Int): Resolution.Failure =
        Resolution.Failure(
            code = FailureCode.IMAGE_INPUT_LIMIT_EXCEEDED,
            message = "图片输入结构超过安全上限，请减少图片数量或简化图片数据后再试",
            imageCount = inputCount.coerceAtLeast(1),
            estimatedBytes = 0,
            maxBytes = 0,
        )

    fun summary(references: List<Reference>): String =
        "count=${references.size}, chars=${references.sumOf { it.value.length }}"

    private fun collectKnownImageItems(
        value: Any?,
        source: String,
        inputs: MutableList<Input>,
    ): Resolution.Failure? {
        val items = when (value) {
            is Iterable<*> -> value.iterator()
            is Array<*> -> value.iterator()
            else -> return null
        }
        var index = 0
        while (items.hasNext() && index < MAX_NESTED_IMAGE_ITEMS) {
            val item = items.next()
            val failure = when (item) {
                is String -> addInput(inputs, item, "$source[$index]")
                null -> null
                else -> addInput(
                    inputs = inputs,
                    value = QuroHookSupport.invokeNoArgs(item, "getUrl") as? String,
                    source = "$source[$index].url",
                )
            }
            if (failure != null) return failure
            index++
        }
        return if (items.hasNext()) inputLimitFailure(index + 1) else null
    }

    private fun collectBreenoMultiImageItems(
        value: Any?,
        inputs: MutableList<Input>,
    ): Resolution.Failure? {
        val items = when (value) {
            is Iterable<*> -> value.iterator()
            is Array<*> -> value.iterator()
            else -> return null
        }
        var index = 0
        while (items.hasNext() && index < MAX_NESTED_IMAGE_ITEMS) {
            val item = items.next()
            if (item != null) {
                val docEntity = QuroHookSupport.invokeNoArgs(item, "getDocEntity")
                val uploadData = docEntity?.let { QuroHookSupport.invokeNoArgs(it, "getData") }
                val reference = firstReference(
                    firstCompletedPreProcessUrl(uploadData),
                    uploadData?.let { QuroHookSupport.invokeNoArgs(it, "getUrl") },
                    QuroHookSupport.invokeNoArgs(item, "getUri"),
                    QuroHookSupport.invokeNoArgs(item, "getOriginUri"),
                    docEntity?.let { QuroHookSupport.invokeNoArgs(it, "getOriginFilePath") },
                    docEntity?.let { QuroHookSupport.invokeNoArgs(it, "getFilePath") },
                    docEntity?.let { QuroHookSupport.invokeNoArgs(it, "getImageMediaUriForChatQueryMsg") },
                )
                val failure = addInput(
                    inputs = inputs,
                    value = reference,
                    source = "aichat.multiImage.items[$index]",
                )
                if (failure != null) return failure
            }
            index++
        }
        return if (items.hasNext()) inputLimitFailure(index + 1) else null
    }

    /**
     * 图片选择器的 originUri 可能只带给小布进程一次性读取权限。
     * 上传完成后的预处理 URL 是小布实际填入 Nlp.Doc 的引用，优先级应高于本地 Uri。
     */
    private fun firstCompletedPreProcessUrl(uploadData: Any?): String? {
        if (uploadData == null) return null
        val value = QuroHookSupport.invokeNoArgs(uploadData, "getImagePreProcessResult")
        val items = when (value) {
            is Iterable<*> -> value.asSequence()
            is Array<*> -> value.asSequence()
            else -> return null
        }
        return items
            .take(MAX_NESTED_IMAGE_ITEMS)
            .filterNotNull()
            .filter { item -> (QuroHookSupport.invokeNoArgs(item, "getCompleted") as? Boolean) != false }
            .mapNotNull { item -> QuroHookSupport.invokeNoArgs(item, "getUrl") as? String }
            .firstOrNull { it.isNotBlank() }
    }

    private fun firstReference(vararg values: Any?): String? =
        values.asSequence()
            .mapNotNull { value ->
                when (value) {
                    is String -> value
                    null -> null
                    else -> value.toString()
                }
            }
            .firstOrNull { it.isNotBlank() }

    private fun oversizedReferenceFailure(valueChars: Int, source: String): Resolution.Failure =
        Resolution.Failure(
            code = FailureCode.IMAGE_DATA_LIMIT_EXCEEDED,
            message = "图片输入数据超过安全上限，请缩小图片后重试",
            imageCount = 1,
            estimatedBytes = estimateStringBytes(valueChars.toLong() + source.length),
            maxBytes = estimateStringBytes(MAX_INPUT_CHARS.toLong()),
        )

    private fun estimateStringBytes(chars: Long): Long =
        (chars + 1L) * PARCEL_STRING_BYTES_PER_CHAR

    private fun addInput(
        inputs: MutableList<Input>,
        value: String?,
        source: String,
    ): Resolution.Failure? {
        if (value.isNullOrEmpty()) return null
        if (inputs.size >= MAX_INPUTS) return inputLimitFailure(inputs.size + 1)
        if (value.length > MAX_INPUT_CHARS) {
            return oversizedReferenceFailure(value.length, source)
        }
        inputs += Input(value, source, imageHint = true)
        return null
    }

    private fun collectFromObject(
        value: Any?,
        source: String,
        candidates: LinkedHashMap<String, Candidate>,
        depth: Int,
        budget: TraversalBudget,
    ) {
        if (value == null) return
        if (depth > MAX_JSON_DEPTH || candidates.size >= MAX_CANDIDATES) {
            budget.markExceeded()
            return
        }
        if (!budget.consume()) return
        when (value) {
            is String -> collectFromString(
                text = value,
                source = source,
                candidates = candidates,
                imageHint = source.hasImageHint(),
                depth = depth,
                budget = budget,
            )
            is JSONObject -> collectFromJsonObject(value, source, candidates, depth, budget)
            is JSONArray -> collectFromJsonArray(value, source, candidates, depth, budget)
        }
    }

    private fun collectFromString(
        text: String,
        source: String,
        candidates: LinkedHashMap<String, Candidate>,
        imageHint: Boolean,
        depth: Int,
        budget: TraversalBudget,
    ) {
        if (candidates.size >= MAX_CANDIDATES) {
            budget.markExceeded()
            return
        }
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        if (depth <= MAX_JSON_DEPTH && (trimmed.startsWith("{") || trimmed.startsWith("["))) {
            parseJson(trimmed)?.let { json ->
                collectFromObject(json, source, candidates, depth + 1, budget)
                return
            }
        }
        if (imageHint || trimmed.isDirectImageReference()) {
            candidates.putIfAbsent(trimmed, Candidate(trimmed, source))
        }
    }

    private fun collectFromJsonObject(
        json: JSONObject,
        source: String,
        candidates: LinkedHashMap<String, Candidate>,
        depth: Int,
        budget: TraversalBudget,
    ) {
        val keys = json.keys()
        while (keys.hasNext()) {
            if (candidates.size >= MAX_CANDIDATES) {
                budget.markExceeded()
                return
            }
            if (!budget.consume()) return
            val key = keys.next()
            val value = json.opt(key)
            val nextSource = "$source.$key"
            if (value == null || value === JSONObject.NULL) continue
            if (value is String) {
                collectFromString(
                    text = value,
                    source = nextSource,
                    candidates = candidates,
                    imageHint = source.hasImageHint() || key.hasImageHint() || value.isDirectImageReference(),
                    depth = depth + 1,
                    budget = budget,
                )
            } else {
                collectFromObject(value, nextSource, candidates, depth + 1, budget)
            }
        }
    }

    private fun collectFromJsonArray(
        json: JSONArray,
        source: String,
        candidates: LinkedHashMap<String, Candidate>,
        depth: Int,
        budget: TraversalBudget,
    ) {
        for (index in 0 until json.length()) {
            if (candidates.size >= MAX_CANDIDATES) {
                budget.markExceeded()
                return
            }
            collectFromObject(json.opt(index), "$source[$index]", candidates, depth + 1, budget)
            if (budget.exceeded) return
        }
    }

    private fun parseJson(text: String): Any? =
        try {
            if (text.startsWith("{")) JSONObject(text) else JSONArray(text)
        } catch (_: Exception) {
            null
        }

    private fun String.hasImageHint(): Boolean {
        val lower = lowercase()
        return lower.contains("image") ||
            lower.contains("images") ||
            lower.contains("photo") ||
            lower.contains("picture") ||
            lower.contains("pic") ||
            lower.contains("screenshot") ||
            lower.contains("thumbnail") ||
            lower.contains("base64") ||
            lower.contains("uri") ||
            lower.contains("url") && lower.contains("img") ||
            lower.contains("path") && (lower.contains("img") || lower.contains("image"))
    }

    private fun String.isPotentialImageInput(): Boolean {
        var start = 0
        while (start < length && start < MAX_LEADING_WHITESPACE && this[start].isWhitespace()) start++
        if (start >= length) return false
        if (start == MAX_LEADING_WHITESPACE && this[start].isWhitespace()) return false
        val first = this[start]
        return first == '{' || first == '[' || isDirectImageReference(start)
    }

    private fun String.isDirectImageReference(): Boolean = isDirectImageReference(0)

    private fun String.isDirectImageReference(start: Int): Boolean =
        startsWith("content://", start) ||
            startsWith("file://", start) ||
            startsWith("data:image/", start) ||
            startsWith("http://", start) && hasImageExtension(start) ||
            startsWith("https://", start) && hasImageExtension(start) ||
            getOrNull(start) == '/' && hasImageExtension(start)

    private fun String.hasImageExtension(start: Int = 0): Boolean {
        val scanEnd = minOf(length, start + MAX_URI_CHARS)
        var end = scanEnd
        for (index in start until scanEnd) {
            if (this[index] == '?' || this[index] == '#') {
                end = index
                break
            }
        }
        if (scanEnd < length && end == scanEnd) return false
        return imageExtensions.any { extension ->
            end - start >= extension.length &&
                regionMatches(end - extension.length, extension, 0, extension.length, ignoreCase = true)
        }
    }
}
