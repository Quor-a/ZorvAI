package com.ai.assistance.quro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.launch
import android.graphics.PixelFormat
import androidx.compose.ui.graphics.asImageBitmap
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ai.assistance.quro.R
import com.ai.assistance.quro.activity.QuroMainActivity
import com.ai.assistance.quro.core.pet.QuroPetStateBus
import com.ai.assistance.quro.core.util.QuroServiceLifecycleOwner
import com.ai.assistance.quro.core.tools.QuroVoiceFeaturePrefs
import com.ai.assistance.quro.genui.aiapp.ui.PetPrefs
import com.ai.assistance.quro.genui.sdk.components.PetPhase
import com.ai.assistance.quro.genui.sdk.components.PetSprite
import com.ai.assistance.quro.util.qstr
import kotlin.math.hypot

/**
 * 桌面宠物前台服务：独立于语音球的常驻 AI 伙伴悬浮窗。
 *
 * - 悬浮窗渲染 [PetSprite]（与对话框宠物同一形象，PetPrefs 配置共享），
 *   阶段实时取自 [QuroPetStateBus]（对话层写入 → 同进程 Snapshot 状态 → 自动重组）。
 * - 拖动移动，落点持久化（下次开启回到原位）；轻点打开应用；长按关闭宠物。
 * - 与语音球可同时开启：语音球负责语音交互，宠物负责「陪伴 + 状态可视化」。
 */
class QuroDesktopPetService : Service() {

    private var composeView: ComposeView? = null
    /** 🔴 当前宠物包 spritesheet 位图（addPet 时 IO 加载；null=无包，走 Canvas 矢量） */
    @Volatile var packImageBitmap: androidx.compose.ui.graphics.ImageBitmap? = null
    private var lifecycleOwner: QuroServiceLifecycleOwner? = null
    private var petDialog: android.app.AlertDialog? = null
    private var petParams: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var longPressPosted = false
    /** 息屏后暂停动画，避免无限 transition 在后台空转。 */
    private var screenInteractive = true

