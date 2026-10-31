package com.ai.assistance.quro.core.mika.userscreen

/** 屏上的一行字:[x]/[y] 是它的中心,和点按同一套 0–999 坐标(左上 0,0)。 */
data class ScreenLine(val text: String, val x: Int, val y: Int)

/**
 * 用户发来的「此刻的屏幕」:双击胶囊或卡片里的截屏按钮时,用户主屏上正看着的那一屏。
 * 和从相册、文件里挑的图片不是一回事:它说的是「我现在在看这个」,带着前台 App、截下的时刻和屏上的字。
 * [packageName]/[appLabel] 是前台 App(读不到为 null);[blocked] 时页面不让截图(防截屏),没有画面,[lines] 也是空的;
 * [lines] 是屏上的字(无障碍树里的,树不够用时补上认出来的字),[fromOcr] 时其中有认出来的字。
 */
data class UserScreen(
    val id: String,
    val takenAt: Long,
    val packageName: String?,
    val appLabel: String?,
    val blocked: Boolean = false,
    val lines: List<ScreenLine> = emptyList(),
    val fromOcr: Boolean = false,
) {
    /** 怎么称呼那个 App:有名字用名字,没有用包名。 */
    val app: String get() = appLabel?.takeIf(String::isNotBlank) ?: packageName ?: UNKNOWN_APP

    companion object {
        const val UNKNOWN_APP = "某个 App"
    }
}

/** 按编号取这个对话里用户发来的屏幕:说明和截图都可能已经不在(读不到返回 null)。 */
interface UserScreenSource {
    suspend fun screen(id: String): UserScreen?

    /** 截下的画面(JPEG 或 PNG);不让截图的页面、读不到时为 null。 */
    suspend fun image(id: String): ByteArray?
}

/** 截下来、还没发出去的一屏:画面([jpeg],不让截图时为 null)和说明([screen],编号在存进对话时才定)。 */
class CapturedScreen(val jpeg: ByteArray?, val screen: UserScreen)
