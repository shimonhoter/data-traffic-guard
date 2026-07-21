package com.shimonhoter.datatrafficguard.monitor

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Polls UsageStatsManager (requires Usage Access, already granted for the
 * data-usage screen) to find the app currently in the foreground.
 * Used by VpnGuardService's Travel Mode loop.
 */
object ForegroundWatcher {
    fun currentForegroundPackage(context: Context): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - 10_000 // look back 10s to catch the most recent event
        val events = usm.queryEvents(start, end)
        var lastForeground: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastForeground = event.packageName
            }
        }
        return lastForeground
    }
}
