package com.localmediatools.gallery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps gallery indexing alive while the app is in the background and shows its progress. The
 * work itself runs in [GalleryIndex]; this only mirrors its state into a quiet notification.
 */
class GalleryIndexService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collector: Job? = null
    private var last = 0L
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            GalleryIndex.setPaused(this, true)
        }
        goForeground(GalleryIndex.state.value)
        if (collector == null) {
            collector = scope.launch {
                GalleryIndex.state.collectLatest { s ->
                    val waiting = s.phase == GalleryIndex.Phase.PAUSED && !s.pausedByUser
                    if (!s.working && !waiting) { stop(); return@collectLatest }
                    val now = System.currentTimeMillis()
                    if (now - last > 1500) {
                        last = now
                        manager(this@GalleryIndexService).notify(ID, notification(this@GalleryIndexService, s))
                        keepAwakeWhileCharging(s)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(s: GalleryIndex.State) {
        try {
            if (Build.VERSION.SDK_INT >= 35) startForeground(ID, notification(this, s), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            else startForeground(ID, notification(this, s), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            android.util.Log.w("LMT", "gallery startForeground failed", e)
        }
    }

    /**
     * With the screen off the phone sleeps and indexing would stall. While charging it is kept
     * awake (for at most 10 minutes past the last progress); on battery it is allowed to sleep.
     */
    private fun keepAwakeWhileCharging(s: GalleryIndex.State) {
        val w = wake ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalMediaTools:gallery").apply { setReferenceCounted(false) }.also { wake = it }
        if (s.working && GalleryIndex.isCharging(this)) w.acquire(10 * 60_000L) else if (w.isHeld) w.release()
    }

    private fun stop() {
        wake?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        GalleryIndex.stop()
        stop()
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "gallery_index"
        private const val ID = 1101
        const val ACTION_PAUSE = "com.localmediatools.app.PAUSE_GALLERY"

        fun manager(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        fun createChannel(ctx: Context) {
            manager(ctx).createNotificationChannel(NotificationChannel(CHANNEL, "Organising the gallery", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shows progress while photos are analysed on this phone for search and people"
                setShowBadge(false)
            })
        }

        /** Starts the service if indexing will take a while; it stops itself when done. */
        fun ensure(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, GalleryIndexService::class.java))
            } catch (e: Exception) {
                // Not allowed from the background on newer Android; indexing still continues while the app is open.
                android.util.Log.i("LMT", "gallery service not started: ${e.message}")
            }
        }

        fun notification(ctx: Context, s: GalleryIndex.State): Notification {
            val open = PendingIntent.getActivity(ctx, 11, Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_GALLERY, true)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val pause = PendingIntent.getService(ctx, 12, Intent(ctx, GalleryIndexService::class.java).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val text = when {
                s.phase == GalleryIndex.Phase.PAUSED -> s.pausedWhy ?: "Paused"
                s.phase == GalleryIndex.Phase.GROUPING -> "Grouping faces into people"
                s.total > 0 -> "${s.done} of ${s.total} photos and videos · on this phone"
                else -> "Reading your library"
            }
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_export)
                .setContentTitle("Organising your gallery")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(open)
                .setProgress(s.total.coerceAtLeast(1), s.done.coerceAtMost(s.total), s.total <= 0)
                .addAction(Notification.Action.Builder(null, "Pause", pause).build())
                .build()
        }
    }
}
