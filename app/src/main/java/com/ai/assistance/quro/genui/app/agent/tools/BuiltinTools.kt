package com.ai.assistance.quro.genui.app.agent.tools

import android.content.Context
import com.ai.assistance.quro.genui.app.agent.AgentMemory
import com.ai.assistance.quro.genui.app.agent.ToolGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 内置工具集（Tool-first，参考 ZorvAI 的 QuroTool 设计：每个工具 = 名称+描述+参数schema+执行体）。
 * 全部工具在 IO 线程执行，返回 JSONObject 给 LLM。
 */
class BuiltinTools(private val context: Context) {

    val memory = AgentMemory(context)
    val gate = ToolGate(context)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** 抓网页用：读超时更长（大页面更慢），不自动重试整站，交给上层控制。 */
    private val fetchHttp = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    // ---------- OpenAI function calling 的 tools 声明 ----------

    fun declarations(): JSONArray {
        fun decl(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject =
            JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", name).put("description", desc)
                    .put("parameters", JSONObject()
                        .put("type", "object").put("properties", props)
                        .put("required", JSONArray(required))))

        return JSONArray()
            .put(decl("web_search", "联网搜索最新信息。多引擎容错，返回去重后的标题/链接/摘要。凡涉及时效性信息（新闻、价格、版本号、赛事、天气、汇率、今天发生的事）必须先调用它，不要凭记忆回答。",
                JSONObject()
                    .put("query", JSONObject().put("type", "string").put("description", "搜索关键词，中文或英文均可，尽量具体"))
                    .put("max", JSONObject().put("type", "integer").put("description", "期望结果条数 3-12，默认 8")),
                listOf("query")))
            .put(decl("web_fetch", "抓取指定网页的正文文本（已剔除导航/广告/脚本，保留段落）。用于读取搜索结果里的具体页面、或用户给出的链接。",
                JSONObject()
                    .put("url", JSONObject().put("type", "string").put("description", "完整网址，以 http:// 或 https:// 开头"))
                    .put("max_chars", JSONObject().put("type", "integer").put("description", "最多返回字符数 500-40000，默认4000")),
                listOf("url")))
            .put(decl("memory_write", "写入长期记忆（持久保存，跨会话可用）。用户的重要偏好、项目背景、约定都应记下。",
                JSONObject()
                    .put("key", JSONObject().put("type", "string").put("description", "记忆键，如 user.style / project.bg"))
                    .put("content", JSONObject().put("type", "string").put("description", "记忆内容，简洁准确")),
                listOf("key", "content")))
            .put(decl("memory_read", "读取一条长期记忆的完整内容。",
                JSONObject().put("key", JSONObject().put("type", "string")), listOf("key")))
            .put(decl("memory_list", "列出全部长期记忆的键与摘要。", JSONObject(), emptyList()))
            .put(decl("memory_delete", "删除一条长期记忆。",
                JSONObject().put("key", JSONObject().put("type", "string")), listOf("key")))
            .put(decl("time_now", "获取设备当前时间与日期。",
                JSONObject(), emptyList()))
            .put(decl("device_info", "获取设备信息：型号、Android 版本、电量、网络类型。",
                JSONObject(), emptyList()))
            .put(decl("notify_send", "发一条系统通知（需要用户授权通知权限）。",
                JSONObject()
                    .put("title", JSONObject().put("type", "string"))
                    .put("body", JSONObject().put("type", "string")),
                listOf("title", "body")))
            .put(decl("location_get", "获取设备最近一次已知地理位置（经纬度+逆地理出的城市级描述）。需要定位权限。用于天气/附近/出行类任务。",
                JSONObject(), emptyList()))
            .put(decl("file_save", "把内容保存为设备上的真实文件（应用文档目录，可反复读取）。",
                JSONObject()
                    .put("name", JSONObject().put("type", "string").put("description", "文件名，如 notes.txt / data.json"))
                    .put("content", JSONObject().put("type", "string")),
                listOf("name", "content")))
            .put(decl("file_read", "读取之前用 file_save 保存的文件内容。",
                JSONObject().put("name", JSONObject().put("type", "string")), listOf("name")))
            .put(decl("file_list", "列出所有已保存的文件名与大小。", JSONObject(), emptyList()))
            .put(decl("contacts_search", "按关键词搜索手机通讯录联系人（姓名/电话）。需要通讯录权限。",
                JSONObject().put("keyword", JSONObject().put("type", "string").put("description", "姓名或号码片段")),
                listOf("keyword")))
            .put(decl("sms_compose", "打开系统短信编辑页并预填收件人与内容（由用户确认发送，不自动发送）。",
                JSONObject()
                    .put("phone", JSONObject().put("type", "string"))
                    .put("text", JSONObject().put("type", "string")),
                listOf("phone", "text")))
            .put(decl("call_dial", "打开系统拨号盘并填入号码（由用户手动拨出，不自动拨打）。",
                JSONObject().put("phone", JSONObject().put("type", "string")), listOf("phone")))
            .put(decl("alarm_set", "设置一个系统闹钟（真实生效）。用于用户说「提醒我几点做xx」。",
                JSONObject()
                    .put("hour", JSONObject().put("type", "integer").put("description", "24小时制 0-23"))
                    .put("minute", JSONObject().put("type", "integer"))
                    .put("label", JSONObject().put("type", "string").put("description", "闹钟备注")),
                listOf("hour", "minute")))
            .put(decl("apps_list", "列出设备上已安装的应用（名称+包名）。用于打开应用/了解设备。",
                JSONObject(), emptyList()))
            .put(decl("app_open", "启动一个已安装应用。",
                JSONObject().put("package", JSONObject().put("type", "string").put("description", "应用包名")), listOf("package")))
            .put(decl("open_url", "用系统浏览器打开网页。",
                JSONObject().put("url", JSONObject().put("type", "string")), listOf("url")))
            .put(decl("clipboard_read", "读取剪贴板当前文本。", JSONObject(), emptyList()))
            // ---------- 以下为 v0.9 扩充：让 AI 能触达更多真实能力 ----------
            .put(decl("system_status", "读取设备实时状态：电量/充电/网络/存储/内存/音量/亮度。做系统面板类界面时先调它取真实值。",
                JSONObject(), emptyList()))
            .put(decl("flashlight", "开关手电筒（真实控制闪光灯）。",
                JSONObject().put("on", JSONObject().put("type", "boolean").put("description", "true 开，false 关")),
                listOf("on")))
            .put(decl("tts_speak", "用系统语音朗读一段文字（真实出声）。用于朗读/播报/无障碍场景。",
                JSONObject().put("text", JSONObject().put("type", "string")), listOf("text")))
            .put(decl("share_text", "把一段文本或链接交给系统分享面板（用户自选目标 App）。",
                JSONObject()
                    .put("title", JSONObject().put("type", "string"))
                    .put("text", JSONObject().put("type", "string")),
                listOf("text")))
            .put(decl("open_settings", "跳转系统设置页。",
                JSONObject().put("page", JSONObject().put("type", "string")
                    .put("description", "wifi / bluetooth / display / sound / battery / apps / location / notification / about")),
                listOf("page")))
            .put(decl("calendar_query", "查询设备日历中近期的真实日程。需要日历读取权限。",
                JSONObject().put("days", JSONObject().put("type", "integer").put("description", "往后查多少天，默认 7")),
                emptyList()))
            .put(decl("calendar_add", "向系统日历添加一个真实日程。",
                JSONObject()
                    .put("title", JSONObject().put("type", "string"))
                    .put("begin", JSONObject().put("type", "string").put("description", "开始时间，格式 yyyy-MM-dd HH:mm"))
                    .put("minutes", JSONObject().put("type", "integer").put("description", "时长（分钟），默认 60"))
                    .put("note", JSONObject().put("type", "string").put("description", "备注，可选")),
                listOf("title", "begin")))
    }

