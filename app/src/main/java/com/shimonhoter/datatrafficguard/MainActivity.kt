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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.shimonhoter.datatrafficguard.monitor.AppUsageSnapshot
import com.shimonhoter.datatrafficguard.monitor.DataUsageRepository
import com.shimonhoter.datatrafficguard.monitor.hasUsageAccess
import com.shimonhoter.datatrafficguard.policy.PolicyEngine
import com.shimonhoter.datatrafficguard.ui.theme.DataTrafficGuardTheme
import com.shimonhoter.datatrafficguard.vpn.VpnGuardService
import kotlinx.coroutines.launch

enum class SortMode(val label: String) {
    NAME_ASC("שם (א-ב)"),
    DOWNLOAD_DESC("קצב הורדה (גבוה-נמוך)"),
    UPLOAD_DESC("קצב העלאה (גבוה-נמוך)"),
    TOTAL_DESC("סה\"כ מצטבר (גבוה-נמוך)")
}

enum class CategoryFilter(val label: String) {
    ALL("הכל"),
    USER_APPS("אפליקציות משתמש"),
    SYSTEM_APPS("אפליקציות מערכת/יצרן")
}

class MainActivity : ComponentActivity() {

    private val usageAccessGranted = mutableStateOf(false)
    private var pendingBlocked: Set<String> = emptySet()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> if (result.resultCode == RESULT_OK) startGuardService(pendingBlocked) }

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
        val policyEngine = PolicyEngine(applicationContext)

        setContent {
            DataTrafficGuardTheme {
                val granted by usageAccessGranted
                if (granted) {
                    val usage by repository.observeUsage().collectAsState(initial = emptyList())
                    val blockedPackages by policyEngine.blockedPackages.collectAsState(initial = emptySet())

                    LaunchedEffect(blockedPackages) { applyBlockedPackages(blockedPackages) }

                    DashboardScaffold(
                        usage = usage,
                        blockedPackages = blockedPackages,
                        onToggleBlocked = { pkg, blocked ->
                            lifecycleScope.launch { policyEngine.toggleBlock(pkg, blocked) }
                        }
                    )
                } else {
                    UsageAccessRequestScreen(
                        onOpenSettings = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        usageAccessGranted.value = hasUsageAccess(this)
    }

    /** Ensures the VPN guard reflects the current manually-blocked set. */
    private fun applyBlockedPackages(blocked: Set<String>) {
        if (blocked.isEmpty()) {
            stopGuardService()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingBlocked = blocked
            vpnPermissionLauncher.launch(intent)
        } else {
            startGuardService(blocked)
        }
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
                "כדי להציג צריכת נתונים לפי אפליקציה, האפליקציה זקוקה להרשאת \"גישה לנתוני שימוש\".",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onOpenSettings) { Text("פתח הגדרות") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScaffold(
    usage: List<AppUsageSnapshot> = emptyList(),
    blockedPackages: Set<String> = emptySet(),
    onToggleBlocked: (String, Boolean) -> Unit = { _, _ -> }
) {
    var searchQuery by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf(CategoryFilter.ALL) }
    var sortMode by remember { mutableStateOf(SortMode.TOTAL_DESC) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    val displayedUsage = remember(usage, searchQuery, categoryFilter, sortMode) {
        usage
            .filter { app ->
                when (categoryFilter) {
                    CategoryFilter.ALL -> true
                    CategoryFilter.USER_APPS -> !app.isSystemApp
                    CategoryFilter.SYSTEM_APPS -> app.isSystemApp
                }
            }
            .filter { app -> searchQuery.isBlank() || app.label.contains(searchQuery, ignoreCase = true) }
            .let { list ->
                when (sortMode) {
                    SortMode.NAME_ASC -> list.sortedBy { it.label.lowercase() }
                    SortMode.DOWNLOAD_DESC -> list.sortedByDescending { it.rxBytesPerSecond }
                    SortMode.UPLOAD_DESC -> list.sortedByDescending { it.txBytesPerSecond }
                    SortMode.TOTAL_DESC -> list.sortedByDescending { it.totalBytesToday }
                }
            }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("DataTrafficGuard") }) }) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("חסימות פעילות", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${blockedPackages.size} אפליקציות חסומות · מצב אוטומטי יתווסף בשלב 5",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("חיפוש אפליקציה...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CategoryFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = categoryFilter == filter,
                            onClick = { categoryFilter = filter },
                            label = { Text(filter.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Box {
                    IconButton(onClick = { sortMenuExpanded = true }) {
                        Icon(Icons.Filled.Sort, contentDescription = "מיון")
                    }
                    DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                        SortMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.label) },
                                onClick = { sortMode = mode; sortMenuExpanded = false }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "מיון: ${sortMode.label}  ·  ${displayedUsage.size} אפליקציות",
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(displayedUsage, key = { it.uid }) { app ->
                    AppUsageRow(
                        app = app,
                        isBlocked = blockedPackages.contains(app.packageName),
                        onToggleBlocked = { blocked -> onToggleBlocked(app.packageName, blocked) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppUsageRow(
    app: AppUsageSnapshot,
    isBlocked: Boolean,
    onToggleBlocked: (Boolean) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (app.unsupported) "לא זמין" else "היום: ${formatBytes(app.totalBytesToday)}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (!app.unsupported) {
                    Text(
                        "↓${formatBytes(app.rxBytesPerSecond)}/ש  ↑${formatBytes(app.txBytesPerSecond)}/ש",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("חסום", style = MaterialTheme.typography.labelSmall)
                Switch(checked = isBlocked, onCheckedChange = onToggleBlocked)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1fGB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1fMB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1fKB".format(bytes / 1_000.0)
    else -> "${bytes}B"
}
