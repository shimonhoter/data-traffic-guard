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
import android.provider.Telephony
import android.telecom.TelecomManager
import android.util.Log
import com.shimonhoter.datatrafficguard.MainActivity
import com.shimonhoter.datatrafficguard.monitor.ForegroundWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.IOException

enum class GuardMode { MANUAL, TRAVEL }

data class VpnStatus(
    val tunnelActive: Boolean,
    val mode: GuardMode = GuardMode.MANUAL,
    val enforcedPackages: Set<String> = emptySet(),
    val currentForegroundApp: String? = null,
    val lastError: String? = null
)

class VpnGuardService : VpnService() {

    companion object {
        const val EXTRA_BLOCKED_PACKAGES = "blocked_packages"
        const val EXTRA_MODE = "mode"
        private const val CHANNEL_ID = "vpn_guard_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "VpnGuardService"
        private const val TRAVEL_POLL_MS = 1500L

        private val _status = MutableStateFlow(VpnStatus(tunnelActive = false))
        val status: StateFlow<VpnStatus> = _status.asStateFlow()
    }

    private var tunnel: ParcelFileDescriptor? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var travelJob: Job? = null
    private val foregroundWatcher = ForegroundWatcher()

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = if (intent?.getStringExtra(EXTRA_MODE) == "travel") GuardMode.TRAVEL else GuardMode.MANUAL

        travelJob?.cancel()
        travelJob = null

        if (mode == GuardMode.TRAVEL) {
            startTravelMode()
        } else {
            val blocked = intent?.getStringArrayListExtra(EXTRA_BLOCKED_PACKAGES)?.toSet() ?: emptySet()
            rebuild(blocked, GuardMode.MANUAL, null)
        }
        return START_STICKY
    }

    private fun startTravelMode() {
        travelJob = serviceScope.launch {
            while (isActive) {
                try {
                    val foreground = foregroundWatcher.currentForegroundPackage(this@VpnGuardService)
                    val whitelist = computeWhitelist()
                    val allNetworkPkgs = allNetworkPackages()
                    val allowed = whitelist + (foreground?.let { setOf(it) } ?: emptySet())
                    val blocked = allNetworkPkgs - allowed
                    rebuild(blocked, GuardMode.TRAVEL, foreground)
                } catch (e: Exception) {
                    Log.e(TAG, "Travel mode tick failed", e)
                }
                delay(TRAVEL_POLL_MS)
            }
        }
    }

    private fun allNetworkPackages(): Set<String> {
        @Suppress("DEPRECATION") val apps = packageManager.getInstalledApplications(0)
        return apps.asSequence()
            .filter { it.uid >= android.os.Process.FIRST_APPLICATION_UID }
            .map { it.packageName }
            .toSet()
    }

    private fun computeWhitelist(): Set<String> {
        val whitelist = mutableSetOf(packageName, "com.android.settings")
        try {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.resolveActivity(launcherIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { whitelist += it }
        } catch (e: Exception) { }
        try {
            (getSystemService(TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage?.let { whitelist += it }
        } catch (e: Exception) { }
        try {
            Telephony.Sms.getDefaultSmsPackage(this)?.let { whitelist += it }
        } catch (e: Exception) { }
        return whitelist
    }

    private fun rebuild(blockedPackages: Set<String>, mode: GuardMode, foregroundApp: String?) {
        closeTunnel()

        if (blockedPackages.isEmpty()) {
            Log.i(TAG, "No blocked packages — guard idle.")
            _status.value = VpnStatus(tunnelActive = false, mode = mode, currentForegroundApp = foregroundApp)
            if (mode == GuardMode.MANUAL) stopSelf()
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
                // uninstalled between listing and building — skip
            }
        }
        if (actuallyAdded.isEmpty()) {
            _status.value = VpnStatus(tunnelActive = false, mode = mode, currentForegroundApp = foregroundApp, lastError = "אין אפליקציות לחסימה")
            return
        }

        tunnel = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish tunnel", e)
            _status.value = VpnStatus(tunnelActive = false, mode = mode, currentForegroundApp = foregroundApp, lastError = e.message)
            null
        }

        if (tunnel == null) {
            _status.value = VpnStatus(tunnelActive = false, mode = mode, currentForegroundApp = foregroundApp, lastError = "establish() החזיר null")
            return
        }

        tunnel?.let { startDrainThread(it) }
        _status.value = VpnStatus(tunnelActive = true, mode = mode, enforcedPackages = actuallyAdded, currentForegroundApp = foregroundApp)
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
        travelJob?.cancel()
        serviceScope.cancel()
        closeTunnel()
        _status.value = VpnStatus(tunnelActive = false)
        super.onDestroy()
    }

    override fun onRevoke() {
        travelJob?.cancel()
        closeTunnel()
        _status.value = VpnStatus(tunnelActive = false, lastError = "הרשאת VPN בוטלה על ידי המשתמש")
        super.onRevoke()
    }
}
