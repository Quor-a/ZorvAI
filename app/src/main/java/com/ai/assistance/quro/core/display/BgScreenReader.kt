package com.ai.assistance.quro.core.display

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.ai.assistance.quro.service.QuroAccessibilityService

/**
 * 后台虚拟屏的**界面结构读取器**（移植自 Mika 的 `ScreenReader`，去品牌化）。
 *
 * 为什么必须有它：只有截图的话，AI 只能靠像素猜位置，既不准又费 token。
 * 有了这个，AI 能拿到「这块屏上有哪些可点元素、叫什么、中心点在哪」，再配合
 * `tapElement(ref)` 就能精确点，不必反复看图试坐标。
 *
 * 读取来源：无障碍服务的 `getWindowsOnAllDisplays()` → 按 displayId 取窗口 → 读节点树。
 * 注意后台屏**不是活跃屏**，不能用 `rootInActiveWindow`。
 *
 * 坐标约定：
 *  - `centerX/centerY` 是**真实像素**，可直接用于 tap；
 *  - `bounds` 是**归一化 0..1000**，便于模型在不同分辨率间比较。
 */
data class BgElement(
    /** 稳定引用，如 `@e3`，本次快照内唯一。 */
    val ref: String,
    /** 控件角色（class 末段），如 Button / EditText / TextView / RecyclerView。 */
    val role: String,
    /** 显示名（文字 / 内容描述 / 提示语）。可能为空串。 */
    val name: String,
    val centerX: Int,
    val centerY: Int,
    /** [left, top, right, bottom] 归一化到 0..1000。 */
    val bounds: List<Int>,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean,
    val enabled: Boolean,
    val password: Boolean,
    /**
     * 资源 ID 短名，如 `search_src_text`。
     *
     * 为什么要给：界面上常有好几个长得一样、名字都是「搜索」的输入框；
     * 而回车前要认出「哪个是真搜索框」只能靠 ID。混淆过的短名（`a1`、`b2`）不给，那没有信息量。
     */
    val resourceId: String? = null,
    /** 当前显示的「名字」其实只是占位提示语（还没输入内容）。 */
    val hint: Boolean = false,
    /** 这个控件此刻有输入焦点（点完输入框后判断焦点落在哪）。 */
    val focused: Boolean = false,
    /** 输入框的占位提示语（仅 editable 有）。 */
    val placeholder: String? = null,
)

/**
 * 一次读屏的结果（**只有结构，不含 OCR 与画面诊断**）。
 *
 * 面向模型的文本由 `QuroVirtualScreen.BgReading.toPromptText()` 生成 ——
 * 这里刻意不再提供 `toPromptText`：两份格式长期必然走样，模型看到的描述会前后不一致。
 */
data class BgSnapshot(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val topPackage: String?,
    val elements: List<BgElement>,
    /** 所有可见文字（按出现顺序，去重），给 AI 快速了解界面内容。 */
    val texts: List<String>,
    /** 界面指纹：点输入框只换焦点也算变化。用来判断动作有没有生效。 */
    val hash: Long,
    val passwordField: Boolean,
)

object BgScreenReader {

