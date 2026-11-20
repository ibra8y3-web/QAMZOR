package org.qamzor.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Notifications for long operations: live progress while running, and a result when the app is in the background. */
object OperationNotifier {
    private const val CHANNEL_PROGRESS = "qamzor_progress"
    private const val CHANNEL_RESULT = "qamzor_result"
    const val ID_PROGRESS = 7001
    private var resultCounter = 7100

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "تقدّم العمليات", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_RESULT, "نتائج العمليات", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** [percent] below zero shows an indeterminate bar. */
    fun progressNotification(context: Context, title: String, text: String, percent: Int): Notification {
        ensureChannels(context)
        return Notification.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_qamzor)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .build()
    }

    fun updateProgress(context: Context, title: String, text: String, percent: Int) {
        if (!hasPermission(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(ID_PROGRESS, progressNotification(context, title, text, percent))
    }

    fun clearProgress(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_PROGRESS)
    }

    @Synchronized
    fun notifyResult(context: Context, title: String, text: String) {
        if (!hasPermission(context)) return
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = Notification.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_qamzor)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        resultCounter += 1
        manager.notify(resultCounter, notification)
    }
}

/**
 * Keeps the process alive (as a foreground "data sync" service) while a long archive operation runs, so it
 * can finish after the user leaves the app. The service does no work itself; the operation thread does.
 */
class OperationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "QAMZOR"
        try {
            val notification = OperationNotifier.progressNotification(this, title, "جارٍ التنفيذ…", -1)
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(OperationNotifier.ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(OperationNotifier.ID_PROGRESS, notification)
            }
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, title: String) {
            try {
                context.startForegroundService(Intent(context, OperationService::class.java).putExtra(EXTRA_TITLE, title))
            } catch (_: Exception) {
                // Not allowed right now; the operation still runs while the app is visible.
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, OperationService::class.java))
            } catch (_: Exception) {
            }
            OperationNotifier.clearProgress(context)
        }
    }
}
