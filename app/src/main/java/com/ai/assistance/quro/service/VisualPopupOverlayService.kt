package com.ai.assistance.quro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ai.assistance.quro.core.tools.VisualCustomPopupData
import com.ai.assistance.quro.core.tools.VisualCustomPopupQueue
import com.ai.assistance.quro.core.tools.generateCustomPopupHtml
import com.ai.assistance.quro.core.util.QuroServiceLifecycleOwner
import com.ai.assistance.quro.activity.QuroMainActivity
import com.ai.assistance.quro.ui.VisualCustomPopupOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 可视化弹窗悬浮窗服务
 * 
 * 功能：
 * 1. 在App外显示可视化弹窗（系统级悬浮窗）
 * 2. 支持AI创建的HTML/CSS/JS内容
 * 3. 支持拖动、关闭、结果返回
 * 4. 与主应用通信（通过VisualCustomPopupQueue）
 */
class VisualPopupOverlayService : Service(), CoroutineScope by CoroutineScope(Dispatchers.Main + SupervisorJob()) {

    private lateinit var windowManager: WindowManager
    private var currentPopupView: ComposeView? = null
    private var composeLifecycleOwner: QuroServiceLifecycleOwner? = null
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 当前显示的弹窗数据
    private var currentPopup: VisualCustomPopupData? = null
    private var isShowing = false
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            // 启动前台服务
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
            
            // 监听弹窗队列变化
            launch {
                VisualCustomPopupQueue.eventFlow.collect { event ->
                    when (event) {
                        is VisualCustomPopupQueue.PopupEvent.PopupAdded -> {
                            // 有新弹窗加入，显示悬浮窗
                            val popup = VisualCustomPopupQueue.getCurrentPopup()
                            if (popup != null) {
                                mainHandler.post {
                                    showOverlayPopup(popup.second)
                                }
                            }
                        }
                        is VisualCustomPopupQueue.PopupEvent.PopupRemoved -> {
                            // 弹窗被移除，隐藏悬浮窗
                            if (currentPopup?.id == event.id) {
                                mainHandler.post {
                                    hideOverlayPopup()
                                }
                            }
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "创建悬浮窗服务失败", e)
            stopSelf()
            return
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_SHOW_POPUP -> {
                // 显示指定的弹窗
                val popupId = intent.getStringExtra(EXTRA_POPUP_ID)
                if (popupId != null) {
                    val popup = VisualCustomPopupQueue.getPopupById(popupId)
                    if (popup != null) {
                        showOverlayPopup(popup)
                    }
                }
            }
            ACTION_HIDE_POPUP -> {
                // 隐藏当前弹窗
                hideOverlayPopup()
            }
            ACTION_STOP_SERVICE -> {
                // 停止服务
                hideOverlayPopup()
                stopSelf()
            }
        }
        return START_STICKY
    }
    
    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chan = NotificationChannel(
            CHANNEL_ID, "可视化弹窗悬浮窗", NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(chan)
        
        val intent = Intent(this, QuroMainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        
        val hideIntent = Intent(this, VisualPopupOverlayService::class.java).apply {
            action = ACTION_HIDE_POPUP
        }
        val hidePi = PendingIntent.getService(
            this, 1, hideIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        
        val stopIntent = Intent(this, VisualPopupOverlayService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPi = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("可视化弹窗")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "隐藏", hidePi)
            .addAction(android.R.drawable.ic_menu_delete, "停止服务", stopPi)
            .build()
    }
    
    private fun showOverlayPopup(popup: VisualCustomPopupData) {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "没有悬浮窗权限，无法显示")
            return
        }
        
        // 如果已经显示了弹窗，先隐藏
        if (isShowing) {
            hideOverlayPopup()
        }
        
        currentPopup = popup
        
        val lifecycleOwner = QuroServiceLifecycleOwner().apply { create(); resume() }
        composeLifecycleOwner = lifecycleOwner
        
        val context = androidx.appcompat.view.ContextThemeWrapper(this, getAppThemeRes())
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            
            setContent {
                VisualCustomPopupOverlay(
                    popupData = popup,
                    onSubmit = { result ->
                        VisualCustomPopupQueue.submitResult(popup.id, result)
                        mainHandler.post {
                            hideOverlayPopup()
                        }
                    },
                    onClose = {
                        VisualCustomPopupQueue.submitResult(popup.id, """{"cancelled":true}""")
                        mainHandler.post {
                            hideOverlayPopup()
                        }
                    },
                    onDrag = { dx, dy ->
                        params.x += dx
                        params.y += dy
                        windowManager.updateViewLayout(composeView, params)
                    },
                    onMinimize = {
                        mainHandler.post {
                            hideOverlayPopup()
                        }
                    }
                )
            }
        }
        
        currentPopupView = composeView
        
        // 悬浮窗参数
        val width = popup.width?.dpToPx() ?: (resources.displayMetrics.widthPixels * 0.9).toInt()
        val height = popup.height?.dpToPx() ?: (resources.displayMetrics.heightPixels * 0.7).toInt()
        
        val params = WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            x = 0
            y = 0
        }
        
