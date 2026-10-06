package com.localmediatools.export

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
 * Foreground service that keeps exports alive in the background and shows their progress.
 * The actual work runs in [ExportManager]; this service mirrors its state into a notification.
 */
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collector: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            ExportManager.state.value.active?.let { ExportManager.cancel(it.id) }
        }
        goForeground(ExportManager.state.value)
        if (collector == null) {
            collector = scope.launch {
                ExportManager.state.collectLatest { s ->
                    if (!s.busy) {
                        stopSelfCleanly()
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastNotified > 700) {
                            lastNotified = now
                            ExportNotifications.manager(this@ExportService).notify(ExportNotifications.PROGRESS_ID, ExportNotifications.progress(this@ExportService, s))
                        }
                    }
                }
            }
        }
        acquireWakeLock()
        return START_NOT_STICKY
    }

    private fun goForeground(s: ExportManager.State) {
        val n = ExportNotifications.progress(this, s)
        try {
            if (Build.VERSION.SDK_INT >= 35) {
                startForeground(ExportNotifications.PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            } else {
                startForeground(ExportNotifications.PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } catch (e: Exception) {
            android.util.Log.w("LMT", "startForeground failed", e)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalMediaTools:export").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun stopSelfCleanly() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Android 15+: foreground media processing is limited to 6 hours per day. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ExportManager.cancelAll()
        stopSelfCleanly()
    }

    override fun onDestroy() {
        scope.cancel()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "com.localmediatools.app.CANCEL_EXPORT"
    }
}

object ExportNotifications {
    const val CHANNEL_PROGRESS = "export_progress"
    const val CHANNEL_RESULTS = "export_results"
    const val PROGRESS_ID = 1001
    private const val RESULT_BASE_ID = 2000

    fun manager(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannels(ctx: Context) {
        val nm = manager(ctx)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Export progress", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows the progress of running exports"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RESULTS, "Finished exports", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Tells you when an export finishes or fails"
        })
    }

    private fun openApp(ctx: Context, jobId: Long?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (jobId != null) putExtra(MainActivity.EXTRA_SHOW_JOB, jobId)
        }
        return PendingIntent.getActivity(ctx, (jobId ?: 0L).toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun progress(ctx: Context, s: ExportManager.State): Notification {
        val a = s.active
        val b = Notification.Builder(ctx, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_export)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openApp(ctx, a?.id))
            .setColor(0xFF4DA3FF.toInt())
        if (a == null) {
            b.setContentTitle("Preparing export…").setProgress(0, 0, true)
        } else {
            val pct = (a.fraction * 100).toInt()
            val queued = if (s.queued.isNotEmpty()) " · ${s.queued.size} queued" else ""
            b.setContentTitle(a.title)
                .setContentText("$pct% · ${a.statusText}${a.currentName?.let { " · $it" } ?: ""}$queued")
                .setProgress(100, pct, a.fraction <= 0.0)
                .setSubText("Workload ${a.workloadPercent}%")
            val cancel = Intent(ctx, ExportService::class.java).setAction(ExportService.ACTION_CANCEL)
            val pi = PendingIntent.getService(ctx, 7, cancel, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, "Cancel", pi).build())
        }
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    fun showFinished(ctx: Context, s: JobSnapshot) {
        val title = when (s.status) {
            JobStatus.SUCCEEDED -> "Export finished"
            JobStatus.PARTIAL -> "Export finished with issues"
            JobStatus.CANCELLED -> "Export cancelled"
            else -> "Export failed"
        }
        val n = Notification.Builder(ctx, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_export)
            .setContentTitle("$title · ${s.tool.title}")
            .setContentText(s.summary())
            .setStyle(Notification.BigTextStyle().bigText(s.summary() + "\nSaved to ${s.tool.outputPath}"))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, s.id))
            .setColor(0xFF4DA3FF.toInt())
            .build()
        try {
            manager(ctx).notify(RESULT_BASE_ID + (s.id % 500).toInt(), n)
        } catch (_: SecurityException) {
            // Notifications not permitted: results are still visible in the app.
        }
    }
}
