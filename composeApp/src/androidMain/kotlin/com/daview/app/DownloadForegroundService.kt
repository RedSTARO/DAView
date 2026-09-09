package com.daview.app

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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.daview.app.platform.createCoreContext
import com.daview.shared.model.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while a film is being copied to this device.
 *
 * A download is minutes of transfer, and Android reclaims a backgrounded
 * process without warning — which for anything long enough to be worth
 * downloading means it would essentially never finish unless the user sat and
 * watched it. Same shape as [ScanForegroundService]: the service does not do
 * the work, it watches the core's own progress, keeps the notification in step,
 * and stops itself once nothing is moving so an ongoing notification is never
 * left behind.
 */
class DownloadForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_ITEM_ID)?.let { createCoreContext().offline.cancel(it) }
            return START_NOT_STICKY
        }

        createChannel()
        startForegroundCompat(buildNotification("正在下载", "准备中", null))
        if (watcher?.isActive != true) watcher = scope.launch { watch() }
        // Not sticky: a transfer killed with the process has not resumed, and a
        // notification for work nobody is doing is worse than none.
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        val offline = createCoreContext().offline
        // The queue write and the service start race; give the download a
        // moment to appear before concluding there is nothing to watch.
        delay(500)
        while (scope.isActive) {
            val active = offline.all().filter {
                it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED
            }
            if (active.isEmpty()) break
            val first = active.first()
            NotificationManagerCompat.from(this).notify(
                NOTIFICATION_ID,
                buildNotification(
                    title = if (active.size == 1) "正在下载 ${first.name}" else "正在下载 ${active.size} 个条目",
                    text = if (first.totalBytes > 0) {
                        "${(first.fraction * 100).toInt()}% · ${formatSize(first.downloadedBytes)} / ${formatSize(first.totalBytes)}"
                    } else {
                        formatSize(first.downloadedBytes)
                    },
                    progress = first.totalBytes.takeIf { it > 0 }?.let {
                        (first.fraction * 100).toInt() to 100
                    },
                    cancelItemId = first.itemId
                )
            )
            delay(1500)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Pair<Int, Int>?,
        cancelItemId: String? = null
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setColor(BRAND_COLOUR)
            .setColorized(false)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        progress?.let { (current, total) -> builder.setProgress(total, current, false) }
            ?: builder.setProgress(0, 0, true)

        cancelItemId?.let { id ->
            val cancel = PendingIntent.getService(
                this,
                1,
                Intent(this, DownloadForegroundService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_ITEM_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, "取消", cancel)
        }
        return builder.build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "离线下载",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        super.onDestroy()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return "${((value * 10).toLong() / 10.0)} ${units[index]}"
    }

    companion object {
        private const val CHANNEL_ID = "daview-download"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_CANCEL = "com.daview.app.CANCEL_DOWNLOAD"
        private const val EXTRA_ITEM_ID = "itemId"

        /** The app's primary violet, so the notification is recognisably ours. */
        private const val BRAND_COLOUR = 0xFF4B2CD6.toInt()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DownloadForegroundService::class.java))
        }
    }
}
