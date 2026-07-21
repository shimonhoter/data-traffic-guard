package com.shimonhoter.datatrafficguard.quota

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Fired periodically by AlarmManager so quota is checked even while the app is closed. */
class QuotaCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                QuotaChecker.checkAndNotify(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val REQUEST_CODE = 4200
        private const val INTERVAL_MS = 15 * 60 * 1000L // 15 minutes — no need for exact alarms here

        /** Idempotent — safe to call on every process start (setInexactRepeating replaces in place). */
        fun schedule(context: Context) {
            val pendingIntent = PendingIntent.getBroadcast(
                context, REQUEST_CODE, Intent(context, QuotaCheckReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                INTERVAL_MS,
                pendingIntent
            )
        }
    }
}
