package com.shimonhoter.datatrafficguard

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shimonhoter.datatrafficguard.monitor.AppUsageSnapshot
import com.shimonhoter.datatrafficguard.monitor.DataUsageRepository
import com.shimonhoter.datatrafficguard.monitor.hasUsageAccess
import com.shimonhoter.datatrafficguard.ui.theme.DataTrafficGuardTheme
import com.shimonhoter.datatrafficguard.vpn.VpnGuardService

class MainActivity : ComponentActivity() {

    private val usageAccessGranted = mutableStateOf(false)

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> if (result.resultCode == RESULT_OK) startGuardService(emptySet()) }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val repository = DataUsageRepository(applicationContext)
        setContent {
            DataTrafficGuardTheme {
                val granted by usageAccessGranted
                if (granted) {
                    val usage by repository.observeUsage().collectAsState(initial = emptyList())
                    DashboardScaffold(
                        usage = usage,
                        onTravelModeToggled = { enabled ->
                            if (enabled) requestVpnPermission() else stopGuardService()
                        }
                    )
                } else {
                    UsageAccessRequestScreen(
                        onOpenSettings = {
                            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        usageAccessGranted.value = hasUsageAccess(this)
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent) else startGuardService(emptySet())
    }

    private fun startGuardService(blocked: Set<String>) {
        val serviceIntent = Intent(this, VpnGuardService::class.java).apply {
            putStringArrayListExtra(VpnGuardService.EXTRA_BLOCKED_PACKAGES, ArrayList(blocked))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)
    }

    private fun stopGuardService() {
        stopService(Intent(this, VpnGuardService::class.java))
    }
}

@Composable
fun UsageAccessRequestScreen(onOpenSettings: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "כדי להציג צריכת נתונים לפי אפליקציה, האפליקציה זקוקה להרשאת " +
                    "\"גישה לנתוני שימוש\" (Usage Access).",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "בעמוד שייפתח: חפש את DataTrafficGuard ברשימה, ולחץ על המתג להפעלה.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onOpenSettings) {
                Text("פתח הגדרות")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScaffold(
    usage: List<AppUsageSnapshot> = emptyList(),
    onTravelModeToggled: (Boolean) -> Unit = {}
) {
    var travelModeEnabled by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("DataTrafficGuard") }) }) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("מצב נסיעה", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (travelModeEnabled) "פעיל" else "כבוי",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = travelModeEnabled,
                        onCheckedChange = { travelModeEnabled = it; onTravelModeToggled(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("צריכת נתונים לפי אפליקציה", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            if (usage.isEmpty()) {
                Text(
                    "טוען נתונים...",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(usage, key = { it.uid }) { app -> AppUsageRow(app) }
            }
        }
    }
}

@Composable
private fun AppUsageRow(app: AppUsageSnapshot) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (app.unsupported) "לא זמין" else "מאז הפעלה: ${formatBytes(app.totalBytesSinceBoot)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "↓${formatBytes(app.rxBytesPerSecond)}/ש  ↑${formatBytes(app.txBytesPerSecond)}/ש",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1fGB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1fMB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1fKB".format(bytes / 1_000.0)
    else -> "${bytes}B"
}
