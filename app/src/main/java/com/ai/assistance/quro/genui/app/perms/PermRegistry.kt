package com.ai.assistance.quro.genui.app.perms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 系统权限登记表 —— 权限屏与被拒工具提示的唯一事实来源。
 *
 * 设计动机（同类问题在运行时库与工具集上都栽过）：
 * 如果权限屏里手写一份清单、工具报错文案里再手写一份，两边迟早对不上——
 * 加了新权限忘了加到权限屏，用户就永远找不到该开哪个开关。
 * 所以：**一处定义**，权限屏渲染它，工具报错查它，概览统计也算它。
 *
 * v0.11 补齐的关键认知：Android 的权限**不都是"弹窗就能给"**。存在三类：
 *  1. 运行时权限（弹窗即得）：位置、通讯录、相机、麦克风…
 *  2. 只能跳系统设置的（弹窗点了也没用）：精确闹钟、悬浮窗、使用情况访问、勿扰
 *  3. 分版本才存在的：POST_NOTIFICATIONS(33+)、READ_MEDIA_*(33+)、BLUETOOTH_*(31+)、
 *     后台定位(29+，且必须先有前台定位)
 * 第三条尤其容易错：在 Android 12 上请求 READ_MEDIA_IMAGES 会直接查不到权限。
 * [minSdk] / [maxSdk] 就是为此存在——**不适用的项不该出现在权限屏里**，
 * 否则用户会看到一个"永远开不了"的开关，比没有还糟。
 */
object PermRegistry {

    /** 权限的获取方式，决定 UI 上的操作按钮长什么样 */
    enum class Kind {
        RUNTIME,        // 应用内弹窗即可
        SETTINGS,       // 只能跳系统设置页
        DEPENDENT       // 需要先满足前置条件（如后台定位需先给前台定位）
    }

    /**
     * @param perm      Android 权限常量；null 表示无运行时权限（只能跳系统设置，如精确闹钟）
     * @param label     人类可读名
     * @param tools     依赖它的工具名（用于"哪个能力需要它"）
     * @param why       为什么需要——写给用户看，不是写给开发者看
     * @param settingsAction 需要跳系统设置时的 action
     * @param danger    true 表示敏感权限（位置/通讯录/麦克风类），UI 需更醒目
     * @param minSdk    低于此版本不存在该权限（不展出）
     * @param maxSdk    高于此版本已废弃该权限（不展出）
     * @param kind      获取方式
     * @param requires  前置权限的 key（kind=DEPENDENT 时用于文案提示）
     */
    data class Entry(
        val key: String,
        val perm: String?,
        val label: String,
        val tools: List<String>,
        val why: String,
        val settingsAction: String? = null,
        val danger: Boolean = false,
        val minSdk: Int = 0,
        val maxSdk: Int = Int.MAX_VALUE,
        val kind: Kind = Kind.RUNTIME,
        val requires: String? = null
    )

