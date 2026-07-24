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
import java.util.Calendar
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.ai.assistance.quro.activity.QuroMainActivity
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

/** 定时任务数据模型 */
data class QuroScheduledTask(
    val id: String,
    val title: String,
    val content: String = "",
    val hour: Int = 8,
    val minute: Int = 0,
    val repeatType: TaskRepeatType = TaskRepeatType.ONCE,
    /** 每周/每双周时生效：1=周一 ... 7=周日 */
    val dayOfWeek: Int = 1,
    /** 每月生效日：1-31 */
    val dayOfMonth: Int = 1,
    /** 每年生效月：1-12 */
    val month: Int = 1,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastTriggered: Long = 0L,
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
                        hour = o.optInt("hour", 8),
                        minute = o.optInt("minute", 0),
                        repeatType = runCatching {
                            TaskRepeatType.valueOf(o.optString("repeatType", "ONCE"))
                        }.getOrDefault(TaskRepeatType.ONCE),
                        dayOfWeek = o.optInt("dayOfWeek", 1),
                        dayOfMonth = o.optInt("dayOfMonth", 1),
                        month = o.optInt("month", 1),
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
                        put("hour", t.hour)
                        put("minute", t.minute)
                        put("repeatType", t.repeatType.name)
                        put("dayOfWeek", t.dayOfWeek)
                        put("dayOfMonth", t.dayOfMonth)
                        put("month", t.month)
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

    /** 计算下次触发时间（毫秒时间戳） */
    fun nextTriggerTime(task: QuroScheduledTask): Long? {
        val now = Calendar.getInstance()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, task.hour)
            set(Calendar.MINUTE, task.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        when (task.repeatType) {
            TaskRepeatType.ONCE -> {
                if (cal.timeInMillis <= now.timeInMillis) {
                    // 如果已过时，设为明天
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            TaskRepeatType.DAILY -> {
                while (cal.timeInMillis <= now.timeInMillis) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            TaskRepeatType.WEEKLY -> {
                // dayOfWeek: 1=周一...7=周日 → Calendar: 2=周一...1=周日
                val calDayOfWeek = if (task.dayOfWeek == 7) Calendar.SUNDAY else task.dayOfWeek + 1
                cal.set(Calendar.DAY_OF_WEEK, calDayOfWeek)
                while (cal.timeInMillis <= now.timeInMillis) {
                    cal.add(Calendar.WEEK_OF_YEAR, 1)
                }
            }
            TaskRepeatType.BIWEEKLY -> {
                val calDayOfWeek = if (task.dayOfWeek == 7) Calendar.SUNDAY else task.dayOfWeek + 1
                cal.set(Calendar.DAY_OF_WEEK, calDayOfWeek)
                while (cal.timeInMillis <= now.timeInMillis) {
                    cal.add(Calendar.WEEK_OF_YEAR, 2)
                }
            }
            TaskRepeatType.MONTHLY -> {
                cal.set(Calendar.DAY_OF_MONTH, minOf(task.dayOfMonth, cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
                while (cal.timeInMillis <= now.timeInMillis) {
                    cal.add(Calendar.MONTH, 1)
                    cal.set(Calendar.DAY_OF_MONTH, minOf(task.dayOfMonth, cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
                }
            }
            TaskRepeatType.YEARLY -> {
                cal.set(Calendar.MONTH, task.month - 1) // Calendar.MONTH is 0-based
                cal.set(Calendar.DAY_OF_MONTH, minOf(task.dayOfMonth, cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
                while (cal.timeInMillis <= now.timeInMillis) {
                    cal.add(Calendar.YEAR, 1)
                }
            }
        }
        return cal.timeInMillis
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
                    description = "QuroAI 定时任务到时提醒"
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
            if (vm.currentId.value.isBlank()) {
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

        // 2) 通知：点击跳转回应用（便于查看/补执行）
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
        val notif = NotificationCompat.Builder(context, "quro_scheduled_task")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(task.title)
            .setContentText(task.content.ifBlank { "定时任务提醒" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        nm.notify(taskId.hashCode(), notif)

        // 3) 更新最后触发时间
        QuroScheduledTaskStore.updateLastTriggered(context, taskId)

        // 4) 如果是重复任务，重新排程下一次
        if (task.repeatType != TaskRepeatType.ONCE && task.enabled) {
            QuroScheduledTaskScheduler.schedule(context, task)
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
            val notif = NotificationCompat.Builder(context, "quro_scheduled_task")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("✅ 定时任务已完成")
                .setContentText("「${task.title}」AI 已处理完成，点击查看回复")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
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

/** 创建定时任务（AI 可调用） */
class ScheduleTaskTool : QuroTool {
    override val name = "schedule_task"
    override val description = "创建一条定时任务/自动化提醒（当用户要设定时提醒/定时任务时使用）。参数：{\"title\":\"晨会提醒\",\"content\":\"该开晨会了\",\"hour\":8,\"minute\":30,\"repeatType\":\"DAILY\",\"dayOfWeek\":1,\"dayOfMonth\":1,\"month\":1}。repeatType 可选：ONCE(仅一次)/DAILY(每天)/WEEKLY(每周)/BIWEEKLY(每双周)/MONTHLY(每月)/YEARLY(每年)。dayOfWeek(1-7 周一至周日，WEEKLY/BIWEEKLY时用)、dayOfMonth(1-31，MONTHLY/YEARLY时用)、month(1-12，YEARLY时用)。"
    override val parametersJson = """{"type":"object","properties":{"title":{"type":"string","description":"任务标题"},"content":{"type":"string","description":"任务内容/提醒详情"},"hour":{"type":"integer","description":"小时0-23"},"minute":{"type":"integer","description":"分钟0-59"},"repeatType":{"type":"string","description":"重复类型: ONCE/DAILY/WEEKLY/BIWEEKLY/MONTHLY/YEARLY"},"dayOfWeek":{"type":"integer","description":"周几1-7(周一至周日)，WEEKLY/BIWEEKLY时用"},"dayOfMonth":{"type":"integer","description":"几号1-31，MONTHLY/YEARLY时用"},"month":{"type":"integer","description":"几月1-12，YEARLY时用"}},"required":["title","hour","minute","repeatType"]}"""
    override val requiredPermissions = listOf(android.Manifest.permission.POST_NOTIFICATIONS)
    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val title = jo.optString("title", "")
        if (title.isBlank()) return "title 不能为空"
        val hour = jo.optInt("hour", -1)
        val minute = jo.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) return "hour(0-23) 与 minute(0-59) 非法"
        val repeatType = runCatching {
            TaskRepeatType.valueOf(jo.optString("repeatType", "ONCE"))
        }.getOrDefault(TaskRepeatType.ONCE)
        val task = QuroScheduledTask(
            id = UUID.randomUUID().toString(),
            title = title,
            content = jo.optString("content", ""),
            hour = hour,
            minute = minute,
            repeatType = repeatType,
            dayOfWeek = jo.optInt("dayOfWeek", 1),
            dayOfMonth = jo.optInt("dayOfMonth", 1),
            month = jo.optInt("month", 1),
            enabled = true,
        )
        QuroScheduledTaskStore.addOrUpdate(context, task)
        QuroScheduledTaskScheduler.ensureChannel(context)
        QuroScheduledTaskScheduler.schedule(context, task)
        return "已创建定时任务「$title」(${repeatType.label} ${"%02d:%02d".format(hour, minute)})，将在 ${QuroScheduledTaskScheduler.nextTriggerTime(task)?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it))
        } ?: "未知时间"} 首次触发"
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
            sb.append(" - ${"%02d:%02d".format(t.hour, t.minute)}")
            sb.append(" (${t.repeatType.label})")
            if (t.content.isNotBlank()) sb.append(" | ${t.content}")
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
