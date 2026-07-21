package com.shimonhoter.datatrafficguard.policy

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.policyDataStore by preferencesDataStore(name = "policy_store")
private val BLOCKED_PACKAGES_KEY = stringSetPreferencesKey("blocked_packages")
private val TRAVEL_MODE_KEY = booleanPreferencesKey("travel_mode_enabled")

/** Persists manual block choices and the Travel Mode toggle across restarts. */
class PolicyStore(private val context: Context) {
    val blockedPackages: Flow<Set<String>> = context.policyDataStore.data
        .map { prefs -> prefs[BLOCKED_PACKAGES_KEY] ?: emptySet() }

    val travelModeEnabled: Flow<Boolean> = context.policyDataStore.data
        .map { prefs -> prefs[TRAVEL_MODE_KEY] ?: false }

    suspend fun setBlocked(packageName: String, blocked: Boolean) {
        context.policyDataStore.edit { prefs ->
            val current = prefs[BLOCKED_PACKAGES_KEY] ?: emptySet()
            prefs[BLOCKED_PACKAGES_KEY] = if (blocked) current + packageName else current - packageName
        }
    }

    suspend fun setTravelMode(enabled: Boolean) {
        context.policyDataStore.edit { prefs -> prefs[TRAVEL_MODE_KEY] = enabled }
    }
}

class PolicyEngine(context: Context) {
    private val store = PolicyStore(context)
    val blockedPackages: Flow<Set<String>> = store.blockedPackages
    val travelModeEnabled: Flow<Boolean> = store.travelModeEnabled

    suspend fun toggleBlock(packageName: String, blocked: Boolean) {
        store.setBlocked(packageName, blocked)
    }

    suspend fun setTravelMode(enabled: Boolean) {
        store.setTravelMode(enabled)
    }
}