        windowManager.addView(composeView, params)
        isShowing = true
        
        Log.d(TAG, "显示悬浮窗弹窗: ${popup.title}")
    }
    
    private fun hideOverlayPopup() {
        try {
            currentPopupView?.let { windowManager.removeView(it) }
            currentPopupView = null
            composeLifecycleOwner?.destroy()
            composeLifecycleOwner = null
            currentPopup = null
            isShowing = false
            
            Log.d(TAG, "隐藏悬浮窗弹窗")
        } catch (e: Exception) {
            Log.e(TAG, "隐藏悬浮窗失败", e)
        }
    }
    
    private fun getAppThemeRes(): Int {
        return resources.getIdentifier("Theme.Quro", "style", packageName).let {
            if (it != 0) it else android.R.style.Theme_Material_Light
        }
    }
    
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
    
    override fun onDestroy() {
        try {
            hideOverlayPopup()
            coroutineContext.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e(TAG, "销毁悬浮窗服务失败", e)
        }
        super.onDestroy()
    }
    
    companion object {
        private const val TAG = "VisualPopupOverlay"
        private const val NOTIF_ID = 8802
        private const val CHANNEL_ID = "visual_popup_overlay"
        private const val MOVE_THRESHOLD_DP = 10
        
        const val ACTION_SHOW_POPUP = "com.ai.assistance.quro.action.SHOW_POPUP"
        const val ACTION_HIDE_POPUP = "com.ai.assistance.quro.action.HIDE_POPUP"
        const val ACTION_STOP_SERVICE = "com.ai.assistance.quro.action.STOP_POPUP_SERVICE"
        const val EXTRA_POPUP_ID = "extra_popup_id"
        
        /**
         * 启动悬浮窗服务
         */
        fun start(context: Context) {
            val intent = Intent(context, VisualPopupOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * 显示指定弹窗
         */
        fun showPopup(context: Context, popupId: String) {
            val intent = Intent(context, VisualPopupOverlayService::class.java).apply {
                action = ACTION_SHOW_POPUP
                putExtra(EXTRA_POPUP_ID, popupId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * 隐藏当前弹窗
         */
        fun hidePopup(context: Context) {
            val intent = Intent(context, VisualPopupOverlayService::class.java).apply {
                action = ACTION_HIDE_POPUP
            }
            context.startService(intent)
        }
        
        /**
         * 停止服务
         */
        fun stop(context: Context) {
            val intent = Intent(context, VisualPopupOverlayService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
        
        /**
         * 检查是否有悬浮窗权限
         */
        fun hasOverlayPermission(context: Context): Boolean {
            return Settings.canDrawOverlays(context)
        }
        
        /**
         * 请求悬浮窗权限
         */
        fun requestOverlayPermission(context: Context) {
            if (!hasOverlayPermission(context)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }
}