    // ---------- 执行分发 ----------

    suspend fun execute(name: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        when (name) {
            "web_search" -> webSearch(args.optString("query"), args.optInt("max", 8))
            "web_fetch" -> webFetch(args.optString("url"), args.optInt("max_chars", 4000))
            "memory_write" -> memory.write(args.optString("key"), args.optString("content"))
            "memory_read" -> memory.read(args.optString("key"))
            "memory_list" -> memory.list()
            "memory_delete" -> memory.delete(args.optString("key"))
            "time_now" -> timeNow()
            "device_info" -> deviceInfo()
            "notify_send" -> notifySend(args.optString("title", "GenUI"), args.optString("body"))
            "location_get" -> locationGet()
            "file_save" -> fileSave(args.optString("name"), args.optString("content"))
            "file_read" -> fileRead(args.optString("name"))
            "file_list" -> fileList()
            "contacts_search" -> contactsSearch(args.optString("keyword"))
            "sms_compose" -> smsCompose(args.optString("phone"), args.optString("text"))
            "call_dial" -> callDial(args.optString("phone"))
            "alarm_set" -> alarmSet(args.optInt("hour"), args.optInt("minute"), args.optString("label", "GenUI 提醒"))
            "apps_list" -> appsList()
            "app_open" -> appOpen(args.optString("package"))
            "open_url" -> openUrl(args.optString("url"))
            "clipboard_read" -> clipboardRead()
            // ---------- v0.9 扩充 ----------
            "system_status" -> systemStatus()
            "flashlight" -> flashlight(args.optBoolean("on"))
            "tts_speak" -> ttsSpeak(args.optString("text"))
            "share_text" -> shareText(args.optString("title", ""), args.optString("text"))
            "open_settings" -> openSettings(args.optString("page"))
            "calendar_query" -> calendarQuery(args.optInt("days", 7))
            "calendar_add" -> calendarAdd(
                args.optString("title"), args.optString("begin"),
                args.optInt("minutes", 60), args.optString("note")
            )
            else -> JSONObject().put("error", "未知工具: $name")
        }
    }

