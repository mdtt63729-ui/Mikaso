package com.mikaso.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mikaso.app.DownloadService
import com.mikaso.app.MainActivity
import com.mikaso.app.R

/** Every notification Mikaso shows: the ongoing download and the "finished / failed" note. */
class Notifier(private val context: Context) {

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while a download is running"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when a download is ready or fails"
            }
        )
    }

    fun canNotify(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** The ongoing (foreground) notification. [showProgress] is the Settings toggle. */
    fun progress(tasks: List<DownloadTask>, showProgress: Boolean): Notification {
        val running = tasks.filter { it.active }
        val title: String
        val text: String
        var percent = 0
        var indeterminate = false

        if (running.size == 1) {
            val t = running.first()
            title = t.request.title
            text = when {
                t.queuePosition > 0 -> "Waiting in queue…"
                t.state == TaskState.Saving -> "Saving to your phone…"
                else -> STAGE_LABELS.getOrElse(t.stageIndex) { "Working…" }
            }
            percent = t.percent.toInt()
            indeterminate = t.indeterminate || t.queuePosition > 0
        } else {
            title = "Downloading ${running.size} items"
            percent = if (running.isEmpty()) 0 else (running.sumOf { it.percent.toDouble() } / running.size).toInt()
            text = "Mikaso is working in the background"
            indeterminate = running.any { it.queuePosition > 0 }
        }

        val cancelAll = PendingIntent.getService(
            context, 1,
            Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openDownloads())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Cancel all", cancelAll)

        if (showProgress && running.isNotEmpty()) {
            builder.setProgress(100, percent.coerceIn(0, 100), indeterminate)
        }
        return builder.build()
    }

    /** Replace the visible foreground notification (called as progress changes). */
    fun notifyForeground(context: Context, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications are turned off for the app; nothing to do.
        }
    }

    /** One short note when a download finishes or fails. */
    fun done(title: String, success: Boolean, show: Boolean) {
        if (!show || !canNotify()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (success) "Download finished" else "Download failed")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(openDownloads())
            .build()
        try {
            NotificationManagerCompat.from(context).notify(title.hashCode(), notification)
        } catch (e: SecurityException) {
            // Ignore.
        }
    }

    private fun openDownloads(): PendingIntent = PendingIntent.getActivity(
        context, 2,
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_TAB, "downloads")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val NOTIFICATION_ID = 1010
        const val EXTRA_TAB = "tab"
        private const val CHANNEL_PROGRESS = "mikaso_downloads"
        private const val CHANNEL_DONE = "mikaso_finished"
    }
}
