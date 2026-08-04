package com.ai.assistance.quro.core.terminal

import android.content.Context

/**
 * 终端命令历史（E-10）。
 *
 * 持久化到 SharedPreferences，跨会话、跨进程重启保留；最多保存 [MAX_ENTRIES] 条。
 *
 * 存储格式是用 `\u0000`（NUL）连接的单个字符串，而不是 `StringSet`：
 *  - `StringSet` **无序**且**自动去重**，历史记录两者都不能要；
 *  - NUL 不可能出现在用户输入的命令里（shell 本身也不允许），做分隔符最安全，
 *    比 `\n` 靠谱（粘贴多行命令时 `\n` 会把一条记录劈成两条）。
 *
 * 纯逻辑（[merge]）与 Android 存储分离，前者可直接单元测试。
 */
object QuroTerminalHistory {

    /** 历史上限。超出时丢弃最旧的。 */
    const val MAX_ENTRIES: Int = 200

    private const val PREFS_NAME = "quro_terminal_history"
    private const val KEY_ENTRIES = "entries"
    private const val SEP = "\u0000"

    // ════════════════════════════════════════
    // 纯逻辑（可单元测试）
    // ════════════════════════════════════════

    /**
     * 把一条新命令并入历史列表，返回新列表（**旧→新**排列）。
     *
     * 规则：
     *  1. 空白命令不入历史；
     *  2. 与**上一条**完全相同的命令不重复入（bash `ignoredups` 语义）；
     *     不做全局去重——用户反复穿插执行 `ls` / `cd ..` 时，全局去重会把
     *     历史顺序打乱得完全不可用；
     *  3. 超过 [max] 条时从头部丢弃最旧的。
     *
     * @param existing 现有历史（旧→新）
     * @param command 新命令（未 trim 也可以）
     * @param max 上限，默认 [MAX_ENTRIES]
     */
    fun merge(existing: List<String>, command: String, max: Int = MAX_ENTRIES): List<String> {
        val cmd = command.trim()
        if (cmd.isEmpty()) return existing
        if (existing.lastOrNull() == cmd) return existing

        val merged = existing + cmd
        return if (merged.size <= max) merged else merged.subList(merged.size - max, merged.size)
    }

    /** 反序列化：把存储字符串还原成列表（旧→新）。空串还原为空列表。 */
    fun decode(raw: String): List<String> =
        if (raw.isEmpty()) emptyList() else raw.split(SEP).filter { it.isNotEmpty() }

    /** 序列化：把列表编码成单个存储字符串。 */
    fun encode(entries: List<String>): String = entries.joinToString(SEP)

    // ════════════════════════════════════════
    // Android 持久化
    // ════════════════════════════════════════

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取全部历史（旧→新）。任何异常都退化成空列表，绝不让终端打不开。 */
    fun load(context: Context): List<String> = runCatching {
        decode(prefs(context).getString(KEY_ENTRIES, "") ?: "")
    }.getOrDefault(emptyList())

    /**
     * 追加一条命令并落盘，返回落盘后的完整历史。
     *
     * 用 `apply()` 异步提交，不阻塞调用线程（终端每敲一条命令都会调）。
     */
    fun add(context: Context, command: String): List<String> = runCatching {
        val merged = merge(load(context), command)
        prefs(context).edit().putString(KEY_ENTRIES, encode(merged)).apply()
        merged
    }.getOrDefault(emptyList())

    /** 清空历史。 */
    fun clear(context: Context) {
        runCatching { prefs(context).edit().remove(KEY_ENTRIES).apply() }
    }
}

/**
 * 历史浏览游标（↑ / ↓ 键的状态机）。
 *
 * 语义对齐 bash：
 *  - 初始停在「草稿位」（列表末尾之后），此处内容是用户当前正在输入、尚未提交的文本；
 *  - `↑`（[up]）第一次按下时把当前草稿**存起来**，然后往更旧的方向走；走到最旧就停住；
 *  - `↓`（[down]）往更新的方向走；越过最新一条后回到草稿位，还原用户原本输入的内容；
 *  - 用户提交命令或手动改动输入框后调 [reset]，游标回到草稿位。
 *
 * 纯 JVM，无 Android 依赖，可直接单元测试。
 *
 * @param entries 历史条目，**旧→新**排列
 */
class QuroHistoryCursor(entries: List<String>) {

    private val items: List<String> = entries.toList()

    /** 当前位置。等于 `items.size` 表示停在草稿位。 */
    private var pos: Int = items.size

    /** 第一次按 ↑ 时暂存的用户草稿。 */
    private var draft: String = ""

    /** 是否停在草稿位（没有在浏览历史）。 */
    val atDraft: Boolean get() = pos >= items.size

    /** 历史条数。 */
    val size: Int get() = items.size

    /**
     * 向更旧的方向移动一格。
     *
     * @param current 输入框里当前的文本（仅在从草稿位起步时会被暂存）
     * @return 应当填入输入框的文本；历史为空时返回 `null`（调用方不要改输入框）
     */
    fun up(current: String): String? {
        if (items.isEmpty()) return null
        if (pos >= items.size) {
            draft = current
            pos = items.size - 1
            return items[pos]
        }
        if (pos == 0) return items[0] // 已到最旧，停住
        pos--
        return items[pos]
    }

    /**
     * 向更新的方向移动一格。
     *
     * @return 应当填入输入框的文本；已经在草稿位时返回 `null`（调用方不要改输入框）
     */
    fun down(): String? {
        if (items.isEmpty()) return null
        if (pos >= items.size) return null // 已在草稿位
        pos++
        return if (pos >= items.size) draft else items[pos]
    }

    /** 回到草稿位并丢弃暂存草稿（提交命令后调用）。 */
    fun reset() {
        pos = items.size
        draft = ""
    }
}
