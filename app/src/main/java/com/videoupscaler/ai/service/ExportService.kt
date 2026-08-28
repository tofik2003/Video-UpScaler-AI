package com.videoupscaler.ai.service

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
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.videoupscaler.ai.MainActivity
import com.videoupscaler.ai.R
import com.videoupscaler.ai.UpScalerApp
import com.videoupscaler.ai.pipeline.VideoExporter

/**
 * Keeps an export alive independent of the UI.
 *
 * Before this, an export was owned by the Activity. Hoisting it to [UpScalerApp] (in the review
 * pass) fixed rotation, but **process death still killed the job** — the process is exactly what
 * gets reclaimed when the user leaves the app for a multi-minute export. A `mediaProcessing`
 * foreground service is the supported answer.
 *
 * ## The 6-hour budget
 *
 * Android 14+ caps `mediaProcessing` foreground services at **6 hours per 24 hours**. This is not a
 * detail to discover in production: a long export can be terminated mid-way. So:
 *
 *  - the cap is surfaced *before* the export starts (DESIGN.md),
 *  - [onTimeout] treats termination as an event to handle, not a crash,
 *  - progress is persisted so a timeout is recoverable rather than silently lost.
 *
 * `onTimeout` only exists on API 34+; overriding it is harmless below that.
 */
@OptIn(UnstableApi::class)
class ExportService : Service() {

    private val exporter by lazy { (application as UpScalerApp).exporter }
    private var startedAtElapsed = 0L
    private var thermalStatus = 0

    private val listener = VideoExporter.Listener { state -> onState(state) }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        exporter.attach(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            exporter.cancel()
            stopSelf()
            return START_NOT_STICKY
        }

        startedAtElapsed = SystemClock.elapsedRealtime()
        startInForeground()

        // START_NOT_STICKY: if the system kills this, do not silently restart it. A restarted
        // service with no running export would show a stale "enhancing" notification, which is worse
        // than nothing. Resuming is an explicit user action instead.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        exporter.detach()
        super.onDestroy()
    }

    /**
     * The 6h/24h `mediaProcessing` budget ran out. The service is about to be stopped by the system.
     *
     * The right response is to stop cleanly and tell the user, not to fight it — attempting to
     * restart immediately just burns the remaining budget and can get the app throttled.
     */
    override fun onTimeout(fgsType: Int) {
        if (fgsType == ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING) {
            exporter.cancel()
            notifyManager.notify(
                NOTIF_ID,
                baseNotification()
                    .setContentTitle(getString(R.string.status_timeout))
                    .setContentText(getString(R.string.status_timeout_detail))
                    .setOngoing(false)
                    .setProgress(0, 0, false)
                    .build(),
            )
        }
        stopSelf()
    }

    // ----------------------------------------------------------------------------------------

    private fun onState(state: VideoExporter.State) {
        when (state) {
            is VideoExporter.State.Running -> updateRunning(state.percent)
            is VideoExporter.State.Completed -> finish(getString(R.string.status_done, state.outputPath))
            is VideoExporter.State.Failed -> finish(getString(R.string.status_failed, state.message))
            VideoExporter.State.Cancelled -> finish(getString(R.string.status_cancelled))
            is VideoExporter.State.Notice -> updateRunning(-1)  // keep going; notice shown in UI
            VideoExporter.State.Idle -> stopSelf()
        }
    }

    private fun updateRunning(percent: Int) {
        val elapsed = SystemClock.elapsedRealtime() - startedAtElapsed
        val eta = if (percent in 1..99) (elapsed / percent) * (100 - percent) else -1L

        // Thermal pressure is the reason exports slow down late. Surfacing it beats silently
        // dropping the frame rate and looking broken.
        val thermal = currentThermalStatus()
        val title =
            if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) {
                getString(R.string.status_throttled)
            } else {
                getString(R.string.status_exporting_short, if (percent >= 0) percent else 0)
            }

        notifyManager.notify(
            NOTIF_ID,
            baseNotification()
                .setContentTitle(title)
                .setContentText(formatEta(eta, elapsed))
                .setOngoing(true)
                .setProgress(100, if (percent >= 0) percent else 0, percent < 0)
                .build(),
        )
    }

    private fun finish(message: String) {
        notifyManager.notify(
            NOTIF_ID,
            baseNotification()
                .setContentTitle(message)
                .setOngoing(false)
                .setAutoCancel(true)
                .setProgress(0, 0, false)
                .build(),
        )
        stopSelf()
    }

    private fun currentThermalStatus(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(PowerManager::class.java)?.currentThermalStatus
                ?: PowerManager.THERMAL_STATUS_NONE
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }

    private fun formatEta(etaMs: Long, elapsedMs: Long): String {
        val e = elapsedMs / 1000
        val elapsedText = String.format("%d:%02d", e / 60, e % 60)
        if (etaMs < 0) return getString(R.string.eta_unknown, elapsedText)
        val t = etaMs / 1000
        return getString(R.string.eta_remaining, String.format("%d:%02d", t / 60, t % 60))
    }

    private fun startInForeground() {
        val notification =
            baseNotification()
                .setContentTitle(getString(R.string.status_exporting_short, 0))
                .setContentText(getString(R.string.eta_unknown, "0:00"))
                .setOngoing(true)
                .setProgress(100, 0, true)
                .build()

        // The typed overload is API 29+; below that the untyped one is the only option.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun baseNotification(): Notification.Builder {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val cancel =
            PendingIntent.getService(
                this,
                1,
                Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_cancel), cancel)
            .setOnlyAlertOnce(true)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_exports),
                // LOW: a long export should not buzz the user. It is progress, not an alert.
                NotificationManager.IMPORTANCE_LOW,
            )
        notifyManager.createNotificationChannel(channel)
    }

    private val notifyManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    companion object {
        private const val CHANNEL_ID = "exports"
        private const val NOTIF_ID = 1
        private const val ACTION_CANCEL = "com.videoupscaler.ai.CANCEL"

        fun start(context: Context) {
            val intent = Intent(context, ExportService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) = context.stopService(Intent(context, ExportService::class.java))
    }
}
