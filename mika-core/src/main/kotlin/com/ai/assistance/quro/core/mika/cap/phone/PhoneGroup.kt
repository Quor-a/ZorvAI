package com.ai.assistance.quro.core.mika.cap.phone

import com.ai.assistance.quro.core.mika.uistate.ScreenFamily
import com.ai.assistance.quro.core.mika.uistate.tools.ScreenTool
import com.ai.assistance.quro.core.mika.tool.AgentTool

/** 手机能力的 id,也是手机那一套工具名字的前缀。 */
const val PHONE = "phone"

/** 手机读屏的 snapshotId 以它开头(端口的随机编号与稳定编号都是),看编号就知道是手机的。 */
const val PHONE_SNAPSHOT_PREFIX = "p"

/** 手机那一套屏幕工具:经验路线只记手机上的做法。 */
internal val PHONE_FAMILY = ScreenFamily(PHONE, snapshotPrefix = PHONE_SNAPSHOT_PREFIX, routable = true, sandboxCallable = true, ownPrompts = setOf("observe", "back", "scroll", "type_text", "clear_text"))

/** 手机专属的工具。 */
internal interface PhoneTool : AgentTool {
    override val capability: String get() = PHONE
    override val sandboxCallable: Boolean get() = true
}

/**
 * 手机一组:那一套屏幕工具(读屏、看图、点、滑、输入、按键、诊断屏幕),再加列出、打开 App 与链接,
 * 经系统标准接口设闹钟、计时器、日程,和转滚轮。按发给模型的顺序。
 */
internal object PhoneGroup {
    val tools: List<AgentTool> = ScreenTool.of(PHONE_FAMILY) + listOf(
        PhoneQuery, PhoneFind, PhoneExtract, PhoneCollect, PhoneWait, PhoneEventTool,
        PhoneDialog, PhoneGesture, SetWheel, PhoneRun, PhoneCommand, ListApps, LaunchApp, ShareText, OpenLink,
        SetAlarm, SetTimer, ShowAlarms, AddCalendarEvent,
    )
}
