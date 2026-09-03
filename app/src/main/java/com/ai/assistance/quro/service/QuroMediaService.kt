package com.ai.assistance.quro.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ai.assistance.quro.R
import com.ai.assistance.quro.activity.QuroMainActivity
import com.ai.assistance.quro.core.tools.QuroMediaController
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 前台音乐播放服务（百分百开源本地音乐播放器，v135 升级）。
 * 基于 Android 框架 MediaPlayer + MediaStyle 通知，支持：
 *  - 播放列表(queue)、上一首/下一首
 *  - 循环模式（不循环 / 列表循环 / 单曲循环）
 *  - 随机播放
 *  - 倍速（0.5x–2x，MediaPlayer.setPlaybackParams）
 *  - 进度拖动（seek）
 *
 * 即使不打开前台也能在后台持续播放；聊天界面的播放卡片 / 全屏播放器与之联动。
 */
class QuroMediaService : android.app.Service() {
    private var player: MediaPlayer? = null
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var tickTask: java.util.concurrent.ScheduledFuture<*>? = null

    // ── 播放列表状态 ──
    private var queue: MutableList<QuroMediaController.Track> = mutableListOf()
    private var order: List<Int> = listOf(0)   // 实际播放顺序（索引指向 queue）
    private var orderPos = 0
    private var loopMode = QuroMediaController.LOOP_OFF
    private var shuffle = false
    private var speed = 1f
    private var preparing = false

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> toggle()
            ACTION_STOP -> stopPlayback()
            ACTION_NEXT -> gotoNext(manual = false)
            ACTION_PREV -> gotoPrev()
            ACTION_SEEK -> {
                val ms = intent.getIntExtra(EXTRA_SEEK_MS, -1)
                if (ms >= 0) { player?.seekTo(ms); pushState() }
            }
            ACTION_SET_LOOP -> {
                loopMode = intent.getIntExtra(EXTRA_LOOP, QuroMediaController.LOOP_OFF)
                pushState(); showNotification()
            }
            ACTION_SET_SHUFFLE -> {
                shuffle = intent.getBooleanExtra(EXTRA_SHUFFLE, false)
                rebuildOrder(keepCurrent = true)
                pushState(); showNotification()
            }
            ACTION_SET_SPEED -> {
                speed = intent.getFloatExtra(EXTRA_SPEED, 1f).coerceIn(0.25f, 3f)
                runCatching { player?.playbackParams = player!!.playbackParams.setSpeed(speed) }
                pushState(); showNotification()
            }
            // 播放队列中指定索引（播放器队列界面点击切歌）
            ACTION_PLAY_INDEX -> {
                val idx = intent.getIntExtra(EXTRA_INDEX, -1)
                val p = order.indexOf(idx)
                if (queue.isNotEmpty() && p >= 0) {
                    orderPos = p
                    playCurrent()
                }
            }
            else -> {
                // 新播放请求：queue 优先，否则单个 uri
                val qUris = intent?.getStringArrayListExtra(EXTRA_QUEUE_URIS)
                val qTitles = intent?.getStringArrayListExtra(EXTRA_QUEUE_TITLES)
                val idx = intent?.getIntExtra(EXTRA_INDEX, 0) ?: 0
                if (!qUris.isNullOrEmpty()) {
                    queue = qUris.mapIndexed { i, u ->
                        QuroMediaController.Track(u, qTitles?.getOrNull(i) ?: "")
                    }.toMutableList()
                    rebuildOrder(keepCurrent = false)
                    orderPos = idx.coerceIn(0, order.lastIndex.coerceAtLeast(0))
                    playCurrent()
                } else {
                    val uri = intent?.getStringExtra(EXTRA_URI).orEmpty()
                    val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
                    if (uri.isNotEmpty()) {
                        queue = mutableListOf(QuroMediaController.Track(uri, title))
                        rebuildOrder(keepCurrent = false)
                        orderPos = 0
                        playCurrent()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun playCurrent() {
        if (order.isEmpty() || orderPos !in order.indices) return
        val track = queue.getOrNull(order[orderPos]) ?: return
        preparing = true
        showNotification()
        runCatching { player?.release() }
        player = MediaPlayer().apply {
            try {
                if (track.uri.startsWith("content://") || track.uri.startsWith("file://")) {
                    setDataSource(this@QuroMediaService, Uri.parse(track.uri))
                } else {
                    setDataSource(track.uri)
                }
                setOnPreparedListener {
                    preparing = false
                    runCatching { it.playbackParams = it.playbackParams.setSpeed(speed) }
                    it.start()
                    pushState()
                    startTicker()
                    showNotification()
                }
                setOnCompletionListener { onTrackEnded() }
                prepareAsync()
            } catch (e: Exception) {
                QuroMediaController.reset()
                stopPlayback()
            }
        }
    }

    private fun onTrackEnded() {
        if (loopMode == QuroMediaController.LOOP_ONE) {
            player?.seekTo(0)
            player?.start()
            pushState()
            return
        }
        gotoNext(manual = false)
    }

    private fun gotoNext(manual: Boolean) {
        if (queue.isEmpty()) return
        if (loopMode == QuroMediaController.LOOP_ONE && !manual) {
            player?.seekTo(0); player?.start(); pushState(); return
        }
        var np = orderPos + 1
        if (np >= order.size) {
            if (loopMode == QuroMediaController.LOOP_ALL || manual) np = 0 else { stopPlayback(); return }
        }
        orderPos = np
        playCurrent()
    }

    private fun gotoPrev() {
        if (queue.isEmpty()) return
        // 播放超过 3s 时点上一首先回到本曲开头
        if ((player?.currentPosition ?: 0) > 3000 && manualPrev) {
            player?.seekTo(0); pushState(); return
        }
        var np = orderPos - 1
        if (np < 0) np = if (loopMode == QuroMediaController.LOOP_ALL) order.size - 1 else 0
        orderPos = np
        playCurrent()
    }

    private var manualPrev = true

    private fun toggle() {
        val p = player ?: return
        if (p.isPlaying) { p.pause(); pushState() } else { p.start(); pushState() }
        showNotification()
    }

    private fun rebuildOrder(keepCurrent: Boolean) {
        if (queue.isEmpty()) { order = listOf(0); return }
        val cur = if (keepCurrent && order.isNotEmpty() && orderPos in order.indices) order[orderPos] else 0
        order = if (shuffle) {
            val rest = (queue.indices - cur).shuffled()
            listOf(cur) + rest
        } else {
            queue.indices.toList()
        }
        orderPos = 0
    }

    private fun startTicker() {
        tickTask?.cancel(false)
        tickTask = scheduler.scheduleAtFixedRate({
            val p = player
            if (p != null && p.isPlaying) pushState()
        }, 0, 500, TimeUnit.MILLISECONDS)
    }

    private fun currentTrack(): QuroMediaController.Track? =
        if (order.isNotEmpty() && orderPos in order.indices) queue.getOrNull(order[orderPos]) else null

    private fun pushState() {
        val p = player
        val track = currentTrack()
        QuroMediaController.update(
            QuroMediaController.State(
                isPlaying = p?.isPlaying == true,
                title = track?.title ?: "",
                uri = track?.uri ?: "",
                positionMs = (p?.currentPosition ?: 0).toLong(),
                durationMs = (p?.duration ?: 0).toLong(),
                queue = queue.toList(),
                index = if (order.isNotEmpty() && orderPos in order.indices) order[orderPos] else 0,
                loopMode = loopMode,
                shuffle = shuffle,
                speed = speed,
            )
        )
    }

    private fun stopPlayback() {
        tickTask?.cancel(false); tickTask = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        QuroMediaController.reset()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "ZorvAI 音乐播放", NotificationManager.IMPORTANCE_LOW)
                ch.setShowBadge(false)
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun showNotification() {
        val playing = player?.isPlaying == true
        val track = currentTrack()
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, QuroMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIntent = PendingIntent.getService(
            this, 1, Intent(this, QuroMediaService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = PendingIntent.getService(
            this, 3, Intent(this, QuroMediaService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prevIntent = PendingIntent.getService(
            this, 4, Intent(this, QuroMediaService::class.java).setAction(ACTION_PREV),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 2, Intent(this, QuroMediaService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(track?.title?.ifBlank { "本地音乐" } ?: "本地音乐")
            .setContentText(if (preparing) "正在准备…" else if (playing) "正在播放" else "已暂停")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_previous, "上一首", prevIntent)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "暂停" else "播放", playPauseIntent
            )
            .addAction(android.R.drawable.ic_media_next, "下一首", nextIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopIntent)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(NOTIFY_ID, notif)
    }

    override fun onDestroy() {
        stopPlayback()
        scheduler.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "quro.media.PLAY_PAUSE"
        const val ACTION_STOP = "quro.media.STOP"
        const val ACTION_NEXT = "quro.media.NEXT"
        const val ACTION_PREV = "quro.media.PREV"
        const val ACTION_SEEK = "quro.media.SEEK"
        const val ACTION_SET_LOOP = "quro.media.SET_LOOP"
        const val ACTION_SET_SHUFFLE = "quro.media.SET_SHUFFLE"
        const val ACTION_SET_SPEED = "quro.media.SET_SPEED"
        const val ACTION_PLAY_INDEX = "quro.media.PLAY_INDEX"
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_QUEUE_URIS = "queue_uris"
        const val EXTRA_QUEUE_TITLES = "queue_titles"
        const val EXTRA_INDEX = "index"
        const val EXTRA_SEEK_MS = "seek_ms"
        const val EXTRA_LOOP = "loop"
        const val EXTRA_SHUFFLE = "shuffle"
        const val EXTRA_SPEED = "speed"
        private const val CHANNEL_ID = "quro_media"
        private const val NOTIFY_ID = 1001
    }
}
