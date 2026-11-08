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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.produceState
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.clickable
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
import com.ai.assistance.quro.core.pet.PetPack
import com.ai.assistance.quro.core.pet.PetPackAssets
import com.ai.assistance.quro.core.util.QuroServiceLifecycleOwner
import com.ai.assistance.quro.core.tools.QuroVoiceFeaturePrefs
import com.ai.assistance.quro.genui.aiapp.ui.PetPrefs
import com.ai.assistance.quro.genui.sdk.components.PetPhase
import com.ai.assistance.quro.genui.sdk.components.PetSprite
import com.ai.assistance.quro.genui.sdk.components.petPoseAnimationCandidates
import com.ai.assistance.quro.ui.pet.PetGlyph
import com.ai.assistance.quro.ui.pet.PetMenuItem
import com.ai.assistance.quro.ui.pet.PetVisuals
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
    /**
     * 当前动作所在分片的位图。用 Snapshot State 持有：首帧由 IO 线程解码后回填，
     * 主线程绝不做 BitmapFactory.decodeFile（1280×2816 的 WebP 解码会直接拖出 ANR）。
     * null 时走 Canvas 矢量兜底，加载完自动重组切到雪碧图。
     */
    private val packImageState = androidx.compose.runtime.mutableStateOf<ImageBitmap?>(null)
    @Volatile private var currentManifest: PetPack.Manifest? = null
    /** 记录当前包名：只有真正换了包才清空 LRU，避免每次设置热更新都重解码。 */
    private var lastPackDir: String? = null
    /** 首帧异步解码任务；服务销毁 / 窗口重建时取消。 */
    private var sheetLoadJob: kotlinx.coroutines.Job? = null
    /** 轻点互动的屏幕线索采集任务（IO 线程，主线程零阻塞）。 */
    private var tapCueJob: kotlinx.coroutines.Job? = null

    /** 分片位图 LRU：上百段动作拆成多张图，只保留最近 3 张，避免常驻巨型位图。 */
    private val sheetCache = object : LinkedHashMap<String, ImageBitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > 3
    }

    /** 只读缓存命中，供 Compose 首帧使用（避免跨分片切换时闪一帧错图）。 */
    @Synchronized
    private fun peekSheetBitmap(animName: String): ImageBitmap? {
        val m = currentManifest ?: return null
        return sheetCache[m.sheetKeyFor(animName)]
    }

    /** 按动画名解码它所在的那张分片；同一张分片只解码一次。 */
    @Synchronized
    private fun loadSheetBitmap(animName: String): ImageBitmap? {
        val m = currentManifest ?: return null
        val key = m.sheetKeyFor(animName)
        sheetCache[key]?.let { return it }
        val file = m.sheetFileFor(this, animName)
        val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
        val img = bmp.asImageBitmap()
        sheetCache[key] = img
        return img
    }
    private var lifecycleOwner: QuroServiceLifecycleOwner? = null
    private var petParams: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var longPressPosted = false
    /** 息屏后暂停动画，避免无限 transition 在后台空转。 */
    private var screenInteractive = true

    // ── 悬浮面板（输入框 / 快捷菜单）与结构化接线状态 ──
    private enum class PetPanel { None, Menu, Input }
    private val petPanel = androidx.compose.runtime.mutableStateOf(PetPanel.None)
    private val petInputText = androidx.compose.runtime.mutableStateOf("")
    private val petStatus = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val petBusy = androidx.compose.runtime.mutableStateOf(false)
    private val petReplyText = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val petScreenMode = androidx.compose.runtime.mutableStateOf<com.ai.assistance.quro.core.pet.PetScreenBrain.Mode?>(null)
    private val petMenuItems = androidx.compose.runtime.mutableStateOf<List<com.ai.assistance.quro.ui.pet.PetMenuItem>>(emptyList())
    private val petScopeJob = kotlinx.coroutines.SupervisorJob()
    private val petScope = kotlinx.coroutines.CoroutineScope(
        petScopeJob + kotlinx.coroutines.Dispatchers.Main.immediate,
    )
    private var screenJob: kotlinx.coroutines.Job? = null
    private var replyJob: kotlinx.coroutines.Job? = null
    private var statusJob: kotlinx.coroutines.Job? = null

    /** 面板展开时窗口必须可聚焦：输入框要弹软键盘，菜单要能收到返回键收起。 */
    private fun setPetWindowFocusable(focusable: Boolean) {
        val v = composeView ?: return
        val p = petParams ?: return
        p.flags = if (focusable) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { windowManager.updateViewLayout(v, p) }
    }

    /** 面板展开时刻：用于忽略长按抬手那一下被误判成「点外面收起」。 */
    private var panelShownAt = 0L

    private fun dismissPanelFromOutside() {
        if (android.os.SystemClock.uptimeMillis() - panelShownAt < 350L) return
        showPetPanel(PetPanel.None)
    }

    private fun showPetPanel(panel: PetPanel) {
        petPanel.value = panel
        if (panel != PetPanel.None) panelShownAt = android.os.SystemClock.uptimeMillis()
        setPetWindowFocusable(panel != PetPanel.None)
        if (panel == PetPanel.None) {
            petInputText.value = ""
        } else if (panel == PetPanel.Menu) {
            // 菜单里没有输入框，焦点留在外层 ComposeView，返回键才会派发到 OnKeyListener。
            // Input 面板不抢焦点，交给 BasicTextField 自己 requestFocus（否则软键盘弹不出来）。
            composeView?.let { v ->
                v.isFocusableInTouchMode = true
                v.requestFocus()
            }
        }
    }

    private fun openChat() {
        runCatching {
            startActivity(Intent(this, QuroMainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
    }

    /** 所有桌宠回合的唯一出口：结构化接线 + 按需拉起主对话框。 */
    private fun emitPetTurn(turn: com.ai.assistance.quro.core.pet.PetTurn) {
        com.ai.assistance.quro.ui.QuroShareBridge.emitPetTurn(turn)
        if (turn.openChat) openChat()
    }

    /** 屏幕分析 / 后台虚拟屏任务：全程 IO，主线程零阻塞。 */
    private fun launchScreenTurn(task: Boolean, text: String) {
        val sp = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
        val preferVirtual = sp.getBoolean("pet_virtual_screen", true)
        petBusy.value = true
        petStatus.value = if (preferVirtual) "正在准备后台虚拟屏…" else "正在看屏幕…"
        screenJob?.cancel()
        screenJob = petScope.launch {
            val insight = com.ai.assistance.quro.core.pet.PetScreenBrain.observe(
                this@QuroDesktopPetService,
                preferVirtual = preferVirtual,
                withScreenshot = true,
            )
            petBusy.value = false
            petStatus.value = insight.status
            petScreenMode.value = insight.mode
            val turn = if (task) {
                com.ai.assistance.quro.core.pet.QuroPetTurn.task(text, insight.toHiddenContext())
            } else {
                com.ai.assistance.quro.core.pet.QuroPetTurn.screen(text, insight.toHiddenContext())
            }
            emitPetTurn(turn)
            showPetPanel(PetPanel.None)
            scheduleStatusClear()
        }
    }

    private fun scheduleStatusClear() {
        statusJob?.cancel()
        statusJob = petScope.launch {
            kotlinx.coroutines.delay(3_600L)
            petStatus.value = null
            petScreenMode.value = null
            petReplyText.value = null
        }
    }

    private fun submitPetInput() {
        val text = petInputText.value.trim()
        if (text.isBlank()) return
        emitPetTurn(com.ai.assistance.quro.core.pet.QuroPetTurn.input(text))
        showPetPanel(PetPanel.None)
    }

    /** 快捷菜单动作分发；图标与文案都在 PetVisuals 里统一定义。 */
    private fun handlePetMenu(id: String) {
        when (id) {
            "input" -> showPetPanel(PetPanel.Input)
            "screen" -> launchScreenTurn(false, "看看我现在在做什么")
            "task" -> launchScreenTurn(true, "帮我把当前屏幕上的这件事做完")
            "action" -> {
                // 只播状态动画，不发对话回合；动画由环境动作池在下一 tick 自然接管。
                val label = currentManifest?.interactions?.filter { it.enabled }?.randomOrNull()?.label
                    ?: PetPrefs.load(this).interactions.randomOrNull()?.label
                    ?: "在这里"
                QuroPetStateBus.statusText = label
                QuroPetStateBus.phase = PetPhase.PERFORMING
                mainHandler.postDelayed({
                    QuroPetStateBus.statusText = null
                    if (QuroPetStateBus.phase == PetPhase.PERFORMING) QuroPetStateBus.phase = PetPhase.IDLE
                }, 2600L)
                showPetPanel(PetPanel.None)
            }
            "mode" -> {
                val spMode = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
                val next = when (spMode.getString("placement_mode", "physics")) {
                    "physics" -> "freeroam"; "freeroam" -> "pinned"; else -> "physics"
                }
                spMode.edit().putString("placement_mode", next).apply()
                sendBroadcast(Intent(ACTION_APPLY_SETTINGS).setPackage(packageName))
                showPetPanel(PetPanel.None)
            }
            "open" -> { showPetPanel(PetPanel.None); openChat() }
            "close" -> {
                // 关掉桌宠必须把总开关一起写回 false，否则冷启动自愈会把它又拉起来，
                // 用户会看到「明明关了，重开 App 又冒出来」。
                QuroVoiceFeaturePrefs.setDesktopPet(this, false)
                showPetPanel(PetPanel.None)
                stopSelf()
            }
            else -> showPetPanel(PetPanel.None)
        }
    }

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
        running = true
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
            // 通知栏「关闭」与菜单「关闭桌宠」语义一致：持久化关掉，别让恢复逻辑复活。
            QuroVoiceFeaturePrefs.setDesktopPet(this, false)
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
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        traceJob?.cancel()
        traceJob = null
        sheetLoadJob?.cancel()
        sheetLoadJob = null
        tapCueJob?.cancel()
        tapCueJob = null
        screenJob?.cancel()
        statusJob?.cancel()
        replyJob?.cancel()
        petScopeJob.cancel()
        runCatching { unregisterReceiver(eventsReceiver) }
        petPanel.value = PetPanel.None
        composeView?.let { runCatching { windowManager.removeView(it) } }
        composeView = null
        lifecycleOwner?.destroy()
        lifecycleOwner = null
        super.onDestroy()
    }

    /** 设置保存后立刻重建悬浮窗（旧实现只在服务首次拉起时读一次 PetPrefs）。 */
    private fun rebuildPet() {
        mainHandler.removeCallbacksAndMessages(null)
        sheetLoadJob?.cancel()
        sheetLoadJob = null
        tapCueJob?.cancel()
        tapCueJob = null
        screenJob?.cancel()
        showPetPanel(PetPanel.None)
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
            .setSmallIcon(R.drawable.ic_pet_notification)
            .setContentIntent(open)
            .addAction(R.drawable.ic_pet_close, qstr(R.string.qk_04593), stop)
            .setOngoing(true)
            .build()
    }

    private fun addPet() {
        // 双向接线：订阅本轮 AI 回复，回流到桌宠气泡；失败回合不会 emit，因此不会显示诊断文本。
        replyJob?.cancel()
        replyJob = petScope.launch {
            com.ai.assistance.quro.core.pet.QuroPetTurnBus.reply.collect { r ->
                if (r != null) {
                    petReplyText.value = r.text
                    petStatus.value = null
                    com.ai.assistance.quro.core.pet.QuroPetTurnBus.consumeReply()
                    scheduleStatusClear()
                }
            }
        }
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
        // 设置页 PetPrefs 写在 pet_prefs，服务旧实现却读 quro_desktop_pet，导致选中的
        // 宠物包 / 位置模式 / 屏幕上下文永远传不到悬浮窗，雪碧图分支从不生效。
        val sp2 = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
        val placementMode = sp2.getString("placement_mode", "physics") ?: "physics"   // physics|freeroam|pinned
        val screenContext = sp2.getBoolean("screen_context", false)
        val builtinDir = PetPackAssets.ensure(this)
        val packDirName = if (sp2.contains("pet_pack_dir")) {
            sp2.getString("pet_pack_dir", null)
        } else {
            // 首次启动且用户未显式选过：默认启用随包内置的宠物
            builtinDir?.also { sp2.edit().putString("pet_pack_dir", it).apply() }
        }
        val packLoaded = packDirName?.let { dir ->
            runCatching {
                val (avails, _) = PetPack.scan(this)
                avails.firstOrNull { it.dirName == dir }
            }.getOrNull()
        }
        currentManifest = packLoaded?.manifest
        // 只有换包才清 LRU；设置里点一次开关就广播一次，全清会导致反复解码 14MB 分片。
        if (lastPackDir != packDirName) {
            sheetCache.clear()
            lastPackDir = packDirName
        }
        sheetLoadJob?.cancel()
        // 缓存命中时首帧立即可用；未命中先走矢量兜底，IO 解码完自动重组切雪碧图。
        packImageState.value = if (packLoaded != null) peekSheetBitmap("stand") else null
        val manifestSnapshot = currentManifest
        sheetLoadJob = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val initial = manifestSnapshot?.let { m ->
                petPoseAnimationCandidates("stand", true, placementMode)
                    .firstOrNull { name -> m.animations.containsKey(name) } ?: "stand"
            }
            val bmp = initial?.let { loadSheetBitmap(it) }
            if (bmp != null) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    packImageState.value = bmp
                }
            }
        }
        // 环境动作池：优先宠物包声明的真实动作名，包未配置时回退旧版 4 种矢量互动。
        val profileMode = when (placementMode) { "freeroam" -> "freeRoam"; else -> placementMode }
        val ambientPool = currentManifest?.ambientFor(profileMode)?.actions
            ?.filter { currentManifest?.animations?.containsKey(it) == true }
            ?: emptyList()
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
        val ambientIntervalSec = currentManifest?.ambientFor(profileMode)?.intervalSeconds
            ?: petSettings.actionIntervalSec
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (screenInteractive && !QuroPetStateBus.isActive) {
                    if (ambientPool.isNotEmpty()) {
                        ambientAction = ambientPool.random()
                        ambientFlag.value = true
                        mainHandler.postDelayed(ambientEndRunnable, 2600L)
                    } else if (interactions.isNotEmpty()) {
                        ambientAction = interactions.random().action
                        ambientFlag.value = true
                        mainHandler.postDelayed(ambientEndRunnable, 2600L)
                    }
                }
                mainHandler.postDelayed(this, ambientIntervalSec.coerceIn(10, 120) * 1000L)
            }
        }, ambientIntervalSec.coerceIn(10, 120) * 1000L)


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
                    // 宠物包渲染优先（分片 spritesheet 帧动画），无包回退 Canvas 矢量
                    // 首帧取缓存中的分片，切换动作时异步解码目标分片并缓存。
                    // 动画表只换算一次：物理 tick 25fps 会重组整棵 Compose 树，
                    // 旧实现每帧 mapValues 重建 131 个对象，持续触发 GC。
                    val sdkAnimations = androidx.compose.runtime.remember(packLoaded) {
                        packLoaded?.manifest?.animations?.mapValues {
                            com.ai.assistance.quro.genui.sdk.components.PetPackAnimDef(
                                it.value.row, it.value.frames, it.value.frameDurationMs, it.value.playback
                            )
                        } ?: emptyMap()
                    }
                    val packBitmap: androidx.compose.ui.graphics.ImageBitmap? = packImageState.value
                    val physicsPoseV = physicsPose.value
                    val panel = petPanel.value
                    // 面板与宠物竖直堆叠，避免多个根 Composable 重叠摆放。
                    // 菜单展开时整块悬浮窗都是「点外面收起」的命中区；菜单行自带
                    // clickable，命中优先于父级，所以点菜单项不会误收起。
                    // 输入框面板不挂父级 clickable，避免和 BasicTextField 抢焦点/点击。
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.then(
                            if (panel == PetPanel.Menu) Modifier.clickable {
                                dismissPanelFromOutside()
                            } else Modifier,
                        ),
                    ) {
                    // 桌宠回合的 AI 回复回流气泡（失败回合不回流）
                    val replyBubble = petReplyText.value
                    if (!replyBubble.isNullOrBlank()) {
                        com.ai.assistance.quro.ui.pet.PetBubble(
                            replyBubble,
                            modifier = Modifier.padding(bottom = 6.dp),
                            accent = com.ai.assistance.quro.ui.pet.PetVisuals.Violet,
                        )
                    }
                    // 悬浮面板与宠物在同一窗口：不需要 Manifest 里注册额外 Activity
                    if (panel == PetPanel.Menu) {
                        com.ai.assistance.quro.ui.pet.PetQuickMenu(
                            items = petMenuItems.value,
                            onSelect = { item -> handlePetMenu(item.id) },
                            onDismiss = { showPetPanel(PetPanel.None) },
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    } else if (panel == PetPanel.Input) {
                        com.ai.assistance.quro.ui.pet.PetInputPanel(
                            value = petInputText.value,
                            onValueChange = { petInputText.value = it },
                            onSubmit = { submitPetInput() },
                            onScreen = { launchScreenTurn(false, "看看我现在在做什么") },
                            onTask = { launchScreenTurn(true, "帮我把当前屏幕上的这件事做完") },
                            onOpenChat = { showPetPanel(PetPanel.None); openChat() },
                            onClose = { showPetPanel(PetPanel.None) },
                            status = petStatus.value,
                            busy = petBusy.value,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    petScreenMode.value?.let { mode ->
                        com.ai.assistance.quro.ui.pet.PetScreenBadge(
                            mode, Modifier.padding(bottom = 6.dp),
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .scale(petScale)
                            // 面板展开时「再点一下宠物」即可收起；收起状态不挂 clickable，
                            // 否则会吞掉拖动 / 轻点互动的 MotionEvent。
                            .then(
                                if (panel != PetPanel.None) Modifier.clickable {
                                    dismissPanelFromOutside()
                                } else Modifier,
                            ),
                    ) {
                        if (packBitmap != null && packLoaded != null) {
                            // 环境动作池里是宠物包声明的真实动画名，直接作为姿态传入；
                            // 姿态候选的兜底分支会把任意动画名解析到对应行。
                            val poseName = when {
                                physicsPoseV != null -> physicsPoseV
                                ambientFlag.value && ambientPool.isNotEmpty() -> ambientAction
                                effectivePhase == PetPhase.PERFORMING -> "celebrate"
                                effectivePhase == PetPhase.THINKING || effectivePhase == PetPhase.PLANNING -> "think"
                                effectivePhase == PetPhase.TOOL_CALLING -> "usetool"
                                effectivePhase == PetPhase.GENERATING -> "reply"
                                effectivePhase == PetPhase.DONE -> "celebrate"
                                else -> "stand"
                            }
                            val facingRight = if (physicsPoseV == "walk") velX.value >= 0 else true
                            val animName = androidx.compose.runtime.remember(
                                poseName, facingRight, placementMode, packLoaded,
                            ) {
                                petPoseAnimationCandidates(
                                    poseName.lowercase(), facingRight, placementMode,
                                ).firstOrNull { packLoaded.manifest.animations.containsKey(it) } ?: "stand"
                            }
                            // 只解码当前动作所在的那一张分片；LRU 命中时连 IO 切换都不做。
                            val cachedSheet = peekSheetBitmap(animName)
                            val sheetState = produceState<ImageBitmap?>(cachedSheet ?: packBitmap, animName) {
                                if (cachedSheet == null) {
                                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        loadSheetBitmap(animName)
                                    } ?: packBitmap
                                }
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
                                animations = sdkAnimations,
                                pose = poseName,
                                facingRight = facingRight,
                                mode = placementMode,
                                bubbleText = bubbleTxt,
                                sheetBitmap = sheetState.value,
                            )
                        } else PetSprite(
                            spec = spec,
                            phase = effectivePhase,
                            toolName = QuroPetStateBus.toolName,
                            showBubble = showBubble,
                        )
                        val statusLine = petStatus.value ?: celebrate ?: QuroPetStateBus.statusText
                        statusLine?.let { st ->
                            Spacer(Modifier.height(5.dp))
                            com.ai.assistance.quro.ui.pet.PetStatusPill(st, busy = petBusy.value)
                        }
                    }
                    }
                }
            }
        }
        composeView = view
        // 返回键收起悬浮面板：面板展开时窗口已切为可聚焦，这里拦下 BACK，
        // 不让它穿透去退出背后的应用。面板收起后窗口恢复 NOT_FOCUSABLE，行为不变。
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, ev ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                ev.action == android.view.KeyEvent.ACTION_UP &&
                petPanel.value != PetPanel.None
            ) {
                showPetPanel(PetPanel.None)
                true
            } else false
        }

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
                // 面板展开时冻结物理运动：卡片跟着宠物飘会让人点不中，也让「收起」无从下手。
                if (petPanel.value == PetPanel.None && placementMode != "pinned" && petVisible) {
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

        // 触摸：拖动移动并持久化 / 轻点互动 / 长按展开悬浮菜单
        // 菜单与输入框都长在同一个 ComposeView 里，不再起独立 Activity：
        // PetInputActivity 未在 Manifest 注册（历史上硬插它导致整包损坏），点开必崩。
        var downX = 0f; var downY = 0f; var offsetX = 0f; var offsetY = 0f; var moved = false
        val threshold = (MOVE_THRESHOLD_DP * resources.displayMetrics.density).toInt()
        val longPressRunnable = Runnable {
            if (!moved) {
                val prefs = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
                val curMode = prefs.getString("placement_mode", "physics") ?: "physics"
                val modeLabel = when (curMode) {
                    "freeroam" -> "自由活动"; "pinned" -> "固定当前位置"; else -> "物理活动"
                }
                val screenAnalysis = prefs.getBoolean("pet_screen_analysis", true)
                val items = mutableListOf(
                    PetMenuItem(PetGlyph.KEYBOARD, "对它说", "直接和桌宠聊天", PetVisuals.Accent, "input"),
                )
                if (screenAnalysis) {
                    items.add(PetMenuItem(PetGlyph.EYE, "看当前屏幕", "读屏并让 AI 说明", PetVisuals.Accent, "screen"))
                    items.add(PetMenuItem(PetGlyph.TASK, "后台虚拟屏任务", "独立屏上完成操作", PetVisuals.Violet, "task"))
                }
                items.add(PetMenuItem(PetGlyph.SPARK, "播互动动作", "只播动画，不发消息", PetVisuals.Accent, "action"))
                items.add(PetMenuItem(PetGlyph.MODE, "模式：$modeLabel", "物理 / 自由 / 固定", PetVisuals.Muted, "mode"))
                items.add(PetMenuItem(PetGlyph.OPEN, "打开对话", "回到主界面", PetVisuals.Muted, "open"))
                items.add(PetMenuItem(PetGlyph.CLOSE, "关闭桌宠", "", PetVisuals.Danger, "close"))
                petMenuItems.value = items
                showPetPanel(PetPanel.Menu)
            }
        }
        view.setOnTouchListener { _, ev ->
            // 面板展开时把触摸完整交给 Compose（菜单行 / 输入框 / 发送按钮）。
            // 外层 OnTouchListener 一旦返回 true 会吞掉 ComposeView 内部的所有点击，
            // 所以这里必须放行；关闭面板后拖动逻辑自动恢复。
            if (petPanel.value != PetPanel.None) return@setOnTouchListener false
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
                            // 桌宠轻点互动 = 一个「安静回合」：
                            //  · 用户气泡只放一句干净短话（互动 label），绝不把提示词模板、
                            //    【当前屏幕线索】、节点树 dump、❌ 权限错误拼进去；
                            //  · 提示词模板 + 净化后的屏幕文字走隐藏上下文，模型可见、用户不可见；
                            //  · 读屏移到 IO 线程（旧实现在主线程同步跑完整节点树 + 截图 + OCR，
                            //    内含 5 秒等待，是无屏 ANR 的直接元凶）；
                            //  · 请求失败整轮撤下，不往对话里写「⚠️ 请求失败（HTTP 429）…」。
                            val packInter = packLoaded?.manifest?.interactions
                                ?.filter { it.enabled }?.randomOrNull()
                            if (packInter != null) {
                                val quiet = com.ai.assistance.quro.core.pet.QuroQuietTurn
                                // 可见气泡只放干净的中文短句，不再用 emoji 当图标。
                                val visible = packInter.label.trim()
                                val instruction = packInter.prompt
                                    .replace("{input}", packInter.label)
                                    .replace("{time}", android.text.format.DateFormat.format("HH:mm", System.currentTimeMillis()).toString())
                                packInter.animation?.let {
                                    ambientAction = it
                                    ambientFlag.value = true
                                    mainHandler.postDelayed(ambientEndRunnable, 2600L)
                                }
                                QuroPetStateBus.onInteract(packInter.label, packInter.animation ?: "celebrate")

                                if (!quiet.allowAutoTurn()) {
                                    // 连续失败冷却中：只播动作，不再发请求，也不刷对话框。
                                    quiet.emit(quiet.FAILURE_HINT)
                                } else if (screenContext) {
                                    tapCueJob?.cancel()
                                    tapCueJob = petScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        val cue = runCatching { quiet.readScreenCue(packageName) }.getOrNull()
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            emitPetTurn(
                                                com.ai.assistance.quro.core.pet.QuroPetTurn.tap(
                                                    label = visible,
                                                    instruction = "[桌宠互动指令] $instruction",
                                                    screen = cue?.let {
                                                        "[当前屏幕可读文字 · 仅作参考，不要复述] $it"
                                                    },
                                                ),
                                            )
                                        }
                                    }
                                } else {
                                    emitPetTurn(
                                        com.ai.assistance.quro.core.pet.QuroPetTurn.tap(
                                            label = visible,
                                            instruction = "[桌宠互动指令] $instruction",
                                        ),
                                    )
                                }
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
                            // 轻点 = 结构化 pet_tap 回合；干净可见短句 + 隐藏指令，
                            // 回复会回流到桌宠气泡，失败整轮撤下。
                            if (com.ai.assistance.quro.core.pet.QuroQuietTurn.allowAutoTurn()) {
                                emitPetTurn(
                                    com.ai.assistance.quro.core.pet.QuroPetTurn.tap(
                                        label = "戳了戳桌宠",
                                        instruction = "用户轻点戳了戳桌宠。用一句温柔俏皮的话回应，20 字以内。",
                                    ),
                                )
                            } else {
                                com.ai.assistance.quro.core.pet.QuroQuietTurn.emit(
                                    com.ai.assistance.quro.core.pet.QuroQuietTurn.FAILURE_HINT)
                                openChat()
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
        /** 悬浮窗坐标；设置项统一存 SETTINGS_PREFS。 */
        private const val PREFS = "quro_desktop_pet"
        /** 设置页 PetPrefs 的单真源：placement_mode / screen_context / pet_pack_dir。 */
        private const val SETTINGS_PREFS = "pet_prefs"
        private const val K_X = "x"
        private const val K_Y = "y"
        private const val MOVE_THRESHOLD_DP = 12
        private const val LONG_PRESS_MS = 600L
        const val ACTION_STOP = "com.ai.assistance.quro.action.DESKTOP_PET_STOP"
        /** 设置保存后由 PetPrefs.save 广播，运行中的服务据此重建悬浮窗。 */
        const val ACTION_APPLY_SETTINGS = "com.ai.assistance.quro.action.DESKTOP_PET_APPLY_SETTINGS"

        /**
         * 进程内运行标志：服务与 App 同进程。冷启动恢复用它做幂等判断，避免每次 onResume
         * 都重复发一次 startForegroundService（常驻通知会闪一下）。
         * 它只代表「服务实例是否活着」，用户开关仍然只认 QuroVoiceFeaturePrefs。
         */
        @Volatile private var running = false

        private fun hasOverlayPermission(ctx: android.content.Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                android.provider.Settings.canDrawOverlays(ctx)

        /** 开启桌面宠物（含开关持久化）；悬浮窗权限缺失时返回 false 并不拉起服务。 */
        fun start(ctx: android.content.Context): Boolean {
            QuroVoiceFeaturePrefs.setDesktopPet(ctx, true)
            if (!hasOverlayPermission(ctx)) return false
            return runCatching {
                ctx.startForegroundService(Intent(ctx, QuroDesktopPetService::class.java))
            }.isSuccess
        }

        /**
         * 冷启动自愈：桌宠开关早就持久化了，但进程被回收（划掉任务、厂商省电策略、
         * 系统重启）后没有任何入口把服务拉回来 —— 此前只有开机广播会恢复，
         * 「退出软件再打开」时设置里显示已开启、桌面上却看不到宠物。
         * 这里只读开关、绝不改写开关（用户显式关过就不会被复活），多处调用均幂等。
         */
        fun ensureStarted(ctx: android.content.Context) {
            if (running) return
            if (!QuroVoiceFeaturePrefs.getDesktopPet(ctx)) return
            if (!hasOverlayPermission(ctx)) return
            // Android 12+ 对后台发起前台服务有限制；被拒时安静失败，等界面真正前台时再试。
            runCatching {
                ctx.startForegroundService(Intent(ctx, QuroDesktopPetService::class.java))
            }
        }

        /** 关闭桌面宠物（含开关持久化）。 */
        fun stop(ctx: android.content.Context) {
            QuroVoiceFeaturePrefs.setDesktopPet(ctx, false)
            ctx.stopService(Intent(ctx, QuroDesktopPetService::class.java))
        }
    }
}
