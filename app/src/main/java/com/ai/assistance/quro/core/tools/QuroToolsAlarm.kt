package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import org.json.JSONObject

/** 设置闹钟/定时器（SET_ALARM 为普通权限，无需运行时申请）。 */
class SetAlarmTool : QuroTool {
    override val name = "set_alarm"
    override val description = "设置一条闹钟（当用户要设闹钟 / 定时提醒时使用）。参数为 {\"hour\":7,\"minute\":30,\"label\":\"晨会\",\"days\":[2,4,6],\"skipUi\":false}。days 为 1-7(周一至周日)，可省略。"
    override val parametersJson = """{"type":"object","properties":{"hour":{"type":"integer","description":"小时0-23"},"minute":{"type":"integer","description":"分钟0-59"},"label":{"type":"string","description":"标签(可选)"},"days":{"type":"array","description":"重复日 1-7(可选)"},"skipUi":{"type":"boolean","description":"是否跳过确认界面，默认false"}},"required":["hour","minute"]}"""
    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val hour = jo.optInt("hour", -1)
        val minute = jo.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) return "hour(0-23) 与 minute(0-59) 非法"
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
        } catch (e: Exception) {
            "设置闹钟失败: ${e.message}"
        }
    }
}
