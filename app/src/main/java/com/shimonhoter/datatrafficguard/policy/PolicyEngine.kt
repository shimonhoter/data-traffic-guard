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
private val SCREEN_OFF_ENABLED_KEY = booleanPreferencesKey("screen_off_allowlist_enabled")
private val SCREEN_OFF_ALLOWED_KEY = stringSetPreferencesKey("screen_off_allowed_packages")
private val SCREEN_ON_ENABLED_KEY = booleanPreferencesKey("screen_on_allowlist_enabled")
private val SCREEN_ON_ALLOWED_KEY = stringSetPreferencesKey("screen_on_allowed_packages")

/** Persists manual block choices, Travel Mode, and separate allowlists for the
 *  screen-off and screen-on restriction modes. */
class PolicyStore(private val context: Context) {
    val blockedPackages: Flow<Set<String>> = context.policyDataStore.data
        .map { prefs -> prefs[BLOCKED_PACKAGES_KEY] ?: emptySet() }

    val travelModeEnabled: Flow<Boolean> = context.policyDataStore.data
        .map { prefs -> prefs[TRAVEL_MODE_KEY] ?: false }

    val screenOffAllowlistEnabled: Flow<Boolean> = context.policyDataStore.data
        .map { prefs -> prefs[SCREEN_OFF_ENABLED_KEY] ?: false }

    val screenOffAllowedPackages: Flow<Set<String>> = context.policyDataStore.data
        .map { prefs -> prefs[SCREEN_OFF_ALLOWED_KEY] ?: emptySet() }

    val screenOnAllowlistEnabled: Flow<Boolean> = context.policyDataStore.data
        .map { prefs -> prefs[SCREEN_ON_ENABLED_KEY] ?: false }

    val screenOnAllowedPackages: Flow<Set<String>> = context.policyDataStore.data
        .map { prefs -> prefs[SCREEN_ON_ALLOWED_KEY] ?: emptySet() }

    suspend fun setBlocked(packageName: String, blocked: Boolean) {
        context.policyDataStore.edit { prefs ->
            val current = prefs[BLOCKED_PACKAGES_KEY] ?: emptySet()
            prefs[BLOCKED_PACKAGES_KEY] = if (blocked) current + packageName else current - packageName
        }
    }

    suspend fun setTravelMode(enabled: Boolean) {
        context.policyDataStore.edit { prefs -> prefs[TRAVEL_MODE_KEY] = enabled }
    }

    suspend fun setScreenOffAllowlistEnabled(enabled: Boolean) {
        context.policyDataStore.edit { prefs -> prefs[SCREEN_OFF_ENABLED_KEY] = enabled }
    }

    suspend fun setScreenOffAllowed(packageName: String, allowed: Boolean) {
        context.policyDataStore.edit { prefs ->
            val current = prefs[SCREEN_OFF_ALLOWED_KEY] ?: emptySet()
            prefs[SCREEN_OFF_ALLOWED_KEY] = if (allowed) current + packageName else current - packageName
        }
    }

    suspend fun setScreenOnAllowlistEnabled(enabled: Boolean) {
        context.policyDataStore.edit { prefs -> prefs[SCREEN_ON_ENABLED_KEY] = enabled }
    }

    suspend fun setScreenOnAllowed(packageName: String, allowed: Boolean) {
        context.policyDataStore.edit { prefs ->
            val current = prefs[SCREEN_ON_ALLOWED_KEY] ?: emptySet()
            prefs[SCREEN_ON_ALLOWED_KEY] = if (allowed) current + packageName else current - packageName
        }
    }
}

class PolicyEngine(context: Context) {
    private val store = PolicyStore(context)
    val blockedPackages: Flow<Set<String>> = store.blockedPackages
    val travelModeEnabled: Flow<Boolean> = store.travelModeEnabled
    val screenOffAllowlistEnabled: Flow<Boolean> = store.screenOffAllowlistEnabled
    val screenOffAllowedPackages: Flow<Set<String>> = store.screenOffAllowedPackages
    val screenOnAllowlistEnabled: Flow<Boolean> = store.screenOnAllowlistEnabled
    val screenOnAllowedPackages: Flow<Set<String>> = store.screenOnAllowedPackages

    suspend fun toggleBlock(packageName: String, blocked: Boolean) {
        store.setBlocked(packageName, blocked)
    }

    suspend fun setTravelMode(enabled: Boolean) {
        store.setTravelMode(enabled)
    }

    suspend fun setScreenOffAllowlistEnabled(enabled: Boolean) {
        store.setScreenOffAllowlistEnabled(enabled)
    }

    suspend fun setScreenOffAllowed(packageName: String, allowed: Boolean) {
        store.setScreenOffAllowed(packageName, allowed)
    }

    suspend fun setScreenOnAllowlistEnabled(enabled: Boolean) {
        store.setScreenOnAllowlistEnabled(enabled)
    }

    suspend fun setScreenOnAllowed(packageName: String, allowed: Boolean) {
        store.setScreenOnAllowed(packageName, allowed)
    }
}
