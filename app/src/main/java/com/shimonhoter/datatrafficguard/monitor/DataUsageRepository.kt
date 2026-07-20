package com.shimonhoter.datatrafficguard.monitor

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Calendar

data class AppUsageSnapshot(
    val packageName: String,
    val uid: Int,
    val label: String,
    val isSystemApp: Boolean,
    val rxBytesPerSecond: Long,
    val txBytesPerSecond: Long,
    val totalBytesToday: Long,
    val unsupported: Boolean = false
)

/**
 * Real usage source: NetworkStatsManager (requires Usage Access, already granted).
 * TrafficStats per-other-uid is no longer reliable on modern Android — dropped.
 *
 * Note: the underlying OS accounting is batched, not instantaneous — the "per
 * second" rate may lag a few seconds behind real activity. This is a platform
 * limitation, not a bug in this app.
 */
class DataUsageRepository(private val context: Context) {

    private data class TrackedApp(
        val packageName: String, val uid: Int, val label: String, val isSystemApp: Boolean
    )

    private val networkStatsManager by lazy {
        context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
    }

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
                    label = pm.getApplicationLabel(app).toString(),
                    isSystemApp = (app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                )
            }
            .toList()
    }

    /** One pass over ALL uids for both network types — cheap regardless of app count. */
    private fun queryAllUidBytes(startMillis: Long, endMillis: Long): Map<Int, Pair<Long, Long>> {
        val totals = HashMap<Int, Pair<Long, Long>>()
        for (networkType in intArrayOf(ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_WIFI)) {
            try {
                val bucket = NetworkStats.Bucket()
                val stats = networkStatsManager.querySummary(networkType, null, startMillis, endMillis)
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    val (rx, tx) = totals[bucket.uid] ?: (0L to 0L)
                    totals[bucket.uid] = (rx + bucket.rxBytes) to (tx + bucket.txBytes)
                }
                stats.close()
            } catch (e: Exception) {
                // this network type unsupported/unavailable right now — skip it
            }
        }
        return totals
    }

    private fun startOfToday(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun observeUsage(intervalMs: Long = 3000L): Flow<List<AppUsageSnapshot>> = flow {
        val apps = networkApps()

        while (true) {
            val now = System.currentTimeMillis()
            val dayStart = startOfToday()
            val recentTotals = queryAllUidBytes(now - intervalMs, now)
            val todayTotals = queryAllUidBytes(dayStart, now)
            val queriesWorked = recentTotals.isNotEmpty() || todayTotals.isNotEmpty()

            val snapshots = apps.map { app ->
                val recent = recentTotals[app.uid]
                val today = todayTotals[app.uid]
                AppUsageSnapshot(
                    packageName = app.packageName,
                    uid = app.uid,
                    label = app.label,
                    isSystemApp = app.isSystemApp,
                    rxBytesPerSecond = ((recent?.first ?: 0L) * 1000 / intervalMs),
                    txBytesPerSecond = ((recent?.second ?: 0L) * 1000 / intervalMs),
                    totalBytesToday = (today?.first ?: 0L) + (today?.second ?: 0L),
                    unsupported = !queriesWorked
                )
            }

            emit(snapshots)
            delay(intervalMs)
        }
    }
}
