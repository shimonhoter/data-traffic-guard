package com.shimonhoter.datatrafficguard.monitor

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
                // this network type unsupported/unavailable right now — skip it, don't crash
            }
        }
        return totals
    }

    /**
     * Total device-wide data used since [startMillis] across mobile + WiFi — this is the
     * "כל האפליקציות" total (matches what Android's own Data Usage screen would show),
     * not just the apps we enumerate individually.
     */
    fun totalDeviceBytesSince(startMillis: Long): Long {
        val now = System.currentTimeMillis()
        if (startMillis <= 0L || startMillis >= now) return 0L
        var total = 0L
        for (networkType in intArrayOf(ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_WIFI)) {
            try {
                val bucket = networkStatsManager.querySummaryForDevice(networkType, null, startMillis, now)
                total += bucket.rxBytes + bucket.txBytes
            } catch (e: Exception) {
                // this network type unsupported/unavailable right now — skip it, don't crash
            }
        }
        return total
    }

    private fun startOfToday(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun errorRow(message: String) = AppUsageSnapshot(
        packageName = "error", uid = -1, label = "שגיאה: $message", isSystemApp = false,
        rxBytesPerSecond = 0, txBytesPerSecond = 0, totalBytesToday = 0, unsupported = true
    )

    /**
     * flowOn(Dispatchers.Default): enumerating ~500+ installed apps and running
     * NetworkStatsManager queries every few seconds is too heavy for the main
     * thread — doing it there caused ANRs ("App Not Responding"). This moves
     * all of that work to a background dispatcher; only the emitted snapshots
     * cross back to the UI thread via collectAsState.
     */
    fun observeUsage(intervalMs: Long = 3000L): Flow<List<AppUsageSnapshot>> = flow {
        val apps = try {
            networkApps()
        } catch (e: Exception) {
            emit(listOf(errorRow("networkApps(): ${e::class.simpleName} - ${e.message}")))
            emptyList()
        }

        while (true) {
            try {
                val now = System.currentTimeMillis()
                val dayStart = startOfToday()
                val recentTotals = queryAllUidBytes(now - intervalMs, now)
                val todayTotals = queryAllUidBytes(dayStart, now)

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
                        unsupported = false
                    )
                }
                emit(snapshots)
            } catch (e: Exception) {
                emit(listOf(errorRow("${e::class.simpleName} - ${e.message}")))
            }
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.Default)
}