    /** 调用是否应该走权限门禁（按"工具族"检查） */
    fun gateFor(name: String): String = when {
        name.startsWith("memory") -> "memory"
        name.startsWith("time") -> "time"
        name.startsWith("device") || name == "system_status" -> "device"
        name.startsWith("location") -> "location"
        name.startsWith("file") -> "file"
        name.startsWith("calendar") -> "calendar"
        name in listOf("web_search", "web_fetch") -> name
        name == "notify_send" -> "notify"
        name == "haptics" || name.startsWith("clipboard") -> name
        name == "flashlight" -> "flashlight"
        name == "tts_speak" -> "tts"
        name.startsWith("share") || name == "open_settings" -> "system"
        else -> name
    }

    // ---------- 各工具实现 ----------

    private fun timeNow(): JSONObject {
        val now = java.util.Date()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd E HH:mm:ss", java.util.Locale.CHINA)
        return JSONObject().put("text", fmt.format(now))
            .put("epoch", now.time)
    }

    // ---------- v0.9 扩充工具实现 ----------

    /** 设备实时状态：电量/充电/网络/存储/内存/音量/亮度 */
    private fun systemStatus(): JSONObject {
        val battery = com.ai.assistance.quro.genui.app.bridge.MoBridgeHost.deviceInfoStatic(context)
        val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val totalGB = st.blockCountLong * st.blockSizeLong / 1024.0 / 1024 / 1024
        val freeGB = st.availableBlocksLong * st.blockSizeLong / 1024.0 / 1024 / 1024
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val volPercent = runCatching {
            val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            if (max > 0) audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100 / max else 0
        }.getOrDefault(0)
        val brightness = runCatching {
            android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)
        return JSONObject()
            .put("battery", battery.optJSONObject("battery"))
            .put("model", battery.optString("model"))
            .put("os", battery.optString("os"))
            .put("network", battery.optString("network"))
            .put("storage", JSONObject()
                .put("total_gb", String.format(java.util.Locale.US, "%.1f", totalGB).toDouble())
                .put("free_gb", String.format(java.util.Locale.US, "%.1f", freeGB).toDouble())
                .put("used_pct", if (totalGB > 0) ((totalGB - freeGB) / totalGB * 100).toInt() else 0))
            .put("memory", JSONObject()
                .put("total_mb", mi.totalMem / 1024 / 1024)
                .put("avail_mb", mi.availMem / 1024 / 1024)
                .put("used_pct", if (mi.totalMem > 0) ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt() else 0))
            .put("volume_pct", volPercent)
            .put("brightness", if (brightness >= 0) brightness * 100 / 255 else -1)
    }