    val all: List<Entry> = listOf(
        // ---------- 通知 / 基础 ----------
        Entry(
            key = "notification",
            perm = Manifest.permission.POST_NOTIFICATIONS,
            label = "通知",
            tools = listOf("notify_send"),
            why = "AI 生成的界面要能弹出真实系统通知（如倒计时结束提醒、日程提醒）。",
            minSdk = 33
        ),

        // ---------- 位置 ----------
        Entry(
            key = "location",
            perm = Manifest.permission.ACCESS_FINE_LOCATION,
            label = "位置",
            tools = listOf("location_get"),
            why = "天气、附近、出行类界面需要真实位置；仅在你明确要求时才读取。",
            danger = true
        ),
        Entry(
            key = "location_bg",
            perm = Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            label = "后台位置",
            tools = listOf("location_continuous"),
            why = "只有需要「持续记录轨迹」的界面才要它（如跑步记录、通勤统计）。",
            settingsAction = "android.settings.APPLICATION_DETAILS_SETTINGS",
            danger = true,
            minSdk = 29,
            kind = Kind.DEPENDENT,
            requires = "location"
        ),

        // ---------- 通讯录 / 通信 ----------
        Entry(
            key = "contacts",
            perm = Manifest.permission.READ_CONTACTS,
            label = "通讯录（读取）",
            tools = listOf("contacts_search"),
            why = "按姓名或号码检索联系人，用于「给妈妈发消息」这类指令。",
            danger = true
        ),

        // ---------- 日历 ----------
        Entry(
            key = "calendar_read",
            perm = Manifest.permission.READ_CALENDAR,
            label = "日历（读取）",
            tools = listOf("calendar_query"),
            why = "读取近期真实日程，让 AI 生成的日程界面显示的是你自己的安排。"
        ),
        Entry(
            key = "calendar_write",
            perm = Manifest.permission.WRITE_CALENDAR,
            label = "日历（写入）",
            tools = listOf("calendar_add"),
            why = "把「明天下午三点开会」这类指令写成真实日历事件。"
        ),

        // ---------- 相机 / 麦克风 ----------
        Entry(
            key = "camera",
            perm = Manifest.permission.CAMERA,
            label = "相机 / 闪光灯",
            tools = listOf("flashlight", "camera_shot"),
            why = "手电筒开关与拍照取景需要相机权限（Android 6+ 闪光灯归入相机权限组）。",
            danger = true
        ),
        Entry(
            key = "microphone",
            perm = Manifest.permission.RECORD_AUDIO,
            label = "麦克风",
            tools = listOf("voice_record"),
            why = "语音输入、录音备忘、音量分析的界面需要它才会真的录到声音。",
            danger = true
        ),

        // ---------- 媒体库 ----------
        Entry(
            key = "media_images",
            perm = Manifest.permission.READ_MEDIA_IMAGES,
            label = "相册（读取）",
            tools = listOf("media_list"),
            why = "相册/拼图/记忆类界面要能列出你真实的照片。",
            minSdk = 33,
            danger = true
        ),
        Entry(
            key = "media_audio",
            perm = Manifest.permission.READ_MEDIA_AUDIO,
            label = "音乐库（读取）",
            tools = listOf("media_list"),
            why = "播放器界面要能列出你本地的音乐。",
            minSdk = 33
        ),
        Entry(
            key = "media_video",
            perm = Manifest.permission.READ_MEDIA_VIDEO,
            label = "视频库（读取）",
            tools = listOf("media_list"),
            why = "视频列表类界面需要读取本地视频。",
            minSdk = 33
        ),
        Entry(
            key = "storage_legacy",
            perm = Manifest.permission.READ_EXTERNAL_STORAGE,
            label = "存储（读取）",
            tools = listOf("media_list"),
            why = "Android 12 及以下用它在媒体列表里读到你的照片与音乐。",
            maxSdk = 32
        ),

        // ---------- 蓝牙 ----------
        Entry(
            key = "bluetooth_connect",
            perm = Manifest.permission.BLUETOOTH_CONNECT,
            label = "蓝牙（连接）",
            tools = listOf("bluetooth_info"),
            why = "展示已连接的外设（耳机/手表）并真实读取连接状态。",
            minSdk = 31
        ),
        Entry(
            key = "bluetooth_scan",
            perm = Manifest.permission.BLUETOOTH_SCAN,
            label = "蓝牙（扫描）",
            tools = listOf("bluetooth_info"),
            why = "列出附近可发现的蓝牙设备。",
            minSdk = 31
        ),

        // ---------- 健康 / 活动 ----------
        Entry(
            key = "activity",
            perm = Manifest.permission.ACTIVITY_RECOGNITION,
            label = "活动识别",
            tools = listOf("step_count"),
            why = "计步、运动状态类界面需要它才能拿到真实的步数。",
            minSdk = 29
        ),

        // ---------- 只能跳设置的 ----------
        Entry(
            key = "exact_alarm",
            perm = null,
            label = "精确闹钟",
            tools = listOf("alarm_set"),
            why = "Android 12+ 起，精确到分钟的闹钟需单独在系统设置里放行。",
            settingsAction = "android.settings.REQUEST_SCHEDULE_EXACT_ALARM",
            minSdk = 31,
            kind = Kind.SETTINGS
        ),
        Entry(
            key = "overlay",
            perm = null,
            label = "悬浮窗",
            tools = listOf("overlay_show"),
            why = "让 AI 生成的界面能浮在其他应用之上（如悬浮计时器、桌面小挂件）。",
            settingsAction = "android.settings.action.MANAGE_OVERLAY_PERMISSION",
            kind = Kind.SETTINGS
        ),
        Entry(
            key = "usage_access",
            perm = null,
            label = "使用情况访问",
            tools = listOf("app_usage"),
            why = "统计各应用的真实使用时长，用于「屏幕时间」类界面。",
            settingsAction = "android.settings.USAGE_ACCESS_SETTINGS",
            kind = Kind.SETTINGS
        ),
        Entry(
            key = "dnd",
            perm = Manifest.permission.ACCESS_NOTIFICATION_POLICY,
            label = "勿扰模式",
            tools = listOf("dnd_toggle"),
            why = "系统控制面板里显示并切换真实的勿扰状态。",
            settingsAction = "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS",
            kind = Kind.SETTINGS
        ),

        // ---------- 自启动 ----------
        Entry(
            key = "autostart",
            perm = Manifest.permission.RECEIVE_BOOT_COMPLETED,
            label = "自启动",
            tools = listOf("autostart"),
            why = "开机后自动就绪，让 GenUI 的提醒/闹钟在重启后依然能正常唤醒。",
            settingsAction = "android.settings.APPLICATION_DETAILS_SETTINGS",
            kind = Kind.SETTINGS
        ),
    )

