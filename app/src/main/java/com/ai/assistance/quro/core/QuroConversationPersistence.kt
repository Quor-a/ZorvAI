package com.ai.assistance.quro.core
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.content.Context
import android.os.Build
import android.util.Log
import com.ai.assistance.quro.core.cards.QuroChatCard
import com.ai.assistance.quro.core.cards.parseCard
import com.ai.assistance.quro.core.cards.serializeCard
import com.ai.assistance.quro.util.QuroStageHints
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 多会话持久化：把多个对话（含消息、工具调用）以 JSON 落盘到应用私有目录，
 * 退出 APP 后再次打开可完整恢复历史聊天记录。
 * 仅依赖 Android 自带的 org.json，不引入任何序列化第三方库。
 */

/**
 * 会话库健康状态。
 * 解析失败时不能静默当作「新用户」，必须把损坏文件隔离并让界面明确提示，
 * 同时在用户做出选择前暂停一切写入，避免把可抢救的数据永久覆盖。
 */
data class QuroConversationHealth(
    val quarantined: Boolean = false,
    val corruptFile: String? = null,
    val reason: String? = null,
    val backupCount: Int = 0,
    /** 本次加载中无法解析而被跳过的会话数（issue #26：不再静默丢弃）。 */
    val droppedConversations: Int = 0,
    /** 本次加载中无法解析而被跳过的消息 / 卡片数。 */
    val droppedMessages: Int = 0,
    /** 无法解析的原始条目另存文件，供用户或支持人员取回。 */
    val droppedEntriesFile: String? = null,
    /** 防缩水保护拒绝了本次覆盖。 */
    val shrinkBlocked: Boolean = false,
    /** 历史最大会话数（防缩水基准）。 */
    val highWaterMark: Int = 0,
)

/** 历史列表中展示用的轻量元数据。 */
data class QuroConversationMeta(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val preview: String,
    val genUiType: String = "normal",
)

/** 一个完整对话（含全部消息），用于内存与落盘。 */
data class QuroPersistedConversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** 保留对话轮数：null = 跟随模型默认（contextWindow）；N>0 = 仅保留最近 N 个 (用户+助手) 轮次。 */
    val historyRounds: Int? = null,
    /** 本对话框类型：normal = 普通对话框（正常聊天）；genui = GenUI 对话框（强制 AI 用原生 quro-ui 生成界面）。按会话隔离，切换/新建各自记忆。 */
    val genUiType: String = "normal",
    /**
     * 🔴 会话绑定的**人格卡 id**（2026-11-04 会话↔人格绑定）：
     * 空 = 跟随全局激活人格（旧行为）；非空 = 本会话固定用这张人格卡
     * 对话内人格选择器选人格时同时写绑定，实现「每个对话一个固定人格」。
     */
    val personaId: String = "",
    val messages: List<QuroMessage>,
    /**
     * 临时对话：只在内存里存在，**永不落盘**。
     *
     * 用途是「问一句就走」的场景（试一个模型、贴一段敏感文本、临时算个数）——
     * 这些内容不该进历史列表，也不该留在磁盘上等下次被翻出来。
     *
     * 实现方式是写入时过滤（见 `QuroChatViewModel.persistConversations`），
     * 而不是打标记后延时删除：延时删除要处理「应用被杀」的窗口，过滤则天然没有窗口。
     * 这个字段不参与序列化 —— 磁盘上出现的 ephemeral 会话本身就是 bug。
     */
    val ephemeral: Boolean = false,
)

class QuroConversationRepository(context: Context) {
    private val dir = context.filesDir
    private val file = File(dir, "quro_conversations.json")
    private val backupDir = File(dir, "quro_backups")
    private val healthPrefs = context.getSharedPreferences("quro_conversation_health", Context.MODE_PRIVATE)

    private val _health = MutableStateFlow(QuroConversationHealth())
    val health: StateFlow<QuroConversationHealth> = _health.asStateFlow()

    /** 只读降级：损坏文件被隔离后，用户确认前任何全量保存都必须被拒绝。 */
    @Volatile private var readOnly = false
    @Volatile private var lastBackupAt = 0L

    fun isReadOnly(): Boolean = readOnly

    /**
     * 历史会话数高水位（issue #26）：记录本库「见过的最大会话数」，只增不减。
     * 用途是识别「数据凭空消失」——老设备历史有 N 个会话，某次加载却只剩 0/1 个，
     * 且主文件 nonexistent / 骤降，这是异常，绝不能当成新用户直接覆盖写回。
     */
    private fun highWater(): Int = healthPrefs.getInt(KEY_HIGH_WATER, 0)

