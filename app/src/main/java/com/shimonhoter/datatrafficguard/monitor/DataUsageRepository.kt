package com.shimonhoter.datatrafficguard.monitor

import android.content.Context
import android.content.pm.ApplicationInfo
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
    val totalBytesSinceBoot: Long,
    val unsupported: Boolean = false
)

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

    fun observeUsage(intervalMs: Long = 1000L): Flow<List<AppUsageSnapshot>> = flow {
        val apps = networkApps()
        val previous = HashMap<Int, Pair<Long, Long>>()

        // one-time diagnostic row so we always see SOMETHING even if everything else fails
        emit(listOf(diagRow("נמצאו ${apps.size} אפליקציות להצגה")))

        while (true) {
            val snapshots = apps.map { app ->
                val rx = TrafficStats.getUidRxBytes(app.uid)
                val tx = TrafficStats.getUidTxBytes(app.uid)
                val isUnsupported = rx < 0 || tx < 0
                val (prevRx, prevTx) = previous[app.uid] ?: (rx.coerceAtLeast(0) to tx.coerceAtLeast(0))
                if (!isUnsupported) previous[app.uid] = rx to tx

                val rxPerSec = if (isUnsupported) 0 else ((rx - prevRx).coerceAtLeast(0) * 1000 / intervalMs)
                val txPerSec = if (isUnsupported) 0 else ((tx - prevTx).coerceAtLeast(0) * 1000 / intervalMs)

                AppUsageSnapshot(
                    packageName = app.packageName,
                    uid = app.uid,
                    label = app.label,
                    rxBytesPerSecond = rxPerSec,
                    txBytesPerSecond = txPerSec,
                    totalBytesSinceBoot = if (isUnsupported) 0 else rx + tx,
                    unsupported = isUnsupported
                )
            }.sortedByDescending { it.totalBytesSinceBoot }

            emit(snapshots)
            delay(intervalMs)
        }
    }

    private fun diagRow(message: String) = AppUsageSnapshot(
        packageName = "diag", uid = -1, label = message,
        rxBytesPerSecond = 0, txBytesPerSecond = 0, totalBytesSinceBoot = 0, unsupported = true
    )
}
