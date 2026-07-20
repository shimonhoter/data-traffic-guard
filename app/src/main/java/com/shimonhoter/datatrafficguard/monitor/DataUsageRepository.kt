package com.shimonhoter.datatrafficguard.monitor

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.TrafficStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class AppUsageSnapshot(
    val packageName: String,
    val uid: Int,
    val label: String,
    val rxBytesPerSecond: Long,
    val txBytesPerSecond: Long,
    val totalBytesSinceBoot: Long
)

/**
 * Live per-app usage via TrafficStats (no extra permission needed).
 *
 * totalBytesSinceBoot resets on device reboot — a proper "since midnight" /
 * "since travel started" figure needs NetworkStatsManager + subscriberId for
 * cellular, which is a later refinement (noted in the spec, section 5).
 */
class DataUsageRepository(private val context: Context) {

    private data class TrackedApp(val packageName: String, val uid: Int, val label: String)

    private fun networkApps(): List<TrackedApp> {
        val pm = context.packageManager
        @Suppress("DEPRECATION") val apps = pm.getInstalledApplications(0)
        return apps
            .asSequence()
            .filter { it.uid >= android.os.Process.FIRST_APPLICATION_UID }
            .distinctBy { it.uid }
            .map { app: ApplicationInfo ->
                TrackedApp(
                    packageName = app.packageName,
                    uid = app.uid,
                    label = pm.getApplicationLabel(app).toString()
                )
            }
            .toList()
    }

    /** Emits a full snapshot every [intervalMs], sorted by current total usage descending. */
    fun observeUsage(intervalMs: Long = 1000L): Flow<List<AppUsageSnapshot>> = flow {
        val apps = networkApps()
        val previous = HashMap<Int, Pair<Long, Long>>() // uid -> (rx, tx) at last tick

        while (true) {
            val snapshots = apps.mapNotNull { app ->
                val rx = TrafficStats.getUidRxBytes(app.uid)
                val tx = TrafficStats.getUidTxBytes(app.uid)
                if (rx < 0 || tx < 0) return@mapNotNull null

                val (prevRx, prevTx) = previous[app.uid] ?: (rx to tx)
                previous[app.uid] = rx to tx

                val rxPerSec = ((rx - prevRx).coerceAtLeast(0) * 1000 / intervalMs)
                val txPerSec = ((tx - prevTx).coerceAtLeast(0) * 1000 / intervalMs)

                AppUsageSnapshot(
                    packageName = app.packageName,
                    uid = app.uid,
                    label = app.label,
                    rxBytesPerSecond = rxPerSec,
                    txBytesPerSecond = txPerSec,
                    totalBytesSinceBoot = rx + tx
                )
            }.sortedByDescending { it.totalBytesSinceBoot }

            emit(snapshots)
            delay(intervalMs)
        }
    }
}
