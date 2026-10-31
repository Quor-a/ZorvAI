package com.ai.assistance.quro.core.mika.phone.shell

import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.ai.assistance.quro.core.mika.uistate.SystemAction

/**
 * 系统动作用的安卓标准 intent:设闹钟、计时器、打开闹钟列表(AlarmClock),打开预填好的新日程(CalendarContract 的插入页)。
 * 先写成与安卓无关的 [Spec](单测直接比),再换成 [Intent]。内置助手只放行这几种([allowed]),不替 ZorvAI 启动任意的 intent。
 */
object SystemIntents {
    /** 日程插入页按类型匹配:直接写明类型,不靠日历提供方去查(查它要日历的可见性)。 */
    const val EVENTS_TYPE = "vnd.android.cursor.dir/event"
    const val EVENTS_URI = "content://${CalendarContract.AUTHORITY}/events"

    /** ZorvAI 的兼容性自检页:内置助手认出这个组件后以 shell 身份直接开到 ZorvAI 的屏上,不按 [allowed] 走。 */
    const val OWN_PAGE_CLASS = "com.ai.assistance.quro.core.mika.selfcheck.SelfCheckTargetActivity"

    /** 能接这个 intent 的一个页面;[system] 是系统自带的 App。 */
    data class Handler(val packageName: String, val className: String, val system: Boolean)

    /**
     * 交给哪一个:只有一个就是它;用户设过默认的([defaultPackage],系统按默认解析出来的包,不是选择器)用默认的;
     * 否则先挑系统自带的,再按先后。直接指定到那个页面:悄悄设好的动作不该在 ZorvAI 的屏上留一个「用哪一个」的选择框。
     * 没有为 null(不支持,模型退回去操作界面)。
     */
    fun choose(handlers: List<Handler>, defaultPackage: String?): Handler? =
        handlers.singleOrNull()
            ?: handlers.firstOrNull { it.packageName == defaultPackage }
            ?: handlers.firstOrNull { it.system }
            ?: handlers.firstOrNull()

    /** 一个 intent 的内容:动作、数据和类型、附加值(只有 Int、Long、Boolean、String、整数列表)。 */
    data class Spec(val action: String, val data: String? = null, val type: String? = null, val extras: Map<String, Any> = emptyMap())

    fun spec(a: SystemAction): Spec = when (a) {
        is SystemAction.SetAlarm -> Spec(AlarmClock.ACTION_SET_ALARM, extras = buildMap {
            put(AlarmClock.EXTRA_HOUR, a.hour)
            put(AlarmClock.EXTRA_MINUTES, a.minute)
            // 不开时钟的界面:直接设好
            put(AlarmClock.EXTRA_SKIP_UI, true)
            a.label?.let { put(AlarmClock.EXTRA_MESSAGE, it) }
            if (a.days.isNotEmpty()) put(AlarmClock.EXTRA_DAYS, ArrayList(a.days))
            a.vibrate?.let { put(AlarmClock.EXTRA_VIBRATE, it) }
        })
        is SystemAction.SetTimer -> Spec(AlarmClock.ACTION_SET_TIMER, extras = buildMap {
            put(AlarmClock.EXTRA_LENGTH, a.seconds)
            put(AlarmClock.EXTRA_SKIP_UI, true)
            a.label?.let { put(AlarmClock.EXTRA_MESSAGE, it) }
        })
        SystemAction.ShowAlarms -> Spec(AlarmClock.ACTION_SHOW_ALARMS)
        is SystemAction.AddCalendarEvent -> Spec(Intent.ACTION_INSERT, EVENTS_URI, EVENTS_TYPE, buildMap {
            put(CalendarContract.Events.TITLE, a.title)
            put(CalendarContract.EXTRA_EVENT_BEGIN_TIME, a.beginMs)
            a.endMs?.let { put(CalendarContract.EXTRA_EVENT_END_TIME, it) }
            if (a.allDay) put(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            a.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            a.description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        })
    }

    /** 内置助手只替 ZorvAI 启动这几种:动作在表里,插入的只能是日程。 */
    fun allowed(action: String?, data: String?): Boolean = when (action) {
        AlarmClock.ACTION_SET_ALARM, AlarmClock.ACTION_SET_TIMER, AlarmClock.ACTION_SHOW_ALARMS -> data == null
        Intent.ACTION_INSERT -> data == EVENTS_URI
        else -> false
    }

    fun toIntent(s: Spec): Intent {
        val i = Intent(s.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        when {
            s.data != null && s.type != null -> i.setDataAndType(Uri.parse(s.data), s.type)
            s.data != null -> i.data = Uri.parse(s.data)
            s.type != null -> i.type = s.type
        }
        for ((k, v) in s.extras) when (v) {
            is Int -> i.putExtra(k, v)
            is Long -> i.putExtra(k, v)
            is Boolean -> i.putExtra(k, v)
            is String -> i.putExtra(k, v)
            is ArrayList<*> -> i.putIntegerArrayListExtra(k, ArrayList(v.filterIsInstance<Int>()))
            else -> error("unsupported extra $k")
        }
        return i
    }
}
