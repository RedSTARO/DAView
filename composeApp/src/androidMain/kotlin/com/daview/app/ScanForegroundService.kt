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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the process alive for as long as a scan is running.
 *
 * A scan is minutes of HTTP round trips against the share, and Android will
 * reclaim a backgrounded process in the middle of one without saying anything —
 * which meant a scan started on the phone died the moment the user switched
 * apps, and looked like it had simply stopped.
 *
 * The service does not run the scan. `ScanService` in the core already owns
 * that; this watches its progress, keeps the notification in step and stops
 * itself once nothing is running, so the ongoing notification is never left
 * behind.
 */
class ScanForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_LIBRARY_ID)?.let { createCoreContext().scans.cancel(it) }
            return START_NOT_STICKY
        }

        createChannel()
        startForegroundCompat(buildNotification("正在扫描媒体库", "准备中", null))
        if (watcher?.isActive != true) watcher = scope.launch { watch() }
        // Not sticky: a scan that was killed with the process has not been
        // restarted, so bringing the service back alone would show a
        // notification for work nobody is doing.
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        val scans = createCoreContext().scans
        // The submit and the service start race; give the scan a moment to
        // appear before concluding there is nothing to watch.
        delay(500)
        while (scope.isActive) {
            val running = scans.status().filter { it.running }
            if (running.isEmpty()) break
            val first = running.first()
            val progress = if (first.total > 0) first.current to first.total else null
            NotificationManagerCompat.from(this).notify(
                NOTIFICATION_ID,
                buildNotification(
                    title = if (running.size == 1) "正在扫描 ${first.libraryName}" else "正在扫描 ${running.size} 个媒体库",
                    text = listOf(phaseLabel(first.phase), first.message).filter { it.isNotBlank() }.joinToString(" · "),
                    progress = progress,
                    cancelLibraryId = first.libraryId
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
        cancelLibraryId: String? = null
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
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        progress?.let { (current, total) -> builder.setProgress(total, current, false) }
            ?: builder.setProgress(0, 0, true)

        cancelLibraryId?.let { id ->
            val cancel = PendingIntent.getService(
                this,
                1,
                Intent(this, ScanForegroundService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_LIBRARY_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancel)
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
            "媒体库扫描",
            // Low: it is a progress bar, not something to interrupt anyone for.
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun phaseLabel(phase: String) = when (phase) {
        "queued" -> "排队中"
        "listing" -> "读取目录"
        "scanning" -> "扫描文件"
        "saving" -> "写入数据库"
        "scraping" -> "刮削"
        "probing" -> "解析容器"
        else -> ""
    }

    companion object {
        private const val CHANNEL_ID = "daview-scan"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_CANCEL = "com.daview.app.CANCEL_SCAN"
        private const val EXTRA_LIBRARY_ID = "libraryId"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ScanForegroundService::class.java))
        }
    }
}

private fun CoroutineScope.cancel() = coroutineContext[Job]?.cancel()