    /** 设置热更新 / 息屏亮屏 / 旋转：统一在这里驱动悬浮窗重建或重算。 */
    private val eventsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_APPLY_SETTINGS -> rebuildPet()
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    mainHandler.removeCallbacksAndMessages(null)
                    lifecycleOwner?.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenInteractive = true
                    rebuildPet() // 重建即恢复 RESUMED 与环境动画
                }
                Intent.ACTION_CONFIGURATION_CHANGED -> clampPetPosition()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 服务级接线（事件源在回合管理层而非 UI）：
        // 订阅 QuroAgentTrace —— 此前唯一生产者在 ChatScreen 组合里，GenUI 应用内聊天、
        // 语音对话、后台代理、App 被杀重启（START_STICKY 拉回）四种场景桌宠全部失联。
        // 仅在 IDLE 时写入，避免与 ChatScreen 的即时微调互相打架。
        // 🔴 2026-11-07：存 Job 引用，避免服务反复启停累积永久订阅者（onDestroy 里 cancel）。
        traceJob = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            com.ai.assistance.quro.core.agent.QuroAgentTrace.flow.collect { ev ->
                val bus = com.ai.assistance.quro.core.pet.QuroPetStateBus
                if (bus.phase != com.ai.assistance.quro.genui.sdk.components.PetPhase.IDLE) return@collect
                when (ev.kind) {
                    com.ai.assistance.quro.core.agent.QuroAgentTrace.TraceKind.ACTION -> {
                        val tool = ev.summary.substringAfter("执行工具：", "")
                            .substringBefore("（").split(',', '，').map { it.trim() }
                            .firstOrNull { it.isNotBlank() }
                        bus.onBusy(tool)
                    }
                    com.ai.assistance.quro.core.agent.QuroAgentTrace.TraceKind.THOUGHT ->
                        bus.onBusy(null)
                    else -> Unit
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_APPLY_SETTINGS)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, eventsReceiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFY_ID, buildNotification())
        if (intent?.action == ACTION_APPLY_SETTINGS) rebuildPet()
        else if (composeView == null) addPet()
        return START_STICKY
    }

    /** 订阅 QuroAgentTrace 的协程句柄；onDestroy 必须取消，否则服务重启会累积订阅者。 */
    private var traceJob: kotlinx.coroutines.Job? = null

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        traceJob?.cancel()
        traceJob = null
        runCatching { unregisterReceiver(eventsReceiver) }
        petDialog?.dismiss()
        petDialog = null
        composeView?.let { runCatching { windowManager.removeView(it) } }
        composeView = null
        lifecycleOwner?.destroy()
        lifecycleOwner = null
        super.onDestroy()
    }

    /** 设置保存后立刻重建悬浮窗（旧实现只在服务首次拉起时读一次 PetPrefs）。 */
    private fun rebuildPet() {
        mainHandler.removeCallbacksAndMessages(null)
        petDialog?.dismiss()
        petDialog = null
        composeView?.let { runCatching { windowManager.removeView(it) } }
        composeView = null
        lifecycleOwner?.destroy()
        lifecycleOwner = null
        if (screenInteractive) addPet()
    }

    /** 旋转 / 多窗口变化后把悬浮窗重新夹回可见区域。 */
    private fun clampPetPosition() {
        val view = composeView ?: return
        val p = petParams ?: return
        val dm = resources.displayMetrics
        p.x = p.x.coerceIn(0, (dm.widthPixels - view.width).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (dm.heightPixels - view.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(view, p) }
    }

    private val windowManager get() = getSystemService(WINDOW_SERVICE) as WindowManager

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, qstr(R.string.qk_04590), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, QuroMainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, javaClass).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(qstr(R.string.qk_04590))
            .setContentText(qstr(R.string.qk_04591))
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, qstr(R.string.qk_04593), stop)
            .setOngoing(true)
            .build()
    }

    private fun addPet() {
        val owner = QuroServiceLifecycleOwner().apply { create(); resume() }
        lifecycleOwner = owner
        val context = ContextThemeWrapper(this, getAppThemeRes())
        val petSettings = PetPrefs.load(this)
        val spec = petSettings.current
        val showBubble = petSettings.bubbleEnabled
        val thinkMatch = petSettings.thinkMatch
        val tapInteract = petSettings.tapInteract
        // 🔴 旧版这两个开关在桌宠服务里完全没读：设置页能看到、改了却不影响桌宠。
        val tapOpensPanel = petSettings.tapOpensPanel
        val petVisible = petSettings.petVisible
        val freePosition = petSettings.freePosition
        val interactions = petSettings.interactions
        val petScale = petSettings.petScale.coerceIn(0.5f, 2.0f)
        // 宠物包 + 位置模式
        val sp2 = getSharedPreferences(PREFS, MODE_PRIVATE)
        val placementMode = sp2.getString("placement_mode", "physics") ?: "physics"   // physics|freeroam|pinned
        val screenContext = sp2.getBoolean("screen_context", false)
        val packDirName = sp2.getString("pet_pack_dir", null)
        val packLoaded = packDirName?.let { dir ->
            runCatching {
                val (avails, _) = com.ai.assistance.quro.core.pet.PetPack.scan(this)
                avails.firstOrNull { it.dirName == dir }
            }.getOrNull()
        }
        packImageBitmap = packLoaded?.let { pk -> runCatching {
            val f = pk.manifest.sheetFile(this)
            val bmp: android.graphics.Bitmap? = if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.absolutePath) else null
            bmp?.asImageBitmap()
        }.getOrNull() }
        // 物理状态（40ms tick 驱动）
        val physicsPose = androidx.compose.runtime.mutableStateOf<String?>(null) // fall/thrown/walk/stand/dragged
        val velX = androidx.compose.runtime.mutableStateOf(0)
        val velY = androidx.compose.runtime.mutableStateOf(0)
        var lastMoveRawX = 0f; var lastMoveRawY = 0f; var lastMoveAt = 0L
        var roamTargetIdx = 0
        var roamDwell = 0
        var roamTarget: Pair<Float, Float>? = null

        // 环境动画：空闲时每隔 actionIntervalSec 随机播一个互动动作（约 2.6s 后回 IDLE），
        // 让宠物「活着」——这是桌宠而非桌面图标的关键差别。
        val ambientFlag = androidx.compose.runtime.mutableStateOf(false)
        var ambientAction = "actionBounce"
        val ambientEndRunnable = Runnable { ambientFlag.value = false }
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (screenInteractive && interactions.isNotEmpty() && !QuroPetStateBus.isActive) {
                    ambientAction = interactions.random().action
                    ambientFlag.value = true
                    mainHandler.postDelayed(ambientEndRunnable, 2600L)
                }
                mainHandler.postDelayed(this, petSettings.actionIntervalSec.coerceIn(10, 120) * 1000L)
            }
        }, petSettings.actionIntervalSec.coerceIn(10, 120) * 1000L)


        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                if (spec != null) {
                    // 阶段仲裁：真实对话状态优先（QuroPetStateBus）；空闲时环境动画接管；
                    // 「显示思考匹配」关闭时不把 THINKING 投影到宠物。
                    val busPhase = QuroPetStateBus.phase
                    // 回复完成的庆祝态优先于环境动作，保证「完成了」这一下不会被随机动作盖掉。
                    val celebrate = QuroPetStateBus.celebrateText
                    val effectivePhase = when {
                        celebrate != null -> PetPhase.DONE
                        ambientFlag.value -> if (ambientAction == "think") PetPhase.THINKING else PetPhase.PERFORMING
                        busPhase == PetPhase.THINKING && !thinkMatch -> PetPhase.IDLE
                        else -> busPhase
                    }
                    // 🔴 宠物包渲染优先（spritesheet 帧动画），无包回退 Canvas 矢量
                    // 位图在 addPet 时加载一次（IO 线程），Compose 层只读
                    val packBitmap: androidx.compose.ui.graphics.ImageBitmap? = packImageBitmap
                    val physicsPoseV = physicsPose.value
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.scale(petScale),
                    ) {
                        if (packBitmap != null && packLoaded != null) {
                            // spritesheet 帧动画路径：姿态取物理状态，动作取 PERFORMING 的包互动动画
                            val poseName = when {
                                physicsPoseV != null -> physicsPoseV
                                effectivePhase == PetPhase.PERFORMING -> "celebrate"
                                effectivePhase == PetPhase.THINKING || effectivePhase == PetPhase.PLANNING -> "think"
                                effectivePhase == PetPhase.TOOL_CALLING -> "usetool"
                                effectivePhase == PetPhase.GENERATING -> "reply"
                                effectivePhase == PetPhase.DONE -> "celebrate"
                                else -> "stand"
                            }
                            val bubbleTxt = when {
                                !showBubble -> ""
                                QuroPetStateBus.statusText != null -> QuroPetStateBus.statusText!!
                                effectivePhase == PetPhase.TOOL_CALLING && !QuroPetStateBus.toolName.isNullOrBlank()
                                    -> "正在调用 " + QuroPetStateBus.toolName
                                else -> ""
                            }
                            com.ai.assistance.quro.genui.sdk.components.PetSpriteFrames(
                                bitmap = packBitmap,
                                cellWidth = packLoaded.manifest.cellWidth,
                                cellHeight = packLoaded.manifest.cellHeight,
                                displayWidth = packLoaded.manifest.displayWidth,
                                displayHeight = packLoaded.manifest.displayHeight,
                                animations = packLoaded.manifest.animations.mapValues {
                                    com.ai.assistance.quro.genui.sdk.components.PetPackAnimDef(
                                        it.value.row, it.value.frames, it.value.frameDurationMs, it.value.playback
                                    )
                                },
                                pose = poseName,
                                facingRight = true,
                                mode = placementMode,
                                bubbleText = bubbleTxt,
                            )
                        } else PetSprite(
                            spec = spec,
                            phase = effectivePhase,
                            toolName = QuroPetStateBus.toolName,
                            showBubble = showBubble,
                        )
                        (celebrate ?: QuroPetStateBus.statusText)?.let { st ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                st,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .background(Color(0xFF555555).copy(alpha = 0.85f), CircleShape)
                                    .padding(8.dp, 3.dp),
                            )
                        }
                    }
                }
            }
        }
        composeView = view

        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = sp.getInt(K_X, -1)
            y = sp.getInt(K_Y, -1)
        }
        runCatching { windowManager.addView(view, params) }
        petParams = params
        // 物理引擎 tick（40ms 循环；physics=重力/投掷/走动，freeroam=5点游走，pinned=静止）
        val dm0 = resources.displayMetrics
        val screenH = dm0.heightPixels
        val screenW = dm0.widthPixels
        val gravityPx = 1.4f; val maxFallPx = 24f; val walkPx = 1.8f
        val physicsRunnable = object : Runnable {
            override fun run() {
                if (placementMode != "pinned" && petVisible) {
                    when (placementMode) {
                        "physics" -> {
                            val floorY = screenH - view.height
                            when (physicsPose.value) {
                                "thrown", "fall" -> {
                                    var vy = velY.value + 1
                                    if (vy > 24) vy = 24
                                    var vx = (velX.value * 0.88f).toInt()
                                    var nx = (params.x + vx).coerceIn(0, (screenW - view.width).coerceAtLeast(0))
                                    var ny = (params.y + vy).coerceAtMost(floorY)
                                    if (nx <= 0 || nx >= screenW - view.width) vx = -(vx * 0.88f).toInt()
                                    if (ny >= floorY) { vy = 0; vx = (vx * 0.78f).toInt(); physicsPose.value = "walk" }
                                    velX.value = vx; velY.value = vy
                                    params.x = nx.toInt(); params.y = ny.toInt()
                                }
                                "walk" -> {
                                    // 沿地面走：velX 决定方向，碰壁回头
                                    var vx = velX.value
                                    if (vx == 0) vx = 2
                                    var nx = params.x + vx
                                    if (nx <= 0) { nx = 0; velX.value = 2 }
                                    if (nx >= screenW - view.width) { nx = screenW - view.width; velX.value = -2 }
                                    params.x = nx.toInt()
                                }
                                else -> physicsPose.value = "fall"
                            }
                            runCatching { windowManager.updateViewLayout(view, params) }
                            sp.edit().putInt(K_X, params.x).putInt(K_Y, params.y).apply()
                        }
                        "freeroam" -> {
                            // 5 点游走（水平 18%/垂直 16% 内缩：左中/上中/右中/下中/中心）
                            if (roamTarget == null && roamDwell <= 0) {
                                val insetW = (screenW * 0.18f).toInt(); val insetH = (screenH * 0.16f).toInt()
                                val cx = screenW / 2; val cy = screenH / 2
                                roamTarget = when (roamTargetIdx % 5) {
                                    0 -> Pair(insetW.toFloat(), (cy - view.height / 2).toFloat())
                                    1 -> Pair((cx - view.width / 2).toFloat(), insetH.toFloat())
                                    2 -> Pair((screenW - insetW - view.width).toFloat(), (cy - view.height / 2).toFloat())
                                    3 -> Pair((cx - view.width / 2).toFloat(), (screenH - insetH - view.height).toFloat())
                                    else -> Pair((cx - view.width / 2).toFloat(), (cy - view.height / 2).toFloat())
                                }
                                roamTargetIdx++
                            }
                            val t = roamTarget
                            if (t != null) {
                                val dx = t.first - params.x; val dy = t.second - params.y
                                val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble())
                                if (dist <= 18) { roamDwell = 70; roamTarget = null }
                                else {
                                    params.x += (dx / dist * 1.6f).toInt()
                                    params.y += (dy / dist * 1.6f).toInt()
                                    runCatching { windowManager.updateViewLayout(view, params) }
                                }
                            }
                            if (roamDwell > 0) roamDwell--
                        }
                    }
                }
                mainHandler.postDelayed(this, 40L)
            }
        }
        mainHandler.postDelayed(physicsRunnable, 40L)

        // petVisible=false 时不显示桌宠，但仍保留服务与状态总线（开关可随时热更新回来）。
        view.visibility = if (petVisible) View.VISIBLE else View.GONE

        // 首次（无持久化坐标）落到右下角
        if (params.x < 0 || params.y < 0) {
            view.post {
                val dm: DisplayMetrics = resources.displayMetrics
                params.x = (dm.widthPixels - view.width - (24 * dm.density).toInt()).coerceAtLeast(0)
                params.y = (dm.heightPixels / 3).coerceAtLeast(0)
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }

        // 触摸：拖动移动并持久化 / 轻点打开应用 / 长按关闭
        var downX = 0f; var downY = 0f; var offsetX = 0f; var offsetY = 0f; var moved = false
        val threshold = (MOVE_THRESHOLD_DP * resources.displayMetrics.density).toInt()
        val longPressRunnable = Runnable {
            if (!moved) {
                //    旧实现长按=直接关闭桌宠（破坏性误触）。菜单项：互动动作 / 打开对话 / 关闭。
                runCatching {
                    // 🔴 修复叠层（2026-11-07）：再次长按时先关掉上一个对话框 ——
                    //    旧实现只替换引用不 dismiss，连续长按会叠出好几层菜单。
                    petDialog?.dismiss()
                    petDialog = null
                    val curMode = getSharedPreferences(PREFS, MODE_PRIVATE).getString("placement_mode", "physics") ?: "physics"
                    val modeLabel = when (curMode) { "freeroam" -> "自由活动"; "pinned" -> "固定当前位置"; else -> "物理活动" }
                    val items = arrayOf("💬 对它说", "🎭 播互动动作", "⇄ 模式：" + modeLabel, "💬 打开对话", "✖ 关闭桌宠")
                    petDialog = android.app.AlertDialog.Builder(this)
                        .setTitle(qstr(R.string.qk_04593))
                        .setItems(items) { d, which ->
                            d.dismiss()
                            when (which) {
                                0 -> {
                                    // 对它说：透明输入条 → 自动对话回合
                                    // 🔴 2026-11-07：Service 上下文 startActivity 必须带 NEW_TASK，
                                    //    否则抛 AndroidRuntimeException 被 runCatching 吞掉，输入框打不开。
                                    runCatching { startActivity(Intent(this, com.ai.assistance.quro.activity.PetInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                }
                                1 -> {
                                    val act = interactions.randomOrNull()
                                    if (act != null) {
                                        val thinkAct = act.action == "think"
                                        QuroPetStateBus.statusText = act.label
                                        QuroPetStateBus.phase = if (thinkAct) PetPhase.THINKING else PetPhase.PERFORMING
                                        mainHandler.postDelayed({
                                            QuroPetStateBus.statusText = null
                                            if (QuroPetStateBus.phase == PetPhase.THINKING || QuroPetStateBus.phase == PetPhase.PLANNING || QuroPetStateBus.phase == PetPhase.PERFORMING) {
                                                QuroPetStateBus.phase = PetPhase.IDLE
                                            }
                                        }, 2600L)
                                    }
                                }
                                2 -> {
                                    // 模式三态轮换（物理/自由/固定）
                                    val spMode = getSharedPreferences(PREFS, MODE_PRIVATE)
                                    val next = when (spMode.getString("placement_mode", "physics")) { "physics" -> "freeroam"; "freeroam" -> "pinned"; else -> "physics" }
                                    spMode.edit().putString("placement_mode", next).apply()
                                    sendBroadcast(Intent("com.ai.assistance.quro.action.DESKTOP_PET_APPLY_SETTINGS").setPackage(packageName))
                                }
                                3 -> runCatching {
                                    startActivity(Intent(this, QuroMainActivity::class.java).apply {
                                        action = Intent.ACTION_MAIN
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    })
                                }
                                4 -> stopSelf()
                            }
                        }
                        .create()
                    // 🔴 2026-11-07 修复：服务上下文弹窗必须「先设悬浮窗类型、再 show()」。
                    //    旧实现 .show() 在前，以 TYPE_APPLICATION 调 addView 会抛 BadTokenException
                    //    （被外层 runCatching 吞掉），长按菜单永远弹不出来。
                    // 持有引用以便 onDestroy / rebuildPet 时 dismiss，避免窗口泄漏。
                    petDialog?.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    petDialog?.show()
                }
            }
        }
        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    offsetX = ev.rawX - params.x; offsetY = ev.rawY - params.y
                    moved = false
                    longPressPosted = true
                    mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (moved) {
                        // 记录最近位移（供拖拽结束计算投掷初速）
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastMoveAt > 16L) { lastMoveRawX = ev.rawX; lastMoveRawY = ev.rawY; lastMoveAt = now }
                        if (placementMode == "physics") physicsPose.value = "dragged"
                    }
                    if (!moved && hypot(dx, dy) > threshold) {
                        moved = true
                        lastMoveRawX = ev.rawX; lastMoveRawY = ev.rawY; lastMoveAt = android.os.SystemClock.uptimeMillis()
                        if (longPressPosted) { mainHandler.removeCallbacks(longPressRunnable); longPressPosted = false }
                    }
                    if (moved) {
                        val dm = resources.displayMetrics
                        params.x = (ev.rawX - offsetX).toInt()
                            .coerceIn(0, (dm.widthPixels - view.width).coerceAtLeast(0))
                        params.y = (ev.rawY - offsetY).toInt()
                            .coerceIn(0, (dm.heightPixels - view.height).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (longPressPosted) { mainHandler.removeCallbacks(longPressRunnable); longPressPosted = false }
                    if (!moved) {
                        // tapOpensPanel=true 且未开轻点互动 → 轻点打开主界面并预填「戳了戳桌宠」；
                        // 关掉它（或开了轻点互动）→ 轻点只播互动动作，不抢占前台。
                        if (tapInteract || !tapOpensPanel) {
                            // 🔴 包互动带提示词模板（{input}←互动label）+ 可选屏幕上下文 → 自动对话回合
                            val packInter = packLoaded?.manifest?.interactions
                                ?.filter { it.enabled }?.randomOrNull()
                            if (packInter != null && packInter.prompt.isNotBlank()) {
                                var prompt = packInter.prompt.replace("{input}", packInter.label)
                                if (screenContext) {
                                    val screenText = runCatching {
                                        com.ai.assistance.quro.core.tools.ReadScreenTool().run(this, "{}").take(3000)
                                    }.getOrNull()
                                    if (!screenText.isNullOrBlank()) {
                                        prompt += "\n\n【当前屏幕线索】这些内容来自用户点击桌宠时的当前屏幕，请结合回答。\n" + screenText
                                    }
                                }
                                QuroPetStateBus.onInteract(packInter.label, packInter.animation ?: "celebrate")
                                com.ai.assistance.quro.ui.QuroShareBridge.emitAutoSend(prompt)
                            } else {
                            val act = interactions.randomOrNull()
                            QuroPetStateBus.statusText = act?.label ?: "在这里"
                            QuroPetStateBus.phase = if (act?.action == "think") PetPhase.THINKING else PetPhase.PERFORMING
                            mainHandler.postDelayed({
                                QuroPetStateBus.statusText = null
                                // 互动动作结束后只在仍是互动态时归位，别把真实的 AI 生成状态清掉。
                                if (QuroPetStateBus.phase == PetPhase.THINKING || QuroPetStateBus.phase == PetPhase.PLANNING || QuroPetStateBus.phase == PetPhase.PERFORMING) {
                                    QuroPetStateBus.phase = PetPhase.IDLE
                                }
                            }, 2600L)
                            }
                        } else {
                            //    经分享桥预填输入框并打开主界面；分享桥已有快照消费逻辑（prefill 后自动发送可由用户确认）。
                            runCatching {
                                com.ai.assistance.quro.ui.QuroShareBridge.emitAutoSend("戳了戳桌宠")
                                startActivity(
                                    Intent(this, QuroMainActivity::class.java).apply {
                                        action = Intent.ACTION_MAIN
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    },
                                )
                            }
                        }
                    } else {
                        // 🔴 拖拽结束：物理模式=计算投掷初速进抛体；吸附右缘逻辑仅在非物理模式保留
                        if (placementMode == "physics") {
                            val now = android.os.SystemClock.uptimeMillis()
                            val dt = (now - lastMoveAt).coerceAtLeast(1L)
                            // 速度 px/tick(40ms)：最近一次 MOVE 位移按时间折算
                            val vx = (((ev.rawX - lastMoveRawX) / dt.toFloat() * 40f).coerceIn(-36f, 36f)).toInt()
                            val vy = (((ev.rawY - lastMoveRawY) / dt.toFloat() * 40f).coerceIn(-36f, 36f)).toInt()
                            velX.value = vx; velY.value = vy
                            physicsPose.value = "thrown"
                            sp.edit().putInt(K_X, params.x).putInt(K_Y, params.y).apply()
                        } else {
                            if (!freePosition) {
                                val dm = resources.displayMetrics
                                params.x = (dm.widthPixels - view.width).coerceAtLeast(0)
                                runCatching { windowManager.updateViewLayout(view, params) }
                            }
                            sp.edit().putInt(K_X, params.x).putInt(K_Y, params.y).apply()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (longPressPosted) { mainHandler.removeCallbacks(longPressRunnable); longPressPosted = false }
                    true
                }
                else -> false
            }
        }
    }

    private fun getAppThemeRes(): Int {
        return resources.getIdentifier("Theme.Quro", "style", packageName).let {
            if (it != 0) it else android.R.style.Theme_Material_Light
        }
    }

    companion object {
        private const val CHANNEL_ID = "quro_desktop_pet"
        private const val NOTIFY_ID = 10086
        private const val PREFS = "quro_desktop_pet"
        private const val K_X = "x"
        private const val K_Y = "y"
        private const val MOVE_THRESHOLD_DP = 12
        private const val LONG_PRESS_MS = 600L
        const val ACTION_STOP = "com.ai.assistance.quro.action.DESKTOP_PET_STOP"
        /** 设置保存后由 PetPrefs.save 广播，运行中的服务据此重建悬浮窗。 */
        const val ACTION_APPLY_SETTINGS = "com.ai.assistance.quro.action.DESKTOP_PET_APPLY_SETTINGS"

        /** 开启桌面宠物（含开关持久化）；悬浮窗权限缺失时返回 false 并不拉起服务。 */
        fun start(ctx: android.content.Context): Boolean {
            QuroVoiceFeaturePrefs.setDesktopPet(ctx, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(ctx)) {
                return false
            }
            ctx.startForegroundService(Intent(ctx, QuroDesktopPetService::class.java))
            return true
        }

        /** 关闭桌面宠物（含开关持久化）。 */
        fun stop(ctx: android.content.Context) {
            QuroVoiceFeaturePrefs.setDesktopPet(ctx, false)
            ctx.stopService(Intent(ctx, QuroDesktopPetService::class.java))
        }
    }
}
