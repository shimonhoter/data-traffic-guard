package com.shimonhoter.datatrafficguard.quota

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.quotaDataStore by preferencesDataStore(name = "quota_store")

private val QUOTA_BYTES_KEY = longPreferencesKey("quota_bytes")
private val THRESHOLD_PERCENT_KEY = intPreferencesKey("threshold_percent")
private val CYCLE_START_KEY = longPreferencesKey("cycle_start_millis")
private val LAST_NOTIFIED_STEP_KEY = intPreferencesKey("last_notified_step")
private val ACCUMULATED_MOBILE_KEY = longPreferencesKey("accumulated_mobile_bytes")
private val ACCUMULATED_ALL_KEY = longPreferencesKey("accumulated_all_bytes")
private val LAST_TICK_MILLIS_KEY = longPreferencesKey("last_usage_tick_millis")

data class QuotaSettings(
    val quotaBytes: Long = 0L,
    val thresholdPercent: Int = 50,
    val cycleStartMillis: Long = 0L,
    val lastNotifiedStep: Int = -1,
    /** Cumulative mobile-only bytes for the current cycle — a true running total, built up
     *  incrementally tick by tick (see VpnGuardService), not recomputed from scratch. Only
     *  ever grows, and only resets via setQuota()/resetCycle(). */
    val accumulatedMobileBytes: Long = 0L,
    /** Same idea, but mobile+WiFi combined. */
    val accumulatedAllBytes: Long = 0L
) {
    val isConfigured: Boolean get() = quotaBytes > 0L && cycleStartMillis > 0L
}

/** Persists the data-plan quota, the alert threshold, where the current cycle stands, and
 *  the running usage accumulators. */
class QuotaStore(private val context: Context) {
    val settings: Flow<QuotaSettings> = context.quotaDataStore.data.map { prefs ->
        QuotaSettings(
            quotaBytes = prefs[QUOTA_BYTES_KEY] ?: 0L,
            thresholdPercent = prefs[THRESHOLD_PERCENT_KEY] ?: 50,
            cycleStartMillis = prefs[CYCLE_START_KEY] ?: 0L,
            lastNotifiedStep = prefs[LAST_NOTIFIED_STEP_KEY] ?: -1,
            accumulatedMobileBytes = prefs[ACCUMULATED_MOBILE_KEY] ?: 0L,
            accumulatedAllBytes = prefs[ACCUMULATED_ALL_KEY] ?: 0L
        )
    }

    /** New quota/threshold = new package/cycle: also resets notification progress and the
     *  usage accumulators — this is a deliberate, user-initiated fresh start. */
    suspend fun setQuota(quotaBytes: Long, thresholdPercent: Int) {
        val now = System.currentTimeMillis()
        context.quotaDataStore.edit { prefs ->
            prefs[QUOTA_BYTES_KEY] = quotaBytes
            prefs[THRESHOLD_PERCENT_KEY] = thresholdPercent
            prefs[CYCLE_START_KEY] = now
            prefs[LAST_NOTIFIED_STEP_KEY] = -1
            prefs[ACCUMULATED_MOBILE_KEY] = 0L
            prefs[ACCUMULATED_ALL_KEY] = 0L
            prefs[LAST_TICK_MILLIS_KEY] = now
        }
    }

    /** Same quota/threshold, fresh cycle (e.g. renewed the same roaming package). */
    suspend fun resetCycle() {
        val now = System.currentTimeMillis()
        context.quotaDataStore.edit { prefs ->
            prefs[CYCLE_START_KEY] = now
            prefs[LAST_NOTIFIED_STEP_KEY] = -1
            prefs[ACCUMULATED_MOBILE_KEY] = 0L
            prefs[ACCUMULATED_ALL_KEY] = 0L
            prefs[LAST_TICK_MILLIS_KEY] = now
        }
    }

    suspend fun setLastNotifiedStep(step: Int) {
        context.quotaDataStore.edit { prefs -> prefs[LAST_NOTIFIED_STEP_KEY] = step }
    }

    /** Adds this tick's real usage delta to the running totals — called periodically by
     *  VpnGuardService, never recomputed from scratch, so mode switches can't cause jumps. */
    suspend fun addUsageDelta(mobileDelta: Long, allDelta: Long, tickMillis: Long) {
        context.quotaDataStore.edit { prefs ->
            val currentMobile = prefs[ACCUMULATED_MOBILE_KEY] ?: 0L
            val currentAll = prefs[ACCUMULATED_ALL_KEY] ?: 0L
            prefs[ACCUMULATED_MOBILE_KEY] = currentMobile + mobileDelta
            prefs[ACCUMULATED_ALL_KEY] = currentAll + allDelta
            prefs[LAST_TICK_MILLIS_KEY] = tickMillis
        }
    }

    /** One-shot read of where tracking last left off, so the service can resume without a
     *  gap (or double-count) after being restarted by the system. */
    suspend fun getLastTickMillis(): Long =
        context.quotaDataStore.data.map { it[LAST_TICK_MILLIS_KEY] ?: System.currentTimeMillis() }.first()
}

class QuotaEngine(context: Context) {
    private val store = QuotaStore(context)
    val settings: Flow<QuotaSettings> = store.settings

    suspend fun setQuota(quotaBytes: Long, thresholdPercent: Int) = store.setQuota(quotaBytes, thresholdPercent)
    suspend fun resetCycle() = store.resetCycle()
    suspend fun setLastNotifiedStep(step: Int) = store.setLastNotifiedStep(step)
    suspend fun addUsageDelta(mobileDelta: Long, allDelta: Long, tickMillis: Long) =
        store.addUsageDelta(mobileDelta, allDelta, tickMillis)
    suspend fun getLastTickMillis(): Long = store.getLastTickMillis()
}
