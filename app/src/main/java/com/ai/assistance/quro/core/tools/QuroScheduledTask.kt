package com.ai.assistance.quro.core.tools

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.ai.assistance.quro.activity.QuroMainActivity
import com.ai.assistance.quro.activity.QuroReminderActivity
import com.ai.assistance.quro.ui.QuroChatViewModel

/** 定时任务「完成推送」独立协程作用域：不随 BroadcastReceiver 销毁，app 生命周期内常驻。 */
private val scheduleCompletionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// ============================================================
// 数据模型
// ============================================================

/** 重复类型 */
enum class TaskRepeatType(val label: String) {
    ONCE("仅一次"),
    DAILY("每天"),
    WEEKLY("每周"),
    BIWEEKLY("每双周"),
    MONTHLY("每月"),
    YEARLY("每年");
}

/**
 * 定时任务 / 自动化提醒数据模型（对齐 WorkBuddy automation_update 语义）：
 * - scheduleType: "once"（仅一次）/ "recurring"（重复）
 * - scheduledAt:  ISO 8601 本地时间 "yyyy-MM-dd HH:mm"；once 的触发时刻、recurring 的起始锚点
 * - rrule:        RFC 5545 重复规则，如 FREQ=DAILY / FREQ=WEEKLY;BYDAY=MO,WE,FR /
 *                FREQ=MONTHLY;BYMONTHDAY=1 / FREQ=YEARLY;BYMONTH=1;BYMONTHDAY=1
 * - content:      触发时写入会话、由 AI 执行的指令（= prompt）；留空则仅弹通知
 * - cwds:         目标会话 id（可选；留空→沿用绑定/默认会话）
 * 旧版 hour/minute/repeatType/dayOfWeek/dayOfMonth/month 仅用于「无 rrule 且无 scheduledAt」的旧任务兼容回退，新任务不再写入。
 */
data class QuroScheduledTask(
    val id: String,
    val title: String,
    val content: String = "",
    val scheduleType: String = "recurring",
    val scheduledAt: String = "",
    val rrule: String = "",
    val cwds: String = "",
    val autoNew: Boolean = false,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastTriggered: Long = 0L,
    // —— 兼容旧版：rrule/scheduledAt 均空时回退此逻辑（新任务不再写入这些值）——
    val repeatType: TaskRepeatType = TaskRepeatType.ONCE,
    val hour: Int = 8,
    val minute: Int = 0,
    val dayOfWeek: Int = 1,
    val dayOfMonth: Int = 1,
    val month: Int = 1,
    /** 重复任务的结束日期（yyyy-MM-dd，可选；留空=永久重复）。达到该日期后不再排程。 */
    val endAt: String = "",
)

// ============================================================
// 持久化
// ============================================================

/** 定时任务持久化（SharedPreferences，JSON 数组）。 */
object QuroScheduledTaskStore {
    private const val PREFS = "quro_scheduled_tasks"
    private const val KEY = "tasks"

