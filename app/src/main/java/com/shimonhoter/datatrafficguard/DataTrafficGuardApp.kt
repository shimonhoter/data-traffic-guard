package com.shimonhoter.datatrafficguard

import android.app.Application
import android.net.VpnService
import android.util.Log
import com.shimonhoter.datatrafficguard.policy.PolicyEngine
import com.shimonhoter.datatrafficguard.quota.QuotaChecker
import com.shimonhoter.datatrafficguard.quota.QuotaCheckReceiver
import com.shimonhoter.datatrafficguard.vpn.VpnServiceLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Starts the guard service the moment the app process comes alive — whether that's
 * a manual launch, a process the OS recreates in the background, or (via
 * BootCompletedReceiver) right after the device reboots — instead of waiting for
 * MainActivity to be opened by the user.
 *
 * VpnService consent can only be requested from an Activity, so if it hasn't been
 * granted yet this is a no-op here; MainActivity's existing flow still handles that
 * first-time grant.
 */
class DataTrafficGuardApp : Application() {

    private val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        QuotaCheckReceiver.schedule(applicationContext)
        appScope.launch {
            try {
                QuotaChecker.checkAndNotify(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Immediate quota check failed", e)
            }
        }

        appScope.launch {
            try {
                val policyEngine = PolicyEngine(applicationContext)
                val travelModeEnabled = policyEngine.travelModeEnabled.first()
                val blocked = policyEngine.blockedPackages.first()

                if (!travelModeEnabled && blocked.isEmpty()) {
                    Log.i(TAG, "No active policy on process start — guard stays idle until configured.")
                    return@launch
                }

                if (VpnService.prepare(applicationContext) != null) {
                    Log.w(TAG, "VPN consent not granted yet — waiting for MainActivity to request it.")
                    return@launch
                }

                VpnServiceLauncher.launch(applicationContext, travelModeEnabled, blocked)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-start guard service on process creation", e)
            }
        }
    }

    companion object {
        private const val TAG = "DataTrafficGuardApp"
    }
}