    /** 该权限项在当前设备上是否适用（版本门控） */
    fun applies(e: Entry): Boolean =
        Build.VERSION.SDK_INT >= e.minSdk && Build.VERSION.SDK_INT <= e.maxSdk

    /** 当前设备上真正应该展示的权限项 */
    val applicable: List<Entry> get() = all.filter { applies(it) }

    /** 运行时授权状态；无运行时权限的返回 null 表示"需去设置里看" */
    fun isGranted(ctx: Context, e: Entry): Boolean? {
        val p = e.perm ?: return null
        return ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    }

    /** 精确闹钟是否已被放行（Android 12+ 走 AlarmManager.canScheduleExactAlarms） */
    fun exactAlarmGranted(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return runCatching {
            (ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms()
        }.getOrDefault(false)
    }

    /** 悬浮窗是否已放行（只能靠 Settings.canDrawOverlays 判断，无运行时权限） */
    fun overlayGranted(ctx: Context): Boolean = runCatching {
        android.provider.Settings.canDrawOverlays(ctx)
    }.getOrDefault(false)

    /**
     * 使用情况访问是否已放行。
     * 这个权限没有 API 能直接查，只能用"检查 AppOps 是否被允许"的经典做法：
     * 查不到就按未授权处理（宁可让用户再去点一次设置，也不要谎报已开）。
     */
    @Suppress("DEPRECATION")
    fun usageAccessGranted(ctx: Context): Boolean = runCatching {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), ctx.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), ctx.packageName
            )
        }
        mode == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** 勿扰访问是否已放行 */
    fun dndGranted(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .isNotificationPolicyAccessGranted
    }.getOrDefault(false)

    /** 该权限项当前是否"已满足"（含无运行时权限的各项特判） */
    fun satisfied(ctx: Context, e: Entry): Boolean = when (e.key) {
        "exact_alarm" -> exactAlarmGranted(ctx)
        "overlay" -> overlayGranted(ctx)
        "usage_access" -> usageAccessGranted(ctx)
        "dnd" -> dndGranted(ctx)
        "autostart" -> autostartEnabled(ctx)
        else -> if (e.perm == null) false else isGranted(ctx, e) == true
    }

    /**
     * 自启动开关：是否已在权限屏点「去授权」（即用户愿意开机后让 GenUI 就绪）。
     * RECEIVE_BOOT_COMPLETED 已声明即视为权限存在，但最终能否开机自启还受厂商 ROM 限制，
     * 因此用本地开关代表"用户意图"，并在系统设置里引导手动放行自启动管理。
     */
    fun autostartEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("gen_prefs", Context.MODE_PRIVATE).getBoolean("autostart", false)

    /** 用户在权限屏点「去授权」时调用：记录意图，后续 BootReceiver 据此发通知 */
    fun setAutostart(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("gen_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("autostart", on).apply()
    }

    /**
     * 工具报错时的引导文案：告诉用户"哪个权限、去哪个屏、怎么开"。
     * 工具实现被拒时调用它，比"权限未授权"这种干话有用得多。
     */
    fun guideFor(tool: String): String {
        val hit = all.firstOrNull { tool in it.tools }
            ?: return "该能力不可用，可在「权」页查看详情。"
        val how = when (hit.kind) {
            Kind.RUNTIME -> "请到 权限屏（顶栏「权」）→ 系统权限 开启。"
            Kind.SETTINGS -> "这项权限系统不弹窗，请到 权限屏（顶栏「权」）点「去设置」手动放行。"
            Kind.DEPENDENT -> {
                val pre = all.firstOrNull { it.key == hit.requires }?.label
                if (pre != null) "需要先开启「$pre」，再到 权限屏（顶栏「权」）→ 系统权限 里开启本项。"
                else "请到 权限屏（顶栏「权」）→ 系统权限 开启。"
            }
        }
        return "需要「${hit.label}」权限：${hit.why}\n$how"
    }

    /** 覆盖统计：已授权 / 适用总数（用于概览条） */
    fun overview(ctx: Context): Pair<Int, Int> {
        val appl = applicable
        return appl.count { satisfied(ctx, it) } to appl.size
    }
}