    /**
     * 在指定屏的可编辑控件里写入文字（**不经过系统输入法**）。
     *
     * 为什么不用 IME：后台屏上弹输入法会在**用户的主屏**冒出键盘（输入法窗口跟焦点走），
     * 这是最容易被察觉的打扰。走无障碍 `ACTION_SET_TEXT` 相当于粘贴，零打扰。
     *
     * 目标选择顺序：
     *  1. 该控件本身有输入焦点（FOCUS_INPUT）；
     *  2. 该屏上第一个 editable 控件（提示语/文字内容匹配优先）；
     *  3. 都没有 → 返回 false，调用方提示「先点一下输入框」。
     *
     * @param append true 时在原文字后追加（不覆盖），等价于键盘继续输入
     */
    fun inputText(
        service: com.ai.assistance.quro.service.QuroAccessibilityService,
        displayId: Int,
        text: String,
        hint: String? = null,
        append: Boolean = false,
    ): Boolean {
        if (text.isEmpty() && !append) return false
        val windows: List<android.view.accessibility.AccessibilityWindowInfo> =
            runCatching { service.windowsOn(displayId) }.getOrElse { emptyList() }
        if (windows.isEmpty()) return false

        // 收集该屏上所有可编辑节点（按遍历顺序，也就是视觉上的先后）
        val editables = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        fun walk(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            if (node.isEditable && node.isEnabled) editables += node
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        windows.forEach { w -> walk(w.root, 0) }
        if (editables.isEmpty()) return false

        val target = editables.firstOrNull { it.isFocused }
            ?: editables.firstOrNull { n ->
                hint != null && (
                    n.text?.toString()?.contains(hint) == true ||
                        n.hintText?.toString()?.contains(hint) == true
                    )
            }
            ?: editables.first()

        val finalText = if (append) (target.text?.toString().orEmpty() + text) else text
        val args = android.os.Bundle().apply {
            putCharSequence(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                finalText,
            )
        }
        val ok = runCatching {
            target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
        if (ok) return true

        // 有的自定义 EditText 不认 ACTION_SET_TEXT：退回「聚焦 + 粘贴」
        return runCatching {
            target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
            target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            false
        }.getOrDefault(false)
    }

    /** 该屏上所有可编辑控件数量（供工具在输入失败时给出更准的提示）。 */
    fun countEditable(
        service: com.ai.assistance.quro.service.QuroAccessibilityService,
        displayId: Int,
    ): Int {
        val windows: List<android.view.accessibility.AccessibilityWindowInfo> =
            runCatching { service.windowsOn(displayId) }.getOrElse { emptyList() }
        var n = 0
        fun walk(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            if (node.isEditable && node.isEnabled) n++
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        windows.forEach { walk(it.root, 0) }
        return n
    }

    private const val MIN_SIZE = 5
    private const val MAX_DEPTH = 80

    /** 无名字容器聚合子节点文字时的上限（字符）。 */
    private const val AGGREGATE_MAX = 60

    /** 输入法与无障碍浮层不属于被操作的 App。 */
    private val SKIPPED_TYPES = setOf(
        AccessibilityWindowInfo.TYPE_INPUT_METHOD,
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
        AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY,
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER,
    )

    /** 从上到下排好的、属于界面内容的窗口。 */
    private fun contentWindows(windows: List<AccessibilityWindowInfo>): List<AccessibilityWindowInfo> =
        windows.filter { it.type !in SKIPPED_TYPES }.sortedByDescending { it.layer }

    /** 最上面的应用窗口的包名。 */
    private fun topPackage(windows: List<AccessibilityWindowInfo>): String? =
        contentWindows(windows)
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .firstNotNullOfOrNull { it.root?.packageName?.toString() }

    /**
     * 读一次指定屏。
     *
     * @return 读不到（服务未连接 / 屏号非法）返回 null
     */
    fun read(
        service: QuroAccessibilityService,
        displayId: Int,
        width: Int,
        height: Int,
    ): BgSnapshot? {
        if (displayId < 0 || width <= 0 || height <= 0) return null
        val windows = service.windowsOn(displayId)
        if (windows.isEmpty()) {
            return BgSnapshot(
                displayId = displayId, width = width, height = height, topPackage = null,
                elements = emptyList(), texts = emptyList(), hash = 0L, passwordField = false,
            )
        }

        val content = contentWindows(windows)
        val elements = mutableListOf<BgElement>()
        val texts = mutableListOf<String>()
        val seen = HashSet<String>()
        var hash = FNV_OFFSET
        var password = false
        val rect = Rect()

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH) return
            val visible = node.isVisibleToUser
            if (visible) {
                node.getBoundsInScreen(rect)
                hash = mix(
                    hash, node.className, node.text, node.contentDescription,
                    rect.flattenToString(), states(node),
                )
                if (node.isPassword) password = true

                val own = if (node.isPassword) {
                    node.hintText?.toString()?.trim()?.ifEmpty { null }
                } else {
                    ownLabel(node)
                }
                own?.let { if (texts.size < 200) texts.add(it) }

                clip(rect, width, height)
                val usable = rect.width() >= MIN_SIZE && rect.height() >= MIN_SIZE
                val interactive = node.isClickable || node.isEditable || node.isScrollable

                if (usable && (own != null || interactive)) {
                    val role = roleOf(node)
                    val label = own ?: if (node.isClickable) descendantLabel(node) else ""
                    val key = "$role|$label|${rect.flattenToString()}"
                    if (seen.add(key)) {
                        val ref = "@e${elements.size + 1}"
                        elements.add(
                            BgElement(
                                ref = ref,
                                role = role,
                                name = label,
                                centerX = rect.centerX(),
                                centerY = rect.centerY(),
                                bounds = listOf(
                                    normalize(rect.left, width), normalize(rect.top, height),
                                    normalize(rect.right, width), normalize(rect.bottom, height),
                                ),
                                clickable = node.isClickable,
                                editable = node.isEditable,
                                scrollable = node.isScrollable,
                                checked = node.isChecked,
                                enabled = node.isEnabled,
                                password = node.isPassword,
                                // 输入框总带上 ID：回车前要靠它认出搜索框（名字常常只是推荐词）
                                resourceId = if (label.isEmpty() || node.isEditable) readableResourceId(node.viewIdResourceName) else null,
                                hint = own != null && showingHint(node),
                                focused = node.isFocused,
                                placeholder = if (node.isEditable) {
                                    node.hintText?.toString()?.trim()?.ifEmpty { null }
                                } else {
                                    null
                                },
                            )
                        )
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { visit(it, depth + 1) }
            }
        }

        for (w in content) w.root?.let { visit(it, 0) }

        return BgSnapshot(
            displayId = displayId,
            width = width,
            height = height,
            topPackage = topPackage(windows),
            elements = elements,
            texts = texts.distinct(),
            hash = hash,
            passwordField = password,
        )
    }

    // ─────────── 辅助 ───────────

    private fun clip(rect: Rect, width: Int, height: Int) {
        if (!rect.intersect(0, 0, width, height)) rect.setEmpty()
    }

    private fun normalize(v: Int, max: Int): Int =
        ((v.toLong() * 1000L / max.toLong()).coerceIn(0L, 1000L)).toInt()

    private fun roleOf(n: AccessibilityNodeInfo): String =
        n.className?.toString()?.substringAfterLast('.')?.ifEmpty { null } ?: "View"

    /** 节点自己的名字：文字（提示语也算）→ 内容描述 → hint。 */
    private fun ownLabel(n: AccessibilityNodeInfo): String? {
        val text = n.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) return text
        val desc = n.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty()) return desc
        return n.hintText?.toString()?.trim()?.ifEmpty { null }
    }

    /** 自己没文字时，从子节点里捞文字（图标按钮常见）。多个子文字拼起来，比只取第一个更像个名字。 */
    private fun descendantLabel(n: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        var guard = 0
        var total = 0
        while (queue.isNotEmpty() && guard++ < 40 && total < AGGREGATE_MAX) {
            val c = queue.removeFirst()
            ownLabel(c)?.let { label ->
                if (label !in parts) {
                    parts += label
                    total += label.length + 1
                }
            }
            for (i in 0 until c.childCount) c.getChild(i)?.let { queue.add(it) }
        }
        val joined = parts.joinToString(" ")
        return if (joined.length > AGGREGATE_MAX) joined.take(AGGREGATE_MAX) + "…" else joined
    }

    /** `com.tencent.mm:id/search_src_text` → `search_src_text`；混淆过的短名认不出用途，不给。 */
    private fun readableResourceId(id: String?): String? {
        val short = id?.substringAfterLast('/') ?: return null
        return short.takeIf {
            Regex("^[A-Za-z0-9_]{4,}$").matches(it) && Regex("[A-Za-z]{3}").containsMatchIn(it)
        }
    }

    /**
     * 节点上显示的是提示语（占位文字），而不是用户输入的内容。
     *
     * 三判据，缺一不可地叠加：系统标了 `showingHintText`；或没有实际文字、名字来自提示语；
     * 或报出的文字就是提示语本身（有的输入框不设那个标记）。
     */
    private fun showingHint(n: AccessibilityNodeInfo): Boolean {
        if (n.isShowingHintText) return true
        val hint = n.hintText?.toString()?.trim()
        if (hint.isNullOrEmpty()) return false
        val text = n.text?.toString()?.trim()
        return if (text.isNullOrEmpty()) n.contentDescription.isNullOrBlank() else text == hint
    }

    private fun states(n: AccessibilityNodeInfo): String =
        "${n.isChecked}${n.isSelected}${n.isEnabled}${n.isFocused}" +
            "${n.textSelectionStart}:${n.textSelectionEnd}"

    // FNV-1a 64
    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L

    private fun mix(base: Long, vararg parts: Any?): Long {
        var h = base
        for (p in parts) {
            val s = p?.toString() ?: ""
            for (c in s) {
                h = h xor c.code.toLong()
                h *= FNV_PRIME
            }
            h = h xor 0x1FL
            h *= FNV_PRIME
        }
        return h
    }
}
