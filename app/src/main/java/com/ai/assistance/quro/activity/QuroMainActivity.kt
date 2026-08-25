package com.ai.assistance.quro.activity

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Parcelable
import android.os.Process
import android.os.StrictMode
import androidx.lifecycle.lifecycleScope
import com.ai.assistance.quro.BuildConfig
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ai.assistance.quro.core.tools.QuroPermissionHolder
import com.ai.assistance.quro.util.AnrMonitor
import com.ai.assistance.quro.core.tools.QuroPermissionRequester
import com.ai.assistance.quro.service.QuroVoiceBallService
import com.ai.assistance.quro.core.tools.QuroVoiceFeaturePrefs
import com.ai.assistance.quro.core.tools.QuroUiActionBridge
import com.ai.assistance.quro.core.QuroCrashReporter
import com.ai.assistance.quro.core.QuroReplyNotifier
import com.ai.assistance.quro.ui.QuroApp
import com.ai.assistance.quro.ui.QuroShareBridge
import com.ai.assistance.quro.ui.QuroSplashContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * Zorv AI 入口 Activity。
 * 负责麦克风/悬浮窗权限、悬浮语音球开关，并作为工具运行时权限请求的网关。
 */
class QuroMainActivity : ComponentActivity(), QuroPermissionRequester {
    private val RECORD_CODE = 1001
    private val NOTIF_CODE = 1002
    private var voiceBallEnabled by mutableStateOf(false)
    private var showSplash by mutableStateOf(true)

