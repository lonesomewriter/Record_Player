package dev.recorddisc.player.convert

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dev.recorddisc.player.MainActivity
import dev.recorddisc.player.RecordDiscApplication
import dev.recorddisc.player.app.ConvertStatus
import dev.recorddisc.player.app.ConvertTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps Convert to Audio going when the app is in the background: a foreground service with a
 * progress notification (and Cancel), holding a partial wake lock only while converting so the
 * phone doesn't sleep mid-file. The work itself runs in the app's model; this follows its task.
 */
class ConvertService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeTask: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Conversions", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val model = (application as RecordDiscApplication).model
        when (intent?.action) {
            ACTION_CANCEL -> {
                model.cancelConversion()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                notifications.cancel(FINISHED_NOTIFICATION_ID)
                startForeground(NOTIFICATION_ID, progressNotification(null))
                holdWakeLock()
                observeTask?.cancel()
                var seenRunning = false
                observeTask = serviceScope.launch {
                    model.convertTask.collect { task ->
                        when (task?.status) {
                            ConvertStatus.Running -> { seenRunning = true; updateProgress(task) }
                            ConvertStatus.Completed -> finish(task, succeeded = true, startId)
                            ConvertStatus.Failed -> finish(task, succeeded = false, startId)
                            ConvertStatus.Cancelled -> finish(null, succeeded = false, startId)
                            // Dismissed, or not started yet (the model fills the task in a moment later).
                            null -> if (seenRunning) finish(null, succeeded = false, startId)
                        }
                    }
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun holdWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RecordPlayer:convert")
            .apply { setReferenceCounted(false); acquire(MAX_WAKE_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun updateProgress(task: ConvertTask) {
        if (canPostNotifications()) notifications.notify(NOTIFICATION_ID, progressNotification(task))
    }

    /** [task] is null when there's nothing to report (cancelled or dismissed). */
    private fun finish(task: ConvertTask?, succeeded: Boolean, startId: Int) {
        observeTask?.cancel()
        observeTask = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (task != null && canPostNotifications()) notifications.notify(FINISHED_NOTIFICATION_ID, finishedNotification(task, succeeded))
        stopSelf(startId)
    }

    private fun progressNotification(task: ConvertTask?): Notification {
        val progress = task?.progress
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(task?.let { "Converting “${it.playlistName}”" } ?: "Preparing the conversion")
            .setContentText(task?.let(::stageText) ?: "Getting the songs ready")
            .setContentIntent(openInboxIntent())
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, task?.percent ?: 0, progress == null)
        if (task != null) {
            builder.addAction(
                Notification.Action.Builder(
                    null, "Cancel",
                    PendingIntent.getService(
                        this, CANCEL_REQUEST,
                        Intent(this, ConvertService::class.java).setAction(ACTION_CANCEL),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build(),
            )
        }
        return builder.build()
    }

    private fun finishedNotification(task: ConvertTask, succeeded: Boolean): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (succeeded) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (succeeded) "Converted “${task.playlistName}”" else "Couldn't convert “${task.playlistName}”")
            .setContentText(if (succeeded) "Saved to ${task.saved?.shownAs ?: task.destinationLabel}" else task.failure ?: "The conversion failed")
            .setContentIntent(openInboxIntent())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun openInboxIntent(): PendingIntent = PendingIntent.getActivity(
        this, OPEN_INBOX_REQUEST,
        Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_INBOX),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stageText(task: ConvertTask): String {
        val p = task.progress ?: return "Starting"
        val step = if (p.measuring) "Measuring volume · song ${p.song + 1} of ${p.songs}" else "Song ${p.song + 1} of ${p.songs}"
        return "$step · ${task.percent}%"
    }

    private fun canPostNotifications(): Boolean =
        notifications.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.recorddisc.player.convert.START"
        const val ACTION_CANCEL = "dev.recorddisc.player.convert.CANCEL"
        private const val CHANNEL_ID = "conversions"
        private const val NOTIFICATION_ID = 8501
        private const val FINISHED_NOTIFICATION_ID = 8504
        private const val OPEN_INBOX_REQUEST = 8502
        private const val CANCEL_REQUEST = 8503
        /** A safety limit: the lock is released when the job ends, or after this at the latest. */
        private const val MAX_WAKE_MS = 6 * 60 * 60 * 1000L
    }
}
