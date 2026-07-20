package com.shimonhoter.datatrafficguard

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shimonhoter.datatrafficguard.ui.theme.DataTrafficGuardTheme
import com.shimonhoter.datatrafficguard.vpn.VpnGuardService

class MainActivity : ComponentActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startGuardService(emptySet()) // step 2: idle guard, proves the mechanism doesn't break internet
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* not critical if denied — service still runs, just no visible notification */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            DataTrafficGuardTheme {
                DashboardScaffold(
                    onTravelModeToggled = { enabled ->
                        if (enabled) requestVpnPermission() else stopGuardService()
                    }
                )
            }
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScaffold(onTravelModeToggled: (Boolean) -> Unit = {}) {
    var travelModeEnabled by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("DataTrafficGuard") }) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("מצב נסיעה", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (travelModeEnabled) "השירות פעיל (שלב 2: ללא חסימה עדיין)" else "כבוי",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = travelModeEnabled,
                        onCheckedChange = {
                            travelModeEnabled = it
                            onTravelModeToggled(it)
                        }
                    )
                }
            }
            Text(
                "בדיקת שלב 2: הפעל את המתג, אשר את דיאלוג ה-VPN, וודא שהאינטרנט " +
                    "ממשיך לעבוד רגיל בכל האפליקציות (כולל האפליקציה הזו).",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
