package com.shimonhoter.datatrafficguard.vpn

import android.content.Context
import android.content.Intent
import android.os.Build

object VpnServiceLauncher {
    fun launch(
        context: Context,
        travelModeEnabled: Boolean,
        blocked: Set<String>,
        screenOffAllowlistEnabled: Boolean = false,
        screenOffAllowedPackages: Set<String> = emptySet(),
        screenOnAllowlistEnabled: Boolean = false,
        screenOnAllowedPackages: Set<String> = emptySet()
    ) {
        val serviceIntent = Intent(context, VpnGuardService::class.java).apply {
            putExtra(VpnGuardService.EXTRA_MODE, if (travelModeEnabled) "travel" else "manual")
            putStringArrayListExtra(VpnGuardService.EXTRA_BLOCKED_PACKAGES, ArrayList(blocked))
            putExtra(VpnGuardService.EXTRA_SCREEN_OFF_ENABLED, screenOffAllowlistEnabled)
            putStringArrayListExtra(VpnGuardService.EXTRA_SCREEN_OFF_ALLOWED, ArrayList(screenOffAllowedPackages))
            putExtra(VpnGuardService.EXTRA_SCREEN_ON_ENABLED, screenOnAllowlistEnabled)
            putStringArrayListExtra(VpnGuardService.EXTRA_SCREEN_ON_ALLOWED, ArrayList(screenOnAllowedPackages))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
