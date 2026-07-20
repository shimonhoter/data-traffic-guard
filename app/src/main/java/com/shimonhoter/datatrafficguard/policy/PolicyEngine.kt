package com.shimonhoter.datatrafficguard.policy

/**
 * Decides, for a given package, whether it should currently have network access.
 *
 * Two modes:
 *  - Manual: per-package allow/block set by the user (see PolicyStore).
 *  - Travel Mode (foreground-only): only the package reported as foreground by
 *    ForegroundWatcherService is allowed, plus a fixed whitelist (phone, SMS, nav).
 *
 * TODO (step 4/5): combine PolicyStore state + ForegroundWatcherService output,
 * emit the allowed-package set that VpnGuardService.rebuild() should apply.
 */
class PolicyEngine

/**
 * Persists per-package user settings (blocked/allowed, whitelist) via DataStore.
 * TODO (step 4): implement with androidx.datastore.preferences.
 */
class PolicyStore
