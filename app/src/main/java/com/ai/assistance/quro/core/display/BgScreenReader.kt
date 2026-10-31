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
)

/** 一次读屏的结果。 */
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
) {
    /** 压成一段给模型看的紧凑文本。 */
    fun toPromptText(maxElements: Int = ELEMENT_LIMIT): String {
        val sb = StringBuilder()
        sb.appendLine("后台屏 displayId=$displayId  ${width}x$height")
        topPackage?.let { sb.appendLine("前台包：$it") }
        if (elements.isEmpty()) {
            sb.appendLine("（没有可读控件：可能界面还没渲染完，或该界面禁止无障碍读取，请改用 screenshot）")
        } else {
            sb.appendLine("可操作元素（共 ${elements.size} 个，ref 可直接用于 tapElement）：")
            elements.take(maxElements).forEach { e ->
                val flags = buildList {
                    if (e.clickable) add("可点")
                    if (e.editable) add("可输入")
                    if (e.scrollable) add("可滚")
                    if (e.checked) add("已选")
                    if (!e.enabled) add("禁用")
                    if (e.password) add("密码")
                }.joinToString(",")
                val label = e.name.ifBlank { "(无文字)" }.take(48)
                sb.appendLine("  ${e.ref} ${e.role} \"$label\" @(${e.centerX},${e.centerY}) ${e.bounds}${if (flags.isEmpty()) "" else " [$flags]"}")
            }
            if (elements.size > maxElements) sb.appendLine("  …还有 ${elements.size - maxElements} 个未列出")
        }
        if (texts.isNotEmpty()) {
            sb.appendLine("界面文字：" + texts.take(TEXT_LIMIT).joinToString(" | ").take(1500))
        }
        return sb.toString().trim()
    }

    private companion object {
        const val ELEMENT_LIMIT = 60
        const val TEXT_LIMIT = 40
    }
}

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

    /** 自己没文字时，从子节点里捞一个有文字的（图标按钮常见）。 */
    private fun descendantLabel(n: AccessibilityNodeInfo): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 30) {
            val c = queue.removeFirst()
            ownLabel(c)?.let { return it.take(60) }
            for (i in 0 until c.childCount) c.getChild(i)?.let { queue.add(it) }
        }
        return ""
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
