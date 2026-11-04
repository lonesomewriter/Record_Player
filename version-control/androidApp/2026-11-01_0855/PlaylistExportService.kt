package dev.recorddisc.player.export

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
import dev.recorddisc.player.MainActivity
import dev.recorddisc.player.RecordDiscApplication
import dev.recorddisc.player.app.PlaylistBundleExportProgress
import dev.recorddisc.player.app.PlaylistBundleExportStage
import dev.recorddisc.player.app.PlaylistBundleExportStatus
import dev.recorddisc.player.app.PlaylistBundleExportTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class PlaylistExportService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeTask: kotlinx.coroutines.Job? = null
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Playlist exports", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val model = (application as RecordDiscApplication).model
        when (intent?.action) {
            ACTION_CANCEL -> {
                model.cancelPlaylistBundleExport()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, progressNotification(null))
                observeTask?.cancel()
                observeTask = serviceScope.launch {
                    model.playlistBundleExportTask.collect { task ->
                        when (task?.status) {
                            PlaylistBundleExportStatus.Running -> updateProgress(task)
                            PlaylistBundleExportStatus.Completed -> finish(task, succeeded = true, startId)
                            PlaylistBundleExportStatus.Failed -> finish(task, succeeded = false, startId)
                            PlaylistBundleExportStatus.Cancelled -> {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf(startId)
                            }
                            null -> Unit
                        }
                    }
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun updateProgress(task: PlaylistBundleExportTask) {
        if (canPostNotifications()) notifications.notify(NOTIFICATION_ID, progressNotification(task))
    }

    private fun finish(task: PlaylistBundleExportTask, succeeded: Boolean, startId: Int) {
        if (canPostNotifications()) {
            stopForeground(STOP_FOREGROUND_DETACH)
            notifications.notify(NOTIFICATION_ID, finishedNotification(task, succeeded))
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf(startId)
    }

    private fun progressNotification(task: PlaylistBundleExportTask?): Notification {
        val progress = task?.progress
        val fraction = progress?.fraction
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(task?.let { "Exporting ${it.playlistName}" } ?: "Preparing playlist export")
            .setContentText(progress?.let(::progressText) ?: "Your playlist bundle is being prepared")
            .setContentIntent(openInboxIntent())
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, fraction?.let { (it * 100).toInt() } ?: 0, fraction == null)
        if (task != null) {
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    "Cancel",
                    PendingIntent.getService(
                        this,
                        CANCEL_REQUEST,
                        Intent(this, PlaylistExportService::class.java).setAction(ACTION_CANCEL),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build(),
            )
        }
        return builder.build()
    }

    private fun finishedNotification(task: PlaylistBundleExportTask, succeeded: Boolean): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (succeeded) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (succeeded) "Playlist export complete" else "Playlist export failed")
            .setContentText(if (succeeded) "${task.playlistName} is ready" else "Couldn't export ${task.playlistName}")
            .setContentIntent(openInboxIntent())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun openInboxIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        OPEN_INBOX_REQUEST,
        Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_INBOX),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressText(progress: PlaylistBundleExportProgress): String {
        val stage = when (progress.stage) {
            PlaylistBundleExportStage.Copying -> "Copying songs and lyrics"
            PlaylistBundleExportStage.Packaging -> "Building the playlist package"
            PlaylistBundleExportStage.Saving -> "Saving the bundle"
            PlaylistBundleExportStage.Restoring -> "Restoring the previous file"
        }
        val percent = progress.fraction?.let { " · ${(it * 100).toInt()}%" }.orEmpty()
        return "$stage$percent"
    }

    private fun canPostNotifications(): Boolean =
        notifications.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.recorddisc.player.export.START"
        const val ACTION_CANCEL = "dev.recorddisc.player.export.CANCEL"
        private const val CHANNEL_ID = "playlist_exports"
        private const val NOTIFICATION_ID = 8401
        private const val OPEN_INBOX_REQUEST = 8402
        private const val CANCEL_REQUEST = 8403
    }
}
