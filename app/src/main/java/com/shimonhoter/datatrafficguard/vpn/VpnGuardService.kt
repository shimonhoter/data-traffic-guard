package com.shimonhoter.datatrafficguard.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shimonhoter.datatrafficguard.MainActivity
import java.io.FileInputStream
import java.io.IOException

/**
 * VPN-based traffic guard — "black hole" approach.
 *
 * Only packages we actually want to BLOCK are added to the tunnel via
 * addAllowedApplication(). Everyone else (including this app itself, which is
 * never added) bypasses the VPN entirely and gets completely normal internet.
 * Packets that do enter the tunnel are read and discarded — those apps simply
 * get no responses, i.e. no network.
 *
 * If blockedPackages is empty, no tunnel is established at all: the guard is
 * fully idle and nothing on the device is affected.
 */
class VpnGuardService : VpnService() {

    companion object {
        const val EXTRA_BLOCKED_PACKAGES = "blocked_packages"
        private const val CHANNEL_ID = "vpn_guard_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "VpnGuardService"
    }

    private var tunnel: ParcelFileDescriptor? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val blocked = intent?.getStringArrayListExtra(EXTRA_BLOCKED_PACKAGES)?.toSet() ?: emptySet()
        rebuild(blocked)
        return START_STICKY
    }

    fun rebuild(blockedPackages: Set<String>) {
        closeTunnel()

        if (blockedPackages.isEmpty()) {
            Log.i(TAG, "No blocked packages — guard idle, network fully untouched.")
            return
        }

        val builder = Builder()
            .setSession("DataTrafficGuard")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")

        var addedAny = false
        for (pkg in blockedPackages) {
            if (pkg == packageName) continue // never block ourselves
            try {
                builder.addAllowedApplication(pkg)
                addedAny = true
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Package not found, skipping: $pkg")
            }
        }
        if (!addedAny) return

        tunnel = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish tunnel", e)
            null
        }
        tunnel?.let { startDrainThread(it) }
    }

    /** Reads and discards packets from blocked apps — they get no responses at all. */
    private fun startDrainThread(fd: ParcelFileDescriptor) {
        running = true
        drainThread = Thread {
            val input = FileInputStream(fd.fileDescriptor)
            val buffer = ByteArray(32 * 1024)
            try {
                while (running) {
                    if (input.read(buffer) < 0) break
                }
            } catch (e: IOException) {
                // expected when the tunnel closes during rebuild/stop
            }
        }.apply { isDaemon = true; start() }
    }

    private fun closeTunnel() {
        running = false
        drainThread?.interrupt()
        drainThread = null
        tunnel?.let { try { it.close() } catch (e: IOException) {} }
        tunnel = null
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "הגנת נתונים", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("DataTrafficGuard פעיל")
            .setContentText("שומר על צריכת הנתונים שלך")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        closeTunnel()
        super.onDestroy()
    }

    override fun onRevoke() {
        closeTunnel()
        super.onRevoke()
    }
}
