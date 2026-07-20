package com.shimonhoter.datatrafficguard.monitor

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Polls UsageStatsManager (requires PACKAGE_USAGE_STATS / "Usage Access" granted manually
 * by the user in Settings) to find out which app is currently in the foreground.
 *
 * TODO (step 5): poll queryEvents() every ~500ms-1s, emit the current foreground
 * package via a Flow/callback that PolicyEngine listens to for "Travel Mode".
 */
class ForegroundWatcherService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
