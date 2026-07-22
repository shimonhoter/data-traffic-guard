package com.shimonhoter.datatrafficguard.vpn

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Builds and starts VpnGuardService with the right mode/extras.
 * Shared by MainActivity (user toggles a switch) and DataTrafficGuardApp
 * (auto-start the moment the process comes alive), so the two paths can't drift apart.
 */
object VpnServiceLauncher {
    fun launch(
        context: Context,
        travelModeEnabled: Boolean,
        blocked: Set<String>,
        screenOffAllowlistEnabled: Boolean = false,
        screenOffAllowedPackages: Set<String> = emptySet()
    ) {
        val serviceIntent = Intent(context, VpnGuardService::class.java).apply {
            if (travelModeEnabled) {
                putExtra(VpnGuardService.EXTRA_MODE, "travel")
            } else {
                putExtra(VpnGuardService.EXTRA_MODE, "manual")
                putStringArrayListExtra(VpnGuardService.EXTRA_BLOCKED_PACKAGES, ArrayList(blocked))
            }
            putExtra(VpnGuardService.EXTRA_SCREEN_OFF_ENABLED, screenOffAllowlistEnabled)
            putStringArrayListExtra(VpnGuardService.EXTRA_SCREEN_OFF_ALLOWED, ArrayList(screenOffAllowedPackages))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
