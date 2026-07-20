package com.shimonhoter.datatrafficguard.monitor

/**
 * Wraps NetworkStatsManager (accurate cumulative per-app usage, cellular vs WiFi)
 * and TrafficStats (live instantaneous rx/tx bytes per uid, sampled every ~1s).
 *
 * TODO (step 3): expose a Flow<List<AppUsageSnapshot>> that DashboardScreen collects.
 */
data class AppUsageSnapshot(
    val packageName: String,
    val uid: Int,
    val rxBytesPerSecond: Long,
    val txBytesPerSecond: Long,
    val totalBytesToday: Long
)

class DataUsageRepository
