package com.shimonhoter.datatrafficguard.monitor

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

class ForegroundWatcher {
    private var lastCheckedMillis: Long = System.currentTimeMillis() - 15_000
    private var lastKnownForeground: String? = null

    fun currentForegroundPackage(context: Context): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastCheckedMillis, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastKnownForeground = event.packageName
            }
        }
        lastCheckedMillis = now
        return lastKnownForeground
    }
}