    private fun bumpHighWater(count: Int) {
        val cur = highWater()
        if (count > cur) healthPrefs.edit().putInt(KEY_HIGH_WATER, count).apply()
    }

    /** 防缩水已另存现场后，把基准下调到新值，避免后续每次 saveAll 都重复报警。 */
    private fun resetHighWater(count: Int) {
        healthPrefs.edit().putInt(KEY_HIGH_WATER, count).apply()
    }

    fun loadAll(): List<QuroPersistedConversation> {
        if (!file.exists()) {
            // 隔离状态跨进程重启保留：文件已被改名移走，不能因为「主文件不存在」就当新用户处理。
            val pending = healthPrefs.getString("corrupt_file", null)
            if (pending != null) {
                readOnly = true
                _health.value = QuroConversationHealth(
                    quarantined = true,
                    corruptFile = pending,
                    reason = healthPrefs.getString("reason", null),
                    backupCount = listBackups().size,
                    highWaterMark = highWater(),
                )
                return emptyList()
            }
            // issue #26 关键防御：老用户的会话库在 App 未运行期间消失（被外部清理/快照事务误伤），
            // 这里若直接返回 emptyList，ViewModel 会立刻新建一条「新对话」并 saveAll 覆盖，
            // 把 9 会话的现场彻底抹掉。改为：先用最完整的备份自动恢复，恢复不了才进只读降级。
            val hwm = highWater()
            if (hwm >= SHRINK_GUARD_MIN) {
                val best = bestBackup()
                if (best != null) {
                    val restored = runCatching { best.readText() }.getOrNull()
                    if (!restored.isNullOrBlank() && runCatching {
                            JSONObject(restored).optJSONArray("conversations")?.length() ?: 0
                        }.getOrDefault(0) > 0
                    ) {
                        runCatching { atomicWrite(file, restored) }
                        readOnly = false
                        bumpHighWater(hwm)
                        _health.value = QuroConversationHealth(
                            backupCount = listBackups().size,
                            highWaterMark = hwm,
                            reason = "会话库文件丢失，已从最近备份自动恢复",
                        )
                        Log.w("QuroPersist", "会话库主文件缺失，已从备份自动恢复: ${best.name}")
                        return parseAll(JSONObject(restored).optJSONArray("conversations") ?: JSONArray()).conversations
                    }
                }
                // 没有任何可用备份：进入只读降级，杜绝「新对话」覆盖现场。
                readOnly = true
                _health.value = QuroConversationHealth(
                    quarantined = true,
                    corruptFile = file.absolutePath,
                    reason = "会话库文件丢失且无可用备份（历史 $hwm 个会话），已暂停写入以保护现场",
                    backupCount = listBackups().size,
                    highWaterMark = hwm,
                )
                Log.e("QuroPersist", "会话库主文件缺失且无备份，进入只读降级（历史 $hwm 会话）")
                return emptyList()
            }
            readOnly = false
            _health.value = QuroConversationHealth()
            return emptyList()
        }
        val text = try {
            file.readText()
        } catch (e: Throwable) {
            return quarantine("读取失败：${e.message}")
        }
        if (text.isBlank()) return quarantine("文件为空或被截断")
        val root = try {
            JSONObject(text)
        } catch (e: Throwable) {
            return quarantine("JSON 解析失败：${e.message}")
        }
        val arr = root.optJSONArray("conversations")
            ?: return quarantine("缺少 conversations 字段")

        // issue #26：逐会话解析不再静默丢弃——计数 + 原始条目另存 quarantine + 暴露 health。
        val parsed = parseAll(arr)
        val raw = parsed.conversations
        val droppedConv = parsed.droppedConversations
        val droppedMsg = parsed.droppedMessages
        val droppedFile = parsed.droppedFile
        bumpHighWater(arr.length())
        readOnly = false
        _health.value = QuroConversationHealth(
            droppedConversations = droppedConv,
            droppedMessages = droppedMsg,
            droppedEntriesFile = droppedFile,
            backupCount = listBackups().size,
            highWaterMark = highWater(),
        )
        if (healthPrefs.contains("corrupt_file")) healthPrefs.edit().remove("corrupt_file").remove("reason").apply()
        scheduleBackup(text)

        // 迁移：自愈旧版本遗留的脏数据（hidden 字段不存在、管道消息泄漏、垃圾工具结果）。
        // 每次加载时检查是否需要清理；仅当发现脏数据时才回写，避免每次启动都写磁盘。
        val healed = migrateAndClean(raw)
        Log.i("QuroPersist", "LOAD rawConvs=${raw.size} rawMsgs=${raw.sumOf { it.messages.size }} healedMsgs=${healed.sumOf { it.messages.size }} droppedConv=$droppedConv droppedMsg=$droppedMsg")
        if (healed !== raw) {
            saveAll(healed)  // 回写修复后的数据，下次不再重复迁移
        }
        return healed
    }

