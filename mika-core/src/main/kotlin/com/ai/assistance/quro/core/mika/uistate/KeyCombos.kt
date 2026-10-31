package com.ai.assistance.quro.core.mika.uistate

/** 一个键在协议里的写法;[androidKeyCode] 是没有 DevTools 时走原生按键用的键码。 */
data class CdpKey(val key: String, val code: String, val keyCode: Int, val text: String?, val androidKeyCode: Int)

enum class KeyModifier(val bit: Int, val key: CdpKey, val androidMeta: Int) {
    Alt(1, CdpKey("Alt", "AltLeft", 18, null, 57), 0x02 or 0x10),
    Control(2, CdpKey("Control", "ControlLeft", 17, null, 113), 0x1000 or 0x2000),
    Meta(4, CdpKey("Meta", "MetaLeft", 91, null, 117), 0x10000 or 0x20000),
    Shift(8, CdpKey("Shift", "ShiftLeft", 16, null, 59), 0x01 or 0x40),
}

/** 组合键,如 Control+A:修饰键按 [modifiers] 的顺序按下、倒序抬起。 */
data class KeyCombo(val modifiers: List<KeyModifier>, val key: CdpKey) {
    val bits: Int get() = modifiers.fold(0) { acc, m -> acc or m.bit }
    val androidMeta: Int get() = modifiers.fold(0) { acc, m -> acc or m.androidMeta }

    /** 这次按键实际产生的字:带 Ctrl/Alt/Meta 时不出字(是快捷键),带 Shift 时字母大写。 */
    val text: String? get() = when {
        modifiers.any { it != KeyModifier.Shift } -> null
        KeyModifier.Shift in modifiers && key.text != null && key.text.length == 1 && key.text[0].isLetter() -> key.text.uppercase()
        else -> key.text
    }

    override fun toString() = (modifiers.map { it.name } + key.key).joinToString("+")
}

object CdpKeys {
    private val NAMED: Map<String, CdpKey> = linkedMapOf(
        "Enter" to CdpKey("Enter", "Enter", 13, "\r", 66),
        "Tab" to CdpKey("Tab", "Tab", 9, null, 61),
        "Backspace" to CdpKey("Backspace", "Backspace", 8, null, 67),
        "Delete" to CdpKey("Delete", "Delete", 46, null, 112),
        "Escape" to CdpKey("Escape", "Escape", 27, null, 111),
        "Space" to CdpKey(" ", "Space", 32, " ", 62),
        "ArrowUp" to CdpKey("ArrowUp", "ArrowUp", 38, null, 19),
        "ArrowDown" to CdpKey("ArrowDown", "ArrowDown", 40, null, 20),
        "ArrowLeft" to CdpKey("ArrowLeft", "ArrowLeft", 37, null, 21),
        "ArrowRight" to CdpKey("ArrowRight", "ArrowRight", 39, null, 22),
        "Home" to CdpKey("Home", "Home", 36, null, 122),
        "End" to CdpKey("End", "End", 35, null, 123),
        "PageUp" to CdpKey("PageUp", "PageUp", 33, null, 92),
        "PageDown" to CdpKey("PageDown", "PageDown", 34, null, 93),
    )
    private val ALIASES = mapOf("return" to "Enter", "esc" to "Escape", "del" to "Delete", "up" to "ArrowUp", "down" to "ArrowDown",
        "left" to "ArrowLeft", "right" to "ArrowRight", " " to "Space")
    private val MODIFIERS = mapOf("control" to KeyModifier.Control, "ctrl" to KeyModifier.Control, "shift" to KeyModifier.Shift,
        "alt" to KeyModifier.Alt, "option" to KeyModifier.Alt, "meta" to KeyModifier.Meta, "cmd" to KeyModifier.Meta, "command" to KeyModifier.Meta)
    private val BY_LOWER = NAMED.mapKeys { it.key.lowercase() }

    /** 小键盘回车和回车同义。 */
    private const val ANDROID_NUMPAD_ENTER = 160

    /** 键名(不分大小写):Enter、Tab、Escape、方向键、Home/End、翻页、Space、单个字母或数字。 */
    fun named(name: String): CdpKey? {
        val n = if (name == " ") name else name.trim().takeIf { it.isNotEmpty() } ?: return null
        if (n.length == 1) {
            val c = n[0]
            if (c in 'a'..'z' || c in 'A'..'Z') {
                val up = c.uppercaseChar()
                return CdpKey(c.lowercase(), "Key$up", up.code, c.lowercase(), 29 + (up - 'A'))
            }
            if (c in '0'..'9') return CdpKey(n, "Digit$n", c.code, n, 7 + (c - '0'))
        }
        return BY_LOWER[(ALIASES[n.lowercase()] ?: n).lowercase()]
    }

    /** press_key 的安卓键码对应的键;没有对应(不该交给网页的键)为 null。 */
    fun forAndroid(keyCode: Int): CdpKey? =
        if (keyCode == ANDROID_NUMPAD_ENTER) NAMED.getValue("Enter").copy(code = "NumpadEnter", androidKeyCode = ANDROID_NUMPAD_ENTER)
        else NAMED.values.firstOrNull { it.androidKeyCode == keyCode }

    /** 「Control+A」「Shift+Tab」「Enter」;修饰键重复、没有主键、主键不认识时为 null。 */
    fun parseCombo(spec: String): KeyCombo? {
        val parts = spec.trim().split('+').map(String::trim)
        if (parts.isEmpty() || parts.any { it.isEmpty() }) return null
        val mods = parts.dropLast(1).map { MODIFIERS[it.lowercase()] ?: return null }
        if (mods.toSet().size != mods.size) return null
        val key = named(parts.last()) ?: return null
        return KeyCombo(mods, key)
    }
}
