package com.shimonhoter.datatrafficguard.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor

/**
 * VPN-based traffic guard.
 *
 * Does NOT route traffic to any external server. It only exists so we can use
 * VpnService.Builder#addAllowedApplication / #addDisallowedApplication to decide,
 * per package, whether an app gets network access at all.
 *
 * Whenever the allow-list needs to change (manual toggle, or foreground app changed
 * in "Travel Mode"), call rebuild(allowedPackages) — this tears down and re-establishes
 * the tunnel with the new list. Expect a brief (tens of ms) reconnect for all apps.
 *
 * TODO (step 2): implement establish()/rebuild(), wire up to PolicyEngine.
 */
class VpnGuardService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null

    fun rebuild(allowedPackages: Set<String>) {
        // TODO: tunnel?.close(); Builder().setSession(...).addAllowedApplication(...)....establish()
    }

    override fun onDestroy() {
        tunnel?.close()
        super.onDestroy()
    }
}