    fun load(context: Context): List<QuroScheduledTask> {
        val out = mutableListOf<QuroScheduledTask>()
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    QuroScheduledTask(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        title = o.optString("title", ""),
                        content = o.optString("content", ""),
                        scheduleType = o.optString("scheduleType", if (o.optString("repeatType", "ONCE") == "ONCE" && o.optString("rrule", "").isBlank() && o.optString("scheduledAt", "").isBlank()) "once" else "recurring"),
                        scheduledAt = o.optString("scheduledAt", ""),
                        rrule = o.optString("rrule", ""),
                        cwds = o.optString("cwds", ""),
                        autoNew = o.optBoolean("autoNew", false),
                        hour = o.optInt("hour", 8),
                        minute = o.optInt("minute", 0),
                        repeatType = runCatching {
                            TaskRepeatType.valueOf(o.optString("repeatType", "ONCE"))
                        }.getOrDefault(TaskRepeatType.ONCE),
                        dayOfWeek = o.optInt("dayOfWeek", 1),
                        dayOfMonth = o.optInt("dayOfMonth", 1),
                        month = o.optInt("month", 1),
                        endAt = o.optString("endAt", ""),
                        enabled = o.optBoolean("enabled", true),
                        createdAt = o.optLong("createdAt", 0L),
                        lastTriggered = o.optLong("lastTriggered", 0L),
                    )
                )
            }
        }
        return out.sortedBy { it.hour * 60 + it.minute }
    }

    fun save(context: Context, list: List<QuroScheduledTask>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { t ->
                arr.put(
                    JSONObject().apply {
                        put("id", t.id)
                        put("title", t.title)
                        put("content", t.content)
                        put("scheduleType", t.scheduleType)
                        put("scheduledAt", t.scheduledAt)
                        put("rrule", t.rrule)
                        put("cwds", t.cwds)
                        put("autoNew", t.autoNew)
                        put("hour", t.hour)
                        put("minute", t.minute)
                        put("repeatType", t.repeatType.name)
                        put("dayOfWeek", t.dayOfWeek)
                        put("dayOfMonth", t.dayOfMonth)
                        put("month", t.month)
                        put("endAt", t.endAt)
                        put("enabled", t.enabled)
                        put("createdAt", t.createdAt)
                        put("lastTriggered", t.lastTriggered)
                    }
                )
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply()
        }
    }

    fun addOrUpdate(context: Context, task: QuroScheduledTask) {
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.id == task.id }
        if (idx >= 0) list[idx] = task else list.add(task)
        save(context, list)
    }

    fun remove(context: Context, id: String) {
        save(context, load(context).filter { it.id != id })
    }

    fun updateLastTriggered(context: Context, id: String) {
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(lastTriggered = System.currentTimeMillis())
            save(context, list)
        }
    }
}

// ============================================================
// 调度器
// ============================================================

/** 定时任务调度器（AlarmManager 精确闹钟 + 自动重新排程）。 */
object QuroScheduledTaskScheduler {
    private const val TAG = "QuroScheduler"
    private const val CHANNEL_ID = "quro_scheduled_task"
    const val ACTION_TRIGGER = "com.ai.assistance.quro.SCHEDULED_TASK_TRIGGER"
    const val EXTRA_TASK_ID = "task_id"

    /** 排程所有启用的任务 */
    fun scheduleAll(context: Context) {
        // 包 runCatching 防止启动期（Application.onCreate / BootReceiver）排程崩溃拖垮应用
        runCatching {
            val tasks = QuroScheduledTaskStore.load(context).filter { it.enabled }
            tasks.forEach { schedule(context, it) }
            Log.i(TAG, "已排程 ${tasks.size} 个定时任务")
        }.onFailure { e ->
            Log.w(TAG, "scheduleAll 失败: ${e.message}")
        }
    }