    /** 手电筒：Camera2 无闪光灯权限时降级为提示 */
    private fun flashlight(on: Boolean): JSONObject = runCatching {
        val cam = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        // 相机权限未授予时 setTorchMode 会抛 SecurityException；提前判断给出可操作提示
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CAMERA
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) throw SecurityException(com.ai.assistance.quro.genui.app.perms.PermRegistry.guideFor("flashlight"))
        val id = cam.cameraIdList.firstOrNull { cid ->
            cam.getCameraCharacteristics(cid)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw IllegalStateException("此设备没有可用闪光灯")
        cam.setTorchMode(id, on)
        JSONObject().put("ok", true).put("on", on)
    }.getOrElse {
        JSONObject().put("error", "手电筒控制失败：${it.message ?: "未知原因"}（部分设备无闪光灯，或已被相机应用占用）")
    }

    /** 系统 TTS 朗读 */
    private fun ttsSpeak(text: String): JSONObject {
        require(text.isNotBlank()) { "text 不能为空" }
        val clipped = text.take(500)
        // 在主线程初始化并 speak（TTS 要求）
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                val tts = android.speech.tts.TextToSpeech(context) { }
                tts.language = java.util.Locale.CHINA
                tts.speak(clipped, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "gen")
            }
        }
        return JSONObject().put("ok", true).put("chars", clipped.length)
    }

    /** 系统分享面板 */
    private fun shareText(title: String, text: String): JSONObject {
        require(text.isNotBlank()) { "text 不能为空" }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            if (title.isNotBlank()) putExtra(android.content.Intent.EXTRA_SUBJECT, title)
            putExtra(android.content.Intent.EXTRA_TEXT, text)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(android.content.Intent.createChooser(send, title.ifBlank { "分享" })
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return JSONObject().put("ok", true)
    }

    /** 跳系统设置页 */
    private fun openSettings(page: String): JSONObject {
        val action = when (page.lowercase()) {
            "wifi" -> android.provider.Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> android.provider.Settings.ACTION_SOUND_SETTINGS
            "battery" -> android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location" -> android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "notification" -> "android.settings.NOTIFICATION_SETTINGS"
            "apps" -> android.provider.Settings.ACTION_APPLICATION_SETTINGS
            "about" -> android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS
            else -> android.provider.Settings.ACTION_SETTINGS
        }
        runCatching {
            context.startActivity(android.content.Intent(action)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.getOrElse {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return JSONObject().put("ok", true).put("page", page)
    }

    /** 查日历事件 */
    private fun calendarQuery(days: Int): JSONObject {
        requireCalendarRead()
        val d = days.coerceIn(1, 90)
        val from = System.currentTimeMillis()
        val to = from + d * 24L * 3600 * 1000
        val out = JSONArray()
        val uri = android.provider.CalendarContract.Events.CONTENT_URI
        context.contentResolver.query(
            uri,
            arrayOf(
                android.provider.CalendarContract.Events.TITLE,
                android.provider.CalendarContract.Events.DTSTART,
                android.provider.CalendarContract.Events.DTEND,
                android.provider.CalendarContract.Events.ALL_DAY,
                android.provider.CalendarContract.Events.EVENT_LOCATION
            ),
            "${android.provider.CalendarContract.Events.DTSTART} >= ? AND ${android.provider.CalendarContract.Events.DTSTART} <= ?",
            arrayOf(from.toString(), to.toString()),
            "${android.provider.CalendarContract.Events.DTSTART} ASC"
        )?.use { c ->
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
            var n = 0
            while (c.moveToNext() && n < 40) {
                out.put(JSONObject()
                    .put("title", c.getString(0) ?: "(无标题)")
                    .put("begin", fmt.format(java.util.Date(c.getLong(1))))
                    .put("end", if (c.isNull(2)) "" else fmt.format(java.util.Date(c.getLong(2))))
                    .put("allDay", c.getInt(3) == 1)
                    .put("where", c.getString(4) ?: ""))
                n++
            }
        }
        return JSONObject().put("days", d).put("count", out.length()).put("events", out)
    }

    /** 写日历 */
    private fun calendarAdd(title: String, begin: String, minutes: Int, note: String): JSONObject {
        require(title.isNotBlank()) { "title 不能为空" }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
        val start = runCatching { fmt.parse(begin)?.time }
            .getOrNull() ?: throw IllegalStateException("时间格式应为 yyyy-MM-dd HH:mm，收到：$begin")
        val end = start + minutes.coerceIn(5, 24 * 60).toLong() * 60 * 1000
        val intent = android.content.Intent(android.content.Intent.ACTION_INSERT).apply {
            data = android.provider.CalendarContract.Events.CONTENT_URI
            putExtra(android.provider.CalendarContract.Events.TITLE, title)
            putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, end)
            if (note.isNotBlank()) putExtra(android.provider.CalendarContract.Events.DESCRIPTION, note)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            throw IllegalStateException("设备上没有可用的日历应用。请先安装/启用系统日历后再试。")
        }
        return JSONObject().put("ok", true)
            .put("title", title)
            .put("note", "已打开系统日历新建页并预填，由用户确认后保存（不静默写入）")
    }

    /** 日历读取权限守卫：报错文案由 [com.ai.assistance.quro.genui.app.perms.PermRegistry] 统一生成 */
    private fun requireCalendarRead() {
        val ok = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CALENDAR
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!ok) throw IllegalStateException(com.ai.assistance.quro.genui.app.perms.PermRegistry.guideFor("calendar_query"))
    }

    private fun deviceInfo(): JSONObject = com.ai.assistance.quro.genui.app.bridge.MoBridgeHost.deviceInfoStatic(context)

    private fun notifySend(title: String, body: String): JSONObject =
        com.ai.assistance.quro.genui.app.bridge.MoBridgeHost.notifyStatic(context, title, body)

    /** 真实定位：系统权限 ACCESS_FINE_LOCATION 已授权时才可用（权限屏/系统弹窗授权） */
    private fun locationGet(): JSONObject {
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse)
            throw IllegalStateException(com.ai.assistance.quro.genui.app.perms.PermRegistry.guideFor("location_get"))
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val providers = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
        var best: android.location.Location? = null
        for (prov in providers) {
            runCatching {
                val loc = lm.getLastKnownLocation(prov)
                val cur = best
                if (loc != null && (cur == null || loc.time > cur.time)) best = loc
            }
        }
        val loc = best ?: throw IllegalStateException("暂无位置缓存，请打开地图类应用后重试或稍后再试")
        val geo = android.location.Geocoder(context, java.util.Locale.CHINA)
        val city = runCatching {
            @Suppress("DEPRECATION")   // 逆地理编码：新 API 需要额外依赖，此处沿用平台能力
            val addrs = geo.getFromLocation(loc.latitude, loc.longitude, 1)
            addrs?.firstOrNull()?.let { a -> a.locality ?: a.subAdminArea ?: a.adminArea ?: "" }
        }.getOrNull() ?: ""
        return JSONObject()
            .put("lat", loc.latitude).put("lng", loc.longitude)
            .put("accuracy_m", loc.accuracy.toInt())
            .put("city", city)
            .put("ts", loc.time)
    }

    private fun fileDir(): File = File(context.filesDir, "agent_files").apply { mkdirs() }

    private fun safeName(n: String): String {
        val cleaned = n.replace("/", "_").replace("..", "_").ifBlank { "untitled.txt" }
        return cleaned
    }

    private fun fileSave(name: String, content: String): JSONObject {
        val f = File(fileDir(), safeName(name))
        f.writeText(content)
        return JSONObject().put("ok", true).put("path", f.absolutePath).put("bytes", content.length)
    }

    private fun fileRead(name: String): JSONObject {
        val f = File(fileDir(), safeName(name))
        if (!f.exists()) throw IllegalStateException("文件不存在：$name")
        val text = f.readText()
        return JSONObject().put("name", name).put("content", text.take(8000))
    }

    /**
     * 联网搜索（多引擎容错 + 去重 + 同域降权）。实现见 [WebSearch]。
     * 失败时返回每个引擎的具体原因，便于模型换关键词或改用 web_fetch。
     */
    private fun webSearch(query: String, max: Int): JSONObject =
        WebSearch.search(http, query, max)

    /**
     * 抓取网页正文。
     *
     * 相比旧版增强：
     * - 正文抽取优先取 article/main/content 区域，剔除导航/页脚/脚本噪声；
     * - 保留段落换行（旧版把所有换行压成一行，长文难读）；
     * - 按 Content-Type 的 charset 正确解码（旧版只靠 string() 猜，中文常乱码）；
     * - 给出截断提示与重定向后的最终地址。
     */
    private fun webFetch(url: String, maxChars: Int): JSONObject {
        val target = url.trim()
        if (!target.startsWith("http://") && !target.startsWith("https://"))
            return JSONObject().put("error", "网址必须以 http:// 或 https:// 开头").put("url", target)
        return runCatching {
            val req = Request.Builder()
                .url(target)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
                )
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept", "text/html,application/xhtml+xml,text/plain,*/*;q=0.8")
                .build()
            fetchHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return JSONObject().put("url", target)
                        .put("error", "HTTP ${resp.code} ${resp.message.ifBlank { "" }}".trim())
                        .put("hint", httpHint(resp.code))
                }
                val ct = resp.header("Content-Type").orEmpty()
                val rawBytes = resp.body?.bytes() ?: ByteArray(0)
                if (rawBytes.isEmpty())
                    return JSONObject().put("url", target).put("error", "响应为空").put("content_type", ct)

                val mime = ct.substringBefore(';').trim().lowercase()
                val isHtml = mime.contains("html") || mime.contains("xml")
                val isText = isHtml || mime.startsWith("text/") || mime.contains("json")
                if (!isText) {
                    return JSONObject().put("url", target).put("content_type", ct)
                        .put("bytes", rawBytes.size)
                        .put("hint", "非文本内容（${mime.ifBlank { "未知类型" }}），未解析。若需引用请让用户提供摘要。")
                }

                val charset = detectCharset(ct, rawBytes)
                val raw = runCatching { String(rawBytes, charset) }
                    .getOrElse { String(rawBytes, Charsets.UTF_8) }

                val text = if (isHtml) Text.article(raw) else raw.trim()
                if (text.isBlank())
                    return JSONObject().put("url", target).put("content_type", ct)
                        .put("hint", "页面没有可提取的正文（可能是纯 JS 渲染的页面）。")

                val limit = maxChars.coerceIn(500, 40_000)
                val clipped = text.length > limit
                val content = if (clipped) text.take(limit) else text

                val finalUrl = resp.request.url.toString()
                return JSONObject()
                    .put("url", target)
                    .also { if (finalUrl != target) it.put("final_url", finalUrl) }
                    .put("content_type", mime)
                    .put("charset", charset.name())
                    .put("chars", text.length)
                    .put("truncated", clipped)
                    .put("content", if (clipped) "$content\n…（已截断，原文共 ${text.length} 字）" else content)
            }
        }.getOrElse { e ->
            JSONObject().put("url", target)
                .put("error", "抓取失败：${humanizeNetError(e)}")
                .put("hint", "确认网址可公网访问、且不是需要登录的页面；也可改用 web_search 找别的来源。")
        }
    }

    /** HTTP 状态码 → 可操作的人话提示。 */
    private fun httpHint(code: Int): String = when (code) {
        401, 403 -> "该页面需要登录或拒绝了爬取，换其他来源试试。"
        404 -> "页面不存在，检查网址是否正确。"
        429 -> "被限流，稍等片刻或换其他来源。"
        in 500..599 -> "对方服务器异常，稍后重试。"
        else -> "可稍后重试或换其他来源。"
    }

    /** 从 Content-Type 的 charset 或 BOM / meta 猜编码，中文站常见 GBK 必须处理。 */
    private fun detectCharset(contentType: String, bytes: ByteArray): java.nio.charset.Charset {
        Regex("""charset=["']?([\w-]+)""", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.get(1)
            ?.let { name -> runCatching { java.nio.charset.Charset.forName(name) }.getOrNull() }
            ?.let { return it }
        // BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return Charsets.UTF_8
        // 从前 2048 字节里找 <meta charset>
        val head = runCatching { String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1) }.getOrDefault("")
        Regex("""charset=["']?([\w-]+)""", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.get(1)
            ?.let { name -> runCatching { java.nio.charset.Charset.forName(name) }.getOrNull() }
            ?.let { return it }
        return Charsets.UTF_8
    }

    private fun humanizeNetError(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "域名解析失败（网络不通或网址有误）"
        is java.net.SocketTimeoutException -> "连接超时"
        is javax.net.ssl.SSLException -> "SSL 证书校验失败"
        is java.net.ConnectException -> "连接被拒绝"
        else -> e.message ?: e.javaClass.simpleName
    }


    // ---------- 系统能力：通信 / 定时 / 应用 / 剪贴板 ----------

    private fun contactsSearch(keyword: String): JSONObject {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw IllegalStateException(com.ai.assistance.quro.genui.app.perms.PermRegistry.guideFor("contacts_search"))
        val out = JSONArray()
        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
            arrayOf("%$keyword%", "%$keyword%"),
            null
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n < 10) {
                out.put(JSONObject().put("name", c.getString(0) ?: "").put("phone", c.getString(1) ?: ""))
                n++
            }
        }
        return JSONObject().put("count", out.length()).put("contacts", out)
    }

    /** 打开短信编辑页（用户确认后自己点发送；不自动发送） */
    private fun smsCompose(phone: String, text: String): JSONObject {
        val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO,
            android.net.Uri.parse("smsto:$phone")).apply {
            putExtra("sms_body", text)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return JSONObject().put("ok", true).put("note", "已打开短信编辑页，由用户确认发送")
    }

    private fun callDial(phone: String): JSONObject {
        val intent = android.content.Intent(android.content.Intent.ACTION_DIAL,
            android.net.Uri.parse("tel:$phone")).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
        return JSONObject().put("ok", true).put("note", "已打开拨号盘，由用户手动拨出")
    }

    /** 真闹钟：AlarmManager 精确闹钟（Android 12+ 需用户在系统设置授权精确闹钟） */
    private fun alarmSet(hour: Int, minute: Int, label: String): JSONObject {
        require(hour in 0..23 && minute in 0..59) { "时间不合法：$hour:$minute" }
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val now = java.util.Calendar.getInstance()
        val target = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            if (before(now)) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        val pi = android.app.PendingIntent.getBroadcast(
            context, (hour * 60 + minute) % 65536,
            android.content.Intent(context, com.ai.assistance.quro.genui.app.bridge.AlarmReceiver::class.java)
                .putExtra("label", label),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val canExact = if (android.os.Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
        if (canExact) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, target.timeInMillis, pi)
        else am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, target.timeInMillis, pi)
        return JSONObject()
            .put("ok", true).put("ring_at", java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(target.time))
            .put("label", label)
            .put("exact", canExact)
    }

    private fun appsList(): JSONObject {
        val pm = context.packageManager
        val out = JSONArray()
        pm.getInstalledApplications(0).sortedBy { it.loadLabel(pm).toString() }.forEach { app ->
            if (pm.getLaunchIntentForPackage(app.packageName) != null)
                out.put(JSONObject().put("app", app.loadLabel(pm).toString()).put("pkg", app.packageName))
        }
        val clipped = JSONArray()
        for (i in 0 until minOf(40, out.length())) clipped.put(out.get(i))
        return JSONObject().put("count", out.length()).put("apps", clipped)
    }

    private fun appOpen(pkg: String): JSONObject {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: throw IllegalStateException("未安装或不可启动：$pkg")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JSONObject().put("ok", true).put("pkg", pkg)
    }

    private fun openUrl(url: String): JSONObject {
        require(url.startsWith("http")) { "仅支持 http(s) 链接" }
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return JSONObject().put("ok", true).put("url", url)
    }

    private fun clipboardRead(): JSONObject {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        return JSONObject().put("text", text)
    }

    private fun fileList(): JSONObject {
        val out = JSONArray()
        fileDir().listFiles()?.sortedByDescending { it.lastModified() }?.forEach {
            out.put(JSONObject().put("name", it.name).put("bytes", it.length()))
        }
        return JSONObject().put("count", out.length()).put("files", out)
    }

}
