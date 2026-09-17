package com.ai.assistance.quro.core.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 12306 余票查询（leftTicket）。
 * 首次调用拉取 12306 车站编码表并缓存，再查询指定日期/区间的车次余票。
 * 注意：12306 对自动化查询有风控，极端情况下可能返回验证码或限流；接口字段随版本可能漂移，席别余票以官方 App 为准。
 */
class QuroTrain12306Tool : QuroTool {
    override val name = "train_12306"
    override val description = "查询 12306 余票（车次/出发到达时间/历时/各席别余票）。" +
        "参数 {\"from\":\"出发站名（如 北京）\",\"to\":\"到达站名（如 上海）\",\"date\":\"出发日期 YYYY-MM-DD（必填）\",\"purpose\":\"ADULT（默认成人）\"}。" +
        "首次调用会拉取 12306 车站编码表并缓存。返回车次列表与余票。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "from":{"type":"string","description":"出发城市/车站名（如 北京、上海虹桥）"},
            "to":{"type":"string","description":"到达城市/车站名"},
            "date":{"type":"string","description":"出发日期 YYYY-MM-DD（必填）"},
            "purpose":{"type":"string","description":"乘客类型，默认 ADULT"}
        },
        "required":["from","to","date"]
    }"""

    companion object {
        private var stationMap: Map<String, String>? = null
        private const val STATION_URL = "https://kyfw.12306.cn/otn/resources/js/framework/station_name.js"
    }

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val from = jo.optString("from", "").trim()
        val to = jo.optString("to", "").trim()
        val date = jo.optString("date", "").trim()
        val purpose = jo.optString("purpose", "ADULT").trim()
        if (listOf(from, to, date).any { it.isEmpty() }) return "❌ 缺少必填参数 from / to / date"
        return runBlocking(Dispatchers.IO) {
            try {
                val map = getStationMap()
                val fromCode = resolveCode(map, from) ?: return@runBlocking "❌ 未找到出发站「$from」的编码"
                val toCode = resolveCode(map, to) ?: return@runBlocking "❌ 未找到到达站「$to」的编码"
                query(date, fromCode, toCode, purpose)
            } catch (e: Exception) {
                "❌ 12306 查询失败：${e.message}"
            }
        }
    }

    private fun getStationMap(): Map<String, String> {
        stationMap?.let { return it }
        val js = fetch(STATION_URL) ?: throw IllegalStateException("无法拉取车站编码表（网络受限）")
        // 格式：var station_names ='@bjb|北京北|VAP|beijingbei|0@...';
        val map = mutableMapOf<String, String>()
        val content = js.substringAfterLast("'").substringBeforeLast("'")
        content.split("@").forEach { seg ->
            if (seg.isBlank()) return@forEach
            val parts = seg.split("|")
            if (parts.size >= 3) {
                map[parts[1]] = parts[2] // 中文名 -> 电报码
                map[parts[0]] = parts[2] // 拼音码 -> 电报码
            }
        }
        stationMap = map
        return map
    }

    private fun resolveCode(map: Map<String, String>, name: String): String? =
        map[name] ?: map.entries.firstOrNull { it.key.contains(name) }?.value

    private fun query(date: String, fromCode: String, toCode: String, purpose: String): String {
        val url = "https://kyfw.12306.cn/otn/leftTicket/query" +
            "?leftTicketDTO.train_date=${URLEncoder.encode(date, "UTF-8")}" +
            "&leftTicketDTO.from_station=$fromCode" +
            "&leftTicketDTO.to_station=$toCode" +
            "&purpose_codes=$purpose"
        val json = fetch(url, mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to "https://kyfw.12306.cn/otn/leftTicket/init"
        )) ?: return "❌ 无法连接 12306（可能风控限流）"
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return "❌ 12306 返回异常：${json.take(200)}"
        val data = root.optJSONObject("data") ?: return "❌ 无数据：${json.take(200)}"
        val resultArr = data.optJSONArray("result") ?: return "未查询到车次（可能无直达或日期不在可售范围内）。"
        if (resultArr.length() == 0) return "未查询到 $fromCode→$toCode 在 $date 的车次。"
        val cm = data.optJSONObject("map") ?: JSONObject()
        val sb = StringBuilder("🚄 12306 余票（${resultArr.length()} 趟，席别余票以官方 App 为准）：\n")
        for (i in 0 until resultArr.length().coerceAtMost(40)) {
            val f = resultArr.optString(i).split("|")
            if (f.size < 22) continue
            val trainNo = f[3]
            val from = cm.optString(f[6], f[6])
            val to = cm.optString(f[7], f[7])
            val dep = f[8]
            val arr = f[9]
            val dur = f[10]
            sb.append("${i + 1}. ${trainNo}  ${from}→${to}  发$dep 到$arr  历时$dur\n")
            seatLabels.forEach { (idx, label) ->
                val v = f.getOrNull(idx) ?: ""
                if (v.isNotBlank() && v != "无" && v != "--" && v != "有") sb.append("   $label:$v")
                else if (v == "有") sb.append("   $label:有")
            }
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    // 席别余票字段索引（随 12306 接口版本可能漂移，仅作近似展示）
    private val seatLabels = listOf(
        23 to "高级软卧", 25 to "软卧", 26 to "软座", 27 to "特等座",
        28 to "无座", 29 to "硬卧", 30 to "硬卧", 31 to "硬座",
        32 to "二等座", 33 to "一等座", 34 to "商务座"
    )

    private fun fetch(url: String, headers: Map<String, String> = emptyMap()): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        val code = conn.responseCode
        val out = if (code in 200..299) conn.inputStream?.bufferedReader()?.readText() else null
        conn.disconnect()
        out
    }.getOrNull()
}
