package com.shimonhoter.datatrafficguard.policy

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.policyDataStore by preferencesDataStore(name = "policy_store")
private val BLOCKED_PACKAGES_KEY = stringSetPreferencesKey("blocked_packages")

/** Persists per-package manual block/allow choices across app restarts. */
class PolicyStore(private val context: Context) {
    val blockedPackages: Flow<Set<String>> = context.policyDataStore.data
        .map { prefs -> prefs[BLOCKED_PACKAGES_KEY] ?: emptySet() }

    suspend fun setBlocked(packageName: String, blocked: Boolean) {
        context.policyDataStore.edit { prefs ->
            val current = prefs[BLOCKED_PACKAGES_KEY] ?: emptySet()
            prefs[BLOCKED_PACKAGES_KEY] = if (blocked) current + packageName else current - packageName
        }
    }
}

/**
 * Manual policy only (step 4). Travel Mode / foreground-only auto logic
 * (step 5) will layer on top of this later via ForegroundWatcherService.
 */
class PolicyEngine(context: Context) {
    private val store = PolicyStore(context)
    val blockedPackages: Flow<Set<String>> = store.blockedPackages

    suspend fun toggleBlock(packageName: String, blocked: Boolean) {
        store.setBlocked(packageName, blocked)
    }
}
