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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileInputStream
import java.io.IOException

data class VpnStatus(
    val tunnelActive: Boolean,
    val enforcedPackages: Set<String>,
    val lastError: String? = null
)

class VpnGuardService : VpnService() {

    companion object {
        const val EXTRA_BLOCKED_PACKAGES = "blocked_packages"
        private const val CHANNEL_ID = "vpn_guard_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "VpnGuardService"

        private val _status = MutableStateFlow(VpnStatus(tunnelActive = false, enforcedPackages = emptySet()))
        val status: StateFlow<VpnStatus> = _status.asStateFlow()
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
            _status.value = VpnStatus(tunnelActive = false, enforcedPackages = emptySet())
            return
        }

        val builder = Builder()
            .setSession("DataTrafficGuard")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")

        val actuallyAdded = mutableSetOf<String>()
        for (pkg in blockedPackages) {
            if (pkg == packageName) continue
            try {
                builder.addAllowedApplication(pkg)
                actuallyAdded += pkg
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Package not found, skipping: $pkg")
            }
        }
        if (actuallyAdded.isEmpty()) {
            _status.value = VpnStatus(tunnelActive = false, enforcedPackages = emptySet(), lastError = "אף חבילה לא נמצאה")
            return
        }

        tunnel = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish tunnel", e)
            _status.value = VpnStatus(tunnelActive = false, enforcedPackages = emptySet(), lastError = e.message)
            null
        }

        if (tunnel == null) {
            _status.value = VpnStatus(tunnelActive = false, enforcedPackages = emptySet(), lastError = "establish() החזיר null")
            return
        }

        tunnel?.let { startDrainThread(it) }
        _status.value = VpnStatus(tunnelActive = true, enforcedPackages = actuallyAdded)
    }

    private fun startDrainThread(fd: ParcelFileDescriptor) {
        running = true
        drainThread = Thread {
            val input = FileInputStream(fd.fileDescriptor)
            val buffer = ByteArray(32 * 1024)
            try {
                while (running) {
                    if (input.read(buffer) < 0) break
                }
            } catch (e: IOException) { }
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
        _status.value = VpnStatus(tunnelActive = false, enforcedPackages = emptySet())
        super.onDestroy()
    }

    override fun onRevoke() {
        closeTunnel()
        _status.value = VpnStatus(tunnelActive = false, enforcedPackages = emptySet(), lastError = "הרשאת VPN בוטלה על ידי המשתמש")
        super.onRevoke()
    }
}
