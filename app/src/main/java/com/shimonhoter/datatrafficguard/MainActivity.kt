package com.shimonhoter.datatrafficguard

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.shimonhoter.datatrafficguard.monitor.AppUsageSnapshot
import com.shimonhoter.datatrafficguard.monitor.DataUsageRepository
import com.shimonhoter.datatrafficguard.monitor.hasUsageAccess
import com.shimonhoter.datatrafficguard.policy.PolicyEngine
import com.shimonhoter.datatrafficguard.quota.QuotaEngine
import com.shimonhoter.datatrafficguard.quota.QuotaSettings
import com.shimonhoter.datatrafficguard.ui.theme.DataTrafficGuardTheme
import com.shimonhoter.datatrafficguard.vpn.GuardMode
import com.shimonhoter.datatrafficguard.vpn.VpnGuardService
import com.shimonhoter.datatrafficguard.vpn.VpnServiceLauncher
import com.shimonhoter.datatrafficguard.vpn.VpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var pendingTravelMode = false
    private var pendingScreenOffEnabled = false
    private var pendingScreenOffAllowed: Set<String> = emptySet()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            if (pendingTravelMode) {
                startTravelModeService(pendingScreenOffEnabled, pendingScreenOffAllowed)
            } else {
                startGuardService(pendingBlocked, pendingScreenOffEnabled, pendingScreenOffAllowed)
            }
        }
    }

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
        val quotaEngine = QuotaEngine(applicationContext)
        val quotaStateFlow = quotaEngine.settings.stateIn(
            lifecycleScope, SharingStarted.Eagerly, QuotaSettings()
        )
        val usageFlow = repository.observeUsage(
            cycleStartMillisProvider = { quotaStateFlow.value.cycleStartMillis }
        )

        setContent {
            DataTrafficGuardTheme {
                val granted by usageAccessGranted
                if (granted) {
                    val usage by usageFlow.collectAsState(initial = emptyList())
                    val blockedPackages by policyEngine.blockedPackages.collectAsState(initial = emptySet())
                    val travelModeEnabled by policyEngine.travelModeEnabled.collectAsState(initial = false)
                    val vpnStatus by VpnGuardService.status.collectAsState()
                    val quotaSettings by quotaEngine.settings.collectAsState(initial = QuotaSettings())
                    val screenOffAllowlistEnabled by policyEngine.screenOffAllowlistEnabled.collectAsState(initial = false)
                    val screenOffAllowedPackages by policyEngine.screenOffAllowedPackages.collectAsState(initial = emptySet())

                    val totalUsedBytes by produceState(initialValue = 0L, quotaSettings.cycleStartMillis) {
                        while (true) {
                            val enforced = vpnStatus.enforcedPackages
                            val allowedPackages = usage.map { it.packageName }.filterNot { enforced.contains(it) }.toSet()
                            value = withContext(Dispatchers.Default) {
                                repository.bytesForPackagesSince(allowedPackages, quotaSettings.cycleStartMillis)
                            }
                            delay(5000L)
                        }
                    }

                    LaunchedEffect(travelModeEnabled, blockedPackages, screenOffAllowlistEnabled, screenOffAllowedPackages) {
                        applyPolicy(travelModeEnabled, blockedPackages, screenOffAllowlistEnabled, screenOffAllowedPackages)
                    }

                    DashboardScaffold(
                        usage = usage,
                        blockedPackages = blockedPackages,
                        travelModeEnabled = travelModeEnabled,
                        vpnStatus = vpnStatus,
                        quotaSettings = quotaSettings,
                        totalUsedBytes = totalUsedBytes,
                        screenOffAllowlistEnabled = screenOffAllowlistEnabled,
                        screenOffAllowedPackages = screenOffAllowedPackages,
                        onToggleBlocked = { pkg, blocked ->
                            lifecycleScope.launch { policyEngine.toggleBlock(pkg, blocked) }
                        },
                        onTravelModeToggled = { enabled ->
                            lifecycleScope.launch { policyEngine.setTravelMode(enabled) }
                        },
                        onSetQuota = { quotaBytes, thresholdPercent ->
                            lifecycleScope.launch { quotaEngine.setQuota(quotaBytes, thresholdPercent) }
                        },
                        onResetCycle = {
                            lifecycleScope.launch { quotaEngine.resetCycle() }
                        },
                        onScreenOffAllowlistToggled = { enabled ->
                            lifecycleScope.launch { policyEngine.setScreenOffAllowlistEnabled(enabled) }
                        },
                        onToggleScreenOffAllowed = { pkg, allowed ->
                            lifecycleScope.launch { policyEngine.setScreenOffAllowed(pkg, allowed) }
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

    private fun applyPolicy(
        travelModeEnabled: Boolean,
        blocked: Set<String>,
        screenOffAllowlistEnabled: Boolean,
        screenOffAllowedPackages: Set<String>
    ) {
        if (travelModeEnabled) {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                pendingTravelMode = true
                pendingScreenOffEnabled = screenOffAllowlistEnabled
                pendingScreenOffAllowed = screenOffAllowedPackages
                vpnPermissionLauncher.launch(intent)
            } else {
                startTravelModeService(screenOffAllowlistEnabled, screenOffAllowedPackages)
            }
            return
        }
        if (blocked.isEmpty() && !screenOffAllowlistEnabled) {
            startGuardService(emptySet(), false, emptySet())
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingTravelMode = false
            pendingBlocked = blocked
            pendingScreenOffEnabled = screenOffAllowlistEnabled
            pendingScreenOffAllowed = screenOffAllowedPackages
            vpnPermissionLauncher.launch(intent)
        } else {
            startGuardService(blocked, screenOffAllowlistEnabled, screenOffAllowedPackages)
        }
    }

    private fun startTravelModeService(screenOffEnabled: Boolean, screenOffAllowed: Set<String>) {
        VpnServiceLauncher.launch(
            this,
            travelModeEnabled = true,
            blocked = emptySet(),
            screenOffAllowlistEnabled = screenOffEnabled,
            screenOffAllowedPackages = screenOffAllowed
        )
    }

    private fun startGuardService(blocked: Set<String>, screenOffEnabled: Boolean, screenOffAllowed: Set<String>) {
        VpnServiceLauncher.launch(
            this,
            travelModeEnabled = false,
            blocked = blocked,
            screenOffAllowlistEnabled = screenOffEnabled,
            screenOffAllowedPackages = screenOffAllowed
        )
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
    travelModeEnabled: Boolean = false,
    vpnStatus: VpnStatus = VpnStatus(tunnelActive = false),
    quotaSettings: QuotaSettings = QuotaSettings(),
    totalUsedBytes: Long = 0L,
    screenOffAllowlistEnabled: Boolean = false,
    screenOffAllowedPackages: Set<String> = emptySet(),
    onToggleBlocked: (String, Boolean) -> Unit = { _, _ -> },
    onTravelModeToggled: (Boolean) -> Unit = {},
    onSetQuota: (Long, Int) -> Unit = { _, _ -> },
    onResetCycle: () -> Unit = {},
    onScreenOffAllowlistToggled: (Boolean) -> Unit = {},
    onToggleScreenOffAllowed: (String, Boolean) -> Unit = { _, _ -> }
) {
    var searchQuery by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf(CategoryFilter.ALL) }
    var activeOnly by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(SortMode.TOTAL_DESC) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var showQuotaDialog by remember { mutableStateOf(false) }
    var showScreenOffDialog by remember { mutableStateOf(false) }

    val displayedUsage = remember(usage, searchQuery, categoryFilter, activeOnly, sortMode) {
        usage
            .filter { app ->
                when (categoryFilter) {
                    CategoryFilter.ALL -> true
                    CategoryFilter.USER_APPS -> !app.isSystemApp
                    CategoryFilter.SYSTEM_APPS -> app.isSystemApp
                }
            }
            .filter { app -> !activeOnly || app.rxBytesPerSecond > 0 || app.txBytesPerSecond > 0 }
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

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("מצב נסיעה", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "רשת זמינה רק לאפליקציה שבחזית",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(checked = travelModeEnabled, onCheckedChange = onTravelModeToggled)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    if (!travelModeEnabled) {
                        Text(
                            "${blockedPackages.size} אפליקציות מסומנות לחסימה ידנית",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when {
                            vpnStatus.tunnelActive && vpnStatus.mode == GuardMode.TRAVEL ->
                                "פעיל · מותר: ${vpnStatus.currentForegroundApp ?: "לא ידוע"} · ${vpnStatus.enforcedPackages.size} חסומות"
                            vpnStatus.tunnelActive ->
                                "מנהרה פעילה · ${vpnStatus.enforcedPackages.size} אפליקציות נאכפות בפועל"
                            else ->
                                "מנהרה לא פעילה" + (vpnStatus.lastError?.let { " · שגיאה: $it" } ?: "")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (vpnStatus.tunnelActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("הגבלה כשהמסך כבוי", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "רק אפליקציות נבחרות ישמרו על גישה לרשת כשהמסך כבוי — פועל גם במצב נסיעה וגם במצב ידני",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(checked = screenOffAllowlistEnabled, onCheckedChange = onScreenOffAllowlistToggled)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${screenOffAllowedPackages.size} אפליקציות מורשות כשהמסך כבוי",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = { showScreenOffDialog = true }) { Text("ערוך רשימה") }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            QuotaCard(
                quotaSettings = quotaSettings,
                usedBytes = totalUsedBytes,
                onEditClick = { showQuotaDialog = true },
                onResetCycle = onResetCycle
            )

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryFilterControl(
                        selected = categoryFilter,
                        expanded = categoryMenuExpanded,
                        onExpandedChange = { categoryMenuExpanded = it },
                        onSelect = { categoryFilter = it }
                    )
                    ActiveOnlyToggle(
                        active = activeOnly,
                        onToggle = { activeOnly = it }
                    )
                }
                SortControl(
                    selected = sortMode,
                    expanded = sortMenuExpanded,
                    onExpandedChange = { sortMenuExpanded = it },
                    onSelect = { sortMode = it }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "${displayedUsage.size} אפליקציות" +
                    (if (activeOnly) " · פעילות כרגע בלבד" else "") +
                    (if (travelModeEnabled) " · חסימה ידנית מושבתת בזמן מצב נסיעה" else ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = { displayedUsage.forEach { onToggleBlocked(it.packageName, true) } },
                    enabled = !travelModeEnabled
                ) { Text("חסום הכל המוצג") }
                TextButton(
                    onClick = { displayedUsage.forEach { onToggleBlocked(it.packageName, false) } },
                    enabled = !travelModeEnabled
                ) { Text("שחרר הכל המוצג") }
            }
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(displayedUsage, key = { it.uid }) { app ->
                    AppUsageRow(
                        app = app,
                        isBlocked = blockedPackages.contains(app.packageName),
                        enabled = !travelModeEnabled,
                        onToggleBlocked = { blocked -> onToggleBlocked(app.packageName, blocked) }
                    )
                }
            }
        }
    }

    if (showQuotaDialog) {
        QuotaEditDialog(
            quotaSettings = quotaSettings,
            onDismiss = { showQuotaDialog = false },
            onSave = { quotaBytes, thresholdPercent ->
                onSetQuota(quotaBytes, thresholdPercent)
                showQuotaDialog = false
            }
        )
    }

    if (showScreenOffDialog) {
        ScreenOffAllowlistDialog(
            apps = usage,
            allowedPackages = screenOffAllowedPackages,
            onDismiss = { showScreenOffDialog = false },
            onToggle = onToggleScreenOffAllowed
        )
    }
}

@Composable
private fun CategoryFilterControl(
    selected: CategoryFilter,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (CategoryFilter) -> Unit
) {
    Box {
        TextButton(onClick = { onExpandedChange(true) }) {
            Icon(Icons.Filled.FilterAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(selected.label, style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            CategoryFilter.entries.forEach { filter ->
                DropdownMenuItem(
                    text = { Text(filter.label) },
                    onClick = { onSelect(filter); onExpandedChange(false) },
                    leadingIcon = {
                        if (filter == selected) Icon(Icons.Filled.Check, contentDescription = null)
                    }
                )
            }
        }
    }
}

@Composable
private fun ActiveOnlyToggle(active: Boolean, onToggle: (Boolean) -> Unit) {
    TextButton(onClick = { onToggle(!active) }) {
        Icon(
            imageVector = if (active) Icons.Filled.Bolt else Icons.Outlined.Bolt,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            "פעילות בלבד",
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current
        )
    }
}

@Composable
private fun SortControl(
    selected: SortMode,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (SortMode) -> Unit
) {
    Box {
        IconButton(onClick = { onExpandedChange(true) }) {
            Icon(Icons.Filled.Sort, contentDescription = "מיון: ${selected.label}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    onClick = { onSelect(mode); onExpandedChange(false) },
                    leadingIcon = {
                        if (mode == selected) Icon(Icons.Filled.Check, contentDescription = null)
                    }
                )
            }
        }
    }
}

@Composable
private fun AppUsageRow(
    app: AppUsageSnapshot,
    isBlocked: Boolean,
    enabled: Boolean,
    onToggleBlocked: (Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                if (app.unsupported) {
                    Text("לא זמין", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (isBlocked) {
                    Text(
                        "חסום — נתוני צריכה לא מוצגים (לרוב ניסיונות שנחסמו, לא נתונים אמיתיים)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (app.rxBytesPerSecond > 0 || app.txBytesPerSecond > 0) {
                        val context = LocalContext.current
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.parse("package:${app.packageName}")
                                    }
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                        ) { Text("עדיין רואה קצב חי? עצור בכל זאת (Force Stop)", style = MaterialTheme.typography.labelSmall) }
                    }
                } else {
                    Text(
                        "היום: ${formatBytes(app.totalBytesToday)} · מצטבר מתחילת המחזור: ${formatBytes(app.totalBytesSinceCycleStart)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "↓${formatBytes(app.rxBytesPerSecond)}/ש  ↑${formatBytes(app.txBytesPerSecond)}/ש",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(checked = isBlocked, onCheckedChange = onToggleBlocked, enabled = enabled)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1fGB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1fMB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1fKB".format(bytes / 1_000.0)
    else -> "${bytes}B"
}

@Composable
private fun QuotaCard(
    quotaSettings: QuotaSettings,
    usedBytes: Long,
    onEditClick: () -> Unit,
    onResetCycle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!quotaSettings.isConfigured) {
                Text(
                    "צריכה כוללת: לא הוגדרה מכסה",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onEditClick, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("הגדר מכסה", style = MaterialTheme.typography.labelMedium)
                }
                return@Row
            }

            val percent = ((usedBytes.toDouble() / quotaSettings.quotaBytes.toDouble()) * 100)
                .toInt().coerceAtLeast(0)
            val overThreshold = percent >= quotaSettings.thresholdPercent

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${formatBytes(usedBytes)} / ${formatBytes(quotaSettings.quotaBytes)} · $percent%" +
                        " · סף ${quotaSettings.thresholdPercent}%(+10%)",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (percent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (overThreshold) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onEditClick, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("ערוך", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = onResetCycle, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("אפס", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun QuotaEditDialog(
    quotaSettings: QuotaSettings,
    onDismiss: () -> Unit,
    onSave: (Long, Int) -> Unit
) {
    var amountText by remember {
        mutableStateOf(
            if (quotaSettings.quotaBytes > 0L) (quotaSettings.quotaBytes / 1_000_000L).toString() else ""
        )
    }
    var thresholdText by remember { mutableStateOf(quotaSettings.thresholdPercent.toString()) }

    val amountMb = amountText.toLongOrNull()
    val thresholdPercent = thresholdText.toIntOrNull()
    val isValid = amountMb != null && amountMb > 0 && thresholdPercent != null &&
        thresholdPercent in 1..99

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("הגדרת מכסת נתונים") },
        text = {
            Column {
                Text(
                    "הזן את כמות הנתונים הכוללת של החבילה (ב-MB) ואת אחוז הסף להתראה ראשונה. " +
                        "משם והלאה תישלח התראה נוספת כל 10%.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter(Char::isDigit) },
                    label = { Text("כמות נתונים (MB)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = thresholdText,
                    onValueChange = { thresholdText = it.filter(Char::isDigit) },
                    label = { Text("סף התראה ראשונה (%)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = { onSave((amountMb!!) * 1_000_000L, thresholdPercent!!) }
            ) { Text("שמור והתחל מחזור חדש") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול") }
        }
    )
}


@Composable
private fun ScreenOffAllowlistDialog(
    apps: List<AppUsageSnapshot>,
    allowedPackages: Set<String>,
    onDismiss: () -> Unit,
    onToggle: (String, Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("אפליקציות מורשות כשהמסך כבוי") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(apps.sortedBy { it.label.lowercase() }, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(app.label, style = MaterialTheme.typography.bodyMedium)
                        Checkbox(
                            checked = allowedPackages.contains(app.packageName),
                            onCheckedChange = { checked -> onToggle(app.packageName, checked) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("סגור") }
        }
    )
}
