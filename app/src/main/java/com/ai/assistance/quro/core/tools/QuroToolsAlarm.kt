package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import androidx.core.app.AlarmManagerCompat
import org.json.JSONObject

/**
 * 设置闹钟/定时器。
 *
 * 两种路径：
 * 1) [AlarmClock.ACTION_SET_ALARM] 跳转系统时钟 App（需 SET_ALARM 权限，普通权限安装即授予）；
 * 2) Android 12+ 精确闹钟需 [android.permission.SCHEDULE_EXACT_ALARM] 特殊权限（用户须手动在设置中授予），
 *    本工具会检测并引导用户前往设置页。
 */
class SetAlarmTool : QuroTool {
    override val name = "set_alarm"
    override val description = "设置一条闹钟（当用户要设闹钟 / 定时提醒时使用）。参数为 {\"hour\":7,\"minute\":30,\"label\":\"晨会\",\"days\":[2,4,6],\"skipUi\":false}。days 为 1-7(周一至周日)，可省略。"
    override val parametersJson = """{"type":"object","properties":{"hour":{"type":"integer","description":"小时0-23"},"minute":{"type":"integer","description":"分钟0-59"},"label":{"type":"string","description":"标签(可选)"},"days":{"type":"array","description":"重复日 1-7(可选)"},"skipUi":{"type":"boolean","description":"是否跳过确认界面，默认false"}},"required":["hour","minute"]}"""
    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val hour = jo.optInt("hour", -1)
        val minute = jo.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) return "hour(0-23) 与 minute(0-59) 非法"

        // Android 12+ 检测精确闹钟权限并引导
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            if (am != null && !AlarmManagerCompat.canScheduleExactAlarms(am)) {
                // 引导用户去设置页授权 SCHEDULE_EXACT_ALARM
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return "⚠️ 精确闹钟权限未授予。已打开系统设置页，请开启「允许设置精确闹钟」后重试。"
                } catch (e: Exception) {
                    return "⚠️ 精确闹钟权限未授予（Android 12+ 需要 SCHEDULE_EXACT_ALARM）。请到 设置→应用→Quro AI→权限 中开启「闹钟和提醒」，或手动允许精确闹钟。"
                }
            }
        }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            jo.optString("label", "").let { if (it.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            if (jo.has("days")) {
                val days = mutableListOf<Int>()
                val arr = jo.getJSONArray("days")
                for (i in 0 until arr.length()) days.add(arr.getInt(i))
                putExtra(AlarmClock.EXTRA_DAYS, days.toIntArray())
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, jo.optBoolean("skipUi", false))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "已请求设置闹钟 ${"%02d:%02d".format(hour, minute)}"
        } catch (e: android.content.ActivityNotFoundException) {
            "设置闹钟失败：本设备没有可处理「设置闹钟」的系统应用（时钟 App），请先安装一个时钟/闹钟应用后重试。"
        } catch (e: Exception) {
            "设置闹钟失败: ${e.message}"
        }
    }
}