    /**
     * 解析全部会话条目：无法解析的原始 JSONObject 不丢弃，另存到 quarantine 文件并计数。
     * 返回 Quadruple(可解析会话, 丢弃会话数, 丢弃消息数, 另存文件路径)。
     */
    private data class ParsedConversations(
        val conversations: List<QuroPersistedConversation>,
        val droppedConversations: Int,
        val droppedMessages: Int,
        val droppedFile: String?,
    )

    private fun parseAll(arr: JSONArray): ParsedConversations {
        val raw = mutableListOf<QuroPersistedConversation>()
        var droppedConv = 0
        var droppedMsg = 0
        val droppedRaw = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = runCatching { arr.getJSONObject(i) }.getOrNull()
            if (obj == null) {
                droppedConv++
                continue
            }
            val parsed = runCatching { parseConv(obj) }.getOrNull()
            if (parsed == null) {
                droppedConv++
                droppedMsg += runCatching { obj.optJSONArray("messages")?.length() ?: 0 }.getOrDefault(0)
                droppedRaw.put(obj)
            } else {
                raw.add(parsed)
            }
        }
        val file2 = if (droppedRaw.length() > 0) {
            runCatching {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val target = File(dir, "quro_conversations.dropped-$stamp.json")
                atomicWrite(target, JSONObject().put("conversations", droppedRaw).toString())
                target.absolutePath
            }.getOrElse {
                Log.w("QuroPersist", "无法另存解析失败的会话条目", it)
                null
            }
        } else null
        return ParsedConversations(raw, droppedConv, droppedMsg, file2)
    }

    /**
     * 数据自愈（兼容旧版本持久化格式）：
     *
     * 1. **隐藏管道消息**：role=="tool" 或 assistant+带 toolCalls → 强制 hidden=true
     *    （parseMsg 已处理；此处做二次保险 + 清理垃圾内容）
     * 2. **空内容管道消息**：assistant 消息 content 为空且带 toolCalls → 删除（纯占位，无信息价值）
     * 3. **垃圾工具结果**：tool 结果 content 为 "33." 开头或 "<100" 等异常值 → 删除
     * 4. **空对话**：删除后没有任何真实 user/assistant 文本消息的会话 → 整个删除
     */
    private fun migrateAndClean(raw: List<QuroPersistedConversation>): List<QuroPersistedConversation> {
        var needsSave = false
        val cleaned = raw.mapNotNull { conv ->
            // 逐条迁移：丢弃管道占位、清空垃圾工具结果，保留真实对话
            val out = mutableListOf<QuroMessage>()
            for (m in conv.messages) {
                // 1) 内部管道消息自愈：仅丢弃「无任何可见信息的残留空消息」，
                //    但必须保留承载真实对话内容的消息——这正是「退出重开对话框看不到部分内容」的根因修复：
                //      · 助手思考过程（reasoning 非空）→ 渲染 💭 思考块
                //      · 助手工具调用（toolCalls 非空）→ 渲染 🔧 工具调用块
                //      · 真实工具结果（role==tool 且 toolCallId 非空且 content 非空）→ 回填 🔧 块的结果
                //    旧逻辑「m.hidden 一律丢弃」会连同思考/工具块一起删掉，导致历史对话重启后缺失。
                val hasRealContent = m.content.isNotBlank() ||
                    !m.reasoning.isNullOrBlank() ||
                    !m.toolCalls.isNullOrEmpty() ||
                    !m.attachments.isNullOrEmpty()
                val isRealToolResult = m.role == "tool" && m.toolCallId != null && m.content.isNotBlank()
                val isPipeDrop = !hasRealContent && !isRealToolResult
                if (isPipeDrop) { needsSave = true; continue }
                // 1.5) 🔧 Bug修复「⏳ 正在处理残留」【复发根因修复】：
                //    上一轮（round-1）的过滤条件是「role==assistant 且 无 reasoning/工具/卡片/附件
                //    且 content 命中阶段提示前缀」才丢弃。但本地 prefill 进度占位
                //    （「⏳ 正在处理提示词… X%」）在「思考流先于正文流到达」时，会被同一条流式占位气泡
                //    附带 reasoning（emitThinkingToken 复用 streamPlaceholderId，见 QuroAssistant.kt:162-180），
                //    导致此处 `m.reasoning.isNullOrBlank()` 为 false → 过滤条件整体不成立 → 占位残留到重开。
                //    阶段提示占位永远是临时状态、绝非真实消息，故改为「只要 content 命中已知阶段提示
                //    前缀就无条件丢弃」，彻底摆脱 reasoning/工具/卡片的干扰。
                //    （真实思考消息的 reasoning 不会以 ⏳ 正在… 开头；真实工具/卡片消息的 content 也不会以
                //    该前缀开头，故不会误删真实内容。）
                if (m.role == "assistant" && isTransientStageHint(m.content)) {
                    needsSave = true; continue
                }
                // 2) 工具结果：保留消息，但把已知的垃圾内容清空
                //    （旧版 bug 曾把 33.333… / "OK" / 纯数字 0-100 当成工具结果落盘，
                //     重启后会以「→ 33.333…」的形式污染对话气泡）
                if (m.role == "tool") {
                    val isGarbage = m.content.startsWith("33.") || m.content == "OK" ||
                        (m.content.matches(Regex("^\\d+\\.?\\d*$")) && (m.content.toDoubleOrNull() ?: 999.0) in 0.0..100.0)
                    if (isGarbage) { needsSave = true; out.add(m.copy(content = "")) }
                    else out.add(m)
                    continue
                }
                // 3) 撤销旧版「把 reasoning 回填进 content」的自愈（v201 修正）：
                //    思考应仅以 ThinkBubble 独立呈现；把 reasoning 混入正文会把 HTML 标签泄漏到气泡正文
                //    （用户截图确诊：气泡里直接渲染出原始 <...> 标签，思考混乱）。
                //    仅当检测到旧数据确已被污染——content 非空且恰好等于 reasoning 且含 '<'——
                //    才把 content 清空修复；正常回合（content 为真实文本）一律原样保留，绝不注入思考。
                //    纯思考回合（content 为空、reasoning 有值）保持 content 为空，UI 仅显示 ThinkBubble，正确。
                val healLeak = m.role == "assistant"
                    && !m.reasoning.isNullOrBlank()
                    && m.content == m.reasoning
                    && m.content.contains("<")
                if (healLeak) { needsSave = true; out.add(m.copy(content = "")) }
                else out.add(m)
            }
            // 过滤后若连一条可见消息都不剩（纯管道空壳），才丢弃整个会话；
            // 否则即使「没有真实 user/assistant 文本」，也保留残存的思考/工具块，
            // 绝不用单条占位符覆盖——避免把用户尚能查看的历史一并销毁
            // （这正是「退出重开旧对话只剩思考/工具块、文本已丢」场景的保护：不再二次破坏）。
            if (out.isEmpty()) {
                needsSave = true
                return@mapNotNull null  // 删除空/全管道会话
            }
            // 工具结果配对：旧会话里 assistant[toolCalls].result 可能为空，结果只存在于
            // 独立的 role=tool 消息中。按 toolCallId 回填进调用本身，让展示自包含，
            // 重启/切会话后不会再被「执行中占位 + 结果行」拆成两条。
            // role=tool 消息本身必须保留 —— 它是发给模型的工具结果，不是 UI 冗余。
            val toolResults = HashMap<String, String>()
            out.forEach { m ->
                val id = m.toolCallId
                if (m.role == "tool" && !id.isNullOrBlank() && m.content.isNotBlank()) {
                    // role=tool 的 content 是发给模型的信封，回填到调用里展示时只取结果正文；
                    // 同一 id 若有多条结果则拼接保留，不能让后一条覆盖前一条。
                    val clean = cleanToolResultForDisplay(m.content)
                    val prev = toolResults[id]
                    toolResults[id] = if (prev.isNullOrBlank()) clean else prev + "\n\n────\n\n" + clean
                }
            }
            var healed = false
            val paired = out.map { m ->
                val calls = m.toolCalls
                if (calls.isNullOrEmpty() || toolResults.isEmpty()) {
                    m
                } else {
                    var changed = false
                    val merged = calls.map { c ->
                        val fallback = if (c.result.isNullOrBlank()) toolResults[c.id] else null
                        if (fallback.isNullOrBlank()) c else { changed = true; c.copy(result = fallback) }
                    }
                    if (changed) { healed = true; m.copy(toolCalls = merged) } else m
                }
            }
            if (healed) needsSave = true
            conv.copy(messages = paired)
        }
        return if (needsSave) cleaned else raw
    }

    @Synchronized
    fun saveAll(list: List<QuroPersistedConversation>) {
        if (readOnly) {
            Log.w("QuroPersist", "会话库处于只读降级，跳过保存")
            return
        }
        runCatching {
            // issue #26 防缩水：历史高水位很多、本次却要写回极少会话，是「数据被抹掉后
            // 用空库覆盖」的典型形态。先把当前磁盘现场另存 orphan，再放行写入，
            // 这样即便判定失误（用户真删了很多），旧数据也仍在 orphan + 备份里可取回。
            val hwm = highWater()
            val shrunk = hwm >= SHRINK_GUARD_MIN &&
                list.size <= (hwm * SHRINK_RATIO).toInt().coerceAtMost(hwm - 1)
            if (shrunk) {
                shieldShrink(hwm, list.size)
                // 现场已另存，基准下调到新值，避免后续每次保存都重复报警。
                resetHighWater(list.size)
            }
            val arr = JSONArray()
            list.forEach { arr.put(serializeConv(it)) }
            val root = JSONObject().put("conversations", arr)
            val text = root.toString()
            atomicWrite(file, text)
            if (!shrunk) bumpHighWater(list.size)
            scheduleBackup(text)
        }.onFailure {
            Log.e("QuroPersist", "会话库保存失败", it)
        }
    }

    /**
     * 防缩水：把当前主文件原样另存为 orphan，并把 health 标记为 shrinkBlocked，
     * 让界面明确提示「会话数异常减少，已从备份/现场保留，可恢复」。
     */
    private fun shieldShrink(hwm: Int, incoming: Int) {
        var orphanPath: String? = null
        if (file.exists()) {
            runCatching {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val target = File(dir, "quro_conversations.orphan-$stamp.json")
                file.copyTo(target, overwrite = false)
                orphanPath = target.absolutePath
            }.onFailure { Log.w("QuroPersist", "另存缩水前现场失败", it) }
        }
        val reason = "会话数从历史 $hwm 骤降到 $incoming，已保留写入前现场" +
            (orphanPath?.let { "：$it" } ?: "")
        Log.w("QuroPersist", reason)
        val cur = _health.value
        _health.value = cur.copy(
            shrinkBlocked = true,
            highWaterMark = hwm,
            droppedEntriesFile = cur.droppedEntriesFile ?: orphanPath,
            reason = reason,
        )
    }

    /**
     * 会话数最多的备份（不是时间最新——最新的可能只是丢数据后的空库）。
     * 用于主文件缺失时自动恢复，以及 UI「从备份恢复」优先项（issue #26）。
     */
    fun bestBackup(): File? = listBackups().mapNotNull { f ->
        val n = runCatching {
            JSONObject(f.readText()).optJSONArray("conversations")?.length() ?: 0
        }.getOrDefault(0)
        if (n > 0) f to n else null
    }.maxByOrNull { it.second }?.first

    /**
     * 原子写：临时文件 → flush + fsync → 原子替换。
     * 目标文件在替换完成前始终是上一份完整数据；进程在写入窗口内被强杀，
     * 最多留下一个临时文件，主文件不会被截断。
     */
    private fun atomicWrite(target: File, text: String) {
        val parent = target.parentFile ?: throw IOException("no parent directory")
        if (!parent.exists()) parent.mkdirs()
        val tmp = File(parent, "${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(tmp, false).use { fos ->
                fos.write(text.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            replaceAtomically(tmp, target)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun replaceAtomically(tmp: File, target: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val atomic = runCatching {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
                true
            }.getOrDefault(false)
            if (atomic) return
            val plain = runCatching {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                true
            }.getOrDefault(false)
            if (plain) return
        }
        if (tmp.renameTo(target)) return
        // 仅当所有原子路径都不可用时才删除旧目标；此时旧数据已有轮转备份可回退。
        target.delete()
        if (!tmp.renameTo(target)) throw IOException("atomic replace failed: ${target.name}")
    }

    /** 把损坏文件改名隔离并进入只读降级；原文件绝不删除、绝不覆盖。 */
    private fun quarantine(reason: String): List<QuroPersistedConversation> {
        readOnly = true
        val previous = healthPrefs.getString("corrupt_file", null)
        val kept = if (previous != null) {
            File(previous)
        } else {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val target = File(dir, "quro_conversations.corrupt-$stamp.json")
            if (file.renameTo(target)) target else file
        }
        if (previous == null) {
            healthPrefs.edit()
                .putString("corrupt_file", kept.absolutePath)
                .putString("reason", reason)
                .apply()
        }
        _health.value = QuroConversationHealth(
            quarantined = true,
            corruptFile = kept.absolutePath,
            reason = reason,
            backupCount = listBackups().size,
            highWaterMark = highWater(),
        )
        Log.w("QuroPersist", "会话库损坏已隔离，暂停保存：$reason")
        return emptyList()
    }

    /** 清除「隔离」相关标记，但保留 high_water 保护基准（不能被普通 clear 抹掉）。 */
    private fun clearQuarantineFlags() {
        healthPrefs.edit()
            .remove("corrupt_file")
            .remove("reason")
            .apply()
    }

    /** 轮转备份：节流到每 10 分钟一次，后台原子写入，保留最近 8 份。 */
    private fun scheduleBackup(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastBackupAt < BACKUP_INTERVAL_MS) return
        lastBackupAt = now
        Thread({
            runCatching {
                if (!backupDir.exists()) backupDir.mkdirs()
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                atomicWrite(File(backupDir, "conv-$stamp.json"), text)
                pruneBackups()
            }.onFailure { Log.w("QuroPersist", "会话库备份失败", it) }
        }, "quro-conv-backup").apply { isDaemon = true }.start()
    }

    private fun listBackups(): List<File> = runCatching {
        (backupDir.listFiles(
            java.io.FileFilter { f -> f.isFile && f.name.startsWith("conv-") && f.name.endsWith(".json") }
        ) ?: emptyArray())
            .sortedByDescending { it.name }
    }.getOrDefault(emptyList())

    private fun pruneBackups() {
        val now = System.currentTimeMillis()
        // issue #26：原策略只留 8 份（按 10 分钟节流 ≈ 80 分钟），远不足覆盖 7 天。
        // 新策略：7 天内的备份一律保留（数量上限提高到 7 天可容纳的量级），
        //         超龄或超上限才裁剪；这样 11-16 这类「中间某天没开 App」也不会被冲掉关键节点。
        val keep = listBackups().filter { f ->
            now - f.lastModified() <= BACKUP_TTL_MS
        }.take(BACKUP_KEEP)
        val keepNames = keep.map { it.name }.toSet()
        listBackups().filterNot { it.name in keepNames }.forEach { runCatching { it.delete() } }
    }

    /** 按时间倒序的可用备份，供界面展示与恢复。 */
    fun backups(): List<File> = listBackups()

    /** 从备份恢复主文件；成功后解除只读降级。 */
    fun restoreBackup(backup: File): Boolean = runCatching {
        val text = backup.readText()
        val restoredCount = JSONObject(text).optJSONArray("conversations")?.length()
            ?: throw IllegalArgumentException("备份结构不完整")
        atomicWrite(file, text)
        clearQuarantineFlags()
        bumpHighWater(restoredCount)
        readOnly = false
        _health.value = QuroConversationHealth(highWaterMark = highWater())
        true
    }.getOrElse {
        Log.e("QuroPersist", "从备份恢复失败", it)
        false
    }

    /**
     * 放弃隔离数据并重新开始；损坏文件仍保留在磁盘上，不会被新写入覆盖。
     * issue #26：这里有「用户明确放弃旧库」的语义，允许把高水位重置，
     * 否则残留的高水位会让新库第一次 saveAll 就被误判为异常骤降。
     */
    fun discardQuarantine() {
        healthPrefs.edit()
            .remove("corrupt_file")
            .remove("reason")
            .remove(KEY_HIGH_WATER)
            .apply()
        readOnly = false
        _health.value = QuroConversationHealth()
    }

    companion object {
        private const val BACKUP_INTERVAL_MS = 10 * 60 * 1000L
        /** issue #26：保留至少 7 天，数量上限提高到 128 份（每天约 10 分钟一次节流也够覆盖）。 */
        private const val BACKUP_TTL_MS = 8 * 24 * 60 * 60 * 1000L
        private const val BACKUP_KEEP = 128
        private const val KEY_HIGH_WATER = "conversation_high_water"
        /** 触发防缩水/丢失保护的最小历史会话数。 */
        private const val SHRINK_GUARD_MIN = 5
        /** 本次写入会话数 <= 高水位 * 该比例时视为异常骤降（0.4 = 降到四成以下）。 */
        private const val SHRINK_RATIO = 0.4
    }

    /**
     * 流式阶段提示判定（Bug「⏳ 正在处理残留」）：统一委托共享实现
     * [com.ai.assistance.quro.util.QuroStageHints]，与 QuroAssistant 的判定严格一致，消除漂移。
     */
    private fun isTransientStageHint(content: String): Boolean =
        QuroStageHints.isTransientStageHint(content)

    private fun serializeConv(c: QuroPersistedConversation): JSONObject {
        val msgs = JSONArray()
        c.messages.forEach { msgs.put(serializeMsg(it)) }
        return JSONObject().apply {
            put("id", c.id)
            put("title", c.title)
            put("createdAt", c.createdAt)
            put("updatedAt", c.updatedAt)
            put("historyRounds", c.historyRounds ?: JSONObject.NULL)
            put("genUiType", c.genUiType)
            if (c.personaId.isNotBlank()) put("personaId", c.personaId)
            put("messages", msgs)
        }
    }

    private fun serializeMsg(m: QuroMessage): JSONObject {
        // 🛑 关键修复：绝不能把「无工具调用」序列化成空数组 "toolCalls": []。
        // 旧实现无条件 `val calls = JSONArray()` 会对每条消息（含纯文本终答 / 用户消息）
        // 都写入空数组；reload 时 parseMsg 读到空数组 → toolCalls 变成非 null 空列表 →
        // uiMessages 的 `m.toolCalls != null` 判定为 true → 走「工具调用块」分支、text 被强制 null
        // → 退出重开后所有文本消息正文消失（仅剩 ThinkBubble）。这正是「live 有、reload 无」的真凶。
        // 修复：仅当 toolCalls 非 null 且非空时才写该字段；否则省略（reload 时 parseMsg 会得到 null）。
        val calls = m.toolCalls?.takeIf { it.isNotEmpty() }?.let { tcs ->
            JSONArray().also { arr ->
                tcs.forEach { call ->
                    arr.put(
                        JSONObject().apply {
                            put("id", call.id)
                            put("name", call.name)
                            put("arguments", call.arguments)
                            put("result", call.result ?: JSONObject.NULL)
                            // 🔴 工具耗时要落盘：旧实现漏了这个字段，重启回放后
                            //    所有工具 durationMs=0，活动流永远不显耗时。
                            if (call.durationMs > 0) put("durationMs", call.durationMs)
                        },
                    )
                }
            }
        }
        val atts = m.attachments?.takeIf { it.isNotEmpty() }?.let { als ->
            JSONArray().also { arr ->
                als.forEach { a ->
                    arr.put(
                        JSONObject().apply {
                            put("id", a.id)
                            put("type", a.type)
                            put("uri", a.uri)
                            put("name", a.name)
                            put("mime", a.mime)
                            put("size", a.size)
                        },
                    )
                }
            }
        }
        return JSONObject().apply {
            put("id", m.id)
            put("role", m.role)
            put("content", m.content)
            put("toolCallId", m.toolCallId ?: JSONObject.NULL)
            put("toolLabel", m.toolLabel ?: JSONObject.NULL)
            put("reasoning", m.reasoning ?: JSONObject.NULL)
            // 🔴 思考耗时落盘：此前 thinkDurationMs 不序列化，重启后所有历史思考的
            //   「思考了 N秒」口径永久丢失，折叠行只能回退「思考过程 · N步」，
            //   而调用点又漏传 steps → 全部显示「0步」（旧思考显示混乱的组成部分）。
            //   发言人昵称同理（集群角色名重启后丢失，气泡全顶成人格名）。
            if (m.thinkDurationMs > 0) put("thinkMs", m.thinkDurationMs)
            m.senderName?.takeIf { it.isNotBlank() }?.let { put("senderName", it) }
            put("createdAt", m.createdAt)
            put("hidden", m.hidden)
            if (m.externalHint) put("externalHint", true)
            m.externalHintIds.takeIf { it.isNotEmpty() }?.let { put("externalHintIds", JSONArray(it)) }
            calls?.let { put("toolCalls", it) }
            atts?.let { put("attachments", it) }
            // 🛑 关键修复（v201）：持久化气泡内富组件。v201 前遗漏 cards 字段，
            // 导致退出/切换界面重进后全部可视化组件彻底丢失。
            m.cards.takeIf { it.isNotEmpty() }?.let { cls ->
                put("cards", JSONArray().also { arr -> cls.forEach { c -> arr.put(serializeCard(c)) } })
            }
        }
    }

    private fun parseConv(o: JSONObject): QuroPersistedConversation {
        val msgsArr = o.optJSONArray("messages") ?: JSONArray()
        val msgs = mutableListOf<QuroMessage>()
        for (i in 0 until msgsArr.length()) {
            runCatching { parseMsg(msgsArr.getJSONObject(i)) }.getOrNull()?.let { msgs.add(it) }
        }
        // 旧 JSON 无 historyRounds 字段 → null → 跟随模型默认（向后兼容）
        val historyRounds = if (o.has("historyRounds") && !o.isNull("historyRounds")) o.optInt("historyRounds", -1).let { if (it < 0) null else it } else null
        // 旧 JSON 无 genUiType 字段 → "normal"（默认普通对话框，向后兼容）
        val genUiType = if (o.has("genUiType") && !o.isNull("genUiType")) o.optString("genUiType", "normal") else "normal"
        // 旧 JSON 无 personaId → ""（跟随全局激活人格，向后兼容）
        val boundPersonaId = o.optString("personaId", "")
        return QuroPersistedConversation(
            id = o.optString("id", UUID.randomUUID().toString()),
            title = o.optString("title", qstr(R.string.qk_00282)),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            historyRounds = historyRounds,
            genUiType = genUiType,
            personaId = boundPersonaId,
            messages = msgs,
        )
    }

    private fun parseMsg(o: JSONObject): QuroMessage {
        val callsArr = o.optJSONArray("toolCalls")
        val calls = if (callsArr != null) {
            val list = mutableListOf<QuroToolCall>()
            for (i in 0 until callsArr.length()) {
                val c = callsArr.getJSONObject(i)
                list.add(
                    QuroToolCall(
                        id = c.optString("id", ""),
                        name = c.optString("name", ""),
                        arguments = c.optString("arguments", ""),
                        result = if (c.has("result") && !c.isNull("result")) c.optString("result") else null,
                        durationMs = c.optLong("durationMs", 0L),
                    ),
                )
            }
            list
        } else {
            null
        }
        val attsArr = o.optJSONArray("attachments")
        val atts = if (attsArr != null) {
            val list = mutableListOf<QuroAttachment>()
            for (i in 0 until attsArr.length()) {
                val a = attsArr.getJSONObject(i)
                list.add(
                    QuroAttachment(
                        id = a.optString("id", UUID.randomUUID().toString()),
                        type = a.optString("type", "file"),
                        uri = a.optString("uri", ""),
                        name = a.optString("name", "file"),
                        mime = a.optString("mime", "application/octet-stream"),
                        size = a.optLong("size", 0),
                    ),
                )
            }
            list
        } else {
            null
        }
        // 🛑 关键修复（v201）：恢复气泡内富组件。v201 前 parseMsg 不读 cards，
        // 导致重进后组件丢失；此处按 cardType 还原全部类型，失败卡片忽略不中断加载。
        val cardsArr = o.optJSONArray("cards")
        val cards = if (cardsArr != null) {
            val list = mutableListOf<QuroChatCard>()
            for (i in 0 until cardsArr.length()) {
                runCatching { parseCard(cardsArr.getJSONObject(i)) }.getOrNull()?.let { list.add(it) }
            }
            list
        } else emptyList()
        val toolCallId = if (o.has("toolCallId") && !o.isNull("toolCallId")) o.optString("toolCallId") else null
        val toolLabel = if (o.has("toolLabel") && !o.isNull("toolLabel")) o.optString("toolLabel") else null
        val reasoning = if (o.has("reasoning") && !o.isNull("reasoning")) o.optString("reasoning") else null
        val role = o.optString("role", "user")
        val hasToolCalls = calls?.isNotEmpty() ?: false
        val hintIdsArr = o.optJSONArray("externalHintIds")
        val externalHintIds = if (hintIdsArr != null) {
            (0 until hintIdsArr.length()).mapNotNull { hintIdsArr.optString(it, "").takeIf { s -> s.isNotEmpty() } }
        } else emptyList()
        // 自修复（兼容旧版本未持久化 hidden 的历史数据）：
        // 凡是「工具结果（role==tool）」或「助手发出的工具调用占位（带 toolCalls）」，
        // 一律视为内部管道消息并隐藏，避免重启后空内容气泡 / 垃圾工具结果外泄。
        // 真实用户/助手文本消息（role=user/assistant 且无 toolCalls）保持可见。
        val hidden = if (o.has("hidden")) o.optBoolean("hidden", false)
        else (role == "tool" || hasToolCalls)
        return QuroMessage(
            id = o.optString("id", UUID.randomUUID().toString()),
            role = role,
            content = o.optString("content", ""),
            toolCallId = toolCallId,
            toolCalls = calls,
            toolLabel = toolLabel,
            reasoning = reasoning,
            // 旧 JSON 无这些字段 → 走默认值（向后兼容）
            thinkDurationMs = o.optLong("thinkMs", 0L),
            senderName = if (o.has("senderName") && !o.isNull("senderName")) o.optString("senderName") else null,
            attachments = atts,
            cards = cards,
            hidden = hidden,
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            externalHint = o.optBoolean("externalHint", false),
            externalHintIds = externalHintIds,
        )
    }
}

/**
 * role=tool 消息存的是发给模型的统一信封（[tool] 名 / status / 正文）。
 * 回填到 toolCalls.result 供界面展示时剥掉信封头，避免详情页出现内部协议文本；
 * 失败信封保留原文，用户需要看到错误原因。
 */
private fun cleanToolResultForDisplay(raw: String): String {
    if (!raw.startsWith("[tool] ")) return raw
    val lines = raw.lines()
    val statusIdx = lines.indexOfFirst { it.startsWith("status: ") }
    if (statusIdx < 0 || !lines[statusIdx].startsWith("status: ok")) return raw
    return lines.drop(statusIdx + 1).joinToString("\n").trim()
}