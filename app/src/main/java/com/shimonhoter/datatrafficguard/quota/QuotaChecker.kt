package com.shimonhoter.datatrafficguard.quota

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.shimonhoter.datatrafficguard.MainActivity
import com.shimonhoter.datatrafficguard.monitor.DataUsageRepository
import kotlinx.coroutines.flow.first

private const val QUOTA_CHANNEL_ID = "quota_alert_channel"
private const val QUOTA_NOTIFICATION_ID_BASE = 2000

/**
 * From the moment usage crosses the configured threshold %, fires one notification per
 * additional 10% (threshold, threshold+10, threshold+20, ...). lastNotifiedStep persists
 * so this is safe to call repeatedly (e.g. every alarm tick) without duplicate notifications.
 */
object QuotaChecker {

    suspend fun checkAndNotify(context: Context) {
        val quotaEngine = QuotaEngine(context)
        val settings = quotaEngine.settings.first()
        if (!settings.isConfigured) return

        val repository = DataUsageRepository(context)
        val usedBytes = repository.totalDeviceBytesSince(settings.cycleStartMillis)
        val percent = ((usedBytes.toDouble() / settings.quotaBytes.toDouble()) * 100).toInt()

        if (percent < settings.thresholdPercent) return

        val stepsPast = (percent - settings.thresholdPercent) / 10
        val currentStep = settings.thresholdPercent + stepsPast * 10

        if (currentStep > settings.lastNotifiedStep) {
            notify(context, currentStep, usedBytes, settings.quotaBytes)
            quotaEngine.setLastNotifiedStep(currentStep)
        }
    }

    private fun notify(context: Context, percentStep: Int, usedBytes: Long, quotaBytes: Long) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(QUOTA_CHANNEL_ID, "התראות סף נתונים", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val openAppIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, QUOTA_CHANNEL_ID)
            .setContentTitle("הגעת ל-$percentStep% מהמכסה")
            .setContentText("${formatGb(usedBytes)} מתוך ${formatGb(quotaBytes)} נוצלו במחזור הנוכחי")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .build()
        notificationManager.notify(QUOTA_NOTIFICATION_ID_BASE + percentStep, notification)
    }

    private fun formatGb(bytes: Long): String = "%.2fGB".format(bytes / 1_000_000_000.0)
}
