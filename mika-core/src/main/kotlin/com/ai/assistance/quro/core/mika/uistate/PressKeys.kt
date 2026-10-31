package com.ai.assistance.quro.core.mika.uistate

/**
 * press_key 只放行导航和编辑键。音量、电源/休眠/唤醒、Home、最近任务、搜索/助手、相机、媒体键
 * 都作用于整台手机而不是 ZorvAI 的屏幕,一律拒绝。外壳进程注入按键时再查一遍同一张表。
 */
object PressKeys {
    val BY_NAME: Map<String, Int> = linkedMapOf(
        "KEYCODE_DPAD_UP" to 19, "KEYCODE_DPAD_DOWN" to 20, "KEYCODE_DPAD_LEFT" to 21, "KEYCODE_DPAD_RIGHT" to 22,
        "KEYCODE_DPAD_CENTER" to 23, "KEYCODE_ENTER" to 66, "KEYCODE_NUMPAD_ENTER" to 160, "KEYCODE_DEL" to 67,
        "KEYCODE_FORWARD_DEL" to 112, "KEYCODE_TAB" to 61, "KEYCODE_SPACE" to 62, "KEYCODE_ESCAPE" to 111,
        "KEYCODE_PAGE_UP" to 92, "KEYCODE_PAGE_DOWN" to 93, "KEYCODE_MOVE_HOME" to 122, "KEYCODE_MOVE_END" to 123,
        "KEYCODE_BACK" to 4, "KEYCODE_MENU" to 82,
    )
    private val CODES: Set<Int> = BY_NAME.values.toSet()

    fun isAllowed(keyCode: Int): Boolean = keyCode in CODES

    /** 名字或数字;不在白名单里返回 null。 */
    fun resolve(key: String): Int? {
        val k = key.trim()
        val code = k.toIntOrNull() ?: BY_NAME[k] ?: return null
        return code.takeIf(::isAllowed)
    }
}
