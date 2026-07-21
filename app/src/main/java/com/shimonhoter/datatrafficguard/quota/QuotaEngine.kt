package com.shimonhoter.datatrafficguard.quota

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.quotaDataStore by preferencesDataStore(name = "quota_store")

private val QUOTA_BYTES_KEY = longPreferencesKey("quota_bytes")
private val THRESHOLD_PERCENT_KEY = intPreferencesKey("threshold_percent")
private val CYCLE_START_KEY = longPreferencesKey("cycle_start_millis")
private val LAST_NOTIFIED_STEP_KEY = intPreferencesKey("last_notified_step")

data class QuotaSettings(
    val quotaBytes: Long = 0L,
    val thresholdPercent: Int = 50,
    val cycleStartMillis: Long = 0L,
    val lastNotifiedStep: Int = -1
) {
    val isConfigured: Boolean get() = quotaBytes > 0L && cycleStartMillis > 0L
}

/** Persists the data-plan quota, the alert threshold, and where the current cycle stands. */
class QuotaStore(private val context: Context) {
    val settings: Flow<QuotaSettings> = context.quotaDataStore.data.map { prefs ->
        QuotaSettings(
            quotaBytes = prefs[QUOTA_BYTES_KEY] ?: 0L,
            thresholdPercent = prefs[THRESHOLD_PERCENT_KEY] ?: 50,
            cycleStartMillis = prefs[CYCLE_START_KEY] ?: 0L,
            lastNotifiedStep = prefs[LAST_NOTIFIED_STEP_KEY] ?: -1
        )
    }

    /** New quota/threshold = new package/cycle: also resets notification progress. */
    suspend fun setQuota(quotaBytes: Long, thresholdPercent: Int) {
        context.quotaDataStore.edit { prefs ->
            prefs[QUOTA_BYTES_KEY] = quotaBytes
            prefs[THRESHOLD_PERCENT_KEY] = thresholdPercent
            prefs[CYCLE_START_KEY] = System.currentTimeMillis()
            prefs[LAST_NOTIFIED_STEP_KEY] = -1
        }
    }

    /** Same quota/threshold, fresh cycle (e.g. renewed the same roaming package). */
    suspend fun resetCycle() {
        context.quotaDataStore.edit { prefs ->
            prefs[CYCLE_START_KEY] = System.currentTimeMillis()
            prefs[LAST_NOTIFIED_STEP_KEY] = -1
        }
    }

    suspend fun setLastNotifiedStep(step: Int) {
        context.quotaDataStore.edit { prefs -> prefs[LAST_NOTIFIED_STEP_KEY] = step }
    }
}

class QuotaEngine(context: Context) {
    private val store = QuotaStore(context)
    val settings: Flow<QuotaSettings> = store.settings

    suspend fun setQuota(quotaBytes: Long, thresholdPercent: Int) = store.setQuota(quotaBytes, thresholdPercent)
    suspend fun resetCycle() = store.resetCycle()
    suspend fun setLastNotifiedStep(step: Int) = store.setLastNotifiedStep(step)
}