    private lateinit var permLauncher: ActivityResultLauncher<Array<String>>
    @Volatile private var permContinuation: Continuation<Boolean>? = null
    // ★ #763 ANR 诊断：主线程看门狗。
    // 每 100ms 探测主线程是否响应，超过 1s 即抓取主线程当前堆栈并落盘到
    // getExternalFilesDir("anr_reports")/anr_report_*.txt，同时打 logcat。
    private lateinit var anrMonitor: AnrMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ★ #763 ANR 诊断：开启 StrictMode 主线程策略（仅 debug 构建）。
        // 一旦主线程出现磁盘读/写、网络访问或资源泄漏，会在 logcat 打印完整调用栈（penaltyLog）。
        // 用户用 LogFox 抓取日志即可定位「突然出现的 ANR」的真实主线程阻塞点，便于精确修复。
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectActivityLeaks()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build()
            )
        }
        // ★ #763 ANR 诊断：启动主线程看门狗。debug 构建常驻，
        // 真实用户反馈「对话框区域突然 ANR」时，本机复现不了、又读不到截图，
        // 靠它抓出卡死那一行的精确堆栈，比读代码猜根因可靠。报告在 anr_reports/。
        anrMonitor = AnrMonitor(this, lifecycleScope)
        anrMonitor.start()
        // 让崩溃上报器尽早能把日志落盘（甚至在 QuroApp 组合之前发生的崩溃也能留痕）。
        QuroCrashReporter.crashDir = filesDir
        // 全局兜底：把主线程（含 Compose 组合）里未捕获的异常记录下来，
        // 再以「干净退出」代替系统「已停止」弹窗——这样下次启动能回放崩溃文本，
        // 用户可复制发回定位「设置闪退 / 无法对话」的真凶，而不是无声闪退且无迹可寻。
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            QuroCrashReporter.report(throwable, "uncaught:$thread")
            // 不再委派给系统默认 killer（那会直接杀死进程且不留痕），改为干净结束，
            // 让 filesDir/quro_crash_pending.log 的崩溃记录能在下次启动被回放。
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        // 工具权限请求网关：注册多权限启动器，绑定到本 Activity 生命周期。
        permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val allGranted = result.values.all { it }
            permContinuation?.resume(allGranted)
            permContinuation = null
        }
        QuroPermissionHolder.requester = this

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_CODE,
            )
        }
        // Android 13+ 必须运行时授予通知权限，否则前台服务的常住通知会被静默压制（通知栏不显示）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIF_CODE,
                )
            }
        }
        // 特殊权限引导：MEDIA 管理（MANAGE_EXTERNAL_STORAGE）
        // 不走普通 runtime 弹窗，需跳系统设置页授权；此处按需拉起一次，让用户直接开通。
        requestSpecialPermissions()
        // 初始化语音球开关（持久化），供设置页开关初始态显示
        voiceBallEnabled = QuroVoiceFeaturePrefs.getVoiceBall(this)
        setContent {
            Box(Modifier.fillMaxSize()) {
                QuroApp(
                    voiceBallEnabled = voiceBallEnabled,
                    onToggleVoiceBall = ::toggleVoiceBall,
                )
                if (showSplash) {
                    QuroSplashContent(Modifier.fillMaxSize())
                }
            }
            LaunchedEffect(Unit) {
                delay(800)
                showSplash = false
            }
        }
        handleIntent(intent)
        // 常住通知栏：软件一启动就拉起前台服务（通知栏常驻），与语音球开关解耦。
        // 即使未授予悬浮窗权限，通知栏也会常驻；悬浮球由通知栏「语音球」按钮 / 设置开关显隐。
        val svc = Intent(this, QuroVoiceBallService::class.java).apply {
            putExtra(QuroVoiceBallService.EXTRA_NO_LISTEN, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        QuroReplyNotifier.isAppForeground = true
    }

    override fun onPause() {
        super.onPause()
        QuroReplyNotifier.isAppForeground = false
    }

    /** 处理外部拉起意图：系统分享（ACTION_SEND / SEND_MULTIPLE）写入分享桥；通知栏「聊天框」动作仅把应用带到前台。 */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.clipData?.getItemAt(0)?.text?.toString()
                val uris = collectSharedUris(intent)
                if (!text.isNullOrBlank() || uris.isNotEmpty()) {
                    // 发送方若授予了可持久化读权限，则接管，避免稍后发送时 Uri 失效
                    if (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
                        uris.forEach { u ->
                            runCatching {
                                contentResolver.takePersistableUriPermission(
                                    u,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                )
                            }
                        }
                    }
                    QuroShareBridge.emit(text, uris)
                }
            }
            QuroVoiceBallService.ACTION_OPEN_CHAT -> {
                // 已是主界面，无需额外处理，仅回到前台
            }
            QuroVoiceBallService.ACTION_SHORTCUT_VOICE_BALL -> {
                // 应用快捷方式「语音球」：确保悬浮语音球显示（已在开屏拉起常驻服务）。
                if (!voiceBallEnabled) toggleVoiceBall(true)
                else {
                    // 已开启则确保服务把球挂上（防服务被杀后球丢失）
                    val i = Intent(this, QuroVoiceBallService::class.java).apply {
                        action = QuroVoiceBallService.ACTION_VOICE_TALK
                        putExtra(QuroVoiceBallService.EXTRA_BALL_SHOW, true)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
                }
            }
            QuroVoiceBallService.ACTION_SHORTCUT_CHAT -> {
                // 应用快捷方式「小窗对话」：已是主界面（对话即主 UI），仅回到前台。
                // 后续如需真正的悬浮迷你对话窗，可在此拉起 overlay 窗口。
            }
            // 桌面组件 / 通知 / 外部入口：携带 ui_action（如 ui_open_schedule）打开对应界面。
            // 走 QuroUiActionBridge.request：UI 桥就绪立即分发，否则暂存待 ChatScreen 组合后补派。
            else -> {
                val uiAction = intent.getStringExtra("ui_action")
                if (!uiAction.isNullOrBlank()) QuroUiActionBridge.request(uiAction)
            }
        }
    }

    /** 从分享意图中收集所有文件 Uri（单文件 EXTRA_STREAM / 多文件 SEND_MULTIPLE / clipData 兜底）。 */
    private fun collectSharedUris(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND) {
            (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let { uris.add(it) }
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)?.forEach {
                (it as? Uri)?.let { u -> uris.add(u) }
            }
        }
        intent.clipData?.let { cd ->
            for (i in 0 until cd.itemCount) {
                cd.getItemAt(i).uri?.let { uris.add(it) }
            }
        }
        return uris.distinct()
    }

    override fun onDestroy() {
        if (::anrMonitor.isInitialized) anrMonitor.stop()
        QuroPermissionHolder.requester = null
        super.onDestroy()
    }

    /** 工具权限网关实现：切主线程弹系统授权框并挂起等待结果。 */
    override suspend fun ensure(permissions: List<String>): Boolean {
        val ctx = this as Context
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                permContinuation = cont
                permLauncher.launch(missing.toTypedArray())
                cont.invokeOnCancellation { permContinuation = null }
            }
        }
    }

    /**
     * 特殊权限引导：仅处理 MANAGE_EXTERNAL_STORAGE（媒体/文件管理）——它不属于普通
     * runtime 弹窗权限，必须跳系统设置页由用户手动开通，启动时按需拉起一次。
     *
     * 闹钟和提醒（SCHEDULE_EXACT_ALARM）【不再】在这里自动跳转：此前无脑跳转、且应用又因
     * 声明 USE_EXACT_ALARM 而不显示在「闹钟和提醒」列表中，导致「打开→跳设置→找不到→返回→
     * 再跳」死循环。现改为：Manifest 仅声明 SCHEDULE_EXACT_ALARM（使本应用出现在列表），
     * 授权入口统一放在应用内「权限」页的「闹钟与提醒」卡片（按钮 → openExactAlarmSettings），
     * 不再打扰式自动跳转。未授权时 AlarmPermissionHelper / QuroScheduledTask 会优雅降级。
     */
    private fun requestSpecialPermissions() {
        // 闹钟权限：SET_ALARM 是危险权限，需要运行时请求
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SET_ALARM)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.SET_ALARM),
                RECORD_CODE // 复用 RECORD_CODE，不影响现有逻辑
            )
        }
        // 媒体/文件管理：Android 11+ 需进入「所有文件访问权限」设置页开通
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (_: Throwable) { /* 个别 ROM 无此入口，忽略 */ }
        }
        // 闹钟和通知权限不在这里自动跳转——
        // - SCHEDULE_EXACT_ALARM：由 Manifest 声明 + QuroScheduleBootReceiver intent-filter 使应用
        //   出现在「闹钟和提醒」列表；用户手动授权后 AlarmManager 即可用。
        //   ⚠️ 覆盖安装不会更新权限列表，必须卸载重装！
        // - POST_NOTIFICATIONS：在上方 onCreate() 已通过 ActivityCompat.requestPermissions 弹窗请求。
        //   被拒后由用户在「设置→应用→通知」手动开启，不自动跳转避免死循环。
        // 悬浮窗权限：可视化弹窗默认需要，启动时引导开启
        if (!Settings.canDrawOverlays(this)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"))
                )
            } catch (_: Throwable) { /* 个别 ROM 无此入口，忽略 */ }
        }
    }

    private fun toggleVoiceBall(enabled: Boolean) {
        voiceBallEnabled = enabled
        QuroVoiceFeaturePrefs.setVoiceBall(this, enabled)
        // 常住通知栏服务已由 onCreate 拉起；这里只控制悬浮球显隐（不启停服务）。
        val intent = Intent(this, QuroVoiceBallService::class.java).apply {
            action = QuroVoiceBallService.ACTION_VOICE_TALK
            putExtra(QuroVoiceBallService.EXTRA_BALL_SHOW, enabled)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        if (enabled && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "开启语音球需授予「悬浮窗」权限", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }
}
