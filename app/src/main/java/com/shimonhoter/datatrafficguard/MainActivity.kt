package com.shimonhoter.datatrafficguard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shimonhoter.datatrafficguard.ui.theme.DataTrafficGuardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DataTrafficGuardTheme {
                DashboardScaffold()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScaffold() {
    var travelModeEnabled by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("DataTrafficGuard") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
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
                    Switch(
                        checked = travelModeEnabled,
                        onCheckedChange = { travelModeEnabled = it }
                    )
                }
            }

            Text(
                "כאן תופיע רשימת האפליקציות וצריכת הנתונים שלהן בזמן אמת — " +
                    "שלב הבא: DataUsageRepository + PolicyEngine.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