    /** 排程单个任务 */
    fun schedule(context: Context, task: QuroScheduledTask) {
        if (!task.enabled) {
            cancel(context, task.id)
            return
        }
        val triggerAt = nextTriggerTime(task) ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, QuroScheduleReceiver::class.java).apply {
            action = ACTION_TRIGGER
            putExtra(EXTRA_TASK_ID, task.id)
        }
        // 使用唯一的 requestCode（取 id 的 hashCode）
        val pi = PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 精确闹钟在 Android 12+ 需要 SCHEDULE_EXACT_ALARM 权限，否则抛 SecurityException；
        // 无权限时降级为 setWindow（非精确窗口），保证不崩溃且仍能在目标时间附近触发。
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 10 * 60 * 1000L, pi)
            }
        }.onFailure { e ->
            Log.w(TAG, "任务[${task.title}] 精确排程失败，降级窗口闹钟: ${e.message}")
            // 兜底再试一次窗口闹钟（极端情况下 setWindow 也可能因 PendingIntent 等问题抛错）
            runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 10 * 60 * 1000L, pi) }
        }
        Log.i(TAG, "任务[${task.title}] 下次触发: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(triggerAt))}")
    }

    /** 取消任务排程 */
    fun cancel(context: Context, taskId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, QuroScheduleReceiver::class.java).apply {
            action = ACTION_TRIGGER
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }

    /**
     * 计算下次触发时间（毫秒时间戳）；无法计算返回 null。
     * 优先级：rrule（新模型）> once(scheduledAt) > 旧版 calendar 模型。
     */
    fun nextTriggerTime(task: QuroScheduledTask): Long? {
        val now = System.currentTimeMillis()
        // —— 一次性优先：明确 once 即只触发一次（即使误带 rrule 也忽略），避免被当成重复无限重排 ——
        if (task.scheduleType == "once") {
            val at = if (task.scheduledAt.isNotBlank()) parseLocal(task.scheduledAt) else null
            if (at == null) return null
            // 略过期（1 分钟内）仍立即触发，避免错过提醒；过久则视为过期跳过
            return if (at >= now - 60_000L) at else null
        }
        // —— 新模型：RRULE 重复 ——
        if (task.rrule.isNotBlank()) {
            val anchor = if (task.scheduledAt.isNotBlank()) parseLocal(task.scheduledAt) ?: now else now
            val next = nextRruleOccurrence(task.rrule, anchor) ?: return null
            // —— 结束日期截断：超过 endAt 则不再排程（重复任务终止机制）——
            if (task.endAt.isNotBlank()) {
                val endMs = parseLocal(task.endAt) ?: parseLocal("${task.endAt} 23:59")
                if (endMs != null && next > endMs) return null
            }
            return next
        }
        // —— 兼容旧版 calendar 模型 ——
        return nextTriggerLegacy(task)
    }

    /** 旧版 hour/minute/repeatType 模型的下次触发（供无 rrule 旧任务回退）。 */
    private fun nextTriggerLegacy(task: QuroScheduledTask): Long? {
        val now = Calendar.getInstance()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, task.hour)
            set(Calendar.MINUTE, task.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        when (task.repeatType) {
            TaskRepeatType.ONCE -> {
                if (cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            TaskRepeatType.DAILY -> {
                while (cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            TaskRepeatType.WEEKLY -> {
                val d = if (task.dayOfWeek == 7) Calendar.SUNDAY else task.dayOfWeek + 1
                cal.set(Calendar.DAY_OF_WEEK, d)
                while (cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.WEEK_OF_YEAR, 1)
            }
            TaskRepeatType.BIWEEKLY -> {
                val d = if (task.dayOfWeek == 7) Calendar.SUNDAY else task.dayOfWeek + 1
                cal.set(Calendar.DAY_OF_WEEK, d)
                while (cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.WEEK_OF_YEAR, 2)
            }
            TaskRepeatType.MONTHLY -> {
                cal.set(Calendar.DAY_OF_MONTH, minOf(task.dayOfMonth, cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
                while (cal.timeInMillis <= now.timeInMillis) {
                    cal.add(Calendar.MONTH, 1)
                    cal.set(Calendar.DAY_OF_MONTH, minOf(task.dayOfMonth, cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
                }
            }
            TaskRepeatType.YEARLY -> {
                cal.set(Calendar.MONTH, task.month - 1)
                cal.set(Calendar.DAY_OF_MONTH, minOf(task.dayOfMonth, cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
                while (cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.YEAR, 1)
            }
        }
        return cal.timeInMillis
    }

    /** 解析本地时间 "yyyy-MM-dd HH:mm"（兼容 "yyyy-MM-dd'T'HH:mm"）→ 毫秒。失败返回 null。 */
    fun parseLocal(s: String): Long? = runCatching {
        val t = s.trim()
        val fmt = if (t.contains("T")) SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
        else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        fmt.parse(t)?.time
    }.getOrNull()

    /** 把旧版参数转换为 RRULE 字符串（供 AI 工具兼容旧入参）。 */
    fun rruleFromLegacy(hour: Int, minute: Int, repeatType: String, dayOfWeek: Int = 1, dayOfMonth: Int = 1, month: Int = 1): String {
        return when (repeatType.uppercase()) {
            "DAILY" -> "FREQ=DAILY"
            "WEEKLY" -> "FREQ=WEEKLY;BYDAY=${legacyDayToRrule(dayOfWeek)}"
            "BIWEEKLY" -> "FREQ=WEEKLY;INTERVAL=2;BYDAY=${legacyDayToRrule(dayOfWeek)}"
            "MONTHLY" -> "FREQ=MONTHLY;BYMONTHDAY=$dayOfMonth"
            "YEARLY" -> "FREQ=YEARLY;BYMONTH=$month;BYMONTHDAY=$dayOfMonth"
            else -> "FREQ=DAILY"
        }
    }

    private fun legacyDayToRrule(d: Int): String = when (d) {
        1 -> "MO"; 2 -> "TU"; 3 -> "WE"; 4 -> "TH"; 5 -> "FR"; 6 -> "SA"; else -> "SU"
    }

    /**
     * 计算 RRULE 的下一个发生时刻（>= fromInclusive 且 >= now）。
     * 支持 FREQ ∈ {DAILY,WEEKLY,MONTHLY,YEARLY}，可选 INTERVAL / BYDAY / BYMONTHDAY / BYMONTH。
     * 采用「逐日推进 + 频率过滤 + interval 对齐」算法，覆盖常见自动化场景且不易出错。
     */
    fun nextRruleOccurrence(rrule: String, fromInclusive: Long): Long? {
        val p = rrule.split(";").mapNotNull { kv ->
            val i = kv.indexOf("=")
            if (i < 0) null else kv.substring(0, i).trim().uppercase() to kv.substring(i + 1).trim()
        }.toMap()
        val freq = p["FREQ"] ?: return null
        val interval = (p["INTERVAL"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val byDay = p["BYDAY"]?.split(",")?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() } ?: emptyList()
        val byMonthDay = p["BYMONTHDAY"]?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        val byMonth = p["BYMONTH"]?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        val anchorCal = Calendar.getInstance().apply { timeInMillis = fromInclusive }
        val anchorWeekday = toRruleDay(anchorCal.get(Calendar.DAY_OF_WEEK))
        val anchorMonthDay = anchorCal.get(Calendar.DAY_OF_MONTH)
        val anchorMonth = anchorCal.get(Calendar.MONTH) + 1
        val cal = Calendar.getInstance().apply {
            timeInMillis = fromInclusive
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val now = System.currentTimeMillis()
        var matched = 0
        repeat(4000) {
            if (matchesRrule(cal, freq, byDay, byMonthDay, byMonth, anchorWeekday, anchorMonthDay, anchorMonth)) {
                if (cal.timeInMillis >= now) {
                    if (matched % interval == 0) return cal.timeInMillis
                    matched++
                } else {
                    matched++ // 历史匹配也计入 interval 对齐，保证周期间隔稳定
                }
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    private fun matchesRrule(
        cal: Calendar, freq: String,
        byDay: List<String>, byMonthDay: List<Int>, byMonth: List<Int>,
        anchorWeekday: String, anchorMonthDay: Int, anchorMonth: Int,
    ): Boolean = when (freq) {
        "DAILY" -> true
        "WEEKLY" -> {
            val days = if (byDay.isEmpty()) listOf(anchorWeekday) else byDay
            toRruleDay(cal.get(Calendar.DAY_OF_WEEK)) in days
        }
        "MONTHLY" -> {
            val days = if (byMonthDay.isEmpty()) listOf(anchorMonthDay) else byMonthDay
            cal.get(Calendar.DAY_OF_MONTH) in days
        }
        "YEARLY" -> {
            val months = if (byMonth.isEmpty()) listOf(anchorMonth) else byMonth
            val days = if (byMonthDay.isEmpty()) listOf(anchorMonthDay) else byMonthDay
            cal.get(Calendar.MONTH) + 1 in months && cal.get(Calendar.DAY_OF_MONTH) in days
        }
        else -> false
    }

    private fun toRruleDay(calDay: Int): String = when (calDay) {
        Calendar.MONDAY -> "MO"; Calendar.TUESDAY -> "TU"; Calendar.WEDNESDAY -> "WE"
        Calendar.THURSDAY -> "TH"; Calendar.FRIDAY -> "FR"; Calendar.SATURDAY -> "SA"
        else -> "SU"
    }

    /** 把 RRULE 转成人话（用于 UI 展示）。 */
    fun humanRrule(rrule: String): String {
        val p = rrule.split(";").mapNotNull { kv ->
            val i = kv.indexOf("="); if (i < 0) null else kv.substring(0, i).trim().uppercase() to kv.substring(i + 1).trim()
        }.toMap()
        val freq = p["FREQ"] ?: return rrule
        val interval = p["INTERVAL"]?.toIntOrNull() ?: 1
        return when (freq) {
            "DAILY" -> if (interval > 1) "每${interval}天" else "每天"
            "WEEKLY" -> {
                val days = p["BYDAY"]?.split(",")?.map { rruleDayLabel(it.trim()) }?.filter { it.isNotBlank() }
                if (days.isNullOrEmpty()) "每周" else "每${if (interval > 1) interval else ""}周${days.joinToString("、")}"
            }
            "MONTHLY" -> {
                val d = p["BYMONTHDAY"]?.toIntOrNull()
                "每月${d ?: ""}号"
            }
            "YEARLY" -> {
                val m = p["BYMONTH"]?.toIntOrNull(); val d = p["BYMONTHDAY"]?.toIntOrNull()
                "每年${if (m != null) "${m}月" else ""}${if (d != null) "${d}号" else ""}"
            }
            else -> rrule
        }
    }

    private fun rruleDayLabel(d: String): String = when (d.uppercase()) {
        "MO" -> "一"; "TU" -> "二"; "WE" -> "三"; "TH" -> "四"; "FR" -> "五"; "SA" -> "六"; "SU" -> "日"
        else -> ""
    }

    /** 创建通知渠道 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "定时任务提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Zorv AI 定时任务到时提醒"
                }
                nm.createNotificationChannel(channel)
            }
        }
    }
}

// ============================================================
// 广播接收器
// ============================================================

/** 定时任务触发接收器：写入目标会话自动执行 + 发通知（点击回应用）+ 重新排程下一次 */
class QuroScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(QuroScheduledTaskScheduler.EXTRA_TASK_ID) ?: return
        val tasks = QuroScheduledTaskStore.load(context)
        val task = tasks.find { it.id == taskId } ?: return

        // 1) 自动执行：把任务内容作为一条用户消息写入目标会话，并真正触发 AI 执行。
        //    仅当对话 ViewModel 已就绪（应用在前台/最近后台）时执行；若应用未运行，
        //    instance 尚未初始化 → 退化为仅通知，点击通知进入应用（最小可行方案）。
        runCatching {
            val vm = QuroChatViewModel.instance
            // 记录本次任务内容写入的会话 id（用于 AI 处理完成后补发「已完成」通知）
            var targetConvId: String? = null
            if (task.autoNew) {
                // 自动新建会话：每次触发都开一个独立会话，避免污染当前/历史对话
                vm.newConversation(); targetConvId = vm.currentId.value
            } else if (task.cwds.isNotBlank() && vm.conversations.value.any { it.id == task.cwds }) {
                // 指定目标会话（cwds）：直接定位，保证提醒落在用户设定的对话里
                vm.selectConversation(task.cwds); targetConvId = task.cwds
            } else if (vm.currentId.value.isBlank()) {
                // 没有当前会话：优先切到最近更新的会话，否则新建一个，避免误写空会话
                val recent = vm.conversations.value.maxByOrNull { it.updatedAt }?.id
                if (recent != null) { vm.selectConversation(recent); targetConvId = recent }
                else { vm.newConversation(); targetConvId = vm.currentId.value }
            } else {
                targetConvId = vm.currentId.value
            }
            if (task.content.isNotBlank()) {
                vm.send(task.content)
                // B3 完成推送：AI 处理完成后（对应会话 busy 由 true 转 false）补发「已完成」通知
                val cid = targetConvId
                if (cid != null) notifyTaskCompletion(context, task, cid)
            }
        }.onFailure { e ->
            Log.w("QuroScheduler", "定时任务[${task.title}] 自动执行失败: ${e.message}")
        }

        // 2) 通知：息屏/锁屏时以全屏提醒 Activity 弹出（覆盖锁屏之上 + 自动亮屏）；
        //    亮屏/解锁状态则退化为 heads-up 弹窗，点击回到应用（便于查看/补执行）。
        QuroScheduledTaskScheduler.ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val contentIntent = PendingIntent.getActivity(
            context,
            taskId.hashCode(),
            Intent(context, QuroMainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 全屏提醒意图：息屏/锁屏到达时由系统拉起 QuroReminderActivity 覆盖锁屏展示
        val reminderIntent = PendingIntent.getActivity(
            context,
            (taskId + "_fs").hashCode(),
            Intent(context, QuroReminderActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(QuroReminderActivity.EXTRA_TITLE, task.title)
                putExtra(QuroReminderActivity.EXTRA_TEXT, task.content.ifBlank { "定时任务提醒" })
                putExtra(QuroReminderActivity.EXTRA_BADGE, "Zorv AI 定时提醒")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, "quro_scheduled_task")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(task.title)
            .setContentText(task.content.ifBlank { "定时任务提醒" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setFullScreenIntent(reminderIntent, true)
            .build()
        nm.notify(taskId.hashCode(), notif)

        // 3) 更新最后触发时间
        QuroScheduledTaskStore.updateLastTriggered(context, taskId)

        // 4) 排程下一次 / 终止任务
        if (task.scheduleType == "once") {
            // 一次性任务触发后即终止：取消闹钟并从列表移除，避免「任务还在继续」
            QuroScheduledTaskScheduler.cancel(context, task.id)
            QuroScheduledTaskStore.remove(context, task.id)
        } else if (task.enabled) {
            // 重复任务：检查是否已到达结束日期；到期则停排并禁用
            val next = QuroScheduledTaskScheduler.nextTriggerTime(task)
            if (next == null) {
                QuroScheduledTaskStore.addOrUpdate(context, task.copy(enabled = false))
            } else {
                QuroScheduledTaskScheduler.schedule(context, task)
            }
        }
    }
}

/**
 * B3 完成推送：定时任务触发 AI 执行后，轮询目标会话的生成状态，
 * 待 busy 由 true 转 false（AI 处理完成）后，补发一条「定时任务已完成」通知。
 *
 * 仅当应用进程常驻（vm.instance 可用）时生效；应用未运行则退化为仅触发通知（见 onReceive 注释）。
 * 超时（默认 6 分钟）或从未进入生成态（如未配置 API Key、内容为空）则静默跳过，不重复打扰。
 */
private fun notifyTaskCompletion(context: Context, task: QuroScheduledTask, targetConvId: String) {
    scheduleCompletionScope.launch {
        try {
            val deadline = System.currentTimeMillis() + 6 * 60_000L
            var sawBusy = false
            while (System.currentTimeMillis() < deadline) {
                if (QuroChatViewModel.instance.isBusy(targetConvId)) sawBusy = true
                else if (sawBusy) break   // 曾进入生成态且现已结束 → 完成
                delay(700)
            }
            if (!sawBusy) return@launch   // 未真正执行（无 Key / 空内容 / 被占忙），不补发
            QuroScheduledTaskScheduler.ensureChannel(context)
            val nm = context.getSystemService(NotificationManager::class.java)
            val piId = (task.id + "_done").hashCode()
            val contentIntent = PendingIntent.getActivity(
                context, piId,
                Intent(context, QuroMainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val doneReminderIntent = PendingIntent.getActivity(
                context, (task.id + "_done_fs").hashCode(),
                Intent(context, QuroReminderActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(QuroReminderActivity.EXTRA_TITLE, "定时任务已完成")
                    putExtra(QuroReminderActivity.EXTRA_TEXT, "「${task.title}」AI 已处理完成，点击查看回复")
                    putExtra(QuroReminderActivity.EXTRA_BADGE, "Zorv AI 定时提醒")
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(context, "quro_scheduled_task")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("✅ 定时任务已完成")
                .setContentText("「${task.title}」AI 已处理完成，点击查看回复")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setFullScreenIntent(doneReminderIntent, true)
                .build()
            nm.notify(piId, notif)
        } catch (_: Throwable) { /* 完成推送失败不影响主流程 */ }
    }
}

/** 开机后重新排程所有定时任务 */
class QuroScheduleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.MY_PACKAGE_REPLACED" ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            QuroScheduledTaskScheduler.scheduleAll(context)
        }
    }
}

// ============================================================
// AI 工具
// ============================================================

/** 创建定时任务（AI 可调用，对齐 WorkBuddy automation_update 语义） */
class ScheduleTaskTool : QuroTool {
    override val name = "schedule_task"
    override val description = "创建一条定时任务/自动化提醒（对齐 WorkBuddy 自动化）。参数：{\"title\":\"晨会提醒\",\"prompt\":\"该开晨会了\",\"scheduleType\":\"recurring|once\",\"scheduledAt\":\"2026-07-27 09:30\",\"rrule\":\"FREQ=DAILY 或 FREQ=WEEKLY;BYDAY=MO,WE,FR 或 FREQ=MONTHLY;BYMONTHDAY=1 或 FREQ=YEARLY;BYMONTH=1;BYMONTHDAY=1\",\"cwds\":\"<目标会话id,可选>\",\"hour\":9,\"minute\":30,\"repeatType\":\"DAILY\",\"dayOfWeek\":1,\"dayOfMonth\":1,\"month\":1}。优先用 scheduleType+rrule；若只给了旧式 hour/minute/repeatType，会自动换算成 rrule。scheduleType=once 时 scheduledAt 为触发时刻；recurring 时 scheduledAt 可空（用当前时间作锚点）。prompt 为触发时写入会话让 AI 执行的指令，可留空（仅提醒）。autoNew:true 表示每次触发自动新建独立会话（优先级高于 cwds）。endAt:\"2026-08-01\" 为重复任务的结束日期（可选，yyyy-MM-dd），到达后自动停止重复（仅 recurring 生效）。"
    override val parametersJson = """{"type":"object","properties":{"title":{"type":"string","description":"任务标题"},"prompt":{"type":"string","description":"触发时写入会话让 AI 执行的指令（内容），可留空"},"scheduleType":{"type":"string","description":"recurring(重复)/once(仅一次)"},"scheduledAt":{"type":"string","description":"触发时刻 yyyy-MM-dd HH:mm（once 必填）"},"rrule":{"type":"string","description":"RRULE 重复规则，如 FREQ=DAILY / FREQ=WEEKLY;BYDAY=MO,WE,FR / FREQ=MONTHLY;BYMONTHDAY=1 / FREQ=YEARLY;BYMONTH=1;BYMONTHDAY=1"},"cwds":{"type":"string","description":"目标会话 id（可选，留空则沿用绑定/默认会话）"},"autoNew":{"type":"boolean","description":"true=每次触发自动新建独立会话(不与cwds同用)"},"hour":{"type":"integer","description":"小时0-23(旧式兼容)"},"minute":{"type":"integer","description":"分钟0-59(旧式兼容)"},"repeatType":{"type":"string","description":"旧式重复类型 ONCE/DAILY/WEEKLY/BIWEEKLY/MONTHLY/YEARLY(兼容用)"},"dayOfWeek":{"type":"integer","description":"周几1-7(周一至周日，旧式 WEEKLY 用)"},"dayOfMonth":{"type":"integer","description":"几号1-31(旧式 MONTHLY/YEARLY 用)"},"month":{"type":"integer","description":"几月1-12(旧式 YEARLY 用)"},"endAt":{"type":"string","description":"重复任务结束日期 yyyy-MM-dd（可选，留空=永久重复，仅 recurring 生效）"}},"required":["title"]}"""
    override val requiredPermissions = listOf(android.Manifest.permission.POST_NOTIFICATIONS)
    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val title = jo.optString("title", "")
        if (title.isBlank()) return "title 不能为空"
        val scheduleType = jo.optString("scheduleType",
            if (jo.optString("rrule", "").isNotBlank()) "recurring" else "once"
        ).lowercase()
        val content = jo.optString("prompt", jo.optString("content", ""))
        val cwds = jo.optString("cwds", "").trim()
        val autoNew = jo.optBoolean("autoNew", false)

        // rrule：优先用新字段；否则用旧式 hour/minute/repeatType 换算
        val rrule = jo.optString("rrule", "").trim().ifBlank {
            val rt = jo.optString("repeatType", "")
            if (rt.isNotBlank()) QuroScheduledTaskScheduler.rruleFromLegacy(
                jo.optInt("hour", 8), jo.optInt("minute", 0), rt,
                jo.optInt("dayOfWeek", 1), jo.optInt("dayOfMonth", 1), jo.optInt("month", 1)
            ) else ""
        }

        // scheduledAt：优先用新字段；否则按类型兜底构造
        val scheduledAt = jo.optString("scheduledAt", "").trim().ifBlank {
            if (scheduleType == "once") {
                val h = jo.optInt("hour", -1); val m = jo.optInt("minute", -1)
                if (h in 0..23 && m in 0..59) {
                    val c = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(c.timeInMillis))
                } else ""
            } else {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis()))
            }
        }

        if (scheduleType == "once" && scheduledAt.isBlank()) {
            return "scheduleType=once 时必须提供 scheduledAt（或 hour/minute）"
        }

        val task = QuroScheduledTask(
            id = UUID.randomUUID().toString(),
            title = title,
            content = content,
            scheduleType = scheduleType,
            scheduledAt = scheduledAt,
            rrule = rrule,
            cwds = cwds,
            autoNew = autoNew,
            enabled = true,
            endAt = if (scheduleType == "recurring") jo.optString("endAt", "").trim() else "",
        )
        QuroScheduledTaskStore.addOrUpdate(context, task)
        QuroScheduledTaskScheduler.ensureChannel(context)
        QuroScheduledTaskScheduler.schedule(context, task)
        val next = QuroScheduledTaskScheduler.nextTriggerTime(task)
        val nextStr = next?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it)) } ?: "未知"
        val ruleStr = if (scheduleType == "once") "一次性 @ $scheduledAt"
        else QuroScheduledTaskScheduler.humanRrule(rrule).takeIf { it.isNotBlank() } ?: rrule
        return "已创建定时任务「$title」（$ruleStr，下次触发 $nextStr）"
    }
}

/** 列出所有定时任务（AI 可调用） */
class ListScheduledTasksTool : QuroTool {
    override val name = "list_scheduled_tasks"
    override val description = "列出所有定时任务/自动化提醒。无参数。返回任务列表（id/标题/时间/重复类型/状态）。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, arguments: String): String {
        val tasks = QuroScheduledTaskStore.load(context)
        if (tasks.isEmpty()) return "当前没有定时任务"
        val sb = StringBuilder("定时任务列表（共 ${tasks.size} 条）：\n")
        tasks.forEachIndexed { i, t ->
            sb.append("${i + 1}. [${if (t.enabled) "启用" else "禁用"}] ${t.title}")
            if (t.scheduleType == "once") sb.append(" - 一次性 @ ${t.scheduledAt}")
            else sb.append(" - ${QuroScheduledTaskScheduler.humanRrule(t.rrule).takeIf { it.isNotBlank() } ?: t.rrule}")
            if (t.content.isNotBlank()) sb.append(" | 指令:${t.content}")
            if (t.cwds.isNotBlank()) sb.append(" | 会话:${t.cwds}")
            sb.append(" | id=${t.id}\n")
        }
        return sb.toString()
    }
}

/** 删除定时任务（AI 可调用） */
class DeleteScheduledTaskTool : QuroTool {
    override val name = "delete_scheduled_task"
    override val description = "删除一条定时任务/自动化提醒。参数：{\"id\":\"任务id\"}。id 可通过 list_scheduled_tasks 获取。"
    override val parametersJson = """{"type":"object","properties":{"id":{"type":"string","description":"要删除的任务id"}},"required":["id"]}"""
    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val id = jo.optString("id", "")
        if (id.isBlank()) return "id 不能为空"
        val tasks = QuroScheduledTaskStore.load(context)
        val task = tasks.find { it.id == id } ?: return "未找到 id=$id 的任务"
        QuroScheduledTaskScheduler.cancel(context, id)
        QuroScheduledTaskStore.remove(context, id)
        return "已删除定时任务「${task.title}」"
    }
}
